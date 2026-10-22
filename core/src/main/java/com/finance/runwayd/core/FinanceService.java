package com.finance.runwayd.core;

import com.finance.runwayd.core.accounts.Accounts;
import com.finance.runwayd.core.bills.Bills;
import com.finance.runwayd.core.bills.Checklist;
import com.finance.runwayd.core.bills.ChecklistItem;
import com.finance.runwayd.core.budget.BudgetPlan;
import com.finance.runwayd.core.budget.BudgetPlans;
import com.finance.runwayd.core.debt.DebtSummary;
import com.finance.runwayd.core.debt.Debts;
import com.finance.runwayd.core.forecast.Forecast;
import com.finance.runwayd.core.forecast.Forecaster;
import com.finance.runwayd.core.forecast.Forecasts;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.Budget;
import com.finance.runwayd.core.model.PaymentRule;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.core.storage.DataStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The front door to the backend. The desktop UI calls this class and nothing else in core.
 *
 * <p>It holds no logic of its own. Each method hands off to the feature that owns it, so to see
 * how something works, follow it into that class:
 * <pre>
 *   accounts/  {@link Accounts}     your accounts and balances
 *   bills/     {@link Bills}        bills, paychecks and transfers; marking them paid
 *              {@link Checklist}    the Forecast tab's "This month" list
 *   debt/      {@link Debts}        how debts are paid; the Debt tab's summary
 *   budget/    {@link BudgetPlans}  the Budget tab: settings, the plan, ticking buckets off
 *   forecast/  {@link Forecasts}    the day-by-day forecast
 * </pre>
 * They all share one {@link Workspace}: the open budget's data in memory, saved after every change.
 *
 * <p>Problems are reported by throwing {@link IllegalArgumentException} or
 * {@link IllegalStateException} with a message that's safe to show the user.
 */
public final class FinanceService {

    /** See {@link Bills#PAYING_AHEAD_WARNING_DAYS}. */
    public static final int PAYING_AHEAD_WARNING_DAYS = Bills.PAYING_AHEAD_WARNING_DAYS;

    private final Workspace workspace;
    private final Accounts accounts;
    private final Bills bills;
    private final Checklist checklist;
    private final Debts debts;
    private final BudgetPlans budgetPlans;
    private final Forecasts forecasts;

    public FinanceService(DataStore store) {
        this.workspace = new Workspace(store);
        Forecaster forecaster = new Forecaster();
        this.accounts = new Accounts(workspace);
        this.budgetPlans = new BudgetPlans(workspace, accounts, forecaster);
        this.forecasts = new Forecasts(workspace, forecaster, budgetPlans);
        this.bills = new Bills(workspace, forecasts);
        this.checklist = new Checklist(workspace, budgetPlans, forecasts);
        this.debts = new Debts(workspace, accounts, bills, forecasts);
    }

    // =========================================================================
    // Accounts (see Accounts)
    // =========================================================================

    /** All accounts, cash first and then debts, each group sorted by name. */
    public List<Account> accounts() {
        return accounts.all();
    }

    /** Just checking and savings: money you have. */
    public List<Account> cashAccounts() {
        return accounts.cash();
    }

    /** Just credit cards and loans: money you owe. */
    public List<Account> debtAccounts() {
        return accounts.debts();
    }

    public Optional<Account> findAccount(int id) {
        return accounts.find(id);
    }

    public Account addAccount(Account draft) {
        return accounts.add(draft);
    }

    public void updateAccount(Account changed) {
        accounts.update(changed);
    }

    public void updateBalance(int accountId, BigDecimal balance, LocalDate asOf) {
        accounts.updateBalance(accountId, balance, asOf);
    }

    public void deleteAccount(int id) {
        accounts.delete(id);
    }

    // =========================================================================
    // Bills, paychecks and transfers (see Bills)
    // =========================================================================

    /** All items, income first and then the biggest bills. */
    public List<ScheduledItem> items() {
        return bills.all();
    }

    public ScheduledItem addItem(ScheduledItem draft) {
        return bills.add(draft);
    }

    public void updateItem(ScheduledItem changed) {
        bills.update(changed);
    }

    public void deleteItem(int id) {
        bills.delete(id);
    }

    public BigDecimal monthlyTotal(Collection<ScheduledItem> group) {
        return bills.monthlyTotal(group);
    }

    public BigDecimal yearlyTotal(Collection<ScheduledItem> group) {
        return bills.yearlyTotal(group);
    }

    public BigDecimal amountEachTime(ScheduledItem item) {
        return bills.amountEachTime(item);
    }

