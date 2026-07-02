package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;

/**
 * Pure-JUnit tests for {@link AnchorResolver}: the edge geometry, anchor merge, post-anchor
 * DTO computation, and the commit-time cascade (grow a target ⇒ anchored child moves, un-anchored
 * child does not). Uses real EMF diagram objects but no OSGi/dispatcher.
 */
public class AnchorResolverTest {

    // ---- edge geometry (integer) --------------------------------------------------------------

    @Test
    public void shouldResolveBelowEdge_trackingTheGrowingBottom() {
        // target at (100,100) size 200x50; child 180x30; gap dy=10
        int[] p = AnchorResolver.resolveByEdge("below", 100, 100, 200, 50, 180, 30, 0, 10);
        assertArrayEquals(new int[] { 100, 160 }, p);
        // grow target height 50 -> 120: child follows the bottom
        int[] grown = AnchorResolver.resolveByEdge("below", 100, 100, 200, 120, 180, 30, 0, 10);
        assertArrayEquals(new int[] { 100, 230 }, grown);
    }

    @Test
    public void shouldResolveAboveEdge_placingChildBottomAboveTarget() {
        int[] p = AnchorResolver.resolveByEdge("above", 100, 100, 200, 50, 180, 30, 0, 10);
        // x = 100 + 0; y = 100 - childHeight(30) - dy(10) = 60
        assertArrayEquals(new int[] { 100, 60 }, p);
    }

    @Test
    public void shouldResolveRightEdge() {
        int[] p = AnchorResolver.resolveByEdge("right", 100, 100, 200, 50, 40, 30, 5, 0);
        // x = 100 + 200 + 5 = 305; y = 100 + 0
        assertArrayEquals(new int[] { 305, 100 }, p);
    }

    @Test
    public void shouldResolveLeftEdge() {
        int[] p = AnchorResolver.resolveByEdge("left", 100, 100, 200, 50, 40, 30, 5, 0);
        // x = 100 - childWidth(40) - dx(5) = 55; y = 100
        assertArrayEquals(new int[] { 55, 100 }, p);
    }

    @Test
    public void shouldDefaultToBelow_whenEdgeNullOrEmpty() {
        int[] a = AnchorResolver.resolveByEdge(null, 0, 0, 10, 10, 5, 5, 0, 0);
        int[] b = AnchorResolver.resolveByEdge("", 0, 0, 10, 10, 5, 5, 0, 0);
        assertArrayEquals(new int[] { 0, 10 }, a);
        assertArrayEquals(new int[] { 0, 10 }, b);
    }

    @Test
    public void shouldRoundDoubleOverload_identicallyToSingleSumRounding() {
        // Byte-identical to (int) Math.round(y + height + gap): round(10.4 + 20.4 + 10) = round(40.8) = 41
        int[] below = AnchorResolver.resolveByEdge("below", 10.4, 10.4, 20.4, 20.4, 0, 0, 0, 10);
        assertEquals(41, below[1]);
        assertEquals(10, below[0]); // round(10.4)
    }

    @Test
    public void shouldValidateEdges() {
        assertTrue(AnchorResolver.isValidEdge(null));
        assertTrue(AnchorResolver.isValidEdge(""));
        assertTrue(AnchorResolver.isValidEdge("below"));
        assertTrue(AnchorResolver.isValidEdge("above"));
        assertTrue(AnchorResolver.isValidEdge("right"));
        assertTrue(AnchorResolver.isValidEdge("left"));
        assertFalse(AnchorResolver.isValidEdge("diagonal"));
        assertFalse(AnchorResolver.isValidEdge("BELOW"));
    }

    // ---- diagram fixtures ---------------------------------------------------------------------

    private static IArchimateDiagramModel newDiagram() {
        return IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
    }

    private static IDiagramModelGroup group(String id, int x, int y, int w, int h) {
        IDiagramModelGroup g = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        g.setId(id);
        g.setBounds(x, y, w, h);
        return g;
    }

    private static IDiagramModelNote note(String id, int x, int y, int w, int h) {
        IDiagramModelNote n = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        n.setId(id);
        n.setBounds(x, y, w, h);
        return n;
    }

    // ---- mergeBounds (anchor-on-set resolution) -----------------------------------------------

    @Test
    public void mergeBounds_shouldResolveChildPositionFromTargetWhenAnchorSet() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup target = group("t1", 100, 100, 200, 50);
        IDiagramModelNote child = note("c1", 0, 0, 180, 30);
        diagram.getChildren().add(target);
        diagram.getChildren().add(child);

