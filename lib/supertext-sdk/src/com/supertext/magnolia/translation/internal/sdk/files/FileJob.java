package com.supertext.magnolia.translation.internal.sdk.files;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One item to translate in a file: the integration's metadata and its segments. */
public final class FileJob {

    private final String plugin;
    private final String reference;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final List<FileSegment> segments;

    /**
     * @param plugin    the integration's name (its repository name), e.g. {@code Contao-Supertext-Translation}
     * @param reference what the integration needs to find the item again, e.g. {@code page:12}; opaque to the SDK
     */
    public FileJob(String plugin, String reference, String sourceLanguage, String targetLanguage, List<FileSegment> segments) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.sourceLanguage = Objects.requireNonNull(sourceLanguage, "sourceLanguage");
        this.targetLanguage = Objects.requireNonNull(targetLanguage, "targetLanguage");
        List<FileSegment> copy = new ArrayList<>(Objects.requireNonNull(segments, "segments"));
        for (FileSegment segment : copy) {
            Objects.requireNonNull(segment, "segment");
        }
        this.segments = Collections.unmodifiableList(copy);
    }

    public String getPlugin() {
        return plugin;
    }

    public String getReference() {
        return reference;
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    /** The segments (unmodifiable). */
    public List<FileSegment> getSegments() {
        return segments;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FileJob)) {
            return false;
        }
        FileJob other = (FileJob) o;
        return plugin.equals(other.plugin) && reference.equals(other.reference) && sourceLanguage.equals(other.sourceLanguage)
                && targetLanguage.equals(other.targetLanguage) && segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(plugin, reference, sourceLanguage, targetLanguage, segments);
    }

    @Override
    public String toString() {
        return "FileJob(" + plugin + ", " + reference + ", " + sourceLanguage + " > " + targetLanguage + ", " + segments.size() + " segments)";
    }
}
