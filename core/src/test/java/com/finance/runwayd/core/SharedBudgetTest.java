package com.finance.runwayd.core;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.storage.ChangedElsewhereException;
import com.finance.runwayd.core.storage.JsonDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two computers using one shared budget file. Each "Mac" here is its own FinanceService on the
 * same file, just like the app running on your Mac and on your partner's, with the file synced between.
 */
class SharedBudgetTest {

    @TempDir
    Path sharedFolder;

    private Path file;
    private FinanceService yourMac;
    private FinanceService partnerMac;

    @BeforeEach
    void setUp() {
        file = sharedFolder.resolve("Household.json");
        new FinanceService(new JsonDataStore(file)).addAccount(account("Checking"));
        yourMac = new FinanceService(new JsonDataStore(file));
        partnerMac = new FinanceService(new JsonDataStore(file));
    }

    @Test
    void theirChangeShowsUpOnYourMac() {
        partnerMac.addAccount(account("Savings"));

        assertTrue(yourMac.reloadIfChangedElsewhere(), "their save is noticed");
        assertEquals(List.of("Checking", "Savings"), names(yourMac));
        assertFalse(yourMac.reloadIfChangedElsewhere(), "nothing new the second time");
    }

    @Test
    void yourOwnSaveIsntMistakenForTheirs() {
        yourMac.addAccount(account("Savings"));

        assertFalse(yourMac.reloadIfChangedElsewhere());
    }

    @Test
    void editingBeforeTheirChangeArrivesNeverOverwritesIt() throws Exception {
        partnerMac.addAccount(account("Their savings"));
        String theirVersion = Files.readString(file);

        // You make a change before your app has picked theirs up.
        assertThrows(ChangedElsewhereException.class, () -> yourMac.addAccount(account("My savings")));

        assertEquals(theirVersion, Files.readString(file), "their change is still in the file");
        assertEquals(List.of("Checking", "Their savings"), names(yourMac), "your screen now shows their version");

        // Redo it, and now it saves, on top of theirs.
        yourMac.addAccount(account("My savings"));
        assertTrue(partnerMac.reloadIfChangedElsewhere());
        assertEquals(List.of("Checking", "My savings", "Their savings"), names(partnerMac));
    }

    @Test
    void aStoreOnlyComplainsAboutChangesAfterItLoaded() throws Exception {
        JsonDataStore store = new JsonDataStore(sharedFolder.resolve("new.json"));
        assertFalse(store.changedSinceLastRead(), "nothing loaded yet");

        store.load();
        Files.writeString(store.file(), "{\"accounts\": []}");

        assertTrue(store.changedSinceLastRead());
    }

    private static List<String> names(FinanceService service) {
        return service.accounts().stream().map(Account::name).sorted().toList();
    }

    private static Account account(String name) {
        return new Account(0, name, AccountType.CHECKING, BigDecimal.ZERO, LocalDate.of(2026, 1, 1), null);
    }
}
