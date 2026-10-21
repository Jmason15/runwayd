package com.finance.runwayd.core.debt;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Works out a loan's monthly payment, the same way lenders do.
 *
 * <p>The standard formula for a fixed-rate loan that's paid off in equal monthly payments:
 * <pre>
 *   payment = balance × r ÷ (1 − (1 + r)^−n)
 *
 *   r = yearly rate ÷ 12   (e.g. 18.69% → 0.015575 a month)
 *   n = payments left
 * </pre>
 * Example: $16,232.75 at 18.69% with 70 payments left is $382.47 a month.
 * At 0% there's no interest, so it's simply balance ÷ payments left.
 */
public final class LoanCalculator {

    // Enough precision for the (1 + r)^n step; the answer is rounded to cents at the end.
    private static final MathContext PRECISE = MathContext.DECIMAL64;
    private static final BigDecimal PERCENT_PER_YEAR_TO_FRACTION_PER_MONTH = BigDecimal.valueOf(1200);

    private LoanCalculator() {
        // Only static helpers here.
    }

    /**
     * The monthly payment that pays {@code balance} off in exactly {@code paymentsLeft} payments.
     *
     * @throws IllegalArgumentException if there are no payments left or the balance isn't positive
     */
    public static BigDecimal monthlyPayment(BigDecimal balance, BigDecimal yearlyRatePercent, int paymentsLeft) {
        if (paymentsLeft <= 0) {
            throw new IllegalArgumentException("Payments left must be at least 1.");
        }
        if (balance.signum() <= 0) {
            throw new IllegalArgumentException("The amount owed must be more than zero.");
        }
        if (yearlyRatePercent.signum() == 0) {
            return balance.divide(BigDecimal.valueOf(paymentsLeft), 2, RoundingMode.HALF_UP);
        }
        BigDecimal monthlyRate = yearlyRatePercent.divide(PERCENT_PER_YEAR_TO_FRACTION_PER_MONTH, PRECISE);
        // (1 + r)^-n is the same as 1 ÷ (1 + r)^n.
        BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(paymentsLeft, PRECISE);
        BigDecimal discount = BigDecimal.ONE.divide(growth, PRECISE);
        return balance.multiply(monthlyRate)
                .divide(BigDecimal.ONE.subtract(discount), PRECISE)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
