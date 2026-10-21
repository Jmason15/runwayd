package com.finance.runwayd.desktop.dialogs;

import com.finance.runwayd.desktop.ui.Theme;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;

/**
 * The shared base for every "fill in a form, click Save" pop-up.
 *
 * <p>A subclass does two things:
 * <ol>
 *   <li>Adds its fields with {@link #addRow(String, Node)}.</li>
 *   <li>Implements {@link #buildResult()}, which reads the fields and returns the finished object,
 *       or throws {@link IllegalArgumentException} with a message if something's wrong.</li>
 * </ol>
 *
 * <p>When Save is clicked, this class calls {@code buildResult()}. If it throws, the
 * message is shown in red and the dialog stays open, so you never lose what you typed.
 * The model records (Account, ScheduledItem) already reject bad values when they're
 * created, so most checks happen there automatically.
 *
 * @param <T> what the dialog produces, e.g. Account
 */
public abstract class FormDialog<T> extends Dialog<T> {

    private final GridPane grid = new GridPane();
    private final Label errorLabel = new Label();
    private int nextRow = 0;

    protected FormDialog(String title) {
        setTitle(title);
        setHeaderText(null);
        Theme.apply(getDialogPane()); // light or dark, like the main window

        grid.setHgap(12);
        grid.setVgap(10);

        errorLabel.getStyleClass().add("form-error");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(380);
        showError(null);

        VBox content = new VBox(12, grid, errorLabel);
        content.setPadding(new Insets(16));
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button saveButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        saveButton.setText("Save");
        // Check the form BEFORE the dialog closes; if it's invalid, cancel the click.
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                buildResult();
            } catch (IllegalArgumentException problem) {
                showError(problem.getMessage());
                event.consume();
            }
        });

        setResultConverter(button -> button == ButtonType.OK ? buildResult() : null);
    }

    /** Reads the form and returns the finished object. Throw IllegalArgumentException to reject it. */
    protected abstract T buildResult();

    /** Adds "Label: [control]" as the next row of the form. */
    protected FormRow addRow(String labelText, Node control) {
        Label label = new Label(labelText);
        grid.addRow(nextRow++, label, control);
        return new FormRow(label, control, this::fitWindowToContent);
    }

    /** Throws a friendly error if a required choice was left empty. */
    protected static <V> V required(V value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /**
     * The date in a DatePicker, including one that was typed but not yet confirmed with Enter.
     * (Out of the box, JavaFX ignores typed dates until you press Enter, which is easy to miss.)
     */
    protected static LocalDate dateFrom(DatePicker picker) {
        String typed = picker.getEditor().getText();
        if (typed == null || typed.isBlank()) {
            return null;
        }
        try {
            return picker.getConverter().fromString(typed);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + typed + "' isn't a date. Use the calendar button to pick one.");
        }
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(message != null);
        errorLabel.setManaged(message != null);
        fitWindowToContent(); // the dialog may need to grow to fit the message
    }

    /**
     * Resizes the pop-up to fit what's in it now. Needed whenever rows are shown or hidden:
     * a window keeps its size on its own, so a taller form would push the Save and Cancel
     * buttons off the bottom. Does nothing before the dialog is on screen, since it sizes
     * itself when it first opens.
     */
    private void fitWindowToContent() {
        if (getDialogPane().getScene() != null && getDialogPane().getScene().getWindow() != null) {
            getDialogPane().getScene().getWindow().sizeToScene();
        }
    }

    /**
     * One label + control pair, so a row can be shown or hidden as a unit.
     *
     * @param onShowOrHide called after the row is shown or hidden, so the dialog can resize to fit
     */
    protected record FormRow(Label label, Node control, Runnable onShowOrHide) {

        public void setShown(boolean shown) {
            if (control.isManaged() == shown) {
                return; // already shown (or hidden); nothing changes
            }
            for (Node node : new Node[] {label, control}) {
                node.setVisible(shown);
                node.setManaged(shown); // managed=false also removes the empty gap
            }
            onShowOrHide.run();
        }

        public void setLabel(String text) {
            label.setText(text);
        }
    }
}
