package com.agenticrag.rag.ingest;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.dto.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定长度分块器：按 chunkSize 切块，块间保留 overlap 重叠（默认 500 字 / overlap 50，读 RagProperties）
 */
@Component
public class FixedChunker implements Chunker {

    private final RagProperties ragProperties;

    public FixedChunker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public List<Chunk> split(String text, String docName){
        if (text == null || text.isBlank()){
            return List.of();
        }

        int chunkSize = ragProperties.getChunkSize();
        int overlap = ragProperties.getChunkOverlap();
                if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 必须小于 chunkSize");
        }

        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int seq = 1;
        int length = text.length();

        while (start < length){
            int end = Math.min(start + chunkSize, length);
            String content = text.substring(start, end).trim();
            if (!content.isEmpty()){
                chunks.add(new Chunk(null, null, seq++, content));
            }
            if (end >= length){
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }


}
