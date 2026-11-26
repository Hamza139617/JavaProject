package osl.bl;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.WholesalerDirectory.ReportReason;

public final class ReportReview {
    public enum Outcome {
        CLEARED("No violation"), SUSPENDED("Wholesaler account deactivated"),
        HIDDEN("Violation · Listing hidden");
        private final String label;
        Outcome(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record Decision(Outcome outcome, String notes, String reviewer, Instant reviewedAt) {}
    public record Report(long id, long contentId, String seller, String username, String reporter,
                         String product, String category, BigDecimal rate, boolean snapshot, ReportReason reason,
                         String comments, Instant submittedAt, boolean pending, Decision decision) {}
    public record Notice(long reportId, String product, Outcome outcome, Instant time, boolean unread) {}

    public interface Repository {
        List<Report> reports(Session session, String search, boolean pendingOnly) throws SQLException;
        void review(Session session, long reportId, Outcome outcome, String notes) throws SQLException;
        List<Notice> notices(Session session) throws SQLException;
        void markRead(Session session, long reportId) throws SQLException;
    }

    public static final class ReviewException extends RuntimeException {
        public ReviewException(String message) { super(message); }
    }

    private final Repository repository;
    public ReportReview(Repository repository) { this.repository = repository; }

    public List<Report> reports(Session session, String search, boolean pendingOnly) throws SQLException {
        require(session, Role.ADMINISTRATOR);
        String term = search.strip();
        if (term.startsWith("#") || term.startsWith("@")) term = term.substring(1).strip();
        if (term.length() > 120 || (!search.isBlank() && term.isEmpty())) {
            throw new ReviewException("Enter a valid report ID, product ID, seller name, or username.");
        }
        return repository.reports(session, term, pendingOnly);
    }

    public void review(Session session, long reportId, Outcome outcome, String input) throws SQLException {
        require(session, Role.ADMINISTRATOR);
        if (outcome == null || outcome == Outcome.HIDDEN) {
            throw new ReviewException("Choose a review outcome.");
        }
        String notes = input.strip();
        if (notes.length() > 500) throw new ReviewException("Use up to 500 characters for the review note.");
        if (outcome == Outcome.SUSPENDED && notes.isBlank()) {
            throw new ReviewException("Enter the reason for deactivating this wholesaler.");
        }
        repository.review(session, reportId, outcome, notes);
    }

    public List<Notice> notices(Session session) throws SQLException {
        require(session, Role.RETAILER);
        return repository.notices(session);
    }

    public void markRead(Session session, long reportId) throws SQLException {
        require(session, Role.RETAILER);
        repository.markRead(session, reportId);
    }

    private static void require(Session session, Role role) {
        if (session == null || session.role() != role) {
            throw new ReviewException("This feature requires a " + role.toString().toLowerCase(java.util.Locale.ROOT) + " account.");
        }
    }
}