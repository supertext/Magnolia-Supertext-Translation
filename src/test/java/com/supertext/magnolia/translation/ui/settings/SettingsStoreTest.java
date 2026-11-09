package com.supertext.magnolia.translation.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.Node;

import org.junit.jupiter.api.Test;

import info.magnolia.jcr.util.NodeTypes;
import info.magnolia.jcr.util.NodeUtil;
import info.magnolia.test.mock.jcr.MockSession;

class SettingsStoreTest {

    @Test
    void migratesTheOldTextIntoOneEntryPerLanguage() throws Exception {
        MockSession session = new MockSession("config");
        Node config = NodeUtil.createPath(session.getRootNode(), SettingsStore.CONFIG_PATH.substring(1), NodeTypes.ContentNode.NAME);
        config.setProperty(SettingsStore.LEGACY_LANGUAGE_MAPPING, "de=de-ch\nfr=fr-CH\nno=xx-new");
        Node codes = config.addNode(SettingsStore.LANGUAGE_CODES, NodeTypes.ContentNode.NAME);
        codes.setProperty("fr", "fr-FR"); // chosen in a combo box already: wins

        assertEquals(3, SettingsStore.migrateLegacyMapping(session));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("de", "de-CH");
        expected.put("fr", "fr-FR");
        expected.put("no", "xx-new");
        assertEquals(expected, SettingsStore.languageCodes(config));
        assertFalse(config.hasProperty(SettingsStore.LEGACY_LANGUAGE_MAPPING), "the text is removed");
        assertEquals(0, SettingsStore.migrateLegacyMapping(session), "nothing left to migrate");
    }

    @Test
    void translationReviewIsStoredPerLanguage() throws Exception {
        MockSession session = new MockSession("config");
        Node config = NodeUtil.createPath(session.getRootNode(), SettingsStore.CONFIG_PATH.substring(1), NodeTypes.ContentNode.NAME);
        assertEquals(java.util.Set.of(), SettingsStore.reviewLanguages(config), "off by default");
        Map<String, Boolean> review = new LinkedHashMap<>();
        review.put("de_CH", true);
        review.put("fr_CH", false);
        SettingsStore.writeReviewLanguages(config, review);
        assertEquals(java.util.Set.of("de_CH"), SettingsStore.reviewLanguages(config));
        SettingsStore.writeReviewLanguages(config, Map.of("de_CH", false));
        assertEquals(java.util.Set.of(), SettingsStore.reviewLanguages(config));
    }

    @Test
    void noConfigNothingToMigrate() throws Exception {
        assertEquals(0, SettingsStore.migrateLegacyMapping(new MockSession("config")));
    }
}
