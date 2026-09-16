# 多 Agent 编排

多 Agent 链路采用 LeaderAgent、SubAgentExecutor、Aggregator 三段式流程。Leader 最多拆解 4 个子问题；如果拆解后只有 1 个子问题，或者全部子任务失败，就自动降级回普通 RAG。成功时会先汇总子结论，再输出最终答案。
