package com.supertext.magnolia.translation.api;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.supertext.magnolia.translation.internal.sdk.Transport;
import com.supertext.magnolia.translation.internal.sdk.v1.ErrorCode;
import com.supertext.magnolia.translation.internal.sdk.v1.Languages;
import com.supertext.magnolia.translation.internal.sdk.v1.Politeness;
import com.supertext.magnolia.translation.internal.sdk.v1.Segment;
import com.supertext.magnolia.translation.internal.sdk.v1.SupertextClient;

/**
 * The module's access to Supertext, on top of the Supertext Java SDK (API v1 in {@code com.supertext.magnolia.translation.internal.sdk.v1},
 * bundled in lib/supertext-sdk and compiled into the module's jar). The SDK does the protocol (upload, poll, download, delete, 429
 * retries, packing texts into {@code data-st-id} documents); this class adds what is Magnolia's:
 * the connection from the settings (the review service for languages with <em>Translation review</em> on, see
 * {@link SupertextConnection#forReview(boolean)}), the https-only endpoint rule, the module's name and version in the
 * User-Agent, and errors as {@link SupertextException} with a message key the UI translates.
 *
 * <p>No Magnolia classes, so it is unit-tested on its own (with a fake {@link Transport}).</p>
 */
public class SupertextService {

    public static final String DEFAULT_ENDPOINT = SupertextClient.LIVE_BASE_URL;

    /**
     * Supertext's review service (the translation proxy of Supertext Localization Review). It speaks the same API v1
     * and forwards to the live Supertext API with the module's key, keeping every translation so reviewers can correct
     * them on the website; their corrections come back with the next translation. Used for target languages with
     * <em>Translation review</em> on (setting {@code reviewUrl} / {@code SUPERTEXT_REVIEW_URL}).
     */
    public static final String REVIEW_URL = "https://localizationreview-production.up.railway.app/v1/";

    /** The module's name and version in the User-Agent, as in the version reports. */
    static final String PLUGIN_NAME = "Magnolia-Supertext-Translation";

    /** Prefix of the error message keys in the module's i18n bundle. */
    public static final String KEY_PREFIX = "supertext-translation.error.";

