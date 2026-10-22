# 多智能体编排 Multi-agent：工作流、编排模式与设计原则

> 版本基准：**Spring AI Alibaba 1.1.2.x + Spring AI 1.1.2 + JDK 17+**
>
> - 官方文档：https://java2ai.com
> - 多智能体专章：https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent
> - 官方示例：`examples/multiagent-patterns/`（里面有 `supervisor`、`routing`、`pipeline`、`handoffs-*` 等完整可跑的例子）
>
> 依赖和 ReactAgent 那一篇完全一样：`spring-ai-alibaba-agent-framework` + DashScope，不需要引入任何新 starter。

---

## 一、先聊一个真实场景：单 Agent 是怎么一步步崩掉的

假设你在做一个"差旅助手"。第一版方案很自然：一个 ReactAgent，挂上机票查询、酒店查询、天气查询、行程规划、报销政策……七八个工具，用户问什么它自己挑。

上线头两天还挺灵。然后问题开始一个个冒出来：

**第一个症状：乱调工具。** 用户问"杭州这两天穿什么"，Agent 犹豫了一下，先调了酒店查询，又调了行程规划，最后才想起天气工具。不是模型笨，是工具列表太长了——对模型来说，从十几个名字相近的工具里挑一个，和在超市货架上找一瓶特定的酱油差不多，眼花。

**第二个症状：上下文爆炸。** 机票有机票的话术，酒店有酒店的注意事项，行程有行程的模板，你全塞进一个 system prompt。Token 花得飞快，模型注意力被稀释，回答质量反而下降。而且这些领域知识彼此干扰：用户明明只问天气，Agent 却在回复里"顺带"推荐起了酒店。

**第三个症状：改一处坏一片。** 产品说要调整行程规划的提示词，你小心翼翼地改了，结果机票查询的召回也跟着变了——因为它们共用同一个 Prompt，牵一发动全身。半夜被叫起来回滚，谁经历过谁知道。

**第四个症状：没法测试。** 想单独验证"行程规划"这个能力好不好使？做不到，它和其它逻辑长在一起，只能整个系统跑一遍然后靠人眼判断。

你会发现这四个毛病其实是同一个病：**一个 Agent 背了太多职责**。工具多了选择就难，知识杂了上下文就炸，职责混了改动就险，边界没了测试就虚。

多智能体（Multi-agent）的解法说白了就一句话：**像拆微服务那样拆 Agent**。机票归机票、酒店归酒店，每个 Agent 只管自己那一摊，工具列表短了、上下文聚焦了、职责边界清了，想测哪个单独拎出来测。拆出来的这些小 Agent，再用某种协作方式拼成一个完整系统——这个"怎么拼"，就是本文的主线。

---

## 二、拆完之后怎么协作？两条主轴

Agent 拆出来了，接下来要回答：它们之间谁听谁的？官方文档把协作方式归纳成两条主轴，理解了这两条，后面所有模式都是它们的变体或组合。

### 2.1 Tool Calling：总控说了算

第一种思路是**集中式**的。设一个总控 Agent，把各个领域 Agent 包成"工具"挂给它。用户的所有请求先到总控，总控判断该找谁，就调用谁；子 Agent 干完活，把结果交回总控，由总控汇总后统一回复用户。

打个比方：这就像一个项目经理带一群专家。客户所有需求都找项目经理，项目经理判断这个需求该找后端还是找设计，安排下去，拿到结果后再统一整理给客户。**客户自始至终只跟项目经理打交道**，专家是谁、有几个、在干什么，客户不用关心。

适合的场景是**任务编排类**：流程相对结构化，输入是任务、输出是结果。比如"帮我查下周五上海飞杭州的机票，顺便看看那两天杭州天气"——总控拆解成两个子任务，分别派给机票 Agent 和天气 Agent，拿回结果合并成一段完整答复。整个过程中用户插不上话，也不需要插话，要的就是结果。

优点很明显：控制流集中在总控手里，流程清晰、结果可控、出了问题好排查（日志里总控调了谁一目了然）。代价是总控的 Prompt 要写得足够好，决策压力大——它判断错了方向，后面全白干。

### 2.2 Handoffs：把话筒递出去

第二种思路是**去中心化**的。当前 Agent 发现这个话题自己不专业，可以直接把"话筒"递给另一个 Agent，**由新 Agent 直接面对用户继续聊**。

比方：你打客服电话，前台客服听两句说"这个问题我转接技术支持啊"，然后技术支持直接跟你对话。**用户感知到的是"换人接电话了"**，而不是前台客服跑去后台问完技术支持再转述给你——后者体验割裂，前者自然。

