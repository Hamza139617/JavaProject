package osl.bl;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import osl.bl.AccountService.Session;
import osl.bl.ReplenishmentService.*;
import osl.bl.StockService.Product;
import osl.bl.StockService.Receipt;

public interface ReplenishmentRepository {
    record Context(Settings settings, List<Plan> plans, Map<Long, BigDecimal> capacities,
                   Map<Long, BigDecimal> recentSales) {}

    Context load(Session session, LocalDate today) throws SQLException;

    Map<Target, Notice> syncNotices(Session session, Set<Target> low, Settings settings, Instant now)
            throws SQLException;

    void acknowledge(Session session, Target target) throws SQLException;

    void saveSettings(Session session, Settings settings) throws SQLException;

    void saveCapacity(Session session, Product product, BigDecimal limit) throws SQLException;

    long savePlan(Session session, long id, Target target, Product product, PlanDetails details, LocalDate today)
            throws SQLException;

    void cancel(Session session, long id) throws SQLException;

    long receive(Session session, Plan plan, Receipt receipt) throws SQLException;
}
