package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

/**
 * Tests for {@link DeleteViewCommand} cascade behaviour.
 *
 * <p>When a view is deleted, every {@link IDiagramModelReference} visual
 * placeholder elsewhere in the model whose {@code getReferencedModel()} is
 * that view must also be removed. Otherwise the EMF cross-reference becomes
 * dangling on save+reload and Archi cannot open the file
 * ({@code "Unresolved reference ..."}).</p>
 *
 * <p>Mirrors Archi GUI's cascade pattern at
 * {@code com.archimatetool.editor/.../DeleteCommandHandler.getDiagramModelReferencesToDelete()}.</p>
 *
 * <p>Pure standard JUnit (no OSGi / Plug-in Test): uses
 * {@link IArchimateFactory#eINSTANCE} on real EMF objects, exercises
 * {@code execute()} / {@code undo()} / {@code redo()} directly.</p>
 */
public class DeleteViewCommandCascadeTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder diagramsFolder;
    private IArchimateDiagramModel viewA;
    private IArchimateDiagramModel viewB;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();
        diagramsFolder = model.getFolder(FolderType.DIAGRAMS);

        viewA = factory.createArchimateDiagramModel();
        viewA.setName("View A");
        diagramsFolder.getElements().add(viewA);

        viewB = factory.createArchimateDiagramModel();
        viewB.setName("View B");
        diagramsFolder.getElements().add(viewB);
    }

    private IDiagramModelReference newRefTo(IArchimateDiagramModel target) {
        IDiagramModelReference ref = factory.createDiagramModelReference();
        ref.setReferencedModel(target);
        ref.setBounds(0, 0, 185, 80);
        return ref;
    }

    private DeleteViewCommand newDeleteCommand(IArchimateDiagramModel view) {
        IFolder folder = (IFolder) view.eContainer();
        int idx = folder.getElements().indexOf(view);
        return new DeleteViewCommand(view, folder, idx);
    }

    @Test
    public void shouldCascadeRemoveSinglePlaceholder_whenReferencedViewDeleted() {
        IDiagramModelReference refInB = newRefTo(viewA);
        viewB.getChildren().add(refInB);

        DeleteViewCommand cmd = newDeleteCommand(viewA);
        cmd.execute();

        assertEquals("View B should have no children after cascade",
                0, viewB.getChildren().size());
        assertFalse("Diagrams folder should no longer contain view A",
                diagramsFolder.getElements().contains(viewA));
    }

    /** Multiple placeholders across sibling views, including a Group nested one. */
    @Test
    public void shouldCascadeRemoveMultiplePlaceholdersAcrossViews_whenReferencedViewDeleted() {
        IArchimateDiagramModel viewC = factory.createArchimateDiagramModel();
        viewC.setName("View C");
        diagramsFolder.getElements().add(viewC);

        IDiagramModelReference refInB1 = newRefTo(viewA);
        viewB.getChildren().add(refInB1);

        IDiagramModelReference refInB2 = newRefTo(viewA);
        viewB.getChildren().add(refInB2);

        IDiagramModelReference refInC = newRefTo(viewA);
        viewC.getChildren().add(refInC);

        DeleteViewCommand cmd = newDeleteCommand(viewA);
        cmd.execute();

        assertEquals("View B should be empty after cascade",
                0, viewB.getChildren().size());
        assertEquals("View C should be empty after cascade",
                0, viewC.getChildren().size());
    }

    /** Undo restores all cascaded placeholders at original indices. */
    @Test
    public void shouldRestorePlaceholders_onUndo() {
        IDiagramModelReference refIdx0 = newRefTo(viewA);
        IDiagramModelReference refIdx1 = newRefTo(viewA);
        IDiagramModelReference refIdx2 = newRefTo(viewA);
        viewB.getChildren().add(refIdx0);
        viewB.getChildren().add(refIdx1);
        viewB.getChildren().add(refIdx2);

        DeleteViewCommand cmd = newDeleteCommand(viewA);
        cmd.execute();
        cmd.undo();

        assertEquals("View B should have 3 children after undo",
                3, viewB.getChildren().size());
        assertSame("First child preserved at index 0", refIdx0, viewB.getChildren().get(0));
        assertSame("Second child preserved at index 1", refIdx1, viewB.getChildren().get(1));
        assertSame("Third child preserved at index 2", refIdx2, viewB.getChildren().get(2));
        assertTrue("View A should be back in diagrams folder",
                diagramsFolder.getElements().contains(viewA));
    }

    /** Nested placeholder inside a Group child. */
    @Test
    public void shouldHandleNestedPlaceholder_inGroupChild() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setName("Group X");
        group.setBounds(10, 10, 400, 200);
        viewB.getChildren().add(group);

        IDiagramModelReference nestedRef = newRefTo(viewA);
        group.getChildren().add(nestedRef);

        DeleteViewCommand cmd = newDeleteCommand(viewA);
        cmd.execute();

        assertEquals("Group should be empty after cascade",
                0, group.getChildren().size());
        assertEquals("View B should still contain only the group",
                1, viewB.getChildren().size());

        cmd.undo();

        assertEquals("Group should be restored with nested ref after undo",
                1, group.getChildren().size());
        assertSame(nestedRef, group.getChildren().get(0));
    }

    /** Redo correctness — lazy capture survives a full undo→redo cycle. */
    @Test
    public void shouldRemovePlaceholdersAgain_onRedo() {
        IDiagramModelReference refIdx0 = newRefTo(viewA);
        IDiagramModelReference refIdx1 = newRefTo(viewA);
        viewB.getChildren().add(refIdx0);
        viewB.getChildren().add(refIdx1);

        DeleteViewCommand cmd = newDeleteCommand(viewA);
        cmd.execute();
        cmd.undo();
        cmd.redo();

        assertEquals("View B should again be empty after redo",
                0, viewB.getChildren().size());
        assertFalse("Diagrams folder should again be missing view A",
                diagramsFolder.getElements().contains(viewA));

        cmd.undo();

        assertEquals("Second undo should restore both placeholders",
                2, viewB.getChildren().size());
        assertSame(refIdx0, viewB.getChildren().get(0));
        assertSame(refIdx1, viewB.getChildren().get(1));
    }

    /** EdgeCase#1 — cascade must reach placeholders inside views filed in a user subfolder under DIAGRAMS. */
    @Test
    public void shouldCascadePlaceholder_inUserSubfolderUnderDiagrams() {
        IFolder userFolder = IArchimateFactory.eINSTANCE.createFolder();
        userFolder.setName("User subfolder");
        diagramsFolder.getFolders().add(userFolder);

        IArchimateDiagramModel nestedView = factory.createArchimateDiagramModel();
        nestedView.setName("Nested in user subfolder");
        userFolder.getElements().add(nestedView);

        IDiagramModelReference placeholderInNestedView = newRefTo(viewA);
        nestedView.getChildren().add(placeholderInNestedView);

        DeleteViewCommand cmd = newDeleteCommand(viewA);
        cmd.execute();

        assertEquals("Placeholder inside user-subfolder view must be cascade-removed",
                0, nestedView.getChildren().size());

        cmd.undo();

        assertEquals("Undo should restore the placeholder inside the user-subfolder view",
                1, nestedView.getChildren().size());
        assertSame(placeholderInNestedView, nestedView.getChildren().get(0));
    }

    /** EdgeCase#8 — index-shift invariant with mixed cascaded + non-cascaded siblings. */
    @Test
    public void shouldRestorePlaceholders_withMixedChildrenAtVaryingIndices() {
        IDiagramModelReference refAt0 = newRefTo(viewA);
        IDiagramModelGroup regularAt1 = factory.createDiagramModelGroup();
        regularAt1.setName("Regular group at idx 1");
        regularAt1.setBounds(10, 10, 200, 100);
        IDiagramModelReference refAt2 = newRefTo(viewA);
        IDiagramModelReference refAt3 = newRefTo(viewA);
        IDiagramModelGroup regularAt4 = factory.createDiagramModelGroup();
        regularAt4.setName("Regular group at idx 4");
        regularAt4.setBounds(220, 10, 200, 100);

        viewB.getChildren().add(refAt0);
        viewB.getChildren().add(regularAt1);
        viewB.getChildren().add(refAt2);
        viewB.getChildren().add(refAt3);
        viewB.getChildren().add(regularAt4);

        DeleteViewCommand cmd = newDeleteCommand(viewA);
        cmd.execute();

        assertEquals("Only the 2 non-cascaded children remain after execute",
                2, viewB.getChildren().size());
        assertSame(regularAt1, viewB.getChildren().get(0));
        assertSame(regularAt4, viewB.getChildren().get(1));

        cmd.undo();

        assertEquals("All 5 children restored after undo", 5, viewB.getChildren().size());
        assertSame("Index 0 preserved", refAt0, viewB.getChildren().get(0));
        assertSame("Index 1 preserved (non-cascaded)", regularAt1, viewB.getChildren().get(1));
        assertSame("Index 2 preserved", refAt2, viewB.getChildren().get(2));
        assertSame("Index 3 preserved", refAt3, viewB.getChildren().get(3));
        assertSame("Index 4 preserved (non-cascaded)", regularAt4, viewB.getChildren().get(4));
    }

    // ---- Connections attached to cascaded placeholders in a SURVIVING view ----
    //
    // A cascaded placeholder lives in another view that SURVIVES the delete. A diagram
    // connection can be attached to it (an IDiagramModelReference is an IConnectable).
    // Because the connection is EMF-contained by its SOURCE, a connection FROM a surviving
    // object INTO the placeholder stays in the surviving view while its target placeholder
    // is removed — a dangling cross-reference that breaks .archimate save/reload. The
    // cascade must therefore disconnect the placeholder's connections, exactly as
    // DeleteElementCommand disconnects the connections on its cascaded view objects.

    /** Builds a surviving view A (kept) holding a placeholder → the deleted view B, plus two
     *  survivor notes with a connection INTO the placeholder and one OUT of it. Returns the
     *  three connectables and two connections wired and ready; view B is the delete target. */
    private IDiagramModelReference placeholderInSurvivingViewWithConnections(
            IDiagramModelNote[] outNotes, IDiagramModelConnection[] outConns) {
        IDiagramModelReference placeholder = newRefTo(viewB); // in A, points at the doomed B
        viewA.getChildren().add(placeholder);

        IDiagramModelNote noteInto = factory.createDiagramModelNote();
        noteInto.setBounds(0, 0, 120, 55);
        viewA.getChildren().add(noteInto);
        IDiagramModelNote noteOut = factory.createDiagramModelNote();
        noteOut.setBounds(0, 200, 120, 55);
        viewA.getChildren().add(noteOut);

        // Connection INTO the placeholder: survivor note -> placeholder (the dangerous shape).
        IDiagramModelConnection connInto = factory.createDiagramModelConnection();
        connInto.connect(noteInto, placeholder);
        // Connection OUT of the placeholder: placeholder -> survivor note.
        IDiagramModelConnection connOut = factory.createDiagramModelConnection();
        connOut.connect(placeholder, noteOut);

        outNotes[0] = noteInto;
        outNotes[1] = noteOut;
        outConns[0] = connInto;
        outConns[1] = connOut;
        return placeholder;
    }

    /** execute() must disconnect both directions of connection on a cascaded placeholder,
     *  so the surviving view keeps no dangling reference to the removed placeholder. */
    @Test
    public void shouldDisconnectConnectionsOnCascadedPlaceholder_whenReferencedViewDeleted() {
        IDiagramModelNote[] notes = new IDiagramModelNote[2];
        IDiagramModelConnection[] conns = new IDiagramModelConnection[2];
        IDiagramModelReference placeholder = placeholderInSurvivingViewWithConnections(notes, conns);
        IDiagramModelNote noteInto = notes[0];
        IDiagramModelNote noteOut = notes[1];
        IDiagramModelConnection connInto = conns[0];
        IDiagramModelConnection connOut = conns[1];

        // Fixture-shape sanity: the connections really are on the placeholder.
        assertTrue("connInto must be a target-connection of the placeholder",
                placeholder.getTargetConnections().contains(connInto));
        assertTrue("connOut must be a source-connection of the placeholder",
                placeholder.getSourceConnections().contains(connOut));

        DeleteViewCommand cmd = newDeleteCommand(viewB);
        cmd.execute();

        assertFalse("Placeholder cascade-removed from the surviving view",
                viewA.getChildren().contains(placeholder));
        // The survivor notes must retain NO connection to the removed placeholder — otherwise
        // the surviving view serializes a dangling cross-reference.
        assertTrue("Survivor note's connection INTO the removed placeholder must be disconnected",
                noteInto.getSourceConnections().isEmpty());
        assertTrue("Survivor note's connection OUT of the removed placeholder must be disconnected",
                noteOut.getTargetConnections().isEmpty());
        assertTrue("Removed placeholder must hold no live connections",
                placeholder.getSourceConnections().isEmpty()
                        && placeholder.getTargetConnections().isEmpty());
    }

    /** undo() must restore the placeholder AND reconnect its connections in both directions. */
    @Test
    public void shouldReconnectPlaceholderConnections_onUndo() {
        IDiagramModelNote[] notes = new IDiagramModelNote[2];
        IDiagramModelConnection[] conns = new IDiagramModelConnection[2];
        IDiagramModelReference placeholder = placeholderInSurvivingViewWithConnections(notes, conns);
        IDiagramModelNote noteInto = notes[0];
        IDiagramModelNote noteOut = notes[1];
        IDiagramModelConnection connInto = conns[0];
        IDiagramModelConnection connOut = conns[1];

        DeleteViewCommand cmd = newDeleteCommand(viewB);
        cmd.execute();
        cmd.undo();

        assertTrue("Placeholder restored to the surviving view on undo",
                viewA.getChildren().contains(placeholder));
        assertTrue("Connection INTO the placeholder reconnected on undo",
                noteInto.getSourceConnections().contains(connInto)
                        && placeholder.getTargetConnections().contains(connInto));
        assertTrue("Connection OUT of the placeholder reconnected on undo",
                placeholder.getSourceConnections().contains(connOut)
                        && noteOut.getTargetConnections().contains(connOut));
    }

    /** redo() must re-disconnect the placeholder connections (lazy capture survives undo→redo). */
    @Test
    public void shouldReDisconnectPlaceholderConnections_onRedo() {
        IDiagramModelNote[] notes = new IDiagramModelNote[2];
        IDiagramModelConnection[] conns = new IDiagramModelConnection[2];
        IDiagramModelReference placeholder = placeholderInSurvivingViewWithConnections(notes, conns);
        IDiagramModelNote noteInto = notes[0];

        DeleteViewCommand cmd = newDeleteCommand(viewB);
        cmd.execute();
        cmd.undo();
        cmd.redo();

        assertFalse("Placeholder again removed from the surviving view on redo",
                viewA.getChildren().contains(placeholder));
        assertTrue("Survivor's connection again disconnected on redo",
                noteInto.getSourceConnections().isEmpty());
    }

    /** Self-reference inside deleted view must NOT be cascaded. */
    @Test
    public void shouldSkipSelfReferenceInsideDeletedView() {
        IDiagramModelReference selfRef = newRefTo(viewA);
        viewA.getChildren().add(selfRef);

        IDiagramModelReference externalRef = newRefTo(viewA);
        viewB.getChildren().add(externalRef);

        DeleteViewCommand cmd = newDeleteCommand(viewA);
        cmd.execute();

        assertEquals("View B's external placeholder should be cascade-removed",
                0, viewB.getChildren().size());

        cmd.undo();

        assertEquals("After undo, view A should have the self-ref again",
                1, viewA.getChildren().size());
        assertSame(selfRef, viewA.getChildren().get(0));
        assertEquals("After undo, view B should have its external ref restored exactly once",
                1, viewB.getChildren().size());
        assertSame(externalRef, viewB.getChildren().get(0));
    }
}
