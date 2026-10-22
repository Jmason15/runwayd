package com.finance.runwayd.core.bills;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.bills.ChecklistItem.Status;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "This month" checklist, with fixed dates so the statuses are easy to follow.
 * "Today" in these tests is whatever day they run, so each test builds dates around it.
 */
class ChecklistTest {

    @TempDir
    Path tempDir;

    private final LocalDate today = LocalDate.now();
    private FinanceService service;
    private Account checking;

    @BeforeEach
    void setUp() {
        service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        // Balance updated today: anything due before today is already in it.
        checking = service.addAccount(new Account(0, "Checking", AccountType.CHECKING,
                new BigDecimal("2000.00"), today, null));
    }

    @Test
    void aBillDueTodayStillCountsUntilTickedOff() {
        ScheduledItem phone = bill("Phone", "50.00", today);

        assertEquals(Status.DUE_TODAY, entryFor(phone, today).status());
        // Even though the balance was updated today, today's bill is still coming out.
        assertEquals(new BigDecimal("1950.00"), service.forecast(today).balanceOf(checking.id()));

        service.setPaid(phone.id(), today, true);

        assertEquals(Status.PAID, entryFor(phone, today).status());
        assertEquals(new BigDecimal("2000.00"), service.forecast(today).balanceOf(checking.id()));
    }

    @Test
    void untickingPutsItBack() {
        ScheduledItem phone = bill("Phone", "50.00", today);
        service.setPaid(phone.id(), today, true);

        service.setPaid(phone.id(), today, false);

        assertEquals(Status.DUE_TODAY, entryFor(phone, today).status());
        assertEquals(new BigDecimal("1950.00"), service.forecast(today).balanceOf(checking.id()));
    }

    @Test
    void billsBeforeTheBalanceUpdateAreNeverTickedForYou() {
        // Only meaningful when today isn't the 1st: a bill earlier this month is already in today's balance.
        if (today.getDayOfMonth() == 1) {
            return;
        }
        ScheduledItem rent = bill("Rent", "900.00", today.withDayOfMonth(1));

        ChecklistItem entry = entryFor(rent, today.withDayOfMonth(1));

        assertEquals(Status.BEFORE_BALANCE_UPDATE, entry.status());
        assertFalse(entry.isDone(), "only you tick things");
    }

    @Test
    void unpaidBillsFromBeforeThisMonthStayOnTheList() {
        // Balance from 40 days ago; a bill 35 days ago was never ticked off.
        service.updateBalance(checking.id(), new BigDecimal("2000.00"), today.minusDays(40));
        ScheduledItem repair = service.addItem(new ScheduledItem(0, "Repair", new BigDecimal("300.00"),
                Frequency.ONCE, today.minusDays(35), null, checking.id(), null));

        assertEquals(Status.OVERDUE, entryFor(repair, today.minusDays(35)).status());
    }

    @Test
    void upcomingBillsLaterThisMonthAreListed() {
        if (today.equals(YearMonth.from(today).atEndOfMonth())) {
            return; // nothing is "later this month" on the last day
        }
        ScheduledItem internet = bill("Internet", "80.00", today.plusDays(1));

        assertEquals(Status.UPCOMING, entryFor(internet, today.plusDays(1)).status());
    }

    // ---- "checked without us checking it": bills from before a balance update ----

    @Test
    void updatingTheBalanceNeverTicksABill() {
        // The report: after a balance update, earlier bills looked ticked though nobody ticked them.
        ScheduledItem lunch = bill("School Lunch", "31.48", today.minusDays(3));

        ChecklistItem entry = lunchEntry(lunch);

        assertFalse(entry.isDone(), "not ticked: nobody ticked it");
        assertEquals("Before balance update", entry.status().toString());
        assertEquals(new BigDecimal("2000.00"), service.forecast(today).balanceOf(checking.id()),
                "not subtracted again: the balance typed in today already includes it");
    }

    @Test
    void youCanTickAndUntickItAndTheForecastStaysRight() {
        ScheduledItem lunch = bill("School Lunch", "31.48", today.minusDays(3));

        service.setPaid(lunch.id(), today.minusDays(3), true);
        assertEquals(Status.PAID, lunchEntry(lunch).status());
        assertEquals(new BigDecimal("2000.00"), service.forecast(today).balanceOf(checking.id()));

        service.setPaid(lunch.id(), today.minusDays(3), false);
        assertEquals(Status.BEFORE_BALANCE_UPDATE, lunchEntry(lunch).status());
        assertEquals(new BigDecimal("2000.00"), service.forecast(today).balanceOf(checking.id()));
    }

    @Test
    void tickedBillsFromEarlierMonthsDontShow() {
        ScheduledItem phone = bill("Phone", "50.00", today.minusMonths(1));
        service.setPaid(phone.id(), today.minusMonths(1), true);

        List<LocalDate> shown = service.monthChecklist(YearMonth.from(today)).stream()
                .filter(e -> !e.isBudgetSplit() && e.item().id() == phone.id())
                .map(ChecklistItem::date)
                .toList();

        assertFalse(shown.contains(today.minusMonths(1)), "last month's ticked one is gone: " + shown);
    }

