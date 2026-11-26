package osl.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Statement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import osl.bl.AccountService.Session;
import osl.bl.StockService.Unit;
import osl.bl.WholesalerDirectory.ProfileSettings;
import osl.bl.WholesalerDirectory.PublicListing;
import osl.bl.WholesalerDirectory.PublicProfile;
import osl.bl.WholesalerDirectory.ReportReason;
import osl.bl.WholesalerDirectory.Search;
import osl.bl.WholesalerDirectory.Summary;
import osl.bl.WholesalerDirectoryRepository;
import osl.db.MySqlDatabase.WorkspaceLock;

public final class MySqlWholesalerDirectory implements WholesalerDirectoryRepository {
    private static final String DIRECTORY_FROM = """
            FROM wholesaler_profiles w
            JOIN businesses b ON b.business_id = w.business_id
            JOIN user_accounts a ON a.account_id = b.account_id
            """;
    private static final String PUBLIC = "w.is_published = TRUE AND a.role = 'WHOLESALER' AND a.account_status = 'ACTIVE'";
    private static final String NOT_MODERATED = """
             AND NOT EXISTS (
                 SELECT 1 FROM wholesaler_listing_reports r
                 JOIN wholesaler_report_reviews v ON v.report_id=r.report_id
                 WHERE r.product_id=l.product_id AND v.outcome='HIDDEN'
             )
            """;

    @Override
    public List<Summary> search(Session session, Search search) throws SQLException {
        String order = switch (search.sort()) {
            case NAME -> "b.business_name, b.business_id";
            case LOWEST_RATE -> "lowest_rate IS NULL, lowest_rate, b.business_name, b.business_id";
            case RECENT -> "latest_update DESC, b.business_name, b.business_id";
        };
        String sql = """
                SELECT b.business_id, b.business_name, a.username, w.city, w.description,
                       COUNT(visible.product_id) AS listings, MIN(visible.rate) AS lowest_rate,
                       GREATEST(w.updated_at, COALESCE(MAX(visible.updated_at), w.updated_at)) AS latest_update
                """ + DIRECTORY_FROM + """
                LEFT JOIN (
                    SELECT p.business_id, p.product_id, p.product_name, l.category, l.rate, l.updated_at
                    FROM wholesaler_listings l
                    JOIN products p ON p.product_id = l.product_id
                    WHERE l.is_listed = TRUE AND p.archived_at IS NULL
                """ + NOT_MODERATED + """
                ) visible ON visible.business_id = b.business_id
                WHERE
                """ + PUBLIC + """
                 AND LOCATE(?, w.city) > 0
                 AND (? = '' OR LOCATE(?, visible.category) > 0)
                 AND (? IS NULL OR visible.rate <= ?)
                 AND (? = '' OR LOCATE(?, b.business_name) > 0 OR LOCATE(?, LOWER(a.username)) > 0
                      OR LOCATE(?, a.full_name) > 0 OR LOCATE(?, w.description) > 0
                      OR LOCATE(?, visible.product_name) > 0 OR LOCATE(?, visible.category) > 0)
                GROUP BY b.business_id, b.business_name, a.username, w.city, w.description, w.updated_at
                ORDER BY
                """ + " " + order;
        try (var connection = MySqlDatabase.open()) {
            MySqlDatabase.workspace(connection, session, WorkspaceLock.NONE);
            try (var query = connection.prepareStatement(sql)) {
                query.setString(1, search.city());
                query.setString(2, search.category());
                query.setString(3, search.category());
                query.setBigDecimal(4, search.maximumRate());
                query.setBigDecimal(5, search.maximumRate());
                String username = search.keyword();
                if (username.startsWith("@") && username.length() > 1) {
                    username = username.substring(1).strip();
                }
                for (int parameter = 6; parameter <= 12; parameter++) {
                    query.setString(parameter, parameter == 8
                            ? username.toLowerCase(Locale.ROOT) : search.keyword());
                }
                try (var rows = query.executeQuery()) {
                    List<Summary> summaries = new ArrayList<>();
                    while (rows.next()) {
                        summaries.add(new Summary(rows.getLong("business_id"), rows.getString("business_name"),
                                rows.getString("username"), rows.getString("city"), rows.getString("description"),
                                rows.getLong("listings"), rows.getBigDecimal("lowest_rate"), time(rows, "latest_update")));
                    }
                    return summaries;
                }
            }
        }
    }

