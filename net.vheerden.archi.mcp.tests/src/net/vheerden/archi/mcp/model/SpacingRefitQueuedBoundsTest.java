package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.AdjustViewSpacingResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * Pins what {@code adjust-view-spacing} re-fits a container <em>against</em> when it runs inside an
 * open batch, on every axis it writes.
 *
 * <p>The pass emits an absolute rectangle per group, computed from {@code getBounds()}. Inside a
 * batch that read predates every command the batch holds, so a group an earlier operation sized or
 * moved is measured at a rectangle it is about to stop having, and the pass's own command — queued
 * later — discards the caller's silently, both calls reporting success. The same is true one level
 * down: the child re-position commands carried live dimensions, so a child the batch had re-sized
 * was laid out, enclosed, and then re-written at its stale size.</p>
 *
 * <h2>Grow-only is a property of the queue, not of the fit</h2>
 *
 * <p>The floor exists only where the batch queued something. With nothing queued the computed fit
 * is written unchanged, shrink included — a negative delta must still be able to tighten a view, and
 * the single-tool path has to stay what it was. {@link #shouldStillShrinkAGroupToItsFit_whenNoBatchIsOpen()}
 * is that guard; without it a floor taken against the live rectangle would silently make every
 * re-fit grow-only and nothing else here would notice.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>The batch-geometry idiom the sibling classes use: a real GEF {@link CommandStack} over an
 * ordered compound, the production {@code NonNotifyingCompoundCommand} rebuilt as a plain
 * {@link CompoundCommand} because its {@code execute()} dereferences
 * {@code IEditorModelManager.INSTANCE}, {@code undo} overridden to pop that stack directly, and
 * explicit bounds on every add so nothing reaches {@code ElementSizer}'s display-bound measurement.</p>
 */
public class SpacingRefitQueuedBoundsTest {

    private static final String SESSION = "spacing-refit-queued-bounds-session";

    /**
     * The inset every fixture below places its children at, which
     * {@code detectPaddingFromPositions} recovers as the group's current padding. Deriving the
     * expected rectangles from this rather than from copied literals is what makes a wrong-for-the-
     * right-reason pass visible.
     */
    private static final int PAD = 10;
    private static final int LABEL = GroupLayoutCalculator.GROUP_LABEL_HEIGHT;

    /** The width a group is re-fitted to around a single child of this width. */
    private static int fitW(int childWidth) {
        return PAD + childWidth + PAD;
    }

    /** The height a group is re-fitted to around a single child of this height. */
    private static int fitH(int childHeight) {
        return PAD + LABEL + childHeight + PAD;
    }

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private int actorSeq;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Spacing Refit Fixture");
        model.setId("model-spacing-refit");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Refit");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 12; i++) {
            IBusinessActor a = factory.createBusinessActor();
            a.setId("actor-" + i);
            a.setName("Actor " + i);
            business.getElements().add(a);
        }

        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.setSource((IBusinessActor) business.getElements().get(0));
        rel.setTarget((IBusinessActor) business.getElements().get(1));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        // actor-1 -> actor-4 and actor-2 -> actor-3: a genuine crossing for the reorder fixtures
        IArchimateRelationship cross1 = factory.createAssociationRelationship();
        cross1.setId("rel-cross-1");
        cross1.setSource((IBusinessActor) business.getElements().get(0));
        cross1.setTarget((IBusinessActor) business.getElements().get(3));
        model.getFolder(FolderType.RELATIONS).getElements().add(cross1);

        IArchimateRelationship cross2 = factory.createAssociationRelationship();
        cross2.setId("rel-cross-2");
        cross2.setSource((IBusinessActor) business.getElements().get(1));
        cross2.setTarget((IBusinessActor) business.getElements().get(2));
        model.getFolder(FolderType.RELATIONS).getElements().add(cross2);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            public UndoRedoState undo(int steps) {
                for (int i = 0; i < steps && stack.canUndo(); i++) {
                    stack.undo();
                }
                return null;
            }
            private Command toPlainCompound(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand plain = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        plain.add(toPlainCompound((Command) child));
                    }
                    return plain;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String group(String label, int x, int y, int w, int h, String parent) {
        return accessor.addGroupToView(SESSION, view.getId(), label, x, y, w, h,
                parent, null, null).entity().viewObjectId();
    }

    private String child(int x, int y, int w, int h, String parent) {
        return accessor.addToView(SESSION, view.getId(), "actor-" + (++actorSeq),
                x, y, w, h, false, parent, null, null).entity().viewObject().viewObjectId();
    }

    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject c : container.getChildren()) {
            if (id.equals(c.getId())) return c;
            if (c instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private String box(String id) {
        IBounds b = find(view, id).getBounds();
        return b.getX() + "," + b.getY() + " " + b.getWidth() + "x" + b.getHeight();
    }

    private AdjustViewSpacingResultDto space(Integer elementDelta, Integer groupDelta,
            boolean recursive) {
        return accessor.adjustViewSpacing(SESSION, view.getId(), elementDelta, null,
                groupDelta, recursive).entity();
    }

    /**
     * Asserts the pass actually did its job before anything is claimed about the group rectangle: a
     * group-size-only assertion would pass just as well on a build where the spacing pass silently
     * did nothing at all.
     */
    private void assertSpacingActuallyHappened(AdjustViewSpacingResultDto result,
            String childId, String childBoxBefore) {
        assertTrue("the pass must have repositioned children — a build where it did nothing would "
                + "satisfy every group-rectangle assertion below. Reported: "
                + result.elementsRepositioned(), result.elementsRepositioned() > 0);
        assertNotEquals("and the model must show it, not just the report", childBoxBefore,
                box(childId));
    }

    // ---- the queued rectangle is a floor, per axis ----------------------------------------------

    @Test
    public void shouldKeepTheQueuedSize_whenTheComputedFitIsSmaller() {
        String g = group("G", 600, 0, 100, 100, null);
        String c = child(PAD, PAD, 200, 100, g);
        String before = box(c);

        dispatcher.beginBatch(SESSION, "size G, then space the view");
        accessor.updateViewObject(SESSION, g, null, null, 900, 700, null, null, null, null);
        AdjustViewSpacingResultDto result = space(60, null, false);
        dispatcher.endBatch(SESSION, true);

        assertSpacingActuallyHappened(result, c, before);
        assertEquals("the queued size is a floor the re-fit may not shrink below",
                "600,0 900x700", box(g));
    }

    @Test
    public void shouldGrowPastTheQueuedSize_whenItIsStillTooSmallForTheChildren() {
        String g = group("G", 600, 0, 100, 100, null);
        String c = child(PAD, PAD, 200, 100, g);
        String before = box(c);

        dispatcher.beginBatch(SESSION, "size G too small, then space the view");
        accessor.updateViewObject(SESSION, g, null, null, 120, 120, null, null, null, null);
        AdjustViewSpacingResultDto result = space(60, null, false);
        dispatcher.endBatch(SESSION, true);

        assertSpacingActuallyHappened(result, c, before);
        assertEquals("the floor must not become a ceiling — the fit still encloses the child",
                "600,0 " + fitW(200) + "x" + fitH(100), box(g));
    }

    @Test
    public void shouldTakeTheMaxPerAxisIndependently_whenQueuedIsWiderButShorter() {
        String g = group("G", 600, 0, 100, 100, null);
        String c = child(PAD, PAD, 200, 100, g);
        String before = box(c);

        // wider than the fit needs, shorter than it needs: each axis must be decided on its own
        int queuedWidth = fitW(200) + 400;
        int queuedHeight = fitH(100) - 40;

        dispatcher.beginBatch(SESSION, "size G wide and short, then space the view");
        accessor.updateViewObject(SESSION, g, null, null, queuedWidth, queuedHeight,
                null, null, null, null);
        AdjustViewSpacingResultDto result = space(60, null, false);
        dispatcher.endBatch(SESSION, true);

        assertSpacingActuallyHappened(result, c, before);
        assertEquals("width from the queue, height from the fit — the axes do not interact",
                "600,0 " + queuedWidth + "x" + fitH(100), box(g));
    }

    // ---- the position is carried too, not only the size -----------------------------------------

    @Test
    public void shouldKeepTheQueuedPosition_whenTheSpacingPassRefitsTheGroup() {
        // roomy enough that the post-spacing overflow cascade finds nothing to grow and emits
        // nothing — otherwise IT re-emits the group at the pending position and the spacing pass's
        // own live-x/y command is never the last writer, hiding the defect
        String g = group("G", 600, 0, 600, 600, null);
        String c = child(PAD, PAD, 200, 100, g);
        String before = box(c);

        dispatcher.beginBatch(SESSION, "move G, then space the view");
        accessor.updateViewObject(SESSION, g, 1200, 300, null, null, null, null, null, null);
        AdjustViewSpacingResultDto result = space(60, null, false);
        dispatcher.endBatch(SESSION, true);

        assertSpacingActuallyHappened(result, c, before);
        assertEquals("the queued move survives; the size floors at the rectangle the move carried",
                "1200,300 600x600", box(g));
    }

    // ---- a child the batch re-sized is laid out at the size it will have -------------------------

    @Test
    public void shouldLayOutAChildAtItsQueuedSize_whenTheBatchResizedItFirst() {
        String g = group("G", 600, 0, 600, 600, null);
        String c = child(PAD, PAD, 100, 50, g);

        dispatcher.beginBatch(SESSION, "grow the child, then space the view");
        accessor.updateViewObject(SESSION, c, null, null, 400, 300, null, null, null, null);
        space(60, null, false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the child's queued size must survive the pass that repositions it",
                "400x300", box(c).split(" ")[1]);
        assertEquals("and the group must be fitted around the size the child will actually have, "
                + "not around the stale one it was read at",
                "600,0 " + fitW(400) + "x" + fitH(300), box(g));
    }

    // ---- nested: the inner size survives its own parent's re-layout ------------------------------

    @Test
    public void shouldKeepTheQueuedSizeOfANestedGroup_whenARecursivePassRefitsItsAncestors() {
        String outer = group("O", 600, 0, 400, 400, null);
        String inner = group("I", PAD, PAD + LABEL, 100, 100, outer);
        child(PAD, PAD, 200, 100, inner);

        dispatcher.beginBatch(SESSION, "size the inner group, then space recursively");
        accessor.updateViewObject(SESSION, inner, null, null, 500, 400, null, null, null, null);
        space(60, null, true);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the inner group's queued size survives — the parent's own child-reposition "
                + "command must carry the effective size, not the live one",
                "500x400", box(inner).split(" ")[1]);
        assertEquals("and the ancestor walk fits AROUND the floored inner group rather than "
                + "undoing it", fitW(500) + "x" + fitH(400), box(outer).split(" ")[1]);
    }

    @Test
    public void shouldKeepTheQueuedSizeOfAnAncestor_whenTheWalkWouldHaveRefittedItSmaller() {
        String outer = group("O", 600, 0, 100, 100, null);
        String inner = group("I", PAD, PAD + LABEL, 100, 100, outer);
        child(PAD, PAD, 200, 100, inner);

        dispatcher.beginBatch(SESSION, "size the OUTER group, then space recursively");
        accessor.updateViewObject(SESSION, outer, null, null, 900, 700, null, null, null, null);
        space(60, null, true);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the ancestor walk runs AFTER the group's own re-fit, so a floor applied only "
                + "to the latter would be a veto this walk steps straight around",
                "600,0 900x700", box(outer));
    }

    // ---- the report describes what commits -------------------------------------------------------

    @Test
    public void shouldReturnAnAssessmentOfTheGeometryThatActuallyCommits() {
        // a neighbour placed so that ONLY the floored rectangle reaches it: the assessment of the
        // temporarily-applied state and the assessment of the committed model can only agree if the
        // floor was applied to the command the pass dispatches, not merged in afterwards
        String g = group("G", 0, 0, 100, 100, null);
        child(PAD, PAD, 200, 100, g);
        String neighbour = group("N", 500, 0, 200, 200, null);
        child(PAD, PAD, 100, 50, neighbour);

        dispatcher.beginBatch(SESSION, "size G over its neighbour, then space the view");
        accessor.updateViewObject(SESSION, g, null, null, 900, 700, null, null, null, null);
        AdjustViewSpacingResultDto result = space(60, null, false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the group commits at the floored rectangle", "0,0 900x700", box(g));

        AssessLayoutResultDto fresh = accessor.assessLayout(view.getId());
        assertEquals("the rating the tool returned must describe the geometry the model now holds; "
                + "an assessment taken before the floor would be of a layout that never commits",
                fresh.overallRating(), result.overallRating());
        assertEquals("and so must the breakdown behind it",
                fresh.ratingBreakdown(), result.ratingBreakdown());
        assertEquals(fresh.coincidentSegmentCount(), result.coincidentSegmentCount());
        assertEquals(fresh.nonOrthogonalTerminalCount(), result.nonOrthogonalTerminalCount());
    }

    // ---- outside a batch, nothing changes --------------------------------------------------------

    /**
     * The mechanical argument, then the assertion. {@code MutationContext.queuedBounds()} returns
     * null outside {@code BATCH} mode and {@code MutationDispatcher.queuedBounds(sessionId)} returns
     * null for a session with no open batch, so {@code seedPending} yields an empty map, every
     * lookup misses, and the re-fit writes its computed rectangle with the live position — the
     * construction it replaced, argument by argument.
     */
    @Test
    public void shouldStillShrinkAGroupToItsFit_whenNoBatchIsOpen() {
        String g = group("G", 600, 0, 600, 600, null);
        child(PAD, PAD, 200, 100, g);

        space(60, null, false);

        assertEquals("with nothing queued the computed fit is written unchanged, shrink included — "
                + "a floor taken against the LIVE rectangle would make every re-fit grow-only",
                "600,0 " + fitW(200) + "x" + fitH(100), box(g));
    }

    /**
     * The shrink rule, pinned in the direction it was decided. A negative delta legitimately
     * tightens a view, and it still does — except for a container the batch has explicitly given a
     * size, where the explicit later size beats the implicit re-fit. Recorded so the next reader
     * sees a decision rather than an oversight.
     */
    @Test
    public void shouldBlockANegativeDeltaFromShrinkingBelowTheQueuedSize_butNotOtherwise() {
        String floored = group("F", 0, 0, 400, 400, null);
        child(PAD, PAD + LABEL, 120, 55, floored);
        // the SECOND child is the one a spacing change moves — the first sits at the padding origin
        String fc2 = child(PAD, PAD + LABEL + 55 + 60, 120, 55, floored);
        String free = group("U", 900, 0, 400, 400, null);
        child(PAD, PAD + LABEL, 120, 55, free);
        child(PAD, PAD + LABEL + 55 + 60, 120, 55, free);
        String before = box(fc2);

        dispatcher.beginBatch(SESSION, "size one group, then tighten the whole view");
        accessor.updateViewObject(SESSION, floored, null, null, 400, 400, null, null, null, null);
        AdjustViewSpacingResultDto result = space(-40, null, false);
        dispatcher.endBatch(SESSION, true);

        assertSpacingActuallyHappened(result, fc2, before);
        assertEquals("the group the batch sized holds that size against the shrink",
                "0,0 400x400", box(floored));
        assertTrue("but a group the batch never touched still tightens normally: " + box(free),
                find(view, free).getBounds().getHeight() < 400);
    }

    /**
     * The one place outside a batch where this change is visible, pinned deliberately rather than
     * discovered later.
     *
     * <p>A recursive pass re-fits a nested group around its own re-spaced children, and then lays
     * that group out as a child of its parent. The second command used to carry the group's LIVE
     * size, so the pass discarded the re-fit it had just computed and the parent was fitted around a
     * rectangle that no longer described the inner group. Feeding the parent's layout the effective
     * sizes fixes both: the inner group keeps its re-fit, and the parent encloses what is really
     * there.</p>
     *
     * <p>It has to work this way for the batch case to be correct at all. When the batch queued an
     * inner size that is too SMALL, the inner group's own re-fit grows past it, and a parent that
     * measured the queued rectangle instead of that re-fit would write the child back down and push
     * its contents outside — trading this defect for a containment one.</p>
     */
    @Test
    public void shouldKeepItsOwnNestedRefit_whenARecursivePassRunsOutsideABatch() {
        String outer = group("O", 0, 0, 400, 400, null);
        String inner = group("I", PAD, PAD + LABEL, 200, 120, outer);
        child(PAD, PAD, 100, 50, inner);

        space(60, null, true);

        assertEquals("the inner group keeps the size the recursion fitted it to, instead of being "
                + "written back to the size it had before the pass ran",
                fitW(100) + "x" + fitH(50), box(inner).split(" ")[1]);
        assertEquals("and the parent is fitted around that, not around the stale rectangle",
                fitW(fitW(100)) + "x" + fitH(fitH(50)), box(outer).split(" ")[1]);
    }

    // ---- the other container re-fit writers on the same axis --------------------------------------

    /**
     * {@code layout-within-group} arranges a container's children and re-fits the container around
     * them. Like the spacing pass it preserves placement by intent, so a size the batch queued is a
     * floor here too. Measured before: 900x700 queued, 220x284 committed.
     */
    @Test
    public void shouldKeepTheQueuedSize_whenLayoutWithinGroupRefitsTheContainer() {
        String g = group("G", 600, 0, 100, 100, null);
        child(PAD, PAD, 200, 100, g);
        child(PAD, 130, 200, 100, g);

        dispatcher.beginBatch(SESSION, "size G, then lay out within it");
        accessor.updateViewObject(SESSION, g, null, null, 900, 700, null, null, null, null);
        accessor.layoutWithinGroup(SESSION, view.getId(), g, "column", 40, PAD,
                null, null, true, false, null, false, false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("layout-within-group's auto-resize is a re-fit, not a re-layout of the view — "
                + "an explicit same-batch size outranks it", "600,0 900x700", box(g));
    }

    /**
     * {@code optimize-group-order} reorders children within groups and re-fits only the groups it
     * actually reordered. Measured before: the reordered group's queued 900x700 committed at
     * 140x194; a group it did not reorder was never written at all, which is why a fixture that
     * sizes the wrong group proves nothing here.
     */
    @Test
    public void shouldKeepTheQueuedSize_whenOptimizeGroupOrderRefitsAGroupItReordered() {
        String g1 = group("G1", 0, 0, 300, 300, null);
        String a = child(PAD, PAD + LABEL, 120, 55, g1);
        String b = child(PAD, PAD + LABEL + 55 + 60, 120, 55, g1);
        String g2 = group("G2", 600, 0, 300, 300, null);
        String c = child(PAD, PAD + LABEL, 120, 55, g2);
        String d = child(PAD, PAD + LABEL + 55 + 60, 120, 55, g2);
        // a→d and b→c cross, so the minimiser must reorder a group to unpick them
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-1", a, d,
                null, null, null, null, null);
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-2", b, c,
                null, null, null, null, null);

        dispatcher.beginBatch(SESSION, "size both groups, then optimize group order");
        accessor.updateViewObject(SESSION, g1, null, null, 900, 700, null, null, null, null);
        accessor.updateViewObject(SESSION, g2, null, null, 900, 700, null, null, null, null);
        var optimized = accessor.optimizeGroupOrder(SESSION, view.getId(), null, null, null,
                null, null, false, null, null).entity();
        dispatcher.endBatch(SESSION, true);

        assertTrue("the fixture must actually make the minimiser reorder something, or the tool "
                + "writes no group rectangle at all and this proves nothing",
                optimized.groupsOptimized() > 0);
        assertEquals("the group it reordered keeps the size the batch queued",
                "0,0 900x700", box(g1));
        assertEquals("and so does the one it left alone", "600,0 900x700", box(g2));
    }

    /**
     * The same tool through its OTHER flag. {@code recursiveChildren} takes a completely separate
     * code path — the post-order descendant walk — which emits its own absolute rectangle per
     * container from a pre-batch read and never consulted the map. Fixing only the single-level arm
     * left the identical defect reachable by flipping one boolean. Measured before: 900x700 queued,
     * 160x143 committed.
     */
    @Test
    public void shouldKeepTheQueuedSize_whenLayoutWithinGroupRecursesIntoDescendants() {
        String g = group("G", 600, 0, 100, 100, null);
        String inner = group("I", PAD, PAD + LABEL, 100, 100, g);
        child(PAD, PAD, 120, 55, inner);

        dispatcher.beginBatch(SESSION, "size G, then lay out its descendants recursively");
        accessor.updateViewObject(SESSION, g, null, null, 900, 700, null, null, null, null);
        accessor.layoutWithinGroup(SESSION, view.getId(), g, "column", 40, PAD,
                null, null, true, false, null, false, /*recursiveChildren=*/ true);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the descendant walk must honour the queued rectangle exactly as the "
                + "single-level arm does", "600,0 900x700", box(g));
    }

    /** ...and the ancestor walk that composes with it, which used the map-less overload. */
    @Test
    public void shouldKeepTheQueuedSizeOfAnAncestor_whenTheDescendantWalkPropagatesUpward() {
        String outer = group("O", 600, 0, 100, 100, null);
        String g = group("G", PAD, PAD + LABEL, 200, 200, outer);
        String inner = group("I", PAD, PAD + LABEL, 100, 100, g);
        child(PAD, PAD, 120, 55, inner);

        dispatcher.beginBatch(SESSION, "size the ANCESTOR, then recurse into descendants");
        accessor.updateViewObject(SESSION, outer, null, null, 900, 700, null, null, null, null);
        accessor.layoutWithinGroup(SESSION, view.getId(), g, "column", 40, PAD,
                null, null, true, false, null, /*recursive=*/ true, true);
        dispatcher.endBatch(SESSION, true);

        assertEquals("both walks in one call must measure against the same map",
                "600,0 900x700", box(outer));
    }

    /**
     * The shrink rule's second consequence, pinned because it is a decision, not an oversight.
     *
     * <p>The map is derived from the command QUEUE, so it holds rectangles this tool itself emitted
     * earlier in the same batch — not only sizes a caller asked for explicitly. Two spacing calls in
     * one batch therefore interact: the second sees the first's output as a floor, and a negative
     * delta cannot tighten below it. Measured: a group spaced +60 to 140x274 and then spaced -40 in
     * the same batch stays at 140x274 rather than reaching the 140x234 the second call computed.</p>
     */
    @Test
    public void shouldFloorASecondSpacingCallAtTheFirstCallsOwnOutput_withinOneBatch() {
        String g = group("G", 0, 0, 400, 400, null);
        child(PAD, PAD + LABEL, 120, 55, g);
        child(PAD, PAD + LABEL + 55 + 60, 120, 55, g);

        dispatcher.beginBatch(SESSION, "space positive, then space negative");
        accessor.adjustViewSpacing(SESSION, view.getId(), 60, null, null, false);
        accessor.adjustViewSpacing(SESSION, view.getId(), -40, null, null, false);
        dispatcher.endBatch(SESSION, true);

        int spacedHeight = PAD + LABEL + 55 + (60 + 60) + 55 + PAD;
        assertEquals("the second call is floored at what the first one queued, so the batch commits "
                + "the wider spacing rather than the tightened one",
                "0,0 " + fitW(120) + "x" + spacedHeight, box(g));
    }

    // ---- the size a layout pass feeds itself, per tool -------------------------------------------
    //
    // The floor above is on what a pass re-fits a container AGAINST. These three are on what it
    // sizes a CHILD from, which is the same frame one step earlier: a layout that resolves its
    // input from getBounds() inside a batch lays the child out at the rectangle it is about to stop
    // having, and then writes that rectangle. One pin per standalone tool, alongside the re-fit
    // floors, so the whole-tool claim is held in the class that holds the rest of them.

    @Test
    public void shouldLayOutAChildAtItsQueuedSize_whenLayoutWithinGroupRunsInTheSameBatch() {
        String g = group("G", 0, 0, 600, 600, null);
        String a = child(PAD, PAD + LABEL, 120, 55, g);

        dispatcher.beginBatch(SESSION, "resize a child, then lay its group out");
        accessor.updateViewObject(SESSION, a, null, null, 400, 300, null, null, null, null);
        accessor.layoutWithinGroup(SESSION, view.getId(), g, "column", 40, PAD,
                null, null, true, false, null, false, false);
        dispatcher.endBatch(SESSION, true);

        assertTrue("the queued rectangle must survive the layout queued after it, on both axes. "
                + "Child was: " + box(a), box(a).endsWith(" 400x300"));
    }

    @Test
    public void shouldLayOutAChildAtItsQueuedSize_whenOptimizeGroupOrderRunsInTheSameBatch() {
        String g1 = group("Left", 0, 0, 600, 600, null);
        String a = child(PAD, PAD + LABEL, 120, 55, g1);
        String b = child(PAD, PAD + LABEL + 115, 120, 55, g1);
        String g2 = group("Right", 900, 0, 600, 600, null);
        String c = child(PAD, PAD + LABEL, 120, 55, g2);
        String d = child(PAD, PAD + LABEL + 115, 120, 55, g2);
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-1", a, d,
                null, null, null, null, null);
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-2", b, c,
                null, null, null, null, null);

        dispatcher.beginBatch(SESSION, "resize a child, then optimise the group order");
        accessor.updateViewObject(SESSION, a, null, null, 400, 300, null, null, null, null);
        accessor.optimizeGroupOrder(SESSION, view.getId(), "column", 40, PAD,
                null, null, false, null, null);
        dispatcher.endBatch(SESSION, true);

        assertTrue("a reorder re-places every child of the groups it touches, and must place this "
                + "one at what the batch queued for it. Child was: " + box(a),
                box(a).endsWith(" 400x300"));
    }

    @Test
    public void shouldLayOutAnElementAtItsQueuedSize_whenLayoutFlatViewRunsInTheSameBatch() {
        String a = child(0, 0, 120, 55, null);
        child(200, 0, 120, 55, null);

        dispatcher.beginBatch(SESSION, "resize an element, then lay the view out flat");
        accessor.updateViewObject(SESSION, a, null, null, 400, 300, null, null, null, null);
        accessor.layoutFlatView(SESSION, view.getId(), "row", 40, PAD,
                null, null, null, false);
        dispatcher.endBatch(SESSION, true);

        assertTrue("the top-level range preserves each element's size, and inside a batch that is "
                + "the queued one. Element was: " + box(a), box(a).endsWith(" 400x300"));
    }

    /**
     * The same three tools' shared size resolver is reached by a fourth caller — the relay pass
     * inside {@code auto-layout-and-route} — and that one must stay blind to the queue. The boundary
     * pin below holds it for a group's own rectangle; this one holds it for a CHILD's, which is the
     * axis the shared resolver actually decides. The relay re-derives every child's width from its
     * label and its height from the model, and a queued child size must not divert either.
     */
    @Test
    public void shouldOverrideAQueuedChildSize_whenAutoLayoutAndRouteRelaysTheWholeView() {
        String g1 = group("Left", 0, 0, 300, 300, null);
        String a = child(PAD, PAD + LABEL, 120, 55, g1);
        String b = child(PAD, PAD + LABEL + 115, 120, 55, g1);
        String g2 = group("Right", 600, 0, 300, 300, null);
        String c = child(PAD, PAD + LABEL, 120, 55, g2);
        String d = child(PAD, PAD + LABEL + 115, 120, 55, g2);
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-1", a, d,
                null, null, null, null, null);
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-2", b, c,
                null, null, null, null, null);

        dispatcher.beginBatch(SESSION, "size a child, then auto-layout the whole view");
        accessor.updateViewObject(SESSION, a, null, null, 400, 300, null, null, null, null);
        accessor.autoLayoutAndRoute(SESSION, view.getId(), "grouped", "vertical", 40, null, null);
        dispatcher.endBatch(SESSION, true);

        // The HEIGHT is the discriminating axis and the only one that can be. The relay runs with
        // autoWidth on, so it derives every child's width from its label and no queued width could
        // ever survive it — an assertion on the pair would hold whatever the height did. Measured
        // relative to the enclosing group: 86x55 with the relay blind, 86x300 with the queue
        // threaded into it.
        assertEquals("the relay re-derives a child's height from the model, and a queued height "
                + "must not divert it: honouring the queue here would keep it for the children of "
                + "the groups the reorder happened to touch and discard it for the rest, which is "
                + "the contract that was tried and reverted. Child was: " + box(a),
                "55", box(a).split(" ")[1].split("x")[1]);
    }

    /**
     * The declared boundary, carrying its measurement.
     *
     * <p>{@code auto-layout-and-route} is not a re-fit — it is a whole-view re-layout, and choosing
     * every group's size from its contents and its position from the topology is its output, not an
     * incidental side effect. A caller that queues a size and then asks for a full auto-layout in
     * the same batch has issued the layout instruction second and more specifically, so the layout
     * wins. Flooring the optimize-group-order pass that runs inside it was tried and reverted: it
     * made one call honour the queued size for the groups that pass happened to reorder (measured
     * 900x700 kept) and discard it for the rest (measured 20,179 232x99), which is a worse contract
     * than overriding uniformly.</p>
     */
    @Test
    public void shouldOverrideAQueuedSizeUniformly_whenAutoLayoutAndRouteRelaysTheWholeView() {
        String g1 = group("G1", 0, 0, 300, 300, null);
        String a = child(PAD, PAD + LABEL, 120, 55, g1);
        String b = child(PAD, PAD + LABEL + 55 + 60, 120, 55, g1);
        String g2 = group("G2", 600, 0, 300, 300, null);
        String c = child(PAD, PAD + LABEL, 120, 55, g2);
        String d = child(PAD, PAD + LABEL + 55 + 60, 120, 55, g2);
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-1", a, d,
                null, null, null, null, null);
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-2", b, c,
                null, null, null, null, null);

        dispatcher.beginBatch(SESSION, "size both groups, then auto-layout the whole view");
        accessor.updateViewObject(SESSION, g1, null, null, 900, 700, null, null, null, null);
        accessor.updateViewObject(SESSION, g2, null, null, 900, 700, null, null, null, null);
        accessor.autoLayoutAndRoute(SESSION, view.getId(), "grouped", "vertical", 40, null, null);
        dispatcher.endBatch(SESSION, true);

        assertNotEquals("a full re-layout chooses the geometry — it does not preserve a queued size",
                "900x700", box(g1).split(" ")[1]);
        assertNotEquals("and it does so for every group, not only the ones a sub-pass touched",
                "900x700", box(g2).split(" ")[1]);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener l) { listeners.add(l); }
        @Override public void removePropertyChangeListener(PropertyChangeListener l) { listeners.remove(l); }
        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel m) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel m) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel m, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel m) { return false; }
        @Override public boolean saveModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel m) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }
}
