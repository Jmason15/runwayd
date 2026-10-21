package com.finance.runwayd.core.demo;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.budget.BudgetPlan;
import com.finance.runwayd.core.debt.DebtSummary;
import com.finance.runwayd.core.forecast.LedgerEntry;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.storage.BudgetLibrary;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The demo budget should give every tab something sensible to show, whatever day it's made. */
class DemoBudgetTest {

    @TempDir
    Path home;

    private final LocalDate today = LocalDate.now();
    private BudgetLibrary library;
    private FinanceService demo;

    @BeforeEach
    void setUp() {
        library = new BudgetLibrary(home);
        demo = new FinanceService(library.open(library.addDemo(today)));
    }

    @Test
    void itHasAccountsBillsAndDebts() {
        assertEquals(5, demo.accounts().size());
        assertEquals(3, demo.debtAccounts().size());
        assertTrue(demo.items().size() >= 15);
        assertTrue(demo.monthlyTotal(demo.items()).signum() > 0, "the paychecks cover the bills");
    }

    @Test
    void theBudgetSplitsEveryPaycheckAndKeepsTheCushion() {
        BudgetPlan plan = demo.budgetPlanUntil(today.plusMonths(3));
        assertTrue(plan.paychecks().size() >= 6, "a paycheck every two weeks");
        assertTrue(plan.paychecks().stream().allMatch(p -> p.available().signum() > 0), "each one has money to split");

        Account checking = demo.budgetAccount().orElseThrow();
        List<LedgerEntry> year = demo.forecast(today.plusYears(1)).ledgerFor(checking.id());
        assertTrue(year.stream().allMatch(e -> e.balanceAfter().compareTo(new BigDecimal("1000.00")) >= 0),
                "checking never drops below the $1,000 it keeps");
    }

    @Test
    void theEmergencyFundGrowsAndTheDebtsGetPaidOff() {
        Account savings = demo.cashAccounts().stream().filter(a -> a.name().equals("Rainy Day Savings"))
                .findFirst().orElseThrow();
        assertTrue(demo.forecast(today.plusMonths(6)).balanceOf(savings.id()).compareTo(savings.balance()) > 0);

        DebtSummary debt = demo.debtSummary(today);
        assertNotNull(debt.debtFreeDate(), "every debt is paid off eventually");
        assertTrue(debt.debtFreeDate().isBefore(today.plusYears(5)));
    }

    @Test
    void thisMonthsChecklistHasThingsOnIt() {
        assertFalse(demo.checklist(YearMonth.from(today), YearMonth.from(today).plusMonths(1)).isEmpty());
    }

    @Test
    void addingItAgainMakesASecondOneAndNeverTouchesYourBudgets() {
        library.create("My budget");

        assertEquals("Demo (2)", library.addDemo(today));
        assertEquals(List.of("Demo", "Demo (2)", "My budget"), library.names());
        assertTrue(library.isDemo("Demo") && library.isDemo("demo (2)"));
        assertFalse(library.isDemo("My budget"));
        assertFalse(library.isDemo("Demolition"));
    }
}
