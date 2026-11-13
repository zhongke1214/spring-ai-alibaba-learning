# MCP：协议基础、Client/Server 与 ReactAgent 接入

## 1. 前期准备

版本基准：Spring AI Alibaba 1.1.2.x + Spring AI 1.1.2 + JDK 17+

官方文档：https://java2ai.com

MCP 示例：https://github.com/spring-ai-alibaba/examples/tree/main/spring-ai-alibaba-mcp-example

Graph 节点绑定 MCP：https://java2ai.com/docs/1.0.0.2/tutorials/graph/mcp-node/

版本跟仓库约定对齐：

```text
JDK                 17+
Spring Boot         3.5.7
Spring AI           1.1.2
Spring AI Alibaba   1.1.2.2
```

前面 Tool 都写在应用进程里。工具一多、一跨语言、一要复用，就该上标准协议——

---

## 2. MCP 解决什么问题？

​	没有 MCP 时，每个 Agent 框架都在发明自己的 Tool 插件格式。天气服务想同时接 Spring AI、Cursor 和 Claude Desktop，就得分别适配三套接口——逻辑没变，变的只是描述格式和通信方式。这就是**烟囱式接入**：每多一个消费方，就多一份重复劳动，而且每一份都可能因为格式差异而出错。

MCP 的解法是把“能力提供”和“能力消费”拆成两层，中间用一个标准协议隔开。工具方只写一次 MCP Server，所有支持 MCP 的 Host 都能消费；Host 方接一次 MCP Client，就能连上所有 MCP Server。**从 M×N 的适配矩阵，压成 M+N 的两侧各自实现。**

```
同一套 Tools / Resources / Prompts 描述
        ▲
   MCP Server（你的业务 / 第三方）
        ▲
   MCP Client（Spring AI / Cursor / 其他 Host）
```

| 痛点               | MCP 给出的答案                        |
| ------------------ | ------------------------------------- |
| 工具接入烟囱化     | 标准协议，一次实现多处消费            |
| 跨语言             | Server 可用任意语言，Client 用 SDK    |
| 能力发现           | `listTools` 等动态枚举                |
| 本地进程或远程服务 | STDIO / Streamable HTTP（SSE 已弃用） |

MCP 的价值不在于它“发明了新能力”——Tools 在 Spring AI 里本来就有。它的价值在于**把能力的描述和连接方式标准化了**：你的业务服务只需要按 MCP 暴露一次，就能被 Spring AI Alibaba、Cursor、以及未来任何支持 MCP 的 Host 消费，而不需要为每个 Host 重写适配层。三角色的职责分离、传输方式的明确边界、三类能力的控制权划分，都是为了同一个目标——**让“接工具”从定制集成变成标准插拔。**

**关于SSE弃用：**

MCP 的 SSE（Server-Sent Events）传输方式被弃用，**并不是因为 SSE 技术本身不好，而是因为其最初的“HTTP+SSE”实现架构存在严重的工程瓶颈，已无法适应现代云原生和大规模部署的需求**。官方在协议版本 **2025-03-26** 中正式引入了 **Streamable HTTP** 作为替代方案，并逐步将 SSE 标记为弃用。

原先的 HTTP+SSE 是一种**有状态**的传输方式，它使用两个独立端点：一个长连接 SSE（`/sse`）用于服务端到客户端的推送，另一个 POST 端点（`/message`）用于客户端到服务端的消息。这种设计导致了以下核心问题：

- **扩展性极差 (Poor Scalability)**：SSE 要求客户端与**单个服务端副本**保持持久的长连接。这意味着你无法简单地在负载均衡器后面增加多个服务实例来横向扩展，因为连接是有状态的，会绑定在特定实例上，严重限制了系统的吞吐能力。
- **连接恢复困难 (Poor Reconnection)**：如果 SSE 长连接意外中断，客户端无法“从断点恢复”，只能重新建立一个新的连接，这可能导致会话上下文丢失，可靠性不佳。
- **基础设施复杂 (Infrastructure Complexity)**：维护大量长连接对服务器的资源消耗很大，并且与无状态的云原生架构（如 Serverless、容器化部署）理念相悖，增加了运维复杂性。

**替代方案：Streamable HTTP 的优势**

Streamable HTTP 被设计用来解决上述问题，同时保留流式响应的能力。它的核心改进在于：

