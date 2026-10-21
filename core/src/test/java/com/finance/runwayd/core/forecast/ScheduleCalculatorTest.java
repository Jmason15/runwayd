package com.finance.runwayd.core.forecast;

import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.ScheduledItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduleCalculatorTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);

    @Test
    void monthlyBillOnThe31stLandsOnTheLastDayOfShortMonths() {
        ScheduledItem rent = bill(Frequency.MONTHLY, LocalDate.of(2026, 1, 31), null);

        List<LocalDate> dates = ScheduleCalculator.datesBetween(rent, JAN_1, LocalDate.of(2026, 4, 30));

        assertEquals(List.of(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),   // February is short...
                LocalDate.of(2026, 3, 31),   // ...but March goes back to the 31st
                LocalDate.of(2026, 4, 30)), dates);
    }

    @Test
    void startDayIsExcludedAndEndDayIsIncluded() {
        ScheduledItem bill = bill(Frequency.MONTHLY, LocalDate.of(2026, 1, 10), null);

        // Balance taken on Feb 10 already includes the Feb 10 bill, so it shouldn't be counted again.
        List<LocalDate> dates = ScheduleCalculator.datesBetween(bill, LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 10));

        assertEquals(List.of(LocalDate.of(2026, 3, 10)), dates);
    }

    @Test
    void everyTwoWeeksCountsFromTheFirstDate() {
        ScheduledItem groceries = bill(Frequency.EVERY_2_WEEKS, LocalDate.of(2025, 12, 31), null);

        List<LocalDate> dates = ScheduleCalculator.datesBetween(groceries, JAN_1, LocalDate.of(2026, 2, 1));

        assertEquals(List.of(LocalDate.of(2026, 1, 14), LocalDate.of(2026, 1, 28)), dates);
    }

    @Test
    void twiceMonthlyIsAlwaysThe1stAnd15th() {
        ScheduledItem pay = bill(Frequency.TWICE_MONTHLY, LocalDate.of(2026, 1, 10), null);

        List<LocalDate> dates = ScheduleCalculator.datesBetween(pay, JAN_1, LocalDate.of(2026, 2, 28));

        // Jan 1 is before the first date (Jan 10), so the first payday is Jan 15.
        assertEquals(List.of(
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 15)), dates);
    }

    @Test
    void endDateStopsTheSchedule() {
        ScheduledItem loan = bill(Frequency.MONTHLY, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 3, 5));

        List<LocalDate> dates = ScheduleCalculator.datesBetween(loan, JAN_1, LocalDate.of(2026, 12, 31));

        assertEquals(3, dates.size());
        assertEquals(LocalDate.of(2026, 3, 5), dates.get(2));
    }

    @Test
    void oneTimeItemOnlyHappensOnce() {
        ScheduledItem repair = bill(Frequency.ONCE, LocalDate.of(2026, 6, 1), null);

        assertEquals(List.of(LocalDate.of(2026, 6, 1)),
                ScheduleCalculator.datesBetween(repair, JAN_1, LocalDate.of(2027, 1, 1)));
        assertEquals(List.of(),
                ScheduleCalculator.datesBetween(repair, LocalDate.of(2026, 6, 1), LocalDate.of(2027, 1, 1)));
    }

    @Test
    void datesMarkedPaidAreSkipped() {
        ScheduledItem rent = bill(Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null)
                .withPaidThrough(LocalDate.of(2026, 2, 1));

        List<LocalDate> dates = ScheduleCalculator.datesBetween(rent, LocalDate.of(2025, 12, 31), LocalDate.of(2026, 3, 31));

        assertEquals(List.of(LocalDate.of(2026, 3, 1)), dates);
    }

    private static ScheduledItem bill(Frequency frequency, LocalDate first, LocalDate end) {
        return new ScheduledItem(1, "Test bill", new BigDecimal("10.00"), frequency, first, end, 1, null);
    }
}
