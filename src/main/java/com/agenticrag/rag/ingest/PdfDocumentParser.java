package com.agenticrag.rag.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


@Component
public class PdfDocumentParser implements DocumentParser {

    private static final int MIN_LINE_LENGTH = 5;

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    @Override
    public String parse(InputStream in) throws Exception {
        try (PDDocument document = Loader.loadPDF(in.readAllBytes())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount <= 1){
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                return stripper.getText(document);
            }

            List<List<String>> pageLines = new ArrayList<>();
            for (int i = 0; i < pageCount; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(document);
                pageLines.add(extractNonEmptyLines(pageText));
            }

            Set<String> headerFooterLines = findRepeatingLines(pageLines);

            StringBuilder result = new StringBuilder();
            for (List<String> lines : pageLines) {
                for (String line : lines) {
                    if (!headerFooterLines.contains(line)) {
                        result.append(line).append('\n');
                    }
                }
                result.append('\n');
            }
            return result.toString().trim();
        }
    }

    private List<String> extractNonEmptyLines(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.length() >= MIN_LINE_LENGTH) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private Set<String> findRepeatingLines(List<List<String>> pageLines) {
        Set<String> seen = new HashSet<>();
        Set<String> repeating = new HashSet<>();
        for (List<String> lines : pageLines) {
            Set<String> uniqueInPage = new HashSet<>(lines);
            for (String line : uniqueInPage){
                if (!seen.add(line)){
                    repeating.add(line);
                }
            }
        }
        return repeating;
    }
}