适合的场景是**对话接管类**：比如通用客服 Agent 聊着聊着发现用户在投诉退款纠纷，这是售后专员的领域，直接交接（handoff）给售后专家 Agent，由专家继续处理对话。用户体验是连贯的，仿佛一直有人专业地陪聊。

优点是对话自然、专家能接管复杂场景；代价是控制流分散了——谁接手、什么时候接、接不住怎么办，这些"交接边界"和"兜底策略"都得提前设计好，不然 A 交给 B、B 觉得不归我管又交回 A，用户就在两个 Agent 之间被踢皮球。

### 2.3 实际系统里，两者混着用

这两条主轴不是二选一。一个常见的成熟形态是：

> **顶层用 Handoffs 换人，每个领域 Agent 内部再用 Tool Calling 调度更细的专家。**

比如客服系统：入口是个通用接待 Agent，它判断用户在聊售后，handoff 给售后 Agent；售后 Agent 自己是个小总控，内部把"退款查询""物流追踪""政策解释"这些能力当成工具调用。对外体验是自然的对话交接，对内执行是结构化的任务编排，两层各取所长。

记住这个分层的感觉，后面讲 Supervisor 和 Handoffs 的 API 时会对上号。

---

## 三、上下文工程：每个 Agent 到底该"看见"什么

拆 Agent 只是第一步，真正决定系统质量的，是接下来这个问题：

**子 Agent 到底应该看见哪些上下文？**

给多了，子 Agent 被无关信息干扰（用户跟别人聊的酒店话题，凭什么塞给天气专家？）；给少了，任务又缺关键输入（行程规划师不知道用户预算怎么规划？）。上下文工程做的就是这件事：**给每个 Agent 精确划定视野**——像给每个工位配显示器，只显示它工作需要的信息。

Spring AI Alibaba 里，这个"视野"由几个关键参数控制。逐个说清楚：

### 3.1 `name` / `description`：别人怎么找到你

在多 Agent 体系里，一个 Agent 是不是"会被选中"，几乎完全取决于它的 description 写得好不好。路由 Agent 靠它分诊，总控 Agent 靠它挑工具——模型根本不会去"试用"每个 Agent，它只能读描述来决定。

所以 description 不是注释，**它是给别人选人用的广告牌**。一条合格的描述要说清楚两件事：我只管什么、我不管什么。对比一下：

- 差的写法："天气助手"。——管理者看了都头疼，问机票它会不会顺手接了？
- 好的写法："天气专家。只处理目的地天气与穿衣建议。不处理机票、酒店、行程。"——正面写能力，反面划边界，谁来选都不会错。

### 3.2 `instruction`：你这一步要干什么

instruction 是这个 Agent 的任务说明书，里面可以用占位符引用状态里的数据，比如 `{input}`（用户输入）、`{article}`（上游产出的文章）。占位符怎么运作，3.5 节用例子细讲。

### 3.3 `outputKey`：你的产出放在哪个货架上

Agent 执行完后，结果写到 OverAllState（全局状态）的哪个键下，由 `outputKey` 指定。这是 Agent 之间**传递数据的主通道**：上游写键，下游读键，一条链就串起来了。

### 3.4 `includeContents`：要不要继承父流程的对话历史

默认情况下，子 Agent 会看到父流程（比如整段 Sequential 流程）积累下来的消息历史。这不一定是你想要的——用户前面闲聊过三句酒店，你确定要让行程规划师也看到吗？多一份信息，多一分被带偏的可能。

设成 `false`，子 Agent 只看到自己的 instruction 里显式给到的东西，视野最干净。**经验法则：子任务越独立，越应该关掉**。像"根据这段草稿润色"这种输入完全自洽的任务，父上下文纯属噪音。

### 3.5 `returnReasoningContents`：推理过程要不要回流

子 Agent（尤其是深度思考型模型）的推理过程，要不要回灌到父流程的消息历史里？回灌的好处是父流程能"看到"子 Agent 的思考，坏处是占上下文、可能污染父流程。一般保持默认即可，需要调试或有特殊依赖时再动它。

### 3.6 把参数串起来：一个"写完再润色"的小例子

这五个参数单看都抽象，串成一个流程就活了。两段式流水线：写作 Agent 产出文章，润色 Agent 引用文章再加工：

```java
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

跑起来之后，状态里发生了什么？画个图就明白了：

```
用户输入 "写一篇关于西湖的散文"
        │
        ▼
writer_agent  执行
  读取 {input}   → "写一篇关于西湖的散文"（占位符替换成功）
  产出写入状态 → state["article"] = "西湖的水……"
        │
        ▼
