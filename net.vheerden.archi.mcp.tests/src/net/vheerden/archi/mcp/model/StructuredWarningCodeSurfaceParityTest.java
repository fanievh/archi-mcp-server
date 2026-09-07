package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import net.vheerden.archi.mcp.handlers.ViewPlacementHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;

/**
 * Build-fired parity guard for the structured-warning code enumerations.
 *
 * <p><strong>What this enforces, in both directions.</strong> Every surface that presents itself as
 * <em>the</em> list of codes a tool emits must name all of that tool's codes, and must name
 * <em>none</em> of any other tool's. The code&nbsp;&rarr;&nbsp;tool split is committed in
 * {@code tools/structured-warning-code-map.txt}, every constant on {@link StructuredWarningCodes}
 * must be classified there exactly once, and a code classified nowhere fails the build.</p>
 *
 * <p>The second direction is not symmetry for its own sake. The holder is shared, and the routing
 * surfaces correctly omit the layout and spacing codes — so a well-meaning edit that "completes"
 * a routing list by adding all eight constants is a <em>regression wearing a fix's clothes</em>. A
 * subset check ({@code owed} &sube; {@code text}) cannot see it. Both directions are asserted per
 * surface, off the same committed map.</p>
 *
 * <p><strong>Why it exists.</strong> A negative enumeration <em>claims by omission</em>. An agent
 * reads one of these lists to decide whether a code it just received on the wire is a code this
 * server supports, so an absence has to mean "this code does not exist" rather than "nobody updated
 * this file". Before this guard the surfaces had drifted three separate ways at once — each file
 * internally consistent, none of them agreeing — because nothing compared them against each other.
 * They were only ever compared against the code, one at a time, which is exactly the check that
 * cannot see an omission.</p>
 *
 * <p><strong>Why the denominator is committed rather than derived.</strong> The constants live in
 * one flat holder shared by several tools, and the routing surfaces correctly omit the layout and
 * spacing codes. Deriving ownership from the constant names is a guess, and a guess that demanded
 * every constant of every surface would push the routing enumerations to the wrong count and read
 * as a fix while being a regression. Each assignment in the map was measured from the emitting call
 * site.</p>
 *
 * <p><strong>What the surfaces are.</strong> Two are ordinary repo files read by walking up from
 * the working directory ({@code README.md}, {@code docs/routing-pipeline.md} — neither is on the
 * classpath). Two are served MCP resources read through the same classloader lookup
 * {@code ResourceHandler} uses at runtime, which resolves because this tests bundle is a
 * {@code Fragment-Host} of {@code net.vheerden.archi.mcp} and that bundle's
 * {@code build.properties bin.includes} carries {@code resources/}. The last is the tool's own
 * served description, read off {@code registry.getToolSpecifications()} — <strong>never off a
 * source grep</strong>, because {@code javac} folds a concatenated description into one
 * constant-pool entry, so the source text is not evidence of what is served.</p>
 *
 * <p>This is the build-fired promotion of the tool-description sync that
 * {@link SpacingTerminationReasonDocSyncTest} recorded as a code-review item rather than a guard.
 * Standing up the catalog turns out to be cheap: the handler takes a stub accessor and a bare
 * registry, with no SWT, EMF or OSGi runtime involved.</p>
 */
public class StructuredWarningCodeSurfaceParityTest {

    /** The committed code -> tool split. Repo-relative; not on the classpath. */
    private static final String CODE_MAP_FILE = "tools/structured-warning-code-map.txt";

    /**
     * Lower bound on the number of classified codes.
     *
     * <p>A FLOOR, never an equality: a ninth code must not break this guard merely by existing. The
     * exactly-once check is what keeps the file honest — this only catches a map gutted to nothing,
     * which would otherwise make every parity assertion below vacuously true.</p>
     */
    private static final int MAP_ENTRY_FLOOR = 9;

