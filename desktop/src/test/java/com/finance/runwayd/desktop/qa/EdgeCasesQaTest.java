package com.finance.runwayd.desktop.qa;

import javafx.scene.control.DatePicker;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QA: the corners. An empty budget, transfers, savings buckets and card interest. */
class EdgeCasesQaTest extends QaBase {

    private final LocalDate today = LocalDate.now();
    private final LocalDate tomorrow = today.plusDays(1);

    @Test
    void aBrandNewEmptyBudgetOpensEveryTabWithoutAnError() {
        for (String tab : List.of("Forecast", "Bills & Income", "Debt", "Budget", "Accounts", "Forecast")) {
            openTab(tab);
            assertTrue(popUp().isEmpty(), tab + " shouldn't pop up an error on an empty budget: "
                    + popUp().map(p -> p.getContentText()).orElse(""));
        }
        // Adding a bill before any account explains what to do first.
        openTab("Bills & Income");
        press("Add");
        assertTrue(popUpText().contains("Accounts tab"), popUpText());
        answer(true);
        assertTrue(reopened().items().isEmpty());
    }

    @Test
    void aTransferTakesFromOneAccountAndAddsToTheOther() {
        addAccount("Checking", "2000");
        addAccount("Savings", "100");
        openTab("Bills & Income");
        press("Add");
        type("Name", "To savings");
        choose("Type", "Transfer / payment");
        type("Amount", "250");
        choose("How often", "One time");
        typeDate("First date", tomorrow);
        choose("Paid from", "Checking");
        choose("Paid into", "Savings");
        save();

        assertEquals("Checking → Savings", cell(tablesWithColumn("Next due").get(0), 0, "Account"));
        assertShows("1750", balanceOn("Checking", tomorrow), "out of checking");
        assertShows("350", balanceOn("Savings", tomorrow), "into savings");
    }

    @Test
    void aSavingsBucketMovesMoneyIntoSavingsOnPayday() {
        addAccount("Checking", "1000");
        addAccount("Savings", "0");
        addItem("Income", "Pay", "1000", "One time", tomorrow, "Checking");
        openTab("Budget");
        chooseIn(byId("budgetAccount"), "Checking");
        typeInto(byId("cushion"), "0");
        key(KeyCode.ENTER);

        // Savings: 100% of what's left, into the Savings account.
        javafx.scene.layout.GridPane grid = byId("buckets");
        javafx.scene.control.TextField savingsName = grid.getChildren().stream()
                .filter(n -> n instanceof javafx.scene.control.TextField tf && "Savings".equals(tf.getText()))
                .map(javafx.scene.control.TextField.class::cast).findFirst().orElseThrow();
        int row = javafx.scene.layout.GridPane.getRowIndex(savingsName);
        javafx.scene.control.TextField value = (javafx.scene.control.TextField) grid.getChildren().stream()
                .filter(n -> javafx.scene.layout.GridPane.getRowIndex(n) == row && javafx.scene.layout.GridPane.getColumnIndex(n) == 1)
                .findFirst().orElseThrow();
        typeInto(value, "100");
        key(KeyCode.ENTER);
        javafx.scene.control.ComboBox<?> goesTo = (javafx.scene.control.ComboBox<?>) grid.getChildren().stream()
                .filter(n -> javafx.scene.layout.GridPane.getRowIndex(n) == row && javafx.scene.layout.GridPane.getColumnIndex(n) == 3)
                .findFirst().orElseThrow();
        String intoSavings = goesTo.getItems().stream().map(String::valueOf).filter(s -> s.contains("Savings"))
                .findFirst().orElseThrow(() -> new AssertionError("no 'into Savings' choice in " + goesTo.getItems()));
        chooseIn(goesTo, intoSavings);

        TableView<?> paychecks = byId("paychecks");
        BigDecimal toSavings = amountShown(cell(paychecks, 0, "Savings"));
        assertTrue(toSavings.signum() > 0, "something goes to savings");

        BigDecimal checking = amountShown(balanceOn("Checking", tomorrow));
        BigDecimal savings = amountShown(balanceOn("Savings", tomorrow));
        assertEquals(toSavings, savings, "Savings grows by exactly the bucket");
        assertEquals(new BigDecimal("2000.00").subtract(toSavings), checking, "and Checking shrinks by it");
    }

    @Test
    void cardInterestAgreesBetweenTheDebtAndForecastTabs() {
        addAccount("Checking", "5000");
        openTab("Debt");
        press("Add debt");
        type("Name", "Visa");
        type("Amount owed today", "1200");
        type("Interest rate % / year", "24");
        choose("Payment", "No payment for now");
        save();

        TableView<?> debts = byId("debtTable");
        assertShows("24", cellWhere(debts, "Debt", "Visa", "Interest / mo"), "1,200 × 24% ÷ 12");

        // The Debt tab's "Owed on date" and the Forecast tab must agree on the same day.
        LocalDate inTwoMonths = today.plusMonths(2);
        DatePicker debtDate = lookup(".date-picker").match(this::isReallyVisible).query();
        clickOn(debtDate.getEditor());
        interact(() -> debtDate.getEditor().selectAll());
        write(debtDate.getConverter().toString(inTwoMonths), 0);
        key(KeyCode.ENTER);
        String owedThen = cellWhere(byId("debtTable"), "Debt", "Visa", "Owed on date");
        assertTrue(amountShown(owedThen).compareTo(new BigDecimal("1200")) > 0, "interest adds up: " + owedThen);

        assertEquals(amountShown(owedThen), amountShown(balanceOn("Visa", inTwoMonths)),
                "the Forecast tab's ledger shows the same amount owed");
    }
}
