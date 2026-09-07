package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
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

import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.ResizedGroupDto;

/**
 * Pins what the view-wide parent-fit passes measure against when they run inside an open batch, and
 * what they are then allowed to claim they changed.
 *
 * <p>A pass that walks the live view reads {@code getBounds()}, which inside a batch still holds
 * whatever was true <em>before</em> the batch — an earlier queued operation's rectangle has not been
 * written yet. So the pass measures a shared group against its pre-batch size and emits an
 * <em>absolute</em> resize computed from it. Both commands land in the same commit, the later one
 * wins, and the size the caller explicitly asked for in the same batch is silently discarded.
 * Measured before the seed existed: a group queued at 900x700 committed at 200x385.</p>
 *
 * <h2>Why the report is pinned in the same class</h2>
 *
 * <p>Seeding the fit map makes it answer two questions at once — which groups this pass
 * <em>grew</em>, and which ones it merely <em>knew about</em>. {@code auto-route-connections}
 * already projects that map into {@code resizedGroups}, so seeding it without excluding the seed
 * would name every group the batch had established as one this routing pass resized: a false
 * statement about the model, dressed as a measurement, in a tool whose report is otherwise
 * asserted. The negative below is therefore not optional coverage; it is the other half of the
 * change.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>The headless idiom the other batch classes use: a real GEF {@link CommandStack} driven over an
 * ordered compound, with the production {@code NonNotifyingCompoundCommand} rebuilt as a plain
 * {@link CompoundCommand} because its {@code execute()} dereferences
 * {@code IEditorModelManager.INSTANCE}. {@code undo} is overridden to pop that stack directly, as
 * the production path marshals it onto the SWT display. Every add passes explicit bounds so nothing
 * reaches {@code ElementSizer}'s display-bound measurement.</p>
 */
public class BatchFitMapSeedingTest {

