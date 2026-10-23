package com.finance.runwayd.desktop;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.storage.BudgetLibrary;
import com.finance.runwayd.core.storage.ChangedElsewhereException;
import com.finance.runwayd.core.storage.JsonDataStore;
import com.finance.runwayd.desktop.ui.Alerts;
import com.finance.runwayd.desktop.ui.Browser;
import com.finance.runwayd.desktop.ui.Theme;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Starts the app: connects the backend (core) to the window (desktop).
 *
 * <p>This is the only place the two halves get wired together:
 * <ol>
 *   <li>Pick the budget to open (the last one used; see BudgetLibrary) and its data store.</li>
 *   <li>Create the FinanceService on top of it.</li>
 *   <li>Hand the service to the main window, under the budget switcher.</li>
 * </ol>
 * Switching budgets repeats steps 1 to 3 for the other budget, in the same window.
 */
public final class App extends Application {

    private final BudgetLibrary library = BudgetLibrary.inHomeFolder();
    // Holds the switcher on top and the tabs below. Its content is swapped when you change budgets.
    private final BorderPane root = new BorderPane();
    // The Mac menu bar (View → Dark Mode). Made once at start-up and kept above every budget.
    private MenuBar menuBar;
    // "runwayd 1.2.0 is ready. [Restart to update]": kept above every budget.
    private UpdateBar updateBar;
    // The open budget and the window showing it; replaced when you switch budgets.
    private FinanceService service;
    private MainWindow window;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        // Any error a button click causes (e.g. "can't delete, account in use")
        // ends up here and is shown as a pop-up instead of being silently lost.
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
            Alerts.error(rootMessage(error));
            if (causedBy(error, ChangedElsewhereException.class) && window != null) {
                // The other computer's version was loaded in place of the change that couldn't be saved.
                window.refreshCurrentTab();
            }
        });
        // Lets website links on the Debt and Accounts tabs open in your normal browser.
        Browser.init(getHostServices());
        // Dark ("Night runway") unless you've switched it off before on this computer.
        Theme.setDark(library.darkMode().orElse(true));
        updateBar = new UpdateBar();
        menuBar = buildMenuBar();

        this.stage = stage;
        try {
            openBudget(library.startingBudget());
        } catch (RuntimeException e) {
            Alerts.error(rootMessage(e));
            Platform.exit();
            return;
        }

        Scene scene = new Scene(root, 1100, 880);
        Theme.apply(scene);

        stage.setScene(scene);
        stage.show();
        watchForChangesElsewhere();
        updateBar.startChecking(); // only does anything in the installed, signed app
    }

    /** Which version this is, and where it comes from. */
    private void showAbout() {
        String source = AppInfo.updateRepo().isEmpty() ? "" : "\n\nReleases: github.com/" + AppInfo.updateRepo();
        Alerts.info("runwayd " + AppInfo.version() + "\n\n"
                + "How far does your money fly? See every account's balance as bills and paychecks come and go, "
                + "when each debt is paid off, and how much of each paycheck is safe to split." + source);
    }

    /** How often to look for changes saved on another computer (for a shared budget). */
    private static final Duration CHECK_EVERY = Duration.seconds(3);

    /**
     * Every few seconds: if the open budget's file was saved somewhere else (a shared budget your
     * partner changed on their Mac), load it and refresh the screen. For an unshared budget the file only
     * changes when this app saves it, so nothing happens.
     */
    private void watchForChangesElsewhere() {
        Timeline timeline = new Timeline(new KeyFrame(CHECK_EVERY, event -> {
            if (service != null && service.reloadIfChangedElsewhere()) {
                window.refreshCurrentTab();
                window.showStatusNote("updated with changes from another computer at "
                        + LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a")));
            }
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    /** True if {@code error}, or anything that caused it, is a {@code type}. */
    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (type.isInstance(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shows budget {@code name}: a fresh FinanceService on its file, with the switcher on top.
     * Called at start-up and whenever you pick, create, rename or import a budget.
     */
    private void openBudget(String name) {
        JsonDataStore store = library.open(name);
        service = new FinanceService(store);
        window = new MainWindow(service, store.file(), library.isShared(name), library.isDemo(name));
        root.setTop(new VBox(menuBar, updateBar, new BudgetSwitcher(library, name, this::openBudget)));
        root.setCenter(window);
        stage.setTitle("runwayd: " + name);
    }

    /**
     * The Mac menu bar at the top of the screen: View → Dark Mode (⌘⇧D), saved in settings.json so the
     * app opens the same way next time, and Help → About runwayd / Check for Updates.
     */
    private MenuBar buildMenuBar() {
        CheckMenuItem darkMode = new CheckMenuItem("Dark Mode");
        darkMode.setSelected(Theme.isDark());
        darkMode.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN));
        darkMode.setOnAction(event -> {
            Theme.setDark(darkMode.isSelected());
            library.setDarkMode(darkMode.isSelected());
        });
        MenuItem about = new MenuItem("About runwayd");
        about.setOnAction(event -> showAbout());
        MenuItem checkForUpdates = new MenuItem("Check for Updates…");
        checkForUpdates.setOnAction(event -> updateBar.checkNow(true));

        MenuBar bar = new MenuBar(new Menu("View", null, darkMode), new Menu("Help", null, about, checkForUpdates));
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    /** The most useful message in an exception chain: the first one that has text. */
    private static String rootMessage(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e.getMessage() != null && !e.getMessage().isBlank()) {
                return e.getMessage();
            }
        }
        return error.toString();
    }
}
