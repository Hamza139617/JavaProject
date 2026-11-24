package osl.bl;

import java.sql.SQLException;
import java.util.List;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Entry;
import osl.bl.StockService.Product;
import osl.bl.StockService.Receipt;
import osl.bl.StockService.Unit;

public interface StockRepository {
    List<Entry> load(Session session) throws SQLException;

    long addProduct(Session session, String name, Unit unit, boolean requiresExpiry) throws SQLException;

    long receive(Session session, Product product, Receipt receipt) throws SQLException;
}
