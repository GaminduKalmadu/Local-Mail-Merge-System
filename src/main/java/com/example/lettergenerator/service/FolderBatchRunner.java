package com.example.lettergenerator.service;

import com.example.lettergenerator.config.BatchProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FolderBatchRunner implements ApplicationRunner {
    private final BatchProperties properties;
    private final FolderBatchGenerationService batchGenerationService;

    public FolderBatchRunner(
        BatchProperties properties,
        FolderBatchGenerationService batchGenerationService
    ) {
        this.properties = properties;
        this.batchGenerationService = batchGenerationService;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        if (!properties.enabled()) return;
        if ("immediate".equalsIgnoreCase(properties.mode())) batchGenerationService.processAll();
        else if (!"daily".equalsIgnoreCase(properties.mode()))
            throw new IllegalArgumentException("LETTER_BATCH_MODE must be immediate or daily");
    }
}
