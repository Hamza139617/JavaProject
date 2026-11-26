package osl.ui;

import java.sql.SQLException;
import java.util.EnumMap;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import osl.bl.AccountService;
import osl.bl.AccountService.AccountException;
import osl.bl.AccountService.Field;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.CustomerService;
import osl.bl.InventoryService;
import osl.bl.ProductService;
import osl.bl.ProfitTrendsService;
import osl.bl.ReplenishmentService;
import osl.bl.ReportReview;
import osl.bl.SalesPerformanceService;
import osl.bl.SalesService;
import osl.bl.StockService;
import osl.bl.WholesalerAdministration;
import osl.bl.WholesalerDirectory;
import osl.db.MySqlAccounts;
import osl.db.MySqlCustomers;
import osl.db.MySqlInventory;
import osl.db.MySqlProducts;
import osl.db.MySqlProfitTrends;
import osl.db.MySqlReplenishment;
import osl.db.MySqlReportReview;
import osl.db.MySqlSales;
import osl.db.MySqlSalesPerformance;
import osl.db.MySqlStock;
import osl.db.MySqlWholesalerAdministration;
import osl.db.MySqlWholesalerDirectory;

public class OslApplication extends Application {
    private static final String INPUT_STYLE = "-fx-background-radius: 8; -fx-border-radius: 8;"
            + "-fx-border-color: #b7c9bf; -fx-background-color: white;";
    private static final String FIELD_ERROR = INPUT_STYLE + "-fx-border-color: #a84835;";
    private final AccountService accounts = new AccountService(new MySqlAccounts());
    private final StockService stocks = new StockService(new MySqlStock());
    private final SalesService sales = new SalesService(new MySqlSales());
    private final SalesPerformanceService performance = new SalesPerformanceService(new MySqlSalesPerformance());
    private final WholesalerDirectory directory = new WholesalerDirectory(new MySqlWholesalerDirectory());
    private final ProductService products = new ProductService(new MySqlProducts());
    private final ProfitTrendsService profitTrends = new ProfitTrendsService(new MySqlProfitTrends());
    private final ReplenishmentService replenishment = new ReplenishmentService(new MySqlReplenishment(), stocks);
    private final CustomerService customers = new CustomerService(new MySqlCustomers());
    private final InventoryService inventory = new InventoryService(new MySqlInventory());
    private final WholesalerAdministration administration = new WholesalerAdministration(new MySqlWholesalerAdministration());
    private final Timeline accountWatch = new Timeline(new KeyFrame(Duration.seconds(5), event -> checkCurrentAccount()));
    private final ReportReview reportedContent = new ReportReview(new MySqlReportReview());
    private Stage stage;
    private StackPane root;
    private GridPane accountCard;
    private ScrollPane content;
    private Session session;
    private boolean busy;
    private boolean checkingAccount;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        Label brand = label("OMNI SALES LOGISTICS ENGINE", "#b9f5d5", 12, true);
        Label headline = label("A clear view of\nstock and sales.", "#f4fff8", 38, true);
        Label description = label("Manage inventory, record sales, and discover wholesalers.", "#c1dcd1", 15, false);
        VBox identity = new VBox(22, brand, headline, description);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setPadding(new Insets(40));
        identity.setStyle("-fx-background-color: linear-gradient(to bottom right, #153e35, #0b2628);");

        content = new ScrollPane();
        content.setFitToWidth(true);
        content.setFitToHeight(true);
        content.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        content.setStyle("-fx-background: #fcfdfb; -fx-background-color: transparent;");
        content.setMinWidth(0);

        GridPane card = new GridPane();
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(46);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(54);
        card.getColumnConstraints().addAll(left, right);
        var row = new RowConstraints();
        row.setPercentHeight(100);
        card.getRowConstraints().add(row);
        card.addRow(0, identity, content);

