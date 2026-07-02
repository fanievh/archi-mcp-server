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
import java.util.Collections;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IApplicationComponent;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.DeleteResultDto;

/**
 * Forensic characterization + regression pins for deleting a model concept
 * (element / relationship) or a view that appears across MULTIPLE diagram
 * views, including via compound (folder-force and bulk) operations.
 *
 * <p>Each test drives the REAL accessor cascade-discovery path
 * ({@code prepareDeleteElement / prepareDeleteRelationship / prepareDeleteFolder})
 * against a real EMF model, then executes / undoes / redoes the prepared GEF
 * command directly. This exercises the all-views folder walk
 * ({@code discoverCascadeInFolder}, {@code discoverViewConnectionsInFolder})
 * and the folder-force sub-command builder
 * ({@code buildFolderDeleteSubCommands}) end-to-end without an OSGi
 * {@code CommandStack}. The dangling-cross-reference save/reload gate lives in
 * the companion {@code MultiViewCompoundDeleteCascadeIntegrationTest}.</p>
 */
public class MultiViewDeleteCascadeTest {

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private IArchimateModel model;
    private IFolder diagrams;
    private IArchimateDiagramModel viewA;
    private IArchimateDiagramModel viewB;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Multi-View Delete Fixture");
        model.setId("model-multi-view-delete");
        model.setDefaults();
        diagrams = model.getFolder(FolderType.DIAGRAMS);

        viewA = factory.createArchimateDiagramModel();
        viewA.setId("view-a");
        viewA.setName("View A");
        diagrams.getElements().add(viewA);

        viewB = factory.createArchimateDiagramModel();
        viewB.setId("view-b");
        viewB.setName("View B");
        diagrams.getElements().add(viewB);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers ----

    private IBusinessActor addActor(String id, String name) {
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId(id);
        actor.setName(name);
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        return actor;
    }

