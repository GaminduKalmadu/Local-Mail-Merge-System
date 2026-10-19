# Gmail SMTP setup

The application sends generated, password-encrypted PDF letters through Gmail SMTP using a dedicated Gmail account, a Google App Password, required STARTTLS, TLS 1.2 or 1.3, and server-hostname verification. It never uses or stores the Gmail account's normal password.

## 1. Create or select a dedicated Gmail account

Use a separate sender account rather than a personal mailbox, for example `company.letter.sender@gmail.com`.

## 2. Enable 2-Step Verification

1. Open <https://myaccount.google.com/>.
2. Select **Security**.
3. Under **How you sign in to Google**, enable **2-Step Verification**.

## 3. Create an App Password

1. Open <https://myaccount.google.com/apppasswords>.
2. Sign in again if requested.
3. Enter `Letter Generator` as the application name.
4. Select **Create**.
5. Copy the generated 16-character password. This is `GMAIL_APP_PASSWORD`; it is not the normal Gmail password.

The App Password option may be unavailable for Advanced Protection, security-key-only verification, or administrator-controlled work/school accounts.

## 4. Fill `.env`

```properties
LETTER_EMAIL_ENABLED=false

GMAIL_SMTP_HOST=smtp.gmail.com
GMAIL_SMTP_PORT=587
GMAIL_USERNAME=company.letter.sender@gmail.com
GMAIL_APP_PASSWORD=replace-with-the-google-app-password

LETTER_EMAIL_TEST_RECIPIENT=your-own-address@gmail.com
LETTER_EMAIL_SUBJECT=Your letter
LETTER_EMAIL_BODY=Dear {name}, please find your {letterType} letter attached.
```

Spaces copied with the App Password are removed by the application. The populated `.env` is ignored by Git. For a deployed service, prefer operating-system or deployment secret variables instead of a plaintext `.env` file.

## 5. Add recipient addresses to the dataset

When email is enabled, every valid row must have an `email` column. Every generated PDF also requires `customerNumber`:

```csv
employeeId,name,email,address,amount,department,joiningDate,customerNumber
EMP001,John Silva,john.silva@example.com,Colombo,5000,IT,2026-09-01,CUST000001
```

## 6. Test safely

1. Leave `LETTER_EMAIL_ENABLED=false` and verify normal letter generation.
2. Set `LETTER_EMAIL_TEST_RECIPIENT` to an address you control.
3. Use a dataset with only one valid row.
4. Change `LETTER_EMAIL_ENABLED=true`.
5. Run the application and verify the email and encrypted PDF attachment. It should refuse to open without the row's customer number.
6. Review `validation-report.pdf`.
7. Set `LETTER_EMAIL_TEST_RECIPIENT=` (blank) only when real delivery is approved. Each message will then go to the `email` value in its own dataset row.

`SENT TO SERVER` means Gmail's SMTP server accepted the message; it is not a delivery or read confirmation. An email failure is recorded for that row and does not stop the remaining batch.

The customer number is the document-open password. Do not include it in the email message; give it to the recipient through a separate channel. Because customer numbers may be guessable, consider a random per-letter password or a secure portal for more sensitive letters. The built-in PDF converter accepts only simple text DOCX templates.

## 7. Common errors

- `GMAIL_APP_PASSWORD is required`: the `.env` value is blank.
- `Username and Password not accepted`: use the App Password, confirm 2-Step Verification, and revoke/recreate the App Password if necessary.
- `Email address is missing or invalid`: add a valid address to the dataset's `email` column.
- App Password option missing: check Advanced Protection and work/school administrator restrictions.
