package com.finance.runwayd.core.bills;

import com.finance.runwayd.core.model.BudgetBucket;
import com.finance.runwayd.core.model.ScheduledItem;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One line of the "This month" checklist: a bill or paycheck on one date, or one budget
 * bucket's share of a paycheck (money the Budget tab moves out of checking that payday).
 *
 * @param item         the bill or paycheck, or null for a budget bucket
 * @param bucket       the budget bucket, or null for a bill or paycheck
 * @param bucketAmount how much the bucket gets this payday (positive); null for a bill or paycheck
 * @param goesTo       where the bucket's money goes, e.g. "Savings" or "Visa", or null if it's spent
 * @param date         the day it's due
 * @param status       whether it's ticked off, already in the bank, or still to come
 * @param accountId    the account the line comes out of (or, for income, goes into); null until filled in
 * @param balanceAfter what that account holds right after this line, from the forecast; null if it isn't in
 *                     the forecast (ticked, or before the last balance update)
 */
public record ChecklistItem(ScheduledItem item, BudgetBucket bucket, BigDecimal bucketAmount, String goesTo,
                            LocalDate date, Status status, Integer accountId, BigDecimal balanceAfter) {

    /** A bill or paycheck. */
    public ChecklistItem(ScheduledItem item, LocalDate date, Status status) {
        this(item, null, null, null, date, status, null, null);
    }

    /** The same line, with its account and what that account holds right after it. */
    public ChecklistItem withBalanceAfter(Integer account, BigDecimal balance) {
        return new ChecklistItem(item, bucket, bucketAmount, goesTo, date, status, account, balance);
    }

    /** One bucket's share of the paycheck on {@code payday}. */
    public static ChecklistItem forBucket(BudgetBucket bucket, BigDecimal amount, String goesTo, LocalDate payday,
                                          Status status) {
        return new ChecklistItem(null, bucket, amount, goesTo, payday, status, null, null);
    }

    /** True for a budget bucket line, false for a bill or paycheck. */
    public boolean isBudgetSplit() {
        return bucket != null;
    }

    /** "My spending → Spending Account", "Partner's spending", or the bill's name. */
    public String name() {
        if (!isBudgetSplit()) {
            return item.name();
        }
        return goesTo == null ? bucket.name() : bucket.name() + " → " + goesTo;
    }

    public enum Status {
        /** You ticked it off. The forecast doesn't count it any more. */
        PAID("Paid"),
        /**
         * Due before your last balance update and not ticked. Shown unticked (only you tick things), but
         * not counted in the forecast: the balance you typed in already includes it.
         */
        BEFORE_BALANCE_UPDATE("Before balance update"),
        /** Due before today and not ticked off yet. */
        OVERDUE("Overdue"),
        /** Due today and not ticked off yet. */
        DUE_TODAY("Due today"),
        /** Still to come this month. */
        UPCOMING("");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** True if you ticked it off. Only your ticks make this true; a balance update never does. */
    public boolean isDone() {
        return status == Status.PAID;
    }
}
