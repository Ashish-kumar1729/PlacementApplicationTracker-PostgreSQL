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
