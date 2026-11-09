package com.supertext.magnolia.translation.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;

import com.supertext.magnolia.translation.api.SupertextException;
import com.supertext.magnolia.translation.internal.sdk.files.FileSegment;
import com.supertext.magnolia.translation.internal.sdk.files.ImportedSegment;
import com.supertext.magnolia.translation.internal.sdk.files.TranslationFile;
import com.supertext.magnolia.translation.internal.sdk.files.TranslationFiles;
import com.supertext.magnolia.translation.internal.sdk.v1.Segment;

/**
 * Translates a page (its own properties, and those of its areas and components) from the
 * site's default language into other site languages. For every target language it collects
 * the translatable texts, has them translated together (the Supertext SDK packs them into one
 * HTML document, split when large), and writes the translations into the language's properties
 * ({@code title_de_CH}, …).
 *
 * <p>Existing translations are kept unless {@code overwrite} is set. Each language is saved
 * on its own: if one fails, the others are still saved, and the failed one is rolled back.</p>
 *
 * <p>The same texts, under the same keys (the node's path below the page, {@code @} and the property:
 * {@code @title}, {@code main/0@text}), are exported to translation files ({@link #fileSegments}) and
 * imported from them ({@link #importFile}), which writes with the same rules.</p>
 *
 * <p>Works on plain JCR nodes; Magnolia specifics (which fields are translatable, how a node is
 * marked modified, how Supertext is called) are passed in, so it is unit-tested with mock nodes.</p>
 */
public final class PageTranslator {

    public static final String PAGE_TYPE = "mgnl:page";
    private static final String DELETED_MIXIN = "mgnl:deleted";

    /**
     * Translates texts (key → segment) and returns key → translation. Fails as a whole when a
     * text is missing from the answer, so a language is either translated completely or not at all.
     */
    @FunctionalInterface
    public interface TextTranslator {
        Map<String, String> translate(Map<String, Segment> texts, Locale target, Locale source) throws SupertextException, InterruptedException;
    }

    /** Marks a node as changed (Magnolia: last modified date and user). */
    @FunctionalInterface
    public interface ModificationMarker {
        void touched(Node node) throws RepositoryException;
    }

    /** What to translate. */
    public record Request(Node page, List<Locale> targets, boolean overwrite, boolean includeSubpages) {
    }

    /** Outcome for one language. {@code problem} is null when it worked. */
    public record LanguageResult(Locale locale, int translated, int kept, SupertextException problem) {
        public boolean failed() {
            return problem != null;
        }

        /** The problem's English message, or null when it worked. */
        public String error() {
            return problem == null ? null : problem.getMessage();
        }
    }

    /** Outcome for all languages. */
    public record Report(int pages, List<LanguageResult> languages) {
        public boolean anyFailed() {
            return languages.stream().anyMatch(LanguageResult::failed);
        }

        public int translated() {
            return languages.stream().mapToInt(LanguageResult::translated).sum();
        }
    }

    /**
     * Outcome of importing one job of a translation file. {@code imported} counts the texts written,
     * {@code kept} the translated texts not written because the language already had a translation
     * there and overwrite wasn't asked for.
     */
    public record FileImportResult(int imported, int sourceChanged, int untranslated, int invalidMarkup, int missing, int kept) {
    }

    private final SiteLanguages languages;
    private final FieldResolver fields;
    private final TextTranslator translator;
    private final ModificationMarker marker;
    private final Set<String> excluded;

    public PageTranslator(SiteLanguages languages, FieldResolver fields, TextTranslator translator, ModificationMarker marker, Set<String> excluded) {
        this.languages = languages;
        this.fields = fields;
        this.translator = translator;
        this.marker = marker;
        this.excluded = excluded == null ? Set.of() : excluded;
    }

    /**
     * @throws SupertextException when the API key is missing or rejected (nothing is saved then);
     *     other Supertext errors are reported per language in the {@link Report}
     */
    public Report translate(Request request) throws RepositoryException, SupertextException, InterruptedException {
        Locale source = languages.defaultLocale();
        List<Node> nodes = new ArrayList<>();
        int pages = collect(request.page(), request.includeSubpages(), nodes);

        List<LanguageResult> results = new ArrayList<>();
        for (Locale target : request.targets()) {
            if (target.equals(source)) {
                continue;
            }
            try {
                results.add(translateInto(nodes, source, target, request.overwrite()));
                request.page().getSession().save();
            } catch (SupertextException e) {
                request.page().getSession().refresh(false);
                if (e.isAuthenticationProblem()) {
                    throw e;
                }
                results.add(new LanguageResult(target, 0, 0, e));
            } catch (RepositoryException | RuntimeException e) {
                request.page().getSession().refresh(false);
                throw e;
            }
        }
        return new Report(pages, results);
    }

