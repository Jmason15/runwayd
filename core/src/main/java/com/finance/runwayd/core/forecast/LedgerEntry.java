package com.finance.runwayd.core.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One line in an account's future history: "On Nov 1, Paycheck, +$3,400, balance $5,120".
 *
 * @param date         the day it happens
 * @param description  what caused it, e.g. "Mortgage" or "Interest (24.99%)"
 * @param accountId    which account's balance changed
 * @param change       how much the balance moved (+ up, - down). On a debt account, + means you owe more
 * @param balanceAfter the account's balance right after this change
 * @param interest     true if this line is an interest charge (lets the Debt tab total up interest paid)
 * @param itemId       the scheduled item (bill, paycheck, payment) behind this line, or null for interest.
 *                     Lets the Budget tab find paydays.
 */
public record LedgerEntry(
        LocalDate date,
        String description,
        int accountId,
        BigDecimal change,
        BigDecimal balanceAfter,
        boolean interest,
        Integer itemId) {

    /** True for a budget split (Budget tab): the only lines with no scheduled item that aren't interest. */
    public boolean fromBudget() {
        return !interest && itemId == null;
    }
}
