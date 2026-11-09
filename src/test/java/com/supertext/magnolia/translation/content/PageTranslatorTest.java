package com.supertext.magnolia.translation.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import info.magnolia.cms.i18n.DefaultI18nContentSupport;
import info.magnolia.cms.i18n.LocaleDefinition;
import info.magnolia.test.mock.jcr.MockSession;

import com.supertext.magnolia.translation.api.SupertextException;
import com.supertext.magnolia.translation.internal.sdk.v1.Segment;

class PageTranslatorTest {

    private static final Locale DE = new Locale("de", "CH");
    private static final Locale FR = new Locale("fr", "CH");

    private SiteLanguages languages;
    private Node page;
    private Node text;
    private final List<Map<String, Segment>> sent = new ArrayList<>();
    private final List<Node> touched = new ArrayList<>();

    /** page: title, text component with rich text, a subpage. */
    @BeforeEach
    void setUp() throws Exception {
        DefaultI18nContentSupport i18n = new DefaultI18nContentSupport();
        i18n.setEnabled(true);
        i18n.setFallbackLocale(Locale.ENGLISH);
        i18n.addLocale(LocaleDefinition.make("en", null, true));
        i18n.addLocale(LocaleDefinition.make("de", "CH", true));
        i18n.addLocale(LocaleDefinition.make("fr", "CH", true));
        languages = new SiteLanguages(i18n);

        MockSession session = new MockSession("website");
        page = session.getRootNode().addNode("home", "mgnl:page");
        page.setProperty("mgnl:template", "demo:pages/page");
        page.setProperty("title", "Welcome");
        page.setProperty("hideInNav", "true");
        Node main = page.addNode("main", "mgnl:area");
        text = main.addNode("0", "mgnl:component");
        text.setProperty("mgnl:template", "demo:components/text");
        text.setProperty("text", "<p>Hello <b>world</b></p>");
        text.setProperty("headline", "  ");
        text.addNode("teaser", "mgnl:contentNode").setProperty("teaserTitle", "Read more");
        Node sub = page.addNode("about", "mgnl:page");
        sub.setProperty("mgnl:template", "demo:pages/page");
        sub.setProperty("title", "About us");
    }

    private static final FieldResolver FIELDS = node -> {
        String template = node.hasProperty("mgnl:template") ? node.getProperty("mgnl:template").getString() : "";
        return switch (template) {
            case "demo:pages/page" -> List.of(new TranslatableField("title", false));
            case "demo:components/text" -> List.of(new TranslatableField("headline", false), new TranslatableField("text", true),
                    new TranslatableField("teaserTitle", false, "teaser"), new TranslatableField("missing", false, "nowhere"));
            default -> List.of();
        };
    };

    /** Stand-in for Supertext: prefixes every text with the language. */
    private PageTranslator translator(Set<String> excluded) {
        return new PageTranslator(languages, FIELDS, (texts, target, source) -> {
            sent.add(texts);
            assertEquals(Locale.ENGLISH, source);
            return prefixed(texts, "[" + target.getLanguage() + "] ");
        }, touched::add, excluded);
    }

    private static Map<String, String> prefixed(Map<String, Segment> texts, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        texts.forEach((key, segment) -> result.put(key, prefix + segment.getText()));
        return result;
    }

    @Test
    void translatesPageAndComponentsIntoLocalizedProperties() throws Exception {
        PageTranslator.Report report = translator(Set.of()).translate(new PageTranslator.Request(page, List.of(DE, FR), false, false));

        assertEquals("[de] Welcome", page.getProperty("title_de_CH").getString());
        assertEquals("[fr] Welcome", page.getProperty("title_fr_CH").getString());
        assertEquals("[de] <p>Hello <b>world</b></p>", text.getProperty("text_de_CH").getString());
        assertEquals("Welcome", page.getProperty("title").getString(), "source untouched");
        assertFalse(text.hasProperty("headline_de_CH"), "blank texts are skipped");
        assertFalse(page.getNode("about").hasProperty("title_de_CH"), "subpages only when asked");
        assertEquals(1, report.pages());
        assertEquals("[de] Read more", text.getNode("teaser").getProperty("teaserTitle_de_CH").getString(), "nested composite");
        assertEquals(6, report.translated());
        assertEquals(2, sent.size(), "one call per language");
        assertEquals(List.of(Segment.text("Welcome"), Segment.html("<p>Hello <b>world</b></p>"), Segment.text("Read more")),
                List.copyOf(sent.get(0).values()), "rich text is sent as HTML, one segment per field");
        assertTrue(touched.contains(page) && touched.contains(text), "changed nodes are marked modified");
    }