- **无状态设计 (Stateless)**：它基于普通的 HTTP 请求，不强制要求持久长连接，因此可以轻松地在负载均衡器后**横向扩展**多个服务实例，完美契合现代云基础设施。
- **连接可恢复**：通过 `Last-Event-ID` 等机制（在早期版本中），它提供了更好的连接恢复能力（注：后续版本有调整，但设计初衷是提升可靠性）。
- **统一端点与灵活性**：它将通信统一到单个端点（如 `/mcp`），客户端通过标准的 POST/GET 请求通信，服务器可以按需将响应“升级”为 SSE 流进行推送，实现了按需的流式传输，简化了架构。

**当前状态与迁移**

SSE 传输方式目前处于**弃用过渡期**，但尚未被立即移除。官方为了向后兼容，仍会在规范中保留它一段时间，并计划在未来彻底移除对它的支持。在此期间，许多服务商（如 Keboola、Cloudflare）已开始或完成了弃用流程，例如将旧的 `/sse` 端点重定向或返回 `410 Gone` 状态码，引导客户端迁移到新的 `/mcp` 端点。

总结来说，MCP 弃用 SSE 是一次**面向工程实践的架构升级**。旧的 HTTP+SSE 方案在可扩展性和可靠性上存在硬伤，而新的 Streamable HTTP 方案在保留流式能力的同时，解决了这些问题，更符合现代软件基础设施的标准。

### 2.1 三角色

**Host 管“要不要调”，Client 管“怎么连”，Server 管“有什么”**

MCP 采用 **Client-Host-Server** 三层架构，每一层的职责边界是刻意划清的，不是随意拆分。

#### 2.1.1Host：会话的所有者，也是安全责任的承担者

Host 是拥有用户会话的那个应用——你的 Spring Boot Agent 服务、Cursor IDE、Claude Desktop。它的职责是**决定什么时候该调工具、调哪个工具、要不要征得用户同意**。官方规范把 Host 描述为“容器和协调器”：创建和管理多个 Client 实例、控制连接权限和生命周期、执行安全策略和同意要求、处理用户授权决策，以及协调上下文聚合。

一个关键原则是：**Server 不应该能看到整个对话，也不应该能“看穿”其他 Server**。Server 只接收必要的上下文信息，完整对话历史留在 Host 手里。这条设计原则直接决定了 MCP 的安全模型——隔离发生在 Host 层的协调逻辑里，而不是寄希望于每个 Server 自觉。

#### 2.1.2 Client：1:1 的隔离连接，不是“多路复用器”

每个 Client 由 Host 创建，与一个特定的 Server 保持**1:1 的状态化会话**。Client 负责协议协商、能力交换、双向消息路由、订阅和通知管理，以及维护 Server 之间的安全边界。

这意味着 Host 要连三个 Server，就创建三个 Client，彼此隔离。**一个 Server 的连接出问题，不会污染另一个 Server 的会话**。这也是为什么 MCP 的架构比“一个 Client 连所有 Server”更安全：隔离是结构性的，不是靠代码纪律维持的。

#### 2.1.3 Server：聚焦能力，不关心调用者是谁

Server 的职责非常收敛：暴露 Resources、Tools、Prompts 三类原语，独立运行，尊重安全约束，可以是本地进程也可以是远程服务。设计原则里写得很直白：**Server 应该“极其容易构建”**，复杂的编排责任交给 Host，Server 只专注于特定、定义良好的能力。

在 Spring Boot 语境里，这个划分很实用：**你的业务服务（制度检索、订单查询）可以是 Server；带 ReactAgent 的那个服务通常是 Host+Client**——它既拥有用户会话、决定何时调工具（Host），也通过内部 Client 去连其他 Server。

### 2.2 传输方式

STDIO 和 Streamable HTTP 是当前的两个标准，SSE 已经退场

MCP 目前定义了**两种标准传输**：stdio 和 Streamable HTTP。**HTTP+SSE 是旧版传输，已在 2025-03-26 规范中弃用**，由 Streamable HTTP 取代。如果现在选型，不需要在 SSE 和 Streamable HTTP 之间纠结——SSE 只在“客户端还不支持 Streamable HTTP”的过渡场景下才考虑。

**STDIO：本机进程，最简单**

Client 把 Server 作为**子进程**拉起，通过 stdin/stdout 交换 JSON-RPC 消息，消息以换行符分隔，stdout 上不允许写任何非 MCP 消息的内容。生命周期由操作系统进程边界管理——Server 进程在，连接就在；进程退出，连接就断。

