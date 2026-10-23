package com.finance.runwayd.desktop.views;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.budget.BudgetPlan;
import com.finance.runwayd.core.budget.BudgetPlan.PaycheckSplit;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.Budget;
import com.finance.runwayd.core.model.BudgetBucket;
import com.finance.runwayd.desktop.ui.Format;
import com.finance.runwayd.desktop.Refreshable;
import com.finance.runwayd.desktop.ui.Tables;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Split the extra cash from each paycheck into buckets: your spending, your partner's,
 * savings, investments, paying down debt, or anything you add.
 *
 * <pre>
 *   Always keep at least $[1000] in [Checking ▾]
 *
 *   Buckets                                   (applied to every paycheck)
 *     [My spending     ] [400] [$ ▾] [Spent ▾]           [Remove]
 *     [Paying down debt] [ 30] [% ▾] [Pays down: Visa ▾]  [Remove]
 *     [+ Add bucket]
 *
 *   Paychecks   Show the next [3 months ▾]
 *   Payday   Paycheck   Available   My spending   Savings   ...   Left over
 *   Next 3 months (6 paychecks): $9,081.21 available.  My spending $1,816.20  ·  ...

 * </pre>
 *
 * <p>"Available" is a steady amount per paycheck that keeps the account above the amount
 * to keep, counting every bill coming after it (see BudgetPlanner in core for how it's evened out).
 *
 * <p>It saves its own settings and never changes bills or debts. When "Include budget
 * splits" is on (RunWay tab), the forecast plays each paycheck's split in on payday.
 */
public final class BudgetView extends ScrollPane implements Refreshable {

    /** The most paycheck rows shown at once; past this the table scrolls. */
    private static final int MAX_ROWS_SHOWN = 12;

    /** The "Show the next" choices. */
    private enum Period {
        ONE_MONTH("1 month", 1),
        THREE_MONTHS("3 months", 3),
        SIX_MONTHS("6 months", 6),
        ONE_YEAR("1 year", 12);

        private final String label;
        private final int months;

        Period(String label, int months) {
            this.label = label;
            this.months = months;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final FinanceService service;

    private final TextField cushionField = new TextField();
    private final ComboBox<Account> accountBox = new ComboBox<>();
    private final GridPane bucketGrid = new GridPane();
    private final List<BucketRow> bucketRows = new ArrayList<>();
    private final Label editError = new Label();
    private final Label percentTotal = new Label();
    private final Label lowBalanceWarning = new Label();
    private final Label leftOverNote = new Label(
            "A red \"Left over\" means your dollar buckets ask for more than that paycheck can safely give.");
    private final ComboBox<Period> periodBox = new ComboBox<>();
    private final Label periodTotals = new Label();
    private final TableView<PaycheckSplit> paycheckTable = Tables.create("No paychecks found going into this account.");

    // True while refresh() fills the boxes in. Filling them fires the same events as typing, and
    // saving a half-filled form would wipe out buckets, so saving is skipped until it's done.
    private boolean loading;

    /**
     * One choice in a bucket's "Goes to" list: Spent, "Into: Savings" for each of your other
     * accounts, "Pays down debt (highest rate)", or "Pays down: Visa" for each of your debts.
     *
     * @param accountId the account or debt picked, or null for Spent / highest rate first
     */
    private record GoesTo(String label, BudgetBucket.Destination destination, Integer accountId) {
        @Override
        public String toString() {
            return label;
        }
    }

    // The "Goes to" choices, rebuilt on each refresh so new or renamed debts show up.
    private List<GoesTo> goesToChoices = List.of();

    /** One line of the bucket editor: name, amount, $ or %, where it goes, and a Remove button. */
    private record BucketRow(TextField name, TextField value, ComboBox<BudgetBucket.Unit> unit,
                             ComboBox<GoesTo> goesTo, Button remove) {
    }

    public BudgetView(FinanceService service) {
        this.service = service;
        // Names the QA tests use to find things.
        cushionField.setId("cushion");
        accountBox.setId("budgetAccount");
        bucketGrid.setId("buckets");
        editError.setId("budgetError");
        paycheckTable.setId("paychecks");
        lowBalanceWarning.setId("lowBalanceWarning");
        periodBox.setId("budgetPeriod");

        Label heading = new Label("Split each paycheck");
        heading.getStyleClass().add("heading");
        Label intro = new Label("\"Available\" is what you can move out of the account on payday. It's evened out so "
                + "each paycheck gets a steady amount, and the account still never drops below the amount to keep, "
                + "with every upcoming bill and debt payment covered. "
                + "Dollar buckets are filled first, then percent buckets share what's left. "
                + "\"Goes to\" decides what the money does when the budget is included in the forecast "
                + "(the switch on the RunWay tab): it's spent, it moves into one of your accounts (e.g. savings, "
                + "so that balance grows), or it pays down debts: highest interest rate first, "
                + "or one debt you pick (once that one's paid off, its money stays in checking). "
                + "Your bills and debts themselves are never changed.");
        intro.getStyleClass().add("hint");
        intro.setWrapText(true);

        editError.getStyleClass().add("form-error");
        percentTotal.getStyleClass().add("hint");
        editError.setWrapText(true);
        for (Label banner : new Label[] {lowBalanceWarning, leftOverNote}) {
            banner.setWrapText(true);
            banner.setMaxWidth(Double.MAX_VALUE);
        }
        lowBalanceWarning.getStyleClass().add("warning");
        leftOverNote.getStyleClass().add("hint");

        bucketGrid.setHgap(8);
        bucketGrid.setVgap(6);
        Button addBucket = new Button("+ Add bucket");
        addBucket.setOnAction(event -> {
            addBucketRow(new BudgetBucket("New bucket", BigDecimal.ZERO, BudgetBucket.Unit.DOLLARS));
            saveSettings();
        });

        Tables.sizeToRows(paycheckTable, MAX_ROWS_SHOWN);

        // How far ahead to list paychecks. Only changes which rows show, not the amounts.
        periodBox.getItems().setAll(Period.values());
        periodBox.setValue(Period.THREE_MONTHS);
        periodBox.setOnAction(event -> showPlan());
        periodTotals.getStyleClass().add("heading");
        periodTotals.setWrapText(true);
        // The bucket columns change as you add or remove buckets, so share the width across all
        // columns instead of letting the first one take whatever is left.
        paycheckTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        VBox page = new VBox(12,
                heading, intro,
                buildCushionRow(),
                section("Buckets (applied to every paycheck)", bucketGrid, bucketFooter(addBucket), editError),
                lowBalanceWarning,
                paycheckSection());
        page.setPadding(new Insets(16));
        setContent(page);
        setFitToWidth(true);
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private HBox buildCushionRow() {
        cushionField.setPrefWidth(100);
        cushionField.setOnAction(event -> saveSettings());
        saveWhenLeft(cushionField);
        accountBox.setOnAction(event -> {
            if (loading) {
                return;
            }
            saveSettings();
            if (editError.getText().isEmpty()) {
                refresh(); // the "Into:" choices leave out this account, so rebuild them
            }
        });
        HBox row = new HBox(8, new Label("Always keep at least $"), cushionField, new Label("in"), accountBox);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Adds one editable bucket line to the grid. */
    private void addBucketRow(BudgetBucket bucket) {
        TextField name = new TextField(bucket.name());
        name.setPrefWidth(200);
        TextField value = new TextField(bucket.value().stripTrailingZeros().toPlainString());
        value.setPrefWidth(90);
        ComboBox<BudgetBucket.Unit> unit = new ComboBox<>();
        unit.getItems().setAll(BudgetBucket.Unit.values());
        unit.setValue(bucket.unit());
        ComboBox<GoesTo> goesTo = new ComboBox<>();
        goesTo.getItems().setAll(goesToChoices);
        goesTo.setValue(choiceFor(bucket));
        goesTo.setPrefWidth(240); // room for "Pays down: <card name>"
        Button remove = new Button("Remove");

        BucketRow row = new BucketRow(name, value, unit, goesTo, remove);
        bucketRows.add(row);
        bucketGrid.addRow(bucketGrid.getRowCount(), name, value, unit, goesTo, remove);

        // Save when you press Enter, leave a box, or pick $ / %.
        name.setOnAction(event -> saveSettings());
        value.setOnAction(event -> saveSettings());
        saveWhenLeft(name);
        saveWhenLeft(value);
        unit.setOnAction(event -> saveSettings());
        goesTo.setOnAction(event -> saveSettings());
        remove.setOnAction(event -> {
            bucketRows.remove(row);
            rebuildBucketGrid();
            saveSettings();
        });
    }

    /** Lays the bucket lines out again, e.g. after one is removed. */
    private void rebuildBucketGrid() {
        bucketGrid.getChildren().clear();
        for (BucketRow row : bucketRows) {
            bucketGrid.addRow(bucketGrid.getRowCount(), row.name(), row.value(), row.unit(), row.goesTo(), row.remove());
        }
    }

    private void saveWhenLeft(TextField field) {
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                saveSettings();
            }
        });
    }

    private static VBox section(String title, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("heading");
        VBox section = new VBox(6, heading);
        section.getChildren().addAll(content);
        return section;
    }

    // -------------------------------------------------------------------------
    // Saving and showing
    // -------------------------------------------------------------------------

    /** Reads the boxes, saves the budget, and refreshes the paycheck table. Shows a message if something's off. */
    private void saveSettings() {
        if (loading) {
            return;
        }
        try {
            List<BudgetBucket> buckets = new ArrayList<>();
            for (BucketRow row : bucketRows) {
                BigDecimal value = row.value().getText().isBlank()
                        ? BigDecimal.ZERO
                        : Format.parseNumber(row.value().getText(), "\"" + row.name().getText() + "\"");
                GoesTo goesTo = row.goesTo().getValue();
                buckets.add(new BudgetBucket(row.name().getText(), value, row.unit().getValue(),
                        goesTo.destination(), goesTo.accountId()));
            }
            BigDecimal cushion = Format.parseMoney(cushionField.getText(), "Amount to keep");
            Integer accountId = accountBox.getValue() == null ? null : accountBox.getValue().id();
            // Keep the RunWay tab's "Include budget splits" switch as it was.
            service.saveBudget(new Budget(accountId, cushion, buckets, service.budgetInForecast()));
            editError.setText("");
            showPlan();
        } catch (IllegalArgumentException problem) {
            // Shown under the buckets instead of a pop-up, since this runs every time you leave a box.
            editError.setText(problem.getMessage());
        }
    }

    @Override
    public void refresh() {
        loading = true;
        try {
            loadSettings();
        } finally {
            loading = false;
        }
        showPlan();
    }

    /**
     * Spent, then "Into: ..." for each checking or savings account except the one the budget comes
     * out of, then highest rate first, then one "Pays down: ..." choice per debt.
     */
    private List<GoesTo> buildGoesToChoices() {
        List<GoesTo> choices = new ArrayList<>();
        choices.add(new GoesTo("Spent", BudgetBucket.Destination.SPENT, null));
        Integer keptIn = service.budgetAccount().map(Account::id).orElse(null);
        for (Account account : service.cashAccounts()) {
            if (!Objects.equals(account.id(), keptIn)) {
                choices.add(new GoesTo("Into: " + account.name(), BudgetBucket.Destination.ACCOUNT, account.id()));
            }
        }
        choices.add(new GoesTo("Pays down debt (highest rate)", BudgetBucket.Destination.DEBT, null));
        for (Account debt : service.debtAccounts()) {
            choices.add(new GoesTo("Pays down: " + debt.name(), BudgetBucket.Destination.DEBT, debt.id()));
        }
        return choices;
    }

    /**
     * The choice matching a saved bucket. A debt that no longer exists falls back to highest rate
     * first; an account that no longer exists falls back to Spent.
     */
    private GoesTo choiceFor(BudgetBucket bucket) {
        return goesToChoices.stream()
                .filter(choice -> choice.destination() == bucket.destination()
                        && Objects.equals(choice.accountId(), bucket.accountId()))
                .findFirst()
                .orElseGet(() -> goesToChoices.stream()
                        .filter(choice -> bucket.destination() == BudgetBucket.Destination.DEBT
                                ? choice.destination() == BudgetBucket.Destination.DEBT && choice.accountId() == null
                                : choice.destination() == BudgetBucket.Destination.SPENT)
                        .findFirst().orElseThrow());
    }

    private void loadSettings() {
        Budget budget = service.budget();
        goesToChoices = buildGoesToChoices();
        cushionField.setText(Format.moneyForEditing(budget.cushion()));
        accountBox.getItems().setAll(service.cashAccounts());
        accountBox.setValue(service.budgetAccount().orElse(null));

        bucketRows.clear();
        bucketGrid.getChildren().clear();
        budget.buckets().forEach(this::addBucketRow);
        editError.setText("");
    }

    /** Recalculates the paychecks and rebuilds the table, one column per bucket. */
    private void showPlan() {
        showPercentTotal();
        Period period = periodBox.getValue();
        BudgetPlan plan = service.budgetPlanUntil(LocalDate.now().plusMonths(period.months));
        List<BudgetBucket> buckets = service.budget().buckets();

        // Build every column first, then swap them all in at once. Adding them one by one lets the
        // first column grab the whole width before the others arrive.
        List<TableColumn<PaycheckSplit, ?>> columns = new ArrayList<>();
        columns.add(Tables.date("Payday", PaycheckSplit::date));
        columns.add(Tables.text("Paycheck", PaycheckSplit::paycheckName));
        columns.add(Tables.money("Amount", PaycheckSplit::paycheck));
        columns.add(Tables.money("Available", PaycheckSplit::available));
        for (int i = 0; i < buckets.size(); i++) {
            int index = i; // the lambda needs a variable that doesn't change
            columns.add(Tables.money(buckets.get(i).name(), split -> split.bucketAmounts().get(index)));
        }
        columns.add(Tables.money("Left over", PaycheckSplit::leftOver));
        paycheckTable.getColumns().setAll(columns);
        paycheckTable.getItems().setAll(plan.paychecks());

        showLowBalanceWarning(plan);
        showPeriodTotals(plan, buckets, period);
    }

    /** "+ Add bucket" with the running % total beside it. */
    private HBox bucketFooter(Button addBucket) {
        HBox footer = new HBox(16, addBucket, percentTotal);
        footer.setAlignment(Pos.CENTER_LEFT);
        return footer;
    }

    /** "% buckets: 100% of what's left after $ buckets", from the saved budget. */
    private void showPercentTotal() {
        BigDecimal total = Budget.totalPercent(service.budget().buckets());
        String text = "% buckets: " + total.stripTrailingZeros().toPlainString() + "% of what's left after $ buckets";
        if (total.compareTo(BigDecimal.valueOf(100)) < 0) {
            text += " (the rest stays as \"Left over\")";
        }
        percentTotal.setText(text);
    }

    /** "Paychecks   Show the next [3 months ▾]", the table, the totals for that period, and the note. */
    private VBox paycheckSection() {
        Label heading = new Label("Paychecks");
        heading.getStyleClass().add("heading");
        HBox header = new HBox(12, heading, new Label("Show the next"), periodBox);
        header.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, header, paycheckTable, periodTotals, leftOverNote);
    }

    /** "Next 3 months: $9,081.21 available. My spending $1,816.20 · Savings $1,816.20 · ..." */
    private void showPeriodTotals(BudgetPlan plan, List<BudgetBucket> buckets, Period period) {
        if (plan.paychecks().isEmpty()) {
            periodTotals.setText("");
            return;
        }
        StringBuilder text = new StringBuilder("Next " + period + " (" + plan.paychecks().size() + " paychecks): "
                + Format.money(plan.totalAvailable()) + " available.");
        List<String> perBucket = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            perBucket.add(buckets.get(i).name() + " " + Format.money(plan.totalForBucket(i)));
        }
        if (!perBucket.isEmpty()) {
            text.append("   ").append(String.join("  ·  ", perBucket));
        }
        periodTotals.setText(text.toString());
    }

    private void showLowBalanceWarning(BudgetPlan plan) {
        boolean warn = plan.lowBalance() != null;
        lowBalanceWarning.setVisible(warn);
        lowBalanceWarning.setManaged(warn);
        if (warn) {
            lowBalanceWarning.setText("Heads up: even before splitting anything, " + plan.account().name()
                    + " is forecast to drop to " + Format.money(plan.lowBalance().balance()) + " on "
                    + Format.date(plan.lowBalance().date()) + ", below the " + Format.money(plan.cushion())
                    + " you want to keep. Paychecks until then show $0 available.");
        }
    }
}
