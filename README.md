# runwayd

Pick any future date and see how much money each account will have, and when each debt will be paid off. A personal finance desktop app in Java 21 + JavaFX.

*The name:* a startup's **runway** is how long its money lasts, and on Unix a name ending in **d** is a daemon (`sshd`, `crond`, `launchd`): a program that runs in the background and keeps watch. So `runwayd` is the daemon watching how far your money goes.

**Balance on a date = the last balance you entered + every paycheck, bill and payment scheduled between then and that date.**

## The tabs

| Tab | What it's for |
|---|---|
| **Forecast** | Every account's balance on any date, a chart, the full day-by-day ledger, and a checklist (this month, or up to a year ahead) to tick bills and budget transfers off |
| **Bills & Income** | Paychecks, bills and transfers grouped by category, with search, filters, totals per month and year, and Mark paid / Undo |
| **Debt** | What you owe, interest vs. principal, payoff dates, debt-free date, and the snowball rollover |
| **Budget** | Splits each paycheck into buckets (spent, into an account, or paying down debt) while never letting checking drop below the amount you keep |
| **Accounts** | Checking and savings accounts and their balances |

Several separate **budgets** (each a complete set of accounts, bills and splits) can be kept and switched at the top of the window. A budget can be **shared** through a synced folder (iCloud Drive, Dropbox…) so two computers use the same one and see each other's changes within seconds, or exported as a copy to send to someone.

It opens in a dark "Night runway" look (navy, with amber like runway lights). **View → Dark Mode** (⌘⇧D) switches to light and back; the choice is remembered.

New here? **Budget ▾ → Open the demo budget** adds a made-up household (checking, savings, two credit cards, a car loan, paychecks, bills and a paycheck split) to click around in. Its dates start from the day you open it, and it never touches your own budgets.

The rules behind each tab are in **[docs/how-it-works.md](docs/how-it-works.md)**.

## Running it

Needs **Java 21** and **Maven 3.9**.

```bash
mvn install                       # build both modules and run the tests
mvn -pl desktop javafx:run        # open the app
```

Your data lives outside the repo, in `~/runwayd/budgets/` (one JSON file per budget), so nothing personal is ever committed. (The app used to be called Paydar, and before that TreasureTracker. The first time runwayd starts it copies your data from `~/Paydar/` or `~/TreasureTracker/` to `~/runwayd/`, leaving the old folder as a backup.)

## How the code is laid out

Two Maven modules. `core` has no JavaFX dependency, so screen code can't leak into it, and every rule can be unit tested.

```
core/         BACKEND: plain Java, no UI
  FinanceService     the only class the screens talk to; hands each call to the feature below
  Workspace          the open budget's data in memory; saves after every change
  accounts/          Accounts: your accounts and balances
  bills/             Bills: bills, paychecks, transfers, mark paid / undo
                     Checklist, ChecklistItem: the Forecast tab's "This month" list
  debt/              Debts, DebtSummary: how debts are paid and the Debt tab's numbers
                     LoanCalculator: the standard loan payment formula
  budget/            BudgetPlans: the Budget tab's settings, plan and moves
                     BudgetPlanner, BudgetPlan: the safe, evened-out amount per paycheck
  forecast/          Forecaster: plays the schedule forward day by day (all the balance math)
                     Forecasts: runs it on your data; Forecast, LedgerEntry: the results
                     ScheduleCalculator: which days something happens
  model/             the saved records: Account, ScheduledItem, Budget, BudgetBucket, ...
  storage/           JsonDataStore: one readable JSON file; BudgetLibrary: the folder of budgets
  demo/              DemoBudget: the made-up household behind "Open the demo budget"

desktop/      FRONTEND: JavaFX only, no money math
  App, Launcher      start-up; opens a budget and builds the window
  MainWindow         the five tabs and the status line
  BudgetSwitcher     the "Budget:" bar at the top
  views/             one class per tab
  dialogs/           the add/edit forms (FormDialog is their shared base)
  ui/                small shared helpers: Tables, Charts, Format, Alerts, Browser, Theme (light/dark)
```

Tests sit in the same packages as the code they test (`core/src/test/...`).

## Installing it on a Mac

Download the latest `runwayd-x.y.z.dmg` from the GitHub Releases page, open it, and drag runwayd into Applications. It's signed with a Developer ID and notarized by Apple, so it opens like any other app. No budgets or personal data are in it: a Mac that has never run it starts with an empty budget (and a hint to try the demo); a Mac that already has data keeps it.

For a quick local build to try something out (unsigned, so macOS asks you to allow it under **System Settings → Privacy & Security → Open Anyway**): `./package-mac.sh` → `dist/runwayd-x.y.z.dmg`.

## Releasing an update

Installed copies check GitHub Releases at start-up and every few hours (or via **Help → Check for Updates…**). When there's a newer version they download it, check it's signed by **your Apple Team ID** and accepted by Gatekeeper, and show **"runwayd 1.2.0 is ready. [Restart to update]"**. Clicking it swaps in the new app and reopens it; budgets live outside the app, so they're never touched.

To publish a version, commit your changes, then:

```bash
./publish.sh 1.2.0 "What changed"
```

It checks your setup, sets the version, builds and tests, signs, gets Apple's notarization (a few minutes), makes the update zip and the `.dmg`, tags `v1.2.0`, pushes, and creates the GitHub release. It stops at the first problem, before anything is pushed.

**One-time setup on the Mac you publish from:**

1. **Developer ID certificate.** In your Apple Developer account, create a *Developer ID Application* certificate and install it in your keychain (or export it from another Mac as a .p12 and double-click it). `security find-identity -v -p codesigning` should list "Developer ID Application: Your Name (TEAMID)".
2. **Notary credentials.** Make an app-specific password at appleid.apple.com, then run `xcrun notarytool store-credentials runwayd-notary --apple-id you@example.com --team-id TEAMID` and paste it when asked. It's stored in your keychain.
3. **GitHub CLI.** Install `gh` and run `gh auth login`.
4. **Settings in `pom.xml`:** `runwayd.updateRepo` (e.g. `yourname/runwayd`) and `runwayd.teamId` (your 10-character Team ID). These are baked into each build so installed copies know where to look and whose signature to trust.
