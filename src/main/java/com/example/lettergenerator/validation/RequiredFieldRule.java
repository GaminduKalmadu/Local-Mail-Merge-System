package com.example.lettergenerator.validation;

import com.example.lettergenerator.model.DataRecord;
import java.util.Optional;

public class RequiredFieldRule implements ValidationRule {
    @Override public Optional<String> validate(DataRecord record, String field) {
        return record.value(field).isBlank() ? Optional.of(field + " is required") : Optional.empty();
    }
}
