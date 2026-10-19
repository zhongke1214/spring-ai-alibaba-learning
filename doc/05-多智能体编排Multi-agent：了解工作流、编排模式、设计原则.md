# 多智能体编排Multi-agent：了解工作流、编排模式、设计原则

## 1. 前期准备

版本基准：Spring AI Alibaba 1.1.2.x + Spring AI 1.1.2 + JDK 17+

官方文档：https://java2ai.com

多智能体：https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent

示例路径：`examples/multiagent-patterns/`（含 `supervisor`、`routing`、`pipeline`、`handoffs-*` 等）

版本跟仓库约定对齐：

```text
JDK                 17+
Spring Boot         3.5.7
Spring AI           1.1.2
Spring AI Alibaba   1.1.2.2
```

依赖与 ReactAgent 篇相同（`spring-ai-alibaba-agent-framework` + DashScope），无需新 starter。

---

## 2. 为什么要多智能体？

在构建 AI 应用时，一开始都会选择“单 Agent + 多工具”的方案：一个 Agent 负责理解用户、选择工具、组织答案，似乎足够简单。但当工具越来越多、职责越来越杂，问题就会逐渐暴露。

| 症状 | 根因 |
| --- | --- |
| 乱调工具 / 选错专家Agent能力 | 工具列表过长，模型选不准 |
| 上下文爆炸 | 所有领域知识塞进一个 Prompt |
| 改一处坏一片 | 耦合所有业务 |
| 难测试 | 无法对单一业务做隔离验收 |

这四类问题本质上都指向同一个矛盾：**一个 Agent 承担了过多职责**。工具一多，模型选择成本上升；知识一杂，上下文迅速膨胀；业务耦合在一起，修改风险变高；没有清晰边界，测试也很难隔离。

因此衍生出Multi-agent，Multi-agent的核心思路是把复杂应用拆成**更小、更专**的 Agent，再组合成协调工作流。这样做的好处是：每个 Agent 只关注自己的领域，工具列表更短，上下文更聚焦，职责边界更清晰，也更容易单独测试和验收。官方给了两条协作主轴：

| 模式             | 控制流                                | 子 Agent 是否直接对用户说话 | 适合                    |
| ---------------- | ------------------------------------- | --------------------------- | ----------------------- |
| **Tool Calling** | 集中：总控决定一切                    | 否，只回结果给总控          | 任务编排、结构化流程    |
| **Handoffs**     | 去中心：当前 Agent 可把“话筒”递给别人 | 是，新 Agent 继续聊用户     | 跨域对话、专家Agent接管 |

**Tool Calling：集中式总控编排**

Tool Calling 模式下，总控 Agent 负责决定下一步做什么，子 Agent 更像被调用的“专家工具”。子 Agent 只把结果返回给总控，不直接面向用户。

这种模式适合任务编排和结构化流程。比如一个总控 Agent 先判断用户意图，再调用机票查询 Agent、酒店查询 Agent、报销政策 Agent，最后统一汇总结果给用户。

它的优势是控制流集中、流程清晰、结果可控；代价是总控需要承担较多决策责任。

**Handoffs：去中心化专家Agent接管**

Handoffs 则是另一种思路：当前 Agent 可以把“话筒”交给另一个 Agent，新 Agent 直接继续和用户对话。

这种模式适合跨域对话和专家接管。比如通用客服 Agent 发现用户在问退款争议，就把对话交接给售后专家 Agent，由后者直接和用户继续沟通。

它的优势是对话自然、专家可以接管复杂场景；代价是控制流更分散，需要设计好交接边界和兜底策略。

**两种模式可以混用**

Tool Calling 和 Handoffs 并不是二选一的关系。实际系统里可以混用：

> **交接换人，每人内部再把专家agent当 Tool 调。**

也就是说，顶层可以通过 Handoffs 把用户交给某个领域 Agent；这个领域 Agent 内部再用 Tool Calling 调用更细粒度的专家agent能力。这样既有自然的对话交接，也有结构化的任务编排。

---

## 3. 上下文工程：子 Agent 到底应该看见什么上下文？

单 Agent 工具一多、职责一杂，就容易乱调工具、上下文爆炸、改一处坏一片、难测试。Multi-agent 通过拆分更小、更专的 Agent 来解决这些问题。

但拆完 Agent 之后，下一个关键问题就来了：**子 Agent 到底应该看见什么上下文？**

