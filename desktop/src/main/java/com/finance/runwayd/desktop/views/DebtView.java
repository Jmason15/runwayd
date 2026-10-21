package com.finance.runwayd.desktop.views;

import com.finance.runwayd.core.debt.DebtSummary;
import com.finance.runwayd.core.debt.DebtSummary.DebtRow;
import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.model.AccountType;
import com.finance.runwayd.core.model.PaymentRule;
import com.finance.runwayd.core.model.ScheduledItem;
import com.finance.runwayd.desktop.ui.Alerts;
import com.finance.runwayd.desktop.ui.Browser;
import com.finance.runwayd.desktop.ui.Charts;
import com.finance.runwayd.desktop.ui.Format;
import com.finance.runwayd.desktop.Refreshable;
import com.finance.runwayd.desktop.ui.Tables;
import com.finance.runwayd.desktop.dialogs.AccountDialog;
import com.finance.runwayd.desktop.dialogs.AddDebtDialog;
import com.finance.runwayd.desktop.dialogs.ItemDialog;
import com.finance.runwayd.desktop.dialogs.PaymentPlan;
import com.finance.runwayd.desktop.dialogs.PaymentSetupDialog;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.StackedAreaChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How much debt you're in, and when it'll be gone.
 *
 * <pre>
 *   Total debt today    On [date]       Debt-free     Interest this month
 *   $17,550.00          $13,600.00      Nov 2027      $139.02
 *                                                     $723.69 goes to principal
 *
 *   [Add debt]
 *   Debt      Website    Owed today  Rate    Payment           Payment / mo  From budget  Interest  Principal  ...
 *   Visa      visa.example $2,400.00  22.99%  [Card minimum ▾]  $69.98        $0.00       $45.98   $24.00
 *
 *   ┌ Chart: total debt over time ┐
 * </pre>
 *
 * <p>Debts are accounts of type Credit card or Loan. A bill becomes a payment toward
 * a debt when its "Paid into" is that debt. Each payment then lowers the balance
 * and stops once it reaches zero.
 */
public final class DebtView extends ScrollPane implements Refreshable {

    private static final List<AccountType> DEBT_TYPES = List.of(AccountType.CREDIT_CARD, AccountType.LOAN);

    private final FinanceService service;

    private final DatePicker datePicker = new DatePicker(LocalDate.now().plusYears(1));
    private final Label totalTodayLabel = statValue();
    private final Label totalOnDateLabel = statValue();
    private final Label debtFreeLabel = statValue();
    private final Label interestLabel = statValue();
    private final Label principalLabel = new Label();
    private final TableView<DebtRow> table = Tables.create("No debts yet. Click \"Add debt\" to enter a credit card or loan.");
    private final StackedAreaChart<Number, Number> chart = Charts.stackedDateChart();
    private final CheckBox rollOver = new CheckBox("Roll paid-off payments into the next debt");

    // Which debt to pay first, and what the "From budget" column means.
    private final Label advice = new Label();

