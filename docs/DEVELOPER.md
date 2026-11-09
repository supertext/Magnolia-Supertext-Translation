# Developer guide — Supertext Translation for Magnolia

How the module is built, how to work on it, and how the demo is deployed.

## Architecture

```
Pages app "Translate with Supertext" (page list and page editor; openDialogAction, populate: false)
   └─ dialog supertext-translation:translate
        targetLanguages (checkBoxGroupField, datasource supertext-target-languages), overwrite, includeSubpages
        └─ commit: TranslateAction (editor's own JCR session)
             SiteLanguagesLookup → SiteLanguages (site i18n: default locale, targets, property names)
             PageTranslator, per target language, one after the other:
                collect page + areas/components (+ subpages) ─ DialogFieldResolver ─ SupertextService ─ Supertext Java SDK
                write name_<locale>, NodeTypes.LastModified.update, session.save()

Pages app "Export for translation" / "Import translation" (same availability; openDialogAction, populate: false)
   ├─ dialog supertext-translation:export (format radioButtonGroupField, targetLanguages)
   │    └─ ExportForTranslationAction: PageTranslator.fileSegments(page) → TranslationFileExchange.job per language
   │         → SDK TranslationFiles.exportXliff/exportHtml → StreamResource (attachment) → UI.getPage().open(…, "_blank")
   └─ dialog supertext-translation:import (uploadField ≤ 10 MB, includeChanged, overwrite)
        └─ ImportTranslationAction: TranslationFileExchange.read (SDK read) → per job: locate (plugin, reference)
             → page by identifier (editor's session), site language, write permission
             → PageTranslator.importFile (SDK importTranslations; same keys and overwrite rule) → Notification summary

App launcher → Translation → Supertext (superuser)
   └─ SupertextSettingsSubApp / SupertextSettingsView ── SettingsStore ── config:/modules/supertext-translation/config
        Save → ReportingService.afterSettingsSaved, Check connection → testConnection (key check shown)

Commando Center: ReportingService ── VersionReports ── Plugin Health InstallationReporter (+ SDK checkApiKey) ── JcrStateStore (config:/modules/supertext-translation/state)
   GET /.supertext/ping ── PingServlet (registered by RegisterPingTask at every start)
```

