# 多模态与语音 Agent：概念、知识地图与落地场景

## 1. 版本基线

Spring AI Alibaba 1.1.2.x + Spring AI 1.1.2 + JDK 17+

官方文档：https://java2ai.com

多模态示例：https://github.com/alibaba/spring-ai-alibaba/tree/main/examples/multimodal

语音 Agent 示例：https://github.com/alibaba/spring-ai-alibaba/tree/main/examples/voice-agent

```text
JDK                 17+
Spring Boot         3.5.7
Spring AI           1.1.2
Spring AI Alibaba   1.1.2.2
```

前面几篇一直在处理文本：工具调用、记忆、上下文、RAG、MCP、A2A、Graph 编排。但真实业务里的信息不全是文字——用户拍一张设备故障照片、客服收到一段录音、商品页只有图没有描述，这时候再怎么用文本转述，信息也已经丢了一半。多模态这块足够单独开一个系列：图像、音频、视频各有各的模型选型、编解码、流式传输和成本核算，光实时语音一条就能写满一本书。本篇文章不做具体实现，只讲概念、要补的知识点、真正用得上的场景和资料。

## 2. 多模态是什么

模态就是信息的形态——文本、图像、音频、视频；能力上又分「理解」「生成」「跨模态转换」三层。落到 Spring AI 里就是：`ChatClient` 的消息上挂一个 `media(...)`，剩下的交给支持视觉或听觉的模型。

### 2.1多模态 → 文本

在 Spring AI Alibaba 中，让一个文本 Agent“收图”的最小改动，就是在 `UserMessage` 上挂一个 `Media`：

```
UserMessage userMessage = UserMessage.builder()
        .text("解释一下这张图里有什么")
        .media(new Media(MimeTypeUtils.IMAGE_PNG,
                new ClassPathResource("/multimodal.test.png")))
        .build();
```

或者用 `ChatClient` 的 lambda 形式：

```
String response = ChatClient.create(chatModel).prompt()
        .user(u -> u.text("解释一下这张图里有什么")
                .media(MimeTypeUtils.IMAGE_PNG, new ClassPathResource("/multimodal.test.png")))
        .call().content();
```

`ReactAgent` 同样可以直接接收带 `media` 的 `UserMessage`。从“接口是否支持”的角度看，这确实是一行代码的事情。

**但“模型支持”和“应用跑得通”的分界线，恰恰从这里开始。**

图片进入模型的方式有两种：公网 URL 或 base64 内嵌。URL 方式的隐含前提是**模型侧能够访问该地址**——这在公网 CDN 场景下成立，但在内网存储、临时签名 URL 或需要鉴权的对象存储中，模型侧根本拉不到图。base64 方式则引入另一组约束：Spring AI 的默认 HTTP 客户端请求体上限约 10MB，一张 4K 图片 base64 编码后约 5.3MB 尚在范围内，但 RAW 格式或高分辨率工程图很容易触顶。

真正消耗工程量的地方在**送入模型之前**。视觉 Token 的数量由图像分辨率和模型配置共同决定，并且可以在调用前主动控制。比如“判断图片里有没有猫”缩到 224×224，Token 消耗可能从 2000 降到 200。这意味着一个“收图”的 Agent，在生产环境中前面必须挂一个**预处理环节**：格式转换（HEIC/RAW → JPEG/PNG）、尺寸缩放（按任务所需精度取最小值）、以及可选的 OCR 前置（如果任务是票据识别，先把文字抽出来再送模型，比让视觉模型“看图读字”更便宜也更准）。

### 2.2文本 → 多模态

生成方向在 Agent 编排中的接入方式很直接：把 `ImageModel` 或 `SpeechModel` 包装成一个工具方法，让 `ReactAgent` 在需要时调用。Spring AI Alibaba 的示例中，`GenerateImageTool` 就是这样做的：

