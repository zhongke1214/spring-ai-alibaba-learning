# 别让 Agent 重启就失忆：Checkpoint、压缩上下文、业务字段分离设计实战

> 版本基准：**Spring AI Alibaba 1.1.2.x + Spring AI 1.1.2 + JDK 17+**（本文所有 API 以此版本为准）
>
> - 官方文档：https://java2ai.com
> - 记忆教程：https://java2ai.com/docs/frameworks/agent-framework/tutorials/memory
> - Agent 教程：https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents
> - 官方主仓库：https://github.com/alibaba/spring-ai-alibaba
> - 官方示例仓库：https://github.com/spring-ai-alibaba/examples
>
> 版本跟仓库约定对齐：JDK 17+ / Spring Boot 3.5.7 / Spring AI 1.1.2 / Spring AI Alibaba 1.1.2.2

上一篇把 ReactAgent 跑通了：能调工具、能多轮对话、有调用上限。但只要你重启一次服务，就会发现之前聊过的东西**全忘了**。本篇就解决这一件事，拆成四个问题讲清楚：

**记忆存在哪、状态怎么设计、会话怎么隔离、上下文太长怎么办。**

依赖骨架沿用 ReactAgent 篇（同样的 BOM + dashscope + agent-framework），只是 Redis 持久化演示要再加一个 Redisson：

```xml
<!-- 与上一篇相同的 BOM + dashscope + agent-framework -->

<!-- 生产级 Checkpoint 演示：Redis（任选 Redis / DB） -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.45.1</version>
</dependency>
```

本机没有 Redis 也别急，先用 `FileSystemSaver` / `MysqlSaver` 一样能验证"重启还能续聊"——**换 Saver 而已，Agent 的写法几乎不动**，这是这套设计最舒服的地方。

配置文件：

```yaml
spring:
  ai:
    dashscope:
      api-key: ${AI_DASHSCOPE_API_KEY}

# Redis
spring.data.redis:
  host: 127.0.0.1
  port: 6379
```

---

## 一、先把两套"记忆"分清楚：它们不在同一层

Spring AI Alibaba 里有两个东西都能实现"记住之前聊过什么"，名字也像，但层级完全不同。第一次接触时几乎人人混淆，先花两分钟掰开。

可以借游戏的**存档**来理解区别：

- **`ChatMemory` + `MessageChatMemoryAdvisor`** 相当于一个**聊天记录本**：只记"谁说了什么"。每次调模型前，它把历史对话（`messages`）从存储里取出来塞进本次请求的 Prompt，让模型"记得"前面聊过啥。它存的是一张**扁平的消息表**，就这么简单。

- **`Checkpoint Saver`** 相当于**游戏存档点**：它不只记对话，还记"你现在站在哪个地图、背包里有什么、进行到哪个任务、下一步该触发什么"。它位于 Agent Framework 的 **Graph 执行引擎层**，在图执行的**每个超级步骤（super-step）之后**，把整个图的运行时状态打包成一个检查点（Checkpoint）存起来。存的是一个**完整的运行时快照**，封装在 `OverAllState` 对象里——里面既有 `messages`，也有"执行到了哪个节点""下一步做什么""你自定义的业务字段"。

**那到底该用哪个？** 判断标准就一条：你用的是不是 Agent 图。

- 如果你只是**简单、无状态的模型调用**（比如直接 `ChatClient` 一问一答），只需要记住对话历史，`ChatMemory` + `MessageChatMemoryAdvisor` 就够了，轻且直观；
- 一旦你用上了 **`ReactAgent`** 或者在搭**复杂 Graph 工作流**，就该用 `Checkpoint Saver`。因为 `ReactAgent` 的内部本身就是一个图执行过程（模型节点 → 工具节点 → 循环判断），只有 Checkpoint 才能保证这个过程可中断、可恢复。

在 `ReactAgent` 的语境下，**`Checkpoint Saver` 是更底层、更全面的那个**：它保存的 `OverAllState` **已经包含了 `messages`**，功能上直接覆盖了 ChatMemory 的记忆能力，还额外带走了执行图的状态。所以 Agent 场景里，它就是实现"短期记忆"的推荐方式——存储的东西更丰富，不限于聊天消息。

正因为存的是完整运行时快照，它才能支撑那些 ChatMemory 根本做不到的能力：**ReAct Agent 的多轮循环、Human-in-the-Loop 的人工中断与恢复、时间旅行（回看/回退到某个历史步骤）、容错（进程崩了重启后接着跑）**。这些都是下一章和下一篇的主角，它们的地基就是今天要讲的 Checkpoint。

---

## 二、"重启就失忆"的真正原因

上一篇里我们写过这么一行：

```java
.saver(new MemorySaver())
```

`MemorySaver` 做的事非常朴素：在 **JVM 堆里的一个 Map**，以 `threadId` 为键存每个会话的 Checkpoint。进程活着，同一个 `threadId` 就能续聊；进程一死，堆内存清空，Map 跟着消失——记忆归零。