    @Test
    void keepsExistingTranslationsUnlessOverwrite() throws Exception {
        page.setProperty("title_de_CH", "Willkommen (edited)");

        PageTranslator.Report report = translator(Set.of()).translate(new PageTranslator.Request(page, List.of(DE), false, false));
        assertEquals("Willkommen (edited)", page.getProperty("title_de_CH").getString());
        assertEquals(1, report.languages().get(0).kept());
        assertEquals(2, report.languages().get(0).translated(), "rich text and teaser");

        translator(Set.of()).translate(new PageTranslator.Request(page, List.of(DE), true, false));
        assertEquals("[de] Welcome", page.getProperty("title_de_CH").getString());
    }

    @Test
    void includesSubpagesWhenAsked() throws Exception {
        PageTranslator.Report report = translator(Set.of()).translate(new PageTranslator.Request(page, List.of(DE), false, true));
        assertEquals("[de] About us", page.getNode("about").getProperty("title_de_CH").getString());
        assertEquals(2, report.pages());
    }

    @Test
    void excludedPropertiesAreNotSent() throws Exception {
        translator(Set.of("title")).translate(new PageTranslator.Request(page, List.of(DE), false, false));
        assertFalse(page.hasProperty("title_de_CH"));
        assertFalse(sent.get(0).containsValue(Segment.text("Welcome")));
    }

    @Test
    void sourceLanguageIsNeverATarget() throws Exception {
        PageTranslator.Report report = translator(Set.of()).translate(new PageTranslator.Request(page, List.of(Locale.ENGLISH), false, false));
        assertEquals(0, report.languages().size());
        assertEquals(List.of(), sent);
    }

    @Test
    void oneFailingLanguageDoesNotStopTheOthers() throws Exception {
        PageTranslator t = new PageTranslator(languages, FIELDS, (texts, target, source) -> {
            if (target.equals(DE)) {
                throw new SupertextException("The Supertext service is currently unavailable.");
            }
            return prefixed(texts, "Bienvenue ");
        }, n -> { }, Set.of());

        PageTranslator.Report report = t.translate(new PageTranslator.Request(page, List.of(DE, FR), false, false));
        assertTrue(report.anyFailed());
        assertEquals("The Supertext service is currently unavailable.", report.languages().get(0).error());
        assertEquals("Bienvenue Welcome", page.getProperty("title_fr_CH").getString());
        assertFalse(page.hasProperty("title_de_CH"), "nothing written for the failed language");
    }

    @Test
    void rejectedKeyAbortsEverything() {
        PageTranslator t = new PageTranslator(languages, FIELDS, (texts, target, source) -> {
            throw new SupertextException("Authentication failed.", true, null);
        }, n -> { }, Set.of());
        assertThrows(SupertextException.class, () -> t.translate(new PageTranslator.Request(page, List.of(DE, FR), false, false)));
    }

    @Test
    void pagesWithoutTextsMakeNoRequest() throws Exception {
        page.setProperty("title", " ");
        text.setProperty("text", "");
        text.getNode("teaser").setProperty("teaserTitle", "");
        PageTranslator.Report report = translator(Set.of()).translate(new PageTranslator.Request(page, List.of(DE), false, false));
        assertEquals(List.of(), sent);
        assertEquals(0, report.translated());
        assertFalse(report.anyFailed());
    }

    @Test
    void siteLanguagesNameProperties() {
        assertEquals("title", languages.propertyName("title", Locale.ENGLISH));
        assertEquals("title_de_CH", languages.propertyName("title", DE));
        assertEquals(List.of(DE, FR), languages.targetLocales());
        assertEquals(Map.of("de_CH", DE).get("de_CH"), languages.byId("de_CH").orElseThrow());
        assertTrue(languages.isEnabled());
    }

    // --- Translation files (Export for translation / Import translation) -----------------

