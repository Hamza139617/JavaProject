package osl.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.SalesService;
import osl.bl.SalesService.Order;
import osl.bl.SalesService.Quote;
import osl.bl.SalesService.RetailItem;
import osl.bl.SalesService.RetailOrder;
import osl.bl.SalesService.SaleException;
import osl.bl.SalesService.WholesaleOrder;
import osl.bl.StockService;
import osl.bl.StockService.StockBook;
import osl.bl.StockService.StockException;
import osl.bl.StockService.StockLine;

final class SaleView extends VBox {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String PRIMARY = "-fx-background-color: #1d604a; -fx-text-fill: white;"
            + "-fx-background-radius: 8; -fx-padding: 8 14; -fx-font-weight: bold;";
    private static final String SECONDARY = "-fx-background-color: #e4f1e9; -fx-text-fill: #1d604a;"
            + "-fx-background-radius: 8; -fx-padding: 8 14;";

    private final StockService stocks;
    private final SalesService sales;
    private final Session session;
    private final Window owner;
    private final Consumer<Boolean> setBusy;
    private final ComboBox<StockLine> choices = new ComboBox<>();
    private final DatePicker date = new DatePicker();
    private final TextField time = new TextField();
    private final TextField quantity = new TextField();
    private final TextField price = new TextField();
    private final TextField buyer = new TextField();
    private final TableView<RetailItem> cart = new TableView<>();
    private final Label status = new Label();
    private final Label total = new Label("Sale total: PKR 0.00");
    private final Label stockDetails = new Label();
    private StockBook book = new StockBook(List.of(), List.of());
    private Quote reviewed;
    private UUID request;

    SaleView(StockService stocks, SalesService sales, Session session, Window owner,
             Runnable back, Consumer<Boolean> setBusy) {
        this.stocks = stocks;
        this.sales = sales;
        this.session = session;
        this.owner = owner;
        this.setBusy = setBusy;
        setSpacing(12);
        setPadding(new Insets(24));
        setStyle("-fx-background-color: #fcfdfb; -fx-background-radius: 19;");
        Label title = new Label("Record sale");
        title.setStyle("-fx-text-fill: #19332a; -fx-font-size: 30px; -fx-font-weight: bold;");
        Label subtitle = new Label(retail() ? "Add the products sold and their quantities."
                : "Record the sale of an entire stock to a retailer.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-text-fill: #53675e;");
        status.setWrapText(true);
        stockDetails.setWrapText(true);
        stockDetails.setStyle("-fx-text-fill: #53675e;");
        resetTime();
        time.setPromptText("HH:mm or HH:mm:ss");
        price.setPromptText("PKR");
        choices.setPromptText(retail() ? "Choose a product" : "Choose a whole stock");
        choices.setConverter(choiceLabels());
        choices.valueProperty().addListener((ignored, old, selected) -> {
            showStockDetails(selected);
            if (selected != null) {
                BigDecimal catalogPrice = selected.product().sellingPrice();
                BigDecimal defaultPrice = catalogPrice == null || retail() ? catalogPrice
                        : catalogPrice.multiply(selected.available()).setScale(2, RoundingMode.HALF_UP);
                price.setText(defaultPrice == null ? "" : defaultPrice.toPlainString());
            }
        });
        HBox schedule = new HBox(10, field("Sale date", date), field("Sale time", time));
        Label payment = new Label("Payment: Cash");
        payment.setStyle("-fx-text-fill: #53675e; -fx-padding: 5 0;");
        getChildren().addAll(bar(button("Back to workspace", false, back), new Label(session.businessName())),
                bar(new VBox(2, title, subtitle), button("Refresh stock", false, () -> load(""))),
                status, schedule);
        if (retail()) {
            configureRetail();
        } else {
            buyer.setPromptText("Name of the retailer who bought this stock");
            var selection = field("Wholesale stock", choices);
            HBox.setHgrow(selection, Priority.ALWAYS);
            getChildren().addAll(new HBox(10, selection, field("Total sale price (PKR)", price)),
                    stockDetails, field("Retailer name", buyer));
        }
        getChildren().add(bar(payment, button("Review sale", true, this::review)));
        load("");
    }

