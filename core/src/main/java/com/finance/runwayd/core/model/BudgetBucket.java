package com.finance.runwayd.core.model;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * One place your extra cash goes each paycheck, e.g. "Savings: 30%" or "My spending: $400".
 *
 * @param name        what it's for, e.g. "Investments"
 * @param value       the dollar amount, or the percent (30 means 30%), depending on {@code unit}
 * @param unit        dollars or percent
 * @param destination what happens to the money in the forecast: it's spent, it pays down debts, or it
 *                    moves into one of your accounts. Missing in older data files; then a bucket with
 *                    "debt" in its name pays debts
 * @param accountId   for a debt bucket: the one debt to pay (e.g. Visa), or null for highest rate first;
 *                    once a chosen debt is paid off, the money stays in checking.
 *                    For an account bucket: the account the money goes into (e.g. Savings).
 *                    Older files call this "debtAccountId"
 */
public record BudgetBucket(String name, BigDecimal value, Unit unit, Destination destination,
                           @JsonAlias("debtAccountId") Integer accountId) {

    /** Whether a bucket is a set dollar amount or a share of what's available. */
    public enum Unit {
        DOLLARS("$"),
        PERCENT("%");

        private final String symbol;

        Unit(String symbol) {
            this.symbol = symbol;
        }

        @Override
        public String toString() {
            return symbol;
        }
    }

    /** Where a bucket's money goes when the budget is included in the forecast. */
    public enum Destination {
        /** Leaves your accounts: spending, savings and investments held elsewhere. */
        SPENT("Spent"),
        /** Pays down debts: the one you pick, or highest interest rate first. */
        DEBT("Pays down debt"),
        /** Moves into one of your own accounts, e.g. savings or investments, so its balance grows. */
        ACCOUNT("Into an account");

        private final String label;

        Destination(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A bucket whose money is spent. */
    public BudgetBucket(String name, BigDecimal value, Unit unit) {
        this(name, value, unit, Destination.SPENT, null);
    }

    /** A bucket that's spent, or pays debts highest rate first. */
    public BudgetBucket(String name, BigDecimal value, Unit unit, Destination destination) {
        this(name, value, unit, destination, null);
    }

    public BudgetBucket {
        Objects.requireNonNull(unit, "unit");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Each bucket needs a name.");
        }
        if (value == null) {
            value = BigDecimal.ZERO;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("\"" + name.trim() + "\" can't be negative.");
        }
        if (unit == Unit.PERCENT && value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("\"" + name.trim() + "\" can't be more than 100%.");
        }
        if (destination == null) {
            // Older files: guess from the name, so "Paying down debt" keeps working.
            destination = name.toLowerCase(Locale.ROOT).contains("debt") ? Destination.DEBT : Destination.SPENT;
        }
        if (destination == Destination.SPENT) {
            accountId = null; // spent money doesn't go to an account
        }
        if (destination == Destination.ACCOUNT && accountId == null) {
            throw new IllegalArgumentException("Pick the account \"" + name.trim() + "\" goes into.");
        }
        name = name.trim();
    }
}
