package com.example.lettergenerator.validation;

import com.example.lettergenerator.model.DataRecord;
import java.util.Optional;

public interface ValidationRule {
    Optional<String> validate(DataRecord record, String field);
}
