package osl.ui;

import static osl.ui.DirectoryControls.*;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import osl.bl.AccountService.Session;
import osl.bl.CustomerService;
import osl.bl.CustomerService.Contact;
import osl.bl.CustomerService.CustomerException;
import osl.bl.CustomerService.Details;
import osl.bl.CustomerService.DuplicateCustomer;
import osl.bl.CustomerService.Interaction;
import osl.bl.CustomerService.Kind;
import osl.bl.CustomerService.Profile;
import osl.bl.CustomerService.Purchase;
import osl.bl.CustomerService.PurchasedItem;
import osl.bl.CustomerService.Relationship;

final class CustomerRelationshipsView extends VBox {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final CustomerService service;
    private final Session session;
    private final Window owner;
    private final Consumer<Boolean> busy;
    private final ObservableList<Contact> people = FXCollections.observableArrayList();
    private final FilteredList<Contact> filtered = new FilteredList<>(people);
    private final ListView<Contact> list = new ListView<>(filtered);
    private final TextField search = new TextField();
    private final Label status = text("", "#53675e", 13, false);
    private final VBox detail = new VBox(10);
    private long request;
    private long displayedCustomer;
    private int selectedTab;

    CustomerRelationshipsView(CustomerService service, Session session, Window owner, Runnable back,
                              Consumer<Boolean> busy) {
        this.service = service;
        this.session = session;
        this.owner = owner;
        this.busy = busy;
        setPadding(new Insets(22));
        setSpacing(12);
        search.setPromptText("Search name or phone");
        search.setAccessibleText("Search customer name or phone");
        search.textProperty().addListener((observable, old, value) -> filter(value));
        list.setPlaceholder(new Label("No matching customers."));
        list.getSelectionModel().selectedItemProperty().addListener((observable, old, value) -> loadProfile(value));
        VBox directory = new VBox(8, text("Customer directory", "#19362e", 15, true), search, list);
        directory.setPadding(new Insets(10));
        directory.setStyle("-fx-background-color: #f5f8f6;");
        VBox.setVgrow(list, Priority.ALWAYS);
        detail.setPadding(new Insets(10, 12, 10, 14));
        detail.setStyle("-fx-background-color: white;");
        var split = new SplitPane(directory, detail);
        split.setDividerPositions(0.28);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(bar(button("Back to workspace", false, back), text(session.businessName(), "#1d604a", 14, true)),
                bar(text("Customer relationships", "#19332a", 28, true), actionRow(8,
                        button("Refresh", false, () -> reload(selectedId(), "")),
                        button("Add customer", true, () -> edit(null)))),
                status, split);
        placeholder("Select a customer to see their relationship history.");
        reload(0, "");
    }

    private void filter(String searchText) {
        String term = searchText.strip().toLowerCase(Locale.ROOT);
        String digits = term.replaceAll("[\\s()+-]", "");
        filtered.setPredicate(row -> row.name().toLowerCase(Locale.ROOT).contains(term)
                || (!digits.isEmpty() && digits.matches("[0-9]+")
                       && (row.phone().substring(1).contains(digits)
                           || ("0" + row.phone().substring(3)).contains(digits))));
    }

    private long selectedId() {
        Contact contact = list.getSelectionModel().getSelectedItem();
        return contact == null ? 0 : contact.id();
    }

    private void reload(long id, String confirmation) {
        setDisable(true);
        message(status, "Loading customers…", false);
        run(() -> service.contacts(session), contacts -> {
            setDisable(false);
            list.getSelectionModel().clearSelection();
            people.setAll(contacts);
            if (id != 0 && filtered.stream().noneMatch(row -> row.id() == id)) search.clear();
            filtered.stream().filter(row -> row.id() == id).findFirst().ifPresent(list.getSelectionModel()::select);
            if (list.getSelectionModel().getSelectedItem() == null) {
                placeholder(contacts.isEmpty() ? "Add your first customer to start tracking visits and purchases."
                        : "Select a customer to see their relationship history.");
            }
            message(status, confirmation, false);
        }, failure -> {
            setDisable(false);
            message(status, error("Could not load customers. Select Refresh to retry.", failure), true);
        });
    }

    private void loadProfile(Contact contact) {
        long current = ++request;
        if (contact == null) {
            placeholder("Select a customer to see their relationship history.");
            return;
        }
        placeholder("Loading " + contact.name() + "…");
        run(() -> service.load(session, contact.id()), relationship -> {
            if (request == current) show(relationship);
        }, failure -> {
            if (request == current) placeholder(error("Could not load this customer. Select Refresh to retry.", failure));
        });
    }

