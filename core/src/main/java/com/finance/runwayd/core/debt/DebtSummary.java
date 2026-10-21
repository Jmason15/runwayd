package com.finance.runwayd.core.debt;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.ScheduledItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * "How much debt am I in?" Everything the Debt tab shows, worked out in one go
 * by {@link FinanceService#debtSummary(LocalDate)}.
 *
 * @param date          the date you picked to look ahead to
 * @param debts         one row per credit card or loan
 * @param totalToday    everything you owe right now
 * @param totalOnDate   everything you'll owe on {@code date}
 * @param interestPerMonth  this month's interest across all debts
 * @param principalPerMonth how much of this month's payments actually lowers what you owe
 * @param debtFreeDate  the day the last debt is paid off, or null if some debt never gets paid off
 *                      by the current payments
 * @param overTime      each debt's balance on the 1st of every month from now until you're debt-free
 *                      (or 30 years if a debt never gets paid off), for the stacked chart
 */
public record DebtSummary(
        LocalDate date,
        List<DebtRow> debts,
        BigDecimal totalToday,
        BigDecimal totalOnDate,
        BigDecimal interestPerMonth,
        BigDecimal principalPerMonth,
        LocalDate debtFreeDate,
        List<MonthOfDebt> overTime) {

    public DebtSummary {
        debts = List.copyOf(debts);
        overTime = List.copyOf(overTime);
    }

    /**
     * One debt.
     *
     * @param account        the credit card or loan
     * @param payment        the item that pays it down (its rule is shown on the Debt tab), or null if none
     * @param owedToday      what you owe right now
     * @param owedOnDate     what you'll owe on the chosen date
     * @param monthlyPayment what's paid toward it per month: its own payment (average), plus any budget money
     * @param budgetPerMonth  the part of {@code monthlyPayment} that comes from the Budget tab: about how much
     *                        a month while the budget is paying this debt (zero if it isn't)
     * @param interestPerMonth  this month's interest charge, based on what's owed today
     * @param principalPerMonth the part of the payment that lowers the debt: payment − interest.
     *                          Negative means the payment doesn't cover the interest, so the debt grows.
     * @param payoffDate     the day it reaches zero, or null if current payments never get there
     * @param interestUntilPaidOff all the interest you'll pay before it reaches zero, or null if it never does
     */
    public record DebtRow(
            Account account,
            ScheduledItem payment,
            BigDecimal owedToday,
            BigDecimal owedOnDate,
            BigDecimal monthlyPayment,
            BigDecimal budgetPerMonth,
            BigDecimal interestPerMonth,
            BigDecimal principalPerMonth,
            LocalDate payoffDate,
            BigDecimal interestUntilPaidOff) {
    }

    /**
     * What each debt owes on one day (the 1st of a month), for the chart.
     *
     * @param date       the day
     * @param owedByDebt debt account id -> what's owed that day
     */
    public record MonthOfDebt(LocalDate date, Map<Integer, BigDecimal> owedByDebt) {

        public MonthOfDebt {
            owedByDebt = Map.copyOf(owedByDebt);
        }

        /** What one debt owes that day; zero once it's paid off. */
        public BigDecimal owed(int debtId) {
            return owedByDebt.getOrDefault(debtId, BigDecimal.ZERO);
        }
    }
}