多 Agent 系统的质量，几乎取决于**上下文裁剪**。给多了，子 Agent 被无关信息干扰；给少了，任务又缺关键输入。上下文工程的核心，就是控制每个 Agent 的“视野”。

**关键参数：控制子 Agent 的上下文边界**

官方强调的几个关键参数如下：

| 参数                          | 作用                                                  |
| ----------------------------- | ----------------------------------------------------- |
| **`name` / `description`**    | 靠描述选人，必须写清边界                              |
| **`instruction`**             | 本节点任务说明；支持 `{input}` / `{outputKey}` 占位符 |
| **`outputKey`**               | 本 Agent 产出写入状态的键，供后续 `{xxx}` 引用        |
| **`includeContents`**         | 是否把父流程上下文带给子 Agent                        |
| **`returnReasoningContents`** | 子 Agent 推理过程是否回灌父消息历史                   |

这几个参数分别回答了四个问题：

1. **别人怎么找到这个 Agent？** 靠 `name` / `description`，所以描述必须写清能力边界。
2. **这个 Agent 要做什么？** 靠 `instruction`，并且可以通过占位符引用输入和上游产出。
3. **它的产出怎么给下游用？** 靠 `outputKey` 写入状态，后续用 `{xxx}` 引用。
4. **它要不要继承父流程上下文？** 靠 `includeContents` 控制，设为 `false` 通常更专注。
5. **它的推理过程要不要回灌父消息历史？** 靠 `returnReasoningContents` 控制。

**占位符示例：写作 Agent 与润色 Agent**

下面是一个典型的两段式流程：写作 Agent 先产出文章，润色 Agent 再引用文章进行润色。

```
ReactAgent writer = ReactAgent.builder()
        .name("writer_agent")
        .model(chatModel)
        .instruction("请根据主题写作：{input}")
        .outputKey("article")
        .build();

ReactAgent reviewer = ReactAgent.builder()
        .name("reviewer_agent")
        .model(chatModel)
        .instruction("请润色下文，只返回终稿：\n{article}")
        .outputKey("reviewed_article")
        .build();
```



这里的关键链路是：

- `writer_agent` 通过 `{input}` 接收主题，产出写入 `article`；
- `reviewer_agent` 通过 `{article}` 引用上游文章，产出写入 `reviewed_article`。

也就是说，`outputKey` 是 Agent 之间传递上下文的状态键，占位符则是下游读取这些状态的入口。

**注意：占位符不是“写了就一定有”**

使用占位符时，有几个约定必须守住：

1. **`outputKey` 命名语义化**：比如 `article`、`reviewed_article`，让下游一眼看懂引用的是什么。
2. **占位符与 key 一致**：`outputKey("article")` 对应 `{article}`，不要写错。
3. **缺值时占位符可能原样留下**：如果上游没有真正写出该 key，下游 Prompt 里的 `{article}` 可能不会被替换。
4. **前面 Agent 必须真写出该 key**：占位符能否生效，取决于上游 Agent 是否真的把产出写入了对应的 `outputKey`。

换句话说，上下文工程不是简单拼 Prompt，而是**用状态键和占位符显式管理 Agent 之间的信息流**。

---

## 4. 内置工作流 Agent：四种编排模式怎么选？

前面了解了多智能体的拆分和上下文工程：子 Agent 要更小、某方向更专业，并且只看见该看见的上下文。

但拆出多个子 Agent 之后，还需要一个“编排层”来决定它们怎么协作。内置工作流 Agent 就是用来解决这个问题的：它负责组织多个子 Agent 的执行顺序、并发方式、路由选择和迭代策略。

常见的内置工作流 Agent 有四类：SequentialAgent、ParallelAgent、LlmRoutingAgent 和 LoopAgent。

### 4.1 SequentialAgent：顺序流水线

SequentialAgent 的特点是**固定顺序**：A → B → C，后者用 `{前者 outputKey}` 接力。

java

```
SequentialAgent blogAgent = SequentialAgent.builder()
        .name("blog_agent")
        .description("先写后评")
        .subAgents(List.of(writerAgent, reviewerAgent))
        .build();

Optional<OverAllState> result = blogAgent.invoke("写一篇100字西湖散文");
result.ifPresent(state -> {
    state.value("article").ifPresent(System.out::println);
    state.value("reviewed_article").ifPresent(System.out::println);
});
```



这段代码里，`writerAgent` 先写，`reviewerAgent` 再评。后者可以通过 `{article}` 这样的占位符引用前者写入 `outputKey` 的产出。

