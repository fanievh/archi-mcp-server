package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;

/**
 * Pins that {@code update-view-connection} names an end that is another connection by that
 * connection's id, on the single call, inside a batch and through {@code bulk-mutate}, exactly as
 * {@code get-view-contents} names it.
 *
 * <p>The update paths used to take each end's id from a cast to an ArchiMate view object, so an end
 * on a connection came back null while the read named it. The geometry is a separate matter: when
 * either end is not a figure, neither end has an anchor or a render face and the absolute bendpoints
 * are omitted, and that abstention is pinned here unchanged.</p>
 */
public class UpdateViewConnectionConnectionEndTest {

    private static final int TEXT_POSITION = IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE;

    private ViewContentsFixture f;
    private IDiagramModelArchimateObject c;
    private IArchimateElement elC;
    private IArchimateRelationship relAb;
    private IDiagramModelArchimateConnection ab;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-update-connection-end");
        IArchimateElement elA = f.element("el-a");
        IArchimateElement elB = f.element("el-b");
        elC = f.element("el-c");
        IDiagramModelArchimateObject a = f.object(f.view, "vo-a", elA, 40, 40, 120, 60);
        IDiagramModelArchimateObject b = f.object(f.view, "vo-b", elB, 400, 40, 120, 60);
        c = f.object(f.view, "vo-c", elC, 220, 300, 120, 60);
        relAb = f.relationship("rel-ab", elA, elB);
        ab = f.archimateConnection("conn-ab", relAb, a, b);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- the single call ---------------------------------------------------------------------------

    @Test
    public void updateViewConnection_shouldNameTheConnectionEnd_whenTheTargetIsAConnection() {
        toConnection();

        ViewConnectionDto dto = single("conn-c-ab");

        assertEquals("vo-c", dto.sourceViewObjectId());
        assertEquals("conn-ab", dto.targetViewObjectId());
        assertNoGeometry(dto);
    }

    @Test
    public void updateViewConnection_shouldNameTheConnectionEnd_whenTheSourceIsAConnection() {
        fromConnection();

        ViewConnectionDto dto = single("conn-ab-c");

        assertEquals("conn-ab", dto.sourceViewObjectId());
        assertEquals("vo-c", dto.targetViewObjectId());
        assertNoGeometry(dto);
    }

    @Test
    public void updateViewConnection_shouldNameTheConnectionEndInThePreview_whenABatchIsOpen() {
        toConnection();
        fromConnection();

        ViewConnectionDto toConn = batched("conn-c-ab");
        ViewConnectionDto fromConn = batched("conn-ab-c");

        assertEquals("vo-c>conn-ab", ends(toConn));
        assertEquals("conn-ab>vo-c", ends(fromConn));
        assertNoGeometry(toConn);
        assertNoGeometry(fromConn);
    }

    // ---- bulk-mutate -------------------------------------------------------------------------------

    @Test
    public void bulkUpdateViewConnection_shouldNameTheConnectionEnd_whenTheTargetIsAConnection() {
        toConnection();

        ViewConnectionDto dto = bulk("conn-c-ab");

        assertEquals("vo-c", dto.sourceViewObjectId());
        assertEquals("conn-ab", dto.targetViewObjectId());
        assertNoGeometry(dto);
    }

    @Test
    public void bulkUpdateViewConnection_shouldNameTheConnectionEnd_whenTheSourceIsAConnection() {
        fromConnection();

        ViewConnectionDto dto = bulk("conn-ab-c");

        assertEquals("conn-ab", dto.sourceViewObjectId());
        assertEquals("vo-c", dto.targetViewObjectId());
        assertNoGeometry(dto);
    }

    @Test
    public void bulkUpdateViewConnection_shouldNameBothEnds_whenTheSameCallAddedTheConnection() {
        IArchimateElement elD = f.element("el-d");
        f.object(f.view, "vo-d", elD, 520, 300, 120, 60);
        f.relationship("rel-c-d", elC, elD);

        BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(
                new BulkOperation("add-connection-to-view", Map.of("viewId", f.view.getId(),
                        "relationshipId", "rel-c-d", "sourceViewObjectId", "vo-c", "targetViewObjectId", "vo-d")),
                new BulkOperation("update-view-connection",
                        Map.of("viewConnectionId", "$0.id", "labelPosition", "source"))), null, false);

