package com.finance.runwayd.core.forecast;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.ScheduledItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForecasterTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    // Account ids used throughout these tests.
    private static final int CHECKING = 1;
    private static final int CARD = 2;

    private final Forecaster forecaster = new Forecaster();

    @Test
    void incomeAndBillsAddUp() {
        Account checking = cash(CHECKING, "1000.00");
        List<ScheduledItem> items = List.of(
                income("Pay", "2000.00", Frequency.MONTHLY, START.plusDays(14)),   // Jan 15, Feb 15
                bill("Rent", "1500.00", Frequency.MONTHLY, START.plusDays(1)));    // Jan 2, Feb 2

        Forecast forecast = forecaster.run(List.of(checking), items, LocalDate.of(2026, 2, 28));

        // 1000 + 2×2000 - 2×1500
        assertEquals(new BigDecimal("2000.00"), forecast.balanceOf(CHECKING));
    }

    @Test
    void lowPointFindsTheTightestDay() {
        Account checking = cash(CHECKING, "1000.00");
        List<ScheduledItem> items = List.of(
                bill("Rent", "1500.00", Frequency.ONCE, LocalDate.of(2026, 1, 2)),
                income("Pay", "2000.00", Frequency.ONCE, LocalDate.of(2026, 1, 15)));

        Forecast forecast = forecaster.run(List.of(checking), items, LocalDate.of(2026, 1, 31));

        Forecast.LowPoint low = forecast.lowPointOf(CHECKING).orElseThrow();
        assertEquals(new BigDecimal("-500.00"), low.balance());
        assertEquals(LocalDate.of(2026, 1, 2), low.date());
    }

    @Test
    void paydayIsCountedBeforeBillsOnTheSameDay() {
        Account checking = cash(CHECKING, "100.00");
        LocalDate sameDay = LocalDate.of(2026, 1, 15);
        List<ScheduledItem> items = List.of(
                bill("Rent", "500.00", Frequency.ONCE, sameDay),
                income("Pay", "1000.00", Frequency.ONCE, sameDay));

        Forecast forecast = forecaster.run(List.of(checking), items, sameDay);

        // If rent went first we'd dip to -400. With pay first, the low point stays at the start.
        assertEquals(new BigDecimal("100.00"), forecast.lowPointOf(CHECKING).orElseThrow().balance());
    }

    @Test
    void debtPaymentsStopOnceItIsPaidOff() {
        Account checking = cash(CHECKING, "5000.00");
        Account card = debt(CARD, "250.00", "0");
        ScheduledItem payment = new ScheduledItem(1, "Pay card", new BigDecimal("100.00"),
                Frequency.MONTHLY, LocalDate.of(2026, 1, 10), null, CHECKING, CARD);

        Forecast forecast = forecaster.run(List.of(checking, card), List.of(payment), LocalDate.of(2026, 6, 30));

        // Payments: 100 (Jan), 100 (Feb), then only the 50 left (Mar), then nothing.
        assertEquals(new BigDecimal("0.00"), forecast.balanceOf(CARD));
        assertEquals(new BigDecimal("4750.00"), forecast.balanceOf(CHECKING));
        assertEquals(LocalDate.of(2026, 3, 10), forecast.payoffDateOf(CARD).orElseThrow());
    }

    @Test
    void interestIsAddedMonthlyOnTheFirst() {
        // 1200 owed at 12% a year = 1% a month = 12.00 on Feb 1.
        Account card = debt(CARD, "1200.00", "12");

        Forecast forecast = forecaster.run(List.of(card), List.of(), LocalDate.of(2026, 2, 1));

        assertEquals(new BigDecimal("1212.00"), forecast.balanceOf(CARD));
    }

    @Test
    void billChargedToACreditCardIncreasesWhatYouOwe() {
        Account card = debt(CARD, "0.00", "0");
        ScheduledItem netflix = new ScheduledItem(1, "Netflix", new BigDecimal("15.00"),
                Frequency.ONCE, LocalDate.of(2026, 1, 5), null, CARD, null);

        Forecast forecast = forecaster.run(List.of(card), List.of(netflix), LocalDate.of(2026, 1, 31));

        assertEquals(new BigDecimal("15.00"), forecast.balanceOf(CARD));
    }

    @Test
    void eachAccountStartsFromItsOwnBalanceDate() {
        // Checking was updated Jan 20, so the Jan 10 bill is already in its balance.
        Account checking = new Account(CHECKING, "Checking", AccountType.CHECKING,
                new BigDecimal("1000.00"), LocalDate.of(2026, 1, 20), null);
        ScheduledItem bill = bill("Phone", "50.00", Frequency.MONTHLY, LocalDate.of(2026, 1, 10));

        Forecast forecast = forecaster.run(List.of(checking), List.of(bill), LocalDate.of(2026, 2, 28));

        // Only the Feb 10 bill counts.
        assertEquals(new BigDecimal("950.00"), forecast.balanceOf(CHECKING));
        assertEquals(1, forecast.ledgerFor(CHECKING).size());
    }

    @Test
    void noAccountsMeansAnEmptyForecast() {
        Forecast forecast = forecaster.run(List.of(), List.of(), START);

        assertTrue(forecast.ledger().isEmpty());
    }

    // ---- small builders so each test reads like a sentence ----

    private static Account cash(int id, String balance) {
        return new Account(id, "Checking", AccountType.CHECKING, new BigDecimal(balance), START, null);
    }

    private static Account debt(int id, String owed, String ratePercent) {
        return new Account(id, "Card", AccountType.CREDIT_CARD, new BigDecimal(owed), START, new BigDecimal(ratePercent));
    }

    private static ScheduledItem income(String name, String amount, Frequency frequency, LocalDate first) {
        return new ScheduledItem(0, name, new BigDecimal(amount), frequency, first, null, null, CHECKING);
    }

    private static ScheduledItem bill(String name, String amount, Frequency frequency, LocalDate first) {
        return new ScheduledItem(0, name, new BigDecimal(amount), frequency, first, null, CHECKING, null);
    }
}