```
ReactAgent creativeAgent = ReactAgent.builder()
        .model(chatModel)
        .methodTools(generateImageTool)   // 包装 ImageModel 的 @Tool
        .build();
creativeAgent.call("生成一张山间日落的图片");
```

TTS 也可以直接用 `DashScopeAudioSpeechModel` 暴露为工具或独立接口。

**但“包成工具”之后，第一个必须做的决策不是模型选型，而是结果形态：返回 URL，还是返回 base64？** Spring AI 的 `ImageModel` 通过 `responseFormat` 参数在两者之间切换；Spring AI Alibaba 的 TTS 示例中，`outputFormat` 控制 `url` 或 `base64`，前者存为服务器临时文件并返回 `file://` 地址，后者供页面直接播放。

这个选择的影响：

- **URL 形态**把生成结果的生命周期交给了文件系统或对象存储。URL 可能过期（某些供应商的生成链接 1–2 小时失效），需要额外的持久化或转发策略。
- **base64 形态**把结果嵌在响应体里，适用于即时播放或内联展示，但会显著推高单次响应的体积和内存占用。

还有一个容易被忽略的选项：**生成结果是否回灌模型**。如果 Agent 下一步需要“检查生成的图片是否符合要求”再决定是否重试，那么结果就不能只是一个返回给前端的 URL，而需要同时以模型可消费的 `Media` 形态保留在上下文中。这决定了工具方法的返回类型是 `String`（URL/路径）还是包含 `Media` 的结构化对象。

### 2.3多模态 → 多模态

图生图、语音到语音这类场景，在 Spring AI Alibaba 中通常不是“一次模型调用”能完成的。官方示例中已经出现了**链式工作流**的形态：`chain().then(LLM).then(Image).then(LLM)`，把“用户一句话 → 生成图 → 自动写文案”串成流水线。语音 Agent 的“三明治架构”也是同一逻辑：STT → ReactAgent → TTS，STT 和 TTS 各是一次独立调用，Agent 只负责中间的决策层。

这意味着工程重心从“写 Agent”转移到了**编排层**：

- **中间产物的形态与传递**：STT 输出的是文本，直接进入 Agent 的 `UserMessage`；TTS 输出的是音频流或文件，需要决定是同步返回还是异步推送。
- **失败链路的逐段收口**：STT 识别失败、Agent 决策失败、TTS 合成失败，三段的降级策略各不相同，不能指望一个全局的 try-catch 兜住。
- **每次调用的独立超时与成本核算**：整条链路的延迟是各段之和，而每段的模型计费方式不同（STT 按音频时长、LLM 按 Token、TTS 按字符数），成本控制需要在编排层按段配置。

在这个方向上，Agent 确实成了“链路里最小的一环”。编排的复杂度和工程代码量，远大于 Agent 本身的定义。

### 2.4实时双向

Spring AI Alibaba 的 Voice Agent 示例已经明确了形态：基于 WebSocket 的实时语音 Agent，支持流式音频或文本输入，并以生成音频响应。TTS 侧存在 `StreamingTextToSpeechModel` / `stream()` 接口，支持分块传输的实时音频流，首包延迟是核心指标；某些模型（如 `cosyvoice-v3-flash`）应通过 `stream()` 而非 `call()` 使用。

但在实时双向场景中，真正决定体验和工程复杂度的不是模型调用本身，而是 **WebSocket 连接上的事件协议**：

- 音频分帧的粒度与缓冲策略——帧太小则协议开销高，帧太大则首包延迟差；
- **打断（barge-in）**——用户在 TTS 还在播放时开口，系统需要立即取消正在进行的合成和播放，并切换到 STT 接收。这是一个跨多个组件的取消信号，Agent 层只是被动接收“当前轮次已取消”的输入。
- 流式状态机的完整性——连接中断后的恢复、半句话的边界处理、VAD（语音活动检测）触发的轮次切分。

