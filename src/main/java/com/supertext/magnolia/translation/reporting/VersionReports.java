package com.supertext.magnolia.translation.reporting;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.supertext.magnolia.translation.api.SupertextConnection;
import com.supertext.magnolia.translation.api.SupertextService;
import com.supertext.magnolia.translation.internal.pluginhealth.Extension;
import com.supertext.magnolia.translation.internal.pluginhealth.InstallationReporter;
import com.supertext.magnolia.translation.internal.pluginhealth.KeyCheck;
import com.supertext.magnolia.translation.internal.pluginhealth.KeyChecker;
import com.supertext.magnolia.translation.internal.pluginhealth.LanguageMapping;
import com.supertext.magnolia.translation.internal.pluginhealth.PingResponse;
import com.supertext.magnolia.translation.internal.pluginhealth.ReporterResult;
import com.supertext.magnolia.translation.internal.pluginhealth.ReporterState;
import com.supertext.magnolia.translation.internal.sdk.SdkInfo;
import com.supertext.magnolia.translation.internal.sdk.Transport;
import com.supertext.magnolia.translation.internal.sdk.v1.ApiKeyCheck;
import com.supertext.magnolia.translation.internal.sdk.v1.SupertextClient;

/**
 * The module's side of the Supertext Commando Center (Commando-Center-Supertext-Translation → docs/DEVELOPER.md →
 * <em>Report protocol</em>): version reports, the automatic API key check and the callback ping. The protocol is
 * Supertext Plugin Health's ({@link InstallationReporter}, {@link PingResponse}, bundled in lib/supertext-plugin-health)
 * with the SDK's {@link SupertextClient#checkApiKey()} as key check; this class gives it what the module knows and keeps
 * the reporter's state through a {@link StateStore}.
 *
 * <p>No Magnolia classes, so it is unit-tested on its own with a fake {@link Transport}. Nothing here throws: a
 * failed report or key check never breaks a save, a page or a translation. The API key is never logged.</p>
 */
public class VersionReports {

    private static final Logger log = LoggerFactory.getLogger(VersionReports.class);

    /** The GitHub repository name (the center reads the latest release from it). */
    public static final String PLUGIN_NAME = "Magnolia-Supertext-Translation";

    /** The public ping route, below the webapp's context path. */
    public static final String PING_PATH = "/.supertext/ping";

    /** Where the reporter's JSON state ({@link ReporterState}) is kept. */
    public interface StateStore {
        String load() throws Exception;

        void save(String json) throws Exception;
    }

    /**
     * What a report names.
     *
     * @param siteUrl         base URL of the Magnolia instance, e.g. {@code https://cms.example.ch}
     * @param pluginVersion   the module version (jar manifest, from {@code pom.xml})
     * @param magnoliaVersion Magnolia's version (its core module), or null
     */
    public record Installation(String siteUrl, String pluginVersion, String magnoliaVersion) {
    }

    public enum Action {
        /** The settings were saved: key check, {@code installed} or {@code settings_saved}. */
        SETTINGS_SAVED,
        /** A back-end request that runs the module's code: re-checks a changed key, reports an upgrade. */
        ON_REQUEST,
        /** The "Check connection" button. */
        TEST_CONNECTION,
        /** A translation was rejected with 401/403. */
        AUTHENTICATION_FAILED
    }

    private final StateStore store;
    private final Transport transport;
    private final String reportUrl;
    private final Clock clock;

    /**
     * Used by Magnolia: the JDK's HTTP client (the SDK's for the key check, Plugin Health's for the report), the report
     * URL from {@code SUPERTEXT_REPORT_URL} or Plugin Health's default.
     */
    public VersionReports(StateStore store) {
        this(store, null, null, null);
    }

    /** For tests: a fake transport (key check and report), report URL and clock ({@code null} = the defaults). */
    public VersionReports(StateStore store, Transport transport, String reportUrl, Clock clock) {
        this.store = store;
        this.transport = transport;
        this.reportUrl = reportUrl;
        this.clock = clock;
    }

    /** The reporter for this installation. */
    public InstallationReporter reporter(Installation installation, SupertextConnection connection, boolean enabled) {
        return reporter(installation, connection, enabled, null);
    }

    /** The reporter for this installation, with the language mapping for {@link #sendConfiguration}. */
    public InstallationReporter reporter(Installation installation, SupertextConnection connection, boolean enabled,
                                         List<LanguageMapping> languages) {
        String apiBaseUrl = apiBaseUrl(connection);
        String userAgent = PLUGIN_NAME + "/" + installation.pluginVersion();
        InstallationReporter.Builder builder = InstallationReporter.builder()
                .enabled(enabled)
                .siteUrl(installation.siteUrl())
                .plugin(PLUGIN_NAME, installation.pluginVersion())
                .system("Magnolia", installation.magnoliaVersion())
                .sdkVersion(SdkInfo.VERSION)
                .callbackUrl(pingUrl(installation.siteUrl()))
                .keyChecker(KeyChecker.of(key -> {
                    SupertextClient.Builder client = SupertextClient.builder(key).baseUrl(apiBaseUrl).userAgent(userAgent);
                    if (transport != null) {
                        client.transport(transport);
                    }
                    if (clock != null) {
                        client.wallClock(clock);
                    }
                    ApiKeyCheck c = client.build().checkApiKey();
                    return new KeyCheck(c.getStatus().value(), c.getCheckedAt(), c.getHttpStatus(), c.getErrorCode(), c.getFingerprint());
                }, ApiKeyCheck::fingerprint));
        if (transport != null) {
            // The same fake answers the key check and the report.
            builder.transport(request -> {
                Transport.Response response = transport.send(new Transport.Request(request.getMethod(), request.getUri(),
                        request.getHeaders(), request.getBody(), request.getTimeout()));
                return response == null ? 0 : response.getStatus();
            });
        }
        if (reportUrl != null) {
            builder.reportUrl(reportUrl);
        }
        if (clock != null) {
            builder.clock(clock);
        }
        if (languages != null) {
            builder.languages(() -> languages);
        }
        return builder.build();
    }

