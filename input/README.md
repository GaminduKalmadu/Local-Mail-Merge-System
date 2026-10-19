# Letter batch input

Create one directory for each letter type. Every letter-type directory must contain:

- `template`: exactly one `.docx` file.
- `dataset`: exactly one `.csv` or `.xlsx` file.

The dataset must include a nonblank `customerNumber` for each row. That value is the password required to open that row's generated PDF. Keep it out of the template and email body, and ensure recipients know it through a separate channel. For XLSX, format customer numbers as text to preserve leading zeros. Add an `email` column when Gmail sending is enabled.

The template and dataset filenames do not need to match. The application associates them through their common letter-type directory.

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
```

In the default immediate mode, run the application after copying the input files. It processes the dataset once and exits. Encrypted PDF letters and `validation-report.pdf` are generated under `output/<letter-type>/`. When email is enabled, messages are submitted during that run.

For a job that sends up to 100 rows each day, use the opt-in `LETTER_BATCH_MODE=daily` workflow described in the root `README.md`. Daily mode requires exactly one letter-type folder and stores its frozen input snapshot and progress under `output/<letter-type>/daily-job/`.

Older scheduled output is not deleted or reused. Set `LETTER_OUTPUT_DIR` to a different directory if `output/<letter-type>/` already contains scheduled delivery state.
