package osl.ui;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.InventoryService;
import osl.bl.InventoryService.Board;
import osl.bl.InventoryService.InventoryException;
import osl.bl.InventoryService.Location;
import osl.bl.InventoryService.Position;
import osl.bl.StockService;

import static osl.ui.DirectoryControls.actionRow;
import static osl.ui.DirectoryControls.bar;
import static osl.ui.DirectoryControls.button;
import static osl.ui.DirectoryControls.column;
import static osl.ui.DirectoryControls.field;
import static osl.ui.DirectoryControls.message;
import static osl.ui.DirectoryControls.text;

final class InventoryView extends VBox {
    private static final String SECONDARY_TEXT = "#53675e";
    private static final Destination UNASSIGNED = new Destination(null, "Unassigned");

    private record Destination(Long id, String title) {
        @Override public String toString() { return title; }
    }

    private final InventoryService inventory;
    private final Session session;
    private final Window owner;
    private final Consumer<Boolean> setBusy;
    private final boolean retailer;
    private final TableView<Position> positions = new TableView<>();
    private final TableView<Location> locations = new TableView<>();
    private final TextField search = new TextField();
    private final Label status = new Label();
    private final Label summary = text("", "#1d604a", 15, true);
    private Board board = new Board(List.of(), List.of());
    private Map<Long, Location> byId = Map.of();

