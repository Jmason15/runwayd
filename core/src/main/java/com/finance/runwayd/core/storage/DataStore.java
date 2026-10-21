package com.finance.runwayd.core.storage;

import com.finance.runwayd.core.model.FinanceData;

/**
 * Somewhere your data is kept between runs of the app.
 *
 * <p>Today the only implementation is {@link JsonDataStore}. Keeping this as an
 * interface means the rest of the code doesn't care HOW data is saved, so swapping
 * in a database later wouldn't touch the forecast math or the screens.
 */
public interface DataStore {

    /** Reads everything. Returns empty data (not an error) the very first time, before anything is saved. */
    FinanceData load();

    /**
     * Replaces everything that was saved with {@code data}.
     *
     * @throws ChangedElsewhereException if the saved data changed since it was last loaded or saved
     *         (a shared budget edited on another computer); nothing is written then
     */
    void save(FinanceData data);

    /**
     * True if the saved data changed since this store last loaded or saved it, e.g. a shared
     * budget saved on another computer. Then {@link #load()} gets the new version.
     */
    default boolean changedSinceLastRead() {
        return false;
    }
}
