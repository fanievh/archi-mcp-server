package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;

/**
 * The {@code merge-elements} tool's handler: its schema, how it reads the clusters, and the
 * envelope it answers with. The accessor is a stub; what a merge does is pinned in the model
 * package.
 */
public class ElementUpdateHandlerMergeElementsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final CommandRegistry registry = new CommandRegistry();
    private StubMergeAccessor accessor;
    private ElementUpdateHandler handler;

    @Before
    public void setUp() {
        accessor = new StubMergeAccessor();
        handler = new ElementUpdateHandler(accessor, new ResponseFormatter(), registry, null);
        handler.registerTools();
    }

    @Test
    public void shouldDeclareEveryArgument_withTheEnumsAndDefaultsTheToolDescribes() {
        McpSchema.Tool tool = tool();
        assertEquals(List.of("merges"), tool.inputSchema().required());
        assertEquals(List.of("merges", "propertyPolicy", "selfRelationships", "recordMergedIds",
                "collapseParallel"), new ArrayList<>(tool.inputSchema().properties().keySet()));
        assertEquals("boolean", property(tool, "collapseParallel").get("type"));
        assertTrue(tool.description().contains("collapseParallel"));
        assertEquals(List.of("union", "keep-target", "append"), property(tool, "propertyPolicy").get("enum"));
        assertEquals(List.of("drop", "keep"), property(tool, "selfRelationships").get("enum"));
        assertTrue(tool.description().startsWith("[Mutation] "));
        assertTrue(tool.description().contains("Inside a batch the call is queued as one operation and decided "
                + "at commit"));
        assertTrue(tool.description().contains("Queue merges last: once a merge is queued, every later operation "
                + "but another merge is refused until end-batch"));
        assertTrue(tool.description().contains("it may name an earlier merge's target as a duplicate, which "
                + "merges that target too"));
        assertTrue(!tool.description().contains("Refused inside a batch"));
        assertTrue(tool.description().contains("one undo step"));
        assertTrue(tool.description().contains("Related: search-elements"));
    }

    @Test
    public void shouldPassTheClustersAndPolicies_asSent() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("merges", List.of(Map.of("targetId", "t", "sourceIds", List.of("s1", "s2")),
                Map.of("targetId", "u", "sourceIds", List.of("v"))));
        args.put("propertyPolicy", "append");
        args.put("selfRelationships", "keep");
        args.put("recordMergedIds", true);

        Map<String, Object> envelope = call(args);

        assertNotNull(envelope.get("result"));
        assertEquals(List.of(new MergeClusterRequest("t", List.of("s1", "s2")),
                new MergeClusterRequest("u", List.of("v"))), accessor.merges);
        assertEquals("append", accessor.propertyPolicy);
        assertEquals("keep", accessor.selfRelationships);
        assertTrue(accessor.recordMergedIds);
    }

    @Test
    public void shouldLeaveThePoliciesNull_forTheAccessorToDefault() throws Exception {
        call(Map.of("merges", List.of(Map.of("targetId", "t", "sourceIds", List.of("s")))));
        assertNull(accessor.propertyPolicy);
        assertNull(accessor.selfRelationships);
        assertEquals(false, accessor.recordMergedIds);
        assertEquals("collapseParallel defaults to false", false, accessor.collapseParallel);
    }

    @Test
    public void shouldPassCollapseParallel_asSent_andAcceptItAsAString() throws Exception {
        call(Map.of("merges", List.of(Map.of("targetId", "t", "sourceIds", List.of("s"))),
                "collapseParallel", true));
        assertTrue(accessor.collapseParallel);
        accessor.collapseParallel = false;
        call(Map.of("merges", List.of(Map.of("targetId", "t", "sourceIds", List.of("s"))),
                "collapseParallel", "true"));
        assertTrue(accessor.collapseParallel);
    }

    @Test
    public void shouldRefuseACollapseParallelThatIsNotABoolean() throws Exception {
        assertInvalid(Map.of("merges", List.of(Map.of("targetId", "t", "sourceIds", List.of("s"))),
                "collapseParallel", "yes"), "collapseParallel");
        assertNull("the accessor never ran", accessor.merges);
    }

    @Test
    public void shouldPointAtTheFoldReports_whenParallelsWereCollapsedOrKept() throws Exception {
        accessor.collapsed = 1;
        accessor.notCollapsed = 1;
        String steps = String.valueOf(call(Map.of("merges",
                List.of(Map.of("targetId", "t", "sourceIds", List.of("s"))), "collapseParallel", true))
                .get("nextSteps"));
        // A kept pair is already on the target, so no later merge can fold it: only a delete resolves it.
        assertTrue(steps, steps.contains("Review parallelsNotCollapsed: each was kept for the reasons listed, "
                + "and no later merge folds it; delete-relationship removes one you do not want"));
        assertTrue(steps, steps.contains("Use relationshipIdMap"));

        accessor.collapsed = 0;
        accessor.notCollapsed = 0;
        steps = String.valueOf(call(Map.of("merges",
                List.of(Map.of("targetId", "t", "sourceIds", List.of("s"))), "collapseParallel", true))
                .get("nextSteps"));
        assertTrue(steps, !steps.contains("parallelsNotCollapsed") && !steps.contains("relationshipIdMap"));
    }

    /** Queued, the steps say the preview is not merged yet and that only merges may follow. */
    @Test
    public void shouldLabelAQueuedMerge_andSayOnlyMergesMayFollow() throws Exception {
        accessor.batchSeq = 3;
        Map<String, Object> envelope = call(Map.of("merges", List.of(Map.of("targetId", "t", "sourceIds", List.of("s")))));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull(result.get("preview"));
        assertNotNull(result.get("batch"));
        assertNull("the id map is a preview, not state", result.get("idMap"));
        assertEquals(List.of(
                "Merge queued as operation #3 in current batch; the preview counts the model before the batch "
                        + "commits, and the merge is decided at commit, after the operations queued before it",
                "Queue only further merge-elements calls now: any other operation is refused until end-batch",
                "Use end-batch to commit; its skippedOperations names a merge that wrote nothing. Then re-read "
                        + "the target with get-element",
                "Use preview.idMap to update any reference to a merged id held outside the model; it holds "
                        + "unless the merge is skipped at commit, and a later merge in this batch that names this "
                        + "target as a duplicate re-maps it, so apply each queued merge's idMap in queue order. "
                        + "Which parallel relationships fold is decided at commit, so re-read them after it"),
                envelope.get("nextSteps"));
    }

    @Test
    public void shouldPassAMissingField_asNull_forTheAccessorToRefuse() throws Exception {
        call(Map.of("merges", List.of(Map.of("sourceIds", List.of("s")))));
        assertEquals(List.of(new MergeClusterRequest(null, List.of("s"))), accessor.merges);
    }

    @Test
    public void shouldRefuseClustersOfTheWrongShape_beforeTheAccessorRuns() throws Exception {
        assertInvalid(Map.of("merges", "t"), "merges must be an array");
        assertInvalid(Map.of("merges", List.of("t")), "merges[0] must be an object");
        assertInvalid(Map.of("merges", List.of(Map.of("targetId", 7, "sourceIds", List.of("s")))),
                "merges[0].targetId must be a string");
        assertInvalid(Map.of("merges", List.of(Map.of("targetId", "t", "sourceIds", "s"))),
                "merges[0].sourceIds must be an array of strings");
        assertInvalid(Map.of("merges", List.of(Map.of("targetId", "t", "sourceIds", List.of("s", 3)))),
                "merges[0].sourceIds[1] must be a string");
        assertInvalid(Map.of("merges", List.of(Map.of("targetId", "t", "sourceId", List.of("s")))),
                "merges[0] has an unknown key 'sourceId'");
        assertInvalid(Map.of("merges", List.of(Map.of("targetId", "t", "sourceIds", List.of("s"))),
                "propertyPolicy", 3), "propertyPolicy must be a string");
        assertNull("the accessor never ran", accessor.merges);
    }

    @Test
    public void shouldAnswerACallerErrorFromTheAccessor_asThatError() throws Exception {
        accessor.refusal = new ModelAccessException("Element not found: ghost (merges[0].sourceIds[0])",
                ErrorCode.ELEMENT_NOT_FOUND, null, "Use search-elements", null);
        Map<String, Object> envelope = call(Map.of("merges",
                List.of(Map.of("targetId", "t", "sourceIds", List.of("ghost")))));
        assertEquals("ELEMENT_NOT_FOUND", error(envelope).get("code"));
    }

    @Test
    public void shouldNestThePreviewBesideTheProposal_inApprovalMode() throws Exception {
        accessor.proposal = new ProposalContext("p-1", "Merge 1 duplicate into 1 element", Instant.now());
        Map<String, Object> envelope = call(Map.of("merges",
                List.of(Map.of("targetId", "t", "sourceIds", List.of("s")))));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull(result.get("proposal"));
        assertNotNull(result.get("preview"));
        assertNull("not at the top level, where it would read as state the model holds", result.get("idMap"));
    }

    @Test
    public void shouldPointAtTheReports_inItsNextSteps() throws Exception {
        accessor.parallels = 1;
        Map<String, Object> envelope = call(Map.of("merges",
                List.of(Map.of("targetId", "t", "sourceIds", List.of("s")))));
        String steps = String.valueOf(envelope.get("nextSteps"));
        assertTrue(steps, steps.contains("idMap"));
        assertTrue(steps, steps.contains("parallelRelationships"));
        assertTrue(steps, !steps.contains("doublePlacements"));
    }

    // ---- helpers ----

    private McpSchema.Tool tool() {
        return registry.getToolSpecifications().stream().map(s -> s.tool())
                .filter(t -> t.name().equals("merge-elements")).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(McpSchema.Tool tool, String name) {
        return (Map<String, Object>) tool.inputSchema().properties().get(name);
    }

    private Map<String, Object> call(Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = handler.handleMergeElements(null,
                McpSchema.CallToolRequest.builder().name("merge-elements").arguments(args).build());
        return json.readValue(((McpSchema.TextContent) result.content().get(0)).text(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("error");
    }

    private void assertInvalid(Map<String, Object> args, String messagePart) throws Exception {
        Map<String, Object> err = error(call(args));
        assertNotNull("expected an error for " + args, err);
        assertEquals("INVALID_PARAMETER", err.get("code"));
        assertTrue(err.get("message") + " should contain " + messagePart,
                String.valueOf(err.get("message")).contains(messagePart));
    }

    private static final class StubMergeAccessor extends BaseTestAccessor {
        List<MergeClusterRequest> merges;
        String propertyPolicy;
        String selfRelationships;
        boolean recordMergedIds;
        boolean collapseParallel;
        int collapsed;
        int notCollapsed;
        ModelAccessException refusal;
        ProposalContext proposal;
        int parallels;
        Integer batchSeq;

        StubMergeAccessor() {
            super(true);
        }

        @Override
        public MutationResult<MergeElementsResultDto> mergeElements(String sessionId,
                List<MergeClusterRequest> merges, String propertyPolicy, String selfRelationships,
                boolean recordMergedIds, boolean collapseParallel) {
            this.merges = merges;
            this.propertyPolicy = propertyPolicy;
            this.selfRelationships = selfRelationships;
            this.recordMergedIds = recordMergedIds;
            this.collapseParallel = collapseParallel;
            if (refusal != null) {
                throw refusal;
            }
            List<MergeElementsResultDto.ParallelCollapsed> folds = new ArrayList<>();
            for (int i = 0; i < collapsed; i++) {
                folds.add(new MergeElementsResultDto.ParallelCollapsed("r", "d" + i, "AssociationRelationship",
                        List.of(), List.of(), List.of(), List.of(), List.of()));
            }
            List<MergeElementsResultDto.ParallelNotCollapsed> kept = new ArrayList<>();
            for (int i = 0; i < notCollapsed; i++) {
                kept.add(new MergeElementsResultDto.ParallelNotCollapsed("k" + i, "r", "AssociationRelationship",
                        List.of("NAME_DIFFERS")));
            }
            MergeElementsResultDto.Cluster cluster = new MergeElementsResultDto.Cluster("t", "Customer",
                    "BusinessActor", List.of("s"), 0, 0, List.of(), null, List.of(), List.of(), List.of(),
                    collapseParallel ? folds : null, collapseParallel ? kept : null, List.of(), 0, null, 0,
                    List.of(), List.of(), List.of());
            MergeElementsResultDto dto = new MergeElementsResultDto(List.of(cluster), Map.of("s", "t"),
                    new MergeElementsResultDto.Totals(1, 1, 0, 0, 0, 0, 0, 0, parallels, 0, 0, 0,
                            collapseParallel ? collapsed : null),
                    "union", "drop", false, collapseParallel, collapseParallel ? Map.of() : null);
            return batchSeq != null ? new MutationResult<>(dto, batchSeq) : new MutationResult<>(dto, null, proposal);
        }
    }
}
