package com.finance.runwayd.core.forecast;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.ScheduledItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays your schedule forward, day by day, to see what every account will hold.
 *
 * <p>How it works, in plain steps:
 * <ol>
 *   <li>Start each account at the balance you last entered.</li>
 *   <li>List every bill, paycheck, transfer and interest charge up to the target date that isn't ticked off,
 *       starting from the day tracking began. A bill that's due before an account's last balance update
 *       still counts if it isn't ticked: nothing is assumed paid until you say so.</li>
 *   <li>Sort them by date. On the same day, income goes first so a payday doesn't look like an overdraft.</li>
 *   <li>Apply them one at a time, writing down each account's new balance as we go.</li>
 * </ol>
 *
 * <p>Two debt rules make payoff dates come out right without a separate debt model:
 * <ul>
 *   <li>A payment never pays more than what's still owed. The last payment is just the remainder.</li>
 *   <li>Once a debt hits zero, payments to it stop. With the <b>snowball rollover</b> on (the
 *       default), each of those payments keeps going on its usual dates to the highest-rate debt
 *       still owed, so the amount paying down debt grows with every payoff. With it off, that
 *       money stays in checking.</li>
 * </ul>
 *
 * <p>Interest is optional. If a debt account has a rate, one month of interest is
 * added on the 1st of each month: owed × rate ÷ 12. That's an estimate, not the
 * bank's exact daily calculation. Updating the balance from your statement keeps
 * it honest.
 *
 * <p>This class has no saved state. Give it the same input and you get the same output,
 * which is what makes it easy to unit test.
 */
public final class Forecaster {

    /**
     * Runs the forecast from each account's last known balance up to and including {@code through}.
     */
    public Forecast run(List<Account> accounts, List<ScheduledItem> items, LocalDate through) {
        return run(accounts, items, List.of(), through);
    }

    /**
     * The same, plus the Budget tab's paycheck splits played in on each payday.
     * See {@link BudgetMove}.
     */
    public Forecast run(List<Account> accounts, List<ScheduledItem> items, List<BudgetMove> budgetMoves,
                        LocalDate through) {
        return run(accounts, items, budgetMoves, through, false);
    }

    /**
     * The same, choosing whether payments to a paid-off debt roll over to the next one.
     *
     * @param rollPaidOffPayments true: once a debt is paid off, its payment keeps going on its usual
     *                            dates, to the highest-rate debt still owed (the "snowball rollover").
     *                            false: the payment simply stops and the money stays in the account.
     */
    public Forecast run(List<Account> accounts, List<ScheduledItem> items, List<BudgetMove> budgetMoves,
                        LocalDate through, boolean rollPaidOffPayments) {
        return run(accounts, items, budgetMoves, through, rollPaidOffPayments, null);
    }

    /**
     * The same, counting from {@code trackingSince}: every occurrence from that day on that isn't ticked
     * off is counted, even one due before its account's balance date (it may not have cleared yet).
     * Null means the earliest balance date.
     */
    public Forecast run(List<Account> accounts, List<ScheduledItem> items, List<BudgetMove> budgetMoves,
                        LocalDate through, boolean rollPaidOffPayments, LocalDate trackingSince) {
        return new Run(accounts, through, rollPaidOffPayments, trackingSince).play(items, budgetMoves);
    }

    /**
     * The working state of a single forecast run. It's a separate object so
     * {@link Forecaster} itself never holds data between calls.
     */
    private static final class Run {
        private final Map<Integer, Account> accountsById = new LinkedHashMap<>();
        private final LocalDate through;
        private final boolean rollPaidOffPayments; // see Forecaster.run
        private final LocalDate countFrom;          // nothing before this day is counted (see appliesTo)

        // These fill in as events are applied, and become the Forecast at the end.
        private final Map<Integer, BigDecimal> balances = new LinkedHashMap<>();
        private final List<LedgerEntry> ledger = new ArrayList<>();
        private final Map<Integer, Forecast.LowPoint> lowPoints = new HashMap<>();
        private final Map<Integer, LocalDate> payoffDates = new HashMap<>();

        // Each debt's regular payment at the start, so we know how much rolls over when it's paid off.
        private final Map<Integer, BigDecimal> regularPayments = new HashMap<>();