    private IApplicationComponent addComponent(String id, String name) {
        IApplicationComponent comp = factory.createApplicationComponent();
        comp.setId(id);
        comp.setName(name);
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);
        return comp;
    }

    private IAssociationRelationship addAssociation(String id,
            com.archimatetool.model.IArchimateConcept source,
            com.archimatetool.model.IArchimateConcept target) {
        IAssociationRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    /** Adds {@code element} to {@code view} as a diagram object and returns it. */
    private IDiagramModelArchimateObject place(IArchimateDiagramModel view,
            com.archimatetool.model.IArchimateElement element, String id,
            int x, int y) {
        IDiagramModelArchimateObject dmo = factory.createDiagramModelArchimateObject();
        dmo.setId(id);
        dmo.setArchimateElement(element);
        dmo.setBounds(x, y, 120, 55);
        view.getChildren().add(dmo);
        return dmo;
    }

    /** Adds a visual connection for {@code rel} between two diagram objects. */
    private IDiagramModelArchimateConnection connect(IArchimateRelationship rel,
            IDiagramModelArchimateObject src, IDiagramModelArchimateObject tgt,
            String id) {
        IDiagramModelArchimateConnection conn =
                factory.createDiagramModelArchimateConnection();
        conn.setId(id);
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
        return conn;
    }

    // ---- element delete: cascade across two views ----

    @Test
    public void shouldRemoveElementFromAllViews_whenDeletedAcrossTwoViews() {
        IBusinessActor actor = addActor("actor-1", "Shared Actor");
        IDiagramModelArchimateObject inA = place(viewA, actor, "dmo-a", 10, 10);
        IDiagramModelArchimateObject inB = place(viewB, actor, "dmo-b", 20, 20);

        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteElement(actor.getId());
        // Both diagram objects are discovered across both views.
        assertEquals("Cascade discovered the element in two views",
                2, prepared.entity().viewReferencesRemoved());

        Command cmd = prepared.command();
        cmd.execute();
        assertFalse("Element removed from its folder",
                model.getFolder(FolderType.BUSINESS).getElements().contains(actor));
        assertFalse("Diagram object removed from view A", viewA.getChildren().contains(inA));
        assertFalse("Diagram object removed from view B", viewB.getChildren().contains(inB));

        cmd.undo();
        assertTrue("Element restored", model.getFolder(FolderType.BUSINESS)
                .getElements().contains(actor));
        assertTrue("Diagram object restored to view A", viewA.getChildren().contains(inA));
        assertTrue("Diagram object restored to view B", viewB.getChildren().contains(inB));

        cmd.redo();
        assertFalse("Diagram object removed from view A on redo",
                viewA.getChildren().contains(inA));
        assertFalse("Diagram object removed from view B on redo",
                viewB.getChildren().contains(inB));
    }

    // ---- relationship delete: connection cascade across two views ----

    @Test
    public void shouldRemoveRelationshipConnectionsFromAllViews_whenDeletedAcrossTwoViews() {
        IBusinessActor a1 = addActor("actor-a", "A");
        IApplicationComponent c1 = addComponent("comp-c", "C");
        IAssociationRelationship rel = addAssociation("rel-1", a1, c1);

        IDiagramModelArchimateObject a1InA = place(viewA, a1, "a1-a", 10, 10);
        IDiagramModelArchimateObject c1InA = place(viewA, c1, "c1-a", 200, 10);
        IDiagramModelArchimateConnection connInA = connect(rel, a1InA, c1InA, "conn-a");

        IDiagramModelArchimateObject a1InB = place(viewB, a1, "a1-b", 10, 10);
        IDiagramModelArchimateObject c1InB = place(viewB, c1, "c1-b", 200, 10);
        IDiagramModelArchimateConnection connInB = connect(rel, a1InB, c1InB, "conn-b");

        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteRelationship(rel.getId());
        assertEquals("Cascade discovered the connection in two views",
                2, prepared.entity().viewConnectionsRemoved());

        Command cmd = prepared.command();
        cmd.execute();
        assertFalse("Relationship removed from its folder",
                model.getFolder(FolderType.RELATIONS).getElements().contains(rel));
        assertTrue("Connection removed from view A source",
                a1InA.getSourceConnections().isEmpty());
        assertTrue("Connection removed from view B source",
                a1InB.getSourceConnections().isEmpty());

        cmd.undo();
        assertTrue("Relationship restored", model.getFolder(FolderType.RELATIONS)
                .getElements().contains(rel));
        assertTrue("Connection restored in view A",
                a1InA.getSourceConnections().contains(connInA));
        assertTrue("Connection restored in view B",
                a1InB.getSourceConnections().contains(connInB));
    }

    // ---- folder-force delete: cascade a placeholder in a sibling view ----

    @Test
    public void shouldCascadeSiblingPlaceholder_whenFolderForceDeletesReferencedView() {
        // User subfolder under DIAGRAMS holding the referenced view.
        IFolder sub = factory.createFolder();
        sub.setId("folder-sub");
        sub.setName("Subfolder");
        diagrams.getFolders().add(sub);

        IArchimateDiagramModel referenced = factory.createArchimateDiagramModel();
        referenced.setId("view-ref");
        referenced.setName("Referenced View");
        sub.getElements().add(referenced);

        // Sibling placeholder lives in view A, OUTSIDE the deleted folder.
        IDiagramModelReference placeholder = factory.createDiagramModelReference();
        placeholder.setReferencedModel(referenced);
        placeholder.setBounds(0, 0, 185, 80);
        viewA.getChildren().add(placeholder);

        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteFolder(sub.getId(), true);
        Command cmd = prepared.command();
        cmd.execute();

        assertFalse("Subfolder removed", diagrams.getFolders().contains(sub));
        assertFalse("Sibling placeholder cascaded out of view A "
                + "(folder-force routes the contained view through DeleteViewCommand)",
                viewA.getChildren().contains(placeholder));

        cmd.undo();
        assertTrue("Subfolder restored on undo", diagrams.getFolders().contains(sub));
        assertTrue("Sibling placeholder restored on undo",
                viewA.getChildren().contains(placeholder));
    }

    // ---- bulk compound delete: atomicity + reversibility across views ----

    /**
     * Snapshot of every view's child + connection multiplicities and folder
     * contents. When {@code ordered} is false, per-view child lists and folder
     * lists are sorted so the snapshot captures membership + multiplicity but
     * NOT positional order (Z-order).
     */
    private String snapshot(boolean ordered) {
        StringBuilder sb = new StringBuilder();
        sb.append("viewA.children=").append(maybeSort(idList(viewA), ordered)).append('\n');
        sb.append("viewB.children=").append(maybeSort(idList(viewB), ordered)).append('\n');
        sb.append("business=").append(maybeSort(folderIds(FolderType.BUSINESS), ordered)).append('\n');
        sb.append("application=").append(maybeSort(folderIds(FolderType.APPLICATION), ordered)).append('\n');
        sb.append("relations=").append(maybeSort(folderIds(FolderType.RELATIONS), ordered)).append('\n');
        return sb.toString();
    }

    private List<String> maybeSort(List<String> in, boolean ordered) {
        if (!ordered) {
            Collections.sort(in);
        }
        return in;
    }

    private List<String> idList(IArchimateDiagramModel view) {
        List<String> ids = new ArrayList<>();
        for (Object child : view.getChildren()) {
            com.archimatetool.model.IDiagramModelObject dmo =
                    (com.archimatetool.model.IDiagramModelObject) child;
            ids.add(dmo.getId() + "#src" + dmo.getSourceConnections().size()
                    + "#tgt" + dmo.getTargetConnections().size());
        }
        return ids;
    }

    private List<String> folderIds(FolderType type) {
        List<String> ids = new ArrayList<>();
        for (Object o : model.getFolder(type).getElements()) {
            ids.add(((com.archimatetool.model.IIdentifier) o).getId());
        }
        return ids;
    }

    @Test
    public void shouldDeleteMultipleConceptsAcrossViewsAtomically_viaBulkCompound() {
        // Two independent elements (each in both views) + one relationship between
        // a separate pair (each rendered in both views). Independent so no single
        // delete cascade-claims another's target.
        IBusinessActor e1 = addActor("e1", "E1");
        IBusinessActor e2 = addActor("e2", "E2");
        IApplicationComponent e3 = addComponent("e3", "E3");
        IApplicationComponent e4 = addComponent("e4", "E4");
        IAssociationRelationship r1 = addAssociation("r1", e3, e4);

        IDiagramModelArchimateObject e1A = place(viewA, e1, "e1-a", 0, 0);
        IDiagramModelArchimateObject e1B = place(viewB, e1, "e1-b", 0, 0);
        IDiagramModelArchimateObject e2A = place(viewA, e2, "e2-a", 0, 80);
        IDiagramModelArchimateObject e2B = place(viewB, e2, "e2-b", 0, 80);
        IDiagramModelArchimateObject e3A = place(viewA, e3, "e3-a", 200, 0);
        IDiagramModelArchimateObject e4A = place(viewA, e4, "e4-a", 400, 0);
        connect(r1, e3A, e4A, "r1-a");
        IDiagramModelArchimateObject e3B = place(viewB, e3, "e3-b", 200, 0);
        IDiagramModelArchimateObject e4B = place(viewB, e4, "e4-b", 400, 0);
        connect(r1, e3B, e4B, "r1-b");

        String beforeMembership = snapshot(false);

        // Prepare all three against the unmutated model (bulk Phase-1 semantics),
        // then compose into one compound (the bulk-mutate path). Production uses a
        // NonNotifyingCompoundCommand; its static initializer needs the OSGi/UI
        // runtime and cannot load headless, so this pure-JUnit lane uses a plain
        // GEF CompoundCommand — identical execute/undo-children contract, the only
        // difference being EMF notification suppression (irrelevant to the
        // structural cascade + undo-atomicity assertions here). The production
        // NonNotifyingCompoundCommand path is covered by the OSGi integration lane.
        List<PreparedMutation<DeleteResultDto>> prepared = List.of(
                accessor.prepareDeleteElement(e1.getId()),
                accessor.prepareDeleteElement(e2.getId()),
                accessor.prepareDeleteRelationship(r1.getId()));
        CompoundCommand compound = new CompoundCommand("Bulk delete");
        for (PreparedMutation<DeleteResultDto> pm : prepared) {
            compound.add(pm.command());
        }

        compound.execute();
        // every target removed from BOTH views.
        assertFalse(viewA.getChildren().contains(e1A));
        assertFalse(viewB.getChildren().contains(e1B));
        assertFalse(viewA.getChildren().contains(e2A));
        assertFalse(viewB.getChildren().contains(e2B));
        assertTrue("r1 connection gone from view A", e3A.getSourceConnections().isEmpty());
        assertTrue("r1 connection gone from view B", e3B.getSourceConnections().isEmpty());
        assertFalse(model.getFolder(FolderType.BUSINESS).getElements().contains(e1));
        assertFalse(model.getFolder(FolderType.RELATIONS).getElements().contains(r1));

        // load-bearing invariant: a single undo restores the ENTIRE multi-view
        // membership + connection multiplicity — no loss, no ghost object, no
        // orphaned connection. (Exact child Z-order is a separate, weaker
        // property — see the quarantined pin below.)
        compound.undo();
        assertEquals("Full multi-view membership + connection multiplicity restored "
                + "after one undo", beforeMembership, snapshot(false));

        // Redo removes everything again as one unit.
        compound.redo();
        assertFalse(viewA.getChildren().contains(e1A));
        assertFalse(viewB.getChildren().contains(e2B));
        assertTrue(e3A.getSourceConnections().isEmpty());
    }

    /**
     * Atomicity-under-failure invariant. A bulk delete prepares EVERY operation
     * (pure read-only cascade discovery) before executing ANY, and the delete
     * commands are total — they carry no execute-time "refuse-to-mutate"
     * certificate and never throw mid-execute. So the only failure mode is a
     * prepare-time rejection (e.g. a missing target), which aborts the whole
     * bulk before a single command runs, leaving the model byte-identical. This
     * pins that the partial-mutation risk is confined to the (side-effect-free)
     * prepare phase.
     */
    @Test
    public void shouldLeaveModelUnchanged_whenABulkMemberFailsPreparation() {
        IBusinessActor e1 = addActor("e1", "E1");
        place(viewA, e1, "e1-a", 0, 0);
        place(viewB, e1, "e1-b", 0, 0);
        String before = snapshot(false);

        // First member prepares cleanly; discovery is a pure read and does not mutate.
        accessor.prepareDeleteElement(e1.getId());
        // Second member targets a non-existent element → discovery rejects it before
        // any command executes.
        try {
            accessor.prepareDeleteElement("no-such-element");
            fail("preparing a delete for a missing element must throw");
        } catch (ModelAccessException expected) {
            // expected — the missing target is rejected at discovery time
        }

        assertEquals("Model byte-identical when a bulk member fails preparation",
                before, snapshot(false));
    }

    /**
     * Undo of a compound that deletes MULTIPLE sibling diagram objects from the
     * same view restores their EXACT child order (paint/Z-order), not just their
     * membership.
     *
     * <p>The removal commands restore each child directly before its surviving
     * successor sibling (captured at construction) rather than at a stale
     * absolute index. Without that, a compound — which undoes its sub-commands in
     * reverse — would re-insert a later-listed delete into a list whose
     * earlier-listed siblings are not yet restored: e.g. {@code [delete e1,
     * delete e2]} would yield {@code [e1, e3, e2, e4]} instead of the original
     * {@code [e1, e2, e3, e4]}. The successor-anchor restore is correct here and
     * reproduces the production {@code NonNotifyingCompoundCommand} undo order
     * (both extend the GEF {@code CompoundCommand} reverse-undo).</p>
     */
    @Test
    public void shouldRestoreChildZOrderExactly_whenBulkCompoundUndone() {
        // Minimal repro: three siblings in view A, delete the first two in one
        // compound. e3 is a stationary anchor that exposes the order shift.
        IBusinessActor e1 = addActor("e1", "E1");
        IBusinessActor e2 = addActor("e2", "E2");
        IBusinessActor e3 = addActor("e3", "E3");
        IDiagramModelArchimateObject e1A = place(viewA, e1, "e1-a", 0, 0);
        IDiagramModelArchimateObject e2A = place(viewA, e2, "e2-a", 0, 80);
        place(viewA, e3, "e3-a", 0, 160);

        String beforeOrdered = snapshot(true);

        List<PreparedMutation<DeleteResultDto>> prepared = List.of(
                accessor.prepareDeleteElement(e1.getId()),
                accessor.prepareDeleteElement(e2.getId()));
        CompoundCommand compound = new CompoundCommand("Bulk delete two");
        for (PreparedMutation<DeleteResultDto> pm : prepared) {
            compound.add(pm.command());
        }
        compound.execute();
        assertFalse(viewA.getChildren().contains(e1A));
        assertFalse(viewA.getChildren().contains(e2A));

        compound.undo();
        assertEquals("Exact child Z-order restored after compound undo",
                beforeOrdered, snapshot(true));
    }

    /** Deletes the listed elements from view A in one ascending compound and
     *  asserts the ordered snapshot round-trips exactly after undo. */
    private void assertBulkDeleteFromViewARestoresOrder(List<IBusinessActor> toDelete) {
        String beforeOrdered = snapshot(true);
        CompoundCommand compound = new CompoundCommand("Bulk delete");
        for (IBusinessActor actor : toDelete) {
            compound.add(accessor.prepareDeleteElement(actor.getId()).command());
        }
        compound.execute();
        compound.undo();
        assertEquals("Exact child Z-order restored after compound undo",
                beforeOrdered, snapshot(true));
    }

    @Test
    public void shouldRestoreChildZOrderExactly_whenFirstAndMiddleDeletedInCompound() {
        IBusinessActor e1 = addActor("e1", "E1");
        IBusinessActor e2 = addActor("e2", "E2");
        IBusinessActor e3 = addActor("e3", "E3");
        IBusinessActor e4 = addActor("e4", "E4");
        place(viewA, e1, "e1-a", 0, 0);
        place(viewA, e2, "e2-a", 0, 80);
        place(viewA, e3, "e3-a", 0, 160);
        place(viewA, e4, "e4-a", 0, 240);
        // first (index 0) + a middle (index 2)
        assertBulkDeleteFromViewARestoresOrder(List.of(e1, e3));
    }

    @Test
    public void shouldRestoreChildZOrderExactly_whenTailAndMiddleDeletedInCompound() {
        IBusinessActor e1 = addActor("e1", "E1");
        IBusinessActor e2 = addActor("e2", "E2");
        IBusinessActor e3 = addActor("e3", "E3");
        IBusinessActor e4 = addActor("e4", "E4");
        place(viewA, e1, "e1-a", 0, 0);
        place(viewA, e2, "e2-a", 0, 80);
        place(viewA, e3, "e3-a", 0, 160);
        place(viewA, e4, "e4-a", 0, 240);
        // a middle (index 1) + the tail (index 3, null successor anchor)
        assertBulkDeleteFromViewARestoresOrder(List.of(e2, e4));
    }

    @Test
    public void shouldRestoreChildZOrderExactly_whenNonAdjacentSiblingsDeletedInDescendingOrder() {
        IBusinessActor e1 = addActor("e1", "E1");
        IBusinessActor e2 = addActor("e2", "E2");
        IBusinessActor e3 = addActor("e3", "E3");
        IBusinessActor e4 = addActor("e4", "E4");
        place(viewA, e1, "e1-a", 0, 0);
        place(viewA, e2, "e2-a", 0, 80);
        place(viewA, e3, "e3-a", 0, 160);
        place(viewA, e4, "e4-a", 0, 240);
        // Non-adjacent (indices 2 and 0) listed in DESCENDING order — both have a
        // surviving successor anchor, so the order is restored exactly regardless
        // of the listing order. (Contrast the documented residual in
        // DeleteElementCommand: a run of 3+ co-deleted siblings listed in a
        // non-monotonic order can leave a sibling without a restored anchor.)
        assertBulkDeleteFromViewARestoresOrder(List.of(e3, e1));
    }

    @Test
    public void shouldRestoreChildZOrderExactly_whenAllSiblingsDeletedInCompound() {
        IBusinessActor e1 = addActor("e1", "E1");
        IBusinessActor e2 = addActor("e2", "E2");
        IBusinessActor e3 = addActor("e3", "E3");
        IBusinessActor e4 = addActor("e4", "E4");
        place(viewA, e1, "e1-a", 0, 0);
        place(viewA, e2, "e2-a", 0, 80);
        place(viewA, e3, "e3-a", 0, 160);
        place(viewA, e4, "e4-a", 0, 240);
        assertBulkDeleteFromViewARestoresOrder(List.of(e1, e2, e3, e4));
    }

    @Test
    public void shouldRestoreChildZOrderExactly_whenSiblingsDeletedAcrossTwoViewsInOneCompound() {
        // View A: a1, a2, a3 ; View B: b1, b2, b3. Delete the first two of each
        // in a single compound; each view's order must restore independently.
        IBusinessActor a1 = addActor("a1", "A1");
        IBusinessActor a2 = addActor("a2", "A2");
        IBusinessActor a3 = addActor("a3", "A3");
        IBusinessActor b1 = addActor("b1", "B1");
        IBusinessActor b2 = addActor("b2", "B2");
        IBusinessActor b3 = addActor("b3", "B3");
        place(viewA, a1, "a1-a", 0, 0);
        place(viewA, a2, "a2-a", 0, 80);
        place(viewA, a3, "a3-a", 0, 160);
        place(viewB, b1, "b1-b", 0, 0);
        place(viewB, b2, "b2-b", 0, 80);
        place(viewB, b3, "b3-b", 0, 160);

        String beforeOrdered = snapshot(true);
        CompoundCommand compound = new CompoundCommand("Bulk delete across views");
        for (IBusinessActor actor : List.of(a1, a2, b1, b2)) {
            compound.add(accessor.prepareDeleteElement(actor.getId()).command());
        }
        compound.execute();
        compound.undo();
        assertEquals("Both views' child Z-order restored independently after undo",
                beforeOrdered, snapshot(true));
    }

    @Test
    public void shouldRestoreRelationshipFolderOrderExactly_whenRelatedElementsBulkDeleted() {
        // Two independent associations sit in the RELATIONS folder in a known
        // order. Deleting the two source actors in one compound cascade-removes
        // both relationships; undo must restore the RELATIONS folder order too,
        // not just membership — this exercises the cascadedRelationships
        // successor-anchor path in DeleteElementCommand.undo().
        IBusinessActor a1 = addActor("a1", "A1");
        IBusinessActor a2 = addActor("a2", "A2");
        IApplicationComponent c1 = addComponent("c1", "C1");
        IApplicationComponent c2 = addComponent("c2", "C2");
        addAssociation("r1", a1, c1);
        addAssociation("r2", a2, c2);
        place(viewA, a1, "a1-a", 0, 0);
        place(viewA, a2, "a2-a", 0, 80);

        String beforeOrdered = snapshot(true);
        CompoundCommand compound = new CompoundCommand("Bulk delete related elements");
        compound.add(accessor.prepareDeleteElement(a1.getId()).command());
        compound.add(accessor.prepareDeleteElement(a2.getId()).command());
        compound.execute();
        compound.undo();
        assertEquals("RELATIONS folder order (and all membership) restored after undo",
                beforeOrdered, snapshot(true));
    }

    // ---- Test plumbing (mirrors ArchiModelAccessorImplAddViewReferenceToViewTest) ----

    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(IArchimateModel testModel) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> testModel) {
            @Override
            public void dispatchImmediate(Command command) {
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    command.execute();
                }
            }
        };
        testDispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
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
