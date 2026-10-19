package com.example.lettergenerator.validation;

import com.example.lettergenerator.model.DataRecord;
import java.math.BigDecimal;
import java.util.Optional;

public class PositiveNumberRule implements ValidationRule {
    @Override public Optional<String> validate(DataRecord record, String field) {
        String value = record.value(field);
        if (value.isBlank()) return Optional.empty();
        try { return new BigDecimal(value).signum() <= 0 ? Optional.of(field + " must be greater than 0") : Optional.empty(); }
        catch (NumberFormatException exception) { return Optional.empty(); }
    }
}
