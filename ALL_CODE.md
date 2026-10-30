# Complete Version 2 source

See README.md for PostgreSQL setup and docs/LEARNING_GUIDE.md for explanation.

## `src/main/java/com/ashish/placementtracker/Main.java`

```java
package com.ashish.placementtracker;

import com.ashish.placementtracker.config.DatabaseConfig;
import com.ashish.placementtracker.repository.*;
import com.ashish.placementtracker.service.ApplicationService;
import com.ashish.placementtracker.ui.ConsoleMenu;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Scanner;

public final class Main {
    public static void main(String[] args) {
        boolean fileMode = args.length == 1 && args[0].equals("--file");
        boolean importing = args.length == 2 && args[0].equals("--import-file");
        if (args.length != 0 && !fileMode && !importing) {
            System.err.println("Usage: run.sh [--file | --import-file PATH]");
            System.exit(1);
        }
        try {
            ApplicationRepository repository;
            if (fileMode) {
                repository = new FileApplicationRepository(Path.of("data", "applications.properties"));
                System.out.println("Storage: local file (single instance only)");
            } else {
                JdbcApplicationRepository jdbc = new JdbcApplicationRepository(
                        new JdbcConnectionFactory(DatabaseConfig.fromEnvironment()));
                jdbc.initializeSchema();
                repository = jdbc;
                System.out.println("Storage: PostgreSQL via JDBC");
                if (importing) {
                    Path source = Path.of(args[1]);
                    if (!java.nio.file.Files.isRegularFile(source))
                        throw new IllegalArgumentException("Import file does not exist.");
                    int imported = jdbc.importAll(new FileApplicationRepository(source).findAll());
                    System.out.println("Imported " + imported + " records. PostgreSQL assigned new IDs. Source unchanged.");
                    return;
                }
            }
            try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
                new ConsoleMenu(new ApplicationService(repository), scanner).start();
            }
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("Cannot complete operation: " + ex.getMessage());
            System.exit(1);
        }
    }
}

```

## `src/main/java/com/ashish/placementtracker/config/DatabaseConfig.java`

```java
package com.ashish.placementtracker.config;

import java.util.Map;

/** No database passwords are hard-coded or printed. */
public record DatabaseConfig(String url, String username, String password, String schema) {
    public DatabaseConfig {
        if (url == null || !url.startsWith("jdbc:postgresql:"))
            throw new IllegalArgumentException("DB_URL must be a PostgreSQL JDBC URL.");
        if (username == null || username.isBlank() || password == null || password.isBlank())
            throw new IllegalArgumentException("Set DB_USER and DB_PASSWORD before using PostgreSQL.");
        if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Invalid database schema name.");
    }
    public static DatabaseConfig fromEnvironment() { return from(System.getenv()); }
    public static DatabaseConfig from(Map<String, String> env) {
        return new DatabaseConfig(env.getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/placement_tracker"),
                env.getOrDefault("DB_USER", "tracker_app"), env.get("DB_PASSWORD"),
                env.getOrDefault("DB_SCHEMA", "public"));
    }
    @Override public String toString() { return "DatabaseConfig[credentials redacted]"; }
}

```

## `src/main/java/com/ashish/placementtracker/model/ApplicationStatus.java`

```java
package com.ashish.placementtracker.model;

public enum ApplicationStatus {
    APPLIED, ASSESSMENT, INTERVIEW, OFFERED, REJECTED
}

```

## `src/main/java/com/ashish/placementtracker/model/PlacementApplication.java`

```java
package com.ashish.placementtracker.model;

import java.time.LocalDate;
import java.util.Objects;

/** Immutable: an update creates a new object instead of modifying stored state. */
public final class PlacementApplication {
    private final int id;
    private final String companyName;
    private final String role;
    private final LocalDate applicationDate;
    private final LocalDate deadline;
    private final ApplicationStatus status;

    public PlacementApplication(int id, String companyName, String role,
            LocalDate applicationDate, LocalDate deadline, ApplicationStatus status) {
        if (id <= 0) throw new IllegalArgumentException("ID must be positive.");
        this.id = id;
        this.companyName = requiredText(companyName, "Company");
        this.role = requiredText(role, "Role");
        this.applicationDate = Objects.requireNonNull(applicationDate, "Application date is required.");
        this.deadline = Objects.requireNonNull(deadline, "Deadline is required.");
        this.status = Objects.requireNonNull(status, "Status is required.");
        if (deadline.isBefore(applicationDate)) {
            throw new IllegalArgumentException("Deadline cannot be before application date.");
        }
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be empty.");
        }
        String text = value.strip();
        if (text.length() > 150) throw new IllegalArgumentException(field + " must be at most 150 characters.");
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " cannot contain control characters.");
        }
        return text;
    }

    public int getId() { return id; }
    public String getCompanyName() { return companyName; }
    public String getRole() { return role; }
    public LocalDate getApplicationDate() { return applicationDate; }
    public LocalDate getDeadline() { return deadline; }
    public ApplicationStatus getStatus() { return status; }

    public PlacementApplication withStatus(ApplicationStatus newStatus) {
        return new PlacementApplication(id, companyName, role, applicationDate, deadline, newStatus);
    }
}

```

## `src/main/java/com/ashish/placementtracker/repository/ApplicationRepository.java`

