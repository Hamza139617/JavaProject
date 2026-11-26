package osl.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.ReplenishmentRepository;
import static osl.bl.ReplenishmentService.SALES_WINDOW_DAYS;

import osl.bl.ReplenishmentService.Notice;
import osl.bl.ReplenishmentService.Plan;
import osl.bl.ReplenishmentService.PlanDetails;
import osl.bl.ReplenishmentService.ReplenishmentException;
import osl.bl.ReplenishmentService.Settings;
import osl.bl.ReplenishmentService.State;
import osl.bl.ReplenishmentService.Target;
import osl.bl.StockService.Product;
import osl.bl.StockService.Receipt;
import osl.db.MySqlDatabase.WorkspaceLock;

public final class MySqlReplenishment implements ReplenishmentRepository {
    private static final String PLAN_QUERY = """
            SELECT r.plan_id, r.product_id, r.replaced_lot_id, r.source_name, r.planned_quantity, r.expected_on,
                   r.status, r.received_lot_id, p.product_name, p.unit, p.requires_expiry, p.selling_price, p.archived_at
            FROM replenishment_plans r JOIN products p ON p.product_id = r.product_id
            WHERE r.business_id = ?
            """;

    @Override
    public Context load(Session session, LocalDate today) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.NONE);
            return new Context(settings(connection, business), plans(connection, business),
                    limits(connection, business), recentSales(connection, business, today));
        });
    }

    @Override
    public Map<Target, Notice> syncNotices(Session session, Set<Target> low, Settings settings, Instant now)
            throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            Map<Target, Notice> existing = notices(connection, business);
            Set<Target> active = settings.noticesEnabled() ? low : Set.of();
            try (var clear = connection.prepareStatement("""
                    DELETE FROM replenishment_notices WHERE business_id=? AND product_id=? AND lot_key=?
                    """)) {
                for (Target key : existing.keySet()) {
                    if (!active.contains(key)) {
                        targetParams(clear, business, key);
                        clear.addBatch();
                    }
                }
                clear.executeBatch();
            }
            existing.keySet().retainAll(active);
            Instant cutoff = now.minus(settings.reminderHours(), ChronoUnit.HOURS);
            for (Target key : active) {
                Notice old = existing.get(key);
                if (old == null) {
                    if (newNotice(connection, business, key, now)) {
                        existing.put(key, new Notice(now, 1, false));
                    }
                } else if (!old.acknowledged() && !old.lastShown().isAfter(cutoff)) {
                    try (var update = connection.prepareStatement("""
                            UPDATE replenishment_notices SET notified_at=?, notice_count=notice_count+1
                            WHERE business_id=? AND product_id=? AND lot_key=?
                            """)) {
                        update.setTimestamp(1, java.sql.Timestamp.from(now));
                        update.setLong(2, business);
                        update.setLong(3, key.productId());
                        update.setLong(4, key.lotId());
                        update.executeUpdate();
                    }
                    existing.put(key, new Notice(now, old.count() + 1, false));
                }
            }
            return Map.copyOf(existing);
        });
    }

    private static boolean newNotice(Connection connection, long business, Target target, Instant now)
            throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO replenishment_notices (business_id,product_id,lot_key,notified_at)
                SELECT p.business_id,p.product_id,?,? FROM products p
                WHERE p.business_id=? AND p.product_id=? AND p.archived_at IS NULL
                """)) {
            insert.setLong(1, target.lotId());
            insert.setTimestamp(2, java.sql.Timestamp.from(now));
            insert.setLong(3, business);
            insert.setLong(4, target.productId());
            return insert.executeUpdate() == 1;
        }
    }

    @Override
    public void acknowledge(Session session, Target target) throws SQLException {
        MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            try (var update = connection.prepareStatement("""
                    UPDATE replenishment_notices SET acknowledged=TRUE
                    WHERE business_id=? AND product_id=? AND lot_key=?
                    """)) {
                targetParams(update, business, target);
                update.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void saveSettings(Session session, Settings settings) throws SQLException {
        MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            try (var query = connection.prepareStatement("""
                    INSERT INTO replenishment_settings(business_id,low_threshold,notices_enabled,reminder_hours)
                    VALUES (?,?,?,?) AS incoming
                    ON DUPLICATE KEY UPDATE low_threshold=incoming.low_threshold,
                        notices_enabled=incoming.notices_enabled, reminder_hours=incoming.reminder_hours
                    """)) {
                query.setLong(1, business);
                query.setBigDecimal(2, settings.threshold());
                query.setBoolean(3, settings.noticesEnabled());
                query.setInt(4, settings.reminderHours());
                query.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void saveCapacity(Session session, Product product, BigDecimal limit) throws SQLException {
        MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            requireProduct(connection, business, product);
            String sql = limit == null ? "DELETE FROM replenishment_capacity WHERE product_id = ?"
                    : """
                      INSERT INTO replenishment_capacity(product_id,maximum_quantity) VALUES (?,?) AS incoming
                      ON DUPLICATE KEY UPDATE maximum_quantity=incoming.maximum_quantity
                      """;
            try (var update = connection.prepareStatement(sql)) {
                update.setLong(1, product.id());
                if (limit != null) update.setBigDecimal(2, limit);
                update.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public long savePlan(Session session, long id, Target target, Product product, PlanDetails details, LocalDate today)
            throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            requireProduct(connection, business, product);
            if (target.productId() != product.id() || ((target.lotId() == 0) != (session.role() == Role.RETAILER))) {
                throw new ReplenishmentException("Choose a valid replenishment target.");
            }
            if (id != 0) {
                Plan current = plan(connection, business, id);
                if (current.state() != State.PLANNED || !current.target().equals(target)) throw closedPlan();
                try (var update = connection.prepareStatement("""
                        UPDATE replenishment_plans SET source_name=?, planned_quantity=?, expected_on=?
                        WHERE business_id=? AND plan_id=?
                        """)) {
                    bindDetails(update, details);
                    update.setLong(4, business);
                    update.setLong(5, id);
                    update.executeUpdate();
                }
                return id;
            }
            checkTarget(connection, business, target, today);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO replenishment_plans(source_name,planned_quantity,expected_on,business_id,product_id,replaced_lot_id)
                    VALUES (?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                bindDetails(insert, details);
                insert.setLong(4, business);
                insert.setLong(5, target.productId());
                bindLot(insert, 6, target.lotId());
                insert.executeUpdate();
                return MySqlDatabase.generatedId(insert.getGeneratedKeys());
            }
        });
    }

    private static void checkTarget(Connection connection, long business, Target target, LocalDate today)
            throws SQLException {
        try (var check = connection.prepareStatement("""
                SELECT status FROM replenishment_plans
                WHERE business_id=? AND product_id=? AND replaced_lot_id <=> ?
                  AND (status='PLANNED' OR (replaced_lot_id IS NOT NULL AND status='RECEIVED'))
                LIMIT 1
                """)) {
            check.setLong(1, business);
            check.setLong(2, target.productId());
            bindLot(check, 3, target.lotId());
            try (var rows = check.executeQuery()) {
                if (rows.next()) throw new ReplenishmentException("This stock already has a replenishment plan or replacement.");
            }
        }
        String sql = """
                SELECT COUNT(*), COALESCE(SUM(IF(expiry_date IS NULL OR expiry_date >= ?, quantity, 0)), 0)
                FROM stock_lots WHERE product_id = ?
                """ + (target.lotId() == 0 ? "" : " AND lot_id = ?");
        try (var query = connection.prepareStatement(sql)) {
            query.setObject(1, today);
            query.setLong(2, target.productId());
            if (target.lotId() != 0) query.setLong(3, target.lotId());
            try (var rows = query.executeQuery()) {
                if (!rows.next() || (target.lotId() != 0 && rows.getLong(1) != 1)
                        || rows.getBigDecimal(2).compareTo(settings(connection, business).threshold()) > 0) {
                    throw new ReplenishmentException("This stock is no longer below the replenishment threshold. Refresh the view.");
                }
            }
        }
    }

    @Override
    public void cancel(Session session, long id) throws SQLException {
        MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            try (var query = connection.prepareStatement("""
                    UPDATE replenishment_plans SET status='CANCELLED'
                    WHERE business_id=? AND plan_id=? AND status='PLANNED'
                    """)) {
                query.setLong(1, business);
                query.setLong(2, id);
                if (query.executeUpdate() != 1) throw closedPlan();
            }
            return null;
        });
    }

    @Override
    public long receive(Session session, Plan quoted, Receipt receipt) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            Plan current = plan(connection, business, quoted.id());
            if (current.state() == State.RECEIVED) return current.receivedLot();
            if (current.state() != State.PLANNED) throw closedPlan();
            if (!current.details().equals(quoted.details()) || !current.target().equals(quoted.target())
                    || !current.details().source().equals(receipt.source())) {
                throw new ReplenishmentException("The plan changed. Refresh before receiving it.");
            }
            requireProduct(connection, business, quoted.product());
            long lot = MySqlStock.insertReceipt(connection, current.product(), receipt, "Replenishment #" + current.id());
            try (var update = connection.prepareStatement("""
                    UPDATE replenishment_plans SET status='RECEIVED', received_lot_id=?
                    WHERE business_id=? AND plan_id=?
                    """)) {
                update.setLong(1, lot);
                update.setLong(2, business);
                update.setLong(3, current.id());
                update.executeUpdate();
            }
            return lot;
        });
    }

    private static List<Plan> plans(Connection connection, long business) throws SQLException {
        try (var query = connection.prepareStatement(PLAN_QUERY + " ORDER BY r.plan_id DESC")) {
            query.setLong(1, business);
            try (var rows = query.executeQuery()) {
                List<Plan> plans = new ArrayList<>();
                while (rows.next()) plans.add(readPlan(rows));
                return plans;
            }
        }
    }

    private static Plan plan(Connection connection, long business, long id) throws SQLException {
        try (var query = connection.prepareStatement(PLAN_QUERY + " AND r.plan_id = ?")) {
            query.setLong(1, business);
            query.setLong(2, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw closedPlan();
                return readPlan(rows);
            }
        }
    }

    private static Plan readPlan(ResultSet row) throws SQLException {
        return new Plan(row.getLong("plan_id"), new Target(row.getLong("product_id"), row.getLong("replaced_lot_id")),
                MySqlStock.product(row), new PlanDetails(row.getString("source_name"), row.getBigDecimal("planned_quantity"),
                row.getObject("expected_on", LocalDate.class)), State.valueOf(row.getString("status")), row.getLong("received_lot_id"));
    }

    static Settings settings(Connection connection, long business) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT low_threshold,notices_enabled,reminder_hours FROM replenishment_settings WHERE business_id=?
                """)) {
            query.setLong(1, business);
            try (var rows = query.executeQuery()) {
                return rows.next() ? new Settings(rows.getBigDecimal(1), rows.getBoolean(2), rows.getInt(3)) : Settings.DEFAULT;
            }
        }
    }

    private static Map<Long, BigDecimal> limits(Connection connection, long business) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT p.product_id,c.maximum_quantity FROM products p JOIN replenishment_capacity c USING(product_id)
                WHERE p.business_id=?
                """)) {
            query.setLong(1, business);
            return values(query);
        }
    }

    private static Map<Long, BigDecimal> recentSales(Connection connection, long business, LocalDate today)
            throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT l.product_id,SUM(i.quantity) FROM sales s
                JOIN sale_items i ON i.sale_id=s.sale_id JOIN stock_lots l ON l.lot_id=i.lot_id
                WHERE s.business_id=? AND s.sold_at>=? AND s.sold_at<?
                GROUP BY l.product_id
                """)) {
            query.setLong(1, business);
            query.setObject(2, today.minusDays(SALES_WINDOW_DAYS - 1).atStartOfDay());
            query.setObject(3, today.plusDays(1).atStartOfDay());
            return values(query);
        }
    }

    private static Map<Long, BigDecimal> values(PreparedStatement query) throws SQLException {
        try (var rows = query.executeQuery()) {
            Map<Long, BigDecimal> values = new HashMap<>();
            while (rows.next()) values.put(rows.getLong(1), rows.getBigDecimal(2));
            return values;
        }
    }

    private static Map<Target, Notice> notices(Connection connection, long business) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT product_id,lot_key,notified_at,notice_count,acknowledged
                FROM replenishment_notices WHERE business_id=?
                """)) {
            query.setLong(1, business);
            try (var rows = query.executeQuery()) {
                Map<Target, Notice> existing = new HashMap<>();
                while (rows.next()) existing.put(new Target(rows.getLong(1), rows.getLong(2)),
                        new Notice(rows.getTimestamp(3).toInstant(), rows.getInt(4), rows.getBoolean(5)));
                return existing;
            }
        }
    }

    private static void targetParams(PreparedStatement query, long business, Target target) throws SQLException {
        query.setLong(1, business);
        query.setLong(2, target.productId());
        query.setLong(3, target.lotId());
    }

    private static void bindDetails(PreparedStatement query, PlanDetails details) throws SQLException {
        query.setString(1, details.source());
        query.setBigDecimal(2, details.quantity());
        query.setObject(3, details.expectedOn());
    }

    private static void bindLot(PreparedStatement query, int index, long lot) throws SQLException {
        if (lot == 0) query.setNull(index, java.sql.Types.BIGINT);
        else query.setLong(index, lot);
    }

    private static void requireProduct(Connection connection, long business, Product product) throws SQLException {
        if (!MySqlStock.matchesProduct(connection, business, product)) {
            throw new ReplenishmentException("The product changed or is no longer active. Refresh the view.");
        }
    }

    private static ReplenishmentException closedPlan() {
        return new ReplenishmentException("This replenishment plan is unavailable or already closed.");
    }
}
