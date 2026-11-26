package osl.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.WholesalerAdministration.AccountRow;
import osl.bl.WholesalerAdministration.AdministrationException;
import osl.bl.WholesalerAdministration.Change;
import osl.bl.WholesalerAdministration.Search;
import osl.bl.WholesalerAdministration.Status;
import osl.bl.WholesalerAdministrationRepository;

public final class MySqlWholesalerAdministration implements WholesalerAdministrationRepository {
    @Override
    public List<AccountRow> search(Session administrator, Search search, int limit) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            requireAdministrator(connection, administrator);
            try (var query = connection.prepareStatement("""
                    SELECT a.account_id, a.username, a.full_name, b.business_name, a.phone, a.account_status
                    FROM user_accounts a
                    LEFT JOIN businesses b ON b.account_id = a.account_id
                    WHERE a.role = 'WHOLESALER'
                        AND (? IS NULL OR a.account_id = ?)
                        AND (? IS NULL OR a.phone = ?)
                        AND (? = '' OR LOCATE(?, LOWER(a.username)) > 0 OR LOCATE(?, LOWER(a.full_name)) > 0
                            OR LOCATE(?, LOWER(b.business_name)) > 0)
                    ORDER BY COALESCE(b.business_name, a.full_name), a.username, a.account_id
                    LIMIT ?
                    """)) {
                query.setObject(1, search.accountId());
                query.setObject(2, search.accountId());
                query.setString(3, search.phone());
                query.setString(4, search.phone());
                for (int parameter = 5; parameter <= 8; parameter++) query.setString(parameter, search.name());
                query.setInt(9, limit);
                List<AccountRow> matches = new ArrayList<>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        matches.add(new AccountRow(rows.getLong("account_id"), rows.getString("username"),
                                rows.getString("full_name"), rows.getString("business_name"), rows.getString("phone"),
                                status(rows, "account_status")));
                    }
                }
                return matches;
            }
        });
    }

    @Override
    public List<Change> history(Session administrator, long accountId) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            requireAdministrator(connection, administrator);
            wholesalerStatus(connection, accountId, " FOR SHARE");
            try (var query = connection.prepareStatement("""
                    SELECT h.previous_status, h.new_status, h.reason, h.changed_at, a.username
                    FROM wholesaler_account_status_history h
                    JOIN user_accounts a ON a.account_id = h.administrator_id
                    WHERE h.wholesaler_id = ?
                    ORDER BY h.changed_at DESC, h.change_id DESC
                    """)) {
                query.setLong(1, accountId);
                List<Change> history = new ArrayList<>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        history.add(new Change(status(rows, "previous_status"), status(rows, "new_status"),
                                rows.getString("reason"), rows.getString("username"),
                                rows.getTimestamp("changed_at").toInstant()));
                    }
                }
                return history;
            }
        });
    }

    @Override
    public void changeStatus(Session administrator, long accountId, Status expected, Status target, String reason)
            throws SQLException {
        MySqlDatabase.transaction(connection -> {
            requireAdministrator(connection, administrator);
            Status current = wholesalerStatus(connection, accountId, " FOR UPDATE");
            if (current != expected) {
                throw new AdministrationException("This account's status has changed. Refresh and review it before trying again.");
            }
            writeStatus(connection, administrator, accountId, expected, target, reason);
            return null;
        });
    }

    static void suspendFromReport(Connection connection, Session administrator, long accountId, String reason) throws SQLException {
        Status current = wholesalerStatus(connection, accountId, " FOR UPDATE");
        if (current == Status.REMOVED) {
            throw new AdministrationException("This wholesaler account has already been removed.");
        }
        if (current == Status.ACTIVE) {
            writeStatus(connection, administrator, accountId, current, Status.SUSPENDED, reason);
        }
    }

    private static void writeStatus(Connection connection, Session administrator, long accountId,
                                    Status expected, Status target, String reason) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE wholesaler_account_status SET account_status = ?
                WHERE account_id = ? AND account_status = ?
                """)) {
            update.setString(1, target.name());
            update.setLong(2, accountId);
            update.setString(3, expected.name());
            if (update.executeUpdate() != 1) {
                throw new AdministrationException("The wholesaler account could not be updated. Refresh and try again.");
            }
        }
        try (var audit = connection.prepareStatement("""
                INSERT INTO wholesaler_account_status_history
                    (wholesaler_id, administrator_id, previous_status, new_status, reason)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            audit.setLong(1, accountId);
            audit.setLong(2, administrator.accountId());
            audit.setString(3, expected.name());
            audit.setString(4, target.name());
            audit.setString(5, reason);
            audit.executeUpdate();
        }
    }

    static void requireAdministrator(Connection connection, Session session) throws SQLException {
        if (session == null || session.role() != Role.ADMINISTRATOR) throw administratorUnavailable();
        try (var query = connection.prepareStatement("""
                SELECT account_id FROM user_accounts
                WHERE account_id = ? AND role = 'ADMINISTRATOR' AND account_status = 'ACTIVE' FOR SHARE
                """)) {
            query.setLong(1, session.accountId());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw administratorUnavailable();
            }
        }
    }

    private static AdministrationException administratorUnavailable() {
        return new AdministrationException("Wholesaler account administration requires an active administrator.");
    }

    private static Status wholesalerStatus(Connection connection, long accountId, String lock) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT account_status FROM user_accounts WHERE account_id = ? AND role = 'WHOLESALER'
                """ + lock)) {
            query.setLong(1, accountId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AdministrationException("No wholesaler account matches that record. Review the account details.");
                }
                return status(rows, "account_status");
            }
        }
    }

    private static Status status(ResultSet rows, String column) throws SQLException {
        return Status.valueOf(rows.getString(column));
    }
}