package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;

/**
 * Pins that an arrangement which widens a cell (a grid column as wide as its widest member) widens
 * only an ordinary box. A box with a loop keeps its own size, so its loop, measured on that size,
 * is drawn where its room was kept; a junction keeps its palette size, as it does everywhere else.
 */
public class LoopRoomArrangementTest {

    private static final int CELL_WIDTH = 400;
    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;

    @Test
    public void shouldWriteALoopedBoxAtItsOwnSize_inAWidenedCell() {
        IDiagramModelArchimateObject looped = box(factory.createApplicationComponent());
        loop(looped);
        NestedLayoutOperations.LoopRoom room = arrangeInWideCells(looped, 120, 55);
        int[] reach = SelfConnectionLoop.reach(looped, 120, 55);
        int[] f = room.footprints().get(0);
        assertArrayEquals(new int[] {f[0] + reach[0], f[1] + reach[1], 120, 55}, room.boxes().get(0));
    }

    @Test
    public void shouldWriteAJunctionAtItsOwnSize_inAWidenedCell() {
        IDiagramModelArchimateObject junction = box(factory.createJunction());
        NestedLayoutOperations.LoopRoom room = arrangeInWideCells(junction, 15, 15);
        int[] f = room.footprints().get(0);
        assertArrayEquals(new int[] {f[0], f[1], 15, 15}, room.boxes().get(0));
    }

    @Test
    public void shouldStillWidenAnOrdinaryBox_toItsCell() {
        IDiagramModelArchimateObject plain = box(factory.createApplicationComponent());
        NestedLayoutOperations.LoopRoom room = arrangeInWideCells(plain, 120, 55);
        assertEquals(CELL_WIDTH, room.boxes().get(0)[2]);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Arranges one child in a cell {@link #CELL_WIDTH} wide, whatever its footprint. */
    private static NestedLayoutOperations.LoopRoom arrangeInWideCells(IDiagramModelObject child, int w, int h) {
        return NestedLayoutOperations.arrangeAroundLoops(List.of(child), List.of(new int[] {w, h}), footprints -> {
            List<int[]> cells = new ArrayList<>();
            for (int[] size : footprints) {
                cells.add(new int[] {20, 30, CELL_WIDTH, size[1]});
            }
            return cells;
        });
    }

    private IDiagramModelArchimateObject box(IArchimateElement element) {
        IDiagramModelArchimateObject object = factory.createDiagramModelArchimateObject();
        object.setArchimateElement(element);
        return object;
    }

    /** A self-connection stored without bendpoints, which Archi draws with its default loop. */
    private void loop(IDiagramModelArchimateObject object) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.connect(object.getArchimateElement(), object.getArchimateElement());
        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(rel);
        conn.connect(object, object);
    }
}
