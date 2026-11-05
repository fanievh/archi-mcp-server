package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;

/**
 * Pins that a {@code labelPosition} neither connection tool can read is refused as a caller error,
 * on {@code add-connection-to-view} and {@code update-view-connection}, alone and inside
 * {@code bulk-mutate}.
 *
 * <p>Each path used to parse the value with its own copy of a parser that threw
 * {@link IllegalArgumentException}. The handlers answer that exception as INTERNAL_ERROR, and bulk's
 * per-operation catch does not take it, so one bad operation failed the whole call as "Error
 * executing bulk mutation". The refusal asserted here is identified by its own phrase, not by the key
 * alone: the unknown-key and read-nothing refusals both name {@code labelPosition} too.</p>
 *
 * <p>The single tools are driven through their registered handlers, so the pins see what an agent
 * receives; bulk is driven through {@code executeBulk}.</p>
 */
public class ConnectionLabelPositionArgumentTest {

    private static final String HANDLER_SESSION = "default";

    /** The phrase only this refusal uses. */
    private static final String REFUSAL = "Invalid labelPosition: expected source, middle or target";

    /** One value of each shape the reader refuses: another word, empty, number, boolean, array, object. */
    private static final List<Object> UNREADABLE = List.of(
            "left", "", 2, Boolean.TRUE, List.of("source"), Map.of("value", "source"));

    private ViewContentsFixture f;
    private IDiagramModelArchimateConnection conn;
    private IArchimateElement a;
    private IArchimateElement b;
    private IDiagramModelArchimateObject vb;
    private IArchimateRelationship relBa;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-connection-label-position");
        a = f.element("el-a");
        b = f.element("el-b");
        IDiagramModelArchimateObject va = f.object(f.view, "vo-a", a, 40, 40, 120, 60);
        vb = f.object(f.view, "vo-b", b, 400, 40, 120, 60);
        conn = f.archimateConnection("conn-ab", f.relationship("rel-ab", a, b), va, vb);
        conn.getBendpoints().add(f.bendpoint(0, -100, 0, 100));
        conn.setTextPosition(IDiagramModelConnection.CONNECTION_TEXT_POSITION_MIDDLE);
        // Not yet on the view: what add-connection-to-view places, from vo-b to vo-a.
        relBa = f.relationship("rel-ba", b, a);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- update-view-connection, single call ---------------------------------------------------

    @Test
    public void updateViewConnection_shouldRefuseAsACallerError_whenLabelPositionIsAnotherWord() {
        String out = update("labelPosition", "left", "lineColor", "#00ff00");

        assertUpdateRefused(out);
        assertTrue("the value is repeated: " + out, out.contains("the string \\\"left\\\""));
        assertTrue("the correction names the values: " + out,
                out.contains("Pass labelPosition: \\\"source\\\", \\\"middle\\\" or \\\"target\\\""));
        assertNull("nothing else is written either", conn.getLineColor());
    }

    @Test
    public void updateViewConnection_shouldRefuseAsACallerError_whenLabelPositionIsNotAString() {
        assertUpdateRefused(update("labelPosition", 1));
        assertUpdateRefused(update("labelPosition", Boolean.TRUE));
        assertUpdateRefused(update("labelPosition", ""));
        assertUpdateRefused(update("labelPosition", List.of("source")));
        assertUpdateRefused(update("labelPosition", Map.of("value", "source")));
    }

    @Test
    public void updateViewConnection_shouldRepeatTheValueAsGiven_notLowerCased() {
        String out = update("labelPosition", "Left");

        assertUpdateRefused(out);
        assertTrue(out, out.contains("the string \\\"Left\\\""));
    }

    @Test
    public void updateViewConnection_shouldRepeatOnlyTheStartOfALongLabelPosition() {
        String longValue = "q".repeat(200);

        String out = update("labelPosition", longValue);

        assertUpdateRefused(out);
        assertTrue(out, out.contains("a string of 200 characters starting"));
        assertFalse("the whole value is not echoed back: " + out.length() + " chars", out.contains(longValue));
    }

