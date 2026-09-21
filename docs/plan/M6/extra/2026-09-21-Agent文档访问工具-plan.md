# Agent 文档访问工具（get_document / list_documents）+ r08b 消融 Plan

> 依据：2026-09-21-Agent文档访问工具-spec.md（已批准）｜ 语言：Java 21 + Spring Boot

## 架构概览

在现有 `tool` 包内新增两个 `@Component` 工具类，复用既有四件套接口（name/description/parametersSchema/execute）与 `@PostConstruct` 自注册机制。数据源直接复用 `DocumentRepository` 的三个已有查询方法，不新增 SQL、不改仓储。配置挂在 `rag.agent.*` 下，与 citationTopN 同级。

```
LLM( function calling )
   │ tool_call: get_document / list_documents
   ▼
AgentLoop.executeTool ── toolRegistry.find(name) ──► GetDocumentTool / ListDocumentsTool
   （通用分支，无需改代码）                                │
                                                        ▼
                                              DocumentRepository（findById / findAll / findChunksByDocumentId）
                                                        │
                                                        ▼
                                              PostgreSQL documents / chunks 表
```

**为什么 AgentLoop 零改动**：`executeTool` 对非 SearchKnowledgeBaseTool 走通用分支 `tool.execute(args)`；`ChatService.toToolSchemas()` 从 `toolRegistry.all()` 全量生成 schema。新工具 `@PostConstruct` 注册后自动出现在 AGENT 模式可用工具列表里。

## 核心数据结构

### 配置（RagProperties.Agent 扩字段）

```java
public static class Agent {
    private int citationTopN = 8;              // 已有，不动
    /** get_document 正文截断上限（字符数） */
    private int documentMaxChars = 8000;
    /** list_documents 默认/最大返回篇数 */
    private int listDocumentsLimit = 20;
}
```

yaml 对应：

```yaml
rag:
  agent:
    document-max-chars: ${RAG_AGENT_DOCUMENT_MAX_CHARS:8000}
    list-documents-limit: ${RAG_AGENT_LIST_DOCUMENTS_LIMIT:20}
```

### GetDocumentTool

| 项 | 值 |
|---|---|
| name | `get_document` |
| description | 「按文档 ID 读取知识库中某篇文档的正文内容。当搜索结果指向某篇文档、需要查看完整原文时使用。超长文档会被截断并标注。」 |
| 参数 | `document_id`（integer，必填，文档 ID，可从 list_documents 或检索结果中获得） |
| 返回 | 文本块：头部元信息行（文档 ID / 标题 / 总字符数 / chunk 数 / 是否截断）+ 正文；截断时末尾追加「（已截断，完整内容共 X 字符）」 |

### ListDocumentsTool

| 项 | 值 |
|---|---|
| name | `list_documents` |
| description | 「列出知识库中已入库的文档清单（ID、标题、摘要、片段数）。想了解知识库里有什么、或需要找到某篇文档的 ID 时使用；拿到 ID 后用 get_document 读全文。」 |
| 参数 | `limit`（integer，可选，返回篇数上限，默认取配置值，超过配置上限按上限截断） |
| 返回 | 编号列表文本：`[id] 标题（N 个片段）摘要…` |

## 模块设计

### GetDocumentTool（新建 `tool/tools/GetDocumentTool.java`）

**职责：** 按 ID 取文档正文，组装元信息+截断标注。
**依赖：** `DocumentRepository`、`ToolRegistry`、`RagProperties`。
**执行流程：**
1. 取 `document_id` 参数：缺失 → 返回「错误：缺少 document_id 参数」；非数字 → 返回「错误：document_id 必须是数字」。
2. `findById`：null → 返回「未找到文档 ID=X，可调用 list_documents 查看可用文档」。
3. 文档状态非 DONE 或 chunkCount=0 → 返回「文档 X 尚未完成入库」。
4. `findChunksByDocumentId`，按 seq 顺序以 `\n` 拼接正文。
5. 正文长度 > `documentMaxChars` → 截断 + 末尾标注总字符数。
6. 渲染：元信息头 + 正文。
7. 全程 try/catch，异常 → 返回「读取文档失败：<message>」文本（fail-open）。

### ListDocumentsTool（新建 `tool/tools/ListDocumentsTool.java`）

**职责：** 限量列出 DONE 文档的 ID/标题/摘要/chunk 数。
**依赖：** `DocumentRepository`、`ToolRegistry`、`RagProperties`。
**执行流程：**
1. 取 `limit` 参数：缺失用配置默认；非法或非正数 → 用默认；超过配置上限 → 按上限截断。
2. `findAll()`，过滤 `status == DONE && chunkCount > 0`，取前 limit 篇。
3. 每篇摘要：`findChunksByDocumentId(id)` 的第一个 chunk 内容截 80 字符（不足 20 篇的量级，查询开销可忽略）；取不到 chunk 时摘要留空。
4. 渲染：`共 N 篇文档（已入库完成），显示前 M 篇：` + 编号列表；M=0 → 「知识库暂无已入库文档」。
5. 全程 try/catch，fail-open 同上。

## 模块交互

- 启动：两个工具 `@PostConstruct register()` → `ToolRegistry` → 日志「已注册工具: get_document / list_documents」。
- 运行：LLM 发出 tool_call → `AgentLoop.executeTool` 通用分支 → `tool.execute(args)` → 结果文本作为 tool message 拼回上下文。
- 注意：这两个工具的结果**不进入** `ctx.addSources()`（只有 search_knowledge_base 进引用集），因此不直接影响 citation 判分口径；若 Agent 因读到全文而改写答案，影响体现在 Correctness/Completeness 上——这正是 r08b 要观测的。

## 文件组织

```
src/main/java/com/agenticrag/
├── tool/tools/
│   ├── GetDocumentTool.java        — 新建，F1
│   └── ListDocumentsTool.java      — 新建，F2
├── config/RagProperties.java       — 修改，Agent 内部类增 2 字段
src/main/resources/application.yaml — 修改，rag.agent 增 2 配置项
src/test/java/com/agenticrag/tool/tools/
│   ├── GetDocumentToolTest.java    — 新建，N4
│   └── ListDocumentsToolTest.java  — 新建，N4
docs/plan/M6/extra/2026-09-21-Agent文档访问工具-*.md — 四份文档
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 全文来源 | 拼接 chunks 表内容，不读文件系统 | 文档正文只落在 chunks 表；DB 是唯一事实源，避免 file_path 失效问题 |
| 截断策略 | 头部保留 + 末尾标注总字符数 | 与 citationTopN 思路一致：保头部保 token，标注让模型知道看到的是部分内容 |
| 列表过滤 | 只列 DONE 且 chunkCount>0 | 与 BM25 健康断言口径一致，避免 Agent 拿到空文档 ID |
| 摘要来源 | 首 chunk 截 80 字符 | 零新增存储字段；≤20 篇逐篇查一次开销可忽略 |
| 配置位置 | `rag.agent.*` 与 citationTopN 同级 | 同属「Agent 行为旋钮」，eval 期可用环境变量覆盖 |
| 引用集 | 不进 ctx.addSources() | 工具结果不是检索证据；保持 citation 判分口径与 r07d 可比 |
| 异常策略 | 工具内 catch-all 返回文本 | 复用现有 fail-open 约定，AgentLoop 已有外层兜底，双保险 |
