package com.agenticrag.rag.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfDocumentParserTest {

    @Test
    void parseExtractsNonEmptyTextFromPdf() throws Exception {
        PdfDocumentParser parser = new PdfDocumentParser();

        String text = parser.parse(new ByteArrayInputStream(createPdf("RAG retrieval works with citations.")));

        assertTrue(text.contains("RAG retrieval works"));
    }

    private byte[] createPdf(String content) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(0))) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(content);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
