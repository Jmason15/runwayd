package com.finance.runwayd.core.storage;

/**
 * A save was stopped because the data file changed since it was last read, e.g. your partner saved
 * the shared budget on their Mac and it synced over. Saving anyway would wipe out their change, so
 * nothing is written; the app reloads the file and asks you to make your change again.
 */
public final class ChangedElsewhereException extends IllegalStateException {

    public ChangedElsewhereException() {
        super("This budget was just changed on another computer, so your last change wasn't saved "
                + "(saving it would have overwritten theirs). Their version is showing now; "
                + "please make your change again.");
    }
}
