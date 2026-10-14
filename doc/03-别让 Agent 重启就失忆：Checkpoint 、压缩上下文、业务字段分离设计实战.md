# 别让 Agent 重启就失忆：Checkpoint 、压缩上下文、业务字段分离设计实战

## 1. 前期准备

版本基准：Spring AI Alibaba 1.1.2.x + Spring AI 1.1.2 + JDK 17+

官方文档：https://java2ai.com

记忆教程：https://java2ai.com/docs/frameworks/agent-framework/tutorials/memory

Agent 教程：https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents

官方主仓库：https://github.com/alibaba/spring-ai-alibaba

官方示例仓库：https://github.com/spring-ai-alibaba/examples

版本跟仓库约定对齐：

```text
JDK                 17+
Spring Boot         3.5.7
Spring AI           1.1.2
Spring AI Alibaba   1.1.2.2
```

上一篇把 ReactAgent 跑通了：能调工具、能多轮对话、有调用上限。但你一重启服务就会发现——**之前的问题全忘了**。本篇专门解决这件事：

**记忆存在哪、状态怎么设计、会话怎么隔离、上下文太长怎么办。**

依赖骨架（在 ReactAgent 篇之上；Redis 持久化演示加 Redisson，后面小节共用）：

```xml
<!-- 与上一篇相同的 BOM + dashscope + agent-framework -->

<!-- 生产级 Checkpoint 演示：Redis（任选 Redis / DB） -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.45.1</version>
</dependency>
```

本地没 Redis 时，也可以先用 `FileSystemSaver` / `MysqlSaver` 完成「重启仍能续聊」——换 Saver，Agent 写法几乎不变。

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

## 2. 先分清两套‘‘记忆’’的区别

SpringAi底座篇的 `ChatMemory` + `MessageChatMemoryAdvisor`，和本篇 讲的Agent 的 **Checkpoint Saver**，名字都是实现对话记忆但不是一层东西：

**ChatMemory + MessageChatMemoryAdvisor**：其核心职责是在每次调用模型前，把历史对话（`messages`）从存储中取出来，注入到本次请求的 `Prompt` 中，让模型“记得”之前聊过什么。主要存储的是用户和助手之间的对话消息列表，也就是 `messages`。它是一张扁平的“消息表”。

**Checkpoint Saver**：`Checkpoint Saver` 位于 Agent Framework 的 **Graph 执行引擎**层，是**Agent 运行时状态的保存者**。其核心职责是在 Agent 执行图的**每个超级步骤（super-step）** 后，将整个图的运行时状态保存为一个检查点（Checkpoint）。其存储内容保存的是一个**完整的运行时快照**，封装在 `OverAllState` 对象中。它不仅包含 `messages`，还包含图执行到了哪个节点、下一步要执行什么任务、自定义的状态变量等。

**如果选择与组合呢？**

如果你的场景是**简单的、无状态的模型调用**（比如直接使用 `ChatClient`），只需要记住对话历史，`ChatMemory` + `MessageChatMemoryAdvisor` 就足够了。但是当你使用 **`ReactAgent`** 或构建**复杂的 Graph 工作流**时，就应该使用 `Checkpoint Saver`。因为 `ReactAgent` 的内部就是一个图执行过程，`Checkpoint Saver` 能保证这个复杂过程的可恢复性。

在 `ReactAgent` 的上下文中，**`Checkpoint Saver` 是更底层、更全面的机制**。它保存的 `OverAllState` **已经包含了 `messages`**，因此在功能上覆盖了 `ChatMemory` 的记忆能力，同时还额外保存了执行图的状态。可以说，在 Agent 场景下，`Checkpoint Saver` 就是实现“短期记忆”的推荐方式，只是它存储的信息更丰富，不限于聊天消息。可以完成**ReAct Agent**、需要**Human-in-the-Loop**（人工中断恢复）、**时间旅行**（回看历史步骤）、**容错**（重启后继续执行）等复杂任务。

---

## 3. 为什么重启就失忆？

上一篇写过：

```java
.saver(new MemorySaver())
```

