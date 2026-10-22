# Graph 核心：StateGraph、条件边并联与审批流实战

> 本讲是《Spring AI Alibaba 学习专栏》的 Graph 核心篇。上一篇我们用 FlowAgent 的 Sequential / Parallel / Routing，以及 Supervisor（ReactAgent + AgentTool）组了「差旅 Agent」。本篇掀开引擎盖：**自己画节点与边**——固定流程、条件分支、并行汇合、人工卡点、导出 Mermaid。
>
> 版本基准（跟仓库约定对齐）：JDK 17+ / Spring Boot 3.5.7 / Spring AI 1.1.2 / Spring AI Alibaba 1.1.2.2。
>
> 所有 API、概念与代码均以官网为准：https://java2ai.com

依赖仍是 `agent-framework`（内含 graph-core 能力）+ DashScope，不需要新增 starter。

官方参考：

- Graph 工作流编排指南（入门）：https://java2ai.com/docs/frameworks/graph-core/quick-start
- Graph 是什么：https://java2ai.com/docs/1.0.0.2/tutorials/graph/whats-spring-ai-alibaba-graph/
- 多节点并行执行：https://java2ai.com/docs/1.0.0.2/tutorials/graph/parallel-node/
- 人类反馈（Human-in-the-loop）：https://java2ai.com/docs/1.0.0.2/tutorials/graph/human-in-the-loop/

---

## 一、什么时候该自己画图，而不是用 ReactAgent / FlowAgent？

框架给了三档抽象，选错档位的代价很实在：用轻了做不出效果，用重了白白增加维护成本。先把三者的"决策权归属"看清楚。

### 1.1 三种抽象，三种放权程度

**ReactAgent：全权外包的「野生助理」。**

你只给目标，不给路线。你说"帮我调研下竞品的最新市场策略"，剩下的全由模型自己拍板：先搜官网还是先查财报？信息不够要不要再查产业链？什么时候算查完可以交付？你只需要给它可用的工具（搜索、文件读取等），不用管它走几步、走哪条路，决策权完全交给模型。

- **适合**：路径完全无法预判的开放式探索——调研、解题、故障排查、多工具自由组合的复杂问答。
- **不适合**：要求步骤规范、结果可控的业务流程，容易"乱跑步骤"。

**FlowAgent：固定工位的「流水线工人」。**

路线提前写死，一步都不能改。你规定死：第一步提取用户信息，第二步生成合同，第三步发送邮件。模型只负责"把每一步的内容填好"，但绝对不能自己加一步"审核合同"，也不能跳步。它的编排能力很基础：顺序执行、简单并行、成功 / 失败二选一的路由。

- **适合**：步骤固定、分支极少的标准化任务——固定格式文档生成、简单数据处理流水线。
- **不适合**：有循环、多分支、人工介入、状态需要来回传递的复杂流程。

**Graph（StateGraph）：你画好规则的「导航地图」。**

它介于两者之间：**所有能走的路、所有能停的站，全是你提前规定好的；但具体走哪条路，由运行时的状态动态决定**。

相当于你提前画好一张完整的路线图：有哪些站点（节点：校验、审核、通知、驳回）、站点之间能不能连通、满足什么条件走哪条（条件边：校验通过 → 走人工审核；校验失败 → 走驳回）。运行到每个节点会算出一个结果，然后照着你定好的规则自动跳转。模型可以在节点里干活，但**不能自己凭空加一条没定义过的路线**。

- **适合**：流程复杂、变数多，但又必须可控、可追溯的业务场景。
- **不适合**：简单几步就能搞定、路径完全开放的轻量任务。

一句话概括三者的区别：**ReactAgent 把路线交给模型，FlowAgent 把路线写死，Graph 把路线画出来、让状态来选。**

### 1.2 满足任意一条，就该上 Graph

判断标准不是"这个流程复不复杂"，而是更具体的一条：**有没有哪件事是 FlowAgent 表达不了的**。下面五条，命中任意一条就够了。

**需要强流程：步骤多、有明确先后和分支。**
比如完整的用户开户流程：提交资料 → 身份核验 → 风险评级 → 开户通知 → 产品推荐。每一步有严格先后，还藏着多个分支（核验失败怎么办？风险评级过高怎么办？）。这种多步骤、多分支的强流程用 Flow 写会非常别扭，Graph 天然适配。

**需要强状态：多节点读写同一份全局共享状态。**
比如客服工单系统：从用户进线、自动答疑、转人工、工单创建、处理、回访，全程共享同一份"工单全局状态"（用户信息、问题描述、处理记录、当前进度）。每个节点都能读能改，后面的节点自动拿到最新数据。这是 Graph 全局 State 的强项，Flow 和 Agent 做优雅的全局状态共享会很麻烦。

**需要条件分支 / 并行汇合 / 循环。**
这是 Flow 的能力边界，却是 Graph 的基本操作：

- **多条件分支**：不只是成功 / 失败二选一，而是多维度判断（用户等级 ≥3 走 A 通道，否则走 B 通道；金额 ≥1 万走复审，否则直接通过）；
- **并行汇合**：同时跑"资质审核"和"征信查询"，两个都跑完才能进入下一步；
- **循环回流**：比如"补料 → 校验 → 不通过再补料"，可以循环 N 次直到通过。Flow 很难优雅实现，Graph 天然支持循环边。

**需要人工卡点（审批 / 确认 / 补参）。**
这是 Graph 的核心杀招。它可以在某个节点前设置"断点"，流程跑到这就自动暂停，等人工审批、确认或补完信息，再接着往下跑，并且支持断点续跑。比如合同生成后必须等领导审批通过才能发出去。这种"人机协作的卡点"，Agent 和 Flow 都很难稳定实现，Graph 的 `interruptBefore + Checkpoint` 机制就是专门解决它的。

**需要可视化：导出流程图给评审看。**
Graph 可以直接导出 Mermaid、PlantUML 格式的流程图，整个流程长什么样、有多少分支、哪里有审批，一眼就能看明白。给产品、业务、领导评审时直接甩图，不用靠嘴描述流程——这在跨角色沟通里的价值经常被低估。

### 1.3 什么时候别上 Graph

工具不是越复杂越好，以下场景别硬上：

1. **任务极简单**：一两步就完事，比如"搜个数据整理成表格"，ReactAgent 直接搞定；
2. **流程极度固定**：纯顺序 3 步以内，没有分支没有循环，FlowAgent 足够轻量；
3. **完全开放探索**：科研调研、开放式创作，你自己都不知道有哪些步骤，交给 Agent 就好。

**快速选型三步法：**

1. 路径完全没法预判？→ 用 ReactAgent
2. 纯固定顺序 / 简单二选一分支？→ 用 FlowAgent
3. 有复杂分支 / 循环 / 人工卡点 / 全局状态 / 可视化需求？→ 上 Graph

---

## 二、核心概念：一张图的四个零件

**StateGraph 本质是一套「声明式的工作流图纸」**。你只负责定义"有哪些工位、工位之间怎么连通、共享台账怎么书写"，剩下的流转调度、状态同步、断点续跑，全由框架底层搞定。

官方说它是「Agent 框架的底层运行时基座」，翻译成人话：**你之前用的 FlowAgent（顺序、并行、路由），都是官方提前画好的现成 Graph 模板**；而手写 StateGraph，就是掀开模板自己亲手画图，灵活度拉满。

下面不逐个背定义，而是拿第五节那个"文档审批流"当例子，看这张图是怎么一点点长出来的。要把它变成能跑的代码，得回答四个问题——**每个问题对应 Graph 的一个零件**：

