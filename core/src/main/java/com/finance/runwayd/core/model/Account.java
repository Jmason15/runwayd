package com.finance.runwayd.core.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A bank account, credit card or loan, with the last balance you checked.
 *
 * <p>The forecast always starts from {@code balance} on {@code balanceDate} and
 * works forward. That's why there's no "paid" checkbox on bills: when you open
 * your bank app and update this number every week or so, anything that went
 * differently than planned is corrected automatically.
 *
 * @param id                  unique number, assigned by FinanceService
 * @param name                what you call it, e.g. "Everyday Checking"
 * @param type                checking, savings, credit card or loan
 * @param balance             for cash accounts, what you have; for debt accounts, what you owe (as a positive number)
 * @param balanceDate         the day that balance was true. Anything scheduled BEFORE this day is already in it;
 *                            things due on this day still count until ticked off as paid
 * @param interestRatePercent optional yearly interest rate for debts, e.g. 24.99. Zero means "ignore interest".
 * @param website             optional link to manage the account online, e.g. "https://mybank.com". null = none
 * @param minimumPaymentPercent for credit cards: the % of the balance in the minimum payment, on top of interest.
 *                            Defaults to 1, the most common rule. See {@link #minimumPaymentOn}
 * @param minimumPaymentFloor   for credit cards: the minimum payment is never less than this (unless you owe less).
 *                            Defaults to $35
 */
public record Account(
        int id,
        String name,
        AccountType type,
        BigDecimal balance,
        LocalDate balanceDate,
        BigDecimal interestRatePercent,
        String website,
        BigDecimal minimumPaymentPercent,
        BigDecimal minimumPaymentFloor) {

    /** Divide by this to turn "24.99% a year" into one month's fraction: 100 (percent) × 12 (months). */
    private static final BigDecimal PERCENT_PER_YEAR_TO_FRACTION_PER_MONTH = BigDecimal.valueOf(1200);

    /** The usual card minimum: interest + 1% of the balance, but at least $35. */
    public static final BigDecimal DEFAULT_MINIMUM_PERCENT = BigDecimal.ONE;
    public static final BigDecimal DEFAULT_MINIMUM_FLOOR = new BigDecimal("35.00");

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    /** An account without a website, using the usual card-minimum rule. Handy in tests. */
    public Account(int id, String name, AccountType type, BigDecimal balance, LocalDate balanceDate,
                   BigDecimal interestRatePercent) {
        this(id, name, type, balance, balanceDate, interestRatePercent, null);
    }

    /** An account using the usual card-minimum rule. */
    public Account(int id, String name, AccountType type, BigDecimal balance, LocalDate balanceDate,
                   BigDecimal interestRatePercent, String website) {
        this(id, name, type, balance, balanceDate, interestRatePercent, website, null, null);
    }

    /** Checks the values as soon as an Account is created, so a bad one can never exist. */
    public Account {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(balanceDate, "balanceDate");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Account name is required.");
        }
        // Older data files may not have this field at all; treat that as "no interest".
        if (interestRatePercent == null) {
            interestRatePercent = BigDecimal.ZERO;
        }
        if (interestRatePercent.signum() < 0) {
            throw new IllegalArgumentException("Interest rate can't be negative.");
        }
        if (minimumPaymentPercent == null) {
            minimumPaymentPercent = DEFAULT_MINIMUM_PERCENT;
        }
        if (minimumPaymentFloor == null) {
            minimumPaymentFloor = DEFAULT_MINIMUM_FLOOR;
        }
        if (minimumPaymentPercent.signum() < 0 || minimumPaymentFloor.signum() < 0) {
            throw new IllegalArgumentException("Minimum payment settings can't be negative.");
        }
        name = name.trim();
        website = Websites.clean(website);
    }

    /**
     * One month of interest on {@code owed}: owed × yearly rate ÷ 12, to the cent.
     * For example $1,200 at 12% is $12.00. Zero when nothing is owed or there's no rate.
     *
     * <p>This is the one place interest is calculated. The forecast and the Debt tab
     * both use it, so they always agree.
     */
    public BigDecimal monthlyInterestOn(BigDecimal owed) {
        if (owed.signum() <= 0 || interestRatePercent.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return owed.multiply(interestRatePercent)
                .divide(PERCENT_PER_YEAR_TO_FRACTION_PER_MONTH, 2, RoundingMode.HALF_UP);
    }

    /**
     * This card's minimum payment when {@code owed} is the balance, the way most
     * card companies work it out:
     * <pre>
     *   minimum = this month's interest + 1% of the balance     (the % is minimumPaymentPercent)
     *   but at least $35                                         (minimumPaymentFloor)
     *   and never more than you owe
     * </pre>
     * Example: $4,160 at 19.74% → interest $68.43 + 1% $41.60 = <b>$110.03</b>.
     */
    public BigDecimal minimumPaymentOn(BigDecimal owed) {
        if (owed.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal percentOfBalance = owed.multiply(minimumPaymentPercent)
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal minimum = monthlyInterestOn(owed).add(percentOfBalance).max(minimumPaymentFloor);
        return minimum.min(owed).setScale(2, RoundingMode.HALF_UP);
    }

    /** Same account with a different id. Used when a new account is first saved. */
    public Account withId(int newId) {
        return new Account(newId, name, type, balance, balanceDate, interestRatePercent, website,
                minimumPaymentPercent, minimumPaymentFloor);
    }

    /** Same account with an updated balance, e.g. after you check your bank app. */
    public Account withBalance(BigDecimal newBalance, LocalDate asOf) {
        return new Account(id, name, type, newBalance, asOf, interestRatePercent, website,
                minimumPaymentPercent, minimumPaymentFloor);
    }

    /** The text shown in drop-downs. */
    @Override
    public String toString() {
        return name;
    }
}
