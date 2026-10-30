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
