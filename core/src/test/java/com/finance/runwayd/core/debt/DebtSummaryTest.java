package com.finance.runwayd.core.debt;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.Category;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.PaymentRule;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.core.storage.JsonDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Debt totals, payments and payoff dates, using balances dated today so the numbers are easy to follow. */
class DebtSummaryTest {

    @TempDir
    Path tempDir;

    private final LocalDate today = LocalDate.now();
    private FinanceService service;
    private Account checking;

    @BeforeEach
    void setUp() {
        service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        checking = service.addAccount(new Account(0, "Checking", AccountType.CHECKING,
                new BigDecimal("5000.00"), today, null));
    }

    @Test
    void linkingABillMakesItPayDownTheDebt() {
        Account card = addDebt("Visa", "300.00");
        ScheduledItem bill = service.addItem(new ScheduledItem(0, "Store Card", Category.DEBT_PAYMENTS, new BigDecimal("100.00"),
                PaymentRule.FIXED, Frequency.MONTHLY, today.plusDays(5), null, checking.id(), null, null));

        service.linkBillToDebt(bill.id(), card.id());

        ScheduledItem linked = service.items().get(0);
        assertEquals(ScheduledItem.Kind.TRANSFER, linked.kind());
        assertEquals(Category.DEBT_PAYMENTS, linked.category(), "category is kept");
        // 300 owed, 100 a month: paid off on the third payment.
        assertEquals(today.plusDays(5).plusMonths(2), service.debtSummary(today).debts().get(0).payoffDate());
    }

    @Test
    void totalsAddUpAllDebts() {
        Account card = addDebt("Card", "1000.00");
        Account loan = addDebt("Loan", "4000.00");
        payMonthly("Card payment", "250.00", card);
        payMonthly("Loan payment", "500.00", loan);

        DebtSummary summary = service.debtSummary(today.plusMonths(1).plusDays(10));

        assertEquals(new BigDecimal("5000.00"), summary.totalToday());
        // Two payments each (day 5 of this month and next): 1000-500 + 4000-1000.
        assertEquals(new BigDecimal("3500.00"), summary.totalOnDate());
        assertEquals(new BigDecimal("250.00"), summary.debts().get(0).monthlyPayment());
    }

    @Test
    void debtFreeDateIsWhenTheLastDebtIsPaidOff() {
        Account card = addDebt("Card", "200.00");
        Account loan = addDebt("Loan", "500.00");
        payMonthly("Card payment", "100.00", card);   // paid off on payment 2
        payMonthly("Loan payment", "100.00", loan);
        // Loan: 500 -> 400 -> 300, then the card's $100 rolls over too: 300 -> 100 -> paid off on payment 4.

        DebtSummary summary = service.debtSummary(today);

        assertEquals(today.plusDays(5).plusMonths(3), summary.debtFreeDate());
    }

    @Test
    void withoutTheRolloverTheLastDebtTakesLonger() {
        service.setRollPaidOffPayments(false);
        Account card = addDebt("Card", "200.00");
        Account loan = addDebt("Loan", "500.00");
        payMonthly("Card payment", "100.00", card);
        payMonthly("Loan payment", "100.00", loan);   // only its own $100: paid off on payment 5

        assertEquals(today.plusDays(5).plusMonths(4), service.debtSummary(today).debtFreeDate());
    }

    @Test
    void chartShowsEachDebtMonthByMonthUntilDebtFree() {
        Account card = addDebt("Card", "200.00");
        Account loan = addDebt("Loan", "500.00");
        payMonthly("Card payment", "100.00", card);   // paid off on payment 2
        payMonthly("Loan payment", "100.00", loan);   // paid off on payment 5

        DebtSummary summary = service.debtSummary(today);
        List<DebtSummary.MonthOfDebt> months = summary.overTime();

        // Starts today with what's owed now...
        assertEquals(today, months.get(0).date());
        assertEquals(new BigDecimal("200.00"), months.get(0).owed(card.id()));
        assertEquals(new BigDecimal("500.00"), months.get(0).owed(loan.id()));
        // ...then the 1st of each month, and runs past the debt-free date.
        assertEquals(1, months.get(1).date().getDayOfMonth());
        LocalDate last = months.get(months.size() - 1).date();
        assertTrue(!last.isBefore(summary.debtFreeDate()), "the chart reaches the debt-free date");
        // At the end, everything is at zero.
        assertEquals(0, months.get(months.size() - 1).owed(card.id()).signum());
        assertEquals(0, months.get(months.size() - 1).owed(loan.id()).signum());
    }

