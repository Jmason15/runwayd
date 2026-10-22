package com.finance.runwayd.desktop.qa;

import javafx.scene.control.TableView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QA: adding an account by clicking, and seeing it everywhere it should show up. */
class AccountsQaTest extends QaBase {

    @Test
    void anAccountAddedOnTheAccountsTabShowsUpEverywhere() {
        openTab("Accounts");
        press("Add");
        type("Name", "Everyday Checking");
        type("Balance", "2,500");
        type("Website", "mybank.com");
        save();

        TableView<?> accounts = byId("accountsTable");
        assertEquals("Everyday Checking", cell(accounts, 0, "Name"));
        assertShows("2500", cell(accounts, 0, "Balance"), "balance on the Accounts tab");
        assertEquals("mybank.com", cell(accounts, 0, "Website"));

        openTab("Forecast");
        TableView<?> forecast = byId("forecastAccounts");
        assertShows("2500", cellWhere(forecast, "Account", "Everyday Checking", "Balance"), "Today on the Forecast tab");
        assertEquals("Today", cellWhere(forecast, "Account", "Everyday Checking", "Updated"), "updated today");

        // And it was saved, exactly as typed.
        assertEquals(new BigDecimal("2500.00"), reopened().accounts().get(0).balance());
        assertTrue(reopened().accounts().get(0).website().startsWith("https://mybank.com"));
    }
}
