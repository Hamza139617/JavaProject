package osl.bl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Unit;

public final class CustomerService {
    public record Contact(long id, String name, String phone) {
        public static final Contact WALK_IN = new Contact(0, "Walk-in customer", "");

        @Override
        public String toString() {
            return phone.isEmpty() ? name : name + " · " + phone;
        }
    }

    public record Details(String name, String phone, String preferences, String notes) {}
    public record Profile(Contact contact, String preferences, String notes) {}

    public enum Kind {
        VISIT("Visit"), FEEDBACK("Feedback"), OTHER("Other");

        private final String label;
        Kind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record Interaction(Kind kind, LocalDate date, Integer rating, String notes) {}

    public record PurchasedItem(long productId, String name, Unit unit, BigDecimal quantity) {}

    public record Purchase(long id, LocalDateTime soldAt, BigDecimal total, List<PurchasedItem> items) {
        public Purchase { items = List.copyOf(items); }
    }

    public record MonthlyActivity(YearMonth month, long purchases, BigDecimal spent, long visits) {}

    public record Relationship(Profile profile, List<Purchase> purchases, List<Interaction> interactions,
                               BigDecimal totalSpent, BigDecimal averagePurchase, BigDecimal averageRating,
                               long visits, List<PurchasedItem> favorites, List<MonthlyActivity> activity) {}

    public static final class CustomerException extends RuntimeException {
        public CustomerException(String message) { super(message); }
    }

    public static final class DuplicateCustomer extends RuntimeException {
        private final Contact existing;
        public DuplicateCustomer(Contact existing) {
            super("This phone number already belongs to " + existing.name() + ".");
            this.existing = existing;
        }
        public Contact existing() { return existing; }
    }

    private final CustomerRepository repository;
    private final Clock clock;

    public CustomerService(CustomerRepository repository) {
        this(repository, Clock.system(ZoneId.of("Asia/Karachi")));
    }

    public CustomerService(CustomerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public LocalDate today() { return LocalDate.now(clock); }

    public List<Contact> contacts(Session session) throws SQLException {
        requireRetailer(session);
        return repository.contacts(session);
    }

    public long save(Session session, long customerId, Details input) throws SQLException {
        requireRetailer(session);
        String name = limited(input.name(), 120, "Name");
        if (name.isBlank()) throw new CustomerException("Enter the customer's name.");
        String phone;
        try {
            phone = AccountService.phone(input.phone().replace("-", "").replace("(", "").replace(")", ""));
        } catch (AccountService.AccountException invalid) {
            throw new CustomerException(invalid.getMessage());
        }
        return repository.save(session, customerId, new Details(name, phone,
                limited(input.preferences(), 500, "Preferences"), limited(input.notes(), 1000, "Notes")));
    }

    public void recordInteraction(Session session, long customerId, Interaction input) throws SQLException {
        requireRetailer(session);
        if (input.kind() == null) throw new CustomerException("Choose the interaction type.");
        if (input.date() == null || input.date().isAfter(today()) || input.date().getYear() < 1000) {
            throw new CustomerException("Choose an interaction date of today or earlier.");
        }
        String notes = limited(input.notes(), 1000, "Notes");
        Integer rating = input.rating();
        if (rating != null && (input.kind() != Kind.FEEDBACK || rating < 1 || rating > 5)) {
            throw new CustomerException("Feedback ratings must be between 1 and 5.");
        }
        if (input.kind() == Kind.FEEDBACK && rating == null && notes.isEmpty()) {
            throw new CustomerException("Enter a rating or comments for this feedback.");
        }
        repository.recordInteraction(session, customerId, new Interaction(input.kind(), input.date(), rating, notes));
    }

    public Relationship load(Session session, long customerId) throws SQLException {
        requireRetailer(session);
        var history = repository.history(session, customerId);
        var purchases = List.copyOf(history.purchases());
        var interactions = List.copyOf(history.interactions());
        BigDecimal spent = purchases.stream().map(Purchase::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = purchases.isEmpty() ? BigDecimal.ZERO
                : spent.divide(BigDecimal.valueOf(purchases.size()), 2, RoundingMode.HALF_UP);
        var scores = interactions.stream().filter(row -> row.rating() != null).map(Interaction::rating).toList();
        long sum = scores.stream().mapToLong(Integer::longValue).sum();
        BigDecimal rating = scores.isEmpty() ? null
                : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(scores.size()), 1, RoundingMode.HALF_UP);
        long visits = interactions.stream().filter(row -> row.kind() == Kind.VISIT).count();
        return new Relationship(history.profile(), purchases, interactions, spent, average, rating, visits,
                favorites(purchases), activity(purchases, interactions));
    }

    private record ItemKey(long id, Unit unit) {}

    private static List<PurchasedItem> favorites(List<Purchase> purchases) {
        Map<ItemKey, PurchasedItem> totals = new LinkedHashMap<>();
        for (Purchase purchase : purchases) {
            for (PurchasedItem item : purchase.items()) {
                totals.merge(new ItemKey(item.productId(), item.unit()), item,
                        (first, next) -> new PurchasedItem(first.productId(), first.name(), first.unit(),
                                first.quantity().add(next.quantity())));
            }
        }
        Map<Unit, PurchasedItem> top = new EnumMap<>(Unit.class);
        totals.values().forEach(item -> top.merge(item.unit(), item,
                (first, next) -> first.quantity().compareTo(next.quantity()) >= 0 ? first : next));
        return List.copyOf(top.values());
    }

    private static List<MonthlyActivity> activity(List<Purchase> purchases, List<Interaction> interactions) {
        Map<YearMonth, MonthlyActivity> months = new TreeMap<>(Comparator.reverseOrder());
        for (Purchase purchase : purchases) {
            YearMonth month = YearMonth.from(purchase.soldAt());
            MonthlyActivity row = months.getOrDefault(month, new MonthlyActivity(month, 0, BigDecimal.ZERO, 0));
            months.put(month, new MonthlyActivity(month, row.purchases() + 1, row.spent().add(purchase.total()), row.visits()));
        }
        for (Interaction visit : interactions) {
            if (visit.kind() != Kind.VISIT) continue;
            YearMonth month = YearMonth.from(visit.date());
            MonthlyActivity row = months.getOrDefault(month, new MonthlyActivity(month, 0, BigDecimal.ZERO, 0));
            months.put(month, new MonthlyActivity(month, row.purchases(), row.spent(), row.visits() + 1));
        }
        return List.copyOf(months.values());
    }

    private static String limited(String value, int maximum, String label) {
        String trimmed = value.strip();
        if (trimmed.length() > maximum) throw new CustomerException(label + " must be at most " + maximum + " characters.");
        return trimmed;
    }

    private static void requireRetailer(Session session) {
        if (session == null || session.role() != Role.RETAILER) {
            throw new CustomerException("Customer relationships are available to retailers.");
        }
    }
}