    private LanguageResult translateInto(List<Node> nodes, Locale source, Locale target, boolean overwrite)
            throws RepositoryException, SupertextException, InterruptedException {
        Map<String, Unit> units = new LinkedHashMap<>();
        int kept = collectUnits(nodes.get(0), nodes, target, overwrite, units);

        int translated = 0;
        Set<Node> touched = new LinkedHashSet<>();
        if (!units.isEmpty()) {
            Map<String, Segment> texts = new LinkedHashMap<>();
            units.forEach((key, unit) -> texts.put(key, unit.segment()));
            Map<String, String> answer = translator.translate(texts, target, source);
            for (Map.Entry<String, Unit> entry : units.entrySet()) {
                String value = answer.get(entry.getKey());
                if (value == null) {
                    continue;
                }
                Unit unit = entry.getValue();
                unit.node().setProperty(unit.property(), value);
                touched.add(unit.node());
                translated++;
            }
        }
        markTouched(touched);
        return new LanguageResult(target, translated, kept, null);
    }

    /**
     * Export for translation: the page's own texts (its properties, areas and components, no subpages)
     * in the site's default language, keyed like the API translation.
     */
    public List<FileSegment> fileSegments(Node page) throws RepositoryException {
        List<Node> nodes = new ArrayList<>();
        collect(page, false, nodes);
        Map<String, Unit> units = new LinkedHashMap<>();
        collectUnits(page, nodes, null, true, units);
        List<FileSegment> segments = new ArrayList<>();
        units.forEach((key, unit) -> segments.add(new FileSegment(key, unit.segment().getText(), unit.segment().isHtml())));
        return segments;
    }

    /**
     * Import translation: one job of a translation file into the page's {@code target} language. Collects the
     * page's texts again ({@link #fileSegments}), lets the SDK match the file's translations to them and writes
     * {@code ok} ones, and {@code source_changed} ones when {@code includeChanged}, into the language's properties.
     * Existing translations are kept unless {@code overwrite}, as for API translations. Saved at the end; on an
     * error nothing is saved.
     */
    public FileImportResult importFile(TranslationFile file, Node page, Locale target, boolean includeChanged, boolean overwrite)
            throws RepositoryException {
        List<Node> nodes = new ArrayList<>();
        collect(page, false, nodes);
        Map<String, Unit> units = new LinkedHashMap<>();
        collectUnits(page, nodes, target, true, units);
        List<FileSegment> current = new ArrayList<>();
        units.forEach((key, unit) -> current.add(new FileSegment(key, unit.segment().getText(), unit.segment().isHtml())));

        int imported = 0;
        int changed = 0;
        int untranslated = 0;
        int invalid = 0;
        int missing = 0;
        int kept = 0;
        Set<Node> touched = new LinkedHashSet<>();
        try {
            for (ImportedSegment result : TranslationFiles.importTranslations(file, current)) {
                switch (result.getStatus()) {
                    case OK, SOURCE_CHANGED -> {
                        if (result.getStatus() == com.supertext.magnolia.translation.internal.sdk.files.ImportStatus.SOURCE_CHANGED && !includeChanged) {
                            changed++;
                            continue;
                        }
                        Unit unit = units.get(result.getKey());
                        String existing = stringValue(unit.node(), unit.property());
                        if (existing != null && !existing.isBlank() && !overwrite) {
                            kept++;
                            continue;
                        }
                        unit.node().setProperty(unit.property(), result.getText());
                        touched.add(unit.node());
                        imported++;
                    }
                    case UNTRANSLATED -> untranslated++;
                    case INVALID_MARKUP -> invalid++;
                    default -> missing++;
                }
            }
            if (!touched.isEmpty()) {
                markTouched(touched);
                page.getSession().save();
            }
        } catch (RepositoryException | RuntimeException e) {
            page.getSession().refresh(false);
            throw e;
        }
        return new FileImportResult(imported, changed, untranslated, invalid, missing, kept);
    }

