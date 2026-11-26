package osl.ui;

import static osl.ui.DirectoryControls.actionRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.SalesPerformanceService;
import osl.bl.SalesPerformanceService.DateRange;
import osl.bl.SalesPerformanceService.ItemMonth;
import osl.bl.SalesPerformanceService.ItemStats;
import osl.bl.SalesPerformanceService.MonthlyRow;
import osl.bl.SalesPerformanceService.Preset;
import osl.bl.SalesPerformanceService.Report;
import osl.bl.SalesPerformanceService.SaleEvent;
import osl.bl.SalesPerformanceService.StockSale;
import osl.bl.StockService.Unit;

final class SalesPerformanceView extends VBox {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
    private final SalesPerformanceService service;
    private final Session session;
    private final boolean retail;
    private final ComboBox<Preset> preset = new ComboBox<>();
    private final DatePicker from = new DatePicker();
    private final DatePicker to = new DatePicker();
    private final HBox filters;
    private final Label status = text("", "#53675e", 13, false);
    private final VBox results = new VBox(10);
    private boolean applyingPreset;
    private int selectedTab;

    SalesPerformanceView(SalesPerformanceService service, Session session, Runnable back) {
        this.service = service;
        this.session = session;
        retail = session.role() == Role.RETAILER;
        setStyle("-fx-background-color: #fcfdfb;");
        setSpacing(12);
        setPadding(new Insets(20));
        var title = text("Sales performance", "#19332a", 30, true);
        var role = text(retail ? "Retail · Item performance" : "Wholesale · Stock performance and growth",
                "#53675e", 14, false);
        filters = actionRow(10, field("Period", preset), field("From", from), field("To", to),
                button("Apply period", true, this::load));
        preset.getItems().setAll(Preset.values());
        preset.setValue(Preset.THIS_MONTH);
        DateRange initial = service.dates(Preset.THIS_MONTH);
        setDates(initial);
        preset.valueProperty().addListener((observable, old, selected) -> {
            if (selected != null && selected != Preset.CUSTOM) {
                DateRange range = service.dates(selected);
                setDates(range);
                fetch(range);
            }
        });
        from.valueProperty().addListener((observable, old, selected) -> markCustom());
        to.valueProperty().addListener((observable, old, selected) -> markCustom());

        getChildren().addAll(bar(button("Back to workspace", false, back),
                text(session.businessName(), "#3d6556", 14, true)), new VBox(3, title, role), filters, status, results);
        VBox.setVgrow(results, Priority.ALWAYS);
        fetch(initial);
    }

    private void setDates(DateRange range) {
        applyingPreset = true;
        from.setValue(range.from());
        to.setValue(range.to());
        applyingPreset = false;
    }

    private void markCustom() {
        if (!applyingPreset) preset.setValue(Preset.CUSTOM);
    }

    private void load() {
        DateRange selected;
        try {
            from.commitValue();
            to.commitValue();
            selected = new DateRange(from.getValue(), to.getValue());
        } catch (DateTimeParseException invalid) {
            clearWith("Enter valid dates or choose them from the calendar.", true);
            return;
        }
        fetch(selected);
    }

    private void fetch(DateRange selected) {
        clearWith("Loading sales…", false);
        filters.setDisable(true);
        Task<Report> task = new Task<>() {
            @Override
            protected Report call() throws Exception {
                return service.load(session, selected);
            }
        };
        task.setOnSucceeded(event -> {
            filters.setDisable(false);
            Report report = task.getValue();
            message(report.totals().count() == 0 ? "No sales were recorded in this period." : "", false);
            display(report);
        });
        task.setOnFailed(event -> {
            filters.setDisable(false);
            Throwable failure = task.getException();
            String detail = failure instanceof IllegalArgumentException invalid ? invalid.getMessage()
                    : failure instanceof SQLException database
                        ? DatabaseMessages.describe("Could not load sales.", database)
                        : "Could not load sales. Apply the period again to retry.";
            clearWith(detail, true);
        });
        Thread.ofVirtual().start(task);
    }

    private void display(Report report) {
        var totals = report.totals();
        String countLabel = retail ? "Sales" : "Stocks sold";
        HBox cards = actionRow(9, metric("Revenue", money(totals.revenue())), metric("Stock cost", money(totals.cost())),
                metric("Gross profit", money(totals.profit())), metric("Gross margin", percent(totals.margin())),
                metric(countLabel, Long.toString(totals.count())));
        DateRange prior = report.previous();
        String baseline = formatDate(prior.from()) + " – " + formatDate(prior.to());
        Label comparison = text("Compared with " + baseline + " · Prior revenue " + money(report.priorTotals().revenue())
                + " · Revenue change " + percent(report.revenueGrowth()) + " · " + countLabel
                + " change " + percent(report.countGrowth()) + " (prior " + report.priorTotals().count() + ")", "#53675e", 12, false);
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        if (retail) {
            tabs.getTabs().addAll(new Tab("Items", retailItems(report)), new Tab("Months", months(report)),
                    new Tab("Sales history", history(report.history())));
        } else {
            tabs.getTabs().addAll(new Tab("Sold stocks", soldStocks(report.stocks())),
                    new Tab("Monthly stock growth", months(report)));
        }
        tabs.getSelectionModel().select(selectedTab);
        tabs.getSelectionModel().selectedIndexProperty().addListener((observable, old, selected) -> {
            if (selected.intValue() >= 0) selectedTab = selected.intValue();
        });
        VBox.setVgrow(tabs, Priority.ALWAYS);
        results.getChildren().setAll(cards, comparison, tabs);
    }