    /**
     * Lower bound on the routing family's size.
     *
     * <p>Same reasoning, applied to the family the surfaces below are registered for: a seventh
     * routing code is expected to raise this, but a map that lost the family entirely must not
     * leave five green assertions asserting nothing.</p>
     */
    private static final int ROUTING_FAMILY_FLOOR = 6;

    private static final String AUTO_ROUTE = "auto-route-connections";

    // ---- Surfaces -------------------------------------------------------------------------------

    private enum SurfaceKind { REPO_FILE, CLASSPATH_RESOURCE, SERVED_DESCRIPTION }

    /**
     * How far the enumerating block extends from its anchor line.
     *
     * <p>Load-bearing, and the reason this test does not simply grep whole files. A whole-file
     * check cannot see the defect this guard exists to catch: {@code docs/routing-pipeline.md}
     * discusses the note-crossing code in prose six hundred lines above the Structured Warnings
     * table, so the file contained the code while the <em>table</em> — the thing presenting itself
     * as the registry — did not. A reader who consults the table never reaches the prose. Scoping
     * each surface to its own enumerating block is what makes an omission visible.</p>
     */
    private enum Extent {
        /** The anchor line alone — a single table row, or a single bullet. */
        LINE,
        /** The anchor line through to the next blank line — a markdown table. */
        TO_BLANK,
        /** The anchor line through to the next {@code ##} heading — a whole section. */
        TO_HEADING,
        /** No anchor; the entire text is the enumeration (a served tool description). */
        ALL
    }

    /**
     * One published enumeration.
     *
     * @param tool    the tool whose codes this surface claims to enumerate
     * @param kind    how the surface is read
     * @param locator repo-relative path, classpath resource path, or tool name, per {@code kind}
     * @param extent  how far the enumerating block runs from {@code anchor}
     * @param anchor  substring identifying the block's first line; null when {@code extent} is ALL
     */
    private record Surface(String tool, SurfaceKind kind, String locator,
                           Extent extent, String anchor) { }

    /**
     * Every surface that presents itself as the list of {@code auto-route-connections} codes.
     *
     * <p>A surface belongs here when it <em>enumerates</em>. Files that merely mention a code in
     * passing are deliberately absent: {@code CHANGELOG.md} records when each code landed and is
     * chronological by nature, {@code docs/glossary.md} names codes inside the description cells of
     * metric-id rows, and {@code docs/layout-engine.md} owns the other family. Demanding parity of
     * a mention would make this guard wrong rather than stricter.</p>
     *
     * <p>Each anchor is a short, stable substring — a table header, a row's leading cell, a
     * bullet's opening phrase. If one stops matching, the assertion says so by name rather than
     * passing on an empty region; see {@code readSurface}.</p>
     */
    private static final List<Surface> SURFACES = List.of(
            new Surface(AUTO_ROUTE, SurfaceKind.REPO_FILE, "README.md",
                    Extent.LINE, "| `auto-route-connections` |"),
            new Surface(AUTO_ROUTE, SurfaceKind.REPO_FILE, "docs/routing-pipeline.md",
                    Extent.TO_BLANK, "| Code | Trigger | `remediationTool` |"),
            new Surface(AUTO_ROUTE, SurfaceKind.CLASSPATH_RESOURCE,
                    "resources/prompts/routing-preconditions-checklist.md",
                    Extent.TO_HEADING, "## Auto-route structured warnings"),
            new Surface(AUTO_ROUTE, SurfaceKind.CLASSPATH_RESOURCE,
                    "resources/reference/archimate-view-patterns.md",
                    Extent.LINE, "- **Ignoring `structuredWarnings[]` from `auto-route-connections`:**"),
            new Surface(AUTO_ROUTE, SurfaceKind.SERVED_DESCRIPTION, AUTO_ROUTE,
                    Extent.ALL, null));

    // ---- The pawl: every constant classified exactly once ---------------------------------------

