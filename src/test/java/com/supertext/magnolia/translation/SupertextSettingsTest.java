package com.supertext.magnolia.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.supertext.magnolia.translation.api.SupertextException;

class SupertextSettingsTest {

    private static SupertextSettings settings(SupertextTranslationModule module, Map<String, String> env) {
        return new SupertextSettings(() -> module, env::get);
    }

    @Test
    void environmentOverridesStoredKeyAndEndpoint() {
        SupertextTranslationModule module = new SupertextTranslationModule();
        module.setApiKey("stored");
        module.setEndpoint("https://stored.example/v1/");
        SupertextSettings s = settings(module, Map.of("SUPERTEXT_API_KEY", "Supertext-Auth-Key fromEnv", "SUPERTEXT_API_ENDPOINT", "https://env.example/v1/"));

        assertEquals("fromEnv", s.connection().apiKey());
        assertEquals("https://env.example/v1/", s.connection().endpoint());
    }

    @Test
    void translationReviewPerLanguage() {
        SupertextTranslationModule module = new SupertextTranslationModule();
        module.setApiKey("k");
        module.setReviewLanguages(Set.of("de_CH"));
        SupertextSettings s = settings(module, Map.of());

        assertEquals("https://localizationreview-production.up.railway.app/v1/", s.reviewUrl(), "default: Supertext's review service");
        assertEquals("https://localizationreview-production.up.railway.app/v1/", s.connection(new Locale("de", "CH")).endpoint());
        assertEquals("https://api.supertext.com/v1/", s.connection(new Locale("fr", "CH")).endpoint(), "review off: directly");
        assertEquals("https://api.supertext.com/v1/", s.connection().endpoint(), "key check and reports: directly");

        module.setReviewUrl("");
        assertEquals("https://api.supertext.com/v1/", s.connection(new Locale("de", "CH")).endpoint(), "empty reviewUrl: off everywhere");
        module.setReviewUrl("https://stored.example/v1/");
        assertEquals("https://stored.example/v1/", s.connection(new Locale("de", "CH")).endpoint());
        SupertextSettings env = settings(module, Map.of("SUPERTEXT_REVIEW_URL", "http://localhost:3000/v1/"));
        assertEquals("http://localhost:3000/v1/", env.connection(new Locale("de", "CH")).endpoint(), "the environment wins");
    }

    @Test
    void storedValuesAndDefaults() {
        SupertextTranslationModule module = new SupertextTranslationModule();
        module.setApiKey(" stored ");
        module.setPoliteness("weird");
        module.setPollTimeoutSeconds(0);
        SupertextSettings s = settings(module, Map.of());

        assertEquals("stored", s.connection().apiKey());
        assertEquals("https://api.supertext.com/v1/", s.connection().endpoint());
        assertEquals(300, s.connection().pollTimeoutSeconds());
        assertEquals("default", s.politeness());
        assertFalse(settings(new SupertextTranslationModule(), Map.of()).hasApiKey());
    }

    @Test
    void mappingFirstThenExactMatchThenDefault() throws SupertextException {
        SupertextTranslationModule module = new SupertextTranslationModule();
        module.setLanguageCodes(Map.of("de", "de-CH", "fr_CH", "fr-FR"));
        SupertextSettings s = settings(module, Map.of());

        assertEquals("de-CH", s.targetLanguageCode(Locale.GERMAN));
        assertEquals("fr-FR", s.targetLanguageCode(new Locale("fr", "CH")));
        assertEquals("it-CH", s.targetLanguageCode(new Locale("it", "CH")), "no entry: exact match");
        assertEquals("es", s.targetLanguageCode(new Locale("es")), "bare targets exist for most languages");
        assertEquals("es", s.targetLanguageCode(new Locale("es", "ES")), "default: the bare target");
        SupertextSettings none = settings(new SupertextTranslationModule(), Map.of());
        assertEquals("it-IT", none.targetLanguageCode(Locale.ITALIAN), "no entry, no exact match: the SDK's default");
        SupertextException e = assertThrows(SupertextException.class, () -> s.targetLanguageCode(new Locale("de", "LI")));
        assertEquals("supertext-translation.error.unsupportedLanguage", e.key(), "no default for de_LI");
        assertEquals("de_LI", e.args()[0]);
    }

    @Test
    void oneMappingServesBothDirections() throws SupertextException {
        SupertextTranslationModule module = new SupertextTranslationModule();
        module.setLanguageCodes(Map.of("en", "en-US", "de", "de-CH"));
        SupertextSettings s = settings(module, Map.of());

        assertEquals("en-US", s.targetLanguageCode(Locale.ENGLISH));
        assertEquals("en", s.sourceLanguageCode(Locale.ENGLISH), "a mapped target code is sent in its source form");
        assertEquals("de", s.sourceLanguageCode(new Locale("de", "CH")));
        assertEquals("fr", s.sourceLanguageCode(new Locale("fr")));
    }

    @Test
    void versionReportsAreOnByDefaultAndTheEnvironmentWins() {
        SupertextTranslationModule module = new SupertextTranslationModule();
        assertTrue(settings(module, Map.of()).versionReportsEnabled(), "not set: on");
        assertFalse(settings(module, Map.of()).versionReportsFromEnvironment());
        module.setVersionReports(false);
        assertFalse(settings(module, Map.of()).versionReportsEnabled());
        assertTrue(settings(module, Map.of("SUPERTEXT_VERSION_REPORTS", "1")).versionReportsEnabled());
        module.setVersionReports(true);
        SupertextSettings off = settings(module, Map.of("SUPERTEXT_VERSION_REPORTS", "0"));
        assertFalse(off.versionReportsEnabled());
        assertTrue(off.versionReportsFromEnvironment());
    }

    @Test
    void propertyListsSplitOnCommasAndLines() {
        SupertextTranslationModule module = new SupertextTranslationModule();
        module.setExcludedProperties("teaser, subtitle\n\n code ");
        assertEquals(Set.of("teaser", "subtitle", "code"), settings(module, Map.of()).excludedProperties());
    }
}
