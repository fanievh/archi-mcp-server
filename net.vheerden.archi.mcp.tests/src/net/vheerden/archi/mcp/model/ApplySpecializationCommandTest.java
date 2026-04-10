package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IProfile;

/**
 * Tests for {@link ApplySpecializationCommand} (Story C3b).
 *
 * <p>Verifies that profile assignment and reversal work correctly for both
 * pre-existing profiles (already in {@code model.getProfiles()}) and newly
 * created profiles (must be added to model on execute, removed on undo).</p>
 */
public class ApplySpecializationCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IBusinessActor concept;
    private IProfile profile;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();

        concept = factory.createBusinessActor();
        concept.setName("Test Actor");

        profile = factory.createProfile();
        profile.setName("VIP");
        profile.setConceptType("BusinessActor");
    }

    @Test
    public void shouldAddProfileToConcept_whenExecuted() {
        ApplySpecializationCommand cmd = new ApplySpecializationCommand(
                concept, profile, model, false);

        cmd.execute();

        assertTrue("Concept should have the profile",
                concept.getProfiles().contains(profile));
    }

    @Test
    public void shouldAddProfileToModel_whenExecutedWithNewProfile() {
        ApplySpecializationCommand cmd = new ApplySpecializationCommand(
                concept, profile, model, true);

        cmd.execute();

        assertTrue("Model should contain the new profile",
                model.getProfiles().contains(profile));
        assertTrue("Concept should have the profile",
                concept.getProfiles().contains(profile));
    }

    @Test
    public void shouldNotAddToModel_whenProfileIsExisting() {
        // Pre-add the profile to the model
        model.getProfiles().add(profile);
        int initialCount = model.getProfiles().size();

        ApplySpecializationCommand cmd = new ApplySpecializationCommand(
                concept, profile, model, false);
        cmd.execute();

        assertEquals("Model profile count should not change",
                initialCount, model.getProfiles().size());
    }

    @Test
    public void shouldRemoveProfileFromConcept_whenUndone() {
        ApplySpecializationCommand cmd = new ApplySpecializationCommand(
                concept, profile, model, false);
        cmd.execute();

        cmd.undo();

        assertFalse("Concept should not have the profile after undo",
                concept.getProfiles().contains(profile));
    }

    @Test
    public void shouldRemoveProfileFromModel_whenUndoneWithNewProfile() {
        ApplySpecializationCommand cmd = new ApplySpecializationCommand(
                concept, profile, model, true);
        cmd.execute();

        cmd.undo();

        assertFalse("Model should not contain the profile after undo",
                model.getProfiles().contains(profile));
        assertFalse("Concept should not have the profile after undo",
                concept.getProfiles().contains(profile));
    }

    @Test
    public void shouldKeepModelProfile_whenUndoneWithExistingProfile() {
        // Pre-add the profile to the model
        model.getProfiles().add(profile);

        ApplySpecializationCommand cmd = new ApplySpecializationCommand(
                concept, profile, model, false);
        cmd.execute();
        cmd.undo();

        assertTrue("Model should still contain the existing profile after undo",
                model.getProfiles().contains(profile));
        assertFalse("Concept should not have the profile after undo",
                concept.getProfiles().contains(profile));
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        ApplySpecializationCommand cmd = new ApplySpecializationCommand(
                concept, profile, model, false);

        String label = cmd.getLabel();

        assertTrue("Label should contain profile name", label.contains("VIP"));
    }
}
