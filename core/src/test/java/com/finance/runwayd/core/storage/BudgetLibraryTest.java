package com.finance.runwayd.core.storage;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
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

/** Budgets as separate files: the first run, switching, new/duplicate/rename/delete, and sharing. */
class BudgetLibraryTest {

    @TempDir
    Path home; // stands in for ~/runwayd

    private BudgetLibrary library;

    @BeforeEach
    void setUp() {
        library = new BudgetLibrary(home);
    }

    // ---- the first run ----

    @Test
    void yourDataFromBeforeBudgetsBecomesMyBudgetAndTheOldFileStays() throws Exception {
        Path oldFile = Files.writeString(home.resolve("treasuretracker.json"), "{\"accounts\": [], \"items\": []}");

        assertEquals("My budget", library.startingBudget());
        assertEquals(Files.readString(oldFile), Files.readString(library.folder().resolve("My budget.json")));
        assertTrue(Files.exists(oldFile), "the old file is left as a backup");
    }

    @Test
    void withNothingAtAllYouGetAnEmptyBudget() {
        assertEquals("My budget", library.startingBudget());
        assertEquals(List.of(), service("My budget").accounts());
    }

    @Test
    void reopensTheBudgetUsedLast() {
        library.create("A");
        library.create("B");
        library.open("B");

        assertEquals("B", new BudgetLibrary(home).startingBudget());
    }

    // ---- managing budgets ----

    @Test
    void eachBudgetHasItsOwnData() {
        library.create("Ours");
        library.create("Trip");
        service("Ours").addAccount(checking("Checking"));

        assertEquals(1, service("Ours").accounts().size());
        assertEquals(0, service("Trip").accounts().size());
    }

    @Test
    void duplicateCopiesEverything() {
        library.create("Ours");
        service("Ours").addAccount(checking("Checking"));

        library.duplicate("Ours", "What if");

        assertEquals("Checking", service("What if").accounts().get(0).name());
        assertEquals(List.of("Ours", "What if"), library.names());
    }

    @Test
    void renameKeepsTheDataAndIsRemembered() {
        library.create("Ours");
        service("Ours").addAccount(checking("Checking"));
        library.open("Ours");

        library.rename("Ours", "Household");

        assertEquals(List.of("Household"), library.names());
        assertEquals("Checking", service("Household").accounts().get(0).name());
        assertEquals("Household", new BudgetLibrary(home).startingBudget());
    }

    @Test
    void renameCanJustChangeCapitals() {
        library.create("ours");
        assertEquals("Ours", library.rename("ours", "Ours"));
        assertEquals(List.of("Ours"), library.names());
    }

    @Test
    void deleteMovesTheFileAsideAndKeepsAtLeastOne() throws Exception {
        library.create("Ours");
        library.create("Trip");

        library.delete("Trip");

        assertEquals(List.of("Ours"), library.names());
        try (var deleted = Files.list(library.folder().resolve("Deleted"))) {
            assertEquals(1, deleted.count(), "the deleted budget can still be recovered");
        }
        assertThrows(IllegalStateException.class, () -> library.delete("Ours"));
    }

    @Test
    void namesAreCheckedBecauseTheyBecomeFileNames() {
        library.create("Ours");
        assertEquals("There's already a budget called \"ours\".",
                assertThrows(IllegalArgumentException.class, () -> library.create("ours")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> library.create("   "));
        assertThrows(IllegalArgumentException.class, () -> library.create("a/b"));
        assertThrows(IllegalArgumentException.class, () -> library.create(".hidden"));
    }

    // ---- sharing ----

    @Test
    void exportThenImportOnAnotherMacGivesThemTheirOwnCopy() throws Exception {
        library.create("Our household");
        service("Our household").addAccount(checking("Checking"));
        Path sent = home.resolve("Our household" + BudgetLibrary.EXPORT_ENDING);
        library.exportTo("Our household", sent);

        BudgetLibrary theirs = new BudgetLibrary(Files.createDirectory(home.resolve("theirs")));
        theirs.create("Our household"); // they already have one with that name
        String imported = theirs.importFrom(sent);

        assertEquals("Our household (2)", imported, "nothing they have is replaced");
        assertEquals("Checking", new FinanceService(theirs.open(imported)).accounts().get(0).name());

        // Separate from then on: their changes don't reach yours.
        new FinanceService(theirs.open(imported)).addAccount(checking("Their savings"));
        assertEquals(1, service("Our household").accounts().size());
    }

    @Test
    void importRefusesFilesThatArentBudgets() throws Exception {
        Path notABudget = Files.writeString(home.resolve("photo.json"), "[1, 2, 3]");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> library.importFrom(notABudget));
        assertEquals("photo.json isn't a runwayd budget.", error.getMessage());
        assertEquals(List.of(), library.names());
    }

