package com.supertext.magnolia.translation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Module settings as stored in {@code config:/modules/supertext-translation/config} (edited in
 * the <em>Supertext</em> app, app launcher → Translation). Also the Magnolia module class. Read
 * the settings in effect through {@link SupertextSettings}, which loads this node on every use
 * and applies the {@code SUPERTEXT_API_KEY} / {@code SUPERTEXT_API_ENDPOINT} environment variables.
 */
public class SupertextTranslationModule {

    private String apiKey = "";
    private String endpoint = "";
    private String politeness = "default";
    private int pollTimeoutSeconds = 300;
    private Map<String, String> languageCodes = new LinkedHashMap<>();
    private Set<String> reviewLanguages = new LinkedHashSet<>();
    private String reviewUrl;
    private String excludedProperties = "";
    private String additionalProperties = "";
    private Boolean versionReports;

    /** API key, with or without the {@code Supertext-Auth-Key } prefix. */
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /** API base URL; empty means https://api.supertext.com/v1/. */
    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /** Form of address: {@code default}, {@code more} (formal) or {@code less} (informal). */
    public String getPoliteness() {
        return politeness;
    }

    public void setPoliteness(String politeness) {
        this.politeness = politeness;
    }

    /** How long to wait for Supertext per document. */
    public int getPollTimeoutSeconds() {
        return pollTimeoutSeconds;
    }

    public void setPollTimeoutSeconds(int pollTimeoutSeconds) {
        this.pollTimeoutSeconds = pollTimeoutSeconds;
    }

    /**
     * Magnolia language → Supertext code, e.g. {@code de=de-CH}: the combo boxes under <em>Language codes</em>,
     * stored as the child node {@code languageCodes} (one property per language). A language without an entry is
     * sent as its exact match or the SDK's default (see {@link com.supertext.magnolia.translation.api.LanguageMapping}).
     */
    public Map<String, String> getLanguageCodes() {
        return languageCodes;
    }

    public void setLanguageCodes(Map<String, String> languageCodes) {
        this.languageCodes = languageCodes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(languageCodes);
    }

    /**
     * Magnolia languages with <em>Translation review</em> on (the checkboxes next to the combo boxes, stored as the
     * child node {@code languageReview}, {@code de_CH = true}): translations into them go through Supertext's review
     * service.
     */
    public Set<String> getReviewLanguages() {
        return reviewLanguages;
    }

    public void setReviewLanguages(Set<String> reviewLanguages) {
        this.reviewLanguages = reviewLanguages == null ? new LinkedHashSet<>() : new LinkedHashSet<>(reviewLanguages);
    }

    /**
     * The review service's base URL ({@code reviewUrl}): {@code null} when not set (= Supertext's review service),
     * {@code ""} = review off for every language. {@code SUPERTEXT_REVIEW_URL} wins.
     */
    public String getReviewUrl() {
        return reviewUrl;
    }

    public void setReviewUrl(String reviewUrl) {
        this.reviewUrl = reviewUrl;
    }

    /** Property names never translated (comma or line separated). */
    public String getExcludedProperties() {
        return excludedProperties;
    }

    public void setExcludedProperties(String excludedProperties) {
        this.excludedProperties = excludedProperties;
    }

    /**
     * Extra property names to translate even when no dialog marks them {@code i18n: true}
     * (e.g. content created by import scripts). Comma or line separated.
     */
    public String getAdditionalProperties() {
        return additionalProperties;
    }

    public void setAdditionalProperties(String additionalProperties) {
        this.additionalProperties = additionalProperties;
    }

    /**
     * "Send version information to Supertext"; {@code null} (never saved) means on. The environment variable
     * {@code SUPERTEXT_VERSION_REPORTS} wins (see {@link SupertextSettings#versionReportsEnabled()}).
     */
    public Boolean getVersionReports() {
        return versionReports;
    }

    public void setVersionReports(Boolean versionReports) {
        this.versionReports = versionReports;
    }
}
