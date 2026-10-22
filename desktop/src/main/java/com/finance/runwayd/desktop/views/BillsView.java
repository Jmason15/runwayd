package com.finance.runwayd.desktop.views;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.Category;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.core.model.ScheduledItem.Kind;
import com.finance.runwayd.desktop.ui.Alerts;
import com.finance.runwayd.desktop.ui.Browser;
import com.finance.runwayd.desktop.ui.Format;
import com.finance.runwayd.desktop.Refreshable;
import com.finance.runwayd.desktop.ui.Tables;
import com.finance.runwayd.desktop.dialogs.ItemDialog;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every bill, paycheck and transfer, grouped by category.
 *
 * <pre>
 *   [ Search bills...   ]  [All] [Bills] [Income] [Transfers]                [Add]
 *   ▾ Housing (2)                         -$3,069.00 / month  ·  -$36,828.00 / year
 *       Rent       -$1,450.00   Monthly   Nov 1    Checking        [Mark paid]
 *       ...
 *   ▸ Subscriptions (7)                       -$84.54 / month  ·  -$1,014.52 / year
 *   Showing 30 of 30                 Net: +$1,234.00 / month  ·  +$14,808.00 / year
 * </pre>
 *
 * <p>How to work with it (standard list behavior):
 * <ul>
 *   <li>Type in the search box, or click a filter button, to narrow the list.</li>
 *   <li>Click a group's header to collapse or expand it.</li>
 *   <li>Click an amount to change it. Enter saves, Esc cancels.</li>
 *   <li>Double-click a bill to edit everything about it.</li>
 *   <li>Right-click a bill for Open website / Edit / Mark paid / Undo last mark paid / Delete, or press Delete to remove it.</li>
 *   <li>After "Mark paid", the bar under the list offers Undo. A bill marked paid ahead shows a small
 *       Undo button next to it, and marking one a month or more ahead asks first.</li>
 * </ul>
 *
 * <p>Each group is its own small table. Columns are given the same widths in every
 * group, so they line up down the page.
 */
public final class BillsView extends BorderPane implements Refreshable {

    private final FinanceService service;

    private final TextField searchField = new TextField();
    private final ToggleGroup kindFilter = new ToggleGroup();
    private final VBox groups = new VBox(10);
    private final Label countLabel = new Label();
    private final Label netLabel = new Label();
    // "Marked Rent paid for Nov 1, 2026.  [Undo]" under the list, shown after each Mark paid click.
    private final Label undoMessage = new Label();
    private final Button undoButton = new Button("Undo");
    private final HBox undoBar = new HBox(12, undoMessage, undoButton);

    /** Groups you've collapsed stay collapsed when the list reloads. */
    private final Set<Category> collapsed = EnumSet.noneOf(Category.class);

    public BillsView(FinanceService service) {
        this.service = service;
        setPadding(new Insets(16));

        ScrollPane scroller = new ScrollPane(groups);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("plain-scroll");

        setTop(buildToolbar());
        setCenter(scroller);
        undoBar.setAlignment(Pos.CENTER_LEFT);
        undoBar.setPadding(new Insets(10, 0, 0, 0));
        showUndoBar(false);
        setBottom(new VBox(undoBar, buildFooter()));
    }

    // -------------------------------------------------------------------------
    // Toolbar and footer
    // -------------------------------------------------------------------------

