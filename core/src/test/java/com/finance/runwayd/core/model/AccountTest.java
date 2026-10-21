package com.finance.runwayd.core.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountTest {

    @Test
    void websiteWithoutHttpsGetsItAdded() {
        assertEquals("https://mybank.com", withWebsite("mybank.com").website());
        assertEquals("https://secure.mybank.com/login", withWebsite("  secure.mybank.com/login ").website());
    }

    @Test
    void fullWebsiteIsKeptAsTyped() {
        assertEquals("http://example.com/pay", withWebsite("http://example.com/pay").website());
    }

    @Test
    void blankWebsiteMeansNone() {
        assertNull(withWebsite("   ").website());
        assertNull(withWebsite(null).website());
    }

    @Test
    void textThatIsNotAWebsiteIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> withWebsite("my bank account"));
        assertThrows(IllegalArgumentException.class, () -> withWebsite("mybank"));
    }

    private static Account withWebsite(String website) {
        return new Account(1, "Visa", AccountType.CREDIT_CARD, BigDecimal.TEN, LocalDate.of(2026, 1, 1),
                null, website);
    }
}
