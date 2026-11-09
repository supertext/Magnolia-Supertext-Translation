package com.supertext.magnolia.translation.internal.sdk.files;

import com.supertext.magnolia.translation.internal.sdk.v1.HtmlSupport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translation files: export segments as XLIFF 1.2 or HTML, translate them outside the API (a CAT tool, a
 * translator, Supertext's web app), import them again ({@code spec/translation-file.json}, integration guide →
 * Translation files).
 *
 * <p>Not tied to an API version: nothing here talks to the API. The HTML file is the same document
 * {@code HtmlDocument.build} makes, plus metadata in its head.</p>
 *
 * <p>Imported files are untrusted. {@link #read} refuses DTDs and files over {@link #MAX_FILE_CHARACTERS};
 * {@link #importTranslations} takes markup only from the integration's current source, never from the file.</p>
 */
public final class TranslationFiles {

    /** Larger files are refused by {@link #read} (counted in Java {@code char}s, i.e. UTF-16 code units). */
    public static final int MAX_FILE_CHARACTERS = 10_000_000;

    private TranslationFiles() {
    }

    private static TranslationFileException fail(TranslationFileErrorCode code, String message) {
        return new TranslationFileException(code, message);
    }

    private static TranslationFileException invalidFile(String what) {
        return fail(TranslationFileErrorCode.INVALID_FILE, "not a well-formed XML file (" + what + ")");
    }

    // ------------------------------------------------------------------ characters

    /** JavaScript's whitespace (\s, trim): the reference implementation's definition. */
    private static boolean isSpace(char c) {
        switch (c) {
            case '\t': case '\n': case '\u000B': case '\f': case '\r': case ' ': case ' ': case ' ':
            case ' ': case ' ': case ' ': case ' ': case '　': case '﻿':
                return true;
            default:
                return c >= ' ' && c <= ' ';
        }
    }

    private static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static String trimStart(String s) {
        int start = 0;
        while (start < s.length() && isSpace(s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }

    /** Only space, tab, CR, LF, FF. */
    private static boolean isBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n' && c != '\f') {
                return false;
            }
        }
        return true;
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= '\u001F' || c == '\u007F') {
                return true;
            }
        }
        return false;
    }

    private static boolean isXmlChar(long cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    private static String normalizeLineBreaks(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ------------------------------------------------------------------ checksum

    /** FNV-1a (32 bit) of the packed segment's UTF-8 bytes, 8 lowercase hex digits. Detects changed sources; not a security check. */
    public static String checksum(FileSegment segment) {
        return checksum(segment.getText(), segment.isHtml());
    }

    /** The checksum of a segment's text ({@code html}: an HTML fragment). */
    public static String checksum(String text, boolean html) {
        String packed = HtmlSupport.pack(text, html);
        int hash = 0x811c9dc5;
        for (int i = 0; i < packed.length(); i++) {
            int cp = packed.codePointAt(i);
            if (Character.isSupplementaryCodePoint(cp)) {
                i++;
            } else if (Character.isSurrogate((char) cp)) {
                cp = 0xFFFD; // a lone surrogate is encoded as U+FFFD, as TextEncoder does
            }
            for (int b : utf8(cp)) {
                hash = (hash ^ b) * 0x01000193;
            }
        }
        return String.format("%08x", hash);
    }

    private static int[] utf8(int cp) {
        if (cp < 0x80) {
            return new int[] {cp};
        } else if (cp < 0x800) {
            return new int[] {0xC0 | (cp >> 6), 0x80 | (cp & 0x3F)};
        } else if (cp < 0x10000) {
            return new int[] {0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F)};
        }
        return new int[] {0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F)};
    }

    // ------------------------------------------------------------------ HTML fragments: tokens and inline codes

    private enum Kind { TEXT, START, END, COMMENT }

    private static final class Token {
        final Kind kind;
        /** Text for TEXT, the raw markup otherwise. */
        final String raw;
        /** Lower-case tag name for START and END. */
        final String name;
        final boolean selfClosing;
        /** Lower-case attribute names and values ({@code null}: no value). */
        final List<String[]> attrs;
        final int end;

        Token(Kind kind, String raw, String name, boolean selfClosing, List<String[]> attrs, int end) {
            this.kind = kind;
            this.raw = raw;
            this.name = name;
            this.selfClosing = selfClosing;
            this.attrs = attrs;
            this.end = end;
        }
    }

    private static final Set<String> VOID = new HashSet<>(List.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"));

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** End of a tag name ([a-zA-Z][a-zA-Z0-9:-]*) starting at {@code i}, or -1. */
    private static int tagNameEnd(String s, int i) {
        if (i >= s.length() || !isAsciiLetter(s.charAt(i))) {
            return -1;
        }
        int j = i + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (isAsciiLetter(c) || (c >= '0' && c <= '9') || c == ':' || c == '-') {
                j++;
            } else {
                break;
            }
        }
        return j;
    }

    /** [^\s"'>/=] */
    private static boolean isAttrNameChar(char c) {
        return !isSpace(c) && c != '"' && c != '\'' && c != '>' && c != '/' && c != '=';
    }

    /** [^\s"'=<>`] */
    private static boolean isUnquotedValueChar(char c) {
        return !isSpace(c) && c != '"' && c != '\'' && c != '=' && c != '<' && c != '>' && c != '`';
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && isSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * End of an optional attribute value ({@code \s*=\s*("…"|'…'|unquoted)}) starting at {@code i}, or -1 when
     * there is none; {@code value[0]} gets the value.
     */
    private static int attrValueEnd(String s, int i, String[] value) {
        int m = skipSpace(s, i);
        if (m >= s.length() || s.charAt(m) != '=') {
            return -1;
        }
        m = skipSpace(s, m + 1);
        if (m >= s.length()) {
            return -1;
        }
        char c = s.charAt(m);
        if (c == '"' || c == '\'') {
            int close = s.indexOf(c, m + 1);
            if (close < 0) {
                return -1;
            }
            value[0] = s.substring(m + 1, close);
            return close + 1;
        }
        int e = m;
        while (e < s.length() && isUnquotedValueChar(s.charAt(e))) {
            e++;
        }
        if (e == m) {
            return -1;
        }
        value[0] = s.substring(m, e);
        return e;
    }

    /** All attributes in {@code s}, scanning like a global regex search: names lower-cased, value {@code null} when absent. */
    private static List<String[]> scanAttributes(String s) {
        List<String[]> attrs = new ArrayList<>();
        int p = 0;
        while (p < s.length()) {
            if (!isAttrNameChar(s.charAt(p))) {
                p++;
                continue;
            }
            int q = p;
            while (q < s.length() && isAttrNameChar(s.charAt(q))) {
                q++;
            }
            String name = s.substring(p, q).toLowerCase(Locale.ROOT);
            String[] value = new String[1];
            int end = attrValueEnd(s, q, value);
            attrs.add(new String[] {name, end < 0 ? null : value[0]});
            p = end < 0 ? q : end;
        }
        return attrs;
    }

    /** The comment, end tag or start tag starting at {@code lt} (a {@code <}), or {@code null}. */
    private static Token matchTag(String s, int lt) {
        if (s.startsWith("<!--", lt)) {
            int end = s.indexOf("-->", lt + 4);
            return end < 0 ? null : new Token(Kind.COMMENT, s.substring(lt, end + 3), null, false, null, end + 3);
        }
        if (lt + 1 < s.length() && s.charAt(lt + 1) == '/') {
            int nameEnd = tagNameEnd(s, lt + 2);
            if (nameEnd < 0) {
                return null;
            }
            int j = skipSpace(s, nameEnd);
            if (j < s.length() && s.charAt(j) == '>') {
                return new Token(Kind.END, s.substring(lt, j + 1), s.substring(lt + 2, nameEnd).toLowerCase(Locale.ROOT), false, null, j + 1);
            }
            return null;
        }
        int nameEnd = tagNameEnd(s, lt + 1);
        if (nameEnd < 0) {
            return null;
        }
        int j = nameEnd;
        for (;;) {
            int k = skipSpace(s, j);
            if (k == j || k >= s.length() || !isAttrNameChar(s.charAt(k))) {
                break;
            }
            while (k < s.length() && isAttrNameChar(s.charAt(k))) {
                k++;
            }
            int valueEnd = attrValueEnd(s, k, new String[1]);
            j = valueEnd < 0 ? k : valueEnd;
        }
        int attrsEnd = j;
        j = skipSpace(s, j);
        boolean selfClosing = j < s.length() && s.charAt(j) == '/';
        if (selfClosing) {
            j++;
        }
        if (j >= s.length() || s.charAt(j) != '>') {
            return null;
        }
        return new Token(Kind.START, s.substring(lt, j + 1), s.substring(lt + 1, nameEnd).toLowerCase(Locale.ROOT), selfClosing,
                scanAttributes(s.substring(nameEnd, attrsEnd)), j + 1);
    }

    private static List<Token> tokenize(String html) {
        List<Token> tokens = new ArrayList<>();
        int last = 0;
        int from = 0;
        for (;;) {
            int lt = html.indexOf('<', from);
            if (lt < 0) {
                break;
            }
            Token t = matchTag(html, lt);
            if (t == null) {
                from = lt + 1;
                continue;
            }
            if (lt > last) {
                tokens.add(new Token(Kind.TEXT, html.substring(last, lt), null, false, null, lt));
            }
            tokens.add(t);
            last = t.end;
            from = t.end;
        }
        if (last < html.length()) {
            tokens.add(new Token(Kind.TEXT, html.substring(last), null, false, null, html.length()));
        }
        return tokens;
    }

    private enum CodeKind {
        BPT("bpt"), EPT("ept"), PH("ph");

        final String tag;

        CodeKind(String tag) {
            this.tag = tag;
        }
    }

    /** An inline code; {@code raw} is null in an XLIFF target (the file's code content is ignored). */
    private static final class Code {
        final CodeKind kind;
        final long id;
        final String raw;

        Code(CodeKind kind, long id, String raw) {
            this.kind = kind;
            this.id = id;
            this.raw = raw;
        }
    }

    /** An element in an XLIFF target that isn't an inline code. */
    private static final Object INVALID_CODE = new Object();

    /** A list of text (String, adjacent text merged) and codes. */
    private static final class Parts {
        final List<Object> list = new ArrayList<>();
        private StringBuilder text;

        void text(String s) {
            if (text == null) {
                text = new StringBuilder();
            }
            text.append(s);
        }

        void add(Object code) {
            flush();
            list.add(code);
        }

        List<Object> done() {
            flush();
            return list;
        }

        private void flush() {
            if (text != null) {
                list.add(text.toString());
                text = null;
            }
        }
    }

    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);");
    private static final Pattern KNOWN_ENTITY = Pattern.compile("amp|lt|gt|quot|apos|nbsp|#[xX][0-9a-fA-F]+|#[0-9]+");

    /**
     * The inline codes of an HTML segment: a start tag with its matching end tag becomes bpt/ept, everything else
     * (void and self-closing tags, unmatched tags, comments, entities XML can't hold) a ph. Ids count from 1 in
     * order of appearance; an ept has its bpt's id. Text has the known entities decoded.
     */
    private static List<Object> segmentParts(String html) {
        List<Token> tokens = tokenize(html);
        Map<Integer, Integer> endOf = new HashMap<>();
        Map<Integer, Integer> startOf = new HashMap<>();
        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind == Kind.START && !t.selfClosing && !VOID.contains(t.name)) {
                stack.push(i);
            } else if (t.kind == Kind.END && !stack.isEmpty() && tokens.get(stack.peek()).name.equals(t.name)) {
                int start = stack.pop();
                endOf.put(start, i);
                startOf.put(i, start);
            }
        }
        Parts parts = new Parts();
        Map<Integer, Long> ids = new HashMap<>();
        long next = 1;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind == Kind.TEXT) {
                String text = t.raw;
                Matcher m = ENTITY.matcher(text);
                int last = 0;
                while (m.find()) {
                    if (m.start() > last) {
                        parts.text(text.substring(last, m.start()));
                    }
                    String decoded = KNOWN_ENTITY.matcher(m.group(1)).matches() ? HtmlSupport.decodeEntities(m.group()) : m.group();
                    if (!decoded.equals(m.group()) && isXmlChar(decoded.codePointAt(0))) {
                        parts.text(decoded);
                    } else {
                        parts.add(new Code(CodeKind.PH, next++, m.group()));
                    }
                    last = m.end();
                }
                if (last < text.length()) {
                    parts.text(text.substring(last));
                }
            } else if (t.kind == Kind.START && endOf.containsKey(i)) {
                long id = next++;
                ids.put(i, id);
                parts.add(new Code(CodeKind.BPT, id, t.raw));
            } else if (t.kind == Kind.END && startOf.containsKey(i)) {
                parts.add(new Code(CodeKind.EPT, ids.get(startOf.get(i)), t.raw));
            } else {
                parts.add(new Code(CodeKind.PH, next++, t.raw));
            }
        }
        return parts.done();
    }

    // ------------------------------------------------------------------ export

    /** XML text: characters XML 1.0 can't hold dropped; & &lt; &gt; escaped, CR as &amp;#13;. */
    private static String xmlText(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                out.append(c).append(s.charAt(++i));
                continue;
            }
            if (Character.isSurrogate(c) || !isXmlChar(c)) {
                continue;
            }
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '\r': out.append("&#13;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static String xmlAttr(String s) {
        return xmlText(s).replace("\"", "&quot;");
    }

    private static String htmlAttr(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static List<FileSegment> checkJob(FileJob job) {
        String[][] fields = {
            {"plugin", job.getPlugin()}, {"reference", job.getReference()},
            {"sourceLanguage", job.getSourceLanguage()}, {"targetLanguage", job.getTargetLanguage()},
        };
        for (String[] field : fields) {
            if (trim(field[1]).isEmpty() || hasControl(field[1])) {
                throw fail(TranslationFileErrorCode.INVALID_JOB, field[0] + " must be a non-empty string without control characters");
            }
        }
        Set<String> keys = new HashSet<>();
        List<FileSegment> segments = new ArrayList<>();
        for (FileSegment segment : job.getSegments()) {
            if (segment.getKey().isEmpty() || hasControl(segment.getKey())) {
                throw fail(TranslationFileErrorCode.INVALID_JOB, "segment keys must be non-empty strings without control characters");
            }
            if (!keys.add(segment.getKey())) {
                throw fail(TranslationFileErrorCode.INVALID_JOB, "duplicate segment key \"" + segment.getKey() + "\"");
            }
        }
        for (FileSegment segment : job.getSegments()) {
            if (!isBlank(segment.getText())) {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) {
            throw fail(TranslationFileErrorCode.INVALID_JOB, "the job has nothing to translate (all segments blank)");
        }
        return segments;
    }

    private static String xliffSource(FileSegment segment) {
        if (!segment.isHtml()) {
            return xmlText(normalizeLineBreaks(segment.getText()));
        }
        StringBuilder out = new StringBuilder();
        for (Object p : segmentParts(segment.getText())) {
            if (p instanceof String) {
                out.append(xmlText((String) p));
            } else {
                Code c = (Code) p;
                out.append('<').append(c.kind.tag).append(" id=\"").append(c.id).append("\">").append(xmlText(c.raw))
                        .append("</").append(c.kind.tag).append('>');
            }
        }
        return out.toString();
    }

    /**
     * One XLIFF 1.2 file with one {@code <file>} per job. Blank segments are left out.
     *
     * @throws TranslationFileException {@code INVALID_JOB}: no jobs, an empty or blank metadata field or key, a control
     *                                  character, a duplicate key, a job with only blank segments
     */
    public static String exportXliff(List<FileJob> jobs) {
        if (jobs.isEmpty()) {
            throw fail(TranslationFileErrorCode.INVALID_JOB, "at least one job is needed");
        }
        StringBuilder out = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xliff version=\"1.2\" xmlns=\"urn:oasis:names:tc:xliff:document:1.2\">\n");
        for (FileJob job : jobs) {
            List<FileSegment> segments = checkJob(job);
            out.append("  <file original=\"").append(xmlAttr(job.getReference()))
                    .append("\" source-language=\"").append(xmlAttr(job.getSourceLanguage()))
                    .append("\" target-language=\"").append(xmlAttr(job.getTargetLanguage()))
                    .append("\" datatype=\"html\" product-name=\"").append(xmlAttr(job.getPlugin()))
                    .append("\" tool-id=\"supertext-sdk\">\n")
                    .append("    <header>\n      <tool tool-id=\"supertext-sdk\" tool-name=\"Supertext SDK\"/>\n    </header>\n    <body>\n");
            for (FileSegment segment : segments) {
                out.append("      <trans-unit id=\"").append(xmlAttr(segment.getKey())).append('"')
                        .append(segment.isHtml() ? "" : " datatype=\"plaintext\"")
                        .append(" extradata=\"").append(checksum(segment)).append("\" xml:space=\"preserve\">\n")
                        .append("        <source>").append(xliffSource(segment)).append("</source>\n      </trans-unit>\n");
            }
            out.append("    </body>\n  </file>\n");
        }
        return out.append("</xliff>\n").toString();
    }

    /**
     * The HTML document for one job (what {@code HtmlDocument.build} makes) with the job's metadata in its head.
     * Blank segments are left out.
     *
     * @throws TranslationFileException {@code INVALID_JOB}, as for {@link #exportXliff}
     */
    public static String exportHtml(FileJob job) {
        List<FileSegment> segments = checkJob(job);
        StringBuilder out = new StringBuilder("<!DOCTYPE html>\n<html lang=\"").append(htmlAttr(job.getSourceLanguage()))
                .append("\"><head><meta charset=\"utf-8\">\n")
                .append("<meta name=\"supertext:plugin\" content=\"").append(htmlAttr(job.getPlugin())).append("\">\n")
                .append("<meta name=\"supertext:reference\" content=\"").append(htmlAttr(job.getReference())).append("\">\n")
                .append("<meta name=\"supertext:source-language\" content=\"").append(htmlAttr(job.getSourceLanguage())).append("\">\n")
                .append("<meta name=\"supertext:target-language\" content=\"").append(htmlAttr(job.getTargetLanguage())).append("\">\n");
        for (int i = 0; i < segments.size(); i++) {
            FileSegment segment = segments.get(i);
            out.append("<meta name=\"supertext:segment\" content=\"").append(i).append(' ').append(checksum(segment)).append(' ')
                    .append(htmlAttr(segment.getKey())).append("\">\n");
        }
        out.append("</head><body>\n");
        for (int i = 0; i < segments.size(); i++) {
            FileSegment segment = segments.get(i);
            out.append("<div data-st-id=\"").append(i).append("\">").append(HtmlSupport.pack(segment.getText(), segment.isHtml()))
                    .append("</div>\n");
        }
        return out.append("</body></html>\n").toString();
    }

    // ------------------------------------------------------------------ reading files

    private static final class XElement {
        final String name;
        final Map<String, String> attrs = new HashMap<>();
        final List<Object> children = new ArrayList<>();

        XElement(String name) {
            this.name = name;
        }
    }

    /** XML name ([A-Za-z_:][-A-Za-z0-9_:.]*) at {@code i}, searched only up to {@code limit}; {@code null} if none. */
    private static String xmlName(String s, int i, int limit) {
        limit = Math.min(limit, s.length());
        if (i >= limit) {
            return null;
        }
        char c = s.charAt(i);
        if (!(isAsciiLetter(c) || c == '_' || c == ':')) {
            return null;
        }
        int j = i + 1;
        while (j < limit) {
            c = s.charAt(j);
            if (isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == ':' || c == '.') {
                j++;
            } else {
                break;
            }
        }
        return s.substring(i, j);
    }

    private static final Pattern XML_REF = Pattern.compile("&(?:#x([0-9a-fA-F]+)|#([0-9]+)|(amp|lt|gt|quot|apos));");

    private static String localName(String name) {
        return name.substring(name.lastIndexOf(':') + 1);
    }

    private static String decodeXml(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int amp = text.indexOf('&', i);
            if (amp < 0) {
                return out.append(text, i, text.length()).toString();
            }
            out.append(text, i, amp);
            Matcher m = XML_REF.matcher(text.substring(amp, Math.min(amp + 12, text.length())));
            if (!m.lookingAt()) {
                throw invalidFile("unknown entity");
            }
            if (m.group(3) != null) {
                switch (m.group(3)) {
                    case "amp": out.append('&'); break;
                    case "lt": out.append('<'); break;
                    case "gt": out.append('>'); break;
                    case "quot": out.append('"'); break;
                    default: out.append('\'');
                }
            } else {
                long cp = m.group(1) != null ? Long.parseLong(m.group(1), 16) : Long.parseLong(m.group(2));
                if (!isXmlChar(cp)) {
                    throw invalidFile("character reference");
                }
                out.appendCodePoint((int) cp);
            }
            i = amp + m.end();
        }
        return out.toString();
    }

    /** A small XML parser: elements, attributes, text, CDATA; comments and processing instructions skipped; no DTD. */
    private static XElement parseXml(String source) {
        String s = normalizeLineBreaks(source.startsWith("﻿") ? source.substring(1) : source);
        int i = 0;
        XElement root = null;
        Deque<XElement> stack = new ArrayDeque<>();
        while (i < s.length()) {
            int lt = s.indexOf('<', i);
            int textEnd = lt < 0 ? s.length() : lt;
            if (textEnd > i) {
                String text = s.substring(i, textEnd);
                if (!stack.isEmpty()) {
                    stack.peek().children.add(decodeXml(text));
                } else if (!isBlank(text)) {
                    throw invalidFile("text outside the root element");
                }
                if (text.contains("]]>")) {
                    throw invalidFile("]]> in text");
                }
            }
            if (lt < 0) {
                break;
            }
            if (s.startsWith("<!--", lt)) {
                int end = s.indexOf("-->", lt + 4);
                if (end < 0) {
                    throw invalidFile("unclosed comment");
                }
                i = end + 3;
            } else if (s.startsWith("<![CDATA[", lt)) {
                int end = s.indexOf("]]>", lt + 9);
                if (end < 0 || stack.isEmpty()) {
                    throw invalidFile("CDATA");
                }
                stack.peek().children.add(s.substring(lt + 9, end));
                i = end + 3;
            } else if (s.startsWith("<?", lt)) {
                int end = s.indexOf("?>", lt + 2);
                if (end < 0) {
                    throw invalidFile("unclosed processing instruction");
                }
                i = end + 2;
            } else if (s.startsWith("<!", lt)) {
                throw invalidFile("declarations are not allowed");
            } else if (s.startsWith("</", lt)) {
                String name = xmlName(s, lt + 2, s.length());
                int end = s.indexOf('>', lt);
                if (name == null || end < 0 || end < lt + 2 + name.length() || !trim(s.substring(lt + 2 + name.length(), end)).isEmpty()) {
                    throw invalidFile("end tag");
                }
                XElement open = stack.poll();
                if (open == null || !open.name.equals(name)) {
                    throw invalidFile("mismatched end tag");
                }
                i = end + 1;
            } else {
                String name = xmlName(s, lt + 1, s.length());
                if (name == null) {
                    throw invalidFile("start tag");
                }
                int j = lt + 1 + name.length();
                XElement element = new XElement(name);
                Set<String> seen = new HashSet<>();
                for (;;) {
                    int ws = 0;
                    while (ws < 200 && j + ws < s.length() && isSpace(s.charAt(j + ws))) {
                        ws++;
                    }
                    j += ws;
                    if (s.startsWith("/>", j) || (j < s.length() && s.charAt(j) == '>')) {
                        break;
                    }
                    if (ws == 0) {
                        throw invalidFile("attribute");
                    }
                    String attr = xmlName(s, j, j + 200);
                    if (attr == null) {
                        throw invalidFile("attribute");
                    }
                    j += attr.length();
                    // \s*=\s*(["']), within the next 200 characters
                    int limit = Math.min(j + 200, s.length());
                    int k = j;
                    while (k < limit && isSpace(s.charAt(k))) {
                        k++;
                    }
                    if (k >= limit || s.charAt(k) != '=') {
                        throw invalidFile("attribute value");
                    }
                    k++;
                    while (k < limit && isSpace(s.charAt(k))) {
                        k++;
                    }
                    if (k >= limit || (s.charAt(k) != '"' && s.charAt(k) != '\'')) {
                        throw invalidFile("attribute value");
                    }
                    char quote = s.charAt(k);
                    j = k + 1;
                    int close = s.indexOf(quote, j);
                    if (close < 0) {
                        throw invalidFile("attribute value");
                    }
                    String raw = s.substring(j, close);
                    if (raw.indexOf('<') >= 0) {
                        throw invalidFile("< in attribute value");
                    }
                    if (!seen.add(attr)) {
                        throw invalidFile("duplicate attribute");
                    }
                    element.attrs.put(localName(attr), decodeXml(raw.replace('\t', ' ').replace('\n', ' ')));
                    j = close + 1;
                }
                boolean selfClosing = s.charAt(j) == '/';
                i = j + (selfClosing ? 2 : 1);
                if (!stack.isEmpty()) {
                    stack.peek().children.add(element);
                } else if (root != null) {
                    throw invalidFile("more than one root element");
                } else {
                    root = element;
                }
                if (!selfClosing) {
                    stack.push(element);
                }
            }
        }
        if (root == null || !stack.isEmpty()) {
            throw invalidFile("unclosed element");
        }
        return root;
    }

    /** A unit's key, checksum and translation: XLIFF target parts or HTML inner HTML (either {@code null}: none). */
    static final class UnitData {
        final String key;
        final String checksum;
        final List<Object> xliffTarget;
        final String htmlTarget;

        UnitData(String key, String checksum, List<Object> xliffTarget, String htmlTarget) {
            this.key = key;
            this.checksum = checksum;
            this.xliffTarget = xliffTarget;
            this.htmlTarget = htmlTarget;
        }
    }

    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{8}");
    private static final Pattern CODE_ID = Pattern.compile("[1-9][0-9]{0,8}");

    private static boolean isElement(Object node, String name) {
        return node instanceof XElement && localName(((XElement) node).name).equals(name);
    }

    /** The target's text and codes; {@code <mrk>} unwrapped (iteratively: nesting depth is the file's choice). */
    private static void targetParts(List<Object> nodes, Parts out) {
        Deque<Iterator<Object>> stack = new ArrayDeque<>();
        stack.push(nodes.iterator());
        while (!stack.isEmpty()) {
            Iterator<Object> it = stack.peek();
            if (!it.hasNext()) {
                stack.pop();
                continue;
            }
            Object node = it.next();
            if (node instanceof String) {
                out.text((String) node);
                continue;
            }
            XElement e = (XElement) node;
            String name = localName(e.name);
            String id = e.attrs.getOrDefault("id", "");
            if (name.equals("mrk")) {
                stack.push(e.children.iterator());
            } else if ((name.equals("bpt") || name.equals("ept") || name.equals("ph")) && CODE_ID.matcher(id).matches()) {
                CodeKind kind = name.equals("bpt") ? CodeKind.BPT : name.equals("ept") ? CodeKind.EPT : CodeKind.PH;
                out.add(new Code(kind, Long.parseLong(id), null));
            } else {
                out.add(INVALID_CODE);
            }
        }
    }

    /** Every trans-unit in document order, also inside groups (iteratively); the first unit wins for an id. */
    private static void walkUnits(List<Object> nodes, Map<String, UnitData> units) {
        Deque<Iterator<Object>> stack = new ArrayDeque<>();
        stack.push(nodes.iterator());
        while (!stack.isEmpty()) {
            Iterator<Object> it = stack.peek();
            if (!it.hasNext()) {
                stack.pop();
                continue;
            }
            Object node = it.next();
            if (isElement(node, "group")) {
                stack.push(((XElement) node).children.iterator());
            } else if (isElement(node, "trans-unit")) {
                XElement unit = (XElement) node;
                String key = unit.attrs.get("id");
                if (key == null || key.isEmpty() || units.containsKey(key)) {
                    continue;
                }
                List<Object> target = null;
                for (Object child : unit.children) {
                    if (isElement(child, "target")) {
                        Parts parts = new Parts();
                        targetParts(((XElement) child).children, parts);
                        target = parts.done();
                        break;
                    }
                }
                String extradata = unit.attrs.getOrDefault("extradata", "");
                units.put(key, new UnitData(key, CHECKSUM.matcher(extradata).matches() ? extradata : null, target, null));
            }
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static List<TranslationFile> readXliff(String content) {
        XElement root = parseXml(content);
        if (!localName(root.name).equals("xliff")) {
            throw fail(TranslationFileErrorCode.INVALID_FILE, "not an XLIFF file");
        }
        List<TranslationFile> files = new ArrayList<>();
        for (Object node : root.children) {
            if (!isElement(node, "file")) {
                continue;
            }
            XElement file = (XElement) node;
            String original = file.attrs.get("original");
            String sourceLanguage = file.attrs.get("source-language");
            String targetLanguage = file.attrs.get("target-language");
            String plugin = file.attrs.get("product-name");
            if (isEmpty(original) || isEmpty(sourceLanguage) || isEmpty(targetLanguage) || isEmpty(plugin)) {
                throw fail(TranslationFileErrorCode.MISSING_METADATA,
                        "the <file> element lacks original, source-language, target-language or product-name");
            }
            Map<String, UnitData> units = new LinkedHashMap<>();
            for (Object body : file.children) {
                if (isElement(body, "body")) {
                    walkUnits(((XElement) body).children, units);
                }
            }
            files.add(register(FileFormat.XLIFF, plugin, original, sourceLanguage, targetLanguage, units));
        }
        if (files.isEmpty()) {
            throw fail(TranslationFileErrorCode.INVALID_FILE, "the XLIFF file has no <file> element");
        }
        return files;
    }

    private static final Pattern SEGMENT_META = Pattern.compile("([0-9]{1,9}) ([0-9a-f]{8}) ([\\s\\S]+)");

    /** The attribute strings of all {@code <meta ...>} tags (as /&lt;meta\b((?:[^>"']|"[^"]*"|'[^']*')*)>/gi finds them). */
    private static List<String> metaTags(String content) {
        List<String> metas = new ArrayList<>();
        int from = 0;
        while (from < content.length()) {
            int lt = content.indexOf('<', from);
            if (lt < 0) {
                break;
            }
            from = lt + 1;
            if (!content.regionMatches(true, lt + 1, "meta", 0, 4)) {
                continue;
            }
            int j = lt + 5;
            if (j < content.length()) {
                char c = content.charAt(j);
                if (isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '_') {
                    continue; // \b
                }
            }
            int start = j;
            int end = -1;
            while (j < content.length()) {
                char c = content.charAt(j);
                if (c == '>') {
                    end = j;
                    break;
                }
                if (c == '"' || c == '\'') {
                    int close = content.indexOf(c, j + 1);
                    if (close < 0) {
                        break;
                    }
                    j = close + 1;
                } else {
                    j++;
                }
            }
            if (end >= 0) {
                metas.add(content.substring(start, end));
                from = end + 1;
            }
        }
        return metas;
    }

    private static final class SegmentMeta {
        final int position;
        final String checksum;
        final String key;

        SegmentMeta(int position, String checksum, String key) {
            this.position = position;
            this.checksum = checksum;
            this.key = key;
        }
    }

    private static TranslationFile readHtml(String content) {
        Map<String, String> meta = new HashMap<>();
        List<SegmentMeta> segments = new ArrayList<>();
        for (String tag : metaTags(content)) {
            Map<String, String> attrs = new HashMap<>();
            for (String[] a : scanAttributes(tag)) {
                attrs.putIfAbsent(a[0], HtmlSupport.decodeEntities(a[1] == null ? "" : a[1]));
            }
            String name = attrs.getOrDefault("name", "").toLowerCase(Locale.ROOT);
            String value = attrs.get("content");
            if (!name.startsWith("supertext:") || value == null) {
                continue;
            }
            if (name.equals("supertext:segment")) {
                Matcher s = SEGMENT_META.matcher(value);
                if (s.matches()) {
                    segments.add(new SegmentMeta(Integer.parseInt(s.group(1)), s.group(2), s.group(3)));
                }
            } else {
                meta.putIfAbsent(name, value);
            }
        }
        String plugin = meta.get("supertext:plugin");
        String reference = meta.get("supertext:reference");
        String sourceLanguage = meta.get("supertext:source-language");
        String targetLanguage = meta.get("supertext:target-language");
        if (isEmpty(plugin) || isEmpty(reference) || isEmpty(sourceLanguage) || isEmpty(targetLanguage) || segments.isEmpty()) {
            throw fail(TranslationFileErrorCode.MISSING_METADATA, "the HTML file lacks the supertext:* metadata in its head");
        }
        Set<Integer> wanted = new HashSet<>();
        for (SegmentMeta s : segments) {
            wanted.add(s.position);
        }
        Map<Integer, String> inner = HtmlSupport.parseHtmlSegments(content, wanted);
        List<SegmentMeta> sorted = new ArrayList<>(segments);
        sorted.sort((a, b) -> Integer.compare(a.position, b.position));
        Map<String, UnitData> units = new LinkedHashMap<>();
        Set<Integer> positions = new HashSet<>();
        for (SegmentMeta s : sorted) {
            if (units.containsKey(s.key) || positions.contains(s.position)) {
                continue;
            }
            positions.add(s.position);
            units.put(s.key, new UnitData(s.key, s.checksum, null, inner.get(s.position)));
        }
        return register(FileFormat.HTML, plugin, reference, sourceLanguage, targetLanguage, units);
    }

    private static TranslationFile register(FileFormat format, String plugin, String reference, String sourceLanguage,
                                            String targetLanguage, Map<String, UnitData> units) {
        List<FileUnit> list = new ArrayList<>();
        for (UnitData u : units.values()) {
            list.add(new FileUnit(u.key, u.checksum));
        }
        return new TranslationFile(format, plugin, reference, sourceLanguage, targetLanguage, list, Collections.unmodifiableMap(units));
    }

    /**
     * Reads an exported file after translation: XLIFF (one result per {@code <file>}) or HTML (one result).
     *
     * @throws TranslationFileException {@code TOO_LARGE}; {@code INVALID_FILE} (neither XLIFF nor HTML, not well-formed,
     *                                  a DTD); {@code MISSING_METADATA}
     */
    public static List<TranslationFile> read(String content) {
        if (content.length() > MAX_FILE_CHARACTERS) {
            throw fail(TranslationFileErrorCode.TOO_LARGE, "the file is larger than " + MAX_FILE_CHARACTERS + " characters");
        }
        String start = trimStart(content.startsWith("﻿") ? content.substring(1) : content);
        start = start.substring(0, Math.min(200, start.length())).toLowerCase(Locale.ROOT);
        if (start.startsWith("<?xml") || start.startsWith("<xliff")) {
            return Collections.unmodifiableList(readXliff(content));
        }
        if (start.startsWith("<!doctype html")
                || (start.startsWith("<html") && start.length() > 5 && (isSpace(start.charAt(5)) || start.charAt(5) == '>'))) {
            return Collections.singletonList(readHtml(content));
        }
        throw fail(TranslationFileErrorCode.INVALID_FILE, "neither an XLIFF nor an HTML file");
    }

    // ------------------------------------------------------------------ importing

    private static String escapeHtmlText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Rebuilds an HTML segment from XLIFF target parts with the current source's codes; null if the target uses codes the source doesn't have. */
    private static String rebuildHtml(List<Object> parts, String source) {
        Map<String, String> codes = new HashMap<>();
        for (Object p : segmentParts(source)) {
            if (p instanceof Code) {
                Code c = (Code) p;
                codes.put(c.kind.tag + ":" + c.id, c.raw);
            }
        }
        Deque<Long> open = new ArrayDeque<>();
        Set<Long> used = new HashSet<>();
        StringBuilder out = new StringBuilder();
        for (Object p : parts) {
            if (p instanceof String) {
                out.append(escapeHtmlText((String) p));
                continue;
            }
            if (p == INVALID_CODE) {
                return null;
            }
            Code c = (Code) p;
            String raw = codes.get(c.kind.tag + ":" + c.id);
            if (raw == null) {
                return null;
            }
            if (c.kind == CodeKind.BPT) {
                if (!used.add(c.id)) {
                    return null;
                }
                open.push(c.id);
            } else if (c.kind == CodeKind.EPT) {
                Long top = open.poll();
                if (top == null || top != c.id) {
                    return null;
                }
            }
            out.append(raw);
        }
        return open.isEmpty() ? out.toString() : null;
    }

    private static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "action", "formaction", "poster", "cite", "xlink:href");
    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https", "mailto", "tel");

    private static boolean safeUrl(String value) {
        String decoded = HtmlSupport.decodeEntities(value);
        StringBuilder v = new StringBuilder(decoded.length());
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c > ' ' && c != '\u007F') {
                v.append(c);
            }
        }
        String lower = v.toString().toLowerCase(Locale.ROOT);
        int cut = lower.length();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String head = lower.substring(0, cut);
        if (head.indexOf('&') >= 0) {
            return false;
        }
        int colon = head.indexOf(':');
        return colon < 0 || SAFE_SCHEMES.contains(head.substring(0, colon));
    }

    private static boolean safeSrcset(String value) {
        for (String candidate : value.split(",", -1)) {
            String c = trim(candidate);
            int end = 0;
            while (end < c.length() && !isSpace(c.charAt(end))) {
                end++;
            }
            if (!safeUrl(c.substring(0, end))) {
                return false;
            }
        }
        return true;
    }

    /** A translated HTML segment from an HTML file, rebuilt; null if it has tags, attributes, comments or URLs the source doesn't allow. */
    private static String sanitizeHtml(String inner, String source) {
        Map<String, Set<String>> allowed = new HashMap<>();
        Set<String> comments = new HashSet<>();
        for (Token t : tokenize(source)) {
            if (t.kind == Kind.COMMENT) {
                comments.add(t.raw);
            } else if (t.kind != Kind.TEXT) {
                Set<String> names = allowed.computeIfAbsent(t.name, n -> new HashSet<>());
                if (t.kind == Kind.START) {
                    for (String[] a : t.attrs) {
                        names.add(a[0]);
                    }
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (Token t : tokenize(inner)) {
            if (t.kind == Kind.TEXT) {
                out.append(t.raw.replace("<", "&lt;"));
            } else if (t.kind == Kind.COMMENT) {
                if (!comments.contains(t.raw)) {
                    return null;
                }
                out.append(t.raw);
            } else {
                Set<String> names = allowed.get(t.name);
                if (names == null) {
                    return null;
                }
                if (t.kind == Kind.START) {
                    for (String[] a : t.attrs) {
                        if (!names.contains(a[0])) {
                            return null;
                        }
                        if (a[1] != null && URL_ATTRIBUTES.contains(a[0]) && !safeUrl(a[1])) {
                            return null;
                        }
                        if (a[1] != null && a[0].equals("srcset") && !safeSrcset(a[1])) {
                            return null;
                        }
                    }
                }
                out.append(t.raw);
            }
        }
        return out.toString();
    }

    /**
     * The translations for the integration's current segments (collected again from the item the file's
     * {@code reference} names), one per non-blank segment in their order. Markup comes from the current source
     * only: XLIFF codes are replaced by the source's tags; HTML translations may only use the source's tags,
     * attributes and comments, and safe URLs.
     *
     * @throws TranslationFileException {@code INVALID_FILE}: {@code file} wasn't returned by {@link #read}
     */
    public static List<ImportedSegment> importTranslations(TranslationFile file, List<FileSegment> current) {
        Map<String, UnitData> units = file.data;
        if (units == null) {
            throw fail(TranslationFileErrorCode.INVALID_FILE, "not a file returned by read()");
        }
        List<ImportedSegment> out = new ArrayList<>();
        for (FileSegment segment : current) {
            if (isBlank(segment.getText())) {
                continue;
            }
            UnitData unit = units.get(segment.getKey());
            if (unit == null) {
                out.add(new ImportedSegment(segment.getKey(), ImportStatus.MISSING, null));
                continue;
            }
            // text: the translation; null with untranslated false: invalid markup
            String text = null;
            boolean untranslated = false;
            if (unit.htmlTarget == null && unit.xliffTarget == null) {
                untranslated = true;
            } else if (unit.htmlTarget != null) {
                String target = unit.htmlTarget;
                if (segment.isHtml()) {
                    untranslated = isBlank(target);
                    text = untranslated ? null : sanitizeHtml(target, segment.getText());
                } else {
                    text = HtmlSupport.toPlainText(target);
                    untranslated = text.isEmpty();
                }
            } else {
                List<Object> target = unit.xliffTarget;
                boolean hasCode = false;
                boolean allBlank = true;
                StringBuilder joined = new StringBuilder();
                for (Object p : target) {
                    if (p instanceof String) {
                        joined.append((String) p);
                        allBlank &= isBlank((String) p);
                    } else {
                        hasCode = true;
                    }
                }
                if (!hasCode && allBlank) {
                    untranslated = true;
                } else if (segment.isHtml()) {
                    text = rebuildHtml(target, segment.getText());
                } else {
                    text = hasCode ? null : joined.toString();
                }
            }
            if (untranslated) {
                out.add(new ImportedSegment(segment.getKey(), ImportStatus.UNTRANSLATED, null));
            } else if (text == null) {
                out.add(new ImportedSegment(segment.getKey(), ImportStatus.INVALID_MARKUP, null));
            } else {
                ImportStatus status = checksum(segment).equals(unit.checksum) ? ImportStatus.OK : ImportStatus.SOURCE_CHANGED;
                out.add(new ImportedSegment(segment.getKey(), status, text));
            }
        }
        return out;
    }
}
