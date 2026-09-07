package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IProfile;

/**
 * Reproduces the force-delete deferred-path scenario end to end, at the command level.
 *
 * <p>The production force compound is Archi's {@code NonNotifyingCompoundCommand}, whose
 * {@code execute()} wraps children in Ecore-event notifications that dereference the running
 * plug-in and therefore cannot run in a headless test. This drives the identical sub-commands
 * through a plain {@link CompoundCommand} instead, which preserves the one property the bug
 * depends on — the co-queued attach runs before the clear — without the notification wrapper.</p>
 */
public class DeleteSpecializationDeferredPathGuardTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IProfile victim;
    private IBusinessActor concept;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();

        victim = factory.createProfile();
        victim.setName("VIP");
        victim.setConceptType("BusinessActor");
        model.getProfiles().add(victim);

        concept = factory.createBusinessActor();
        concept.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(concept);
        concept.getProfiles().add(victim);
    }

    /**
     * A co-queued op attaches a second profile to the sole victim concept after the force-delete
     * was authorised. The clear must not wipe it, and the profile deletion must not orphan the
     * victim reference — the two decline in step, and nothing is lost.
     */
    @Test
    public void forceDeleteDeclinesCoherently_whenASecondProfileIsAttachedAfterAuthorisation() {
        // Prepared while the concept held only the victim (the multi-profile guard passed):
        CompoundCommand compound = new CompoundCommand("Delete specialization: VIP");
        compound.add(new ClearSpecializationCommand(concept, Set.of(victim)));
        compound.add(new DeleteProfileCommand(victim, model));

        // Earlier operation in the same request attaches a second, unrelated profile:
        IProfile other = factory.createProfile();
        other.setName("Internal");
        other.setConceptType("BusinessActor");
        model.getProfiles().add(other);
        concept.getProfiles().add(other);

        compound.execute();

        assertTrue("The second specialization must survive — this is the data loss the guard forbids",
                concept.getProfiles().contains(other));
        assertTrue("The victim reference must remain intact (no orphaned concept)",
                concept.getProfiles().contains(victim));
        assertTrue("The victim must stay in the catalog — deleting it would dangle the concept",
                model.getProfiles().contains(victim));

        List<String> reasons = CommitSkippableCommand.collectSkipReasons(compound);
        assertFalse("The declined operation must be reported, not silently dropped",
                reasons.isEmpty());
    }

    /**
     * bulk-mutate dispatches one compound whose children are the requested operations. A single
     * operation that declines coherently (both its leaves refusing) must surface as ONE
     * {@code skippedOperations} line, not two, so the count reconciles against the operation count.
     */
    @Test
    public void bulkGroupsACoherentDeclineIntoOneEntryPerOperation() {
        CompoundCommand forceDelete = new CompoundCommand("Delete specialization: VIP");
        forceDelete.add(new ClearSpecializationCommand(concept, Set.of(victim)));
        forceDelete.add(new DeleteProfileCommand(victim, model));

        // The bulk-mutate dispatch compound holds the delete as one child operation:
        CompoundCommand bulk = new CompoundCommand("Bulk mutation (1 operation)");
        bulk.add(forceDelete);

        IProfile other = factory.createProfile();
        other.setName("Internal");
        other.setConceptType("BusinessActor");
        model.getProfiles().add(other);
        concept.getProfiles().add(other);

        bulk.execute();

        assertEquals("Two leaves declined, so the flat reason list has two entries",
                2, CommitSkippableCommand.collectSkipReasons(bulk).size());
        assertEquals("...but they belong to one operation, so per-operation grouping yields one",
                1, CommitSkippableCommand.collectSkipReasonsByOperation(bulk).size());
    }

    /**
     * The undoable single entry stays reversible after a coherent decline: undoing the compound
     * leaves the model exactly as the co-queued attach left it, with nothing double-applied.
     */
    @Test
    public void undoAfterACoherentDeclineIsInert() {
        CompoundCommand compound = new CompoundCommand("Delete specialization: VIP");
        compound.add(new ClearSpecializationCommand(concept, Set.of(victim)));
        compound.add(new DeleteProfileCommand(victim, model));

        IProfile other = factory.createProfile();
        other.setName("Internal");
        other.setConceptType("BusinessActor");
        model.getProfiles().add(other);
        concept.getProfiles().add(other);

        compound.execute();
        compound.undo();

        assertEquals("Concept keeps both profiles after undo", 2, concept.getProfiles().size());
        assertTrue(concept.getProfiles().contains(victim));
        assertTrue(concept.getProfiles().contains(other));
        assertEquals("Catalog unchanged after undo", 2, model.getProfiles().size());
    }

    /**
     * When a <em>different</em> concept picks up the victim after authorisation, the profile
     * deletion declines (no orphaned reference) while the authorised clears still apply — the
     * fail-safe, non-atomic semantic, matching how {@code DeleteFolderCommand} leaves the
     * sub-commands that did run applied. The essential guarantee is that nothing is lost and
     * nothing dangles, not that the operation is all-or-nothing.
     */
    @Test
    public void profileDeletionDeclinesWithoutDangling_whenANewConceptPicksUpTheVictim() {
        // Prepared while only `concept` used the victim:
        CompoundCommand compound = new CompoundCommand("Delete specialization: VIP");
        compound.add(new ClearSpecializationCommand(concept, Set.of(victim)));
        compound.add(new DeleteProfileCommand(victim, model));

        // A co-queued op attaches the victim to a brand-new concept:
        IBusinessActor latecomer = factory.createBusinessActor();
        latecomer.setName("Supplier");
        model.getFolder(FolderType.BUSINESS).getElements().add(latecomer);
        latecomer.getProfiles().add(victim);

        compound.execute();

        assertTrue("The victim stays in the catalog — the new user is not orphaned",
                model.getProfiles().contains(victim));
        assertTrue("The latecomer keeps its specialization", latecomer.getProfiles().contains(victim));
        assertTrue("The authorised clear still applied", concept.getProfiles().isEmpty());
    }
}
