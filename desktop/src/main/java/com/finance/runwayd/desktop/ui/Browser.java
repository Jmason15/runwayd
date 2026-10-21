package com.finance.runwayd.desktop.ui;

import javafx.application.HostServices;

import java.net.URI;

/**
 * Opens web pages, like your credit card's login page, in your normal browser
 * (Safari, Chrome, ...).
 *
 * <p>JavaFX can only do this through the running Application's HostServices, so
 * {@link App} hands them over once at start-up.
 */
public final class Browser {

    private static HostServices hostServices;

    private Browser() {
    }

    /** Called once by App when the window opens. */
    public static void init(HostServices services) {
        hostServices = services;
    }

    /** Opens {@code url} in the default browser. Does nothing for an empty link. */
    public static void open(String url) {
        if (url == null || url.isBlank() || hostServices == null) {
            return;
        }
        hostServices.showDocument(url);
    }

    /** The short form to show on screen: "https://www.mybank.com/login" becomes "mybank.com". */
    public static String shortName(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String host = URI.create(url).getHost();
        if (host == null) {
            return url;
        }
        return host.startsWith("www.") ? host.substring(4) : host;
    }
}