| 要回答的问题 | 对应的零件 |
| --- | --- |
| 有哪些工位，每个工位干什么活？ | **Node** |
| 工位之间怎么走，什么条件下改道？ | **Edge** |
| 工位之间传什么数据，怎么传？ | **OverAllState + KeyStrategy** |
| 跑到一半要暂停、要存档，谁管？ | **CompileConfig** |

`StateGraph` 就是承载这四件事的"图纸"。记住一个关键性质：**它只负责声明，不负责执行**——加节点、加边的时候没有任何业务代码在跑，调用 `compile()` 之后才变成真正能启动的生产线（`CompiledGraph`）。这个"先声明后编译"的设计不是故弄玄虚，它带来两个很实在的好处：图纸阶段就能**导出流程图**给业务评审，也能在编译期**校验结构**（边指向了不存在的节点会当场报错，而不是等到线上跑一半才发现）。

顺带说一句：官方说 StateGraph 是「Agent 框架的底层运行时基座」，翻译成人话就是**你之前用的 FlowAgent（顺序、并行、路由），都是官方提前画好的现成 Graph 模板**；手写 StateGraph，等于掀开模板自己画图，灵活度拉满。

### 2.1 Node：只干一件事的工位

一个节点就是一步具体操作。它的工作方式是固定的三段式：**读状态 → 干活 → 返回一个 Map**。

关键在于最后那步：节点**不直接修改全局状态**，而是返回一个"我要改哪些 key"的 Map，由框架负责合并。这么做的好处是节点之间没有隐式耦合——节点不知道谁在它前面、谁在它后面，甚至不知道整张图长什么样，它只认状态。这也就是为什么同一个节点能搬到另一张图里复用。

**更重要的一个隐含约定：节点不决定下一步去哪。** 很多新手会把 `if (通过) { 调下一个节点 }` 写进节点里，这是错的——路由是边的职责，写进节点里就等于把流程图拆散埋进了业务代码，导出的图会失真，改流程要动业务代码。这个分工后面 3.2 还会展开。

节点有两种形态：普通 `NodeAction` 同步执行、干完一次性返回；`AsyncNodeAction`（用 `node_async` 包装）支持流式输出，大模型可以边生成边往下传，不用等全部生成完。写 LLM 节点时基本都用后者。

### 2.2 Edge：决定下一步去哪的岔路口

边就是节点之间的连通规则，分两种：

- **普通边（`addEdge`）**：固定路线，A 干完一定去 B，没有分支。用来串主流程；
- **条件边（`addConditionalEdges`）**：根据状态里的字段动态选路。比如质检合格走包装、不合格走返工，而且分支数量不限——三档、五档都行，不像 FlowAgent 只有成功/失败二选一。

还有两个虚拟节点 `START` / `END`：它们不干活，只标记流水线的入口和出口。所有合法流程必须从 START 出发、到 END 结束——这不是形式主义，框架靠它判断"图有没有画完整"，漏了 END 的边编译期就会报错。

### 2.3 OverAllState：为什么是"按 key 合并"而不是整体覆盖

所有节点共享的那份数据容器就是 OverAllState。节点之间不用单独传参，数据全从这份状态里取——这解决了"第十个节点想要第一个节点的某个字段"这种跨节点取数的问题。

但这里有个**必须想明白的设计**：节点返回的是一个 Map，框架拿到之后**不是**把整个状态替换掉，而是**逐个 key、按提前声明的策略合并**。

为什么要这么麻烦？因为并行。设想两个并行节点同时返回修改：敏感词检查返回 `{compliance_note: "未命中敏感词"}`，必填项检查返回 `{required_ok: false}`。如果框架是"整体覆盖"，先跑完的那个节点写的结果会被后跑完的**整表冲掉**——而谁先跑完是不确定的，于是这个 bug 时灵时不灵，是最难查的那一类。按 key 合并之后，两个分支各改各的字段，谁也不影响谁。

这也解释了为什么并行分支**必须各写各的 key**：框架能保证 key 级别互不干扰，但保证不了两个节点往同一个 key 上写的语义冲突——那仍然会互相覆盖，只是变成可预测的"后写完胜"。

### 2.4 规则一：KeyStrategyFactory（提前声明每个 key 怎么写）

既然合并是按 key 进行的，那就得提前说清每个 key 的合并方式，这就是 `KeyStrategyFactory`——**必填项**，不是可选配置。

常用的就两种：

- **`ReplaceStrategy`（覆盖）**：新值替换旧值，永远只留最新一份。适合状态标记、分类结果、查询条件这类"只关心当前值"的字段。案例里的 `final_status`、`approved`、`doc_type` 都是它；
- **`AppendStrategy`（追加）**：新值追加到列表末尾，历史全保留。适合对话消息、操作日志、审批轨迹这类需要留痕的字段。案例里的 `review_trail` 就是它——每经过一个节点追加一条，最后就是一份完整的审批轨迹。

判断标准就一句：**这个字段你关心的是"现在是什么"，还是"发生过什么"**。前者 Replace，后者 Append。用反了会出两类事故：日志类用了 Replace，每次只剩最后一条，出事查不到过程；状态标记用了 Append，值越堆越多，判断时拿到一串而不是一个。

还有一个坑：**没注册的 key 行为不确定**（不同版本可能默认 Replace，也可能直接报错）。所以自己加的 key，老老实实注册。

### 2.5 规则二：CompileConfig（把管控能力装在图纸上）

`compile()` 的时候可以传一份 `CompileConfig`，给这条生产线挂管控能力。为什么存档和中断要放在**编译期**而不是运行时传参？因为它们是"这条生产线的固有属性"，而不是"这一次运行的可选参数"——图纸定稿时就把存档系统和卡点位置装好，之后每次运行只需要带上 `threadId` 就够了，业务代码完全不用感知。

它主要管两件事：Checkpoint Saver（自动存档系统）、`interruptBefore`（人工卡点），下面 2.8 单独展开。

### 2.6 最小可运行图：四步走

把上面四个零件串起来，一个能跑的图就是这四步。每步都在回答一个具体的问题。

**第一步，定台账书写规则（这个 key 怎么写？）：**

```java
KeyStrategyFactory keyStrategyFactory = () -> {
    Map<String, KeyStrategy> m = new HashMap<>();
    m.put("query", new ReplaceStrategy());
    m.put("answer", new ReplaceStrategy());
    return m;
};
```

→ 告诉共享台账：`query` 和 `answer` 这两个条目，新值来了直接覆盖旧值。

**第二步，在画板上画流程图（有哪些工位、怎么连？）：**

```java
StateGraph graph = new StateGraph(keyStrategyFactory)
    .addNode("answer_node", node_async(state -> {
        String q = state.value("query", "");
        return Map.of("answer", "echo: " + q);
    }))
    .addEdge(StateGraph.START, "answer_node")
    .addEdge("answer_node", StateGraph.END);
```

→ 一共做了三件事：新建画板并绑定台账规则；添加一个叫 `answer_node` 的工位（从台账拿 `query`，前面拼上 `echo:`，结果写进 `answer`）；画两条传送带（起点 → answer_node → 终点）。

**第三步，编译成可运行的生产线（要不要存档？）：**

```java
CompiledGraph app = graph.compile(
    CompileConfig.builder()
        .saverConfig(SaverConfig.builder()
            .register(SaverConstant.MEMORY, new MemorySaver())
            .build())
        .build());
```

→ 把图纸编译成真正能跑的生产线，同时给它装一个「内存存档器」：运行时每一步都存在内存里，程序重启就消失，适合本地测试。

**第四步，启动生产线（这次跑的是哪个会话？）：**

```java
Map<String, Object> input = Map.of("query", "hello");
Optional<OverAllState> result = app.invoke(input);
```

→ 把初始数据 `query=hello` 塞进台账，从起点启动流水线，跑完拿到最终台账，里面就有 `answer=echo: hello`。

