package osl.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import osl.bl.AccountService.Session;
import osl.bl.StockRepository;
import osl.bl.StockService.Entry;
import osl.bl.StockService.Lot;
import osl.bl.StockService.Product;
import osl.bl.StockService.Receipt;
import osl.bl.StockService.Unit;

public final class MySqlStock implements StockRepository {
    @Override
    public List<Entry> load(Session session) throws SQLException {
        try (Connection connection = MySqlDatabase.open()) {
            long business = workspace(connection, session, false);
            try (var query = connection.prepareStatement("""
                    SELECT p.product_id, p.product_name, p.unit, p.requires_expiry,
                           l.lot_id, l.quantity, l.unit_cost, l.expiry_date, l.source_name
                    FROM products p
                    LEFT JOIN stock_lots l ON l.product_id = p.product_id
                    WHERE p.business_id = ?
                    ORDER BY p.product_name, p.product_id, l.lot_id DESC
                    """)) {
                query.setLong(1, business);
                try (var rows = query.executeQuery()) {
                    List<Entry> results = new ArrayList<>();
                    while (rows.next()) {
                        Product product = product(rows);
                        long id = rows.getLong("lot_id");
                        Lot lot = rows.wasNull() ? null : new Lot(id, rows.getBigDecimal("quantity"),
                                rows.getBigDecimal("unit_cost"), rows.getObject("expiry_date", LocalDate.class),
                                rows.getString("source_name"));
                        results.add(new Entry(product, lot));
                    }
                    return results;
                }
            }
        }
    }

    @Override
    public long addProduct(Session session, String name, Unit unit, boolean requiresExpiry) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = workspace(connection, session, true);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO products (business_id, product_name, unit, requires_expiry)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, business);
                insert.setString(2, name);
                insert.setString(3, unit.name());
                insert.setBoolean(4, requiresExpiry);
                insert.executeUpdate();
                return generatedId(insert.getGeneratedKeys());
            }
        });
    }

    @Override
    public long receive(Session session, Product product, Receipt receipt) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = workspace(connection, session, true);
            if (!matchesProduct(connection, business, product)) {
                return 0L;
            }
            long lot;
            try (var insert = connection.prepareStatement("""
                    INSERT INTO stock_lots (product_id, quantity, unit_cost, expiry_date, source_name)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, product.id());
                insert.setBigDecimal(2, receipt.quantity());
                insert.setBigDecimal(3, receipt.unitCost());
                insert.setObject(4, receipt.expiry());
                insert.setString(5, receipt.source());
                insert.executeUpdate();
                lot = generatedId(insert.getGeneratedKeys());
            }
            try (var move = connection.prepareStatement("""
                    INSERT INTO stock_movements (lot_id, quantity_delta, reason)
                    VALUES (?, ?, ?)
                    """)) {
                move.setLong(1, lot);
                move.setBigDecimal(2, receipt.quantity());
                move.setString(3, "Received stock");
                move.executeUpdate();
            }
            return lot;
        });
    }

    private static boolean matchesProduct(Connection connection, long business, Product product)
            throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT product_id, product_name, unit, requires_expiry
                FROM products WHERE product_id = ? AND business_id = ? FOR SHARE
                """)) {
            query.setLong(1, product.id());
            query.setLong(2, business);
            try (var rows = query.executeQuery()) {
                return rows.next() && product.equals(product(rows));
            }
        }
    }

    private static Product product(ResultSet row) throws SQLException {
        return new Product(row.getLong("product_id"), row.getString("product_name"),
                Unit.valueOf(row.getString("unit")), row.getBoolean("requires_expiry"));
    }

    private static long workspace(Connection connection, Session session, boolean lock) throws SQLException {
        String query = """
                SELECT b.business_id FROM businesses b
                JOIN user_accounts a ON a.account_id = b.account_id
                WHERE b.account_id = ? AND a.account_status = 'ACTIVE' AND a.role = ?
                  AND a.role IN ('RETAILER', 'WHOLESALER')
                """ + (lock ? " FOR SHARE" : "");
        try (var statement = connection.prepareStatement(query)) {
            statement.setLong(1, session.accountId());
            statement.setString(2, session.role().name());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("The stock workspace is unavailable.", "OSL04");
                }
                return rows.getLong("business_id");
            }
        }
    }

    private static long generatedId(ResultSet keys) throws SQLException {
        try (keys) {
            if (!keys.next()) {
                throw new SQLException("The database did not return a stock record ID.");
            }
            return keys.getLong(1);
        }
    }
}
