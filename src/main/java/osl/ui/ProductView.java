package osl.ui;

import static osl.ui.DirectoryControls.*;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.ProductService;
import osl.bl.ProductService.Input;
import osl.bl.ProductService.ProductException;
import osl.bl.ProductService.ProductRow;
import osl.bl.ProductService.Removal;
import osl.bl.StockService.Unit;

final class ProductView extends VBox {
    private final ProductService products;
    private final Session session;
    private final Window owner;
    private final Consumer<Boolean> busy;
    private final boolean wholesale;
    private final ObservableList<ProductRow> rows = FXCollections.observableArrayList();
    private final FilteredList<ProductRow> matching = new FilteredList<>(rows);
    private final TableView<ProductRow> table = new TableView<>(matching);
    private final TextField search = new TextField();
    private final CheckBox archived = new CheckBox("Show archived products");
    private final Label status = text("", "#53675e", 13, false);
    private final Button add;
    private final Button edit;
    private final Button remove;
    private final Button restore;

    ProductView(ProductService products, Session session, Window owner, String backLabel, Runnable back,
                Consumer<Boolean> busy) {
        this.products = products;
        this.session = session;
        this.owner = owner;
        this.busy = busy;
        wholesale = session.role() == Role.WHOLESALER;
        setSpacing(13);
        setPadding(new Insets(22));
        setStyle("-fx-background-color: #fcfdfb;");
        add = button("Add product", true, () -> editor(null));
        edit = button("Edit selected", false, () -> editor(selected()));
        remove = button("Remove selected", false, () -> remove(selected()));
        restore = button("Restore selected", false, () -> restore(selected()));
        add.setDisable(true);
        var top = bar(button(backLabel, false, back), text(session.businessName(), "#1d604a", 14, true));
        var title = bar(text("Manage products", "#19332a", 29, true), add);
        String description = wholesale ? "Choose which stock types and selling prices appear in your published profile."
                : "Manage the items and prices offered to your buyers.";
        search.setPromptText("Find products by name or category");
        search.setAccessibleText("Filter products by name or category");
        var filter = actionRow(10, search, archived, button("Refresh", false, () -> reload(0, "")));
        filter.setAlignment(Pos.CENTER_LEFT);
        configureTable();
        getChildren().addAll(top, title, text(description, "#53675e", 14, false), filter, status, table,
                actionRow(9, edit, remove, restore));
        VBox.setVgrow(table, Priority.ALWAYS);
        search.textProperty().addListener((observable, old, value) -> filter());
        archived.selectedProperty().addListener((observable, old, value) -> filter());
        table.getSelectionModel().selectedItemProperty().addListener((observable, old, value) -> updateActions());
        updateActions();
        reload(0, "");
    }

