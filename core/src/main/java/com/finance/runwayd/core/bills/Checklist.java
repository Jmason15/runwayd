package com.finance.runwayd.core.bills;

import com.finance.runwayd.core.Workspace;
import com.finance.runwayd.core.budget.BudgetPlan;
import com.finance.runwayd.core.budget.BudgetPlans;
import com.finance.runwayd.core.forecast.Forecast;
import com.finance.runwayd.core.forecast.Forecasts;
import com.finance.runwayd.core.forecast.LedgerEntry;
import com.finance.runwayd.core.forecast.ScheduleCalculator;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.Budget;
import com.finance.runwayd.core.model.BudgetBucket;
import com.finance.runwayd.core.model.ScheduledItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Forecast tab's checklist ("This month", or up to a year ahead): every bill and paycheck due, each budget
 * bucket's share of each payday, and anything from earlier still not ticked off.
 */
public final class Checklist {

    private final Workspace workspace;
    private final BudgetPlans budgetPlans;
    private final Forecasts forecasts;

    public Checklist(Workspace workspace, BudgetPlans budgetPlans, Forecasts forecasts) {
        this.workspace = workspace;
        this.budgetPlans = budgetPlans;
        this.forecasts = forecasts;
    }

    /** The checklist for one month. See {@link #forMonths}. */
    public List<ChecklistItem> forMonth(YearMonth month) {
        return forMonths(month, month);
    }

    /**
     * The checklist from {@code first} through {@code last} (e.g. this month through next September):
     * every bill and paycheck due in those months with its status, the budget buckets' lines, plus
     * anything from before {@code first} that still isn't ticked off (so nothing gets lost).
     * Soonest first; on the same day, the paycheck, then its budget buckets (in Budget tab order),
     * then the bills by name.
     */
    public List<ChecklistItem> forMonths(YearMonth first, YearMonth last) {
        LocalDate today = LocalDate.now();
        LocalDate firstOfMonth = first.atDay(1);
        LocalDate endOfLast = last.atEndOfMonth();
        List<ChecklistItem> checklist = new ArrayList<>();
        // Nothing before tracking began counts, so it isn't listed (ticked or not).
        LocalDate since = workspace.trackingSince() != null ? workspace.trackingSince() : firstOfMonth;
        // Look back to that day too, for ones from earlier months still not ticked.
        LocalDate from = since.isBefore(firstOfMonth) ? since : firstOfMonth;
        for (ScheduledItem item : workspace.items()) {
            // Ask for every date, ticked or not (the schedule normally skips the ticked ones).
            for (LocalDate date : ScheduleCalculator.datesBetween(item.withPaidThrough(null), from.minusDays(1),
                    endOfLast)) {
                if (date.isBefore(since)) {
                    continue;
                }
                ChecklistItem.Status status = statusOf(item, date, today);
                boolean inRange = !date.isBefore(firstOfMonth);
                if (inRange || status == ChecklistItem.Status.OVERDUE) {
                    checklist.add(new ChecklistItem(item, date, status));
                }
            }
        }
        checklist.addAll(budgetLines(firstOfMonth, endOfLast, today));

        // What each line's account holds right after it, and how much it actually moved, read from the forecast.
        Forecast forecast = forecasts.on(endOfLast);
        List<LedgerEntry> ledger = forecast.ledger();
        List<ChecklistItem> withBalances = new ArrayList<>();
        Map<ChecklistItem, Integer> ledgerOrder = new HashMap<>();
        for (ChecklistItem entry : checklist) {
            int account = accountOf(entry);
            int index = -1;          // its last ledger line, for the balance after it
            BigDecimal moved = null; // all its ledger lines added up (a debt bucket can pay several debts)
            for (int i = 0; i < ledger.size(); i++) {
                if (isLineFor(entry, account, ledger.get(i))) {
                    index = i;
                    moved = moved == null ? ledger.get(i).change() : moved.add(ledger.get(i).change());
                }
            }
            ChecklistItem filled = entry.withForecast(account, index < 0 ? null : ledger.get(index).balanceAfter(), moved);
            withBalances.add(filled);
            ledgerOrder.put(filled, index < 0 ? Integer.MAX_VALUE : index);
        }

        // Soonest first. On the same day: income, then budget buckets, then bills and payments in the
        // order the forecast applies them, so "Balance after" reads like a running balance.
        List<BudgetBucket> buckets = workspace.budget().buckets();
        withBalances.sort(Comparator.comparing(ChecklistItem::date)
                .thenComparingInt(entry -> entry.isBudgetSplit() ? 1 : entry.item().kind() == ScheduledItem.Kind.INCOME ? 0 : 2)
                .thenComparingInt(entry -> entry.isBudgetSplit() ? buckets.indexOf(entry.bucket()) : 0)
                .thenComparingInt(ledgerOrder::get)
                .thenComparing(entry -> entry.isBudgetSplit() ? "" : entry.item().name(), String.CASE_INSENSITIVE_ORDER));
        return withBalances;
    }

