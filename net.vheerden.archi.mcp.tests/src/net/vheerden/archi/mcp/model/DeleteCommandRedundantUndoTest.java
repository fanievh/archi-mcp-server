package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;

/**
 * Pins undo integrity when one compound removes the SAME target twice — the shape a batch
 * produces when a caller queues the same delete redundantly, or when a folder-cascade delete
 * overlaps a standalone delete of a contained item. Each delete command captures its
 * re-insertion anchor at construction, before any execute, so both hold the same pre-delete
 * position; committing removes the target once, and the compound's reverse-order undo runs both
 * restores. Without the already-present guard the second restore re-adds the target into an EMF
 * containment list, which forbids duplicates and throws {@code IllegalArgumentException}
 * ("no duplicates"), aborting the undo mid-way and leaving the model half-restored.
 *
 * <p>These cover the two distinct re-insertion sites: {@link DeleteRelationshipCommand} routes its
 * undo through the shared {@link SiblingUndoAnchor#restore}, while {@link DeleteElementCommand}
 * carries its own inline {@code safeAddElement}. Both must be idempotent. Commands are constructed
 * directly and driven with raw {@code execute()/undo()}, mirroring
 * {@code DeleteRelationshipCommandCompoundUndoTest}; no dispatcher, no {@code CommandStack}.</p>
 */
public class DeleteCommandRedundantUndoTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder relations;
    private IFolder business;
    private IBusinessActor e1;
    private IBusinessActor e2;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;

        model = factory.createArchimateModel();
        model.setName("Redundant Delete Undo Fixture");
        model.setId("model-redundant-delete-undo");
        model.setDefaults();
        relations = model.getFolder(FolderType.RELATIONS);
        business = model.getFolder(FolderType.BUSINESS);

        e1 = factory.createBusinessActor();
        e1.setId("e1");
        e1.setName("Actor 1");
        business.getElements().add(e1);
        e2 = factory.createBusinessActor();
        e2.setId("e2");
        e2.setName("Actor 2");
        business.getElements().add(e2);
    }

    private IArchimateRelationship newRel(String id) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setName(id);
        rel.connect(e1, e2);
        relations.getElements().add(rel);
        return rel;
    }

    private DeleteRelationshipCommand deleteOf(IArchimateRelationship rel) {
        int index = relations.getElements().indexOf(rel);
        return new DeleteRelationshipCommand(rel, relations, index, e1, e2, List.of());
    }

    private IBusinessActor newActor(String id) {
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId(id);
        actor.setName(id);
        business.getElements().add(actor);
        return actor;
    }

    private DeleteElementCommand deleteOf(IArchimateElement element) {
        int index = business.getElements().indexOf(element);
        return new DeleteElementCommand(element, business, index, List.of(), List.of(), List.of());
    }

    /**
     * Relationship kind — routes undo through the shared {@link SiblingUndoAnchor#restore}.
     */
    @Test
    public void shouldUndoCleanly_whenSameRelationshipDeletedTwiceInOneCompound() {
        IArchimateRelationship rel = newRel("r1");
        int before = relations.getElements().size();

        CompoundCommand compound = new CompoundCommand("delete the same relationship twice");
        compound.add(deleteOf(rel));
        compound.add(deleteOf(rel));

        compound.execute();
        assertFalse("Forward: the relationship is removed", relations.getElements().contains(rel));

        compound.undo();

        assertEquals("Undo restores the relationship EXACTLY once — no duplicate re-insertion",
                1, Collections.frequency(relations.getElements(), rel));
        assertEquals("Folder membership is exactly restored", before, relations.getElements().size());
    }

    /**
     * Element kind — routes undo through {@link DeleteElementCommand}'s own inline re-insertion,
     * a separate site from the shared helper that needs the same idempotency guard.
     */
    @Test
    public void shouldUndoCleanly_whenSameElementDeletedTwiceInOneCompound() {
        IBusinessActor actor = newActor("a1");
        int before = business.getElements().size();

        CompoundCommand compound = new CompoundCommand("delete the same element twice");
        compound.add(deleteOf(actor));
        compound.add(deleteOf(actor));

        compound.execute();
        assertFalse("Forward: the element is removed", business.getElements().contains(actor));

        compound.undo();

        assertEquals("Undo restores the element EXACTLY once — no duplicate re-insertion",
                1, Collections.frequency(business.getElements(), actor));
        assertEquals("Folder membership is exactly restored", before, business.getElements().size());
        assertTrue("The element is back in its folder", business.getElements().contains(actor));
    }
}