`MemorySaver` 把每个 `threadId` 的 Checkpoint 放在 **JVM 堆里的 Map**。进程活着，同一 `threadId` 就能续聊；进程一死，Map 清空——记忆归零。

这种方式是**开发期默认实现**：快、零依赖、适合 Demo。生产要换成实现了同一套 `BaseCheckpointSaver` 契约的持久化实现：

| 操作 | 作用 |
| --- | --- |
| `put` | 写入 / 更新某次 Checkpoint |
| `get` | 取最新，或按 `checkpointId` 取指定快照 |
| `list` | 列出该 thread 的历史 Checkpoint |
| `release` | 释放该 thread 相关 Checkpoint（清理会话） |

---

## 4.1 Agent 记忆

### 4.1.1 `MemorySaver`

最小配置上一篇已经写过。核心只有两句：

1. 创建 Agent 时 `.saver(new MemorySaver())`
2. 每次调用带 **同一个** `threadId`

```java
ReactAgent agent = ReactAgent.builder()
        .name("memory_agent")
        .model(chatModel)
        .systemPrompt("你是助手。用户提到所在城市时要记住，后续问「本地天气」时沿用。")
        .saver(new MemorySaver())
        .build();

RunnableConfig config = RunnableConfig.builder()
        .threadId("t-001")
        .build();

agent.call("我在成都", config);
agent.call("我所在地叫什么？", config); // 同一 threadId 才答得上
```

**新 `threadId` = 新会话**：换 `t-002` 再问「我所在地」，模型不知道 `t-001` 的成都。

### 4.1.2 生产级 Checkpoint / CheckPointer（持久化）

官方记忆文档生产示例用的是 **Redis Checkpointer**。社区与仓库里常见实现还包括：

| Saver | 介质 | 适合 |
| --- | --- | --- |
| `MemorySaver` | 内存 | 开发、单测、临时 Demo |
| `RedisSaver` | Redis | 高并发、多实例、分布式会话 |
| `MysqlSaver` / `PostgresSaver` / `OracleSaver` | 关系库 | 强一致、要和业务库同事务边界、审计 |
| `FileSystemSaver` | 本地文件 | 单机、零中间件先跑通「重启续聊」 |
| `MongoSaver` 等 | 文档库 | Schema 灵活的状态文档 |

**更换 Saver 的实现方式永远一样**：构建好 Bean，放到 `.saver(...)`。

#### Redis实现方式

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

#### MySQL实现方式

```java
@Bean
public MysqlSaver mysqlSaver(DataSource dataSource) {
    return MysqlSaver.builder()
            .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
            .dataSource(dataSource)
            .build();
}
```

#### FileSystem实现方式

适合本机先验证：Checkpoint 落在磁盘目录，进程挂了文件还在。生产多实例不要指望共享本地盘，除非挂了共享卷。

### 4.1.3 同一 `threadId` 续聊、新 `threadId` 新会话

`threadId` 是 Checkpoint 的**一级分区键**。可以把它想成会话文件夹名：

```text
saver/
  ├── thread-t-001/   ← 用户 A 的客服会话
  ├── thread-t-002/   ← 用户 A 的另一个话题
  └── thread-t-003/   ← 用户 B 的会话
```

| 场景 | 怎么设 threadId |
| --- | --- |
| 同一用户同一客服窗口连续聊 | **固定**同一个 threadId |
| 用户点「新对话」 | **生成新** threadId |
| 多轮里偶尔丢上下文 | 先查前端是否每轮换了 id，再查 saver |

`RunnableConfig` 里还可以再带 `checkpointId`，从**某一个历史快照**恢复（人机审批中断后续跑会用到）。日常续聊一般只传 `threadId`，框架取该 thread **最新** Checkpoint。

### 4.1.4 消息窗口、摘要压缩解决上下文过长

短期记忆最常见的形态：**把历史 messages 全塞进状态**。对话一长就会出现：

1. **触达模型上下文上限** → 报错或截断  
2. **没超限但被旧内容带偏** → 答非所问  
3. **Token 账单起飞** → 底座篇说过的「复读历史」税