    /** The account a line comes out of (or, for income, goes into): the budget's account for a bucket line. */
    private int accountOf(ChecklistItem entry) {
        if (entry.isBudgetSplit()) {
            return budgetPlans.budgetAccount().map(Account::id).orElse(-1);
        }
        return entry.item().mainAccountId();
    }

    /**
     * True if {@code line} is this checklist line's change to {@code account}: a bill is found by its id; a
     * bucket by its "Budget: Savings" description (a debt bucket can pay several debts in a row).
     */
    private static boolean isLineFor(ChecklistItem entry, int account, LedgerEntry line) {
        if (line.accountId() != account || !line.date().equals(entry.date())) {
            return false;
        }
        return entry.isBudgetSplit()
                ? line.itemId() == null && (line.description().equals("Budget: " + entry.bucket().name())
                        || line.description().startsWith("Budget: " + entry.bucket().name() + " → "))
                : Integer.valueOf(entry.item().id()).equals(line.itemId());
    }

    /**
     * Ticked (PAID) only if you ticked it; nothing else ticks a bill, and nothing assumes it's paid.
     * Updating a balance doesn't either: an unticked bill from before then is still OVERDUE, and still
     * counted in the forecast, until you tick it.
     */
    private static ChecklistItem.Status statusOf(ScheduledItem item, LocalDate date, LocalDate today) {
        if (item.paidThrough() != null && !date.isAfter(item.paidThrough())) {
            return ChecklistItem.Status.PAID;
        }
        if (date.isBefore(today)) {
            return ChecklistItem.Status.OVERDUE;
        }
        return date.equals(today) ? ChecklistItem.Status.DUE_TODAY : ChecklistItem.Status.UPCOMING;
    }

    // -------------------------------------------------------------------------
    // Budget bucket lines
    // -------------------------------------------------------------------------

    /**
     * One line per bucket per payday from {@code from} to {@code to} (plus any earlier one not ticked off yet), so each
     * transfer can be ticked as you make it. Only when the budget is part of the forecast (otherwise
     * nothing is taken out, so there's nothing to tick), and only buckets that get something.
     */
    private List<ChecklistItem> budgetLines(LocalDate from, LocalDate to, LocalDate today) {
        if (!budgetPlans.inForecast()) {
            return List.of();
        }
        Budget budget = workspace.budget();
        List<BudgetBucket> buckets = budget.buckets();
        Forecast forecast = null; // only needed to name the debt a "highest rate" bucket pays
        List<ChecklistItem> lines = new ArrayList<>();
        for (BudgetPlan.PaycheckSplit split : budgetPlans.planThrough(to, Integer.MAX_VALUE).paychecks()) {
            LocalDate payday = split.date();
            if (payday.isAfter(to)) {
                continue;
            }
            for (int i = 0; i < buckets.size() && i < split.bucketAmounts().size(); i++) {
                BudgetBucket bucket = buckets.get(i);
                BigDecimal amount = split.bucketAmounts().get(i);
                if (amount.signum() <= 0) {
                    continue;
                }
                ChecklistItem.Status status = bucketStatus(budget, bucket, payday, today);
                boolean inRange = !payday.isBefore(from);
                if (!inRange && status != ChecklistItem.Status.OVERDUE) {
                    continue;
                }
                String goesTo = switch (bucket.destination()) {
                    case SPENT -> null;
                    case ACCOUNT -> workspace.findAccount(bucket.accountId()).map(Account::name).orElse(null);
                    case DEBT -> {
                        if (bucket.accountId() != null) {
                            yield workspace.findAccount(bucket.accountId()).map(Account::name).orElse("highest-rate debt");
                        }
                        if (forecast == null) {
                            forecast = forecasts.on(to);
                        }
                        yield debtsPaidBy(forecast, bucket, payday).orElse("highest-rate debt");
                    }
                };
                lines.add(ChecklistItem.forBucket(bucket, amount, goesTo, payday, status));
            }
        }
        return lines;
    }

    private static ChecklistItem.Status bucketStatus(Budget budget, BudgetBucket bucket, LocalDate payday,
                                                     LocalDate today) {
        LocalDate doneThrough = budget.doneThroughFor(bucket.name());
        if (doneThrough != null && !payday.isAfter(doneThrough)) {
            return ChecklistItem.Status.PAID;
        }
        if (payday.isBefore(today)) {
            return ChecklistItem.Status.OVERDUE;
        }
        return payday.equals(today) ? ChecklistItem.Status.DUE_TODAY : ChecklistItem.Status.UPCOMING;
    }

    /**
     * Which debt(s) a "highest rate first" bucket pays on {@code payday}, read from the forecast,
     * e.g. "Visa", or "Visa, Mastercard" if one gets paid off and the rest spills over.
     */
    private Optional<String> debtsPaidBy(Forecast forecast, BudgetBucket bucket, LocalDate payday) {
        String prefix = "Budget: " + bucket.name() + " → ";
        List<String> debts = budgetPlans.budgetAccount().stream()
                .flatMap(account -> forecast.ledgerFor(account.id()).stream())
                .filter(entry -> entry.date().equals(payday) && entry.description().startsWith(prefix))
                .map(entry -> entry.description().substring(prefix.length()))
                .distinct()
                .toList();
        return debts.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", debts));
    }
}