前三步都是"一次性"的，通常放在构造器里做一次；第四步才是每次请求都要跑的。这个分工在第五节的代码里看得很清楚：`StateGraph` 和 `CompiledGraph` 是成员变量，接口方法里只做 `invoke`。

### 2.7 OverAllState 的三条设计原则

官方的设计原则里，新手最容易踩坑的三条，翻译成大白话：

**原则一：状态里存「原始数据」，别存拼好的大段文本。**
存「用户问题、用户等级、历史订单号」这些原始字段，别存「请根据以下用户信息生成回复：用户等级 3 级，历史订单 xxx...」这种拼好的 Prompt。好处是不同节点可以按自己的需求格式化提示词；改提示词不用动状态结构；调试时看原始数据一目了然，不会被大段文本淹没。

**原则二：只存「必须跨节点共享的」，能派生的就现算。**
存「单价、数量」就够了，别存「总价」——哪个节点要用，哪个节点自己算。好处是避免数据不一致（改了单价忘了改总价），状态里只留最核心的原始事实。

**原则三：调试先查状态，再查节点逻辑。**
流程出问题，第一步先打印 OverAllState 里每个 key 的值，看数据对不对；数据没问题，再去查对应节点的代码逻辑。这是排错的黄金顺序——**绝大多数流程问题，本质都是状态里的数据不符合预期**。

### 2.8 存档与卡点：Graph 凭什么能做审批流

`CompileConfig` 最重要的两个能力，恰好回答了两个业务上绕不开的问题。

**第一个问题：流程跑一半服务重启了怎么办？** → Checkpoint Saver。

每执行完一个节点，框架就自动存一个检查点，把当前完整的 OverAllState 持久化下来。测试环境用 `MemorySaver`（内存，重启就没），生产环境换 Redis / 数据库 Saver，服务重启、扩容都不丢状态。

一个容易被忽略的细节：官方默认**异步持久化**，存档在后台写，流水线不用停下来等。所以不必为了性能刻意减少存档——这个设计就是鼓励你"每步都存"。

**第二个问题：某个环节必须等人签字才能往下走怎么办？** → `interruptBefore` + `threadId`。

这是 Graph 区别于普通流程框架的核心能力，也是它能做审批流的根本原因：

- `interruptBefore("节点名")`：编译时声明"跑到这里**之前**自动暂停"，等人来审批、确认或补参数；
- `RunnableConfig.threadId`：给每次会话分配唯一 ID，这个会话的所有状态和检查点都挂在它下面。

两者合起来的效果是：流程跑到审批节点自动停，你可以隔一天、隔一周，拿着同一个 threadId 再触发继续执行，精准从暂停的地方接着跑，前面的步骤一步都不用重来。

打个比方就是行政审批：材料递交到领导案头自动暂停、档案同时归档；领导什么时候有空什么时候签，签完自动往下走，前面那些核验环节全部不用重做。

**一句话串起所有概念**：用 StateGraph 画板定义好工位（Node）和流转规则（Edge），约定好共享台账（OverAllState）的书写规则（KeyStrategy），再通过 CompileConfig 配上存档和人工卡点，编译之后就是一条完全可控、支持人机协作、可断点续跑的自定义工作流生产线。

---

## 三、控制流：Graph 真正比 FlowAgent 强的地方

上一篇用的 FlowAgent 只有「顺序、简单并行、二选一路由」三板斧。它撞天花板撞在哪里？具体是三件事做不了：**路由只有成功/失败两个分支**（要按等级分三档就抓瞎）、**并行跑完没法优雅汇合聚合**（各分支结果怎么合并、谁等谁，没有现成机制）、**没有循环和人工卡点**（"不通过就退回重填"写不出来，"等人签字"也无从下手）。

StateGraph 把这三层控制流全部开放出来，于是这些都能自己拼。下面按"简单 → 复杂"的顺序逐个看，正好也是五个能力台阶。

### 3.1 顺序：最基础的直线流水线

- **类比**：一条传送带串起所有工位，前一道工序干完自动送到下一道，没有任何岔路。下料 → 冲压 → 喷漆 → 质检 → 出厂。
- **代码**：用 `addEdge(起点, 终点)` 逐段连接，从 START 出发，最终汇入 END。

```java
.addEdge(START, "classify")
.addEdge("classify", "draft")
.addEdge("draft", END);
```

- **选型说明**：纯顺序的简单流程用 FlowAgent 完全足够，没必要手写 Graph。Graph 的价值从下面这些控制流才开始真正体现。

### 3.2 条件路由：智能分岔路口

这是流程从固定死走向动态可控的第一步，也是 Graph 相比 FlowAgent 路由能力的核心升级。

- **类比**：生产线上的质检分岔口——产品检测后，合格走包装线，不合格走返工线；更复杂的场景可按等级分流：A 级走高端包装线，B 级走普通包装线，C 级直接报废。
- **核心逻辑**：
  1. 上游节点执行完毕，把判断依据（分类结果、审批状态等）写入共享状态；
  2. 条件边读取状态中的字段，返回一个路由 key；
  3. 框架根据提前定义的「key → 目标节点」映射表，自动跳转到对应节点。

**三要素：起点节点 + 路由函数 + 路由映射表**

```java
.addConditionalEdges(
    "classify", // 起点：从 classify 节点执行完后分流
    edge_async(state -> {
        // 读取 state 中的分类结果，返回路由 key
        String intent = state.value("intent", "other");
        return "billing".equals(intent) ? "billing" : "general";
    }),
    // 路由 key 到目标节点的映射关系
    Map.of(
        "billing", "billing_handler",
        "general", "general_handler"
    )
)
```

**官方推荐模式：路由决策显式化、可追溯**

不要把所有判断逻辑都塞在边的函数里。官方更推崇的写法是：**节点自行计算路由结果，写入 state（比如 `next_node` 字段）；条件边只负责读取该字段做转发。**

分类节点执行完直接返回：

```java
return Map.of(
    "classification", intent,
    "next_node", "billing_handler" // 显式声明下一步去向
);
```

条件边只做最简单的读取转发：

```java
edge_async(state -> state.value("next_node", "default_handler"))
```

这么写的好处很实在：**路由决策完全可追溯、可审计**。调试时不用翻边的逻辑，直接看 state 里的 `next_node` 就知道当时为什么走了这条路径；而且节点与边职责分离，改路由规则不用动节点业务代码。

对比 FlowAgent：Flow 的路由通常只有「成功 / 失败」两个分支，而 Graph 的条件路由支持**任意数量分支、任意复杂度的判断条件**，没有限制。

### 3.3 并行边与汇合：多工位同时作业，汇总后再推进

这是 FlowAgent 的能力边界之一：Flow 的并行能力很基础，复杂的汇合、聚合逻辑很难优雅实现，而 Graph 原生支持完整的扇出 / 扇入模式。

- **类比**：制作一份项目汇报，同时安排两人分工——A 查业务数据做表格，B 翻译海外资料，两人都完成后再交给主管汇总成最终报告。
- **触发前提（官方原话）**：**多个并行节点之间互不依赖对方的输出结果**。如果 B 节点需要 A 节点的结果才能启动，就不能并行，必须顺序执行。

**代码与执行语义**：

```java
// 从 START 同时扇出到两个独立节点
.addEdge(START, "expander")
.addEdge(START, "translate")
// 两个节点都完成后，汇入 merge 节点
.addEdge("expander", "merge")
.addEdge("translate", "merge")
.addEdge("merge", END);
```

执行逻辑三步：

