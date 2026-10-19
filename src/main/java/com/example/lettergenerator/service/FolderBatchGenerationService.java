package com.example.lettergenerator.service;

import com.example.lettergenerator.config.BatchProperties;
import com.example.lettergenerator.datasource.CsvDataProvider;
import com.example.lettergenerator.datasource.DataProvider;
import com.example.lettergenerator.datasource.ExcelDataProvider;
import com.example.lettergenerator.model.DataRecord;
import com.example.lettergenerator.model.EmailStatus;
import com.example.lettergenerator.model.TemplateMapping;
import com.example.lettergenerator.model.ValidationResult;
import com.example.lettergenerator.model.ValidationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class FolderBatchGenerationService {
    private static final Logger log = LoggerFactory.getLogger(FolderBatchGenerationService.class);
    private static final List<String> STANDARD_FIELDS =
        List.of("name", "employeeId", "address", "amount", "department", "joiningDate");

    private final BatchProperties properties;
    private final TemplateProcessorService templateProcessor;
    private final ValidationService validationService;
    private final ReportService reportService;
    private final GmailSmtpEmailService emailService;
    private final ProtectedLetterPdfService protectedPdfService;

    public FolderBatchGenerationService(
        BatchProperties properties,
        TemplateProcessorService templateProcessor,
        ValidationService validationService,
        ReportService reportService,
        GmailSmtpEmailService emailService,
        ProtectedLetterPdfService protectedPdfService
    ) {
        this.properties = properties;
        this.templateProcessor = templateProcessor;
        this.validationService = validationService;
        this.reportService = reportService;
        this.emailService = emailService;
        this.protectedPdfService = protectedPdfService;
    }

    public void processAll() throws IOException {
        Path inputRoot = Path.of(properties.inputDir()).toAbsolutePath().normalize();
        Path outputRoot = Path.of(properties.outputDir()).toAbsolutePath().normalize();
        Files.createDirectories(inputRoot);
        Files.createDirectories(outputRoot);

        try (FileChannel channel = FileChannel.open(outputRoot.resolve(".letter-generator.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new IOException("Another letter batch is already running", exception);
            }
            if (lock == null) throw new IOException("Another letter batch is already running");
            try (lock) {
                processAllLocked(inputRoot, outputRoot);
            }
        }
    }

    private void processAllLocked(Path inputRoot, Path outputRoot) throws IOException {
        List<Path> letterTypes;
        try (var entries = Files.list(inputRoot)) {
            letterTypes = entries.filter(Files::isDirectory).sorted().toList();
        }
        if (letterTypes.isEmpty()) {
            log.info("No letter-type folders found under {}", inputRoot);
            return;
        }

        int failures = 0;
        for (Path letterType : letterTypes) {
            String letterTypeName = letterType.getFileName().toString();
            try {
                processLetterType(letterType, outputRoot);
            } catch (Exception exception) {
                failures++;
                log.error("Failed to process letter type {}", letterTypeName, exception);
                writeProcessingError(outputRoot, letterTypeName, exception);
            }
        }
        if (failures > 0) throw new IOException(failures + " letter type(s) failed; check processing-error.txt");
    }

    private void processLetterType(Path letterType, Path outputRoot) throws IOException {
        String letterTypeName = letterType.getFileName().toString();
        Path typeOutput = safeTypeOutput(outputRoot, letterTypeName);
        if (Files.exists(typeOutput.resolve("daily-job"))
            || Files.exists(typeOutput.resolve("weeks"))
            || Files.exists(typeOutput.resolve("one-time"))
            || Files.exists(typeOutput.resolve("batches"))
            || Files.exists(typeOutput.resolve("delivery-state.properties"))
            || Files.exists(typeOutput.resolve("batch-plan.properties"))) {
            throw new IOException("Existing scheduled delivery state must be preserved; "
                + "use a different LETTER_OUTPUT_DIR for unscheduled runs: " + typeOutput);
        }

        Path templatePath = findSingleFile(letterType.resolve("template"), Set.of(".docx"));
        Path datasetPath = findSingleFile(letterType.resolve("dataset"), Set.of(".csv", ".xlsx"));
        byte[] template = Files.readAllBytes(templatePath);
        DataProvider provider = providerFor(datasetPath);
        List<DataRecord> records;
        try (InputStream input = Files.newInputStream(datasetPath)) {
            records = provider.loadData(input);
        }

        Set<String> placeholders = templateProcessor.extractPlaceholders(template);
        rejectPasswordPlaceholder(placeholders);
        TemplateMapping mapping = createAutomaticMapping(placeholders, provider.columns());
        List<ValidationResult> results = validationService.validate(records, provider.columns(), mapping);
        List<String> mappingErrors = validationService.validateMapping(provider.columns(), placeholders, mapping);
        if (!mappingErrors.isEmpty()) {
            results.forEach(result -> mappingErrors.forEach(result::addError));
        }
        prepareEmailValidation(records, provider.columns(), results);
        validateCustomerNumbers(records, provider.columns(), results);

        resetOutputDirectory(typeOutput);
        Path lettersOutput = Files.createDirectories(typeOutput.resolve("letters"));
        Set<String> usedFileNames = new LinkedHashSet<>();
        int generated = 0;
        for (int index = 0; index < records.size(); index++) {
            ValidationResult result = results.get(index);
            if (result.getStatus() != ValidationStatus.VALID) continue;

            try {
                byte[] personalizedDocx = templateProcessor.replacePlaceholders(
                    template, records.get(index), mapping.placeholderToColumn());
                Set<String> unresolved = templateProcessor.unresolvedPlaceholders(personalizedDocx);
                if (!unresolved.isEmpty()) {
                    throw new IOException("Unresolved placeholders: " + String.join(", ", unresolved));
                }
                byte[] generatedLetter = protectedPdfService.create(personalizedDocx,
                    customerNumber(records.get(index), provider.columns()));
                String filename = outputFileName(result, usedFileNames);
                Files.write(lettersOutput.resolve(filename), generatedLetter,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                result.setLetterGenerated(true);
                result.setOutputFileName(filename);
                generated++;
                sendEmailIfEnabled(result, letterTypeName, filename, generatedLetter);
            } catch (Exception exception) {
                result.markGenerationFailed("Generation failed: " + safeMessage(exception));
                if (emailService.isEnabled()) {
                    result.markEmailNotAttempted("Letter generation failed");
                }
            }
        }

        Files.write(typeOutput.resolve("validation-report.pdf"),
            reportService.pdf(results, letterTypeName),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        long invalid = results.stream().filter(result -> result.getStatus() != ValidationStatus.VALID).count();
        long emailsSent = results.stream().filter(result -> result.getEmailStatus() == EmailStatus.SENT_TO_SERVER).count();
        long emailsFailed = results.stream().filter(result -> result.getEmailStatus() == EmailStatus.FAILED).count();
        log.info("Processed {}: {} records, {} letters generated, {} invalid or failed, {} emails accepted, {} email failures",
            letterTypeName, records.size(), generated, invalid, emailsSent, emailsFailed);
    }

    private void prepareEmailValidation(List<DataRecord> records, List<String> columns,
                                        List<ValidationResult> results) {
        String emailColumn = matchingColumn("email", columns);
        boolean columnExists = emailColumn != null && columns.contains(emailColumn);
        for (int index = 0; index < results.size(); index++) {
            ValidationResult result = results.get(index);
            if (!emailService.isEnabled()) {
                result.setEmailStatus(EmailStatus.DISABLED);
                continue;
            }
            result.setEmailStatus(EmailStatus.NOT_ATTEMPTED);
            if (!columnExists) {
                result.addError("Required dataset column missing: email");
                result.markEmailNotAttempted("Email validation failed");
                continue;
            }
            String email = records.get(index).value(emailColumn).trim();
            result.setEmail(email);
            if (!emailService.isValidAddress(email)) {
                result.addError("Email address is missing or invalid");
                result.markEmailNotAttempted("Email validation failed");
            }
        }
    }

    private void validateCustomerNumbers(List<DataRecord> records, List<String> columns,
                                         List<ValidationResult> results) {
        String column = matchingColumn("customerNumber", columns);
        for (int index = 0; index < records.size(); index++) {
            ValidationResult result = results.get(index);
            if (!columns.contains(column) || records.get(index).value(column).isBlank()) {
                result.addError("Required customerNumber is missing; encrypted PDF was not generated");
                if (emailService.isEnabled()) result.markEmailNotAttempted("PDF password validation failed");
            }
        }
    }

    private String customerNumber(DataRecord record, List<String> columns) {
        return record.value(matchingColumn("customerNumber", columns));
    }

    private void rejectPasswordPlaceholder(Set<String> placeholders) throws IOException {
        if (placeholders.stream().anyMatch(field -> normalized(field).equals("customernumber"))) {
            throw new IOException("Template must not display customerNumber because it is the PDF password");
        }
    }

    private void sendEmailIfEnabled(ValidationResult result, String letterType,
                                    String filename, byte[] generatedLetter) {
        if (!emailService.isEnabled()) return;
        try {
            String recipient = emailService.sendLetter(result.getEmail(), result.getName(),
                letterType, filename, generatedLetter);
            result.markEmailSent(recipient);
        } catch (Exception exception) {
            result.markEmailFailed("Email failed: " + safeMessage(exception));
            log.warn("Email failed for row {}: {}", result.getRowNumber(), safeMessage(exception));
        }
    }

    private TemplateMapping createAutomaticMapping(Set<String> placeholders, List<String> columns) {
        Set<String> fields = new LinkedHashSet<>(STANDARD_FIELDS);
        fields.addAll(placeholders);
        Map<String, String> mappings = new LinkedHashMap<>();
        for (String field : fields) mappings.put(field, matchingColumn(field, columns));
        return new TemplateMapping(mappings);
    }

    private String matchingColumn(String placeholder, List<String> columns) {
        return columns.stream()
            .filter(column -> column.equals(placeholder))
            .findFirst()
            .orElseGet(() -> columns.stream()
                .filter(column -> normalized(column).equals(normalized(placeholder)))
                .findFirst()
                .orElse(placeholder));
    }

    private String normalized(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private DataProvider providerFor(Path datasetPath) {
        String filename = datasetPath.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".xlsx") ? new ExcelDataProvider() : new CsvDataProvider();
    }

    private Path findSingleFile(Path directory, Set<String> extensions) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("Required directory not found: " + directory);
        List<Path> matches;
        try (var files = Files.list(directory)) {
            matches = files.filter(Files::isRegularFile)
                .filter(path -> hasExtension(path, extensions))
                .sorted().toList();
        }
        if (matches.size() != 1) {
            throw new IOException("Expected exactly one " + String.join(" or ", extensions)
                + " file in " + directory + ", but found " + matches.size());
        }
        return matches.getFirst();
    }

    private boolean hasExtension(Path path, Set<String> extensions) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(filename::endsWith);
    }

    private String outputFileName(ValidationResult result, Set<String> usedFileNames) {
        String identifier = sanitize(result.getEmployeeId());
        if (identifier.isBlank()) identifier = "row-" + result.getRowNumber();
        String filename = "letter-" + identifier + ".pdf";
        if (!usedFileNames.add(filename)) {
            filename = "letter-" + identifier + "-row-" + result.getRowNumber() + ".pdf";
            usedFileNames.add(filename);
        }
        return filename;
    }

    private String sanitize(String value) {
        return value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Path safeTypeOutput(Path outputRoot, String letterTypeName) throws IOException {
        Path typeOutput = outputRoot.resolve(letterTypeName).normalize();
        if (!typeOutput.startsWith(outputRoot)) {
            throw new IOException("Invalid letter-type directory name: " + letterTypeName);
        }
        return typeOutput;
    }

    private void resetOutputDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            List<Path> paths;
            try (var walk = Files.walk(directory)) {
                paths = walk.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : paths) Files.delete(path);
        }
        Files.createDirectories(directory);
    }

    private void writeProcessingError(Path outputRoot, String letterTypeName, Exception exception) {
        try {
            Path typeOutput = safeTypeOutput(outputRoot, letterTypeName);
            Files.createDirectories(typeOutput);
            Files.writeString(typeOutput.resolve("processing-error.txt"), safeMessage(exception),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException writeException) {
            log.error("Could not write processing error for {}", letterTypeName, writeException);
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
