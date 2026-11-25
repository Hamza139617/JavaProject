package osl.ui;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import osl.bl.WholesalerDirectory.DirectoryException;

final class DirectoryControls {
    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Karachi"));

    private DirectoryControls() {}

    static <T> void run(Callable<T> operation, Consumer<T> done, Consumer<Throwable> failed) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception { return operation.call(); }
        };
        task.setOnSucceeded(event -> done.accept(task.getValue()));
        task.setOnFailed(event -> failed.accept(task.getException()));
        Thread.ofVirtual().start(task);
    }

    static String error(String action, Throwable failure) {
        if (failure instanceof DirectoryException invalid) return invalid.getMessage();
        if (failure instanceof SQLException database) return DatabaseMessages.describe(action, database);
        return action + " Try again.";
    }

    static Label text(String value, String color, int size, boolean bold) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: " + color + "; -fx-font-size: " + size + "px;"
                + (bold ? "-fx-font-weight: bold;" : ""));
        return label;
    }

    static void message(Label label, String text, boolean error) {
        label.setText(text);
        label.setStyle("-fx-text-fill: " + (error ? "#963b2c;" : "#1d604a;"));
    }

    static Button button(String value, boolean primary, Runnable action) {
        Button button = new Button(value);
        button.setStyle((primary ? "-fx-background-color: #1d604a; -fx-text-fill: white; -fx-font-weight: bold;"
                : "-fx-background-color: #e4f1e9; -fx-text-fill: #1d604a;")
                + "-fx-background-radius: 8; -fx-padding: 8 13;");
        button.setOnAction(event -> action.run());
        return button;
    }

    static VBox field(String title, Control control) {
        Label label = new Label(title);
        label.setLabelFor(control);
        control.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, label, control);
    }

    static HBox bar(Node left, Node right) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var bar = new HBox(10, left, spacer, right);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    static ScrollPane scroll(Node content) {
        var pane = new ScrollPane(content);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.setStyle("-fx-background: #fcfdfb; -fx-background-color: transparent;");
        return pane;
    }

    static <T> void column(TableView<T> table, String title, Function<T, String> value, int width) {
        var column = new TableColumn<T, String>(title);
        column.setCellValueFactory(row -> new ReadOnlyStringWrapper(value.apply(row.getValue())));
        column.setPrefWidth(width);
        column.setSortable(false);
        table.getColumns().add(column);
    }

    static String money(BigDecimal rate) { return rate == null ? "—" : "PKR " + rate.toPlainString(); }
    static String updated(Instant date) { return date == null ? "—" : UPDATED.format(date); }
}
