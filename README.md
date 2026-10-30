# Placement Application Tracker — PostgreSQL + JDBC

A core Java console application for tracking job and internship applications.
Version 2 adds PostgreSQL persistence through JDBC and JUnit 5 tests while keeping
the existing console menu and an optional local-file mode. No Spring Boot yet.

## Implemented features

- Add applications with company, role, application date and deadline.
- View, find by ID, search company/role, update status and confirm deletion.
- Sort by deadline, filter by status and display a status summary.
- Validate fields/dates and reject duplicate company-role-date combinations.
- Persist records in PostgreSQL across application restarts.
- Use PreparedStatement parameters for user input and close JDBC resources automatically.
- Use PostgreSQL identity IDs rather than allocating IDs in application memory.
- Enforce required fields, valid statuses, date order and case-insensitive duplicate
  prevention in the database. Index deadlines and statuses for future SQL queries.
- Import the old file in one database transaction: all rows commit or none do.
- Retain the original file-storage option for learning and offline use.

## Tech stack

Java 17+, PostgreSQL, JDBC (pgJDBC 42.7.14), Maven and JUnit Jupiter 5.14.4.
JUnit and the driver are downloaded automatically by Maven. JDK 25 can compile
this Java 17-targeted project too. PostgreSQL 16+ is the recommended server target.

## 1. Extract this version separately

Keep your old project and its `data/applications.properties` file as a backup.
Extract this ZIP into a new folder and open it in VS Code or IntelliJ IDEA.
The source layout now follows Maven conventions. Do not paste the new files
inside the old `src/com/...` tree.

If you have edited your own project since the first ZIP, compare those changes
before replacing anything. This update is based on the Java project supplied in
this conversation; it does not modify your computer or GitHub repository.

## 2. Install Maven and PostgreSQL on Ubuntu

If already installed, skip the installation command:

```bash
sudo apt update
sudo apt install maven postgresql postgresql-contrib
sudo systemctl start postgresql
```

Use your existing Android Studio JDK in the terminal:

```bash
export JAVA_HOME="$HOME/Applications/android-studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -version
```

`JAVA_HOME` points to the JDK directory, not its `bin` subdirectory.
You can use another JDK 17+ instead. The launcher automatically uses the above
Android Studio JDK when JAVA_HOME is unset and that JDK is installed.

## 3. Create the database user and databases

Open the PostgreSQL shell from your normal Ubuntu account:

```bash
sudo -u postgres psql
```

Inside **psql**, execute the following one line at a time:

```sql
CREATE ROLE tracker_app LOGIN;
\password tracker_app
CREATE DATABASE placement_tracker OWNER tracker_app;
CREATE DATABASE placement_tracker_test OWNER tracker_app;
\q
```

The `\password` command prompts for your chosen password twice. Do not put your
real password into GitHub or a screenshot. `\q` returns to your Ubuntu terminal.
If a role/database already exists, inspect and reuse it rather than dropping it.
The application creates its table/indexes on startup; you do not manually run
`schema.sql`. This initialization does not delete records or reset the table.

## 4. Configure local credentials

From the extracted project folder:

```bash
cp .env.example .env
```

Open `.env` in your editor. Replace both password placeholders with the password
you chose. Leave the single quotes around passwords. Then load the file:

```bash
set -a
source .env
set +a
```

This is a shell environment file, not an automatically loaded Spring Boot file.
Load it again in a new terminal. Only source the local file you control.
The `.gitignore` excludes `.env` and local records. `.env.example` has placeholders only.

| Variable | Purpose |
| --- | --- |
| `DB_URL` | JDBC URL of the application's PostgreSQL database |
| `DB_USER` / `DB_PASSWORD` | Application database credentials |
| `DB_SCHEMA` | Existing schema; normally `public` |
| `DB_TEST_URL` | JDBC URL of a separate test database |
| `DB_TEST_USER` / `DB_TEST_PASSWORD` | Credentials allowed to create schemas in the test database |

The application fails clearly if configuration or the database is unavailable;
it does not silently fall back to file mode.

## 5. Run the application

```bash
bash run.sh
```

The first build needs internet to download Maven dependencies. When the menu opens,
choose 1 to add an application. Press Enter for today's application date, and use a
deadline on or after that date. Choose 0 to exit. Run again and choose 2 to confirm
that PostgreSQL retained the record.

Alternatively build once and run the packaged JAR:

```bash
mvn package
java -jar target/placement-application-tracker-2.0.0.jar
```

A prebuilt copy is also included for immediate use after loading credentials:

```bash
java -jar dist/placement-application-tracker-2.0.0.jar
```

To rebuild after editing code, use `mvn package` and run the JAR in `target/`.
The `dist/` copy is a convenience snapshot and does not update automatically.

The JAR includes the PostgreSQL driver. Each JDBC operation opens and closes its
own connection; this learning version does not have a connection pool.

### Preserve/import records from version 1

Keep a backup of your original file. With database credentials loaded:

