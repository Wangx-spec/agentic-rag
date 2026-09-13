# M9 · 记忆持久化与调用策略 Plan

> 前置：spec.md 已批准（决策记录见 spec 文末）
> 本文基于当前代码实际结构设计：`memory/`（ConversationMemory 三件套）、`tool/`（Tool 四件套 + ToolRegistry）、`rag/index/`（EmbeddingClient/QdrantStore）、`agent/`（AgentLoop/AgentContext）、`api/ChatController`

## 架构概览

八个组件，分四层职责（与 spec 的三层记忆架构对应）：

```
┌─ 读编排层 ─────────────────────────────────────────────┐
│ MemoryContextAssembler（F5）                            │
│   并行拉取三路记忆，组装 MemoryContext，支路降级         │
├─ 记忆层 ───────────────────────────────────────────────┤
│ 短期：ConversationMemory（接口扩展 userId）             │
│   ├─ InMemory / Redis（既有，改签名）                   │
│   └─ JdbcConversationMemory（F1 新增）                  │
│ 摘要：SummaryService（F2 滑动窗口摘要）                 │
│ 实体：EntityMemoryService（F3 用户画像）                │
│ 长期：LongTermMemoryService（F4 向量+元数据）           │
├─ 使用层 ───────────────────────────────────────────────┤
│ SearchMemoryTool（F6，注册进 ToolRegistry）             │
├─ 写回与管理层 ─────────────────────────────────────────┤
│ MemoryExtractionService（F7 周期批量判断+提取）         │
│ MemoryAdminController（F8 管理 API）                    │
└────────────────────────────────────────────────────────┘
```

## 核心数据结构

### 数据库表（init.sql 增量迁移）

```sql
-- 短期记忆持久化（F1）
CREATE TABLE IF NOT EXISTS conversation_messages (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL DEFAULT 0,
    session_id  VARCHAR(64) NOT NULL,
    role        VARCHAR(16) NOT NULL,          -- user | assistant
    content     TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_conv_msgs ON conversation_messages(user_id, session_id, id);

-- 会话摘要（F2）：每 (user, session) 一行，滚动更新
CREATE TABLE IF NOT EXISTS conversation_summaries (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL DEFAULT 0,
    session_id      VARCHAR(64) NOT NULL,
    content         TEXT NOT NULL,
    last_message_id BIGINT NOT NULL,           -- 摘要已覆盖到的消息位置
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, session_id)
);

-- 实体记忆（F3）：每用户一行，JSONB 字段级合并
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id    BIGINT PRIMARY KEY,
    profile    JSONB NOT NULL DEFAULT '{}',    -- {"语言偏好":"英文","风格":"简洁",...}
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 长期记忆元数据（F4）：向量存 Qdrant 独立集合
CREATE TABLE IF NOT EXISTS long_term_memories (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL DEFAULT 0,
    content           TEXT NOT NULL,           -- 有价值结论的摘要条目
    source_session_id VARCHAR(64),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ltm ON long_term_memories(user_id, created_at DESC);
```

> Qdrant 新集合 `agentic_rag_memories`：point id = PG `long_term_memories.id`，payload `{ownerId, content, createdAt}`，维度复用 `rag.embeddingDim`（与知识库集合同 embedding 模型）。

### 接口与类

