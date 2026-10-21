package com.finance.runwayd.core.budget;

import com.finance.runwayd.core.model.Account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The Budget tab's numbers: for each upcoming paycheck, how much is safe to split
 * and where it goes. Worked out by {@link BudgetPlanner}.
 *
 * @param account    the account kept above the cushion (usually checking), or null if there isn't one
 * @param cushion    the least that account should ever hold, e.g. $1,000
 * @param paychecks  the next few paychecks, soonest first
 * @param lowBalance set if the account already dips below the cushion before anything is split, else null
 */
public record BudgetPlan(Account account, BigDecimal cushion, List<PaycheckSplit> paychecks, LowBalance lowBalance) {

    public BudgetPlan {
        paychecks = List.copyOf(paychecks);
    }

    /** Everything available to split across all the paychecks in this plan. */
    public BigDecimal totalAvailable() {
        return paychecks.stream().map(PaycheckSplit::available).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** What bucket number {@code bucketIndex} gets across all the paychecks in this plan. */
    public BigDecimal totalForBucket(int bucketIndex) {
        return paychecks.stream()
                .map(paycheck -> paycheck.bucketAmounts().get(bucketIndex))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * One paycheck and how its extra cash is split.
     *
     * @param date          payday
     * @param paycheckName  e.g. "Income 25th"
     * @param paycheck      the paycheck amount
     * @param available     safe to move out on payday: the account stays above the cushion for every
     *                      future bill, even after the earlier paychecks' splits
     * @param bucketAmounts what each bucket gets, in the same order as the budget's buckets
     * @param leftOver      available minus everything assigned. Negative means the dollar buckets ask
     *                      for more than is safe ("short")
     */
    public record PaycheckSplit(
            LocalDate date,
            String paycheckName,
            BigDecimal paycheck,
            BigDecimal available,
            List<BigDecimal> bucketAmounts,
            BigDecimal leftOver) {

        public PaycheckSplit {
            bucketAmounts = List.copyOf(bucketAmounts);
        }

        /** What the buckets actually move out of the account (everything but "left over"). */
        public BigDecimal moved() {
            return available.subtract(leftOver);
        }
    }

    /** The first day the balance drops below the cushion, and how low it gets that day. */
    public record LowBalance(LocalDate date, BigDecimal balance) {
    }
}