**适合**：本地工具、IDE 插件、单机 Agent、Claude Desktop 这类“一个人在自己机器上跑”的场景。部署最简单，不需要处理网络认证和跨机器安全。

**Streamable HTTP：远程服务，独立进程，多客户端**

Server 作为独立进程运行，提供**单个 HTTP endpoint**，同时支持 POST 和 GET。客户端发送的每条 JSON-RPC 消息是一个新的 HTTP POST；Server 可以返回单个 JSON 对象，也可以启动一个 request-scoped 的 SSE 流来推送多条消息。

**适合**：远程共享服务、多客户端接入、需要认证和网络控制的部署。但代价是安全责任更重：Server **必须**验证 `Origin` 头以防止 DNS rebinding，本地运行时**应该**只绑定 `127.0.0.1`，并且**应该**为所有连接实现认证。

### 2.3 三类能力

MCP 的全称是 **Model Context Protocol**，它要解决的是“AI 应用怎么标准化地获取外部上下文和能力”。那“上下文和能力”具体以什么形式提供？答案就是这三类原语：

- **Tools**：Server 能提供的**可执行动作**；
- **Resources**：Server 能提供的**只读数据**；
- **Prompts**：Server 能提供的**预定义提示模板**。

**为什么是这三类，而不是别的**

因为 MCP 在设计时把“AI 应用需要从外部获取的东西”归纳成了三种**控制权归属不同**的交互模式：

- 有些事需要**模型自己决定做不做**（查库、调 API）→ Tools；
- 有些数据需要**应用决定给不给模型看**（文件、配置、日志）→ Resources；
- 有些流程需要**用户主动触发**（一键总结、诊断向导）→ Prompts。

这三类覆盖了“模型主动做事”“应用被动供数据”“用户主动启动流程”三种典型场景。MCP 没有发明新概念，它只是把这三类交互**标准化**了。

假设你有一个“企业 IT 运维助手”，用户是公司员工，助手背后连着几个 MCP Server：订单系统、代码仓库、日志平台、工单系统。

#### 2.3.1 Tools ：模型自己决定“我要动手做一件事“

**什么时候用**：当模型需要**执行一个动作**才能回答问题时，就做成 Tool。它的特征是**有输入、有输出、会改变状态或查询实时数据**。

**实际案例**：员工问“我上个月提交的工单处理到哪了？”

模型看到工具列表里有一个 `queryTicket(ticketId, userId)`，它判断“这问题得查工单系统”，于是自己发起调用，拿到“处理中，已分配给网络组”的结果，再组织成回答。**用户没有说“你去查工单”，应用代码也没有写死“这里必须调工具”，是模型自己看着办的。**

再比如员工说“帮我重启一下测试环境的服务”，模型判断需要调 `restartService(env, serviceName)`，这也是 Tool——因为它**执行了一个动作**，而不只是“看数据”。

**判断标准**：这件事需要**模型自主决定做不做、什么时候做**，而且做完会**产生一个动作或拿到实时结果**，就做成 Tool。它是 Agent 的主战场，因为 Agent 的核心就是“模型自主决定行动”。

#### 2.3.2 Resources：应用决定“这次给它看什么”，模型被动看到

**什么时候用**：当你希望**应用代码控制**“这次对话要不要把某份数据放进上下文”，而不是让模型自己去找，就做成 Resource。它的特征是**只读、不执行动作、由应用附加**。

**实际案例**：员工打开“代码审查”功能，你的应用代码在会话开始时，自动把 `file:///src/main.java` 的内容附加进上下文。模型一上来就“看到”了这段代码，直接开始审查。**模型不会说“我要去读 main.java”，它只是被动地看到了。** 决定权在你的应用代码：这次审查要不要带这个文件、带哪几个文件。

再比如“故障排查”会话，应用代码自动把最近 24 小时的 `file:///logs/error.log` 附加进上下文，模型一上来就能看到日志。**不是模型自己去翻日志**，是应用决定“这次排查需要这份日志”。

**判断标准**：如果你想让**应用代码在会话开始时自动加载一份数据给模型看**，就做成 Resource。如果你想让**模型自己去翻文件**，那应该是 Tool（模型控制）。两者的分界线是：**谁决定“拿这份数据”**——应用决定 → Resource，模型决定 → Tool。

#### 2.3.3 Prompts：用户自己点一下，启动一个预设流程

**什么时候用**：当你希望**用户显式触发**一个固定流程，而不想每次手打一遍需求，就做成 Prompt。它的特征是**用户主动选择、返回一段结构化提示词、不参与模型自主决策**。

