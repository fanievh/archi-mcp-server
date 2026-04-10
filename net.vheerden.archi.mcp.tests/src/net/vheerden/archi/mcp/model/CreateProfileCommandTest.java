package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IProfile;

/**
 * Tests for {@link CreateProfileCommand} (Story C3c).
 */
public class CreateProfileCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IProfile profile;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();

        profile = factory.createProfile();
        profile.setName("Cloud Server");
        profile.setConceptType("Node");
    }

    @Test
    public void shouldAddProfileToModel_whenExecuted() {
        CreateProfileCommand cmd = new CreateProfileCommand(profile, model);

        cmd.execute();

        assertTrue("Model should contain the new profile",
                model.getProfiles().contains(profile));
        assertEquals(1, model.getProfiles().size());
    }

    @Test
    public void shouldRemoveProfileFromModel_whenUndone() {
        CreateProfileCommand cmd = new CreateProfileCommand(profile, model);
        cmd.execute();

        cmd.undo();

        assertFalse("Model should not contain the profile after undo",
                model.getProfiles().contains(profile));
        assertEquals(0, model.getProfiles().size());
    }

    @Test
    public void shouldBeIdempotentOnDoubleUndo() {
        CreateProfileCommand cmd = new CreateProfileCommand(profile, model);
        cmd.execute();
        cmd.undo();
        cmd.undo(); // should not throw

        assertFalse(model.getProfiles().contains(profile));
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        CreateProfileCommand cmd = new CreateProfileCommand(profile, model);

        String label = cmd.getLabel();

        assertTrue("Label should contain profile name", label.contains("Cloud Server"));
        assertTrue("Label should describe the action", label.contains("Create"));
    }

    @Test
    public void shouldExposeProfile_packageVisible() {
        CreateProfileCommand cmd = new CreateProfileCommand(profile, model);

        assertSame(profile, cmd.getProfile());
    }
}