```java
package com.ashish.placementtracker.repository;

import com.ashish.placementtracker.model.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ApplicationRepository {
    PlacementApplication create(String company, String role, LocalDate appliedOn,
            LocalDate deadline) throws IOException;
    List<PlacementApplication> findAll() throws IOException;
    Optional<PlacementApplication> findById(int id) throws IOException;
    void save(PlacementApplication application) throws IOException;
    void deleteById(int id) throws IOException;
}

```

## `src/main/java/com/ashish/placementtracker/repository/FileApplicationRepository.java`

```java
package com.ashish.placementtracker.repository;

import com.ashish.placementtracker.model.ApplicationStatus;
import com.ashish.placementtracker.model.PlacementApplication;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** Single-process file store. Never launch two instances against the same file. */
public final class FileApplicationRepository implements ApplicationRepository {
    private final Path file;
    private Map<Integer, PlacementApplication> applications = new LinkedHashMap<>();
    private int nextId = 1;

    public FileApplicationRepository(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        load();
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        try {
            if (!"1".equals(required(properties, "formatVersion"))) {
                throw new IllegalArgumentException("Unsupported file format.");
            }
            int count = Integer.parseInt(required(properties, "count"));
            int loadedNextId = Integer.parseInt(required(properties, "nextId"));
            if (count < 0 || loadedNextId < 1) throw new IllegalArgumentException("Invalid file metadata.");
            Map<Integer, PlacementApplication> loaded = new LinkedHashMap<>();
            int highestId = 0;
            for (int i = 0; i < count; i++) {
                String prefix = "application." + i + ".";
                int id = Integer.parseInt(required(properties, prefix + "id"));
                PlacementApplication application = new PlacementApplication(id,
                        required(properties, prefix + "company"), required(properties, prefix + "role"),
                        LocalDate.parse(required(properties, prefix + "appliedOn")),
                        LocalDate.parse(required(properties, prefix + "deadline")),
                        ApplicationStatus.valueOf(required(properties, prefix + "status")));
                if (loaded.put(id, application) != null) throw new IllegalArgumentException("Duplicate ID.");
                highestId = Math.max(highestId, id);
            }
            if (loadedNextId <= highestId) throw new IllegalArgumentException("Invalid next ID.");
            applications = loaded;
            nextId = loadedNextId;
        } catch (RuntimeException ex) {
            throw new IOException("Saved data is invalid. Back up and inspect " + file
                    + ". It has not been overwritten.", ex);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing field: " + key);
        return value;
    }

    @Override public PlacementApplication create(String company, String role,
            LocalDate appliedOn, LocalDate deadline) throws IOException {
        PlacementApplication application = new PlacementApplication(nextId, company, role,
                appliedOn, deadline, ApplicationStatus.APPLIED);
        boolean duplicate = applications.values().stream().anyMatch(existing ->
                existing.getCompanyName().equalsIgnoreCase(application.getCompanyName())
                && existing.getRole().equalsIgnoreCase(application.getRole())
                && existing.getApplicationDate().equals(appliedOn));
        if (duplicate) throw new IllegalArgumentException("This company, role and date already exist.");
        save(application);
        return application;
    }
    @Override public List<PlacementApplication> findAll() {
        return new ArrayList<>(applications.values());
    }
    @Override public Optional<PlacementApplication> findById(int id) {
        return Optional.ofNullable(applications.get(id));
    }

    @Override public void save(PlacementApplication application) throws IOException {
        Map<Integer, PlacementApplication> updated = new LinkedHashMap<>(applications);
        updated.put(application.getId(), application);
        int updatedNextId = Math.max(nextId, Math.addExact(application.getId(), 1));
        persist(updated, updatedNextId);
        // Change in-memory state only after the file was successfully saved.
        applications = updated;
        nextId = updatedNextId;
    }

    @Override public void deleteById(int id) throws IOException {
        if (!applications.containsKey(id)) throw new IllegalArgumentException("Application ID not found.");
        Map<Integer, PlacementApplication> updated = new LinkedHashMap<>(applications);
        updated.remove(id);
        persist(updated, nextId);
        applications = updated;
    }

    private void persist(Map<Integer, PlacementApplication> records, int updatedNextId) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("formatVersion", "1");
        properties.setProperty("count", Integer.toString(records.size()));
        properties.setProperty("nextId", Integer.toString(updatedNextId));
        int index = 0;
        for (PlacementApplication application : records.values()) {
            String prefix = "application." + index++ + ".";
            properties.setProperty(prefix + "id", Integer.toString(application.getId()));
            properties.setProperty(prefix + "company", application.getCompanyName());
            properties.setProperty(prefix + "role", application.getRole());
            properties.setProperty(prefix + "appliedOn", application.getApplicationDate().toString());
            properties.setProperty(prefix + "deadline", application.getDeadline().toString());
            properties.setProperty(prefix + "status", application.getStatus().name());
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "applications-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Placement Application Tracker - do not edit while app is running");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

```

## `src/main/java/com/ashish/placementtracker/repository/JdbcApplicationRepository.java`

