package osl.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.ProductRepository;
import osl.bl.ProductService.Details;
import osl.bl.ProductService.ProductException;
import osl.bl.ProductService.ProductRow;
import osl.bl.ProductService.Removal;
import osl.bl.StockService.Unit;
import osl.db.MySqlDatabase.WorkspaceLock;

public final class MySqlProducts implements ProductRepository {
    private static final String SELECTION = """
            SELECT p.product_id, p.product_name, p.category, p.unit, p.selling_price, p.requires_expiry,
                   p.archived_at IS NOT NULL AS archived, COALESCE(l.is_listed, FALSE) AS listed,
                   (l.product_id IS NOT NULL OR EXISTS
                       (SELECT 1 FROM stock_lots s WHERE s.product_id = p.product_id)) AS has_history
            FROM products p LEFT JOIN wholesaler_listings l ON l.product_id = p.product_id
            WHERE p.business_id = ?
            """;

    @Override
    public List<ProductRow> load(Session session) throws SQLException {
        try (var connection = MySqlDatabase.open()) {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.NONE);
            try (var query = connection.prepareStatement(SELECTION + " ORDER BY p.product_name, p.product_id")) {
                query.setLong(1, business);
                try (var rows = query.executeQuery()) {
                    List<ProductRow> products = new ArrayList<>();
                    while (rows.next()) products.add(read(rows));
                    return products;
                }
            }
        }
    }

    @Override
    public long save(Session session, long productId, Details details) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            long saved = productId == 0 ? insert(connection, business, details)
                    : update(connection, business, productId, details);
            if (session.role() == Role.WHOLESALER) {
                if (details.listed()) {
                    list(connection, saved, details);
                } else {
                    unlist(connection, saved);
                }
            }
            return saved;
        });
    }

    private static long insert(Connection connection, long business, Details details) throws SQLException {
        try (var query = connection.prepareStatement("""
                INSERT INTO products (product_name, category, unit, selling_price, requires_expiry, business_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            bind(query, details, business);
            query.executeUpdate();
            return MySqlDatabase.generatedId(query.getGeneratedKeys());
        }
    }

    private static long update(Connection connection, long business, long id, Details details) throws SQLException {
        ProductRow current = owned(connection, business, id);
        if (current.archived()) throw new ProductException("Restore this product before editing it.");
        if (current.unitLocked() && current.unit() != details.unit()) {
            throw new ProductException("The unit is fixed once this product has stock or listing history.");
        }
        try (var query = connection.prepareStatement("""
                UPDATE products SET product_name = ?, category = ?, unit = ?, selling_price = ?, requires_expiry = ?
                WHERE business_id = ? AND product_id = ?
                """)) {
            bind(query, details, business);
            query.setLong(7, id);
            query.executeUpdate();
        }
        return id;
    }

    @Override
    public Removal remove(Session session, long productId) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            ProductRow current = owned(connection, business, productId);
            if (current.unitLocked()) {
                if (!current.archived()) setArchived(connection, business, productId, true);
                if (current.listed()) unlist(connection, productId);
                return Removal.ARCHIVED;
            }
            try (var query = connection.prepareStatement("DELETE FROM products WHERE business_id = ? AND product_id = ?")) {
                query.setLong(1, business);
                query.setLong(2, productId);
                query.executeUpdate();
            }
            return Removal.DELETED;
        });
    }

    @Override
    public void restore(Session session, long productId) throws SQLException {
        MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            ProductRow current = owned(connection, business, productId);
            if (current.archived()) setArchived(connection, business, productId, false);
            return null;
        });
    }

    private static void setArchived(Connection connection, long business, long id, boolean archived) throws SQLException {
        String sql = archived
                ? "UPDATE products SET archived_at = CURRENT_TIMESTAMP(6) WHERE business_id = ? AND product_id = ?"
                : "UPDATE products SET archived_at = NULL WHERE business_id = ? AND product_id = ?";
        try (var query = connection.prepareStatement(sql)) {
            query.setLong(1, business);
            query.setLong(2, id);
            query.executeUpdate();
        }
    }

    private static ProductRow owned(Connection connection, long business, long id) throws SQLException {
        try (var query = connection.prepareStatement(SELECTION + " AND p.product_id = ? FOR UPDATE OF p")) {
            query.setLong(1, business);
            query.setLong(2, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new ProductException("This product is no longer in your catalog.");
                return read(rows);
            }
        }
    }

    private static void list(Connection connection, long product, Details details) throws SQLException {
        try (var query = connection.prepareStatement("""
                INSERT INTO wholesaler_listings (product_id, category, rate) VALUES (?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE
                    updated_at = IF(NOT wholesaler_listings.is_listed OR wholesaler_listings.category <> incoming.category
                                    OR wholesaler_listings.rate <> incoming.rate,
                                    CURRENT_TIMESTAMP(6), wholesaler_listings.updated_at),
                    category = incoming.category, rate = incoming.rate, is_listed = TRUE
                """)) {
            query.setLong(1, product);
            query.setString(2, details.category());
            query.setBigDecimal(3, details.sellingPrice());
            query.executeUpdate();
        }
    }

    private static void unlist(Connection connection, long product) throws SQLException {
        try (var query = connection.prepareStatement(
                "UPDATE wholesaler_listings SET is_listed = FALSE WHERE product_id = ? AND is_listed = TRUE")) {
            query.setLong(1, product);
            query.executeUpdate();
        }
    }

    private static void bind(PreparedStatement query, Details details, long business) throws SQLException {
        query.setString(1, details.name());
        query.setString(2, details.category());
        query.setString(3, details.unit().name());
        query.setBigDecimal(4, details.sellingPrice());
        query.setBoolean(5, details.requiresExpiry());
        query.setLong(6, business);
    }

    private static ProductRow read(ResultSet row) throws SQLException {
        return new ProductRow(row.getLong("product_id"), row.getString("product_name"), row.getString("category"),
                Unit.valueOf(row.getString("unit")), row.getBigDecimal("selling_price"),
                row.getBoolean("requires_expiry"), row.getBoolean("listed"), row.getBoolean("archived"),
                row.getBoolean("has_history"));
    }
}
