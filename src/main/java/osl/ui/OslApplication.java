package osl.ui;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

public class OslApplication extends Application {
    @Override
    public void start(Stage stage) {
        Label brand = new Label("OMNI SALES LOGISTICS ENGINE");
        brand.setStyle("-fx-text-fill: #b9f5d5; -fx-font-size: 12px; -fx-font-weight: bold;");
        Label headline = new Label("A clear view of\nstock and sales.");
        headline.setStyle("-fx-text-fill: #f4fff8; -fx-font-size: 39px; -fx-font-weight: bold;");
        Label description = new Label("Manage inventory, record sales, and discover wholesalers.");
        description.setWrapText(true);
        description.setStyle("-fx-text-fill: #c1dcd1; -fx-font-size: 15px;");
        VBox identity = new VBox(24, brand, headline, description);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setPadding(new Insets(44));
        identity.setStyle("-fx-background-color: linear-gradient(to bottom right, #153e35, #0b2628); -fx-background-radius: 22 0 0 22;");

        Label heading = new Label("Sign in");
        heading.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: #19332a;");
        Label intro = new Label("Access your business workspace.");
        intro.setStyle("-fx-text-fill: #53675e; -fx-font-size: 15px;");
        TextField username = new TextField();
        username.setPromptText("Enter your username");
        PasswordField password = new PasswordField();
        password.setPromptText("Enter your password");
        VBox usernameInput = field("Username", username);
        VBox passwordInput = field("Password", password);

        Label feedback = new Label();
        feedback.setWrapText(true);
        feedback.setMinHeight(38);
        feedback.setStyle("-fx-text-fill: #875024;");
        Button signIn = new Button("Sign in");
        signIn.setDefaultButton(true);
        signIn.setMaxWidth(Double.MAX_VALUE);
        signIn.setMinHeight(45);
        signIn.setStyle("-fx-background-color: #1d604a; -fx-background-radius: 10; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        signIn.setOnAction(event -> {
            if (username.getText().isBlank()) {
                feedback.setText("Enter your username.");
                username.requestFocus();
            } else if (password.getText().isEmpty()) {
                feedback.setText("Enter your password.");
                password.requestFocus();
            } else {
                password.clear();
                feedback.setText("Database sign-in is pending.");
            }
        });

        VBox form = new VBox(15, heading, intro, usernameInput, passwordInput, signIn, feedback);
        form.setAlignment(Pos.CENTER_LEFT);
        form.setPadding(new Insets(38));
        form.setStyle("-fx-background-color: #fcfdfb; -fx-background-radius: 0 22 22 0;");

        GridPane card = new GridPane();
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(51);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(49);
        card.getColumnConstraints().addAll(left, right);
        card.addRow(0, identity, form);
        GridPane.setVgrow(identity, Priority.ALWAYS);
        GridPane.setVgrow(form, Priority.ALWAYS);
        card.setMaxSize(950, 520);
        card.setStyle("-fx-effect: dropshadow(gaussian, #173d2e25, 32, 0, 0, 10);");

        StackPane root = new StackPane(card);
        root.setPadding(new Insets(28));
        root.setStyle("-fx-background-color: #edf4ef; -fx-font-family: 'Segoe UI'; -fx-font-size: 14px;");
        stage.setTitle("Omni Sales Logistics Engine");
        stage.setScene(new Scene(root, 1040, 620));
        stage.setMinWidth(800);
        stage.setMinHeight(530);
        stage.show();
    }

    private VBox field(String caption, TextField input) {
        Label label = new Label(caption);
        label.setLabelFor(input);
        label.setStyle("-fx-text-fill: #284439; -fx-font-weight: bold;");
        input.setMinHeight(43);
        input.setStyle("-fx-background-radius: 9; -fx-border-radius: 9; -fx-border-color: #b7c9bf; -fx-background-color: white;");
        return new VBox(6, label, input);
    }

    public static void main(String[] args) {
        launch(args);
    }
}