    private void configureRetail() {
        cart.setPlaceholder(new Label("Add a product to start the sale."));
        cart.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        cart.setPrefHeight(225);
        cart.setMinHeight(90);
        column("Product", row -> row.product().name());
        column("Quantity", row -> amount(row.quantity()) + " " + row.product().unit());
        column("Unit price", row -> money(row.price()));
        column("Line total", row -> money(row.total()));
        VBox.setVgrow(cart, Priority.ALWAYS);

        var selection = field("Product", choices);
        HBox.setHgrow(selection, Priority.ALWAYS);
        quantity.setPromptText("Quantity sold");
        Button add = button("Add item", false, this::addItem);
        HBox fields = new HBox(10, selection, field("Quantity", quantity), field("Unit price (PKR)", price), add);
        fields.setAlignment(Pos.BOTTOM_LEFT);
        Button remove = button("Remove selected item", false, () -> {
            cart.getItems().remove(cart.getSelectionModel().getSelectedItem());
            updateTotal();
        });
        remove.disableProperty().bind(cart.getSelectionModel().selectedItemProperty().isNull());
        total.setStyle("-fx-font-weight: bold; -fx-text-fill: #19332a;");
        getChildren().addAll(fields, stockDetails, cart, bar(remove, total));
    }

    private void addItem() {
        try {
            StockLine selected = choices.getValue();
            if (selected == null) throw new SaleException("Choose a product from the available stock.");
            RetailItem item = sales.item(selected.product(), quantity.getText(), price.getText());
            if (item.quantity().compareTo(selected.available()) > 0) {
                throw new SaleException("Only " + amount(selected.available()) + " " + item.product().unit() + " available.");
            }
            if (item.total().signum() == 0) {
                throw new SaleException("The line total must be at least PKR 0.01.");
            }
            if (cart.getItems().stream().anyMatch(row -> row.product().id() == item.product().id())) {
                throw new SaleException("This product is already in the sale. Remove its line to change it.");
            }
            cart.getItems().add(item);
            quantity.clear();
            price.clear();
            updateTotal();
            message("", false);
        } catch (SaleException invalid) {
            message(invalid.getMessage(), true);
        }
    }

