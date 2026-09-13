# M9 · 记忆持久化与调用策略 Tasks

> 前置：spec.md、plan.md 均已批准
> 测试约定：单测按项目既有风格（纯构造注入、不起 Spring 上下文）；LLM 依赖注入 fake；Qdrant/PG 依赖 mock 或标记手动验证

## 文件清单

| 操作 | 文件 | 职责 |
|---|---|---|
| 修改 | `resources/db/init.sql` | +4 张记忆表（conversation_messages / conversation_summaries / user_profiles / long_term_memories） |
| 新建 | `memory/MemoryProperties.java` | rag.memory.* 配置（type/keepTurns/summary/extract/上限/llm） |
| 新建 | `memory/MemoryLlmClient.java` | 摘要+提取共用小模型客户端（空配置回退主 LlmClient） |
| 修改 | `memory/ConversationMemory.java` | 三方法签名 +userId 首参 |
| 修改 | `memory/InMemoryConversationMemory.java` | key 改为 userId+sessionId 复合 |
| 修改 | `memory/RedisConversationMemory.java` | key = chat:memory:{userId}:{sessionId} |
| 新建 | `memory/JdbcConversationMemory.java` | PG 实现（F1） |
| 修改 | `memory/MemoryConfig.java` | 三态装配（memory/redis/jdbc）+ 新组件导入 |
| 新建 | `memory/summary/ConversationSummaryRepository.java` | 摘要表读写 |
| 新建 | `memory/summary/SummaryService.java` | 滑动摘要（F2） |
| 新建 | `memory/entity/UserProfileRepository.java` | user_profiles 读写 |
| 新建 | `memory/entity/EntityMemoryService.java` | 画像加载渲染 + 字段级合并（F3） |
| 新建 | `memory/longterm/LongTermMemoryRepository.java` | long_term_memories 读写 |
| 新建 | `memory/longterm/MemoryQdrantStore.java` | memories 集合封装（upsert/search/delete） |
| 新建 | `memory/longterm/LongTermMemoryService.java` | save/search/淘汰/listAll/clearAll（F4） |
| 新建 | `memory/MemoryContextAssembler.java` | 三路并行读编排（F5） |
| 新建 | `memory/MemoryScope.java` | ThreadLocal userId 作用域（F6） |
| 新建 | `tool/tools/SearchMemoryTool.java` | 记忆检索工具（F6） |
| 新建 | `memory/MemoryExtractionService.java` | 周期批量判断+提取+写回（F7） |
| 修改 | `api/ChatController.java` | userId 参数 + 读编排接入 + 异步写回挂钩 + Scope 绑定 |
| 新建 | `api/MemoryAdminController.java` | 记忆管理四端点（F8） |
| 修改 | `docs/plan/README.md` | 里程碑索引登记 M9 |

## T1: 数据库迁移

**文件**：`resources/db/init.sql`
**依赖**：无
**步骤**：
1. 追加 plan.md「数据库表」一节的 4 张建表语句与索引（全部 `IF NOT EXISTS`，幂等）
2. 确认 `user_id` 均为 `BIGINT NOT NULL DEFAULT 0`（缺省系统用户）

**验证**：对开发库执行 init.sql 无报错；重复执行一次仍无报错（幂等）；`\dt` 可见 4 张新表

## T2: 配置与小模型客户端

**文件**：`memory/MemoryProperties.java`、`memory/MemoryLlmClient.java`
**依赖**：无
**步骤**：
1. MemoryProperties：前缀 `rag.memory`，字段按 plan 配置节（type/keepTurns/summary.enabled/summary.maxChars/extract.intervalTurns/profileMaxEntries/ltmMaxPerUser/ltmSearchTopk/llm.*）
2. MemoryLlmClient：构造注入 MemoryProperties 与主 LlmClient；`chat(messages)` 方法——llm 配置为空则委托主 LlmClient，否则用独立配置发起同步调用（复用 LlmClient 的请求构造逻辑，超时用 llm.timeoutSeconds）

**验证**：`./mvnw compile` 通过；MemoryProperties 绑定默认值单测（type=jdbc、keepTurns=8、intervalTurns=5）

## T3: ConversationMemory 签名扩展

**文件**：`ConversationMemory.java`、`InMemoryConversationMemory.java`、`RedisConversationMemory.java`，及全部调用点（`ChatController` 等编译报错处临时传 0）
**依赖**：无
**步骤**：
1. 接口三方法加 `long userId` 首参
2. InMemory：内部 key 由 `sessionId` 改为 `userId + ":" + sessionId`
3. Redis：key 改为 `chat:memory:{userId}:{sessionId}`
4. 调用点临时硬编码 `0`（T11 统一接入真实参数）

