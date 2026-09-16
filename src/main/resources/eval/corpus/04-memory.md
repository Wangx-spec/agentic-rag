# 会话记忆

会话记忆支持内存版和 Redis 版两种实现。RedisConversationMemory 会把消息按 sessionId 持久化，并设置 7 天 TTL。装配方式通过 rag.memory.type 控制，默认使用 memory，实现切换对上层调用透明。
