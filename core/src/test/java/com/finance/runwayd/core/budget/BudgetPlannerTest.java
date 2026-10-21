package com.finance.runwayd.core.budget;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.forecast.LedgerEntry;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.Budget;
import com.finance.runwayd.core.model.BudgetBucket;
import com.finance.runwayd.core.model.BudgetBucket.Unit;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.core.storage.JsonDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget's "available" amounts: steady from paycheck to paycheck, and never
 * dipping the account below the cushion.
 */
class BudgetPlannerTest {

    private static final BigDecimal CUSHION = new BigDecimal("1000.00");

    @TempDir
    Path tempDir;

    private final LocalDate today = LocalDate.now();
    private FinanceService service;
    private Account checking;

    @BeforeEach
    void setUp() {
        service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        checking = service.addAccount(new Account(0, "Checking", AccountType.CHECKING,
                new BigDecimal("1500.00"), today, null));
    }

    // ---- evening out ----

    @Test
    void lumpyBillsStillGiveEveryPaycheckASteadyAmount() {
        // Paid twice a month. Almost all the bills land right after the second paycheck,
        // so without evening out that paycheck would get $0 and the first one everything.
        paycheck("Pay A", today.plusDays(2));
        paycheck("Pay B", today.plusDays(12));
        bill("Big bills", "4000.00", today.plusDays(14));
        bill("Small bill", "500.00", today.plusDays(4));
        useBuckets();

        List<BudgetPlan.PaycheckSplit> paychecks = service.budgetPlan(6).paychecks();

        BigDecimal first = paychecks.get(0).available();
        assertTrue(first.signum() > 0, "the first paycheck gets something");
        for (BudgetPlan.PaycheckSplit paycheck : paychecks.subList(0, 4)) {
            assertTrue(paycheck.available().subtract(first).abs().compareTo(new BigDecimal("1.00")) <= 0,
                    "each paycheck gets about the same: " + paycheck.available() + " vs " + first);
        }
        assertNeverBelowCushion(paychecks);
    }

    @Test
    void steadyBillsGiveTheSameAmountEveryPaycheck() {
        paycheck("Paycheck", today.plusDays(3));
        bill("Rent", "1500.00", today.plusDays(10));
        useBuckets();

        List<BudgetPlan.PaycheckSplit> paychecks = service.budgetPlan(6).paychecks();

        // $3,000 in and $1,500 out each month leaves about $1,500 a paycheck (a little more,
        // since the $500 already above the cushion is shared out too).
        for (BudgetPlan.PaycheckSplit paycheck : paychecks) {
            assertTrue(paycheck.available().compareTo(new BigDecimal("1500.00")) >= 0
                            && paycheck.available().compareTo(new BigDecimal("1550.00")) <= 0,
                    "about $1,500 each, got " + paycheck.available());
        }
        assertNeverBelowCushion(paychecks);
    }

    @Test
    void evenOutNeverPassesALimit() {
        // The example from BudgetPlanner.evenOut: paycheck 3's limit holds the first three down.
        List<BigDecimal> limits = List.of(new BigDecimal("2166.00"), new BigDecimal("4645.00"),
                new BigDecimal("4645.00"), new BigDecimal("7714.00"));

        List<BigDecimal> totals = BudgetPlanner.evenOut(limits);

        assertEquals(new BigDecimal("1548.33"), totals.get(0));
        assertEquals(new BigDecimal("3096.66"), totals.get(1));
        for (int k = 0; k < limits.size(); k++) {
            assertTrue(totals.get(k).compareTo(limits.get(k)) <= 0, "total " + k + " is within its limit");
        }
    }

    @Test
    void lookingFurtherAheadShowsMorePaychecksWithTheSameAmounts() {
        paycheck("Paycheck", today.plusDays(3));
        bill("Rent", "1500.00", today.plusDays(10));
        useBuckets(percent("Savings", "50"));

        BudgetPlan oneMonth = service.budgetPlanUntil(today.plusMonths(1));
        BudgetPlan oneYear = service.budgetPlanUntil(today.plusYears(1));

        assertEquals(1, oneMonth.paychecks().size());
        assertEquals(12, oneYear.paychecks().size());
        assertEquals(oneMonth.paychecks().get(0), oneYear.paychecks().get(0)); // same amounts either way
        // The totals add up the paychecks shown.
        assertEquals(oneYear.paychecks().stream().map(BudgetPlan.PaycheckSplit::available)
                .reduce(BigDecimal.ZERO, BigDecimal::add), oneYear.totalAvailable());
        assertEquals(oneYear.paychecks().stream().map(p -> p.bucketAmounts().get(0))
                .reduce(BigDecimal.ZERO, BigDecimal::add), oneYear.totalForBucket(0));
    }

    // ---- splitting into buckets ----

    @Test
    void dollarBucketsComeFirstThenPercentsShareTheRest() {
        // $2,000 available: $400 + $400 first, leaving $1,200. 50% = $600, 25% = $300, $300 left over.
        BudgetPlan.PaycheckSplit split = BudgetPlanner.split(payday(), new BigDecimal("2000.00"), List.of(
                dollars("Me", "400"), dollars("Partner", "400"), percent("Savings", "50"), percent("Invest", "25")));

        assertEquals(List.of(new BigDecimal("400.00"), new BigDecimal("400.00"),
                new BigDecimal("600.00"), new BigDecimal("300.00")), split.bucketAmounts());
        assertEquals(new BigDecimal("300.00"), split.leftOver());
    }

