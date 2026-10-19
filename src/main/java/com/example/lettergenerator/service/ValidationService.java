package com.example.lettergenerator.service;

import com.example.lettergenerator.model.*;
import com.example.lettergenerator.validation.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ValidationService {
    private final Map<String, List<ValidationRule>> rulesByField = Map.of(
        "name", List.of(new RequiredFieldRule()),
        "employeeId", List.of(new RequiredFieldRule()),
        "address", List.of(new RequiredFieldRule()),
        "amount", List.of(new RequiredFieldRule(), new NumericRule(), new PositiveNumberRule()),
        "department", List.of(new RequiredFieldRule()),
        "joiningDate", List.of(new RequiredFieldRule(), new DateRule())
    );

    public List<ValidationResult> validate(List<DataRecord> records, List<String> columns, TemplateMapping mapping) {
        List<ValidationResult> results = new ArrayList<>();
        Set<String> mappedColumns = new HashSet<>(mapping.placeholderToColumn().values());
        for (DataRecord record : records) {
            ValidationResult result = new ValidationResult(record.rowNumber(), record.value(mapping.columnFor("employeeId")), record.value(mapping.columnFor("name")));
            for (Map.Entry<String, List<ValidationRule>> entry : rulesByField.entrySet()) {
                String column = mapping.columnFor(entry.getKey());
                if (column == null || !columns.contains(column)) result.addError("Required dataset column missing: " + entry.getKey());
                else entry.getValue().forEach(rule -> rule.validate(record, column).ifPresent(result::addError));
            }
            mapping.placeholderToColumn().forEach((placeholder, column) -> {
                if (!columns.contains(column)) result.addError("Mapped dataset column missing: " + column);
                else if (record.value(column).isBlank()) result.addError("Mapped value is blank: " + placeholder);
            });
            results.add(result);
        }
        return results;
    }

    public List<String> validateMapping(List<String> columns, Set<String> placeholders, TemplateMapping mapping) {
        List<String> errors = new ArrayList<>();
        placeholders.forEach(placeholder -> {
            String column = mapping.columnFor(placeholder);
            if (column == null || column.isBlank()) errors.add("Template placeholder not mapped: " + placeholder);
            else if (!columns.contains(column)) errors.add("Mapped dataset column missing: " + column);
        });
        return errors;
    }
}
