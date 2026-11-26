package osl.ui;

import static osl.ui.DirectoryControls.*;

import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import osl.bl.AccountService.Session;
import osl.bl.WholesalerDirectory;
import osl.bl.WholesalerDirectory.ProfileSettings;

final class PublicWholesalerProfileView extends VBox {
    private final WholesalerDirectory directory;
    private final Session session;
    private final Consumer<Boolean> busy;
    private final TextField city = new TextField();
    private final TextField address = new TextField();
    private final TextArea about = new TextArea();
    private final CheckBox published = new CheckBox("Publish profile in the retailer directory");
    private final CheckBox phone = new CheckBox("Show my phone number");
    private final Label profileStatus = text("", "#53675e", 13, false);
    private final VBox form;
    private final Button retry;

    PublicWholesalerProfileView(WholesalerDirectory directory, Session session, Runnable back,
                                Runnable manageListings, Consumer<Boolean> busy) {
        this.directory = directory;
        this.session = session;
        this.busy = busy;
        setPadding(new Insets(22));
        setSpacing(14);
        form = profileForm();
        retry = button("Reload profile", false, this::load);
        var pane = scroll(form);
        VBox.setVgrow(pane, Priority.ALWAYS);
        getChildren().addAll(bar(button("Back to workspace", false, back), text(session.businessName(), "#1d604a", 14, true)),
                bar(text("Public wholesaler profile", "#19332a", 29, true),
                        button("Manage product listings", false, manageListings)), retry, profileStatus, pane);
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
        var locations = actionRow(12, field("City", city), field("Public address (optional)", address));
        var description = field("About the business (optional)", about);
        VBox.setVgrow(description, Priority.ALWAYS);
        var save = button("Save profile", true, this::saveProfile);
        save.setMaxWidth(Double.MAX_VALUE);
        var fields = new VBox(12, identity, guidance, locations, description, phone, published, save);
        fields.setPadding(new Insets(14, 0, 0, 0));
        return fields;
    }

    private void load() {
        retry.setVisible(false);
        retry.setManaged(false);
        form.setDisable(true);
        message(profileStatus, "Loading profile…", false);
        run(() -> directory.settings(session), settings -> {
            form.setDisable(false);
            published.setSelected(settings.published());
            city.setText(settings.city());
            address.setText(settings.address());
            about.setText(settings.description());
            phone.setSelected(settings.showPhone());
            message(profileStatus, visibility(settings.published()), false);
        }, failure -> {
            retry.setVisible(true);
            retry.setManaged(true);
            message(profileStatus, error("Could not load your profile.", failure), true);
        });
    }

    private void saveProfile() {
        var settings = new ProfileSettings(published.isSelected(), city.getText(), address.getText(),
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

    private static String visibility(boolean published) {
        return published ? "Your profile is visible to retailers." : "Your profile is hidden.";
    }
}
