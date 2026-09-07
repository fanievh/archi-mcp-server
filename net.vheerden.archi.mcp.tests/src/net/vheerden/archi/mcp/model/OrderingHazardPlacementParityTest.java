package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.ResourceHandler;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Build-fired guard for the ordering-hazard registry in {@code docs/ordering-hazards.md}.
 *
 * <p><strong>What this enforces.</strong> An ordering hazard — a caveat whose remedy is a change in
 * sequence — must be stated in the description of the tool that is unsafe to call at the wrong
 * time. The registry names, per hazard, the tools that own it. This guard reads that file and
 * asserts every owning tool's <em>served</em> description carries the row's probe spans.</p>
 *
 * <p><strong>Why a guard rather than a checklist.</strong> Every comparable mechanism in this repo
 * is a pawl, not a guideline. A registry of where hazards must live, with nothing checking they are
 * still there, drifts exactly the way the structured-warning enumerations drifted before
 * {@link StructuredWarningCodeSurfaceParityTest} — each file internally consistent, none of them
 * agreeing — because nothing compared them against each other.</p>
 *
 * <p><strong>Presence, not text equality.</strong> The assertion is that the row's probe spans
 * appear in the description, never that the description matches a pinned sentence. Pinning wording
 * turns every future editorial improvement into a guard failure and trains people to edit the
 * guard. The prose stays free.</p>
 *
 * <p><strong>What a probe has to be.</strong> The registry's terminating-action column supplies the
 * probes as backticked spans, and they have to be specific to the hazard. Measured at the time this
 * guard was written: {@code auto-route-connections} on its own appears in the <em>Related:</em> list
 * of four of the tools named below, none of which stated the hazard — so a bare tool-name probe
 * would have come back green on descriptions that said nothing. The phrase probes
 * ({@code before auto-route-connections}, {@code after auto-route-connections}) were absent from
 * every owner except the one tool that genuinely already carried its hazard, which is what makes
 * them discriminating. A probe that is already present everywhere before the hazard is placed is a
 * guard that cannot fail.</p>
 *
 * <p><strong>Descriptions are read off the registry, never off a source grep.</strong>
 * {@code javac} folds a concatenated description into one constant-pool entry, so the source text
 * is not evidence of what is served. Enumeration goes through {@link HandlerRegistrar}, the same
 * entry point the server boots from, so a tool added behind a brand-new handler class is visible
 * here too.</p>
 *
 * <p>This guard is deliberately NOT in {@code tools/osgi-excluded-tests.txt}: it runs in the
 * default headless lane and in CI. A pawl the build never pulls is not a pawl.</p>
 */
public class OrderingHazardPlacementParityTest {

    /** The committed registry. Repo-relative; not on the classpath. */
    private static final String REGISTRY_FILE = "docs/ordering-hazards.md";

    /**
     * The registry table's header, as a stable PREFIX rather than the whole line.
     *
     * <p>Deliberately stops before the third column's wording. Anchoring on the full line makes the
     * guard hostage to punctuation — an apostrophe swapped for a curly one by a paste through a
     * rich-text editor would take the table out of scope and report it as missing. The column
     * <em>arity</em> is checked separately, per row, so nothing is lost by anchoring short.</p>
     */
    private static final String TABLE_HEADER_PREFIX = "| Hazard | Owning tool description |";

    /** A markdown table separator: pipes, dashes, colons and spaces, nothing else. */
    private static final Pattern SEPARATOR = Pattern.compile("\\|[\\s:|-]+\\|");

    /**
     * Lower bound on the number of registry rows.
     *
     * <p>A FLOOR, never an equality: a new hazard must not break this guard merely by existing. It
     * catches a registry gutted to nothing, which would otherwise make every assertion below
     * vacuously true. Raise it in the commit that adds a row.</p>
     */
    private static final int ROW_FLOOR = 6;

    /** Lower bound on the registered surface, so a half-built registry cannot pass quietly. */
    private static final int TOOL_FLOOR = 60;

    private static final Pattern PROBE = Pattern.compile("`([^`]+)`");

    // ---- The parity assertion -------------------------------------------------------------------

