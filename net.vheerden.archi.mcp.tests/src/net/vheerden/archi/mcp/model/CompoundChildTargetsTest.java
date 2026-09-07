package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;

/**
 * Headless coverage of the compound child-id extractor (disposition (a)). Builds the
 * project-owned commands the layout/route gate-sites assemble — {@link UpdateViewObjectCommand},
 * {@link UpdateViewConnectionCommand}, {@link SetTextPositionCommand}, {@link AddConnectionToViewCommand} —
 * over real EMF fixtures (no model instantiation, no {@code Display}) and asserts
 * {@link CompoundChildTargets#collect} recovers exactly the pre-existing object ids each touches.
 */
public class CompoundChildTargetsTest {

    private static final IArchimateFactory FACTORY = IArchimateFactory.eINSTANCE;

    // ---- layout site: UpdateViewObjectCommand → diagram-object ids ----

    @Test
    public void shouldUnionAnchorAndTouchedObjectIds_forLayoutCompound() {
        CompoundCommand compound = new CompoundCommand();
        compound.add(new UpdateViewObjectCommand(dmo("d1"), 10, 10, 100, 50));
        compound.add(new UpdateViewObjectCommand(dmo("d2"), 20, 20, 100, 50));

        Set<String> ids = CompoundChildTargets.collect(compound, "view-1");

        assertEquals(Set.of("view-1", "d1", "d2"), ids);
    }

    @Test
    public void shouldKeepMultipleAnchors_forLayoutWithinGroup() {
        CompoundCommand compound = new CompoundCommand();
        compound.add(new UpdateViewObjectCommand(dmo("child"), 0, 0, 80, 40));

        Set<String> ids = CompoundChildTargets.collect(compound, "view-1", "group-1");

        assertEquals(Set.of("view-1", "group-1", "child"), ids);
    }

    // ---- route sites: connection ids ----

    @Test
    public void shouldTrackConnectionId_forUpdateViewConnectionCommand() {
        CompoundCommand compound = new CompoundCommand();
        compound.add(new UpdateViewConnectionCommand(conn("c1"), List.of()));

        assertTrue(CompoundChildTargets.collect(compound, "view-1").contains("c1"));
    }

    @Test
    public void shouldTrackConnectionId_forSetTextPositionCommand() {
        CompoundCommand compound = new CompoundCommand();
        compound.add(new SetTextPositionCommand(conn("c2"), 1));

        assertTrue(CompoundChildTargets.collect(compound, "view-1").contains("c2"));
    }

    @Test
    public void shouldTrackConnectionId_forSetTextRelativePositionCommand() {
        CompoundCommand compound = new CompoundCommand();
        compound.add(new SetTextRelativePositionCommand(conn("c3"), 16)); // EAST

        assertTrue(CompoundChildTargets.collect(compound, "view-1").contains("c3"));
    }

    // ---- auto-connect: track pre-existing endpoints, NOT the created connection ----

    @Test
    public void shouldTrackEndpoints_andSkipCreatedConnection_forAddConnection() {
        IDiagramModelArchimateObject src = dmo("src");
        IDiagramModelArchimateObject tgt = dmo("tgt");
        CompoundCommand compound = new CompoundCommand();
        compound.add(new AddConnectionToViewCommand(conn("new-conn"), src, tgt));

        Set<String> ids = CompoundChildTargets.collect(compound, "view-1");

        assertTrue("source endpoint tracked", ids.contains("src"));
        assertTrue("target endpoint tracked", ids.contains("tgt"));
        assertFalse("a being-created connection is not yet resolvable (B19) — not tracked",
                ids.contains("new-conn"));
    }

    // ---- placement: track the container being placed INTO, not the object being placed ----
    //
    // The object does not exist in the model at propose time, so its id resolves nowhere and the
    // guard would drop it. The container does resolve, and it is the thing an approval can outlive
    // — deleted by a human during review, or queued by an enclosing batch that then rolled back.

    @Test
    public void shouldTrackTheTargetContainer_forAddToViewCommand() {
        IDiagramModelGroup parent = group("parent-group");
        CompoundCommand compound = new CompoundCommand();
        compound.add(new AddToViewCommand(placeable("placed-object"), parent));

        Set<String> ids = CompoundChildTargets.collect(compound, "view-1");

        assertTrue("the container the object is placed into is tracked",
                ids.contains("parent-group"));
        assertFalse("the object being created is not yet resolvable — not tracked",
                ids.contains("placed-object"));
    }

    @Test
    public void shouldTrackTheTargetContainer_forAddGroupToViewCommand() {
        IDiagramModelGroup parent = group("outer-group");
        CompoundCommand compound = new CompoundCommand();
        compound.add(new AddGroupToViewCommand(group("inner-group"), parent));

        Set<String> ids = CompoundChildTargets.collect(compound, "view-1");

        assertTrue("the parent container is tracked", ids.contains("outer-group"));
        assertFalse("the group being created is not yet resolvable — not tracked",
                ids.contains("inner-group"));
    }

    @Test
    public void shouldTrackTheTargetContainer_forAddNoteToViewCommand() {
        IDiagramModelGroup parent = group("note-host");
        IDiagramModelNote note = FACTORY.createDiagramModelNote();
        note.setId("new-note");
        CompoundCommand compound = new CompoundCommand();
        compound.add(new AddNoteToViewCommand(note, parent));

        Set<String> ids = CompoundChildTargets.collect(compound, "view-1");

        assertTrue("the parent container is tracked", ids.contains("note-host"));
        assertFalse("the note being created is not yet resolvable — not tracked",
                ids.contains("new-note"));
    }

