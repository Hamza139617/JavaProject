package osl.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.InventoryRepository;
import osl.bl.InventoryService.Board;
import osl.bl.InventoryService.InventoryException;
import osl.bl.InventoryService.Location;
import osl.bl.InventoryService.LocationDetails;
import osl.bl.InventoryService.Position;
import osl.bl.StockService.Unit;
import osl.db.MySqlDatabase.WorkspaceLock;

public final class MySqlInventory implements InventoryRepository {
    private static final String LOCATION = """
            SELECT l.location_id, l.storeroom, l.rack, l.bin, l.slot_capacity,
                (SELECT COUNT(*) FROM inventory_item_positions i WHERE i.location_id = l.location_id)
                + (SELECT COUNT(*) FROM inventory_stock_positions s WHERE s.location_id = l.location_id) AS used
            FROM inventory_locations l
            WHERE l.business_id = ?
            """;

    private enum InventoryType {
        RETAIL("inventory_item_positions", "product_id", "item", """
                SELECT p.product_id AS id, p.product_name, p.unit, COALESCE(SUM(s.quantity), 0) AS on_hand,
                       NULL AS expiry_date, p.archived_at, i.location_id
                FROM products p
                LEFT JOIN stock_lots s ON s.product_id = p.product_id
                LEFT JOIN inventory_item_positions i ON i.product_id = p.product_id
                WHERE p.business_id = ?
                GROUP BY p.product_id, p.product_name, p.unit, p.archived_at, i.location_id
                HAVING p.archived_at IS NULL OR on_hand > 0 OR i.location_id IS NOT NULL
                """, """
                SELECT i.location_id FROM products p
                LEFT JOIN inventory_item_positions i ON i.product_id = p.product_id
                WHERE p.business_id = ? AND p.product_id = ?
                """),
        WHOLESALE("inventory_stock_positions", "lot_id", "stock", """
                SELECT s.lot_id AS id, p.product_name, p.unit, s.quantity AS on_hand,
                       s.expiry_date, p.archived_at, i.location_id
                FROM products p
                JOIN stock_lots s ON s.product_id = p.product_id
                LEFT JOIN inventory_stock_positions i ON i.lot_id = s.lot_id
                WHERE p.business_id = ?
                """, """
                SELECT i.location_id FROM stock_lots s
                JOIN products p ON p.product_id = s.product_id
                LEFT JOIN inventory_stock_positions i ON i.lot_id = s.lot_id
                WHERE p.business_id = ? AND s.lot_id = ?
                """);

        private final String table;
        private final String key;
        private final String label;
        private final String positionsSql;
        private final String recordSql;

        InventoryType(String table, String key, String label, String positionsSql, String recordSql) {
            this.table = table;
            this.key = key;
            this.label = label;
            this.positionsSql = positionsSql;
            this.recordSql = recordSql;
        }

        static InventoryType forSession(Session session) {
            return session.role() == Role.RETAILER ? RETAIL : WHOLESALE;
        }
    }