```java
package com.ashish.placementtracker.repository;

import com.ashish.placementtracker.model.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcApplicationRepository implements ApplicationRepository {
    private static final String INSERT = "INSERT INTO applications "
            + "(company_name, role, application_date, deadline, status) VALUES (?, ?, ?, ?, ?) RETURNING *";
    private final JdbcConnectionFactory connections;
    public JdbcApplicationRepository(JdbcConnectionFactory connections) { this.connections = connections; }

    /** Idempotent initial schema creation, not a general schema migration framework. */
    public void initializeSchema() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/db/schema.sql")) {
            if (stream == null) throw new IOException("Missing database schema resource.");
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection connection = connections.open()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                    connection.commit();
                } catch (SQLException ex) { rollback(connection, ex); throw ex; }
            }
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public PlacementApplication create(String company, String role, LocalDate appliedOn,
            LocalDate deadline) throws IOException {
        PlacementApplication candidate = new PlacementApplication(1, company, role, appliedOn,
                deadline, ApplicationStatus.APPLIED);
        try (Connection connection = connections.open()) { return insert(connection, candidate); }
        catch (SQLException ex) { throw failure(ex); }
    }

    private PlacementApplication insert(Connection connection, PlacementApplication application) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, application.getCompanyName());
            statement.setString(2, application.getRole());
            statement.setObject(3, application.getApplicationDate());
            statement.setObject(4, application.getDeadline());
            statement.setString(5, application.getStatus().name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Insert returned no row.");
                return map(result);
            }
        }
    }

    @Override public List<PlacementApplication> findAll() throws IOException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM applications ORDER BY id");
             ResultSet result = statement.executeQuery()) {
            List<PlacementApplication> applications = new ArrayList<>();
            while (result.next()) applications.add(map(result));
            return applications;
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public Optional<PlacementApplication> findById(int id) throws IOException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM applications WHERE id = ?")) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public void save(PlacementApplication application) throws IOException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("UPDATE applications SET company_name = ?, "
                     + "role = ?, application_date = ?, deadline = ?, status = ? WHERE id = ?")) {
            statement.setString(1, application.getCompanyName());
            statement.setString(2, application.getRole());
            statement.setObject(3, application.getApplicationDate());
            statement.setObject(4, application.getDeadline());
            statement.setString(5, application.getStatus().name());
            statement.setInt(6, application.getId());
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Application ID not found.");
        } catch (SQLException ex) { throw failure(ex); }
    }

    @Override public void deleteById(int id) throws IOException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM applications WHERE id = ?")) {
            statement.setInt(1, id);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Application ID not found.");
        } catch (SQLException ex) { throw failure(ex); }
    }

    /** All-or-nothing import. Source file and its IDs remain unchanged; DB allocates new IDs. */
    public int importAll(List<PlacementApplication> applications) throws IOException {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                for (PlacementApplication application : applications) insert(connection, application);
                connection.commit();
                return applications.size();
            } catch (SQLException ex) { rollback(connection, ex); throw ex; }
        } catch (SQLException ex) { throw failure(ex); }
    }

    private static void rollback(Connection connection, SQLException original) {
        try { connection.rollback(); } catch (SQLException ex) { original.addSuppressed(ex); }
    }
    private static PlacementApplication map(ResultSet result) throws SQLException {
        return new PlacementApplication(result.getInt("id"), result.getString("company_name"),
                result.getString("role"), result.getObject("application_date", LocalDate.class),
                result.getObject("deadline", LocalDate.class), ApplicationStatus.valueOf(result.getString("status")));
    }
    private static IOException failure(SQLException ex) {
        if ("23505".equals(ex.getSQLState()))
            throw new IllegalArgumentException("This company, role and application date already exist.");
        return new IOException("Database operation failed (SQLState " + ex.getSQLState()
                + "). Check PostgreSQL, credentials and permissions.", ex);
    }
}

```

## `src/main/java/com/ashish/placementtracker/repository/JdbcConnectionFactory.java`

```java
package com.ashish.placementtracker.repository;

import com.ashish.placementtracker.config.DatabaseConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class JdbcConnectionFactory {
    private final DatabaseConfig config;
    public JdbcConnectionFactory(DatabaseConfig config) { this.config = config; }
    public Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "15");
        Connection connection = DriverManager.getConnection(config.url(), properties);
        try { connection.setSchema(config.schema()); return connection; }
        catch (SQLException ex) { connection.close(); throw ex; }
    }
}

```

## `src/main/java/com/ashish/placementtracker/service/ApplicationService.java`

