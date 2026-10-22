package com.finance.runwayd.desktop.qa;

import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.desktop.ui.Format;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA: the Bills &amp; Income tab, clicked through. Each change should show up on the Bills tab, in the
 * Forecast, and in the saved file, with the numbers adding up.
 */
class BillsQaTest extends QaBase {

    private final LocalDate today = LocalDate.now();
    private final LocalDate inFiveDays = today.plusDays(5);

    @BeforeEach
    void checkingWith2000() {
        addAccount("Checking", "2000");
    }

    @Test
    void aNewBillShowsOnTheBillsTabAndComesOutOfTheForecast() {
        addBill("Electric", "150", "Monthly", inFiveDays);

        TableView<?> bills = onlyBillTable();
        assertEquals("Electric", cell(bills, 0, "Name"));
        assertShows("-150", cell(bills, 0, "Amount"), "bills show as negative");
        assertEquals("Monthly", cell(bills, 0, "How often"));
        assertEquals(Format.date(inFiveDays), cell(bills, 0, "Next due"));
        assertEquals("Checking", cell(bills, 0, "Account"));
        assertFooter("-150", "-1800");

        // Today it hasn't come out yet; a month from now it has, once.
        assertShows("2000", forecastCell("Checking", "Today"), "not due yet");
        showBalancesOn(today.plusMonths(1));
        assertShows("1850", forecastCell("Checking", "On chosen date"), "$2,000 - $150");

        assertEquals(new BigDecimal("150.00"), reopened().items().get(0).amount().setScale(2));
    }

    @Test
    void typingANewAmountUpdatesTotalsForecastAndFile() {
        addBill("Electric", "150", "Monthly", inFiveDays);

        typeIntoCell(onlyBillTable(), 0, "Amount", "175.25");

        assertShows("-175.25", cell(onlyBillTable(), 0, "Amount"), "the typed amount");
        assertFooter("-175.25", "-2103");
        showBalancesOn(today.plusMonths(1));
        assertShows("1824.75", forecastCell("Checking", "On chosen date"), "$2,000 - $175.25");
        assertEquals(0, new BigDecimal("175.25").compareTo(reopened().items().get(0).amount()));
    }

    @Test
    void markPaidMovesNextDueAndUndoPutsItBack() {
        addBill("Electric", "150", "Monthly", inFiveDays);

        press("Mark paid");
        assertEquals(Format.date(inFiveDays.plusMonths(1)), cell(onlyBillTable(), 0, "Next due"));
        Label undoMessage = byId("undoMessage");
        assertTrue(undoMessage.getText().contains("Electric"), undoMessage.getText());
        showBalancesOn(today.plusMonths(1));
        assertShows("2000", forecastCell("Checking", "On chosen date"), "paid, so not counted");

        openTab("Bills & Income");
        clickOn((javafx.scene.Node) byId("undoButton"));
        settle();
        assertEquals(Format.date(inFiveDays), cell(onlyBillTable(), 0, "Next due"));
        assertShows("1850", forecastCell("Checking", "On chosen date"), "unpaid again");
        assertEquals(null, reopened().items().get(0).paidThrough());
    }

    @Test
    void markingPaidFarAheadAsksFirstAndCancelLeavesItAlone() {
        addBill("Electric", "150", "Monthly", inFiveDays);
        press("Mark paid"); // this month's: no question
        press("Mark paid"); // next month's: a month away, so it asks

        assertTrue(popUpText().contains("Electric"), popUpText());
        cancel();
        assertEquals(Format.date(inFiveDays.plusMonths(1)), cell(onlyBillTable(), 0, "Next due"),
                "Cancel: still only this month's is paid");
        assertEquals(inFiveDays, reopened().items().get(0).paidThrough());
    }

    @Test
    void badInputIsRefusedAndNothingIsSaved() {
        openTab("Bills & Income");
        press("Add");

        save(); // nothing typed
        assertNotNull(formError(), "a blank name must be refused");

        type("Name", "Electric");
        for (String amount : List.of("", "abc", "0", "-50", "12.34.56")) {
            type("Amount", amount);
            save();
            assertNotNull(formError(), "amount \"" + amount + "\" must be refused");
        }
        type("Amount", "150");
        typeDate("First date", today);
        typeDate("Ends (optional)", today.minusDays(1));
        save();
        assertNotNull(formError(), "ending before it starts must be refused");

        typeDate("Ends (optional)", today.plusYears(1));
        type("Website", "not a website");
        save();
        assertNotNull(formError(), "a website with spaces must be refused");

        TextField firstDate = ((javafx.scene.control.DatePicker) formField("First date")).getEditor();
        typeInto(firstDate, "13/45/2026");
        type("Website", "");
        save();
        assertNotNull(formError(), "a date that doesn't exist must be refused");

        cancel();
        assertTrue(reopened().items().isEmpty(), "Cancel saves nothing");
    }

