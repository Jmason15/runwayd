package com.finance.runwayd.desktop.qa;

import com.finance.runwayd.core.FinanceService;
import com.finance.runwayd.core.demo.DemoBudget;
import com.finance.runwayd.core.model.Account;
import com.finance.runwayd.core.storage.JsonDataStore;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA: the demo budget, a whole made-up household, checked for numbers that don't add up. Unlike the
 * other QA tests it doesn't build anything; it reads a year of the real screens and cross-checks them.
 */
class DemoBudgetQaTest extends QaBase {

    @Override
    void before(FinanceService budget) {
        new JsonDataStore(dataFile).save(DemoBudget.asOf(LocalDate.now()));
    }

    @Test
    void aYearOfTheChecklistReadsAsARunningBalance() {
        openTab("Forecast");
        chooseIn(byId("checklistRange"), "1 year");
        TableView<?> checklist = byId("checklist");

        // Only cash accounts: every change to them is a line on the checklist. (A card's balance also
        // changes with interest and with payments listed under checking, so it can't be followed line by line.)
        List<String> cash = service.cashAccounts().stream().map(Account::name).toList();
        Map<String, BigDecimal> last = new HashMap<>();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (int row = 0; row < checklist.getItems().size(); row++) {
            String account = cell(checklist, row, "Account");
            String after = cell(checklist, row, "Balance after");
            if (!cash.contains(account) || after.isEmpty()) {
                continue;
            }
            BigDecimal shownAfter = amountShown(after);
            BigDecimal amount = amountShown(cell(checklist, row, "Amount"));
            BigDecimal before = last.get(account);
            if (before != null && before.add(amount).compareTo(shownAfter) != 0
                    && !cell(checklist, row, "What").contains("debt")) {
                problems.add(cell(checklist, row, "Due") + " " + cell(checklist, row, "What") + ": " + before
                        + " + " + amount + " should be " + before.add(amount) + ", shows " + shownAfter);
            }
            last.put(account, shownAfter);
            checked++;
        }
        assertTrue(checked > 50, "a year of lines, saw " + checked);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theDebtTotalsAddUpAndAgreeWithTheForecastTab() {
        openTab("Debt");
        TableView<?> debts = byId("debtTable");
        BigDecimal sum = BigDecimal.ZERO;
        for (int row = 0; row < debts.getItems().size(); row++) {
            sum = sum.add(amountShown(cell(debts, row, "Owed today")));
        }
        String total = ((Label) byId("debtTotalToday")).getText();
        assertEquals(sum, amountShown(total), "Total debt today is the sum of the rows");

        openTab("Forecast");
        String debtLine = ((Label) byId("debtLine")).getText();
        assertTrue(debtLine.startsWith("Total debt: " + total + " today"), debtLine);
    }

    @Test
    void theBillsTabNetIsTheSumOfItsGroups() {
        openTab("Bills & Income");
        Pattern perMonth = Pattern.compile("([+-]?-?\\$[\\d,]+\\.\\d\\d) / month");
        BigDecimal groups = BigDecimal.ZERO;
        int found = 0;
        for (Label label : lookup(".label").match(this::isReallyVisible).queryAllAs(Label.class)) {
            if (label.getId() != null || label.getText() == null || label.getText().startsWith("Net:")) {
                continue;
            }
            Matcher m = perMonth.matcher(label.getText());
            if (m.find()) {
                groups = groups.add(amountShown(m.group(1).replace("+", "")));
                found++;
            }
        }
        Matcher net = perMonth.matcher(((Label) byId("billNet")).getText());
        assertTrue(found > 3 && net.find(), "groups found: " + found);
        assertEquals(groups, amountShown(net.group(1).replace("+", "")), "Net / month = the groups added up");
    }

    @Test
    void everyPaycheckSplitAddsUp() {
        openTab("Budget");
        TableView<?> paychecks = byId("paychecks");
        assertTrue(paychecks.getItems().size() > 2, "the demo has paychecks");
        for (int row = 0; row < paychecks.getItems().size(); row++) {
            BigDecimal available = amountShown(cell(paychecks, row, "Available"));
            BigDecimal parts = amountShown(cell(paychecks, row, "Left over"));
            boolean inBuckets = false;
            for (var column : paychecks.getColumns()) {
                if (column.getText().equals("Left over")) {
                    break;
                }
                if (inBuckets) {
                    parts = parts.add(amountShown(cell(paychecks, row, column.getText())));
                }
                inBuckets |= column.getText().equals("Available");
            }
            assertTrue(available.signum() >= 0);
            assertTrue(parts.subtract(available).abs().compareTo(new BigDecimal("0.01")) <= 0,
                    "paycheck " + cell(paychecks, row, "Payday") + ": parts " + parts + " vs available " + available);
        }
    }
}
