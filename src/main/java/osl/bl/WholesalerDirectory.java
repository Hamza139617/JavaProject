package osl.bl;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Unit;

public final class WholesalerDirectory {
    public enum Sort {
        NAME("Business name"), LOWEST_RATE("Lowest listed rate"), RECENT("Recently updated");

        private final String label;
        Sort(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum ReportReason {
        MISLEADING_DETAILS("Misleading details"), INCORRECT_RATE("Incorrect rate"),
        INAPPROPRIATE_CONTENT("Inappropriate content"), OTHER("Other");

        private final String label;
        ReportReason(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record Search(String keyword, String city, String category, BigDecimal maximumRate, Sort sort) {}

    public record Summary(long businessId, String businessName, String username, String city, String description,
                          long matchingListings, BigDecimal lowestRate, Instant latestUpdate) {}

    public record PublicListing(long productId, String product, Unit unit, String category, BigDecimal rate,
                                Instant updatedAt) {}

    public record PublicProfile(long businessId, String businessName, String contactName, String username,
                                String city, String address, String phone, String description,
                                Instant updatedAt, List<PublicListing> listings) {
        public PublicProfile { listings = List.copyOf(listings); }
    }

    public record ProfileSettings(boolean published, String city, String address, String description,
                                  boolean showPhone) {}

    public static final class DirectoryException extends RuntimeException {
        public DirectoryException(String message) { super(message); }
    }

    private final WholesalerDirectoryRepository repository;

    public WholesalerDirectory(WholesalerDirectoryRepository repository) { this.repository = repository; }

    public List<Summary> search(Session session, String keyword, String city, String category, String maxRate,
                                Sort sort) throws SQLException {
        require(session, Role.RETAILER);
        Search criteria = new Search(text(keyword, 120, "Search term"), text(city, 100, "City"),
                text(category, 80, "Category"), maxRate.isBlank() ? null : rate(maxRate),
                sort == null ? Sort.NAME : sort);
        return repository.search(session, criteria);
    }

    public Optional<PublicProfile> profile(Session session, long businessId) throws SQLException {
        require(session, Role.RETAILER);
        return repository.profile(session, businessId);
    }

    public ProfileSettings settings(Session session) throws SQLException {
        require(session, Role.WHOLESALER);
        return repository.settings(session);
    }

    public void saveProfile(Session session, ProfileSettings settings) throws SQLException {
        require(session, Role.WHOLESALER);
        String city = text(settings.city(), 100, "City");
        if (settings.published() && city.isEmpty()) {
            throw new DirectoryException("Enter a city before publishing your profile.");
        }
        repository.saveProfile(session, new ProfileSettings(settings.published(), city,
                text(settings.address(), 200, "Public address"), text(settings.description(), 1000, "About"),
                settings.showPhone()));
    }

    public void report(Session session, long businessId, long productId, ReportReason reason, String note)
            throws SQLException {
        require(session, Role.RETAILER);
        if (reason == null) throw new DirectoryException("Choose a reason for the report.");
        String comments = text(note, 500, "Comments");
        try {
            if (!repository.report(session, businessId, productId, reason, comments)) {
                throw new DirectoryException("This listing is no longer available. Return to search for updated results.");
            }
        } catch (SQLException failure) {
            if (failure.getErrorCode() == 1062) {
                throw new DirectoryException("You have already reported this listing.");
            }
            throw failure;
        }
    }

    private static BigDecimal rate(String value) {
        BigDecimal rate;
        try {
            rate = StockService.decimal(value, 2, 11, "Enter a rate in PKR with up to two decimal places.");
        } catch (StockService.StockException invalid) {
            throw new DirectoryException(invalid.getMessage());
        }
        if (rate.signum() <= 0) throw new DirectoryException("The rate must be greater than zero.");
        return rate;
    }

    private static String text(String value, int limit, String label) {
        String clean = value.strip();
        if (clean.length() > limit) throw new DirectoryException(label + " must be at most " + limit + " characters.");
        return clean;
    }

    private static void require(Session session, Role role) {
        if (session == null || session.role() != role) {
            throw new DirectoryException(role == Role.RETAILER
                    ? "Wholesaler search is available to retailers." : "Public listing settings are for wholesalers.");
        }
    }
}
