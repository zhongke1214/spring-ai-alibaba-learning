# Spring AI 底座

## 前期准备

版本基准：**Spring AI Alibaba 1.1.2.x** + **Spring AI 1.1.2** + **JDK 17+**

官方文档：[https://java2ai.com](https://java2ai.com)

官方主仓库：[https://github.com/alibaba/spring-ai-alibaba](https://github.com/alibaba/spring-ai-alibaba)

官方示例仓库：[https://github.com/spring-ai-alibaba/examples](https://github.com/spring-ai-alibaba/examples)

版本跟仓库约定对齐：

```plain
JDK                 17+
Spring Boot         3.5.7
Spring AI           1.1.2
Spring AI Alibaba   1.1.2.2
```

模型用百炼 DashScope（通义）。换 OpenAI / Ollama 只是换 starter 和配置，`ChatClient` 写法差不多。

依赖骨架（后面小节共用这套，不重复贴）：

```plain
 <properties>
        <java.version>17</java.version>
        <spring-ai.version>1.1.2</spring-ai.version>
        <spring-ai-alibaba.version>1.1.2.2</spring-ai-alibaba.version>
        <spring-ai-alibaba-extensions.version>1.1.2.2</spring-ai-alibaba-extensions.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <!-- Agent / Graph 用这个 -->
            <dependency>
                <groupId>com.alibaba.cloud.ai</groupId>
                <artifactId>spring-ai-alibaba-bom</artifactId>
                <version>${spring-ai-alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <!-- DashScope starter 用这个，缺了版本就是 unknown -->
            <dependency>
                <groupId>com.alibaba.cloud.ai</groupId>
                <artifactId>spring-ai-alibaba-extensions-bom</artifactId>
                <version>${spring-ai-alibaba-extensions.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
        </dependency>
         <!-- OpenAPI 3 / Swagger UI -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.8.13</version>
        </dependency>
    </dependencies>
```

配置文件：

```plain
# 配置模型和apiKey
spring:
  ai:
    dashscope:
# 生产环境强烈推荐使用环境变量（或等效的外部化配置），而不是把密钥硬编码在
      api-key: ${AI_DASHSCOPE_API_KEY}
```

**注意**：

默认不指定模型时使用的是`qwen-plus` 属于纯文本模型，走默认的 text-generation 接口，所以没问题 。如果要指定其他**原生视觉语言/多模态** 系列，如 qwen3.7-flash  等百炼要求走 multimodal-generation。Spring AI Alibaba 默认仍打文本端点，就会报：

`InvalidParameter` / `url error, please check url！`

**解决办法**

在配置里打开多模态开关，例如：

```plain
spring:
  ai:
    dashscope:
      chat:
        options:
          model: qwen3.7-flash
          multi-model: true
```

或在代码里指定：

```plain
DashScopeChatOptions.builder()
    .model("qwen3.7-flash")
    .multiModel(true)   // 有的版本是 withMultiModel(true)
    .build()
```

注意：只改 `model` 不够，必须带上 `multi-model: true`，starter 才会把请求切到多模态 URI。

**或者改走 OpenAI 兼容模式**

多模态模型在 OpenAI 兼容模式下，只要请求体是标准文本格式，服务端会自动处理，不会因为“模型是多模态”而拒绝。你只需要把 starter 换成 OpenAI 兼容的，base-url 指向 DashScope 的兼容端

```plain
spring:
  ai:
    openai:
      api-key: ${AI_DASHSCOPE_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      chat:
        options:
          model: qwen3.7-flash
```

## 1.1 模型抽象

### 1.1.1 ChatModel / StreamingChatModel
Spring AI 官方将 `ChatModel` 定义为所有大模型厂商的统一接口，设计目标是**以最少的代码更改在不同模型之间切换**。`ChatModel` 接口继承了 `StreamingChatModel`，因此实现类天然同时支持同步和流式两种模式。

```plain
public interface ChatModel extends Model<Prompt, ChatResponse>, StreamingChatModel {
    default String call(String message) {...}
    ChatResponse call(Prompt prompt);
}
```

`StreamingChatModel` 定义 `stream()` 方法，返回 `Flux<ChatResponse>`，实现逐块推送。

`ChatModel` 就像**一台万能遥控器**。不管电视是索尼、三星还是小米（DashScope / OpenAI / Ollama），遥控器上的“开机”按钮位置和功能都一样。你不需要因为换了电视就重新学习一套操作。`StreamingChatModel` 则是遥控器上的“逐帧慢放”按钮——同一台设备，按一下就能让画面一行一行地出来，而不是等整段视频加载完。

### 1.1.2 ChatClient 流式 API（call / stream）
可以把 **ChatModel 理解为底层引擎（手动挡）**，把 **ChatClient 理解为高层门面（自动挡）**。后者在前者的基础上，为你封装了日常开发中绝大多数繁琐的样板代码。

两者本质是**分层关系**，而不是替代关系。

+ **ChatModel (底层接口)**：直接对接各家 AI 厂商的 SDK。能力偏底层，只提供基础的 `call()` 和 `stream()` 方法，相当于一个“翻译官”，负责帮你把请求发出去，再把原始响应（`ChatResponse`）拿回来。
+ **ChatClient (高层门面)**：构建在 `ChatModel` 之上的“智能点餐机”。它内部**持有一个 `ChatModel`**，实际的请求最终还是由底层的 `ChatModel` 发出的。它把你需要手动处理的复杂逻辑（如提示词组装、解析）都打包隐藏了起来。

#### 能力与使用对比
| 维度 | ChatModel (手动挡) | ChatClient (自动挡) |
| --- | --- | --- |
| **调用方式** | 需手动构造 `Prompt` 和 `Message` 对象 | **链式（Fluent）调用**，代码极其简洁直观 |
| **系统提示词** | 需要手动拼装进 `Prompt` | 直接调用 `.system("你是...")` 设置 |
| **对话记忆** | 不支持，需自己维护历史消息 | **内置支持**，通过 `Advisor` 机制自动管理上下文 |
| **结构化输出** | 需自己解析返回的 JSON 字符串 | **一键映射**，`.entity(MyClass.class)` 直接返回 Java 对象 |
| **工具调用** | 需自己实现复杂的循环逻辑（执行工具->回传结果） | **自动处理**，通过 `ToolCallingAdvisor` 完成闭环 |
| **适用场景** | 框架开发者、需要精确控制底层参数的场景 | **日常业务开发**、快速构建 AI 功能 |

结合你当前的学习阶段，**直接使用 `ChatClient` 是更高效的选择**：

1. **开发效率高**：你只需要写一行链式代码，就能完成“设置系统提示词 -> 填充用户参数 -> 调用 -> 获取结果”的完整流程，不用再手动 `new` 一堆 `Message` 对象。
2. **避免重复造轮子**：你之前遇到的“不指定模型跑通，指定模型报错”的问题，本质是在处理底层端点细节。而 `ChatClient` 的设计初衷就是**屏蔽这些底层的复杂性**，让你专注于业务逻辑。
3. **未来趋势**：在 Spring AI 2.0 的设计中，**`ChatClient` 已经被明确为面向用户的统一入口**，所有高级能力（如工具调用、记忆管理）都收拢到了它背后的 Advisor 链中。直接学 `ChatClient`，是在适应未来的主流写法。

### 1.1.3 ChatOptions
`ChatOptions` 是 AI 聊天客户端库中的一个**统一配置容器**，用于在单次请求中精确控制模型的生成行为、工具调用和上下文管理。

它涵盖了几个主要维度的控制：

+ **生成策略**：控制输出的随机性和长度。比如 `Temperature`（随机性）、`TopP`（核采样）、`MaxOutputTokens`（最大生成长度）、`StopSequences`（停止生成序列）等。
+ **对话上下文**：管理会话状态。比如 `ConversationId`（绑定多轮对话）、`Instructions`（临时追加系统指令）。
+ **工具调用**：配置模型如何使用外部工具。比如 `Tools`（可用工具列表）、`ToolMode`（调用策略，如自动/禁用）。
+ **模型与采样**：指定用哪个模型或固定结果。比如 `ModelId`（指定模型）、`Seed`（固定随机种子以实现可复现的结果）。

#### ChatOptions 核心参数全表
| 参数 | 类型 | 作用 | 调参直觉 |
| --- | --- | --- | --- |
| **model** | String | 指定本次请求用哪个模型 | 临时切换到更强或更便宜的模型 |
| **temperature** | Double | 控制随机性（0.0–2.0） | 低=确定/严谨，高=创意/发散 |
| **maxTokens** | Integer | 生成的最大 token 数 | 限制输出长度，防止废话过多 |
| **topP** | Double | 核采样阈值（0.0–1.0） | 与 temperature 二选一调节，控制多样性 |
| **topK** | Integer | 采样候选池大小 | 限制候选词范围，K 越小越保守 |
| **frequencyPenalty** | Double | 频率惩罚 | 正值=降低重复词的出现频率 |
| **presencePenalty** | Double | 存在惩罚 | 正值=鼓励谈论新话题 |
| **stopSequences** | List<String> | 停止序列 | 遇到指定字符串就停止生成 |

**注意：temperature 和 topP 不要同时调**。这两个参数都在控制“生成多样性”，同时调整会让效果难以预期。通常的做法是：要么固定 topP=1 只调 temperature，要么固定 temperature=0.7 只调 topP。

**frequencyPenalty 和 presencePenalty 的区别**：frequencyPenalty 是按“出现次数”惩罚，出现越多罚越重；presencePenalty 是按“是否出现过”惩罚，只要出现过就罚一次。前者治“复读机”，后者治“车轱辘话来回说”。

**stopSequences 在结构化输出时特别有用**。比如你让模型生成 JSON，可以设置停止序列为 `\n\n` 或 `###`，防止它在 JSON 后面继续输出无关解释。

#### 一个容易踩的坑
**运行时 Options 是完全覆盖，不是合并**。如果你在 yml 里配了 `model: qwen-plus` 和 `temperature: 0.7`，然后在代码里只设置了 `.withTemperature(0.3)`，那么 `model` 会回退到 DashScope 的默认值（通常是 `qwen-plus`），而不是继承 yml 里的配置。所以如果只改一个参数，最好用 `mutate()` 基于现有配置修改

除了 Spring AI 通用参数，Spring AI Alibaba 的 `DashScopeChatOptions` 还提供了一些平台特有配置：

| 参数 | 作用 | 默认值 |
| --- | --- | --- |
| **seed** | 随机种子，相同 seed + 相同输入 = 相同输出 | null |
| **enableSearch** | 是否启用联网搜索 | false |
| **incrementalOutput** | 流式输出时是否只返回新增 token | true |
| **enableThinking** | 是否开启深度思考模式 | true |
| **responseFormat** | 响应格式（text 或 json_object） | text |
| **repetitionPenalty** | 重复惩罚（1.0 表示不惩罚） | null |

**seed 是做可复现测试的关键**。你之前在学习阶段反复调试 prompt 时，如果每次输出都不一样，会很难判断改动到底有没有效果。固定 seed 后，同样的输入永远得到同样的输出，排查问题会轻松很多。

**enableThinking 在 Qwen3 系列上要特别注意**。默认是开启的，这意味着模型会先输出一段“思考过程”再给答案。如果你只需要最终结果，可以关掉它来节省 token 和等待时间。

### 1.1.4 多厂商切换思路（DashScope / OpenAI / DeepSeek / Ollama）
Spring AI 的统一模型抽象使得 OpenAI、Azure OpenAI、Ollama、DashScope、DeepSeek 等只要实现了 `ChatModel` 接口，就能作为“可插拔”组件集成。DeepSeek 有独立的 `org.springframework.ai.deepseek` 包提供 `ChatModel` 实现。兼容 OpenAI 接口的第三方模型（如百川、Moonshot）可直接使用 `spring-ai-starter-model-openai`，只需更改 `base-url` 和 `api-key`。

多个 `ChatModel` 实现共存时，使用 `@Qualifier` 指定注入哪个 Bean，例如 `@Qualifier("dashscopeChatModel")` 和 `@Qualifier("ollamaChatModel")`。

多厂商切换就像**同一个 USB-C 接口插不同的设备**。你的电脑（业务代码）只认 USB-C 这个形状（`ChatModel` 接口）。插上 U 盘是 U 盘，插上手机是手机，插上显示器是显示器。电脑不用改，换设备就行。如果同时插了多个，就用“标签”（`@Qualifier`）告诉系统：“我现在要用的是那个贴着‘DashScope’标签的。”

### 1.1.5 同步 vs 流式响应、Token 消耗直觉
同步模式通过 Servlet 栈支持，流式模式仅通过 Reactive 栈支持。官方可观测性指标区分了 `call` 和 `stream` 的操作耗时统计。

同步 vs 流式的区别，就像**等一封信 vs 看直播**。等信：邮递员把整封信写完才送过来，你拿到的是完整内容。直播：主播每说一句话你立刻看到，但你可能看了半小时才等到关键信息。

**Token 消耗的直觉**：真正贵的地方往往不是“回答本身”，而是**反复“复读”的历史上下文**。有实测数据显示，在 Agent 循环中，每次请求可能重新读取近 10 万 token 的缓存上下文，导致“上下文开销比”高达 200:1——**每产出 1 token 的有效输出，背后要喂回 200 token 的上下文**。这就好比每次你问一个问题，助手都要把之前三个小时的所有聊天记录重新读一遍再回答。

### 样例
```plain
package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * @author zk
 * @date 2026/9/28 17:41
 */
@Tag(name = "Stage01-1.1 模型抽象", description = "Spring AI 底座")
@RestController
public class AModelClientDemo {

    private final ChatModel chatModel;
    private final ChatClient chatClient;

    public AModelClientDemo(ChatModel chatModel, ChatClient.Builder builder) {
        this.chatModel = chatModel;
        // Builder 由 starter 自动配置；这里做出一个可复用的 ChatClient
        this.chatClient = builder.build();
    }

    //    1.1.1 ChatModel / StreamingChatModel
    @Operation(summary = "ChatModel 直接调用")
    @GetMapping("/api/s11/model")
    public String byModel(
            @Parameter(description = "用户问题") @RequestParam(defaultValue = "用一句话介绍成都") String q) {
        ChatResponse response = chatModel.call(new Prompt(q));
        // 取第一条候选的文本；一般就一条
        return response.getResult().getOutput().getText();
    }

    //    1.1.2 ChatClient 流式 API（call / stream）

    @Operation(summary = "ChatClient 同步调用")
    @GetMapping("/api/s11/call")
    public String byClient(
            @Parameter(description = "用户问题") @RequestParam(defaultValue = "把春眠不觉晓翻译成英文") String q) {
        return chatClient.prompt()
                .user(q)
                .call()
                .content(); // 只要字符串时用 content()
    }

    /**
     * 流式：前端用 SSE / WebFlux 接 Flux<String>
     *
     * @param q
     * @return
     */
    @Operation(summary = "ChatClient 流式输出")
    @GetMapping(value = "/api/s11/stream", produces = "text/plain;charset=UTF-8")
    public Flux<String> stream(
            @Parameter(description = "用户问题") @RequestParam(defaultValue = "讲个50字的冷笑话") String q) {
        return chatClient.prompt()
                .user(q)
                .stream() //swagger可能看不到流式输出效果，可以在浏览器F12查看响应或者用支持的前端界面测试
                .content(); // 每个元素是一小段增量文本
    }

// 1.1.3 ChatOptions

    /**
     * 临时改选项：低温更稳，适合分类/抽取；高温更活，适合文案
     */
    @Operation(summary = "带 temperature / maxTokens 选项调用,其余参数可自行添加测试")
    @GetMapping("/api/s11/options")
    public String withOptions(
            @Parameter(description = "用户问题") @RequestParam(defaultValue = "给咖啡店起10个中文名，逗号分隔") String q) {
        return chatClient.prompt()
                .user(q)
                .options(ChatOptions.builder()
                        .temperature(0.2)  // 0~1，越低越保守
                        .maxTokens(15)    // 限制生成长度，省钱也防废话
                        .build())
                .call()
                .content();
    }
}

```

---

## 1.2 消息与提示词
在 Spring AI 里，**消息（Message）是对话的基本单元，提示词（Prompt）是一次请求的完整容器**。可以把它们的关系理解成“句子”和“整封信”：Message 是信里的一句句话，Prompt 是封装好收件人、正文、附件后准备寄出的那整封信。

每条 Message 代表对话中的一个“发言”，包含三个核心要素：

| 要素 | 说明 |
| --- | --- |
| **角色（Role）** | 谁在说话：System、User、Assistant、Tool |
| **内容（Content）** | 说了什么，文本或多媒体 |
| **元数据（Metadata）** | 附加信息，如消息 ID、工具调用结果 |

Spring AI 提供了对应的实现类：

+ **SystemMessage**：设定 AI 的行为准则、人设、背景知识。比如“你是一个 Java 专家，回答要简洁”。
+ **UserMessage**：用户输入的问题或指令。
+ **AssistantMessage**：AI 之前的回复。在多轮对话中，把历史回复放回上下文，AI 才能“记得”之前聊过什么。
+ **ToolResponseMessage**：工具调用的返回结果。当 AI 决定调用某个函数时，执行结果以这种消息形式回传。

#### 一次请求的完整封装
Prompt 是你发给 AI 模型的**完整请求包**，包含：

| 组成部分 | 作用 |
| --- | --- |
| **消息列表** | 按顺序排列的 System、User、Assistant 消息 |
| **ChatOptions** | 本次请求的运行时参数（temperature、model 等） |

用 ChatClient 时，这些构造过程被封装了，你写的是更简洁的链式调用，但底层最终还是会组装成一个 Prompt。

所谓“写提示词”，主要就是在设计 **SystemMessage 的内容**。SystemMessage 决定了 AI 的：

+ **角色定位**：“你是一个资深后端工程师”
+ **输出格式**：“用 Markdown 表格回答”
+ **约束条件**：“只回答 Java 相关问题，其他一律拒绝”
+ **思考方式**：“先分析问题，再给出方案，最后附上代码示例”

System 提示别写成小说。角色一句、边界一句、工具规则一句、输出格式一句，够用了。

另外指定系统消息时`可以用.defaultSystem(...)`和`.system(...)`指定：

当你的整个应用都希望 AI 保持一致的人设或行为规则时。比如你的学习项目里，所有对话都希望 AI 以“Java 学习助手”的身份回答，就在构建 `ChatClient` 时用 `defaultSystem` 设一次，之后每个接口都不需要重复写 。

当某个特定接口需要临时改变 AI 的角色时。比如你的项目里大部分接口是“技术答疑”，但某个接口需要 AI “扮演面试官出题”，就在那个接口的 prompt 链里用 `.system("你是一个 Java 面试官...")`，只影响这一次调用 。

**优先级规则很简单**：单次 `system` > 全局 `defaultSystem`。如果两者都设了，模型看到的是单次 `system` 的内容 。

**UserMessage** 则是具体的任务输入。两者配合，才能让模型输出你真正想要的结果。

用 `chatClient.prompt().user("...").call().content()`，Spring AI 在底层帮你做了这些事：

1. 把你传入的字符串包装成 `UserMessage`
2. 如果有 `.system("...")`，包装成 `SystemMessage` 放在最前面
3. 把历史消息（如果有 Advisor 管理）按顺序拼进去
4. 加上 ChatOptions，组装成 `Prompt`
5. 调用 `ChatModel`，拿到 `ChatResponse`
6. 从响应中提取 `AssistantMessage` 的内容返回给你

理解 Message 和 Prompt 的结构，能帮你在遇到“AI 不记得上下文”“输出格式不对”“系统提示词不生效”这类问题时，快速定位到是哪一层出了问题。

### 样例
```plain
package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 1.2 消息与提示词（Message / Prompt / System）
 * <p>
 * ChatClient 链式调用最终仍会组装成 Prompt，再交给 ChatModel。
 * 底层大致：system→SystemMessage、user→UserMessage、历史消息(Advisor)、Options → Prompt → ChatResponse → 文本。
 * 排查「不记得上下文 / 格式不对 / 系统提示不生效」时，先看消息列表和 Prompt 怎么拼的。
 */
@Tag(name = "Stage01-1.2 消息与提示词", description = "System / User / Prompt 常用案例")
@RestController
public class BPromptDemo {

    private final ChatClient chatClient;

    public BPromptDemo(ChatClient.Builder builder) {
        // defaultSystem：整应用一致人设，设一次即可；单次 .system(...) 会整段覆盖它（不是追加）
        // System 提示宜短：角色一句、边界一句、输出格式一句
        this.chatClient = builder
                .defaultSystem("""
                        你是 Java 学习助手。
                        只回答 Java / Spring 相关问题，其余礼貌拒绝。
                        用简洁中文，必要时给短代码。
                        """)
                .build();
    }

    /** 只用全局人设：不写 .system()，走 defaultSystem */
    @Operation(summary = "defaultSystem：全局人设")
    @GetMapping("/api/s12/chat")
    public String chat(
            @Parameter(description = "用户问题") @RequestParam(defaultValue = "什么是依赖注入？一句话") String q) {
        // user → UserMessage；无 .system() 时用构造时的 defaultSystem
        return chatClient.prompt()
                .user(q)
                .call()
                .content();
    }

    /** 单次覆盖：这次调用换成「面试官」，不影响其它接口的 defaultSystem */
    @Operation(summary = "system：单次覆盖人设（优先于 defaultSystem）")
    @GetMapping("/api/s12/interview")
    public String interview(
            @Parameter(description = "考察主题") @RequestParam(defaultValue = "HashMap") String topic) {
        // 优先级：本次 .system(...) > 全局 defaultSystem（覆盖，不是拼在后面）
        return chatClient.prompt()
                .system("你是 Java 面试官。只出 1 道简答题，不要给答案。")
                .user("请围绕「" + topic + "」出题。")
                .call()
                .content();
    }

    /** 手搓 Message 列表：看清 Prompt 里 system / user / assistant 的结构 */
    @Operation(summary = "手搓 Message → Prompt（理解底层结构）")
    @GetMapping("/api/s12/messages")
    public String rawMessages() {
        List<Message> messages = List.of(
                new SystemMessage("你是校对员，只指出错别字，不要改写文风。"),
                new UserMessage("请检查：春眠不觉晓，处处蚊叮咬。"),
                new AssistantMessage("「蚊叮咬」疑似笔误，常见写法是「闻啼鸟」。"),
                new UserMessage("再检查：夜来风雨声，花落知多少。")
        );
        // 等价于 ChatClient 帮你自动组装 Prompt；多轮/few-shot 时历史就是这样排的
        return chatClient.prompt(new Prompt(messages))
                .call()
                .content();
    }

    /** 模板变量：别用 + 硬拼 Prompt */
    @Operation(summary = "PromptTemplate 变量替换")
    @GetMapping("/api/s12/template")
    public String template(@RequestParam(defaultValue = "成都") String city) {
        PromptTemplate template = new PromptTemplate(
                "用不超过30字介绍{city}，面向第一次去旅游的人。");
        Prompt prompt = template.create(Map.of("city", city));
        return chatClient.prompt(prompt).call().content();
    }
}

```

---

## 1.3 结构化输出
**结构化输出**指的是让大语言模型（LLM）不再返回一段随意的自然语言文本，而是严格按照你预先定义好的数据格式（比如 JSON、Java 对象）来返回结果，以便程序直接解析使用。平常和模型对话，它返回的是一段**字符串**。如果程序需要把这段文本里的信息存进数据库或传给下一个函数，就得靠正则表达式去“抠”数据，模型稍微换个说法，解析就失败了。

结构化输出解决了这个痛点：

+ **类型安全**：直接得到 Java 对象，不用手动解析字符串，避免缺少字段或格式错误。
+ **下游友好**：可以直接把结果传给其他方法或存入数据库，形成可靠的自动化流程。

Spring AI 用 `BeanOutputConverter`（ChatClient 的 `.entity(Class)` 背后就是它）把「按这个 JSON Schema 吐」塞进提示，再把返回文本反序列化成 Java Bean。字段名、类型尽量简单。嵌套别一次嵌五层，模型更容易交卷合格。

Spring AI 提供了两种方式来得到结构化结果，可靠性不同：

**默认方式（基于 Prompt）**
这是最通用的方法。它把格式要求写进提示词里，然后指望模型“听话”地按格式返回。它的优点是**兼容所有模型**，缺点是**没有强制力**，模型偶尔会跑偏，返回多余的字段或者包裹在多余的文本里。

**原生方式（Provider-Native）**
这是更可靠的方法。它把 JSON Schema 直接作为 **API 级别的参数**发给模型服务商（比如 OpenAI、Anthropic），由服务商的运行时**强制保证**输出必须符合 Schema，从源头上杜绝格式错误。开启后提示词里不再需要写格式说明，更节省 token。代价是**兼容性有限**，不是所有模型都支持。

#### 兜底机制：`validateSchema()`
即使有上述两种方式，模型偶尔还是可能返回无效数据。Spring AI 提供了一个“安全网”：
调用 `.entity(SomeClass.class, spec -> spec.validateSchema())`。如果模型返回的 JSON 校验失败，Spring AI 会自动**带着具体的错误信息重新请求**模型（默认重试 3 次），让模型有机会自己修正。

### 样例
```plain
package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stage01-1.3 结构化输出", description = "结构化输出")

@RestController
public class CStructuredDemo {

    private final ChatClient chatClient;

    public CStructuredDemo(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /** 业务对象：用 record 最省事；也可用普通 POJO */
    public record MovieRec(
            String title,
            String reason,
            int year
    ) {}

    /** 推荐写法：entity() 内部用 BeanOutputConverter */
    @GetMapping("/api/s13/entity")
    public MovieRec entity(@RequestParam(defaultValue = "科幻") String genre) {
        return chatClient.prompt()
                .user(u -> u.text("推荐1部经典{genre}电影，理由一句话。")
                        .param("genre", genre))
                .call()
                .entity(MovieRec.class); // 失败时会抛转换异常，调用方自行 catch/重试
    }

    /** 显式 Converter：想自己拼 prompt、或流式聚合后再转时用 */
    @GetMapping("/api/s13/converter")
    public MovieRec converter(@RequestParam(defaultValue = "动画") String genre) {
        BeanOutputConverter<MovieRec> converter = new BeanOutputConverter<>(MovieRec.class);
        // getFormat() 是一段「请按此 JSON Schema 输出」的说明文字
        String text = chatClient.prompt()
                .user("推荐1部" + genre + "电影。\n" + converter.getFormat())
                .call()
                .content();
        return converter.convert(text); // 把模型文本解析成 MovieRec
    }
}
```

---

## 1.4 Tool Calling
Tool Calling（工具调用，早期也叫函数调用）是让大语言模型能够**调用外部程序**来获取信息或执行操作的一种机制。

它解决的核心问题是：模型本身只会“生成文字”，无法获取实时数据（如今天天气、股价）或执行真实操作（如发邮件、查数据库）。Tool Calling 让模型在需要时能“伸手”去调用你写好的 Java 方法，拿到结果后再组织成自然语言回复给用户。

以 Spring AI 为例，一次典型的工具调用循环是这样的：

1. **模型决策**：你向模型提问，同时把可用的工具列表（包含名称、描述、参数 Schema）一起发给它。模型判断需要调用某个工具时，不直接回答，而是返回一个“工具调用请求”，里面写明要调用哪个工具、传什么参数。
2. **框架执行**：Spring AI 的 `ToolCallingManager` 拦截到这个请求，根据工具名找到对应的 Java 方法（`ToolCallback`），把模型给的参数传进去执行。
3. **结果回传**：工具执行的返回结果被追加到对话历史中，再次发给模型。模型拿到真实数据后，生成最终的文本回复返回给你。

这个循环会重复进行，直到模型不再请求调用工具、直接输出最终答案为止。

### 在 Spring AI 中如何定义工具
最常见的方式是用 `@Tool` 注解标记一个普通 Java 方法。框架会自动解析方法签名，生成 JSON Schema，让模型知道这个工具“叫什么名字、能干什么、需要什么参数”。

示例1：

```plain
class DateTimeTools {
    @Tool(description = "获取用户时区的当前日期和时间")
    String getCurrentDateTime() {
        return LocalDateTime.now().toString();
    }
}
```

然后在调用时注册进去：

```plain
String response = chatClient.prompt()
        .user("现在几点了？")
        .tools(new DateTimeTools())  // 注册工具
        .call()
        .content();
```

模型看到用户问时间，发现有一个 `getCurrentDateTime` 工具可用，就会自动请求调用它，Spring AI 执行后把时间结果回传给模型，模型最终告诉你具体时间。模型判断“什么时候该用哪个工具”完全依赖 `@Tool(description = "...")` 里的文字。描述写得越贴近用户的真实问法，模型匹配得越准。比如你写“获取天气”，用户问“明天北京热不热”，模型可能拿不准；但如果你写“根据城市名称查询指定日期的天气预报”，匹配度就高很多。

如果你用的是较新的 Spring AI 2.0，工具调用的执行位置有调整：以前工具循环是嵌在 `ChatModel` 内部的，2.0 之后统一移到 `ChatClient` 层面，由 `ToolCallingAdvisor` 负责整个循环的编排。`internalToolExecutionEnabled` 这个选项在新版本中已被移除，工具执行完全由客户端侧管理。

一个问题里模型可能连调多个工具，也可能并行（看模型）。你这边工具要尽量无副作用或自己做幂等，别让「查两次」变成「扣两次款」。

### `.toolContext()`
`.tools()` 和 `.toolContext()` 是 Spring AI 中两个协同工作的机制：一个负责**注册工具**，另一个负责**传递运行时上下文**。

`.tools()` 的作用是告诉 AI 模型：“你现在可以使用这些工具”。它接受的参数很灵活，可以混用多种形式：

+ **`@Tool` 注解的 POJO**：直接传入对象，框架会自动扫描其 `@Tool` 方法并生成 `ToolCallback`。这是最常用的方式，参照上面示例1。
+ **`ToolCallback`**：一个已经构建完成的、可直接执行的工具回调。比如你用 `FunctionToolCallback.builder(...)` 手动构建出来的对象，或者从 MCP 客户端适配出来的 `SyncMcpToolCallback`。传入它，框架直接注册使用。
+ **`ToolCallbackProvider`**：一个“工具提供者”，它本身不直接是工具，而是能**按需产出**一批 `ToolCallback`。比如 `SyncMcpToolCallbackProvider` 封装了一个 MCP 客户端，请求时它才去 MCP 服务器发现并返回可用的工具列表。框架拿到 provider 后，会在请求时调用它的 `getToolCallbacks()` 来获取实际工具。
+ **数组或集合：**

上述类型（`ToolCallback`、`ToolCallbackProvider`，或者它们的混合）**打包成一个数组或集合**再传进去。比如：

```plain
// 一个数组里混放 callback 和 provider
Object[] tools = { myCallback, myProvider, new DateTimeTools() };
chatClient.prompt().tools(tools)...
```

框架会遍历这个数组或集合，对每个元素再按上述规则逐一分类处理。

### 为什么要这样设计
这种“异构参数”的设计是为了**调用方便**。你不需要先手动把各种工具整理成统一格式，框架会自动帮你分发：是 POJO 就扫描 `@Tool` 方法，是 Callback 就直接用，是 Provider 就延迟解析。这也就解释了为什么 `tools(Object... tools)` 签名是 `Object...` 而不是某个具体类型。

`而.toolContext()` 的作用是**在应用内部传递数据给工具方法**，这些数据**不会**被发送给 AI 模型。它本质是一个 `Map<String, Object>`。

**核心用途**：在工具方法中，你可以通过注入的 `ToolContext` 参数来读取这些数据。典型场景包括：

+ **用户身份**：传入 `userId` 或 `userName`，让工具知道当前是谁在操作（如退款、查订单）。
+ **请求追踪**：传入 `requestId`，方便日志排查。
+ **多租户隔离**：传入 `tenantId`，让工具根据租户查询数据。

**使用示例**：

```plain
chatClient.prompt()
    .user("帮我退款")
    .tools(new OrderTools())
    .toolContext(Map.of("userId", "user_123")) // 内部传递，模型看不到
    .call()
    .content();
```

对应的工具方法通过 `ToolContext` 参数接收：

```plain
@Tool(description = "用户退款")
String refund(ToolContext toolContext) {
    String userId = (String) toolContext.getContext().get("userId");
    // ... 根据 userId 执行退款逻辑
}
```

在 Spring AI 2.0 中，`toolContext()` 被提升为一等公民，并增强了校验。同时，`ToolContext` 中不再自动携带对话历史，工具方法只能收到模型传入的参数和你在 `.toolContext()` 中自定义的上下文。这意味着上下文传递变得更加干净和可控。

**作用域**：

通过 `chatClient.prompt().tools(...)` 注册的工具**仅对当前请求有效**。如果你想让某个工具对所有请求都生效，应该使用 `ChatClient.Builder.defaultTools(...)` 来注册。

注意在 Spring AI  1.1.2 的版本 .tools(...)  不会覆盖.defaultTools(...)的工具，官网 Tool Calling 文档现在也写的是 _appends / union_；但部分 2.x API javadoc、旧中文文档仍写 “completely override”，有文档打架。以依赖的 1.1.2 源码为准：tools 是追加，不是覆盖。

### 样例
```plain
package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
/**
 * @author zk
 * @date 2026/9/30 11:16
 */
@Component
class WeatherTools {

    @Tool(description = "查询城市当前天气。用户问气温、是否下雨、是否带伞时使用。")
    public String getWeather(
            @ToolParam(description = "城市中文名，如杭州、上海") String city,
            ToolContext toolContext) {
        // ToolContext 来自调用方传入的上下文，不是模型编的
        String userId = String.valueOf(toolContext.getContext().getOrDefault("userId", "anonymous"));
        // 学习阶段写死返回；真实项目这里调 HTTP
        String weather = switch (city == null ? "" : city.trim()) {
            case "杭州" -> "晴，24°C，东北风2级";
            case "上海" -> "多云，22°C，东风3级";
            default -> city + "：晴间多云，20°C（示例数据）";
        };
        return "user=" + userId + ", " + weather;
    }
}

@Tag(name = "Stage01-1.4 Tool Calling", description = "工具调用")
@RestController
public class DToolDemo {

    private final ChatClient chatClient;
    private final WeatherTools weatherTools;

    public DToolDemo(ChatClient.Builder builder, WeatherTools weatherTools) {
        this.weatherTools = weatherTools;
        this.chatClient = builder
                .defaultSystem("你是助手。问天气必须调用 getWeather，禁止编造气温。")
                .defaultTools(weatherTools) // 注册工具；ChatClient 会跑工具循环
                .build();
    }

    @GetMapping("/api/s14/weather")
    public String weather(@RequestParam(defaultValue = "杭州今天要不要带伞？") String q,
                          @RequestParam(defaultValue = "u1001") String userId) {
        return chatClient.prompt()
                .user(q)
                // 把业务身份塞进 ToolContext，工具方法里能读到
                .toolContext(Map.of("userId", userId))
                .call()
                .content();
    }
}
```

---

## 1.5 Chat Memory
Chat Memory 是 Spring AI 中用来解决大模型“没有记忆”问题的机制。因为 LLM 本身是无状态的，每次调用对它来说都是全新的开始，Chat Memory 的作用就是在每次请求时，自动把之前的相关对话历史**注入到 Prompt 中**，让模型看起来像是“记得”上下文。

#### Memory 与 Repository
Spring AI 把这件事拆成了两个独立的部分，职责非常清晰：

+ **`ChatMemory`（决定“记什么”）**：它不存储原始数据，而是决定**哪些消息需要保留给模型作为上下文**。比如只保留最近 20 条，还是保留全部系统消息。
+ **`ChatMemoryRepository`（负责“存哪里”）**：这是实际的存储层。`ChatMemory` 把要记的内容交给它，由它负责持久化到内存、JDBC、Redis 等地方。**对于记忆持久化在后续章节会详细讲解，这里先了解下 ChatMemory。**

#### `MessageWindowChatMemory`
这是 Spring AI 默认且最常用的实现。它维护一个“消息窗口”，只保留**最近 N 条**消息（默认 20 条）。当新消息进来导致总数超标时，它会**自动淘汰最旧的消息**，但会特殊对待 `SystemMessage`：如果有新的系统消息，旧的会被清除；淘汰时系统消息会被优先保留。

自己试：先问「我叫xx」，再问「我叫什么？」——同一个 `conversationId` 才答得上。

#### 两个容易踩的坑
1. **会话隔离**：必须给每个独立的对话（比如每个用户或每个会话）设置**唯一的 `conversationId`**。如果都用默认值或固定值，会导致用户 A 看到用户 B 的聊天记录，这是严重的安全问题。
2. **Token 成本**：窗口设置得越大，每次请求携带的历史消息就越多，Token 消耗会线性增长，还可能让模型“分心”。生产环境中建议根据场景控制在 **10~50 条**之间。

### 样例
```plain
package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * @author zk
 * @date 2026/9/30 14:28
 */
@Tag(name = "Stage01-1.5 Chat Memory", description = "大模型会话记忆")
@RestController
public class EMemoryDemo {

    /** 手动管理会话记忆：默认窗口实现，生产可换成 Redis/JDBC 的 ChatMemory */
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(20) // 只留最近 20 条消息，超出丢最旧的
            .build();

    private final ChatClient chatClient;

    public EMemoryDemo(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/api/s15/chat")
    public String chat(@RequestParam(defaultValue = "我是xxx") String q,
                       @RequestParam(defaultValue = "c-001") String conversationId) {

        // 1. 取出该会话已有的历史消息（没有则返回空 List）
        List<Message> history = chatMemory.get(conversationId);

        // 2. 历史 + 本轮用户输入，组装成本次请求的完整消息列表
        List<Message> messages = new ArrayList<>(history);
        messages.add(new UserMessage(q));

        // 3. 调模型
        String answer = chatClient.prompt()
                .messages(messages)
                .call()
                .content();

        // 4. 手动把本轮 user + assistant 两条消息写回记忆
        //    下次同一个 conversationId 进来就能"续聊"
        chatMemory.add(conversationId, List.of(
                new UserMessage(q),
                new AssistantMessage(answer)
        ));

        return answer;
    }

    /** 清空某个会话的记忆，方便演示 */
    @GetMapping("/api/s15/clear")
    public String clear(@RequestParam(defaultValue = "c-001") String conversationId) {
        chatMemory.clear(conversationId);
        return "cleared: " + conversationId;
    }
}
```

---

## 1.6 Advisors
**Advisor 是一个核心的拦截器机制**，用于在 AI 请求发送到模型之前，以及响应返回给客户端之前，对交互流程进行拦截、修改和增强。

你可以把它类比为 **Servlet Filter、Spring MVC Interceptor 或 AOP 切面**，只不过它拦截的不是 HTTP 请求或 Bean 方法，而是**模型调用链**。

它的主要优势包括：

+ **封装重复性任务**：将对话记忆、检索增强等常见模式打包，避免重复代码。
+ **转换数据**：增强发送给大语言模型（LLM）的请求，并格式化返回给客户端的响应。
+ **提供可移植性**：创建的转换组件可以跨不同的 AI 模型和用例工作。

Advisor 以**链式结构（Chain）** 运行，执行顺序由 `getOrder()` 方法决定，**值越小越先执行**。整个流程类似“**洋葱模型**”：

1. **请求阶段**：用户的 Prompt 被封装为 `ChatClientRequest`，依次通过链中的每个 Advisor。每个 Advisor 都可以**修改请求**，也可以选择**阻止请求**（不调用下一个 Advisor）。
2. **模型调用**：链中最后一个 Advisor（由框架自动添加）负责将请求发送给 Chat Model。
3. **响应阶段**：模型返回的响应被封装为 `ChatClientResponse`，再**反向**通过 Advisor 链。每个 Advisor 都可以对响应进行处理或修改，最终返回给客户端。

#### 核心组件与内置 Advisor
Advisor API 的核心接口分为**非流式**（`CallAdvisor`）和**流式**（`StreamAdvisor`）两类。

Spring AI 提供了一系列开箱即用的内置 Advisor，用于处理常见场景：

+ **对话记忆（Chat Memory）**：如 `MessageChatMemoryAdvisor`、`PromptChatMemoryAdvisor`、`VectorStoreChatMemoryAdvisor`，用于管理对话历史，实现多轮上下文。
+ **检索增强生成（RAG）**：如 `QuestionAnswerAdvisor`、`RetrievalAugmentationAdvisor`，通过检索外部知识库来增强回答的准确性。关于什么是检索增强生成（RAG）下一章讲解。
+ **日志记录（Logging）**：如 `SimpleLoggerAdvisor`，用于记录请求和响应的详细信息。
+ **安全护栏（Guardrails）**：如 `SafeGuardAdvisor`，可以基于敏感词等规则，在请求到达模型前进行拦截，防止生成不当内容。

#### 进阶：递归 Advisor（Recursive Advisors）
从版本 1.1.0-M4 开始，Spring AI 引入了**递归 Advisor**，用于支持需要**多次迭代**的复杂工作流。

与普通 Advisor 的单次执行不同，递归 Advisor 可以**多次循环执行下游的 Advisor 链**，重复调用 LLM，直到满足某个条件。这适用于：

+ **工具调用循环**：按顺序执行多个工具，每个工具的输出为下一个决策提供信息。
+ **输出验证与重试**：验证结构化响应，若验证失败则通过反馈重试。
+ **智能体循环**：构建能自主规划、执行和迭代任务的 AI 代理。

总的来说，Spring AI 的 Advisor 提供了一种**标准化、可组合**的方式来治理 AI 交互流程，将记忆、安全、日志等横切关注点从业务逻辑中解耦，是构建企业级 AI 应用的重要基础组件。

### 样例讲解
`SimpleLoggerAdvisor` 默认使用 **DEBUG 级别** 输出日志，而 Spring Boot 默认只显示 INFO 及以上级别，所以在控制台里什么都看不到，它的作用完全没有体现出来。

有两种方式可以让它显现效果：

方式一：配置日志级别（最简单）

在 `application.yml` 中把 `SimpleLoggerAdvisor` 所在包的日志级别调成 `DEBUG`：

```plain
logging:
  level:
    org.springframework.ai.chat.client.advisor: DEBUG
```

这种方式的缺点是：日志**格式冗长、嵌套很深**，而且默认是 DEBUG，生产环境不想开。教学演示也不够直观。

方式二：自定义 LoggerAdvisor（推荐）

自己写一个简易的 `LoggerAdvisor`，用 **INFO 级别** 输出，格式清晰，既能体现 Advisor 的工作原理，也方便直接看到日志效果，下面样例采用这种方式实现打印日志。

**控制台日志效果:**

调用 `GET /api/s16/chat?q=我是谁`，控制台会输出：

 >>> [MyLoggerAdvisor] 进入请求阶段 | order=0 | 用户输入：我是谁

 >>> [MyAuditAdvisor] 进入请求阶段 | order=10 | 记录审计信息

    —— 到这里才真正调用大模型 ——

 <<< [MyAuditAdvisor] 进入响应阶段 | order=10 | 完成审计

 <<< [MyLoggerAdvisor] 进入响应阶段 | order=0 | 模型输出：xxxxxx

**请求阶段 `>>>` 按 order 从小到大（0→10→20→30）进入；响应阶段 `<<<` 按 order 从大到小（30→20→10→0）退出。** 一进一出、方向相反，这就是"洋葱模型"最直观的体现。

如果调用 `/api/s16/chat-guard?q=暴力内容`，日志会变成：

>>> [MyLoggerAdvisor] 进入请求阶段 | order=0 | 用户输入：暴力内容

>>> [MyAuditAdvisor] 进入请求阶段 | order=10 | 记录审计信息

（命中敏感词，直接返回兜底语，不再往内层走）

<<< [MyAuditAdvisor] 进入响应阶段 | order=10 | 完成审计

<<< [MyLoggerAdvisor] 进入响应阶段 | order=0 | 模型输出：抱歉，您的问题包含敏感内容，我无法回答。

### 样例
```plain
package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Advisors 讲解示例
 *
 * 本示例通过自定义 Advisor + 不同 order，直观展示"洋葱模型"：
 *
 *   请求方向（order 从小到大）：
 *     MyLoggerAdvisor(0) -> MyAuditAdvisor(10) -> SafeGuardAdvisor(20) -> MessageMemory(30) -> 模型
 *
 *   响应方向（order 从大到小）：
 *     模型 -> MessageMemory(30) -> SafeGuardAdvisor(20) -> MyAuditAdvisor(10) -> MyLoggerAdvisor(0)
 *
 * 控制台会看到成对出现的 ">>>" 和 "<<<" 日志，且顺序正好相反。
 */
@Tag(name = "Stage01-1.6 Advisors", description = "Advisors拦截器")
@RestController
public class FAdvisorDemo {

    private final ChatClient chatClient;

    public FAdvisorDemo(ChatClient.Builder builder) {

        // ---------- 1. 对话记忆 ----------
        ChatMemory memory = MessageWindowChatMemory.builder().build();

        // ---------- 2. 自定义日志 Advisor（order=0，最先进入、最后退出） ----------
        //SimpleLoggerAdvisor 默认使用 DEBUG 级别 输出日志，而 Spring Boot 默认只显示 INFO 及以上级别,自定义 LoggerAdvisor,用 INFO 级别 输出
        MyLoggerAdvisor loggerAdvisor = new MyLoggerAdvisor(0);

        // ---------- 3. 自定义审计 Advisor（order=10） ----------
        MyAuditAdvisor auditAdvisor = new MyAuditAdvisor(10);

        // ---------- 4. 安全护栏（order=20） ----------
        SafeGuardAdvisor safeGuardAdvisor = SafeGuardAdvisor.builder()
                .sensitiveWords(List.of("暴力", "违禁词", "敏感内容"))
                .failureResponse("抱歉，您的问题包含敏感内容，我无法回答。")
                .order(20)// 显式指定：order为20
                .build();

        // ---------- 5. 消息记忆 Advisor（order=30，最后进入、最先退出） ----------
        MessageChatMemoryAdvisor messageMemoryAdvisor =
                MessageChatMemoryAdvisor
                        .builder(memory)
                        .order(30)// 显式指定：order为30
                        .build();

        // ---------- 6. 组装默认链 ----------
        // 显式指定 order，让洋葱模型的层次更清晰。
        this.chatClient = builder
                .defaultAdvisors(
                        loggerAdvisor,          // order=0  最外层
                        auditAdvisor,           // order=10
                        safeGuardAdvisor,       // order=20
                        messageMemoryAdvisor    // order=30 最内层
                )
                .build();
    }

    /**
     * 基础对话接口。
     * 访问示例：/api/s16/chat?q=你好
     */
    @GetMapping("/api/s16/chat")
    public String chat(@RequestParam String q,
                       @RequestParam(defaultValue = "adv-1") String conversationId) {
        return chatClient.prompt()
                .user(q)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    /**
     * 演示 SafeGuardAdvisor 触发敏感词拦截。
     * 观察日志：命中敏感词后，响应会从 SafeGuardAdvisor 层直接往回走，
     * 后面的 MessageChatMemoryAdvisor 不会再执行。
     * 访问示例：/api/s16/chat-guard?q=暴力内容
     */
    @GetMapping("/api/s16/chat-guard")
    public String chatWithGuard(@RequestParam String q,
                                @RequestParam(defaultValue = "adv-1") String conversationId) {
        return chatClient.prompt()
                .user(q)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}

/**
 * 自定义日志 Advisor。
 *
 * 特点：
 * - 使用 INFO 级别，无需修改 yml 日志配置就能看到；
 * - 通过 ">>>" 和 "<<<" 前缀直观展示"进入请求 / 退出响应"的洋葱模型；
 * - 打印用户输入、模型输出以及当前 Advisor 的执行顺序。
 */
 class MyLoggerAdvisor implements BaseAdvisor {

     private static final Logger log = LoggerFactory.getLogger(MyLoggerAdvisor.class);

     /**
      * 执行顺序，值越小越先执行
      */
     private final int order;

     public MyLoggerAdvisor() {
         this(0);
     }

     public MyLoggerAdvisor(int order) {
         this.order = order;
     }

     @Override
     public String getName() {
         return "MyLoggerAdvisor";
     }

     @Override
     public int getOrder() {
         return this.order;
     }

     /**
      * 请求阶段：正向执行，order 越小越先被调用。
      */
     @Override
     public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
         String userText = request.prompt().getUserMessage().getText();
         log.info(">>> [{}] 进入请求阶段 | order={} | 用户输入：{}",
                 getName(), getOrder(), userText);
         return request;
     }

     /**
      * 响应阶段：反向执行，order 越小越后被调用。
      */
     @Override
     public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
         String text = response.chatResponse().getResult().getOutput().getText();
         log.info("<<< [{}] 进入响应阶段 | order={} | 模型输出：{}",
                 getName(), getOrder(), text);
         return response;
     }
 }

/**
 * 自定义审计 Advisor。
 *
 * 用来和 MyLoggerAdvisor 搭配，展示 Advisor 链的多层"洋葱"结构：
 * 请求阶段按 order 从小到大依次进入，
 * 响应阶段按 order 从大到小依次退出。
 */
 class MyAuditAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MyAuditAdvisor.class);

    private final int order;

    public MyAuditAdvisor(int order) {
        this.order = order;
    }

    @Override
    public String getName() {
        return "MyAuditAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        log.info(">>> [{}] 进入请求阶段 | order={} | 记录审计信息",
                getName(), getOrder());
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        log.info("<<< [{}] 进入响应阶段 | order={} | 完成审计",
                getName(), getOrder());
        return response;
    }
}

```

## 1.7 Embedding / VectorStore / RAG
了解RAG之前先说说大模型的几个硬伤：知识有截止日期、不懂你的私有数据、不知道时会一本正经地编造（幻觉）、上下文窗口有限且长文本又贵又慢。正因为这些缺点，直接拿大模型做企业问答这类功能会不准确、不可控、成本高。

假设你做了一个基于大模型的客服机器人，用户问：

"你们公司最新的退货政策是什么？"

大模型会怎么回答？

+ 它**不知道**你公司的退货政策——训练数据里根本没有。
+ 如果硬答，它会**编一个看起来很像真的**答案——这就是"幻觉"。
+ 就算你把政策文档贴在 Prompt 里，文档可能有 200 页，**塞不进上下文窗口**，或者塞进去成本极高。

这不是个别问题，而是**所有大模型应用的共性困境**。RAG 就是为解决这个问题而生的。

**RAG是什么**一句话概括：

**在让大模型回答问题之前，先从外部知识库中检索出相关资料，把资料和问题一起交给模型，让它"看着资料回答"。**

面对"让模型知道新知识"的需求，常见方案有三种：

| **方案** | **做法** | **优点** | **缺点** |
| --- | --- | --- | --- |
| **微调（Fine-tuning）** | **用新数据继续训练模型** | **效果好、风格一致** | **成本高、周期长、更新慢、需要 GPU** |
| **长上下文（Long Context）** | **把资料全塞进 Prompt** | **简单直接** | **贵、慢、有窗口上限、效果不稳定** |
| **RAG** | **检索相关片段再生成** | **便宜、快、易更新、可溯源** | **依赖检索质量** |

**RAG 的核心优势：**

1. **知识实时更新：改知识库即可，不用重新训练模型。**
2. **成本低：不用 GPU 训练，只增加少量检索和 token 成本。**
3. **可溯源：能告诉用户"这个答案来自哪份文档"，便于审计。**
4. **可控：知识库里有什么，模型就只能用什么，减少幻觉。**
5. **权限隔离：不同用户检索不同知识库，天然支持多租户。这就是为什么 RAG 成了企业级 AI 应用的事实标准——几乎所有"基于内部文档的问答助手"底层都是 RAG。**

而**Embedding / VectorStore / RAG **这三个概念是构建知识库问答、文档助手这类 AI 应用的核心技术栈，它们是一条链上的三个环节。先用一个类比把它们串起来，再逐个拆开讲。

假设你要做一个"公司内部文档问答助手"，让 AI 能回答关于公司制度、产品手册的问题。大模型本身并不知道你公司的内部资料，怎么办？

+ **Embedding（嵌入）**：把每段文字变成一串数字（向量），就像给每本书编一个"内容指纹"。
+ **VectorStore（向量存储）**：把这些"指纹"存进一个专门的数据库，就像图书馆的索引系统。
+ **RAG（检索增强生成）**：用户提问时，先从这个索引里找出最相关的几段资料，再把它们塞进 Prompt 一起发给大模型，让模型"看着资料回答"。

三者是一条流水线：**Embedding 负责"翻译"，VectorStore 负责"存储和检索"，RAG 负责"组装并调用模型"**。

### 1. Embedding（嵌入）
Embedding 是**把文本转换成一串浮点数（向量）的过程**。比如：

```plain
"我喜欢猫"  →  [0.12, -0.45, 0.78, 0.03, ...]   （通常 768 或 1536 维）
"我讨厌猫"  →  [0.11, -0.44, 0.76, 0.05, ...]   （和上一句很接近）
"今天天气真好" → [-0.62, 0.31, -0.09, 0.55, ...] （和上面差很远）
```

**为什么有用**

关键在于：**语义相近的文本，向量距离也相近**。这意味着计算机可以用数学方式判断"两段文字是不是在说同一件事"，而不需要理解文字本身。

+ "如何请假" 和 "请假流程是什么" → 向量很接近
+ "如何请假" 和 "报销标准" → 向量差很远

衡量接近程度常用**余弦相似度**，值域 -1 到 1，越接近 1 越相似。

**在 Spring AI 里**

```plain
// EmbeddingModel 就是干这个的
float[] vector = embeddingModel.embed("我喜欢猫");
```

`EmbeddingModel` 是接口，具体实现由你选用的模型决定：OpenAI 的 `text-embedding-3-small`、Ollama 的 `nomic-embed-text`、DashScope 的 `text-embedding-v3` 等。

### 2. VectorStore（向量存储）
VectorStore 是**专门存储向量并提供相似度检索的数据库**。你往里面存文本，它会自动调用 EmbeddingModel 把文本转成向量存起来；你查询时，它把查询语句也转成向量，然后找出最相似的若干条。

**为什么不用普通数据库？**

普通数据库擅长精确匹配（`WHERE name = '张三'`），但没法回答"哪段文字和这句话语义最接近"。向量检索是**按相似度排序**，这是关系型数据库不擅长的。

**常见实现有:**

| 类型 | 代表 | 适用场景 |
| --- | --- | --- |
| 内存 | `SimpleVectorStore` | 开发测试、小数据量 |
| 关系库扩展 | PGVector、Redis | 已有 PG/Redis，想轻量接入 |
| 专业向量库 | Milvus、Qdrant、Chroma | 生产环境、大数据量、高并发 |
| 云服务 | Pinecone、阿里云 DashVector | 免运维 |

**在 Spring AI 里**

```plain
// 存
vectorStore.add(List.of(
    new Document("公司年假为 10 天"),
    new Document("请假需提前 3 天申请")
));

// 查：找出和"请假"最相关的 3 条
List<Document> results = vectorStore.similaritySearch(
    SearchRequest.builder().query("请假").topK(3).build()
);
```

`VectorStore` 是接口，`SimpleVectorStore`、`PgVectorStore`、`RedisVectorStore` 等都是它的实现。切换存储方案时，业务代码几乎不用改。

### 3. RAG（检索增强生成）
RAG = **Retrieval-Augmented Generation**，检索增强生成。它是**一种应用架构模式**，不是某个具体技术。

核心思想：**大模型回答之前，先帮它把相关资料找出来，一起塞进 Prompt。**

为什么需要 RAG?

大模型有两个天然短板：

1. **知识截止**：训练数据有时间截止点，不知道最新信息。
2. **不知道私有数据**：不知道你公司的内部文档、你的个人笔记。
3. **容易幻觉**：不知道时会一本正经地编造。

RAG 通过"先检索、再生成"解决这些问题：让模型**基于检索到的真实资料**回答，而不是凭记忆瞎编。

**完整流程**

```plain
【离线阶段：建库】
  文档 → 切分成片段 → Embedding → 存入 VectorStore

【在线阶段：问答】
  用户提问
    ↓
  问题 → Embedding → 在 VectorStore 中检索最相似的 N 个片段
    ↓
  把「检索到的片段」+「用户问题」拼成一个 Prompt
    ↓
  发送给大模型
    ↓
  模型基于这些片段生成回答
```

举个例子,用户问："公司年假多少天？"

**没有 RAG**：模型只能瞎猜，或者回答"我不知道"。

**有 RAG**：

1. 把"公司年假多少天"转成向量，去 VectorStore 检索。
2. 找到最相关的片段："公司年假为 10 天，入职满 3 年增至 15 天。"
3. 拼成 Prompt：

```plain
请根据以下资料回答问题。

资料：
公司年假为 10 天，入职满 3 年增至 15 天。

问题：公司年假多少天？
```

4. 模型回答："根据公司规定，年假为 10 天，入职满 3 年后增至 15 天。"

回答**准确、可溯源**，这就是 RAG 的价值。

### 4. 三者的关系
```plain
┌─────────────────────────────────────┐
        │              RAG（架构）             │
        │                                     │
        │   ┌──────────┐    ┌──────────────┐  │
        │   │Embedding │───→│ VectorStore  │  │
        │   │  （翻译） │    │（存储+检索）  │  │
        │   └──────────┘    └──────────────┘  │
        │        ↑                  ↓         │
        │     文本/问题         相似片段        │
        │                          ↓          │
        │                   拼进 Prompt → LLM  │
        └─────────────────────────────────────┘
```

+ **Embedding** 是底层能力，把文本变成可比较的向量。
+ **VectorStore** 是基于 Embedding 的存储与检索设施。
+ **RAG** 是把 Embedding + VectorStore + 大模型串起来的应用架构。

### 5. 回到 Spring AI
在 Spring AI 中，这三者分别对应：

| 概念 | Spring AI 接口/类 | 作用 |
| --- | --- | --- |
| Embedding | `EmbeddingModel` | 文本转向量 |
| VectorStore | `VectorStore`、`SimpleVectorStore`、`PgVectorStore`… | 存向量、相似度检索 |
| RAG | `QuestionAnswerAdvisor`、`RetrievalAugmentationAdvisor` | 把检索结果自动注入 Prompt |

**关键点**：在 Spring AI 里，RAG 通常不需要你手写"检索 + 拼 Prompt"的流程，而是通过 **Advisor** 自动完成。它会拦截用户请求，自动去 VectorStore 检索，把结果拼进 Prompt，再交给模型。

```plain
QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
        .searchRequest(SearchRequest.builder().topK(6).build())
        .build();

ChatClient client = builder.defaultAdvisors(qaAdvisor).build();
```

一行 `defaultAdvisors` 就把 RAG 流程串起来了，底层的 Embedding、检索、Prompt 拼接都被 Advisor 封装掉了

### 6. TopK、相似度阈值、简单重排序与降级策略
RAG 的在线阶段，用户问一句话，系统要在向量库里找到"最相关"的片段。但"最相关"是个模糊概念，需要几个参数来量化：

+ **相似度**：两段文字有多像（0~1，越接近 1 越像）
+ **TopK**：返回几条
+ **相似度阈值**：低于多少分就不要了

这三个参数共同决定了"检索到什么、检索多少、检索质量如何"。

#### 6.1 TopK

TopK 就是**检索时返回相似度最高的前 K 条片段**：
+ **太小（K=1）**：只返回一条，如果这条恰好不完整或不是最优，模型就得不到足够信息。
+ **太大（K=20）**：返回太多，噪声多、Prompt 变长、成本升高，还可能稀释重点。
+ **经验值**：通常 3~8 条。具体取决于片段大小和知识库密度。

TopK 就像搜索引擎的"第一页显示几条结果"。显示 1 条太少，显示 100 条没人看，显示 10 条刚好。

#### 6.2 相似度阈值

相似度阈值是**过滤门槛**：只有相似度 ≥ 阈值的片段才会被返回。
TopK 只保证"返回前 K 条"，但**不保证这 K 条真的相关**。如果知识库里根本没有相关内容，TopK 也会硬塞给你 K 条最不相关的片段。

阈值的作用是：**宁可返回 0 条，也不要返回不相关的**。

举例，用户问："公司年假多少天？"

| 片段 | 相似度 | 阈值 0.7 下 |
| --- | --- | --- |
| "公司年假为 10 天" | 0.92 | ✅ 保留 |
| "请假需提前 3 天申请" | 0.78 | ✅ 保留 |
| "今天天气真好" | 0.31 | ❌ 丢弃 |
| "公司食堂菜单" | 0.25 | ❌ 丢弃 |

如果没有阈值，后两条也会被塞进 Prompt，干扰模型判断。

**阈值怎么选**

+ **太高（0.9）**：可能一条都检索不到，导致"明明有资料却答不上来"。
+ **太低（0.3）**：噪声大量涌入，模型被无关信息干扰。
+ **经验值**：0.6~0.8。但**不同 Embedding 模型的分数分布不同**，需要实测调整。

#### 6.3 简单重排序（Re-ranking）
向量检索是**粗排**：它用向量距离快速筛出候选。但向量相似度高，不代表**语义上真的最相关**。

**重排序是在粗排结果之上，用更精细的方法重新打分排序。**

向量检索的局限：

1. **只看整体语义，抓不住关键词精确匹配。**
2. **对否定、条件、细节不敏感。比如"不含糖的饮料"和"含糖的饮料"向量可能很接近。**
3. **TopK 顺序未必正确：排第一的可能不如排第三的相关。**

简单重排序的做法不引入额外模型，用规则做二次排序：

```plain
// 伪代码：对检索结果做简单重排
List<Document> results = vectorStore.similaritySearch(request);

// 策略1：关键词命中加分
results.sort((a, b) -> {
    int scoreA = countKeywordHits(a, query);
    int scoreB = countKeywordHits(b, query);
    return scoreB - scoreA;
});

// 策略2：按文档新鲜度排序
results.sort(Comparator.comparing(Document::getCreatedAt).reversed());

// 策略3：按来源权威性排序（官方文档 > 用户评论）
results.sort(Comparator.comparing(Document::getSource).reversed());
```

**与"模型重排序"的区别**

| **类型** | **做法** | **成本** | **效果** |
| --- | --- | --- | --- |
| **简单重排序** | **规则、关键词、元数据** | **低** | **一般** |
| **模型重排序** | **用 Cross-Encoder 模型重新打分** | **高** | **好** |

**生产环境常用"向量粗排 Top50 → 模型精排 Top5"的两阶段策略。教学阶段了解简单重排序即可。**

#### 6.4 幻觉

RAG 也不能完全避免幻觉（Hallucination）：模型生成了**看起来合理但实际错误**的内容。

RAG 场景下的幻觉来源

1. **检索到了不相关内容**，模型硬着头皮编。
2. **检索到了相关内容，但模型理解错了**。
3. **检索不到内容**，模型不承认"不知道"，而是凭训练记忆瞎答。
4. **多个片段互相矛盾**，模型自行"调和"出一个错误答案。

**RAG 减少幻觉，但不消除幻觉。**

RAG 把"凭记忆瞎编"变成"基于资料回答"，但如果资料本身有问题、检索不准、或模型不老实，幻觉依然存在。

**缓解手段**

+ 提高检索质量（阈值、重排序）
+ Prompt 中明确要求"只根据资料回答，资料没有就说不知道"
+ 在回答中标注来源，便于人工核验
+ 检索不到时走降级策略

#### 6.5 检索不到时的降级策略
这是 RAG 工程中最容易被忽视、但生产环境必须处理的问题。

什么时候会"检索不到"

+ 知识库里确实没有相关内容
+ 相似度阈值设太高，把相关片段也过滤了
+ 用户问的是闲聊、通用问题，知识库里没有对应内容

如果不处理会怎样

模型拿到空的资料，但 Prompt 里写着"请根据以下资料回答"，于是它：

+ 要么编一个答案（幻觉）
+ 要么输出奇怪的格式
+ 要么答非所问

**常见降级策略策略1：明确告知"没有找到相关资料"**

检索到 0 条 → 直接返回："抱歉，知识库中没有找到相关信息，请换个问法或联系人工客服。"

**策略2：降级为通用对话**

```plain
检索到 0 条 → 不注入任何资料，让模型用通用能力回答，
              但标注"以下回答不基于知识库，仅供参考"
```

**策略3：放宽阈值重试**

```plain
第一次检索（阈值 0.8）→ 0 条
    ↓ 自动降级
第二次检索（阈值 0.5）→ 3 条 → 用这些回答，但提示"匹配度较低"
```

**策略4：回退到关键词检索**

向量检索 0 条 → 改用 关键词检索 → 找到 2 条

**策略5：澄清式追问**

检索 0 条 → "您是想问关于 X 还是 Y？能否补充一下背景？"

**在 Spring AI 里怎么实现**

`QuestionAnswerAdvisor` 本身不直接提供降级逻辑，通常通过**自定义 Advisor** 在 `before` 阶段判断检索结果：

```plain
public class MyRagAdvisor implements BaseAdvisor {

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        List<Document> docs = vectorStore.similaritySearch(...);

        if (docs.isEmpty()) {
            // 降级：直接返回兜底响应，不调用模型
            // 或修改 Prompt，要求模型说"不知道"
        }
        // 正常情况：把 docs 拼进 Prompt
        return request;
    }
}
```

#### 6.6 把五个概念串起来
```plain
用户提问
    ↓
向量检索
    ↓
┌─────────────────────────────────────┐
│  TopK：取前 K 条                     │
│  相似度阈值：过滤低分               │
│  （两者共同决定候选集）              │
└─────────────────────────────────────┘
    ↓
简单重排序（关键词 / 元数据 / 新鲜度）
    ↓
┌─────────────────────────────────────┐
│  候选集为空？                        │
│    → 是：走降级策略                  │
│    → 否：拼进 Prompt                 │
└─────────────────────────────────────┘
    ↓
模型生成回答
    ↓
可能仍有幻觉 → 靠 Prompt 约束 + 来源标注 + 人工核验
```

### 样例讲解
`QuestionAnswerAdvisor`因为它**不在 Spring AI 的核心依赖中**，而是被拆分到了独立的 `spring-ai-advisors-vector-store` 模块里。

其他 Advisor（如 `SimpleLoggerAdvisor`、`MessageChatMemoryAdvisor`、`SafeGuardAdvisor`）属于核心基础功能，随 `spring-ai-client-chat` 或 `spring-ai-core` 自动引入，所以不会报错。而这两个 Advisor 专门服务于 **RAG（检索增强生成）** 场景，需要额外引入向量存储相关的依赖。

在 `pom.xml` 中添加以下依赖即可：

```plain
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-advisors-vector-store</artifactId>
</dependency>
```

Spring AI 的 BOM（`spring-ai-bom`）会统一管理版本号，所以通常不需要手动指定 `<version>`。

示例里没有手动创建 `EmbeddingModel`，但它能被注入，是因为 Spring AI 的**自动配置（Auto-Configuration）** 机制在背后帮你完成了装配。

**你不需要手动创建 `EmbeddingModel`，是因为 Spring AI 的 Starter 依赖引入了自动配置类，它根据你的 `application.yml` 配置和条件注解，在容器中自动注册了一个 `EmbeddingModel` Bean。** 你的示例能直接注入 `EmbeddingModel` 参数，正是这个机制在起作用。

如果想手动配置你完全可以手动定义一个 `EmbeddingModel` Bean，自动配置会因为 `@ConditionalOnMissingBean` 而退让：

```plain
@Bean
public EmbeddingModel embeddingModel() {
    return new OpenAiEmbeddingModel(
        new OpenAiApi(System.getenv("OPENAI_API_KEY"))
    );
}
```

手动定义的好处是：可以精确控制超时、重试、自定义 HTTP 客户端等参数；坏处是失去了自动配置的便捷性，需要自己管理所有配置项。

##### 知识库有的问题，走 QuestionAnswerAdvisor 自动检索增强

GET /api/s16/rag/chat?q=公司年假多少天

##### 知识库没有的问题，观察模型是否幻觉

GET /api/s16/rag/chat?q=食堂今天吃什么

##### 降级版本：有资料正常回答

GET /api/s16/rag/chat-fallback?q=报销流程

##### 降级版本：无资料触发兜底

GET /api/s16/rag/chat-fallback?q=食堂今天吃什么

### 样例
```plain
package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 演示控制器（基于 SimpleVectorStore，无需外部向量数据库）。
 *
 * SimpleVectorStore 是 Spring AI 内置的内存向量存储：
 * - 数据存在 JVM 内存中，应用重启即丢失，仅用于开发测试和教学演示；
 * - 依赖 EmbeddingModel 把文本转成向量，EmbeddingModel 由 Spring AI 自动配置注入。
 *
 * 本控制器演示两种场景：
 * 1. 正常 RAG：QuestionAnswerAdvisor 自动完成"检索 + 拼 Prompt + 调用模型"。
 * 2. 降级处理：检索为空时返回兜底提示，而不是让模型自由发挥（避免幻觉）。
 */
@Tag(name = "Stage01-1.7 RAG 演示", description = "基于 SimpleVectorStore 的 RAG 演示")
@RestController
public class GRagDemo {

    private static final Logger log = LoggerFactory.getLogger(GRagDemo.class);

    /** 相似度阈值：低于此值的片段被丢弃（内存演示用 0.5，生产需按模型实测调整） */
    private static final double SIMILARITY_THRESHOLD = 0.5d;

    /** TopK：最多返回几条最相关片段 */
    private static final int TOP_K = 3;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    /**
     * 构造注入：
     * - ChatClient.Builder：Spring AI 自动配置提供；
     * - EmbeddingModel：用于把文本转成向量，SimpleVectorStore 依赖它工作。
     */
    public GRagDemo(ChatClient.Builder builder, EmbeddingModel embeddingModel) {

        // ---------- 1. 创建内存向量存储 ----------
        this.vectorStore = SimpleVectorStore.builder(embeddingModel).build();

        // ---------- 2. 创建 RAG Advisor ----------
        // QuestionAnswerAdvisor 会自动：检索向量库 → 把相关片段拼进 Prompt → 调用模型
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .topK(TOP_K)
                        .build())
                .build();

        // ---------- 3. 构建 ChatClient ----------
        this.chatClient = builder
                .defaultAdvisors(qaAdvisor)
                .build();
    }

    /**
     * 预置知识库文档。
     * 使用 @PostConstruct 保证在构造完成后、请求到达前执行。
     * 实际项目中这些文档来自 PDF、数据库或爬虫，这里手动写入几条用于演示。
     */
    @PostConstruct
    public void initKnowledgeBase() {
        List<Document> documents = List.of(
                new Document("公司年假为 10 天，入职满 3 年后增至 15 天，满 5 年增至 20 天。"),
                new Document("请假需提前 3 个工作日通过 OA 系统提交申请，由直属主管审批。"),
                new Document("公司报销流程：在 OA 系统提交报销申请，附上发票原件照片，直属主管审批后由财务部复核，3 个工作日内打款。"),
                new Document("差旅报销标准：一线城市住宿每晚不超过 500 元，二线城市不超过 350 元。"),
                new Document("年终奖于每年 1 月 15 日发放，计算基数为上一年度 12 个月的平均月薪。"),
                new Document("五险一金按照国家规定缴纳，公积金缴存比例为 12%。")
        );

        vectorStore.add(documents);
        log.info("【RAG 初始化】已向 SimpleVectorStore 写入 {} 条知识文档", documents.size());
    }

    /**
     * 正常 RAG 问答。
     * QuestionAnswerAdvisor 自动完成检索和 Prompt 增强，代码上和一个普通对话没区别。
     *
     * 访问示例：
     *   /api/s16/rag/chat?q=公司年假多少天     ← 知识库有，基于资料回答
     *   /api/s16/rag/chat?q=食堂今天吃什么     ← 知识库没有，观察模型如何回答
     */
    @GetMapping("/api/s16/rag/chat")
    public String chat(@RequestParam String q) {
        log.info("【RAG 请求】用户提问：{}", q);
        return chatClient.prompt()
                .user(q)
                .call()
                .content();
    }

    /**
     * 降级演示：先手动检索，检索为空则直接返回兜底提示，不调用模型。
     *
     * 为什么需要降级？
     * 当知识库里没有相关内容时，如果直接交给模型，模型可能凭训练记忆编造答案（幻觉）。
     * 正确做法是：检索为空 → 明确告知用户"没有找到相关资料"。
     *
     * 访问示例：
     *   /api/s16/rag/chat-fallback?q=公司年假多少天   ← 有资料，正常回答
     *   /api/s16/rag/chat-fallback?q=食堂今天吃什么   ← 无资料，触发降级
     */
    @GetMapping("/api/s16/rag/chat-fallback")
    public String chatWithFallback(@RequestParam String q) {
        log.info("【RAG 降级演示】用户提问：{}", q);

        // 1. 手动执行相似度检索
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(q)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .topK(TOP_K)
                        .build()
        );

        // 2. 检索为空 → 降级：直接返回兜底提示，不调用模型
        if (docs == null || docs.isEmpty()) {
            log.warn("【RAG 降级】检索为空，触发兜底响应。query={}", q);
            return "抱歉，知识库中没有找到与您问题相关的资料。"
                    + "请尝试换个问法，或联系人工客服获取帮助。";
        }

        // 3. 检索到内容 → 打印命中片段，便于观察
        log.info("【RAG 检索】命中 {} 条片段：", docs.size());
        for (Document doc : docs) {
            log.info("  - {}", doc.getText());
        }

        // 4. 把检索到的片段拼进 Prompt，交给模型生成
        StringBuilder context = new StringBuilder();
        for (Document doc : docs) {
            context.append(doc.getText()).append("\n");
        }

        String prompt = """
                请根据以下资料回答问题。如果资料中没有相关信息，请明确说明"资料中未提及"。

                资料：
                %s

                问题：%s
                """.formatted(context, q);

        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}
```

## 1.8 多模态基础

**多模态（Multimodal）** 指的是 AI 模型能够同时理解和/或生成**多种类型的信息**，而不是只处理单一类型。
这里的“模态”（Modality）就是信息的形态，常见的有：

| 模态 | 例子 |
| --- | --- |
| 文本 | 句子、文档、代码 |
| 图像 | 照片、截图、图表 |
| 音频 | 语音、音乐、环境声 |
| 视频 | 短视频、监控录像 |
| 其他 | 表格、PDF、3D 模型等 |

### 单模态 vs 多模态
+ **单模态模型**：只能处理一种输入。比如早期的纯文本大模型，你只能发文字，它也只能回文字。
+ **多模态模型**：可以同时接收多种输入，甚至生成多种输出。比如你发一张图 + 一句话问“这是什么”，它能看图回答；再比如它可以用语音回复你。

多模态又分几个层次：

1. **多模态理解**：能看懂图片、听懂语音、理解视频内容。
2. **多模态生成**：能生成图片、语音、视频。
3. **跨模态转换**：比如文字生成图片、图片生成描述、语音转文字。

现实世界的信息本来就不是纯文本的。医生看片子、客服听录音、教育看板书、电商看商品图——如果 AI 只能读文字，很多场景就做不了。

多模态让 AI 更接近人的感知方式：**眼睛看、耳朵听、嘴巴说、手写画**，能处理的信息维度更丰富。

Spring AI 的 `ChatClient` 支持多模态输入。以图片为例，大致写法是：

```plain
String response = chatClient.prompt()
        .user(u -> u.text("请描述这张图片的内容")
                .media(MimeTypeUtils.IMAGE_PNG,
                       new ClassPathResource("images/demo.png")))
        .call()
        .content();
```

关键点是：

+ `text(...)` 是文本部分；
+ `media(...)` 是附加的图片、音频等；
+ 底层会把它们一起发给支持多模态的模型。

**注意**：不是所有模型都支持多模态。你需要选择具备视觉/听觉能力的模型，比如 GPT-4o、Claude 3、Gemini、Qwen-VL 等。如果模型不支持，传图片会报错或直接被忽略。

### 多模态与 RAG
多模态也可以和 RAG 结合：

+ **多模态 Embedding**：把图片和文字映射到同一个向量空间，实现“用文字搜图片”或“用图片搜文字”。
+ **多模态知识库**：向量库里不只存文本片段，还可以存图片、图表、音频转写文本等。
+ **多模态问答**：用户上传一张图，系统检索相关文档 + 图片，一起交给多模态模型回答。

但是多模态实现问题也比较多比如：

+ **成本高**：图片、视频的 token 消耗远大于纯文本。
+ **延迟大**：处理多模态数据更慢。
+ **对齐难**：让文字和图像在语义上真正对应，需要专门的训练。
+ **安全风险**：图片可能包含敏感信息，需要额外审核。

**多模态就是让 AI 不只“读文字”，还能“看图、听声、看视频”，并能跨这些形式理解和生成内容。** 在 Spring AI 中，它体现为 `ChatClient` 支持在消息里附带图片、音频等媒体，再交给具备多模态能力的模型处理。

**样例讲解：测试图片解析时选择支持多模态的大模型**

```plain
  ai:
    chat:
    dashscope:
#        生产环境强烈推荐使用环境变量（或等效的外部化配置），而不是把密钥硬编码在
      api-key: ${AI_DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen3.7-flash
          multi-model: true
```

### 样例
```plain
package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.UrlResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "Stage01-1.8 多模态基础", description = "多模态")
@RestController
public class HMultiModalDemo {
    private final ChatClient chatClient;

    public HMultiModalDemo(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 图片 URL 需公网可访问（或换成 classpath 文件资源）。
     * 模型侧请使用支持视觉的通义模型，具体模型名以百炼为准。
     */
    @GetMapping("/api/s18/image")
    public String describe(@RequestParam(defaultValue = "用中文描述图片里有什么，不超过50字") String q)
            throws Exception {

        Media image = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG) // jpeg 就换 IMAGE_JPEG
                //网络地址
//                .data(new UrlResource(URI.create(imageUrl)))
                //本地图片
                .data(new ClassPathResource("doc/images/img.png"))
                .build();

        return chatClient.prompt()
                .user(u -> u.text(q)
                        .media(image)) // 文本和图片一起作为 User 输入
                .call()
                .content();
    }
}
```

官方参考：[Spring AI Concepts](https://java2ai.com/ecosystem/spring-ai/reference/concepts) · [docs.spring.io/spring-ai](https://docs.spring.io/spring-ai/reference/)
