package com.finance.runwayd.core.forecast;

import com.finance.runwayd.core.Workspace;
import com.finance.runwayd.core.budget.BudgetPlans;

import java.time.LocalDate;
import java.util.List;

/**
 * Runs the {@link Forecaster} on the open budget's data: your accounts, bills and paychecks, plus
 * the Budget tab's paycheck splits when its switch is on.
 */
public final class Forecasts {

    private final Workspace workspace;
    private final Forecaster forecaster;
    private final BudgetPlans budgetPlans;

    // Today's forecast, kept until the data changes so tables can ask for it on every row cheaply.
    private Forecast today;
    private long todayVersion = -1;

    public Forecasts(Workspace workspace, Forecaster forecaster, BudgetPlans budgetPlans) {
        this.workspace = workspace;
        this.forecaster = forecaster;
        this.budgetPlans = budgetPlans;
    }

    /**
     * What every account looks like on {@code date}, plus everything that happens before then.
     * Includes the Budget tab's paycheck splits when its switch is on.
     */
    public Forecast on(LocalDate date) {
        List<BudgetMove> budgetMoves = budgetPlans.inForecast() ? budgetPlans.moves(date) : List.of();
        return forecaster.run(workspace.accounts(), workspace.items(), budgetMoves, date,
                workspace.rollPaidOffPayments(), workspace.trackingSince());
    }

    /** Today's balances, worked out once and reused until something changes. */
    public Forecast today() {
        if (today == null || todayVersion != workspace.version() || !today.through().equals(LocalDate.now())) {
            today = on(LocalDate.now());
            todayVersion = workspace.version();
        }
        return today;
    }

    /** True if payments to a paid-off debt roll over to the next one (the Debt tab switch). */
    public boolean rollsPaidOffPayments() {
        return workspace.rollPaidOffPayments();
    }

    /** Turns the snowball rollover on or off. See {@link Forecaster#run}. */
    public void setRollPaidOffPayments(boolean roll) {
        workspace.setRollPaidOffPayments(roll);
        workspace.save();
    }
}