    private HBox buildToolbar() {
        searchField.setPromptText("Search bills");
        searchField.setPrefWidth(260);
        searchField.textProperty().addListener((obs, oldText, newText) -> refresh());

        HBox filterButtons = new HBox(4,
                filterButton("All", null),
                filterButton("Bills", Kind.BILL),
                filterButton("Income", Kind.INCOME),
                filterButton("Transfers", Kind.TRANSFER));
        kindFilter.selectToggle(kindFilter.getToggles().get(0));
        // Clicking the selected button again would leave nothing selected; keep it selected instead.
        kindFilter.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                kindFilter.selectToggle(oldToggle);
            } else {
                refresh();
            }
        });

        Button add = new Button("Add");
        add.getStyleClass().add("primary");
        add.setOnAction(event -> add());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(12, searchField, filterButtons, spacer, add);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 12, 0));
        return toolbar;
    }

    /** One of the All / Bills / Income / Transfers buttons. {@code kind} is null for "All". */
    private ToggleButton filterButton(String text, Kind kind) {
        ToggleButton button = new ToggleButton(text);
        button.setUserData(kind);
        button.setToggleGroup(kindFilter);
        return button;
    }

    private HBox buildFooter() {
        countLabel.getStyleClass().add("hint");
        netLabel.getStyleClass().add("heading");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(countLabel, spacer, netLabel);
        footer.setPadding(new Insets(10, 0, 0, 0));
        return footer;
    }

    // -------------------------------------------------------------------------
    // Building the groups
    // -------------------------------------------------------------------------

    @Override
    public void refresh() {
        // Finished bills from earlier months (e.g. a one-time bill paid last month) are left out.
        List<ScheduledItem> all = service.items().stream().filter(item -> !service.droppedOff(item)).toList();
        List<ScheduledItem> visible = all.stream().filter(this::matchesFilters).toList();

        // EnumMap keeps the groups in the Category order: Income, Housing, Utilities, ...
        Map<Category, List<ScheduledItem>> byCategory = visible.stream()
                .collect(Collectors.groupingBy(ScheduledItem::category,
                        () -> new EnumMap<>(Category.class), Collectors.toList()));

        groups.getChildren().clear();
        byCategory.forEach((category, items) -> groups.getChildren().add(categoryGroup(category, items)));
        if (visible.isEmpty()) {
            Label empty = new Label(all.isEmpty()
                    ? "No bills or income yet. Click \"Add\" to enter one."
                    : "Nothing matches your search.");
            empty.getStyleClass().add("hint");
            groups.getChildren().add(empty);
        }

        countLabel.setText("Showing " + visible.size() + " of " + all.size());
        netLabel.setText("Net: " + perMonthAndYear(service.monthlyTotal(visible), service.yearlyTotal(visible), true));
    }

    /** True if the item passes both the search box and the All/Bills/Income/Transfers filter. */
    private boolean matchesFilters(ScheduledItem item) {
        Kind kind = (Kind) kindFilter.getSelectedToggle().getUserData();
        if (kind != null && item.kind() != kind) {
            return false;
        }
        String search = searchField.getText().trim().toLowerCase(Locale.ROOT);
        if (search.isEmpty()) {
            return true;
        }
        // Search the name, the category and the account names.
        String searchable = String.join(" ", item.name(), item.category().label(), accountText(item))
                .toLowerCase(Locale.ROOT);
        return searchable.contains(search);
    }

    /** A collapsible section: "Housing (2) .... -$3,069.00 / month · -$36,828.00 / year", with its bills underneath. */
    private TitledPane categoryGroup(Category category, List<ScheduledItem> items) {
        Label name = new Label(category.label() + "  (" + items.size() + ")");
        name.getStyleClass().add("group-title");
        BigDecimal total = service.monthlyTotal(items);
        Label totalLabel = new Label(perMonthAndYear(total, service.yearlyTotal(items), false));
        totalLabel.getStyleClass().add(total.signum() < 0 ? "negative" : "positive");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(name, spacer, totalLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        TitledPane group = new TitledPane(null, itemTable(items));
        group.setGraphic(header);
        group.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        // Stretch the header across the full width (minus room for the arrow), so the total sits on the right.
        header.prefWidthProperty().bind(group.widthProperty().subtract(40));

        // While searching, open every group so matches are never hidden.
        boolean searching = !searchField.getText().isBlank();
        group.setExpanded(searching || !collapsed.contains(category));
        group.expandedProperty().addListener((obs, wasOpen, isOpen) -> {
            if (isOpen) {
                collapsed.remove(category);
            } else {
                collapsed.add(category);
            }
        });
        return group;
    }

    /**
     * "-$84.54 / month  ·  -$1,014.52 / year". The monthly figure spreads yearly and other
     * bills evenly (Amazon's $139 a year counts as $11.58); the yearly one is the real yearly cost.
     *
     * @param showPlus put a "+" in front of positive amounts (for the net total)
     */
    private static String perMonthAndYear(BigDecimal perMonth, BigDecimal perYear, boolean showPlus) {
        String plus = showPlus && perMonth.signum() > 0 ? "+" : "";
        String plusYear = showPlus && perYear.signum() > 0 ? "+" : "";
        return plus + Format.money(perMonth) + " / month  ·  " + plusYear + Format.money(perYear) + " / year";
    }

    /** The bills in one category. Same columns and widths in every group so they line up. */
    private TableView<ScheduledItem> itemTable(List<ScheduledItem> items) {
        TableView<ScheduledItem> table = Tables.create("");
        table.setEditable(true); // for the click-to-edit Amount column
        table.getItems().setAll(items);
        Tables.sizeToRows(table, Integer.MAX_VALUE); // show every row; the page scrolls instead

        table.getColumns().add(width(220, Tables.text("Name", ScheduledItem::name)));
        table.getColumns().add(width(130, Tables.editableMoney("Amount", service::signedAmount, this::changeAmount)));
        table.getColumns().add(width(170, Tables.text("How often", item -> item.frequency().label())));
        table.getColumns().add(width(120, Tables.date("Next due", item -> service.nextDue(item).orElse(null))));
        table.getColumns().add(width(200, Tables.text("Account", this::accountText)));
        table.getColumns().add(width(150, Tables.link("Website", ScheduledItem::website)));
        table.getColumns().add(width(110, Tables.date("Ends", ScheduledItem::endDate)));
        table.getColumns().add(width(190, payColumn()));

        table.setRowFactory(t -> itemRow(table));
        table.setOnKeyPressed(event -> {
            ScheduledItem selected = table.getSelectionModel().getSelectedItem();
            if (selected != null && table.getEditingCell() == null
                    && (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE)) {
                delete(selected);
            }
        });
        return table;
    }

    /** A row that opens the editor on double-click and has a right-click menu. */
    private TableRow<ScheduledItem> itemRow(TableView<ScheduledItem> table) {
        TableRow<ScheduledItem> row = new TableRow<>();

        row.setOnMouseClicked(event -> {
            // Skip when the click was on the Amount cell, which edits in place instead.
            if (event.getClickCount() == 2 && !row.isEmpty() && table.getEditingCell() == null) {
                edit(row.getItem());
            }
        });

        MenuItem openSite = new MenuItem("Open website");
        MenuItem edit = new MenuItem("Edit…");
        MenuItem markPaid = new MenuItem("Mark paid");
        MenuItem undoPaid = new MenuItem("Undo last mark paid");
        MenuItem delete = new MenuItem("Delete");
        openSite.setOnAction(event -> Browser.open(row.getItem().website()));
        edit.setOnAction(event -> edit(row.getItem()));
        markPaid.setOnAction(event -> markPaid(row.getItem()));
        undoPaid.setOnAction(event -> undoPaid(row.getItem()));
        delete.setOnAction(event -> delete(row.getItem()));
        ContextMenu menu = new ContextMenu(openSite, edit, markPaid, undoPaid, new SeparatorMenuItem(), delete);
        // Grey out what doesn't apply: no website to open, or no "mark paid" to take back.
        menu.setOnShowing(event -> {
            openSite.setDisable(row.getItem() == null || row.getItem().website() == null);
            undoPaid.setDisable(row.getItem() == null || !service.canUndoPaid(row.getItem()));
        });
        // Only offer the menu on rows that actually hold a bill.
        row.emptyProperty().addListener((obs, wasEmpty, isEmpty) -> row.setContextMenu(isEmpty ? null : menu));
        return row;
    }

    private static <S, V> TableColumn<S, V> width(double pixels, TableColumn<S, V> column) {
        column.setPrefWidth(pixels);
        return column;
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void add() {
        if (service.accounts().isEmpty()) {
            Alerts.info("Add an account on the Accounts tab first, so bills have somewhere to come from.");
            return;
        }
        new ItemDialog(null, service.accounts()).showAndWait().ifPresent(item -> {
            service.addItem(item);
            refresh();
        });
    }

    private void edit(ScheduledItem item) {
        new ItemDialog(item, service.accounts()).showAndWait().ifPresent(changed -> {
            service.updateItem(changed);
            refresh();
        });
    }

    /**
     * Typed straight into the Amount column. The sign doesn't matter; the bill's type decides it.
     * Card-minimum payments can't be typed over, since their amount follows the balance.
     */
    private void changeAmount(ScheduledItem item, BigDecimal typed) {
        if (item.followsCardMinimum()) {
            Alerts.info(item.name() + " pays the card minimum, which changes with the balance. "
                    + "To pay more, use the debt bucket on the Budget tab.");
            refresh(); // put the old number back
            return;
        }
        service.updateItem(item.withAmount(typed.abs()));
        refresh();
    }

    /**
     * The last column: "Mark paid" (or "Mark received"), plus a small "Undo" beside it when
     * the bill is marked paid ahead, so a click too many is easy to take back.
     */
    private TableColumn<ScheduledItem, ScheduledItem> payColumn() {
        TableColumn<ScheduledItem, ScheduledItem> column = new TableColumn<>("");
        column.setSortable(false);
        column.setEditable(false);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            private final Button pay = new Button();
            private final Label done = new Label(); // "Paid Oct 21" or "Ended", when nothing is left to pay
            private final Button undo = new Button("Undo");
            private final HBox buttons = new HBox(6, pay, done, undo);

            {
                pay.setOnAction(event -> markPaid(getItem()));
                undo.setOnAction(event -> undoPaid(getItem()));
                buttons.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(ScheduledItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                boolean somethingDue = service.nextDue(item).isPresent();
                Optional<LocalDate> paidOn = service.paidOn(item);
                Optional<LocalDate> ahead = service.paidAheadTo(item);

                // Something to pay: the button. A one-time bill that's paid: "Paid Oct 21" and Undo.
                // A repeating bill past its end date: "Ended". Never a button that does nothing.
                pay.setText(Format.payButtonText(item));
                show(pay, somethingDue);
                done.setText(paidOn.map(date -> (item.kind() == Kind.INCOME ? "Received " : "Paid ") + Format.date(date))
                        .orElse("Ended"));
                show(done, !somethingDue);

                boolean canUndo = paidOn.isPresent() || ahead.isPresent();
                show(undo, canUndo);
                if (paidOn.isPresent()) {
                    undo.setTooltip(new Tooltip("Mark it not " + doneWord(item) + " again."));
                } else {
                    ahead.ifPresent(date -> undo.setTooltip(new Tooltip("Marked " + doneWord(item) + " ahead, up to "
                            + Format.date(date) + ". Click to unmark " + Format.date(date) + ".")));
                }
                setGraphic(buttons);
            }

            private void show(Node node, boolean visible) {
                node.setVisible(visible);
                node.setManaged(visible);
            }
        });
        return column;
    }

    /**
     * One click: the next due date is marked paid and "Next due" jumps to the one after.
     * A date a month or more away asks first, since that's usually a click too many.
     * Either way, the bar under the list offers Undo.
     */
    private void markPaid(ScheduledItem item) {
        if (service.wouldPayFarAhead(item)) {
            LocalDate due = service.nextDue(item).orElseThrow();
            long months = ChronoUnit.MONTHS.between(LocalDate.now().withDayOfMonth(1), due.withDayOfMonth(1));
            String away = months <= 1 ? "about a month" : "about " + months + " months";
            boolean sure = Alerts.confirm(item.name() + "'s next date is " + Format.date(due) + ", " + away
                    + " from now.\n\nMark it " + doneWord(item) + " already? Only do this if it really has been "
                    + doneWord(item) + "; until then the forecast should still count it.");
            if (!sure) {
                return;
            }
        }
        service.markPaid(item.id()).ifPresent(date -> {
            undoMessage.setText("Marked " + item.name() + " " + doneWord(item) + " for " + Format.date(date) + ".");
            undoButton.setOnAction(event -> undoPaid(item));
            showUndoBar(true);
        });
        refresh();
    }

    /** Takes back the latest "mark paid" on this bill, so the forecast counts that date again. */
    private void undoPaid(ScheduledItem item) {
        showUndoBar(false);
        // Look the bill up again: the row may hold an older copy from before the last click.
        ScheduledItem current = service.items().stream().filter(i -> i.id() == item.id()).findFirst().orElse(item);
        service.undoLastPaid(current.id()).ifPresent(date -> {
            undoMessage.setText(current.name() + " for " + Format.date(date) + " counts as not "
                    + doneWord(current) + " again.");
            undoButton.setOnAction(event -> undoPaid(current));
            showUndoBar(true);
        });
        refresh();
    }

    /** "paid" for bills and transfers, "received" for income. */
    private static String doneWord(ScheduledItem item) {
        return item.kind() == Kind.INCOME ? "received" : "paid";
    }

    private void showUndoBar(boolean show) {
        undoBar.setVisible(show);
        undoBar.setManaged(show);
    }

    private void delete(ScheduledItem item) {
        if (Alerts.confirm("Delete \"" + item.name() + "\"?")) {
            service.deleteItem(item.id());
            refresh();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Which account(s) the item uses: "Checking", or "Checking → Store Card" for a transfer. */
    private String accountText(ScheduledItem item) {
        return switch (item.kind()) {
            case INCOME -> accountName(item.toAccountId());
            case BILL -> accountName(item.fromAccountId());
            case TRANSFER -> accountName(item.fromAccountId()) + " → " + accountName(item.toAccountId());
        };
    }

    private String accountName(Integer accountId) {
        return accountId == null ? "" : service.findAccount(accountId).map(Account::name).orElse("(missing)");
    }
}
