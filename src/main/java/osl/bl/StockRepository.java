package osl.bl;

import java.sql.SQLException;
import java.util.List;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Entry;
import osl.bl.StockService.Product;
import osl.bl.StockService.Receipt;

public interface StockRepository {
    List<Entry> load(Session session) throws SQLException;

    long receive(Session session, Product product, Receipt receipt) throws SQLException;
}