所以它不是"有问题"，而是**开发期的默认实现**：快、零依赖、写 Demo 和单测最省事。但它注定进不了生产。

生产要换的是实现了同一套 `BaseCheckpointSaver` 契约的持久化实现。这套契约只有四个操作，理解它们就理解了整个持久化机制：

| 操作 | 作用 |
| --- | --- |
| `put` | 写入 / 更新某次 Checkpoint |
| `get` | 取最新，或按 `checkpointId` 取指定快照 |
| `list` | 列出该 thread 的历史 Checkpoint |
| `release` | 释放该 thread 相关 Checkpoint（清理会话） |

注意 `get` 支持"按 `checkpointId` 取指定快照"——这就是"时间旅行"的入口：你不只能拿到最新状态，还能拿到历史上任何一个检查点的状态。人工审批中断后恢复、出问题回溯现场，都靠它。

---

## 三、换成持久化 Saver：换的只是介质，不是写法

**更换 Saver 的方式永远一样**：把 Saver 构建成 Bean，塞进 `.saver(...)`。Agent 的其他代码一行都不用改。

| Saver | 介质 | 适合 |
| --- | --- | --- |
| `MemorySaver` | 内存 | 开发、单测、临时 Demo |
| `RedisSaver` | Redis | 高并发、多实例、分布式会话 |
| `MysqlSaver` / `PostgresSaver` / `OracleSaver` | 关系库 | 强一致、要和业务库同事务边界、要审计 |
| `FileSystemSaver` | 本地文件 | 单机、零中间件先把"重启续聊"跑通 |
| `MongoSaver` 等 | 文档库 | Schema 灵活的状态文档 |

选型的实际考量就两点：**多实例要不要共享**（要共享 → Redis 或数据库，本地文件在容器里等于没有）、**要不要和业务库同事务/审计**（要 → 关系库）。

### 3.1 Redis 实现方式

官方记忆文档的生产示例用的就是 Redis Checkpointer：

```java
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import org.redisson.api.RedissonClient;

@Bean
public RedisSaver redisSaver(RedissonClient redissonClient) {
    return RedisSaver.builder()
            .redisson(redissonClient)
            .build();
}

ReactAgent agent = ReactAgent.builder()
        .name("prod_memory_agent")
        .model(chatModel)
        .saver(redisSaver)   // 替换 MemorySaver
        .build();
```

### 3.2 MySQL 实现方式

```java
@Bean
public MysqlSaver mysqlSaver(DataSource dataSource) {
    return MysqlSaver.builder()
            .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
            .dataSource(dataSource)
            .build();
}
```

`CREATE_IF_NOT_EXISTS` 会帮你自动建表，第一次接入省事；生产环境更稳妥的做法是提前把建表语句纳入 Flyway/Liquibase 的版本管理，别让应用启动时偷偷 DDL。

### 3.3 FileSystem 实现方式

Checkpoint 落在磁盘目录里，进程挂了文件还在，重启照样续聊——**适合本机先把流程验证一遍**，不用装任何中间件。

但要注意：生产多实例部署时，每个容器的本地盘是各自的，请求打到不同实例就会"时灵时不灵"。除非你把目录挂成共享卷（NFS 之类），否则别在集群环境里指望它。

---

## 四、`threadId`：会话的一级分区键

换完 Saver，还有一半的"失忆"事故跟存储无关，而是 `threadId` 用错了。

`threadId` 是 Checkpoint 的**一级分区键**，可以把它理解成"会话文件夹名"：

```text
saver/
  ├── thread-t-001/   ← 用户 A 的客服会话
  ├── thread-t-002/   ← 用户 A 的另一个话题
  └── thread-t-003/   ← 用户 B 的会话
```

不同文件夹之间完全隔离：换一个 `threadId`，就是另一个世界，之前的记忆一概看不到。

| 场景 | 怎么设 threadId |
| --- | --- |
| 同一用户同一客服窗口连续聊 | **固定**同一个 threadId |
| 用户点「新对话」 | **生成新** threadId |
| 多轮里偶尔丢上下文 | 先查前端是否每轮换了 id，再查 saver |

最后一条是实战里最高频的坑：用户反馈"聊着聊着就忘了"，八成不是 Saver 的问题，而是前端每轮请求都重新生成了一个 id。排查顺序永远是**先查前端 id，再查存储**——因为前者一秒钟就能验证，后者要翻半天日志。

`RunnableConfig` 里还能再带一个 `checkpointId`，用来从**某一个历史快照**恢复（人机审批中断后续跑会用到）。日常续聊一般只传 `threadId`，框架默认取该 thread **最新**的 Checkpoint。

---

## 五、上下文太长怎么办：修剪、删除、摘要

