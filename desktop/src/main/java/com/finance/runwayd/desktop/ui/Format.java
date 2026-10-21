package com.finance.runwayd.desktop.ui;

import com.finance.runwayd.core.model.ScheduledItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Turns money and dates into text for the screen, and typed text back into money.
 * Keeping it all here means every screen shows "$1,234.56" and "Oct 18, 2026" the same way.
 */
public final class Format {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private Format() {
    }

    /** "$1,234.56" or "-$1,234.56". Empty text for null. */
    public static String money(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        // NumberFormat isn't thread-safe, so make a fresh one each time. It's cheap.
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }

    /** What the "paid" button says: "Mark received" for income, "Mark paid" for everything else. */
    public static String payButtonText(ScheduledItem item) {
        return item.kind() == ScheduledItem.Kind.INCOME ? "Mark received" : "Mark paid";
    }

    /** "Oct 18, 2026". Empty text for null. */
    public static String date(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    /** "Oct 2026", for chart labels where space is tight. */
    public static String monthYear(LocalDate date) {
        return MONTH_YEAR.format(date);
    }

    /** "Oct 21", for chart labels covering a few months. */
    public static String monthDay(LocalDate date) {
        return MONTH_DAY.format(date);
    }

    /**
     * Reads money the way people type it: "1450", "1,450.00" or "$1,450".
     *
     * @throws IllegalArgumentException with a friendly message if it isn't a number
     */
    public static BigDecimal parseMoney(String text, String fieldName) {
        return parseNumber(text, fieldName).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Reads any number, ignoring "$", "%" and commas, e.g. an interest rate like "6.125%".
     *
     * @throws IllegalArgumentException with a friendly message if it isn't a number
     */
    public static BigDecimal parseNumber(String text, String fieldName) {
        String cleaned = text == null ? "" : text.replaceAll("[$,%]", "").trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be a number, like 1450.00");
        }
    }

    /** Money as a plain number for an edit box: 1450.00 (no $ or commas). */
    public static String moneyForEditing(BigDecimal amount) {
        return amount == null ? "" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
