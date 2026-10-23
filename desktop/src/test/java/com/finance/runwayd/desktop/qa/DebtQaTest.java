package com.finance.runwayd.desktop.qa;

import com.finance.runwayd.desktop.ui.Format;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA: the Debt tab. A $1,000 card at 0% paid $100 a month is paid off with the 10th payment; the
 * totals on the Debt tab, the Forecast tab and the Bills tab should all agree.
 */
class DebtQaTest extends QaBase {

    private final LocalDate tomorrow = LocalDate.now().plusDays(1);

    @BeforeEach
    void checkingAndACard() {
        addAccount("Checking", "5000");
        openTab("Debt");
        press("Add debt");
        type("Name", "Store Card");
        choose("Type", "Credit card");
        type("Amount owed today", "1000");
        choose("Payment", "Set up a monthly payment");
        choose("Pay each month", "Fixed amount");
        type("Payment amount", "100");
        typeDate("Next due date", tomorrow);
        choose("Paid from", "Checking");
        save();
    }

    @Test
    void payoffDateAndTotalsAgreeEverywhere() {
        TableView<?> debts = byId("debtTable");
        assertShows("1000", owed(debts), "owed today");
        assertShows("100", cellWhere(debts, "Debt", "Store Card", "Payment / mo"), "payment");
        // 0% interest: ten $100 payments, the first tomorrow.
        LocalDate lastPayment = tomorrow.plusMonths(9);
        assertEquals(Format.date(lastPayment), cellWhere(debts, "Debt", "Store Card", "Paid off"));
        assertEquals(Format.monthYear(lastPayment), ((Label) byId("debtFree")).getText());
        assertEquals(money("1000"), ((Label) byId("debtTotalToday")).getText());

        openTab("Forecast");
        assertTrue(((Label) byId("debtLine")).getText().startsWith("Total debt: " + money("1000") + " today"),
                ((Label) byId("debtLine")).getText());
        assertShows("-1000", forecastCell("Store Card", "Balance"), "owed shows as negative on the Forecast tab");

        // Two months on, three payments have been made: tomorrow's and the next two.
        assertShows("4700", balanceOn("Checking", tomorrow.plusMonths(2)), "$5,000 - 3 × $100");
        assertShows("700", balanceOn("Store Card", tomorrow.plusMonths(2)), "$1,000 - 3 × $100 still owed");

        openTab("Bills & Income");
        assertTrue(tablesWithColumn("Next due").stream()
                .anyMatch(t -> t.getItems().size() > 0 && "Store Card payment".equals(cell(t, 0, "Name"))),
                "the payment is on the Bills tab");
    }

    @Test
    void typingTheStatementBalanceMovesThePayoffDate() {
        TableView<?> debts = byId("debtTable");
        typeIntoCell(debts, rowWhere(debts, "Debt", "Store Card"), "Owed today", "500");

        debts = byId("debtTable");
        assertShows("500", owed(debts), "the new statement balance");
        assertEquals(Format.date(tomorrow.plusMonths(4)), cellWhere(debts, "Debt", "Store Card", "Paid off"),
                "five payments now");
        assertEquals(money("500"), ((Label) byId("debtTotalToday")).getText());
        assertShows("-500", forecastCell("Store Card", "Balance"), "Forecast tab agrees");
        assertEquals(0, new BigDecimal("500").compareTo(reopened().accounts().stream()
                .filter(a -> a.name().equals("Store Card")).findFirst().orElseThrow().balance()));
    }

    @Test
    void aDebtNeedsANameAndANumber() {
        openTab("Debt");
        press("Add debt");
        type("Amount owed today", "lots");
        save();
        assertNotNull(formError(), "\"lots\" isn't an amount");
        cancel();
        assertEquals(2, reopened().accounts().size(), "nothing added");
    }

    private String owed(TableView<?> debts) {
        return cellWhere(debts, "Debt", "Store Card", "Owed today");
    }
}
