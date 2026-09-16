package com.agenticrag.eval;

import com.agenticrag.rag.retrieve.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class RecallEval {

    public Result evaluate(List<RetrievedChunk> retrieved, List<Long> expectedChunkIds) {
        if (expectedChunkIds == null || expectedChunkIds.isEmpty()) {
            return new Result(1.0, 0, 0);
        }
        Set<Long> expected = new HashSet<>(expectedChunkIds);
        int hits = 0;
        for (RetrievedChunk chunk : retrieved == null ? List.<RetrievedChunk>of() : retrieved) {
            if (chunk.chunkId() != null && expected.contains(chunk.chunkId())) {
                hits++;
            }
        }
        double recall = expected.isEmpty() ? 1.0 : (double) hits / expected.size();
        return new Result(recall, hits, expected.size());
    }

    public record Result(double recallAt10, int matchedCount, int expectedCount) {
    }
}
