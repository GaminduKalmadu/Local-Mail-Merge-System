package com.example.lettergenerator.service;

import com.example.lettergenerator.model.DataRecord;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TemplateProcessorService {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}" );

    public Set<String> extractPlaceholders(byte[] document) throws IOException {
        Set<String> placeholders = new LinkedHashSet<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(document))) {
            collectPlaceholders(doc.getParagraphs(), placeholders);
            collectTablePlaceholders(doc.getTables(), placeholders);
        }
        return placeholders;
    }

    public byte[] replacePlaceholders(byte[] document, DataRecord record, Map<String, String> mapping) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(document)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            replaceParagraphs(doc.getParagraphs(), record, mapping);
            replaceTables(doc.getTables(), record, mapping);
            doc.write(output);
            return output.toByteArray();
        }
    }

    public Set<String> unresolvedPlaceholders(byte[] document) throws IOException { return extractPlaceholders(document); }

    private void collectPlaceholders(List<XWPFParagraph> paragraphs, Set<String> result) {
        for (XWPFParagraph paragraph : paragraphs) {
            Matcher matcher = PLACEHOLDER.matcher(paragraphText(paragraph));
            while (matcher.find()) result.add(matcher.group(1));
        }
    }
    private void collectTablePlaceholders(List<XWPFTable> tables, Set<String> result) {
        for (XWPFTable table : tables) for (XWPFTableRow row : table.getRows()) for (XWPFTableCell cell : row.getTableCells()) {
            collectPlaceholders(cell.getParagraphs(), result); collectTablePlaceholders(cell.getTables(), result);
        }
    }
    private void replaceParagraphs(List<XWPFParagraph> paragraphs, DataRecord record, Map<String, String> mapping) {
        paragraphs.forEach(paragraph -> replaceParagraph(paragraph, record, mapping));
    }
    private void replaceTables(List<XWPFTable> tables, DataRecord record, Map<String, String> mapping) {
        for (XWPFTable table : tables) for (XWPFTableRow row : table.getRows()) for (XWPFTableCell cell : row.getTableCells()) {
            replaceParagraphs(cell.getParagraphs(), record, mapping); replaceTables(cell.getTables(), record, mapping);
        }
    }
    private void replaceParagraph(XWPFParagraph paragraph, DataRecord record, Map<String, String> mapping) {
        String original = paragraphText(paragraph);
        String replaced = PLACEHOLDER.matcher(original).replaceAll(match -> {
            String column = mapping.get(match.group(1));
            return column == null ? match.group(0) : record.value(column);
        });
        if (!original.equals(replaced)) {
            XWPFRun first = paragraph.getRuns().isEmpty() ? paragraph.createRun() : paragraph.getRuns().get(0);
            for (int i = paragraph.getRuns().size() - 1; i > 0; i--) paragraph.removeRun(i);
            first.setText(replaced, 0);
        }
    }
    private String paragraphText(XWPFParagraph paragraph) { return paragraph.getRuns().stream().map(XWPFRun::text).filter(Objects::nonNull).reduce("", String::concat); }
}
