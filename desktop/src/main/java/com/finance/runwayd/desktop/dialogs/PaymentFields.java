package com.finance.runwayd.desktop.dialogs;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.PaymentRule;
import com.finance.runwayd.desktop.ui.Format;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * The "how is this debt paid?" boxes, shared by the Add debt and Set up payment forms
 * so they look and work the same:
 * <pre>
 *   Pay each month   [Card minimum ▾]       cards default to the minimum
 *   Payments left    [70]                   loans: fills in the amount for you
 *   Payment amount   [382.47]               only for a fixed amount; can be changed
 *   Next due date    [Oct 24, 2026]
 *   Paid from        [Checking ▾]
 * </pre>
 */
final class PaymentFields {

    /** Which boxes to show. */
    enum Mode {
        /** A brand-new payment: every box. */
        NEW_PAYMENT,
        /** Linking an existing bill: only "Pay each month"; the bill already has a date and account. */
        RULE_ONLY,
        /** No payment: nothing. */
        HIDDEN
    }

    private final ComboBox<PaymentRule> ruleBox = new ComboBox<>();
    private final TextField paymentsLeftField = new TextField();
    private final TextField amountField = new TextField();
    private final DatePicker dueDatePicker = new DatePicker(LocalDate.now().plusMonths(1));
    private final ComboBox<Account> paidFromBox = new ComboBox<>();

    private final LoanPaymentFormula formula;
    private final Supplier<BigDecimal> owed;
    private final Supplier<BigDecimal> ratePercent;

    private FormDialog.FormRow ruleRow;
    private FormDialog.FormRow paymentsLeftRow;
    private FormDialog.FormRow amountRow;
    private FormDialog.FormRow dueDateRow;
    private FormDialog.FormRow paidFromRow;
    private Mode mode = Mode.NEW_PAYMENT;

    /**
     * @param cashAccounts accounts the payment can come out of; the first is picked to start
     * @param owed         reads what's owed (null if not typed in yet), for the loan calculation
     * @param ratePercent  reads the interest rate (null if not typed in yet), for the loan calculation
     */
    PaymentFields(List<Account> cashAccounts, LoanPaymentFormula formula,
                  Supplier<BigDecimal> owed, Supplier<BigDecimal> ratePercent) {
        this.formula = formula;
        this.owed = owed;
        this.ratePercent = ratePercent;

        ruleBox.getItems().setAll(PaymentRule.values());
        ruleBox.setValue(PaymentRule.CARD_MINIMUM);
        paymentsLeftField.setPromptText("from your lender, e.g. 70");
        amountField.setPromptText("e.g. 382.47");
        paidFromBox.getItems().setAll(cashAccounts);
        if (!cashAccounts.isEmpty()) {
            paidFromBox.setValue(cashAccounts.get(0));
        }

        ruleBox.valueProperty().addListener((obs, oldRule, newRule) -> updateRows());
        paymentsLeftField.textProperty().addListener((obs, oldText, newText) -> recalculate());
    }

    /** Adds the boxes to the end of {@code dialog}'s form. */
    void addTo(FormDialog<?> dialog) {
        ruleRow = dialog.addRow("Pay each month", ruleBox);
        paymentsLeftRow = dialog.addRow("Payments left", paymentsLeftField);
        amountRow = dialog.addRow("Payment amount", amountField);
        dueDateRow = dialog.addRow("Next due date", dueDatePicker);
        paidFromRow = dialog.addRow("Paid from", paidFromBox);
        updateRows();
    }

    /** Cards start on the card minimum; loans on a fixed amount. */
    void useDefaultsFor(AccountType type) {
        ruleBox.setValue(type == AccountType.CREDIT_CARD ? PaymentRule.CARD_MINIMUM : PaymentRule.FIXED);
    }

    /** Picks which boxes to show; see {@link Mode}. */
    void setMode(Mode mode) {
        this.mode = mode;
        updateRows();
    }

    /** The current choice in "Pay each month". */
    PaymentRule rule() {
        return ruleBox.getValue();
    }

    /**
     * Fills in the payment amount from "Payments left", using the loan formula.
     * Does nothing until the amount owed, the rate and the payments left are all filled in.
     */
    void recalculate() {
        BigDecimal owedNow = owed.get();
        BigDecimal rate = ratePercent.get();
        Integer paymentsLeft = parseWholeNumber(paymentsLeftField.getText());
        if (owedNow == null || rate == null || paymentsLeft == null || paymentsLeft <= 0 || owedNow.signum() <= 0) {
            return;
        }
        amountField.setText(Format.moneyForEditing(formula.monthlyPayment(owedNow, rate, paymentsLeft)));
    }

    /** Reads the boxes. Throws IllegalArgumentException with a friendly message if something's missing. */
    PaymentPlan build() {
        PaymentRule rule = FormDialog.required(ruleBox.getValue(), "Pick how much to pay each month.");
        BigDecimal fixedAmount = rule == PaymentRule.FIXED
                ? Format.parseMoney(amountField.getText(), "Payment amount")
                : null;
        LocalDate due = FormDialog.required(FormDialog.dateFrom(dueDatePicker), "Pick the next due date.");
        Account from = FormDialog.required(paidFromBox.getValue(), "Pick where the payment comes from.");
        return new PaymentPlan(rule, fixedAmount, due, from);
    }

    /** Only show what applies: the amount boxes are for fixed payments only. */
    private void updateRows() {
        if (ruleRow == null) {
            return; // not added to a form yet
        }
        boolean newPayment = mode == Mode.NEW_PAYMENT;
        boolean fixed = ruleBox.getValue() == PaymentRule.FIXED;
        ruleRow.setShown(mode != Mode.HIDDEN);
        paymentsLeftRow.setShown(newPayment && fixed);
        amountRow.setShown(newPayment && fixed);
        dueDateRow.setShown(newPayment);
        paidFromRow.setShown(newPayment);
    }

    private static Integer parseWholeNumber(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
