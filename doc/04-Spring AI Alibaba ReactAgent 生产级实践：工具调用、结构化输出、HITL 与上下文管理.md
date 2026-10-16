# **Spring AI Alibaba ReactAgent 生产级实践：工具调用、结构化输出、HITL 与上下文管理**

## 1. 前期准备

版本基准：Spring AI Alibaba 1.1.2.x + Spring AI 1.1.2 + JDK 17+

官方文档：https://java2ai.com

Hooks 教程：https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks

记忆教程：https://java2ai.com/docs/frameworks/agent-framework/tutorials/memory

官方主仓库：https://github.com/alibaba/spring-ai-alibaba

官方示例：https://github.com/spring-ai-alibaba/examples（含 `HumanInTheLoopExample`、Skills 示例）

版本跟仓库约定对齐：

```text
JDK                 17+
Spring Boot         3.5.7
Spring AI           1.1.2
Spring AI Alibaba   1.1.2.2
```

上一篇把 Checkpoint 换成持久化后，Agent **重启还能续聊**。本篇往上再加一层治理手段：

**危险工具配合工点确认、超长对话自动压缩、工具抖动自动重试、能力按需展开。**

---

## 2. 为什么需要 Hooks / Interceptors？

ReactAgent 的主循环本身很短：**模型推理 → 调工具 → 观察结果 → 再推理**，直到模型决定停止。但把这个简单循环直接放进生产环境，会撞上一堆“循环本身管不了”的问题。Hooks 和 Interceptors 就是插在循环关键点上的**安全带 + 仪表盘**。主循环只管“推理和调工具”，不管“该不该调、调几次、调完怎么处理、成本多少”。下面六个痛点，都是主循环天然无法覆盖的。

**痛点一：高风险操作无人把关**

**没有治理时**：模型在对话里说“好的，我帮你退款”，直接调了 `refund(orderId, amount)` 工具，钱就出去了。模型没有“审批”概念，它只根据提示词和上下文决定下一步动作。

**有 Hook 时**：在工具执行前插入 `HumanInTheLoopHook`，拦截高风险工具调用，暂停执行，等人确认、改参或拒绝。

```
模型决定调 refund(orderId, 199)
        ↓
HumanInTheLoopHook 拦截
        ↓
等待人工审批（可能是几分钟、几小时）
        ↓
批准 → 继续执行
拒绝 → 返回“已拒绝”作为观察结果
改参 → 用修改后的参数执行
```

**关键能力**：中断、恢复、状态持久化。中断期间 Agent 状态存在 Checkpoint 里，人审批后从断点继续。这是主循环做不到的。



**痛点二：对话越聊越长**

**没有治理时**：50 轮对话后，messages 里堆了几百条消息。Token 消耗爆炸，模型注意力被稀释，回答质量下降，甚至触发上下文长度上限报错。

**有 Hook 时**：在模型调用前插入上下文管理 Hook，做两件事：

- **硬窗口修剪**：按轮次/工具组裁剪，保留最近 N 轮，确保不超限、不破坏消息结构。
- **摘要提质**：把早期对话压缩成一段摘要，作为 SystemMessage 放在历史开头，保留关键事实。

**为什么这是 Hook 而不是主循环的事**：主循环只管“把 messages 发给模型”，不管“messages 是不是太长了”。裁剪逻辑放在 Hook 里，和主循环解耦，可插拔、可配置。



**痛点三：工具偶发超时**

**没有治理时**：工具调用超时或抛异常，主循环直接把异常往上抛，整个 Agent 会话崩掉。或者模型看到失败后，反复重试同一个工具，陷入死循环。

**有 Hook 时**：

- **`ToolRetryHook`**：对瞬时故障自动重试有限次数（如 2 次），带退避策略。
- **工具内部兜底**：捕获异常，返回结构化的错误 JSON，让模型看到“失败了”这个观察结果，而不是崩掉会话。
- **`ModelCallLimitHook`**：限制模型调用次数，防止模型在工具反复失败时无限空转烧钱。

