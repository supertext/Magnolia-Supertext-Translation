package com.supertext.magnolia.translation.internal.sdk.v1;

import com.supertext.magnolia.translation.internal.sdk.JdkHttpTransport;
import com.supertext.magnolia.translation.internal.sdk.Transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Client for the Supertext AI file translation API v1.
 *
 * <p>The protocol: upload one HTML document, poll its status, download the translation, delete the
 * file. On top of it, {@link #translateTexts} packs texts into documents and reads them back. The
 * client applies the lessons from Supertext's CMS plugins: a pasted {@code Supertext-Auth-Key} prefix
 * is stripped, HTTP 429 is retried, and the file part is sent as exactly {@code text/html}. Language
 * codes are sent as given (trimmed): resolve CMS languages with {@link Languages#target} and
 * {@link Languages#source} first.</p>
 *
 * <p>Instances are immutable and thread-safe. Calls block; documents are translated one after the
 * other because the API limits requests per second per key.</p>
 */
public final class SupertextClient {

    /** The live API. */
    public static final String LIVE_BASE_URL = "https://api.supertext.com/v1/";

    /** Characters per document in {@link #translateTexts}, below the API's limit of 1,000,000. */
    public static final int DEFAULT_MAX_CHARACTERS = 900_000;

    private static final String SDK_USER_AGENT = "supertext-sdk-java";
    private static final String AUTH_PREFIX = "Supertext-Auth-Key ";
    private static final Pattern PASTED_PREFIX = Pattern.compile("^supertext-auth-key(\\s+|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETRY_AFTER_SECONDS = Pattern.compile("^[0-9]+(\\.[0-9]+)?$");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final long MAX_RETRY_DELAY_MS = 30_000;

    /** Waits between polls and retries; replace it in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final String apiKey;
    private final String baseUrl;
    private final Duration pollInterval;
    private final Duration timeout;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final String userAgent;
    private final Transport transport;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;
    private final Clock wallClock;

    /** A client for the live API with the default options. */
    public SupertextClient(String apiKey) {
        this(builder(apiKey));
    }

    private SupertextClient(Builder b) {
        this.apiKey = b.apiKey == null ? "" : b.apiKey;
        String url = b.baseUrl == null || b.baseUrl.isBlank() ? LIVE_BASE_URL : b.baseUrl.trim();
        this.baseUrl = url.endsWith("/") ? url : url + "/";
        this.pollInterval = b.pollInterval;
        this.timeout = b.timeout;
        this.requestTimeout = b.requestTimeout;
        this.maxRetries = b.maxRetries;
        this.userAgent = b.userAgent == null || b.userAgent.isBlank()
                ? SDK_USER_AGENT : SDK_USER_AGENT + " " + b.userAgent.trim();
        this.transport = b.transport != null ? b.transport : new JdkHttpTransport();
        this.sleeper = b.sleeper != null ? b.sleeper : d -> Thread.sleep(d.toMillis());
        this.nanoTime = b.nanoTime != null ? b.nanoTime : System::nanoTime;
        this.wallClock = b.wallClock != null ? b.wallClock : Clock.systemUTC();
    }

    /**
     * Options for a client. A blank key doesn't fail here: the first request throws
     * {@link ErrorCode#MISSING_API_KEY} without sending anything.
     */
    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    // ---------------------------------------------------------------- static helpers

    /**
     * The {@code Authorization} header value for a key as an administrator pasted it: Supertext
     * shows the key with its prefix, so a pasted prefix is stripped and exactly one is sent.
     *
     * @throws SupertextException {@link ErrorCode#MISSING_API_KEY} for a blank key
     */
    public static String authHeader(String apiKey) {
        String key = apiKey == null ? "" : apiKey.trim();
        key = PASTED_PREFIX.matcher(key).replaceFirst("").trim();
        if (key.isEmpty()) {
            throw new SupertextException(ErrorCode.MISSING_API_KEY);
        }
        return AUTH_PREFIX + key;
    }

    /**
     * Milliseconds to wait before retrying an HTTP 429. A {@code Retry-After} in seconds wins (capped
     * at 30 s); otherwise 1, 2, 4, 8 s plus 0–249 ms jitter, so clients sharing a key spread out.
     *
     * @param attempt    0-based retry number
     * @param retryAfter the {@code Retry-After} header, or {@code null}
     */
    public static long retryDelayMs(int attempt, String retryAfter) {
        return retryDelayMs(attempt, retryAfter, ThreadLocalRandom.current());
    }

    /** {@link #retryDelayMs(int, String)} with your own randomness for the jitter. */
    public static long retryDelayMs(int attempt, String retryAfter, Random random) {
        if (retryAfter != null) {
            String value = trimAscii(retryAfter);
            // Only seconds; HTTP dates and garbage fall back to the backoff.
            if (RETRY_AFTER_SECONDS.matcher(value).matches()) {
                double ms = Double.parseDouble(value) * 1000;
                return Math.min(MAX_RETRY_DELAY_MS, Math.round(ms));
            }
        }
        // 1000 × 2^attempt; past attempt 5 the cap applies anyway, so the shift is bounded to avoid overflow.
        long backoff = 1000L << Math.max(0, Math.min(attempt, 20));
        return Math.min(MAX_RETRY_DELAY_MS, backoff + random.nextInt(250));
    }

    // ---------------------------------------------------------------- API calls

    /**
     * The language pairs the API offers right now, with their features ({@code supports_politeness}, …): the
     * live version of {@link Languages#SOURCES}/{@link Languages#TARGETS}, e.g. for a settings page. Free of
     * charge. Malformed entries are skipped.
     */
    public List<FeatureSupport> getFeatures() throws InterruptedException {
        List<FeatureSupport> out = new ArrayList<>();
        Object list = jsonObjectField(request("GET", "features", null, null).getBody(), "supported_features");
        if (!(list instanceof List)) {
            return out;
        }
        for (Object item : (List<?>) list) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> o = (Map<?, ?>) item;
            Object source = o.get("source_lang");
            Object target = o.get("target_lang");
            if (!(source instanceof String) || !(target instanceof String)) {
                continue;
            }
            List<String> features = new ArrayList<>();
            if (o.get("features") instanceof List) {
                for (Object feature : (List<?>) o.get("features")) {
                    if (feature instanceof String) {
                        features.add((String) feature);
                    }
                }
            }
            out.add(new FeatureSupport((String) source, (String) target, features));
        }
        return out;
    }

    /** Checks that the key works ({@code GET features}, free of charge). */
    public void validateApiKey() throws InterruptedException {
        request("GET", "features", null, null);
    }

    /**
     * The automatic API key check (spec/api-key-check.json): {@code GET features} with a
     * timeout of at most {@link ApiKeyCheck#TIMEOUT} and the usual 429 retries. Never throws:
     * 2xx is {@code ok}, 401/403 {@code invalid}, 5xx or no answer {@code unreachable}, anything else
     * {@code error}. A blank key gives {@code missing} without a request. If the thread is interrupted,
     * the check is {@code unreachable} and the interrupt flag stays set.
     */
    public ApiKeyCheck checkApiKey() {
        String checkedAt = ApiKeyCheck.isoSeconds(wallClock.instant());
        String fingerprint = ApiKeyCheck.fingerprint(apiKey);
        if (fingerprint.isEmpty()) {
            return new ApiKeyCheck(ApiKeyCheck.Status.MISSING, checkedAt, null, null, fingerprint);
        }
        Duration checkTimeout = requestTimeout.compareTo(ApiKeyCheck.TIMEOUT) < 0 ? requestTimeout : ApiKeyCheck.TIMEOUT;
        try {
            Transport.Response response = request("GET", "features", null, null, checkTimeout);
            return new ApiKeyCheck(ApiKeyCheck.Status.OK, checkedAt, response.getStatus(), null, fingerprint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ApiKeyCheck(ApiKeyCheck.Status.UNREACHABLE, checkedAt, null, null, fingerprint);
        } catch (RuntimeException e) {
            return ApiKeyCheck.fromError(e, checkedAt, fingerprint);
        }
    }

    /** {@link #submit(String, String, String, Politeness)} with auto-detected source and default politeness. */
    public String submit(String html, String targetLang) throws InterruptedException {
        return submit(html, targetLang, null, Politeness.DEFAULT);
    }

    /**
     * Uploads an HTML document for translation.
     *
     * <p>The language codes are sent as given (trimmed): resolve CMS languages with {@link Languages#target}
     * and {@link Languages#source} first.</p>
     *
     * @param targetLang a Supertext target code, e.g. {@code de-CH} (see {@link Languages#TARGETS})
     * @param sourceLang a Supertext source code, e.g. {@code en}, or {@code null}/empty to auto-detect
     * @return the file id
     * @throws SupertextException {@link ErrorCode#UNSUPPORTED_LANGUAGE} for an empty target, without a request
     */
    public String submit(String html, String targetLang, String sourceLang, Politeness politeness) throws InterruptedException {
        Objects.requireNonNull(html, "html");
        String target = targetLang == null ? "" : targetLang.strip();
        if (target.isEmpty()) {
            throw new SupertextException(ErrorCode.UNSUPPORTED_LANGUAGE);
        }
        String source = sourceLang == null ? "" : sourceLang.strip();
        String boundary = "----SupertextSdk" + UUID.randomUUID().toString().replace("-", "");
        Multipart form = new Multipart(boundary);
        // Field order as the API documents it: target_lang, source_lang, politeness, file.
        form.field("target_lang", target);
        if (!source.isEmpty()) { // omitted: auto-detect
            form.field("source_lang", source);
        }
        if (politeness == Politeness.MORE || politeness == Politeness.LESS) {
            form.field("politeness", politeness.value());
        }
        // Exactly "text/html": a charset parameter gets HTTP 415.
        form.file("file", "content.html", "text/html", html.getBytes(StandardCharsets.UTF_8));

        Transport.Response response = request("POST", "translate/ai/file", form.finish(), "multipart/form-data; boundary=" + boundary);
        String fileId = Json.stringField(response.getBody(), "file_id");
        if (fileId == null || fileId.isEmpty()) {
            throw new SupertextException(ErrorCode.NO_FILE_ID);
        }
        return fileId;
    }

    /** The file's status: {@code translating}, {@code done}, {@code error}, {@code limit_exceeded}, {@code deleted}, or others in future. */
    public String getStatus(String fileId) throws InterruptedException {
        Transport.Response response = request("GET", filePath(fileId) + "/status", null, null);
        String status = Json.stringField(response.getBody(), "status");
        return status == null ? "" : status;
    }

    /**
     * Polls until the file is done: first poll right away, then every poll interval, until the
     * timeout. Unknown statuses keep polling.
     *
     * @throws SupertextException {@code translation_error}, {@code quota_exceeded}, {@code file_deleted}, {@code timeout}, or an HTTP error
     */
    public void waitUntilDone(String fileId) throws InterruptedException {
        long start = nanoTime.getAsLong();
        while (true) {
            String status = getStatus(fileId);
            switch (status) {
                case "done":
                    return;
                case "error":
                    throw new SupertextException(ErrorCode.TRANSLATION_ERROR);
                case "limit_exceeded":
                    throw new SupertextException(ErrorCode.QUOTA_EXCEEDED);
                case "deleted":
                    throw new SupertextException(ErrorCode.FILE_DELETED);
                default:
                    break; // translating, queued, or a status this SDK doesn't know yet
            }
            long nextPoll = nanoTime.getAsLong() - start + pollInterval.toNanos();
            if (nextPoll > timeout.toNanos()) {
                throw new SupertextException(ErrorCode.TIMEOUT);
            }
            sleeper.sleep(pollInterval);
        }
    }

    /**
     * Downloads the translated HTML.
     *
     * @throws SupertextException {@code incomplete_response} when the answer is empty
     */
    public String download(String fileId) throws InterruptedException {
        String body = request("GET", filePath(fileId) + "/translation", null, null).getBody();
        if (body.isBlank()) {
            throw new SupertextException(ErrorCode.INCOMPLETE_RESPONSE, null, "empty document", null, null, null, null, null);
        }
        return body;
    }

    /** Deletes the file at Supertext. Never throws: files expire after 24 h anyway. */
    public void delete(String fileId) {
        // Run even when the thread was interrupted (e.g. a cancelled wait), so the file doesn't linger.
        boolean interrupted = Thread.interrupted();
        try {
            request("DELETE", filePath(fileId), null, null);
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (RuntimeException e) {
            // Ignore: best effort.
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** {@link #translateHtml(String, String, String, Politeness)} with auto-detected source and default politeness. */
    public String translateHtml(String html, String targetLang) throws InterruptedException {
        return translateHtml(html, targetLang, null, Politeness.DEFAULT);
    }

    /** {@link #translateHtml(String, String, String, Politeness)} with default politeness. */
    public String translateHtml(String html, String targetLang, String sourceLang) throws InterruptedException {
        return translateHtml(html, targetLang, sourceLang, Politeness.DEFAULT);
    }

    /**
     * Translates one HTML document: upload, wait, download, and always delete the file afterwards.
     * Keep each text that belongs together in one {@code data-st-id} element (see {@link HtmlDocument}).
     */
    public String translateHtml(String html, String targetLang, String sourceLang, Politeness politeness) throws InterruptedException {
        String fileId = submit(html, targetLang, sourceLang, politeness);
        try {
            waitUntilDone(fileId);
            return download(fileId);
        } finally {
            delete(fileId);
        }
    }

    /** {@link #translateTexts(Map, String, String, Politeness, int)} with auto-detected source and default politeness. */
    public Map<String, String> translateTexts(Map<String, Segment> segments, String targetLang) throws InterruptedException {
        return translateTexts(segments, targetLang, null, Politeness.DEFAULT, DEFAULT_MAX_CHARACTERS);
    }

    /** {@link #translateTexts(Map, String, String, Politeness, int)} with default politeness. */
    public Map<String, String> translateTexts(Map<String, Segment> segments, String targetLang, String sourceLang)
            throws InterruptedException {
        return translateTexts(segments, targetLang, sourceLang, Politeness.DEFAULT, DEFAULT_MAX_CHARACTERS);
    }

    /** {@link #translateTexts(Map, String, String, Politeness, int)} with the default document size. */
    public Map<String, String> translateTexts(Map<String, Segment> segments, String targetLang, String sourceLang,
                                              Politeness politeness) throws InterruptedException {
        return translateTexts(segments, targetLang, sourceLang, politeness, DEFAULT_MAX_CHARACTERS);
    }

    /**
     * Translates texts: packs them into as few documents as fit {@code maxCharacters}, translates the
     * documents one after the other and reads the texts back. Blank texts come back unchanged and
     * aren't sent; when nothing is left, no request is made.
     *
     * @param segments key → segment; use a {@link LinkedHashMap} to keep your order
     * @return key → translated text, in the order of {@code segments}
     * @throws SupertextException {@code incomplete_response} (detail {@code "missing: key1, key2"}) when texts are missing from the answer
     */
    public Map<String, String> translateTexts(Map<String, Segment> segments, String targetLang, String sourceLang,
                                              Politeness politeness, int maxCharacters) throws InterruptedException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Segment> entry : segments.entrySet()) {
            if (isBlank(entry.getValue().getText())) {
                result.put(entry.getKey(), entry.getValue().getText());
            } else {
                result.put(entry.getKey(), null); // placeholder keeps the input order
            }
        }
        for (List<String> batch : batches(segments, maxCharacters)) {
            List<Segment> documentSegments = new ArrayList<>(batch.size());
            for (String key : batch) {
                documentSegments.add(segments.get(key));
            }
            String translated = translateHtml(HtmlDocument.build(documentSegments), targetLang, sourceLang, politeness);
            List<String> texts = HtmlDocument.parse(translated, documentSegments);
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                if (texts.get(i) == null) {
                    missing.add(batch.get(i));
                } else {
                    result.put(batch.get(i), texts.get(i));
                }
            }
            if (!missing.isEmpty()) {
                throw new SupertextException(ErrorCode.INCOMPLETE_RESPONSE, null,
                        "missing: " + String.join(", ", missing), null, null, null, null, null);
            }
        }
        return result;
    }

    /**
     * Splits the non-blank segments into documents (spec/batching.json): in order, while the packed
     * content length (code points) stays at or below {@code maxCharacters}; an oversized segment goes alone.
     */
    static List<List<String>> batches(Map<String, Segment> segments, int maxCharacters) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        long size = 0;
        for (Map.Entry<String, Segment> entry : segments.entrySet()) {
            Segment segment = entry.getValue();
            if (isBlank(segment.getText())) {
                continue;
            }
            String content = HtmlDocument.content(segment);
            long length = content.codePointCount(0, content.length());
            if (!current.isEmpty() && size + length > maxCharacters) {
                batches.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(entry.getKey());
            size += length;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    // ---------------------------------------------------------------- HTTP

    /** All pending corrections; see {@link #pendingCorrections(String, String, Politeness)}. */
    public List<Correction> pendingCorrections() throws InterruptedException {
        return pendingCorrections(null, null, null);
    }

    /**
     * Corrections reviewers made that the integration hasn't written into the CMS yet (Supertext Localization
     * Review; spec/corrections.json). Only the review service answers this: against Supertext's API it throws
     * {@link ErrorCode#NOT_FOUND}. Follows all pages. {@link Correction#getSegmentId()} is the
     * {@code data-st-id} the integration sent, so this is useful when the integration builds its documents with
     * its own ids ({@link #translateHtml(String, String)}).
     *
     * @param targetLang only this target language; {@code null}: all
     * @param sourceLang only this source language; {@code null}: all
     * @param politeness only this politeness; {@code null}: all
     */
    public List<Correction> pendingCorrections(String targetLang, String sourceLang, Politeness politeness)
            throws InterruptedException {
        List<Correction> all = new ArrayList<>();
        String after = null;
        for (int page = 0; page < Corrections.MAX_PAGES; page++) {
            Corrections.Page result = Corrections.parsePage(
                    request("GET", Corrections.path(targetLang, sourceLang, politeness, after), null, null).getBody());
            all.addAll(result.corrections);
            if (result.nextAfter == null || result.nextAfter.equals(after)) {
                break;
            }
            after = result.nextAfter;
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * Tells the review service these corrections are in the CMS now; returns how many it accepted. Only the
     * fetched {@link Correction#getVersion()} counts: a newer correction stays pending.
     */
    public int acknowledgeCorrections(List<Correction> corrections) throws InterruptedException {
        int total = 0;
        for (int i = 0; i < corrections.size(); i += Corrections.ACKNOWLEDGE_BATCH_SIZE) {
            String body = Corrections.acknowledgeBody(corrections.subList(i, Math.min(corrections.size(), i + Corrections.ACKNOWLEDGE_BATCH_SIZE)));
            total += Corrections.acknowledgedCount(request("POST", "review/corrections/acknowledge",
                    body.getBytes(StandardCharsets.UTF_8), "application/json").getBody());
        }
        return total;
    }

    private Transport.Response request(String method, String path, byte[] body, String contentType) throws InterruptedException {
        return request(method, path, body, contentType, requestTimeout);
    }

    private Transport.Response request(String method, String path, byte[] body, String contentType, Duration timeout)
            throws InterruptedException {
        String authorization = authHeader(apiKey); // throws missing_api_key before anything is sent
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", authorization);
        headers.put("Accept", "application/json");
        headers.put("User-Agent", userAgent);
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        Transport.Request request = new Transport.Request(method, URI.create(baseUrl + path), headers, body, timeout);

        for (int attempt = 0; ; attempt++) {
            Transport.Response response;
            try {
                response = transport.send(request);
            } catch (IOException e) {
                String detail = scrub(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                throw new SupertextException(ErrorCode.TRANSPORT_ERROR, null, detail, null, null, null, null, e);
            }
            int status = response.getStatus();
            if (status >= 200 && status < 300) {
                return response;
            }
            if (status == 429 && attempt < maxRetries) {
                sleeper.sleep(Duration.ofMillis(retryDelayMs(attempt, response.getHeader("Retry-After"))));
                continue;
            }
            throw httpError(status, response.getBody());
        }
    }

    /** Maps an HTTP error answer to a {@link SupertextException} (spec/errors.json). */
    SupertextException httpError(int status, String body) {
        String text = body == null ? "" : body;
        String apiErrorCode = Json.stringField(text, "error_code");
        String detail = scrub(trimAscii(TAGS.matcher(text).replaceAll("")));
        if (detail.codePointCount(0, detail.length()) > 200) {
            detail = detail.substring(0, detail.offsetByCodePoints(0, 200));
        }
        if (detail.isEmpty()) {
            detail = null;
        }
        ErrorCode code;
        String sourceLang = null;
        String targetLang = null;
        String feature = null;
        if ((status == 400 || status == 404) && text.contains("INVALID_LANGUAGE_PAIR")) {
            code = ErrorCode.INVALID_LANGUAGE_PAIR;
            sourceLang = Json.stringField(text, "source_lang");
            targetLang = Json.stringField(text, "target_lang");
        } else if (status == 400 && "FEATURE_ERROR".equals(apiErrorCode)) {
            code = ErrorCode.UNSUPPORTED_FEATURE;
            sourceLang = Json.stringField(text, "source_lang");
            targetLang = Json.stringField(text, "target_lang");
            feature = Json.stringField(text, "feature");
        } else if (status == 401 || status == 403) {
            code = ErrorCode.AUTHENTICATION_FAILURE;
        } else if (status == 404) {
            code = ErrorCode.NOT_FOUND;
        } else if (status == 413) {
            code = ErrorCode.PAYLOAD_TOO_LARGE;
        } else if (status == 429) {
            code = ErrorCode.TOO_MANY_REQUESTS;
        } else if (status >= 500 && status <= 599) {
            code = ErrorCode.SERVICE_UNAVAILABLE;
        } else {
            code = ErrorCode.UNEXPECTED_STATUS;
        }
        return new SupertextException(code, status, detail, apiErrorCode, sourceLang, targetLang, feature, null);
    }

    /** Removes the API key from text that leaves the SDK, in case an answer or error echoes it. */
    private String scrub(String text) {
        String key = PASTED_PREFIX.matcher(apiKey.trim()).replaceFirst("").trim();
        return key.length() >= 4 ? text.replace(key, "***") : text;
    }

    /** The field {@code name} of a JSON object text, or {@code null} (not JSON, not an object, or missing). */
    private static Object jsonObjectField(String text, String name) {
        try {
            Object value = Json.parse(text);
            return value instanceof Map ? ((Map<?, ?>) value).get(name) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String filePath(String fileId) {
        // URLEncoder is for forms; a path needs %20, not +.
        return "translate/ai/file/" + URLEncoder.encode(Objects.requireNonNull(fileId, "fileId"), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Removes leading and trailing ASCII whitespace (space, tab, CR, LF, FF), unlike {@link String#trim()}. */
    private static String trimAscii(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isAsciiSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isAsciiSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isAsciiSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
    }

    /** Empty or only ASCII whitespace (space, tab, CR, LF, FF). */
    private static boolean isBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!isAsciiSpace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "SupertextClient{baseUrl=" + baseUrl + "}";
    }

    // ---------------------------------------------------------------- builder

    /** Options for {@link SupertextClient}. */
    public static final class Builder {
        private final String apiKey;
        private String baseUrl = LIVE_BASE_URL;
        private Duration pollInterval = Duration.ofSeconds(2);
        private Duration timeout = Duration.ofSeconds(180);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private int maxRetries = 4;
        private String userAgent;
        private Transport transport;
        private Sleeper sleeper;
        private LongSupplier nanoTime;
        private Clock wallClock;

        private Builder(String apiKey) {
            this.apiKey = apiKey;
        }

        /** API base URL (default {@link #LIVE_BASE_URL}); a trailing slash is added. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Time between status polls (default 2 s). */
        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = requirePositive(pollInterval, "pollInterval");
            return this;
        }

        /** How long to wait for one document (default 180 s). */
        public Builder timeout(Duration timeout) {
            this.timeout = requirePositive(timeout, "timeout");
            return this;
        }

        /** Timeout of each HTTP request (default 30 s). */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
            return this;
        }

        /** Retries after HTTP 429 (default 4). */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /** Appended to the User-Agent, e.g. {@code "MyCms-Supertext/1.2.0"}. */
        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /** How requests are sent (default {@link JdkHttpTransport}). */
        public Builder transport(Transport transport) {
            this.transport = transport;
            return this;
        }

        /** How the client waits between polls and retries (default {@link Thread#sleep}). */
        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        /** Monotonic clock in nanoseconds for the polling timeout (default {@link System#nanoTime}). */
        public Builder clock(LongSupplier nanoTime) {
            this.nanoTime = nanoTime;
            return this;
        }

        /** Wall clock for {@link ApiKeyCheck#getCheckedAt()} (default {@link Clock#systemUTC()}). */
        public Builder wallClock(Clock wallClock) {
            this.wallClock = wallClock;
            return this;
        }

        public SupertextClient build() {
            return new SupertextClient(this);
        }

        private static Duration requirePositive(Duration value, String name) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }

    /** multipart/form-data written by hand: the JDK has no builder, and the file part must carry exactly "text/html". */
    private static final class Multipart {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final String boundary;

        Multipart(String boundary) {
            this.boundary = boundary;
        }

        void field(String name, String value) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
        }

        void file(String name, String fileName, String type, byte[] content) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName
                    + "\"\r\nContent-Type: " + type + "\r\n\r\n");
            out.write(content, 0, content.length);
            write("\r\n");
        }

        byte[] finish() {
            write("--" + boundary + "--\r\n");
            return out.toByteArray();
        }

        private void write(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
    }
}