**适合场景**：步骤明确、结果可接力的流水线，比如“写 → 审 → 发”。

**注意点**：顺序是写死的，适合流程稳定的任务；上下游之间要通过 `outputKey` 和占位符正确接力。

### 4.2 ParallelAgent：并行后汇总

ParallelAgent 会把同一输入同时发给多个子 Agent，再按 `MergeStrategy` 合并结果。

```
ParallelAgent parallel = ParallelAgent.builder()
        .name("parallel_creative")
        .description("并行写散文、诗、总结")
        .subAgents(List.of(proseAgent, poemAgent, summaryAgent))
        .mergeOutputKey("merged_results")
        .mergeStrategy(new ParallelAgent.DefaultMergeStrategy())
        .build();
```



这里 `proseAgent`、`poemAgent`、`summaryAgent` 会并行处理同一输入，最终结果写入 `merged_results`。

**适合场景**：互不依赖的扇出任务，比如多源检索、多风格生成。

**注意点**：写操作并行要小心幂等与冲突。并行适合读多写少、彼此独立的子任务，不适合多个 Agent 同时修改同一份状态。

### 4.3 LlmRoutingAgent：意图路由（单次）

LlmRoutingAgent 让 LLM 根据子 Agent 的 **`description`** 选**一个**专家Agent执行，然后结束。

```
LlmRoutingAgent router = LlmRoutingAgent.builder()
        .name("content_router")
        .model(chatModel)
        .description("按意图路由到写作/评审/翻译专家")
        .subAgents(List.of(writerAgent, reviewerAgent, translatorAgent))
        .build();

router.invoke("帮我写一篇春天的散文"); // → writer
```



这类 Agent 像一个“分诊台”：用户请求进来后，路由 Agent 判断意图，选一个最合适的专家处理。

**关键原则**：描述写糊 = 路由必糊。子 Agent 的 `description` 必须写清能力边界。必要时可以加 `systemPrompt` / `instruction` 来强化选人规则。

**适合场景**：单次分诊、按意图选择专家的场景。

### 4.4 LoopAgent：迭代直到满足条件

LoopAgent 只挂**一个** `subAgent`，配合 `loopStrategy` 反复执行，比如按次数、条件或 JSON 数组迭代等。

```
LoopAgent loop = LoopAgent.builder()
        .name("refine_loop")
        .description("反复润色直到达标或达上限")
        .subAgent(refinerAgent)
        .loopStrategy(LoopMode.count(3)) // 或以 condition(predicate) 等，以版本 API 为准
        .build();
```



它适合让同一个 Agent 反复改进结果，直到满足条件或达到次数上限。

**适合场景**：自我改进、批量扫元素。

**注意点**：务必设上限，防死循环烧 Token。循环一定要有明确的退出条件或次数限制。

### 4.5 四种内置怎么选？

| 模式       | 路径       | 次数     | 一句话         |
| ---------- | ---------- | -------- | -------------- |
| Sequential | 写死顺序   | 每人一次 | 流水线         |
| Parallel   | 同时扇出   | 每人一次 | 齐头并进再合并 |
| LlmRouting | LLM 选一个 | 单次     | 分诊台         |
| Loop       | 同一人反复 | 多次     | do-while       |

可以按任务形态快速判断：

- 流程固定、前后依赖：选 **Sequential**；
- 子任务互不依赖、可以同时做：选 **Parallel**；
- 需要按意图选一个专家：选 **LlmRouting**；
- 需要同一个 Agent 反复打磨：选 **Loop**。

---

## 5. 常见协作模式

四种内置工作流 Agent：Sequential、Parallel、LlmRouting、Loop，分别解决顺序、并行、路由和迭代问题。

但在真实业务里，光靠一个内置模式往往不够。多 Agent 系统还需要更灵活的协作方式：谁来调度、子 Agent 怎么接任务、什么时候把对话交给别人、固定流程和动态决策怎么混着用。

下面整理几种常见协作模式

### 5.1 Supervisor：总控多步调度（ReactAgent + AgentTool）

Supervisor 的做法是：用一个中心 **`ReactAgent` 当总控**，把各专家Agent用 **`AgentTool.getFunctionToolCallback`** 挂成工具；总控按需多轮调用专家Agent，再汇总成对用户的回复。官方示例见 `examples/multiagent-patterns/supervisor`。

它和 LlmRouting 看起来都像「选人」，差别在于：

