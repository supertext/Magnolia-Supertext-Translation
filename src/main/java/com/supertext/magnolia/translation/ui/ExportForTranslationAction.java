package com.supertext.magnolia.translation.ui;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.magnolia.cms.i18n.I18nContentSupport;
import info.magnolia.i18nsystem.SimpleTranslator;
import info.magnolia.module.site.SiteManager;
import info.magnolia.rendering.template.registry.TemplateDefinitionRegistry;
import info.magnolia.ui.CloseHandler;
import info.magnolia.ui.ValueContext;
import info.magnolia.ui.api.action.AbstractAction;
import info.magnolia.ui.api.action.ActionExecutionException;
import info.magnolia.ui.dialog.DialogDefinitionRegistry;
import info.magnolia.ui.editor.EditorView;

import com.supertext.magnolia.translation.SupertextSettings;
import com.supertext.magnolia.translation.api.SupertextException;
import com.supertext.magnolia.translation.content.DialogFieldResolver;
import com.supertext.magnolia.translation.content.PageTranslator;
import com.supertext.magnolia.translation.content.SiteLanguages;
import com.supertext.magnolia.translation.content.TranslationFileExchange;
import com.supertext.magnolia.translation.internal.sdk.files.FileJob;
import com.supertext.magnolia.translation.internal.sdk.files.FileSegment;
import com.vaadin.server.DownloadStream;
import com.vaadin.server.StreamResource;
import com.vaadin.ui.Notification;
import com.vaadin.ui.UI;

/**
 * "Export for translation": downloads the selected page's texts (its properties, areas and components, in the
 * site's main language) as an XLIFF 1.2 file for the ticked languages, or an HTML file for one language. The texts
 * and keys are those of "Translate with Supertext" ({@link PageTranslator#fileSegments}). Reads with the editor's
 * own JCR session; no Supertext call, so no API key is needed.
 */
public class ExportForTranslationAction extends AbstractAction<ExportForTranslationActionDefinition> {

    private static final Logger log = LoggerFactory.getLogger(ExportForTranslationAction.class);

    public static final String FIELD_FORMAT = "format";

    private final ValueContext<Node> valueContext;
    private final EditorView<Node> form;
    private final CloseHandler closeHandler;
    private final SupertextSettings settings;
    private final SiteManager siteManager;
    private final I18nContentSupport i18nContentSupport;
    private final TemplateDefinitionRegistry templates;
    private final DialogDefinitionRegistry dialogs;
    private final SimpleTranslator i18n;
    private final SupertextMessages messages;

    @Inject
    public ExportForTranslationAction(ExportForTranslationActionDefinition definition, ValueContext<Node> valueContext, EditorView<Node> form,
                                      CloseHandler closeHandler, SupertextSettings settings, SiteManager siteManager,
                                      I18nContentSupport i18nContentSupport, TemplateDefinitionRegistry templates, DialogDefinitionRegistry dialogs,
                                      SimpleTranslator i18n) {
        super(definition);
        this.valueContext = valueContext;
        this.form = form;
        this.closeHandler = closeHandler;
        this.settings = settings;
        this.siteManager = siteManager;
        this.i18nContentSupport = i18nContentSupport;
        this.templates = templates;
        this.dialogs = dialogs;
        this.i18n = i18n;
        this.messages = new SupertextMessages(i18n::translate);
    }

    @Override
    public void execute() throws ActionExecutionException {
        Node page = valueContext.getSingle().orElse(null);
        if (page == null) {
            return;
        }
        SiteLanguages languages = SiteLanguagesLookup.of(page, siteManager, i18nContentSupport);
        if (!languages.isEnabled()) {
            warning(t("supertext-translation.translate.noLanguages"));
            return;
        }
        List<Locale> targets = new ArrayList<>();
        for (String id : selectedIds()) {
            languages.byId(id).filter(l -> !l.equals(languages.defaultLocale())).ifPresent(targets::add);
        }
        if (targets.isEmpty()) {
            warning(t("supertext-translation.translate.noTargets"));
            return;
        }
        String format = String.valueOf(form.getPropertyValue(FIELD_FORMAT).orElse(TranslationFileExchange.FORMAT_XLIFF));
        format = TranslationFileExchange.FORMAT_HTML.equals(format) ? TranslationFileExchange.FORMAT_HTML : TranslationFileExchange.FORMAT_XLIFF;

        TranslationFileExchange.ExportedFile file;
        try {
            PageTranslator translator = new PageTranslator(languages, new DialogFieldResolver(templates, dialogs, settings.additionalProperties()),
                    (texts, target, source) -> {
                        throw new IllegalStateException("not used for exports");
                    }, node -> { }, settings.excludedProperties());
            List<FileSegment> segments = translator.fileSegments(page);
            Locale source = languages.defaultLocale();
            String sourceId = languages.id(source);
            String sourceCode = TranslationFileExchange.languageCode(() -> settings.sourceLanguageCode(source), sourceId);
            List<String> ids = new ArrayList<>();
            List<FileJob> jobs = new ArrayList<>();
            for (Locale target : targets) {
                String id = languages.id(target);
                ids.add(id);
                jobs.add(TranslationFileExchange.job(page.getIdentifier(), id, sourceCode,
                        TranslationFileExchange.languageCode(() -> settings.targetLanguageCode(target), id), segments));
            }
            file = TranslationFileExchange.export(format, page.getIdentifier(), ids, jobs);
        } catch (SupertextException e) {
            warning(messages.of(e));
            return;
        } catch (RepositoryException e) {
            throw new ActionExecutionException(e);
        }
        log.info("Supertext: exported '{}' for translation as {}", page, file.fileName());
        closeHandler.close();
        UI.getCurrent().getPage().open(new AttachmentResource(file), "_blank", true);
    }

    /** The file as an attachment (Vaadin's StreamResource sends it inline by default). */
    static final class AttachmentResource extends StreamResource {

        private static final long serialVersionUID = 1L;

        private final String fileName;

        AttachmentResource(TranslationFileExchange.ExportedFile file) {
            super(() -> new ByteArrayInputStream(file.content()), file.fileName());
            this.fileName = file.fileName();
            setMIMEType(file.contentType());
            setCacheTime(0);
        }

        @Override
        public DownloadStream getStream() {
            DownloadStream stream = super.getStream();
            stream.setParameter("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            return stream;
        }
    }

    private Set<String> selectedIds() {
        Optional<Object> value = form.getPropertyValue(TranslateAction.FIELD_TARGETS);
        Set<String> ids = new LinkedHashSet<>();
        value.ifPresent(v -> {
            if (v instanceof Collection<?> collection) {
                collection.forEach(item -> ids.add(String.valueOf(item)));
            } else if (v instanceof String s && !s.isBlank()) {
                for (String part : s.split(",")) {
                    ids.add(part.trim());
                }
            }
        });
        return ids;
    }

    private String t(String key, Object... args) {
        return i18n.translate(key, args);
    }

    private void warning(String message) {
        Notification.show(message, Notification.Type.WARNING_MESSAGE);
    }
}
