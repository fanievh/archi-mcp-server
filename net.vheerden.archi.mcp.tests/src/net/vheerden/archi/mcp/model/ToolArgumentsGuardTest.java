package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * An argument a tool does not read is refused by name, before the tool runs, on every tool and every
 * {@code bulk-mutate} operation, through the tools as the server registers them (call scope included).
 * Each refusal is pinned whole: a pin that only checks the refusal names the key passes for any refusal
 * that names it.
 */
public class ToolArgumentsGuardTest {

    private static final String REFUSED = ". An argument the tool cannot read is refused rather than ignored, "
            + "and nothing was changed. ";

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("guard-model");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    private static String refusal(String tool, String keys, String declared) {
        return tool + " does not accept " + keys + REFUSED + tool + " accepts " + declared + ".";
    }

    private IDiagramModelArchimateConnection conn() {
        IDiagramModelArchimateObject source = (IDiagramModelArchimateObject) h.f.view.getChildren().get(0);
        return (IDiagramModelArchimateConnection) source.getSourceConnections().get(0);
    }

    private String bounds() {
        StringBuilder sb = new StringBuilder();
        for (Object o : h.f.view.getChildren()) {
            IDiagramModelObject obj = (IDiagramModelObject) o;
            sb.append(obj.getId()).append('=').append(obj.getBounds().getX()).append(',').append(obj.getBounds().getY())
                    .append(',').append(obj.getBounds().getWidth()).append('x').append(obj.getBounds().getHeight())
                    .append(' ');
        }
        return sb.toString();
    }

    private boolean elementNamed(String name) {
        return h.f.model.getFolder(com.archimatetool.model.FolderType.BUSINESS).getElements().stream()
                .anyMatch(e -> e instanceof com.archimatetool.model.INameable n && name.equals(n.getName()));
    }

    private void queueOne(String name) {
        h.success("begin-batch", "modelId", "guard-model");
        h.success("create-element", "type", "BusinessActor", "name", name);
    }

    // ---- the batch that commits instead of rolling back ----

    @Test
    public void shouldRefuseAMisspelledRollback_andKeepTheBatchOpen() {
        queueOne("Queued One");
        JsonNode error = h.servedError("end-batch", "rolback", true);
        assertEquals("INVALID_PARAMETER", error.path("code").asText());
        assertEquals(refusal("end-batch", "'rolback'", "rollback"), error.path("message").asText());
        assertEquals("Remove it, or correct the spelling. end-batch accepts rollback.",
                error.path("suggestedCorrection").asText());
        assertFalse("nothing may be committed", elementNamed("Queued One"));
        h.success("end-batch", "rollback", true);
        assertFalse("the batch was still open, so the rollback discarded it", elementNamed("Queued One"));
    }

    @Test
    public void shouldRollBack_whenRollbackIsTheStringTrue() {
        queueOne("Queued Two");
        JsonNode reply = h.success("end-batch", "rollback", "TRUE");
        assertTrue(reply.toString(), reply.path("result").path("rolledBack").asBoolean());
        assertFalse(elementNamed("Queued Two"));
    }

    @Test
    public void shouldRefuseARollbackThatIsNotTrueOrFalse_andKeepTheBatchOpen() {
        queueOne("Queued Three");
        assertEquals("rollback must be true or false, got the string 'yes'. Nothing was changed.",
                h.message("end-batch", "rollback", "yes"));
        assertEquals("rollback must be true or false, got the integer 1. Nothing was changed.",
                h.message("end-batch", "rollback", 1));
        assertFalse(elementNamed("Queued Three"));
        h.success("end-batch", "rollback", false);
        assertTrue("the batch was still open and commits now", elementNamed("Queued Three"));
    }

    @Test
    public void shouldAcceptAStaleModelId_onEndBatch() {
        queueOne("Queued Four");
        JsonNode reply = h.success("end-batch", "rollback", true, "modelId", "no-such-model");
        assertTrue(reply.path("result").path("rolledBack").asBoolean());
    }

    // ---- the dry run that runs for real ----

    @Test
    public void shouldRefuseAMisspelledDryRun_andChangeNothing() {
        String before = bounds();
        JsonNode error = h.servedError("apply-element-spacing-recommendations",
                "viewId", h.f.view.getId(), "dryrun", true);
        assertEquals(refusal("apply-element-spacing-recommendations", "'dryrun'",
                "viewId, dryRun, targetSpacing, iterationBudget, modelId"), error.path("message").asText());
        assertEquals(before, bounds());
        assertFalse(h.f.stack.canUndo());
    }