    @Test
    public void shouldClassifyEveryRegistryConstantExactlyOnce_whenTheCodeMapIsRead() {
        Map<String, String> byField = reflectCodeConstants();
        assertTrue("StructuredWarningCodes should expose at least " + MAP_ENTRY_FLOOR
                        + " code constants; reflection found " + byField.size()
                        + ". A reflective seam that finds nothing makes every check below vacuous.",
                byField.size() >= MAP_ENTRY_FLOOR);

        // Every constant on this holder is a wire code whose published VALUE is its own NAME.
        // Asserting that does two jobs a value-keyed set cannot: two fields sharing one literal
        // stop collapsing into a single invisible entry, and a constant that is not a wire code
        // at all (a shared prefix, a URL, a message fragment) is named as the wrong KIND of thing
        // rather than being reported as an unclassified code, which would send the next reader
        // to the map file to fix something that does not belong there.
        List<String> mismatched = new ArrayList<>();
        byField.forEach((field, value) -> {
            if (!field.equals(value)) {
                mismatched.add(field + " = \"" + value + "\"");
            }
        });
        if (!mismatched.isEmpty()) {
            fail("StructuredWarningCodes holds public String constant(s) whose value is not their "
                    + "own name: " + mismatched + ". Every constant here is a code that goes out "
                    + "on the wire, so name and value must agree. If one of these is not a wire "
                    + "code, it does not belong on this holder — move it rather than classifying "
                    + "it in " + CODE_MAP_FILE + ".");
        }

        Set<String> constants = new LinkedHashSet<>(byField.values());
        Map<String, List<String>> map = readCodeMap();

        List<String> unclassified = new ArrayList<>();
        for (String code : constants) {
            if (!map.containsKey(code)) {
                unclassified.add(code);
            }
        }
        if (!unclassified.isEmpty()) {
            fail("Structured-warning code(s) classified NOWHERE: " + unclassified
                    + ". Every constant on StructuredWarningCodes must be assigned to its owning "
                    + "tool in " + CODE_MAP_FILE + ", because that file is the denominator every "
                    + "published enumeration is measured against. Classify it there (measure the "
                    + "owner from the emitting call site, do not infer it from the name), then add "
                    + "it to every surface that enumerates that tool's codes.");
        }

        List<String> unknown = new ArrayList<>(map.keySet());
        unknown.removeAll(constants);
        if (!unknown.isEmpty()) {
            fail(CODE_MAP_FILE + " classifies code(s) that are not constants on "
                    + "StructuredWarningCodes: " + unknown + ". A stale line makes the map claim a "
                    + "denominator the server does not publish.");
        }

        assertTrue(CODE_MAP_FILE + " should classify at least " + MAP_ENTRY_FLOOR
                        + " codes; it classifies " + map.size(),
                map.size() >= MAP_ENTRY_FLOOR);
    }

    @Test
    public void shouldNameOnlyRegisteredTools_whenTheCodeMapIsRead() {
        Set<String> registered = registeredToolNames();
        List<String> offenders = new ArrayList<>();
        readCodeMap().forEach((code, tools) -> {
            for (String tool : tools) {
                if (!registered.contains(tool)) {
                    offenders.add(code + " -> " + tool);
                }
            }
        });
        if (!offenders.isEmpty()) {
            fail(CODE_MAP_FILE + " assigns code(s) to unregistered tool name(s): " + offenders
                    + ". Registered tools are: " + new TreeSet<>(registered)
                    + ". A typo or a renamed tool would otherwise park a code in a family that has "
                    + "no surfaces, silently exempting it from every parity check below.");
        }
    }

    // ---- The parity assertions ------------------------------------------------------------------

    @Test
    public void shouldKeepTheRoutingFamilyAtItsFloor_whenTheCodeMapIsRead() {
        Set<String> routing = codesOwnedBy(readCodeMap(), AUTO_ROUTE);
        assertTrue(AUTO_ROUTE + " should own at least " + ROUTING_FAMILY_FLOOR
                        + " codes in " + CODE_MAP_FILE + "; the map assigns it " + routing.size()
                        + " " + new TreeSet<>(routing)
                        + ". A shrunken family leaves the surface checks passing while asserting "
                        + "less than they did.",
                routing.size() >= ROUTING_FAMILY_FLOOR);
    }