官方把这类手段归到「上下文工程」。ReactAgent 侧常用三种：

| 策略 | 做法 | 优点 | 风险 |
| --- | --- | --- | --- |
| **修剪（Trimming）** | 调用模型前丢掉中间旧消息，保留首条 + 最近 N 条 | 实现简单、成本低 | 丢掉的细节找不回来 |
| **删除（Deletion）** | 永久从状态里删掉部分/全部消息 | 立刻降压 | 删坏 tool 配对会让部分厂商 API 报错 |
| **摘要（Summarization）** | 旧消息交给便宜模型总结，用摘要替换长历史 | 保留事实线索 | 多一次模型调用；摘要本身可能丢细节 |

推荐扩展 `MessagesModelHook`，在 `BEFORE_MODEL` 里改消息列表，并用 **`UpdatePolicy.REPLACE`** 写回。

**修剪示例：**

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

**思路：** 估算 token（或按条数）超过阈值 → 把旧段发给 `summaryModel` → 生成一条 `SystemMessage("## 之前对话摘要:...")` + 保留最近 N 条 → `REPLACE`。框架后续版本也有内置 `SummarizationHook` 一类能力，原理相同：先压缩，再推理。

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

## 4.2 状态模型

### 4.2.1 `OverAllState`：跨节点 / 跨轮共享状态

上一篇把 `OverAllState` 比作「公共背包」。本篇详细讲讲它和记忆的关系：

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

所以：**一轮对话之内**节点之间靠 OverAllState 传数据 。**多轮对话之间**靠 Checkpoint 把 OverAllState **冻住再解冻**  。

### 4.2.2 状态读写、默认值、合并策略（KeyStrategy）

状态管理在**读的时候记得带上默认值防止空指针异常，写的时候看更据key 的策略来判断。** 策略决定了新的值和旧的值是“覆盖”还是“追加”，用错了 key 的策略，要么类型对不上，要么历史被截断。

在读状态时`OverAllState.value(key)` 返回 `Optional`，`value(key, defaultValue)` 直接带上默认值。记住**没有 key 时要判断**，多租户、跨会话场景做 NPE异常判断。

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

**注意 `value` 的泛型推断**。`state.value("messages", List.of())` 返回的是 `List<Message>`，但如果你写 `state.value("messages", new ArrayList<>())`，返回的是 `ArrayList<Object>`，后续遍历时可能 cast 失败。默认值的类型要和实际存储的类型对齐。

**这里科普下`Optional`：**

`Optional<T>` 是 Java 8 引入的容器类，用来优雅地表示“一个值可能存在，也可能不存在”。它强迫调用方显式处理“值不存在”的情况，避免直接返回 `null` 导致后续 `NullPointerException`。`orElseThrow()` 是 `Optional` 的一个方法，无参版本的行为是：

- 如果 `opt` 里有值 → 返回这个值。
- 如果 `opt` 是空的 → 抛出 `NoSuchElementException`。

这样做的好处是：**如果状态意外缺失，程序会立刻抛出异常并暴露问题，而不是让 `state` 变成 `null`，在后面使用时才报 NPE，增加排查难度**。如果你能接受“状态不存在时返回默认值”，可以用 `orElse(new OverAllState())` 或 `orElseGet(...)`。

在写状态时节点/Hook 返回 Map，框架按 key 策略合并。在Agent 图里的每个节点（包括 Hook）执行完后，返回一个 `Map<String, Object>`。框架不会无脑覆盖整张状态表，而是**逐步 遍历key 决定怎么合并**：

```// 节点/Hook 返回
return Map.of(
        "user_city", "成都",
        "messages", List.of(new AssistantMessage("已记住"))
);
```

框架拿到这个 Map 后，对每个 key 做两件事：

1. **查这个 key 注册了什么策略**（`AppendStrategy` 还是 `ReplaceStrategy`）。
2. **按策略把新值合并进旧值**。

所以同一个 `Map` 里，`user_city` 和 `messages` 的合并行为可以完全不同——一个覆盖，一个追加。

**KeyStrategy：两类策略，两种语义**