    @Test
    public void updateViewConnection_shouldReadLabelPositionInAnyCase() {
        assertSucceeded(update("labelPosition", "Target"));
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_TARGET, conn.getTextPosition());

        assertSucceeded(update("labelPosition", "SOURCE"));
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE, conn.getTextPosition());
        assertEquals("the route is kept", 1, conn.getBendpoints().size());
    }

    @Test
    public void updateViewConnection_shouldReadAnUpperCaseLabelPosition_underATurkishDefaultLocale() {
        conn.setTextPosition(IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE);
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertSucceeded(update("labelPosition", "MIDDLE"));
        } finally {
            Locale.setDefault(before);
        }
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_MIDDLE, conn.getTextPosition());
    }

    @Test
    public void updateViewConnection_shouldRefuseANullLabelPosition_besideAnotherField_asWhenAlone() {
        // A key that is sent must read, alone or beside another: JSON null beside a colour used to be
        // read as absent and the colour applied, while the same null alone was refused.
        String out = update("labelPosition", null, "lineColor", "#00ff00");
        assertTrue("refused: " + out, out.startsWith("ERROR "));
        assertTrue(out, out.contains("\"message\":\"update-view-connection cannot read labelPosition (expected one of "
                + "source, middle, target, got null). Every argument sent must read, so nothing was changed, and the "
                + "route was kept.\""));
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_MIDDLE, conn.getTextPosition());
        assertNull("the colour beside it is not applied", conn.getLineColor());
    }

    @Test
    public void updateViewConnection_shouldRefuseAndKeepTheRoute_whenLabelPositionIsNullAndAlone() {
        String out = update("labelPosition", null);

        // Not this refusal: a present key that reads as nothing is the read-nothing refusal, which keeps
        // the route rather than taking the call for the bare one that straightens it.
        assertTrue("refused: " + out, out.startsWith("ERROR "));
        assertTrue("as a caller error: " + out, out.contains("\"code\":\"INVALID_PARAMETER\""));
        assertTrue("by the read-nothing refusal: " + out, out.contains("read nothing to change from labelPosition"));
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_MIDDLE, conn.getTextPosition());
        assertEquals("the stored bendpoint survives", 1, conn.getBendpoints().size());
    }

    @Test
    public void updateViewConnection_shouldRefuseBeforeQueueing_whenABatchIsOpen() {
        f.dispatcher.beginBatch(HANDLER_SESSION, "bad labelPosition in a batch");
        try {
            assertUpdateRefused(update("labelPosition", "left"));
            assertEquals("nothing is queued", Integer.valueOf(0),
                    f.dispatcher.getBatchStatus(HANDLER_SESSION).queuedCount());
        } finally {
            f.dispatcher.endBatch(HANDLER_SESSION, false);
        }
    }

    // ---- add-connection-to-view, single call ---------------------------------------------------

    @Test
    public void addConnectionToView_shouldRefuseAsACallerError_whenLabelPositionIsAnotherWord() {
        String out = add("labelPosition", "left");

        assertAddRefused(out);
        assertTrue("the value is repeated: " + out, out.contains("the string \\\"left\\\""));
    }

    @Test
    public void addConnectionToView_shouldRefuseAsACallerError_whenLabelPositionIsNotAString() {
        assertAddRefused(add("labelPosition", 2));
        assertAddRefused(add("labelPosition", Boolean.FALSE));
        assertAddRefused(add("labelPosition", ""));
        assertAddRefused(add("labelPosition", List.of("source")));
        assertAddRefused(add("labelPosition", Map.of("value", "source")));
    }

    @Test
    public void addConnectionToView_shouldReadLabelPositionInAnyCase() {
        assertSucceeded(add("labelPosition", "Source"));
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE, added().getTextPosition());
    }

    @Test
    public void addConnectionToView_shouldPlaceTheConnectionAsWithoutIt_whenLabelPositionIsNull() {
        IArchimateRelationship second = f.relationship("rel-ba-2", b, a);

        assertSucceeded(add("labelPosition", null));
        assertSucceeded(add("relationshipId", second.getId()));

        List<IDiagramModelConnection> placed = vb.getSourceConnections();
        assertEquals(2, placed.size());
        assertEquals("JSON null is the default, as an absent key is",
                placed.get(1).getTextPosition(), placed.get(0).getTextPosition());
    }

    @Test
    public void addConnectionToView_shouldRefuseBeforeProposing_whenApprovalIsRequired() {
        f.approval = true;

        assertAddRefused(add("labelPosition", "left"));
        assertTrue("nothing is proposed", f.dispatcher.listAllPending().isEmpty());
    }

    // ---- bulk-mutate ---------------------------------------------------------------------------

    @Test
    public void bulkUpdateViewConnection_shouldRefuseOnlyThatOperation_asACallerError() {
        BulkOperationFailure row = onlyRow(bulkRefused(
                new BulkOperation("update-view-connection", updateParams("labelPosition", "left"))));

        assertEquals("update-view-connection", row.tool());
        assertTrue(row.message(), row.message().startsWith(REFUSAL));
        assertTrue(row.message(), row.message().contains("the string \"left\""));
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_MIDDLE, conn.getTextPosition());
        assertEquals(1, conn.getBendpoints().size());
    }

    @Test
    public void bulkAddConnectionToView_shouldRefuseOnlyThatOperation_asACallerError() {
        BulkOperationFailure row = onlyRow(bulkRefused(
                new BulkOperation("add-connection-to-view", addParams("labelPosition", 1))));

        assertEquals("add-connection-to-view", row.tool());
        assertTrue(row.message(), row.message().startsWith(REFUSAL));
        assertTrue(row.message(), row.message().contains("the integer 1"));
        assertEquals("nothing is placed", 0, vb.getSourceConnections().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldRefuseEveryValueItCannotRead() {
        for (Object value : UNREADABLE) {
            BulkOperationFailure row = onlyRow(bulkRefused(
                    new BulkOperation("update-view-connection", updateParams("labelPosition", value))));
            assertTrue(value + ": " + row.message(), row.message().startsWith(REFUSAL));
        }
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_MIDDLE, conn.getTextPosition());
        assertEquals(1, conn.getBendpoints().size());
    }

    @Test
    public void bulkAddConnectionToView_shouldRefuseEveryValueItCannotRead() {
        for (Object value : UNREADABLE) {
            BulkOperationFailure row = onlyRow(bulkRefused(
                    new BulkOperation("add-connection-to-view", addParams("labelPosition", value))));
            assertTrue(value + ": " + row.message(), row.message().startsWith(REFUSAL));
        }
        assertEquals("nothing is placed", 0, vb.getSourceConnections().size());
    }

    @Test
    public void bulk_shouldApplyTheOtherOperations_whenContinueOnErrorIsOn() {
        BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(
                new BulkOperation("add-connection-to-view", addParams("labelPosition", "left")),
                new BulkOperation("update-view-connection", updateParams("labelPosition", "target"))),
                null, true);

        assertFalse(result.allSucceeded());
        assertEquals(1, result.failedOperations().size());
        BulkOperationFailure row = result.failedOperations().get(0);
        assertEquals(0, row.index());
        assertEquals(ErrorCode.INVALID_PARAMETER.name(), row.errorCode());
        assertTrue(row.message(), row.message().startsWith(REFUSAL));
        assertEquals("the bad add placed nothing", 0, vb.getSourceConnections().size());
        assertEquals("the good update applied", IDiagramModelConnection.CONNECTION_TEXT_POSITION_TARGET,
                conn.getTextPosition());
    }

    @Test
    public void bulk_shouldReadLabelPositionInAnyCase_onBothTools() {
        BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(
                new BulkOperation("add-connection-to-view", addParams("labelPosition", "TARGET")),
                new BulkOperation("update-view-connection", updateParams("labelPosition", "Source"))),
                null, false);

        assertTrue(result.allSucceeded());
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_TARGET, added().getTextPosition());
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE, conn.getTextPosition());
    }

    @Test
    public void bulkAddConnectionToView_shouldRefuseBeforeQueueing_whenABatchIsOpen() {
        f.dispatcher.beginBatch(ViewContentsFixture.SESSION, "bad labelPosition in a bulk batch");
        try {
            bulkRefused(new BulkOperation("add-connection-to-view", addParams("labelPosition", "left")));
            assertEquals("nothing is queued", Integer.valueOf(0),
                    f.dispatcher.getBatchStatus(ViewContentsFixture.SESSION).queuedCount());
        } finally {
            f.dispatcher.endBatch(ViewContentsFixture.SESSION, false);
        }
        assertEquals(0, vb.getSourceConnections().size());
    }

    @Test
    public void bulkUpdateViewConnection_shouldRefuseBeforeProposing_whenApprovalIsRequired() {
        f.approval = true;

        bulkRefused(new BulkOperation("update-view-connection", updateParams("labelPosition", "left")));
        assertTrue("nothing is proposed", f.dispatcher.listAllPending().isEmpty());
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_MIDDLE, conn.getTextPosition());
    }

    // ---- harness -------------------------------------------------------------------------------

    private void assertUpdateRefused(String out) {
        assertRefused(out);
        assertEquals("the label is untouched", IDiagramModelConnection.CONNECTION_TEXT_POSITION_MIDDLE,
                conn.getTextPosition());
        assertEquals("the stored bendpoint survives", 1, conn.getBendpoints().size());
    }

    private void assertAddRefused(String out) {
        assertRefused(out);
        assertEquals("nothing is placed", 0, vb.getSourceConnections().size());
    }

    private static void assertRefused(String out) {
        assertTrue("refused: " + out, out.startsWith("ERROR "));
        // INTERNAL_ERROR is the only code the handlers log at ERROR, so this also says nothing was.
        assertTrue("as a caller error: " + out, out.contains("\"code\":\"INVALID_PARAMETER\""));
        assertTrue("by this refusal: " + out, out.contains(REFUSAL));
    }

    private static void assertSucceeded(String out) {
        assertFalse("succeeded: " + out, out.startsWith("ERROR "));
    }

    /** The connection add-connection-to-view placed, from vo-b. */
    private IDiagramModelConnection added() {
        List<IDiagramModelConnection> placed = vb.getSourceConnections();
        assertEquals("one connection placed: " + placed, 1, placed.size());
        return placed.get(0);
    }

    private String update(Object... keysAndValues) {
        return f.serve("update-view-connection", updateParams(keysAndValues));
    }

    private String add(Object... keysAndValues) {
        return f.serve("add-connection-to-view", addParams(keysAndValues));
    }

    private static Map<String, Object> updateParams(Object... keysAndValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("viewConnectionId", "conn-ab");
        return with(p, keysAndValues);
    }

    private Map<String, Object> addParams(Object... keysAndValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("viewId", f.view.getId());
        p.put("relationshipId", relBa.getId());
        p.put("sourceViewObjectId", "vo-b");
        p.put("targetViewObjectId", "vo-a");
        return with(p, keysAndValues);
    }

    /** A LinkedHashMap so a null value is allowed. */
    private static Map<String, Object> with(Map<String, Object> p, Object... keysAndValues) {
        for (int i = 0; i < keysAndValues.length; i += 2) {
            p.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return p;
    }

    private BulkValidationException bulkRefused(BulkOperation op) {
        try {
            BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(op), null, false);
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
