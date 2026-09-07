package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

import io.modelcontextprotocol.server.McpServerFeatures;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins that a served description citing a documentation LABEL also names the response key that
 * label maps to.
 *
 * <h2>The distinction being enforced</h2>
 *
 * <p>{@code docs/glossary.md} maps a human-facing label to the JSON field a response actually
 * carries — {@code M3} to {@code zigzagCount}, {@code parallelConnectionGap_V_p10} to
 * {@code vAxisParallelGapP10}. A label is resolvable to a reader who has the glossary open. It is
 * NOT resolvable to an agent whose only ground truth is the response, because no key of that name
 * is ever returned: an instruction to check a named metric is unfollowable if the name never
 * appears in what comes back.</p>
 *
 * <p>The project already writes these correctly almost everywhere, in a consistent shape — the key
 * first, the label in parentheses after it: <em>"{@code zigzagCount} (M3) flags route shapes
 * that…"</em>. This guard is that convention made checkable, rather than a new rule.</p>
 *
 * <h2>Why the map is read rather than restated</h2>
 *
 * <p>A hard-coded label list would drift from the glossary and would then pass while enforcing a
 * mapping the project no longer publishes. Reading the table means the guard cannot outlive its own
 * source: delete a glossary row and the label stops being checked, change the key it maps to and
 * the guard demands the new one.</p>
 *
 * <p>Only identifier-shaped labels are checked. A prose label such as "Hub-to-neighbour crowding"
 * cannot be mistaken for a response key, and requiring the field name beside every mention of it
 * would make ordinary sentences unwritable.</p>
 */
public class GlossaryLabelCitationResolvesTest {

    private static final String GLOSSARY = "docs/glossary.md";

    /**
     * Every document carrying a label-to-field table. The citation rule is keyed off the glossary,
     * which is the document the published-vocabulary exemption rests on; the existence rule below
     * covers both, because a table naming a field that does not exist misleads a reader wherever it
     * lives.
     */
    private static final List<String> LABEL_TABLES = List.of(GLOSSARY, "docs/layout-engine.md");

    private static final String PLUGIN_SOURCE = "net.vheerden.archi.mcp/src";

