package osl.bl;

import java.sql.SQLException;
import java.util.List;
import osl.bl.AccountService.Session;
import osl.bl.ProfitTrendsService.Bucket;
import osl.bl.ProfitTrendsService.Point;
import osl.bl.ProfitTrendsService.ProductChoice;
import osl.bl.SalesPerformanceService.DateRange;
import osl.bl.SalesPerformanceService.Metrics;

public interface ProfitTrendsRepository {
    record Snapshot(List<Point> points, Metrics previous, List<ProductChoice> products) {}

    Snapshot load(Session session, DateRange range, DateRange previous, long productId, Bucket bucket)
            throws SQLException;
}
