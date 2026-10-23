package com.finance.runwayd.desktop.qa;

import javafx.scene.control.TableView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QA: adding, editing and deleting bank accounts on the RunWay tab, and seeing it saved. */
class AccountsQaTest extends QaBase {

    @Test
    void anAccountAddedOnTheRunWayTabShowsUpAndIsSaved() {
        openTab("RunWay");
        press("Add account");
        type("Name", "Everyday Checking");
        type("Balance", "2,500");
        type("Website", "mybank.com");
        save();

        TableView<?> accounts = byId("forecastAccounts");
        assertEquals("Everyday Checking", cell(accounts, 0, "Account"));
        assertShows("2500", cell(accounts, 0, "Balance"), "the balance as typed");
        assertEquals("Today", cell(accounts, 0, "Updated"), "updated today");
        assertEquals("mybank.com", cell(accounts, 0, "Website"));

        // And it was saved, exactly as typed.
        assertEquals(new BigDecimal("2500.00"), reopened().accounts().get(0).balance());
        assertTrue(reopened().accounts().get(0).website().startsWith("https://mybank.com"));
    }

    @Test
    void doubleClickingAnAccountOpensEdit() {
        addAccount("Checking", "100");
        doubleClickOn(cellNode(byId("forecastAccounts"), 0, "Account"));
        settle();
        type("Name", "Joint Checking");
        save();

        assertEquals("Joint Checking", cell(byId("forecastAccounts"), 0, "Account"));
        assertEquals("Joint Checking", reopened().accounts().get(0).name());
    }

    @Test
    void anUnusedAccountCanBeDeletedAfterAskingFirst() {
        addAccount("Checking", "100");
        addAccount("Old savings", "0");
        TableView<?> accounts = byId("forecastAccounts");
        clickOn(cellNode(accounts, rowWhere(accounts, "Account", "Old savings"), "Account"));
        press("Delete");
        answer(true); // "Delete Old savings?"

        assertEquals(1, reopened().accounts().size());
        assertEquals("Checking", cell(byId("forecastAccounts"), 0, "Account"));
    }
}
