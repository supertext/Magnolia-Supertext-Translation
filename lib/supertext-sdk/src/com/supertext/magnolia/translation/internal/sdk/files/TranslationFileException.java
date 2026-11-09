package com.supertext.magnolia.translation.internal.sdk.files;

import java.util.Objects;

/** A translation file that couldn't be made or read. {@link #getCode()} says why; the message is English, for logs. */
public class TranslationFileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final TranslationFileErrorCode code;

    public TranslationFileException(TranslationFileErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** Why it failed. */
    public TranslationFileErrorCode getCode() {
        return code;
    }
}
