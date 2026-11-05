package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.vheerden.archi.mcp.response.dto.PendingProposalView;

/**
 * Pins that {@code showLabel} is read by one rule on every tool that places a connection:
 * {@code add-connection-to-view} alone and inside {@code bulk-mutate}, and {@code auto-connect-view}.
 * A boolean, or {@code "true"}/{@code "false"} in any case, is read; anything else is refused as a
 * caller error.
 *
 * <p>The single tools used to take only a JSON boolean, so {@code "false"} was dropped and the label
 * shown; bulk ran any non-blank string through {@link Boolean#parseBoolean}, so {@code "yes"} hid it.
 * Every one of those answered success. The refusal is identified by its own phrase, not by the key:
 * other refusals name {@code showLabel} too.</p>
 *
 * <p>The single tools are driven through their registered handlers, so the pins see what an agent
 * receives; bulk is driven through {@code executeBulk}.</p>
 */
public class ConnectionShowLabelArgumentTest {

    private static final String HANDLER_SESSION = "default";

    /** The phrase only this refusal uses. */
    private static final String REFUSAL = "showLabel must be true or false, got";

    /** One value of each shape the reader refuses: another word, empty, padded, number, array, object. */
    private static final List<Object> UNREADABLE = List.of(
            "yes", "", "TRUE ", 1, List.of(false), Map.of("value", false));

    private ViewContentsFixture f;
    private IArchimateElement a;
    private IArchimateElement b;
    private IDiagramModelArchimateObject vb;
    private int relationships;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-connection-show-label");
        a = f.element("el-a");
        b = f.element("el-b");
        f.object(f.view, "vo-a", a, 40, 40, 120, 60);
        vb = f.object(f.view, "vo-b", b, 400, 40, 120, 60);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- add-connection-to-view, single call ---------------------------------------------------

    @Test
    public void addConnectionToView_shouldRefuseAsACallerError_whenShowLabelIsAnotherWord() {
        String out = add(newRelationship(), "showLabel", "yes");

        assertNothingPlaced(out);
        assertTrue("the value is repeated: " + out, out.contains("the string \\\"yes\\\""));
        assertTrue("the correction says what to pass: " + out,
                out.contains("Pass showLabel: false to hide the relationship name, or true to show it."));
    }

    @Test
    public void addConnectionToView_shouldRefuseEveryValueItCannotRead() {
        IArchimateRelationship rel = newRelationship();
        for (Object value : UNREADABLE) {
            assertNothingPlaced(add(rel, "showLabel", value));
        }
    }

    @Test
    public void addConnectionToView_shouldReadShowLabelAsAStringInAnyCase() {
        assertEquals(false, addAndRead("showLabel", "false").isNameVisible());
        assertEquals(false, addAndRead("showLabel", "FALSE").isNameVisible());
        // The label is shown by default, so these two cannot tell a read from a dropped key; the
        // proposal pin below can.
        assertEquals(true, addAndRead("showLabel", "true").isNameVisible());
        assertEquals(true, addAndRead("showLabel", "True").isNameVisible());
    }

    @Test
    public void addConnectionToView_shouldProposeAStringShowLabelAsABoolean_whenApprovalIsRequired() {
        f.approval = true;

        String out = add(newRelationship(), "showLabel", "True");

        assertFalse("proposed: " + out, out.startsWith("ERROR "));
        List<PendingProposalView> pending = f.dispatcher.listAllPending();
        assertEquals(1, pending.size());
        assertEquals("the proposal carries the value read, not a dropped key",
                Boolean.TRUE, pending.get(0).proposal().proposedChanges().get("showLabel"));
        assertEquals("nothing is placed until approved", 0, vb.getSourceConnections().size());
    }

    @Test
    public void addConnectionToView_shouldKeepTheBooleanReads() {
        assertEquals(false, addAndRead("showLabel", Boolean.FALSE).isNameVisible());
        assertEquals(true, addAndRead("showLabel", Boolean.TRUE).isNameVisible());
    }

    @Test
    public void addConnectionToView_shouldPlaceTheConnectionAsWithoutIt_whenShowLabelIsNullOrAbsent() {
        boolean absent = addAndRead().isNameVisible();
        boolean jsonNull = addAndRead("showLabel", null).isNameVisible();

        assertTrue("the default shows the label", absent);
        assertEquals("JSON null is the default, as an absent key is", absent, jsonNull);
    }

    @Test
    public void addConnectionToView_shouldRefuseBeforeQueueing_whenABatchIsOpen() {
        f.dispatcher.beginBatch(HANDLER_SESSION, "bad showLabel in a batch");
        try {
            assertNothingPlaced(add(newRelationship(), "showLabel", "yes"));
            assertEquals("nothing is queued", Integer.valueOf(0),
                    f.dispatcher.getBatchStatus(HANDLER_SESSION).queuedCount());
        } finally {
            f.dispatcher.endBatch(HANDLER_SESSION, false);
        }
    }

