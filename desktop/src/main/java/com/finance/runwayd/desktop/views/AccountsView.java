package com.finance.runwayd.desktop.views;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.desktop.ui.Alerts;
import com.finance.runwayd.desktop.Refreshable;
import com.finance.runwayd.desktop.ui.Tables;
import com.finance.runwayd.desktop.dialogs.AccountDialog;
import com.finance.runwayd.desktop.dialogs.BalanceDialog;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Your cash accounts: checking and savings. Credit cards and loans live on the Debt tab.
 *
 * <p>The most-used button here is "Update balance": type in what your bank shows
 * today, and the whole forecast re-anchors to the real number.
 */
public final class AccountsView extends BorderPane implements Refreshable {

    private static final List<AccountType> CASH_TYPES = List.of(AccountType.CHECKING, AccountType.SAVINGS);

    private final FinanceService service;
    private final TableView<Account> table = Tables.create("No accounts yet. Click \"Add\" to enter your checking account.");

    public AccountsView(FinanceService service) {
        this.service = service;
        setPadding(new Insets(16));

        table.getColumns().add(Tables.text("Name", Account::name));
        table.getColumns().add(Tables.link("Website", Account::website));
        table.getColumns().add(Tables.text("Type", account -> account.type().label()));
        table.getColumns().add(Tables.money("Balance", Account::balance));
        table.getColumns().add(Tables.date("As of", Account::balanceDate));

        // Double-click a row to update its balance (the most common edit).
        table.setRowFactory(t -> {
            TableRow<Account> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    updateBalance(row.getItem());
                }
            });
            return row;
        });

        Label help = new Label("Tip: update balances from your bank every week or so. "
                + "Credit cards and loans are on the Debt tab.");
        help.getStyleClass().add("hint");
        help.setWrapText(true);

        setTop(buildToolbar());
        setCenter(table);
        setBottom(new VBox(help));
        BorderPane.setMargin(getBottom(), new Insets(12, 0, 0, 0));
    }

    private HBox buildToolbar() {
        Button add = new Button("Add");
        Button updateBalance = new Button("Update balance");
        Button edit = new Button("Edit");
        Button delete = new Button("Delete");
        add.getStyleClass().add("primary");

        for (Button needsSelection : new Button[] {updateBalance, edit, delete}) {
            needsSelection.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        }

        add.setOnAction(event -> add());
        updateBalance.setOnAction(event -> updateBalance(selected()));
        edit.setOnAction(event -> edit(selected()));
        delete.setOnAction(event -> delete(selected()));

        HBox toolbar = new HBox(8, add, updateBalance, edit, delete);
        toolbar.setPadding(new Insets(0, 0, 12, 0));
        return toolbar;
    }

    @Override
    public void refresh() {
        table.getItems().setAll(service.cashAccounts());
    }

    private Account selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    private void add() {
        new AccountDialog(null, CASH_TYPES).showAndWait().ifPresent(account -> {
            service.addAccount(account);
            refresh();
        });
    }

    private void edit(Account account) {
        new AccountDialog(account, CASH_TYPES).showAndWait().ifPresent(changed -> {
            service.updateAccount(changed);
            refresh();
        });
    }

    private void updateBalance(Account account) {
        new BalanceDialog(account).showAndWait().ifPresent(changed -> {
            service.updateBalance(changed.id(), changed.balance(), changed.balanceDate());
            refresh();
        });
    }

    private void delete(Account account) {
        // FinanceService refuses if a bill still uses this account; App shows its message as a pop-up.
        if (Alerts.confirm("Delete \"" + account.name() + "\"?")) {
            service.deleteAccount(account.id());
            refresh();
        }
    }
}
