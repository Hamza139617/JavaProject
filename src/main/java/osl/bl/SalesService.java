package osl.bl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Entry;
import osl.bl.StockService.Product;
import osl.bl.StockService.StockBook;

public final class SalesService {
    private static final Comparator<Entry> RETAIL_BATCH_ORDER = Comparator
            .comparing((Entry entry) -> entry.lot().expiry(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(entry -> entry.lot().id());

    public static final class SaleException extends RuntimeException {
        public SaleException(String message) {
            super(message);
        }
    }

    public record RetailItem(Product product, BigDecimal quantity, BigDecimal price) {
        public BigDecimal total() {
            return money(quantity.multiply(price));
        }
    }

    public sealed interface Order permits RetailOrder, WholesaleOrder {
        LocalDateTime soldAt();
    }

    public record RetailOrder(LocalDateTime soldAt, List<RetailItem> items) implements Order {
        public RetailOrder {
            items = List.copyOf(items);
        }
    }

    public record WholesaleOrder(LocalDateTime soldAt, long lotId, String buyer, BigDecimal price) implements Order {}

    public record SaleLine(Product product, long lotId, BigDecimal quantity, BigDecimal unitPrice,
                           BigDecimal unitCost, BigDecimal revenue, BigDecimal cost) {}

    public record Quote(Order order, List<SaleLine> lines) {
        public Quote {
            lines = List.copyOf(lines);
        }

        public BigDecimal revenue() {
            return lines.stream().map(SaleLine::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal cost() {
            return lines.stream().map(SaleLine::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal profit() {
            return revenue().subtract(cost());
        }
    }

    public record SaleReceipt(long saleId, BigDecimal revenue, BigDecimal cost, BigDecimal profit) {}

    private final SalesRepository repository;
    private final Clock clock;

    public SalesService(SalesRepository repository) {
        this(repository, Clock.system(ZoneId.of("Asia/Karachi")));
    }

    public SalesService(SalesRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    public RetailItem item(Product product, String quantity, String price) {
        if (product == null) {
            throw new SaleException("Choose a product.");
        }
        try {
            return new RetailItem(product, StockService.quantity(product.unit(), quantity), price(price));
        } catch (StockService.StockException invalid) {
            throw new SaleException(invalid.getMessage());
        }
    }

    public BigDecimal price(String value) {
        BigDecimal price;
        try {
            price = StockService.decimal(value, 2, 11, "Enter a price in PKR with up to two decimal places.");
        } catch (StockService.StockException invalid) {
            throw new SaleException(invalid.getMessage());
        }
        if (price.signum() <= 0) {
            throw new SaleException("The selling price must be greater than zero.");
        }
        return price;
    }

    public Quote preview(Session session, Order order, StockBook book) {
        List<Entry> lots = new ArrayList<>();
        for (var line : book.lines()) {
            for (var lot : line.batches()) {
                lots.add(new Entry(line.product(), lot));
            }
        }
        return quote(session, order, lots);
    }

    public SaleReceipt record(Session session, UUID request, Quote preview) throws SQLException {
        validate(session, preview.order());
        return repository.record(session, request, preview, lots -> {
            Quote current = quote(session, preview.order(), lots);
            if (!current.lines().equals(preview.lines())) {
                throw new SaleException("Stock changed after review. Refresh stock and review the sale again.");
            }
            return current;
        });
    }

    private Quote quote(Session session, Order order, List<Entry> lots) {
        validate(session, order);
        LocalDate today = LocalDate.now(clock);
        List<Entry> sellable = lots.stream()
                .filter(e -> e.lot().quantity().signum() > 0
                        && (e.lot().expiry() == null || !e.lot().expiry().isBefore(today)))
                .toList();
        if (order instanceof WholesaleOrder wholesale) {
            Entry entry = sellable.stream().filter(e -> e.lot().id() == wholesale.lotId()).findFirst()
                    .orElseThrow(() -> new SaleException("The selected wholesale stock is unavailable or expired."));
            var lot = entry.lot();
            return new Quote(order, List.of(new SaleLine(entry.product(), lot.id(), lot.quantity(), null,
                    lot.unitCost(), money(wholesale.price()), money(lot.quantity().multiply(lot.unitCost())))));
        }

        List<SaleLine> lines = new ArrayList<>();
        for (RetailItem item : ((RetailOrder) order).items()) {
            List<Entry> eligible = sellable.stream().filter(e -> e.product().id() == item.product().id())
                    .sorted(RETAIL_BATCH_ORDER).toList();
            BigDecimal available = eligible.stream().map(e -> e.lot().quantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (item.quantity().compareTo(available) > 0) {
                throw new SaleException(item.product().name() + " has only " + available.stripTrailingZeros().toPlainString()
                        + " " + item.product().unit() + " available. Expired batches are excluded.");
            }
            allocate(lines, item, eligible);
        }
        return new Quote(order, lines);
    }

    private static void allocate(List<SaleLine> lines, RetailItem item, List<Entry> eligible) {
        BigDecimal sold = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (Entry entry : eligible) {
            BigDecimal missing = item.quantity().subtract(sold);
            if (missing.signum() == 0) {
                break;
            }
            var lot = entry.lot();
            BigDecimal taken = lot.quantity().min(missing);
            BigDecimal nextSold = sold.add(taken);
            BigDecimal nextCost = cost.add(taken.multiply(lot.unitCost()));
            BigDecimal batchRevenue = money(nextSold.multiply(item.price())).subtract(money(sold.multiply(item.price())));
            BigDecimal batchCost = money(nextCost).subtract(money(cost));
            lines.add(new SaleLine(entry.product(), lot.id(), taken, item.price(), lot.unitCost(), batchRevenue, batchCost));
            sold = nextSold;
            cost = nextCost;
        }
    }

    private void validate(Session session, Order order) {
        Role role = session == null ? null : session.role();
        if ((order instanceof RetailOrder && role != Role.RETAILER)
                || (order instanceof WholesaleOrder && role != Role.WHOLESALER)) {
            throw new SaleException("This sale is unavailable for your account type.");
        }
        if (order.soldAt() == null || order.soldAt().getYear() < 1000 || order.soldAt().isAfter(now())) {
            throw new SaleException("Enter a valid sale date and time that is not in the future.");
        }
        if (order instanceof WholesaleOrder wholesale) {
            if (wholesale.lotId() <= 0) {
                throw new SaleException("Choose a wholesale stock.");
            }
            if (wholesale.buyer() == null || wholesale.buyer().isBlank() || wholesale.buyer().strip().length() > 120) {
                throw new SaleException("Enter the retailer's name (up to 120 characters).");
            }
            price(wholesale.price().toPlainString());
        } else if (order instanceof RetailOrder retail) {
            if (retail.items().isEmpty()) {
                throw new SaleException("Add at least one product to the sale.");
            }
            var products = new HashSet<Long>();
            for (RetailItem item : retail.items()) {
                item(item.product(), item.quantity().toPlainString(), item.price().toPlainString());
                if (item.total().signum() == 0) {
                    throw new SaleException("The line total must be at least PKR 0.01.");
                }
                if (!products.add(item.product().id())) {
                    throw new SaleException("Add each product once. Remove its line to change the quantity or price.");
                }
            }
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
