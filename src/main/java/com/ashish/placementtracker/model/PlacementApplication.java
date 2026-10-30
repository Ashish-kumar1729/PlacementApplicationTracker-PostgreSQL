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
