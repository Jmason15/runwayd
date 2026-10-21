package com.finance.runwayd.desktop;

/**
 * A screen that can reload what it shows from the FinanceService.
 *
 * <p>MainWindow calls {@link #refresh()} whenever you switch to a tab, so a bill
 * added on the Bills tab shows up on the Forecast tab right away.
 */
public interface Refreshable {

    void refresh();
}