    /** The page's export, translated like a translator would ("[fr] " before every source). */
    private com.supertext.magnolia.translation.internal.sdk.files.TranslationFile exportedAndTranslated(String target) throws Exception {
        List<com.supertext.magnolia.translation.internal.sdk.files.FileSegment> segments = translator(Set.of()).fileSegments(page);
        String xliff = com.supertext.magnolia.translation.internal.sdk.files.TranslationFiles.exportXliff(List.of(
                TranslationFileExchange.job(page.getIdentifier(), target, "en", "fr-CH", segments)));
        String translated = java.util.regex.Pattern.compile("<source>(.*?)</source>").matcher(xliff)
                .replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group() + "<target>[fr] " + m.group(1) + "</target>"));
        return com.supertext.magnolia.translation.internal.sdk.files.TranslationFiles.read(translated).get(0);
    }

    @Test
    void fileSegmentsAreThePagesOwnTextsWithStableKeys() throws Exception {
        List<com.supertext.magnolia.translation.internal.sdk.files.FileSegment> segments = translator(Set.of()).fileSegments(page);
        assertEquals(List.of("@title", "main/0@text", "main/0/teaser@teaserTitle"), segments.stream().map(s -> s.getKey()).toList());
        assertTrue(segments.get(1).isHtml());
        page.setProperty("title_fr_CH", "Bienvenue");
        assertEquals(3, translator(Set.of()).fileSegments(page).size(), "existing translations are still exported");
    }

    @Test
    void importWritesOkTextsAndKeepsExistingTranslations() throws Exception {
        page.setProperty("title_fr_CH", "Bienvenue (edited)");
        var file = exportedAndTranslated("fr_CH");

        PageTranslator.FileImportResult result = translator(Set.of()).importFile(file, page, FR, false, false);
        assertEquals(new PageTranslator.FileImportResult(2, 0, 0, 0, 0, 1), result);
        assertEquals("Bienvenue (edited)", page.getProperty("title_fr_CH").getString(), "kept without overwrite");
        assertEquals("[fr] <p>Hello <b>world</b></p>", text.getProperty("text_fr_CH").getString());
        assertTrue(touched.contains(text));

        assertEquals(3, translator(Set.of()).importFile(file, page, FR, false, true).imported());
        assertEquals("[fr] Welcome", page.getProperty("title_fr_CH").getString(), "overwritten when asked");
    }

    @Test
    void sourceChangedIsImportedOnlyWhenAsked() throws Exception {
        var file = exportedAndTranslated("fr_CH");
        page.setProperty("title", "Welcome again");

        assertEquals(new PageTranslator.FileImportResult(2, 1, 0, 0, 0, 0), translator(Set.of()).importFile(file, page, FR, false, false));
        assertFalse(page.hasProperty("title_fr_CH"));
        assertEquals(3, translator(Set.of()).importFile(file, page, FR, true, true).imported());
        assertEquals("[fr] Welcome", page.getProperty("title_fr_CH").getString());
    }

    @Test
    void untranslatedInvalidAndMissingAreNotWritten() throws Exception {
        List<com.supertext.magnolia.translation.internal.sdk.files.FileSegment> segments = translator(Set.of()).fileSegments(page);
        String xliff = com.supertext.magnolia.translation.internal.sdk.files.TranslationFiles.exportXliff(List.of(
                TranslationFileExchange.job(page.getIdentifier(), "fr_CH", "en", "fr-CH", segments)));
        xliff = xliff.replaceFirst("(<trans-unit id=\"@title\"[^>]*>\\s*<source>Welcome</source>)", "$1<target>Bienvenue</target>");
        xliff = xliff.replaceFirst("(<trans-unit id=\"main/0@text\"[^>]*>\\s*<source>.*?</source>)", "$1<target>Bonjour <script>x</script></target>");
        var file = com.supertext.magnolia.translation.internal.sdk.files.TranslationFiles.read(xliff).get(0);
        text.setProperty("headline", "A headline added since the export");

        PageTranslator.FileImportResult result = translator(Set.of()).importFile(file, page, FR, true, true);
        assertEquals(new PageTranslator.FileImportResult(1, 0, 1, 1, 1, 0), result);
        assertEquals("Bienvenue", page.getProperty("title_fr_CH").getString());
        assertFalse(text.hasProperty("text_fr_CH"));
        assertFalse(text.hasProperty("headline_fr_CH"));
        assertFalse(text.getNode("teaser").hasProperty("teaserTitle_fr_CH"));
    }
}