短期记忆最常见的形态就是"把历史 messages 全塞进状态"。对话一长，三个问题接踵而来：

1. **触达模型上下文上限** → 直接报错，或者被框架悄悄截断；
2. **没超限但被旧内容带偏** → 模型注意力被稀释，答非所问；
3. **Token 账单起飞** → 底座篇讲过的"每轮复读全部历史"税，聊得越久越贵。

官方把这类手段归到"上下文工程"。ReactAgent 侧常用的三种：

| 策略 | 做法 | 优点 | 风险 |
| --- | --- | --- | --- |
| **修剪（Trimming）** | 调用模型前丢掉中间旧消息，保留首条 + 最近 N 条 | 实现简单、成本低 | 丢掉的细节找不回来 |
| **删除（Deletion）** | 永久从状态里删掉部分/全部消息 | 立刻降压 | 删坏 tool 配对会让部分厂商 API 报错 |
| **摘要（Summarization）** | 旧消息交给便宜模型总结，用摘要替换长历史 | 保留事实线索 | 多一次模型调用；摘要本身也可能丢细节 |

三者不是互斥的，实际系统常常是"**到一定长度先摘要，超硬上限再修剪**"。摘要保质量，修剪保底线。

### 5.1 修剪怎么落地：`MessagesModelHook` + `REPLACE`

推荐的做法是扩展 `MessagesModelHook`，在 `BEFORE_MODEL` 阶段改消息列表，并用 **`UpdatePolicy.REPLACE`** 把新列表写回状态：

```java
@HookPositions({HookPosition.BEFORE_MODEL})
public class MessageTrimmingHook extends MessagesModelHook {

    private static final int MAX_MESSAGES = 8;

    @Override
    public String getName() {
        return "message_trimming";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        if (previousMessages.size() <= MAX_MESSAGES) {
            return new AgentCommand(previousMessages);
        }
        Message first = previousMessages.get(0);
        List<Message> recent = previousMessages.subList(
                previousMessages.size() - (MAX_MESSAGES - 1),
                previousMessages.size());
        List<Message> trimmed = new ArrayList<>();
        trimmed.add(first);
        trimmed.addAll(recent);
        return new AgentCommand(trimmed, UpdatePolicy.REPLACE);
    }
}
```

这段代码的意图是"保留首条 + 最近 N 条"：首条通常是用户的第一句需求或系统设定，丢了可惜；最近 N 条是当前语境。中间那段"聊过就忘"的过渡内容，是性价比最低的部分。

**摘要的思路类似，只是多一步模型调用**：估算 token（或按条数）超过阈值 → 把旧段发给 `summaryModel`（一般配个更便宜的小模型）→ 生成一条 `SystemMessage("## 之前对话摘要:...")` + 保留最近 N 条 → 用 `REPLACE` 写回。框架后续版本也有内置 `SummarizationHook` 一类的能力，原理完全一样：**先压缩，再推理**。

### 5.2 裁剪的两条铁律：剪坏结构比不剪更糟

这里有个几乎所有人都踩过的坑，而且它不会报"你的裁剪逻辑有 bug"，只会报一个看起来莫名其妙的 API 错误。

**铁律一：`messages[0]` 必须是 user 消息**

许多模型厂商（尤其 Anthropic Claude 系列，以及部分 OpenAI 兼容网关）对 `messages` 数组的结构有强校验：

```
messages[0].role 必须是 "user"
```

如果你裁剪时把开头的 `UserMessage` 删掉了，让列表变成以 `AssistantMessage` 或 `ToolResponseMessage` 开头，API 会**直接拒绝请求**。

最容易中招的是"按条数滑动窗口"的裁剪。比如窗口大小设为 20，它按"条数"裁而不是按"轮次"裁——聊久了就可能把最早那条 `UserMessage` 淘汰掉，留下一条孤零零的 `AssistantMessage` 挂在列表开头。

怎么避免：

1. **按轮次裁剪，不要按条数**。一轮 = `UserMessage` + `AssistantMessage`（+ 可能的 `ToolResponseMessage`），删就删整轮；
2. 自己实现裁剪逻辑时，裁完检查一次 `messages.get(0).getMessageType() == USER`，不是就继续往前删直到满足；
3. 用框架自带的 memory 时，先确认它的窗口策略是"保留最近 N 轮"还是"最近 N 条"——**前者安全，后者容易踩坑**。

**铁律二：`tool_calls` 和它的响应是原子配对，不能拆**

这条比第一条更常见。几乎所有支持 function calling 的厂商（OpenAI、Anthropic、DashScope 等）都强制要求：

```
AssistantMessage(toolCalls=[call_abc])
    → 必须紧跟 ToolResponseMessage(id=call_abc)
```

这两条是**原子配对**的。如果你删掉了 `ToolResponseMessage`，只留下带 `toolCalls` 的 `AssistantMessage`，API 会报：

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