    @Test
    public void shouldRefuseADryRunThatIsNotTrueOrFalse() {
        for (String tool : List.of("apply-element-spacing-recommendations", "apply-group-spacing-recommendations",
                "apply-spacing-recommendations")) {
            assertEquals(tool, "dryRun must be true or false, got the string 'maybe'. Nothing was changed.",
                    h.message(tool, "viewId", h.f.view.getId(), "dryRun", "maybe"));
        }
        assertFalse(h.f.stack.canUndo());
    }

    // ---- the id filter that widens to the whole view ----

    @Test
    public void shouldRefuseAMisspelledIdFilter_andResizeNothing() {
        String before = bounds();
        assertEquals(refusal("resize-elements-to-fit", "'elementIDs'", "viewId, elementIds, wrapFit, modelId"),
                h.message("resize-elements-to-fit", "viewId", h.f.view.getId(), "elementIDs", List.of("vo-a")));
        assertEquals(before, bounds());
    }

    // ---- a read tool ----

    @Test
    public void shouldRefuseAMisspelledFilter_onARead() {
        assertEquals(refusal("search-elements", "'lyer', 'tpye'",
                "query, type, layer, specialization, fields, exclude, limit, cursor, dryRun, format, modelId"),
                h.message("search-elements", "query", "el", "tpye", "BusinessActor", "lyer", "Business"));
    }

    // ---- order: the tool's own required read, and modelId, answer first ----

    @Test
    public void shouldLetTheToolNameANearMiss_whenARequiredKeyIsMissing() {
        String message = h.message("update-view-connection", "relationshipId", "conn-ab", "lineWidht", 2);
        assertEquals("Missing required parameter 'viewConnectionId' — 'relationshipId' was supplied, which this "
                + "tool does not accept.", message);
    }

    @Test
    public void shouldAnswerABadModelIdFirst() {
        assertEquals("modelId must be the id of an open model, as a non-empty string; got a integer",
                h.message("search-elements", "query", "el", "modelId", 5, "tpye", "x"));
    }

    @Test
    public void shouldReachTheTool_whenACallHasNoArguments() {
        JsonNode reply = h.call("end-batch", null);
        assertEquals("BATCH_NOT_ACTIVE", reply.path("error").path("code").asText());
    }

    @Test
    public void shouldKeepEveryKeyOfAFreeFormMap() {
        h.success("create-element", "type", "BusinessActor", "name", "With Props",
                "properties", Map.of("anyKeyAtAll", "v", "another", "w"));
        assertTrue(elementNamed("With Props"));
    }

    @Test
    public void shouldKeepEveryInnerKey_ofEachFreeFormArgument() {
        h.success("create-element", "type", "BusinessActor", "name", "With Source",
                "source", Map.of("anyKeyAtAll", "v"));
        assertTrue(elementNamed("With Source"));
        h.success("search-and-create", "query", "zz-nothing-matches", "createType", "BusinessActor",
                "createName", "Searched", "createProperties", Map.of("freeKey", "v"),
                "createSource", Map.of("otherFreeKey", "w"));
        assertTrue(elementNamed("Searched"));
        String answer = h.call("optimize-group-order", args("viewId", h.f.view.getId(),
                "groupArrangements", Map.of("any-group-id", "row"))).toString();
        assertFalse(answer, answer.contains(" does not accept "));
    }

    @Test
    public void shouldAcceptAStaleModelId_onEveryToolThatTouchesNoModel() {
        for (String tool : List.of("get-guidance", "get-session-filters", "set-session-filter", "get-batch-status",
                "end-batch", "list-pending-approvals", "get-models")) {
            String answer = h.call(tool, args("modelId", "no-such-model")).toString();
            assertFalse(tool + ": " + answer, answer.contains(" does not accept "));
            assertFalse(tool + ": " + answer, answer.contains("modelId must be"));
        }
    }

    @Test
    public void shouldRefuseAMisspelledIdFilter_onEveryToolThatWidensWithoutIt() {
        String before = bounds();
        for (String[] call : new String[][] {{"auto-route-connections", "connectionIDs"},
                {"auto-connect-view", "elementIDs"}, {"arrange-groups", "groupIDs"}}) {
            Map<String, Object> args = args("viewId", h.f.view.getId(), call[1], List.of("x"));
            if (call[0].equals("arrange-groups")) {
                args.put("arrangement", "grid");
            }
            String message = h.errorOf(call[0], args).path("message").asText();
            assertTrue(message, message.startsWith(call[0] + " does not accept '" + call[1] + "'" + REFUSED));
        }
        assertEquals(before, bounds());
        assertFalse(h.f.stack.canUndo());
    }

    @Test
    public void shouldRefuseNodeStylingOnAConnectionTool() {
        String message = h.message("add-connection-to-view", "viewId", h.f.view.getId(), "relationshipId", "rel-ab",
                "sourceViewObjectId", "vo-a", "targetViewObjectId", "vo-b", "fillColor", "#ffffff", "recede", false);
        assertTrue(message, message.startsWith("add-connection-to-view does not accept 'fillColor', 'recede'. "));
    }

