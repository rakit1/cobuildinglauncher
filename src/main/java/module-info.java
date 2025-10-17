module cobuilding {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;

    opens com.cobuilding to javafx.fxml;
    exports com.cobuilding;
}