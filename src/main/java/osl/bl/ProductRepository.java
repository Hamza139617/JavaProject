package osl.bl;

import java.sql.SQLException;
import java.util.List;
import osl.bl.AccountService.Session;
import osl.bl.ProductService.Details;
import osl.bl.ProductService.ProductRow;
import osl.bl.ProductService.Removal;

public interface ProductRepository {
    List<ProductRow> load(Session session) throws SQLException;

    long save(Session session, long productId, Details details) throws SQLException;

    Removal remove(Session session, long productId) throws SQLException;

    void restore(Session session, long productId) throws SQLException;
}