        Run(List<Account> accounts, LocalDate through, boolean rollPaidOffPayments, LocalDate trackingSince) {
            this.through = through;
            this.rollPaidOffPayments = rollPaidOffPayments;
            LocalDate earliestBalance = accounts.stream().map(Account::balanceDate)
                    .min(Comparator.naturalOrder()).orElse(through);
            // Never later than the earliest balance, so an account entered as of an older date still counts from it.
            this.countFrom = trackingSince == null || earliestBalance.isBefore(trackingSince) ? earliestBalance : trackingSince;
            for (Account account : accounts) {
                accountsById.put(account.id(), account);
                balances.put(account.id(), account.balance());
                if (!account.type().isDebt()) {
                    // Until something lowers it, the starting balance IS the low point.
                    lowPoints.put(account.id(), new Forecast.LowPoint(account.balance(), account.balanceDate()));
                }
            }
        }

        Forecast play(List<ScheduledItem> items, List<BudgetMove> budgetMoves) {
            noteRegularPayments(items);
            for (Event event : buildTimeline(items, budgetMoves)) {
                if (event.isInterest()) {
                    chargeInterest(event.date(), event.interestOn());
                } else if (event.isBudget()) {
                    applyBudgetMove(event.date(), event.budgetMove());
                } else {
                    applyItem(event.date(), event.item());
                }
            }
            return new Forecast(through, balances, ledger, lowPoints, payoffDates);
        }

        // ---------------------------------------------------------------------
        // Step 2 & 3: build the sorted list of everything that will happen
        // ---------------------------------------------------------------------

        private List<Event> buildTimeline(List<ScheduledItem> items, List<BudgetMove> budgetMoves) {
            if (accountsById.isEmpty()) {
                return List.of();
            }
            // Start the day tracking began (including that day itself). Ticked dates are skipped by the schedule.
            LocalDate start = countFrom;

            List<Event> events = new ArrayList<>();
            for (ScheduledItem item : items) {
                for (LocalDate date : ScheduleCalculator.datesBetween(item, start.minusDays(1), through)) {
                    events.add(Event.forItem(date, item));
                }
            }
            for (Account account : accountsById.values()) {
                if (account.type().isDebt() && account.interestRatePercent().signum() > 0) {
                    // Interest is charged on the 1st of every month after the balance date.
                    LocalDate firstOfMonth = account.balanceDate().withDayOfMonth(1).plusMonths(1);
                    for (LocalDate date = firstOfMonth; !date.isAfter(through); date = date.plusMonths(1)) {
                        events.add(Event.forInterest(date, account));
                    }
                }
            }
            for (BudgetMove move : budgetMoves) {
                if (!move.date().isAfter(through)) {
                    events.add(Event.forBudget(move));
                }
            }

            events.sort(Comparator.comparing(Event::date).thenComparingInt(Event::sameDayOrder));
            return events;
        }

        // ---------------------------------------------------------------------
        // Step 4: apply each event to the balances
        // ---------------------------------------------------------------------

        private void applyItem(LocalDate date, ScheduledItem item) {
            Account from = item.fromAccountId() == null ? null : accountsById.get(item.fromAccountId());
            Account to = item.toAccountId() == null ? null : accountsById.get(item.toAccountId());
            boolean paysADebt = to != null && to.type().isDebt() && appliesTo(to, date);

            // A payment to a debt that's already paid off doesn't go to it any more. Checked first,
            // because a paid-off card's minimum is $0 and would otherwise just be skipped.
            if (paysADebt && balances.get(to.id()).signum() <= 0) {
                if (rollPaidOffPayments) {
                    rollPaymentOver(date, item, from, to);
                }
                return;
            }

            BigDecimal amount = amountThisTime(item, to);
            if (amount.signum() <= 0) {
                return; // nothing to move
            }
            if (paysADebt) {
                amount = amount.min(balances.get(to.id())); // never pay more than is owed
            }

            if (from != null && appliesTo(from, date)) {
                // Money leaving a cash account lowers it; "leaving" a credit card means you charged it, so you owe more.
                BigDecimal change = from.type().isDebt() ? amount : amount.negate();
                changeBalance(from, date, item.name(), change, false, item.id());
            }
            if (to != null && appliesTo(to, date)) {
                // Money arriving in a cash account raises it; arriving at a debt pays it down.
                BigDecimal change = to.type().isDebt() ? amount.negate() : amount;
                changeBalance(to, date, item.name(), change, false, item.id());
            }
        }

