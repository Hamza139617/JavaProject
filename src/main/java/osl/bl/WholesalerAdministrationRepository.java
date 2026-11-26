package osl.bl;

import java.sql.SQLException;
import java.util.List;
import osl.bl.AccountService.Session;
import osl.bl.WholesalerAdministration.AccountRow;
import osl.bl.WholesalerAdministration.Change;
import osl.bl.WholesalerAdministration.Search;
import osl.bl.WholesalerAdministration.Status;

public interface WholesalerAdministrationRepository {
    List<AccountRow> search(Session administrator, Search search, int limit) throws SQLException;

    List<Change> history(Session administrator, long accountId) throws SQLException;

    void changeStatus(Session administrator, long accountId, Status expected, Status target, String reason)
            throws SQLException;
}