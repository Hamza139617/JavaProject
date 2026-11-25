package osl.ui;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.StockService;
import osl.bl.StockService.Lot;
import osl.bl.StockService.Product;
import osl.bl.StockService.Receipt;
import osl.bl.StockService.ReceiptInput;
import osl.bl.StockService.StockException;
import osl.bl.StockService.StockLine;

final class StockView extends VBox {
    private static final String ACCENT = "-fx-background-color: #1d604a; -fx-text-fill: white;"
            + "-fx-background-radius: 8; -fx-padding: 8 14; -fx-font-weight: bold;";
    private static final String SECONDARY = "-fx-background-color: #e4f1e9; -fx-text-fill: #1d604a;"
            + "-fx-background-radius: 8; -fx-padding: 8 14;";

    private final StockService stocks;
    private final Session session;
    private final Window owner;
    private final Consumer<Boolean> setBusy;
    private final boolean retailer;
    private final Button receive;
    private final TableView<StockLine> inventory = new TableView<>();
    private final TableView<Lot> batches = new TableView<>();
    private final Label selection = heading("Batch details", 17);
    private final Label status = new Label();
    private List<Product> catalog = List.of();

    StockView(StockService stocks, Session session, Window owner, Runnable back, Runnable manageProducts, Consumer<Boolean> setBusy) {
        this.stocks = stocks;
        this.session = session;
        this.owner = owner;
        this.setBusy = setBusy;
        retailer = session.role() == Role.RETAILER;
        setSpacing(14);
        setPadding(new Insets(24));
        setStyle("-fx-background-color: #fcfdfb; -fx-background-radius: 19;");

        Button backButton = button("Back to workspace", false, back);
        Label business = new Label(session.businessName());
        business.setStyle("-fx-text-fill: #3d6556; -fx-font-weight: bold;");
        HBox navigation = bar(backButton, business);
        Label title = heading(retailer ? "Retail stock" : "Wholesale stocks", 30);
        Label subtitle = new Label(retailer ? "One stock for all products in your shop."
                : "Each stock contains one product and has its own receiving details.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-text-fill: #53675e;");
        Button refresh = button("Refresh", false, () -> reload(selectedProductId(), selectedLotId(), ""));
        Button catalog = button("Manage products", false, manageProducts);
        receive = button(retailer ? "Receive stock" : "Receive new stock", true, this::showReceive);
        HBox actions = new HBox(9, refresh, catalog, receive);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox heading = new VBox(4, title, subtitle);
        HBox header = bar(heading, actions);
        status.setWrapText(true);

        configureInventory();
        getChildren().addAll(navigation, header, status, inventory);
        VBox.setVgrow(inventory, Priority.ALWAYS);
        if (retailer) {
            configureBatches();
            inventory.setPrefHeight(285);
            batches.setPrefHeight(185);
            getChildren().addAll(selection, batches);
            VBox.setVgrow(batches, Priority.ALWAYS);
            inventory.getSelectionModel().selectedItemProperty().addListener((ignored, old, line) -> showBatches(line));
        }
        reload(0, 0, "");
    }

