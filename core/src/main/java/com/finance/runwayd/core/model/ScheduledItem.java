package com.finance.runwayd.core.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Something that moves money on a schedule: a paycheck, a bill, a savings
 * transfer or a debt payment.
 *
 * <p>Every item is described the same way: money moves FROM one account TO another.
 * Leaving one side empty means "the outside world":
 * <pre>
 *   from      to           what it is
 *   -------   ----------   ------------------------------------------
 *   (none)    Checking     Income       (paycheck lands in checking)
 *   Checking  (none)       Bill         (electric bill leaves checking)
 *   Checking  Savings      Transfer     (move money to savings)
 *   Checking  Visa Card   Debt payment (pay down the card)
 *   Visa      (none)       Bill charged to a credit card (debt goes up)
 * </pre>
 * This one rule covers bills, income and debt without needing a separate class
 * for each.
 *
 * <p><b>How much it pays.</b> Usually a fixed {@code amount}. A credit card payment
 * can instead follow the card's minimum ({@link PaymentRule#CARD_MINIMUM}), which
 * the forecast works out each time from the balance. To pay a debt down faster,
 * use the Budget tab's debt bucket.
 *
 * <p><b>Paying a bill.</b> There's no list of past payments. Each item remembers
 * one date, {@code paidThrough}: everything on or before it is done, so the
 * forecast skips it. Clicking "Mark paid" just moves that date to the next due
 * date. The balance isn't changed; your next bank balance update covers that.
 *
 * @param id            unique number, assigned by FinanceService
 * @param name          what it is, e.g. "Mortgage"
 * @param category      what it's for, e.g. Housing. Used to group the Bills list.
 *                      Missing in older data files; then it defaults to Income or Other.
 * @param amount        how much moves each time (positive). Only for FIXED; null for CARD_MINIMUM
 * @param paymentRule   FIXED or CARD_MINIMUM. Missing in older data files; then FIXED
 * @param frequency     how often it happens
 * @param firstDate     any date it happens (the first one, or any past one; the schedule repeats from here)
 * @param endDate       optional last day it can happen, e.g. a loan's final payment. null = never ends
 * @param fromAccountId account money comes out of, or null for income
 * @param toAccountId   account money goes into, or null for bills
 * @param paidThrough   occurrences on or before this date are already paid. null = nothing marked paid yet
 * @param website       optional link to check or pay it online, e.g. "https://myelectric.com". null = none.
 *                      Missing in older data files
 */