    /** {@code | **label** | `key` | description |}, with the label optionally backticked. */
    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*\\*\\*`?([^`*|]+)`?\\*\\*\\s*\\|\\s*([^|]*)\\|");

    /** The first backticked token in the key column — the response field the label denotes. */
    private static final Pattern FIRST_CODE = Pattern.compile("`([^`]+)`");

    /** A label a reader could mistake for a field name: no spaces, identifier characters only. */
    private static final Pattern IDENTIFIER_SHAPED = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    @Test
    public void shouldNameTheResponseKey_whereverAServedDescriptionCitesAGlossaryLabel() {
        Map<String, String> labelToKey = readGlossaryLabels();
        assertFalse("the glossary must yield at least one identifier-shaped label, or this guard "
                + "is asserting nothing", labelToKey.isEmpty());

        List<String> unresolvable = new ArrayList<>();
        for (McpServerFeatures.SyncToolSpecification spec : buildRegistry().getToolSpecifications()) {
            String served = spec.tool().description();
            if (served == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : labelToKey.entrySet()) {
                if (namesToken(served, entry.getKey()) && !served.contains(entry.getValue())) {
                    unresolvable.add(spec.tool().name() + " cites '" + entry.getKey()
                            + "' without naming '" + entry.getValue() + "'");
                }
            }
        }

        assertTrue("A served description tells the agent to read a metric under a name the "
                + "response never carries — resolvable from the glossary, but not from the reply, "
                + "which is the agent's only ground truth. Name the key and put the label in "
                + "parentheses after it, as this project already does elsewhere: " + unresolvable,
                unresolvable.isEmpty());
    }

    /**
     * Every field a label table names must exist in the plugin source.
     *
     * <p>This is the check that the citation rule above cannot make, and the one that matters more.
     * A guard which only compares a document against a pinned expectation proves that two strings
     * agree — if both carry the same wrong field name it is green forever, and its greenness is the
     * reason the error survives. Both sides were written from the same belief; the source was never
     * consulted. So consult it.</p>
     *
     * <p>Every backticked identifier in the key column is checked, not just the first: a row can
     * name a primary field and a detail companion beside it, and the companion is the easier one to
     * let rot.</p>
     */
    @Test
    public void shouldNameOnlyFieldsThatExistInTheSource_inEveryLabelTable() {
        Set<String> source = readPluginSource();
        List<String> dead = new ArrayList<>();

        for (String table : LABEL_TABLES) {
            for (Map.Entry<String, List<String>> row : readAllKeys(table).entrySet()) {
                for (String key : row.getValue()) {
                    if (!source.contains(key)) {
                        dead.add(table + " maps " + row.getKey() + " to '" + key
                                + "', which appears nowhere in " + PLUGIN_SOURCE);
                    }
                }
            }
        }

        assertTrue("A label table names a response field that does not exist, so a reader "
                + "following it looks up a key no reply ever carries: " + dead, dead.isEmpty());
    }

    // ---- harness ------------------------------------------------------------------------------

    /** Identifier-shaped glossary labels mapped to the response key each denotes. */
    private Map<String, String> readGlossaryLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String line : readRepoFile(GLOSSARY).split("\n", -1)) {
            Matcher row = ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            String label = row.group(1).trim();
            if (!IDENTIFIER_SHAPED.matcher(label).matches()) {
                continue;
            }
            Matcher key = FIRST_CODE.matcher(row.group(2));
            if (key.find()) {
                labels.put(label, key.group(1).trim());
            }
        }
        return labels;
    }

    /**
     * True when {@code text} names {@code token} standalone. A bare {@code contains} would let
     * {@code M3} match inside another identifier, and would let a label match as a prefix of the
     * longer label that contains it.
     */
    private static boolean namesToken(String text, String token) {
        int from = 0;
        while (true) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return false;
            }
            boolean leftClear = at == 0 || !isIdentifierChar(text.charAt(at - 1));
            int after = at + token.length();
            boolean rightClear = after >= text.length() || !isIdentifierChar(text.charAt(after));
            if (leftClear && rightClear) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Stands up the WHOLE live tool catalog, not one handler's worth.
     *
     * <p>Registering a single handler would make this guard sound only for the tools that handler
     * owns, and silently blind to a bare label added to any other — the guard would still be green,
     * and the description it was written to catch would ship. No SWT, no EMF, no OSGi: a stub
     * accessor is enough for descriptions.</p>
     */
    private CommandRegistry buildRegistry() {
        CommandRegistry registry = new CommandRegistry();
        SessionManager sessions =
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(new BaseTestAccessor(), new ResponseFormatter(), registry,
                sessions);
        return registry;
    }

    /** Every identifier-shaped backticked key in a table's key column, per label. */
    private Map<String, List<String>> readAllKeys(String table) {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        for (String line : readRepoFile(table).split("\n", -1)) {
            Matcher row = ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            List<String> identifiers = new ArrayList<>();
            Matcher code = FIRST_CODE.matcher(row.group(2));
            while (code.find()) {
                String candidate = code.group(1).trim();
                if (IDENTIFIER_SHAPED.matcher(candidate).matches()) {
                    identifiers.add(candidate);
                }
            }
            if (!identifiers.isEmpty()) {
                keys.put(row.group(1).trim(), identifiers);
            }
        }
        return keys;
    }

    /** Every identifier token appearing anywhere in the plugin source. */
    private Set<String> readPluginSource() {
        Path root = repoPath(PLUGIN_SOURCE);
        Set<String> tokens = new LinkedHashSet<>();
        Pattern identifier = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = identifier.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    tokens.add(m.group());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + root, e);
        }
        return tokens;
    }

    private String readRepoFile(String relative) {
        try {
            return Files.readString(repoPath(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + relative, e);
        }
    }

    private Path repoPath(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the checkout.");
    }
}
