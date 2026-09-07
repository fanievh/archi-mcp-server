package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;

import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;

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

    // ---- same-batch pending state shared by both update-view-object prepare paths ----

    @Test
    public void seedPending_shouldReturnEmptyMap_whenNotInABatch() {
        Map<String, int[]> seeded = AnchorResolver.seedPending(null);
        assertTrue("outside a batch the fit must start unseeded", seeded.isEmpty());
    }

    @Test
    public void seedPending_shouldCopyEntries_withoutAliasingTheBatchMap() {
        Map<String, int[]> pending = new LinkedHashMap<>();
        pending.put("g1", new int[] { 0, 0, 300, 800 });
        Map<String, int[]> seeded = AnchorResolver.seedPending(pending);

        assertArrayEquals(new int[] { 0, 0, 300, 800 }, seeded.get("g1"));
        seeded.put("g2", new int[] { 1, 1, 1, 1 });
        assertNull("the working copy must not write through to the batch map", pending.get("g2"));
    }

    @Test
    public void foldPending_shouldPublishCascadeBounds_andTolerateNulls() {
        Map<String, int[]> pending = new LinkedHashMap<>();
        Map<String, int[]> cascade = new LinkedHashMap<>();
        cascade.put("g1", new int[] { 100, 100, 300, 510 });

        AnchorResolver.foldPending(null, cascade);          // outside a batch
        AnchorResolver.foldPending(pending, null);          // no cascade ran
        assertTrue(pending.isEmpty());

        AnchorResolver.foldPending(pending, cascade);
        assertArrayEquals("a cascade grow must be visible to later ops",
                new int[] { 100, 100, 300, 510 }, pending.get("g1"));
    }

    /**
     * The two-argument overload is the one both update prepares actually call, and its whole reason
     * for existing is the ordering: an open batch's queued geometry underneath, the in-flight bulk
     * pass's own pending bounds on top, so the later writer wins on an object both know about.
     * Only the one-argument overload was pinned, which left that ordering — the property the
     * overload exists for — unguarded.
     */
    @Test
    public void seedPending_shouldLayerBulkOverQueued_whenBothSourcesKnowTheSameObject() {
        Map<String, int[]> queued = new LinkedHashMap<>();
        queued.put("g1", new int[] { 0, 0, 300, 400 });
        queued.put("g2", new int[] { 5, 5, 50, 50 });
        Map<String, int[]> bulkPending = new LinkedHashMap<>();
        bulkPending.put("g1", new int[] { 0, 0, 300, 900 });

        Map<String, int[]> seeded = AnchorResolver.seedPending(queued, bulkPending);

        assertArrayEquals("the bulk pass writes last and must win on a shared object",
                new int[] { 0, 0, 300, 900 }, seeded.get("g1"));
        assertArrayEquals("an object only the batch knows must survive the layering",
                new int[] { 5, 5, 50, 50 }, seeded.get("g2"));

        seeded.put("g3", new int[] { 1, 1, 1, 1 });
        assertNull("the working copy must not write through to the batch map", queued.get("g3"));
        assertNull("nor to the bulk map", bulkPending.get("g3"));
    }

    @Test
    public void seedPending_shouldReturnAnEmptyMap_whenBothSourcesAreNull() {
        assertTrue("the ordinary single-tool call must start unseeded",
                AnchorResolver.seedPending(null, null).isEmpty());
    }

    /**
     * The write-back half of the accumulator: what a prepare owes the shared map once it has
     * finished. Both halves matter and they are separable — the cascade's grown groups are folded
     * unconditionally, the object's OWN effective rectangle only when the call actually moved it.
     * Recording an unmoved object would publish a floor no command is going to establish.
     */
    @Test
    public void recordEffective_shouldFoldTheCascadeAndRecordTheObject_whenBoundsChanged() {
        Map<String, int[]> pending = new LinkedHashMap<>();
        Map<String, int[]> cascade = new LinkedHashMap<>();
        cascade.put("g1", new int[] { 100, 100, 300, 510 });

        AnchorResolver.recordEffective(pending, cascade, "v1", true, 40, 50, 120, 60);

        assertArrayEquals("the cascade's grown group must reach the shared map",
                new int[] { 100, 100, 300, 510 }, pending.get("g1"));
        assertArrayEquals("a moved object must record its own effective rectangle",
                new int[] { 40, 50, 120, 60 }, pending.get("v1"));
    }

    @Test
    public void recordEffective_shouldFoldTheCascadeButNotTheObject_whenBoundsWereNotModified() {
        Map<String, int[]> pending = new LinkedHashMap<>();
        Map<String, int[]> cascade = new LinkedHashMap<>();
        cascade.put("g1", new int[] { 100, 100, 300, 510 });

        AnchorResolver.recordEffective(pending, cascade, "v1", false, 40, 50, 120, 60);

        assertArrayEquals("the cascade still folds — it ran regardless",
                new int[] { 100, 100, 300, 510 }, pending.get("g1"));
        assertNull("an object this call did not move must leave no floor behind",
                pending.get("v1"));
    }

    @Test
    public void recordEffective_shouldBeANoOp_whenThereIsNoSharedMap() {
        Map<String, int[]> cascade = new LinkedHashMap<>();
        cascade.put("g1", new int[] { 100, 100, 300, 510 });

        AnchorResolver.recordEffective(null, cascade, "v1", true, 40, 50, 120, 60);
        AnchorResolver.recordEffective(null, null, "v1", true, 40, 50, 120, 60);

        // "Did not throw" is not an assertion. With no map to fold INTO, the only observable state
        // left is the cascade map itself, so that is what must be shown untouched -- a no-op that
        // quietly wrote its argument the other way round would otherwise pass this test.
        assertEquals("the cascade map must not gain entries when there is nowhere to fold them",
                1, cascade.size());
        assertArrayEquals("nor have its own entry rewritten",
                new int[] { 100, 100, 300, 510 }, cascade.get("g1"));
        assertNull("and the recorded object must not be invented in the cascade map",
                cascade.get("v1"));
    }

    /**
     * The maps are shallow copies: {@code seedPending} does a {@code putAll}, so a value array in
     * the seeded map is the SAME {@code int[]} instance the source map holds. Every writer is
     * therefore obliged to REPLACE the array rather than mutate it in place, or a working copy
     * would write through into the batch's own queued geometry. That obligation was carried only by
     * a comment; this pins it on the two writers a pass actually uses.
     */
    @Test
    public void recordEffective_shouldReplaceTheBoundsArray_notMutateTheSourcesInPlace() {
        int[] sourceArray = new int[] { 0, 0, 300, 400 };
        Map<String, int[]> queued = new LinkedHashMap<>();
        queued.put("g1", sourceArray);

        Map<String, int[]> working = AnchorResolver.seedPending(queued, null);
        assertSame("precondition: the seed really is a shallow copy, or this pin proves nothing",
                sourceArray, working.get("g1"));

        // NO cascade entry for g1. A cascade that grew the same group would fold a fresh array in
        // first and de-alias the key, so the write-through path would never be reached and this pin
        // would pass against the very mutation it exists to catch. The object's own record is the
        // writer that meets the still-aliased array.
        AnchorResolver.recordEffective(working, null, "g1", true, 0, 0, 300, 900);

        assertArrayEquals("the batch's own queued geometry must not be written through",
                new int[] { 0, 0, 300, 400 }, sourceArray);
        assertArrayEquals("while the working copy does carry the new value",
                new int[] { 0, 0, 300, 900 }, working.get("g1"));
        assertNotSame("the working copy must hold a REPLACEMENT array, not the source's",
                sourceArray, working.get("g1"));
    }

    @Test
    public void pendingParent_shouldReturnNull_outsideABatchAndForUnknownObjects() {
        assertNull(AnchorResolver.pendingParent(null, "v1"));
        assertNull(AnchorResolver.pendingParent(new LinkedHashMap<>(), "v1"));
    }

    @Test
    public void recordPendingParent_shouldStoreTheContainer_andIgnoreNulls() {
        Map<String, IDiagramModelContainer> parents = new LinkedHashMap<>();
        IDiagramModelGroup g = group("g1", 0, 0, 300, 300);

        AnchorResolver.recordPendingParent(null, "v1", g);      // non-bulk: no map, no crash
        AnchorResolver.recordPendingParent(parents, "v1", null); // unresolved parent: nothing stored
        assertTrue(parents.isEmpty());

        AnchorResolver.recordPendingParent(parents, "v1", g);
        assertTrue(g == AnchorResolver.pendingParent(parents, "v1"));
    }

    @Test
    public void wrapWithGroupResizes_shouldReturnBaseUnchanged_whenNoResizeNeeded() {
        IDiagramModelGroup target = group("t1", 100, 100, 200, 50);
        Command base = new UpdateViewObjectCommand(target, 100, 100, 200, 120);
        assertTrue("a fit that needed no resize must not allocate a compound",
                base == AnchorResolver.wrapWithGroupResizes(base, new LinkedHashMap<>()));
    }

    @Test
    public void wrapWithGroupResizes_shouldBundleBaseAndEveryResize_intoOneUndoUnit() {
        IDiagramModelGroup target = group("t1", 100, 100, 200, 50);
        IDiagramModelGroup parent = group("p1", 0, 0, 300, 300);
        IDiagramModelGroup grandparent = group("gp1", 0, 0, 400, 400);
        Command base = new UpdateViewObjectCommand(target, 100, 100, 200, 120);

        Map<String, Command> resizes = new LinkedHashMap<>();
        resizes.put("p1", new UpdateViewObjectCommand(parent, 0, 0, 300, 510));
        resizes.put("gp1", new UpdateViewObjectCommand(grandparent, 0, 0, 400, 620));

        Command wrapped = AnchorResolver.wrapWithGroupResizes(base, resizes);
        assertTrue(wrapped instanceof CompoundCommand);
        List<?> members = ((CompoundCommand) wrapped).getCommands();
        assertEquals("base plus one command per grown group", 3, members.size());
        assertTrue("the caller's own command must execute first", base == members.get(0));
    }

    @Test
    public void wrapWithGroupResizes_shouldCarryTheCallersLabel_whenOneIsSupplied() {
        IDiagramModelGroup target = group("t1", 100, 100, 200, 50);
        IDiagramModelGroup parent = group("p1", 0, 0, 300, 300);
        Command base = new UpdateViewObjectCommand(target, 100, 100, 200, 120);
        Map<String, Command> resizes = new LinkedHashMap<>();
        resizes.put("p1", new UpdateViewObjectCommand(parent, 0, 0, 300, 510));

        Command wrapped = AnchorResolver.wrapWithGroupResizes(base, resizes, "Custom cascade label");
        assertEquals("the undo entry must be named for the lever that provoked the growth",
                "Custom cascade label", ((CompoundCommand) wrapped).getLabel());
        assertTrue("base still executes first", base == ((CompoundCommand) wrapped).getCommands().get(0));
        assertTrue("an empty map still short-circuits on the labelled overload",
                base == AnchorResolver.wrapWithGroupResizes(base, new LinkedHashMap<>(), "Custom cascade label"));
    }

    @Test
    public void wrapWithIconBandResize_shouldReserveTheCornerBeforeTheChildLands() {
        IDiagramModelGroup parent = group("p1", 0, 0, 300, 300);
        IDiagramModelGroup child = group("c1", 10, 10, 100, 100);
        Command add = new UpdateViewObjectCommand(child, 10, 10, 100, 100);
        Command parentResize = new UpdateViewObjectCommand(parent, 0, 0, 300, 324);

        assertTrue("no reservation needed means no compound is allocated",
                add == AnchorResolver.wrapWithIconBandResize(add, null, "Icon band"));

        Command wrapped = AnchorResolver.wrapWithIconBandResize(add, parentResize, "Icon band");
        assertTrue(wrapped instanceof CompoundCommand);
        List<?> members = ((CompoundCommand) wrapped).getCommands();
        assertEquals("exactly the reservation plus the add", 2, members.size());
        assertTrue("the parent reservation must execute BEFORE the child is added",
                parentResize == members.get(0));
        assertTrue("and the add lands second", add == members.get(1));
    }

    // ---- effectiveRect / refitRect / effectiveDims ---------------------------------------------

    @Test
    public void effectiveRect_shouldReadLiveBounds_whenTheBatchQueuedNothingForTheObject() {
        IDiagramModelGroup g = group("g", 600, 0, 100, 100);
        assertArrayEquals("no queued entry ⇒ the live rectangle, exactly as the unseeded read",
                new int[] { 600, 0, 100, 100 }, AnchorResolver.effectiveRect(g, Map.of()));
    }

    @Test
    public void effectiveRect_shouldReadTheQueuedRectangle_whenTheBatchAlreadySizedTheObject() {
        IDiagramModelGroup g = group("g", 600, 0, 100, 100);
        Map<String, int[]> queued = Map.of("g", new int[] { 1200, 300, 900, 700 });
        assertArrayEquals("the queued rectangle supersedes the pre-batch read",
                new int[] { 1200, 300, 900, 700 }, AnchorResolver.effectiveRect(g, queued));
    }

    @Test
    public void effectiveRect_shouldReadLiveBounds_whenThePendingMapIsNull() {
        // the single-tool path: MutationContext.queuedBounds() is null outside BATCH mode
        IDiagramModelGroup g = group("g", 5, 7, 11, 13);
        assertArrayEquals(new int[] { 5, 7, 11, 13 }, AnchorResolver.effectiveRect(g, null));
    }

    @Test
    public void refitRect_shouldKeepTheQueuedSize_whenTheComputedFitIsSmaller() {
        IDiagramModelGroup g = group("g", 600, 0, 100, 100);
        Map<String, int[]> queued = Map.of("g", new int[] { 600, 0, 900, 700 });
        assertArrayEquals("an explicit same-batch size is a floor the re-fit may not shrink below",
                new int[] { 600, 0, 900, 700 },
                AnchorResolver.refitRect(g, new int[] { 220, 144 }, queued));
    }

    @Test
    public void refitRect_shouldGrowPastTheQueuedSize_whenTheChildrenNeedMore() {
        IDiagramModelGroup g = group("g", 600, 0, 100, 100);
        Map<String, int[]> queued = Map.of("g", new int[] { 600, 0, 210, 210 });
        assertArrayEquals("the floor must not become a ceiling — the fit still encloses the children",
                new int[] { 600, 0, 220, 385 },
                AnchorResolver.refitRect(g, new int[] { 220, 385 }, queued));
    }

    @Test
    public void refitRect_shouldTakeTheMaxPerAxisIndependently_whenQueuedIsWiderButShorter() {
        IDiagramModelGroup g = group("g", 600, 0, 100, 100);
        Map<String, int[]> queued = Map.of("g", new int[] { 600, 0, 900, 50 });
        assertArrayEquals("width from the queue, height from the fit — the axes do not interact",
                new int[] { 600, 0, 900, 144 },
                AnchorResolver.refitRect(g, new int[] { 220, 144 }, queued));
    }

    @Test
    public void refitRect_shouldCarryThePositionOutright_neverMaxed() {
        // max() is meaningless for a coordinate: a queued move to a SMALLER x must survive intact
        IDiagramModelGroup g = group("g", 600, 0, 100, 100);
        Map<String, int[]> queued = Map.of("g", new int[] { 5, 9, 100, 100 });
        int[] rect = AnchorResolver.refitRect(g, new int[] { 220, 144 }, queued);
        assertEquals("x is taken outright, not maxed", 5, rect[0]);
        assertEquals("y is taken outright, not maxed", 9, rect[1]);
    }

    @Test
    public void refitRect_shouldLetTheFitShrinkTheContainer_whenTheBatchQueuedNothingForIt() {
        // the single-tool path, and any object the batch never touched: flooring against the LIVE
        // rectangle would make every re-fit grow-only and stop a negative delta ever tightening
        IDiagramModelGroup g = group("g", 600, 0, 600, 600);
        assertArrayEquals("no queued entry ⇒ the computed fit is written unchanged, shrink included",
                new int[] { 600, 0, 220, 144 },
                AnchorResolver.refitRect(g, new int[] { 220, 144 }, Map.of()));
        assertArrayEquals("null map (outside a batch) behaves identically",
                new int[] { 600, 0, 220, 144 },
                AnchorResolver.refitRect(g, new int[] { 220, 144 }, null));
    }

    @Test
    public void effectiveDims_shouldPreferThisPassesOwnCompound_overWhatTheBatchQueued() {
        IDiagramModelGroup child = group("c", 0, 0, 100, 100);
        Map<String, int[]> queued = Map.of("c", new int[] { 0, 0, 500, 400 });
        assertArrayEquals("the compound is the later write — a nested re-fit supersedes the queue",
                new int[] { 220, 144 },
                AnchorResolver.effectiveDims(child, new int[] { 220, 144 }, queued));
    }

    @Test
    public void effectiveDims_shouldFallBackToTheQueuedSize_whenThisPassHasNotTouchedTheChild() {
        IDiagramModelGroup child = group("c", 0, 0, 100, 100);
        Map<String, int[]> queued = Map.of("c", new int[] { 0, 0, 500, 400 });
        assertArrayEquals("a child the batch re-sized is laid out at the size it will have",
                new int[] { 500, 400 }, AnchorResolver.effectiveDims(child, null, queued));
    }

    @Test
    public void effectiveDims_shouldFallBackToLiveBounds_whenNeitherSourceKnowsTheChild() {
        IDiagramModelGroup child = group("c", 0, 0, 100, 100);
        assertArrayEquals(new int[] { 100, 100 },
                AnchorResolver.effectiveDims(child, null, Map.of()));
        assertArrayEquals("null map (the single-tool path) behaves identically",
                new int[] { 100, 100 }, AnchorResolver.effectiveDims(child, null, null));
    }

    // ---- projectResizedAcrossIterations (the multi-iteration control-loop projection) ----------
    //
    // The three spacing convenience tools drive a control loop that calls the spacing helper once
    // per iteration and dispatches the ACCEPTED iterations as ONE outer compound. The outer
    // compound's members are therefore per-iteration compounds, not placements — a scan that only
    // looks at the top level finds nothing on any fixture that does not escalate.

    /** An outer compound shaped exactly as the three tools build one: per-iteration compounds. */
    private static NonNotifyingCompoundCommand outer(Command... members) {
        NonNotifyingCompoundCommand c = new NonNotifyingCompoundCommand("outer");
        for (Command m : members) {
            c.add(m);
        }
        return c;
    }

    private static NonNotifyingCompoundCommand iteration(Command... members) {
        NonNotifyingCompoundCommand c = new NonNotifyingCompoundCommand("iteration");
        for (Command m : members) {
            c.add(m);
        }
        return c;
    }

    @Test
    public void projectResizedAcrossIterations_shouldFindPlacementsNestedInsideAnIterationCompound() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("g", 0, 0, 100, 100);
        g.setName("G");
        diagram.getChildren().add(g);

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(iteration(new UpdateViewObjectCommand(g, 0, 0, 180, 140))),
                Map.of(), diagram);

        assertEquals("a placement one level inside an iteration compound must be found — the outer "
                + "compound never holds placements directly on the non-escalating path",
                1, resized.size());
        assertEquals("g", resized.get(0).viewObjectId());
        assertEquals("G", resized.get(0).name());
        assertEquals(180, resized.get(0).newWidth());
        assertEquals(140, resized.get(0).newHeight());
    }

    @Test
    public void projectResizedAcrossIterations_shouldFindABarePlacementSittingAtTheOuterLevel() {
        // the one-shot density hub-resize: a bare UpdateViewObjectCommand added straight into the
        // accepted list, so it lands at the OUTER compound's top level rather than inside an
        // iteration compound. It is the largest single resize these tools perform.
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup hub = group("hub", 10, 20, 120, 90);
        diagram.getChildren().add(hub);

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(new UpdateViewObjectCommand(hub, 10, 20, 300, 250)), Map.of(), diagram);

        assertEquals(1, resized.size());
        assertEquals("hub", resized.get(0).viewObjectId());
        assertEquals(300, resized.get(0).newWidth());
        assertEquals(250, resized.get(0).newHeight());
    }

    @Test
    public void projectResizedAcrossIterations_shouldReportTheLastIterationsRectangle_whenTwoIterationsResizeTheSameObject() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("g", 0, 0, 100, 100);
        diagram.getChildren().add(g);

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(iteration(new UpdateViewObjectCommand(g, 0, 0, 150, 120)),
                      iteration(new UpdateViewObjectCommand(g, 0, 0, 210, 160))),
                Map.of(), diagram);

        assertEquals("one entry per object, not one per iteration", 1, resized.size());
        assertEquals("the LAST iteration is the one the model ends at", 210, resized.get(0).newWidth());
        assertEquals(160, resized.get(0).newHeight());
    }

    @Test
    public void projectResizedAcrossIterations_shouldExcludeAnObjectALaterIterationRestoredToItsOriginalSize() {
        // net-zero across the call: the model ends at the size it started, so the call changed
        // nothing about this object's size and naming it would be a false report.
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("g", 0, 0, 100, 100);
        diagram.getChildren().add(g);

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(iteration(new UpdateViewObjectCommand(g, 0, 0, 150, 120)),
                      iteration(new UpdateViewObjectCommand(g, 0, 0, 100, 100))),
                Map.of(), diagram);

        assertTrue("a size the last command restores is not a size the call changed",
                resized.isEmpty());
    }

    @Test
    public void projectResizedAcrossIterations_shouldExcludeAnObjectThatOnlyMoved() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("g", 0, 0, 100, 100);
        diagram.getChildren().add(g);

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(iteration(new UpdateViewObjectCommand(g, 400, 300, 100, 100))),
                Map.of(), diagram);

        assertTrue("this list is about SIZE alone — a moved object kept its rectangle's extent",
                resized.isEmpty());
    }

    @Test
    public void projectResizedAcrossIterations_shouldMeasureAgainstTheQueuedSize_whenTheBatchAlreadySizedTheObject() {
        // getBounds() inside an open batch is a PRE-batch read while the loop's commands were
        // computed against queued geometry. Comparing the two frames produces both false positives
        // and false negatives; this fixture is the false-positive half.
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("g", 0, 0, 100, 100);
        diagram.getChildren().add(g);
        Map<String, int[]> queued = Map.of("g", new int[] { 0, 0, 260, 200 });

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(iteration(new UpdateViewObjectCommand(g, 0, 0, 260, 200))), queued, diagram);

        assertTrue("the command writes exactly what the batch already queued — no size change; "
                + "against the pre-batch getBounds() this would read as a 100x100 -> 260x200 resize",
                resized.isEmpty());
    }

    @Test
    public void projectResizedAcrossIterations_shouldReportAResizeTheLiveBoundsWouldHide_whenTheBatchQueuedTheLandedSize() {
        // the false-NEGATIVE half of the same frame error: live bounds happen to equal the landed
        // size, but the batch queued something else, so the object DOES change size when the
        // compound runs.
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("g", 0, 0, 300, 240);
        diagram.getChildren().add(g);
        Map<String, int[]> queued = Map.of("g", new int[] { 0, 0, 120, 90 });

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(iteration(new UpdateViewObjectCommand(g, 0, 0, 300, 240))), queued, diagram);

        assertEquals("against getBounds() this reads as a no-op and would be dropped",
                1, resized.size());
        assertEquals(300, resized.get(0).newWidth());
    }

    @Test
    public void projectResizedAcrossIterations_shouldReturnAnEmptyList_whenTheCompoundHoldsNothing() {
        assertTrue(AnchorResolver.projectResizedAcrossIterations(
                outer(), Map.of(), newDiagram()).isEmpty());
    }

    @Test
    public void projectResizedAcrossIterations_shouldLetABareOuterPlacementSupersedeAnEarlierIteration() {
        // the escalate ordering: the hub-resize command is appended to the accepted list AFTER the
        // spacing iterations that preceded it, so it is the last write for that object.
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup hub = group("hub", 0, 0, 120, 90);
        diagram.getChildren().add(hub);

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(iteration(new UpdateViewObjectCommand(hub, 0, 0, 140, 100)),
                      new UpdateViewObjectCommand(hub, 0, 0, 380, 330)),
                Map.of(), diagram);

        assertEquals(1, resized.size());
        assertEquals("the bare hub-resize is the later write", 380, resized.get(0).newWidth());
        assertEquals(330, resized.get(0).newHeight());
    }

    @Test
    public void projectResizedAcrossIterations_shouldFindAPlacementNestedTwoLevelsDeep() {
        // The three tools build a flat per-iteration compound today, so one level in was sufficient
        // — but a depth limit fails SILENTLY the first time a pass nests one deeper, reporting an
        // empty list rather than an error. The queued-command walk this file sits beside recurses
        // without a limit for exactly that reason, and this pin is what stops the two drifting.
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("g", 0, 0, 100, 100);
        g.setName("G");
        diagram.getChildren().add(g);

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(iteration(iteration(new UpdateViewObjectCommand(g, 0, 0, 180, 140)))),
                Map.of(), diagram);

        assertEquals("a placement two levels down must still be found", 1, resized.size());
        assertEquals(180, resized.get(0).newWidth());
        assertEquals(140, resized.get(0).newHeight());
    }

    @Test
    public void projectResizedAcrossIterations_shouldKeepDispatchOrderAcrossNestingDepths() {
        // "Later wins" has to mean later in the DISPATCH, not later at some particular depth:
        // a deeper command that runs after a shallower one still overwrites it.
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("g", 0, 0, 100, 100);
        diagram.getChildren().add(g);

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(new UpdateViewObjectCommand(g, 0, 0, 150, 120),
                      iteration(iteration(new UpdateViewObjectCommand(g, 0, 0, 260, 210)))),
                Map.of(), diagram);

        assertEquals(1, resized.size());
        assertEquals("the nested command runs last, so it is the one the model ends at",
                260, resized.get(0).newWidth());
        assertEquals(210, resized.get(0).newHeight());
    }

    @Test
    public void projectResizedAcrossIterations_shouldFallBackToTheId_whenTheViewCannotNameTheObject() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group("detached", 0, 0, 100, 100);
        // deliberately NOT added to the diagram: the ordinary case inside a batch, where an object
        // an earlier operation created is still detached when this runs.

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outer(iteration(new UpdateViewObjectCommand(g, 0, 0, 180, 140))),
                Map.of(), diagram);

        assertEquals(1, resized.size());
        assertEquals("an id is still an actionable handle; a placeholder name would not be",
                "detached", resized.get(0).name());
    }
}
