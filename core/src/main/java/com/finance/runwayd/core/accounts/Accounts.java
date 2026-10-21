package com.finance.runwayd.core.accounts;

import com.finance.runwayd.core.Workspace;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.ScheduledItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Your accounts: checking and savings (money you have) and credit cards and loans (money you owe).
 *
 * <p>Rules:
 * <ul>
 *   <li>New accounts get the next free id.</li>
 *   <li>An account can't be deleted while a bill, paycheck or budget bucket still uses it.</li>
 * </ul>
 */
public final class Accounts {

    private final Workspace workspace;

    public Accounts(Workspace workspace) {
        this.workspace = workspace;
    }

    /** All accounts, cash first and then debts, each group sorted by name. */
    public List<Account> all() {
        return workspace.accounts().stream()
                .sorted(Comparator.comparing((Account a) -> a.type().isDebt())
                        .thenComparing(Account::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Just checking and savings: money you have. */
    public List<Account> cash() {
        return all().stream().filter(account -> !account.type().isDebt()).toList();
    }

    /** Just credit cards and loans: money you owe. */
    public List<Account> debts() {
        return all().stream().filter(account -> account.type().isDebt()).toList();
    }

    public Optional<Account> find(int id) {
        return workspace.findAccount(id);
    }

    /** The first checking account by name (or the first savings account if there's no checking). */
    public Optional<Account> firstChecking() {
        return cash().stream()
                .min(Comparator.comparing((Account a) -> a.type() != AccountType.CHECKING));
    }

    /** Saves a new account. The id on {@code draft} is ignored; the saved copy with its real id is returned. */
    public Account add(Account draft) {
        Account saved = draft.withId(Workspace.nextId(workspace.accounts(), Account::id));
        workspace.accounts().add(saved);
        workspace.save();
        return saved;
    }

    /** Replaces the account that has the same id. */
    public void update(Account changed) {
        workspace.accounts().set(workspace.indexOfAccount(changed.id()), changed);
        workspace.save();
    }

    /** Records a fresh balance, e.g. what your bank app shows today. */
    public void updateBalance(int accountId, BigDecimal balance, LocalDate asOf) {
        Account account = workspace.accounts().get(workspace.indexOfAccount(accountId));
        update(account.withBalance(balance, asOf));
    }

    /** Deletes an account, as long as nothing still uses it. */
    public void delete(int id) {
        List<String> users = new ArrayList<>(workspace.items().stream()
                .filter(item -> item.touches(id))
                .map(ScheduledItem::name)
                .toList());
        // A budget bucket that pays this debt, or fills this account, counts too.
        workspace.budget().buckets().stream()
                .filter(bucket -> Objects.equals(bucket.accountId(), id))
                .forEach(bucket -> users.add("the Budget bucket \"" + bucket.name() + "\""));
        if (!users.isEmpty()) {
            throw new IllegalStateException("This account is still used by: " + String.join(", ", users)
                    + ". Change or delete those first.");
        }
        workspace.accounts().remove(workspace.indexOfAccount(id));
        workspace.save();
    }
}
