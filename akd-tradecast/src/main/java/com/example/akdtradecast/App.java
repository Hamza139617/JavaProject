package com.example.akdtradecast;

import java.util.Arrays;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * AKD TradeCast - Authentication screen.
 * Frontend-only JavaFX implementation; no backend or database is used.
 */
public class App extends Application {

    private static final int[] REQUIRED_PASSWORD_POSITIONS = {3, 5, 7, 8};
    private static final int PASSWORD_LENGTH = 10;

    private final TextField[] passwordFields = new TextField[PASSWORD_LENGTH];
    private TextField userIdField;
    private Label instructionLabel;
    private Label errorLabel;
    private Stage primaryStage;

    private double dragOffsetX;
    private double dragOffsetY;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("Trade Cast v1.4.0");
        stage.setResizable(false);

        BorderPane window = new BorderPane();
        window.setStyle(
            "-fx-background-color: #f4f4f1;" +
            "-fx-border-color: #7f8b9b;" +
            "-fx-border-width: 1px;"
        );

        window.setTop(createTitleBar(stage));
        window.setCenter(createLoginContent());
        window.setBottom(createFooter());

        Scene scene = new Scene(window, 790, 465);
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();

        Platform.runLater(() -> userIdField.requestFocus());
    }

    private HBox createTitleBar(Stage stage) {
        HBox titleBar = new HBox(8);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(4, 7, 4, 9));
        titleBar.setPrefHeight(29);
        titleBar.setStyle(
            "-fx-background-color: linear-gradient(to bottom, #2679d8, #0756b5);"
        );

        Ellipse appIcon = new Ellipse(6, 6);
        appIcon.setFill(Color.web("#ff8a22"));
        appIcon.setStroke(Color.web("#e45c13"));

        Label title = new Label("Trade Cast v1.4.0");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Tahoma", FontWeight.BOLD, 12));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimizeButton = titleBarButton("—");
        minimizeButton.setOnAction(event -> stage.setIconified(true));

        Button closeButton = titleBarButton("×");
        closeButton.setOnAction(event -> stage.close());

        titleBar.getChildren().addAll(appIcon, title, spacer, minimizeButton, closeButton);
        enableWindowDragging(titleBar, stage);
        return titleBar;
    }

    private Button titleBarButton(String text) {
        Button button = new Button(text);
        button.setFocusTraversable(false);
        button.setPrefSize(25, 20);
        button.setStyle(
            "-fx-background-color: #dce8f5;" +
            "-fx-border-color: #ffffff #264f83 #264f83 #ffffff;" +
            "-fx-border-width: 1px;" +
            "-fx-text-fill: #102c4c;" +
            "-fx-font-family: Tahoma;" +
            "-fx-font-size: 13px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 0;"
        );
        return button;
    }

    private void enableWindowDragging(Node node, Stage stage) {
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getTarget() instanceof Button) {
                return;
            }
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
        });

        node.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (event.getTarget() instanceof Button) {
                return;
            }
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
        });
    }

    private VBox createLoginContent() {
        VBox content = new VBox(17);
        content.setPadding(new Insets(24, 34, 20, 34));
        content.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, #ffffff, #eeeeeb);"
        );

        HBox branding = new HBox();
        branding.setAlignment(Pos.CENTER_LEFT);
        branding.getChildren().addAll(createTradeCastLogo(), spacer(), createAkdLogo());

        Region separator = new Region();
        separator.setPrefHeight(1);
        separator.setMaxHeight(1);
        separator.setStyle("-fx-background-color: #c6cbd1;");

        HBox mainArea = new HBox(30);
        mainArea.setAlignment(Pos.TOP_LEFT);

        VBox form = createLoginForm();
        VBox rightBrand = createRightBranding();
        HBox.setHgrow(form, Priority.ALWAYS);
        mainArea.getChildren().addAll(form, rightBrand);

        content.getChildren().addAll(branding, separator, mainArea);
        return content;
    }

    private Node spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private VBox createTradeCastLogo() {
        Polygon mark = new Polygon(
            1.0, 15.0, 12.0, 0.0, 25.0, 5.0, 17.0, 23.0, 7.0, 21.0
        );
        mark.setFill(Color.web("#ed5a36"));

        Label logoText = new Label("TRADE CAST");
        logoText.setFont(Font.font("Tahoma", FontWeight.BOLD, 12));
        logoText.setTextFill(Color.web("#273b59"));

        VBox logo = new VBox(1, mark, logoText);
        logo.setAlignment(Pos.CENTER_LEFT);
        return logo;
    }

    private HBox createAkdLogo() {
        Polygon orangeMark = new Polygon(
            0.0, 11.0, 12.0, 0.0, 51.0, 3.0, 45.0, 30.0, 13.0, 27.0
        );
        orangeMark.setFill(Color.web("#f36b21"));

        Label brand = new Label("akdtrade.com");
        brand.setFont(Font.font("Arial", FontWeight.BOLD, 25));
        brand.setTextFill(Color.web("#1c3152"));

        Label tagline = new Label("ONLINE INVESTMENTS");
        tagline.setFont(Font.font("Arial", FontWeight.BOLD, 8));
        tagline.setTextFill(Color.web("#657184"));

        VBox words = new VBox(-2, brand, tagline);
        words.setAlignment(Pos.CENTER_LEFT);

        HBox logo = new HBox(7, orangeMark, words);
        logo.setAlignment(Pos.CENTER);
        return logo;
    }

    private VBox createRightBranding() {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(250);
        box.setPadding(new Insets(22, 8, 8, 8));

        Polygon orangeMark = new Polygon(
            8.0, 15.0, 30.0, 0.0, 91.0, 6.0, 84.0, 55.0, 25.0, 51.0
        );
        orangeMark.setFill(Color.web("#f36b21"));

        Label brand = new Label("akdtrade.com");
        brand.setFont(Font.font("Arial", FontWeight.BOLD, 28));
        brand.setTextFill(Color.web("#1c3152"));

        Label tagline = new Label("ONLINE INVESTMENTS");
        tagline.setFont(Font.font("Arial", FontWeight.BOLD, 10));
        tagline.setTextFill(Color.web("#687589"));

        box.getChildren().addAll(orangeMark, brand, tagline);
        return box;
    }

    private VBox createLoginForm() {
        VBox form = new VBox(13);
        form.setPadding(new Insets(10, 0, 0, 0));
        form.setPrefWidth(435);

        HBox userRow = new HBox(9);
        userRow.setAlignment(Pos.CENTER_LEFT);

        Label userLabel = formLabel("User ID");
        userLabel.setPrefWidth(75);

        userIdField = new TextField();
        userIdField.setPromptText("Enter User ID");
        userIdField.setPrefWidth(185);
        userIdField.setPrefHeight(27);
        styleInput(userIdField);

        Button forgotButton = legacyButton("Forgot Password?");
        forgotButton.setPrefWidth(125);
        forgotButton.setOnAction(event -> showForgotPasswordDialog());

        userRow.getChildren().addAll(userLabel, userIdField, forgotButton);

        HBox passwordRow = new HBox(5);
        passwordRow.setAlignment(Pos.CENTER_LEFT);

        Label passwordLabel = formLabel("Password");
        passwordLabel.setPrefWidth(75);

        HBox passwordBoxes = new HBox(5);
        passwordBoxes.setAlignment(Pos.CENTER_LEFT);

        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            final int index = i;
            TextField characterField = new TextField();
            characterField.setAlignment(Pos.CENTER);
            characterField.setPrefSize(29, 27);
            characterField.setMinSize(29, 27);
            characterField.setMaxSize(29, 27);
            characterField.setFont(Font.font("Tahoma", FontWeight.BOLD, 13));
            characterField.setStyle(passwordBoxStyle(false));

            boolean isRequired = isRequiredPosition(i + 1);
            characterField.setDisable(!isRequired);
            if (!isRequired) {
                characterField.setStyle(passwordBoxStyle(true));
            }

            characterField.textProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue != null && newValue.length() > 1) {
                    characterField.setText(newValue.substring(newValue.length() - 1, newValue.length()));
                    return;
                }
                if (newValue != null && !newValue.isEmpty()) {
                    focusNextRequiredField(index);
                }
            });

            characterField.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.BACK_SPACE && characterField.getText().isEmpty()) {
                    focusPreviousRequiredField(index);
                }
            });

            passwordFields[i] = characterField;
            passwordBoxes.getChildren().add(characterField);
        }

        passwordRow.getChildren().addAll(passwordLabel, passwordBoxes);

        instructionLabel = new Label(buildPasswordInstruction(REQUIRED_PASSWORD_POSITIONS));
        instructionLabel.setFont(Font.font("Tahoma", 11));
        instructionLabel.setTextFill(Color.web("#555555"));
        instructionLabel.setWrapText(true);

        errorLabel = new Label(" ");
        errorLabel.setFont(Font.font("Tahoma", FontWeight.NORMAL, 12));
        errorLabel.setTextFill(Color.web("#d52b2b"));
        errorLabel.setMinHeight(18);

        HBox actionButtons = new HBox(10);
        actionButtons.setAlignment(Pos.CENTER);

        Button loginButton = legacyButton("Login");
        loginButton.setPrefWidth(86);
        loginButton.setOnAction(event -> handleLogin());

        Button cancelButton = legacyButton("Cancel");
        cancelButton.setPrefWidth(86);
        cancelButton.setOnAction(event -> primaryStage.close());

        actionButtons.getChildren().addAll(loginButton, cancelButton);

        userIdField.setOnAction(event -> passwordFields[REQUIRED_PASSWORD_POSITIONS[0] - 1].requestFocus());

        form.getChildren().addAll(
            userRow,
            passwordRow,
            instructionLabel,
            errorLabel,
            actionButtons
        );

        return form;
    }

    private Label formLabel(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("Tahoma", 11));
        label.setTextFill(Color.web("#303030"));
        return label;
    }

    private void styleInput(TextField field) {
        field.setFont(Font.font("Tahoma", 12));
        field.setStyle(
            "-fx-background-color: #ffffff;" +
            "-fx-border-color: #9daab7 #d9dfe5 #d9dfe5 #9daab7;" +
            "-fx-border-width: 1px;" +
            "-fx-background-radius: 0;" +
            "-fx-border-radius: 0;"
        );
    }

    private String passwordBoxStyle(boolean inactive) {
        String background = inactive ? "#e8e5d3" : "#ffffff";
        return "-fx-background-color: " + background + ";" +
               "-fx-border-color: #aeb9c5;" +
               "-fx-border-width: 1px;" +
               "-fx-background-radius: 0;" +
               "-fx-border-radius: 0;" +
               "-fx-padding: 2px;";
    }

    private Button legacyButton(String text) {
        Button button = new Button(text);
        button.setFont(Font.font("Tahoma", 11));
        button.setPrefHeight(27);
        button.setFocusTraversable(false);
        button.setStyle(
            "-fx-background-color: linear-gradient(to bottom, #ffffff, #e5e8eb);" +
            "-fx-border-color: #ffffff #8a969f #8a969f #ffffff;" +
            "-fx-border-width: 1px;" +
            "-fx-background-radius: 2px;" +
            "-fx-text-fill: #202020;" +
            "-fx-padding: 3px 10px;"
        );
        return button;
    }

    private boolean isRequiredPosition(int oneBasedPosition) {
        return Arrays.stream(REQUIRED_PASSWORD_POSITIONS)
                     .anyMatch(position -> position == oneBasedPosition);
    }

    private String buildPasswordInstruction(int[] positions) {
        StringBuilder result = new StringBuilder("Please enter ");
        for (int i = 0; i < positions.length; i++) {
            if (i > 0) {
                result.append(i == positions.length - 1 ? " & " : ", ");
            }
            result.append(ordinal(positions[i]));
        }
        result.append(" character of your password.");
        return result.toString();
    }

    private String ordinal(int number) {
        if (number % 100 >= 11 && number % 100 <= 13) {
            return number + "th";
        }
        return switch (number % 10) {
            case 1 -> number + "st";
            case 2 -> number + "nd";
            case 3 -> number + "rd";
            default -> number + "th";
        };
    }

    private void focusNextRequiredField(int currentIndex) {
        for (int i = currentIndex + 1; i < PASSWORD_LENGTH; i++) {
            if (isRequiredPosition(i + 1)) {
                final int nextIndex = i;
                Platform.runLater(() -> passwordFields[nextIndex].requestFocus());
                return;
            }
        }
    }

    private void focusPreviousRequiredField(int currentIndex) {
        for (int i = currentIndex - 1; i >= 0; i--) {
            if (isRequiredPosition(i + 1)) {
                passwordFields[i].requestFocus();
                passwordFields[i].positionCaret(passwordFields[i].getText().length());
                return;
            }
        }
    }

    private void handleLogin() {
        errorLabel.setText(" ");

        if (userIdField.getText().trim().isEmpty()) {
            showLoginError("Please enter your User ID.");
            userIdField.requestFocus();
            return;
        }

        for (int position : REQUIRED_PASSWORD_POSITIONS) {
            if (passwordFields[position - 1].getText().trim().isEmpty()) {
                showLoginError("Please enter all requested password characters.");
                passwordFields[position - 1].requestFocus();
                return;
            }
        }

        // No authentication backend is permitted by the assignment.
        // Therefore, a completed frontend form displays the reference error state.
        showLoginError("Invalid login credentials!");
        clearPasswordFields();
    }

    private void showLoginError(String message) {
        errorLabel.setText(message);
    }

    private void clearPasswordFields() {
        for (TextField field : passwordFields) {
            if (field != null && !field.isDisabled()) {
                field.clear();
            }
        }
    }

    private void showForgotPasswordDialog() {
        Stage dialog = new Stage(StageStyle.UNDECORATED);
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Forgot Password");
        dialog.setResizable(false);

        BorderPane root = new BorderPane();
        root.setStyle(
            "-fx-background-color: #f4f4f1;" +
            "-fx-border-color: #7f8b9b;" +
            "-fx-border-width: 1px;"
        );

        root.setTop(createDialogTitleBar(dialog));

        VBox content = new VBox(16);
        content.setPadding(new Insets(22, 26, 18, 26));
        content.setStyle("-fx-background-color: linear-gradient(to bottom right, #ffffff, #eeeeeb);");

        HBox branding = new HBox();
        branding.setAlignment(Pos.CENTER_LEFT);
        branding.getChildren().addAll(createTradeCastLogo(), spacer(), createAkdLogo());

        TextField dialogUserId = new TextField();
        dialogUserId.setPrefWidth(220);
        dialogUserId.setPrefHeight(27);
        dialogUserId.setPromptText("User ID");
        styleInput(dialogUserId);

        TextField emailField = new TextField();
        emailField.setPrefWidth(220);
        emailField.setPrefHeight(27);
        emailField.setPromptText("Email Address");
        styleInput(emailField);

        HBox userRow = new HBox(12, dialogLabel("User ID"), dialogUserId);
        userRow.setAlignment(Pos.CENTER_LEFT);

        HBox emailRow = new HBox(12, dialogLabel("Email Address"), emailField);
        emailRow.setAlignment(Pos.CENTER_LEFT);

        Label dialogError = new Label(" ");
        dialogError.setTextFill(Color.web("#d52b2b"));
        dialogError.setFont(Font.font("Tahoma", 11));

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER);

        Button okButton = legacyButton("OK");
        okButton.setPrefWidth(72);
        okButton.setOnAction(event -> {
            String userId = dialogUserId.getText().trim();
            String email = emailField.getText().trim();

            if (userId.isEmpty() || email.isEmpty()) {
                dialogError.setText("Please enter your User ID and Email Address.");
                return;
            }

            if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                dialogError.setText("Please enter a valid email address.");
                emailField.requestFocus();
                return;
            }

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(dialog);
            alert.setTitle("Forgot Password");
            alert.setHeaderText("Request captured");
            alert.setContentText(
                "This is a frontend-only demonstration. Password recovery email is not sent."
            );
            alert.showAndWait();
            dialog.close();
        });

        Button cancelButton = legacyButton("Cancel");
        cancelButton.setPrefWidth(72);
        cancelButton.setOnAction(event -> dialog.close());

        buttons.getChildren().addAll(okButton, cancelButton);
        content.getChildren().addAll(branding, userRow, emailRow, dialogError, buttons);
        root.setCenter(content);

        Scene scene = new Scene(root, 510, 275);
        dialog.setScene(scene);
        dialog.show();
        dialog.centerOnScreen();
        Platform.runLater(dialogUserId::requestFocus);
    }

    private HBox createDialogTitleBar(Stage dialog) {
        HBox titleBar = new HBox(8);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(4, 7, 4, 9));
        titleBar.setPrefHeight(29);
        titleBar.setStyle("-fx-background-color: linear-gradient(to bottom, #2679d8, #0756b5);");

        Ellipse icon = new Ellipse(6, 6);
        icon.setFill(Color.web("#ff8a22"));

        Label title = new Label("Forgot Password");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Tahoma", FontWeight.BOLD, 12));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button close = titleBarButton("×");
        close.setOnAction(event -> dialog.close());

        titleBar.getChildren().addAll(icon, title, spacer, close);
        enableWindowDragging(titleBar, dialog);
        return titleBar;
    }

    private Label dialogLabel(String text) {
        Label label = formLabel(text);
        label.setPrefWidth(95);
        return label;
    }

    private HBox createFooter() {
        HBox footer = new HBox();
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(5, 9, 5, 9));
        footer.setStyle("-fx-background-color: #e4e6e7; -fx-border-color: #c3c7ca; -fx-border-width: 1px 0 0 0;");

        Label left = new Label("BROKERAGE PRELUDE");
        left.setFont(Font.font("Tahoma", FontWeight.BOLD, 9));
        left.setTextFill(Color.web("#425c78"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label copyright = new Label("Copyright 2010 - 2011 CATALYST IT Solutions (Pvt.) Limited. All Rights Reserved.");
        copyright.setFont(Font.font("Tahoma", 9));
        copyright.setTextFill(Color.web("#555555"));

        footer.getChildren().addAll(left, spacer, copyright);
        return footer;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
