package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.archimatetool.model.IArchimateModel;

/**
 * Checks every relationship pair the recipe pages declare against the relationship matrix Archi
 * itself enforces, so a recipe cannot tell an agent to make a {@code create-relationship} call
 * that Archi rejects.
 *
 * <p><strong>What it reads.</strong> Each recipe page opens with a {@code relationships} block,
 * one line per pair: {@code permitted} or {@code forbidden}, then the relationship type without its
 * {@code Relationship} suffix, the source element type and the target, with {@code *} for every
 * element type. The matrix is Archi's own data file, {@code model/relationships.xml}, with its key
 * letters from {@code model/relationships-keys.xml}, read from the {@code com.archimatetool.model}
 * bundle that holds {@link IArchimateModel}. The file is found from that class's own location
 * (beside the model jar on the harness classpath, inside the bundle under OSGi), not from an
 * install path, so it is found the same way on a developer machine and on CI. No Archi API is
 * called: the server's validity gate is backed by this same file, and the API that wraps it moved
 * between releases, while the file's format has not.</p>
 *
 * <p><strong>It cannot pass by finding nothing.</strong> A missing matrix fails, never skips, and
 * names the paths tried. The matrix must be in the format this class was written against (its
 * version, a total square of concepts, only the key letters it defines). A page with no block
 * fails, an unparseable line fails naming its page and line, and an unknown relationship or
 * element type fails, so a typo cannot read as "not permitted" and let a {@code forbidden} line
 * pass. The record totals across the corpus are pinned, so a block that parsed as empty is red.</p>
 *
 * <p><strong>Coverage, so the data cannot quietly fall behind the prose.</strong> Every
 * relationship type a page names in full in its prose ({@code FlowRelationship}) must appear in
 * one of that page's records, or be on {@link #NAMED_WITHOUT_A_PAIR} with the reason it has no
 * pair. Every element type a record names must appear in the page's prose as a code span
 * ({@code `Node`}), not merely as an English word. What this still cannot
 * see is a pair named only in prose and never declared: the block is the page author's statement
 * of the pairs, and this class checks that statement, not an extraction from English.</p>
 */
public class RecipeRelationshipMatrixTest {

    private static final String[] RESOURCE_ROOTS = {
            "net.vheerden.archi.mcp/resources",
            "../net.vheerden.archi.mcp/resources",
    };

    /** The matrix format this class parses. A different version stops the build rather than mis-reading. */
    private static final String MATRIX_VERSION = "3.2";
    private static final String KEYS_VERSION = "1.0";

    /** Element types in the matrix. Every ordered pair has a row, which is what makes "absent" mean "not permitted". */
    private static final int EXPECTED_CONCEPTS = 62;

    /** Pages in the recipe library, each of which must carry a block. */
    private static final int EXPECTED_PAGES = 6;

    /**
     * Records across the corpus. Pinned so a block that silently parsed as empty cannot pass as
     * clean. Update deliberately, in the commit that adds or removes a declared pair.
     */
    private static final int EXPECTED_PERMITTED = 37;
    private static final int EXPECTED_FORBIDDEN = 17;

    /**
     * Relationship types a page names in full without naming a pair for them, by page, with the
     * reason. Each entry must still be named in that page's prose and still lack a record, or it
     * fails as stale.
     */
    private static final Map<String, Map<String, String>> NAMED_WITHOUT_A_PAIR = Map.of(
            "index.md", Map.of(
                    "Specialization", "named for the type-tree shape, between elements of one type the page leaves open"),
            "application-integration.md", Map.of(
                    "Flow", "drawn among the page's element types; the page names no single pair",
                    "Serving", "drawn among the page's element types; the page names no single pair"),
            "behaviour-process-flow.md", Map.of(
                    "Serving", "drawn from the support layers up to the journey step served; no single pair"),
            "technology-deployment.md", Map.of(
                    "Serving", "drawn from a TechnologyService up to whatever it serves; no single target",
                    "Path", "named only to say ArchiMate has no such relationship"));

    private static final Pattern RECORD = Pattern.compile(
            "  - (permitted|forbidden) ([A-Za-z]+) (\\*|[A-Za-z]+) (\\*|[A-Za-z]+)");