| | LlmRoutingAgent | Supervisor |
| --- | --- | --- |
| 形态 | FlowAgent，选一个 `subAgent` 后结束 | `ReactAgent`，专家是 **Tools** |
| 次数 | 单次路由 | 可多轮调多个专家工具 |
| 子完成后 | 直接结束 | 结果回到总控，可再调工具或直接答用户 |
| 适合 | 单意图分诊 | 「先机票再酒店再行程」等多步任务 |

```java
// 专家：name/description 会成为工具名与工具描述；inputType(String) 接收自然语言子任务
ReactAgent writerAgent = ReactAgent.builder()
        .name("writer_agent")
        .model(chatModel)
        .description("写作专家。只负责根据主题起草文稿。")
        .systemPrompt("你是写作助手，根据输入的主题写出短文，只返回正文。")
        .inputType(String.class)
        .build();

ReactAgent translatorAgent = ReactAgent.builder()
        .name("translator_agent")
        .model(chatModel)
        .description("翻译专家。只负责把文本译成目标语言。")
        .systemPrompt("你是翻译助手，按输入要求翻译，只返回译文。")
        .inputType(String.class)
        .build();

ReactAgent supervisor = ReactAgent.builder()
        .name("content_supervisor")
        .model(chatModel)
        .systemPrompt("""
                你是内容总控。可调用 writer_agent、translator_agent 完成多步骤任务。
                需要写作时调 writer_agent；需要翻译时调 translator_agent。
                可连续调用多个工具，最后把结果汇总成对用户的回复。
                """)
        .tools(
                AgentTool.getFunctionToolCallback(writerAgent),
                AgentTool.getFunctionToolCallback(translatorAgent))
        .saver(new MemorySaver())
        .build();
```

要点：

- 专家Agent对总控来说就是 **Tool**；用户始终面对 Supervisor，不直接跟专家Agent聊。  
- 专家用 **`inputType(String.class)`**，总控传入一段自然语言子请求。  
- 调度靠 **Tool Calling 多轮**：需要几个专家就调几次，最后由总控合成答复。  

一句话区分：**Routing 是分诊台，选一次就结束；Supervisor 是调度中心，把专家当工具反复调用再汇总。**

### 5.2 Subagent：任务下发与产出

子 Agent 本质上就是带清晰 `name` / `description` 的 ReactAgent（也可是 FlowAgent）。父流程常见用法：

- **FlowAgent 的 `subAgents(...)`**：编入 Sequential / Parallel / Routing / Loop；
- **`AgentTool.getFunctionToolCallback(agent)`**：编入 Supervisor；
- **状态 `outputKey` + 占位符**：适合 Sequential 等流水线接力；
- **`includeContents(false)`**：子任务少被父上下文干扰。

Supervisor 路径下，专家Agent结果主要通过 **工具返回文本** 回到总控；流水线路径下才重度依赖 `outputKey`。两者别混用预期。

### 5.3 Handoffs：会话移交

Handoffs 是当前 Agent 主动把“活动话筒”交给另一个 Agent，**用户接下来直接和新 Agent 聊**。

它适合客服场景里的“转人工技术支持”“转账单专家”等跨域对话。

和 Supervisor 的关键区别在于：

- **Supervisor 是幕后调工具**：子 Agent 干完把结果交回总控，用户始终面对总控；
- **Handoffs 换的是对用户可见的对话主体**：交接后，新 Agent 直接继续和用户对话。

一句话：**Supervisor 换的是执行者，Handoffs 换的是说话人。**

### 5.4 Skills 与 Workflow

在多 Agent 体系里，还有两个常见的组织手段：

- **Skills**：上一篇讲过渐进披露；在多 Agent 里，常给每个专家挂不同的技能包，让专家按需加载能力。
- **Workflow**：路径更固定时，可以用 Sequential / Parallel 组合；如果还需要条件分支，可以进一步下沉到 **Graph**，用条件边表达更复杂的流程。

简单说：Skills 管“每个专家会什么”，Workflow 管“这些专家按什么路径走”。

## 5.5 混合模式：复杂业务靠嵌套

**先看懂这张图**

```
Parallel(多源调研) → Analysis → LlmRouting(报告格式)
          ↑______________ Sequential 串起来 ______________↑
```



把它拆开，其实是三层嵌套：

1. **最外层是 Sequential**：整体按“调研 → 分析 → 出报告”的顺序串起来；
2. **第一步内部是 Parallel**：多源调研同时扇出，齐头并进；
3. **最后一步是 LlmRouting**：根据报告格式，路由到对应的格式专家。