**关键设计**：工具失败不是“致命错误”，而是“一次可观察的结果”。Hook 把异常翻译成模型能理解的消息，让模型自己决定下一步（重试、换工具、告知用户）。



**痛点四：工具上百个，Prompt 塞爆**

**没有治理时**：所有工具的 description 全部塞进 System Prompt，几万个 token 起步。模型在 100 个工具里选，选错概率大增。而且每次新增工具都要改 Prompt。

**有 Hook / Skills 时**：

- **动态筛选**：根据当前对话上下文，只把相关的工具描述注入 Prompt。比如用户问天气，就只给 `get_weather`，不给订单工具。
- **Skills 渐进披露**：工具按技能分组，先给模型看“有哪些技能类别”，模型选了类别再展开具体工具。减少一次性注入的 token 量。

**为什么这是 Hook 的事**：工具筛选逻辑需要在模型调用前动态执行，依赖当前对话状态。主循环是静态的，不知道“这一轮该给哪些工具”。



**痛点五：成本失控**

**没有治理时**：ReAct 循环空转。模型反复推理、调工具、观察、再推理，每一步都是一次 API 调用。一个复杂任务可能跑 20 轮，成本是预期的 10 倍。

**有 Hook 时**：

- **`ModelCallLimitHook`**：限制单次执行的模型调用次数，超限则 `ERROR`（开发调试）或 `END`（生产优雅结束）。
- **`ToolCallLimitHook`**：限制工具调用次数，防止某个工具被反复调用。
- **Token 用量监控 Hook**：累计 token 消耗，超过阈值时告警或中断。

**关键取舍**：`runLimit` 设太大等于没保护，设太小正常任务被误杀。经验值是“最复杂正常任务所需步数 × 1.5”。



**痛点六：可观测性缺失**

**没有治理时**：Agent 跑完只返回一个最终回复。你不知道它调了哪些工具、中间推理是什么、哪一步耗时最长、哪一步失败了。出了问题只能靠猜。

**有 Hook 时**：在关键节点插入日志、埋点、追踪：

- **模型调用前后**：记录输入 token 数、输出 token 数、耗时、模型名称。
- **工具调用前后**：记录工具名、参数、返回、耗时、成功/失败。
- **执行结束时**：汇总整个轨迹，输出结构化日志。

## 3. Hook的运行机制执行顺序

### 3.1 Hook vs Interceptor：先分清

官方文档里两者都能干预 Agent的流程，但职责不同：

| 特性 | **Hook** | **Interceptor** |
| --- | --- | --- |
| 挂载 | `.hooks(...)` | `.interceptors(...)` |
| 粒度 | Agent / 模型前后 | 单次模型调用 / 单次工具调用 |
| 中断恢复 | **支持**（如 HITL） | 一般不中断整图，只包一层调用 |
| 典型用途 | 审批、摘要、调用上限、PII、Skills 装配 | 重试、日志、缓存、上下文编辑、工具选择 |

HITL、配额、PII 这类操作，本质上是在改状态，甚至要把图节点运行停掉，这是 Hook 的用武之地。像工具失败重试三次、打点日志、临时改一下请求上下文，这类包一层就完事的，交给 Interceptor 更舒服。如果审批塞进 Interceptor，和 Checkpoint 对不上；简单重试又做成 Hook，又重得没必要。

### 3.2 agent调用生命周期

在多个 Hook 时一整次 `call` / `invoke` 的大致顺序：

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

其规则和SpringAi底座篇讲的 Advisor「洋葱模型」同一套规则：

- **`before_*`：正序**（先注册的先跑）
- **`after_*`：逆序**（后注册的先跑）
- **Interceptor：嵌套包装**（第一个 interceptor 最外层）

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

一次 `invoke` / `call`调用，里面的 ReAct 可能会执行几轮「模型想 → 可能调工具 → 再想」的小循环。beforeModel` / ModelInterceptor / `afterModel等会在每次循环都被调用一次，而beforeAgent` / `afterAgent不管中间模型想了几轮、调了几次工具，这两头通常各一次。

