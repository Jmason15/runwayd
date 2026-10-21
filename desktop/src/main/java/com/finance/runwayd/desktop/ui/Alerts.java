package com.finance.runwayd.desktop.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;

import java.util.Optional;

/** Short-hands for the standard pop-up boxes. */
public final class Alerts {

    private Alerts() {
    }

    public static void error(String message) {
        show(Alert.AlertType.ERROR, "Something went wrong", message);
    }

    public static void info(String message) {
        show(Alert.AlertType.INFORMATION, "runwayd", message);
    }

    /** Asks a yes/no question. Returns true only if the user clicks OK. */
    public static boolean confirm(String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question, ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText(null);
        Theme.apply(alert.getDialogPane());
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    /**
     * Asks for a line of text, e.g. a name. Returns it trimmed, or empty if the user
     * cancels or leaves it blank.
     */
    public static Optional<String> askText(String title, String question, String startingText) {
        TextInputDialog dialog = new TextInputDialog(startingText);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(question);
        Theme.apply(dialog.getDialogPane());
        return dialog.showAndWait().map(String::trim).filter(text -> !text.isEmpty());
    }

    private static void show(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        Theme.apply(alert.getDialogPane());
        alert.showAndWait();
    }
}
