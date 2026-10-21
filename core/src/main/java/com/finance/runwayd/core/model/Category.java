package com.finance.runwayd.core.model;

/**
 * What a bill or paycheck is for, used to group the Bills list.
 *
 * <p>A fixed list based on the standard categories budgeting apps use (Mint,
 * Monarch, YNAB). Picking from a short, fixed list is quicker than typing, and
 * keeps the groups consistent.
 *
 * <p>The order here is the order the groups appear on screen: money in first,
 * then the big essentials, then everything else.
 */
public enum Category {
    INCOME("Income"),
    HOUSING("Housing"),
    UTILITIES("Utilities"),
    FOOD("Food & Groceries"),
    TRANSPORTATION("Transportation"),
    INSURANCE("Insurance"),
    HEALTHCARE("Healthcare"),
    DEBT_PAYMENTS("Debt Payments"),
    KIDS("Kids & Childcare"),
    PETS("Pets"),
    SUBSCRIPTIONS("Subscriptions"),
    PERSONAL("Personal"),
    SAVINGS("Savings"),
    OTHER("Other");

    private final String label;

    Category(String label) {
        this.label = label;
    }

    /** Friendly name for showing on screen, e.g. "Debt Payments". */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