    @Test
    public void addConnectionToView_shouldRefuseBeforeProposing_whenApprovalIsRequired() {
        f.approval = true;

        assertNothingPlaced(add(newRelationship(), "showLabel", "yes"));
        assertTrue("nothing is proposed", f.dispatcher.listAllPending().isEmpty());
    }

    // ---- auto-connect-view ---------------------------------------------------------------------

    @Test
    public void autoConnectView_shouldRefuseEveryValueItCannotRead() {
        newRelationship();
        for (Object value : UNREADABLE) {
            assertNothingPlaced(autoConnect("showLabel", value));
        }
    }

    @Test
    public void autoConnectView_shouldReadShowLabelAsAStringInAnyCase() {
        IArchimateRelationship first = newRelationship();
        assertEquals(false, placedFor(autoConnect("showLabel", "false"), first).isNameVisible());
        IArchimateRelationship second = newRelationship();
        assertEquals(false, placedFor(autoConnect("showLabel", "FALSE"), second).isNameVisible());
        // Shown is the default, so this one only says "True" is not refused.
        IArchimateRelationship third = newRelationship();
        assertEquals(true, placedFor(autoConnect("showLabel", "True"), third).isNameVisible());
    }

    @Test
    public void autoConnectView_shouldConnectAsWithoutIt_whenShowLabelIsNull() {
        IArchimateRelationship rel = newRelationship();

        assertTrue("JSON null is the default, which shows the label",
                placedFor(autoConnect("showLabel", null), rel).isNameVisible());
    }

    @Test
    public void autoConnectView_shouldRefuseBeforeQueueing_whenABatchIsOpen() {
        newRelationship();
        f.dispatcher.beginBatch(HANDLER_SESSION, "bad showLabel in an auto-connect batch");
        try {
            assertNothingPlaced(autoConnect("showLabel", "yes"));
            assertEquals("nothing is queued", Integer.valueOf(0),
                    f.dispatcher.getBatchStatus(HANDLER_SESSION).queuedCount());
        } finally {
            f.dispatcher.endBatch(HANDLER_SESSION, false);
        }
    }

    @Test
    public void autoConnectView_shouldRefuseBeforeProposing_whenApprovalIsRequired() {
        newRelationship();
        f.approval = true;

        assertNothingPlaced(autoConnect("showLabel", "yes"));
        assertTrue("nothing is proposed", f.dispatcher.listAllPending().isEmpty());
    }

    // ---- bulk-mutate ---------------------------------------------------------------------------

    @Test
    public void bulkAddConnectionToView_shouldRefuseOnlyThatOperation_asACallerError() {
        BulkOperationFailure row = onlyRow(bulkRefused(
                new BulkOperation("add-connection-to-view", addParams(newRelationship(), "showLabel", "yes"))));

        assertEquals("add-connection-to-view", row.tool());
        assertTrue(row.message(), row.message().startsWith(REFUSAL));
        assertTrue(row.message(), row.message().contains("the string \"yes\""));
        assertEquals("nothing is placed", 0, vb.getSourceConnections().size());
    }

    @Test
    public void bulkAddConnectionToView_shouldRefuseEveryValueItCannotRead() {
        IArchimateRelationship rel = newRelationship();
        for (Object value : UNREADABLE) {
            BulkOperationFailure row = onlyRow(bulkRefused(
                    new BulkOperation("add-connection-to-view", addParams(rel, "showLabel", value))));
            assertTrue(value + ": " + row.message(), row.message().startsWith(REFUSAL));
        }
        assertEquals("nothing is placed", 0, vb.getSourceConnections().size());
    }

    @Test
    public void bulkAddConnectionToView_shouldDispatchNothing_whenOneOperationIsRefused() {
        BulkOperationFailure row = onlyRow(bulkRefused(
                new BulkOperation("add-connection-to-view", addParams(newRelationship(), "showLabel", false)),
                new BulkOperation("add-connection-to-view", addParams(newRelationship(), "showLabel", "yes"))));

        assertEquals(1, row.index());
        assertEquals("the readable operation is not applied either", 0, vb.getSourceConnections().size());
    }

    @Test
    public void bulk_shouldApplyTheOtherOperations_whenContinueOnErrorIsOn() {
        IArchimateRelationship good = newRelationship();
        BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(
                new BulkOperation("add-connection-to-view", addParams(newRelationship(), "showLabel", "yes")),
                new BulkOperation("add-connection-to-view", addParams(good, "showLabel", "FALSE"))),
                null, true);

