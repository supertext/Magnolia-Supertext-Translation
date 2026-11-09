package com.supertext.magnolia.translation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LanguageMappingTest {

    @Test
    void migratesTheOldTextKeepingEveryEntry() {
        LanguageMapping m = LanguageMapping.parseLegacy(" de = de-ch \nfr=fr-CH, it=it-CH; broken\n=x\nen=\nno=xx-new");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("de", "de-CH"); // written the way Supertext writes it
        expected.put("fr", "fr-CH");
        expected.put("it", "it-CH");
        expected.put("no", "xx-new"); // unknown to the SDK: kept as entered
        assertEquals(expected, m.entries());
        assertTrue(m.maps("DE"));
        assertFalse(m.maps("en"));
    }

    @Test
    void ofIgnoresBlankEntries() {
        Map<String, String> stored = new HashMap<>();
        stored.put("de", " de-CH ");
        stored.put("fr", "");
        stored.put(" ", "it-CH");
        assertEquals(Map.of("de", "de-CH"), LanguageMapping.of(stored).entries());
        assertEquals(Map.of(), LanguageMapping.of(null).entries());
    }

    @Test
    void comboBoxSelectionIsTheStoredCodeElseTheSuggestion() {
        LanguageMapping m = LanguageMapping.of(Map.of("de", "de-CH"));
        assertEquals("de-CH", m.selection("de"), "stored");
        assertEquals("fr-CH", m.selection("fr_CH"), "exact match");
        assertEquals("en-US", m.selection("en"), "default");
        assertEquals("es", m.selection("es_ES"), "region of a bare target");
        assertEquals("zh-Hant", m.selection("zh_TW"));
        assertNull(m.selection("de_LI"), "no default: choose");
        assertNull(m.selection("gsw"));
    }

    @Test
    void statusForTheSettingsScreen() {
        LanguageMapping m = LanguageMapping.of(Map.of("de", "de-CH", "en", "en-US"));
        LanguageMapping.Status de = m.status("de", false);
        assertEquals("de-CH", de.code());
        assertTrue(de.mapped());
        assertEquals(Boolean.TRUE, de.politeness());

        LanguageMapping.Status frCh = m.status("fr_CH", false);
        assertEquals("fr-CH", frCh.code());
        assertFalse(frCh.mapped(), "exact match");

        assertEquals(Boolean.FALSE, m.status("en", false).politeness(), "English has no formal/informal");
        assertEquals("en", m.status("en", true).code(), "as source: bare");

        LanguageMapping.Status it = m.status("it", false);
        assertEquals("it-IT", it.code(), "the SDK's default target");
        assertTrue(it.fromDefault());
        assertFalse(it.mapped());
        assertFalse(frCh.fromDefault());
        LanguageMapping.Status enIe = m.status("en_IE", false);
        assertFalse(enIe.supported(), "no obvious default");
        assertNull(enIe.politeness());
        assertEquals("it", m.status("it", true).code(), "a bare source is fine");

        LanguageMapping.Status li = m.status("de_LI", false);
        assertFalse(li.supported(), "no default region for de_LI");
        assertNull(li.politeness());
    }
}