**验证**：`./mvnw compile` 通过；InMemoryConversationMemory 单测改 key 后仍绿（多 userId 同 sessionId 互不串扰用例）

## T4: JdbcConversationMemory + 三态装配

**文件**：`memory/JdbcConversationMemory.java`、`memory/MemoryConfig.java`
**依赖**：T1、T2
**步骤**：
1. JdbcConversationMemory：JdbcTemplate 实现——`load` 按 (user_id, session_id) 倒序取 maxMessages 再反转正序；`append` 单条 insert；`clear` delete by key
2. MemoryConfig：按 `rag.memory.type` 三态条件装配（现有 memory/redis 模式不变，新增 jdbc）

**验证**：单测（mock JdbcTemplate 验证 SQL 语义与参数顺序）；compose 起真 PG 后手动 curl 一轮对话，重启再问能引用前文（AC1 手动预演）

## T5: 滑动摘要（F2）

**文件**：`memory/summary/ConversationSummaryRepository.java`、`memory/summary/SummaryService.java`
**依赖**：T2、T4
**步骤**：
1. Repository：find(upsert 目标行)/save（ON CONFLICT (user_id, session_id) DO UPDATE）、按 id 区间查消息的 SQL（读 conversation_messages）
2. SummaryService.compressIfNeeded：inflight 标记（ConcurrentHashMap）→ 计算窗口最早消息 id 与已有摘要 last_message_id → 半窗口重叠判定（仅当「上次摘要位置 < 窗口一半位置」才触发）→ 取区间消息 + 既有摘要 → MemoryLlmClient 增量合并（≤maxChars）→ upsert（更新 last_message_id）
3. loadWithHistory：摘要包装为 system 消息（「[历史摘要] …」前缀）+ 窗口内原文

**验证**：单测（fake LlmClient）——①未超窗口不触发；②触发时区间与 prompt 含既有摘要；③inflight 期间二次调用直接返回；④loadWithHistory 摘要在最前且顺序正确

## T6: 实体记忆（F3）

**文件**：`memory/entity/UserProfileRepository.java`、`memory/entity/EntityMemoryService.java`
**依赖**：T2
**步骤**：
1. Repository：load(userId) → Optional<String profileJson>；save(userId, json) upsert
2. EntityMemoryService.load：解析 JSON → 渲染为 prompt 片段（「## 用户偏好\n- 语言偏好：英文」格式，空返回空串）
3. applyUpdates：合并更新（同 key 覆盖并刷新该条目时间戳；超 profileMaxEntries 淘汰最旧）；解析异常整体跳过（N1）

**验证**：单测——新增/覆盖/超上限淘汰/空 profile 渲染空串/非法 JSON 跳过

## T7: 长期记忆（F4）

**文件**：`memory/longterm/LongTermMemoryRepository.java`、`memory/longterm/MemoryQdrantStore.java`、`memory/longterm/LongTermMemoryService.java`
**依赖**：T1、T2
**步骤**：
1. Repository：insert（返回自增 id）、listByUser（created_at 倒序）、countByUser、deleteById、deleteByUser
2. MemoryQdrantStore：ensureCollection（复用 rag.embeddingDim、HNSW 参数与 chunk 集合同配置）、upsert(pointId, vector, payload{ownerId,content,createdAt})、search(vector, ownerId filter, topK)、delete(pointId)
3. LongTermMemoryService.save：EmbeddingClient.embed(content) → Repository.insert + Store.upsert → 超过 ltmMaxPerUser 则删除最旧（Repository + Store 同步删）
4. search：embed(query) → Store.search(ownerId=userId) → 返回 content 列表
5. listAll / clearAll：委托 Repository（clearAll 同步清 Store 按 ownerId——若无按 filter 删除能力则全量 point 遍历删除，实现时确认 SDK API）

**验证**：单测（mock Store/EmbeddingClient）——save 写两侧+超限淘汰最旧；search 带 ownerId filter；clearAll 清空；真 Qdrant 下手动冒烟（upsert→search→delete）

## T8: 读编排（F5）

**文件**：`memory/MemoryContextAssembler.java`
**依赖**：T4、T5、T6、T7
**步骤**：
1. assemble(userId, sessionId, query)：CompletableFuture 三支路并行（复用 M2 线程池 bean）——EntityMemoryService.load / LongTermMemoryService.search(query, topK) / ConversationMemory.load + SummaryService.loadWithHistory
2. 每支路 try-catch + orTimeout（llm.timeoutSeconds 或固定 3s），异常/超时返回空值
3. 合并为 MemoryContext record

