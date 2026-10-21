package com.finance.runwayd.core.forecast;

import com.finance.runwayd.core.model.BudgetBucket.Destination;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One budget bucket's share of one paycheck, played into the forecast on payday,
 * e.g. "Budget: Savings, $302.70 out of Checking on Oct 25".
 *
 * <p>These aren't saved anywhere. BudgetPlans works them out from the Budget tab
 * each time a forecast is run, so they always follow the latest bills and balances.
 *
 * @param date          payday
 * @param description   shown in the ledger, e.g. "Budget: Savings"
 * @param amount        how much leaves the account
 * @param fromAccountId the account it comes out of (the budget's "keep at least" account)
 * @param destination   what the money does: it's spent (just leaves), pays down debts, or moves
 *                      into another of your accounts
 * @param accountId     for debts: the one debt to pay, or null for highest rate first.
 *                      For an account: the account it goes into
 * @param bucketName    the bucket it's for, e.g. "Savings" (each bucket is ticked off separately)
 */
public record BudgetMove(LocalDate date, String description, BigDecimal amount, int fromAccountId,
                         Destination destination, Integer accountId, String bucketName) {
}
