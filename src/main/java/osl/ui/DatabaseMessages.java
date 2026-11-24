package osl.ui;

import java.sql.SQLException;

final class DatabaseMessages {
    private DatabaseMessages() {}

    static String describe(String action, SQLException failure) {
        String reason = switch (failure.getSQLState() == null ? "" : failure.getSQLState()) {
            case "OSL01" -> "The OSL database credentials are missing.";
            case "OSL02" -> "The OSL database settings could not be read or are invalid.";
            case "OSL03" -> "The MySQL driver is missing. Launch OSL from its JavaProject configuration.";
            case "OSL04" -> "This account no longer has an active stock workspace.";
            case "28000" -> "MySQL rejected the configured database login.";
            case "08S01", "08001", "08006" -> "Could not reach MySQL. Check that the local server is running on port 3307.";
            default -> switch (failure.getErrorCode()) {
                case 1049, 1146 -> "The required MySQL tables are missing.";
                case 1044, 1045, 1142 -> "The configured MySQL user does not have the required access.";
                default -> "MySQL returned an error while processing the request.";
            };
        };
        return action + " " + reason;
    }
}