在这个方向上，Agent 的职责被压缩到“拿到一段完整的文本输入，返回一段文本输出”。**传输、播放、取消、重连，才是工程量的真正所在。**

## 3. 真要上手，得学习哪些知识

### 3.1模型与能力

Spring AI 把多模态能力拆成了几个独立的模型接口，这个拆分本身就是第一层知识：

- `ChatModel` 处理文本 + 图像（视觉理解），通过 `UserMessage` 上的 `Media` 传入。
- `ImageModel` 负责文生图，`call(ImagePrompt)` 返回 `ImageResponse`。
- `TranscriptionModel`（语音转文本）和 `TextToSpeechModel`（文本转语音）分别对应 ASR 和 TTS。
- 视频理解通常没有独立接口，而是通过 `ChatModel` 传入视频帧或视频 URL，取决于模型供应商的支持方式。

在 Spring AI Alibaba 中，DashScope 生态对应的是 `qwen-vl` 系列（视觉理解）、`paraformer` / `sensevoice`（ASR）、`cosyvoice`（TTS）、`wanx`（文生图）。**接口分工决定了你后面编排的形态**：如果 `TranscriptionModel` 和 `TextToSpeechModel` 是两个独立 Bean，语音 Agent 的“三明治架构”就是天然的三段式编排，而不是一个模型调用。

这一层最容易误判的是 **Token 计费方式与文件大小的关系**。视觉 Token 按图像分辨率计算，不按文件字节数；一张高度压缩的 4K 图可能文件很小但 Token 很多，一张模糊的小图可能文件不小但 Token 很少。音频按秒计费，与采样率无关。如果不提前建立“分辨率/时长 → Token/费用”的换算直觉，账单翻倍时很难定位是哪个环节放大了输入。

### 3.2 数据与编解码

这一层的核心是**在代码里把“格式约定”变成显式的常量或配置，而不是依赖默认值**。

Spring AI 的 `Media` 构造是 `new Media(MimeType, Resource)`，`Resource` 的类型直接决定传输形态：传 `UrlResource` 时，Spring AI 把 URL 原样发给模型；传 `ByteArrayResource` 时，内部会 base64 编码后内嵌到请求体中。**这个选择不是格式偏好，而是架构决策**——URL 方式要求模型侧能访问该地址，内网存储、临时签名 URL、需要鉴权的对象存储都会在这里失败；base64 方式则受 Spring AI 默认 HTTP 客户端请求体上限约 10MB 的约束。

实操中可以提前确定几条约定：

- **图片统一先压到任务所需的最小分辨率**。用 `ImageIO` + `Graphics2D` 或 Thumbnailator 在送入模型前缩放，而不是把原图直接挂 `Media`。票据识别可能需要较高分辨率，但“判断图里有没有猫”缩到 224×224 就能把视觉 Token 从数千降到数百。
- **音频统一为 16 kHz / 16-bit / mono PCM**。ASR 模型对采样率有偏好，不统一的输入要么被重采样（增加延迟），要么识别质量下降。这个约定应该写在工具方法的入参校验里，而不是靠调用方自觉。
- **内网图片在发送前转 base64 或转存到模型可达的地址**。如果模型侧拉不到内网 URL，`UrlResource` 方式会静默失败或超时。一个可检查的规则是：凡是来源为内网存储的 `Media`，构造时就走 `ByteArrayResource` 路径，并在日志里记录原始字节数和编码后字节数。

### 3.3传输与实时

在 Spring 中，实时语音通常落在 `WebSocketHandler` 上。这一层的工程量不在“能不能连上”，而在**事件协议的设计**：