**实际案例**：员工在输入框里敲 `/summarize`，或者点菜单里的“总结会议纪要”。Server 返回一段已经组织好的模板：“请总结以下会议记录，重点列出待办事项、责任人和截止时间”。用户不用每次手打这段描述，点一下就跑。

再比如“诊断慢机器”这个菜单项：用户点一下，Server 返回“请分析以下日志和代码，找出性能瓶颈，并给出优化建议”，同时可能自动带上相关上下文。**用户主动触发，模型据此执行。**

**判断标准**：如果这件事是**用户想主动启动的固定流程**，而且每次启动都需要同一套提示词，就做成 Prompt。它不参与模型的自主决策——模型不会“自己决定用一个 Prompt 模板”，是用户按下了那个按钮。

## 3. Spring AI Alibaba 里怎么落地	

#### 3.1 Tool → ToolCallback 的转换链路

**Spring AI Alibaba 并没有让 Agent 直接去“认识” MCP 协议，而是在中间加了一层适配层，把远程 MCP Server 的 `ToolCallback` 静默地“翻译”成本地 Agent 能直接执行的 `CodeactTool`。** 对 Agent 来说，它只是在调用一个本地工具，完全不需要知道背后连接的是一个本地进程还是远程 HTTP 服务。

**完整的转换链路：**

官方文档给出的工作流程非常清晰，整条链路分为四步：

```
MCP 服务配置（mcp-servers.json / application.yml）
        │
        ▼
Spring AI MCP Client Starter
（自动创建 ToolCallbackProvider，负责握手和工具发现）
        │
        ▼
McpDynamicToolFactory
（将 ToolCallback 适配为 CodeactTool）
        │
        ▼
CodeactToolRegistry
        │
        ▼
Agent 可调用工具
```

**第一步：Spring AI MCP Client Starter 负责“连上并发现”。** 它读取你的配置，根据传输方式（STDIO 拉起子进程，或 Streamable HTTP 建立远程连接），完成协议握手，然后通过 `listTools` 拿到 Server 暴露的所有工具，包装成标准的 `ToolCallback`，放进一个 `ToolCallbackProvider` 里。这一步是 Spring AI 原生的 MCP 客户端能力，Spring AI Alibaba 没有重写，只是消费它的产出。

**第二步：`McpDynamicToolFactory` 负责“翻译”。** 这是 Spring AI Alibaba 适配层的核心。它从 `ToolCallbackProvider` 中取出每一个 MCP 工具，交给内部的 `McpToolCallbackAdapter`，把 Spring AI 的 `ToolCallback` 封装成 Agent 执行引擎能识别的 `CodeactTool`。翻译过程中，MCP 工具的 `inputSchema` 会被自动解析为 `CodeactTool` 的参数定义，Agent 生成的调用代码（如 `mcp.search_documents(query="关键词", limit=10)`）就是根据这份 Schema 自动生成的。

**第三步：`CodeactToolRegistry` 负责“登记”。** 适配完成的 `CodeactTool` 被注册到注册表中，Agent 在后续的 ReAct 循环里就能看到并调用它们。启动时如果看到日志 `McpDynamicToolFactory#createTools - reason=创建MCP工具成功, toolName=xxx`，说明这条链路走通了。

**第四步：Agent 按需调用。** Agent 执行引擎在需要时从 `CodeactToolRegistry` 取出工具，执行 `CodeactTool` 的调用逻辑。`CodeactTool` 内部再桥接回 `ToolCallback`，最终由 MCP Client 把 JSON-RPC 请求发到 Server 上真正执行。

#### 3.2 两种传输的配置入口

配置层面的差异，根源在于**连接的生命周期由谁管理**。

**STDIO：由 MCP Client 管理子进程生命周期。** 你只需要提供一个 `mcp-servers.json`，告诉 Client“用什么命令拉起什么进程”。Client 在启动时执行这个命令，把 Server 作为子进程拉起，通过 stdin/stdout 通信，进程退出连接就断。所以配置的核心是 `command` 和 `args`：

```
spring.ai.mcp.client:
  enabled: true
  type: SYNC
  toolcallback.enabled: true          # 必须启用，否则拿不到 ToolCallback
  stdio.servers-configuration: classpath:mcp-servers.json
```

`mcp-servers.json` 里写的是进程启动命令，不是 URL：

```
{
  "mcpServers": {
    "local-tool": {
      "command": "python",
      "args": ["/path/to/mcp_server.py"]
    }
  }
}
```



