package com.supertext.magnolia.translation.internal.sdk.files;

/** Why a translation file couldn't be made or read. {@link #value()} is the string shared by all Supertext SDKs. */
public enum TranslationFileErrorCode {
    /** Export: no jobs, an empty or blank metadata field or key, a control character, a duplicate key, nothing to translate. */
    INVALID_JOB("invalid_job"),
    /** Read: neither XLIFF nor HTML, not well-formed XML, a DTD; import: a file not returned by {@code read}. */
    INVALID_FILE("invalid_file"),
    /** Read: the file lacks the job's metadata (plugin, reference, languages) or, for HTML, the segment list. */
    MISSING_METADATA("missing_metadata"),
    /** Read: more than {@link TranslationFiles#MAX_FILE_CHARACTERS} characters. */
    TOO_LARGE("too_large");

    private final String value;

    TranslationFileErrorCode(String value) {
        this.value = value;
    }

    /** {@code invalid_job}, {@code invalid_file}, {@code missing_metadata} or {@code too_large}. */
    public String value() {
        return value;
    }
}
