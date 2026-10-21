package com.finance.runwayd.desktop;

import com.finance.runwayd.core.update.Version;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Facts about this copy of the app, written into version.properties when it was built:
 * its version, and where updates come from (see the runwayd.* settings in pom.xml).
 */
public final class AppInfo {

    private static final Properties PROPERTIES = load();

    private AppInfo() {
    }

    /** This app's version, e.g. 1.2.0. */
    public static Version version() {
        return Version.parse(PROPERTIES.getProperty("version", "0.0.0"));
    }

    /** The public GitHub repo updates come from, e.g. "yourname/runwayd", or "" if not set up. */
    public static String updateRepo() {
        return setting("updateRepo");
    }

    /** The Apple Team ID updates must be signed with, or "" if not set up. */
    public static String teamId() {
        return setting("teamId");
    }

    /** True if this build knows where updates come from and who must have signed them. */
    public static boolean updatesConfigured() {
        return !updateRepo().isEmpty() && !teamId().isEmpty();
    }

    /** A setting, or "" if it's missing or Maven left the placeholder unfilled. */
    private static String setting(String key) {
        String value = PROPERTIES.getProperty(key, "").trim();
        return value.startsWith("${") ? "" : value;
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = AppInfo.class.getResourceAsStream("version.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            // Without it the app still works; it just shows version 0.0.0 and doesn't update.
        }
        return properties;
    }
}
