package com.finance.runwayd.core.bills;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.core.storage.JsonDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Taking back a "Mark paid" (or "Mark received") that was clicked by mistake. */
class UndoPaidTest {

    @TempDir
    Path tempDir;

    private final LocalDate today = LocalDate.now();
    private FinanceService service;
    private Account checking;
    private ScheduledItem paycheck;

    @BeforeEach
    void setUp() {
        service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        checking = service.addAccount(new Account(0, "Checking", AccountType.CHECKING,
                new BigDecimal("1000.00"), today, null));
        paycheck = service.addItem(new ScheduledItem(0, "Paycheck", new BigDecimal("2450.00"),
                Frequency.MONTHLY, today.plusDays(10), null, null, checking.id()));
    }

    @Test
    void fourClicksTooManyAreUndoneOneAtATime() {
        BigDecimal before = balanceInSixMonths();
        for (int click = 0; click < 4; click++) {
            service.markPaid(paycheck.id());
        }
        assertEquals(before.subtract(new BigDecimal("9800.00")), balanceInSixMonths(), "four paychecks skipped");

        // Undo comes back newest first: the 4th month, then the 3rd, ...
        assertEquals(Optional.of(today.plusDays(10).plusMonths(3)), service.undoLastPaid(paycheck.id()));
        for (int click = 0; click < 3; click++) {
            service.undoLastPaid(paycheck.id());
        }

        assertEquals(before, balanceInSixMonths(), "all four paychecks count again");
        assertEquals(Optional.of(today.plusDays(10)), service.nextDue(current()));
    }

    @Test
    void markPaidSaysWhichDateItMarked() {
        assertEquals(Optional.of(today.plusDays(10)), service.markPaid(paycheck.id()));
    }

    @Test
    void payingAMonthOrMoreAheadIsFlagged() {
        assertFalse(service.wouldPayFarAhead(current()), "due in 10 days: just do it");

        service.markPaid(paycheck.id());

        assertTrue(service.wouldPayFarAhead(current()), "next one is over a month away: ask first");
        assertEquals(Optional.of(today.plusDays(10)), service.paidAheadTo(current()), "shows as paid ahead");
    }

    @Test
    void undoIsRememberedAfterARestart() {
        service.markPaid(paycheck.id());
        service.undoLastPaid(paycheck.id());

        FinanceService reopened = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        assertEquals(Optional.of(today.plusDays(10)), reopened.nextDue(reopened.items().get(0)));
    }

    @Test
    void undoWithNothingMarkedBringsBackTheDateBefore() {
        // Rent has been due monthly for half a year, but tracking began today: next due is in 5 days.
        LocalDate nextRent = today.plusDays(5);
        ScheduledItem rent = service.addItem(new ScheduledItem(0, "Rent", new BigDecimal("900.00"),
                Frequency.MONTHLY, nextRent.minusMonths(6), null, checking.id(), null));
        assertEquals(Optional.of(nextRent), service.nextDue(rent));
        assertEquals(Optional.of(nextRent.minusMonths(1)), service.undoWouldUnpay(rent), "Undo can step back");

        // Undo: last month's rent (from before tracking began) is owed again, and counts.
        assertEquals(Optional.of(nextRent.minusMonths(1)), service.undoLastPaid(rent.id()));
        ScheduledItem steppedBack = itemNamed("Rent");
        assertEquals(Optional.of(nextRent.minusMonths(1)), service.nextDue(steppedBack));
        assertEquals(new BigDecimal("100.00"), service.forecast(today).balanceOf(checking.id()),
                "$1,000 - last month's $900 rent, owed again");
        assertTrue(service.checklist(YearMonth.from(today), YearMonth.from(today)).stream()
                .anyMatch(e -> !e.isBudgetSplit() && e.item().id() == rent.id()
                        && e.date().equals(nextRent.minusMonths(1)) && e.status() == ChecklistItem.Status.OVERDUE),
                "and it's on the checklist as overdue");

        // Again: one more month back. Mark paid then pays the oldest first.
        service.undoLastPaid(rent.id());
        assertEquals(Optional.of(nextRent.minusMonths(2)), service.nextDue(itemNamed("Rent")));
        assertEquals(Optional.of(nextRent.minusMonths(2)), service.markPaid(rent.id()));
        assertEquals(Optional.of(nextRent.minusMonths(1)), service.nextDue(itemNamed("Rent")));
    }

    @Test
    void undoStepsBackPastTheFirstDateItWasEnteredWith() {
        // Rent entered as first due on the 10th of next month. Undo brings back the month before.
        LocalDate firstDue = today.plusMonths(1).withDayOfMonth(10);
        ScheduledItem rent = service.addItem(new ScheduledItem(0, "Rent", new BigDecimal("900.00"),
                Frequency.MONTHLY, firstDue, null, checking.id(), null));
        LocalDate monthBefore = firstDue.minusMonths(1);
        assertEquals(Optional.of(monthBefore), service.undoLastPaid(rent.id()));

        assertEquals(monthBefore, itemNamed("Rent").firstDate(), "the first date moves back with it");
        assertEquals(Optional.of(monthBefore), service.nextDue(itemNamed("Rent")), "and it's owed");
    }

    @Test
    void undoNeverShiftsTheDayABillIsDue() {
        // Monthly on Mar 31: a month back is Feb 28, which would move it to the 28th. So it stops there.
        ScheduledItem rent = service.addItem(new ScheduledItem(0, "Rent", new BigDecimal("900.00"),
                Frequency.MONTHLY, LocalDate.of(today.getYear() + 1, 3, 31), null, checking.id(), null));
        assertEquals(Optional.empty(), service.undoWouldUnpay(rent));

        // An unpaid one-time bill has nothing before it either.
        ScheduledItem once = service.addItem(new ScheduledItem(0, "Repair", new BigDecimal("80.00"),
                Frequency.ONCE, today.plusDays(3), null, checking.id(), null));
        assertFalse(service.canUndoPaid(once));
    }

    private ScheduledItem itemNamed(String name) {
        return service.items().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    private ScheduledItem current() {
        return service.items().get(0);
    }

    private BigDecimal balanceInSixMonths() {
        return service.forecast(today.plusMonths(6)).balanceOf(checking.id());
    }
}
