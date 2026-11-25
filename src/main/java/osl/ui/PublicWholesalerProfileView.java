package osl.ui;

import static osl.ui.DirectoryControls.*;

import java.util.List;
import java.util.function.Consumer;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
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
import osl.bl.WholesalerDirectory;
import osl.bl.WholesalerDirectory.CatalogListing;
import osl.bl.WholesalerDirectory.ProfileSettings;

final class PublicWholesalerProfileView extends VBox {
    private record Initial(ProfileSettings settings, List<CatalogListing> catalog) {}

    private final WholesalerDirectory directory;
    private final Session session;
    private final Window owner;
    private final Consumer<Boolean> busy;
    private final TextField city = new TextField();
    private final TextField address = new TextField();
    private final TextArea about = new TextArea();
    private final CheckBox published = new CheckBox("Publish profile in the retailer directory");
    private final CheckBox phone = new CheckBox("Show my phone number");
    private final Label profileStatus = text("", "#53675e", 13, false);
    private final Label listingStatus = text("", "#53675e", 13, false);
    private final Label initialStatus = text("", "#53675e", 13, false);
    private final TabPane tabs = new TabPane();
    private final TableView<CatalogListing> catalog = new TableView<>();
    private final VBox profileForm;
    private final VBox listingSection;
    private final Button retry;

    PublicWholesalerProfileView(WholesalerDirectory directory, Session session, Window owner, Runnable back,
                                Consumer<Boolean> busy) {
        this.directory = directory;
        this.session = session;
        this.owner = owner;
        this.busy = busy;
        setPadding(new Insets(22));
        setSpacing(14);
        setStyle("-fx-background-color: #fcfdfb; -fx-background-radius: 19;");
        profileForm = profileForm();
        listingSection = listingSection();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Profile details", scroll(profileForm)), new Tab("Stock listings", listingSection));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        retry = button("Reload profile", false, this::load);
        retry.setVisible(false);
        retry.setManaged(false);
        getChildren().addAll(bar(button("Back to workspace", false, back), text(session.businessName(), "#1d604a", 14, true)),
                text("Public wholesaler profile", "#19332a", 29, true), retry, initialStatus, tabs);
        load();
    }

    private VBox profileForm() {
        city.setPromptText("e.g. Islamabad");
        address.setPromptText("Business address to show retailers");
        about.setPromptText("Describe what you supply and where you operate.");
        about.setWrapText(true);
        about.setPrefRowCount(4);
        var identity = text(session.businessName() + " · " + session.fullName() + " · @" + session.username(),
                "#1d604a", 15, true);
        var guidance = text("Publishing makes this identity, your public location, and your listed stock types visible to retailers.",
                "#53675e", 13, false);
        HBox locations = new HBox(12, field("City", city), field("Public address (optional)", address));
        HBox.setHgrow(locations.getChildren().getLast(), Priority.ALWAYS);
        var form = new VBox(12, identity, guidance, locations, field("About the business (optional)", about),
                phone, published, button("Save profile", true, this::saveProfile), profileStatus);
        form.setPadding(new Insets(14, 10, 12, 1));
        return form;
    }

    private VBox listingSection() {
        catalog.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        catalog.setPlaceholder(new Label("Add products in Manage Stock before publishing stock types."));
        column(catalog, "Catalog stock type", CatalogListing::product, 160);
        column(catalog, "Unit", row -> row.unit().toString(), 60);
        column(catalog, "Visibility", row -> row.listed() ? "Listed" : "Private", 85);
        column(catalog, "Category", row -> row.category() == null ? "—" : row.category(), 125);
        column(catalog, "Advertised rate", row -> money(row.rate()), 115);
        column(catalog, "Rate updated", row -> updated(row.updatedAt()), 110);

        var edit = button("Publish / update listing", true, () -> edit(catalog.getSelectionModel().getSelectedItem()));
        edit.disableProperty().bind(catalog.getSelectionModel().selectedItemProperty().isNull());
        var hide = button("Unlist stock type", false, () -> unlist(catalog.getSelectionModel().getSelectedItem()));
        hide.setDisable(true);
        catalog.getSelectionModel().selectedItemProperty().addListener((observable, old, row) ->
                hide.setDisable(row == null || !row.listed()));
        var refresh = button("Refresh catalog", false, () -> reloadCatalog(0, ""));
        var tools = new HBox(9, edit, hide, refresh);
        var body = new VBox(10, text("Selected stock types appear while your profile is published.",
                "#53675e", 13, false), tools, catalog, listingStatus);
        body.setPadding(new Insets(12, 0, 0, 0));
        VBox.setVgrow(catalog, Priority.ALWAYS);
        return body;
    }