    /**
     * "Send configuration to Supertext": one {@code extensions} report with the installed modules and the language
     * mapping, sent now (also within the hour after a failed report). Nothing with reports off. Never throws.
     *
     * @return the result, or null when it couldn't run (no site URL, storage failed)
     */
    public synchronized ReporterResult sendConfiguration(Installation installation, SupertextConnection connection, boolean enabled,
                                                         List<Extension> extensions, List<LanguageMapping> languages) {
        if (installation == null || installation.siteUrl() == null) {
            return null;
        }
        try {
            ReporterResult result = reporter(installation, connection, enabled, languages)
                    .sendExtensions(ReporterState.fromJson(load()), extensions);
            store.save(result.getState().toJson());
            return result;
        } catch (Exception e) {
            log.debug("Supertext: configuration not sent: {}", e.toString());
            return null;
        }
    }

    /**
     * Runs one reporter action with the stored state and stores the result. One at a time; never throws.
     *
     * @return the result, or null when it couldn't run (no site URL, storage failed)
     */
    public synchronized ReporterResult run(Action action, Installation installation, SupertextConnection connection, boolean enabled,
                                           int httpStatus) {
        if (installation == null || installation.siteUrl() == null) {
            return null;
        }
        try {
            InstallationReporter reporter = reporter(installation, connection, enabled);
            ReporterState state = ReporterState.fromJson(load());
            String apiKey = SupertextService.normalizeApiKey(connection.apiKey());
            ReporterResult result = switch (action) {
                case SETTINGS_SAVED -> reporter.afterSettingsSaved(state, apiKey);
                case TEST_CONNECTION -> reporter.testConnection(state, apiKey);
                case AUTHENTICATION_FAILED -> reporter.authenticationFailed(state, apiKey, httpStatus);
                case ON_REQUEST -> reporter.onRequest(state, apiKey);
            };
            store.save(result.getState().toJson());
            if (result.getEvent() != null) {
                log.debug("Supertext: version report {}: {}.", result.getEvent(), result.isSent() ? "sent" : "not sent");
            }
            return result;
        } catch (Exception e) {
            log.debug("Supertext: version report skipped: {}", e.toString());
            return null;
        }
    }

    /** The latest stored key check (for the settings screen), or null. */
    public KeyCheck lastCheck() {
        try {
            return ReporterState.fromJson(load()).getKeyCheck();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The ping route's answer: 200 with the challenge, 400 for a bad challenge, 404 while reports are off. */
    public static PingResponse ping(String challenge, boolean enabled) {
        return PingResponse.forChallenge(challenge, PLUGIN_NAME, enabled);
    }

    /** The absolute URL of the ping route for a site URL. */
    public static String pingUrl(String siteUrl) {
        return siteUrl == null ? null : stripSlash(siteUrl) + PING_PATH;
    }

    /**
     * The installation's base URL without trailing slash: Magnolia's {@code /server/defaultBaseUrl} when it is set
     * to a real address, else the address the current request came in on ({@code scheme://host[:port]/context}).
     * A {@code localhost} base URL (Magnolia's bootstrap default is {@code http://localhost:8080/magnolia/}) is only used
     * when there is no request.
     * Null when neither is an absolute http(s) URL.
     */
    public static String siteUrl(String configured, String requestBase) {
        String fromConfig = absolute(configured);
        String fromRequest = absolute(requestBase);
        if (fromConfig != null && (fromRequest == null || !isLocal(fromConfig))) {
            return fromConfig;
        }
        return fromRequest;
    }

    /** {@code scheme://host[:port]} plus the context path, from a request's parts. */
    public static String requestBase(String scheme, String host, int port, String contextPath) {
        if (host == null || host.isBlank() || scheme == null) {
            return null;
        }
        String s = scheme.toLowerCase(Locale.ROOT);
        boolean defaultPort = port <= 0 || (s.equals("http") && port == 80) || (s.equals("https") && port == 443);
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return s + "://" + h + (defaultPort ? "" : ":" + port) + (contextPath == null ? "" : contextPath);
    }

    private static String absolute(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
                return null;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return stripSlash(scheme + "://" + uri.getRawAuthority().replaceFirst("^.*@", "") + path);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isLocal(String url) {
        String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.endsWith(".localhost") || host.startsWith("127.") || host.equals("[::1]");
    }

    private static String stripSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String apiBaseUrl(SupertextConnection connection) {
        String endpoint = connection == null ? null : connection.endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return SupertextService.DEFAULT_ENDPOINT;
        }
        try {
            // Never send the key over plain http (allowed for localhost only), not even for the check.
            SupertextService.checkEndpoint(endpoint);
            return endpoint.trim();
        } catch (com.supertext.magnolia.translation.api.SupertextException e) {
            return SupertextService.DEFAULT_ENDPOINT;
        }
    }

    private String load() {
        try {
            return store.load();
        } catch (Exception e) {
            log.debug("Supertext: could not read the reporter state: {}", e.toString());
            return null;
        }
    }
}
