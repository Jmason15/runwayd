package com.finance.runwayd.desktop;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.desktop.views.BillsView;
import com.finance.runwayd.desktop.views.BudgetView;
import com.finance.runwayd.desktop.views.DebtView;
import com.finance.runwayd.desktop.views.ForecastView;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;

import java.nio.file.Path;

/**
 * The window's layout: five tabs, plus a status line showing where your data is saved.
 *
 * <pre>
 *   [ RunWay ] [ Bills &amp; Income ] [ Debt ] [ Budget ]
 *   ...the selected screen...
 *   Data saved in: ~/runwayd/budgets/My budget.json
 * </pre>
 */
public final class MainWindow extends BorderPane {

    private final FinanceService service;
    private final TabPane tabs;
    private final Label status = new Label();
    private final String savedIn;
    private final boolean demo;
    // A one-line note above the tabs: "you're in the demo", or how to start on an empty budget.
    private final Label banner = new Label();

    /**
     * @param dataFile where this budget is saved, shown in the status line
     * @param shared   true for a budget in a shared folder (the status line says so)
     * @param demo     true for the demo budget (a banner says it's made up)
     */
    public MainWindow(FinanceService service, Path dataFile, boolean shared, boolean demo) {
        this.service = service;
        this.demo = demo;
        this.savedIn = (shared ? "Shared budget, saved in: " : "Data saved in: ") + dataFile;
        tabs = new TabPane(
                tab("RunWay", new ForecastView(service)),
                tab("Bills & Income", new BillsView(service)),
                tab("Debt", new DebtView(service)),
                tab("Budget", new BudgetView(service)));

        // Reload a screen every time you switch to it, so it shows the latest changes.
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            refresh(newTab);
            showBanner(); // e.g. hide "this budget is empty" once you've added an account
        });
        refresh(tabs.getSelectionModel().getSelectedItem());

        status.setText(savedIn);
        status.getStyleClass().add("status-bar");
        status.setPadding(new Insets(4, 12, 4, 12));

        banner.getStyleClass().add("banner");
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setWrapText(true);
        showBanner();

        setTop(banner);
        setCenter(tabs);
        setBottom(status);
    }

    /**
     * The note above the tabs. In the demo it says the numbers are made up; in a budget with no
     * accounts yet it says where to start. Otherwise it's hidden.
     */
    private void showBanner() {
        String text = null;
        if (demo) {
            text = "You're looking at the demo budget: a made-up household to try things out. Nothing here is "
                    + "real, and nothing you change here touches your own budgets. Switch back with \"Budget:\" "
                    + "above, or remove it with Budget → Delete.";
        } else if (service.accounts().isEmpty()) {
            text = "This budget is empty. Start on the RunWay tab: click \"Add account\" for your checking account and today's "
                    + "balance, then your bills on Bills & Income. Want to look around first? "
                    + "Budget → Open the demo budget.";
        }
        banner.setText(text == null ? "" : text);
        banner.setVisible(text != null);
        banner.setManaged(text != null);
    }

    /** Reloads the screen you're looking at, e.g. after changes arrive from another computer. */
    public void refreshCurrentTab() {
        refresh(tabs.getSelectionModel().getSelectedItem());
        showBanner();
    }

    /** Adds a note after "saved in", e.g. "updated with changes from another computer at 6:42 PM". */
    public void showStatusNote(String note) {
        status.setText(savedIn + "   ·   " + note);
    }

    private static <V extends Node & Refreshable> Tab tab(String title, V view) {
        Tab tab = new Tab(title, view);
        tab.setClosable(false);
        tab.setUserData(view); // remembered so refresh() can find the screen again
        return tab;
    }

    private static void refresh(Tab tab) {
        if (tab != null && tab.getUserData() instanceof Refreshable view) {
            view.refresh();
        }
    }
}