    @Override
    public Board load(Session session) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.NONE);
            List<Position> positions = positions(connection, business, InventoryType.forSession(session));
            List<Location> locations = new ArrayList<>();
            try (var query = connection.prepareStatement(LOCATION + " ORDER BY l.storeroom, l.rack, l.bin")) {
                query.setLong(1, business);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) locations.add(location(rows));
                }
            }
            return new Board(positions, locations);
        });
    }

    @Override
    public long saveLocation(Session session, long locationId, LocationDetails details) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            if (locationId != 0 && findLocation(connection, business, locationId).used() > details.capacity()) {
                throw new InventoryException("Capacity cannot be smaller than the number of assigned slots.");
            }
            String sql = locationId == 0
                    ? "INSERT INTO inventory_locations (storeroom, rack, bin, slot_capacity, business_id) VALUES (?,?,?,?,?)"
                    : "UPDATE inventory_locations SET storeroom=?, rack=?, bin=?, slot_capacity=? WHERE business_id=? AND location_id=?";
            try (var save = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                save.setString(1, details.storeroom());
                save.setString(2, details.rack());
                save.setString(3, details.bin());
                save.setInt(4, details.capacity());
                save.setLong(5, business);
                if (locationId != 0) save.setLong(6, locationId);
                save.executeUpdate();
                return locationId == 0 ? MySqlDatabase.generatedId(save.getGeneratedKeys()) : locationId;
            } catch (SQLException failure) {
                if (failure.getErrorCode() == 1062) {
                    throw new InventoryException("That storeroom, rack, and bin combination already exists.");
                }
                throw failure;
            }
        });
    }

    @Override
    public void removeLocation(Session session, long locationId) throws SQLException {
        MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            if (findLocation(connection, business, locationId).used() > 0) {
                throw new InventoryException("Move or unassign the records at this location before removing it.");
            }
            try (var delete = connection.prepareStatement("DELETE FROM inventory_locations WHERE business_id=? AND location_id=?")) {
                delete.setLong(1, business);
                delete.setLong(2, locationId);
                delete.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void move(Session session, long recordId, Long locationId) throws SQLException {
        MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.EXCLUSIVE);
            InventoryType type = InventoryType.forSession(session);
            Long current = currentLocation(connection, business, type, recordId);
            if (java.util.Objects.equals(current, locationId)) return null;
            if (locationId != null && findLocation(connection, business, locationId).available() <= 0) {
                throw new InventoryException("That location is full. Choose another location or increase its capacity.");
            }
            String sql = locationId == null
                    ? "DELETE FROM " + type.table + " WHERE " + type.key + "=?"
                    : "INSERT INTO " + type.table + " (" + type.key + ", location_id) VALUES (?,?) AS incoming "
                        + "ON DUPLICATE KEY UPDATE location_id=incoming.location_id";
            try (var move = connection.prepareStatement(sql)) {
                move.setLong(1, recordId);
                if (locationId != null) move.setLong(2, locationId);
                move.executeUpdate();
            }
            return null;
        });
    }

    private static List<Position> positions(Connection connection, long business, InventoryType type) throws SQLException {
        try (var query = connection.prepareStatement(type.positionsSql + " ORDER BY product_name, id DESC")) {
            query.setLong(1, business);
            try (var rows = query.executeQuery()) {
                List<Position> positions = new ArrayList<>();
                while (rows.next()) {
                    positions.add(new Position(rows.getLong("id"), rows.getString("product_name"),
                            Unit.valueOf(rows.getString("unit")), rows.getBigDecimal("on_hand"),
                            rows.getObject("expiry_date", LocalDate.class), rows.getObject("archived_at") != null,
                            rows.getObject("location_id", Long.class)));
                }
                return positions;
            }
        }
    }

    private static Long currentLocation(Connection connection, long business, InventoryType type, long recordId)
            throws SQLException {
        try (var query = connection.prepareStatement(type.recordSql)) {
            query.setLong(1, business);
            query.setLong(2, recordId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new InventoryException("This " + type.label + " is unavailable. Refresh and select a current record.");
                return rows.getObject("location_id", Long.class);
            }
        }
    }

    private static Location findLocation(Connection connection, long business, long id) throws SQLException {
        try (var query = connection.prepareStatement(LOCATION + " AND l.location_id = ?")) {
            query.setLong(1, business);
            query.setLong(2, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new InventoryException("That storage location is unavailable. Refresh and choose a current location.");
                return location(rows);
            }
        }
    }

    private static Location location(ResultSet rows) throws SQLException {
        return new Location(rows.getLong("location_id"), new LocationDetails(rows.getString("storeroom"),
                rows.getString("rack"), rows.getString("bin"), rows.getInt("slot_capacity")), rows.getLong("used"));
    }
}