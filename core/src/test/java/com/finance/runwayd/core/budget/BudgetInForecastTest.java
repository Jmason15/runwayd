package com.finance.runwayd.core.budget;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.forecast.LedgerEntry;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.Budget;
import com.finance.runwayd.core.model.BudgetBucket;
import com.finance.runwayd.core.model.BudgetBucket.Destination;
import com.finance.runwayd.core.model.BudgetBucket.Unit;
import com.finance.runwayd.core.model.Category;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.PaymentRule;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.core.storage.JsonDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget's paycheck splits played into the forecast.
 *
 * <pre>
 *   Checking $1,500 today, keep $1,000. Paycheck $3,000 monthly (day 3), rent $1,500 (day 10).
 * </pre>
 */
class BudgetInForecastTest {

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
        service.addItem(new ScheduledItem(0, "Paycheck", new BigDecimal("3000.00"), Frequency.MONTHLY,
                today.plusDays(3), null, null, checking.id()));
        service.addItem(new ScheduledItem(0, "Rent", new BigDecimal("1500.00"), Frequency.MONTHLY,
                today.plusDays(10), null, checking.id(), null));
    }

    @Test
    void splitsLeaveCheckingOnPaydayAndTheCushionHolds() {
        useBuckets(new BudgetBucket("Spending", new BigDecimal("100"), Unit.PERCENT, Destination.SPENT));

        List<LedgerEntry> ledger = service.forecast(today.plusMonths(6)).ledgerFor(checking.id());

        // The split shows up in the ledger on payday...
        LedgerEntry firstSplit = ledger.stream().filter(e -> e.description().equals("Budget: Spending"))
                .findFirst().orElseThrow();
        assertEquals(today.plusDays(3), firstSplit.date());
        assertEquals(service.budgetPlan(1).paychecks().get(0).available().negate(), firstSplit.change());
        // ...and with every dollar of extra handed out, checking still never goes under $1,000.
        for (LedgerEntry entry : ledger) {
            assertTrue(entry.balanceAfter().compareTo(CUSHION) >= 0,
                    entry.balanceAfter() + " on " + entry.date() + " is under the cushion");
        }
    }

    @Test
    void debtBucketPaysTheHighestRateDebtFirst() {
        Account lowRate = service.addAccount(new Account(0, "Car loan", AccountType.LOAN,
                new BigDecimal("5000.00"), today, new BigDecimal("6")));
        Account highRate = service.addAccount(new Account(0, "Card", AccountType.CREDIT_CARD,
                new BigDecimal("300.00"), today, new BigDecimal("24")));
        useBuckets(new BudgetBucket("Debt", new BigDecimal("100"), Unit.PERCENT, Destination.DEBT));

        var forecast = service.forecast(today.plusDays(5)); // just past the first payday
        BigDecimal firstSplit = service.budgetPlan(1).paychecks().get(0).available();

        // The card (24%) is paid off first; whatever's left goes to the car loan (6%).
        assertEquals(new BigDecimal("0.00"), forecast.balanceOf(highRate.id()).setScale(2));
        assertEquals(new BigDecimal("5000.00").subtract(firstSplit.subtract(new BigDecimal("300.00"))),
                forecast.balanceOf(lowRate.id()));
    }

    @Test
    void aChosenDebtGetsTheBucketEvenWithALowerRate() {
        Account carLoan = service.addAccount(new Account(0, "Car loan", AccountType.LOAN,
                new BigDecimal("5000.00"), today, new BigDecimal("6")));
        Account card = service.addAccount(new Account(0, "Card", AccountType.CREDIT_CARD,
                new BigDecimal("300.00"), today, new BigDecimal("24")));
        useBuckets(new BudgetBucket("Debt", new BigDecimal("100"), Unit.PERCENT, Destination.DEBT, carLoan.id()));

        var forecast = service.forecast(today.plusDays(5)); // just past the first payday
        BigDecimal firstSplit = service.budgetPlan(1).paychecks().get(0).available();

        assertEquals(new BigDecimal("5000.00").subtract(firstSplit), forecast.balanceOf(carLoan.id()));
        assertEquals(new BigDecimal("300.00"), forecast.balanceOf(card.id())); // higher rate, but not chosen
    }

    @Test
    void amountsDontDependOnHowFarAheadYouLook() {
        // The Budget tab (a year ahead) and a 20-year forecast must agree on the first paycheck's split.
        useBuckets(new BudgetBucket("Spending", new BigDecimal("100"), Unit.PERCENT, Destination.SPENT));
        BigDecimal budgetTab = service.budgetPlan(1).paychecks().get(0).available();

        BigDecimal inLongForecast = service.forecast(today.plusYears(20)).ledgerFor(checking.id()).stream()
                .filter(LedgerEntry::fromBudget)
                .filter(entry -> entry.date().equals(today.plusDays(3)))
                .map(entry -> entry.change().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(budgetTab, inLongForecast);
    }

    @Test
    void theChosenDebtsMonthlyPaymentIncludesTheBudget() {
        // Visa has its own $100 a month; the budget's debt bucket picks Visa too.
        Account visa = service.addAccount(new Account(0, "Visa", AccountType.CREDIT_CARD,
                new BigDecimal("8000.00"), today, BigDecimal.ZERO));
        Account other = service.addAccount(new Account(0, "Other", AccountType.CREDIT_CARD,
                new BigDecimal("8000.00"), today, BigDecimal.ZERO));
        service.addItem(new ScheduledItem(0, "Visa payment", new BigDecimal("100.00"), Frequency.MONTHLY,
                today.plusDays(5), null, checking.id(), visa.id()));
        useBuckets(new BudgetBucket("Debt", new BigDecimal("100"), Unit.PERCENT, Destination.DEBT, visa.id()));

        BigDecimal fromBudget = service.budgetPlan(1).paychecks().get(0).available(); // one payday a month here
        var rows = service.debtSummary(today).debts();
        var visaRow = rows.stream().filter(r -> r.account().id() == visa.id()).findFirst().orElseThrow();
        var otherRow = rows.stream().filter(r -> r.account().id() == other.id()).findFirst().orElseThrow();

        // About one paycheck's split a month (an average over the year, so allow a few cents).
        assertTrue(visaRow.budgetPerMonth().subtract(fromBudget).abs().compareTo(new BigDecimal("0.05")) <= 0,
                "budget per month " + visaRow.budgetPerMonth() + " vs one split " + fromBudget);
        assertEquals(new BigDecimal("100.00").add(visaRow.budgetPerMonth()), visaRow.monthlyPayment());
        assertEquals(visaRow.monthlyPayment(), visaRow.principalPerMonth()); // 0% rate: all of it is principal
        assertEquals(BigDecimal.ZERO, otherRow.budgetPerMonth().stripTrailingZeros()); // not the one picked
    }

    @Test
    void onlyTheDebtTheBudgetPaysRightNowShowsBudgetMoney() {
        // Highest rate first: the small 20% card goes first (paid off within months), then the 10% card.
        // Over the year both get budget money, but right now only the 20% card does.
        Account first = service.addAccount(new Account(0, "First", AccountType.CREDIT_CARD,
                new BigDecimal("2000.00"), today, new BigDecimal("20")));
        Account next = service.addAccount(new Account(0, "Next", AccountType.CREDIT_CARD,
                new BigDecimal("9000.00"), today, new BigDecimal("10")));
        useBuckets(new BudgetBucket("Debt", new BigDecimal("100"), Unit.PERCENT, Destination.DEBT));

        var rows = service.debtSummary(today).debts();
        var firstRow = rows.stream().filter(r -> r.account().id() == first.id()).findFirst().orElseThrow();
        var nextRow = rows.stream().filter(r -> r.account().id() == next.id()).findFirst().orElseThrow();

        assertTrue(firstRow.budgetPerMonth().signum() > 0, "the card being paid now shows its budget money");
        assertEquals(0, nextRow.budgetPerMonth().signum(), "the next card waits its turn");
        assertTrue(nextRow.payoffDate().isAfter(firstRow.payoffDate()), "...but its payoff date still counts it");
    }

    @Test
    void onceTheChosenDebtIsPaidOffTheMoneyStaysInChecking() {
        Account card = service.addAccount(new Account(0, "Card", AccountType.CREDIT_CARD,
                new BigDecimal("300.00"), today, BigDecimal.ZERO));
        Account carLoan = service.addAccount(new Account(0, "Car loan", AccountType.LOAN,
                new BigDecimal("5000.00"), today, new BigDecimal("6")));
        useBuckets(new BudgetBucket("Debt", new BigDecimal("100"), Unit.PERCENT, Destination.DEBT, card.id()));
        BigDecimal checkingWithoutBudget = withoutBudget(() -> service.forecast(today.plusDays(5)).balanceOf(checking.id()));

        var forecast = service.forecast(today.plusDays(5));

        assertEquals(new BigDecimal("0.00"), forecast.balanceOf(card.id()).setScale(2));
        assertEquals(new BigDecimal("5000.00"), forecast.balanceOf(carLoan.id())); // nothing rolls over to it
        // Only the $300 that paid off the card left checking; the rest of the split stayed.
        assertEquals(checkingWithoutBudget.subtract(new BigDecimal("300.00")), forecast.balanceOf(checking.id()));
    }

    @Test
    void aDebtABucketPaysCantBeDeleted() {
        Account card = service.addAccount(new Account(0, "Card", AccountType.CREDIT_CARD,
                new BigDecimal("300.00"), today, BigDecimal.ZERO));
        useBuckets(new BudgetBucket("Debt", new BigDecimal("10"), Unit.PERCENT, Destination.DEBT, card.id()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deleteAccount(card.id()));
        assertTrue(error.getMessage().contains("the Budget bucket \"Debt\""), error.getMessage());
    }

    @Test
    void turningTheSwitchOffLeavesTheForecastAsItWas() {
        BigDecimal withoutBudget = service.forecast(today.plusMonths(3)).balanceOf(checking.id());
        useBuckets(new BudgetBucket("Spending", new BigDecimal("50"), Unit.PERCENT, Destination.SPENT));
        BigDecimal withBudget = service.forecast(today.plusMonths(3)).balanceOf(checking.id());

        service.setBudgetInForecast(false);

        assertTrue(withBudget.compareTo(withoutBudget) < 0, "the budget takes money out");
        assertEquals(withoutBudget, service.forecast(today.plusMonths(3)).balanceOf(checking.id()));
    }

    @Test
    void theBudgetIsWorkedOutFromTheForecastWithoutItself() {
        // Including the budget in the forecast must not change what the budget says is available.
        useBuckets(new BudgetBucket("Spending", new BigDecimal("100"), Unit.PERCENT, Destination.SPENT));
        BigDecimal on = service.budgetPlan(1).paychecks().get(0).available();
        service.setBudgetInForecast(false);
        BigDecimal off = service.budgetPlan(1).paychecks().get(0).available();

        assertEquals(off, on);
    }

    @Test
    void olderBucketsWithDebtInTheNamePayDebt() {
        assertEquals(Destination.DEBT,
                new BudgetBucket("Paying down debt", BigDecimal.TEN, Unit.PERCENT, null).destination());
        assertEquals(Destination.SPENT,
                new BudgetBucket("Savings", BigDecimal.TEN, Unit.PERCENT, null).destination());
    }

    /** Works something out with the budget switched off, then switches it back on. */
    // ---- topping up: the budget's own effect on later bills ----

    @Test
    void payingACardDownWithTheBudgetFreesItsShrinkingMinimumToo() {
        // A card on minimum payments. The debt bucket pays it down, so its minimum keeps shrinking
        // and checking would end up well above $1,000 unless the budget hands that out as well.
        Account card = service.addAccount(new Account(0, "Card", AccountType.CREDIT_CARD,
                new BigDecimal("20000.00"), today, new BigDecimal("24")));
        service.addItem(new ScheduledItem(0, "Card payment", Category.DEBT_PAYMENTS,
                null, PaymentRule.CARD_MINIMUM, Frequency.MONTHLY,
                today.plusDays(5), null, checking.id(), card.id(), null));
        useBuckets(new BudgetBucket("Debt", new BigDecimal("100"), Unit.PERCENT, Destination.DEBT, card.id()));

        BigDecimal lowest = service.forecast(today.plusMonths(12)).ledgerFor(checking.id()).stream()
                .filter(entry -> !entry.date().isBefore(today))
                .map(LedgerEntry::balanceAfter)
                .min(BigDecimal::compareTo).orElseThrow();

        assertTrue(lowest.compareTo(CUSHION) >= 0, "never below the amount to keep: " + lowest);
        assertTrue(lowest.compareTo(new BigDecimal("1010.00")) <= 0, "gets down to it, not hundreds above: " + lowest);
    }

    // ---- buckets that go into one of your accounts ----

    @Test
    void anAccountBucketMovesTheMoneyIntoThatAccount() {
        Account savings = service.addAccount(new Account(0, "Savings", AccountType.SAVINGS,
                new BigDecimal("200.00"), today, null));
        useBuckets(new BudgetBucket("Savings", new BigDecimal("100"), Unit.PERCENT, Destination.ACCOUNT,
                savings.id()));

        var forecast = service.forecast(today.plusMonths(6));
        BigDecimal handedOut = service.budgetPlanUntil(today.plusMonths(6)).totalAvailable();

        // Everything checking gave up landed in savings, on the same paydays.
        assertEquals(new BigDecimal("200.00").add(handedOut), forecast.balanceOf(savings.id()));
        LedgerEntry firstDeposit = forecast.ledgerFor(savings.id()).get(0);
        assertEquals("Budget: Savings", firstDeposit.description());
        assertEquals(today.plusDays(3), firstDeposit.date());
        // Checking still never dips under the amount to keep.
        assertTrue(forecast.ledgerFor(checking.id()).stream()
                .allMatch(entry -> entry.balanceAfter().compareTo(CUSHION) >= 0));
    }

    @Test
    void anAccountBucketNeedsACashAccountOtherThanTheOneItComesOutOf() {
        Account card = service.addAccount(new Account(0, "Card", AccountType.CREDIT_CARD,
                new BigDecimal("300.00"), today, BigDecimal.ZERO));

        assertThrows(IllegalArgumentException.class, () -> useBuckets(
                new BudgetBucket("Oops", BigDecimal.TEN, Unit.PERCENT, Destination.ACCOUNT, checking.id())));
        assertThrows(IllegalArgumentException.class, () -> useBuckets(
                new BudgetBucket("Oops", BigDecimal.TEN, Unit.PERCENT, Destination.ACCOUNT, card.id())));
        assertThrows(IllegalArgumentException.class,
                () -> new BudgetBucket("No account", BigDecimal.TEN, Unit.PERCENT, Destination.ACCOUNT, null));
    }

    @Test
    void anAccountABucketFillsCantBeDeleted() {
        Account investment = service.addAccount(new Account(0, "Investment", AccountType.SAVINGS,
                BigDecimal.ZERO, today, null));
        useBuckets(new BudgetBucket("Investments", BigDecimal.TEN, Unit.PERCENT, Destination.ACCOUNT,
                investment.id()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deleteAccount(investment.id()));
        assertTrue(error.getMessage().contains("the Budget bucket \"Investments\""), error.getMessage());
    }

    @Test
    void olderFilesWithDebtAccountIdStillLoad() throws Exception {
        java.nio.file.Path file = tempDir.resolve("old.json");
        java.nio.file.Files.writeString(file, """
                {"accounts": [], "items": [],
                 "budget": {"cushion": 1000, "buckets": [
                   {"name": "Paying down debt", "value": 40, "unit": "PERCENT", "destination": "DEBT",
                    "debtAccountId": 4}]}}
                """);

        BudgetBucket bucket = new FinanceService(new JsonDataStore(file)).budget().buckets().get(0);

        assertEquals(Destination.DEBT, bucket.destination());
        assertEquals(4, bucket.accountId());
    }

    private BigDecimal withoutBudget(Supplier<BigDecimal> calculation) {
        service.setBudgetInForecast(false);
        try {
            return calculation.get();
        } finally {
            service.setBudgetInForecast(true);
        }
    }

    private void useBuckets(BudgetBucket... buckets) {
        service.saveBudget(new Budget(checking.id(), CUSHION, List.of(buckets)));
    }
}
