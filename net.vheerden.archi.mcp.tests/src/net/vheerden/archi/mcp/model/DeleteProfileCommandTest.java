package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IProfile;

/**
 * Tests for {@link DeleteProfileCommand} (Story C3c).
 */
public class DeleteProfileCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IProfile profileA;
    private IProfile profileB;
    private IProfile profileC;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();

        profileA = makeProfile("A", "Node");
        profileB = makeProfile("B", "Node");
        profileC = makeProfile("C", "Node");
        model.getProfiles().add(profileA);
        model.getProfiles().add(profileB);
        model.getProfiles().add(profileC);
    }

    private IProfile makeProfile(String name, String type) {
        IProfile p = factory.createProfile();
        p.setName(name);
        p.setConceptType(type);
        return p;
    }

    @Test
    public void shouldRemoveProfileFromModel_whenExecuted() {
        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model);

        cmd.execute();

        assertFalse(model.getProfiles().contains(profileB));
        assertEquals(2, model.getProfiles().size());
    }

    @Test
    public void shouldReinsertAtOriginalIndex_whenUndone() {
        // profileB is at index 1
        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model);
        cmd.execute();

        cmd.undo();

        assertEquals("Profile should be re-inserted at its original index",
                profileB, model.getProfiles().get(1));
        assertEquals(3, model.getProfiles().size());
    }

    @Test
    public void shouldClampIndex_whenOtherProfilesAlsoRemoved() {
        // Capture original index for profileC = 2
        DeleteProfileCommand cmdC = new DeleteProfileCommand(profileC, model);
        // Now externally remove profileA and profileB
        model.getProfiles().remove(profileA);
        model.getProfiles().remove(profileB);
        // List now has only profileC at index 0
        cmdC.execute();
        // List is empty
        assertEquals(0, model.getProfiles().size());

        cmdC.undo();
        // originalIndex was 2, but list is empty -> clamp to 0
        assertEquals(1, model.getProfiles().size());
        assertEquals(profileC, model.getProfiles().get(0));
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model);

        String label = cmd.getLabel();

        assertTrue("Label should contain profile name", label.contains("B"));
        assertTrue("Label should describe the action", label.contains("Delete"));
    }

    @Test
    public void shouldExposeOriginalIndex_packageVisible() {
        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model);

        assertEquals(1, cmd.getOriginalIndex());
    }

    // ---- Execute-time usage re-check (deferred-path guard) ----

    @Test
    public void shouldDeclineAndLeaveProfile_whenAConceptUsesItAtExecute() {
        // A concept attaches profileB after the delete was authorised — the shape an earlier
        // operation in the same batch or bulk request produces.
        IBusinessActor actor = factory.createBusinessActor();
        actor.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        actor.getProfiles().add(profileB);

        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model);
        cmd.execute();

        assertNotNull("Command must decline while the profile is still in use", cmd.getSkipReason());
        assertTrue("Profile must remain in the catalog, not be orphaned",
                model.getProfiles().contains(profileB));
        assertTrue("The using concept keeps its specialization",
                actor.getProfiles().contains(profileB));
    }

    @Test
    public void shouldBeInertOnUndo_whenItDeclined() {
        IBusinessActor actor = factory.createBusinessActor();
        actor.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        actor.getProfiles().add(profileB);
        int before = model.getProfiles().size();

        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model);
        cmd.execute();
        cmd.undo();

        assertEquals("Undo of a declined delete must not re-add anything",
                before, model.getProfiles().size());
    }

    @Test
    public void shouldRemoveAndReportNoSkip_whenProfileIsUnusedAtExecute() {
        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model);
        cmd.execute();

        assertNull("No skip when the profile is unused", cmd.getSkipReason());
        assertFalse(model.getProfiles().contains(profileB));
    }

    // ---- Force path: clears execution-time (deferred) usages instead of declining ----
    //
    // This is the bulk shape: delete-specialization force=true is prepared while the profile has
    // zero usages, then an earlier operation in the same batch attaches it before this runs. A
    // plain delete would decline (see above); force must clear the late attach and delete.

    @Test
    public void shouldForceClearDeferredUsage_andDelete_whenForceTrue() {
        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model, true);

        // The attach happens AFTER the command was built — exactly the deferred-batch shape.
        IBusinessActor actor = factory.createBusinessActor();
        actor.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        actor.getProfiles().add(profileB);

        cmd.execute();

        assertNull("Force delete must not decline on a clearable usage", cmd.getSkipReason());
        assertFalse("Profile removed from the catalog", model.getProfiles().contains(profileB));
        assertFalse("Profile stripped from the concept it was attached to",
                actor.getProfiles().contains(profileB));
    }

    @Test
    public void shouldReattachAndRestore_whenForceDeleteUndone() {
        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model, true);
        IBusinessActor actor = factory.createBusinessActor();
        actor.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        actor.getProfiles().add(profileB);

        cmd.execute();
        cmd.undo();

        assertEquals("Profile restored to its original catalog index",
                profileB, model.getProfiles().get(1));
        assertTrue("Concept's specialization re-attached on undo",
                actor.getProfiles().contains(profileB));
    }

    @Test
    public void shouldDeclineForce_whenAUsageConceptCarriesAnUnrelatedProfile() {
        // The concept the profile got attached to ALSO carries an unrelated profile the delete was
        // never authorised to remove — force must decline rather than cause collateral loss.
        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model, true);
        IBusinessActor actor = factory.createBusinessActor();
        actor.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        actor.getProfiles().add(profileB);
        actor.getProfiles().add(profileA);

        cmd.execute();

        assertNotNull("Force delete must decline when it would strip an unrelated profile",
                cmd.getSkipReason());
        assertTrue("Target profile left in the catalog", model.getProfiles().contains(profileB));
        assertTrue("Unrelated profile untouched", actor.getProfiles().contains(profileA));
        assertTrue("Target profile still attached (nothing cleared)",
                actor.getProfiles().contains(profileB));
    }

    @Test
    public void shouldDeleteNormally_whenForceTrueButUnused() {
        DeleteProfileCommand cmd = new DeleteProfileCommand(profileB, model, true);
        cmd.execute();

        assertNull("No skip when unused, force or not", cmd.getSkipReason());
        assertFalse(model.getProfiles().contains(profileB));
    }

    // ---- Redundant queued delete (same profile twice in one batch) ----

    /**
     * A batch that queues delete-specialization for the SAME profile twice produces two commands
     * against one catalog. The first removes it; the second finds it already gone. On the compound's
     * reverse-order undo, only the command that actually removed the profile may re-add it — a
     * second re-add would violate the catalog's no-duplicates constraint and throw, aborting undo.
     */
    @Test
    public void shouldUndoCleanly_whenSameProfileDeletedTwiceInOneCompound() {
        int before = model.getProfiles().size();

        CompoundCommand compound = new CompoundCommand("delete the same specialization twice");
        compound.add(new DeleteProfileCommand(profileB, model));
        compound.add(new DeleteProfileCommand(profileB, model));

        compound.execute();
        assertFalse("Forward: the profile is removed", model.getProfiles().contains(profileB));

        compound.undo();

        assertEquals("Undo restores the profile EXACTLY once — no duplicate re-insertion",
                1, Collections.frequency(model.getProfiles(), profileB));
        assertEquals("Catalog membership is exactly restored", before, model.getProfiles().size());
        assertEquals("Profile restored at its original index", profileB, model.getProfiles().get(1));
    }
}