    private void configureInventory() {
        inventory.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        inventory.setPlaceholder(new Label(retailer
                ? "Add a product, then receive its first batch."
                : "Add a catalog product, then receive its first stock."));
        if (!retailer) {
            column(inventory, "Stock", row -> "#" + row.lot().id(), 70);
        }
        column(inventory, "Product", row -> row.product().name(), 145);
        column(inventory, "On hand", row -> amount(row.onHand()) + " " + row.product().unit(), 90);
        if (retailer) {
            column(inventory, "Available", row -> amount(row.available()) + " " + row.product().unit(), 90);
        } else {
            column(inventory, "Unit cost", row -> money(row.lot().unitCost()), 85);
        }
        column(inventory, retailer ? "Nearest expiry" : "Expiry",
                row -> StockService.displayDate(row.nearestExpiry()), 100);
        column(inventory, retailer ? "Latest source" : "Source",
                row -> row.source() == null ? "—" : row.source(), 105);
        TableColumn<StockLine, String> statusColumn = column(inventory, "Status", StockLine::status, 125);
        statusColumn.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (empty || item == null) {
                    setStyle("");
                    return;
                }
                String color = switch (item) {
                    case "In stock" -> "#1d604a";
                    case "Expired", "Out of stock" -> "#963b2c";
                    default -> "#875a17";
                };
                setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
            }
        });
    }

    private void configureBatches() {
        batches.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        batches.setPlaceholder(new Label("Select a product to see its batches."));
        column(batches, "Batch", row -> "#" + row.id(), 75);
        column(batches, "Quantity", row -> amount(row.quantity()), 90);
        column(batches, "Unit cost", row -> money(row.unitCost()), 115);
        column(batches, "Expiry", row -> StockService.displayDate(row.expiry()), 105);
        column(batches, "Source", Lot::source, 140);
    }

    private void showBatches(StockLine line) {
        selection.setText(line == null ? "Batch details" : line.product().name() + " batches");
        batches.getItems().setAll(line == null ? List.of() : line.batches());
        batches.setPlaceholder(new Label(line == null ? "Select a product to see its batches."
                : "This product has no batches yet."));
    }

    private void reload(long product, long lot, String message) {
        showStatus("Loading stock…", false);
        run(() -> stocks.load(session), book -> {
            catalog = book.catalog();
            receive.setDisable(catalog.isEmpty());
            inventory.getItems().setAll(book.lines());
            inventory.getSelectionModel().clearSelection();
            for (StockLine line : book.lines()) {
                if (retailer ? line.product().id() == product : line.lot().id() == lot) {
                    inventory.getSelectionModel().select(line);
                    inventory.scrollTo(line);
                    break;
                }
            }
            String empty = catalog.isEmpty() ? "Add an active product in Manage Products to start receiving stock." : "";
            showStatus(message.isEmpty() ? empty : message, false);
        }, failure -> showStatus(errorMessage(message.isEmpty() ? "Could not load stock."
                : message + " Could not refresh stock.", failure), true));
    }

    private void showReceive() {
        if (catalog.isEmpty()) {
            showStatus("Add a catalog product before receiving stock.", true);
            return;
        }
        ButtonType review = new ButtonType("Review receipt", ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = dialog(retailer ? "Receive a product batch" : "Receive a new wholesale stock", review);
        ComboBox<Product> product = new ComboBox<>();
        product.getItems().setAll(catalog);
        product.setPromptText("Choose a catalog product");
        StockLine selected = inventory.getSelectionModel().getSelectedItem();
        if (selected != null && !selected.product().archived()) {
            product.setValue(selected.product());
        } else if (catalog.size() == 1) {
            product.setValue(catalog.getFirst());
        }

        TextField quantity = new TextField();
        TextField cost = new TextField();
        TextField expiry = new TextField();
        TextField source = new TextField();
        Label current = new Label();
        current.setWrapText(true);
        current.setStyle("-fx-text-fill: #53675e;");
        Label error = errorLabel();
        product.valueProperty().addListener((ignored, old, line) -> receiptHints(line, quantity, expiry, current));
        receiptHints(product.getValue(), quantity, expiry, current);
        cost.setPromptText("PKR per stock unit");
        source.setPromptText("Supplier or source");
        dialog.getDialogPane().setContent(new VBox(10, field("Product", product), current, field("Received quantity", quantity),
                field("Unit cost (PKR)", cost), field("Expiry date", expiry), field("Source", source), error));
        dialog.getDialogPane().lookupButton(review).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            Product chosen = product.getValue();
            ReceiptInput input = new ReceiptInput(quantity.getText(), cost.getText(), expiry.getText(), source.getText());
            Receipt receipt;
            try {
                receipt = stocks.prepare(chosen, input);
            } catch (StockException invalid) {
                error.setText(invalid.getMessage());
                return;
            }
            String detail = amount(receipt.quantity()) + " " + chosen.unit() + " · " + money(receipt.unitCost())
                    + " per unit\nSource: " + receipt.source() + "\nExpiry: " + StockService.displayDate(receipt.expiry());
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, detail, ButtonType.CANCEL, ButtonType.OK);
            confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
            confirm.setTitle("Confirm receipt");
            confirm.setHeaderText(chosen.name());
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                save(dialog, error, () -> stocks.receive(session, chosen, input),
                        lot -> reload(chosen.id(), lot, retailer ? "Batch received into retail stock."
                                : "New wholesale stock received."));
            }
        });
        dialog.showAndWait();
    }

    private void receiptHints(Product product, TextField quantity, TextField expiry, Label current) {
        if (product == null) {
            quantity.setPromptText("Enter the received quantity");
            expiry.setPromptText("DD/MM/YYYY");
            current.setText("");
            return;
        }
        quantity.setPromptText("Quantity in " + product.unit());
        expiry.setPromptText(product.requiresExpiry() ? "DD/MM/YYYY · required" : "DD/MM/YYYY · optional");
        if (retailer) {
            inventory.getItems().stream().filter(line -> line.product().id() == product.id()).findFirst()
                    .ifPresentOrElse(line -> current.setText("Current stock: " + amount(line.onHand()) + " " + product.unit()
                            + " across " + line.batches().size() + " batch(es)."), () -> current.setText("Current stock: 0"));
        } else {
            current.setText("This receipt creates a separate stock for " + product.name() + ".");
        }
    }

    private Dialog<ButtonType> dialog(String title, ButtonType action) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.getDialogPane().setHeaderText(title);
        dialog.getDialogPane().setPrefWidth(440);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, action);
        return dialog;
    }

    private <T> void save(Dialog<ButtonType> dialog, Label error, Callable<T> action, Consumer<T> saved) {
        error.setText("Saving…");
        dialog.getDialogPane().setDisable(true);
        dialog.setOnCloseRequest(event -> event.consume());
        run(action, result -> {
            dialog.setOnCloseRequest(null);
            dialog.getDialogPane().setDisable(false);
            dialog.close();
            saved.accept(result);
        }, failure -> {
            dialog.setOnCloseRequest(null);
            dialog.getDialogPane().setDisable(false);
            error.setText(errorMessage("Could not save.", failure));
        });
    }

    private <T> void run(Callable<T> action, Consumer<T> success, Consumer<Throwable> failure) {
        setBusy.accept(true);
        setDisable(true);
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return action.call();
            }
        };
        task.setOnSucceeded(event -> {
            setBusy.accept(false);
            setDisable(false);
            success.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            setBusy.accept(false);
            setDisable(false);
            failure.accept(task.getException());
        });
        Thread.ofVirtual().start(task);
    }

    private static String errorMessage(String action, Throwable failure) {
        if (failure instanceof StockException invalid) {
            return invalid.getMessage();
        }
        if (failure instanceof SQLException database) {
            return DatabaseMessages.describe(action, database);
        }
        return action + " Try again.";
    }

    private long selectedProductId() {
        StockLine line = inventory.getSelectionModel().getSelectedItem();
        return line == null ? 0 : line.product().id();
    }

    private long selectedLotId() {
        StockLine line = inventory.getSelectionModel().getSelectedItem();
        return line == null || line.lot() == null ? 0 : line.lot().id();
    }

    private void showStatus(String text, boolean error) {
        status.setText(text);
        status.setStyle("-fx-text-fill: " + (error ? "#963b2c;" : "#286049;"));
    }

    private static <T> TableColumn<T, String> column(TableView<T> table, String title, Function<T, String> value, int width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        column.setSortable(false);
        table.getColumns().add(column);
        return column;
    }

    private static String amount(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal value) {
        return "PKR " + value.toPlainString();
    }

    private static VBox field(String text, javafx.scene.control.Control control) {
        Label label = new Label(text);
        label.setLabelFor(control);
        control.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, label, control);
    }

    private static Label errorLabel() {
        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #963b2c;");
        return error;
    }

    private static Label heading(String text, int size) {
        Label heading = new Label(text);
        heading.setStyle("-fx-font-size: " + size + "px; -fx-text-fill: #19332a; -fx-font-weight: bold;");
        return heading;
    }

    private static Button button(String title, boolean primary, Runnable action) {
        Button button = new Button(title);
        button.setStyle(primary ? ACCENT : SECONDARY);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static HBox bar(Node left, Node right) {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox bar = new HBox(12, left, space, right);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }
}
