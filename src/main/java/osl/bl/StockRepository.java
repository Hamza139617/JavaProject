package osl.bl;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Entry;
import osl.bl.StockService.Product;
import osl.bl.StockService.Receipt;

public interface StockRepository {
    record StockData(List<Entry> entries, BigDecimal lowThreshold) {}

    StockData load(Session session) throws SQLException;

    long receive(Session session, Product product, Receipt receipt) throws SQLException;
}