**更隐蔽的变体**：一轮里模型同时调了多个工具——

```
AssistantMessage(toolCalls=[call_1, call_2, call_3])
ToolResponseMessage(id=call_1)
ToolResponseMessage(id=call_2)   ← 这条被裁掉
ToolResponseMessage(id=call_3)
```

只删中间一条，照样报错：`tool_call_id call_2 not found`。

怎么避免：

1. **按"工具调用组"裁剪**：把 `AssistantMessage(toolCalls=[...])` 加上它后面所有的 `ToolResponseMessage` 视为一个**不可分割的原子块**，要删整块删，要么全留；
2. 自己实现的话，裁完做一次**配对校验**：遍历 messages，遇到带 `toolCalls` 的 assistant 就检查后面是否每个 `toolCallId` 都有对应响应，缺了就把这个 assistant 也一起删掉；
3. 用框架自带 memory 时，确认它有没有处理工具消息的配对——有些简单实现只按条数裁，不管配对。

一句话记住：**裁剪不是"删掉旧消息"这么简单，删完必须仍然是一条合法的消息序列。**

---

## 六、`OverAllState`：跨节点、跨轮的共享状态

上一篇把 `OverAllState` 比作"公共背包"，这里把它和记忆的关系讲透。一次请求的完整数据流转是这样的：

```text
用户请求 + threadId
        │
        ▼
  Saver.get(threadId) ──► 恢复 OverAllState（含 messages / 业务字段）
        │
        ▼
  Graph 节点读写状态（模型节点、工具节点、Hook）
        │
        ▼
  Saver.put(threadId, Checkpoint) ──► 落盘最新快照
```

记住这一句就够了：**一轮对话之内**，节点之间靠 OverAllState 传数据；**多轮对话之间**，靠 Checkpoint 把 OverAllState "冻住再解冻"。

### 6.1 读状态：带上默认值，别指望 key 一定在

状态管理的第一原则是：**读的时候带默认值防 NPE，写的时候看清这个 key 的策略**。前者决定你会不会半夜被空指针叫醒，后者决定新值和旧值是"覆盖"还是"追加"。

`OverAllState.value(key)` 返回 `Optional`；`value(key, defaultValue)` 直接带上默认值。多租户、跨会话场景里，key 缺失是常态而不是异常，所以**默认用带默认值的写法**：

```java
Optional<OverAllState> opt = agent.invoke(q, config);
//从一个可能为空的 Optional 容器中取出 OverAllState 对象；如果容器里没有值，就直接抛出异常，而不是返回 null
OverAllState state = opt.orElseThrow();

// 方式一：Optional 手动处理
Optional<Object> raw = state.value("user_city");
String city = raw.map(String::valueOf).orElse("");

// 方式二：直接给默认值（推荐）
List<Message> messages = state.value("messages", List.of());
String city = state.value("user_city", "");
boolean needHuman = state.value("need_human", false);
```

**要小心 `value` 的泛型推断。** `state.value("messages", List.of())` 返回的是 `List<Message>`，但如果你写成 `state.value("messages", new ArrayList<>())`，推断出来的可能是 `ArrayList<Object>`，后面遍历时就会 cast 失败。**默认值的类型必须和实际存储的类型对齐。**

顺带科普一下 `Optional`（读 Spring AI 源码时到处都是）：

`Optional<T>` 是 Java 8 引入的容器类，用来优雅地表示"一个值可能存在，也可能不存在"。它强迫调用方显式处理"值不存在"的情况，避免直接返回 `null` 导致后面某处突然 NPE。`orElseThrow()` 的无参版本行为是：

- `opt` 里有值 → 返回这个值；
- `opt` 是空的 → 抛 `NoSuchElementException`。

这样做的好处是**问题当场暴露**：状态意外缺失时程序立刻炸在取值那一行，而不是让 `state` 变成 `null` 然后躲到后面某个不相干的地方报 NPE，排查成本翻十倍。如果你能接受"状态不存在就返回默认值"，可以用 `orElse(new OverAllState())` 或 `orElseGet(...)`。

### 6.2 写状态：节点返回 Map，框架按 key 策略合并

Agent 图里的每个节点（包括 Hook）执行完后，返回一个 `Map<String, Object>`。框架**不会无脑覆盖整张状态表**，而是逐个 key 决定怎么合并：

```java
// 节点/Hook 返回
return Map.of(
        "user_city", "成都",
        "messages", List.of(new AssistantMessage("已记住"))
);
```

框架拿到这个 Map 后，对每个 key 做两件事：

1. **查这个 key 注册了什么策略**（`AppendStrategy` 还是 `ReplaceStrategy`）；
2. **按策略把新值合并进旧值**。

所以同一个 Map 里，`user_city` 和 `messages` 的合并行为可以完全不同——一个覆盖，一个追加。这是理解状态行为的关键。