也就是说：**外层 Sequential 控制主流程，内层按需嵌套 Parallel、Routing；多轮专家调度再用 Supervisor。**

#### 为什么官方推荐“嵌套”而不是“发明新模式”

常见积木可以这样理解：

| 模式 | 控制结构 | 实现形态 |
| --- | --- | --- |
| Sequential | 顺序 | FlowAgent |
| Parallel | 并发 | FlowAgent |
| LlmRouting | 选择 | FlowAgent |
| Loop | 循环 | FlowAgent |
| Supervisor | 多轮派专家 | ReactAgent + AgentTool |

复杂业务无非是这些结构的**组合**。与其从零发明一种「超级模式」，不如嵌套——**可复用、可测试、边界清晰**。

#### 一个更完整的混合编排示例

假设要做一个「竞品分析报告」系统：

```
Sequential(
    Parallel(         // 第一步：多源调研，并行
        网页搜索 Agent,
        财报解析 Agent,
        新闻聚合 Agent
    ),
    Analysis Agent,   // 第二步：汇总分析
    Supervisor        // 第三步：多轮打磨（ReactAgent + AgentTool）
        tools: writer_agent, reviewer_agent
)
```

对应关系：

- **Parallel** 解决「多源调研互不依赖」；
- **Sequential** 保证「调研完才分析，分析完才写报告」；
- **Supervisor** 处理「写 → 审 → 改」的多轮工具调度。

这就是典型的**模式嵌套**：FlowAgent 管固定结构，Supervisor 管动态多专家调用。



---

## 6. 设计原则

1. **单一职责**：一个 Agent 一类事（机票 / 酒店 / 行程）。  
2. **`name` + `description` 写给人看也写给模型看**：路由完全依赖描述。  
3. **共享状态 vs 消息传递**：Sequential 等用 `outputKey`；Supervisor（AgentTool）用工具返回文本。别混预期。  
4. **禁止全能 Agent**：总控只调度，细节下沉专家；专家 `description` 必须写清边界（路由/工具选择都靠它）。  
5. **失败隔离**：子 Agent / 工具失败应有兜底（可读错误 / 总控改派 / 结束），别拖垮整个团队。

---

## 7. 案例

### 7.1 示例代码

