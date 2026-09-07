package com.agenticrag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 配置（M2 填充字段）
 * <p>
 * 计划字段：
 * - chunkSize(500) / chunkOverlap(50)          分块参数
 * - topK(10) / finalTopN(5) / rrfK(60)         检索参数
 * - embeddingDim(4096)                          向量维度（与 embedding 模型一致）
 * - dataDir(./data)                             SQLite/本地数据目录
 * - vector.type(qdrant | memory)                向量存储装配选择
 * - vector.qdrant.host / port(6334) / collection(agentic_rag_chunks)
 * - vector.qdrant.hnswM / efConstruct / searchEf
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private int chunkSize = 500;
    private int chunkOverlap = 50;
    private int topK = 10;
    private int finalTopN = 5;
    private int rrfK = 60;
    private int embeddingDim = 4096;
    private String dataDir = "./data";
    private Vector vector = new Vector();

    @Data
    public static class Vector {
        private String type = "qdrant";
        private Qdrant qdrant = new Qdrant();
    }

    @Data
    public static class Qdrant {
        private String host = "localhost";
        private int port = 6334;
        /** collection 名前缀；实际 collection = {prefix}_{modelSlug}_{dim}，实现按 embedding 模型/维度隔离 */
        private String collectionPrefix = "agentic_rag_chunks";
        /** 兼容旧配置：若显式设置则优先使用该固定名 */
        private String collection;
        /** HNSW 图的邻居数，默认采用方案里的 m=16。 */
        private int hnswM = 16;
        /** 建索引时的 ef_construct，默认采用方案里的 100。 */
        private int efConstruct = 100;
        /** 查询时的 hnsw_ef，默认采用方案里的 100。 */
        private int searchEf = 100;

        public String resolveCollection(String modelSlug, int embeddingDim) {
            if (collection != null && !collection.isBlank()) {
                return collection;
            }
            return collectionPrefix + "_" + modelSlug + "_" + embeddingDim;
        }
    }
}
