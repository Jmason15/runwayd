package com.finance.runwayd.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything one budget file holds: its accounts, its scheduled items and its paycheck split.
 * This is exactly what's written to the JSON file.
 *
 * @param budget the Budget tab settings. Missing in older data files; then a starter budget is used.
 * @param rollPaidOffPayments the Debt tab's "Roll paid-off payments into the next debt" switch.
 *                            Missing in older data files; then on
 * @param budgets          only read, from files saved by a short-lived version that kept several
 *                         paycheck splits in one file. The one named {@code activeBudget} (or the
 *                         first) becomes {@code budget}. Always null once loaded, so never written back
 * @param activeBudget     only read, with {@code budgets}
 * @param budgetInForecast only read, with {@code budgets}: the "Include budget splits" switch
 */
public record FinanceData(List<Account> accounts, List<ScheduledItem> items, Budget budget,
                          Boolean rollPaidOffPayments,
                          @JsonInclude(JsonInclude.Include.NON_NULL) List<SplitFromOlderVersion> budgets,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String activeBudget,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Boolean budgetInForecast) {

    /** One named paycheck split, as that short-lived version saved it. */
    public record SplitFromOlderVersion(String name, Integer cushionAccountId, BigDecimal cushion,
                                        List<BudgetBucket> buckets) {
    }

    public FinanceData {
        // Store read-only copies so nobody can change the lists behind our back.
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        items = items == null ? List.of() : List.copyOf(items);
        if (budget == null && budgets != null && !budgets.isEmpty()) {
            budget = fromOlderVersion(budgets, activeBudget, budgetInForecast);
        }
        if (budget == null) {
            budget = Budget.starter();
        }
        if (rollPaidOffPayments == null) {
            rollPaidOffPayments = Boolean.TRUE;
        }
        budgets = null;
        activeBudget = null;
        budgetInForecast = null;
    }

    /** What the app saves. */
    public FinanceData(List<Account> accounts, List<ScheduledItem> items, Budget budget,
                       Boolean rollPaidOffPayments) {
        this(accounts, items, budget, rollPaidOffPayments, null, null, null);
    }

    /** A brand-new budget with nothing entered yet. */
    public static FinanceData empty() {
        return new FinanceData(List.of(), List.of(), null, null);
    }

    /** The split that was in use (or the first), as the single Budget this version keeps. */
    private static Budget fromOlderVersion(List<SplitFromOlderVersion> splits, String active, Boolean inForecast) {
        SplitFromOlderVersion chosen = splits.stream()
                .filter(split -> split.name() != null && split.name().equalsIgnoreCase(active))
                .findFirst()
                .orElse(splits.get(0));
        return new Budget(chosen.cushionAccountId(), chosen.cushion(), chosen.buckets(), inForecast);
    }
}
