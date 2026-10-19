package com.example.lettergenerator.model;

import java.util.Map;

public record TemplateMapping(Map<String, String> placeholderToColumn) {
    public TemplateMapping { placeholderToColumn = Map.copyOf(placeholderToColumn); }
    public String columnFor(String placeholder) { return placeholderToColumn.get(placeholder); }
}
