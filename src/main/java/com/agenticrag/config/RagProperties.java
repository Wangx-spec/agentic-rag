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
    private long maxFileSizeBytes = 50 * 1024 * 1024;
    private String allowedExtensions = ".pdf,.txt,.md";
    private int minChunkSize = 80;
    private Retrieval retrieval = new Retrieval();
    private Vector vector = new Vector();
    private Agent agent = new Agent();

    @Data
    public static class Agent {
        /** Agent 终答引用裁剪上限：累积 sources 超过该数时按 rerank 精排裁剪（fail-open 保留前 N 个） */
        private int citationTopN = 8;
        /** get_document 工具正文截断上限（字符数），超出部分截断并标注总字符数 */
        private int documentMaxChars = 8000;
        /** list_documents 工具默认/最大返回篇数 */
        private int listDocumentsLimit = 20;
        /** get_document/list_documents 注册总开关：false 时两工具不注册（评测冻结配置用，r08b 消融为负后回退 r07d 口径） */
        private boolean documentToolsEnabled = true;
    }

    @Data
    public static class Vector {
        private String type = "qdrant";
        private Qdrant qdrant = new Qdrant();
    }

    @Data
    public static class Retrieval {
        /** 检索通道模式：hybrid | bm25 | vector，通道消融评测用 */
        private String mode = "hybrid";
        /** Phase 3.1：LLM Query 改写开关（改写查询与原查询并行检索） */
        private boolean rewriterEnabled = false;
        /** Phase 3.1：触发改写的最小查询 token 数（短查询不值得改写） */
        private int rewriterMinTokens = 15;
        /** Phase 3.2：HyDE 假设性答案扩展开关（附加一路向量检索） */
        private boolean hydeEnabled = false;
        /** Phase 7：Rerank 精排配置 */
        private Rerank rerank = new Rerank();
    }

    @Data
    public static class Rerank {
        /** Rerank 总开关（默认关闭） */
        private boolean enabled = false;
        /** Rerank 模型名（硅基流动支持的模型，默认 bge-reranker-v2-m3） */
        private String model = "BAAI/bge-reranker-v2-m3";
        /** 送入 rerank 的候选文档数量（RRF 后取 top N 进行精排） */
        private int candidateSize = 20;
        /** 精排后保留数量（null 时回退为 retrieval.finalTopN） */
        private Integer finalTopN;
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
