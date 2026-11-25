package osl.bl;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import osl.bl.AccountService.Session;
import osl.bl.SalesService.Quote;
import osl.bl.SalesService.SaleReceipt;
import osl.bl.StockService.Entry;

public interface SalesRepository {
    SaleReceipt record(Session session, UUID request, Quote preview, Function<List<Entry>, Quote> review)
            throws SQLException;
}