| Part | Role |
| --- | --- |
| `META-INF/magnolia/supertext-translation.xml` | Module `supertext-translation` (version from the `pom.xml`), depends on `core`, `ui-framework-jcr`, `pages-app`, `site`. Version handler `setup/SupertextVersionHandler`. Registers `SupertextSettings`, `SupertextService` and `reporting/ReportingService` (singletons) and the datasource `supertext-target-languages` (component id `datasource-supertext-target-languages`: a `SelectFieldSupport` and a `ListDataProvider`, the same pattern as the Pages app's template chooser). |
| `supertext-translation/decorations/pages-app/apps/pages-app.yaml` | Adds the action `translateWithSupertext` to the browser (`crud` section, `edit` group) and to the page editor (`pageActions` section, `edit` group). Decorated lists merge by item `name`, so the item is appended to Magnolia's own groups. Availability: write permission, `mgnl:page`, not marked for deletion. |
| `supertext-translation/dialogs/translate.yaml` | The dialog. Not bound to the page (`populate: false`): `TranslateAction` reads the values with `EditorView.getPropertyValue`. |
| `supertext-translation/apps/supertext.yaml`, `decorations/admincentral/config.yaml` | The Supertext app (`ConfiguredAppDescriptor`, `BaseApp`, one sub-app), `superuser` only, in the launcher group `translation` (Magnolia 6.4's layout has no *Tools* group). |
| Supertext Java SDK (`…translation.internal.sdk`, `lib/supertext-sdk/`) | The [Supertext SDK](https://github.com/Supertext/Supertext-API-SDK-and-Guide) does the protocol (below): `translateTexts` packs the texts into HTML documents (`<div data-st-id="N">`, at most 900 000 characters each), uploads, polls, downloads, reads the texts back and deletes the file; 429 retries; one `Authorization` prefix; `Languages` (the live language list, mapping and exact match, see *Language codes*); the key check, `InstallationReporter` and `PingResponse` (see *Commando Center*). Plain text is HTML-encoded (line breaks as `<br>`); rich text goes as it is, **one segment per field**. Magnolia's internal links (`${link:{…}}`) are in `href` attributes, which Supertext keeps. Bundled in `lib/supertext-sdk/` and compiled into the module's jar as `com.supertext.magnolia.translation.internal.sdk` (API v1 in `…internal.sdk.v1`; see *Supertext SDK*). |
| `api/SupertextService` | The module's adapter on the SDK: builds a client from the settings (endpoint, key without a pasted prefix, poll interval, timeout, User-Agent `Magnolia-Supertext-Translation/<version>`, the version from the jar manifest as in the reports), allows only https endpoints (http for localhost), turns the SDK's error codes into `SupertextException`s with a message key (table under *Errors*), resolves language codes (`targetCode`/`sourceCode` on the SDK's `Languages`) and sends the form of address only where `Languages.supportsPoliteness(target)` is true. `api/LanguageMapping` holds the *Language codes* choices (and parses the 0.1.x text for the migration), gives each combo box its preselection (`selection()`: stored code, else `Languages.suggestTarget`) and the settings screen each language's status. No Magnolia classes; unit-tested with a fake SDK `Transport`. |
| `content/SiteLanguages`, `ui/SiteLanguagesLookup` | The page's site (`SiteManager.getAssignedSite(page).getI18n()`, falling back to the global `I18nContentSupport`). The default locale is the source; property names follow Magnolia's dialogs: the default locale is the bare name, others get `"_" + I18nContentSupport.getLanguageString(locale)` (`title_de_CH`). |
| `content/DialogFieldResolver` | Which properties to translate: the node's `mgnl:template` → `TemplateDefinition.getDialog()` → `DialogDefinitionRegistry` → a Magnolia 6 `FormDialogDefinition`; every `TextFieldDefinition` (plain) and `RichTextFieldDefinition` (HTML) with `i18n: true`. Composite fields: sub-fields on the same node (`CurrentItemProviderDefinition`) or in a child node (`NestedContentProvider`/`JcrChildNodeProviderDefinition`, `TranslatableField.childNode`); an `i18n` composite with child nodes (one child node per locale) is skipped. Plus the *Also translate* property names. |
| `content/PageTranslator` | Plain JCR. Collects the page, its non-page descendants (areas, components, nested nodes) and, if asked, subpages; skips `jcr:*` and `mgnl:deleted`. Per target: keeps non-empty translations unless `overwrite`, sends the remaining texts in one `TextTranslator` call (key = node path + `@` + property; in the module that is `SupertextService.translateTexts`), writes the results, marks changed nodes and their page modified, saves. A Supertext error for one language, including an answer with a text missing, rolls that language back (`session.refresh(false)`) and goes on; a missing/rejected key aborts everything. Unit-tested with Magnolia's `MockNode`. |
| `SupertextSettings`, `ui/settings/SettingsStore` | Settings in effect, read from `config:/modules/supertext-translation/config` **on every use** (system context; Magnolia doesn't refresh the module bean when that node is created at runtime), with `SUPERTEXT_API_KEY` / `SUPERTEXT_API_ENDPOINT` (environment or system property) winning; `versionReportsEnabled()` = the setting (`null` = on) unless `SUPERTEXT_VERSION_REPORTS` decides. `SupertextTranslationModule` is the module class and the settings bean. |
| `content/TranslationFileExchange` | *Export for translation* / *Import translation* without Magnolia classes (unit-tested): reference format, `FileJob`s, file names and types, the upload size check, the `plugin`/`reference` check, SDK file errors as keyed `SupertextException`s (`supertext-translation.files.error.*`). See *Translation files*. |
| `ui/ExportForTranslationAction`, `ui/ImportTranslationAction` | Commit actions of the `export` and `import` dialogs (`dialogs/export.yaml`, `dialogs/import.yaml`, actions `exportForTranslation`/`importTranslation` in the Pages app decoration). |
| `ui/TranslateAction` | Validates the form, resolves the languages, checks for a key (dialog stays open on key problems), runs `PageTranslator`, closes the dialog, refreshes the list (`DatasourceObservation.Manual.trigger`) and shows a Vaadin `Notification` with the summary. Messages come from the i18n bundle (see *Interface strings*) via `SimpleTranslator`; Supertext errors are shown through `ui/SupertextMessages`. |
| `ui/settings/SupertextSettingsView` | Plain Vaadin 8 form: key (never shown back; empty keeps it), endpoint, form of address, *Language codes*: a `GridLayout` with one row per site language (`SiteManager.getSites()`, else the global `I18nContentSupport`; main languages included), a Vaadin `ComboBox` of `Languages.targetOptions()` (*Name (code)*, names from Java's display names in the user's language, sorted with a `Collator`; preselected with `LanguageMapping.selection()`, else *— choose —*) and how it is sent (target, source, formal/informal, unsupported in red); while a review URL is set, a *Translation review* `CheckBox` per target language between them (stored with `SettingsStore.writeReviewLanguages`, see *Translation review*); *Save* checks every value against the SDK's list (a code kept from 0.1.x is allowed to stay) and stores them with `SettingsStore.writeLanguageCodes` (child node `languageCodes`, one property per language; entries of languages no site has any more are kept), excluded/additional properties, timeout, *Send version information to Supertext*, the ping address; *Save* and *Check connection* show the API key check (`SupertextMessages.keyCheckHtml`); opening the app runs `onRequest`; version from the jar manifest (`Implementation-Version`) linked to its GitHub release. |
| `reporting/VersionReports` | Commando Center glue without Magnolia classes: builds Supertext Plugin Health's `InstallationReporter` (plugin `Magnolia-Supertext-Translation`, system `Magnolia`, `sdk_version` = the SDK's `SdkInfo.VERSION`, callback `<site>/.supertext/ping`, the SDK's `checkApiKey()` against the endpoint in effect as key checker), runs one action with the stored state and stores the result, never throws; site URL rule; ping answer. Unit-tested with a fake SDK `Transport` that also answers the reports (`VersionReportsTest`). |
| `reporting/ReportingService`, `reporting/JcrStateStore`, `reporting/PingServlet` | Magnolia side: site URL, versions, background thread, state in `config:/modules/supertext-translation/state` (property `reporter`), the public ping servlet. |
| `setup/SupertextVersionHandler`, `setup/RegisterPingTask`, `setup/MigrateLanguageCodesTask` | At every start: moves a 0.1.x `languageMapping` text into the `languageCodes` node (`SettingsStore.migrateLegacyMapping`; until then `readLanguageCodes` reads both, the node's entries winning);  registers `PingServlet` as `SupertextPing` in `/server/filters/servlets` (Magnolia's `RegisterServletTask`, mapping `/.supertext/ping`) and adds `supertextPing` bypasses (`URIStartsWithVoter`) to the `adminSetup`, `csrfTokenSecurity`, `uriSecurity` and `cache` filters, only what is missing. |

## Supertext API protocol

AI file translation API v1, shared with all Supertext plugins. The SDK implements it; the module only configures it:

1. `POST translate/ai/file` — multipart: `file` (`content.html`, part `Content-Type: text/html` exactly), `target_lang` (`de-CH`), `source_lang` (bare language, `en`; see *Language codes*), optional `politeness` (`more`/`less`) → `{file_id}`
2. `GET translate/ai/file/{id}/status` every 2 s until `done` (`error`, `limit_exceeded`, `deleted` fail; timeout from the settings, default 300 s)
3. `GET translate/ai/file/{id}/translation` → translated HTML
4. `DELETE translate/ai/file/{id}` (files also expire after 24 h)

Header `Authorization: Supertext-Auth-Key <key>` (a pasted prefix is stripped, exactly one is sent). HTTP 429 is retried up to 4 times (`Retry-After`, else 1/2/4/8 s with jitter). Languages, and the documents of a large page, are translated one after the other to stay under the per-second limit. The Supertext app calls `GET features` (free) to check the key.

### Errors

`SupertextService` maps the SDK's error codes to the module's message keys (`supertext-translation.error.*`, in all four languages):

| SDK code | Key | Note |
| --- | --- | --- |
| `missing_api_key` | `noApiKey` | Key problem: the dialog stays open and shows the links. |
| `authentication_failure` | `authentication` | Key problem, as above. |
| `invalid_language_pair` | `invalidLanguagePair` | `{0}` source, `{1}` target code. Also a language Supertext doesn't know at all (HTTP 404 with `INVALID_LANGUAGE_PAIR`), not `not_found`. |
| `unsupported_language` | `unsupportedLanguage` | `{0}` the Magnolia language. From `Languages.target`/`source`: no mapping and no exact match; nothing is sent, that language fails, the others go on. The message says where to map it. |
| `unsupported_feature` | `unsupportedFeature` | `{0}` the option (`politeness`), `{1}` the target. Only for codes the SDK doesn't know yet (for known ones the form of address isn't sent where it isn't offered). |
| `not_found`, `payload_too_large`, `too_many_requests`, `service_unavailable` | `notFound`, `tooLarge`, `rateLimit`, `unavailable` | |
| `unexpected_status` | `http` | `{0}` the HTTP status. |
| `transport_error` | `unreachable` | `{0}` the reason (connection refused, timeout, …). |
| `no_file_id`, `translation_error`, `quota_exceeded`, `file_deleted`, `timeout` | `noFileId`, `failed`, `limitExceeded`, `deleted`, `timeout` | |
| `incomplete_response` | `incomplete` | A text was missing from the answer (or the answer was empty); nothing is saved for that language. |

Supertext's answer, if any, is shown in brackets after the message. Endpoint problems (`endpointNotHttps`, `endpointInvalid`) are the module's own check. `SupertextServiceTest.everySdkErrorHasAMessage` fails when the SDK gains a code without a key.

### Language codes

Supertext accepts exactly the codes its `GET features` lists (the SDK's `Languages`, `spec/languages.json` in the SDK repo): 28 bare sources (`en`, `de`); 37 targets in one exact form each, regional for German, English, French, Italian and Portuguese (`de-CH`, `en-US`, `pt-BR`), with a script for Chinese and Serbian (`zh-Hans`, `sr-Latn`), Swiss German dialects (`gsw-u-sd-chzh`), bare for the others (`es`, `nl`, `sl`). Anything else is HTTP 404 `INVALID_LANGUAGE_PAIR`; formal/informal for a target without it (`en-*`, `rm`, `sq`, `sr-*`, `gsw-*`) is HTTP 400 `FEATURE_ERROR` (`unsupported_feature`).

**Nothing guessed beyond the SDK's obvious defaults.** `SupertextSettings.targetLanguageCode`/`sourceLanguageCode` pass the Magnolia locale (`Locale.toString()`, e.g. `de_CH`) and the *Language codes* choices (`api/LanguageMapping`, from the `languageCodes` node) to the SDK (`SupertextService.targetCode`/`sourceCode` → `Languages.target`/`source`):

- **Target:** the mapping's entry (keys compared like codes: case and `_`/`-` ignored; sent trimmed and otherwise as it is, even a code the SDK doesn't list yet); else the locale if it equals a Supertext code (`de_CH` → `de-CH`, `es`); else the SDK's default target (SDK 0.4.0: `de` → `de-DE`, `en` → `en-US`, `es_ES` → `es`, `zh_CN` → `zh-Hans`); else `unsupportedLanguage` with the locale, reported for that language without calling Supertext (`de_LI`, `en_IE`, `gsw`).
- **Source** (the site's default locale): the same mapping, in its source form (`de=de-CH` → `de`), else the locale's first subtag if that is a source (`en_GB` → `en`), else `unsupportedLanguage`. One mapping serves both directions (SDK 0.2.1).
- **Form of address:** sent only when `Languages.supportsPoliteness(target)` is `true` (`SupertextService.politenessFor`); unknown codes get none.
- **Settings screen:** a combo box per site language (`Languages.targetOptions()`), preselected with the stored code, else `Languages.suggestTarget` (exact match, else default), else *— choose —*; next to it `LanguageMapping.status()` for the chosen code: as target, as source, formal/informal, unsupported. Saving stores every combo box's value, so a preselected default becomes an explicit choice.

The stand-in (`tools/docs/stand-in.mjs`) accepts exactly Supertext's target list and bare sources, answers anything else with 404 `INVALID_LANGUAGE_PAIR` and politeness for `en`/`gsw`/`rm`/`sq`/`sr` with 400 `FEATURE_ERROR`, like the live API.

### Translation review

Per target language (checkbox *Translation review* next to the combo box, stored in `config:/modules/supertext-translation/config/languageReview` as `de_CH = true`, read by `SupertextSettings.reviewFor`): `TranslateAction` passes `SupertextSettings.connection(target)`, which is `connection().forReview(reviewFor(target))`. `SupertextConnection.forReview(true)` returns a connection with the same key, poll interval and timeout whose endpoint is the review URL; `SupertextService` builds its SDK client from that (same transport and User-Agent), and since the SDK's `translateTexts` uploads, polls, downloads and deletes with one client, a job is always collected through the service that received it. The review URL is `SUPERTEXT_REVIEW_URL`, else the config property `reviewUrl` (`""` = off for every language), else `SupertextService.REVIEW_URL`, the translation proxy of [Supertext Localization Review](https://github.com/Supertext/Localization-Review-Supertext-Translation): it speaks the same API v1, forwards to the live Supertext API with the module's key, stores every segment and serves reviewers' corrections on the next upload. With an empty review URL, or the same URL as the endpoint, `forReview` returns the direct connection and the app hides the checkboxes. The key check (`VersionReports`), the reports and file export/import always use `connection()`. Tests: `SupertextServiceTest` (all four calls go to the review URL, others directly, empty or same URL means direct, default `REVIEW_URL`), `SupertextSettingsTest` (per language, default, empty, environment wins), `SettingsStoreTest` (stored per language, off by default).

### Translation files (XLIFF and HTML)

*Export for translation* and *Import translation* use the SDK's `TranslationFiles` (SDK 0.5.0, package `…internal.sdk.files`; integration guide → *Translation files: XLIFF and HTML*, vectors `spec/translation-file.json`). Nothing here calls the API, so no API key is needed.

- **Segments:** the page's own texts as `PageTranslator` collects them for API translations (the page, its areas and components; no subpages), keyed by the node's path below the page, `@` and the property (`@title`, `main/0@text`, `main/0/teaser@teaserTitle`). API translations use the same keys (relative, so they survive moving or renaming a page).
- **Job:** `plugin` = `Magnolia-Supertext-Translation`; `reference` = `page:<page identifier>:<language>` (the page's JCR identifier and the Magnolia language id of the target, e.g. `page:7a1e2b3c-…:de_CH`); `sourceLanguage`/`targetLanguage` = the codes the *Language codes* setting resolves (`SupertextSettings.sourceLanguageCode`/`targetLanguageCode`), or the language id with `_` → `-` when it has none, so unmapped languages don't block an export.
- **Export:** XLIFF with one `<file>` per language (`magnolia-<page id>-<language>.xlf`, or `magnolia-<page id>.xlf` for several), `application/x-xliff+xml`; HTML for exactly one language (`magnolia-<page id>-<language>.html`, `text/html; charset=utf-8`). Served by Vaadin as a `StreamResource` with `Content-Disposition: attachment`, opened in a new tab like Magnolia's own JCR export (`DownloadHelper`). Nothing to translate → a warning, no file.
- **Import:** the dialog's `uploadField` (`maxUploadSize: 10485760`) refuses larger files; the action checks the size again, reads the temporary file once and deletes it. Per job: `plugin` must be ours (*other system*), the reference must parse and name an existing page the editor's session can see (*not found*), the language must be one of the site's other than its main language (*no language*), the editor needs `set_property` on the page. `PageTranslator.importFile` collects the texts again, writes `ok` results, `source_changed` ones only with *Also import texts whose source changed*, keeps existing translations unless *Overwrite existing translations*, marks changed nodes modified and saves once (rolled back on an error). `untranslated`, `invalid_markup` and `missing` are not written. The summary is a Vaadin notification.

### Commando Center: version reports, key check, ping

Protocol: [Commando-Center-Supertext-Translation](https://github.com/Supertext/Commando-Center-Supertext-Translation) → `docs/DEVELOPER.md` → *Report protocol*. Supertext Plugin Health's `InstallationReporter`, `Reporting.reportsEnabled()` and `PingResponse` implement it, with the SDK's `checkApiKey()` as key check; `reporting/VersionReports` (no Magnolia classes) loads the state, runs one reporter action and stores the new state, never throwing; `reporting/ReportingService` gives it what Magnolia knows.

- **Setting:** `versionReports` in `config:/modules/supertext-translation/config` (*Send version information to Supertext*, a checkbox, on when not set). `SUPERTEXT_VERSION_REPORTS` (`0/false/off/no`, `1/true/on/yes`) wins; the checkbox is then disabled. Off: no reports, ping 404. `SUPERTEXT_REPORT_URL` sends reports elsewhere (CI: the stand-in's `POST /report`; `GET /reports` lists what it got).
- **Report fields:** `site_url` = `/server/defaultBaseUrl`, unless that is a `localhost`/`127.*` address (Magnolia's bootstrap default `http://localhost:8080/magnolia/`) and there is a request, then `scheme://host[:port]/context` of the request (`RemoteIpValve` makes it https behind Railway); plugin `Magnolia-Supertext-Translation` with `SupertextService.version()` (the jar's `Implementation-Version`, from `pom.xml`); system `Magnolia` with the `core` module's version (`ModuleRegistry`); runtime Java (Plugin Health's default); `sdk_version` = `SdkInfo.VERSION`; `callback.url` = `<site URL>/.supertext/ping`. HTTP through the JDK's `HttpClient` (Plugin Health's `JdkHttpTransport`, 3 s per report; the SDK's `JdkHttpTransport`, 10 s, for the key check, which goes to the endpoint in effect; a non-https endpoint falls back to the live API so the key never goes over plain http).
- **When:**
  - `afterSettingsSaved`: *Save* in the Supertext app, after the settings are stored; synchronous, the key check is shown below the buttons.
  - `testConnection`: *Check connection* (the saved key; a typed, unsaved key asks to save first).
  - `onRequest`: opening the Supertext app and every *Translate with Supertext*; in a background thread (one daemon thread, queue of 10, extra work dropped), with the site URL taken from the request first. Never on website requests.
  - `authenticationFailed`: a translation rejected with HTTP 401/403 (`SupertextException.isKeyRejected()`), in the background.
- **State:** Plugin Health's `ReporterState` JSON (the same format the SDK 0.2 reporter stored, so updated installations send no new `installed`) in `config:/modules/supertext-translation/state`, property `reporter`, read and written with system rights (`JcrStateStore`). Load and save errors are swallowed (logged at debug). The API key is never stored there or logged; only the SDK's fingerprint of it.
- **Ping:** `reporting/PingServlet` (`HttpServlet`, GET only) writes `PingResponse`'s status, headers (`Content-Type: application/json`, `Cache-Control: no-store`) and body. `setup/RegisterPingTask` registers it and the filter bypasses at every start, so updated installations get it without an update task.
- **Messages:** `ui/SupertextMessages.keyCheckHtml()` with the center's five texts (`supertext-translation.keyCheck.*`, four languages, `{0}`/`{1}` the account and API key links in a new tab, `{2}` the HTTP status or error code); `MessageBundlesTest.keyCheckMessages` checks them.

## Local development

Java 21, Maven 3.9, Docker, Node 20+ (stand-in API, end-to-end check, screenshots). Magnolia's artifacts come from `https://nexus.magnolia-cms.com/content/groups/public` (declared in the poms), some Vaadin add-ons from `https://maven.vaadin.com/vaadin-addons`.

```bash
mvn install                                   # module (with the bundled SDK) + unit tests, into ~/.m2
mvn -f demo/pom.xml package                   # demo webapp: demo/target/ROOT.war
docker build -f demo/Dockerfile -t supertext-magnolia-demo .   # what Railway builds

cd tools/docs && npm install && STAND_IN_PREFIX=1 node stand-in.mjs &   # untranslated text comes back as "[de-CH] …"
docker run --rm -p 8080:8080 -v magnoliademo:/data --add-host host.docker.internal:host-gateway \
  -e DEMO_ADMIN_EMAIL=admin@example.com -e DEMO_ADMIN_PASSWORD=… \
  -e DEMO_EDITOR_EMAIL=editor@example.com -e DEMO_EDITOR_PASSWORD=… \
  -e SUPERTEXT_API_KEY=test -e SUPERTEXT_API_ENDPOINT=http://host.docker.internal:8765/v1/ \
  -e SUPERTEXT_REPORT_URL=http://host.docker.internal:8765/report \
  supertext-magnolia-demo
```

The first start installs Magnolia into the volume (about a minute); sign in at <http://localhost:8080/.magnolia/admincentral> with the e-mail address as user name. Remove the volume for a fresh start. After changing the module, rebuild the image (or the war and the image).

Magnolia's default log config sets `com` to `WARN`; the demo's `log4j2.xml` adds `com.supertext` at `INFO`.

### Supertext SDK

The SDK provides the API client and the API key check (`checkApiKey()`, `ApiKeyCheck.fingerprint()`, `SdkInfo.VERSION`); version reports and the ping answer come from Supertext Plugin Health (next section). The module bundles the Java SDK from [Supertext-API-SDK-and-Guide](https://github.com/Supertext/Supertext-API-SDK-and-Guide) (`sdk/java`, Java 11+, no dependencies) as source in `lib/supertext-sdk/`: the SDK's classes, `LICENSE`, `COMMIT` (the full SDK commit SHA), `VERSION` (the SDK's package version) and a short README, with the package changed from `com.supertext.sdk` to `com.supertext.magnolia.translation.internal.sdk`. The `build-helper-maven-plugin` adds `lib/supertext-sdk/src` as a source folder, so `mvn install` compiles the SDK into the module's jar: administrators still install one jar, another plugin or the webapp can ship its own SDK without a clash, and no build step or local Maven install is needed before Maven (CI, the release workflow and `demo/Dockerfile` just run Maven; the Dockerfile copies `lib/`). The SDK's MIT licence is in the jar as `META-INF/LICENSE-supertext-sdk.txt`.

- Since SDK 0.2.0 everything that talks to the API is in the subpackage `v1` (`…internal.sdk.v1`); `SdkInfo`, `Transport` and `JdkHttpTransport` stay in `…internal.sdk`. Since 0.5.0 the translation files are in `…internal.sdk.files`. The script copies all three.
- Update it with `scripts/vendor-sdk.sh [commit-or-branch]` (default `main`; `SDK_DIR=/path/to/clone` uses a local clone as it is), then `mvn verify`, and note user-visible changes in `CHANGELOG.md`.
- Never edit `lib/supertext-sdk/`: fix the SDK in its own repository, then re-run the script.

### Supertext Plugin Health

Version reports to the Supertext Commando Center, the API key check result and the ping answer come from Supertext Plugin Health ([Commando-Center-Supertext-Translation → `plugin-health/java`](https://github.com/Supertext/Commando-Center-Supertext-Translation/tree/main/plugin-health), Java 17+, no dependencies). `reporting/VersionReports` passes it the SDK's key check (`KeyChecker.of(…checkApiKey()…, ApiKeyCheck::fingerprint)`) and `SdkInfo.VERSION`. It is bundled exactly like the SDK: source in `lib/supertext-plugin-health/` (classes, `LICENSE`, `COMMIT`, `VERSION` with the Plugin Health version, a short README), package changed from `com.supertext.pluginhealth` to `com.supertext.magnolia.translation.internal.pluginhealth`, added as a second source folder by the `build-helper-maven-plugin` and compiled into the module's jar. Its MIT licence is in the jar as `META-INF/LICENSE-supertext-plugin-health.txt`.

- Update it with `scripts/vendor-plugin-health.sh <tag>` (e.g. `plugin-health-v0.1.0`; default `main`; `PLUGIN_HEALTH_DIR=/path/to/clone` uses a local clone of the Commando Center repository as it is), then `mvn verify`, and note it in `CHANGELOG.md`.
- Never edit `lib/supertext-plugin-health/`: fix it in Commando-Center-Supertext-Translation/plugin-health, then re-run the script.

### Interface strings

All texts the module shows live in Magnolia's message bundle `src/main/resources/supertext-translation/i18n/module-supertext-translation-messages_{en,de,fr,it}.properties` (UTF-8, as Magnolia 6 reads them; no `\u` escapes needed). YAML definitions reference the keys (`label: supertext-translation.dialog.label`), Java code uses `SimpleTranslator`. Every new or changed string goes into all four files in the same commit: formal address (Sie, vous, Lei), Magnolia's own terms in each language (page/Seite/page/pagina, component/Komponente/composant/componente, publish/veröffentlichen/publier/pubblicare), never translate "Supertext", placeholders or URLs, and French gets a non-breaking space before `? ! : ;` and inside `« »`.

- `SimpleTranslator` runs `MessageFormat` when there are arguments, so a message with `{0}` must not contain a straight apostrophe (`'` starts a quoted section): French and Italian use `’`.
- `api/` stays free of Magnolia classes: a `SupertextException` carries an English message (logs) plus a key under `supertext-translation.error.*`, its arguments and Supertext's answer (`detail`); `ui/SupertextMessages` turns it into the user's language and builds the API key hint (`supertext-translation.apiKey.hint` / `hintHtml`, `{0}` = sign-up URL, `{1}` = API key URL).
- `MessageBundlesTest` checks that all four files have the same keys and placeholders, that every key used in the Java code and YAML exists, the apostrophe rule, the French spacing and both links in the hint.

## Tests

```bash
mvn test        # SupertextService (fake SDK transport: request, auth header, 429, error keys, politeness), settings, LanguageMapping and language codes (combo box preselection, migration of the 0.1.x text: LanguageMappingTest, SettingsStoreTest on a MockSession), VersionReports (fake transport: reports, key check, ping, site URL; never the live center), DialogFieldResolver, PageTranslator (MockNode; also file export/import: keys, ok written, source_changed only when asked, untranslated/invalid/missing not written, existing kept), TranslationFileExchange (reference round trip, export with the mapping, other plugin, unknown reference, too large and bad files), message bundles (MessageBundlesTest)
```

End to end against a running demo and the stand-in: `cd tools/docs && DEMO_URL=http://localhost:8080 DEMO_EDITOR_EMAIL=… DEMO_EDITOR_PASSWORD=… npm run e2e` signs in as the editor, translates the sample page into German in the Pages app and checks `/de_CH/supertext-demo.html`, then exports the page for translation (HTML), changes the file and imports it again (summary, German page shows the imported text).

CI (`.github/workflows/ci.yml`): builds and tests the module (with the bundled SDK), builds the demo image from `demo/Dockerfile`, starts it with test accounts and the stand-in, checks that both accounts were created and that no password appears in the log (the log is captured into a variable first; never `docker logs … | grep -q` under `pipefail`), runs `e2e.mjs`, then calls `/.supertext/ping` (answer, `no-store`, 400 for a bad challenge) and checks that the translation reported the installation to the stand-in (`SUPERTEXT_REPORT_URL`) without the key.

Before a release, also check by hand (fresh demo, stand-in with `STAND_IN_PREFIX=1`): translate the sample page into all three languages from the page list and from the page editor (every text carries the marker, the quote's author and the link URL don't), translate German again without and with *Overwrite*, try *Also translate all subpages* with a subpage, and remove the key to see the *No Supertext API key* message.

## Docs screenshots

`docs/images/` is generated by `tools/docs/screenshots.mjs` from a **fresh** demo whose module talks to the stand-in **without** `STAND_IN_PREFIX`. The stand-in returns real German for the sample content (`tools/docs/sample-de.json`), so the guides never show placeholder text.

Start the demo with `SUPERTEXT_API_ENDPOINT` pointing to the stand-in but **without** `SUPERTEXT_API_KEY`: the script stores a dummy key in the Supertext app like an administrator (the stand-in accepts any key). The settings screenshot then shows *The endpoint comes from the SUPERTEXT_API_ENDPOINT environment variable* and the live endpoint as placeholder, never the local URL.

```bash
cd tools/docs && npm install && npx playwright install chromium && node stand-in.mjs &
# start the demo fresh (see Local development, minus SUPERTEXT_API_KEY), then:
DEMO_URL=http://localhost:8080 DEMO_ADMIN_EMAIL=… DEMO_ADMIN_PASSWORD=… DEMO_EDITOR_EMAIL=… DEMO_EDITOR_PASSWORD=… npm run screenshots
```

The settings screenshot leaves out the ping address line (`.supertext-ping`), which would show the local demo URL.

New texts in the sample: run the stand-in with `STAND_IN_DUMP=<dir>` and translate what it writes to `<dir>/de-CH.json` into `sample-de.json`. Avoid the *Definitions* app in scripts: in Magnolia 6.4.10 it sends a message without a type, which breaks AdminCentral's banner and leaves error notifications behind.

## Demo movie

`docs/images/demo.gif` (shown at the top of the README) is recorded by `tools/docs/movie.mjs` with Supertext's movie kit (`tools/docs/movie/`: Playwright screencast → ffmpeg; intro and outro cards, captions, cursor; Inter font under the SIL Open Font License in `movie/assets/fonts/`). It needs the demo and the stand-in set up like for the screenshots (the stand-in **without** `STAND_IN_PREFIX`, so the German result is real German), `ffmpeg` and, for the voice, Piper:

```bash
pip install piper-tts                       # the voice; without it (or with MOVIE_VOICE=0) the movie is silent
cd tools/docs && npm install && node stand-in.mjs &
# demo with SUPERTEXT_API_ENDPOINT pointing at the stand-in and an API key stored (e.g. after the install movie)
DEMO_URL=http://localhost:8080 DEMO_EDITOR_EMAIL=… DEMO_EDITOR_PASSWORD=… npm run movie
```

The demo editor opens the sample page in the page editor (English), clicks *Translate with Supertext* in the action bar, ticks *German (Switzerland) · de_CH*, clicks *Translate* (the waiting time is cut), and switches the page editor's language selector to German. The script writes the GIF (800 px, 12 fps, keep it under 3 MB) to `docs/images/demo.gif` and the MP4 to `tools/docs/movie/out/Magnolia-Supertext-Translation.mp4` (git-ignored; `MOVIE_OUT` changes the path). The MP4 is not committed; the GIF is silent. The voice model (63 MB, pinned revision, checked by SHA-256) is downloaded once into `tools/docs/movie/voice-cache/` (git-ignored, never committed); the time spent generating speech is cut from the movie. Re-record it when the translate dialog or the page editor's action bar changes.

**Install and configuration movie** (`tools/docs/movie-install.mjs`, `npm run movie:install`): the same kit, voice and look, for administrators. It needs a **fresh** demo started like for the screenshots, without `SUPERTEXT_API_KEY` (the script types the made-up key `st-demo-0000-0000-0000` into the Supertext app; the stand-in accepts any key), signed in as the demo **administrator**:

```bash
DEMO_URL=http://localhost:8080 DEMO_ADMIN_EMAIL=… DEMO_ADMIN_PASSWORD=… npm run movie:install
```

A terminal (`kit.terminal()`) with the steps from `docs/INSTALLATION.md` (`mvn install:install-file` with the release jar, the dependency in the webapp's `pom.xml`, `mvn package`, the module's installation in the log, the site definition's `i18n` languages; output shortened), then AdminCentral: app launcher → *Translation* → *Supertext*, where the key comes from (supertext.com → Integrations → API; create an account at supertext.com), the fake key in *API key*, *Language codes* (de_CH kept as *German (Switzerland) (de-CH)*, *Translation review* left off), *Save* with the API key check (*the connection to Supertext works*), and *Translate with Supertext* in the Pages app. The ping address line (the demo's local URL) is removed from the page before it is shown. No GIF; the MP4 goes to `tools/docs/movie/out/Magnolia-Supertext-Translation-install.mp4` (git-ignored, not committed; `MOVIE_OUT` changes the path). Record it before the demo movie on the same fresh demo (it stores the key the demo movie needs). Re-record it when the installation steps or the Supertext app change. Don't wait for network idle in these scripts (Vaadin's push connection stays open) and don't open the *Definitions* app.

Voice: Piper voice “alba” (en_GB), trained on a dataset from the University of Edinburgh's Centre for Speech Technology Research, CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)

## Demo (Railway)

The public demo is a container built from `demo/Dockerfile`: Magnolia 6.4.11-sp1 Community Edition on Tomcat 11 / Java 21, a small demo module (site *supertext* with English, German, French and Italian (Switzerland), a page template with *Text* and *Quote* components, the sample page `/supertext-demo`) and this module. It runs on Railway in the `supertext-cms-demos` project, service `magnolia`, region EU West (Amsterdam): <https://magnolia-production-2b73.up.railway.app/> (AdminCentral at `/.magnolia/admincentral`, sign in with the e-mail address).

**Deploys:** Railway watches `main` and rebuilds on every push.

| File | Purpose |
| --- | --- |
| `demo/Dockerfile` | Builds the module with the bundled SDK (`lib/`, `src/`; `mvn install`), then `demo/pom.xml` (war, with the module version passed as `supertext.version`), and runs the exploded `ROOT` webapp on `tomcat:11.0-jdk21-temurin` with a `RemoteIpValve` (Railway terminates TLS). |
| `demo/pom.xml` | `magnolia-community-webapp` (war overlay + pom) with Magnolia's bundle BOM imported (without it, Maven picks older Magnolia jars and Magnolia refuses to start), plus this module. |
| `demo/entrypoint.sh` | Creates the folders on `/data`, applies `PORT`, sets the heap (`MAGNOLIA_HEAP`, default 1536 MB), starts Tomcat. |
| `demo/src/main/webapp/WEB-INF/config/default/magnolia.properties` | Replaces the empty webapp's: repository (H2 + Jackrabbit), keys, cache and logs on `/data`; `magnolia.update.auto=true` (no install wizard); no samples. |
| `demo/src/main/webapp/WEB-INF/config/default/log4j2.xml` | Magnolia's default plus `com.supertext` at `INFO`. |
| `demo/src/main/java/…/SupertextDemoModule.java` | Module `supertext-demo`; on every start: creates the demo accounts if missing, completes Magnolia's first-run admin setup, keeps the built-in `superuser` disabled, creates the sample page if missing. |
| `demo/src/main/resources/supertext-demo/` | Site definition (`sites/supertext.yaml`, with the languages), templates, dialogs (fields `i18n: true`). |
| `demo/.env.example` | All variables. |

**State:** the JCR repository, keys and logs live on a Railway volume at `/data`. To reset the demo, delete the files on the volume and redeploy.

**Accounts** (created on every start if missing; existing ones are never changed; the e-mail is the user name):

| Variables | Account |
| --- | --- |
| `DEMO_ADMIN_EMAIL`, `DEMO_ADMIN_PASSWORD` | `superuser` role. Once it exists, the demo completes Magnolia's first-run admin setup (the `/.magnolia/adminsetup` screen that asks for the built-in superuser's password), so the screen no longer appears, and keeps the built-in `superuser` account disabled. Without these variables Magnolia shows that screen. |
| `DEMO_EDITOR_EMAIL`, `DEMO_EDITOR_PASSWORD` | Roles `admincentral-editor`, `pages-app-editor`, `dam-app-core-editor`, `dam-app-jcr-editor`, `imaging-editor`, `resources-editor`, `security-base` and the `editors` group: edits, translates and publishes pages. Magnolia has no per-language permissions, so the editor can work in every language. Magnolia Community Edition has no ready-made editor role, so this is the closest combination of its standard roles. |

Magnolia's own rules apply: the password must not be empty, and the user name (the e-mail) must not contain characters Magnolia forbids in user names (`MgnlUserManager.VALID_USERNAME_REGEX`). Otherwise the account is skipped with a warning naming the variable; the demo still starts. Passwords are never logged.

**Service variables:** `DEMO_*` (above), `SUPERTEXT_API_KEY`, optional `SUPERTEXT_API_ENDPOINT` and `SUPERTEXT_VERSION_REPORTS`, `PORT=8080` (the domain's target port), `MAGNOLIA_HEAP`, `RAILWAY_DOCKERFILE_PATH=demo/Dockerfile`. On Railway the `DEMO_*` variables reference the umbraco service's (`${{umbraco.DEMO_ADMIN_EMAIL}}` …) and `SUPERTEXT_API_KEY` the orchardcore service's, so the demos share one set.

## Dependency updates

Dependabot (`.github/dependabot.yml`) opens weekly pull requests: Maven minor and patch updates grouped into one, GitHub Actions in another, each major update on its own. Merge one when CI is green and it doesn't change what the module supports.

`.github/workflows/dependabot-merge.yml` merges a minor or patch package update by itself once every other check on its pull request has passed (it waits up to 85 minutes and leaves the pull request alone if a check fails). GitHub Actions updates and majors stay for a person: the workflow's built-in token can't merge changes to workflow files, and a major needs a decision. Merges by the workflow don't start CI on `main`; the weekly health check looks at `main`.

Everything Magnolia provides at runtime is ignored on purpose and updated by hand: the Magnolia artifacts (`info.magnolia*`: `magnolia.version` and the other Magnolia properties are the oldest Magnolia the module supports, see `docs/INSTALLATION.md` → Requirements), and Vaadin (`provided` scope, so it must be the version that Magnolia release ships). When you raise `magnolia.version`, also raise it in `demo/pom.xml` and set Vaadin to what `mvn dependency:tree -Dverbose -Dincludes=com.vaadin` shows Magnolia bringing.

## Code quality and security checks

- **Checks** workflow (`.github/workflows/checks.yml`): [actionlint](https://github.com/rhysd/actionlint) and [zizmor](https://docs.zizmor.sh/) lint the workflows on every push and pull request. Dependency review fails a pull request that adds a package with a known vulnerability (moderate or worse). Actions are pinned to commit SHAs; Dependabot keeps the pins up to date. To run the workflow lint locally: `pip install actionlint-py zizmor`, then `actionlint` and `zizmor .github/workflows` in the repo root.
- **Links** workflow (`.github/workflows/links.yml`): [lychee](https://lychee.cli.rs/) checks the links in all Markdown files weekly and whenever docs change on `main`. Broken links open (or update) the issue "Broken links in the docs". Links that can't work from CI (local URLs, placeholders, pages behind a login) are excluded in `.lycheeignore`.
- Java and the JavaScript tools are analysed by CodeQL (see below), so this repo runs no separate static analyser.
- GitHub settings (set by Remy's setup script, not stored in the repo): **secret scanning with push protection** (a push containing a known token format is rejected; findings under *Security → Secret scanning*) and **CodeQL default setup** (findings under *Security → Code scanning* and as comments on pull requests; PHP isn't covered by CodeQL, which is why the PHP plugins run PHPStan).
- `.github/workflows/support-triage.yml`: support requests from the Supertext integrations website arrive as issues labelled `support` (contact details stay in the Supertext CMS). Claude classifies each one: answers questions (marked `<!-- supertext:reply -->`, emailed to the sender by the CMS) and closes them, fixes bugs as pull requests for review (never merged automatically), labels feature requests `feature-request`. Only people with triage rights can add labels, so outsiders can't start it. Needs the organisation secret `ANTHROPIC_API_KEY`; the workflow is the same in every Supertext repo.

Before starting work in this repo, look at its open findings: code scanning alerts, secret scanning alerts, Dependabot PRs and the "Broken links in the docs" issue.

## Releasing

Releases are published by `.github/workflows/release.yml` when the version is officially bumped; nobody tags or creates releases by hand.

1. Move the *Unreleased* entries in `CHANGELOG.md` under a new `## X.Y.Z — YYYY-MM-DD` section, and keep an empty *Unreleased* above it.
2. Set the same version in:
   - `pom.xml`: `<version>`, the module version (Magnolia reads it from `META-INF/magnolia/supertext-translation.xml`, filled in at build time; the Supertext app shows the jar's `Implementation-Version`);
   - `demo/pom.xml`: `<version>` and `<supertext.version>`.
3. Push to `main`. The workflow checks that the version files match `CHANGELOG.md`, builds the module with Maven, tags `vX.Y.Z` and creates the GitHub release with the CHANGELOG section as notes (0.x versions as pre-releases) and `magnolia-supertext-translation-X.Y.Z.jar` attached. A push that adds no new version does nothing, and a version that is already released is skipped. After fixing a failed run, start it again with *Run workflow* on the *Release* workflow.

The module is not on Maven Central; the installation guide explains how to install the release jar.

## Known limitations / roadmap

- Translation runs inside the dialog's request (up to the timeout per document and language). Planned: a background job with a Pulse message for large page trees.
- The source is always the site's default language.
- Legacy Magnolia 5 (compatibility) dialogs are not read; list their fields under *Also translate*.
- An `i18n` composite field stored as nested content (one child node per language) is not translated; multi-value fields (`jcrMultiField`, `multiValueField`) neither.
- Content apps other than Pages (Stories, content types) are not supported yet.
- *Export for translation* exports one page without its subpages. The docs have no screenshots of the export and import dialogs yet (the session that added them couldn't run the demo; `tools/docs/screenshots.mjs` should add them).
- The stored API key is plain text in the config workspace (superuser-only by default); prefer `SUPERTEXT_API_KEY` in production.
- No sync of later source changes; translate again (with *Overwrite*) to refresh a language. With *Translation review*, reviewers' corrections (and a translation memory) come from the review service, but reach Magnolia only with the next translation (no push).
- Human (professional) translation orders are not supported yet.
- Tested on Magnolia 6.4.10 and 6.4.11 (6.4.11-sp1) CE with the stand-in API; not yet against the live API.
