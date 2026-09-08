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
    public List<Chunk> split(String text, String docName) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = text.replace("\r\n", "\n");
        List<String> blocks = splitIntoBlocks(normalized);
        List<Chunk> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        int seq = 1;

        for (String rawBlock : blocks) {
            String block = rawBlock.trim();
            if (block.isEmpty()) {
                continue;
            }

            if (current.isEmpty()) {
                current.append(block);
                continue;
            }

            int combinedLength = current.length() + block.length() + 2;
            if (combinedLength <= ragProperties.getChunkSize()) {
                current.append("\n\n").append(block);
            } else {
                seq = flush(current.toString(), docName, result, seq, pending);
                current = new StringBuilder(block);
            }
        }

        if (!current.isEmpty()) {
            seq = flush(current.toString(), docName, result, seq, pending);
        }

        if (!pending.isEmpty()){
            flush(pending.toString(), docName, result, seq);
        }

        return result;
    }

    private int flush(String content, String docName, List<Chunk> result, int seq) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return seq;
        }

        if (trimmed.length() <= ragProperties.getChunkSize()) {
            result.add(new Chunk(null, null, seq, trimmed));
            return seq + 1;
        }

        List<Chunk> fallback = fixedChunker.split(trimmed, docName);
        for (Chunk chunk : fallback) {
            result.add(new Chunk(null, null, seq++, chunk.content()));
        }
        return seq;
    }

    private int flush(String block, String docName, List<Chunk> result, int seq, StringBuilder pending) {
        String content = block.trim();
        if (content.isEmpty()) {
            return seq;
        }

        int minChunkSize = ragProperties.getMinChunkSize();
        if (content.length() < minChunkSize && !pending.isEmpty()) {
            String combined = pending.toString().trim() + "\n\n" + content;
            if (combined.length() <= ragProperties.getChunkSize()) {
                pending.setLength(0);
                content = combined;
            }
        }

        if (content.length() < minChunkSize && !result.isEmpty()) {
            Chunk lastChunk = result.get(result.size() - 1);
            if (lastChunk.content().length() + content.length() + 2 <= ragProperties.getChunkSize()) {
                String merged = lastChunk.content() + "\n\n" + content;
                result.set(result.size() - 1, new Chunk(lastChunk.id(), lastChunk.documentId(), lastChunk.seq(), merged));
                return seq;
            }
        }

        if (content.length() < minChunkSize) {
            if (pending.isEmpty()) {
                pending.append(content);
            } else {
                pending.append("\n\n").append(content);
            }
            return seq;
        }

        if (!pending.isEmpty()) {
            content = pending.toString().trim() + "\n\n" + content;
            pending.setLength(0);
        }

        return flush(content, docName, result, seq);
    }

    static List<String> splitIntoBlocks(String text) {
        String[] lines = text.split("\n", -1);
        List<String> blocks = new ArrayList<>();
        StringBuilder currentBlock = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushParagraph(paragraph, currentBlock);
                continue;
            }

            if (isHeadingLine(trimmed)) {
                flushParagraph(paragraph, currentBlock);
                flushBlock(currentBlock, blocks);
                currentBlock.append(trimmed);
                continue;
            }

            if (!paragraph.isEmpty()) {
                paragraph.append('\n');
            }
            paragraph.append(trimmed);
        }

        flushParagraph(paragraph, currentBlock);
        flushBlock(currentBlock, blocks);
        return blocks;
    }

    static boolean isHeadingLine(String line) {
        return line.matches("^#{1,6}\\s+.+$")
                || line.matches("^\\d+(\\.\\d+)*[\\.、]?\\s+.+$")
                || line.matches("^[一二三四五六七八九十]+[、.]\\s*.+$")
                || line.matches("^（[一二三四五六七八九十]+）.+$");
    }

    private static void flushParagraph(StringBuilder paragraph, StringBuilder currentBlock) {
        if (paragraph.isEmpty()) {
            return;
        }
        if (!currentBlock.isEmpty()) {
            currentBlock.append("\n\n");
        }
        currentBlock.append(paragraph);
        paragraph.setLength(0);
    }

    private static void flushBlock(StringBuilder currentBlock, List<String> blocks) {
        String content = currentBlock.toString().trim();
        if (!content.isEmpty()) {
            blocks.add(content);
        }
        currentBlock.setLength(0);
    }
}
