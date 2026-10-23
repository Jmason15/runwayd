package com.finance.runwayd.core.debt;

import com.finance.runwayd.core.Workspace;
import com.finance.runwayd.core.accounts.Accounts;
import com.finance.runwayd.core.bills.Bills;
import com.finance.runwayd.core.forecast.Forecast;
import com.finance.runwayd.core.forecast.Forecasts;
import com.finance.runwayd.core.forecast.LedgerEntry;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.Category;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.PaymentRule;
import com.finance.runwayd.core.model.ScheduledItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Debt tab: how each credit card and loan is paid, and the summary of what you owe,
 * what it costs in interest and when it's paid off.
 */
public final class Debts {

    /** How far ahead to look when working out when a debt will be paid off. */
    private static final int PAYOFF_SEARCH_YEARS = 40;
    /** How far the chart goes when some debt never gets paid off. */
    private static final int CHART_YEARS_IF_NEVER_DEBT_FREE = 30;

    private final Workspace workspace;
    private final Accounts accounts;
    private final Bills bills;
    private final Forecasts forecasts;

    public Debts(Workspace workspace, Accounts accounts, Bills bills, Forecasts forecasts) {
        this.workspace = workspace;
        this.accounts = accounts;
        this.bills = bills;
        this.forecasts = forecasts;
    }

    // -------------------------------------------------------------------------
    // Payments
    // -------------------------------------------------------------------------

    /**
     * Turns a plain bill (e.g. "Store Card", money leaving checking) into a payment
     * toward a debt account, so each payment lowers what you owe. Its category,
     * due dates and paid-through date stay the same.
     *
     * @return the updated payment
     */
    public ScheduledItem linkBillToDebt(int itemId, int debtAccountId) {
        Account debt = accounts.find(debtAccountId)
                .orElseThrow(() -> new IllegalArgumentException("No account with id " + debtAccountId));
        if (!debt.type().isDebt()) {
            throw new IllegalArgumentException(debt.name() + " isn't a credit card or loan.");
        }
        int index = workspace.indexOfItem(itemId);
        ScheduledItem linked = workspace.items().get(index).withToAccountId(debtAccountId);
        workspace.items().set(index, linked);
        workspace.save();
        return linked;
    }

    /**
     * Sets up a monthly payment toward a debt, e.g. "Store Card payment" from checking.
     *
     * @param rule        CARD_MINIMUM, or FIXED with {@code fixedAmount}
     * @param fixedAmount the monthly amount for FIXED; ignored for CARD_MINIMUM
     * @param firstDate   the next due date
     * @return the saved payment
     */
    public ScheduledItem createPayment(int debtAccountId, PaymentRule rule, BigDecimal fixedAmount,
                                       LocalDate firstDate, int fromAccountId) {
        Account debt = accounts.find(debtAccountId)
                .orElseThrow(() -> new IllegalArgumentException("No account with id " + debtAccountId));
        BigDecimal amount = rule == PaymentRule.FIXED ? fixedAmount : null;
        return bills.add(new ScheduledItem(0, debt.name() + " payment", Category.DEBT_PAYMENTS, amount, rule,
                Frequency.MONTHLY, firstDate, null, fromAccountId, debt.id(), null));
    }

    /**
     * The monthly payment that pays off a loan in {@code paymentsLeft} equal payments.
     * See {@link LoanCalculator} for the formula.
     */
    public BigDecimal loanPayment(BigDecimal owed, BigDecimal yearlyRatePercent, int paymentsLeft) {
        return LoanCalculator.monthlyPayment(owed, yearlyRatePercent, paymentsLeft);
    }

    /**
     * Switches a debt payment between the card minimum and a fixed amount.
     * Going to fixed starts from today's minimum, so the payment doesn't suddenly jump;
     * change the amount afterwards if you like.
     */
    public void setPaymentRule(int itemId, PaymentRule rule) {
        ScheduledItem item = workspace.items().get(workspace.indexOfItem(itemId));
        if (item.paymentRule() == rule) {
            return;
        }
        ScheduledItem changed = rule == PaymentRule.CARD_MINIMUM
                ? item.withPaymentRule(PaymentRule.CARD_MINIMUM)
                : item.withFixedAmount(bills.amountEachTime(item));
        bills.update(changed);
    }

