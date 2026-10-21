package com.finance.runwayd.desktop.dialogs;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.Category;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.PaymentRule;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.core.model.ScheduledItem.Kind;
import com.finance.runwayd.desktop.ui.Format;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Add or edit a bill, paycheck, transfer or debt payment.
 *
 * <p>Picking the type shows only the account boxes that make sense:
 * <ul>
 *   <li>Income: just "Paid into".</li>
 *   <li>Bill: just "Paid from" (pick a credit card here to charge it to the card).</li>
 *   <li>Transfer / payment: both. Pick a credit card or loan as "Paid into" to pay down a debt.</li>
 * </ul>
 *
 * <p>For a transfer or payment, "Payment" picks a fixed amount or the card's minimum
 * (which changes with the balance).
 */
public final class ItemDialog extends FormDialog<ScheduledItem> {

    private final int id;
    // Not shown in the form, but kept so editing a bill doesn't un-pay it.
    private final LocalDate paidThrough;

    private final TextField nameField = new TextField();
    private final ComboBox<Kind> kindBox = new ComboBox<>();
    private final ComboBox<Category> categoryBox = new ComboBox<>();
    private final ComboBox<PaymentRule> ruleBox = new ComboBox<>();
    private final TextField amountField = new TextField();
    private final ComboBox<Frequency> frequencyBox = new ComboBox<>();
    private final DatePicker firstDatePicker = new DatePicker(LocalDate.now());
    private final DatePicker endDatePicker = new DatePicker();
    private final ComboBox<Account> fromBox = new ComboBox<>();
    private final ComboBox<Account> toBox = new ComboBox<>();

    private final FormRow ruleRow;
    private final FormRow amountRow;
    private final FormRow fromRow;
    private final FormRow toRow;
    private final FormRow hintRow;

    /**
     * @param existing the item to edit, or null to add a new one
     * @param accounts every account, for the from/to drop-downs
     */
    public ItemDialog(ScheduledItem existing, List<Account> accounts) {
        super(existing == null ? "Add bill or income" : "Edit " + existing.name());
        this.id = existing == null ? 0 : existing.id();
        this.paidThrough = existing == null ? null : existing.paidThrough();

        kindBox.getItems().setAll(Kind.values());
        categoryBox.getItems().setAll(Category.values());
        ruleBox.getItems().setAll(PaymentRule.values());
        ruleBox.setValue(PaymentRule.FIXED);
        frequencyBox.getItems().setAll(Frequency.values());
        fromBox.getItems().setAll(accounts);
        toBox.getItems().setAll(accounts);
        nameField.setPromptText("e.g. Mortgage");
        amountField.setPromptText("e.g. 1450.00");
        endDatePicker.setPromptText("optional");

        Label hint = new Label("Tip: pick a credit card or loan here to pay down that debt.");
        hint.getStyleClass().add("hint");

        addRow("Name", nameField);
        addRow("Type", kindBox);
        addRow("Category", categoryBox);
        ruleRow = addRow("Payment", ruleBox);
        amountRow = addRow("Amount", amountField);
        addRow("How often", frequencyBox);
        addRow("First date", firstDatePicker);
        addRow("Ends (optional)", endDatePicker);
        fromRow = addRow("Paid from", fromBox);
        toRow = addRow("Paid into", toBox);
        hintRow = addRow("", hint);

        if (existing != null) {
            fillFrom(existing, accounts);
        } else {
            kindBox.setValue(Kind.BILL);
            categoryBox.setValue(Category.OTHER);
            frequencyBox.setValue(Frequency.MONTHLY);
            // Most bills come out of checking, which accounts() lists first.
            if (!accounts.isEmpty()) {
                fromBox.setValue(accounts.get(0));
                toBox.setValue(accounts.get(0));
            }
        }

        kindBox.valueProperty().addListener((obs, oldKind, newKind) -> {
            suggestCategory(newKind);
            updateRows();
        });
        ruleBox.valueProperty().addListener((obs, oldRule, newRule) -> updateRows());
        updateRows();
    }

    private void fillFrom(ScheduledItem item, List<Account> accounts) {
        nameField.setText(item.name());
        kindBox.setValue(item.kind());
        categoryBox.setValue(item.category());
        ruleBox.setValue(item.paymentRule());
        amountField.setText(Format.moneyForEditing(item.amount()));
        frequencyBox.setValue(item.frequency());
        firstDatePicker.setValue(item.firstDate());
        endDatePicker.setValue(item.endDate());
        fromBox.setValue(findAccount(accounts, item.fromAccountId()));
        toBox.setValue(findAccount(accounts, item.toAccountId()));
    }

    /**
     * Switching to Income picks the Income category, and switching away from it
     * goes back to Other. A category you chose yourself is left alone.
     */
    private void suggestCategory(Kind kind) {
        Category current = categoryBox.getValue();
        if (kind == Kind.INCOME && current == Category.OTHER) {
            categoryBox.setValue(Category.INCOME);
        } else if (kind != Kind.INCOME && current == Category.INCOME) {
            categoryBox.setValue(Category.OTHER);
        }
    }

    /**
     * Show only the boxes that apply: the account boxes depend on the type, and a
     * card-minimum payment has no fixed amount to type in.
     */
    private void updateRows() {
        Kind kind = kindBox.getValue();
        boolean transfer = kind == Kind.TRANSFER;
        fromRow.setShown(kind != Kind.INCOME);
        toRow.setShown(kind != Kind.BILL);
        hintRow.setShown(transfer);
        ruleRow.setShown(transfer);
        amountRow.setShown(!transfer || ruleBox.getValue() == PaymentRule.FIXED);
    }

    @Override
    protected ScheduledItem buildResult() {
        Kind kind = required(kindBox.getValue(), "Pick a type.");
        Category category = required(categoryBox.getValue(), "Pick a category.");
        Frequency frequency = required(frequencyBox.getValue(), "Pick how often it happens.");
        LocalDate firstDate = required(dateFrom(firstDatePicker), "Pick the first date.");
        LocalDate endDate = dateFrom(endDatePicker);

        // Only keep the accounts that this type uses; hidden boxes are ignored.
        Integer fromId = kind == Kind.INCOME ? null : required(fromBox.getValue(), "Pick where the money comes from.").id();
        Integer toId = kind == Kind.BILL ? null : required(toBox.getValue(), "Pick where the money goes.").id();

        // Only payments into an account can use the card minimum.
        PaymentRule rule = kind == Kind.TRANSFER ? ruleBox.getValue() : PaymentRule.FIXED;
        if (rule == PaymentRule.CARD_MINIMUM && !toBox.getValue().type().isDebt()) {
            throw new IllegalArgumentException("\"Card minimum\" only works when paying into a credit card or loan.");
        }
        BigDecimal amount = rule == PaymentRule.FIXED ? Format.parseMoney(amountField.getText(), "Amount") : null;
        return new ScheduledItem(id, nameField.getText(), category, amount, rule, frequency, firstDate, endDate,
                fromId, toId, paidThrough);
    }

    private static Account findAccount(List<Account> accounts, Integer id) {
        return accounts.stream()
                .filter(account -> Objects.equals(account.id(), id))
                .findFirst()
                .orElse(null);
    }
}
