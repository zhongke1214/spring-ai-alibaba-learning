# Spring AI Alibaba ReactAgent 生产级实践：工具调用、结构化输出、HITL 与上下文管理

> 版本基准：**Spring AI Alibaba 1.1.2.x + Spring AI 1.1.2 + JDK 17+**（本文所有 API 以此版本为准）
>
> - 官方文档：https://java2ai.com
> - Hooks 教程：https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks
> - 记忆教程：https://java2ai.com/docs/frameworks/agent-framework/tutorials/memory
> - 官方主仓库：https://github.com/alibaba/spring-ai-alibaba
> - 官方示例：https://github.com/spring-ai-alibaba/examples（含 `HumanInTheLoopExample`、Skills 示例）
>
> 版本跟仓库约定对齐：JDK 17+ / Spring Boot 3.5.7 / Spring AI 1.1.2 / Spring AI Alibaba 1.1.2.2

上一篇把 Checkpoint 换成持久化之后，Agent 已经**重启还能续聊**。这一篇再往上加一层治理：

**危险工具配合人工确认、超长对话自动压缩、工具抖动自动重试、能力按需展开。**

---

## 一、ReactAgent 主循环管不了的那些事

先把 ReactAgent 的主循环摊开看，它其实短得惊人：**模型推理 → 调工具 → 观察结果 → 再推理**，直到模型自己决定停下。

就这么一个小循环，直接丢进生产环境，会撞上一堆它**天生管不了**的问题。原因很简单：主循环只负责"推理和调工具"，至于"该不该调、调几次、调完怎么收尾、花了多少钱"，它一概不管。下面逐个看这些缺口——每个缺口对应一类事故，也对应一个治理组件。

**缺口一：高风险操作没人把关。** 模型在对话里说"好的，我帮你退款"，顺手就调了 `refund(orderId, 199)`，钱当场出去了。它没有"审批"这个概念，只根据提示词和上下文决定下一步动作——你没法靠写一句 prompt 让它学会谨慎。治理手段是在工具执行前插入 `HumanInTheLoopHook`，把高风险调用拦下来，暂停执行，等人确认、改参或拒绝：

```
模型决定调 refund(orderId, 199)
        ↓
HumanInTheLoopHook 拦截
        ↓
等待人工审批（可能是几分钟、几小时）
        ↓
批准 → 继续执行
拒绝 → 返回"已拒绝"作为观察结果
改参 → 用修改后的参数执行
```

这里的关键能力是**中断、恢复、状态持久化**：中断期间 Agent 的完整状态躺在 Checkpoint 里，人审批完再从断点接着跑。这一段等待可能是几小时甚至跨天——主循环自己绝对做不到，它必须依赖上一篇讲的 Saver。

**缺口二：对话越聊越长。** 50 轮之后 messages 里堆了几百条消息，Token 消耗爆炸，模型注意力被稀释，回答质量下滑，再往下就该触发上下文长度上限报错了。治理手段是模型调用前的上下文管理：一是**硬窗口修剪**，按轮次/工具组裁剪，保留最近 N 轮，保证不超限也不破坏消息结构；二是**摘要提质**，把早期对话压缩成一段摘要作为 SystemMessage 放在历史开头，保住关键事实。这活儿归 Hook 而不是主循环，因为主循环只管"把 messages 发给模型"，不管"messages 是不是太长了"——裁剪逻辑放在 Hook 里才能可插拔、可配置。

**缺口三：工具偶发抖动。** 工具超时或抛异常，主循环要么把异常直接抛上去让整个会话崩掉，要么让模型看到失败后反复重试同一个工具，陷入死循环。治理手段有三层：`ToolRetryHook` / `ToolRetryInterceptor` 对瞬时故障自动重试有限次数（带退避）；工具内部捕获异常、返回结构化错误 JSON，让模型看到"失败了"这个观察结果而不是崩掉；`ModelCallLimitHook` 限制模型调用次数，防止空转烧钱。这里有个很重要的设计观念：**工具失败不是致命错误，而是一次可观察的结果**——把异常翻译成模型能看懂的消息，让它自己决定下一步（重试、换工具、还是如实告知用户）。

**缺口四：工具上百个，Prompt 塞爆。** 所有工具的 description 全塞进 System Prompt，几万 token 起步；模型在 100 个工具里挑，选错概率大增，而且每加一个工具都要改 Prompt。治理手段是**动态筛选**和 **Skills 渐进披露**：前者根据当前对话只注入相关工具（问天气就只给 `get_weather`）；后者把工具按技能分组，先给模型看"有哪些技能类别"，它选了类别再展开具体工具。这逻辑必须在模型调用前动态执行、依赖当前对话状态，静态的主循环做不了。

**缺口五：成本失控。** ReAct 循环空转是真实存在的：模型反复推理、调工具、观察、再推理，每一步都是一次 API 调用，一个复杂任务跑 20 轮，成本就是预期的十倍。治理手段是双层限流——`ModelCallLimitHook` 管模型调用次数，`ToolCallLimitHook` 管工具调用次数。取舍在于 `runLimit` 设多大：设太大等于没保护，设太小正常任务被误杀，**经验值是"最复杂正常任务所需步数 × 1.5"**。

**缺口六：出了事看不见。** Agent 跑完只返回一句最终回复，它调了哪些工具、中间怎么推理的、哪一步最慢、哪一步失败了，一概不知，出问题只能靠猜。治理手段就是在关键节点插日志埋点：模型调用前后记录输入输出 token、耗时、模型名；工具调用前后记录工具名、参数、返回、耗时、成败；执行结束时汇总整条轨迹输出结构化日志。

这六个缺口，就是下面所有 Hook 和 Interceptor 存在的理由。带着"我在防什么事故"去看这些组件，比背 API 参数有用得多。

---

