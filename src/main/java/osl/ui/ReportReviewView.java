package osl.ui;

import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.ReportReview;
import osl.bl.ReportReview.Notice;
import osl.bl.ReportReview.Outcome;
import osl.bl.ReportReview.Report;
import osl.bl.ReportReview.ReviewException;

import static osl.ui.DirectoryControls.*;

final class ReportReviewView extends VBox {
    private final ReportReview reports;
    private final Session session;
    private final Consumer<Boolean> busy;
    private final Label status = text("", "#53675e", 13, false);

    ReportReviewView(ReportReview reports, Session session, Runnable back, Consumer<Boolean> busy) {
        this.reports = reports;
        this.session = session;
        this.busy = busy;
        setSpacing(12);
        setPadding(new Insets(24));
        setStyle("-fx-background-color: #fcfdfb;");
        boolean admin = session.role() == Role.ADMINISTRATOR;
        getChildren().addAll(bar(button(admin ? "Back to administration" : "Back to workspace", false, back),
                text(session.username(), "#3d6556", 14, true)),
                text(admin ? "Review reported content" : "Report updates", "#173d2e", 30, true), status);
        VBox body = admin ? reviewQueue() : inbox();
        getChildren().add(body);
        VBox.setVgrow(body, Priority.ALWAYS);
    }

    private VBox reviewQueue() {
        TableView<Report> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No reports match your search."));
        column(table, "Report", row -> "#" + row.id(), 68);
        column(table, "Reported wholesaler", Report::seller, 180);
        column(table, "Listing evidence", Report::product, 160);
        column(table, "Status", row -> row.pending() ? "Pending" : "Reviewed", 100);

        TextField search = new TextField();
        search.setPromptText("Report ID, product ID, wholesaler or username");
        CheckBox pending = new CheckBox("Pending only");
        pending.setSelected(true);
        class Queue {
            void load(long selectedId, String notice) {
                String term = search.getText();
                boolean pendingOnly = pending.isSelected();
                run(() -> reports.reports(session, term, pendingOnly), found -> {
                    table.getItems().setAll(found);
                    table.getSelectionModel().clearSelection();
                    found.stream().filter(row -> row.id() == selectedId).findFirst()
                            .ifPresent(row -> table.getSelectionModel().select(row));
                    message(status, notice.isEmpty() ? found.size() + (found.size() == 1 ? " report" : " reports") : notice, false);
                }, "Could not load reports.");
            }
        }
        Queue queue = new Queue();
        search.setOnAction(event -> queue.load(0, ""));
        pending.setOnAction(event -> queue.load(0, ""));
        HBox actions = actionRow(10, search, pending, button("Search", false, () -> queue.load(0, "")));
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label subject = text("Select a report to see its details.", "#173d2e", 16, true);
        Label details = text("", "#53675e", 13, false);
        ComboBox<Outcome> outcome = new ComboBox<>();
        outcome.getItems().setAll(Outcome.CLEARED, Outcome.SUSPENDED);
        outcome.setPromptText("Choose a review outcome");
        TextArea note = new TextArea();
        note.setPromptText("Review note (required when deactivating the wholesaler)");
        note.setPrefRowCount(2);
        note.setWrapText(true);
        Button save = button("Save review", true, () -> {
            Report selected = table.getSelectionModel().getSelectedItem();
            Outcome decision = outcome.getValue();
            String notes = note.getText();
            if (decision == Outcome.SUSPENDED && !notes.isBlank() && notes.strip().length() <= 500) {
                var confirm = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                        "Deactivate " + selected.seller() + "? The wholesaler will lose workspace access, "
                                + "and their public profile and listings will be hidden.",
                        javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
                confirm.initOwner(getScene().getWindow());
                if (confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)
                        != javafx.scene.control.ButtonType.OK) return;
            }
            run(() -> {
                reports.review(session, selected.id(), decision, notes);
                return null;
            }, ignored -> queue.load(selected.id(), "Review saved. The retailer was notified."), "Could not save the review.");
        });
        save.disableProperty().bind(outcome.valueProperty().isNull());
        HBox review = actionRow(8, outcome, save);
        VBox form = new VBox(6, review, note);
        form.setVisible(false);
        form.managedProperty().bind(form.visibleProperty());
        table.getSelectionModel().selectedItemProperty().addListener((observable, old, row) -> {
            outcome.setValue(null);
            note.clear();
            if (row == null) {
                subject.setText("Select a report to see its details.");
                details.setText("");
                form.setVisible(false);
                return;
            }
            subject.setText(row.seller() + " · @" + row.username());
            String text = (row.snapshot() ? "Details at submission" : "Current listing details")
                    + " · " + row.product() + " (#" + row.contentId() + ") · " + row.category() + " · " + money(row.rate())
                    + "\nReported by " + row.reporter() + " on " + updated(row.submittedAt()) + " · " + row.reason();
            if (!row.comments().isBlank()) text += "\n" + row.comments();
            if (row.decision() != null) {
                var decision = row.decision();
                text += "\n" + decision.outcome() + " · @" + decision.reviewer() + " · " + updated(decision.reviewedAt());
                if (!decision.notes().isBlank()) text += "\n" + decision.notes();
            }
            details.setText(text);
            form.setVisible(row.pending());
        });
        var evidence = scroll(new VBox(6, subject, details));
        VBox body = new VBox(9, actions, table, evidence, form);
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox.setVgrow(evidence, Priority.ALWAYS);
        queue.load(0, "");
        return body;
    }

    private VBox inbox() {
        TableView<Notice> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("Updates appear here after an administrator reviews your report."));
        column(table, "Report", row -> "#" + row.reportId(), 70);
        column(table, "Listing", Notice::product, 160);
        column(table, "Outcome", row -> row.outcome().toString(), 190);
        column(table, "Reviewed", row -> updated(row.time()), 120);
        column(table, "Read status", row -> row.unread() ? "New" : "Read", 80);
        Runnable load = () -> run(() -> reports.notices(session), rows -> {
            table.getItems().setAll(rows);
            table.getSelectionModel().clearSelection();
            long unread = rows.stream().filter(Notice::unread).count();
            message(status, unread + (unread == 1 ? " unread update" : " unread updates"), false);
        }, "Could not load report updates.");
        Button seen = button("Mark as read", true, () -> {
            long id = table.getSelectionModel().getSelectedItem().reportId();
            run(() -> {
                reports.markRead(session, id);
                return null;
            }, ignored -> load.run(), "Could not mark the update as read.");
        });
        seen.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((observable, old, row) ->
                seen.setDisable(row == null || !row.unread()));
        VBox body = new VBox(10, actionRow(8, button("Refresh", false, load), seen), table);
        VBox.setVgrow(table, Priority.ALWAYS);
        load.run();
        return body;
    }

    private <T> void run(Callable<T> operation, Consumer<T> done, String action) {
        busy.accept(true);
        setDisable(true);
        DirectoryControls.run(operation, result -> {
            setDisable(false);
            busy.accept(false);
            done.accept(result);
        }, failure -> {
            setDisable(false);
            busy.accept(false);
            String error = switch (failure) {
                case ReviewException invalid -> invalid.getMessage();
                case osl.bl.WholesalerAdministration.AdministrationException invalid -> invalid.getMessage();
                case SQLException database -> DatabaseMessages.describe(action, database);
                default -> action + " Try again.";
            };
            message(status, error, true);
        });
    }
}