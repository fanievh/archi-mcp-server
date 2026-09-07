package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

/**
 * Pins undo restoration ORDER for {@link MoveToFolderCommand} when several
 * siblings are moved out of the same source folder inside one GEF
 * {@link CompoundCommand}.
 *
 * <p>Undo returns each object to its source folder at the captured absolute
 * index. Under reverse compound undo an earlier-moved sibling is not yet back, so
 * the index overshoots and the source folder order is silently re-shuffled —
 * membership is preserved, position is not. No {@code CommandStack}.</p>
 */
public class MoveToFolderCommandSiblingUndoTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder source;
    private IFolder target;
    private IArchimateElement e1;
    private IArchimateElement e2;
    private IArchimateElement e3;
    private IArchimateElement e4;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;

        model = factory.createArchimateModel();
        model.setName("Move Order Fixture");
        model.setId("model-move-order");
        model.setDefaults();

        IFolder business = model.getFolder(FolderType.BUSINESS);
        source = factory.createFolder();
        source.setName("Source");
        business.getFolders().add(source);
        target = factory.createFolder();
        target.setName("Target");
        business.getFolders().add(target);

        e1 = newElement("e1");
        e2 = newElement("e2");
        e3 = newElement("e3");
        e4 = newElement("e4");
    }

    private IArchimateElement newElement(String id) {
        IArchimateElement el = factory.createBusinessActor();
        el.setId(id);
        el.setName(id);
        source.getElements().add(el);
        return el;
    }

    private MoveToFolderCommand moveOf(IArchimateElement el) {
        int index = source.getElements().indexOf(el);
        return new MoveToFolderCommand(el, false, source, index, target);
    }

    private List<String> sourceIds() {
        List<String> ids = new ArrayList<>();
        for (Object o : source.getElements()) {
            if (o instanceof IArchimateElement el) {
                ids.add(el.getId());
            }
        }
        return ids;
    }

    @Test
    public void shouldRestoreSourceOrder_whenCompoundMovesThreeSiblings() {
        CompoundCommand compound = new CompoundCommand("Move three elements");
        compound.add(moveOf(e1));
        compound.add(moveOf(e2));
        compound.add(moveOf(e3));

        compound.execute();
        assertEquals("Only e4 remains in source after moves", List.of("e4"), sourceIds());

        compound.undo();

        assertTrue("e1 back in source", source.getElements().contains(e1));
        assertTrue("e2 back in source", source.getElements().contains(e2));
        assertTrue("e3 back in source", source.getElements().contains(e3));
        assertEquals("All four back in source", 4, source.getElements().size());

        assertEquals("Source folder order must be restored exactly after compound undo",
                List.of("e1", "e2", "e3", "e4"), sourceIds());
    }

    @Test
    public void shouldRestoreAtOriginalIndex_whenSingleElementMoved() {
        MoveToFolderCommand cmd = moveOf(e2);

        cmd.execute();
        assertEquals("e2 moved out", List.of("e1", "e3", "e4"), sourceIds());

        cmd.undo();
        assertEquals("Single move restores at the exact original index",
                List.of("e1", "e2", "e3", "e4"), sourceIds());
    }
}
