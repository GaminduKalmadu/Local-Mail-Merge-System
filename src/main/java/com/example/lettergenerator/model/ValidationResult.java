package com.example.lettergenerator.model;

import java.util.ArrayList;
import java.util.List;

public class ValidationResult {
    private final int rowNumber;
    private final String employeeId;
    private final String name;
    private ValidationStatus status;
    private boolean letterGenerated;
    private final List<String> errors = new ArrayList<>();
    private String outputFileName;
    private String email;
    private EmailStatus emailStatus = EmailStatus.DISABLED;
    private String emailMessage;

    public ValidationResult(int rowNumber, String employeeId, String name) {
        this.rowNumber = rowNumber;
        this.employeeId = employeeId;
        this.name = name;
        this.status = ValidationStatus.VALID;
    }

    public void addError(String error) { errors.add(error); status = ValidationStatus.INVALID; }
    public void markGenerationFailed(String error) { errors.add(error); status = ValidationStatus.GENERATION_FAILED; }
    public int getRowNumber() { return rowNumber; }
    public String getEmployeeId() { return employeeId; }
    public String getName() { return name; }
    public ValidationStatus getStatus() { return status; }
    public boolean isLetterGenerated() { return letterGenerated; }
    public void setLetterGenerated(boolean letterGenerated) { this.letterGenerated = letterGenerated; }
    public List<String> getErrors() { return List.copyOf(errors); }
    public String getOutputFileName() { return outputFileName; }
    public void setOutputFileName(String outputFileName) { this.outputFileName = outputFileName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public EmailStatus getEmailStatus() { return emailStatus; }
    public void setEmailStatus(EmailStatus emailStatus) { this.emailStatus = emailStatus; }
    public String getEmailMessage() { return emailMessage; }
    public void markEmailSent(String recipient) {
        this.emailStatus = EmailStatus.SENT_TO_SERVER;
        this.emailMessage = "Accepted for " + recipient;
    }
    public void markEmailNotAttempted(String reason) {
        this.emailStatus = EmailStatus.NOT_ATTEMPTED;
        this.emailMessage = reason;
    }
    public void markEmailFailed(String error) {
        this.emailStatus = EmailStatus.FAILED;
        this.emailMessage = error;
    }
    public void setEmailMessage(String emailMessage) { this.emailMessage = emailMessage; }
}
