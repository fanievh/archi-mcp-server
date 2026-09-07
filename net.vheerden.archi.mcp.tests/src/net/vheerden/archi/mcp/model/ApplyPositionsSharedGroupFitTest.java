package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Pins that every position in one {@code apply-positions} call measures its container against what
 * the same call has already decided about that container, and against what an open batch has
 * already queued for it.
 *
 * <p>Each entry is prepared against a model where none of the call's own commands have executed. A
 * per-entry working map therefore lets two entries sharing one group each measure that group at its
 * pre-call size and each emit its own <em>absolute</em> resize for it. All of them land in one
 * compound and execute in insertion order, so the last one wins on all four dimensions and the
 * demand every earlier entry made is discarded — silently, because the call reports success either
 * way.</p>
 *
 * <h2>Why the pair, and why different axes</h2>
 *
 * <p>The two overflowing children push the group on <em>different</em> axes, and the same pair is
 * asserted in both orders. A pair overflowing on the same axis is subsumption-blind: the later,
 * bigger demand swallows the earlier one and the test passes with the defect live. Overflow on
 * different axes makes both orders fail, which is what distinguishes a defect from a race.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>Same headless idiom as {@code BatchQueuedViewObjectUpdateTest}: a real GEF {@link CommandStack}
 * driven over an <em>ordered</em> compound, because command order is the whole subject here. The
 * production compound is {@code NonNotifyingCompoundCommand}, whose {@code execute()} dereferences
 * {@code IEditorModelManager.INSTANCE} and cannot run headless, so it is rebuilt into a plain GEF
 * {@link CompoundCommand} — order preserved, only ECORE event suppression dropped.</p>
 *
 * <p>Every fixture object is created with explicit x/y/width/height so no path reaches
 * {@code ElementSizer}'s {@code Display.getDefault().syncExec}, which is what would otherwise force
 * this class onto a display.</p>
 */
public class ApplyPositionsSharedGroupFitTest {

    private static final String SESSION = "apply-positions-shared-group-session";

    /**
     * The group sits away from the origin and away from every child coordinate below, so a fixture
     * in which the parent and child coordinate frames coincide cannot make a frame error invisible.
     */
    private static final int GROUP_X = 700;
    private static final int GROUP_Y = 250;
    private static final int GROUP_W = 300;
    private static final int GROUP_H = 200;

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IBusinessActor actor;