```java
package space.kmind.zk.springaialibabalearning.stage05;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LoopAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.loop.LoopMode;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Tag(name = "Stage05-多智能体", description = "Sequential/Parallel/Routing/Loop/Supervisor/混合")
@RestController
public class ATravelMultiAgentDemo {

    private final SequentialAgent sequentialAgent;
    private final ParallelAgent parallelAgent;
    private final LlmRoutingAgent routingAgent;
    private final LoopAgent loopAgent;
    private final ReactAgent supervisorAgent;
    private final SequentialAgent hybridAgent;

    public ATravelMultiAgentDemo(ChatModel chatModel) throws Exception {

        // ========== §3 + §4.1：Sequential + outputKey + 占位符 ==========
        ReactAgent drafter = ReactAgent.builder()
                .name("itinerary_drafter")
                .model(chatModel)
                .description("行程草稿员。只写按天行程初稿。")
                .instruction("根据用户需求写一份按天行程草稿，只返回草稿正文。用户需求：{input}")
                .outputKey("itinerary_draft")
                .build();

        ReactAgent polisher = ReactAgent.builder()
                .name("itinerary_polisher")
                .model(chatModel)
                .description("行程润色员。只润色已有草稿。")
                .instruction("请润色以下行程草稿，使其更清晰，只返回终稿：\n{itinerary_draft}")
                .outputKey("itinerary_final")
                .includeContents(false)
                .build();

        this.sequentialAgent = SequentialAgent.builder()
                .name("itinerary_sequential")
                .description("先拟草稿再润色")
                .subAgents(List.of(drafter, polisher))
                .build();

        // ========== §4.2：Parallel 扇出后合并 ==========
        ReactAgent flightP = expert(chatModel, "flight_parallel",
                "机票调研。只回答航班与票价要点。",
                "你是机票调研员。针对用户差旅需求给出航班要点。需求：{input}",
                "flight_plan");
        ReactAgent hotelP = expert(chatModel, "hotel_parallel",
                "酒店调研。只回答住宿要点。",
                "你是酒店调研员。针对用户差旅需求给出住宿要点。需求：{input}",
                "hotel_plan");
        ReactAgent weatherP = expert(chatModel, "weather_parallel",
                "天气调研。只回答目的地天气与穿衣建议。",
                "你是天气顾问。针对用户目的地给出天气与穿衣建议。需求：{input}",
                "weather_plan");

        this.parallelAgent = ParallelAgent.builder()
                .name("travel_parallel")
                .description("并行调研机票、酒店、天气")
                .subAgents(List.of(flightP, hotelP, weatherP))
                .mergeOutputKey("research_bundle")
                .mergeStrategy(new ParallelAgent.DefaultMergeStrategy())
                .build();

        // ========== §4.3：LlmRouting 单次分诊（description 定边界）==========
        ReactAgent flightR = expert(chatModel, "flight_agent",
                "机票专家。只处理航班、舱位、票价。不处理酒店、天气、行程。",
                "你是机票顾问。给出航班方案要点。用户问题：{input}",
                "flight_plan");
        ReactAgent hotelR = expert(chatModel, "hotel_agent",
                "酒店专家。只处理住宿、区位、价格。不处理机票、天气、行程。",
                "你是酒店顾问。给出住宿建议要点。用户问题：{input}",
                "hotel_plan");
        ReactAgent itineraryR = expert(chatModel, "itinerary_agent",
                "行程专家。只处理景点与日程安排。不处理订票订房。",
                "你是行程规划师。给出按天行程。用户问题：{input}",
                "itinerary_plan");
        // §3：天气专家关掉父上下文，避免被「顺带提一句机票」带偏
        ReactAgent weatherR = ReactAgent.builder()
                .name("weather_agent")
                .model(chatModel)
                .description("天气专家。只处理目的地天气与穿衣。不处理机票酒店行程。")
                .instruction("你是天气顾问。只根据用户问题给天气与穿衣建议。用户问题：{input}")
                .outputKey("weather_plan")
                .includeContents(false)
                .build();

        this.routingAgent = LlmRoutingAgent.builder()
                .name("travel_router")
                .description("差旅意图分诊")
                .model(chatModel)
                .systemPrompt("""
                        你是差旅分诊台。只返回下列名称之一：
                        flight_agent / hotel_agent / itinerary_agent / weather_agent
                        机票→flight_agent；酒店→hotel_agent；行程景点→itinerary_agent；天气穿衣→weather_agent。
                        """)
                .subAgents(List.of(flightR, hotelR, itineraryR, weatherR))
                .build();

        // ========== §4.4：Loop 有限次打磨 ==========
        ReactAgent refiner = ReactAgent.builder()
                .name("itinerary_refiner")
                .model(chatModel)
                .description("行程打磨员。每次把行程写得更具体一点。")
                .instruction("""
                        在现有思路上改进杭州行程，让时间安排更具体。
                        用户原需求：{input}
                        只返回改进后的完整行程。
                        """)
                .outputKey("refined_itinerary")
                .build();

        this.loopAgent = LoopAgent.builder()
                .name("itinerary_loop")
                .description("最多打磨 3 次")
                .subAgent(refiner)
                .loopStrategy(LoopMode.count(3))
                .build();

        // ========== §5.1：Supervisor = ReactAgent + AgentTool ==========
        ReactAgent flightTool = toolExpert(chatModel, "flight_agent",
                "机票专家。根据自然语言给出航班方案要点。",
                "你是机票顾问。根据输入给出航班方案要点，只返回要点。");
        ReactAgent hotelTool = toolExpert(chatModel, "hotel_agent",
                "酒店专家。根据自然语言给出住宿建议。",
                "你是酒店顾问。根据输入给出住宿建议要点，只返回要点。");
        ReactAgent itineraryTool = toolExpert(chatModel, "itinerary_agent",
                "行程专家。根据自然语言给出按天行程草稿。",
                "你是行程规划师。根据输入给出按天行程草稿，只返回草稿。");

        this.supervisorAgent = ReactAgent.builder()
                .name("travel_supervisor")
                .model(chatModel)
                .systemPrompt("""
                        你是差旅总控。可调用 flight_agent、hotel_agent、itinerary_agent。
                        综合需求时分步调用相关工具，最后汇总成给用户的完整说明。
                        只需单一领域时，只调对应一个工具即可。
                        """)
                .tools(
                        AgentTool.getFunctionToolCallback(flightTool),
                        AgentTool.getFunctionToolCallback(hotelTool),
                        AgentTool.getFunctionToolCallback(itineraryTool))
                .saver(new MemorySaver())
                .build();

        // ========== §5.5：混合嵌套 Sequential( Parallel → Summarizer ) ==========
        ReactAgent hybridSummarizer = ReactAgent.builder()
                .name("travel_summarizer")
                .model(chatModel)
                .description("差旅简报员。把并行调研结果整理成简报。")
                .instruction("""
                        根据下列并行调研材料，写一份给领导看的简洁中文简报（保留关键结论）：
                        {research_bundle}
                        """)
                .outputKey("final_brief")
                .includeContents(false)
                .build();

        // 复用上面的 parallelAgent 作为子步骤
        this.hybridAgent = SequentialAgent.builder()
                .name("travel_hybrid")
                .description("先并行调研，再串行汇总")
                .subAgents(List.of(parallelAgent, hybridSummarizer))
                .build();
    }

    private static ReactAgent expert(ChatModel model, String name, String desc,
                                     String instruction, String outputKey) {
        return ReactAgent.builder()
                .name(name)
                .model(model)
                .description(desc)
                .instruction(instruction)
                .outputKey(outputKey)
                .build();
    }

    private static ReactAgent toolExpert(ChatModel model, String name,
                                         String desc, String systemPrompt) {
        return ReactAgent.builder()
                .name(name)
                .model(model)
                .description(desc)
                .systemPrompt(systemPrompt)
                .inputType(String.class)
                .build();
    }

    @Operation(summary = "§4.1 Sequential：草稿→润色（outputKey 接力）")
    @PostMapping("/api/s51/sequential")
    public Map<String, Object> sequential(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日游，偏西湖与美食");
        return toView(sequentialAgent.invoke(q),
                List.of("itinerary_draft", "itinerary_final"));
    }

    @Operation(summary = "§4.2 Parallel：机票+酒店+天气并行合并")
    @PostMapping("/api/s52/parallel")
    public Map<String, Object> parallel(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "下周五去杭州两天，预算中等");
        return toView(parallelAgent.invoke(q),
                List.of("research_bundle", "flight_plan", "hotel_plan", "weather_plan"));
    }

    @Operation(summary = "§4.3 Routing：单意图分诊")
    @PostMapping("/api/s53/route")
    public Map<String, Object> route(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "下周五上海飞杭州机票怎么选？");
        return toView(routingAgent.invoke(q),
                List.of("flight_plan", "hotel_plan", "itinerary_plan", "weather_plan"));
    }

    @Operation(summary = "§4.4 Loop：行程最多打磨 3 次")
    @PostMapping("/api/s54/loop")
    public Map<String, Object> loop(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日游行程，请尽量具体到半天");
        return toView(loopAgent.invoke(q), List.of("refined_itinerary"));
    }

    @Operation(summary = "§5.1 Supervisor：专家当 Tool 多轮调度")
    @PostMapping("/api/s55/supervise")
    public Map<String, Object> supervise(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日游，帮我安排机票思路、住宿和行程");
        return toView(supervisorAgent.invoke(q), List.of());
    }

    @Operation(summary = "§5.5 混合：Parallel 调研后再 Sequential 汇总")
    @PostMapping("/api/s56/hybrid")
    public Map<String, Object> hybrid(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日：并行调研机票酒店天气并出简报");
        return toView(hybridAgent.invoke(q),
                List.of("research_bundle", "final_brief"));
    }

    private Map<String, Object> toView(Optional<OverAllState> opt, List<String> keys) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (opt.isEmpty()) {
            view.put("status", "empty");
            return view;
        }
        OverAllState state = opt.get();
        view.put("status", "ok");
        for (String key : keys) {
            state.value(key).ifPresent(v -> view.put(key, unwrap(v)));
        }
        // Supervisor 等：最终回复在 messages 末条
        state.value("messages").ifPresent(msgs -> {
            if (msgs instanceof List<?> list && !list.isEmpty()) {
                Object last = list.get(list.size() - 1);
                if (last instanceof AssistantMessage m) {
                    view.put("answer", m.getText());
                } else if (last instanceof Message m) {
                    view.put("answer", m.getText());
                }
            }
        });
        return view;
    }

    private Object unwrap(Object v) {
        if (v instanceof AssistantMessage m) {
            return m.getText();
        }
        return String.valueOf(v);
    }
}
```

