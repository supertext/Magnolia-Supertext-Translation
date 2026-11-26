package com.supertext.magnolia.translation.internal.sdk.v1;

import java.util.Objects;

/**
 * A correction a reviewer made in Supertext Localization Review that the integration hasn't written into the
 * CMS yet ({@link SupertextClient#pendingCorrections}; spec/corrections.json).
 */
public final class Correction {

    private final String id;
    private final String segmentId;
    private final String sourceLang;
    private final String targetLang;
    private final Politeness politeness;
    private final String sourceHtml;
    private final String previousHtml;
    private final String html;
    private final String status;
    private final String correctedAt;
    private final String version;

    public Correction(String id, String segmentId, String sourceLang, String targetLang, Politeness politeness,
            String sourceHtml, String previousHtml, String html, String status, String correctedAt, String version) {
        this.id = Objects.requireNonNull(id, "id");
        this.segmentId = Objects.requireNonNull(segmentId, "segmentId");
        this.sourceLang = sourceLang;
        this.targetLang = Objects.requireNonNull(targetLang, "targetLang");
        this.politeness = Objects.requireNonNull(politeness, "politeness");
        this.sourceHtml = Objects.requireNonNull(sourceHtml, "sourceHtml");
        this.previousHtml = Objects.requireNonNull(previousHtml, "previousHtml");
        this.html = Objects.requireNonNull(html, "html");
        this.status = Objects.requireNonNull(status, "status");
        this.correctedAt = Objects.requireNonNull(correctedAt, "correctedAt");
        this.version = Objects.requireNonNull(version, "version");
    }

    /** The review service's id of this place (send back with {@link SupertextClient#acknowledgeCorrections}). */
    public String getId() {
        return id;
    }

    /** The {@code data-st-id} the integration gave the segment (its own reference to the field). */
    public String getSegmentId() {
        return segmentId;
    }

    /** {@code null}: the source language was detected. */
    public String getSourceLang() {
        return sourceLang;
    }

    public String getTargetLang() {
        return targetLang;
    }

    public Politeness getPoliteness() {
        return politeness;
    }

    public String getSourceHtml() {
        return sourceHtml;
    }

    /** What the integration received for this place last: check the field still holds it before overwriting an editor's change. */
    public String getPreviousHtml() {
        return previousHtml;
    }

    /** The corrected translation: write this into the field. */
    public String getHtml() {
        return html;
    }

    /** {@code corrected}, {@code approved}, … */
    public String getStatus() {
        return status;
    }

    /** ISO time of the latest change. */
    public String getCorrectedAt() {
        return correctedAt;
    }

    /** Changes with every new correction: acknowledge the version you wrote. */
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "Correction{id=" + id + ", segmentId=" + segmentId + ", targetLang=" + targetLang + ", version=" + version + "}";
    }
}