```bash
bash run.sh --import-file /absolute/path/to/old-project/data/applications.properties
```

The source file is read only. PostgreSQL assigns NEW IDs; old file IDs are not
preserved. Company, role, dates and status are preserved. If any duplicate exists,
the entire import rolls back. Re-running the same import reports a duplicate and
makes no partial additions. Failed transactions may consume identity sequence
numbers: gaps in IDs are normal. Import is explicit; simply starting the app does
not import or remove old records.

To run without PostgreSQL, choose the file mode explicitly:

```bash
bash run.sh --file
```

File mode writes to this project's `data/applications.properties`; the old file is
not automatically copied. It supports only one running instance. Maven is still
needed to build this version.

## 6. Tests

### Local JUnit tests — no PostgreSQL required

```bash
mvn test
# or
bash run.sh --test
```

These cover validation, defaults, trimming, historical dates, duplicate rules,
search, sorting, immutable status changes and safe configuration. A regression
test also runs the original 24 file/console checks, including failed saves,
restart persistence, Unicode, deletion and invalid saved data.

### Integration tests — a REAL PostgreSQL server is required

Load `.env`, ensure `placement_tracker_test` exists, then run:

```bash
mvn -Pintegration verify
# or
bash run.sh --integration-test
```

This runs local tests plus 13 PostgreSQL integration tests. They create a unique
`tracker_test_<random>` schema for each test and clean up only that schema.
They do not truncate or reset your public application table. Use a dedicated test
database anyway. Missing test configuration causes a clear failure, not a silent skip.

Tests check:

- Generated IDs and persistence across new connections/repository instances.
- Status updates and deletes.
- Case-insensitive uniqueness and simultaneous duplicate requests.
- Different IDs for simultaneous distinct requests.
- SQL payloads treated as literal data; Unicode text round trips.
- Database CHECK constraints when Java validation is bypassed.
- Idempotent schema setup without deleting records.
- Successful imports and rollback when a later imported row is a duplicate.

JUnit reports are in `target/surefire-reports/`; database reports are in
`target/failsafe-reports/`. `mvn test` does NOT run the database suite.
The supplied GitHub Actions workflow provisions PostgreSQL and runs both suites.
See `VALIDATION.md` for what was actually executed while preparing this version.

## Project structure

| Location | Responsibility |
| --- | --- |
| `pom.xml` | Dependencies, compilation, JUnit and executable JAR packaging |
| `src/main/java/com/ashish/placementtracker/Main.java` | Choose storage, initialize schema, import or start menu |
| `.../config/DatabaseConfig.java` | Validate environment settings without logging secrets |
| `.../model/` | Immutable application object and status enum |
| `.../repository/ApplicationRepository.java` | Storage contract |
| `.../repository/JdbcConnectionFactory.java` | Open PostgreSQL connections |
| `.../repository/JdbcApplicationRepository.java` | Parameterized SQL CRUD and transactional import |
| `.../repository/FileApplicationRepository.java` | Retained file-storage implementation |
| `.../service/ApplicationService.java` | Business validation, search, sort and filtering |
| `.../ui/ConsoleMenu.java` | Console input/output and errors |
| `src/main/resources/db/schema.sql` | Table constraints, uniqueness and indexes |
| `src/test/java/` | Local tests and PostgreSQL integration suite |
| `.env.example` | Configuration template without real credentials |
| `docs/LEARNING_GUIDE.md` | JDBC explanation and interview preparation |

## Design and current limitations

The menu calls the service, which depends on the repository INTERFACE. Main
injects either the JDBC implementation or the file implementation. The DB
allocates IDs during INSERT and the repository maps returned rows to Java objects.
SQL values use placeholders, not string concatenation. Only fixed SQL and generated,
validated test schema identifiers are concatenated.

Search/filter/sort still load records into Java. Adding indexes does not make
those in-memory operations faster; moving these operations to parameterized SQL
and adding pagination is a future improvement. This app suits small personal datasets.

There is no login, frontend, encryption, connection pooling or optimistic locking.
Simultaneous updates to one record can overwrite each other (last writer wins).
Database duplicate protection is stronger than file mode, but it does not turn
this single-user console into a multi-user web application. Status corrections
are unrestricted. PostgreSQL lower() follows database collation rules; file-mode
Java equalsIgnoreCase may differ for unusual international text.

Startup SQL supports initial schema creation, not arbitrary future schema upgrades.
Use versioned migrations before evolving a deployed database schema. The table
has no database CHECK against the current date; future application dates are
rejected by the service, while the database enforces date ordering.

## Future improvements

- SQL-side search, sorting, pagination and full-field editing.
- Spring Boot REST APIs using the existing service/repository separation.
- Spring Security user accounts and per-user record ownership.
- Versioned database migrations, connection pooling and optimistic locking.
- A web frontend, deadline reminders, CSV export and deployment.

Only describe implemented features in your resume; authentication and Spring Boot
are not included in this version.
