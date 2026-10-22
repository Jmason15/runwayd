package com.finance.runwayd.core.budget;

import com.finance.runwayd.core.bills.ChecklistItem;
import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.Budget;
import com.finance.runwayd.core.model.BudgetBucket;
import com.finance.runwayd.core.model.BudgetBucket.Destination;
import com.finance.runwayd.core.model.BudgetBucket.Unit;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.core.storage.JsonDataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each budget bucket's share of a payday on the "This month" checklist, one line per bucket,
 * and ticking each off once that money's moved.
 */
class BudgetSplitChecklistTest {

    private static final BigDecimal CUSHION = new BigDecimal("1000.00");

    @TempDir
    Path tempDir;

    private final LocalDate today = LocalDate.now();
    private FinanceService service;
    private Account checking;
    private Account savings;

    /**
     * Checking with $1,500 as of {@code balanceDate} and a $3,000 paycheck on {@code payday}.
     * Half of the extra goes into savings, half is spent.
     */
    private void setUp(LocalDate balanceDate, LocalDate payday) {
        service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        checking = service.addAccount(new Account(0, "Checking", AccountType.CHECKING,
                new BigDecimal("1500.00"), balanceDate, null));
        savings = service.addAccount(new Account(0, "Savings", AccountType.SAVINGS,
                BigDecimal.ZERO, balanceDate, null));
        service.addItem(new ScheduledItem(0, "Paycheck", new BigDecimal("3000.00"), Frequency.MONTHLY,
                payday, null, null, checking.id()));
        service.addItem(new ScheduledItem(0, "Rent", new BigDecimal("1500.00"), Frequency.MONTHLY,
                payday.plusDays(7), null, checking.id(), null));
        service.saveBudget(new Budget(checking.id(), CUSHION, List.of(
                new BudgetBucket("Savings", new BigDecimal("50"), Unit.PERCENT, Destination.ACCOUNT, savings.id()),
                new BudgetBucket("Spending", new BigDecimal("50"), Unit.PERCENT, Destination.SPENT, null))));
    }

    @Test
    void eachBucketIsItsOwnLineNamedByWhereItGoes() {
        LocalDate payday = today.plusDays(3);
        setUp(today, payday);

        List<ChecklistItem> lines = bucketLines(payday);
        BudgetPlan.PaycheckSplit plan = service.budgetPlan(1).paychecks().get(0);

        assertEquals(List.of("Savings → Savings", "Spending"), lines.stream().map(ChecklistItem::name).toList());
        assertEquals(plan.bucketAmounts().get(0), lines.get(0).bucketAmount(), "same amount as the Budget tab");
        assertEquals(plan.bucketAmounts().get(1), lines.get(1).bucketAmount());
        assertTrue(lines.stream().allMatch(line -> line.status() == ChecklistItem.Status.UPCOMING));
    }

    @Test
    void tickingOneBucketOnlyStopsThatOne() {
        LocalDate payday = today.plusDays(3);
        setUp(today, payday);
        BigDecimal toSavings = line(payday, "Savings").bucketAmount();
        BigDecimal checkingBefore = balanceOn(checking, payday);
        BigDecimal planBefore = service.budgetPlan(3).totalAvailable();

        service.setBucketDone("Savings", payday, true);

        assertEquals(ChecklistItem.Status.PAID, line(payday, "Savings").status());
        assertEquals(ChecklistItem.Status.UPCOMING, line(payday, "Spending").status(), "the other bucket is separate");
        assertEquals(checkingBefore.add(toSavings), balanceOn(checking, payday), "savings isn't taken out twice");
        assertEquals(BigDecimal.ZERO.setScale(2), balanceOn(savings, payday).setScale(2),
                "already moved, so the forecast doesn't add it again either");
        assertEquals(planBefore, service.budgetPlan(3).totalAvailable(), "ticking doesn't change the amounts");

        service.setBucketDone("Savings", payday, false);

        assertEquals(ChecklistItem.Status.UPCOMING, line(payday, "Savings").status());
        assertEquals(checkingBefore, balanceOn(checking, payday));
    }