**验证**：单测——三路正常返回完整；一支路抛异常/超时时其余两路正常且该路为空（AC7 单测化）；线程池为 null 时退化为串行执行

## T9: 记忆检索工具（F6）

**文件**：`memory/MemoryScope.java`、`tool/tools/SearchMemoryTool.java`
**依赖**：T7
**步骤**：
1. MemoryScope：ThreadLocal<Long>，bind/current/clear 三静态方法
2. SearchMemoryTool：name=`search_memory`，description 说明「检索当前用户跨会话的长期记忆」，参数 schema `{query: string}`；execute 取 MemoryScope.current()（null 视为 0）→ LongTermMemoryService.search → 条目拼接文本；空结果返回「无相关记忆」
3. 注册进 ToolRegistry（与 CalculatorTool 同装配方式）

**验证**：单测——scope 绑定后工具检索命中；scope 为空不抛异常；schema 可被 ToolSchemaValidator 校验通过

## T10: 周期批量写回（F7）

**文件**：`memory/MemoryExtractionService.java`
**依赖**：T4、T6、T7
**步骤**：
1. extractIfNeeded(userId, sessionId)：读取会话 user 消息计数（ConversationMemory 或 Repository count），`count % intervalTurns != 0` 直接返回；inflight 标记防重入
2. 触发时异步：取最近 intervalTurns 轮消息 + 当前 profile → 构造单次 MemoryLlmClient 调用（system 提取指令 + JSON 输出契约：`{"profileUpdates":[{key,value}],"newMemories":[{content}]}`）
3. 解析 JSON（Jackson），失败静默跳过；profileUpdates → EntityMemoryService.applyUpdates；newMemories → LongTermMemoryService.save（sourceSessionId 记录）

**验证**：单测（fake LlmClient）——①计数不整除不调用 LLM；②整除时恰好 1 次调用（AC6 单测化）；③返回空数组不写回；④非法 JSON 不抛异常不写回；⑤inflight 期间重复触发被跳过

## T11: ChatController 集成

**文件**：`api/ChatController.java`
**依赖**：T8、T9、T10
**步骤**：
1. 请求参数加 `userId`（可选，缺省 0）
2. 主链路：MemoryScope.bind(userId) → MemoryContextAssembler.assemble 替代直接 memory.load → system prompt 追加 profileSection 与 relatedMemories（空则不加）→ 既有模式路由与 RAG/Agent/Chat 链路不变 → finally MemoryScope.clear()
3. 回复完成后：memory.append(user/assistant) → 异步触发 SummaryService.compressIfNeeded + MemoryExtractionService.extractIfNeeded（不阻塞 SSE done）
4. T3 的临时硬编码 userId 全部替换为真实参数

**验证**：集成冒烟——jdbc 模式多轮对话；重启后同会话延续（AC1）；30+ 轮后调试日志显示消息数受控且首条为摘要（AC2）；日志统计 10 轮内提取调用 ≤2（AC6）

## T12: 管理接口（F8）

**文件**：`api/MemoryAdminController.java`
**依赖**：T6、T7
**步骤**：
1. 四端点：GET/DELETE `/api/memory/profile/{userId}`、GET/DELETE `/api/memory/longterm/{userId}`
2. DELETE 后相关检索不再命中

**验证**：curl 四端点往返——写入记忆 → GET 可见 → DELETE → GET 空 + 对话检索不命中（AC8）

## T13: 收口回归与文档

**文件**：`docs/plan/README.md`、`docs/plan/M9-记忆方案/*`
**依赖**：T1-T12
**步骤**：
1. plan/README 里程碑索引登记 M9（含 M8/M9 衔接说明链接）
2. 全量 `./mvnw test`；对 M8 方案的 T4/T5 条目补注「记忆部分已由 M9 完成」

**验证**：全量测试绿（AC9）；README 索引可达

## 执行顺序

```
T1 ──┬──▶ T4 ──▶ T5 ──▶ T8 ──┐
T2 ──┤         ↘             ├──▶ T11 ──▶ T13
     ├──▶ T6 ────▶ T8        │
     └──▶ T7 ──▶ T9 ──▶ T10 ─┘
T3（与 T4-T7 并行，先改签名保证编译面收敛）
T12（依赖 T6/T7，可并行于 T8-T11）
```

> 关键路径：T1/T2 → T4 → T5 → T8 → T11 → T13；T3 建议最先做（签名扩展一次改完所有调用点，避免后续任务反复踩编译错误）