1. **扇出（Fan-out）**：流程到达 START 后，同时启动 `expander` 和 `translate` 两个节点并行执行；
2. **扇入（Fan-in）**：两个节点全部执行完毕、都把结果写入 state 后，才会进入 `merge` 节点；
3. **聚合处理**：`merge` 节点读取两个分支分别写入的 key（如 `expander_content`、`translate_content`），合并成最终结果（如 `merge_result`）。

**汇合策略与三个坑点**：

1. **汇合策略**：默认 `allOf`——等待所有并行分支全部完成再进入汇合节点（绝大多数业务场景用这个）；也支持 `anyOf`——任意一个分支完成就向下执行（适合多路查询取最快结果的场景）。
2. **State 合并坑点（最重要）**：并行节点最好**写入不同的 state key**。比如都写 `result` 字段，在 `ReplaceStrategy` 策略下，后完成的节点会覆盖先完成的结果，导致数据丢失——而且这种丢失是**随机**的，取决于谁跑得快，排查起来非常痛苦。约定每个分支写专属 key，汇合节点统一读取处理，最稳妥。
3. **官方实现细节**：官方示例中的并行节点内部支持 LLM 流式输出，最终会将流式结果聚合成完整值再写入 state，不影响并行汇合的整体逻辑。

### 3.4 嵌套子图：标准工序模组化复用

当流程规模变大、复杂度升高时，就需要模块化拆分——把一套固定的小流程封装成「子图」，嵌入大流程中当作一个普通节点使用。

- **类比**：工厂的「标准审批模组」——无论生产计划、采购申请还是报销单，审批流程都是「提交 → 初审 → 终审 → 驳回 / 通过」。把这套流程做成可复用的标准模组，哪个大流程需要就直接嵌入，无需每次重复绘制。
- **本质**：一整张完整的 Graph 可以被当作父图里的一个普通节点调用。**你之前使用的 FlowAgent，本质上就是官方提前封装好、可直接嵌套的子图**。

三种子图用法：

| 用法类型 | 说明 | 适用场景 |
| --- | --- | --- |
| 子图作为 NodeAction | 在普通节点代码中，手动调用一张已编译的子图 | 灵活控制：节点内先做判断，满足条件再调用子图 |
| 子图作为 StateGraph | 传入未编译的子图定义，运行时自动编译执行 | 动态流程：子图结构可能根据配置动态生成 |
| 子图作为 CompiledGraph | 传入提前编译好的子图，直接作为节点嵌套 | 性能优先：子图结构固定，提前编译，运行时直接使用 |

注意事项：

- **状态边界**：子图可以拥有独立的 state，也可以与父图共享状态，取决于具体配置；
- **Saver 与 threadId**：如果需要父子图断点续跑联动，需确保两者共用同一个 Saver 和同一个 `threadId`，否则状态相互隔离，续跑会出现异常；
- **核心价值**：实现流程复用、大拆小，降低维护成本。复杂业务系统通常会拆成「主流程 + 多个通用子流程」来管理。

### 3.5 循环与终止：返工重做，直到达标

这是 FlowAgent 完全无法优雅实现的能力，同时也是 Agent 模式的底层实现原理。

- **类比**：产品质检不合格就送回上一道工序返工，返工完成后再次质检；直到质检合格，或者达到最大返工次数直接报废。
- **实现方式**：**用条件边指回上游节点，形成闭环**。满足循环条件就持续绕圈，满足退出条件就跳出循环继续向下。

**经典案例：ReAct Agent 的底层循环**

你熟悉的 ReactAgent，底层本质就是一张自带循环的 Graph：

1. Agent 节点（思考）：判断当前问题是否需要调用工具；
2. 需要工具 → 路由到 Tool 节点执行；
3. Tool 执行完毕 → 绕回 Agent 节点重新判断；
4. 无需工具 → 路由到 END，流程结束。

**绝对红线：必须设置明确的退出条件**

循环能力强大但风险也高，必须设置**至少一个退出条件**，否则会形成死循环，造成 Token 无意义消耗：

- 次数上限：最多重试 5 次，超过则强制退出；
- 结果达标：答案置信分 ≥80 分则结束；
- 标记位：模型返回无工具调用、状态中 `finished=true`。

最佳实践：**退出条件统一写在条件边的路由逻辑中**，不要靠节点自行终止——路由统一管控更安全可控，也方便一眼看清"这个循环什么时候会停"。

### 3.6 中断、快照、恢复：人工卡点（Human-in-the-loop）

这是 StateGraph 最核心的业务价值之一，也是企业级流程的刚需：**执行到中途自动暂停，等待人工审批 / 确认 / 补参，完成后从断点继续执行，前面的步骤完全无需重跑**。

**标准人机交互全流程**

**第一步：编译时配置中断点 + 持久化**

```java
CompileConfig config = CompileConfig.builder()
    .saverConfig(...) // 必须配置，否则快照无法持久化
    .interruptBefore("human_review") // 执行到 human_review 节点前自动暂停
    .build();
```

关键特性：中断是**编译配置层面的行为**，`human_review` 节点本身无需做任何特殊改造，就是个普通节点——节点代码和"要不要卡人"完全解耦，想加卡点不用改业务代码。

**第二步：运行到中断点，自动暂停并存档**

调用 `invoke` 或 `stream` 启动流程，执行到 `human_review` 节点之前，图会自动暂停，同时把当前完整的 OverAllState 作为快照（Checkpoint）存入 Saver。必须配合 `threadId` 使用：同一会话用同一个 `threadId`，后续才能精准定位到对应快照。

**第三步：读取当前快照，写入人工结论**

```java
StateSnapshot snapshot = compiledGraph.getState(runnableConfig);
OverAllState state = snapshot.state();

// 注意：state.data() 返回的是不可变视图，不能 data().put(...)
// 必须用 updateState 按 KeyStrategy 合并写入
state.updateState(Map.of("approved", true));
```

**第四步：带 resume 标记，从断点继续执行**

```java
RunnableConfig resumeConfig = runnableConfig.withResume();
Optional<OverAllState> result = compiledGraph.invoke(state, resumeConfig);
// 流式场景也可用：compiledGraph.streamFromInitialNode(state, resumeConfig);
```

`withResume()` 挂在 **RunnableConfig** 上（写入 resume 元数据），框架据此走「从 Checkpoint 续跑」而不是从头开跑；流程从暂停位置继续，不会重跑前面的节点。

**后续路由规则**

人工审批完成后的走向**依然通过条件边控制**，和普通路由逻辑完全一致。比如让人工节点读取 state 中的 `approved` 字段，写入 `next_node`：`approved=true` → `approve`；`approved=false` → `reject`。

**六个关键点（踩坑清单）**

1. **必须配置 Saver**：没有持久化 Saver，快照仅存在内存中，程序重启后丢失，无法续跑；
2. **threadId 必须一致**：恢复时的 `threadId` 必须和启动时完全相同，换了就找不到快照；
3. **中断是「节点前暂停」**：`interruptBefore("A")` 是在执行 A 节点**之前**暂停，而非执行完 A 之后；
4. **写状态用 `updateState`，不要用 `data().put`**：`data()` 返回不可变视图，直接 put 会抛 `UnsupportedOperationException`；
5. **续跑要带 `runnableConfig.withResume()`**：否则框架可能按新开跑处理，而不是从 Checkpoint 恢复；
6. **与 ReactAgent 的 HITL 同源**：底层都是 Checkpoint + threadId，只是触发点不同——ReactAgent 挂在 `afterModel` 拦截工具调用，Graph 挂在节点前。

把这六种控制流自由组合，你就能绘制出任意复杂度的业务流程。Graph 的核心优势不在于「能做简单流程」，而在于**面对复杂流程时依然能保持结构清晰、状态可控、可追溯、可调试**，同时原生支持人机协作。

---

## 四、预置节点、可视化与错误处理

### 4.1 节点为什么分类：为了决定它该怎么容错

