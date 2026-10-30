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