    @Test
    void filesExportedBeforeTheRenameStillImportUnderTheirName() throws Exception {
        library.create("Ours");
        Path old = home.resolve("Our household.treasuretracker.json");
        library.exportTo("Ours", old);

        assertEquals("Our household", library.importFrom(old));
    }

    // ---- the renames from TreasureTracker and Paydar ----

    @Test
    void theOldFolderIsCopiedOnTheFirstRunAndKept() throws Exception {
        Path oldHome = Files.createDirectories(home.resolve("TreasureTracker"));
        Path oldBudgets = Files.createDirectories(oldHome.resolve("budgets"));
        Files.writeString(oldBudgets.resolve("My budget.json"), "{\"accounts\": []}");
        Files.writeString(oldHome.resolve("settings.json"), "{\"lastBudget\": \"My budget\"}");
        BudgetLibrary runwayd = new BudgetLibrary(home.resolve("runwayd"));

        assertTrue(runwayd.copyOldFolderIfNew(oldHome));

        assertEquals(List.of("My budget"), runwayd.names());
        assertEquals("My budget", runwayd.startingBudget());
        assertTrue(Files.exists(oldBudgets.resolve("My budget.json")), "the old folder stays as a backup");
    }

    @Test
    void paydarsFolderWinsOverTheOlderTreasureTrackerOne() throws Exception {
        // Your Mac has both: ~/TreasureTracker (oldest) and ~/Paydar (copied from it, then used).
        Files.createDirectories(home.resolve("TreasureTracker").resolve("budgets"));
        Files.writeString(home.resolve("TreasureTracker/budgets/Old.json"), "{\"accounts\": []}");
        Files.createDirectories(home.resolve("Paydar").resolve("budgets"));
        Files.writeString(home.resolve("Paydar/budgets/Newer.json"), "{\"accounts\": []}");
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            assertEquals(List.of("Newer"), BudgetLibrary.inHomeFolder().names());
        } finally {
            System.setProperty("user.home", realHome);
        }
    }

    @Test
    void filesExportedAsPaydarStillImport() throws Exception {
        library.create("Ours");
        Path old = home.resolve("Trip.paydar.json");
        library.exportTo("Ours", old);

        assertEquals("Trip", library.importFrom(old));
    }

    @Test
    void theCopyHappensOnlyOnce() throws Exception {
        Path oldHome = Files.createDirectories(home.resolve("TreasureTracker").resolve("budgets")).getParent();
        Files.writeString(oldHome.resolve("budgets").resolve("Old.json"), "{\"accounts\": []}");
        BudgetLibrary runwayd = new BudgetLibrary(home.resolve("runwayd"));
        runwayd.create("New");

        assertFalse(runwayd.copyOldFolderIfNew(oldHome), "runwayd already has its folder");
        assertEquals(List.of("New"), runwayd.names());
    }

    @Test
    void aMacThatOnlyHadTheOldSingleFileGetsItToo() throws Exception {
        // The other person's Mac: the first shared app left ~/TreasureTracker/treasuretracker.json, no budgets yet.
        Path oldHome = Files.createDirectories(home.resolve("TreasureTracker"));
        Files.writeString(oldHome.resolve("treasuretracker.json"), "{\"accounts\": [], \"items\": []}");
        BudgetLibrary runwayd = new BudgetLibrary(home.resolve("runwayd"));

        runwayd.copyOldFolderIfNew(oldHome);

        assertEquals("My budget", runwayd.startingBudget());
    }

    // ---- shared budgets ----

    @Test
    void sharingMovesTheBudgetIntoTheSyncedFolderAndKeepsItOnTheList() throws Exception {
        library.create("Household");
        library.create("Mine");
        Path synced = Files.createDirectories(home.resolve("iCloud").resolve("runwayd"));

        library.share("Household", synced);

        assertTrue(library.isShared("Household"));
        assertEquals(List.of("Household", "Mine"), library.names());
        assertTrue(Files.exists(synced.resolve("Household.json")), "the file is in the synced folder");
        assertFalse(Files.exists(library.folder().resolve("Household.json")), "moved, not copied");
        assertEquals(synced.resolve("Household.json"), library.open("Household").file());
    }

    @Test
    void theOtherMacOpensTheSameFile() throws Exception {
        library.create("Household");
        Path synced = Files.createDirectories(home.resolve("iCloud"));
        library.share("Household", synced);

        BudgetLibrary theirs = new BudgetLibrary(Files.createDirectory(home.resolve("theirs")));
        theirs.create("Their own");
        String listedAs = theirs.openShared(synced.resolve("Household.json"));

        assertEquals("Household", listedAs);
        assertEquals(library.open("Household").file(), theirs.open("Household").file(), "one file, two computers");
        assertEquals("Household", theirs.openShared(synced.resolve("Household.json")), "opening it twice doesn't add it twice");
        assertEquals(List.of("Household", "Their own"), theirs.names());
    }

    @Test
    void renamingOrRemovingASharedBudgetNeverTouchesTheSharedFile() throws Exception {
        library.create("Household");
        library.create("Mine");
        Path synced = Files.createDirectories(home.resolve("iCloud"));
        library.share("Household", synced);

        library.rename("Household", "Our money");
        assertEquals(List.of("Mine", "Our money"), library.names());
        assertTrue(Files.exists(synced.resolve("Household.json")), "the file keeps its name for the other Mac");

        library.delete("Our money");
        assertEquals(List.of("Mine"), library.names());
        assertTrue(Files.exists(synced.resolve("Household.json")), "removed from the list, not deleted");
    }

    @Test
    void aSharedBudgetThatCantBeReachedIsSkippedAtStartUp() throws Exception {
        library.create("Mine");
        Path synced = Files.createDirectories(home.resolve("iCloud"));
        library.create("Household");
        library.share("Household", synced);
        library.open("Household");
        Files.delete(synced.resolve("Household.json")); // e.g. iCloud Drive isn't running

        assertEquals("Mine", library.startingBudget(), "opens one that's there instead");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> library.open("Household"));
        assertTrue(error.getMessage().contains("Can't find the shared budget"), error.getMessage());
    }

    @Test
    void sharingIntoAFolderThatAlreadyHasThatFileIsRefused() throws Exception {
        library.create("Household");
        Path synced = Files.createDirectories(home.resolve("iCloud"));
        Files.writeString(synced.resolve("Household.json"), "{\"accounts\": []}");

        assertThrows(IllegalArgumentException.class, () -> library.share("Household", synced));
        assertFalse(library.isShared("Household"));
    }

    // ---- settings for this computer ----

    @Test
    void darkModeIsRememberedAndDoesntForgetTheLastBudget() {
        library.create("A");
        library.create("B");
        library.open("B");
        assertEquals(java.util.Optional.empty(), library.darkMode(), "never chosen yet");

        library.setDarkMode(false);

        BudgetLibrary reopened = new BudgetLibrary(home);
        assertEquals(java.util.Optional.of(false), reopened.darkMode());
        assertEquals("B", reopened.startingBudget(), "saving dark mode kept the last budget");

        reopened.open("A");
        assertEquals(java.util.Optional.of(false), new BudgetLibrary(home).darkMode(), "and the other way round");
    }

    // ---- helpers ----

    private FinanceService service(String budget) {
        return new FinanceService(library.open(budget));
    }

    private static Account checking(String name) {
        return new Account(0, name, AccountType.CHECKING, BigDecimal.ZERO, LocalDate.of(2026, 1, 1), null);
    }
}