    @Test
    void dollarBucketsMoreThanAvailableShowAsShort() {
        BudgetPlan.PaycheckSplit split = BudgetPlanner.split(payday(), new BigDecimal("2000.00"), List.of(
                dollars("Me", "1500"), dollars("Partner", "1000"), percent("Savings", "50")));

        assertEquals(new BigDecimal("0.00"), split.bucketAmounts().get(2)); // nothing left for percents
        assertEquals(new BigDecimal("-500.00"), split.leftOver());          // 2,000 − 2,500
    }

    @Test
    void percentBucketsCantAddUpToMoreThan100() {
        Budget tooMuch = new Budget(checking.id(), CUSHION, List.of(percent("Me", "20"), percent("Partner", "20"),
                percent("Savings", "20"), percent("Debt", "60")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveBudget(tooMuch));
        assertEquals("Your % buckets add up to 120%. They can't be more than 100%; lower one first.",
                error.getMessage());
        assertEquals(Budget.starter(), service.budget(), "nothing was saved");

        // Exactly 100% is fine, and dollar buckets don't count toward it.
        useBuckets(dollars("Me", "400"), percent("Savings", "40"), percent("Debt", "60"));
        assertEquals(new BigDecimal("100"), Budget.totalPercent(service.budget().buckets()));
    }

    // ---- warnings and saving ----

    @Test
    void warnsWhenTheBalanceDipsBelowTheCushionBeforeAnySplit() {
        // A $600 bill before payday takes checking from 1,500 to 900, under the 1,000 cushion.
        paycheck("Paycheck", today.plusDays(3));
        bill("Car repair", "600.00", today.plusDays(1));
        useBuckets();

        BudgetPlan plan = service.budgetPlan(1);

        assertEquals(today.plusDays(1), plan.lowBalance().date());
        assertEquals(new BigDecimal("900.00"), plan.lowBalance().balance());
    }

    @Test
    void noWarningWhenTheCushionHolds() {
        paycheck("Paycheck", today.plusDays(3));
        useBuckets();
        assertNull(service.budgetPlan(1).lowBalance());
    }

    @Test
    void budgetIsSavedAndOlderFilesGetTheStarterBudget() {
        assertEquals(Budget.starter(), service.budget()); // nothing saved yet

        useBuckets(dollars("Me", "400"), percent("Savings", "30"));
        Budget reopened = new FinanceService(new JsonDataStore(tempDir.resolve("data.json"))).budget();

        assertEquals(2, reopened.buckets().size());
        assertEquals("Me", reopened.buckets().get(0).name());
        assertEquals(CUSHION, reopened.cushion());
    }

    // ---- helpers ----

    /**
     * Replays checking's forecast with each paycheck's "available" taken out on payday,
     * and checks the balance never goes under the cushion.
     */
    private void assertNeverBelowCushion(List<BudgetPlan.PaycheckSplit> paychecks) {
        List<LedgerEntry> ledger = service.forecast(today.plusMonths(15)).ledgerFor(checking.id());
        BigDecimal takenSoFar = BigDecimal.ZERO;
        int next = 0;
        for (LedgerEntry entry : ledger) {
            if (next < paychecks.size() && entry.date().equals(paychecks.get(next).date())
                    && entry.change().signum() > 0) {
                takenSoFar = takenSoFar.add(paychecks.get(next).available());
                next++;
            }
            BigDecimal balance = entry.balanceAfter().subtract(takenSoFar);
            assertTrue(balance.compareTo(CUSHION) >= 0,
                    "balance " + balance + " on " + entry.date() + " went below the cushion");
        }
    }

    private void paycheck(String name, LocalDate first) {
        service.addItem(new ScheduledItem(0, name, new BigDecimal("3000.00"), Frequency.MONTHLY, first, null,
                null, checking.id()));
    }

    private void bill(String name, String amount, LocalDate first) {
        Frequency frequency = name.equals("Car repair") ? Frequency.ONCE : Frequency.MONTHLY;
        service.addItem(new ScheduledItem(0, name, new BigDecimal(amount), frequency, first, null,
                checking.id(), null));
    }

    private void useBuckets(BudgetBucket... buckets) {
        service.saveBudget(new Budget(checking.id(), CUSHION, List.of(buckets)));
    }

    private static LedgerEntry payday() {
        return new LedgerEntry(LocalDate.of(2026, 1, 15), "Paycheck", 1, new BigDecimal("3000.00"),
                new BigDecimal("5000.00"), false, 1);
    }

    private static BudgetBucket dollars(String name, String amount) {
        return new BudgetBucket(name, new BigDecimal(amount), Unit.DOLLARS);
    }

    private static BudgetBucket percent(String name, String percent) {
        return new BudgetBucket(name, new BigDecimal(percent), Unit.PERCENT);
    }
}