    /** The School Lunch line, looking across last month too in case "3 days ago" was in it. */
    private ChecklistItem lunchEntry(ScheduledItem lunch) {
        return service.checklist(YearMonth.from(today.minusDays(3)), YearMonth.from(today)).stream()
                .filter(e -> !e.isBudgetSplit() && e.item().id() == lunch.id() && e.date().equals(today.minusDays(3)))
                .findFirst().orElseThrow();
    }

    // ---- what's left after each line ----

    @Test
    void eachLineShowsTheBalanceRightAfterIt() {
        ScheduledItem phone = bill("Phone", "50.00", today.plusDays(1));
        ScheduledItem rent = bill("Rent", "900.00", today.plusDays(1));
        ScheduledItem internet = bill("Internet", "100.00", today.plusDays(2));

        List<ChecklistItem> lines = service.checklist(YearMonth.from(today), YearMonth.from(today.plusDays(2))).stream()
                .filter(e -> !e.isBudgetSplit()).toList();

        // Same-day bills read as a running balance: 2,000 → then each one taken off in turn.
        ChecklistItem first = lines.get(0);
        ChecklistItem second = lines.get(1);
        assertEquals(today.plusDays(1), first.date());
        assertEquals(new BigDecimal("2000.00").subtract(first.item().amount()), first.balanceAfter());
        assertEquals(new BigDecimal("1050.00"), second.balanceAfter(), "after Phone and Rent");
        assertEquals(new BigDecimal("950.00"), lineFor(internet, today.plusDays(2)).balanceAfter());
        assertEquals(checking.id(), lineFor(internet, today.plusDays(2)).accountId());

        service.setPaid(phone.id(), today.plusDays(1), true);
        assertEquals(null, lineFor(phone, today.plusDays(1)).balanceAfter(), "ticked: not in the forecast");
        assertEquals(new BigDecimal("1100.00"), lineFor(rent, today.plusDays(1)).balanceAfter(), "now Rent alone");
    }

    /** A line from this month through two days from now (which may be next month). */
    private ChecklistItem lineFor(ScheduledItem item, LocalDate date) {
        return service.checklist(YearMonth.from(today), YearMonth.from(today.plusDays(2))).stream()
                .filter(e -> !e.isBudgetSplit() && e.item().id() == item.id() && e.date().equals(date))
                .findFirst().orElseThrow();
    }

    // ---- looking further ahead ----

    @Test
    void aYearShowsEveryMonthsDateAndTickingAheadWorksThere() {
        ScheduledItem rent = bill("Rent", "900.00", today.plusDays(1));
        YearMonth thisMonth = YearMonth.from(today);

        List<ChecklistItem> year = service.checklist(thisMonth, thisMonth.plusMonths(11));
        List<LocalDate> rentDates = year.stream().filter(e -> !e.isBudgetSplit() && e.item().id() == rent.id())
                .map(ChecklistItem::date).toList();

        // Every rent date from tomorrow to the end of the 12th month, soonest first.
        assertEquals(today.plusDays(1), rentDates.get(0));
        assertTrue(rentDates.size() >= 11 && rentDates.size() <= 12, rentDates.toString());
        assertTrue(rentDates.stream().allMatch(d -> !d.isAfter(thisMonth.plusMonths(11).atEndOfMonth())));
        assertEquals(rentDates.stream().sorted().toList(), rentDates);

        // Ticking one three months out marks it (and the ones before it) paid.
        LocalDate third = rentDates.get(2);
        service.setPaid(rent.id(), third, true);
        List<ChecklistItem> after = service.checklist(thisMonth, thisMonth.plusMonths(11));
        assertEquals(Status.PAID, after.stream().filter(e -> !e.isBudgetSplit() && e.date().equals(third))
                .findFirst().orElseThrow().status());
    }

    @Test
    void oneMonthIsTheSameAsThisMonth() {
        bill("Rent", "900.00", today.plusDays(1));
        YearMonth thisMonth = YearMonth.from(today);

        assertEquals(service.monthChecklist(thisMonth), service.checklist(thisMonth, thisMonth));
    }

    // ---- helpers ----

    private ScheduledItem bill(String name, String amount, LocalDate first) {
        return service.addItem(new ScheduledItem(0, name, new BigDecimal(amount), Frequency.MONTHLY, first, null,
                checking.id(), null));
    }

    private ChecklistItem entryFor(ScheduledItem item, LocalDate date) {
        List<ChecklistItem> checklist = service.monthChecklist(YearMonth.from(today));
        return checklist.stream()
                .filter(entry -> entry.item().id() == item.id() && entry.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError(item.name() + " on " + date + " isn't on the checklist"));
    }
}
