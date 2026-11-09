package com.supertext.magnolia.translation;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.supertext.magnolia.translation.api.SupertextConnection;
import com.supertext.magnolia.translation.api.SupertextService;
import com.supertext.magnolia.translation.ui.settings.SettingsStore;
import com.supertext.magnolia.translation.api.LanguageMapping;
import com.supertext.magnolia.translation.api.SupertextException;
import com.supertext.magnolia.translation.internal.pluginhealth.Reporting;

/**
 * The settings in effect: the module configuration (edited in the Supertext app), with the API key and endpoint
 * overridden by the {@code SUPERTEXT_API_KEY} / {@code SUPERTEXT_API_ENDPOINT} environment
 * variables (or the same names as Java system properties) when those are set.
 */
@Singleton
public class SupertextSettings {

    public static final String API_KEY_VARIABLE = "SUPERTEXT_API_KEY";
    public static final String ENDPOINT_VARIABLE = "SUPERTEXT_API_ENDPOINT";
    public static final String REVIEW_URL_VARIABLE = "SUPERTEXT_REVIEW_URL";

    private final Supplier<SupertextTranslationModule> module;
    private final Function<String, String> environment;

    /**
     * Used by Magnolia: reads {@code config:/modules/supertext-translation/config} on every use,
     * so settings saved in the Supertext app apply right away (Magnolia doesn't refresh the
     * module bean when that node is created at runtime).
     */
    @Inject
    public SupertextSettings() {
        this(SettingsStore::loadModule, SupertextSettings::systemValue);
    }

    public SupertextSettings(Supplier<SupertextTranslationModule> module, Function<String, String> environment) {
        this.module = module;
        this.environment = environment;
    }

    public SupertextTranslationModule module() {
        SupertextTranslationModule m = module.get();
        return m == null ? new SupertextTranslationModule() : m;
    }

    /** The key from the environment, if any (then the stored key is not used). */
    public String apiKeyFromEnvironment() {
        return trim(environment.apply(API_KEY_VARIABLE));
    }

    public String endpointFromEnvironment() {
        return trim(environment.apply(ENDPOINT_VARIABLE));
    }

    public SupertextConnection connection() {
        SupertextTranslationModule m = module();
        String apiKey = firstNonEmpty(apiKeyFromEnvironment(), m.getApiKey());
        String endpoint = firstNonEmpty(endpointFromEnvironment(), m.getEndpoint(), SupertextService.DEFAULT_ENDPOINT);
        int timeout = m.getPollTimeoutSeconds() > 0 ? m.getPollTimeoutSeconds() : 300;
        return new SupertextConnection(endpoint, SupertextService.normalizeApiKey(apiKey), 2000, timeout, reviewUrl());
    }

    /**
     * The connection for translations into a Magnolia language: the review service when its <em>Translation
     * review</em> is on and a review URL is set, else Supertext directly. Only for translations: the key check and
     * the reports use {@link #connection()}.
     */
    public SupertextConnection connection(Locale target) {
        return connection().forReview(reviewFor(target));
    }

    /**
     * The review service in effect: {@code SUPERTEXT_REVIEW_URL} when set, else the stored {@code reviewUrl}
     * ({@code ""} = off), else Supertext's review service ({@link SupertextService#REVIEW_URL}).
     */
    public String reviewUrl() {
        String fromEnvironment = trim(environment.apply(REVIEW_URL_VARIABLE));
        if (!fromEnvironment.isEmpty()) {
            return fromEnvironment;
        }
        String stored = module().getReviewUrl();
        return stored == null ? SupertextService.REVIEW_URL : stored.trim();
    }

    /** Whether <em>Translation review</em> is ticked for the language (case and {@code _}/{@code -} ignored). */
    public boolean reviewFor(Locale target) {
        String key = reviewKey(target.toString());
        return module().getReviewLanguages().stream().anyMatch(language -> reviewKey(language).equals(key));
    }

    private static String reviewKey(String language) {
        return language == null ? "" : language.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    public boolean hasApiKey() {
        return !connection().apiKey().isEmpty();
    }

    public String politeness() {
        String p = trim(module().getPoliteness());
        return p.equals("more") || p.equals("less") ? p : "default";
    }

    /**
     * The target code sent to Supertext for a Magnolia locale: the <em>Language codes</em> combo box, else an exact
     * match ({@code de_CH} → {@code de-CH}), else the SDK's default ({@code de} → {@code de-DE}), else
     * {@code unsupportedLanguage} (nothing is sent).
     */
    public String targetLanguageCode(Locale locale) throws SupertextException {
        return languageMapping().target(locale.toString());
    }

    /** The source code sent to Supertext: the entry in its source form ({@code de=de-CH} → {@code de}), else the bare language. */
    public String sourceLanguageCode(Locale locale) throws SupertextException {
        return languageMapping().source(locale.toString());
    }

    public LanguageMapping languageMapping() {
        return LanguageMapping.of(module().getLanguageCodes());
    }

    /** The "Send version information to Supertext" setting ({@code null} = not set = on); see {@link #versionReportsEnabled()}. */
    public Boolean versionReportsSetting() {
        return module().getVersionReports();
    }

    /** Whether version reports are on: {@code SUPERTEXT_VERSION_REPORTS} wins over the setting, which is on by default. */
    public boolean versionReportsEnabled() {
        return Reporting.reportsEnabled(versionReportsSetting(), environment.apply(Reporting.ENV_VERSION_REPORTS));
    }

    /** Whether {@code SUPERTEXT_VERSION_REPORTS} decides on its own (then the setting is shown read-only). */
    public boolean versionReportsFromEnvironment() {
        String env = environment.apply(Reporting.ENV_VERSION_REPORTS);
        return Reporting.reportsEnabled(true, env) == Reporting.reportsEnabled(false, env);
    }

    public Set<String> excludedProperties() {
        return split(module().getExcludedProperties());
    }

    public Set<String> additionalProperties() {
        return split(module().getAdditionalProperties());
    }

    static Set<String> split(String value) {
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream((value == null ? "" : value).split("[\\n,;]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(result::add);
        return result;
    }

    private static String systemValue(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? System.getProperty(name) : value;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
