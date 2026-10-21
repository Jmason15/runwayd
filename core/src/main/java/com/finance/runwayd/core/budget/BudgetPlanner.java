package com.finance.runwayd.core.budget;

import com.finance.runwayd.core.forecast.Forecast;
import com.finance.runwayd.core.forecast.LedgerEntry;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.BudgetBucket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Works out how much of each paycheck is safe to split, and splits it.
 *
 * <p><b>Safe limit.</b> Moving money out on payday lowers every later balance by the same
 * amount, so for each paycheck there's a most you could have moved out <i>in total</i> by
 * then without ever going below the cushion:
 * <pre>
 *   limit(paycheck k) = (lowest balance from payday k onward) − cushion
 * </pre>
 *
 * <p><b>Evening it out.</b> Handing out each paycheck's full limit right away is safe but
 * lumpy: if big bills land right after one paycheck, that paycheck gets $0 and the
 * other gets everything. Instead, the running total handed out is the tightest
 * "string" pulled from $0 that never goes above any paycheck's limit (in maths terms,
 * the lower convex hull of the limits). Between the points where the string touches a
 * limit, every paycheck gets the same amount. So the cushion is never touched, and
 * each paycheck gets as steady a share as the bills allow.
 *
 * <p><b>Topping up.</b> The limits come from the forecast without the budget, but the budget changes
 * later bills (paying cards down shrinks their minimums), so the account ends up higher than planned.
 * {@link #topUp} hands out that extra and evens out again; {@link BudgetPlans} repeats it until nothing
 * is left, keeping only rounds that never go below the cushion.
 *
 * <p>Paychecks are evened out <b>one year at a time</b> (this year's together, then next
 * year's), so the amounts are the same whether a screen looks 3 months or 40 years ahead.
 *
 * <p><b>Splitting</b>: dollar buckets are filled first, in order. Percent buckets
 * then share what's left after the dollar buckets. Anything not assigned is "left
 * over"; if the dollar buckets alone are more than is available, left over goes
 * negative ("short").
 *
 * <p>This only reads the forecast. It never changes any data.
 */
public final class BudgetPlanner {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private BudgetPlanner() {
        // Only static helpers here.
    }

    /**
     * @param forecast    a forecast that runs well past the last paycheck shown (so later bills are counted)
     * @param account     the account to keep above the cushion
     * @param cushion     the least the account should hold
     * @param paycheckIds the scheduled items that are paychecks into {@code account}
     * @param buckets     how to split each paycheck's extra
     * @param today       nothing before this date is looked at
     * @param evenOutUntil paychecks up to this date are evened out together (FinanceService uses at least a
     *                     year, so the amounts don't change with how far ahead you look)
     * @param howMany     how many paychecks to return
     */
    public static BudgetPlan plan(Forecast forecast, Account account, BigDecimal cushion, Set<Integer> paycheckIds,
                                  List<BudgetBucket> buckets, LocalDate today, LocalDate evenOutUntil, int howMany) {
        List<LedgerEntry> history = forecast.ledgerFor(account.id());
        List<LedgerEntry> future = history.stream().filter(entry -> !entry.date().isBefore(today)).toList();

        // The lowest balance from each line onward, so each payday's limit is a quick lookup
        // even for forecasts years long.
        BigDecimal[] lowestFromHere = lowestFromEachLine(future);

        // 1. Find the paydays, and each one's safe limit (the most handed out in total by then).
        List<LedgerEntry> paydays = new ArrayList<>();
        List<BigDecimal> limits = new ArrayList<>();
        for (int i = 0; i < future.size() && !future.get(i).date().isAfter(evenOutUntil); i++) {
            LedgerEntry entry = future.get(i);
            if (entry.itemId() == null || !paycheckIds.contains(entry.itemId())) {
                continue; // not a payday
            }
            paydays.add(entry);
            BigDecimal lowestFromPayday = lowestFromHere[i];
            // Never below zero: if the balance already dips under the cushion, nothing is safe yet.
            limits.add(lowestFromPayday.subtract(cushion).max(BigDecimal.ZERO));
        }

        // 2. Even out the running total under those limits, then turn it back into per-paycheck amounts.
        List<BigDecimal> runningTotals = evenOutYearByYear(paydays, limits, today);
        List<BudgetPlan.PaycheckSplit> splits = new ArrayList<>();
        BigDecimal previousTotal = BigDecimal.ZERO;
        for (int k = 0; k < paydays.size() && k < howMany; k++) {
            BigDecimal available = runningTotals.get(k).subtract(previousTotal);
            previousTotal = runningTotals.get(k);
            splits.add(split(paydays.get(k), available, buckets));
        }
        return new BudgetPlan(account, cushion, splits, firstDipBelow(cushion, startingBalance(account, history, today), future, today));
    }

    /**
     * Hands out what {@code plan} left on the table. {@code withPlan} is a forecast that already
     * includes {@code plan}'s splits. Splits can change later bills: a debt bucket pays cards down,
     * so their minimum payments shrink, and the account ends up higher than the plan assumed.
     *
     * <p>For each paycheck, the room still left is (lowest balance from that payday onward,
     * with the plan) − cushion. That's added to what the plan's buckets had taken out by then (not
     * counting "left over", which stays in the account), giving new
     * limits, which are evened out again the same way. Room can also be negative (the plan took a
     * little too much), and then the limits go down instead.
     *
     * <p>{@link BudgetPlans} repeats this a few times and keeps a result only if its own forecast
     * never goes below the cushion.
     */
    public static BudgetPlan topUp(BudgetPlan plan, Forecast withPlan, Set<Integer> paycheckIds,
                                   List<BudgetBucket> buckets, LocalDate today) {
        List<LedgerEntry> future = withPlan.ledgerFor(plan.account().id()).stream()
                .filter(entry -> !entry.date().isBefore(today))
                .toList();
        BigDecimal[] lowestFromHere = lowestFromEachLine(future);

        List<LedgerEntry> paydays = new ArrayList<>();
        List<BigDecimal> limits = new ArrayList<>();
        BigDecimal handedOutSoFar = BigDecimal.ZERO;
        int k = 0;
        for (int i = 0; i < future.size() && k < plan.paychecks().size(); i++) {
            LedgerEntry entry = future.get(i);
            if (entry.itemId() == null || !paycheckIds.contains(entry.itemId())) {
                continue; // not a payday
            }
            // Only what the buckets actually took left the account; "left over" stayed in it, so it's
            // already part of the room below and mustn't be counted twice.
            BudgetPlan.PaycheckSplit split = plan.paychecks().get(k);
            handedOutSoFar = handedOutSoFar.add(split.moved());
            BigDecimal roomLeft = lowestFromHere[i].subtract(plan.cushion());
            paydays.add(entry);
            limits.add(handedOutSoFar.add(roomLeft).max(BigDecimal.ZERO));
            k++;
        }

        List<BigDecimal> runningTotals = evenOutYearByYear(paydays, limits, today);
        List<BudgetPlan.PaycheckSplit> splits = new ArrayList<>();
        BigDecimal previousTotal = BigDecimal.ZERO;
        for (int j = 0; j < paydays.size(); j++) {
            splits.add(split(paydays.get(j), runningTotals.get(j).subtract(previousTotal), buckets));
            previousTotal = runningTotals.get(j);
        }
        return new BudgetPlan(plan.account(), plan.cushion(), splits, plan.lowBalance());
    }

    /** The lowest balance {@code accountId} reaches from {@code today} on, or null if nothing happens. */
    public static BigDecimal lowestBalance(Forecast forecast, int accountId, LocalDate today) {
        return forecast.ledgerFor(accountId).stream()
                .filter(entry -> !entry.date().isBefore(today))
                .map(LedgerEntry::balanceAfter)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    /** For each ledger line, the lowest balance from that line to the end. Worked out backwards, once. */
    private static BigDecimal[] lowestFromEachLine(List<LedgerEntry> lines) {
        BigDecimal[] lowest = new BigDecimal[lines.size()];
        for (int i = lines.size() - 1; i >= 0; i--) {
            BigDecimal balance = lines.get(i).balanceAfter();
            lowest[i] = i == lines.size() - 1 ? balance : balance.min(lowest[i + 1]);
        }
        return lowest;
    }

    /**
     * Evens out each year's paychecks on their own, carrying the running total from one
     * year into the next. Every year's total stays under its own limits, and since limits
     * never go down, it's under every later limit too, so later years are still safe.
     */
    static List<BigDecimal> evenOutYearByYear(List<LedgerEntry> paydays, List<BigDecimal> limits, LocalDate today) {
        List<BigDecimal> totals = new ArrayList<>();
        BigDecimal carried = BigDecimal.ZERO; // handed out in earlier years
        int start = 0;
        for (int year = 1; start < paydays.size(); year++) {
            LocalDate yearEnd = today.plusYears(year);
            int end = start;
            while (end < paydays.size() && paydays.get(end).date().isBefore(yearEnd)) {
                end++;
            }
            if (end == start) {
                continue; // no paychecks this year
            }
            // This year's limits, minus what earlier years already handed out.
            List<BigDecimal> thisYear = new ArrayList<>();
            for (BigDecimal limit : limits.subList(start, end)) {
                thisYear.add(limit.subtract(carried).max(BigDecimal.ZERO));
            }
            for (BigDecimal total : evenOut(thisYear)) {
                totals.add(total.add(carried));
            }
            carried = totals.get(totals.size() - 1);
            start = end;
        }
        return totals;
    }

    /**
     * The steadiest running totals that never go over {@code limits} (which never go down).
     *
     * <p>Starting from $0, look ahead at every later paycheck and pick the one that allows the
     * smallest equal amount per paycheck to reach its limit (on a tie, the furthest one).
     * Every paycheck up to there gets that amount; then carry on from there. Each step uses
     * the smallest amount any limit ahead allows, so no limit is ever passed.
     *
     * <p>Example with limits 2,166 / 4,645 / 4,645 / 7,714: from $0, paycheck 3 allows only
     * 4,645 ÷ 3 = 1,548.33 each, the smallest, so paychecks 1 to 3 get 1,548.33 each.
     */
    static List<BigDecimal> evenOut(List<BigDecimal> limits) {
        List<BigDecimal> totals = new ArrayList<>();
        BigDecimal base = BigDecimal.ZERO; // running total at the last point the "string" touched
        int from = -1;                     // that point's paycheck (-1 = before the first one)
        while (from < limits.size() - 1) {
            int best = -1;
            BigDecimal bestPerPaycheck = null;
            for (int j = from + 1; j < limits.size(); j++) {
                BigDecimal perPaycheck = limits.get(j).subtract(base)
                        .divide(BigDecimal.valueOf(j - from), 10, RoundingMode.DOWN);
                if (bestPerPaycheck == null || perPaycheck.compareTo(bestPerPaycheck) <= 0) {
                    best = j;
                    bestPerPaycheck = perPaycheck;
                }
            }
            for (int k = from + 1; k <= best; k++) {
                // Rounded down to cents, so a total can never creep over its limit.
                totals.add(base.add(bestPerPaycheck.multiply(BigDecimal.valueOf(k - from)))
                        .setScale(2, RoundingMode.DOWN));
            }
            base = totals.get(best);
            from = best;
        }
        return totals;
    }

    /** Fills the dollar buckets first, then shares the rest among the percent buckets. */
    static BudgetPlan.PaycheckSplit split(LedgerEntry payday, BigDecimal available, List<BudgetBucket> buckets) {
        BigDecimal dollarsTotal = buckets.stream()
                .filter(bucket -> bucket.unit() == BudgetBucket.Unit.DOLLARS)
                .map(BudgetBucket::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal leftForPercents = available.subtract(dollarsTotal).max(BigDecimal.ZERO);

        List<BigDecimal> amounts = new ArrayList<>();
        for (BudgetBucket bucket : buckets) {
            amounts.add(bucket.unit() == BudgetBucket.Unit.DOLLARS
                    ? bucket.value().setScale(2, RoundingMode.HALF_UP)
                    // Round percents down so the buckets never add up to more than there is.
                    : leftForPercents.multiply(bucket.value()).divide(ONE_HUNDRED, 2, RoundingMode.DOWN));
        }
        BigDecimal assigned = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BudgetPlan.PaycheckSplit(payday.date(), payday.description(), payday.change(), available,
                amounts, available.subtract(assigned));
    }

    /** The balance going into today: the last ledger line before today, or the account's saved balance. */
    private static BigDecimal startingBalance(Account account, List<LedgerEntry> history, LocalDate today) {
        BigDecimal balance = account.balance();
        for (LedgerEntry entry : history) {
            if (entry.date().isBefore(today)) {
                balance = entry.balanceAfter();
            }
        }
        return balance;
    }

    /** The first time the balance falls below the cushion (before any splitting), or null if it never does. */
    private static BudgetPlan.LowBalance firstDipBelow(BigDecimal cushion, BigDecimal startingBalance,
                                                      List<LedgerEntry> future, LocalDate today) {
        if (startingBalance.compareTo(cushion) < 0) {
            return new BudgetPlan.LowBalance(today, startingBalance); // already below it
        }
        return future.stream()
                .filter(entry -> entry.balanceAfter().compareTo(cushion) < 0)
                .findFirst()
                .map(entry -> new BudgetPlan.LowBalance(entry.date(), entry.balanceAfter()))
                .orElse(null);
    }
}
