package com.finance.runwayd.desktop.ui;

import javafx.scene.Scene;
import javafx.scene.control.DialogPane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Light or dark ("Night runway"). Every window and pop-up gets its stylesheets from here:
 * {@code app.css} always, plus {@code dark.css} on top when dark mode is on.
 *
 * <p>Pop-ups are separate windows, so they don't inherit the main window's stylesheets;
 * {@link Alerts} and the edit forms call {@link #apply(DialogPane)} when they're made.
 * The main window is remembered, so switching modes restyles it straight away.
 */
public final class Theme {

    private static final String APP_CSS = stylesheet("app.css");
    private static final String DARK_CSS = stylesheet("dark.css");

    private static boolean dark = true;
    private static final List<Scene> liveScenes = new ArrayList<>();

    private Theme() {
    }

    public static boolean isDark() {
        return dark;
    }

    /** Switches between light and dark, restyling the open window right away. */
    public static void setDark(boolean on) {
        dark = on;
        liveScenes.forEach(scene -> scene.getStylesheets().setAll(stylesheets()));
    }

    /** Styles a window, and keeps it in step when the mode changes later. */
    public static void apply(Scene scene) {
        scene.getStylesheets().setAll(stylesheets());
        if (!liveScenes.contains(scene)) {
            liveScenes.add(scene);
        }
    }

    /** Styles a pop-up (an alert or an edit form) to match the current mode. */
    public static void apply(DialogPane pane) {
        pane.getStylesheets().setAll(stylesheets());
    }

    private static List<String> stylesheets() {
        return dark ? List.of(APP_CSS, DARK_CSS) : List.of(APP_CSS);
    }

    /** The full address of a stylesheet stored next to the desktop classes. */
    private static String stylesheet(String name) {
        return Objects.requireNonNull(Theme.class.getResource("/com/finance/runwayd/desktop/" + name),
                name + " is missing").toExternalForm();
    }
}
