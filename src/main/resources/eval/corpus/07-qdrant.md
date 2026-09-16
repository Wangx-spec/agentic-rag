# Qdrant 配置

默认向量库使用 Qdrant。配置中 qdrant 端口为 6334，HNSW 的 hnswM 默认为 16，efConstruct 和 searchEf 默认都是 100。collection 名会按 embedding 模型和维度拼出前缀，避免不同模型混用同一集合。
