package com.finance.runwayd.desktop.qa;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA: the Forecast tab's checklist. Ticking, unticking and typing in a new bank balance should move
 * the numbers by exactly the right amount, and nothing should ever be ticked except by a click.
 */
class ChecklistQaTest extends QaBase {

    private final LocalDate today = LocalDate.now();
    private final LocalDate threeDaysAgo = today.minusDays(3);

    /** Started tracking 10 days ago with $2,000, so there's room for a bill since then. */
    @Override
    void before(FinanceService budget) {
        budget.addAccount(new Account(0, "Checking", AccountType.CHECKING, new BigDecimal("2000.00"),
                today.minusDays(10), null));
    }

    @Test
    void anUntickedBillCountsUntilTickedEvenAfterABalanceUpdate() {
        addBill("Groceries", "200", "One time", threeDaysAgo);
        openTab("Forecast");

        assertEquals("[ ]", checklistCell("Groceries", "Paid"));
        assertEquals("Overdue", checklistCell("Groceries", "Status"));
        assertShows("1800", checklistCell("Groceries", "Balance after"), "$2,000 - $200");
        assertShows("2000", forecastCell("Checking", "Balance"), "Today is what's in the bank, like the Accounts tab");
        assertShows("1800", balanceOn("Checking", today), "the bill still counts in the forecast");

        // The bank says $1,900 now. The bill isn't ticked, so it still counts: 1,900 - 200.
        TableView<?> accounts = byId("forecastAccounts");
        typeIntoCell(accounts, rowWhere(accounts, "Account", "Checking"), "Balance", "1900");
        assertShows("1900", forecastCell("Checking", "Balance"), "what was typed");
        assertShows("1700", balanceOn("Checking", today), "a balance update doesn't tick anything");
        assertEquals("[ ]", checklistCell("Groceries", "Paid"), "nobody ticked it");
        assertEquals("Overdue", checklistCell("Groceries", "Status"));
        openTab("Accounts");
        assertShows("1900", cellWhere(byId("accountsTable"), "Name", "Checking", "Balance"), "what the bank said");

        // Tick it: it stops counting. Untick it: it's back.
        tick("Groceries");
        assertEquals("Paid", checklistCell("Groceries", "Status"));
        assertEquals("", checklistCell("Groceries", "Balance after"), "ticked lines aren't in the forecast");
        assertShows("1900", balanceOn("Checking", today), "ticked, so not taken off");
        assertEquals(threeDaysAgo, reopened().items().get(0).paidThrough());

        tick("Groceries");
        assertEquals("Overdue", checklistCell("Groceries", "Status"));
        assertShows("1700", balanceOn("Checking", today), "unticked, so counted again");
    }

    @Test
    void balanceAfterReadsLikeARunningBalance() {
        LocalDate tomorrow = today.plusDays(1);
        addBill("Groceries", "200", "One time", threeDaysAgo);
        addBill("Rent", "500", "One time", tomorrow);
        addBill("Phone", "50", "One time", tomorrow);
        addItem("Income", "Pay", "1000", "One time", tomorrow, "Checking");
        openTab("Forecast");
        chooseIn(byId("checklistRange"), "3 months"); // tomorrow may be next month

        TableView<?> checklist = byId("checklist");
        BigDecimal running = new BigDecimal("2000.00");
        int counted = 0;
        for (int row = 0; row < checklist.getItems().size(); row++) {
            String after = cell(checklist, row, "Balance after");
            if (after.isEmpty() || !"Checking".equals(cell(checklist, row, "Account"))) {
                continue;
            }
            running = running.add(amountShown(cell(checklist, row, "Amount")));
            assertEquals(running, amountShown(after), "row " + row + " (" + cell(checklist, row, "What") + ")");
            counted++;
        }
        assertTrue(counted >= 4, "all four lines are counted, saw " + counted);
        // Ends where the forecast says Checking ends up: 2,000 - 200 - 500 - 50 + 1,000 (less any budget splits).
        assertEquals(running, amountShown(balanceOn("Checking", tomorrow)));
    }

    // ---- helpers ----

    private String checklistCell(String what, String column) {
        openTab("Forecast");
        return cellWhere(byId("checklist"), "What", what, column);
    }

    /** Clicks the tick box on that line. */
    private void tick(String what) {
        openTab("Forecast");
        TableView<?> checklist = byId("checklist");
        CheckBox box = (CheckBox) cellNode(checklist, rowWhere(checklist, "What", what), "Paid").getGraphic();
        clickOn(box);
        settle();
    }
}