    @Override
    public boolean hasPublishedProfiles(Session session) throws SQLException {
        try (var connection = MySqlDatabase.open()) {
            MySqlDatabase.workspace(connection, session, WorkspaceLock.NONE);
            try (var query = connection.prepareStatement("SELECT 1 " + DIRECTORY_FROM + " WHERE " + PUBLIC + " LIMIT 1");
                 var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    @Override
    public Optional<PublicProfile> profile(Session session, long businessId) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            MySqlDatabase.workspace(connection, session, WorkspaceLock.NONE);
            String sql = """
                    SELECT b.business_name, a.full_name, a.username, w.city, w.public_address, w.description,
                           CASE WHEN w.show_phone = TRUE THEN a.phone END AS public_phone, w.updated_at
                    """ + DIRECTORY_FROM + " WHERE " + PUBLIC + " AND b.business_id = ?";
            try (var query = connection.prepareStatement(sql)) {
                query.setLong(1, businessId);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    return Optional.of(new PublicProfile(businessId, rows.getString("business_name"),
                            rows.getString("full_name"), rows.getString("username"), rows.getString("city"),
                            rows.getString("public_address"), rows.getString("public_phone"),
                            rows.getString("description"), time(rows, "updated_at"), publicListings(connection, businessId)));
                }
            }
        });
    }

    private static List<PublicListing> publicListings(Connection connection, long business) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT p.product_id, p.product_name, p.unit, l.category, l.rate, l.updated_at
                FROM products p JOIN wholesaler_listings l ON l.product_id = p.product_id
                WHERE p.business_id = ? AND l.is_listed = TRUE AND p.archived_at IS NULL
                """ + NOT_MODERATED + """
                ORDER BY p.product_name, p.product_id
                """)) {
            query.setLong(1, business);
            try (var rows = query.executeQuery()) {
                List<PublicListing> listings = new ArrayList<>();
                while (rows.next()) {
                    listings.add(new PublicListing(rows.getLong("product_id"), rows.getString("product_name"),
                            Unit.valueOf(rows.getString("unit")), rows.getString("category"), rows.getBigDecimal("rate"),
                            time(rows, "updated_at")));
                }
                return listings;
            }
        }
    }

    @Override
    public ProfileSettings settings(Session session) throws SQLException {
        try (var connection = MySqlDatabase.open()) {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.NONE);
            try (var query = connection.prepareStatement("""
                    SELECT is_published, city, public_address, description, show_phone
                    FROM wholesaler_profiles WHERE business_id = ?
                    """)) {
                query.setLong(1, business);
                try (var row = query.executeQuery()) {
                    return row.next() ? new ProfileSettings(row.getBoolean("is_published"), row.getString("city"),
                            row.getString("public_address"), row.getString("description"), row.getBoolean("show_phone"))
                            : new ProfileSettings(false, "", "", "", false);
                }
            }
        }
    }

    @Override
    public void saveProfile(Session session, ProfileSettings settings) throws SQLException {
        MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.SHARED);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO wholesaler_profiles (business_id, is_published, city, public_address, description, show_phone)
                    VALUES (?, ?, ?, ?, ?, ?) AS incoming
                    ON DUPLICATE KEY UPDATE is_published = incoming.is_published, city = incoming.city,
                        public_address = incoming.public_address, description = incoming.description,
                        show_phone = incoming.show_phone, updated_at = CURRENT_TIMESTAMP(6)
                    """)) {
                insert.setLong(1, business);
                insert.setBoolean(2, settings.published());
                insert.setString(3, settings.city());
                insert.setString(4, settings.address());
                insert.setString(5, settings.description());
                insert.setBoolean(6, settings.showPhone());
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean report(Session session, long businessId, long productId, ReportReason reason, String comments)
            throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long reporter = MySqlDatabase.workspace(connection, session, WorkspaceLock.SHARED);
            String sql = "SELECT p.product_name, l.category, l.rate " + DIRECTORY_FROM + """
                    JOIN products p ON p.business_id = b.business_id
                    JOIN wholesaler_listings l ON l.product_id = p.product_id
                    WHERE
                    """ + PUBLIC + " AND b.business_id = ? AND l.product_id = ? AND l.is_listed = TRUE AND p.archived_at IS NULL" + NOT_MODERATED + " FOR SHARE";
            String product;
            String category;
            BigDecimal rate;
            try (var query = connection.prepareStatement(sql)) {
                query.setLong(1, businessId);
                query.setLong(2, productId);
                try (var row = query.executeQuery()) {
                    if (!row.next()) return false;
                    product = row.getString("product_name");
                    category = row.getString("category");
                    rate = row.getBigDecimal("rate");
                }
            }
            long reportId;
            try (var insert = connection.prepareStatement("""
                    INSERT INTO wholesaler_listing_reports (reporter_business_id, product_id, reason, comments)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, reporter);
                insert.setLong(2, productId);
                insert.setString(3, reason.name());
                insert.setString(4, comments);
                insert.executeUpdate();
                reportId = MySqlDatabase.generatedId(insert.getGeneratedKeys());
            }
            try (var evidence = connection.prepareStatement("""
                    INSERT INTO wholesaler_report_evidence (report_id, product_name, category, rate)
                    VALUES (?, ?, ?, ?)
                    """)) {
                evidence.setLong(1, reportId);
                evidence.setString(2, product);
                evidence.setString(3, category);
                evidence.setBigDecimal(4, rate);
                evidence.executeUpdate();
            }
            return true;
        });
    }

    private static Instant time(ResultSet rows, String key) throws SQLException {
        Timestamp timestamp = rows.getTimestamp(key);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