| 策略 | 行为 | 典型 key |
| --- | --- | --- |
| **`AppendStrategy`（追加）** | 列表类追加，旧元素保留，新元素接在后面 | `messages` |
| **`ReplaceStrategy`（替换）** | 新值直接覆盖旧值，旧值丢弃 | `user_city`、`order_status`、`need_human` |

**`messages` 默认按追加语义管理。** ReactAgent 构建时框架内部已经为 `messages` 注册了 `AppendStrategy`，所以每轮调用历史消息会越积越多，而不是被新消息覆盖——这正是"多轮对话"能成立的原因。

**自定义业务字段默认是替换。** 你传入 `user_city=成都`，下一轮传 `user_city=上海`，状态里的值就是 `上海`，而不是 `[成都, 上海]`。这通常正是你想要的。

### 6.3 自定义 key 的策略注册

ReactAgent 场景下，`messages` 由框架管，你自定义的 key 默认走替换策略，一般够用。但如果你在 Graph 层自己构建 `StateGraph`，就得**显式为每个 key 注册策略**：

```java
KeyStrategyFactory factory = () -> {
    Map<String, KeyStrategy> map = new HashMap<>();
    map.put("messages", new AppendStrategy());       // 对话历史，追加
    map.put("user_city", new ReplaceStrategy());     // 业务标量，覆盖
    map.put("need_human", new ReplaceStrategy());
    map.put("tool_call_log", new AppendStrategy());  // 调用日志，追加
    return map;
};
```

**不注册的 key 会怎样？** 取决于框架版本的默认行为——有的版本默认用 `ReplaceStrategy`，有的版本直接报错。别赌，**自己注册的 key 就显式注册策略**。

---

## 七、本篇最重要的一节：把业务字段从 `messages` 里拿出来

把一切都塞进 `messages`、指望模型从自然语言历史里"挖"出关键信息，是 Agent 设计里最隐蔽的坑。正确的做法是**分类存放**：对话轨迹归对话轨迹，业务状态归业务状态，身份信息归身份信息，临时数据归临时数据。

第二节那个"我说了我在成都，第二轮它问我您在哪个城市"的案例，就是典型症状。全塞 messages 会有五个绕不开的问题：

- **messages 是线性的自然语言流**。"用户在成都"这个关键事实混在工具调用、中间推理、格式化输出里，模型注意力被稀释——它要在一堆文字里"注意到"这句话，凭什么？
- **messages 会被裁剪**。上下文超长时早期消息被窗口淘汰，"我在成都"直接人间蒸发。
- **messages 会被摘要**。摘要模型可能把"我在成都"压掉，或者压成一句模糊的表述。
- **工具无法直接读 messages**。工具只能通过入参拿数据，如果地点只存在于历史里，工具每次都得靠模型"回忆"并正确传参。
- **前端/审计无法结构化展示**。想显示"当前工单状态"，只能去解析整段对话——脆弱且昂贵。

### 7.1 四类数据，四个去处

#### 第一类：对话轨迹 → `messages`

user 说了什么、assistant 回了什么、调了哪个工具、工具返回了什么。

```
UserMessage: 成都天气怎么样？
AssistantMessage: toolCalls=[get_weather(city=成都)]
ToolResponseMessage: 晴，25℃
AssistantMessage: 成都今天晴，25℃。
```

**特征**：自然语言、线性、需要被模型"读到"、需要按轮次保留。
**管理方式**：框架自动追加（`AppendStrategy`），超长时做窗口裁剪 + 摘要。

#### 第二类：结构化业务态 → 独立 state key

从对话中提取出来、后续需要**反复使用**的事实和状态。

**特征**：结构化（String / Boolean / POJO）、跨轮次持久、工具和 Hook 直接读写、**不依赖模型回忆**。

| key | 类型 | 用途 |
| --- | --- | --- |
| `user_city` | String | 用户所在地，后续天气查询直接用 |
| `cart_id` | String | 当前购物车 ID |
| `ticket_status` | String | 工单状态：pending/resolved/escalated |
| `need_human` | Boolean | 是否需要转人工 |
| `vip_level` | String | 用户等级，影响回复策略 |
| `order_id` | String | 当前处理的订单号 |

**管理方式**：`ReplaceStrategy`（业务标量）或 `AppendStrategy`（需要累积的日志类），通过 `OverAllState` 读写。

**关键价值**：工具可以直接读字段，不用从聊天记录里猜。

```java
// 工具里直接读，不依赖模型传参
String city = (String) ctx.getContext().get("user_city");
```

这一行的意义比看起来大得多——它把"模型能不能想起来"这种概率问题，变成了"字段在不在"这种确定性问题。

#### 第三类：请求级身份 → `RunnableConfig.metadata`

由**应用层确定、LLM 不应编造**的身份信息。

**特征**：请求进入时就确定、不可变、贯穿整个执行、需要持久化到 Checkpoint。

