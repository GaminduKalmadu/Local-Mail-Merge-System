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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** One opt-in, durable job for one letter type. The immediate workflow remains separate. */
@Service
@ConditionalOnProperty(name = "letter.batch.mode", havingValue = "daily")
public class DailyBatchService {
    private static final Logger log = LoggerFactory.getLogger(DailyBatchService.class);
    private static final String STATE_FILE = "delivery-state.properties";
    private static final List<String> STANDARD_FIELDS =
        List.of("name", "employeeId", "address", "amount", "department", "joiningDate");

    private final BatchProperties batch;
    private final TemplateProcessorService templates;
    private final ValidationService validation;
    private final ProtectedLetterPdfService pdfs;
    private final ReportService reports;
    private final GmailSmtpEmailService email;
    private final ConfigurableApplicationContext context;
    private final boolean sendEnabled;
    private final int dailyLimit;
    private final ZoneId zone;

    public DailyBatchService(BatchProperties batch, TemplateProcessorService templates,
                             ValidationService validation, ProtectedLetterPdfService pdfs,
                             ReportService reports, GmailSmtpEmailService email,
                             ConfigurableApplicationContext context,
                             @Value("${letter.daily.send-enabled:false}") boolean sendEnabled,
                             @Value("${letter.daily.limit:100}") int dailyLimit,
                             @Value("${letter.daily.zone-id:Asia/Colombo}") String zoneId) {
        if (dailyLimit < 1) throw new IllegalArgumentException("LETTER_DAILY_LIMIT must be at least 1");
        this.batch = batch;
        this.templates = templates;
        this.validation = validation;
        this.pdfs = pdfs;
        this.reports = reports;
        this.email = email;
        this.context = context;
        this.sendEnabled = sendEnabled;
        this.dailyLimit = dailyLimit;
        this.zone = ZoneId.of(zoneId);
    }

    @Scheduled(fixedDelayString = "${letter.daily.poll-delay-ms:60000}", initialDelay = 1000)
    public void poll() {
        if (!batch.enabled()) return;
        try {
            if (processWithLock()) CompletableFuture.runAsync(context::close);
        } catch (Exception exception) {
            log.error("Daily delivery stopped on this poll; job state was retained", exception);
        }
    }

