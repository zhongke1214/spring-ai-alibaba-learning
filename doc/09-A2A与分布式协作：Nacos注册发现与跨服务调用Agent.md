# A2A 与分布式协作：Nacos 注册发现与跨服务调用Agent

## 1. 前期准备

版本基准：Spring AI Alibaba 1.1.2.x + Spring AI 1.1.2 + JDK 17+

官方文档：https://java2ai.com

示例 README：`examples/documentation/.../advanced/a2a/README.md`（spring-ai-alibaba 仓库）

社区示例：https://github.com/nacos-group/nacos-spring-ai-alibaba-example（`a2a-example`）

Nacos Agent Registry：https://nacos.io/docs/latest/manual/user/ai/agent-registry/

版本跟仓库约定对齐：

```text
JDK                 17+
Spring Boot         3.5.7
Spring AI           1.1.2
Spring AI Alibaba   1.1.2.2
```

 05篇多智能体编排Multi-agent的多智能体是**同进程**编排。本篇讲跨进程——**服务 A 的 Agent，通过 A2A + Nacos，发现并调用服务 B 的 Agent。**

---

## 2. A2A 是什么？

Spring AI Alibaba 中的 A2A，是其为 Java 开发者提供的**分布式智能体（Agent-to-Agent）通信与协作支持**。它基于 Google 推出的开放协议 A2A（Agent2Agent），旨在解决单个智能体能力有限、企业级应用中多智能体跨进程、跨框架协作的难题。

**为什么需要 A2A：从“单体智能”到“分布式协作”？**

在复杂的企业场景中，让一个智能体“包打天下”会面临多重挑战，而分布式多智能体架构是更优的工程选择：

- **能力与复杂性瓶颈**：单一智能体难以成为解决所有问题的“超级专家”。当工具数量增多、对话轮次变长时，上下文会急剧膨胀，导致性能下降。
- **组织协同与运维难题**：在单进程部署模式下，不同团队（如支付团队和搜索团队）需要共同维护同一份代码，协同成本极高。同时，单点故障会导致所有智能体不可用，也无法按需进行细粒度的扩缩容。
- **安全隔离需求**：所有智能体共享同一进程的内存和上下文，权限边界模糊，难以实施数据隔离和最小权限原则。

A2A 协议正是为了解决这些问题而生，它让智能体可以像微服务一样，被独立开发、部署和调用，并通过标准化协议进行通信。

### 2.1 通信模型

**1. Provider 声明 Agent 与 AgentCard 元数据**

一切始于一个普通的 `ReactAgent` Bean。Provider 通过 `ReactAgent.builder()` 定义智能体的名称、模型、描述和指令。这个 Bean 本身只是一个本地智能体，关键的一步在于**配置元数据**，使其具备被远程发现的能力：

yaml

```
spring:
  ai:
    alibaba:
      a2a:
        server:
          version: 1.0.0
          card:
            name: data_analysis_agent
            description: 专门用于数据分析和统计计算的本地智能体
            provider:
              name: Spring AI Alibaba Documentation
              organization: Spring AI Alibaba
```



这里的 `card` 配置，就是未来会注册到注册中心的“智能体名片”的雏形。

**2. A2A Server 自动暴露能力描述与消息入口**

应用启动后，A2A Server 的自动配置会接管这个 Bean，并**自动生成 AgentCard**（整合了 Bean 信息与 YAML 配置）。随后，它会暴露两个核心端点：

- `GET /.well-known/agent.json`：返回完整的 AgentCard JSON，供发现者读取智能体的能力、端点 URL 和支持的协议特性（如是否支持流式）。
- `POST /a2a`（或自定义为 `/a2a/message`）：接收 JSON-RPC 2.0 格式的调用请求。请求体中的 `method` 字段（如 `message/send` 或 `message/stream`）决定是同步调用还是流式调用。

**3. Registry 把 Card 注册到 Nacos**

这是让智能体“可被发现”的关键一步。通过配置开启注册后，A2A Server 会将生成的 AgentCard 自动写入 Nacos：

```
spring.ai.alibaba.a2a.nacos:
  server-addr: 127.0.0.1:8848
  registry:
    enabled: true   # 开启注册
```



Nacos 3 及以上版本原生支持 A2A AgentCard 模型的存储与推送，因此可以直接作为 A2A Registry 使用。

**4. Consumer 发现 → 构造 `A2aRemoteAgent` → `invoke`**

