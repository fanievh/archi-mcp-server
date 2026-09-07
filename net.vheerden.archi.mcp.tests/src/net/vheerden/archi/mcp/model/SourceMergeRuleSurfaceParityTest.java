package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.modelcontextprotocol.spec.McpSchema;

import net.vheerden.archi.mcp.handlers.ElementCreationHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * Pins that every surface describing the {@code source} traceability merge also states what the
 * merge refuses, in the same words.
 *
 * <h2>The rule being disclosed</h2>
 *
 * <p>{@code ConceptMetadata.mergeSourceProperties} prefixes each {@code source} key with
 * {@code mcp.source.} and folds it into {@code properties}. Two inputs are refused: a key that
 * already carries the prefix (which would otherwise be stored as
 * {@code mcp.source.mcp.source.tool}), and a key whose prefixed form matches an entry the caller
 * also passed in {@code properties} (which would otherwise overwrite the caller's own value).</p>
 *
 * <h2>Why a parity guard rather than a single assertion</h2>
 *
 * <p>Five surfaces told a caller that the merge happens and none told them what happens when it
 * collides — two tool descriptions, two {@code source} input-schema property descriptions, and the
 * README row. A caller reading any one of them had no way to know either input was a refusal, and
 * an agent that discovers a refusal it was never told about cannot tell a rejected call from a
 * broken server.</p>
 *
 * <p>The four served surfaces are read off the live registry rather than by grepping the handler,
 * because {@code javac} folds a description's {@code + "…"} concatenation into one constant-pool
 * entry: grepping the source tests the literal, not the string a client receives. The input-schema
 * property descriptions are part of that served surface too — a client renders them beside the
 * tool's own description — so both are asserted.</p>
 */
public class SourceMergeRuleSurfaceParityTest {

    /**
     * The fragment that carries the rule. It names the disposition (rejection) and both halves of
     * what is rejected, so it cannot be satisfied by prose that merely mentions the prefix — every
     * one of these surfaces already did that while saying nothing about a collision.
     */
    private static final String REFUSAL_RULE =
            "rejected rather than silently double-prefixed or overwriting the caller's value";

    /** The two tools whose descriptions own the rule. */
    private static final List<String> OWNING_TOOLS = List.of("create-element", "create-relationship");

    @Test
    public void shouldStateTheSourceMergeRefusals_inEveryToolDescriptionThatDescribesTheMerge() {
        CommandRegistry registry = buildRegistry();
        List<String> silent = new ArrayList<>();

        for (String tool : OWNING_TOOLS) {
            if (!servedDescriptionOf(registry, tool).contains(REFUSAL_RULE)) {
                silent.add(tool);
            }
        }

        assertTrue("These tools describe the source merge without saying that a colliding or "
                + "already-prefixed key is refused, so a caller learns the refusal only by "
                + "triggering it: " + silent + ". The wording to reuse is: \"" + REFUSAL_RULE + "\"",
                silent.isEmpty());
    }

    /**
     * The second served surface, and the one a client is most likely to read when filling the
     * parameter in: the {@code source} property's own schema description. A rule stated only in the
     * tool description is absent exactly where the caller is choosing the keys.
     */
    @Test
    public void shouldStateTheSourceMergeRefusals_inEverySourceInputSchemaDescription() {
        CommandRegistry registry = buildRegistry();
        List<String> silent = new ArrayList<>();

        for (String tool : OWNING_TOOLS) {
            if (!sourcePropertyDescriptionOf(registry, tool).contains(REFUSAL_RULE)) {
                silent.add(tool);
            }
        }

        assertTrue("The 'source' input-schema description is where a caller picks the keys, so a "
                + "refusal rule missing there is missing at the moment it matters: " + silent
                + ". The wording to reuse is: \"" + REFUSAL_RULE + "\"", silent.isEmpty());
    }

    /**
     * The fifth surface. The README's tool table is the first place a human reads about
     * {@code source}, and it is not served through the registry, so nothing else here can catch it
     * drifting out of agreement with the four that are.
     */
    @Test
    public void shouldStateTheSourceMergeRefusals_inTheReadmeToolTable() {
        String row = createElementRowOf(readRepoFile("README.md"));
        assertTrue("README.md's create-element row describes the source map's prefixing and must "
                + "state the refusals in the same words as the served surfaces, or a reader who "
                + "starts there is told half the contract. Scoped to that ROW, not the whole file: "
                + "a file-wide search passes on the sentence appearing anywhere, including a "
                + "section that has nothing to do with the tool table. The wording to reuse is: \""
                + REFUSAL_RULE + "\"", row.contains(REFUSAL_RULE));
    }

    // ---- harness ------------------------------------------------------------------------------

    private String servedDescriptionOf(CommandRegistry registry, String toolName) {
        return toolNamed(registry, toolName).description();
    }

    @SuppressWarnings("unchecked")
    private String sourcePropertyDescriptionOf(CommandRegistry registry, String toolName) {
        McpSchema.JsonSchema schema = toolNamed(registry, toolName).inputSchema();
        Object sourceProp = schema.properties().get("source");
        if (!(sourceProp instanceof Map)) {
            throw new AssertionError(toolName + " no longer registers a 'source' input property — "
                    + "if the parameter was removed this whole guard should go with it, not be "
                    + "quietly satisfied by its absence.");
        }
        Object description = ((Map<String, Object>) sourceProp).get("description");
        return description == null ? "" : description.toString();
    }

    private McpSchema.Tool toolNamed(CommandRegistry registry, String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + toolName))
                .tool();
    }

    /**
     * Stands up the live tool catalog for the handler that owns both descriptions. No SWT, no EMF,
     * no OSGi — a stub accessor is enough.
     */
    private CommandRegistry buildRegistry() {
        CommandRegistry registry = new CommandRegistry();
        new ElementCreationHandler(new BaseTestAccessor(), new ResponseFormatter(), registry, null)
                .registerTools();
        return registry;
    }

    /**
     * The single {@code create-element} row of the README tool table. Returned as its own string so
     * the assertion above cannot be satisfied by the sentence appearing elsewhere in the file.
     */
    private String createElementRowOf(String readme) {
        return readme.lines()
                .filter(line -> line.startsWith("| `create-element` |"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("README.md no longer has a create-element row "
                        + "in its tool table — if the table was restructured this check needs "
                        + "re-pointing, not deleting."));
    }

    /**
     * Reads a repo-relative file by walking upward from the working directory. {@code README.md} is
     * not on the classpath, so a runner started outside the checkout gets an explicit failure
     * rather than a silently skipped check.
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
}
