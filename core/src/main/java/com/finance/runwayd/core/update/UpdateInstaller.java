package com.finance.runwayd.core.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Puts a new version of the app in place of the running one, safely:
 * <ol>
 *   <li><b>Download</b> the release's zip and <b>unpack</b> it next to nothing else (a fresh temp folder).</li>
 *   <li><b>Check it</b> before trusting it: the signature must be intact, it must be signed by
 *       <i>your</i> Apple Team ID (so nobody else can pass off an "update"), and macOS's Gatekeeper
 *       must accept it (signed and notarized by Apple). Anything else stops the update.</li>
 *   <li><b>Swap and reopen:</b> a running app can't replace itself, so a tiny helper script waits
 *       for runwayd to quit, moves the new app into place (keeping the old one until that worked),
 *       and opens it.</li>
 * </ol>
 * Your data isn't in the app (it's in ~/runwayd), so an update never touches it.
 *
 * <p>Updating only works in the installed Mac app ({@link #installedApp()}); when runwayd is run
 * from Maven there's nothing to replace.
 */
public final class UpdateInstaller {

    /** Runs a command line and reports how it went. Swapped out in tests. */
    public interface Commands {
        /** @return the exit code and everything the command printed */
        Result run(List<String> command);

        record Result(int exitCode, String output) {
            boolean ok() {
                return exitCode == 0;
            }
        }

        /** The real thing: runs the command and waits for it. */
        static Commands system() {
            return command -> {
                try {
                    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return new Result(process.waitFor(), output);
                } catch (IOException e) {
                    return new Result(-1, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Result(-1, "interrupted");
                }
            };
        }
    }

    private final String teamId;
    private final Commands commands;

    /** @param teamId the Apple Team ID every update must be signed with */
    public UpdateInstaller(String teamId) {
        this(teamId, Commands.system());
    }

    UpdateInstaller(String teamId, Commands commands) {
        this.teamId = teamId;
        this.commands = commands;
    }

    /**
     * Where the running app is installed, e.g. /Applications/runwayd.app. Empty when runwayd isn't
     * running as an installed Mac app (e.g. from Maven), so there's nothing to update.
     */
    public static Optional<Path> installedApp() {
        // The Mac launcher sets this to .../runwayd.app/Contents/MacOS/runwayd.
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null || launcher.isBlank()) {
            return Optional.empty();
        }
        Path app = Path.of(launcher).getParent().getParent().getParent();
        return app.getFileName().toString().endsWith(".app") ? Optional.of(app) : Optional.empty();
    }

    // -------------------------------------------------------------------------
    // 1. Download and unpack
    // -------------------------------------------------------------------------

    /**
     * Downloads the release's zip into a fresh temp folder, unpacks it and checks it (see {@link #verify}).
     *
     * @return the new runwayd.app, ready for {@link #installAndRelaunch}
     */
    public Path download(URI appZip) {
        try {
            Path folder = Files.createTempDirectory("runwayd-update");
            Path zip = folder.resolve("update.zip");
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL) // GitHub sends downloads on to its file servers
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(appZip).header("User-Agent", "runwayd").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("The update download failed (GitHub answered " + response.statusCode() + ").");
            }
            try (InputStream in = response.body()) {
                Files.copy(in, zip);
            }
            Path unpacked = folder.resolve("unpacked");
            run("unpacking the update", "ditto", "-x", "-k", zip.toString(), unpacked.toString());
            Path app = findApp(unpacked);
            verify(app);
            return app;
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't download the update.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The update download was interrupted.", e);
        }
    }

    /** The single .app inside the unpacked zip. */
    static Path findApp(Path unpacked) throws IOException {
        try (Stream<Path> entries = Files.list(unpacked)) {
            List<Path> apps = entries.filter(p -> p.getFileName().toString().endsWith(".app")).toList();
            if (apps.size() != 1) {
                throw new IllegalStateException("The update doesn't contain exactly one app.");
            }
            return apps.get(0);
        }
    }

    // -------------------------------------------------------------------------
    // 2. Check it
    // -------------------------------------------------------------------------

    /**
     * Refuses an app that isn't properly signed by this Team ID and accepted by Gatekeeper.
     *
     * @throws IllegalStateException saying which check failed; nothing is installed then
     */
    public void verify(Path app) {
        run("checking the update's signature", "codesign", "--verify", "--deep", "--strict", app.toString());

        Commands.Result details = commands.run(List.of("codesign", "-dv", "--verbose=4", app.toString()));
        String signedBy = teamIdIn(details.output()).orElse("nobody");
        if (!details.ok() || !signedBy.equals(teamId)) {
            throw new IllegalStateException("The update isn't signed by this app's developer (expected Team ID "
                    + teamId + ", found " + signedBy + "), so it wasn't installed.");
        }

        run("checking the update with macOS Gatekeeper", "spctl", "--assess", "--type", "execute", app.toString());
    }

    /** The "TeamIdentifier=ABCDE12345" line that codesign prints. */
    static Optional<String> teamIdIn(String codesignOutput) {
        return codesignOutput.lines()
                .filter(line -> line.startsWith("TeamIdentifier="))
                .map(line -> line.substring("TeamIdentifier=".length()).trim())
                .filter(id -> !id.isEmpty() && !id.equals("not set"))
                .findFirst();
    }

    // -------------------------------------------------------------------------
    // 3. Swap and reopen
    // -------------------------------------------------------------------------

    /**
     * Starts the helper that replaces {@code installedApp} with {@code newApp} once this app has
     * quit, then reopens it. The caller should quit right after this returns.
     */
    public void installAndRelaunch(Path installedApp, Path newApp) {
        try {
            Path script = Files.createTempFile("runwayd-update", ".sh");
            Files.writeString(script, helperScript(ProcessHandle.current().pid(), installedApp, newApp, "open"));
            // Detached with nohup, so it keeps going after this app quits.
            new ProcessBuilder("/bin/bash", "-c", "nohup /bin/bash " + quoted(script.toString()) + " >/dev/null 2>&1 &")
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't start the update.", e);
        }
    }

    /**
     * The helper script, in plain shell:
     * <pre>
     *   wait until the app (this process) has quit
     *   move the installed app aside, put the new one in its place
     *   if that failed, put the old one back
     *   open the app, then tidy up
     * </pre>
     *
     * @param openCommand "open" to reopen the app (tests use a harmless stand-in)
     */
    static String helperScript(long pid, Path installedApp, Path newApp, String openCommand) {
        String installed = quoted(installedApp.toString());
        String aside = quoted(installedApp + ".old");
        return String.join("\n",
                "#!/bin/bash",
                "# Written by runwayd to finish an update. Safe to delete.",
                "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done",
                "rm -rf " + aside,
                "mv " + installed + " " + aside + " || exit 1",
                "if ditto " + quoted(newApp.toString()) + " " + installed + "; then",
                "    rm -rf " + aside,
                "else",
                "    rm -rf " + installed,
                "    mv " + aside + " " + installed,
                "fi",
                openCommand + " " + installed,
                "rm -f \"$0\"",
                "");
    }

    /** A path wrapped in single quotes for the shell, with any single quotes inside escaped. */
    static String quoted(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private void run(String what, String... command) {
        Commands.Result result = commands.run(List.of(command));
        if (!result.ok()) {
            throw new IllegalStateException("The update stopped while " + what + ": " + result.output().trim());
        }
    }
}