## 二、Hook 和 Interceptor：先分清谁干哪种活

官方文档里这两者都能干预 Agent 流程，但职责边界很清楚：

| 特性 | **Hook** | **Interceptor** |
| --- | --- | --- |
| 挂载 | `.hooks(...)` | `.interceptors(...)` |
| 粒度 | Agent / 模型前后 | 单次模型调用 / 单次工具调用 |
| 中断恢复 | **支持**（如 HITL） | 一般不中断整图，只包一层调用 |
| 典型用途 | 审批、摘要、调用上限、PII、Skills 装配 | 重试、日志、缓存、上下文编辑、工具选择 |

判断准则就一句话：**要不要动状态、要不要把整个图停下来**。

HITL、配额、PII 这类操作本质上是在改状态，甚至要把图节点运行直接停掉，这是 Hook 的用武之地。而工具失败重试三次、打个日志、临时改一下请求上下文，这类"包一层就完事"的，交给 Interceptor 更轻快。如果把审批塞进 Interceptor，它和 Checkpoint 对不上，中断恢复会出问题；反过来把简单重试做成 Hook，又重得没必要。

### 2.1 一次调用的执行顺序

多个 Hook 并存时，一整次 `call` / `invoke` 的顺序大致是这样：

```text
1. beforeAgent（按注册顺序）
2. 进入 ReAct 循环
   ├─ beforeModel（按顺序）
   ├─ ModelInterceptor 链（洋葱：外层包内层）→ 真正调模型
   ├─ afterModel（逆序）
   ├─ 若模型要调工具：
   │    ToolInterceptor 链 → 执行工具
   └─ 继续循环或结束
3. afterAgent（逆序）
```

规则和 Spring AI 底座篇讲的 Advisor「洋葱模型」是同一套：

- **`before_*`：正序**（先注册的先跑）
- **`after_*`：逆序**（后注册的先跑）
- **Interceptor：嵌套包装**（第一个 interceptor 在最外层）

挂接方式：

```java
ReactAgent agent = ReactAgent.builder()
        .name("governed_agent")
        .model(chatModel)
        .tools(tools)
        .hooks(loggingHook, summarizationHook, hitlHook)
        .interceptors(toolRetryInterceptor, contextEditingInterceptor)
        .saver(new MemorySaver()) // HITL / 跨轮必须有
        .build();
```

### 2.2 用时间线看清"每次"和"整次"的区别

这是最容易搞混的一点：一次 `invoke` / `call` 里面，ReAct 可能会跑好几轮"模型想 → 可能调工具 → 再想"的小循环。`beforeModel` / ModelInterceptor / `afterModel` **每一轮都会被调用一次**；而 `beforeAgent` / `afterAgent` 不管中间想了几轮、调了几次工具，两头各只跑一次。

假设一次任务是：模型想完 → 调了搜索工具 → 再想一次 → 给出最终答案。

```
beforeAgent                    ← 只这一次

  第 1 轮
  beforeModel → 调模型 → afterModel
  ToolInterceptor → 执行搜索

  第 2 轮
  beforeModel → 调模型 → afterModel
  （没有工具了，循环结束）

afterAgent                     ← 只这一次
```

所以选型就很自然了：

- **剪历史、加系统提示、模型前拦截** → 用 `beforeModel`，每轮模型调用前都会生效；
- **整次任务计时、开头装配 Skills** → 用 `beforeAgent` / `afterAgent`。

### 2.3 自定义时怎么选基类

| 基类 | 适合 | 返回 |
| --- | --- | --- |
| **`MessagesModelHook`**（推荐） | 只动消息列表：修剪、加系统提示、简单摘要 | `AgentCommand` + `UpdatePolicy` |
| **`ModelHook`** | 要读写完整 `OverAllState`、自定义业务字段 | `Map<String, Object>` |
| **`AgentHook`** | Agent 整次开始/结束：计时、资源初始化 | 同上 |
| **`ModelInterceptor`** | 改一次模型请求/响应、动态筛工具 | `ModelResponse` |
| **`ToolInterceptor`** | 改一次工具调用：重试、缓存、监控 | `ToolCallResponse` |

对口选择：

- 只做消息裁剪、塞一条 system → `MessagesModelHook`
- 要改 state 里的业务字段（配额、用户等级）→ `ModelHook`
- 整次调用计时、打开连接 → `AgentHook`
- 模型调用超时重试、按轮次筛选 tool → `ModelInterceptor`
- 工具调用重试、结果缓存 → `ToolInterceptor`

最省事的 `MessagesModelHook` 长这样（模型调用前修剪历史）：

```java
@HookPositions({HookPosition.BEFORE_MODEL})
public class MessageTrimmingHook extends MessagesModelHook {

    private static final int MAX = 12;

    @Override
    public String getName() {
        return "message_trimming";
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        if (messages.size() <= MAX) {
            return new AgentCommand(messages);
        }
        List<Message> keep = messages.subList(messages.size() - MAX, messages.size());
        return new AgentCommand(keep, UpdatePolicy.REPLACE); // 整表替换
    }
}
```

**`UpdatePolicy` 只有两个策略，但用错就是灾难**：

- `REPLACE`：用新列表整表替换 messages；
- `APPEND`：往现有列表后面追加。

**改历史长度的场景几乎都用 REPLACE**。如果你在裁剪场景里误用 `APPEND`，历史不但没变短，反而每轮都多一份——上下文会以肉眼可见的速度膨胀。`APPEND` 适合的场景是"再塞一条提示词"这种增量操作。

最后一个建议：**本轮临时计数**（调了几次模型、有没有做过摘要这类只属于本次执行的数据）最好放 `RunnableConfig.context()`，一般不要写进 Checkpoint 持久化。key 建议带独立前缀，免得和业务字段撞名。确实需要跨轮持久的，再放到 state / Checkpoint。关于 `OverAllState`、`RunnableConfig.metadata`、`RunnableConfig.context` 的分工，可以回看上一篇"03-别让 Agent 重启就失忆"的状态模型那一节。

