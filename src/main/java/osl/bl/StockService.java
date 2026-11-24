package osl.bl;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;

public final class StockService {
    private static final BigDecimal LOW_LEVEL = BigDecimal.TEN;
    private static final int EXPIRY_WINDOW_DAYS = 30;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);

    public enum Unit {
        PIECE("piece", 0), CARTON("carton", 0), KILOGRAM("kg", 3);

        private final String label;
        private final int quantityScale;

        Unit(String label, int quantityScale) {
            this.label = label;
            this.quantityScale = quantityScale;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record Product(long id, String name, Unit unit, boolean requiresExpiry) {
        @Override
        public String toString() {
            return name + " (" + unit + ")";
        }
    }

    public record Lot(long id, BigDecimal quantity, BigDecimal unitCost, LocalDate expiry, String source) {}
    public record Entry(Product product, Lot lot) {}
    public record ReceiptInput(String quantity, String cost, String expiry, String source) {}
    public record Receipt(BigDecimal quantity, BigDecimal unitCost, LocalDate expiry, String source) {}
    public record StockLine(Product product, Lot lot, BigDecimal onHand, BigDecimal available,
                            LocalDate nearestExpiry, String source, String status, List<Lot> batches) {}
    public record StockBook(List<Product> catalog, List<StockLine> lines) {}

    public static final class StockException extends RuntimeException {
        public StockException(String message) {
            super(message);
        }
    }

    private final StockRepository repository;
    private final Clock clock;

    public StockService(StockRepository repository) {
        this(repository, Clock.system(ZoneId.of("Asia/Karachi")));
    }

    public StockService(StockRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public StockBook load(Session session) throws SQLException {
        requireBusiness(session);
        LocalDate today = LocalDate.now(clock);
        Map<Product, List<Lot>> products = new LinkedHashMap<>();
        for (Entry entry : repository.load(session)) {
            List<Lot> lots = products.computeIfAbsent(entry.product(), key -> new ArrayList<>());
            if (entry.lot() != null) {
                lots.add(entry.lot());
            }
        }

        List<StockLine> lines = new ArrayList<>();
        products.forEach((product, lots) -> {
            if (session.role() == Role.RETAILER) {
                lines.add(summarize(product, null, lots, today));
            } else {
                for (Lot lot : lots) {
                    lines.add(summarize(product, lot, List.of(lot), today));
                }
            }
        });
        return new StockBook(List.copyOf(products.keySet()), List.copyOf(lines));
    }

    public long addProduct(Session session, String productName, Unit unit, boolean requiresExpiry) throws SQLException {
        requireBusiness(session);
        if (unit == null) {
            throw new StockException("Choose a stock unit.");
        }
        String name = required(productName, "Enter a product name of up to 120 characters.");
        try {
            return repository.addProduct(session, name, unit, requiresExpiry);
        } catch (SQLException failure) {
            if (failure.getErrorCode() == 1062) {
                throw new StockException("This product and unit are already in your catalog.");
            }
            throw failure;
        }
    }

    public Receipt prepare(Product product, ReceiptInput input) {
        if (product == null) {
            throw new StockException("Choose a product from your catalog.");
        }

        BigDecimal quantity = decimal(input.quantity(), product.unit().quantityScale, 12,
                "Enter a positive quantity. Use whole pieces or cartons, or up to three decimal places for kg.");
        if (quantity.signum() <= 0) {
            throw new StockException("Quantity must be greater than zero.");
        }
        BigDecimal cost = decimal(input.cost(), 2, 11,
                "Enter a unit cost in PKR with up to two decimal places.");
        LocalDate expiry = parseExpiry(input.expiry(), product.requiresExpiry(), LocalDate.now(clock));
        String source = required(input.source(), "Enter a source of up to 120 characters.");
        return new Receipt(quantity, cost, expiry, source);
    }

    public long receive(Session session, Product product, ReceiptInput input) throws SQLException {
        requireBusiness(session);
        Receipt receipt = prepare(product, input);
        long lot = repository.receive(session, product, receipt);
        if (lot == 0) {
            throw new StockException("The product is no longer available in your catalog. Refresh the stock list.");
        }
        return lot;
    }

    public static String displayDate(LocalDate date) {
        return date == null ? "—" : DATE.format(date);
    }

    private static StockLine summarize(Product product, Lot lot, List<Lot> lots, LocalDate today) {
        BigDecimal onHand = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        LocalDate nearest = null;
        LocalDate past = null;
        String source = null;
        for (Lot batch : lots) {
            if (batch.quantity().signum() == 0) {
                continue;
            }
            onHand = onHand.add(batch.quantity());
            if (source == null) {
                source = batch.source();
            }
            LocalDate expiry = batch.expiry();
            if (expiry != null && expiry.isBefore(today)) {
                if (past == null || expiry.isBefore(past)) {
                    past = expiry;
                }
                continue;
            }
            available = available.add(batch.quantity());
            if (expiry != null && (nearest == null || expiry.isBefore(nearest))) {
                nearest = expiry;
            }
        }

        String status;
        if (onHand.signum() == 0) {
            status = "Out of stock";
        } else if (available.signum() == 0) {
            status = "Expired";
        } else {
            boolean low = available.compareTo(LOW_LEVEL) <= 0;
            boolean near = nearest != null && !nearest.isAfter(today.plusDays(EXPIRY_WINDOW_DAYS));
            status = low && near ? "Low stock · Near expiry"
                    : low ? "Low stock" : near ? "Near expiry" : "In stock";
        }
        return new StockLine(product, lot, onHand, available, nearest == null ? past : nearest, source,
                status, List.copyOf(lots));
    }

    private static LocalDate parseExpiry(String value, boolean required, LocalDate today) {
        if (value.isBlank()) {
            if (required) {
                throw new StockException("Enter the expiry date for this product.");
            }
            return null;
        }
        LocalDate expiry;
        try {
            expiry = LocalDate.parse(value.strip(), DATE);
        } catch (DateTimeParseException invalid) {
            throw new StockException("Enter a valid expiry date in DD/MM/YYYY format.");
        }
        if (expiry.isBefore(today)) {
            throw new StockException("The expiry date cannot be in the past.");
        }
        return expiry;
    }

    private static BigDecimal decimal(String value, int scale, int wholeDigits, String error) {
        String clean = value.strip();
        String decimalPart = scale == 0 ? "" : "(?:\\.[0-9]{1," + scale + "})?";
        if (!clean.matches("[0-9]{1," + wholeDigits + "}" + decimalPart)) {
            throw new StockException(error);
        }
        return new BigDecimal(clean);
    }

    private static String required(String value, String error) {
        String clean = value.strip();
        if (clean.isEmpty() || clean.length() > 120) {
            throw new StockException(error);
        }
        return clean;
    }

    private static void requireBusiness(Session session) {
        if (session == null || (session.role() != Role.RETAILER && session.role() != Role.WHOLESALER)) {
            throw new StockException("Manage Stock is available in retailer and wholesaler workspaces.");
        }
    }
}
