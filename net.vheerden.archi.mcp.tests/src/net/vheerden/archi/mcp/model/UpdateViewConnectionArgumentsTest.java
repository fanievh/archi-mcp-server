package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;

/**
 * Pins that {@code update-view-connection}, alone and inside {@code bulk-mutate}, refuses an
 * argument it cannot read instead of falling back to clearing the route.
 *
 * <p>Both paths used to decide "the caller asked for nothing, so straighten the line" from what
 * their parsers returned, never from the keys they were given. A misspelled field, a field of
 * another tool, or a recognised field holding a value of the wrong type all parsed as nothing, so
 * each erased every stored bendpoint behind a success response. The connection here starts with one
 * stored bendpoint, and every refusal pin asserts that it is still there afterwards.</p>
 *
 * <p>The single tool is driven through its registered handler, so the pins see what an agent
 * receives; bulk is driven through {@code executeBulk}.</p>
 */
public class UpdateViewConnectionArgumentsTest {

    private static final String HANDLER_SESSION = "default";

    private ViewContentsFixture f;
    private IDiagramModelArchimateConnection conn;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-update-connection-arguments");
        IArchimateElement a = f.element("el-a");
        IArchimateElement b = f.element("el-b");
        IDiagramModelArchimateObject va = f.object(f.view, "vo-a", a, 40, 40, 120, 60);
        IDiagramModelArchimateObject vb = f.object(f.view, "vo-b", b, 400, 40, 120, 60);
        conn = f.archimateConnection("conn-ab", f.relationship("rel-ab", a, b), va, vb);
        conn.getBendpoints().add(f.bendpoint(0, -100, 0, 100));
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- an unknown key, single call -----------------------------------------------------------

    @Test
    public void updateViewConnection_shouldRefuseAndKeepTheRoute_whenTheOnlyFieldIsAnotherToolsKey() {
        assertRefusedKeepingTheRoute(single("textPosition", "source"), "textPosition");
    }

    @Test
    public void updateViewConnection_shouldRefuseAndKeepTheRoute_whenTheLabelKeyIsMisspelled() {
        assertRefusedKeepingTheRoute(single("labelPositon", "source"), "labelPositon");
    }

    @Test
    public void updateViewConnection_shouldRefuseAndKeepTheRoute_whenAStylingKeyIsMisspelled() {
        assertRefusedKeepingTheRoute(single("lineColour", "#ff0000"), "lineColour");
        assertNull("nothing is written", conn.getLineColor());
    }

    @Test
    public void updateViewConnection_shouldNameEveryUnknownKeyAndTheAcceptedSet_whenSeveralAreGiven() {
        String out = single("textPosition", "source", "lineColour", "#ff0000");

        assertRefusedKeepingTheRoute(out, "textPosition");
        assertTrue("every unknown key is named, not the first: " + out, out.contains("lineColour"));
        assertTrue("the accepted set is named: " + out,
                out.contains(String.join(", ", ConnectionUpdateArguments.DECLARED)));
    }

    @Test
    public void updateViewConnection_shouldRefuseTheWholeCall_whenAnUnknownKeySitsBesideARealField() {
        String out = single("lineColour", "#ff0000", "showLabel", Boolean.FALSE);

        assertRefusedKeepingTheRoute(out, "lineColour");
        assertTrue("the real field is not applied either", conn.isNameVisible());
    }

    @Test
    public void updateViewConnection_shouldRefuseAViewObjectKey_insteadOfReportingAChangeItNeverWrites() {
        assertRefusedKeepingTheRoute(single("fillColor", "#ff0000"), "fillColor");
        assertRefusedKeepingTheRoute(single("recede", Boolean.TRUE), "recede");
    }

    // ---- a recognised key that reads as nothing, single call -----------------------------------

    @Test
    public void updateViewConnection_shouldRefuseAndKeepTheRoute_whenLineWidthIsAString() {
        String out = single("lineWidth", "2");

        assertRefusedKeepingTheRoute(out, "lineWidth");
        assertTrue("the expected type is named: " + out, out.contains("integer"));
    }

    @Test
    public void updateViewConnection_shouldRefuseAndKeepTheRoute_whenFontStyleIsEmpty() {
        assertRefusedKeepingTheRoute(single("fontStyle", ""), "fontStyle");
    }

    @Test
    public void updateViewConnection_shouldRefuseAndKeepTheRoute_whenLineColorIsNull() {
        assertRefusedKeepingTheRoute(single("lineColor", null), "lineColor");
    }