- **文本帧与二进制帧的分工**。控制消息（开始录音、结束、打断、错误）走 JSON 文本帧，音频 PCM 走二进制帧。两者混在一个帧类型里，后期解析和调试会非常痛苦。
- **音频分帧粒度**。20ms–100ms 一帧是常见区间，帧太小协议开销高，帧太大首包延迟差。这个值应该在客户端和服务端之间作为协议常量约定。
- **打断（barge-in）的取消信号要贯穿整条链路**。用户开口时，需要同时取消正在进行的 TTS 合成、停止已缓冲音频的播放、切换 STT 进入接收状态。这个取消信号如果只在 WebSocket 层处理，Agent 层的状态不会被重置，下一轮会读到上一轮的脏状态。**取消点必须在架构里提前预留**。
- **鉴权在握手阶段完成**，而不是在第一条业务消息里。WebSocket 握手可以携带 token，校验失败直接拒绝连接，避免建立连接后再踢人带来的状态清理问题。

### 3.4与 Agent 接合

这一层在代码形态上：`UserMessage.builder().media(...)` 让 `ReactAgent` 收图，把 `ImageModel` 或 `TextToSpeechModel` 包成 `@Tool` 方法让 Agent 调用。但真正需要提前决策的是**工具方法的返回类型**。

如果工具返回 `String`（URL 或文件路径），Agent 的多轮上下文里只保留一个短字符串，Token 成本低，但前端拿到后还需要一次额外请求才能展示，且 URL 可能过期。如果工具返回包含 `Media` 的结构化对象，前端可以直接内联展示或播放，但每次多轮传递都会把 base64 重新带入上下文，Token 和内存开销显著上升。

**务实的做法是分两条通路**：工具方法返回一个包含 `url` 和 `media` 两个字段的对象，`url` 用于前端展示和持久化，`media` 仅在 Agent 需要“回灌模型检查生成结果”时才放入上下文。两者都想要时，不要让一个 `String` 返回值同时承担“给前端”和“给模型”两个职责。

多轮场景还有一个容易被忽略的点：**媒体在 `threadId` 的多轮对话中是否重复上传**。如果每一轮都把同一张图重新挂到 `UserMessage` 上，Token 会按轮次线性增长。对于需要跨轮引用的媒体，应将其持久化到会话状态中，后续轮次通过引用而非重传来使用。

### 3.5 质量与治理

延迟需要拆成三个独立指标来度量：**首包延迟**（用户说完到第一个音频包发出）、**轮次延迟**（用户说完到 AI 说完）、**打断响应延迟**（用户打断到系统停止播放并开始接收）。这三个指标的优化手段不同，混在一起看“总延迟”会导致优化方向错误。首包延迟取决于 TTS 是否走 `stream()` 而非 `call()`、音频分帧粒度、以及模型侧的首 token 时间；轮次延迟还叠加了 ASR 的整段转写等待和 Agent 的推理时间。

**打断的取消点必须在架构里提前预留**，具体来说至少三个位置：TTS 流式合成的取消（`StreamingTextToSpeechModel` 的流需要支持关闭）、播放缓冲的清空（客户端侧）、以及 Agent 当前轮次状态的标记为已取消。这三个取消点缺任何一个，打断都会表现为“声音停了但下一轮读到了上一轮的残留状态”或“声音没停干净”。

评测集的构建在这一层同样不能延后。多模态评测需要覆盖**不同分辨率、不同光照、不同口音、不同背景噪声**的输入，而不是只用干净的 Demo 样本

## 4. 哪些场景会真的用到

### 4.1票据 / 证照 / 表单识别

`ChatClient` 挂 `Media` 后直接 `.entity()`：

```
InvoiceInfo info = ChatClient.create(chatModel).prompt()
        .user(u -> u.text("提取这张发票的金额、日期、开票方")
                .media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes)))
        .call().entity(InvoiceInfo.class);
```

`.entity()` 内部会追加格式指令并做 JSON 解析，看起来“一句话完成”。但**版式差异会让结构化输出在真实数据上频繁失败**——不同供应商的发票字段名不同，模型可能返回 `"amount"` 也可能返回 `"total_amount"`，`entity()` 的反序列化直接抛异常。工程上需要一个**校验与修正层**：字段缺失时回退到宽松解析、金额做数值范围校验、日期做格式归一化。隐私脱敏则是在送入模型**之前**对图片做区域遮盖或文字替换，而不是依赖模型“不输出敏感字段”。