reviewer_agent 执行
  读取 {article} → "西湖的水……"（占位符从状态里取值替换）
  产出写入状态 → state["reviewed_article"] = "（润色后终稿）"
```

一句话总结机制：**`outputKey` 是写入状态的键，占位符 `{xxx}` 是从状态读值的入口**。一写一读，Agent 之间不直接对话，全部通过状态（OverAllState）中转——这就是所谓"共享状态传递"。

### 3.7 占位符的坑：写了不等于有

这个机制有个非常容易踩的坑，值得单独强调：

**占位符能不能被替换，取决于上游是不是真的写出了对应的 `outputKey`。** 如果你写了 `{article}`，但上游 Agent 的 `outputKey` 拼成了 `articles`（多个 s），或者上游压根没配 `outputKey`，那么下游的 Prompt 里就会原模原样地出现字符串 `{article}`——模型会一脸懵地收到一句"请润色 {article}"，然后对着这串花括号开始自由发挥。

排查方法也简单：如果下游产出里**出现了未替换的花括号原文**，别怀疑模型，先检查上游 `outputKey` 的拼写。

配套的几条约定：

1. `outputKey` 命名要语义化：`article`、`reviewed_article`，下游读到占位符就知道自己在引用什么；
2. 占位符与 key 严格一致：`outputKey("article")` 对 `{article}`，一个字符都不能差；
3. 上游必须真的产出：链条里任何一环断了，下游全部遭殃。

到这里你会发现，所谓上下文工程，本质就是**用状态键和占位符，显式地、可见地管理 Agent 之间的信息流**。信息在哪写入、在哪读出，全在代码里写得明明白白，而不是靠消息历史顺其自然地"蔓延"。这既是性能优化，更是可维护性优化。

---

## 四、四种内置工作流 Agent：编排的"基本粒子"

上下文管好了，接下来解决执行顺序问题：十个子 Agent，谁先跑、谁后跑、谁并行、谁来跑几遍？框架内置了四个工作流 Agent（都叫 FlowAgent），对应四种最基础的编排形态。它们是积木块——后面所有复杂系统，都由这四块拼出来。

### 4.1 SequentialAgent：流水线

最朴素的一种：子 Agent 按传入 `subAgents` 的顺序**依次执行**，前面跑完后面才跑，数据靠上一节讲的 `outputKey` + 占位符接力。

```java
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

`writerAgent` 先执行，把文章写进 `article`；`reviewerAgent` 后执行，用 `{article}` 接住初稿做润色，结果写进 `reviewed_article`。最后从返回的 OverAllState 里按 key 取值即可。

**什么时候用它**：步骤固定、前后有依赖、每步的产出能被下一步直接消费。典型如"写 → 审 → 发"三段式、ETL 式的处理链。

**什么时候别用它**：顺序是**写死在代码里**的。如果流程本身会变（比如"审稿不通过就退回重写"这种带条件的流转），Sequential 表达不了，得下沉到 Graph（下一篇的内容）。

### 4.2 ParallelAgent：齐头并进

把同一个输入**同时扇出**给多个子 Agent，各自独立跑完，再按 `mergeStrategy` 把结果合并到 `mergeOutputKey` 指定的键下：

```java
ParallelAgent parallel = ParallelAgent.builder()
        .name("parallel_creative")
        .description("并行写散文、诗、总结")
        .subAgents(List.of(proseAgent, poemAgent, summaryAgent))
        .mergeOutputKey("merged_results")
        .mergeStrategy(new ParallelAgent.DefaultMergeStrategy())
        .build();
```

三个子 Agent 拿到同样的输入各干各的，`DefaultMergeStrategy` 会把三份产出拼合成一份文本，写入 `merged_results`。顾名思义"扇出"：一个输入进，N 路并行走，一路汇总出。

**什么时候用它**：子任务之间**互不依赖**。多源检索（同时查网页、查知识库、查数据库）、多风格生成（同一主题写散文、写诗、写摘要）是天然适合并行的两类活——它们既没有先后依赖，又明显能靠并发省时间。

**什么时候要小心**：并行 + 写操作 = 事故温床。如果多个子 Agent 同时修改同一份状态，谁覆盖谁全看运气。所以并行原则是**读多写少、各写各的键**；真有共享写需求，要么串行化，要么自己设计幂等。

### 4.3 LlmRoutingAgent：智能分诊台

前两种模式下所有子 Agent 都会跑。路由模式不同：**LLM 读一遍所有子 Agent 的 description，选一个最合适的去执行，然后结束**。

