package com.agenticrag.rag.ingest;


import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.dto.Chunk;

/**
 * 结构化分块器：按标题/段落边界切块；段落超长时回退 FixedChunker 兜底
 * <p>
 * 实现要点：识别 Markdown 标题（#）/ 空行段落边界，标题跟随其所属段落
 */
@Primary
@Component
public class StructureChunker implements Chunker {

    private final RagProperties ragProperties;
    private final FixedChunker fixedChunker;

    public StructureChunker(RagProperties ragProperties, FixedChunker fixedChunker) {
        this.ragProperties = ragProperties;
        this.fixedChunker = fixedChunker;
    }

    @Override
    public List<Chunk> split(String text, String docName){
        
        if (text == null || text.isBlank()){
            return List.of();
        }

        String normalized = text.replace("\r\n", "\n");
        String[] paragraphs = normalized.split("\\n\\s*\\n");
        List<Chunk> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int seq = 1;

        for (String paragraph : paragraphs) {
            String block = paragraph.trim();
            if (block.isEmpty()) {
                continue;
            }

            if (current.isEmpty()) {
                current.append(block);
                continue;
            }

            if (current.length() + block.length() + 2 <= ragProperties.getChunkSize()) {
                current.append("\n\n").append(block);
            } else {
                seq = flush(current.toString(), docName, result, seq);
                current = new StringBuilder(block);
            }
        }

        if (!current.isEmpty()) {
            flush(current.toString(), docName, result, seq);
        }

        return result;

    }

    private int flush(String block, String docName, List<Chunk> result, int seq) {
        String content = block.trim();
        if (content.isEmpty()) {
            return seq;
        }

        if (content.length() <= ragProperties.getChunkSize()) {
            result.add(new Chunk(null, null, seq, content));
            return seq + 1;
        }

        List<Chunk> fallback = fixedChunker.split(content, docName);
        for (Chunk chunk : fallback) {
            result.add(new Chunk(null, null, seq++, chunk.content()));
        }
        return seq;
    }

}
