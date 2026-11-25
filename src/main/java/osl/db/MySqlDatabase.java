package osl.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import osl.bl.AccountService.Session;

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

    enum WorkspaceLock {
        NONE(""), SHARED(" FOR SHARE"), EXCLUSIVE(" FOR UPDATE");

        private final String clause;
        WorkspaceLock(String clause) {
            this.clause = clause;
        }
    }

    static long workspace(Connection connection, Session session, WorkspaceLock lock) throws SQLException {
        String query = """
                SELECT b.business_id FROM businesses b
                JOIN user_accounts a ON a.account_id = b.account_id
                WHERE b.account_id = ? AND a.account_status = 'ACTIVE' AND a.role = ?
                  AND a.role IN ('RETAILER', 'WHOLESALER')
                """ + lock.clause;
        try (var statement = connection.prepareStatement(query)) {
            statement.setLong(1, session.accountId());
            statement.setString(2, session.role().name());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("The business workspace is unavailable.", "OSL04");
                }
                return rows.getLong("business_id");
            }
        }
    }

    static long generatedId(ResultSet keys) throws SQLException {
        try (keys) {
            if (!keys.next()) {
                throw new SQLException("The database did not return the record ID.");
            }
            return keys.getLong(1);
        }
    }
}