    private Node retailItems(Report report) {
        var items = report.items();
        TableView<ItemStats> table = table("No items were sold in this period.", items);
        col(table, "Item", ItemStats::name, Function.identity(), 120);
        col(table, "Unit", ItemStats::unit, Unit::toString, 65);
        col(table, "Quantity sold", ItemStats::quantity, SalesPerformanceView::amount, 95).setSortable(false);
        col(table, "Revenue", ItemStats::revenue, SalesPerformanceView::money, 92);
        col(table, "Gross profit", ItemStats::profit, SalesPerformanceView::money, 91);
        col(table, "Demand", ItemStats::demand, Function.identity(), 63);
        col(table, "Best month", row -> row.bestMonth().month(), MONTH::format, 83);
        col(table, "Latest sale", ItemStats::latest, TIME::format, 125);
        Label leaders = text(leaders(items, ItemStats::unit, ItemStats::quantity, ItemStats::revenue,
                ItemStats::name), "#1d604a", 13, true);
        Label detail = text("", "#53675e", 13, false);
        table.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> {
            if (selected == null) {
                detail.setText("");
            } else {
                ItemMonth best = selected.bestMonth();
                detail.setText(selected.name() + " · Best month " + MONTH.format(best.month()) + " · "
                        + amount(best.quantity()) + " " + selected.unit() + " sold · " + money(best.revenue())
                        + " revenue · " + selected.demand() + " demand");
            }
        });
        if (!items.isEmpty()) table.getSelectionModel().selectFirst();
        Button history = button("View item history", false, () -> itemHistory(table.getSelectionModel().getSelectedItem()));
        history.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        return section(leaders, table, bar(detail, history));
    }

    private void itemHistory(ItemStats item) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(getScene().getWindow());
        dialog.setResizable(true);
        dialog.setTitle("Item sales by month");
        dialog.setHeaderText(item.name() + " · " + item.unit());
        TableView<ItemMonth> table = table("No months with sales.", item.months());
        col(table, "Month", ItemMonth::month, MONTH::format, 108);
        col(table, "Quantity sold", ItemMonth::quantity, SalesPerformanceView::amount, 90);
        col(table, "Revenue", ItemMonth::revenue, SalesPerformanceView::money, 115);
        col(table, "Gross profit", ItemMonth::profit, SalesPerformanceView::money, 115);
        col(table, "Latest sale", ItemMonth::latest, TIME::format, 136);
        dialog.getDialogPane().setContent(table);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    private Node soldStocks(List<StockSale> stocks) {
        TableView<StockSale> table = table("No wholesale stocks were sold in this period.", stocks);
        col(table, "Stock", StockSale::lotId, id -> "#" + id, 67);
        col(table, "Product", StockSale::product, Function.identity(), 110);
        col(table, "Retailer", StockSale::buyer, Function.identity(), 110);
        col(table, "Quantity", StockSale::quantity, SalesPerformanceView::amount, 75).setSortable(false);
        col(table, "Unit", StockSale::unit, Unit::toString, 57);
        col(table, "Sold at", StockSale::soldAt, TIME::format, 125);
        col(table, "Revenue", StockSale::revenue, SalesPerformanceView::money, 91);
        col(table, "Gross profit", StockSale::profit, SalesPerformanceView::money, 90);
        Label leaders = text(leaders(stocks, StockSale::unit, StockSale::quantity, StockSale::revenue,
                row -> "#" + row.lotId() + " " + row.product()), "#1d604a", 13, true);
        return section(leaders, table);
    }

    private Node months(Report report) {
        TableView<MonthlyRow> table = table("No monthly results.", report.months());
        col(table, "Month", MonthlyRow::month, MONTH::format, 105);
        col(table, retail ? "Sales" : "Stocks sold", row -> row.metrics().count(), Object::toString, 92);
        col(table, "Revenue", row -> row.metrics().revenue(), SalesPerformanceView::money, 110);
        col(table, "Gross profit", row -> row.metrics().profit(), SalesPerformanceView::money, 110);
        col(table, "Revenue change", MonthlyRow::revenueGrowth, SalesPerformanceView::percent, 112);
        col(table, retail ? "Sales change" : "Stocks sold change", MonthlyRow::countGrowth, SalesPerformanceView::percent, 115);
        Label note = text("Monthly change compares consecutive full months with a nonzero baseline.",
                "#53675e", 12, false);
        if (report.totals().count() == 0) return section(table, note);

        var axis = new NumberAxis();
        axis.setLabel("PKR");
        BarChart<String, Number> chart = new BarChart<>(new CategoryAxis(), axis);
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setTitle(report.months().size() > 12 ? "Revenue · Last 12 months in period" : "Revenue by month");
        var series = new XYChart.Series<String, Number>();
        List<MonthlyRow> all = report.months();
        for (var row : all.subList(Math.max(0, all.size() - 12), all.size())) {
            var point = new XYChart.Data<String, Number>(MONTH.format(row.month()), row.metrics().revenue());
            point.nodeProperty().addListener((observable, old, node) -> {
                if (node != null) node.setStyle("-fx-bar-fill: #287458;");
            });
            series.getData().add(point);
        }
        chart.getData().add(series);
        chart.setMinHeight(80);
        table.setMinHeight(75);
        SplitPane breakdown = new SplitPane(chart, table);
        breakdown.setOrientation(Orientation.VERTICAL);
        breakdown.setDividerPositions(0.52);
        return section(breakdown, note);
    }

    private Node history(List<SaleEvent> history) {
        TableView<SaleEvent> table = table("No sales were recorded in this period.", history);
        col(table, "Sale", SaleEvent::saleId, id -> "#" + id, 85);
        col(table, "Sold at", SaleEvent::soldAt, TIME::format, 170);
        col(table, "Payment", SaleEvent::paymentType, Function.identity(), 90);
        col(table, "Revenue", SaleEvent::revenue, SalesPerformanceView::money, 112);
        col(table, "Stock cost", SaleEvent::cost, SalesPerformanceView::money, 112);
        col(table, "Gross profit", SaleEvent::profit, SalesPerformanceView::money, 112);
        return section(table);
    }

    private static <T> String leaders(List<T> rows, Function<T, Unit> unit, Function<T, BigDecimal> quantity,
                                      Function<T, BigDecimal> revenue, Function<T, String> name) {
        if (rows.isEmpty()) return "";
        T topRevenue = rows.stream().max(Comparator.comparing(revenue)).orElseThrow();
        var topQuantity = new EnumMap<Unit, T>(Unit.class);
        for (T row : rows) {
            topQuantity.merge(unit.apply(row), row, (first, next) ->
                    quantity.apply(first).compareTo(quantity.apply(next)) >= 0 ? first : next);
        }
        List<String> sold = new ArrayList<>();
        topQuantity.forEach((stockUnit, row) ->
                sold.add(name.apply(row) + " (" + amount(quantity.apply(row)) + " " + stockUnit + ")"));
        return "Highest revenue: " + name.apply(topRevenue) + " · " + money(revenue.apply(topRevenue))
                + "\nMost sold by unit: " + String.join(" · ", sold);
    }

    private void clearWith(String value, boolean error) {
        results.getChildren().clear();
        message(value, error);
    }

    private void message(String value, boolean error) {
        status.setText(value);
        status.setVisible(!value.isEmpty());
        status.setManaged(!value.isEmpty());
        status.setStyle("-fx-font-size: 13px; -fx-text-fill: " + (error ? "#963b2c;" : "#286049;"));
    }

    private static <T> TableView<T> table(String empty, List<T> data) {
        TableView<T> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label(empty));
        table.getItems().setAll(data);
        return table;
    }

    private static <T, V> TableColumn<T, V> col(TableView<T> table, String title, Function<T, V> value,
                                                Function<V, String> format, int width) {
        var column = new TableColumn<T, V>(title);
        column.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(value.apply(row.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(V item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item == null ? "—" : format.apply(item));
            }
        });
        column.setPrefWidth(width);
        table.getColumns().add(column);
        return column;
    }

    private static VBox section(Node... elements) {
        VBox pane = new VBox(9, elements);
        pane.setPadding(new Insets(10, 0, 0, 0));
        for (Node node : elements) {
            if (node instanceof TableView<?> || node instanceof SplitPane) VBox.setVgrow(node, Priority.ALWAYS);
        }
        return pane;
    }

    private static VBox metric(String name, String value) {
        var box = new VBox(5, text(name, "#53675e", 12, false), text(value, "#19332a", 17, true));
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #e8f2ec; -fx-background-radius: 9;");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMinWidth(0);
        return box;
    }

    private static VBox field(String name, Control control) {
        var label = new Label(name);
        label.setLabelFor(control);
        control.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, label, control);
    }

    private static Button button(String title, boolean primary, Runnable action) {
        Button button = new Button(title);
        button.setStyle((primary ? "-fx-background-color: #1d604a; -fx-text-fill: white; -fx-font-weight: bold;"
                : "-fx-background-color: #e4f1e9; -fx-text-fill: #1d604a;")
                + "-fx-background-radius: 8; -fx-padding: 8 14;");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static HBox bar(Node left, Node right) {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox row = new HBox(10, left, space, right);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label text(String value, String color, int size, boolean bold) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: " + color + "; -fx-font-size: " + size + "px;"
                + (bold ? "-fx-font-weight: bold;" : ""));
        return label;
    }

    private static String money(BigDecimal value) {
        return "PKR " + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
    private static String percent(BigDecimal value) { return value == null ? "—" : value.toPlainString() + "%"; }
    private static String formatDate(LocalDate date) { return DAY.format(date); }
    private static String amount(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