    // ---- approval and batch modes refuse before anything is queued or proposed ----

    @Test
    public void shouldRefuseBeforeProposing_inApprovalMode() {
        h.f.approval = true;
        h.message("create-element", "type", "BusinessActor", "name", "Proposed", "nmae", "x");
        assertEquals("[]", h.call("list-pending-approvals", args()).path("result").path("proposals").toString());
    }

    @Test
    public void shouldRefuseBeforeQueueing_inABatch() {
        h.success("begin-batch", "modelId", "guard-model");
        h.message("create-element", "type", "BusinessActor", "name", "Queued", "nmae", "x");
        h.success("end-batch");
        assertFalse(elementNamed("Queued"));
    }

    // ---- bulk ----

    private static Map<String, Object> op(String tool, Map<String, Object> params) {
        return Map.of("tool", tool, "params", params);
    }

    private static final String CREATE_ELEMENT_KEYS =
            "type, name, documentation, properties, folderId, force, source, specialization, modelId";

    @Test
    public void shouldRefuseAnUnknownParam_asTheOperationsRow() {
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                op("create-element", Map.of("type", "BusinessActor", "name", "Bulk A")),
                op("create-element", Map.of("type", "BusinessActor", "name", "Bulk B", "nmae", "x")))));
        assertTrue(reply.toString(), reply.path("isError").asBoolean());
        JsonNode error = reply.path("error");
        assertEquals("BULK_VALIDATION_FAILED", error.path("code").asText());
        assertTrue(error.toString(), error.toString().contains(
                refusal("create-element", "'nmae'", CREATE_ELEMENT_KEYS)));
        assertFalse("all or nothing: the good operation is not applied", elementNamed("Bulk A"));
    }

    @Test
    public void shouldApplyTheRest_underContinueOnError() {
        JsonNode reply = h.call("bulk-mutate", args("continueOnError", true, "operations", List.of(
                op("create-element", Map.of("type", "BusinessActor", "name", "Bulk C")),
                op("create-element", Map.of("type", "BusinessActor", "name", "Bulk D", "nmae", "x")))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertTrue(elementNamed("Bulk C"));
        assertFalse(elementNamed("Bulk D"));
        assertTrue(reply.toString(), reply.toString().contains(refusal("create-element", "'nmae'", CREATE_ELEMENT_KEYS)));
    }

    @Test
    public void shouldRefuseAnUnknownParam_inAnOpenBatchAndInApprovalMode() {
        h.success("begin-batch", "modelId", "guard-model");
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                op("create-element", Map.of("type", "BusinessActor", "name", "Bulk E", "nmae", "x")))));
        assertEquals("BULK_VALIDATION_FAILED", reply.path("error").path("code").asText());
        h.success("end-batch");
        assertFalse(elementNamed("Bulk E"));

        h.f.approval = true;
        reply = h.call("bulk-mutate", args("operations", List.of(
                op("create-element", Map.of("type", "BusinessActor", "name", "Bulk F", "nmae", "x")))));
        assertEquals("BULK_VALIDATION_FAILED", reply.path("error").path("code").asText());
        assertEquals("[]", h.call("list-pending-approvals", args()).path("result").path("proposals").toString());
    }

    @Test
    public void shouldRefuseAnUnknownParam_onTheBulkOnlyOperation() {
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                op("set-view-label-expression", Map.of("viewId", h.f.view.getId(), "labelExpression", "x",
                        "objectType", List.of("note"))))));
        assertTrue(reply.toString(), reply.toString().contains(refusal("set-view-label-expression",
                "'objectType'", "viewId, labelExpression, objectTypes")));
    }

    @Test
    public void shouldRefuseARecedeThatIsNotTrueOrFalse_onBothPaths() {
        assertEquals("recede must be true or false, got the string 'nope'. Nothing was changed.",
                h.message("add-to-view", "viewId", h.f.view.getId(), "elementId", "do-1", "recede", "nope"));
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                op("add-to-view", Map.of("viewId", h.f.view.getId(), "elementId", "do-1", "recede", "nope")))));
        assertTrue(reply.toString(), reply.toString()
                .contains("recede must be true or false, got the string 'nope'. Nothing was changed."));
    }

    @Test
    public void shouldRefuseAnOptOutThatIsNotTrueOrFalse() {
        assertEquals("enableChannelNudging must be true or false, got the string 'off'. Nothing was changed.",
                h.message("auto-route-connections", "viewId", h.f.view.getId(), "enableChannelNudging", "off"));
        assertFalse(h.f.stack.canUndo());
    }

    // ---- apply-positions entries ----

    private Map<String, Object> entry(Object... keyValues) {
        return args(keyValues);
    }

    private JsonNode applyConnection(Map<String, Object> entry) {
        return h.call("apply-positions", args("viewId", h.f.view.getId(), "connections", List.of(entry)));
    }

    @Test
    public void shouldRefuseAMisspelledEntryKey_andKeepTheRoute() {
        conn().getBendpoints().add(h.f.bendpoint(10, 10, -10, -10));
        JsonNode reply = applyConnection(entry("viewConnectionId", "conn-ab",
                "bendPoints", List.of(Map.of("startX", 5, "startY", 5, "endX", -5, "endY", -5))));
        assertEquals("connections[0]: the entry does not accept 'bendPoints'. A key the tool cannot read is "
                + "refused rather than ignored, and nothing was changed. A connections entry accepts "
                + "viewConnectionId, bendpoints, absoluteBendpoints.", reply.path("error").path("message").asText());
        assertEquals(1, conn().getBendpoints().size());
    }

    @Test
    public void shouldRefuseABendpointValueThatReadsAsNothing_andKeepTheRoute() {
        conn().getBendpoints().add(h.f.bendpoint(10, 10, -10, -10));
        assertEquals("connections[0]: bendpoints must be an array of {startX, startY, endX, endY}, got the string "
                + "'x'. Nothing was changed, and the route was kept.",
                applyConnection(entry("viewConnectionId", "conn-ab", "bendpoints", "x"))
                        .path("error").path("message").asText());
        assertEquals("connections[0]: absoluteBendpoints must be an array of {x, y}, got null. Nothing was "
                + "changed, and the route was kept.",
                applyConnection(entry("viewConnectionId", "conn-ab", "absoluteBendpoints", null))
                        .path("error").path("message").asText());
        assertEquals("connections[0]: Cannot provide both 'bendpoints' and 'absoluteBendpoints'",
                applyConnection(entry("viewConnectionId", "conn-ab", "bendpoints", List.of(),
                        "absoluteBendpoints", List.of(Map.of("x", 1, "y", 2)))).path("error").path("message").asText());
        assertEquals(1, conn().getBendpoints().size());
    }

    @Test
    public void shouldApplyARoute_besideANullForTheOtherFormat() {
        JsonNode reply = applyConnection(entry("viewConnectionId", "conn-ab", "bendpoints",
                List.of(Map.of("startX", 10, "startY", 10, "endX", -10, "endY", -10)), "absoluteBendpoints", null));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals(1, conn().getBendpoints().size());
        // An explicit clear beside a null is a clear, as on update-view-connection.
        reply = applyConnection(entry("viewConnectionId", "conn-ab", "bendpoints", List.of(),
                "absoluteBendpoints", null));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals(0, conn().getBendpoints().size());
    }

    @Test
    public void shouldEchoAnUnreadBendpointValue_bounded() {
        conn().getBendpoints().add(h.f.bendpoint(10, 10, -10, -10));
        String digits = "1".repeat(60);
        assertEquals("connections[0]: bendpoints must be an array of {startX, startY, endX, endY}, got the "
                + "biginteger '" + "1".repeat(40) + "' (the start of 60 characters). Nothing was changed, and the "
                + "route was kept.",
                applyConnection(entry("viewConnectionId", "conn-ab", "bendpoints", new java.math.BigInteger(digits)))
                        .path("error").path("message").asText());
        assertEquals(1, conn().getBendpoints().size());
    }

    @Test
    public void shouldClear_onAnEmptyArrayOrABareEntry() {
        conn().getBendpoints().add(h.f.bendpoint(10, 10, -10, -10));
        assertFalse(applyConnection(entry("viewConnectionId", "conn-ab", "bendpoints", List.of()))
                .path("isError").asBoolean());
        assertEquals(0, conn().getBendpoints().size());
        conn().getBendpoints().add(h.f.bendpoint(10, 10, -10, -10));
        assertFalse(applyConnection(entry("viewConnectionId", "conn-ab")).path("isError").asBoolean());
        assertEquals(0, conn().getBendpoints().size());
    }

    @Test
    public void shouldRefuseAMisspelledPositionKey() {
        String before = bounds();
        JsonNode reply = h.call("apply-positions", args("viewId", h.f.view.getId(),
                "positions", List.of(entry("viewObjectId", "vo-a", "X", 5))));
        assertEquals("positions[0]: the entry does not accept 'X'. A key the tool cannot read is refused rather "
                + "than ignored, and nothing was changed. A positions entry accepts viewObjectId, x, y, width, "
                + "height.", reply.path("error").path("message").asText());
        assertEquals(before, bounds());
    }
}