    InventoryView(InventoryService inventory, Session session, Window owner, Runnable back, Consumer<Boolean> setBusy) {
        this.inventory = inventory;
        this.session = session;
        this.owner = owner;
        this.setBusy = setBusy;
        retailer = session.role() == Role.RETAILER;

        setSpacing(14);
        setPadding(new Insets(24));
        Label company = text(session.businessName(), "#3d6556", 14, true);
        Label heading = text("Track inventory", "#173d2e", 30, true);
        Label description = text(retailer ? "Find and update the storage position of each item in your shop."
                : "Find and update where each wholesale stock is stored.", SECONDARY_TEXT, 14, false);
        Button refresh = button("Refresh", false, () -> reload(positionId(), locationId(), ""));
        status.setWrapText(true);

        Tab positionTab = new Tab(retailer ? "Item positions" : "Stock positions", positionsPage());
        Tab storageTab = new Tab("Storage utilization", storagePage());
        TabPane tabs = new TabPane(positionTab, storageTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        getChildren().addAll(bar(button("Back to workspace", false, back), company),
                bar(new VBox(3, heading, description), refresh), status, tabs);
        reload(0, 0, "");
    }

    private VBox positionsPage() {
        positions.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        if (!retailer) column(positions, "Stock", row -> "#" + row.id(), 85);
        column(positions, "Item", row -> row.productName() + (row.archived() ? " (archived)" : ""), 170);
        column(positions, "On hand", row -> row.onHand().stripTrailingZeros().toPlainString() + " " + row.unit(), 110);
        if (!retailer) column(positions, "Expiry", row -> StockService.displayDate(row.expiry()), 110);
        column(positions, "Current location", this::path, 260);
        search.setPromptText(retailer ? "Search by item or location" : "Search by stock number, item, or location");
        search.textProperty().addListener((observable, old, value) -> filter(positionId()));

        Button move = button("Change location", true, this::showMove);
        move.disableProperty().bind(positions.getSelectionModel().selectedItemProperty().isNull());
        return page(actionRow(10, search, move), positions);
    }

    private VBox storagePage() {
        locations.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        locations.setPlaceholder(new Label("Add a location to start assigning inventory."));
        column(locations, "Location", row -> row.details().path(), 250);
        column(locations, "Capacity", row -> Integer.toString(row.details().capacity()), 95);
        column(locations, "Used", row -> Long.toString(row.used()), 80);
        column(locations, "Available", row -> Long.toString(row.available()), 90);
        column(locations, "Utilization", row -> Math.round(100.0 * row.used() / row.details().capacity()) + "%", 100);

        Button add = button("Add location", true, () -> showLocation(null));
        Button edit = button("Edit location", false, () -> showLocation(locations.getSelectionModel().getSelectedItem()));
        Button remove = button("Remove location", false, this::confirmRemoval);
        edit.disableProperty().bind(locations.getSelectionModel().selectedItemProperty().isNull());
        remove.disableProperty().bind(locations.getSelectionModel().selectedItemProperty().isNull());
        Label capacityNote = text(retailer ? "Each assigned item reserves one slot until it is moved or unassigned."
                : "Each assigned stock reserves one slot until it is moved or unassigned.", SECONDARY_TEXT, 13, false);
        return page(new VBox(8, actionRow(8, add, edit, remove), summary, capacityNote), locations);
    }

    private static VBox page(VBox header, TableView<?> table) {
        VBox pane = new VBox(12, header, table);
        pane.setPadding(new Insets(14, 0, 0, 0));
        VBox.setVgrow(table, Priority.ALWAYS);
        return pane;
    }

    private static VBox page(HBox header, TableView<?> table) {
        return page(new VBox(header), table);
    }

    private void filter(long selected) {
        String term = search.getText().strip().toLowerCase(Locale.ROOT);
        positions.getItems().setAll(board.positions().stream().filter(row ->
                row.productName().toLowerCase(Locale.ROOT).contains(term)
                || path(row).toLowerCase(Locale.ROOT).contains(term)
                || (!retailer && (term.equals("#" + row.id()) || term.equals(Long.toString(row.id()))))).toList());
        positions.getSelectionModel().clearSelection();
        positions.getItems().stream().filter(row -> row.id() == selected).findFirst()
                .ifPresent(row -> positions.getSelectionModel().select(row));
        positions.setPlaceholder(new Label(term.isEmpty()
                ? (retailer ? "Add an item in Manage products to track its position."
                : "Receive a wholesale stock in Manage stock to track it.")
                : (retailer ? "No items match your search." : "No stocks match your search.")));
    }

    private String path(Position row) {
        Location location = row.locationId() == null ? null : byId.get(row.locationId());
        return location == null ? UNASSIGNED.title() : location.details().path();
    }

    private void reload(long positionId, long locationId, String notice) {
        message(status, "Loading inventory…", false);
        setDisable(true);
        setBusy.accept(true);
        DirectoryControls.run(() -> inventory.load(session), result -> {
            setBusy.accept(false);
            setDisable(false);
            board = result;
            byId = board.locations().stream().collect(Collectors.toMap(Location::id, row -> row));
            filter(positionId);
            locations.getItems().setAll(board.locations());
            locations.getSelectionModel().clearSelection();
            if (byId.containsKey(locationId)) locations.getSelectionModel().select(byId.get(locationId));

            long used = board.locations().stream().mapToLong(Location::used).sum();
            long available = board.locations().stream().mapToLong(Location::available).sum();
            long unassigned = board.positions().stream().filter(row -> row.locationId() == null).count();
            summary.setText(used + " used  ·  " + available + " available  ·  " + unassigned
                    + (retailer ? " unassigned items" : " unassigned stocks"));
            message(status, notice, false);
        }, failure -> {
            setBusy.accept(false);
            setDisable(false);
            String action = notice.isEmpty() ? "Could not load inventory." : notice + " Could not refresh inventory.";
            message(status, error(action, failure), true);
        });
    }

    private void showMove() {
        Position selected = positions.getSelectionModel().getSelectedItem();
        String subject = retailer ? selected.productName() : "Stock #" + selected.id() + " · " + selected.productName();
        ButtonType save = new ButtonType("Save location", ButtonData.OK_DONE);
        Dialog<Void> dialog = dialog("Change location", save);
        ComboBox<Destination> destination = new ComboBox<>();
        destination.getItems().add(UNASSIGNED);
        for (Location location : board.locations()) {
            destination.getItems().add(new Destination(location.id(),
                    location.details().path() + " (" + location.available() + " available)"));
        }
        destination.setValue(destination.getItems().stream()
                .filter(value -> Objects.equals(value.id(), selected.locationId())).findFirst().orElse(UNASSIGNED));
        Label details = text(subject + "\nCurrent location · " + path(selected), SECONDARY_TEXT, 14, false);
        Label feedback = text(board.locations().isEmpty() ? "Add a location under Storage utilization first." : "",
                SECONDARY_TEXT, 13, false);
        dialog.getDialogPane().setContent(new VBox(10, details, field("Destination", destination), feedback));
        submit(dialog, save, feedback, () -> {
            Long target = destination.getValue().id();
            return () -> {
                inventory.move(session, selected.id(), target);
                return selected.id();
            };
        }, id -> reload(id, locationId(), "Location saved."), "Could not update the location.");
        dialog.showAndWait();
    }

    private void showLocation(Location existing) {
        boolean creating = existing == null;
        ButtonType save = new ButtonType("Save location", ButtonData.OK_DONE);
        Dialog<Void> dialog = dialog(creating ? "Add storage location" : "Edit storage location", save);
        TextField room = new TextField(creating ? "" : existing.details().storeroom());
        TextField rack = new TextField(creating ? "" : existing.details().rack());
        TextField bin = new TextField(creating ? "" : existing.details().bin());
        TextField capacity = new TextField(creating ? "" : Integer.toString(existing.details().capacity()));
        room.setPromptText("e.g. Main storeroom");
        rack.setPromptText("e.g. A");
        bin.setPromptText("e.g. 03");
        Label feedback = text("", SECONDARY_TEXT, 13, false);
        dialog.getDialogPane().setContent(new VBox(10, field("Storeroom", room), field("Rack (optional)", rack),
                field("Bin (optional)", bin), field("Capacity (slots)", capacity), feedback));
        submit(dialog, save, feedback, () -> {
            String roomName = room.getText();
            String rackName = rack.getText();
            String binName = bin.getText();
            String slots = capacity.getText();
            return () -> inventory.saveLocation(session, creating ? 0 : existing.id(), roomName, rackName, binName, slots);
        }, id -> reload(positionId(), id, creating ? "Location added." : "Location updated."), "Could not save the location.");
        dialog.showAndWait();
    }

    private void confirmRemoval() {
        Location selected = locations.getSelectionModel().getSelectedItem();
        if (selected.used() > 0) {
            message(status, "Move or unassign the records at this location before removing it.", true);
            return;
        }
        ButtonType remove = new ButtonType("Remove location", ButtonData.OK_DONE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, "Remove " + selected.details().path() + "?",
                remove, ButtonType.CANCEL);
        dialog.initOwner(owner);
        dialog.setResizable(true);
        dialog.setHeaderText(null);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != remove) return;

        setDisable(true);
        setBusy.accept(true);
        DirectoryControls.run(() -> {
            inventory.removeLocation(session, selected.id());
            return null;
        }, ignored -> reload(positionId(), 0, "Location removed."), failure -> {
            setBusy.accept(false);
            setDisable(false);
            message(status, error("Could not remove the location.", failure), true);
        });
    }