```java
package com.ashish.placementtracker.service;

import com.ashish.placementtracker.model.ApplicationStatus;
import com.ashish.placementtracker.model.PlacementApplication;
import com.ashish.placementtracker.repository.ApplicationRepository;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ApplicationService {
    private final ApplicationRepository repository;

    public ApplicationService(ApplicationRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public PlacementApplication add(String company, String role, LocalDate appliedOn,
            LocalDate deadline) throws IOException {
        if (appliedOn == null || appliedOn.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Application date must be today or earlier.");
        }
        // Construct once to validate and normalize fields before calling storage.
        PlacementApplication validated = new PlacementApplication(1, company, role,
                appliedOn, deadline, ApplicationStatus.APPLIED);
        return repository.create(validated.getCompanyName(), validated.getRole(), appliedOn, deadline);
    }

    public List<PlacementApplication> findAll() throws IOException { return repository.findAll(); }

    public PlacementApplication findById(int id) throws IOException {
        return repository.findById(id).orElseThrow(() ->
                new IllegalArgumentException("No application exists with ID " + id + "."));
    }

    public List<PlacementApplication> search(String query) throws IOException {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Search cannot be empty.");
        String keyword = query.strip().toLowerCase(Locale.ROOT);
        return repository.findAll().stream().filter(application ->
                application.getCompanyName().toLowerCase(Locale.ROOT).contains(keyword)
                || application.getRole().toLowerCase(Locale.ROOT).contains(keyword)).toList();
    }

    public void updateStatus(int id, ApplicationStatus status) throws IOException {
        repository.save(findById(id).withStatus(status));
    }

    public void delete(int id) throws IOException {
        findById(id);
        repository.deleteById(id);
    }

    public List<PlacementApplication> sortByDeadline() throws IOException {
        return repository.findAll().stream().sorted(Comparator
                .comparing(PlacementApplication::getDeadline)
                .thenComparingInt(PlacementApplication::getId)).toList();
    }

    public List<PlacementApplication> filterByStatus(ApplicationStatus status) throws IOException {
        return repository.findAll().stream()
                .filter(application -> application.getStatus() == status).toList();
    }
}

```

## `src/main/java/com/ashish/placementtracker/ui/ConsoleMenu.java`

```java
package com.ashish.placementtracker.ui;

import com.ashish.placementtracker.model.ApplicationStatus;
import com.ashish.placementtracker.model.PlacementApplication;
import com.ashish.placementtracker.service.ApplicationService;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Scanner;

public final class ConsoleMenu {
    private final ApplicationService service;
    private final Scanner scanner;

    public ConsoleMenu(ApplicationService service, Scanner scanner) {
        this.service = service;
        this.scanner = scanner;
    }

    public void start() {
        while (true) {
            System.out.println("\nPLACEMENT APPLICATION TRACKER");
            System.out.println("1. Add application");
            System.out.println("2. View all applications");
            System.out.println("3. Search company or role");
            System.out.println("4. Find application by ID");
            System.out.println("5. Update application status");
            System.out.println("6. Delete application");
            System.out.println("7. Sort by deadline (earliest first)");
            System.out.println("8. Filter by status");
            System.out.println("9. Show status summary");
            System.out.println("0. Exit");
            try {
                int choice = readNumber("Choose an option: ");
                switch (choice) {
                    case 1 -> addApplication();
                    case 2 -> display(service.findAll());
                    case 3 -> display(service.search(readLine("Company or role keyword: ")));
                    case 4 -> display(List.of(service.findById(readNumber("Application ID: "))));
                    case 5 -> updateStatus();
                    case 6 -> deleteApplication();
                    case 7 -> display(service.sortByDeadline());
                    case 8 -> display(service.filterByStatus(readStatus()));
                    case 9 -> showSummary();
                    case 0 -> {
                        System.out.println("Goodbye! Changes were saved automatically.");
                        return;
                    }
                    default -> System.out.println("Choose a menu option from 0 to 9.");
                }
            } catch (EndOfInput ex) {
                System.out.println("\nInput closed. Exiting; completed changes are already saved.");
                return;
            } catch (IllegalArgumentException ex) {
                System.out.println("Input error: " + ex.getMessage());
            } catch (IOException ex) {
                System.out.println("Storage error: " + ex.getMessage());
                System.out.println("Change was not confirmed. Check file access and free disk space.");
            }
        }
    }

    private void addApplication() throws IOException {
        String company = readLine("Company name: ");
        String role = readLine("Role: ");
        LocalDate appliedOn = readDate("Application date (YYYY-MM-DD; Enter = today): ", true);
        LocalDate deadline = readDate("Application deadline (YYYY-MM-DD): ", false);
        PlacementApplication created = service.add(company, role, appliedOn, deadline);
        System.out.println("Application saved. ID: " + created.getId() + ", status: APPLIED");
    }

    private void updateStatus() throws IOException {
        int id = readNumber("Application ID: ");
        display(List.of(service.findById(id)));
        service.updateStatus(id, readStatus());
        System.out.println("Status updated and saved.");
    }

    private void deleteApplication() throws IOException {
        int id = readNumber("Application ID: ");
        display(List.of(service.findById(id)));
        if (readLine("Delete this application? Type yes to confirm: ").equalsIgnoreCase("yes")) {
            service.delete(id);
            System.out.println("Application deleted and saved.");
        } else {
            System.out.println("Deletion cancelled.");
        }
    }

    private void showSummary() throws IOException {
        List<PlacementApplication> applications = service.findAll();
        System.out.println("Total applications: " + applications.size());
        for (ApplicationStatus status : ApplicationStatus.values()) {
            long count = applications.stream().filter(application -> application.getStatus() == status).count();
            System.out.println(status + ": " + count);
        }
    }

    private void display(List<PlacementApplication> applications) {
        if (applications.isEmpty()) {
            System.out.println("No matching applications found.");
            return;
        }
        for (PlacementApplication application : applications) {
            System.out.println("\nID: " + application.getId());
            System.out.println("Company: " + application.getCompanyName());
            System.out.println("Role: " + application.getRole());
            System.out.println("Applied on: " + application.getApplicationDate());
            System.out.println("Deadline: " + application.getDeadline());
            System.out.println("Status: " + application.getStatus());
        }
    }

    private ApplicationStatus readStatus() {
        ApplicationStatus[] statuses = ApplicationStatus.values();
        for (int i = 0; i < statuses.length; i++) {
            System.out.println((i + 1) + ". " + statuses[i]);
        }
        while (true) {
            int choice = readNumber("Select status (1-" + statuses.length + "): ");
            if (choice >= 1 && choice <= statuses.length) return statuses[choice - 1];
            System.out.println("Please select a status from the list.");
        }
    }

    private LocalDate readDate(String prompt, boolean defaultToday) {
        while (true) {
            String input = readLine(prompt);
            if (defaultToday && input.isEmpty()) return LocalDate.now();
            try {
                return LocalDate.parse(input);
            } catch (DateTimeParseException ex) {
                System.out.println("Enter a valid date such as 2026-11-05.");
            }
        }
    }

    private int readNumber(String prompt) {
        while (true) {
            try {
                return Integer.parseInt(readLine(prompt));
            } catch (NumberFormatException ex) {
                System.out.println("Enter a whole number, for example 1.");
            }
        }
    }

    private String readLine(String prompt) {
        System.out.print(prompt);
        if (!scanner.hasNextLine()) throw new EndOfInput();
        return scanner.nextLine().strip();
    }

    private static final class EndOfInput extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}

```

