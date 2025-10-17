package com.cobuilding;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

public class ConfirmDialogController {

    @FXML private Label titleLabel;
    @FXML private Label contentLabel;
    @FXML private Button okButton;
    @FXML private Button cancelButton;
    @FXML private HBox titleBar;

    private Stage stage;
    private boolean confirmed = false;
    private double xOffset = 0;
    private double yOffset = 0;

    @FXML
    public void initialize() {
        titleBar.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        titleBar.setOnMouseDragged(event -> {
            if (stage != null) {
                stage.setX(event.getScreenX() - xOffset);
                stage.setY(event.getScreenY() - yOffset);
            }
        });

        okButton.setOnAction(e -> {
            confirmed = true;
            close();
        });
        cancelButton.setOnAction(e -> {
            confirmed = false;
            close();
        });
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public void setData(String title, String content) {
        titleLabel.setText(title);
        contentLabel.setText(content);
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    private void close() {
        if (stage != null) {
            stage.close();
        }
    }
}