    @Test
    public void shouldNameEveryCodeOfItsTool_whenEachEnumeratingSurfaceIsRead() {
        Map<String, List<String>> map = readCodeMap();
        List<String> failures = new ArrayList<>();
        for (Surface surface : SURFACES) {
            Set<String> owed = codesOwnedBy(map, surface.tool());

            // Not decoration. With an empty `owed` the loop below runs zero times for every
            // surface and this method reports green having checked nothing — a pass on exactly
            // the drift it exists to catch. The family floor is asserted in its own @Test, which
            // does not help anyone running this method alone.
            assertFalse(CODE_MAP_FILE + " assigns no codes to " + surface.tool()
                            + ", so the parity check over " + surface.locator()
                            + " would assert nothing at all.",
                    owed.isEmpty());

            String text = readSurface(surface);

            for (String code : owed) {
                if (!namesCode(text, code)) {
                    failures.add(describe(surface) + " OMITS " + code);
                }
            }

            // The other direction, and the one a subset check cannot see. A surface that ADDS a
            // code belonging to another tool is the "routing count creeps to eight" regression
            // this story exists to prevent — a change that reads as a fix while being the defect.
            // Checking only `owed ⊆ text` leaves that entirely unguarded.
            for (String foreign : map.keySet()) {
                if (owed.contains(foreign)) {
                    continue;
                }
                if (namesCode(text, foreign)) {
                    failures.add(describe(surface) + " CLAIMS " + foreign
                            + ", which " + CODE_MAP_FILE + " assigns to "
                            + map.get(foreign) + " and not to " + surface.tool());
                }
            }
        }
        if (!failures.isEmpty()) {
            fail("Published enumeration(s) out of parity with " + CODE_MAP_FILE + ":\n  "
                    + String.join("\n  ", failures)
                    + "\n\nEach of these surfaces presents itself as THE list of its tool's codes. "
                    + "An OMISSION tells a reader the code does not exist; a CLAIM of another "
                    + "tool's code tells them this tool emits something it never emits. Fix the "
                    + "surface in its own voice — or, if the ownership itself is wrong, correct it "
                    + "in " + CODE_MAP_FILE + ".");
        }
    }

    private String describe(Surface surface) {
        return surface.locator() + " (" + surface.kind() + ", " + surface.extent()
                + " from \"" + describeAnchor(surface) + "\")";
    }

    private String describeAnchor(Surface surface) {
        return surface.anchor() == null ? "the whole description" : surface.anchor();
    }

    // ---- Reading the surfaces -------------------------------------------------------------------

    private String readSurface(Surface surface) {
        String text = switch (surface.kind()) {
            case REPO_FILE -> readRepoFile(surface.locator());
            case CLASSPATH_RESOURCE -> readClasspathResource(surface.locator());
            case SERVED_DESCRIPTION -> servedDescriptionOf(surface.locator());
        };
        return narrowToEnumeratingBlock(surface, text);
    }

