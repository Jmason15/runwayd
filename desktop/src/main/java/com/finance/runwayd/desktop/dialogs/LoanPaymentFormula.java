package com.finance.runwayd.desktop.dialogs;

import java.math.BigDecimal;

/**
 * Works out a loan's monthly payment from what's owed, the rate and the payments left.
 *
 * <p>The forms don't do money math themselves; the screens pass in
 * {@code FinanceService::loanPayment}, which uses the core LoanCalculator.
 */
@FunctionalInterface
public interface LoanPaymentFormula {

    BigDecimal monthlyPayment(BigDecimal owed, BigDecimal yearlyRatePercent, int paymentsLeft);
}