**Streamable HTTP：Server 是独立进程，Client 只负责建立网络连接。** Server 的生命周期不由你的应用管理，你只需要提供一个 URL，Client 通过 HTTP POST/GET 与它通信。所以配置的入口是 `streamable-http.connections`，而不是 `stdio.servers-configuration`：

```
spring.ai.mcp.client:
  streamable-http:
    connections:
      remote-server:
        url: https://mcp.example.com
        endpoint: /mcp
```

两者是**并列的传输配置入口**，不会同时生效。选 stdio 就配 `servers-configuration`，选远程就配 `streamable-http.connections`。SSE 作为旧版传输已被 Streamable HTTP 取代。

**认证怎么带：stdio 和远程的注入位置不同？**

**STDIO 场景下，认证头是“交给中间人转发的”。** 因为 Server 是本地子进程，它自己通常不处理 HTTP 认证。常见的做法是通过 `npx mcp-remote` 这类桥接工具，把认证头作为 `--header` 参数传给桥接进程，由它代为附加到后续请求上：

```
{
  "args": [
    "mcp-remote",
    "https://api.example.com/mcp",
    "--header", "Authorization: Bearer ${API_TOKEN}"
  ]
}
```

**Streamable HTTP 场景下，认证头由 Client 的传输层直接附加。** Spring AI 的 MCP Client 在建立 HTTP 连接时，可以配置请求头。具体到 Spring AI Alibaba 的封装，通常通过 `connections` 下的配置或编程式 `McpClient` 构建来注入。

注意：Bearer Token 在企业的合规审计面前是脆弱的。 它不会过期、无法按用户粒度撤销、泄露后影响面大。更稳妥的路径是 **OAuth 2.1 + JWT**，由授权服务器签发短时效令牌，网关层做第一道校验，MCP Server 侧再做第二道，形成双重校验。这和前面 RAG 权限那几节是同一个逻辑：**安全边界要落在结构上，不能依赖一个长期有效的静态密钥。**

## 4. 案例：两个进程跑通 MCP

两个模块，两个进程：

```text
mcp-server-demo  :8081   ← MCP Server（只有能力，不持有对话）
        ▲  Streamable HTTP，单端点 POST /mcp
        │
mcp-client-demo  :8080   ← Host + Client（ReactAgent 在这里）
```

### 4.1 Server 侧：依赖与传输配置

Server 不需要 Agent Framework，只需要 MCP Server Starter：

```xml
<!-- 只做 Server 端；不要引 agent-framework -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    <version>1.1.2</version>
</dependency>
```

`application.yml`：

```yaml
server:
  port: 8081

spring:
  ai:
    mcp:
      server:
        name: local-tools
        version: 1.0.0
        protocol: STREAMABLE        
        type: SYNC                  # 只注册同步方法，返回 Mono/Flux 的会被过滤掉
        annotation-scanner:
          enabled: true             # 扫描 @McpTool / @McpResource / @McpPrompt
```

### 4.2 Server 侧：一个类暴露三类原语

```java
package space.kmind.zk.mcpServer.server;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * MCP Server 侧：只回答「我有什么能力」，不关心谁在调、为什么调。
 * 对应正文 2.1.3 —— Server 的职责要极其收敛。
 */
@Component
public class LocalMcpCapabilities {

    /** 白名单根目录：所有文件访问都被限制在这里面 */
    private static final Path BASE = Path.of("mcp-data").toAbsolutePath().normalize();

    // ==================== Tools：模型自己决定调不调 ====================

    @McpTool(name = "get_current_time", description = "获取服务器当前时间，ISO-8601 格式")
    public String getCurrentTime() {
        return ZonedDateTime.now().toString();
    }

    @McpTool(
            name = "read_text_file",
            description = "读取白名单目录 mcp-data 下的文本文件；filename 是相对文件名，例如 hello.txt")
    public String readTextFile(
            @McpToolParam(description = "相对文件名，不含路径分隔符", required = true) String filename) {
        return read(filename);
    }

    // ==================== Resources：应用决定给不给模型看 ====================

    @McpResource(
            uri = "file:///mcp-data/{name}",
            name = "whitelisted-file",
            description = "白名单目录下某个文本文件的内容；由 Host 决定何时附加到上下文")
    public String whitelistedFile(String name) {
        return read(name);
    }

    // ==================== Prompts：用户显式触发 ====================

    @McpPrompt(name = "daily-report", description = "生成日报模板，用户在前端点一下即可触发")
    public GetPromptResult dailyReport(
            @McpArg(name = "topic", description = "日报主题", required = true) String topic) {
        String text = "请基于今天的工作记录，生成一份「" + topic + "」日报：\n"
                + "1) 今天完成的三件事；\n"
                + "2) 当前的一个阻塞点；\n"
                + "3) 明天的第一步。";
        return new GetPromptResult("daily-report",
                List.of(new PromptMessage(Role.USER, new TextContent(text))));
    }

    // ==================== 共用：白名单校验 ====================

    /**
     * 安全点：先 normalize 再比 startsWith。
     * 少了这一步，filename 传 "../../etc/passwd" 就能读到白名单外的文件。
     */
    private String read(String name) {
        if (name == null || name.isBlank()) {
            return "ERROR: filename required";
        }
        Path target = BASE.resolve(name).normalize();
        if (!target.startsWith(BASE)) {
            return "ERROR: file not allowed";
        }
        if (!Files.isRegularFile(target)) {
            return "ERROR: file not found";
        }
        try {
            return Files.readString(target);
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
```