    private Dialog<Void> dialog(String title, ButtonType save) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setResizable(true);
        dialog.setTitle(title);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        return dialog;
    }

    private <T> void submit(Dialog<Void> dialog, ButtonType save, Label feedback,
                            java.util.function.Supplier<Callable<T>> capture, Consumer<T> success, String action) {
        var button = dialog.getDialogPane().lookupButton(save);
        button.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            Callable<T> operation = capture.get();
            dialog.getDialogPane().setDisable(true);
            dialog.setOnCloseRequest(close -> close.consume());
            setBusy.accept(true);
            message(feedback, "Saving…", false);
            DirectoryControls.run(operation, result -> {
                dialog.setOnCloseRequest(null);
                dialog.close();
                success.accept(result);
            }, failure -> {
                setBusy.accept(false);
                dialog.setOnCloseRequest(null);
                dialog.getDialogPane().setDisable(false);
                message(feedback, error(action, failure), true);
            });
        });
    }

    private long positionId() {
        Position selected = positions.getSelectionModel().getSelectedItem();
        return selected == null ? 0 : selected.id();
    }

    private long locationId() {
        Location selected = locations.getSelectionModel().getSelectedItem();
        return selected == null ? 0 : selected.id();
    }

    private static String error(String action, Throwable cause) {
        if (cause instanceof InventoryException invalid) return invalid.getMessage();
        if (cause instanceof SQLException database) return DatabaseMessages.describe(action, database);
        return action + " Try again.";
    }
}