> `LoopMode` 的包路径若编译报错，以你依赖里 `loop` 包实际类名为准（有的版本是 `LoopMode.count(3)`，语义不变：必须设上限）。

### 7.2 建议自测

以下均用 `Content-Type: application/json`。每条对应一张知识点表。

---

**① §3 + §4.1 Sequential：`outputKey` 接力**

`POST /api/s51/sequential`

```json
{"message": "杭州两日游，偏西湖与美食"}
```

**应出现：**

```json
{
  "status": "ok",
  "itinerary_draft": "Day1……（初稿）",
  "itinerary_final": "Day1……（润色后，通常更工整）"
}
```

**怎么判断：**

- 两个键都在 → 上游 `outputKey("itinerary_draft")` 已写出，下游 `{itinerary_draft}` 替换成功。  
- 若 `itinerary_final` 里仍原样出现 `{itinerary_draft}` → 占位符缺值（§3）。  
- `polisher` 配了 `includeContents(false)`，它应主要依据草稿键，而不是整段父对话。

---

**② §4.2 Parallel：扇出合并**

`POST /api/s52/parallel`

```json
{"message": "下周五去杭州两天，预算中等"}
```

**应出现：**

```json
{
  "status": "ok",
  "flight_plan": "……",
  "hotel_plan": "……",
  "weather_plan": "……",
  "research_bundle": "……（三者合并后的文本）"
}
```

