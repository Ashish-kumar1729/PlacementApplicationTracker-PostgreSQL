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