**用时间线来看看**

假设一次任务里：模型想完 → 调了搜索工具 → 再想一次 → 给出最终答案。

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

所以：

- 剪历史、加系统提示、模型前拦截 → 使用 `beforeModel`，每轮模型调用前都会生效。
- 整次任务计时、开头装 Skills → 使用 `beforeAgent` / `afterAgent。`

### 3.3 自定义 Hook 时怎么选基类

| 基类 | 适合 | 返回 |
| --- | --- | --- |
| **`MessagesModelHook`**（推荐） | 只动消息列表：修剪、加系统提示、简单摘要 | `AgentCommand` + `UpdatePolicy` |
| **`ModelHook`** | 要读写完整 `OverAllState`、自定义业务字段 | `Map<String, Object>` |
| **`AgentHook`** | Agent 整次开始/结束：计时、资源初始化 | 同上 |
| **`ModelInterceptor`** | 改一次模型请求/响应、动态筛工具 | `ModelResponse` |
| **`ToolInterceptor`** | 改一次工具调用：重试、缓存、监控 | `ToolCallResponse` |

一般使用情况：

- 只做消息裁剪、塞一条 system 等→ `MessagesModelHook`
- 要修改 state 里的业务字段（配额、用户等级）→ `ModelHook`
- 整次调用计时、打开连接等 → `AgentHook`
- 模型调用超时重试、按轮次筛选tool → `ModelInterceptor`
- 工具调用重试、结果缓存 → `ToolInterceptor`

最省事的是 `MessagesModelHook`。比如模型调用前修剪历史：

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

**`UpdatePolicy` 两个策略：**

- `REPLACE`：用新列表整表替换 messages
- `APPEND`：往现有列表后面追加

在改历史长度的场景，几乎都用 REPLACE。APPEND追加更适合再塞一条提示词等场景。

最后建议只是本轮临时计数（调了几次模型、有没有做过摘要等本次临时数据）最好放 `RunnableConfig.context()`，一般不放到 Checkpoint持久化。key 建议带上单独前缀，免得和业务字段撞名。需要跨轮持久的，再放到 state /Checkpoint持久化。关于OverAllState 、RunnableConfig.metadata、RunnableConfig.context的用法可以看我的上一篇 "03-别让 Agent 重启就失忆：Checkpoint 、压缩上下文、业务字段分离设计实战"的状态模型那一栏。

---

## 4. Human-in-the-Loop

### 4.1 `HumanInTheLoopHook` 是什么

`umanInTheLoopHook`是 Spring AI Alibaba Agent 模块中**实现 “人在回路” 安全管控的核心组件**，本质是一个挂载在 Agent 执行流 `afterModel` 阶段的拦截钩子，专门对模型生成的工具调用（`tool_calls`）做风险拦截：高风险工具必须经过人工审批才能执行，低风险 / 查询类工具自动放行，是保障 AI Agent 业务操作安全的关键机制。

**核心工作原理与触发时机**

1. **触发节点**：Agent 执行流程的 `afterModel` 阶段 —— 也就是大模型刚生成完回复、输出了 `tool_calls` 工具调用指令，但还没真正进入 Tool 节点执行工具之前。
2. **判断逻辑**：
   - 钩子拿到模型生成的所有工具调用请求，逐个校验工具名称是否在 `approvalOn` 注册的审批名单中
   - **在名单内**：立即中断 Agent 执行流，返回 `InterruptionMetadata` 中断元数据，进入人工等待状态
   - **不在名单内**：直接放行，工具正常执行，不打断流程

使用所有**写操作、不可逆操作、高风险操作**的场景，例如：订单退款、资金打款、删除生产数据、发送正式通知邮件、修改系统权限等，从机制上避免 AI 误调用造成业务损失。

**配置方式与代码示例**

通过 Builder 模式构建 `HumanInTheLoopHook` 实例，用 `approvalOn` 方法注册需要人工审批的工具，支持为每个工具配置说明信息：

