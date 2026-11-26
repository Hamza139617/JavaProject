package osl.ui;

import static osl.ui.DirectoryControls.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import osl.bl.AccountService.Session;
import osl.bl.ProfitTrendsService;
import osl.bl.ProfitTrendsService.Bucket;
import osl.bl.ProfitTrendsService.Point;
import osl.bl.ProfitTrendsService.ProductChoice;
import osl.bl.ProfitTrendsService.Report;
import osl.bl.SalesPerformanceService.DateRange;
import osl.bl.SalesPerformanceService.Preset;

final class ProfitTrendsView extends VBox {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_AXIS = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private enum Graph {
        LINE("Line chart"), BAR("Bar chart");

        private final String label;
        Graph(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final ProfitTrendsService service;
    private final Session session;
    private final ComboBox<Preset> preset = new ComboBox<>();
    private final ComboBox<ProductChoice> product = new ComboBox<>();
    private final ComboBox<Graph> graph = new ComboBox<>();
    private final DatePicker from = new DatePicker();
    private final DatePicker to = new DatePicker();
    private final Label status = text("", "#53675e", 13, false);
    private final VBox results = new VBox(9);
    private final TabPane tabs = new TabPane();
    private final StackPane canvas = new StackPane();
    private final TableView<Point> table = new TableView<>();
    private final VBox filters = new VBox(8);
    private final Timeline refresh;
    private DateRange applied;
    private Report displayed;
    private boolean settingControls;
    private boolean loading;
    private long revision;

    ProfitTrendsView(ProfitTrendsService service, Session session, Runnable back) {
        this.service = service;
        this.session = session;
        setStyle("-fx-background-color: #fcfdfb;");
        setSpacing(11);
        setPadding(new Insets(20));
        var title = text("Profit trends", "#19332a", 30, true);
        configureFilters();
        configureResults();
        getChildren().addAll(bar(button("Back to workspace", false, back),
                text(session.businessName(), "#3d6556", 14, true)), title, filters, status, results);
        VBox.setVgrow(results, Priority.ALWAYS);

        refresh = new Timeline(new KeyFrame(Duration.seconds(10), event -> refreshVisible()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((observable, old, scene) -> {
            if (scene == null) {
                refresh.stop();
                revision++;
                loading = false;
            } else {
                refresh.play();
            }
        });
        fetch(service.dates(Preset.THIS_MONTH), 0, false);
    }

    private void configureFilters() {
        preset.getItems().setAll(Preset.values());
        preset.setValue(Preset.THIS_MONTH);
        graph.getItems().setAll(Graph.values());
        graph.setValue(Graph.LINE);
        product.getItems().add(ProductChoice.ALL);
        product.setValue(ProductChoice.ALL);
        setDates(service.dates(Preset.THIS_MONTH));

        var period = actionRow(10, field("Period", preset), field("From", from), field("To", to),
                button("Apply period", true, this::applyDates));
        var options = actionRow(10, field("Product", product), field("Graph type", graph));
        filters.getChildren().addAll(period, options);

        preset.valueProperty().addListener((observable, old, next) -> {
            if (next == null || next == Preset.CUSTOM || settingControls) return;
            DateRange range = service.dates(next);
            setDates(range);
            fetch(range, selectedProduct(), false);
        });
        from.valueProperty().addListener((observable, old, next) -> custom());
        to.valueProperty().addListener((observable, old, next) -> custom());
        product.valueProperty().addListener((observable, old, next) -> {
            if (!settingControls && applied != null && next != null) fetch(applied, next.id(), false);
        });
        graph.valueProperty().addListener((observable, old, next) -> {
            if (displayed != null) updateChart(displayed);
        });
    }

    private void configureResults() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No values for the selected period."));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Chart", canvas), new Tab("Values", table));
        VBox.setVgrow(tabs, Priority.ALWAYS);
    }

    private void custom() {
        if (!settingControls) preset.setValue(Preset.CUSTOM);
    }

    private void setDates(DateRange range) {
        settingControls = true;
        from.setValue(range.from());
        to.setValue(range.to());
        settingControls = false;
    }

    private void applyDates() {
        try {
            from.commitValue();
            to.commitValue();
            fetch(new DateRange(from.getValue(), to.getValue()), selectedProduct(), false);
        } catch (DateTimeParseException invalid) {
            applied = null;
            clear("Enter valid dates or select them from the calendar.", true);
        }
    }

    private long selectedProduct() {
        ProductChoice choice = product.getValue();
        return choice == null ? 0 : choice.id();
    }

    private void refreshVisible() {
        if (loading || applied == null || getScene() == null || getScene().getWindow() == null
                || !getScene().getWindow().isShowing()) return;
        DateRange range = preset.getValue() == Preset.CUSTOM ? applied : service.dates(preset.getValue());
        if (!range.equals(applied) && preset.getValue() != Preset.CUSTOM) setDates(range);
        fetch(range, selectedProduct(), true);
    }

    private void fetch(DateRange range, long productId, boolean automatic) {
        long request = ++revision;
        loading = true;
        applied = range;
        if (!automatic) {
            filters.setDisable(true);
            clear("Loading profit trends…", false);
        }
        run(() -> service.load(session, range, productId), report -> {
            if (request != revision) return;
            loading = false;
            filters.setDisable(false);
            if (!report.equals(displayed)) {
                render(report);
                displayed = report;
            }
        }, failure -> {
            if (request != revision) return;
            loading = false;
            filters.setDisable(false);
            if (failure instanceof IllegalArgumentException invalid) {
                applied = null;
                clear(invalid.getMessage(), true);
            } else {
                String error = failure instanceof SQLException database
                        ? DatabaseMessages.describe("Could not load profit trends.", database)
                        : "Could not load profit trends.";
                clear(error + " Select Apply period to retry.", true);
            }
        });
    }

