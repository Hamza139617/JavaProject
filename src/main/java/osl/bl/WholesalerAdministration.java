package osl.bl;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;

public final class WholesalerAdministration {
    private static final int RESULT_LIMIT = 100;

    public enum Status {
        ACTIVE("Active"), SUSPENDED("Deactivated"), REMOVED("Removed");

        private final String label;
        Status(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record AccountRow(long id, String username, String fullName, String businessName, String phone, Status status) {}
    public record Search(String name, Long accountId, String phone) {}
    public record Results(List<AccountRow> accounts, boolean limited) {
        public Results { accounts = List.copyOf(accounts); }
    }
    public record Change(Status previous, Status current, String reason, String administrator, Instant time) {}

    public static final class AdministrationException extends RuntimeException {
        public AdministrationException(String message) { super(message); }
    }

    private final WholesalerAdministrationRepository repository;

    public WholesalerAdministration(WholesalerAdministrationRepository repository) {
        this.repository = repository;
    }

    public Results search(Session administrator, String identity, String idInput, String phoneInput) throws SQLException {
        requireAdministrator(administrator);
        String name = identity.strip();
        if (name.startsWith("@")) name = name.substring(1).strip();
        if (name.length() > 120 || name.codePoints().anyMatch(Character::isISOControl)
                || (name.isEmpty() && !identity.isBlank())) {
            throw new AdministrationException("Enter a valid name or username of up to 120 characters.");
        }
        Long id = idInput.isBlank() ? null : parseId(idInput);
        String phone = null;
        if (!phoneInput.isBlank()) {
            try {
                phone = AccountService.phone(phoneInput.replace("-", "").replace("(", "").replace(")", ""));
            } catch (AccountService.AccountException invalid) {
                throw new AdministrationException(invalid.getMessage());
            }
        }
        List<AccountRow> rows = repository.search(administrator, new Search(name.toLowerCase(Locale.ROOT), id, phone),
                RESULT_LIMIT + 1);
        return new Results(rows.subList(0, Math.min(rows.size(), RESULT_LIMIT)), rows.size() > RESULT_LIMIT);
    }

    public List<Change> history(Session administrator, long accountId) throws SQLException {
        requireAdministrator(administrator);
        return List.copyOf(repository.history(administrator, accountId));
    }

    public void changeStatus(Session administrator, long accountId, Status expected, String input) throws SQLException {
        requireAdministrator(administrator);
        Status target = switch (expected) {
            case ACTIVE -> Status.SUSPENDED;
            case SUSPENDED -> Status.ACTIVE;
            case REMOVED -> throw new AdministrationException("A removed account cannot be reactivated here.");
        };
        String reason = input.strip();
        if (reason.length() > 500) throw new AdministrationException("Use up to 500 characters for the reason.");
        if (target == Status.SUSPENDED && reason.isEmpty()) {
            throw new AdministrationException("Enter a reason for deactivating this account.");
        }
        repository.changeStatus(administrator, accountId, expected, target, reason);
    }

    private static long parseId(String value) {
        String text = value.strip();
        if (text.startsWith("#")) text = text.substring(1).strip();
        try {
            if (text.matches("[0-9]{1,19}")) {
                long id = Long.parseLong(text);
                if (id > 0) return id;
            }
        } catch (NumberFormatException outOfRange) {
            // Values beyond the supported account ID range are invalid.
        }
        throw new AdministrationException("Enter a valid numeric account ID, such as 42.");
    }

    private static void requireAdministrator(Session administrator) {
        if (administrator == null || administrator.role() != Role.ADMINISTRATOR) {
            throw new AdministrationException("Wholesaler account administration requires an active administrator.");
        }
    }
}