官方 QuickStart 把节点分成四类。刚看时容易觉得这只是"起名字"，其实分类的真正用途只有一个：**判断这个节点出错时该怎么处理**。

举个具体的对比就明白了：

- "查客户历史订单"这个节点失败了——重跑一次完全没问题，查两次和查一次结果一样，而且结果还能缓存起来下次复用；
- "发起退款"这个节点失败了——**绝对不能无脑重跑**。你不知道上一次是"已经退款成功但响应丢了"还是"根本没执行"，重跑一次就可能退两笔钱。

同样是"失败了"，一个该重试，一个该停下来查清楚。区别就在于**这个节点有没有外部副作用**。所以分类不是形式主义，它直接决定了 4.3 节的容错策略该怎么写。

按这个思路看四类节点：

| 节点类型 | 干什么 | 典型场景 | 容错要点 |
| --- | --- | --- | --- |
| **LLM 节点** | 调模型做判断和生成 | 意图分类、回复起草、内容总结 | 输出不稳定（可能带标点、多废话），**必须归一化 + 白名单兜底**，不能拿模型原话当枚举用 |
| **数据节点** | 从外部取数，**无副作用** | 文档搜索、客户历史查询、拉接口数据 | 典型 IO，失败可重试、结果可缓存；官方预置了 `HttpNode` 等现成实现 |
| **操作节点** | 产生**外部影响**的动作 | 发回复、建工单、更新 Bug 跟踪 | 有副作用：**重试前必须保证幂等**，且不建议缓存（缓存写操作会导致动作没真正执行） |
| **人工节点** | 承接人工介入 | 内容审核、参数补全、决策确认 | 逻辑很轻，核心是配合 `interruptBefore` 暂停等输入；它本身几乎不会"出错"，会出错的是"人一直不来" |

案例代码里那两个并行节点就是典型的数据节点（只读、可重试），而 `approve` / `reject` 属于操作节点——真实业务里它们要发通知、写业务表，所以得考虑幂等。

另外两点值得知道：官方提供了不少开箱即用的预置节点（`QuestionClassifierNode`、`AgentToolNode` 等），常用能力不用从零写；你也可以把自己写的通用 `NodeAction` 封装成"标准工位模组"，在多个图里复用。

### 4.2 导出 PlantUML / Mermaid

这是 StateGraph 非常实用的开箱能力，核心价值是**把代码定义的流程直接变成可视化流程图**，评审、沟通、排查效率远高于读代码。

**用法**（在**未编译的 StateGraph 对象**上直接调用，指定格式和标题即可）：

```java
// 导出 Mermaid 格式
GraphRepresentation mermaid = stateGraph.getGraph(
    GraphRepresentation.Type.MERMAID, "doc-approval"
);
System.out.println(mermaid.content());

// 导出 PlantUML 格式
GraphRepresentation plantuml = stateGraph.getGraph(
    GraphRepresentation.Type.PLANTUML, "doc-approval"
);
```

把输出的文本复制到支持 Mermaid / PlantUML 的 Markdown 编辑器、IDE 插件里，就能自动渲染成完整的流程图，节点、边、分支、走向一目了然。

两个关键注意点：

1. **调用对象是 StateGraph，不是 CompiledGraph**。导出能力基于「图的定义」而非编译后的运行时对象。所以工程里建议**把 StateGraph 提成成员变量复用**——既用来编译运行，也用来导出流程图；
2. **核心使用场景**：和产品、业务评审流程时，甩一张图比念 Java 代码效率高一个数量级；调试排错时，先看图确认拓扑结构有没有问题，再去查代码逻辑。

### 4.3 错误处理：谁能修复，就交给谁

写 Graph 时最偷懒的做法是给每个节点套一层 `try-catch (Exception e)`，然后返回一个默认值。这么做短期看"流程不会崩"，长期看是把所有问题都捂住了——模型参数错了、数据库挂了、业务规则不合法，最后都变成一句看不懂的默认输出，排查时完全无从下手。

官方的分类策略要清醒得多，核心原则只有一句：**按"谁有能力修复这个错误"，决定交给谁处理。**

**① 系统能自己修：瞬时错误 → 自动重试**

网络抖动、接口限流、临时超时——这类错误的特点是"再试一次大概率就好了"，人根本不需要知道。交给节点内部的重试机制（可配次数 + 指数退避），重试成功就当没发生过，全部失败再往上抛。所有外部 IO 调用都该优先考虑这一层。

**② 模型能自己修：把错误当成一次观察结果喂回去**

工具调用失败、参数解析错误、返回格式不对——这类错误的特点是"模型看到错误信息后能自己调整"。所以处理方式不是简单重试，而是**把错误写进 state，再路由回模型节点**，让它看着错误信息重新思考、换个参数再来一次：

```java
try {
    String result = runTool(toolCall);
    return Map.of("tool_result", result, "next_node", "agent");
} catch (ToolException e) {
    // 把错误写进 state，路由回 agent 节点让模型自己修正
    return Map.of("tool_result", "Tool error: " + e.getMessage(), "next_node", "agent");
}
```

这个写法有个很重要的观念转变：**失败不是异常，而是一次可观察的结果**。模型拿到"上次调用报了什么错"，就有了调整的依据——这正是 3.5 节那个 ReAct 循环能成立的前提。

**③ 只有人能修：中断流程，等人补全或拍板**

缺客户 ID、缺订单号、需要领导签字——这类错误系统修不了、模型也猜不出来（猜出来就是编造，更危险）。处理方式就是 `interruptBefore` 暂停，把缺什么、要批什么摆给人看，人填完或签完再从断点继续。

**④ 谁都修不了：让它炸**

未知异常、代码 Bug、系统级错误——既不能自动恢复也不能由人修复，那就**不要捕获**，让异常直接冒泡终止流程，保留完整堆栈和现场状态。这听起来很粗暴，但比"捕获后返回默认值然后带着脏数据继续跑"安全得多：**带着错误状态继续往下走，往往会在更远的地方造成更难挽回的后果**（比如用错误的金额继续后面的打款节点）。

记住一条判断顺序：**能自动恢复吗？模型能自己改吗？需要人介入吗？都不行 → 抛出去。**

### 4.4 用图调试：按优先级排查

流程出 Bug 时按以下顺序排查，覆盖了 90% 的新手常见坑：

1. **先看 Mermaid 流程图**。确认拓扑结构有没有错：边接反了没、节点名写错没、有没有漏边、条件边的目标节点和映射是否对应。很多时候流程"走丢"，就是边连错了或者节点名拼写不一致。
2. **打印每个节点的 state 出入日志**。打印节点执行前的 state、执行后返回的 Map，看 key 有没有正确写入、数据是不是预期值。绝大多数流程问题本质都是 state 里的数据不符合预期。
3. **检查 KeyStrategy 配置**（新手第一大坑）：是不是该用 `AppendStrategy` 的日志/消息列表错用了 `ReplaceStrategy`，导致历史被覆盖；是不是有 key 没在 KeyStrategyFactory 里注册，导致写进去的数据没被合并进 state。
4. **中断场景专项排查**：Saver 有没有正确配置；启动和恢复时的 `threadId` 是否完全一致；`interruptBefore` 的节点名是否准确。
5. **核对条件边的路由映射**：条件边返回的路由 key 和后面 Map 里的 key 是否完全一致（大小写、拼写、空格）。不一致会导致框架找不到下一个节点，流程直接中断。

---

## 五、案例：文档审批 Graph

**流程：** 接收 → 分类 → **并行合规检查（敏感词 ∥ 必填项）→ 汇合** → 人工确认 → 通过 / 驳回；导出 Mermaid。