    // -------------------------------------------------------------------------
    // The summary
    // -------------------------------------------------------------------------

    /** Everything about your debts: each one's balance, payment, interest and payoff date, plus totals. */
    public DebtSummary summary(LocalDate date) {
        List<Account> debts = accounts.debts();
        Forecast onDate = forecasts.on(date);
        Forecast longRun = forecasts.on(LocalDate.now().plusYears(PAYOFF_SEARCH_YEARS)); // for payoff dates

        Integer budgetPaysNow = debtTheBudgetPaysNow(longRun);
        List<DebtSummary.DebtRow> rows = debts.stream()
                .map(debt -> debtRow(debt, onDate, longRun, Objects.equals(debt.id(), budgetPaysNow)))
                .toList();

        LocalDate debtFree = debtFreeDate(rows);
        // The chart runs until you're debt-free (plus a month, to show the last one reach zero).
        LocalDate chartEnd = debtFree != null
                ? debtFree.plusMonths(1)
                : LocalDate.now().plusYears(CHART_YEARS_IF_NEVER_DEBT_FREE);

        return new DebtSummary(
                date,
                rows,
                sum(rows.stream().map(DebtSummary.DebtRow::owedToday).toList()),
                sum(rows.stream().map(DebtSummary.DebtRow::owedOnDate).toList()),
                sum(rows.stream().map(DebtSummary.DebtRow::interestPerMonth).toList()),
                sum(rows.stream().map(DebtSummary.DebtRow::principalPerMonth).toList()),
                debtFree,
                debtsByMonth(debts, longRun, chartEnd));
    }

    /**
     * One debt's numbers. The interest/principal split works like a statement:
     * this month's interest is owed × rate ÷ 12, and whatever is left of the
     * payment after that is what actually lowers the balance.
     */
    private DebtSummary.DebtRow debtRow(Account debt, Forecast onDate, Forecast longRun,
                                        boolean budgetPaysThisNow) {
        // What you typed in, the same as the RunWay tab. A payment you haven't ticked still counts from today
        // on (payoff date, owed on a date, the chart), just not here.
        BigDecimal owedToday = debt.balance();
        // Only the debt the budget is paying right now shows budget money; later ones show up when it's their turn.
        BigDecimal fromBudget = budgetPaysThisNow ? budgetPerMonthToward(debt, longRun) : BigDecimal.ZERO;
        BigDecimal payment = monthlyPaymentToward(debt).add(fromBudget);
        BigDecimal interest = debt.monthlyInterestOn(owedToday);
        LocalDate payoffDate = longRun.payoffDateOf(debt.id()).orElse(null);

        return new DebtSummary.DebtRow(
                debt,
                paymentsToward(debt).stream().findFirst().orElse(null),
                owedToday,
                onDate.balanceOf(debt.id()),
                payment,
                fromBudget,
                interest,
                payment.subtract(interest),
                payoffDate,
                payoffDate == null ? null : interestCharged(debt, longRun, payoffDate));
    }

    /**
     * The debt the Budget tab is paying right now: the one its next debt payment goes to.
     * With "highest rate first" that changes as debts are paid off, so it's read from the forecast.
     * Null if the budget isn't paying any debt.
     */
    private Integer debtTheBudgetPaysNow(Forecast forecast) {
        LocalDate today = LocalDate.now();
        return forecast.ledger().stream()
                .filter(LedgerEntry::fromBudget)
                .filter(entry -> !entry.date().isBefore(today))
                // Budget lines on a debt account are its payments (the rest are on checking).
                .filter(entry -> accounts.find(entry.accountId()).map(a -> a.type().isDebt()).orElse(false))
                .map(LedgerEntry::accountId)
                .findFirst()
                .orElse(null);
    }

