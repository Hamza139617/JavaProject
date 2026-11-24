package osl.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

final class MySqlDatabase {
    private MySqlDatabase() {}

    static Connection open() throws SQLException {
        Properties config = databaseConfig();
        String user = config.getProperty("OSL_DB_USER");
        String password = config.getProperty("OSL_DB_PASSWORD");
        if (user == null || user.isBlank() || password == null) {
            throw new SQLException("OSL database credentials are missing.", "OSL01");
        }

        String url = config.getProperty("OSL_DB_URL", "jdbc:mysql://127.0.0.1:3306/osl_engine");
        if (!url.startsWith("jdbc:mysql:")) {
            throw new SQLException("OSL requires a MySQL JDBC URL.", "OSL02");
        }
        try {
            DriverManager.getDriver(url);
        } catch (SQLException failure) {
            throw new SQLException("MySQL Connector/J is missing from the runtime classpath.", "OSL03", failure);
        }

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", "4000");
        props.setProperty("socketTimeout", "8000");
        return DriverManager.getConnection(url, props);
    }

    private static Properties databaseConfig() throws SQLException {
        Properties config = new Properties();
        for (String key : new String[]{"OSL_DB_URL", "OSL_DB_USER", "OSL_DB_PASSWORD"}) {
            String value = System.getenv(key);
            if (value != null) {
                config.setProperty(key, value);
            }
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (config.isEmpty() && localAppData != null && !localAppData.isBlank()) {
            Path path = Path.of(localAppData, "OSL-Engine", "connection.properties");
            try (var stream = Files.newInputStream(path)) {
                config.load(stream);
            } catch (NoSuchFileException missing) {
                // Explicit environment variables also support installations without a local config file.
            } catch (IOException | IllegalArgumentException failure) {
                throw new SQLException("The OSL database settings could not be read.", "OSL02", failure);
            }
        }
        return config;
    }

    interface Transaction<T> {
        T execute(Connection connection) throws SQLException;
    }

    static <T> T transaction(Transaction<T> action) throws SQLException {
        try (var connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = action.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }
}