    private static final Pattern NAMED_RELATIONSHIP = Pattern.compile("\\b([A-Z][a-z]+)Relationship\\b");

    // ---- The corpus -------------------------------------------------------------------------

    @Test
    public void shouldDeclareOnlyPairsArchiAccepts_onEveryRecipePage() {
        Matrix matrix = Matrix.load(MatrixSource.locate());
        Map<String, String> pages = recipePages();
        assertEquals("recipe pages", EXPECTED_PAGES, pages.size());

        List<String> problems = new ArrayList<>();
        int permitted = 0;
        int forbidden = 0;
        for (Map.Entry<String, String> page : pages.entrySet()) {
            Parsed parsed = parse(page.getKey(), page.getValue());
            problems.addAll(parsed.problems);
            problems.addAll(check(page.getKey(), parsed, matrix,
                    NAMED_WITHOUT_A_PAIR.getOrDefault(page.getKey(), Map.of())));
            for (Record r : parsed.records) {
                if (r.permitted) {
                    permitted++;
                } else {
                    forbidden++;
                }
            }
        }
        assertTrue("The recipe pages declare pairs Archi's relationship matrix contradicts, or the "
                + "declarations have fallen behind the prose:\n  " + String.join("\n  ", problems),
                problems.isEmpty());
        assertEquals("permitted records across the recipe pages", EXPECTED_PERMITTED, permitted);
        assertEquals("forbidden records across the recipe pages", EXPECTED_FORBIDDEN, forbidden);
    }

    @Test
    public void shouldReadArchisMatrixInTheFormatThisCheckWasWrittenFor() {
        MatrixSource source = MatrixSource.locate();
        Matrix matrix = Matrix.load(source);
        assertEquals("element types in " + source.relationships, EXPECTED_CONCEPTS, matrix.concepts.size());
        assertEquals("rows in " + source.relationships, EXPECTED_CONCEPTS * EXPECTED_CONCEPTS,
                matrix.relations.size());
        assertEquals("relationship types in " + source.keys, 11, matrix.letters.size());
    }

    // ---- Each way the check must fail -------------------------------------------------------

    @Test
    public void shouldFail_notSkip_whenTheMatrixFileIsMissing() throws IOException {
        Path empty = Files.createTempDirectory("no-archi-model");
        try {
            AssertionError e = assertThrows(AssertionError.class,
                    () -> Matrix.load(MatrixSource.inBundleDirectory(empty)));
            assertTrue(e.getMessage(), e.getMessage().contains(empty.resolve("model").toString()));
        } finally {
            Files.delete(empty);
        }
    }

    @Test
    public void shouldFail_whenTheMatrixListsARowTwice_soAnOverwrittenRowCannotPassAsTotal() throws IOException {
        Path bundle = Files.createTempDirectory("dup-archi-model");
        Path model = Files.createDirectory(bundle.resolve("model"));
        try {
            Files.writeString(model.resolve("relationships-keys.xml"), "<relationshipskeys version=\"1.0\">"
                    + "<key char=\"c\" relationship=\"CompositionRelationship\"/></relationshipskeys>");
            Files.writeString(model.resolve("relationships.xml"), "<relationships version=\"3.2\">"
                    + "<source concept=\"A\"><target concept=\"A\" relations=\"c\"/><target concept=\"A\" relations=\"\"/></source>"
                    + "</relationships>");
            AssertionError e = assertThrows(AssertionError.class,
                    () -> Matrix.load(MatrixSource.inBundleDirectory(bundle)));
            assertTrue(e.getMessage(), e.getMessage().contains("A lists target A twice"));
        } finally {
            Files.delete(model.resolve("relationships.xml"));
            Files.delete(model.resolve("relationships-keys.xml"));
            Files.delete(model);
            Files.delete(bundle);
        }
    }

    @Test
    public void shouldFail_whenAPermittedRecordIsAPairArchiRejects() {
        List<String> problems = checkPage("  - permitted Composition ApplicationComponent ApplicationService",
                "`ApplicationComponent` and `ApplicationService`, by `CompositionRelationship`.");
        assertEquals(List.of("p.md line 3: permitted Composition ApplicationComponent ApplicationService, "
                + "but Archi does not permit it (relations: fiortv)"), problems);
    }

