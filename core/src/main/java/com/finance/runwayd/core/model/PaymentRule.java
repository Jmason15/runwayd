package com.finance.runwayd.core.model;

/**
 * How much a scheduled item pays each time.
 *
 * <ul>
 *   <li><b>FIXED</b>: the same amount every time. Bills, paychecks and loans like a
 *       car or shed loan.</li>
 *   <li><b>CARD_MINIMUM</b>: the credit card's minimum payment, worked out fresh
 *       each time from what's owed (see {@link Account#minimumPaymentOn}). As the
 *       balance drops, so does the payment.</li>
 * </ul>
 *
 * <p>To put more toward the card you want to pay off first, use the Budget tab's
 * debt bucket.
 */
public enum PaymentRule {
    FIXED("Fixed amount"),
    CARD_MINIMUM("Card minimum");

    private final String label;

    PaymentRule(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