## `src/test/java/com/ashish/placementtracker/FileRegressionTest.java`

```java
package com.ashish.placementtracker;
import org.junit.jupiter.api.Test;
class FileRegressionTest {
    @Test void existingFileFeaturesStillWork() throws Exception { TrackerTests.main(new String[0]); }
}

```

## `src/test/java/com/ashish/placementtracker/PostgresRepositoryIT.java`

```java
package com.ashish.placementtracker;

import com.ashish.placementtracker.config.DatabaseConfig;
import com.ashish.placementtracker.model.*;
import com.ashish.placementtracker.repository.*;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL tests. Each test owns a fresh random schema; public data is never cleared. */
class PostgresRepositoryIT {
    DatabaseConfig config;
    JdbcConnectionFactory factory;
    JdbcApplicationRepository repository;
    String schema;
    final LocalDate today = LocalDate.now();

    @BeforeEach void setUp() throws Exception {
        Map<String, String> env = System.getenv();
        String url = env.get("DB_TEST_URL");
        String user = env.get("DB_TEST_USER");
        String password = env.get("DB_TEST_PASSWORD");
        assertNotNull(url, "Set DB_TEST_URL to a separate PostgreSQL test database.");
        assertNotNull(user, "Set DB_TEST_USER.");
        assertNotNull(password, "Set DB_TEST_PASSWORD.");
        schema = "tracker_test_" + UUID.randomUUID().toString().replace("-", "");
        config = new DatabaseConfig(url, user, password, schema);
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        factory = new JdbcConnectionFactory(config);
        repository = new JdbcApplicationRepository(factory);
        repository.initializeSchema();
    }

    @AfterEach void cleanUp() throws Exception {
        if (config == null) return;
        try (Connection connection = DriverManager.getConnection(config.url(), config.username(), config.password());
             Statement statement = connection.createStatement()) {
            // Only the generated schema for this test can be dropped.
            if (schema == null || !schema.matches("tracker_test_[0-9a-f]{32}"))
                throw new IllegalStateException("Refusing to drop an unexpected schema.");
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void createsAndReadsAcrossNewRepositoryInstances() throws Exception {
        var created = repository.create("Company", "Java Developer", today, today.plusDays(3));
        assertTrue(created.getId() > 0);
        var loaded = new JdbcApplicationRepository(factory).findById(created.getId()).orElseThrow();
        assertEquals("Company", loaded.getCompanyName());
        assertEquals(today, loaded.getApplicationDate());
        assertEquals(today.plusDays(3), loaded.getDeadline());
        assertEquals(ApplicationStatus.APPLIED, loaded.getStatus());
    }
    @Test void updatesStatusAndDeletesPersistently() throws Exception {
        var created = repository.create("Company", "Java", today, today);
        repository.save(created.withStatus(ApplicationStatus.INTERVIEW));
        assertEquals(ApplicationStatus.INTERVIEW, repository.findById(created.getId()).orElseThrow().getStatus());
        repository.deleteById(created.getId());
        assertTrue(new JdbcApplicationRepository(factory).findById(created.getId()).isEmpty());
    }
    @Test void duplicateCheckIsCaseInsensitiveAndTrimsWhitespace() throws Exception {
        repository.create("Company", "Java", today, today);
        assertThrows(IllegalArgumentException.class, () -> repository.create(" COMPANY ", "java", today, today));
        assertEquals(1, repository.findAll().size());
        assertNotNull(repository.create("Company", "Java", today.minusDays(1), today));
    }
    @Test void sqlPayloadIsStoredAsDataAndDoesNotExecute() throws Exception {
        String payload = "O'Reilly'); DROP TABLE applications; --";
        var application = repository.create(payload, "Java", today, today);
        assertEquals(payload, repository.findById(application.getId()).orElseThrow().getCompanyName());
        assertNotNull(repository.create("Still exists", "Role", today, today));
        assertEquals(2, repository.findAll().size());
    }
    @Test void preservesUnicodeAndSpecialCharacters() throws Exception {
        var application = repository.create("भारत = A&B : Labs", "Développeur Java", today, today);
        assertEquals(application.getCompanyName(), repository.findById(application.getId()).orElseThrow().getCompanyName());
    }
    @Test void schemaInitializationIsRepeatableWithoutDeletingRecords() throws Exception {
        repository.create("Company", "Role", today, today);
        repository.initializeSchema();
        assertEquals(1, repository.findAll().size());
    }
    @Test void missingUpdateAndDeleteDoNotInventRecords() {
        var absent = new PlacementApplication(999, "Missing", "Role", today, today, ApplicationStatus.APPLIED);
        assertThrows(IllegalArgumentException.class, () -> repository.save(absent));
        assertThrows(IllegalArgumentException.class, () -> repository.deleteById(999));
    }
    @Test void databaseRejectsInvalidStatusEvenWhenJavaIsBypassed() throws Exception {
        SQLException ex = assertThrows(SQLException.class, () -> insertInvalid("UNKNOWN", today));
        assertEquals("23514", ex.getSQLState());
        assertTrue(repository.findAll().isEmpty());
    }
    @Test void databaseRejectsInvalidDateOrderEvenWhenJavaIsBypassed() throws Exception {
        SQLException ex = assertThrows(SQLException.class, () -> insertInvalid("APPLIED", today.minusDays(1)));
        assertEquals("23514", ex.getSQLState());
        assertTrue(repository.findAll().isEmpty());
    }
    private void insertInvalid(String status, LocalDate deadline) throws SQLException {
        try (Connection connection = factory.open();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO applications "
                     + "(company_name, role, application_date, deadline, status) VALUES ('Company', 'Role', ?, ?, ?)")) {
            statement.setObject(1, today); statement.setObject(2, deadline); statement.setString(3, status);
            statement.executeUpdate();
        }
    }
    @Test void importPreservesStatusesAndCommitsAllRows() throws Exception {
        int count = repository.importAll(List.of(
                new PlacementApplication(40, "First", "Role", today, today, ApplicationStatus.INTERVIEW),
                new PlacementApplication(90, "Second", "Role", today, today, ApplicationStatus.OFFERED)));
        assertEquals(2, count);
        assertEquals(List.of(ApplicationStatus.INTERVIEW, ApplicationStatus.OFFERED),
                repository.findAll().stream().map(PlacementApplication::getStatus).toList());
    }
    @Test void duplicateDuringImportRollsBackEarlierRows() throws Exception {
        repository.create("Existing", "Role", today, today);
        assertThrows(IllegalArgumentException.class, () -> repository.importAll(List.of(
                new PlacementApplication(50, "Would be partial", "Role", today, today, ApplicationStatus.APPLIED),
                new PlacementApplication(51, "existing", "ROLE", today, today, ApplicationStatus.APPLIED))));
        assertEquals(1, repository.findAll().size());
        assertEquals("Existing", repository.findAll().get(0).getCompanyName());
    }
    @Test @Timeout(20) void concurrentDuplicatesCreateOnlyOneApplication() throws Exception {
        List<Boolean> results = concurrent(() -> {
            try { repository.create("Same", "Role", today, today); return true; }
            catch (IllegalArgumentException ex) { return false; }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, repository.findAll().size());
    }
    @Test @Timeout(20) void concurrentDifferentApplicationsReceiveDifferentIds() throws Exception {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        List<Integer> ids = concurrent(() -> repository.create("Company " + counter.incrementAndGet(),
                "Role", today, today).getId());
        assertEquals(2, new HashSet<>(ids).size());
        assertEquals(2, repository.findAll().size());
    }
    private <T> List<T> concurrent(Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<T> gated = () -> { ready.countDown(); start.await(); return action.call(); };
        try {
            Future<T> first = executor.submit(gated);
            Future<T> second = executor.submit(gated);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}

```

