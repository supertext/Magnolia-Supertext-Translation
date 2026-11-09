package com.supertext.magnolia.translation.internal.sdk.v1;

import java.util.Map;
import java.util.Set;

/**
 * Internal: {@link HtmlDocument}'s helpers for {@code com.supertext.magnolia.translation.internal.sdk.files} (translation files), which
 * lives outside this package. Not part of the SDK's public API; it may change in any release.
 */
public final class HtmlSupport {

    private HtmlSupport() {
    }

    /** What {@link HtmlDocument#build} puts between {@code <div data-st-id>} and {@code </div>} for a segment. */
    public static String pack(String text, boolean html) {
        return HtmlDocument.content(new Segment(text, html));
    }

    /** Decodes the known entities: amp lt gt quot apos nbsp and numeric references to Unicode scalar values. */
    public static String decodeEntities(String text) {
        return HtmlDocument.decode(text);
    }

    /** Plain text from inner HTML, as {@link HtmlDocument#parse} reads a plain-text segment. */
    public static String toPlainText(String inner) {
        return HtmlDocument.plain(inner);
    }

    /** The inner HTML of the first element with each of these data-st-ids, as {@link HtmlDocument#parse} reads HTML segments. */
    public static Map<Integer, String> parseHtmlSegments(String html, Set<Integer> ids) {
        return HtmlDocument.parseHtml(html, ids);
    }
}
