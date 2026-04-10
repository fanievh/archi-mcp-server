package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
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
}
