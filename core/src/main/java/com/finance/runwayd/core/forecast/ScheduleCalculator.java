package com.finance.runwayd.core.forecast;

import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.ScheduledItem;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out which days a scheduled item actually happens on.
 *
 * <p>Example: rent is MONTHLY starting 2026-01-01. Asking for the dates
 * after 2026-02-15 through 2026-05-01 gives Mar 1, Apr 1 and May 1.
 */
public final class ScheduleCalculator {

    private ScheduleCalculator() {
        // Only static helpers here; nothing to create.
    }

    /**
     * Every date the item happens that is strictly AFTER {@code after}
     * and on or before {@code through}.
     *
     * <p>"After" is exclusive on purpose: an account balance taken on a day
     * already includes that day's bills, so the forecast must not count them twice.
     *
     * <p>Dates on or before the item's {@code paidThrough} are skipped too. They've
     * been marked paid.
     */
    public static List<LocalDate> datesBetween(ScheduledItem item, LocalDate after, LocalDate through) {
        if (item.paidThrough() != null && item.paidThrough().isAfter(after)) {
            after = item.paidThrough();
        }
        // Stop early if the item has an end date before our window ends.
        LocalDate last = item.endDate() != null && item.endDate().isBefore(through)
                ? item.endDate()
                : through;

        return switch (item.frequency()) {
            case ONCE -> onceDates(item.firstDate(), after, last);
            case TWICE_MONTHLY -> firstAndFifteenthDates(item.firstDate(), after, last);
            default -> steppedDates(item.frequency(), item.firstDate(), after, last);
        };
    }

    /** A one-time item happens on its first date, if that date is in the window. */
    private static List<LocalDate> onceDates(LocalDate date, LocalDate after, LocalDate last) {
        return inWindow(date, after, last) ? List.of(date) : List.of();
    }

    /** Weekly, monthly, yearly and so on: occurrence 0, 1, 2, ... counted from the first date. */
    private static List<LocalDate> steppedDates(Frequency frequency, LocalDate first, LocalDate after, LocalDate last) {
        List<LocalDate> dates = new ArrayList<>();
        for (int n = 0; ; n++) {
            LocalDate date = frequency.nthDate(first, n);
            if (date.isAfter(last)) {
                break;
            }
            if (date.isAfter(after)) {
                dates.add(date);
            }
        }
        return dates;
    }

    /** Twice a month always means the 1st and the 15th, starting from the first one on or after {@code first}. */
    private static List<LocalDate> firstAndFifteenthDates(LocalDate first, LocalDate after, LocalDate last) {
        List<LocalDate> dates = new ArrayList<>();
        // Skip ahead to whichever month is later: the item's start, or the window's start.
        LocalDate month = (first.isAfter(after) ? first : after).withDayOfMonth(1);

        while (!month.isAfter(last)) {
            for (LocalDate date : List.of(month, month.withDayOfMonth(15))) {
                if (!date.isBefore(first) && inWindow(date, after, last)) {
                    dates.add(date);
                }
            }
            month = month.plusMonths(1);
        }
        return dates;
    }

    private static boolean inWindow(LocalDate date, LocalDate after, LocalDate last) {
        return date.isAfter(after) && !date.isAfter(last);
    }
}