    @Test
    public void updateViewConnection_shouldReadShowLabelFromTheStringFalse_andKeepTheRoute() {
        String out = single("showLabel", "false");

        assertSucceeded(out);
        assertFalse("the label is hidden", conn.isNameVisible());
        assertEquals("the route is kept", 1, conn.getBendpoints().size());
    }

    @Test
    public void updateViewConnection_shouldReadShowLabelFromAnyCaseOfTrue() {
        conn.setNameVisible(false);

        assertSucceeded(single("showLabel", "TRUE"));
        assertTrue(conn.isNameVisible());
    }

    @Test
    public void updateViewConnection_shouldRefuseShowLabel_whenItIsNeitherABooleanNorTrueOrFalse() {
        String out = single("showLabel", "yes", "lineColor", "#00ff00");

        assertRefusedKeepingTheRoute(out, "showLabel");
        assertTrue("nothing is written", conn.isNameVisible());
        assertNull("nothing is written", conn.getLineColor());
    }

    // ---- the empty array and the bare call, single call ----------------------------------------

    @Test
    public void updateViewConnection_shouldClearTheRoute_whenAnEmptyBendpointsArraySitsBesideStyling() {
        String out = single("bendpoints", List.of(), "lineColor", "#00ff00");

        assertSucceeded(out);
        assertEquals("an empty array is an explicit clear", 0, conn.getBendpoints().size());
        assertEquals("#00ff00", conn.getLineColor());
        assertTrue("the guidance says the route was cleared: " + out,
                out.contains("Connection bendpoints cleared (straight line)."));
    }

