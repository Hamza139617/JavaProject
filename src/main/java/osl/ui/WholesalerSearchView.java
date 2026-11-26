package osl.ui;

import static osl.ui.DirectoryControls.*;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.util.List;
import java.util.function.Consumer;
import osl.bl.AccountService.Session;
import osl.bl.WholesalerDirectory;
import osl.bl.WholesalerDirectory.PublicListing;
import osl.bl.WholesalerDirectory.PublicProfile;
import osl.bl.WholesalerDirectory.ReportReason;
import osl.bl.WholesalerDirectory.Sort;
import osl.bl.WholesalerDirectory.Summary;

final class WholesalerSearchView extends VBox {
    private record Results(List<Summary> profiles, boolean directoryHasProfiles) {}

    private final WholesalerDirectory directory;
    private final Session session;
    private final Window owner;
    private final Consumer<Boolean> busy;
    private final TextField keyword = new TextField();
    private final TextField city = new TextField();
    private final TextField category = new TextField();
    private final TextField maximumRate = new TextField();
    private final ComboBox<Sort> order = new ComboBox<>();
    private final GridPane cards = new GridPane();
    private final Label status = text("", "#53675e", 13, false);
    private final VBox catalogue = new VBox(12);
    private final VBox content = new VBox();
    private final Button searchButton;

    WholesalerSearchView(WholesalerDirectory directory, Session session, Window owner, String backLabel, Runnable back,
                         Consumer<Boolean> busy) {
        this.directory = directory;
        this.session = session;
        this.owner = owner;
        this.busy = busy;
        setSpacing(16);
        setPadding(new Insets(22));
        setStyle("-fx-background-color: #fcfdfb;");
        var navigation = bar(button(backLabel, false, back), text("Wholesaler directory", "#1d604a", 14, true));
        getChildren().addAll(navigation, content);
        VBox.setVgrow(content, Priority.ALWAYS);

        keyword.setPromptText("Business, @username or stock type");
        city.setPromptText("Any city");
        category.setPromptText("Any category");
        maximumRate.setPromptText("Any rate");
        order.getItems().setAll(Sort.values());
        order.setValue(Sort.NAME);
        searchButton = button("Search", true, this::search);
        keyword.setOnAction(event -> search());
        city.setOnAction(event -> search());
        category.setOnAction(event -> search());
        maximumRate.setOnAction(event -> search());
        var keywordField = field("Keyword", keyword);
        var clear = button("Clear filters", false, () -> {
            keyword.clear();
            city.clear();
            category.clear();
            maximumRate.clear();
            order.setValue(Sort.NAME);
            search();
        });
        var searchRow = actionRow(10, keywordField, searchButton, clear);
        var filters = actionRow(10, field("City", city), field("Category", category),
                field("Maximum unit rate (PKR)", maximumRate), field("Sort", order));

        cards.setHgap(12);
        cards.setVgap(12);
        cards.setPadding(new Insets(1, 8, 10, 1));
        var results = scroll(cards);
        VBox.setVgrow(results, Priority.ALWAYS);
        catalogue.getChildren().addAll(text("Find wholesalers", "#19332a", 29, true), searchRow, filters, status, results);
        content.getChildren().setAll(catalogue);
        VBox.setVgrow(catalogue, Priority.ALWAYS);
        search();
    }

    private void search() {
        catalogue.setDisable(true);
        message(status, "Loading wholesalers…", false);
        String query = keyword.getText();
        String location = city.getText();
        String type = category.getText();
        String rate = maximumRate.getText();
        Sort sort = order.getValue();
        boolean filtering = !(query.isBlank() && location.isBlank() && type.isBlank() && rate.isBlank());
        run(() -> {
            var profiles = directory.search(session, query, location, type, rate, sort);
            return new Results(profiles, !profiles.isEmpty() || (filtering && directory.hasPublishedProfiles(session)));
        }, results -> {
            var profiles = results.profiles();
            catalogue.setDisable(false);
            showCards(profiles);
            String summary = profiles.isEmpty()
                    ? (results.directoryHasProfiles() ? "No wholesalers match these filters. Clear filters to browse the directory."
                       : "No public wholesaler profiles yet. A wholesaler must publish a profile before retailers can find it.")
                    : profiles.size() + (profiles.size() == 1 ? " wholesaler found" : " wholesalers found");
            message(status, summary, false);
        }, failure -> {
            catalogue.setDisable(false);
            cards.getChildren().clear();
            message(status, error("Could not load wholesalers.", failure), true);
        });
    }

    private void showCards(List<Summary> profiles) {
        cards.getChildren().clear();
        cards.getColumnConstraints().clear();
        int columns = Math.min(3, profiles.size());
        for (int i = 0; i < columns; i++) {
            var column = new ColumnConstraints();
            column.setPercentWidth(100.0 / columns);
            cards.getColumnConstraints().add(column);
        }
        for (int i = 0; i < profiles.size(); i++) {
            cards.add(card(profiles.get(i)), i % columns, i / columns);
        }
    }