```java
HumanInTheLoopHook hitl = HumanInTheLoopHook.builder()
        .approvalOn("refund_order", ToolConfig.builder()
                .description("退款操作需要人工确认金额与订单号")
                .build())
        .approvalOn("delete_data") // 也可不写 description
        .build();
```

**硬性前提：必须配置 Checkpointer（Saver）**

人在环能力强依赖**状态持久化组件 Checkpointer（也叫 Saver）**，这是必要前提：

- 中断发生时，Agent 的完整执行状态（对话上下文、执行进度、中间状态、待执行工具信息）必须通过 Saver 落盘持久化
- 人工审核完成后，需要用**同一个 threadId** 去恢复执行，Saver 会根据 threadId 还原中断前的状态
- 如果没有配置 Saver，中断后状态会丢失，恢复执行时会丢失上下文，相当于 “失忆”，无法接续之前的流程。

### 4.2 批设计原则

1. **最小审批原则**：只把高风险工具加入审批名单，纯查询类工具（查订单、查物流、查天气等）不要加入，避免过度拦截导致交互卡顿、人工审核负担过重。
2. **描述面向人工**：`description` 是写给审核人员看的，要明确说明操作风险点，比如涉及的金额、订单号、操作是否可撤销等关键信息。
3. **命名严格一致**：`approvalOn` 里写的工具名，必须和 `@Tool` 注解、`FunctionToolCallback` 中定义的工具 `name` 完全一致，否则会匹配失败、拦截失效。

### 4.3 标准执行流程：中断 → 人工审核 → 恢复执行

官方标准是**两段式调用**模式，完整流程如下：

1. **第一次调用：触发中断** 传入用户问题和 `RunnableConfig`（携带唯一 `threadId`），调用 `invokeAndGetOutput`。 如果命中审批规则，返回值会是 `InterruptionMetadata` 类型，里面包含待审核的工具名称、参数、描述等信息。
2. **人工审核环节** 业务系统通过 UI 或接口把待审信息展示给审核人，审核人给出决策：批准、修改参数、拒绝。
3. **第二次调用：恢复执行** 用**同一个 threadId** 构建恢复配置，把人工审批结果放入 `metadata` 的 `HUMAN_FEEDBACK_METADATA_KEY` 中，再次调用 `invokeAndGetOutput`（用户输入传空字符串，避免被当成新问题冲掉上下文），Agent 会从中断点继续执行。

**核心代码骨架**：