```java
// 既有接口扩展：三个方法都加 userId 首参（InMemory/Redis 实现同步改）
public interface ConversationMemory {
    List<ChatMessage> load(long userId, String sessionId, int maxMessages);
    void append(long userId, String sessionId, ChatMessage message);
    void clear(long userId, String sessionId);
}

// 读编排结果（F5）
public record MemoryContext(
        String profileSection,        // 实体记忆拼装的 prompt 片段（可为空）
        List<String> relatedMemories, // 长期记忆检索结果条目
        List<ChatMessage> history     // 摘要(若有) + 窗口内原文
) {}

// 滑动摘要（F2）：append 后异步触发
public class SummaryService {
    void compressIfNeeded(long userId, String sessionId);  // 半窗口重叠判定，inflight 标记防并发
    List<ChatMessage> loadWithHistory(long userId, String sessionId, int keepTurns);
}

// 周期批量写回（F7）：提取的小模型输出契约
// { "profileUpdates": [{"key":"语言偏好","value":"英文"}],
//   "newMemories":   [{"content":"项目截止日期是10月15日"}] }

// 工具执行期的用户作用域（F6）
public final class MemoryScope {                 // ThreadLocal<Long>，入口绑定/finally 清理
    static void bind(long userId); static Long current(); static void clear();
}
```

### 配置（MemoryProperties，前缀 `rag.memory`）

```yaml
rag.memory:
  type: jdbc                      # memory | redis | jdbc（三态，默认改 jdbc）
  keep-turns: 8                   # 原文保留窗口（轮）
  summary:
    enabled: true
    max-chars: 300
    half-window-overlap: true     # 半窗口重叠（见技术决策 3）
  extract:
    interval-turns: 5             # 每 N 轮触发一次批量提取
  profile-max-entries: 50         # 实体记忆条目上限（N4）
  ltm-max-per-user: 200           # 长期记忆条目上限，超限淘汰最旧（N4）
  ltm-search-topk: 3              # 读阶段长期记忆检索条数
  llm:                            # 摘要+提取共用小模型，空则回退主 LLM（对齐 IntentProperties 模式）
    base-url: ""
    model: ""
    api-key: ""
    timeout-seconds: 15
```

## 模块设计

### JdbcConversationMemory（F1）
**职责**：消息读写 PG，`load` 按 `(user_id, session_id)` 倒序取 `maxMessages` 条再正序返回；`append` 单条 insert；`clear` 按 key delete。
**依赖**：JdbcTemplate。不缓存（N 决策：PG 直读）。

### SummaryService（F2）
**职责**：append assistant 消息后异步检查——当窗口内最早消息 id > 已摘要位置（`last_message_id`）且存在滑出窗口的消息时，取「上次摘要位置 → 窗口一半位置」区间消息，调小模型与既有摘要增量合并（≤maxChars），upsert `conversation_summaries`；`loadWithHistory` 把摘要包装为 system 消息拼在窗口历史前。
**并发防护**：`ConcurrentHashMap<String,Boolean>` inflight 标记（单实例，N5）。
**依赖**：ConversationMemory（Jdbc 模式）、MemoryLlmClient（小模型）、自有 Repository。

### EntityMemoryService（F3）
**职责**：`load(userId)` 读 JSONB profile 渲染为 prompt 片段（「用户偏好：语言=英文…」）；`applyUpdates(userId, List<{key,value}>)` 字段级合并（新值覆盖同 key，超上限淘汰最旧 key）。仅 jdbc 模式生效（memory/redis 模式下降级为空实现）。
**依赖**：UserProfileRepository。

### LongTermMemoryService（F4）
**职责**：`search(userId, query, topK)` → embedding(query) → Qdrant filter(ownerId=userId) 检索；`save(userId, content, sourceSessionId)` → embedding + PG insert + Qdrant upsert，超上限淘汰最旧（PG+Qdrant 同步删）；`listAll/clearAll(userId)`（供 F8）。
**依赖**：EmbeddingClient（复用）、MemoryQdrantStore（新集合轻封装，不复用面向 chunk 的 VectorStore 接口）、LongTermMemoryRepository。

### MemoryContextAssembler（F5）
**职责**：`assemble(userId, sessionId, query)` 用 `CompletableFuture`（复用既有线程池模式）并行执行三支路——实体记忆 / 长期记忆检索 / 会话历史+摘要——每支路独立 try-catch + 超时（超时或异常返回空），合并为 `MemoryContext`。
**依赖**：EntityMemoryService、LongTermMemoryService、ConversationMemory、SummaryService。
**集成点**：ChatController 在拼装 prompt 时调用，替代现有直接 `memory.load(...)`。

