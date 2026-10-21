package com.finance.runwayd.core.debt;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoanCalculatorTest {

    @Test
    void matchesTheLenderFormula() {
        // A personal loan: $15,000 at 18% with 72 payments left (worked out by hand: $342.12).
        assertEquals(new BigDecimal("342.12"),
                LoanCalculator.monthlyPayment(new BigDecimal("15000.00"), new BigDecimal("18.00"), 72));
        // $15,500 over 75 payments.
        assertEquals(new BigDecimal("345.66"),
                LoanCalculator.monthlyPayment(new BigDecimal("15500.00"), new BigDecimal("18.00"), 75));
    }

    @Test
    void zeroInterestIsJustBalanceDividedByPayments() {
        assertEquals(new BigDecimal("100.00"),
                LoanCalculator.monthlyPayment(new BigDecimal("1200.00"), BigDecimal.ZERO, 12));
    }

    @Test
    void needsAtLeastOnePaymentAndSomethingOwed() {
        assertThrows(IllegalArgumentException.class,
                () -> LoanCalculator.monthlyPayment(new BigDecimal("1000"), BigDecimal.TEN, 0));
        assertThrows(IllegalArgumentException.class,
                () -> LoanCalculator.monthlyPayment(BigDecimal.ZERO, BigDecimal.TEN, 12));
    }
}
