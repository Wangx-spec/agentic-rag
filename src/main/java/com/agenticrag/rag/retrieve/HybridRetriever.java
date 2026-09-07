package com.agenticrag.rag.retrieve;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.index.Bm25Store;
import com.agenticrag.rag.index.EmbeddingClient;
import com.agenticrag.rag.index.VectorSearchResult;
import com.agenticrag.rag.index.VectorStore;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
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
@Component
public class HybridRetriever {

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final Bm25Store bm25Store;
    private final RagProperties ragProperties;

    public HybridRetriever(EmbeddingClient embeddingClient,
                           VectorStore vectorStore,
                           Bm25Store bm25Store,
                           RagProperties ragProperties) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.bm25Store = bm25Store;
        this.ragProperties = ragProperties;
    }

    public List<RetrievedChunk> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        float[] queryVector = embeddingClient.embed(query);
        List<VectorSearchResult> vectorResults = vectorStore.searchByVector(queryVector, ragProperties.getTopK());
        List<VectorSearchResult> bm25Results = bm25Store.search(query, ragProperties.getTopK());

        Map<Long, MergeBucket> merged = new HashMap<>();
        merge(vectorResults, merged);
        merge(bm25Results, merged);

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(MergeBucket::rrfScore).reversed())
                .limit(ragProperties.getFinalTopN())
                .map(bucket -> new RetrievedChunk(
                        bucket.chunkId(),
                        bucket.documentId(),
                        bucket.seq(),
                        bucket.content(),
                        bucket.docName(),
                        bucket.rrfScore(),
                        bucket.rank()
                ))
                .toList();
    }

    private void merge(List<VectorSearchResult> results, Map<Long, MergeBucket> merged) {
        for (int i = 0; i < results.size(); i++) {

            VectorSearchResult result = results.get(i);
            double score = 1.0 / (ragProperties.getRrfK() + i + 1);
            final int rank = i + 1;
            merged.compute(result.chunkId(), (key, bucket) -> {
                if (bucket == null) {
                    return new MergeBucket(
                            result.chunkId(),
                            result.documentId(),
                            result.seq(),
                            result.content(),
                            result.docName(),
                            score,
                            rank
                    );
                }
                return bucket.addScore(score, Math.min(bucket.rank(), rank));
            });
        }
    }

    private record MergeBucket(
            Long chunkId,
            Long documentId,
            int seq,
            String content,
            String docName,
            double rrfScore,
            int rank
    ) {
        private MergeBucket addScore(double delta, int newRank) {
            return new MergeBucket(chunkId, documentId, seq, content, docName, rrfScore + delta, newRank);
        }
    }
}

