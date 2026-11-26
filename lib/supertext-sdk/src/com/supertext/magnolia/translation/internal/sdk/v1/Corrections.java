package com.supertext.magnolia.translation.internal.sdk.v1;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The review service's two calls without HTTP (spec/corrections.json). */
final class Corrections {

    static final int PAGE_SIZE = 500;
    static final int MAX_PAGES = 100;
    static final int ACKNOWLEDGE_BATCH_SIZE = 1000;

    private static final Pattern VERSION = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private Corrections() {
    }

    /** One page of a pending-corrections answer. */
    static final class Page {
        final List<Correction> corrections;
        final String nextAfter;

        Page(List<Correction> corrections, String nextAfter) {
            this.corrections = corrections;
            this.nextAfter = nextAfter;
        }
    }

    static String path(String targetLang, String sourceLang, Politeness politeness, String after) {
        List<String> query = new ArrayList<>();
        add(query, "target_lang", targetLang);
        add(query, "source_lang", sourceLang);
        add(query, "politeness", politeness == null ? null : politeness.value());
        add(query, "after", after);
        add(query, "limit", String.valueOf(PAGE_SIZE));
        return "review/corrections?" + String.join("&", query);
    }

    private static void add(List<String> query, String name, String value) {
        if (value != null) {
            query.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
        }
    }

    static Page parsePage(String body) {
        List<Correction> out = new ArrayList<>();
        Object data;
        try {
            data = Json.parse(body);
        } catch (IllegalArgumentException e) {
            return new Page(out, null);
        }
        if (!(data instanceof Map) || !(((Map<?, ?>) data).get("corrections") instanceof List)) {
            return new Page(out, null);
        }
        Map<?, ?> page = (Map<?, ?>) data;
        for (Object raw : (List<?>) page.get("corrections")) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<?, ?> o = (Map<?, ?>) raw;
            String id = nonEmpty(o.get("id"));
            String stId = nonEmpty(o.get("st_id"));
            String target = nonEmpty(o.get("target_lang"));
            String html = nonEmpty(o.get("html"));
            Object version = o.get("version");
            if (id == null || stId == null || target == null || html == null
                    || !(version instanceof String) || !VERSION.matcher((String) version).matches()) {
                continue;
            }
            Object politeness = o.get("politeness");
            out.add(new Correction(id, stId, nonEmpty(o.get("source_lang")), target,
                    "more".equals(politeness) ? Politeness.MORE : "less".equals(politeness) ? Politeness.LESS : Politeness.DEFAULT,
                    str(o.get("source_html")), str(o.get("previous_html")), html, str(o.get("status")), str(o.get("corrected_at")), (String) version));
        }
        Object next = page.get("next_after");
        return new Page(out, next instanceof String && DIGITS.matcher((String) next).matches() ? (String) next : null);
    }

    static String acknowledgeBody(List<Correction> corrections) {
        List<Object> items = new ArrayList<>();
        for (Correction c : corrections) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("version", c.getVersion());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("corrections", items);
        return Json.write(body);
    }

    static int acknowledgedCount(String body) {
        try {
            Object data = Json.parse(body);
            Object n = data instanceof Map ? ((Map<?, ?>) data).get("acknowledged") : null;
            if (n instanceof Double) {
                double d = (Double) n;
                if (d >= 0 && d == Math.rint(d) && d <= Integer.MAX_VALUE) {
                    return (int) d;
                }
            }
        } catch (IllegalArgumentException e) {
            // Not JSON: nothing counted.
        }
        return 0;
    }

    private static String str(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static String nonEmpty(Object value) {
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }
}
