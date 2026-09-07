package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

/**
 * Pins undo restoration ORDER for {@link DeleteViewCommand}'s two re-insert sites
 * when several views are deleted inside one GEF {@link CompoundCommand}.
 *
 * <p><b>Site 1 — the view among its sibling views ({@code viewIndex}).</b> This
 * index is captured at prepare time against the full folder, so under reverse
 * compound undo it overshoots into the collapsed survivors: the diagrams folder
 * order is silently re-shuffled. This is the vulnerable site.</p>
 *
 * <p><b>Site 2 — cascaded external placeholders.</b> These indices are captured at
 * <em>execute</em> time, relative to the list as it collapses, so reverse compound
 * undo unwinds them self-consistently. The third test characterizes this site and
 * guards it as a regression pin; it passes without the fix. No {@code CommandStack}.</p>
 */
public class DeleteViewCommandSiblingUndoTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder diagrams;
    private IArchimateDiagramModel v1;
    private IArchimateDiagramModel v2;
    private IArchimateDiagramModel v3;
    private IArchimateDiagramModel v4;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;

        model = factory.createArchimateModel();
        model.setName("View Order Fixture");
        model.setId("model-view-order");
        model.setDefaults();
        diagrams = model.getFolder(FolderType.DIAGRAMS);

        v1 = newView("v1");
        v2 = newView("v2");
        v3 = newView("v3");
        v4 = newView("v4");
    }

    private IArchimateDiagramModel newView(String id) {
        IArchimateDiagramModel v = factory.createArchimateDiagramModel();
        v.setId(id);
        v.setName(id);
        diagrams.getElements().add(v);
        return v;
    }

    private DeleteViewCommand deleteOf(IArchimateDiagramModel view) {
        int index = diagrams.getElements().indexOf(view);
        return new DeleteViewCommand(view, diagrams, index);
    }

    private List<String> viewIds() {
        List<String> ids = new ArrayList<>();
        for (Object o : diagrams.getElements()) {
            if (o instanceof IArchimateDiagramModel v) {
                ids.add(v.getId());
            }
        }
        return ids;
    }

    @Test
    public void shouldRestoreViewFolderOrder_whenCompoundDeletesThreeSiblings() {
        CompoundCommand compound = new CompoundCommand("Delete three views");
        compound.add(deleteOf(v1));
        compound.add(deleteOf(v2));
        compound.add(deleteOf(v3));

        compound.execute();
        assertEquals("Only v4 survives execute", List.of("v4"), viewIds());

        compound.undo();

        assertTrue("v1 restored", diagrams.getElements().contains(v1));
        assertTrue("v2 restored", diagrams.getElements().contains(v2));
        assertTrue("v3 restored", diagrams.getElements().contains(v3));
        assertEquals("All four views present after undo", 4, diagrams.getElements().size());

        assertEquals("Diagrams folder order must be restored exactly after compound undo",
                List.of("v1", "v2", "v3", "v4"), viewIds());
    }

    @Test
    public void shouldRestoreAtOriginalIndex_whenSingleViewDeleted() {
        DeleteViewCommand cmd = deleteOf(v2);

        cmd.execute();
        assertEquals("v2 removed", List.of("v1", "v3", "v4"), viewIds());

        cmd.undo();
        assertEquals("Single view delete restores at the exact original index",
                List.of("v1", "v2", "v3", "v4"), viewIds());
    }

    /**
     * Characterizes site 2 (cascaded external placeholders). A holding view H
     * contains three placeholders pointing at v1, v2, v3; deleting all three in one
     * compound removes each placeholder from H and undo restores them. Because the
     * cascaded indices are captured at execute time relative to the collapsing list,
     * reverse compound undo restores H's children in their original order without the
     * successor-anchor fix. Kept as a regression pin.
     */
    @Test
    public void shouldRestoreCascadedPlaceholderOrder_whenCompoundDeletesReferencedViews() {
        IArchimateDiagramModel holding = newView("holding");
        IDiagramModelReference p1 = placeholderFor(holding, v1, "p1");
        IDiagramModelReference p2 = placeholderFor(holding, v2, "p2");
        IDiagramModelReference p3 = placeholderFor(holding, v3, "p3");

        CompoundCommand compound = new CompoundCommand("Delete three referenced views");
        compound.add(deleteOf(v1));
        compound.add(deleteOf(v2));
        compound.add(deleteOf(v3));

        compound.execute();
        assertEquals("All placeholders removed from holding view on execute",
                0, holding.getChildren().size());

        compound.undo();

        List<Object> children = new ArrayList<>(holding.getChildren());
        assertEquals("All three placeholders restored", 3, children.size());
        assertEquals("Cascaded placeholder order preserved", List.of(p1, p2, p3), children);
    }

    private IDiagramModelReference placeholderFor(IArchimateDiagramModel holding,
                                                  IArchimateDiagramModel referenced, String id) {
        IDiagramModelReference ref = factory.createDiagramModelReference();
        ref.setId(id);
        ref.setReferencedModel(referenced);
        ref.setBounds(0, 0, 120, 55);
        holding.getChildren().add(ref);
        return ref;
    }
}
