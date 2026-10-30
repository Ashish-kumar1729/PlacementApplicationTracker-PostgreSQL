package com.ashish.placementtracker.repository;

import com.ashish.placementtracker.config.DatabaseConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class JdbcConnectionFactory {
    private final DatabaseConfig config;
    public JdbcConnectionFactory(DatabaseConfig config) { this.config = config; }
    public Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "15");
        Connection connection = DriverManager.getConnection(config.url(), properties);
        try { connection.setSchema(config.schema()); return connection; }
        catch (SQLException ex) { connection.close(); throw ex; }
    }
}
