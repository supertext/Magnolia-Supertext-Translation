package com.supertext.magnolia.translation.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.supertext.magnolia.translation.api.SupertextConnection;
import com.supertext.magnolia.translation.internal.pluginhealth.Extension;
import com.supertext.magnolia.translation.internal.pluginhealth.LanguageMapping;
import com.supertext.magnolia.translation.internal.pluginhealth.PingResponse;
import com.supertext.magnolia.translation.internal.pluginhealth.ReportEvent;
import com.supertext.magnolia.translation.internal.pluginhealth.ReporterResult;
import com.supertext.magnolia.translation.internal.sdk.SdkInfo;
import com.supertext.magnolia.translation.internal.sdk.Transport;
import com.supertext.magnolia.translation.internal.sdk.v1.ApiKeyCheck;

/** The Commando Center glue against a fake transport: never reaches the live center or Supertext. */
class VersionReportsTest {

    private static final String REPORT_URL = "http://127.0.0.1:9/report";
    private static final SupertextConnection CONNECTION = new SupertextConnection("https://api.example.test/v1/", "Supertext-Auth-Key secret-key");
    private static final VersionReports.Installation INSTALLATION =
            new VersionReports.Installation("https://cms.example.ch", "0.2.0", "6.4.11");

    private final List<Transport.Request> requests = new ArrayList<>();
    private int keyStatus = 200;
    private boolean centerDown;
    private String stored;

    private final Transport fake = request -> {
        requests.add(request);
        if (request.getUri().toString().equals(REPORT_URL)) {
            if (centerDown) {
                throw new IOException("connection refused");
            }
            return new Transport.Response(202, Map.of("Content-Type", "application/json"), "{\"ok\":true}");
        }
        return new Transport.Response(keyStatus, Map.of("Content-Type", "application/json"), keyStatus == 200 ? "{}" : "{\"error_code\":\"UNAUTHORIZED\"}");
    };

    private final VersionReports.StateStore store = new VersionReports.StateStore() {
        @Override
        public String load() {
            return stored;
        }

        @Override
        public void save(String json) {
            stored = json;
        }
    };