| 策略                          | 行为                                   | 典型 key                                  |
| ----------------------------- | -------------------------------------- | ----------------------------------------- |
| **`AppendStrategy`（追加）**  | 列表类追加，旧元素保留，新元素接在后面 | `messages`                                |
| **`ReplaceStrategy`（替换）** | 新值直接覆盖旧值，旧值丢弃             | `user_city`、`order_status`、`need_human` |

**`messages` 默认按追加语义管理。** ReactAgent 构建时，框架内部已经为 `messages` 注册了 `AppendStrategy`。所以你每轮调用时，历史消息会越积越多，而不是被新消息覆盖。

**自定义业务字段默认是替换。** 如果你在 Agent 里传入 `user_city=成都`，下一轮传入 `user_city=上海`，状态里的值会变成 `上海`，而不是 `[成都, 上海]`。这通常是你想要的。

**自定义 key 的策略注册**

ReactAgent 场景下，`messages` 由框架管理，你自定义的 key 默认走替换策略，一般够用。但如果你在 Graph 层自行构建 `StateGraph`，就需要**显式注册每个 key 的策略**：

```
KeyStrategyFactory factory = () -> {
    Map<String, KeyStrategy> map = new HashMap<>();
    map.put("messages", new AppendStrategy());       // 对话历史，追加
    map.put("user_city", new ReplaceStrategy());     // 业务标量，覆盖
    map.put("need_human", new ReplaceStrategy());
    map.put("tool_call_log", new AppendStrategy());  // 调用日志，追加
    return map;
};
```

**不注册的 key 会怎样？** 取决于框架版本的默认行为。有的版本默认用 `ReplaceStrategy`，有的版本直接报错。

### 4.2.3 `messages` 与业务字段分离设计

把一切都塞进 `messages`，指望模型从自然语言历史里“挖”出关键信息，是 Agent 设计里最隐蔽的坑。正确的做法是：**对话轨迹归对话轨迹，业务状态归业务状态，身份信息归身份信息，临时数据归临时数据。**

之前案例遇到的坑“我说了我在成都，第二轮忘了”，就是典型症状。把一切都塞进 `messages`会出现：

- **messages 是线性的自然语言流**，关键事实（“用户在成都”）混在工具调用、中间推理、格式化输出里，注意力被稀释。
- **messages 会被裁剪**。上下文超长时，早期消息被窗口淘汰，“我在成都”直接消失。
- **messages 会被摘要**。摘要模型可能把“我在成都”压缩掉，或压缩成模糊表述。
- **工具无法直接读 messages**。工具只能通过参数拿数据，如果地点只在历史里，工具每次都得靠模型“猜”参数。
- **前端/审计无法结构化展示**。想显示“当前工单状态”，只能解析整段对话，脆弱且昂贵。

分离设计解决的就是这些问题。

#### **对四类消息有不同的处理方式：**

##### 1. 对话轨迹 → `messages`

user 说了什么、assistant 回了什么、调了哪个工具、工具返回了什么。

**特征**：自然语言、线性、需要被模型“读到”、需要按轮次保留。

**典型内容**：

```
UserMessage: 成都天气怎么样？
AssistantMessage: toolCalls=[get_weather(city=成都)]
ToolResponseMessage: 晴，25℃
AssistantMessage: 成都今天晴，25℃。
```

**管理方式**：框架自动追加，`AppendStrategy`，窗口裁剪 + 摘要。



##### 2. 结构化业务态 → 独立 state key

从对话中提取出的、后续需要反复使用的**事实和状态**。

**特征**：结构化（String/Boolean/POJO）、跨轮次持久、工具和 Hook 直接读写、不依赖模型“回忆”。

**典型 key**：

| key             | 类型    | 用途                                 |
| --------------- | ------- | ------------------------------------ |
| `user_city`     | String  | 用户所在地，后续天气查询直接用       |
| `cart_id`       | String  | 当前购物车 ID                        |
| `ticket_status` | String  | 工单状态：pending/resolved/escalated |
| `need_human`    | Boolean | 是否需要转人工                       |
| `vip_level`     | String  | 用户等级，影响回复策略               |
| `order_id`      | String  | 当前处理的订单号                     |