    @Test
    public void shouldStateEveryRegisteredHazardInItsOwningToolDescription() {
        List<Row> rows = readRegistry();
        Set<String> registered = registeredToolNames();
        List<String> failures = new ArrayList<>();

        for (Row row : rows) {
            for (String tool : row.owners()) {
                if (!registered.contains(tool)) {
                    failures.add(REGISTRY_FILE + " names '" + tool + "' as an owner of \""
                            + row.shortHazard() + "\", but no such tool is registered. Either the "
                            + "tool was renamed and the row was not, or the row is a typo.");
                    continue;
                }
                String served = servedSurfaceOf(tool);
                for (String probe : row.probes()) {
                    if (!served.contains(probe)) {
                        failures.add(tool + " does not state \"" + row.shortHazard()
                                + "\" — its served surface is missing the probe span '"
                                + probe + "'.");
                    }
                }
            }
        }
        if (!failures.isEmpty()) {
            fail("Ordering hazard(s) not stated where the reader is standing:\n  "
                    + String.join("\n  ", failures)
                    + "\n\nAn agent reads ONE tool description, at the moment of the call. A hazard "
                    + "filed only in " + REGISTRY_FILE + ", a reference page or a recipe is found "
                    + "only by a reader who already suspected it. State it in the tool's own "
                    + "description, in the imperative, naming the operation it must follow or "
                    + "precede — or, if the ownership itself is wrong, correct the row.");
        }
    }

    /**
     * Every row must carry at least one probe, or its owners are unguarded.
     *
     * <p>Separate from the parity assertion on purpose: a row whose terminating action lost its
     * backticks would otherwise pass the loop above by having nothing to check, which is the
     * quietest way for a guard to stop guarding.</p>
     */
    @Test
    public void shouldGiveEveryRegistryRowAtLeastOneProbeAndOneOwner() {
        List<Row> rows = readRegistry();
        assertTrue("Expected at least " + ROW_FLOOR + " registry rows in " + REGISTRY_FILE
                + "; found " + rows.size() + ". A gutted registry makes the parity assertion "
                + "vacuous.", rows.size() >= ROW_FLOOR);
        List<String> failures = new ArrayList<>();
        for (Row row : rows) {
            if (row.owners().isEmpty()) {
                failures.add("\"" + row.shortHazard() + "\" names no owning tool.");
            }
            if (row.probes().isEmpty()) {
                failures.add("\"" + row.shortHazard() + "\" has no backticked probe span in its "
                        + "terminating action, so nothing about it is checked.");
            }
        }
        if (!failures.isEmpty()) {
            fail("Unenforceable row(s) in " + REGISTRY_FILE + ":\n  "
                    + String.join("\n  ", failures));
        }
    }

    /**
     * The registry stays a repo document, not a served MCP resource.
     *
     * <p>Serving it would create a public surface with its own parity obligations — a second
     * enumeration of the same hazards, free to drift from the descriptions this guard pins. The
     * registry is an authoring artefact; the tool descriptions are the delivery.</p>
     */
    @Test
    public void shouldNotServeTheRegistryAsAnMcpResource() {
        for (Map.Entry<String, ?> entry : resourceDefinitions().entrySet()) {
            assertTrue("The ordering-hazard registry must not be a served MCP resource, but "
                            + "ResourceHandler registers '" + entry.getKey() + "'.",
                    !entry.getKey().contains("ordering-hazard")
                            && !String.valueOf(entry.getValue()).contains("ordering-hazard"));
        }
    }

    /**
     * The rule is stated where a tool author is standing, not only in the registry it governs.
     *
     * <p>This document's own theme, applied to itself: a rule filed only in the artefact it
     * produces is a rule that is not where its reader is standing. The extension guide is the
     * checklist someone follows when adding a tool; the registry is what they reach for only once
     * they already know it exists.</p>
     */
    @Test
    public void shouldPointAtTheRegistryFromTheAuthoringSurfaces() {
        String addingATool = regionOf(readRepoFile("docs/extension-guide.md"), ADDING_A_TOOL);
        assertTrue("docs/extension-guide.md must point a tool author at " + REGISTRY_FILE
                + " from inside its \"" + ADDING_A_TOOL + "\" checklist block — the rule has to be "
                + "in the list they are already working through, not only in a See-also footer at "
                + "the bottom of the page they have stopped reading.",
                addingATool.contains("ordering-hazards.md"));
        String index = readRepoFile("docs/index.md");
        assertTrue("docs/index.md must list " + REGISTRY_FILE + " alongside the other documents.",
                index.contains("ordering-hazards.md"));
    }

