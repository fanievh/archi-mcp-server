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
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IFolder;

/**
 * Pins undo restoration ORDER for {@link DeleteRelationshipCommand} when several
 * relationships in the same folder are deleted inside one GEF
 * {@link CompoundCommand} — the shape a batch or {@code bulk-mutate} request
 * produces.
 *
 * <p>A compound undoes its members in reverse, so a later-listed delete restores
 * into a folder whose earlier-listed deletes are not yet back. Restoring at an
 * absolute index captured before any of them ran then overshoots into the collapsed
 * survivors and the folder order is silently re-shuffled — membership is preserved,
 * position is not.
 * Commands are constructed directly and driven with raw {@code execute()/undo()},
 * mirroring {@code DeleteViewCommandCompoundCascadeTest}; no {@code CommandStack}.</p>
 */
public class DeleteRelationshipCommandCompoundUndoTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder relations;
    private IArchimateElement e1;
    private IArchimateElement e2;
    private IArchimateRelationship r1;
    private IArchimateRelationship r2;
    private IArchimateRelationship r3;
    private IArchimateRelationship r4;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;

        model = factory.createArchimateModel();
        model.setName("Relationship Order Fixture");
        model.setId("model-rel-order");
        model.setDefaults();
        relations = model.getFolder(FolderType.RELATIONS);
        IFolder business = model.getFolder(FolderType.BUSINESS);

        e1 = factory.createBusinessActor();
        e1.setId("e1");
        e1.setName("Actor 1");
        business.getElements().add(e1);
        e2 = factory.createBusinessActor();
        e2.setId("e2");
        e2.setName("Actor 2");
        business.getElements().add(e2);

        r1 = newRel("r1");
        r2 = newRel("r2");
        r3 = newRel("r3");
        r4 = newRel("r4");
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
        // The command takes its index and anchor when it runs, against the folder as it meets it.
        return new DeleteRelationshipCommand(rel, new DeleteCascade.OfRelationship(List.of(), List.of(), 0));
    }

    private List<String> relationIds() {
        List<String> ids = new ArrayList<>();
        for (Object o : relations.getElements()) {
            if (o instanceof IArchimateRelationship rel) {
                ids.add(rel.getId());
            }
        }
        return ids;
    }

    @Test
    public void shouldRestoreFolderOrder_whenCompoundDeletesThreeSiblings() {
        // Folder starts [r1, r2, r3, r4]; delete the first three in one compound.
        CompoundCommand compound = new CompoundCommand("Delete three relationships");
        compound.add(deleteOf(r1));
        compound.add(deleteOf(r2));
        compound.add(deleteOf(r3));

        compound.execute();
        assertEquals("Only r4 survives execute", List.of("r4"), relationIds());

        compound.undo();

        // Membership must always be intact regardless of order.
        assertTrue("r1 restored", relations.getElements().contains(r1));
        assertTrue("r2 restored", relations.getElements().contains(r2));
        assertTrue("r3 restored", relations.getElements().contains(r3));
        assertEquals("All four relationships present after undo", 4, relations.getElements().size());

        // The load-bearing assertion: original order restored.
        assertEquals("Folder order must be restored exactly after compound undo",
                List.of("r1", "r2", "r3", "r4"), relationIds());
    }

    @Test
    public void shouldRestoreAtOriginalIndex_whenSingleRelationshipDeleted() {
        // Byte-identical single-delete guarantee: no co-deleted sibling, exact index.
        DeleteRelationshipCommand cmd = deleteOf(r2);

        cmd.execute();
        assertEquals("r2 removed", List.of("r1", "r3", "r4"), relationIds());

        cmd.undo();
        assertEquals("Single delete restores at the exact original index",
                List.of("r1", "r2", "r3", "r4"), relationIds());
    }

    @Test
    public void shouldRestoreTailAtOriginalIndex_whenSingleTailRelationshipDeleted() {
        // Byte-identical for a TAIL item, whose successor is null — this drives the
        // fallback-index branch of the anchor (no surviving successor to anchor to),
        // the path the ascending-order compound tests never reach.
        DeleteRelationshipCommand cmd = deleteOf(r4);

        cmd.execute();
        assertEquals("r4 removed", List.of("r1", "r2", "r3"), relationIds());

        cmd.undo();
        assertEquals("Tail delete restores at the exact original index via the fallback path",
                List.of("r1", "r2", "r3", "r4"), relationIds());
    }

    @Test
    public void shouldRestoreFolderOrder_whenThreeSiblingsDeletedInNonMonotonicOrder() {
        // Each delete takes its anchor against the folder as it meets it, after the deletes
        // before it ran, so the reverse undo meets the folder exactly as each delete left it and
        // restores the order exactly, in any compound order. Anchors taken at prepare, against
        // the untouched folder, left r2 with an anchor (r3) not yet back when r2 restored.
        CompoundCommand compound = new CompoundCommand("Delete three relationships non-monotonic");
        compound.add(deleteOf(r3));
        compound.add(deleteOf(r1));
        compound.add(deleteOf(r2));

        compound.execute();
        assertEquals("Only r4 survives execute", List.of("r4"), relationIds());

        compound.undo();

        // Every relationship is back and reconnected, in its original order.
        assertTrue("r1 restored", relations.getElements().contains(r1));
        assertTrue("r2 restored", relations.getElements().contains(r2));
        assertTrue("r3 restored", relations.getElements().contains(r3));
        assertTrue("r4 present", relations.getElements().contains(r4));
        assertEquals("Membership fully intact after non-monotonic compound undo",
                4, relations.getElements().size());
        assertEquals("Order is restored exactly", List.of("r1", "r2", "r3", "r4"), relationIds());
    }
}