---

## 三、Human-in-the-Loop：给危险操作装一道闸门

### 3.1 它拦在什么位置

`HumanInTheLoopHook` 是实现"人在回路"的核心组件，本质是一个挂在 **`afterModel` 阶段**的拦截钩子。这个挂载点选得非常讲究：

- 太早（beforeModel）：模型还没产出工具调用，你不知道要批什么；
- 太晚（工具已执行）：钱已经打出去了，拦了个寂寞。

`afterModel` 正好是**大模型刚生成完回复、输出了 `tool_calls`，但还没真正进入 Tool 节点执行**的那个瞬间——看到了意图，还没造成后果。这就是唯一正确的拦截时机。

判断逻辑很朴素：钩子拿到模型生成的所有工具调用请求，逐个校验工具名是否在 `approvalOn` 注册的审批名单里。

- **在名单内**：立刻中断 Agent 执行流，返回 `InterruptionMetadata` 中断元数据，进入人工等待状态；
- **不在名单内**：直接放行，工具正常执行，流程不打断。

所以它适用于所有**写操作、不可逆操作、高风险操作**：订单退款、资金打款、删除生产数据、发送正式通知邮件、修改系统权限等——从机制上杜绝 AI 误调用造成的业务损失。

配置方式（Builder 模式，`approvalOn` 注册需要审批的工具）：

```java
HumanInTheLoopHook hitl = HumanInTheLoopHook.builder()
        .approvalOn("refund_order", ToolConfig.builder()
                .description("退款操作需要人工确认金额与订单号")
                .build())
        .approvalOn("delete_data") // 也可不写 description
        .build();
```

### 3.2 硬性前提：必须配 Saver

这条不是建议，是**必须**。人在环能力强依赖状态持久化组件 Checkpointer（也就是 Saver），原因很直白：

- 中断发生时，Agent 的完整执行状态（对话上下文、执行进度、中间状态、待执行的工具信息）必须通过 Saver 落盘；
- 人工审核完成后，要用**同一个 threadId** 恢复执行，Saver 靠它还原中断前的状态；
- 没配 Saver，中断后状态就丢了，恢复时相当于"失忆"，根本接不上之前的流程。

上一篇文章讲的 Checkpoint，在这里第一次真正显示出价值：它不只是"重启续聊"，更是**"暂停几小时后从断点继续"**的技术基础。

### 3.3 审批名单的三条设计原则

1. **最小审批原则**：只把高风险工具加入名单，纯查询类（查订单、查物流、查天气）一律不要加。否则每次对话都弹审批，交互卡成一团，人工审核也会被无意义的点击淹没。
2. **描述面向人工**：`ToolConfig.description` 是**写给审核人员看的**，要说清风险点——涉及多少金额、哪个订单号、操作是否可撤销。审核人要在三秒内判断批不批，描述就得给得起这个判断。
3. **命名严格一致**：`approvalOn` 里写的工具名，必须和 `@Tool` 注解或 `FunctionToolCallback` 定义的工具 `name` **完全一致**。这条最不起眼，也最容易出错——名字对不上不会报错，只会**静默失效**，你以为拦住了，其实一路绿灯。

### 3.4 标准流程：中断 → 人工审核 → 恢复执行

官方标准是**两段式调用**，完整流程三步：

1. **第一次调用（触发中断）**：传入用户问题和携带 `threadId` 的 `RunnableConfig`，调用 `invokeAndGetOutput`。命中审批规则时，返回值是 `InterruptionMetadata` 类型，里面有待审核的工具名称、参数、描述等信息。
2. **人工审核环节**：业务系统通过 UI 或接口把待审信息展示给审核人，审核人给出决策——批准、修改参数、拒绝。
3. **第二次调用（恢复执行）**：用**同一个 threadId** 构建恢复配置，把审批结果放进 metadata 的 `HUMAN_FEEDBACK_METADATA_KEY`，再次调用 `invokeAndGetOutput`（**用户输入传空字符串**，避免被当成新问题冲掉上下文），Agent 从中断点继续。

代码骨架：

```java
// 1. 首次调用，携带threadId
RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
Optional<?> first = agent.invokeAndGetOutput("帮我把订单 O001 退款 199 元", config);

// 2. 判断是否发生了中断
if (first.isPresent() && first.get() instanceof InterruptionMetadata interruption) {
    // 3. 构建人工审核反馈
    InterruptionMetadata.Builder feedback = InterruptionMetadata.builder()
            .nodeId(interruption.node())
            .state(interruption.state());

    // 逐个对待审工具给出审批结果
    interruption.toolFeedbacks().forEach(tf -> feedback.addToolFeedback(
            InterruptionMetadata.ToolFeedback.builder(tf)
                    // 审批结果：批准/修改/拒绝
                    .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
                    .build()));

    // 4. 构建恢复配置，传入相同threadId + 审批结果
    RunnableConfig resume = RunnableConfig.builder()
            .threadId(threadId)
            .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedback.build())
            .build();

    // 5. 第二次调用，空输入，从中断点继续执行
    agent.invokeAndGetOutput("", resume);
}
```

三种人工决策：

| 决策结果 | 效果 |
| --- | --- |
| `APPROVED`（批准） | 按模型原参数执行工具调用 |
| `EDITED`（编辑） | 人工修改工具参数（arguments）后再执行 |
| `REJECTED`（拒绝） | 不执行该工具；可在描述中填写拒绝原因，供后续模型向用户解释 |

> 一次模型输出如果包含多个待审批工具，需要在同一个 `InterruptionMetadata` 中逐个给出决策，三种结果可以混合使用。

### 3.5 人工不止能批驳，还能改状态

