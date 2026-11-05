package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.requests.CreateConnectionRequest;
import org.junit.Test;

import com.archimatetool.editor.diagram.commands.CreateDiagramConnectionCommand;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Pins {@link SelfConnectionLoop} against Archi itself rather than against a transcription.
 *
 * <p>The oracle executes the command Archi's palette runs when a connection is drawn from an
 * object to itself, {@code CreateDiagramConnectionCommand.execute()}: it connects the two ends and
 * then runs {@code createBendPointsForCircularConnectionCommand}. Its edit-part selection step is
 * skipped by the command itself when the request names no source edit part, so the command runs
 * here on detached objects with no editor open. Each of the helper's three bendpoint commands
 * inserts at index 0, so the stored order is the reverse of the order the helper lists them in;
 * running Archi's code, not a copy of it, is what keeps that order honest.
 *
 * <p>The boxes cover an even positive box, an odd box at negative coordinates, a box wide enough
 * and a box tall enough for the 0.6 scaling to beat the 100 / 60 floor (171 and 103, both odd, so
 * the {@code (int)} truncation of 102.6 and 61.8 is visible), and the {@code -1} default-size
 * sentinel, which Archi replaces with 100 / 60 before scaling.
 */
public class SelfConnectionLoopTest {

    private static final int[][] BOXES = {
            {100, 100, 120, 60},
            {-37, -11, 121, 61},
            {0, 0, 171, 60},
            {0, 0, 120, 103},
            {10, 20, -1, -1},
    };

    @Test
    public void defaultLoop_shouldEqualWhatArchisPaletteCommandStores() {
        for (int[] b : BOXES) {
            IDiagramModelArchimateObject obj = object(b);
            assertEquals("box " + java.util.Arrays.toString(b), archiLoop(obj),
                    SelfConnectionLoop.defaultLoop(obj));
        }
    }

    @Test
    public void defaultLoop_shouldBeStoredDownThenAcrossThenUp() {
        // Read off Archi's stored file for a 120 x 60 box: down out of the bottom face, across, up
        // into the right face. Written out by hand so the order is visible here, not only derived.
        assertEquals(List.of(new BendpointDto(0, 60, 0, 60), new BendpointDto(100, 60, 100, 60),
                        new BendpointDto(100, 0, 100, 0)),
                SelfConnectionLoop.defaultLoop(120, 60));
        assertEquals(List.of(new BendpointDto(0, 61, 0, 61), new BendpointDto(102, 61, 102, 61),
                        new BendpointDto(102, 0, 102, 0)),
                SelfConnectionLoop.defaultLoop(171, 103));
        assertEquals(SelfConnectionLoop.defaultLoop(100, 60), SelfConnectionLoop.defaultLoop(-1, -1));
    }

    @Test
    public void creationBendpoints_shouldAddTheLoop_whenASelfConnectionCarriesNone() {
        IDiagramModelArchimateObject obj = object(BOXES[1]);
        List<BendpointDto> expected = archiLoop(object(BOXES[1]));
        assertEquals(expected, SelfConnectionLoop.creationBendpoints(null, null, obj, obj));
        assertEquals(expected, SelfConnectionLoop.creationBendpoints(List.of(), List.of(), obj, obj));
    }

    @Test
    public void creationBendpoints_shouldKeepWhatTheCallerSupplied() {
        IDiagramModelArchimateObject obj = object(BOXES[0]);
        List<BendpointDto> supplied = List.of(new BendpointDto(80, -50, 80, -50));
        assertSame(supplied, SelfConnectionLoop.creationBendpoints(supplied, null, obj, obj));
        // absolute bendpoints are converted exactly as before: off the centre (160, 130)
        assertEquals(List.of(new BendpointDto(70, -60, 70, -60)), SelfConnectionLoop.creationBendpoints(
                null, List.of(new AbsoluteBendpointDto(230, 70)), obj, obj));
    }

    @Test
    public void creationBendpoints_shouldNotLoopTwoObjectsWhoseCentresCoincide() {
        IDiagramModelArchimateObject a = object(BOXES[0]);
        IDiagramModelArchimateObject b = object(BOXES[0]);
        assertNull(SelfConnectionLoop.creationBendpoints(null, null, a, b));
        assertEquals(List.of(), SelfConnectionLoop.creationBendpoints(List.of(), null, a, b));
    }

