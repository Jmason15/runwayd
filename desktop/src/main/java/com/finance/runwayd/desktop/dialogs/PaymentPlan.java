package com.finance.runwayd.desktop.dialogs;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.PaymentRule;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A monthly debt payment someone filled in, ready to hand to FinanceService.createPayment.
 *
 * @param rule        the card minimum, or a fixed amount
 * @param fixedAmount the monthly amount for a fixed payment; null for the card minimum
 * @param firstDate   the next due date
 * @param paidFrom    the account the money comes out of
 */
public record PaymentPlan(PaymentRule rule, BigDecimal fixedAmount, LocalDate firstDate, Account paidFrom) {
}