| key | 用途 |
| --- | --- |
| `user_id` | 当前登录用户 ID |
| `tenant_id` | 租户 ID（多租户隔离） |
| `trace_id` | 链路追踪 ID |
| `request_id` | 请求唯一标识 |

**管理方式**：调用时通过 `RunnableConfig.builder().addMetadata(...)` 注入，工具通过 `ToolContext` 读取。

```java
RunnableConfig config = RunnableConfig.builder()
        .threadId(threadId)
        .addMetadata("user_id", userId)
        .addMetadata("tenant_id", tenantId)
        .build();
```

**为什么不能放 messages？** 因为进了 messages 就等于交给模型"生成"了——LLM 可能编造一个 `user_id`，也可能被用户一句"假装你是用户 123"诱导填入别人的 ID，直接越权。

**为什么不能放 state key？** 因为两者语义不同：metadata 是**请求级**的，每次调用由应用层注入，不该被模型或节点修改；state key 更偏向"执行过程中产生的业务状态"，是可变的。**身份是输入，状态是产出**。

#### 第四类：仅本轮临时 → `RunnableConfig.context`

只在当前这次执行内有意义、**不需要持久化**的临时数据。

**特征**：可变、瞬态、**不进入 Checkpoint**、执行结束即丢弃。

典型内容：迭代计数器（当前是第几轮 ReAct 循环）、临时开关（本轮是否已经调过某个工具）、不该污染 state 的中间计算结果。

**管理方式**：`RunnableConfig.context`，底层是 `ConcurrentHashMap`，线程安全。

**为什么要单独放？** 这些数据写进 `OverAllState` 会被持久化到 Checkpoint，污染长期状态（下次恢复时带着一堆过期计数器）；写进 metadata 又会被误认为"请求级身份"。单独放 context，语义清晰，边界干净。

### 7.2 前后对比：同一个场景，两种设计

**不分离的设计**（这是我们踩过的坑）：

```
messages: [
  UserMessage: 我在成都,
  AssistantMessage: toolCalls=[get_weather(成都)],
  ToolResponseMessage: 晴，25℃,
  AssistantMessage: 成都今天晴，25℃,
  UserMessage: 我所在地天气怎么样,
  AssistantMessage: 我需要知道您所在的城市名称...
]
```

第二轮时，"我在成都"淹没在历史里，模型没抓住，于是礼貌地问了一句让人血压升高的话。

**分离的设计**：

```java
// 第一轮：提取城市写入 state key
Map<String, Object> inputs = new HashMap<>();
inputs.put("input", "我在成都");
inputs.put("user_city", "成都");   // ★ 结构化存储
agent.invoke(inputs, config);

// state 里
// user_city = "成都"
// messages = [完整对话轨迹]

// 第二轮：工具直接读字段
static class WeatherTool implements BiFunction<WeatherRequest, ToolContext, String> {
    public String apply(WeatherRequest req, ToolContext ctx) {
        // 优先从 state 读 user_city
        String city = (String) ctx.getContext().get("user_city");
        if (city == null) {
            city = req.getCity();  // 兜底用模型传的参数
        }
        return queryWeather(city);
    }
}
```

**关键区别**：第二轮时"成都"不在 messages 里，而在 `user_city` 这个 state key 里。即使 messages 被裁剪、被摘要，`user_city` 依然稳稳地在那儿；工具直接读字段，彻底不依赖模型"回忆"。

注意这里还留了兜底：`city == null` 时才用模型传的参数。**提取成功走结构化，提取失败退回自然语言**——两条路都留着，系统才稳。

### 7.3 怎么自动提取业务字段

每轮都手动传 `user_city` 太笨，可以在 Hook 里自动从消息中提取：

```java
// 伪代码：在模型调用后的 Hook 里
public Map<String, Object> afterModel(OverAllState state, RunnableConfig config) {
    List<Message> messages = state.value("messages", List.of());
    // 从最新的 user 消息里提取城市
    String city = extractCity(messages);
    if (city != null) {
        return Map.of("user_city", city);   // 写入 state key
    }
    return Map.of();
}
```

提取手段可以三选一：规则（正则匹配城市名）、小模型（专门做一次抽取调用）、或者直接取工具返回的结构化字段。一旦写入 `user_city`，后续所有工具和 Hook 都能直接读——**一次提取，全程受益**。

---

## 八、会话与多租户：三个 id 各司其职

### 8.1 `tenantId` / `userId` / `conversationId`

多租户场景下这三个 id 一个都不能省，职责完全不同：

| 标识 | 含义 | 放哪 | 谁写入 |
| --- | --- | --- | --- |
| **tenantId** | 租户/企业 | metadata，或拼进 threadId 前缀 | 服务端（登录态） |
| **userId** | 终端用户 | metadata → ToolContext | 服务端（登录态） |
| **conversationId / threadId** | 一次会话线程 | `RunnableConfig.threadId` | 服务端生成，前端可持有 |

