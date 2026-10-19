package com.example.lettergenerator.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProtectedLetterPdfService {
    private static final float MARGIN = 54;
    private static final float FONT_SIZE = 11;
    private static final float LINE_HEIGHT = 16;
    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    public byte[] create(byte[] personalizedDocx, String customerNumber) throws IOException {
        if (customerNumber == null || customerNumber.isBlank()) {
            throw new IOException("customerNumber is required for PDF encryption");
        }
        try (XWPFDocument word = new XWPFDocument(new ByteArrayInputStream(personalizedDocx));
             PDDocument pdf = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!word.getAllPictures().isEmpty() || !word.getTables().isEmpty()
                || word.getBodyElements().stream().anyMatch(element -> !(element instanceof XWPFParagraph))
                || word.getHeaderList().stream().anyMatch(header -> !header.getText().isBlank())
                || word.getFooterList().stream().anyMatch(footer -> !footer.getText().isBlank())) {
                throw new IOException("PDF conversion supports text-only DOCX letters; tables, images and headers/footers need a full DOCX converter");
            }

            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(pdf, page);
            float y = page.getMediaBox().getHeight() - MARGIN;
            try {
                for (IBodyElement element : word.getBodyElements()) {
                    XWPFParagraph paragraph = (XWPFParagraph) element;
                    PDFont font = paragraph.getRuns().stream().anyMatch(XWPFRun::isBold) ? BOLD : REGULAR;
                    List<String> lines = wrap(paragraph.getText(), font,
                        page.getMediaBox().getWidth() - 2 * MARGIN);
                    for (String line : lines) {
                        if (y < MARGIN + LINE_HEIGHT) {
                            stream.close();
                            page = new PDPage(PDRectangle.A4);
                            pdf.addPage(page);
                            stream = new PDPageContentStream(pdf, page);
                            y = page.getMediaBox().getHeight() - MARGIN;
                        }
                        if (!line.isEmpty()) {
                            stream.beginText();
                            stream.setFont(font, FONT_SIZE);
                            float x = MARGIN;
                            float width = font.getStringWidth(line) / 1000 * FONT_SIZE;
                            if (paragraph.getAlignment().name().equals("CENTER")) x = (page.getMediaBox().getWidth() - width) / 2;
                            if (paragraph.getAlignment().name().equals("RIGHT")) x = page.getMediaBox().getWidth() - MARGIN - width;
                            stream.newLineAtOffset(x, y);
                            stream.showText(line);
                            stream.endText();
                        }
                        y -= LINE_HEIGHT;
                    }
                    y -= LINE_HEIGHT / 2;
                }
            } finally {
                stream.close();
            }

            AccessPermission permissions = new AccessPermission();
            permissions.setCanModify(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                UUID.randomUUID().toString() + UUID.randomUUID(), customerNumber, permissions);
            policy.setEncryptionKeyLength(256);
            pdf.protect(policy);
            pdf.save(output);
            return output.toByteArray();
        } catch (IllegalArgumentException exception) {
            throw new IOException("DOCX contains text that the built-in PDF font cannot render", exception);
        }
    }

    private List<String> wrap(String text, PDFont font, float availableWidth) throws IOException {
        String normalized = text == null ? "" : text.replace('\t', ' ').replace('\r', ' ');
        List<String> lines = new ArrayList<>();
        for (String part : normalized.split("\\n", -1)) {
            if (part.isBlank()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : part.trim().split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000 * FONT_SIZE <= availableWidth) {
                    line.setLength(0);
                    line.append(candidate);
                } else if (line.isEmpty()) {
                    throw new IOException("A word is too wide for the PDF page");
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                    if (font.getStringWidth(word) / 1000 * FONT_SIZE > availableWidth) {
                        throw new IOException("A word is too wide for the PDF page");
                    }
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