```java
LlmRoutingAgent router = LlmRoutingAgent.builder()
        .name("content_router")
        .model(chatModel)
        .description("按意图路由到写作/评审/翻译专家")
        .subAgents(List.of(writerAgent, reviewerAgent, translatorAgent))
        .build();

router.invoke("帮我写一篇春天的散文"); // → 选中 writerAgent，其余不执行
```

它就像医院分诊台：患者描述症状，护士判断该挂哪个科——**只挂一个科，不挂全部**。这就是"单次路由"的含义：选一次，执行一次，结束。

**路由准不准，全看 description**。这句话值得写三遍：路由模型判断的唯一依据就是每个子 Agent 的 `description`。描述写糊了，路由必糊；两个描述有重叠（"行程专家"和"旅游顾问"——它俩谁管订票？），模型就会摇摆。出现选错人的情况，先别怀疑模型，回头改描述：正面写清"只管什么"，反面写清"不管什么"。必要时还可以给路由 Agent 配 `systemPrompt` 强化选人规则（后面案例里有现成写法）。

**什么时候用它**：用户输入是**单意图**的——一个问题，一个答案，找一个专家。注意"单意图"这个前提，多步任务（先查机票再查酒店）它做不了，那是 Supervisor 的活，5.1 节会对比。

### 4.4 LoopAgent：反复打磨

前三种每个子 Agent 都只跑一次（Parallel 也是每人一次）。有些任务需要**同一个 Agent 反复执行、逐轮改进**——写完自己检查一遍再改一遍，直到满意。这就是 LoopAgent：

```java
LoopAgent loop = LoopAgent.builder()
        .name("refine_loop")
        .description("反复润色直到达标或达上限")
        .subAgent(refinerAgent)                    // 注意：只挂一个
        .loopStrategy(LoopMode.count(3))           // 也可以用 condition 谓词，以版本 API 为准
        .build();
```

两个要点：

1. 它只挂**一个** subAgent（挂多个语义不明——到底谁循环谁？）；
2. `loopStrategy` 决定循环怎么退出：`count(3)` 是最多跑 3 轮，也可以换成条件谓词（比如"结果里不再包含'待改进'字样就停"）。

**铁律：循环必须有上限。** 没有上限的 LoopAgent 是一台 Token 焚烧炉——模型卡在"永远差一点"的状态里无限自我改进，账单起飞。哪怕你觉得条件肯定能触发，也再垫一层次数上限兜底。

### 4.5 选型速记

四种模式各有各的"形态"，可以拿一个直观印象来记：

- **Sequential** — 工厂流水线：工位顺序固定，产品一站一站往下传；
- **Parallel** — 一对多派活：老板同时把任务群发给几个人，最后收上来合并；
- **LlmRouting** — 医院分诊台：看症状，挂一个科，看完走人；
- **Loop** — do-while 循环：同一个师傅反复打磨，直到达标或到次数。

按任务形态对号入座即可：流程固定前后依赖 → Sequential；互不依赖可同时做 → Parallel；按意图选一个专家 → LlmRouting；同一个 Agent 反复打磨 → Loop。

还有一点值得注意：这四者的 `subAgents` 收的都是 Agent 对象，**而 Agent 本身可以嵌套**——ParallelAgent 可以作为 SequentialAgent 的一个 subAgent。这个"嵌套"能力是 5.5 节混合模式的基础，先留个印象。

---

## 五、四种模式之外：真实系统里的协作方式

四种内置工作流解决的是"执行顺序"问题，但真实业务还有几个它们覆盖不到的典型问题：多步任务怎么调度、子 Agent 怎么组织、对话怎么移交。这一节讲这些"进阶积木"。

### 5.1 Supervisor：能多轮调度的"调度中心"

先回想 4.3 的 LlmRoutingAgent：选一个专家，执行，结束。但现实中很多任务是这样的：

> "帮我安排下周五去杭州出差：看看机票、订个酒店、再规划下两天的行程。"

这是**一个请求里的多个意图**。Routing 选不出"一个"专家，而 Sequential 又把流程写死了。真正需要的是：一个总控，**按需、多轮、动态地**调用多个专家，最后汇总。这就是 Supervisor 模式。

框架里它没有一个专门的 `SupervisorAgent` 类——它就是**一个普通 ReactAgent + 把专家 Agent 包成工具**：