        /**
         * How much this occurrence pays: the fixed amount, or for a card-minimum payment,
         * the card's minimum on what's owed right now.
         */
        private BigDecimal amountThisTime(ScheduledItem item, Account to) {
            if (!item.followsCardMinimum()) {
                return item.amount();
            }
            if (to != null && to.type().isDebt()) {
                return to.minimumPaymentOn(balances.get(to.id()));
            }
            return BigDecimal.ZERO; // a card minimum needs a card to pay into
        }

        /**
         * Before anything is applied: note each debt's regular payment (a card minimum at
         * today's balance), so the snowball rollover knows how much to move once it's paid off.
         */
        private void noteRegularPayments(List<ScheduledItem> items) {
            for (ScheduledItem item : items) {
                Account debt = item.toAccountId() == null ? null : accountsById.get(item.toAccountId());
                if (debt == null || !debt.type().isDebt()) {
                    continue;
                }
                BigDecimal regular = item.followsCardMinimum()
                        ? debt.minimumPaymentOn(debt.balance())
                        : item.amount();
                regularPayments.merge(debt.id(), regular, BigDecimal::add);
            }
        }

        /**
         * One budget bucket's share of a paycheck:
         * <ul>
         *   <li><b>Spent:</b> it just leaves the account.</li>
         *   <li><b>Into an account:</b> it moves into that account (e.g. Savings), so that balance
         *       grows. If the account is gone, it just leaves, like spent money.</li>
         *   <li><b>Pays down debt:</b> the one debt it names, or else highest interest rate first,
         *       moving on as each is paid off. Whatever no debt needs stays in the account.</li>
         * </ul>
         */
        private void applyBudgetMove(LocalDate date, BudgetMove move) {
            Account from = accountsById.get(move.fromAccountId());
            if (from == null || !appliesTo(from, date)) {
                return;
            }
            switch (move.destination()) {
                case DEBT -> payDownDebts(date, from, move.amount(), move.accountId(), move.description(), null);
                case ACCOUNT -> {
                    changeBalance(from, date, move.description(), move.amount().negate(), false, null);
                    Account into = move.accountId() == null ? null : accountsById.get(move.accountId());
                    if (into != null && appliesTo(into, date)) {
                        changeBalance(into, date, move.description(), move.amount(), false, null);
                    }
                }
                case SPENT -> changeBalance(from, date, move.description(), move.amount().negate(), false, null);
            }
        }

        /**
         * The snowball rollover: {@code item} pays a debt that's already paid off, so its usual
         * payment goes to the highest-rate debt still owed instead. A card minimum rolls at what
         * it was when the forecast started (e.g. a card's $300 minimum), not the tiny minimum at the end.
         */
        private void rollPaymentOver(LocalDate date, ScheduledItem item, Account from, Account paidOff) {
            if (from == null || !appliesTo(from, date)) {
                return;
            }
            BigDecimal usualPayment = regularPayments.getOrDefault(paidOff.id(), BigDecimal.ZERO);
            payDownDebts(date, from, usualPayment, null, item.name() + " (rolled over)", item.id());
        }

        /**
         * Pays {@code amount} out of {@code from} toward debts: only {@code onlyDebtId} if given,
         * otherwise highest interest rate first, moving to the next as each is paid off. Whatever
         * can't be used (nothing left owed) stays in {@code from}.
         */
        private void payDownDebts(LocalDate date, Account from, BigDecimal amount, Integer onlyDebtId,
                                  String description, Integer itemId) {
            BigDecimal left = amount;
            while (left.signum() > 0) {
                Account debt = onlyDebtId != null
                        ? chosenDebtStillOwed(onlyDebtId, date)
                        : highestRateDebtStillOwed(date);
                if (debt == null) {
                    break; // nothing left to pay; the rest stays in the account
                }
                BigDecimal payment = left.min(balances.get(debt.id()));
                changeBalance(from, date, description + " → " + debt.name(), payment.negate(), false, itemId);
                changeBalance(debt, date, description, payment.negate(), false, itemId);
                left = left.subtract(payment);
            }
        }

