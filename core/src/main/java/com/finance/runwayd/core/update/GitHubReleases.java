package com.finance.runwayd.core.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Finds the latest runwayd release on GitHub. The repo is public, so no password or token is needed:
 * <pre>
 *   GET https://api.github.com/repos/yourname/runwayd/releases/latest
 *   → tag "v1.2.0", notes, and the files attached: runwayd-1.2.0-mac.zip (the signed app) and a .dmg
 * </pre>
 * Only the zip is used for updating; the .dmg is for installing on a new Mac.
 */
public final class GitHubReleases {

    /** The attached file that holds the signed, notarized app, e.g. "runwayd-1.2.0-mac.zip". */
    static final String APP_ZIP_ENDING = "-mac.zip";

    private final String repo;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    /** @param repo "owner/name", e.g. "yourname/runwayd" */
    public GitHubReleases(String repo) {
        this(repo, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GitHubReleases(String repo, HttpClient http) {
        this.repo = repo;
        this.http = http;
    }

    /** The newest published release, or empty if there's none yet or it has no Mac app attached. */
    public Optional<Release> latest() {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "runwayd")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty(); // no releases yet
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("GitHub answered " + response.statusCode() + " when checking for updates.");
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't reach GitHub to check for updates. Are you online?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Reads GitHub's answer: the tag ("v1.2.0"), the release notes, and the link to the attached
     * "...-mac.zip". Empty if there's no such file (e.g. a release still being uploaded).
     */
    Optional<Release> parse(String body) throws IOException {
        JsonNode release = json.readTree(body);
        String tag = release.path("tag_name").asText("");
        if (tag.isBlank() || release.path("draft").asBoolean(false) || release.path("prerelease").asBoolean(false)) {
            return Optional.empty();
        }
        for (JsonNode asset : release.path("assets")) {
            String name = asset.path("name").asText("");
            if (name.endsWith(APP_ZIP_ENDING)) {
                return Optional.of(new Release(
                        Version.parse(tag),
                        release.path("body").asText(""),
                        URI.create(asset.path("browser_download_url").asText()),
                        URI.create(release.path("html_url").asText("https://github.com/" + repo + "/releases"))));
            }
        }
        return Optional.empty();
    }

    /**
     * One published version.
     *
     * @param version  e.g. 1.2.0
     * @param notes    what changed, as written on the release
     * @param appZip   where to download the signed app
     * @param page     the release's web page, for "What's new"
     */
    public record Release(Version version, String notes, URI appZip, URI page) {
    }
}
