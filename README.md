# Letter Forge

Letter Forge is a backend-only Spring Boot application. It reads one DOCX template and one CSV/XLSX dataset from each letter-type directory under `input/`, generates one personalized, password-encrypted PDF per valid row, optionally sends it through Gmail SMTP, and writes a validation report. Immediate mode runs once; the opt-in daily mode submits up to the configured limit per day until the job is processed.

## Requirements

- Java 21
- Maven 3.9+

## Input structure

The template and dataset filenames do not need to match. Their common letter-type directory associates them.

```text
input/
  appointment-letter/
    template/
      Appointment Letter Final.docx
    dataset/
      New Employees September.csv
  salary-letter/
    template/
      Staff Salary Notice.docx
    dataset/
      Payroll Export 2026.xlsx
  promotion-letter/
    template/
      promotion-template.docx
    dataset/
      selected-employees.csv
```

Every `template` directory must contain exactly one `.docx` file. Every `dataset` directory must contain exactly one `.csv` or `.xlsx` file.

## Template placeholders

Use placeholders such as:

```text
Dear {{name}},

Employee ID: {{employeeId}}
Department: {{department}}
Amount: {{amount}}
```

Placeholder matching is case-insensitive and ignores spaces, underscores, and hyphens. For example, `employeeId`, `Employee ID`, and `employee_id` match one another.

## Run

```bash
mvn clean package
java -jar target/letter-generator-0.0.1-SNAPSHOT.jar
```

Copy `.env.example` to `.env` and configure it before running. Set `LETTER_EMAIL_ENABLED=false` to generate letters without sending email. When `LETTER_EMAIL_ENABLED=true`, each run generates and sends immediately, one by one. For a safe test, set `LETTER_EMAIL_TEST_RECIPIENT` to an address you control. The example `example.com` addresses cannot receive mail.

Immediate mode does not run continuously or enforce delivery dates. Rerunning it with email enabled can resend the same letters, so check recipients before every run.

## Opt-in daily delivery: configurable daily limit

The `daily` mode is a separate workflow. It reads the dataset size itself, freezes the dataset and template in `output/<letter-type>/daily-job/`, and submits at most `LETTER_DAILY_LIMIT` rows per Asia/Colombo calendar day (default 100; must be at least 1). The first batch starts when delivery is enabled and the service is running. Later batches start no earlier than the same local time on following days. For 235 valid rows with the default limit, it takes at least three days: 100, 100, then 35. If the service is down at that time, it sends one batch when it next runs; it never catches up with multiple batches on one day.

1. Place exactly one letter-type folder under `input/`. Daily mode deliberately accepts one job at a time so its daily limit is global.
2. Set `LETTER_BATCH_MODE=daily`, `LETTER_DAILY_SEND_ENABLED=false`, and `LETTER_EMAIL_TEST_RECIPIENT` to an address you control in `.env`. Start the JAR. The service prepares a durable job and a validation report but sends nothing. Review `output/<letter-type>/daily-job/validation-report.pdf` and the snapshot before activating delivery. Keep `LETTER_EMAIL_TEST_RECIPIENT` unchanged for this job.
3. When ready, set `LETTER_EMAIL_ENABLED=true` and `LETTER_DAILY_SEND_ENABLED=true`, then restart the application. This triggers the first batch immediately, up to `LETTER_DAILY_LIMIT` rows. Keep the PC and Java process running for subsequent days.
4. Check `output/<letter-type>/daily-job/delivery-state.properties` and the report after each batch. `SENT_TO_SERVER` means Gmail accepted the message, not inbox delivery. The service stops automatically when no pending rows remain. `FAILED` or uncertain `SENDING` rows require manual review; they are never automatically retried.

Set `LETTER_DAILY_ZONE_ID` if the local calendar day is not `Asia/Colombo`. The service checks due work every `LETTER_DAILY_POLL_DELAY_MS` (default 60 seconds), so sending is not guaranteed at an exact second. The first send time, timezone, and test recipient are frozen for each job. The daily limit is read at startup; changing it takes effect on the next run/batch after a restart, including an existing job. If the app restarts, it resumes pending rows from the saved snapshot instead of rereading a modified input dataset. Do not delete or edit the job state to force a resend. To start a different dataset, preserve/archive the completed `daily-job` directory and use a new output directory. The snapshot contains personal data and customer-number PDF passwords; protect the output folder.

Daily mode accepts syntactically valid `example.com` test addresses, but those addresses cannot receive mail. For a test you can actually receive, set `LETTER_EMAIL_TEST_RECIPIENT` to an address you control before preparing the job. Leave `LETTER_DAILY_SEND_ENABLED=false` while reviewing a job. Do not use the ordinary immediate mode with the same output folder while a daily job exists.

## Output structure

```text
output/
  appointment-letter/
    letters/
      letter-EMP001.pdf
      letter-EMP002.pdf
    validation-report.pdf
```

Only valid rows produce letters. Invalid rows and generation failures appear in `validation-report.pdf`. An input-structure or file-processing failure creates `processing-error.txt`.

Each immediate run rebuilds its letter-type output folder. Older scheduled output and delivery-state files are preserved; immediate mode refuses to overwrite them. The existing `output/letter/` contains historical scheduled state. Set `LETTER_OUTPUT_DIR` to a different directory before running the immediate workflow.

## Configuration

```properties
letter.batch.enabled=true
letter.batch.input-dir=input
letter.batch.output-dir=output
```

The directories can also be configured using `LETTER_INPUT_DIR` and `LETTER_OUTPUT_DIR` environment variables.

## Gmail email

Email delivery is disabled by default. When enabled, the dataset must contain an `email` column. The project uses a dedicated Gmail account, a Google App Password, required STARTTLS, and server identity verification. The normal Gmail account password must never be used.

## Password-protected letters

Every generated letter requires a nonblank `customerNumber` column. Its value is the PDF **open password** for that row, so preserve leading zeros by storing the column as text in Excel. The example CSV files use fictional numbers. The app encrypts each PDF with a 256-bit key before writing it to `output/` or attaching it to an email. It never writes an unencrypted personalized DOCX or PDF to disk. A template containing `{{customerNumber}}` is refused so the password is not printed in the letter. The validation report remains unencrypted and must be access-controlled locally.

Recipients must already know their customer number through a separate, verified channel. Do **not** put it in the same email. A customer number is often predictable or available elsewhere, so this is weaker than a random per-letter password; use it only if that risk is acceptable. Encryption protects the attachment, not the email text or address, and an authorized recipient can still copy or share what they see.

The built-in Java PDF converter supports text-only DOCX letters with ordinary paragraphs and basic alignment. It rejects tables, images, and nonempty headers/footers rather than silently losing content. Review a generated PDF against your template before production. For complex layouts or non-Latin text, add a full DOCX-to-PDF converter before sending.

Populate `.env` using `.env.example`, then follow [Gmail SMTP setup](docs/GMAIL_SMTP_SETUP.md). The populated `.env` is ignored by Git.

## Main source structure

```text
src/main/java/com/example/lettergenerator/
  config/       Batch configuration
  datasource/   CSV and XLSX readers
  model/        Data and validation models
  service/      Folder scanning, validation, document generation, reports
  validation/   Required, numeric, positive-number, and date rules
```