**怎么判断：**

- `research_bundle`（或三个 `*_plan`）里机票 / 酒店 / 天气信息都有 → 扇出 + `MergeStrategy` 生效。  
- 日志上三个子 Agent 应接近同时开始，而不是严格串行。

---

**③ §4.3 Routing：单次分诊（description 边界）**

`POST /api/s53/route`

```json
{"message": "下周五上海飞杭州机票怎么选？"}
```

**应出现：** 主要为 `flight_plan`（一般不应同时有完整的 `hotel_plan` / `weather_plan`）。

再测天气（顺带验证 `includeContents(false)` 的天气专家）：

```json
{"message": "杭州这两天穿什么？会下雨吗？"}
```

**应出现：** 主要为 `weather_plan`。

**怎么判断：** 问法变了，返回的 key 应变；若总选错人 → 改子 Agent `description` 与路由 `systemPrompt`（§4.3「描述写糊 = 路由必糊」）。

---

**④ §4.4 Loop：有上限迭代**

`POST /api/s54/loop`

```json
{"message": "杭州两日游行程，请尽量具体到半天"}
```

**应出现：**

```json
{
  "status": "ok",
  "refined_itinerary": "……（多轮后更具体的行程）"
}
```

**怎么判断：** 日志中 `itinerary_refiner` 调用次数 **≤ 3**；若无限刷 → `LoopMode.count(3)` 未生效，立刻停掉。

---

**⑤ §5.1 Supervisor：专家当 Tool（看 answer，不看 *_plan）**

多步：

`POST /api/s55/supervise`

```json
{"message": "杭州两日游，帮我安排机票思路、住宿和行程"}
```

**应出现：**

```json
{
  "status": "ok",
  "answer": "……（总控汇总后的完整说明）"
}
```

**怎么判断：**

- 关键看 `answer`；**通常没有** Routing 那种单独的 `flight_plan` 键——专家结果经 Tool 回到总控。  
- 日志里 `flight_agent` / `hotel_agent` / `itinerary_agent` 作为工具被调用，综合问法下通常 ≥ 2 次。

单领域：

```json
{"message": "只帮我看看西湖附近住哪合适"}
```

**应出现：** 有 `answer`；日志里主要是 `hotel_agent`，不必三工具全开。

---

**⑥ §5.5 混合嵌套：Parallel → Sequential 汇总**

`POST /api/s56/hybrid`

```json
{"message": "杭州两日：并行调研机票酒店天气并出简报"}
```

**应出现：**

```json
{
  "status": "ok",
  "research_bundle": "……（并行阶段）",
  "final_brief": "……（汇总简报，应基于 research_bundle）"
}
```

**怎么判断：**

- 两个键都在 → 外层 Sequential、内层 Parallel 嵌套正确。  
- `final_brief` 不应仍带未替换的 `{research_bundle}`。  
- 这对应 §5.5「外层 Sequential 管主流程，内层 Parallel 扇出」。



---

## 下一篇预告

当「固定审批、条件分支、并行汇合」比「一群 Agent 商量」更重要时，下沉 **Graph**：`StateGraph`、条件边、中断恢复与 Mermaid 导出。

---

## 官方参考

- https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent  
- `examples/multiagent-patterns/`（pipeline / routing / supervisor / handoffs / …）  
- Supervisor：`examples/multiagent-patterns/supervisor`