## `src/test/java/com/ashish/placementtracker/ServiceTest.java`

```java
package com.ashish.placementtracker;

import com.ashish.placementtracker.config.DatabaseConfig;
import com.ashish.placementtracker.model.*;
import com.ashish.placementtracker.repository.FileApplicationRepository;
import com.ashish.placementtracker.service.ApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ServiceTest {
    @TempDir Path directory;
    ApplicationService service;
    final LocalDate today = LocalDate.now();
    @BeforeEach void setUp() throws Exception {
        service = new ApplicationService(new FileApplicationRepository(directory.resolve("records.properties")));
    }
    @Test void defaultsToAppliedAndTrimsFields() throws Exception {
        var app = service.add(" Company ", " Java ", today, today);
        assertEquals("Company", app.getCompanyName());
        assertEquals("Java", app.getRole());
        assertEquals(ApplicationStatus.APPLIED, app.getStatus());
    }
    @Test void rejectsDeadlineBeforeApplication() {
        assertThrows(IllegalArgumentException.class, () -> service.add("Company", "Role", today, today.minusDays(1)));
    }
    @Test void rejectsFutureApplicationDate() {
        assertThrows(IllegalArgumentException.class, () -> service.add("Company", "Role", today.plusDays(1), today.plusDays(2)));
    }
    @Test void allowsHistoricalApplications() throws Exception {
        assertNotNull(service.add("Company", "Role", today.minusYears(1), today.minusMonths(11)));
    }
    @Test void rejectsDuplicatesButAllowsDifferentDates() throws Exception {
        service.add("Company", "Role", today, today);
        assertThrows(IllegalArgumentException.class, () -> service.add(" company ", "ROLE", today, today));
        assertNotNull(service.add("Company", "Role", today.minusDays(1), today));
    }
    @Test void emptySearchAndMissingIdHaveUsefulErrors() {
        assertThrows(IllegalArgumentException.class, () -> service.search(" "));
        assertThrows(IllegalArgumentException.class, () -> service.findById(99));
    }
    @Test void rejectsBlankAndOverlongFields() {
        assertThrows(IllegalArgumentException.class, () -> service.add(" ", "Role", today, today));
        assertThrows(IllegalArgumentException.class, () -> service.add("Company", "x".repeat(151), today, today));
    }
    @Test void rejectsControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> service.add("Company\nOther", "Role", today, today));
    }
    @Test void sortsDatesRatherThanDateStrings() throws Exception {
        service.add("Later", "Role", today.minusYears(1), today.plusDays(5));
        service.add("Earlier", "Role", today.minusYears(1), today.minusDays(2));
        assertEquals("Earlier", service.sortByDeadline().get(0).getCompanyName());
    }
    @Test void updatesAndFiltersStatus() throws Exception {
        var app = service.add("Company", "Role", today, today);
        service.updateStatus(app.getId(), ApplicationStatus.OFFERED);
        assertEquals(1, service.filterByStatus(ApplicationStatus.OFFERED).size());
        assertEquals(ApplicationStatus.APPLIED, app.getStatus());
    }
    @Test void missingPasswordFailsBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseConfig.from(Map.of()));
    }
    @Test void rejectsInvalidSchemaAndRedactsCredentials() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConfig("jdbc:postgresql://localhost/x", "u", "p", "public;DROP"));
        assertFalse(new DatabaseConfig("jdbc:postgresql://localhost/x", "u", "secret", "public").toString().contains("secret"));
    }
}

```

