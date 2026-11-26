package osl.bl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Product;
import osl.bl.StockService.StockLine;

public final class ReplenishmentService {
    public static final int SALES_WINDOW_DAYS = 30;

    public record Target(long productId, long lotId) {}

    public record Settings(BigDecimal threshold, boolean noticesEnabled, int reminderHours) {
        public static final Settings DEFAULT = new Settings(BigDecimal.TEN, true, 24);
    }

    public enum State {
        PLANNED("Being replenished"), RECEIVED("Received"), CANCELLED("Cancelled");
        private final String label;
        State(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record PlanDetails(String source, BigDecimal quantity, LocalDate expectedOn) {}

    public record Plan(long id, Target target, Product product, PlanDetails details, State state, long receivedLot) {}

    public record Notice(Instant lastShown, int count, boolean acknowledged) {}

    public record Candidate(Target target, Product product, BigDecimal available, BigDecimal capacity,
                            BigDecimal freeSpace, BigDecimal daysRemaining, Plan plan, Notice notice) {}

    public record Board(List<Candidate> low, List<Plan> plans, Settings settings) {
        public long unreadNotices() {
            return low.stream().filter(row -> row.notice() != null && !row.notice().acknowledged()).count();
        }
    }

    public static final class ReplenishmentException extends RuntimeException {
        public ReplenishmentException(String message) { super(message); }
    }

    private final ReplenishmentRepository repository;
    private final StockService stock;
    private final Clock clock;

    public ReplenishmentService(ReplenishmentRepository repository, StockService stock) {
        this(repository, stock, Clock.system(ZoneId.of("Asia/Karachi")));
    }

    public ReplenishmentService(ReplenishmentRepository repository, StockService stock, Clock clock) {
        this.repository = repository;
        this.stock = stock;
        this.clock = clock;
    }

    public LocalDate today() { return LocalDate.now(clock); }

    public Board load(Session session) throws SQLException {
        requireBusiness(session);
        var context = repository.load(session, today());
        var rows = stock.load(session).lines();
        Map<Long, BigDecimal> onHand = new HashMap<>();
        Map<Target, Plan> planned = new HashMap<>();
        Set<Target> replaced = new HashSet<>();
        for (Plan plan : context.plans()) {
            if (plan.state() == State.PLANNED) planned.put(plan.target(), plan);
            if (plan.state() == State.RECEIVED && plan.target().lotId() != 0) replaced.add(plan.target());
        }
        for (StockLine row : rows) onHand.merge(row.product().id(), row.onHand(), BigDecimal::add);
        List<StockLine> low = rows.stream().filter(row -> !row.product().archived()
                && (row.available().compareTo(context.settings().threshold()) <= 0 || planned.containsKey(target(row)))
                && !replaced.contains(target(row))).toList();
        Set<Target> needsNotice = new HashSet<>();
        for (StockLine row : low) {
            if (!planned.containsKey(target(row))) needsNotice.add(target(row));
        }
        Map<Target, Notice> notices = repository.syncNotices(session, needsNotice, context.settings(), clock.instant());
        List<Candidate> candidates = low.stream().map(row -> {
            Target target = target(row);
            BigDecimal capacity = context.capacities().get(row.product().id());
            BigDecimal free = capacity == null ? null : capacity.subtract(onHand.get(row.product().id())).max(BigDecimal.ZERO);
            BigDecimal sales = context.recentSales().getOrDefault(row.product().id(), BigDecimal.ZERO);
            BigDecimal days = row.available().signum() == 0 ? BigDecimal.ZERO
                    : sales.signum() == 0 ? null : row.available().multiply(BigDecimal.valueOf(SALES_WINDOW_DAYS))
                            .divide(sales, 1, RoundingMode.HALF_UP);
            return new Candidate(target, row.product(), row.available(), capacity, free, days, planned.get(target), notices.get(target));
        }).toList();
        return new Board(candidates, List.copyOf(context.plans()), context.settings());
    }

    public long plan(Session session, Candidate candidate, String supplier, String quantity, LocalDate due)
            throws SQLException {
        requireBusiness(session);
        if (candidate == null) throw new ReplenishmentException("Select stock to replenish.");
        return repository.savePlan(session, 0, candidate.target(), candidate.product(),
                details(candidate.product(), supplier, quantity, due), today());
    }

    public long edit(Session session, Plan plan, String supplier, String quantity, LocalDate due) throws SQLException {
        requireBusiness(session);
        return repository.savePlan(session, plan.id(), plan.target(), plan.product(),
                details(plan.product(), supplier, quantity, due), today());
    }

    public long receive(Session session, Plan plan, String quantity, String cost, String expiry) throws SQLException {
        requireBusiness(session);
        var receipt = stock.prepare(plan.product(), new StockService.ReceiptInput(quantity, cost, expiry, plan.details().source()));
        return repository.receive(session, plan, receipt);
    }

    public void cancel(Session session, Plan plan) throws SQLException {
        requireBusiness(session);
        repository.cancel(session, plan.id());
    }

    public void acknowledge(Session session, Candidate candidate) throws SQLException {
        requireBusiness(session);
        repository.acknowledge(session, candidate.target());
    }

    public void savePreferences(Session session, String threshold, boolean alerts, String hours) throws SQLException {
        requireBusiness(session);
        BigDecimal limit = StockService.decimal(threshold, 3, 12, "Enter a positive low-stock threshold.");
        if (limit.signum() <= 0) throw new ReplenishmentException("Enter a positive low-stock threshold.");
        int interval;
        try {
            interval = Integer.parseInt(hours.strip());
        } catch (NumberFormatException invalid) {
            throw new ReplenishmentException("Enter a reminder interval from 1 to 720 hours.");
        }
        if (interval < 1 || interval > 720) throw new ReplenishmentException("Enter a reminder interval from 1 to 720 hours.");
        repository.saveSettings(session, new Settings(limit, alerts, interval));
    }

    public void saveCapacity(Session session, Product product, String maximum) throws SQLException {
        requireBusiness(session);
        repository.saveCapacity(session, product, maximum.isBlank() ? null : StockService.quantity(product.unit(), maximum));
    }

    private PlanDetails details(Product product, String name, String quantity, LocalDate due) {
        String source = name.strip();
        if (source.isEmpty() || source.length() > 120) {
            throw new ReplenishmentException("Enter the wholesaler or supplier name (up to 120 characters).");
        }
        if (due == null || due.isBefore(today()) || due.getYear() > 9999) {
            throw new ReplenishmentException("Choose an expected delivery date of today or later.");
        }
        return new PlanDetails(source, StockService.quantity(product.unit(), quantity), due);
    }

    private static Target target(StockLine row) {
        return new Target(row.product().id(), row.lot() == null ? 0 : row.lot().id());
    }

    private static void requireBusiness(Session session) {
        if (session == null || (session.role() != Role.RETAILER && session.role() != Role.WHOLESALER)) {
            throw new ReplenishmentException("Replenishment is available to retailers and wholesalers.");
        }
    }
}
