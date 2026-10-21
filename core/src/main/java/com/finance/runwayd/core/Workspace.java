package com.finance.runwayd.core;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.Budget;
import com.finance.runwayd.core.model.FinanceData;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.core.storage.ChangedElsewhereException;
import com.finance.runwayd.core.storage.DataStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * The open budget's data, in memory: accounts, bills, the paycheck split and the rollover switch.
 * Every feature (accounts, bills, debt, budget, forecast) reads and changes the same Workspace,
 * then calls {@link #save()}.
 *
 * <p>Saving also bumps {@link #version()}. Features that cache something worked out from the data
 * (today's forecast, budget plans) remember the version they used and recalculate once it changes.
 */
public final class Workspace {

    private final DataStore store;

    // Working copies of the saved data. Every change edits these, then saves.
    private final List<Account> accounts;
    private final List<ScheduledItem> items;
    private Budget budget;
    private boolean rollPaidOffPayments;

    private long version;

    public Workspace(DataStore store) {
        this.store = store;
        this.accounts = new ArrayList<>();
        this.items = new ArrayList<>();
        takeFrom(store.load());
    }

    /** Replaces what's in memory with {@code data}, e.g. after loading the file again. */
    private void takeFrom(FinanceData data) {
        accounts.clear();
        accounts.addAll(data.accounts());
        items.clear();
        items.addAll(data.items());
        budget = data.budget();
        rollPaidOffPayments = data.rollPaidOffPayments();
    }

    /** The accounts, in the order they were added. Changes to this list are saved by {@link #save()}. */
    public List<Account> accounts() {
        return accounts;
    }

    /** The bills, paychecks and transfers, in the order they were added. Saved by {@link #save()}. */
    public List<ScheduledItem> items() {
        return items;
    }

    public Budget budget() {
        return budget;
    }

    /** Replaces the budget settings. Call {@link #save()} afterwards. */
    public void setBudget(Budget budget) {
        this.budget = budget;
    }

    public boolean rollPaidOffPayments() {
        return rollPaidOffPayments;
    }

    /** Turns the snowball rollover on or off. Call {@link #save()} afterwards. */
    public void setRollPaidOffPayments(boolean roll) {
        this.rollPaidOffPayments = roll;
    }

    /**
     * Writes everything to the data file, and tells caches the data changed.
     *
     * @throws ChangedElsewhereException if a shared budget was saved on another computer since it was
     *         loaded. Nothing is written; their version is loaded in its place, so the screen can show it
     */
    public void save() {
        version++;
        try {
            store.save(new FinanceData(accounts, items, budget, rollPaidOffPayments));
        } catch (ChangedElsewhereException e) {
            takeFrom(store.load()); // show their version instead of the change that couldn't be saved
            throw e;
        }
    }

    /**
     * For a shared budget: if the file was saved on another computer since it was last read, loads
     * that version. Called every few seconds while the app is open.
     *
     * @return true if something new was loaded (so the screen should refresh)
     */
    public boolean reloadIfChangedElsewhere() {
        if (!store.changedSinceLastRead()) {
            return false;
        }
        takeFrom(store.load());
        version++; // caches made from the old data are out of date
        return true;
    }

    /** Goes up by one on every save. A cache made at an older version is out of date. */
    public long version() {
        return version;
    }

    // -------------------------------------------------------------------------
    // Lookups every feature needs
    // -------------------------------------------------------------------------

    public Optional<Account> findAccount(int id) {
        return accounts.stream().filter(a -> a.id() == id).findFirst();
    }

    /** Where account {@code id} is in {@link #accounts()}. */
    public int indexOfAccount(int id) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).id() == id) {
                return i;
            }
        }
        throw new IllegalArgumentException("No account with id " + id);
    }

    /** Where item {@code id} is in {@link #items()}. */
    public int indexOfItem(int id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id() == id) {
                return i;
            }
        }
        throw new IllegalArgumentException("No item with id " + id);
    }

    /** One more than the biggest id in use. Starts at 1. */
    public static <T> int nextId(List<T> list, ToIntFunction<T> idOf) {
        return list.stream().mapToInt(idOf).max().orElse(0) + 1;
    }
}
