package com.supertext.magnolia.translation.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.magnolia.cms.i18n.I18nContentSupport;
import info.magnolia.i18nsystem.SimpleTranslator;
import info.magnolia.jcr.util.NodeTypes;
import info.magnolia.module.site.SiteManager;
import info.magnolia.rendering.template.registry.TemplateDefinitionRegistry;
import info.magnolia.ui.CloseHandler;
import info.magnolia.ui.ValueContext;
import info.magnolia.ui.api.action.AbstractAction;
import info.magnolia.ui.api.action.ActionExecutionException;
import info.magnolia.ui.dialog.DialogDefinitionRegistry;
import info.magnolia.ui.editor.EditorView;
import info.magnolia.ui.observation.DatasourceObservation;

import com.supertext.magnolia.translation.SupertextSettings;
import com.supertext.magnolia.translation.api.SupertextService;
import com.supertext.magnolia.translation.api.SupertextException;
import com.supertext.magnolia.translation.content.DialogFieldResolver;
import com.supertext.magnolia.translation.content.PageTranslator;
import com.supertext.magnolia.translation.content.SiteLanguages;
import com.supertext.magnolia.translation.reporting.ReportingService;
import com.vaadin.data.BinderValidationStatus;
import com.vaadin.ui.Notification;

/**
 * Translates the selected page into the languages ticked in the dialog. Runs with the
 * editor's own JCR session, so Magnolia's permissions apply. The dialog stays open when
 * nothing could be done (no language ticked, no API key, key rejected).
 */
public class TranslateAction extends AbstractAction<TranslateActionDefinition> {

    private static final Logger log = LoggerFactory.getLogger(TranslateAction.class);

    public static final String FIELD_TARGETS = "targetLanguages";
    public static final String FIELD_OVERWRITE = "overwrite";
    public static final String FIELD_SUBPAGES = "includeSubpages";

    private final ValueContext<Node> valueContext;
    private final EditorView<Node> form;
    private final CloseHandler closeHandler;
    private final DatasourceObservation.Manual<Node> datasourceObservation;
    private final SupertextSettings settings;
    private final SupertextService supertext;
    private final SiteManager siteManager;
    private final I18nContentSupport i18nContentSupport;
    private final TemplateDefinitionRegistry templates;
    private final DialogDefinitionRegistry dialogs;
    private final SimpleTranslator i18n;
    private final SupertextMessages messages;
    private final ReportingService reporting;

    @Inject
    public TranslateAction(TranslateActionDefinition definition, ValueContext<Node> valueContext, EditorView<Node> form, CloseHandler closeHandler,
                           DatasourceObservation.Manual<Node> datasourceObservation, SupertextSettings settings, SupertextService supertext,
                           SiteManager siteManager, I18nContentSupport i18nContentSupport, TemplateDefinitionRegistry templates,
                           DialogDefinitionRegistry dialogs, SimpleTranslator i18n, ReportingService reporting) {
        super(definition);
        this.valueContext = valueContext;
        this.form = form;
        this.closeHandler = closeHandler;
        this.datasourceObservation = datasourceObservation;
        this.settings = settings;
        this.supertext = supertext;
        this.siteManager = siteManager;
        this.i18nContentSupport = i18nContentSupport;
        this.templates = templates;
        this.dialogs = dialogs;
        this.i18n = i18n;
        this.messages = new SupertextMessages(i18n::translate);
        this.reporting = reporting;
    }

