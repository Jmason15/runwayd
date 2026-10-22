package com.finance.runwayd.core.bills;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.Workspace;
import com.finance.runwayd.core.forecast.Forecasts;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One-time bills on the Bills tab: due until paid, then "Paid <date>" with Undo, then gone the next month. */
class OneTimeBillTest {

    @TempDir
    Path tempDir;

    private final LocalDate today = LocalDate.now();
    private FinanceService service;
    private Account checking;

    @BeforeEach
    void setUp() {
        service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        checking = service.addAccount(new Account(0, "Checking", AccountType.CHECKING,
                new BigDecimal("2000.00"), today, null));
    }

    @Test
    void dueUntilPaidThenPaidOnItsDateAndUndoable() {
        ScheduledItem apostille = oneTime("Apostille", today);

        assertEquals(Optional.of(today), service.nextDue(apostille), "due on its date");
        assertEquals(Optional.empty(), service.paidOn(apostille));

        service.markPaid(apostille.id());
        ScheduledItem paid = current(apostille);

        assertEquals(Optional.empty(), service.nextDue(paid), "nothing left to pay, so no Mark paid button");
        assertEquals(Optional.of(today), service.paidOn(paid), "shown as Paid <date>");
        assertTrue(service.canUndoPaid(paid));

        service.undoLastPaid(apostille.id());
        assertEquals(Optional.of(today), service.nextDue(current(apostille)), "Undo puts it back");
    }

    @Test
    void aOneTimeBillFromBeforeTheBalanceUpdateCanStillBeMarkedPaid() {
        // Before, its "next due" was empty (it's before the balance date) and Mark paid did nothing.
        ScheduledItem payBack = oneTime("Pay back", today.minusDays(3));

        assertEquals(Optional.of(today.minusDays(3)), service.nextDue(payBack));
        service.markPaid(payBack.id());
        assertEquals(Optional.of(today.minusDays(3)), service.paidOn(current(payBack)));
    }

    @Test
    void aPaidOneTimeBillDropsOffTheListAfterItsMonth() {
        ScheduledItem lastMonth = oneTime("Last month", today.minusMonths(1));
        ScheduledItem thisMonth = oneTime("This month", today);
        ScheduledItem unpaidLastMonth = oneTime("Unpaid last month", today.minusMonths(1));
        service.markPaid(lastMonth.id());
        service.markPaid(thisMonth.id());

        Bills bills = billsFor();
        assertTrue(bills.droppedOff(current(lastMonth), today), "paid last month: gone");
        assertFalse(bills.droppedOff(current(thisMonth), today), "paid this month: stays, so it can be undone");
        assertFalse(bills.droppedOff(current(unpaidLastMonth), today), "not paid: stays until it is");
        assertTrue(bills.droppedOff(current(thisMonth), today.plusMonths(1)), "and next month it's gone too");
    }

    @Test
    void aRepeatingBillThatHasEndedDropsOffToo() {
        ScheduledItem loan = service.addItem(new ScheduledItem(0, "Old loan", new BigDecimal("100.00"),
                Frequency.MONTHLY, today.minusMonths(6), today.minusMonths(2), checking.id(), null));

        assertEquals(Optional.empty(), service.nextDue(loan), "it ended");
        assertTrue(billsFor().droppedOff(loan, today));
    }

    // ---- helpers ----

    private ScheduledItem oneTime(String name, LocalDate date) {
        return service.addItem(new ScheduledItem(0, name, new BigDecimal("10.00"), Frequency.ONCE, date, null,
                checking.id(), null));
    }

    private ScheduledItem current(ScheduledItem item) {
        return service.items().stream().filter(i -> i.id() == item.id()).findFirst().orElseThrow();
    }

    /** The Bills feature on the same file, to ask about a date other than today. */
    private Bills billsFor() {
        Workspace workspace = new Workspace(new JsonDataStore(tempDir.resolve("data.json")));
        return new Bills(workspace, null);
    }
}