### SearchMemoryTool（F6）
**职责**：实现既有 `Tool` 四件套（name=`search_memory`，参数 `{query: string}`），`execute` 从 `MemoryScope.current()` 取 userId（缺省 0），调 LongTermMemoryService.search 返回条目文本。注册进 ToolRegistry（仅 Agent 模式可见，与 CalculatorTool 同模式）。
**依赖**：LongTermMemoryService、MemoryScope。

### MemoryExtractionService（F7）
**职责**：ChatController 在 assistant 回复完成后判断「本会话 user 消息数 % intervalTurns == 0」则异步触发：取最近 `intervalTurns` 轮消息 + 当前 profile，**单次**小模型调用完成「判断+提取」（输出结构化 JSON 契约，解析失败/无新增即跳过，N4）；`profileUpdates` → EntityMemoryService.applyUpdates，`newMemories` → LongTermMemoryService.save。inflight 标记防重入（N5）。
**依赖**：ConversationMemory、EntityMemoryService、LongTermMemoryService、MemoryLlmClient。

### MemoryAdminController（F8）
**职责**：`GET /api/memory/profile/{userId}`、`DELETE /api/memory/profile/{userId}`、`GET /api/memory/longterm/{userId}`、`DELETE /api/memory/longterm/{userId}`。无鉴权（M8 前无用户体系，M8 落地后纳入拦截器+归属校验）。

## 模块交互（读→用→写全链路）

```
请求 (query, sessionId, userId?)            userId 缺省 0
  │
  ▼ ChatController
  ├─ MemoryScope.bind(userId)               【用层准备】
  ├─ MemoryContextAssembler.assemble()      【读】三路并行，降级安全
  │    → system prompt = 既有指令 + profileSection + relatedMemories
  │    → messages = history + 本轮 user query
  ├─ 模式路由（M4 意图识别）→ RAG / Agent / Chat 链路   【用】
  │    └─ Agent 模式：LLM 可自主调 search_memory（MemoryScope 取 userId）
  ├─ memory.append(userId, sessionId, user / assistant 消息)   → PG
  └─ 异步（不阻塞 SSE 响应）：
       ├─ SummaryService.compressIfNeeded()            【写】摘要
       └─ MemoryExtractionService.extractIfNeeded()    【写】偏好/结论
  finally: MemoryScope.clear()
```

## 文件组织

```
src/main/java/com/agenticrag/
├── memory/
│   ├── ConversationMemory.java            改：签名 +userId
│   ├── InMemoryConversationMemory.java    改：key 含 userId
│   ├── RedisConversationMemory.java       改：key = chat:memory:{userId}:{sessionId}
│   ├── JdbcConversationMemory.java        新（F1）
│   ├── MemoryConfig.java                  改：三态装配 + 新组件导入
│   ├── MemoryProperties.java              新（rag.memory.* 全量配置）
│   ├── MemoryLlmClient.java               新：小模型调用（配置空则回退主 LlmClient）
│   ├── MemoryContextAssembler.java        新（F5）
│   ├── MemoryExtractionService.java       新（F7）
│   ├── MemoryScope.java                   新（F6 用户作用域）
│   ├── summary/
│   │   ├── ConversationSummaryRepository.java  新
│   │   └── SummaryService.java            新（F2）
│   ├── entity/
│   │   ├── UserProfileRepository.java     新
│   │   └── EntityMemoryService.java       新（F3）
│   └── longterm/
│       ├── LongTermMemoryRepository.java  新（PG 元数据）
│       ├── MemoryQdrantStore.java         新（memories 集合封装）
│       └── LongTermMemoryService.java     新（F4）
├── tool/tools/
│   └── SearchMemoryTool.java              新（F6）
├── api/
│   ├── ChatController.java                改：userId 参数 + 读编排接入 + 异步写回挂钩
│   └── MemoryAdminController.java         新（F8）
└── resources/db/init.sql                  改：+4 张表
src/test/java/...                          对应单测（详见 task.md）
```

