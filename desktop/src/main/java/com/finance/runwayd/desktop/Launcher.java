package com.finance.runwayd.desktop;

import javafx.application.Application;

/**
 * The program's entry point.
 *
 * <p>Why not just put {@code main} in {@link App}? When the main class itself
 * extends JavaFX's Application, Java checks for JavaFX before our code runs and
 * can fail with "JavaFX runtime components are missing". Starting from a plain
 * class avoids that. It's a standard JavaFX workaround.
 */
public final class Launcher {

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