        accountCard = card;
        root = new StackPane(accountCard);
        root.setStyle("-fx-background-color: #edf4ef; -fx-font-family: 'Segoe UI'; -fx-font-size: 14px;");
        stage.setTitle("Omni Sales Logistics Engine");
        stage.setScene(new Scene(root));
        stage.setMinWidth(780);
        stage.setMinHeight(560);
        stage.setOnCloseRequest(event -> {
            if (busy) {
                event.consume();
            }
        });
        showLogin("", "", false);
        stage.setMaximized(true);
        stage.show();
    }

    private void showLogin(String rememberedUsername, String message, boolean error) {
        accountWatch.stop();
        session = null;
        Form form = new Form("Sign in", "Access your business workspace.");
        TextField username = form.text(Field.USERNAME, "Username", "Enter your username", rememberedUsername);
        PasswordInput password = form.password(Field.PASSWORD, "Password", "Enter your password");

        Button submit = primary("Sign in");
        submit.setPrefSize(68, 68);
        submit.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        submit.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        VBox.setVgrow(submit, Priority.NEVER);
        submit.setOnAction(event -> {
            form.reset();
            String name = username.getText();
            String secret = password.value();
            run(form, () -> accounts.login(name, secret), result -> {
                password.clear();
                switch (result.status()) {
                    case SUCCESS -> {
                        session = result.session();
                        if (session.role() == Role.WHOLESALER) {
                            accountWatch.setCycleCount(Timeline.INDEFINITE);
                            accountWatch.play();
                        }
                        showDashboard(null);
                    }
                    case INVALID -> form.message("The username or password is incorrect.", true);
                    case LOCKED -> form.message("Sign-in is temporarily locked. Try again after five minutes.", true);
                    case INACTIVE -> form.message("This account cannot sign in. Contact your administrator.", true);
                }
                if (result.status() == AccountService.LoginStatus.INVALID) {
                    password.masked.requestFocus();
                }
            }, failure -> {
                password.clear();
                form.message(DatabaseMessages.describe("Could not sign in.", failure), true);
            });
        });
        Hyperlink signUp = link("Create an account", this::showRegistration);
        Hyperlink cancel = link("Cancel", stage::close);
        form.body.getChildren().addAll(submit, DirectoryControls.actionRow(16, signUp, cancel));
        form.message(message, error);
        display(form.body);
    }

    private void showRegistration() {
        Form form = new Form("Create an account", "Set up a retailer or wholesaler workspace.");
        ComboBox<Role> role = new ComboBox<>();
        role.getItems().addAll(Role.RETAILER, Role.WHOLESALER);
        role.setPromptText("Choose an account type");
        form.add(Field.ROLE, "Account type", role, role);
        TextField name = form.text(Field.FULL_NAME, "Full name", "Your name", "");
        TextField business = form.text(Field.BUSINESS, "Business name", "Your business name", "");
        TextField phone = form.text(Field.PHONE, "Contact number", "03001234567 or +92 300 1234567", "");
        TextField username = form.text(Field.USERNAME, "Username", "Choose a username", "");
        PasswordInput password = form.password(Field.PASSWORD, "Password", "Choose a password");
        form.body.getChildren().add(label(AccountService.PASSWORD_RULE, "#53675e", 12, false));
        Button submit = primary("Create account");
        submit.setOnAction(event -> {
            form.reset();
            Role chosen = role.getValue();
            String fullName = name.getText();
            String businessName = business.getText();
            String contact = phone.getText();
            String login = username.getText();
            String secret = password.value();
            run(form, () -> accounts.register(chosen, fullName, businessName, contact, login, secret),
                    created -> {
                        password.clear();
                        showLogin(created, chosen == Role.WHOLESALER
                                ? "Account created. Sign in and publish your public profile to appear in retailer searches."
                                : "Account created. Sign in to open your workspace.", false);
                    },
                    failure -> {
                        password.clear();
                        showLogin("", DatabaseMessages.describe("Could not save the account.", failure), true);
                    });
        });
        form.body.getChildren().addAll(submit, link("Cancel and return to sign in", () -> showLogin("", "", false)));
        display(form.body);
    }

    private void showDashboard(String message) {
        String heading = switch (session.role()) {
            case RETAILER -> "Retailer workspace";
            case WHOLESALER -> "Wholesaler workspace";
            case ADMINISTRATOR -> "Administration";
        };
        String subtitle = session.businessName() == null ? session.fullName() : session.businessName();
        Form form = new Form(heading, subtitle);
        form.body.getChildren().add(label("Signed in as " + session.username(), "#53675e", 14, false));
        if (session.role() != Role.ADMINISTRATOR) {
            Button catalog = primary("Manage products");
            catalog.setOnAction(event -> showProducts("Back to workspace", () -> showDashboard(null)));
            form.body.getChildren().add(catalog);
            Button stock = primary("Manage stock");
            stock.setOnAction(event -> showStock());
            Button track = primary("Track inventory");
            track.setOnAction(event -> showInventory());
            form.body.getChildren().addAll(stock, track);
            Button sale = primary("Record sale");
            sale.setOnAction(event -> showSale());
            form.body.getChildren().add(sale);
            Button analysis = primary("Sales performance");
            analysis.setOnAction(event -> showSalesPerformance());
            form.body.getChildren().add(analysis);
            Button trends = primary("Profit trends");
            trends.setOnAction(event -> showProfitTrends());
            form.body.getChildren().add(trends);
            Button replenish = primary("Replenish stock");
            replenish.setOnAction(event -> showReplenishment());
            Label lowNotices = label("", "#286049", 13, false);
            lowNotices.setManaged(false);
            lowNotices.setVisible(false);
            form.body.getChildren().addAll(replenish, lowNotices);
            watchReplenishment(form.body, lowNotices, session);
        }
        if (session.role() == Role.RETAILER) {
            Button relationships = primary("Customer relationships");
            relationships.setOnAction(event -> showCustomerRelationships());
            form.body.getChildren().add(relationships);
            Button search = primary("Search wholesalers");
            search.setOnAction(event -> showWholesalers());
            Button reportUpdates = primary("Report updates");
            reportUpdates.setOnAction(event -> showReportReview());
            form.body.getChildren().addAll(search, reportUpdates);
            watchReportUpdates(form.body, reportUpdates, session);
        } else if (session.role() == Role.WHOLESALER) {
            Button publicProfile = primary("Public wholesaler profile");
            publicProfile.setOnAction(event -> showPublicProfile());
            form.body.getChildren().addAll(publicProfile,
                    label("Publish your public profile to appear in retailer searches.", "#53675e", 13, false));
        }
        if (session.role() == Role.ADMINISTRATOR) {
            Button administer = primary("Manage wholesaler accounts");
            administer.setOnAction(event -> showWholesalerAdministration());
            Button review = primary("Review reported content");
            review.setOnAction(event -> showReportReview());
            form.body.getChildren().addAll(administer, review);
        }
        Button edit = primary("Edit profile");
        edit.setOnAction(event -> showProfile());
        form.body.getChildren().addAll(edit, link("Sign out", () -> showLogin("", "", false)));
        form.message(message, false);
        showWorkspace(form.body);
    }

    private void checkCurrentAccount() {
        Session current = session;
        if (current == null || checkingAccount || !canReturnToLogin()) return;
        checkingAccount = true;
        DirectoryControls.run(() -> accounts.isActive(current), active -> {
            checkingAccount = false;
            if (!active && session == current && canReturnToLogin()) {
                showLogin(current.username(), "This account cannot sign in. Contact your administrator.", true);
            }
        }, failure -> checkingAccount = false);
    }

    private boolean canReturnToLogin() {
        return !busy && Window.getWindows().stream().noneMatch(window -> window != stage && window.isShowing());
    }

    private void showReportReview() {
        showWorkspace(new ReportReviewView(reportedContent, session,
                () -> showDashboard(null), working -> busy = working));
    }

    private void watchReportUpdates(VBox body, Button updates, Session current) {
        Runnable query = () -> DirectoryControls.run(() -> reportedContent.notices(current), notices -> {
            if (session != current || body.getScene() == null) return;
            long unread = notices.stream().filter(ReportReview.Notice::unread).count();
            updates.setText(unread == 0 ? "Report updates" : "Report updates (" + unread + " new)");
        }, failure -> {});
        Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(30), event -> query.run()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        body.sceneProperty().addListener((observable, old, scene) -> {
            if (scene == null) refresh.stop();
            else {
                query.run();
                refresh.play();
            }
        });
    }

    private void showWholesalerAdministration() {
        showWorkspace(new WholesalerAdministrationView(administration, session, stage,
                () -> showDashboard(null), working -> busy = working));
    }

    private void showStock() {
        showWorkspace(new StockView(stocks, session, stage, () -> showDashboard(null),
                () -> showProducts("Back to stock", this::showStock), working -> busy = working));
    }

    private void showInventory() {
        showWorkspace(new InventoryView(inventory, session, stage, () -> showDashboard(null),
                working -> busy = working));
    }

    private void showSale() {
        showWorkspace(new SaleView(stocks, sales, customers, session, stage, () -> showDashboard(null),
                working -> busy = working));
    }

    private void showCustomerRelationships() {
        showWorkspace(new CustomerRelationshipsView(customers, session, stage, () -> showDashboard(null),
                working -> busy = working));
    }

    private void showSalesPerformance() {
        showWorkspace(new SalesPerformanceView(performance, session, () -> showDashboard(null)));
    }

    private void showProfitTrends() {
        showWorkspace(new ProfitTrendsView(profitTrends, session, () -> showDashboard(null)));
    }

    private void showReplenishment() {
        showWorkspace(new ReplenishmentView(replenishment, session, stage, () -> showDashboard(null),
                () -> showWholesalers("Back to replenishment", this::showReplenishment), working -> busy = working));
    }

    private void watchReplenishment(VBox body, Label notice, Session current) {
        Runnable query = () -> DirectoryControls.run(() -> replenishment.load(current), board -> {
            if (session != current || body.getScene() == null) return;
            long unread = board.unreadNotices();
            notice.setText(unread == 0 ? ""
                    : unread + (unread == 1 ? " unread low-stock notice" : " unread low-stock notices"));
            notice.setVisible(unread != 0);
            notice.setManaged(unread != 0);
        }, failure -> {});
        Timeline reminders = new Timeline(new KeyFrame(Duration.seconds(45), event -> query.run()));
        reminders.setCycleCount(Timeline.INDEFINITE);
        body.sceneProperty().addListener((observable, old, scene) -> {
            if (scene == null) {
                reminders.stop();
            } else {
                query.run();
                reminders.play();
            }
        });
    }

    private void showWholesalers() {
        showWholesalers("Back to workspace", () -> showDashboard(null));
    }

    private void showWholesalers(String backLabel, Runnable back) {
        showWorkspace(new WholesalerSearchView(directory, session, stage, backLabel, back,
                working -> busy = working));
    }

    private void showPublicProfile() {
        showWorkspace(new PublicWholesalerProfileView(directory, session, () -> showDashboard(null),
                () -> showProducts("Back to public profile", this::showPublicProfile), working -> busy = working));
    }

    private void showProducts(String backLabel, Runnable back) {
        showWorkspace(new ProductView(products, session, stage, backLabel, back, working -> busy = working));
    }

    private void showProfile() {
        Session current = session;
        Form form = new Form("Edit profile", "Update your account details or change your password.");
        TextField name = form.text(Field.FULL_NAME, "Full name", "Your name", current.fullName());
        TextField business = current.businessName() == null ? null
                : form.text(Field.BUSINESS, "Business name", "Your business name", current.businessName());
        TextField phone = form.text(Field.PHONE, "Contact number", "03001234567", current.phone());
        PasswordInput previous = form.password(Field.CURRENT_PASSWORD, "Current password", "Required for a password change");
        PasswordInput next = form.password(Field.PASSWORD, "New password", "Leave blank to keep your password");
        form.body.getChildren().add(label(AccountService.PASSWORD_RULE, "#53675e", 12, false));
        Button submit = primary("Save changes");
        submit.setOnAction(event -> {
            form.reset();
            String fullName = name.getText();
            String businessName = business == null ? "" : business.getText();
            String contact = phone.getText();
            String currentPassword = previous.value();
            String newPassword = next.value();
            run(form, () -> accounts.updateProfile(current, fullName, businessName, contact, currentPassword, newPassword),
                    saved -> {
                        previous.clear();
                        next.clear();
                        session = saved;
                        showDashboard("Profile updated.");
                    },
                    failure -> form.message(DatabaseMessages.describe("Could not save the profile.", failure), true));
        });
        form.body.getChildren().addAll(submit, link("Cancel changes", () -> showDashboard(null)));
        showWorkspace(form.body);
    }

    private <T> void run(Form form, Callable<T> action, Consumer<T> success, Consumer<SQLException> databaseFailure) {
        busy = true;
        form.body.setDisable(true);
        form.message("Please wait…", false);
        Task<T> work = new Task<>() {
            @Override
            protected T call() throws Exception {
                return action.call();
            }
        };
        work.setOnSucceeded(event -> {
            busy = false;
            form.body.setDisable(false);
            success.accept(work.getValue());
        });
        work.setOnFailed(event -> {
            busy = false;
            form.body.setDisable(false);
            Throwable failure = work.getException();
            if (failure instanceof AccountException validation) {
                form.highlight(validation);
            } else if (failure instanceof SQLException databaseError) {
                databaseFailure.accept(databaseError);
            } else {
                if (failure != null) {
                    System.err.println("OSL account operation failed: " + failure.getClass().getSimpleName());
                }
                form.message("The account request could not be completed. Try again.", true);
            }
        });
        Thread.ofVirtual().start(work);
    }

    private void display(VBox view) {
        if (!root.getChildren().contains(accountCard)) {
            root.getChildren().setAll(accountCard);
        }
        content.setContent(view);
        content.setVvalue(0);
    }

    private void showWorkspace(Node view) {
        var pane = DirectoryControls.scroll(view);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        root.getChildren().setAll(pane);
    }

    private static Label label(String text, String color, int size, boolean bold) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: " + color + "; -fx-font-size: " + size + "px;"
                + (bold ? " -fx-font-weight: bold;" : ""));
        return label;
    }

    private static Button primary(String title) {
        Button button = new Button(title);
        button.setDefaultButton(true);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinHeight(43);
        button.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(button, Priority.ALWAYS);
        button.setStyle("-fx-background-color: #1d604a; -fx-background-radius: 9;"
                + "-fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        return button;
    }

    private static Hyperlink link(String text, Runnable action) {
        Hyperlink link = new Hyperlink(text);
        link.setStyle("-fx-text-fill: #1d604a;");
        link.setOnAction(event -> action.run());
        return link;
    }

    private static final class Form {
        final VBox body = new VBox(11);
        final Label status = label("", "#53675e", 13, false);
        final EnumMap<Field, Control[]> fields = new EnumMap<>(Field.class);

        Form(String title, String subtitle) {
            body.setAlignment(Pos.CENTER_LEFT);
            body.setMinHeight(Region.USE_PREF_SIZE);
            body.setFillWidth(true);
            body.setPadding(new Insets(28, 32, 26, 32));
            status.setMinHeight(20);
            body.getChildren().addAll(label(title, "#19332a", 30, true),
                    label(subtitle, "#53675e", 15, false), status);
        }

        TextField text(Field key, String caption, String prompt, String value) {
            TextField field = new TextField(value);
            field.setPromptText(prompt);
            add(key, caption, field, field);
            return field;
        }

        PasswordInput password(Field key, String caption, String prompt) {
            PasswordInput entry = new PasswordInput(prompt);
            entry.masked.setAccessibleText(caption);
            entry.visible.setAccessibleText(caption);
            add(key, caption, entry.row, entry.masked, entry.visible);
            return entry;
        }

        void add(Field key, String caption, Node input, Control... controls) {
            Label text = label(caption, "#284439", 13, true);
            text.setLabelFor(controls[0]);
            for (Control control : controls) {
                control.setStyle(INPUT_STYLE);
                control.setMinHeight(34);
                control.setMaxWidth(Double.MAX_VALUE);
            }
            fields.put(key, controls);
            body.getChildren().add(new VBox(4, text, input));
        }

        void reset() {
            fields.values().forEach(pair -> {
                for (Control control : pair) {
                    control.setStyle(INPUT_STYLE);
                }
            });
        }

        void highlight(AccountException failure) {
            message(failure.getMessage(), true);
            Control[] controls = failure.field() == null ? null : fields.get(failure.field());
            if (controls != null) {
                for (Control control : controls) {
                    control.setStyle(FIELD_ERROR);
                    if (control.isVisible()) {
                        control.requestFocus();
                    }
                }
            }
        }

        void message(String message, boolean error) {
            status.setText(message == null ? "" : message);
            status.setStyle("-fx-font-size: 13px; -fx-text-fill: " + (error ? "#9d3b2d;" : "#286049;"));
        }
    }

    private static final class PasswordInput {
        final PasswordField masked = new PasswordField();
        final TextField visible = new TextField();
        final HBox row;

        PasswordInput(String prompt) {
            masked.setPromptText(prompt);
            visible.setPromptText(prompt);
            visible.textProperty().bindBidirectional(masked.textProperty());
            visible.setVisible(false);
            visible.managedProperty().bind(visible.visibleProperty());
            masked.managedProperty().bind(masked.visibleProperty());
            Button toggle = new Button("Show");
            toggle.setAccessibleText("Show password");
            toggle.setMinHeight(34);
            toggle.setOnAction(event -> {
                boolean reveal = !visible.isVisible();
                visible.setVisible(reveal);
                masked.setVisible(!reveal);
                toggle.setText(reveal ? "Hide" : "Show");
                toggle.setAccessibleText(reveal ? "Hide password" : "Show password");
                TextField active = reveal ? visible : masked;
                active.requestFocus();
                active.end();
            });
            StackPane input = new StackPane(masked, visible);
            HBox.setHgrow(input, Priority.ALWAYS);
            row = new HBox(7, input, toggle);
        }

        String value() {
            return masked.getText();
        }

        void clear() {
            masked.clear();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