    public DebtView(FinanceService service) {
        this.service = service;

        buildTable();
        chart.setTitle("Your debt until it's paid off");

        Button add = new Button("Add debt");
        add.getStyleClass().add("primary");
        add.setOnAction(event -> add());

        Label hint = new Label("Tip: click an amount under \"Owed today\" to type in your latest statement balance. "
                + "Right-click a debt to open its website or link one of your bills as its payment.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        advice.getStyleClass().add("hint");
        advice.setWrapText(true);

        // "Add debt" on the left, the snowball rollover switch on the right.
        rollOver.setTooltip(new Tooltip("When a debt is paid off, keep paying its usual amount, "
                + "to the highest-rate debt still owed."));
        rollOver.setOnAction(event -> {
            service.setRollPaidOffPayments(rollOver.isSelected());
            refresh();
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox actions = new HBox(8, add, gap, rollOver);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox page = new VBox(14, buildStats(), actions, table, hint, advice, chart);
        page.setPadding(new Insets(16));
        setContent(page);
        setFitToWidth(true);

        datePicker.valueProperty().addListener((obs, oldDate, newDate) -> refresh());
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    /** The three big numbers across the top. */
    private HBox buildStats() {
        HBox stats = new HBox(16,
                statCard(new Label("Total debt today"), totalTodayLabel),
                statCard(new HBox(8, new Label("On"), datePicker), totalOnDateLabel),
                statCard(new Label("Debt-free"), debtFreeLabel),
                statCard(new Label("Interest this month"), interestLabel, principalLabel));
        stats.getChildren().forEach(card -> HBox.setHgrow(card, Priority.ALWAYS));
        return stats;
    }

    /** A white box: a small title, a big number, and optionally a line of detail under it. */
    private static VBox statCard(Node title, Label value, Label... details) {
        if (title instanceof HBox row) {
            row.setAlignment(Pos.CENTER_LEFT);
        }
        title.getStyleClass().add("stat-label");
        VBox card = new VBox(6, title, value);
        for (Label detail : details) {
            detail.getStyleClass().add("stat-label");
            card.getChildren().add(detail);
        }
        card.getStyleClass().add("stat-card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static Label statValue() {
        Label label = new Label();
        label.getStyleClass().add("stat-value");
        label.setWrapText(true); // long text like "Not with current payments" wraps instead of being cut off
        return label;
    }

    private void buildTable() {
        table.setEditable(true); // for the click-to-edit "Owed today" column
        Tables.sizeToRows(table, 12);

        table.getColumns().add(Tables.text("Debt", row -> row.account().name()));
        table.getColumns().add(Tables.link("Website", row -> row.account().website()));
        table.getColumns().add(Tables.editableMoney("Owed today", DebtRow::owedToday, this::saveStatementBalance));
        table.getColumns().add(Tables.text("Rate", DebtView::rateText));
        table.getColumns().add(paymentColumn());
        table.getColumns().add(Tables.money("Payment / mo", DebtRow::monthlyPayment));
        // The part of "Payment / mo" that comes from the Budget tab's debt bucket.
        table.getColumns().add(Tables.money("From budget", DebtRow::budgetPerMonth));
        // How this month's payment splits: interest charged, and what actually lowers the debt.
        // Principal shows red when the payment doesn't even cover the interest.
        table.getColumns().add(Tables.money("Interest / mo", DebtRow::interestPerMonth));
        table.getColumns().add(Tables.money("Principal / mo", DebtRow::principalPerMonth));
        table.getColumns().add(Tables.text("Paid off", row -> row.payoffDate() == null
                ? "Not paid off" : Format.date(row.payoffDate())));
        table.getColumns().add(Tables.money("Total interest", DebtRow::interestUntilPaidOff));
        table.getColumns().add(Tables.money("Owed on date", DebtRow::owedOnDate));

        table.setRowFactory(t -> debtRow());
    }

    /**
     * The Payment column: a drop-down on each row to switch between the card minimum and
     * a fixed amount in one click. Debts without a payment get a "Set up…" link instead.
     */
    private TableColumn<DebtRow, DebtRow> paymentColumn() {
        TableColumn<DebtRow, DebtRow> column = new TableColumn<>("Payment");
        column.setEditable(false); // the drop-down does the changing, not table editing
        column.setPrefWidth(150);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            private final ComboBox<PaymentRule> choice = new ComboBox<>();
            private boolean filling; // true while we set the value ourselves, so it isn't treated as a change
            // Shown instead of the drop-down when the debt has no payment yet.
            private final Hyperlink setUpLink = new Hyperlink("Set up…");

            {
                setUpLink.setOnAction(event -> setUpPayment(getItem()));
                choice.getItems().setAll(PaymentRule.values());
                choice.setMaxWidth(Double.MAX_VALUE);
                choice.setOnAction(event -> {
                    DebtRow row = getItem();
                    if (!filling && row != null && row.payment() != null && choice.getValue() != null) {
                        service.setPaymentRule(row.payment().id(), choice.getValue());
                        // Refresh after this click finishes; rebuilding the table mid-click can upset JavaFX.
                        Platform.runLater(DebtView.this::refresh);
                    }
                });
            }

            @Override
            protected void updateItem(DebtRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setGraphic(null);
                } else if (row.payment() == null) {
                    setText(null);
                    setGraphic(setUpLink);
                } else {
                    filling = true;
                    choice.setValue(row.payment().paymentRule());
                    filling = false;
                    setText(null);
                    setGraphic(choice);
                }
            }
        });
        return column;
    }

    /** Double-click to edit; right-click for Open website / Edit / Link a bill / Delete. */
    private TableRow<DebtRow> debtRow() {
        TableRow<DebtRow> row = new TableRow<>();
        row.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !row.isEmpty() && table.getEditingCell() == null) {
                edit(row.getItem().account());
            }
        });

