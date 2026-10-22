# Spring AI Alibaba Learning

从零开始、面向 Java 工程师的 Spring AI Alibaba 系统学习仓库——不是跑通一个 Hello World，而是真正具备用 Java 构建生产级 AI 应用的能力。

![JDK](https://img.shields.io/badge/JDK-17+-blue) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F) ![Spring AI Alibaba](https://img.shields.io/badge/Spring%20AI%20Alibaba-1.1.2.x-orange) ![License](https://img.shields.io/badge/License-MIT-yellow)

## 项目介绍

如果你是一名 Java 开发者，过去几年大概有过这样的感受：AI 应用的浪潮铺天盖地，但翻看教程和开源项目，清一色是 Python 的天下。但事实并非如此——你手里那套经过互联网时代考验的 Spring 技术栈，正在成为 AI 应用落地企业级场景的关键拼图。

**Spring AI Alibaba 是一个面向 Java 开发者的 Agentic AI 框架**，由 Spring 官方社区和阿里开源社区共同维护。它的定位可以用一个类比来理解：如果说 Spring AI 是 Java 领域的 LangChain，那么 Spring AI Alibaba 更像是 Java 领域的 LangGraph。前者解决"怎么接入 AI 模型"，后者解决"怎么让多个 AI 协同工作、怎么编排复杂的工作流"。

从架构上看，它分为三层：

- **底层是 Augmented LLM**：基于 Spring AI 的原子抽象，提供模型调用、消息、工具、向量存储、MCP 等基础能力，几行代码就能调用通义千问、DeepSeek 等模型。
- **中间是 Graph Runtime**：一个低级别的工作流和多智能体编排引擎，支持条件路由、嵌套图、并行执行、状态管理和持久化。
- **上层是 Agent Framework**：提供 ReactAgent、SequentialAgent、ParallelAgent、RoutingAgent 等预置智能体模式，快速搭建具备推理和行动能力的 Agent 应用。

从 2024 年 9 月开源至今，它已经积累了超过 10k Star，支持了从聊天机器人到多智能体工作流的完整场景。

## 学习路线

按顺序阅读，每一篇都对应 `doc/` 目录下的一篇文档：

| 序号 | 文档 | 内容简介 |
| --- | --- | --- |
| 00 | [环境与全局认知](./doc/00-环境与全局认知.md) | 环境搭建、框架分层架构、四种应用形态与技术选型 |
| 01 | [Spring AI 底座](./doc/01-Spring%20AI%20底座.md) | ChatClient 接入大模型、Prompt、消息角色、流式响应 |
| 02 | [ReactAgent：手把手带你搭建自己的 Agent](./doc/02-ReactAgent-%E6%89%8B%E6%8A%8A%E6%89%8B%E5%B8%A6%E4%BD%A0%E6%90%AD%E5%BB%BA%E8%87%AA%E5%B7%B1%E7%9A%84agent.md) | 用 ReactAgent 构建具备推理和行动能力的智能体 |
| 03 | [别让 Agent 重启就失忆](./doc/03-%E5%88%AB%E8%AE%A9%20Agent%20%E9%87%8D%E5%90%AF%E5%B0%B1%E5%A4%B1%E5%BF%86%EF%BC%9ACheckpoint%E3%80%81%E5%8E%8B%E7%BC%A9%E4%B8%8A%E4%B8%8B%E6%96%87%E3%80%81%E4%B8%9A%E5%8A%A1%E5%AD%97%E6%AE%B5%E5%88%86%E7%A6%BB%E8%AE%BE%E8%AE%A1%E5%AE%9E%E6%88%98.md) | Checkpoint、压缩上下文、业务字段分离设计实战 |
| 04 | [ReactAgent 生产级实践](./doc/04-Spring%20AI%20Alibaba%20ReactAgent%20%E7%94%9F%E4%BA%A7%E7%BA%A7%E5%AE%9E%E8%B7%B5%EF%BC%9A%E5%B7%A5%E5%85%B7%E8%B0%83%E7%94%A8%E3%80%81%E7%BB%93%E6%9E%84%E5%8C%96%E8%BE%93%E5%87%BA%E3%80%81HITL%20%E4%B8%8E%E4%B8%8A%E4%B8%8B%E6%96%87%E7%AE%A1%E7%90%86.md) | 工具调用、结构化输出、HITL 与上下文管理 |
| 05 | [多智能体编排 Multi-agent](./doc/05-%E5%A4%9A%E6%99%BA%E8%83%BD%E4%BD%93%E7%BC%96%E6%8E%92Multi-agent%EF%BC%9A%E5%B7%A5%E4%BD%9C%E6%B5%81%E3%80%81%E7%BC%96%E6%8E%92%E6%A8%A1%E5%BC%8F%E4%B8%8E%E8%AE%BE%E8%AE%A1%E5%8E%9F%E5%88%99.md) | 工作流、编排模式与设计原则 |
| 06 | [Graph 核心：StateGraph 条件边并联与审批流实战](./doc/06-Graph%E6%A0%B8%E5%BF%83%EF%BC%9AStateGraph%E6%9D%A1%E4%BB%B6%E8%BE%B9%E5%B9%B6%E8%81%94%E4%B8%8E%E5%AE%A1%E6%89%B9%E6%B5%81%E5%AE%9E%E6%88%98.md) | StateGraph 条件边、并联与审批流实战 |

## 快速开始

### 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | Spring AI Alibaba 基于 Spring Boot 3.x，最低要求 JDK 17 |
| Maven | 3.8.8+ | 建议使用 Temurin 发行版 JDK |
| API Key | 阿里云百炼平台 | 免费额度足够学习和测试 |

不需要 AI 或机器学习背景，只要你有 Spring Boot 开发经验即可。LangChain、Prompt Engineering、向量数据库这些概念，会在用到的时候解释清楚。

### 运行示例

```bash
git clone https://github.com/zhongke1214/spring-ai-alibaba-learning.git
cd spring-ai-alibaba-learning

# 配置百炼平台 API Key（环境变量或 application.yml）
export DASHSCOPE_API_KEY=your-api-key

# 运行 stage01 示例
mvn clean package -DskipTests
```

## 更新计划

- [x] 00 环境与全局认知
- [x] 01 Spring AI 底座
- [x] 02 ReactAgent：手把手搭建自己的 Agent
- [x] 03 Checkpoint、压缩上下文与状态管理
- [x] 04 ReactAgent 生产级实践（工具调用、结构化输出、HITL）
- [x] 05 多智能体编排 Multi-agent
- [x] 06 Graph 核心：StateGraph 条件边与审批流
- [ ] 07 RAG 进阶：权限过滤、引用溯源与企业 FAQ Agent
- [ ] 08 MCP：协议基础 Client/Server 与 ReactAgent 接入
- [ ] 09 A2A 与分布式协作：Nacos 注册发现与跨服务调用
- [ ] 10 多模态与语音 Agent：图文理解与 STT-Agent-TTS 三明治
- [ ] 11 可观测、评估与平台化：日志、Tracing、指标与 Admin/Studio
- [ ] 12 生产落地清单：安全、稳定性、成本与架构选型速查
- [ ] 13 综合实战：企业知识库助手 / 工单客服 Agent / 多智能体调研助手（选做）

## 相关资源

- 官方文档：<https://java2ai.com>
- 官方主仓库：<https://github.com/alibaba/spring-ai-alibaba>
- 官方示例仓库：<https://github.com/spring-ai-alibaba/examples>
- 我的 CSDN：<https://blog.csdn.net/qq_37878585>

## License

本项目基于 [MIT License](./LICENSE) 开源。
