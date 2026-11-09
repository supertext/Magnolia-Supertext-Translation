package com.supertext.magnolia.translation.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

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
import com.supertext.magnolia.translation.api.SupertextException;
import com.supertext.magnolia.translation.content.DialogFieldResolver;
import com.supertext.magnolia.translation.content.PageTranslator;
import com.supertext.magnolia.translation.content.SiteLanguages;
import com.supertext.magnolia.translation.content.TranslationFileExchange;
import com.supertext.magnolia.translation.internal.sdk.files.TranslationFile;
import com.vaadin.ui.Notification;

/**
 * "Import translation": reads an uploaded XLIFF or HTML translation file (the upload field refuses files over
 * 10 MB; the file is read once and its temporary copy deleted) and writes each job's translations into its page and
 * language, with the editor's own JCR session and the rules of "Translate with Supertext" ({@link PageTranslator#importFile}).
 * Shows a summary per page and language.
 */
public class ImportTranslationAction extends AbstractAction<ImportTranslationActionDefinition> {

    private static final Logger log = LoggerFactory.getLogger(ImportTranslationAction.class);

    public static final String FIELD_FILE = "file";
    public static final String FIELD_INCLUDE_CHANGED = "includeChanged";

    private final ValueContext<Node> valueContext;
    private final EditorView<Node> form;
    private final CloseHandler closeHandler;
    private final DatasourceObservation.Manual<Node> datasourceObservation;
    private final SupertextSettings settings;
    private final SiteManager siteManager;
    private final I18nContentSupport i18nContentSupport;
    private final TemplateDefinitionRegistry templates;
    private final DialogDefinitionRegistry dialogs;
    private final SimpleTranslator i18n;
    private final SupertextMessages messages;

    @Inject
    public ImportTranslationAction(ImportTranslationActionDefinition definition, ValueContext<Node> valueContext, EditorView<Node> form,
                                   CloseHandler closeHandler, DatasourceObservation.Manual<Node> datasourceObservation, SupertextSettings settings,
                                   SiteManager siteManager, I18nContentSupport i18nContentSupport, TemplateDefinitionRegistry templates,
                                   DialogDefinitionRegistry dialogs, SimpleTranslator i18n) {
        super(definition);
        this.valueContext = valueContext;
        this.form = form;
        this.closeHandler = closeHandler;
        this.datasourceObservation = datasourceObservation;
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
        Node selected = valueContext.getSingle().orElse(null);
        if (selected == null) {
            return;
        }
        boolean includeChanged = this.<Boolean>value(FIELD_INCLUDE_CHANGED).orElse(false);
        boolean overwrite = this.<Boolean>value(TranslateAction.FIELD_OVERWRITE).orElse(false);

        List<TranslationFile> jobs;
        try {
            jobs = TranslationFileExchange.read(upload());
        } catch (SupertextException e) {
            Notification.show(t("supertext-translation.files.import.failed"), messages.of(e), Notification.Type.WARNING_MESSAGE);
            return;
        }

        List<String> lines = new ArrayList<>();
        boolean anyFailed = false;
        Node lastChanged = null;
        for (TranslationFile job : jobs) {
            String what = job.getReference();
            try {
                TranslationFileExchange.Reference reference = TranslationFileExchange.locate(job);
                Node page = find(selected.getSession(), reference.pageId());
                what = title(page);
                SiteLanguages languages = SiteLanguagesLookup.of(page, siteManager, i18nContentSupport);
                Locale target = languages.byId(reference.language()).filter(l -> !l.equals(languages.defaultLocale()))
                        .orElseThrow(() -> TranslationFileExchange.noLanguage(reference.language()));
                what = t("supertext-translation.files.import.job", title(page), SiteLanguagesLookup.displayName(target));
                if (!page.getSession().hasPermission(page.getPath(), Session.ACTION_SET_PROPERTY)) {
                    throw SupertextException.withKey("supertext-translation.files.error.notAllowed", "You may not edit this page.");
                }
                PageTranslator translator = new PageTranslator(languages, new DialogFieldResolver(templates, dialogs, settings.additionalProperties()),
                        (texts, t, s) -> {
                            throw new IllegalStateException("not used for imports");
                        }, NodeTypes.LastModified::update, settings.excludedProperties());
                PageTranslator.FileImportResult result = translator.importFile(job, page, target, includeChanged, overwrite);
                lines.add(what + ": " + summary(result));
                if (result.imported() > 0) {
                    lastChanged = page;
                }
            } catch (SupertextException e) {
                anyFailed = true;
                lines.add(what + ": " + messages.of(e));
            } catch (RepositoryException e) {
                anyFailed = true;
                log.warn("Supertext: importing {} failed", job.getReference(), e);
                lines.add(what + ": " + t("supertext-translation.translate.saveFailed"));
            }
        }
        log.info("Supertext: imported a translation file: {}", lines);
        closeHandler.close();
        if (lastChanged != null) {
            datasourceObservation.trigger(lastChanged);
        }
        Notification.show(t(anyFailed ? "supertext-translation.files.import.partly" : "supertext-translation.files.import.done"),
                String.join("\n", lines), anyFailed ? Notification.Type.WARNING_MESSAGE : Notification.Type.HUMANIZED_MESSAGE);
    }