public record ScheduledItem(
        int id,
        String name,
        Category category,
        BigDecimal amount,
        PaymentRule paymentRule,
        Frequency frequency,
        LocalDate firstDate,
        LocalDate endDate,
        Integer fromAccountId,
        Integer toAccountId,
        LocalDate paidThrough,
        String website) {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    /** An item without a website. */
    public ScheduledItem(int id, String name, Category category, BigDecimal amount, PaymentRule paymentRule,
                         Frequency frequency, LocalDate firstDate, LocalDate endDate, Integer fromAccountId,
                         Integer toAccountId, LocalDate paidThrough) {
        this(id, name, category, amount, paymentRule, frequency, firstDate, endDate, fromAccountId, toAccountId,
                paidThrough, null);
    }

    /** A new, uncategorized, fixed-amount item that hasn't been paid yet. Handy in tests. */
    public ScheduledItem(int id, String name, BigDecimal amount, Frequency frequency, LocalDate firstDate,
                         LocalDate endDate, Integer fromAccountId, Integer toAccountId) {
        this(id, name, null, amount, PaymentRule.FIXED, frequency, firstDate, endDate,
                fromAccountId, toAccountId, null);
    }

    /** The kinds of item, worked out from which accounts are filled in. */
    public enum Kind {
        INCOME("Income"),
        BILL("Bill"),
        TRANSFER("Transfer / payment");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Checks the values as soon as an item is created, so a bad one can never exist. */
    public ScheduledItem {
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(firstDate, "firstDate");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required.");
        }
        if (fromAccountId == null && toAccountId == null) {
            throw new IllegalArgumentException("Pick at least one account.");
        }
        if (Objects.equals(fromAccountId, toAccountId)) {
            throw new IllegalArgumentException("'From' and 'to' must be different accounts.");
        }
        if (endDate != null && endDate.isBefore(firstDate)) {
            throw new IllegalArgumentException("End date can't be before the first date.");
        }

        // Older data files don't have these fields; fill in the defaults.
        if (paymentRule == null) {
            paymentRule = PaymentRule.FIXED;
        }
        if (category == null) {
            // Income has no "from" account. Everything else starts in Other until you pick.
            category = fromAccountId == null ? Category.INCOME : Category.OTHER;
        }

        if (paymentRule == PaymentRule.FIXED) {
            Objects.requireNonNull(amount, "amount");
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Amount must be more than zero.");
            }
        } else {
            if (toAccountId == null || fromAccountId == null) {
                throw new IllegalArgumentException("A card minimum payment must go from an account into a credit card.");
            }
            amount = null; // the forecast works it out each time
        }
        name = name.trim();
        website = Websites.clean(website);
    }

    /** Income, bill or transfer, based on which side has an account. */
    public Kind kind() {
        if (fromAccountId == null) {
            return Kind.INCOME;
        }
        if (toAccountId == null) {
            return Kind.BILL;
        }
        return Kind.TRANSFER;
    }

    /** True if the amount follows the card's minimum instead of being fixed. */
    public boolean followsCardMinimum() {
        return paymentRule == PaymentRule.CARD_MINIMUM;
    }

    /**
     * {@code amountEachTime} the way a bank statement shows it: income positive,
     * bills and transfers negative, since both take money out of the paying account.
     */
    public BigDecimal signed(BigDecimal amountEachTime) {
        return kind() == Kind.INCOME ? amountEachTime : amountEachTime.negate();
    }

    /**
     * The total per year if {@code amountEachTime} is paid on this item's schedule, signed like
     * {@link #signed}. For example, $139 yearly is -$139.00 and $20 monthly is -$240.00.
     * One-time items count as zero, since they don't repeat.
     */
    public BigDecimal perYear(BigDecimal amountEachTime) {
        return signed(amountEachTime)
                .multiply(BigDecimal.valueOf(frequency.timesPerYear()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The average per month if {@code amountEachTime} is paid on this item's schedule,
     * signed like {@link #signed}. For example, $300 every 2 weeks is -$650.00 a month
     * (26 times a year ÷ 12). One-time items count as zero, since they don't repeat.
     */
    public BigDecimal perMonth(BigDecimal amountEachTime) {
        return signed(amountEachTime)
                .multiply(BigDecimal.valueOf(frequency.timesPerYear()))
                .divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
    }

    /** True if this item moves money into or out of the given account. */
    public boolean touches(int accountId) {
        return Objects.equals(fromAccountId, accountId) || Objects.equals(toAccountId, accountId);
    }

    /**
     * The account whose balance date decides which occurrences still count:
     * the account the money comes out of, or for income, the one it lands in.
     */
    public int mainAccountId() {
        return fromAccountId != null ? fromAccountId : toAccountId;
    }

    /** Same item with a different id. Used when a new item is first saved. */
    public ScheduledItem withId(int newId) {
        return new ScheduledItem(newId, name, category, amount, paymentRule, frequency, firstDate, endDate,
                fromAccountId, toAccountId, paidThrough, website);
    }

    /** Same item with a different fixed amount. Used when you type a new amount into the Bills list. */
    public ScheduledItem withAmount(BigDecimal newAmount) {
        return new ScheduledItem(id, name, category, newAmount, paymentRule, frequency, firstDate, endDate,
                fromAccountId, toAccountId, paidThrough, website);
    }

    /**
     * Same item, paying by a different rule. Switching to CARD_MINIMUM drops the
     * fixed amount; the item must already pay into a card.
     */
    public ScheduledItem withPaymentRule(PaymentRule rule) {
        return new ScheduledItem(id, name, category, amount, rule, frequency, firstDate, endDate,
                fromAccountId, toAccountId, paidThrough, website);
    }

    /** Same item, now paying a fixed {@code fixedAmount} each time (e.g. switching off the card minimum). */
    public ScheduledItem withFixedAmount(BigDecimal fixedAmount) {
        return new ScheduledItem(id, name, category, fixedAmount, PaymentRule.FIXED, frequency, firstDate,
                endDate, fromAccountId, toAccountId, paidThrough, website);
    }

    /**
     * Same item, but the money now goes into {@code accountId}. Used to turn a plain
     * bill like "Store Card" into a payment that lowers the Store Card debt.
     */
    public ScheduledItem withToAccountId(Integer accountId) {
        return new ScheduledItem(id, name, category, amount, paymentRule, frequency, firstDate, endDate,
                fromAccountId, accountId, paidThrough, website);
    }

    /** Same item, with everything up to and including {@code date} marked as paid. */
    public ScheduledItem withPaidThrough(LocalDate date) {
        return new ScheduledItem(id, name, category, amount, paymentRule, frequency, firstDate, endDate,
                fromAccountId, toAccountId, date, website);
    }
}
