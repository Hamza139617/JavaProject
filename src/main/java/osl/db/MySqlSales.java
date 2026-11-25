package osl.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import osl.bl.AccountService.Session;
import osl.bl.SalesRepository;
import osl.bl.SalesService.Order;
import osl.bl.SalesService.Quote;
import osl.bl.SalesService.RetailOrder;
import osl.bl.SalesService.SaleException;
import osl.bl.SalesService.SaleReceipt;
import osl.bl.SalesService.WholesaleOrder;
import osl.bl.StockService.Entry;
import osl.db.MySqlDatabase.WorkspaceLock;

public final class MySqlSales implements SalesRepository {
    @Override
    public SaleReceipt record(Session session, UUID request, Quote preview, Function<List<Entry>, Quote> review)
            throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            SaleReceipt existing = savedReceipt(connection, business, request);
            if (existing != null) {
                return existing;
            }
            Quote quote = review.apply(lockStock(connection, business, preview.order()));
            long sale = insertSale(connection, business, request, quote);
            saveLines(connection, sale, quote);
            return new SaleReceipt(sale, quote.revenue(), quote.cost(), quote.profit());
        });
    }

    private static SaleReceipt savedReceipt(Connection connection, long business, UUID request) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT sale_id, revenue, total_cost, gross_profit
                FROM sales WHERE business_id = ? AND request_key = ?
                """)) {
            query.setLong(1, business);
            query.setString(2, request.toString());
            try (var rows = query.executeQuery()) {
                return rows.next() ? new SaleReceipt(rows.getLong("sale_id"), rows.getBigDecimal("revenue"),
                        rows.getBigDecimal("total_cost"), rows.getBigDecimal("gross_profit")) : null;
            }
        }
    }

    private static List<Entry> lockStock(Connection connection, long business, Order order) throws SQLException {
        List<Long> ids;
        String restriction;
        if (order instanceof WholesaleOrder wholesale) {
            ids = List.of(wholesale.lotId());
            restriction = "l.lot_id = ?";
        } else {
            ids = ((RetailOrder) order).items().stream().map(item -> item.product().id()).toList();
            restriction = "p.product_id IN (" + String.join(", ", Collections.nCopies(ids.size(), "?")) + ")";
        }

        String sql = """
                SELECT p.product_id, p.product_name, p.unit, p.requires_expiry,
                       l.lot_id, l.quantity, l.unit_cost, l.expiry_date, l.source_name
                FROM stock_lots l
                JOIN products p ON p.product_id = l.product_id
                WHERE p.business_id = ? AND l.quantity > 0 AND
                """ + restriction + " ORDER BY l.lot_id FOR UPDATE OF l";
        try (var query = connection.prepareStatement(sql)) {
            query.setLong(1, business);
            for (int i = 0; i < ids.size(); i++) {
                query.setLong(i + 2, ids.get(i));
            }
            try (var rows = query.executeQuery()) {
                List<Entry> stock = new ArrayList<>();
                while (rows.next()) {
                    stock.add(MySqlStock.entry(rows));
                }
                return stock;
            }
        }
    }

    private static long insertSale(Connection connection, long business, UUID request, Quote quote) throws SQLException {
        boolean wholesale = quote.order() instanceof WholesaleOrder;
        try (var insert = connection.prepareStatement("""
                INSERT INTO sales (business_id, request_key, sale_type, buyer_name, sold_at, revenue, total_cost, gross_profit)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, business);
            insert.setString(2, request.toString());
            insert.setString(3, wholesale ? "WHOLESALE" : "RETAIL");
            insert.setString(4, wholesale ? ((WholesaleOrder) quote.order()).buyer().strip() : null);
            insert.setObject(5, quote.order().soldAt());
            insert.setBigDecimal(6, quote.revenue());
            insert.setBigDecimal(7, quote.cost());
            insert.setBigDecimal(8, quote.profit());
            insert.executeUpdate();
            return MySqlDatabase.generatedId(insert.getGeneratedKeys());
        }
    }

    private static void saveLines(Connection connection, long sale, Quote quote) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE stock_lots SET quantity = quantity - ? WHERE lot_id = ? AND quantity >= ?
                """);
             var item = connection.prepareStatement("""
                     INSERT INTO sale_items
                     (sale_id, lot_id, product_name, unit, quantity, unit_selling_price, unit_cost, line_total, line_cost)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """);
             var movement = connection.prepareStatement("""
                     INSERT INTO stock_movements (lot_id, quantity_delta, reason) VALUES (?, ?, ?)
                     """)) {
            for (var line : quote.lines()) {
                update.setBigDecimal(1, line.quantity());
                update.setLong(2, line.lotId());
                update.setBigDecimal(3, line.quantity());
                if (update.executeUpdate() != 1) {
                    throw new SaleException("Stock changed after review. Refresh stock and review the sale again.");
                }
                item.setLong(1, sale);
                item.setLong(2, line.lotId());
                item.setString(3, line.product().name());
                item.setString(4, line.product().unit().name());
                item.setBigDecimal(5, line.quantity());
                item.setBigDecimal(6, line.unitPrice());
                item.setBigDecimal(7, line.unitCost());
                item.setBigDecimal(8, line.revenue());
                item.setBigDecimal(9, line.cost());
                item.addBatch();

                movement.setLong(1, line.lotId());
                movement.setBigDecimal(2, line.quantity().negate());
                movement.setString(3, "Sale #" + sale);
                movement.addBatch();
            }
            item.executeBatch();
            movement.executeBatch();
        }
    }
}
