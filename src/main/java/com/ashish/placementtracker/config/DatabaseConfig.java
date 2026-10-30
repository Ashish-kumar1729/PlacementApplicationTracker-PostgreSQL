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
