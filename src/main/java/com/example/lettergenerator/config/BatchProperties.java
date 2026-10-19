package com.example.lettergenerator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "letter.batch")
public record BatchProperties(boolean enabled, String inputDir, String outputDir, String mode) {
}
