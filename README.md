# 1.从 Java 到 AI：为什么 Spring AI Alibaba 值得你投入时间
你好，欢迎来到这个系列。

如果你是一名 Java 开发者，过去几年大概有过这样的感受：AI 应用的浪潮铺天盖地，但翻看教程和开源项目，清一色是 Python 的天下。LangChain、LlamaIndex、各种 Agent 框架，似乎 Java 开发者天然被排除在这场技术变革之外。

但事实并非如此。你手里那套经过互联网时代考验的 Spring 技术栈，正在成为 AI 应用落地企业级场景的关键拼图。

## 2.为什么 Java 开发者需要关注 AI 框架
一个被反复讨论的事实是：搭建 AI Demo 很容易，把 AI 放进生产环境很难。吞吐量、可扩展性、微服务生态、可观测性——这些恰恰是 Java 生态积累了二十年的强项。

Python 在模型训练和实验阶段占据主导，但当 AI 能力需要嵌入到已有的订单系统、风控系统、客服系统时，Java 才是那个“最后一公里”的语言。问题在于，过去缺少一个真正为 Java 开发者设计的、生产可用的 AI 应用框架。

Spring AI Alibaba 正是在这个背景下出现的。

## 3.它到底是什么
简单来说，**Spring AI Alibaba 是一个面向 Java 开发者的 Agentic AI 框架**，由 Spring 官方社区和阿里开源社区共同维护。

它的定位可以用一个类比来理解：如果说 Spring AI 是 Java 领域的 LangChain，那么 Spring AI Alibaba 更像是 Java 领域的 LangGraph。前者解决“怎么接入 AI 模型”，后者解决“怎么让多个 AI 协同工作、怎么编排复杂的工作流”。

从架构上看，它分为三层：

**底层是 Augmented LLM**，基于 Spring AI 的原子抽象，提供模型调用、消息、工具、向量存储、MCP 等基础能力。这一层让你用几行代码就能调用通义千问、DeepSeek 等模型。

**中间是 Graph Runtime**，一个低级别的工作流和多智能体编排引擎，支持条件路由、嵌套图、并行执行、状态管理和持久化。

**上层是 Agent Framework**，提供 ReactAgent、SequentialAgent、ParallelAgent、RoutingAgent 等预置智能体模式，让开发者能快速搭建具备推理和行动能力的 Agent 应用。

## 4.这个系列会带你学什么
接下来的一段时间，我会用连载的形式，从零开始带你系统性地掌握 Spring AI Alibaba。

不会只停留在“跑通一个 Hello World”。这个系列的野心是让你真正具备用 Java 构建生产级 AI 应用的能力。大致会覆盖这些内容：

**基础篇**会从 ChatClient 讲起，让你理解如何用最少的代码接入大模型，如何处理 Prompt、消息角色、流式响应，以及如何通过 DashScope 调用通义系列模型。然后进入 Tool Calling，这是 Agent 能力的基石——让大模型能够调用你写的 Java 方法。

**进阶篇**会深入 Agent Framework。你会学到如何用 ReactAgent 构建具备推理和行动能力的智能体，如何通过 SequentialAgent、ParallelAgent、RoutingAgent 组合多个 Agent 协作完成复杂任务，以及如何通过 Graph API 做更精细的流程控制。

**生产篇**会聚焦那些让 AI 应用从“能跑”到“可靠”的关键能力：Memory 与状态管理（短期记忆、长期记忆、上下文工程）、可观测性（追踪模型调用、监控成本、发现幻觉）、以及 Human-in-the-loop（人工审批节点）。

**实战篇**会带着你构建完整的项目：一个具备 RAG 知识库的智能客服、一个多 Agent 协作的研究助手、或者一个工作流驱动的自动化系统。具体做什么，到时候我们可以一起讨论。

## 5.需要什么准备
你不需要有 AI 或机器学习背景。这个系列面向的是有 Spring Boot 开发经验的 Java 工程师。你只需要：

+ **JDK 17 及以上**（Spring AI Alibaba 基于 Spring Boot 3.x）
+ 一个阿里云百炼平台的 **API Key**（免费额度足够学习和测试）
+ 一颗愿意把 AI 能力“工程化”而非“玩具化”的心