**为什么不能用一个字符串糊弄？** 三个理由：

1. 只用一个 `threadId`，多租户隔离、用户隔离、窗口隔离全糊在一起。要清会话时不知道该清哪条，查越权时不知道该查谁；
2. **生命周期不同**：`tenantId` 和 `userId` 跨会话不变，`conversationId` 每开一个新窗口就变一次；
3. **信任级别不同**：`tenantId` / `userId` 必须由服务端注入，绝不能信前端；`conversationId` 可以让前端持有并回传，但服务端要校验它确实属于当前用户。

推荐的 threadId 生成规则——把三段拼起来：

```text
{tenantId}:{userId}:{conversationId}

例：acme:u10086:c-20260409-01
```

这样设计带来三个实打实的好处：

| 场景 | 表现 |
| --- | --- |
| **同一用户多个窗口** | 不同 `conversationId`，各自独立历史，互不干扰 |
| **清会话** | `saver.release("acme:u10086:c-20260409-01")` 精确释放，或业务表软删除 |
| **串会话排查** | 直接看 threadId 里的三个字段是否匹配，一秒定位是前端复用了 conversationId，还是服务端 userId 注入错了 |

最后一条在工程上特别值钱：当用户报告"我看到别人的对话"时，你不用翻半天日志，看一眼 threadId 的三个字段就能判断是哪一环串了。

**清会话的两种方式**：

```java
// 方式一：直接释放 saver 里的检查点
saver.release(threadId);

// 方式二：业务表软删，同时释放 saver
conversationService.softDelete(conversationId);
saver.release(threadId);
```

生产环境建议**两种都做**：业务表存会话元数据（标题、创建时间、归属用户），saver 存实际状态。清会话时先软删业务记录，再释放 saver。

### 8.2 三条隔离红线

**红线一：绝不能所有用户共用一个默认 threadId。**
如果你不传 `threadId`，框架会用默认值——于是所有用户的对话历史全混进同一条会话里，A 用户的订单可能出现在 B 用户的上下文里。**这是最严重的越权漏洞**，而且它不会报错，只会偶尔"神奇地"泄露数据。

**红线二：服务端根据登录态写入 userId / tenantId，不要信任模型或前端。**
前端传 `userId=123` 不代表当前登录用户就是 123。服务端必须从**登录态 / Session / Token** 里取，而不是从请求参数里取。

**红线三：Tool 内读身份只从 ToolContext / metadata 来。**
工具查订单时，`userId` 必须从 `ToolContext` 读，不能从模型传的参数读——理由和 7.1 节第三类数据一样：模型传的参数是不可信输入。

### 8.3 敏感信息：不进 Prompt、不进 messages、谨慎进 Checkpoint

Checkpoint 会把状态**序列化持久化**。进了 messages / state / metadata 的东西，就等于进了 Redis / DB / 磁盘——会被长期保存、可能被备份、可能被日志打印出来。

| 类别 | 例子 | 建议 |
| --- | --- | --- |
| **密钥** | API Key、密码、Token | 环境变量/密钥托管；Tool 内临时用，不进任何上下文 |
| **强隐私** | 身份证、银行卡完整号 | 脱敏，记忆里只留尾号 |
| **过量 PII** | 手机号、住址全文 | 最小化；日志与记忆分级 |
| **他人数据** | 别的 userId 的订单 | 工具层鉴权 |

还有一条必须强调：**你不能写一句 `systemPrompt("不要泄露其他用户的订单")` 就指望模型守规矩。** 模型可能被提示词注入诱导，也可能单纯出错。真正的鉴权必须在工具内部做：

```java
public String apply(OrderRequest req, ToolContext ctx) {
    String userId = (String) ctx.getContext().get("user_id");
    Order order = orderService.getById(req.getOrderId());
    
    // 工具层鉴权：确认订单属于当前用户
    if (!order.getUserId().equals(userId)) {
        return errorJson("FORBIDDEN", "无权查看该订单");
    }
    return okJson(order.toJson());
}
```

把这句话当作安全设计的基本原则：**模型负责意图，工具负责权限。** 任何涉及"这个数据他能不能看"的判断，都不该交给模型。

---

## 九、动手验证：Redis 续聊 + 状态查看 + 修剪

### 9.1 配置 RedisSaver

```java
package space.kmind.zk.springaialibabalearning.stage03;


import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author zk
 * @date 2026/10/14 15:04
 */
@Configuration
public class CheckpointConfig {

    @Bean
    public RedisSaver redisSaver(RedissonClient redissonClient) {
        return RedisSaver.builder()
                .redisson(redissonClient)
                .build();
    }
}

```

### 9.2 Agent 接口