        assertFalse(result.allSucceeded());
        assertEquals(1, result.failedOperations().size());
        BulkOperationFailure row = result.failedOperations().get(0);
        assertEquals(0, row.index());
        assertEquals(ErrorCode.INVALID_PARAMETER.name(), row.errorCode());
        assertTrue(row.message(), row.message().startsWith(REFUSAL));
        IDiagramModelConnection placed = only(vb.getSourceConnections());
        assertEquals("the good add applied", good, ((IDiagramModelArchimateConnection) placed).getArchimateConcept());
        assertFalse("and read FALSE as false", placed.isNameVisible());
    }

    @Test
    public void bulkAddConnectionToView_shouldReadShowLabelInAnyCase_andKeepTheOtherReads() {
        // Each value, then whether the label is visible once it is read; null stands for an absent key.
        Object[][] cases = {
                {"false", false}, {"FALSE", false}, {"true", true}, {"True", true},
                {false, false}, {true, true}, {JSON_NULL, true}, {null, true}};
        List<BulkOperation> ops = new ArrayList<>();
        List<IArchimateRelationship> rels = new ArrayList<>();
        for (Object[] c : cases) {
            IArchimateRelationship rel = newRelationship();
            rels.add(rel);
            ops.add(new BulkOperation("add-connection-to-view", c[0] == null ? addParams(rel)
                    : addParams(rel, "showLabel", c[0] == JSON_NULL ? null : c[0])));
        }

        BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION, ops, null, false);

        assertTrue(result.allSucceeded());
        assertEquals(cases.length, vb.getSourceConnections().size());
        for (int i = 0; i < cases.length; i++) {
            assertEquals("showLabel " + cases[i][0], cases[i][1], connectionFor(rels.get(i)).isNameVisible());
        }
    }

    @Test
    public void bulkAddConnectionToView_shouldRefuseBeforeQueueing_whenABatchIsOpen() {
        f.dispatcher.beginBatch(ViewContentsFixture.SESSION, "bad showLabel in a bulk batch");
        try {
            bulkRefused(new BulkOperation("add-connection-to-view", addParams(newRelationship(), "showLabel", "yes")));
            assertEquals("nothing is queued", Integer.valueOf(0),
                    f.dispatcher.getBatchStatus(ViewContentsFixture.SESSION).queuedCount());
        } finally {
            f.dispatcher.endBatch(ViewContentsFixture.SESSION, false);
        }
        assertEquals(0, vb.getSourceConnections().size());
    }

    @Test
    public void bulkAddConnectionToView_shouldRefuseBeforeProposing_whenApprovalIsRequired() {
        f.approval = true;

        bulkRefused(new BulkOperation("add-connection-to-view", addParams(newRelationship(), "showLabel", "yes")));
        assertTrue("nothing is proposed", f.dispatcher.listAllPending().isEmpty());
        assertEquals(0, vb.getSourceConnections().size());
    }

    // ---- harness -------------------------------------------------------------------------------

    /** Stands for an explicit JSON null in a case table where {@code null} means the key is absent. */
    private static final Object JSON_NULL = new Object();

    /** A relationship from el-b to el-a that is not yet on the view; ids rel-1, rel-2, … */
    private IArchimateRelationship newRelationship() {
        return f.relationship("rel-" + (++relationships), b, a);
    }

    private void assertNothingPlaced(String out) {
        assertTrue("refused: " + out, out.startsWith("ERROR "));
        // INTERNAL_ERROR is the only code the handlers log at ERROR, so this also says nothing was.
        assertTrue("as a caller error: " + out, out.contains("\"code\":\"INVALID_PARAMETER\""));
        assertTrue("by this refusal: " + out, out.contains(REFUSAL));
        assertEquals("nothing is placed", 0, vb.getSourceConnections().size());
    }

    /** Adds a connection for a new relationship and returns it, once the call succeeded. */
    private IDiagramModelConnection addAndRead(Object... keysAndValues) {
        IArchimateRelationship rel = newRelationship();
        return placedFor(add(rel, keysAndValues), rel);
    }

    /** The one connection a call that succeeded placed for {@code rel}. */
    private IDiagramModelConnection placedFor(String out, IArchimateRelationship rel) {
        assertFalse("succeeded: " + out, out.startsWith("ERROR "));
        return connectionFor(rel);
    }

    /** The one connection on the view drawing {@code rel}. */
    private IDiagramModelConnection connectionFor(IArchimateRelationship rel) {
        List<IDiagramModelConnection> drawing = vb.getSourceConnections().stream()
                .filter(c -> ((IDiagramModelArchimateConnection) c).getArchimateConcept() == rel)
                .toList();
        assertEquals("one connection for " + rel.getId() + ": " + drawing, 1, drawing.size());
        return drawing.get(0);
    }

    private static IDiagramModelConnection only(List<IDiagramModelConnection> placed) {
        assertEquals("one connection placed: " + placed, 1, placed.size());
        return placed.get(0);
    }

    private String add(IArchimateRelationship rel, Object... keysAndValues) {
        return f.serve("add-connection-to-view", addParams(rel, keysAndValues));
    }

    private String autoConnect(Object... keysAndValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("viewId", f.view.getId());
        return f.serve("auto-connect-view", with(p, keysAndValues));
    }

    private Map<String, Object> addParams(IArchimateRelationship rel, Object... keysAndValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("viewId", f.view.getId());
        p.put("relationshipId", rel.getId());
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

    private BulkValidationException bulkRefused(BulkOperation... ops) {
        try {
            BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(ops), null, false);
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
