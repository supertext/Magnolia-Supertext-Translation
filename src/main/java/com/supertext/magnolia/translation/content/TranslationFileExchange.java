package com.supertext.magnolia.translation.content;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.supertext.magnolia.translation.api.SupertextException;
import com.supertext.magnolia.translation.internal.sdk.files.FileJob;
import com.supertext.magnolia.translation.internal.sdk.files.FileSegment;
import com.supertext.magnolia.translation.internal.sdk.files.TranslationFile;
import com.supertext.magnolia.translation.internal.sdk.files.TranslationFileException;
import com.supertext.magnolia.translation.internal.sdk.files.TranslationFiles;

/**
 * "Export for translation" / "Import translation" without Magnolia classes (unit-tested): the job reference,
 * the SDK's {@link FileJob}s from the texts {@link PageTranslator#fileSegments} collects (the same keys as the API
 * translation), file names, the upload size check and the check that a job read from a file is this module's.
 * {@link PageTranslator#importFile} writes back; the UI actions check permissions and show the summary.
 */
public final class TranslationFileExchange {

    /** The {@code plugin} of every job: the repository name. */
    public static final String PLUGIN = "Magnolia-Supertext-Translation";

    /** The first part of the download's file name. */
    public static final String SHORT_NAME = "magnolia";

    /** Uploads larger than this are refused before they are read (the dialog's upload field has the same limit). */
    public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    public static final String FORMAT_XLIFF = "xliff";
    public static final String FORMAT_HTML = "html";

    /** Message keys ({@code supertext-translation.files.error.*}). */
    public static final String ERROR_PREFIX = "supertext-translation.files.error.";

    private static final String REFERENCE_PREFIX = "page:";

    private TranslationFileExchange() {
    }

    /** A file ready for download. */
    public record ExportedFile(String fileName, String contentType, byte[] content) {
    }

    /** The parts of a reference: the page's JCR identifier and the Magnolia language id ({@code de_CH}). */
    public record Reference(String pageId, String language) {
        @Override
        public String toString() {
            return REFERENCE_PREFIX + pageId + ":" + language;
        }
    }

    /** {@code page:<page identifier>:<language>}, e.g. {@code page:7a1e…:de_CH} (the target language). */
    public static String reference(String pageId, String language) {
        return new Reference(pageId, language).toString();
    }

    /** Parses {@link #reference}; null for anything else. */
    public static Reference parseReference(String reference) {
        if (reference == null || !reference.startsWith(REFERENCE_PREFIX)) {
            return null;
        }
        String[] parts = reference.substring(REFERENCE_PREFIX.length()).split(":", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return new Reference(parts[0], parts[1]);
    }

    /** Resolves a Supertext code; may throw {@link SupertextException} for an unmapped language. */
    @FunctionalInterface
    public interface CodeResolver {
        String resolve() throws SupertextException;
    }

    /**
     * The Supertext code the language mapping resolves, else the Magnolia language with {@code _} replaced by
     * {@code -}: a file may go to any translator, so an unmapped language doesn't block the export.
     */
    public static String languageCode(CodeResolver resolver, String language) {
        try {
            return resolver.resolve();
        } catch (SupertextException e) {
            return language.replace('_', '-');
        }
    }

    /** One job: the page's texts for one target language. */
    public static FileJob job(String pageId, String language, String sourceCode, String targetCode, List<FileSegment> segments) {
        return new FileJob(PLUGIN, reference(pageId, language), sourceCode, targetCode, segments);
    }

    /**
     * The file: XLIFF with one {@code <file>} per language, or HTML for exactly one language. Named
     * {@code magnolia-<page id>-<language>.xlf} ({@code .html}), or {@code magnolia-<page id>.xlf} for several.
     */
    public static ExportedFile export(String format, String pageId, List<String> languages, List<FileJob> jobs) throws SupertextException {
        if (jobs.isEmpty() || jobs.stream().allMatch(j -> j.getSegments().stream().allMatch(s -> s.getText().isBlank()))) {
            throw SupertextException.withKey("supertext-translation.files.error.nothingToTranslate", "Nothing to translate.");
        }
        String base = SHORT_NAME + "-" + safe(pageId) + (languages.size() == 1 ? "-" + safe(languages.get(0)) : "");
        if (FORMAT_HTML.equals(format)) {
            if (jobs.size() != 1) {
                throw SupertextException.withKey("supertext-translation.files.error.htmlOneLanguage", "An HTML file holds one language: choose exactly one.");
            }
            return new ExportedFile(base + ".html", "text/html; charset=utf-8", TranslationFiles.exportHtml(jobs.get(0)).getBytes(StandardCharsets.UTF_8));
        }
        return new ExportedFile(base + ".xlf", "application/x-xliff+xml", TranslationFiles.exportXliff(jobs).getBytes(StandardCharsets.UTF_8));
    }

    /** Checks the upload's size, then reads its jobs. */
    public static List<TranslationFile> read(byte[] upload) throws SupertextException {
        if (upload == null || upload.length == 0) {
            throw SupertextException.withKey("supertext-translation.files.error.chooseFile", "Choose a file to import.");
        }
        if (upload.length > MAX_UPLOAD_BYTES) {
            throw SupertextException.withKey("supertext-translation.files.error.tooLarge", "The file is too large (at most 10 MB).");
        }
        try {
            return TranslationFiles.read(new String(upload, StandardCharsets.UTF_8));
        } catch (TranslationFileException e) {
            throw fileError(e);
        }
    }

    /** Checks that a job read from a file is this module's and returns its reference. */
    public static Reference locate(TranslationFile file) throws SupertextException {
        if (!PLUGIN.equals(file.getPlugin())) {
            throw SupertextException.withKey("supertext-translation.files.error.otherSystem", "This file belongs to another system (" + file.getPlugin() + ").", file.getPlugin());
        }
        Reference reference = parseReference(file.getReference());
        if (reference == null) {
            throw notFound();
        }
        return reference;
    }

    /** The page the file was exported from no longer exists (or the reference is unknown). */
    public static SupertextException notFound() {
        return SupertextException.withKey("supertext-translation.files.error.notFound", "The page this file was exported from no longer exists.");
    }

    /** The site has no such language. */
    public static SupertextException noLanguage(String language) {
        return SupertextException.withKey("supertext-translation.files.error.noLanguage", "The site has no language " + language + ".", language);
    }

    /** An SDK translation file error as a keyed error. */
    public static SupertextException fileError(TranslationFileException e) {
        return switch (e.getCode()) {
            case MISSING_METADATA -> SupertextException.withKey("supertext-translation.files.error.missingMetadata",
                    "This file wasn't exported by the Supertext module, or its header was removed.");
            case TOO_LARGE -> SupertextException.withKey("supertext-translation.files.error.tooLarge", "The file is too large (at most 10 MB).");
            case INVALID_JOB -> SupertextException.withKey("supertext-translation.files.error.nothingToTranslate", "Nothing to translate.");
            default -> SupertextException.withKey("supertext-translation.files.error.invalidFile", "This file isn't a valid XLIFF or HTML translation file.");
        };
    }

    private static String safe(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            out.append(c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_') ? c : '-');
        }
        return out.toString();
    }
}