    @Test
    void noDebtFreeDateIfADebtHasNoPayment() {
        service.setRollPaidOffPayments(false); // with it on, the card's payment would roll over to the loan
        Account card = addDebt("Card", "200.00");
        addDebt("Forgotten loan", "500.00");
        payMonthly("Card payment", "100.00", card);

        assertNull(service.debtSummary(today).debtFreeDate());
    }

    @Test
    void theRolloverPaysADebtThatHasNoPaymentOfItsOwn() {
        Account card = addDebt("Card", "200.00");
        Account loan = addDebt("Forgotten loan", "500.00");
        payMonthly("Card payment", "100.00", card);

        DebtSummary summary = service.debtSummary(today);

        // After the card (payment 2), its $100 a month goes to the loan: 5 more payments.
        assertEquals(today.plusDays(5).plusMonths(6), summary.debts().stream()
                .filter(row -> row.account().id() == loan.id()).findFirst().orElseThrow().payoffDate());
    }

    @Test
    void paymentIsSplitIntoInterestAndPrincipal() {
        // $1,200 at 12% a year = 1% a month = $12.00 interest. A $100 payment leaves $88.00 for principal.
        Account card = addDebt("Card", "1200.00", "12");
        payMonthly("Card payment", "100.00", card);

        DebtSummary summary = service.debtSummary(today);
        DebtSummary.DebtRow row = summary.debts().get(0);

        assertEquals(new BigDecimal("12.00"), row.interestPerMonth());
        assertEquals(new BigDecimal("88.00"), row.principalPerMonth());
        assertEquals(new BigDecimal("12.00"), summary.interestPerMonth());
        assertEquals(new BigDecimal("88.00"), summary.principalPerMonth());
    }

    @Test
    void paymentSmallerThanTheInterestMeansTheDebtGrows() {
        // $10,000 at 24% = $200.00 interest a month, but only $150 is paid.
        Account card = addDebt("Card", "10000.00", "24");
        payMonthly("Card payment", "150.00", card);

        DebtSummary.DebtRow row = service.debtSummary(today).debts().get(0);

        assertEquals(new BigDecimal("-50.00"), row.principalPerMonth());
        assertNull(row.payoffDate());
        assertNull(row.interestUntilPaidOff());
    }

    @Test
    void totalInterestUntilPaidOffAddsUpEveryCharge() {
        // Fixed dates so every step can be followed by hand:
        //   Jan 1  owe 1000.00 (12% a year)
        //   Jan 10 pay  600.00 -> 400.00
        //   Feb 1  interest 4.00 -> 404.00
        //   Feb 10 pay the last 404.00 -> paid off
        LocalDate jan1 = LocalDate.of(2026, 1, 1);
        Account loan = service.addAccount(new Account(0, "Loan", AccountType.LOAN, new BigDecimal("1000.00"),
                jan1, new BigDecimal("12")));
        service.addItem(new ScheduledItem(0, "Loan payment", new BigDecimal("600.00"), Frequency.MONTHLY,
                LocalDate.of(2026, 1, 10), null, checking.id(), loan.id()));

        DebtSummary.DebtRow row = service.debtSummary(today).debts().get(0);

        assertEquals(LocalDate.of(2026, 2, 10), row.payoffDate());
        assertEquals(new BigDecimal("4.00"), row.interestUntilPaidOff());
    }

    @Test
    void onlyCreditCardsAndLoansCanBePaidDown() {
        ScheduledItem bill = service.addItem(new ScheduledItem(0, "Rent", new BigDecimal("900"), Frequency.MONTHLY,
                today, null, checking.id(), null));

        assertThrows(IllegalArgumentException.class, () -> service.linkBillToDebt(bill.id(), checking.id()));
    }

    @Test
    void cashAndDebtAccountsAreListedSeparately() {
        addDebt("Card", "10.00");

        assertEquals(1, service.cashAccounts().size());
        assertEquals(1, service.debtAccounts().size());
    }

    private Account addDebt(String name, String owed) {
        return addDebt(name, owed, "0");
    }

    private Account addDebt(String name, String owed, String ratePercent) {
        return service.addAccount(new Account(0, name, AccountType.LOAN, new BigDecimal(owed), today,
                new BigDecimal(ratePercent)));
    }

    /** A monthly payment from checking toward {@code debt}, first one five days from now. */
    private void payMonthly(String name, String amount, Account debt) {
        service.addItem(new ScheduledItem(0, name, new BigDecimal(amount), Frequency.MONTHLY,
                today.plusDays(5), null, checking.id(), debt.id()));
    }
}