    private void configureTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("Add a product to start your catalog."));
        column(table, "Product", ProductRow::name, 175);
        column(table, "Category", row -> row.category() == null ? "—" : row.category(), 135);
        column(table, "Unit", row -> row.unit().toString(), 75);
        column(table, "Selling price", row -> money(row.sellingPrice()), 125);
        column(table, "Expiry required", row -> row.requiresExpiry() ? "Yes" : "No", 95);
        if (wholesale) {
            column(table, "Directory", row -> row.listed() && !row.archived() ? "Listed" : "Private", 95);
        }
        column(table, "Status", row -> row.archived() ? "Archived"
                : row.sellingPrice() == null ? "Details needed" : "Active", 110);
    }

    private void filter() {
        String term = search.getText().strip().toLowerCase(Locale.ROOT);
        matching.setPredicate(row -> (archived.isSelected() || !row.archived())
                && (row.name().toLowerCase(Locale.ROOT).contains(term)
                    || (row.category() != null && row.category().toLowerCase(Locale.ROOT).contains(term))));
        String empty = rows.isEmpty() ? "Add a product to start your catalog."
                : rows.stream().allMatch(ProductRow::archived) && !archived.isSelected()
                    ? "There are no active products. Enable Show archived products to view your catalog history."
                    : "No products match the current filters.";
        table.setPlaceholder(new Label(empty));
        updateActions();
    }

    private void reload(long id, String confirmation) {
        setDisable(true);
        message(status, "Loading products…", false);
        run(() -> products.load(session), loaded -> {
            setDisable(false);
            add.setDisable(false);
            rows.setAll(loaded);
            filter();
            matching.stream().filter(row -> row.id() == id).findFirst().ifPresent(table.getSelectionModel()::select);
            String summary = confirmation;
            if (summary.isEmpty() && loaded.stream().anyMatch(row -> !row.archived() && row.sellingPrice() == null)) {
                summary = "Some existing products need a category and selling price. Select one to complete its details.";
            }
            message(status, summary, false);
        }, failure -> {
            setDisable(false);
            message(status, error((confirmation + " Could not load products.").strip(), failure), true);
        });
    }

    private void editor(ProductRow row) {
        ButtonType save = new ButtonType("Save product", ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setResizable(true);
        dialog.setTitle(row == null ? "Add product" : "Edit product");
        dialog.setHeaderText(row == null ? "New catalog product" : row.name());

        TextField name = new TextField(row == null ? "" : row.name());
        name.setPromptText("Product name");
        ComboBox<String> category = new ComboBox<>();
        category.setEditable(true);
        category.getItems().setAll(rows.stream().map(ProductRow::category).filter(value -> value != null && !value.isBlank())
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        category.setValue(row == null ? "" : row.category());
        category.setPromptText("Choose or type a category");
        ComboBox<Unit> unit = new ComboBox<>();
        unit.getItems().setAll(Unit.values());
        unit.setValue(row == null ? null : row.unit());
        unit.setPromptText("Choose a unit");
        unit.setDisable(row != null && row.unitLocked());
        TextField price = new TextField(row == null || row.sellingPrice() == null ? "" : row.sellingPrice().toPlainString());
        price.setPromptText("Price in PKR per unit");
        CheckBox expiry = new CheckBox("Require an expiry date for new batches");
        expiry.setSelected(row != null && row.requiresExpiry());
        CheckBox listed = new CheckBox("List this stock type in my public profile");
        listed.setSelected(row != null && row.listed());
        Label error = text("", "#963b2c", 13, false);
        var fields = new VBox(10, field("Product name", name), field("Category", category), field("Unit", unit),
                field("Selling price (PKR per unit)", price), expiry);
        if (wholesale) fields.getChildren().add(listed);
        fields.getChildren().add(error);
        var pane = dialog.getDialogPane();
        pane.setContent(fields);
        pane.getButtonTypes().addAll(ButtonType.CANCEL, save);
        pane.lookupButton(save).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            Input input = new Input(name.getText(), category.getEditor().getText(), unit.getValue(), price.getText(),
                    expiry.isSelected(), wholesale && listed.isSelected());
            pane.setDisable(true);
            dialog.setOnCloseRequest(close -> close.consume());
            busy.accept(true);
            run(() -> products.save(session, row == null ? 0 : row.id(), input), id -> {
                busy.accept(false);
                dialog.setOnCloseRequest(null);
                pane.setDisable(false);
                dialog.close();
                reload(id, row == null ? "Product added." : "Product updated.");
            }, failure -> {
                busy.accept(false);
                dialog.setOnCloseRequest(null);
                pane.setDisable(false);
                message(error, error("Could not save the product.", failure), true);
            });
        });
        dialog.showAndWait();
    }

    private void remove(ProductRow row) {
        String explanation = row.unitLocked() ? "Archive " + row.name() + "? Its stock and sales history will be kept."
                : "Remove " + row.name() + " from your catalog?";
        var alert = new Alert(Alert.AlertType.CONFIRMATION, explanation, ButtonType.CANCEL, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle("Remove product");
        alert.setHeaderText(row.name());
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        change(() -> products.remove(session, row.id()), result -> reload(0, result == Removal.ARCHIVED
                ? "Product archived. Stock and sales history are preserved." : "Product removed."));
    }

    private void restore(ProductRow row) {
        change(() -> {
            products.restore(session, row.id());
            return row.id();
        }, id -> reload(id, "Product restored."));
    }

    private <T> void change(Callable<T> action, Consumer<T> result) {
        setDisable(true);
        busy.accept(true);
        run(action, saved -> {
            busy.accept(false);
            setDisable(false);
            result.accept(saved);
        }, failure -> {
            busy.accept(false);
            setDisable(false);
            message(status, error("Could not update the catalog.", failure), true);
        });
    }

    private ProductRow selected() { return table.getSelectionModel().getSelectedItem(); }

    private void updateActions() {
        ProductRow selected = selected();
        edit.setDisable(selected == null || selected.archived());
        remove.setDisable(selected == null || selected.archived());
        restore.setDisable(selected == null || !selected.archived());
    }

    private static String error(String action, Throwable failure) {
        return failure instanceof ProductException invalid ? invalid.getMessage() : DirectoryControls.error(action, failure);
    }
}
