package com.finance.runwayd.core.debt;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.forecast.LedgerEntry;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Card minimum payments, fixed payments, and switching between them. */
class PaymentPlanTest {

    @TempDir
    Path tempDir;

    private final LocalDate today = LocalDate.now();
    private FinanceService service;
    private Account checking;

    @BeforeEach
    void setUp() {
        service = new FinanceService(new JsonDataStore(tempDir.resolve("data.json")));
        checking = service.addAccount(new Account(0, "Checking", AccountType.CHECKING,
                new BigDecimal("50000.00"), today, null));
    }

    // ---- the minimum payment formula ----

    @Test
    void minimumIsInterestPlusOnePercent() {
        Account store = card("Store card", "4000.00", "18");
        // Interest 60.00 (4,000 × 18% ÷ 12) + 1% 40.00
        assertEquals(new BigDecimal("100.00"), store.minimumPaymentOn(new BigDecimal("4000.00")));
    }

    @Test
    void minimumIsNeverBelowTheFloorOrAboveTheBalance() {
        Account card = card("Card", "0", "20");
        assertEquals(new BigDecimal("35.00"), card.minimumPaymentOn(new BigDecimal("500.00"))); // 8.33 + 5.00 < 35
        assertEquals(new BigDecimal("20.00"), card.minimumPaymentOn(new BigDecimal("20.00")));  // owe less than $35
        assertEquals(new BigDecimal("0.00"), card.minimumPaymentOn(BigDecimal.ZERO));
    }

    // ---- the forecast follows the minimum as the balance drops ----

    @Test
    void minimumPaymentShrinksWithTheBalance() {
        // $10,000 at 0%: first minimum is 1% = $100.00, leaving $9,900, so the next is $99.00.
        Account card = service.addAccount(card("Card", "10000.00", "0"));
        service.addItem(minimumPayment(card));

        List<LedgerEntry> payments = service.forecast(today.plusMonths(2)).ledgerFor(card.id());

        assertEquals(new BigDecimal("-100.00"), payments.get(0).change());
        assertEquals(new BigDecimal("-99.00"), payments.get(1).change());
    }

    @Test
    void paymentCanSwitchBetweenFixedAndCardMinimum() {
        Account card = service.addAccount(card("Card", "10000.00", "0"));
        ScheduledItem fixed = service.addItem(new ScheduledItem(0, "Card payment", new BigDecimal("412.00"),
                Frequency.MONTHLY, today.plusDays(5), null, checking.id(), card.id()));

        service.setPaymentRule(fixed.id(), PaymentRule.CARD_MINIMUM);
        assertEquals(PaymentRule.CARD_MINIMUM, service.items().get(0).paymentRule());
        assertEquals(new BigDecimal("100.00"), service.amountEachTime(service.items().get(0))); // 1% of $10,000

        // Back to fixed: starts at today's minimum so the payment doesn't jump.
        service.setPaymentRule(fixed.id(), PaymentRule.FIXED);
        assertEquals(PaymentRule.FIXED, service.items().get(0).paymentRule());
        assertEquals(new BigDecimal("100.00"), service.items().get(0).amount());
    }

    @Test
    void createdLoanPaymentPaysItOffOnSchedule() {
        // 12 payments at the calculated amount should clear the loan on the 12th one.
        Account loan = service.addAccount(new Account(0, "Loan", AccountType.LOAN, new BigDecimal("1200.00"),
                today, BigDecimal.ZERO));
        BigDecimal monthly = service.loanPayment(loan.balance(), loan.interestRatePercent(), 12);
        ScheduledItem payment = service.createPayment(loan.id(), PaymentRule.FIXED, monthly, today.plusDays(5),
                checking.id());

        assertEquals("Loan payment", payment.name());
        assertEquals(Category.DEBT_PAYMENTS, payment.category());
        assertEquals(today.plusDays(5).plusMonths(11),
                service.forecast(today.plusYears(2)).payoffDateOf(loan.id()).orElseThrow());
    }

    @Test
    void createdCardPaymentFollowsTheMinimum() {
        Account card = service.addAccount(card("Card", "10000.00", "0"));

        ScheduledItem payment = service.createPayment(card.id(), PaymentRule.CARD_MINIMUM, null, today.plusDays(5),
                checking.id());

        assertEquals(PaymentRule.CARD_MINIMUM, payment.paymentRule());
        assertEquals(new BigDecimal("100.00"), service.amountEachTime(payment));
    }

    @Test
    void cardMinimumPaymentIsSavedAndReloaded() {
        Path file = tempDir.resolve("data.json");
        Account card = service.addAccount(card("Card", "1000.00", "10"));
        service.addItem(minimumPayment(card));

        ScheduledItem reloaded = new FinanceService(new JsonDataStore(file)).items().get(0);

        assertEquals(PaymentRule.CARD_MINIMUM, reloaded.paymentRule());
    }

    @Test
    void cardMinimumMustPayIntoSomething() {
        assertThrows(IllegalArgumentException.class, () -> new ScheduledItem(0, "Bad", Category.DEBT_PAYMENTS, null,
                PaymentRule.CARD_MINIMUM, Frequency.MONTHLY, today, null, 1, null, null));
    }

    // ---- helpers ----

    private Account card(String name, String owed, String ratePercent) {
        return new Account(0, name, AccountType.CREDIT_CARD, new BigDecimal(owed), today, new BigDecimal(ratePercent));
    }

    /** A monthly card-minimum payment from checking, first one five days from now. */
    private ScheduledItem minimumPayment(Account card) {
        return new ScheduledItem(0, card.name() + " payment", Category.DEBT_PAYMENTS, null, PaymentRule.CARD_MINIMUM,
                Frequency.MONTHLY, today.plusDays(5), null, checking.id(), card.id(), null);
    }
}
