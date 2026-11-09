package com.supertext.magnolia.translation.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.supertext.magnolia.translation.internal.sdk.v1.Languages;

/**
 * The <em>Language codes</em> setting: Magnolia language → Supertext code, e.g. {@code de=de-CH}, chosen in a
 * combo box per site language in the Supertext app and stored in
 * {@code config:/modules/supertext-translation/config/languageCodes} (one property per language). One mapping
 * serves both directions: as target {@code de} is sent as {@code de-CH}, as source (the site's main language) as
 * {@code de}.
 *
 * <p>The SDK's {@link Languages#target(String, Map)}: the mapping wins, else a Magnolia language that equals a
 * Supertext code (case and {@code _}/{@code -} ignored: {@code de_CH} → {@code de-CH}, {@code es}), else the SDK's
 * default ({@code de} → {@code de-DE}, {@code en} → {@code en-US}, {@code es_ES} → {@code es}), else the language is
 * unsupported and nothing is sent ({@code de_LI}, {@code en_IE}, {@code gsw} need a mapping). No Magnolia classes, so
 * it is unit-tested on its own.</p>
 */
public final class LanguageMapping {

    private final Map<String, String> entries;

    private LanguageMapping(Map<String, String> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    /** The stored mapping; blank keys or values are ignored. */
    public static LanguageMapping of(Map<String, String> entries) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (entries != null) {
            entries.forEach((language, code) -> {
                if (language != null && !language.isBlank() && code != null && !code.isBlank()) {
                    mapping.put(language.strip(), code.strip());
                }
            });
        }
        return new LanguageMapping(mapping);
    }

    /**
     * The textarea of versions up to 0.1.x ({@code de=de-CH}, one per line or separated by commas or semicolons),
     * for the migration to the combo boxes. Lines without {@code =} or with an empty side are ignored, later lines
     * win, and a code Supertext knows is written the way Supertext writes it ({@code de_ch} → {@code de-CH}). A code
     * the SDK doesn't know is kept as entered, so nothing configured is lost.
     */
    public static LanguageMapping parseLegacy(String value) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String line : (value == null ? "" : value).split("[\\n,;]")) {
            String[] pair = line.split("=", 2);
            if (pair.length == 2 && !pair[0].isBlank() && !pair[1].isBlank()) {
                String code = pair[1].trim();
                String known = Languages.matchTarget(code);
                mapping.put(pair[0].trim(), known != null ? known : code);
            }
        }
        return new LanguageMapping(mapping);
    }

    /** Magnolia language → Supertext code, as stored (the SDK compares keys like codes). */
    public Map<String, String> entries() {
        return entries;
    }

    /** The stored code for the language (case and {@code _}/{@code -} ignored), else {@code null}. */
    public String codeFor(String language) {
        String key = key(language);
        String found = null;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (key(entry.getKey()).equals(key)) {
                found = entry.getValue();
            }
        }
        return found;
    }

    /** Whether the mapping has an entry for the language (case and {@code _}/{@code -} ignored). */
    public boolean maps(String language) {
        return codeFor(language) != null;
    }

    /**
     * What the combo box for a Magnolia language shows: the stored code, else the SDK's suggestion (exact match,
     * else default), else {@code null} ("— choose —").
     */
    public String selection(String language) {
        String stored = codeFor(language);
        return stored != null ? stored : Languages.suggestTarget(language);
    }

    /** The target code to send, or throws {@code unsupportedLanguage}. */
    public String target(String language) throws SupertextException {
        return SupertextService.targetCode(language, entries);
    }

    /** The source code to send, or throws {@code unsupportedLanguage}. */
    public String source(String language) throws SupertextException {
        return SupertextService.sourceCode(language, entries);
    }

    /**
     * How a Magnolia language is sent, for the settings screen.
     *
     * @param language   the Magnolia language, e.g. {@code de_CH}
     * @param code       the Supertext code it is sent as, or null when it is unsupported
     * @param mapped     true when the code comes from the mapping, false for an exact match or a default
     * @param politeness whether the target offers formal/informal ({@code null} for a source or an unknown code)
     * @param fromDefault true when the code is the SDK's default target for the language ({@code de} → {@code de-DE})
     */
    public record Status(String language, String code, boolean mapped, Boolean politeness, boolean fromDefault) {
        public boolean supported() {
            return code != null;
        }
    }

    /** How {@code language} is sent as a target ({@code asSource} = the site's main language, sent as source). */
    public Status status(String language, boolean asSource) {
        boolean mapped = maps(language);
        try {
            if (asSource) {
                return new Status(language, source(language), mapped, null, false);
            }
            String code = target(language);
            boolean fromDefault = !mapped && Languages.matchTarget(language) == null;
            return new Status(language, code, mapped, Languages.supportsPoliteness(code), fromDefault);
        } catch (SupertextException e) {
            return new Status(language, null, mapped, null, false);
        }
    }

    private static String key(String code) {
        return code == null ? "" : code.strip().replace('_', '-').toLowerCase(Locale.ROOT);
    }
}