审核环节的权力比"批 / 驳"大得多，常见两种进阶用法：

1. **修改工具参数（EDITED）**：最常用的场景。模型申请退款 999 元，人工审核改成 199 元再批准——只需要改 `ToolFeedback` 里的 `arguments`。这相当于给了人类一个"最后修正权"，模型的判断不是终点。
2. **修改业务状态字段**：构建反馈时保留 `interruption.state()`，业务代码可以在恢复前修改 State 里的业务字段（比如标记 `need_human=false`、记录审批人 `refund_approved_by=ops01`）再写入反馈。Agent 恢复后，后续节点能读到这些被改过的字段。**这实际上给了人类一个直接干预 Agent 状态的入口**，做审计留痕、加审批人标记都很方便。

两个必须守住的注意事项：

- 恢复执行时 `threadId` **必须和中断时完全一致**，否则还原不了状态——这是新手最常见的"为什么恢复后它又问一遍"的原因；
- 第二次调用时用户输入通常传**空字符串**。传了内容会被当成新一轮用户提问，覆盖掉中断前的上下文，审批白做。

---

## 四、上下文工程：在送进模型之前就管好

上下文工程是 Spring AI Alibaba Agent 体系里**生产级落地的核心能力集**。它的核心思想可以用一句话概括：

> **在输入送入大模型之前，主动对上下文（对话消息、工具集、指令、状态、调用次数等）进行管控和优化，而不是指望大模型自己处理长上下文、规避风险、控制成本。**

它填的是 Demo 到生产之间的那道沟：token 溢出、成本失控、工具滥用、执行死循环、上下文冗余、高风险操作不可控、能力过多导致 token 爆炸。下面逐个拆。

### 4.1 软压缩：`SummarizationHook`

长对话下有两条路：硬删（丢信息）和摘要（保事实）。`SummarizationHook` 走后者，是**"软截断"**方案。

它挂在模型调用前，执行逻辑是：每次调模型前统计当前上下文的 token 总量 → 达到 `maxTokensBeforeSummary` 阈值时触发压缩 → 用指定模型（可以配成更便宜的轻量模型）把较早的对话历史压成一段摘要 → 最终送入主模型的上下文 = **历史摘要 + 最近 N 条原始对话消息**。

```java
SummarizationHook summarizationHook = SummarizationHook.builder()
        .model(chatModel)               // 执行摘要的模型，可换成低成本小模型
        .maxTokensBeforeSummary(4000)   // token 阈值：达到4000时触发压缩
        .messagesToKeep(20)             // 压缩后保留最近20条原始消息
        .build();
```

对比硬截断，它能保住"用户叫张三、收货地址在杭州、之前约定过退款规则"这类核心事实，上下文质量明显更高；代价是每次压缩多一次模型调用，产生额外成本和一点点延迟。

生产上的经典组合是：**`SummarizationHook` 保上下文质量 + `ModelCallLimitHook` 控整体调用成本**。适合长会话场景（客服、私人助理）。

### 4.2 硬裁剪：`ContextEditingInterceptor`

这是**"硬裁剪"**方案，纯框架层面的按策略清理，不需要额外模型调用，成本低、见效快。

执行逻辑：送入 LLM 前检查上下文规模 → token 量达到 `trigger` 触发阈值时启动裁剪 → 按策略（通常从最早的消息开始删）至少清理出 `clearAtLeast` 指定的 token 空间。

```java
ContextEditingInterceptor editing = ContextEditingInterceptor.builder()
        .trigger(12000)       // 上下文达到12000 token时触发裁剪
        .clearAtLeast(6000)   // 至少清理出6000 token空间
        .build();
```

两者怎么选：

| 维度 | Context Compaction（`SummarizationHook`） | Context Editing（`ContextEditingInterceptor`） |
| --- | --- | --- |
| 核心思路 | 总结替换：旧内容提炼成摘要，保留核心信息 | 按策略删除：直接裁掉旧内容，不保留 |
| 信息损失 | 低，关键事实可保留在摘要中 | 高，被裁剪的消息直接丢失 |
| 额外成本 | 有，需要额外调用一次摘要模型 | 无，纯框架层面逻辑处理 |
| 适用场景 | 对历史信息依赖强的长对话 | 对历史依赖弱、轮次极多的场景 |

> 两者可以同时挂载，但要注意执行顺序，避免重复修改上下文导致异常，建议先用日志确认谁先谁后。

### 4.3 裁剪的两条铁律

不管是自己写 Hook 裁剪还是用框架能力，有两条结构约束必须守住。它们不会报"裁剪逻辑有 bug"，只会报一个看起来莫名其妙的 API 错误，所以值得单独记牢。

**铁律一：`messages[0]` 必须是 user 消息**

许多模型厂商（尤其 Anthropic Claude 系列，以及部分 OpenAI 兼容网关）对 `messages` 数组结构有强校验：

```
messages[0].role 必须是 "user"
```

如果裁剪把开头的 `UserMessage` 删掉了，让列表以 `AssistantMessage` 或 `ToolResponseMessage` 开头，API 会**直接拒绝请求**。

最容易中招的是"按条数"的滑动窗口。比如 `MessageWindowChatMemory` 窗口设为 20，它按条数裁而不是按轮次裁，聊久了就可能把最早那条 `UserMessage` 淘汰掉，留下一条孤零零的 `AssistantMessage` 挂在列表开头。

避免方法：

1. **按轮次裁剪，不要按条数**。一轮 = `UserMessage` + `AssistantMessage`（+ 可能的 `ToolResponseMessage`），删就删整轮；
2. 自己实现的话，裁完检查 `messages.get(0).getMessageType() == USER`，不是就继续往前删直到满足；
3. 用框架自带 memory 时，先确认它的窗口策略是"保留最近 N 轮"还是"最近 N 条"——**前者安全，后者容易踩坑**。