    /**
     * A placement is rarely a bare add — the group and note paths wrap theirs in a placement guard,
     * and the element path builds a compound when it also has fill-recession or auto-connect work to
     * do. Without the recursion the ladder sees a {@code CompoundCommand}, matches no arm, and skips
     * the add inside it: the tracked set comes back holding only the anchors, which is exactly the
     * empty-guard shape this whole extractor exists to prevent.
     */
    @Test
    public void shouldWalkNestedCompounds_soAWrappedPlacementIsNotSkipped() {
        IDiagramModelGroup parent = group("wrapped-host");
        CompoundCommand inner = new CompoundCommand();
        inner.add(new AddToViewCommand(placeable("wrapped-object"), parent));
        CompoundCommand outer = new CompoundCommand();
        outer.add(inner);

        Set<String> ids = CompoundChildTargets.collect(outer, "view-1");

        assertTrue("a placement one level down must still be seen",
                ids.contains("wrapped-host"));
    }

    /**
     * The recursion's OTHER consequence, which is not about placement at all and was missed when
     * only the three placement arms were measured.
     *
     * <p>{@code auto-connect-view} wraps every connection it creates in a placement guard —
     * {@code RequireAttachedContainerCommand}, itself a {@link CompoundCommand}. Before the
     * recursion existed those wrappers matched no arm, so the tool's tracked set was its view id and
     * nothing else: the {@code AddConnectionToViewCommand} arm below it could never fire, and a
     * human deleting one of the elements being connected did not reject-stale the frozen compound.
     * The recursion reaches through the wrapper and the endpoints are now tracked, which is what
     * the single-tool {@code add-connection-to-view} has always done.</p>
     *
     * <p>Pinned as its own case because it is a behaviour change to a second tool, reached by a
     * different route than the placement arms, and nothing else here would have caught it.</p>
     */
    @Test
    public void shouldTrackConnectionEndpoints_whenTheAddIsWrappedInAPlacementGuard() {
        IDiagramModelArchimateObject src = dmo("guarded-src");
        IDiagramModelArchimateObject tgt = dmo("guarded-tgt");
        CompoundCommand guard = new CompoundCommand();
        guard.add(new AddConnectionToViewCommand(conn("guarded-conn"), src, tgt));
        CompoundCommand compound = new CompoundCommand();
        compound.add(guard);

        Set<String> ids = CompoundChildTargets.collect(compound, "view-1");

        assertTrue("a wrapped connection's source endpoint must still be tracked",
                ids.contains("guarded-src"));
        assertTrue("and its target endpoint", ids.contains("guarded-tgt"));
        assertFalse("the connection being created is still not resolvable",
                ids.contains("guarded-conn"));
    }

    /** Two levels down, so the recursion is proven recursive rather than merely one-deep. */
    @Test
    public void shouldWalkCompoundsMoreThanOneLevelDeep() {
        CompoundCommand deepest = new CompoundCommand();
        deepest.add(new UpdateViewObjectCommand(dmo("deep-object"), 0, 0, 10, 10));
        CompoundCommand middle = new CompoundCommand();
        middle.add(deepest);
        CompoundCommand outer = new CompoundCommand();
        outer.add(middle);

        assertTrue(CompoundChildTargets.collect(outer, "view-1").contains("deep-object"));
    }

    // ---- edge cases ----

    @Test
    public void shouldReturnAnchorsOnly_whenCompoundNull() {
        assertEquals(Set.of("view-1"), CompoundChildTargets.collect(null, "view-1"));
    }

    @Test
    public void shouldFilterNullAndBlankAnchors() {
        assertEquals(Set.of("view-1"),
                CompoundChildTargets.collect(new CompoundCommand(), "view-1", null, "  "));
    }

    // ---- fixtures ----

    private static IDiagramModelArchimateObject dmo(String id) {
        IDiagramModelArchimateObject o = FACTORY.createDiagramModelArchimateObject();
        o.setId(id);
        o.setBounds(0, 0, 100, 50);
        return o;
    }

    /**
     * A diagram object carrying a real ArchiMate element. {@code AddToViewCommand}'s constructor
     * builds its undo label from {@code getArchimateElement().eClass()}, so the bare {@link #dmo}
     * fixture throws there — and a pin that dies in its own fixture proves nothing about the code
     * under test.
     */
    private static IDiagramModelArchimateObject placeable(String id) {
        IBusinessActor actor = FACTORY.createBusinessActor();
        actor.setId(id + "-element");
        actor.setName("Placed");
        IDiagramModelArchimateObject o = dmo(id);
        o.setArchimateElement(actor);
        return o;
    }

    private static IDiagramModelGroup group(String id) {
        IDiagramModelGroup g = FACTORY.createDiagramModelGroup();
        g.setId(id);
        g.setBounds(0, 0, 400, 400);
        return g;
    }

    private static IDiagramModelArchimateConnection conn(String id) {
        IDiagramModelArchimateConnection c = FACTORY.createDiagramModelArchimateConnection();
        c.setId(id);
        return c;
    }
}