    /**
     * The translatable texts of {@code nodes} by key (path below {@code root}, {@code @}, property). With a
     * {@code target}, each unit writes to that language's property; without overwrite, texts that already have a
     * translation there are left out and counted (the return value).
     */
    private int collectUnits(Node root, List<Node> nodes, Locale target, boolean overwrite, Map<String, Unit> units) throws RepositoryException {
        Set<String> seen = new java.util.HashSet<>();
        int kept = 0;
        for (Node owner : nodes) {
            for (TranslatableField field : fields.fieldsOf(owner)) {
                if (excluded.contains(field.name())) {
                    continue;
                }
                Node node = field.childNode().isEmpty() ? owner : owner.hasNode(field.childNode()) ? owner.getNode(field.childNode()) : null;
                if (node == null || !seen.add(node.getPath() + "@" + field.name())) {
                    continue;
                }
                String text = stringValue(node, field.name());
                if (text == null || text.isBlank()) {
                    continue;
                }
                String targetName = target == null ? null : languages.propertyName(field.name(), target);
                String existing = targetName == null ? null : stringValue(node, targetName);
                if (existing != null && !existing.isBlank() && !overwrite) {
                    kept++;
                    continue;
                }
                units.put(key(root, node) + "@" + field.name(),
                        new Unit(node, targetName, field.html() ? Segment.html(text) : Segment.text(text)));
            }
        }
        return kept;
    }

    /** The node's path below {@code root} ("" for the root itself), so keys survive moving or renaming the page. */
    private static String key(Node root, Node node) throws RepositoryException {
        String rootPath = root.getPath();
        String path = node.getPath();
        if (path.equals(rootPath)) {
            return "";
        }
        return path.startsWith(rootPath + "/") ? path.substring(rootPath.length() + 1) : path;
    }

    private void markTouched(Set<Node> touched) throws RepositoryException {
        Set<Node> pagesTouched = new LinkedHashSet<>();
        for (Node node : touched) {
            marker.touched(node);
            Node page = pageOf(node);
            if (page != null && !touched.contains(page)) {
                pagesTouched.add(page);
            }
        }
        for (Node page : pagesTouched) {
            marker.touched(page);
        }
    }

    /** Adds the page, its areas and components (and subpages if asked) to {@code out}; returns the page count. */
    private static int collect(Node page, boolean includeSubpages, List<Node> out) throws RepositoryException {
        out.add(page);
        int pages = 1;
        NodeIterator children = page.getNodes();
        while (children.hasNext()) {
            Node child = children.nextNode();
            if (child.getName().startsWith("jcr:") || isDeleted(child)) {
                continue;
            }
            if (isPage(child)) {
                if (includeSubpages) {
                    pages += collect(child, true, out);
                }
                continue;
            }
            collectContent(child, out);
        }
        return pages;
    }

    private static void collectContent(Node node, List<Node> out) throws RepositoryException {
        out.add(node);
        NodeIterator children = node.getNodes();
        while (children.hasNext()) {
            Node child = children.nextNode();
            if (!child.getName().startsWith("jcr:") && !isPage(child) && !isDeleted(child)) {
                collectContent(child, out);
            }
        }
    }

    static boolean isPage(Node node) throws RepositoryException {
        return node.isNodeType(PAGE_TYPE);
    }

    private static boolean isDeleted(Node node) throws RepositoryException {
        try {
            return node.isNodeType(DELETED_MIXIN);
        } catch (RepositoryException | UnsupportedOperationException e) {
            return false;
        }
    }

    private static Node pageOf(Node node) throws RepositoryException {
        Node current = node;
        while (current != null) {
            if (isPage(current)) {
                return current;
            }
            if (current.getDepth() == 0) {
                return null;
            }
            current = current.getParent();
        }
        return null;
    }

    private static String stringValue(Node node, String name) throws RepositoryException {
        if (!node.hasProperty(name)) {
            return null;
        }
        Property property = node.getProperty(name);
        if (property.isMultiple() || property.getType() != PropertyType.STRING) {
            return null;
        }
        return property.getString();
    }

    private record Unit(Node node, String property, Segment segment) {
    }
}