        int[] merged = AnchorResolver.mergeBounds(child, null, null, null, null,
                "t1", "below", 0, 10);
        // below: x=100, y=100+50+10=160; width/height keep current (180x30)
        assertArrayEquals(new int[] { 100, 160, 180, 30 }, merged);
    }

    @Test(expected = ModelAccessException.class)
    public void mergeBounds_shouldRejectCrossCoordinateSpaceAnchor() {
        // child lives inside a group (group-relative coords); target is top-level (absolute) —
        // resolving across spaces would write the wrong numbers, so it must be rejected.
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("g", 0, 0, 300, 200);
        IDiagramModelNote child = note("c1", 10, 5, 100, 20);
        g.getChildren().add(child);
        IDiagramModelGroup target = group("t1", 400, 400, 100, 80);
        diagram.getChildren().add(g);
        diagram.getChildren().add(target);

        AnchorResolver.mergeBounds(child, null, null, null, null, "t1", "below", 0, 10);
    }

    @Test(expected = ModelAccessException.class)
    public void mergeBounds_shouldRejectSelfAnchor() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelNote child = note("c1", 10, 5, 100, 20);
        diagram.getChildren().add(child);
        AnchorResolver.mergeBounds(child, null, null, null, null, "c1", "below", 0, 10);
    }

    @Test
    public void mergeBounds_shouldResolveWhenBothInSameGroup() {
        // Same-space (both children of the same group) is allowed.
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("g", 0, 0, 400, 300);
        IDiagramModelGroup target = group("t1", 100, 100, 200, 50); // group-relative
        IDiagramModelNote child = note("c1", 0, 0, 180, 30);
        g.getChildren().add(target);
        g.getChildren().add(child);
        diagram.getChildren().add(g);

        int[] merged = AnchorResolver.mergeBounds(child, null, null, null, null, "t1", "below", 0, 10);
        assertArrayEquals(new int[] { 100, 160, 180, 30 }, merged);
    }

    @Test
    public void mergeBounds_shouldMergeNormallyWhenNoAnchor() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelNote child = note("c1", 5, 6, 180, 30);
        diagram.getChildren().add(child);
        int[] merged = AnchorResolver.mergeBounds(child, 50, null, null, 90, null, null, null, null);
        // x overridden to 50, y keeps 6, width keeps 180 (null), height overridden to 90
        assertArrayEquals(new int[] { 50, 6, 180, 90 }, merged);
    }

    // ---- computePostAnchor --------------------------------------------------------------------

    @Test
    public void computePostAnchor_shouldEchoSetValues() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelNote child = note("c1", 0, 0, 10, 10);
        diagram.getChildren().add(child);
        AnchorResolver.AnchorInfo info = AnchorResolver.computePostAnchor(child, "t1", null, null, 12);
        assertEquals("t1", info.target());
        assertEquals("below", info.edge());
        assertEquals(Integer.valueOf(0), info.dx());
        assertEquals(Integer.valueOf(12), info.dy());
    }

    @Test
    public void computePostAnchor_shouldReportNullWhenClearing() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelNote child = note("c1", 0, 0, 10, 10);
        child.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, "t1");
        diagram.getChildren().add(child);
        AnchorResolver.AnchorInfo info = AnchorResolver.computePostAnchor(child, "", null, null, null);
        assertNull(info.target());
        assertNull(info.edge());
    }

    @Test
    public void computePostAnchor_shouldReflectExistingWhenNotTouched() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelNote child = note("c1", 0, 0, 10, 10);
        child.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, "t1");
        child.getFeatures().putString(AnchorResolver.ANCHOR_EDGE_FEATURE, "right");
        child.getFeatures().putString(AnchorResolver.ANCHOR_DX_FEATURE, "7");
        child.getFeatures().putString(AnchorResolver.ANCHOR_DY_FEATURE, "0");
        diagram.getChildren().add(child);
        AnchorResolver.AnchorInfo info = AnchorResolver.computePostAnchor(child, null, null, null, null);
        assertEquals("t1", info.target());
        assertEquals("right", info.edge());
        assertEquals(Integer.valueOf(7), info.dx());
    }

    @Test
    public void computePostAnchor_shouldReportAllNullWhenUnanchored() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelNote child = note("c1", 0, 0, 10, 10);
        diagram.getChildren().add(child);
        AnchorResolver.AnchorInfo info = AnchorResolver.computePostAnchor(child, null, null, null, null);
        assertNull(info.target());
    }

    // ---- the commit-time cascade: RED-ON-REVERT -----------------------------------------------

    @Test
    public void wrapAnchoredChildren_shouldMoveAnchoredChild_butNotUnanchored_whenTargetGrows() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup target = group("t1", 100, 100, 200, 50);
        IDiagramModelNote anchored = note("c1", 100, 160, 180, 30); // already below at height 50
        IDiagramModelNote free = note("c2", 500, 500, 100, 20);
        anchored.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, "t1");
        anchored.getFeatures().putString(AnchorResolver.ANCHOR_EDGE_FEATURE, "below");
        anchored.getFeatures().putString(AnchorResolver.ANCHOR_DX_FEATURE, "0");
        anchored.getFeatures().putString(AnchorResolver.ANCHOR_DY_FEATURE, "10");
        diagram.getChildren().add(target);
        diagram.getChildren().add(anchored);
        diagram.getChildren().add(free);

        // Grow the target's height 50 -> 120 and cascade.
        Command base = new UpdateViewObjectCommand(target, 100, 100, 200, 120);
        Command wrapped = AnchorResolver.wrapAnchoredChildren(base, target, 100, 100, 200, 120, true);
        // The wrap bundles the target grow + one child reposition into a single compound (undo unit).
        assertTrue("expected a compound when an anchored child exists", wrapped instanceof CompoundCommand);
        List<?> members = ((CompoundCommand) wrapped).getCommands();
        assertEquals(2, members.size());
        executeAll(members);

        // Anchored child tracked the growing bottom: y = 100 + 120 + 10 = 230.
        assertEquals(100, anchored.getBounds().getX());
        assertEquals(230, anchored.getBounds().getY());
        // Un-anchored child did not move.
        assertEquals(500, free.getBounds().getX());
        assertEquals(500, free.getBounds().getY());

        // Undo restores both the target and the anchored child (single undo unit).
        undoAll(members);
        assertEquals(50, target.getBounds().getHeight());
        assertEquals(160, anchored.getBounds().getY());
        assertEquals(500, free.getBounds().getY());
    }

    // Execute the compound's members directly: NonNotifyingCompoundCommand.execute() statically
    // initializes Archi's EditorModelManager, which is unavailable in the headless harness; the
    // individual UpdateViewObjectCommands are headless-safe and are what the compound runs internally.
    private static void executeAll(List<?> members) {
        for (Object c : members) {
            ((Command) c).execute();
        }
    }

    private static void undoAll(List<?> members) {
        for (int i = members.size() - 1; i >= 0; i--) {
            ((Command) members.get(i)).undo();
        }
    }

    @Test
    public void wrapAnchoredChildren_shouldReturnBaseUnchanged_whenBoundsNotModified() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup target = group("t1", 100, 100, 200, 50);
        diagram.getChildren().add(target);
        Command base = new UpdateViewObjectCommand(target, 100, 100, 200, 50);
        Command result = AnchorResolver.wrapAnchoredChildren(base, target, 100, 100, 200, 50, false);
        assertTrue("no-op guard must return the same command instance", base == result);
    }

    @Test
    public void wrapAnchoredChildren_shouldSkipChildInDifferentCoordinateSpace() {
        // A stale/hand-edited anchor pointing across coordinate spaces must not be repositioned.
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup target = group("t1", 100, 100, 200, 50); // top-level
        IDiagramModelGroup g = group("g", 500, 0, 300, 200);
        IDiagramModelNote strayChild = note("c1", 10, 5, 100, 20);   // inside group g
        strayChild.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, "t1");
        strayChild.getFeatures().putString(AnchorResolver.ANCHOR_EDGE_FEATURE, "below");
        g.getChildren().add(strayChild);
        diagram.getChildren().add(target);
        diagram.getChildren().add(g);

        Command base = new UpdateViewObjectCommand(target, 100, 100, 200, 120);
        Command result = AnchorResolver.wrapAnchoredChildren(base, target, 100, 100, 200, 120, true);
        assertTrue("cross-space anchored child must be skipped", base == result);
    }

    @Test
    public void wrapAnchoredChildren_shouldNotDoubleMove_onSelfAnchor() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup target = group("t1", 100, 100, 200, 50);
        target.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, "t1"); // anchored to itself
        target.getFeatures().putString(AnchorResolver.ANCHOR_EDGE_FEATURE, "below");
        diagram.getChildren().add(target);

        Command base = new UpdateViewObjectCommand(target, 100, 100, 200, 120);
        Command result = AnchorResolver.wrapAnchoredChildren(base, target, 100, 100, 200, 120, true);
        assertTrue("self-anchor must not spawn a second move", base == result);
    }

    @Test
    public void wrapAnchoredChildren_shouldReturnBaseUnchanged_whenNoAnchoredChildren() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup target = group("t1", 100, 100, 200, 50);
        IDiagramModelNote free = note("c2", 500, 500, 100, 20);
        diagram.getChildren().add(target);
        diagram.getChildren().add(free);
        Command base = new UpdateViewObjectCommand(target, 100, 100, 200, 120);
        Command result = AnchorResolver.wrapAnchoredChildren(base, target, 100, 100, 200, 120, true);
        assertTrue(base == result);
    }
}
