package osl.db;

import static osl.db.MySqlSalesPerformance.FILTER;
import static osl.db.MySqlSalesPerformance.bind;
import static osl.db.MySqlSalesPerformance.metrics;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.ProfitTrendsRepository;
import osl.bl.ProfitTrendsService.Bucket;
import osl.bl.ProfitTrendsService.Point;
import osl.bl.ProfitTrendsService.ProductChoice;
import osl.bl.SalesPerformanceService.DateRange;
import osl.bl.SalesPerformanceService.Metrics;
import osl.bl.StockService.Unit;
import osl.db.MySqlDatabase.WorkspaceLock;

public final class MySqlProfitTrends implements ProfitTrendsRepository {
    @Override
    public Snapshot load(Session session, DateRange range, DateRange previous, long productId, Bucket bucket)
            throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.NONE);
            String saleType = session.role() == Role.RETAILER ? "RETAIL" : "WHOLESALE";
            List<Point> points = totals(connection, business, saleType, range, productId, bucket);
            Metrics comparison = totals(connection, business, saleType, previous, productId, null).getFirst().metrics();
            return new Snapshot(points, comparison, products(connection, business));
        });
    }

    private static List<Point> totals(Connection connection, long business, String saleType,
                                      DateRange range, long productId, Bucket bucket) throws SQLException {
        String start = bucket == null ? "NULL" : switch (bucket) {
            case DAY -> "DATE(s.sold_at)";
            case MONTH -> "CAST(DATE_FORMAT(s.sold_at, '%Y-%m-01') AS DATE)";
            case YEAR -> "MAKEDATE(YEAR(s.sold_at), 1)";
        };
        boolean product = productId != 0;
        String fields = product
                ? """
                  COUNT(DISTINCT s.sale_id) AS sale_count,
                  COALESCE(SUM(i.line_total), 0) AS revenue, COALESCE(SUM(i.line_cost), 0) AS cost
                  """
                : """
                  COUNT(*) AS sale_count,
                  COALESCE(SUM(s.revenue), 0) AS revenue, COALESCE(SUM(s.total_cost), 0) AS cost
                  """;
        String sql = "SELECT " + start + " AS start_date, " + fields + " FROM sales s\n"
                + (product ? """
                        JOIN sale_items i ON i.sale_id = s.sale_id
                        JOIN stock_lots l ON l.lot_id = i.lot_id
                        """ : "") + FILTER + (product ? " AND l.product_id = ?" : "")
                + (bucket == null ? "" : " GROUP BY start_date ORDER BY start_date");
        try (var query = connection.prepareStatement(sql)) {
            bind(query, business, saleType, range);
            if (product) query.setLong(5, productId);
            try (var rows = query.executeQuery()) {
                List<Point> values = new ArrayList<>();
                while (rows.next()) {
                    LocalDate date = bucket == null ? range.from() : rows.getObject("start_date", LocalDate.class);
                    values.add(new Point(date, metrics(rows)));
                }
                return values;
            }
        }
    }

    private static List<ProductChoice> products(Connection connection, long business) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT p.product_id, p.product_name, p.unit, p.archived_at IS NOT NULL AS archived
                FROM products p
                WHERE p.business_id = ? AND (p.archived_at IS NULL OR EXISTS (
                    SELECT 1 FROM stock_lots l JOIN sale_items i ON i.lot_id = l.lot_id
                    WHERE l.product_id = p.product_id
                ))
                ORDER BY p.product_name, p.product_id
                """)) {
            query.setLong(1, business);
            try (var rows = query.executeQuery()) {
                List<ProductChoice> products = new ArrayList<>();
                while (rows.next()) {
                    products.add(new ProductChoice(rows.getLong("product_id"), rows.getString("product_name"),
                            Unit.valueOf(rows.getString("unit")), rows.getBoolean("archived")));
                }
                return products;
            }
        }
    }
}
