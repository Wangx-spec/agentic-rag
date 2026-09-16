# 意图路由

auto 模式支持六类意图：CHAT、KB_QA、MULTI_TASK、TOOL_TASK、OFF_TOPIC、UNKNOWN。只有当问题明确包含多个独立子问题时，才会判定为 MULTI_TASK；如果置信度过低，则回退到 UNKNOWN，再走隐式路由兜底。
