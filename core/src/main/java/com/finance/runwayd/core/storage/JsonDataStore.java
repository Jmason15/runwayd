package com.finance.runwayd.core.storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finance.runwayd.core.model.FinanceData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Saves all your data in one human-readable JSON file.
 *
 * <p>You can open the file in any text editor to see exactly what's stored.
 * Dates look like "2026-10-18" and amounts like 1450.00.
 *
 * <p>Two safety nets, since this is your money data:
 * <ul>
 *   <li>Each save writes to a temporary file first, then swaps it in. If the app
 *       crashes halfway through a save, the old file is still intact.</li>
 *   <li>The previous version is kept next to it as {@code <name>.bak}.</li>
 * </ul>
 *
 * <p><b>Shared budgets.</b> The file can live in a synced folder (iCloud Drive, Dropbox, ...) that
 * another computer also writes to. So the store remembers the exact contents it last read or
 * wrote: {@link #changedSinceLastRead()} spots a change from elsewhere, and {@link #save} refuses
 * (with {@link ChangedElsewhereException}) rather than overwrite it.
 */
public final class JsonDataStore implements DataStore {

    private final Path file;
    private final ObjectMapper json;

    // The file's contents as this store last loaded or saved them (null = there was no file).
    // Only checked once the file has been loaded, so a brand-new file can always be written.
    private byte[] lastSeen;
    private boolean loaded;

    public JsonDataStore(Path file) {
        this.file = file;
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())                       // LocalDate support
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)    // "2026-10-18", not [2026,10,18]
                .enable(SerializationFeature.INDENT_OUTPUT)                 // one field per line, easy to read
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES); // old files with extra fields still load
    }

    /** Where this store reads and writes. Shown in the app so you can find and back up the file. */
    public Path file() {
        return file;
    }

    @Override
    public FinanceData load() {
        loaded = true;
        if (Files.notExists(file)) {
            lastSeen = null;
            return FinanceData.empty();
        }
        try {
            byte[] contents = Files.readAllBytes(file);
            FinanceData data = json.readValue(contents, FinanceData.class);
            lastSeen = contents;
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + file + ". It may be damaged; a backup is at " + backupFile(), e);
        }
    }

    @Override
    public void save(FinanceData data) {
        if (changedSinceLastRead()) {
            throw new ChangedElsewhereException(); // don't overwrite someone else's change
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            byte[] contents = json.writeValueAsBytes(data);
            Files.write(temp, contents);

            if (Files.exists(file)) {
                Files.copy(file, backupFile(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastSeen = contents;
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't save to " + file, e);
        }
    }

    @Override
    public boolean changedSinceLastRead() {
        if (!loaded) {
            return false;
        }
        try {
            byte[] now = Files.exists(file) ? Files.readAllBytes(file) : null;
            return !Arrays.equals(now, lastSeen);
        } catch (IOException e) {
            return false; // can't read it right now (e.g. mid-sync); check again next time
        }
    }

    private Path backupFile() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }
}