    private void review() {
        Quote quote;
        try {
            Order order = order();
            quote = reviewed != null && order.equals(reviewed.order())
                    ? reviewed : sales.preview(session, order, book);
        } catch (SaleException invalid) {
            message(invalid.getMessage(), true);
            return;
        }
        String lines = quote.order() instanceof WholesaleOrder wholesale
                ? "Retailer: " + wholesale.buyer() + "\nStock #" + wholesale.lotId()
                    + " · " + amount(quote.lines().getFirst().quantity()) + " "
                    + quote.lines().getFirst().product().unit() + "\n\n"
                : "";
        String summary = lines + "Revenue: " + money(quote.revenue())
                + "\nStock cost: " + money(quote.cost())
                + "\nGross profit: " + money(quote.profit());
        ButtonType confirmSale = new ButtonType("Confirm sale", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, summary, ButtonType.CANCEL, confirmSale);
        confirm.initOwner(owner);
        confirm.setTitle("Confirm sale");
        confirm.setHeaderText("Review cash sale");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != confirmSale) {
            return;
        }
        if (reviewed != quote) {
            request = UUID.randomUUID();
        }
        reviewed = quote;
        UUID confirmed = request;
        message("Recording sale…", false);
        run(() -> sales.record(session, confirmed, quote), saved -> {
            reviewed = null;
            request = null;
            cart.getItems().clear();
            updateTotal();
            choices.getSelectionModel().clearSelection();
            quantity.clear();
            price.clear();
            buyer.clear();
            resetTime();
            load("Sale #" + saved.saleId() + " recorded. Revenue " + money(saved.revenue())
                    + " · Gross profit " + money(saved.profit()) + ".");
        }, failure -> {
            if (failure instanceof SaleException) {
                reviewed = null;
                request = null;
            }
            message(error("Could not record the sale.", failure), true);
        });
    }

    private Order order() {
        LocalDateTime soldAt;
        try {
            date.commitValue();
            if (date.getValue() == null) throw new SaleException("Choose the date of the sale.");
            soldAt = LocalDateTime.of(date.getValue(), LocalTime.parse(time.getText().strip()));
        } catch (DateTimeParseException invalid) {
            throw new SaleException("Enter a valid sale date and time in HH:mm or HH:mm:ss.");
        }
        if (retail()) {
            return new RetailOrder(soldAt, cart.getItems());
        }
        StockLine selected = choices.getValue();
        if (selected == null) throw new SaleException("Choose the wholesale stock that was sold.");
        return new WholesaleOrder(soldAt, selected.lot().id(), buyer.getText().strip(), sales.price(price.getText()));
    }

    private void load(String successMessage) {
        StockLine previous = choices.getValue();
        String draftPrice = price.getText();
        message(successMessage.isEmpty() ? "Loading available stock…" : successMessage, false);
        run(() -> stocks.load(session), current -> {
            book = current;
            List<StockLine> available = current.lines().stream().filter(row -> row.available().signum() > 0).toList();
            choices.getItems().setAll(available);
            choices.getSelectionModel().clearSelection();
            if (previous != null) {
                available.stream().filter(line -> retail()
                        ? line.product().id() == previous.product().id() : line.lot().id() == previous.lot().id())
                        .findFirst().ifPresent(line -> {
                            choices.setValue(line);
                            price.setText(draftPrice);
                        });
            }
            String empty = available.isEmpty() ? "No sellable stock. Receive stock before recording a sale." : "";
            message(successMessage.isEmpty() ? empty : successMessage, false);
        }, failure -> message(error(successMessage.isEmpty() ? "Could not load stock."
                : successMessage + " Could not refresh stock.", failure), true));
    }

    private void showStockDetails(StockLine selected) {
        if (selected == null) {
            stockDetails.setText("");
        } else if (retail()) {
            stockDetails.setText(amount(selected.available()) + " " + selected.product().unit() + " available");
        } else {
            stockDetails.setText("Entire stock: " + amount(selected.available()) + " "
                    + selected.product().unit() + " · Cost per unit: " + money(selected.lot().unitCost())
                    + " · Expiry: " + StockService.displayDate(selected.lot().expiry()));
        }
    }

    private StringConverter<StockLine> choiceLabels() {
        return new StringConverter<>() {
            @Override
            public String toString(StockLine row) {
                if (row == null) return "";
                String prefix = retail() ? "" : "Stock #" + row.lot().id() + " · ";
                return prefix + row.product().name() + " (" + amount(row.available()) + " " + row.product().unit() + ")";
            }

            @Override
            public StockLine fromString(String value) {
                throw new UnsupportedOperationException("Choose an available stock entry.");
            }
        };
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

    private static String error(String action, Throwable failure) {
        if (failure instanceof SaleException || failure instanceof StockException) return failure.getMessage();
        if (failure instanceof SQLException database) return DatabaseMessages.describe(action, database);
        return action + " Try again.";
    }

    private boolean retail() {
        return session.role() == Role.RETAILER;
    }

    private void resetTime() {
        var now = sales.now();
        date.setValue(now.toLocalDate());
        time.setText(TIME.format(now.toLocalTime()));
    }

    private void updateTotal() {
        BigDecimal revenue = cart.getItems().stream().map(RetailItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        total.setText("Sale total: " + money(revenue));
    }

    private void message(String text, boolean error) {
        status.setText(text);
        status.setStyle("-fx-text-fill: " + (error ? "#963b2c;" : "#286049;"));
    }

    private void column(String name, Function<RetailItem, String> value) {
        TableColumn<RetailItem, String> column = new TableColumn<>(name);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setSortable(false);
        cart.getColumns().add(column);
    }

    private static VBox field(String name, Control control) {
        Label label = new Label(name);
        label.setLabelFor(control);
        control.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, label, control);
    }

    private static String money(BigDecimal value) {
        return "PKR " + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String amount(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static Button button(String text, boolean primary, Runnable action) {
        Button button = new Button(text);
        button.setStyle(primary ? PRIMARY : SECONDARY);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static HBox bar(Node left, Node right) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(12, left, spacer, right);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }
}
