package osl.db;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.ReportReview.Decision;
import osl.bl.ReportReview.Notice;
import osl.bl.ReportReview.Outcome;
import osl.bl.ReportReview.Report;
import osl.bl.ReportReview.Repository;
import osl.bl.ReportReview.ReviewException;
import osl.bl.WholesalerDirectory.ReportReason;
import osl.db.MySqlDatabase.WorkspaceLock;

public final class MySqlReportReview implements Repository {
    @Override
    public List<Report> reports(Session session, String search, boolean pendingOnly) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            MySqlWholesalerAdministration.requireAdministrator(connection, session);
            try (var query = connection.prepareStatement("""
                    SELECT r.report_id, r.product_id, b.business_name AS seller, a.username,
                        rb.business_name AS reporter, COALESCE(e.product_name, p.product_name) AS product,
                        COALESCE(e.category, l.category) AS category, COALESCE(e.rate, l.rate) AS rate,
                        e.report_id IS NOT NULL AS snapshot, r.reason, r.comments, r.submitted_at,
                        r.report_status = 'PENDING' AS pending, v.outcome, v.review_note, v.reviewed_at,
                        reviewer.username AS reviewer
                    FROM wholesaler_listing_reports r
                    JOIN wholesaler_listings l ON l.product_id=r.product_id
                    JOIN products p ON p.product_id=r.product_id
                    JOIN businesses b ON b.business_id=p.business_id
                    JOIN user_accounts a ON a.account_id=b.account_id
                    JOIN businesses rb ON rb.business_id=r.reporter_business_id
                    LEFT JOIN wholesaler_report_evidence e ON e.report_id=r.report_id
                    LEFT JOIN wholesaler_report_reviews v ON v.report_id=r.report_id
                    LEFT JOIN user_accounts reviewer ON reviewer.account_id=v.administrator_id
                    WHERE (NOT ? OR r.report_status='PENDING') AND
                        (? = '' OR CAST(r.report_id AS CHAR)=? OR CAST(r.product_id AS CHAR)=?
                          OR LOCATE(LOWER(?), LOWER(b.business_name)) > 0
                          OR LOCATE(LOWER(?), LOWER(a.username)) > 0
                          OR LOCATE(LOWER(?), LOWER(a.full_name)) > 0)
                    ORDER BY pending DESC, r.submitted_at DESC, r.report_id DESC
                    """)) {
                query.setBoolean(1, pendingOnly);
                for (int i = 2; i <= 7; i++) query.setString(i, search);
                List<Report> reports = new ArrayList<>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        String outcome = rows.getString("outcome");
                        Decision decision = outcome == null ? null : new Decision(Outcome.valueOf(outcome),
                                rows.getString("review_note"), rows.getString("reviewer"),
                                rows.getTimestamp("reviewed_at").toInstant());
                        reports.add(new Report(rows.getLong("report_id"), rows.getLong("product_id"),
                                rows.getString("seller"), rows.getString("username"), rows.getString("reporter"),
                                rows.getString("product"), rows.getString("category"), rows.getBigDecimal("rate"),
                                rows.getBoolean("snapshot"), ReportReason.valueOf(rows.getString("reason")),
                                rows.getString("comments"), rows.getTimestamp("submitted_at").toInstant(),
                                rows.getBoolean("pending"), decision));
                    }
                }
                return reports;
            }
        });
    }

    @Override
    public void review(Session session, long reportId, Outcome outcome, String notes) throws SQLException {
        MySqlDatabase.transaction(connection -> {
            MySqlWholesalerAdministration.requireAdministrator(connection, session);
            try (var update = connection.prepareStatement("""
                    UPDATE wholesaler_listing_reports SET report_status='REVIEWED'
                    WHERE report_id=? AND report_status='PENDING'
                    """)) {
                update.setLong(1, reportId);
                if (update.executeUpdate() != 1) {
                    throw new ReviewException("This report has already been reviewed or is unavailable. Refresh the queue.");
                }
            }
            if (outcome == Outcome.SUSPENDED) {
                suspendSeller(session, reportId, notes, connection);
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO wholesaler_report_reviews (report_id, administrator_id, outcome, review_note)
                    VALUES (?, ?, ?, ?)
                    """)) {
                insert.setLong(1, reportId);
                insert.setLong(2, session.accountId());
                insert.setString(3, outcome.name());
                insert.setString(4, notes);
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Notice> notices(Session session) throws SQLException {
        requireRetailer(session);
        return MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.SHARED);
            try (var query = connection.prepareStatement("""
                    SELECT r.report_id, COALESCE(e.product_name, p.product_name) AS product,
                        v.outcome, v.reviewed_at, v.seen_at IS NULL AS unread
                    FROM wholesaler_listing_reports r
                    JOIN wholesaler_report_reviews v ON v.report_id=r.report_id
                    JOIN products p ON p.product_id=r.product_id
                    LEFT JOIN wholesaler_report_evidence e ON e.report_id=r.report_id
                    WHERE r.reporter_business_id=?
                    ORDER BY unread DESC, v.reviewed_at DESC, r.report_id DESC
                    """)) {
                query.setLong(1, business);
                List<Notice> notices = new ArrayList<>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) notices.add(new Notice(rows.getLong("report_id"), rows.getString("product"),
                            Outcome.valueOf(rows.getString("outcome")), rows.getTimestamp("reviewed_at").toInstant(),
                            rows.getBoolean("unread")));
                }
                return notices;
            }
        });
    }

    @Override
    public void markRead(Session session, long reportId) throws SQLException {
        requireRetailer(session);
        MySqlDatabase.transaction(connection -> {
            long business = MySqlDatabase.workspace(connection, session, WorkspaceLock.SHARED);
            try (var update = connection.prepareStatement("""
                    UPDATE wholesaler_report_reviews v JOIN wholesaler_listing_reports r ON r.report_id=v.report_id
                    SET v.seen_at=CURRENT_TIMESTAMP(6)
                    WHERE r.reporter_business_id=? AND r.report_id=? AND v.seen_at IS NULL
                    """)) {
                update.setLong(1, business);
                update.setLong(2, reportId);
                update.executeUpdate();
            }
            return null;
        });
    }

    private static void suspendSeller(Session session, long reportId, String notes, java.sql.Connection connection)
            throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT b.account_id FROM wholesaler_listing_reports r
                JOIN products p ON p.product_id=r.product_id
                JOIN businesses b ON b.business_id=p.business_id
                WHERE r.report_id=?
                """)) {
            query.setLong(1, reportId);
            long seller;
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new ReviewException("The reported wholesaler is unavailable.");
                seller = rows.getLong("account_id");
            }
            MySqlWholesalerAdministration.suspendFromReport(connection, session, seller, notes);
        }
    }

    private static void requireRetailer(Session session) {
        if (session == null || session.role() != Role.RETAILER) throw new ReviewException("Report updates require a retailer account.");
    }
}