package com.example.lettergenerator.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "letter.batch.mode", havingValue = "daily")
public class DailyBatchSchedulingConfiguration { }