**铁律二：`tool_calls` 和它的响应是原子配对**

这条比第一条更常见。几乎所有支持 function calling 的厂商（OpenAI、Anthropic、DashScope 等）都强制要求：

```
AssistantMessage(toolCalls=[call_abc]) 
    → 必须紧跟 ToolResponseMessage(id=call_abc)
```

如果删掉了 `ToolResponseMessage`，只留下带 `toolCalls` 的 `AssistantMessage`，API 会报：

```
An assistant message with 'tool_calls' must be followed by tool messages 
responding to each 'tool_call_id'.
```

按条数滑动的窗口最容易制造这种事故：

```
... 
[17] AssistantMessage(toolCalls=[call_abc])   ← 被保留
[18] ToolResponseMessage(id=call_abc)          ← 被裁掉
[19] AssistantMessage(text="成都今天晴，25℃")
```

窗口往前滑一格，`[18]` 被淘汰而 `[17]` 还在，下一轮请求发出去就是 400。

**更隐蔽的变体**：一轮里模型同时调多个工具——

```
AssistantMessage(toolCalls=[call_1, call_2, call_3])
ToolResponseMessage(id=call_1)
ToolResponseMessage(id=call_2)   ← 这条被裁掉
ToolResponseMessage(id=call_3)
```

只删中间一条也报错：`tool_call_id call_2 not found`。

避免方法：

1. **按"工具调用组"裁剪**：把 `AssistantMessage(toolCalls=[...])` 加上它后面所有的 `ToolResponseMessage` 视为一个**不可分割的原子块**，要删整块删，要么全留；
2. 自己实现的话，裁完做一次**配对校验**：遇到带 `toolCalls` 的 assistant 就检查后面是否每个 `toolCallId` 都有响应，缺了就把这个 assistant 一并删掉；
3. 用框架自带 memory 时，确认它有没有处理工具消息配对——有些简单实现只按条数裁，不管配对。

### 4.4 双层限流：模型 + 工具

**`ModelCallLimitHook`** 管的是 ReAct 死循环。模型陷入"思考 → 调工具 → 再思考"出不来时，它会限制单次运行内模型的最大调用次数，达到上限触发预设退出行为。

```java
ModelCallLimitHook modelLimit = ModelCallLimitHook.builder()
        .runLimit(8)    // 单次运行内，模型最多调用8次
        .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR) // 超限行为：抛错
        .build();
```

两种退出行为的选择很关键：

- `ERROR`：直接抛异常，适合**开发测试阶段**，能快速暴露死循环问题；
- `END`：直接结束运行并返回友好结束语，适合**生产环境**，避免报错吓到用户。

**`ToolCallLimitHook`** 管的是工具被滥用，尤其高风险写操作（退款、删数据）。它可以针对全局，也可以指定单个工具，分别限制「单次运行内次数」和「单会话累计次数」：

```java
ToolCallLimitHook toolLimit = ToolCallLimitHook.builder()
        .toolName("refund_order")  // 可选：只限制退款工具；不填则限制全部工具
        .threadLimit(20)           // 整个会话内，该工具最多调用20次
        .runLimit(5)               // 单次运行内，该工具最多调用5次
        .exitBehavior(ToolCallLimitHook.ExitBehavior.END) // 超限结束运行
        .build();
```

设计建议：查询类工具（查订单、查物流）限制放宽，保障交互流畅；高风险写操作（退款、打款、删数据）限制严格，`runLimit` 压低。

### 4.5 工具重试：`ToolRetryInterceptor`

工具调用经常遇到瞬时故障：网络超时、下游 5xx、临时限流。这些直接判失败既没必要又伤体验，于是有了这个工具层面的拦截器：失败时自动重试，达到最大次数后执行预设失败策略。

```java
ToolRetryInterceptor toolRetry = ToolRetryInterceptor.builder()
        .maxRetries(2)  // 最多重试2次
        .onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
        .build();
```

三个要点：

1. **必须设上限**：禁止无界重试，否则下游抖动时会引发雪崩。生产建议 1~2 次；
2. **写操作要幂等**：退款、打款这类工具**自身必须保证幂等**（同一订单号重复调用不会重复执行），否则重试直接造成资产损失——这是重试机制最危险的副作用；
3. **失败降级**：`RETURN_MESSAGE` 把错误信息作为观察结果返回给模型，由模型决定下一步（换方式、告知用户），而不是让 Agent 崩溃。

> 补充：还有对应的 `ModelRetryInterceptor`，专门处理模型调用侧的瞬时错误（网络超时、限流）。它和工具重试是两层独立的容错，别互相替代。

### 4.6 任务规划：`TodoListInterceptor`

复杂多步任务（调研、跨系统操作）里，模型容易走一步看一步，出现漏步骤、重复操作、逻辑混乱，而且执行过程完全不透明。这个拦截器在正式执行前**强制模型先输出一份待办清单**，明确步骤和目标，确认规划后再一步步执行。

```java
TodoListInterceptor planning = TodoListInterceptor.builder().build();

ReactAgent agent = ReactAgent.builder()
        .name("planning_agent")
        .model(chatModel)
        .tools(tools)
        .interceptors(planning)  // 挂载规划拦截器
        .build();
```

适合复杂调研、多系统编排、需要向用户展示执行计划的透明场景；**不适合简单一问一答**，那会白白多一轮规划调用，增加延迟和成本。

### 4.7 动态工具选择：让模型别在 100 个工具里挑

工具太多会带来三个问题：token 占用高、模型容易选错、权限难管控。主流有两种解法。

**方案一：LLM 自动选工具 —— `ToolSelectionInterceptor`**。它在主模型调用前加一层轻量决策：先让模型从全量候选工具里挑出与当前问题相关的，只把选中的暴露给本次主模型调用，大幅减少上下文中的工具定义数量。

