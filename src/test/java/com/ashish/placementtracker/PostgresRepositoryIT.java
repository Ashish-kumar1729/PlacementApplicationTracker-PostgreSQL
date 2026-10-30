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