    @Test
    void aHighestRateDebtBucketNamesTheDebtItPays() {
        LocalDate payday = today.plusDays(3);
        setUp(today, payday);
        service.addAccount(new Account(0, "Visa", AccountType.CREDIT_CARD, new BigDecimal("5000.00"), today,
                new BigDecimal("24")));
        service.saveBudget(new Budget(checking.id(), CUSHION, List.of(
                new BudgetBucket("Paying down debt", new BigDecimal("100"), Unit.PERCENT, Destination.DEBT, null))));

        assertEquals("Paying down debt → Visa", line(payday, "Paying down debt").name());
    }

    @Test
    void anUntickedBucketStaysOnTheListAsOverdue() {
        // Last balance update 10 days ago; payday was 5 days ago and nothing was ticked.
        LocalDate payday = today.minusDays(5);
        setUp(today.minusDays(10), payday);

        assertEquals(ChecklistItem.Status.OVERDUE, line(payday, "Savings").status());
    }

    @Test
    void editingTheBudgetKeepsTheTicks() {
        LocalDate payday = today.plusDays(3);
        setUp(today, payday);
        service.setBucketDone("Savings", payday, true);

        service.saveBudget(new Budget(checking.id(), new BigDecimal("900.00"), service.budget().buckets()));

        assertEquals(payday, service.budget().doneThroughFor("Savings"));
    }

    @Test
    void ticksSurviveARestart() {
        LocalDate payday = today.plusDays(3);
        setUp(today, payday);
        service.setBucketDone("Savings", payday, true);

        FinanceService reopened = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));

        assertEquals(payday, reopened.budget().doneThroughFor("Savings"));
    }

    @Test
    void noBucketLinesWhenTheBudgetIsLeftOutOfTheForecast() {
        LocalDate payday = today.plusDays(3);
        setUp(today, payday);
        service.setBudgetInForecast(false);

        assertTrue(bucketLines(payday).isEmpty());
    }

    @Test
    void eachBucketLineShowsCheckingRightAfterIt() {
        LocalDate payday = today.plusDays(3);
        setUp(today, payday);

        BigDecimal afterPaycheck = new BigDecimal("1500.00").add(new BigDecimal("3000.00"));
        ChecklistItem savings = line(payday, "Savings");
        ChecklistItem spending = line(payday, "Spending");

        assertEquals(checking.id(), savings.accountId());
        assertEquals(afterPaycheck.subtract(savings.bucketAmount()), savings.balanceAfter());
        assertEquals(afterPaycheck.subtract(savings.bucketAmount()).subtract(spending.bucketAmount()),
                spending.balanceAfter(), "the buckets come out one after another");
    }

    @Test
    void aLongerRangeListsEveryPaydaysBuckets() {
        LocalDate payday = today.plusDays(3);
        setUp(today, payday);
        YearMonth first = YearMonth.from(today);

        List<LocalDate> savingsPaydays = service.checklist(first, first.plusMonths(5)).stream()
                .filter(entry -> entry.isBudgetSplit() && entry.bucket().name().equals("Savings"))
                .map(ChecklistItem::date)
                .toList();

        // Monthly paycheck: one Savings line for each payday in the six months.
        assertTrue(savingsPaydays.size() >= 5, savingsPaydays.toString());
        assertEquals(payday, savingsPaydays.get(0));
        assertEquals(payday.plusMonths(1), savingsPaydays.get(1));
    }

    private List<ChecklistItem> bucketLines(LocalDate payday) {
        return service.monthChecklist(YearMonth.from(payday)).stream()
                .filter(entry -> entry.isBudgetSplit() && entry.date().equals(payday))
                .toList();
    }

    private ChecklistItem line(LocalDate payday, String bucketName) {
        return bucketLines(payday).stream()
                .filter(entry -> entry.bucket().name().equals(bucketName))
                .findFirst().orElseThrow(() -> new AssertionError("no " + bucketName + " line on " + payday));
    }

    private BigDecimal balanceOn(Account account, LocalDate date) {
        return service.forecast(date).balanceOf(account.id());
    }
}
