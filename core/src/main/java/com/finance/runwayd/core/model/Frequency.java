package com.finance.runwayd.core.model;

import java.time.LocalDate;

/**
 * How often a bill, paycheck or transfer happens.
 *
 * <p>Each frequency knows how to find its Nth date counting from the first one.
 * We always count from the FIRST date instead of adding to the previous one,
 * because months have different lengths. For example, a monthly bill that starts
 * Jan 31 lands on Feb 28, then Mar 31, not Mar 28. {@link LocalDate#plusMonths}
 * handles the short months for us.
 *
 * <p>TWICE_MONTHLY is the odd one out. It always lands on the 1st and 15th, so
 * the schedule code handles it separately (see ScheduleCalculator).
 */
public enum Frequency {
    ONCE("One time", 0),
    WEEKLY("Weekly", 52),
    EVERY_2_WEEKS("Every 2 weeks", 26),
    TWICE_MONTHLY("Twice a month (1st & 15th)", 24),
    MONTHLY("Monthly", 12),
    EVERY_2_MONTHS("Every 2 months", 6),
    EVERY_3_MONTHS("Every 3 months", 4),
    EVERY_6_MONTHS("Every 6 months", 2),
    YEARLY("Yearly", 1);

    private final String label;
    private final int timesPerYear;

    Frequency(String label, int timesPerYear) {
        this.label = label;
        this.timesPerYear = timesPerYear;
    }

    /** Friendly name for showing on screen. */
    public String label() {
        return label;
    }

    /** Roughly how many times a year this happens. Used for "per month" averages. */
    public int timesPerYear() {
        return timesPerYear;
    }

    /**
     * The date of occurrence number {@code n}, where n = 0 is {@code first}.
     *
     * @throws UnsupportedOperationException for ONCE and TWICE_MONTHLY, which don't step evenly
     */
    public LocalDate nthDate(LocalDate first, int n) {
        return switch (this) {
            case WEEKLY -> first.plusWeeks(n);
            case EVERY_2_WEEKS -> first.plusWeeks(2L * n);
            case MONTHLY -> first.plusMonths(n);
            case EVERY_2_MONTHS -> first.plusMonths(2L * n);
            case EVERY_3_MONTHS -> first.plusMonths(3L * n);
            case EVERY_6_MONTHS -> first.plusMonths(6L * n);
            case YEARLY -> first.plusYears(n);
            case ONCE, TWICE_MONTHLY ->
                    throw new UnsupportedOperationException(this + " has no fixed step; see ScheduleCalculator");
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
