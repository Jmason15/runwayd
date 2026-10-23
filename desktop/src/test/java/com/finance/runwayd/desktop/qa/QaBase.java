package com.finance.runwayd.desktop.qa;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.storage.JsonDataStore;
import com.finance.runwayd.desktop.MainWindow;
import com.finance.runwayd.desktop.ui.Alerts;
import com.finance.runwayd.desktop.ui.Format;
import com.finance.runwayd.desktop.ui.Theme;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base for the QA click-through tests: opens the real runwayd window on a brand-new, throwaway
 * budget file, and gives the tests a robot that clicks, types and reads the screen like a person.
 *
 * <p>Each test gets its own window and its own empty file, so tests never affect each other or any
 * real budget.
 *
 * <p><b>Checking the numbers.</b> A test works out what the screen should say by hand (e.g. $2,000
 * minus a $150 bill is $1,850) and compares it to the text on screen. It can also open the saved
 * file again with {@link #reopened()} to make sure what's shown is what was saved.
 *
 * <p>By default the window is invisible (Monocle headless), so a build never takes over the
 * screen. To watch it: {@code mvn -pl desktop test -Dqa.headless=false}.
 */
// A test that gets stuck (e.g. on a pop-up nobody answers) fails instead of hanging the release.
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class QaBase extends ApplicationTest {

    static {
        // Must be set before JavaFX starts, which is why it's here and not in a @BeforeAll.
        if (Boolean.parseBoolean(System.getProperty("qa.headless", "true"))) {
            System.setProperty("testfx.robot", "glass");
            System.setProperty("testfx.headless", "true");
            System.setProperty("glass.platform", "Monocle");
            System.setProperty("monocle.platform", "Headless");
            System.setProperty("headless.geometry", "1600x1200-32"); // room for the 1100 x 880 window and its pop-ups
            System.setProperty("prism.order", "sw");
            System.setProperty("prism.text", "t2k");
            System.setProperty("java.awt.headless", "true");
        }
    }

    @TempDir
    Path tempDir;

    /** Where this test's budget is saved. */
    Path dataFile;
    /** The window's own service: what the screen is showing. */
    FinanceService service;
    MainWindow window;

    /** Opens the window on an empty budget, after {@link #before(FinanceService)} has had a chance to fill it. */
    @Override
    public void start(Stage stage) {
        dataFile = tempDir.resolve("QA budget.json");
        FinanceService setup = new FinanceService(new JsonDataStore(dataFile));
        before(setup);
        service = new FinanceService(new JsonDataStore(dataFile));
        // Like App: an error a click causes is shown as a pop-up, not lost.
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
            Throwable root = error;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            Alerts.error(root.getMessage());
        });
        Theme.setDark(true);
        window = new MainWindow(service, dataFile, false, false);
        Scene scene = new Scene(window, 1100, 880);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Fills the budget before the window opens, for set-up that's not what the test is about
     * (e.g. a balance from last week). Most tests leave this alone and do everything by clicking.
     */
    void before(FinanceService budget) {
    }

    /**
     * Closes everything this test opened, while its budget file still exists. A pop-up still waiting
     * for an answer would block the next test. And the Budget tab saves a box when it loses focus: if
     * that happened later, once this test's folder is gone, the save would fail as the next test starts.
     * Emptying each window first makes that last save happen now.
     */
    @AfterEach
    void closeLeftoverWindows() {
        javafx.application.Platform.runLater(() -> {
            for (javafx.stage.Window w : new java.util.ArrayList<>(javafx.stage.Window.getWindows())) {
                if (w.getScene() != null) {
                    // Moving the focus off the old screen is what runs its "save when you leave" right now.
                    javafx.scene.layout.Pane blank = new javafx.scene.layout.Pane();
                    w.getScene().setRoot(blank);
                    blank.requestFocus();
                }
                w.hide();
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The budget as saved on disk, read fresh, the way the app would read it next time it opens. */
    FinanceService reopened() {
        return new FinanceService(new JsonDataStore(dataFile));
    }

    // -------------------------------------------------------------------------
    // Getting around
    // -------------------------------------------------------------------------

    /** Clicks a tab: "Forecast", "Bills & Income", "Debt", "Budget" or "Accounts". */
    void openTab(String name) {
        clickOn(lookup(".tab-label").match((Labeled label) -> name.equals(label.getText())).<Node>query());
        settle();
    }

    /** Clicks the button with this text that's on screen now (on the open tab or the open pop-up). */
    void press(String buttonText) {
        clickOn(shown(".button", buttonText));
        settle();
    }

    /** The visible node of this CSS class with this text; fails if there isn't exactly one on top. */
    Node shown(String cssClass, String text) {
        List<Node> matches = lookup(cssClass)
                .match((Node node) -> node instanceof Labeled labeled && text.equals(labeled.getText())
                        && isReallyVisible(node))
                .queryAll().stream().toList();
        assertTrue(!matches.isEmpty(), "Nothing on screen says \"" + text + "\"");
        // A pop-up's buttons win over the main window's, like for a person.
        return matches.get(matches.size() - 1);
    }

    /** A node with this id (see the setId calls in the views). */
    <T extends Node> T byId(String id) {
        return lookup("#" + id).match(this::isReallyVisible).query();
    }

    /** True if the node and everything around it is visible (tabs hide their content when not selected). */
    boolean isReallyVisible(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return node.getScene() != null && node.getScene().getWindow() != null && node.getScene().getWindow().isShowing();
    }

    /**
     * Presses and releases a named key (Enter, Backspace, Delete...) on whatever has the focus.
     *
     * <p>Sent straight to the focused control, the way the window would pass it on. TestFX's own
     * key presses never arrive in the invisible (headless) window, though typed letters do.
     */
    void key(KeyCode code) {
        // Not interact(): a key that opens a question (Delete) would wait for the answer, and so would we.
        javafx.application.Platform.runLater(() -> {
            Node target = window.getScene().getFocusOwner();
            for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
                if (w.isFocused() && w.getScene() != null && w.getScene().getFocusOwner() != null) {
                    target = w.getScene().getFocusOwner(); // a pop-up's field wins
                }
            }
            if (target == null) {
                target = window;
            }
            for (javafx.event.EventType<KeyEvent> type : List.of(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
                javafx.event.Event.fireEvent(target, new KeyEvent(type, "", "", code, false, false, false, false));
            }
        });
        settle();
    }

    /** Waits for JavaFX to finish what the last click started (refreshes, Platform.runLater). */
    void settle() {
        WaitForAsyncUtils.waitForFxEvents();
    }

    // -------------------------------------------------------------------------
    // Pop-ups
    // -------------------------------------------------------------------------

    /** The pop-up on top (a form, a question or an error), or empty if none is open. */
    Optional<DialogPane> popUp() {
        settle();
        return lookup(".dialog-pane").match(this::isReallyVisible).queryAll().stream()
                .map(DialogPane.class::cast)
                .reduce((first, second) -> second);
    }

    DialogPane requirePopUp() {
        return popUp().orElseThrow(() -> new AssertionError("Expected a pop-up, but none is open"));
    }

    /** The control next to "label" in the open form, e.g. formField("Name"). */
    <T extends Node> T formField(String label) {
        GridPane grid = gridIn(requirePopUp().getContent())
                .orElseThrow(() -> new AssertionError("The pop-up isn't a form"));
        Integer row = grid.getChildren().stream()
                .filter(node -> node instanceof Label l && label.equals(l.getText()) && node.isVisible())
                .map(GridPane::getRowIndex).findFirst()
                .orElseThrow(() -> new AssertionError("The form has no \"" + label + "\" field"));
        @SuppressWarnings("unchecked")
        T control = (T) grid.getChildren().stream()
                .filter(node -> Objects.equals(GridPane.getRowIndex(node), row) && !(node instanceof Label))
                .findFirst().orElseThrow();
        return control;
    }

    /** The first GridPane (the form's rows) inside {@code node}. GridPanes have no CSS class, so search by type. */
    private static Optional<GridPane> gridIn(Node node) {
        if (node instanceof GridPane grid) {
            return Optional.of(grid);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Optional<GridPane> found = gridIn(child);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /** Clicks into a form's text box, clears it and types {@code text}, like a person would. */
    void type(String label, String text) {
        typeInto(formField(label), text);
    }

    void typeInto(TextField field, String text) {
        clickOn(field);
        interact(field::selectAll);
        key(KeyCode.BACK_SPACE);
        if (!text.isEmpty()) {
            write(text, 0); // no pause between letters
        }
        settle();
    }

    /** Picks a value in a form's drop-down, chosen by how it's shown (e.g. "Monthly"). */
    void choose(String label, String shownAs) {
        ComboBox<?> box = formField(label);
        chooseIn(box, shownAs);
    }

    <T> void chooseIn(ComboBox<T> box, String shownAs) {
        T value = box.getItems().stream()
                .filter(item -> shownAs.equals(String.valueOf(item)) || shownAs.equals(box.getConverter() == null
                        ? null : box.getConverter().toString(item)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No \"" + shownAs + "\" in " + box.getItems()));
        interact(() -> box.setValue(value));
        settle();
    }

    /** Sets a form's date by typing it into the date box, the way most people do. */
    void typeDate(String label, LocalDate date) {
        DatePicker picker = formField(label);
        String text = picker.getConverter().toString(date);
        clickOn(picker.getEditor());
        interact(() -> picker.getEditor().selectAll());
        key(KeyCode.BACK_SPACE);
        write(text, 0); // no pause between letters
        settle();
    }

    /** Clicks Save on the open form. */
    void save() {
        clickOn(requirePopUp().lookupButton(ButtonType.OK));
        settle();
    }

    /** The red message a form shows when something's wrong, or null if there isn't one. */
    String formError() {
        return requirePopUp().lookupAll(".form-error").stream()
                .map(Label.class::cast).filter(Node::isVisible).map(Label::getText).findFirst().orElse(null);
    }

    /** The text of the question or error on top. */
    String popUpText() {
        return requirePopUp().getContentText();
    }

    /** Answers the question on top with OK (or Cancel). */
    void answer(boolean ok) {
        clickOn(requirePopUp().lookupButton(ok ? ButtonType.OK : ButtonType.CANCEL));
        settle();
    }

    void cancel() {
        answer(false);
    }

    // -------------------------------------------------------------------------
    // Reading tables
    // -------------------------------------------------------------------------

    /** The text shown in one cell: row {@code row} (0 = top), column titled {@code column}. */
    String cell(TableView<?> table, int row, String column) {
        scrollToRow(table, row);
        return table.lookupAll(".table-cell").stream()
                .map(node -> (TableCell<?, ?>) node)
                .filter(c -> c.getIndex() == row && c.getTableColumn() != null
                        && column.equals(c.getTableColumn().getText()))
                .findFirst()
                .map(this::shownText)
                .orElseThrow(() -> new AssertionError("No cell at row " + row + ", column \"" + column + "\""));
    }

    /**
     * Tables only draw the rows on screen, so scroll to {@code row} first if it isn't drawn yet
     * (like a person scrolling down to it).
     */
    private void scrollToRow(TableView<?> table, int row) {
        settle();
        boolean drawn = table.lookupAll(".table-row-cell").stream()
                .anyMatch(node -> ((javafx.scene.control.TableRow<?>) node).getIndex() == row && node.isVisible());
        if (!drawn) {
            interact(() -> table.scrollTo(row));
            settle();
        }
    }

    /** What a cell shows: its text, or the text of a link or button inside it, or "[x]"/"[ ]" for a tick box. */
    private String shownText(TableCell<?, ?> c) {
        if (c.getGraphic() instanceof CheckBox box) {
            return box.isSelected() ? "[x]" : "[ ]";
        }
        if (c.getGraphic() instanceof Labeled labeled) {
            return labeled.getText();
        }
        if (c.getGraphic() != null) {
            return visibleText(c.getGraphic()); // e.g. the Bills tab's "Paid Oct 21" next to a hidden button
        }
        return c.getText() == null ? "" : c.getText();
    }

    /** The text of the first visible label or button inside {@code node}. */
    private static String visibleText(Node node) {
        if (!node.isVisible()) {
            return "";
        }
        if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isEmpty()) {
            return labeled.getText();
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                String text = visibleText(child);
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    /** The row (0 = top) whose {@code column} shows {@code text}, e.g. rowWhere(table, "Name", "Rent"). */
    int rowWhere(TableView<?> table, String column, String text) {
        for (int row = 0; row < table.getItems().size(); row++) {
            if (text.equals(cell(table, row, column))) {
                return row;
            }
        }
        throw new AssertionError("No row with " + column + " = \"" + text + "\" (rows: " + table.getItems().size() + ")");
    }

    /** The cell in {@code column} on the row where {@code keyColumn} shows {@code key}. */
    String cellWhere(TableView<?> table, String keyColumn, String key, String column) {
        return cell(table, rowWhere(table, keyColumn, key), column);
    }

    /** The node drawn in a cell, e.g. to click its tick box or link. */
    TableCell<?, ?> cellNode(TableView<?> table, int row, String column) {
        scrollToRow(table, row);
        return table.lookupAll(".table-cell").stream()
                .map(node -> (TableCell<?, ?>) node)
                .filter(c -> c.getIndex() == row && c.getTableColumn() != null
                        && column.equals(c.getTableColumn().getText()))
                .findFirst().orElseThrow();
    }

    /** Every table on the open tab whose columns include {@code column}, e.g. the Bills tab's groups. */
    List<TableView<?>> tablesWithColumn(String column) {
        settle();
        return lookup(".table-view").match(this::isReallyVisible).queryAll().stream()
                .<TableView<?>>map(node -> (TableView<?>) node)
                .filter(t -> t.getColumns().stream().map(TableColumn::getText).anyMatch(column::equals))
                .toList();
    }

    /** Clicks a money cell, types a new amount and presses Enter (the click-to-edit amounts). */
    void typeIntoCell(TableView<?> table, int row, String column, String text) {
        clickOn(cellNode(table, row, column));
        settle();
        TextField editor = table.lookupAll(".text-field").stream().filter(TextField.class::isInstance)
                .map(TextField.class::cast).filter(Node::isVisible).findFirst()
                .orElseThrow(() -> new AssertionError("Clicking the cell didn't open it for typing"));
        interact(editor::selectAll);
        key(KeyCode.BACK_SPACE);
        write(text, 0); // no pause between letters
        key(KeyCode.ENTER);
        settle();
    }

    // -------------------------------------------------------------------------
    // Common steps, done by clicking
    // -------------------------------------------------------------------------

    /** Adds a checking account on the Accounts tab, balance as of today. */
    void addAccount(String name, String balance) {
        openTab("Accounts");
        press("Add");
        type("Name", name);
        type("Balance", balance);
        save();
    }

    /**
     * Adds a bill, paycheck or transfer on the Bills tab.
     *
     * @param kind  "Bill", "Income" or "Transfer / payment"
     * @param often e.g. "Monthly", "Every 2 weeks", "One time"
     */
    void addItem(String kind, String name, String amount, String often, LocalDate first, String account) {
        openTab("Bills & Income");
        press("Add");
        type("Name", name);
        choose("Type", kind);
        type("Amount", amount);
        choose("How often", often);
        typeDate("First date", first);
        choose(kind.equals("Income") ? "Paid into" : "Paid from", account);
        save();
        assertTrue(popUp().isEmpty(), "the form should close after Save, but says: " + popUp().map(p -> formErrorOf(p)).orElse(""));
    }

    void addBill(String name, String amount, String often, LocalDate first) {
        addItem("Bill", name, amount, often, first, "Checking");
    }

    private static String formErrorOf(DialogPane pane) {
        return pane.lookupAll(".form-error").stream().map(Label.class::cast).map(Label::getText).findFirst().orElse("");
    }

    /**
     * What the forecast says an account holds at the end of {@code date} (within the next 3 months), read
     * off the Forecast tab like a person would: pick the account under "Details for" and take the running
     * balance from the last ledger line on or before that day. With nothing before it, the account's
     * Balance. Cards and loans read as owed (positive), the way the ledger shows them.
     */
    String balanceOn(String account, LocalDate date) {
        openTab("Forecast");
        chooseIn(byId("detailsAccount"), account);
        TableView<?> ledger = byId("ledger");
        String found = null;
        for (int row = 0; row < ledger.getItems().size(); row++) {
            LocalDate day = ((com.finance.runwayd.core.forecast.LedgerEntry) ledger.getItems().get(row)).date();
            if (!day.isAfter(date)) {
                found = cell(ledger, row, "Balance after");
            }
        }
        if (found != null) {
            return found;
        }
        String balance = forecastCell(account, "Balance");
        boolean owed = List.of("Credit card", "Loan").contains(forecastCell(account, "Type"));
        return owed && balance.startsWith("-") ? balance.substring(1) : balance; // owed, like the ledger
    }

    /** On the Forecast tab: what the Accounts table shows for an account in a column ("Balance", "Updated", "Watch out for"). */
    String forecastCell(String account, String column) {
        openTab("Forecast");
        return cellWhere(byId("forecastAccounts"), "Account", account, column);
    }

    // -------------------------------------------------------------------------
    // Money
    // -------------------------------------------------------------------------

    /** How the app shows an amount, e.g. money("-150") is "-$150.00". */
    static String money(String amount) {
        return Format.money(new BigDecimal(amount));
    }

    /** Reads an amount as shown, e.g. "-$1,234.56", back into a number. */
    static BigDecimal amountShown(String shown) {
        return new BigDecimal(shown.replace("$", "").replace(",", ""));
    }

    static void assertShows(String expectedAmount, String shown, String what) {
        assertEquals(money(expectedAmount), shown, what);
    }
}
