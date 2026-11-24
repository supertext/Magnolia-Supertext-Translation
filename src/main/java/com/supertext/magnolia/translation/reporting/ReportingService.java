package com.supertext.magnolia.translation.reporting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.magnolia.cms.beans.config.ServerConfiguration;
import info.magnolia.context.MgnlContext;
import info.magnolia.context.WebContext;
import info.magnolia.module.ModuleRegistry;
import info.magnolia.module.model.ModuleDefinition;
import info.magnolia.objectfactory.Components;

import com.supertext.magnolia.translation.SupertextSettings;
import com.supertext.magnolia.translation.api.SupertextException;
import com.supertext.magnolia.translation.api.SupertextService;
import com.supertext.magnolia.translation.internal.pluginhealth.Extension;
import com.supertext.magnolia.translation.internal.pluginhealth.KeyCheck;
import com.supertext.magnolia.translation.internal.pluginhealth.LanguageMapping;
import com.supertext.magnolia.translation.internal.pluginhealth.PingResponse;
import com.supertext.magnolia.translation.internal.pluginhealth.ReporterResult;

/**
 * Wires {@link VersionReports} to Magnolia: the site URL ({@code /server/defaultBaseUrl} or the current request), the
 * module and Magnolia versions, the settings in effect and the state in the config workspace ({@link JcrStateStore}).
 *
 * <p>Settings saves and "Check connection" run synchronously (the result is shown); back-end requests and rejected
 * translations report in a background thread, so no request waits for Supertext or the center. Never on front-end
 * page views: only the Supertext app and the translate action call it. Nothing here throws.</p>
 */
@Singleton
public class ReportingService {

    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);

    private final SupertextSettings settings;
    private final VersionReports reports;
    private final ExecutorService background = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10), runnable -> {
        Thread thread = new Thread(runnable, "supertext-version-reports");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.DiscardPolicy());

    @Inject
    public ReportingService(SupertextSettings settings) {
        this(settings, new VersionReports(new JcrStateStore()));
    }

    public ReportingService(SupertextSettings settings, VersionReports reports) {
        this.settings = settings;
        this.reports = reports;
    }

    public boolean enabled() {
        return settings.versionReportsEnabled();
    }

    /** After the settings were saved: checks the key, reports; the result's check is shown on the settings screen. */
    public ReporterResult afterSettingsSaved() {
        return run(VersionReports.Action.SETTINGS_SAVED, installation(), 0);
    }

    /** The "Check connection" button. */
    public ReporterResult testConnection() {
        return run(VersionReports.Action.TEST_CONNECTION, installation(), 0);
    }

    /** A back-end request that runs the module's code (Supertext app, translate action): in the background. */
    public void onRequest() {
        inBackground(VersionReports.Action.ON_REQUEST, 0);
    }

    /** Supertext rejected the key during a translation (HTTP 401/403): in the background. */
    public void authenticationFailed(Integer httpStatus) {
        inBackground(VersionReports.Action.AUTHENTICATION_FAILED, httpStatus == null ? 401 : httpStatus);
    }

    /**
     * "Send configuration to Supertext" (Supertext app): the installed modules that aren't Magnolia's own and every
     * site language with its Supertext code, in one {@code extensions} report. Null with reports off.
     *
     * @param languages the site languages (Magnolia locales, e.g. {@code de_CH})
     */
    public ReporterResult sendConfiguration(Collection<String> languages) {
        if (!enabled()) {
            return null;
        }
        List<LanguageMapping> mapping = new ArrayList<>();
        com.supertext.magnolia.translation.api.LanguageMapping codes = settings.languageMapping();
        for (String language : languages) {
            String code;
            try {
                code = codes.target(language);
            } catch (SupertextException | RuntimeException e) {
                code = null;
            }
            mapping.add(new LanguageMapping(language, code));
        }
        List<Extension> extensions = installedModules();
        VersionReports.Installation installation = installation();
        try {
            // Like "Check connection": in the request, which stores the state as the user (see run()).
            return reports.sendConfiguration(installation, settings.connection(), true, extensions, mapping);
        } catch (RuntimeException e) {
            log.debug("Supertext: configuration not sent: {}", e.toString());
            return null;
        }
    }

    /** The modules with a module class outside {@code info.magnolia} (i.e. not Magnolia's own), by name. */
    public static List<Extension> installedModules() {
        List<Extension> out = new ArrayList<>();
        try {
            for (ModuleDefinition module : Components.getComponent(ModuleRegistry.class).getModuleDefinitions()) {
                String className = module.getClassName();
                if (className == null || className.isBlank() || className.startsWith("info.magnolia.")) {
                    continue;
                }
                out.add(new Extension(module.getName(), module.getVersion() == null ? null : module.getVersion().toString(), true, "module"));
            }
        } catch (RuntimeException e) {
            // A report without modules still has the languages.
        }
        out.sort(Comparator.comparing(Extension::name));
        return out;
    }

    /** The latest key check, or null. */
    public KeyCheck lastCheck() {
        try {
            return MgnlContext.doInSystemContext(reports::lastCheck);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public PingResponse ping(String challenge) {
        boolean on;
        try {
            on = enabled();
        } catch (RuntimeException e) {
            on = true;
        }
        return VersionReports.ping(challenge, on);
    }

    /** The full URL of the ping route, or null when the site URL is unknown. */
    public String pingUrl() {
        return VersionReports.pingUrl(siteUrl());
    }

    private ReporterResult run(VersionReports.Action action, VersionReports.Installation installation, int httpStatus) {
        try {
            return reports.run(action, installation, settings.connection(), enabled(), httpStatus);
        } catch (RuntimeException e) {
            log.debug("Supertext: version report skipped: {}", e.toString());
            return null;
        }
    }

    private void inBackground(VersionReports.Action action, int httpStatus) {
        // Read everything that needs the request here, in the request's thread.
        VersionReports.Installation installation = installation();
        if (installation == null || installation.siteUrl() == null) {
            return;
        }
        try {
            background.execute(() -> {
                try {
                    MgnlContext.doInSystemContext(() -> run(action, installation, httpStatus), true);
                } catch (RuntimeException e) {
                    log.debug("Supertext: version report skipped: {}", e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("Supertext: version report skipped (busy).");
        }
    }

    VersionReports.Installation installation() {
        try {
            return new VersionReports.Installation(siteUrl(), SupertextService.version(), magnoliaVersion());
        } catch (RuntimeException e) {
            log.debug("Supertext: version report skipped: {}", e.toString());
            return null;
        }
    }

    static String siteUrl() {
        String configured = null;
        try {
            ServerConfiguration server = Components.getComponent(ServerConfiguration.class);
            configured = server == null ? null : server.getDefaultBaseUrl();
        } catch (RuntimeException e) {
            // No server configuration: use the request.
        }
        String fromRequest = null;
        try {
            WebContext web = MgnlContext.getWebContextOrNull();
            HttpServletRequest request = web == null ? null : web.getRequest();
            if (request != null) {
                fromRequest = VersionReports.requestBase(request.getScheme(), request.getServerName(), request.getServerPort(), request.getContextPath());
            }
        } catch (RuntimeException e) {
            // No request (background thread).
        }
        return VersionReports.siteUrl(configured, fromRequest);
    }

    static String magnoliaVersion() {
        try {
            ModuleDefinition core = Components.getComponent(ModuleRegistry.class).getDefinition("core");
            return core == null || core.getVersion() == null ? null : core.getVersion().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
