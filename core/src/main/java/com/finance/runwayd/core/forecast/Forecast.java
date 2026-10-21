package com.finance.runwayd.core.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The answer to "what will my accounts look like on {@code through}?"
 *
 * <p>Produced by {@link Forecaster}. Everything in here is read-only.
 *
 * @param through     the date the forecast runs up to (inclusive)
 * @param balances    account id -> balance on {@code through}
 * @param ledger      every balance change between now and {@code through}, oldest first
 * @param lowPoints   cash account id -> the lowest that account gets before {@code through}
 * @param payoffDates debt account id -> the day that debt reaches zero (only if it happens before {@code through})
 */
public record Forecast(
        LocalDate through,
        Map<Integer, BigDecimal> balances,
        List<LedgerEntry> ledger,
        Map<Integer, LowPoint> lowPoints,
        Map<Integer, LocalDate> payoffDates) {

    /** The lowest balance a cash account reaches, and the day it happens. */
    public record LowPoint(BigDecimal balance, LocalDate date) {
    }

    public Forecast {
        balances = Map.copyOf(balances);
        ledger = List.copyOf(ledger);
        lowPoints = Map.copyOf(lowPoints);
        payoffDates = Map.copyOf(payoffDates);
    }

    /** Balance of one account on the forecast date. Zero if the account isn't known. */
    public BigDecimal balanceOf(int accountId) {
        return balances.getOrDefault(accountId, BigDecimal.ZERO);
    }

    /** Just the ledger lines for one account, oldest first. */
    public List<LedgerEntry> ledgerFor(int accountId) {
        return ledger.stream()
                .filter(entry -> entry.accountId() == accountId)
                .toList();
    }

    /** Lowest balance for a cash account. Empty for debt accounts. */
    public Optional<LowPoint> lowPointOf(int accountId) {
        return Optional.ofNullable(lowPoints.get(accountId));
    }

    /** The day a debt hits zero, if it does before the forecast date. */
    public Optional<LocalDate> payoffDateOf(int accountId) {
        return Optional.ofNullable(payoffDates.get(accountId));
    }
}
