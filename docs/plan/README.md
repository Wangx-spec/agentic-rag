# 开发方案总览（M1 → M6）

> 项目：agentic-rag —— 从零自研的 Java Agentic RAG 平台（简化版）
> 总蓝图：[../mvp-blueprint.md](../mvp-blueprint.md)（定位 / 技术栈 / 8 包分层 / 关键取舍）
> 原则：每个里程碑独立可验收、可演示；做完一个再开下一个；M2/M3 是后续所有里程碑的地基

## 里程碑索引

| 里程碑 | 方案 | 状态 | 核心交付 |
|---|---|---|---|
| M1 骨架 | —（已实现） | ✅ 完成 | LLM 流式对话 + Web 聊天页（commit 87721bc） |
| M2 RAG | [M2-RAG方案.md](M2-RAG方案.md) | 📋 待开发 | PDF 解析/分块/入库 + 向量+BM25 双通道 + RRF + 引用 |
| M3 Agent | [M3-Agent状态机方案.md](M3-Agent状态机方案.md) | 📋 待开发 | 状态机 + function calling 工具循环 + 参数校验 |
| M4 体验 | [M4-体验升级方案.md](M4-体验升级方案.md) | 📋 待开发 | 溯源 UI + Redis 持久记忆 + 前端三模式 |
| M5 多 Agent | [M5-多Agent方案.md](M5-多Agent方案.md) | 📋 待开发 | Leader 拆解 → SubAgent 并行 → Aggregator 汇总 |
| M6 收口 | [M6-工程化与评测方案.md](M6-工程化与评测方案.md) | 📋 待开发 | 评测闭环 + Dockerfile + K8s + README 完整化 |

## 依赖关系与执行顺序

```
M1 ✅
 └─▶ M2 (RAG)
      └─▶ M3 (Agent) ──┬─▶ M4 (体验)
                        └─▶ M5 (多 Agent)
                             └─▶ M6 (评测+部署收口)
```

- **严格线性**：M3 依赖 M2 的检索（SearchKnowledgeBaseTool）；M4/M5 依赖 M3 的模式分流；M6 收口依赖全部
- M4 与 M5 理论上可互换顺序（都只依赖 M3），但建议 M4 先——溯源 UI 让 M5 的多 Agent 演示效果更好

## 每个方案的统一结构

范围（做/不做）→ 核心设计（类/接口/表结构/链路）→ 文件清单 → 任务拆解（T 序号 + 验证方式）→ 验收标准（可观测）→ 关键取舍（面试可讲）

## 全局约定

- 技术栈：Java 17 + Spring Boot 3.3 + 手写 OpenAI 兼容客户端 + Qdrant（VectorStore 接口可插拔）+ PostgreSQL（元数据）+ SQLite FTS5 + Redis
- 不依赖 LangChain / Spring AI / ES / 消息队列（MVP 方案已定的取舍，勿中途加回）
- 每个里程碑完成后 git commit 一次，message 前缀 `M{n}:`
- 每个里程碑的「验收标准」就是它的 DoD，逐项过完才算完成
