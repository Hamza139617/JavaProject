package osl.bl;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Unit;

public final class InventoryService {
    public record LocationDetails(String storeroom, String rack, String bin, int capacity) {
        public String path() {
            return storeroom + (rack.isEmpty() ? "" : " / Rack " + rack) + (bin.isEmpty() ? "" : " / Bin " + bin);
        }
    }

    public record Location(long id, LocationDetails details, long used) {
        public long available() { return details.capacity() - used; }
    }

    public record Position(long id, String productName, Unit unit, BigDecimal onHand, LocalDate expiry,
                           boolean archived, Long locationId) {}

    public record Board(List<Position> positions, List<Location> locations) {
        public Board {
            positions = List.copyOf(positions);
            locations = List.copyOf(locations);
        }
    }

    public static final class InventoryException extends RuntimeException {
        public InventoryException(String message) { super(message); }
    }

    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    public Board load(Session session) throws SQLException {
        requireBusiness(session);
        return repository.load(session);
    }

    public long saveLocation(Session session, long locationId, String storeroom, String rack, String bin,
                             String capacity) throws SQLException {
        requireBusiness(session);
        String room = name(storeroom, 80, "storeroom", true);
        String rackName = name(rack, 60, "rack", false);
        String binName = name(bin, 60, "bin", false);
        String slots = capacity.strip();
        if (!slots.matches("[1-9][0-9]{0,5}")) {
            throw new InventoryException("Enter a capacity between 1 and 999,999 slots.");
        }
        return repository.saveLocation(session, locationId,
                new LocationDetails(room, rackName, binName, Integer.parseInt(slots)));
    }

    public void removeLocation(Session session, long locationId) throws SQLException {
        requireBusiness(session);
        repository.removeLocation(session, locationId);
    }

    public void move(Session session, long recordId, Long locationId) throws SQLException {
        requireBusiness(session);
        repository.move(session, recordId, locationId);
    }

    private static String name(String value, int limit, String field, boolean required) {
        String clean = value.strip().replaceAll(" +", " ");
        if (clean.isEmpty() && !required) return "";
        if (clean.length() > limit || !clean.matches("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} ._()&'-]*")) {
            throw new InventoryException("Enter a valid " + field + " label of up to " + limit
                    + " characters. Start with a letter or number.");
        }
        return clean;
    }

    private static void requireBusiness(Session session) {
        if (session == null || (session.role() != Role.RETAILER && session.role() != Role.WHOLESALER)) {
            throw new InventoryException("Track Inventory is available in retailer and wholesaler workspaces.");
        }
    }
}