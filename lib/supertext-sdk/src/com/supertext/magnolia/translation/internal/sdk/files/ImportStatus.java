package com.supertext.magnolia.translation.internal.sdk.files;

/** What {@link TranslationFiles#importTranslations} found for a segment. {@link #value()} is the string shared by all Supertext SDKs. */
public enum ImportStatus {
    /** Translated, and the source is unchanged since the export. */
    OK("ok"),
    /** Translated, but the source changed since the export (or the file has no checksum): the integration decides. */
    SOURCE_CHANGED("source_changed"),
    /** The file has no translation for the segment. */
    UNTRANSLATED("untranslated"),
    /** The translation uses markup the current source doesn't have. */
    INVALID_MARKUP("invalid_markup"),
    /** The file has no unit with the segment's key. */
    MISSING("missing");

    private final String value;

    ImportStatus(String value) {
        this.value = value;
    }

    /** {@code ok}, {@code source_changed}, {@code untranslated}, {@code invalid_markup} or {@code missing}. */
    public String value() {
        return value;
    }
}
