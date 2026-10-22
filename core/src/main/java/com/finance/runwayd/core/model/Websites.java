package com.finance.runwayd.core.model;

import java.net.URI;

/** Tidying the web addresses you can attach to an account or a bill. */
public final class Websites {

    private Websites() {
    }

    /**
     * Tidies a typed web address: blank means none, and "mybank.com" becomes
     * "https://mybank.com" so it can be opened in a browser.
     *
     * @throws IllegalArgumentException if it can't be a web address (e.g. contains spaces)
     */
    public static String clean(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        String website = typed.trim();
        if (!website.startsWith("http://") && !website.startsWith("https://")) {
            website = "https://" + website;
        }
        try {
            URI uri = URI.create(website);
            if (uri.getHost() == null || !uri.getHost().contains(".")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + typed.trim() + "\" doesn't look like a website. Try something like mybank.com");
        }
        return website;
    }
}
