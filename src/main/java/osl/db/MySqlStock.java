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
import osl.db.MySqlDatabase.WorkspaceLock;

public final class MySqlStock implements StockRepository {
    @Override
    public List<Entry> load(Session session) throws SQLException {
        try (Connection connection = MySqlDatabase.open()) {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.NONE);
            try (var query = connection.prepareStatement("""
                    SELECT p.product_id, p.product_name, p.unit, p.requires_expiry, p.selling_price, p.archived_at,
                           l.lot_id, l.quantity, l.unit_cost, l.expiry_date, l.source_name
                    FROM products p
                    LEFT JOIN stock_lots l ON l.product_id = p.product_id
                    WHERE p.business_id = ? AND (p.archived_at IS NULL OR l.lot_id IS NOT NULL)
                    ORDER BY p.product_name, p.product_id, l.lot_id DESC
                    """)) {
                query.setLong(1, business);
                try (var rows = query.executeQuery()) {
                    List<Entry> results = new ArrayList<>();
                    while (rows.next()) {
                        results.add(entry(rows));
                    }
                    return results;
                }
            }
        }
    }

    @Override
    public long receive(Session session, Product product, Receipt receipt) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.SHARED);
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
                lot = MySqlDatabase.generatedId(insert.getGeneratedKeys());
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
                SELECT product_id, product_name, unit, requires_expiry, selling_price, archived_at
                FROM products WHERE product_id = ? AND business_id = ? AND archived_at IS NULL FOR SHARE
                """)) {
            query.setLong(1, product.id());
            query.setLong(2, business);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return false;
                Product current = product(rows);
                return current.unit() == product.unit() && current.requiresExpiry() == product.requiresExpiry();
            }
        }
    }

    static Entry entry(ResultSet row) throws SQLException {
        Product product = product(row);
        long id = row.getLong("lot_id");
        Lot lot = row.wasNull() ? null : new Lot(id, row.getBigDecimal("quantity"),
                row.getBigDecimal("unit_cost"), row.getObject("expiry_date", LocalDate.class),
                row.getString("source_name"));
        return new Entry(product, lot);
    }

    private static Product product(ResultSet row) throws SQLException {
        return new Product(row.getLong("product_id"), row.getString("product_name"),
                Unit.valueOf(row.getString("unit")), row.getBoolean("requires_expiry"),
                row.getBigDecimal("selling_price"), row.getObject("archived_at") != null);
    }
}