```java
ToolSelectionInterceptor selector = ToolSelectionInterceptor.builder().build();
```

**方案二：自定义规则动态控权 —— 自定义 `ModelInterceptor`**。适合按角色、权限、用户等级等业务规则控制工具可见性，**比自动选择更可控**（自动选择有概率漏，规则不会）：

```java
public class RoleBasedToolInterceptor extends ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        // 从上下文获取用户角色
        String role = String.valueOf(request.getContext().getOrDefault("user_role", "user"));
        ModelRequest.Builder builder = ModelRequest.builder(request);
        
        if ("agent".equals(role)) {
            // 满足权限：可查可退
            builder.tools(List.of("query_order", "refund_order"));
        } else {
            // 普通用户：只能查询
            builder.tools(List.of("query_order"));
        }
        return handler.call(builder.build());
    }

    @Override
    public String getName() {
        return "role_based_tools";
    }
}
```

注意这里的价值不只是省 token——**工具不可见本身就是一种权限控制**。普通用户连 `refund_order` 的名字都看不到，从源头杜绝了越权调用的可能。

> 补充：还可以通过 `dynamicToolCallbacks(...)` 在运行时动态追加工具，比如高级会员解锁专属能力。

### 4.8 Skills 渐进披露（Progressive Disclosure）

如果 Agent 有几十上百个技能，把所有技能的说明、工具、规则全塞进上下文，token 会爆炸，模型也容易混淆不同技能的规则。Skills 用「渐进披露」解决：**不用的技能不占上下文，需要时才加载**。

**三层加载机制**：

1. **元数据层**：只有技能的 `name + description`，体积很小，几乎常驻上下文。相当于一本**技能目录**，让模型知道"我会些什么"。
2. **SKILL.md 正文层**：技能的详细规则、使用说明、注意事项。只有模型主动调用 `read_skill` 工具后，才加载到上下文。
3. **资产层**：技能对应的脚本、参考资料、配置文件等，真正执行时才按需加载。

**目录结构约定**：

```
skills/
└── refund-policy/
    └── SKILL.md
```

SKILL.md 的最小形态（带 YAML front matter）：

```markdown
---
name: refund-policy
description: "电商退款政策与操作要点。用户问退款规则、几日内可退、如何发起退款时使用。"
---

# 退款政策技能
1. 先查订单状态，再判断是否可退。
2. 涉及真实退款必须调用 refund_order，且需人工审批。
3. 禁止承诺「一定能退」。
```

**装配方式**：注册技能 → 构建技能钩子 → 挂到 Agent。

```java
// 1. 注册技能：从指定目录加载所有技能
SkillRegistry registry = FileSystemSkillRegistry.builder()
        .projectSkillsDirectory("classpath:skills")
        .build();

// 2. 构建技能钩子
SkillsAgentHook skillsHook = SkillsAgentHook.builder()
        .skillRegistry(registry)
        .build();

// 3. 挂载到 Agent
ReactAgent agent = ReactAgent.builder()
        .name("skills_agent")
        .model(chatModel)
        .hooks(skillsHook)  // 自动注入 read_skill 工具 + SkillsInterceptor
        .saver(new MemorySaver())
        .build();
```

**执行流程**：模型初始只能看到所有技能的元数据（名称 + 描述）→ 判断当前问题需要某个技能时，调用 `read_skill` 读取详情 → 拦截器把该技能的规则和工具注入后续模型请求 → 模型基于完整技能信息执行任务。

这个"目录 → 打开 → 执行"的三段式，既保留了 Agent 的丰富能力，又避免全量加载的浪费，是中大型 Agent 系统的标准设计。

---

## 五、动手验证：一个带审批的客服 Agent

场景是电商客服：能查订单、能退款（但退款必须人工审批）、会用退款政策技能、工具抖动自动重试、对话长了自动压缩。下面这份代码把前面所有治理组件串在了一起。

先准备技能文件（放在 `src/main/resources/skills/refund-policy/SKILL.md`；本地开发也可用文件系统目录，见代码注释）：

```markdown
---
name: refund-policy
description: "电商退款政策与操作要点。用户问退款规则、几日内可退、七天无理由、如何发起退款时，先 read_skill 再回答。"
---

# 退款政策技能

1. 先调用 `query_order` 查清订单状态与金额，再判断是否可退。
2. 已支付（paid）且 `refundable=true` 才可发起退款；已发货订单需提示用户先确认收货或走售后。
3. 涉及真实退款必须调用 `refund_order`，禁止口头承诺「已经退款」。
4. 退款金额不得超过订单金额；用户未说明金额时，默认按订单金额申请。
5. 禁止承诺「一定能退」「马上到账」等无法保证的时效。
```