消费者侧通过 `AgentCardProvider` 从 Nacos 拉取目标 Agent 的 Card 信息，然后利用这些元数据构造一个 `A2aRemoteAgent` 代理对象。这个代理内部封装了 JSON-RPC over HTTP 的通信细节，并支持同步与流式两种模式。

```
A2aRemoteAgent remoteAgent = A2aRemoteAgent.builder()
    .name("data_analysis_agent")
    .description("远程数据分析智能体")
    .agentCardProvider(nacosAgentCardProvider) // 从Nacos发现
    .build();
// 之后便可像调用本地Agent一样调用 remoteAgent.invoke(...)
```

### 2.2 与 Nacos 集成：注册、发现、调用

**添加依赖：**

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-a2a-nacos</artifactId>
</dependency>
```

**关键配置：**

```yaml
spring:
  ai:
    alibaba:
      a2a:
        nacos:
          server-addr: 127.0.0.1:8848
          username: nacos
          password: nacos
          registry:
            enabled: true    # 我作为提供者：注册本地 Agent
          discovery:
            enabled: true    # 我作为消费者：发现别人
        server:
          version: 1.0.0
          card:
            name: data_analysis_agent          # 必须与 ReactAgent.name 一致
            description: 专门用于数据分析的智能体
            provider:
              name: demo
              organization: learning
```

| 开关 | 角色 |
| --- | --- |
| `registry.enabled=true` | 提供者：本地 Agent → Nacos |
| `discovery.enabled=true` | 消费者：Nacos → AgentCard |
| 可只开一侧 | 纯 Server 或纯 Client 进程 |

本地验证 Card：

```bash
curl http://localhost:8080/.well-known/agent.json
```

Nacos 控制台「Agent 管理」中应能看到对应 Agent。

**注意事项：**

**1、注册侧：**

`1.server.card.name` 必须与 `ReactAgent` Bean 的 `name()` 严格一致。

2.命名空间填的是 ID，不是名称。 

如果你的 Nacos 开了命名空间隔离，`spring.ai.alibaba.a2a.nacos.namespace` 必须填命名空间的 **ID**（形如 `e1231dd9-a9d6-4c32-9333-0f9578718ce4`），填名称（如 `a2a`）会被当作不存在的命名空间处理，注册和发现都会“静默失败”——Nacos 控制台里什么都看不到。

**2、发现侧：**

`spring-ai-alibaba-starter-a2a-nacos` 的自动配置中可能存在**多个** `AgentCardProvider` Bean，直接 `@Autowired` 可能产生歧义。实践中的做法是按名注入 `nacosAgentCardProvider`：

```
@Service
public class RemoteAgentService {
    private final AgentCardProvider agentCardProvider;

