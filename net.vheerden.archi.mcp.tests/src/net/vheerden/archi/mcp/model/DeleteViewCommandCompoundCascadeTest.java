package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

/**
 * Forensic characterization of the {@link DeleteViewCommand} placeholder
 * cascade when TWO views are deleted inside the SAME GEF
 * {@link CompoundCommand}, and the second view holds an
 * {@link IDiagramModelReference} placeholder pointing at the first.
 *
 * <p>The single-view cascade is already pinned by
 * {@code DeleteViewCommandCascadeTest}. This class probes the COMPOUND case,
 * where the relative {@code execute()} order of the two commands changes which
 * command observes the placeholder and therefore which command owns its
 * undo-restore. The risk is a <em>double restore</em>: one command captures
 * the placeholder as an external cascade target, the other captures it as one
 * of its own children, and undo re-inserts it twice.</p>
 *
 * <p>Two orderings are characterized:</p>
 * <ul>
 *   <li><b>referenced-view first</b> ({@code [delete A, delete B]}) — A removes
 *       B's placeholder before B captures its children, so B captures an empty
 *       child list and the placeholder is owned solely by A's cascade.</li>
 *   <li><b>holding-view first</b> ({@code [delete B, delete A]}) — B captures
 *       the placeholder among its own children before A runs; A then captures
 *       the same placeholder externally against B's already-cleared child list.
 *       This is the ordering most likely to double-restore on undo.</li>
 * </ul>
 *
 * <p>Both tests assert the single load-bearing invariant: after a full
 * execute/undo cycle the holding view contains the placeholder EXACTLY ONCE,
 * referencing the restored view. Commands are constructed directly and driven
 * with raw {@code execute()/undo()/redo()} (no {@code CommandStack}), mirroring
 * {@code DeleteViewCommandCascadeTest}.</p>
 */
public class DeleteViewCommandCompoundCascadeTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder diagrams;
    private IArchimateDiagramModel viewA;
    private IArchimateDiagramModel viewB;
    private IDiagramModelReference placeholderInB;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;

        model = factory.createArchimateModel();
        model.setName("Compound Cascade Fixture");
        model.setId("model-compound-cascade");
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

        // B holds a placeholder pointing at A.
        placeholderInB = factory.createDiagramModelReference();
        placeholderInB.setReferencedModel(viewA);
        placeholderInB.setBounds(0, 0, 185, 80);
        viewB.getChildren().add(placeholderInB);
    }

    private CompoundCommand compoundOf(IArchimateDiagramModel first,
                                       IArchimateDiagramModel second) {
        // Capture indices against the unmutated model (prepare-time semantics):
        // production discovers all cascade state before any sub-command executes.
        int firstIndex = diagrams.getElements().indexOf(first);
        int secondIndex = diagrams.getElements().indexOf(second);
        DeleteViewCommand cmdFirst = new DeleteViewCommand(first, diagrams, firstIndex);
        DeleteViewCommand cmdSecond = new DeleteViewCommand(second, diagrams, secondIndex);
        CompoundCommand compound = new CompoundCommand("Delete two views");
        compound.add(cmdFirst);
        compound.add(cmdSecond);
        return compound;
    }

    /**
     * Occurrences of the placeholder in view B's children. Because B's children
     * is an EMF containment list (single-parent, move-on-readd), this can never
     * exceed 1 through the list API — so the {@code == 1} assertions are a sound
     * pin for the REAL risk in these orderings (placeholder LOST, count 0), and
     * document that a literal double-insert would be a bug if EMF allowed it.
     */
    private int placeholdersInB() {
        return Collections.frequency(viewB.getChildren(), placeholderInB);
    }

    @Test
    public void shouldRestoreSinglePlaceholder_whenCompoundDeletesBothViews_orderABeforeB() {
        CompoundCommand compound = compoundOf(viewA, viewB);

        compound.execute();
        assertFalse("View A removed", diagrams.getElements().contains(viewA));
        assertFalse("View B removed", diagrams.getElements().contains(viewB));

        compound.undo();
        assertTrue("View A restored", diagrams.getElements().contains(viewA));
        assertTrue("View B restored", diagrams.getElements().contains(viewB));
        assertEquals("Holding view B must contain the placeholder exactly once after undo "
                + "(order: referenced-view A deleted first)", 1, placeholdersInB());
        assertSame("Restored placeholder still points at the restored view A",
                viewA, placeholderInB.getReferencedModel());

        // Redo must remove everything again and leave no stray placeholder.
        compound.redo();
        assertFalse("View A removed on redo", diagrams.getElements().contains(viewA));
        assertFalse("View B removed on redo", diagrams.getElements().contains(viewB));
    }

    @Test
    public void shouldNotDoubleRestorePlaceholder_whenCompoundDeletesBothViews_orderBBeforeA() {
        CompoundCommand compound = compoundOf(viewB, viewA);

        compound.execute();
        assertFalse("View A removed", diagrams.getElements().contains(viewA));
        assertFalse("View B removed", diagrams.getElements().contains(viewB));

        compound.undo();
        assertTrue("View A restored", diagrams.getElements().contains(viewA));
        assertTrue("View B restored", diagrams.getElements().contains(viewB));
        assertEquals("Holding view B must contain the placeholder EXACTLY ONCE after undo "
                + "(order: holding-view B deleted first — the double-restore risk path)",
                1, placeholdersInB());
        assertSame("Restored placeholder still points at the restored view A",
                viewA, placeholderInB.getReferencedModel());

        compound.redo();
        assertFalse("View A removed on redo", diagrams.getElements().contains(viewA));
        assertFalse("View B removed on redo", diagrams.getElements().contains(viewB));
    }

    // ---- Connection on cascaded placeholders in a SURVIVING view, under compound ----
    //
    // A single connection can touch TWO external placeholders that point at two DIFFERENT
    // deleted views, so it is reachable from both DeleteViewCommands' cascade scans. The
    // load-bearing invariant is that it is disconnected once and reconnected EXACTLY once
    // on undo — never twice. This holds only because each command captures its cascade
    // connections at EXECUTE time against the live (already-partly-mutated) model: the
    // first command to run disconnects the connection, so the second command's scan no
    // longer finds it. A regression to prepare-time capture would make both commands
    // capture the connection and double-reconnect it on undo.

    /** order [A, B]: referenced view A deleted first. */
    @Test
    public void shouldReconnectSurvivingPlaceholderConnectionExactlyOnce_orderABeforeB() {
        assertSurvivingPlaceholderConnectionReconnectedOnce(viewA, viewB);
    }

    /** order [B, A]: referenced view B deleted first. */
    @Test
    public void shouldReconnectSurvivingPlaceholderConnectionExactlyOnce_orderBBeforeA() {
        assertSurvivingPlaceholderConnectionReconnectedOnce(viewB, viewA);
    }

    private void assertSurvivingPlaceholderConnectionReconnectedOnce(
            IArchimateDiagramModel first, IArchimateDiagramModel second) {
        // Surviving view C holds a placeholder -> A and a placeholder -> B, with one
        // connection between them. The compound deletes BOTH A and B, so both placeholders
        // cascade out of the surviving C and the connection (touching both) must leave
        // cleanly and come back exactly once.
        IArchimateDiagramModel viewC = factory.createArchimateDiagramModel();
        viewC.setId("view-c");
        viewC.setName("View C (survivor)");
        diagrams.getElements().add(viewC);

        IDiagramModelReference phToA = factory.createDiagramModelReference();
        phToA.setReferencedModel(viewA);
        phToA.setBounds(0, 0, 185, 80);
        viewC.getChildren().add(phToA);

        IDiagramModelReference phToB = factory.createDiagramModelReference();
        phToB.setReferencedModel(viewB);
        phToB.setBounds(0, 200, 185, 80);
        viewC.getChildren().add(phToB);

        IDiagramModelConnection conn = factory.createDiagramModelConnection();
        conn.connect(phToA, phToB);
        assertTrue("fixture: connection sourced by placeholder->A",
                phToA.getSourceConnections().contains(conn));
        assertTrue("fixture: connection targeted at placeholder->B",
                phToB.getTargetConnections().contains(conn));

        CompoundCommand compound = compoundOf(first, second);
        compound.execute();

        assertFalse("Referenced view A removed", diagrams.getElements().contains(viewA));
        assertFalse("Referenced view B removed", diagrams.getElements().contains(viewB));
        assertTrue("Surviving view C kept", diagrams.getElements().contains(viewC));
        assertEquals("Both placeholders cascaded out of the surviving view", 0, viewC.getChildren().size());
        assertTrue("Connection disconnected from placeholder->A on execute",
                phToA.getSourceConnections().isEmpty());
        assertTrue("Connection disconnected from placeholder->B on execute",
                phToB.getTargetConnections().isEmpty());

        compound.undo();

        assertTrue("View A restored", diagrams.getElements().contains(viewA));
        assertTrue("View B restored", diagrams.getElements().contains(viewB));
        assertEquals("Both placeholders restored to the surviving view", 2, viewC.getChildren().size());
        assertEquals("Placeholder->A restored exactly once",
                1, Collections.frequency(viewC.getChildren(), phToA));
        assertEquals("Placeholder->B restored exactly once",
                1, Collections.frequency(viewC.getChildren(), phToB));
        assertEquals("Connection reconnected on placeholder->A EXACTLY once "
                + "(no double-reconnect across the two commands)",
                1, Collections.frequency(phToA.getSourceConnections(), conn));
        assertEquals("Connection reconnected on placeholder->B EXACTLY once",
                1, Collections.frequency(phToB.getTargetConnections(), conn));

        compound.redo();
        assertTrue("Connection disconnected again on redo", phToA.getSourceConnections().isEmpty());
    }
}