    private static final Pattern AUTH_PREFIX = Pattern.compile("^\\s*Supertext-Auth-Key\\s+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_DETAIL = 200;

    private final Transport transport;
    private final SupertextClient.Sleeper sleeper;

    /** Used by Magnolia: the SDK's own HTTP transport (the JDK's {@code HttpClient}). */
    public SupertextService() {
        this(null, null);
    }

    /** For tests: a fake transport and sleeper ({@code null} = the SDK's defaults). */
    public SupertextService(Transport transport, SupertextClient.Sleeper sleeper) {
        this.transport = transport;
        this.sleeper = sleeper;
    }

    /**
     * Translates texts (key → segment) and returns key → translation. The SDK packs them into as
     * few documents as fit (900,000 characters each) and translates those one after the other.
     *
     * @param targetLanguage Supertext target code, e.g. {@code de-CH}
     * @param sourceLanguage Supertext source code, e.g. {@code en}; empty for auto-detection
     * @param politeness     {@code default}, {@code more} or {@code less}; only sent where the target offers it
     *                       ({@link Languages#supportsPoliteness(String)}), otherwise Supertext would refuse the job
     * @throws SupertextException also when a text is missing from the answer (key {@code incomplete})
     */
    public Map<String, String> translateTexts(SupertextConnection connection, Map<String, Segment> texts,
                                              String targetLanguage, String sourceLanguage, String politeness)
            throws SupertextException, InterruptedException {
        SupertextClient client = client(connection);
        String source = sourceLanguage == null || sourceLanguage.isBlank() ? null : sourceLanguage;
        try {
            return client.translateTexts(texts, targetLanguage, source, politenessFor(targetLanguage, politeness));
        } catch (com.supertext.magnolia.translation.internal.sdk.v1.SupertextException e) {
            throw convert(e, source, targetLanguage);
        }
    }

    /** Cost-free check of the API key. */
    public void validateApiKey(SupertextConnection connection) throws SupertextException, InterruptedException {
        SupertextClient client = client(connection);
        try {
            client.validateApiKey();
        } catch (com.supertext.magnolia.translation.internal.sdk.v1.SupertextException e) {
            throw convert(e, null, null);
        }
    }

    /** The form of address to send: the setting, but only for targets that offer formal/informal (else none). */
    static Politeness politenessFor(String targetLanguage, String politeness) {
        return Boolean.TRUE.equals(Languages.supportsPoliteness(targetLanguage)) ? Politeness.fromValue(politeness) : Politeness.DEFAULT;
    }

    /**
     * The Supertext target code for a Magnolia language ({@code de_CH}): the mapping's entry, else an exact
     * match ({@code de_CH} → {@code de-CH}), else the SDK's default ({@code de} → {@code de-DE}), else the error
     * {@code unsupportedLanguage} (nothing is sent): {@code de_LI}, {@code en_IE}, {@code gsw} need a mapping.
     */
    public static String targetCode(String language, Map<String, String> mapping) throws SupertextException {
        try {
            return Languages.target(language, mapping);
        } catch (com.supertext.magnolia.translation.internal.sdk.v1.SupertextException e) {
            throw convert(e, null, language);
        }
    }

    /** The Supertext source code for a Magnolia language: the mapping's entry in its source form, else the bare language. */
    public static String sourceCode(String language, Map<String, String> mapping) throws SupertextException {
        try {
            return Languages.source(language, mapping);
        } catch (com.supertext.magnolia.translation.internal.sdk.v1.SupertextException e) {
            throw convert(e, language, null);
        }
    }

    private SupertextClient client(SupertextConnection connection) throws SupertextException {
        String apiKey = normalizeApiKey(connection.apiKey());
        if (apiKey.isEmpty()) {
            // The SDK would say the same; checked here so nothing is built for a request that can't be sent.
            throw noApiKey();
        }
        SupertextClient.Builder builder = SupertextClient.builder(apiKey)
                .baseUrl(baseUri(connection.endpoint()).toString())
                .pollInterval(Duration.ofMillis(Math.max(100, connection.pollIntervalMillis())))
                .timeout(Duration.ofSeconds(Math.max(10, connection.pollTimeoutSeconds())))
                .requestTimeout(Duration.ofSeconds(60))
                .userAgent(PLUGIN_NAME + "/" + version());
        if (transport != null) {
            builder.transport(transport);
        }
        if (sleeper != null) {
            builder.sleeper(sleeper);
        }
        return builder.build();
    }

    /** The SDK's error as the module's: message key and arguments for the UI, English message for the log. */
    static SupertextException convert(com.supertext.magnolia.translation.internal.sdk.v1.SupertextException e, String sourceLanguage, String targetLanguage) {
        ErrorCode code = e.getCode();
        String detail = shorten(e.getDetail());
        String message = e.getMessage();
        Object[] args = new Object[0];
        boolean authentication = false;
        Integer status = null;
        String key;
        switch (code) {
            case MISSING_API_KEY:
                return noApiKey();
            case AUTHENTICATION_FAILURE:
                key = "authentication";
                message = "Authentication failed. Please check the Supertext API key. " + SupertextLinks.PLAIN_TEXT_HINT;
                authentication = true;
                status = e.getStatus() == null ? 401 : e.getStatus();
                break;
            case UNSUPPORTED_LANGUAGE:
                key = "unsupportedLanguage";
                args = new Object[] {orUnknown(e.getTargetLang() != null ? e.getTargetLang() : e.getSourceLang(),
                        targetLanguage != null ? targetLanguage : sourceLanguage)};
                detail = "";
                message = "The Magnolia language " + args[0] + " has no Supertext language code. Choose one in Translation → Supertext"
                        + " → Language codes. Nothing was sent.";
                break;
            case UNSUPPORTED_FEATURE:
                key = "unsupportedFeature";
                args = new Object[] {orUnknown(e.getFeature(), null), orUnknown(e.getTargetLang(), targetLanguage)};
                detail = "";
                break;
            case INVALID_LANGUAGE_PAIR:
                key = "invalidLanguagePair";
                args = new Object[] {orUnknown(e.getSourceLang(), sourceLanguage), orUnknown(e.getTargetLang(), targetLanguage)};
                detail = "";
                break;
            case NOT_FOUND:
                key = "notFound";
                break;
            case PAYLOAD_TOO_LARGE:
                key = "tooLarge";
                break;
            case TOO_MANY_REQUESTS:
                key = "rateLimit";
                break;
            case SERVICE_UNAVAILABLE:
                key = "unavailable";
                break;
            case TRANSPORT_ERROR:
                key = "unreachable";
                args = new Object[] {detail.isEmpty() ? "?" : detail};
                detail = "";
                break;
            case NO_FILE_ID:
                key = "noFileId";
                break;
            case TRANSLATION_ERROR:
                key = "failed";
                break;
            case QUOTA_EXCEEDED:
                key = "limitExceeded";
                break;
            case FILE_DELETED:
                key = "deleted";
                break;
            case TIMEOUT:
                key = "timeout";
                break;
            case INCOMPLETE_RESPONSE:
                key = "incomplete";
                break;
            case UNEXPECTED_STATUS:
            default:
                key = "http";
                // A string, so the UI's MessageFormat doesn't group the digits.
                args = new Object[] {e.getStatus() == null ? "?" : String.valueOf(e.getStatus())};
                break;
        }
        return new SupertextException(KEY_PREFIX + key, args, detail, message, authentication, status, e);
    }

    private static SupertextException noApiKey() {
        return new SupertextException(KEY_PREFIX + "noApiKey", new Object[0], "",
                "No Supertext API key configured (Translation → Supertext, or the SUPERTEXT_API_KEY environment variable). "
                + SupertextLinks.PLAIN_TEXT_HINT, true, null);
    }

    /** Removes a pasted "Supertext-Auth-Key " prefix (Supertext shows the key with it). */
    public static String normalizeApiKey(String apiKey) {
        return apiKey == null ? "" : AUTH_PREFIX.matcher(apiKey).replaceFirst("").trim();
    }

    /** Throws when the endpoint is not https (http is allowed for localhost only). */
    public static void checkEndpoint(String endpoint) throws SupertextException {
        baseUri(endpoint);
    }

    static URI baseUri(String endpoint) throws SupertextException {
        String value = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        if (!value.endsWith("/")) {
            value += "/";
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new SupertextException(KEY_PREFIX + "endpointInvalid", new Object[] {value}, "",
                    "The Supertext endpoint is not a valid URL: " + value, false, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1")
                || host.endsWith(".localhost") || host.equals("host.docker.internal");
        if (!scheme.equals("https") && !(scheme.equals("http") && local)) {
            throw new SupertextException(KEY_PREFIX + "endpointNotHttps", new Object[] {value}, "",
                    "The Supertext endpoint must use https (http is only allowed for localhost): " + value, false, null);
        }
        return uri;
    }

    /** The module version (from the jar manifest), for the User-Agent and the settings screen. */
    public static String version() {
        String version = SupertextService.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private static String orUnknown(String fromAnswer, String sent) {
        if (fromAnswer != null && !fromAnswer.isBlank()) {
            return fromAnswer;
        }
        return sent == null || sent.isBlank() ? "?" : sent;
    }

    private static String shorten(String detail) {
        if (detail == null) {
            return "";
        }
        String d = detail.trim();
        return d.length() > MAX_DETAIL ? d.substring(0, MAX_DETAIL) + "…" : d;
    }
}