    private void load() {
        retry.setVisible(false);
        retry.setManaged(false);
        tabs.setDisable(true);
        message(initialStatus, "Loading profile…", false);
        run(() -> new Initial(directory.settings(session), directory.catalog(session)), initial -> {
            tabs.setDisable(false);
            message(initialStatus, "", false);
            var settings = initial.settings();
            published.setSelected(settings.published());
            city.setText(settings.city());
            address.setText(settings.address());
            about.setText(settings.description());
            phone.setSelected(settings.showPhone());
            catalog.getItems().setAll(initial.catalog());
            message(profileStatus, visibility(settings.published()), false);
        }, failure -> {
            retry.setVisible(true);
            retry.setManaged(true);
            message(initialStatus, error("Could not load your profile.", failure), true);
        });
    }

    private void saveProfile() {
        ProfileSettings settings = new ProfileSettings(published.isSelected(), city.getText(), address.getText(),
                about.getText(), phone.isSelected());
        setDisable(true);
        busy.accept(true);
        message(profileStatus, "Saving profile…", false);
        run(() -> {
            directory.saveProfile(session, settings);
            return settings.published();
        }, visible -> {
            busy.accept(false);
            setDisable(false);
            message(profileStatus, "Profile saved. " + visibility(visible), false);
        }, failure -> {
            busy.accept(false);
            setDisable(false);
            message(profileStatus, error("Could not save your profile.", failure), true);
        });
    }

    private void edit(CatalogListing row) {
        var save = new ButtonType("Save listing", ButtonData.OK_DONE);
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(owner);
        dialog.setTitle("Publish stock type");
        dialog.setHeaderText(row.product() + " · per " + row.unit());
        var category = new TextField(row.category() == null ? "" : row.category());
        category.setPromptText("e.g. Packaged foods");
        var rate = new TextField(row.rate() == null ? "" : row.rate().toPlainString());
        rate.setPromptText("Public price in PKR");
        var status = text("", "#963b2c", 13, false);
        var pane = dialog.getDialogPane();
        pane.setContent(new VBox(10, field("Category", category), field("Advertised rate (PKR)", rate), status));
        pane.setPrefWidth(380);
        pane.getButtonTypes().addAll(save, ButtonType.CANCEL);
        pane.lookupButton(save).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            String selectedCategory = category.getText();
            String advertisedRate = rate.getText();
            pane.setDisable(true);
            dialog.setOnCloseRequest(close -> close.consume());
            busy.accept(true);
            run(() -> {
                directory.saveListing(session, row.productId(), selectedCategory, advertisedRate);
                return null;
            }, saved -> {
                busy.accept(false);
                dialog.setOnCloseRequest(null);
                pane.setDisable(false);
                dialog.close();
                reloadCatalog(row.productId(), "Stock listing saved.");
            }, failure -> {
                busy.accept(false);
                dialog.setOnCloseRequest(null);
                pane.setDisable(false);
                message(status, error("Could not save the listing.", failure), true);
            });
        });
        dialog.showAndWait();
    }

    private void unlist(CatalogListing row) {
        var confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove " + row.product() + " from your public listings?", ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(owner);
        confirm.setTitle("Unlist stock type");
        confirm.setHeaderText(row.product());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        setDisable(true);
        busy.accept(true);
        run(() -> {
            directory.unlist(session, row.productId());
            return row.productId();
        }, id -> {
            busy.accept(false);
            setDisable(false);
            reloadCatalog(id, "Stock type removed from the directory.");
        }, failure -> {
            busy.accept(false);
            setDisable(false);
            message(listingStatus, error("Could not unlist the stock type.", failure), true);
        });
    }

    private void reloadCatalog(long selected, String confirmation) {
        listingSection.setDisable(true);
        message(listingStatus, "Loading catalog…", false);
        run(() -> directory.catalog(session), list -> {
            listingSection.setDisable(false);
            catalog.getItems().setAll(list);
            list.stream().filter(row -> row.productId() == selected).findFirst()
                    .ifPresent(catalog.getSelectionModel()::select);
            message(listingStatus, confirmation, false);
        }, failure -> {
            listingSection.setDisable(false);
            message(listingStatus, error(confirmation + " Could not reload the catalog.", failure).strip(), true);
        });
    }

    private static String visibility(boolean published) {
        return published ? "Your profile is visible to retailers." : "Your profile is hidden.";
    }
}
