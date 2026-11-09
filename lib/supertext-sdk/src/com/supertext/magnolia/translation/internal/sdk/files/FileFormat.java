package com.supertext.magnolia.translation.internal.sdk.files;

/** The kind of a translation file. {@link #value()} is the string shared by all Supertext SDKs. */
public enum FileFormat {
    /** XLIFF 1.2: for CAT tools and translators. */
    XLIFF("xliff"),
    /** The HTML document {@code HtmlDocument.build} makes, with the job's metadata in its head. */
    HTML("html");

    private final String value;

    FileFormat(String value) {
        this.value = value;
    }

    /** {@code xliff} or {@code html}. */
    public String value() {
        return value;
    }
}
