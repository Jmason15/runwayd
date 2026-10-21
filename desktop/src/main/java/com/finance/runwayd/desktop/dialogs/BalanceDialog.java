package com.finance.runwayd.desktop.dialogs;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.desktop.ui.Format;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The quick "what does my bank say right now?" update.
 *
 * <p>This is the habit that keeps the forecast accurate: every week or so, type in
 * the real balance. The date defaults to today.
 */
public final class BalanceDialog extends FormDialog<Account> {

    private final Account account;
    private final TextField balanceField = new TextField();
    private final DatePicker asOfPicker = new DatePicker(LocalDate.now());

    public BalanceDialog(Account account) {
        super("Update balance: " + account.name());
        this.account = account;

        balanceField.setText(Format.moneyForEditing(account.balance()));
        addRow(account.type().isDebt() ? "Amount owed now" : "Balance now", balanceField);
        addRow("As of", asOfPicker);

        // Put the cursor in the amount box with the old number selected, ready to type over.
        setOnShown(event -> {
            balanceField.requestFocus();
            balanceField.selectAll();
        });
    }

    @Override
    protected Account buildResult() {
        BigDecimal balance = Format.parseMoney(balanceField.getText(), "Balance");
        LocalDate asOf = required(dateFrom(asOfPicker), "Pick the date this balance is from.");
        return account.withBalance(balance, asOf);
    }
}
