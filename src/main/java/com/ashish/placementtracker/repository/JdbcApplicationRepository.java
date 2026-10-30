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
