# How runwayd works

The rules behind each tab. For how to run the app and how the code is laid out, see the [README](../README.md).

## The whole idea in one line

**Balance on a date = the last balance you entered + every paycheck, bill and payment scheduled between then and that date.**

Every week or so you type in what your bank actually shows ("Update balance"), and anything that went differently from the plan is corrected. Ticking bills off between updates is optional; it just keeps the forecast from counting something you already paid.

## What you enter

| Thing | Example | Notes |
|---|---|---|
| **Account** | Everyday Checking, $2,140 as of Oct 18 | Checking, savings, credit card or loan. For debts, enter what you owe. |
| **Scheduled item** | Rent, $1,450, monthly from Nov 1 | Money moves *from* an account *to* an account. |

Every scheduled item follows one rule, money moves **from → to**:

| From | To | Means |
|---|---|---|
| — | Checking | Income |
| Checking | — | Bill |
| Checking | Savings | Transfer |
| Checking | Visa Card | Debt payment (stops automatically once it's paid off) |
| Visa Card | — | Bill charged to a card (debt goes up) |

Debts can have an optional yearly interest rate, added monthly. Leave it blank to ignore interest.

## Organizing bills

The Bills & Income tab groups everything by **category**, using a standard budgeting list (Income, Housing, Utilities, Food & Groceries, Transportation, Insurance, Healthcare, Debt Payments, Kids & Childcare, Pets, Subscriptions, Personal, Savings, Other). Each group header shows its total **per month** and **per year**, and the footer shows your net both ways. The monthly figure spreads bills evenly (a $139 yearly bill counts as $11.58 a month); the yearly figure is the real yearly cost.

- **Search** filters as you type (name, category or account). **All / Bills / Income / Transfers** narrows the list further.
- **Click an amount** to change it. Enter saves, Esc cancels.
- **Double-click** a bill to edit everything, including its category.
- **Right-click** a bill for Open website / Edit / Mark paid / Delete, or press **Delete**.
- Give a bill a **Website** (in its Edit window, e.g. myelectric.com) and it shows as a link on the Bills tab and on the Forecast checklist: one click opens it in your browser, to check whether it went through or to pay it.
- Click a group header to collapse it. It stays collapsed until you open it again.

## Paying bills

The Forecast tab has a **This month** checklist: every bill and paycheck due this month (plus anything still unticked from earlier). **Show** next to its heading switches it to the next **3 months**, **6 months** or **1 year** ("Coming up: Oct 2026 – Sep 2027"), so you can look ahead or tick something off early. **Tick** one as you pay it and the forecast stops counting it; **untick** it to put it back. With "Include budget splits" on, each payday also lists **every budget bucket on its own line**, named by where it goes and with the same amount as the Budget tab, e.g. "My spending → Spending Account $241.71", "Partner's spending $241.71", "Savings → Savings $241.71", "Paying down debt → Visa $483.42" (for a highest-rate-first debt bucket, the debt it's paying that day). Tick each one once you've made that transfer, and the forecast stops taking it out again (the amounts don't change). A bucket you haven't ticked stays on the list as Overdue until you tick it or update your balance, just like a bill. The Bills tab also has a **Mark paid** button (**Mark received** for income) that ticks off a bill's next due date.

One-time bills: once marked paid, the button becomes **"Paid Oct 21 [Undo]"** (there's no next date, so there's nothing left to mark). They stay on the list for the rest of that month, then drop off on their own; they aren't deleted. The same goes for repeating bills whose end date has passed ("Ended").

Mistakes are easy to take back:
- After each Mark paid click, a bar under the list says what was marked, with an **Undo** button.
- Every bill has an **Undo** button next to Mark paid. **Mark paid** moves a bill forward one date; **Undo** moves it back one, and that date counts as owed again (it shows as Overdue and comes out of the forecast until you mark it paid). So four clicks too many take four Undos, and Undo also brings back a date that was never marked, even one from before tracking began or before the date the bill was entered with (Rent first due Nov 10: Undo makes Oct 10 owed). It's greyed out only for an unpaid one-time bill. Right-click → **Undo last mark paid** does the same.
- Marking something that's a month or more away asks first ("Next date is Feb 15, 2027, about 4 months from now…"), so extra clicks can't quietly skip months of bills or paychecks.

**Nothing is assumed paid until you tick it.** Updating a balance doesn't tick anything: a bill you haven't ticked keeps counting, even if its date is before the balance update, since it may not have come out yet. So to catch up after a while: update the balances, then tick what you can see went through. Anything you can't confirm stays on the list as still to pay. (If you forget to tick something that did clear, the forecast just shows a bit less money than you have, the safe direction.)

Counting starts the day you began tracking: the earliest balance date when the budget was set up (saved in the budget file as `trackingSince`). Bills from before then never count and aren't listed. The list also shows each line's **Account** and the **Balance after**: what that account will hold right after the bill is paid (or the paycheck lands, or the budget transfer goes out), straight from the forecast. Lines on the same day are in the order the forecast applies them, so the balances read top to bottom. Ticked bills show no balance, since they aren't coming out again.

**A box is only ever ticked by you.** Tick a bill when it's paid and it's removed from every calculation; untick it to put it back. Ticked bills from earlier months drop off the list; unticked ones stay, month after month, until you tick them. Budget splits work the same way.

No payment history is kept. Each bill only remembers a `paidThrough` date. Balances aren't changed either, since you update those yourself from your bank.

A bill shows as **Overdue** if its date has passed and it isn't ticked. It counts in the forecast until you tick it. If it already cleared your bank, tick it.

## Debt

The **Debt** tab shows how much you owe in total, today and on any date you pick, plus your **debt-free date** and a **stacked chart**: one colored band per debt from today until you're debt-free. Bands drop off the top in the order debts are paid off, and the top edge is your total. Each credit card or loan shows its balance, rate, monthly payment and payoff date.

- **Add debt** asks for the name, the amount owed, an optional interest rate and how it's paid. By default it **sets up the payment for you**: a card pays its minimum, and for a loan you type **Payments left** (from your lender) and the monthly amount is calculated with the standard loan formula. You can also pick one of your existing bills (e.g. "Store Card") so it isn't entered twice, or choose no payment for now.
- A debt with no payment shows **Set up…** in the Payment column; click it to set one up the same way.
- **Click "Owed today"** to type in your latest statement balance.
- **How each debt is paid:** a **Fixed** amount (car or shed loan), or the **card minimum**, which is recalculated every month as interest + 1% of the balance, at least $35 (both numbers are editable per card). Right-click a debt, then **Edit payment…** to switch.
- **Paying a debt off faster:** use the **debt bucket on the Budget tab** (see below). The Debt tab names your highest-interest debt (the one to pay first) under the table, and the "From budget" column shows what the budget adds.
- **Roll paid-off payments into the next debt** (Debt tab, on by default): when a debt is paid off, its usual payment keeps going on its usual dates to the highest-rate debt still owed, so the amount paying down debt grows with every payoff (the "snowball"). A card's minimum rolls at what it was when the plan started. Turn it off to see payments simply stop.
- **Interest vs. principal:** each debt shows this month's interest (owed × rate ÷ 12) and how much of the payment actually lowers the balance (payment − interest). Principal shows red if the payment doesn't cover the interest. "Interest till paid off" totals every interest charge before the debt reaches zero. The top of the tab shows this month's totals across all debts.
- **Right-click** a debt to edit it, link a bill as its payment, or delete it.

Behind the scenes a debt is just an account of type Credit card or Loan, and its payment is an item with that debt as "Paid into". Payments stop on their own once the balance reaches zero. The Forecast tab also shows a one-line total, and the Accounts tab now lists only checking and savings.

## Budget

The **Budget** tab splits the extra cash from each paycheck into buckets (your spending, your partner's, savings, investments, paying down debt, or any you add). Each bucket is a dollar amount or a percent; dollar buckets are filled first, then percent buckets share what's left. Percent buckets can't add up to more than 100% (a running total shows under the buckets; going over shows a message and isn't saved).

"Available" on each paycheck is how much you can move out of checking on payday. It's **evened out** so every paycheck gets a steady amount, even when big bills all land after one paycheck, while the balance still **never drops below the amount you keep** (e.g. $1,000) with every upcoming bill and debt payment covered. It looks a year of paychecks ahead to do this. It also counts the budget's own effect: a debt bucket pays cards down, which shrinks their minimum payments, so that freed-up money is handed out too and checking really does get down to the amount you keep (instead of floating above it). Use **Show the next** (1 month, 3 months, 6 months or 1 year) to choose how many paychecks are listed; a line under the table totals what's available and what each bucket gets over that period. A red "Left over" means the dollar buckets ask for more than that paycheck can safely give.

**Budget in the forecast.** With "Include budget splits" ticked on the Forecast tab (the default), each paycheck's split comes out of checking on payday in the forecast, so balances, the chart and payoff dates show what will really happen. Each bucket's **Goes to** decides what its money does: **Spent** (leaves your accounts), **Into: <an account>** (moves into another of your checking or savings accounts, e.g. Savings or Investment, so its balance grows in the forecast; the budget's own "keep at least" account isn't offered), or **Pays down debt (highest rate)** (paid to your debts, highest interest rate first, rolling to the next when one is paid off; if no debt is left it stays in checking), or **Pays down: <a debt you pick>** (all of it goes to that one debt; once it's paid off, the money stays in checking until you pick another). The budget is always worked out from the forecast *without* the budget, so it never feeds back on itself. Untick the box to see the forecast without it.

The Budget tab never changes your bills or debts; it only saves its own settings.

## Your data: budgets

Everything is organized into **budgets**. A budget is a whole, separate set of data: its own accounts, balances, bills, debts and paycheck split. Use one for your household and make others for anything you want to keep apart or try out. The bar at the top of the window shows which budget is open:

- **Budget: [name ▾]** switches to another budget. The app reopens the last one you used.
- **Budget ▾ → New budget…** starts an empty one. **Duplicate…** copies the open one with everything in it, e.g. to try a "what if" without touching the real one. **Rename…** and **Delete…** do what they say. A deleted budget's file is moved to a `Deleted` folder, not erased, and your only budget can't be deleted.
- **Budget ▾ → Open the demo budget** adds "Demo": a made-up household (everyday checking, a savings account, two credit cards on the minimum, a car loan, a paycheck every two weeks, everyday bills and a paycheck split) with every date counted from the day it's made, so it always looks current. A banner says you're in the demo. Delete it any time with Budget ▾ → Delete. A brand-new, empty budget shows a hint pointing to it.
- **Budget ▾ → Share this budget…** moves the open budget into a folder that syncs between computers (iCloud Drive, Dropbox, Google Drive or a network share), so you and your partner both use **the same budget**. On their Mac, **Budget ▾ → Open a shared budget…** and pick that file. It shows as "Household (shared)". Every few seconds the app checks for changes saved on the other computer and reloads them (the status line says when). If you both change something at nearly the same moment, the second save is refused rather than overwriting the first: that person sees the other change and is asked to redo theirs. For a shared budget, **Rename** only changes the name on your list and **Remove from my list** never deletes the shared file, since the other computer still uses it. If the synced folder isn't available (say iCloud Drive isn't running), the app opens another budget and tells you when you try that one.
- **Budget ▾ → Export…** saves a copy of the open budget (on your Desktop by default) as `<name>.runwayd.json`. Send it by AirDrop, Messages or email. In their TreasureTracker, **Budget ▾ → Import…** adds it as a new budget; nothing they have is replaced, and a name that's taken gets "(2)". From then on, their copy and yours are separate. The file holds everything in that budget, so only send it to people you'd share your finances with.

Each budget is one file in `~/runwayd/budgets/` (e.g. `My budget.json`), with the previous version kept as `My budget.json.bak`. The status line at the bottom of the window shows the open one. Your data from before there were budgets became **My budget**; the old `~/runwayd/treasuretracker.json` is left where it was, as a backup.


