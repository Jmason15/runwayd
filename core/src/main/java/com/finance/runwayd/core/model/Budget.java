package com.finance.runwayd.core.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Your Budget tab settings: how much to always keep in checking, and how to split
 * the extra cash from each paycheck.
 *
 * @param cushionAccountId  the account to keep money in (usually checking). null = the first checking account
 * @param cushion           never let that account go below this, e.g. $1,000
 * @param buckets           where the extra goes, in order: dollar buckets are filled first, then percent buckets
 * @param includeInForecast true to show each paycheck's split in the forecast (money leaving checking, and
 *                          the debt bucket paying down debts). Missing in older data files; then true
 * @param bucketsDoneThrough for each bucket (by name), the last payday whose share you've moved: ticked off
 *                          on the Forecast tab's "This month" list, so the forecast doesn't take it out
 *                          again. A bucket that isn't listed has nothing ticked yet
 */
public record Budget(Integer cushionAccountId, BigDecimal cushion, List<BudgetBucket> buckets,
                     Boolean includeInForecast, Map<String, LocalDate> bucketsDoneThrough) {

    public Budget {
        if (cushion == null) {
            cushion = BigDecimal.ZERO;
        }
        if (cushion.signum() < 0) {
            throw new IllegalArgumentException("The amount to keep can't be negative.");
        }
        buckets = buckets == null ? List.of() : List.copyOf(buckets);
        if (includeInForecast == null) {
            includeInForecast = Boolean.TRUE;
        }
        bucketsDoneThrough = bucketsDoneThrough == null ? Map.of() : Map.copyOf(bucketsDoneThrough);
    }

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    /**
     * Percent buckets share what's left after the dollar buckets, so together they can't pass 100%.
     * Checked when you save (not when the file is loaded, so an old file can always be opened).
     *
     * @throws IllegalArgumentException with a friendly message if they add up to more than 100%
     */
    public void checkPercentTotal() {
        BigDecimal percentTotal = totalPercent(buckets);
        if (percentTotal.compareTo(ONE_HUNDRED) > 0) {
            throw new IllegalArgumentException("Your % buckets add up to "
                    + percentTotal.stripTrailingZeros().toPlainString()
                    + "%. They can't be more than 100%; lower one first.");
        }
    }

    /** What the percent buckets add up to, e.g. 100 for 20% + 20% + 20% + 40%. */
    public static BigDecimal totalPercent(List<BudgetBucket> buckets) {
        return buckets.stream()
                .filter(bucket -> bucket.unit() == BudgetBucket.Unit.PERCENT)
                .map(BudgetBucket::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A budget with nothing ticked off yet. */
    public Budget(Integer cushionAccountId, BigDecimal cushion, List<BudgetBucket> buckets,
                  Boolean includeInForecast) {
        this(cushionAccountId, cushion, buckets, includeInForecast, null);
    }

    /** A budget that's included in the forecast. */
    public Budget(Integer cushionAccountId, BigDecimal cushion, List<BudgetBucket> buckets) {
        this(cushionAccountId, cushion, buckets, Boolean.TRUE);
    }

    /** Same budget, with the forecast switch turned on or off. */
    public Budget withIncludeInForecast(boolean include) {
        return new Budget(cushionAccountId, cushion, buckets, include, bucketsDoneThrough);
    }

    /** The last payday whose share of {@code bucketName} is ticked off, or null if none is. */
    public LocalDate doneThroughFor(String bucketName) {
        return bucketsDoneThrough.get(bucketName);
    }

    /** Same budget, with {@code bucketName} ticked off up to {@code date} (null = nothing ticked). */
    public Budget withBucketDoneThrough(String bucketName, LocalDate date) {
        Map<String, LocalDate> changed = new HashMap<>(bucketsDoneThrough);
        if (date == null) {
            changed.remove(bucketName);
        } else {
            changed.put(bucketName, date);
        }
        return new Budget(cushionAccountId, cushion, buckets, includeInForecast, changed);
    }

    /** Same budget, with these ticks (e.g. keeping the old ticks when the Budget tab saves). */
    public Budget withBucketsDoneThrough(Map<String, LocalDate> ticks) {
        return new Budget(cushionAccountId, cushion, buckets, includeInForecast, ticks);
    }

    /** What a new budget starts with: keep $1,000, and five empty buckets to fill in. */
    public static Budget starter() {
        return new Budget(null, new BigDecimal("1000.00"), List.of(
                new BudgetBucket("My spending", BigDecimal.ZERO, BudgetBucket.Unit.DOLLARS),
                new BudgetBucket("Partner's spending", BigDecimal.ZERO, BudgetBucket.Unit.DOLLARS),
                new BudgetBucket("Savings", BigDecimal.ZERO, BudgetBucket.Unit.PERCENT),
                new BudgetBucket("Investments", BigDecimal.ZERO, BudgetBucket.Unit.PERCENT),
                new BudgetBucket("Paying down debt", BigDecimal.ZERO, BudgetBucket.Unit.PERCENT,
                        BudgetBucket.Destination.DEBT)));
    }
}