```java
package space.kmind.zk.springaialibabalearning.stage04;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

@Tag(name = "Stage04-Hooks/HITL/Context", description = "客服：退款审批 + 压缩 + 重试 + Skills")
@RestController
public class ACustomerServiceHookDemo {
    final AtomicInteger queryFailBudget = new AtomicInteger(1);

    public static final String SYSTEM_PROMPT = """
            你是电商客服 Agent。
            查订单必须调用 query_order；退款必须调用 refund_order，禁止口头承诺已退款。
            用户询问退款规则、可否退款、几日内可退时：先调用 read_skill 读取 refund-policy，再基于技能内容回答。
            工具返回 error 时如实说明；无关问题礼貌拒绝。
            """;

    private final ReactAgent agent;
    private final BaseCheckpointSaver saver;

    /**
     * 演示用：模拟工具前两次失败
     */

    public ACustomerServiceHookDemo(ChatModel chatModel, RedisSaver redisSaver) throws Exception {
        this.saver = redisSaver;
        ToolCallback queryOrder = FunctionToolCallback
                .builder("query_order", new QueryOrder())
                .description("根据订单号查询订单状态与金额。用户问订单、物流、能否退款时先查。")
                .inputType(OrderQuery.class)
                .build();

        ToolCallback refundOrder = FunctionToolCallback
                .builder("refund_order", this::refundOrder)
                .description("对指定订单发起退款。高风险写操作，仅在用户明确要求退款时调用。")
                .inputType(RefundRequest.class)
                .build();

        // Skills：元数据进提示词；需要细则时模型调 read_skill（由 SkillsAgentHook 自动注册）
        // 开发期用 Classpath；若打包成 jar 后加载失败，改用 FileSystemSkillRegistry 指向真实目录
        // （已知问题：https://github.com/alibaba/spring-ai-alibaba/issues/4426）
        SkillRegistry skillRegistry = ClasspathSkillRegistry.builder()
                .classpathPath("skills")
                .build();
        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .build();
        // 文件系统写法示例：
        // SkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
        //         .projectSkillsDirectory(Paths.get("src/main/resources/skills").toAbsolutePath().toString())
        //         .build();

        HumanInTheLoopHook hitl = HumanInTheLoopHook.builder()
                .approvalOn("refund_order", ToolConfig.builder()
                        .description("退款将真实动账，请核对订单号与金额后再批准。")
                        .build())
                .build();

        SummarizationHook summarization = SummarizationHook.builder()
                .model(chatModel)
                .maxTokensBeforeSummary(3000)
                .messagesToKeep(16)
                .build();

        ModelCallLimitHook modelLimit = ModelCallLimitHook.builder()
                .runLimit(10)
                .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                .build();

        ToolRetryInterceptor toolRetry = ToolRetryInterceptor.builder()
                .maxRetries(2)
                .onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
                .build();

        this.agent = ReactAgent.builder()
                .name("cs_agent")
                .model(chatModel)
                .systemPrompt(SYSTEM_PROMPT)
                .tools(queryOrder, refundOrder) // read_skill 由 skillsHook.getTools() 自动合并，无需手写
                .hooks(List.of(skillsHook, summarization, modelLimit, hitl))
                .interceptors(List.of(toolRetry))
                .saver(saver)
                .build();
    }

    /**
     * 按 threadId 缓存中断快照。
     */
    private final Map<String, InterruptionMetadata> pendingByThread = new ConcurrentHashMap<>();

    @Operation(summary = "客服对话：可能中断（退款待审）")
    @PostMapping("/api/s51/chat")
    public Map<String, Object> chat(@RequestBody ChatReq req) throws Exception {
        String threadId = Optional.ofNullable(req.threadId()).orElse("cs-demo-1");
        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("user_id", Optional.ofNullable(req.userId()).orElse("u10086"))
                .build();

        Optional<?> out = agent.invokeAndGetOutput(req.message(), config);
        return toView(threadId, out);
    }

    @Operation(summary = "人工审批后退款：decision=APPROVED|EDITED|REJECTED")
    @PostMapping("/api/s52/resume")
    public Map<String, Object> resume(@RequestBody ResumeReq req) throws Exception {
        InterruptionMetadata pending = pendingByThread.get(req.threadId());
        if (pending == null || pending.toolFeedbacks() == null || pending.toolFeedbacks().isEmpty()) {
            return Map.of("error", "该 threadId 没有待审批中断，请先调 /api/s51/chat 触发退款");
        }

        InterruptionMetadata.Builder feedback = InterruptionMetadata.builder()
                .nodeId(pending.node())
                .state(pending.state());

        for (InterruptionMetadata.ToolFeedback tf : pending.toolFeedbacks()) {
            var b = InterruptionMetadata.ToolFeedback.builder(tf);
            switch (req.decision().toUpperCase(Locale.ROOT)) {
                case "EDITED" -> b.arguments(Optional.ofNullable(req.editedArguments()).orElse(tf.getArguments()))
                        .result(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED);
                case "REJECTED" -> b.result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED)
                        .description(Optional.ofNullable(req.reason()).orElse("人工拒绝退款"));
                default -> b.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
            }
            feedback.addToolFeedback(b.build());
        }

        RunnableConfig resume = RunnableConfig.builder()
                .threadId(req.threadId())
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedback.build())
                .build();

        Optional<?> out = agent.invokeAndGetOutput("", resume);
        // 恢复后再清掉，避免重复审批
        pendingByThread.remove(req.threadId());
        return toView(req.threadId(), out);
    }

    private Map<String, Object> toView(String threadId, Optional<?> out) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("threadId", threadId);
        if (out.isEmpty()) {
            view.put("status", "empty");
            return view;
        }
        Object v = out.get();
        if (v instanceof InterruptionMetadata interruption) {
            pendingByThread.put(threadId, interruption); // 服务端留底，resume 用
            view.put("status", "interrupted");
            view.put("pending", toPendingView(interruption)); // 显式 DTO，避免 Jackson 丢字段
            return view;
        }
        view.put("status", "completed");
        view.put("output", String.valueOf(v));
        return view;
    }

    /** 手动抽出审批需要的字段，保证 HTTP JSON 里能看到 toolFeedbacks */
    private Map<String, Object> toPendingView(InterruptionMetadata interruption) {
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("node", interruption.node());
        List<Map<String, Object>> feedbacks = new ArrayList<>();
        for (InterruptionMetadata.ToolFeedback tf : interruption.toolFeedbacks()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", tf.getId());
            one.put("name", tf.getName());
            one.put("arguments", tf.getArguments());
            one.put("description", tf.getDescription());
            one.put("result", tf.getResult());
            feedbacks.add(one);
        }
        pending.put("toolFeedbacks", feedbacks);
        return pending;
    }

    public class  QueryOrder implements BiFunction<OrderQuery, ToolContext, String> {

        @Override
        public String apply(OrderQuery orderQuery, ToolContext toolContext) {

            if (queryFailBudget.getAndDecrement() > 0) {
                throw new RuntimeException("调用超时");
            }
            String orderId = orderQuery == null ? "" : orderQuery.orderId();
            if ("O001".equalsIgnoreCase(orderId)) {
                return "{\"orderId\":\"O001\",\"status\":\"paid\",\"amount\":199.0,\"refundable\":true}";
            }
            return "{\"error\":true,\"message\":\"订单不存在\"}";
        }
    }

    private String refundOrder(RefundRequest r, ToolContext ctx) {
        // 真正执行到这里，说明已经过人工批准（或编辑后的参数）
        return "{\"refundId\":\"R-" + r.orderId() + "\",\"amount\":" + r.amount()
                + ",\"status\":\"success\"}";
    }

    public record ChatReq(String message, String threadId, String userId) {
    }

    public record ResumeReq(
            String threadId,
            String decision,
            String editedArguments,
            String reason,
            InterruptionMetadata pending
    ) {
    }

    public static class OrderQuery {
        @ToolParam(description = "订单号，如 O001")
        private String orderId;

        public String orderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }
    }

    public static class RefundRequest {
        @ToolParam(description = "订单号")
        private String orderId;
        @ToolParam(description = "退款金额")
        private double amount;

        public String orderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public double amount() {
            return amount;
        }

        public void setAmount(double amount) {
            this.amount = amount;
        }
    }
}
```