```java
package space.kmind.zk.springaialibabalearning.stage03;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
/**
 * @author zk
 * @date 2026/10/14 15:05
 */

/**
 * 阶段 3：记忆 / Checkpoint / 状态
 * <p>
 */
@Tag(name = "Stage03-记忆与 Checkpoint", description = "持久化续聊 / 状态 / 修剪")
@RestController
public class AMemoryCheckpointDemo {

    private final ReactAgent agent;
    private final BaseCheckpointSaver saver;

    public AMemoryCheckpointDemo(ChatModel chatModel, RedisSaver redisSaver) throws Exception {
        this.saver = redisSaver;

        this.agent = ReactAgent.builder()
                .name("checkpoint_agent")
                .model(chatModel)
                .systemPrompt("""
                        你是会话助手。
                        当用户提到姓名或所在城市时，要记住并在后续问答中使用。
                        回答简洁；不要编造用户没说过的个人信息。
                        """)
                .hooks(new MessageTrimmingHook())
                .saver(redisSaver) // 可换成 MysqlSaver / FileSystemSaver
                .build();
    }

    @Operation(summary = "续聊：同一 threadId 记住上下文（重启后也应可用）")
    @GetMapping("/api/s41/chat")
    public String chat(
            @RequestParam(defaultValue = "我叫张三，在成都") String q,
            @RequestParam(defaultValue = "acme:u10086:c-001") String threadId,
            @RequestParam(defaultValue = "u10086") String userId,
            @RequestParam(defaultValue = "acme") String tenantId) throws Exception {

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("user_id", userId)
                .addMetadata("tenant_id", tenantId)
                .build();

        return agent.call(q, config).getText();
    }

    @Operation(summary = "查看 OverAllState.messages")
    @GetMapping("/api/s42/state")
    public String state(
            @RequestParam(defaultValue = "再确认一下我是谁") String q,
            @RequestParam(defaultValue = "acme:u10086:c-001") String threadId) throws Exception {

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        Optional<OverAllState> opt = agent.invoke(q, config);
        if (opt.isEmpty()) {
            return "empty state";
        }
        List<Message> messages = opt.get().value("messages", List.of());
        return "messageCount=" + messages.size() + "\n" + messages;
    }

    @Operation(summary = "释放会话 Checkpoint（新对话前可调用）")
    @GetMapping("/api/s43/release")
    public String release(@RequestParam(defaultValue = "acme:u10086:c-001") String threadId)
            throws Exception {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        BaseCheckpointSaver.Tag tag = saver.release(config);
        return "released threadId=" + tag.threadId()
                + ", checkpointCount=" + tag.checkpoints().size();
    }

    /**
     * 教学用修剪：消息过多时保留首条 + 最近若干条。
     * 生产请按 token 估算，并避免拆坏 tool 调用对。
     */
    @HookPositions({HookPosition.BEFORE_MODEL})
    static class MessageTrimmingHook extends MessagesModelHook {

        private static final int MAX_MESSAGES = 12;

        @Override
        public String getName() {
            return "message_trimming";
        }

        @Override
        public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
            if (previousMessages.size() <= MAX_MESSAGES) {
                return new AgentCommand(previousMessages);
            }
            Message first = previousMessages.get(0);
            List<Message> recent = previousMessages.subList(
                    previousMessages.size() - (MAX_MESSAGES - 1),
                    previousMessages.size());
            List<Message> trimmed = new ArrayList<>();
            trimmed.add(first);
            trimmed.addAll(recent);
            return new AgentCommand(trimmed, UpdatePolicy.REPLACE);
        }
    }
}

```

### 9.3 自测顺序

按这个顺序跑一遍，每一步都在验证一个明确的知识点：

1. `GET /api/s41/chat?q=我叫张三，在杭州&threadId=acme:u10086:c-001`
2. `GET /api/s41/chat?q=我叫什么？在哪个城市？&threadId=acme:u10086:c-001` → **应答上**（第一步的记忆生效）
3. **重启应用**
4. 重复步骤 2 → **仍应答上**（这一步是本篇的核心：`MemorySaver` 会失败，持久化 Saver 应该成功）
5. 换 `threadId=acme:u10086:c-002` 再问「我叫什么」→ **应不知道**（验证 threadId 的分区隔离）
6. `/api/s42/state` 看 messages 是否在增长；多聊几轮观察 trimming 是否触发（消息数应该被压在上限附近，而不是无限涨）
7. `/api/s43/release` 后同一 thread 再问个人信息 → **应清空**（验证会话释放）

第 4 步是分水岭。它通过了，说明你的记忆真正落到了进程之外；它没通过，先别怀疑模型——检查 Saver Bean 有没有真的注入、threadId 前后是否一致。

---

## 下一篇预告

Hooks 与上下文工程：`HumanInTheLoopHook`、Tool 审批、更系统的压缩 / 编辑 / 重试 / 动态选工具。

记忆能"存住"之后，下一篇让它**可审批、可压缩、可重试、危险操作人工确认**。