## `src/test/java/com/ashish/placementtracker/TrackerTests.java`

```java
package com.ashish.placementtracker;

import com.ashish.placementtracker.model.ApplicationStatus;
import com.ashish.placementtracker.repository.FileApplicationRepository;
import com.ashish.placementtracker.service.ApplicationService;
import com.ashish.placementtracker.ui.ConsoleMenu;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Scanner;

/** Dependency-free regression checks: run with bash run.sh --test. */
public final class TrackerTests {
    private static int checks;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
    @FunctionalInterface private interface CheckedAction { void run() throws IOException; }
    private static void invalid(CheckedAction action, String name) throws IOException {
        try { action.run(); } catch (IllegalArgumentException ex) { checks++; return; }
        throw new AssertionError(name);
    }
    private static void invalidAdd(ApplicationService service, String company, String role,
            LocalDate date, LocalDate deadline, String name) throws IOException {
        try { service.add(company, role, date, deadline); }
        catch (IllegalArgumentException ex) { checks++; return; }
        throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("placement-tracker-tests-");
        try {
            Path file = directory.resolve("applications.properties");
            LocalDate today = LocalDate.now();
            ApplicationService service = new ApplicationService(new FileApplicationRepository(file));
            check(service.findAll().isEmpty(), "New store is empty");
            var first = service.add("  A&B = Labs : भारत  ", "Java Developer", today, today.plusDays(5));
            var second = service.add("Beta", "Backend Intern", today.minusDays(2), today.plusDays(1));
            check(first.getId() == 1 && second.getId() == 2, "Unique IDs");
            check(service.search("labs").size() == 1 && service.search("JAVA").size() == 1,
                    "Case-insensitive company and role search");
            check(service.sortByDeadline().get(0).getId() == second.getId(), "Chronological sorting");
            invalidAdd(service, "a&b = labs : भारत", "java developer", today, today.plusDays(1), "Duplicate rejected");
            invalidAdd(service, " ", "Role", today, today, "Blank company rejected");
            invalidAdd(service, "Gamma", " ", today, today, "Blank role rejected");
            invalidAdd(service, "Gamma", "Role", today, today.minusDays(1), "Invalid date order rejected");
            invalidAdd(service, "Gamma", "Role", today.plusDays(1), today.plusDays(2), "Future application rejected");
            invalid(() -> service.findById(999), "Missing ID rejected");
            invalid(() -> service.search(" "), "Empty search rejected");
            service.updateStatus(first.getId(), ApplicationStatus.INTERVIEW);
            check(first.getStatus() == ApplicationStatus.APPLIED, "Old object remains immutable");
            check(service.filterByStatus(ApplicationStatus.INTERVIEW).size() == 1, "Status filter");
            ApplicationService reloaded = new ApplicationService(new FileApplicationRepository(file));
            check(reloaded.findAll().size() == 2, "Records survive restart");
            check(reloaded.findById(1).getStatus() == ApplicationStatus.INTERVIEW, "Update persisted");
            check(reloaded.findById(1).getCompanyName().equals("A&B = Labs : भारत"), "Unicode and delimiters preserved");
            reloaded.delete(2);
            reloaded = new ApplicationService(new FileApplicationRepository(file));
            check(reloaded.findAll().size() == 1, "Deletion persisted");
            check(reloaded.add("Gamma", "SDE", today, today).getId() == 3, "Deleted IDs not reused after restart");

            // Console checks use isolated storage and scripted input, never the user's data.
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            PrintStream original = System.out;
            try (PrintStream output = new PrintStream(capture, true, StandardCharsets.UTF_8)) {
                System.setOut(output);
                String input = "abc\n1\nConsole Company\nSDE\nnot-a-date\n\n" + today
                        + "\n5\n1\n3\n3\nconsole\n7\n8\n3\n9\n6\n1\nno\n6\n1\nyes\n2\n0\n";
                ApplicationService consoleService = new ApplicationService(
                        new FileApplicationRepository(directory.resolve("console.properties")));
                new ConsoleMenu(consoleService, new Scanner(input)).start();
                check(consoleService.findAll().isEmpty(), "Console add, update, cancel delete, confirm delete");
                new ConsoleMenu(consoleService, new Scanner("")).start();
            } finally { System.setOut(original); }
            String consoleOutput = capture.toString(StandardCharsets.UTF_8);
            check(consoleOutput.contains("Enter a whole number") && consoleOutput.contains("Enter a valid date"),
                    "Invalid console input recovers");
            check(consoleOutput.contains("Input closed"), "End of input exits cleanly");

            Path blocked = directory.resolve("blocked");
            ApplicationService blockedService = new ApplicationService(
                    new FileApplicationRepository(blocked.resolve("data.properties")));
            Files.writeString(blocked, "A file prevents creating the directory");
            try {
                blockedService.add("Failure", "Role", today, today);
                throw new AssertionError("Expected storage error");
            } catch (IOException ex) {
                check(blockedService.findAll().isEmpty(), "Failed write does not commit in-memory state");
            }
            Files.delete(blocked);
            check(blockedService.add("Retry", "Role", today, today).getId() == 1,
                    "Failed write does not consume ID");

            Files.writeString(file, "formatVersion=1\ncount=1\nnextId=2\n");
            String corrupted = Files.readString(file);
            try {
                new FileApplicationRepository(file);
                throw new AssertionError("Invalid data should fail startup");
            } catch (IOException ex) {
                check(Files.readString(file).equals(corrupted), "Corrupt file is not silently overwritten");
            }
            System.out.println("PASS: " + checks + " checks. Tests used temporary storage only.");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

```