    /** The extension guide's per-tool checklist block. */
    private static final String ADDING_A_TOOL = "**Adding a tool:**";

    /**
     * The block of {@code text} introduced by {@code anchor}, ending at the next bold label or
     * heading.
     *
     * <p>Scoped, not whole-file, and that is the difference between a check and the appearance of
     * one. This same page carries a See-also footer naming the registry, so
     * {@code guide.contains("ordering-hazards.md")} is satisfied whether or not the checklist item
     * still exists — it would stay green through exactly the deletion it claims to catch. The
     * repo has already paid for this once, on a doc parity guard that scanned a whole file while
     * the enumerating table inside it was incomplete.</p>
     */
    private static String regionOf(String text, String anchor) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        int matches = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(anchor)) {
                matches++;
                if (start < 0) {
                    start = i;
                }
            }
        }
        if (matches != 1) {
            throw new AssertionError("Anchor \"" + anchor + "\" matches " + matches + " lines; it "
                    + "must identify exactly one block. An anchor that matches none has been "
                    + "reworded and an anchor that matches several makes the scan guess — either "
                    + "way this check stops checking what it names.");
        }
        StringBuilder block = new StringBuilder();
        for (int i = start + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("**") || line.startsWith("#") || line.startsWith("---")) {
                break;
            }
            block.append(lines[i]).append('\n');
        }
        return block.toString();
    }

    // ---- Reading the registry -------------------------------------------------------------------

    /** One registry row: the hazard, the tools that own it, and the probes it is guarded by. */
    private record Row(String hazard, List<String> owners, List<String> probes) {
        String shortHazard() {
            return hazard.length() <= 70 ? hazard : hazard.substring(0, 67) + "...";
        }
    }

    /**
     * Parses the registry table.
     *
     * <p>Scoped to the one table, from its header line to the next blank line. A whole-file scan
     * would swallow the "Considered and not admitted" prose below it and read a hazard that was
     * deliberately refused as a row that must be placed.</p>
     *
     * <p>A header line only counts as the table when a markdown SEPARATOR row follows it. That does
     * two jobs at once. It lets the document quote its own header in prose — which a document
     * <em>about</em> this table is likely to want to do — without the guard reporting two registries.
     * And it removes the quiet failure in trusting {@code header + 2} blind: with the separator
     * deleted, a bare skip would drop the first data row on the floor, unread and unguarded, while
     * the row floor stayed satisfied by the rows below it. Here the table simply is not found, and
     * the guard says so.</p>
     */
    private List<Row> readRegistry() {
        String[] lines = readRepoFile(REGISTRY_FILE).split("\n", -1);
        int header = -1;
        int matches = 0;
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].trim().startsWith(TABLE_HEADER_PREFIX)
                    && SEPARATOR.matcher(lines[i + 1].trim()).matches()) {
                matches++;
                if (header < 0) {
                    header = i;
                }
            }
        }
        if (matches > 1) {
            throw new AssertionError(REGISTRY_FILE + " has " + matches + " tables with the registry "
                    + "header. There must be exactly one — two tables of hazards is the drift this "
                    + "guard exists to prevent.");
        }
        if (header < 0) {
            throw new AssertionError("Could not find the registry table in " + REGISTRY_FILE
                    + ". Expected a line starting \"" + TABLE_HEADER_PREFIX + "\" with a markdown "
                    + "separator row (|---|---|---|---|) directly beneath it. If the separator was "
                    + "deleted, restore it — do NOT relax this check: without it the first data row "
                    + "is read as the separator and skipped, which leaves a hazard unguarded while "
                    + "the build stays green.");
        }
        List<Row> rows = new ArrayList<>();
        for (int i = header + 2; i < lines.length; i++) {   // +2 skips the |---| separator
            String line = lines[i].trim();
            if (line.isEmpty()) {
                break;
            }
            String[] cells = line.split("\\|", -1);
            if (cells.length != 6) {                        // leading + 4 cells + trailing
                throw new AssertionError("Registry row " + (rows.size() + 1) + " in "
                        + REGISTRY_FILE + " has " + (cells.length - 2) + " cells, expected 4 "
                        + "(hazard | owning tool description | trigger | terminating action). A "
                        + "literal '|' inside a cell counts as a column separator here — there is no "
                        + "escaping — so write it some other way:\n  " + line);
            }
            rows.add(new Row(cells[1].trim(), spans(cells[2]), spans(cells[4])));
        }
        return rows;
    }

    /**
     * Every backticked span in a cell, in order, deduplicated.
     *
     * <p>Malformed backticks are rejected rather than tolerated. The regex takes single-backtick
     * spans only, so an unclosed backtick or a markdown double-backtick span would leave part of a
     * probe unread — and a probe that is quietly narrower than its author wrote is a row guarded by
     * less than it looks like. That is the one failure a guard must not have, so it is an error and
     * not a best effort.</p>
     */
    private static List<String> spans(String cell) {
        long backticks = cell.chars().filter(c -> c == '`').count();
        if (backticks % 2 != 0) {
            throw new AssertionError("Unbalanced backtick in a registry cell of " + REGISTRY_FILE
                    + " — a probe span would be read short:\n  " + cell.trim());
        }
        if (cell.contains("``")) {
            throw new AssertionError("Double-backtick code span in a registry cell of "
                    + REGISTRY_FILE + ". Probes are read as single-backtick spans, so this one "
                    + "would be read wrong. Rewrite the probe without a literal backtick:\n  "
                    + cell.trim());
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = PROBE.matcher(cell);
        while (m.find()) {
            found.add(m.group(1));
        }
        return List.of(found.toArray(new String[0]));
    }

    // ---- Reading the served surface -------------------------------------------------------------

    /**
     * Everything the server serves for a tool — off the registry, never off the source.
     *
     * <p>The whole tool specification, serialised, not just {@code description()}. A parameter's
     * own description is served in the input schema and is read by the same agent in the same
     * breath, and for at least one hazard it is the <em>right</em> place: the reader who needs to
     * know what a grid does to row height is the reader deciding the {@code columns} value, and
     * that note belongs beside the cell-width note already on that property. A guard that looked
     * only at {@code description()} would force the hazard away from its reader to satisfy itself.
     *
     * <p>The cost is that a probe can be satisfied from either half of the surface. That is the
     * right trade here — the rule is about the hazard reaching the reader at the moment of the
     * call, and both halves do — but it means the probes have to carry the discrimination, which
     * is why they are phrase-shaped rather than bare tool names.</p>
     */
    private String servedSurfaceOf(String toolName) {
        McpSchema.Tool tool = buildRegistry().getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + toolName))
                .tool();
        try {
            return tool.description() + "\n" + MAPPER.writeValueAsString(tool.inputSchema());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("Could not serialise the input schema of " + toolName
                    + "; the served surface cannot be read, so this guard must fail rather than "
                    + "check half of it.", e);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Set<String> registeredToolNames() {
        Set<String> names = new LinkedHashSet<>();
        buildRegistry().getToolSpecifications().forEach(spec -> names.add(spec.tool().name()));
        assertTrue("the registry should expose the full tool surface; got " + names.size(),
                names.size() >= TOOL_FLOOR);
        return names;
    }

    /** Stands up the live tool catalog. No SWT, no EMF, no OSGi — a stub accessor is enough. */
    private CommandRegistry buildRegistry() {
        CommandRegistry registry = new CommandRegistry();
        SessionManager sessions =
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(
                new BaseTestAccessor(), new ResponseFormatter(), registry, sessions);
        return registry;
    }

    /**
     * The served-resource table, read reflectively.
     *
     * <p>The field is private and stays private: this test asserts a negative about it, which is
     * not a reason to widen production visibility.</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, ?> resourceDefinitions() {
        try {
            Field field = ResourceHandler.class.getDeclaredField("RESOURCE_DEFINITIONS");
            field.setAccessible(true);
            return (Map<String, ?>) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // RuntimeException is not over-catching. The two most likely futures both throw one
            // rather than a ReflectiveOperationException: the field made non-static makes
            // Field.get(null) a NullPointerException, and a module or security policy makes
            // setAccessible an InaccessibleObjectException. Catching only the checked family would
            // surface those as a bare stack trace instead of the instruction below, which is the
            // whole point of having a message here.
            throw new AssertionError("ResourceHandler.RESOURCE_DEFINITIONS is no longer readable; "
                    + "re-point this check at whatever now holds the served resource table rather "
                    + "than deleting it.", e);
        }
    }

    /** Reads a repo-relative file by walking upward from the working directory. */
    private String readRepoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the checkout.");
    }
}