```java
// 专家一：注意 name/description 会变成工具名/工具描述，inputType 声明它接收什么
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

// 总控：一个普通 ReactAgent，专家是它的 tools
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

这段代码几个值得停下来的点：

**`AgentTool.getFunctionToolCallback(...)` 是关键魔法**：它把一个 ReactAgent 包装成标准 Function Tool。对总控来说，专家和一个查天气的 HTTP 工具没有任何区别——都是"工具列表里的一项"，模型按工具名和描述决定要不要调、什么时候调、调几次。这正是第二节说的 Tool Calling 主轴的落地形态。

**专家侧要用 `inputType(String.class)`**：总控调工具时传的参数是一段自然语言子任务（比如"请翻译这句：……"），专家要声明自己接收的是 String。总控负责把大任务拆成一句句子任务，这是它 Prompt 工程的核心。

**调度机制就是普通的 Tool Calling 多轮**：模型调一次工具、拿一次结果、判断要不要继续调，直到它认为信息够了，输出最终答复。所以 Supervisor 天然支持"先查 A 再根据 A 的结果决定查不查 B"这种**动态决策**——这是四种 FlowAgent 都做不到的（它们的路径在编译期就定了）。

**和 LlmRouting 到底怎么区分？** 初学者最容易混这两个，因为它们都"选人"。三个维度掰开看就清楚了：

- **形态**：Routing 是 FlowAgent，选一个 subAgent 执行完就结束；Supervisor 是 ReactAgent，专家是它的 Tools；
- **次数**：Routing 单次路由选一人；Supervisor 可以多轮、调多个、来回调；
- **子 Agent 干完后**：Routing 里流程直接结束；Supervisor 里结果回到总控手上，总控可以继续调别的工具，也可以直接组织答案回用户。

一句话记：**Routing 是分诊台，选一次就完；Supervisor 是调度中心，把专家当工具反复使唤，最后自己出面汇报。** 分别对应"单意图"和"多步任务"两种需求。

### 5.2 Subagent：子 Agent 的组织方式

"Subagent"不是一个特殊类，就是普通 Agent 在父流程里扮演的角色。一个 Agent 建好后，想让它进哪个体系，取决于你"挂"它的方式，常见四条路：

1. **`FlowAgent 的 subAgents(...)`**：编入 Sequential / Parallel / Routing / Loop 的执行序列——它是流程里的一站；
2. **`AgentTool.getFunctionToolCallback(agent)`**：挂进 Supervisor 的工具列表——它是总控手上的一个工具；
3. **`outputKey` + 占位符**：配合 Sequential 等做流水线接力——它的产出是下游的输入；
4. **`includeContents(false)`**：配合上面任意一种，切断父上下文的干扰——它只看自己 instruction 里给的东西。

特别提醒一个容易"预期错位"的点：**同一个子 Agent，走不同的挂载路径，数据回流方式完全不同**。

- 走 Supervisor（AgentTool）路径：专家的产出以**工具返回值**的形式回到总控的消息流里，一般不需要配 `outputKey`（配了也主要是写日志用）；
- 走 Sequential / Parallel 流水线路径：产出必须写 `outputKey`，下游靠占位符取，**没有 outputKey 链条就断了**。

所以给子 Agent 设计参数前，先想清楚它要被挂到哪里，别把两条路径的预期搞混。

### 5.3 Handoffs：把对话主体换人

Supervisor 模式下有个隐含设定：用户从头到尾只面对总控，专家永远在幕后。这在"任务型"场景没问题，但在"对话型"场景里会别扭——

> 用户：我要投诉退款问题。
> 总控：请稍等，我咨询一下售后专家。（后台转述一遍问题）
> 总控：专家说可以这样这样……
> 用户：（追问一个细节）那我这单具体什么时候能到账？
> 总控：请稍等，我再问一下……

这种"传声筒"体验在多轮对话里非常糟糕。Handoffs 的做法是：当前 Agent 判断这个对话归别人管，就**把对话上下文连同"说话权"一起移交**给目标 Agent，**新 Agent 直接面对用户接着聊**：

```
用户 ⇄ 通用客服 Agent          （发现是退款纠纷，不在我的能力范围）
              │ handoff（移交对话历史 + 控制权）
              ▼
