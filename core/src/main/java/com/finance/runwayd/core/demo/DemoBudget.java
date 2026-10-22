package com.finance.runwayd.core.demo;

import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.Budget;
import com.finance.runwayd.core.model.BudgetBucket;
import com.finance.runwayd.core.model.BudgetBucket.Destination;
import com.finance.runwayd.core.model.BudgetBucket.Unit;
import com.finance.runwayd.core.model.Category;
import com.finance.runwayd.core.model.FinanceData;
import com.finance.runwayd.core.model.Frequency;
import com.finance.runwayd.core.model.PaymentRule;
import com.finance.runwayd.core.model.ScheduledItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A made-up household to look around in: Budget → Open the demo budget. Nothing in it is real.
 *
 * <p>It has a little of everything, so every tab has something to show:
 * <ul>
 *   <li>a paycheck every two weeks, into Everyday Checking</li>
 *   <li>rent, utilities, groceries, gas, insurance and a few subscriptions</li>
 *   <li>two credit cards paying the card minimum, and a car loan with a fixed payment</li>
 *   <li>a one-time vacation a few months out</li>
 *   <li>a paycheck split: fun money, an emergency fund in savings, and paying down debt</li>
 * </ul>
 *
 * <p>Every date is counted from {@code today}, so whenever the demo is made, its forecast,
 * checklist and "overdue" flags look current.
 */
public final class DemoBudget {

    /** The demo's name in the budget list ("Demo (2)" if that's taken). */
    public static final String NAME = "Demo";

    private static final int CHECKING = 1;
    private static final int SAVINGS = 2;
    private static final int TRAVEL_CARD = 3;
    private static final int STORE_CARD = 4;
    private static final int CAR_LOAN = 5;

    private DemoBudget() {
    }

    /** The whole demo household, as of {@code today}. */
    public static FinanceData asOf(LocalDate today) {
        List<Account> accounts = List.of(
                new Account(CHECKING, "Everyday Checking", AccountType.CHECKING, money("3200"), today, null),
                new Account(SAVINGS, "Rainy Day Savings", AccountType.SAVINGS, money("1500"), today, null),
                new Account(TRAVEL_CARD, "Travel Rewards Card", AccountType.CREDIT_CARD, money("2400"), today,
                        new BigDecimal("22.99")),
                new Account(STORE_CARD, "Store Card", AccountType.CREDIT_CARD, money("650"), today,
                        new BigDecimal("26.99")),
                new Account(CAR_LOAN, "Car Loan", AccountType.LOAN, money("14500"), today,
                        new BigDecimal("6.49")));

        LocalDate firstOfNextMonth = today.withDayOfMonth(1).plusMonths(1);
        List<ScheduledItem> items = new ArrayList<>();
        // Money in
        add(items, "Paycheck", Category.INCOME, "2450", Frequency.EVERY_2_WEEKS, today.plusDays(4), null, CHECKING);
        // Everyday bills out of checking
        add(items, "Rent", Category.HOUSING, "1450", Frequency.MONTHLY, firstOfNextMonth, CHECKING, null);
        add(items, "Electric", Category.UTILITIES, "120", Frequency.MONTHLY, today.plusDays(12), CHECKING, null);
        add(items, "Internet", Category.UTILITIES, "70", Frequency.MONTHLY, today.plusDays(18), CHECKING, null);
        add(items, "Phone", Category.UTILITIES, "85", Frequency.MONTHLY, today.plusDays(21), CHECKING, null);
        add(items, "Groceries", Category.FOOD, "150", Frequency.WEEKLY, today.plusDays(2), CHECKING, null);
        add(items, "Gas", Category.TRANSPORTATION, "45", Frequency.WEEKLY, today.plusDays(5), CHECKING, null);
        add(items, "Car insurance", Category.INSURANCE, "140", Frequency.MONTHLY, today.plusDays(9), CHECKING, null);
        add(items, "Streaming", Category.SUBSCRIPTIONS, "16", Frequency.MONTHLY, today.plusDays(7), CHECKING, null);
        add(items, "Music", Category.SUBSCRIPTIONS, "11", Frequency.MONTHLY, today.plusDays(14), CHECKING, null);
        add(items, "Cloud storage", Category.SUBSCRIPTIONS, "99", Frequency.YEARLY, today.plusDays(60), CHECKING, null);
        add(items, "Gym", Category.PERSONAL, "30", Frequency.MONTHLY, today.plusDays(3), CHECKING, null);
        add(items, "Vacation", Category.OTHER, "1200", Frequency.ONCE, today.plusMonths(4), CHECKING, null);
        // Debt payments: a fixed loan payment, and two cards paying their minimum
        add(items, "Car loan payment", Category.DEBT_PAYMENTS, "380", Frequency.MONTHLY, today.plusDays(15),
                CHECKING, CAR_LOAN);
        items.add(cardMinimum(items.size() + 1, "Travel card payment", today.plusDays(20), TRAVEL_CARD));
        items.add(cardMinimum(items.size() + 1, "Store card payment", today.plusDays(10), STORE_CARD));

        Budget budget = new Budget(CHECKING, money("1000"), List.of(
                new BudgetBucket("Fun money", money("100"), Unit.DOLLARS),
                new BudgetBucket("Emergency fund", new BigDecimal("30"), Unit.PERCENT, Destination.ACCOUNT, SAVINGS),
                new BudgetBucket("Paying down debt", new BigDecimal("50"), Unit.PERCENT, Destination.DEBT, null),
                new BudgetBucket("Treats", new BigDecimal("20"), Unit.PERCENT)));

        return new FinanceData(accounts, items, budget, true, null);
    }

    /** Adds a fixed-amount item with the next id. */
    private static void add(List<ScheduledItem> items, String name, Category category, String amount,
                            Frequency frequency, LocalDate first, Integer from, Integer to) {
        items.add(new ScheduledItem(items.size() + 1, name, category, money(amount), PaymentRule.FIXED, frequency,
                first, null, from, to, null));
    }

    /** A monthly card-minimum payment from checking into {@code card}. */
    private static ScheduledItem cardMinimum(int id, String name, LocalDate first, int card) {
        return new ScheduledItem(id, name, Category.DEBT_PAYMENTS, null, PaymentRule.CARD_MINIMUM,
                Frequency.MONTHLY, first, null, CHECKING, card, null);
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2);
    }
}