```
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

**三种人工决策类型**：

| 决策结果           | 效果                                                       |
| ------------------ | ---------------------------------------------------------- |
| `APPROVED`（批准） | 按模型原参数执行工具调用                                   |
| `EDITED`（编辑）   | 人工修改工具参数（arguments）后再执行                      |
| `REJECTED`（拒绝） | 不执行该工具；可在描述中填写拒绝原因，供后续模型向用户解释 |

> 一次模型输出如果包含多个待审批工具，需要在同一个 `InterruptionMetadata` 中逐个给出决策，支持三种结果混合使用。

### 4.4 人工修改状态后再继续

工审核环节不仅可以批 / 驳工具，还可以修改执行状态后再恢复，常见两种场景：

1. **修改工具参数（EDITED）**：最常用的场景。比如模型申请退款 999 元，人工审核时修改为 199 元，再批准执行，只需要修改 `ToolFeedback` 中的 `arguments` 即可。
2. **修改业务状态字段**：在构建反馈时保留 `interruption.state()`，业务代码可以在恢复前修改 State 中的业务字段（比如标记 `need_human=false`、记录审批人 `refund_approved_by=ops01`），再写入反馈；Agent 恢复执行后，后续节点可以读取这些修改后的状态字段。

**键注意事项**

- 恢复执行时 `threadId` 必须和中断时完全一致，否则无法还原状态。
- 第二次调用 `invokeAndGetOutput` 时，用户输入通常传**空字符串**，避免被 Agent 识别为新一轮用户提问，覆盖掉中断前的上下文。

## 5. Context Engineering（上下文工程）

上下文工程是 Spring AI Alibaba Agent 体系中**生产级落地的核心能力集**，核心思想是：**在输入送入大模型之前，通过框架内置的拦截器 / 钩子，主动对上下文（对话消息、工具集、指令、状态、调用次数等）进行管控、优化和治理**，而不是完全依赖大模型自身的能力去处理长上下文、规避风险、控制成本。

它解决的是 Demo 到生产之间的核心痛点：token 溢出、成本失控、工具滥用、执行死循环、上下文冗余、高风险操作不可控、能力过多导致 token 爆炸等。下面逐个拆解核心组件：

### 5.1 上下文压缩：SummarizationHook

长对话下的「软截断」方案，用摘要保留历史核心信息，避免直接硬截断丢失关键事实。

**核心定位**：长对话下的「软截断」方案，用摘要保留历史核心信息，避免直接硬截断丢失关键事实。

**解决的问题**

随着对话轮次增加，上下文 token 量会逼近模型上限。如果直接硬删除最早的消息，会丢失用户身份、历史约定、关键事实等重要信息；如果全量保留，会触发 token 超限报错。

**工作原理**

它是一个挂载在模型调用前的 Agent Hook，执行逻辑：

1. 每次调用模型前，统计当前上下文的 token 总量
2. 当 token 量达到 `maxTokensBeforeSummary` 阈值时，触发压缩
3. 调用指定的模型（可以是更便宜的轻量模型），将较早的对话历史压缩成一段摘要
4. 最终送入主模型的上下文 = 历史摘要 + 最近 N 条原始对话消息

**配置示例**

```
SummarizationHook summarizationHook = SummarizationHook.builder()
        .model(chatModel)               // 执行摘要的模型，可换成低成本小模型
        .maxTokensBeforeSummary(4000)   // token 阈值：达到4000时触发压缩
        .messagesToKeep(20)             // 压缩后保留最近20条原始消息
        .build();
```

**特点与适用场景**

- 对比硬截断：能保留「用户叫张三、收货地址在杭州、之前约定过退款规则」这类核心事实，上下文质量更高
- 代价：每次压缩会多一次模型调用，产生额外成本和延迟
- 生产最佳实践：`SummarizationHook` 保障上下文质量 + `ModelCallLimitHook` 控制整体调用成本
- 适用：需要保留历史上下文的长会话场景（客服、私人助理）

### 5.2 上下文裁剪：ContextEditingInterceptor

**核心定位**：按策略硬清理上下文的「硬裁剪」方案，低成本快速腾出 token 空间。

**解决的问题**

上下文存在大量冗余、低价值的历史消息，不需要全部保留，需要按规则批量清理，快速降低 token 占用。

**工作原理**

它是一个模型调用前的拦截器，执行逻辑：

1. 送入 LLM 前检查上下文规模
2. 当 token 量达到 `trigger` 触发阈值时，启动裁剪
3. 按照策略（通常从最早的消息开始删）至少清理出 `clearAtLeast` 指定的 token 量

**配置示例**

```
ContextEditingInterceptor editing = ContextEditingInterceptor.builder()
        .trigger(12000)       // 上下文达到12000 token时触发裁剪
        .clearAtLeast(6000)   // 至少清理出6000 token空间
        .build();
