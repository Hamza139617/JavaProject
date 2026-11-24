module com.example.akdtradecast {
    requires javafx.controls;
    requires javafx.fxml;

    opens com.example.akdtradecast to javafx.fxml;
    exports com.example.akdtradecast;
}
