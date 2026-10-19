package com.example.lettergenerator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "letter.email")
public record GmailSmtpProperties(
    boolean enabled,
    String host,
    int port,
    String username,
    String appPassword,
    String testRecipient,
    String subject,
    String body
) { }