## `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.ashish</groupId>
    <artifactId>placement-application-tracker</artifactId>
    <version>2.0.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.14.4</junit.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><version>42.7.14</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version><scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.1</version>
                <executions><execution><phase>package</phase><goals><goal>shade</goal></goals>
                    <configuration>
                        <createDependencyReducedPom>false</createDependencyReducedPom>
                        <filters><filter><artifact>*:*</artifact><excludes>
                            <exclude>META-INF/*.SF</exclude><exclude>META-INF/*.DSA</exclude><exclude>META-INF/*.RSA</exclude>
                        </excludes></filter></filters>
                        <transformers>
                            <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                <mainClass>com.ashish.placementtracker.Main</mainClass>
                            </transformer>
                        </transformers>
                    </configuration>
                </execution></executions>
            </plugin>
        </plugins>
    </build>
    <profiles>
        <profile><id>integration</id><build><plugins><plugin>
            <groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>3.5.4</version>
            <executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions>
        </plugin></plugins></build></profile>
    </profiles>
</project>

```

## `src/main/resources/db/schema.sql`

```sql
CREATE TABLE IF NOT EXISTS applications (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_name VARCHAR(150) NOT NULL CHECK (length(btrim(company_name)) > 0),
    role VARCHAR(150) NOT NULL CHECK (length(btrim(role)) > 0),
    application_date DATE NOT NULL,
    deadline DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED'
        CHECK (status IN ('APPLIED', 'ASSESSMENT', 'INTERVIEW', 'OFFERED', 'REJECTED')),
    CONSTRAINT deadline_not_before_application CHECK (deadline >= application_date)
);
CREATE UNIQUE INDEX IF NOT EXISTS applications_unique_company_role_date
    ON applications (lower(btrim(company_name)), lower(btrim(role)), application_date);
CREATE INDEX IF NOT EXISTS applications_deadline_idx ON applications (deadline);
CREATE INDEX IF NOT EXISTS applications_status_idx ON applications (status);

```

## `run.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/Applications/android-studio/jbr/bin/java" ]]; then
    export JAVA_HOME="$HOME/Applications/android-studio/jbr"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! command -v mvn >/dev/null 2>&1; then
    echo "Maven is required. On Ubuntu: sudo apt install maven"
    exit 1
fi
if [[ "${1:-}" == "--test" ]]; then
    exec mvn test
elif [[ "${1:-}" == "--integration-test" ]]; then
    exec mvn -Pintegration verify
fi
mvn -q -DskipTests package
exec java -jar target/placement-application-tracker-2.0.0.jar "$@"

```

## `.env.example`

```bash
# Copy to .env, edit locally, then: set -a; source .env; set +a
# Set a nonempty password. Do not commit .env.
DB_URL=jdbc:postgresql://localhost:5432/placement_tracker
DB_USER=tracker_app
DB_PASSWORD='replace-with-your-local-password'
DB_SCHEMA=public
# Used only by the integration tests. Keep a separate database.
DB_TEST_URL=jdbc:postgresql://localhost:5432/placement_tracker_test
DB_TEST_USER=tracker_app
DB_TEST_PASSWORD='replace-with-your-local-password'

```
