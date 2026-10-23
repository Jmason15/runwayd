package com.finance.runwayd.desktop.dialogs;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.desktop.ui.Format;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Add or edit an account: checking, savings, credit card or loan.
 *
 * <p>For debts, "Balance" means how much you owe, entered as a positive number,
 * and there's an optional yearly interest rate. Credit cards also have the two numbers
 * that decide their minimum payment: interest + a % of the balance, and a dollar floor.
 */
public final class AccountDialog extends FormDialog<Account> {

    private final int id;

    private final TextField nameField = new TextField();
    private final ComboBox<AccountType> typeBox = new ComboBox<>();
    private final TextField balanceField = new TextField();
    private final DatePicker asOfPicker = new DatePicker(LocalDate.now());
    private final TextField interestField = new TextField();
    private final TextField websiteField = new TextField();
    private final TextField minimumPercentField = new TextField();
    private final TextField minimumFloorField = new TextField();

    private final FormRow balanceRow;
    private final FormRow interestRow;
    private final FormRow minimumPercentRow;
    private final FormRow minimumFloorRow;

    /**
     * @param existing the account to edit, or null to add a new one
     * @param types    the account types to offer, e.g. just checking and savings on the RunWay tab
     */
    public AccountDialog(Account existing, List<AccountType> types) {
        super(existing == null ? "Add account" : "Edit " + existing.name());
        this.id = existing == null ? 0 : existing.id();

        typeBox.getItems().setAll(types);
        nameField.setPromptText("e.g. Everyday Checking");
        interestField.setPromptText("e.g. 24.99 (leave blank to ignore interest)");
        websiteField.setPromptText("optional, e.g. mybank.com");

        addRow("Name", nameField);
        addRow("Type", typeBox);
        balanceRow = addRow("Balance", balanceField);
        addRow("As of", asOfPicker);
        interestRow = addRow("Interest rate % / year", interestField);
        minimumPercentRow = addRow("Minimum: interest + % of balance", minimumPercentField);
        minimumFloorRow = addRow("Minimum is at least $", minimumFloorField);
        addRow("Website", websiteField);

        if (existing != null) {
            nameField.setText(existing.name());
            typeBox.setValue(existing.type());
            balanceField.setText(Format.moneyForEditing(existing.balance()));
            asOfPicker.setValue(existing.balanceDate());
            websiteField.setText(existing.website());
            minimumPercentField.setText(existing.minimumPaymentPercent().stripTrailingZeros().toPlainString());
            minimumFloorField.setText(Format.moneyForEditing(existing.minimumPaymentFloor()));
            if (existing.interestRatePercent().signum() > 0) {
                interestField.setText(existing.interestRatePercent().toPlainString());
            }
        } else {
            typeBox.setValue(types.get(0));
            minimumPercentField.setText(Account.DEFAULT_MINIMUM_PERCENT.toPlainString());
            minimumFloorField.setText(Format.moneyForEditing(Account.DEFAULT_MINIMUM_FLOOR));
        }

        // Debt accounts word "Balance" differently and get the interest field; cards also get the minimum rule.
        typeBox.valueProperty().addListener((obs, oldType, newType) -> updateForType());
        updateForType();
    }

    private void updateForType() {
        boolean debt = typeBox.getValue() != null && typeBox.getValue().isDebt();
        balanceRow.setLabel(debt ? "Amount owed" : "Balance");
        interestRow.setShown(debt);
        boolean card = typeBox.getValue() == AccountType.CREDIT_CARD;
        minimumPercentRow.setShown(card);
        minimumFloorRow.setShown(card);
    }

    @Override
    protected Account buildResult() {
        AccountType type = required(typeBox.getValue(), "Pick an account type.");
        BigDecimal balance = Format.parseMoney(balanceField.getText(), balanceRow.label().getText());
        LocalDate asOf = required(dateFrom(asOfPicker), "Pick the date this balance is from.");

        BigDecimal interest = BigDecimal.ZERO;
        if (type.isDebt() && !interestField.getText().isBlank()) {
            interest = Format.parseNumber(interestField.getText(), "Interest rate");
        }
        // Only cards use the minimum rule; other types keep the defaults.
        BigDecimal minimumPercent = null;
        BigDecimal minimumFloor = null;
        if (type == AccountType.CREDIT_CARD) {
            minimumPercent = Format.parseNumber(minimumPercentField.getText(), "Minimum %");
            minimumFloor = Format.parseMoney(minimumFloorField.getText(), "Minimum floor");
        }
        return new Account(id, nameField.getText(), type, balance, asOf, interest, websiteField.getText(),
                minimumPercent, minimumFloor);
    }
}