    @Test
    public void withDefaultLoop_shouldLoopOnlyASelfConnection() {
        IDiagramModelArchimateObject obj = object(BOXES[2]);
        IDiagramModelArchimateConnection self = connection();
        assertSame(self, SelfConnectionLoop.withDefaultLoop(self, obj, obj));
        assertEquals(archiLoop(object(BOXES[2])), ConnectionResponseBuilder.collectBendpoints(self));

        IDiagramModelArchimateConnection other = connection();
        SelfConnectionLoop.withDefaultLoop(other, obj, object(BOXES[2]));
        assertTrue(other.getBendpoints().isEmpty());
    }

    @Test
    public void isSelfConnection_shouldCompareTheViewObjectNotItsGeometry() {
        IDiagramModelArchimateObject a = object(BOXES[0]);
        assertTrue(SelfConnectionLoop.isSelfConnection(a, a));
        assertFalse(SelfConnectionLoop.isSelfConnection(a, object(BOXES[0])));
        assertFalse(SelfConnectionLoop.isSelfConnection(null, null));
    }

    @Test
    public void splitForRouting_shouldLeaveANoteSelfConnectionAsStored_andNotClaimItHasBendpoints() {
        // A plain connection from a note to itself: no ArchiMate connection, so no loop command
        com.archimatetool.model.IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setBounds(0, 0, 120, 60);
        IDiagramModelConnection plain = IArchimateFactory.eINSTANCE.createDiagramModelConnection();
        plain.connect(note, note);
        List<String> warnings = new ArrayList<>();
        List<net.vheerden.archi.mcp.response.dto.StructuredWarningDto> structured = new ArrayList<>();

        SelfConnectionLoop.RouteSplit split = SelfConnectionLoop.splitForRouting(List.of(plain),
                DispatchArm.APPLIED, warnings, structured);

        assertTrue(split.routable().isEmpty());
        assertTrue(split.loopCommands().isEmpty());
        assertNull("no loop is written, so the approval card counts none", split.loopedCount());
        assertTrue(plain.getBendpoints().isEmpty());
        String message = structured.get(0).message();
        assertTrue(message, message.endsWith("left exactly as stored: " + plain.getId() + "."));
        assertFalse(message, message.contains("with bendpoints"));
    }

    @Test
    public void splitForRouting_shouldGiveAHeldLoopACommandOnlyOnADeferredArm() {
        for (DispatchArm arm : DispatchArm.values()) {
            IDiagramModelArchimateObject obj = object(BOXES[1]);
            IDiagramModelArchimateConnection held = onView(obj);
            ConnectionResponseBuilder.applyBendpointsToConnection(held, List.of(new BendpointDto(0, -70, 0, -70)));
            IDiagramModelArchimateConnection bare = onView(object(BOXES[0]));

            SelfConnectionLoop.RouteSplit split = SelfConnectionLoop.splitForRouting(List.of(held, bare), arm,
                    new ArrayList<>(), new ArrayList<>());

            // on the applied arm it runs on the model this read, where a held loop's command does nothing
            assertEquals(arm.toString(), arm == DispatchArm.APPLIED ? 1 : 2, split.loopCommands().size());
            assertEquals(arm + ": the response's split is the prepare-time reading", List.of(bare.getId()),
                    split.loopedIds());
            assertTrue(arm + ": a bare one is a write", split.loopsOrRoutes(List.of()));
            SelfConnectionLoop.RouteSplit heldOnly = SelfConnectionLoop.splitForRouting(List.of(held), arm,
                    new ArrayList<>(), new ArrayList<>());
            assertFalse(arm + ": a held loop alone never switches the router", heldOnly.loopsOrRoutes(List.of()));
            assertEquals(arm + ": the card explains the rule only when a command is in the compound",
                    arm != DispatchArm.APPLIED, heldOnly.cardRule() != null);
        }
    }

