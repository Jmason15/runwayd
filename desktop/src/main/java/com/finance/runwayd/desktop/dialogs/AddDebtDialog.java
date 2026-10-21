package com.finance.runwayd.desktop.dialogs;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.Category;
import com.finance.runwayd.core.model.PaymentRule;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.desktop.ui.Format;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Add a credit card or loan, and how it gets paid, in one form.
 *
 * <p>The "Payment" choice:
 * <ul>
 *   <li><b>Set up a monthly payment</b> (the default): a card pays its minimum; for a
 *       loan, type "Payments left" and the amount is worked out for you.</li>
 *   <li><b>An existing bill</b>, e.g. "Store Card". It becomes the payment toward this
 *       debt, so you don't enter it twice.</li>
 *   <li><b>No payment for now</b>: just track the balance.</li>
 * </ul>
 */
public final class AddDebtDialog extends FormDialog<AddDebtDialog.NewDebt> {

    /**
     * What the form produces. The caller saves the debt first, then sets up its payment.
     *
     * @param debt       the new credit card or loan (not saved yet)
     * @param rule       card minimum or fixed amount, also applied to a linked bill
     * @param billToLink an existing bill that becomes the payment, or null
     * @param newPayment a brand-new payment to create, or null
     */
    public record NewDebt(Account debt, PaymentRule rule, ScheduledItem billToLink, PaymentPlan newPayment) {
    }

    /** One entry in the Payment drop-down. {@code bill} is null for the two special choices. */
    private record PaymentChoice(String label, ScheduledItem bill) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final PaymentChoice SET_UP_PAYMENT = new PaymentChoice("Set up a monthly payment", null);
    private static final PaymentChoice NO_PAYMENT = new PaymentChoice("No payment for now", null);

    private final TextField nameField = new TextField();
    private final ComboBox<AccountType> typeBox = new ComboBox<>();
    private final TextField owedField = new TextField();
    private final TextField interestField = new TextField();
    private final TextField websiteField = new TextField();
    private final ComboBox<PaymentChoice> paymentBox = new ComboBox<>();
    private final PaymentFields paymentFields;

    /**
     * @param bills        bills that could become this debt's payment
     * @param cashAccounts accounts a new payment could come out of
     * @param formula      works out a loan's monthly payment (FinanceService::loanPayment)
     */
    public AddDebtDialog(List<ScheduledItem> bills, List<Account> cashAccounts, LoanPaymentFormula formula) {
        super("Add debt");

        typeBox.getItems().setAll(AccountType.CREDIT_CARD, AccountType.LOAN);
        typeBox.setValue(AccountType.CREDIT_CARD);
        nameField.setPromptText("e.g. Store Card");
        owedField.setPromptText("from your latest statement");
        interestField.setPromptText("optional, e.g. 24.99");
        websiteField.setPromptText("optional, e.g. mybank.com");
        paymentBox.getItems().addAll(paymentChoices(bills));
        paymentBox.setValue(SET_UP_PAYMENT);

        // The loan calculation reads what's owed and the rate straight from this form.
        paymentFields = new PaymentFields(cashAccounts, formula,
                () -> numberOrNull(owedField.getText()),
                () -> interestField.getText().isBlank() ? BigDecimal.ZERO : numberOrNull(interestField.getText()));

        addRow("Name", nameField);
        addRow("Type", typeBox);
        addRow("Amount owed today", owedField);
        addRow("Interest rate % / year", interestField);
        addRow("Website", websiteField);
        addRow("Payment", paymentBox);
        paymentFields.addTo(this);

        typeBox.valueProperty().addListener((obs, oldType, newType) -> paymentFields.useDefaultsFor(newType));
        paymentBox.valueProperty().addListener((obs, oldChoice, newChoice) -> updateMode());
        // Changing what's owed or the rate changes a loan's calculated payment.
        owedField.textProperty().addListener((obs, oldText, newText) -> paymentFields.recalculate());
        interestField.textProperty().addListener((obs, oldText, newText) -> paymentFields.recalculate());
        updateMode();
    }

    /** "Set up a monthly payment", then your bills (debt payments first), then "No payment for now". */
    private static List<PaymentChoice> paymentChoices(List<ScheduledItem> bills) {
        List<PaymentChoice> choices = new ArrayList<>(List.of(SET_UP_PAYMENT));
        bills.stream()
                .sorted(Comparator.comparing((ScheduledItem bill) -> bill.category() != Category.DEBT_PAYMENTS)
                        .thenComparing(ScheduledItem::name, String.CASE_INSENSITIVE_ORDER))
                .map(bill -> new PaymentChoice("Use bill: " + bill.name() + "  ("
                        + Format.money(bill.amount()) + ", " + bill.frequency().label().toLowerCase() + ")", bill))
                .forEach(choices::add);
        choices.add(NO_PAYMENT);
        return choices;
    }

    /** All the payment boxes for a new payment, just "Pay each month" for a linked bill, none for no payment. */
    private void updateMode() {
        PaymentChoice choice = paymentBox.getValue();
        if (choice == SET_UP_PAYMENT) {
            paymentFields.setMode(PaymentFields.Mode.NEW_PAYMENT);
        } else if (choice == NO_PAYMENT) {
            paymentFields.setMode(PaymentFields.Mode.HIDDEN);
        } else {
            paymentFields.setMode(PaymentFields.Mode.RULE_ONLY);
        }
    }

    @Override
    protected NewDebt buildResult() {
        AccountType type = required(typeBox.getValue(), "Pick credit card or loan.");
        BigDecimal owed = Format.parseMoney(owedField.getText(), "Amount owed");
        BigDecimal interest = interestField.getText().isBlank()
                ? BigDecimal.ZERO
                : Format.parseNumber(interestField.getText(), "Interest rate");
        Account debt = new Account(0, nameField.getText(), type, owed, LocalDate.now(), interest, websiteField.getText());

        PaymentChoice choice = required(paymentBox.getValue(), "Pick how this debt gets paid.");
        if (choice == NO_PAYMENT) {
            return new NewDebt(debt, null, null, null);
        }
        if (choice == SET_UP_PAYMENT) {
            return new NewDebt(debt, null, null, paymentFields.build());
        }
        return new NewDebt(debt, paymentFields.rule(), choice.bill(), null);
    }

    /** The number typed in, or null if it isn't one (yet). Used for the live loan calculation. */
    private static BigDecimal numberOrNull(String text) {
        try {
            return Format.parseNumber(text, "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