### 4.2设备巡检 / 缺陷质检

这个场景的 Agent 形态是 `ReactAgent` + 工具调用（查工单、建单），代码上不复杂。真正的难点在**误报的代价不对称**：漏报可能造成事故，误报则浪费巡检人力。这意味着 Agent 的输出不能是二元的“有缺陷/无缺陷”，而需要携带**置信度和依据**（图中哪个区域、什么特征），并且低置信度结果要路由到人工复核，而不是直接建单。图文对齐的问题也在这里暴露：模型说“左上角有裂纹”，但实际图片的左上角是空白，这种错位在自动建单前必须有校验。

### 4.3图表 / 扫描件知识库

这个场景的工程量几乎全在**入库和召回**，不在生成。`VectorStore` 和 `EmbeddingModel` 是现成的，但“图和文怎么一起召回”需要提前决策：是先把图表用 VL 模型转成文字描述再嵌入，还是用多模态 Embedding 模型直接把图嵌入到与文本同一向量空间？前者实现简单但丢失视觉细节，后者检索质量更好但依赖特定模型支持。扫描件还需要 OCR 前置，而 OCR 的错误会沿着检索链路放大——检索不到正确文档时，生成层再强也无济于事。

### 4.4 营销配图

把 `ImageModel` 包成 `@Tool` 让 Agent 调用。但生产环境中，生成结果必须经过**内容审核**（涉政、涉黄、商标侵权）才能返回给用户，而审核本身是一次额外的模型调用或第三方 API 调用，会推高延迟和成本。版权与风格一致性则需要维护**提示词模板和参考图库**，而不是让 Agent 每次自由发挥。`url` vs `base64` 的决策在这里同样存在：前端要立刻展示用 `base64`，要持久化和分享用 `url`，两者分两条通路。

### 4.5会议录音转写与纪要

`TranscriptionModel.transcribe()` 处理短音频很简单，但会议录音通常超过模型单次处理的时长上限。工程上需要**按静音段切分音频、分段转写、再按时间轴拼接**，同时处理说话人分离（谁在什么时候说了什么）。这些预处理逻辑不在 Spring AI 的抽象层里，需要自己实现或集成第三方服务。摘要层反而是最简单的部分——把转写文本交给 `ChatClient` 即可。

### 4.6朗读 / 无障碍

如果需求只是“让回答能读出来”，最小实现就是在文本 Agent 的输出末尾接一次 TTS：

```
String answer = reactAgent.call(question).getText();
byte[] audio = textToSpeechModel.call(new TextToSpeechPrompt(answer))
        .getResult().getOutput().getAudio();
```

不需要 WebSocket、不需要流式、不需要打断处理。`TextToSpeechModel` 的 `call()` 同步返回音频，`url` 或 `base64` 取决于前端如何消费。**这条路径的工程量和文本 Agent 几乎没有区别**，只是多了一次同步模型调用。

### 4.7语音助手 / 智能座舱

在 Spring AI Alibaba 中，STT 和 TTS 各自是独立的 `TranscriptionModel` / `TextToSpeechModel` Bean，中间的 `ReactAgent` 持有工具和业务逻辑。**三段各自可替换**是这个架构的最大收益——STT 换模型不影响 Agent，TTS 换音色不影响识别。

代价全在链路层：上行 PCM 推流、下行合成音频推流、识别字幕和工具轨迹一并送前端，全靠 WebSocket 事件串起来。barge-in 需要同时取消 TTS 合成流、清空播放缓冲、标记 Agent 当前轮次已取消、切换 STT 进入接收状态——这四个动作缺一个都会出现问题”。这部分确实**几乎没有现成经验可以照搬**，因为每个业务对“打断后如何恢复上下文”的期望不同。

