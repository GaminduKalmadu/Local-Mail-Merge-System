package com.example.lettergenerator.model;

import java.util.Map;

public record DataRecord(int rowNumber, Map<String, String> values) {
    public DataRecord {
        values = Map.copyOf(values);
    }

    public String value(String field) {
        return field == null ? "" : values.getOrDefault(field, "");
    }
}