三个注解的语义差别，正是正文 2.3 讲的控制权划分：

| 注解 | 谁决定用不用 | 在 Client 侧的表现 |
| --- | --- | --- |
| `@McpTool` | 模型 | 变成 `ToolCallback`，Agent 能自己调 |
| `@McpResource` | Host 的应用代码 | **不会**进 `ToolCallbackProvider`，模型根本看不到 |
| `@McpPrompt` | 用户 | 走 `prompts/get`，由前端入口触发 |

同一个文件，用 `@McpTool` 暴露就是「模型想读就自己读」，用 `@McpResource` 暴露就是「你决定什么时候塞给它」。案例里两个都写了，底层走的是同一个 `read()` 方法——差别只在控制权归谁。

#### `@McpResource`：把一条路拆成四步

Resource 的完整生命周期长这样：

```text
① 注册   启动时 annotation-scanner 扫到 @McpResource；
         uri 里带 {占位符} → 注册进 resources/templates/list（URI 模板）
         uri 是完整地址   → 注册进 resources/list（静态资源）
② 发现   Host 调 client.listResourceTemplates() 拿到模板清单（模型看不到）
③ 读取   Host 把占位符填成具体值，调 client.readResource("file:///mcp-data/hello.txt")；
         Server 拿真实 URI 匹配模板 → {name} 绑定到方法参数 name → 执行方法
④ 使用   拿到文本之后塞不塞进 Prompt、什么时候塞，全由 Host 代码说了算
```

#### `@McpPrompt`：定义在 Server，触发在用户

```text
前端点「生成日报」按钮
  → Host 调 client.getPrompt("daily-report", {topic: "MCP 接入"})
  → Server 执行 dailyReport("MCP 接入")，返回渲染好的消息列表
  → Host 拿着这个消息去调模型
```

### 4.3 换 STDIO：只改配置，不改代码

Server 那 60 行一行不动，换 starter 加两行配置就是另一种传输：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server</artifactId> 
</dependency>
```

```yaml
spring:
  main:
    web-application-type: none     # stdio 模式没有 Web 容器
  ai:
    mcp:
      server:
        stdio: true
        name: local-tools
        version: 1.0.0
        type: SYNC
```

### 4.4 Client 侧：连接配置与 Agent 装配

依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

连接配置。

```yaml
server:
  port: 8080

spring:
  ai:
    dashscope:
      api-key: ${AI_DASHSCOPE_API_KEY}
    mcp:
      client:
        enabled: true
        type: SYNC
        request-timeout: 30s
        toolcallback:
          enabled: true           # 默认就是 true；置 false 后不再产生 ToolCallbackProvider
        streamable-http:
          connections:
            local-tools:
              url: http://localhost:8081
              endpoint: /mcp      # 默认就是 /mcp，写出来是为了和旧 SSE 的 /sse 对照
```

Agent 装配。**MCP 工具走 `toolCallbackProviders`，不是 `tools`**：

```java
package space.kmind.zk.springaialibabalearning.stage08;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author zk
 * @date 2026/11/11 16:57
 */
@Tag(name = "Stage08-MCP", description = "协议基础ClientServer与ReactAgent接入")
@RestController
public class McpAgentController {

    private final ReactAgent agent;
    private final ChatModel chatModel;

