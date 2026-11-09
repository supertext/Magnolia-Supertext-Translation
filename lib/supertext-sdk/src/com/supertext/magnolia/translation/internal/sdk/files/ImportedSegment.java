package com.supertext.magnolia.translation.internal.sdk.files;

import java.util.Objects;

/** The result of {@link TranslationFiles#importTranslations} for one current segment. */
public final class ImportedSegment {

    private final String key;
    private final ImportStatus status;
    private final String text;

    public ImportedSegment(String key, ImportStatus status, String text) {
        this.key = Objects.requireNonNull(key, "key");
        this.status = Objects.requireNonNull(status, "status");
        this.text = text;
    }

    public String getKey() {
        return key;
    }

    public ImportStatus getStatus() {
        return status;
    }

    /** The translation (plain text or HTML, like the segment) for {@code OK} and {@code SOURCE_CHANGED}, else {@code null}. */
    public String getText() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ImportedSegment)) {
            return false;
        }
        ImportedSegment other = (ImportedSegment) o;
        return key.equals(other.key) && status == other.status && Objects.equals(text, other.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, status, text);
    }

    @Override
    public String toString() {
        return "ImportedSegment(" + key + ", " + status.value() + (text == null ? "" : ", " + text) + ")";
    }
}