    public BigDecimal signedAmount(ScheduledItem item) {
        return bills.signedAmount(item);
    }

    public Optional<LocalDate> nextDue(ScheduledItem item) {
        return bills.nextDue(item);
    }

    public void setPaid(int itemId, LocalDate date, boolean paid) {
        bills.setPaid(itemId, date, paid);
    }

    public Optional<LocalDate> markPaid(int itemId) {
        return bills.markPaid(itemId);
    }

    public boolean wouldPayFarAhead(ScheduledItem item) {
        return bills.wouldPayFarAhead(item);
    }

    public Optional<LocalDate> paidAheadTo(ScheduledItem item) {
        return bills.paidAheadTo(item);
    }

    public boolean canUndoPaid(ScheduledItem item) {
        return bills.canUndoPaid(item);
    }

    /** The day a one-time bill was paid, if it is (see Bills). */
    public Optional<LocalDate> paidOn(ScheduledItem item) {
        return bills.paidOn(item);
    }

    /** True if the Bills tab should leave this finished bill out now (see Bills). */
    public boolean droppedOff(ScheduledItem item) {
        return bills.droppedOff(item, LocalDate.now());
    }

    public Optional<LocalDate> undoLastPaid(int itemId) {
        return bills.undoLastPaid(itemId);
    }

    /** The Forecast tab's "This month" list (see Checklist). */
    public List<ChecklistItem> monthChecklist(YearMonth month) {
        return checklist.forMonth(month);
    }

    /** The checklist from {@code first} through {@code last}, e.g. this month and the next 11 (see Checklist). */
    public List<ChecklistItem> checklist(YearMonth first, YearMonth last) {
        return checklist.forMonths(first, last);
    }

    // =========================================================================
    // Debt (see Debts)
    // =========================================================================

    public ScheduledItem linkBillToDebt(int itemId, int debtAccountId) {
        return debts.linkBillToDebt(itemId, debtAccountId);
    }

    public ScheduledItem createPayment(int debtAccountId, PaymentRule rule, BigDecimal fixedAmount,
                                       LocalDate firstDate, int fromAccountId) {
        return debts.createPayment(debtAccountId, rule, fixedAmount, firstDate, fromAccountId);
    }

    public BigDecimal loanPayment(BigDecimal owed, BigDecimal yearlyRatePercent, int paymentsLeft) {
        return debts.loanPayment(owed, yearlyRatePercent, paymentsLeft);
    }

    public void setPaymentRule(int itemId, PaymentRule rule) {
        debts.setPaymentRule(itemId, rule);
    }

    public DebtSummary debtSummary(LocalDate date) {
        return debts.summary(date);
    }

    /** The day runwayd started counting bills: see {@link Workspace#trackingSince()}. */
    public LocalDate trackingSince() {
        return workspace.trackingSince();
    }

    // =========================================================================
    // Budget (see BudgetPlans)
    // =========================================================================

    public Budget budget() {
        return budgetPlans.budget();
    }

    public void saveBudget(Budget changed) {
        budgetPlans.save(changed);
    }

    public Optional<Account> budgetAccount() {
        return budgetPlans.budgetAccount();
    }

    public BudgetPlan budgetPlan(int howMany) {
        return budgetPlans.plan(howMany);
    }

    public BudgetPlan budgetPlanUntil(LocalDate until) {
        return budgetPlans.planUntil(until);
    }

    public boolean budgetInForecast() {
        return budgetPlans.inForecast();
    }

    public void setBudgetInForecast(boolean include) {
        budgetPlans.setInForecast(include);
    }

    public void setBucketDone(String bucketName, LocalDate payday, boolean done) {
        budgetPlans.setBucketDone(bucketName, payday, done);
    }

    // =========================================================================
    // Shared budgets
    // =========================================================================

    /**
     * For a budget in a shared folder: loads the latest version if it was saved on another computer.
     * The window calls this every few seconds.
     *
     * @return true if something new was loaded, so the screen should refresh
     */
    public boolean reloadIfChangedElsewhere() {
        return workspace.reloadIfChangedElsewhere();
    }

    // =========================================================================
    // Forecast (see Forecasts)
    // =========================================================================

    public Forecast forecast(LocalDate date) {
        return forecasts.on(date);
    }

    public boolean rollsPaidOffPayments() {
        return forecasts.rollsPaidOffPayments();
    }

    public void setRollPaidOffPayments(boolean roll) {
        forecasts.setRollPaidOffPayments(roll);
    }
}
