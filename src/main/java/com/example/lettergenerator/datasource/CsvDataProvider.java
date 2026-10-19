package com.example.lettergenerator.datasource;

import com.example.lettergenerator.model.DataRecord;
import com.opencsv.CSVReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class CsvDataProvider implements DataProvider {
    private List<String> columns = List.of();

    @Override
    public List<DataRecord> loadData(InputStream input) throws IOException {
        List<DataRecord> records = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String[] headers = reader.readNext();
            if (headers == null || headers.length == 0) throw new IOException("CSV does not contain headers");
            columns = Arrays.stream(headers).map(String::trim).toList();
            String[] values;
            int rowNumber = 1;
            while ((values = reader.readNext()) != null) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int index = 0; index < headers.length; index++) {
                    row.put(columns.get(index), index < values.length ? values[index].trim() : "");
                }
                records.add(new DataRecord(++rowNumber, row));
            }
        } catch (Exception exception) {
            if (exception instanceof IOException ioException) throw ioException;
            throw new IOException("Malformed CSV file", exception);
        }
        return records;
    }

    @Override public List<String> columns() { return columns; }
}
