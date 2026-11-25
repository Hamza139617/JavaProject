package osl.bl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Unit;

public final class SalesPerformanceService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public enum Preset {
        TODAY("Today"), THIS_WEEK("This week"), THIS_MONTH("This month"), LAST_MONTH("Last month"),
        LAST_THREE_MONTHS("Last 3 months"), THIS_YEAR("This year"), CUSTOM("Custom");

        private final String label;

        Preset(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    public record DateRange(LocalDate from, LocalDate to) {
        public DateRange previous() {
            YearMonth month = YearMonth.from(from);
            if (!from.equals(to) && from.equals(month.atDay(1)) && YearMonth.from(to).equals(month)) {
                YearMonth prior = month.minusMonths(1);
                LocalDate end = to.equals(month.atEndOfMonth()) ? prior.atEndOfMonth()
                        : prior.atDay(Math.min(to.getDayOfMonth(), prior.lengthOfMonth()));
                return new DateRange(prior.atDay(1), end);
            }
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            return new DateRange(from.minusDays(days), from.minusDays(1));
        }

        boolean containsFull(YearMonth month) {
            return !from.isAfter(month.atDay(1)) && !to.isBefore(month.atEndOfMonth());
        }
    }

    public record Metrics(long count, BigDecimal revenue, BigDecimal cost) {
        public static final Metrics ZERO = new Metrics(0, BigDecimal.ZERO, BigDecimal.ZERO);

        public Metrics plus(Metrics other) {
            return new Metrics(count + other.count, revenue.add(other.revenue), cost.add(other.cost));
        }

        public BigDecimal profit() { return revenue.subtract(cost); }

        public BigDecimal margin() {
            return revenue.signum() == 0 ? null : profit().multiply(HUNDRED).divide(revenue, 2, RoundingMode.HALF_UP);
        }
    }

    public record ItemMonth(long productId, String name, Unit unit, YearMonth month, BigDecimal quantity,
                            BigDecimal revenue, BigDecimal cost, LocalDateTime latest) {
        public BigDecimal profit() { return revenue.subtract(cost); }
    }

    public record StockSale(long lotId, String product, String buyer, Unit unit, BigDecimal quantity,
                            LocalDateTime soldAt, BigDecimal revenue, BigDecimal cost) {
        public BigDecimal profit() { return revenue.subtract(cost); }
    }

    public record SaleEvent(long saleId, LocalDateTime soldAt, String paymentType, BigDecimal revenue,
                            BigDecimal cost) {
        public BigDecimal profit() { return revenue.subtract(cost); }
    }

    public record MonthSales(YearMonth month, Metrics metrics) {}

    public record MonthlyRow(YearMonth month, Metrics metrics, BigDecimal revenueGrowth, BigDecimal countGrowth) {}

    public record ItemStats(String name, Unit unit, BigDecimal quantity, BigDecimal revenue, BigDecimal cost,
                            String demand, ItemMonth bestMonth, LocalDateTime latest, List<ItemMonth> months) {
        public BigDecimal profit() { return revenue.subtract(cost); }
    }

    public record Report(DateRange range, DateRange previous, Metrics totals, Metrics priorTotals,
                         List<MonthlyRow> months, List<ItemStats> items, List<StockSale> stocks,
                         List<SaleEvent> history) {
        public BigDecimal revenueGrowth() { return growth(totals.revenue(), priorTotals.revenue()); }

        public BigDecimal countGrowth() {
            return growth(BigDecimal.valueOf(totals.count()), BigDecimal.valueOf(priorTotals.count()));
        }
    }

    private record ItemKey(long id, Unit unit) {}

    private final SalesPerformanceRepository repository;
    private final Clock clock;

    public SalesPerformanceService(SalesPerformanceRepository repository) {
        this(repository, Clock.system(ZoneId.of("Asia/Karachi")));
    }

    public SalesPerformanceService(SalesPerformanceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public DateRange dates(Preset preset) {
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);
        return switch (preset) {
            case TODAY -> new DateRange(today, today);
            case THIS_WEEK -> new DateRange(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today);
            case THIS_MONTH, CUSTOM -> new DateRange(month.atDay(1), today);
            case LAST_MONTH -> new DateRange(month.minusMonths(1).atDay(1), month.minusMonths(1).atEndOfMonth());
            case LAST_THREE_MONTHS -> new DateRange(month.minusMonths(2).atDay(1), today);
            case THIS_YEAR -> new DateRange(today.withDayOfYear(1), today);
        };
    }

    public Report load(Session session, DateRange selected) throws SQLException {
        if (session == null || (session.role() != Role.RETAILER && session.role() != Role.WHOLESALER)) {
            throw new IllegalArgumentException("Sales performance is available in retailer and wholesaler workspaces.");
        }
        if (selected.from() == null || selected.to() == null || selected.from().isAfter(selected.to())
                || selected.from().getYear() < 1001 || selected.to().isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("Choose a valid date range ending today or earlier.");
        }
        DateRange previous = selected.previous();
        if (previous.from().getYear() < 1000) {
            throw new IllegalArgumentException("Choose a shorter date range for comparison.");
        }
        var snapshot = repository.load(session, selected, previous);
        Metrics totals = snapshot.monthly().stream().map(MonthSales::metrics).reduce(Metrics.ZERO, Metrics::plus);
        return new Report(selected, previous, totals, snapshot.previous(), monthly(selected, snapshot.monthly()),
                rank(snapshot.itemMonths()), List.copyOf(snapshot.stocks()), List.copyOf(snapshot.history()));
    }

    private static List<MonthlyRow> monthly(DateRange range, List<MonthSales> totals) {
        Map<YearMonth, Metrics> byMonth = new LinkedHashMap<>();
        totals.forEach(total -> byMonth.put(total.month(), total.metrics()));
        List<MonthlyRow> rows = new ArrayList<>();
        YearMonth end = YearMonth.from(range.to());
        for (YearMonth month = YearMonth.from(range.from()); !month.isAfter(end); month = month.plusMonths(1)) {
            Metrics metrics = byMonth.getOrDefault(month, Metrics.ZERO);
            YearMonth prior = month.minusMonths(1);
            boolean compare = range.containsFull(month) && range.containsFull(prior);
            Metrics previous = byMonth.getOrDefault(prior, Metrics.ZERO);
            rows.add(new MonthlyRow(month, metrics, compare ? growth(metrics.revenue(), previous.revenue()) : null,
                    compare ? growth(BigDecimal.valueOf(metrics.count()), BigDecimal.valueOf(previous.count())) : null));
        }
        return List.copyOf(rows);
    }

    private static List<ItemStats> rank(List<ItemMonth> months) {
        Map<ItemKey, List<ItemMonth>> byProduct = new LinkedHashMap<>();
        for (var month : months) {
            byProduct.computeIfAbsent(new ItemKey(month.productId(), month.unit()), ignored -> new ArrayList<>()).add(month);
        }
        Map<Unit, List<ItemStats>> byUnit = new EnumMap<>(Unit.class);
        for (var sales : byProduct.values()) {
            ItemMonth latest = sales.stream().max(Comparator.comparing(ItemMonth::latest)).orElseThrow();
            ItemMonth best = sales.stream().max(Comparator.comparing(ItemMonth::quantity)
                    .thenComparing(ItemMonth::revenue).thenComparing(ItemMonth::month)).orElseThrow();
            BigDecimal quantity = sales.stream().map(ItemMonth::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal revenue = sales.stream().map(ItemMonth::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cost = sales.stream().map(ItemMonth::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
            byUnit.computeIfAbsent(latest.unit(), ignored -> new ArrayList<>())
                    .add(new ItemStats(latest.name(), latest.unit(), quantity, revenue, cost, "", best, latest.latest(),
                            sales.stream().sorted(Comparator.comparing(ItemMonth::month)).toList()));
        }

        List<ItemStats> ranked = new ArrayList<>();
        for (var items : byUnit.values()) {
            items.sort(Comparator.comparing(ItemStats::quantity).reversed().thenComparing(ItemStats::name));
            for (int i = 0; i < items.size();) {
                int end = i + 1;
                while (end < items.size() && items.get(end).quantity().compareTo(items.get(i).quantity()) == 0) {
                    end++;
                }
                double position = items.size() == 1 ? 0.5 : (i + end - 1) / (2.0 * (items.size() - 1));
                String demand = position <= 0.2 ? "High" : position >= 0.8 ? "Low" : "Medium";
                for (; i < end; i++) {
                    ItemStats item = items.get(i);
                    ranked.add(new ItemStats(item.name(), item.unit(), item.quantity(), item.revenue(), item.cost(),
                            demand, item.bestMonth(), item.latest(), item.months()));
                }
            }
        }
        return List.copyOf(ranked);
    }

    private static BigDecimal growth(BigDecimal value, BigDecimal baseline) {
        return baseline.signum() == 0 ? null
                : value.subtract(baseline).multiply(HUNDRED).divide(baseline, 2, RoundingMode.HALF_UP);
    }
}