```text
START
  → ingest                    清洗文档文本
  → classify                  LLM 分类：policy / contract / other
  → sensitive_check     ┐
  → required_field_check ┘    并行：两个检查互不依赖
  → compliance_merge          汇合：两份结果合成 compliance_ok
  → human_review              interruptBefore：执行前暂停，等人工
  → approve 或 reject          条件边按 next_node 分流
  → END
```

### 5.1 示例代码

```java
package space.kmind.zk.springaialibabalearning.stage06;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 文档审批流程 StateGraph 示例（含并行检查 + 人工卡点）
 * 适配 Spring AI Alibaba 1.1.2.2
 */
@Tag(name = "Stage06-文档审批 Graph", description = "StateGraph 并行检查 + 人工审批流实战")
@RestController
public class ADocApprovalGraphDemo {

    // ========== 节点名：集中定义，避免字符串散落各处拼错（调试第一大坑）==========
    private static final String NODE_INGEST = "ingest";
    private static final String NODE_CLASSIFY = "classify";
    private static final String NODE_SENSITIVE_CHECK = "sensitive_check";
    private static final String NODE_REQUIRED_CHECK = "required_field_check";
    private static final String NODE_COMPLIANCE_MERGE = "compliance_merge";
    private static final String NODE_HUMAN_REVIEW = "human_review";
    private static final String NODE_APPROVE = "approve";
    private static final String NODE_REJECT = "reject";

    // ========== 状态 key ==========
    private static final String K_DOC_TEXT = "doc_text";
    private static final String K_DOC_TYPE = "doc_type";
    private static final String K_SENSITIVE_OK = "sensitive_ok";
    private static final String K_SENSITIVE_NOTE = "sensitive_note";
    private static final String K_REQUIRED_OK = "required_ok";
    private static final String K_REQUIRED_NOTE = "required_note";
    private static final String K_COMPLIANCE_OK = "compliance_ok";
    private static final String K_COMPLIANCE_NOTE = "compliance_note";
    private static final String K_APPROVED = "approved";
    private static final String K_REVIEW_COMMENT = "review_comment";
    private static final String K_FINAL_STATUS = "final_status";
    private static final String K_NEXT_NODE = "next_node";
    private static final String K_REVIEW_TRAIL = "review_trail";

    // ========== 业务常量 ==========
    private static final Set<String> DOC_TYPES = Set.of("policy", "contract", "other");
    private static final List<String> SENSITIVE_WORDS = List.of("违禁", "保密协议外传", "内部绝密");
    private static final int MIN_DOC_LENGTH = 20;
    /** 已终结状态：这些状态下不允许再次审批，避免重复放行 */
    private static final Set<String> TERMINAL_STATUS = Set.of("approved", "rejected");

    private final StateGraph stateGraph;
    private final CompiledGraph compiledGraph;
    private final ChatClient chatClient;

    public ADocApprovalGraphDemo(ChatModel chatModel) throws GraphStateException {
        this.chatClient = ChatClient.builder(chatModel).build();

        // ========== 1. 状态 key 的合并策略 ==========
        // 标量用 Replace；审批轨迹用 Append（每次追加一条，保留完整留痕）
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategyMap = new HashMap<>();
            strategyMap.put(K_DOC_TEXT, new ReplaceStrategy());
            strategyMap.put(K_DOC_TYPE, new ReplaceStrategy());
            strategyMap.put(K_SENSITIVE_OK, new ReplaceStrategy());
            strategyMap.put(K_SENSITIVE_NOTE, new ReplaceStrategy());
            strategyMap.put(K_REQUIRED_OK, new ReplaceStrategy());
            strategyMap.put(K_REQUIRED_NOTE, new ReplaceStrategy());
            strategyMap.put(K_COMPLIANCE_OK, new ReplaceStrategy());
            strategyMap.put(K_COMPLIANCE_NOTE, new ReplaceStrategy());
            strategyMap.put(K_APPROVED, new ReplaceStrategy());
            strategyMap.put(K_REVIEW_COMMENT, new ReplaceStrategy());
            strategyMap.put(K_FINAL_STATUS, new ReplaceStrategy());
            strategyMap.put(K_NEXT_NODE, new ReplaceStrategy());
            strategyMap.put(K_REVIEW_TRAIL, new AppendStrategy());
            return strategyMap;
        };

        // ========== 2. 定义各个节点 ==========
        // 节点1：文档接入，清洗文本
        NodeAction ingestNode = state -> Map.of(
                K_DOC_TEXT, state.value(K_DOC_TEXT, "").toString().trim(),
                K_NEXT_NODE, NODE_CLASSIFY,
                K_REVIEW_TRAIL, List.of("ingest: 文档已接收")
        );

        // 节点2：LLM 文档分类（输出需归一化 + 兜底，不能把模型原话直接当枚举用）
        NodeAction classifyNode = state -> {
            String text = state.value(K_DOC_TEXT, "").toString();
            String docType = classify(text);
            return Map.of(
                    K_DOC_TYPE, docType,
                    K_NEXT_NODE, NODE_COMPLIANCE_MERGE,
                    K_REVIEW_TRAIL, List.of("classify: doc_type=" + docType)
            );
        };

        // 节点3-A：敏感词检查（并行分支一，只写 sensitive_* 这两个专属 key）
        NodeAction sensitiveNode = state -> {
            String text = state.value(K_DOC_TEXT, "").toString();
            List<String> hits = SENSITIVE_WORDS.stream().filter(text::contains).toList();
            boolean ok = hits.isEmpty();
            return Map.of(
                    K_SENSITIVE_OK, ok,
                    K_SENSITIVE_NOTE, ok ? "未命中敏感词" : "命中敏感词：" + String.join("、", hits)
            );
        };

        // 节点3-B：必填项/结构完整性检查（并行分支二，只写 required_*，避免与分支一互相覆盖）
        NodeAction requiredNode = state -> {
            String text = state.value(K_DOC_TEXT, "").toString();
            boolean lengthOk = text.length() >= MIN_DOC_LENGTH;
            boolean structured = text.contains("。") || text.contains("\n");
            boolean ok = lengthOk && structured;
            String note = ok ? "必填项与结构完整"
                    : "文档不完整（字数<" + MIN_DOC_LENGTH + " 或缺少段落结构）";
            return Map.of(
                    K_REQUIRED_OK, ok,
                    K_REQUIRED_NOTE, note
            );
        };

        // 节点4：汇合节点。两个并行分支都跑完才会执行到这里
        NodeAction complianceMergeNode = state -> {
            boolean sensitiveOk = Boolean.TRUE.equals(state.value(K_SENSITIVE_OK, false));
            boolean requiredOk = Boolean.TRUE.equals(state.value(K_REQUIRED_OK, false));
            boolean ok = sensitiveOk && requiredOk;
            String note = String.join("；",
                    String.valueOf(state.value(K_SENSITIVE_NOTE, "")),
                    String.valueOf(state.value(K_REQUIRED_NOTE, "")));
            return Map.of(
                    K_COMPLIANCE_OK, ok,
                    K_COMPLIANCE_NOTE, (ok ? "合规校验通过：" : "合规校验未通过，需人工重点审核：") + note,
                    K_NEXT_NODE, NODE_HUMAN_REVIEW,
                    K_REVIEW_TRAIL, List.of("compliance_merge: ok=" + ok)
            );
        };

        // 节点5：人工审核（interruptBefore 之后才执行，读取人工注入的 approved / review_comment）
        NodeAction humanReviewNode = state -> {
            boolean approved = Boolean.TRUE.equals(state.value(K_APPROVED, false));
            String comment = String.valueOf(state.value(K_REVIEW_COMMENT, ""));
            return Map.of(
                    K_FINAL_STATUS, "human_reviewed",
                    K_NEXT_NODE, approved ? NODE_APPROVE : NODE_REJECT,
                    K_REVIEW_TRAIL, List.of("human_review: " + (approved ? "批准" : "驳回")
                            + (comment.isBlank() ? "" : "，意见=" + comment))
            );
        };

        // 节点6：审批通过
        NodeAction approveNode = state -> Map.of(
                K_APPROVED, true,
                K_FINAL_STATUS, "approved",
                K_REVIEW_TRAIL, List.of("approve: 文档已放行")
        );

        // 节点7：审批驳回
        NodeAction rejectNode = state -> Map.of(
                K_APPROVED, false,
                K_FINAL_STATUS, "rejected",
                K_REVIEW_TRAIL, List.of("reject: 文档已驳回")
        );

        // ========== 3. 组装图：添加节点 + 连接边 ==========
        this.stateGraph = new StateGraph(keyStrategyFactory)
                .addNode(NODE_INGEST, node_async(ingestNode))
                .addNode(NODE_CLASSIFY, node_async(classifyNode))
                .addNode(NODE_SENSITIVE_CHECK, node_async(sensitiveNode))
                .addNode(NODE_REQUIRED_CHECK, node_async(requiredNode))
                .addNode(NODE_COMPLIANCE_MERGE, node_async(complianceMergeNode))
                .addNode(NODE_HUMAN_REVIEW, node_async(humanReviewNode))
                .addNode(NODE_APPROVE, node_async(approveNode))
                .addNode(NODE_REJECT, node_async(rejectNode))

                // 顺序主流程
                .addEdge(StateGraph.START, NODE_INGEST)
                .addEdge(NODE_INGEST, NODE_CLASSIFY)

                // 并行扇出：两个检查互不依赖，同时开始
                .addEdge(NODE_CLASSIFY, NODE_SENSITIVE_CHECK)
                .addEdge(NODE_CLASSIFY, NODE_REQUIRED_CHECK)
                // 并行扇入：两个都完成后才进汇合节点
                .addEdge(NODE_SENSITIVE_CHECK, NODE_COMPLIANCE_MERGE)
                .addEdge(NODE_REQUIRED_CHECK, NODE_COMPLIANCE_MERGE)
                .addEdge(NODE_COMPLIANCE_MERGE, NODE_HUMAN_REVIEW)

                // 条件边：人工审核后按 next_node 分流（边只做转发，决策在节点里）
                .addConditionalEdges(
                        NODE_HUMAN_REVIEW,
                        edge_async(state -> state.value(K_NEXT_NODE, NODE_REJECT).toString()),
                        Map.of(
                                NODE_APPROVE, NODE_APPROVE,
                                NODE_REJECT, NODE_REJECT
                        )
                )

                // 终点
                .addEdge(NODE_APPROVE, StateGraph.END)
                .addEdge(NODE_REJECT, StateGraph.END);

        // ========== 4. 导出 Mermaid 流程图 ==========
        System.out.println("====== Mermaid 流程图 ======");
        System.out.println(stateGraph
                .getGraph(GraphRepresentation.Type.MERMAID, "文档审批流程")
                .content());
        System.out.println("============================");

        // ========== 5. 编译 + 配置持久化与中断点 ==========
        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder()
                        .register(new MemorySaver())
                        .build())
                .interruptBefore(NODE_HUMAN_REVIEW) // 在人工审核节点【之前】暂停
                .build();

        this.compiledGraph = stateGraph.compile(compileConfig);
    }

    /**
     * LLM 分类：输出归一化 + 失败兜底。
     * 模型可能返回 "policy。"、"`contract`"、"根据内容判断：other" 等，
     * 不能直接拿原话当枚举用；调用失败时降级为 other，不让整个流程崩掉。
     */
    private String classify(String text) {
        try {
            String raw = chatClient.prompt()
                    .user("将下面文档分类为 policy / contract / other，只返回类别单词，不要多余内容：\n" + text)
                    .call()
                    .content();
            if (raw == null) {
                return "other";
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            return DOC_TYPES.contains(normalized) ? normalized : "other";
        } catch (Exception e) {
            return "other"; // 分类是辅助信息，失败不应阻断审批流程
        }
    }

    /**
     * 提交文档，执行到人工审核前中断
     */
    @Operation(summary = "提交文档审批（跑到 human_review 前暂停）")
    @PostMapping("/api/s61/submit")
    public Map<String, Object> submit(@RequestBody SubmitReq req) {
        // 不传 threadId 就生成一个，避免所有人共用默认 id 导致会话串味
        String threadId = (req == null || req.threadId() == null || req.threadId().isBlank())
                ? "doc-" + UUID.randomUUID()
                : req.threadId();
        String docText = (req == null || req.docText() == null || req.docText().isBlank())
                ? "本公司考勤制度规定员工须按时打卡，迟到需说明原因并报备。"
                : req.docText();

        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        compiledGraph.invoke(Map.of(K_DOC_TEXT, docText), runnableConfig);

        // 中断后回读快照，把当前状态原样返回给前端
        return snapshotView(threadId);
    }

    /**
     * 人工审批后恢复流程
     */
    @Operation(summary = "人工审批恢复流程")
    @PostMapping("/api/s62/resume")
    public Map<String, Object> resume(@RequestBody ResumeReq req) {
        if (req == null || req.threadId() == null || req.threadId().isBlank()) {
            return Map.of("error", "threadId 不能为空，且与 submit 时必须一致");
        }
        if (req.approved() == null) {
            return Map.of("error", "approved 必须显式传 true/false，缺省可能导致误放行");
        }
        String threadId = req.threadId();

        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        // 1. 取中断处快照（须与 submit 使用同一 threadId）
        StateSnapshot snapshot = compiledGraph.getState(runnableConfig);
        if (snapshot == null || snapshot.state() == null) {
            return Map.of("error", "未找到 threadId=" + threadId + " 的快照：请先调用 submit，或检查 threadId 是否一致");
        }
        OverAllState state = snapshot.state();

        // 2. 已终结的会话不允许重复审批
        String status = String.valueOf(state.value(K_FINAL_STATUS, ""));
        if (TERMINAL_STATUS.contains(status)) {
            return Map.of("error", "该会话已审批完成（final_status=" + status + "），不能重复审批",
                    "threadId", threadId);
        }

        // 3. 写入人工结论。禁止 state.data().put(...) —— data() 是不可变视图，会抛 UnsupportedOperationException
        Map<String, Object> humanInput = new HashMap<>();
        humanInput.put(K_APPROVED, req.approved());
        if (req.comment() != null && !req.comment().isBlank()) {
            humanInput.put(K_REVIEW_COMMENT, req.comment());
        }
        state.updateState(humanInput);

        // 4. withResume() 标记续跑，再 invoke 从断点继续（human_review → 条件边分流）
        RunnableConfig resumeConfig = runnableConfig.withResume();
        compiledGraph.invoke(state, resumeConfig);

        return snapshotView(threadId);
    }

    /**
     * 查看当前会话快照：调试、审计、时间旅行都靠它
     */
    @Operation(summary = "查看当前会话状态快照")
    @GetMapping("/api/s63/state")
    public Map<String, Object> state(@RequestParam(defaultValue = "doc-001") String threadId) {
        StateSnapshot snapshot = compiledGraph.getState(
                RunnableConfig.builder().threadId(threadId).build());
        if (snapshot == null || snapshot.state() == null) {
            return Map.of("threadId", threadId, "status", "not_found");
        }
        return snapshotView(threadId);
    }

    /**
     * 获取 Mermaid 流程图文本
     */
    @Operation(summary = "获取 Mermaid 流程图")
    @GetMapping("/api/s62/mermaid")
    public String getMermaid() {
        return stateGraph.getGraph(GraphRepresentation.Type.MERMAID, "文档审批流程").content();
    }

    /** 统一的状态视图：中断态和终态用同一套字段返回，前端不用分两套解析 */
    private Map<String, Object> snapshotView(String threadId) {
        StateSnapshot snapshot = compiledGraph.getState(
                RunnableConfig.builder().threadId(threadId).build());
        if (snapshot == null || snapshot.state() == null) {
            return Map.of("threadId", threadId, "status", "not_found");
        }
        OverAllState s = snapshot.state();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("threadId", threadId);
        view.put("status", String.valueOf(s.value(K_FINAL_STATUS, "pending_review")));
        view.put("docType", s.value(K_DOC_TYPE, ""));
        view.put("sensitiveOk", s.value(K_SENSITIVE_OK, false));
        view.put("requiredOk", s.value(K_REQUIRED_OK, false));
        view.put("complianceOk", s.value(K_COMPLIANCE_OK, false));
        view.put("complianceNote", s.value(K_COMPLIANCE_NOTE, ""));
        view.put("approved", s.value(K_APPROVED, false));
        view.put("reviewComment", s.value(K_REVIEW_COMMENT, ""));
        view.put("nextNode", s.value(K_NEXT_NODE, ""));
        view.put("trail", s.value(K_REVIEW_TRAIL, List.of()));
        return view;
    }

    public record SubmitReq(String threadId, String docText) {
    }

    public record ResumeReq(String threadId, Boolean approved, String comment) {
    }
}
```

