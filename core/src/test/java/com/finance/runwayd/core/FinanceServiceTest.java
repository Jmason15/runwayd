package com.finance.runwayd.core;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.Category;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.PaymentRule;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.core.storage.JsonDataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the service together with the real JSON file store, written to a temporary folder. */
class FinanceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void dataSurvivesARestart() {
        Path file = tempDir.resolve("data.json");
        FinanceService first = new FinanceService(new JsonDataStore(file));
        Account checking = first.addAccount(checking("1234.56"));
        first.addItem(new ScheduledItem(0, "Phone", new BigDecimal("50.00"), Frequency.MONTHLY,
                LocalDate.of(2026, 1, 5), null, checking.id(), null));

        // A second service reading the same file simulates closing and reopening the app.
        FinanceService reopened = new FinanceService(new JsonDataStore(file));

        assertEquals(1, reopened.accounts().size());
        assertEquals(new BigDecimal("1234.56"), reopened.accounts().get(0).balance());
        assertEquals("Phone", reopened.items().get(0).name());
    }

    @Test
    void newRecordsGetTheNextId() {
        FinanceService service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));

        assertEquals(1, service.addAccount(checking("0")).id());
        assertEquals(2, service.addAccount(checking("0")).id());
    }

    @Test
    void cannotDeleteAnAccountThatABillStillUses() {
        FinanceService service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        Account checking = service.addAccount(checking("0"));
        service.addItem(new ScheduledItem(0, "Rent", new BigDecimal("900"), Frequency.MONTHLY,
                LocalDate.of(2026, 1, 1), null, checking.id(), null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deleteAccount(checking.id()));
        assertEquals("This account is still used by: Rent. Change or delete those first.", error.getMessage());
    }

    @Test
    void cannotAddAnItemForAMissingAccount() {
        FinanceService service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));

        assertThrows(IllegalArgumentException.class, () -> service.addItem(new ScheduledItem(0, "Rent",
                new BigDecimal("900"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null, 99, null)));
    }

    @Test
    void markPaidMovesTheBillToItsNextDate() {
        FinanceService service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        LocalDate today = LocalDate.now();
        Account checking = service.addAccount(checking("1000").withBalance(new BigDecimal("1000"), today.minusDays(1)));
        ScheduledItem phone = service.addItem(new ScheduledItem(0, "Phone", new BigDecimal("50"), Frequency.MONTHLY,
                today.plusDays(3), null, checking.id(), null));

        assertEquals(today.plusDays(3), service.nextDue(phone).orElseThrow());

        service.markPaid(phone.id());

        ScheduledItem paid = service.items().get(0);
        assertEquals(today.plusDays(3).plusMonths(1), service.nextDue(paid).orElseThrow());
        // The paid occurrence no longer lowers the forecast.
        assertEquals(new BigDecimal("1000"), service.forecast(today.plusDays(10)).balanceOf(checking.id()));
    }

    @Test
    void paidThroughSurvivesARestart() {
        Path file = tempDir.resolve("data.json");
        FinanceService first = new FinanceService(new JsonDataStore(file));
        Account checking = first.addAccount(checking("0"));
        ScheduledItem rent = first.addItem(new ScheduledItem(0, "Rent", new BigDecimal("900"), Frequency.MONTHLY,
                LocalDate.of(2026, 1, 1), null, checking.id(), null));
        first.markPaid(rent.id());

        FinanceService reopened = new FinanceService(new JsonDataStore(file));

        // Jan 1 is the balance date. Bills due that day still count until ticked off, so that's the one paid.
        assertEquals(LocalDate.of(2026, 1, 1), reopened.items().get(0).paidThrough());
    }

    @Test
    void aBillsWebsiteSurvivesARestart() {
        Path file = tempDir.resolve("data.json");
        FinanceService first = new FinanceService(new JsonDataStore(file));
        Account checking = first.addAccount(checking("0"));
        first.addItem(new ScheduledItem(0, "Electric", null, new BigDecimal("90"), null, Frequency.MONTHLY,
                LocalDate.of(2026, 1, 1), null, checking.id(), null, null, "myelectric.com"));

        FinanceService reopened = new FinanceService(new JsonDataStore(file));

        assertEquals("https://myelectric.com", reopened.items().get(0).website());
    }

    @Test
    void categoryAndAmountEditsAreSaved() {
        Path file = tempDir.resolve("data.json");
        FinanceService first = new FinanceService(new JsonDataStore(file));
        Account checking = first.addAccount(checking("0"));
        ScheduledItem rent = first.addItem(new ScheduledItem(0, "Rent", Category.HOUSING, new BigDecimal("900.00"),
                PaymentRule.FIXED, Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null, checking.id(), null, null));
        first.updateItem(rent.withAmount(new BigDecimal("950.00")));

        ScheduledItem reopened = new FinanceService(new JsonDataStore(file)).items().get(0);

        assertEquals(Category.HOUSING, reopened.category());
        assertEquals(new BigDecimal("950.00"), reopened.amount());
    }

    @Test
    void dataFilesFromBeforeCategoriesStillLoad() throws Exception {
        Path file = tempDir.resolve("old.json");
        Files.writeString(file, """
                {
                  "accounts": [{"id": 1, "name": "Checking", "type": "CHECKING", "balance": 100,
                                "balanceDate": "2026-01-01", "interestRatePercent": 0}],
                  "items": [{"id": 1, "name": "Rent", "amount": 900, "frequency": "MONTHLY",
                             "firstDate": "2026-01-01", "endDate": null, "fromAccountId": 1, "toAccountId": null}]
                }
                """);

        ScheduledItem rent = new FinanceService(new JsonDataStore(file)).items().get(0);

        assertEquals(Category.OTHER, rent.category());
    }

    @Test
    void filesWithSeveralPaycheckSplitsLoadTheOneInUse() throws Exception {
        // Saved by the short-lived version that kept several splits in one file.
        Path file = tempDir.resolve("plans.json");
        Files.writeString(file, """
                {
                  "accounts": [], "items": [],
                  "budgets": [
                    {"name": "My budget", "cushion": 1000, "buckets": []},
                    {"name": "Aggressive", "cushion": 500,
                     "buckets": [{"name": "Debt", "value": 60, "unit": "PERCENT", "destination": "DEBT"}]}
                  ],
                  "activeBudget": "Aggressive",
                  "budgetInForecast": false
                }
                """);

        FinanceService service = new FinanceService(new JsonDataStore(file));

        assertEquals(0, new BigDecimal("500").compareTo(service.budget().cushion()));
        assertEquals("Debt", service.budget().buckets().get(0).name());
        assertEquals(false, service.budgetInForecast());

        // Saved back in the normal format, without the old fields.
        service.setRollPaidOffPayments(true);
        String saved = Files.readString(file);
        assertTrue(saved.contains("\"budget\"") && !saved.contains("\"budgets\"") && !saved.contains("activeBudget"),
                saved);
    }

    @Test
    void anOldExtraPaymentIsDroppedOnLoad() throws Exception {
        // Files saved before the extra-payment feature was removed have "extra" on a payment.
        Path file = tempDir.resolve("old.json");
        Files.writeString(file, """
                {
                  "accounts": [{"id": 1, "name": "Checking", "type": "CHECKING", "balance": 1000,
                                "balanceDate": "2026-01-01", "interestRatePercent": 0}],
                  "items": [{"id": 1, "name": "Phone", "amount": 50, "extra": 100, "frequency": "MONTHLY",
                             "firstDate": "2026-01-05", "endDate": null, "fromAccountId": 1, "toAccountId": null}]
                }
                """);

        FinanceService service = new FinanceService(new JsonDataStore(file));

        // Only the $50 counts; the $100 extra is gone.
        assertEquals(new BigDecimal("50"), service.amountEachTime(service.items().get(0)));
    }

    @Test
    void subscriptionsTotalPerMonthAndPerYear() {
        // Six monthly subscriptions plus a $139 yearly one.
        FinanceService service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        LocalDate jan1 = LocalDate.of(2026, 1, 1);
        List<ScheduledItem> subscriptions = List.of(
                bill(1, "News", "20.00", Frequency.MONTHLY, jan1),
                bill(2, "Podcasts", "17.00", Frequency.MONTHLY, jan1),
                bill(3, "Netflix", "8.99", Frequency.MONTHLY, jan1),
                bill(4, "Apple Music", "11.99", Frequency.MONTHLY, jan1),
                bill(5, "Icloud", "9.99", Frequency.MONTHLY, jan1),
                bill(6, "Prime Video", "4.99", Frequency.MONTHLY, jan1),
                bill(7, "Amazon", "139.00", Frequency.YEARLY, jan1));

        // Monthly ones: 72.96 a month. Amazon: 139 ÷ 12 = 11.58. Total 84.54 a month.
        assertEquals(new BigDecimal("-84.54"), service.monthlyTotal(subscriptions));
        // Per year: 72.96 × 12 = 875.52, plus Amazon's real 139.00 = 1,014.52.
        assertEquals(new BigDecimal("-1014.52"), service.yearlyTotal(subscriptions));
    }

    private static ScheduledItem bill(int id, String name, String amount, Frequency frequency, LocalDate first) {
        return new ScheduledItem(id, name, new BigDecimal(amount), frequency, first, null, 1, null);
    }

    @Test
    void monthlyTotalAddsIncomeAndSubtractsBills() {
        FinanceService service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        LocalDate jan1 = LocalDate.of(2026, 1, 1);
        List<ScheduledItem> group = List.of(
                new ScheduledItem(1, "Pay", new BigDecimal("1000"), Frequency.MONTHLY, jan1, null, null, 1),
                new ScheduledItem(2, "Rent", new BigDecimal("600"), Frequency.MONTHLY, jan1, null, 1, null));

        assertEquals(new BigDecimal("400.00"), service.monthlyTotal(group));
    }

    @Test
    void websiteIsSavedAndSurvivesABalanceUpdate() {
        Path file = tempDir.resolve("data.json");
        FinanceService first = new FinanceService(new JsonDataStore(file));
        Account card = first.addAccount(new Account(0, "Visa", AccountType.CREDIT_CARD, new BigDecimal("500"),
                LocalDate.of(2026, 1, 1), null, "mybank.com"));
        first.updateBalance(card.id(), new BigDecimal("450"), LocalDate.of(2026, 2, 1));

        Account reopened = new FinanceService(new JsonDataStore(file)).accounts().get(0);

        assertEquals("https://mybank.com", reopened.website());
    }

    private static Account checking(String balance) {
        return new Account(0, "Checking", AccountType.CHECKING, new BigDecimal(balance), LocalDate.of(2026, 1, 1), null);
    }
}