```

**压缩 vs 裁剪 核心区别**

| 维度     | Context Compaction（SummarizationHook）  | Context Editing（ContextEditingInterceptor） |
| -------- | ---------------------------------------- | -------------------------------------------- |
| 核心思路 | 总结替换：旧内容提炼成摘要，保留核心信息 | 按策略删除：直接裁掉旧内容，不保留           |
| 信息损失 | 低，关键事实可保留在摘要中               | 高，被裁剪的消息直接丢失                     |
| 额外成本 | 有，需要额外调用一次摘要模型             | 无，纯框架层面逻辑处理                       |
| 适用场景 | 对历史信息依赖强的长对话                 | 对历史依赖弱、轮次极多的场景                 |

> 两者可以同时挂载，但需要注意执行顺序，避免重复修改上下文导致异常，建议通过日志确认执行先后。

**删除内容时注意：**

**第一点：**

许多模型厂商（尤其是 Anthropic Claude 系列，以及部分 OpenAI 兼容网关）对 `messages` 数组的结构有强校验：

```
messages[0].role 必须是 "user"
```

如果你裁剪历史时，把开头的 `UserMessage` 删掉了，让列表变成以 `AssistantMessage` 或 `ToolResponseMessage` 开头，API 会直接拒绝请求。比如用 `MessageWindowChatMemory` 做滑动窗口时，它按“条数”裁剪，而不是按“轮次”裁剪。比如窗口大小设为 20，对话轮次多了之后，它可能把最早的一条 `UserMessage` 删掉，留下一条孤零零的 `AssistantMessage` 作为列表开头。

**避免方法**：

1、按轮次裁剪，而不是按条数。一轮 = `UserMessage` + `AssistantMessage`（+ 可能的 `ToolResponseMessage`）。删就删整轮。

2、如果自己实现裁剪逻辑，裁剪后检查 `messages.get(0).getMessageType() == USER`，不是就继续往前删，直到满足。

3、用框架自带的 memory 时，确认它的窗口策略是“保留最近 N 轮”还是“最近 N 条”。前者安全，后者可能踩坑。

**第二点**：**更常见**的一条

像OpenAI、Anthropic、DashScope 等几乎所有支持 function calling 的厂商都强制要求：

```
AssistantMessage(toolCalls=[call_abc]) 
    → 必须紧跟 ToolResponseMessage(id=call_abc)
```

这两条消息是**原子配对**的，不能拆开。如果你删掉了 `ToolResponseMessage`，只留下带 `toolCalls` 的 `AssistantMessage`，API 会报：

```
An assistant message with 'tool_calls' must be followed by tool messages 
responding to each 'tool_call_id'.
```

用“按条数裁剪”的滑动窗口时，最容易出现这种情况：

```
... 
[17] AssistantMessage(toolCalls=[call_abc])   ← 被保留
[18] ToolResponseMessage(id=call_abc)          ← 被裁掉
[19] AssistantMessage(text="成都今天晴，25℃")
```

窗口往前滑动，`[18]` 被淘汰，但 `[17]` 还在。下一轮请求发出去，API 直接 400。

**更隐蔽的情况**：一轮里模型调了多个工具：

```
AssistantMessage(toolCalls=[call_1, call_2, call_3])
ToolResponseMessage(id=call_1)
ToolResponseMessage(id=call_2)   ← 这条被裁掉
ToolResponseMessage(id=call_3)
```

只删了中间一条，API 也会报错：`tool_call_id call_2 not found`。

**避免方法：**

1、**按“工具调用组”裁剪**：一个 `AssistantMessage(toolCalls=[...])` 加上它后面所有的 `ToolResponseMessage`，视为一个**不可分割的原子块**。要删就整块删，要么全留。

2、如果自己实现裁剪，裁剪后做一次**配对校验**：遍历 messages，遇到带 `toolCalls` 的 assistant，检查后面是否有对应每个 `toolCallId` 的 tool 响应。缺失就把这个 assistant 也删掉。

3、用框架自带的 memory 时，确认它是否处理了工具消息的配对。有些简单实现只按条数裁，不管配对，就会踩坑。

### 5.3 调用次数限流：模型 + 工具双层限流

#### 5.3.1 模型调用限流：ModelCallLimitHook

**解决的问题**：ReAct 模式下模型容易陷入 “思考→调用工具→再思考→再调用” 的死循环，导致 token 成本飙升、执行超时。

**工作原理**：限制单次 Agent 运行内，大模型的最大调用次数，达到上限后触发预设的退出行为。

**配置示例**

```
ModelCallLimitHook modelLimit = ModelCallLimitHook.builder()
        .runLimit(8)    // 单次运行内，模型最多调用8次
        .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR) // 超限行为：抛错
        .build();
