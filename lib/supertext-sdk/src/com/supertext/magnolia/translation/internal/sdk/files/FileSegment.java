package com.supertext.magnolia.translation.internal.sdk.files;

import java.util.Objects;

/** One text of a job: plain text, or an HTML fragment when {@link #isHtml()}. */
public final class FileSegment {

    private final String key;
    private final String text;
    private final boolean html;

    /**
     * @param key  the integration's own key for the text, unique in the job, e.g. {@code title} or {@code body.3}
     * @param text the text, or an HTML fragment when {@code html} is true
     */
    public FileSegment(String key, String text, boolean html) {
        this.key = Objects.requireNonNull(key, "key");
        this.text = Objects.requireNonNull(text, "text");
        this.html = html;
    }

    /** A plain-text segment. */
    public FileSegment(String key, String text) {
        this(key, text, false);
    }

    public String getKey() {
        return key;
    }

    public String getText() {
        return text;
    }

    public boolean isHtml() {
        return html;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FileSegment)) {
            return false;
        }
        FileSegment other = (FileSegment) o;
        return html == other.html && key.equals(other.key) && text.equals(other.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, text, html);
    }

    @Override
    public String toString() {
        return "FileSegment(" + key + ", " + (html ? "html" : "text") + ")";
    }
}
