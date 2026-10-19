package com.example.lettergenerator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;

@org.springframework.context.annotation.Configuration
@EnableConfigurationProperties({BatchProperties.class, GmailSmtpProperties.class})
public class Configuration { }
