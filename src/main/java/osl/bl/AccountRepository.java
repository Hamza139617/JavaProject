package osl.bl;

import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Function;

public interface AccountRepository {
    record Account(long id, String username, String fullName, String businessName, String phone,
                   AccountService.Role role, boolean active, String passwordHash,
                   int failedAttempts, long lockedUntil) {}

    record LoginChange(AccountService.LoginResult result, int failedAttempts, long lockedUntil) {}

    boolean create(String username, String fullName, String businessName, String phone,
                   AccountService.Role role, String passwordHash) throws SQLException;

    AccountService.LoginResult checkLogin(String username, Function<Account, LoginChange> decision)
            throws SQLException;

    Optional<Account> find(long id) throws SQLException;

    boolean updateProfile(long id, String expectedHash, String fullName, String businessName,
                          String phone, String passwordHash) throws SQLException;
}
