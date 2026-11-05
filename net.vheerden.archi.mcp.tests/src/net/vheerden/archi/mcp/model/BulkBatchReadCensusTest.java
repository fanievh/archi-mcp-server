package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import net.vheerden.archi.mcp.JavaSourceText;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Classifies every {@code bulk-mutate} operation by whether it reads the work staged ahead of it: the
 * operations earlier in the same call, and the commands the enclosing batch has queued. None of that work
 * has run when an operation is prepared, so an operation that reads only the live model answers for the
 * model as it was before all of it — a view the batch creates is "not found", an object it places is
 * missed in silence.
 *
 * <p>The classification is checked against the dispatch in {@code ArchiModelAccessorImpl.prepareOperation},
 * one {@code case} per operation, so a new operation fails this test until it is placed in a list, and an
 * operation that stops reading the batch fails it too. The operations that do not read it are checked
 * against the list {@code docs/mutation-model.md} publishes, so that list cannot drift from the code.</p>
 *
 * <p>An operation reads the staged work when its arm, or a {@code prepare*} it calls (a facade method, or a
 * model command's static {@code prepare}, followed two levels down), makes a resolving call: the view
 * reader, the batch's queue, or a lookup over both. One of this call's maps counts only when it is handed
 * to a {@code prepare*}; an arm that merely names or declares one reads nothing.</p>
 */
public class BulkBatchReadCensusTest {

    /** Operations that resolve what they address through the staged work. */
    private static final Set<String> READS = Set.of(
            "create-element", "create-view", "update-view", "add-to-view", "add-connection-to-view",
            "update-view-object", "update-view-connection", "set-view-label-expression", "clear-view",
            "add-group-to-view", "add-note-to-view", "add-view-reference-to-view", "add-image-to-view",
            "create-folder", "update-folder", "create-specialization", "update-specialization",
            "delete-specialization");

    /** Operations that read part of the staged work, with what they still miss; each is in the published list. */
    private static final Map<String, String> READS_IN_PART = Map.of(
            "update-element", "an inline specialization resolves through the batch; the element addressed does not",
            "update-relationship", "an inline specialization resolves through the batch; the relationship addressed does not",
            "create-relationship", "elements this call creates resolve; an endpoint the enclosing batch queued does not",
            "move-to-folder", "a target folder created earlier resolves; the object being moved does not",
            "delete-folder", "refuses a folder created earlier; deleting a not-yet-created object has no rule yet");

    /** Operations that read only the live model; exactly the list the docs publish, less the partial ones. */
    private static final Set<String> GAP = Set.of(
            "remove-from-view", "delete-element", "delete-relationship", "delete-view");

    /** Operations that address nothing an earlier operation could have made. */
    private static final Map<String, String> NOTHING_TO_RESOLVE = Map.of(
            "update-model", "addresses the model itself, which always exists");

    /** Operations whose staged read must be the shared view reader itself. */
    private static final Set<String> READS_THE_VIEW_READER = Set.of("set-view-label-expression", "clear-view");

    /** A call that resolves something through the staged work: the view reader (made, or read as {@code staged}), the queue, or a lookup over both. */
    private static final Pattern RESOLVES = Pattern.compile("\\bStagedViewState\\.of\\(|\\bcoalesceQueuedView\\("
            + "|\\bfindBackReferenced\\(|\\bfindBatchCreatedObject\\(|\\bfindProfileForBulkPrepare\\("
            + "|\\bresolveOrCreateProfile\\(|\\bmutationDispatcher\\.queued\\w*\\(|\\bstaged\\.\\w+\\(");

    /** This call's maps of what it made; they count only when handed to a call that can resolve through them. */
    private static final Pattern STAGED_MAP = Pattern.compile("\\bsameCall\\b"
            + "|\\bcreated(?:Elements|ViewObjects|Relationships|ViewConnections|Groups|Views|LeafObjects)\\b");

    /**
     * A {@code prepare*} call, or a {@code build*Command} a prepare delegates to: a facade method, or a
     * command class's static {@code X.prepare(}.
     */
    private static final Pattern PREPARE_CALL = Pattern.compile("\\b(?:([A-Z]\\w*)\\.)?(prepare\\w*|build\\w*Command)\\(");

    /** How far below the arm the census follows {@code prepare*} calls. */
    private static final int DEPTH = 2;

    private static final Pattern ARM = Pattern.compile("\\n\\s*case \"([a-z-]+)\"\\s*->");

    @Test
    public void everyBulkOperation_isClassifiedExactlyOnce() {
        List<String> all = new ArrayList<>();
        all.addAll(READS);
        all.addAll(READS_IN_PART.keySet());
        all.addAll(GAP);
        all.addAll(NOTHING_TO_RESOLVE.keySet());

        assertEquals("an operation is in two lists", new LinkedHashSet<>(all).size(), all.size());
        assertEquals(new TreeSet<>(BulkOperation.SUPPORTED_TOOLS_ORDERED), new TreeSet<>(all));
    }

    @Test
    public void everyBulkOperation_hasOneArmInTheDispatch() {
        assertEquals(new TreeSet<>(BulkOperation.SUPPORTED_TOOLS_ORDERED), new TreeSet<>(arms(facade()).keySet()));
    }

    @Test
    public void everyOperationThatReadsTheBatch_readsItInItsArmOrItsPrepare() {
        Map<String, String> arms = arms(facade());
        List<String> silent = new ArrayList<>();
        for (String tool : union(READS, READS_IN_PART.keySet())) {
            if (!readsStaged(tool, arms, facade())) {
                silent.add(tool);
            }
        }
        assertEquals("classified as reading the batch, but reads none of it", List.of(), silent);
    }

    @Test
    public void theViewReadingOperations_readThroughTheSharedViewReader() {
        Map<String, String> arms = arms(facade());
        for (String tool : READS_THE_VIEW_READER) {
            assertTrue(tool + " must read the view through StagedViewState: " + arms.get(tool),
                    arms.get(tool).contains("StagedViewState"));
        }
        assertTrue(READS.containsAll(READS_THE_VIEW_READER));
    }

    @Test
    public void everyOperationThatDoesNotReadTheBatch_saysSoInItsArm() {
        Map<String, String> arms = arms(facade());
        for (String tool : GAP) {
            assertFalse(tool + " reads the batch now: move it out of the gap list and the docs' list",
                    readsStaged(tool, arms, facade()));
        }
    }

    @Test
    public void thePublishedGapList_isTheGapAndPartialOperations() {
        assertEquals(new TreeSet<>(union(GAP, READS_IN_PART.keySet())), publishedGap());
    }

    /** Naming or declaring one of this call's maps is not reading the batch; handing it to a prepare is. */
    @Test
    public void aMapCounts_onlyWhenHandedToAPrepare() {
        assertFalse(resolves("{ Map<Integer, IArchimateDiagramModel> createdViews = new HashMap<>(); yield noop(id); }"));
        assertFalse(resolves("{ int n = createdViews.size(); yield prepareThing(id, n); }"));
        assertTrue(resolves("{ yield prepareThing(id, createdViews); }"));
        assertTrue(resolves("{ yield ClearViewCommand.prepare(model, folderOps.earlier(sessionId, sameCall)); }"));
    }

    /** A command class's static {@code prepare} is followed, and its reads through the view reader count. */
    @Test
    public void aStaticCommandPrepare_isFollowed() {
        assertFalse("the arm alone names nothing staged", resolves("{ yield ClearViewCommand.prepare(model, id); }"));
        assertTrue(readsStaged("{ yield ClearViewCommand.prepare(model, id); }", facade(), DEPTH));
        assertFalse("a class that is not in the model package is not followed",
                readsStaged("{ yield NoSuchCommand.prepare(model, id); }", facade(), DEPTH));
    }

    /** A facade member of any visibility is followed, and so is a {@code build*Command} a prepare delegates to. */
    @Test
    public void aMemberOfAnyVisibility_andABuiltCommand_areFollowed() {
        String source = "class X {\n    Command prepareThing(String id) {\n        return buildThingCommand(id);\n    }\n"
                + "\n    private Command buildThingCommand(String id) {\n        return lookup(staged.find(id));\n    }\n}\n";
        assertTrue("package-private prepare, then a build*Command", readsStaged("{ yield prepareThing(id); }", source, DEPTH));
        assertFalse("a call inside a body is not a declaration",
                readsStaged("{ yield prepareOther(id); }", "class X {\n    void a() {\n        return prepareOther(staged.x());\n    }\n}\n", DEPTH));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Each {@code case} of {@code prepareOperation}'s switch, mapped to its body with comments and strings blanked. */
    static Map<String, String> arms(String source) {
        String code = JavaSourceText.blank(source);
        int method = source.indexOf("private PreparedMutation<?> prepareOperation(");
        assertTrue("prepareOperation not found", method >= 0);
        int open = source.indexOf('{', source.indexOf("return switch (tool)", method));
        int close = matching(code, open);
        String raw = source.substring(open, close);
        String blanked = code.substring(open, close);
        Matcher m = ARM.matcher(raw);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(new int[] {m.start(), m.end()});
            names.add(m.group(1));
        }
        Map<String, String> arms = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : blanked.length();
            if (arms.put(names.get(i), blanked.substring(starts.get(i)[1], end)) != null) {
                fail("two arms for " + names.get(i));
            }
        }
        return arms;
    }

    /** Whether the arm, or a {@code prepare*} it calls (to {@link #DEPTH}), resolves through the staged work. */
    private static boolean readsStaged(String tool, Map<String, String> arms, String source) {
        return readsStaged(arms.get(tool), source, DEPTH);
    }

    static boolean readsStaged(String code, String source, int depth) {
        if (resolves(code)) {
            return true;
        }
        if (depth == 0) {
            return false;
        }
        Matcher called = PREPARE_CALL.matcher(code);
        while (called.find()) {
            String body = called.group(1) == null ? methodBody(source, called.group(2))
                    : staticBodies(called.group(1), called.group(2));
            if (body != null && readsStaged(body, source, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code code} itself resolves through the staged work: a resolution call, or one of this
     * call's maps handed to a {@code prepare*} call. Naming or declaring a map is not reading it.
     */
    static boolean resolves(String code) {
        if (RESOLVES.matcher(code).find()) {
            return true;
        }
        Matcher called = PREPARE_CALL.matcher(code);
        while (called.find()) {
            if (STAGED_MAP.matcher(arguments(code, called.end() - 1)).find()) {
                return true;
            }
        }
        return false;
    }

    /** The argument list of the call whose {@code (} is at {@code open}, parentheses balanced. */
    private static String arguments(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return code.substring(open + 1, i);
            }
        }
        throw new AssertionError("unbalanced parentheses from " + open);
    }

    /** Every {@code static} method {@code name} of the model class {@code cls}, bodies joined; null when none. */
    private static String staticBodies(String cls, String name) {
        String file = "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/" + cls + ".java";
        if (!Files.isRegularFile(Paths.get("..", file)) && !Files.isRegularFile(Paths.get(file))) {
            return null;
        }
        String code = JavaSourceText.blank(read(file));
        Matcher m = Pattern.compile("\\bstatic [^;{=()]*\\b" + name + "\\(").matcher(code);
        StringBuilder bodies = new StringBuilder();
        while (m.find()) {
            int open = code.indexOf('{', m.end());
            bodies.append(code, open, matching(code, open)).append('\n');
        }
        return bodies.length() == 0 ? null : bodies.toString();
    }

    private static String methodBody(String source, String name) {
        String code = JavaSourceText.blank(source);
        // A member declaration of any visibility: class members sit at four spaces, which a call
        // inside a body (deeper, or preceded by "return" or "=") never matches.
        Matcher m = Pattern.compile("\\n {4}(?! )[^;{=()]*\\b" + name + "\\(").matcher(code);
        StringBuilder bodies = new StringBuilder();
        while (m.find()) {
            int open = code.indexOf('{', m.end());
            bodies.append(code, open, matching(code, open)).append('\n');
        }
        return bodies.length() == 0 ? null : bodies.toString();
    }

    private static int matching(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        throw new AssertionError("unbalanced braces from " + open);
    }

    /** The operations the docs' "What still reports such an id as not found" sentence names. */
    private static Set<String> publishedGap() {
        String docs = read("docs/mutation-model.md");
        int from = docs.indexOf("What still reports such an id as not found");
        assertTrue("the published gap list is gone from docs/mutation-model.md", from >= 0);
        String sentence = docs.substring(from, docs.indexOf("That is a gap", from));
        Set<String> named = new TreeSet<>();
        Matcher m = Pattern.compile("`([a-z*-]+)`").matcher(sentence);
        while (m.find()) {
            String token = m.group(1);
            if (token.endsWith("*")) {
                String prefix = token.substring(0, token.length() - 1);
                BulkOperation.SUPPORTED_TOOLS_ORDERED.stream().filter(t -> t.startsWith(prefix)).forEach(named::add);
            } else {
                named.add(token);
            }
        }
        return named;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new TreeSet<>(a);
        u.addAll(b);
        return u;
    }

    private static String facade() {
        return read("net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java");
    }

    private static String read(String repoRelative) {
        for (String root : new String[] {"..", "."}) {
            Path path = Paths.get(root, repoRelative);
            if (Files.isRegularFile(path)) {
                try {
                    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new AssertionError("Could not resolve " + repoRelative + " from " + Paths.get("").toAbsolutePath()
                + " — this census cannot silently cover nothing");
    }
}
