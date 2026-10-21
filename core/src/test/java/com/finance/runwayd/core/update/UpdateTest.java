package com.finance.runwayd.core.update;

import com.finance.runwayd.core.update.UpdateInstaller.Commands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version numbers, reading GitHub's answer, checking a download, and swapping the app. */
class UpdateTest {

    private static final String TEAM = "ABCDE12345";

    @TempDir
    Path temp;

    // ---- version numbers ----

    @Test
    void versionsReadTheWayTheyAreWritten() {
        assertEquals(new Version(1, 2, 0), Version.parse("v1.2.0"));
        assertEquals(new Version(1, 2, 0), Version.parse("1.2"));
        assertEquals(new Version(1, 0, 0), Version.parse("1.0.0-SNAPSHOT"));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("latest"));
    }

    @Test
    void newerMeansAHigherNumberNotALongerOne() {
        assertTrue(Version.parse("1.10.0").isNewerThan(Version.parse("1.9.3")));
        assertTrue(Version.parse("2.0.0").isNewerThan(Version.parse("1.99.99")));
        assertFalse(Version.parse("1.2.0").isNewerThan(Version.parse("1.2.0")));
    }

    // ---- GitHub's answer ----

    @Test
    void findsTheVersionNotesAndMacZipInARelease() throws Exception {
        String body = """
                {"tag_name": "v1.2.0", "body": "Faster forecasts", "draft": false, "prerelease": false,
                 "html_url": "https://github.com/someone/runwayd/releases/tag/v1.2.0",
                 "assets": [
                   {"name": "runwayd-1.2.0.dmg", "browser_download_url": "https://example.com/runwayd-1.2.0.dmg"},
                   {"name": "runwayd-1.2.0-mac.zip", "browser_download_url": "https://example.com/runwayd-1.2.0-mac.zip"}
                 ]}
                """;

        GitHubReleases.Release release = new GitHubReleases("someone/runwayd").parse(body).orElseThrow();

        assertEquals(Version.parse("1.2.0"), release.version());
        assertEquals("Faster forecasts", release.notes());
        assertEquals("https://example.com/runwayd-1.2.0-mac.zip", release.appZip().toString());
    }

    @Test
    void draftsAndReleasesWithoutTheAppAreIgnored() throws Exception {
        GitHubReleases github = new GitHubReleases("someone/runwayd");
        assertEquals(Optional.empty(), github.parse("""
                {"tag_name": "v1.3.0", "draft": true, "assets": [
                  {"name": "runwayd-1.3.0-mac.zip", "browser_download_url": "https://example.com/x.zip"}]}
                """));
        assertEquals(Optional.empty(), github.parse("""
                {"tag_name": "v1.3.0", "assets": [{"name": "notes.txt", "browser_download_url": "https://example.com/n"}]}
                """));
    }

    // ---- checking a download before trusting it ----

    @Test
    void aProperlySignedUpdateFromYourTeamPasses() {
        new UpdateInstaller(TEAM, fake(command -> ok(signedBy(TEAM)))).verify(temp);
    }

    @Test
    void anUpdateSignedBySomeoneElseIsRefused() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new UpdateInstaller(TEAM, fake(command -> ok(signedBy("ZZZZZ99999")))).verify(temp));
        assertTrue(error.getMessage().contains("isn't signed by this app's developer"), error.getMessage());
    }

    @Test
    void aBrokenSignatureOrAGatekeeperNoIsRefused() {
        Commands badSignature = fake(command -> command.contains("--verify")
                ? new Commands.Result(1, "invalid signature") : ok(signedBy(TEAM)));
        assertThrows(IllegalStateException.class, () -> new UpdateInstaller(TEAM, badSignature).verify(temp));

        Commands notNotarized = fake(command -> command.get(0).equals("spctl")
                ? new Commands.Result(3, "rejected") : ok(signedBy(TEAM)));
        assertThrows(IllegalStateException.class, () -> new UpdateInstaller(TEAM, notNotarized).verify(temp));
    }

    @Test
    void readsTheTeamFromCodesignsOutput() {
        assertEquals(Optional.of(TEAM), UpdateInstaller.teamIdIn("Identifier=x\nTeamIdentifier=" + TEAM + "\n"));
        assertEquals(Optional.empty(), UpdateInstaller.teamIdIn("TeamIdentifier=not set\n"));
    }

    // ---- swapping the app ----

    @Test
    void theHelperSwapsInTheNewAppOnceTheOldOneHasQuit() throws Exception {
        Path installed = Files.createDirectories(temp.resolve("Apps").resolve("runwayd.app"));
        Files.writeString(installed.resolve("version"), "1.0.0");
        Path newApp = Files.createDirectories(temp.resolve("download").resolve("runwayd.app"));
        Files.writeString(newApp.resolve("version"), "1.2.0");
        Process finished = new ProcessBuilder("true").start();
        finished.waitFor(); // stands in for runwayd, already quit

        Path script = temp.resolve("swap.sh");
        Files.writeString(script, UpdateInstaller.helperScript(finished.pid(), installed, newApp, "true"));
        assertEquals(0, new ProcessBuilder("/bin/bash", script.toString()).start().waitFor());

        assertEquals("1.2.0", Files.readString(installed.resolve("version")), "the new app is in place");
        assertFalse(Files.exists(Path.of(installed + ".old")), "the old copy is tidied away");
    }

    @Test
    void pathsWithApostrophesAreQuotedSafelyForTheShell() throws Exception {
        String tricky = "/Users/o'neil/My Apps/runwayd.app";
        Process echo = new ProcessBuilder("/bin/bash", "-c", "printf %s " + UpdateInstaller.quoted(tricky)).start();
        assertEquals(tricky, new String(echo.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    // ---- helpers ----

    private static String signedBy(String team) {
        return "Executable=/x\nTeamIdentifier=" + team + "\n";
    }

    private static Commands.Result ok(String output) {
        return new Commands.Result(0, output);
    }

    /** Pretends to run commands, answering each with {@code answer}. */
    private static Commands fake(Function<List<String>, Commands.Result> answer) {
        return answer::apply;
    }
}
