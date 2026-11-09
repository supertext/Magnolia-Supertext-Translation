package com.supertext.magnolia.translation.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.supertext.magnolia.translation.api.LanguageMapping;
import com.supertext.magnolia.translation.api.SupertextException;
import com.supertext.magnolia.translation.internal.sdk.files.FileJob;
import com.supertext.magnolia.translation.internal.sdk.files.FileSegment;
import com.supertext.magnolia.translation.internal.sdk.files.TranslationFile;
import com.supertext.magnolia.translation.internal.sdk.files.TranslationFiles;

/** Export for translation / Import translation: the glue around the SDK's TranslationFiles. */
class TranslationFileExchangeTest {

    private static final String PAGE = "7a1e2b3c-4d5e-6f70-8192-a3b4c5d6e7f8";
    private static final List<FileSegment> SEGMENTS = List.of(new FileSegment("@title", "Welcome"),
            new FileSegment("main/0@text", "<p>Hello <b>world</b></p>", true));

    @Test
    void referenceRoundTrip() {
        String reference = TranslationFileExchange.reference(PAGE, "de_CH");
        assertEquals("page:" + PAGE + ":de_CH", reference);
        assertEquals(new TranslationFileExchange.Reference(PAGE, "de_CH"), TranslationFileExchange.parseReference(reference));
        assertNull(TranslationFileExchange.parseReference("item:12:de"));
        assertNull(TranslationFileExchange.parseReference("page::de"));
        assertNull(TranslationFileExchange.parseReference("page:a:b:c"));
        assertNull(TranslationFileExchange.parseReference(null));
    }

    @Test
    void exportUsesTheCollectorKeysAndTheMapping() throws Exception {
        LanguageMapping mapping = LanguageMapping.of(Map.of("de", "de-CH"));
        String target = TranslationFileExchange.languageCode(() -> mapping.target("de"), "de");
        String source = TranslationFileExchange.languageCode(() -> mapping.source("en_US"), "en_US");
        FileJob job = TranslationFileExchange.job(PAGE, "de", source, target, SEGMENTS);
        assertEquals(TranslationFileExchange.PLUGIN, job.getPlugin());
        assertEquals("page:" + PAGE + ":de", job.getReference());
        assertEquals("en", job.getSourceLanguage());
        assertEquals("de-CH", job.getTargetLanguage());

        TranslationFileExchange.ExportedFile xliff = TranslationFileExchange.export("xliff", PAGE, List.of("de"), List.of(job));
        assertEquals("magnolia-" + PAGE + "-de.xlf", xliff.fileName());
        assertEquals("application/x-xliff+xml", xliff.contentType());
        TranslationFile read = TranslationFiles.read(new String(xliff.content(), StandardCharsets.UTF_8)).get(0);
        assertEquals(List.of("@title", "main/0@text"), read.getUnits().stream().map(u -> u.getKey()).toList());

        TranslationFileExchange.ExportedFile html = TranslationFileExchange.export("html", PAGE, List.of("de"), List.of(job));
        assertEquals("magnolia-" + PAGE + "-de.html", html.fileName());
        assertEquals("text/html; charset=utf-8", html.contentType());
        assertEquals("magnolia-" + PAGE + ".xlf", TranslationFileExchange.export("xliff", PAGE, List.of("de", "fr"), List.of(job, job)).fileName());
        assertEquals("supertext-translation.files.error.htmlOneLanguage",
                assertThrows(SupertextException.class, () -> TranslationFileExchange.export("html", PAGE, List.of("de", "fr"), List.of(job, job))).key());
        FileJob empty = TranslationFileExchange.job(PAGE, "de", "en", "de-CH", List.of());
        assertEquals("supertext-translation.files.error.nothingToTranslate",
                assertThrows(SupertextException.class, () -> TranslationFileExchange.export("xliff", PAGE, List.of("de"), List.of(empty))).key());
    }

    @Test
    void unmappedLanguagesDoNotBlockTheExport() {
        LanguageMapping none = LanguageMapping.of(Map.of());
        assertEquals("en-IE", TranslationFileExchange.languageCode(() -> none.target("en_IE"), "en_IE"));
        assertEquals("de-DE", TranslationFileExchange.languageCode(() -> none.target("de"), "de"));
    }

    @Test
    void otherSystemsAndUnknownReferencesAreRefused() throws Exception {
        TranslationFile other = TranslationFiles.read(TranslationFiles.exportXliff(List.of(
                new FileJob("Contao-Supertext-Translation", "page:12", "en", "de-CH", SEGMENTS)))).get(0);
        SupertextException e = assertThrows(SupertextException.class, () -> TranslationFileExchange.locate(other));
        assertEquals("supertext-translation.files.error.otherSystem", e.key());
        assertEquals("Contao-Supertext-Translation", e.args()[0]);

        TranslationFile unknown = TranslationFiles.read(TranslationFiles.exportXliff(List.of(
                new FileJob(TranslationFileExchange.PLUGIN, "page:12", "en", "de-CH", SEGMENTS)))).get(0);
        assertEquals("supertext-translation.files.error.notFound", assertThrows(SupertextException.class, () -> TranslationFileExchange.locate(unknown)).key());

        TranslationFile ours = TranslationFiles.read(TranslationFiles.exportXliff(List.of(
                TranslationFileExchange.job(PAGE, "de_CH", "en", "de-CH", SEGMENTS)))).get(0);
        assertEquals(new TranslationFileExchange.Reference(PAGE, "de_CH"), TranslationFileExchange.locate(ours));
    }

    @Test
    void badAndTooLargeFilesAreRefused() {
        byte[] big = new byte[(int) TranslationFileExchange.MAX_UPLOAD_BYTES + 1];
        assertEquals("supertext-translation.files.error.tooLarge", assertThrows(SupertextException.class, () -> TranslationFileExchange.read(big)).key());
        assertEquals("supertext-translation.files.error.chooseFile", assertThrows(SupertextException.class, () -> TranslationFileExchange.read(new byte[0])).key());
        for (Map.Entry<String, String> bad : Map.of(
                "<!DOCTYPE x [<!ENTITY a 'b'>]><xliff/>", "invalidFile",
                "<html><head></head><body><div data-st-id=\"0\">x</div></body></html>", "missingMetadata").entrySet()) {
            assertEquals("supertext-translation.files.error." + bad.getValue(),
                    assertThrows(SupertextException.class, () -> TranslationFileExchange.read(bad.getKey().getBytes(StandardCharsets.UTF_8))).key());
        }
    }
}