**管理方式**：`ReplaceStrategy`（业务标量）或 `AppendStrategy`（需要累积的日志类），通过 `OverAllState` 读写。

**关键价值**：工具直接读字段，不用从聊天记录里猜。

```
// 工具里直接读，不依赖模型传参
String city = (String) ctx.getContext().get("user_city");
```



##### 3. 请求级身份 → `RunnableConfig.metadata`

**由应用层确定、LLM 不应编造**的身份信息。

**特征**：请求进入时就确定、不可变、贯穿整个执行、需要持久化到 Checkpoint。

**典型内容**：

| key          | 用途                  |
| ------------ | --------------------- |
| `user_id`    | 当前登录用户 ID       |
| `tenant_id`  | 租户 ID（多租户隔离） |
| `trace_id`   | 链路追踪 ID           |
| `request_id` | 请求唯一标识          |

**管理方式**：调用时通过 `RunnableConfig.builder().addMetadata(...)` 注入，工具通过 `ToolContext` 读取。

**为什么不能放 messages**：LLM 可能会编造 `user_id`，或者被用户诱导填入别人的 ID，造成越权。

**为什么不能放 state key**：metadata 是**请求级**的，每次调用由应用层注入，不该让模型或节点修改。state key 更偏向“执行过程中产生的业务状态”。

```
RunnableConfig config = RunnableConfig.builder()
        .threadId(threadId)
        .addMetadata("user_id", userId)
        .addMetadata("tenant_id", tenantId)
        .build();
```



##### 4. 仅本轮临时 → `RunnableConfig.context`

**只在当前这次执行内有意义、不需要持久化**的临时数据。

**特征**：可变、瞬态、**不进入 Checkpoint**、执行结束即丢弃。

**典型内容**：

- 迭代计数器（当前是第几轮 ReAct 循环）
- 临时开关（本轮是否已经调用过某个工具）
- 中间计算结果（不该污染 state 的临时变量）

**管理方式**：`RunnableConfig.context`，使用 `ConcurrentHashMap`，线程安全。

**为什么单独放**：这些数据如果写进 `OverAllState`，会被持久化到 Checkpoint，污染长期状态；如果写进 metadata，又会被误认为“请求级身份”。单独放 context，语义清晰。



##### 我们来对比下之前的设计案例：

**不分离的设计**：

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

第二轮时，“我在成都”混在历史里，模型没抓住。

**分离的设计**：

```
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

**关键区别**：第二轮时，“成都”不在 messages 里，而在 `user_city` 这个 state key 里。即使 messages 被裁剪、被摘要，`user_city` 依然在。工具直接读字段，不依赖模型“回忆”。

##### 怎么在 Hook 里自动提取业务字段：

手动传 `user_city` 太麻烦，可以在 Hook 里自动从消息中提取：

```
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

提取可以用规则（正则匹配城市名）、小模型（专门的抽取调用）、或工具返回的结构化字段。一旦写入 `user_city`，后续所有工具和 Hook 都能直接读。

## 4.3 会话与多租户

### 4.3.1 `userId` / `conversationId` / `tenantId` 设计

多租户场景下，三个 id 各司其职，一个都不能省。

三个 id 职责不同：

| 标识                          | 含义         | 放哪                           | 谁写入                 |
| ----------------------------- | ------------ | ------------------------------ | ---------------------- |
| **tenantId**                  | 租户/企业    | metadata，或拼进 threadId 前缀 | 服务端（登录态）       |
| **userId**                    | 终端用户     | metadata → ToolContext         | 服务端（登录态）       |
| **conversationId / threadId** | 一次会话线程 | `RunnableConfig.threadId`      | 服务端生成，前端可持有 |

**什么不能用一个字符串糊弄？**

