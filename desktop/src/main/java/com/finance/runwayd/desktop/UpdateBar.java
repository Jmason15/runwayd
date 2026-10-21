package com.finance.runwayd.desktop;

import com.finance.runwayd.core.update.GitHubReleases;
import com.finance.runwayd.core.update.GitHubReleases.Release;
import com.finance.runwayd.core.update.UpdateInstaller;
import com.finance.runwayd.core.update.Version;
import com.finance.runwayd.desktop.ui.Alerts;
import com.finance.runwayd.desktop.ui.Browser;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Keeps the app up to date from GitHub Releases, and asks before updating:
 * <pre>
 *   runwayd 1.2.0 is ready.                     [What's new]  [Later]  [Restart to update]
 * </pre>
 * At start-up and every few hours it checks for a newer release. If there is one, it downloads
 * it and checks the signature in the background (see UpdateInstaller in core), and only then
 * shows this bar. "Restart to update" quits, swaps in the new app and reopens it; your data is
 * outside the app, so it isn't touched. "Later" hides the bar until the app is next opened.
 *
 * <p>It only runs in the installed Mac app, and only if this build knows where updates come from
 * (see {@link AppInfo#updatesConfigured()}).
 */
public final class UpdateBar extends HBox {

    private static final Duration CHECK_EVERY = Duration.hours(6);

    private final Label message = new Label();
    private Release ready;        // the release that's downloaded and checked, or null
    private Path readyApp;        // where its app was unpacked
    private Version dismissed;    // "Later" was clicked for this version
    private boolean checking;

    public UpdateBar() {
        message.getStyleClass().add("heading");
        Button whatsNew = new Button("What's new");
        whatsNew.setOnAction(event -> {
            if (ready != null) {
                Browser.open(ready.page().toString());
            }
        });
        Button later = new Button("Later");
        later.setOnAction(event -> {
            dismissed = ready == null ? null : ready.version();
            show(false);
        });
        Button restart = new Button("Restart to update");
        restart.getStyleClass().add("primary");
        restart.setOnAction(event -> restartIntoUpdate());

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        getChildren().addAll(message, gap, whatsNew, later, restart);
        getStyleClass().add("banner");
        setSpacing(8);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(6, 16, 6, 16));
        show(false);
    }

    /** True if this copy of the app can update itself (installed, and set up with a repo and Team ID). */
    public static boolean canUpdate() {
        return AppInfo.updatesConfigured() && UpdateInstaller.installedApp().isPresent();
    }

    /** Checks now and then every few hours, quietly; the bar appears when an update is ready. */
    public void startChecking() {
        if (!canUpdate()) {
            return;
        }
        checkNow(false);
        Timeline timer = new Timeline(new KeyFrame(CHECK_EVERY, event -> checkNow(false)));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
    }

    /**
     * Looks for a newer release in the background. When {@code youAsked} (Help → Check for Updates),
     * it also says "you're up to date", or what went wrong; otherwise problems (e.g. being offline)
     * are just tried again next time.
     */
    public void checkNow(boolean youAsked) {
        if (!canUpdate()) {
            if (youAsked) {
                Alerts.info("This copy of runwayd doesn't update itself: it's running from the developer tools, "
                        + "or it was built without a GitHub repo and Apple Team ID. You have version "
                        + AppInfo.version() + ".");
            }
            return;
        }
        if (checking) {
            return;
        }
        checking = true;
        Thread worker = new Thread(() -> {
            try {
                Optional<Release> latest = new GitHubReleases(AppInfo.updateRepo()).latest()
                        .filter(release -> release.version().isNewerThan(AppInfo.version()));
                if (latest.isEmpty()) {
                    Platform.runLater(() -> {
                        if (youAsked) {
                            Alerts.info("You're up to date: runwayd " + AppInfo.version() + " is the latest version.");
                        }
                    });
                    return;
                }
                Release release = latest.get();
                if (ready == null || !ready.version().equals(release.version())) {
                    Path app = new UpdateInstaller(AppInfo.teamId()).download(release.appZip());
                    Platform.runLater(() -> {
                        ready = release;
                        readyApp = app;
                    });
                }
                Platform.runLater(() -> {
                    if (youAsked || !release.version().equals(dismissed)) {
                        message.setText("runwayd " + release.version() + " is ready.");
                        show(true);
                    }
                });
            } catch (RuntimeException e) {
                if (youAsked) {
                    Platform.runLater(() -> Alerts.error(e.getMessage()));
                } else {
                    System.err.println("Update check failed: " + e.getMessage()); // try again next time
                }
            } finally {
                Platform.runLater(() -> checking = false);
            }
        }, "update check");
        worker.setDaemon(true);
        worker.start();
    }

    /** Quits, and the helper swaps in the new app and reopens it. Everything you entered is already saved. */
    private void restartIntoUpdate() {
        Path installed = UpdateInstaller.installedApp().orElse(null);
        if (installed == null || readyApp == null) {
            return;
        }
        new UpdateInstaller(AppInfo.teamId()).installAndRelaunch(installed, readyApp);
        Platform.exit();
        System.exit(0);
    }

    private void show(boolean visible) {
        setVisible(visible);
        setManaged(visible);
    }
}