    @Test
    public void updateViewConnection_shouldClearTheRoute_whenAnEmptyAbsoluteArraySitsBesideALabelField() {
        assertSucceeded(single("absoluteBendpoints", List.of(), "labelPosition", "target"));
        assertEquals(0, conn.getBendpoints().size());
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_TARGET, conn.getTextPosition());
    }

    @Test
    public void updateViewConnection_shouldStillClearTheRoute_whenOnlyTheConnectionIdIsGiven() {
        assertSucceeded(single());
        assertEquals("the bare call is the published straighten", 0, conn.getBendpoints().size());
    }

    @Test
    public void updateViewConnection_shouldStillClearTheRoute_whenOnlyTheConnectionIdAndModelIdAreGiven() {
        assertSucceeded(single("modelId", f.model.getId()));
        assertEquals(0, conn.getBendpoints().size());
    }

    @Test
    public void updateViewConnection_shouldRefuseBothFormats_whenAnEmptyArraySitsBesideTheOtherFormat() {
        String out = single("bendpoints", List.of(), "absoluteBendpoints", List.of(Map.of("x", 280, "y", 0)));

        assertRefusedKeepingTheRoute(out, "Cannot provide both 'bendpoints' and 'absoluteBendpoints'");
    }

    @Test
    public void updateViewConnection_shouldRefuseBothFormats_whenAnEmptyArraySitsBesideAnUnreadableOtherFormat() {
        String out = single("bendpoints", List.of(), "absoluteBendpoints", "x");

        assertRefusedKeepingTheRoute(out, "Cannot provide both 'bendpoints' and 'absoluteBendpoints'");
    }

    @Test
    public void updateViewConnection_shouldClearOnce_whenBothFormatsAreEmptyArrays() {
        assertSucceeded(single("bendpoints", List.of(), "absoluteBendpoints", List.of()));
        assertEquals(0, conn.getBendpoints().size());
    }

    @Test
    public void updateViewConnection_shouldClearTheColourAndKeepTheRoute_whenLineColorIsEmpty() {
        conn.setLineColor("#ff0000");

        assertSucceeded(single("lineColor", ""));
        assertNull("the empty string the refusal text offers is a clear, read as a change", conn.getLineColor());
        assertEquals("not taken for the bare call", 1, conn.getBendpoints().size());
    }

    @Test
    public void updateViewConnection_shouldRepeatOnlyTheStartOfALongValue_whenRefusingIt() {
        String longValue = "x".repeat(500);

        String out = single("lineWidth", longValue);

        assertRefusedKeepingTheRoute(out, "a string of 500 characters starting");
        assertFalse("the whole value is not echoed back: " + out.length() + " chars", out.contains(longValue));
    }

    @Test
    public void updateViewConnection_shouldKeepTheRoute_whenOnlyALabelFieldIsGiven() {
        assertSucceeded(single("labelPosition", "source"));
        assertEquals(1, conn.getBendpoints().size());
    }

    // ---- what is preserved ---------------------------------------------------------------------

    @Test
    public void updateViewConnection_shouldKeepItsOwnLineStyleRefusal() {
        String out = single("lineStyle", "dashed");

        assertRefusedKeepingTheRoute(out, "lineStyle cannot be applied to a connection");
        assertFalse("not answered as an unknown key: " + out, out.contains("does not accept"));
    }

    @Test
    public void updateViewConnection_shouldTellAMissingIdItsSpelling_beforeLookingAtUnknownKeys() {
        String out = single(new LinkedHashMap<>(Map.of("id", "conn-ab", "lineColour", "#ff0000")));

        assertTrue(out, out.startsWith("ERROR "));
        assertTrue("the near miss for the required id answers first: " + out,
                out.contains("viewConnectionId") && out.contains("'id'"));
        assertFalse("not answered as an unknown key: " + out, out.contains("lineColour"));
    }

    @Test
    public void updateViewConnection_shouldRefuseBeforeQueueing_whenABatchIsOpen() {
        f.dispatcher.beginBatch(HANDLER_SESSION, "unknown key in a batch");
        try {
            assertRefusedKeepingTheRoute(single("textPosition", "source"), "textPosition");
            assertEquals("nothing is queued", Integer.valueOf(0),
                    f.dispatcher.getBatchStatus(HANDLER_SESSION).queuedCount());
        } finally {
            f.dispatcher.endBatch(HANDLER_SESSION, false);
        }
    }

    @Test
    public void updateViewConnection_shouldRefuseBeforeProposing_whenApprovalIsRequired() {
        f.approval = true;

        assertRefusedKeepingTheRoute(single("lineWidth", "2"), "lineWidth");
        assertTrue("nothing is proposed", f.dispatcher.listAllPending().isEmpty());
    }

    // ---- bulk-mutate ---------------------------------------------------------------------------

    @Test
    public void bulkUpdateViewConnection_shouldRefuseAndKeepTheRoute_whenAParamIsUnknown() {
        BulkValidationException e = bulkRefused(params("textPosition", "source", "lineColour", "#ff0000"));

        BulkOperationFailure row = onlyRow(e);
        assertEquals(ErrorCode.INVALID_PARAMETER.name(), row.errorCode());
        assertTrue(row.message(), row.message().contains("textPosition") && row.message().contains("lineColour"));
        assertTrue("the accepted set rides on the row: " + row.message(),
                row.message().contains(String.join(", ", ConnectionUpdateArguments.DECLARED)));
        assertEquals(1, conn.getBendpoints().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldRefuseOnlyTheBadOperation_whenContinueOnErrorIsOn() {
        BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(
                new BulkOperation("update-view-connection", params("labelPositon", "source")),
                new BulkOperation("update-view-connection", params("labelPosition", "target"))),
                null, true);

        assertFalse(result.allSucceeded());
        assertEquals(1, result.failedOperations().size());
        assertEquals(0, result.failedOperations().get(0).index());
        assertEquals("the good operation applied", IDiagramModelConnection.CONNECTION_TEXT_POSITION_TARGET,
                conn.getTextPosition());
        assertEquals("and the route survived both", 1, conn.getBendpoints().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldRefuseAndKeepTheRoute_whenAParamReadsAsNothing() {
        BulkOperationFailure row = onlyRow(bulkRefused(params("lineWidth", "2")));

        assertTrue(row.message(), row.message().contains("lineWidth"));
        assertEquals(1, conn.getBendpoints().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldRefuseShowLabel_whenItIsNeitherABooleanNorTrueOrFalse() {
        BulkOperationFailure row = onlyRow(bulkRefused(params("showLabel", "yes")));

        assertTrue(row.message(), row.message().contains("showLabel"));
        assertTrue("the label is untouched", conn.isNameVisible());
    }

    @Test
    public void bulkUpdateViewConnection_shouldReadShowLabelFromAnyCaseOfFalse() {
        assertTrue(bulk(params("showLabel", "False")).allSucceeded());
        assertFalse(conn.isNameVisible());
        assertEquals(1, conn.getBendpoints().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldClearTheRoute_whenAnEmptyArraySitsBesideStyling() {
        assertTrue(bulk(params("bendpoints", List.of(), "lineColor", "#00ff00")).allSucceeded());
        assertEquals(0, conn.getBendpoints().size());
        assertEquals("#00ff00", conn.getLineColor());
    }

    @Test
    public void bulkUpdateViewConnection_shouldRefuseBothFormats_whenAnEmptyArraySitsBesideTheOtherFormat() {
        BulkOperationFailure row = onlyRow(bulkRefused(params("absoluteBendpoints", List.of(),
                "bendpoints", List.of(Map.of("startX", 0, "startY", -50, "endX", 0, "endY", 50)))));

        assertTrue(row.message(), row.message().contains("Cannot provide both 'bendpoints' and 'absoluteBendpoints'"));
        assertEquals(1, conn.getBendpoints().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldClearTheColourAndKeepTheRoute_whenLineColorIsEmpty() {
        conn.setLineColor("#ff0000");

        assertTrue(bulk(params("lineColor", "")).allSucceeded());
        assertNull(conn.getLineColor());
        assertEquals(1, conn.getBendpoints().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldStillClearTheRoute_whenOnlyTheConnectionIdIsGiven() {
        assertTrue(bulk(params()).allSucceeded());
        assertEquals(0, conn.getBendpoints().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldAcceptModelId_asTheSingleToolDoes() {
        assertTrue(bulk(params("modelId", f.model.getId(), "labelPosition", "source")).allSucceeded());
        assertEquals(1, conn.getBendpoints().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldKeepItsOwnLineStyleRefusal() {
        BulkOperationFailure row = onlyRow(bulkRefused(params("lineStyle", "dashed")));

        assertTrue(row.message(), row.message().startsWith("lineStyle cannot be applied to a connection"));
    }

    @Test
    public void bulkUpdateViewConnection_shouldTellAMissingIdItsSpelling_beforeLookingAtUnknownKeys() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", "conn-ab");
        p.put("lineColour", "#ff0000");

        BulkOperationFailure row = onlyRow(bulkRefused(p));

        assertTrue(row.message(), row.message().contains("viewConnectionId") && row.message().contains("'id'"));
        assertFalse(row.message(), row.message().contains("lineColour"));
    }

    @Test
    public void bulkUpdateViewConnection_shouldRefuseBeforeQueueing_whenABatchIsOpen() {
        f.dispatcher.beginBatch(ViewContentsFixture.SESSION, "unknown param in a batch");
        try {
            bulkRefused(params("textPosition", "source"));
            assertEquals("nothing is queued", Integer.valueOf(0),
                    f.dispatcher.getBatchStatus(ViewContentsFixture.SESSION).queuedCount());
        } finally {
            f.dispatcher.endBatch(ViewContentsFixture.SESSION, false);
        }
        assertEquals(1, conn.getBendpoints().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldRefuseBeforeProposing_whenApprovalIsRequired() {
        f.approval = true;

        bulkRefused(params("textPosition", "source"));
        assertTrue("nothing is proposed", f.dispatcher.listAllPending().isEmpty());
        assertEquals(1, conn.getBendpoints().size());
    }

    // ---- harness -------------------------------------------------------------------------------

    private void assertRefusedKeepingTheRoute(String out, String named) {
        assertTrue("refused: " + out, out.startsWith("ERROR "));
        assertTrue("as a caller error: " + out, out.contains("\"code\":\"INVALID_PARAMETER\""));
        assertTrue("naming " + named + ": " + out, out.contains(named));
        assertEquals("the stored bendpoint survives", 1, conn.getBendpoints().size());
    }

    private static void assertSucceeded(String out) {
        assertFalse("succeeded: " + out, out.startsWith("ERROR "));
    }

    private String single(Object... keysAndValues) {
        return single(params(keysAndValues));
    }

    private String single(Map<String, Object> args) {
        return f.serve("update-view-connection", args);
    }

    /** viewConnectionId first, then the given pairs; a LinkedHashMap so a null value is allowed. */
    private static Map<String, Object> params(Object... keysAndValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("viewConnectionId", "conn-ab");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            p.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return p;
    }

    private BulkMutationResult bulk(Map<String, Object> p) {
        return f.accessor.executeBulk(ViewContentsFixture.SESSION,
                List.of(new BulkOperation("update-view-connection", p)), null, false);
    }

    private BulkValidationException bulkRefused(Map<String, Object> p) {
        try {
            BulkMutationResult result = bulk(p);
            fail("expected a refusal, got allSucceeded=" + result.allSucceeded());
            return null;
        } catch (BulkValidationException e) {
            for (BulkOperationFailure row : e.getFailures()) {
                assertEquals("each row is a caller error: " + row.message(),
                        ErrorCode.INVALID_PARAMETER.name(), row.errorCode());
            }
            return e;
        }
    }

    private static BulkOperationFailure onlyRow(BulkValidationException e) {
        List<BulkOperationFailure> rows = e.getFailures();
        assertEquals("one failure row: " + rows, 1, rows.size());
        return rows.get(0);
    }
}
