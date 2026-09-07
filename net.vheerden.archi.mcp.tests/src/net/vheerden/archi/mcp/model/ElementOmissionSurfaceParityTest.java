package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.modelcontextprotocol.spec.McpSchema;

import net.vheerden.archi.mcp.handlers.DiscoveryHandler;
import net.vheerden.archi.mcp.handlers.ElementCreationHandler;
import net.vheerden.archi.mcp.handlers.ElementUpdateHandler;
import net.vheerden.archi.mcp.handlers.ModelQueryHandler;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.handlers.TraversalHandler;
import net.vheerden.archi.mcp.handlers.ViewHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * Pins that every tool publishing an element's {@code documentation} and {@code properties} says
 * when those fields are absent, in the same words — and that the contrast belonging to
 * {@code update-relationship} does not travel with the rule.
 *
 * <h2>The rule being disclosed</h2>
 *
 * <p>{@code DtoMapper.convertToElementDto} maps an empty documentation and an empty property list to
 * null, and the formatter drops null keys, so an element that carries neither reports neither. The
 * collapse is deliberate: a concept's documentation defaults to the empty string and is never null,
 * so without it every element row would carry {@code "documentation": ""}.</p>
 *
 * <h2>Why a parity guard</h2>
 *
 * <p>Four of these surfaces did not merely stay silent — they promised the opposite, listing
 * {@code documentation} and {@code properties} in an unconditional "returns …" enumeration.
 * {@code update-element} was the sharpest: it scoped the third field ("specialization only when
 * set") and left the two beside it reading as guaranteed. {@code get-relationships} was next: one
 * description string documented the omission rule for a relationship and left it unexplained for an
 * element.</p>
 *
 * <p>Read off the live registry, not the source: javac folds a description's concatenation into one
 * constant, so grepping the handler tests the literal rather than the string a client receives. The
 * {@code fields} property descriptions are asserted separately from the tool descriptions, because
 * that is where three of the four false enumerations lived — a rule stated only in the tool
 * description would leave the lie in place exactly where a caller picks the preset.</p>
 */
public class ElementOmissionSurfaceParityTest {

    /**
     * The sentence fragment that carries the rule.
     *
     * <p>It names the element and both fields. A bare {@code "documentation"} substring would pin
     * nothing here: it is already a request-side schema property on the mutation tools and appears
     * in several unrelated clauses on the read tools, so the check would pass over a surface that
     * says nothing about omission at all.</p>
     */
    private static final String OMISSION_RULE =
            "An element reports documentation and properties only when it has them";

    /**
     * The clause that must NOT travel with the rule. True of {@code update-relationship}'s response
     * and false of every element tool: {@code update-element} cannot clear a documentation at all —
     * its reader strips blanks before the command ever sees them, deliberately and in writing — so
     * copying this sentence across would put a claim on the wire the code contradicts.
     */
    private static final String SCALAR_CONTRAST = "empty string rather than omitting";

    /** Every tool that publishes an element's documentation/properties and therefore owes the rule. */
    private static final List<String> OWNING_TOOLS = List.of(
            "get-element", "search-elements", "get-relationships", "get-view-contents",
            "get-or-create-element", "search-and-create", "create-element", "update-element");

    /**
     * The subset whose {@code fields} preset enumerates the element's standard fields by name. The
     * rule has to be in that description too, not only in the tool's.
     */
    private static final List<String> TOOLS_WITH_A_FIELD_LIST = List.of(
            "get-element", "search-elements", "get-relationships");

    @Test
    public void shouldStateTheOmissionRule_onEverySurfaceThatPublishesAnElementsFields() {
        CommandRegistry registry = buildRegistry();
        List<String> silent = new ArrayList<>();

        for (String tool : OWNING_TOOLS) {
            if (!servedSurfaceOf(registry, tool).contains(OMISSION_RULE)) {
                silent.add(tool);
            }
        }

        assertTrue("These tools publish an element's documentation and properties without saying "
                + "when the fields are absent, so the same omission means a documented convention "
                + "on one surface and an unexplained silence — or a broken promise — on another: "
                + silent + ". The wording to reuse is: \"" + OMISSION_RULE + "\"", silent.isEmpty());
    }

    @Test
    public void shouldStateTheOmissionRule_insideTheFieldsPresetThatNamesTheFields() {
        CommandRegistry registry = buildRegistry();
        List<String> silent = new ArrayList<>();

        for (String tool : TOOLS_WITH_A_FIELD_LIST) {
            if (!propertyDescriptionOf(registry, tool, "fields").contains(OMISSION_RULE)) {
                silent.add(tool);
            }
        }

        assertTrue("The 'fields' preset description is where these tools enumerate documentation "
                + "and properties by name, so a caller reading it is told they are returned. The "
                + "qualifier has to sit beside the list, not only in the tool description: "
                + silent, silent.isEmpty());
    }

    /**
     * The absence half. {@code bulk-mutate} is not on the owning list and is not checked here: its
     * description states that an operation's parameters and response fields are governed by the
     * standalone tool of the same name, which is a single-source pointer rather than a silence.
     */
    @Test
    public void shouldNotClaimAClearedScalarSurvives_onAnyElementSurface() {
        CommandRegistry registry = buildRegistry();
        List<String> overclaiming = new ArrayList<>();

        for (String tool : OWNING_TOOLS) {
            if (servedSurfaceOf(registry, tool).contains(SCALAR_CONTRAST)) {
                overclaiming.add(tool);
            }
        }

        assertTrue("These tools promise a cleared documentation comes back as an empty string. "
                + "That is true of update-relationship and of no element tool: update-element's "
                + "reader strips a blank documentation before the command sees it, so an element "
                + "documentation cannot be cleared at all. " + overclaiming, overclaiming.isEmpty());
    }

    /**
     * And the contrast really is on the one surface that earns it, so the absence check above
     * cannot pass merely because the wording drifted out of the tree.
     */
    @Test
    public void shouldKeepTheScalarContrast_onTheRelationshipSurfaceWhereItIsTrue() {
        assertTrue("update-relationship's response does preserve a cleared documentation as an "
                + "empty string — losing that sentence would make the absence check vacuous",
                servedSurfaceOf(buildRegistry(), "update-relationship").contains(SCALAR_CONTRAST));
    }

    // ---- harness ------------------------------------------------------------------------------

    /** Description plus every input-schema property description — all of what a client is served. */
    private String servedSurfaceOf(CommandRegistry registry, String toolName) {
        McpSchema.Tool tool = toolNamed(registry, toolName);
        StringBuilder surface = new StringBuilder(tool.description());
        Map<String, Object> props = tool.inputSchema() == null ? null : tool.inputSchema().properties();
        if (props != null) {
            for (Object value : props.values()) {
                surface.append(' ').append(descriptionOf(value));
            }
        }
        return surface.toString();
    }

    private String propertyDescriptionOf(CommandRegistry registry, String toolName, String property) {
        Map<String, Object> props = toolNamed(registry, toolName).inputSchema().properties();
        Object prop = props.get(property);
        if (prop == null) {
            throw new AssertionError(toolName + " no longer registers a '" + property + "' input "
                    + "property — if the preset was removed this check should go with it, not pass "
                    + "vacuously on its absence.");
        }
        return descriptionOf(prop);
    }

    @SuppressWarnings("unchecked")
    private String descriptionOf(Object schemaProperty) {
        if (!(schemaProperty instanceof Map)) {
            return "";
        }
        Object description = ((Map<String, Object>) schemaProperty).get("description");
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
     * Seven handlers, not the four the relationship guard registers — the element's fields are
     * published across a wider surface than the relationship's.
     */
    private CommandRegistry buildRegistry() {
        CommandRegistry registry = new CommandRegistry();
        ResponseFormatter formatter = new ResponseFormatter();
        new ModelQueryHandler(new BaseTestAccessor(), formatter, registry, null).registerTools();
        new SearchHandler(new BaseTestAccessor(), formatter, registry, null).registerTools();
        new ViewHandler(new BaseTestAccessor(), formatter, registry, null).registerTools();
        new TraversalHandler(new BaseTestAccessor(), formatter, registry, null).registerTools();
        new DiscoveryHandler(new BaseTestAccessor(), formatter, registry, null).registerTools();
        new ElementCreationHandler(new BaseTestAccessor(), formatter, registry, null).registerTools();
        new ElementUpdateHandler(new BaseTestAccessor(), formatter, registry, null).registerTools();
        return registry;
    }
}