    @Override
    public void execute() throws ActionExecutionException {
        boolean invalid = form.validate().stream().map(BinderValidationStatus::getFieldValidationErrors).anyMatch(errors -> !errors.isEmpty());
        if (invalid) {
            return;
        }
        Node page = valueContext.getSingle().orElse(null);
        if (page == null) {
            return;
        }
        // Commando Center: re-checks a changed key, reports an upgrade (in the background, never blocks).
        reporting.onRequest();
        SiteLanguages languages = SiteLanguagesLookup.of(page, siteManager, i18nContentSupport);
        if (!languages.isEnabled()) {
            error(t("supertext-translation.translate.noLanguages"), null);
            return;
        }
        List<Locale> targets = new ArrayList<>();
        for (String id : selectedIds()) {
            languages.byId(id).ifPresent(targets::add);
        }
        if (targets.isEmpty()) {
            warning(t("supertext-translation.translate.noTargets"));
            return;
        }
        if (!settings.hasApiKey()) {
            error(t("supertext-translation.translate.noApiKey"), messages.hint());
            return;
        }
        boolean overwrite = this.<Boolean>value(FIELD_OVERWRITE).orElse(false);
        boolean subpages = this.<Boolean>value(FIELD_SUBPAGES).orElse(false);

        PageTranslator translator = new PageTranslator(
                languages,
                new DialogFieldResolver(templates, dialogs, settings.additionalProperties()),
                // Languages with Translation review on go through Supertext's review service.
                (texts, target, source) -> supertext.translateTexts(settings.connection(target), texts,
                        settings.targetLanguageCode(target), settings.sourceLanguageCode(source), settings.politeness()),
                NodeTypes.LastModified::update,
                settings.excludedProperties());
        PageTranslator.Report report;
        try {
            report = translator.translate(new PageTranslator.Request(page, targets, overwrite, subpages));
        } catch (SupertextException e) {
            // Missing or rejected key: nothing was saved, keep the dialog open.
            log.warn("Supertext: {}", e.getMessage());
            if (e.isKeyRejected()) {
                reporting.authenticationFailed(e.httpStatus());
            }
            error(messages.of(e), e.isAuthenticationProblem() ? messages.hint() : null);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActionExecutionException(e);
        } catch (RepositoryException e) {
            throw new ActionExecutionException(t("supertext-translation.translate.saveFailed"), e);
        }

        closeHandler.close();
        datasourceObservation.trigger(page);
        report(page, report);
    }

    private void report(Node page, PageTranslator.Report report) {
        String title = pageTitle(page);
        List<String> ok = report.languages().stream().filter(r -> !r.failed() && r.translated() > 0).map(r -> SiteLanguagesLookup.displayName(r.locale())).toList();
        int kept = report.languages().stream().mapToInt(PageTranslator.LanguageResult::kept).sum();

        List<String> lines = new ArrayList<>();
        if (!ok.isEmpty()) {
            lines.add(t("supertext-translation.translate.done", report.translated(), String.join(", ", ok)));
        }
        if (report.pages() > 1) {
            lines.add(t("supertext-translation.translate.pages", report.pages()));
        }
        if (kept > 0) {
            lines.add(t("supertext-translation.translate.kept", kept));
        }
        if (!report.anyFailed() && report.translated() == 0 && kept == 0) {
            lines.add(t("supertext-translation.translate.nothing"));
        }
        report.languages().stream().filter(PageTranslator.LanguageResult::failed)
                .forEach(r -> lines.add(t("supertext-translation.translate.languageFailed", SiteLanguagesLookup.displayName(r.locale()), messages.of(r.problem()))));
        log.info("Supertext: translated '{}' ({} pages): {}", page, report.pages(), report.languages());

        String caption = t(report.anyFailed() ? "supertext-translation.translate.partly"
                : report.translated() == 0 && kept > 0 ? "supertext-translation.translate.upToDate"
                : "supertext-translation.translate.success", title);
        Notification.show(caption, String.join("\n", lines), report.anyFailed() ? Notification.Type.WARNING_MESSAGE : Notification.Type.HUMANIZED_MESSAGE);
    }

    private Set<String> selectedIds() {
        Optional<Object> value = form.getPropertyValue(FIELD_TARGETS);
        Set<String> ids = new LinkedHashSet<>();
        value.ifPresent(v -> {
            if (v instanceof Collection<?> collection) {
                collection.forEach(item -> ids.add(String.valueOf(item)));
            } else if (v instanceof String s && !s.isBlank()) {
                ids.addAll(List.of(s.split(",")).stream().map(String::trim).collect(Collectors.toList()));
            }
        });
        return ids;
    }

    @SuppressWarnings("unchecked")
    private <V> Optional<V> value(String name) {
        try {
            return (Optional<V>) form.getPropertyValue(name);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String pageTitle(Node page) {
        try {
            if (page.hasProperty("title") && !page.getProperty("title").getString().isBlank()) {
                return page.getProperty("title").getString();
            }
            return page.getName();
        } catch (RepositoryException e) {
            return "";
        }
    }

    private String t(String key, Object... args) {
        return i18n.translate(key, args);
    }

    private void warning(String message) {
        Notification.show(message, Notification.Type.WARNING_MESSAGE);
    }

    private void error(String message, String hint) {
        Notification.show(t("supertext-translation.translate.failed"), hint == null ? message : message + "\n" + hint, Notification.Type.ERROR_MESSAGE);
    }
}
