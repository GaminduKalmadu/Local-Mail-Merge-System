package com.example.lettergenerator.service;

import com.example.lettergenerator.config.GmailSmtpProperties;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Service
public class GmailSmtpEmailService {
    private final GmailSmtpProperties properties;

    public GmailSmtpEmailService(GmailSmtpProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    public String testRecipient() {
        return properties.testRecipient() == null ? "" : properties.testRecipient().trim();
    }

    public String sendLetter(
        String recipient,
        String employeeName,
        String letterType,
        String attachmentName,
        byte[] attachment
    ) throws Exception {
        validateConfiguration();
        String deliveryRecipient = hasText(properties.testRecipient())
            ? properties.testRecipient().trim()
            : recipient.trim();
        new InternetAddress(deliveryRecipient, true).validate();

        Session session = Session.getInstance(smtpProperties());
        MimeMessage message = new MimeMessage(session);
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.username().trim());
        helper.setTo(deliveryRecipient);
        helper.setSubject(expand(properties.subject(), employeeName, letterType));
        helper.setText(expand(properties.body(), employeeName, letterType), false);
        helper.addAttachment(
            attachmentName,
            new ByteArrayResource(attachment),
            "application/pdf"
        );
        message.setHeader("Auto-Submitted", "auto-generated");
        message.saveChanges();

        Transport transport = session.getTransport("smtp");
        try {
            transport.connect(
                properties.host(),
                properties.port(),
                properties.username().trim(),
                normalizedAppPassword()
            );
            transport.sendMessage(message, message.getRecipients(Message.RecipientType.TO));
        } finally {
            if (transport.isConnected()) transport.close();
        }
        return deliveryRecipient;
    }

    public boolean isValidAddress(String address) {
        if (!hasText(address)) return false;
        try {
            InternetAddress parsed = new InternetAddress(address.trim(), true);
            parsed.validate();
            return parsed.getAddress().contains("@");
        } catch (Exception exception) {
            return false;
        }
    }

    private void validateConfiguration() {
        require(properties.host(), "GMAIL_SMTP_HOST");
        require(properties.username(), "GMAIL_USERNAME");
        require(properties.appPassword(), "GMAIL_APP_PASSWORD");
        if (!isValidAddress(properties.username())) {
            throw new IllegalStateException("GMAIL_USERNAME must be a valid email address");
        }
        if (properties.port() < 1 || properties.port() > 65535) {
            throw new IllegalStateException("GMAIL_SMTP_PORT must be between 1 and 65535");
        }
    }

    private Properties smtpProperties() {
        Properties smtp = new Properties();
        smtp.setProperty("mail.smtp.auth", "true");
        smtp.setProperty("mail.smtp.auth.mechanisms", "LOGIN PLAIN");
        smtp.setProperty("mail.smtp.starttls.enable", "true");
        smtp.setProperty("mail.smtp.starttls.required", "true");
        smtp.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        smtp.setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2");
        smtp.setProperty("mail.smtp.connectiontimeout", "15000");
        smtp.setProperty("mail.smtp.timeout", "30000");
        smtp.setProperty("mail.smtp.writetimeout", "30000");
        smtp.setProperty("mail.debug", "false");
        return smtp;
    }

    private String normalizedAppPassword() {
        return properties.appPassword().replaceAll("\\s+", "");
    }

    private String expand(String template, String employeeName, String letterType) {
        String value = template == null ? "" : template;
        return value
            .replace("{name}", employeeName == null ? "" : employeeName)
            .replace("{letterType}", letterType == null ? "" : letterType);
    }

    private void require(String value, String setting) {
        if (!hasText(value)) throw new IllegalStateException(setting + " is required when email is enabled");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
