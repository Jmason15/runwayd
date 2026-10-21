package com.finance.runwayd.desktop;

import com.finance.runwayd.core.storage.BudgetLibrary;
import com.finance.runwayd.desktop.ui.Alerts;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import javafx.scene.layout.HBox;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * The bar across the top of the window: which budget is open, and what you can do with budgets.
 *
 * <pre>
 *   Budget: [ Our household ▾ ]  [ Budget ▾ ]
 *                                  New…  Duplicate…  Rename…  Delete…
 *                                  Share this budget…  Open a shared budget…
 *                                  Export…  Import…
 * </pre>
 *
 * <p>Each budget is a whole separate set of accounts, bills, debts and paycheck split (see
 * BudgetLibrary in core). Picking one here reopens the window on it.
 */
public final class BudgetSwitcher extends HBox {

    private final BudgetLibrary library;
    private final String current;
    private final Consumer<String> openBudget;

    /**
     * @param library    where the budgets are
     * @param current    the budget that's open now
     * @param openBudget opens a budget by name (App rebuilds the window around it)
     */
    public BudgetSwitcher(BudgetLibrary library, String current, Consumer<String> openBudget) {
        this.library = library;
        this.current = current;
        this.openBudget = openBudget;

        ComboBox<String> picker = new ComboBox<>();
        picker.getItems().setAll(library.names());
        picker.setValue(current);
        picker.setPrefWidth(240);
        // Shared budgets show as "Household (shared)"; the value itself stays the plain name.
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(String name) {
                return name == null ? "" : library.isShared(name) ? name + " (shared)" : name;
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        });
        picker.setOnAction(event -> {
            String picked = picker.getValue();
            if (picked != null && !picked.equals(current)) {
                openBudget.accept(picked);
            }
        });

        boolean shared = library.isShared(current);
        MenuItem share = item("Share this budget (in iCloud Drive, Dropbox…)…", this::share);
        share.setDisable(shared); // already shared
        MenuButton menu = new MenuButton("Budget");
        menu.getItems().addAll(
                item("New budget…", this::newBudget),
                item("Duplicate \"" + current + "\"…", this::duplicate),
                item("Rename…", this::rename),
                item(shared ? "Remove from my list…" : "Delete…", this::delete),
                new SeparatorMenuItem(),
                share,
                item("Open a shared budget…", this::openShared),
                new SeparatorMenuItem(),
                item("Open the demo budget", this::openDemo),
                new SeparatorMenuItem(),
                item("Export (to send to someone)…", this::export),
                item("Import (a budget someone sent you)…", this::importBudget));

        Label label = new Label("Budget:");
        label.getStyleClass().add("heading");
        getChildren().addAll(label, picker, menu);
        setSpacing(8);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(8, 16, 0, 16));
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    private void newBudget() {
        Alerts.askText("New budget", "Name for the new, empty budget:", "")
                .ifPresent(name -> openBudget.accept(library.create(name)));
    }

    private void duplicate() {
        Alerts.askText("Duplicate budget", "Name for the copy of \"" + current + "\":", current + " copy")
                .ifPresent(name -> openBudget.accept(library.duplicate(current, name)));
    }

    private void rename() {
        Alerts.askText("Rename budget", "New name for \"" + current + "\":", current)
                .ifPresent(name -> openBudget.accept(library.rename(current, name)));
    }

    private void delete() {
        if (library.isShared(current)) {
            if (Alerts.confirm("Remove \"" + current + "\" from your list? The shared file stays where it is, "
                    + "so the other computer keeps using it, and you can open it again any time.")) {
                library.delete(current);
                openBudget.accept(library.names().get(0));
            }
            return;
        }
        if (Alerts.confirm("Delete the budget \"" + current + "\"? Its file is moved to the Deleted folder in "
                + library.folder() + ", in case you need it back.")) {
            library.delete(current);
            openBudget.accept(library.names().get(0));
        }
    }

    /**
     * Moves the open budget into a folder you pick (one that syncs, like iCloud Drive or Dropbox),
     * then explains how to open it on the other computer.
     */
    private void share() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Pick a synced folder to keep \"" + current + "\" in");
        File iCloud = Path.of(System.getProperty("user.home"), "Library", "Mobile Documents", "com~apple~CloudDocs").toFile();
        if (iCloud.isDirectory()) {
            chooser.setInitialDirectory(iCloud); // start in iCloud Drive if this Mac has it
        }
        File folder = chooser.showDialog(getScene().getWindow());
        if (folder == null) {
            return;
        }
        library.share(current, folder.toPath());
        openBudget.accept(current); // reopen it from its new home
        Path file = library.sharedLocation(current).orElseThrow();
        Alerts.info("\"" + current + "\" is now shared. It lives in:\n" + file + "\n\n"
                + "On the other computer:\n"
                + "1. Give them access to that folder (in Finder: right-click it → Share, or share it in Dropbox).\n"
                + "2. In their runwayd: Budget → Open a shared budget…, and pick " + file.getFileName() + ".\n\n"
                + "From then on you both see the same budget. Changes from the other computer show up "
                + "here within a few seconds of syncing.");
    }

    /**
     * Opens the demo budget (a made-up household to try things in), making it first if needed.
     * Your own budgets aren't touched.
     */
    private void openDemo() {
        String existing = library.names().stream().filter(library::isDemo).findFirst().orElse(null);
        openBudget.accept(existing != null ? existing : library.addDemo(LocalDate.now()));
    }

    /** Adds a shared budget (a file in a synced folder someone shared with you) and opens it. */
    private void openShared() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open a shared budget");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("runwayd budgets", "*.json"));
        File iCloud = Path.of(System.getProperty("user.home"), "Library", "Mobile Documents", "com~apple~CloudDocs").toFile();
        if (iCloud.isDirectory()) {
            chooser.setInitialDirectory(iCloud);
        }
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            openBudget.accept(library.openShared(file.toPath()));
        }
    }

    /** Saves a copy of the open budget, on the Desktop by default, ready to AirDrop. */
    private void export() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export budget");
        chooser.setInitialFileName(current + BudgetLibrary.EXPORT_ENDING);
        File desktop = Path.of(System.getProperty("user.home"), "Desktop").toFile();
        if (desktop.isDirectory()) {
            chooser.setInitialDirectory(desktop);
        }
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            library.exportTo(current, file.toPath());
            Alerts.info("Saved a copy of \"" + current + "\" as " + file.getName() + ".\n\n"
                    + "Send that file (AirDrop, Messages or email). In their runwayd they choose "
                    + "Budget → Import. It holds everything in this budget: accounts, balances, bills and "
                    + "the paycheck split. After that, their copy and yours are separate.");
        }
    }

    /** Adds a budget someone sent you and opens it. */
    private void importBudget() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import budget");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("runwayd budgets", "*.json"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            String name = library.importFrom(file.toPath());
            openBudget.accept(name);
            Alerts.info("Added the budget \"" + name + "\". Your other budgets weren't changed.");
        }
    }
}
