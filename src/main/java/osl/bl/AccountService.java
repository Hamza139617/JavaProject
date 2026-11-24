package osl.bl;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import osl.bl.AccountRepository.Account;
import osl.bl.AccountRepository.LoginChange;

public final class AccountService {
    public enum Role {
        RETAILER("Retailer"), WHOLESALER("Wholesaler"), ADMINISTRATOR("Administrator");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Field { ROLE, USERNAME, PASSWORD, FULL_NAME, BUSINESS, PHONE, CURRENT_PASSWORD }
    public enum LoginStatus { SUCCESS, INVALID, LOCKED, INACTIVE }

    public record Session(long accountId, String username, String fullName,
                          String businessName, String phone, Role role) {}
    public record LoginResult(LoginStatus status, Session session) {}

    public static final class AccountException extends RuntimeException {
        private final Field field;

        AccountException(Field field, String message) {
            super(message);
            this.field = field;
        }

        public Field field() {
            return field;
        }
    }

    public static final String PASSWORD_RULE = "Use 8–128 characters with at least one letter and one number.";
    private static final int ATTEMPT_LIMIT = 3;
    private static final long LOCK_DURATION = Duration.ofMinutes(5).toMillis();
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_HASH = hash("placeholder-password");
    private final AccountRepository repository;
    private final Clock clock;

    public AccountService(AccountRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public AccountService(AccountRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public String register(Role role, String fullName, String business, String phone,
                           String username, String password) throws SQLException {
        if (role != Role.RETAILER && role != Role.WHOLESALER) {
            throw invalid(Field.ROLE, "Choose Retailer or Wholesaler.");
        }
        return create(role, fullName, business, phone, username, password);
    }

    public void provisionAdministrator(String fullName, String phone, String username, String password)
            throws SQLException {
        create(Role.ADMINISTRATOR, fullName, null, phone, username, password);
    }

    private String create(Role role, String fullName, String business, String phone,
                          String username, String password) throws SQLException {
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        if (!validUsername(normalized)) {
            throw invalid(Field.USERNAME, "Use 3–32 letters, numbers, dots, underscores or hyphens.");
        }
        String name = name(fullName, Field.FULL_NAME, "full name");
        String businessName = role == Role.ADMINISTRATOR ? null : name(business, Field.BUSINESS, "business name");
        String contact = phone(phone);
        validatePassword(password);
        if (!repository.create(normalized, name, businessName, contact, role, hash(password))) {
            throw invalid(Field.USERNAME, "That username is already taken. Choose another.");
        }
        return normalized;
    }

    public LoginResult login(String username, String password) throws SQLException {
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw invalid(Field.USERNAME, "Enter your username.");
        }
        if (password.isEmpty()) {
            throw invalid(Field.PASSWORD, "Enter your password.");
        }
        if (!validUsername(normalized) || password.length() > 128) {
            return new LoginResult(LoginStatus.INVALID, null);
        }
        return repository.checkLogin(normalized, account -> decideLogin(account, password));
    }

    private LoginChange decideLogin(Account account, String password) {
        if (account == null) {
            matches(password, DUMMY_HASH);
            return outcome(LoginStatus.INVALID, 0, 0);
        }
        long now = clock.millis();
        if (account.lockedUntil() > now) {
            return outcome(LoginStatus.LOCKED, account.failedAttempts(), account.lockedUntil());
        }
        if (!matches(password, account.passwordHash())) {
            int attempts = (account.lockedUntil() == 0 ? account.failedAttempts() : 0) + 1;
            return attempts >= ATTEMPT_LIMIT
                    ? outcome(LoginStatus.LOCKED, ATTEMPT_LIMIT, now + LOCK_DURATION)
                    : outcome(LoginStatus.INVALID, attempts, 0);
        }
        if (!account.active()) {
            return outcome(LoginStatus.INACTIVE, 0, 0);
        }
        return new LoginChange(new LoginResult(LoginStatus.SUCCESS, session(account)), 0, 0);
    }

    public Session updateProfile(Session current, String fullName, String business, String phone,
                                 String currentPassword, String newPassword) throws SQLException {
        String name = name(fullName, Field.FULL_NAME, "full name");
        String businessName = current.role() == Role.ADMINISTRATOR
                ? null : name(business, Field.BUSINESS, "business name");
        String contact = phone(phone);
        if (!newPassword.isEmpty()) {
            validatePassword(newPassword);
        }
        Account saved = repository.find(current.accountId()).filter(Account::active)
                .orElseThrow(() -> invalid(null, "Your account is unavailable. Contact the administrator."));
        String newHash = saved.passwordHash();
        if (!newPassword.isEmpty()) {
            if (currentPassword.length() > 128 || !matches(currentPassword, saved.passwordHash())) {
                throw invalid(Field.CURRENT_PASSWORD, "Enter your correct current password.");
            }
            newHash = hash(newPassword);
        }
        if (!repository.updateProfile(saved.id(), saved.passwordHash(), name, businessName, contact, newHash)) {
            throw invalid(null, "Your account changed. Sign out and sign in again.");
        }
        return new Session(saved.id(), saved.username(), name, businessName, contact, saved.role());
    }

    private static LoginChange outcome(LoginStatus status, int attempts, long lockedUntil) {
        return new LoginChange(new LoginResult(status, null), attempts, lockedUntil);
    }

    private static Session session(Account account) {
        return new Session(account.id(), account.username(), account.fullName(),
                account.businessName(), account.phone(), account.role());
    }

    private static boolean validUsername(String value) {
        return value.matches("[a-z0-9._-]{3,32}");
    }

    private static String name(String value, Field field, String label) {
        String clean = value.strip();
        if (clean.isEmpty() || clean.length() > 120) {
            throw invalid(field, "Enter a " + label + " of 1–120 characters.");
        }
        return clean;
    }

    private static String phone(String value) {
        String clean = value.replaceAll("\\s+", "");
        if (clean.matches("03[0-9]{9}")) {
            return "+92" + clean.substring(1);
        }
        if (!clean.matches("\\+923[0-9]{9}")) {
            throw invalid(Field.PHONE, "Enter a mobile number such as 03001234567 or +92 300 1234567.");
        }
        return clean;
    }

    private static void validatePassword(String password) {
        if (password.length() < 8 || password.length() > 128
                || password.codePoints().noneMatch(Character::isLetter)
                || password.codePoints().noneMatch(Character::isDigit)) {
            throw invalid(Field.PASSWORD, PASSWORD_RULE);
        }
    }

    private static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        var encoder = Base64.getEncoder();
        return encoder.encodeToString(salt) + ":" + encoder.encodeToString(derive(password, salt));
    }

    private static boolean matches(String password, String stored) {
        String[] parts = stored.split(":", 2);
        var decoder = Base64.getDecoder();
        return MessageDigest.isEqual(decoder.decode(parts[1]), derive(password, decoder.decode(parts[0])));
    }

    private static byte[] derive(String password, byte[] salt) {
        char[] value = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(value, salt, ITERATIONS, 256);
        Arrays.fill(value, '\0');
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Password hashing is unavailable.", failure);
        } finally {
            spec.clearPassword();
        }
    }

    private static AccountException invalid(Field field, String message) {
        return new AccountException(field, message);
    }
}