        /** The debt the bucket picked, if it still has a balance (and is counted on this date). */
        private Account chosenDebtStillOwed(int debtId, LocalDate date) {
            Account debt = accountsById.get(debtId);
            boolean stillOwed = debt != null && debt.type().isDebt() && appliesTo(debt, date)
                    && balances.get(debtId).signum() > 0;
            return stillOwed ? debt : null;
        }

        /** The debt with the highest interest rate that still has a balance (ties: the smaller balance first). */
        private Account highestRateDebtStillOwed(LocalDate date) {
            return accountsById.values().stream()
                    .filter(account -> account.type().isDebt() && appliesTo(account, date))
                    .filter(account -> balances.get(account.id()).signum() > 0)
                    .max(Comparator.comparing(Account::interestRatePercent)
                            .thenComparing(account -> balances.get(account.id()), Comparator.reverseOrder()))
                    .orElse(null);
        }

        private void chargeInterest(LocalDate date, Account debt) {
            BigDecimal owed = balances.get(debt.id());
            if (owed.signum() <= 0) {
                return; // Nothing owed, no interest.
            }
            String description = "Interest (" + debt.interestRatePercent().stripTrailingZeros().toPlainString() + "%)";
            changeBalance(debt, date, description, debt.monthlyInterestOn(owed), true, null);
        }

        /**
         * The one place balances change. Also records the ledger line, low point and payoff date.
         *
         * @param interest true for an interest charge, so the Debt tab can add up interest separately
         * @param itemId   the bill or paycheck that caused it, or null for interest
         */
        private void changeBalance(Account account, LocalDate date, String description, BigDecimal change,
                                   boolean interest, Integer itemId) {
            BigDecimal newBalance = balances.get(account.id()).add(change);
            balances.put(account.id(), newBalance);
            ledger.add(new LedgerEntry(date, description, account.id(), change, newBalance, interest, itemId));

            if (account.type().isDebt()) {
                if (newBalance.signum() <= 0 && !payoffDates.containsKey(account.id())) {
                    payoffDates.put(account.id(), date);
                }
            } else {
                Forecast.LowPoint low = lowPoints.get(account.id());
                if (newBalance.compareTo(low.balance()) < 0) {
                    // An unticked bill from before the balance update hits the balance as it is now, so the
                    // low comes no earlier than the balance date.
                    LocalDate when = date.isBefore(account.balanceDate()) ? account.balanceDate() : date;
                    lowPoints.put(account.id(), new Forecast.LowPoint(newBalance, when));
                }
            }
        }

        /**
         * True if something on {@code date} changes {@code account}: anything from the day tracking
         * began. Not just from the account's balance date: a bill you haven't ticked still counts after
         * a balance update, because it's only assumed paid once you tick it.
         */
        private boolean appliesTo(Account account, LocalDate date) {
            return !date.isBefore(countFrom);
        }
    }

    /**
     * One thing that happens on one day: a scheduled item, a monthly interest charge on a
     * debt, or a budget split. Exactly one of {@code item}, {@code interestOn} and
     * {@code budgetMove} is set.
     */
    private record Event(LocalDate date, ScheduledItem item, Account interestOn, BudgetMove budgetMove) {

        static Event forItem(LocalDate date, ScheduledItem item) {
            return new Event(date, item, null, null);
        }

        static Event forInterest(LocalDate date, Account debt) {
            return new Event(date, null, debt, null);
        }

        static Event forBudget(BudgetMove move) {
            return new Event(move.date(), null, null, move);
        }

        boolean isInterest() {
            return interestOn != null;
        }

        boolean isBudget() {
            return budgetMove != null;
        }

        /**
         * Order within a single day: money in, then the budget split (it comes out of that
         * paycheck), then interest, then transfers, then bills.
         */
        int sameDayOrder() {
            if (isBudget()) {
                return 1;
            }
            if (isInterest()) {
                return 2;
            }
            return switch (item.kind()) {
                case INCOME -> 0;
                case TRANSFER -> 3;
                case BILL -> 4;
            };
        }
    }
}