    private void show(Relationship relationship) {
        Profile profile = relationship.profile();
        Contact contact = profile.contact();
        if (displayedCustomer != contact.id()) {
            selectedTab = 0;
            displayedCustomer = contact.id();
        }
        var heading = new VBox(4, text(contact.name(), "#19332a", 22, true), text(contact.phone(), "#53675e", 14, false));
        var tabs = new TabPane(new Tab("Overview", scroll(overview(relationship))), new Tab("Purchases", purchases(relationship.purchases())),
                new Tab("Interactions", interactions(relationship.interactions())));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().select(selectedTab);
        tabs.getSelectionModel().selectedIndexProperty().addListener((observable, old, next) -> {
            if (next.intValue() >= 0) selectedTab = next.intValue();
        });
        VBox.setVgrow(tabs, Priority.ALWAYS);
        detail.getChildren().setAll(bar(heading, actionRow(8, button("Edit customer", false, () -> edit(profile)),
                button("Record interaction", true, () -> record(contact)))), tabs);
    }

    private VBox overview(Relationship data) {
        String latest = data.purchases().isEmpty() ? "—" : TIME.format(data.purchases().getFirst().soldAt());
        String favorites = data.favorites().stream().map(CustomerRelationshipsView::itemText).collect(Collectors.joining(" · "));
        var summary = text("Purchases " + data.purchases().size() + " · Total spent " + price(data.totalSpent())
                + " · Average purchase " + price(data.averagePurchase()) + "\n"
                + "Visits " + data.visits() + " · Average feedback "
                + (data.averageRating() == null ? "—" : data.averageRating().toPlainString() + "/5")
                + " · Latest purchase " + latest, "#1d604a", 13, true);
        var spending = table("No sales or visits to summarize.", data.activity());
        column(spending, "Month", row -> MONTH.format(row.month()), 105);
        column(spending, "Purchases", row -> Long.toString(row.purchases()), 75);
        column(spending, "Spent", row -> price(row.spent()), 110);
        column(spending, "Visits", row -> Long.toString(row.visits()), 70);
        var body = new VBox(8, summary,
                text("Preferences: " + shown(data.profile().preferences()), "#53675e", 13, false),
                text("Notes: " + shown(data.profile().notes()), "#53675e", 13, false),
                text("Most purchased by unit: " + shown(favorites), "#53675e", 13, false),
                text("Monthly activity", "#19332a", 16, true), spending);
        VBox.setVgrow(spending, Priority.ALWAYS);
        body.setPadding(new Insets(10, 0, 0, 0));
        return body;
    }

    private TableView<Purchase> purchases(List<Purchase> rows) {
        var table = table("Sales linked to this customer will appear here.", rows);
        column(table, "Sale", row -> "#" + row.id(), 55);
        column(table, "Date and time", row -> TIME.format(row.soldAt()), 133);
        column(table, "Items", row -> row.items().stream().map(CustomerRelationshipsView::itemText)
                .collect(Collectors.joining(", ")), 220);
        column(table, "Total", row -> price(row.total()), 90);
        return table;
    }

    private TableView<Interaction> interactions(List<Interaction> rows) {
        var table = table("Record a visit or feedback to start the interaction history.", rows);
        column(table, "Date", row -> DATE.format(row.date()), 95);
        column(table, "Type", row -> row.kind().toString(), 83);
        column(table, "Rating", row -> row.rating() == null ? "—" : row.rating() + "/5", 65);
        column(table, "Notes", row -> shown(row.notes()), 240);
        return table;
    }

    private void edit(Profile current) {
        var name = new TextField(current == null ? "" : current.contact().name());
        name.setPromptText("Customer name");
        var phone = new TextField(current == null ? "" : current.contact().phone());
        phone.setPromptText("0300 1234567 or +92 300 1234567");
        var preferences = area(current == null ? "" : current.preferences(), "e.g. preferred brands or pack sizes", 2);
        var notes = area(current == null ? "" : current.notes(), "Optional shop notes", 3);
        var fields = new VBox(10, field("Name", name), field("Mobile number", phone),
                field("Preferences", preferences), field("Notes", notes));
        editDialog(current == null ? "Add customer" : "Edit customer", "Save customer", fields, () -> {
            Details input = new Details(name.getText(), phone.getText(), preferences.getText(), notes.getText());
            return () -> service.save(session, current == null ? 0 : current.contact().id(), input);
        }, id -> reload(id, current == null ? "Customer added." : "Customer updated."));
    }

