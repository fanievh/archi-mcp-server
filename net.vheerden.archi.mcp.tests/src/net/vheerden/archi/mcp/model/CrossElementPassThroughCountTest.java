package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.ViewPlacementHandler;
import net.vheerden.archi.mcp.handlers.ViewPlacementHandlerTest;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * The charged pass-through count, from the detector that measures it to the two surfaces that
 * publish it.
 *
 * <p>Before this component existed, both handler surfaces read {@code connectionPassThroughs
 * .size()} — a capped list of descriptions mixing the charged cross-element crossings with the
 * unrated self-element ones — as if it were the number the rating is computed on. The two
 * quantities disagree in BOTH directions, and each direction had its own consequence, so each is
 * pinned separately here.</p>
 */
public class CrossElementPassThroughCountTest {

    private static final String ACCESSOR_SOURCE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java";

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    // ---- Fixtures ------------------------------------------------------------------------------

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null,
                0.0, 0.0, 0.0);
    }

    private static double[] p(double x, double y) {
        return new double[] {x, y};
    }

    /**
     * {@code n} connections of the overshoot-and-return shape, each routing through its OWN target
     * and nothing else. Every one is described; none is charged.
     */
    private LayoutAssessmentResult selfElementOnlyView(int n) {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double y = 100 + 300 * i;
            nodes.add(node("selfA" + i, 0, y, 50, 50));
            nodes.add(node("selfB" + i, 100, y, 100, 50));
            connections.add(new AssessmentConnection("self" + i, "selfA" + i, "selfB" + i,
                    List.of(p(25, y + 25), p(350, y + 25), p(150, y + 25)), "", 1));
        }
        return assessor.assess(nodes, connections, false);
    }

    /** {@code n} independent source/blocker/target triples, each one charged cross-element crossing. */
    private LayoutAssessmentResult crossElementView(int n) {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double y = 500 * i;
            nodes.add(node("src" + i, 0, y, 80, 50));
            nodes.add(node("tgt" + i, 400, y, 80, 50));
            nodes.add(node("mid" + i, 180, y, 140, 80));
            connections.add(new AssessmentConnection("c" + i, "src" + i, "tgt" + i,
                    List.of(p(80, y + 25), p(400, y + 25)), "", 1));
        }
        return assessor.assess(nodes, connections, false);
    }

    // ---- The two directions the list size gets wrong --------------------------------------------

    @Test
    public void shouldChargeZero_whenTheDescriptionListOverstatesWithSelfElementEntries() {
        LayoutAssessmentResult result = selfElementOnlyView(3);

        // The precondition that makes this fixture worth anything: without it the pin could pass
        // on an ordinary view where the two quantities happen to agree.
        assertEquals("the fixture must publish three descriptions", 3,
                result.connectionPassThroughs().size());
        assertEquals("the rating must charge none of them", "pass",
                result.ratingBreakdown().get("passThroughs"));

        assertEquals("the published charged count must be the quantity the rating was computed on,"
                        + " which on self-element-only geometry is zero — not the three"
                        + " descriptions beside it", 0, result.crossElementPassThroughCount());
        assertNotEquals("this fixture no longer discriminates: the two quantities agree",
                result.connectionPassThroughs().size(), result.crossElementPassThroughCount());
    }

    @Test
    public void shouldChargeEveryCrossing_whenTheCapUnderstatesTheDescriptionList() {
        LayoutAssessmentResult result = crossElementView(15);

        assertEquals("the description list is capped, so it cannot be the count", 10,
                result.connectionPassThroughs().size());
        assertEquals("the rating charges all fifteen", "poor",
                result.ratingBreakdown().get("passThroughs"));

        assertEquals("the published charged count must be all fifteen crossings, not the ten the"
                        + " capped list managed to describe", 15,
                result.crossElementPassThroughCount());
        assertNotEquals("this fixture no longer discriminates: the two quantities agree",
                result.connectionPassThroughs().size(), result.crossElementPassThroughCount());
    }

    // ---- The sweep and the drill-in must name the same number -----------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void theWholeModelSweepAndTheDrillInRemedyMustNameTheSameCount() throws Exception {
        // Both numbers are read here, in one test, so a future divergence is a red test rather
        // than something a caller discovers by comparing two responses by hand.
        LayoutAssessmentResult result = crossElementView(15);

        String drillIn = null;
        for (String suggestion : result.suggestions()) {
            if (suggestion.contains("do not connect to")) {
                drillIn = suggestion;
            }
        }
        assertNotNull("the drill-in pass-through remedy is no longer emitted, so this test can no"
                + " longer compare the two surfaces: " + result.suggestions(), drillIn);
        assertTrue("the drill-in remedy must open with the charged count: " + drillIn,
                drillIn.startsWith(result.crossElementPassThroughCount() + " connections"));

        int sweepCount = (Integer) sweepEntryFor(result).get("crossElementPassThroughCount");
        assertEquals("the whole-model sweep and the drill-in remedy publish different counts for"
                        + " the same view, and nothing on either surface reconciles them",
                result.crossElementPassThroughCount(), sweepCount);
        assertNotEquals("the sweep is reporting the capped description list's size again",
                result.connectionPassThroughs().size(), sweepCount);
    }

    /** Drives the real all-views sweep over a DTO carrying this result's two pass-through fields. */
    private Map<String, Object> sweepEntryFor(LayoutAssessmentResult result) throws Exception {
        AssessLayoutResultDto legacy = new AssessLayoutResultDto(
                "v-pt", 45, 15, 0, 0, 0, 0.0, 120.0, 90,
                result.overallRating(), result.ratingBreakdown(),
                null, null, result.connectionPassThroughs(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null, 0, null, 0, null, 0, null, null, List.of("s"));
        // Through the WIDEST constructor: every back-compat form defaults this component to zero,
        // so a fixture that skipped this step would assert nothing about the value.
        AssessLayoutResultDto dto = ViewPlacementHandlerTest.withComponent(
                legacy, "crossElementPassThroughCount", result.crossElementPassThroughCount());

        CommandRegistry registry = new CommandRegistry();
        BaseTestAccessor accessor = new BaseTestAccessor() {
            @Override
            public List<ViewDto> getViews(String viewpointFilter) {
                return List.of(new ViewDto("v-pt", "Pass-through View", "Layered", "/"));
            }

            @Override
            public AssessLayoutResultDto assessLayout(String viewId, boolean includeViolatorIds) {
                return dto;
            }
        };
        new ViewPlacementHandler(accessor, new ResponseFormatter(), registry, null).registerTools();
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(t -> t.tool().name().equals("assess-layout")).findFirst().orElseThrow();
        McpSchema.CallToolResult response = spec.callHandler().apply(null,
                McpSchema.CallToolRequest.builder()
                        .name("assess-layout")
                        .arguments(Map.of("scope", "all-views"))
                        .build());
        Map<String, Object> envelope = new ObjectMapper().readValue(
                ((McpSchema.TextContent) response.content().get(0)).text(),
                new TypeReference<Map<String, Object>>() {});
        return (Map<String, Object>) ((Map<String, Object>) envelope.get("result")).get("v-pt");
    }

    // ---- The mapping the pin above stands in for ------------------------------------------------

    @Test
    public void theAccessorMappingMustForwardTheChargedCountNotTheDescriptionListSize() {
        // The sweep pin above builds its DTO by hand, so something has to hold that hand-build to
        // what production actually maps. Scoped to the DTO construction expression itself rather
        // than the file, and read with comments stripped so a javadoc naming the accessor cannot
        // satisfy it.
        String code = PassThroughAndCrowdingRemedyTest.withoutComments(
                PassThroughAndCrowdingRemedyTest.readRepoFile(ACCESSOR_SOURCE));
        int start = code.indexOf("new AssessLayoutResultDto(");
        assertTrue("the accessor no longer builds an AssessLayoutResultDto, so this guard has lost"
                + " its target", start >= 0);
        int end = code.indexOf("\n    }", start);
        assertTrue("could not find the end of the DTO construction expression", end > start);
        String construction = code.substring(start, end);

        assertTrue("the accessor must forward the assessor's own charged count onto the DTO;"
                        + " without it every response reads the back-compat default of zero",
                construction.contains("result.crossElementPassThroughCount()"));
        assertFalse("the accessor must never derive a pass-through count from the capped"
                        + " description list",
                construction.contains("connectionPassThroughs().size()"));
    }
}