至于 LangChain、Prompt Engineering、向量数据库这些概念，我会在用到的时候解释清楚，不需要提前补课。

## 6.最后
Spring AI Alibaba 的开源初衷很朴素：让 Java 开发者不用切换技术栈，就能以较低门槛开发 AI 应用。从 2024 年 9 月开源至今，它已经积累了超过 10k Star，支持了从聊天机器人到多智能体工作流的完整场景。

但框架只是工具。真正有价值的是你用它构建出来的东西。

## Spring-AI-Alibaba环境与全局认知

<font style="color:rgb(119, 119, 119);">版本基准：</font>**<font style="color:rgb(119, 119, 119);">Spring AI Alibaba 1.1.2.x</font>**<font style="color:rgb(119, 119, 119);"> + </font>**<font style="color:rgb(119, 119, 119);">Spring AI 1.1.2</font>**<font style="color:rgb(119, 119, 119);"> + </font>**<font style="color:rgb(119, 119, 119);">JDK 17+</font>**

<font style="color:rgb(119, 119, 119);">官方文档：</font>[<font style="color:rgb(65, 131, 196);">https://java2ai.com</font>](https://java2ai.com)

<font style="color:rgb(119, 119, 119);">主仓库：</font>[<font style="color:rgb(65, 131, 196);">https://github.com/alibaba/spring-ai-alibaba</font>](https://github.com/alibaba/spring-ai-alibaba)

<font style="color:rgb(119, 119, 119);">示例仓库：</font>[<font style="color:rgb(65, 131, 196);">https://github.com/spring-ai-alibaba/examples</font>](https://github.com/spring-ai-alibaba/examples)

## 一、JDK 17+ 与 Maven 3.8+
JDK 17+ 与 Maven 3.8+ 的组合，是目前 Java 生态中**最稳妥、最主流的生产环境基准线**。AI 推荐这个版本，核心原因在于 **JDK 17 是 LTS（长期支持版），而 Maven 3.8+ 是能稳定支撑 JDK 17 及现代框架（如 Spring Boot 3.x）的最低安全版本**。

### JDK 17+ 的核心新特性
JDK 17 于 2021 年 9 月正式发布，是继 JDK 8、11 之后的又一个 LTS 版本。它的新特性主要集中在语言表达力和代码安全性上：

+ **密封类 (Sealed Classes)**：允许你精确控制“哪些类或接口可以继承/实现我”。这对于设计稳定的领域模型非常有用，能避免意外的扩展破坏代码逻辑。
+ **模式匹配增强**：虽然 `switch` 的模式匹配在 17 中还是预览特性，但它代表了 Java 语言未来的方向，能大幅简化复杂的 `if-else` 类型判断代码。
+ **强封装 JDK 内部 API**：默认禁止通过反射访问 `java.*` 的内部实现，这迫使开发者放弃对内部 API 的依赖，保证了应用在未来升级 JDK 时的稳定性。

### Maven 3.8+ 的核心新特性
Maven 3.8 系列（以及 3.8.6、3.8.8 等后续补丁版）主要解决了**安全**和**依赖管理的确定性**问题，这是现代 CI/CD 流水线的基石：

+ **依赖项验证 (Dependency Verification)**：允许你配置校验和，验证从仓库下载的依赖是否被篡改。这在防范供应链攻击时非常关键。
+ **移除对不安全 HTTP 仓库的默认支持**：强制推动开发者使用 HTTPS，避免依赖在传输过程中被劫持或篡改。
+ **凭证加密**：支持对存储在 `settings.xml` 中的仓库密码进行加密，避免明文密码泄露。

### 为什么 AI 会推荐 “JDK 17 + Maven 3.8+”
AI 推荐这个组合，通常是基于**生态兼容性**和**项目稳定性**的权衡：

+ **Spring Boot 3.x 的强制门槛**：如果你使用的是 Spring Boot 3.0 或更高版本，官方要求**最低 JDK 17**，且不再支持 JDK 8 或 11。如果你用旧版 JDK 打开项目，会直接抛出 `UnsupportedClassVersionError` 异常。
+ **Maven 3.8.6+ 是许多现代项目的硬性要求**：很多基于 Spring Boot 3.x 的教程和脚手架工具（如 Spring Initializr）默认推荐 Maven 3.8.6 或 3.8.8 以上。低于这个版本（如 3.6.x），可能会在解析现代依赖或处理 JDK 17 项目时遇到兼容性问题。
+ **生产环境的“最大公约数”**：JDK 17 已经是当前生产环境的事实标准，几乎所有主流框架（Spring、Quarkus、Micronaut）和工具链都对它提供了最佳的稳定支持。Maven 3.8+ 的改动相对 3.6 是平滑的，它既带来了必要的安全加固，又没有引入像 Maven 4 那样激进的破坏性变更，是风险最低的选择。

如果你正在启动一个全新的 Spring Boot 项目，直接选择 **JDK 17（Temurin 发行版）** + **Maven 3.8.8+** 的组合，通常能避免绝大多数版本兼容性的“坑”。

## <font style="color:rgb(51, 51, 51);">二、 Spring AI Alibaba核心架构</font>
根据 Spring AI Alibaba 官网的架构说明，这几个框架呈现出清晰的分层关系。从底层到上层，从开发到管理，可以这样理解它们各自的角色：

### <font style="color:rgb(51, 51, 51);">底层基座：Spring AI</font>
<font style="color:rgb(51, 51, 51);">Spring AI 是整个体系的地基，由 Spring 官方团队维护，并非阿里专属。它的核心目标是</font>**连接企业数据与 AI 模型**<font style="color:rgb(51, 51, 51);">，提供最基础的原子抽象</font><font style="color:rgb(51, 51, 51);">。你可以把它理解为“零件库”，提供了模型接入（Model）、工具调用（Tool）、对话记忆（Memory）、向量存储（Vector Store）等基础组件</font><font style="color:rgb(51, 51, 51);">。它不负责复杂的业务编排，只解决“如何让 Java 应用调用大模型”的问题。</font>

### <font style="color:rgb(51, 51, 51);">核心引擎：Spring AI Alibaba Graph</font>
<font style="color:rgb(51, 51, 51);">Graph 是 Spring AI Alibaba 区别于上游 Spring AI 的核心价值，可以理解为 </font>**Java 版的 LangGraph**<font style="color:rgb(51, 51, 51);">。它的角色是</font>**工作流与多智能体编排的底层运行时基座**<font style="color:rgb(51, 51, 51);">。它通过 State（状态）、Node（节点）、Edge（边）这三个概念，让开发者能够用声明式的方式定义复杂的流程</font><font style="color:rgb(51, 51, 51);">。它内置了 SequentialAgent、ParallelAgent、RoutingAgent 等预置模式，支持 Human-in-the-loop（人工介入）、流式响应和状态持久化</font><font style="color:rgb(51, 51, 51);">。当你的业务需要“先执行 A，再根据结果判断走 B 还是 C”这类流程时，Graph 就是用来干这个的。</font>

### <font style="color:rgb(51, 51, 51);">上层封装：Spring AI Alibaba Agent Framework</font>
<font style="color:rgb(51, 51, 51);">Agent Framework 是构建在 Graph </font>**之上**<font style="color:rgb(51, 51, 51);">的 Agent 开发框架，它的设计目标是让开发者</font>**用更少的代码快速构建智能体**<font style="color:rgb(51, 51, 51);">。它的核心是 </font>**ReactAgent**<font style="color:rgb(51, 51, 51);">，遵循“推理+行动”范式：Agent 会先思考（Reasoning）需要做什么，决定调用哪个工具（Acting），观察结果（Observation），然后迭代直到任务完成</font><font style="color:rgb(51, 51, 51);">。Agent Framework 帮你封装了自动上下文工程、人机交互等高级能力，你不需要从零去拼装这些逻辑。简单说，</font>**Graph 是给你搭乐高积木的底板，Agent Framework 是给你提供预装好的乐高套装**<font style="color:rgb(51, 51, 51);">。</font>

### <font style="color:rgb(51, 51, 51);">可视化调试：Spring AI Alibaba Studio</font>
<font style="color:rgb(51, 51, 51);">Studio 是一个</font>**可视化的聊天窗口**<font style="color:rgb(51, 51, 51);">，用于与你开发的 Agent 进行交互</font><font style="color:rgb(51, 51, 51);">。它的核心作用是</font>**调试**<font style="color:rgb(51, 51, 51);">：当你用 Agent Framework 或 Graph 开发了一个智能体后，可以通过 Studio 的界面直观地看到 Agent 的推理过程和工具执行细节</font><font style="color:rgb(51, 51, 51);">。它不参与应用的运行时，而是开发阶段的辅助工具。</font>

### <font style="color:rgb(51, 51, 51);">生命周期管理：Spring AI Alibaba Admin</font>
<font style="color:rgb(51, 51, 51);">Admin 是一个</font>**企业级 Agent 开发与评估平台**<font style="color:rgb(51, 51, 51);">，定位比 Studio 更重。它覆盖了从 </font>**Prompt 工程、数据集管理、评估器配置，到实验执行和结果分析**<font style="color:rgb(51, 51, 51);">的完整工作流。简单说，Studio 用来“聊天调试”，Admin 用来“系统化地管理和优化”你的智能体——比如维护 Prompt 版本、构建评估数据集、跑实验对比不同配置的效果、通过集成的可观测性追踪调用链路</font>

<font style="color:rgb(51, 51, 51);">如果把开发一个智能体应用比作造车：</font>**Spring AI**<font style="color:rgb(51, 51, 51);"> 是螺丝、齿轮、发动机；</font>**Graph**<font style="color:rgb(51, 51, 51);"> 是底盘和传动系统，负责让各个部件按流程协同工作；</font>**Agent Framework**<font style="color:rgb(51, 51, 51);"> 是整车组装线，让你快速拼出一辆能跑的车；</font>**Studio**<font style="color:rgb(51, 51, 51);"> 是试车跑道，用来试驾和观察；</font>**Admin**<font style="color:rgb(51, 51, 51);"> 是 4S 店的管理系统，负责保养记录、性能评估和版本管理。</font>

## <font style="color:rgb(51, 51, 51);">三、Chatbot / 单 Agent / 多 Agent / Workflow 四种应用形态</font>
这四种形态可以理解为从“最简单”到“最复杂”的四个台阶，核心区别在于**控制权在谁手里**：是代码预先定好，还是让大模型自己决定



| 形态 | 控制权归属 | 核心特点 | 典型场景 |
| --- | --- | --- | --- |
| **Chatbot** | 代码 + 简单 LLM | 单轮/多轮对话，无复杂工具调用 | 客服问答、知识库查询 |
| **单 Agent** | LLM 主导（ReAct循环） | 自主推理+调用工具，解决单一领域问题 | 数据分析助手、代码生成 |
| **Workflow** | **代码侧 100% 控制** | 预定义步骤（顺序/并行/路由），**确定性高** | 审批流、RAG管道、结构化提取 |
| **多 Agent** | LLM 主导 + 编排框架 | 多个专家 Agent 协作或路由，**处理复杂多领域任务** | Deep Research、跨领域客服 |




**Workflow vs Agent**：这是最重要的分界线。Workflow 是“**把 LLM 当可靠函数用**”，路径由你写死（A→B→C），适合结果必须可控、可验证的场景。Agent 则是“**给 LLM 一个目标让它自己找路**”，容忍过程不确定，换取解决未知问题的灵活性。

**单 Agent vs 多 Agent**：当单 Agent 的上下文窗口快撑爆、或者任务需要不同领域的专精能力时，才考虑拆分。奥卡姆剃刀原则：**如无必要，勿增实体**。多 Agent 的核心价值是**上下文隔离**和**并行执行**，但代价是复杂度和 token 消耗都上升。

### 在 Spring AI Alibaba 里怎么对应
+ **Chatbot / 单 Agent**：直接用 Spring AI 的 `ChatClient`，搭配 `Advisor` 实现记忆。
+ **Workflow**：用 **Spring AI Alibaba Graph** 的 `StateGraph` 编排，支持顺序、并行、路由、循环四类 Flow Agent。
+ **多 Agent**：用 **Agent Framework** 的预置模式——`SequentialAgent`（顺序）、`ParallelAgent`（并行）、`LlmRoutingAgent`（路由）、`SupervisorAgent`（监督者）。

官方对 Spring AI Alibaba 的定位更侧重 **Workflow 和 Graph 编排**，如果你追求的是“把自主权完全交给模型”的 Agentic 范式，官方推荐考虑 AgentScope。



## 四、何时用 ChatClient，何时用 ReactAgent，何时下沉 Graph API
这三者的选择，本质上是在**代码控制力**和**模型自主性**之间做权衡。可以理解为一条从“轻”到“重”的谱系：**ChatClient 是基础对话能力，ReactAgent 是开箱即用的智能体，Graph API 是终极的流程控制权**。

### 何时用 ChatClient
当你需要的只是**一次可靠的对话或工具调用**，不需要 Agent 自主循环时，用 `ChatClient` 就够了。

它的核心优势是**简单直接**。你发起调用，它返回结果，流程控制权完全在你手里。Spring AI 的 `ChatClient` 本身也支持工具调用，如果你只是想让模型在回答前查一下天气或数据库，直接配置工具即可，不必引入 Agent 的复杂度。

**判断信号**：你的需求是“提问-回答”式的，即使涉及工具，也是“查一次、答一次”的线性流程，不需要模型自己决定“下一步该干什么”。

### 何时用 ReactAgent
当你需要**把一个目标交给模型，让它自己规划并执行多步骤任务**时，用 `ReactAgent`。

它实现的是 ReAct（推理+行动）循环：模型思考需要什么，调用工具，观察结果，再思考，直到任务完成。你只需要提供工具集和任务描述，剩下的循环逻辑由框架处理。与 `ChatClient` 的关键区别在于**过程可见性和可干预性**：`ReactAgent` 的 `stream()` 会暴露每一步的模型推理和工具调用事件，而 `ChatClient` 只给你最终回复。

**判断信号**：任务需要**多个工具的组合调用**（比如先查天气、再根据天气查航班、最后生成建议），且步骤间的依赖关系由模型根据中间结果动态决定，而不是你预先写死的。

### 何时下沉 Graph API
当你有**明确的、必须严格遵循的流程**，或者需要对执行状态进行**精细控制**（中断、恢复、回滚）时，直接使用 `Graph API`。

Graph 的核心价值是**确定性和控制力**。你可以用 StateGraph 显式定义节点和边：什么条件下走哪个分支、哪些节点并行、哪里需要人工审批。官方文档明确指出，Graph API 适用于**需要超高可靠性、大量自定义逻辑、需要精确控制延迟**的场景。

**判断信号**：你的业务流程本身就有明确的结构（比如“分类 → 路由 → 处理 → 记录”），你希望代码完全掌控流转，而不是让模型“自由发挥”去决定下一步。或者，你需要**人工介入**（Human-in-the-loop）、**断点续传**这类运行时控制能力，这些只有 Graph 层才能提供。

可以按这个顺序判断：**如果 ChatClient 够用，就不引入 Agent；如果单个 ReactAgent 能完成任务，就不下沉到 Graph；如果流程必须由你精确编排，或者需要运行时控制，再直接用 Graph。**

反过来，一旦你发现自己在 ReactAgent 外面包了一层又一层的判断逻辑来“引导”它走正确的流程，那大概率说明这个场景更适合用 Graph 来显式定义。



## 五、Spring AI Alibaba 与 AgentScope 的定位差异
官方文档中有一个很清晰的概括：**Spring AI Alibaba 以 Graph 为核心，强调工作流编排；AgentScope 以 Agentic 为核心，最大化利用基础大模型的能力**。

### 核心设计哲学：Workflow vs Agentic
这是两者最根本的分界线，决定了框架的“性格”。

**Spring AI Alibaba** 的设计哲学是：**把 LLM 当作一个不可靠的“函数”，用可靠的代码结构把它“框”住**。控制权 100% 在代码侧，开发者决定何时调用模型、Prompt 是什么、输出怎么解析、失败怎么重试。路径是预先定义的，结果可预测、可测试。

**AgentScope** 的设计哲学则相反：**把 LLM 当作“大脑”，给它工具和目标，让它自己找路**。控制权在 LLM 侧，系统只给一个目标，模型自主决定下一步做什么。它容忍过程的不确定性，以换取解决复杂、未知问题的能力。

### 由此衍生的定位差异
| 维度 | Spring AI Alibaba | AgentScope |
| --- | --- | --- |
| **核心抽象** | Graph（状态图）、Workflow 编排 | Agent（尤其是 ReActAgent）、多智能体协作 |
| **擅长场景** | 流程明确的业务：审批流、RAG 管道、结构化提取、高风险业务 | 开放式任务：市场调研、代码生成与修复、需要动态规划的场景 |
| **语言与生态** | Java 原生，深度绑定 Spring 生态 | Python 原生（后推出 Java 版），多语言覆盖 |
| **企业级侧重** | 工作流可靠性、状态持久化、人工介入 | 分布式部署、多租户、安全沙箱、实时介入控制 |


官方问答给出了很直接的建议：**想构建以 Agent 为核心的智能应用，选择 AgentScope；想基于现有工作流集成 AI 能力，选择 Spring AI Alibaba**。

更具体地说，如果你的业务逻辑是“先分类，再路由到不同处理流程，最后记录结果”这种**有明确结构**的场景，Spring AI Alibaba 的 Graph 编排更合适。如果你的任务是“帮我研究一下这个领域并写份报告”这种**目标开放、路径未知**的场景，AgentScope 的自主 Agent 范式更对路。

两者并非完全对立，Spring AI Alibaba 也提供了 Agent Framework 层（ReactAgent），AgentScope 也在增强工程化能力。官方透露未来 Spring AI Alibaba 会在底层集成 AgentScope 的编排能力，走融合路线。



## 六、完整调用链图
<!-- 这是一张图片，ocr 内容为： -->
<img width="7157" height="5249" alt="全流程图" src="https://github.com/user-attachments/assets/62b22ed7-314e-4a95-bb27-526c80eef591" />

**1. ReactAgent 的核心循环（绿色区域）**

ReactAgent 运行在 **Graph Runtime** 之上，本质是一个由 Model Node 和 Tool Node 构成的图循环。一次完整的 `call()` 会经历：记忆检索 → 构建 Prompt → LLM 推理 → 判断返回类型。如果是 Tool Call，执行工具后把结果写回记忆，然后**再次进入模型调用**，直到模型返回纯文本才跳出循环。

**2. 多智能体的两种协作范式（紫色区域）**

Spring AI Alibaba 的 Multi-Agent 有两条路径：

+ **Agent as Tool**：主 Agent（控制器）把子 Agent 当作一个“工具”来调用。子 Agent 不直接与用户对话，执行完任务后把结果返回给主 Agent，由主 Agent 决定下一步。适合**集中式编排**场景。
+ **Handoff**：当前活跃的 Agent 主动把控制权交接给另一个 Agent，新 Agent 直接与用户交互。适合**跨领域对话、专家接管**场景。

**3. 多智能体编排模式（橙色区域）**

Agent Framework 预置了四类编排模式：

+ **SequentialAgent**：按顺序执行，上一个输出传给下一个
+ **ParallelAgent**：多个 Agent 并行处理同一输入，结果合并
+ **LlmRoutingAgent**：LLM 单次路由到最合适的子 Agent
+ **SupervisorAgent**：LLM 作为监督者，支持**多步骤循环路由**——子 Agent 完成后返回监督者，监督者决定继续路由还是 FINISH

**4. 记忆与状态（黄色区域）**

短期记忆由 `MemorySaver` / `RedisSaver` 管理，以 `thread_id` 区分会话。长期记忆通过 `ModelHook` 在模型调用前后自动加载和保存。**状态快照（Checkpoint）** 是 Graph Runtime 提供的底层能力，支持断点续传和 Human-in-the-loop。

**5. 工具与 MCP（粉色区域）**

工具来源有两种：本地 `@Tool` 注解的方法，以及通过 **MCP 协议**从外部服务发现的标准工具。ReactAgent 可以在一次对话中同时调度两者。



**<font style="color:rgb(51, 51, 51);">推荐资料：</font>**

+ [<font style="color:rgb(65, 131, 196);">https://java2ai.com/docs/overview</font>](https://java2ai.com/docs/overview)
+ [<font style="color:rgb(65, 131, 196);">https://java2ai.com/docs/quick-start</font>](https://java2ai.com/docs/quick-start)

+ ## 七、文档
+ + [<font style="color:rgb(65, 131, 196);">stage01-1.Spring AI 底座.md</font>](https://github.com/zhongke1214/spring-ai-alibaba-learning/blob/main/doc/1.Spring%20AI%20%E5%BA%95%E5%BA%A7.md)

