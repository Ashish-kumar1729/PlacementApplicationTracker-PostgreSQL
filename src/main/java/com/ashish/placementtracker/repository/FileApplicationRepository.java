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
