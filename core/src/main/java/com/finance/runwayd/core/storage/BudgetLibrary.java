package com.finance.runwayd.core.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finance.runwayd.core.demo.DemoBudget;
import com.finance.runwayd.core.model.FinanceData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Your budgets. Each budget is a complete, separate set of data (accounts, bills, debts and
 * paycheck split) saved as its own JSON file in one folder:
 * <pre>
 *   ~/runwayd/
 *     budgets/
 *       My budget.json
 *       Our household.json
 *       Deleted/                    deleted budgets go here instead of disappearing
 *     settings.json                 remembers which budget was open last
 *     treasuretracker.json          the single file from before there were budgets (left as a backup)
 * </pre>
 *
 * <p>The app used to be called Paydar (folder ~/Paydar), and before that TreasureTracker (folder
 * ~/TreasureTracker). The first time runwayd starts, it copies whichever of those it finds here
 * ({@link #copyOldFolderIfNew}); the old folder stays put as a backup.
 *
 * <p>Two ways to share a budget:
 * <ul>
 *   <li><b>A copy:</b> {@link #exportTo} writes a copy anywhere (e.g. the Desktop, to AirDrop), and
 *       {@link #importFrom} adds a copy someone sent you. After that the two copies are separate.</li>
 *   <li><b>One budget for both:</b> {@link #share} moves the file into a synced folder (iCloud Drive,
 *       Dropbox, ...) and {@link #openShared} adds that same file on the other computer. Both open the
 *       one file; settings.json remembers where each shared budget lives.</li>
 * </ul>
 *
 * <p>This class only handles the files. What's inside one is read and written by
 * {@link JsonDataStore}, through FinanceService.
 */
public final class BudgetLibrary {

    /** The name the first budget gets, e.g. your data from before there were budgets. */
    public static final String FIRST_BUDGET_NAME = "My budget";

    /** The ending of an exported file, e.g. "Our household.runwayd.json", so it's easy to recognize. */
    public static final String EXPORT_ENDING = ".runwayd.json";
    /** What exported files ended in under the app's old names (Paydar, TreasureTracker). They still import. */
    private static final List<String> OLD_EXPORT_ENDINGS = List.of(".paydar.json", ".treasuretracker.json");

    private static final String ENDING = ".json";
    private static final int LONGEST_NAME = 60;

    private final Path home;
    private final ObjectMapper json = new ObjectMapper();

    /** @param home the app's folder, normally ~/runwayd (a temporary folder in tests) */
    public BudgetLibrary(Path home) {
        this.home = home;
    }

    /**
     * The library in your home folder, ~/runwayd. If that doesn't exist yet, your data is copied
     * over from the app's old folder first: ~/Paydar, or else ~/TreasureTracker.
     */
    public static BudgetLibrary inHomeFolder() {
        String userHome = System.getProperty("user.home");
        BudgetLibrary library = new BudgetLibrary(Path.of(userHome, "runwayd"));
        for (String oldName : List.of("Paydar", "TreasureTracker")) {
            if (library.copyOldFolderIfNew(Path.of(userHome, oldName))) {
                break; // the newest old folder wins
            }
        }
        return library;
    }

    /**
     * First run after the rename: if this library's folder doesn't exist yet and {@code oldHome}
     * does, copies everything in {@code oldHome} (budgets, settings, the old single file) here.
     * The old folder isn't touched, so it stays as a backup. Does nothing once the folder exists.
     *
     * @return true if the old folder was copied
     */
    public boolean copyOldFolderIfNew(Path oldHome) {
        if (Files.exists(home) || !Files.isDirectory(oldHome)) {
            return false;
        }
        try (Stream<Path> everything = Files.walk(oldHome)) {
            for (Path from : everything.toList()) {
                Path to = home.resolve(oldHome.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't copy your data from " + oldHome + " to " + home, e);
        }
    }

    /** The folder the budget files are in. */
    public Path folder() {
        return home.resolve("budgets");
    }

    /** Every budget's name, A to Z: the ones in your budgets folder and the shared ones. */
    public List<String> names() {
        List<String> all = new ArrayList<>(localNames());
        all.addAll(sharedBudgets().keySet());
        all.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(all);
    }

    /** The budgets in your own budgets folder (not shared ones), A to Z. */
    private List<String> localNames() {
        if (Files.notExists(folder())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder())) {
            return files
                    .filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(ENDING) && !fileName.startsWith("."))
                    .map(fileName -> fileName.substring(0, fileName.length() - ENDING.length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't list the budgets in " + folder(), e);
        }
    }

    /**
     * Which budget to open when the app starts: the one open last time, or else the first.
     * On the very first run it makes sure there is one: your data from before there were budgets
     * (treasuretracker.json in this folder) if there is any, or else an empty budget. Either way
     * it's called "My budget". The old file is copied, not moved, so it stays as a backup.
     */
    public String startingBudget() {
        if (names().isEmpty()) {
            Path oldSingleFile = home.resolve("treasuretracker.json");
            if (Files.exists(oldSingleFile)) {
                copy(oldSingleFile, fileFor(FIRST_BUDGET_NAME));
            } else {
                create(FIRST_BUDGET_NAME);
            }
        }
        // The one open last time, unless it's a shared budget whose folder can't be reached right now
        // (e.g. iCloud Drive or Dropbox isn't running); then the first one that can be opened.
        String last = readLastOpened();
        List<String> reachable = names().stream().filter(this::canOpen).toList();
        if (reachable.isEmpty()) {
            return create(uniqueName(FIRST_BUDGET_NAME));
        }
        return reachable.stream().filter(name -> name.equalsIgnoreCase(last)).findFirst()
                .orElse(reachable.get(0));
    }

    /** The store for budget {@code name}, and remembers it as the one to open next time. */
    public JsonDataStore open(String name) {
        Path file = existingFile(name);
        writeLastOpened(name);
        return new JsonDataStore(file);
    }

    /** Makes a new, empty budget. Returns its name, tidied up. */
    public String create(String name) {
        String tidy = checkNewName(name);
        new JsonDataStore(fileFor(tidy)).save(FinanceData.empty());
        return tidy;
    }

    /**
     * Adds the demo budget (a made-up household, see {@link DemoBudget}) dated from {@code today}.
     * Called "Demo", or "Demo (2)" if that's taken. Your own budgets aren't touched.
     *
     * @return its name
     */
    public String addDemo(LocalDate today) {
        String name = uniqueName(DemoBudget.NAME);
        new JsonDataStore(fileFor(name)).save(DemoBudget.asOf(today));
        return name;
    }

    /** True for a demo budget made by {@link #addDemo}: "Demo", or "Demo (2)" and so on. */
    public boolean isDemo(String name) {
        return name != null && !isShared(name)
                && name.matches("(?i)" + DemoBudget.NAME + "( \\(\\d+\\))?");
    }

    /** Makes a copy of budget {@code from}, with everything in it, called {@code newName}. */
    public String duplicate(String from, String newName) {
        String tidy = checkNewName(newName);
        copy(existingFile(from), fileFor(tidy));
        return tidy;
    }

    /** Renames a budget. Its data doesn't change. */
    public String rename(String from, String newName) {
        Path file = existingFile(from);
        String tidy = newName == null ? "" : newName.trim();
        if (!tidy.equalsIgnoreCase(from)) {
            checkNewName(tidy);
        } else {
            checkNameRules(tidy); // same name, different capitals: still has to be a valid name
        }
        Optional<String> sharedName = sharedName(from);
        if (sharedName.isPresent()) {
            // A shared file keeps its name in the shared folder (the other computer uses it);
            // only the name on your list changes.
            Map<String, Path> shared = sharedBudgets();
            Path location = shared.remove(sharedName.get());
            shared.put(tidy, location);
            writeSharedBudgets(shared);
            if (from.equalsIgnoreCase(readLastOpened())) {
                writeLastOpened(tidy);
            }
            return tidy;
        }
        try {
            // Via a temporary name, so changing only capitals works on Macs (whose file names ignore case).
            Path temp = file.resolveSibling(".renaming-" + System.nanoTime());
            Files.move(file, temp);
            Files.move(temp, fileFor(tidy));
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't rename \"" + from + "\".", e);
        }
        if (from.equalsIgnoreCase(readLastOpened())) {
            writeLastOpened(tidy);
        }
        return tidy;
    }

    /**
     * Deletes a budget by moving its file into the "Deleted" folder, with the date and time added
     * to its name, so it can still be recovered by hand. Your only budget can't be deleted.
     */
    public void delete(String name) {
        if (names().size() == 1) {
            throw new IllegalStateException("\"" + name + "\" is your only budget, so it can't be deleted. "
                    + "Make another one first.");
        }
        Optional<String> sharedName = sharedName(name);
        if (sharedName.isPresent()) {
            // Someone else uses the shared file, so it stays; it just leaves your list.
            Map<String, Path> shared = sharedBudgets();
            shared.remove(sharedName.get());
            writeSharedBudgets(shared);
            return;
        }
        Path file = existingFile(name);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"));
        Path deleted = folder().resolve("Deleted").resolve(name + " (deleted " + stamp + ")" + ENDING);
        try {
            Files.createDirectories(deleted.getParent());
            Files.move(file, deleted);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't delete \"" + name + "\".", e);
        }
    }

    /** Saves a copy of budget {@code name} to {@code target}, e.g. to AirDrop to someone. */
    public void exportTo(String name, Path target) {
        try {
            Files.copy(existingFile(name), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't save a copy to " + target, e);
        }
    }

    /**
     * Adds a budget someone sent you (a file made by {@link #exportTo}). It's named after the file,
     * e.g. "Our household.runwayd.json" becomes "Our household" (so do the older ".paydar.json" and
     * ".treasuretracker.json"). If that name is taken, it
     * becomes "Our household (2)". Nothing you already have is replaced.
     *
     * @return the new budget's name
     */
    public String importFrom(Path source) {
        checkIsBudgetFile(source);
        String fileName = source.getFileName().toString();
        // Drop the longest ending it has: ".runwayd.json", an older app name's, or just ".json".
        List<String> endings = new ArrayList<>();
        endings.add(EXPORT_ENDING);
        endings.addAll(OLD_EXPORT_ENDINGS);
        endings.add(ENDING);
        String base = fileName;
        for (String ending : endings) {
            if (fileName.toLowerCase(Locale.ROOT).endsWith(ending)) {
                base = fileName.substring(0, fileName.length() - ending.length());
                break;
            }
        }
        base = base.replaceAll("[/:\\\\]", "-").trim();
        if (base.isEmpty() || base.startsWith(".")) {
            base = "Imported budget";
        }
        if (base.length() > LONGEST_NAME) {
            base = base.substring(0, LONGEST_NAME).trim();
        }
        String name = uniqueName(base);
        copy(source, fileFor(name));
        return name;
    }

    // -------------------------------------------------------------------------
    // Shared budgets: one file in a synced folder (iCloud Drive, Dropbox, ...) that two computers
    // both open. Each computer remembers where its shared budgets live in settings.json.
    // -------------------------------------------------------------------------

    /** True if {@code name} is a shared budget (its file lives in a shared folder). */
    public boolean isShared(String name) {
        return sharedName(name).isPresent();
    }

    /** Where a shared budget's file lives, e.g. iCloud Drive/runwayd/Household.json. */
    public Optional<Path> sharedLocation(String name) {
        return sharedName(name).map(shared -> sharedBudgets().get(shared));
    }

    /**
     * Moves budget {@code name} into {@code folder} (a synced folder like iCloud Drive or Dropbox)
     * so another computer can open it too. Its name and data stay the same.
     *
     * @throws IllegalArgumentException if it's already shared, or that folder already has a file by
     *         that name (then use {@link #openShared} on that file instead)
     */
    public void share(String name, Path folder) {
        if (isShared(name)) {
            throw new IllegalArgumentException("\"" + name + "\" is already shared.");
        }
        Path from = existingFile(name);
        Path to = folder.resolve(from.getFileName());
        if (Files.exists(to)) {
            throw new IllegalArgumentException("That folder already has a file called " + to.getFileName()
                    + ". To use it, choose \"Open a shared budget\" instead.");
        }
        try {
            Files.createDirectories(folder);
            Files.move(from, to); // moved, not copied: there's one copy of a shared budget
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't move \"" + name + "\" to " + folder, e);
        }
        String listed = from.getFileName().toString();
        listed = listed.substring(0, listed.length() - ENDING.length());
        Map<String, Path> shared = sharedBudgets();
        shared.put(listed, to);
        writeSharedBudgets(shared);
    }

    /**
     * Adds a budget someone shared (its file in your synced folder) to your list, without copying it:
     * both computers then open the same file. Named after the file; if you already have a budget by
     * that name it becomes e.g. "Household (2)". If it's already on your list, that name is returned.
     *
     * @return the name it's listed under
     */
    public String openShared(Path file) {
        checkIsBudgetFile(file);
        Path location = file.toAbsolutePath().normalize();
        for (Map.Entry<String, Path> entry : sharedBudgets().entrySet()) {
            if (entry.getValue().toAbsolutePath().normalize().equals(location)) {
                return entry.getKey(); // already on the list
            }
        }
        String fileName = location.getFileName().toString();
        String base = fileName.toLowerCase(Locale.ROOT).endsWith(ENDING)
                ? fileName.substring(0, fileName.length() - ENDING.length())
                : fileName;
        String name = uniqueName(base.isBlank() ? "Shared budget" : base.trim());
        Map<String, Path> shared = sharedBudgets();
        shared.put(name, location);
        writeSharedBudgets(shared);
        return name;
    }

    /** The shared budgets on this computer's list: name → file, in the order they were added. */
    private Map<String, Path> sharedBudgets() {
        Map<String, Path> shared = new LinkedHashMap<>();
        JsonNode list = readSettings().get("sharedBudgets");
        if (list != null) {
            list.fields().forEachRemaining(entry -> shared.put(entry.getKey(), Path.of(entry.getValue().asText())));
        }
        return shared;
    }

    private void writeSharedBudgets(Map<String, Path> shared) {
        ObjectNode list = json.createObjectNode();
        shared.forEach((name, file) -> list.put(name, file.toString()));
        ObjectNode settings = readSettings();
        settings.set("sharedBudgets", list);
        writeSettings(settings);
    }

    /** The shared budget's name as it's listed (ignoring capitals), if {@code name} is one. */
    private Optional<String> sharedName(String name) {
        return sharedBudgets().keySet().stream().filter(shared -> shared.equalsIgnoreCase(name)).findFirst();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Path fileFor(String name) {
        return folder().resolve(name + ENDING);
    }

    /**
     * The file of an existing budget: in your budgets folder, or wherever a shared one lives.
     *
     * @throws IllegalStateException with a friendly message if a shared budget's file can't be found
     */
    private Path existingFile(String name) {
        Optional<String> sharedName = sharedName(name);
        if (sharedName.isPresent()) {
            Path file = sharedBudgets().get(sharedName.get());
            if (Files.notExists(file)) {
                throw new IllegalStateException("Can't find the shared budget \"" + sharedName.get() + "\" at "
                        + file + ". Is iCloud Drive or Dropbox running and finished syncing on this Mac?");
            }
            return file;
        }
        String onDisk = localNames().stream().filter(n -> n.equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("There's no budget called \"" + name + "\"."));
        return fileFor(onDisk);
    }

    /** True if the budget's file is there to open (a shared one may be in a folder that's offline). */
    private boolean canOpen(String name) {
        return sharedName(name).map(shared -> Files.exists(sharedBudgets().get(shared))).orElse(true);
    }

    private boolean isTaken(String name) {
        return names().stream().anyMatch(existing -> existing.equalsIgnoreCase(name));
    }

    /** {@code base} if no budget has that name yet, otherwise "base (2)", "base (3)" and so on. */
    private String uniqueName(String base) {
        String name = base;
        for (int n = 2; isTaken(name); n++) {
            name = base + " (" + n + ")";
        }
        return name;
    }

    /** {@code name} trimmed, if it's a valid name nobody else uses. */
    private String checkNewName(String name) {
        String tidy = name == null ? "" : name.trim();
        checkNameRules(tidy);
        if (isTaken(tidy)) {
            throw new IllegalArgumentException("There's already a budget called \"" + tidy + "\".");
        }
        return tidy;
    }

    /** Names become file names, so a few characters aren't allowed. */
    private static void checkNameRules(String tidy) {
        if (tidy.isEmpty()) {
            throw new IllegalArgumentException("Give the budget a name.");
        }
        if (tidy.length() > LONGEST_NAME) {
            throw new IllegalArgumentException("Keep the name under " + LONGEST_NAME + " characters.");
        }
        if (tidy.startsWith(".") || tidy.matches(".*[/:\\\\].*")) {
            throw new IllegalArgumentException("A budget name can't start with a dot or use / : or \\.");
        }
    }

    /** Refuses files that aren't runwayd (or Paydar or TreasureTracker) data, e.g. a photo or some other app's JSON. */
    private void checkIsBudgetFile(Path source) {
        try {
            JsonNode root = json.readTree(source.toFile());
            boolean looksRight = root != null && root.isObject() && (root.has("accounts") || root.has("items"));
            if (!looksRight) {
                throw new IllegalArgumentException(source.getFileName() + " isn't a runwayd budget.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(source.getFileName() + " isn't a runwayd budget, "
                    + "or it couldn't be read.", e);
        }
    }

    private void copy(Path from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't copy " + from.getFileName() + " to " + to, e);
        }
    }

    // -------------------------------------------------------------------------
    // Settings for this computer (settings.json), e.g. {"lastBudget": "My budget", "darkMode": true}
    // -------------------------------------------------------------------------

    /** True for the dark look, false for light; empty if it's never been chosen on this computer. */
    public Optional<Boolean> darkMode() {
        JsonNode dark = readSettings().get("darkMode");
        return dark == null || !dark.isBoolean() ? Optional.empty() : Optional.of(dark.asBoolean());
    }

    /** Remembers the light or dark choice (View → Dark Mode) for next time. */
    public void setDarkMode(boolean dark) {
        writeSettings(readSettings().put("darkMode", dark));
    }

    private Path settingsFile() {
        return home.resolve("settings.json");
    }

    /** The budget that was open last time, or null. */
    private String readLastOpened() {
        JsonNode last = readSettings().get("lastBudget");
        return last == null ? null : last.asText();
    }

    private void writeLastOpened(String name) {
        writeSettings(readSettings().put("lastBudget", name));
    }

    /** Everything in settings.json; empty if there's no file yet or it can't be read. */
    private ObjectNode readSettings() {
        try {
            if (Files.exists(settingsFile()) && json.readTree(settingsFile().toFile()) instanceof ObjectNode settings) {
                return settings;
            }
        } catch (IOException e) {
            // A damaged settings file just means "start with the defaults".
        }
        return json.createObjectNode();
    }

    private void writeSettings(ObjectNode settings) {
        try {
            Files.createDirectories(home);
            json.writerWithDefaultPrettyPrinter().writeValue(settingsFile().toFile(), settings);
        } catch (IOException e) {
            // Not worth stopping for: next time the app just uses the defaults.
        }
    }
}