    @Test
    public void shouldFail_whenAForbiddenRecordIsAPairArchiAccepts() {
        List<String> problems = checkPage("  - forbidden Assignment ApplicationComponent ApplicationService",
                "`ApplicationComponent` and `ApplicationService`, by `AssignmentRelationship`.");
        assertEquals(List.of("p.md line 3: forbidden Assignment ApplicationComponent ApplicationService, "
                + "but Archi permits it (relations: fiortv)"), problems);
    }

    @Test
    public void shouldFail_whenAWildcardRecordHasAnExceptionInTheMatrix() {
        List<String> problems = checkPage("  - forbidden Association * ApplicationComponent",
                "Any element and `ApplicationComponent`, by `AssociationRelationship`.");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0), problems.get(0).startsWith(
                "p.md line 3: forbidden Association * ApplicationComponent, but Archi permits it from "));
    }

    @Test
    public void shouldFail_whenARecordNamesAnUnknownTypeOrElement_soATypoCannotReadAsNotPermitted() {
        List<String> problems = checkPage("  - forbidden Compostion Node Node\n  - forbidden Composition Node Nod",
                "`Node`, by `CompositionRelationship`.");
        assertEquals(List.of(
                "p.md line 3: unknown relationship type 'Compostion'",
                "p.md line 4: unknown element type 'Nod'"), problems);
    }

    @Test
    public void shouldFail_whenAPageHasNoBlock() {
        Parsed parsed = parse("p.md", "# A page\n\nNo block here.\n");
        assertEquals(List.of("p.md: no relationships block at the top of the page"), parsed.problems);
    }

    @Test
    public void shouldReadABlock_behindAByteOrderMarkAndTrailingSpaces() {
        Parsed parsed = parse("p.md", "\uFEFF--- \r\nrelationships:\r\n  - permitted Composition Node Node  \r\n---\r\n`Node`\r\n");
        assertEquals(List.of(), parsed.problems);
        assertEquals(1, parsed.records.size());
    }

    @Test
    public void shouldFail_whenABlockLineDoesNotParse() {
        Parsed parsed = parse("p.md", "---\nrelationships:\n  - permitted Composition Node → Node\n---\n");
        assertEquals(List.of("p.md line 3: not a relationships record: '  - permitted Composition Node → Node'",
                "p.md: the relationships block declares no pairs"), parsed.problems);
    }

    @Test
    public void shouldFail_whenTheProseNamesARelationshipTypeNoRecordCovers() {
        List<String> problems = checkPage("  - permitted Composition Node Node",
                "`Node` by `CompositionRelationship`, and also `InfluenceRelationship`.");
        assertEquals(List.of("p.md: the prose names InfluenceRelationship but no record declares an "
                + "Influence pair; declare the pair, or list the type as named without a pair"), problems);
    }

    @Test
    public void shouldFail_whenARecordNamesAnElementTypeThePageNeverMentions() {
        List<String> problems = checkPage("  - permitted Composition Device Device",
                "`Node` by `CompositionRelationship`.");
        assertEquals(List.of("p.md line 3: names Device, which the page's prose never names as `Device`"), problems);
    }

    @Test
    public void shouldFail_whenTheElementTypeAppearsOnlyAsAnEnglishWord() {
        List<String> problems = checkPage("  - permitted Composition Node Node",
                "Node by `CompositionRelationship`: every Node nests.");
        assertEquals(List.of("p.md line 3: names Node, which the page's prose never names as `Node`"), problems);
    }

    @Test
    public void shouldFail_whenAnAllowListEntryHasGoneStale() {
        Matrix matrix = Matrix.load(MatrixSource.locate());
        Parsed parsed = parse("p.md", "---\nrelationships:\n  - permitted Composition Node Node\n---\n"
                + "`Node` by `CompositionRelationship`.\n");
        List<String> problems = check("p.md", parsed, matrix, Map.of(
                "Serving", "no longer named", "Composition", "now has a record"));
        assertEquals(List.of(
                "p.md: Composition is listed as named without a pair, but a record declares one",
                "p.md: Serving is listed as named without a pair, but the prose no longer names it"),
                problems);
    }

    // ---- The check --------------------------------------------------------------------------

    /** One page's block and prose, checked with no allow-list. */
    private static List<String> checkPage(String records, String prose) {
        Parsed parsed = parse("p.md", "---\nrelationships:\n" + records + "\n---\n\n" + prose + "\n");
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty());
        return check("p.md", parsed, Matrix.load(MatrixSource.locate()), Map.of());
    }

    static List<String> check(String page, Parsed parsed, Matrix matrix, Map<String, String> namedWithoutAPair) {
        List<String> problems = new ArrayList<>();
        Set<String> declaredTypes = new TreeSet<>();
        for (Record r : parsed.records) {
            String where = page + " line " + r.line + ": ";
            Character letter = matrix.letters.get(r.relationship);
            if (letter == null) {
                problems.add(where + "unknown relationship type '" + r.relationship + "'");
                continue;
            }
            declaredTypes.add(r.relationship);
            boolean known = true;
            for (String end : new LinkedHashSet<>(List.of(r.source, r.target))) {
                if (!end.equals("*") && !matrix.concepts.contains(end)) {
                    problems.add(where + "unknown element type '" + end + "'");
                    known = false;
                } else if (!end.equals("*") && !parsed.prose.contains("`" + end + "`")) {
                    problems.add(where + "names " + end + ", which the page's prose never names as `" + end + "`");
                }
            }
            if (!known) {
                continue;
            }
            List<String> contrary = new ArrayList<>();
            for (String s : r.source.equals("*") ? matrix.concepts : Set.of(r.source)) {
                for (String t : r.target.equals("*") ? matrix.concepts : Set.of(r.target)) {
                    String relations = matrix.relations.get(s + " " + t);
                    if ((relations.indexOf(letter) >= 0) != r.permitted) {
                        contrary.add(s + " " + t + " (relations: " + relations + ")");
                    }
                }
            }
            if (contrary.isEmpty()) {
                continue;
            }
            boolean single = !r.source.equals("*") && !r.target.equals("*");
            problems.add(where + r.text() + ", but Archi " + (r.permitted ? "does not permit" : "permits")
                    + " it " + (single
                            ? "(relations: " + matrix.relations.get(r.source + " " + r.target) + ")"
                            : "from " + String.join(", ", contrary)));
        }

        Set<String> namedInProse = new TreeSet<>();
        Matcher m = NAMED_RELATIONSHIP.matcher(parsed.prose);
        while (m.find()) {
            namedInProse.add(m.group(1));
        }
        for (String type : namedInProse) {
            if (!declaredTypes.contains(type) && !namedWithoutAPair.containsKey(type)) {
                problems.add(page + ": the prose names " + type + "Relationship but no record declares an "
                        + type + " pair; declare the pair, or list the type as named without a pair");
            }
        }
        for (String type : new TreeSet<>(namedWithoutAPair.keySet())) {
            if (declaredTypes.contains(type)) {
                problems.add(page + ": " + type + " is listed as named without a pair, but a record declares one");
            } else if (!namedInProse.contains(type)) {
                problems.add(page + ": " + type + " is listed as named without a pair, but the prose no longer names it");
            }
        }
        return problems;
    }

    static Parsed parse(String page, String body) {
        String normalised = body.replace("\r\n", "\n");
        if (normalised.startsWith("\uFEFF")) {
            normalised = normalised.substring(1);
        }
        String[] lines = normalised.split("\n", -1);
        Parsed parsed = new Parsed();
        if (lines.length < 3 || !lines[0].strip().equals("---") || !lines[1].strip().equals("relationships:")) {
            parsed.problems.add(page + ": no relationships block at the top of the page");
            parsed.prose = body;
            return parsed;
        }
        int i = 2;
        for (; i < lines.length && !lines[i].strip().equals("---"); i++) {
            Matcher m = RECORD.matcher(lines[i].stripTrailing());
            if (m.matches()) {
                parsed.records.add(new Record(i + 1, m.group(1).equals("permitted"), m.group(2), m.group(3), m.group(4)));
            } else {
                parsed.problems.add(page + " line " + (i + 1) + ": not a relationships record: '" + lines[i] + "'");
            }
        }
        if (i == lines.length) {
            parsed.problems.add(page + ": the relationships block is never closed with '---'");
        }
        if (parsed.records.isEmpty()) {
            parsed.problems.add(page + ": the relationships block declares no pairs");
        }
        parsed.prose = Stream.of(lines).skip(Math.min(i + 1, lines.length)).collect(Collectors.joining("\n"));
        return parsed;
    }

    static final class Parsed {
        final List<Record> records = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        String prose = "";
    }

    static final class Record {
        final int line;
        final boolean permitted;
        final String relationship;
        final String source;
        final String target;

        Record(int line, boolean permitted, String relationship, String source, String target) {
            this.line = line;
            this.permitted = permitted;
            this.relationship = relationship;
            this.source = source;
            this.target = target;
        }

        String text() {
            return (permitted ? "permitted " : "forbidden ") + relationship + " " + source + " " + target;
        }
    }

    // ---- Archi's matrix ---------------------------------------------------------------------

    /** Where the two data files were found, as URLs a parser can open, and how to describe them. */
    static final class MatrixSource {
        final URL relationships;
        final URL keys;

        private MatrixSource(URL relationships, URL keys) {
            this.relationships = relationships;
            this.keys = keys;
        }

        /**
         * The files beside the model bundle's code. On the harness classpath the code source is the
         * model jar inside the bundle directory; under OSGi it is the bundle directory itself. If
         * neither holds the file, the bundle's class loader is asked, which finds it in a jarred
         * bundle. Nothing found is a failure that names every place tried.
         */
        static MatrixSource locate() {
            List<String> tried = new ArrayList<>();
            java.security.CodeSource code = IArchimateModel.class.getProtectionDomain().getCodeSource();
            URL codeSource = code == null ? null : code.getLocation();
            if (codeSource == null) {
                tried.add("the code source of " + IArchimateModel.class.getName() + " (none reported)");
            } else {
                try {
                    Path location = Paths.get(codeSource.toURI());
                    Path bundle = Files.isDirectory(location) ? location : location.getParent();
                    Path model = bundle == null ? null : bundle.resolve("model");
                    if (model != null && Files.isRegularFile(model.resolve("relationships.xml"))
                            && Files.isRegularFile(model.resolve("relationships-keys.xml"))) {
                        return inBundleDirectory(bundle);
                    }
                    tried.add(model == null ? location.toString() : model.toString());
                } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
                    tried.add(codeSource + " (not a file location: " + e.getMessage() + ")");
                }
            }
            ClassLoader loader = IArchimateModel.class.getClassLoader();
            URL relationships = loader == null ? null : loader.getResource("model/relationships.xml");
            URL keys = loader == null ? null : loader.getResource("model/relationships-keys.xml");
            if (relationships != null && keys != null) {
                return new MatrixSource(relationships, keys);
            }
            tried.add("model/relationships.xml on the class loader of " + IArchimateModel.class.getName());
            throw new AssertionError("Archi's relationship matrix was not found. Tried: " + String.join("; ", tried)
                    + ". This check fails rather than skips: a skipped check reports a clean corpus it never read.");
        }

        static MatrixSource inBundleDirectory(Path bundle) {
            Path model = bundle.resolve("model");
            Path relationships = model.resolve("relationships.xml");
            Path keys = model.resolve("relationships-keys.xml");
            if (!Files.isRegularFile(relationships) || !Files.isRegularFile(keys)) {
                throw new AssertionError("Archi's relationship matrix was not found at " + relationships
                        + " and " + keys + ". This check fails rather than skips: a skipped check reports a "
                        + "clean corpus it never read.");
            }
            try {
                return new MatrixSource(relationships.toUri().toURL(), keys.toUri().toURL());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static final class Matrix {
        /** Element types, as {@code concept="…"} spells them. */
        final Set<String> concepts = new TreeSet<>();
        /** {@code "Source Target"} to the relations letters. */
        final Map<String, String> relations = new LinkedHashMap<>();
        /** Relationship type without its suffix ({@code Composition}) to its key letter. */
        final Map<String, Character> letters = new TreeMap<>();

        static Matrix load(MatrixSource source) {
            Matrix matrix = new Matrix();
            Element keys = root(source.keys, "relationshipskeys", KEYS_VERSION);
            NodeList keyNodes = keys.getElementsByTagName("key");
            for (int i = 0; i < keyNodes.getLength(); i++) {
                Element key = (Element) keyNodes.item(i);
                String name = key.getAttribute("relationship");
                String letter = key.getAttribute("char");
                assertTrue("unexpected key in " + source.keys + ": " + letter + " " + name,
                        name.endsWith("Relationship") && letter.length() == 1);
                Character previous = matrix.letters.put(
                        name.substring(0, name.length() - "Relationship".length()), letter.charAt(0));
                assertTrue("relationship listed twice in " + source.keys + ": " + name, previous == null);
            }
            Set<Character> known = new LinkedHashSet<>(matrix.letters.values());
            assertEquals("one key letter per relationship in " + source.keys, matrix.letters.size(), known.size());

            Element relationships = root(source.relationships, "relationships", MATRIX_VERSION);
            NodeList sources = relationships.getElementsByTagName("source");
            for (int i = 0; i < sources.getLength(); i++) {
                matrix.concepts.add(((Element) sources.item(i)).getAttribute("concept"));
            }
            List<String> malformed = new ArrayList<>();
            if (matrix.concepts.size() != sources.getLength()) {
                malformed.add(sources.getLength() + " source blocks name only " + matrix.concepts.size()
                        + " element types; a source is listed twice");
            }
            for (int i = 0; i < sources.getLength(); i++) {
                Element s = (Element) sources.item(i);
                NodeList targets = s.getElementsByTagName("target");
                Set<String> seen = new TreeSet<>();
                for (int j = 0; j < targets.getLength(); j++) {
                    Element t = (Element) targets.item(j);
                    String target = t.getAttribute("concept");
                    String letters = t.getAttribute("relations");
                    for (char c : letters.toCharArray()) {
                        if (!known.contains(c)) {
                            malformed.add(s.getAttribute("concept") + " " + target + " carries '" + c
                                    + "', which the key file does not define");
                        }
                    }
                    if (!seen.add(target)) {
                        malformed.add(s.getAttribute("concept") + " lists target " + target + " twice");
                    }
                    matrix.relations.put(s.getAttribute("concept") + " " + target, letters);
                }
                if (!seen.equals(matrix.concepts)) {
                    malformed.add(s.getAttribute("concept") + " does not have exactly one row per element type");
                }
            }
            assertTrue("Archi's matrix is not in the format this check reads (" + source.relationships + "):\n  "
                    + String.join("\n  ", malformed), malformed.isEmpty());
            return matrix;
        }

        private static Element root(URL file, String name, String version) {
            try (InputStream in = file.openStream()) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(in);
                Element root = doc.getDocumentElement();
                assertEquals("root element of " + file, name, root.getTagName());
                assertEquals("format version of " + file + "; a new version needs this check re-read against it",
                        version, root.getAttribute("version"));
                return root;
            } catch (IOException e) {
                throw new AssertionError("Could not read " + file + ": " + e.getMessage(), e);
            } catch (Exception e) {
                throw new AssertionError("Could not parse " + file + ": " + e.getMessage(), e);
            }
        }
    }

    // ---- The pages --------------------------------------------------------------------------

    private static Map<String, String> recipePages() {
        Path recipes = null;
        for (String candidate : RESOURCE_ROOTS) {
            Path path = Paths.get(candidate, "recipes");
            if (Files.isDirectory(path)) {
                recipes = path;
                break;
            }
        }
        assertTrue("No recipes directory under " + String.join(", ", RESOURCE_ROOTS) + " from "
                + Paths.get("").toAbsolutePath(), recipes != null);
        Map<String, String> pages = new TreeMap<>();
        try (Stream<Path> stream = Files.list(recipes)) {
            for (Path p : stream.filter(p -> p.toString().endsWith(".md")).collect(Collectors.toList())) {
                pages.put(p.getFileName().toString(), new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return pages;
    }
}
