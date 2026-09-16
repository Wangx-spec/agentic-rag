# 前端模式

前端模式选择器支持 auto、plain、rag、agent、multi-agent 五种模式。auto 依赖意图分类自然分流；multi-agent 作为显式兜底入口，便于演示、评测和手动验证复合问题链路。前端通过统一的 mode 字段把模式发送给后端。
