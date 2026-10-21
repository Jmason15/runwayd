package com.finance.runwayd.desktop.dialogs;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.desktop.ui.Format;
import javafx.scene.control.Label;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sets up a monthly payment for a debt that doesn't have one yet.
 * Opened from "Set up…" in the Debt tab's Payment column.
 *
 * <p>Same boxes as Add debt: a card pays its minimum; for a loan, type "Payments
 * left" and the amount is worked out from what's owed and the rate.
 */
public final class PaymentSetupDialog extends FormDialog<PaymentPlan> {

    private final PaymentFields paymentFields;

    /**
     * @param debt         the credit card or loan that needs a payment
     * @param owedToday    what's owed right now, for the loan calculation
     * @param cashAccounts accounts the payment can come out of
     * @param formula      works out a loan's monthly payment (FinanceService::loanPayment)
     */
    public PaymentSetupDialog(Account debt, BigDecimal owedToday, List<Account> cashAccounts,
                              LoanPaymentFormula formula) {
        super("Set up payment: " + debt.name());

        paymentFields = new PaymentFields(cashAccounts, formula, () -> owedToday, debt::interestRatePercent);
        addRow("Owed today", new Label(Format.money(owedToday)));
        paymentFields.addTo(this);
        paymentFields.useDefaultsFor(debt.type());
    }

    @Override
    protected PaymentPlan buildResult() {
        return paymentFields.build();
    }
}