    private void updateProducts(Report report) {
        var choices = new ArrayList<ProductChoice>();
        choices.add(ProductChoice.ALL);
        choices.addAll(report.products());
        if (choices.equals(product.getItems())) return;
        settingControls = true;
        ProductChoice selected = product.getValue();
        if (selected != null && choices.stream().noneMatch(value -> value.id() == selected.id())) {
            choices.add(selected);
        }
        product.getItems().setAll(choices);
        product.setValue(choices.stream().filter(choice -> choice.id() == report.productId()).findFirst()
                .orElse(ProductChoice.ALL));
        settingControls = false;
    }

    private void render(Report report) {
        updateProducts(report);
        var totals = report.totals();
        var tiles = actionRow(8, metric("Revenue", money(totals.revenue())),
                metric(totals.profit().signum() < 0 ? "Gross loss" : "Gross profit", money(totals.profit())),
                metric("Average daily profit", money(report.averageDailyProfit())),
                metric("Gross margin", percent(totals.margin())));
        var current = report.range();
        var baseline = report.previousRange();
        String context = report.bucket() + " · " + DAY.format(current.from()) + " – " + DAY.format(current.to());
        String change = switch (report.profitChange().signum()) {
            case -1 -> "Profit decreased by " + money(report.profitChange().abs());
            case 1 -> "Profit increased by " + money(report.profitChange());
            default -> "Profit was unchanged";
        };
        String comparison = context + "\n" + change + " (" + percent(report.profitChangePercent()) + ")"
                + " · Revenue change " + percent(report.revenueChangePercent())
                + "\nPrevious period " + DAY.format(baseline.from()) + " – " + DAY.format(baseline.to())
                + " · Gross profit " + money(report.previous().profit()) + " · Revenue " + money(report.previous().revenue());
        var compared = text(comparison, "#53675e", 12, false);

        if (totals.count() == 0) {
            message(status, report.productId() == 0 ? "No sales were recorded in this period."
                    : "This product had no sales in the selected period.", false);
            results.getChildren().setAll(tiles, compared);
            return;
        }
        message(status, report.periodsWithSales() < 3
                ? "Limited trend data: sales were recorded in only " + report.periodsWithSales()
                        + (report.periodsWithSales() == 1 ? " period." : " periods.") : "", false);
        updateChart(report);
        table.getColumns().clear();
        column(table, "Period", row -> label(report.bucket(), row.start(), false), 133);
        column(table, "Sales", row -> Long.toString(row.metrics().count()), 92);
        column(table, "Revenue", row -> money(row.metrics().revenue()), 115);
        column(table, "Stock cost", row -> money(row.metrics().cost()), 115);
        column(table, "Gross profit / loss", row -> money(row.metrics().profit())
                + (row.metrics().profit().signum() < 0 ? " · Loss" : ""), 143);
        table.getItems().setAll(report.points());
        results.getChildren().setAll(tiles, compared, tabs);
    }

    private void updateChart(Report report) {
        if (report.points().isEmpty()) {
            canvas.getChildren().clear();
            return;
        }
        var dates = new CategoryAxis();
        var values = new NumberAxis();
        dates.setTickLabelRotation(report.points().size() > 14 ? -45 : 0);
        values.setLabel("PKR");
        XYChart<String, Number> plot;
        if (graph.getValue() == Graph.BAR) {
            plot = new BarChart<>(dates, values);
        } else {
            LineChart<String, Number> lines = new LineChart<>(dates, values);
            lines.setCreateSymbols(true);
            plot = lines;
        }
        plot.setAnimated(false);
        var revenue = new XYChart.Series<String, Number>();
        revenue.setName("Revenue");
        var profit = new XYChart.Series<String, Number>();
        profit.setName("Gross profit / loss");
        for (Point point : report.points()) {
            String x = label(report.bucket(), point.start(), true);
            revenue.getData().add(chartPoint(x, "Revenue", point.metrics().revenue()));
            profit.getData().add(chartPoint(x, point.metrics().profit().signum() < 0 ? "Gross loss" : "Gross profit",
                    point.metrics().profit()));
        }
        plot.getData().setAll(List.of(revenue, profit));
        canvas.getChildren().setAll(plot);
    }

    private static XYChart.Data<String, Number> chartPoint(String time, String name, BigDecimal value) {
        var point = new XYChart.Data<String, Number>(time, value);
        point.nodeProperty().addListener((observable, old, node) -> {
            if (node != null) {
                String detail = time + "\n" + name + " " + money(value);
                Tooltip.install(node, new Tooltip(detail));
            }
        });
        return point;
    }

    private void clear(String value, boolean error) {
        displayed = null;
        results.getChildren().clear();
        canvas.getChildren().clear();
        table.getItems().clear();
        message(status, value, error);
    }

    private static VBox metric(String name, String value) {
        var box = new VBox(4, text(name, "#53675e", 12, false), text(value, "#19332a", 17, true));
        box.setPadding(new Insets(8));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMinWidth(0);
        box.setStyle("-fx-background-color: #e8f2ec; -fx-background-radius: 9;");
        return box;
    }

    private static String percent(BigDecimal percentage) {
        return percentage == null ? "—" : percentage.toPlainString() + "%";
    }

    private static String money(BigDecimal value) {
        return "PKR " + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String label(Bucket bucket, LocalDate date, boolean axis) {
        return switch (bucket) {
            case DAY -> (axis ? DAY_AXIS : DAY).format(date);
            case MONTH -> MONTH.format(date);
            case YEAR -> Integer.toString(date.getYear());
        };
    }
}