### 5.2 代码里几个刻意为之的设计

读代码时留意这几处，它们各自对应一个知识点：

1. **节点名和状态 key 全部常量化**。`interruptBefore`、条件边的映射表、节点返回值里到处是字符串，拼接错一个字母流程就会静默走歪——这是 4.4 节排查清单里的第一大坑，常量化从源头杜绝。
2. **并行分支各写各的 key**。`sensitive_check` 只写 `sensitive_*`，`required_field_check` 只写 `required_*`，最后由 `compliance_merge` 汇总。如果两个分支都写 `compliance_ok`，在 `ReplaceStrategy` 下会互相覆盖，而且**谁覆盖谁取决于谁跑得快**——这种 bug 时灵时不灵，最难查。
3. **LLM 输出归一化 + 兜底**。`classify()` 把模型返回清洗成纯小写字母再比对白名单，避免 `"policy。"`、"`contract`" 这类"看起来对其实不匹配"的返回值导致路由失效；调用异常时降级为 `other`，不让分类这种辅助信息阻断整个审批流程。
4. **路由显式化**。每个节点把去向写进 `next_node`，条件边只做 `state.value(K_NEXT_NODE, ...)` 转发。调试时看一眼 `next_node` 就知道当时为什么走这条路。
5. **`AppendStrategy` 留痕**。`review_trail` 注册为追加策略，ingest / classify / merge / human_review / approve 各追加一条，最后返回给前端就是一份完整的审批轨迹——注意追加策略的 value 要传 `List`，元素会被逐个累加。
6. **resume 的三道校验**：threadId 非空、`approved` 必须显式传（防止缺省 false 造成"没表态就算驳回"或误放行）、已终结会话拒绝重复审批。原版代码里 `request.getOrDefault("approved", "false")` 的写法有歧义，这里改成 `Boolean` 包装类型，没传就报错。
7. **threadId 不传就生成**。默认 `doc-001` 意味着所有人共用一条会话——这是上一篇讲过的"最严重的越权漏洞"的同类问题，这里改成不传就生成 UUID。
8. **统一状态视图**。中断态和终态都走 `snapshotView()` 返回同一套字段，前端不用分两套解析，也顺带提供了 `/api/s63/state` 这个调试入口。