    public McpAgentController(ChatModel chatModel, SyncMcpToolCallbackProvider mcpTools) {
        this.chatModel = chatModel;
        this.agent = ReactAgent.builder()
                .name("mcp_react_agent")
                .model(chatModel)
                .systemPrompt("""
                        你是运维助手。查时间用 get_current_time，读文件用 read_text_file。
                        工具返回以 ERROR 开头时，如实转述错误，禁止编造文件内容。
                        """)
                // ToolCallbackProvider 由 agent-framework 适配成可执行工具（正文 3.1 那条链路）
                .toolCallbackProviders(mcpTools)
                .saver(new MemorySaver())
                .build();
    }

    @Operation(summary = "测试调用McpTool服务")
    @PostMapping("/api/s81/chat")
    public Map<String, Object> chat(@RequestBody ChatReq req) throws Exception {
        RunnableConfig config = RunnableConfig.builder()
                .threadId(req.threadId() == null ? "mcp-1" : req.threadId())
                .build();

        Optional<OverAllState> out = agent.invoke(req.q(), config);
        if (out.isEmpty()) {
            return Map.of("status", "empty");
        }

        List<Message> messages = out.get().value("messages", new ArrayList<>());

        // 最终回答 = 最后一条不带 toolCalls 的 AssistantMessage
        String answer = "";
        for (Message m : messages) {
            if (m instanceof AssistantMessage am && !am.hasToolCalls()) {
                answer = am.getText();
            }
        }

        // 实际调了哪些工具 —— 验证 MCP 链路最直接的证据
        List<String> toolsUsed = messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .flatMap(am -> am.getToolCalls().stream())
                .map(AssistantMessage.ToolCall::name)
                .toList();

        return Map.of("answer", answer, "toolsUsed", toolsUsed);
    }

    public record ChatReq(String q, String threadId) {
    }


@Autowired
private List<McpSyncClient> mcpClients;

@GetMapping("/api/s81/resource")
@Operation(summary = "测试调用McpResource服务")
public Map<String, Object> readResource(@RequestParam(defaultValue = "hello.txt") String name) {
    McpSyncClient client = mcpClients.get(0);

    // ② 发现
    List<String> templates = client.listResourceTemplates().resourceTemplates().stream()
            .map(t -> t.uriTemplate() + " （" + t.description() + "）")
            .toList();

    // ③ 读取
    var result = client.readResource(new McpSchema.ReadResourceRequest("file:///mcp-data/" + name));

    // contents 是 ResourceContents 的列表，文本文件就是 TextResourceContents
    String text = result.contents().stream()
            .map(c -> ((McpSchema.TextResourceContents) c).text())
            .collect(Collectors.joining());

    // ④ 之后的每一步都是你的代码：拼进 systemPrompt、丢给 chatModel 总结、或者直接忽略
    return Map.of("templates", templates, "text", text);
}


    @GetMapping("/api/s81/prompt/daily-report")
    @Operation(summary = "测试McpPrompt")
    public Map<String, Object> dailyReport(@RequestParam String topic) {
        McpSyncClient client = mcpClients.get(0);

        // ① 发现：前端「模板选择」下拉框的数据就来自这里
        List<String> prompts = client.listPrompts().prompts().stream()
                .map(p -> p.name() + " （" + p.description() + "）")
                .toList();

        // ② 取模板：arguments 的 key 要和 @McpArg(name = "topic") 对上
        var result = client.getPrompt(new McpSchema.GetPromptRequest("daily-report", Map.of("topic", topic)));

        // ③ 返回的是渲染好的消息列表，取出文本
        String rendered = result.messages().stream()
                .map(m -> ((McpSchema.TextContent) m.content()).text())
                .collect(Collectors.joining("\n"));

        // ④ 谁调模型、用哪个模型，Host 决定
        String answer = chatModel.call(rendered);
        return Map.of("prompts", prompts, "answer", answer);
    }
}
```

### 4.5 启动顺序与验证

顺序不能反：**先 Server，后 Client**。Client 启动时就要握手、`listTools`，Server 不在的话它拿到的工具列表是空的，而且不会自动重试。

**第一步，确认 Server 的 MCP 端点活着。**

```bash
curl -s -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-06-18",
        "capabilities":{},
        "clientInfo":{"name":"curl","version":"1.0"}}}'
