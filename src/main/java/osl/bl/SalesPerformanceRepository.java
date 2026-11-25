package osl.bl;

import java.sql.SQLException;
import java.util.List;
import osl.bl.AccountService.Session;
import osl.bl.SalesPerformanceService.DateRange;
import osl.bl.SalesPerformanceService.ItemMonth;
import osl.bl.SalesPerformanceService.Metrics;
import osl.bl.SalesPerformanceService.MonthSales;
import osl.bl.SalesPerformanceService.SaleEvent;
import osl.bl.SalesPerformanceService.StockSale;

public interface SalesPerformanceRepository {
    record Snapshot(List<MonthSales> monthly, Metrics previous, List<ItemMonth> itemMonths,
                    List<StockSale> stocks, List<SaleEvent> history) {}

    Snapshot load(Session session, DateRange selected, DateRange previous) throws SQLException;
}
