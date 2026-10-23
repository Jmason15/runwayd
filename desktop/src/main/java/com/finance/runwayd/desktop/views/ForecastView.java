package com.finance.runwayd.desktop.views;

import com.finance.runwayd.core.bills.ChecklistItem;
import com.finance.runwayd.core.debt.DebtSummary;
import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.forecast.Forecast;
import com.finance.runwayd.core.forecast.LedgerEntry;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.desktop.ui.Charts;
import com.finance.runwayd.desktop.ui.Format;
import com.finance.runwayd.desktop.Refreshable;
import com.finance.runwayd.desktop.ui.Tables;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The main screen: every account's balance, what's coming up, and how low each account gets.
 *
 * <pre>
 *                                                            [x] Include budget splits
 *   (warning if a balance is old)
 *   ┌ Accounts: balance (click to type your bank's) / updated / low point in the next 3 months ┐
 *   Total debt: today, debt-free date
 *   ┌ This month: [✓] Oct 1  Store Card  -$340.06  Paid  ...  tick bills off as you pay them ┐
 *   Details for: [account]
 *   ┌ Chart of that account's balance over time ┐
 *   ┌ Every change to that account, with the running balance ┐
 * </pre>
 *
 * The page scrolls if the window is too short, so no section ever gets squeezed.
 */
public final class ForecastView extends ScrollPane implements Refreshable {

    /** Balances older than this get a reminder to update them. */
    private static final int STALE_AFTER_DAYS = 14;


    // The most rows each table shows before it starts scrolling.
    private static final int MAX_ACCOUNT_ROWS = 10;
    private static final int MAX_CHECKLIST_ROWS = 10;
    private static final DateTimeFormatter MONTH_HEADING = DateTimeFormatter.ofPattern("MMMM yyyy");
    private static final DateTimeFormatter SHORT_MONTH = DateTimeFormatter.ofPattern("MMM yyyy");

    /** How far ahead the checklist goes: this month, or up to a year. */
    private enum ChecklistRange {
        THIS_MONTH("This month", 1),
        THREE_MONTHS("3 months", 3),
        SIX_MONTHS("6 months", 6),
        ONE_YEAR("1 year", 12);

        private final String label;
        private final int months;

        ChecklistRange(String label, int months) {
            this.label = label;
            this.months = months;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final FinanceService service;

    /** How far ahead the low-point warning, the chart and the ledger look. */
    private static final int LOOK_AHEAD_MONTHS = 3;

    private final CheckBox includeBudget = new CheckBox("Include budget splits");
    private final Label staleWarning = new Label();
    private final Label debtLine = new Label();
    private final TableView<AccountSummary> summaryTable = Tables.create("Add an account on the Accounts tab to get started.");
    private final TableView<ChecklistItem> checklistTable = Tables.create("Nothing due in this period.");
    private final Label checklistHeading = new Label();
    private final ComboBox<ChecklistRange> checklistRange = new ComboBox<>();
    private final ComboBox<Account> accountPicker = new ComboBox<>();
    private final LineChart<Number, Number> chart = Charts.dateChart();
    private final TableView<LedgerEntry> ledgerTable = Tables.create("Nothing scheduled for this account before that date.");

    /** The most recent forecast, kept so switching accounts doesn't recalculate. */
    private Forecast forecast;

    /** One row of the summary table. The text in {@code note} depends on the account type. */
    private record AccountSummary(Account account, BigDecimal balance, String note) {
    }

    public ForecastView(FinanceService service) {
        this.service = service;

        buildSummaryTable();
        buildChecklist();
        buildLedgerTable();

        // Names the QA tests use to find things (desktop/src/test/.../qa).
        summaryTable.setId("forecastAccounts");
        checklistTable.setId("checklist");
        ledgerTable.setId("ledger");
        debtLine.setId("debtLine");
        checklistRange.setId("checklistRange");
        accountPicker.setId("detailsAccount");

        staleWarning.getStyleClass().add("warning");
        staleWarning.setWrapText(true);
        staleWarning.setMaxWidth(Double.MAX_VALUE);

        Label detailsLabel = new Label("Details for");
        HBox detailsBar = new HBox(8, detailsLabel, accountPicker);
        detailsBar.setAlignment(Pos.CENTER_LEFT);

        Label balanceHint = new Label("Tip: click an amount under \"Balance\" to type in what your bank or card statement shows, "
                + "then press Enter. Cards and loans show as negative because that money is owed.");
        balanceHint.getStyleClass().add("hint");
        debtLine.getStyleClass().add("debt-line");

        Tables.sizeToRows(summaryTable, MAX_ACCOUNT_ROWS);
        Tables.sizeToRows(checklistTable, MAX_CHECKLIST_ROWS);
        ledgerTable.setPrefHeight(320);

        // Everything stacked top to bottom, full width.
        VBox page = new VBox(12,
                buildTopBar(),
                staleWarning,
                section("Accounts", summaryTable, balanceHint, debtLine),
                checklistSection(),
                detailsBar,
                chart,
                ledgerTable);
        page.setPadding(new Insets(16));

        setContent(page);
        setFitToWidth(true); // stretch to the window's width; only scroll up and down

        accountPicker.valueProperty().addListener((obs, oldAccount, newAccount) -> showDetails());
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    /** Just the "Include budget splits" switch, on the right. */
    private HBox buildTopBar() {
        // On: each paycheck's Budget tab split leaves checking on payday (and the debt bucket pays
        // down debts). Off: the forecast shows just your bills, as if the extra cash stayed put.
        includeBudget.setTooltip(new Tooltip("Take each paycheck's Budget tab split out of checking on payday."));
        includeBudget.setOnAction(event -> {
            service.setBudgetInForecast(includeBudget.isSelected());
            refresh();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, spacer, includeBudget);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void buildSummaryTable() {
        summaryTable.getColumns().add(Tables.text("Account", row -> row.account().name()));
        // Click to check the account online (the website set on the Accounts or Debt tab).
        summaryTable.getColumns().add(Tables.link("Website", row -> row.account().website()));
        summaryTable.getColumns().add(Tables.text("Type", row -> row.account().type().label()));
        // The balance as entered (the same as the Accounts tab) and when it was last updated. Typing in the
        // Balance column saves what the bank shows now, as of today, the same as Update balance.
        summaryTable.setEditable(true);
        summaryTable.getColumns().add(Tables.editableMoney("Balance", AccountSummary::balance, this::saveBankBalance));
        summaryTable.getColumns().add(Tables.text("Updated", row -> updatedText(row.account().balanceDate())));
        summaryTable.getColumns().add(Tables.text("Watch out for", AccountSummary::note));
        // Clicking a row shows that account's details below.
        summaryTable.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, row) -> {
            if (row != null) {
                accountPicker.setValue(row.account());
            }
        });
    }

    /**
     * Saves what your bank shows right now, the same as Update balance on the Accounts tab. Everything
     * on screen is recalculated from it; bills you haven't ticked still count (see the checklist).
     */
    private void saveBankBalance(AccountSummary row, BigDecimal bankBalance) {
        // For a card or loan, "2400.00" and "-2400.00" both mean you owe $2,400.00.
        BigDecimal balance = row.account().type().isDebt() ? bankBalance.abs() : bankBalance;
        service.updateBalance(row.account().id(), balance, LocalDate.now());
        refresh();
    }

    /**
     * Every bill and paycheck this month (plus any still unticked from earlier), soonest first.
     * With "Include budget splits" on, each budget bucket's share of each payday is listed too,
     * one line per bucket (e.g. "Savings → Savings"), so each transfer is ticked on its own.
     * Tick one as you pay it (or move the money) and the forecast stops counting it; untick it to put it back.
     */
    private void buildChecklist() {
        checklistTable.getColumns().add(paidColumn());
        checklistTable.getColumns().add(Tables.date("Due", ChecklistItem::date));
        checklistTable.getColumns().add(Tables.text("What", ChecklistItem::name));
        TableColumn<ChecklistItem, String> website = Tables.link("Website", entry -> entry.isBudgetSplit() ? null : entry.item().website());
        website.setPrefWidth(110); // most rows have none, so leave the room to "What"
        checklistTable.getColumns().add(website);
        checklistTable.getColumns().add(Tables.money("Amount", this::amountOf));
        checklistTable.getColumns().add(Tables.text("Account", entry -> entry.accountId() == null ? ""
                : service.findAccount(entry.accountId()).map(Account::name).orElse("")));
        checklistTable.getColumns().add(Tables.money("Balance after", this::balanceAfter));
        checklistTable.getColumns().add(Tables.text("Status", entry -> entry.status().toString()));
    }

    /**
     * How much the line moves: a bucket's share, or what the forecast actually pays that day. That's
     * not always the bill's usual amount: a card minimum shrinks as the card is paid down, and a last
     * payment is only what's still owed. A ticked line isn't in the forecast, so it shows the usual amount.
     */
    private BigDecimal amountOf(ChecklistItem entry) {
        if (entry.isBudgetSplit()) {
            return entry.bucketAmount().negate();
        }
        if (entry.change() == null) {
            return service.signedAmount(entry.item());
        }
        // Same sign as "Balance after": on a card or loan, more owed shows as money out.
        boolean owed = service.findAccount(entry.accountId()).map(a -> a.type().isDebt()).orElse(false);
        return owed ? entry.change().negate() : entry.change();
    }

    /**
     * What the line's account holds right after it, from the forecast; blank if it isn't counted
     * (ticked, or before the last balance update). Cards and loans show as negative, since that's owed.
     */
    private BigDecimal balanceAfter(ChecklistItem entry) {
        if (entry.balanceAfter() == null) {
            return null;
        }
        boolean owed = service.findAccount(entry.accountId()).map(a -> a.type().isDebt()).orElse(false);
        return owed ? entry.balanceAfter().negate() : entry.balanceAfter();
    }

    /**
     * "This month: October 2026   Show [This month ▾]" above the checklist, with a one-line tip under it.
     * Picking 3 months, 6 months or 1 year lists everything coming up that far ahead.
     */
    private VBox checklistSection() {
        checklistHeading.getStyleClass().add("heading");
        checklistRange.getItems().setAll(ChecklistRange.values());
        checklistRange.setValue(ChecklistRange.THIS_MONTH);
        checklistRange.setOnAction(event -> showChecklist());
        HBox header = new HBox(12, checklistHeading, new Label("Show"), checklistRange);
        header.setAlignment(Pos.CENTER_LEFT);
        Label tip = new Label("Tick bills off as you pay them, and budget splits once you've moved the money. "
                + "Anything not ticked still counts in the forecast, including things due today.");
        tip.getStyleClass().add("hint");
        return new VBox(6, header, checklistTable, tip);
    }

    /**
     * The tick box column. A box is ticked only when you tick it: that bill is paid and stops counting.
     * Untick it to put it back. Nothing ticks a box for you, not even a balance update.
     */
    private TableColumn<ChecklistItem, ChecklistItem> paidColumn() {
        TableColumn<ChecklistItem, ChecklistItem> column = new TableColumn<>("Paid");
        column.setEditable(false); // the check box does the changing, not table editing
        column.setMaxWidth(60);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            {
                box.setOnAction(event -> {
                    ChecklistItem entry = getItem();
                    if (entry != null) {
                        if (entry.isBudgetSplit()) {
                            service.setBucketDone(entry.bucket().name(), entry.date(), box.isSelected());
                        } else {
                            service.setPaid(entry.item().id(), entry.date(), box.isSelected());
                        }
                        // Refresh after this click finishes; rebuilding the table mid-click can upset JavaFX.
                        Platform.runLater(ForecastView.this::refresh);
                    }
                });
            }

            @Override
            protected void updateItem(ChecklistItem entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setGraphic(null);
                } else {
                    box.setSelected(entry.isDone());
                    setGraphic(box);
                }
            }
        });
        return column;
    }

    /** A bold title above some content. */
    private static VBox section(String title, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("heading");
        VBox section = new VBox(6, heading);
        section.getChildren().addAll(content);
        return section;
    }

    private void buildLedgerTable() {
        ledgerTable.getColumns().add(Tables.date("Date", LedgerEntry::date));
        ledgerTable.getColumns().add(Tables.text("What", LedgerEntry::description));
        ledgerTable.getColumns().add(Tables.money("Change", LedgerEntry::change));
        ledgerTable.getColumns().add(Tables.money("Balance after", LedgerEntry::balanceAfter));
    }

    // -------------------------------------------------------------------------
    // Filling in the data
    // -------------------------------------------------------------------------

    @Override
    public void refresh() {
        LocalDate date = LocalDate.now().plusMonths(LOOK_AHEAD_MONTHS);
        includeBudget.setSelected(service.budgetInForecast());
        List<Account> accounts = service.accounts();

        forecast = service.forecast(date);
        DebtSummary debt = service.debtSummary(date);

        // Every account in one list: checking and savings first, then cards and loans.
        summaryTable.getItems().setAll(accounts.stream()
                .map(account -> summaryRow(account, debt))
                .toList());
        showChecklist();
        showDebtLine(debt);

        showStaleWarning(accounts);
        refreshAccountPicker(accounts);
        showDetails();
    }

    /**
     * Fills the checklist for the chosen range: "This month: October 2026", or for longer ranges
     * "Coming up: Oct 2026 – Sep 2027" (this month plus the next ones).
     */
    private void showChecklist() {
        YearMonth first = YearMonth.now();
        YearMonth last = first.plusMonths(checklistRange.getValue().months - 1L);
        checklistHeading.setText(first.equals(last)
                ? "This month: " + first.format(MONTH_HEADING)
                : "Coming up: " + first.format(SHORT_MONTH) + " – " + last.format(SHORT_MONTH));
        checklistTable.getItems().setAll(service.checklist(first, last));
    }

    /** The lowest the account gets before the chosen date, flagged if it goes below zero. */
    private String noteFor(Account account) {
        return forecast.lowPointOf(account.id())
                .map(low -> (low.balance().signum() < 0 ? "OVERDRAWN: " : "Lowest: ")
                        + Format.money(low.balance()) + " on " + Format.date(low.date()))
                .orElse("");
    }

    /**
     * One row of the Accounts table.
     *
     * <p>Debts are shown as negative numbers (shown in red), the way budgeting apps
     * do it, because money you owe counts against you. The account itself still
     * stores what you owe as a positive number.
     */
    /** "Today" if the balance was updated today, otherwise the date it was, e.g. "Oct 18, 2026". */
    private static String updatedText(LocalDate balanceDate) {
        return balanceDate.equals(LocalDate.now()) ? "Today" : Format.date(balanceDate);
    }

    /**
     * One row. "Balance" is the balance as entered (the same as the Accounts tab), so it matches the bank;
     * bills not ticked yet show up on the checklist instead (its Balance after starts from this balance).
     */
    private AccountSummary summaryRow(Account account, DebtSummary debt) {
        if (!account.type().isDebt()) {
            return new AccountSummary(account, account.balance(), noteFor(account));
        }
        String note = debt.debts().stream()
                .filter(row -> row.account().id() == account.id())
                .findFirst()
                .map(row -> row.payoffDate() == null
                        ? "Not paid off with current payments"
                        : "Paid off " + Format.monthYear(row.payoffDate()))
                .orElse("");
        return new AccountSummary(account, account.balance().negate(), note);
    }

    /** "Total debt: $17,550.00 today, $13,600.00 on Jan 18, 2027. Debt-free: Aug 2029". Hidden with no debts. */
    private void showDebtLine(DebtSummary debt) {
        boolean hasDebt = !debt.debts().isEmpty();
        debtLine.setVisible(hasDebt);
        debtLine.setManaged(hasDebt);
        if (hasDebt) {
            String debtFree = debt.debtFreeDate() == null
                    ? "not with current payments"
                    : Format.monthYear(debt.debtFreeDate());
            debtLine.setText("Total debt: " + Format.money(debt.totalToday()) + " today.   Debt-free: " + debtFree + ".   (Details on the Debt tab.)");
        }
    }

    /** Remind you to update balances that are more than two weeks old. */
    private void showStaleWarning(List<Account> accounts) {
        LocalDate cutoff = LocalDate.now().minusDays(STALE_AFTER_DAYS);
        List<String> stale = accounts.stream()
                .filter(account -> account.balanceDate().isBefore(cutoff))
                .map(account -> account.name() + " (" + Format.date(account.balanceDate()) + ")")
                .toList();

        staleWarning.setText("These balances are more than " + STALE_AFTER_DAYS + " days old, so the forecast is a guess: "
                + String.join(", ", stale) + ". Click the amount under \"Balance\" and type what your bank shows.");
        staleWarning.setVisible(!stale.isEmpty());
        staleWarning.setManaged(!stale.isEmpty());
    }

    /** Reload the drop-down but keep the same account selected if it still exists. */
    private void refreshAccountPicker(List<Account> accounts) {
        Integer selectedId = accountPicker.getValue() == null ? null : accountPicker.getValue().id();
        accountPicker.getItems().setAll(accounts);
        accountPicker.setValue(accounts.stream()
                .filter(account -> selectedId != null && account.id() == selectedId)
                .findFirst()
                .orElse(accounts.isEmpty() ? null : accounts.get(0)));
    }

    /** Fill the chart and ledger for the account in the drop-down. */
    private void showDetails() {
        Account account = accountPicker.getValue();
        chart.getData().clear();
        if (account == null || forecast == null) {
            ledgerTable.getItems().clear();
            return;
        }

        List<LedgerEntry> entries = forecast.ledgerFor(account.id());
        ledgerTable.getItems().setAll(entries);
        chart.getData().add(balanceLine(account, entries));
        chart.setTitle(account.name() + (account.type().isDebt() ? ": amount owed" : ": balance"));
    }

    /** The account's balance over time: where it starts, then after each change. */
    private XYChart.Series<Number, Number> balanceLine(Account account, List<LedgerEntry> entries) {
        List<Charts.Step> steps = new ArrayList<>();
        steps.add(new Charts.Step(account.balanceDate(), account.balance()));
        entries.forEach(entry -> steps.add(new Charts.Step(entry.date(), entry.balanceAfter())));
        return Charts.staircase(steps, forecast.through());
    }
}