代码里几个和正文知识点对得上的细节，读的时候留意：

- `queryFailBudget` 故意让 `query_order` 前两次抛异常，用来验证 `ToolRetryInterceptor` 的重试；
- 服务端用 `pendingByThread` 按 threadId 缓存中断快照，`resume` 接口不必再回传 pending——**真实系统应该把中断落库，而不是放内存 Map**，这里是 Demo 才这么省事；
- `toPendingView` 手动抽字段，是因为 `InterruptionMetadata` 直接交给 Jackson 序列化会丢字段，显式 DTO 才稳。

### 自测顺序

以下均用 `Content-Type: application/json`。

**① 问退款规则（验证 `read_skill`）**

`POST /api/s51/chat`

```json
{
  "message": "你们退款规则是什么？几天内可以退？",
  "threadId": "cs-1",
  "userId": "u10086"
}
```

期望：会调 `read_skill`（`refund-policy`），再基于 SKILL.md 回答。这验证了渐进披露的第二层——**技能正文不是常驻的，是模型主动读进来的**。

**② 查订单（验证工具重试 / 查询）**

`POST /api/s51/chat`

```json
{
  "message": "查一下订单 O001",
  "threadId": "cs-1",
  "userId": "u10086"
}
```

**③ 申请退款 → 中断待审**

`POST /api/s51/chat`

```json
{
  "message": "帮我退款 199 元",
  "threadId": "cs-1",
  "userId": "u10086"
}
```

```json
{
  "threadId": "cs-1",
  "status": "interrupted",
  "pending": {
    "node": "…HITL…",
    "toolFeedbacks": [
      {
        "id": "call_xxxxxxxx",
        "name": "refund_order",
        "arguments": "{\"orderId\":\"O001\",\"amount\":199.0}",
        "description": "退款将真实动账，请核对订单号与金额后再批准。",
        "result": null
      }
    ]
  }
}
```

确认：返回 `status=interrupted`，且 `pending.toolFeedbacks[0].name` 为 `refund_order`。服务端已按 `threadId` 缓存完整中断态，**resume 不必再带 pending**。

**④ 批准（APPROVED）后继续**

`POST /api/s52/resume`

```json
{
  "threadId": "cs-1",
  "decision": "APPROVED",
  "editedArguments": null,
  "reason": null
}
```

期望：`status=completed`，退款工具真正执行（金额仍是模型申请的 199）。

**⑤ 再测 EDITED（改小金额）**

换新会话先触发中断：

`POST /api/s51/chat`

```json
{
  "message": "帮我把订单 O001 退款 199 元",
  "threadId": "cs-edit-1",
  "userId": "u10086"
}
```

再审批：

`POST /api/s52/resume`

```json
{
  "threadId": "cs-edit-1",
  "decision": "EDITED",
  "editedArguments": "{\"orderId\":\"O001\",\"amount\":99.0}",
  "reason": null
}
```

期望：按 **99.0** 执行退款（以 `editedArguments` 为准）。这一步验证的是"人类拥有最终修正权"。

> `editedArguments` 是**整段工具参数 JSON 字符串**，字段名需与工具入参一致（本例 `orderId`、`amount`）。

**⑥ 再测 REJECTED（拒绝）**

`POST /api/s51/chat`

```json
{
  "message": "帮我把订单 O001 全额退款",
  "threadId": "cs-reject-1",
  "userId": "u10086"
}
```

`POST /api/s52/resume`

```json
{
  "threadId": "cs-reject-1",
  "decision": "REJECTED",
  "editedArguments": null,
  "reason": "用户未提供签收凭证，暂不同意退款"
}
```

期望：**不会**真正退款；Agent 根据 `reason` 向用户说明。注意"拒绝"不是抛异常——它是一次**可观察的结果**，模型会拿着拒绝理由继续和用户对话，这正是 1 号缺口里讲的那个设计观念。

---

## 下一篇预告

多智能体编排：`Sequential` / `Parallel` / `Routing` / `Loop`，以及 Supervisor、Handoffs。

单个 Agent 治理好了之后，下一步是**让一群 Agent 协作**。

---

## 官方参考

- Hooks / Interceptors：https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks
- 记忆与 Checkpoint：https://java2ai.com/docs/frameworks/agent-framework/tutorials/memory
- HITL 示例：`examples/.../HumanInTheLoopExample.java`
- Skills 示例：`spring-ai-alibaba-agent-example/skills-agent-example`
