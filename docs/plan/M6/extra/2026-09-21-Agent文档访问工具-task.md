# Agent 文档访问工具 + r08b 消融 Tasks

> 依据：2026-09-21-Agent文档访问工具-spec.md + plan.md（已批准）

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/agenticrag/tool/tools/GetDocumentTool.java` | F1 按 ID 取文档正文（截断+标注） |
| 新建 | `src/main/java/com/agenticrag/tool/tools/ListDocumentsTool.java` | F2 限量列出文档（ID/标题/摘要/chunk 数） |
| 修改 | `src/main/java/com/agenticrag/config/RagProperties.java` | Agent 内部类增 documentMaxChars / listDocumentsLimit |
| 修改 | `src/main/resources/application.yaml` | rag.agent 增两个可环境变量覆盖的配置项 |
| 新建 | `src/test/java/com/agenticrag/tool/tools/GetDocumentToolTest.java` | F1 正常+异常路径单测 |
| 新建 | `src/test/java/com/agenticrag/tool/tools/ListDocumentsToolTest.java` | F2 正常+异常路径单测 |

## T1: RagProperties 增配

**文件：** `src/main/java/com/agenticrag/config/RagProperties.java`
**依赖：** 无
**步骤：**
1. 在 `Agent` 内部类 `citationTopN` 下方新增 `documentMaxChars`（默认 8000）与 `listDocumentsLimit`（默认 20）两个 int 字段，附中文注释。

**验证：** `./mvnw compile -q` 编译通过。

## T2: application.yaml 增配

**文件：** `src/main/resources/application.yaml`
**依赖：** T1
**步骤：**
1. 在 `rag.agent.citation-top-n` 同级的 `agent:` 块内新增两行：
   - `document-max-chars: ${RAG_AGENT_DOCUMENT_MAX_CHARS:8000}`
   - `list-documents-limit: ${RAG_AGENT_LIST_DOCUMENTS_LIMIT:20}`
2. 附一行注释说明用途。

**验证：** `./mvnw compile -q` 通过；`grep -n "document-max-chars" src/main/resources/application.yaml` 能查到，且缩进与 `citation-top-n` 对齐（注意上次 YAML 缩进踩坑）。

## T3: GetDocumentTool 实现

**文件：** `src/main/java/com/agenticrag/tool/tools/GetDocumentTool.java`
**依赖：** T1
**步骤：**
1. 按 plan.md「GetDocumentTool」设计实现：`@Component` + 构造注入 `DocumentRepository` / `ToolRegistry` / `RagProperties`；`@PostConstruct register()` 自注册。
2. name=`get_document`，description 与参数 schema 按 plan 表（document_id 必填 integer）。
3. execute 流程：参数校验 → findById → 状态检查 → 拼 chunk 正文 → 超 documentMaxChars 截断并标注总字符数 → 元信息头+正文渲染。
4. 全方法 try/catch，异常返回「读取文档失败：...」文本，不抛出。

**验证：** `./mvnw compile -q` 编译通过。

## T4: ListDocumentsTool 实现

**文件：** `src/main/java/com/agenticrag/tool/tools/ListDocumentsTool.java`
**依赖：** T1
**步骤：**
1. 按 plan.md「ListDocumentsTool」设计实现：同样的组件/注册骨架。
2. name=`list_documents`，description 与参数 schema 按 plan 表（limit 可选 integer）。
3. execute 流程：limit 解析（缺失/非法→默认，超上限→截断）→ findAll 过滤 DONE 且 chunkCount>0 → 取前 limit 篇 → 每篇取首 chunk 截 80 字符作摘要 → 渲染编号列表；空结果返回「知识库暂无已入库文档」。
4. 全方法 try/catch，fail-open。

**验证：** `./mvnw compile -q` 编译通过。

## T5: GetDocumentToolTest 单测

**文件：** `src/test/java/com/agenticrag/tool/tools/GetDocumentToolTest.java`
**依赖：** T3
**步骤：**
1. Mockito mock `DocumentRepository` + `ToolRegistry`，构造真实 `RagProperties`（documentMaxChars 设小值如 50 便于测截断）。
2. 用例：①正常返回含元信息头与正文；②超长截断且含「已截断」与总字符数；③ID 不存在返回「未找到」；④缺参数/非数字参数返回错误提示；⑤仓储抛异常时返回「读取文档失败」文本（fail-open，不抛出）。

**验证：** `./mvnw test -Dtest=GetDocumentToolTest -q` 全绿。

## T6: ListDocumentsToolTest 单测

**文件：** `src/test/java/com/agenticrag/tool/tools/ListDocumentsToolTest.java`
**依赖：** T4
**步骤：**
1. 同样的 mock 骨架。
2. 用例：①正常列表含 ID/标题/chunk 数/摘要；②默认 limit 生效（传超量文档只返回配置上限）；③显式 limit 生效且超上限被截断；④非 DONE 文档被过滤；⑤空库返回「暂无已入库文档」；⑥仓储抛异常 fail-open。

**验证：** `./mvnw test -Dtest=ListDocumentsToolTest -q` 全绿。

## T7: 全量测试 + 注册冒烟

**文件：** 无（验证任务）
**依赖：** T5、T6
**步骤：**
1. `./mvnw test -q` 全量测试。
2. 确认既有测试无回归（尤其 ToolSchemaValidatorTest / ChatServiceTest）。

**验证：** 全量测试通过；两新工具注册逻辑被单测覆盖（register 调用一次）。

## T8: 提交代码

**文件：** 上述全部
**依赖：** T7
**步骤：**
1. `git add` 相关文件，commit message：`feat(agent): 新增 get_document/list_documents 文档访问工具（r08b 消融前置）`。
2. push 三分支（main/dev/feature）与远端同步。

**验证：** `git log --oneline -1` 可见；`git status` 干净。

## T9: r08b 消融评测

**文件：** 无（评测任务）
**依赖：** T8
**步骤：**
1. 作答（r07d 同配置，唯一变量=两新工具已注册）：
   ```bash
   cd /Users/miaobao/agent-projects/agentic-rag
   EVAL_ERAG_CHAT_MODE=agent ROUNDS_DIR=eval-answers/rounds-era2 \
     ./scripts/run-erag-round.sh r08b hybrid 1 1 1 > eval-answers/rounds-era2/r08b-run.log 2>&1
   ```
2. 完成标志：日志出现「EnterpriseRAG 评测完成，answers=...」。
3. 判分：
   ```bash
   ROUNDS_DIR=eval-answers/rounds-era2 ./scripts/run-erag-judge.sh r08b > eval-answers/rounds-era2/r08b-judge.log 2>&1
   ```
4. 从 results json 提取四指标，与 r07d 对比，回填 checklist。

**验证：** results-erag-rounds-era2-r08b.json 生成且 64 题判分完整；对比表落入 checklist 文档。

## 执行顺序

```
T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9（长时评测，后台跑）
```
