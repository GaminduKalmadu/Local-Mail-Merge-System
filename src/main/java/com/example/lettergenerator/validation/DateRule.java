package com.example.lettergenerator.validation;

import com.example.lettergenerator.model.DataRecord;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public class DateRule implements ValidationRule {
    @Override public Optional<String> validate(DataRecord record, String field) {
        String value = record.value(field);
        if (value.isBlank()) return Optional.empty();
        try { LocalDate.parse(value); return Optional.empty(); }
        catch (DateTimeParseException exception) { return Optional.of(field + " must be a valid date"); }
    }
}
