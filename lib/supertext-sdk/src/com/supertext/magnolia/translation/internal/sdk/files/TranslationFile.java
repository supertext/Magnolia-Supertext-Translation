package com.supertext.magnolia.translation.internal.sdk.files;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One job read back from a file by {@link TranslationFiles#read} (an XLIFF file can hold several). */
public final class TranslationFile {

    private final FileFormat format;
    private final String plugin;
    private final String reference;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final List<FileUnit> units;
    /** The units' translations, by key; {@code null} for a file not returned by {@code read}. */
    final Map<String, TranslationFiles.UnitData> data;

    /** A file description only; {@link TranslationFiles#importTranslations} accepts only files returned by {@code read}. */
    public TranslationFile(FileFormat format, String plugin, String reference, String sourceLanguage, String targetLanguage,
                           List<FileUnit> units) {
        this(format, plugin, reference, sourceLanguage, targetLanguage, units, null);
    }

    TranslationFile(FileFormat format, String plugin, String reference, String sourceLanguage, String targetLanguage,
                    List<FileUnit> units, Map<String, TranslationFiles.UnitData> data) {
        this.format = Objects.requireNonNull(format, "format");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.sourceLanguage = Objects.requireNonNull(sourceLanguage, "sourceLanguage");
        this.targetLanguage = Objects.requireNonNull(targetLanguage, "targetLanguage");
        this.units = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(units, "units")));
        this.data = data;
    }

    public FileFormat getFormat() {
        return format;
    }

    /** The integration that exported the file. */
    public String getPlugin() {
        return plugin;
    }

    /** The job's reference: find the item with it. */
    public String getReference() {
        return reference;
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    /** The units in the file (unmodifiable). */
    public List<FileUnit> getUnits() {
        return units;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TranslationFile)) {
            return false;
        }
        TranslationFile other = (TranslationFile) o;
        return format == other.format && plugin.equals(other.plugin) && reference.equals(other.reference)
                && sourceLanguage.equals(other.sourceLanguage) && targetLanguage.equals(other.targetLanguage)
                && units.equals(other.units);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, plugin, reference, sourceLanguage, targetLanguage, units);
    }

    @Override
    public String toString() {
        return "TranslationFile(" + format.value() + ", " + plugin + ", " + reference + ", " + sourceLanguage + " > "
                + targetLanguage + ", " + units.size() + " units)";
    }
}
