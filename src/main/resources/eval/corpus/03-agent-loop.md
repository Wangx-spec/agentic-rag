# Agent 状态机

主 Agent 循环采用四态状态机：THINKING、ACTING、OBSERVING、FINAL。为了避免无限工具循环，系统配置了 maxAgentRounds，默认上限为 5 轮。最后一轮会强制去掉工具列表，让模型直接生成最终回答。
