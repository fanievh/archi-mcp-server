package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IProfile;

/**
 * Tests for {@link UpdateProfileCommand} (Story C3c).
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
}
