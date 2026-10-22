package com.finance.runwayd.desktop.qa;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA: the Budget tab. Every paycheck's split must add up (buckets + left over = available), the
 * buckets must follow what was typed, and with the splits in the forecast the account must never
 * dip below the amount to keep.
 */
class BudgetQaTest extends QaBase {

    private static final BigDecimal CENT = new BigDecimal("0.01");
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void payAndRent() {
        addAccount("Checking", "1000");
        addItem("Income", "Pay", "2000", "Every 2 weeks", today.plusDays(2), "Checking");
        addBill("Rent", "1500", "Monthly", today.plusDays(10));
    }

    @Test
    void eachSplitAddsUpAndFollowsTheBuckets() {
        openTab("Budget");
        typeAndEnter(byId("cushion"), "500");
        typeAndEnter(bucketValue("My spending"), "200");
        typeAndEnter(bucketValue("Savings"), "50");
        assertEquals("", ((Label) byId("budgetError")).getText());

        TableView<?> paychecks = byId("paychecks");
        assertFalse(paychecks.getItems().isEmpty(), "paychecks are listed");
        for (int row = 0; row < paychecks.getItems().size(); row++) {
            BigDecimal available = amountShown(cell(paychecks, row, "Available"));
            BigDecimal mine = amountShown(cell(paychecks, row, "My spending"));
            BigDecimal savings = amountShown(cell(paychecks, row, "Savings"));
            BigDecimal leftOver = amountShown(cell(paychecks, row, "Left over"));
            BigDecimal everyBucket = sumOfBuckets(paychecks, row);
            String where = "paycheck on " + cell(paychecks, row, "Payday");

            assertTrue(available.signum() >= 0, where + ": nothing negative to hand out");
            assertTrue(everyBucket.add(leftOver).subtract(available).abs().compareTo(CENT) <= 0,
                    where + ": buckets " + everyBucket + " + left over " + leftOver + " = available " + available);
            // $200 first (or all of it if there's less), then half of what's left goes to Savings.
            BigDecimal expectedMine = available.min(new BigDecimal("200"));
            assertEquals(0, expectedMine.compareTo(mine), where + ": My spending");
            BigDecimal expectedSavings = available.subtract(mine).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            assertTrue(expectedSavings.subtract(savings).abs().compareTo(CENT) <= 0,
                    where + ": Savings should be half of " + available.subtract(mine) + ", was " + savings);
        }

        // With the splits taken out on payday, Checking never goes below the $500 to keep.
        showBalancesOn(today.plusYears(1));
        String note = forecastCell("Checking", "Watch out for");
        assertTrue(note.startsWith("Lowest: "), note);
        BigDecimal lowest = amountShown(note.substring("Lowest: ".length(), note.indexOf(" on ")));
        assertTrue(lowest.compareTo(new BigDecimal("500")) >= 0, "lowest " + lowest + " must stay at or above $500");
    }

    @Test
    void aBadBucketAmountShowsAMessageAndIsntSaved() {
        openTab("Budget");
        typeAndEnter(bucketValue("Savings"), "lots");
        assertFalse(((Label) byId("budgetError")).getText().isEmpty(), "\"lots\" isn't a number");
        assertEquals(0, reopened().budget().buckets().stream()
                .filter(b -> b.name().equals("Savings")).findFirst().orElseThrow().value().signum(), "not saved");

        typeAndEnter(bucketValue("Savings"), "10");
        assertEquals("", ((Label) byId("budgetError")).getText(), "fixed: the message goes away");
    }

    // ---- helpers ----

    private void typeAndEnter(TextField field, String text) {
        typeInto(field, text);
        key(KeyCode.ENTER);
    }

    /** The amount box on the bucket line named {@code name}. */
    private TextField bucketValue(String name) {
        GridPane grid = byId("buckets");
        Integer row = grid.getChildren().stream()
                .filter(n -> n instanceof TextField tf && name.equals(tf.getText()))
                .map(GridPane::getRowIndex).findFirst()
                .orElseThrow(() -> new AssertionError("No bucket named " + name));
        return (TextField) grid.getChildren().stream()
                .filter(n -> Objects.equals(GridPane.getRowIndex(n), row) && Objects.equals(GridPane.getColumnIndex(n), 1))
                .findFirst().orElseThrow();
    }

    /** Every bucket column on one paycheck row (everything between "Available" and "Left over"). */
    private BigDecimal sumOfBuckets(TableView<?> paychecks, int row) {
        BigDecimal sum = BigDecimal.ZERO;
        boolean inBuckets = false;
        for (var column : paychecks.getColumns()) {
            String title = column.getText();
            if (title.equals("Left over")) {
                break;
            }
            if (inBuckets) {
                sum = sum.add(amountShown(cell(paychecks, row, title)));
            }
            if (title.equals("Available")) {
                inBuckets = true;
            }
        }
        return sum;
    }
}