    /**
     * Every command tree handed to the dispatcher, in dispatch order. The compound is what the
     * model executes and what a human approves, so the number of commands inside it is only
     * observable here — {@code totalOperations} counts entries and cannot see a nested resize, and
     * the group's final rectangle is the same whether one resize wrote it or a thousand did.
     */
    private final List<Command> dispatched = new ArrayList<>();

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Apply Positions Shared Group Fixture");
        model.setId("model-apply-positions-shared-group");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Laying out");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Actor");
        business.getElements().add(actor);

        IBusinessActor peer = factory.createBusinessActor();
        peer.setId("actor-2");
        peer.setName("Peer");
        business.getElements().add(peer);

        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.setSource(actor);
        rel.setTarget(peer);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatched.clear();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                dispatched.add(command);
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                dispatched.add(command);
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
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

    // ---- helpers ------------------------------------------------------------------------------

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label,
                x, y, w, h, parentId, null, null).entity().viewObjectId();
    }

    private String element(int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), actor.getId(), x, y, w, h,
                false, parentId, null, null).entity().viewObject().viewObjectId();
    }

    private void update(String id, Integer x, Integer y, Integer w, Integer h) {
        accessor.updateViewObject(SESSION, id, x, y, w, h,
                null, null, null, null, null, null, null, null);
    }

    private void anchor(String id, String targetId, String edge, int dx, int dy) {
        accessor.updateViewObject(SESSION, id, null, null, null, null,
                null, null, null, null, targetId, edge, dx, dy);
    }

    private static ViewPositionSpec pos(String id, int x, int y) {
        return new ViewPositionSpec(id, x, y, null, null);
    }

    /** A resize-only entry: no x/y, so the object keeps its position and only its size moves. */
    private static ViewPositionSpec resize(String id, int w, int h) {
        return new ViewPositionSpec(id, null, null, w, h);
    }

    private ApplyViewLayoutResultDto apply(ViewPositionSpec... positions) {
        return accessor.applyViewLayout(SESSION, view.getId(),
                List.of(positions), null, null).entity();
    }

    /** Depth-first search of live containment for a view object id. */
    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (Object child : container.getChildren()) {
            IDiagramModelObject obj = (IDiagramModelObject) child;
            if (id.equals(obj.getId())) {
                return obj;
            }
            if (obj instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static void assertBounds(String what, IDiagramModelObject obj, int x, int y, int w, int h) {
        assertNotNull(what + " must exist after the call", obj);
        assertEquals(what + " x", x, obj.getBounds().getX());
        assertEquals(what + " y", y, obj.getBounds().getY());
        assertEquals(what + " width", w, obj.getBounds().getWidth());
        assertEquals(what + " height", h, obj.getBounds().getHeight());
    }

    /**
     * Asserts containment through the production predicate, so the test and the production fit
     * cannot disagree about what "inside" means. The rectangle claims above are written out as
     * literals instead, because a guard deriving its expectation from the helper the production
     * code calls is blind to that helper.
     */
    private static void assertContained(String what, IDiagramModelObject child,
            IDiagramModelObject parent) {
        assertFalse(what + " must sit inside its group",
                ParentFitCascade.childExceedsParentBounds(
                        child.getBounds().getX(), child.getBounds().getY(),
                        child.getBounds().getWidth(), child.getBounds().getHeight(),
                        parent.getBounds().getWidth(), parent.getBounds().getHeight(),
                        ArchiModelAccessorImpl.DEFAULT_GROUP_PADDING));
    }

    /**
     * Flattens a dispatched command tree into its leaves, in execution order. Same recursion the
     * dispatcher stub already runs to rebuild a headless-safe compound, asking a different question
     * of it: not what shape reaches the stack, but how many commands do.
     */
    private static void flatten(Command command, List<Command> out) {
        if (command instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                flatten((Command) child, out);
            }
        } else {
            out.add(command);
        }
    }

    /**
     * How many bounds commands in the whole dispatched tree name {@code viewObjectId}. Counted over
     * the leaves rather than the top level, because the redundancy this measures is nested one
     * level down inside each entry's own compound.
     */
    private int boundsCommandsFor(String viewObjectId) {
        List<Command> leaves = new ArrayList<>();
        for (Command tree : dispatched) {
            flatten(tree, leaves);
        }
        int count = 0;
        for (Command leaf : leaves) {
            if (leaf instanceof UpdateViewObjectCommand update
                    && viewObjectId.equals(update.getDiagramObject().getId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * One group, two children well inside it. Returns {group, childA, childB}; both children keep
     * their 100x50 size throughout, so every expected number below is arithmetic on the padding.
     */
    private String[] oneGroupTwoChildren() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String a = element(10, 10, 100, 50, g);
        String b = element(10, 80, 100, 50, g);
        return new String[] { g, a, b };
    }

    // ---- Defect A: the intra-call clobber ------------------------------------------------------

    /**
     * Two positions in ONE call push the same group past its edge on different axes. The group must
     * end at the size BOTH demand.
     *
     * <p>Child A moves to relative x=500, so the group needs 500+100+10 = 610 of width. Child B
     * moves to relative y=400, so it needs 400+50+10 = 460 of height. Neither demand subsumes the
     * other, so whichever resize executes last discards the other unless the two entries share one
     * pending-bounds map.</p>
     */
    @Test
    public void shouldFitBothChildren_whenTwoPositionsInOneCallOverflowOnDifferentAxes() {
        String[] ids = oneGroupTwoChildren();
        String g = ids[0], a = ids[1], b = ids[2];

        apply(pos(a, 500, 10), pos(b, 10, 400));

        assertBounds("child A lands where it was put", find(view, a), 500, 10, 100, 50);
        assertBounds("child B lands where it was put", find(view, b), 10, 400, 100, 50);
        assertBounds("the group ends at the size BOTH positions demand",
                find(view, g), GROUP_X, GROUP_Y, 610, 460);
        assertContained("child A", find(view, a), find(view, g));
        assertContained("child B", find(view, b), find(view, g));
    }

    /**
     * The same two positions in the opposite order. The twin is not decoration: it is what proves
     * the outcome is order-independent rather than accidentally right for one insertion order.
     * Because the two demands are on different axes neither subsumes the other, so this order fails
     * against the defect exactly as its twin does.
     */
    @Test
    public void shouldFitBothChildren_whenTheSameTwoPositionsArriveInReverseOrder() {
        String[] ids = oneGroupTwoChildren();
        String g = ids[0], a = ids[1], b = ids[2];

        apply(pos(b, 10, 400), pos(a, 500, 10));

        assertBounds("child A lands where it was put", find(view, a), 500, 10, 100, 50);
        assertBounds("child B lands where it was put", find(view, b), 10, 400, 100, 50);
        assertBounds("the group ends at the size both positions demand, in either order",
                find(view, g), GROUP_X, GROUP_Y, 610, 460);
        assertContained("child A", find(view, a), find(view, g));
        assertContained("child B", find(view, b), find(view, g));
    }

    /**
     * One entry re-sizes a group; a later entry in the SAME call moves that group's child. The
     * child must be measured against the size the earlier entry gave the group.
     *
     * <p>This is the half of the accumulator that the cascade cannot supply on its own. A group
     * grown by a <em>cascade</em> reaches the shared map because the cascade writes its working map
     * in place; a group re-sized because the caller <em>named</em> it reaches the map only through
     * the write-back at the end of the prepare. Drop that write-back and the two paths look alike
     * until a fixture asks this question — and then the later entry measures the group at its
     * pre-call 100x100, finds an overflow that is not there, and emits a resize which, executing
     * after the entry that asked for 400x400, shrinks the group to 250x250.</p>
     */
    @Test
    public void shouldMeasureTheChildAgainstTheGroupSizeAnEarlierEntryAsked() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String inner = group("Inner", 10, 10, 100, 100, g);
        String child = element(5, 5, 40, 40, inner);

        apply(resize(inner, 400, 400), pos(child, 200, 200));

        assertBounds("the child lands where it was put", find(view, child), 200, 200, 40, 40);
        assertBounds("the inner group keeps the size the earlier entry asked for",
                find(view, inner), 10, 10, 400, 400);
        assertBounds("the outer group fits the re-sized inner group",
                find(view, g), GROUP_X, GROUP_Y, 420, 420);
        assertContained("the child", find(view, child), find(view, inner));
    }

    /**
     * Two entries in one call each push the same group past its TOP-LEFT edge, on different axes.
     *
     * <p>The grow-left/grow-up branch of the cascade is additive where the right/bottom branch is a
     * {@code Math.max}, so a shared map is the difference between each axis shifting once and the
     * shifts stacking. On different axes each entry owns its own axis, so the correct outcome is one
     * shift per axis: x by A's overflow, y by B's. This is the negative-coordinate counterpart of
     * the pair above, and it is here because a shared working map is exactly what makes an additive
     * branch reachable across entries that no anchor relates.</p>
     */
    @Test
    public void shouldShiftEachAxisOnce_whenTwoPositionsOverflowTheTopLeftOnDifferentAxes() {
        String[] ids = oneGroupTwoChildren();
        String g = ids[0], a = ids[1], b = ids[2];

        apply(pos(a, -50, 10), pos(b, 10, -40));

        assertBounds("child A lands where it was put", find(view, a), -50, 10, 100, 50);
        assertBounds("child B lands where it was put", find(view, b), 10, -40, 100, 50);
        // x: 700 + (-50 - 10) = 640, width 300 + 60 = 360.  y: 250 + (-40 - 10) = 200, height 200 + 50 = 250.
        assertBounds("each axis shifts once, by the overflow that axis's own entry demanded",
                find(view, g), 640, 200, 360, 250);
    }

    // ---- Defect B1: the group an earlier queued operation already grew -------------------------

    /**
     * Inside an open batch, an earlier operation grows the group; a later {@code apply-positions}
     * must measure it at the size the batch has queued, not at its pre-batch size.
     *
     * <p>The fixture is built BEFORE the batch opens, so what the batch queues is the only pending
     * state in play. The queued grow takes the group to 800x700; the position then needs 610x70,
     * which fits inside that. Measuring against the pre-batch 300x200 instead finds an overflow that
     * is not there and emits a resize which — executing after the queued grow — shrinks the group
     * back on BOTH axes.</p>
     */
    @Test
    public void shouldKeepTheQueuedGroupSize_whenAnEarlierBatchOperationAlreadyGrewIt() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String child = element(10, 10, 100, 50, g);

        dispatcher.beginBatch(SESSION, "grow the group, then lay out inside it");
        update(g, null, null, 800, 700);
        apply(pos(child, 500, 10));
        dispatcher.endBatch(SESSION, true);

        assertBounds("the child lands where it was put", find(view, child), 500, 10, 100, 50);
        assertBounds("the group keeps the size the earlier queued operation gave it",
                find(view, g), GROUP_X, GROUP_Y, 800, 700);
    }

    // ---- Defect B2: the grandparent-and-above ancestor walk ------------------------------------

    /**
     * A child inside a queued group inside a queued group. The immediate container is already found
     * through the pre-resolved queued view object, so what this pin is about is the hop ABOVE it:
     * the intermediate group is still detached at prepare time, its {@code eContainer()} is null,
     * and without the batch's queued containment the walk stops there and the outer group goes
     * unmeasured.
     */
    @Test
    public void shouldFitTheGrandparent_whenTheIntermediateGroupIsStillQueued() {
        dispatcher.beginBatch(SESSION, "nested queued groups then a layout");
        String outer = group("Outer", GROUP_X, GROUP_Y, 200, 200, null);
        String inner = group("Inner", 10, 10, 100, 100, outer);
        String leaf = element(5, 5, 40, 40, inner);
        apply(pos(leaf, 600, 600));
        dispatcher.endBatch(SESSION, true);

        assertBounds("the leaf lands where it was put", find(view, leaf), 600, 600, 40, 40);
        assertBounds("the inner group fits its far-pushed leaf",
                find(view, inner), 10, 10, 650, 650);
        assertBounds("the OUTER group fits the grown inner group",
                find(view, outer), GROUP_X, GROUP_Y, 670, 670);
    }

    // ---- Defect B3: anchors the batch has declared but not written -----------------------------

    /**
     * An object the batch has anchored to another must follow that other when this call repositions
     * it. The anchor is queued, not written — it is persisted by its own command at execute() — so a
     * layout prepared while the batch is open cannot see it from the model and must be handed it.
     */
    @Test
    public void shouldMoveTheAnchoredFollower_whenTheBatchOnlyQueuedTheAnchor() {
        String g = group("G", GROUP_X, GROUP_Y, 600, 600, null);
        String target = element(10, 10, 100, 50, g);
        String follower = element(10, 200, 100, 50, g);

        dispatcher.beginBatch(SESSION, "anchor then lay out the target");
        anchor(follower, target, "BOTTOM", 0, 20);
        apply(pos(target, 300, 300));
        dispatcher.endBatch(SESSION, true);

        assertBounds("the target lands where it was put", find(view, target), 300, 300, 100, 50);
        assertBounds("the follower follows the target this call moved",
                find(view, follower), 300, 370, 100, 50);
    }

    // ---- The published shape must not move -----------------------------------------------------

    /**
     * {@code totalOperations} is {@code commands.size()} and is published on the approval card. The
     * fix must not smuggle group-resize commands into that list, so the identity every path holds
     * today must still hold on a call that provokes a cascade.
     */
    @Test
    public void shouldKeepTotalOperationsEqualToTheSumOfTheTwoCounters_whenAGroupIsRefitted() {
        String[] ids = oneGroupTwoChildren();

        ApplyViewLayoutResultDto dto = apply(pos(ids[1], 500, 10), pos(ids[2], 10, 400));

        assertEquals("positions updated", 2, dto.positionsUpdated());
        assertEquals("connections updated", 0, dto.connectionsUpdated());
        assertEquals("totalOperations must stay the sum of the two counters",
                dto.positionsUpdated() + dto.connectionsUpdated(), dto.totalOperations());
    }

    /**
     * Off-batch and outside any bulk window every projection is null and the pass map starts empty,
     * so a single-entry call must behave exactly as it did before the pass map existed: the child
     * moves, the group grows to that one demand, and nothing else changes.
     */
    @Test
    public void shouldGrowTheGroupToTheSingleDemand_whenNoBatchIsOpen() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String child = element(10, 10, 100, 50, g);

        ApplyViewLayoutResultDto dto = apply(pos(child, 500, 10));

        assertBounds("the child lands where it was put", find(view, child), 500, 10, 100, 50);
        assertBounds("the group grows to the one demand and no further",
                find(view, g), GROUP_X, GROUP_Y, 610, GROUP_H);
        assertEquals("one position, one command", 1, dto.totalOperations());
    }

    // ---- Defect C: one resize per entry, where one per call would do ---------------------------

    /**
     * Three entries in one call each push the same group further right. Each is a record on its
     * own axis, so each provokes a fit — and every fit is an ABSOLUTE rectangle for the same group.
     * All but the last are overwritten the moment they execute.
     *
     * <p>The count is read off the dispatched compound, which is what the model executes and what a
     * human approves. It is deliberately not read off {@code totalOperations}: that field counts
     * entries and the resizes are nested inside them, so it is the same number either way. Nor off
     * the group's final rectangle, which is the same whether one command wrote it or three did.
     * Measured at {@code 968e3cfa} this read 3; at ten thousand entries it read 10,000.</p>
     */
    @Test
    public void shouldEmitOneCommandForTheGroup_whenThreeEntriesEachPushItFurtherOut() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String a = element(10, 10, 100, 50, g);
        String b = element(10, 80, 100, 50, g);
        String c = element(10, 140, 100, 50, g);

        apply(pos(a, 300, 10), pos(b, 500, 80), pos(c, 700, 140));

        assertEquals("the group is re-sized ONCE for the whole call, not once per entry that grew it",
                1, boundsCommandsFor(g));
        assertBounds("and the one command it gets carries what every entry demanded",
                find(view, g), GROUP_X, GROUP_Y, 810, GROUP_H);
        assertContained("child C", find(view, c), find(view, g));
    }

    /**
     * Each of the three children keeps its own single move. Consolidating the group's resizes must
     * not consolidate anything else: a call that stopped emitting per-child commands would satisfy
     * the count above while losing the layout it was asked to apply.
     */
    @Test
    public void shouldStillEmitOneCommandPerChild_whenTheGroupsResizesAreConsolidated() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String a = element(10, 10, 100, 50, g);
        String b = element(10, 80, 100, 50, g);
        String c = element(10, 140, 100, 50, g);

        apply(pos(a, 300, 10), pos(b, 500, 80), pos(c, 700, 140));

        assertEquals("child A keeps its own move", 1, boundsCommandsFor(a));
        assertEquals("child B keeps its own move", 1, boundsCommandsFor(b));
        assertEquals("child C keeps its own move", 1, boundsCommandsFor(c));
        assertBounds("child C lands where it was put", find(view, c), 700, 140, 100, 50);
    }

    /**
     * TWO independent groups grown in one call, with the entries interleaved so that the order the
     * groups are first touched is not the order they are last updated.
     *
     * <p>One consolidated command per group is a claim about a map with several keys, and a fixture
     * with one group cannot make it. It also cannot see the shape of the map: entries are re-keyed
     * in place, so a group's position in iteration order is fixed by the FIRST entry that grew it
     * while its rectangle is whatever the LAST one computed. Interleaving the two groups separates
     * those two orders, which is what makes this fixture different from running the pair twice.</p>
     */
    @Test
    public void shouldEmitOneCommandPerGroup_whenTwoGroupsAreGrownByInterleavedEntries() {
        String g1 = group("Left", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String a1 = element(10, 10, 100, 50, g1);
        String a2 = element(10, 80, 100, 50, g1);
        String g2 = group("Right", 2000, GROUP_Y, GROUP_W, GROUP_H, null);
        String b1 = element(10, 10, 100, 50, g2);
        String b2 = element(10, 80, 100, 50, g2);

        apply(pos(a1, 300, 10), pos(b1, 400, 10), pos(a2, 500, 80), pos(b2, 600, 80));

        assertEquals("the first group is re-sized once", 1, boundsCommandsFor(g1));
        assertEquals("the second group is re-sized once", 1, boundsCommandsFor(g2));
        assertBounds("the first group ends at the widest demand made of IT",
                find(view, g1), GROUP_X, GROUP_Y, 610, GROUP_H);
        assertBounds("the second group ends at the widest demand made of IT",
                find(view, g2), 2000, GROUP_Y, 710, GROUP_H);
    }

    /**
     * A group inside a group, both grown twice by two entries in one call.
     *
     * <p>Nesting is where a shared command map could go wrong in a way a pair of siblings cannot
     * show: the cascade writes the inner group and then walks up and writes the outer one, so both
     * keys are touched by every entry and the two commands land in one compound with a containment
     * relation between their targets. Both commands are absolute and name different objects, so
     * neither reads the other when it executes and the pair is order-independent — this pin is what
     * turns that from a claim into a measurement.</p>
     */
    @Test
    public void shouldEmitOneCommandPerGroup_whenTwoEntriesGrowBothANestedGroupAndItsParent() {
        String outer = group("Outer", GROUP_X, GROUP_Y, 300, 300, null);
        String inner = group("Inner", 10, 10, 200, 200, outer);
        String c = element(5, 5, 40, 40, inner);
        String d = element(5, 60, 40, 40, inner);

        apply(pos(c, 400, 5), pos(d, 5, 400));

        assertEquals("the inner group is re-sized once for the call", 1, boundsCommandsFor(inner));
        assertEquals("the outer group is re-sized once for the call", 1, boundsCommandsFor(outer));
        assertBounds("the inner group ends at what BOTH entries demanded of it",
                find(view, inner), 10, 10, 450, 450);
        assertBounds("the outer group ends fitted around the grown inner group",
                find(view, outer), GROUP_X, GROUP_Y, 470, 470);
        assertContained("the inner group", find(view, inner), find(view, outer));
        assertContained("the second child", find(view, d), find(view, inner));
    }

    /**
     * A cascade grows a group; a LATER entry in the same call names that group and asks for it
     * smaller than its children now need. The cascade wins.
     *
     * <p>This is a deliberate change of disposition. At {@code 968e3cfa} each group resize executed
     * inside the entry that provoked it, so the later explicit entry ran last and the group ended
     * at <strong>700x250x300x200</strong> with child A hanging 300px outside its right edge.
     * Consolidated, the cascade's one command executes after every entry and the group ends at
     * <strong>700x250x610x200</strong> — the caller's explicit width is overridden and containment
     * holds. That is the same ruling the spacing pass already records for its own cascade, and it
     * is the property the cascade exists for: a group left at a requested width with a child
     * outside it is a canvas that is wrong, described accurately.</p>
     */
    @Test
    public void shouldLetTheCascadeWin_whenALaterEntryAsksForTheGroupSmallerThanItsChildNeeds() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String a = element(10, 10, 100, 50, g);

        apply(pos(a, 500, 10), resize(g, GROUP_W, GROUP_H));

        assertBounds("child A lands where it was put", find(view, a), 500, 10, 100, 50);
        assertBounds("the cascade's rectangle is the one that survives",
                find(view, g), GROUP_X, GROUP_Y, 610, GROUP_H);
        assertContained("child A", find(view, a), find(view, g));
    }

    /**
     * The mirror of the pin above, and the case its ruling does NOT cover: a cascade grows a group,
     * and a later entry explicitly asks for it <em>bigger</em> than the cascade needs.
     *
     * <p>"The cascade wins" is justified by containment — a group left at a caller's width with a
     * child outside it is a wrong canvas. That justification says nothing here, because 800 already
     * contains everything 610 does. Letting the cascade's rectangle win in this direction would
     * discard an explicit request and buy nothing, so the caller's larger value must survive.</p>
     */
    @Test
    public void shouldKeepTheCallersWidth_whenALaterEntryAsksForTheGroupBiggerThanTheCascadeNeeds() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String a = element(10, 10, 100, 50, g);

        apply(pos(a, 500, 10), resize(g, 800, GROUP_H));

        assertBounds("child A lands where it was put", find(view, a), 500, 10, 100, 50);
        assertBounds("the caller's larger width is not undone by a cascade that needed less",
                find(view, g), GROUP_X, GROUP_Y, 800, GROUP_H);
        assertContained("child A", find(view, a), find(view, g));
    }

    /**
     * The boundary between the two pins above: the caller asks for EXACTLY the rectangle the
     * cascade computed.
     *
     * <p>Both rules agree on the geometry here, which is why this is asserted on the command count
     * instead. "Already satisfied" is a containment test with an inclusive edge, and an exclusive
     * one would look right on both neighbours of this case while quietly re-emitting a second,
     * identical rectangle for the group — invisible to any assertion on where the group ends up.</p>
     */
    @Test
    public void shouldStillEmitOneCommand_whenTheCallerAsksForExactlyWhatTheCascadeComputed() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String a = element(10, 10, 100, 50, g);

        apply(pos(a, 500, 10), resize(g, 610, GROUP_H));

        assertEquals("an exactly-satisfied fit is retired, not re-emitted beside the caller's own",
                1, boundsCommandsFor(g));
        assertBounds("and the group ends where both of them agree it should",
                find(view, g), GROUP_X, GROUP_Y, 610, GROUP_H);
    }

    /**
     * The same divergence one step further on, where it stops being a lost request and becomes a
     * containment violation the cascade itself causes.
     *
     * <p>An entry enlarges the group; a later entry then places a child against that larger size,
     * legitimately, so the cascade sees no overflow and emits nothing for it. If a stale cascade
     * rectangle from an earlier entry were still committed last, it would shrink the group under a
     * child that was correctly placed — the cascade breaking the invariant it exists to hold, on a
     * call that reports success.</p>
     */
    @Test
    public void shouldNotShrinkTheGroupUnderAValidlyPlacedChild_whenAnEarlierEntryGrewItLess() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String a = element(10, 10, 100, 50, g);
        String b = element(10, 80, 100, 50, g);

        apply(pos(a, 500, 10), resize(g, 800, GROUP_H), pos(b, 650, 80));

        assertBounds("the group holds the width the caller set",
                find(view, g), GROUP_X, GROUP_Y, 800, GROUP_H);
        assertContained("child A", find(view, a), find(view, g));
        assertContained("child B, placed against the caller's larger group",
                find(view, b), find(view, g));
    }

    /**
     * One valid entry that overflows its group, one entry naming an id the view does not hold. The
     * call refuses, and nothing reaches the model.
     *
     * <p>A per-entry resize map dies with the failed entry's discarded prepare. A map shared across
     * the whole call outlives it: the valid entry's resize is sitting in that map when the refusal
     * is raised. The refusal is above the compound, so nothing executes — this pin is what makes
     * that an assertion rather than an assumption.</p>
     */
    @Test
    public void shouldReachTheModelWithNothing_whenOneEntryOverflowsAndAnotherNamesAMissingId() {
        String g = group("G", GROUP_X, GROUP_Y, GROUP_W, GROUP_H, null);
        String a = element(10, 10, 100, 50, g);
        dispatched.clear();

        try {
            apply(pos(a, 500, 10), pos("no-such-view-object", 10, 10));
            fail("a position naming an id the view does not hold must refuse the whole call");
        } catch (ModelAccessException expected) {
            // the refusal is the subject of the assertions below, not of this catch
        }

        assertTrue("nothing may be dispatched when the call refuses", dispatched.isEmpty());
        assertBounds("the group keeps the size it had before the refused call",
                find(view, g), GROUP_X, GROUP_Y, GROUP_W, GROUP_H);
        assertBounds("and the valid entry's child did not move either",
                find(view, a), 10, 10, 100, 50);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override
        public List<IArchimateModel> getModels() { return models; }
        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }
        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }
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
