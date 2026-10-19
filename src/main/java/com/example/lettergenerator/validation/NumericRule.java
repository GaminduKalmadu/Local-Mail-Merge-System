package com.example.lettergenerator.validation;

import com.example.lettergenerator.model.DataRecord;
import java.util.Optional;

public class NumericRule implements ValidationRule {
    @Override public Optional<String> validate(DataRecord record, String field) {
        String value = record.value(field);
        if (value.isBlank()) return Optional.empty();
        try { new java.math.BigDecimal(value); return Optional.empty(); }
        catch (NumberFormatException exception) { return Optional.of(field + " must be numeric"); }
    }
}