## 技术决策

| # | 决策点 | 选择 | 理由 |
|---|---|---|---|
| 1 | userId 类型 | 数值型 long，缺省 0（系统用户） | 与 M8 `users.id BIGINT` 同型；M8 落地时 UserContext 直接替换缺省值，零迁移（spec N3 已定） |
| 2 | userId 传递方式 | **扩展 ConversationMemory 签名**（三方法 +userId 首参），而非新建接口 | 三实现统一收口；Redis key 格式顺势改为 M8 约定的 `{userId}:{sessionId}`——**提前完成 M8 T5 的记忆隔离部分**；改动集中可控 |
| 3 | 摘要触发判定 | 半窗口重叠：摘要只覆盖到保留窗口的一半位置，重叠段完全滑出才再次摘要 | 防「每 append 一次就调一次 LLM」；参考 ragent 思路自研实现（单实例 inflight 标记替代其分布式锁） |
| 4 | 提取的判断与生成 | 单次小模型调用合并输出（判断+提取一个 JSON 契约） | 比两次调用（先判断再提取）省一半成本；解析失败静默跳过本轮（N1 降级优先） |
| 5 | 长期记忆去重 | 不做语义去重，仅条数上限 + 时间最新优先 | 语义去重需额外 LLM 判重，成本与收益不匹配（YAGNI）；M 条上限已控膨胀（N4） |
| 6 | 工具取 userId | ThreadLocal（MemoryScope），请求入口绑定/finally 清理 | 不侵入 Tool 接口签名；**已知边界：M5 并行 SubAgent 若跨线程执行需显式传递 scope**，届时再扩展（在 M5 方案标注） |
| 7 | 记忆向量集合 | 独立集合 `agentic_rag_memories`，不复用 chunk 集合与 VectorStore 接口 | 知识库检索与记忆检索的 payload/filter/淘汰策略完全不同；泛化 chunk 接口反而扭曲语义 |
| 8 | 摘要/提取共用小模型 | 一个 `rag.memory.llm.*` 配置（空回退主 LLM） | 两者都是低优先级后台任务，可共担一个便宜模型；对齐 M4 IntentProperties 回退模式 |
| 9 | 既有 memory/redis 模式下的实体/长期记忆 | 降级为空实现（NoOp），仅 jdbc 模式全功能 | 保持三态配置的可组合性；单测与零依赖演示仍可用 memory 模式 |
| 10 | 异步执行载体 | 复用 M2 的线程池 + inflight 标记，不引入 MQ | M10 才引入 RocketMQ 且仅任务链路；记忆写回的量级（每会话每 5 轮一次）远不需要 MQ |

## spec 覆盖对照

| spec 需求 | 归属组件 | 状态 |
|---|---|---|
| F1 短期记忆持久化 | JdbcConversationMemory + 表迁移 | ✅ |
| F2 窗口+摘要 | SummaryService + ConversationMemory.load 窗口参数 | ✅ |
| F3 实体记忆 | EntityMemoryService + user_profiles | ✅ |
| F4 长期记忆 | LongTermMemoryService + long_term_memories + Qdrant 集合 | ✅ |
| F5 读编排 | MemoryContextAssembler | ✅ |
| F6 检索工具 | SearchMemoryTool + MemoryScope | ✅ |
| F7 周期批量写回 | MemoryExtractionService | ✅ |
| F8 管理 API | MemoryAdminController | ✅ |
| N1 降级 / N2 小模型 / N3 userId / N4 上限 / N5 并发 | 各组件设计内嵌（决策 3/4/5/8/10） | ✅ |
