package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;

/**
 * Tests for {@link RecedeContainerFillCommand} — the container-recession emitter.
 *
 * <p>Covers the provenance gate (recede only an unauthored / null-fill parent), the
 * element-vs-group-vs-root-view distinction, the {@code recede:false} opt-out, idempotency,
 * and the atomic execute/undo of the wrapped add. Pure EMF, headless-safe.</p>
 */
public class RecedeContainerFillCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setName("Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
    }

    /** A fresh element view object (null fill = unauthored, the EMF default). */
    private IDiagramModelArchimateObject element(String name) {
        IArchimateElement el = factory.createApplicationComponent();
        el.setName(name);
        model.getFolder(FolderType.APPLICATION).getElements().add(el);
        IDiagramModelArchimateObject dmo = factory.createDiagramModelArchimateObject();
        dmo.setArchimateElement(el);
        dmo.setBounds(0, 0, 120, 55);
        return dmo;
    }

    /** StylingParams carrying only a recede opt-out flag. */
    private static StylingParams recede(Boolean flag) {
        return new StylingParams(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, flag);
    }

    // ---- provenance gate (shouldRecede) ----

    @Test
    public void shouldRecede_elementParent_nullFill_default() {
        assertTrue(RecedeContainerFillCommand.shouldRecede(element("p"), null));
    }

    @Test
    public void shouldRecede_groupParent_nullFill_default() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        assertTrue(RecedeContainerFillCommand.shouldRecede(group, null));
    }

    @Test
    public void shouldNotRecede_authoredFill_isSacrosanct() {
        IDiagramModelArchimateObject parent = element("p");
        parent.setFillColor("#3366CC");
        assertFalse(RecedeContainerFillCommand.shouldRecede(parent, null));
    }

    @Test
    public void shouldNotRecede_rootView_isExcluded() {
        // The diagram model is an IDiagramModelContainer but NOT an element/group — never recede.
        assertFalse(RecedeContainerFillCommand.shouldRecede(view, null));
    }

    @Test
    public void shouldNotRecede_whenRecedeFalseOptOut() {
        assertFalse(RecedeContainerFillCommand.shouldRecede(element("p"), recede(Boolean.FALSE)));
    }

    @Test
    public void shouldRecede_whenRecedeTrueExplicit() {
        assertTrue(RecedeContainerFillCommand.shouldRecede(element("p"), recede(Boolean.TRUE)));
    }

    // ---- execute / undo ----

    @Test
    public void execute_setsRecessionFill_undoRestoresNull() {
        IDiagramModelArchimateObject parent = element("p");
        assertNull("precondition: unauthored fill", parent.getFillColor());

        RecedeContainerFillCommand cmd = new RecedeContainerFillCommand(parent);
        cmd.execute();
        assertEquals(RecedeContainerFillCommand.CONTAINER_RECESSION_FILL, parent.getFillColor());

        cmd.undo();
        assertNull("undo restores the prior (null) fill", parent.getFillColor());
    }

    @Test
    public void recedeByLightnessNotOpacity_alphaUntouched() {
        IDiagramModelArchimateObject parent = element("p");
        int alphaBefore = parent.getAlpha();
        new RecedeContainerFillCommand(parent).execute();
        assertEquals("opacity/alpha must be untouched by the recede", alphaBefore, parent.getAlpha());
    }

    @Test
    public void idempotent_secondNestDoesNotReRecede() {
        // After the first recede the fill is non-null → the gate now reads it as authored, so a
        // second child nested into the same parent must NOT recede again (no compounding).
        IDiagramModelArchimateObject parent = element("p");
        new RecedeContainerFillCommand(parent).execute();
        assertFalse(RecedeContainerFillCommand.shouldRecede(parent, null));
    }

    // ---- wrap (atomic compound) ----

    @Test
    public void wrap_returnsBaseUnchanged_whenParentDoesNotQualify() {
        IDiagramModelArchimateObject parent = element("p");
        parent.setFillColor("#3366CC"); // authored → no recede
        IDiagramModelArchimateObject child = element("c");
        Command base = new AddToViewCommand(child, parent);

        assertSame("authored parent → base returned, no compound wrap",
                base, RecedeContainerFillCommand.wrap(base, parent, null));
    }

    @Test
    public void wrap_composesRecedeThenAddAsAtomicCompound() {
        // The recede and the add are sequenced into one undoable compound. (Executing the
        // NonNotifyingCompoundCommand needs the Eclipse runtime, so this asserts the composition
        // structurally; the per-command execute/undo/redo is covered above and in
        // AddToViewCommandTest, and the end-to-end live gate exercises real execution.)
        IDiagramModelArchimateObject parent = element("p");
        IDiagramModelArchimateObject child = element("c");
        Command base = new AddToViewCommand(child, parent);

        Command wrapped = RecedeContainerFillCommand.wrap(base, parent, null);
        assertTrue("a qualifying parent yields a compound wrap", wrapped instanceof CompoundCommand);

        List<?> cmds = ((CompoundCommand) wrapped).getCommands();
        assertEquals("recede + add as one unit", 2, cmds.size());
        assertTrue("recede runs first", cmds.get(0) instanceof RecedeContainerFillCommand);
        assertSame("the original add is preserved unchanged", base, cmds.get(1));
        assertSame("recede targets the parent container", parent,
                ((RecedeContainerFillCommand) cmds.get(0)).getContainer());
    }

    @Test
    public void wrap_returnsBaseUnchanged_whenRecedeFalse() {
        IDiagramModelArchimateObject parent = element("p");
        IDiagramModelArchimateObject child = element("c");
        Command base = new AddToViewCommand(child, parent);

        assertSame("recede:false → base returned, parent untouched",
                base, RecedeContainerFillCommand.wrap(base, parent, recede(Boolean.FALSE)));
        assertNull("parent fill stays null under opt-out", parent.getFillColor());
    }

    // ---- decided when it runs ----

    @Test
    public void execute_writesNothing_andUndoRestoresNothing_whenAFillArrivedAfterThePrepare() {
        IDiagramModelArchimateObject parent = element("p");
        RecedeContainerFillCommand cmd = new RecedeContainerFillCommand(parent);
        parent.setFillColor("#123456"); // an earlier operation of the same batch, run before this one
        cmd.execute();
        assertEquals("#123456", parent.getFillColor());
        assertFalse(RecedeContainerFillCommand.reportOf(cmd).receded());
        cmd.undo();
        assertEquals("undo must not clear a fill the recede never wrote", "#123456", parent.getFillColor());
    }

    @Test
    public void execute_takesTheNextShade_whenTheParentHoldsTheFirst() {
        IDiagramModelGroup outer = factory.createDiagramModelGroup();
        outer.setFillColor("#f4f4f4"); // compared ignoring case
        IDiagramModelGroup inner = factory.createDiagramModelGroup();
        outer.getChildren().add(inner);
        new RecedeContainerFillCommand(inner).execute();
        assertEquals("#FFFFFF", inner.getFillColor());
    }

    @Test
    public void execute_takesAShadeNoChildHolds_theIncomingOneIncluded() {
        IDiagramModelGroup parent = factory.createDiagramModelGroup();
        IDiagramModelGroup existing = factory.createDiagramModelGroup();
        existing.setFillColor("#F4F4F4");
        parent.getChildren().add(existing);
        IDiagramModelArchimateObject incoming = element("c");
        incoming.setFillColor("#ffffff");
        RecedeContainerFillCommand cmd = new RecedeContainerFillCommand(parent, incoming);
        cmd.execute();
        assertEquals("#E8E8E8", parent.getFillColor());
        assertEquals("#E8E8E8", RecedeContainerFillCommand.reportOf(cmd).getFillColor());
    }

    @Test
    public void execute_leavesTheContainerUnauthored_whenEveryShadeIsTaken() {
        IDiagramModelGroup outer = factory.createDiagramModelGroup();
        outer.setFillColor("#F4F4F4");
        IDiagramModelGroup inner = factory.createDiagramModelGroup();
        outer.getChildren().add(inner);
        for (String fill : new String[] {"#FFFFFF", "#E8E8E8"}) {
            IDiagramModelGroup child = factory.createDiagramModelGroup();
            child.setFillColor(fill);
            inner.getChildren().add(child);
        }
        RecedeContainerFillCommand cmd = new RecedeContainerFillCommand(inner);
        cmd.execute();
        assertNull(inner.getFillColor());
        assertFalse(RecedeContainerFillCommand.reportOf(cmd).receded());
        cmd.undo();
        assertNull(inner.getFillColor());
    }

    @Test
    public void reportOf_findsTheRecedeThroughTheWrap_andNothingWithoutOne() {
        IDiagramModelArchimateObject parent = element("p");
        parent.setId("vo-parent");
        Command base = new AddToViewCommand(element("c"), parent);
        Command wrapped = RecedeContainerFillCommand.wrap(base, parent, null);
        // nothing is recorded before the recede runs: a queued placement reports no forecast
        assertFalse(RecedeContainerFillCommand.reportOf(wrapped).receded());
        // the compound's own execute needs the editor plugin, so run only the recede it holds
        for (Object child : ((org.eclipse.gef.commands.CompoundCommand) wrapped).getCommands()) {
            if (child instanceof RecedeContainerFillCommand recede) {
                recede.execute();
            }
        }
        assertEquals("vo-parent -> #F4F4F4", RecedeContainerFillCommand.reportOf(wrapped).toString());
        assertNull(RecedeContainerFillCommand.reportOf(base));
    }
}
