package com.finance.runwayd.desktop.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.NumberBinding;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Builds table columns in one line each.
 *
 * <p>JavaFX normally needs a few lines of setup per column. These helpers take
 * a column title and a function that pulls the value out of a row, e.g.
 * {@code Tables.money("Balance", Account::balance)}.
 */
public final class Tables {

    // Fixed row and header heights, so sizeToRows can make a table exactly as tall as its rows.
    private static final double ROW_HEIGHT = 30;
    private static final double HEADER_HEIGHT = 32;

    private Tables() {
    }

    /** A table that stretches its columns to fill the width and shows {@code emptyText} when there are no rows. */
    public static <T> TableView<T> create(String emptyText) {
        TableView<T> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(emptyText));
        return table;
    }

    /**
     * A new column you can't type into. Columns are editable by default in JavaFX, and in a
     * table with a click-to-edit column (like Amount) that makes ANY cell go into "editing"
     * when clicked, which swallows double-clicks meant to open the edit dialog. So every
     * column starts read-only, and only {@link #editableMoney} turns editing back on.
     */
    private static <T, V> TableColumn<T, V> readOnlyColumn(String title) {
        TableColumn<T, V> column = new TableColumn<>(title);
        column.setEditable(false);
        return column;
    }

    /**
     * Makes a table exactly as tall as its rows, so short lists don't leave empty
     * space and nothing is cut off. Past {@code maxRows} the table scrolls instead.
     */
    public static void sizeToRows(TableView<?> table, int maxRows) {
        table.setFixedCellSize(ROW_HEIGHT);
        // Show at least one row, so the "nothing here" message has room.
        NumberBinding visibleRows = Bindings.max(1, Bindings.min(maxRows, Bindings.size(table.getItems())));
        NumberBinding height = visibleRows.multiply(ROW_HEIGHT).add(HEADER_HEIGHT);
        table.minHeightProperty().bind(height);
        table.prefHeightProperty().bind(height);
        table.maxHeightProperty().bind(height);
    }

    /** A plain text column. */
    public static <T> TableColumn<T, String> text(String title, Function<T, String> value) {
        TableColumn<T, String> column = readOnlyColumn(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        return column;
    }

    /** A money column: right-aligned, "$1,234.56", red when negative. Sorts by the number, not the text. */
    public static <T> TableColumn<T, BigDecimal> money(String title, Function<T, BigDecimal> value) {
        TableColumn<T, BigDecimal> column = readOnlyColumn(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal amount, boolean empty) {
                super.updateItem(amount, empty);
                setText(empty ? null : Format.money(amount));
                getStyleClass().removeAll("negative");
                if (!empty && amount != null && amount.signum() < 0) {
                    getStyleClass().add("negative");
                }
            }
        });
        column.getStyleClass().add("money-column");
        return column;
    }

    /**
     * A money column you can type into. One click on an amount opens it for editing;
     * Enter saves, Esc cancels. You can type "2140", "2,140.00" or "$2,140".
     *
     * <p>The table must have {@code setEditable(true)} for this to work.
     *
     * @param onSave called with the row and the new amount after Enter is pressed
     */
    public static <T> TableColumn<T, BigDecimal> editableMoney(String title, Function<T, BigDecimal> value,
                                                               BiConsumer<T, BigDecimal> onSave) {
        TableColumn<T, BigDecimal> column = money(title, value);
        column.setEditable(true);

        // How the amount looks while you're typing, and how your typing is read back.
        StringConverter<BigDecimal> converter = new StringConverter<>() {
            @Override
            public String toString(BigDecimal amount) {
                return Format.moneyForEditing(amount);
            }

            @Override
            public BigDecimal fromString(String text) {
                return Format.parseMoney(text, title);
            }
        };

        column.setCellFactory(col -> new TextFieldTableCell<>(converter) {
            {
                // Normally a cell needs a click to select it and a second click to edit.
                // One click is friendlier for "type in my balance".
                setOnMouseClicked(event -> {
                    if (!isEmpty() && !isEditing()) {
                        getTableView().edit(getIndex(), getTableColumn());
                    }
                });
            }

            @Override
            public void updateItem(BigDecimal amount, boolean empty) {
                super.updateItem(amount, empty);
                // While not editing, show "$2,140.00" (red if negative) like the other money columns.
                if (!empty && !isEditing()) {
                    setText(Format.money(amount));
                }
                getStyleClass().removeAll("negative");
                if (!empty && amount != null && amount.signum() < 0) {
                    getStyleClass().add("negative");
                }
            }
        });
        column.setOnEditCommit(event -> onSave.accept(event.getRowValue(), event.getNewValue()));
        column.getStyleClass().add("editable-column");
        return column;
    }

    /**
     * A clickable web link, shown short ("mybank.com") and opened in your normal browser.
     * Hovering shows the full address. Empty when the row has no link.
     */
    public static <T> TableColumn<T, String> link(String title, Function<T, String> url) {
        TableColumn<T, String> column = readOnlyColumn(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(url.apply(cell.getValue())));
        column.setCellFactory(col -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink();

            {
                link.setOnAction(event -> Browser.open(getItem()));
            }

            @Override
            protected void updateItem(String address, boolean empty) {
                super.updateItem(address, empty);
                if (empty || address == null) {
                    setGraphic(null);
                } else {
                    link.setText(Browser.shortName(address));
                    link.setTooltip(new Tooltip(address));
                    setGraphic(link);
                }
            }
        });
        return column;
    }

    /**
     * A column with a button in every row, e.g. "Mark paid".
     *
     * @param label   the button text for a row
     * @param onClick what to do with that row's item when its button is clicked
     */
    public static <T> TableColumn<T, T> button(String title, Function<T, String> label, Consumer<T> onClick) {
        TableColumn<T, T> column = readOnlyColumn(title);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            // One button per cell, reused as the table scrolls.
            private final Button button = new Button();

            {
                button.setOnAction(event -> onClick.accept(getItem()));
            }

            @Override
            protected void updateItem(T row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                } else {
                    button.setText(label.apply(row));
                    setGraphic(button);
                }
            }
        });
        return column;
    }

    /** A date column: "Oct 18, 2026", blank when there's no date. Sorts by the date, not the text. */
    public static <T> TableColumn<T, LocalDate> date(String title, Function<T, LocalDate> value) {
        TableColumn<T, LocalDate> column = readOnlyColumn(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setText(empty ? null : Format.date(date));
            }
        });
        return column;
    }
}