- 如果只用一个 `threadId`，多租户隔离、用户隔离、窗口隔离全都混在一起。清会话时不知道该清哪条，查越权时不知道该查谁。
- 三个 id 的**生命周期不同**：`tenantId` 和 `userId` 跨会话不变，`conversationId` 每次开新窗口都变。
- 三个 id 的**信任级别不同**：`tenantId` / `userId` 必须服务端注入，不能信前端；`conversationId` 可以由前端持有并回传，但服务端要校验它属于当前用户。

推荐 threadId 生成规则：

```text
{tenantId}:{userId}:{conversationId}

例：acme:u10086:c-20260409-01
```

**这样设计的三个好处：**

| 场景                 | 表现                                                         |
| -------------------- | ------------------------------------------------------------ |
| **同一用户多个窗口** | 不同 `conversationId`，各自独立历史，互不干扰                |
| **清会话**           | `saver.release("acme:u10086:c-20260409-01")` 精确释放，或业务表软删除 |
| **串会话排查**       | 直接看 threadId 里三个字段是否匹配，快速定位是前端复用了 conversationId，还是服务端 userId 注入错了 |

**清会话的两种方式：**

```
// 方式一：直接释放 saver 里的检查点
saver.release(threadId);

// 方式二：业务表软删，同时释放 saver
conversationService.softDelete(conversationId);
saver.release(threadId);
```

生产环境建议**两种都做**——业务表记录会话元数据，saver 存实际状态。清会话时先软删业务记录，再释放 saver。

**三条隔离红线**：

**红线一：绝不能所有用户共用一个默认 threadId**

如果你不传 `threadId`，框架会用默认值。所有用户的对话历史会混进同一条会话，A 用户的订单可能出现在 B 用户的上下文里。**这是最严重的越权漏洞。**

**红线二：服务端根据登录态写入 userId / tenantId，不要信任模型或前端**

前端传 `userId=123`，不代表当前登录用户就是 123。服务端必须从**登录态/Session/Token** 里取，而不是从请求参数里取。

**红线三：Tool 内读身份只从 ToolContext / metadata 来**

工具查订单时，`userId` 必须从 `ToolContext` 读，不能从模型传的参数读

### 4.3.2 敏感信息不进 Prompt、不进 messages、谨慎进 Checkpoint

Checkpoint 会把状态**序列化持久化**。进了 messages / state / metadata 的东西，就等于进了 Redis/DB/磁盘，会被长期保存、可能被备份、可能被日志打印。

| 类别         | 例子                 | 建议                                             |
| ------------ | -------------------- | ------------------------------------------------ |
| **密钥**     | API Key、密码、Token | 环境变量/密钥托管；Tool 内临时用，不进任何上下文 |
| **强隐私**   | 身份证、银行卡完整号 | 脱敏，记忆里只留尾号                             |
| **过量 PII** | 手机号、住址全文     | 最小化；日志与记忆分级                           |
| **他人数据** | 别的 userId 的订单   | 工具层鉴权                                       |

你不能写一句 `systemPrompt("不要泄露其他用户的订单")` 就指望模型守规矩。模型可能被诱导，也可能出错。**真正的鉴权必须在工具内部做**：

```
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

## 5. 样例：持久化续聊 + 状态查看 + 修剪

### 5.1 配置 RedisSaver

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

### 5.2 Agent 接口

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

### 5.3 自测顺序

1. `GET /api/s41/chat?q=我叫张三，在杭州&threadId=acme:u10086:c-001`  
2. `GET /api/s41/chat?q=我叫什么？在哪个城市？&threadId=acme:u10086:c-001` → 应答上  
3. **重启应用**  
4. 重复步骤 2 → **仍应答上**（MemorySaver 会失败，持久化 Saver 应成功）  
5. 换 `threadId=acme:u10086:c-002` 再问「我叫什么」→ 应不知道  
6. `/api/s42/state` 看 messages 是否在增长；多聊几轮观察 trimming 是否触发  
7. `/api/s43/release` 后同一 thread 再问个人信息 → 应清空  

## 下一篇预告

Hooks 与上下文工程：`HumanInTheLoopHook`、Tool 审批、更系统的压缩/编辑/重试/动态选工具。  
记忆能「存住」之后，下一篇让它「可审批、可压缩、可重试、危险操作人工确认」。
