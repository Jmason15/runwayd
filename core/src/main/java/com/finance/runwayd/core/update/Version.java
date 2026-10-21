package com.finance.runwayd.core.update;

import java.util.Comparator;

/**
 * A version number like 1.2.0 (major.minor.patch), as used for releases.
 * GitHub tags are written "v1.2.0"; the "v" is ignored.
 */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    private static final Comparator<Version> ORDER = Comparator.comparingInt(Version::major)
            .thenComparingInt(Version::minor)
            .thenComparingInt(Version::patch);

    public Version {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version numbers can't be negative.");
        }
    }

    /**
     * Reads "1.2.0", "v1.2.0" or "1.2" (patch 0). Anything after a dash is ignored, so
     * "1.2.0-SNAPSHOT" reads as 1.2.0.
     *
     * @throws IllegalArgumentException if it isn't a version number
     */
    public static Version parse(String text) {
        String plain = text == null ? "" : text.trim();
        if (plain.startsWith("v") || plain.startsWith("V")) {
            plain = plain.substring(1);
        }
        int dash = plain.indexOf('-');
        if (dash >= 0) {
            plain = plain.substring(0, dash);
        }
        String[] parts = plain.split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("\"" + text + "\" isn't a version number like 1.2.0.");
        }
        try {
            return new Version(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    parts.length == 3 ? Integer.parseInt(parts[2]) : 0);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + text + "\" isn't a version number like 1.2.0.", e);
        }
    }

    /** True if this is a later version than {@code other}. */
    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Version other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
