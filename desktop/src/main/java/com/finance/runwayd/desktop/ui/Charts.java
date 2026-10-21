package com.finance.runwayd.desktop.ui;

import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedAreaChart;
import javafx.scene.chart.XYChart;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Line charts of money over time, shared by the Forecast and Debt tabs.
 *
 * <p>JavaFX charts only understand numbers, so dates are stored as day numbers
 * (days since 1970) and turned back into "Oct 21" labels for display.
 */
public final class Charts {

    private Charts() {
    }

    /** A value on a date, e.g. "owed $5,000 on Nov 1". */
    public record Step(LocalDate date, BigDecimal value) {
    }

    /** An empty line chart with dates along the bottom, ready for {@link #staircase}. */
    public static LineChart<Number, Number> dateChart() {
        LineChart<Number, Number> chart = new LineChart<>(dateAxis(), new NumberAxis());
        chart.setLegendVisible(false);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setPrefHeight(220);
        chart.setMinHeight(180);
        return chart;
    }

    /**
     * An empty stacked-area chart with dates along the bottom: one colored band per series,
     * stacked on top of each other, with a legend naming them. The top edge is the total.
     */
    public static StackedAreaChart<Number, Number> stackedDateChart() {
        StackedAreaChart<Number, Number> chart = new StackedAreaChart<>(dateAxis(), new NumberAxis());
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setPrefHeight(320);
        chart.setMinHeight(240);
        return chart;
    }

    /** A value on a date, e.g. what one debt owes on the 1st of a month. */
    public static XYChart.Data<Number, Number> point(LocalDate date, BigDecimal value) {
        return new XYChart.Data<>(date.toEpochDay(), value.doubleValue());
    }

    /**
     * The bottom axis: dates stored as day numbers, labelled to suit how much time is shown.
     * Up to about a year: "Oct 21". Up to about 6 years: "Oct 2026". Longer: just "2026" (ticks are
     * then a year or more apart, so years don't repeat).
     */
    private static NumberAxis dateAxis() {
        NumberAxis dateAxis = new NumberAxis();
        dateAxis.setForceZeroInRange(false);
        dateAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number epochDay) {
                LocalDate date = LocalDate.ofEpochDay(epochDay.longValue());
                double daysShown = dateAxis.getUpperBound() - dateAxis.getLowerBound();
                if (daysShown <= 400) {
                    return Format.monthDay(date);
                }
                return daysShown <= 6 * 366 ? Format.monthYear(date) : String.valueOf(date.getYear());
            }

            @Override
            public Number fromString(String text) {
                throw new UnsupportedOperationException("Chart labels are display-only.");
            }
        });
        return dateAxis;
    }

    /**
     * Builds a "staircase" line: the value stays flat between changes, then jumps,
     * which is how a bank balance really behaves. The line is carried across to
     * {@code end} so it reaches the right edge.
     *
     * @param steps the starting value, then the value after each change, oldest first
     */
    public static XYChart.Series<Number, Number> staircase(List<Step> steps, LocalDate end) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        if (steps.isEmpty()) {
            return series;
        }
        BigDecimal current = steps.get(0).value();
        series.getData().add(point(steps.get(0).date(), current));

        for (Step step : steps.subList(1, steps.size())) {
            series.getData().add(point(step.date(), current)); // flat until this day...
            current = step.value();
            series.getData().add(point(step.date(), current)); // ...then the jump
        }
        LocalDate lastDate = steps.get(steps.size() - 1).date();
        if (end.isAfter(lastDate)) {
            series.getData().add(point(end, current));
        }
        return series;
    }
}
