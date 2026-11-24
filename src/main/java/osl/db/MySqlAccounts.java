package osl.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.function.Function;
import osl.bl.AccountRepository;
import osl.bl.AccountService;

public final class MySqlAccounts implements AccountRepository {
    private static final String SELECT = """
            SELECT a.account_id, a.username, a.full_name, a.phone, a.role, a.account_status,
                   a.password_hash, a.failed_attempts, a.locked_until_ms, b.business_name
            FROM user_accounts a LEFT JOIN businesses b ON b.account_id = a.account_id
            """;

    @Override
    public boolean create(String username, String fullName, String businessName, String phone,
                          AccountService.Role role, String passwordHash) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long accountId;
            try (var insert = connection.prepareStatement("""
                    INSERT INTO user_accounts (username, full_name, phone, role, password_hash)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, username);
                insert.setString(2, fullName);
                insert.setString(3, phone);
                insert.setString(4, role.name());
                insert.setString(5, passwordHash);
                try {
                    insert.executeUpdate();
                } catch (SQLException failure) {
                    if (failure.getErrorCode() == 1062) {
                        return false;
                    }
                    throw failure;
                }
                try (var keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Account ID was not returned.");
                    }
                    accountId = keys.getLong(1);
                }
            }
            if (businessName != null) {
                try (var insert = connection.prepareStatement(
                        "INSERT INTO businesses (account_id, business_name) VALUES (?, ?)")) {
                    insert.setLong(1, accountId);
                    insert.setString(2, businessName);
                    insert.executeUpdate();
                }
            }
            return true;
        });
    }

    @Override
    public AccountService.LoginResult checkLogin(String username, Function<Account, LoginChange> decision)
            throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            Account account;
            try (var query = connection.prepareStatement(SELECT + " WHERE a.username = ? FOR UPDATE")) {
                query.setString(1, username);
                try (var rows = query.executeQuery()) {
                    account = rows.next() ? read(rows) : null;
                }
            }
            LoginChange change = decision.apply(account);
            if (account != null && (change.failedAttempts() != account.failedAttempts()
                    || change.lockedUntil() != account.lockedUntil())) {
                try (var update = connection.prepareStatement("""
                        UPDATE user_accounts SET failed_attempts = ?, locked_until_ms = ? WHERE account_id = ?
                        """)) {
                    update.setInt(1, change.failedAttempts());
                    update.setLong(2, change.lockedUntil());
                    update.setLong(3, account.id());
                    update.executeUpdate();
                }
            }
            return change.result();
        });
    }

    @Override
    public Optional<Account> find(long id) throws SQLException {
        try (var connection = MySqlDatabase.open()) {
            return find(connection, id, "");
        }
    }

    private Optional<Account> find(Connection connection, long id, String suffix) throws SQLException {
        try (var query = connection.prepareStatement(SELECT + " WHERE a.account_id = ?" + suffix)) {
            query.setLong(1, id);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    @Override
    public boolean updateProfile(long id, String expectedHash, String fullName, String businessName,
                                 String phone, String passwordHash) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            var current = find(connection, id, " FOR UPDATE");
            if (current.filter(Account::active).filter(account -> expectedHash.equals(account.passwordHash()))
                    .isEmpty()) {
                return false;
            }
            try (var update = connection.prepareStatement("""
                    UPDATE user_accounts SET full_name = ?, phone = ?, password_hash = ? WHERE account_id = ?
                    """)) {
                update.setString(1, fullName);
                update.setString(2, phone);
                update.setString(3, passwordHash);
                update.setLong(4, id);
                update.executeUpdate();
            }
            if (businessName != null) {
                if (current.get().businessName() == null) {
                    throw new SQLException("The account has no business workspace.");
                }
                try (var update = connection.prepareStatement(
                        "UPDATE businesses SET business_name = ? WHERE account_id = ?")) {
                    update.setString(1, businessName);
                    update.setLong(2, id);
                    update.executeUpdate();
                }
            }
            return true;
        });
    }

    private Account read(ResultSet row) throws SQLException {
        return new Account(row.getLong("account_id"), row.getString("username"), row.getString("full_name"),
                row.getString("business_name"), row.getString("phone"),
                AccountService.Role.valueOf(row.getString("role")), "ACTIVE".equals(row.getString("account_status")),
                row.getString("password_hash"), row.getInt("failed_attempts"), row.getLong("locked_until_ms"));
    }
}
