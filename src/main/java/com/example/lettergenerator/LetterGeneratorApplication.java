package com.example.lettergenerator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LetterGeneratorApplication {
    public static void main(String[] args) {
        if (System.getProperty("pdfbox.fontcache") == null) {
            System.setProperty("pdfbox.fontcache", System.getProperty("java.io.tmpdir"));
        }
        SpringApplication.run(LetterGeneratorApplication.class, args);
    }
}