```

期望返回里有 `result.serverInfo`。两个细节容易踩：`Accept` 必须**同时**包含 `application/json` 和 `text/event-stream`，只给前者会被拒；响应头里会带 `Mcp-Session-Id`，后续 `tools/list`、`tools/call` 要把它带上。

**第二步，看 Client 启动日志。**

```
McpDynamicToolFactory#createTools - reason=创建MCP工具成功, toolName=get_current_time
McpDynamicToolFactory#createTools - reason=创建MCP工具成功, toolName=read_text_file
```

**第三步，发三个请求。**

| 请求体 | 期望 `toolsUsed` | 验证了什么 |
| --- | --- | --- |
| `{"q":"现在几点？"}` | `[get_current_time]` | 工具发现 + 调用链通 |
| `{"q":"读一下 hello.txt"}` | `[read_text_file]` | 带参数的工具调用 |
| `{"q":"读一下 ../../etc/passwd"}` | `[read_text_file]` | 白名单生效，回答里是 `ERROR: file not allowed` |

第三个请求值它验证的不是"模型聪不聪明"，而是**安全边界落在哪一层**：模型完全可能老老实实按用户说的去调工具，拦截发生在 Server 的 `read()` 里——`normalize()` 之后 `startsWith(BASE)` 判断失败，直接返回错误。这和 RAG 权限那几节是同一个思路：**安全边界要落在结构上，不能指望模型自己会拒绝。**

**第四步，绕过 Agent 直接读 Resource。**

验证两个请求：

```text
GET /api/s81/resource?name=hello.txt   → templates 有 file:///mcp-data/{name}，text 是文件内容
GET /api/s81/resource?name=../etc/passwd → 白名单同样生效：read() 返回 ERROR: file not allowed
```

**第五步，调 Prompt。**

```text
GET /api/s81/prompt/daily-report?topic=MCP接入
→ prompts: [daily-report （生成日报模板，用户在前端点一下即可触发）]
→ answer: 模型按模板里那三条要求生成的日报
```

### 4.6 如果要上生产需要注意：

**第一，Client 和 Server 是 1:1 的，Server 挂了 Client 就不可用。** MCP 协议本身不提供负载均衡和故障转移。Spring AI Alibaba 的解法是结合 **Nacos 做服务注册与发现**，把 MCP Server 信息注册到 Nacos，Client 侧通过注册中心获取实例列表，实现动态感知和负载均衡。如果你的 Server 是单点部署，这在生产环境是一个明确的可用性缺口。

**第二，存量服务转 MCP 不要重写业务代码。** Spring AI Alibaba 提供了基于 Nacos 的**代理层方案**：把 Nacos 中注册的普通 HTTP/Dubbo 服务信息转换成 MCP Server 协议，由代理应用完成协议转换和请求转发，原有业务代码一行不改。不必为了接 MCP 把整个服务重写一遍。

**第三，安全边界不是 Server 一个人的事。** MCP 规范明确要求 Streamable HTTP Server 验证 `Origin`、限制绑定地址、实现认证。而 Host 侧的授权决策、同意管理、跨 Server 隔离，同样属于安全链条的一部分。这和前面 RAG 权限那几节是同一个逻辑。

**第四，`type: SYNC` 和 `ASYNC` 的选择会影响调用链。** 同步模式适合工具调用链路短、需要即时返回的场景；如果工具本身耗时较长，同步调用会阻塞 Agent 的决策循环。这个配置在 `application.yml` 里只是两行，但它决定了 Host 侧的线程模型和超时行为，需要在压测阶段验证清楚。

还有一条容易被忽略的：**MCP 的会话不按用户隔离。** 自动配置出来的 `McpSyncClient` 是应用级单例，一个 Client 面对所有用户。多租户场景下，用户身份不能靠 MCP 会话区分，得在 Host 侧自己传（`RunnableConfig.metadata` → `ToolContext`）。

到这里，案例把正文的结论都覆盖了一遍：三角色、两种传输、适配链路、生产的四个缺口，以及三类原语各自的完整链路——Tool 模型自主决策（第三步）、Resource 应用控制（第四步）、Prompt 用户触发（第五步）。

---

## 下一篇预告

清单下一阶段是 **A2A**：Agent 不再只调 Tool，而是跨服务发现并调用另一个 Agent。

---

## 官方参考

- https://java2ai.com/docs/1.0.0.2/practices/mcp/spring-ai-mcp-starter-client/  
- https://java2ai.com/docs/1.0.0.2/tutorials/graph/mcp-node/  
- https://github.com/spring-ai-alibaba/examples/tree/main/spring-ai-alibaba-mcp-example  
- https://spring.io/blog/2025/09/16/spring-ai-mcp-intro-blog  

