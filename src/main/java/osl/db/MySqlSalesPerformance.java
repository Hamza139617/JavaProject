package osl.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.SalesPerformanceRepository;
import osl.bl.SalesPerformanceService.DateRange;
import osl.bl.SalesPerformanceService.ItemMonth;
import osl.bl.SalesPerformanceService.Metrics;
import osl.bl.SalesPerformanceService.MonthSales;
import osl.bl.SalesPerformanceService.SaleEvent;
import osl.bl.SalesPerformanceService.StockSale;
import osl.bl.StockService.Unit;
import osl.db.MySqlDatabase.WorkspaceLock;

public final class MySqlSalesPerformance implements SalesPerformanceRepository {
    static final String FILTER = """
            WHERE s.business_id = ? AND s.sale_type = ? AND s.sold_at >= ? AND s.sold_at < ?
            """;

    @Override
    public Snapshot load(Session session, DateRange selected, DateRange previous) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.NONE);
            String saleType = session.role() == Role.RETAILER ? "RETAIL" : "WHOLESALE";
            List<MonthSales> monthly = monthly(connection, business, saleType, selected);
            Metrics prior = comparison(connection, business, saleType, previous);
            if (session.role() == Role.RETAILER) {
                return new Snapshot(monthly, prior, itemMonths(connection, business, selected), List.of(),
                        history(connection, business, selected));
            }
            return new Snapshot(monthly, prior, List.of(), stocks(connection, business, selected), List.of());
        });
    }

    private static List<MonthSales> monthly(Connection connection, long business, String saleType, DateRange range)
            throws SQLException {
        String sql = """
                SELECT EXTRACT(YEAR_MONTH FROM s.sold_at) AS month_key, COUNT(*) AS sale_count,
                       SUM(s.revenue) AS revenue, SUM(s.total_cost) AS cost
                FROM sales s
                """ + FILTER + " GROUP BY month_key ORDER BY month_key";
        try (var query = connection.prepareStatement(sql)) {
            bind(query, business, saleType, range);
            try (var rows = query.executeQuery()) {
                List<MonthSales> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(new MonthSales(month(rows), metrics(rows)));
                }
                return values;
            }
        }
    }

    private static Metrics comparison(Connection connection, long business, String saleType, DateRange range)
            throws SQLException {
        String sql = """
                SELECT COUNT(*) AS sale_count, COALESCE(SUM(s.revenue), 0) AS revenue,
                       COALESCE(SUM(s.total_cost), 0) AS cost
                FROM sales s
                """ + FILTER;
        try (var query = connection.prepareStatement(sql)) {
            bind(query, business, saleType, range);
            try (var rows = query.executeQuery()) {
                return rows.next() ? metrics(rows) : Metrics.ZERO;
            }
        }
    }

    private static List<ItemMonth> itemMonths(Connection connection, long business, DateRange range) throws SQLException {
        String sql = """
                SELECT l.product_id,
                       CONCAT(MAX(i.product_name), IF(MAX(p.archived_at IS NOT NULL), ' (archived)', '')) AS product_name,
                       i.unit,
                       EXTRACT(YEAR_MONTH FROM s.sold_at) AS month_key,
                       SUM(i.quantity) AS quantity, SUM(i.line_total) AS revenue,
                       SUM(i.line_cost) AS cost, MAX(s.sold_at) AS latest
                FROM sales s
                JOIN sale_items i ON i.sale_id = s.sale_id
                JOIN stock_lots l ON l.lot_id = i.lot_id
                JOIN products p ON p.product_id = l.product_id
                """ + FILTER + " GROUP BY l.product_id, i.unit, month_key";
        try (var query = connection.prepareStatement(sql)) {
            bind(query, business, "RETAIL", range);
            try (var rows = query.executeQuery()) {
                List<ItemMonth> items = new ArrayList<>();
                while (rows.next()) {
                    items.add(new ItemMonth(rows.getLong("product_id"), rows.getString("product_name"),
                            Unit.valueOf(rows.getString("unit")), month(rows), rows.getBigDecimal("quantity"),
                            rows.getBigDecimal("revenue"), rows.getBigDecimal("cost"),
                            rows.getObject("latest", LocalDateTime.class)));
                }
                return items;
            }
        }
    }

    private static List<SaleEvent> history(Connection connection, long business, DateRange range) throws SQLException {
        String sql = """
                SELECT s.sale_id, s.sold_at, s.payment_type, s.revenue, s.total_cost
                FROM sales s
                """ + FILTER + " ORDER BY s.sold_at DESC, s.sale_id DESC";
        try (var query = connection.prepareStatement(sql)) {
            bind(query, business, "RETAIL", range);
            try (var rows = query.executeQuery()) {
                List<SaleEvent> sales = new ArrayList<>();
                while (rows.next()) {
                    sales.add(new SaleEvent(rows.getLong("sale_id"), rows.getObject("sold_at", LocalDateTime.class),
                            rows.getString("payment_type"), rows.getBigDecimal("revenue"), rows.getBigDecimal("total_cost")));
                }
                return sales;
            }
        }
    }

    private static List<StockSale> stocks(Connection connection, long business, DateRange range) throws SQLException {
        String sql = """
                SELECT i.lot_id,
                       CONCAT(i.product_name, IF(p.archived_at IS NOT NULL, ' (archived)', '')) AS product_name,
                       s.buyer_name, i.unit, i.quantity,
                       s.sold_at, i.line_total, i.line_cost
                FROM sales s
                JOIN sale_items i ON i.sale_id = s.sale_id
                JOIN stock_lots l ON l.lot_id = i.lot_id
                JOIN products p ON p.product_id = l.product_id
                """ + FILTER + " ORDER BY i.line_total DESC, s.sold_at DESC, i.lot_id DESC";
        try (var query = connection.prepareStatement(sql)) {
            bind(query, business, "WHOLESALE", range);
            try (var rows = query.executeQuery()) {
                List<StockSale> stocks = new ArrayList<>();
                while (rows.next()) {
                    stocks.add(new StockSale(rows.getLong("lot_id"), rows.getString("product_name"),
                            rows.getString("buyer_name"), Unit.valueOf(rows.getString("unit")),
                            rows.getBigDecimal("quantity"), rows.getObject("sold_at", LocalDateTime.class),
                            rows.getBigDecimal("line_total"), rows.getBigDecimal("line_cost")));
                }
                return stocks;
            }
        }
    }

    static void bind(PreparedStatement query, long business, String saleType, DateRange range)
            throws SQLException {
        query.setLong(1, business);
        query.setString(2, saleType);
        query.setObject(3, range.from().atStartOfDay());
        query.setObject(4, range.to().plusDays(1).atStartOfDay());
    }

    static Metrics metrics(ResultSet rows) throws SQLException {
        return new Metrics(rows.getLong("sale_count"), rows.getBigDecimal("revenue"), rows.getBigDecimal("cost"));
    }

    private static YearMonth month(ResultSet rows) throws SQLException {
        int key = rows.getInt("month_key");
        return YearMonth.of(key / 100, key % 100);
    }
}