    /**
     * What the Budget tab pays toward {@code debt} per month, while it's paying it, read straight
     * from the forecast over the next 12 months. So it follows the real plan: the debt the bucket
     * picked, or whichever debt "highest rate first" is on, and only up to what's still owed.
     *
     * <p>Months the debt gets nothing from the budget (e.g. before the budget has money to give,
     * or after it's paid off), and the month of the small final payment, are left out of the
     * average, so this reads as "about $X a month while it's getting the budget".
     */
    private static BigDecimal budgetPerMonthToward(Account debt, Forecast forecast) {
        LocalDate today = LocalDate.now();
        LocalDate yearOn = today.plusYears(1);
        Map<YearMonth, BigDecimal> byMonth = new HashMap<>();
        forecast.ledgerFor(debt.id()).stream()
                .filter(LedgerEntry::fromBudget)
                .filter(entry -> !entry.date().isBefore(today) && entry.date().isBefore(yearOn))
                // A payment lowers the debt, so its change is negative; flip it.
                .forEach(entry -> byMonth.merge(YearMonth.from(entry.date()), entry.change().negate(), BigDecimal::add));
        // The month it's paid off usually only needs a small final payment; leave it out so the
        // average shows the usual monthly amount (unless that's the only month).
        forecast.payoffDateOf(debt.id()).map(YearMonth::from).ifPresent(payoffMonth -> {
            if (byMonth.size() > 1) {
                byMonth.remove(payoffMonth);
            }
        });
        if (byMonth.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return sum(byMonth.values()).divide(BigDecimal.valueOf(byMonth.size()), 2, RoundingMode.HALF_UP);
    }

    /** Adds up every interest charge on {@code debt} in the forecast, up to {@code until}. */
    private static BigDecimal interestCharged(Account debt, Forecast forecast, LocalDate until) {
        return sum(forecast.ledgerFor(debt.id()).stream()
                .filter(LedgerEntry::interest)
                .filter(entry -> !entry.date().isAfter(until))
                .map(LedgerEntry::change)
                .toList());
    }

    /** The average paid into a debt per month, from every item whose "Paid into" is that debt. */
    private BigDecimal monthlyPaymentToward(Account debt) {
        // Payments are money leaving checking, so their monthly total is negative. Flip it for display.
        return bills.monthlyTotal(paymentsToward(debt)).negate();
    }

    /** Every item whose "Paid into" is {@code debt}. Usually just one. */
    private List<ScheduledItem> paymentsToward(Account debt) {
        return workspace.items().stream()
                .filter(item -> Objects.equals(item.toAccountId(), debt.id()))
                .toList();
    }

    /** The latest payoff date, but only if every debt that's still owed gets paid off. */
    private static LocalDate debtFreeDate(List<DebtSummary.DebtRow> rows) {
        LocalDate latest = null;
        for (DebtSummary.DebtRow row : rows) {
            if (row.owedToday().signum() <= 0) {
                continue; // already paid off, so it doesn't hold anything up
            }
            if (row.payoffDate() == null) {
                return null; // this one never gets paid off, so you're never debt-free
            }
            if (latest == null || row.payoffDate().isAfter(latest)) {
                latest = row.payoffDate();
            }
        }
        return latest;
    }

    /**
     * Each debt's balance today and on the 1st of every month after, until {@code until}.
     * Replays the forecast's ledger in order, writing down every debt's balance as each month starts.
     */
    private static List<DebtSummary.MonthOfDebt> debtsByMonth(List<Account> debts, Forecast forecast,
                                                             LocalDate until) {
        if (debts.isEmpty()) {
            return List.of();
        }
        Map<Integer, BigDecimal> owed = new HashMap<>();
        debts.forEach(debt -> owed.put(debt.id(), debt.balance()));
        List<LedgerEntry> debtLines = forecast.ledger().stream()
                .filter(entry -> owed.containsKey(entry.accountId()))
                .toList();

        List<DebtSummary.MonthOfDebt> months = new ArrayList<>();
        int next = 0; // the next ledger line not yet applied
        LocalDate day = LocalDate.now();
        while (!day.isAfter(until)) {
            // Apply everything up to and including this day, then take a snapshot.
            while (next < debtLines.size() && !debtLines.get(next).date().isAfter(day)) {
                LedgerEntry entry = debtLines.get(next++);
                owed.put(entry.accountId(), entry.balanceAfter().max(BigDecimal.ZERO));
            }
            months.add(new DebtSummary.MonthOfDebt(day, owed));
            day = day.withDayOfMonth(1).plusMonths(1);
        }
        return months;
    }

    private static BigDecimal sum(Collection<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
