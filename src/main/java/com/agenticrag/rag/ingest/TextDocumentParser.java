package com.agenticrag.rag.ingest;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 纯文本/Markdown 解析器
 * <p>
 * 实现要点：直接读流为 UTF-8 文本；supports 匹配 .txt / .md
 */
@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md");
    }

    @Override
    public String parse(InputStream in) throws Exception {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}