### 4.8 视频内容理解

视频没有独立的 Spring AI 接口，通常走“抽帧 → 每帧作为 `Media` 送入 VL 模型 → 汇总”的路径。**抽帧策略是核心决策**：固定间隔抽帧简单但可能漏掉关键片段，基于场景变化抽帧更智能但需要额外的视频处理库。成本随时长和帧数线性增长，长视频必须先做分段摘要再汇总，而不是把所有帧一次性塞进上下文。

## 5. Spring AI Alibaba 多模态学习资料索引

| 知识层 / 目标                     | 资料入口与具体路径                                           | 关键内容 / 查找线索                                          |
| :-------------------------------- | :----------------------------------------------------------- | :----------------------------------------------------------- |
| **① 概念与 API 抽象**             | **官方文档：Multimodal API** `java2ai.com/ecosystem/spring-ai/reference/multimodality/` | `UserMessage` 的 `media` 字段用法、`MimeType` 指定模态、`Resource` vs `URI` 的数据形态。**关键限制**：`media` 仅用于用户消息，`AssistantMessage` 只返回文本。 |
| **② 最小可运行示例**              | **GitHub 示例仓库：`examples/multimodal`** 路径：`alibaba/spring-ai-alibaba` → `examples/multimodal` | 五个场景：**图片 URL 理解、本地资源理解、视觉 `ReactAgent`、工具出图、TTS**。 |
|                                   | **运行命令**                                                 | `./mvnw -pl examples/multimodal spring-boot:run`（需 JDK 17+ 和 `AI_DASHSCOPE_API_KEY`）。 |
| **③ 实时语音（三明治架构）**      | **GitHub 示例仓库：`examples/voice-agent`** 路径：`alibaba/spring-ai-alibaba` → `examples/voice-agent` | **Sandwich 架构**（STT → Agent → TTS），WebSocket 双向通信，Push-to-Talk，**打断控制（barge-in）**，基于 Project Reactor 的流式编排。 |
|                                   | **核心代码文件**                                             | `VoiceAgentPipeline.java`（STT→Agent→TTS 管道）、`VoiceWebSocketHandler.java`（WS 消息处理）、`event/`（事件定义）。 |
| **④ 模型抽象与配置**              | **官方文档：Chat Models 对比** `java2ai.com` 的 “Chat Models 对比”页 | 各模型对 `Multimodality`（输入类型）的支持情况对比。         |
|                                   | **示例代码（配置来源）**                                     | `multimodal` 示例的 `application.yml` 和 `GenerateImageTool` / `DashScopeAudioSpeechModel` 的构造代码，是配置项（如 `outputFormat`）最准确的来源。 |
| **⑤ 模型能力与计费（DashScope）** | **百炼计费总览**                                             | 理解**阶梯计费**规则：单价取决于单次请求的输入 Token 总量，而非超出部分。 |
|                                   | **视觉模型（qwen-vl 系列）**                                 | 按输入/输出 **Token** 计费（每百万 Token），单次请求 Token 量跨档时，**全部 Token 按高档单价结算**。 |
|                                   | **语音合成（TTS / CosyVoice）**                              | 按输入**字符数**计费（每万字符）。生成的音频不另行计费。     |
|                                   | **语音识别（ASR）**                                          | 按输入**音频时长**（秒）计费。                               |
|                                   | **图像生成（Qwen-Image / 万相）**                            | 按输出**图片张数**计费。                                     |

## 下一篇

聊 **可观测、评估、平台化**：日志与 Tracing、指标、Admin / Studio、评估与测试策略——让 Agent 从「能跑」到「可运营」。

---

## 官方参考

- https://github.com/alibaba/spring-ai-alibaba/tree/main/examples/multimodal
- https://github.com/alibaba/spring-ai-alibaba/tree/main/examples/voice-agent
- https://java2ai.com （Chat / Image / Audio Model 基础）
