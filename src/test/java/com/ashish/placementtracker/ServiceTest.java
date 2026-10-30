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