用户 ⇄ 售后专家 Agent          （直接继续和用户对话，用户感知到"换人了"，但对话没断）
```

和 Supervisor 的本质区别，一句话：**Supervisor 换的是执行者（背后干活的人），Handoffs 换的是说话人（面对用户的人）。** 或者更直白：一个像"领导帮你去问"，一个像"直接把电话转给懂的人"。

什么时候选 Handoffs：对话跨越领域边界、且后续多轮都该由新专家主导（客服转人工、技术支持、专家会话）。什么时候选 Supervisor：任务可以拆成子步骤、用户不需要和子步骤的执行者直接对话（查数据、出报告、做编排）。

### 5.4 Skills 与 Workflow：两个辅助概念

多 Agent 体系里还有两个常被提到的组织手段，顺带厘清一下定位：

**Skills（技能包）**：上一篇讲过的渐进披露机制——技能按需加载，不占常驻上下文。放到多 Agent 语境下，就是给每个专家配不同的技能包：天气专家挂天气查询技能，行程专家挂地图技能。**Skills 管的是"某个专家自己会什么"**，让单个 Agent 在保持职责单一的同时能力够用。

**Workflow（工作流）**：就是本文第四节讲的那套 FlowAgent 组合。当协作路径相对固定时，用 Sequential / Parallel 直接拼；如果还需要条件分支、审批流转这类更复杂的控制流，就下沉到 **Graph**（StateGraph + 条件边，下一篇的主角）。**Workflow 管的是"这群专家按什么路径走"**。

一句话：Skills 是纵向的（单个 Agent 的能力深度），Workflow 是横向的（Agent 之间的协作广度）。

### 5.5 混合嵌套：真实系统的标准形态

到这里积木都齐了。真实业务几乎从来不是"单选一种模式"，而是**嵌套**。先看一个典型结构：

```
Parallel(多源调研) → Analysis → LlmRouting(报告格式)
          ↑______________ Sequential 串起来 ______________↑