    public RemoteAgentService(
            @Qualifier("nacosAgentCardProvider") AgentCardProvider agentCardProvider) {
        this.agentCardProvider = agentCardProvider;
    }
```

构造 `A2aRemoteAgent` 时，**不需要手动指定远程地址**，`agentCardProvider` 会在 `build()` 阶段从 Nacos 解析出目标 Agent 的端点信息：

```
A2aRemoteAgent remote = A2aRemoteAgent.builder()
        .name("data_analysis_agent")          // 与提供者注册的 Card name 一致
        .agentCardProvider(agentCardProvider)  // 从 Nacos 自动获取 AgentCard
        .description("数据分析远程代理")
        .build();
Optional<OverAllState> result = remote.invoke("请根据季度数据给出同比与环比分析概要。");
```

`invoke` 返回的是 `Optional<OverAllState>`，调用方需要自行处理“远程 Agent 没有返回内容”的兜底分支。如果 Nacos 中查不到对应名称的 Agent，或者名称不一致，底层可能会在构建客户端前就失败，抛出带有 Agent 名称和 resolver 信息的异常，而不是静默返回空结果。

### 2.3 本地多 Agent vs 跨服务多 Agent

| 维度 | 本地 | 跨服务 A2A |
| --- | --- | --- |
| Supervisor 调专家 | 方法调用级 | 网络 RPC 级 |
| outputKey / 状态 | 同 OverAllState | 需约定输入输出契约 |
| 发布 | 一次发版 | 专家服务可独立发版 |
| card.name | Bean 名 | **跨服务唯一身份** |

**1、Supervisor 调专家：方法调用级 → 网络 RPC 级**

本地场景下，Supervisor 通过 `AgentTool.getFunctionToolCallback()` 或 Handoff 调用专家，失败是**同步异常**，在当前调用栈中立即抛出。Supervisor 能捕获、能重试、能降级——失败边界是封闭的。但是跨服务后，一次“调用”可能以至少五种形态失败：连接不上、写不进去、读超时、流式读到一半断开、重试后对方返回了互相矛盾的结果。

一个具体的后果是**重试不再是安全的默认动作**。本地调用重试，最坏情况是多跑一次内存计算；跨服务重试如果没有携带幂等键，每次重试都是一次全新的任务创建。有实践案例记录：读超时被设得小于远程 Agent 的 P99，超时触发重试，三次重试在远程侧留下了三条独立的检索记录，而主服务只用了其中一条。把重试次数从 2 提到 5 反而恶化了问题——根因不是网络抖动，而是尾延迟导致的超时误判，重试越多，远程负载越高，P99 越差。所以在这个维度上，替代“方法调用的异常同步性”的，是**分层超时 + 幂等键 + 有界重试**三件套，缺一不可。

**2、outputKey / 状态：同 `OverAllState` → 需约定输入输出契约**

本地多 Agent 共享同一个 `OverAllState`。子 Agent 通过 `.outputKey("article_content")` 写入，Supervisor 在 instruction 中用 `{article_content}` 直接读取，类型是 `AssistantMessage`。而`A2aRemoteAgent` 在消费者侧看起来仍然是一个 `BaseAgent`，内部是一个只有单个节点的 StateGraph，把本地状态序列化后通过 JSON-RPC 发往远程，再把远程响应写回本地状态。但它有一个容易被忽略的参数：`outputKey` 的**默认值是 `"output"`**。如果远程 Agent 实际返回的内容结构不是这个 key，或者提供方修改了自己的 `outputKey` 命名，消费者侧的反序列化不会在编译期报错，而是在运行时静默产生错位或空值。`shareState` 参数（默认 `true`）会把本地 `state.data()` 随请求发给远程 Agent。这带来一个隐性的契约依赖：远程 Agent 如果读取了某些状态字段，而提供方在后续版本中改变了字段语义，消费者的本地状态就会被以未预期的方式消费。

在这个维度上，替代“类型系统”的，是**显式的输入输出契约文档 + 向后兼容的字段演进策略**。能力声明中新增字段应当标记为可选并带默认值，而不是当作普通 DTO 直接加必填字段——否则新版 Agent 上线后，旧版消费者的解析器会直接判定请求非法，在协作开始前就失败。

**3、发布：一次发版 → 专家服务可独立发版**

这是 A2A 最大的工程收益，也是最大的契约风险来源。

本地多 Agent 的所有 Bean 在同一个 Spring 上下文里，编译期就能发现不兼容的签名变更。拆成独立服务后，知识库团队有自己的发版窗口，**不需要等主应用**——这是组织上拆分它的初衷。但代价是，提供方的发版节奏与消费方的兼容能力**解耦了**，而解耦之后没有任何机制自动保证两端仍然匹配。

Nacos 的 A2A Registry 提供了版本管理：同一个 AgentCard 可以有多个版本，发布者修改 Card 内容时应创建新版本，消费者默认获取当前默认发布版本，也可以指定版本号获取。但这只解决了“能找到哪个版本”，没有解决“找到的版本我是否兼容”。健壮的做法是在调用前做**版本区间比对**，超出支持范围就快速失败并输出可诊断的错误，而不是静默错答。

**4、`card.name`：Bean 名 → 跨服务唯一身份**

本地 Bean 名的作用域是 Spring 容器，冲突了启动就报错。跨服务后，`card.name` 是 Nacos 注册中心里的**唯一标识**。Nacos 的规则是：通过 `namespaceId` 和 `name` 共同唯一标识一个 AgentCard，同一命名空间内 name 不能重复，不同命名空间可以重复；name 长度不超过 64 字符，仅限 ASCII 可视字符。

这意味着 `server.card.name` 与 `ReactAgent` 内部 name 的一致性不再是“建议一致”，而是**注册正确性的硬性前提**。两者不一致时，Card 里写的名字和实际暴露的智能体身份脱节，发现侧拿到的名片指向一个不存在的身份。更隐蔽的失败模式是命名空间填了名称而非 ID——注册会静默失败，控制台里什么都看不到。

`card.name` 从“方便调试的标签”变成了**分布式系统中的服务身份**。它一旦发布，消费者就靠它来发现和寻址，改名等同于破坏性变更。

**5、进程边界：一个进程 = 一张名片**

**Spring AI Alibaba 的 A2A 自动配置在启动时，默认依赖容器中“唯一”的 `Agent` 类型 Bean 来生成 `AgentCard` 并绑定执行器**。如果存在多个 `Agent` Bean，自动配置就无法确定该把哪一个发布为 A2A 服务，从而导致启动失败。官方文档也明确提到：**默认情况下，只有一个 `Agent` Bean 会被注册**；如果需要注册多个 Agent，需要运行多个应用实例，每个实例配置不同的 Agent。这从设计上就决定了单进程内 A2A 自动配置只面向“单 Agent”场景。

如果你确实有多个智能体需要对外提供 A2A 服务，正确的做法是**拆分成多个独立的 Spring Boot 应用实例**，每个实例只定义一个 `Agent` Bean，并配置不同的 `server.card.name` 和端口。这样每个实例都会把自己的那一个 Agent 注册到 Nacos（或直接暴露端点），调用方通过 `AgentCardProvider` 按名称发现对应的 Agent 即可。如果你只是想在一个应用里保留多个 Agent 做内部编排（比如用 `SequentialAgent` 或 `SupervisorAgent` 把多个子 Agent 串起来），那就只把**根编排 Agent** 暴露为 A2A 服务，子 Agent 作为内部组件不注册为独立的 `Agent` Bean，或者把它们定义为非 `Agent` 类型（比如普通的工具类或子图），从而避开自动配置的“唯一 Bean”约束。

**选型判断：**

“同团队、强事务感 → 先本地；多团队、独立扩缩 → A2A”这个判断是准确的。补充一个中间态：**即使跨团队，如果事务感极强**（例如扣款与发货之间需要严格的一致性边界），也不意味着必须退回本地部署——可以用一个确定性的工作流（而非自主 LLM 调度）来持有控制流，A2A 远程 Agent 只承担“专家推理”节点，幂等键由工作流层统一注入，远程 Agent 不拥有事务决策权。

反过来，**同团队也可以上 A2A**，如果某个 Agent 有独立的重度依赖（独立向量库、独立 GPU、独立数据源）需要单独伸缩。这时拆分的驱动力是**资源与生命周期**，而不是组织边界。

### 2.4 超时、重试、链路追踪

**1、超时：三层拆分，而非固定一个单一时间**

在 A2A 的上下文中，单一超时值是危险的。Spring AI A2A 框架本身提供了服务端的超时保护，但消费者侧必须自己设计分层策略。

**框架内置的服务端超时**：`a2a.blocking.agent.timeout.seconds` 默认为 30 秒，控制 `AgentExecutor.executeAsMessage()` 的最长执行时间；`a2a.blocking.consumption.timeout.seconds` 默认为 5 秒，确保在返回响应前 `TaskStore` 的更新和持久化完成。这两个是**远程 Agent 自身**的保护边界，不替代消费者侧的调用超时。

**消费者侧应当拆成三层**：连接超时（建立 HTTP 连接的最大等待）、读超时（等待远程 Agent 返回首个响应字节的时间，应由远程业务的 P99 延迟反推，而非随意设定）、任务总时长（整个远程调用从发起到结束的硬上限，由主流程可接受的首字延迟反推）。读超时小于远程 P99 是重试风暴的常见根因——超时误判触发重试，重试抬高远程负载，P99 进一步恶化，形成正反馈循环。

------

 **2、重试：只有幂等查询才配拥有**

A2A 协议在消息层面提供了幂等的基础设施：`messageId` 可用于幂等任务创建，AgentCard 中的 `capabilities` 可以声明 `idempotent` 支持，表示远程 Agent 会对重复的 `messageId` 去重。但**协议提供能力不等于默认安全**。

**写操作的重试必须携带幂等键**，且键应由上游统一生成并跨跳传递，而不是每次重试时新生成。重试时应**复用同一个幂等键**，并在重试前查询远程任务状态，而不是盲目重发。对于非幂等的写操作，自动重试应当**默认关闭**——重复改签、重复出款这类副作用在跨进程边界上没有“回滚”按钮。

如果远程 Agent 的 AgentCard 没有声明幂等支持，且调用语义不是只读查询，那么**不重试**，直接走降级路径。

------

**3、降级：发现失败和调用失败需要不同的兜底**

降级策略应当按失败形态分开设计：

**发现失败**（Nacos 中找不到 Agent，或 `AgentCardProvider` 解析失败）：主流程应返回明确标注的错误，或切换到一个**本地兜底 Agent**。例如，知识 Agent 不可用时，主服务保留的本地知识库兜底回答，而不是让请求无限挂起。

**调用失败**（超时、连接失败、远程返回错误）：按实例维度统计失败率，超过阈值后**快速失败并对该实例做短时熔断**，同时返回明确标注来源的降级答案。熔断的作用不只是保护调用方，也避免在远程 Agent 已经过载时继续施加无效压力。

降级答案的**来源标注**很重要比如：“知识库更新中，以下回答基于本地缓存”。让调用方和终端用户都能区分正常结果和兜底结果。

------

**4、链路追踪：traceId 跨进程，但不止于 HTTP header**

Spring AI 通过 Micrometer Observation 在 `ChatClient` 和 Advisor 层自动产出 span，并导出到 Zipkin 或 Jaeger。跨进程传播依赖 W3C `traceparent` header 自动透传。但**仅靠 HTTP header 透传 traceId 是不够的**，A2A 场景下至少有三个容易断裂的点：

**线程池上下文丢失**。A2A 的服务端使用独立的 `a2aInternalExecutor` 线程池执行异步任务。如果不在 `TaskDecorator` 中显式传播 `traceId` 和业务上下文（如 `threadId`），异步执行阶段产生的日志和 span 会与主调用链断开，追踪链在远程 Agent 内部“断头”。

**业务标识需要跟随 traceId 一起跨进程**。`traceId` 回答“这次调用属于哪个请求”，但排查问题时还需要知道“是哪个 Agent、哪个会话、哪个 `threadId` 在处理”。推荐的做法是在入口过滤器生成 `traceId` 的同时，把 `threadId = projectId + ":" + sessionId` 一并写入 MDC，并通过 A2A 消息的元数据或自定义 header 透传到远程侧。日志模板应同时包含 `traceId`、`threadId` 和 `agentName`，三者缺一，事后定位就只能靠猜。

**`sessionId` 与 `traceId` 不是一回事**。一次用户会话（`sessionId`）可能包含多条独立链路（`traceId`）。工具索引等资源按 `sessionId` 组织，会话结束时必须 `evictSession`，否则长期运行后索引会积累无用引用。

------

**5、版本：Card 的 `version` 是契约声明，不是装饰字段**

Nacos 的 A2A Agent 规范中，标准身份是 `namespaceId → agent → agentName → version → protocol=a2a`，`version` 是一等维度。这意味着同一个 `agentName` 可以有多个版本并存，发现侧需要明确自己消费的是哪个版本。

**消费方锁定主版本**是最低成本的兼容策略。Spring AI Alibaba 自身采用四段版本号，前三段与 Spring AI 主版本对齐，第四段由社区迭代。在 AgentCard 层面，`protocolVersion` 用于快速过滤不兼容的 A2A 协议版本，`nativeDescriptor` 中保留完整的 AgentCard 字段。

一个务实的版本兼容策略是：**新增字段标记为可选并带默认值，而非必填**。提供方新增一个必填字段，旧版消费者会在解析阶段直接失败，协作在开始前就被阻断。版本协商的目的不是“总能调用成功”，而是**在不兼容时快速、可诊断地失败**，而不是静默产生错位结果。

这五个主题在跨服务 A2A 中共享同一个逻辑：**进程内的“默认正确”在边界上全部失效，每一项都必须被显式设计为可观测、可恢复、可诊断的契约**。超时分层造出可预期，幂等键造出可重试，熔断降级造出可收口，`traceId` + `threadId` + `agentName` 造出可追溯，版本锁定造出可演进。缺任何一项，系统在跑通之后的下一次网络抖动中就会暴露为不可诊断的故障。

## 3.案例：两个 Spring Boot，A 发现并调用 B

**目标：** 服务 B 把一个 `ReactAgent` 注册进 Nacos；服务 A 从 Nacos 发现它，通过 A2A 远程调用——A 的进程里没有任何模型依赖和 API Key。

### 3.1 拓扑与启动顺序

```text
                 Nacos :8848（+ 9848 gRPC）
                  ▲ registry              ▲ discovery + 订阅推送
                  │                       │
        服务B :8082                 服务A :8080
    data_analysis_agent          A2aRemoteAgent 代理
   （ReactAgent + dashscope）     （无模型、无 API Key）
                  ▲                       │
                  └──── JSON-RPC message/send ──── POST /a2a
```

启动顺序：**Nacos 最先起**。B 和 A 谁先谁后都行——A 是懒加载，B 晚起一会儿不影响 A 启动。

### 3.2 准备 Nacos

```bash
docker run -d \
  --name nacos-test \
  -p 8848:8848 \
  -p 9848:9848 \
  -p 8080:8080 \
  -e MODE=standalone \
  -e NACOS_AUTH_ENABLE=true \
  -e NACOS_AUTH_TOKEN=SecretKey012345678901234567890123456789012345678901234567890123456789 \
  -e NACOS_AUTH_IDENTITY_KEY=serverIdentity \
  -e NACOS_AUTH_IDENTITY_VALUE=security \
  nacos/nacos-server:v3.1.0
```

两个端口都要映射：**Nacos 2.x/3.x 客户端的数据通道是 9848 的 gRPC 长连接**，只映射 8848 时注册可能表面成功，发现侧却拉不到 Card——「控制台里看得到，代码里查不到」。另外 **Nacos 3.1.0** 版本才首次引入原生支持 A2A AgentCard 模型。

### 3.3 服务 B（提供者）

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-a2a-nacos</artifactId>
    <version>1.1.2.2</version>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
</dependency>
```

`application.yml`：

```yaml
server:
  port: 8082

spring:
  application:
    name: a2a-provider
  ai:
    dashscope:
      api-key: ${AI_DASHSCOPE_API_KEY}
    alibaba:
      a2a:
        server:
          version: 1.0.0
          card:
            name: data_analysis_agent    # 必须与 ReactAgent.name() 逐字符一致
            description: 专门用于数据分析和统计计算的智能体
            provider:
              name: saa-learning
              organization: stage09
              url: http://ai.kmind.space  # 必填：提供者组织的网站/信息页 URL
        nacos:
          server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
          registry:
            enabled: true   # 默认就是 true；显式写出是为了让「我是提供者」一眼可见
          discovery:
            enabled: false   # B 不消费别人，关掉
          username: nacos
          password: nacos
```

Agent 本体是个再普通不过的 `ReactAgent`，对 A2A **零感知**——被远程调用这件事，全部发生在 yml 和自动配置里：

```java
package space.kmind.zk.springaialibabalearning.stage09.provider;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供者：一个再普通不过的 ReactAgent。
 * 它不知道自己会被远程调用——A2A 的接入全部发生在 yml 和自动配置里。
 */
@Configuration
public class A2aProviderConfig {

    /**
     * 约束一：name 必须与 spring.ai.alibaba.a2a.server.card.name 逐字符一致。
     * 约束二：整个进程只能有一个 Agent Bean——
     *        自动配置按类型注入单个 Agent 来生成名片和执行器，
     *        多一个就 NoUniqueBeanDefinitionException，启动即失败。
     *        要暴露多个 Agent，请起多个实例（见本节末），不要塞进同一个进程。
     */
    @Bean
    public ReactAgent dataAnalysisAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("data_analysis_agent")
                .model(chatModel)
                .description("专门用于数据分析和统计计算的智能体")
                .instruction("""
                        你是数据分析专家。用户会给出一组数字，你做同比/环比等简明分析。
                        只基于给定数字作答，缺的数据明确说缺，禁止编造。
                        """)
                .build();
    }
}
```

启动后自动配置做三件事：生成 AgentCard（Bean 信息 + yml 的 card 配置合并）→ 注册进 Nacos（B 的日志里能看到 `Register agent card data_analysis_agent to Nacos namespace public successfully`）→ 暴露两个端点：`GET /.well-known/agent.json` 和 `POST /a2a`。

自测名片：

```bash
curl -s http://localhost:8082/.well-known/agent.json | python -m json.tool
```

重点看三个字段：`name`（跨服务唯一身份）；`url`（**这就是 A 将要打的地址**，由本机 IP + 8082 + `/a2a` 拼出来）；`capabilities.streaming`（决定 A 走同步还是流式——A 侧 `build()` 会以 Card 声明为准，而不是 A 自己说了算）。

### 3.4 服务 A（消费者）

依赖——**只有一个**：

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-a2a-nacos</artifactId>
    <version>1.1.2.2</version>
</dependency>
```

注意 pom 里没有 dashscope、没有任何模型 starter，yml 里也没有 api-key：**A 进程零模型成本**，它是纯粹的「发现 + 转发 + 编排」。这也是正文第 2 节「按需伸缩」最直观的证明：B 的模型账单和 A 无关，A 可以水平扩到任意多个实例。

`application.yml`：

```yaml
server:
  port: 8080

spring:
  application:
    name: a2a-consumer
  ai:
    alibaba:
      a2a:
        nacos:
          server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
          registry:
            enabled: false     # A 不对外提供 Agent，不注册
          discovery:
            enabled: true      # 只当消费者
```

第一件事：**别在启动期 build，也别每个请求 build。** 两个原因，都来自源码：

- `A2aRemoteAgent.build()` 的最后一步就是调 `agentCardProvider.getAgentCard(name)` 去 Nacos 解析 Card——B 还没注册时这一步直接抛异常。启动期 build 等于把「B 必须先于 A 存活」变成硬约束，两个服务的启动顺序从此锁死。
- `getAgentCard(name)` 内部会 `subscribeAgentCard` **向 Nacos 挂一个 Card 订阅监听器**。每个请求 build 一次，监听器就积累一个，跑几天就是一个缓慢泄漏。

正确姿势是懒加载单例：

```java
package space.kmind.zk.springaialibabalearning.stage09.consumer;

import com.alibaba.cloud.ai.graph.agent.a2a.A2aRemoteAgent;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 懒加载单例：A2aRemoteAgent 全进程一份。
 * build() 时解析 Card 并订阅变更——之后 B 换了地址重新注册，
 * Nacos 会把新 Card 推过来，代理对象内部跟着更新，不需要重建。
 */
@Component
public class RemoteAnalysisAgentHolder {