        assertTrue("bulk must apply both operations", result.allSucceeded());
        assertEquals("reported after the call ran, so the connection has its ends by then",
                "vo-c>vo-d", ends(result.operations().get(1).effectiveConnection()));
    }

    // ---- both ends on connections ------------------------------------------------------------------

    @Test
    public void updateViewConnection_shouldNameBothConnectionEnds_whenEachEndIsAConnection() {
        IDiagramModelArchimateConnection cab = toConnection();
        f.archimateConnection("conn-ab-cab", f.relationship("rel-ab-cab", relAb, cab.getArchimateRelationship()),
                ab, cab).getBendpoints().add(f.bendpoint(0, -100, 0, 100));

        assertEquals("conn-ab>conn-c-ab", ends(read("conn-ab-cab")));
        assertSameEnds("conn-ab-cab");
        assertNoGeometry(single("conn-ab-cab"));
        assertNoGeometry(bulk("conn-ab-cab"));
    }

    // ---- agreement with get-view-contents ----------------------------------------------------------

    @Test
    public void updateViewConnection_shouldNameBothEndsAsGetViewContentsDoes_whenTheTargetIsAConnection() {
        toConnection();

        assertSameEnds("conn-c-ab");
    }

    @Test
    public void updateViewConnection_shouldNameBothEndsAsGetViewContentsDoes_whenTheSourceIsAConnection() {
        fromConnection();

        assertSameEnds("conn-ab-c");
    }

    /** Every path that reports the connection names its ends as the read does. */
    private void assertSameEnds(String connId) {
        String read = ends(read(connId));
        assertEquals("single call", read, ends(single(connId)));
        assertEquals("bulk-mutate", read, ends(bulk(connId)));
        assertEquals("batch preview", read, ends(batched(connId)));
    }

    /**
     * Neither end is anchored, faced or converted when one end is a connection, and the stored
     * bendpoint is still reported as stored.
     */
    private static void assertNoGeometry(ViewConnectionDto dto) {
        assertNull(dto.sourceAnchor());
        assertNull(dto.targetAnchor());
        assertNull(dto.sourceRenderFace());
        assertNull(dto.targetRenderFace());
        assertNull(dto.absoluteBendpoints());
        assertEquals(List.of(new BendpointDto(0, -100, 0, 100)), dto.bendpoints());
        assertEquals("the update itself was applied or projected", Integer.valueOf(TEXT_POSITION), dto.textPosition());
    }

    private static String ends(ViewConnectionDto dto) {
        return dto.sourceViewObjectId() + ">" + dto.targetViewObjectId();
    }

    // ---- harness -----------------------------------------------------------------------------------

    /** vo-c → conn-ab, with one stored bendpoint so an absolute conversion would have something to show. */
    private IDiagramModelArchimateConnection toConnection() {
        IDiagramModelArchimateConnection conn =
                f.archimateConnection("conn-c-ab", f.relationship("rel-c-ab", elC, relAb), c, ab);
        conn.getBendpoints().add(f.bendpoint(0, -100, 0, 100));
        return conn;
    }

    /** conn-ab → vo-c, likewise with one stored bendpoint. */
    private void fromConnection() {
        f.archimateConnection("conn-ab-c", f.relationship("rel-ab-c", relAb, elC), ab, c)
                .getBendpoints().add(f.bendpoint(0, -100, 0, 100));
    }

    private ViewConnectionDto single(String connId) {
        MutationResult<ViewConnectionDto> result = f.accessor.updateViewConnection(
                ViewContentsFixture.SESSION, connId, null, null, null, null, TEXT_POSITION);
        return result.entity();
    }

    private ViewConnectionDto bulk(String connId) {
        BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(
                new BulkOperation("update-view-connection",
                        Map.of("viewConnectionId", connId, "labelPosition", "source"))), null, false);
        assertTrue("bulk must apply the update", result.allSucceeded());
        assertEquals(1, result.operations().size());
        return result.operations().get(0).effectiveConnection();
    }

    private ViewConnectionDto batched(String connId) {
        f.dispatcher.beginBatch(ViewContentsFixture.SESSION, "connection end in preview");
        try {
            MutationResult<ViewConnectionDto> result = f.accessor.updateViewConnection(
                    ViewContentsFixture.SESSION, connId, null, null, null, null, TEXT_POSITION);
            assertTrue("the update is queued, so its entity is a preview", result.isBatched());
            return result.entity();
        } finally {
            f.dispatcher.endBatch(ViewContentsFixture.SESSION, false);
        }
    }

    private ViewConnectionDto read(String connId) {
        for (ViewConnectionDto row : f.accessor.getViewContents(f.view.getId()).orElseThrow().connections()) {
            if (row.viewConnectionId().equals(connId)) {
                return row;
            }
        }
        throw new AssertionError("no connection " + connId);
    }
}