    private static final String SESSION = "batch-fit-map-seeding-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Batch Fit Map Fixture");
        model.setId("model-batch-fit-map");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Seeding");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 4; i++) {
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

    /**
     * A group holding two connected children, the second placed well past the group's bottom edge:
     * the connection gives the routing pass something to route, and the overflow is what makes the
     * parent-fit pass want to grow the group.
     */
    private String overflowingGroup(int w, int h) {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "G", 0, 0, w, h,
                null, null, null).entity().viewObjectId();
        String src = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 120, 55, false, groupId, null, null).entity().viewObject().viewObjectId();
        String tgt = accessor.addToView(SESSION, view.getId(), "actor-2",
                20, 320, 120, 55, false, groupId, null, null).entity().viewObject().viewObjectId();
        accessor.addConnectionToView(SESSION, view.getId(), "rel-1", src, tgt,
                null, null, null, null, null);
        return groupId;
    }

    /** force MUST stay false: the routing pass computes autoNudge as (autoNudge and not force). */
    private AutoRouteResultDto route() {
        return accessor.autoRouteConnections(SESSION, view.getId(), null, null,
                false, true, 0, 0, null).entity();
    }

    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId())) {
                return child;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static String box(IDiagramModelObject o) {
        IBounds b = o.getBounds();
        return b.getX() + "," + b.getY() + " " + b.getWidth() + "x" + b.getHeight();
    }

    // ---- the defect ----------------------------------------------------------------------------

    /**
     * The clobber itself. One batch, two prepares: the caller sizes a group explicitly, then runs a
     * routing pass over the same view. Without the seed the routing pass measures the group at its
     * pre-batch 200x200, emits an absolute resize to 200x385, and that command — queued second —
     * silently overwrites the 900x700 the caller asked for.
     */
    @Test
    public void shouldKeepTheGroupSizeTheBatchQueued_whenAutoRouteRunsInTheSameBatch()
            throws Exception {
        String groupId = overflowingGroup(200, 200);
        assertEquals("fixture starts at the pre-batch size", "0,0 200x200",
                box(find(view, groupId)));

        dispatcher.beginBatch(SESSION, "size the group, then route the same view");
        accessor.updateViewObject(SESSION, groupId, null, null, 900, 700, null, null, null, null);
        route();
        dispatcher.endBatch(SESSION, true);

        assertEquals("the size the batch explicitly queued must survive the routing pass — the "
                + "pass measures against it rather than against the pre-batch rectangle",
                "0,0 900x700", box(find(view, groupId)));
    }

    /**
     * The other half: having measured against the queued size, the pass must not then claim it
     * resized the group. Nothing about the group changed in this pass — the batch had already sized
     * it — so an entry naming it would be a fabricated outcome in a field the agent is meant to act
     * on.
     */
    @Test
    public void shouldNotNameAGroupTheBatchMerelyEstablished_whenTheRoutingPassDidNotGrowIt()
            throws Exception {
        String groupId = overflowingGroup(200, 200);

        dispatcher.beginBatch(SESSION, "size the group, then route the same view");
        accessor.updateViewObject(SESSION, groupId, null, null, 900, 700, null, null, null, null);
        AutoRouteResultDto routed = route();
        dispatcher.endBatch(SESSION, true);

        assertNotNull("auto-route must always return a resizedGroups list", routed.resizedGroups());
        assertTrue("the routing pass grew nothing — the group was already 900x700 by the batch's "
                + "own doing — so it must name nothing. Reported: " + routed.resizedGroups(),
                routed.resizedGroups().isEmpty());
    }

    /**
     * ...and the exclusion must not become a blanket mute. When the size the batch queued is still
     * too small for the children, the pass genuinely does grow the group, and must say so with the
     * rectangle it grew to.
     */
    @Test
    public void shouldStillNameTheGroupItGrew_whenTheQueuedSizeIsStillTooSmall() throws Exception {
        String groupId = overflowingGroup(200, 200);

        dispatcher.beginBatch(SESSION, "size the group too small, then route the same view");
        accessor.updateViewObject(SESSION, groupId, null, null, 210, 210, null, null, null, null);
        AutoRouteResultDto routed = route();
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject live = find(view, groupId);
        assertTrue("fixture must actually make the pass grow the group past the queued 210, or "
                + "this proves nothing: " + box(live), live.getBounds().getHeight() > 210);

        assertFalse("the pass grew the group, so it must be named", routed.resizedGroups().isEmpty());
        ResizedGroupDto reported = routed.resizedGroups().get(0);
        assertEquals("the reported group", groupId, reported.viewObjectId());
        assertEquals("reported x must be the x the model holds",
                live.getBounds().getX(), reported.newX());
        assertEquals("reported y must be the y the model holds",
                live.getBounds().getY(), reported.newY());
        assertEquals("reported width must be the width the model holds",
                live.getBounds().getWidth(), reported.newWidth());
        assertEquals("reported height must be the height the model holds",
                live.getBounds().getHeight(), reported.newHeight());
    }

    /**
     * Outside a batch there is nothing to seed from, so the map starts empty and every entry in it
     * is something this pass put there. The pre-seed behaviour, unchanged.
     */
    @Test
    public void shouldGrowAndReportExactlyAsBefore_whenNoBatchIsOpen() throws Exception {
        String groupId = overflowingGroup(200, 200);

        AutoRouteResultDto routed = route();

        assertEquals("the pass grows the group to clear its overflowing child",
                "0,0 200x385", box(find(view, groupId)));
        assertEquals("and names exactly it", 1, routed.resizedGroups().size());
        assertEquals(groupId, routed.resizedGroups().get(0).viewObjectId());
        assertEquals(385, routed.resizedGroups().get(0).newHeight());
    }

    /**
     * The other writer on the {@code adjust-view-spacing} path, now measuring against the same map.
     *
     * <p>Seeding the fit map was never enough here, because the fit map is not the only writer of
     * group geometry on that path: the spacing pass re-fits every group it inflates, directly and
     * absolutely, from a pre-batch {@code getBounds()}, and it runs <em>before</em> the seeded
     * cascade — which then finds nothing left to grow and emits nothing. A group sized by the batch
     * and then spaced in the same batch used to commit at 220x144 against a queued 900x700: the
     * arithmetic of the re-fit itself (10 + 200 + 10 wide, 10 + 24 + 100 + 10 tall), not a stale
     * map. The re-fit now takes its rectangle from what the batch queued, so the explicit size
     * survives a spacing pass that would otherwise have shrunk the group around its children.</p>
     */
    @Test
    public void shouldKeepTheQueuedSize_whenTheSpacingPassRefitsTheGroupItself()
            throws Exception {
        String spaced = accessor.addGroupToView(SESSION, view.getId(), "Spaced", 0, 0, 260, 200,
                null, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-1", 10, 30, 60, 30, false, spaced, null, null);
        accessor.addToView(SESSION, view.getId(), "actor-2", 90, 30, 60, 30, false, spaced, null, null);

        String g = accessor.addGroupToView(SESSION, view.getId(), "G", 600, 0, 100, 100,
                null, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-3", 10, 10, 200, 100, false, g, null, null);

        dispatcher.beginBatch(SESSION, "size G, then space the view");
        accessor.updateViewObject(SESSION, g, null, null, 900, 700, null, null, null, null);
        accessor.adjustViewSpacing(SESSION, view.getId(), 60, null, null, false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the spacing tool's own group re-fit measures against the rectangle the batch "
                + "queued, so the explicit size survives the pass that would have shrunk it",
                "600,0 900x700", box(find(view, g)));
    }

    /**
     * The layout pass reads that same map for a <em>follower's own rectangle</em>, and a note is the
     * only object on a laid-out view that can expose it.
     *
     * <p>The parent-fit reading above cannot fail here: the layout collector emits a container
     * before its children, and every entry it prepares supplies all four dimensions, so each id the
     * pass touches has its seeded value overwritten by the pass's own rectangle before any
     * descendant measures against it. The reachable read is the other one — the cascade that moves
     * objects anchored to a target whose bounds are changing takes the follower's width and height
     * from this map, and writes them back in the move it emits.</p>
     *
     * <p>A note is never a layout node ("Notes are not laid out"), so the pass emits no command of
     * its own for it and the anchored move is the last writer. Inside a batch that move must carry
     * the size the batch gave the note; measuring it against the pre-batch rectangle instead would
     * reinstate a width the caller had already replaced, in a command queued after the caller's
     * own. The anchor is set on the left edge deliberately: that is one of the two edges whose
     * position formula reads the follower's own width, so a stale read moves the note as well as
     * resizing it.</p>
     *
     * <h2>Why this asserts the note's POSITION and not only its size</h2>
     *
     * <p>Asserting the committed width alone would be a half-pin, and measurably so: the note's own
     * queued update already sets that width, so a regression that stops the cascade emitting a move
     * for notes <em>at all</em> leaves the width correct and passes. Position is the discriminating
     * axis. The note's own update supplies no x or y and names no anchor target, and the merge only
     * re-resolves an anchor when the target is passed as a parameter — so its x is untouched by its
     * own command, and the anchored move is the only writer that can change it. Anchoring on the
     * left then makes that x a function of the follower's width: seating the note against the
     * target's committed left edge is <em>x = targetX - width</em>, which is wrong by the
     * difference between the queued and pre-batch widths if the map is not seeded, and unchanged
     * from its pre-batch value if no move is emitted. One assertion, both failure modes.</p>
     *
     * <p>The second element and the connection are not decoration: they give the layout pass a
     * reason to move the anchor target, which is what provokes the cascade in the first place.</p>
     */
    @Test
    public void shouldKeepTheNoteWidthTheBatchQueued_whenALayoutPassMovesItsAnchorTarget()
            throws Exception {
        final int preBatchWidth = 200;
        final int queuedWidth = 340;
        final int queuedHeight = 80;

        String target = accessor.addToView(SESSION, view.getId(), "actor-1",
                400, 300, 120, 55, false, null, null, null).entity().viewObject().viewObjectId();
        String other = accessor.addToView(SESSION, view.getId(), "actor-2",
                800, 640, 120, 55, false, null, null, null).entity().viewObject().viewObjectId();
        accessor.addConnectionToView(SESSION, view.getId(), "rel-1", target, other,
                null, null, null, null, null);
        String note = accessor.addNoteToView(SESSION, view.getId(), "Anchored", null, null,
                0, 0, preBatchWidth, queuedHeight, null, null, null).entity().viewObjectId();
        accessor.updateViewObject(SESSION, note, null, null, null, null, null, null, null, null,
                target, "left", 0, 0);

        String targetBefore = box(find(view, target));
        IBounds targetSeated = find(view, target).getBounds();
        IBounds noteSeated = find(view, note).getBounds();
        int noteXBefore = noteSeated.getX();
        assertEquals("the anchor seats the note flush against its target's left edge, so its x is "
                + "the target's x less its own width",
                targetSeated.getX() - preBatchWidth, noteXBefore);
        assertEquals("...and level with the target's top edge",
                targetSeated.getY(), noteSeated.getY());

        dispatcher.beginBatch(SESSION, "widen the note, then lay out the same view");
        accessor.updateViewObject(SESSION, note, null, null, queuedWidth, queuedHeight,
                null, null, null, null);
        accessor.autoLayoutAndRoute(SESSION, view.getId(), "flat", "DOWN", 50, null, null);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject liveTarget = find(view, target);
        IDiagramModelObject liveNote = find(view, note);
        IBounds t = liveTarget.getBounds();
        IBounds n = liveNote.getBounds();

        assertNotEquals("fixture guard: the layout pass must actually move the anchor target, or "
                + "the anchored move this pins is never emitted at all",
                targetBefore, box(liveTarget));
        assertNotEquals("fixture guard: the anchored move must actually have run. The note's own "
                + "queued update supplies no x and names no anchor target, so it cannot move the "
                + "note — an unchanged x means no move was emitted and everything below is "
                + "vacuous. Note committed at " + box(liveNote),
                noteXBefore, n.getX());

        assertEquals("the anchored move must seat the note against the target's committed left "
                + "edge using the width the BATCH queued, not the pre-batch width it is about to "
                + "stop having. Target at " + box(liveTarget) + ", note at " + box(liveNote),
                t.getX() - queuedWidth, n.getX());
        assertEquals("and level with the target, as the left edge places it",
                t.getY(), n.getY());
        assertEquals("the width the batch queued must survive the move written back for the note",
                queuedWidth, n.getWidth());
        assertEquals("and so must the height",
                queuedHeight, n.getHeight());
    }

    /**
     * The same read, on the other edge that consumes it — and the other dimension.
     *
     * <p>One follower rectangle comes out of that map, and both of the size-reading edges are
     * served by it: {@code left} subtracts the follower's width, {@code above} subtracts its
     * height. A pin on one of them certifies one of them. This is the height-carrying half, so a
     * change that seeded the width correctly and left the height reading against the pre-batch
     * rectangle cannot pass both.</p>
     *
     * <p>The queued update passes an explicit height, which is also what keeps the note's own
     * height re-fit — a display-bound text measurement — out of the picture.</p>
     */
    @Test
    public void shouldKeepTheNoteHeightTheBatchQueued_whenALayoutPassMovesATargetItSitsAbove()
            throws Exception {
        final int preBatchHeight = 80;
        final int queuedHeight = 200;

        String target = accessor.addToView(SESSION, view.getId(), "actor-1",
                400, 300, 120, 55, false, null, null, null).entity().viewObject().viewObjectId();
        String other = accessor.addToView(SESSION, view.getId(), "actor-2",
                800, 640, 120, 55, false, null, null, null).entity().viewObject().viewObjectId();
        accessor.addConnectionToView(SESSION, view.getId(), "rel-1", target, other,
                null, null, null, null, null);
        String note = accessor.addNoteToView(SESSION, view.getId(), "Above", null, null,
                0, 0, 200, preBatchHeight, null, null, null).entity().viewObjectId();
        accessor.updateViewObject(SESSION, note, null, null, null, null, null, null, null, null,
                target, "above", 0, 0);

        String targetBefore = box(find(view, target));
        IBounds targetSeated = find(view, target).getBounds();
        int noteYBefore = find(view, note).getBounds().getY();
        assertEquals("the anchor seats the note directly above its target, so its y is the "
                + "target's y less its own height",
                targetSeated.getY() - preBatchHeight, noteYBefore);

        dispatcher.beginBatch(SESSION, "grow the note, then lay out the same view");
        accessor.updateViewObject(SESSION, note, null, null, null, queuedHeight,
                null, null, null, null);
        accessor.autoLayoutAndRoute(SESSION, view.getId(), "flat", "DOWN", 50, null, null);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject liveTarget = find(view, target);
        IDiagramModelObject liveNote = find(view, note);
        IBounds t = liveTarget.getBounds();
        IBounds n = liveNote.getBounds();

        assertNotEquals("fixture guard: the layout pass must actually move the anchor target",
                targetBefore, box(liveTarget));
        assertNotEquals("fixture guard: the anchored move must actually have run — the note's own "
                + "queued update supplies no y, so an unchanged y means no move was emitted. "
                + "Note committed at " + box(liveNote),
                noteYBefore, n.getY());

        assertEquals("the anchored move must sit the note on the target's committed top edge using "
                + "the height the BATCH queued, not the pre-batch height. Target at "
                + box(liveTarget) + ", note at " + box(liveNote),
                t.getY() - queuedHeight, n.getY());
        assertEquals("and aligned to the target's left, as the above edge places it",
                t.getX(), n.getX());
        assertEquals("the height the batch queued must survive the move written back for the note",
                queuedHeight, n.getHeight());
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
