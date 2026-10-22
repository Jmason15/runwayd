package com.finance.runwayd.desktop.qa;

import com.finance.runwayd.core.model.ScheduledItem;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA: trying to break it. Odd input, extreme numbers, and changes that have to ripple through every
 * tab. Each test says what a person might do and what should happen.
 */
class BreakItQaTest extends QaBase {

    private final LocalDate today = LocalDate.now();
    private final LocalDate tomorrow = today.plusDays(1);

    @BeforeEach
    void checkingWith2000() {
        addAccount("Checking", "2000");
    }

    @Test
    void deletingAnAccountABillStillUsesIsRefusedWithAMessage() {
        addBill("Rent", "900", "Monthly", tomorrow);
        openTab("Accounts");
        clickOn(cellNode(byId("accountsTable"), 0, "Name"));
        press("Delete");
        answer(true); // "Delete Checking?"

        assertFalse(popUpText().isBlank(), "a message says why it can't be deleted");
        answer(true);
        assertEquals(1, reopened().accounts().size(), "the account is still there");
        assertEquals(1, reopened().items().size(), "and so is the bill");
    }

    @Test
    void renamingAnAccountShowsTheNewNameEverywhere() {
        addBill("Rent", "900", "Monthly", tomorrow);
        openTab("Accounts");
        clickOn(cellNode(byId("accountsTable"), 0, "Name"));
        press("Edit");
        type("Name", "Joint Checking");
        save();

        openTab("Bills & Income");
        assertEquals("Joint Checking", cell(tablesWithColumn("Next due").get(0), 0, "Account"));
        assertShows("2000", forecastCell("Joint Checking", "Today"), "Forecast tab uses the new name");
        chooseIn(byId("checklistRange"), "3 months");
        assertEquals("Joint Checking", cellWhere(byId("checklist"), "What", "Rent", "Account"));
    }

    @Test
    void movingABillToAnotherAccountMovesTheMoney() {
        addAccount("Savings", "500");
        addBill("Rent", "900", "Monthly", tomorrow);

        openTab("Bills & Income");
        doubleClickOn(cellNode(tablesWithColumn("Next due").get(0), 0, "Name"));
        settle();
        choose("Paid from", "Savings");
        save();

        showBalancesOn(tomorrow);
        assertShows("2000", forecastCell("Checking", "On chosen date"), "Checking no longer pays it");
        assertShows("-400", forecastCell("Savings", "On chosen date"), "$500 - $900");
        assertTrue(forecastCell("Savings", "Watch out for").startsWith("OVERDRAWN"),
                "going below zero is called out: " + forecastCell("Savings", "Watch out for"));
    }

    @Test
    void hugeAndTinyAmountsAddUpToTheCent() {
        openTab("Accounts");
        clickOn(cellNode(byId("accountsTable"), 0, "Name"));
        press("Update balance");
        type("Balance now", "999,999,999.99");
        save();
        addBill("Penny", "0.01", "Monthly", tomorrow);

        // Twelve pennies in the next year.
        showBalancesOn(tomorrow.plusMonths(11));
        assertShows("999999999.87", forecastCell("Checking", "On chosen date"), "999,999,999.99 - 12 × 0.01");
    }

    @Test
    void namesAreTidiedAndKeepTheirAccents() {
        addBill("   Café Ünïcode Rent   ", "10", "Monthly", tomorrow);
        openTab("Bills & Income");
        assertEquals("Café Ünïcode Rent", cell(tablesWithColumn("Next due").get(0), 0, "Name"));
        assertEquals("Café Ünïcode Rent", reopened().items().get(0).name());
    }

    @Test
    void aBillThatStartedYearsAgoOnlyCountsSinceTrackingBegan() {
        // Due on tomorrow's day of the month since 2000 (today's would rightly count: due today, not ticked).
        addBill("Old loan", "100", "Monthly", tomorrow.minusYears(26));

        // Tracking began today, so 26 years of old payments don't come out of today's balance.
        assertShows("2000", forecastCell("Checking", "Today"), "no ancient history");
        openTab("Bills & Income");
        LocalDate nextDue = LocalDate.parse(reopened().nextDue(reopened().items().get(0)).orElseThrow().toString());
        assertFalse(nextDue.isBefore(today), "next due is from today on, was " + nextDue);
    }

    @Test
    void tenYearsAheadWorksAndShowsTheOverdraft() {
        addBill("Rent", "100", "Monthly", tomorrow);
        showBalancesOn(tomorrow.plusYears(10).minusDays(1));

        // 120 payments of $100 out of $2,000.
        assertShows("-10000", forecastCell("Checking", "On chosen date"), "$2,000 - 120 × $100");
        assertTrue(forecastCell("Checking", "Watch out for").startsWith("OVERDRAWN"));
    }

    @Test
    void cancelEverywhereChangesNothing() throws Exception {
        addBill("Rent", "900", "Monthly", tomorrow);
        byte[] before = Files.readAllBytes(dataFile);

        openTab("Accounts");
        press("Add");
        type("Name", "Not saved");
        cancel();
        openTab("Debt");
        press("Add debt");
        type("Name", "Not saved");
        cancel();
        openTab("Bills & Income");
        press("Add");
        type("Name", "Not saved");
        cancel();
        doubleClickOn(cellNode(tablesWithColumn("Next due").get(0), 0, "Name"));
        settle();
        type("Amount", "1");
        cancel();

        assertEquals(new String(before), new String(Files.readAllBytes(dataFile)), "the file is untouched");
        assertShows("-900", cell(tablesWithColumn("Next due").get(0), 0, "Amount"), "and so is the screen");
    }

    @Test
    void switchingTabsBackAndForthKeepsTheSameNumbers() {
        addBill("Rent", "900", "Monthly", tomorrow);
        showBalancesOn(tomorrow);
        String first = forecastCell("Checking", "On chosen date");
        for (String tab : List.of("Bills & Income", "Debt", "Budget", "Accounts", "Forecast", "Budget", "Forecast")) {
            openTab(tab);
        }
        assertEquals(first, forecastCell("Checking", "On chosen date"));
        assertShows("1100", first, "$2,000 - $900");
    }

    @Test
    void deletingATickedBillTakesItOffTheChecklist() {
        addBill("Rent", "900", "One time", tomorrow);
        openTab("Forecast");
        chooseIn(byId("checklistRange"), "3 months");
        TableView<?> checklist = byId("checklist");
        clickOn(cellNode(checklist, rowWhere(checklist, "What", "Rent"), "Paid").getGraphic());
        settle();

        openTab("Bills & Income");
        clickOn(cellNode(tablesWithColumn("Next due").get(0), 0, "Name"));
        key(javafx.scene.input.KeyCode.DELETE);
        answer(true);

        openTab("Forecast");
        List<String> names = ((TableView<?>) byId("checklist")).getItems().stream()
                .map(item -> ((com.finance.runwayd.core.bills.ChecklistItem) item).name()).toList();
        assertFalse(names.contains("Rent"), names.toString());
        assertTrue(reopened().items().stream().map(ScheduledItem::name).noneMatch("Rent"::equals));
    }
}