    private final AgentCardProvider agentCardProvider;

    private volatile A2aRemoteAgent remote;

    public RemoteAnalysisAgentHolder(
            // 存在多个 AgentCardProvider 实现时按名注入，正文 2.2 注意事项第二点
            @Qualifier("nacosAgentCardProvider") AgentCardProvider agentCardProvider) {
        this.agentCardProvider = agentCardProvider;
    }

    public A2aRemoteAgent get() {
        A2aRemoteAgent local = this.remote;
        if (local == null) {
            synchronized (this) {
                if (this.remote == null) {
                    this.remote = build();
                }
                local = this.remote;
            }
        }
        return local;
    }

    private A2aRemoteAgent build() {
        return A2aRemoteAgent.builder()
                .name("data_analysis_agent")      // 与 B 注册的 card.name 一致
                .description("远程数据分析智能体")   // build() 校验要求非空
                .instruction("{input}")
            	.shareState(false)   // 在这里设置：false 隔离状态，true 共享状态
                .agentCardProvider(agentCardProvider)
                .build();
    }
}
```

​	.instruction("{input}")这里，`A2aRemoteAgent` 本质上是一个图节点（Graph Node），而 Spring AI Alibaba 的图节点设计是：**入参只有 `OverAllState`（或 `OverAllState` + `RunnableConfig`），没有额外的“直接传参”入口**。节点的执行逻辑是固定的：从 State 里读取数据，处理后写回 State。

当你调用 `remote.invoke("文本")` 时，框架做的事情是把这个文本放进 State 的 `input` key。真正决定“发送什么给远程 Agent”的，是 Builder 阶段配置的 `instruction` 模板。如果不在 Builder 里配置 `instruction`，且 `shareState` 为 `false`（或 State 里没有 `messages`），节点在执行时会直接抛出 `IllegalStateException`，因为它找不到任何可发送的内容。所以 `instruction` 不是“可选的提示词”，而是**节点能否正常运行的必要条件**。它告诉框架“从 State 的哪个位置取数据来渲染成最终发送的文本”。

​	远程 B 处理完后，返回的文本不会自动变成一条新消息塞进对话历史。框架会把 B 的响应文本写入**调用方 state 的 `output` key 下**（这是默认的 `outputKey` 值）。这意味着：

- 你取远程结果时，要从 `state.value("output")` 拿。
- 如果 B 哪天改了返回结构，你的代码**不会编译报错**，只会运行到取结果时拿到空值或意料之外的内容。这就是“隐性契约依赖”——代码层面看不出这个依赖，但运行时一旦不匹配就会静默出问题。

#### 注意数据泄露风险：

`shareState = true` 时，A2A 节点会尝试与远程 Agent 共享状态上下文。**你的本地 state 里有什么，就可能被序列化后发到远程**。

当前这个案例里，state 只有 `messages` 和 `input`，确实没什么敏感的。但是一旦把这个 `A2aRemoteAgent` 嵌进一个更大的图里（比如当 Supervisor 的子节点），父图的 state 里可能包含用户会话历史、内部中间变量、甚至其他工具的返回数据。这些内容都会被默认带出去，发给远程 B。

所以建议是：**要么敏感字段根本不进 state，要么显式设置 `shareState(false)`** 来隔离状态，只传必要的最小信息。

调用：

```java
package space.kmind.zk.springaialibabalearning.stage09.consumer;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.a2a.A2aRemoteAgent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/s91")
public class A2aCallerController {

