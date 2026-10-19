package com.example.lettergenerator.datasource;

import com.example.lettergenerator.model.DataRecord;
import org.apache.poi.ss.usermodel.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExcelDataProvider implements DataProvider {
    private List<String> columns = List.of();

    @Override
    public List<DataRecord> loadData(InputStream input) throws IOException {
        List<DataRecord> records = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null || header.getLastCellNum() <= 0) throw new IOException("Excel sheet does not contain headers");
            DataFormatter formatter = new DataFormatter();
            List<String> headers = new ArrayList<>();
            for (Cell cell : header) headers.add(formatter.formatCellValue(cell).trim());
            columns = List.copyOf(headers);
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                Map<String, String> values = new LinkedHashMap<>();
                for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                    Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    values.put(headers.get(columnIndex), cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                records.add(new DataRecord(rowIndex + 1, values));
            }
        } catch (IOException exception) { throw exception; }
        catch (Exception exception) { throw new IOException("Malformed Excel file", exception); }
        return records;
    }

    @Override public List<String> columns() { return columns; }
}
