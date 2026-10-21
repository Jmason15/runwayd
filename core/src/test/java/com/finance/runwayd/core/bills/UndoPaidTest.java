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
        assertFalse(service.canUndoPaid(current()), "nothing left to undo");
        assertEquals(Optional.empty(), service.undoLastPaid(paycheck.id()));
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

    private ScheduledItem current() {
        return service.items().get(0);
    }

    private BigDecimal balanceInSixMonths() {
        return service.forecast(today.plusMonths(6)).balanceOf(checking.id());
    }
}