```

**两种退出行为**：

- `ERROR`：直接抛出异常，适合开发测试阶段，快速暴露死循环问题
- `END`：直接结束 Agent 运行，返回友好的结束语，适合生产环境，避免报错影响用户

#### 5.3.2 工具调用限流：ToolCallLimitHook

**解决的问题**：防止工具被频繁调用，尤其是高风险写操作工具（退款、删数据）被滥用，同时控制单会话的工具调用总量。

**工作原理**：可以针对全局所有工具，也可以指定单个工具，分别限制「单次运行内调用次数」和「单会话累计调用次数」。

**配置示例**

```
ToolCallLimitHook toolLimit = ToolCallLimitHook.builder()
        .toolName("refund_order")  // 可选：只限制退款工具；不填则限制全部工具
        .threadLimit(20)           // 整个会话内，该工具最多调用20次
        .runLimit(5)               // 单次运行内，该工具最多调用5次
        .exitBehavior(ToolCallLimitHook.ExitBehavior.END) // 超限结束运行
        .build();
```

**设计建议**：

- 查询类工具（查订单、查物流）限制可以宽松，保障交互流畅
- 高风险写操作工具（退款、打款、删数据）限制要严格，runLimit 设低

### 5.4 工具重试：ToolRetryInterceptor

**心定位**：处理工具瞬时故障的容错机制，提升 Agent 整体可用性。

**解决的问题**

工具调用经常会遇到瞬时故障：网络超时、下游服务 5xx、临时限流等，直接失败会中断流程、影响体验，没必要直接终止。

**工作原理**

它是工具调用层面的拦截器，工具调用失败时自动重试，达到最大重试次数后执行预设的失败策略。

**配置示例**

```
ToolRetryInterceptor toolRetry = ToolRetryInterceptor.builder()
        .maxRetries(2)  // 最多重试2次
        .onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
        .build();
```

**核心要点**

1. **必须设上限**：禁止无界重试，避免雪崩效应，生产环境建议 1~2 次
2. **写操作要幂等**：退款、打款这类写操作工具，自身必须保证幂等（同一订单号重复调用不会重复执行），否则重试会造成资产损失
3. **失败降级**：`RETURN_MESSAGE` 策略会把错误信息作为观察结果返回给模型，由模型决定下一步处理（比如换方式、告知用户），而不是直接让 Agent 崩溃

> 补充：还有对应的 `ModelRetryInterceptor`，专门处理模型调用侧的瞬时错误（网络超时、限流），和工具重试是两层独立的容错机制。

### 5.5 任务规划：TodoListInterceptor

**核心定位**：复杂多步任务的「先规划、后执行」机制，提升执行逻辑性和透明度。

**解决的问题**

复杂多步任务（比如调研、跨系统操作）中，模型容易走一步看一步，出现漏步骤、重复操作、逻辑混乱的问题，且执行过程不透明。

**工作原理**

它是一个执行前拦截器，在正式执行任务前，强制模型先输出一份待办清单（Todo List），明确执行步骤和目标，确认规划后再一步步执行。

**配置示例**

```
TodoListInterceptor planning = TodoListInterceptor.builder().build();

ReactAgent agent = ReactAgent.builder()
        .name("planning_agent")
        .model(chatModel)
        .tools(tools)
        .interceptors(planning)  // 挂载规划拦截器
        .build();