### 5.3 自测顺序

**① 提交（中断在 human_review 前）**

`POST /api/s61/submit`

```json
{"threadId":"doc-1","docText":"本公司考勤制度规定员工须按时打卡，迟到需说明原因并报备。"}
```

期望：`status` 为 `pending_review`；`docType` 被分类为 `policy`；`sensitiveOk`、`requiredOk` 均为 `true`；`nextNode` 为 `human_review`；`trail` 里有 ingest / classify / compliance_merge 三条记录（**没有** human_review，说明确实停在了它前面）。

**② 验证并行确实生效**

`GET /api/s63/state?threadId=doc-1`

期望：`complianceNote` 里同时出现"未命中敏感词"和"必填项与结构完整"两段——只有两个并行分支都跑完并写入各自 key，汇合节点才能拼出这句。再故意提交一个短文本（比如 `"测试"`），`requiredOk` 应变 `false`，`complianceOk` 随之变 `false`，但流程仍会进人工审核（合规不通过也要人看一眼）。

**③ 通过后恢复**

`POST /api/s62/resume`

```json
{"threadId":"doc-1","approved":true,"comment":"已核对，同意发布"}
```

期望：`status` 为 `approved`，`approved=true`，`reviewComment` 带回审批意见，`trail` 末尾多出 `human_review: 批准…` 和 `approve: 文档已放行` 两条。

**④ 换 thread 测驳回**

先 `submit`（`threadId=doc-2`，短文本），再：

```json
{"threadId":"doc-2","approved":false,"comment":"内容过短，请补充"}
```

期望：`status` 为 `rejected`。

**⑤ 健壮性验证**

- 不传 `threadId` 再 submit → 返回自动生成的 `threadId`（形如 `doc-<UUID>`），两条会话互不干扰；
- 对已审批完成的 `doc-1` 再调一次 resume → 返回 `error: 该会话已审批完成`，不会重复放行；
- resume 时不传 `approved` → 返回 `error: approved 必须显式传 true/false`；
- 用一个从未提交过的 threadId 调 resume → 返回 `error: 未找到…快照`。

**⑥ 看流程图**

`GET /api/s62/mermaid`，把输出贴到支持 Mermaid 的编辑器里，应该能看到 classify 之后分成两条、再汇入 compliance_merge 的并行结构。如果图上没有分叉，说明边没接对——这正是 4.4 节"先看图再查代码"的排查顺序。

---

## 六、下一步可以探索的官方进阶主题

本篇讲了 Graph 核心。官方 QuickStart 末尾还列了这些可扩展方向：

1. **流式输出**：LLM 节点用 `StreamingChatGenerator` + `AsyncGenerator`，向用户实时展示进度；
2. **工具集成 / MCP**：`AgentToolNode` 异步工具执行、把 MCP 工具绑定给指定节点；
3. **持久化与时光旅行（Time Travel）**：Checkpoint 全保留 → 可回到历史状态检查「当时发生了什么」，甚至从过去某个 checkpoint fork 出新轨迹；
4. **子图**：NodeAction / StateGraph / CompiledGraph 三种嵌套方式；
5. **错误处理与重试**：瞬时错误重试、LLM 可恢复错误循环、人工可修复错误暂停。

其中第 3 条值得单独提一句：本篇案例只用了"最新快照"，而 `saver.list(threadId)` 能列出该会话的全部历史检查点。配合 `checkpointId` 恢复到任意一个历史点，就是"时间旅行"——排查线上事故时，能精确回到出问题的那一步看当时的状态，比翻日志高效得多。

---

## 下一篇预告

**RAG 进阶**：切分、元数据过滤、Hybrid、引用溯源，以及 Agent 里「检索当 Tool 还是注入节点」。

---

## 官方参考

- https://java2ai.com/docs/frameworks/graph-core/quick-start
- https://java2ai.com/docs/1.0.0.2/tutorials/graph/whats-spring-ai-alibaba-graph/
- https://java2ai.com/docs/1.0.0.2/tutorials/graph/parallel-node/
- https://java2ai.com/docs/1.0.0.2/tutorials/graph/human-in-the-loop/