```

拆开读这张图，一共三层：

1. **最外层 Sequential**：整个流程"调研 → 分析 → 出报告"三步走，顺序固定；
2. **第一步内部是 Parallel**：多源调研拆成好几路同时扇出，齐头并进省时间；
3. **最后一步是 LlmRouting**：报告写完了，根据用户要的格式（正式版/摘要版/PPT 大纲）路由给对应的格式化专家。

再展开一个更完整的"竞品分析报告"系统，几乎用上全部模式：

```
Sequential(
    Parallel(                     // 第一阶段：多源调研（互不依赖 → 并行）
        网页搜索 Agent,
        财报解析 Agent,
        新闻聚合 Agent
    ),
    Analysis Agent,               // 第二阶段：汇总分析（依赖调研结果 → 串行）
    Supervisor                    // 第三阶段：多轮打磨（动态决策 → 总控+工具）
        tools: writer_agent, reviewer_agent
)
```

每个阶段的模式选择都有明确的"为什么"：

- **Parallel** 出现在调研阶段，因为三个信息源互不依赖，并行纯属白捡的提速；
- **Sequential** 保证"调研完才分析、分析完才写报告"——后一步依赖前一步产出，顺序不能乱；
- **Supervisor** 兜底写报告环节，因为"写 → 审 → 按审稿意见改"是个动态过程：改几轮、先改哪部分、要不要再审一次，只有看到中间结果才能决定，这正是 ReactAgent 多轮工具调度的主场。

#### 为什么官方推荐嵌套，而不是发明新模式？

初学者看到一个复杂需求，容易想"这需要一种新的超级模式"。别。回头看一眼你手里的积木：

| 模式 | 它解决的问题 | 实现形态 |
| --- | --- | --- |
| Sequential | 顺序依赖 | FlowAgent |
| Parallel | 互不依赖的并发 | FlowAgent |
| LlmRouting | 单次选人 | FlowAgent |
| Loop | 同一任务反复改进 | FlowAgent |
| Supervisor | 多轮动态调度 | ReactAgent + AgentTool |

现实里的复杂业务，拆到最后几乎都是这五种基本结构的组合。嵌套的好处是实打实的：**每一层都可单独测试**（Parallel 那一层可以脱离整个系统独立验收）、**积木可复用**（调研 Parallel 模块明天可以直接搬到另一个报告系统里）、**边界清晰**（出问题从日志定位到某一层就到头了）。而"发明新模式"意味着发明新的 bug、新的心智负担、新的没法测试的胶水逻辑。

所以设计口诀是：**外层 Sequential 定主流程，内层按需嵌 Parallel / Routing，需要动态多专家决策的地方换 Supervisor。**

---

## 六、五条设计原则

这一节把散落全文的原则收拢起来，并且说清楚**每条原则对应的事故场景**——知道了它在防什么，才记得住。

**1. 单一职责：一个 Agent 只干一类事。**
防的是"上下文爆炸 + 乱调工具"的复合事故。判断标准很具体：如果你描述一个 Agent 的职责时用上了"和"、"以及"，它多半该拆。机票归机票、酒店归酒店。

**2. `name` / `description` 要当成接口文档来写。**
防的是路由选错人、总控调错工具。要记住：这些描述的第一读者不是人类同事，是**拿它做决策的模型**。正面写能力、反面划边界，就像写 API 文档一样写它。

**3. 共享状态和消息传递，别混用预期。**
防的是"专家明明产出了、下游却读不到"的隐形断链。约定俗成：走 Sequential / Parallel 流水线的 Agent，产出必须写 `outputKey` 供占位符读取；走 Supervisor 的专家，产出通过工具返回值回总控。给一个 Agent 配参数前，先确定它的挂载路径，再决定配 `outputKey` 还是配 `inputType`。

**4. 禁止全能 Agent：总控只调度，专家只管细节。**
防的是系统退化回"单 Agent 大杂烩"——总控忍不住也开始自己写行程、自己查机票？那拆分就白做了。总控的 systemPrompt 要明确"你的职责是调度和汇总，不是自己干活"；专家的 description 要明确划出边界，让"越权"在选人阶段就被拦住。

**5. 失败要有兜底，别让一个专家拖垮全队。**
防的是雪崩：一个子 Agent 超时/抛异常，整条流程跟着挂。多 Agent 系统天然比单 Agent 更容易失败（环节多了），所以每个可能失败的环节都要想好降级方案：返回可读的错误信息让总控改派？跳过该环节用默认值继续？还是干脆终止但给用户一句人话解释？别让用户的界面上出现一个裸异常。

---

## 七、动手验证：一个完整的差旅多智能体 Demo

知识点讲完了，下面这份完整的 Controller 把所有模式串在一个真实场景里（差旅助手），每个接口对应一种模式。

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

        // ========== 模式一：Sequential + outputKey 接力（草稿 → 润色） ==========
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
                .includeContents(false)   // 润色只看草稿，父上下文是噪音，关掉
                .build();

        this.sequentialAgent = SequentialAgent.builder()
                .name("itinerary_sequential")
                .description("先拟草稿再润色")
                .subAgents(List.of(drafter, polisher))
                .build();

        // ========== 模式二：Parallel 扇出后合并（机票/酒店/天气互不依赖） ==========
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

        // ========== 模式三：LlmRouting 单次分诊（description 划边界是关键） ==========
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
        // 天气专家特意关掉父上下文：防止被用户"顺带提一句机票"带偏
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

        // ========== 模式四：Loop 有限次打磨 ==========
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
                .loopStrategy(LoopMode.count(3))   // 必须设上限，防 Token 焚烧
                .build();

        // ========== 模式五：Supervisor = ReactAgent + AgentTool ==========
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

        // ========== 模式六：混合嵌套 Sequential( Parallel → Summarizer ) ==========
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

        // 注意这里直接复用了上面的 parallelAgent 作为外层 Sequential 的一个子步骤——嵌套就是这么自然
        this.hybridAgent = SequentialAgent.builder()
                .name("travel_hybrid")
                .description("先并行调研，再串行汇总")
                .subAgents(List.of(parallelAgent, hybridSummarizer))
                .build();
    }

    // 流水线用：有 outputKey、instruction 里带 {input} 占位符
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

    // Supervisor 用：没有 outputKey，靠 systemPrompt + inputType(String) 接收子任务、工具返回值回传
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

    @Operation(summary = "Sequential：草稿→润色（outputKey 接力）")
    @PostMapping("/api/s51/sequential")
    public Map<String, Object> sequential(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日游，偏西湖与美食");
        return toView(sequentialAgent.invoke(q),
                List.of("itinerary_draft", "itinerary_final"));
    }

    @Operation(summary = "Parallel：机票+酒店+天气并行合并")
    @PostMapping("/api/s52/parallel")
    public Map<String, Object> parallel(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "下周五去杭州两天，预算中等");
        return toView(parallelAgent.invoke(q),
                List.of("research_bundle", "flight_plan", "hotel_plan", "weather_plan"));
    }

    @Operation(summary = "Routing：单意图分诊")
    @PostMapping("/api/s53/route")
    public Map<String, Object> route(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "下周五上海飞杭州机票怎么选？");
        return toView(routingAgent.invoke(q),
                List.of("flight_plan", "hotel_plan", "itinerary_plan", "weather_plan"));
    }

    @Operation(summary = "Loop：行程最多打磨 3 次")
    @PostMapping("/api/s54/loop")
    public Map<String, Object> loop(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日游行程，请尽量具体到半天");
        return toView(loopAgent.invoke(q), List.of("refined_itinerary"));
    }

    @Operation(summary = "Supervisor：专家当 Tool 多轮调度")
    @PostMapping("/api/s55/supervise")
    public Map<String, Object> supervise(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日游，帮我安排机票思路、住宿和行程");
        return toView(supervisorAgent.invoke(q), List.of());
    }

    @Operation(summary = "混合：Parallel 调研后再 Sequential 汇总")
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
        // Supervisor 没有配 outputKey，最终回复藏在 messages 列表的最后一条里
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



### 自测清单：怎么确认每种模式真的跑对了

以下请求都用 `Content-Type: application/json` 发 POST。每种模式除了"看返回"，都配了"怎么判断"——跑通不等于跑对，判断标准才对应知识点。

**① Sequential：验证 outputKey 接力**

```json
{"message": "杭州两日游，偏西湖与美食"}
```

应返回 `itinerary_draft`（初稿）和 `itinerary_final`（润色稿）两个键。判断三点：

- 两个键都在 → 上游 `outputKey("itinerary_draft")` 写出成功，下游 `{itinerary_draft}` 替换成功；
- 如果 `itinerary_final` 的正文里**原样出现字符串 `{itinerary_draft}`** → 占位符缺值（3.7 节的坑，查上游 key 拼写）；
- `polisher` 配了 `includeContents(false)`，润色稿应明显基于草稿改写，而不是把父对话内容又复述一遍。

**② Parallel：验证扇出合并**

```json
{"message": "下周五去杭州两天，预算中等"}
```

应返回 `flight_plan`、`hotel_plan`、`weather_plan` 三键，以及合并后的 `research_bundle`。判断：三个 `*_plan` 内容各自独立且专业（没串台），日志里三个子 Agent 的开始时间**接近同时**（真并行而非伪装的串行）。

**③ Routing：验证"选一个"和 description 边界**

先问机票：

```json
{"message": "下周五上海飞杭州机票怎么选？"}
```

应主要返回 `flight_plan`，其余三个 key 不该有完整内容——Routing 只执行被选中的那一个。再问天气，顺带验证 `includeContents(false)` 的效果：

```json
{"message": "杭州这两天穿什么？会下雨吗？"}
```

应主要返回 `weather_plan`。如果总选错人，回到 4.3 节的老规矩：改子 Agent 的 `description` 边界措辞、强化路由 `systemPrompt`，而不是换模型。

**④ Loop：验证有上限的迭代**

```json
{"message": "杭州两日游行程，请尽量具体到半天"}
```

返回 `refined_itinerary`。判断看日志：`itinerary_refiner` 的执行次数**必须 ≤ 3**。如果日志里它在无限刷，说明 `LoopMode.count(3)` 没生效——立刻停应用，这是 Token 在燃烧。

**⑤ Supervisor：验证专家当工具（看 answer，不看 \*_plan）**

```json
{"message": "杭州两日游，帮我安排机票思路、住宿和行程"}
```

返回里关键的键是 `answer`（总控汇总后的完整说明）。两个判断点：

- **通常没有** `flight_plan` 这类单独的 key——这正是 Supervisor 和 Routing 的结果形态差异：专家产出经工具返回值流回总控，不写状态键；
- 日志里 `flight_agent` / `hotel_agent` / `itinerary_agent` 作为工具被调用，这种综合性提问下通常 ≥ 2 次。

再发一个单领域请求验证"按需调用"：

```json
{"message": "只帮我看看西湖附近住哪合适"}
```

日志里应该主要是 `hotel_agent` 被调，另外两个工具安静待着。

**⑥ 混合嵌套：验证外层串行、内层并行**

```json
{"message": "杭州两日：并行调研机票酒店天气并出简报"}
```

应返回 `research_bundle`（并行阶段产出）和 `final_brief`（汇总简报）。判断：

- 两个键都在 → 外层 Sequential 和内层 Parallel 的嵌套结构生效；
- `final_brief` 的内容应明显基于 `research_bundle` 改写，且**不应**再残留未替换的 `{research_bundle}` 字样。

六个接口全过，基本模式就算真正上手了。

---

## 下一篇预告

本文的模式都有一个共同限制：**执行路径要么写死（Sequential/Parallel/Loop），要么交给模型临场发挥（Routing/Supervisor）**。当业务需要"固定审批流、条件分支、并行后按条件汇合、人工介入再恢复"这类精确控制时，就该下沉到 **Graph** 了：StateGraph、条件边、中断恢复与 Mermaid 导出——那是下一篇的主角。

---

## 官方参考

- 多智能体文档：https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent
- 官方示例仓库：`examples/multiagent-patterns/`（pipeline / routing / supervisor / handoffs / …）
- Supervisor 示例：`examples/multiagent-patterns/supervisor`
