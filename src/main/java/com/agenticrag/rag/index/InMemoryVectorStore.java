package com.agenticrag.rag.index;

import com.agenticrag.rag.dto.Chunk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储实现（余弦相似度暴力检索）
 * <p>
 * 用途：零依赖单测与演示兜底；按 rag.vector.type=memory 时装配。
 * 数据量小场景性能足够，重启即失（不入库持久化）。
 */
@ConditionalOnProperty(prefix = "rag.vector", name = "type", havingValue = "memory")
@Component
public class InMemoryVectorStore implements VectorStore {

    private final Map<Long, StoredVector> store = new ConcurrentHashMap<>();

    @Override
    public void saveChunks(long documentId, String docName, List<Chunk> chunks, List<float[]> vectors) {
        if (chunks == null || vectors == null){
            return;
        }
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("chunks 与 vectors 数量不一致");
        }
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            float[] vector = vectors.get(i);
            if (chunk.id() == null) {
                throw new IllegalArgumentException("chunk.id 不能为空");
            }
            store.put(chunk.id(), new StoredVector(
                    chunk.id(),
                    documentId,
                    chunk.seq(),
                    chunk.content(),
                    docName,
                    vector
            ));
        }
    
    }

    @Override
    public List<VectorSearchResult> searchByVector(float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }

        List<VectorSearchResult> results = new ArrayList<>();
        for (StoredVector stored : store.values()) {
            double score = cosineSimilarity(queryVector, stored.vector());
            results.add(new VectorSearchResult(
                    stored.chunkId(),
                    stored.documentId(),
                    stored.seq(),
                    stored.content(),
                    stored.docName(),
                    score
            ));
        }

        return results.stream()
            .sorted(Comparator.comparingDouble(VectorSearchResult::score).reversed())
            .limit(topK)
            .toList();

    }

    @Override
    public void deleteByDocumentId(long documentId) {
        store.values().removeIf(stored -> stored.documentId() != null && stored.documentId() == documentId);
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0) {
            return 0.0;
        }
        if (left.length != right.length) {
            throw new IllegalArgumentException("向量维度不一致");
        }

        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

        private record StoredVector(
            Long chunkId,
            Long documentId,
            int seq,
            String content,
            String docName,
            float[] vector
    ) {
    }
}