        MenuItem openSite = new MenuItem("Open website");
        MenuItem edit = new MenuItem("Edit…");
        MenuItem editPayment = new MenuItem("Edit payment…");
        MenuItem link = new MenuItem("Link a bill as its payment…");
        MenuItem delete = new MenuItem("Delete");
        openSite.setOnAction(event -> Browser.open(row.getItem().account().website()));
        edit.setOnAction(event -> edit(row.getItem().account()));
        editPayment.setOnAction(event -> editPayment(row.getItem()));
        link.setOnAction(event -> linkBill(row.getItem().account()));
        delete.setOnAction(event -> delete(row.getItem().account()));
        ContextMenu menu = new ContextMenu(openSite, edit, editPayment, link, new SeparatorMenuItem(), delete);
        row.emptyProperty().addListener((obs, wasEmpty, isEmpty) -> row.setContextMenu(isEmpty ? null : menu));
        // Grey out what doesn't apply: no website to open, or no payment to edit yet.
        row.itemProperty().addListener((obs, oldRow, newRow) -> {
            openSite.setDisable(newRow == null || newRow.account().website() == null);
            editPayment.setDisable(newRow == null || newRow.payment() == null);
        });
        return row;
    }

    // -------------------------------------------------------------------------
    // Filling in the data
    // -------------------------------------------------------------------------

    @Override
    public void refresh() {
        rollOver.setSelected(service.rollsPaidOffPayments());
        LocalDate date = datePicker.getValue() != null ? datePicker.getValue() : LocalDate.now();
        DebtSummary summary = service.debtSummary(date);

        totalTodayLabel.setText(Format.money(summary.totalToday()));
        totalOnDateLabel.setText(Format.money(summary.totalOnDate()));
        interestLabel.setText(Format.money(summary.interestPerMonth()));
        principalLabel.setText(Format.money(summary.principalPerMonth()) + " of your payments goes to principal");
        if (summary.debts().isEmpty()) {
            debtFreeLabel.setText("—");
        } else if (summary.debtFreeDate() == null) {
            debtFreeLabel.setText("Not with current payments");
        } else {
            debtFreeLabel.setText(Format.monthYear(summary.debtFreeDate()));
        }

        table.getItems().setAll(summary.debts());
        showAdvice(summary);

        showDebtChart(summary);
    }

    /**
     * One colored band per debt, stacked, from today until you're debt-free. The debts that
     * take longest go at the bottom, so bands drop off the top in the order they're paid off.
     * The top edge of the pile is your total debt.
     */
    private void showDebtChart(DebtSummary summary) {
        List<DebtRow> bottomToTop = summary.debts().stream()
                // Never paid off (no date) at the very bottom, then latest payoff to earliest.
                .sorted(Comparator.comparing(DebtRow::payoffDate,
                        Comparator.nullsFirst(Comparator.<LocalDate>reverseOrder())))
                .toList();

        List<XYChart.Series<Number, Number>> bands = new ArrayList<>();
        for (DebtRow row : bottomToTop) {
            XYChart.Series<Number, Number> band = new XYChart.Series<>();
            band.setName(row.account().name());
            for (DebtSummary.MonthOfDebt month : summary.overTime()) {
                band.getData().add(Charts.point(month.date(), month.owed(row.account().id())));
            }
            bands.add(band);
        }
        chart.getData().setAll(bands);
        chart.setTitle(summary.debtFreeDate() == null
                ? "Your debt over the next 30 years (some debts never get paid off with current payments)"
                : "Your debt until it's all paid off (" + Format.monthYear(summary.debtFreeDate()) + ")");
    }

    /**
     * The line under the table: which debt has the highest rate (pay that one first; point the
     * Budget tab's debt bucket at it), and what the "From budget" column means.
     */
    private void showAdvice(DebtSummary summary) {
        StringBuilder text = new StringBuilder();
        summary.debts().stream()
                .filter(row -> row.owedToday().signum() > 0)
                .map(DebtRow::account)
                .max(Comparator.comparing(Account::interestRatePercent))
                .ifPresent(highest -> text.append("Highest interest: ").append(highest.name()).append(" (")
                        .append(highest.interestRatePercent().stripTrailingZeros().toPlainString())
                        .append("%). Paying it first saves the most interest; to pay more toward it, ")
                        .append("use the debt bucket on the Budget tab. "));
        // Explain the "From budget" column when the Budget tab is paying any debt.
        boolean budgetPaysDebt = summary.debts().stream().anyMatch(row -> row.budgetPerMonth().signum() > 0);
        if (budgetPaysDebt) {
            text.append("\"From budget\" is about how much a month your Budget tab adds to a debt while it's "
                    + "paying it; it's included in \"Payment / mo\" and the payoff dates.");
        }
        advice.setText(text.toString().trim());
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    /**
     * Saves the new debt, then sets up its payment: either an existing bill pointed at
     * the debt, or a brand-new monthly payment. Both use the "Pay each month" rule.
     */
    private void add() {
        new AddDebtDialog(plainBills(), service.cashAccounts(), service::loanPayment).showAndWait().ifPresent(result -> {
            Account debt = service.addAccount(result.debt());
            if (result.billToLink() != null) {
                ScheduledItem linked = service.linkBillToDebt(result.billToLink().id(), debt.id());
                service.setPaymentRule(linked.id(), result.rule());
            }
            if (result.newPayment() != null) {
                createPayment(debt, result.newPayment());
            }
            refresh();
        });
    }

    /** "Set up…" on a debt with no payment yet: same boxes as Add debt. */
    private void setUpPayment(DebtRow row) {
        new PaymentSetupDialog(row.account(), row.owedToday(), service.cashAccounts(), service::loanPayment)
                .showAndWait()
                .ifPresent(plan -> {
                    createPayment(row.account(), plan);
                    refresh();
                });
    }

    private void createPayment(Account debt, PaymentPlan plan) {
        service.createPayment(debt.id(), plan.rule(), plan.fixedAmount(), plan.firstDate(), plan.paidFrom().id());
    }

    /** Opens the bill editor for this debt's payment: switch fixed / card minimum, change the date, and so on. */
    private void editPayment(DebtRow row) {
        new ItemDialog(row.payment(), service.accounts()).showAndWait().ifPresent(changed -> {
            service.updateItem(changed);
            refresh();
        });
    }

    private void edit(Account debt) {
        new AccountDialog(debt, DEBT_TYPES).showAndWait().ifPresent(changed -> {
            service.updateAccount(changed);
            refresh();
        });
    }

    /** Pick one of your bills (e.g. "Store Card") to become this debt's payment. */
    private void linkBill(Account debt) {
        List<ScheduledItem> bills = plainBills();
        if (bills.isEmpty()) {
            Alerts.info("There are no plain bills to link. Every bill already pays something else.");
            return;
        }
        // The picker shows text, so label each bill and map the chosen label back to the bill.
        Map<String, ScheduledItem> byLabel = new LinkedHashMap<>();
        bills.forEach(bill -> byLabel.put(bill.name() + "  (" + Format.money(bill.amount()) + ")", bill));
        ChoiceDialog<String> picker = new ChoiceDialog<>(byLabel.keySet().iterator().next(), byLabel.keySet());
        picker.setTitle("Link a bill to " + debt.name());
        picker.setHeaderText("Which bill pays " + debt.name() + "? Each payment will lower what you owe.");
        picker.setContentText("Bill:");
        picker.showAndWait().map(byLabel::get).ifPresent(bill -> {
            service.linkBillToDebt(bill.id(), debt.id());
            refresh();
        });
    }

    private void delete(Account debt) {
        // FinanceService refuses if a payment still points at this debt; App shows its message as a pop-up.
        if (Alerts.confirm("Delete \"" + debt.name() + "\"?")) {
            service.deleteAccount(debt.id());
            refresh();
        }
    }

    /** Clicked into "Owed today" and typed the balance from a statement. */
    private void saveStatementBalance(DebtRow row, BigDecimal owed) {
        service.updateBalance(row.account().id(), owed.abs(), LocalDate.now());
        refresh();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Bills that just leave an account (not income, not already a transfer or payment). */
    private List<ScheduledItem> plainBills() {
        return service.items().stream()
                .filter(item -> item.kind() == ScheduledItem.Kind.BILL)
                .toList();
    }

    private static String rateText(DebtRow row) {
        BigDecimal rate = row.account().interestRatePercent();
        return rate.signum() == 0 ? "" : rate.stripTrailingZeros().toPlainString() + "%";
    }
}