    private final VersionReports reports = new VersionReports(store, fake, REPORT_URL,
            Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), ZoneOffset.UTC));

    private List<String> reportBodies() {
        return requests.stream().filter(r -> r.getUri().toString().equals(REPORT_URL))
                .map(r -> new String(r.getBody(), StandardCharsets.UTF_8)).toList();
    }

    @Test
    void settingsSavedChecksTheKeyAndReportsInstalledThenSettingsSaved() {
        ReporterResult first = reports.run(VersionReports.Action.SETTINGS_SAVED, INSTALLATION, CONNECTION, true, 0);
        assertNotNull(first);
        assertEquals(ApiKeyCheck.Status.OK.value(), first.getCheck().status());
        assertEquals(ReportEvent.INSTALLED, first.getEvent());
        assertTrue(first.isSent());
        assertEquals("https://api.example.test/v1/features", requests.get(0).getUri().toString(), "key checked against the endpoint in effect");
        String body = reportBodies().get(0);
        assertTrue(body.contains("\"plugin\":{\"name\":\"Magnolia-Supertext-Translation\",\"version\":\"0.2.0\"}"), body);
        assertTrue(body.contains("\"system\":{\"name\":\"Magnolia\",\"version\":\"6.4.11\"}"), body);
        assertTrue(body.contains("\"site_url\":\"https://cms.example.ch\""), body);
        assertTrue(body.contains("\"callback\":{\"url\":\"https://cms.example.ch/.supertext/ping\"}"), body);
        assertTrue(body.contains("\"sdk_version\":\"" + SdkInfo.VERSION + "\""), body);
        assertFalse(body.contains("secret-key"), "never the key");

        ReporterResult second = reports.run(VersionReports.Action.SETTINGS_SAVED, INSTALLATION, CONNECTION, true, 0);
        assertEquals(ReportEvent.SETTINGS_SAVED, second.getEvent());
        assertEquals(ApiKeyCheck.Status.OK.value(), reports.lastCheck().status(), "state stored");
    }

    @Test
    void offMeansNoReportButTheKeyIsStillChecked() {
        keyStatus = 401;
        ReporterResult result = reports.run(VersionReports.Action.SETTINGS_SAVED, INSTALLATION, CONNECTION, false, 0);
        assertEquals(ApiKeyCheck.Status.INVALID.value(), result.getCheck().status());
        assertEquals(List.of(), reportBodies());
    }

    @Test
    void upgradesAreReportedOnRequests() {
        reports.run(VersionReports.Action.SETTINGS_SAVED, INSTALLATION, CONNECTION, true, 0);
        requests.clear();
        assertNull(reports.run(VersionReports.Action.ON_REQUEST, INSTALLATION, CONNECTION, true, 0).getEvent(), "nothing changed");
        assertEquals(List.of(), requests, "same key: not checked again");
        ReporterResult upgraded = reports.run(VersionReports.Action.ON_REQUEST,
                new VersionReports.Installation("https://cms.example.ch", "0.3.0", "6.4.11"), CONNECTION, true, 0);
        assertEquals(ReportEvent.UPGRADED, upgraded.getEvent());
    }

    @Test
    void aRejectedTranslationMarksTheKeyInvalidWithoutAskingSupertext() {
        reports.run(VersionReports.Action.SETTINGS_SAVED, INSTALLATION, CONNECTION, true, 0);
        requests.clear();
        ReporterResult result = reports.run(VersionReports.Action.AUTHENTICATION_FAILED, INSTALLATION, CONNECTION, true, 403);
        assertEquals(ApiKeyCheck.Status.INVALID.value(), result.getCheck().status());
        assertEquals(403, result.getCheck().httpStatus());
        assertEquals(ReportEvent.API_KEY_CHECK, result.getEvent());
        assertTrue(requests.stream().noneMatch(r -> r.getUri().getPath().endsWith("/features")));
    }

    @Test
    void anUnreachableCenterNeverThrows() {
        centerDown = true;
        ReporterResult result = reports.run(VersionReports.Action.TEST_CONNECTION, INSTALLATION, CONNECTION, true, 0);
        assertNotNull(result);
        assertFalse(result.isSent());
        assertEquals(ApiKeyCheck.Status.OK.value(), result.getCheck().status());
    }

    @Test
    void aBrokenStoreNeverThrows() {
        VersionReports broken = new VersionReports(new VersionReports.StateStore() {
            @Override
            public String load() throws Exception {
                throw new Exception("no repository");
            }

            @Override
            public void save(String json) throws Exception {
                throw new Exception("no repository");
            }
        }, fake, REPORT_URL, null);
        assertNull(broken.run(VersionReports.Action.SETTINGS_SAVED, INSTALLATION, CONNECTION, true, 0));
        assertNull(broken.lastCheck());
        assertNull(reports.run(VersionReports.Action.ON_REQUEST, new VersionReports.Installation(null, "0.2.0", null), CONNECTION, true, 0),
                "no site URL: nothing to report");
    }

    @Test
    void theKeyIsNeverSentOverPlainHttp() {
        reports.run(VersionReports.Action.TEST_CONNECTION, INSTALLATION, new SupertextConnection("http://evil.example/v1/", "key"), false, 0);
        assertEquals("https://api.supertext.com/v1/features", requests.get(0).getUri().toString());
    }

    @Test
    void aStateStoredByTheSdk02ReporterIsKeptWithoutANewInstalledReport() {
        // What the SDK 0.2 reporter stored before the switch to Plugin Health.
        stored = "{\"reportedVersions\":\"0.2.0|6.4.11|" + System.getProperty("java.version") + "\",\"reportedKeyStatus\":\"ok\","
                + "\"lastFailureAt\":null,\"keyCheck\":{\"status\":\"ok\",\"checkedAt\":\"2026-10-14T08:00:00Z\",\"httpStatus\":200,"
                + "\"errorCode\":null,\"fingerprint\":\"" + ApiKeyCheck.fingerprint("secret-key") + "\"}}";
        String before = stored;
        ReporterResult result = reports.run(VersionReports.Action.ON_REQUEST, INSTALLATION, CONNECTION, true, 0);
        assertNull(result.getEvent(), "no new installed report");
        assertEquals(List.of(), requests, "same key: not checked again");
        // Plugin Health 0.2+ adds keyChecks and reportedKeyStatuses (null with one key); everything else is kept.
        assertEquals(before.substring(0, before.length() - 1) + ",\"keyChecks\":null,\"reportedKeyStatuses\":null}", stored, "same JSON");
    }

    @Test
    void sendConfigurationSendsModulesAndTheLanguageMappingNow() {
        centerDown = true;
        reports.run(VersionReports.Action.ON_REQUEST, INSTALLATION, CONNECTION, true, 0);
        centerDown = false;
        requests.clear();
        ReporterResult result = reports.sendConfiguration(INSTALLATION, CONNECTION, true,
                List.of(new Extension("acme-forms", "2.1.0", true, "module")),
                List.of(new LanguageMapping("en", "en-US"), new LanguageMapping("de_CH", "de-CH"), new LanguageMapping("en_IE", null)));
        assertEquals(ReportEvent.EXTENSIONS, result.getEvent());
        assertTrue(result.isSent(), "also within the hour after a failed report");
        String body = reportBodies().get(0);
        assertTrue(body.contains("\"event\":\"extensions\""), body);
        assertTrue(body.contains("{\"name\":\"acme-forms\",\"version\":\"2.1.0\",\"active\":true,\"type\":\"module\"}"), body);
        assertTrue(body.contains("{\"code\":\"de_CH\",\"supertext\":\"de-CH\"}"), body);
        assertTrue(body.contains("{\"code\":\"en_IE\",\"supertext\":null}"), body);
    }

    @Test
    void sendConfigurationSendsNothingWithReportsOff() {
        ReporterResult result = reports.sendConfiguration(INSTALLATION, CONNECTION, false, List.of(new Extension("a", "1")), List.of());
        assertFalse(result.isSent());
        assertEquals(List.of(), reportBodies());
    }

    @Test
    void ping() {
        PingResponse ok = VersionReports.ping("abcdefghijklmnop1234", true);
        assertEquals(200, ok.getStatus());
        assertEquals("{\"ok\":true,\"plugin\":\"Magnolia-Supertext-Translation\",\"challenge\":\"abcdefghijklmnop1234\"}", ok.getBody());
        assertEquals("no-store", ok.getHeaders().get("Cache-Control"));
        assertEquals(400, VersionReports.ping("<script>", true).getStatus());
        assertEquals(404, VersionReports.ping("abcdefghijklmnop1234", false).getStatus());
    }

    @Test
    void siteUrl() {
        assertEquals("https://cms.example.ch", VersionReports.siteUrl("https://cms.example.ch/", null));
        assertEquals("https://cms.example.ch/author", VersionReports.siteUrl(" ", "https://cms.example.ch/author"));
        assertEquals("https://cms.example.ch", VersionReports.siteUrl("http://localhost:8080/magnoliaAuthor/", "https://cms.example.ch"),
                "Magnolia's bootstrap default loses against a real request");
        assertEquals("http://127.0.0.1:8095", VersionReports.siteUrl("http://localhost:8080/magnolia/", "http://127.0.0.1:8095"),
                "the address the instance is really reached at");
        assertEquals("http://localhost:8080/magnolia", VersionReports.siteUrl("http://localhost:8080/magnolia/", null), "no request");
        assertNull(VersionReports.siteUrl("ftp://x", null));
        assertEquals("https://cms.example.ch", VersionReports.requestBase("https", "cms.example.ch", 443, ""));
        assertEquals("http://localhost:8080/ctx", VersionReports.requestBase("http", "localhost", 8080, "/ctx"));
        assertEquals("https://cms.example.ch/.supertext/ping", VersionReports.pingUrl("https://cms.example.ch/"));
    }
}
