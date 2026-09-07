package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.handlers.ElementCreationHandler;
import net.vheerden.archi.mcp.handlers.ElementUpdateHandler;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.handlers.TraversalHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * Pins that every tool publishing a relationship's {@code properties} says when the field is
 * absent, in the same words.
 *
 * <h2>The rule being disclosed</h2>
 *
 * <p>{@code DtoMapper.convertToRelationshipDto} maps an empty property list to null on both
 * flavours, so {@code @JsonInclude(NON_NULL)} drops the key entirely: a relationship that has no
 * properties, or whose last property a caller just removed, reports nothing about properties at
 * all. That collapse is deliberate. It was pinned by a test added in the same commit that made the
 * neighbouring {@code documentation} field present-and-empty, and the two are contrasted on
 * purpose — a cleared scalar comes back as {@code ""} while an emptied collection comes back
 * absent.</p>
 *
 * <h2>Why a parity guard rather than a single assertion</h2>
 *
 * <p>A rule an agent cannot see is indistinguishable from a bug. {@code update-relationship}
 * defined the omission and the other three tools that publish the same field said nothing, so the
 * same absent key meant a documented convention on one tool and an unexplained silence on the next
 * three — and {@code create-relationship} went further, claiming it returns "the documentation and
 * properties the relationship actually holds after the write" with no mention that a relationship
 * holding none reports none.</p>
 *
 * <p>{@code bulk-mutate} is deliberately excluded. Its description states that an operation's own
 * parameters and response fields are governed by the standalone tool of the same name, which is a
 * single-source pointer rather than a silence; repeating the rule there would be the duplication
 * that pointer exists to prevent.</p>
 *
 * <p>The catalog is stood up from the live registry rather than by reading source, so the assertion
 * is made against the string a client is actually served. No SWT, no EMF, no OSGi — a stub accessor
 * is enough.</p>
 */
public class RelationshipPropertiesOmissionSurfaceParityTest {

    /**
     * The sentence fragment that carries the rule. Taken from {@code update-relationship}, which
     * defined it first, so the four surfaces agree word for word rather than each inventing a
     * paraphrase a reader would have to reconcile.
     */
    private static final String OMISSION_RULE = "properties only when the relationship has any";

    /**
     * The clause that must NOT travel with the rule above. True of {@code update-relationship}'s
     * mutation response and false everywhere else on this list.
     */
    private static final String SCALAR_CONTRAST = "empty string rather than omitting";

    /** Every tool that publishes a relationship's properties and therefore owes the rule. */
    private static final List<String> OWNING_TOOLS = List.of(
            "update-relationship", "get-relationships", "search-relationships",
            "create-relationship");

    @Test
    public void shouldStateThePropertiesOmissionRule_onEverySurfaceThatPublishesTheField() {
        CommandRegistry registry = buildRegistry();
        List<String> silent = new ArrayList<>();

        for (String tool : OWNING_TOOLS) {
            if (!servedDescriptionOf(registry, tool).contains(OMISSION_RULE)) {
                silent.add(tool);
            }
        }

        assertTrue("These tools publish a relationship's properties without saying when the field "
                + "is absent, so the same omission means a documented convention on one surface "
                + "and an unexplained silence on another: " + silent
                + ". The wording to reuse is update-relationship's: \"" + OMISSION_RULE + "\"",
                silent.isEmpty());
    }

    /**
     * The other half, and the reason the rule is copied rather than the sentence carrying it.
     *
     * <p>{@code update-relationship} states the omission beside its contrast — that clearing
     * {@code documentation} returns {@code ""} rather than dropping the key. That contrast is true
     * of {@code update-relationship} and of nothing else here. The read tools run the mapper's read
     * flavour, which normalises empty documentation to null and drops it; and
     * {@code create-relationship}'s fresh-create arm builds its DTO by hand and omits an empty
     * documentation too. Copying the whole sentence to those three would put a claim on the wire
     * that the code contradicts — the failure mode this project has already paid for, arriving by
     * the same route as the fix for it.</p>
     *
     * <p>So the guard is an absence, not a presence: the omission rule travels, the contrast stays
     * where it is true.</p>
     */
    @Test
    public void shouldNotClaimAClearedScalarSurvives_onSurfacesWhereItDoesNot() {
        CommandRegistry registry = buildRegistry();
        List<String> overclaiming = new ArrayList<>();

        for (String tool : OWNING_TOOLS) {
            if ("update-relationship".equals(tool)) {
                continue;
            }
            if (servedDescriptionOf(registry, tool).contains(SCALAR_CONTRAST)) {
                overclaiming.add(tool);
            }
        }

        assertTrue("These tools promise a cleared documentation comes back as an empty string, "
                + "which is false of them: the read flavour normalises empty documentation to null "
                + "and create-relationship's fresh arm omits it. The sentence belongs to "
                + "update-relationship alone: " + overclaiming, overclaiming.isEmpty());
    }

    /**
     * And the sentence really is on the one surface that earns it, so the guard above cannot pass
     * because the wording drifted out of the tree altogether.
     */
    @Test
    public void shouldKeepTheScalarContrast_onTheSurfaceWhereItIsTrue() {
        assertTrue("update-relationship's response really does preserve a cleared documentation as "
                + "an empty string, and it is the only surface here that does — losing the sentence "
                + "would make the absence guard vacuous",
                servedDescriptionOf(buildRegistry(), "update-relationship")
                        .contains(SCALAR_CONTRAST));
    }

    // ---- harness ------------------------------------------------------------------------------

    private String servedDescriptionOf(CommandRegistry registry, String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + toolName))
                .tool()
                .description();
    }

    /**
     * Stands up the live tool catalog for the four handlers that own these descriptions. No SWT, no
     * EMF, no OSGi — a stub accessor is enough.
     */
    private CommandRegistry buildRegistry() {
        CommandRegistry registry = new CommandRegistry();
        ResponseFormatter formatter = new ResponseFormatter();
        new ElementUpdateHandler(new BaseTestAccessor(), formatter, registry, null).registerTools();
        new TraversalHandler(new BaseTestAccessor(), formatter, registry, null).registerTools();
        new SearchHandler(new BaseTestAccessor(), formatter, registry, null).registerTools();
        new ElementCreationHandler(new BaseTestAccessor(), formatter, registry, null)
                .registerTools();
        return registry;
    }
}