```

**适用场景与注意事项**

- 适合：复杂调研、多系统编排、需要向用户展示执行计划的透明场景
- 不适合：简单一问一答场景，会多一轮规划的模型调用，增加不必要的延迟和成本

### 5.6 动态工具选择

**核心定位**：解决「工具太多→token 占用高、模型易选错、权限难管控」的问题，按需暴露工具。

工具数量多了之后，把所有工具定义全塞进上下文，会占用大量 token，还会增加模型选错工具的概率，也不好做权限控制。主流有两种实现方案：

**方案 1：LLM 自动选工具 —— ToolSelectionInterceptor**

**工作原理**：增加一层轻量决策，先让模型从全量候选工具中，挑选出当前问题相关的工具，只把选中的工具暴露给本次主模型调用，大幅减少上下文中的工具定义数量。

**配置**：

```
ToolSelectionInterceptor selector = ToolSelectionInterceptor.builder().build();
```

**方案 2：自定义规则动态控权 —— 自定义 ModelInterceptor**

适合基于角色、权限、用户等级等业务规则，动态控制工具可见性，比自动选择更可控。

**示例：按用户角色分配工具**

```
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

> 补充：还可以通过 `dynamicToolCallbacks(...)` 在运行时动态追加工具，比如高级会员解锁专属能力。

### 5.7 Skills 渐进披露（Progressive Disclosure）

**核心定位**：Agent 能力的按需加载机制，解决「能力太多→上下文爆炸、模型混淆」的问题。

**解决的问题**

如果 Agent 有几十上百个技能，把所有技能的说明、工具、规则全部塞进上下文，token 会爆炸，模型也容易混淆不同技能的规则。Skills 机制采用「渐进披露」的思路，不用的技能不占上下文，需要的时候再加载。

**三层加载机制**

1. **元数据层**：只有技能的 `name + description`，几乎常驻上下文，体积很小，相当于技能目录，让模型知道有哪些能力
2. **SKILL.md 正文层**：技能的详细规则、使用说明、注意事项，只有模型主动调用 `read_skill` 工具后，才会加载到上下文
3. **资产层**：技能对应的脚本、参考资料、配置文件等，真正执行时才按需加载

**技能结构约定**

目录结构：

```
skills/
└── refund-policy/
    └── SKILL.md
```

SKILL.md 最小形态：

```
---
name: refund-policy
description: "电商退款政策与操作要点。用户问退款规则、几日内可退、如何发起退款时使用。"
---

# 退款政策技能
1. 先查订单状态，再判断是否可退。
2. 涉及真实退款必须调用 refund_order，且需人工审批。
3. 禁止承诺「一定能退」。
```

**装配与工作流程**

**装配示例**：

```
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

**执行流程**：

1. 模型初始只能看到所有技能的元数据（名称 + 描述）
2. 当判断当前问题需要某个技能时，模型调用 `read_skill` 工具读取该技能详情
3. 拦截器将该技能对应的规则、工具注入到后续的模型请求上下文中
4. 模型基于完整的技能信息执行任务

这种方式既保留了 Agent 的丰富能力，又避免了全量加载导致的 token 浪费，是中大型 Agent 系统的标准设计。



### 6.1 案例

先准备技能文件（放在项目 `src/main/resources/skills/refund-policy/SKILL.md`；本地开发也可用文件系统目录，见下方注释）：

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

### 6.2 建议自测顺序

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

期望：会调 `read_skill`（`refund-policy`），再基于 SKILL.md 回答。

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

确认：`status=interrupted`，且 `pending.toolFeedbacks[0].name` 为 `refund_order`。服务端已按 `threadId` 缓存完整中断态，**resume 不必再带 pending**。

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

期望：`status=completed`，退款工具真正执行（金额仍为模型申请的 199）。

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

期望：按 **99.0** 执行退款（以 `editedArguments` 为准）。

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

期望：**不会**真正退款；Agent 根据 `reason` 向用户说明。 

## 下一篇预告

多智能体编排：`Sequential` / `Parallel` / `Routing` / `Loop`，以及 Supervisor、Handoffs。  

---

## 官方参考

- Hooks / Interceptors：https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks  
- 记忆与 Checkpoint：https://java2ai.com/docs/frameworks/agent-framework/tutorials/memory  
- HITL 示例：`examples/.../HumanInTheLoopExample.java`  
- Skills 示例：`spring-ai-alibaba-agent-example/skills-agent-example`
