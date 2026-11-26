package osl.bl;

import java.sql.SQLException;
import osl.bl.AccountService.Session;
import osl.bl.InventoryService.Board;
import osl.bl.InventoryService.LocationDetails;

public interface InventoryRepository {
    Board load(Session session) throws SQLException;

    long saveLocation(Session session, long locationId, LocationDetails details) throws SQLException;

    void removeLocation(Session session, long locationId) throws SQLException;

    void move(Session session, long recordId, Long locationId) throws SQLException;
}