    private String summary(PageTranslator.FileImportResult r) {
        List<String> parts = new ArrayList<>();
        parts.add(t("supertext-translation.files.summary.imported", r.imported()));
        if (r.sourceChanged() > 0) {
            parts.add(t("supertext-translation.files.summary.changed", r.sourceChanged()));
        }
        if (r.untranslated() > 0) {
            parts.add(t("supertext-translation.files.summary.untranslated", r.untranslated()));
        }
        if (r.invalidMarkup() > 0) {
            parts.add(t("supertext-translation.files.summary.invalid", r.invalidMarkup()));
        }
        if (r.missing() > 0) {
            parts.add(t("supertext-translation.files.summary.missing", r.missing()));
        }
        if (r.kept() > 0) {
            parts.add(t("supertext-translation.files.summary.kept", r.kept()));
        }
        return String.join("; ", parts);
    }

    /** The uploaded file's bytes (size checked first); its temporary copy is deleted. */
    private byte[] upload() throws SupertextException {
        Optional<Object> value = form.getPropertyValue(FIELD_FILE);
        if (value.isEmpty() || !(value.get() instanceof File file) || !file.isFile()) {
            throw SupertextException.withKey("supertext-translation.files.error.chooseFile", "Choose a file to import.");
        }
        try {
            if (file.length() > TranslationFileExchange.MAX_UPLOAD_BYTES) {
                throw SupertextException.withKey("supertext-translation.files.error.tooLarge", "The file is too large (at most 10 MB).");
            }
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw SupertextException.withKey("supertext-translation.files.error.invalidFile", "This file isn't a valid XLIFF or HTML translation file.");
        } finally {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                log.debug("Supertext: could not delete the uploaded file {}", file, e);
            }
        }
    }

    private static Node find(Session session, String id) throws SupertextException, RepositoryException {
        try {
            Node node = session.getNodeByIdentifier(id);
            if (!node.isNodeType(PageTranslator.PAGE_TYPE)) {
                throw TranslationFileExchange.notFound();
            }
            return node;
        } catch (ItemNotFoundException e) {
            throw TranslationFileExchange.notFound();
        }
    }

    private static String title(Node page) {
        try {
            if (page.hasProperty("title") && !page.getProperty("title").getString().isBlank()) {
                return page.getProperty("title").getString();
            }
            return page.getName();
        } catch (RepositoryException e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private <V> Optional<V> value(String name) {
        try {
            return (Optional<V>) form.getPropertyValue(name);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String t(String key, Object... args) {
        return i18n.translate(key, args);
    }
}