    private final RemoteAnalysisAgentHolder holder;

    public A2aCallerController(RemoteAnalysisAgentHolder holder) {
        this.holder = holder;
    }

    @PostMapping("/invoke")
    public Map<String, Object> invoke(@RequestBody InvokeReq req) {
        String q = (req.q() == null || req.q().isBlank())
                ? "Q1 营收 100，Q2 营收 120，请做环比简述" : req.q();

        A2aRemoteAgent agent;
        try {
            agent = holder.get();      // 发现失败在这：Nacos 里查不到这个 Agent
        } catch (Exception e) {
            return Map.of("status", "AGENT_NOT_FOUND",
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(req.threadId() == null ? "s91-remote-1" : req.threadId())
                    .build();
            Optional<OverAllState> state = agent.invoke(q, config);

            // 远程回答在 outputKey（默认 "output"）下
            String answer = state.map(s -> s.value("output", "")).orElse("");
            return Map.of("status", answer.isBlank() ? "EMPTY" : "OK", "answer", answer);
        } catch (Exception e) {
            // 调用失败在这：B 挂了 / 超时 / 返回错误
            return Map.of("status", "AGENT_UNREACHABLE",
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public record InvokeReq(String q, String threadId) {}
}
```

### 3.5 验证：五步

**第一步，B 的名片。** 4.3 的 curl 已经验过：`url` 就是 A 的目标地址。

**第二步，Nacos 控制台「Agent 管理」。** 能看到 `data_analysis_agent`。

**第三步，正常链路。**

```bash
curl -X POST http://localhost:8080/api/s91/invoke \
  -H 'Content-Type: application/json' \
  -d '{"q":"Q1 营收 100，Q2 营收 120，请做环比简述"}'
```

期望：

```json
{"status":"OK","answer":"环比增长 20%……"}
```

**第四步，杀掉 B。** 再发同样的请求：

```json
{"status":"AGENT_UNREACHABLE","error":"...ConnectException..."}
```

这个步骤验收三件事：返回的是**结构化错误**，不是一屏 500 堆栈；A 不需要重启（单例早就建好，失败的只是这一次调用）；B 重启后（端口不变）A 不改任何代码直接恢复。如果 B 换了端口重启——Nacos 会把新 Card 推给 A 的订阅，A 照样跟上，这就是 4.4 懒加载单例里那段注释的依据。

**第五步，模拟发现失败。** 把 `RemoteAnalysisAgentHolder` 里的 name 改成 `data_analysis_agent_x`，重启 A，再调：

```json
{"status":"AGENT_NOT_FOUND","error":"NacosRuntimeException: ..."}
```

注意发现失败只发生在**首次 build**——单例建好之后再改 Nacos 里的注册情况，失败形态会变成调用失败。两种失败分开返回，前端和运维才能分开处置：AGENT_NOT_FOUND 该找注册中心/部署，AGENT_UNREACHABLE 该找网络/实例。

### 3.6 这个 Demo 距离生产还差什么

| 主题 | Demo 现状 | 生产要做 |
| --- | --- | --- |
| 超时 | 没设任何调用超时 | 连接 / 读 / 任务总时长三层拆开，读超时 ≥ 远程 P99 |
| 重试 | 失败直接返回结构化错误 | 只对幂等查询做有界重试；写操作带幂等键 |
| 熔断降级 | 每次失败都实打实打一次远程 | 按实例统计失败率，超阈值短时熔断 |
| 追踪 | 无 | `traceId + threadId + agentName` 三个都进日志（正文 2.4 第 4 点） |
| 版本 | 默认拿 latest 版 Card | 消费侧锁版本区间，不兼容时快速失败 |


## 下一篇预告

清单下一阶段是 **多模态与语音 Agent**：图片理解、工具触发生成，以及 STT → Agent → TTS 的实时语音三明治。

---

## 官方参考

- spring-ai-alibaba：`examples/.../advanced/a2a/README.md`  
- https://github.com/nacos-group/nacos-spring-ai-alibaba-example  
- https://nacos.io/docs/latest/manual/user/ai/agent-registry/  

