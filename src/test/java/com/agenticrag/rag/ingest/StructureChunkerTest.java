package com.agenticrag.rag.ingest;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.dto.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureChunkerTest {

    @Test
    void splitIntoBlocksKeepsHeadingWithFollowingParagraphs() {
        String text = """
                # 第一章
                这是第一章第一段。

                这是第一章第二段。

                ## 第二章
                这是第二章内容。
                """;

        List<String> blocks = StructureChunker.splitIntoBlocks(text);

        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0).startsWith("# 第一章"));
        assertTrue(blocks.get(0).contains("这是第一章第二段。"));
        assertTrue(blocks.get(1).startsWith("## 第二章"));
    }

    @Test
    void splitFallsBackToFixedChunkerForOversizedSection() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.setChunkSize(24);
        ragProperties.setChunkOverlap(4);

        StructureChunker chunker = new StructureChunker(ragProperties, new FixedChunker(ragProperties));
        String text = """
                # 标题
                这是一段很长很长的正文内容，用来触发结构化分块器回退到固定切块逻辑。
                """;

        List<Chunk> chunks = chunker.split(text, "demo.md");

        assertTrue(chunks.size() > 1);
        assertEquals(1, chunks.get(0).seq());
        assertTrue(chunks.get(0).content().startsWith("# 标题"));
    }
}
