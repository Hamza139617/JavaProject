package osl.bl;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Unit;

public final class ProductService {
    public record ProductRow(long id, String name, String category, Unit unit, BigDecimal sellingPrice,
                             boolean requiresExpiry, boolean listed, boolean archived, boolean unitLocked) {}

    public record Input(String name, String category, Unit unit, String sellingPrice,
                        boolean requiresExpiry, boolean listed) {}

    public record Details(String name, String category, Unit unit, BigDecimal sellingPrice,
                          boolean requiresExpiry, boolean listed) {}

    public enum Removal { DELETED, ARCHIVED }

    public static final class ProductException extends RuntimeException {
        public ProductException(String message) { super(message); }
    }

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) { this.repository = repository; }

    public List<ProductRow> load(Session session) throws SQLException {
        requireBusiness(session);
        return repository.load(session);
    }

    public long save(Session session, long productId, Input input) throws SQLException {
        requireBusiness(session);
        String name = required(input.name(), 120, "product name");
        String category = required(input.category(), 80, "category");
        if (input.unit() == null) throw new ProductException("Choose a stock unit.");
        if (input.listed() && session.role() != Role.WHOLESALER) {
            throw new ProductException("Public stock listings are available to wholesalers.");
        }

        BigDecimal price;
        try {
            price = StockService.decimal(input.sellingPrice(), 2, 11,
                    "Enter a selling price in PKR with up to two decimal places.");
        } catch (StockService.StockException invalid) {
            throw new ProductException(invalid.getMessage());
        }
        if (price.signum() <= 0) throw new ProductException("The selling price must be greater than zero.");
        try {
            return repository.save(session, productId,
                    new Details(name, category, input.unit(), price, input.requiresExpiry(), input.listed()));
        } catch (SQLException failure) {
            if (failure.getErrorCode() == 1062) {
                throw new ProductException("This product and unit are already in your catalog.");
            }
            throw failure;
        }
    }

    public Removal remove(Session session, long productId) throws SQLException {
        requireBusiness(session);
        return repository.remove(session, productId);
    }

    public void restore(Session session, long productId) throws SQLException {
        requireBusiness(session);
        repository.restore(session, productId);
    }

    private static String required(String value, int limit, String field) {
        String clean = value.strip();
        if (clean.isEmpty() || clean.length() > limit) {
            throw new ProductException("Enter a " + field + " of 1–" + limit + " characters.");
        }
        return clean;
    }

    private static void requireBusiness(Session session) {
        if (session == null || (session.role() != Role.RETAILER && session.role() != Role.WHOLESALER)) {
            throw new ProductException("Manage Products is available to retailers and wholesalers.");
        }
    }
}