    private void record(Contact contact) {
        var kind = new ComboBox<Kind>();
        kind.getItems().setAll(Kind.values());
        kind.setValue(Kind.VISIT);
        var day = new DatePicker(service.today());
        var rating = new ComboBox<Integer>();
        rating.getItems().setAll(1, 2, 3, 4, 5);
        rating.setPromptText("Optional");
        var ratingField = field("Rating out of 5", rating);
        ratingField.setVisible(false);
        ratingField.setManaged(false);
        kind.valueProperty().addListener((observable, old, next) -> {
            boolean feedback = next == Kind.FEEDBACK;
            ratingField.setVisible(feedback);
            ratingField.setManaged(feedback);
            if (!feedback) rating.setValue(null);
        });
        var notes = area("", "Optional visit notes or feedback comments", 4);
        var fields = new VBox(10, field("Interaction", kind), field("Date", day), ratingField, field("Notes", notes));
        editDialog("Record interaction for " + contact.name(), "Save interaction", fields, () -> {
            day.commitValue();
            Interaction input = new Interaction(kind.getValue(), day.getValue(), rating.getValue(), notes.getText());
            return () -> {
                service.recordInteraction(session, contact.id(), input);
                return contact.id();
            };
        }, id -> {
            selectedTab = 2;
            loadProfile(contact);
            message(status, "Interaction saved.", false);
        });
    }

    private void editDialog(String title, String action, VBox fields, Supplier<Callable<Long>> values, Consumer<Long> saved) {
        var submit = new ButtonType(action, ButtonData.OK_DONE);
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(owner);
        dialog.setResizable(true);
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        var pane = dialog.getDialogPane();
        var feedback = text("", "#963b2c", 13, false);
        var duplicateAction = new HBox(8);
        fields.getChildren().addAll(feedback, duplicateAction);
        pane.setContent(fields);
        pane.getButtonTypes().addAll(ButtonType.CANCEL, submit);
        pane.lookupButton(submit).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            Callable<Long> edit;
            try {
                edit = values.get();
            } catch (DateTimeParseException invalid) {
                message(feedback, "Choose a valid interaction date.", true);
                return;
            }
            duplicateAction.getChildren().clear();
            busy.accept(true);
            pane.setDisable(true);
            dialog.setOnCloseRequest(close -> close.consume());
            run(edit, id -> {
                finishDialog(dialog);
                dialog.close();
                saved.accept(id);
            }, failure -> {
                finishDialog(dialog);
                message(feedback, error("Could not save.", failure), true);
                if (failure instanceof DuplicateCustomer duplicate) {
                    duplicateAction.getChildren().setAll(button("Open existing customer", false, () -> {
                        dialog.close();
                        reload(duplicate.existing().id(), "Opened the existing customer record.");
                    }));
                }
            });
        });
        dialog.showAndWait();
    }

    private void finishDialog(Dialog<?> dialog) {
        busy.accept(false);
        dialog.getDialogPane().setDisable(false);
        dialog.setOnCloseRequest(null);
    }

    private void placeholder(String text) {
        detail.getChildren().setAll(text(text, "#53675e", 14, false));
    }

    private static TextArea area(String value, String prompt, int lines) {
        var field = new TextArea(value);
        field.setPromptText(prompt);
        field.setPrefRowCount(lines);
        field.setWrapText(true);
        return field;
    }

    private static <T> TableView<T> table(String empty, List<T> items) {
        var view = new TableView<T>();
        view.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        view.setPlaceholder(new Label(empty));
        view.getItems().setAll(items);
        return view;
    }

    private static String error(String action, Throwable failure) {
        if (failure instanceof CustomerException || failure instanceof DuplicateCustomer) return failure.getMessage();
        return DirectoryControls.error(action, failure);
    }

    private static String itemText(PurchasedItem item) {
        return item.name() + " × " + item.quantity().stripTrailingZeros().toPlainString() + " " + item.unit();
    }

    private static String price(BigDecimal value) { return money(value.setScale(2)); }
    private static String shown(String value) { return value.isBlank() ? "—" : value; }
}
