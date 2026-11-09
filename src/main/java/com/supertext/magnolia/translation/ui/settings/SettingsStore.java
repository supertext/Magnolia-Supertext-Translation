package com.supertext.magnolia.translation.ui.settings;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import info.magnolia.context.MgnlContext;
import info.magnolia.jcr.util.NodeTypes;
import info.magnolia.jcr.util.NodeUtil;
import info.magnolia.repository.RepositoryConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.supertext.magnolia.translation.SupertextTranslationModule;
import com.supertext.magnolia.translation.api.LanguageMapping;

/**
 * Reads and writes {@code config:/modules/supertext-translation/config}, the settings edited
 * in the Supertext app. {@link #write} uses the current user's session, so only users allowed
 * to change the configuration can save; {@link #loadModule} reads with system rights.
 *
 * <p>The language codes (one combo box per site language) are the child node {@code languageCodes}, one property per
 * Magnolia language ({@code de_CH = de-CH}). Up to 0.1.x they were the text property {@code languageMapping}
 * ({@code de=de-CH} per line): {@link #migrateLegacyMapping} moves it into the node.</p>
 */
public class SettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

    public static final String CONFIG_PATH = "/modules/supertext-translation/config";

    public static final String API_KEY = "apiKey";
    public static final String ENDPOINT = "endpoint";
    public static final String POLITENESS = "politeness";
    public static final String POLL_TIMEOUT = "pollTimeoutSeconds";
    /** Child node: Magnolia language → Supertext code. */
    public static final String LANGUAGE_CODES = "languageCodes";
    /** Child node: Magnolia language → {@code true} for languages with <em>Translation review</em> on. */
    public static final String LANGUAGE_REVIEW = "languageReview";
    /** The review service's base URL; not set = Supertext's review service, empty = off. No field in the app. */
    public static final String REVIEW_URL = "reviewUrl";
    /** The textarea of 0.1.x, migrated into {@link #LANGUAGE_CODES}. */
    public static final String LEGACY_LANGUAGE_MAPPING = "languageMapping";
    public static final String EXCLUDED = "excludedProperties";
    public static final String ADDITIONAL = "additionalProperties";
    public static final String VERSION_REPORTS = "versionReports";

    /** The stored settings, read with system rights (editors can't read the config workspace). */
    public static SupertextTranslationModule loadModule() {
        SupertextTranslationModule module = new SupertextTranslationModule();
        try {
            Map<String, String> values = MgnlContext.doInSystemContext(() -> {
                try {
                    return new SettingsStore().read();
                } catch (RepositoryException e) {
                    throw new IllegalStateException(e);
                }
            });
            module.setApiKey(values.getOrDefault(API_KEY, ""));
            module.setEndpoint(values.getOrDefault(ENDPOINT, ""));
            module.setPoliteness(values.getOrDefault(POLITENESS, "default"));
            String timeout = values.getOrDefault(POLL_TIMEOUT, "");
            if (!timeout.isBlank()) {
                module.setPollTimeoutSeconds(Integer.parseInt(timeout.trim()));
            }
            module.setLanguageCodes(MgnlContext.doInSystemContext(() -> {
                try {
                    return new SettingsStore().readLanguageCodes();
                } catch (RepositoryException e) {
                    throw new IllegalStateException(e);
                }
            }));
            module.setReviewLanguages(MgnlContext.doInSystemContext(() -> {
                try {
                    return new SettingsStore().readReviewLanguages();
                } catch (RepositoryException e) {
                    throw new IllegalStateException(e);
                }
            }));
            module.setReviewUrl(MgnlContext.doInSystemContext(() -> {
                try {
                    return new SettingsStore().readReviewUrl();
                } catch (RepositoryException e) {
                    throw new IllegalStateException(e);
                }
            }));
            module.setExcludedProperties(values.getOrDefault(EXCLUDED, ""));
            module.setAdditionalProperties(values.getOrDefault(ADDITIONAL, ""));
            module.setVersionReports(parseBoolean(values.getOrDefault(VERSION_REPORTS, "")));
        } catch (RuntimeException e) {
            log.warn("Supertext: could not read {}: {}", CONFIG_PATH, e.getMessage());
        }
        return module;
    }

    /** {@code true}/{@code false}; anything else (not set) is null, which means on. */
    static Boolean parseBoolean(String value) {
        String v = value == null ? "" : value.trim();
        return v.equalsIgnoreCase("true") ? Boolean.TRUE : v.equalsIgnoreCase("false") ? Boolean.FALSE : null;
    }

    public Map<String, String> read() throws RepositoryException {
        Map<String, String> values = new LinkedHashMap<>();
        Session session = MgnlContext.getJCRSession(RepositoryConstants.CONFIG);
        if (session.nodeExists(CONFIG_PATH)) {
            Node node = session.getNode(CONFIG_PATH);
            for (String name : new String[] {API_KEY, ENDPOINT, POLITENESS, POLL_TIMEOUT, EXCLUDED, ADDITIONAL, VERSION_REPORTS}) {
                values.put(name, node.hasProperty(name) ? node.getProperty(name).getString() : "");
            }
        }
        return values;
    }

    /** Writes the given values; a null value leaves the stored one unchanged. */
    public void write(Map<String, String> values) throws RepositoryException {
        Session session = MgnlContext.getJCRSession(RepositoryConstants.CONFIG);
        Node node = NodeUtil.createPath(session.getRootNode(), CONFIG_PATH.substring(1), NodeTypes.ContentNode.NAME);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (POLL_TIMEOUT.equals(entry.getKey())) {
                node.setProperty(entry.getKey(), Long.parseLong(entry.getValue()));
            } else if (VERSION_REPORTS.equals(entry.getKey())) {
                node.setProperty(entry.getKey(), Boolean.parseBoolean(entry.getValue()));
            } else {
                node.setProperty(entry.getKey(), entry.getValue());
            }
        }
        session.save();
    }

    /**
     * The stored language codes. Until the startup task has migrated it, a 0.1.x text mapping is read as well
     * (the node's entries win), so nothing configured is lost in between.
     */
    public Map<String, String> readLanguageCodes() throws RepositoryException {
        Session session = MgnlContext.getJCRSession(RepositoryConstants.CONFIG);
        return session.nodeExists(CONFIG_PATH) ? languageCodes(session.getNode(CONFIG_PATH)) : new LinkedHashMap<>();
    }

    /** The stored {@code reviewUrl}, or {@code null} when it isn't set (then Supertext's review service is used). */
    public String readReviewUrl() throws RepositoryException {
        Session session = MgnlContext.getJCRSession(RepositoryConstants.CONFIG);
        if (!session.nodeExists(CONFIG_PATH)) {
            return null;
        }
        Node config = session.getNode(CONFIG_PATH);
        return config.hasProperty(REVIEW_URL) ? config.getProperty(REVIEW_URL).getString() : null;
    }

    /** The languages with <em>Translation review</em> on. */
    public Set<String> readReviewLanguages() throws RepositoryException {
        Session session = MgnlContext.getJCRSession(RepositoryConstants.CONFIG);
        return session.nodeExists(CONFIG_PATH) ? reviewLanguages(session.getNode(CONFIG_PATH)) : new LinkedHashSet<>();
    }

    static Set<String> reviewLanguages(Node config) throws RepositoryException {
        Set<String> languages = new LinkedHashSet<>();
        if (config.hasNode(LANGUAGE_REVIEW)) {
            PropertyIterator properties = config.getNode(LANGUAGE_REVIEW).getProperties();
            while (properties.hasNext()) {
                Property property = properties.nextProperty();
                if (!property.getName().contains(":") && !property.isMultiple() && Boolean.parseBoolean(property.getString())) {
                    languages.add(property.getName());
                }
            }
        }
        return languages;
    }

    /**
     * Sets <em>Translation review</em> for the given languages ({@code true} = on); languages not in the map keep
     * their stored value (e.g. a language no site has any more).
     */
    public void writeReviewLanguages(Map<String, Boolean> review) throws RepositoryException {
        Session session = MgnlContext.getJCRSession(RepositoryConstants.CONFIG);
        Node config = NodeUtil.createPath(session.getRootNode(), CONFIG_PATH.substring(1), NodeTypes.ContentNode.NAME);
        writeReviewLanguages(config, review);
        session.save();
    }

    static void writeReviewLanguages(Node config, Map<String, Boolean> review) throws RepositoryException {
        Node node = config.hasNode(LANGUAGE_REVIEW) ? config.getNode(LANGUAGE_REVIEW) : config.addNode(LANGUAGE_REVIEW, NodeTypes.ContentNode.NAME);
        for (Map.Entry<String, Boolean> entry : review.entrySet()) {
            try {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    node.setProperty(entry.getKey(), true);
                } else if (node.hasProperty(entry.getKey())) {
                    node.getProperty(entry.getKey()).remove();
                }
            } catch (RepositoryException e) {
                log.warn("Supertext: could not store Translation review for \"{}\": {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /** Replaces the stored language codes (an empty value removes the entry). */
    public void writeLanguageCodes(Map<String, String> codes) throws RepositoryException {
        Session session = MgnlContext.getJCRSession(RepositoryConstants.CONFIG);
        Node config = NodeUtil.createPath(session.getRootNode(), CONFIG_PATH.substring(1), NodeTypes.ContentNode.NAME);
        if (writeLanguageCodes(config, codes) == 0 && config.hasProperty(LEGACY_LANGUAGE_MAPPING)) {
            config.getProperty(LEGACY_LANGUAGE_MAPPING).remove();
        }
        session.save();
    }

    static Map<String, String> languageCodes(Node config) throws RepositoryException {
        Map<String, String> codes = new LinkedHashMap<>();
        if (config.hasProperty(LEGACY_LANGUAGE_MAPPING)) {
            codes.putAll(LanguageMapping.parseLegacy(config.getProperty(LEGACY_LANGUAGE_MAPPING).getString()).entries());
        }
        if (config.hasNode(LANGUAGE_CODES)) {
            PropertyIterator properties = config.getNode(LANGUAGE_CODES).getProperties();
            while (properties.hasNext()) {
                Property property = properties.nextProperty();
                if (!property.getName().contains(":") && !property.isMultiple() && !property.getString().isBlank()) {
                    codes.put(property.getName(), property.getString().strip());
                }
            }
        }
        return codes;
    }

    /** Writes the codes into {@code languageCodes}; returns how many entries couldn't be written (an illegal name). */
    private static int writeLanguageCodes(Node config, Map<String, String> codes) throws RepositoryException {
        Node node = config.hasNode(LANGUAGE_CODES) ? config.getNode(LANGUAGE_CODES) : config.addNode(LANGUAGE_CODES, NodeTypes.ContentNode.NAME);
        PropertyIterator existing = node.getProperties();
        java.util.List<Property> remove = new java.util.ArrayList<>();
        while (existing.hasNext()) {
            Property property = existing.nextProperty();
            if (!property.getName().contains(":") && !codes.containsKey(property.getName())) {
                remove.add(property);
            }
        }
        for (Property property : remove) {
            property.remove();
        }
        int failed = 0;
        for (Map.Entry<String, String> entry : codes.entrySet()) {
            try {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    if (node.hasProperty(entry.getKey())) {
                        node.getProperty(entry.getKey()).remove();
                    }
                } else {
                    node.setProperty(entry.getKey(), entry.getValue().strip());
                }
            } catch (RepositoryException e) {
                failed++;
                log.warn("Supertext: could not store the language code for \"{}\": {}", entry.getKey(), e.getMessage());
            }
        }
        return failed;
    }

    /**
     * Moves a 0.1.x {@code languageMapping} text ({@code de=de-CH} per line) into the {@code languageCodes} node,
     * keeping entries already in the node, and removes the text. Returns how many entries were moved; 0 when there
     * was nothing to migrate. Doesn't save the session.
     */
    public static int migrateLegacyMapping(Session config) throws RepositoryException {
        if (!config.nodeExists(CONFIG_PATH)) {
            return 0;
        }
        Node node = config.getNode(CONFIG_PATH);
        if (!node.hasProperty(LEGACY_LANGUAGE_MAPPING)) {
            return 0;
        }
        Map<String, String> legacy = LanguageMapping.parseLegacy(node.getProperty(LEGACY_LANGUAGE_MAPPING).getString()).entries();
        Map<String, String> merged = languageCodes(node); // legacy entries, overridden by the node's
        if (writeLanguageCodes(node, merged) > 0) {
            return 0; // keeps the text, so the entry that couldn't be stored isn't lost (it is still read from there)
        }
        node.getProperty(LEGACY_LANGUAGE_MAPPING).remove();
        return legacy.size();
    }
}