    private VBox card(Summary summary) {
        String description = summary.description();
        if (description.length() > 120) description = description.substring(0, 120).stripTrailing() + "…";
        var name = text(summary.businessName(), "#19332a", 17, true);
        var identity = text("@" + summary.username() + " · " + summary.city(), "#53675e", 13, false);
        var offered = text(summary.matchingListings() + (summary.matchingListings() == 1 ? " stock type" : " stock types"),
                "#53675e", 13, false);
        var facts = summary.lowestRate() == null ? offered
                : new VBox(4, offered, text("From " + money(summary.lowestRate()), "#1d604a", 13, true));
        var tile = new VBox(7, name, identity);
        if (!description.isBlank()) tile.getChildren().add(text(description, "#53675e", 13, false));
        var view = button("View profile", false, () -> open(summary.businessId()));
        view.setMaxWidth(Double.MAX_VALUE);
        tile.getChildren().addAll(facts, view);
        tile.setPadding(new Insets(14));
        tile.setStyle("-fx-background-color: #f2f7f4; -fx-border-color: #d4e5dc;"
                + "-fx-border-radius: 11; -fx-background-radius: 11;");
        return tile;
    }

    private void open(long businessId) {
        catalogue.setDisable(true);
        message(status, "Loading profile…", false);
        run(() -> directory.profile(session, businessId), profile -> {
            catalogue.setDisable(false);
            if (profile.isPresent()) {
                display(profile.get());
            } else {
                cards.getChildren().clear();
                message(status, "That profile is no longer available. Search again for updated results.", true);
            }
        }, failure -> {
            catalogue.setDisable(false);
            message(status, error("Could not open the profile.", failure), true);
        });
    }

    private void display(PublicProfile profile) {
        var title = text(profile.businessName(), "#19332a", 29, true);
        var contact = text(profile.contactName() + " · @" + profile.username(), "#315c4c", 15, true);
        var location = text(profile.address().isBlank() ? profile.city() : profile.city() + " · " + profile.address(),
                "#53675e", 14, false);
        var overview = new VBox(7, title, contact, location);
        if (profile.phone() != null) overview.getChildren().add(text("Phone " + profile.phone(), "#19332a", 14, true));
        if (!profile.description().isBlank()) overview.getChildren().add(text(profile.description(), "#53675e", 14, false));
        overview.getChildren().add(text("Profile updated " + updated(profile.updatedAt()), "#53675e", 12, false));

        var listings = new TableView<PublicListing>();
        listings.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        listings.setPlaceholder(new Label("This wholesaler has not published any stock types."));
        column(listings, "Stock type", PublicListing::product, 150);
        column(listings, "Category", PublicListing::category, 125);
        column(listings, "Unit", row -> row.unit().toString(), 70);
        column(listings, "Advertised rate", row -> money(row.rate()), 130);
        column(listings, "Rate updated", row -> updated(row.updatedAt()), 105);
        listings.getItems().setAll(profile.listings());

        Label feedback = text("", "#53675e", 13, false);
        Button report = button("Report wholesaler", false,
                () -> report(profile, listings.getSelectionModel().getSelectedItem(), feedback));
        report.setDisable(profile.listings().isEmpty());
        var publicDetails = new VBox(10, button("Back to results", false, () -> {
            content.getChildren().setAll(catalogue);
            search();
        }), overview, bar(text("Listed stock types", "#19332a", 19, true), report), listings, feedback);
        VBox.setVgrow(listings, Priority.ALWAYS);
        var page = scroll(publicDetails);
        VBox.setVgrow(page, Priority.ALWAYS);
        content.getChildren().setAll(page);
    }

    private void report(PublicProfile profile, PublicListing listing, Label feedback) {
        var send = new ButtonType("Submit report", ButtonData.OK_DONE);
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(owner);
        dialog.setResizable(true);
        dialog.setTitle("Report wholesaler");
                dialog.setHeaderText(profile.businessName() + " · @" + profile.username());
        var evidence = new ComboBox<PublicListing>();
        evidence.getItems().setAll(profile.listings());
        evidence.setPromptText("Select the listing to include");
        evidence.setValue(listing);
        evidence.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(PublicListing row) {
                return row == null ? "" : row.product() + " · " + row.category() + " · " + money(row.rate());
            }
            @Override public PublicListing fromString(String value) { return null; }
        });
        var reason = new ComboBox<ReportReason>();
        reason.getItems().setAll(ReportReason.values());
        reason.setPromptText("Select a reason");
        var comments = new TextArea();
        comments.setPromptText("Optional context for the administrator");
        comments.setWrapText(true);
        comments.setPrefRowCount(3);
        var status = text("", "#963b2c", 13, false);
        var pane = dialog.getDialogPane();
        pane.setContent(new VBox(10, field("Listing evidence", evidence), field("Reason", reason), field("Comments", comments), status));
        pane.getButtonTypes().addAll(send, ButtonType.CANCEL);
        pane.lookupButton(send).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            PublicListing product = evidence.getValue();
            if (product == null) {
                message(status, "Choose the listing that prompted your report.", true);
                return;
            }
            ReportReason selected = reason.getValue();
            String note = comments.getText();
            pane.setDisable(true);
            dialog.setOnCloseRequest(close -> close.consume());
            busy.accept(true);
            run(() -> {
                directory.report(session, profile.businessId(), product.productId(), selected, note);
                return null;
            }, saved -> {
                busy.accept(false);
                dialog.setOnCloseRequest(null);
                pane.setDisable(false);
                dialog.close();
                message(feedback, "Wholesaler report sent for administrator review.", false);
            }, failure -> {
                busy.accept(false);
                dialog.setOnCloseRequest(null);
                pane.setDisable(false);
                message(status, error("Could not submit the report.", failure), true);
            });
        });
        dialog.showAndWait();
    }
}
