package com.finance.runwayd.core.model;

/**
 * What kind of account this is.
 *
 * <p>The only thing the math really cares about is {@link #isDebt()}:
 * <ul>
 *   <li>Cash accounts (checking, savings) hold money you HAVE. Paying in makes the balance go up.</li>
 *   <li>Debt accounts (credit cards, loans) hold money you OWE. Paying in makes the balance go down.</li>
 * </ul>
 * The rest is just a label to help you tell accounts apart.
 */
public enum AccountType {
    CHECKING("Checking", false),
    SAVINGS("Savings", false),
    CREDIT_CARD("Credit card", true),
    LOAN("Loan", true);

    private final String label;
    private final boolean debt;

    AccountType(String label, boolean debt) {
        this.label = label;
        this.debt = debt;
    }

    /** Friendly name for showing on screen, e.g. "Credit card". */
    public String label() {
        return label;
    }

    /** True if the balance on this account is money you owe. */
    public boolean isDebt() {
        return debt;
    }

    @Override
    public String toString() {
        return label;
    }
}
