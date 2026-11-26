package osl.ui;

import static osl.ui.DirectoryControls.*;

import java.math.BigDecimal;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.ReplenishmentService;
import osl.bl.ReplenishmentService.Board;
import osl.bl.ReplenishmentService.Candidate;
import osl.bl.ReplenishmentService.Plan;
import osl.bl.ReplenishmentService.ReplenishmentException;
import osl.bl.ReplenishmentService.Settings;
import osl.bl.ReplenishmentService.State;
import osl.bl.StockService;
import osl.bl.StockService.StockException;

final class ReplenishmentView extends VBox {
    private final ReplenishmentService service;
    private final Session session;
    private final Window owner;
    private final Consumer<Boolean> busy;
    private final boolean retailer;
    private final TableView<Candidate> low = new TableView<>();
    private final TableView<Plan> plans = new TableView<>();
    private final TabPane tabs = new TabPane();
    private final Label status = text("", "#53675e", 13, false);
    private final Label noticeSummary = text("", "#1d604a", 13, true);
    private final Label threshold = text("", "#53675e", 13, false);
    private Settings settings = Settings.DEFAULT;
    private boolean loading;
    private boolean writing;

    ReplenishmentView(ReplenishmentService service, Session session, Window owner, Runnable back,
                      Runnable findWholesalers, Consumer<Boolean> busy) {
        this.service = service;
        this.session = session;
        this.owner = owner;
        this.busy = busy;
        retailer = session.role() == Role.RETAILER;
        setPadding(new Insets(21));
        setSpacing(12);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab(retailer ? "Low-stock items" : "Low wholesale stocks", candidatePane()),
                new Tab("Replenishment plans", planPane()));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        var tools = actionRow(8,
            button("Preferences", false, this::preferences), button("Refresh", false, () -> reload("", 0, false)));
        if (retailer) tools.getChildren().addFirst(button("Find wholesalers", false, findWholesalers));
        var header = bar(text("Replenish stock", "#19332a", 30, true), tools);
        String intro = retailer ? "Plan to restock items in your shared inventory through a wholesaler."
                : "Plan a replacement for a specific low or depleted stock through your supplier.";
        getChildren().addAll(bar(button("Back to workspace", false, back), text(session.businessName(), "#1d604a", 14, true)),
                header, text(intro, "#53675e", 14, false), noticeSummary, status, tabs);

