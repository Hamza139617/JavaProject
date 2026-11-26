package osl.ui;

import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import osl.bl.AccountService.Session;
import osl.bl.WholesalerAdministration;
import osl.bl.WholesalerAdministration.AccountRow;
import osl.bl.WholesalerAdministration.AdministrationException;
import osl.bl.WholesalerAdministration.Change;
import osl.bl.WholesalerAdministration.Status;

import static osl.ui.DirectoryControls.actionRow;
import static osl.ui.DirectoryControls.bar;
import static osl.ui.DirectoryControls.button;
import static osl.ui.DirectoryControls.column;
import static osl.ui.DirectoryControls.field;
import static osl.ui.DirectoryControls.message;
import static osl.ui.DirectoryControls.text;

final class WholesalerAdministrationView extends VBox {
    private static final String MUTED = "#53675e";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm:ss", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Karachi"));

    private record Filters(String identity, String id, String phone) {}

    private final WholesalerAdministration administration;
    private final Session session;
    private final Window owner;
    private final Consumer<Boolean> setBusy;
    private final TextField identity = new TextField();
    private final TextField accountId = new TextField();
    private final TextField phone = new TextField();
    private final Label status = text("", MUTED, 13, false);
    private final TableView<AccountRow> results = new TableView<>();
    private final Button history = button("Status history", false, this::showHistory);
    private final Button change = button("Deactivate account", true, this::showStatusChange);
    private Filters applied = new Filters("", "", "");

    WholesalerAdministrationView(WholesalerAdministration administration, Session session, Window owner,
                                 Runnable back, Consumer<Boolean> setBusy) {
        this.administration = administration;
        this.session = session;
        this.owner = owner;
        this.setBusy = setBusy;
        setSpacing(14);
        setPadding(new Insets(24));

        Label heading = text("Manage wholesaler accounts", "#173d2e", 30, true);
        Label user = text("Signed in as " + session.username(), "#3d6556", 14, true);
        identity.setPromptText("Business, owner or username");
        accountId.setPromptText("e.g. 42");
        phone.setPromptText("e.g. 03001234567");

        Button search = button("Search", true, this::search);
        identity.setOnAction(event -> search());
        accountId.setOnAction(event -> search());
        phone.setOnAction(event -> search());
        Button clear = button("Clear", false, () -> {
            identity.clear();
            accountId.clear();
            phone.clear();
            search();
        });
        VBox identityField = field("Name or username", identity);
        HBox filters = actionRow(8, identityField, field("Account ID", accountId), field("Contact number", phone),
                search, clear);

        results.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        column(results, "ID", row -> "#" + row.id(), 65);
        column(results, "Business", row -> row.businessName() == null ? "—" : row.businessName(), 180);
        column(results, "Owner", AccountRow::fullName, 145);
        column(results, "Username", AccountRow::username, 130);
        column(results, "Contact number", AccountRow::phone, 124);
        column(results, "Status", row -> row.status().toString(), 105);
        results.getSelectionModel().selectedItemProperty().addListener((ignored, old, row) -> updateActions(row));
        updateActions(null);
        VBox.setVgrow(results, Priority.ALWAYS);

        getChildren().addAll(bar(button("Back to administration", false, back), user), heading, filters, status,
                results, actionRow(8, change, history));
        load(applied, 0, "");
    }

    private void updateActions(AccountRow row) {
        history.setDisable(row == null);
        change.setDisable(row == null || row.status() == Status.REMOVED);
        change.setText(row != null && row.status() == Status.SUSPENDED ? "Reactivate account" : "Deactivate account");
    }

    private void search() {
        load(new Filters(identity.getText(), accountId.getText(), phone.getText()), 0, "");
    }

    private void load(Filters filters, long selected, String notice) {
        message(status, "Searching wholesaler accounts…", false);
        run(() -> administration.search(session, filters.identity(), filters.id(), filters.phone()), found -> {
            applied = filters;
            results.getItems().setAll(found.accounts());
            results.getSelectionModel().clearSelection();
            results.getItems().stream().filter(row -> row.id() == selected).findFirst()
                    .ifPresent(row -> results.getSelectionModel().select(row));
            results.setPlaceholder(text("No wholesaler accounts match. Review your search details and try again.", MUTED, 14, false));
            String report = found.limited() ? "Showing the first " + found.accounts().size() + " matches. Refine the search to narrow results."
                    : found.accounts().size() + (found.accounts().size() == 1 ? " account found" : " accounts found");
            message(status, notice.isEmpty() ? report : notice + " " + report, false);
        }, failure -> message(status, error(notice.isEmpty() ? "Could not search accounts." : notice + " Could not refresh accounts.",
                failure), true));
    }

    private void showStatusChange() {
        AccountRow account = results.getSelectionModel().getSelectedItem();
        boolean deactivate = account.status() == Status.ACTIVE;
        String title = deactivate ? "Deactivate account" : "Reactivate account";
        ButtonType confirm = new ButtonType(deactivate ? "Confirm deactivation" : "Confirm reactivation", ButtonData.OK_DONE);
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setResizable(true);
        dialog.setTitle(title);
        dialog.getDialogPane().getButtonTypes().setAll(confirm, ButtonType.CANCEL);

        TextArea reason = new TextArea();
        reason.setWrapText(true);
        reason.setPrefRowCount(3);
        reason.setPromptText(deactivate ? "Enter a reason for deactivation" : "Enter a reason if needed");
        Label feedback = text("", MUTED, 13, false);
        Label effect = text(deactivate ? "Signing in and business workspace access will be blocked."
                : "This account will be able to sign in and access its workspace again.", MUTED, 14, false);
        dialog.getDialogPane().setContent(new VBox(10, text(accountLabel(account), "#173d2e", 17, true),
                effect, field(deactivate ? "Reason (required)" : "Reason (optional)", reason), feedback));

        dialog.getDialogPane().lookupButton(confirm).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            String note = reason.getText();
            dialog.setOnCloseRequest(close -> close.consume());
            dialog.getDialogPane().setDisable(true);
            message(feedback, "Saving account status…", false);
            setBusy.accept(true);
            DirectoryControls.run(() -> {
                administration.changeStatus(session, account.id(), account.status(), note);
                return null;
            }, result -> {
                dialog.setOnCloseRequest(null);
                dialog.close();
                load(applied, account.id(), deactivate ? "Wholesaler account deactivated." : "Wholesaler account reactivated.");
            }, failure -> {
                setBusy.accept(false);
                dialog.setOnCloseRequest(null);
                dialog.getDialogPane().setDisable(false);
                message(feedback, error("Could not change the account status.", failure), true);
            });
        });
        dialog.showAndWait();
    }

    private void showHistory() {
        AccountRow selected = results.getSelectionModel().getSelectedItem();
        run(() -> administration.history(session, selected.id()), changes -> showHistoryDialog(selected, changes),
                failure -> message(status, error("Could not load the status history.", failure), true));
    }

    private void showHistoryDialog(AccountRow account, List<Change> history) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setResizable(true);
        dialog.setTitle("Status history");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        ListView<Change> entries = new ListView<>();
        entries.getItems().setAll(history);
        entries.setPlaceholder(new Label("No status changes recorded for this account."));
        entries.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Change event, boolean empty) {
                super.updateItem(event, empty);
                if (empty || event == null) {
                    setGraphic(null);
                    return;
                }
                Label transition = text(event.previous() + " → " + event.current(), "#173d2e", 15, true);
                Label details = text(TIME.format(event.time()) + " PKT · " + event.administrator(), MUTED, 13, false);
                var entry = new VBox(4, transition, details);
                if (!event.reason().isBlank()) {
                    Label note = text(event.reason(), "#173d2e", 14, false);
                    note.maxWidthProperty().bind(view.widthProperty().subtract(42));
                    entry.getChildren().add(note);
                }
                entry.setPadding(new Insets(7, 5, 7, 5));
                setGraphic(entry);
            }
        });
        VBox historyContent = new VBox(10, text(accountLabel(account), "#173d2e", 17, true), entries);
        VBox.setVgrow(entries, Priority.ALWAYS);
        dialog.getDialogPane().setContent(historyContent);
        dialog.showAndWait();
    }

    private <T> void run(Callable<T> action, Consumer<T> done, Consumer<Throwable> failed) {
        setDisable(true);
        setBusy.accept(true);
        DirectoryControls.run(action, value -> {
            setBusy.accept(false);
            setDisable(false);
            done.accept(value);
        }, failure -> {
            setBusy.accept(false);
            setDisable(false);
            failed.accept(failure);
        });
    }

    private static String accountLabel(AccountRow account) {
        return (account.businessName() == null ? account.fullName() : account.businessName()) + " · @"
                + account.username() + " · #" + account.id();
    }

    private static String error(String action, Throwable failure) {
        if (failure instanceof AdministrationException invalid) return invalid.getMessage();
        if (failure instanceof SQLException database) return DatabaseMessages.describe(action, database);
        return action + " Try again.";
    }
}