    private boolean processWithLock() throws IOException {
        Path inputRoot = Path.of(batch.inputDir()).toAbsolutePath().normalize();
        Path outputRoot = Path.of(batch.outputDir()).toAbsolutePath().normalize();
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
                return processLocked(inputRoot, outputRoot);
            }
        }
    }

    private boolean processLocked(Path inputRoot, Path outputRoot) throws IOException {
        if (!Files.isDirectory(inputRoot)) throw new IOException("Input directory not found: " + inputRoot);
        List<Path> types;
        try (var entries = Files.list(inputRoot)) {
            types = entries.filter(Files::isDirectory).sorted().toList();
        }
        if (types.size() != 1) {
            throw new IOException("Daily mode requires exactly one letter-type folder under " + inputRoot
                + "; this keeps the daily email limit global");
        }
        Path type = types.getFirst();
        Path typeOutput = outputRoot.resolve(type.getFileName().toString()).normalize();
        if (!typeOutput.startsWith(outputRoot)) throw new IOException("Unsafe letter-type output path");
        Path job = typeOutput.resolve("daily-job");
        if (!Files.exists(job.resolve(STATE_FILE))) {
            if (Files.exists(job)) throw new IOException("Incomplete daily job exists: " + job);
            prepare(type, job);
        }
        Properties state = readState(job);
        String datasetName = required(state, "dataset-name");
        String templateName = required(state, "template-name");
        Path datasetPath = job.resolve(datasetName).normalize();
        Path templatePath = job.resolve(templateName).normalize();
        if (!datasetPath.getParent().equals(job) || !templatePath.getParent().equals(job)) {
            throw new IOException("Unsafe daily job snapshot filename");
        }
        byte[] dataset = Files.readAllBytes(datasetPath);
        byte[] template = Files.readAllBytes(templatePath);
        if (!sha256(dataset).equals(required(state, "dataset-sha"))
            || !sha256(template).equals(required(state, "template-sha"))) {
            throw new IOException("Daily job snapshot changed; delivery refused");
        }
        if (!zone.getId().equals(required(state, "zone-id"))) {
            throw new IOException("LETTER_DAILY_ZONE_ID changed since job preparation");
        }
        if (!email.testRecipient().equals(state.getProperty("test-recipient", ""))) {
            throw new IOException("LETTER_EMAIL_TEST_RECIPIENT changed since job preparation");
        }
        DataProvider provider = providerFor(datasetPath);
        List<DataRecord> records = provider.loadData(new ByteArrayInputStream(dataset));
        if (records.size() != Integer.parseInt(required(state, "row-count"))) {
            throw new IOException("Daily job row count changed; delivery refused");
        }
        if (pendingCount(state, records.size()) == 0) {
            finish(job, state, records, provider.columns());
            return true;
        }
        if (!sendEnabled) {
            log.info("Daily job prepared at {}; LETTER_DAILY_SEND_ENABLED=false, no email sent", job);
            return false;
        }
        if (!email.isEnabled()) {
            log.warn("Daily job is idle: set LETTER_EMAIL_ENABLED=true to send");
            return false;
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        String startedAt = state.getProperty("started-at", "");
        if (!startedAt.isBlank()) {
            LocalDate lastDate = LocalDate.parse(required(state, "last-batch-date"));
            LocalTime sendTime = OffsetDateTime.parse(startedAt).atZoneSameInstant(zone).toLocalTime();
            if (!now.toLocalDate().isAfter(lastDate) || now.toLocalTime().isBefore(sendTime)) {
                log.info("Next daily batch for {} is due on the next eligible day at {} {}",
                    type.getFileName(), sendTime, zone);
                return false;
            }
        }

        Set<String> placeholders = templates.extractPlaceholders(template);
        TemplateMapping mapping = automaticMapping(placeholders, provider.columns());
        String customerColumn = matchingColumn("customerNumber", provider.columns());
        int attempted = 0;
        Path letters = Files.createDirectories(job.resolve("letters"));
        for (int index = 0; index < records.size() && attempted < dailyLimit; index++) {
            if (!"PENDING".equals(required(state, "row." + index + ".status"))) continue;
            DataRecord record = records.get(index);
            String prefix = "row." + index + ".";
            // Record a slot and an uncertain state before generating or contacting SMTP.
            if (startedAt.isBlank()) {
                startedAt = now.toOffsetDateTime().toString();
                state.setProperty("started-at", startedAt);
            }
            state.setProperty("last-batch-date", now.toLocalDate().toString());
            state.setProperty(prefix + "status", "SENDING");
            state.setProperty(prefix + "attempt-date", now.toLocalDate().toString());
            state.setProperty(prefix + "message", "Attempt started; investigate before retrying after a crash");
            saveState(job, state);
            attempted++;

            try {
                byte[] personalized = templates.replacePlaceholders(template, record, mapping.placeholderToColumn());
                Set<String> unresolved = templates.unresolvedPlaceholders(personalized);
                if (!unresolved.isEmpty()) throw new IOException("Unresolved placeholders: " + String.join(", ", unresolved));
                byte[] attachment = pdfs.create(personalized, record.value(customerColumn));
                String filename = "letter-" + sanitize(record.value(matchingColumn("employeeId", provider.columns())))
                    + "-row-" + record.rowNumber() + ".pdf";
                Files.write(letters.resolve(filename), attachment, StandardOpenOption.CREATE_NEW);
                state.setProperty(prefix + "file", filename);
                saveState(job, state);
                String recipient = email.sendLetter(record.value(matchingColumn("email", provider.columns())),
                    record.value(matchingColumn("name", provider.columns())), type.getFileName().toString(),
                    filename, attachment);
                state.setProperty(prefix + "status", "SENT_TO_SERVER");
                state.setProperty(prefix + "message", "Accepted for " + recipient);
            } catch (Exception exception) {
                state.setProperty(prefix + "status", "FAILED");
                state.setProperty(prefix + "message", "Attempt failed: " + safeMessage(exception));
                log.warn("Daily job row {} failed: {}", record.rowNumber(), safeMessage(exception));
            }
            saveState(job, state);
        }
        writeReport(job, state, records, provider.columns());
        log.info("Daily batch processed {} rows; {} remain pending", attempted, pendingCount(state, records.size()));
        if (pendingCount(state, records.size()) == 0) {
            finish(job, state, records, provider.columns());
            return true;
        }
        return false;
    }

    private void prepare(Path type, Path job) throws IOException {
        Path datasetPath = singleFile(type.resolve("dataset"), Set.of(".csv", ".xlsx"));
        Path templatePath = singleFile(type.resolve("template"), Set.of(".docx"));
        byte[] dataset = Files.readAllBytes(datasetPath);
        byte[] template = Files.readAllBytes(templatePath);
        DataProvider provider = providerFor(datasetPath);
        List<DataRecord> records = provider.loadData(new ByteArrayInputStream(dataset));
        if (records.isEmpty()) throw new IOException("Daily job dataset is empty");
        Set<String> placeholders = templates.extractPlaceholders(template);
        if (placeholders.stream().anyMatch(value -> normalized(value).equals("customernumber"))) {
            throw new IOException("Template must not display customerNumber because it is the PDF password");
        }
        TemplateMapping mapping = automaticMapping(placeholders, provider.columns());
        List<ValidationResult> results = validation.validate(records, provider.columns(), mapping);
        List<String> mappingErrors = validation.validateMapping(provider.columns(), placeholders, mapping);
        String emailColumn = matchingColumn("email", provider.columns());
        String customerColumn = matchingColumn("customerNumber", provider.columns());
        for (int index = 0; index < records.size(); index++) {
            DataRecord record = records.get(index);
            ValidationResult result = results.get(index);
            mappingErrors.forEach(result::addError);
            if (!provider.columns().contains(emailColumn)
                || !email.isValidAddress(record.value(emailColumn))) {
                result.addError("Required email address is missing or invalid");
            }
            if (!provider.columns().contains(customerColumn) || record.value(customerColumn).isBlank()) {
                result.addError("Required customerNumber is missing");
            }
        }
        long invalid = results.stream().filter(result -> result.getStatus() != ValidationStatus.VALID).count();
        if (invalid > 0) {
            throw new IOException("Daily job has " + invalid + " invalid rows; correct the dataset before starting");
        }

        Files.createDirectories(job);
        String snapshotDataset = "source-dataset" + extension(datasetPath);
        String snapshotTemplate = "source-template.docx";
        Files.write(job.resolve(snapshotDataset), dataset, StandardOpenOption.CREATE_NEW);
        Files.write(job.resolve(snapshotTemplate), template, StandardOpenOption.CREATE_NEW);
        Properties state = new Properties();
        state.setProperty("version", "1");
        state.setProperty("dataset-name", snapshotDataset);
        state.setProperty("template-name", snapshotTemplate);
        state.setProperty("dataset-sha", sha256(dataset));
        state.setProperty("template-sha", sha256(template));
        state.setProperty("row-count", Integer.toString(records.size()));
        state.setProperty("test-recipient", email.testRecipient());
        state.setProperty("zone-id", zone.getId());
        state.setProperty("started-at", "");
        state.setProperty("last-batch-date", "");
        state.setProperty("job-status", "PENDING");
        for (int index = 0; index < records.size(); index++) {
            state.setProperty("row." + index + ".status", "PENDING");
        }
        saveState(job, state);
        writeReport(job, state, records, provider.columns());
        log.info("Prepared daily job for {}: {} rows, {} days minimum at {} per day; no email sent",
            type.getFileName(), records.size(), ((long) records.size() + dailyLimit - 1) / dailyLimit, dailyLimit);
    }

    private void finish(Path job, Properties state, List<DataRecord> records, List<String> columns) throws IOException {
        long accepted = 0;
        long needsReview = 0;
        for (int index = 0; index < records.size(); index++) {
            String status = required(state, "row." + index + ".status");
            if ("SENT_TO_SERVER".equals(status)) accepted++;
            else if (!"PENDING".equals(status)) needsReview++;
        }
        state.setProperty("job-status", needsReview == 0 ? "COMPLETE" : "NEEDS_REVIEW");
        saveState(job, state);
        writeReport(job, state, records, columns);
        log.info("Daily job finished: {} accepted by SMTP, {} rows need review. Service will stop.",
            accepted, needsReview);
    }

    private void writeReport(Path job, Properties state, List<DataRecord> records, List<String> columns) throws IOException {
        String emailColumn = matchingColumn("email", columns);
        String employeeColumn = matchingColumn("employeeId", columns);
        String nameColumn = matchingColumn("name", columns);
        List<ValidationResult> results = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            DataRecord record = records.get(index);
            ValidationResult result = new ValidationResult(record.rowNumber(), record.value(employeeColumn), record.value(nameColumn));
            result.setEmail(record.value(emailColumn));
            String prefix = "row." + index + ".";
            String filename = state.getProperty(prefix + "file", "");
            if (!filename.isBlank()) {
                result.setLetterGenerated(true);
                result.setOutputFileName(filename);
            }
            String status = required(state, prefix + "status");
            result.setEmailStatus(EmailStatus.valueOf(status));
            result.setEmailMessage(state.getProperty(prefix + "message", ""));
            if ("FAILED".equals(status) && filename.isBlank()) {
                result.markGenerationFailed(state.getProperty(prefix + "message", "Generation failed"));
            }
            results.add(result);
        }
        Files.write(job.resolve("validation-report.pdf"), reports.pdf(results, job.getParent().getFileName().toString()),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private int pendingCount(Properties state, int rowCount) throws IOException {
        int pending = 0;
        for (int index = 0; index < rowCount; index++) {
            String status = required(state, "row." + index + ".status");
            if ("PENDING".equals(status)) pending++;
            else if (!Set.of("SENDING", "SENT_TO_SERVER", "FAILED").contains(status)) {
                throw new IOException("Invalid daily job row status: " + status);
            }
        }
        return pending;
    }

    private Properties readState(Path job) throws IOException {
        Properties state = new Properties();
        try (InputStream input = Files.newInputStream(job.resolve(STATE_FILE))) {
            state.load(input);
        }
        if (!"1".equals(required(state, "version"))) throw new IOException("Unsupported daily job state version");
        return state;
    }

    private void saveState(Path job, Properties state) throws IOException {
        Path target = job.resolve(STATE_FILE);
        Path temporary = job.resolve(STATE_FILE + ".tmp");
        try (var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            state.store(output, "Daily letter delivery state");
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private TemplateMapping automaticMapping(Set<String> placeholders, List<String> columns) {
        Set<String> fields = new LinkedHashSet<>(STANDARD_FIELDS);
        fields.addAll(placeholders);
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String field : fields) mapping.put(field, matchingColumn(field, columns));
        return new TemplateMapping(mapping);
    }

    private String matchingColumn(String field, List<String> columns) {
        return columns.stream().filter(column -> column.equals(field)).findFirst()
            .orElseGet(() -> columns.stream()
                .filter(column -> normalized(column).equals(normalized(field)))
                .findFirst().orElse(field));
    }

    private String normalized(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String sanitize(String value) {
        return value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private DataProvider providerFor(Path dataset) {
        return dataset.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")
            ? new ExcelDataProvider() : new CsvDataProvider();
    }

    private Path singleFile(Path directory, Set<String> extensions) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("Required directory not found: " + directory);
        List<Path> matches;
        try (var files = Files.list(directory)) {
            matches = files.filter(Files::isRegularFile)
                .filter(path -> extensions.stream().anyMatch(ext ->
                    path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext)))
                .sorted().toList();
        }
        if (matches.size() != 1) throw new IOException("Expected exactly one input file in " + directory);
        return matches.getFirst();
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        return name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 unavailable", exception);
        }
    }

    private String required(Properties values, String key) throws IOException {
        String value = values.getProperty(key);
        if (value == null) throw new IOException("Missing daily job state field: " + key);
        return value;
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
