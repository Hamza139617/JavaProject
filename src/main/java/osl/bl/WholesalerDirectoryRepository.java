package osl.bl;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import osl.bl.AccountService.Session;
import osl.bl.WholesalerDirectory.CatalogListing;
import osl.bl.WholesalerDirectory.ProfileSettings;
import osl.bl.WholesalerDirectory.PublicProfile;
import osl.bl.WholesalerDirectory.ReportReason;
import osl.bl.WholesalerDirectory.Search;
import osl.bl.WholesalerDirectory.Summary;

public interface WholesalerDirectoryRepository {
    List<Summary> search(Session retailer, Search search) throws SQLException;
    Optional<PublicProfile> profile(Session retailer, long businessId) throws SQLException;
    ProfileSettings settings(Session wholesaler) throws SQLException;
    List<CatalogListing> catalog(Session wholesaler) throws SQLException;
    void saveProfile(Session wholesaler, ProfileSettings settings) throws SQLException;
    boolean saveListing(Session wholesaler, long productId, String category, BigDecimal rate) throws SQLException;
    boolean unlist(Session wholesaler, long productId) throws SQLException;
    boolean report(Session retailer, long businessId, long productId, ReportReason reason, String comments)
            throws SQLException;
}