    @Test
    public void loopCommand_shouldLoopOnlyWhatHoldsNoBendpointsWhenItRuns_andUndoToThatState() {
        IDiagramModelArchimateObject obj = object(BOXES[1]);
        IDiagramModelArchimateConnection conn = onView(obj);
        List<BendpointDto> held = List.of(new BendpointDto(0, -70, 0, -70));
        ConnectionResponseBuilder.applyBendpointsToConnection(conn, held);
        // built while it held a loop, as a deferred prepare would
        SelfConnectionLoop.LoopCommand cmd = new SelfConnectionLoop.LoopCommand(conn);

        cmd.execute();
        assertEquals("holds bendpoints when it runs: untouched", held, ConnectionResponseBuilder.collectBendpoints(conn));
        cmd.undo();
        cmd.redo();
        assertEquals("wrote nothing, so undo and redo do nothing", held,
                ConnectionResponseBuilder.collectBendpoints(conn));

        conn.getBendpoints().clear();
        obj.setBounds(-37, -11, 201, 111);
        cmd.execute();
        assertEquals("cleared before it ran: the loop, from the bounds it reads then",
                archiLoop(object(new int[] {-37, -11, 201, 111})), ConnectionResponseBuilder.collectBendpoints(conn));
        cmd.undo();
        assertEquals("undo restores what it found, not what was held when it was built", List.of(),
                ConnectionResponseBuilder.collectBendpoints(conn));
        cmd.redo();
        assertEquals(archiLoop(object(new int[] {-37, -11, 201, 111})), ConnectionResponseBuilder.collectBendpoints(conn));
    }

    @Test
    public void loopCommand_shouldWriteNothing_onAConnectionNoLongerOnItsView() {
        IDiagramModelArchimateConnection conn = onView(object(BOXES[0]));
        SelfConnectionLoop.LoopCommand cmd = new SelfConnectionLoop.LoopCommand(conn);
        conn.disconnect();
        cmd.execute();
        assertTrue(conn.getBendpoints().isEmpty());
        cmd.undo();
        cmd.redo();
        assertTrue(conn.getBendpoints().isEmpty());
    }

    @Test
    public void loopCommand_shouldWriteNothing_onAConnectionNoLongerFromAnObjectToItself() {
        IDiagramModelArchimateObject obj = object(BOXES[0]);
        IDiagramModelArchimateConnection conn = onView(obj);
        SelfConnectionLoop.LoopCommand cmd = new SelfConnectionLoop.LoopCommand(conn);
        IDiagramModelArchimateObject other = object(BOXES[1]);
        obj.getDiagramModel().getChildren().add(other);
        conn.connect(obj, other);
        cmd.execute();
        assertTrue("re-pointed before it ran: an ordinary connection, given no loop", conn.getBendpoints().isEmpty());
    }

    /** A self-connection on {@code obj}, with {@code obj} on a view, so the connection is attached. */
    private static IDiagramModelArchimateConnection onView(IDiagramModelArchimateObject obj) {
        IArchimateFactory.eINSTANCE.createArchimateDiagramModel().getChildren().add(obj);
        IDiagramModelArchimateConnection conn = connection();
        conn.connect(obj, obj);
        return conn;
    }

    // ---- the oracle: Archi's palette command ----

    /** What Archi stores for a connection drawn in the palette from {@code obj} to itself. */
    static List<BendpointDto> archiLoop(IDiagramModelArchimateObject obj) {
        IDiagramModelArchimateConnection conn = connection();
        CreateDiagramConnectionCommand cmd = new CreateDiagramConnectionCommand(new CreateConnectionRequest()) {
            @Override
            protected IDiagramModelConnection createNewConnection() {
                return conn;
            }
        };
        cmd.setSource(obj);
        cmd.setTarget(obj);
        cmd.execute();
        List<BendpointDto> stored = new ArrayList<>();
        for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
            stored.add(new BendpointDto(bp.getStartX(), bp.getStartY(), bp.getEndX(), bp.getEndY()));
        }
        // the command connected the detached pair; undo it so the object can be reused
        conn.disconnect();
        return stored;
    }

    static IDiagramModelArchimateObject object(int[] b) {
        IDiagramModelArchimateObject obj = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        obj.setArchimateElement(IArchimateFactory.eINSTANCE.createApplicationComponent());
        obj.setBounds(b[0], b[1], b[2], b[3]);
        return obj;
    }

    private static IDiagramModelArchimateConnection connection() {
        IDiagramModelArchimateConnection conn =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(IArchimateFactory.eINSTANCE.createAssociationRelationship());
        return conn;
    }
}
