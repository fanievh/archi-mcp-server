package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.AutoRouteWarningsProbe;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.DispatchArm;
import net.vheerden.archi.mcp.model.HubSizingSuggestionBuilder;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.PassThroughAndCrowdingRemedyTest;
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
import net.vheerden.archi.mcp.response.dto.HiddenLabelDto;
import net.vheerden.archi.mcp.response.dto.NudgedElementDto;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.DetectHubElementsResultDto;
import net.vheerden.archi.mcp.response.dto.HubElementEntryDto;
import net.vheerden.archi.mcp.response.dto.LayoutFlatViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.ResizedGroupDto;
import net.vheerden.archi.mcp.response.dto.RoutingViolationDto;
import net.vheerden.archi.mcp.response.dto.SkippedContainerDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ApplyElementSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyGroupSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplySpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
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

    /**
     * {@code add-image-to-view}'s imagePath description published the shape
     * {@code 'images/<sha1>.png'} and, in the same sentence, named the {@code IMAGE_NOT_FOUND}
     * rejection — so it invited a caller to construct or validate a path against a shape that can
     * never match, then named the error that follows. Archi mints the path as {@code images/} + a
     * generated identifier + the source file's lower-cased extension: neither a content hash nor
     * reliably {@code .png}.
     */
    @Test
    public void addImageToView_imagePathShouldBeDescribedAsOpaque() {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) registry.getToolSpecifications().stream()
                .filter(spec -> "add-image-to-view".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool()
                .inputSchema()
                .properties();
        @SuppressWarnings("unchecked")
        Map<String, Object> imagePathProp = (Map<String, Object>) properties.get("imagePath");
        String desc = (String) imagePathProp.get("description");

        assertFalse("must not describe the archive path as a content hash", desc.contains("sha1"));
        assertFalse("must not publish a constructable archive-path format", desc.contains("images/<"));
        assertTrue("must say the value is opaque", desc.contains("opaque"));
        assertTrue("must tell the caller to pass it back unmodified",
                desc.contains("never construct or parse one"));
        assertTrue("the IMAGE_NOT_FOUND consequence must survive", desc.contains("IMAGE_NOT_FOUND"));
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

    /**
     * Response-field documentation pins for the nine view tools this handler registers.
     *
     * <p>An agent driving these tools had no way to learn the response shape short of
     * guessing or invoking the mutation — destructive for clear-view and the delete
     * family. Each pin asserts a SHORT, STABLE substring (a field name, or the phrase
     * carrying a conditional), never a whole sentence, so an innocuous rewording does
     * not turn the suite red.</p>
     */
    private String descriptionOf(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName))
                .tool()
                .description();
    }

    /**
     * Every connection tool must say that lineStyle is refused AND name what to use instead.
     *
     * <p>Stating only the prohibition is what produced the gap in the first place: an agent that
     * is told a parameter is unavailable, and nothing more, substitutes something arbitrary or
     * drops the distinction it was asked to convey. Each description has to stand alone — an
     * agent sees one tool's description, not the set — so all three carry the pair.</p>
     */
    @Test
    public void connectionTools_descriptionsShouldRejectLineStyleAndNameTheSupportedIdiom() {
        for (String tool : java.util.List.of(
                "add-connection-to-view", "update-view-connection", "auto-connect-view")) {
            String desc = descriptionOf(tool);
            assertTrue(tool + " description should name lineStyle", desc.contains("lineStyle"));
            assertTrue(tool + " description should say lineStyle is rejected, not ignored",
                    desc.contains("REJECTED"));
            assertTrue(tool + " description should name lineColor as the supported idiom",
                    desc.contains("lineColor"));
            assertTrue(tool + " description should name lineWidth as the supported idiom",
                    desc.contains("lineWidth"));
        }
    }

    @Test
    public void addToView_descriptionShouldDocumentResponseFields() {
        String desc = descriptionOf("add-to-view");
        assertTrue("must name the viewObject wrapper", desc.contains("viewObject"));
        // autoConnections/skippedAutoConnections are BOTH omitted unless autoConnect is
        // true (ArchiModelAccessorImpl branch), and autoConnections can come back as an
        // empty array. Documenting them unconditionally would make an agent expect a
        // field that is simply absent on the default path.
        assertTrue("must name autoConnections", desc.contains("autoConnections"));
        assertTrue("must gate autoConnections on the autoConnect flag",
                desc.contains("only when autoConnect is true"));
        assertTrue("must name skippedAutoConnections", desc.contains("skippedAutoConnections"));
    }

    @Test
    public void addConnectionToView_descriptionShouldDocumentResponseFields() {
        String desc = descriptionOf("add-connection-to-view");
        assertTrue("must name viewConnectionId", desc.contains("viewConnectionId"));
        assertTrue("must name relationshipType", desc.contains("relationshipType"));
    }

    @Test
    public void addGroupToView_descriptionShouldDocumentResponseFields() {
        String desc = descriptionOf("add-group-to-view");
        assertTrue("must name viewObjectId", desc.contains("viewObjectId"));
        // parentViewObjectId is hardcoded null and childViewObjectIds always empty, so
        // nesting can never be read back from this response. Saying so prevents an agent
        // concluding the nest failed.
        assertTrue("must warn that parent/children are not echoed",
                desc.contains("never echoed"));
    }

    // ---- Untitled groups: an empty label is a value, an absent key is not ----

    @Test
    public void addGroupToView_shouldPassAnEmptyLabelThrough_whenLabelIsEmpty() throws Exception {
        // The handler used to reject "" itself, which relocated "what do I call a container the
        // source never named" onto every caller. It must now hand "" to the accessor untouched.
        List<String> seen = new ArrayList<>();
        accessor.setAddGroupToViewBehavior((sid, vId, label, x, y, w, h, pvoId) -> {
            seen.add(label);
            return new MutationResult<>(new ViewGroupDto("vg-1", label, 10, 10, 300, 200,
                    null, null), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("label", "");
        McpSchema.CallToolResult result = callTool("add-group-to-view", args);

        assertFalse("an empty label must not be an error", result.isError());
        assertEquals("the handler must forward the empty label rather than reject it",
                List.of(""), seen);
    }

    @Test
    public void addGroupToView_shouldReturnError_whenLabelIsOmitted() throws Exception {
        // Omitted and explicitly-empty are different requests and must stay different: relaxing the
        // blank check must not relax the presence check, and the diagnostic must still come from
        // the shared near-miss reporter rather than from a bare null guard further down.
        //
        // The wording follows the seam. Where "" is accepted, the default "missing OR EMPTY" phrasing
        // and its "provide a NON-EMPTY value" advice are both false — and that particular falsehood
        // is what makes an LLM caller invent a placeholder, which is the defect this tool's
        // empty-label support exists to remove.
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");

        McpSchema.CallToolResult result = callTool("add-group-to-view", args);

        assertTrue("an omitted label must still be an error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
        assertTrue("the handler missing-parameter diagnostic must survive. Was: " + content,
                content.contains("Missing required parameter: label"));
        assertFalse("the diagnostic must not tell a caller that an empty label is rejected — it is "
                + "not, and that advice is what produces invented placeholders. Was: " + content,
                content.contains("non-empty"));
    }

    @Test
    public void addGroupToViewSpec_shouldKeepLabelRequired() {
        // An empty string is a present value, so relaxing the blank check must not relax the
        // schema: dropping label from `required` would make the omitted case legal too.
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "add-group-to-view".equals(s.tool().name()))
                .findFirst().orElseThrow();
        assertTrue("label must stay in the JSON-schema required list",
                spec.tool().inputSchema().required().contains("label"));
    }

    @Test
    public void addGroupToViewDescription_shouldStateTheEmptyLabelContract() {
        String desc = descriptionOf("add-group-to-view");
        assertTrue("the description must tell an LLM that an empty label is accepted and yields "
                + "an untitled group, or callers keep inventing placeholders. Was: " + desc,
                desc.contains("untitled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void addGroupToViewLabelProperty_shouldStateTheEmptyLabelContract() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "add-group-to-view".equals(s.tool().name()))
                .findFirst().orElseThrow();
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        String labelDesc = (String) ((Map<String, Object>) props.get("label")).get("description");
        assertTrue("the label property must carry the contract too — an agent reading the property "
                + "list alone must not conclude a non-empty value is required. Was: " + labelDesc,
                labelDesc.contains("untitled"));
    }

    @Test
    public void addNoteToView_shouldPassAnEmptyContentThrough_whenContentIsEmpty() throws Exception {
        // The schema property for `content` has always said "Empty string is allowed for placeholder
        // notes". This is the seam that made that sentence false.
        List<String> seen = new ArrayList<>();
        accessor.setAddNoteToViewBehavior((sid, vId, content, pos, gap, x, y, w, h, pvoId) -> {
            seen.add(content);
            return new MutationResult<>(new ViewNoteDto("vn-1", content, 10, 10, 185, 80, null), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("content", "");
        McpSchema.CallToolResult result = callTool("add-note-to-view", args);

        assertFalse("an empty content must not be an error", result.isError());
        assertEquals("the handler must forward the empty content rather than reject it",
                List.of(""), seen);
    }

    @Test
    public void addNoteToView_shouldReturnError_whenContentIsOmitted() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");

        McpSchema.CallToolResult result = callTool("add-note-to-view", args);

        assertTrue("an omitted content must still be an error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
        assertTrue("the missing-parameter diagnostic must survive. Was: " + content,
                content.contains("Missing required parameter: content"));
        assertFalse("the diagnostic must not claim an empty content is rejected. Was: " + content,
                content.contains("non-empty"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void addNoteToViewContentProperty_shouldStillPromiseThatEmptyIsAllowed() {
        // This description was already shipping this promise while the handler refused it. Pin it
        // now that it is true, so the pair cannot silently diverge again.
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "add-note-to-view".equals(s.tool().name()))
                .findFirst().orElseThrow();
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        String desc = (String) ((Map<String, Object>) props.get("content")).get("description");
        assertTrue("the content property must keep promising that an empty string is allowed. "
                + "Was: " + desc, desc.contains("Empty string is allowed"));
        assertTrue("content must stay in the required list — \"\" is a value, absence is not",
                spec.tool().inputSchema().required().contains("content"));
    }

    @Test
    public void updateViewObject_shouldPassAnEmptyTextThrough_whenTextIsEmpty() throws Exception {
        // Absent stays "leave unchanged"; "" becomes "clear". Reading text through the
        // blank-collapsing helper made the two indistinguishable at the handler seam.
        List<String> seen = new ArrayList<>();
        accessor.setUpdateViewObjectBehavior((sid, voId, x, y, w, h, txt) -> {
            seen.add(txt);
            return new MutationResult<>(new ViewObjectDto(voId, null, txt, "DiagramModelGroup",
                    10, 10, 300, 200), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("text", "");
        McpSchema.CallToolResult result = callTool("update-view-object", args);

        assertFalse("an empty text must not be an error", result.isError());
        assertEquals("the handler must forward \"\" as a supplied value, not collapse it to null",
                List.of(""), seen);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void updateViewObjectTextProperty_shouldStateThatAnEmptyStringClears() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "update-view-object".equals(s.tool().name()))
                .findFirst().orElseThrow();
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        String textDesc = (String) ((Map<String, Object>) props.get("text")).get("description");
        assertTrue("the text property must say that an empty string clears. Was: " + textDesc,
                textDesc.contains("clear"));
    }

    @Test
    public void addNoteToView_descriptionShouldDocumentResponseFields() {
        String desc = descriptionOf("add-note-to-view");
        assertTrue("must name viewObjectId", desc.contains("viewObjectId"));
        // The `note` field is a placement DIAGNOSTIC, not the note's text (that is
        // `content`). An agent reading it as the body would be wrong.
        assertTrue("must frame the note field as a placement warning",
                desc.contains("placement warning"));
        // parentViewObjectId is null for a top-level placement (the common case) and the
        // DTO is NON_NULL, so it is absent unless the object was actually nested.
        assertTrue("parentViewObjectId must be scoped to nested placements",
                desc.contains("parentViewObjectId only when nested"));
    }

    @Test
    public void addViewReferenceToView_descriptionShouldDocumentResponseFields() {
        String desc = descriptionOf("add-view-reference-to-view");
        // NOTE: `referencedViewId` alone is NOT a valid pin here — it is already a
        // REQUEST parameter in this description, so asserting it would pass even if the
        // response clause were dropped. Pin the response sentence itself.
        // (`parentViewObjectId` is likewise already a request parameter here.)
        assertTrue("must name viewObjectId as a returned field",
                desc.contains("Returns viewObjectId"));
        assertTrue("parentViewObjectId must be scoped to nested placements",
                desc.contains("parentViewObjectId only when nested"));
    }

    @Test
    public void addImageToView_descriptionShouldDocumentResponseFields() {
        String desc = descriptionOf("add-image-to-view");
        assertTrue("must name viewObjectId", desc.contains("viewObjectId"));
        // Only borderColor + documentation survive into DiagramImageDto; the other
        // styling params this tool accepts are silently not echoed.
        assertTrue("must name borderColor as one of the two echoed fields",
                desc.contains("borderColor"));
        assertTrue("parentViewObjectId must be scoped to nested placements",
                desc.contains("parentViewObjectId only when nested"));
    }

    @Test
    public void removeFromView_descriptionShouldDocumentResponseFields() {
        String desc = descriptionOf("remove-from-view");
        assertTrue("must name removedObjectType", desc.contains("removedObjectType"));
        assertTrue("must name cascadeRemovedConnectionIds",
                desc.contains("cascadeRemovedConnectionIds"));
        // Two traps: the list is OMITTED (not empty) when nothing cascaded, and on the
        // group branch collectDescendantIds also appends descendant view-OBJECT ids, so
        // the name under-describes the contents.
        assertTrue("must say the list is omitted rather than empty",
                desc.contains("omitted, not"));
        assertTrue("must warn the group branch also lists view-object IDs",
                desc.contains("descendant view-object IDs"));
    }

    @Test
    public void updateViewConnection_descriptionShouldDocumentResponseFields() {
        String desc = descriptionOf("update-view-connection");
        // NOTE: bare `viewConnectionId` is NOT a valid pin — it is already the required
        // request parameter. Pin the response phrasing.
        assertTrue("must frame the returned fields as post-update",
                desc.contains("Returns post-update"));
        // nameVisible is null (hence omitted) when showLabel=true. Absence therefore
        // means "label visible", the opposite of the natural reading.
        assertTrue("must name nameVisible", desc.contains("nameVisible"));
        assertTrue("must explain that absence of nameVisible means visible",
                desc.contains("absence means visible"));
    }

    @Test
    public void clearView_descriptionShouldDocumentResponseFields() {
        String desc = descriptionOf("clear-view");
        assertTrue("must name elementsRemoved", desc.contains("elementsRemoved"));
        assertTrue("must name nonArchimateObjectsRemoved",
                desc.contains("nonArchimateObjectsRemoved"));
        // elementsRemoved is view.getChildren().size() — ALL top-level children — so it
        // already contains nonArchimateObjectsRemoved. Summing them double-counts.
        assertTrue("must warn the two counts overlap and must not be summed",
                desc.contains("do not sum them"));
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
        assertTrue("description should contain '+80px' — phrase-presence pin, unique to cross-reference (not in heuristic table)",
                desc.contains("+80px"));
        assertTrue("description should contain '+100px' — phrase-presence pin, unique to cross-reference (not in heuristic table)",
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
        assertTrue("description should contain '+80px' — phrase-presence pin, unique to cross-reference (not in heuristic table)",
                desc.contains("+80px"));
        assertTrue("description should contain '+100px' — phrase-presence pin, unique to cross-reference (not in heuristic table)",
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
    public void assessLayout_shouldSendTheCrossBranchAndTrueBoundaryCountsOnTheWire() throws Exception {
        // Computing a field on the assessor proves nothing about what the client receives.
        // These two must be serialized by the registered tool, or an agent can never act on them.
        Map<String, Object> entity = getResult(callAndParse("assess-layout",
                Map.of("viewId", "v-1")));

        assertTrue("cousinOverlapCount must reach the client",
                entity.containsKey("cousinOverlapCount"));
        assertTrue("boundaryViolationCount must reach the client",
                entity.containsKey("boundaryViolationCount"));
        // Ints are never omitted, so a zero here is a reported zero rather than a missing field.
        assertEquals(0, entity.get("cousinOverlapCount"));
        assertEquals(0, entity.get("boundaryViolationCount"));
    }

    @Test
    public void assessLayout_descriptionShouldStateTheActualOverlapRuleNotItsOpposite() {
        // overlapCount counts SAME-PARENT pairs. Describing it as covering "unrelated elements"
        // promised an agent the exact case the same-parent test drops — two objects in different
        // branches with no ancestor relationship. The client cannot see the canvas, so the
        // description is its only ground truth and an inverted rule is a correctness defect.
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "assess-layout".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool()
                .description();

        assertFalse("the description must not claim overlapCount covers unrelated elements",
                desc.contains("genuine layout problems where unrelated elements overlap"));
        assertTrue("it must state the same-parent rule",
                desc.contains("same-parent"));
        assertTrue("it must say that top-level objects count as siblings of each other",
                desc.contains("top-level objects"));
        assertTrue("it must say where a cross-branch overlap surfaces instead",
                desc.contains("boundaryViolations"));
        assertTrue("it must name the container-pair fallback",
                desc.contains("container pair"));
        assertTrue("it must name the field that reports the actual colliding pair",
                desc.contains("cousinOverlapCount"));
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
        assertTrue("description should name the density-aware 3-state-termination ordering",
                desc.contains("observe → decide → density-aware "
                        + "3-state-termination"));
        assertTrue("description should name the 2×2 discriminator axes",
                desc.contains("aggregate-trend × spacing-regime-position"));
        assertTrue("description should name the three termination states",
                desc.contains("CONTINUE") && desc.contains("ESCALATE")
                        && desc.contains("PASS-HONEST"));
        assertTrue("description should name the density_floor_reflow_required terminal",
                desc.contains("density_floor_reflow_required"));
        assertTrue("description should surface the no-auto-reflow + consent model",
                desc.contains("NEVER auto-reflows")
                        && desc.contains("never surface + act"));
        assertTrue("description should name the +10/step monotone ladder (control-loop semantics)",
                desc.contains("+10/step monotone ladder"));
        assertTrue("description should name the iterationBudget parameter",
                desc.contains("iterationBudget"));
        assertTrue("description should name the aggregate thresholds_met back-off rule",
                desc.contains("aggregate thresholds_met"));
        assertTrue("description should EXPLICITLY exclude per-metric monotonicity (the contract forbids it)",
                desc.contains("per-metric monotonicity"));
        assertTrue("description should name the terminationReason DTO field",
                desc.contains("terminationReason"));
        assertTrue("description should name the goal_reached termination branch (a)",
                desc.contains("goal_reached_at_iteration_N"));
        assertTrue("description should name the budget_exhausted termination branch (b)",
                desc.contains("budget_exhausted_after_N_iterations"));
        assertTrue("description should name the aggregate_threshold_regressed termination branch (c)",
                desc.contains("aggregate_threshold_regressed_at_iteration_N"));
        assertTrue("description should name the structural_no_change termination branch (d)",
                desc.contains("structural_no_change"));
        assertTrue("description should name the heuristic_already_met termination branch (e)",
                desc.contains("heuristic_already_met_no_change"));
        assertTrue("description should name the dry_run_recommendation_not_applied taxonomy string (6th branch overall — pre-loop dryRun guard)",
                desc.contains("dry_run_recommendation_not_applied"));
        assertTrue("description should name the iteration_apply_failed taxonomy string (7th branch overall — the partial-commit patch covers cmd.execute() partial-throw recovery)",
                desc.contains("iteration_apply_failed_at_iteration_N"));
        assertTrue("description should frame the contract as 'ten branches' (7 in-loop + 3 pre-loop guards — branches (i) reroute_degraded and (j) density_precondition_infeasible_reflow_required shipped after the density_floor_reflow_required branch)",
                desc.contains("ten branches"));
        assertTrue("description should name the iterationCount + appliedDeltas DTO fields",
                desc.contains("iterationCount") && desc.contains("appliedDeltas"));
        assertTrue("description should name the single-undo guarantee",
                desc.contains("single undo-stack entry"));
        assertTrue("description should name the NonNotifyingCompoundCommand wrapping mechanism",
                desc.contains("NonNotifyingCompoundCommand"));
        assertTrue("description should name the densityFloorDiagnosis DTO field",
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
        assertTrue("description should name the density-aware 3-state-termination ordering",
                desc.contains("observe → decide → density-aware "
                        + "3-state-termination"));
        assertTrue("description should name the 2×2 discriminator axes",
                desc.contains("aggregate-trend × spacing-regime-position"));
        assertTrue("description should name the three termination states",
                desc.contains("CONTINUE") && desc.contains("ESCALATE")
                        && desc.contains("PASS-HONEST"));
        assertTrue("description should name the density_floor_reflow_required terminal",
                desc.contains("density_floor_reflow_required"));
        assertTrue("description should surface the no-auto-reflow + consent model",
                desc.contains("NEVER auto-reflows")
                        && desc.contains("never surface + act"));
        assertTrue("description should name the +10/step monotone ladder (control-loop semantics)",
                desc.contains("+10/step monotone ladder"));
        assertTrue("description should name the iterationBudget parameter",
                desc.contains("iterationBudget"));
        assertTrue("description should name the aggregate thresholds_met back-off rule",
                desc.contains("aggregate thresholds_met"));
        assertTrue("description should EXPLICITLY exclude per-metric monotonicity (the contract forbids it)",
                desc.contains("per-metric monotonicity"));
        assertTrue("description should name the terminationReason DTO field",
                desc.contains("terminationReason"));
        assertTrue("description should name the goal_reached termination branch (a)",
                desc.contains("goal_reached_at_iteration_N"));
        assertTrue("description should name the budget_exhausted termination branch (b)",
                desc.contains("budget_exhausted_after_N_iterations"));
        assertTrue("description should name the aggregate_threshold_regressed termination branch (c)",
                desc.contains("aggregate_threshold_regressed_at_iteration_N"));
        assertTrue("description should name the structural_no_change termination branch (d)",
                desc.contains("structural_no_change"));
        assertTrue("description should name the heuristic_already_met termination branch (e)",
                desc.contains("heuristic_already_met_no_change"));
        assertTrue("description should name the dry_run_recommendation_not_applied taxonomy string (6th branch overall — pre-loop dryRun guard)",
                desc.contains("dry_run_recommendation_not_applied"));
        assertTrue("description should name the iteration_apply_failed taxonomy string (7th branch overall — the partial-commit patch covers cmd.execute() partial-throw recovery)",
                desc.contains("iteration_apply_failed_at_iteration_N"));
        assertTrue("description should frame the contract as 'ten branches' (7 in-loop + 3 pre-loop guards — branches (i) reroute_degraded and (j) density_precondition_infeasible_reflow_required shipped after the density_floor_reflow_required branch)",
                desc.contains("ten branches"));
        assertTrue("description should name the iterationCount + appliedDeltas DTO fields",
                desc.contains("iterationCount") && desc.contains("appliedDeltas"));
        assertTrue("description should name the single-undo guarantee",
                desc.contains("single undo-stack entry"));
        assertTrue("description should name the NonNotifyingCompoundCommand wrapping mechanism",
                desc.contains("NonNotifyingCompoundCommand"));
        assertTrue("description should name the densityFloorDiagnosis DTO field",
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
        assertTrue("description should name the density-aware 3-state-termination ordering",
                desc.contains("observe → decide → density-aware "
                        + "3-state-termination"));
        assertTrue("description should name the 2×2 discriminator axes",
                desc.contains("aggregate-trend × spacing-regime-position"));
        assertTrue("description should name the three termination states",
                desc.contains("CONTINUE") && desc.contains("ESCALATE")
                        && desc.contains("PASS-HONEST"));
        assertTrue("description should name the density_floor_reflow_required terminal",
                desc.contains("density_floor_reflow_required"));
        assertTrue("description should surface the no-auto-reflow + consent model",
                desc.contains("NEVER auto-reflows")
                        && desc.contains("never surface + act"));
        assertTrue("description should name the iterationBudget parameter",
                desc.contains("iterationBudget"));
        assertTrue("description should name the per-iteration step cap reinterpretation",
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
        assertTrue("description should name the aggregate thresholds_met back-off rule",
                desc.contains("aggregate thresholds_met"));
        assertTrue("description should name the goal_reached termination branch",
                desc.contains("goal_reached_at_iteration_N"));
        assertTrue("description should name the dry_run_recommendation_not_applied taxonomy string (6th branch overall — pre-loop dryRun guard)",
                desc.contains("dry_run_recommendation_not_applied"));
        assertTrue("description should name the iteration_apply_failed taxonomy string (7th branch overall — the partial-commit patch covers cmd.execute() partial-throw recovery)",
                desc.contains("iteration_apply_failed_at_iteration_N"));
        assertTrue("description should frame the contract as 'ten branches' (7 in-loop + 3 pre-loop guards — branches (i) reroute_degraded and (j) density_precondition_infeasible_reflow_required shipped after the density_floor_reflow_required branch)",
                desc.contains("ten branches"));
        assertTrue("description should name the single-undo guarantee (across both arms)",
                desc.contains("single undo-stack entry"));
        assertTrue("description should name the NonNotifyingCompoundCommand wrapping mechanism",
                desc.contains("NonNotifyingCompoundCommand"));
        assertTrue("description should name the composer's default budget split (4+4 from 8)",
                desc.contains("4+4"));
        assertTrue("description should name the per-arm density diagnosis DTO fields",
                desc.contains("elementDensityFloorDiagnosis")
                        && desc.contains("groupDensityFloorDiagnosis"));
    }

    /**
     * No served tool description opens a sentence with a lowercase identifier.
     *
     * <p>These descriptions are assembled from wrapped string literals, and a clause appended
     * after a full stop was repeatedly begun with the bare JSON key, parameter name, mode value or
     * enum value it was about — "…a mis-nesting you can still fix. autoConnections only when
     * autoConnect is true", "…and stored bendpoints. nameVisible is returned only when…". Each
     * reads as a run-on to whatever renders it.</p>
     *
     * <p>None of these tokens may be capitalised to fix it: every one is a value the caller must
     * use verbatim — a JSON key it reads off the response, a parameter it passes, a mode string it
     * sends, or a terminationReason it matches on — and no other casing resolves anywhere. The
     * sentences are recomposed around them instead.</p>
     *
     * <p>Read off the registry, so this sees what is SERVED rather than what the source looks
     * like. The scan is over the description plus every input-schema property description,
     * because both reach the agent.</p>
     */
    @Test
    public void servedToolSurface_mustNotOpenASentenceWithALowercaseIdentifier() {
        // Tokens measured as having carried this defect, plus the two the fix introduced no
        // instance of but which share the shape. A token is listed here, not derived, because the
        // check is "does a sentence start with THIS identifier" — a general rule over any
        // lowercase word would fire on ordinary prose that legitimately continues a sentence.
        List<String> tokens = List.of(
                "autoConnections", "nameVisible", "cascadeRemovedConnectionIds",
                "contextualPartialDimensions", "viewId", "nudgedElements", "elementWidth",
                "iterationBudget", "budget_exhausted_after_3_iterations",
                "ownIconOverLabelDescriptions", "auto-layout-and-route", "terminals-only");

        List<String> offenders = new java.util.ArrayList<>();
        for (var spec : registry.getToolSpecifications()) {
            String name = spec.tool().name();
            for (String surface : servedProse(spec)) {
                for (String token : tokens) {
                    if (surface.contains(". " + token)) {
                        int at = surface.indexOf(". " + token);
                        offenders.add(name + ": \"..."
                                + surface.substring(Math.max(0, at - 40),
                                        Math.min(surface.length(), at + token.length() + 30))
                                + "...\"");
                    }
                }
            }
        }

        assertTrue("a sentence must not open with a bare identifier the caller has to use"
                + " verbatim — recompose the sentence around it, do not capitalise it:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    /** The description plus every input-schema property description — both reach the agent. */
    @SuppressWarnings("unchecked")
    private static List<String> servedProse(
            io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec) {
        List<String> out = new java.util.ArrayList<>();
        out.add(spec.tool().description());
        Object schema = spec.tool().inputSchema();
        if (schema instanceof io.modelcontextprotocol.spec.McpSchema.JsonSchema js
                && js.properties() != null) {
            for (Object prop : js.properties().values()) {
                if (prop instanceof Map<?, ?> m && m.get("description") instanceof String d) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    /**
     * The termination contract names its third pre-loop guard as one word.
     *
     * <p>All three spacing tools build that sentence by concatenating wrapped string
     * literals, and an interposed paragraph once split {@code density-precondition-infeasible}
     * across the seam, leaving a sentence that began {@code "infeasible), surfaced in"} with an
     * orphaned closing paren. The five sibling pins above were green throughout, because a
     * substring assertion cannot see a word severed in half — each of them names a token that
     * survived the split intact.</p>
     *
     * <p>This reads the SERVED description off the registry rather than the source text.
     * {@code javac} folds the {@code + "…"} concatenation into a single constant-pool entry, so
     * the served string is already fused and needs no seam collapsing; a source-text grep for the
     * phrase returns zero both before AND after the repair, because it spans a line break either
     * way. Asserting on the source would therefore certify nothing.</p>
     *
     * <p>The second assertion pins the repair's shape, not merely its result: the explanatory
     * paragraph belongs AFTER the (a)–(j) enumeration that introduces the {@code N} it explains,
     * so the guard token must appear before it. The paragraph itself is not banned — it is
     * required prose — only its position relative to the enumeration is constrained.</p>
     */
    @Test
    public void spacingTools_descriptionShouldNameTheThirdPreLoopGuardContiguously() {
        for (String tool : new String[] {
                "apply-element-spacing-recommendations",
                "apply-group-spacing-recommendations",
                "apply-spacing-recommendations" }) {
            String desc = descriptionOf(tool);

            assertTrue(tool + ": the third pre-loop guard must read as one contiguous token —"
                            + " 'density-precondition-infeasible)' — not split across an"
                            + " interposed paragraph",
                    desc.contains("density-precondition-infeasible)"));

            int guard = desc.indexOf("density-precondition-infeasible)");
            int note = desc.indexOf("NOTE — in budget_exhausted_after_N_iterations");
            assertTrue(tool + ": the accepted-COMMANDS paragraph must still be published",
                    note >= 0);
            assertTrue(tool + ": the accepted-COMMANDS paragraph explains the N introduced by"
                            + " branches (b), (c) and (g), so it must follow the enumeration"
                            + " rather than interrupt the sentence that opens it",
                    guard < note);
        }
    }


    /**
     * The (d) branch's enumeration must name the newest reason it can publish — on the SERVED
     * description, which is the string an agent actually reads.
     *
     * <p>Scoped to the (d) clause rather than the whole description: every one of these three
     * tools mentions containers drawn inside a host elsewhere in its prose, so a description-wide
     * check passes on a sentence in a different paragraph and says nothing about the enumeration.
     * Read against the registered specification rather than the handler source, because the source
     * is a pile of concatenated literals and a phrase that straddles two of them is present in the
     * served string and absent from any single line of the file.</p>
     */
    @Test
    public void spacingTools_structuralBranchShouldNameTheNotPositionedReason() {
        for (String tool : new String[] {
                "apply-element-spacing-recommendations",
                "apply-group-spacing-recommendations",
                "apply-spacing-recommendations" }) {
            String desc = descriptionOf(tool);

            int open = desc.indexOf("(d) structural_no_change_<reason>");
            int close = desc.indexOf("(e) heuristic_already_met_no_change");
            assertTrue(tool + ": the (d) branch must still be enumerated in the served description",
                    open >= 0);
            assertTrue(tool + ": the (e) branch must still follow it, or this guard cannot bound "
                    + "the clause it means to read", close > open);

            String clause = desc.substring(open, close);
            assertTrue(tool + ": the (d) clause enumerates the structural short-circuits without "
                    + "naming the one a caller on a hosted view actually hits. Clause: " + clause,
                    clause.contains("drawn inside a host"));
            assertTrue(tool + ": and it must name the tool that does position those containers. "
                    + "Clause: " + clause, clause.contains("arrange-groups"));
        }
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
    public void shouldPassLabelExpression_whenUpdateViewObjectReceivesParam() throws Exception {
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
    public void shouldPassEmptyString_whenLabelExpressionClearRequested() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("labelExpression", "");

        callAndParse("update-view-object", args);

        // Empty string is the clear semantic — must reach the accessor as "" (NOT null)
        // so the accessor can distinguish "clear" from "no change".
        assertEquals("", accessor.lastUpdateViewObjectLabelExpression);
    }

    @Test
    public void shouldNotPassLabelExpression_whenAbsent() throws Exception {
        // Reset any prior capture before the call.
        accessor.lastUpdateViewObjectLabelExpression = "sentinel-prefilled";

        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("x", 200);  // some other field, so the call validates

        callAndParse("update-view-object", args);

        assertNull("Absent labelExpression key must reach the accessor as null",
                accessor.lastUpdateViewObjectLabelExpression);
    }

    // ====================================================================
    // BLOCK: layout-within-group GRID SIZING — DOCUMENTED WHERE IT APPLIES
    // ====================================================================
    //
    // Two statements in this tool's schema were incomplete in exactly the place a live agent
    // tripped over them. (1) recursiveChildren enumerated what propagates to every nesting level
    // and omitted `columns`, which does propagate. (2) The uniform-cell-width rule was stated only
    // under autoWidth, while the grid applies it whether or not autoWidth is set — so a caller who
    // had switched to an explicit elementWidth had been told nothing about the behaviour that was
    // still active. Both are corrected, and the substance of each corrected sentence is proved by
    // an executable test rather than by its own presence: the single-level rule by
    // GroupLayoutCalculatorTest.computeGridLayout_defaultOverload_shouldStillGiveEveryCellTheWidestWidth
    // (which passes explicit sizes, i.e. no autoWidth at all), and the recursive rule by
    // NestedLayoutOperationsTest.buildRecursiveLayoutCommands_shouldNotLetOneWideGrandchildSetEverySiblingsWidth.

    @SuppressWarnings("unchecked")
    private static Map<String, Object> layoutWithinGroupProps(
            io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec) {
        return (Map<String, Object>) spec.tool().inputSchema().properties();
    }

    private String layoutWithinGroupPropDescription(String propertyName) {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "layout-within-group".equals(s.tool().name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> prop =
                (Map<String, Object>) layoutWithinGroupProps(spec).get(propertyName);
        assertNotNull(propertyName + " must be declared on layout-within-group", prop);
        String desc = (String) prop.get("description");
        assertNotNull(propertyName + " must have a description", desc);
        return desc;
    }

    @Test
    public void autoRouteDescription_shouldSayItDoesNotRouteAroundNotes() {
        // ArchiModelAccessorImpl's obstacle build does "if (node.isContainer() || node.isNote())
        // continue;" -- notes are deliberately excluded, in both routing paths. assess-layout then
        // reports connectionThroughNote after the fact. Internally consistent; never told to the
        // caller, who reads "routes around element obstacles" and reasonably expects a note to
        // count. Measured 2026-08-14: a connection took a large vertical detour straight through
        // the legend note on the specimen's primary ingress path, and the run recorded it as a
        // residual rather than as the visible defect it was.
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "auto-route-connections".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        String lower = desc.toLowerCase(java.util.Locale.ROOT);
        assertTrue("must say notes are NOT treated as obstacles. Was: " + desc,
                lower.contains("note"));
        assertTrue("must point at the metric that reports the consequence, so the caller can "
                        + "detect it rather than discovering it in the export. Was: " + desc,
                desc.contains("connectionThroughNote"));
        // The exclusion is still true and this test is what stops the disclosure being
        // over-corrected into "the router avoids notes now". The router does not; it discloses.
        assertTrue("the exclusion must still be stated — the router routes THROUGH a note and the"
                        + " disclosure does not change that. Was: " + desc,
                desc.contains("NOTES ARE NOT OBSTACLES"));
    }

    @Test
    public void autoRouteDescription_shouldSayItNamesTheNotesItCrossed() {
        // The other half, and the reason this story exists. A description is transmitted once, at
        // schema time, and can only say that A crossing is possible -- never which one happened.
        // Before this, an agent that had just routed a view and wanted to know whether it had laid
        // a line across the legend had to issue a SECOND call to a DIFFERENT tool, which answered
        // with a count whose ids were recoverable only from prose. The description has to advertise
        // the per-call channel, or an agent has no reason to look for it.
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "auto-route-connections".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        assertTrue("must name the structured code the response carries. Was: " + desc,
                desc.contains(StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE));
        assertTrue("must say where the crossed note ids are, since acting on them is the point."
                        + " Was: " + desc,
                desc.contains("remediationViolatorIds"));
        assertTrue("must name the remedy that moves a note, and it is not undo. Was: " + desc,
                desc.contains("update-view-object"));
    }

    @Test
    public void autoRouteDescription_shouldNameEveryStructuredWarningCodeItCanEmit() {
        // A negative enumeration claims by omission. This paragraph named three codes behind
        // ordinals ("A second code... A third code...") that read as a CLOSED list, while the tool
        // emits six -- and the two it never named at all were reachable only through a conditional
        // nextSteps string. A description is transmitted ONCE, at schema time; a nextSteps entry
        // is emitted only when its condition fires. So a code named nowhere but a conditional step
        // is undiscoverable in advance: an agent cannot plan for a signal it will only ever meet
        // after it has already been surprised by it.
        //
        // Pinned off the REGISTRY, never a source grep -- javac folds the concatenation into one
        // constant-pool entry, so the source text is not evidence of what is served. Short
        // substrings only, per the convention note above.
        String desc = descriptionOf("auto-route-connections");

        assertTrue("description must name the crossings-regression code. Was: " + desc,
                desc.contains(StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED));
        assertTrue("description must name the unresolved-id code. Was: " + desc,
                desc.contains(StructuredWarningCodes.CONNECTION_NOT_FOUND));
        assertTrue("description must name the egress-lift code. Was: " + desc,
                desc.contains(StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND));
        assertTrue("description must name the sibling-overlap skip code. Was: " + desc,
                desc.contains(StructuredWarningCodes.AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP));
        assertTrue("description must name the note-crossing code. Was: " + desc,
                desc.contains(StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE));
        assertTrue("description must name the net-zero nudge code. Was: " + desc,
                desc.contains(StructuredWarningCodes.AUTO_NUDGE_NET_ZERO));

        // The ordinals are the half of the defect a presence check cannot see: six codes listed
        // under "A second code... A third code..." would satisfy every assertion above while still
        // telling the reader the list ends at three.
        assertFalse("description must not number the codes as a closed list. Was: " + desc,
                desc.contains("A second code"));
        assertFalse("description must not number the codes as a closed list. Was: " + desc,
                desc.contains("A third code"));

        // The two codes this tool does NOT emit. Naming either here would take the routing count to
        // eight -- a regression wearing a fix's clothes, since both belong to other tools and are
        // correctly documented on their own surfaces.
        assertFalse("auto-route must not claim the auto-layout rating code. Was: " + desc,
                desc.contains(StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED));
        assertFalse("auto-route must not claim the spacing rating code. Was: " + desc,
                desc.contains(StructuredWarningCodes.SPACING_RATING_REGRESSED));
    }

    @Test
    public void arrangeGroupsDescription_shouldNameTheThirdContainerKindItDoesNotArrange() {
        // The description enumerates the TWO kinds of container it arranges. A positive
        // enumeration claims by omission, and the omitted third kind is the common one on an
        // infrastructure view: a plain ArchiMate element (a Node typing a cloud Region, say) that
        // holds children. TopLevelGroupTargets deliberately excludes it -- "a Node that happens to
        // hold nested children is a host, not a zone" -- and says so nowhere a caller can read.
        // Measured 2026-08-14: groupsPositioned came back 8 on a view with 9 top-level containers,
        // the ninth held an entire account branch, and nothing in the response named it.
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "arrange-groups".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        // Case-insensitive on the prose assertions: these descriptions use caps for emphasis and
        // the emphasis moves around, so pinning case makes an editorial change look like a
        // regression. The field name is pinned case-sensitively — that one IS the wire contract.
        String lower = desc.toLowerCase(java.util.Locale.ROOT);
        assertTrue("must say a plain element acting as a container is NOT arranged. Was: " + desc,
                lower.contains("not arranged"));
        assertTrue("must give the caller the reason, so the exclusion reads as design not defect",
                lower.contains("host") && lower.contains("zone"));
        assertTrue("must name skippedContainers so the caller can detect the shortfall from the "
                        + "response rather than by counting containers themselves",
                desc.contains("skippedContainers"));
        // The exclusion is a DEFAULT, and saying so is what stops the correction over-swinging:
        // groupIds now admits such a container, but omitting groupIds still arranges exactly what
        // it always did. Both halves have to be readable or one of them becomes a false claim.
        assertTrue("must qualify the exclusion as the default it is, now that naming the container "
                        + "in groupIds arranges it. Was: " + desc,
                lower.contains("not arranged by default"));
        assertTrue("and must say naming it in groupIds is what arranges it, so the "
                        + "skippedContainers entry leads somewhere",
                desc.contains("groupIds"));
    }

    /**
     * The {@code groupIds} description enumerates what it accepts, and a positive enumeration
     * claims by omission — the omitted kind was the one the caller most needs, because it is the
     * one the default declines. It must also name what it refuses, since three of the four
     * refusals are valid ids used wrongly rather than bad ids.
     */
    @Test
    public void arrangeGroupsGroupIdsDescription_shouldSayWhichContainersAreAdmissible() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "arrange-groups".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().inputSchema().properties()
                .get("groupIds").toString();

        String lower = desc.toLowerCase(java.util.Locale.ROOT);
        assertTrue("must say a plain element acting as a container is accepted here, which is the "
                        + "whole point of the parameter's widening. Was: " + desc,
                lower.contains("plain archimate element"));
        assertTrue("must tie it back to the response field that names such a container",
                desc.contains("skippedContainers"));
        assertTrue("must name what is refused, or the enumeration claims by omission",
                lower.contains("note") && lower.contains("refused"));
        assertTrue("and keep the remedy for the objects it refuses",
                desc.contains("apply-positions"));
        // Depth, not kind. A container drawn inside a HOST is admissible and is not a direct
        // child of the view; one drawn inside a container this tool arranges is still refused.
        // Both halves have to be readable or the parameter reads as accepting either everything
        // nested or nothing nested, and it accepts exactly one of the two.
        assertTrue("must say a container drawn inside a host is accepted. Was: " + desc,
                lower.contains("inside a host"));
        assertTrue("must say such a container is arranged in the host's space rather than on the "
                        + "canvas, since that is what its returned coordinates mean. Was: " + desc,
                lower.contains("host's own coordinate space"));
        assertTrue("must keep the refusal for a container that is a member of an arranged one, "
                        + "or the widening reads as admitting every nested container. Was: " + desc,
                lower.contains("member of another arrangement target is still refused"));
    }

    /**
     * The headline sentence used to say only direct children of the view are positioned. That was
     * true when it was written and false the moment {@code arrange-groups} learned to arrange a
     * zone drawn inside a host — and nothing greped for it, which is why it could have shipped
     * false and green. This is that grep, plus the two things a caller cannot use the new field
     * without: which space the coordinates are in, and that it is outside the four-bucket sum.
     */
    @Test
    public void arrangeGroupsDescription_shouldSayHowANestedZoneIsArrangedAndReported() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "arrange-groups".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();
        String lower = desc.toLowerCase(java.util.Locale.ROOT);

        assertFalse("the refuted rule must not reappear: a container nested inside a host IS "
                        + "positioned. Was: " + desc,
                lower.contains("only direct children of the view are positioned"));
        assertTrue("must name the field the nested arrangement is reported in",
                desc.contains("nestedContainersArranged"));
        assertTrue("must name the key that says which origin those coordinates are measured from, "
                        + "without which they cannot be read at all",
                desc.contains("hostViewObjectId"));
        assertTrue("must say the nested containers sit OUTSIDE the four-bucket identity, or a "
                        + "caller summing the buckets reads the published equality as broken",
                lower.contains("outside that identity"));
        assertTrue("must say a host that cannot hold the arrangement is declined rather than "
                        + "half-filled, so a caller can tell an empty field from a refusal",
                lower.contains("declined whole"));
        assertTrue("and must keep the case that has NOT changed: a container inside a container "
                        + "of the same kind is still a member of it",
                lower.contains("member of it"));
    }

    @Test
    public void arrangeGroupsDescription_shouldDeclareTheDenominatorAndEveryResidualReasonCode() {
        // skippedContainers alone told the caller to check one bucket against another, which is
        // still asking it to re-derive a total the tool already knows -- and the instruction was
        // incomplete besides: a childless top-level element belongs to neither bucket, so a view
        // could report a clean container diff with seven elements stacked at the origin. The
        // response now declares topLevelObjects and names everything unclaimed, so the description
        // has to publish both, and it has to publish every reason code: an agent filtering on a
        // code that is documented nowhere is guessing at the wire contract.
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "arrange-groups".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        assertTrue("must name the denominator the other counters are measured against. Was: " + desc,
                desc.contains("topLevelObjects"));
        assertTrue("must name the bucket holding everything the other three did not claim",
                desc.contains("unhandled"));
        assertTrue("must state the reconciliation, or the denominator is just another loose number",
                desc.contains("groupsPositioned + standaloneElementsPlaced + skippedContainers "
                        + "+ unhandled"));
        // Iterated from the enumeration the reasons are composed from, never from a copy of it.
        // A literal list here passes over exactly the codes it was written with, so the sixth code
        // shipped documented on no surface and this assertion stayed green while it did.
        for (String code : SkippedContainerDto.ARRANGE_GROUPS_UNHANDLED_REASON_CODES) {
            assertTrue("every reason code the response can emit must be documented, or a caller "
                            + "cannot filter on it: " + code,
                    desc.contains(code));
        }
        assertTrue("must say the residual is honest on a deferred call, since that is where the "
                        + "effective geometry is not",
                desc.toLowerCase(java.util.Locale.ROOT).contains("queued or proposed"));

        // Both routes to a container, on both surfaces that state the predicate. Asserted against
        // the RENDERED description rather than the source: these sentences are built from string
        // concatenation, so a phrase grep over the Java file finds neither of them and reads as
        // though the tool states the predicate nowhere. It states it twice.
        assertTrue("the reason-code bullet must say a connection reaches a container by its box "
                        + "as well as by an element inside it — \"fewer than 2 arranged "
                        + "containers\" alone is the ambiguity that produced the wrong diagnostic",
                desc.contains("terminates on an element INSIDE it and when it terminates on the "
                        + "container's own box"));
        assertTrue("and so must the lane paragraph, which is where an agent reads the predicate "
                        + "before it builds the view",
                desc.contains("terminates on an element INSIDE the group or on the group's own "
                        + "container box"));
        assertTrue("grouped mode inherits the same predicate and has no unhandled bucket to report "
                        + "what it placed, which only this description can say",
                desc.contains("no `unhandled` bucket in which to report"));
    }

    @Test
    public void autoLayoutAndRouteDescription_shouldDeclareWhatGroupedModeNowDoesAndReports() {
        // Review finding: grouped mode gained a descent into nested containers, two response
        // fields, a changed elementsRepositioned counting rule and narrower grid cells — and a
        // caller reading only the registered tool had no way to learn any of it. A tool
        // description is the whole contract for an agent that cannot read this repo.
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "auto-layout-and-route".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        assertTrue("must say grouped mode descends into nested containers",
                desc.contains("WHOLE SUBTREE under each"));
        assertTrue("must say the arrangement is derived per level",
                desc.contains("derived") && desc.contains("per level"));
        assertTrue("must declare the changed elementsRepositioned counting rule",
                desc.contains("counts EVERY"));
        assertTrue("must name the nestedContainersFitted field",
                desc.contains("'nestedContainersFitted'"));
        assertTrue("must name the depthCapHit field and say what it means",
                desc.contains("'depthCapHit'"));
        assertTrue("must warn that grouped grids come out narrower than a single-level grid",
                desc.contains("per column"));
    }

    @Test
    public void recursiveDescription_shouldStateThatPropagationStopsAtNonNativeGroups() {
        String desc = layoutWithinGroupPropDescription("recursive");

        // NestedLayoutOperations.resizeAncestorGroups "stays typed to native groups" (its own
        // javadoc). An ArchiMate Grouping element acting as a container is therefore NOT resized,
        // and the walk stops there rather than continuing past it. Measured 2026-08-14: a call with
        // recursive+autoResize returned ancestorsResized:0 and left a Grouping parent too small for
        // the child it had just grown -- correct behaviour, undescribed. "Propagates sizing UPWARD
        // through the nesting hierarchy" reads as unconditional and is what misled the caller.
        // Case-insensitive on purpose: these descriptions use caps for emphasis and the emphasis
        // moves around. Pinning the case would make an editorial change look like a regression.
        String lower = desc.toLowerCase(java.util.Locale.ROOT);
        assertTrue("the description must name the type restriction, not just the direction: "
                        + "propagation covers native groups only. Was: " + desc,
                lower.contains("native"));
        assertTrue("and must say the walk STOPS rather than skipping past a non-native ancestor, "
                        + "since a caller who thinks it skips will not re-call on the parent. Was: " + desc,
                lower.contains("stops"));

        // The exclusion the fix above did NOT describe, and the one that actually fired on the
        // measured call: the guard is on the REQUESTED container, not on the ancestor. Naming an
        // ArchiMate-element container means the walk never starts, so the ancestor sentence
        // pinned above describes something that never got the chance to happen. Both are real and
        // both must be stated -- neither may be softened to make room for the other.
        assertTrue("the description must also say the walk only STARTS from a native group, since "
                        + "that is the exclusion that fires when the caller names an element "
                        + "container. Was: " + desc,
                lower.contains("starts"));
        assertTrue("and must point at the field that says which exclusion applied, rather than "
                        + "leaving the caller to infer it from a count. Was: " + desc,
                desc.contains("ancestorPropagation"));
    }

    /**
     * Every value the field can take is documented where an agent reads the contract. A published
     * code an agent cannot look up is a code it cannot triage on, and the enumeration is what makes
     * the field actionable rather than merely present.
     */
    @Test
    public void layoutWithinGroupDescription_shouldEnumerateEveryPropagationReasonCode() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "layout-within-group".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        assertTrue("the response prose must name the field itself",
                desc.contains("'ancestorPropagation'"));
        for (String reason : LayoutWithinGroupResultDto.ANCESTOR_PROPAGATION_REASONS) {
            String code = reason.substring(0, reason.indexOf(':'));
            // Quoted, not bare. 'not-requested' is a literal substring of
            // 'auto-resize-not-requested', so a bare contains() is satisfied by the longer code and
            // would keep passing if the standalone one were ever dropped from the description --
            // the assertion would then be green while the contract it names had regressed.
            assertTrue("every reason code the response can emit must be documented as its own "
                            + "quoted token, or a caller cannot act on it: " + code,
                    desc.contains("'" + code + "'"));
        }
        assertTrue("and it must warn that the count cannot substitute for the reason, which is the "
                        + "whole point of adding the field",
                desc.contains("does not identify the reason in either direction"));
        assertTrue("BOTH ambiguous codes must be named as able to arrive with a positive count. "
                        + "Naming only one under-discloses exactly the hazard this field exists "
                        + "for, and that omission is the defect this story was written to fix",
                desc.contains("BOTH 'stopped-at-non-native-ancestor' and 'depth-cap-reached'"));
    }

    /**
     * {@code adjust-view-spacing} runs the same upward pass under the same undisclosed restriction.
     * It is not gaining a reason field -- one silent surface beside one disclosing surface is a
     * diff that contradicts itself -- but it must at least state the restriction it inherits.
     */
    @Test
    public void adjustViewSpacingDescription_shouldDiscloseTheSameNativeGroupRestriction() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "adjust-view-spacing".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        String lower = desc.toLowerCase(java.util.Locale.ROOT);
        assertTrue("the parent-fit cascade walks native view groups only, and the description "
                        + "published the cascade without the restriction. Was: " + desc,
                lower.contains("native view groups only"));
        assertTrue("and must say what the caller does about it, since the tool will not do it. "
                        + "Was: " + desc,
                desc.contains("resize-elements-to-fit"));
    }

    /**
     * The description must name the field, every mechanism that populates it, and how it relates to
     * the sibling list — and the {@code resizedAncestors} sentence beside it must say both what that
     * field actually contains and where what it EXCLUDES is reported instead.
     *
     * <p>{@code resizedAncestors} is built from the overflow cascade's command map ALONE, so a
     * group this tool re-fits to its own contents with nothing overflowing is absent from it. Left
     * at that, the two sentences read as a contradiction: one says the re-fit is not reported, the
     * other says every resize is. The exclusion has to carry its destination.</p>
     *
     * <p>The mechanism list is the part that goes stale silently. It was written when the field
     * meant "children of the groups this tool spaced", and a container re-fitted at the TOP level
     * is not a child of any group — so the largest resize the call performs was described nowhere
     * and reported nowhere. It is asserted here mechanism by mechanism rather than as one blanket
     * sentence, because a blanket sentence stays green while a mechanism goes missing.</p>
     */
    @Test
    public void adjustViewSpacingDescription_shouldNameTheElementsItResizesAndCorrectTheAncestorClaim() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "adjust-view-spacing".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        assertTrue("must name the field the rectangles arrive in. Was: " + desc,
                desc.contains("`resizedElements`"));
        assertTrue("must claim the WIDE meaning the sibling tools already publish, not a meaning "
                        + "bounded to the children of the groups spaced. Was: " + desc,
                desc.contains("names every object whose SIZE this call changed"));
        assertTrue("...and must say the mechanism is irrelevant, which is what stops a caller "
                        + "reading the list as one mechanism's. Was: " + desc,
                desc.contains("It does not matter which mechanism changed it"));
        assertTrue("must state the grid mechanism — the widest element sets every cell. Was: "
                        + desc,
                desc.contains("the width of the widest element in it"));
        assertTrue("must state the recursion mechanism, which is the one a grid-only reading "
                        + "misses. Was: " + desc,
                desc.contains("nested container this same call re-fitted"));
        assertTrue("must state the container's own re-fit — the mechanism no map observed and the "
                        + "reason this field was silent about the view's biggest object. Was: "
                        + desc,
                desc.contains("container re-fitted to its own inflated contents"));
        assertTrue("...and must say that reaches a TOP-LEVEL container, which is the case a "
                        + "reader would otherwise assume is covered by the nested one. Was: " + desc,
                desc.contains("includes a TOP-LEVEL container"));
        assertTrue("...and must disclose that this mechanism can SHRINK, since the tool's own "
                        + "headline sentence promises only to increase spacing. Was: " + desc,
                desc.contains("SHRINKS to them"));
        assertTrue("and must say the re-fit mechanisms are NOT confined to grids, or a caller on a "
                        + "row arrangement reads the field as inapplicable. Was: " + desc,
                desc.contains("fire on row and column arrangements too"));
        assertTrue("must say why the existing count cannot substitute for it. Was: " + desc,
                desc.contains("counts children PLACED"));
        assertTrue("must state the empty-omission, in the same words the sibling tools use. Was: "
                        + desc,
                desc.contains("omitted entirely when empty"));
        assertTrue("must say it is an observation, not a list of everything placed — and about an "
                        + "OBJECT, since the list is no longer confined to children. Was: " + desc,
                desc.contains("an object re-written to the size it already had does not appear"));
        assertTrue("must state the relationship to the sibling list rather than leave the reader "
                        + "to assume they partition. Was: " + desc,
                desc.contains("The two lists are NOT disjoint"));
        assertTrue("...and that the overlap does not mean two different rectangles. Was: " + desc,
                desc.contains("both carry the same"));

        assertTrue("the ancestor list is built from the overflow cascade, and the description used "
                        + "to attribute it to the inflation itself. Was: " + desc,
                desc.contains("names any enclosing group the OVERFLOW CASCADE grew"));
        assertTrue("and must state the exclusion that makes the attribution checkable: a group "
                        + "re-fitted with nothing overflowing is not in the list. Was: " + desc,
                desc.contains("re-fits to its own contents without anything overflowing is NOT in it"));
        assertTrue("...and the exclusion must name where that re-fit IS reported, or the two "
                        + "sentences read as a contradiction. Was: " + desc,
                desc.contains("that re-fit is reported in `resizedElements` instead"));
    }

    /**
     * The parent-growth branch was reported by a local boolean that never left the method, which is
     * the flag case the effective-state invariant names explicitly. The description has to name the
     * field, say what populates it, and bound the claim — a caller who reads "resizes" without the
     * bound will look for it on a plain flat arrangement, where nothing can resize at all.
     */
    @Test
    public void layoutFlatViewDescription_shouldNameTheElementsItResizesAndBoundTheClaim() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "layout-flat-view".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        assertTrue("must name the field the rectangles arrive in. Was: " + desc,
                desc.contains("`resizedElements`"));
        assertTrue("must name the ONE mechanism that populates it. Was: " + desc,
                desc.contains("A parent grown to contain the children laid out inside it"));
        assertTrue("and must bound the claim, or a caller looks for resizes the arrangements "
                        + "cannot produce. Was: " + desc,
                desc.contains("the grid is uniform in cell SPACING, not cell SIZE"));
        assertTrue("...and must say what the bound rests on inside a batch, where the sizes "
                        + "come from the queue rather than from the stored view: a caller that "
                        + "queued a resize needs to know it survives the layout it issues next, "
                        + "and that the neighbours are spaced around THAT size. Was: " + desc,
                desc.contains("the sizes come from what the batch has already queued"));
        assertTrue("must say why the two existing counts cannot substitute. Was: " + desc,
                desc.contains("count objects PLACED"));
        assertTrue("must state the empty-omission in the same words the sibling tools use. Was: "
                        + desc, desc.contains("omitted entirely when empty"));
        assertTrue("must say it is an observation. Was: " + desc,
                desc.contains("re-written to the size it already had does not appear"));
        assertTrue("must separate size from movement. Was: " + desc,
                desc.contains("only MOVED and kept its size is not in it"));
        assertTrue("must tie the field to the switch that turns its only mechanism off. Was: "
                        + desc, desc.contains("With autoLayoutChildren=false nothing can grow"));
    }

    /**
     * Three mechanisms resize a child here and one of them is the caller's own value, so the
     * description has to name all three AND say why a requested size is still reported — otherwise
     * a caller reads an entry for a width they asked for as a bug in the report. It must also say
     * what the list does NOT cover, since the group's own re-fit is a separate, still-unreported
     * write and a reader would otherwise assume "resized" spans it.
     */
    @Test
    public void optimizeGroupOrderDescription_shouldNameAllThreeResizeMechanisms() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "optimize-group-order".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        assertTrue("must name the field. Was: " + desc, desc.contains("`resizedElements`"));
        assertTrue("mechanism 1 — the grid's uniform cell. Was: " + desc,
                desc.contains("the width of the widest element in that group"));
        assertTrue("mechanism 2 — autoWidth. Was: " + desc,
                desc.contains("autoWidth derives a width from the label"));
        assertTrue("mechanism 3 — the caller's own value. Was: " + desc,
                desc.contains("an explicit elementWidth or elementHeight imposes one"));
        assertTrue("and must justify reporting the requested one, or an entry for a width the "
                        + "caller asked for reads as a defect in the report. Was: " + desc,
                desc.contains("what the model ENDED UP holding"));
        assertTrue("must say why the existing count cannot substitute — and must describe what "
                        + "that count ACTUALLY counts. Measured against CrossingMinimizer: it "
                        + "increments only where a child's index in the order changed, so a child "
                        + "that kept its index is re-placed and re-sized and never counted. Was: "
                        + desc,
                desc.contains("counts only the children whose POSITION IN THE ORDER changed"));
        assertTrue("must say a requested size that was already held does NOT appear — that is the "
                        + "half of mechanism 3 a caller will otherwise be surprised by. Was: "
                        + desc,
                desc.contains("including when you requested that size"));
        assertTrue("must state the empty-omission in the sibling tools' words. Was: " + desc,
                desc.contains("omitted entirely when empty"));
        assertTrue("must separate size from movement. Was: " + desc,
                desc.contains("only MOVED and kept its size is not in it"));
        assertTrue("must bound the list to children, since the group's own re-fit is a separate "
                        + "write this field does not carry. Was: " + desc,
                desc.contains("the group's own re-fit is not in it"));
    }

    /**
     * The grouped descent already computed which leaves it re-sized and the call site read past it.
     * The description must name the field, bound it to grouped mode (flat never descends, so a
     * caller in flat mode would otherwise read its absence as "nothing was resized"), and separate
     * it from the sibling list it could be confused with.
     */
    @Test
    public void autoLayoutAndRouteDescription_shouldNameTheLeavesItResizesAndBoundThemToGroupedMode() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "auto-layout-and-route".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        assertTrue("must name the field. Was: " + desc, desc.contains("`resizedElements`"));
        assertTrue("must say what populates it — the pass sizes leaves itself. Was: " + desc,
                desc.contains("the pass decides a leaf's size itself"));
        assertTrue("must bound it to grouped mode, or a flat caller reads its absence as a claim. "
                        + "Was: " + desc,
                desc.contains("GROUPED MODE ONLY"));
        assertTrue("must say the two container lists do not overlap. Was: " + desc,
                desc.contains("disjoint from"));
        assertTrue("must say why the existing count cannot substitute. Was: " + desc,
                desc.contains("counts descendants MOVED"));
        assertTrue("must state the empty-omission in the sibling tools' words. Was: " + desc,
                desc.contains("omitted entirely when empty"));
        assertTrue("must say it is an observation. Was: " + desc,
                desc.contains("re-written to the size it already had does not appear"));
        assertTrue("must separate size from movement. Was: " + desc,
                desc.contains("only MOVED and kept its size is not in it"));
    }

    /**
     * One claim, restated in five published descriptions, must agree in all five.
     *
     * <p>{@code resizedElements} was added to four tools in one sweep, beside the one that already
     * had it. Each description was written separately, and a caller who learns the field's rules
     * from one tool carries them to the next — so a tool that omits the empty-omission rule, or the
     * observation semantics, or the size-versus-movement distinction, silently teaches something
     * different. Per-tool tests cannot catch that: each passes on its own text. This one loops.</p>
     */
    @Test
    public void resizedElementsWording_shouldAgreeAcrossEveryToolThatPublishesIt() {
        List<String> tools = List.of("adjust-view-spacing", "layout-flat-view",
                "optimize-group-order", "auto-layout-and-route", "layout-within-group");

        for (String tool : tools) {
            String desc = registry.getToolSpecifications().stream()
                    .filter(s -> tool.equals(s.tool().name()))
                    .findFirst().orElseThrow().tool().description();

            assertTrue(tool + " must name the field at all. Was: " + desc,
                    desc.contains("resizedElements"));
            assertTrue(tool + " must state the empty-omission in the SAME words as its siblings, "
                            + "or a caller who learned the rule elsewhere reads an absent key as "
                            + "an unanswered question. Was: " + desc,
                    desc.contains("omitted entirely when empty"));
            assertTrue(tool + " must state the observation semantics in the same words — the field "
                            + "is not a list of everything the call placed. Was: " + desc,
                    desc.contains("re-written to the size it already had does not appear"));
            assertTrue(tool + " must separate size from movement in the same words, since every "
                            + "one of these tools also MOVES things. Was: " + desc,
                    desc.contains("only MOVED and kept its size is not in it"));
        }
    }

    @Test
    public void recursiveChildrenDescription_shouldNameColumnsAmongWhatPropagates() {
        String desc = layoutWithinGroupPropDescription("recursiveChildren");

        assertTrue("the propagation list must name columns — it does propagate to every level",
                desc.contains("columns apply at every level"));
        assertTrue("and must say the value is chosen once for all depths",
                desc.contains("one 'columns' value is used at every depth"));
    }

    @Test
    public void layoutWithinGroupDescription_shouldSayItResizesElementsAndNameWhereToReadThem() {
        // The defect this pins: the RESPONSE section described positions, ancestors and nested
        // containers, and said nothing at all about the elements the call re-sizes. A description is
        // transmitted once, at schema time, so an agent that never reads the field name has no
        // reason to look for it — and the change is invisible on a canvas it cannot see.
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "layout-within-group".equals(s.tool().name()))
                .findFirst().orElseThrow().tool().description();

        assertTrue("must say the call resizes elements at all. Was: " + desc,
                desc.contains("THIS CALL ALSO RESIZES ELEMENTS"));
        assertTrue("must name the field the rectangles arrive in. Was: " + desc,
                desc.contains("'resizedElements'"));
        assertTrue("must state the mechanism — the cell, not the element's own width. Was: " + desc,
                desc.contains("that rectangle is its CELL"));
        assertTrue("must uncondition it from autoWidth, which is the belief that let it through. "
                        + "Was: " + desc,
                desc.contains("NOT conditional on autoWidth"));
        assertTrue("and from recursiveChildren — both arms do it. Was: " + desc,
                desc.contains("NOT conditional on recursiveChildren"));
        assertTrue("must say it is an observation, like its two siblings. Was: " + desc,
                desc.contains("a child re-written to the size it already had does not appear"));
        assertTrue("must state the empty-omission, since an absent key is otherwise ambiguous. "
                        + "Was: " + desc,
                desc.contains("omitted entirely when "));
        assertTrue("must separate it from the moves it was hiding behind — and say size ALONE, "
                        + "because the field does not report a pure move. Was: " + desc,
                desc.contains("A child that only MOVED and kept its size is not in it"));
        assertTrue("the remedy must be scoped to the arm where it works: elementWidth caps the "
                        + "cell on a single-level grid and does NOT prevent the within-column "
                        + "stretch under recursiveChildren. Was: " + desc,
                desc.contains("On a SINGLE-LEVEL grid, set elementWidth"));
        assertTrue("and the other arm must be told elementWidth is not its way out. Was: " + desc,
                desc.contains("with recursiveChildren elementWidth does NOT prevent the stretch"));
    }

    @Test
    public void recursiveChildrenDescription_shouldLimitTheAntiInflationClaimToAcrossLevels() {
        // The claim was true and read as more than it says. Per-column sizing stops one wide
        // descendant widening its siblings AT EVERY LEVEL UP; it does not stop it widening the
        // siblings IN ITS OWN COLUMN, because a fitted container is a column member like any other.
        // That is precisely the case that produced the 2230px element, so the sentence that
        // reassures the caller sat beside the mechanism that caught them out.
        String desc = layoutWithinGroupPropDescription("recursiveChildren");

        assertTrue("the across-levels guarantee must survive — it is true and it is the reason the "
                        + "recursive path sizes columns differently. Was: " + desc,
                desc.contains("does not widen its siblings at every level up"));
        assertTrue("and must be bounded to that, not read as a general one. Was: " + desc,
                desc.contains("ACROSS LEVELS ONLY, NOT WITHIN A COLUMN"));
        assertTrue("must name the member that does the widening — a fitted container. Was: " + desc,
                desc.contains("an inner container that fitted wide is one"));
        assertTrue("must point at where the caller sees which children it happened to. Was: " + desc,
                desc.contains("Read 'resizedElements'"));
        // The remedy half. elementWidth sets what a LEAF contributes to its column; the column is
        // still sized by its widest member, which is the fitted container. Offering it as the way
        // out here would be a remedy that does not work, which is the defect this story is about.
        // Pinned executably by LayoutWithinGroupAncestorReportingTest
        // .shouldStillStretchALeafToItsColumn_whenElementWidthIsSetOnTheRecursiveArm.
        assertTrue("must NOT offer elementWidth as the way out on this arm. Was: " + desc,
                desc.contains("elementWidth does NOT prevent it here"));
        assertTrue("and must name a remedy that actually works. Was: " + desc,
                desc.contains("stop them sharing a column"));
    }

    @Test
    public void columnsDescription_shouldStateTheCellWidthRuleWhereItActuallyApplies() {
        String desc = layoutWithinGroupPropDescription("columns");

        assertTrue("the uniform-cell rule must not be conditioned on autoWidth",
                desc.contains("whether or not autoWidth is set"));
        assertTrue("the caller must be told how to opt out",
                desc.contains("Set elementWidth to opt out"));
        assertTrue("and told the recursive path differs",
                desc.contains("With recursiveChildren each column is instead sized from its own"));
        assertEquals("columns stays an integer — a per-depth array would change a live tool's "
                + "declared type, which clients cache", "integer",
                ((Map<String, Object>) layoutWithinGroupProps(registry.getToolSpecifications().stream()
                        .filter(s -> "layout-within-group".equals(s.tool().name()))
                        .findFirst().orElseThrow()).get("columns")).get("type"));
    }

    @Test
    public void shouldIncludeLabelExpressionProperty_inUpdateViewObjectSpec() {
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
    public void updateViewObjectToolDescription_mentionsLabelExpression() {
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
    public void addToViewSpec_includesFigureTypeAndTextAlignmentAndVerticalAlignment() {
        assertStylingSurfaceProperties("add-to-view");
    }

    @Test
    public void addGroupToViewSpec_includesFigureTypeAndTextAlignmentAndVerticalAlignment() {
        assertStylingSurfaceProperties("add-group-to-view");
    }

    @Test
    public void addNoteToViewSpec_includesFigureTypeAndTextAlignmentAndVerticalAlignment() {
        // Notes silently ignore figureType at apply-time, but the schema property still appears
        // (uniform extract path via the shared addStylingProperties helper). The tool description
        // tells the LLM that figureType is ignored on notes.
        assertStylingSurfaceProperties("add-note-to-view");
    }

    @Test
    public void updateViewObjectSpec_includesFigureTypeAndTextAlignmentAndVerticalAlignment() {
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
    public void addToViewToolDescription_mentionsAllThreeNewParams() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "add-to-view".equals(s.tool().name()))
                .map(s -> s.tool().description())
                .findFirst().orElseThrow();
        assertTrue("description mentions figureType", desc.contains("figureType"));
        assertTrue("description mentions textAlignment", desc.contains("textAlignment"));
        assertTrue("description mentions verticalTextAlignment", desc.contains("verticalTextAlignment"));
    }

    @Test
    public void addGroupToViewToolDescription_mentionsAllThreeNewParams() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "add-group-to-view".equals(s.tool().name()))
                .map(s -> s.tool().description())
                .findFirst().orElseThrow();
        assertTrue(desc.contains("figureType"));
        assertTrue(desc.contains("textAlignment"));
        assertTrue(desc.contains("verticalTextAlignment"));
    }

    @Test
    public void updateViewObjectToolDescription_mentionsAllThreeNewParams() {
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
                        List.of("Element 'Hub Element' has 8 connections (large hub: > 6). "
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
                        List.of("Element 'Small Label Hub' has 10 connections (large hub: > 6). "
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
                        List.of("Element 'No Labels Hub' has 8 connections (large hub: > 6). "
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


    // ---- The three spacing convenience tools: the resize report reaching the wire ---------------
    //
    // All three embed the same `adjustResult`, and that record already carries `resizedElements`
    // with NON_EMPTY omission — so nothing was added to the three tool DTOs. What these pin is that
    // the field actually SURVIVES the envelope on each of the three paths, and that on a queued call
    // it stays inside the preview rather than reading as state the model already holds.

    private static final List<MovedViewObjectDto> RESIZED_LEAVES = List.of(
            new MovedViewObjectDto("vo-inner", "Inner", 20, 40, 356, 220),
            new MovedViewObjectDto("vo-hub", "Hub", 700, 60, 370, 306));

    private static AdjustViewSpacingResultDto adjustResultWithResizes() {
        return new AdjustViewSpacingResultDto(
                "v-1", 0, 0, 0, 0, 4, 2, "good", Map.of(), 0, 0, 120.0, List.of(),
                /*resolvedInterElementDelta=*/ 60,
                /*defaultResolutionReason=*/ "control_loop_synthesized_after_2_iterations",
                /*resizedAncestors=*/ List.of(), RESIZED_LEAVES);
    }

    private ApplyElementSpacingRecommendationsResultDto elementDto() {
        return new ApplyElementSpacingRecommendationsResultDto(
                "v-1", false, 5, 40, 120, 60, null, null, null, null,
                adjustResultWithResizes(), "budget_exhausted_after_2_iterations", 2,
                List.of(30, 30), null);
    }

    private ApplyGroupSpacingRecommendationsResultDto groupDto() {
        return new ApplyGroupSpacingRecommendationsResultDto(
                "v-1", false, 5, 2, true, 40, 120, 60, null, null, null, null,
                adjustResultWithResizes(), "budget_exhausted_after_2_iterations", 2,
                List.of(30, 30), null);
    }

    private ApplySpacingRecommendationsResultDto composerDto() {
        return new ApplySpacingRecommendationsResultDto(
                "v-1", "both", /*dryRun=*/ false, 5, 2, true, false,
                /*currentElementSpacingPx=*/ 40, /*currentGroupSpacingPx=*/ 40,
                /*elementTargetSpacingPx=*/ 120, /*groupTargetSpacingPx=*/ 120,
                /*proposedElementDelta=*/ 60, /*proposedGroupDelta=*/ 60,
                /*interElementDelta=*/ 60, /*interGroupDelta=*/ 60,
                /*elementKneeClampApplied=*/ false, /*groupKneeClampApplied=*/ false,
                /*noChangeReason=*/ null, /*elementTargetOverride=*/ null,
                /*groupTargetOverride=*/ null, /*before=*/ null, /*after=*/ null,
                adjustResultWithResizes(),
                "budget_exhausted_after_2_iterations", 2, List.of(30, 30),
                "budget_exhausted_after_2_iterations", 2, List.of(30, 30), null, null);
    }

    /** The three tool names, paired with a stub that returns the same populated adjustResult. */
    private void stubAllThreeWithResizes(Integer batchSequenceNumber) {
        accessor.setApplyElementSpacingBehavior((sid, vId, dry, target, budget) ->
                new MutationResult<>(elementDto(), batchSequenceNumber));
        accessor.setApplyGroupSpacingBehavior((sid, vId, dry, target, budget) ->
                new MutationResult<>(groupDto(), batchSequenceNumber));
        accessor.setApplySpacingBehavior((sid, vId, scope, dry, et, gt, budget) ->
                new MutationResult<>(composerDto(), batchSequenceNumber));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allThreeSpacingTools_shouldCarryTheResizedLeavesOnTheWire_onTheImmediatePath()
            throws Exception {
        // One assertion body over all three: a claim restated in three surfaces has to hold in all
        // of them, and pinning only the arm that happened to be written first is how the other two
        // ship silent.
        for (String tool : List.of("apply-element-spacing-recommendations",
                "apply-group-spacing-recommendations", "apply-spacing-recommendations")) {
            stubAllThreeWithResizes(/*batchSequenceNumber=*/ null);

            Map<String, Object> data = getResult(callAndParse(tool, Map.of("viewId", "v-1")));
            Map<String, Object> adjust = nested(data, "adjustResult");
            assertNotNull(tool + ": the embedded adjustResult must survive the envelope", adjust);

            List<Map<String, Object>> resized =
                    (List<Map<String, Object>>) adjust.get("resizedElements");
            assertNotNull(tool + ": the objects this call re-sized must reach the wire, at "
                    + "result.adjustResult.resizedElements. adjustResult was: " + adjust, resized);
            assertEquals(tool + ": one entry per object", 2, resized.size());
            assertEquals(tool + ": the hub's landed width", 370,
                    resized.get(1).get("newWidth"));
            assertEquals(tool + ": and its landed height", 306,
                    resized.get(1).get("newHeight"));
            assertEquals(tool + ": every entry carries an actionable handle", "vo-inner",
                    resized.get(0).get("viewObjectId"));
            assertNull(tool + ": resizedAncestors is not populated on this path and NON_EMPTY must "
                    + "therefore omit it entirely rather than emit an empty list",
                    adjust.get("resizedAncestors"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allThreeSpacingTools_shouldKeepTheResizedLeavesInsideThePreview_whenQueued()
            throws Exception {
        // In a batch nothing has executed, so the list is a projection of what WOULD land. The
        // obligation is discharged by LABELLING: the entity nests under 'preview' beside a 'batch'
        // sibling, and no part of it may sit at the top level of result where an agent reads it as
        // state the model holds.
        for (String tool : List.of("apply-element-spacing-recommendations",
                "apply-group-spacing-recommendations", "apply-spacing-recommendations")) {
            stubAllThreeWithResizes(/*batchSequenceNumber=*/ 4);

            Map<String, Object> data = getResult(callAndParse(tool, Map.of("viewId", "v-1")));
            assertTrue(tool + ": a queued call must nest its entity under preview: " + data,
                    data.containsKey("preview"));
            assertTrue(tool + ": the preview nesting needs its batch sibling: " + data,
                    data.containsKey("batch"));
            assertFalse(tool + ": the resize projection must NOT appear at the top level of result",
                    data.containsKey("resizedElements"));
            assertFalse(tool + ": nor may the adjustResult that carries it",
                    data.containsKey("adjustResult"));

            // And the fixture has to actually populate it — an always-empty list plus NON_EMPTY
            // omission satisfies "absent from the top level" however the labelling behaves, which
            // is a fixture that cannot discriminate.
            Map<String, Object> adjust = nested(nested(data, "preview"), "adjustResult");
            assertNotNull(tool + ": preview must still carry the projection: " + data, adjust);
            List<Map<String, Object>> resized =
                    (List<Map<String, Object>>) adjust.get("resizedElements");
            assertTrue(tool + ": the queued fixture must genuinely populate the list, or the "
                    + "top-level assertion above proves nothing. preview.adjustResult was: "
                    + adjust, resized != null && !resized.isEmpty());
            assertEquals(tool + ": and it must be the same projection, unaltered by queueing",
                    370, resized.get(1).get("newWidth"));
        }
    }

    @Test
    public void allThreeSpacingTools_shouldOmitTheListEntirely_whenTheCallResizedNothing()
            throws Exception {
        // The JSON convention is identical to adjust-view-spacing's: never null in Java, absent
        // from the wire when empty. A call that resized nothing must serialize byte-identically to
        // one from before the field was populated here.
        accessor.setApplyElementSpacingBehavior((sid, vId, dry, target, budget) ->
                new MutationResult<>(new ApplyElementSpacingRecommendationsResultDto(
                        "v-1", false, 5, 40, 120, 60, null, null, null, null,
                        new AdjustViewSpacingResultDto("v-1", 0, 0, 0, 0, 4, 2, "good", Map.of(),
                                0, 0, 120.0, List.of(), 60, "control_loop_synthesized_after_"
                                + "1_iterations", List.of(), List.of()),
                        "budget_exhausted_after_1_iterations", 1, List.of(60), null), null));

        Map<String, Object> data = getResult(callAndParse(
                "apply-element-spacing-recommendations", Map.of("viewId", "v-1")));
        assertNull("an empty list is omitted, not emitted as []",
                nested(data, "adjustResult").get("resizedElements"));
    }


    // ------------------------------------------------------------------
    // The rating-regression disclosure reaches the wire on all three tools
    //
    // The loop that produces the two snapshots cannot execute in either automated lane, so the
    // comparison is deliberately a pure function of them and the wire is driven from a hand-built
    // DTO. What is under test here is exactly the part that IS reachable: that the entity carries
    // the warning, and that nextSteps says the right thing on the immediate path and abstains on
    // the queued one.
    // ------------------------------------------------------------------

    /** The minimal delegate result the three spacing DTOs all embed. */
    private static AdjustViewSpacingResultDto disclosureAdjustResult() {
        return new AdjustViewSpacingResultDto("v-1", 0, 0, 0, 0, 4, 2, "poor", Map.of(),
                0, 0, 120.0, List.of(), 60, "control_loop_synthesized_after_1_iterations",
                List.of(), List.of());
    }

    /** One regression warning, shaped exactly as the accessor emits it. */
    private static List<StructuredWarningDto> regressionWarning(String tool) {
        return List.of(new StructuredWarningDto(
                StructuredWarningCodes.SPACING_RATING_REGRESSED,
                tool + " left this view worse than it found it: overall rating dropped from "
                        + "'excellent' to 'poor'. Worsened: element overlaps 0 -> 5. The spacing "
                        + "was applied anyway.",
                "undo", List.of()));
    }

    /** Stubs all three arms with the same disclosure state and dispatch mode. */
    private void stubAllThreeWithDisclosure(
            boolean regressed, Integer batchSequenceNumber) {
        accessor.setApplyElementSpacingBehavior((sid, vId, dry, target, budget) ->
                new MutationResult<>(new ApplyElementSpacingRecommendationsResultDto(
                        "v-1", false, 5, 40, 120, 60, null, null, null, null,
                        disclosureAdjustResult(),
                        "budget_exhausted_after_1_iterations", 1, List.of(60), null,
                        regressed ? regressionWarning("apply-element-spacing-recommendations")
                                : List.of()),
                        batchSequenceNumber));
        accessor.setApplyGroupSpacingBehavior((sid, vId, dry, target, budget) ->
                new MutationResult<>(new ApplyGroupSpacingRecommendationsResultDto(
                        "v-1", false, 5, 3, true, 40, 120, 60, null, null, null, null,
                        disclosureAdjustResult(),
                        "budget_exhausted_after_1_iterations", 1, List.of(60), null,
                        regressed ? regressionWarning("apply-group-spacing-recommendations")
                                : List.of()),
                        batchSequenceNumber));
        accessor.setApplySpacingBehavior((sid, vId, scope, dry, et, gt, budget) ->
                new MutationResult<>(new ApplySpacingRecommendationsResultDto(
                        "v-1", "both", false, 5, 3, true, false,
                        40, 40, 120, 120, 60, 60, 60, 60, false, false,
                        null, null, null, null, null,
                        disclosureAdjustResult(),
                        "budget_exhausted_after_1_iterations", 1, List.of(60),
                        "budget_exhausted_after_1_iterations", 1, List.of(60), null, null,
                        regressed ? regressionWarning("apply-spacing-recommendations")
                                : List.of()),
                        batchSequenceNumber));
    }

    private static final List<String> THREE_SPACING_TOOLS = List.of(
            "apply-element-spacing-recommendations",
            "apply-group-spacing-recommendations",
            "apply-spacing-recommendations");

    private String spacingToolDescription(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tool not registered: " + toolName))
                .tool().description();
    }

    @Test
    public void allThreeSpacingTools_shouldStateInTheirOwnDescriptionThatTheyCanReturnAWorseView() {
        // A description is transmitted once, at schema time, and is the only thing an agent that
        // has not read the checklist ever sees. All three used to promise the opposite of this.
        //
        // This pin exists because the claim is restated in three separate literals and the
        // composed tool's copy is split across a Java string-concatenation boundary — the obvious
        // grep for it returns two hits, not three, so a census built from that grep would leave
        // the false guarantee standing in exactly the tool this defect was reported against.
        for (String tool : THREE_SPACING_TOOLS) {
            String desc = spacingToolDescription(tool);

            assertFalse(tool + ": the false guarantee must be gone",
                    desc.contains("never presents a silently-degraded view")
                            || desc.contains("never a silently-degraded view"));
            assertTrue(tool + ": the description must say the tool CAN return a worse view",
                    desc.contains("return a view rated worse than the one it was handed"));
            assertTrue(tool + ": and name the signal that reports it",
                    desc.contains("SPACING_RATING_REGRESSED"));
            assertTrue(tool + ": and name the remedy, standalone",
                    desc.contains("one undo reverses the whole call"));
            assertTrue(tool + ": and say the comparison is unavailable on a queued call",
                    desc.contains("queued (batch) call the comparison cannot be taken"));
        }
    }

    @Test
    public void allThreeSpacingTools_shouldKeepTheScopedPassHonestClaim() {
        // The two claims are NOT the same claim and must not get the same edit. This one sits
        // inside the PASS-HONEST paragraph, where it is scoped to that branch and true of it.
        // Deleting an accurate sentence is as much a defect as keeping a false one.
        for (String tool : THREE_SPACING_TOOLS) {
            String desc = spacingToolDescription(tool);
            // Two spellings ship: the element arm says "the best (never-degraded) state" while the
            // other two say "the best non-degraded state". A grep on either phrase alone returns
            // two hits, not three — which is exactly how one of them gets edited alone.
            assertTrue(tool + ": the scoped PASS-HONEST claim is accurate and stays, in whichever "
                            + "of its two shipped spellings this tool uses",
                    desc.contains("preserves the best (never-degraded) state")
                            || desc.contains("preserve the best non-degraded state"));
        }
    }

    @Test
    public void allThreeSpacingTools_shouldDiscloseThatTheGoalReachedBranchCannotFire() {
        // The loop's goal predicate defaults to false and the spacing callback factory never
        // overrides it, so branch (a) — the FIRST row of the published taxonomy — cannot fire
        // through these tools. An enumeration whose headline outcome is dead is the same
        // over-claim as the guarantee above.
        for (String tool : THREE_SPACING_TOOLS) {
            String desc = spacingToolDescription(tool);
            assertTrue(tool + ": branch (a) is still enumerated",
                    desc.contains("goal_reached_at_iteration_N"));
            // Assert the WHOLE phrase, spaces included. A description is assembled from dozens of
            // concatenated literals, and a dropped trailing space fuses two words into one
            // ("butNOT REACHABLE") while every contains("NOT REACHABLE") assertion stays green.
            assertTrue(tool + ": and must be marked unreachable through this tool, without a "
                            + "concatenation seam fusing it to the words either side",
                    desc.contains("met, but NOT REACHABLE through this tool")
                            || desc.contains("(NOT REACHABLE through this tool"));
            assertTrue(tool + ": and the clause must close cleanly into the next branch",
                    desc.contains("the constant exists); (b) budget_exhausted"));
            assertFalse(tool + ": no fused words at the concatenation seams",
                    desc.contains("butNOT") || desc.contains("exists);(b)"));

            // Seventh tally surface, found live: the iterationBudget PARAMETER description carried
            // its own hand-maintained count ("six in-loop branches") beside the tool description's
            // correct ten. Only the checklist keeps a number, and only because a test holds it
            // there — every unpinned count is deleted, this one included.
            assertFalse(tool + ": the parameter description must not restate a branch count",
                    desc.contains("six in-loop branches") || desc.contains("eight branches"));
        }
    }

    /**
     * A paragraph published identically by tools with different DTOs must name no field only some
     * of them have.
     *
     * <p>The paragraph explaining what {@code N} counts is emitted byte-identically by all three
     * spacing tools, but their response DTOs are not alike: the two single-arm tools carry a bare
     * {@code terminationReason} / {@code iterationCount}, while the composed tool carries only the
     * per-arm {@code elementTerminationReason} / {@code groupTerminationReason} and
     * {@code elementIterationCount} / {@code groupIterationCount}. Naming the bare fields in the
     * shared paragraph therefore told the composed tool's caller to read fields that do not exist
     * on it — three sentences after that same description had correctly named the per-arm ones, so
     * one served string contradicted itself.</p>
     *
     * <p>The paragraph now points at "the iteration-count field named above". Each tool's own
     * enumeration sentence precedes it and names that tool's actual fields, so one shared string
     * is true on all three surfaces.</p>
     *
     * <p><strong>Scoped to the shared paragraph, not to the whole description.</strong> Banning
     * the bare names outright would be wrong: the composed tool's own prose legitimately writes
     * "Per-arm terminationReason fields surface in the response DTO (elementTerminationReason /
     * groupTerminationReason)", which is accurate and disambiguates itself on the same line. The
     * invariant is about the paragraph that cannot know which tool is rendering it.</p>
     *
     * <p>Casing does the discriminating work: {@code elementIterationCount} does not contain
     * {@code iterationCount} — the embedded spelling is {@code IterationCount} — so a bare-name
     * search cannot be satisfied by the per-arm field it must distinguish from.</p>
     */
    @Test
    public void theSharedIterationParagraph_mustNameNoFieldOnlySomeToolsCarry() {
        String open = "NOTE — in budget_exhausted_after_N_iterations";
        String close = "when you want iterations.";

        String first = null;
        for (String tool : THREE_SPACING_TOOLS) {
            String desc = spacingToolDescription(tool);
            int a = desc.indexOf(open);
            assertTrue(tool + ": the shared paragraph must be published", a >= 0);
            int b = desc.indexOf(close, a);
            assertTrue(tool + ": the shared paragraph must be terminated", b >= 0);
            String para = desc.substring(a, b + close.length());

            assertFalse(tool + ": the shared paragraph must not name a bare terminationReason —"
                            + " the composed tool has only the per-arm fields: " + para,
                    para.contains("terminationReason"));
            assertFalse(tool + ": the shared paragraph must not name a bare iterationCount —"
                            + " the composed tool has only the per-arm fields: " + para,
                    para.contains("iterationCount"));

            if (first == null) {
                first = para;
            } else {
                assertEquals(tool + ": the paragraph is published byte-identically by all three,"
                        + " which is why it may not name a per-tool field", first, para);
            }
        }
    }

    /**
     * Each tool must still name the response fields it DOES carry — the fix above must not have
     * scrubbed a pointer that resolves.
     */
    @Test
    public void eachSpacingTool_mustNameTheTerminationFieldsItActuallyCarries() {
        for (String tool : List.of("apply-element-spacing-recommendations",
                "apply-group-spacing-recommendations")) {
            String desc = spacingToolDescription(tool);
            assertTrue(tool + " carries the bare fields and must name them: " + desc,
                    desc.contains("terminationReason") && desc.contains("iterationCount"));
        }

        String composed = spacingToolDescription("apply-spacing-recommendations");
        assertTrue("the composed tool must name its per-arm fields: " + composed,
                composed.contains("elementTerminationReason")
                        && composed.contains("groupTerminationReason")
                        && composed.contains("elementIterationCount")
                        && composed.contains("groupIterationCount"));
    }

    @Test
    public void allThreeSpacingTools_shouldSayWhatNCountsInTheIterationTokens() {
        // The tokens say "_iterations" and interpolate a COMMAND count; an escalate iteration
        // pushes two commands. Rather than change three published values, every surface that
        // carries the tokens now says what the number is — on all three tokens or on none.
        for (String tool : THREE_SPACING_TOOLS) {
            String desc = spacingToolDescription(tool);
            assertTrue(tool + ": the description must say N counts commands",
                    desc.contains("N counts accepted COMMANDS"));
            assertTrue(tool + ": and why the two numbers differ",
                    desc.contains("escalate iteration pushes TWO commands"));
            assertTrue(tool + ": and that the pairing is consistent rather than a contradiction",
                    desc.contains("is consistent"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allThreeSpacingTools_shouldCarryTheRegressionWarningOnTheWire_whenItRegressed()
            throws Exception {
        // The blind spot is in the step scalar, which all three share, so a fix on the composed
        // tool alone would leave the other two shipping the same silence.
        for (String tool : THREE_SPACING_TOOLS) {
            stubAllThreeWithDisclosure(/*regressed=*/ true, /*batchSequenceNumber=*/ null);

            Map<String, Object> data = getResult(callAndParse(tool, Map.of("viewId", "v-1")));
            List<Map<String, Object>> warnings =
                    (List<Map<String, Object>>) data.get("structuredWarnings");

            assertNotNull(tool + ": the disclosure must reach result.structuredWarnings, not stop "
                    + "at the DTO. result was: " + data, warnings);
            assertEquals(tool + ": at most one entry per call", 1, warnings.size());
            assertEquals(tool + ": under the published code",
                    "SPACING_RATING_REGRESSED", warnings.get(0).get("code"));
            assertEquals(tool + ": and the applied state is what should change",
                    "undo", warnings.get(0).get("remediationTool"));
            assertTrue(tool + ": the message must name the tool the caller actually invoked",
                    String.valueOf(warnings.get(0).get("message")).startsWith(tool));
        }
    }

    @Test
    public void allThreeSpacingTools_shouldOmitTheWarningListEntirely_whenNothingRegressed()
            throws Exception {
        // The ordinary case must serialize byte-identically to a response from before the field
        // existed — an empty array on every clean run is a new, permanent contract for nothing.
        for (String tool : THREE_SPACING_TOOLS) {
            stubAllThreeWithDisclosure(/*regressed=*/ false, /*batchSequenceNumber=*/ null);

            Map<String, Object> data = getResult(callAndParse(tool, Map.of("viewId", "v-1")));
            assertNull(tool + ": an empty list is omitted, not emitted as []",
                    data.get("structuredWarnings"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allThreeSpacingTools_shouldAddTheRegressionNextStep_whenItRegressed()
            throws Exception {
        for (String tool : THREE_SPACING_TOOLS) {
            stubAllThreeWithDisclosure(/*regressed=*/ true, /*batchSequenceNumber=*/ null);

            List<String> steps = (List<String>) callAndParse(
                    tool, Map.of("viewId", "v-1")).get("nextSteps");

            assertNotNull(tool + ": nextSteps must be present", steps);
            assertTrue(tool + ": the response's own guidance must say the call went backwards, "
                            + "not leave it to a structured field the caller may not read. "
                            + "nextSteps was: " + steps,
                    steps.stream().anyMatch(x -> x.contains("worse than it found it")));
            assertTrue(tool + ": and it must say one undo is enough. nextSteps was: " + steps,
                    steps.stream().anyMatch(x -> x.contains("one undo is enough")));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allThreeSpacingTools_shouldNotAddTheRegressionNextStep_whenNothingRegressed()
            throws Exception {
        // The pre-existing unconditional "use undo if unsatisfactory" line fires on clean runs too
        // and says nothing was measured; it must not be mistaken for the disclosure.
        for (String tool : THREE_SPACING_TOOLS) {
            stubAllThreeWithDisclosure(/*regressed=*/ false, /*batchSequenceNumber=*/ null);

            List<String> steps = (List<String>) callAndParse(
                    tool, Map.of("viewId", "v-1")).get("nextSteps");

            assertFalse(tool + ": a clean run must not claim a regression. nextSteps was: " + steps,
                    steps.stream().anyMatch(x -> x.contains("worse than it found it")));
            assertFalse(tool + ": nor may it abstain — the comparison DID run. nextSteps was: "
                            + steps,
                    steps.stream().anyMatch(x -> x.contains("could not be taken for a queued")));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allThreeSpacingTools_shouldAbstainInWords_whenTheCallWasQueued() throws Exception {
        // On the queued path the comparison is structurally blind: the accepted commands were
        // queued rather than executed and the loop already reset the model, so the "after" snapshot
        // re-reads the unmutated view. Saying nothing there reads as "nothing regressed", which is
        // a false all-clear about a check that never ran.
        for (String tool : THREE_SPACING_TOOLS) {
            stubAllThreeWithDisclosure(/*regressed=*/ false, /*batchSequenceNumber=*/ 4);

            List<String> steps = (List<String>) callAndParse(
                    tool, Map.of("viewId", "v-1")).get("nextSteps");

            assertTrue(tool + ": a queued call must name the coverage gap rather than omit it. "
                            + "nextSteps was: " + steps,
                    steps.stream().anyMatch(x -> x.contains("could not be taken for a queued")));
            assertTrue(tool + ": and it must name the call that closes it. nextSteps was: " + steps,
                    steps.stream().anyMatch(x -> x.contains("assess-layout")
                            && x.contains("end-batch")));
            assertFalse(tool + ": and it must not claim a regression it could not measure. "
                            + "nextSteps was: " + steps,
                    steps.stream().anyMatch(x -> x.contains("worse than it found it")));
            assertTrue(tool + ": the existing queue guidance must survive. nextSteps was: " + steps,
                    steps.stream().anyMatch(x -> x.contains("end-batch to commit")));
        }
    }

    @Test
    public void allThreeSpacingTools_shouldDocumentTheResizeReportInWordsThatAgree()
            throws Exception {
        // A claim restated in three descriptions has to say the same thing in all three, or the
        // agent that reads two of them learns two different contracts. The three now share one
        // constant, so the agreement is structural and the equality assertion below can only fail
        // if someone re-inlines an edited copy at one site. What still genuinely discriminates is
        // the rest: that each of the three descriptions actually REFERENCES it (a site that never
        // got the sentence is what a value grep cannot see), and that the sentence makes every
        // promise the response has to keep.
        String canonical = null;
        for (String tool : List.of("apply-element-spacing-recommendations",
                "apply-group-spacing-recommendations", "apply-spacing-recommendations")) {
            String description = registry.getToolSpecifications().stream()
                    .filter(spec -> tool.equals(spec.tool().name()))
                    .findFirst().orElseThrow().tool().description();

            int start = description.indexOf("RESPONSE: `resizedElements`");
            assertTrue(tool + ": must document what resizedElements covers", start >= 0);
            String paragraph = description.substring(start,
                    description.indexOf("do not read it as applied.", start)
                            + "do not read it as applied.".length());

            assertTrue(tool + ": must say the hub resize is in the list — it is the largest resize "
                    + "these tools perform and was reported on no surface at all before",
                    paragraph.contains("one-shot hub resize"));
            assertTrue(tool + ": must say a reverted step is NOT in the list",
                    paragraph.contains("reverted") && paragraph.contains("not evidence"));
            assertTrue(tool + ": must say resizedAncestors is not populated on this path",
                    paragraph.contains("`resizedAncestors` is NOT populated on this path"));
            assertTrue(tool + ": must carry the batch sentence — nothing has executed, so the list "
                    + "is a projection nested under preview",
                    paragraph.contains("In a batch nothing has executed yet")
                            && paragraph.contains("nested under `preview`"));
            assertFalse(tool + ": must NOT name an approval path. These three tools have none — no "
                    + "storeAsProposal call in the facade names them — and describing one would be "
                    + "a claim about a path that does not exist",
                    paragraph.contains("pending approval"));

            if (canonical == null) {
                canonical = paragraph;
            } else {
                assertEquals(tool + ": the paragraph must be word-for-word what the siblings say",
                        canonical, paragraph);
            }
        }
    }

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
            case "apply-element-spacing-recommendations" ->
                    handler.handleApplyElementSpacingRecommendations(null, request);
            case "apply-group-spacing-recommendations" ->
                    handler.handleApplyGroupSpacingRecommendations(null, request);
            case "apply-spacing-recommendations" ->
                    handler.handleApplySpacingRecommendations(null, request);
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
        // The compact key set STATED, not counted. A bare size assertion is a hand-maintained
        // tally: it goes stale whenever a key is added, it cannot say which key went missing, and
        // it passes just as happily when one key is swapped for another. Listing them asserts
        // membership, absence of extras, and the emission order in one go.
        assertEquals("exactly the compact keys — no breakdown / violatorIds / descriptions",
                COMPACT_SWEEP_KEYS, List.copyOf(a.keySet()));
        // The overview must carry both cross-branch signals, otherwise a whole-model sweep can
        // see a view rated poor and have nothing in the compact entry explaining why.
        assertTrue("compact entry must carry cousinOverlapCount",
                a.containsKey("cousinOverlapCount"));
        assertTrue("compact entry must carry boundaryViolationCount",
                a.containsKey("boundaryViolationCount"));
        assertEquals("View A", a.get("name"));
        assertEquals("fair", a.get("overallRating"));
        assertEquals("excellent", a.get("overallExcludingAcceptedCosmetics"));
        assertEquals(5, a.get("elementCount"));
        assertEquals(3, a.get("connectionCount"));
        assertEquals(0, a.get("overlapCount"));
        assertEquals(0, a.get("nonOrthogonalTerminalCount"));
        assertEquals(0, a.get("crossElementPassThroughCount"));

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

        assertEquals("the compact keys are all present even with a null breakdown",
                COMPACT_SWEEP_KEYS, List.copyOf(x.keySet()));
        assertEquals("excellent", x.get("overallRating"));
        assertEquals("excellent", x.get("overallExcludingAcceptedCosmetics"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_allViewsScope_shouldCarryOwnIconOverLabelCount() throws Exception {
        // The whole-model sweep is the close-out tool: one call, every diagram. A view whose
        // element names are buried under their own icons rates excellent, so the headline cannot
        // surface it and the compact entry is the only place it could appear. Presence of the key
        // is not enough — a key that is always zero would pass while carrying no measurement, so
        // the value is asserted against a view that really has three.
        accessor.setViews(List.of(new ViewDto("v-g", "View G", "Layered", "/")));
        accessor.setAssessLayoutBehavior(vId -> withOwnIconOverLabelCount(
                new AssessLayoutResultDto(
                        vId, 79, 0, 0, 0, 0, 0.0, 145.0, 100, "excellent", null,
                        null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                        0, null, 0, null, 0, null, null, List.of("ok")),
                3));

        Map<String, Object> envelope = callAndParse("assess-layout",
                Map.of("scope", "all-views"));
        Map<String, Object> result = getResult(envelope);
        Map<String, Object> g = (Map<String, Object>) result.get("v-g");

        assertTrue("the compact entry must carry ownIconOverLabelCount",
                g.containsKey("ownIconOverLabelCount"));
        assertEquals("and must carry the measured value, not a placeholder zero",
                3, g.get("ownIconOverLabelCount"));
    }

    @Test
    public void buildAssessLayoutNextSteps_ownIconOverLabel_shouldEmitOnEveryRating() {
        // The step must not live inside an arm of the rating switch. "excellent" and the default
        // arm both contribute nothing, so a step placed inside the switch would be swallowed on
        // exactly the ratings this metric reaches — it moves no rating, so a view carrying it is
        // typically rated clean.
        for (String rating : List.of("excellent", "fair", "not-applicable")) {
            AssessLayoutResultDto dto = withOwnIconOverLabelCount(
                    new AssessLayoutResultDto(
                            "v-1", 3, 0, 0, 0, 0, 0.0, 145.0, 100, rating, null,
                            null, null, List.of(), null, 0, null, 0, null, 0, null,
                            false, 0, 0, null,
                            0, null, 0, null, 0, null, null, List.of(),
                            0, null, 0, null, 0, null, 1.0, null,
                            rating, rating,
                            1.0, null),
                    3);

            List<String> steps = handler.buildAssessLayoutNextSteps(dto);

            String step = steps.stream()
                    .filter(t -> t.contains("own icon"))
                    .findFirst().orElse(null);
            assertNotNull("rating=" + rating + " must still name the collision: " + steps, step);
            assertTrue("rating=" + rating + ": the step must carry the count, not a flag: " + step,
                    step.contains("3 element"));
            assertTrue("rating=" + rating + ": the step must name a remedy: " + step,
                    step.contains("update-view-object"));
        }
    }

    @Test
    public void buildAssessLayoutNextSteps_ownIconOverLabel_shouldPrecedeTheExportViewTail() {
        // Precondition-class placement: above the rating switch, so it is read before the
        // unconditional tail rather than appended after the rating-graduated advice.
        AssessLayoutResultDto dto = withOwnIconOverLabelCount(
                new AssessLayoutResultDto(
                        "v-1", 3, 0, 0, 0, 0, 0.0, 145.0, 100, "excellent", null,
                        null, null, List.of(), null, 0, null, 0, null, 0, null,
                        false, 0, 0, null,
                        0, null, 0, null, 0, null, null, List.of(),
                        0, null, 0, null, 0, null, 1.0, null,
                        "excellent", "excellent",
                        1.0, null),
                2);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);
        int icon = -1;
        int export = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (icon < 0 && steps.get(i).contains("own icon")) icon = i;
            if (export < 0 && steps.get(i).contains("export-view")) export = i;
        }
        assertTrue("the collision step must be present: " + steps, icon >= 0);
        assertTrue("the export-view tail must be present: " + steps, export >= 0);
        assertTrue("the collision step must precede the export-view tail: " + steps, icon < export);
    }

    @Test
    public void buildAssessLayoutNextSteps_noOwnIconOverLabel_shouldStaySilent() {
        // A zero count must add nothing — otherwise every clean view carries a step about a
        // defect it does not have.
        AssessLayoutResultDto dto = new AssessLayoutResultDto(
                "v-1", 3, 0, 0, 0, 0, 0.0, 145.0, 100, "excellent", null,
                null, null, List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                "excellent", "excellent",
                1.0, null);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        assertTrue("a zero count must emit nothing: " + steps,
                steps.stream().noneMatch(t -> t.contains("own icon")));
    }

    @Test
    public void assessLayoutServedSurface_mustEnumerateTheSweepKeyItActuallyReturns() {
        // The summary key set is enumerated in TWO served places — the tool description and the
        // `scope` property's own schema description — and a caller reads whichever its client
        // renders. A key returned on the wire but absent from an enumeration that claims to list
        // the value's shape is a surface that contradicts the response, so both are pinned. A
        // grep for the metric name cannot catch this drift: the defect is an enumeration that
        // OMITS the name, which matches no search for it.
        var spec = registry.getToolSpecifications().stream()
                .filter(t -> "assess-layout".equals(t.tool().name()))
                .findFirst().orElseThrow();
        String description = spec.tool().description();
        String schema = spec.tool().inputSchema().toString();

        // Presence only — the exact position is pinned by the order-parity test below, which
        // compares both enumerations against the order the response actually emits.
        assertTrue("the tool description's key enumeration must name the key the sweep returns",
                description.contains("ownIconOverLabelCount"));
        assertTrue("...and must say why it is carried despite moving no rating",
                description.contains("drill into a non-zero count"));
        assertTrue("the scope parameter's own schema description must name it too",
                schema.contains("ownIconOverLabelCount"));
    }

    @Test
    public void buildAssessLayoutNextSteps_ownIconOverLabel_shouldNotOverclaimACappedDescriptionList() {
        // The count can outrun the capped description list, and this dimension publishes no
        // violator-id key, so the remainder sits in no field at all. The step must say so rather
        // than sending the caller to a list that stops short.
        AssessLayoutResultDto base = new AssessLayoutResultDto(
                "v-1", 79, 0, 0, 0, 0, 0.0, 145.0, 100, "excellent", null,
                null, null, List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                "excellent", "excellent",
                1.0, null);
        List<String> ten = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add("Element 'E" + i + "' icon overlaps its own title label");
        }
        AssessLayoutResultDto dto = withComponent(
                withComponent(base, "ownIconOverLabelCount", 17),
                "ownIconOverLabelDescriptions", List.copyOf(ten));

        String step = handler.buildAssessLayoutNextSteps(dto).stream()
                .filter(t -> t.contains("own icon"))
                .findFirst().orElseThrow();

        assertFalse("a capped list must not be offered as naming every object: " + step,
                step.contains("Read ownIconOverLabelDescriptions for the objects and the "
                        + "alignment each one uses"));
        assertTrue("the shortfall must be stated: " + step,
                step.contains("The first 10 are named in assess-layout's "
                        + "ownIconOverLabelDescriptions"));
        assertTrue("...and quantified so the caller knows what is missing: " + step,
                step.contains("remaining 7"));
        assertTrue("the remedy must survive the rewording: " + step,
                step.contains("update-view-object"));
        // Read off the live wire: the clause boundary was a semicolon followed by a capitalised
        // sentence. A substring pin cannot see punctuation it does not quote, so quote it.
        assertFalse("a capitalised sentence must not follow a semicolon: " + step,
                step.contains("; Use update-view-object"));
        assertTrue("the remedy must open its own sentence: " + step,
                step.contains("render. Use update-view-object"));
    }

    /**
     * Both corrected next-step sentences are pinned across their BOUNDARY, not by fragment.
     *
     * <p>Each of these clauses is appended after a full stop that lives in the preceding literal,
     * so the defect is entirely a matter of what precedes the token: the step read "…buried under
     * the glyph. ownIconOverLabelDescriptions names the first N…" and "…growing the parent.
     * auto-layout-and-route will not clear it…". A pin asserting merely that the identifier
     * appears is green under the defect and certifies nothing, so these assertions quote the two
     * characters in front of it.</p>
     *
     * <p>Neither token may be capitalised to fix this. {@code ownIconOverLabelDescriptions} is a
     * JSON key the caller reads off the response and {@code auto-layout-and-route} is a tool name
     * the caller invokes; no other casing of either resolves anywhere. The sentence is recomposed
     * around them instead.</p>
     */
    @Test
    public void buildAssessLayoutNextSteps_appendedClausesMustNotOpenWithALowercaseToken() {
        List<String> ten = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add("Element 'E" + i + "' icon overlaps its own title label");
        }
        AssessLayoutResultDto dto = withComponent(
                withComponent(
                        withComponent(
                                withComponent(ratedDto("poor"),
                                        "ownIconOverLabelCount", 17),
                                "ownIconOverLabelDescriptions", List.copyOf(ten)),
                        "parentLabelObscuredCount", 17),
                "parentLabelObscuredDescriptions", List.copyOf(ten));

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        String icon = steps.stream().filter(t -> t.contains("own icon"))
                .findFirst().orElseThrow();
        String parent = steps.stream().filter(t -> t.contains("topmost child"))
                .findFirst().orElseThrow();

        assertFalse("the icon step must not start a sentence with the JSON key: " + icon,
                icon.contains(". ownIconOverLabelDescriptions"));
        assertTrue("...the key must still be published verbatim and lowercase: " + icon,
                icon.contains("ownIconOverLabelDescriptions"));
        assertTrue("...qualified by the tool that publishes it: " + icon,
                icon.contains("assess-layout's ownIconOverLabelDescriptions"));

        assertFalse("the parent step must not start a sentence with the tool name: " + parent,
                parent.contains(". auto-layout-and-route"));
        assertTrue("...the tool name must still be published verbatim and lowercase: " + parent,
                parent.contains("auto-layout-and-route will not clear it"));
    }

    /**
     * The empty arm and the whole-list arm of the icon clause are unaffected by the recomposition.
     *
     * <p>The full stop that the recomposed clause is appended after lives in the literal BEFORE
     * the clause, shared by all three arms. Converting the middle arm to a continuation would have
     * meant deleting that stop, which reads correctly only for the middle arm and silently breaks
     * the other two into run-ons ("…under the glyph Use update-view-object…"). The stop is kept
     * and only the middle arm recomposed; these assertions hold the other two arms to that.</p>
     */
    @Test
    public void buildAssessLayoutNextSteps_ownIconOverLabel_everyArmMustCloseTheGlyphSentence() {
        AssessLayoutResultDto empty = withComponent(ratedDto("excellent"),
                "ownIconOverLabelCount", 3);
        AssessLayoutResultDto whole = withComponent(
                withComponent(ratedDto("excellent"), "ownIconOverLabelCount", 2),
                "ownIconOverLabelDescriptions",
                List.of("Element 'A' icon overlaps its own title label",
                        "Element 'B' icon overlaps its own title label"));

        String emptyArm = handler.buildAssessLayoutNextSteps(empty).stream()
                .filter(t -> t.contains("own icon")).findFirst().orElseThrow();
        String wholeArm = handler.buildAssessLayoutNextSteps(whole).stream()
                .filter(t -> t.contains("own icon")).findFirst().orElseThrow();

        assertTrue("with no descriptions the remedy follows the glyph sentence directly: "
                + emptyArm, emptyArm.contains("under the glyph. Use update-view-object"));
        assertTrue("with a complete list the read-the-field clause opens its own sentence: "
                + wholeArm, wholeArm.contains("under the glyph. Read ownIconOverLabelDescriptions"));
    }


    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_allViewsScope_shouldNotAdviseOnAnIconFindingTheSweepDidNotMake() throws Exception {
        // Advice to act on a finding nothing reported is noise, and noise in a close-out sweep's
        // steps is what stops the real entries being read.
        accessor.setViews(List.of(new ViewDto("v-clean", "Clean", "Layered", "/")));
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 5, 3, 0, 0, 0, 0.0, 50.0, 90, "excellent", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of("ok")));

        Map<String, Object> envelope = callAndParse("assess-layout",
                Map.of("scope", "all-views"));
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");

        assertTrue("a clean sweep must not carry the icon drill-in step: " + nextSteps,
                nextSteps.stream().noneMatch(t -> t.contains("ownIconOverLabelCount")));
        assertFalse("...but the sweep's standing guidance must survive", nextSteps.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_allViewsScope_shouldAdviseWhenTheSweepDidFindOne() throws Exception {
        // The complement — the gate must not silence a real finding.
        accessor.setViews(List.of(new ViewDto("v-g", "View G", "Layered", "/")));
        accessor.setAssessLayoutBehavior(vId -> withComponent(
                new AssessLayoutResultDto(
                        vId, 79, 0, 0, 0, 0, 0.0, 145.0, 100, "excellent", null,
                        null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                        0, null, 0, null, 0, null, null, List.of("ok")),
                "ownIconOverLabelCount", 17));

        Map<String, Object> envelope = callAndParse("assess-layout",
                Map.of("scope", "all-views"));
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");

        assertTrue("a sweep that found one must say so: " + nextSteps,
                nextSteps.stream().anyMatch(t -> t.contains("ownIconOverLabelCount")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_allViewsScope_servedKeyOrderMustMatchTheOrderActuallyEmitted() throws Exception {
        // The compact entry is a LinkedHashMap, so its insertion order IS the wire order, and both
        // served enumerations claim to describe that shape. A description that lists the keys in a
        // different order than the response emits them is a surface contradicting the response.
        accessor.setViews(List.of(new ViewDto("v-1", "V", "Layered", "/")));
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 5, 3, 0, 0, 0, 0.0, 50.0, 90, "good", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of("ok")));

        Map<String, Object> envelope = callAndParse("assess-layout",
                Map.of("scope", "all-views"));
        Map<String, Object> entry =
                (Map<String, Object>) getResult(envelope).get("v-1");
        String emitted = String.join(", ", entry.keySet());

        var spec = registry.getToolSpecifications().stream()
                .filter(t -> "assess-layout".equals(t.tool().name()))
                .findFirst().orElseThrow();
        String flattenedDescription = spec.tool().description().replaceAll("\\s+", " ");
        String flattenedSchema = spec.tool().inputSchema().toString().replaceAll("\\s+", " ");

        assertTrue("the tool description must enumerate the keys in the order emitted ("
                        + emitted + ")", flattenedDescription.contains(emitted));
        assertTrue("the scope schema description must too (" + emitted + ")",
                flattenedSchema.contains(emitted));
    }

    // ---- The rating switch must not prescribe a lever that cannot move the cause ----

    @Test
    public void buildAssessLayoutNextSteps_parentLabelObscured_namesThePaddingLeverNotElk() {
        // A view held at "poor" by an obscured parent label alone reaches the poor arm's generic
        // fallback: passThroughDominated is false and the perception-routing predicate contains
        // none of the rating-bearing layout metrics, so it fell through to auto-layout-and-route.
        // Automated layout re-positions elements; the clearance above a child is a padding
        // decision, so the caller was marked down to the worst rating the tool gives and handed
        // the one lever that cannot move it.
        AssessLayoutResultDto dto =
                withComponent(ratedDto("poor"), "parentLabelObscuredCount", 1);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        String named = steps.stream()
                .filter(t -> t.contains("topmost child"))
                .findFirst().orElse(null);
        assertNotNull("the cause holding the rating down must be named: " + steps, named);
        assertTrue("...with the lever that actually moves it: " + named,
                named.contains("update-view-object"));
        assertFalse("and the lever that cannot must not be prescribed: " + steps,
                steps.stream().anyMatch(t -> t.contains("Use auto-layout-and-route (ELK)")));
    }

    @Test
    public void buildAssessLayoutNextSteps_parentLabelObscured_emitsOnEveryRatingTheMetricReaches() {
        // Placed ABOVE the rating switch. The metric vetoes the overall rating to "poor", but the
        // step must not depend on that: a caller re-running after a partial fix, or reading a
        // degenerate view, reaches arms that contribute nothing, and a step inside the switch
        // would be swallowed there.
        for (String rating : List.of("excellent", "good", "fair", "poor", "not-applicable")) {
            AssessLayoutResultDto dto =
                    withComponent(ratedDto(rating), "parentLabelObscuredCount", 2);

            List<String> steps = handler.buildAssessLayoutNextSteps(dto);

            assertTrue("rating=" + rating + " must still name the cause: " + steps,
                    steps.stream().anyMatch(t -> t.contains("topmost child")));
        }
    }

    @Test
    public void buildAssessLayoutNextSteps_parentLabelObscuredWithOverlaps_keepsTheLayoutTool() {
        // The suppression is scoped, not blanket. A view that ALSO has element overlaps still has
        // something the layout tool genuinely fixes, so removing it there would trade one wrong
        // remedy for one missing one.
        AssessLayoutResultDto dto = withComponent(
                withComponent(ratedDto("poor"), "parentLabelObscuredCount", 1),
                "overlapCount", 3);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        assertTrue("the layout tool must still be offered when overlaps are present: " + steps,
                steps.stream().anyMatch(t -> t.contains("Use auto-layout-and-route (ELK)")));
    }

    @Test
    public void buildAssessLayoutNextSteps_noParentLabelObscured_staysSilentAndKeepsElk() {
        // The control. Without the metric the arm must behave exactly as it did.
        List<String> steps = handler.buildAssessLayoutNextSteps(ratedDto("poor"));

        assertTrue("a zero count must emit nothing: " + steps,
                steps.stream().noneMatch(t -> t.contains("topmost child")));
        assertTrue("and the untouched fallback must still fire: " + steps,
                steps.stream().anyMatch(t -> t.contains("Use auto-layout-and-route (ELK)")));
    }

    @Test
    public void buildAssessLayoutNextSteps_parentLabelObscured_shouldNotOverclaimACappedList() {
        // Capped description list, no violator-id key for this dimension: past the cap the
        // remainder is recoverable from no field at all, even with includeViolatorIds.
        List<String> ten = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add("Parent 'G" + i + "' label overlapped by child");
        }
        AssessLayoutResultDto dto = withComponent(
                withComponent(ratedDto("poor"), "parentLabelObscuredCount", 17),
                "parentLabelObscuredDescriptions", List.copyOf(ten));

        String step = handler.buildAssessLayoutNextSteps(dto).stream()
                .filter(t -> t.contains("topmost child"))
                .findFirst().orElseThrow();

        assertTrue("the shortfall and its size must be stated: " + step,
                step.contains("The first 10 are named in parentLabelObscuredDescriptions"));
        assertTrue("...and that nothing else recovers the rest: " + step,
                step.contains("remaining 7 have to be found in the render"));
        // The clause is appended after a full stop, so the pin quotes the BOUNDARY, not just the
        // fragment. A substring assertion cannot see a sentence starting with a lowercase
        // identifier — this project has shipped exactly that defect, found by the live wire and by
        // no test.
        assertTrue("the appended clause must open a sentence, not run on from the last one: "
                + step, step.contains("not a routing one. The first 10 are named in"));
    }

    @Test
    public void buildAssessLayoutNextSteps_parentLabelObscuredWithRoutingDefects_mustNotBlameRouting() {
        // The poor arm's routing-only branch is tested BEFORE the obscured-label branch, and every
        // metric in its predicate caps the routing tier at "fair" — none can reach "poor" alone. So
        // on a view carrying an obscured parent label AND any routing defect, that branch fired
        // first and told the caller "element positions are clean; the routing defects are the
        // rating-driver", then recommended the very tool the obscured-label step says cannot clear
        // it. Two contradictory steps, in one response, about one view.
        AssessLayoutResultDto dto = withComponent(
                withComponent(ratedDto("poor"), "parentLabelObscuredCount", 1),
                "zigzagCount", 4);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        assertTrue("the Tier-1L cause must still be named: " + steps,
                steps.stream().anyMatch(t -> t.contains("topmost child")));
        assertFalse("routing must NOT be called the rating-driver here: " + steps,
                steps.stream().anyMatch(t -> t.contains("are the rating-driver")));
        assertFalse("and the tool that cannot move it must not be prescribed: " + steps,
                steps.stream().anyMatch(t -> t.contains("auto-layout-and-route (ELK)")));
        assertTrue("the routing defects are real and must still get their own remedy: " + steps,
                steps.stream().anyMatch(t -> t.contains("auto-route-connections")
                        && t.contains("not what holds this view at 'poor'")));
    }

    @Test
    public void buildAssessLayoutNextSteps_routingOnlyPoor_keepsItsCausalClaim() {
        // The control for the guard above: with no obscured label the causal claim is TRUE, so the
        // branch must be untouched. Without this, narrowing the predicate could silently delete the
        // routing-only remedy and the suite would not notice.
        AssessLayoutResultDto dto = withComponent(ratedDto("poor"), "zigzagCount", 4);

        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        assertTrue("routing-only poor must still name routing as the driver: " + steps,
                steps.stream().anyMatch(t -> t.contains("are the rating-driver")));
        assertTrue("...and still offer the layout fallback: " + steps,
                steps.stream().anyMatch(t -> t.contains("auto-layout-and-route (ELK)")));
    }

    @Test
    public void assessLayoutServedSurface_mustDocumentNoteOverlapOnTheLiveSchema() {
        // This dimension was wired, declared `checked` in coverage, and named on NO served surface
        // at all — so an agent holding a nonzero count could not learn from the tool what the field
        // meant or what to do about it. The defect class is an OMISSION, which matches no search
        // for the name, so this reads the LIVE served description rather than the source file.
        var spec = registry.getToolSpecifications().stream()
                .filter(t -> "assess-layout".equals(t.tool().name()))
                .findFirst().orElseThrow();
        String description = spec.tool().description().replaceAll("\\s+", " ");

        assertTrue("the served description must name the field", 
                description.contains("`noteOverlapCount` / `noteOverlapDescriptions`"));
        assertTrue("...must say a nested note is not a collision",
                description.contains("NESTED inside a container is not flagged"));
        assertTrue("...must state the counting unit, since the count can exceed the note count",
                description.contains("(note, object) PAIR"));
        assertTrue("...and must carry a remedy, which is the bar its note sibling sets",
                description.contains("Move the note clear with update-view-object"));
    }

    /**
     * The per-view summary key set of the whole-model sweep, in the order the response emits it.
     * Held once so the two entry-shape pins cannot drift apart, and written as names rather than a
     * count so a failure names the key that moved.
     */
    private static final List<String> COMPACT_SWEEP_KEYS = List.of(
            "name", "overallRating", "overallExcludingAcceptedCosmetics", "elementCount",
            "connectionCount", "overlapCount", "cousinOverlapCount", "ownIconOverLabelCount",
            "boundaryViolationCount", "parentLabelObscuredCount", "nonOrthogonalTerminalCount",
            "crossElementPassThroughCount", "contextualPartialDimensions");

    /** Rebuilds a DTO with one component replaced; the widest constructor takes 87 arguments. */
    private static AssessLayoutResultDto withOwnIconOverLabelCount(
            AssessLayoutResultDto base, int count) {
        return withComponent(base, "ownIconOverLabelCount", count);
    }

    /**
     * Public because the cross-package pin on the charged pass-through count needs the same
     * rebuild: every back-compat constructor defaults that component, so a fixture that does not
     * go through the widest form is green without ever reaching the value it claims to test.
     */
    public static AssessLayoutResultDto withComponent(
            AssessLayoutResultDto base, String component, Object value) {
        try {
            java.lang.reflect.RecordComponent[] components =
                    AssessLayoutResultDto.class.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            boolean matched = false;
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = components[i].getAccessor().invoke(base);
                if (component.equals(components[i].getName())) {
                    values[i] = value;
                    matched = true;
                }
            }
            // A typo in the component name would otherwise return the base unchanged and let a
            // test pass while asserting nothing.
            assertTrue("no such record component: " + component, matched);
            return AssessLayoutResultDto.class.getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }


    // ---- assess-layout: the charged pass-through count vs the description list ----

    private static final String HANDLER_SOURCE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/handlers/ViewPlacementHandler.java";

    /**
     * A DTO carrying the two pass-through quantities INDEPENDENTLY, which is the whole point:
     * every fixture on the back-compat ladder reads the charged count as zero, so a pin built on
     * one of those is green because it never reaches the value it claims to test.
     */
    private static AssessLayoutResultDto passThroughDto(
            String overallRating, List<String> descriptions, int chargedCount,
            int zigzagCount, Map<String, String> breakdown) {
        AssessLayoutResultDto base = new AssessLayoutResultDto(
                "v-pt", 6, 3, 0, 0, 0, 0.0, 120.0, 90,
                overallRating, breakdown,
                null, null, descriptions, null, 0, null, 0, null, 0, null,
                false, 0, 0, null, 0, null, 0, null, 0, null, null, List.of("s"));
        return withComponent(
                withComponent(base, "crossElementPassThroughCount", chargedCount),
                "zigzagCount", zigzagCount);
    }

    /** The three descriptions a view of self-element-only pass-throughs actually publishes. */
    private static List<String> selfElementDescriptions() {
        return List.of(
                "Connection 'self0' routes through its own target element 'selfB0'",
                "Connection 'self1' routes through its own target element 'selfB1'",
                "Connection 'self2' routes through its own target element 'selfB2'");
    }

    private static boolean anyStepNamesAPassThroughCount(List<String> steps) {
        return steps.stream().anyMatch(s -> s.contains("pass-through(s)"));
    }

    @Test
    public void assessLayoutNextSteps_fairArm_shouldNotChargeSelfElementPassThroughs() {
        // Measured on the real assessor: three overshoot-and-return connections publish three
        // DESCRIPTIONS and a passThroughs band of "pass" — the rating charges none of them. The
        // step this arm used to emit named a count of 3 and prescribed re-routing "around
        // elements", which is not what that geometry is.
        List<String> steps = handler.buildAssessLayoutNextSteps(passThroughDto(
                "fair", selfElementDescriptions(), 0, 0,
                Map.of("passThroughs", "pass", "overall", "fair")));

        assertFalse("no step may charge a pass-through count the rating rated 'pass': " + steps,
                anyStepNamesAPassThroughCount(steps));
        // hasRoutingIssues is deliberately the WIDER quantity, so the generic re-route advice must
        // survive: a self-element pass-through is still a routing defect worth routing. Nothing
        // else in this fixture can raise that predicate, so if it were narrowed to the charged
        // count this assertion is the one that goes red.
        assertTrue("the generic re-route step must still be emitted off the description list: "
                        + steps,
                steps.stream().anyMatch(s -> s.startsWith("Use auto-route-connections to re-route"
                        + " connections without moving elements")));
    }

    @Test
    public void assessLayoutNextSteps_fairArm_shouldNameTheChargedCountWhenItDoesFire() {
        // The negative pin above drives this arm with a charged count of zero, so it only ever
        // exercises the branch where no count is published. This is the other side: the arm DOES
        // fire, and the number it prints has to be the charged one. The fixture is discriminating
        // by construction — five self-element descriptions sit in the list beside the three
        // charged crossings, so the list size (8) and the charged count (3) are different numbers
        // and only one of them is the quantity the band was computed on.
        List<String> mixed = new ArrayList<>(selfElementDescriptions());
        mixed.add("Connection 'self3' routes through its own target element 'selfB3'");
        mixed.add("Connection 'self4' routes through its own target element 'selfB4'");
        mixed.add("Connection 'c0' passes through element 'mid0'");
        mixed.add("Connection 'c1' passes through element 'mid1'");
        mixed.add("Connection 'c2' passes through element 'mid2'");
        assertEquals("the fixture must keep the two quantities apart or it discriminates nothing",
                8, mixed.size());

        List<String> steps = handler.buildAssessLayoutNextSteps(passThroughDto(
                "fair", mixed, 3, 0, Map.of("passThroughs", "fair", "overall", "fair")));

        assertTrue("the fair arm must name the three crossings the rating charged, not the eight"
                        + " entries the description list happens to hold: " + steps,
                steps.stream().anyMatch(s -> s.contains("Found 3 pass-through(s)")));
        assertFalse("the fair arm must never publish the description list's size as a count: "
                        + steps,
                steps.stream().anyMatch(s -> s.contains("Found 8 pass-through(s)")));
    }

    @Test
    public void assessLayoutNextSteps_poorArm_shouldNotChargeSelfElementPassThroughs() {
        // The same view as measured: driven to "poor" by zigzags, with passThroughs at "pass".
        // The old arm named pass-throughs as the reason for a "poor" the rating charged elsewhere.
        List<String> steps = handler.buildAssessLayoutNextSteps(passThroughDto(
                "poor", selfElementDescriptions(), 0, 3,
                Map.of("passThroughs", "pass", "zigzags", "poor", "overall", "poor")));

        assertFalse("no step may attribute this view's 'poor' to pass-throughs the rating rated"
                        + " 'pass': " + steps,
                anyStepNamesAPassThroughCount(steps));
        assertTrue("the routing-defect step that names the real driver must still be emitted: "
                        + steps,
                steps.stream().anyMatch(s -> s.contains("the routing defects")
                        && s.contains("are the rating-driver")));
    }

    @Test
    public void assessLayoutNextSteps_shouldReadTheChargedCountNotTheCappedList() {
        // The under-count direction: the description list is capped at ten entries, so on a view
        // charged with more than ten crossings the list size is not the count and never can be.
        List<String> capped = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            capped.add("Connection 'c" + i + "' passes through element 'mid" + i + "'");
        }
        List<String> steps = handler.buildAssessLayoutNextSteps(passThroughDto(
                "poor", capped, 15, 0, Map.of("passThroughs", "poor", "overall", "poor")));

        assertTrue("the step must name the charged 15, not the capped list's 10: " + steps,
                steps.stream().anyMatch(s -> s.contains("Found 15 pass-through(s)")));
    }

    @Test
    public void assessLayoutNextSteps_poorArm_shouldGateOnTheBreakdownBandNotTheFairFloor() {
        // The predicate's ">= 3" is the FAIR floor: the band is 1-3 -> fair, >3 -> poor. So a
        // charged count of exactly 3 on a view driven to "poor" by something else must NOT be
        // named as the reason, and the same count with a "poor" band must.
        List<String> notCharged = handler.buildAssessLayoutNextSteps(passThroughDto(
                "poor", List.of("a", "b", "c"), 3, 4,
                Map.of("passThroughs", "fair", "zigzags", "poor", "overall", "poor")));
        assertFalse("a charged count of 3 rates 'fair'; this view's 'poor' came from zigzags, so"
                        + " naming pass-throughs as the reason is a misattribution: " + notCharged,
                anyStepNamesAPassThroughCount(notCharged));

        List<String> charged = handler.buildAssessLayoutNextSteps(passThroughDto(
                "poor", List.of("a", "b", "c"), 3, 0,
                Map.of("passThroughs", "poor", "overall", "poor")));
        assertTrue("with the band reading 'poor' the attribution is the rating's own, and the step"
                        + " must be emitted: " + charged,
                charged.stream().anyMatch(s -> s.contains("Found 3 pass-through(s)")));
    }

    @Test
    public void assessLayoutNextSteps_poorArm_shouldNotThrowOnANullRatingBreakdown() {
        // A degenerate view returns a DTO with no per-metric breakdown at all. Reading the band
        // out of it must not throw, and with nothing declaring the metric 'poor' there is no
        // attribution to make.
        List<String> steps = handler.buildAssessLayoutNextSteps(passThroughDto(
                "poor", selfElementDescriptions(), 4, 0, null));

        assertFalse("a view that declared no per-metric verdict cannot have its 'poor' attributed"
                        + " to pass-throughs: " + steps,
                anyStepNamesAPassThroughCount(steps));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_allViewsScope_shouldPublishTheChargedPassThroughCount()
            throws Exception {
        // Defect B, the opposite direction to the arms above: the sweep used to publish the
        // capped list's size, so a view charged with fifteen crossings reported ten while the
        // drill-in call reported fifteen.
        List<String> capped = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            capped.add("Connection 'c" + i + "' passes through element 'mid" + i + "'");
        }
        AssessLayoutResultDto dto = passThroughDto(
                "poor", capped, 15, 0, Map.of("passThroughs", "poor", "overall", "poor"));
        accessor.setViews(List.of(new ViewDto("v-pt", "Pass-through View", "Layered", "/")));
        accessor.setAssessLayoutBehavior(vId -> dto);

        Map<String, Object> result = getResult(callAndParse("assess-layout",
                Map.of("scope", "all-views")));
        Map<String, Object> entry = (Map<String, Object>) result.get("v-pt");

        assertEquals("the sweep must publish the count the rating charges, not the capped list's"
                        + " size", 15, entry.get("crossElementPassThroughCount"));
        // The key's position is part of the contract COMPACT_SWEEP_KEYS pins, so a rename that
        // moved it would change the entry shape as well as its name.
        assertEquals("the renamed key must keep its position in the emission order",
                COMPACT_SWEEP_KEYS.indexOf("crossElementPassThroughCount"),
                List.copyOf(entry.keySet()).indexOf("crossElementPassThroughCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_servedCompactKeyEnumerations_shouldAgreeWithTheMapTheHandlerBuilds()
            throws Exception {
        // Two served surfaces enumerate this key set in prose — the tool description and the
        // scope parameter's input-schema description — and both had forked from the map before.
        // Read from the handler SOURCE with comments stripped, because a javadoc listing the keys
        // would otherwise satisfy a raw-text scan for the very enumeration being checked.
        accessor.setViews(List.of(new ViewDto("v-1", "View 1", "Layered", "/")));
        Map<String, Object> result = getResult(callAndParse("assess-layout",
                Map.of("scope", "all-views")));
        List<String> built = List.copyOf(
                ((Map<String, Object>) result.get("v-1")).keySet());

        String code = PassThroughAndCrowdingRemedyTest.withoutComments(
                PassThroughAndCrowdingRemedyTest.readRepoFile(HANDLER_SOURCE));
        // Fuse the concatenated literals so each description reads as the single string it becomes
        // at runtime; a key split across a line break is otherwise invisible to any match.
        String fused = code.replaceAll("\"\\s*\\+\\s*\"", "");
        Matcher enumeration = Pattern.compile("\\{name, ([^}]*)\\}").matcher(fused);
        List<List<String>> enumerated = new ArrayList<>();
        while (enumeration.find()) {
            List<String> keys = new ArrayList<>();
            keys.add("name");
            for (String key : enumeration.group(1).split(",")) {
                keys.add(key.trim());
            }
            enumerated.add(keys);
        }

        assertEquals("the scan no longer finds both served compact-key enumerations in"
                + " ViewPlacementHandler.java, so it is certifying nothing", 2, enumerated.size());
        for (List<String> keys : enumerated) {
            assertEquals("a compact-key enumeration inside a served description in"
                    + " ViewPlacementHandler.java disagrees with the per-view map"
                    + " handleAssessAllViews actually builds — the two surfaces have forked",
                    built, keys);
        }
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
        assertFalse("Should NOT mention compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    // A view rated "poor" purely because of
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
        // route to auto-route-connections.
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
        // hasRoutingIssues path.
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

        // This is the ONLY guidance pointer the plugin emits from a runtime response rather than
        // from a tool description, so it is the one place an agent meets an archimate:// URI
        // without the tool description's dual-route note alongside it. The contract test that scans
        // source can prove the URI resolves; only this can prove the alternative route is still
        // named. Dropping the mention would leave a resources-less client with an unfollowable
        // instruction and every other test green.
        String spacingStep = steps.stream()
                .filter(s -> s.contains("Spacing tightness flagged"))
                .findFirst().orElseThrow();
        assertTrue("The runtime pointer must keep naming the resource route",
                spacingStep.contains("archimate://reference/archimate-view-patterns"));
        assertTrue("The runtime pointer must keep naming the tool route for clients without "
                + "MCP resource reads", spacingStep.contains("get-guidance"));
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
        assertFalse("Poor should NOT mention compute-layout",
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
        assertFalse("Poor + grouped should NOT recommend compute-layout",
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

    // --- labelPolicy: opt-in, default-off ---

    @Test
    public void autoRoute_shouldPassNullLabelPolicy_whenParameterOmitted() throws Exception {
        // THE DEFAULT-OFF REGRESSION. An omitted parameter must reach the accessor as null, which
        // resolves to "keep" — label visibility is left exactly as it was. Hiding is opt-in.
        callAndParse("auto-route-connections", Map.of("viewId", "v-1"));

        assertTrue("the accessor must have been called", accessor.autoRouteLabelPolicyCaptured);
        assertNull("an omitted labelPolicy must not become a policy",
                accessor.lastAutoRouteLabelPolicy);
    }

    @Test
    public void autoRoute_shouldForwardLabelPolicy_whenParameterSupplied() throws Exception {
        callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "labelPolicy", "auto-hide-on-collision"));

        assertEquals("auto-hide-on-collision", accessor.lastAutoRouteLabelPolicy);
    }

    @Test
    public void autoRouteConnections_specShouldDeclareLabelPolicy_defaultingToKeep() {
        McpSchema.Tool tool = registry.getToolSpecifications().stream()
                .filter(spec -> "auto-route-connections".equals(spec.tool().name()))
                .findFirst().orElseThrow().tool();

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tool.inputSchema().properties();
        assertTrue("labelPolicy must be declared on the tool spec", props.containsKey("labelPolicy"));

        @SuppressWarnings("unchecked")
        Map<String, Object> labelPolicy = (Map<String, Object>) props.get("labelPolicy");
        assertEquals("the parameter must default to keeping labels", "keep", labelPolicy.get("default"));
        assertEquals(List.of("keep", "auto-hide-on-collision"), labelPolicy.get("enum"));

        String desc = String.valueOf(labelPolicy.get("description"));
        assertTrue("the description must state that hidden labels are reported by ID",
                desc.contains("hiddenLabels"));
        assertTrue("the description must state that a hide is reversible",
                desc.contains("showLabel"));
        assertFalse("labelPolicy must not be a required parameter",
                tool.inputSchema().required().contains("labelPolicy"));
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
    public void autoRoute_noteCrossing_surfacesTheNoteIdAndTheMoveRemedy() throws Exception {
        // The route was applied through a note deliberately, so the caller needs the note's id and
        // a remedy that moves the NOTE. Deliberately NOT undo: the sibling regressed-crossings step
        // offers undo because there the route itself was the worse artefact. Here the route is
        // correct and the note is in the way, so undoing would discard a good route and leave the
        // crossing exactly where it was.
        String message = "1 applied route(s) pass through a note: connection 'reaches' (c-1) "
                + "through note 'obj-note-a' …";
        StructuredWarningDto crossing = new StructuredWarningDto(
                StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE, message,
                "update-view-object", List.of("obj-note-a"));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 0, 0, 0,
                        List.of(message), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(crossing)), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("nextSteps must name the note to move: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("obj-note-a")));
        assertTrue("nextSteps must name the tool that moves a note: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("update-view-object")));
        assertTrue("nextSteps must state the fixed-point fact — moving a note re-routes around it,"
                        + " so the result has to be re-assessed: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("assess-layout")
                        && s.contains("moving a note changes the routes")));
        assertTrue("nextSteps must NOT tell the caller to undo a route that is not the problem: "
                        + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("do not undo the route")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_noteCrossing_batched_surfacesTheNoteIdAndAQueuedRemedy() throws Exception {
        // A queued call still COMPUTES the routes — the projection is made of them — so the
        // crossings are known at prepare time and must be disclosed there too. The batched
        // nextSteps branch is a separate list built by separate code, which is exactly how a
        // deferred path in this codebase ends up silently missing a signal the immediate path has.
        //
        // It is no longer "the same remedy": the applied arm says "do not undo the route", and on a
        // queued call there is no route to undo. The evidence below is what this test was really
        // asserting all along and it survives the rescope untouched; only the name was claiming
        // more than the assertions do.
        String message = "1 applied route(s) pass through a note: connection 'reaches' (c-1) "
                + "through note 'obj-note-a' …";
        StructuredWarningDto crossing = new StructuredWarningDto(
                StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE, message,
                "update-view-object", List.of("obj-note-a"));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 0, 0, 0,
                        List.of(message), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(crossing)), 2));   // batchSequenceNumber → batched branch

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("the batched path must surface the note id too: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("obj-note-a")));
        assertTrue("and still mention the batch queue: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("batch")));
        // The deferred-mode labelling rule: nothing is effective yet, so the entity must sit under
        // 'preview' and must NOT appear at the top level of result where it reads as applied state.
        Map<String, Object> data = getResult(result);
        assertTrue("a queued auto-route must nest its projection under preview: " + data,
                data.containsKey("preview"));
        assertTrue("and must not present the routing outcome as state the model holds: " + data,
                !data.containsKey("connectionsRouted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_noteCrossing_approvalMode_carriesTheDisclosureUnderPreview()
            throws Exception {
        // The third deferred path, and the one the batch test's own comment warns about: a call
        // awaiting approval executes nothing, but it still COMPUTES the routes its projection is
        // made of, so the crossings are known and must be disclosed. Asserted on the wire rather
        // than trusted to "it is the same DTO by construction" — a future edit that handed
        // storeAsProposal a differently-built entity would not be caught by anything else.
        String message = "1 applied route(s) pass through a note: connection 'reaches' (c-1) "
                + "through note 'obj-note-a' …";
        StructuredWarningDto crossing = new StructuredWarningDto(
                StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE, message,
                "update-view-object", List.of("obj-note-a"));
        AutoRouteResultDto entity = new AutoRouteResultDto("v-1", 5, 0, "orthogonal", false, 0,
                0, 0, 0, List.of(message), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(crossing));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(entity, null, new ProposalContext("p-note",
                        "Auto-route connections on view " + vId,
                        Instant.parse("2026-03-04T00:00:00Z"))));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertNotNull("an approval-gated route must still be proposed", data.get("proposal"));
        Map<String, Object> preview = (Map<String, Object>) data.get("preview");
        assertNotNull("nothing is effective yet, so the projection sits under preview", preview);
        List<Map<String, Object>> warnings =
                (List<Map<String, Object>>) preview.get("structuredWarnings");
        assertNotNull("the disclosure must survive into the proposal preview", warnings);
        assertTrue("and must still name the note to move: " + warnings,
                warnings.stream().anyMatch(w ->
                        StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE.equals(w.get("code"))
                                && w.get("remediationViolatorIds").toString().contains("obj-note-a")));
        assertTrue("and the routing outcome must NOT read as state the model holds",
                !data.containsKey("connectionsRouted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_noNoteCrossing_addsNoNoteStep() throws Exception {
        // The negative control for the guard. Without it, the commonest call this tool receives —
        // a clean route on a view with no notes — would gain a remedy for a problem it does not
        // have, which is the failure mode a per-code guard exists to prevent.
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 0, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of()), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("no crossing means no note guidance at all: " + nextSteps,
                nextSteps.stream().noneMatch(s -> s.contains("update-view-object")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_crossingsRegressedWarning_surfacesUndoAndTerminalsOnlyNextStep()
            throws Exception {
        // A full re-route raised crossings above the geometry it replaced. The route was applied,
        // so the handler must name the recovery and what running it costs. It must NOT tell the
        // caller the previous state was better: two crossing counts do not settle that, and the
        // sentence that used to say so is gone. What this test holds is narrower and still worth
        // holding -- that the recovery is named at all, and that the alternative mode is offered.
        // RoutingPipeline.appendCrossingWarnings always mirrors the structured message into the
        // free-text list, so a DTO with a populated structuredWarnings and an EMPTY warnings list
        // cannot occur in production. Mirror it here or the mock tests a shape that never ships.
        String message = "Routing increased edge crossings from 6 to 17 …";
        StructuredWarningDto regressed = new StructuredWarningDto(
                StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED, message,
                "undo", List.of());
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 6, 17, 0,
                        List.of(message), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(regressed)), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("regression surfaces a nextSteps entry",
                nextSteps.stream().anyMatch(s -> s.contains("increased edge crossings")));
        assertTrue("nextSteps prescribes undo",
                nextSteps.stream().anyMatch(s -> s.contains("Undo")));
        assertTrue("nextSteps prescribes the terminals-only alternative",
                nextSteps.stream().anyMatch(s -> s.contains("terminals-only")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_crossingsRegressedWarning_batched_surfacesNextStep() throws Exception {
        // The batched nextSteps branch must apply the same regression guard as the non-batched one.
        String message = "Routing increased edge crossings from 2 to 8 …";
        StructuredWarningDto regressed = new StructuredWarningDto(
                StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED, message,
                "undo", List.of());
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 2, 8, 0,
                        List.of(message), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(regressed)), 2));   // batchSequenceNumber → batched branch

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("batched path also surfaces the regression step",
                nextSteps.stream().anyMatch(s -> s.contains("increased edge crossings")));
        assertTrue("batched path still mentions the batch queue",
                nextSteps.stream().anyMatch(s -> s.contains("batch")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_noCrossingsRegressedWarning_emitsNoRegressionNextStep() throws Exception {
        // A re-route that improved or held crossings carries no structured warning → no regression
        // nextSteps entry. Guards the "no over-correction" half: a good route must stay quiet.
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse("no regression nextSteps entry without the structured warning",
                nextSteps.stream().anyMatch(s -> s.contains("increased edge crossings")));
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
    @SuppressWarnings("unchecked")
    public void autoRoute_crossingsRegressedWarning_emitsNoConnectionNotFoundNextStep()
            throws Exception {
        // The decisive case. A re-route that regressed crossings emits a warning that has nothing to
        // do with connection IDs — yet the caller passed no connectionIds at all. The connection-IDs
        // nextStep must key off its own structured code, not off "the warnings list is non-empty";
        // otherwise a true regression warning ships beside a false "IDs not found" claim.
        // The message is mirrored into warnings because production always mirrors the two surfaces.
        String message = "Routing increased edge crossings from 6 to 17. The new paths were still "
                + "applied.";
        StructuredWarningDto regressed = new StructuredWarningDto(
                StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED, message, "undo", List.of());
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 6, 17, 0,
                        List.of(message), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(regressed)), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("the true regression signal still surfaces",
                nextSteps.stream().anyMatch(s -> s.contains("increased edge crossings")));
        assertFalse("a crossings-only warning must not claim connection IDs were missing",
                nextSteps.contains(ViewPlacementHandler.CONNECTION_NOT_FOUND_STEP));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_crossingsRegressedWarning_batched_emitsNoConnectionNotFoundNextStep()
            throws Exception {
        // Same as above on the batched branch — the two branches build nextSteps independently and
        // must not diverge.
        String message = "Routing increased edge crossings from 6 to 17.";
        StructuredWarningDto regressed = new StructuredWarningDto(
                StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED, message, "undo", List.of());
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 6, 17, 0,
                        List.of(message), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(regressed)), 2));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse("batched branch must not claim connection IDs were missing either",
                nextSteps.contains(ViewPlacementHandler.CONNECTION_NOT_FOUND_STEP));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_connectionNotFoundWarning_surfacesConnectionNotFoundNextStep()
            throws Exception {
        // The true signal must survive the fix: a genuine lookup miss still tells the caller so.
        // Mock shape mirrors production exactly: one free-text line PER missing ID, plus a single
        // AGGREGATE structured warning. Unlike the other codes, this one does not repeat its message
        // verbatim in warnings — the per-ID surface and the aggregate message are different strings.
        StructuredWarningDto notFound = new StructuredWarningDto(
                StructuredWarningCodes.CONNECTION_NOT_FOUND,
                "1 requested connection ID(s) were not found on the view and were skipped: bogus-id. "
                        + "The remaining connections were routed normally.",
                "get-view-contents", List.of("bogus-id"));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 0, 0, 0,
                        List.of("Connection not found on view: bogus-id"),
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(notFound)), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("a genuine miss still surfaces the connection-IDs nextStep",
                nextSteps.contains(ViewPlacementHandler.CONNECTION_NOT_FOUND_STEP));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_connectionNotFoundWarning_batched_surfacesConnectionNotFoundNextStep()
            throws Exception {
        StructuredWarningDto notFound = new StructuredWarningDto(
                StructuredWarningCodes.CONNECTION_NOT_FOUND,
                "1 requested connection ID(s) were not found on the view and were skipped: bogus-id. "
                        + "The remaining connections were routed normally.",
                "get-view-contents", List.of("bogus-id"));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, 0, "orthogonal", false, 0, 0, 0, 0,
                        List.of("Connection not found on view: bogus-id"),
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(notFound)), 2));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("batched branch surfaces a genuine miss too",
                nextSteps.contains(ViewPlacementHandler.CONNECTION_NOT_FOUND_QUEUED_STEP));
        // Re-pointing alone is not a check. The applied-arm constant says "the connections that
        // were found are routed" — on a queued call nothing is routed, and a guard moved onto a
        // different-but-real value stays green over the very change it exists to measure. So the
        // negative half is asserted beside the positive one.
        assertFalse("a queued miss must not reuse the applied arm's claim that the surviving "
                        + "connections are routed: " + nextSteps,
                nextSteps.contains(ViewPlacementHandler.CONNECTION_NOT_FOUND_STEP));
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
        // A genuine lookup miss: production emits the free-text line AND the coded warning that
        // identifies the condition, so the mock carries both. Asserting against the step constant
        // keeps this pinned to the behaviour rather than to the step's current wording.
        List<String> testWarnings = List.of("Connection not found on view: bad-id");
        List<StructuredWarningDto> testStructured = List.of(new StructuredWarningDto(
                StructuredWarningCodes.CONNECTION_NOT_FOUND,
                "1 requested connection ID(s) were not found on the view and were skipped: bad-id.",
                "get-view-contents", List.of("bad-id")));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 2, "orthogonal", false, testWarnings, testStructured), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds", List.of("c-1", "c-2", "bad-id")));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("a genuine connection-ID miss surfaces its nextStep",
                nextSteps.contains(ViewPlacementHandler.CONNECTION_NOT_FOUND_STEP));
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

    /**
     * Builds the response shape production emits when every nudge netted to zero: the reported
     * nudge list is empty, and the outcome is carried by the net-zero structured warning. The
     * free-text message is the SAME string as the structured message — every emitter mirrors it —
     * so a mock with an empty warnings list beside a populated structuredWarnings list would be a
     * shape production cannot produce, and any assertion against it would be vacuous.
     */
    private static AutoRouteResultDto netZeroOnlyResponse(String viewId,
            List<ResizedGroupDto> resizedGroups) {
        String message = "1 element(s) processed by autoNudge ended at their starting position "
                + "(net displacement 0,0) and are therefore not reported in nudgedElements: "
                + "internal API GW (vo-1).";
        return new AutoRouteResultDto(
                viewId, 8, 1, "orthogonal", false, 0, 0, 0, 0,
                List.of(message), List.of(), List.of(), List.of(),
                List.of(), resizedGroups,
                List.of(new StructuredWarningDto(StructuredWarningCodes.AUTO_NUDGE_NET_ZERO,
                        message, "apply-spacing-recommendations", List.of("vo-1"))));
    }

    @Test
    public void autoRoute_shouldOmitTheNudgeNextStep_whenTheOnlyNudgeNettedToZero() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(netZeroOnlyResponse(vId, List.of()), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "autoNudge", true));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse("nextSteps must not count a nudge the response does not report",
                nextSteps.stream().anyMatch(step -> step.contains("automatically nudged")));
        assertNull("nudgedElements key disappears rather than becoming an empty array",
                getResult(result).get("nudgedElements"));
    }

    @Test
    public void autoRoute_shouldSurfaceTheNetZeroWarning_whenTheOnlyNudgeNettedToZero() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(netZeroOnlyResponse(vId, List.of()), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "autoNudge", true));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> structured =
                (List<Map<String, Object>>) getResult(result).get("structuredWarnings");
        assertNotNull("filtering the row must not replace the contradiction with a silence",
                structured);
        assertTrue("the net-zero outcome is named by its stable code",
                structured.stream().anyMatch(w ->
                        StructuredWarningCodes.AUTO_NUDGE_NET_ZERO.equals(w.get("code"))));
    }

    @Test
    public void autoRoute_resizedGroupsNextStep_shouldNotAssertANudgeTheResponseDenies() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(netZeroOnlyResponse(vId,
                        List.of(new ResizedGroupDto("g-1", "Group A", 0, 0, 400, 300))), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "autoNudge", true));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        String resizeStep = nextSteps.stream()
                .filter(step -> step.contains("auto-resized"))
                .findFirst().orElse(null);
        assertNotNull("the resize is still reported", resizeStep);
        assertFalse("the parent-fit cascade is grow-only, so a group can outlive the nudge that "
                + "grew it — the step must not claim elements the response says were not nudged",
                resizeStep.contains("nudged"));
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

    // --- auto-layout-and-route labelPolicy: opt-in, default-off ---

    @Test
    public void autoLayoutAndRoute_shouldPassNullLabelPolicy_whenParameterOmitted() throws Exception {
        callAndParse("auto-layout-and-route", Map.of("viewId", "v-1"));

        assertTrue("the accessor must have been called", accessor.autoLayoutLabelPolicyCaptured);
        assertNull("an omitted labelPolicy must not become a policy",
                accessor.lastAutoLayoutLabelPolicy);
    }

    @Test
    public void autoLayoutAndRoute_shouldForwardLabelPolicy_whenParameterSupplied() throws Exception {
        callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "labelPolicy", "auto-hide-on-collision"));

        assertEquals("auto-hide-on-collision", accessor.lastAutoLayoutLabelPolicy);
    }

    @Test
    public void autoLayoutAndRoute_specShouldDeclareLabelPolicy_defaultingToKeep() {
        McpSchema.Tool tool = registry.getToolSpecifications().stream()
                .filter(spec -> "auto-layout-and-route".equals(spec.tool().name()))
                .findFirst().orElseThrow().tool();

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tool.inputSchema().properties();
        assertTrue("labelPolicy must be declared", props.containsKey("labelPolicy"));

        @SuppressWarnings("unchecked")
        Map<String, Object> labelPolicy = (Map<String, Object>) props.get("labelPolicy");
        assertEquals("keep", labelPolicy.get("default"));
        assertEquals(List.of("keep", "auto-hide-on-collision"), labelPolicy.get("enum"));

        // The spec must warn that one path cannot honour the policy — an agent that reads only the
        // description should not be surprised by the rejection.
        String desc = String.valueOf(labelPolicy.get("description"));
        assertTrue("the description must state the routing-pass requirement",
                desc.contains("targetRating"));
        assertTrue("the description must say hidden labels are reported by ID",
                desc.contains("hiddenLabels"));
        assertFalse("labelPolicy must not be required",
                tool.inputSchema().required().contains("labelPolicy"));
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

    // ---- auto-layout-and-route termination reason ----

    /**
     * Builds a quality-target result in the canonical shape, so these tests exercise the same
     * component list the accessor's own {@code buildQualityTargetDto} fills in. The shorter
     * back-compat overloads cannot carry a termination reason by construction.
     */
    private static AutoLayoutAndRouteResultDto qualityDto(
            String viewId, int spacing, String targetRating, String achievedRating,
            int iterations, String limitingFactor, String remediation, String terminationReason) {
        return new AutoLayoutAndRouteResultDto(
                viewId, "auto", "DOWN", spacing, 5, 3, false, 8, 0, 0, 0,
                targetRating, achievedRating, null, iterations,
                new AutoLayoutAssessmentSummaryDto(0, 5, 45.0, 70, achievedRating, List.of()),
                limitingFactor, remediation, terminationReason,
                List.of(), false, List.of(), List.of());
    }

    /** The remediation the accessor computes for a spacing-insensitive terminal geometry. */
    private static final String NONORTH_REMEDIATION =
            "Re-run auto-route-connections after element repositioning, "
            + "or use update-element-position to adjust source/target positions so terminal "
            + "segments approach element edges orthogonally";

    @Test
    public void autoLayoutAndRoute_shouldReportTerminationReasonWhenTargetMissed() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(qualityDto(vId, sp, tr, "fair", 1,
                        "nonOrthogonalTerminals", NONORTH_REMEDIATION,
                        "limiting_factor_not_remediable_nonOrthogonalTerminals"), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        Map<String, Object> data = getResult(result);
        assertNotNull("terminationReason must reach the wire on a target miss",
                data.get("terminationReason"));
        assertEquals("limiting_factor_not_remediable_nonOrthogonalTerminals",
                data.get("terminationReason"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldNotAdviseSpacingWhenTheLoopRuledThatLeverOut()
            throws Exception {
        // The loop broke on early-exit-nonorth: it MEASURED that spacing cannot move this factor.
        // A "consider increasing spacing" step here sends the agent at the one lever already
        // disproved. Asserting the positive alone would pass while the bad sentence still shipped
        // beside the good one, so this asserts the NEGATIVE.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(qualityDto(vId, sp, tr, "fair", 1,
                        "nonOrthogonalTerminals", NONORTH_REMEDIATION,
                        "limiting_factor_not_remediable_nonOrthogonalTerminals"), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse("no nextStep may point at spacing when the loop proved spacing cannot help: "
                        + nextSteps,
                nextSteps.stream().anyMatch(
                        s -> s.toLowerCase(java.util.Locale.ROOT).contains("spacing")));
        assertTrue("the step must carry the remediation the DTO already computed",
                nextSteps.stream().anyMatch(s -> s.contains("update-element-position")));
        assertTrue("the step must name the factor the loop stopped on, in readable prose",
                nextSteps.stream().anyMatch(
                        s -> s.contains("not achieved") && s.contains("nonOrthogonalTerminals")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldStillAdviseSpacingWhenTheFactorRoutesToASpacingIncrease()
            throws Exception {
        // Negative control for the test above: the suppression must be factor-aware, not a blanket
        // deletion of every spacing sentence. "overlaps" IS a factor the loop's dispatch table
        // routes to a spacing increase, so the advice is true here and must survive.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(qualityDto(vId, sp, tr, "fair", 5,
                        "overlaps",
                        "Increase spacing parameter or use layout-within-group "
                                + "to reposition overlapping elements",
                        "budget_exhausted_after_5_iterations"), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("a spacing-remediable factor must keep its spacing advice: " + nextSteps,
                nextSteps.stream().anyMatch(
                        s -> s.toLowerCase(java.util.Locale.ROOT).contains("spacing")));
        assertTrue("budget exhaustion must read as cut-short, not exhausted-lever: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("not achieved")
                        && s.toLowerCase(java.util.Locale.ROOT).contains("budget")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldReportTerminationReasonWhenTargetWasMet() throws Exception {
        // "Why did it stop" has a true answer on the happy path too, and a uniformly-present
        // field is cheaper for an agent than one whose absence must be interpreted.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(qualityDto(vId, sp, tr, "good", 2,
                        null, null, "goal_reached_at_iteration_2"), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        Map<String, Object> data = getResult(result);
        assertEquals("goal_reached_at_iteration_2", data.get("terminationReason"));
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertFalse("a met target must not produce a target-miss step",
                nextSteps.stream().anyMatch(s -> s.contains("not achieved")));
    }

    @Test
    public void autoLayoutAndRoute_shouldOmitTerminationReasonWithoutTargetRating()
            throws Exception {
        // No quality loop ran, so there is no stop to explain — and the response for a call that
        // did not ask for iteration must serialize exactly as it did before this field existed.
        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertNull("terminationReason must be absent when no quality loop ran",
                data.get("terminationReason"));
    }

    @Test
    public void autoLayoutAndRoute_withHiddenLabelsMustNotDropTheTerminationReason() {
        // withHiddenLabels rebuilds the record component by component. A sibling DTO already
        // shipped the failure this pins: "the field was computed, attached, and then thrown away."
        AutoLayoutAndRouteResultDto dto = qualityDto("v-1", 50, "good", "fair", 1,
                "nonOrthogonalTerminals", NONORTH_REMEDIATION,
                "limiting_factor_not_remediable_nonOrthogonalTerminals");

        AutoLayoutAndRouteResultDto copied = dto.withHiddenLabels(
                List.of(HiddenLabelDto.noValidPosition("conn-1")));

        assertEquals("the copier must forward terminationReason",
                "limiting_factor_not_remediable_nonOrthogonalTerminals",
                copied.terminationReason());
        assertEquals("nonOrthogonalTerminals", copied.limitingFactor());
        assertEquals(1, copied.hiddenLabels().size());
    }

    @Test
    public void autoLayoutAndRoute_withHiddenLabelsMustNotDropTheRegressionDisclosure() {
        // Same copier, same failure mode, two more components to lose. On the immediate path this
        // copy is the LAST thing that happens to the DTO, so a component it forgets is computed,
        // attached and then thrown away — the caller never sees the regression at all.
        AutoLayoutAndRouteResultDto dto = regressionDto("v-1", 50, "good", "fair", "poor",
                "budget_exhausted_after_5_iterations", true);

        AutoLayoutAndRouteResultDto copied = dto.withHiddenLabels(
                List.of(HiddenLabelDto.noValidPosition("conn-1")));

        assertEquals("the copier must forward ratingBefore", "fair", copied.ratingBefore());
        assertEquals("the copier must forward structuredWarnings",
                1, copied.structuredWarnings().size());
        assertEquals(StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED,
                copied.structuredWarnings().get(0).code());
        assertEquals("and must still forward everything it forwarded before",
                "budget_exhausted_after_5_iterations", copied.terminationReason());
        assertEquals("poor", copied.achievedRating());
        assertEquals(1, copied.hiddenLabels().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_batchedPathMustStillCarryTheTerminationReason() throws Exception {
        // The loop RUNS, assesses and undoes before queueing, so the reason is measured rather
        // than projected — unlike geometry, which is genuinely unavailable pre-dispatch.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(qualityDto(vId, sp, tr, "fair", 1,
                        "nonOrthogonalTerminals", NONORTH_REMEDIATION,
                        "limiting_factor_not_remediable_nonOrthogonalTerminals"), 2));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        Map<String, Object> data = getResult(result);
        Map<String, Object> preview = (Map<String, Object>) data.get("preview");
        assertNotNull("batched responses nest the entity under preview", preview);
        assertEquals("limiting_factor_not_remediable_nonOrthogonalTerminals",
                preview.get("terminationReason"));
    }

    @Test
    public void autoLayoutAndRoute_backCompatConstructorLeavesTerminationReasonNull() {
        // The 17-arg overload is what the pre-existing construction sites reach; it must keep
        // compiling unchanged and must not invent a reason for a loop it knows nothing about.
        AutoLayoutAndRouteResultDto dto17 = new AutoLayoutAndRouteResultDto(
                "v-1", "auto", "DOWN", 50, 5, 3, false, 8, 0, 0, 0,
                "good", "fair", 3, null, "overlaps", "Increase spacing");
        assertNull("no reason without one being supplied", dto17.terminationReason());
        assertEquals("overlaps", dto17.limitingFactor());
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
    public void autoConnect_shouldPassRelationshipIdsAllowList() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes, sl, sty) ->
                new MutationResult<>(new AutoConnectResultDto(
                        vId, 1, 0, List.of("rel-fwd"), List.of()), null));

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1", "relationshipIds",
                        List.of("rel-fwd", "rel-hub")));

        Map<String, Object> data = getResult(result);
        assertEquals(1, ((Number) data.get("connectionsCreated")).intValue());
        // The handler must forward the allow-list verbatim to the accessor.
        assertEquals("relationshipIds forwarded to accessor",
                List.of("rel-fwd", "rel-hub"),
                accessor.lastAutoConnectViewRelationshipIds);
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
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) -> {
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

    /**
     * The three propagation outcomes that leave work undone must each produce a next step naming
     * the remedy. Reporting a reason and then offering no action is half a disclosure: the caller
     * cannot see the canvas, so "a container above yours is now too small" is only actionable if
     * the response also says what to call.
     *
     * <p>Driven per code rather than once, because the three remedies differ — one says re-call on
     * an enclosing group, one says re-call on the parent that stopped it, one says re-call higher
     * up — and a single fixture would have pinned whichever branch happened to fire.</p>
     */
    @Test
    public void layoutWithinGroup_shouldNameTheRemedy_forEveryPropagationThatLeavesWorkUndone()
            throws Exception {
        Map<String, String> remedyByCode = Map.of(
                LayoutWithinGroupResultDto.PROPAGATION_CONTAINER_NOT_A_NATIVE_GROUP,
                "naming an enclosing view group",
                LayoutWithinGroupResultDto.PROPAGATION_STOPPED_AT_NON_NATIVE_ANCESTOR,
                "on that parent",
                LayoutWithinGroupResultDto.PROPAGATION_DEPTH_CAP_REACHED,
                "higher up");

        for (Map.Entry<String, String> expected : remedyByCode.entrySet()) {
            accessor.setLayoutWithinGroupBehavior(
                    (sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) ->
                            new MutationResult<>(new LayoutWithinGroupResultDto(
                                    vId, gvoId, arr, 3, true, 300, 200, false, false, null,
                                    0, 0, 0, List.of(), List.of(), expected.getKey()), null));

            Map<String, Object> result = callAndParse("layout-within-group",
                    Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"));

            @SuppressWarnings("unchecked")
            List<String> nextSteps = (List<String>) result.get("nextSteps");
            assertNotNull(nextSteps);
            assertTrue("a propagation outcome that leaves a container above too small must name "
                            + "the remedy in nextSteps, not only in the reason string. Reason was: "
                            + expected.getKey() + ", nextSteps was: " + nextSteps,
                    nextSteps.stream().anyMatch(s -> s.contains(expected.getValue())));
        }
    }

    /** ...and a terminal outcome must NOT add a remedy, or every call carries noise. */
    @Test
    public void layoutWithinGroup_shouldAddNoRemedy_whenThePropagationIsTerminal() throws Exception {
        accessor.setLayoutWithinGroupBehavior(
                (sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) ->
                        new MutationResult<>(new LayoutWithinGroupResultDto(
                                vId, gvoId, arr, 3, true, 300, 200, false, false, null,
                                0, 0, 0, List.of(), List.of(),
                                LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED), null));

        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        // The negative control for codeOf: an unguarded empty-prefix would make every startsWith
        // match, firing all three remedies on the most common call in the tool's traffic.
        assertFalse("the overwhelmingly common outcome must add no remedy. nextSteps was: "
                        + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("upward pass")));
    }

    @Test
    public void layoutWithinGroup_shouldPassOptionalParams() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) -> {
            // Verify optional params were passed through
            assertEquals(Integer.valueOf(15), sp);
            assertEquals(Integer.valueOf(5), pad);
            assertEquals(Integer.valueOf(120), ew);
            assertEquals(Integer.valueOf(55), eh);
            assertTrue(ar);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 3, true, 300, 200, false, false, null, 0, 0, 0), null);
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
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) ->
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
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) ->
                new MutationResult<>(new LayoutWithinGroupResultDto(
                        vId, gvoId, arr, 4, false, null, null, false, false, null, 0, 0, 0), 7));

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

    // ---- recursiveChildren (recursive descendant layout) tests ----

    @Test
    public void layoutWithinGroup_shouldParseRecursiveChildrenAndSurfaceCounts() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) -> {
            assertTrue("recursiveChildren should be parsed true", recCh);
            assertFalse("recursive (ancestor-resize) stays false", rec);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 6, true, 160, 406, false, false, null, 0, 2, 1), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "column");
        args.put("recursiveChildren", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("nestedContainersArranged")).intValue());
        assertEquals(1, ((Number) data.get("maxDepthReached")).intValue());

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should mention the nested containers arranged",
                nextSteps.stream().anyMatch(s -> s.contains("nested container")));
    }

    @Test
    public void layoutWithinGroup_recursiveChildrenIsDistinctFromRecursive() throws Exception {
        boolean[] seen = new boolean[2]; // [recursive, recursiveChildren]
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) -> {
            seen[0] = rec;
            seen[1] = recCh;
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 1, false, null, null, false, false, null, 0, 0, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "row");
        args.put("recursive", true); // ancestor-resize only; recursiveChildren omitted

        callAndParse("layout-within-group", args);
        assertTrue("recursive parsed true", seen[0]);
        assertFalse("recursiveChildren defaults to false", seen[1]);
    }

    // ---- autoWidth tests ----

    @Test
    public void layoutWithinGroup_shouldParseAutoWidthParam() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) -> {
            assertTrue("autoWidth should be true", aw);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, true, null, 0, 0, 0), null);
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
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) -> {
            // Handler passes both; accessor decides precedence
            assertEquals(Integer.valueOf(150), ew);
            assertTrue("autoWidth should be true from handler", aw);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, false, null, 0, 0, 0), null);
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
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) -> {
            assertTrue("autoWidth should be passed as true from handler", aw);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, aw, null, 0, 0, 0), null);
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
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) -> {
            assertEquals(Integer.valueOf(4), cols);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 12, false, null, null, false, false, 4, 0, 0, 0), null);
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
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) -> {
            assertTrue("recursive should be true", rec);
            assertTrue("autoResize should be true", ar);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, true, 300, 200, false, false, null, 2, 0, 0), null);
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
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) -> {
            assertNull("columns should be null by default", cols);
            assertFalse("recursive should be false by default", rec);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, false, null, 0, 0, 0), null);
        });

        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "grid"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
    }

    @Test
    public void layoutWithinGroup_shouldReportAncestorsResizedInResponse() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) ->
                new MutationResult<>(new LayoutWithinGroupResultDto(
                        vId, gvoId, arr, 4, true, 300, 200, false, false, null, 3, 0, 0), null));

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
                boolean autoResize, boolean autoWidth, Integer columns, boolean recursive,
                boolean recursiveChildren);
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
    interface ApplyElementSpacingRecommendationsBehavior {
        MutationResult<ApplyElementSpacingRecommendationsResultDto> apply(String sessionId,
                String viewId, boolean dryRun, Integer targetSpacingOverride,
                Integer iterationBudget);
    }

    // ---- The deferred arms: the immediate arm's text, byte for byte -----------------------------
    //
    // Every sentence the batched and awaiting-approval arms rescope is a sentence they SHARE with
    // the immediate arm today. Rescoping by locating-and-replacing a tail inside a finished string
    // is how a shared constant silently acquires a second, wrong meaning, so the immediate text is
    // committed here as a literal rather than read off the production constant: a pin that
    // re-derives the string it pins moves with it and certifies nothing.
    //
    // These are captured BEFORE the deferred arms gained their own tails. If one of them goes red,
    // a rescope reached the arm that was always telling the truth.

    private static final String IMMEDIATE_CONNECTION_NOT_FOUND_STEP =
            "One or more of the connection IDs you passed are not on this view and were skipped; "
                    + "the connections that were found are routed. See the structuredWarnings entry "
                    + "(CONNECTION_NOT_FOUND) for the exact IDs in remediationViolatorIds. Run "
                    + "get-view-contents to list the connection IDs that exist on this view.";

    private static final String IMMEDIATE_EGRESS_LIFT_LAYOUT_BOUND_STEP =
            "One or more off-face terminal hugs were left in place because clearing them would narrow "
                    + "a parallel-connection gap below the 15px healthy floor — this is layout-bound. "
                    + "Increase element spacing in the affected corridor (e.g. run "
                    + "apply-spacing-recommendations), then re-route; re-routing alone will not clear "
                    + "them. See the structuredWarnings entry (EGRESS_LIFT_LAYOUT_BOUND) for details.";

    private static final String IMMEDIATE_AUTO_ROUTE_CROSSINGS_REGRESSED_STEP =
            "This re-route increased edge crossings. The new paths were applied anyway. "
                    + "Crossings alone do not determine layout quality — a routed view can score "
                    + "worse here and still read better overall, so review the view before "
                    + "deciding. Undo reverts the whole routing pass, not just the crossings. If "
                    + "the goal was to straighten diagonal terminals on an already-tidy layout, "
                    + "re-run with mode 'terminals-only' instead: it fixes terminals without "
                    + "re-routing connection interiors and declines any rectification that would "
                    + "add crossings. See the structuredWarnings entry "
                    + "(AUTO_ROUTE_CROSSINGS_REGRESSED) for the counts.";

    private static final String IMMEDIATE_CONNECTION_THROUGH_NOTE_STEP =
            "One or more applied routes pass through a note (obj-note-a). A note is not a routing "
                    + "obstacle, so this is deliberate rather than a failure — do not undo the route. "
                    + "Move the note clear with update-view-object instead, then re-run assess-layout: "
                    + "moving a note changes the routes around it, so the crossing count after the "
                    + "move is not predictable from the one before it. Position notes after routing "
                    + "for this reason. See the structuredWarnings entry "
                    + "(CONNECTION_ROUTED_THROUGH_NOTE) for the pairs.";

    private static final String IMMEDIATE_SPACING_RATING_REGRESSED_STEP =
            "This run left the view worse than it found it — the view was better before the call. "
                    + "The spacing was applied anyway. Undo restores the previous state, and one "
                    + "undo is enough because every accepted iteration was committed as a single "
                    + "compound operation. See the structuredWarnings entry "
                    + "(SPACING_RATING_REGRESSED) for both ratings and the metric counts that "
                    + "moved, and compare the before and after snapshots yourself.";

    private static final String IMMEDIATE_OVERFLOW_STEP =
            "WARNING: Children overflow the group bounds. "
                    + "Use autoResize: true or manually resize the group.";

    private static final String IMMEDIATE_NESTED_CONTAINERS_STEP =
            "Arranged 2 nested container(s) across 2 level(s) bottom-up. "
                    + "Use undo to roll back the entire recursive layout.";

    private static final String IMMEDIATE_CONTAINER_NOT_NATIVE_STEP =
            "Nothing above this container was resized: the upward pass runs only "
                    + "from a native view group and you named an ArchiMate-element container. "
                    + "Call layout-within-group again naming an enclosing view group, or use "
                    + "resize-elements-to-fit on the parent.";

    private static final String IMMEDIATE_STOPPED_AT_NON_NATIVE_STEP =
            "The upward pass stopped at a Grouping or element parent, which is now "
                    + "too small for what this call grew inside it. Call layout-within-group or "
                    + "resize-elements-to-fit on that parent.";

    private static final String IMMEDIATE_DEPTH_CAP_STEP =
            "The upward pass stopped at its nesting limit with groups still above "
                    + "the last one it re-fitted. Call layout-within-group again higher up the "
                    + "nesting to finish propagating.";

    /**
     * The three lines an awaiting-approval response has always carried, in order.
     *
     * <p>Approval mode is human-owned — the agent observes the gate and cannot approve its own
     * change — so agent-directed guidance is genuinely wrong there and these three are correct.
     * A tool with nothing state-dependent to disclose must therefore carry exactly these and
     * nothing else, on every arm, forever.</p>
     */
    private static final List<String> FIXED_APPROVAL_LINES = List.of(
            "This change is pending the human's approval and was NOT applied",
            "Tell the user to approve or reject it in Archi (the agent cannot approve its own changes)",
            "Use list-pending-approvals to see all changes awaiting the human's decision");

    /** Stubs auto-route with one structured warning and the given dispatch arm. */
    private void stubAutoRouteWithWarning(StructuredWarningDto warning, String freeText,
            Integer batchSequenceNumber, ProposalContext proposal) {
        AutoRouteResultDto entity = new AutoRouteResultDto("v-1", 5, 0, "orthogonal", false,
                0, 0, 0, 0,
                freeText == null ? List.of() : List.of(freeText),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                warning == null ? List.of() : List.of(warning));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge,
                snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(entity, batchSequenceNumber, proposal));
    }

    private static StructuredWarningDto connectionNotFoundWarning() {
        return new StructuredWarningDto(StructuredWarningCodes.CONNECTION_NOT_FOUND,
                "1 requested connection ID(s) were not found on the view and were skipped: bogus-id.",
                "get-view-contents", List.of("bogus-id"));
    }

    private static StructuredWarningDto egressLiftWarning() {
        return new StructuredWarningDto(StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND,
                "1 off-face terminal hug(s) could not be cleared … 15px healthy floor …",
                "apply-spacing-recommendations", List.of());
    }

    private static StructuredWarningDto crossingsRegressedWarning() {
        return new StructuredWarningDto(StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED,
                "Routing increased edge crossings from 6 to 17 …", "undo", List.of());
    }

    private static StructuredWarningDto noteCrossingWarning() {
        return new StructuredWarningDto(StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE,
                "1 applied route(s) pass through a note: connection 'reaches' (c-1) through note "
                        + "'obj-note-a' …",
                "update-view-object", List.of("obj-note-a"));
    }

    private static ProposalContext proposal(String id) {
        return new ProposalContext(id, "Auto-route connections on view v-1",
                Instant.parse("2026-03-04T00:00:00Z"));
    }

    /** A layout-within-group DTO with the four fields the conditional steps are selected on. */
    private static LayoutWithinGroupResultDto layoutDto(boolean overflow,
            int nestedContainersArranged, int maxDepthReached, String ancestorPropagation) {
        return new LayoutWithinGroupResultDto("v-1", "g-1", "row", 4, false, null, null,
                overflow, false, null, 0, nestedContainersArranged, maxDepthReached,
                List.of(), List.of(), ancestorPropagation);
    }

    private void stubLayoutWithinGroup(LayoutWithinGroupResultDto dto,
            Integer batchSequenceNumber, ProposalContext proposal) {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw,
                cols, rec, recCh) -> new MutationResult<>(dto, batchSequenceNumber, proposal));
    }

    private List<String> layoutWithinGroupSteps() throws Exception {
        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"))
                .get("nextSteps");
        return steps;
    }

    private List<String> autoRouteSteps() throws Exception {
        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) callAndParse("auto-route-connections",
                Map.of("viewId", "v-1")).get("nextSteps");
        return steps;
    }

    @Test
    public void autoRoute_immediate_keepsAllFourDisclosuresVerbatim() throws Exception {
        stubAutoRouteWithWarning(connectionNotFoundWarning(), null, null, null);
        assertTrue("the immediate connection-not-found step must not drift: " + autoRouteSteps(),
                autoRouteSteps().contains(IMMEDIATE_CONNECTION_NOT_FOUND_STEP));

        stubAutoRouteWithWarning(egressLiftWarning(), null, null, null);
        assertTrue("the immediate layout-bound step must not drift: " + autoRouteSteps(),
                autoRouteSteps().contains(IMMEDIATE_EGRESS_LIFT_LAYOUT_BOUND_STEP));

        stubAutoRouteWithWarning(crossingsRegressedWarning(),
                "Routing increased edge crossings from 6 to 17 …", null, null);
        assertTrue("the immediate crossings-regressed step must not drift: " + autoRouteSteps(),
                autoRouteSteps().contains(IMMEDIATE_AUTO_ROUTE_CROSSINGS_REGRESSED_STEP));

        stubAutoRouteWithWarning(noteCrossingWarning(),
                "1 applied route(s) pass through a note …", null, null);
        assertTrue("the immediate note-crossing step must not drift: " + autoRouteSteps(),
                autoRouteSteps().contains(IMMEDIATE_CONNECTION_THROUGH_NOTE_STEP));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void spacingTools_immediate_keepTheRegressionStepVerbatim() throws Exception {
        for (String tool : THREE_SPACING_TOOLS) {
            stubAllThreeWithDisclosure(/*regressed=*/ true, /*batchSequenceNumber=*/ null);

            List<String> steps = (List<String>) callAndParse(
                    tool, Map.of("viewId", "v-1")).get("nextSteps");

            assertTrue(tool + ": the immediate regression step must not drift: " + steps,
                    steps.contains(IMMEDIATE_SPACING_RATING_REGRESSED_STEP));
        }
    }

    @Test
    public void layoutWithinGroup_immediate_keepsItsFiveConditionalStepsVerbatim()
            throws Exception {
        stubLayoutWithinGroup(layoutDto(true, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED), null, null);
        assertTrue("the immediate overflow warning must not drift: " + layoutWithinGroupSteps(),
                layoutWithinGroupSteps().contains(IMMEDIATE_OVERFLOW_STEP));

        stubLayoutWithinGroup(layoutDto(false, 2, 1,
                LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED), null, null);
        assertTrue("the immediate nested-containers note must not drift: "
                        + layoutWithinGroupSteps(),
                layoutWithinGroupSteps().contains(IMMEDIATE_NESTED_CONTAINERS_STEP));

        stubLayoutWithinGroup(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_CONTAINER_NOT_A_NATIVE_GROUP), null, null);
        assertTrue("the immediate container-not-native remedy must not drift: "
                        + layoutWithinGroupSteps(),
                layoutWithinGroupSteps().contains(IMMEDIATE_CONTAINER_NOT_NATIVE_STEP));

        stubLayoutWithinGroup(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_STOPPED_AT_NON_NATIVE_ANCESTOR), null, null);
        assertTrue("the immediate stopped-at-non-native remedy must not drift: "
                        + layoutWithinGroupSteps(),
                layoutWithinGroupSteps().contains(IMMEDIATE_STOPPED_AT_NON_NATIVE_STEP));

        stubLayoutWithinGroup(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_DEPTH_CAP_REACHED), null, null);
        assertTrue("the immediate depth-cap remedy must not drift: " + layoutWithinGroupSteps(),
                layoutWithinGroupSteps().contains(IMMEDIATE_DEPTH_CAP_STEP));
    }

    /**
     * The regression pin for the seam.
     *
     * <p>The awaiting-approval arm currently discards the handler's {@code nextSteps} entirely, and
     * that discard is load-bearing: the list it drops is composed in the present tense and claims
     * the write landed. Passing it through would put "the new paths were applied anyway; undo to
     * restore" in front of a caller whose change is sitting unapproved — for every tool at once.
     * So the disclosures the in-scope tools gain are supplied explicitly, on a separate parameter,
     * and a tool that declares none must still carry exactly the three fixed lines.</p>
     *
     * <p>The exemplar is {@code add-group-to-view} rather than {@code remove-from-view}, which it
     * was when this pin was written: that tool has since been given a deferred-arm disclosure of
     * its own, so it can no longer stand for a tool that declares none. The claim under test is a
     * property of the seam, not of any one tool, and it needs an exemplar still on the
     * five-argument overload to make it.</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    public void outOfScopeTool_approvalMode_carriesExactlyTheThreeFixedLines() throws Exception {
        accessor.setAddGroupToViewBehavior((sid, vId, label, x, y, w, h, pvoId) ->
                new MutationResult<>(new ViewGroupDto("vg-1", label, 10, 10, 300, 200, null, null),
                        null,
                        new ProposalContext("p-out-of-scope", "Add group to view v-1",
                                Instant.parse("2026-03-04T00:00:00Z"))));

        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Zone"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertEquals("a tool with no declared deferred-arm disclosure must carry the three fixed "
                        + "approval lines and nothing else, in order. nextSteps was: " + nextSteps,
                FIXED_APPROVAL_LINES, nextSteps);
    }


    // ---- one response, both halves, one arm ----------------------------------------------------
    //
    // The guard the live gate needed, and the one no per-field suite could have supplied. The prose
    // half already had full three-arm coverage; the structured half already had full per-code
    // coverage. Both were green while a single response said "Nothing has been applied: the
    // re-route is queued in the open batch, so undo is not the remedy here" in nextSteps and "The
    // new paths were still applied. To restore the previous paths, undo" in structuredWarnings,
    // with remediationTool: "undo". Two independently-valid guards are not a cross-check.
    //
    // So these assert on ONE response, and the structured half is taken from the REAL emitters
    // rather than restated here -- a copy of the model layer's output cannot disagree with it.

    /** Sentences that assert the model already holds this call's routing, on either channel. */
    private static final List<String> AUTO_ROUTE_APPLIED_STATE_CLAIMS = List.of(
            "were routed normally", "that were found are routed",
            "kept the hug(s) in place", "were left in place because",
            "ended at their starting position",
            "were still applied", "were applied anyway",
            "were applied through it", "crossings THIS CALL applied",
            // Re-pointed with the applied step. The applied arm no longer says "Undo to restore
            // the previous paths" -- it prices the recovery instead -- and a banned phrase no
            // emitter produces bans nothing, so leaving the old one here would have retired this
            // entry silently while reading like coverage.
            "Undo reverts the whole routing pass");

    private Map<String, Object> autoRouteOneResponse(AutoRouteWarningsProbe.Emitted emitted,
            Integer batchSequenceNumber, ProposalContext proposal) throws Exception {
        AutoRouteResultDto entity = new AutoRouteResultDto("v-1", 5, 0, "orthogonal", false,
                0, 0, 0, 0, emitted.freeText(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(emitted.structured()));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force, autoNudge,
                snapThreshold, perimeterMargin, mode) ->
                new MutationResult<>(entity, batchSequenceNumber, proposal));
        return callAndParse("auto-route-connections", Map.of("viewId", "v-1"));
    }

    @SuppressWarnings("unchecked")
    private void assertHalvesAgree(DispatchArm arm, String code, Map<String, Object> response) {
        List<String> steps = (List<String>) response.get("nextSteps");
        Map<String, Object> data = getResult(response);
        Map<String, Object> body = data.containsKey("preview")
                ? (Map<String, Object>) data.get("preview") : data;
        List<Map<String, Object>> structured =
                (List<Map<String, Object>>) body.get("structuredWarnings");
        assertNotNull(arm + "/" + code + ": the structured half must reach the response", structured);
        assertEquals(1, structured.size());
        String message = (String) structured.get(0).get("message");
        String tool = (String) structured.get(0).get("remediationTool");
        assertNotNull(arm + "/" + code + ": a message must reach the response", message);

        if (arm != DispatchArm.APPLIED) {
            for (String claim : AUTO_ROUTE_APPLIED_STATE_CLAIMS) {
                assertFalse(arm + "/" + code + ": structuredWarnings asserts applied state \""
                        + claim + "\" on an arm where nothing was written:\n" + message,
                        message.contains(claim));
                assertFalse(arm + "/" + code + ": nextSteps asserts applied state \"" + claim
                        + "\" on an arm where nothing was written:\n" + steps,
                        steps.stream().anyMatch(s -> s.contains(claim)));
            }
            assertTrue(arm + "/" + code + ": the structured half must say nothing has been applied:"
                    + "\n" + message, message.contains("Nothing has been applied"));
            assertTrue(arm + "/" + code + ": and so must the prose half:\n" + steps,
                    steps.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT)
                            .contains("nothing has been applied")));
        }

        // Neither half may name a recovery the other denies. This is the exact pair the live gate
        // caught: a nextSteps entry saying undo is not the remedy, beside remediationTool "undo".
        boolean proseDeniesUndo = steps.stream()
                .anyMatch(s -> s.contains("undo is not the remedy here"));
        assertFalse(arm + "/" + code + ": nextSteps denies undo while the structured field "
                + "prescribes it -- one response, two answers: " + steps,
                proseDeniesUndo && "undo".equals(tool));
        boolean prosePrescribesUndo = steps.stream()
                .anyMatch(s -> s.contains("Undo reverts the whole routing pass"));
        assertFalse(arm + "/" + code + ": nextSteps prescribes undo while the structured field "
                + "declines to name it: " + steps,
                prosePrescribesUndo && !"undo".equals(tool));
    }

    @Test
    public void autoRoute_oneResponse_bothHalvesDescribeTheSameArm_applied() throws Exception {
        for (Map.Entry<String, AutoRouteWarningsProbe.Emitted> e
                : emittersFor(DispatchArm.APPLIED).entrySet()) {
            assertHalvesAgree(DispatchArm.APPLIED, e.getKey(),
                    autoRouteOneResponse(e.getValue(), null, null));
        }
    }

    @Test
    public void autoRoute_oneResponse_bothHalvesDescribeTheSameArm_queued() throws Exception {
        for (Map.Entry<String, AutoRouteWarningsProbe.Emitted> e
                : emittersFor(DispatchArm.QUEUED).entrySet()) {
            assertHalvesAgree(DispatchArm.QUEUED, e.getKey(),
                    autoRouteOneResponse(e.getValue(), 2, null));
        }
    }

    @Test
    public void autoRoute_oneResponse_bothHalvesDescribeTheSameArm_awaitingApproval()
            throws Exception {
        for (Map.Entry<String, AutoRouteWarningsProbe.Emitted> e
                : emittersFor(DispatchArm.AWAITING_APPROVAL).entrySet()) {
            assertHalvesAgree(DispatchArm.AWAITING_APPROVAL, e.getKey(),
                    autoRouteOneResponse(e.getValue(), null, proposal("p-" + e.getKey())));
        }
    }

    /** The four codes that have a prose sibling, emitted for {@code arm} by the real emitters. */
    private static Map<String, AutoRouteWarningsProbe.Emitted> emittersFor(DispatchArm arm) {
        Map<String, AutoRouteWarningsProbe.Emitted> emitted = new LinkedHashMap<>();
        emitted.put(StructuredWarningCodes.CONNECTION_NOT_FOUND,
                AutoRouteWarningsProbe.connectionsNotFound(arm));
        emitted.put(StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND,
                AutoRouteWarningsProbe.egressLiftLayoutBound(arm));
        emitted.put(StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED,
                AutoRouteWarningsProbe.crossingsRegressed(arm));
        emitted.put(StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE,
                AutoRouteWarningsProbe.connectionThroughNote(arm));
        return emitted;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_regressionWarning_emptyRemediationToolMustNotReachTheWire()
            throws Exception {
        // The auto-route twin of the auto-layout pin. A green pin on a sibling tool is not coverage
        // of yours: this family reaches the wire through a different DTO and, on this arm, through
        // the proposal preview rather than the top level. Asserting the Java accessor holds "" says
        // nothing about the JSON -- a served "remediationTool": "" reads to an agent as a
        // deliberate empty value, not as "no tool recovers this".
        AutoRouteWarningsProbe.Emitted emitted =
                AutoRouteWarningsProbe.crossingsRegressed(DispatchArm.AWAITING_APPROVAL);
        assertEquals("precondition: the emitter names no tool on this arm",
                "", emitted.structured().remediationTool());

        Map<String, Object> response = autoRouteOneResponse(emitted, null, proposal("p-cross"));

        Map<String, Object> preview =
                (Map<String, Object>) getResult(response).get("preview");
        assertNotNull("nothing is effective yet, so the projection sits under preview", preview);
        List<Map<String, Object>> warnings =
                (List<Map<String, Object>>) preview.get("structuredWarnings");
        assertEquals(1, warnings.size());
        assertFalse("an empty remediationTool must be OMITTED from the JSON, not served as an "
                        + "empty string: " + warnings.get(0),
                warnings.get(0).containsKey("remediationTool"));
    }

    // ---- auto-route-connections on the two deferred arms ---------------------------------------
    //
    // Four codes, two deferred arms, one test each rather than one fixture carrying all four. A
    // single fixture with every warning set cannot show that a code with no deferred variant fell
    // through to the applied constant: the other three would carry the assertions and the response
    // would look correct.

    private static long occurrences(List<String> steps, String fragment) {
        return steps.stream().filter(s -> s.contains(fragment)).count();
    }

    @Test
    public void autoRoute_connectionNotFound_queued_doesNotClaimTheSurvivorsAreRouted()
            throws Exception {
        stubAutoRouteWithWarning(connectionNotFoundWarning(), null, 2, null);

        List<String> steps = autoRouteSteps();

        assertFalse("a queued call routes nothing yet: " + steps,
                steps.contains(IMMEDIATE_CONNECTION_NOT_FOUND_STEP));
        assertFalse("nor may any step say the survivors are already routed: " + steps,
                steps.stream().anyMatch(s -> s.contains("that were found are routed")));
        assertTrue("it must say outright that nothing has been applied: " + steps,
                steps.stream().anyMatch(s -> s.contains("nothing has been applied")));
        assertTrue("the evidence pointer stays — the miss was genuinely measured: " + steps,
                steps.stream().anyMatch(s -> s.contains("CONNECTION_NOT_FOUND")
                        && s.contains("remediationViolatorIds")));
        assertTrue("and the remedy a queued caller can actually run: " + steps,
                steps.stream().anyMatch(s -> s.contains("end-batch rollback:true")));
    }

    @Test
    public void autoRoute_connectionNotFound_awaitingApproval_disclosesWithoutNamingARecovery()
            throws Exception {
        stubAutoRouteWithWarning(connectionNotFoundWarning(), null, null, proposal("p-cnf"));

        List<String> steps = autoRouteSteps();

        assertTrue("the three fixed approval lines come first: " + steps,
                steps.subList(0, 3).equals(FIXED_APPROVAL_LINES));
        assertTrue("and the disclosure is no longer discarded: " + steps,
                steps.contains(ViewPlacementHandler.CONNECTION_NOT_FOUND_PROPOSED_STEP));
        assertFalse("it must not reuse the applied arm's routed claim: " + steps,
                steps.contains(IMMEDIATE_CONNECTION_NOT_FOUND_STEP));
        assertTrue("the evidence survives: " + steps,
                steps.stream().anyMatch(s -> s.contains("remediationViolatorIds")));
        assertNoRecoveryToolIsPrescribed(steps);
    }

    @Test
    public void autoRoute_layoutBound_queued_doesNotClaimTheHugsWereLeftInPlace() throws Exception {
        stubAutoRouteWithWarning(egressLiftWarning(), null, 2, null);

        List<String> steps = autoRouteSteps();

        assertFalse("a queued call has left nothing anywhere yet: " + steps,
                steps.contains(IMMEDIATE_EGRESS_LIFT_LAYOUT_BOUND_STEP));
        assertFalse("nor may a step assert the hugs were already left in place: " + steps,
                steps.stream().anyMatch(s -> s.contains("hugs were left in place")));
        assertTrue("it must say outright that nothing has been applied: " + steps,
                steps.stream().anyMatch(s -> s.contains("Nothing has been applied")));
        assertTrue("the corridor and the floor stay — both were measured: " + steps,
                steps.stream().anyMatch(s -> s.contains("15px healthy floor")
                        && s.contains("layout-bound")));
        assertTrue("and the queued remedy is named: " + steps,
                steps.stream().anyMatch(s -> s.contains("end-batch")));
    }

    @Test
    public void autoRoute_layoutBound_awaitingApproval_disclosesWithoutNamingARecovery()
            throws Exception {
        stubAutoRouteWithWarning(egressLiftWarning(), null, null, proposal("p-egress"));

        List<String> steps = autoRouteSteps();

        assertTrue("the three fixed approval lines come first: " + steps,
                steps.subList(0, 3).equals(FIXED_APPROVAL_LINES));
        assertTrue("and the disclosure is no longer discarded: " + steps,
                steps.contains(ViewPlacementHandler.EGRESS_LIFT_LAYOUT_BOUND_PROPOSED_STEP));
        assertFalse("it must not reuse the applied arm's past-tense decline: " + steps,
                steps.contains(IMMEDIATE_EGRESS_LIFT_LAYOUT_BOUND_STEP));
        assertTrue("the 15px floor survives: " + steps,
                steps.stream().anyMatch(s -> s.contains("15px healthy floor")));
        assertNoRecoveryToolIsPrescribed(steps);
    }

    /**
     * The two deferred steps exactly as the tool published them before the applied arm was
     * rewritten — the positive control for that rewrite.
     *
     * <p>Held as literals rather than compared against the constants, which would only prove the
     * constants equal themselves. Both are composed as {@code DEFERRED_HEAD + middle + TAIL}, and
     * the same {@code TAIL} is concatenated into the applied step, so a reword of either shared
     * part fails here — including a reword reached by editing a constant nobody opened this
     * arm to change.</p>
     *
     * <p><strong>Green here is not an endorsement of what these strings say.</strong> Both still
     * carry "applying it would leave the view worse than it is now" — the whole-view verdict two
     * crossing counts cannot support, which is exactly what the immediate arm was just rewritten
     * to stop claiming. This test pins that wording as UNCHANGED, not as correct. It is the
     * control for a deliberately one-armed edit, and it should be re-pointed, not deleted, when
     * the deferred arms are brought into line.</p>
     */
    private static final String QUEUED_CROSSINGS_STEP_AS_PUBLISHED =
            "This re-route increased edge crossings against the geometry it would replace — "
                    + "applying it would leave the view worse than it is now. Nothing has been "
                    + "applied: the re-route is queued in the open batch, so undo is not the "
                    + "remedy here and would revert whichever command is actually on top of the "
                    + "stack. Discard the queued re-route with end-batch rollback:true, or "
                    + "commit it with end-batch and re-run assess-layout to see what landed. If "
                    + "the goal was to straighten diagonal terminals on an already-tidy layout, "
                    + "re-run with mode 'terminals-only' instead: it fixes terminals without "
                    + "re-routing connection interiors and declines any rectification that would "
                    + "add crossings. See the structuredWarnings entry "
                    + "(AUTO_ROUTE_CROSSINGS_REGRESSED) for the counts.";

    private static final String PROPOSED_CROSSINGS_STEP_AS_PUBLISHED =
            "This re-route increased edge crossings against the geometry it would replace — "
                    + "applying it would leave the view worse than it is now. Nothing has been "
                    + "applied: the re-route is waiting on the human's decision, so undo is not "
                    + "the remedy here and would revert whichever command is actually on top of "
                    + "the stack. Rejecting the change in Archi leaves the previous paths "
                    + "exactly as they are. If the goal was to straighten diagonal terminals on "
                    + "an already-tidy layout, re-run with mode 'terminals-only' instead: it "
                    + "fixes terminals without re-routing connection interiors and declines any "
                    + "rectification that would add crossings. See the structuredWarnings entry "
                    + "(AUTO_ROUTE_CROSSINGS_REGRESSED) for the counts.";

    @Test
    public void autoRoute_crossingsRegressed_bothDeferredStepsAreByteIdentical_afterTheAppliedRewrite() {
        assertEquals("the queued step must not move when the applied arm does",
                QUEUED_CROSSINGS_STEP_AS_PUBLISHED,
                ViewPlacementHandler.AUTO_ROUTE_CROSSINGS_REGRESSED_QUEUED_STEP);
        assertEquals("nor the awaiting-approval one",
                PROPOSED_CROSSINGS_STEP_AS_PUBLISHED,
                ViewPlacementHandler.AUTO_ROUTE_CROSSINGS_REGRESSED_PROPOSED_STEP);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoRoute_crossingsRegressed_applied_statesTheMeasurementWithoutTheVerdict()
            throws Exception {
        // The applied arm's nextSteps head used to open "the view was better before the call" --
        // an unconditional whole-view claim, in the field an agent reads first, drawn from two
        // crossing counts. Edge crossings are the most tolerable routing defect this project
        // measures, so a full re-route that raises them while clearing an interior termination or
        // a route through an element has IMPROVED the view by its own weighting, and an agent
        // obeying that opening clause discards the better geometry.
        //
        // Driven through the handler on ONE response whose structured half comes from the REAL
        // emitter, not from a canned stub: the defect is a property of the response, and a stub
        // would let nextSteps be fixed while the message beside it drifted. Both halves are
        // asserted below, so this test cannot pass on half a fix.
        Map<String, Object> response = autoRouteOneResponse(
                AutoRouteWarningsProbe.crossingsRegressed(DispatchArm.APPLIED), null, null);

        List<String> steps = (List<String>) response.get("nextSteps");
        assertNotNull(steps);
        List<Map<String, Object>> structured =
                (List<Map<String, Object>>) getResult(response).get("structuredWarnings");
        assertEquals(1, structured.size());
        String message = (String) structured.get(0).get("message");

        assertFalse("two counts do not license a whole-view verdict: " + steps,
                steps.stream().anyMatch(s -> s.contains("the view was better before the call")));
        assertFalse("nor does the structured half get to state one:\n" + message,
                message.contains("is worse than the geometry it replaced"));
        assertTrue("the measurement is the part the counts DO license: " + steps,
                steps.stream().anyMatch(s -> s.contains("increased edge crossings")));

        // The two clauses, on BOTH channels of the same response. The failure this guards is not a
        // missing sentence but a DISAGREEMENT: one half qualifying the count while the other still
        // calls the view worse is what an agent reads as two answers to one question.
        assertTrue("(i) nextSteps: the count is not by itself a quality judgment: " + steps,
                steps.stream().anyMatch(
                        s -> s.contains("Crossings alone do not determine layout quality")));
        assertTrue("(i) and the structured half says it too:\n" + message,
                message.contains("Crossings alone do not determine layout quality"));
        assertTrue("(ii) nextSteps: the recovery is priced -- undo costs the whole pass: " + steps,
                steps.stream().anyMatch(
                        s -> s.contains("Undo reverts the whole routing pass, not just the "
                                + "crossings")));
        assertTrue("(ii) and so does the structured half:\n" + message,
                message.contains("undo reverts the whole routing pass, not just the crossings"));

        assertTrue("and the applied-state claim stays: on this arm the model WAS written: " + steps,
                steps.stream().anyMatch(s -> s.contains("The new paths were applied anyway.")));
        assertTrue("on the structured half too:\n" + message,
                message.contains("The new paths were still applied."));
    }

    @Test
    public void autoRoute_crossingsRegressed_queued_doesNotPrescribeUndo() throws Exception {
        stubAutoRouteWithWarning(crossingsRegressedWarning(),
                "Routing increased edge crossings from 6 to 17 …", 2, null);

        List<String> steps = autoRouteSteps();

        assertFalse("the worst of the four: a queued run must not claim the paths landed: " + steps,
                steps.contains(IMMEDIATE_AUTO_ROUTE_CROSSINGS_REGRESSED_STEP));
        assertFalse("nor say they were applied anyway: " + steps,
                steps.stream().anyMatch(s -> s.contains("paths were applied anyway")));
        assertFalse("nor prescribe undo, which would revert somebody else's command: " + steps,
                steps.stream().anyMatch(s -> s.contains("Undo reverts the whole routing pass")));
        assertTrue("it must say outright that nothing has been applied: " + steps,
                steps.stream().anyMatch(s -> s.contains("Nothing has been applied")));
        assertTrue("the counts the router genuinely measured stay: " + steps,
                steps.stream().anyMatch(s -> s.contains("increased edge crossings")
                        && s.contains("AUTO_ROUTE_CROSSINGS_REGRESSED")));
        assertTrue("and the discard a queued caller can run: " + steps,
                steps.stream().anyMatch(s -> s.contains("end-batch rollback:true")));
    }

    @Test
    public void autoRoute_crossingsRegressed_awaitingApproval_disclosesWithoutNamingARecovery()
            throws Exception {
        stubAutoRouteWithWarning(crossingsRegressedWarning(),
                "Routing increased edge crossings from 6 to 17 …", null, proposal("p-cross"));

        List<String> steps = autoRouteSteps();

        assertTrue("the three fixed approval lines come first: " + steps,
                steps.subList(0, 3).equals(FIXED_APPROVAL_LINES));
        assertTrue("and the disclosure is no longer discarded: " + steps,
                steps.contains(ViewPlacementHandler.AUTO_ROUTE_CROSSINGS_REGRESSED_PROPOSED_STEP));
        assertFalse("it must not claim the paths were applied: " + steps,
                steps.stream().anyMatch(s -> s.contains("were applied anyway")));
        assertTrue("the measurement survives: " + steps,
                steps.stream().anyMatch(s -> s.contains("increased edge crossings")));
        assertTrue("and terminals-only is still the second attempt worth making: " + steps,
                steps.stream().anyMatch(s -> s.contains("terminals-only")));
        assertNoRecoveryToolIsPrescribed(steps);
    }

    @Test
    public void autoRoute_noteCrossing_queued_doesNotCallTheRoutesApplied() throws Exception {
        stubAutoRouteWithWarning(noteCrossingWarning(),
                "1 applied route(s) pass through a note …", 2, null);

        List<String> steps = autoRouteSteps();

        assertFalse("a queued call has applied no route to pass through anything: " + steps,
                steps.contains(IMMEDIATE_CONNECTION_THROUGH_NOTE_STEP));
        assertFalse("nor may a step call them applied routes: " + steps,
                steps.stream().anyMatch(s -> s.contains("One or more applied routes")));
        assertTrue("the note id stays — it is the argument the remedy takes: " + steps,
                steps.stream().anyMatch(s -> s.contains("obj-note-a")));
        assertTrue("the remedy still moves the note, not the route: " + steps,
                steps.stream().anyMatch(s -> s.contains("update-view-object")));
        assertTrue("and it is scoped to the batch the caller is actually in: " + steps,
                steps.stream().anyMatch(s -> s.contains("end-batch")));
    }

    @Test
    public void autoRoute_noteCrossing_awaitingApproval_disclosesWithoutNamingARecovery()
            throws Exception {
        stubAutoRouteWithWarning(noteCrossingWarning(),
                "1 applied route(s) pass through a note …", null, proposal("p-note-steps"));

        List<String> steps = autoRouteSteps();

        assertTrue("the three fixed approval lines come first: " + steps,
                steps.subList(0, 3).equals(FIXED_APPROVAL_LINES));
        assertFalse("the prose remedy was being discarded entirely; it must not come back as the "
                        + "applied arm's sentence: " + steps,
                steps.contains(IMMEDIATE_CONNECTION_THROUGH_NOTE_STEP));
        assertTrue("the note id must reach the caller in prose, not only under preview: " + steps,
                steps.stream().anyMatch(s -> s.contains("obj-note-a")));
        assertTrue("and the tool that moves a note: " + steps,
                steps.stream().anyMatch(s -> s.contains("update-view-object")));
        assertNoRecoveryToolIsPrescribed(steps);
    }

    /**
     * No arm may recover an unapproved change, so no arm may name a tool that claims to.
     *
     * <p>{@code update-view-object} and {@code apply-spacing-recommendations} are deliberately not
     * banned: they are layout remedies that are true whatever happens to this proposal, not offers
     * to undo it. What is banned is a recovery prescription for <em>this call</em>, because the
     * agent cannot run one — it cannot approve or reject its own change.</p>
     */
    private static void assertNoRecoveryToolIsPrescribed(List<String> steps) {
        List<String> disclosures = steps.subList(3, steps.size());
        // Three spellings because three applied-arm constants prescribe undo in three ways, and a
        // guard keyed on a phrase its producer no longer writes is green over the hole rather than
        // over the property. "Undo reverts" is auto-route's, added when its applied step stopped
        // saying "Undo to restore the previous paths".
        assertFalse("an awaiting-approval arm must not prescribe undo: " + disclosures,
                disclosures.stream().anyMatch(s -> s.contains("Undo to restore")
                        || s.contains("Undo restores")
                        || s.contains("Undo reverts")));
        assertFalse("nor end-batch, which commits a batch this call is not in: " + disclosures,
                disclosures.stream().anyMatch(s -> s.contains("end-batch")));
    }

    @Test
    public void autoRoute_clean_gainsNothingOnAnyArm() throws Exception {
        // The commonest call this tool receives. A negative control per arm, because a guard that
        // fires on a clean route is a remedy for a problem the caller does not have.
        stubAutoRouteWithWarning(null, null, null, null);
        assertTrue("a clean immediate route discloses nothing: " + autoRouteSteps(),
                autoRouteSteps().stream().noneMatch(s -> s.contains("structuredWarnings entry")));

        stubAutoRouteWithWarning(null, null, 2, null);
        assertTrue("nor a clean queued one: " + autoRouteSteps(),
                autoRouteSteps().stream().noneMatch(s -> s.contains("structuredWarnings entry")));

        stubAutoRouteWithWarning(null, null, null, proposal("p-clean"));
        assertEquals("and a clean proposal keeps exactly the three fixed lines: " + autoRouteSteps(),
                FIXED_APPROVAL_LINES, autoRouteSteps());
    }

    @Test
    public void autoRoute_everyArm_emitsEachDisclosureAtMostOnce() throws Exception {
        // Asserted by occurrence count, not by presence: a composer called on both the shared path
        // and a leftover inline branch would satisfy every contains() above.
        for (Integer batch : new Integer[] {null, 2}) {
            stubAutoRouteWithWarning(noteCrossingWarning(),
                    "1 applied route(s) pass through a note …", batch, null);
            assertEquals("the note disclosure must appear once on arm batch=" + batch + ": "
                            + autoRouteSteps(),
                    1, occurrences(autoRouteSteps(), "CONNECTION_ROUTED_THROUGH_NOTE"));

            stubAutoRouteWithWarning(crossingsRegressedWarning(),
                    "Routing increased edge crossings from 6 to 17 …", batch, null);
            assertEquals("the crossings disclosure must appear once on arm batch=" + batch + ": "
                            + autoRouteSteps(),
                    1, occurrences(autoRouteSteps(), "AUTO_ROUTE_CROSSINGS_REGRESSED"));
        }

        stubAutoRouteWithWarning(noteCrossingWarning(),
                "1 applied route(s) pass through a note …", null, proposal("p-once"));
        assertEquals("and once on the awaiting-approval arm: " + autoRouteSteps(),
                1, occurrences(autoRouteSteps(), "CONNECTION_ROUTED_THROUGH_NOTE"));
    }

    // ---- auto-layout-and-route on the awaiting-approval arm -------------------------------------
    //
    // The batched arm was rescoped when the quality loop's undo remedy was arm-scoped; the
    // awaiting-approval arm was not, because at that point every tool's nextSteps were being
    // discarded there and there was nothing to rescope.

    private void stubAutoLayoutRegressed(Integer batchSequenceNumber, ProposalContext proposal) {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true),
                        batchSequenceNumber, proposal));
    }

    private List<String> autoLayoutSteps() throws Exception {
        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"))
                .get("nextSteps");
        return steps;
    }

    @Test
    public void autoLayoutAndRoute_regressionWarning_awaitingApproval_disclosesTheRegression()
            throws Exception {
        stubAutoLayoutRegressed(null, new ProposalContext("p-auto-layout",
                "Auto-layout and route view v-1", Instant.parse("2026-03-04T00:00:00Z")));

        List<String> steps = autoLayoutSteps();

        assertTrue("the three fixed approval lines come first: " + steps,
                steps.subList(0, 3).equals(FIXED_APPROVAL_LINES));
        assertTrue("a regression measured before the gate must not be discarded with the "
                        + "response's guidance: " + steps,
                steps.stream().anyMatch(
                        s -> s.contains("produced a view worse than the one it was handed")));
        assertFalse("but it must not open by saying the view IS worse, on an arm where the run is "
                        + "still sitting on a card: " + steps,
                steps.stream().anyMatch(s -> s.contains("the view was better before the call")));
        assertTrue("it must say outright that nothing has been applied: " + steps,
                steps.stream().anyMatch(s -> s.contains("Nothing has been applied")));
        assertFalse("and must not reuse the applied arm's claim: " + steps,
                steps.contains(APPLIED_NEXT_STEP));
        assertFalse("nor say the layout was applied at all: " + steps,
                steps.stream().anyMatch(s -> s.contains("were applied anyway")));
    }

    @Test
    public void autoLayoutAndRoute_regressionWarning_awaitingApproval_keepsBothRatingsAndTheMetrics()
            throws Exception {
        // Rescoping the remedy must not become withdrawing the measurement.
        stubAutoLayoutRegressed(null, new ProposalContext("p-auto-layout-evidence",
                "Auto-layout and route view v-1", Instant.parse("2026-03-04T00:00:00Z")));

        List<String> steps = autoLayoutSteps();

        assertTrue("the warning carrying the counts is still named: " + steps,
                steps.stream().anyMatch(s -> s.contains("AUTO_LAYOUT_RATING_REGRESSED")));
        assertTrue("and both ratings: " + steps,
                steps.stream().anyMatch(s -> s.contains("ratingBefore beside achievedRating")));
    }

    @Test
    public void autoLayoutAndRoute_regressionWarning_awaitingApproval_prescribesNoRecoveryTool()
            throws Exception {
        stubAutoLayoutRegressed(null, new ProposalContext("p-auto-layout-remedy",
                "Auto-layout and route view v-1", Instant.parse("2026-03-04T00:00:00Z")));

        List<String> steps = autoLayoutSteps();

        assertNoRecoveryToolIsPrescribed(steps);
        assertTrue("the human's decision is what resolves it: " + steps,
                steps.stream().anyMatch(s -> s.contains("Rejecting the change in Archi")));
    }

    @Test
    public void autoLayoutAndRoute_regressionWarning_batchedArmIsUnchangedByTheApprovalArm()
            throws Exception {
        // The batched constant was scoped by an earlier change and is not this one's to move.
        stubAutoLayoutRegressed(2, null);

        List<String> steps = autoLayoutSteps();

        assertTrue("the queued arm keeps its own remedy: " + steps,
                steps.stream().anyMatch(s -> s.contains("end-batch rollback:true")));
        assertFalse("and must not have acquired the approval arm's: " + steps,
                steps.stream().anyMatch(s -> s.contains("Rejecting the change in Archi")));
    }

    @Test
    public void autoLayoutAndRoute_cleanRun_awaitingApproval_keepsExactlyTheThreeFixedLines()
            throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "good",
                        "target_met_at_iteration_2", false), null,
                        new ProposalContext("p-auto-layout-clean",
                                "Auto-layout and route view v-1",
                                Instant.parse("2026-03-04T00:00:00Z"))));

        assertEquals("a run that improved the view discloses nothing extra: " + autoLayoutSteps(),
                FIXED_APPROVAL_LINES, autoLayoutSteps());
    }

    // ---- layout-within-group on the two deferred arms -------------------------------------------
    //
    // The batched arm returned before every one of the five conditional steps, so a queued call
    // learned nothing about an overflow, a recursive arrangement or an ancestor left too small.
    // The awaiting-approval arm had the same five discarded one layer up.

    private List<String> layoutStepsFor(LayoutWithinGroupResultDto dto,
            Integer batchSequenceNumber, ProposalContext proposal) throws Exception {
        stubLayoutWithinGroup(dto, batchSequenceNumber, proposal);
        return layoutWithinGroupSteps();
    }

    @Test
    public void layoutWithinGroup_queued_carriesAllFiveConditionalStepsInFutureTense()
            throws Exception {
        List<String> overflow = layoutStepsFor(layoutDto(true, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED), 7, null);
        assertFalse("a queued call has overflowed nothing yet: " + overflow,
                overflow.contains(IMMEDIATE_OVERFLOW_STEP));
        assertTrue("but the caller must still learn it will: " + overflow,
                overflow.stream().anyMatch(s -> s.contains("will overflow the group bounds")));

        List<String> nested = layoutStepsFor(layoutDto(false, 2, 1,
                LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED), 7, null);
        assertFalse("nothing has been arranged yet: " + nested,
                nested.contains(IMMEDIATE_NESTED_CONTAINERS_STEP));
        assertTrue("the counts are still owed — the recursion really did run: " + nested,
                nested.stream().anyMatch(s -> s.contains("2 nested container(s) across 2 level(s)")
                        && s.contains("will be arranged")));

        List<String> notNative = layoutStepsFor(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_CONTAINER_NOT_A_NATIVE_GROUP), 7, null);
        assertFalse("no ancestor has been left unresized yet: " + notNative,
                notNative.contains(IMMEDIATE_CONTAINER_NOT_NATIVE_STEP));
        assertTrue("but the remedy is the same call and must still be named: " + notNative,
                notNative.stream().anyMatch(s -> s.contains("will be resized")
                        && s.contains("resize-elements-to-fit")));

        List<String> stopped = layoutStepsFor(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_STOPPED_AT_NON_NATIVE_ANCESTOR), 7, null);
        assertFalse("the parent is not too small yet: " + stopped,
                stopped.contains(IMMEDIATE_STOPPED_AT_NON_NATIVE_STEP));
        assertTrue("it will be, once the batch commits: " + stopped,
                stopped.stream().anyMatch(s -> s.contains("will be")
                        && s.contains("too small") && s.contains("batch commits")));

        List<String> depthCap = layoutStepsFor(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_DEPTH_CAP_REACHED), 7, null);
        assertFalse("the depth-cap remedy must not prescribe a call as if the walk had landed: "
                        + depthCap, depthCap.contains(IMMEDIATE_DEPTH_CAP_STEP));
        assertTrue("the walk genuinely stopped at its limit and that fact is owed: " + depthCap,
                depthCap.stream().anyMatch(s -> s.contains("stopped at its nesting limit")));
    }

    @Test
    public void layoutWithinGroup_queued_keepsTheQueueLinesBesideTheDisclosures() throws Exception {
        List<String> steps = layoutStepsFor(layoutDto(true, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED), 7, null);

        assertTrue("the queue position must survive the rescope: " + steps,
                steps.contains("Mutation queued as operation #7 in current batch"));
        assertTrue("and how to inspect it: " + steps,
                steps.contains("Use get-batch-status to check batch progress"));
        assertTrue("and how to commit it: " + steps,
                steps.contains("Use end-batch to commit all queued mutations"));
    }

    @Test
    public void layoutWithinGroup_awaitingApproval_carriesAllFiveInApprovalTense() throws Exception {
        ProposalContext ctx = new ProposalContext("p-lwg", "Layout within group g-1",
                Instant.parse("2026-03-04T00:00:00Z"));

        List<String> overflow = layoutStepsFor(layoutDto(true, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED), null, ctx);
        assertTrue("the three fixed approval lines come first: " + overflow,
                overflow.subList(0, 3).equals(FIXED_APPROVAL_LINES));
        assertTrue("and the overflow is no longer discarded: " + overflow,
                overflow.stream().anyMatch(s -> s.contains("will overflow the group bounds")
                        && s.contains("if this change is approved")));
        assertNoRecoveryToolIsPrescribed(overflow);

        List<String> nested = layoutStepsFor(layoutDto(false, 2, 1,
                LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED), null, ctx);
        assertTrue("nor the recursion counts: " + nested,
                nested.stream().anyMatch(s -> s.contains("2 nested container(s) across 2 level(s)")));
        assertNoRecoveryToolIsPrescribed(nested);

        List<String> notNative = layoutStepsFor(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_CONTAINER_NOT_A_NATIVE_GROUP), null, ctx);
        assertTrue("nor the container-not-native remedy: " + notNative,
                notNative.stream().anyMatch(s -> s.contains("Nothing above this container will be "
                        + "resized if this change is approved")));
        assertNoRecoveryToolIsPrescribed(notNative);

        List<String> stopped = layoutStepsFor(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_STOPPED_AT_NON_NATIVE_ANCESTOR), null, ctx);
        assertTrue("nor the ancestor the walk could not grow: " + stopped,
                stopped.stream().anyMatch(s -> s.contains("too small")
                        && s.contains("if this change is approved")));
        assertNoRecoveryToolIsPrescribed(stopped);

        List<String> depthCap = layoutStepsFor(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_DEPTH_CAP_REACHED), null, ctx);
        assertTrue("nor the nesting limit: " + depthCap,
                depthCap.stream().anyMatch(s -> s.contains("stopped at its nesting limit")));
        assertNoRecoveryToolIsPrescribed(depthCap);
    }

    @Test
    public void layoutWithinGroup_everyArm_selectsAPropagationRemedyByItsPublishedCode()
            throws Exception {
        // Every arm selects through codeOf, which refuses a colonless reason. If an arm ever
        // matched on a bare literal instead, an empty prefix would make every startsWith() true
        // and fire all three remedies at once on a call that needs none. Two halves: a reason
        // outside the three fires nothing, and a malformed one fires nothing either.
        for (Object[] arm : new Object[][] {{null, null}, {7, null},
                {null, new ProposalContext("p-code", "Layout within group g-1",
                        Instant.parse("2026-03-04T00:00:00Z"))}}) {
            Integer batch = (Integer) arm[0];
            ProposalContext ctx = (ProposalContext) arm[1];

            List<String> terminal = layoutStepsFor(layoutDto(false, 0, 0,
                    LayoutWithinGroupResultDto.PROPAGATED), batch, ctx);
            assertEquals("a walk that finished fires no propagation remedy on arm batch=" + batch
                            + " proposal=" + (ctx != null) + ": " + terminal,
                    0, occurrences(terminal, "upward pass"));
            assertEquals("nor the container-not-native one: " + terminal,
                    0, occurrences(terminal, "Nothing above this container"));

            List<String> malformed = layoutStepsFor(
                    layoutDto(false, 0, 0, "a reason with no colon"), batch, ctx);
            assertEquals("a malformed reason must fire no remedy, not all three, on arm batch="
                            + batch + " proposal=" + (ctx != null) + ": " + malformed,
                    0, occurrences(malformed, "upward pass"));
            assertEquals("nor the container-not-native one: " + malformed,
                    0, occurrences(malformed, "Nothing above this container"));
        }
    }

    @Test
    public void layoutWithinGroup_everyArm_emitsEachDisclosureAtMostOnce() throws Exception {
        for (Object[] arm : new Object[][] {{null, null}, {7, null},
                {null, new ProposalContext("p-once-lwg", "Layout within group g-1",
                        Instant.parse("2026-03-04T00:00:00Z"))}}) {
            List<String> steps = layoutStepsFor(layoutDto(true, 2, 1,
                    LayoutWithinGroupResultDto.PROPAGATION_DEPTH_CAP_REACHED),
                    (Integer) arm[0], (ProposalContext) arm[1]);

            assertEquals("one overflow warning on arm batch=" + arm[0] + ": " + steps,
                    1, occurrences(steps, "overflow the group bounds"));
            assertEquals("one recursion note: " + steps,
                    1, occurrences(steps, "nested container(s)"));
            assertEquals("one propagation remedy: " + steps,
                    1, occurrences(steps, "upward pass"));
        }
    }

    @Test
    public void layoutWithinGroup_cleanCall_gainsNothingOnAnyArm() throws Exception {
        List<String> queued = layoutStepsFor(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED), 7, null);
        assertEquals("a clean queued layout carries the queue lines and nothing else: " + queued,
                3, queued.size());

        List<String> proposed = layoutStepsFor(layoutDto(false, 0, 0,
                LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED), null,
                new ProposalContext("p-lwg-clean", "Layout within group g-1",
                        Instant.parse("2026-03-04T00:00:00Z")));
        assertEquals("and a clean proposal keeps exactly the three fixed lines: " + proposed,
                FIXED_APPROVAL_LINES, proposed);
    }

    @FunctionalInterface
    interface ApplyGroupSpacingRecommendationsBehavior {
        MutationResult<ApplyGroupSpacingRecommendationsResultDto> apply(String sessionId,
                String viewId, boolean dryRun, Integer targetSpacingOverride,
                Integer iterationBudget);
    }

    @FunctionalInterface
    interface ApplySpacingRecommendationsBehavior {
        MutationResult<ApplySpacingRecommendationsResultDto> apply(String sessionId, String viewId,
                String scope, boolean dryRun, Integer elementTargetSpacingOverride,
                Integer groupTargetSpacingOverride, Integer iterationBudget);
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
        private ApplyElementSpacingRecommendationsBehavior applyElementSpacingBehavior;
        private ApplyGroupSpacingRecommendationsBehavior applyGroupSpacingBehavior;
        private ApplySpacingRecommendationsBehavior applySpacingBehavior;

        // Capture last styling params passed to each method (for assertion in tests)
        StylingParams lastUpdateViewObjectStyling;
        StylingParams lastAddToViewStyling;
        StylingParams lastAddGroupToViewStyling;
        StylingParams lastAddNoteToViewStyling;
        StylingParams lastUpdateViewConnectionStyling;
        StylingParams lastAutoConnectViewStyling;
        String lastAutoRouteLabelPolicy;
        String lastAutoLayoutLabelPolicy;
        boolean autoLayoutLabelPolicyCaptured;
        boolean autoRouteLabelPolicyCaptured;
        // capture last relationshipIds allow-list passed to auto-connect-view.
        List<String> lastAutoConnectViewRelationshipIds;
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

        void setApplyElementSpacingBehavior(ApplyElementSpacingRecommendationsBehavior behavior) {
            this.applyElementSpacingBehavior = behavior;
        }

        void setApplyGroupSpacingBehavior(ApplyGroupSpacingRecommendationsBehavior behavior) {
            this.applyGroupSpacingBehavior = behavior;
        }

        void setApplySpacingBehavior(ApplySpacingRecommendationsBehavior behavior) {
            this.applySpacingBehavior = behavior;
        }

        @Override
        public MutationResult<ApplyElementSpacingRecommendationsResultDto>
                applyElementSpacingRecommendations(String sessionId, String viewId, boolean dryRun,
                        Integer targetSpacingOverride, Integer iterationBudget) {
            return applyElementSpacingBehavior.apply(sessionId, viewId, dryRun,
                    targetSpacingOverride, iterationBudget);
        }

        @Override
        public MutationResult<ApplyGroupSpacingRecommendationsResultDto>
                applyGroupSpacingRecommendations(String sessionId, String viewId, boolean dryRun,
                        Integer targetSpacingOverride, Integer iterationBudget) {
            return applyGroupSpacingBehavior.apply(sessionId, viewId, dryRun,
                    targetSpacingOverride, iterationBudget);
        }

        @Override
        public MutationResult<ApplySpacingRecommendationsResultDto> applySpacingRecommendations(
                String sessionId, String viewId, String scope, boolean dryRun,
                Integer elementTargetSpacingOverride, Integer groupTargetSpacingOverride,
                Integer iterationBudget) {
            return applySpacingBehavior.apply(sessionId, viewId, scope, dryRun,
                    elementTargetSpacingOverride, groupTargetSpacingOverride, iterationBudget);
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
            this.layoutWithinGroupBehavior = (sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec, recCh) ->
                    new MutationResult<>(new LayoutWithinGroupResultDto(
                            vId, gvoId, arr, 4, ar, ar ? 300 : null, ar ? 200 : null, false, aw, null, 0, 0, 0), null);
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
                    List.of("Element 'API Gateway' has 8 connections (large hub: > 6). "
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
                String direction, int spacing, String targetRating, String labelPolicy) {
            lastAutoLayoutLabelPolicy = labelPolicy;
            autoLayoutLabelPolicyCaptured = true;
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
                boolean enableChannelNudging, String labelPolicy) {
            lastAutoRouteLabelPolicy = labelPolicy;
            autoRouteLabelPolicyCaptured = true;
            return autoRouteConnectionsBehavior.apply(sessionId, viewId, connectionIds, strategy, force, autoNudge, snapThreshold, perimeterMargin, mode);
        }

        @Override
        public MutationResult<AutoConnectResultDto> autoConnectView(
                String sessionId, String viewId,
                List<String> elementIds, List<String> relationshipTypes,
                List<String> relationshipIds,
                Boolean showLabel, StylingParams styling) {
            lastAutoConnectViewStyling = styling;
            lastAutoConnectViewRelationshipIds = relationshipIds;
            return autoConnectViewBehavior.apply(sessionId, viewId, elementIds,
                    relationshipTypes, showLabel, styling);
        }

        @Override
        public MutationResult<LayoutWithinGroupResultDto> layoutWithinGroup(
                String sessionId, String viewId, String groupViewObjectId,
                String arrangement, Integer spacing, Integer padding,
                Integer elementWidth, Integer elementHeight, boolean autoResize,
                boolean autoWidth, Integer columns, boolean recursive,
                boolean recursiveChildren) {
            return layoutWithinGroupBehavior.apply(sessionId, viewId, groupViewObjectId,
                    arrangement, spacing, padding, elementWidth, elementHeight, autoResize,
                    autoWidth, columns, recursive, recursiveChildren);
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

    // ------------------------------------------------------------------
    // auto-layout-and-route — the rating-regression disclosure
    // ------------------------------------------------------------------

    /** The disclosure the accessor attaches when the committed state is worse than the input. */
    private static final String RATING_REGRESSED_MESSAGE =
            "auto-layout-and-route left this view worse than it found it: overall rating dropped "
            + "from 'fair' to 'poor'. Worsened: element overlaps 0 -> 4, edge crossings 26 -> 40. "
            + "The layout and routes were applied anyway — undo restores the previous state in a "
            + "single call.";

    /** A quality-loop DTO carrying the pre-call rating and, optionally, the regression warning. */
    private static AutoLayoutAndRouteResultDto regressionDto(
            String viewId, int spacing, String targetRating, String ratingBefore,
            String achievedRating, String terminationReason, boolean regressed) {
        List<StructuredWarningDto> warnings = regressed
                ? List.of(new StructuredWarningDto(
                        StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED,
                        RATING_REGRESSED_MESSAGE, "undo", List.of()))
                : List.of();
        return new AutoLayoutAndRouteResultDto(
                viewId, "grouped", "DOWN", spacing, 5, 3, false, 8, 2, 0, 0,
                targetRating, achievedRating, ratingBefore, 5,
                new AutoLayoutAssessmentSummaryDto(4, 40, 45.0, 70, achievedRating, List.of()),
                null, null, terminationReason,
                List.of(), false, List.of(), warnings);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldReportThePreCallRatingBesideTheAchievedRating()
            throws Exception {
        // achievedRating is a statement about the OUTCOME. Without the input beside it a caller
        // cannot tell "failed to improve a poor view" from "took a fair view and made it poor".
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        Map<String, Object> data = getResult(result);
        assertEquals("the rating the view had before the call must reach the wire",
                "fair", data.get("ratingBefore"));
        assertEquals("poor", data.get("achievedRating"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldReportThePreCallRatingWhenTheResultImproved()
            throws Exception {
        // Present whenever a quality loop ran — including when it improved — so its absence never
        // has to be interpreted.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "poor", "good",
                        "goal_reached_at_iteration_2", false), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        assertEquals("poor", getResult(result).get("ratingBefore"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldSurfaceTheStructuredRegressionWarningOnTheWire()
            throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<Map<String, Object>> warnings =
                (List<Map<String, Object>>) getResult(result).get("structuredWarnings");
        assertNotNull("structuredWarnings must reach the wire", warnings);
        assertEquals(1, warnings.size());
        assertEquals("AUTO_LAYOUT_RATING_REGRESSED", warnings.get(0).get("code"));
        assertEquals("undo", warnings.get(0).get("remediationTool"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_regressionWarning_immediate_surfacesNextStep() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("nextSteps must say the view was better before the call: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("better before")));
        assertTrue("nextSteps must say the state was applied anyway: " + nextSteps,
                nextSteps.stream().anyMatch(
                        s -> s.toLowerCase(java.util.Locale.ROOT).contains("applied")));
        assertTrue("nextSteps must prescribe undo: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("undo")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_regressionWarning_batched_surfacesNextStep() throws Exception {
        // A batched call still ran the whole loop and still measured the regression. The batch
        // branch returns early from the steps builder, so parity here is required, not optional.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true), 2));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("batched path also surfaces the regression step: " + nextSteps,
                nextSteps.stream().anyMatch(
                        s -> s.contains("produced a view worse than the one it was handed")));
        assertFalse("without claiming the view IS worse, which a queued run has not made it: "
                        + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("the view was better before the call")));
        assertTrue("batched path still mentions the batch queue: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("batch")));
    }

    /**
     * The immediate arm's nextSteps disclosure, byte for byte.
     *
     * <p>Committed rather than read off the production constant: a pin that re-derives the string
     * it pins moves with it and certifies nothing. The immediate arm is the one verified
     * end-to-end against the live server — two loop iterations, one stack entry, one undo, the
     * view fully restored — and every rescope on the deferred arms changes a string it shares.</p>
     */
    private static final String APPLIED_NEXT_STEP =
            "This run left the view worse than it found it — the view was better before the call. "
                    + "The new layout and routes were applied anyway. Undo restores the previous "
                    + "state, and one undo is enough because the whole run was committed as a "
                    + "single compound operation. See the structuredWarnings entry "
                    + "(AUTO_LAYOUT_RATING_REGRESSED) for the ratings and the metric counts that "
                    + "moved, and read ratingBefore beside achievedRating.";

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_regressionWarning_immediate_keepsTheAppliedStepVerbatim()
            throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("the immediate arm's step must not drift: " + nextSteps,
                nextSteps.contains(APPLIED_NEXT_STEP));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_regressionWarning_batched_mustNotClaimTheRunWasApplied()
            throws Exception {
        // One response used to carry both "the new layout and routes were applied anyway. Undo
        // restores the previous state" and "mutation queued as operation #2 in current batch".
        // Nothing had been applied: an agent obeying the first reverts whichever command is really
        // on top of the stack, or on a clean stack reverts nothing and then re-reads a view that
        // is still degraded.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true), 2));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertFalse("a queued run must not reuse the applied-arm step: " + nextSteps,
                nextSteps.contains(APPLIED_NEXT_STEP));
        assertFalse("nor say the layout was applied at all: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("were applied anyway")));
        assertFalse("nor prescribe undo as the recovery for this call: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("Undo restores the previous state")));
        assertTrue("it must say outright that nothing was applied: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("Nothing has been applied")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_regressionWarning_batched_namesARemedyThatWorks()
            throws Exception {
        // An arm that says only "not undo" leaves the caller with nothing to do.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true), 2));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("the discard a queued caller can actually run: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("end-batch rollback:true")));
        assertTrue("and the way to find out what landed if they commit instead: " + nextSteps,
                nextSteps.stream().anyMatch(
                        s -> s.contains("end-batch and re-run assess-layout")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_regressionWarning_batched_stillPointsAtTheEvidence()
            throws Exception {
        // Rescoping the remedy must not become suppressing the measurement. Unlike the spacing
        // family, whose 'after' snapshot re-reads an unmutated view when queued, this loop measures
        // its best attempt inside its own temporary dispatch window before the queue decision — so
        // the regression here is genuinely measured and is owed on this arm.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true), 2));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("the queued arm still reports the measured regression: " + nextSteps,
                nextSteps.stream().anyMatch(
                        s -> s.contains("produced a view worse than the one it was handed")));
        assertFalse("stated as what applying it WOULD do, not as what the view already is: "
                        + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("the view was better before the call")));
        assertTrue("and still points at the warning carrying the counts: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("AUTO_LAYOUT_RATING_REGRESSED")));
        assertTrue("and at the two ratings: " + nextSteps,
                nextSteps.stream().anyMatch(
                        s -> s.contains("ratingBefore beside achievedRating")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_regressionWarning_batched_stillReportsTheQueue()
            throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true), 2));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("the batch steps must survive beside the rescoped disclosure: " + nextSteps,
                nextSteps.stream().anyMatch(
                        s -> s.contains("Mutation queued as operation #2 in current batch")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_regressionWarning_emptyRemediationToolMustNotReachTheWire()
            throws Exception {
        // The awaiting-approval arm names no tool, because none of them recovers a proposal. That
        // is expressed as an EMPTY remediationTool relying on @JsonInclude(NON_EMPTY) to drop the
        // key — and this story is the first caller ever to pass "" for it, so the annotation had
        // never actually been exercised. Asserting the Java accessor is "" proves nothing about
        // the JSON: a served "remediationTool": "" reads to an agent as a deliberate empty value,
        // not as "no remedy". Pin the wire.
        List<StructuredWarningDto> noRemedy = List.of(new StructuredWarningDto(
                StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED,
                RATING_REGRESSED_MESSAGE, "", List.of()));
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "grouped", "DOWN", sp, 5, 3, false, 8, 2, 0, 0,
                        tr, "poor", "fair", 5, null, null, null, "plateau_at_iteration_2",
                        List.of(), false, List.of(), noRemedy, List.of()), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<Map<String, Object>> warnings =
                (List<Map<String, Object>>) getResult(result).get("structuredWarnings");
        assertEquals(1, warnings.size());
        assertFalse("an empty remediationTool must be OMITTED from the JSON, not served as an "
                        + "empty string: " + warnings.get(0),
                warnings.get(0).containsKey("remediationTool"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_regressionWarning_approval_prescribesNoUndoInNextSteps()
            throws Exception {
        // Approval mode replaces nextSteps with fixed boilerplate for every tool, so the applied
        // step never reaches this arm's nextSteps channel. Pinned because that is WHY only the card
        // and the preview needed rescoping here — if the override were ever dropped, the arm-blind
        // applied step would surface on a change that has not happened.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "fair", "poor",
                        "budget_exhausted_after_5_iterations", true), null,
                        new ProposalContext("p-alr", "auto-layout-and-route", Instant.now())));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertFalse("an unapproved change must not be described as applied: " + nextSteps,
                nextSteps.contains(APPLIED_NEXT_STEP));
        assertTrue("the approval boilerplate says what actually happened: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("was NOT applied")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_noRegressionWarning_emitsNoRegressionNextStep()
            throws Exception {
        // Negative control on the same code path: a run that improved must stay quiet on both
        // channels, or the disclosure is noise the caller learns to ignore.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "poor", "good",
                        "goal_reached_at_iteration_2", false), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse("a run that improved must emit no regression step: " + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("better before")));
        assertNull("and no structuredWarnings entry",
                getResult(result).get("structuredWarnings"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_regressionWarning_firesEvenWhenTheTargetWasMet()
            throws Exception {
        // meetsTarget is >=, and the shipped guidance tells agents to use targetRating 'good' for
        // most views. On an already-excellent view, iteration 1 producing 'good' satisfies the
        // target, breaks the loop and commits excellent -> good: a regression reported today as
        // unambiguous success. The disclosure must not be gated on the target being missed.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(regressionDto(vId, sp, tr, "excellent", "good",
                        "goal_reached_at_iteration_1", true), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "mode", "grouped", "targetRating", "good"));

        Map<String, Object> data = getResult(result);
        assertEquals("excellent", data.get("ratingBefore"));
        assertNotNull("the warning must survive the goal-reached path",
                data.get("structuredWarnings"));
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("and so must the nextStep, which does not run through the target-miss builder: "
                        + nextSteps,
                nextSteps.stream().anyMatch(s -> s.contains("better before")));
    }

    @Test
    public void autoLayoutAndRoute_shouldOmitThePreCallRatingWhenNoQualityLoopRan()
            throws Exception {
        // A single-pass call never assesses, so there is no rating to report. Omitted, not null,
        // so the response serializes exactly as it did before the field existed.
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, m, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", 50, 5, 3, false, 8), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1"));

        assertNull("no loop ran, so no pre-call rating exists to report",
                getResult(result).get("ratingBefore"));
    }

    // ---- The hub-port-quality remedy band -------------------------------------------------
    //
    // The rating caps a view's routing tier for the whole of the fair band as well as the poor
    // one, but the hub next-step used to fire only below the poor edge, so every view sitting in
    // [0.5, 0.75) was capped and told nothing. These pins hold the step to the band, and the
    // three cases at and above 0.75 are the guard against an over-wide comparison: two views on
    // the reference corpus score exactly 0.75, which the rating calls good.

    private static String hubStep(List<String> steps) {
        return steps.stream()
                .filter(s -> s.contains("Hub-port quality"))
                .findFirst().orElse(null);
    }

    private static long hubStepCount(List<String> steps) {
        return steps.stream().filter(s -> s.contains("Hub-port quality")).count();
    }

    @Test
    public void buildAssessLayoutNextSteps_shouldEmitTheHubStep_acrossTheWholeCappingBand() {
        // 0.49 is poor and already fired; 0.50, 0.60 and 0.7499 are the fair band the remedy
        // could not reach. All four take a routing tier off the view, so all four must speak.
        for (double hpq : new double[]{0.49, 0.50, 0.60, 0.7499}) {
            List<String> steps =
                    handler.buildAssessLayoutNextSteps(nestedHubDto(0.5, 0, 0, hpq, "fair", false));
            assertNotNull("hubPortQualityScore " + hpq + " caps the view's routing tier, so the "
                    + "hub next-step must name the remedy: " + steps, hubStep(steps));
        }
    }

    @Test
    public void buildAssessLayoutNextSteps_shouldNotEmitTheHubStep_whenTheBandIsGoodOrBetter() {
        // 0.75 exactly is good — the value two corpus views actually carry. A <= comparison
        // against 0.75 would newly advise a hub resize on a view the rating is content with.
        for (double hpq : new double[]{0.75, 0.80, 1.0}) {
            List<String> steps =
                    handler.buildAssessLayoutNextSteps(nestedHubDto(0.5, 0, 0, hpq, "good", false));
            assertNull("hubPortQualityScore " + hpq + " is good or better and takes no tier, so "
                    + "the hub next-step must stay silent: " + steps, hubStep(steps));
        }
    }

    @Test
    public void buildAssessLayoutNextSteps_shouldEmitExactlyOneHubStep_whenASaturatedViewIsInBand() {
        // The saturated-nested-hub diagnostic exists so it does not duplicate the hub step, and
        // its gate is the complement of the hub step's condition. Widen one without the other and
        // a saturated view in the fair band receives two hub remedies at once.
        List<String> steps =
                handler.buildAssessLayoutNextSteps(nestedHubDto(0.95, 6, 0, 0.60, "fair", false));

        assertEquals("a saturated view inside the fair band must receive exactly one hub remedy, "
                + "not the direct step and the diagnostic together: " + steps,
                1, hubStepCount(steps) + (saturatedStep(steps) == null ? 0 : 1));
        assertNotNull("and the one it receives is the direct step, which names the violators",
                hubStep(steps));
    }

    @Test
    public void buildAssessLayoutNextSteps_shouldNameAViolatorHub_whenTheBandIsFair() {
        // A remedy that says "violator hubs:" over an empty set is not a remedy. The face detail
        // list is what the step reads, so a fair-band view must still resolve to a named hub.
        List<String> steps = handler.buildAssessLayoutNextSteps(
                hubFacesDto(0.60, List.of(
                        new AssessLayoutResultDto.HubFaceDetailDto("hub-1", "BOTTOM", 5, 3, 0.6))));

        String step = hubStep(steps);
        assertNotNull("the fair band must emit: " + steps, step);
        assertTrue("the emitted step must name the hub it is talking about: " + step,
                step.contains("hub-1"));
    }

    @Test
    public void hubPortQualityRemedyThreshold_shouldTrackTheAssessorsGoodBandEdge() throws Exception {
        // The handler cannot import the assessor across the layer boundary, so it mirrors the
        // boundary as its own constant — and a mirror nobody checks is a copy waiting to drift.
        // Reflection is the only way a handler-package test can read the model-package constant,
        // and reading it is the whole point: this fails the moment either side is edited alone.
        Class<?> assessor = Class.forName("net.vheerden.archi.mcp.model.LayoutQualityAssessor");
        java.lang.reflect.Field good = assessor.getDeclaredField("HUB_PORT_QUALITY_GOOD_THRESHOLD");
        good.setAccessible(true);

        assertEquals("the hub remedy must fire exactly where the rating stops calling the metric "
                + "good; a drift here silently re-opens or widens the unexplained band",
                good.getDouble(null),
                ViewPlacementHandler.HUB_PORT_QUALITY_REMEDY_THRESHOLD, 0.0);
    }

    // ---- The fan-out sizing precondition ---------------------------------------------------

    private static String unsizedHubStep(List<String> steps) {
        return steps.stream()
                .filter(s -> s.contains("too small for their connection fan-out"))
                .findFirst().orElse(null);
    }

    @Test
    public void buildAssessLayoutNextSteps_shouldPrescribeSizingBeforeRouting_forUnsizedHubs() {
        List<String> steps = handler.buildAssessLayoutNextSteps(withUnsizedHubs(
                nestedHubDto(0.5, 0, 0, 1.0, "good", false),
                List.of(new AssessLayoutResultDto.HubPreconditionDto(
                        "kafka", "obj-kafka", "Kafka", 13, 240, 100, 360, 298))));

        String step = unsizedHubStep(steps);
        assertNotNull("an unmet fan-out precondition must reach nextSteps: " + steps, step);
        assertTrue("the step must name the hub it means", step.contains("Kafka")
                && step.contains("obj-kafka"));
        assertTrue("and carry the numbers, so the caller does not recompute a target from prose",
                step.contains("13 connections") && step.contains("240x100")
                        && step.contains("360x298"));

        // The order is the whole point of the precondition: measure, resize, THEN route. A step
        // that named the three tools in any other order would prescribe the mistake it exists to
        // prevent, and would still contain all three names.
        assertTrue("detect-hub-elements must come first",
                step.indexOf("detect-hub-elements") < step.indexOf("update-view-object"));
        assertTrue("and auto-route-connections must come last",
                step.indexOf("update-view-object") < step.indexOf("auto-route-connections"));
    }

    @Test
    public void buildAssessLayoutNextSteps_shouldPrecedeTheRatingSwitchAdvice() {
        // A precondition acted on after the rating-switch advice is a precondition acted on too
        // late — the caller has already re-routed by then.
        List<String> steps = handler.buildAssessLayoutNextSteps(withUnsizedHubs(
                nestedHubDto(0.5, 6, 0, 1.0, "fair", false),
                List.of(new AssessLayoutResultDto.HubPreconditionDto(
                        "kafka", "obj-kafka", "Kafka", 13, 240, 100, 360, 298))));

        int precondition = -1;
        int routeAdvice = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (precondition == -1 && steps.get(i).contains("too small for their connection fan-out")) {
                precondition = i;
            }
            if (routeAdvice == -1 && steps.get(i).startsWith("Use auto-route-connections")) {
                routeAdvice = i;
            }
        }
        assertTrue("the precondition must be surfaced before the rating-switch advice "
                        + "(precondition=" + precondition + ", advice=" + routeAdvice + ")",
                precondition >= 0 && routeAdvice >= 0 && precondition < routeAdvice);
    }

    @Test
    public void buildAssessLayoutNextSteps_shouldSayNothing_whenNoHubIsUnsized() {
        // Absent and empty must both read as "measured, nothing unmet" — never as an empty
        // sentence the caller has to interpret.
        assertNull("null means nothing was unmet", unsizedHubStep(
                handler.buildAssessLayoutNextSteps(nestedHubDto(0.5, 0, 0, 1.0, "good", false))));
        assertNull("and so does an empty list", unsizedHubStep(handler.buildAssessLayoutNextSteps(
                withUnsizedHubs(nestedHubDto(0.5, 0, 0, 1.0, "good", false), List.of()))));
    }

    @Test
    public void buildAssessLayoutNextSteps_shouldNameEveryUnsizedHub_notJustTheWorst() {
        List<String> steps = handler.buildAssessLayoutNextSteps(withUnsizedHubs(
                nestedHubDto(0.5, 0, 0, 1.0, "good", false),
                List.of(new AssessLayoutResultDto.HubPreconditionDto(
                                "kafka", "obj-kafka", "Kafka", 13, 240, 100, 360, 298),
                        new AssessLayoutResultDto.HubPreconditionDto(
                                "gw", "obj-gw", "API Gateway", 9, 120, 55, 320, 266))));

        String step = unsizedHubStep(steps);
        assertNotNull(step);
        assertTrue("both hubs must be named, not a count standing in for them",
                step.contains("Kafka") && step.contains("API Gateway"));
        assertTrue("and the count must agree with the rows", step.startsWith("2 element(s)"));
    }

    /**
     * The same DTO with a different {@code unsizedHubs}, rebuilt through the canonical
     * constructor by record component rather than by re-typing eighty arguments — so this helper
     * cannot fall behind the next field appended to the record.
     */
    private static AssessLayoutResultDto withUnsizedHubs(AssessLayoutResultDto base,
            List<AssessLayoutResultDto.HubPreconditionDto> hubs) {
        try {
            java.lang.reflect.RecordComponent[] components =
                    AssessLayoutResultDto.class.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = components[i].getAccessor().invoke(base);
                if ("unsizedHubs".equals(components[i].getName())) {
                    values[i] = hubs;
                }
            }
            return AssessLayoutResultDto.class.getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A next-steps DTO carrying per-face hub detail, so the step's violator clause resolves. */
    private static AssessLayoutResultDto hubFacesDto(
            double hpq, List<AssessLayoutResultDto.HubFaceDetailDto> faces) {
        return new AssessLayoutResultDto(
                "v-1", 8, 8, 0, 0, 0, 0.0, 50.0, 80, "fair", null,
                null, null, List.<String>of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.<String>of(),
                0, null, 0, null, 0, null, hpq, faces,
                "fair", "fair",
                0.5, null);
    }
    // ---- Coverage attribution on the nextSteps surface ----

    /** A rated DTO carrying no findings, so only the coverage attribution can add a step. */
    private static AssessLayoutResultDto ratedDto(String rating) {
        return new AssessLayoutResultDto(
                "v-1", 5, 0, 0, 0, 0, 0.0, 145.0, 100, rating, null,
                null, null, List.of(), null, 0, null, 0, null, 0, null,
                false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of(),
                0, null, 0, null, 0, null, 1.0, null,
                rating, rating,
                1.0, null);
    }

    private static AssessLayoutResultDto withUnexamined(String rating, List<String> dimensions) {
        return withComponent(ratedDto(rating), "contextualPartialDimensions", dimensions);
    }

    private String coverageStep(AssessLayoutResultDto dto) {
        return handler.buildAssessLayoutNextSteps(dto).stream()
                .filter(t -> t.contains("Coverage on this view is incomplete"))
                .findFirst().orElse(null);
    }

    @Test
    public void buildAssessLayoutNextSteps_coverage_shouldAttributeOnEveryRatingThatAddsNoSteps() {
        // Coverage moves no rating, so a run whose only issue is an unexamined dimension rates
        // clean. The "excellent" arm is a bare break and the default arm (which covers
        // "not-applicable") is empty, so a step placed inside the rating switch would be swallowed
        // on exactly the ratings this can reach. "fair" is included as the control: a rating that
        // DOES add steps must still carry the attribution rather than having it crowded out.
        for (String rating : List.of("excellent", "fair", "not-applicable")) {
            AssessLayoutResultDto dto = withUnexamined(rating, List.of("ownIconOverLabel"));
            String step = coverageStep(dto);
            assertNotNull("the attribution must survive the '" + rating + "' arm: "
                    + handler.buildAssessLayoutNextSteps(dto), step);
            assertTrue("...and must name the dimension: " + step,
                    step.contains("ownIconOverLabel"));
        }
    }

    @Test
    public void buildAssessLayoutNextSteps_coverage_shouldNotAttributeWhenNothingWasUnexamined() {
        // The negative half. An emitter that always attributed would pass every test above.
        for (String rating : List.of("excellent", "fair", "not-applicable")) {
            AssessLayoutResultDto dto = withUnexamined(rating, List.of());
            assertNull("nothing was unexamined, so nothing may be attributed: "
                    + handler.buildAssessLayoutNextSteps(dto), coverageStep(dto));
        }
    }

    @Test
    public void buildAssessLayoutNextSteps_coverage_shouldAttributeTheExistingExportStep() {
        // Attribution, not a second remedy. "Render-verify" IS export-view, and that step is
        // already the unconditional tail; adding another would tell the caller to do the same
        // thing twice. What was missing is which dimensions the export has to settle.
        AssessLayoutResultDto dto = withUnexamined("excellent", List.of("ownIconOverLabel"));
        List<String> steps = handler.buildAssessLayoutNextSteps(dto);

        // Counted on steps that RECOMMEND the export, not on every mention of it — the
        // attribution names the step it points at, so a naive substring count sees two.
        long exportSteps = steps.stream().filter(t -> t.startsWith("Use export-view")).count();
        assertEquals("exactly one export step, the pre-existing tail: " + steps, 1, exportSteps);
        assertTrue("the attribution must point at that step rather than duplicate it: " + steps,
                coverageStep(dto).contains("The export-view step below is what settles"));
        assertTrue("...and 'below' must be true — the export step stays last: " + steps,
                steps.get(steps.size() - 1).startsWith("Use export-view"));
    }

    @Test
    public void buildAssessLayoutNextSteps_coverage_shouldNameEveryUnexaminedDimension() {
        // All three, and the plural must agree with the count — a singular template over a list of
        // three reads as though only one dimension were at issue.
        AssessLayoutResultDto dto = withUnexamined("excellent",
                List.of("labelOverlaps", "ownIconOverLabel", "parentLabelObscured"));
        String step = coverageStep(dto);

        for (String dimension : List.of("labelOverlaps", "ownIconOverLabel",
                "parentLabelObscured")) {
            assertTrue("every unexamined dimension must be named: " + dimension + " in " + step,
                    step.contains(dimension));
        }
        assertTrue("the plural must agree with the count: " + step, step.contains("were not fully"));
        assertFalse("...and must not read as a single dimension: " + step,
                step.contains("was not fully"));
    }

    @Test
    public void buildAssessLayoutNextSteps_coverage_shouldReadAsSingularForOneDimension() {
        // The other half of the agreement. Pinned separately because a template hard-coded to
        // either number passes exactly one of these two tests.
        String step = coverageStep(withUnexamined("excellent", List.of("ownIconOverLabel")));

        assertTrue("one dimension must read singular: " + step, step.contains("was not fully"));
        assertFalse("...and must not read plural: " + step, step.contains("were not fully"));
    }

    // ---- Coverage attribution on the whole-model sweep ----

    private void setSweepViews(List<String> unexaminedForV1) {
        accessor.setViews(List.of(new ViewDto("v-1", "V One", "Layered", "/")));
        accessor.setAssessLayoutBehavior(vId -> withComponent(new AssessLayoutResultDto(
                vId, 5, 3, 0, 0, 0, 0.0, 50.0, 90, "excellent", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of("ok")),
                "contextualPartialDimensions", unexaminedForV1));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_allViewsScope_shouldCarryTheDimensionsAViewCouldNotExamine()
            throws Exception {
        setSweepViews(List.of("ownIconOverLabel", "labelOverlaps"));

        Map<String, Object> envelope = callAndParse("assess-layout", Map.of("scope", "all-views"));
        Map<String, Object> entry = (Map<String, Object>) getResult(envelope).get("v-1");

        assertEquals("the names must survive to the wire, in order",
                List.of("ownIconOverLabel", "labelOverlaps"),
                entry.get("contextualPartialDimensions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_allViewsScope_emptyCoverageListMustStayOnTheWireAsAnEmptyList()
            throws Exception {
        // The empty case is DECIDED, not defaulted. The response mapper omits null values, so a
        // null here would drop the key entirely and the entry's key set would stop being constant
        // across views — leaving a consumer unable to tell "nothing was left unexamined" from
        // "this build does not report it". Asserted on the WIRE, after serialization, because that
        // is where the omission would happen; asserting the handler's own map would not see it.
        setSweepViews(List.of());

        Map<String, Object> envelope = callAndParse("assess-layout", Map.of("scope", "all-views"));
        Map<String, Object> entry = (Map<String, Object>) getResult(envelope).get("v-1");

        assertTrue("the key must be PRESENT on a view with nothing unexamined: " + entry.keySet(),
                entry.containsKey("contextualPartialDimensions"));
        assertEquals("...and must serialize as an empty list, not null and not absent",
                List.of(), entry.get("contextualPartialDimensions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void assessLayout_allViewsScope_shouldAdviseTheDrillInOnlyWhenTheSweepFoundOne()
            throws Exception {
        setSweepViews(List.of("ownIconOverLabel"));
        List<String> withFinding =
                (List<String>) callAndParse("assess-layout", Map.of("scope", "all-views"))
                        .get("nextSteps");
        assertTrue("a sweep that found one must say so: " + withFinding,
                withFinding.stream().anyMatch(
                        t -> t.contains("non-empty contextualPartialDimensions")));

        setSweepViews(List.of());
        List<String> without =
                (List<String>) callAndParse("assess-layout", Map.of("scope", "all-views"))
                        .get("nextSteps");
        assertFalse("advice to act on a finding nothing reported is noise: " + without,
                without.stream().anyMatch(
                        t -> t.contains("non-empty contextualPartialDimensions")));
    }

    @Test
    public void assessLayoutServedSurface_mustEnumerateTheCoverageKeyAndSayWhatEmptyMeans() {
        // The defect class here is an OMISSION, which matches no grep for the key's name, so both
        // served enumerations are asserted directly. The claim "absent/empty means nothing fired"
        // is one a consumer will act on, so the surface has to actually make it.
        var spec = registry.getToolSpecifications().stream()
                .filter(t -> "assess-layout".equals(t.tool().name()))
                .findFirst().orElseThrow();
        String description = spec.tool().description();
        String schema = spec.tool().inputSchema().toString();

        assertTrue("the tool description must name the key the sweep returns",
                description.contains("contextualPartialDimensions"));
        assertTrue("the scope parameter's own schema description must name it too",
                schema.contains("contextualPartialDimensions"));
        assertTrue("the description must say what an empty list means",
                description.contains("empty list means nothing was left unexamined"));
        // Case-folded: the two surfaces emphasise the word differently and the claim is what
        // matters, not its typography.
        assertTrue("the schema description must say the key is always present",
                schema.toLowerCase(java.util.Locale.ROOT).contains("always present"));
        assertTrue("the description must say the list is the contextual set, not the whole map",
                description.contains("only the CONTEXTUAL downgrades"));
    }

}
