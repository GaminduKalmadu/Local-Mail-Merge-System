package com.example.lettergenerator.service;

import com.example.lettergenerator.model.ValidationResult;
import com.example.lettergenerator.model.ValidationStatus;
import com.example.lettergenerator.model.EmailStatus;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ReportService {
    private static final String[] HEADERS =
        {"Row", "Employee ID", "Name", "Status", "Letter", "Output file", "Email", "Email status", "Details"};
    private static final float[] COLUMN_WIDTHS = {28, 62, 80, 68, 42, 100, 120, 78, 155};
    private static final float MARGIN = 36;
    private static final float BODY_FONT_SIZE = 8;
    private static final float LINE_HEIGHT = 10;
    private static final float CELL_PADDING = 4;
    private static final float HEADER_HEIGHT = 22;
    private static final PDColor DARK_BLUE = new PDColor(new float[]{0.10f, 0.23f, 0.38f}, PDDeviceRGB.INSTANCE);
    private static final PDColor LIGHT_BLUE = new PDColor(new float[]{0.93f, 0.96f, 0.99f}, PDDeviceRGB.INSTANCE);
    private static final PDColor LIGHT_GRAY = new PDColor(new float[]{0.82f, 0.84f, 0.87f}, PDDeviceRGB.INSTANCE);
    private static final PDColor DARK_GRAY = new PDColor(new float[]{0.22f, 0.24f, 0.27f}, PDDeviceRGB.INSTANCE);
    private static final PDColor WHITE = new PDColor(new float[]{1, 1, 1}, PDDeviceRGB.INSTANCE);

    private final PDFont regularFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    public byte[] pdf(List<ValidationResult> results, String letterType) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDDocumentInformation information = new PDDocumentInformation();
            information.setTitle("Validation report - " + letterType);
            information.setSubject("Letter generation validation results");
            information.setCreator("Letter Forge");
            document.setDocumentInformation(information);

            PageState state = createPage(document, letterType, results);
            for (int index = 0; index < results.size(); index++) {
                ValidationResult result = results.get(index);
                List<List<String>> cellLines = rowLines(result);
                float rowHeight = Math.max(20, maxLineCount(cellLines) * LINE_HEIGHT + (CELL_PADDING * 2));
                if (state.y() - rowHeight < MARGIN + 20) {
                    state.stream().close();
                    state = createContinuationPage(document, letterType);
                }
                drawRow(state.stream(), state.y(), rowHeight, cellLines, index % 2 == 1);
                state = new PageState(state.stream(), state.y() - rowHeight);
            }
            state.stream().close();
            addPageNumbers(document);
            document.save(output);
            return output.toByteArray();
        }
    }

    private PageState createPage(PDDocument document, String letterType, List<ValidationResult> results)
        throws IOException {
        PDPage page = new PDPage(landscapeA4());
        document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(document, page);
        float pageHeight = page.getMediaBox().getHeight();

        writeText(stream, boldFont, 18, DARK_BLUE, MARGIN, pageHeight - MARGIN, "Validation Report");
        writeText(stream, regularFont, 10, DARK_GRAY, MARGIN, pageHeight - MARGIN - 18,
            "Letter type: " + letterType);
        writeText(stream, regularFont, 9, DARK_GRAY, MARGIN, pageHeight - MARGIN - 36, summary(results));

        float tableTop = pageHeight - MARGIN - 58;
        drawHeader(stream, tableTop);
        return new PageState(stream, tableTop - HEADER_HEIGHT);
    }

    private PageState createContinuationPage(PDDocument document, String letterType) throws IOException {
        PDPage page = new PDPage(landscapeA4());
        document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(document, page);
        float pageHeight = page.getMediaBox().getHeight();
        writeText(stream, boldFont, 12, DARK_BLUE, MARGIN, pageHeight - MARGIN,
            "Validation Report - " + letterType);
        float tableTop = pageHeight - MARGIN - 18;
        drawHeader(stream, tableTop);
        return new PageState(stream, tableTop - HEADER_HEIGHT);
    }

    private void drawHeader(PDPageContentStream stream, float top) throws IOException {
        float x = MARGIN;
        stream.setNonStrokingColor(DARK_BLUE);
        stream.addRect(x, top - HEADER_HEIGHT, totalTableWidth(), HEADER_HEIGHT);
        stream.fill();

        for (int index = 0; index < HEADERS.length; index++) {
            writeText(stream, boldFont, 8, WHITE, x + CELL_PADDING, top - 14, HEADERS[index]);
            x += COLUMN_WIDTHS[index];
        }
        drawGrid(stream, top, HEADER_HEIGHT);
    }

    private void drawRow(
        PDPageContentStream stream,
        float top,
        float height,
        List<List<String>> cellLines,
        boolean shaded
    ) throws IOException {
        if (shaded) {
            stream.setNonStrokingColor(LIGHT_BLUE);
            stream.addRect(MARGIN, top - height, totalTableWidth(), height);
            stream.fill();
        }

        float x = MARGIN;
        for (int column = 0; column < cellLines.size(); column++) {
            float textY = top - CELL_PADDING - BODY_FONT_SIZE;
            for (String line : cellLines.get(column)) {
                writeText(stream, regularFont, BODY_FONT_SIZE, DARK_GRAY, x + CELL_PADDING, textY, line);
                textY -= LINE_HEIGHT;
            }
            x += COLUMN_WIDTHS[column];
        }
        drawGrid(stream, top, height);
    }

    private void drawGrid(PDPageContentStream stream, float top, float height) throws IOException {
        stream.setStrokingColor(LIGHT_GRAY);
        stream.setLineWidth(0.5f);
        float x = MARGIN;
        stream.moveTo(x, top);
        stream.lineTo(x + totalTableWidth(), top);
        stream.moveTo(x, top - height);
        stream.lineTo(x + totalTableWidth(), top - height);
        for (float columnWidth : COLUMN_WIDTHS) {
            stream.moveTo(x, top);
            stream.lineTo(x, top - height);
            x += columnWidth;
        }
        stream.moveTo(x, top);
        stream.lineTo(x, top - height);
        stream.stroke();
    }

    private List<List<String>> rowLines(ValidationResult result) throws IOException {
        String[] values = {
            String.valueOf(result.getRowNumber()),
            result.getEmployeeId(),
            result.getName(),
            result.getStatus().name().replace('_', ' '),
            result.isLetterGenerated() ? "YES" : "NO",
            result.getOutputFileName(),
            result.getEmail(),
            result.getEmailStatus().name().replace('_', ' '),
            reportDetails(result)
        };
        List<List<String>> lines = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            lines.add(wrap(values[index], COLUMN_WIDTHS[index] - (CELL_PADDING * 2)));
        }
        return lines;
    }

    private String reportDetails(ValidationResult result) {
        List<String> details = new ArrayList<>(result.getErrors());
        if (result.getEmailMessage() != null && !result.getEmailMessage().isBlank()) {
            details.add(result.getEmailMessage());
        }
        return String.join("; ", details);
    }

    private List<String> wrap(String value, float availableWidth) throws IOException {
        String text = safePdfText(value == null || value.isBlank() ? "-" : value, regularFont);
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (textWidth(candidate) <= availableWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                if (!current.isEmpty()) lines.add(current.toString());
                current.setLength(0);
                if (textWidth(word) <= availableWidth) current.append(word);
                else lines.addAll(splitLongWord(word, availableWidth));
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        if (lines.isEmpty()) lines.add("-");
        return lines;
    }

    private List<String> splitLongWord(String word, float availableWidth) throws IOException {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char character : word.toCharArray()) {
            String candidate = current.toString() + character;
            if (!current.isEmpty() && textWidth(candidate) > availableWidth) {
                parts.add(current.toString());
                current.setLength(0);
            }
            current.append(character);
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }

    private float textWidth(String text) throws IOException {
        return regularFont.getStringWidth(text) / 1000 * BODY_FONT_SIZE;
    }

    private int maxLineCount(List<List<String>> cellLines) {
        return cellLines.stream().mapToInt(List::size).max().orElse(1);
    }

    private String summary(List<ValidationResult> results) {
        long valid = results.stream().filter(result -> result.getStatus() == ValidationStatus.VALID).count();
        long invalid = results.stream().filter(result -> result.getStatus() == ValidationStatus.INVALID).count();
        long failed = results.stream().filter(result -> result.getStatus() == ValidationStatus.GENERATION_FAILED).count();
        long generated = results.stream().filter(ValidationResult::isLetterGenerated).count();
        long emailsSent = results.stream().filter(result -> result.getEmailStatus() == EmailStatus.SENT_TO_SERVER).count();
        long emailFailures = results.stream().filter(result -> result.getEmailStatus() == EmailStatus.FAILED).count();
        return String.format(
            Locale.ROOT,
            "Total: %d    Valid: %d    Invalid: %d    Generation failed: %d    Letters: %d    Emails accepted: %d    Email failures: %d",
            results.size(), valid, invalid, failed, generated, emailsSent, emailFailures
        );
    }

    private void addPageNumbers(PDDocument document) throws IOException {
        int totalPages = document.getNumberOfPages();
        for (int index = 0; index < totalPages; index++) {
            PDPage page = document.getPage(index);
            try (PDPageContentStream stream = new PDPageContentStream(
                document,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            )) {
                String pageNumber = "Page " + (index + 1) + " of " + totalPages;
                float width = boldFont.getStringWidth(pageNumber) / 1000 * 8;
                writeText(stream, boldFont, 8, DARK_GRAY,
                    page.getMediaBox().getWidth() - MARGIN - width, 18, pageNumber);
            }
        }
    }

    private void writeText(
        PDPageContentStream stream,
        PDFont font,
        float size,
        PDColor color,
        float x,
        float y,
        String text
    ) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(safePdfText(text, font));
        stream.endText();
    }

    private String safePdfText(String value, PDFont font) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder();
        for (char character : value.toCharArray()) {
            try {
                font.encode(String.valueOf(character));
                safe.append(character);
            } catch (Exception exception) {
                safe.append('?');
            }
        }
        return safe.toString();
    }

    private float totalTableWidth() {
        float width = 0;
        for (float columnWidth : COLUMN_WIDTHS) width += columnWidth;
        return width;
    }

    private PDRectangle landscapeA4() {
        return new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    }

    private record PageState(PDPageContentStream stream, float y) { }
}
