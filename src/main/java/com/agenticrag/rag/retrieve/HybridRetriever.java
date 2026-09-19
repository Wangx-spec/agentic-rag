package com.agenticrag.rag.retrieve;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.index.Bm25Store;
import com.agenticrag.rag.index.EmbeddingClient;
import com.agenticrag.rag.index.VectorSearchResult;
import com.agenticrag.rag.index.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器：向量 + BM25 双通道 → RRF 融合 → top N
 * <p>
 * 计划方法：
 * - List&lt;RetrievedChunk&gt; retrieve(String query)   双通道各取 topK=10，RRF 融合（score = Σ 1/(60 + rank)）后取 top 5
 * <p>
 * 组装 context：每段前缀 [1][2]... 编号 + 文档名，供聊天链路注入 prompt
 */
@Slf4j
@Component
public class HybridRetriever {

    private static final String MODE_HYBRID = "hybrid";
    private static final String MODE_BM25 = "bm25";
    private static final String MODE_VECTOR = "vector";
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final Bm25Store bm25Store;
    private final RagProperties ragProperties;
    private final QueryRewriter queryRewriter;
    private final HydeExpander hydeExpander;

    public HybridRetriever(EmbeddingClient embeddingClient,
                           VectorStore vectorStore,
                           Bm25Store bm25Store,
                           RagProperties ragProperties,
                           QueryRewriter queryRewriter,
                           HydeExpander hydeExpander) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.bm25Store = bm25Store;
        this.ragProperties = ragProperties;
        this.queryRewriter = queryRewriter;
        this.hydeExpander = hydeExpander;
    }

    /**
     * 多查询混合检索：Query 改写器返回候选查询集（Rewriter 关闭/短查询时仅含原查询），
     * 每路独立执行向量 + BM25 双通道；HyDE 生成的假设性答案片段额外附加一路纯向量检索。
     * RRF 分数跨查询累加（同一 chunk 被多路命中时排序上升）。合并去重语义见 merge()。
     */
    public List<RetrievedChunk> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String mode = normalizedMode();
        boolean useVector = MODE_HYBRID.equals(mode) || MODE_VECTOR.equals(mode);
        boolean useBm25 = MODE_HYBRID.equals(mode) || MODE_BM25.equals(mode);

        Map<Long, MergeBucket> merged = new HashMap<>();
        for (String candidate : queryRewriter.expand(query)) {
            searchAndMerge(candidate, useVector, useBm25, merged);
        }
        if (useVector) {
            hydeExpander.hypothesize(query).ifPresent(passage ->
                    searchAndMerge(passage, true, false, merged));
        }

        List<MergeBucket> ranked = merged.values().stream()
                .sorted(Comparator.comparingDouble(MergeBucket::rrfScore).reversed())
                .limit(ragProperties.getFinalTopN())
                .toList();

        List<RetrievedChunk> output = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            MergeBucket bucket = ranked.get(i);
            output.add(new RetrievedChunk(
                    bucket.chunkId(),
                    bucket.documentId(),
                    bucket.seq(),
                    bucket.content(),
                    bucket.docName(),
                    bucket.rrfScore(),
                    i + 1
            ));
        }
        return output;
    }


    private String normalizedMode() {
        String configured = ragProperties.getRetrieval() == null
                ? null
                : ragProperties.getRetrieval().getMode();
        if (configured == null || configured.isBlank()) {
            return MODE_HYBRID;
        }
        return switch (configured.strip().toLowerCase()) {
            case MODE_BM25 -> MODE_BM25;
            case MODE_VECTOR -> MODE_VECTOR;
            case MODE_HYBRID -> MODE_HYBRID;
            default -> {
                log.warn("未知 rag.retrieval.mode={}，回落为 hybrid", configured);
                yield MODE_HYBRID;
            }
        };
    }

    private String abbreviateQuery(String query) {
        String compact = query.strip().replaceAll("\\s+", " ");
        return compact.length() <= 50 ? compact : compact.substring(0, 50) + "…";
    }

    /**
     * 单路查询的向量 + BM25 双通道检索：任一通道关闭即跳过（空结果对 merge 无意义贡献）。
     */
    private void searchAndMerge(String candidate, boolean useVector, boolean useBm25,
                                Map<Long, MergeBucket> merged) {
        if (useVector) {
            merge(vectorStore.searchByVector(embeddingClient.embed(candidate),
                    ragProperties.getTopK()), merged);
        }
        if (useBm25) {
            merge(bm25Store.search(candidate, ragProperties.getTopK()), merged);
        }
    }

    private void merge(List<VectorSearchResult> results, Map<Long, MergeBucket> merged) {
        for (int i = 0; i < results.size(); i++) {
            VectorSearchResult result = results.get(i);
            double score = 1.0 / (ragProperties.getRrfK() + i + 1);
            merged.compute(result.chunkId(), (key, bucket) -> {
                if (bucket == null) {
                    return new MergeBucket(
                            result.chunkId(),
                            result.documentId(),
                            result.seq(),
                            result.content(),
                            result.docName(),
                            score
                    );
                }
                return bucket.addScore(score);
            });
        }
    }

    private record MergeBucket(
            Long chunkId,
            Long documentId,
            int seq,
            String content,
            String docName,
            double rrfScore
    ) {
        private MergeBucket addScore(double delta) {
            return new MergeBucket(chunkId, documentId, seq, content, docName, rrfScore + delta);
        }
    }
}
