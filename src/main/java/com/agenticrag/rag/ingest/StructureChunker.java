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
