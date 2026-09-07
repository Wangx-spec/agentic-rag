package com.agenticrag.rag.ingest;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DocumentParserSelector {

    private final List<DocumentParser> parsers;

    public DocumentParserSelector(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public DocumentParser select(String filename) {
        return parsers.stream()
                .filter(parser -> parser.supports(filename))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的文件类型: " + filename));
    }
}