package osl.ui;

import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;

final class WorkspaceDashboard extends VBox {
    record Action(String title, Runnable open) {}
    record Section(String title, List<Action> actions) {}

    WorkspaceDashboard(Session session, String notice, List<Section> sections, List<AlertRow> alerts,
                       Runnable recordSale, Runnable editProfile, Runnable signOut) {
        getStyleClass().add("workspace-dashboard");
        VBox page = new VBox(18);
        page.getStyleClass().add("dashboard-page");
        page.setMaxWidth(1380);

        VBox quickActions = new VBox(12);
        for (Section section : sections) quickActions.getChildren().add(workflowSection(section));

        VBox sidebar = new VBox(12, alerts(alerts), profile(session, editProfile));
        GridPane columns = new GridPane();
        columns.setHgap(16);
        columns.setVgap(14);
        columns.addRow(0, quickActions, sidebar);
        var main = new ColumnConstraints();
        main.setHgrow(Priority.ALWAYS);
        var right = new ColumnConstraints(278, 310, 345);
        columns.getColumnConstraints().setAll(main, right);
        GridPane.setValignment(sidebar, javafx.geometry.VPos.TOP);

        page.getChildren().add(hero(session, recordSale));
        if (notice != null && !notice.isBlank()) {
            page.getChildren().add(label(notice, "dashboard-feedback"));
        }
        page.getChildren().add(columns);
        StackPane centered = new StackPane(page);
        centered.setAlignment(Pos.TOP_CENTER);
        getChildren().addAll(topbar(signOut), centered);
    }

    private static StackPane topbar(Runnable signOut) {
        HBox brand = new HBox(11, label("OSL", "dashboard-mark"),
                label("Omni Sales Logistics Engine", "dashboard-brand"));
        brand.setAlignment(Pos.CENTER_LEFT);
        HBox row = DirectoryControls.bar(brand, button(new Action("Sign out", signOut), "dashboard-signout"));
        row.setMaxWidth(1380);
        row.getStyleClass().add("dashboard-masthead");
        StackPane bar = new StackPane(row);
        bar.getStyleClass().add("dashboard-topbar");
        return bar;
    }

    private static HBox hero(Session session, Runnable recordSale) {
        var identity = new VBox(6, label(session.role() == Role.RETAILER ? "Retailer dashboard" : "Wholesaler dashboard",
                "dashboard-context"), label(session.businessName(), "dashboard-title"));
        HBox banner = DirectoryControls.bar(identity, button(new Action("Record sale", recordSale), "dashboard-primary"));
        identity.setMinWidth(0);
        banner.getStyleClass().add("dashboard-hero");
        return banner;
    }

    private static VBox workflowSection(Section section) {
        GridPane links = new GridPane();
        links.setHgap(8);
        links.setVgap(8);
        for (int index = 0; index < 2; index++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(50);
            links.getColumnConstraints().add(column);
        }
        for (int index = 0; index < section.actions().size(); index++) {
            Button link = button(section.actions().get(index), "dashboard-task");
            link.setMaxWidth(Double.MAX_VALUE);
            link.setMaxHeight(Double.MAX_VALUE);
            link.setMinWidth(0);
            link.setWrapText(true);
            int span = section.actions().size() == 1 ? 2 : 1;
            links.add(link, index % 2, index / 2, span, 1);
        }
        return card(label(section.title(), "dashboard-section-title"), links);
    }

    private static VBox alerts(List<AlertRow> rows) {
        VBox card = card(label("Alerts and notifications", "dashboard-section-title"));
        card.getChildren().addAll(rows);
        return card;
    }

    private static VBox profile(Session session, Runnable edit) {
        HBox heading = DirectoryControls.bar(label("Profile details", "dashboard-section-title"),
                button(new Action("Edit profile", edit), "dashboard-edit"));
        VBox details = new VBox(10,
                profileField("Full name", session.fullName()),
                profileField("Username", session.username()),
                profileField("Contact number", session.phone()));
        return card(heading, details);
    }

    private static VBox profileField(String name, String value) {
        return new VBox(2, label(name, "dashboard-field-name"), label(value, "dashboard-field-value"));
    }

    private static VBox card(javafx.scene.Node... children) {
        VBox card = new VBox(11, children);
        card.getStyleClass().add("dashboard-card");
        return card;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.getStyleClass().add(style);
        return label;
    }

    private static Button button(Action action, String style) {
        Button button = new Button(action.title());
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.open().run());
        return button;
    }

    static final class AlertRow extends HBox {
        private final Label detail;
        private final Label count = label("", "dashboard-count");

        AlertRow(String title, Runnable open) {
            var action = button(new Action(title, open), "dashboard-alert-link");
            detail = label("Checking for updates…", "dashboard-alert-detail");
            VBox copy = new VBox(2, action, detail);
            HBox.setHgrow(copy, Priority.ALWAYS);
            setSpacing(10);
            setAlignment(Pos.CENTER_LEFT);
            getStyleClass().add("dashboard-alert-row");
            getChildren().addAll(copy, count);
            count.setMinWidth(Region.USE_PREF_SIZE);
            count.managedProperty().bind(count.visibleProperty());
            count.setVisible(false);
        }

        void update(long unread, String message) {
            detail.setText(message);
            count.setText(Long.toString(unread));
            count.setVisible(unread > 0);
        }

        void unavailable() {
            update(0, "Updates unavailable. Open to try again.");
        }
    }
}