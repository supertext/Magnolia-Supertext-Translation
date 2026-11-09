package com.supertext.magnolia.translation.internal.sdk.v1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Packs segments into the HTML document sent to Supertext and reads them back.
 *
 * <p>Each segment becomes one {@code <div data-st-id="N">}. Supertext translates every such element
 * on its own, so a whole paragraph must be one segment, with formatting as inline tags: a segment per
 * formatted run breaks sentences apart.</p>
 */
public final class HtmlDocument {

    private static final String HEAD = "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"></head><body>\n";
    private static final String TAIL = "</body></html>\n";

    // Start tag of any element carrying data-st-id (double, single or no quotes).
    private static final Pattern START = Pattern.compile(
            "<([a-zA-Z][a-zA-Z0-9]*)(?=[\\s>/])[^>]*?\\sdata-st-id\\s*=\\s*(?:\"(\\d+)\"|'(\\d+)'|(\\d+))[^>]*>");
    private static final Pattern BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\n\\r\\f]+");
    private static final Pattern LINE_BREAK = Pattern.compile(" ?\u001e ?");
    private static final Pattern OUTER_SPACES = Pattern.compile("^ +| +$");
    private static final Pattern OUTER_WHITESPACE = Pattern.compile("^[ \\t\\n\\r\\f]+|[ \\t\\n\\r\\f]+$");
    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);");

    private HtmlDocument() {
    }

    /** The document for these segments; ids are the positions in the list. */
    public static String build(List<Segment> segments) {
        StringBuilder html = new StringBuilder(HEAD);
        for (int i = 0; i < segments.size(); i++) {
            html.append("<div data-st-id=\"").append(i).append("\">").append(content(segments.get(i))).append("</div>\n");
        }
        return html.append(TAIL).toString();
    }

    /**
     * Reads the translated segments back, in the order of {@code segments} (only their
     * {@link Segment#isHtml()} matters). A segment missing from the document is {@code null}.
     */
    public static List<String> parse(String html, List<Segment> segments) {
        String[] out = new String[segments.size()];
        scan(html, id -> id < out.length && out[id] == null,
                (id, inner) -> out[id] = segments.get(id).isHtml() ? trimOuter(inner) : plain(inner));
        return new ArrayList<>(Arrays.asList(out));
    }

    /**
     * The inner HTML (outer whitespace trimmed) of the first element with each of these ids; ids without an
     * element are absent. Same rules as {@link #parse} for HTML segments.
     */
    static Map<Integer, String> parseHtml(String html, Set<Integer> ids) {
        Map<Integer, String> out = new HashMap<>();
        scan(html, id -> ids.contains(id) && !out.containsKey(id), (id, inner) -> out.put(id, trimOuter(inner)));
        return out;
    }

    private interface Found {
        void accept(int id, String inner);
    }

    /** Finds the elements carrying data-st-id; {@code found} gets the inner HTML of those {@code wanted} accepts. */
    private static void scan(String html, IntPredicate wanted, Found found) {
        Matcher start = START.matcher(html);
        int position = 0;
        while (position <= html.length() && start.find(position)) {
            String tag = start.group(1).toLowerCase(Locale.ROOT);
            String digits = start.group(2) != null ? start.group(2) : start.group(3) != null ? start.group(3) : start.group(4);
            int contentStart = start.end();
            position = contentStart;

            // Find the matching end tag by counting nested elements with the same name.
            Matcher tags = Pattern.compile("<(/?)" + tag + "(?=[\\s>/])[^>]*?(/?)>", Pattern.CASE_INSENSITIVE).matcher(html);
            int depth = 1;
            int end = -1;
            int after = -1;
            int from = contentStart;
            while (tags.find(from)) {
                if (!tags.group(1).isEmpty()) {
                    depth--;
                } else if (tags.group(2).isEmpty()) {
                    depth++;
                }
                if (depth == 0) {
                    end = tags.start();
                    after = tags.end();
                    break;
                }
                from = tags.end();
            }
            if (end < 0) {
                continue; // no closing tag: ignore this element
            }
            int id = digits.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(digits);
            if (wanted.test(id)) {
                found.accept(id, html.substring(contentStart, end));
            }
            position = after;
        }
    }

    private static String trimOuter(String inner) {
        return OUTER_WHITESPACE.matcher(inner).replaceAll("");
    }

    /** What goes between {@code <div data-st-id>} and {@code </div>} for a segment. */
    static String content(Segment segment) {
        if (segment.isHtml()) {
            return segment.getText();
        }
        String text = segment.getText().replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                case '\n': out.append("<br>"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    /** Plain text from translated inner HTML: line breaks from {@code <br>}, tags removed, whitespace collapsed, entities decoded. */
    static String plain(String inner) {
        String s = BR.matcher(inner).replaceAll("\u001e");
        s = TAG.matcher(s).replaceAll("");
        s = WHITESPACE.matcher(s).replaceAll(" ");
        s = LINE_BREAK.matcher(s).replaceAll("\n");
        s = OUTER_SPACES.matcher(s).replaceAll("");
        return decode(s);
    }

    /** Decodes the known entities (amp lt gt quot apos nbsp, numeric references to Unicode scalar values). */
    static String decode(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            out.append(entity(m.group(1), m.group()));
            last = m.end();
        }
        return out.append(s, last, s.length()).toString();
    }

    private static String entity(String name, String original) {
        switch (name) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return "\u00a0";
            default:
                boolean hex = name.charAt(1) == 'x' || name.charAt(1) == 'X';
                String digits = name.substring(hex ? 2 : 1);
                long n;
                try {
                    n = Long.parseLong(digits, hex ? 16 : 10);
                } catch (NumberFormatException e) {
                    return original; // too long to be a code point
                }
                // Only Unicode scalar values: &#0;, surrogates and values above U+10FFFF stay as written.
                boolean scalar = n > 0 && n <= 0x10FFFF && (n < 0xD800 || n > 0xDFFF);
                return scalar ? new String(Character.toChars((int) n)) : original;
        }
    }
}
