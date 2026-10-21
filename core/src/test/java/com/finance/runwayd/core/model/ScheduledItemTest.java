package com.finance.runwayd.core.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduledItemTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final int CHECKING = 1;

    @Test
    void itemsWithoutACategoryDefaultToIncomeOrOther() {
        ScheduledItem pay = new ScheduledItem(1, "Pay", new BigDecimal("100"), Frequency.MONTHLY, JAN_1, null, null, CHECKING);
        ScheduledItem rent = new ScheduledItem(2, "Rent", new BigDecimal("100"), Frequency.MONTHLY, JAN_1, null, CHECKING, null);

        assertEquals(Category.INCOME, pay.category());
        assertEquals(Category.OTHER, rent.category());
    }

    @Test
    void billsAreNegativeAndIncomeIsPositive() {
        ScheduledItem pay = new ScheduledItem(1, "Pay", new BigDecimal("100.00"), Frequency.MONTHLY, JAN_1, null, null, CHECKING);
        ScheduledItem rent = new ScheduledItem(2, "Rent", new BigDecimal("100.00"), Frequency.MONTHLY, JAN_1, null, CHECKING, null);

        assertEquals(new BigDecimal("100.00"), pay.signed(pay.amount()));
        assertEquals(new BigDecimal("-100.00"), rent.signed(rent.amount()));
    }

    @Test
    void perMonthSpreadsOtherFrequenciesOverTheYear() {
        ScheduledItem groceries = new ScheduledItem(1, "Groceries", new BigDecimal("300"), Frequency.EVERY_2_WEEKS,
                JAN_1, null, CHECKING, null);
        ScheduledItem amazon = new ScheduledItem(2, "Amazon", new BigDecimal("139"), Frequency.YEARLY,
                JAN_1, null, CHECKING, null);
        ScheduledItem repair = new ScheduledItem(3, "Repair", new BigDecimal("800"), Frequency.ONCE,
                JAN_1, null, CHECKING, null);

        assertEquals(new BigDecimal("-650.00"), groceries.perMonth(groceries.amount())); // 300 × 26 ÷ 12
        assertEquals(new BigDecimal("-11.58"), amazon.perMonth(amazon.amount()));     // 139 ÷ 12
        assertEquals(new BigDecimal("0.00"), repair.perMonth(repair.amount()));       // one-time: not a monthly cost
    }
}
