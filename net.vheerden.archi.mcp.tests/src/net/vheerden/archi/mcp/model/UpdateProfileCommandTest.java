package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IProfile;

/**
 * Tests for {@link UpdateProfileCommand}.
 */
public class UpdateProfileCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IProfile profile;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();

        profile = factory.createProfile();
        profile.setName("Old Name");
        profile.setConceptType("Node");
        model.getProfiles().add(profile);
    }

    @Test
    public void shouldRenameProfile_whenExecuted() {
        UpdateProfileCommand cmd = new UpdateProfileCommand(profile, "New Name");

        cmd.execute();

        assertEquals("New Name", profile.getName());
    }

    @Test
    public void shouldRestoreOldName_whenUndone() {
        UpdateProfileCommand cmd = new UpdateProfileCommand(profile, "New Name");
        cmd.execute();

        cmd.undo();

        assertEquals("Old Name", profile.getName());
    }

    @Test
    public void shouldCaptureOldNameAtConstructionTime() {
        UpdateProfileCommand cmd = new UpdateProfileCommand(profile, "New Name");
        // Mutate the profile externally before executing — undo should still
        // restore the name captured at construction.
        profile.setName("Interim");

        cmd.execute();
        cmd.undo();

        assertEquals("Old Name", profile.getName());
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        UpdateProfileCommand cmd = new UpdateProfileCommand(profile, "New Name");

        String label = cmd.getLabel();

        assertTrue("Label should mention old name", label.contains("Old Name"));
        assertTrue("Label should mention new name", label.contains("New Name"));
    }

    // ---- G16 AXIS B — imagePath snapshot/apply/undo/idempotence ----

    @Test
    public void shouldSetImagePath_whenExecuted() {
        UpdateProfileCommand cmd = new UpdateProfileCommand(profile, null,
                UpdateProfileCommand.ImagePathChange.setTo("images/cloud.png"));

        cmd.execute();

        assertEquals("imagePath should be applied",
                "images/cloud.png", profile.getImagePath());
        assertEquals("Name should be unchanged when newName is null",
                "Old Name", profile.getName());
    }

    @Test
    public void shouldRestoreOldImagePath_whenUndone() {
        profile.setImagePath("images/old.png");
        UpdateProfileCommand cmd = new UpdateProfileCommand(profile, null,
                UpdateProfileCommand.ImagePathChange.setTo("images/new.png"));
        cmd.execute();
        assertEquals("images/new.png", profile.getImagePath());

        cmd.undo();

        assertEquals("imagePath should be restored to pre-execute value",
                "images/old.png", profile.getImagePath());
    }

    @Test
    public void shouldClearImagePath_whenClearKindSupplied() {
        profile.setImagePath("images/will-be-cleared.png");
        UpdateProfileCommand cmd = new UpdateProfileCommand(profile, null,
                UpdateProfileCommand.ImagePathChange.clear());

        cmd.execute();

        assertEquals("imagePath should be cleared (null)",
                null, profile.getImagePath());

        cmd.undo();
        assertEquals("Cleared imagePath restored to pre-clear value",
                "images/will-be-cleared.png", profile.getImagePath());
    }

    @Test
    public void shouldBeIdempotentOnSameValueImagePathSet() {
        // Task 0.4 EMF probe pin: IProfile.setImagePath is non-idempotent
        // (raw putfield + eNotify on same-value sets). UpdateProfileCommand
        // MUST guard with Objects.equals before invoking the setter.
        profile.setImagePath("images/same.png");
        // Force the test to be observable: if the guard were missing, undo
        // would restore null (since execute would call setImagePath(same)
        // which the EMF impl would still execute, but the captured
        // oldImagePath would be the pre-execute value — same.png).
        // The simpler observable: post-undo state matches pre-execute state.
        UpdateProfileCommand cmd = new UpdateProfileCommand(profile, null,
                UpdateProfileCommand.ImagePathChange.setTo("images/same.png"));
        cmd.execute();
        assertEquals("Same-value set leaves imagePath unchanged",
                "images/same.png", profile.getImagePath());
        cmd.undo();
        assertEquals("Undo of same-value set is a no-op (still same.png)",
                "images/same.png", profile.getImagePath());
    }

    @Test
    public void shouldCombineRenameAndImagePathChange() {
        UpdateProfileCommand cmd = new UpdateProfileCommand(profile, "Renamed",
                UpdateProfileCommand.ImagePathChange.setTo("images/combined.png"));

        cmd.execute();

        assertEquals("Renamed", profile.getName());
        assertEquals("images/combined.png", profile.getImagePath());

        cmd.undo();
        assertEquals("Old Name", profile.getName());
        assertEquals("Both fields restored on undo", null, profile.getImagePath());
    }

    @Test
    public void shouldPreserveLegacy2ArgCtor() {
        // The 2-arg ctor is kept as a back-compat delegating ctor that
        // calls the 3-arg ctor with ImagePathChange.unchanged().
        UpdateProfileCommand cmd = new UpdateProfileCommand(profile, "BackCompat");
        cmd.execute();
        assertEquals("BackCompat", profile.getName());
        assertEquals("imagePath stays at its pre-command value (unchanged)",
                null, profile.getImagePath()); // profile started with null imagePath
    }
}