    @Test
    void amountsAreReadTheWayPeopleTypeThem() {
        addBill("Car", "$1,234.5", "Monthly", inFiveDays);
        assertShows("-1234.50", cell(onlyBillTable(), 0, "Amount"), "$1,234.5 is $1,234.50");
    }

    @Test
    void deleteAsksFirstAndUpdatesEverything() {
        addBill("Electric", "150", "Monthly", inFiveDays);
        addBill("Water", "40", "Monthly", inFiveDays);

        TableView<?> bills = onlyBillTable();
        clickOn(cellNode(bills, rowWhere(bills, "Name", "Water"), "Name"));
        key(javafx.scene.input.KeyCode.DELETE);
        cancel();
        assertEquals(2, reopened().items().size(), "Cancel keeps it");

        bills = onlyBillTable();
        clickOn(cellNode(bills, rowWhere(bills, "Name", "Water"), "Name"));
        key(javafx.scene.input.KeyCode.DELETE);
        answer(true);
        assertEquals(List.of("Electric"), reopened().items().stream().map(ScheduledItem::name).toList());
        assertFooter("-150", "-1800");
        showBalancesOn(today.plusMonths(1));
        assertShows("1850", forecastCell("Checking", "On chosen date"), "only Electric comes out now");
    }

    @Test
    void searchAndFiltersCountWhatTheyShow() {
        addBill("Electric", "150", "Monthly", inFiveDays);
        addItem("Income", "Paycheck", "2000", "Every 2 weeks", inFiveDays, "Checking");

        Label count = byId("billCount");
        assertEquals("Showing 2 of 2", count.getText());
        typeInto(byId("billSearch"), "elec");
        assertEquals("Showing 1 of 2", count.getText());
        typeInto(byId("billSearch"), "");
        clickOn(shown(".toggle-button", "Income"));
        settle();
        assertEquals("Showing 1 of 2", count.getText());
        // Net for the paycheck alone: 26 a year × $2,000 ÷ 12.
        assertTrue(((Label) byId("billNet")).getText().contains(money("4333.33")), ((Label) byId("billNet")).getText());
    }

    @Test
    void aWebsiteShowsAsALinkOnTheBillsTabAndChecklist() {
        openTab("Bills & Income");
        press("Add");
        type("Name", "Electric");
        type("Amount", "150");
        typeDate("First date", today);
        type("Website", "www.myelectric.com/pay");
        save();

        assertEquals("myelectric.com", cell(onlyBillTable(), 0, "Website"));
        openTab("Forecast");
        assertEquals("myelectric.com", cellWhere(byId("checklist"), "What", "Electric", "Website"));
        assertEquals("https://www.myelectric.com/pay", reopened().items().get(0).website());
    }

    @Test
    void aOneTimeBillShowsPaidWithItsDateOnceMarked() {
        addBill("Apostille", "10", "One time", today);
        press("Mark paid");
        assertEquals("Paid " + Format.date(today), cellPayText());
        assertFalse(cell(onlyBillTable(), 0, "Next due").length() > 0, "nothing left due");
    }

    // ---- helpers ----

    /** The one table on the Bills tab (these tests put everything in one group, or use the first). */
    private TableView<?> onlyBillTable() {
        openTab("Bills & Income");
        return tablesWithColumn("Next due").get(0);
    }

    private String cellPayText() {
        TableView<?> table = onlyBillTable();
        String last = table.getColumns().get(table.getColumns().size() - 1).getText();
        return cell(table, 0, last);
    }

    /** The footer: "Net: -$150.00 / month · -$1,800.00 / year". */
    private void assertFooter(String perMonth, String perYear) {
        String net = ((Label) byId("billNet")).getText();
        assertTrue(net.contains(money(perMonth) + " / month") && net.contains(money(perYear) + " / year"), net);
    }
}
