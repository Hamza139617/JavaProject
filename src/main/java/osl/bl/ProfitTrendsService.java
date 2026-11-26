package osl.bl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.SalesPerformanceService.DateRange;
import osl.bl.SalesPerformanceService.Metrics;
import osl.bl.SalesPerformanceService.Preset;
import osl.bl.StockService.Unit;

public final class ProfitTrendsService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public enum Bucket {
        DAY("Daily"), MONTH("Monthly"), YEAR("Yearly");

        private final String label;
        Bucket(String label) { this.label = label; }
        @Override public String toString() { return label; }

        LocalDate start(LocalDate date) {
            return switch (this) {
                case DAY -> date;
                case MONTH -> date.withDayOfMonth(1);
                case YEAR -> date.withDayOfYear(1);
            };
        }

        LocalDate next(LocalDate date) {
            return switch (this) {
                case DAY -> date.plusDays(1);
                case MONTH -> date.plusMonths(1);
                case YEAR -> date.plusYears(1);
            };
        }
    }

    public record ProductChoice(long id, String name, Unit unit, boolean archived) {
        public static final ProductChoice ALL = new ProductChoice(0, "All products", null, false);

        @Override
        public String toString() {
            if (id == 0) return name;
            return name + " (" + unit + ")" + (archived ? " · Archived" : "");
        }
    }

    public record Point(LocalDate start, Metrics metrics) {}

    public record Report(DateRange range, DateRange previousRange, Bucket bucket, long productId,
                         Metrics totals, Metrics previous, BigDecimal averageDailyProfit,
                         int periodsWithSales, List<Point> points, List<ProductChoice> products) {
        public BigDecimal profitChange() { return totals.profit().subtract(previous.profit()); }

        public BigDecimal profitChangePercent() { return change(totals.profit(), previous.profit()); }

        public BigDecimal revenueChangePercent() { return change(totals.revenue(), previous.revenue()); }
    }

    private final ProfitTrendsRepository repository;
    private final Clock clock;

    public ProfitTrendsService(ProfitTrendsRepository repository) {
        this(repository, Clock.system(ZoneId.of("Asia/Karachi")));
    }

    public ProfitTrendsService(ProfitTrendsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public DateRange dates(Preset preset) {
        return SalesPerformanceService.dates(preset, LocalDate.now(clock));
    }

    public Report load(Session session, DateRange range, long productId) throws SQLException {
        if (session == null || (session.role() != Role.RETAILER && session.role() != Role.WHOLESALER)) {
            throw new IllegalArgumentException("Profit trends are available to retailers and wholesalers.");
        }

        DateRange previous = SalesPerformanceService.comparisonRange(range, LocalDate.now(clock));
        long days = ChronoUnit.DAYS.between(range.from(), range.to()) + 1;
        long months = ChronoUnit.MONTHS.between(YearMonth.from(range.from()), YearMonth.from(range.to())) + 1;
        Bucket bucket = days <= 62 ? Bucket.DAY : months <= 36 ? Bucket.MONTH : Bucket.YEAR;
        var snapshot = repository.load(session, range, previous, productId, bucket);
        Metrics totals = snapshot.points().stream().map(Point::metrics).reduce(Metrics.ZERO, Metrics::plus);
        BigDecimal average = totals.profit().divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        int periodsWithSales = (int) snapshot.points().stream().filter(point -> point.metrics().count() > 0).count();
        List<Point> points = periodsWithSales == 0 ? List.of() : fill(range, bucket, snapshot.points());
        return new Report(range, previous, bucket, productId, totals, snapshot.previous(), average, periodsWithSales,
                points, List.copyOf(snapshot.products()));
    }

    private static List<Point> fill(DateRange range, Bucket bucket, List<Point> values) {
        Map<LocalDate, Metrics> totals = new HashMap<>();
        values.forEach(point -> totals.put(point.start(), point.metrics()));
        List<Point> points = new ArrayList<>();
        for (LocalDate start = bucket.start(range.from()); !start.isAfter(range.to()); start = bucket.next(start)) {
            points.add(new Point(start, totals.getOrDefault(start, Metrics.ZERO)));
        }
        return List.copyOf(points);
    }

    private static BigDecimal change(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) return null;
        return current.subtract(previous).multiply(HUNDRED).divide(previous.abs(), 2, RoundingMode.HALF_UP);
    }
}
