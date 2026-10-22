package com.finance.runwayd.core.bills;

import com.finance.runwayd.core.Workspace;
import com.finance.runwayd.core.forecast.Forecasts;
import com.finance.runwayd.core.forecast.ScheduleCalculator;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.ScheduledItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Your scheduled items (bills, paychecks, transfers and debt payments): adding and changing them,
 * their totals, and marking them paid.
 *
 * <p><b>Paid</b> is remembered as one date per item, {@code paidThrough}: every date up to it is
 * done, so the forecast skips it. Balances aren't changed; you update those from your bank.
 */
public final class Bills {

    /**
     * Paying a bill this many days or more before it's due asks "are you sure?" first, so a few
     * extra clicks can't quietly skip months of bills or paychecks.
     */
    public static final int PAYING_AHEAD_WARNING_DAYS = 31;

    private final Workspace workspace;
    private final Forecasts forecasts;

    public Bills(Workspace workspace, Forecasts forecasts) {
        this.workspace = workspace;
        this.forecasts = forecasts;
    }

    // -------------------------------------------------------------------------
    // The items themselves
    // -------------------------------------------------------------------------

    /** All items, income first and then the biggest bills. */
    public List<ScheduledItem> all() {
        return workspace.items().stream()
                .sorted(Comparator.comparing(ScheduledItem::kind)
                        .thenComparing(this::amountEachTime, Comparator.reverseOrder())
                        .thenComparing(ScheduledItem::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Saves a new item. The id on {@code draft} is ignored; the saved copy with its real id is returned. */
    public ScheduledItem add(ScheduledItem draft) {
        checkAccountsExist(draft);
        ScheduledItem saved = draft.withId(Workspace.nextId(workspace.items(), ScheduledItem::id));
        workspace.items().add(saved);
        workspace.save();
        return saved;
    }

    /** Replaces the item that has the same id. */
    public void update(ScheduledItem changed) {
        checkAccountsExist(changed);
        workspace.items().set(workspace.indexOfItem(changed.id()), changed);
        workspace.save();
    }

    public void delete(int id) {
        workspace.items().remove(workspace.indexOfItem(id));
        workspace.save();
    }

    private void checkAccountsExist(ScheduledItem item) {
        for (Integer accountId : new Integer[] {item.fromAccountId(), item.toAccountId()}) {
            if (accountId != null && workspace.findAccount(accountId).isEmpty()) {
                throw new IllegalArgumentException("Account #" + accountId + " doesn't exist.");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Amounts and totals
    // -------------------------------------------------------------------------

    /**
     * What the item pays each time, right now (always positive): its fixed amount,
     * or for a card-minimum payment, the minimum on today's balance.
     * The forecast recalculates card minimums every month; this is today's number for display.
     */
    public BigDecimal amountEachTime(ScheduledItem item) {
        if (!item.followsCardMinimum()) {
            return item.amount();
        }
        Account card = workspace.findAccount(item.toAccountId()).orElseThrow();
        return card.minimumPaymentOn(forecasts.today().balanceOf(card.id()));
    }

    /** {@link #amountEachTime} signed like a bank statement: income +, bills and payments -. */
    public BigDecimal signedAmount(ScheduledItem item) {
        return item.signed(amountEachTime(item));
    }

    /**
     * What a group of items adds up to per month on average: income positive, bills negative.
     * Used for the category totals on the Bills tab, e.g. "Housing: -$3,069.00 / month".
     */
    public BigDecimal monthlyTotal(Collection<ScheduledItem> group) {
        return group.stream()
                .map(item -> item.perMonth(amountEachTime(item)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * What a group of items adds up to per year: income positive, bills negative. Uses each
     * item's real yearly cost, so a $139 yearly bill counts as $139 (not $11.58 × 12).
     */
    public BigDecimal yearlyTotal(Collection<ScheduledItem> group) {
        return group.stream()
                .map(item -> item.perYear(amountEachTime(item)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // -------------------------------------------------------------------------
    // Paying
    // -------------------------------------------------------------------------

    /**
     * The next date of this item that the forecast still counts as unpaid.
     *
     * <p>That's the first occurrence since tracking began that isn't ticked off (after its paid-through
     * date). Updating a balance doesn't change it: nothing counts as paid until you tick it. It can be in
     * the past, which means it's overdue. Empty if the item has ended.
     */
    public Optional<LocalDate> nextDue(ScheduledItem item) {
        if (item.frequency() == Frequency.ONCE) {
            // A one-time bill is due on its date until it's paid, whenever that is.
            return paidOn(item).isPresent() ? Optional.empty() : Optional.of(item.firstDate());
        }
        LocalDate countedAfter = countedFrom().map(since -> since.minusDays(1)).orElse(item.firstDate().minusDays(1));
        // A little over a year ahead is enough to find the next date of even a yearly bill.
        LocalDate searchUntil = LocalDate.now().plusMonths(13);
        return ScheduleCalculator.datesBetween(item, countedAfter, searchUntil).stream().findFirst();
    }

    /**
     * Ticks a bill off (or unticks it) on the checklist. Ticking a date also ticks any earlier
     * ones of the same bill, and unticking a date unticks it and everything after it.
     */
    public void setPaid(int itemId, LocalDate date, boolean paid) {
        int index = workspace.indexOfItem(itemId);
        ScheduledItem item = workspace.items().get(index);
        LocalDate paidThrough = item.paidThrough();
        if (paid && (paidThrough == null || date.isAfter(paidThrough))) {
            paidThrough = date;
        } else if (!paid && paidThrough != null && !date.isAfter(paidThrough)) {
            paidThrough = previousDate(item, date);
        } else {
            return; // already the way you want it
        }
        workspace.items().set(index, item.withPaidThrough(paidThrough));
        workspace.save();
    }

    /**
     * Marks the item's next due occurrence as paid, so the forecast stops counting it.
     *
     * @return the date that was marked paid (for "Marked Rent paid for Nov 1. Undo"), or empty
     *         if the item has no date left to pay
     */
    public Optional<LocalDate> markPaid(int itemId) {
        int index = workspace.indexOfItem(itemId);
        ScheduledItem item = workspace.items().get(index);
        Optional<LocalDate> due = nextDue(item);
        due.ifPresent(dueDate -> {
            workspace.items().set(index, item.withPaidThrough(dueDate));
            workspace.save();
        });
        return due;
    }

    /** True if the item's next due date is far enough out that marking it paid should be confirmed. */
    public boolean wouldPayFarAhead(ScheduledItem item) {
        return nextDue(item)
                .map(due -> !due.isBefore(LocalDate.now().plusDays(PAYING_AHEAD_WARNING_DAYS)))
                .orElse(false);
    }

    /**
     * The date the item is marked paid up to, if that's still in the future, e.g. a paycheck marked
     * received for next month already. Empty if nothing is marked ahead.
     */
    public Optional<LocalDate> paidAheadTo(ScheduledItem item) {
        LocalDate paidThrough = item.paidThrough();
        return paidThrough != null && paidThrough.isAfter(LocalDate.now()) ? Optional.of(paidThrough) : Optional.empty();
    }

    /**
     * True if the item has a "mark paid" that can still be undone: one that the forecast is
     * skipping. (Dates before tracking began never count, so unmarking them would change nothing.)
     */
    public boolean canUndoPaid(ScheduledItem item) {
        if (item.paidThrough() == null) {
            return false;
        }
        if (item.frequency() == Frequency.ONCE) {
            return true; // a paid one-time bill can always be put back
        }
        return !item.paidThrough().isBefore(countedFrom().orElse(LocalDate.MIN));
    }

    /** The day tracking began: nothing earlier counts (see Workspace.trackingSince). */
    private Optional<LocalDate> countedFrom() {
        return Optional.ofNullable(workspace.trackingSince());
    }

    /**
     * The day a one-time bill was paid (its date), or empty if it isn't paid yet or repeats.
     * Shown on the Bills tab as "Paid Oct 21".
     */
    public Optional<LocalDate> paidOn(ScheduledItem item) {
        boolean paid = item.frequency() == Frequency.ONCE && item.paidThrough() != null
                && !item.firstDate().isAfter(item.paidThrough());
        return paid ? Optional.of(item.firstDate()) : Optional.empty();
    }

    /**
     * True if the bill is finished (nothing left due) and its month is over, so the Bills tab leaves
     * it out: a one-time bill paid last month, or a repeating bill whose end date was last month.
     * It isn't deleted; it just stops cluttering the list. This month's stay, so a mis-click can be undone.
     */
    public boolean droppedOff(ScheduledItem item, LocalDate today) {
        if (nextDue(item).isPresent()) {
            return false;
        }
        LocalDate finished = paidOn(item).orElse(item.endDate());
        return finished != null && finished.isBefore(today.withDayOfMonth(1));
    }

    /**
     * Takes back the last "mark paid": the latest date marked paid counts as unpaid again, so the
     * forecast includes it. Undo again to step back one more.
     *
     * @return the date that's unpaid again, or empty if there was nothing to undo
     */
    public Optional<LocalDate> undoLastPaid(int itemId) {
        int index = workspace.indexOfItem(itemId);
        ScheduledItem item = workspace.items().get(index);
        if (!canUndoPaid(item)) {
            return Optional.empty();
        }
        LocalDate unpaid = item.paidThrough();
        workspace.items().set(index, item.withPaidThrough(previousDate(item, unpaid)));
        workspace.save();
        return Optional.of(unpaid);
    }

    /** The occurrence just before {@code date}, or null if there isn't one in the last two years. */
    private static LocalDate previousDate(ScheduledItem item, LocalDate date) {
        List<LocalDate> earlier = ScheduleCalculator.datesBetween(item.withPaidThrough(null),
                date.minusYears(2), date.minusDays(1));
        return earlier.isEmpty() ? null : earlier.get(earlier.size() - 1);
    }
}