    /**
     * Cuts {@code text} down to the block that does the enumerating.
     *
     * <p>An anchor that no longer matches fails here rather than silently yielding an empty region.
     * That distinction is the whole guard: an empty region satisfies every "contains" assertion
     * below vacuously, so a surface whose heading was reworded would otherwise stop being checked
     * at the moment it most needed checking.</p>
     */
    private String narrowToEnumeratingBlock(Surface surface, String text) {
        if (surface.extent() == Extent.ALL) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        int start = -1;
        int matches = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(surface.anchor())) {
                matches++;
                if (start < 0) {
                    start = i;
                }
            }
        }
        // An AMBIGUOUS anchor is the same hazard as a missing one, and quieter: the scan would
        // lock onto whichever copy came first and check a block nobody meant, either masking a
        // real omission or failing on unrelated prose. Neither outcome names the real problem.
        if (matches > 1) {
            throw new AssertionError("Anchor matches " + matches + " lines in "
                    + surface.locator() + ": '" + surface.anchor() + "'. It must identify ONE "
                    + "block. Narrow the anchor so it picks out the enumerating block and nothing "
                    + "else — do not let the scan guess which copy was meant.");
        }
        if (start < 0) {
            throw new AssertionError("Anchor not found in " + surface.locator() + ": '"
                    + surface.anchor() + "'. The enumerating block has moved or been reworded. "
                    + "Re-anchor it here — do NOT delete the surface, and do not let this test "
                    + "fall back to scanning the whole file: a whole-file scan cannot tell an "
                    + "incomplete table from a complete one when the file discusses the code "
                    + "somewhere else.");
        }
        StringBuilder block = new StringBuilder(lines[start]).append('\n');
        for (int i = start + 1; i < lines.length; i++) {
            String line = lines[i];
            boolean stop = switch (surface.extent()) {
                case LINE -> true;
                case TO_BLANK -> line.isBlank();
                case TO_HEADING -> line.startsWith("## ");
                case ALL -> true;
            };
            if (stop) {
                break;
            }
            block.append(line).append('\n');
        }
        return block.toString();
    }

    /**
     * Reads a repo-relative file by walking upward from the working directory.
     *
     * <p>{@code README.md} and {@code docs/**} are not on the classpath, so the classloader lookup
     * cannot see them. A runner started outside the checkout gets the explicit failure below rather
     * than a silently skipped check — the alternative failure mode for a pawl is to quietly stop
     * pawling.</p>
     */
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

    /**
     * Reads a served MCP resource through the classloader, exactly as {@code ResourceHandler} does.
     *
     * <p>If this returns null the path is usually innocent: check that the tests bundle still
     * declares {@code Fragment-Host: net.vheerden.archi.mcp} and that the production bundle's
     * {@code build.properties bin.includes} still lists {@code resources/}. Those two headers are
     * what put the served markdown on the test classpath at all.</p>
     */
    private String readClasspathResource(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new AssertionError("Served MCP resource " + resourcePath + " is not on the "
                        + "test classpath. Check Fragment-Host on the tests bundle and "
                        + "bin.includes resources/ on net.vheerden.archi.mcp before assuming the "
                        + "path is wrong.");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read served resource " + resourcePath, e);
        }
    }

    /**
     * Reads what the server actually serves for a tool.
     *
     * <p>Off the registry, never off the source. {@code javac} folds the description's
     * {@code + "…"} concatenation into a single constant-pool entry, so grepping
     * {@code ViewPlacementHandler.java} tests the source text and not the served string — and the
     * two can differ, because a description can be assembled from more than the literal you read.</p>
     */
    private String servedDescriptionOf(String toolName) {
        return buildRegistry().getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + toolName))
                .tool()
                .description();
    }

    private Set<String> registeredToolNames() {
        Set<String> names = new LinkedHashSet<>();
        buildRegistry().getToolSpecifications().forEach(spec -> names.add(spec.tool().name()));
        return names;
    }

    /** Stands up the live tool catalog. No SWT, no EMF, no OSGi — a stub accessor is enough. */
    private CommandRegistry buildRegistry() {
        CommandRegistry registry = new CommandRegistry();
        new ViewPlacementHandler(new BaseTestAccessor(), new ResponseFormatter(), registry, null)
                .registerTools();
        return registry;
    }

    /**
     * True when {@code text} names {@code code} as a standalone token.
     *
     * <p>A bare {@code contains} is not good enough here, and the collision is not hypothetical:
     * {@code ViewPlacementHandler} declares {@code CONNECTION_NOT_FOUND_STEP},
     * {@code AUTO_ROUTE_CROSSINGS_REGRESSED_STEP}, {@code EGRESS_LIFT_LAYOUT_BOUND_STEP} and two
     * more, each carrying its bare code as a strict prefix. A surface that named only the
     * {@code _STEP} constant would satisfy {@code contains(CODE)} while never naming the code a
     * reader actually keys off — a false pass on the exact question this guard asks.</p>
     */
    private static boolean namesCode(String text, String code) {
        int from = 0;
        while (true) {
            int at = text.indexOf(code, from);
            if (at < 0) {
                return false;
            }
            boolean leftClear = at == 0 || !isIdentifierChar(text.charAt(at - 1));
            int after = at + code.length();
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

    // ---- Reading the map ------------------------------------------------------------------------

    /** Parses {@code CODE tool[,tool] # comment} lines into code -> tools, preserving file order. */
    private Map<String, List<String>> readCodeMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String raw : readRepoFile(CODE_MAP_FILE).split("\n", -1)) {
            lineNumber++;
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 2) {
                fail(CODE_MAP_FILE + ":" + lineNumber + " is malformed: '" + raw.trim()
                        + "'. Expected '<CODE> <tool>[,<tool>...] # why'.");
            }
            // The file's own header documents the format as "<CODE> <tool>[,<tool>] # why".
            // Writing a claim the build does not check is the defect this whole story is about,
            // so the reason is enforced — at the same 20-character bar EffectiveStateContractTest
            // holds tools/effective-state-gaps.txt to.
            String reason = hash >= 0 ? raw.substring(hash + 1).trim() : "";
            if (reason.length() < 20) {
                fail(CODE_MAP_FILE + ":" + lineNumber + " (" + parts[0] + ") needs a '# why' "
                        + "reason of at least 20 characters saying how this tool was determined "
                        + "to own the code. Ownership is measured from the emitting call site, "
                        + "never inferred from the constant name, and the next reader needs to "
                        + "be able to tell which was done.");
            }
            List<String> tools = new ArrayList<>();
            for (String tool : parts[1].split(",")) {
                String trimmed = tool.trim();
                if (trimmed.isEmpty()) {
                    fail(CODE_MAP_FILE + ":" + lineNumber + " (" + parts[0] + ") has an empty "
                            + "entry in its tool list: '" + parts[1] + "'.");
                }
                tools.add(trimmed);
            }
            if (map.put(parts[0], tools) != null) {
                fail(CODE_MAP_FILE + " classifies " + parts[0] + " more than once (line "
                        + lineNumber + "). Every code must be classified EXACTLY once, or the "
                        + "denominator a surface is measured against depends on which line wins.");
            }
        }
        return map;
    }

    private Set<String> codesOwnedBy(Map<String, List<String>> map, String tool) {
        Set<String> owned = new LinkedHashSet<>();
        map.forEach((code, tools) -> {
            if (tools.contains(tool)) {
                owned.add(code);
            }
        });
        return owned;
    }

    /**
     * Reflectively enumerates every {@code public static final String} constant on
     * {@link StructuredWarningCodes}.
     *
     * <p>This is the seam that picks up a future code: a constant added to the holder arrives here
     * without anyone editing this test, and then fails the classification check until it is
     * committed to the map.</p>
     */
    private Map<String, String> reflectCodeConstants() {
        Map<String, String> byField = new LinkedHashMap<>();
        for (Field f : StructuredWarningCodes.class.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (!Modifier.isPublic(mods)
                    || !Modifier.isStatic(mods)
                    || !Modifier.isFinal(mods)
                    || f.getType() != String.class) {
                continue;
            }
            try {
                String value = (String) f.get(null);
                if (value != null) {
                    byField.put(f.getName(), value);
                }
            } catch (IllegalAccessException e) {
                fail("Reflective access to StructuredWarningCodes." + f.getName() + " failed: "
                        + e.getMessage());
            }
        }
        return byField;
    }
}