        var reminder = new Timeline(new KeyFrame(Duration.seconds(45), event -> {
            if (getScene() != null && !writing && !loading) reload("", 0, true);
        }));
        reminder.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((observable, old, scene) -> {
            if (scene == null) reminder.stop(); else reminder.play();
        });
        reload("", 0, false);
    }

    private VBox candidatePane() {
        low.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        low.setPlaceholder(new Label("No products or stocks need replenishment."));
        if (!retailer) column(low, "Stock", row -> "#" + row.target().lotId(), 65);
        column(low, "Product", row -> row.product().name(), 125);
        column(low, "Available", row -> quantity(row.available()) + " " + row.product().unit(), 100);
        column(low, "Est. days left", row -> row.daysRemaining() == null ? "No sales history" : quantity(row.daysRemaining()), 106);
        column(low, "Free space", row -> row.freeSpace() == null ? "No limit set"
                : quantity(row.freeSpace()) + " " + row.product().unit(), 100);
        column(low, "Status", row -> row.plan() == null ? (row.available().signum() == 0 ? "Out of stock" : "Low stock")
                : "Being replenished", 123);
        column(low, "Expected", row -> row.plan() == null ? "—" : StockService.displayDate(row.plan().details().expectedOn()), 90);
        column(low, "Notice", row -> row.notice() == null ? "—" : row.notice().acknowledged() ? "Acknowledged"
                : row.notice().count() == 1 ? "Unread" : "Reminder " + (row.notice().count() - 1), 110);

        var create = button("Plan replenishment", true, () -> planEditor(low.getSelectionModel().getSelectedItem(), null));
        var acknowledge = button("Acknowledge notice", false, () -> {
            Candidate candidate = low.getSelectionModel().getSelectedItem();
            change(() -> {
                service.acknowledge(session, candidate);
                return 0L;
            }, "Notice acknowledged.", false);
        });
        var storage = button("Set storage limit", false, () -> capacity(low.getSelectionModel().getSelectedItem()));
        Consumer<Candidate> enable = row -> {
            create.setDisable(row == null || row.plan() != null);
            acknowledge.setDisable(row == null || row.notice() == null || row.notice().acknowledged());
            storage.setDisable(row == null);
        };
        enable.accept(null);
        low.getSelectionModel().selectedItemProperty().addListener((observable, old, row) -> enable.accept(row));
        var details = text("Depletion uses the last 30 days of sales. Free space uses your product storage limit.",
                "#53675e", 12, false);
        var body = new VBox(9, threshold, low, details, actionRow(8, create, acknowledge, storage));
        VBox.setVgrow(low, Priority.ALWAYS);
        body.setPadding(new Insets(10, 0, 0, 0));
        return body;
    }

    private VBox planPane() {
        plans.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        plans.setPlaceholder(new Label("No replenishment plans yet."));
        column(plans, "Plan", row -> "#" + row.id(), 62);
        if (!retailer) column(plans, "Replacing", row -> "#" + row.target().lotId(), 75);
        column(plans, "Product", row -> row.product().name(), 120);
        column(plans, retailer ? "Wholesaler" : "Supplier", row -> row.details().source(), 120);
        column(plans, "Planned quantity", row -> quantity(row.details().quantity()) + " " + row.product().unit(), 105);
        column(plans, "Expected", row -> StockService.displayDate(row.details().expectedOn()), 90);
        column(plans, "Status", row -> row.state().toString(), 105);
        column(plans, retailer ? "Added batch" : "New stock", row -> row.receivedLot() == 0 ? "—" : "#" + row.receivedLot(), 90);

        var receive = button("Receive delivery", true, () -> receive(plans.getSelectionModel().getSelectedItem()));
        var edit = button("Edit plan", false, () -> planEditor(null, plans.getSelectionModel().getSelectedItem()));
        var cancel = button("Cancel plan", false, () -> cancel(plans.getSelectionModel().getSelectedItem()));
        Consumer<Plan> enable = row -> {
            boolean planned = row != null && row.state() == State.PLANNED;
            receive.setDisable(!planned || row.product().archived());
            edit.setDisable(!planned || row.product().archived());
            cancel.setDisable(!planned);
        };
        enable.accept(null);
        plans.getSelectionModel().selectedItemProperty().addListener((observable, old, row) -> enable.accept(row));

        var body = new VBox(9, plans, actionRow(8, receive, edit, cancel));
        body.setPadding(new Insets(10, 0, 0, 0));
        VBox.setVgrow(plans, Priority.ALWAYS);
        return body;
    }

    private void reload(String confirmation, long planId, boolean automatic) {
        if (loading) return;
        loading = true;
        if (!automatic) {
            tabs.setDisable(true);
            message(status, "Loading replenishment…", false);
        }
        run(() -> service.load(session), board -> {
            loading = false;
            tabs.setDisable(false);
            show(board, planId);
            if (!automatic) message(status, confirmation, false);
        }, failure -> {
            loading = false;
            tabs.setDisable(false);
            message(status, error((confirmation + " Could not refresh replenishment.").strip(), failure), true);
        });
    }

    private void show(Board board, long planId) {
        Candidate selected = low.getSelectionModel().getSelectedItem();
        Plan selectedPlan = plans.getSelectionModel().getSelectedItem();
        if (!low.getItems().equals(board.low())) low.getItems().setAll(board.low());
        if (!plans.getItems().equals(board.plans())) plans.getItems().setAll(board.plans());
        if (selected != null) board.low().stream().filter(row -> row.target().equals(selected.target())).findFirst()
                .ifPresent(low.getSelectionModel()::select);
        long desired = planId != 0 ? planId : selectedPlan == null ? 0 : selectedPlan.id();
        board.plans().stream().filter(row -> row.id() == desired).findFirst().ifPresent(plans.getSelectionModel()::select);
        settings = board.settings();
        threshold.setText("Replenishment threshold: " + quantity(settings.threshold()) + " units or fewer");
        long unread = board.unreadNotices();
        noticeSummary.setText(!settings.noticesEnabled() ? "Low-stock notices are paused."
                : unread == 0 ? "" : unread + (unread == 1 ? " unread low-stock notice" : " unread low-stock notices"));
        noticeSummary.setManaged(!noticeSummary.getText().isEmpty());
        noticeSummary.setVisible(noticeSummary.isManaged());
    }

    private void planEditor(Candidate candidate, Plan plan) {
        var product = plan == null ? candidate.product() : plan.product();
        var selected = plan == null ? candidate.target() : plan.target();
        var heading = product.name() + (retailer ? "" : " · Stock #" + selected.lotId());
        var supplier = new TextField(plan == null ? "" : plan.details().source());
        supplier.setPromptText(retailer ? "Wholesaler you order from" : "External supplier or manufacturer");
        var amount = new TextField(plan == null ? "" : quantity(plan.details().quantity()));
        amount.setPromptText("Planned quantity in " + product.unit());
        var expected = new DatePicker(plan == null ? service.today().plusDays(1) : plan.details().expectedOn());
        String details = candidate == null ? "" : "Available " + quantity(candidate.available()) + " " + product.unit()
                + (candidate.freeSpace() == null ? " · Storage limit not set"
                    : " · Free space " + quantity(candidate.freeSpace()) + " " + product.unit());
        var note = text(details, "#53675e", 13, false);
        var fields = new VBox(10, note, field(retailer ? "Wholesaler" : "Supplier", supplier),
                field("Planned quantity", amount), field("Expected delivery date", expected));
        showDialog("Plan replenishment", heading, "Save plan", fields, () -> {
            expected.commitValue();
            String source = supplier.getText();
            String quantity = amount.getText();
            var date = expected.getValue();
            return () -> plan == null ? service.plan(session, candidate, source, quantity, date)
                    : service.edit(session, plan, source, quantity, date);
        }, id -> {
            tabs.getSelectionModel().select(1);
            reload("Plan saved. Stock quantity updates when you receive the delivery.", id, false);
        });
    }

    private void receive(Plan plan) {
        var quantity = new TextField(quantity(plan.details().quantity()));
        var cost = new TextField();
        cost.setPromptText("Actual cost in PKR per " + plan.product().unit());
        var expiry = new TextField();
        expiry.setPromptText(plan.product().requiresExpiry() ? "DD/MM/YYYY · required" : "DD/MM/YYYY · optional");
        var fields = new VBox(10, text("From " + plan.details().source(), "#53675e", 13, false),
                field("Quantity received", quantity), field("Unit cost (PKR)", cost), field("Expiry date", expiry));
        showDialog("Receive delivery", plan.product().name(), "Confirm receipt", fields, () -> {
            String amount = quantity.getText();
            String price = cost.getText();
            String date = expiry.getText();
            return () -> service.receive(session, plan, amount, price, date);
        }, lot -> reload((retailer ? "Batch #" : "Replacement stock #") + lot + " received.", plan.id(), false));
    }

    private void cancel(Plan plan) {
        var dialog = new Alert(Alert.AlertType.CONFIRMATION,
                "Cancel the replenishment plan for " + plan.product().name() + "?", ButtonType.CANCEL, ButtonType.OK);
        dialog.initOwner(owner);
        dialog.setResizable(true);
        dialog.setTitle("Cancel replenishment");
        dialog.setHeaderText("Plan #" + plan.id());
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            change(() -> {
                service.cancel(session, plan);
                return plan.id();
            }, "Replenishment plan cancelled.", true);
        }
    }

    private void preferences() {
        var limit = new TextField(quantity(settings.threshold()));
        var hours = new TextField(Integer.toString(settings.reminderHours()));
        var enabled = new CheckBox("Show in-app low-stock notices");
        enabled.setSelected(settings.noticesEnabled());
        var fields = new VBox(10, field("Low-stock threshold", limit), field("Repeat an unread notice after (hours)", hours), enabled);
        showDialog("Replenishment preferences", "Low-stock alerts", "Save preferences", fields, () -> {
            String value = limit.getText();
            String interval = hours.getText();
            boolean visible = enabled.isSelected();
            return () -> {
                service.savePreferences(session, value, visible, interval);
                return 0L;
            };
        }, id -> reload("Preferences saved.", 0, false));
    }

    private void capacity(Candidate item) {
        var maximum = new TextField(item.capacity() == null ? "" : quantity(item.capacity()));
        maximum.setPromptText("Leave blank if no limit is known");
        var fields = new VBox(10, field("Maximum quantity on hand (" + item.product().unit() + ")", maximum));
        showDialog("Product storage limit", item.product().name(), "Save limit", fields, () -> {
            String value = maximum.getText();
            return () -> {
                service.saveCapacity(session, item.product(), value);
                return 0L;
            };
        }, id -> reload("Storage limit saved.", 0, false));
    }

    private void showDialog(String title, String heading, String action, VBox fields,
                            java.util.function.Supplier<Callable<Long>> operation, Consumer<Long> done) {
        ButtonType submit = new ButtonType(action, ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setResizable(true);
        dialog.setTitle(title);
        dialog.setHeaderText(heading);
        Label feedback = text("", "#963b2c", 13, false);
        fields.getChildren().add(feedback);
        var pane = dialog.getDialogPane();
        pane.setContent(fields);
        pane.getButtonTypes().addAll(ButtonType.CANCEL, submit);
        pane.lookupButton(submit).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            if (loading) {
                message(feedback, "Stock is refreshing. Try again in a moment.", false);
                return;
            }
            Callable<Long> request;
            try {
                request = operation.get();
            } catch (DateTimeParseException invalid) {
                message(feedback, "Enter a valid expected delivery date.", true);
                return;
            }
            pane.setDisable(true);
            dialog.setOnCloseRequest(close -> close.consume());
            setWriting(true);
            run(request, result -> {
                setWriting(false);
                pane.setDisable(false);
                dialog.setOnCloseRequest(null);
                dialog.close();
                done.accept(result);
            }, failure -> {
                setWriting(false);
                pane.setDisable(false);
                dialog.setOnCloseRequest(null);
                message(feedback, error("Could not save.", failure), true);
            });
        });
        dialog.showAndWait();
    }

    private void change(Callable<Long> action, String confirmation, boolean selectPlan) {
        if (loading) return;
        setWriting(true);
        setDisable(true);
        run(action, id -> {
            setWriting(false);
            setDisable(false);
            reload(confirmation, selectPlan ? id : 0, false);
        }, failure -> {
            setWriting(false);
            setDisable(false);
            message(status, error("Could not update replenishment.", failure), true);
        });
    }

    private void setWriting(boolean value) {
        writing = value;
        busy.accept(value);
    }

    private static String error(String action, Throwable failure) {
        if (failure instanceof ReplenishmentException || failure instanceof StockException) return failure.getMessage();
        return DirectoryControls.error(action, failure);
    }

    private static String quantity(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
