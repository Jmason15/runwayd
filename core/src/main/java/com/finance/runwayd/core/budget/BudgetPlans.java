package com.finance.runwayd.core.budget;

import com.finance.runwayd.core.Workspace;
import com.finance.runwayd.core.accounts.Accounts;
import com.finance.runwayd.core.forecast.BudgetMove;
import com.finance.runwayd.core.forecast.Forecast;
import com.finance.runwayd.core.forecast.Forecaster;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.Budget;
import com.finance.runwayd.core.model.BudgetBucket;
import com.finance.runwayd.core.model.ScheduledItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Budget tab: its settings, the paycheck-by-paycheck plan, and the moves that play the plan
 * into the forecast.
 *
 * <p>How a plan is made:
 * <ol>
 *   <li>{@link BudgetPlanner#plan} works out a steady, safe amount per paycheck from the forecast
 *       <i>without</i> the budget.</li>
 *   <li>The budget changes later bills (paying cards down shrinks their minimums), so the plan is
 *       played into the forecast and topped up with whatever room is left
 *       ({@link BudgetPlanner#topUp}), a few rounds, keeping only rounds that stay above the cushion.</li>
 * </ol>
 * Plans are cached until the data changes, since every forecast with the budget in it needs one.
 */
public final class BudgetPlans {

    /** Paychecks are evened out together over at least this many months, so the amounts stay put. */
    private static final int EVEN_OUT_MONTHS = 12;
    /** How far past the last evened-out paycheck to look, so the bills after it are counted too. */
    private static final int EXTRA_LOOKAHEAD_MONTHS = 3;
    /** How many times to top the plan up; each round hands out what the last one left. */
    private static final int TOP_UP_ROUNDS = 6;

    private final Workspace workspace;
    private final Accounts accounts;
    private final Forecaster forecaster;

    // Plans, worked out once per start and "evened out until" date, and kept until the data changes.
    private final Map<String, BudgetPlan> cache = new HashMap<>();
    private long cacheVersion = -1;

    public BudgetPlans(Workspace workspace, Accounts accounts, Forecaster forecaster) {
        this.workspace = workspace;
        this.accounts = accounts;
        this.forecaster = forecaster;
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    /** The Budget tab settings: the cushion and the buckets. */
    public Budget budget() {
        return workspace.budget();
    }

    /** Saves the Budget tab settings, after checking every bucket points at a sensible account. */
    public void save(Budget changed) {
        changed.checkPercentTotal();
        if (changed.cushionAccountId() != null) {
            Account account = accounts.find(changed.cushionAccountId())
                    .orElseThrow(() -> new IllegalArgumentException("That account doesn't exist."));
            if (account.type().isDebt()) {
                throw new IllegalArgumentException("Pick a checking or savings account to keep money in.");
            }
        }
        for (BudgetBucket bucket : changed.buckets()) {
            checkBucketAccount(bucket, changed);
        }
        // Which buckets are ticked off is kept from before; the Budget tab doesn't change it.
        workspace.setBudget(changed.withBucketsDoneThrough(workspace.budget().bucketsDoneThrough()));
        workspace.save();
    }

    /**
     * A debt bucket can only pay a credit card or loan. An account bucket needs a checking or savings
     * account other than the one the budget comes out of (moving money into the same account would
     * do nothing).
     */
    private void checkBucketAccount(BudgetBucket bucket, Budget changed) {
        if (bucket.accountId() == null) {
            return;
        }
        Account account = accounts.find(bucket.accountId())
                .orElseThrow(() -> new IllegalArgumentException("\"" + bucket.name() + "\" uses an account that doesn't exist."));
        if (bucket.destination() == BudgetBucket.Destination.DEBT && !account.type().isDebt()) {
            throw new IllegalArgumentException("\"" + bucket.name() + "\" can only pay a credit card or loan.");
        }
        if (bucket.destination() == BudgetBucket.Destination.ACCOUNT) {
            if (account.type().isDebt()) {
                throw new IllegalArgumentException("\"" + bucket.name() + "\" can only go into a checking or "
                        + "savings account. To pay down " + account.name() + ", pick \"Pays down\" instead.");
            }
            Integer keptIn = changed.cushionAccountId() != null ? changed.cushionAccountId()
                    : accounts.firstChecking().map(Account::id).orElse(null);
            if (Objects.equals(keptIn, account.id())) {
                throw new IllegalArgumentException("\"" + bucket.name() + "\" can't go into " + account.name()
                        + ", since that's where the budget comes out of. Pick another account.");
            }
        }
    }

    /** The account the budget keeps above the cushion: the one picked, or else the first checking account. */
    public Optional<Account> budgetAccount() {
        Integer picked = workspace.budget().cushionAccountId();
        if (picked != null) {
            Optional<Account> account = accounts.find(picked);
            if (account.isPresent()) {
                return account;
            }
        }
        return accounts.firstChecking();
    }

    /** True if the budget's splits are part of the forecast (the checkbox on the Forecast tab). */
    public boolean inForecast() {
        return workspace.budget().includeInForecast();
    }

    /** Turns the budget's forecast switch on or off (the checkbox on the Forecast tab). */
    public void setInForecast(boolean include) {
        workspace.setBudget(workspace.budget().withIncludeInForecast(include));
        workspace.save();
    }

    /**
     * Ticks one bucket's share of a payday off (you've moved that money) or unticks it. Like bills,
     * each bucket remembers one date: ticking a payday ticks that bucket's earlier ones too, and
     * unticking a payday unticks it and its later ones. Balances aren't changed; update them from
     * your bank as usual.
     */
    public void setBucketDone(String bucketName, LocalDate payday, boolean done) {
        Budget budget = workspace.budget();
        LocalDate doneThrough = budget.doneThroughFor(bucketName);
        if (done && (doneThrough == null || payday.isAfter(doneThrough))) {
            workspace.setBudget(budget.withBucketDoneThrough(bucketName, payday));
        } else if (!done && doneThrough != null && !payday.isAfter(doneThrough)) {
            workspace.setBudget(budget.withBucketDoneThrough(bucketName, payday.minusDays(1)));
        } else {
            return; // already the way you want it
        }
        workspace.save();
    }

    // -------------------------------------------------------------------------
    // The plan
    // -------------------------------------------------------------------------

    /**
     * The next {@code howMany} paychecks into the budget account, each with how much is
     * safe to split and how the buckets divide it. See {@link BudgetPlanner}.
     */
    public BudgetPlan plan(int howMany) {
        return planThrough(LocalDate.now(), howMany);
    }

    /**
     * Every paycheck up to and including {@code until} (the Budget tab's "Show the next" choice).
     * The amounts are the same however far you look, since they're always evened out over at
     * least a year.
     */
    public BudgetPlan planUntil(LocalDate until) {
        BudgetPlan everything = planThrough(LocalDate.now(), Integer.MAX_VALUE);
        List<BudgetPlan.PaycheckSplit> upToThen = everything.paychecks().stream()
                .filter(paycheck -> !paycheck.date().isAfter(until))
                .toList();
        return new BudgetPlan(everything.account(), everything.cushion(), upToThen, everything.lowBalance());
    }

    /**
     * The plan for every paycheck up to {@code through} (or a year, whichever is later), showing
     * the first {@code howMany}. It starts the day tracking began rather than today: a payday since
     * then whose buckets aren't ticked off still counts, like an unpaid bill, until you tick them.
     * Updating the balance doesn't tick them.
     */
    public BudgetPlan planThrough(LocalDate through, int howMany) {
        Budget budget = workspace.budget();
        Optional<Account> account = budgetAccount();
        if (account.isEmpty()) {
            return new BudgetPlan(null, budget.cushion(), List.of(), null);
        }
        Set<Integer> paycheckIds = workspace.items().stream()
                .filter(item -> item.kind() == ScheduledItem.Kind.INCOME)
                .filter(item -> Objects.equals(item.toAccountId(), account.get().id()))
                .map(ScheduledItem::id)
                .collect(Collectors.toSet());
        LocalDate today = LocalDate.now();
        LocalDate since = workspace.trackingSince() != null ? workspace.trackingSince() : account.get().balanceDate();
        LocalDate start = since.isBefore(today) ? since : today;
        LocalDate evenOutUntil = through.isAfter(today.plusMonths(EVEN_OUT_MONTHS))
                ? through
                : today.plusMonths(EVEN_OUT_MONTHS);
        LocalDate lookaheadEnd = evenOutUntil.plusMonths(EXTRA_LOOKAHEAD_MONTHS);

        if (cacheVersion != workspace.version()) {
            cache.clear(); // the data changed since these were worked out
            cacheVersion = workspace.version();
        }
        BudgetPlan full = cache.computeIfAbsent(start + "|" + evenOutUntil, key -> {
            Forecast withoutBudget = forecaster.run(workspace.accounts(), workspace.items(), List.of(), lookaheadEnd,
                    workspace.rollPaidOffPayments(), workspace.trackingSince());
            BudgetPlan first = BudgetPlanner.plan(withoutBudget, account.get(), budget.cushion(), paycheckIds,
                    budget.buckets(), start, evenOutUntil, Integer.MAX_VALUE);
            return topUpWhileSafe(first, paycheckIds, lookaheadEnd, start);
        });
        List<BudgetPlan.PaycheckSplit> shown = full.paychecks().stream().limit(howMany).toList();
        return new BudgetPlan(full.account(), full.cushion(), shown, full.lowBalance());
    }

    /**
     * Plays the plan into the forecast, hands out the room still left above the cushion
     * (see {@link BudgetPlanner#topUp}), and repeats. A round is kept only if its own forecast never
     * goes below the amount to keep; otherwise the last safe plan stands. It stops early once a
     * round adds less than a dollar.
     */
    private BudgetPlan topUpWhileSafe(BudgetPlan first, Set<Integer> paycheckIds, LocalDate lookaheadEnd,
                                      LocalDate start) {
        int accountId = first.account().id();
        BudgetPlan current = first;
        Forecast withCurrent = runWith(current, lookaheadEnd);
        BudgetPlan safe = isSafe(withCurrent, accountId, start, first) ? current : null;
        for (int round = 0; round < TOP_UP_ROUNDS; round++) {
            BudgetPlan next = BudgetPlanner.topUp(current, withCurrent, paycheckIds, workspace.budget().buckets(), start);
            Forecast withNext = runWith(next, lookaheadEnd);
            BigDecimal added = next.totalAvailable().subtract(current.totalAvailable());
            current = next;
            withCurrent = withNext;
            if (isSafe(withNext, accountId, start, first)) {
                safe = next;
            }
            if (added.abs().compareTo(BigDecimal.ONE) < 0) {
                break; // nothing meaningful left to hand out
            }
        }
        return safe != null ? safe : first;
    }

    private Forecast runWith(BudgetPlan plan, LocalDate through) {
        return forecaster.run(workspace.accounts(), workspace.items(), movesFor(plan), through,
                workspace.rollPaidOffPayments(), workspace.trackingSince());
    }

    /**
     * True if the account never goes below the amount to keep with this plan in the forecast.
     * (If the bills alone already dip below it, matching that dip is the best possible, so that's fine too.)
     */
    private static boolean isSafe(Forecast withPlan, int accountId, LocalDate start, BudgetPlan first) {
        BigDecimal lowest = BudgetPlanner.lowestBalance(withPlan, accountId, start);
        if (lowest == null) {
            return true;
        }
        BigDecimal floor = first.cushion();
        if (first.lowBalance() != null) {
            floor = floor.min(first.lowBalance().balance());
        }
        return lowest.compareTo(floor.subtract(new BigDecimal("0.01"))) >= 0;
    }

    // -------------------------------------------------------------------------
    // Into the forecast
    // -------------------------------------------------------------------------

    /**
     * Every bucket's share of every paycheck up to {@code through}, ready to play into the forecast.
     * Buckets you've ticked off are already moved, so they're left out. (The plan itself still
     * counts them, so ticking one doesn't change the amounts.)
     */
    public List<BudgetMove> moves(LocalDate through) {
        Budget budget = workspace.budget();
        return movesFor(planThrough(through, Integer.MAX_VALUE)).stream()
                .filter(move -> {
                    LocalDate doneThrough = budget.doneThroughFor(move.bucketName());
                    return doneThrough == null || move.date().isAfter(doneThrough);
                })
                .toList();
    }

    /** Each bucket's share of each paycheck in {@code plan}, as moves the forecast can play in. */
    private List<BudgetMove> movesFor(BudgetPlan plan) {
        if (plan.account() == null) {
            return List.of();
        }
        List<BudgetBucket> buckets = workspace.budget().buckets();
        List<BudgetMove> moves = new ArrayList<>();
        for (BudgetPlan.PaycheckSplit paycheck : plan.paychecks()) {
            for (int i = 0; i < buckets.size(); i++) {
                BudgetBucket bucket = buckets.get(i);
                BigDecimal amount = paycheck.bucketAmounts().get(i);
                if (amount.signum() > 0) {
                    moves.add(new BudgetMove(paycheck.date(), "Budget: " + bucket.name(), amount,
                            plan.account().id(), bucket.destination(), bucket.accountId(), bucket.name()));
                }
            }
        }
        return moves;
    }
}
