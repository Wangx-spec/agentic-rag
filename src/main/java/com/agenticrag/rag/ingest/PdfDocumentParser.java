package com.agenticrag.rag.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * PDF 文档解析器（PDFBox 3.x 实现）
 * <p>
 * 实现要点：PDFBox 逐页抽取文本，处理分页截断（页间补空白/换行）；supports 匹配 .pdf
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    @Override
    public String parse(InputStream in) throws Exception {
        try (PDDocument document = Loader.loadPDF(in.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }
}
