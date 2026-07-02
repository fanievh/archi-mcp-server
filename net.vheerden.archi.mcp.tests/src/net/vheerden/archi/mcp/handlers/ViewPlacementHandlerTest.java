package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.HubSizingSuggestionBuilder;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.ImageParams;
import net.vheerden.archi.mcp.model.StylingParams;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.AdjustViewSpacingResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAssessmentSummaryDto;
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.NudgedElementDto;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.DetectHubElementsResultDto;
import net.vheerden.archi.mcp.response.dto.HubElementEntryDto;
import net.vheerden.archi.mcp.response.dto.LayoutFlatViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.RoutingViolationDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionSpec;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Tests for {@link ViewPlacementHandler}.
 *
 * <p>Uses a StubViewPlacementAccessor that returns canned DTOs,
 * avoiding EMF/GEF dependencies in handler tests.</p>
 */
public class ViewPlacementHandlerTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubViewPlacementAccessor accessor;
    private ViewPlacementHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        accessor = new StubViewPlacementAccessor();
        handler = new ViewPlacementHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Tool registration ----

    @Test
    public void shouldRegisterTwentyFiveTools() {
        // The parent composed-tool change shipped `apply-spacing-recommendations`
        // as the 24th registered tool but did not bump this assertion —
        // silent-failure latent because Eclipse MCP `get_console_output` returns
        // empty stdout for JUnit launches per the SILENT-FAILURE WARNING memory.
        // Surfaced via direct Eclipse JUnit view run; sweeper-cleanup applied here.
        // add-view-reference-to-view bumped 24→25.
        // add-image-to-view bumped 25→26.
        // Archi 5.10 Zest-drop removed compute-layout: 26→25.
        assertEquals(25, registry.getToolSpecifications().size());
    }

    @Test
    public void shouldRegisterAddImageToViewTool() {
        // add-image-to-view registered alongside the
        // existing add-X-to-view siblings (notes / groups / view-references).
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "add-image-to-view".equals(spec.tool().name()));
        assertTrue("add-image-to-view tool should be registered", found);
    }

    @Test
    public void shouldRegisterAddToViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "add-to-view".equals(spec.tool().name()));
        assertTrue("add-to-view tool should be registered", found);
    }

    @Test
    public void shouldRegisterAddConnectionToViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "add-connection-to-view".equals(spec.tool().name()));
        assertTrue("add-connection-to-view tool should be registered", found);
    }

    @Test
    public void shouldRegisterUpdateViewObjectTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "update-view-object".equals(spec.tool().name()));
        assertTrue("update-view-object tool should be registered", found);
    }

    @Test
    public void shouldRegisterUpdateViewConnectionTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "update-view-connection".equals(spec.tool().name()));
        assertTrue("update-view-connection tool should be registered", found);
    }

    @Test
    public void shouldRegisterRemoveFromViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "remove-from-view".equals(spec.tool().name()));
        assertTrue("remove-from-view tool should be registered", found);
    }

    @Test
    public void shouldHaveMutationPrefix_inMutationToolDescriptions() {
        registry.getToolSpecifications().stream()
                .filter(spec -> !"assess-layout".equals(spec.tool().name()))
                .filter(spec -> !"detect-hub-elements".equals(spec.tool().name()))
                .forEach(spec -> {
                    assertTrue(spec.tool().name() + " description should start with [Mutation]",
                            spec.tool().description().startsWith("[Mutation]"));
                });
    }

    @Test
    public void shouldRegisterAssessLayoutTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "assess-layout".equals(spec.tool().name()));
        assertTrue("assess-layout tool should be registered", found);
    }

    @Test
    public void shouldRegisterAutoRouteConnectionsTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "auto-route-connections".equals(spec.tool().name()));
        assertTrue("auto-route-connections tool should be registered", found);
    }

    @Test
    public void autoRouteConnections_descriptionShouldDocumentStructuredWarnings() {
        // The tool description must name the structuredWarnings
        // field, the canonical AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP code, the
        // remediation tool, and the remediationViolatorIds field. The
        // LLM-facing-guidance channel for plugin-specific behaviour is the
        // tool description (not CLAUDE.md).
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "auto-route-connections".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool()
                .description();
        assertTrue("description should name the structuredWarnings field",
                desc.contains("structuredWarnings"));
        assertTrue("description should name the canonical AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP code value",
                desc.contains("AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP"));
        assertTrue("description should frame the recommended iteration (invoke tool BEFORE re-running)",
                desc.contains("BEFORE re-running"));
        assertTrue("description should name the remediationViolatorIds field",
                desc.contains("remediationViolatorIds"));
    }

    @Test
    public void applyElementSpacingRecommendations_descriptionShouldCrossReferenceComposedToolKneeGuard() {
        // The single-axis element sibling MUST cross-reference
        // the composed tool `apply-spacing-recommendations(scope=both)` as the
        // surface with structural knee-enforcement (+80px element / +100px
        // inter-group per-call clamp). The LLM-facing-guidance channel for
        // plugin-specific behaviour is the tool description (not CLAUDE.md).
        // Phrase-presence pin:
        // asserts the cross-reference prose contains "+80px element" and
        // "+100px inter-group" — the "+NNpx" format is unique to the
        // cross-reference block and does NOT appear in the heuristic tier
        // table ("80px"/"100px" without "+"). If the cross-reference values
        // change, update these assertions. Note: does NOT compare against
        // ApplySpacingDecision.ELEMENT_KNEE_LIMIT_PX at runtime; if those
        // constants change, grep for "+80px"/"+100px" in
        // ViewPlacementHandler.java to find the prose to update.
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "apply-element-spacing-recommendations".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool()
                .description();
        assertTrue("description should name the composed tool apply-spacing-recommendations",
                desc.contains("apply-spacing-recommendations"));
        assertTrue("description should mention the knee-guard discipline (case-insensitive)",
                desc.toLowerCase().contains("knee"));
        assertTrue("description should name the scope=both arm of the composed tool",
                desc.contains("scope=both"));
        assertTrue("description should contain '+80px' — AC-3 phrase-presence pin, unique to cross-reference (not in heuristic table)",
                desc.contains("+80px"));
        assertTrue("description should contain '+100px' — AC-3 phrase-presence pin, unique to cross-reference (not in heuristic table)",
                desc.contains("+100px"));
    }

    @Test
    public void applyGroupSpacingRecommendations_descriptionShouldCrossReferenceComposedToolKneeGuard() {
        // Sibling-symmetric with applyElementSpacingRecommendations_... above.
        // Same five substrings (composed tool name, knee, scope=both, +80px,
        // +100px) verified on the inter-group sibling. See element test comment
        // for phrase-presence pin rationale.
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "apply-group-spacing-recommendations".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool()
                .description();
        assertTrue("description should name the composed tool apply-spacing-recommendations",
                desc.contains("apply-spacing-recommendations"));
        assertTrue("description should mention the knee-guard discipline (case-insensitive)",
                desc.toLowerCase().contains("knee"));
        assertTrue("description should name the scope=both arm of the composed tool",
                desc.contains("scope=both"));
        assertTrue("description should contain '+80px' — AC-3 phrase-presence pin, unique to cross-reference (not in heuristic table)",
                desc.contains("+80px"));
        assertTrue("description should contain '+100px' — AC-3 phrase-presence pin, unique to cross-reference (not in heuristic table)",
                desc.contains("+100px"));
    }

    @Test
    public void assessLayout_shouldNotHaveMutationPrefix() {
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "assess-layout".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool()
                .description();
        assertTrue("assess-layout should not start with [Mutation]",
                !desc.startsWith("[Mutation]"));
    }

    @Test
    public void applyElementSpacingRecommendations_descriptionShouldDocumentControlLoopSemantics() {
        // The tool description must surface the new control-loop
        // semantics + termination contract + iterationBudget parameter so
        // an LLM agent can select + invoke the tool correctly without
        // out-of-band documentation. The
        // LLM-facing-guidance channel for plugin-specific behaviour is
        // the tool description (not CLAUDE.md). Phrase-presence pins
        // verify each load-bearing sub-promise. If the wording
        // changes, update these assertions in lockstep with the tool
        // description in ViewPlacementHandler.
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "apply-element-spacing-recommendations".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool()
                .description();
        assertTrue("description should name the embedded control-loop semantics",
                desc.contains("control loop"));
        assertTrue("description should name the density-aware 3-state-termination ordering (Story backlog-control-loop-density-aware-termination AC-15)",
                desc.contains("observe → decide → density-aware "
                        + "3-state-termination"));
        assertTrue("description should name the 2×2 discriminator axes (AC-2)",
                desc.contains("aggregate-trend × spacing-regime-position"));
        assertTrue("description should name the three termination states (AC-1)",
                desc.contains("CONTINUE") && desc.contains("ESCALATE")
                        && desc.contains("PASS-HONEST"));
        assertTrue("description should name the density_floor_reflow_required terminal (AC-6)",
                desc.contains("density_floor_reflow_required"));
        assertTrue("description should surface the no-auto-reflow + consent model (AC-6)",
                desc.contains("NEVER auto-reflows")
                        && desc.contains("never surface + act"));
        assertTrue("description should name the +10/step monotone ladder (AC-15 control-loop semantics)",
                desc.contains("+10/step monotone ladder"));
        assertTrue("description should name the iterationBudget parameter",
                desc.contains("iterationBudget"));
        assertTrue("description should name the aggregate thresholds_met back-off rule per AC-3",
                desc.contains("aggregate thresholds_met"));
        assertTrue("description should EXPLICITLY exclude per-metric monotonicity (AC-3 forbids it)",
                desc.contains("per-metric monotonicity"));
        assertTrue("description should name the terminationReason DTO field",
                desc.contains("terminationReason"));
        assertTrue("description should name the goal_reached termination branch (AC-5 (a))",
                desc.contains("goal_reached_at_iteration_N"));
        assertTrue("description should name the budget_exhausted termination branch (AC-5 (b))",
                desc.contains("budget_exhausted_after_N_iterations"));
        assertTrue("description should name the aggregate_threshold_regressed termination branch (AC-5 (c))",
                desc.contains("aggregate_threshold_regressed_at_iteration_N"));
        assertTrue("description should name the structural_no_change termination branch (AC-5 (d))",
                desc.contains("structural_no_change"));
        assertTrue("description should name the heuristic_already_met termination branch (AC-5 (e))",
                desc.contains("heuristic_already_met_no_change"));
        assertTrue("description should name the dry_run_recommendation_not_applied taxonomy string (6th branch overall — pre-loop dryRun guard)",
                desc.contains("dry_run_recommendation_not_applied"));
        assertTrue("description should name the iteration_apply_failed taxonomy string (7th branch overall — Session 8 Decision-A.1.1=α' patch covers cmd.execute() partial-throw recovery)",
                desc.contains("iteration_apply_failed_at_iteration_N"));
        assertTrue("description should frame the contract as 'ten branches' (7 in-loop + 3 pre-loop guards — branches (i) reroute_degraded and (j) density_precondition_infeasible_reflow_required shipped after AC-6's density_floor_reflow_required branch)",
                desc.contains("ten branches"));
        assertTrue("description should name the iterationCount + appliedDeltas DTO fields",
                desc.contains("iterationCount") && desc.contains("appliedDeltas"));
        assertTrue("description should name the single-undo guarantee per AC-6",
                desc.contains("single undo-stack entry"));
        assertTrue("description should name the NonNotifyingCompoundCommand wrapping mechanism per AC-6",
                desc.contains("NonNotifyingCompoundCommand"));
        assertTrue("description should name the densityFloorDiagnosis DTO field (AC-6)",
                desc.contains("densityFloorDiagnosis"));
    }

    @Test
    public void applyGroupSpacingRecommendations_descriptionShouldDocumentControlLoopSemantics() {
        // Sibling-symmetric with applyElementSpacingRecommendations_... above
        // (sub-promise pin). Same load-bearing substrings on the
        // inter-group sibling. See element test comment for rationale.
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "apply-group-spacing-recommendations".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool()
                .description();
        assertTrue("description should name the embedded control-loop semantics",
                desc.contains("control loop"));
        assertTrue("description should name the density-aware 3-state-termination ordering (Story backlog-control-loop-density-aware-termination AC-15)",
                desc.contains("observe → decide → density-aware "
                        + "3-state-termination"));
        assertTrue("description should name the 2×2 discriminator axes (AC-2)",
                desc.contains("aggregate-trend × spacing-regime-position"));
        assertTrue("description should name the three termination states (AC-1)",
                desc.contains("CONTINUE") && desc.contains("ESCALATE")
                        && desc.contains("PASS-HONEST"));
        assertTrue("description should name the density_floor_reflow_required terminal (AC-6)",
                desc.contains("density_floor_reflow_required"));
        assertTrue("description should surface the no-auto-reflow + consent model (AC-6)",
                desc.contains("NEVER auto-reflows")
                        && desc.contains("never surface + act"));
        assertTrue("description should name the +10/step monotone ladder (AC-15 control-loop semantics)",
                desc.contains("+10/step monotone ladder"));
        assertTrue("description should name the iterationBudget parameter",
                desc.contains("iterationBudget"));
        assertTrue("description should name the aggregate thresholds_met back-off rule per AC-3",
                desc.contains("aggregate thresholds_met"));
        assertTrue("description should EXPLICITLY exclude per-metric monotonicity (AC-3 forbids it)",
                desc.contains("per-metric monotonicity"));
        assertTrue("description should name the terminationReason DTO field",
                desc.contains("terminationReason"));
        assertTrue("description should name the goal_reached termination branch (AC-5 (a))",
                desc.contains("goal_reached_at_iteration_N"));
        assertTrue("description should name the budget_exhausted termination branch (AC-5 (b))",
                desc.contains("budget_exhausted_after_N_iterations"));
        assertTrue("description should name the aggregate_threshold_regressed termination branch (AC-5 (c))",
                desc.contains("aggregate_threshold_regressed_at_iteration_N"));
        assertTrue("description should name the structural_no_change termination branch (AC-5 (d))",
                desc.contains("structural_no_change"));
        assertTrue("description should name the heuristic_already_met termination branch (AC-5 (e))",
                desc.contains("heuristic_already_met_no_change"));
        assertTrue("description should name the dry_run_recommendation_not_applied taxonomy string (6th branch overall — pre-loop dryRun guard)",
                desc.contains("dry_run_recommendation_not_applied"));
        assertTrue("description should name the iteration_apply_failed taxonomy string (7th branch overall — Session 8 Decision-A.1.1=α' patch covers cmd.execute() partial-throw recovery)",
                desc.contains("iteration_apply_failed_at_iteration_N"));
        assertTrue("description should frame the contract as 'ten branches' (7 in-loop + 3 pre-loop guards — branches (i) reroute_degraded and (j) density_precondition_infeasible_reflow_required shipped after AC-6's density_floor_reflow_required branch)",
                desc.contains("ten branches"));
        assertTrue("description should name the iterationCount + appliedDeltas DTO fields",
                desc.contains("iterationCount") && desc.contains("appliedDeltas"));
        assertTrue("description should name the single-undo guarantee per AC-6",
                desc.contains("single undo-stack entry"));
        assertTrue("description should name the NonNotifyingCompoundCommand wrapping mechanism per AC-6",
                desc.contains("NonNotifyingCompoundCommand"));
        assertTrue("description should name the densityFloorDiagnosis DTO field (AC-6)",
                desc.contains("densityFloorDiagnosis"));
    }

    @Test
    public void applySpacingRecommendations_descriptionShouldDocumentTwoArmControlLoopSemantics() {
        // Composer sub-promise pin. The composer surfaces TWO
        // coordinated control loops (element arm first, group arm second per
        // architecture-spec § 1.7 Option A) with PER-ARM terminationReason +
        // iterationCount + appliedDeltas DTO fields. Plus the composer-only
        // promise: the legacy ELEMENT_KNEE_LIMIT_PX / GROUP_KNEE_LIMIT_PX
        // constants are reinterpreted as PER-ITERATION step caps (NOT
        // per-call total caps as in the previous single-shot composer).
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "apply-spacing-recommendations".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool()
                .description();
        assertTrue("description should name TWO coordinated control loops (composer-specific per arch-spec § 1.7)",
                desc.contains("TWO coordinated"));
        assertTrue("description should name the density-aware 3-state-termination ordering (Story backlog-control-loop-density-aware-termination AC-15)",
                desc.contains("observe → decide → density-aware "
                        + "3-state-termination"));
        assertTrue("description should name the 2×2 discriminator axes (AC-2)",
                desc.contains("aggregate-trend × spacing-regime-position"));
        assertTrue("description should name the three termination states (AC-1)",
                desc.contains("CONTINUE") && desc.contains("ESCALATE")
                        && desc.contains("PASS-HONEST"));
        assertTrue("description should name the density_floor_reflow_required terminal (AC-6)",
                desc.contains("density_floor_reflow_required"));
        assertTrue("description should surface the no-auto-reflow + consent model (AC-6)",
                desc.contains("NEVER auto-reflows")
                        && desc.contains("never surface + act"));
        assertTrue("description should name the iterationBudget parameter",
                desc.contains("iterationBudget"));
        assertTrue("description should name the per-iteration step cap reinterpretation (Option α)",
                desc.contains("per-iteration step caps")
                        || desc.contains("PER-ITERATION step caps"));
        assertTrue("description should name the per-arm elementTerminationReason DTO field",
                desc.contains("elementTerminationReason"));
        assertTrue("description should name the per-arm groupTerminationReason DTO field",
                desc.contains("groupTerminationReason"));
        assertTrue("description should name the per-arm elementIterationCount DTO field",
                desc.contains("elementIterationCount"));
        assertTrue("description should name the per-arm groupIterationCount DTO field",
                desc.contains("groupIterationCount"));
        assertTrue("description should name the per-arm elementAppliedDeltas DTO field",
                desc.contains("elementAppliedDeltas"));
        assertTrue("description should name the per-arm groupAppliedDeltas DTO field",
                desc.contains("groupAppliedDeltas"));
        assertTrue("description should name the aggregate thresholds_met back-off rule per AC-3",
                desc.contains("aggregate thresholds_met"));
        assertTrue("description should name the goal_reached termination branch",
                desc.contains("goal_reached_at_iteration_N"));
        assertTrue("description should name the dry_run_recommendation_not_applied taxonomy string (6th branch overall — pre-loop dryRun guard)",
                desc.contains("dry_run_recommendation_not_applied"));
        assertTrue("description should name the iteration_apply_failed taxonomy string (7th branch overall — Session 8 Decision-A.1.1=α' patch covers cmd.execute() partial-throw recovery)",
                desc.contains("iteration_apply_failed_at_iteration_N"));
        assertTrue("description should frame the contract as 'ten branches' (7 in-loop + 3 pre-loop guards — branches (i) reroute_degraded and (j) density_precondition_infeasible_reflow_required shipped after AC-6's density_floor_reflow_required branch)",
                desc.contains("ten branches"));
        assertTrue("description should name the single-undo guarantee per AC-6 (across both arms)",
                desc.contains("single undo-stack entry"));
        assertTrue("description should name the NonNotifyingCompoundCommand wrapping mechanism per AC-6",
                desc.contains("NonNotifyingCompoundCommand"));
        assertTrue("description should name the composer's default budget split (4+4 from 8)",
                desc.contains("4+4"));
        assertTrue("description should name the per-arm density diagnosis DTO fields (AC-6)",
                desc.contains("elementDensityFloorDiagnosis")
                        && desc.contains("groupDensityFloorDiagnosis"));
    }

    // ---- add-to-view tests ----

    @Test
    public void shouldReturnViewObjectDto_whenAddToViewSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1", "x", 100, "y", 200));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        @SuppressWarnings("unchecked")
        Map<String, Object> viewObj = (Map<String, Object>) entity.get("viewObject");
        assertEquals("vo-1", viewObj.get("viewObjectId"));
        assertEquals("e-1", viewObj.get("elementId"));
    }

    @Test
    public void shouldReturnAutoPlacement_whenXYOmitted() throws Exception {
        Map<String, Object> result = callAndParse("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        @SuppressWarnings("unchecked")
        Map<String, Object> viewObj = (Map<String, Object>) entity.get("viewObject");
        // Default auto-placement returns 50, 50
        assertEquals(50, ((Number) viewObj.get("x")).intValue());
        assertEquals(50, ((Number) viewObj.get("y")).intValue());
    }

    @Test
    public void shouldReturnAutoConnections_whenAutoConnectTrue() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            ViewObjectDto vo = new ViewObjectDto("vo-1", eId, "Name", "Type", 50, 50, 120, 55);
            ViewConnectionDto conn = new ViewConnectionDto(
                    "vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);
            return new MutationResult<>(new AddToViewResultDto(vo, List.of(conn)), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("autoConnect", true);
        Map<String, Object> result = callAndParse("add-to-view", args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Object> autoConns = (List<Object>) entity.get("autoConnections");
        assertNotNull(autoConns);
        assertEquals(1, autoConns.size());
    }

    @Test
    public void shouldIncludeCapWarning_whenAutoConnectCapped() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            ViewObjectDto vo = new ViewObjectDto("vo-1", eId, "Name", "Type", 50, 50, 120, 55);
            ViewConnectionDto conn = new ViewConnectionDto(
                    "vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);
            return new MutationResult<>(
                    new AddToViewResultDto(vo, List.of(conn), 5), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("autoConnect", true);
        Map<String, Object> result = callAndParse("add-to-view", args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        boolean hasCapWarning = nextSteps.stream()
                .anyMatch(s -> s.contains("capped at 50") && s.contains("5 additional"));
        assertTrue("Should include cap warning in nextSteps", hasCapWarning);
    }

    @Test
    public void shouldSuggestAutoConnectView_inAddToViewNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1", "x", 100, "y", 200));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Should recommend auto-connect-view",
                nextSteps.stream().anyMatch(s -> s.contains("auto-connect-view")));
        assertTrue("Should mention add-connection-to-view as fallback",
                nextSteps.stream().anyMatch(s -> s.contains("add-connection-to-view")));
    }

    @Test
    public void shouldReturnError_whenViewNotFound() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new ModelAccessException("View not found", ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-to-view",
                Map.of("viewId", "bad", "elementId", "e-1"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void shouldReturnError_whenElementNotFound() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new ModelAccessException("Element not found", ErrorCode.ELEMENT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-to-view",
                Map.of("viewId", "v-1", "elementId", "bad"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("ELEMENT_NOT_FOUND"));
    }

    @Test
    public void shouldReturnError_whenPartialCoordinates() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new ModelAccessException(
                    "Both x and y must be specified together", ErrorCode.INVALID_PARAMETER);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("x", 50);
        // y intentionally omitted

        McpSchema.CallToolResult result = callTool("add-to-view", args);

        assertTrue("Should be error", result.isError());
    }

    @Test
    public void shouldReturnModelNotLoadedError_forAddToView() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-to-view")
                .arguments(Map.of("viewId", "v-1", "elementId", "e-1"))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleAddToView(null, request);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_whenApprovalModeActive() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            ViewObjectDto vo = new ViewObjectDto("vo-1", eId, "Name", "Type", 50, 50, 120, 55);
            ProposalContext ctx = new ProposalContext("prop-1", "Add element to view",
                    Instant.parse("2026-01-01T00:00:00Z"));
            return new MutationResult<>(new AddToViewResultDto(vo, null), null, ctx);
        });

        Map<String, Object> result = callAndParse("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
        assertEquals("prop-1", proposal.get("proposalId"));
    }

    @Test
    public void shouldReturnBatchSeq_whenBatchModeActive() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            ViewObjectDto vo = new ViewObjectDto("vo-1", eId, "Name", "Type", 50, 50, 120, 55);
            return new MutationResult<>(new AddToViewResultDto(vo, null), 3);
        });

        Map<String, Object> result = callAndParse("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> batch = (Map<String, Object>) entity.get("batch");
        assertNotNull(batch);
        assertTrue((Boolean) batch.get("success"));
    }

    // ---- add-group-to-view tests ----

    @Test
    public void shouldRegisterAddGroupToViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "add-group-to-view".equals(spec.tool().name()));
        assertTrue("add-group-to-view tool should be registered", found);
    }

    @Test
    public void shouldAddGroupToView() throws Exception {
        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "My Group",
                        "x", 100, "y", 200, "width", 400, "height", 300));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vg-1", entity.get("viewObjectId"));
        assertEquals("My Group", entity.get("label"));
        assertEquals(100, ((Number) entity.get("x")).intValue());
        assertEquals(200, ((Number) entity.get("y")).intValue());
        assertEquals(400, ((Number) entity.get("width")).intValue());
        assertEquals(300, ((Number) entity.get("height")).intValue());
    }

    @Test
    public void shouldAddGroupWithDefaults() throws Exception {
        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Default Group"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals(300, ((Number) entity.get("width")).intValue());
        assertEquals(200, ((Number) entity.get("height")).intValue());
    }

    @Test
    public void shouldReturnNotFound_whenViewMissing_forAddGroup() throws Exception {
        accessor.setAddGroupToViewBehavior((sid, vId, label, x, y, w, h, pvoId) -> {
            throw new ModelAccessException("View not found", ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-group-to-view",
                Map.of("viewId", "nonexistent", "label", "Test"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void shouldReturnModelNotLoaded_forAddGroup() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-group-to-view")
                .arguments(Map.of("viewId", "v-1", "label", "Test"))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleAddGroupToView(null, request);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forAddGroup_whenApprovalActive() throws Exception {
        accessor.setAddGroupToViewBehavior((sid, vId, label, x, y, w, h, pvoId) -> {
            ViewGroupDto dto = new ViewGroupDto("vg-1", label, 50, 50, 300, 200, null, null);
            ProposalContext ctx = new ProposalContext("p-1", "Add group to view", Instant.now());
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Test Group"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
    }

    @Test
    public void shouldReturnBatchSeq_forAddGroup_whenBatchActive() throws Exception {
        accessor.setAddGroupToViewBehavior((sid, vId, label, x, y, w, h, pvoId) -> {
            ViewGroupDto dto = new ViewGroupDto("vg-1", label, 50, 50, 300, 200, null, null);
            return new MutationResult<>(dto, 3);
        });

        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Test Group"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("Should mention batch", nextSteps.get(0).contains("batch"));
    }

    @Test
    public void shouldIncludeGroupNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Channel Apps"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("Should mention parentViewObjectId",
                nextSteps.stream().anyMatch(s -> s.contains("parentViewObjectId")));
    }

    @Test
    public void shouldSuggestLayoutWithinGroup_inAddGroupNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Channel Apps"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Should recommend layout-within-group",
                nextSteps.stream().anyMatch(s -> s.contains("layout-within-group")));
    }

    // ---- add-note-to-view tests ----

    @Test
    public void shouldRegisterAddNoteToViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "add-note-to-view".equals(spec.tool().name()));
        assertTrue("add-note-to-view tool should be registered", found);
    }

    @Test
    public void shouldAddNoteToView() throws Exception {
        Map<String, Object> result = callAndParse("add-note-to-view",
                Map.of("viewId", "v-1", "content", "Design decision: use REST",
                        "x", 500, "y", 100, "width", 200, "height", 100));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vn-1", entity.get("viewObjectId"));
        assertEquals("Design decision: use REST", entity.get("content"));
        assertEquals(500, ((Number) entity.get("x")).intValue());
        assertEquals(100, ((Number) entity.get("y")).intValue());
        assertEquals(200, ((Number) entity.get("width")).intValue());
        assertEquals(100, ((Number) entity.get("height")).intValue());
    }

    @Test
    public void shouldAddNoteWithDefaults() throws Exception {
        Map<String, Object> result = callAndParse("add-note-to-view",
                Map.of("viewId", "v-1", "content", "A note"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals(185, ((Number) entity.get("width")).intValue());
        assertEquals(80, ((Number) entity.get("height")).intValue());
    }

    @Test
    public void shouldReturnNotFound_whenViewMissing_forAddNote() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, pos, gap, x, y, w, h, pvoId) -> {
            throw new ModelAccessException("View not found", ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-note-to-view",
                Map.of("viewId", "nonexistent", "content", "Test"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void shouldReturnModelNotLoaded_forAddNote() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-note-to-view")
                .arguments(Map.of("viewId", "v-1", "content", "Test"))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleAddNoteToView(null, request);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forAddNote_whenApprovalActive() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, pos, gap, x, y, w, h, pvoId) -> {
            ViewNoteDto dto = new ViewNoteDto("vn-1", content, 50, 50, 185, 80, null);
            ProposalContext ctx = new ProposalContext("p-1", "Add note to view", Instant.now());
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> result = callAndParse("add-note-to-view",
                Map.of("viewId", "v-1", "content", "Test note"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
    }

    @Test
    public void shouldReturnBatchSeq_forAddNote_whenBatchActive() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, pos, gap, x, y, w, h, pvoId) -> {
            ViewNoteDto dto = new ViewNoteDto("vn-1", content, 50, 50, 185, 80, null);
            return new MutationResult<>(dto, 5);
        });

        Map<String, Object> result = callAndParse("add-note-to-view",
                Map.of("viewId", "v-1", "content", "Test note"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("Should mention batch", nextSteps.get(0).contains("batch"));
    }

    @Test
    public void shouldIncludeNoteNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("add-note-to-view",
                Map.of("viewId", "v-1", "content", "Test note"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("Should mention update-view-object",
                nextSteps.stream().anyMatch(s -> s.contains("update-view-object")));
    }

    // ---- add-connection-to-view tests ----

    @Test
    public void shouldReturnConnectionDto_whenAddConnectionSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vc-1", entity.get("viewConnectionId"));
        assertEquals("rel-1", entity.get("relationshipId"));
    }

    @Test
    public void shouldReturnBendpoints_whenProvided() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto(
                    "vc-1", relId, "Serving", src, tgt, bps);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("relationshipId", "rel-1");
        args.put("sourceViewObjectId", "vo-1");
        args.put("targetViewObjectId", "vo-2");
        args.put("bendpoints", List.of(
                Map.of("startX", 10, "startY", 20, "endX", 30, "endY", 40)));

        Map<String, Object> result = callAndParse("add-connection-to-view", args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Object> bps = (List<Object>) entity.get("bendpoints");
        assertNotNull(bps);
        assertEquals(1, bps.size());
    }

    @Test
    public void shouldReturnError_whenRelationshipNotFound() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            throw new ModelAccessException("Not found", ErrorCode.RELATIONSHIP_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "bad",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("RELATIONSHIP_NOT_FOUND"));
    }

    @Test
    public void shouldReturnError_whenViewObjectNotFound() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            throw new ModelAccessException("Not found", ErrorCode.VIEW_OBJECT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "bad", "targetViewObjectId", "vo-2"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_OBJECT_NOT_FOUND"));
    }

    @Test
    public void shouldReturnError_whenRelationshipMismatch() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            throw new ModelAccessException("Mismatch", ErrorCode.RELATIONSHIP_MISMATCH);
        });

        McpSchema.CallToolResult result = callTool("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("RELATIONSHIP_MISMATCH"));
    }

    @Test
    public void shouldReturnError_whenConnectionAlreadyOnView() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            throw new ModelAccessException("Already exists", ErrorCode.CONNECTION_ALREADY_ON_VIEW);
        });

        McpSchema.CallToolResult result = callTool("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("CONNECTION_ALREADY_ON_VIEW"));
    }

    @Test
    public void shouldReturnModelNotLoadedError_forAddConnection() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-connection-to-view")
                .arguments(Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleAddConnectionToView(null, request);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forAddConnection_whenApprovalActive() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto("vc-1", relId, "Serving", src, tgt, null);
            ProposalContext ctx = new ProposalContext("prop-2", "Add connection",
                    Instant.parse("2026-01-01T00:00:00Z"));
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> result = callAndParse("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
        assertEquals("prop-2", proposal.get("proposalId"));
    }

    @Test
    public void shouldReturnMutationError_whenDispatchFails() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new MutationException("Dispatch failed");
        });

        McpSchema.CallToolResult result = callTool("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MUTATION_FAILED"));
    }

    @Test
    public void shouldReturnInternalError_whenUnexpectedExceptionOccurs() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new RuntimeException("Unexpected");
        });

        McpSchema.CallToolResult result = callTool("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INTERNAL_ERROR"));
    }

    // ---- update-view-object tests ----

    @Test
    public void shouldReturnUpdatedDto_whenUpdateViewObjectSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("update-view-object",
                Map.of("viewObjectId", "vo-1", "x", 200, "y", 100, "width", 150, "height", 70));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vo-1", entity.get("viewObjectId"));
        assertEquals(200, ((Number) entity.get("x")).intValue());
        assertEquals(100, ((Number) entity.get("y")).intValue());
    }

    @Test
    public void shouldReturnPartialUpdate_whenOnlyXProvided() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("x", 300);
        Map<String, Object> result = callAndParse("update-view-object", args);

        Map<String, Object> entity = getResult(result);
        assertEquals(300, ((Number) entity.get("x")).intValue());
    }

    @Test
    public void shouldReturnError_whenNoFieldsProvided_forUpdateViewObject() throws Exception {
        accessor.setUpdateViewObjectBehavior((sid, voId, x, y, w, h, txt) -> {
            throw new ModelAccessException(
                    "At least one of x, y, width, height must be provided",
                    ErrorCode.INVALID_PARAMETER);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");

        McpSchema.CallToolResult result = callTool("update-view-object", args);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void shouldReturnError_whenViewObjectNotFound_forUpdate() throws Exception {
        accessor.setUpdateViewObjectBehavior((sid, voId, x, y, w, h, txt) -> {
            throw new ModelAccessException("Not found", ErrorCode.VIEW_OBJECT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("update-view-object",
                Map.of("viewObjectId", "bad", "x", 50));
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_OBJECT_NOT_FOUND"));
    }

    @Test
    public void shouldReturnModelNotLoadedError_forUpdateViewObject() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("update-view-object")
                .arguments(Map.of("viewObjectId", "vo-1", "x", 50))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleUpdateViewObject(null, request);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forUpdateViewObject_whenApprovalActive() throws Exception {
        accessor.setUpdateViewObjectBehavior((sid, voId, x, y, w, h, txt) -> {
            ViewObjectDto dto = new ViewObjectDto(voId, "e-1", "Name", "Type", 200, 100, 150, 70);
            ProposalContext ctx = new ProposalContext("prop-3", "Update bounds",
                    Instant.parse("2026-01-01T00:00:00Z"));
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> result = callAndParse("update-view-object",
                Map.of("viewObjectId", "vo-1", "x", 200));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
        assertEquals("prop-3", proposal.get("proposalId"));
    }

    @Test
    public void shouldReturnBatchSeq_forUpdateViewObject_whenBatchActive() throws Exception {
        accessor.setUpdateViewObjectBehavior((sid, voId, x, y, w, h, txt) -> {
            ViewObjectDto dto = new ViewObjectDto(voId, "e-1", "Name", "Type", 200, 100, 150, 70);
            return new MutationResult<>(dto, 5);
        });

        Map<String, Object> result = callAndParse("update-view-object",
                Map.of("viewObjectId", "vo-1", "x", 200));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> batch = (Map<String, Object>) entity.get("batch");
        assertNotNull(batch);
        assertTrue((Boolean) batch.get("success"));
    }

    // ---- Styling parameter tests ----

    @Test
    public void shouldPassStylingParams_whenUpdateViewObjectWithStylingOnly() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("fillColor", "#FF0000");

        Map<String, Object> result = callAndParse("update-view-object", args);
        Map<String, Object> entity = getResult(result);
        assertNotNull("Styling-only update should succeed", entity);
        assertEquals("vo-1", entity.get("viewObjectId"));

        // Verify styling params were correctly extracted and passed to accessor
        StylingParams captured = ((StubViewPlacementAccessor) accessor).lastUpdateViewObjectStyling;
        assertNotNull("Styling params should be captured", captured);
        assertEquals("#FF0000", captured.fillColor());
        assertNull("lineColor should be null when not provided", captured.lineColor());
    }

    // ---- anchor handler-level flow tests ----

    @Test
    public void shouldThreadAnchorParamsToAccessor_whenAnchorSet() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("anchorTarget", "target-1");
        args.put("anchorEdge", "below");
        args.put("anchorDx", 4);
        args.put("anchorDy", 12);

        Map<String, Object> result = callAndParse("update-view-object", args);
        assertNotNull("anchor-only update should succeed", getResult(result));

        assertEquals("target-1", accessor.lastUpdateViewObjectAnchorTarget);
        assertEquals("below", accessor.lastUpdateViewObjectAnchorEdge);
        assertEquals(Integer.valueOf(4), accessor.lastUpdateViewObjectAnchorDx);
        assertEquals(Integer.valueOf(12), accessor.lastUpdateViewObjectAnchorDy);
    }

    @Test
    public void shouldPassEmptyAnchorTarget_whenClearRequested() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("anchorTarget", "");

        callAndParse("update-view-object", args);

        assertEquals("", accessor.lastUpdateViewObjectAnchorTarget);
    }

    @Test
    public void shouldRejectInvalidAnchorEdge() throws Exception {
        accessor.lastUpdateViewObjectAnchorTarget = "sentinel";
        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("anchorTarget", "target-1");
        args.put("anchorEdge", "diagonal");

        McpSchema.CallToolResult result = callTool("update-view-object", args);

        assertTrue("invalid anchorEdge must produce an error response", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
        assertEquals("accessor must not be called on invalid edge",
                "sentinel", accessor.lastUpdateViewObjectAnchorTarget);
    }

    // ---- labelExpression handler-level flow tests ----

    @Test
    public void shouldPassLabelExpression_whenUpdateViewObjectReceivesParam_AC2() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("labelExpression", "${name}");

        Map<String, Object> result = callAndParse("update-view-object", args);
        Map<String, Object> entity = getResult(result);
        assertNotNull("labelExpression-only update should succeed", entity);

        String captured = accessor.lastUpdateViewObjectLabelExpression;
        assertEquals("Handler must thread labelExpression to the accessor verbatim",
                "${name}", captured);
    }

    @Test
    public void shouldPassEmptyString_whenLabelExpressionClearRequested_AC3() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("labelExpression", "");

        callAndParse("update-view-object", args);

        // Empty string is the clear semantic — must reach the accessor as "" (NOT null)
        // so the accessor can distinguish "clear" from "no change".
        assertEquals("", accessor.lastUpdateViewObjectLabelExpression);
    }

    @Test
    public void shouldNotPassLabelExpression_whenAbsent_AC5() throws Exception {
        // Reset any prior capture before the call.
        accessor.lastUpdateViewObjectLabelExpression = "sentinel-prefilled";

        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("x", 200);  // some other field, so the call validates

        callAndParse("update-view-object", args);

        assertNull("Absent labelExpression key must reach the accessor as null",
                accessor.lastUpdateViewObjectLabelExpression);
    }

    @Test
    public void shouldIncludeLabelExpressionProperty_inUpdateViewObjectSpec_AC10() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "update-view-object".equals(s.tool().name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();

        assertTrue("update-view-object schema should expose labelExpression",
                props.containsKey("labelExpression"));

        @SuppressWarnings("unchecked")
        Map<String, Object> labelExpressionProp = (Map<String, Object>) props.get("labelExpression");
        assertEquals("string", labelExpressionProp.get("type"));
        String desc = (String) labelExpressionProp.get("description");
        assertNotNull("labelExpression must have a description", desc);
        assertTrue("Description must mention ${name}", desc.contains("${name}"));
        assertTrue("Description must mention ${property:KEY}",
                desc.contains("${property:KEY}"));
        assertTrue("Description must explain empty-string-clears semantic",
                desc.toLowerCase().contains("empty"));
    }

    @Test
    public void updateViewObjectToolDescription_mentionsLabelExpression_AC10() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "update-view-object".equals(s.tool().name()))
                .findFirst().orElseThrow();

        String description = spec.tool().description();
        assertTrue("Tool description must mention labelExpression",
                description.contains("labelExpression"));
        // text vs labelExpression distinction must be discoverable from the description.
        assertTrue("Tool description must distinguish text from labelExpression",
                description.contains("literal stored label"));
    }

    @Test
    public void shouldIncludeStylingProperties_inUpdateViewObjectSpec() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "update-view-object".equals(s.tool().name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        assertTrue("Should have fillColor property", props.containsKey("fillColor"));
        assertTrue("Should have lineColor property", props.containsKey("lineColor"));
        assertTrue("Should have fontColor property", props.containsKey("fontColor"));
        assertTrue("Should have opacity property", props.containsKey("opacity"));
        assertTrue("Should have lineWidth property", props.containsKey("lineWidth"));
    }

    @Test
    public void shouldIncludeStylingProperties_inAddToViewSpec() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "add-to-view".equals(s.tool().name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        assertTrue("Should have fillColor property", props.containsKey("fillColor"));
        assertTrue("Should have lineColor property", props.containsKey("lineColor"));
        assertTrue("Should have fontColor property", props.containsKey("fontColor"));
    }

    // ---- group/element styling-surface schema property pins ----

    @Test
    public void addToViewSpec_includesFigureTypeAndTextAlignmentAndVerticalAlignment_AC8() {
        assertStylingSurfaceProperties("add-to-view");
    }

    @Test
    public void addGroupToViewSpec_includesFigureTypeAndTextAlignmentAndVerticalAlignment_AC8() {
        assertStylingSurfaceProperties("add-group-to-view");
    }

    @Test
    public void addNoteToViewSpec_includesFigureTypeAndTextAlignmentAndVerticalAlignment_AC8() {
        // Notes silently ignore figureType at apply-time, but the schema property still appears
        // (uniform extract path via the shared addStylingProperties helper). The tool description
        // tells the LLM that figureType is ignored on notes.
        assertStylingSurfaceProperties("add-note-to-view");
    }

    @Test
    public void updateViewObjectSpec_includesFigureTypeAndTextAlignmentAndVerticalAlignment_AC8() {
        assertStylingSurfaceProperties("update-view-object");
    }

    @SuppressWarnings("unchecked")
    private void assertStylingSurfaceProperties(String toolName) {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> toolName.equals(s.tool().name()))
                .findFirst().orElseThrow();
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        assertTrue(toolName + " should expose figureType property", props.containsKey("figureType"));
        assertTrue(toolName + " should expose textAlignment property", props.containsKey("textAlignment"));
        assertTrue(toolName + " should expose verticalTextAlignment property", props.containsKey("verticalTextAlignment"));

        Map<String, Object> figureType = (Map<String, Object>) props.get("figureType");
        assertEquals("string", figureType.get("type"));
        List<?> figureEnum = (List<?>) figureType.get("enum");
        assertTrue("figureType enum should include 'rectangular'", figureEnum.contains("rectangular"));
        assertTrue("figureType enum should include 'tabbed'", figureEnum.contains("tabbed"));
        assertNotNull("figureType should have non-empty description", figureType.get("description"));
        assertFalse("figureType description should not be empty",
                ((String) figureType.get("description")).isEmpty());

        Map<String, Object> textAlignment = (Map<String, Object>) props.get("textAlignment");
        List<?> textEnum = (List<?>) textAlignment.get("enum");
        assertTrue(textEnum.contains("left"));
        assertTrue(textEnum.contains("centre"));
        assertTrue(textEnum.contains("center"));
        assertTrue(textEnum.contains("right"));

        Map<String, Object> verticalTextAlignment = (Map<String, Object>) props.get("verticalTextAlignment");
        List<?> verticalEnum = (List<?>) verticalTextAlignment.get("enum");
        assertTrue(verticalEnum.contains("top"));
        assertTrue(verticalEnum.contains("centre"));
        assertTrue(verticalEnum.contains("center"));
        assertTrue(verticalEnum.contains("bottom"));
    }

    @Test
    public void addToViewToolDescription_mentionsAllThreeNewParams_AC9() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "add-to-view".equals(s.tool().name()))
                .map(s -> s.tool().description())
                .findFirst().orElseThrow();
        assertTrue("description mentions figureType", desc.contains("figureType"));
        assertTrue("description mentions textAlignment", desc.contains("textAlignment"));
        assertTrue("description mentions verticalTextAlignment", desc.contains("verticalTextAlignment"));
    }

    @Test
    public void addGroupToViewToolDescription_mentionsAllThreeNewParams_AC9() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "add-group-to-view".equals(s.tool().name()))
                .map(s -> s.tool().description())
                .findFirst().orElseThrow();
        assertTrue(desc.contains("figureType"));
        assertTrue(desc.contains("textAlignment"));
        assertTrue(desc.contains("verticalTextAlignment"));
    }

    @Test
    public void updateViewObjectToolDescription_mentionsAllThreeNewParams_AC9() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "update-view-object".equals(s.tool().name()))
                .map(s -> s.tool().description())
                .findFirst().orElseThrow();
        assertTrue(desc.contains("figureType"));
        assertTrue(desc.contains("textAlignment"));
        assertTrue(desc.contains("verticalTextAlignment"));
    }

    @Test
    public void shouldIncludeConnectionStylingProperties_inUpdateViewConnectionSpec() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "update-view-connection".equals(s.tool().name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        assertTrue("Should have lineColor property", props.containsKey("lineColor"));
        assertTrue("Should have fontColor property", props.containsKey("fontColor"));
        assertTrue("Should have lineWidth property", props.containsKey("lineWidth"));
        assertFalse("Should NOT have fillColor property", props.containsKey("fillColor"));
        assertFalse("Should NOT have opacity property", props.containsKey("opacity"));
    }

    @Test
    public void shouldPassStylingParams_whenAddToViewWithStyling() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("fillColor", "#00FF00");
        args.put("opacity", 128);

        Map<String, Object> result = callAndParse("add-to-view", args);
        Map<String, Object> entity = getResult(result);
        assertNotNull("add-to-view with styling should succeed", entity);

        // Verify styling params were correctly extracted and passed to accessor
        StylingParams captured = ((StubViewPlacementAccessor) accessor).lastAddToViewStyling;
        assertNotNull("Styling params should be captured", captured);
        assertEquals("#00FF00", captured.fillColor());
        assertEquals(Integer.valueOf(128), captured.opacity());
        assertNull("lineColor should be null when not provided", captured.lineColor());
    }

    @Test
    public void shouldPassRecedeOptOut_whenAddToViewRecedeFalse() throws Exception {
        // A lone recede:false (no other styling) must still reach the accessor — it cannot be
        // dropped as "no styling", or the container-recession opt-out would be silently ignored.
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("recede", false);

        Map<String, Object> result = callAndParse("add-to-view", args);
        assertNotNull("add-to-view with recede opt-out should succeed", getResult(result));

        StylingParams captured = ((StubViewPlacementAccessor) accessor).lastAddToViewStyling;
        assertNotNull("a lone recede:false must produce a non-null StylingParams", captured);
        assertEquals("recede opt-out carried to the accessor", Boolean.FALSE, captured.recede());
        assertNull("no fill styling was provided", captured.fillColor());
    }

    @Test
    public void shouldPassRecedeOptOut_whenAddGroupToViewRecedeFalse() throws Exception {
        // Symmetric to the add-to-view opt-out: a lone recede:false on add-group-to-view must
        // reach the accessor (the bulk and single paths share extractStylingParams, but the
        // group handler branch is wired independently).
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("label", "My Group");
        args.put("recede", false);

        Map<String, Object> result = callAndParse("add-group-to-view", args);
        assertNotNull("add-group-to-view with recede opt-out should succeed", getResult(result));

        StylingParams captured = ((StubViewPlacementAccessor) accessor).lastAddGroupToViewStyling;
        assertNotNull("a lone recede:false must produce a non-null StylingParams", captured);
        assertEquals("recede opt-out carried to the accessor", Boolean.FALSE, captured.recede());
        assertNull("no fill styling was provided", captured.fillColor());
    }

    @Test
    public void recedeDefaultsToNull_whenAddToViewOmitsIt() throws Exception {
        // Absent recede = default auto-recede (null), carried only when other styling is present.
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("fillColor", "#00FF00");

        callAndParse("add-to-view", args);
        StylingParams captured = ((StubViewPlacementAccessor) accessor).lastAddToViewStyling;
        assertNotNull(captured);
        assertNull("recede defaults to null (auto-recede) when omitted", captured.recede());
    }

    @Test
    public void addToViewSpec_includesRecedeProperty() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "add-to-view".equals(s.tool().name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        assertTrue("add-to-view should expose the recede opt-out property",
                props.containsKey("recede"));
    }

    @Test
    public void addGroupToViewSpec_includesRecedeProperty() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "add-group-to-view".equals(s.tool().name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        assertTrue("add-group-to-view should expose the recede opt-out property",
                props.containsKey("recede"));
    }

    @Test
    public void shouldPassStylingParams_whenUpdateViewConnectionWithStyling() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "conn-1");
        args.put("lineColor", "#0000FF");
        args.put("lineWidth", 2);

        Map<String, Object> result = callAndParse("update-view-connection", args);
        Map<String, Object> entity = getResult(result);
        assertNotNull("update-view-connection with styling should succeed", entity);

        // Verify styling params were correctly extracted and passed to accessor
        StylingParams captured = ((StubViewPlacementAccessor) accessor).lastUpdateViewConnectionStyling;
        assertNotNull("Connection styling params should be captured", captured);
        assertEquals("#0000FF", captured.lineColor());
        assertEquals(Integer.valueOf(2), captured.lineWidth());
        assertNull("fillColor should be null for connections", captured.fillColor());
    }

    // ---- update-view-connection tests ----

    @Test
    public void shouldReturnUpdatedDto_whenUpdateConnectionSucceeds() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("bendpoints", List.of(
                Map.of("startX", 60, "startY", 0, "endX", -60, "endY", 0)));

        Map<String, Object> result = callAndParse("update-view-connection", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vc-1", entity.get("viewConnectionId"));
    }

    @Test
    public void shouldClearBendpoints_whenEmptyArrayProvided() throws Exception {
        accessor.setUpdateViewConnectionBehavior((sid, vcId, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto(
                    vcId, "rel-1", "Serving", "vo-1", "vo-2", List.of());
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("bendpoints", List.of());

        Map<String, Object> result = callAndParse("update-view-connection", args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Object> bps = (List<Object>) entity.get("bendpoints");
        assertNotNull(bps);
        assertEquals(0, bps.size());
    }

    @Test
    public void shouldReturnError_whenConnectionNotFound_forUpdate() throws Exception {
        accessor.setUpdateViewConnectionBehavior((sid, vcId, bps, absBps) -> {
            throw new ModelAccessException("Not found", ErrorCode.VIEW_OBJECT_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "bad");
        args.put("bendpoints", List.of());

        McpSchema.CallToolResult result = callTool("update-view-connection", args);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_OBJECT_NOT_FOUND"));
    }

    @Test
    public void shouldReturnModelNotLoadedError_forUpdateConnection() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("bendpoints", List.of());

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("update-view-connection")
                .arguments(args)
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleUpdateViewConnection(null, request);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forUpdateConnection_whenApprovalActive() throws Exception {
        accessor.setUpdateViewConnectionBehavior((sid, vcId, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto(
                    vcId, "rel-1", "Serving", "vo-1", "vo-2", bps);
            ProposalContext ctx = new ProposalContext("prop-4", "Update bendpoints",
                    Instant.parse("2026-01-01T00:00:00Z"));
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("bendpoints", List.of());

        Map<String, Object> result = callAndParse("update-view-connection", args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
        assertEquals("prop-4", proposal.get("proposalId"));
    }

    @Test
    public void shouldReturnError_whenInvalidLabelPosition() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("labelPosition", "bogus");

        McpSchema.CallToolResult result = callTool("update-view-connection", args);
        assertTrue("Should be error for invalid labelPosition", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("Error should mention invalid labelPosition",
                content.contains("Invalid labelPosition"));
    }

    // ---- absolute bendpoints tests ----

    @Test
    public void shouldAcceptAbsoluteBendpoints_forAddConnection() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            assertNull("relative bendpoints should be null when absolute provided", bps);
            assertNotNull("absolute bendpoints should be forwarded", absBps);
            assertEquals(1, absBps.size());
            assertEquals(300, absBps.get(0).x());
            assertEquals(150, absBps.get(0).y());
            ViewConnectionDto dto = new ViewConnectionDto(
                    "vc-1", relId, "Serving", src, tgt, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("relationshipId", "rel-1");
        args.put("sourceViewObjectId", "vo-1");
        args.put("targetViewObjectId", "vo-2");
        args.put("absoluteBendpoints", List.of(
                Map.of("x", 300, "y", 150)));

        Map<String, Object> result = callAndParse("add-connection-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vc-1", entity.get("viewConnectionId"));
    }

    @Test
    public void shouldAcceptAbsoluteBendpoints_forUpdateConnection() throws Exception {
        accessor.setUpdateViewConnectionBehavior((sid, vcId, bps, absBps) -> {
            assertNull("relative bendpoints should be null when absolute provided", bps);
            assertNotNull("absolute bendpoints should be forwarded", absBps);
            assertEquals(1, absBps.size());
            assertEquals(300, absBps.get(0).x());
            assertEquals(150, absBps.get(0).y());
            ViewConnectionDto dto = new ViewConnectionDto(
                    vcId, "rel-1", "Serving", "vo-1", "vo-2", null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("absoluteBendpoints", List.of(
                Map.of("x", 300, "y", 150)));

        Map<String, Object> result = callAndParse("update-view-connection", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vc-1", entity.get("viewConnectionId"));
    }

    @Test
    public void shouldRejectBothFormats_forAddConnection() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("relationshipId", "rel-1");
        args.put("sourceViewObjectId", "vo-1");
        args.put("targetViewObjectId", "vo-2");
        args.put("bendpoints", List.of(
                Map.of("startX", 10, "startY", 20, "endX", 30, "endY", 40)));
        args.put("absoluteBendpoints", List.of(
                Map.of("x", 300, "y", 150)));

        McpSchema.CallToolResult result = callTool("add-connection-to-view", args);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void shouldRejectBothFormats_forUpdateConnection() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("bendpoints", List.of(
                Map.of("startX", 10, "startY", 20, "endX", 30, "endY", 40)));
        args.put("absoluteBendpoints", List.of(
                Map.of("x", 300, "y", 150)));

        McpSchema.CallToolResult result = callTool("update-view-connection", args);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void shouldClearBendpoints_whenNeitherFormatProvided() throws Exception {
        accessor.setUpdateViewConnectionBehavior((sid, vcId, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto(
                    vcId, "rel-1", "Serving", "vo-1", "vo-2", List.of());
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");

        Map<String, Object> result = callAndParse("update-view-connection", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
    }

    @Test
    public void shouldStillAcceptRelativeBendpoints_forBackwardsCompat() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto(
                    "vc-1", relId, "Serving", src, tgt, bps);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("relationshipId", "rel-1");
        args.put("sourceViewObjectId", "vo-1");
        args.put("targetViewObjectId", "vo-2");
        args.put("bendpoints", List.of(
                Map.of("startX", 10, "startY", 20, "endX", 30, "endY", 40)));

        Map<String, Object> result = callAndParse("add-connection-to-view", args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Object> bps = (List<Object>) entity.get("bendpoints");
        assertNotNull(bps);
        assertEquals(1, bps.size());
    }

    // ---- remove-from-view tests ----

    @Test
    public void shouldReturnDto_whenRemoveElementSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "vo-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vo-1", entity.get("removedObjectId"));
        assertEquals("viewObject", entity.get("removedObjectType"));
    }

    @Test
    public void shouldReturnCascadeIds_whenRemovingElementWithConnections() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    voId, "viewObject", List.of("vc-1", "vc-2"));
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> result = callAndParse("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "vo-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<String> cascadeIds = (List<String>) entity.get("cascadeRemovedConnectionIds");
        assertNotNull(cascadeIds);
        assertEquals(2, cascadeIds.size());
    }

    @Test
    public void shouldReturnDto_whenRemoveConnectionSucceeds() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    voId, "viewConnection", null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> result = callAndParse("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "vc-1"));

        Map<String, Object> entity = getResult(result);
        assertEquals("viewConnection", entity.get("removedObjectType"));
        assertNull(entity.get("cascadeRemovedConnectionIds"));
    }

    @Test
    public void shouldReturnError_whenViewNotFound_forRemove() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            throw new ModelAccessException("View not found", ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("remove-from-view",
                Map.of("viewId", "bad", "viewObjectId", "vo-1"));
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void shouldReturnError_whenViewObjectNotFound_forRemove() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            throw new ModelAccessException("Not found", ErrorCode.VIEW_OBJECT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "bad"));
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_OBJECT_NOT_FOUND"));
    }

    @Test
    public void shouldReturnModelNotLoadedError_forRemoveFromView() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("remove-from-view")
                .arguments(Map.of("viewId", "v-1", "viewObjectId", "vo-1"))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleRemoveFromView(null, request);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forRemoveFromView_whenApprovalActive() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    voId, "viewObject", null);
            ProposalContext ctx = new ProposalContext("prop-5", "Remove from view",
                    Instant.parse("2026-01-01T00:00:00Z"));
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> result = callAndParse("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "vo-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
        assertEquals("prop-5", proposal.get("proposalId"));
    }

    @Test
    public void shouldReturnBatchSeq_forRemoveFromView_whenBatchActive() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    voId, "viewObject", null);
            return new MutationResult<>(dto, 7);
        });

        Map<String, Object> result = callAndParse("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "vo-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> batch = (Map<String, Object>) entity.get("batch");
        assertNotNull(batch);
        assertTrue((Boolean) batch.get("success"));
    }

    // ---- clear-view tests ----

    @Test
    public void shouldReturnDto_whenClearViewSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("clear-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals("Test View", entity.get("viewName"));
        assertEquals(3, entity.get("elementsRemoved"));
        assertEquals(1, entity.get("connectionsRemoved"));
    }

    @Test
    public void shouldReturnSuccessForEmptyView() throws Exception {
        accessor.setClearViewBehavior((sid, vId) -> {
            ClearViewResultDto dto = new ClearViewResultDto(vId, "Empty View", 0, 0, 0);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> result = callAndParse("clear-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertEquals(0, entity.get("elementsRemoved"));
        assertEquals(0, entity.get("connectionsRemoved"));
    }

    @Test
    public void shouldRequireViewId_forClearView() throws Exception {
        McpSchema.CallToolResult result = callTool("clear-view", Map.of());
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void shouldHandleModelAccessException_forClearView() throws Exception {
        accessor.setClearViewBehavior((sid, vId) -> {
            throw new ModelAccessException("View not found", ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("clear-view",
                Map.of("viewId", "bad"));
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    // ---- apply-positions tests ----

    @Test
    public void applyViewLayout_shouldParsePositionsAndCallAccessor() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("positions", List.of(
                Map.of("viewObjectId", "vo-1", "x", 100, "y", 200),
                Map.of("viewObjectId", "vo-2", "x", 300, "y", 200, "width", 150, "height", 70)));

        Map<String, Object> result = callAndParse("apply-positions", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals(2, ((Number) entity.get("positionsUpdated")).intValue());
        assertEquals(0, ((Number) entity.get("connectionsUpdated")).intValue());
        assertEquals(2, ((Number) entity.get("totalOperations")).intValue());
    }

    @Test
    public void applyViewLayout_shouldParseConnectionsWithAbsoluteBendpoints() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("connections", List.of(
                Map.of("viewConnectionId", "vc-1",
                        "absoluteBendpoints", List.of(
                                Map.of("x", 150, "y", 100),
                                Map.of("x", 250, "y", 100)))));

        Map<String, Object> result = callAndParse("apply-positions", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals(0, ((Number) entity.get("positionsUpdated")).intValue());
        assertEquals(1, ((Number) entity.get("connectionsUpdated")).intValue());
    }

    @Test
    public void applyViewLayout_shouldParseConnectionsWithRelativeBendpoints() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("connections", List.of(
                Map.of("viewConnectionId", "vc-1",
                        "bendpoints", List.of(
                                Map.of("startX", 0, "startY", -50, "endX", 0, "endY", -50)))));

        Map<String, Object> result = callAndParse("apply-positions", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals(1, ((Number) entity.get("connectionsUpdated")).intValue());
    }

    @Test
    public void applyViewLayout_shouldRequireViewId() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("positions", List.of(Map.of("viewObjectId", "vo-1", "x", 100)));

        McpSchema.CallToolResult result = callTool("apply-positions", args);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void applyViewLayout_shouldIncludeNextSteps() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("positions", List.of(Map.of("viewObjectId", "vo-1", "x", 100, "y", 200)));

        Map<String, Object> result = callAndParse("apply-positions", args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-view-contents")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("export-view")));
    }

    @Test
    public void applyViewLayout_shouldRejectMutuallyExclusiveBendpoints() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("connections", List.of(
                Map.of("viewConnectionId", "vc-1",
                        "bendpoints", List.of(
                                Map.of("startX", 0, "startY", 0, "endX", 0, "endY", 0)),
                        "absoluteBendpoints", List.of(
                                Map.of("x", 100, "y", 100)))));

        McpSchema.CallToolResult result = callTool("apply-positions", args);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void applyViewLayout_shouldHandleApprovalMode() throws Exception {
        accessor.setApplyViewLayoutBehavior((sid, vId, pos, conns, desc) -> {
            int posCount = (pos != null) ? pos.size() : 0;
            int connCount = (conns != null) ? conns.size() : 0;
            ApplyViewLayoutResultDto dto = new ApplyViewLayoutResultDto(
                    vId, posCount, connCount, posCount + connCount);
            ProposalContext ctx = new ProposalContext(
                    "p-layout-1", "View layout ready for application.", Instant.now());
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("positions", List.of(Map.of("viewObjectId", "vo-1", "x", 100, "y", 200)));

        Map<String, Object> result = callAndParse("apply-positions", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal info", entity.get("proposal"));
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-layout-1", proposal.get("proposalId"));
        assertEquals("pending", proposal.get("status"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
    }

    @Test
    public void applyViewLayout_shouldHandleBatchMode() throws Exception {
        accessor.setApplyViewLayoutBehavior((sid, vId, pos, conns, desc) -> {
            int posCount = (pos != null) ? pos.size() : 0;
            int connCount = (conns != null) ? conns.size() : 0;
            ApplyViewLayoutResultDto dto = new ApplyViewLayoutResultDto(
                    vId, posCount, connCount, posCount + connCount);
            return new MutationResult<>(dto, 5);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("positions", List.of(Map.of("viewObjectId", "vo-1", "x", 100, "y", 200)));

        Map<String, Object> result = callAndParse("apply-positions", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have batch info", entity.get("batch"));
    }

    // ---- detect-hub-elements ----

    @Test
    public void shouldRegisterDetectHubElementsTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "detect-hub-elements".equals(spec.tool().name()));
        assertTrue("detect-hub-elements tool should be registered", found);
    }

    @Test
    public void detectHubElements_shouldReturnSortedElements() throws Exception {
        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals(5, entity.get("totalElements"));
        assertEquals(8, entity.get("totalConnections"));
        assertEquals(3.2, entity.get("averageConnectionCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) entity.get("elements");
        assertNotNull(elements);
        assertEquals(3, elements.size());

        // Verify sorted descending by connectionCount
        assertEquals(8, elements.get(0).get("connectionCount"));
        assertEquals(4, elements.get(1).get("connectionCount"));
        assertEquals(2, elements.get(2).get("connectionCount"));

        // Verify first element fields
        assertEquals("vo-1", elements.get(0).get("viewObjectId"));
        assertEquals("e-1", elements.get(0).get("elementId"));
        assertEquals("API Gateway", elements.get(0).get("elementName"));
        assertEquals("ApplicationComponent", elements.get(0).get("elementType"));
        assertEquals(120, elements.get(0).get("width"));
        assertEquals(55, elements.get(0).get("height"));
    }

    @Test
    public void detectHubElements_shouldIncludeSuggestionsForHubs() throws Exception {
        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);

        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) entity.get("suggestions");
        assertNotNull("Should have suggestions for hub element", suggestions);
        assertEquals(1, suggestions.size());
        assertTrue(suggestions.get(0).contains("API Gateway"));
        assertTrue(suggestions.get(0).contains("8 connections"));
    }

    @Test
    public void detectHubElements_shouldRequireViewId() throws Exception {
        McpSchema.CallToolResult result = callTool("detect-hub-elements",
                new HashMap<>());

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void detectHubElements_shouldIncludeNextStepsWithHubs() throws Exception {
        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("update-view-object")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("auto-route-connections")));
    }

    @Test
    public void detectHubElements_shouldReturnEmptyForEmptyView() throws Exception {
        accessor.setDetectHubElementsBehavior(vId ->
                new DetectHubElementsResultDto(vId, 0, 0, 0.0, List.of(), null));

        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertEquals(0, entity.get("totalElements"));
        assertEquals(0, entity.get("totalConnections"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) entity.get("elements");
        assertNotNull(elements);
        assertTrue(elements.isEmpty());
    }

    @Test
    public void detectHubElements_shouldOmitSuggestionsWhenNoHubs() throws Exception {
        accessor.setDetectHubElementsBehavior(vId ->
                new DetectHubElementsResultDto(vId, 3, 4, 2.0,
                        List.of(new HubElementEntryDto("vo-1", "e-1", "Comp A",
                                "ApplicationComponent", 3, 120, 55, 0)),
                        null));

        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertNull("suggestions should be null when no hubs", entity.get("suggestions"));
    }

    @Test
    public void detectHubElements_shouldReturnNextStepsForNoHubs() throws Exception {
        accessor.setDetectHubElementsBehavior(vId ->
                new DetectHubElementsResultDto(vId, 3, 4, 2.0,
                        List.of(new HubElementEntryDto("vo-1", "e-1", "Comp A",
                                "ApplicationComponent", 3, 120, 55, 0)),
                        null));

        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("No hub elements detected")));
    }

    @Test
    public void detectHubElements_shouldHandleNoModelLoaded() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);
        noModelHandler.registerTools();

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("detect-hub-elements")
                .arguments(Map.of("viewId", "v-1"))
                .build();
        McpSchema.CallToolResult result = noModelHandler.handleDetectHubElements(null, request);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void detectHubElements_shouldIncludeBoth1DAnd2DForLargeHub() throws Exception {
        // Large-hub case (>LARGE_HUB_THRESHOLD): suggestions list has BOTH the
        // existing 1D-or-1D entry AND the new 2D entry. Exercises helper-class
        // extraction at the integration layer.
        accessor.setDetectHubElementsBehavior(vId -> {
            List<HubElementEntryDto> entries = List.of(new HubElementEntryDto(
                    "vo-large", "e-large", "Large Hub", "ApplicationComponent",
                    14, 200, 180, 0));
            return new DetectHubElementsResultDto(vId, 1, 14, 14.0, entries,
                    HubSizingSuggestionBuilder.buildSuggestions(entries));
        });

        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) entity.get("suggestions");
        assertNotNull("Should have suggestions for large-fan-out hub", suggestions);
        assertEquals("Large hub (14 conns) should yield two suggestions",
                2, suggestions.size());
        assertTrue("First entry should be the existing 1D-or-1D format",
                suggestions.get(0).contains("for vertical layouts"));
        assertTrue("Second entry should be the new 2D recommendation",
                suggestions.get(1).contains("Consider 2D resize"));
        assertTrue("2D entry should surface 'connections per edge' owner-perception text",
                suggestions.get(1).contains("connections per edge"));
    }

    @Test
    public void detectHubElements_shouldOmit2DForModerateHub() throws Exception {
        // Negative-branch coverage at the integration layer: 8 conns is in the
        // 7..LARGE_HUB_THRESHOLD branch — only the existing 1D-or-1D entry
        // should appear; no 2D recommendation.
        accessor.setDetectHubElementsBehavior(vId -> {
            List<HubElementEntryDto> entries = List.of(new HubElementEntryDto(
                    "vo-mid", "e-mid", "Moderate Hub", "ApplicationComponent",
                    8, 160, 120, 0));
            return new DetectHubElementsResultDto(vId, 1, 8, 8.0, entries,
                    HubSizingSuggestionBuilder.buildSuggestions(entries));
        });

        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) entity.get("suggestions");
        assertNotNull("Should have one suggestion for moderate hub", suggestions);
        assertEquals("Moderate hub (8 conns) should yield exactly one suggestion",
                1, suggestions.size());
        assertFalse("Moderate hub should NOT trip the 2D branch",
                suggestions.get(0).contains("Consider 2D resize"));
    }

    // ---- detect-hub-elements label-aware sizing ----

    @Test
    public void detectHubElements_shouldOmitMaxLabelWidthWhenZero() throws Exception {
        // Default stub has maxLabelWidth=0 for all entries
        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) entity.get("elements");
        assertNotNull(elements);
        // maxLabelWidth should be omitted when 0 (NON_DEFAULT serialization)
        assertNull("maxLabelWidth should be omitted when 0",
                elements.get(0).get("maxLabelWidth"));
    }

    @Test
    public void detectHubElements_shouldIncludeMaxLabelWidthWhenNonZero() throws Exception {
        accessor.setDetectHubElementsBehavior(vId ->
                new DetectHubElementsResultDto(vId, 2, 5, 2.5,
                        List.of(new HubElementEntryDto("vo-1", "e-1", "API Gateway",
                                "ApplicationComponent", 8, 120, 55, 154)),
                        List.of("test suggestion")));

        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) entity.get("elements");
        assertEquals(154, elements.get(0).get("maxLabelWidth"));
    }

    @Test
    public void detectHubElements_shouldIncludeLabelAwareSuggestion() throws Exception {
        // maxLabelWidth=250 + currentWidth=120 = 370 > connectionBasedWidth(120 + 15*2 = 150)
        accessor.setDetectHubElementsBehavior(vId ->
                new DetectHubElementsResultDto(vId, 2, 10, 5.0,
                        List.of(new HubElementEntryDto("vo-1", "e-1", "Hub Element",
                                "ApplicationComponent", 8, 120, 55, 250)),
                        List.of("Element 'Hub Element' has 8 connections (hub threshold: 6). "
                                + "Consider increasing height to 85px (55 + 15 \u00d7 2) for horizontal layouts, "
                                + "or width to 370px (120 + 15 \u00d7 2) for vertical layouts. "
                                + "Label-adjusted width: 370px (longest label: 250px).")));

        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) entity.get("suggestions");
        assertNotNull(suggestions);
        assertTrue("Should contain label-adjusted text",
                suggestions.get(0).contains("Label-adjusted width"));
        assertTrue("Should contain longest label info",
                suggestions.get(0).contains("longest label: 250px"));
    }

    @Test
    public void detectHubElements_shouldPreferConnectionBasedWidthWhenLarger() throws Exception {
        // maxLabelWidth=10 + currentWidth=120 = 130 < connectionBasedWidth(120 + 15*4 = 180)
        accessor.setDetectHubElementsBehavior(vId ->
                new DetectHubElementsResultDto(vId, 2, 12, 6.0,
                        List.of(new HubElementEntryDto("vo-1", "e-1", "Small Label Hub",
                                "ApplicationComponent", 10, 120, 55, 10)),
                        List.of("Element 'Small Label Hub' has 10 connections (hub threshold: 6). "
                                + "Consider increasing height to 115px (55 + 15 \u00d7 4) for horizontal layouts, "
                                + "or width to 180px (120 + 15 \u00d7 4) for vertical layouts.")));

        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) entity.get("suggestions");
        assertNotNull(suggestions);
        assertFalse("Should NOT contain label-adjusted text when connection-based is larger",
                suggestions.get(0).contains("Label-adjusted width"));
    }

    @Test
    public void detectHubElements_shouldHandleNullRelationshipNames() throws Exception {
        // Entry with maxLabelWidth=0 means no labels contributed
        accessor.setDetectHubElementsBehavior(vId ->
                new DetectHubElementsResultDto(vId, 1, 8, 8.0,
                        List.of(new HubElementEntryDto("vo-1", "e-1", "No Labels Hub",
                                "ApplicationComponent", 8, 120, 55, 0)),
                        List.of("Element 'No Labels Hub' has 8 connections (hub threshold: 6). "
                                + "Consider increasing height to 85px (55 + 15 \u00d7 2) for horizontal layouts, "
                                + "or width to 150px (120 + 15 \u00d7 2) for vertical layouts.")));

        Map<String, Object> result = callAndParse("detect-hub-elements",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) entity.get("elements");
        assertNull("maxLabelWidth should be omitted when 0",
                elements.get(0).get("maxLabelWidth"));
    }

    // ---- layout-flat-view ----

    @Test
    public void shouldRegisterLayoutFlatViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "layout-flat-view".equals(spec.tool().name()));
        assertTrue("layout-flat-view tool should be registered", found);
    }

    @Test
    public void layoutFlatView_shouldParseRowArrangementAndCallAccessor() throws Exception {
        Map<String, Object> result = callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "row"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
        assertEquals("v-1", data.get("viewId"));
        assertEquals("row", data.get("arrangement"));
        assertEquals(6, ((Number) data.get("elementsRepositioned")).intValue());
    }

    @Test
    public void layoutFlatView_shouldParseColumnArrangement() throws Exception {
        Map<String, Object> result = callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "column"));

        Map<String, Object> data = getResult(result);
        assertEquals("column", data.get("arrangement"));
    }

    @Test
    public void layoutFlatView_shouldParseGridArrangementWithColumns() throws Exception {
        Map<String, Object> result = callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "grid", "columns", 4));

        Map<String, Object> data = getResult(result);
        assertEquals("grid", data.get("arrangement"));
        assertEquals(4, ((Number) data.get("columnsUsed")).intValue());
    }

    @Test
    public void layoutFlatView_shouldParseSortByParameter() throws Exception {
        Map<String, Object> result = callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "row", "sortBy", "name"));

        Map<String, Object> data = getResult(result);
        assertEquals("name", data.get("sortBy"));
    }

    @Test
    public void layoutFlatView_shouldParseCategoryFieldParameter() throws Exception {
        Map<String, Object> result = callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "row", "categoryField", "layer"));

        Map<String, Object> data = getResult(result);
        assertEquals("layer", data.get("categoryField"));
        assertNotNull("categories should be present when categoryField is set",
                data.get("categories"));
    }

    @Test
    public void layoutFlatView_shouldRequireViewId() throws Exception {
        McpSchema.CallToolResult result = callTool("layout-flat-view",
                Map.of("arrangement", "row"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("viewId"));
    }

    @Test
    public void layoutFlatView_shouldRequireArrangement() throws Exception {
        McpSchema.CallToolResult result = callTool("layout-flat-view",
                Map.of("viewId", "v-1"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("arrangement"));
    }

    @Test
    public void layoutFlatView_shouldRejectInvalidArrangement() throws Exception {
        accessor.setLayoutFlatViewBehavior((sid, vId, arr, sp, pad, sb, cf, cols, alc) -> {
            throw new ModelAccessException(
                    "Invalid arrangement: 'bogus'. Valid values: row, column, grid.",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "bogus"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("Invalid arrangement"));
    }

    @Test
    public void layoutFlatView_shouldIncludeNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "grid"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        assertTrue("Should suggest auto-route-connections",
                nextSteps.stream().anyMatch(s -> s.contains("auto-route-connections")));
    }

    @Test
    public void layoutFlatView_shouldHandleNoModelLoaded() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);
        noModelHandler.registerTools();

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("layout-flat-view")
                .arguments(Map.of("viewId", "v-1", "arrangement", "row"))
                .build();
        McpSchema.CallToolResult result = noModelHandler.handleLayoutFlatView(null, request);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void layoutFlatView_shouldForwardSpacingAndPadding() throws Exception {
        int[] capturedSpacing = {-1};
        int[] capturedPadding = {-1};
        accessor.setLayoutFlatViewBehavior((sid, vId, arr, sp, pad, sb, cf, cols, alc) -> {
            capturedSpacing[0] = sp;
            capturedPadding[0] = pad;
            return new MutationResult<>(new LayoutFlatViewResultDto(
                    vId, arr, 6, 0, sb, cf, null, null), null);
        });

        callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "column",
                        "spacing", 80, "padding", 30));

        assertEquals("spacing should be forwarded", 80, capturedSpacing[0]);
        assertEquals("padding should be forwarded", 30, capturedPadding[0]);
    }

    @Test
    public void layoutFlatView_shouldForwardSortByAndCategoryField() throws Exception {
        String[] capturedSortBy = {null};
        String[] capturedCategoryField = {null};
        accessor.setLayoutFlatViewBehavior((sid, vId, arr, sp, pad, sb, cf, cols, alc) -> {
            capturedSortBy[0] = sb;
            capturedCategoryField[0] = cf;
            return new MutationResult<>(new LayoutFlatViewResultDto(
                    vId, arr, 6, 0, sb, cf,
                    cf != null ? List.of("Application") : null, null), null);
        });

        callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "grid",
                        "sortBy", "type", "categoryField", "type"));

        assertEquals("sortBy should be forwarded", "type", capturedSortBy[0]);
        assertEquals("categoryField should be forwarded", "type", capturedCategoryField[0]);
    }

    @Test
    public void layoutFlatView_shouldForwardSortByLayer() throws Exception {
        String[] capturedSortBy = {null};
        accessor.setLayoutFlatViewBehavior((sid, vId, arr, sp, pad, sb, cf, cols, alc) -> {
            capturedSortBy[0] = sb;
            return new MutationResult<>(new LayoutFlatViewResultDto(
                    vId, arr, 6, 0, sb, cf, null, null), null);
        });

        callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "row", "sortBy", "layer"));

        assertEquals("sortBy layer should be forwarded", "layer", capturedSortBy[0]);
    }

    @Test
    public void layoutFlatView_shouldHandleEmptyViewError() throws Exception {
        accessor.setLayoutFlatViewBehavior((sid, vId, arr, sp, pad, sb, cf, cols, alc) -> {
            throw new ModelAccessException(
                    "View has no top-level elements to layout",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("layout-flat-view",
                Map.of("viewId", "v-empty", "arrangement", "row"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("no top-level elements"));
    }

    @Test
    public void layoutFlatView_shouldHandleMutationException() throws Exception {
        accessor.setLayoutFlatViewBehavior((sid, vId, arr, sp, pad, sb, cf, cols, alc) -> {
            throw new MutationException("Mutation failed");
        });

        McpSchema.CallToolResult result = callTool("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "row"));

        assertTrue("Should be error", result.isError());
    }

    // ---- layout-flat-view autoLayoutChildren ----

    @Test
    public void layoutFlatView_shouldForwardAutoLayoutChildrenTrue() throws Exception {
        boolean[] capturedAutoLayoutChildren = {false};
        accessor.setLayoutFlatViewBehavior((sid, vId, arr, sp, pad, sb, cf, cols, alc) -> {
            capturedAutoLayoutChildren[0] = alc;
            return new MutationResult<>(new LayoutFlatViewResultDto(
                    vId, arr, 6, 3, sb, cf, null, null), null);
        });
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "column");
        args.put("autoLayoutChildren", true);
        callAndParse("layout-flat-view", args);
        assertTrue("autoLayoutChildren should be forwarded as true",
                capturedAutoLayoutChildren[0]);
    }

    @Test
    public void layoutFlatView_shouldForwardAutoLayoutChildrenFalse() throws Exception {
        boolean[] capturedAutoLayoutChildren = {true};
        accessor.setLayoutFlatViewBehavior((sid, vId, arr, sp, pad, sb, cf, cols, alc) -> {
            capturedAutoLayoutChildren[0] = alc;
            return new MutationResult<>(new LayoutFlatViewResultDto(
                    vId, arr, 6, 0, sb, cf, null, null), null);
        });
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "row");
        args.put("autoLayoutChildren", false);
        callAndParse("layout-flat-view", args);
        assertFalse("autoLayoutChildren should be forwarded as false",
                capturedAutoLayoutChildren[0]);
    }

    @Test
    public void layoutFlatView_shouldDefaultAutoLayoutChildrenToTrue() throws Exception {
        boolean[] capturedAutoLayoutChildren = {false}; // sentinel
        accessor.setLayoutFlatViewBehavior((sid, vId, arr, sp, pad, sb, cf, cols, alc) -> {
            capturedAutoLayoutChildren[0] = alc;
            return new MutationResult<>(new LayoutFlatViewResultDto(
                    vId, arr, 6, 0, sb, cf, null, null), null);
        });
        callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "row"));
        assertTrue("autoLayoutChildren should default to true when not provided",
                capturedAutoLayoutChildren[0]);
    }

    @Test
    public void layoutFlatView_shouldIncludeChildrenRepositionedInResponse() throws Exception {
        accessor.setLayoutFlatViewBehavior((sid, vId, arr, sp, pad, sb, cf, cols, alc) ->
                new MutationResult<>(new LayoutFlatViewResultDto(
                        vId, arr, 6, 12, sb, cf, null, null), null));
        Map<String, Object> result = callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "column"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resultData = (Map<String, Object>) result.get("result");
        assertEquals("childrenRepositioned should be 12", 12,
                ((Number) resultData.get("childrenRepositioned")).intValue());
    }

    @Test
    public void layoutFlatView_shouldIncludeZeroChildrenRepositionedWhenNoChildren() throws Exception {
        Map<String, Object> result = callAndParse("layout-flat-view",
                Map.of("viewId", "v-1", "arrangement", "row"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resultData = (Map<String, Object>) result.get("result");
        assertEquals("childrenRepositioned should be 0", 0,
                ((Number) resultData.get("childrenRepositioned")).intValue());
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args)
            throws Exception {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(args)
                .build();

        return switch (toolName) {
            case "add-to-view" -> handler.handleAddToView(null, request);
            case "add-group-to-view" -> handler.handleAddGroupToView(null, request);
            case "add-note-to-view" -> handler.handleAddNoteToView(null, request);
            case "add-connection-to-view" -> handler.handleAddConnectionToView(null, request);
            case "update-view-object" -> handler.handleUpdateViewObject(null, request);
            case "update-view-connection" -> handler.handleUpdateViewConnection(null, request);
            case "remove-from-view" -> handler.handleRemoveFromView(null, request);
            case "clear-view" -> handler.handleClearView(null, request);
            case "apply-positions" -> handler.handleApplyViewLayout(null, request);
            case "assess-layout" -> handler.handleAssessLayout(null, request);
            case "auto-route-connections" -> handler.handleAutoRouteConnections(null, request);
            case "auto-connect-view" -> handler.handleAutoConnectView(null, request);
            case "layout-within-group" -> handler.handleLayoutWithinGroup(null, request);
            case "auto-layout-and-route" -> handler.handleAutoLayoutAndRoute(null, request);
            case "arrange-groups" -> handler.handleArrangeGroups(null, request);
            case "optimize-group-order" -> handler.handleOptimizeGroupOrder(null, request);
            case "detect-hub-elements" -> handler.handleDetectHubElements(null, request);
            case "layout-flat-view" -> handler.handleLayoutFlatView(null, request);
            case "adjust-view-spacing" -> handler.handleAdjustViewSpacing(null, request);
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }

    private Map<String, Object> callAndParse(String toolName, Map<String, Object> args)
            throws Exception {
        McpSchema.CallToolResult result = callTool(toolName, args);
        return parseResult(result);
    }

    private Map<String, Object> parseResult(McpSchema.CallToolResult result) throws Exception {
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        return objectMapper.readValue(content, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getResult(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("result");
    }

    // ---- assess-layout ----

    @Test
    public void assessLayout_shouldParseViewIdAndCallAccessor() throws Exception {
        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals(5, entity.get("elementCount"));
        assertEquals(3, entity.get("connectionCount"));
        assertEquals("good", entity.get("overallRating"));
    }

    @Test
    public void assessLayout_shouldRequireViewId() throws Exception {
        McpSchema.CallToolResult result = callTool("assess-layout",
                new HashMap<>());

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void assessLayout_shouldIncludeNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("export-view")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_shouldStampModelVersionInMeta() throws Exception {
        // The assessment is computed against the current model state; its _meta must carry
        // the model's monotonic mutation stamp so a consumer can compare it against an
        // export-view stamp and detect a render that predates a later mutation.
        Map<String, Object> envelope = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull("assess-layout envelope should have _meta", meta);
        assertEquals("assess-layout _meta should carry the model mutation stamp",
                accessor.getModelVersion(), meta.get("modelVersion"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_allViewsScope_shouldReturnCompactPerViewMap() throws Exception {
        accessor.setViews(List.of(
                new ViewDto("v-a", "View A", "Layered", "/"),
                new ViewDto("v-b", "View B", "Layered", "/")));
        accessor.setAssessLayoutBehavior(vId -> {
            String overall = "v-a".equals(vId) ? "fair" : "good";
            Map<String, String> bd = new LinkedHashMap<>();
            bd.put("overall", overall);
            bd.put("overallExcludingAcceptedCosmetics", "v-a".equals(vId) ? "excellent" : "good");
            return new AssessLayoutResultDto(
                    vId, 5, 3, 0, 0, 2, 0.67, 45.5, 70, overall, bd,
                    null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                    0, null, 0, null, 0, null, null,
                    List.of("ok"));
        });

        Map<String, Object> envelope = callAndParse("assess-layout",
                Map.of("scope", "all-views"));
        Map<String, Object> result = getResult(envelope);

        assertEquals("one compact entry per view", 2, result.size());
        Map<String, Object> a = (Map<String, Object>) result.get("v-a");
        assertNotNull("per-view entry keyed by view id", a);
        // Exactly the eight compact keys — no breakdown / violatorIds / descriptions.
        assertEquals(8, a.size());
        assertEquals("View A", a.get("name"));
        assertEquals("fair", a.get("overallRating"));
        assertEquals("excellent", a.get("overallExcludingAcceptedCosmetics"));
        assertEquals(5, a.get("elementCount"));
        assertEquals(3, a.get("connectionCount"));
        assertEquals(0, a.get("overlapCount"));
        assertEquals(0, a.get("nonOrthogonalTerminalCount"));
        assertEquals(0, a.get("connectionPassThroughCount"));

        Map<String, Object> b = (Map<String, Object>) result.get("v-b");
        assertEquals("good", b.get("overallRating"));
        assertEquals("good", b.get("overallExcludingAcceptedCosmetics"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_allViewsScope_nullBreakdown_keepsDenoisedKeyEqualToOverall()
            throws Exception {
        // A degenerate view can return a DTO with a null/absent ratingBreakdown. The compact
        // entry must still carry overallExcludingAcceptedCosmetics (the response mapper omits
        // null fields, which would silently drop it) — falling back to overallRating.
        accessor.setViews(List.of(new ViewDto("v-x", "View X", "Layered", "/")));
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 1, 0, 0, 0, 0, 0.0, 0.0, 0, "excellent", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("ok")));

        Map<String, Object> envelope = callAndParse("assess-layout",
                Map.of("scope", "all-views"));
        Map<String, Object> result = getResult(envelope);
        Map<String, Object> x = (Map<String, Object>) result.get("v-x");

        assertEquals("the eight compact keys are present even with a null breakdown",
                8, x.size());
        assertEquals("excellent", x.get("overallRating"));
        assertEquals("excellent", x.get("overallExcludingAcceptedCosmetics"));
    }

    @Test
    public void assessLayout_allViewsScope_emptyModel_shouldReturnEmptyMap() throws Exception {
        accessor.setViews(List.of());

        Map<String, Object> envelope = callAndParse("assess-layout",
                Map.of("scope", "all-views"));
        Map<String, Object> result = getResult(envelope);

        assertTrue("empty model yields an empty per-view map", result.isEmpty());
    }

    @Test
    public void assessLayout_allViewsScope_shouldIgnoreSuppliedViewId() throws Exception {
        accessor.setViews(List.of(new ViewDto("only", "Only", "Layered", "/")));

        Map<String, Object> envelope = callAndParse("assess-layout",
                Map.of("scope", "all-views", "viewId", "ignored-id"));
        Map<String, Object> result = getResult(envelope);

        // The map is keyed by the model's actual view id, not the supplied (ignored) viewId.
        assertTrue("all-views keys by real view ids", result.containsKey("only"));
        assertFalse("supplied viewId is ignored in all-views scope",
                result.containsKey("ignored-id"));
    }

    @Test
    public void assessLayout_singleScope_explicit_shouldReturnFullDto() throws Exception {
        // scope="single" (explicit) behaves identically to the default single-view path.
        Map<String, Object> envelope = callAndParse("assess-layout",
                Map.of("viewId", "v-1", "scope", "single"));
        Map<String, Object> entity = getResult(envelope);

        assertEquals("v-1", entity.get("viewId"));
        assertEquals(5, entity.get("elementCount"));
        assertEquals("good", entity.get("overallRating"));
    }

    @Test
    public void assessLayout_shouldIncludeLayoutSuggestionForPoorRating() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 10, 5, 4, 0, 15, 3.0, 8.0, 20, "poor", null,
                List.of("Element 'a' overlaps with element 'b'"),
                null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Found 4 overlapping element pairs — use auto-layout-and-route")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        // poor rating suggests auto-layout-and-route (no compute-layout)
        assertTrue("Should suggest auto-layout-and-route for poor rating",
                nextSteps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        assertFalse("Should NOT mention compute-layout (Story 11-22)",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    // Assessor.Redesign code-review H1 (2026-04-27): a view rated "poor" purely because of
    // M2/M3/M4/M5 routing defects (no overlaps, no PTs) must funnel to auto-route-connections,
    // not to auto-layout-and-route — re-positioning elements that are already clean is wrong.
    @Test
    public void buildAssessLayoutNextSteps_routingOnlyPoorWithZigzags_shouldSuggestAutoRouteFirst() {
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 6, 4, 0, 0, 0, 0.0, 50.0, 80, "poor", null,
                null, null, List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of(),
                // M2-M6: 2 zigzags, no other routing/layout defects
                0, null, 2, null, 0, null, 1.0, null,
                "excellent", "poor",
                // R8 (defaults)
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        assertTrue("Routing-only poor should suggest auto-route-connections",
                steps.stream().anyMatch(s -> s.contains("auto-route-connections")));
        assertTrue("Routing-only poor should still mention auto-layout-and-route as fallback",
                steps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        // Order matters — auto-route-connections must come first.
        int autoRouteIdx = -1, ergoLayoutIdx = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (autoRouteIdx == -1 && steps.get(i).contains("auto-route-connections")) {
                autoRouteIdx = i;
            }
            if (ergoLayoutIdx == -1 && steps.get(i).contains("auto-layout-and-route")) {
                ergoLayoutIdx = i;
            }
        }
        assertTrue("auto-route-connections must precede auto-layout-and-route fallback",
                autoRouteIdx >= 0 && (ergoLayoutIdx == -1 || autoRouteIdx < ergoLayoutIdx));
    }

    @Test
    public void buildAssessLayoutNextSteps_fairWithEdgeCoincidenceOnly_shouldSuggestAutoRoute() {
        // M4 edge-coincidence with no crossings, no PTs — fair-rated view should still
        // route to auto-route-connections per code-review H1 fix.
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 6, 4, 0, 0, 0, 0.0, 50.0, 80, "fair", null,
                null, null, List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of(),
                0, null, 0, null, 3, null, 1.0, null,
                "excellent", "fair",
                // R8 (defaults)
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        assertTrue("Fair with M4 alone should suggest auto-route-connections",
                steps.stream().anyMatch(s -> s.contains("auto-route-connections")));
    }

    @Test
    public void buildAssessLayoutNextSteps_fairWithLowHubPortQuality_shouldSuggestAutoRoute() {
        // M5 low hub-port quality with no other issues — fair-rated view should fall into
        // hasRoutingIssues path under code-review H1 fix.
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 6, 4, 0, 0, 0, 0.0, 50.0, 80, "fair", null,
                null, null, List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of(),
                0, null, 0, null, 0, null, 0.25, null,
                "excellent", "fair",
                // R8 (defaults)
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        assertTrue("Fair with low hub-port quality should suggest auto-route-connections",
                steps.stream().anyMatch(s -> s.contains("auto-route-connections")));
    }

    // Boundary-violation composite-remedy: predicate gate, violator-clause paths, ordering invariants.
    @Test
    public void buildAssessLayoutNextSteps_withBoundaryViolations_shouldEmitCompositeRemedy() {
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 6, 4, 0, 0, 0, 0.0, 50.0, 80, "poor", null,
                null,
                List.of("Element 'a' overflows parent 'g1'", "Element 'b' overflows parent 'g2'"),
                List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null,
                Map.of("boundaryViolations", List.of("id-elem-a", "id-elem-b")),
                List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                "excellent", "poor",
                // R8 (defaults)
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        String remedy = steps.stream()
                .filter(s -> s.contains("Composite recovery"))
                .findFirst()
                .orElse(null);
        assertNotNull("Composite-remedy entry should be present", remedy);
        assertTrue("Should include violation count",
                remedy.contains("Found 2 boundary violation(s)"));
        assertTrue("Should mention update-view-object", remedy.contains("update-view-object"));
        assertTrue("Should mention layout-within-group", remedy.contains("layout-within-group"));
        assertTrue("Should mention auto-route-connections",
                remedy.contains("auto-route-connections"));
        assertTrue("Should list violator elements",
                remedy.contains("violator elements: id-elem-a, id-elem-b"));
        assertTrue("Should reference Row F deferred sibling", remedy.contains("Row F"));
    }

    @Test
    public void buildAssessLayoutNextSteps_withBoundaryViolationsNoViolatorIds_shouldEmitFallbackClause() {
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 6, 4, 0, 0, 0, 0.0, 50.0, 80, "poor", null,
                null,
                List.of("Element 'a' overflows parent 'g1'"),
                List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null,
                null,
                List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                "excellent", "poor",
                // R8 (defaults)
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        String remedy = steps.stream()
                .filter(s -> s.contains("Composite recovery"))
                .findFirst()
                .orElse(null);
        assertNotNull("Composite-remedy entry should be present", remedy);
        assertTrue("Should include violation count",
                remedy.contains("Found 1 boundary violation(s)"));
        assertTrue("Should fall back to re-run guidance when violatorIds map is null",
                remedy.contains("re-run assess-layout with includeViolatorIds=true"));
    }

    @Test
    public void buildAssessLayoutNextSteps_withBoundaryViolationsEmptyViolatorIdsList_shouldEmitFallbackClause() {
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 6, 4, 0, 0, 0, 0.0, 50.0, 80, "poor", null,
                null,
                List.of("Element 'a' overflows parent 'g1'"),
                List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null,
                Map.of("boundaryViolations", List.of()),
                List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                "excellent", "poor",
                // R8 (defaults)
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        String remedy = steps.stream()
                .filter(s -> s.contains("Composite recovery"))
                .findFirst()
                .orElse(null);
        assertNotNull("Composite-remedy entry should be present", remedy);
        assertTrue("Should fall back to re-run guidance when violatorIds list is empty",
                remedy.contains("re-run assess-layout with includeViolatorIds=true"));
    }

    @Test
    public void buildAssessLayoutNextSteps_zeroBoundaryViolations_shouldNotEmitRemedy() {
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 6, 4, 0, 0, 0, 0.0, 50.0, 80, "good", null,
                null,
                List.of(),
                List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null,
                null,
                List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                "excellent", "good",
                // R8 (defaults)
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        assertFalse("Empty boundaryViolations list should not emit composite remedy",
                steps.stream().anyMatch(s -> s.contains("Composite recovery")));
    }

    @Test
    public void buildAssessLayoutNextSteps_nullBoundaryViolations_shouldNotEmitRemedy() {
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 6, 4, 0, 0, 0, 0.0, 50.0, 80, "good", null,
                null,
                null,
                List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null,
                null,
                List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                "excellent", "good",
                // R8 (defaults)
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        assertFalse("Null boundaryViolations should not emit composite remedy",
                steps.stream().anyMatch(s -> s.contains("Composite recovery")));
    }

    @Test
    public void buildAssessLayoutNextSteps_compositeRemedyPositioning_shouldPrecedeRatingSwitch() {
        // boundaryViolations populated + grouped view + crossingsPerConnection=5.0 (>4.0)
        // + rating="poor" — composite-remedy + inter-group-crossing-density + rating-switch
        // (auto-layout-and-route) all fire. Pin: composite-remedy index < rating-switch index.
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 6, 4, 0, 0, 25, 5.0, 50.0, 80, "poor", null,
                null,
                List.of("Element 'a' overflows parent 'g1'"),
                List.of(), null, 0, null, 0, null, 0, null,
                true, 0, 0, null,
                0, null, 0, null, 0, null,
                Map.of("boundaryViolations", List.of("id-elem-a")),
                List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                "poor", "poor",
                // R8 (defaults)
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        int compositeIdx = -1, crossingDensityIdx = -1, ratingSwitchIdx = -1;
        for (int i = 0; i < steps.size(); i++) {
            String s = steps.get(i);
            if (compositeIdx == -1 && s.contains("Composite recovery")) {
                compositeIdx = i;
            }
            if (crossingDensityIdx == -1 && s.contains("High inter-group crossing density")) {
                crossingDensityIdx = i;
            }
            if (ratingSwitchIdx == -1 && s.contains("auto-layout-and-route")) {
                ratingSwitchIdx = i;
            }
        }
        assertTrue("Composite-remedy entry must be present", compositeIdx >= 0);
        assertTrue("Inter-group-crossing-density entry must be present", crossingDensityIdx >= 0);
        assertTrue("Rating-switch (auto-layout-and-route) entry must be present",
                ratingSwitchIdx >= 0);
        assertTrue("Composite-remedy must precede rating-switch advice",
                compositeIdx < ratingSwitchIdx);
    }

    @Test
    public void buildAssessLayoutNextSteps_compositeRemedyAlwaysBeforeExportView() {
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 6, 4, 0, 0, 0, 0.0, 50.0, 80, "good", null,
                null,
                List.of("Element 'a' overflows parent 'g1'"),
                List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null,
                null,
                List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                "excellent", "good",
                // R8 (defaults)
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        int compositeIdx = -1, exportIdx = -1;
        for (int i = 0; i < steps.size(); i++) {
            String s = steps.get(i);
            if (compositeIdx == -1 && s.contains("Composite recovery")) {
                compositeIdx = i;
            }
            if (exportIdx == -1 && s.contains("export-view")) {
                exportIdx = i;
            }
        }
        assertTrue("Composite-remedy entry must be present", compositeIdx >= 0);
        assertTrue("Export-view terminal step must be present", exportIdx >= 0);
        assertTrue("Composite-remedy must come before export-view (terminal step)",
                compositeIdx < exportIdx);
    }

    // ----- Saturated container-nested-hub diagnostic step -----
    // Trigger: corridorUtilisationScore >= 0.9 AND (edgeCoincidence > 4 OR coincidentSegments > 2)
    // AND hubPortQualityScore >= 0.5. Diagnostic (detect-hub-first, hub-existence-safe), both levers
    // + render caveat; supersedes the generic spacing step; suppressed when hub-port quality already
    // flagged (< 0.5) or corridors are not saturated.

    /**
     * Build a DTO with the saturated-nested-hub knobs set; everything else neutral. Uses the 45-arg
     * delegating ctor, so {@code hubNeighbourClearanceMin} defaults to the negative sentinel →
     * these DTOs exercise the emitter's hub-existence-safe present-both branch. Tests that need the
     * sparse/dense branch use {@link #nestedHubDtoWithClearance} instead.
     */
    private static AssessLayoutResultDto nestedHubDto(double corridorUtil, int edgeCoinc,
            int coincidentSeg, double hpq, String rating, boolean hasGroups) {
        return new AssessLayoutResultDto(
                "v-1", 8, 8, 0, 0, 0, 0.0, 50.0, 80, rating, null,
                null, null, List.<String>of(), null, 0, null, 0, null, 0, null,
                hasGroups, coincidentSeg, 0, null,
                0, null, 0, null, 0, null, null, List.<String>of(),
                0, null, 0, null, edgeCoinc, null, hpq, null,
                rating, rating,
                corridorUtil, null);
    }

    private static String saturatedStep(List<String> steps) {
        return steps.stream()
                .filter(s -> s.contains("Saturated layout (corridorUtilisation"))
                .findFirst().orElse(null);
    }

    @Test
    public void buildAssessLayoutNextSteps_saturatedNestedHub_emitsDiagnosticStep() {
        // corridorUtil 0.95, edgeCoinc 6 (>4), hpq 1.0 (>=0.5) → trigger fires.
        List<String> steps =
                handler.buildAssessLayoutNextSteps(nestedHubDto(0.95, 6, 0, 1.0, "fair", false));

        String diag = saturatedStep(steps);
        assertNotNull("Saturated container-nested-hub diagnostic step must be present", diag);

        // The step is diagnostic and hub-existence-safe: it names detect-hub-elements first and
        // gates all resize/reposition advice behind "If a hub is present" — there is no
        // unconditional resize imperative (the enlarge lever follows the conditional gate).
        assertTrue("Step must name detect-hub-elements first",
                diag.contains("detect-hub-elements"));
        assertTrue("Step must be conditional on a hub being present",
                diag.contains("If a hub is present"));
        assertTrue("The conditional gate must precede any enlarge directive (no unconditional resize)",
                diag.indexOf("If a hub is present") < diag.indexOf("enlarge"));
        assertTrue("detect-hub-elements must precede the enlarge directive",
                diag.indexOf("detect-hub-elements") < diag.indexOf("enlarge"));

        // Both levers present (resize + ELK reposition), each with its specific caveat.
        assertTrue("Resize lever: enlarge in both dimensions then auto-route-connections",
                diag.contains("BOTH") && diag.contains("update-view-object")
                        && diag.contains("auto-route-connections"));
        assertTrue("Resize caveat: re-routing alone is inert", diag.contains("inert"));
        assertTrue("Resize caveat: high hubPortQualityScore does not mean resize won't help",
                diag.contains("hubPortQualityScore"));
        assertTrue("Reposition lever: revert hub to normal size + ELK",
                diag.contains("normal size") && diag.contains("auto-layout-and-route"));
        assertTrue("Reposition lever: FULL auto-route, not terminals-only",
                diag.contains("terminals-only"));
        assertTrue("Render-authoritative caveat: verify with export-view, not the rating",
                diag.contains("export-view") && diag.contains("do not accept on"));
    }

    @Test
    public void buildAssessLayoutNextSteps_saturatedViaCoincidentSegments_emitsDiagnosticStep() {
        // Second arm of the OR: coincidentSegments 3 (>2), edgeCoinc 0 → trigger still fires.
        List<String> steps =
                handler.buildAssessLayoutNextSteps(nestedHubDto(0.92, 0, 3, 1.0, "fair", true));
        assertNotNull("coincidentSegments>2 must also trip the diagnostic",
                saturatedStep(steps));
    }

    @Test
    public void buildAssessLayoutNextSteps_saturatedNestedHub_suppressesSpacingStepAndPrecedesRatingSwitch() {
        List<String> steps =
                handler.buildAssessLayoutNextSteps(nestedHubDto(0.95, 6, 0, 1.0, "fair", true));

        // Block #2 (generic spacing inflation) must be suppressed — no two conflicting spacing remedies.
        assertFalse("Generic spacing-tightness step must be suppressed when the diagnostic fires",
                steps.stream().anyMatch(s -> s.contains("Spacing tightness flagged")));

        // Ordered before the terminal export-view step. (Match the terminal step's exact text —
        // the diagnostic's own render-authoritative caveat also mentions export-view.)
        int diagIdx = -1, terminalIdx = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (diagIdx == -1 && steps.get(i).contains("Saturated layout (corridorUtilisation")) {
                diagIdx = i;
            }
            if (terminalIdx == -1 && steps.get(i).contains("visually inspect the current layout")) {
                terminalIdx = i;
            }
        }
        assertTrue("Diagnostic step must precede the terminal export-view step",
                diagIdx >= 0 && terminalIdx >= 0 && diagIdx < terminalIdx);
    }

    @Test
    public void buildAssessLayoutNextSteps_triggerNotMet_block2FiresUnchanged() {
        // corridorUtil 0.5 (< 0.9) → diagnostic does NOT fire, but Block #2's condition (edgeCoinc>4)
        // is met → the generic spacing step fires unchanged.
        List<String> steps =
                handler.buildAssessLayoutNextSteps(nestedHubDto(0.5, 6, 0, 1.0, "fair", true));

        assertNull("Diagnostic must not fire when corridors are not saturated",
                saturatedStep(steps));
        assertTrue("Block #2 spacing-tightness step fires unchanged when its condition is met",
                steps.stream().anyMatch(s -> s.contains("Spacing tightness flagged")));
    }

    @Test
    public void buildAssessLayoutNextSteps_lowHubPortQuality_block1FiresDiagnosticSuppressed() {
        // hpq 0.25 (< 0.5) → Block #1 fires; the new diagnostic is suppressed (no double hub advice).
        List<String> steps =
                handler.buildAssessLayoutNextSteps(nestedHubDto(0.95, 6, 0, 0.25, "fair", false));

        assertNull("Diagnostic must be suppressed when hub-port quality already flagged (<0.5)",
                saturatedStep(steps));
        assertTrue("Block #1 hub-port-quality step fires unchanged",
                steps.stream().anyMatch(s -> s.contains("Hub-port quality")));
    }

    @Test
    public void buildAssessLayoutNextSteps_lowCorridorUtil_noDiagnostic() {
        // corridorUtil 0.89 (< 0.9), edgeCoinc 6 but Block #2 also fires — diagnostic stays off.
        // Boundary mirror of triggerNotMet: just-below the threshold must still suppress the
        // diagnostic AND let Block #2 fire (guards against the threshold drifting to <=0.89).
        List<String> steps =
                handler.buildAssessLayoutNextSteps(nestedHubDto(0.89, 6, 0, 1.0, "fair", false));
        assertNull("Diagnostic must not fire below the corridor-saturation threshold",
                saturatedStep(steps));
        assertTrue("Block #2 fires unchanged when corridors are just below saturation",
                steps.stream().anyMatch(s -> s.contains("Spacing tightness flagged")));
    }

    @Test
    public void buildAssessLayoutNextSteps_edgeCoinc4OrBelow_noDiagnostic() {
        // edgeCoinc 4 (NOT >4) and coincidentSeg 2 (NOT >2), corridors saturated → no trigger.
        // Pins the boundary so a saturated-but-low-coincidence view is byte-clean of the new step.
        List<String> steps =
                handler.buildAssessLayoutNextSteps(nestedHubDto(1.0, 4, 2, 1.0, "good", false));
        assertNull("Diagnostic must not fire when coincidence pressure is at/below threshold",
                saturatedStep(steps));
        assertFalse("And Block #2 must also stay off at/below its threshold",
                steps.stream().anyMatch(s -> s.contains("Spacing tightness flagged")));
    }

    // ----- Density-branched diagnostic (hub-neighbour clearance) -----
    // When the assessor measures a hub-to-spoke-row clearance, the diagnostic branches:
    // clearance >= floor → resize lever only (sparse); 0..floor → reposition lever only (dense);
    // sentinel (no measurement) → present both (hub-existence-safe, covered above).

    /** Saturated-nested-hub DTO with the hub-neighbour clearance scalar set (canonical ctor). */
    private static AssessLayoutResultDto nestedHubDtoWithClearance(double corridorUtil,
            int edgeCoinc, int coincidentSeg, double hpq, String rating, boolean hasGroups,
            double clearance) {
        return new AssessLayoutResultDto(
                "v-1", 8, 8, 0, 0, 0, 0.0, 50.0, 80, rating, null,
                null, null, List.<String>of(), null, 0, null, 0, null, 0, null,
                0, null,
                hasGroups, coincidentSeg, 0, null,
                0, null, 0, null, 0, null, null, List.<String>of(),
                0, null, 0, null, edgeCoinc, null, hpq, null,
                rating, rating,
                corridorUtil, null,
                null, 0, null,
                clearance);
    }

    @Test
    public void buildAssessLayoutNextSteps_saturatedSparseHub_emitsResizeLeverOnly() {
        // clearance 90px (>= 60 floor) → SPARSE → resize lever only, present-both text gone.
        List<String> steps = handler.buildAssessLayoutNextSteps(
                nestedHubDtoWithClearance(0.95, 6, 0, 1.0, "fair", false, 90.0));
        String diag = saturatedStep(steps);
        assertNotNull("Diagnostic step must still be present", diag);
        assertTrue("Names detect-hub-elements first", diag.contains("detect-hub-elements"));
        assertTrue("Conditional on a hub being present", diag.contains("If a hub is present"));
        assertTrue("Sparse → room to grow", diag.contains("room to grow"));
        assertTrue("Sparse → enlarge in BOTH dimensions", diag.contains("BOTH"));
        assertTrue("Render-authoritative caveat preserved",
                diag.contains("export-view") && diag.contains("do not accept on"));
        assertFalse("Present-both MVP replaced — no choose-by-density",
                diag.contains("choose by density"));
        assertFalse("Sparse branch must not emit the reposition lever",
                diag.contains("would crowd its neighbours"));
        assertFalse("Generic spacing step suppressed when the diagnostic fires",
                steps.stream().anyMatch(s -> s.contains("Spacing tightness flagged")));
    }

    @Test
    public void buildAssessLayoutNextSteps_saturatedDenseHub_emitsRepositionLeverOnly() {
        // clearance 45px (< 60 floor) → DENSE → reposition lever only, present-both text gone.
        List<String> steps = handler.buildAssessLayoutNextSteps(
                nestedHubDtoWithClearance(0.95, 6, 0, 1.0, "fair", false, 45.0));
        String diag = saturatedStep(steps);
        assertNotNull("Diagnostic step must still be present", diag);
        assertTrue("Names detect-hub-elements first", diag.contains("detect-hub-elements"));
        assertTrue("Conditional on a hub being present", diag.contains("If a hub is present"));
        assertTrue("Dense → enlarging would crowd neighbours",
                diag.contains("would crowd its neighbours"));
        assertTrue("Dense → ELK reposition + revert to normal size",
                diag.contains("normal size") && diag.contains("auto-layout-and-route"));
        assertTrue("Dense → FULL auto-route, not terminals-only", diag.contains("terminals-only"));
        assertTrue("Render-authoritative caveat preserved",
                diag.contains("export-view") && diag.contains("do not accept on"));
        assertFalse("Dense branch must not emit the resize lever", diag.contains("room to grow"));
        assertFalse("Present-both MVP replaced — no choose-by-density",
                diag.contains("choose by density"));
        assertFalse("Generic spacing step suppressed when the diagnostic fires",
                steps.stream().anyMatch(s -> s.contains("Spacing tightness flagged")));
    }

    @Test
    public void buildAssessLayoutNextSteps_saturatedSentinelClearance_presentsBothLevers() {
        // clearance -1 (no hub measured) → hub-existence-safe fallback presents both levers.
        List<String> steps = handler.buildAssessLayoutNextSteps(
                nestedHubDtoWithClearance(0.95, 6, 0, 1.0, "fair", false,
                        AssessLayoutResultDto.NO_HUB_NEIGHBOUR_CLEARANCE));
        String diag = saturatedStep(steps);
        assertNotNull("Diagnostic step must be present", diag);
        assertTrue("Sentinel → present both via choose-by-density",
                diag.contains("choose by density"));
        assertTrue("Sentinel fallback still names detect-hub-elements",
                diag.contains("detect-hub-elements"));
    }

    @Test
    public void assessLayout_shouldFormatAsReadOnlyResponse() throws Exception {
        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        // Read-only response should NOT have mutation-specific fields
        assertNull(result.get("batched"));
        assertNull(result.get("proposal"));
    }

    @Test
    public void assessLayout_shouldIncludeContainmentOverlapsInResponse() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 2, 0, 5, 1, 0.5, 40.0, 65, "good", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Layout quality is good — no immediate improvements needed.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("result");
        assertEquals(0, ((Number) data.get("overlapCount")).intValue());
        assertEquals(5, ((Number) data.get("containmentOverlaps")).intValue());
    }

    @Test
    public void assessLayout_shouldIncludeRatingBreakdownInResponse() throws Exception {
        // verify ratingBreakdown serializes through handler→formatter→JSON
        Map<String, String> breakdown = new java.util.LinkedHashMap<>();
        breakdown.put("overlaps", "pass");
        breakdown.put("edgeCrossings", "good");
        breakdown.put("spacing", "pass");
        breakdown.put("alignment", "pass");
        breakdown.put("labelOverlaps", "pass");
        breakdown.put("passThroughs", "pass");
        breakdown.put("overall", "good");
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 0, 0, 12, 3.0, 45.0, 70, "good", breakdown,
                null, null, null, null, 0, null, 0, null, 0, null, true, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Layout quality is good — no immediate improvements needed.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("result");
        @SuppressWarnings("unchecked")
        Map<String, String> returnedBreakdown = (Map<String, String>) data.get("ratingBreakdown");
        assertNotNull("ratingBreakdown should be present in JSON response", returnedBreakdown);
        assertEquals("good", returnedBreakdown.get("overall"));
        assertEquals("pass", returnedBreakdown.get("overlaps"));
        assertEquals("good", returnedBreakdown.get("edgeCrossings"));
        assertEquals(7, returnedBreakdown.size());
    }

    @Test
    public void assessLayout_shouldReturnErrorOnViewNotFound() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> {
            throw new ModelAccessException("View not found: " + vId,
                    ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("assess-layout",
                Map.of("viewId", "bad-id"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    // ---- orphan detection in assess-layout ----

    @Test
    public void assessLayout_shouldReportOrphanedConnections() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 5, 3, 0, 0, 0, 0.0, 50.0, 80, "good", null,
                null, null, null, null, 0, null, 2,
                List.of("Connection 'c-1' references missing view object(s): source=obj-x target=obj-y"),
                0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Layout quality is good — no immediate improvements needed.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("result");
        assertEquals(2, ((Number) data.get("orphanedConnections")).intValue());
        @SuppressWarnings("unchecked")
        List<String> orphanDescs = (List<String>) data.get("orphanedConnectionDescriptions");
        assertNotNull(orphanDescs);
        assertEquals(1, orphanDescs.size());
        assertTrue(orphanDescs.get(0).contains("missing view object"));
    }

    @Test
    public void assessLayout_shouldOmitOrphanFieldsWhenZero() throws Exception {
        // Default behavior has 0 orphans and null descriptions
        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("result");
        // orphanedConnections is 0 (int, always present)
        assertEquals(0, ((Number) data.get("orphanedConnections")).intValue());
        // orphanedConnectionDescriptions should be null/absent (NON_NULL)
        assertNull(data.get("orphanedConnectionDescriptions"));
    }

    @Test
    public void assessLayout_shouldSuggestClearViewForOrphans() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 5, 3, 0, 0, 0, 0.0, 50.0, 80, "good", null,
                null, null, null, null, 0, null, 3,
                List.of("Connection 'c-1' references missing view object(s)"),
                0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Layout quality is good.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Should suggest clear-view for orphans",
                nextSteps.stream().anyMatch(s -> s.contains("orphaned") && s.contains("clear-view")));
    }

    // ---- context-aware graduated nextSteps ----

    @Test
    public void assessLayout_excellentRating_shouldOnlyRecommendExportView() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 0, 0, 0, 0.0, 80.0, 90, "excellent", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("No issues detected.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals("Excellent should only have export-view step", 1, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("export-view"));
    }

    @Test
    public void assessLayout_goodWithEdgeCrossings_shouldRecommendAutoRoute() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 0, 0, 5, 1.25, 60.0, 80, "good", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Some crossings.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Good + crossings should recommend auto-route-connections",
                nextSteps.stream().anyMatch(s -> s.contains("auto-route-connections")));
        assertFalse("Good rating should NOT recommend compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_goodWithSpacingIssuesAndGroups_shouldRecommendLayoutWithinGroup() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 10, 3, 2, 0, 0, 0.0, 25.0, 60, "good", null,
                List.of("overlap1", "overlap2"),
                null, null, null, 0, null, 0, null, 0, null, true, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Use layout-within-group.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Grouped view should recommend layout-within-group",
                nextSteps.stream().anyMatch(s -> s.contains("layout-within-group")));
        assertFalse("Good + grouped should NOT recommend compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_fairRating_shouldRecommendAutoLayoutAndRoute() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 12, 6, 3, 0, 8, 1.33, 30.0, 50, "fair", null,
                List.of("overlap"), null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Multiple issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Fair should recommend auto-layout-and-route",
                nextSteps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        assertTrue("Fair should mention auto-route-connections as lighter alternative",
                nextSteps.stream().anyMatch(s -> s.contains("auto-route-connections")));
        assertFalse("Fair should NOT recommend compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_poorRating_shouldRecommendAutoLayoutNoLayoutView() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 15, 8, 6, 0, 20, 2.5, 15.0, 30, "poor", null,
                List.of("many overlaps"), null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Major issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Poor should recommend auto-layout-and-route",
                nextSteps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        assertFalse("Poor should NOT mention compute-layout (Story 11-22)",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_poorWithGroups_shouldNotRecommendLayoutView() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 15, 8, 6, 0, 20, 2.5, 15.0, 30, "poor", null,
                List.of("many overlaps"), null, null, null, 0, null, 0, null, 0, null, true, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Major issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Poor + grouped should recommend auto-layout-and-route",
                nextSteps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        assertFalse("Poor + grouped should NOT recommend compute-layout (Story 11-22)",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_orphanedConnections_shouldPreserveClearViewGuidance() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 0, 0, 0, 0.0, 80.0, 90, "excellent", null,
                null, null, null, null, 0, null, 2,
                List.of("orphan1"), 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("No issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Orphaned connections should still recommend clear-view",
                nextSteps.stream().anyMatch(s -> s.contains("orphaned") && s.contains("clear-view")));
    }

    @Test
    public void assessLayout_goodWithSpacingIssuesFlat_shouldRecommendApplyViewLayout() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 10, 3, 2, 0, 0, 0.0, 25.0, 60, "good", null,
                List.of("overlap1", "overlap2"),
                null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Spacing is tight.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Flat view should recommend apply-positions",
                nextSteps.stream().anyMatch(s -> s.contains("apply-positions")));
        assertFalse("Good + flat should NOT recommend compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_goodWithNoIssues_shouldOnlyRecommendExportView() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 0, 0, 0, 0.0, 80.0, 90, "good", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("No issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals("Good with no issues should only have export-view", 1, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("export-view"));
    }

    @Test
    public void assessLayout_fairWithGroups_shouldNotRecommendLayoutView() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 12, 6, 3, 0, 8, 1.33, 30.0, 50, "fair", null,
                List.of("overlap"), null, null, null, 0, null, 0, null, 0, null, true, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Multiple issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Fair + grouped should recommend auto-layout-and-route",
                nextSteps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        assertFalse("Fair + grouped should NOT recommend compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_anyRating_shouldAlwaysEndWithExportView() throws Exception {
        // Test with fair rating (has multiple steps)
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 12, 6, 3, 0, 8, 1.33, 30.0, 50, "fair", null,
                List.of("overlap"), null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null,
                List.of("Issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Last step should always be export-view",
                nextSteps.get(nextSteps.size() - 1).contains("export-view"));
    }

    // ---- includeViolatorIds parameter passthrough ----

    @Test
    public void assessLayout_shouldPassIncludeViolatorIdsToAccessor() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 2, 0, 0, 0.0, 80.0, 90, "good", null,
                List.of("overlap1"), null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null,
                Map.of("overlaps", List.of("elem-1", "elem-2")),
                List.of("No issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1", "includeViolatorIds", true));

        // Verify parameter was passed through to accessor
        assertTrue("includeViolatorIds should be passed to accessor",
                accessor.lastAssessLayoutIncludeViolatorIds);

        // Verify violatorIds appears in response
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> violatorIds = (Map<String, Object>) data.get("violatorIds");
        assertNotNull("violatorIds should be present in response", violatorIds);
        @SuppressWarnings("unchecked")
        List<String> overlapIds = (List<String>) violatorIds.get("overlaps");
        assertNotNull("Should have overlaps key", overlapIds);
        assertTrue("Should contain elem-1", overlapIds.contains("elem-1"));
        assertTrue("Should contain elem-2", overlapIds.contains("elem-2"));
    }

    @Test
    public void assessLayout_shouldOmitViolatorIdsWhenNotRequested() throws Exception {
        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        // Default behavior returns null violatorIds
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("result");
        assertNull("violatorIds should be absent when not requested",
                data.get("violatorIds"));
        // Parameter should default to false
        assertFalse("includeViolatorIds should default to false",
                accessor.lastAssessLayoutIncludeViolatorIds);
    }

    // ---- auto-route-connections tests ----

    @Test
    public void autoRoute_shouldRouteAllConnections_defaultStrategy() throws Exception {
        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
        assertEquals("v-1", data.get("viewId"));
        assertEquals(5, ((Number) data.get("connectionsRouted")).intValue());
        assertEquals("orthogonal", data.get("strategy"));
    }

    @Test
    public void autoRoute_shouldReturnErrorWhenViewIdMissing() throws Exception {
        McpSchema.CallToolResult result = callTool("auto-route-connections",
                Map.of());

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("viewId"));
    }

    @Test
    public void autoRoute_shouldUseClearStrategy() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 3, "clear", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "strategy", "clear"));

        Map<String, Object> data = getResult(result);
        assertEquals("clear", data.get("strategy"));
        assertEquals(3, ((Number) data.get("connectionsRouted")).intValue());
    }

    @Test
    public void autoRoute_shouldPassConnectionIdsFilter() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            int count = (connIds != null) ? connIds.size() : 0;
            return new MutationResult<>(new AutoRouteResultDto(vId, count, "orthogonal", false), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds", List.of("c-1", "c-2")));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsRouted")).intValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_layoutBoundWarning_surfacesLayoutBoundNextStep() throws Exception {
        // The router rolled back an off-face egress lift (corridor too tight for a healthy lift); the
        // DTO carries the EGRESS_LIFT_LAYOUT_BOUND structured warning. The handler must translate it
        // into a layout-bound nextSteps entry directing the caller to widen spacing, not re-route.
        StructuredWarningDto egress = new StructuredWarningDto(
                StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND,
                "1 off-face terminal hug(s) could not be cleared … 15px healthy floor …",
                "apply-spacing-recommendations", List.of());
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 0, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(egress)), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("layout-bound decline surfaces a nextSteps entry",
                nextSteps.stream().anyMatch(s -> s.contains("layout-bound")));
        assertTrue("nextSteps names the corridor-widening remedy",
                nextSteps.stream().anyMatch(s -> s.contains("Increase element spacing")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_layoutBoundWarning_batched_surfacesLayoutBoundNextStep() throws Exception {
        // The batched nextSteps branch must apply the same layout-bound guard as the non-batched one.
        StructuredWarningDto egress = new StructuredWarningDto(
                StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND,
                "1 off-face terminal hug(s) could not be cleared … 15px healthy floor …",
                "apply-spacing-recommendations", List.of());
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 0, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(egress)), 2));   // batchSequenceNumber → batched branch

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("batched path also surfaces the layout-bound step",
                nextSteps.stream().anyMatch(s -> s.contains("layout-bound")));
        assertTrue("batched path still mentions the batch queue",
                nextSteps.stream().anyMatch(s -> s.contains("batch")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_noLayoutBoundWarning_emitsNoLayoutBoundNextStep() throws Exception {
        // No egress lift was rolled back (kept, or none generated) → no structured warning → no
        // layout-bound nextSteps entry. Guards against a false signal on a healthy re-route.
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse("no layout-bound nextSteps entry without the structured warning",
                nextSteps.stream().anyMatch(s -> s.contains("layout-bound")));
    }

    @Test
    public void autoRoute_shouldReturnErrorOnInvalidStrategy() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            throw new ModelAccessException("Invalid strategy: 'bogus'. Valid: orthogonal, clear",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("auto-route-connections",
                Map.of("viewId", "v-1", "strategy", "bogus"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void autoRoute_shouldReturnProposalInApprovalMode() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(null, null, new ProposalContext("p-99",
                        "Auto-route connections on view " + vId,
                        Instant.parse("2026-03-04T00:00:00Z"))));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-99", proposal.get("proposalId"));
    }

    // ---- auto-route routerTypeSwitched ----

    @Test
    public void autoRoute_shouldIncludeRouterTypeSwitchedTrue() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 10, "orthogonal", true), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals(true, data.get("routerTypeSwitched"));
        // nextSteps should mention the switch
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should mention router type switch",
                nextSteps.stream().anyMatch(s -> s.contains("bendpoint mode")));
    }

    @Test
    public void autoRoute_shouldIncludeRouterTypeSwitchedFalse() throws Exception {
        // Explicitly set up a scenario where routerTypeSwitched is false
        // (view already in bendpoint mode — no switch needed)
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals(false, data.get("routerTypeSwitched"));
    }

    @Test
    public void autoRoute_clearStrategy_shouldNotSwitchRouterType() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 3, "clear", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "strategy", "clear"));

        Map<String, Object> data = getResult(result);
        assertEquals(false, data.get("routerTypeSwitched"));
    }

    // ---- auto-route selective routing & partial success ----

    @Test
    public void autoRoute_shouldRouteOnlySpecifiedConnections_preservingOthers() throws Exception {
        // When connectionIds are specified, only those connections should be routed
        // The count should reflect only the specified connections
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertNotNull("connectionIds should be passed through", connIds);
            assertEquals(2, connIds.size());
            assertEquals("c-1", connIds.get(0));
            assertEquals("c-3", connIds.get(1));
            return new MutationResult<>(new AutoRouteResultDto(vId, 2, "orthogonal", false), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds", List.of("c-1", "c-3")));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsRouted")).intValue());
    }

    @Test
    public void autoRoute_shouldRouteAllConnections_whenConnectionIdsOmitted() throws Exception {
        // When connectionIds is omitted, all connections should be routed (backward compat)
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertNull("connectionIds should be null when omitted", connIds);
            return new MutationResult<>(new AutoRouteResultDto(vId, 20, "orthogonal", false), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals(20, ((Number) data.get("connectionsRouted")).intValue());
    }

    @Test
    public void autoRoute_shouldIncludeWarningsForInvalidIds() throws Exception {
        // Partial success: valid connections routed, invalid IDs reported as warnings
        List<String> testWarnings = List.of(
                "Connection not found on view: bad-id-1",
                "Connection not found on view: bad-id-2");
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 1, "orthogonal", false, testWarnings), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds",
                        List.of("c-valid", "bad-id-1", "bad-id-2")));

        Map<String, Object> data = getResult(result);
        assertEquals(1, ((Number) data.get("connectionsRouted")).intValue());
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) data.get("warnings");
        assertNotNull("Should have warnings", warnings);
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("bad-id-1"));
        assertTrue(warnings.get(1).contains("bad-id-2"));
    }

    @Test
    public void autoRoute_shouldReturnError_whenAllConnectionIdsInvalid() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            throw new ModelAccessException(
                    "None of the specified connection IDs were found on the view",
                    ErrorCode.ELEMENT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds", List.of("bad-1", "bad-2")));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("ELEMENT_NOT_FOUND"));
        assertTrue(content.contains("None of the specified connection IDs"));
    }

    @Test
    public void autoRoute_shouldOmitWarningsWhenEmpty() throws Exception {
        // When no warnings, the field should be absent from JSON (NON_EMPTY)
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertNull("warnings should be absent when empty", data.get("warnings"));
    }

    @Test
    public void autoRoute_shouldIncludeWarningsNextStep() throws Exception {
        List<String> testWarnings = List.of("Connection not found on view: bad-id");
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 2, "orthogonal", false, testWarnings), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds", List.of("c-1", "c-2", "bad-id")));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should mention warnings",
                nextSteps.stream().anyMatch(s -> s.contains("warnings")));
    }

    @Test
    public void autoRoute_shouldMentionSelectiveRoutingInNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should mention selective routing with connectionIds",
                nextSteps.stream().anyMatch(s -> s.contains("connectionIds")));
    }

    // ---- auto-route force mode ----

    @Test
    public void autoRoute_shouldDefaultForceToFalse() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertFalse("force should default to false", force);
            return new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null);
        });
        callAndParse("auto-route-connections", Map.of("viewId", "v-1"));
    }

    @Test
    public void autoRoute_shouldPassForceTrueToAccessor() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertTrue("force should be true", force);
            return new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null);
        });
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("force", true);
        callAndParse("auto-route-connections", args);
    }

    @Test
    public void autoRoute_shouldIncludeViolationsInForceMode() throws Exception {
        List<RoutingViolationDto> violations = List.of(
                new RoutingViolationDto("c-1", "Src", "Tgt", "element_crossing", "warning"));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 5, 0, "orthogonal", false,
                        List.of(), List.of(), List.of(), violations), null));
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("force", true);
        Map<String, Object> result = callAndParse("auto-route-connections", args);
        Map<String, Object> data = getResult(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violationList = (List<Map<String, Object>>) data.get("violations");
        assertNotNull("violations should be present", violationList);
        assertEquals(1, violationList.size());
        assertEquals("element_crossing", violationList.get(0).get("constraintViolated"));
        assertEquals("warning", violationList.get(0).get("severity"));
    }

    @Test
    public void autoRoute_shouldIncludeViolationNextSteps_whenForceMode() throws Exception {
        List<RoutingViolationDto> violations = List.of(
                new RoutingViolationDto("c-1", "Src", "Tgt", "element_crossing", "warning"));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 5, 0, "orthogonal", false,
                        List.of(), List.of(), List.of(), violations), null));
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("force", true);
        Map<String, Object> result = callAndParse("auto-route-connections", args);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should mention constraint violations",
                nextSteps.stream().anyMatch(s -> s.contains("constraint violation")));
    }

    @Test
    public void autoRoute_shouldOmitViolationsInDefaultMode() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null));
        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));
        Map<String, Object> data = getResult(result);
        assertNull("violations should be absent in default mode", data.get("violations"));
    }

    // ---- auto-route-connections autoNudge tests ----

    @Test
    public void autoRoute_shouldPassAutoNudgeToAccessor() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertTrue("autoNudge should be true when passed", autoNudge);
            return new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null);
        });

        callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "autoNudge", true));
    }

    @Test
    public void autoRoute_shouldDefaultAutoNudgeToFalse() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertFalse("autoNudge should default to false", autoNudge);
            return new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null);
        });

        callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));
    }

    @Test
    public void autoRoute_shouldPassSnapThresholdToAccessor() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertEquals("snapThreshold should be 35 when passed", 35, snapThreshold);
            return new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null);
        });

        callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "snapThreshold", 35));
    }

    @Test
    public void autoRoute_shouldDefaultSnapThresholdTo20() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertEquals("snapThreshold should default to 20", 20, snapThreshold);
            return new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null);
        });

        callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));
    }

    @Test
    public void autoRoute_shouldReturnNudgedElements_whenAutoNudgeApplied() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 8, 0, "orthogonal", false, 0,
                        List.of(), List.of(), List.of(), List.of(),
                        List.of(new NudgedElementDto("vo-1", "Element A", 50, 0),
                                new NudgedElementDto("vo-2", "Element B", 0, -40))), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "autoNudge", true));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
        assertEquals(8, ((Number) data.get("connectionsRouted")).intValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nudged = (List<Map<String, Object>>) data.get("nudgedElements");
        assertNotNull("nudgedElements should be present", nudged);
        assertEquals(2, nudged.size());
        assertEquals("vo-1", nudged.get(0).get("viewObjectId"));
        assertEquals("Element A", nudged.get(0).get("elementName"));
        assertEquals(50, ((Number) nudged.get(0).get("deltaX")).intValue());
        assertEquals(0, ((Number) nudged.get(0).get("deltaY")).intValue());
    }

    @Test
    public void autoRoute_shouldOmitNudgedElements_whenEmpty() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "autoNudge", true));

        Map<String, Object> data = getResult(result);
        assertNull("nudgedElements should be omitted when empty", data.get("nudgedElements"));
    }

    @Test
    public void autoRoute_shouldIncludeNudgeInfoInNextSteps() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 8, 0, "orthogonal", false, 0,
                        List.of(), List.of(), List.of(), List.of(),
                        List.of(new NudgedElementDto("vo-1", "El A", 50, 0))), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "autoNudge", true));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        boolean hasNudgeStep = nextSteps.stream().anyMatch(s -> s.contains("nudge"));
        assertTrue("nextSteps should mention nudged elements", hasNudgeStep);
    }

    @Test
    public void autoRoute_shouldIgnoreAutoNudge_whenForceIsTrue() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertTrue("force should be true", force);
            assertTrue("autoNudge should be passed as true", autoNudge);
            // Implementation ignores autoNudge when force=true (effectiveAutoNudge = autoNudge && !force)
            return new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null);
        });

        callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "force", true, "autoNudge", true));
    }

    // ---- auto-route terminals-only mode parameter validation ----

    @Test
    public void autoRoute_terminalsOnly_shouldPassModeParam() throws Exception {
        // 4 routed, 7 skipped broken down as: 3 already-orthogonal + 2 obstacle + 2 crossing.
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertEquals("terminals-only", mode);
            return new MutationResult<>(new AutoRouteResultDto(
                    vId, 4, 0, "orthogonal", false, 0, 0, 0, 0, 7, 2, 2, 0, 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "mode", "terminals-only"));

        Map<String, Object> data = getResult(result);
        assertEquals(4, ((Number) data.get("connectionsRouted")).intValue());
        assertEquals(7, ((Number) data.get("connectionsSkipped")).intValue());
        assertEquals(2, ((Number) data.get("vetoedByObstacle")).intValue());
        assertEquals(2, ((Number) data.get("vetoedByCrossing")).intValue());
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should mention terminals-only",
                nextSteps.stream().anyMatch(s -> s.contains("terminals-only")));
        assertTrue("nextSteps should break out the three skip categories",
                nextSteps.stream().anyMatch(s -> s.contains("already orthogonal")
                        && s.contains("vetoed")));
        assertTrue("nextSteps should mention force=true escape hatch when vetoes > 0",
                nextSteps.stream().anyMatch(s -> s.contains("force=true")));
    }

    @Test
    public void autoRoute_terminalsOnly_shouldSurfaceInteriorVeto() throws Exception {
        // 2 routed, 6 skipped = 1 already-orthogonal + 2 obstacle + 1 crossing + 2 interior.
        // alreadyOrtho = skipped - obstacle - crossing - interior = 6 - 2 - 1 - 2 = 1.
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertEquals("terminals-only", mode);
            return new MutationResult<>(new AutoRouteResultDto(
                    vId, 2, 0, "orthogonal", false, 0, 0, 0, 0, 6, 2, 1, 2, 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "mode", "terminals-only"));

        Map<String, Object> data = getResult(result);
        assertEquals(6, ((Number) data.get("connectionsSkipped")).intValue());
        assertEquals(2, ((Number) data.get("vetoedByInterior")).intValue());
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should report the interior-termination veto category",
                nextSteps.stream().anyMatch(s -> s.contains("terminate inside an element")));
        assertTrue("nextSteps should compute alreadyOrtho net of the interior veto",
                nextSteps.stream().anyMatch(s -> s.contains("1 already orthogonal")));
        assertTrue("nextSteps should offer the force=true escape hatch when vetoes > 0",
                nextSteps.stream().anyMatch(s -> s.contains("force=true")));
    }

    @Test
    public void autoRoute_terminalsOnly_shouldSurfaceZigzagVeto() throws Exception {
        // 5 skipped = 1 already-orthogonal + 1 obstacle + 1 crossing + 0 interior + 2 zigzag.
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertEquals("terminals-only", mode);
            return new MutationResult<>(new AutoRouteResultDto(
                    vId, 3, 0, "orthogonal", false, 0, 0, 0, 0, 5, 1, 1, 0, 2,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "mode", "terminals-only"));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("vetoedByZigzag")).intValue());
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should report the zigzag veto category",
                nextSteps.stream().anyMatch(s -> s.contains("introduce a zigzag/reversal")));
        assertTrue("nextSteps should compute alreadyOrtho net of all vetoes",
                nextSteps.stream().anyMatch(s -> s.contains("1 already orthogonal")));
        assertTrue("nextSteps should offer the force=true escape hatch",
                nextSteps.stream().anyMatch(s -> s.contains("force=true")));
    }

    @Test
    public void autoRoute_terminalsOnly_shouldOmitAlreadyOrthogonal_whenAllSkippedAreVetoes() throws Exception {
        // 6 skipped = 0 already-orthogonal + 2 obstacle + 1 crossing + 3 interior + 0 zigzag.
        // alreadyOrtho = 0 → the breakdown must omit "already orthogonal" and the first listed
        // category (obstacle) must use a space separator, not a leading comma.
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertEquals("terminals-only", mode);
            return new MutationResult<>(new AutoRouteResultDto(
                    vId, 0, 0, "orthogonal", false, 0, 0, 0, 0, 6, 2, 1, 3, 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "mode", "terminals-only"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("First category should follow the colon with a space, not a comma",
                nextSteps.stream().anyMatch(s ->
                        s.contains("(terminals-only mode): 2 vetoed (L-bend would cross")));
        assertTrue("Breakdown must omit 'already orthogonal' when none are",
                nextSteps.stream().noneMatch(s -> s.contains("already orthogonal")));
    }

    @Test
    public void autoRoute_terminalsOnly_shouldRejectClearStrategy() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            throw new ModelAccessException(
                    "strategy 'clear' cannot be combined with mode 'terminals-only'"
                            + " — they are mutually exclusive",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("auto-route-connections",
                Map.of("viewId", "v-1", "strategy", "clear", "mode", "terminals-only"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("Should mention INVALID_PARAMETER",
                content.contains("INVALID_PARAMETER"));
        assertTrue("Should explain mutual exclusion",
                content.contains("mutually exclusive"));
    }

    @Test
    public void autoRoute_terminalsOnly_shouldRejectAutoNudge() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            throw new ModelAccessException(
                    "autoNudge cannot be combined with mode 'terminals-only'"
                            + " — terminals-only never moves elements",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("auto-route-connections",
                Map.of("viewId", "v-1", "autoNudge", true, "mode", "terminals-only"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("Should mention INVALID_PARAMETER",
                content.contains("INVALID_PARAMETER"));
        assertTrue("Should explain that terminals-only never moves elements",
                content.contains("never moves elements"));
    }

    @Test
    public void autoRoute_terminalsOnly_shouldForwardForceTrue() throws Exception {
        // force=true must propagate into terminals-only so the accessor
        // can bypass the obstacle + crossing veto.
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertEquals("terminals-only", mode);
            assertTrue("force should propagate into terminals-only mode", force);
            return new MutationResult<AutoRouteResultDto>(
                    new AutoRouteResultDto(vId, 5, "orthogonal", false), null);
        });

        callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "mode", "terminals-only", "force", true));
    }

    @Test
    public void autoRoute_terminalsOnly_shouldHonourConnectionIdsFilter() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
            assertEquals("terminals-only", mode);
            assertNotNull("connectionIds filter should propagate", connIds);
            assertEquals(2, connIds.size());
            return new MutationResult<>(new AutoRouteResultDto(
                    vId, 1, 0, "orthogonal", false, 0, 0, 0, 0, 1, 0, 0, 0, 0,
                    List.of("Connection not found on view: c-bogus"),
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "mode", "terminals-only",
                        "connectionIds", List.of("c-1", "c-bogus")));

        Map<String, Object> data = getResult(result);
        assertEquals(1, ((Number) data.get("connectionsRouted")).intValue());
        assertEquals(1, ((Number) data.get("connectionsSkipped")).intValue());
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) data.get("warnings");
        assertNotNull("Warnings should be present", warnings);
        assertEquals(1, warnings.size());
    }

    // ---- auto-layout-and-route (targetRating) ----

    @Test
    public void autoLayoutAndRoute_shouldReturnResultWithoutTargetRating() throws Exception {
        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals("v-1", data.get("viewId"));
        assertEquals("DOWN", data.get("direction"));
        assertEquals(50, ((Number) data.get("spacing")).intValue());
        assertEquals(5, ((Number) data.get("elementsRepositioned")).intValue());
        assertEquals(3, ((Number) data.get("connectionsRouted")).intValue());
        // targetRating fields should be absent (null → omitted by @JsonInclude)
        assertNull("targetRating should be absent", data.get("targetRating"));
        assertNull("achievedRating should be absent", data.get("achievedRating"));
        assertNull("iterationsPerformed should be absent", data.get("iterationsPerformed"));
        assertNull("assessmentSummary should be absent", data.get("assessmentSummary"));
    }

    @Test
    public void autoLayoutAndRoute_shouldRejectInvalidTargetRating() throws Exception {
        McpSchema.CallToolResult result = callTool("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "poor"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
        assertTrue(content.contains("targetRating"));
    }

    @Test
    public void autoLayoutAndRoute_shouldRejectNotApplicableTargetRating() throws Exception {
        McpSchema.CallToolResult result = callTool("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "not-applicable"));

        assertTrue("Should be error", result.isError());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldIncludeQualityFieldsWhenTargetRatingUsed() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8,
                        tr, "good", 2,
                        new AutoLayoutAssessmentSummaryDto(
                                0, 5, 45.5, 70, "good",
                                List.of("No improvements needed."))), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        Map<String, Object> data = getResult(result);
        assertEquals("good", data.get("targetRating"));
        assertEquals("good", data.get("achievedRating"));
        assertEquals(2, ((Number) data.get("iterationsPerformed")).intValue());
        Map<String, Object> summary = (Map<String, Object>) data.get("assessmentSummary");
        assertNotNull("assessmentSummary should be present", summary);
        assertEquals(0, ((Number) summary.get("overlapCount")).intValue());
        assertEquals(5, ((Number) summary.get("edgeCrossingCount")).intValue());
    }

    @Test
    public void autoLayoutAndRoute_shouldOmitAssessLayoutFromNextStepsWhenTargetRatingUsed() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8,
                        tr, "good", 1,
                        new AutoLayoutAssessmentSummaryDto(
                                0, 2, 50.0, 80, "good", null)), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        boolean hasAssessLayout = nextSteps.stream()
                .anyMatch(s -> s.contains("assess-layout"));
        assertFalse("Should NOT suggest assess-layout when targetRating used", hasAssessLayout);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldIncludeTargetMissGuidanceInNextSteps() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8,
                        tr, "fair", 5,
                        new AutoLayoutAssessmentSummaryDto(
                                1, 15, 30.0, 50, "fair",
                                List.of("Increase spacing."))), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        boolean hasTargetMiss = nextSteps.stream()
                .anyMatch(s -> s.contains("not achieved") && s.contains("fair"));
        assertTrue("Should include target miss guidance", hasTargetMiss);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldReturnResultWithTargetMetOnFirstIteration() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8,
                        tr, "good", 1,
                        new AutoLayoutAssessmentSummaryDto(
                                0, 3, 50.0, 75, "good", null)), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "fair"));

        Map<String, Object> data = getResult(result);
        assertEquals("fair", data.get("targetRating"));
        assertEquals("good", data.get("achievedRating"));
        assertEquals(1, ((Number) data.get("iterationsPerformed")).intValue());

        // When target is exceeded on first iteration, nextSteps should NOT contain
        // target miss guidance and should NOT suggest assess-layout
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        boolean hasTargetMiss = nextSteps.stream()
                .anyMatch(s -> s.contains("not achieved"));
        assertFalse("Should NOT include target miss guidance when target exceeded", hasTargetMiss);
        boolean hasAssessLayout = nextSteps.stream()
                .anyMatch(s -> s.contains("assess-layout"));
        assertFalse("Should NOT suggest assess-layout when targetRating used", hasAssessLayout);
    }

    // ---- auto-layout-and-route limiting factor ----

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldIncludeLimitingFactorWhenTargetMissed() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8, 0, 0,
                        tr, "fair", 5,
                        new AutoLayoutAssessmentSummaryDto(
                                1, 15, 30.0, 50, "fair",
                                List.of("Increase spacing.")),
                        "edgeCrossings",
                        "Run optimize-group-order to reduce inter-group crossings, "
                                + "or reposition hub elements manually"), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        Map<String, Object> data = getResult(result);
        assertEquals("edgeCrossings", data.get("limitingFactor"));
        assertNotNull("suggestedRemediation should be present", data.get("suggestedRemediation"));
        assertTrue(((String) data.get("suggestedRemediation")).contains("optimize-group-order"));
    }

    @Test
    public void autoLayoutAndRoute_shouldOmitLimitingFactorWhenTargetMet() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8, 0, 0,
                        tr, "good", 1,
                        new AutoLayoutAssessmentSummaryDto(
                                0, 2, 50.0, 80, "good", null),
                        null, null), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        Map<String, Object> data = getResult(result);
        assertNull("limitingFactor should be absent when target met", data.get("limitingFactor"));
        assertNull("suggestedRemediation should be absent when target met", data.get("suggestedRemediation"));
    }

    @Test
    public void autoLayoutAndRoute_shouldOmitLimitingFactorWithoutTargetRating() throws Exception {
        // Default behavior (no targetRating) — uses the 7-arg constructor
        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertNull("limitingFactor should be absent without targetRating", data.get("limitingFactor"));
        assertNull("suggestedRemediation should be absent without targetRating", data.get("suggestedRemediation"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldSelectWorstMetricAsLimitingFactor() throws Exception {
        // labelOverlaps=fair, edgeCrossings=good → labelOverlaps is worse
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8, 0, 0,
                        tr, "fair", 3,
                        new AutoLayoutAssessmentSummaryDto(
                                0, 5, 45.0, 70, "fair",
                                List.of("Fix label overlaps.")),
                        "labelOverlaps",
                        "Use update-view-connection to set labelPosition "
                                + "(source/middle/target) on overlapping labels, "
                                + "or suppress labels with showLabel=false"), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        Map<String, Object> data = getResult(result);
        assertEquals("labelOverlaps", data.get("limitingFactor"));
        assertTrue(((String) data.get("suggestedRemediation")).contains("labelPosition"));
    }

    @Test
    public void autoLayoutAndRoute_backwardCompatibleConstructorsStillWork() {
        // 7-arg (single pass, no quality target)
        AutoLayoutAndRouteResultDto dto7 = new AutoLayoutAndRouteResultDto(
                "v-1", "DOWN", 50, 5, 3, false, 8);
        assertNull(dto7.limitingFactor());
        assertNull(dto7.suggestedRemediation());
        assertNull(dto7.targetRating());

        // 11-arg (quality target, no labels)
        AutoLayoutAndRouteResultDto dto11 = new AutoLayoutAndRouteResultDto(
                "v-1", "DOWN", 50, 5, 3, false, 8,
                "good", "good", 1,
                null);
        assertNull(dto11.limitingFactor());
        assertNull(dto11.suggestedRemediation());

        // 12-arg (quality target + labels, no fallback)
        AutoLayoutAndRouteResultDto dto12 = new AutoLayoutAndRouteResultDto(
                "v-1", "DOWN", 50, 5, 3, false, 8, 2,
                "good", "fair", 3,
                null);
        assertNull(dto12.limitingFactor());
        assertNull(dto12.suggestedRemediation());

        // 13-arg (quality target + labels + fallback, no limiting factor)
        AutoLayoutAndRouteResultDto dto13 = new AutoLayoutAndRouteResultDto(
                "v-1", "DOWN", 50, 5, 3, false, 8, 2, 1,
                "good", "fair", 3,
                null);
        assertNull(dto13.limitingFactor());
        assertNull(dto13.suggestedRemediation());

        // 15-arg (full constructor with limiting factor)
        AutoLayoutAndRouteResultDto dto15 = new AutoLayoutAndRouteResultDto(
                "v-1", "DOWN", 50, 5, 3, false, 8, 2, 1,
                "good", "fair", 3,
                null, "overlaps", "Increase spacing");
        assertEquals("overlaps", dto15.limitingFactor());
        assertEquals("Increase spacing", dto15.suggestedRemediation());
    }

    // ---- auto-layout-and-route mode parameter ----

    @Test
    public void autoLayoutAndRoute_shouldDefaultToAutoModeWhenOmitted() throws Exception {
        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals("auto", data.get("mode"));
    }

    @Test
    public void autoLayoutAndRoute_shouldAcceptExplicitAutoMode() throws Exception {
        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "auto"));

        Map<String, Object> data = getResult(result);
        assertEquals("auto", data.get("mode"));
    }

    @Test
    public void autoLayoutAndRoute_shouldRejectInvalidMode() throws Exception {
        McpSchema.CallToolResult result = callTool("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "invalid"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
        assertTrue(content.contains("mode"));
    }

    @Test
    public void autoLayoutAndRoute_shouldReturnErrorForGroupedModeOnFlatView() throws Exception {
        // flat-view guard — accessor throws INVALID_PARAMETER when no groups exist
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) -> {
            if ("grouped".equals(m)) {
                throw new ModelAccessException(
                        "mode='grouped' requires a view with groups. "
                        + "Use mode='auto' (default) for flat views.",
                        ErrorCode.INVALID_PARAMETER);
            }
            return new MutationResult<>(new AutoLayoutAndRouteResultDto(
                    vId, "DOWN", sp, 5, 3, false, 8), null);
        });

        McpSchema.CallToolResult result = callTool("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped"));

        assertTrue("Should be error for grouped mode on flat view", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
        assertTrue(content.contains("mode='grouped' requires a view with groups"));
    }

    @Test
    public void autoLayoutAndRoute_shouldPassModeToAccessor() throws Exception {
        final String[] capturedMode = {null};
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) -> {
            capturedMode[0] = m;
            return new MutationResult<>(new AutoLayoutAndRouteResultDto(
                    vId, "DOWN", sp, 5, 3, false, 8), null);
        });

        callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped"));

        assertEquals("grouped", capturedMode[0]);
    }

    @Test
    public void autoLayoutAndRoute_shouldPassNullModeWhenOmitted() throws Exception {
        final String[] capturedMode = {"NOT_NULL"};
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) -> {
            capturedMode[0] = m;
            return new MutationResult<>(new AutoLayoutAndRouteResultDto(
                    vId, "DOWN", sp, 5, 3, false, 8), null);
        });

        callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1"));

        assertNull("mode should be null when omitted", capturedMode[0]);
    }

    @Test
    public void autoLayoutAndRoute_shouldIncludeGroupsArrangedInGroupedMode() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "grouped", "DOWN", sp, 5, 3, true, 12,
                        4, 0, 0, null, null, null, null, null, null), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped"));

        Map<String, Object> data = getResult(result);
        assertEquals("grouped", data.get("mode"));
        assertEquals(4, ((Number) data.get("groupsArranged")).intValue());
    }

    @Test
    public void autoLayoutAndRoute_shouldOmitGroupsArrangedWhenZero() throws Exception {
        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        // groupsArranged=0 should be omitted by @JsonInclude(NON_DEFAULT)
        assertNull("groupsArranged should be omitted when 0", data.get("groupsArranged"));
    }

    @Test
    public void autoLayoutAndRoute_backwardCompatConstructorsIncludeAutoMode() {
        // All backward-compatible constructors should set mode="auto"
        AutoLayoutAndRouteResultDto dto7 = new AutoLayoutAndRouteResultDto(
                "v-1", "DOWN", 50, 5, 3, false, 8);
        assertEquals("auto", dto7.mode());
        assertEquals(0, dto7.groupsArranged());

        AutoLayoutAndRouteResultDto dto15 = new AutoLayoutAndRouteResultDto(
                "v-1", "DOWN", 50, 5, 3, false, 8, 2, 1,
                "good", "fair", 3,
                null, "overlaps", "Increase spacing");
        assertEquals("auto", dto15.mode());
        assertEquals(0, dto15.groupsArranged());
    }

    // ---- auto-connect-view ----

    @Test
    public void shouldRegisterAutoConnectViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "auto-connect-view".equals(spec.tool().name()));
        assertTrue("auto-connect-view tool should be registered", found);
    }

    @Test
    public void autoConnect_shouldConnectAllRelationships() throws Exception {
        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
        assertEquals("v-1", data.get("viewId"));
        assertEquals(3, ((Number) data.get("connectionsCreated")).intValue());
        assertEquals(1, ((Number) data.get("connectionsSkipped")).intValue());
        @SuppressWarnings("unchecked")
        List<String> relIds = (List<String>) data.get("relationshipIdsConnected");
        assertEquals(3, relIds.size());
    }

    @Test
    public void autoConnect_shouldReturnErrorWhenViewIdMissing() throws Exception {
        McpSchema.CallToolResult result = callTool("auto-connect-view",
                Map.of());

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("viewId"));
    }

    @Test
    public void autoConnect_shouldPassElementIdsFilter() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            int count = (elemIds != null) ? elemIds.size() : 0;
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, count, 0, List.of("r-1"), List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1", "elementIds", List.of("e-1", "e-2")));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsCreated")).intValue());
    }

    @Test
    public void autoConnect_shouldPassRelationshipTypesFilter() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            int count = (relTypes != null) ? relTypes.size() : 0;
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, count, 0, List.of("r-1"), List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1", "relationshipTypes",
                        List.of("ServingRelationship")));

        Map<String, Object> data = getResult(result);
        assertEquals(1, ((Number) data.get("connectionsCreated")).intValue());
    }

    @Test
    public void autoConnect_shouldReturnZeroWhenNoConnections() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) ->
                new MutationResult<>(new AutoConnectResultDto(
                        vId, 0, 0, List.of(), List.of()), null));

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals(0, ((Number) data.get("connectionsCreated")).intValue());
        assertEquals(0, ((Number) data.get("connectionsSkipped")).intValue());
    }

    @Test
    public void autoConnect_shouldReturnProposalInApprovalMode() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) ->
                new MutationResult<>(null, null, new ProposalContext("p-42",
                        "Auto-connect view " + vId,
                        Instant.parse("2026-03-04T00:00:00Z"))));

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-42", proposal.get("proposalId"));
    }

    @Test
    public void autoConnect_shouldReturnErrorOnInvalidRelationshipType() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            throw new ModelAccessException(
                    "Invalid ArchiMate relationship type: BogusRelationship",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("auto-connect-view",
                Map.of("viewId", "v-1", "relationshipTypes",
                        List.of("BogusRelationship")));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    // ---- auto-connect-view showLabel ----

    @Test
    public void autoConnect_shouldPassShowLabelFalseToAccessor() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            assertNotNull("showLabel should be passed", (Object) sl);
            assertFalse("showLabel should be false", sl.booleanValue());
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, 2, 0, List.of("r-1", "r-2"), List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1", "showLabel", false));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsCreated")).intValue());
    }

    @Test
    public void autoConnect_shouldPassShowLabelTrueToAccessor() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            assertNotNull("showLabel should be passed", (Object) sl);
            assertTrue("showLabel should be true", sl.booleanValue());
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, 2, 0, List.of("r-1", "r-2"), List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1", "showLabel", true));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsCreated")).intValue());
    }

    @Test
    public void autoConnect_shouldPassNullShowLabelWhenOmitted() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            assertNull("showLabel should be null when omitted", (Object) sl);
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, 2, 0, List.of("r-1", "r-2"), List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsCreated")).intValue());
    }

    // ---- auto-connect-view styling ----

    @Test
    public void autoConnect_shouldPassStylingParamsToAccessor() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            assertNotNull("styling should be passed", sty);
            assertEquals("#0066CC", sty.lineColor());
            assertEquals("#FFFFFF", sty.fontColor());
            assertEquals(Integer.valueOf(2), sty.lineWidth());
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, 3, 0, List.of("r-1", "r-2", "r-3"), List.of()), null);
        });

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewId", "v-1");
        args.put("lineColor", "#0066CC");
        args.put("fontColor", "#FFFFFF");
        args.put("lineWidth", 2);
        Map<String, Object> result = callAndParse("auto-connect-view", args);

        Map<String, Object> data = getResult(result);
        assertEquals(3, ((Number) data.get("connectionsCreated")).intValue());
    }

    @Test
    public void autoConnect_shouldPassNullStylingWhenOmitted() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            assertNull("styling should be null when omitted", sty);
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, 2, 0, List.of("r-1", "r-2"), List.of()), null);
        });

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsCreated")).intValue());
    }

    @Test
    public void autoConnect_shouldPassLineColorOnlyToAccessor() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            assertNotNull("styling should be passed", sty);
            assertEquals("#FF8800", sty.lineColor());
            assertNull("fontColor should be null", sty.fontColor());
            assertNull("lineWidth should be null", sty.lineWidth());
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, 2, 0, List.of("r-1", "r-2"), List.of()), null);
        });

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewId", "v-1");
        args.put("lineColor", "#FF8800");
        Map<String, Object> result = callAndParse("auto-connect-view", args);

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsCreated")).intValue());
    }

    @Test
    public void autoConnect_shouldRejectInvalidLineColor() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            throw new ModelAccessException(
                    "Invalid colour format: 'red'. Expected #RRGGBB hex format.",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("auto-connect-view",
                Map.of("viewId", "v-1", "lineColor", "red"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void autoConnect_shouldRejectOutOfRangeLineWidth() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            throw new ModelAccessException(
                    "lineWidth must be between 1 and 3, got: 5",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("auto-connect-view",
                Map.of("viewId", "v-1", "lineWidth", 5));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void autoConnect_shouldCombineStylingWithFilters() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            assertNotNull("elementIds should be passed", elemIds);
            assertEquals(1, elemIds.size());
            assertNotNull("relTypes should be passed", relTypes);
            assertEquals(1, relTypes.size());
            assertNotNull("showLabel should be passed", (Object) sl);
            assertFalse("showLabel should be false", sl.booleanValue());
            assertNotNull("styling should be passed", sty);
            assertEquals("#0066CC", sty.lineColor());
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, 1, 0, List.of("r-1"), List.of()), null);
        });

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewId", "v-1");
        args.put("elementIds", List.of("e-1"));
        args.put("relationshipTypes", List.of("FlowRelationship"));
        args.put("showLabel", false);
        args.put("lineColor", "#0066CC");
        Map<String, Object> result = callAndParse("auto-connect-view", args);

        Map<String, Object> data = getResult(result);
        assertEquals(1, ((Number) data.get("connectionsCreated")).intValue());
    }

    @Test
    public void autoConnect_shouldPassEmptyStringLineColorToAccessor() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) -> {
            assertNotNull("styling should be passed", sty);
            assertEquals("lineColor should be empty string", "", sty.lineColor());
            assertNull("fontColor should be null", sty.fontColor());
            assertNull("lineWidth should be null", sty.lineWidth());
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, 2, 0, List.of("r-1", "r-2"), List.of()), null);
        });

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewId", "v-1");
        args.put("lineColor", "");
        Map<String, Object> result = callAndParse("auto-connect-view", args);

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsCreated")).intValue());
    }

    // ---- layout-within-group ----

    @Test
    public void shouldRegisterLayoutWithinGroupTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "layout-within-group".equals(spec.tool().name()));
        assertTrue("layout-within-group tool should be registered", found);
    }

    @Test
    public void layoutWithinGroup_shouldParseRowArrangementAndCallAccessor() throws Exception {
        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
        assertEquals("v-1", data.get("viewId"));
        assertEquals("g-1", data.get("groupViewObjectId"));
        assertEquals("row", data.get("arrangement"));
        assertEquals(4, ((Number) data.get("elementsRepositioned")).intValue());
    }

    @Test
    public void layoutWithinGroup_shouldParseColumnArrangement() throws Exception {
        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "column"));

        Map<String, Object> data = getResult(result);
        assertEquals("column", data.get("arrangement"));
    }

    @Test
    public void layoutWithinGroup_shouldParseGridArrangement() throws Exception {
        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "grid"));

        Map<String, Object> data = getResult(result);
        assertEquals("grid", data.get("arrangement"));
    }

    @Test
    public void layoutWithinGroup_shouldRequireViewId() throws Exception {
        McpSchema.CallToolResult result = callTool("layout-within-group",
                Map.of("groupViewObjectId", "g-1", "arrangement", "row"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("viewId"));
    }

    @Test
    public void layoutWithinGroup_shouldRequireGroupViewObjectId() throws Exception {
        McpSchema.CallToolResult result = callTool("layout-within-group",
                Map.of("viewId", "v-1", "arrangement", "row"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("groupViewObjectId"));
    }

    @Test
    public void layoutWithinGroup_shouldRequireArrangement() throws Exception {
        McpSchema.CallToolResult result = callTool("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("arrangement"));
    }

    @Test
    public void layoutWithinGroup_shouldRejectInvalidArrangement() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            throw new ModelAccessException(
                    "Invalid arrangement: 'bogus'. Valid values: row, column, grid.",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "bogus"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void layoutWithinGroup_shouldPassOptionalParams() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            // Verify optional params were passed through
            assertEquals(Integer.valueOf(15), sp);
            assertEquals(Integer.valueOf(5), pad);
            assertEquals(Integer.valueOf(120), ew);
            assertEquals(Integer.valueOf(55), eh);
            assertTrue(ar);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 3, true, 300, 200, false, false, null, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "row");
        args.put("spacing", 15);
        args.put("padding", 5);
        args.put("elementWidth", 120);
        args.put("elementHeight", 55);
        args.put("autoResize", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(true, data.get("groupResized"));
        assertEquals(300, ((Number) data.get("newGroupWidth")).intValue());
        assertEquals(200, ((Number) data.get("newGroupHeight")).intValue());
    }

    @Test
    public void layoutWithinGroup_shouldHandleApprovalMode() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) ->
                new MutationResult<>(null, null, new ProposalContext("p-50",
                        "Layout within group " + gvoId,
                        Instant.parse("2026-03-04T00:00:00Z"))));

        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-50", proposal.get("proposalId"));
    }

    @Test
    public void layoutWithinGroup_shouldHandleBatchMode() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) ->
                new MutationResult<>(new LayoutWithinGroupResultDto(
                        vId, gvoId, arr, 4, false, null, null, false, false, null, 0), 7));

        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);

        // Check nextSteps mentions batch
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("Should mention batch",
                nextSteps.stream().anyMatch(s -> s.contains("batch")));
    }

    @Test
    public void layoutWithinGroup_shouldIncludeNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull("Should have nextSteps", nextSteps);
        assertTrue("Should mention export-view",
                nextSteps.stream().anyMatch(s -> s.contains("export-view")));
    }

    // ---- autoWidth tests ----

    @Test
    public void layoutWithinGroup_shouldParseAutoWidthParam() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            assertTrue("autoWidth should be true", aw);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, true, null, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "row");
        args.put("autoWidth", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(true, data.get("autoWidth"));
    }

    @Test
    public void layoutWithinGroup_shouldPassAutoWidthWithElementWidthOverride() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            // Handler passes both; accessor decides precedence
            assertEquals(Integer.valueOf(150), ew);
            assertTrue("autoWidth should be true from handler", aw);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, false, null, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "row");
        args.put("elementWidth", 150);
        args.put("autoWidth", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        assertNotNull(result);
    }

    @Test
    public void layoutWithinGroup_shouldReportAutoWidthInResponse() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            assertTrue("autoWidth should be passed as true from handler", aw);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, aw, null, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "column");
        args.put("autoWidth", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(true, data.get("autoWidth"));
    }

    // ---- columns + recursive tests ----

    @Test
    public void layoutWithinGroup_shouldPassColumnsParam() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            assertEquals(Integer.valueOf(4), cols);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 12, false, null, null, false, false, 4, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "grid");
        args.put("columns", 4);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(4, ((Number) data.get("columnsUsed")).intValue());
    }

    @Test
    public void layoutWithinGroup_shouldPassRecursiveParam() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            assertTrue("recursive should be true", rec);
            assertTrue("autoResize should be true", ar);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, true, 300, 200, false, false, null, 2), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "row");
        args.put("autoResize", true);
        args.put("recursive", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("ancestorsResized")).intValue());
    }

    @Test
    public void layoutWithinGroup_shouldDefaultColumnsToNull() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            assertNull("columns should be null by default", cols);
            assertFalse("recursive should be false by default", rec);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, false, null, 0), null);
        });

        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "grid"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
    }

    @Test
    public void layoutWithinGroup_shouldReportAncestorsResizedInResponse() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) ->
                new MutationResult<>(new LayoutWithinGroupResultDto(
                        vId, gvoId, arr, 4, true, 300, 200, false, false, null, 3), null));

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "column");
        args.put("autoResize", true);
        args.put("recursive", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(3, ((Number) data.get("ancestorsResized")).intValue());
        assertEquals(true, data.get("groupResized"));
    }

    // ---- Element-to-element nesting tests ----

    @Test
    public void shouldAddToView_withElementParent() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            assertEquals("vo-parent", pvoId);
            ViewObjectDto vo = new ViewObjectDto(
                    "vo-child", eId, "Child Element", "ApplicationFunction", 30, 30, 120, 55);
            return new MutationResult<>(new AddToViewResultDto(vo, null), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-child");
        args.put("x", 30);
        args.put("y", 30);
        args.put("parentViewObjectId", "vo-parent");
        Map<String, Object> result = callAndParse("add-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        @SuppressWarnings("unchecked")
        Map<String, Object> viewObject = (Map<String, Object>) entity.get("viewObject");
        assertNotNull(viewObject);
        assertEquals("vo-child", viewObject.get("viewObjectId"));
    }

    @Test
    public void shouldReturnError_whenParentIsNote() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new ModelAccessException(
                    "Parent view object must be a group or element: " + pvoId,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "parentViewObjectId must reference a group or element view object, not a DiagramModelNote",
                    null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("parentViewObjectId", "note-1");
        McpSchema.CallToolResult result = callTool("add-to-view", args);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("Should mention INVALID_PARAMETER", content.contains("INVALID_PARAMETER"));
        assertTrue("Should mention group or element", content.contains("group or element"));
    }

    @Test
    public void shouldReturnError_whenParentIsConnection() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new ModelAccessException(
                    "Parent view object must be a group or element: " + pvoId,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "parentViewObjectId must reference a group or element view object, not a DiagramModelConnection",
                    null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("parentViewObjectId", "conn-1");
        McpSchema.CallToolResult result = callTool("add-to-view", args);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("Should mention INVALID_PARAMETER", content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void shouldAddGroupToView_withElementParent() throws Exception {
        accessor.setAddGroupToViewBehavior((sid, vId, label, x, y, w, h, pvoId) -> {
            assertEquals("vo-element-parent", pvoId);
            ViewGroupDto dto = new ViewGroupDto(
                    "vg-1", label, 30, 30, 300, 200, "vo-element-parent", null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("label", "Nested Group");
        args.put("x", 30);
        args.put("y", 30);
        args.put("parentViewObjectId", "vo-element-parent");
        Map<String, Object> result = callAndParse("add-group-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vo-element-parent", entity.get("parentViewObjectId"));
    }

    @Test
    public void shouldAddNoteToView_withElementParent() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, pos, gap2, x, y, w, h, pvoId) -> {
            assertEquals("vo-element-parent", pvoId);
            ViewNoteDto dto = new ViewNoteDto(
                    "vn-1", content, 30, 30, 185, 80, "vo-element-parent");
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("content", "Nested Note");
        args.put("x", 30);
        args.put("y", 30);
        args.put("parentViewObjectId", "vo-element-parent");
        Map<String, Object> result = callAndParse("add-note-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vo-element-parent", entity.get("parentViewObjectId"));
    }

    // ---- add-note-to-view position tests ----

    @Test
    public void addNote_positionAboveContent_shouldPassPositionToAccessor() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, pos, gap2, x, y, w, h, pvoId) -> {
            assertEquals("above-content", pos);
            assertNull(gap2);
            assertNull(x);
            assertNull(y);
            ViewNoteDto dto = new ViewNoteDto("vn-1", content, 100, 50, 185, 80, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("content", "Title");
        args.put("position", "above-content");
        Map<String, Object> result = callAndParse("add-note-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vn-1", entity.get("viewObjectId"));
    }

    @Test
    public void addNote_positionBelowContent_shouldPassPositionAndGap() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, pos, gap2, x, y, w, h, pvoId) -> {
            assertEquals("below-content", pos);
            assertEquals(Integer.valueOf(20), gap2);
            ViewNoteDto dto = new ViewNoteDto("vn-1", content, 100, 500, 185, 80, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("content", "Footer");
        args.put("position", "below-content");
        args.put("gap", 20);
        Map<String, Object> result = callAndParse("add-note-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
    }

    @Test
    public void addNote_positionWithExplicitXY_shouldPassBothToAccessor() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, pos, gap2, x, y, w, h, pvoId) -> {
            assertEquals("above-content", pos);
            // Handler passes both — accessor decides precedence
            assertEquals(Integer.valueOf(50), x);
            assertEquals(Integer.valueOf(60), y);
            ViewNoteDto dto = new ViewNoteDto("vn-1", content, 100, 10, 185, 80, null,
                    null, null, null, null, null,
                    "position='above-content' takes precedence over explicit x/y coordinates");
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("content", "Title");
        args.put("position", "above-content");
        args.put("x", 50);
        args.put("y", 60);
        Map<String, Object> result = callAndParse("add-note-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertNotNull("Should have position note", entity.get("note"));
    }

    @Test
    public void addNote_positionWithParentViewObjectId_shouldReturnError() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("content", "Title");
        args.put("position", "above-content");
        args.put("parentViewObjectId", "vg-1");

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-note-to-view")
                .arguments(args)
                .build();

        McpSchema.CallToolResult result = handler.handleAddNoteToView(null, request);
        assertTrue("Should be error", result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("INVALID_PARAMETER"));
    }

    @Test
    public void addNote_gapParameter_shouldPassToAccessor() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, pos, gap2, x, y, w, h, pvoId) -> {
            assertEquals("above-content", pos);
            assertEquals(Integer.valueOf(25), gap2);
            ViewNoteDto dto = new ViewNoteDto("vn-1", content, 100, 15, 185, 80, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("content", "Title");
        args.put("position", "above-content");
        args.put("gap", 25);
        Map<String, Object> result = callAndParse("add-note-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
    }

    // ---- arrange-groups tests ----

    @Test
    public void shouldRegisterArrangeGroupsTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "arrange-groups".equals(spec.tool().name()));
        assertTrue("arrange-groups tool should be registered", found);
    }

    @Test
    public void arrangeGroups_shouldReturnResult() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "grid");
        args.put("columns", 3);
        args.put("spacing", 80);
        Map<String, Object> result = callAndParse("arrange-groups", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals(6, entity.get("groupsPositioned"));
        assertEquals(800, entity.get("layoutWidth"));
        assertEquals(600, entity.get("layoutHeight"));
        assertEquals(3, entity.get("columnsUsed"));
        assertEquals("grid", entity.get("arrangement"));
    }

    @Test
    public void arrangeGroups_viewNotFound_shouldReturnError() throws Exception {
        accessor.setArrangeGroupsBehavior((sid, vId, arr, cols, sp, gids, dir) -> {
            throw new ModelAccessException("View not found: " + vId, ErrorCode.VIEW_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "nonexistent");
        args.put("arrangement", "row");
        McpSchema.CallToolResult result = callTool("arrange-groups", args);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void arrangeGroupsNextSteps_shouldIncludeLayoutWithinGroupGuidance() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "row");
        Map<String, Object> result = callAndParse("arrange-groups", args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Should mention layout-within-group",
                nextSteps.stream().anyMatch(s -> s.contains("layout-within-group")));
        assertTrue("Should mention auto-route-connections",
                nextSteps.stream().anyMatch(s -> s.contains("auto-route-connections")));
    }

    @Test
    public void arrangeGroups_shouldRequireViewId() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("arrangement", "grid");
        McpSchema.CallToolResult result = callTool("arrange-groups", args);
        assertTrue("Should be error for missing viewId", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void arrangeGroups_shouldRequireArrangement() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        McpSchema.CallToolResult result = callTool("arrange-groups", args);
        assertTrue("Should be error for missing arrangement", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void arrangeGroups_shouldPassDirectionToAccessor() throws Exception {
        final String[] capturedDirection = {null};
        accessor.setArrangeGroupsBehavior((sid, vId, arr, cols, sp, gids, dir) -> {
            capturedDirection[0] = dir;
            return new MutationResult<>(new ArrangeGroupsResultDto(
                    vId, 3, 800, 200, null, arr,
                    sp != null ? sp : 40, null), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "topology");
        args.put("direction", "horizontal");
        Map<String, Object> result = callAndParse("arrange-groups", args);

        assertNotNull(result);
        assertEquals("horizontal", capturedDirection[0]);
    }

    @Test
    public void arrangeGroups_shouldPassNullDirectionWhenOmitted() throws Exception {
        final String[] capturedDirection = {"sentinel"};
        accessor.setArrangeGroupsBehavior((sid, vId, arr, cols, sp, gids, dir) -> {
            capturedDirection[0] = dir;
            return new MutationResult<>(new ArrangeGroupsResultDto(
                    vId, 3, 800, 200, null, arr,
                    sp != null ? sp : 40, null), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "topology");
        Map<String, Object> result = callAndParse("arrange-groups", args);

        assertNotNull(result);
        assertNull(capturedDirection[0]);
    }

    // ---- optimize-group-order ----

    @Test
    public void shouldRegisterOptimizeGroupOrderTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "optimize-group-order".equals(spec.tool().name()));
        assertTrue("optimize-group-order tool should be registered", found);
    }

    @Test
    public void optimizeGroupOrder_shouldReturnResult() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "row");

        Map<String, Object> result = callAndParse("optimize-group-order", args);
        Map<String, Object> entity = getResult(result);

        assertEquals("v-1", entity.get("viewId"));
        assertNotNull(entity.get("crossingsBefore"));
        assertNotNull(entity.get("crossingsAfter"));
        assertNotNull(entity.get("reductionPercent"));
    }

    @Test
    public void optimizeGroupOrder_shouldRequireViewId() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("arrangement", "row");
        McpSchema.CallToolResult result = callTool("optimize-group-order", args);
        assertTrue("Should be error for missing viewId", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void optimizeGroupOrder_shouldWorkWithoutArrangement() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        // arrangement is now optional — auto-detection applies
        Map<String, Object> result = callAndParse("optimize-group-order", args);
        Map<String, Object> entity = getResult(result);
        assertEquals("v-1", entity.get("viewId"));
    }

    @Test
    public void optimizeGroupOrder_viewNotFound_shouldReturnError() throws Exception {
        accessor.setOptimizeGroupOrderBehavior((sid, vId, arr, sp, pad, ew, eh, aw, cols, ga) -> {
            throw new ModelAccessException("View not found: " + vId, ErrorCode.VIEW_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "nonexistent");
        args.put("arrangement", "row");

        McpSchema.CallToolResult result = callTool("optimize-group-order", args);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void optimizeGroupOrder_nextStepsShouldIncludeAutoRouteGuidance() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "row");

        Map<String, Object> result = callAndParse("optimize-group-order", args);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull("nextSteps should not be null", nextSteps);
        assertTrue("nextSteps should mention auto-route-connections",
                nextSteps.stream().anyMatch(s -> s.contains("auto-route-connections")));
    }

    @Test
    public void optimizeGroupOrder_shouldIncludeArrangementFieldsInResponse() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "row");

        Map<String, Object> result = callAndParse("optimize-group-order", args);
        Map<String, Object> entity = getResult(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) entity.get("groupDetails");
        assertNotNull("groupDetails should not be null", groups);
        assertFalse("groupDetails should not be empty", groups.isEmpty());

        Map<String, Object> firstGroup = groups.get(0);
        assertNotNull("arrangementUsed should be present", firstGroup.get("arrangementUsed"));
        assertNotNull("arrangementSource should be present", firstGroup.get("arrangementSource"));
    }

    @Test
    public void optimizeGroupOrder_shouldPassGroupArrangementsToAccessor() throws Exception {
        final Map<String, String> capturedGA = new HashMap<>();
        accessor.setOptimizeGroupOrderBehavior((sid, vId, arr, sp, pad, ew, eh, aw, cols, ga) -> {
            if (ga != null) capturedGA.putAll(ga);
            return new MutationResult<>(new OptimizeGroupOrderResultDto(
                    vId, 5, 2, 60.0, 2, 4, List.of(
                            new OptimizeGroupOrderResultDto.GroupDetail(
                                    "g-1", "Group 1", 3, true, "row", "override"),
                            new OptimizeGroupOrderResultDto.GroupDetail(
                                    "g-2", "Group 2", 2, true, "grid", "detected")
                    )), null);
        });

        Map<String, Object> gaMap = new LinkedHashMap<>();
        gaMap.put("g-1", "row");

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupArrangements", gaMap);

        Map<String, Object> result = callAndParse("optimize-group-order", args);
        Map<String, Object> entity = getResult(result);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals("row", capturedGA.get("g-1"));
    }

    @Test
    public void optimizeGroupOrder_shouldRejectInvalidGroupArrangementValue() throws Exception {
        accessor.setOptimizeGroupOrderBehavior((sid, vId, arr, sp, pad, ew, eh, aw, cols, ga) -> {
            throw new ModelAccessException(
                    "Invalid arrangement 'diagonal' for group 'g-1'. Must be 'row', 'column', or 'grid'.",
                    ErrorCode.INVALID_PARAMETER);
        });

        Map<String, Object> gaMap = new LinkedHashMap<>();
        gaMap.put("g-1", "diagonal");

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupArrangements", gaMap);

        McpSchema.CallToolResult result = callTool("optimize-group-order", args);
        assertTrue("Should be error for invalid groupArrangement value", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    // ---- adjust-view-spacing ----

    @Test
    public void adjustViewSpacing_allDeltas_shouldReturnCombinedResult() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("interElementDelta", 40);
        args.put("paddingDelta", 10);
        args.put("interGroupDelta", 60);

        Map<String, Object> result = callAndParse("adjust-view-spacing", args);
        Map<String, Object> entity = getResult(result);

        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals(3, entity.get("groupsAdjusted"));
        assertEquals(9, entity.get("elementsRepositioned"));
        assertEquals(5, entity.get("connectionsRouted"));
        assertEquals("good", entity.get("overallRating"));
        assertEquals(0, entity.get("coincidentSegmentCount"));
    }

    @Test
    public void adjustViewSpacing_singleDelta_shouldSucceed() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("interElementDelta", 30);

        Map<String, Object> result = callAndParse("adjust-view-spacing", args);
        Map<String, Object> entity = getResult(result);

        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
    }

    @Test
    public void adjustViewSpacing_missingViewId_shouldReturnError() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("interElementDelta", 40);

        McpSchema.CallToolResult result = callTool("adjust-view-spacing", args);
        assertTrue("Should be error for missing viewId", result.isError());
    }

    @Test
    public void adjustViewSpacing_recursiveDefault_shouldBeTrue() throws Exception {
        final boolean[] capturedRecursive = {false};
        accessor.setAdjustViewSpacingBehavior((sid, vId, ied, pd, igd, rec) -> {
            capturedRecursive[0] = rec;
            return new MutationResult<>(new AdjustViewSpacingResultDto(
                    vId, 1, 3, 0, 0, 0, 0, "good", null, 0, 0, 80.0, List.of(),
                    /*resolvedInterElementDelta=*/ 20,
                    /*defaultResolutionReason=*/ null), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("interElementDelta", 20);

        callAndParse("adjust-view-spacing", args);
        assertTrue("recursive should default to true", capturedRecursive[0]);
    }

    @Test
    public void adjustViewSpacing_recursiveFalse_shouldPassThrough() throws Exception {
        final boolean[] capturedRecursive = {true};
        accessor.setAdjustViewSpacingBehavior((sid, vId, ied, pd, igd, rec) -> {
            capturedRecursive[0] = rec;
            return new MutationResult<>(new AdjustViewSpacingResultDto(
                    vId, 1, 3, 0, 0, 0, 0, "good", null, 0, 0, 80.0, List.of(),
                    /*resolvedInterElementDelta=*/ 20,
                    /*defaultResolutionReason=*/ null), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("interElementDelta", 20);
        args.put("recursive", false);

        callAndParse("adjust-view-spacing", args);
        assertFalse("recursive=false should pass through", capturedRecursive[0]);
    }

    @Test
    public void adjustViewSpacing_responseStructure_shouldContainRoutingAndAssessment()
            throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("interElementDelta", 40);

        Map<String, Object> result = callAndParse("adjust-view-spacing", args);
        Map<String, Object> entity = getResult(result);

        // Verify routing metrics present
        assertNotNull(entity.get("connectionsRouted"));
        assertNotNull(entity.get("crossingsBefore"));
        assertNotNull(entity.get("crossingsAfter"));
        // Verify assessment summary present
        assertNotNull(entity.get("overallRating"));
        assertNotNull(entity.get("averageSpacing"));
        assertNotNull(entity.get("suggestions"));
    }

    @Test
    public void adjustViewSpacing_modelNotLoaded_shouldReturnError() throws Exception {
        accessor = new StubViewPlacementAccessor(false);
        // callTool invokes the handler method directly, so swapping in the no-model
        // handler is enough — re-registering its tools onto the already-populated
        // setUp() registry would be a redundant duplicate registration.
        handler = new ViewPlacementHandler(accessor, formatter, registry, null);

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("interElementDelta", 40);

        McpSchema.CallToolResult result = callTool("adjust-view-spacing", args);
        assertTrue("Should be error when model not loaded", result.isError());
    }

    @Test
    public void shouldRegisterAdjustViewSpacingTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "adjust-view-spacing".equals(spec.tool().name()));
        assertTrue("adjust-view-spacing tool should be registered", found);
    }

    // ---- Stubs ----

    @FunctionalInterface
    interface ArrangeGroupsBehavior {
        MutationResult<ArrangeGroupsResultDto> apply(String sessionId, String viewId,
                String arrangement, Integer columns, Integer spacing, List<String> groupIds,
                String direction);
    }

    @FunctionalInterface
    interface OptimizeGroupOrderBehavior {
        MutationResult<OptimizeGroupOrderResultDto> apply(String sessionId, String viewId,
                String arrangement, Integer spacing, Integer padding,
                Integer elementWidth, Integer elementHeight, boolean autoWidth, Integer columns,
                Map<String, String> groupArrangements);
    }

    interface LayoutWithinGroupBehavior {
        MutationResult<LayoutWithinGroupResultDto> apply(String sessionId, String viewId,
                String groupViewObjectId, String arrangement, Integer spacing,
                Integer padding, Integer elementWidth, Integer elementHeight,
                boolean autoResize, boolean autoWidth, Integer columns, boolean recursive);
    }

    @FunctionalInterface
    interface AutoConnectViewBehavior {
        MutationResult<AutoConnectResultDto> apply(String sessionId, String viewId,
                List<String> elementIds, List<String> relationshipTypes,
                Boolean showLabel, StylingParams styling);
    }

    @FunctionalInterface
    interface AutoLayoutAndRouteBehavior {
        MutationResult<AutoLayoutAndRouteResultDto> apply(String sessionId, String viewId,
                String mode, String direction, int spacing, String targetRating);
    }

    @FunctionalInterface
    interface AutoRouteConnectionsBehavior {
        MutationResult<AutoRouteResultDto> apply(String sessionId, String viewId,
                List<String> connectionIds, String strategy, boolean force,
                boolean autoNudge, int snapThreshold, int perimeterMargin, String mode);
    }

    @FunctionalInterface
    interface DetectHubElementsBehavior {
        DetectHubElementsResultDto apply(String viewId);
    }

    @FunctionalInterface
    interface LayoutFlatViewBehavior {
        MutationResult<LayoutFlatViewResultDto> apply(String sessionId, String viewId,
                String arrangement, Integer spacing, Integer padding,
                String sortBy, String categoryField, Integer columns,
                boolean autoLayoutChildren);
    }

    @FunctionalInterface
    interface AssessLayoutBehavior {
        AssessLayoutResultDto apply(String viewId);
    }

    @FunctionalInterface
    interface AddToViewBehavior {
        MutationResult<AddToViewResultDto> apply(String sessionId, String viewId,
                String elementId, Integer x, Integer y, Integer width, Integer height,
                boolean autoConnect, String parentViewObjectId);
    }

    @FunctionalInterface
    interface AddGroupToViewBehavior {
        MutationResult<ViewGroupDto> apply(String sessionId, String viewId,
                String label, Integer x, Integer y, Integer width, Integer height,
                String parentViewObjectId);
    }

    @FunctionalInterface
    interface AddNoteToViewBehavior {
        MutationResult<ViewNoteDto> apply(String sessionId, String viewId,
                String content, String position, Integer gap,
                Integer x, Integer y, Integer width, Integer height,
                String parentViewObjectId);
    }

    @FunctionalInterface
    interface AddConnectionBehavior {
        MutationResult<ViewConnectionDto> apply(String sessionId, String viewId,
                String relationshipId, String sourceViewObjectId, String targetViewObjectId,
                List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints);
    }

    /**
     * Intentionally narrower than the accessor's full 10-arg signature: styling,
     * imageParams, and labelExpression are captured into {@code last*} fields on
     * the stub before delegating to this behavior, so existing test lambdas (using
     * the 7-arg shape) keep working without rewrites. New per-call assertions on
     * those trailing params read from the capture fields directly.
     */
    @FunctionalInterface
    interface UpdateViewObjectBehavior {
        MutationResult<ViewObjectDto> apply(String sessionId, String viewObjectId,
                Integer x, Integer y, Integer width, Integer height, String text);
    }

    @FunctionalInterface
    interface UpdateViewConnectionBehavior {
        MutationResult<ViewConnectionDto> apply(String sessionId, String viewConnectionId,
                List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints);
    }

    @FunctionalInterface
    interface RemoveFromViewBehavior {
        MutationResult<RemoveFromViewResultDto> apply(String sessionId, String viewId,
                String viewObjectId);
    }

    @FunctionalInterface
    interface ClearViewBehavior {
        MutationResult<ClearViewResultDto> apply(String sessionId, String viewId);
    }

    @FunctionalInterface
    interface ApplyViewLayoutBehavior {
        MutationResult<ApplyViewLayoutResultDto> apply(String sessionId, String viewId,
                List<ViewPositionSpec> positions, List<ViewConnectionSpec> connections,
                String description);
    }

    @FunctionalInterface
    interface AdjustViewSpacingBehavior {
        MutationResult<AdjustViewSpacingResultDto> apply(String sessionId, String viewId,
                Integer interElementDelta, Integer paddingDelta,
                Integer interGroupDelta, boolean recursive);
    }

    private static class StubViewPlacementAccessor extends BaseTestAccessor {

        private AddToViewBehavior addToViewBehavior;
        private AddGroupToViewBehavior addGroupToViewBehavior;
        private AddNoteToViewBehavior addNoteToViewBehavior;
        private AddConnectionBehavior addConnectionBehavior;
        private UpdateViewObjectBehavior updateViewObjectBehavior;
        private UpdateViewConnectionBehavior updateViewConnectionBehavior;
        private RemoveFromViewBehavior removeFromViewBehavior;
        private ClearViewBehavior clearViewBehavior;
        private ApplyViewLayoutBehavior applyViewLayoutBehavior;
        private AssessLayoutBehavior assessLayoutBehavior;
        private AutoConnectViewBehavior autoConnectViewBehavior;
        private AutoLayoutAndRouteBehavior autoLayoutAndRouteBehavior;
        private AutoRouteConnectionsBehavior autoRouteConnectionsBehavior;
        private LayoutWithinGroupBehavior layoutWithinGroupBehavior;
        private ArrangeGroupsBehavior arrangeGroupsBehavior;
        private OptimizeGroupOrderBehavior optimizeGroupOrderBehavior;
        private DetectHubElementsBehavior detectHubElementsBehavior;
        private LayoutFlatViewBehavior layoutFlatViewBehavior;
        private AdjustViewSpacingBehavior adjustViewSpacingBehavior;

        // Capture last styling params passed to each method (for assertion in tests)
        StylingParams lastUpdateViewObjectStyling;
        StylingParams lastAddToViewStyling;
        StylingParams lastAddGroupToViewStyling;
        StylingParams lastAddNoteToViewStyling;
        StylingParams lastUpdateViewConnectionStyling;
        StylingParams lastAutoConnectViewStyling;
        // capture last labelExpression param passed to update-view-object.
        String lastUpdateViewObjectLabelExpression;
        // capture last anchor params passed to update-view-object.
        String lastUpdateViewObjectAnchorTarget;
        String lastUpdateViewObjectAnchorEdge;
        Integer lastUpdateViewObjectAnchorDx;
        Integer lastUpdateViewObjectAnchorDy;
        // capture last includeViolatorIds parameter
        boolean lastAssessLayoutIncludeViolatorIds;

        StubViewPlacementAccessor() {
            super(true);
            resetBehaviors();
        }

        StubViewPlacementAccessor(boolean modelLoaded) {
            super(modelLoaded);
            resetBehaviors();
        }

        void setAddToViewBehavior(AddToViewBehavior behavior) {
            this.addToViewBehavior = behavior;
        }

        void setAddGroupToViewBehavior(AddGroupToViewBehavior behavior) {
            this.addGroupToViewBehavior = behavior;
        }

        void setAddNoteToViewBehavior(AddNoteToViewBehavior behavior) {
            this.addNoteToViewBehavior = behavior;
        }

        void setAddConnectionBehavior(AddConnectionBehavior behavior) {
            this.addConnectionBehavior = behavior;
        }

        void setUpdateViewObjectBehavior(UpdateViewObjectBehavior behavior) {
            this.updateViewObjectBehavior = behavior;
        }

        void setUpdateViewConnectionBehavior(UpdateViewConnectionBehavior behavior) {
            this.updateViewConnectionBehavior = behavior;
        }

        void setRemoveFromViewBehavior(RemoveFromViewBehavior behavior) {
            this.removeFromViewBehavior = behavior;
        }

        void setClearViewBehavior(ClearViewBehavior behavior) {
            this.clearViewBehavior = behavior;
        }

        void setApplyViewLayoutBehavior(ApplyViewLayoutBehavior behavior) {
            this.applyViewLayoutBehavior = behavior;
        }

        void setAssessLayoutBehavior(AssessLayoutBehavior behavior) {
            this.assessLayoutBehavior = behavior;
        }

        void setAutoConnectViewBehavior(AutoConnectViewBehavior behavior) {
            this.autoConnectViewBehavior = behavior;
        }

        void setAutoLayoutAndRouteBehavior(AutoLayoutAndRouteBehavior behavior) {
            this.autoLayoutAndRouteBehavior = behavior;
        }

        void setAutoRouteConnectionsBehavior(AutoRouteConnectionsBehavior behavior) {
            this.autoRouteConnectionsBehavior = behavior;
        }

        void setLayoutWithinGroupBehavior(LayoutWithinGroupBehavior behavior) {
            this.layoutWithinGroupBehavior = behavior;
        }

        void setArrangeGroupsBehavior(ArrangeGroupsBehavior behavior) {
            this.arrangeGroupsBehavior = behavior;
        }

        void setOptimizeGroupOrderBehavior(OptimizeGroupOrderBehavior behavior) {
            this.optimizeGroupOrderBehavior = behavior;
        }

        void setDetectHubElementsBehavior(DetectHubElementsBehavior behavior) {
            this.detectHubElementsBehavior = behavior;
        }

        void setLayoutFlatViewBehavior(LayoutFlatViewBehavior behavior) {
            this.layoutFlatViewBehavior = behavior;
        }

        void setAdjustViewSpacingBehavior(AdjustViewSpacingBehavior behavior) {
            this.adjustViewSpacingBehavior = behavior;
        }

        private void resetBehaviors() {
            this.addToViewBehavior = (sid, vId, eId, x, y, w, h, ac, pvoId) -> {
                int rx = (x != null) ? x : 50;
                int ry = (y != null) ? y : 50;
                int rw = (w != null) ? w : 120;
                int rh = (h != null) ? h : 55;
                ViewObjectDto vo = new ViewObjectDto(
                        "vo-1", eId, "Element Name", "BusinessActor", rx, ry, rw, rh);
                return new MutationResult<>(new AddToViewResultDto(vo, null), null);
            };
            this.addGroupToViewBehavior = (sid, vId, label, x, y, w, h, pvoId) -> {
                int rx = (x != null) ? x : 50;
                int ry = (y != null) ? y : 50;
                int rw = (w != null) ? w : 300;
                int rh = (h != null) ? h : 200;
                ViewGroupDto dto = new ViewGroupDto("vg-1", label, rx, ry, rw, rh, null, null);
                return new MutationResult<>(dto, null);
            };
            this.addNoteToViewBehavior = (sid, vId, content, pos, gap, x, y, w, h, pvoId) -> {
                int rx = (x != null) ? x : 50;
                int ry = (y != null) ? y : 50;
                int rw = (w != null) ? w : 185;
                int rh = (h != null) ? h : 80;
                ViewNoteDto dto = new ViewNoteDto("vn-1", content, rx, ry, rw, rh, null);
                return new MutationResult<>(dto, null);
            };
            this.addConnectionBehavior = (sid, vId, relId, src, tgt, bps, absBps) -> {
                ViewConnectionDto dto = new ViewConnectionDto(
                        "vc-1", relId, "ServingRelationship", src, tgt, null);
                return new MutationResult<>(dto, null);
            };
            this.updateViewObjectBehavior = (sid, voId, x, y, w, h, txt) -> {
                int rx = (x != null) ? x : 50;
                int ry = (y != null) ? y : 50;
                int rw = (w != null) ? w : 120;
                int rh = (h != null) ? h : 55;
                ViewObjectDto dto = new ViewObjectDto(
                        voId, "e-1", "Element Name", "BusinessActor", rx, ry, rw, rh);
                return new MutationResult<>(dto, null);
            };
            this.updateViewConnectionBehavior = (sid, vcId, bps, absBps) -> {
                ViewConnectionDto dto = new ViewConnectionDto(
                        vcId, "rel-1", "ServingRelationship", "vo-1", "vo-2", bps);
                return new MutationResult<>(dto, null);
            };
            this.removeFromViewBehavior = (sid, vId, voId) -> {
                RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                        voId, "viewObject", null);
                return new MutationResult<>(dto, null);
            };
            this.clearViewBehavior = (sid, vId) -> {
                ClearViewResultDto dto = new ClearViewResultDto(
                        vId, "Test View", 3, 1, 0);
                return new MutationResult<>(dto, null);
            };
            this.applyViewLayoutBehavior = (sid, vId, pos, conns, desc) -> {
                int posCount = (pos != null) ? pos.size() : 0;
                int connCount = (conns != null) ? conns.size() : 0;
                ApplyViewLayoutResultDto dto = new ApplyViewLayoutResultDto(
                        vId, posCount, connCount, posCount + connCount);
                return new MutationResult<>(dto, null);
            };
            this.assessLayoutBehavior = (vId) -> new AssessLayoutResultDto(
                    vId, 5, 3, 0, 0, 2, 0.67, 45.5, 70, "good", null,
                    null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                    0, null, 0, null, 0, null, null,
                    List.of("Layout quality is good — no immediate improvements needed."));
            this.autoLayoutAndRouteBehavior = (sid, vId, m, dir, sp, tr) -> {
                String d = (dir != null) ? dir.toUpperCase() : "DOWN";
                int s = sp > 0 ? sp : 50;
                return new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, d, s, 5, 3, false, 8), null);
            };
            this.autoRouteConnectionsBehavior = (sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) -> {
                String s = (strategy != null) ? strategy : "orthogonal";
                return new MutationResult<>(new AutoRouteResultDto(vId, 5, s, false), null);
            };
            this.autoConnectViewBehavior = (sid, vId, elemIds, relTypes, sl, sty) ->
                    new MutationResult<>(new AutoConnectResultDto(
                            vId, 3, 1, List.of("r-1", "r-2", "r-3"), List.of()), null);
            this.layoutWithinGroupBehavior = (sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) ->
                    new MutationResult<>(new LayoutWithinGroupResultDto(
                            vId, gvoId, arr, 4, ar, ar ? 300 : null, ar ? 200 : null, false, aw, null, 0), null);
            this.arrangeGroupsBehavior = (sid, vId, arr, cols, sp, gids, dir) ->
                    new MutationResult<>(new ArrangeGroupsResultDto(
                            vId, 6, 800, 600,
                            "grid".equals(arr) ? (cols != null ? cols : 3) : null,
                            arr,
                            sp != null ? sp : 40, null), null);
            this.optimizeGroupOrderBehavior = (sid, vId, arr, sp, pad, ew, eh, aw, cols, ga) ->
                    new MutationResult<>(new OptimizeGroupOrderResultDto(
                            vId, 5, 2, 60.0, 2, 4, List.of(
                                    new OptimizeGroupOrderResultDto.GroupDetail(
                                            "g-1", "Group 1", 3, true,
                                            arr != null ? arr : "column", arr != null ? "fallback" : "detected"),
                                    new OptimizeGroupOrderResultDto.GroupDetail(
                                            "g-2", "Group 2", 2, true,
                                            arr != null ? arr : "row", arr != null ? "fallback" : "detected")
                            )), null);
            this.layoutFlatViewBehavior = (sid, vId, arr, sp, pad, sb, cf, cols, alc) ->
                    new MutationResult<>(new LayoutFlatViewResultDto(
                            vId, arr, 6, 0, sb, cf,
                            cf != null ? List.of("Application", "Business") : null,
                            "grid".equals(arr) ? (cols != null ? cols : 3) : null), null);
            this.adjustViewSpacingBehavior = (sid, vId, ied, pd, igd, rec) ->
                    new MutationResult<>(new AdjustViewSpacingResultDto(
                            vId, 3, 9, 5, 0, 12, 8, "good",
                            Map.of("overlaps", "excellent", "crossings", "good",
                                    "coincidentSegments", "excellent"),
                            0, 2, 85.5,
                            List.of("Layout quality is good — no immediate improvements needed."),
                            /*resolvedInterElementDelta=*/ (ied != null ? ied : 0),
                            /*defaultResolutionReason=*/ null),
                            null);
            this.detectHubElementsBehavior = (vId) -> new DetectHubElementsResultDto(
                    vId, 5, 8, 3.2,
                    List.of(
                            new HubElementEntryDto("vo-1", "e-1", "API Gateway",
                                    "ApplicationComponent", 8, 120, 55, 0),
                            new HubElementEntryDto("vo-2", "e-2", "ESB",
                                    "ApplicationComponent", 4, 120, 55, 0),
                            new HubElementEntryDto("vo-3", "e-3", "Database",
                                    "ApplicationComponent", 2, 120, 55, 0)),
                    List.of("Element 'API Gateway' has 8 connections (hub threshold: 6). "
                            + "Consider increasing height to 85px (55 + 15 \u00d7 2) for horizontal layouts, "
                            + "or width to 150px (120 + 15 \u00d7 2) for vertical layouts."));
        }

        @Override
        public MutationResult<AddToViewResultDto> addToView(String sessionId, String viewId,
                String elementId, Integer x, Integer y, Integer width, Integer height,
                boolean autoConnect, String parentViewObjectId, StylingParams styling, ImageParams imageParams) {
            this.lastAddToViewStyling = styling;
            return addToViewBehavior.apply(sessionId, viewId, elementId, x, y, width, height,
                    autoConnect, parentViewObjectId);
        }

        @Override
        public MutationResult<ViewGroupDto> addGroupToView(String sessionId, String viewId,
                String label, Integer x, Integer y, Integer width, Integer height,
                String parentViewObjectId, StylingParams styling, ImageParams imageParams) {
            this.lastAddGroupToViewStyling = styling;
            return addGroupToViewBehavior.apply(sessionId, viewId, label, x, y, width, height,
                    parentViewObjectId);
        }

        @Override
        public MutationResult<ViewNoteDto> addNoteToView(String sessionId, String viewId,
                String content, String position, Integer gap, Integer x, Integer y,
                Integer width, Integer height,
                String parentViewObjectId, StylingParams styling, ImageParams imageParams) {
            this.lastAddNoteToViewStyling = styling;
            return addNoteToViewBehavior.apply(sessionId, viewId, content, position, gap,
                    x, y, width, height, parentViewObjectId);
        }

        @Override
        public MutationResult<ViewConnectionDto> addConnectionToView(String sessionId,
                String viewId, String relationshipId, String sourceViewObjectId,
                String targetViewObjectId, List<BendpointDto> bendpoints,
                List<AbsoluteBendpointDto> absoluteBendpoints,
                StylingParams styling, Boolean showLabel, Integer textPosition) {
            return addConnectionBehavior.apply(sessionId, viewId, relationshipId,
                    sourceViewObjectId, targetViewObjectId, bendpoints, absoluteBendpoints);
        }

        @Override
        public MutationResult<ViewObjectDto> updateViewObject(String sessionId,
                String viewObjectId, Integer x, Integer y, Integer width, Integer height,
                String text, StylingParams styling, ImageParams imageParams,
                String labelExpression, String anchorTarget, String anchorEdge,
                Integer anchorDx, Integer anchorDy) {
            this.lastUpdateViewObjectStyling = styling;
            this.lastUpdateViewObjectLabelExpression = labelExpression;
            this.lastUpdateViewObjectAnchorTarget = anchorTarget;
            this.lastUpdateViewObjectAnchorEdge = anchorEdge;
            this.lastUpdateViewObjectAnchorDx = anchorDx;
            this.lastUpdateViewObjectAnchorDy = anchorDy;
            return updateViewObjectBehavior.apply(sessionId, viewObjectId, x, y, width, height,
                    text);
        }

        @Override
        public MutationResult<ViewConnectionDto> updateViewConnection(String sessionId,
                String viewConnectionId, List<BendpointDto> bendpoints,
                List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling,
                Boolean showLabel, Integer textPosition) {
            this.lastUpdateViewConnectionStyling = styling;
            return updateViewConnectionBehavior.apply(sessionId, viewConnectionId,
                    bendpoints, absoluteBendpoints);
        }

        @Override
        public MutationResult<RemoveFromViewResultDto> removeFromView(String sessionId,
                String viewId, String viewObjectId) {
            return removeFromViewBehavior.apply(sessionId, viewId, viewObjectId);
        }

        @Override
        public MutationResult<ClearViewResultDto> clearView(String sessionId, String viewId) {
            return clearViewBehavior.apply(sessionId, viewId);
        }

        @Override
        public MutationResult<ApplyViewLayoutResultDto> applyViewLayout(String sessionId,
                String viewId, List<ViewPositionSpec> positions,
                List<ViewConnectionSpec> connections, String description) {
            return applyViewLayoutBehavior.apply(sessionId, viewId, positions, connections,
                    description);
        }

        // Views returned by getViews(null) for scope="all-views" assess tests.
        private List<ViewDto> stubViews = List.of();

        void setViews(List<ViewDto> views) {
            this.stubViews = views;
        }

        @Override
        public List<ViewDto> getViews(String viewpointFilter) {
            return stubViews;
        }

        @Override
        public AssessLayoutResultDto assessLayout(String viewId) {
            return assessLayoutBehavior.apply(viewId);
        }

        @Override
        public AssessLayoutResultDto assessLayout(String viewId, boolean includeViolatorIds) {
            this.lastAssessLayoutIncludeViolatorIds = includeViolatorIds;
            return assessLayoutBehavior.apply(viewId);
        }

        @Override
        public DetectHubElementsResultDto detectHubElements(String viewId) {
            return detectHubElementsBehavior.apply(viewId);
        }

        @Override
        public MutationResult<AutoLayoutAndRouteResultDto> autoLayoutAndRoute(
                String sessionId, String viewId, String mode,
                String direction, int spacing, String targetRating) {
            return autoLayoutAndRouteBehavior.apply(sessionId, viewId, mode, direction, spacing, targetRating);
        }

        @Override
        public MutationResult<AutoRouteResultDto> autoRouteConnections(
                String sessionId, String viewId,
                List<String> connectionIds, String strategy, boolean force,
                boolean autoNudge, int snapThreshold, int perimeterMargin, String mode) {
            return autoRouteConnectionsBehavior.apply(sessionId, viewId, connectionIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode);
        }

        @Override
        public MutationResult<AutoRouteResultDto> autoRouteConnections(
                String sessionId, String viewId,
                List<String> connectionIds, String strategy, boolean force,
                boolean autoNudge, int snapThreshold, int perimeterMargin, String mode,
                boolean enableChannelNudging) {
            return autoRouteConnectionsBehavior.apply(sessionId, viewId, connectionIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode);
        }

        @Override
        public MutationResult<AutoConnectResultDto> autoConnectView(
                String sessionId, String viewId,
                List<String> elementIds, List<String> relationshipTypes,
                Boolean showLabel, StylingParams styling) {
            lastAutoConnectViewStyling = styling;
            return autoConnectViewBehavior.apply(sessionId, viewId, elementIds,
                    relationshipTypes, showLabel, styling);
        }

        @Override
        public MutationResult<LayoutWithinGroupResultDto> layoutWithinGroup(
                String sessionId, String viewId, String groupViewObjectId,
                String arrangement, Integer spacing, Integer padding,
                Integer elementWidth, Integer elementHeight, boolean autoResize,
                boolean autoWidth, Integer columns, boolean recursive) {
            return layoutWithinGroupBehavior.apply(sessionId, viewId, groupViewObjectId,
                    arrangement, spacing, padding, elementWidth, elementHeight, autoResize,
                    autoWidth, columns, recursive);
        }

        @Override
        public MutationResult<ArrangeGroupsResultDto> arrangeGroups(
                String sessionId, String viewId, String arrangement,
                Integer columns, Integer spacing, List<String> groupIds,
                String direction) {
            return arrangeGroupsBehavior.apply(sessionId, viewId, arrangement,
                    columns, spacing, groupIds, direction);
        }

        @Override
        public MutationResult<LayoutFlatViewResultDto> layoutFlatView(
                String sessionId, String viewId, String arrangement,
                Integer spacing, Integer padding, String sortBy,
                String categoryField, Integer columns,
                boolean autoLayoutChildren) {
            return layoutFlatViewBehavior.apply(sessionId, viewId, arrangement,
                    spacing, padding, sortBy, categoryField, columns, autoLayoutChildren);
        }

        @Override
        public MutationResult<OptimizeGroupOrderResultDto> optimizeGroupOrder(
                String sessionId, String viewId, String arrangement,
                Integer spacing, Integer padding, Integer elementWidth,
                Integer elementHeight, boolean autoWidth, Integer columns,
                Map<String, String> groupArrangements) {
            return optimizeGroupOrderBehavior.apply(sessionId, viewId, arrangement,
                    spacing, padding, elementWidth, elementHeight, autoWidth, columns,
                    groupArrangements);
        }

        @Override
        public MutationResult<AdjustViewSpacingResultDto> adjustViewSpacing(
                String sessionId, String viewId,
                Integer interElementDelta, Integer paddingDelta,
                Integer interGroupDelta, boolean recursive) {
            return adjustViewSpacingBehavior.apply(sessionId, viewId,
                    interElementDelta, paddingDelta, interGroupDelta, recursive);
        }
    }
}
