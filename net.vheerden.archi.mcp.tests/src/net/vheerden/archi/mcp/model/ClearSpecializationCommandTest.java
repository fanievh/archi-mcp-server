package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IProfile;

/**
 * Tests for {@link ClearSpecializationCommand} (Story C3b).
 *
 * <p>Verifies that all profiles are removed from the concept on execute, and
 * fully restored on undo.</p>
 */
public class ClearSpecializationCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IBusinessActor concept;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();

        concept = factory.createBusinessActor();
        concept.setName("Test Actor");
    }

    @Test
    public void shouldRemoveAllProfilesFromConcept_whenExecuted() {
        IProfile p1 = factory.createProfile();
        p1.setName("VIP");
        p1.setConceptType("BusinessActor");
        IProfile p2 = factory.createProfile();
        p2.setName("Internal");
        p2.setConceptType("BusinessActor");
        concept.getProfiles().add(p1);
        concept.getProfiles().add(p2);

        ClearSpecializationCommand cmd = new ClearSpecializationCommand(concept);
        cmd.execute();

        assertTrue("Concept should have no profiles after clear",
                concept.getProfiles().isEmpty());
    }

    @Test
    public void shouldRestoreAllProfiles_whenUndone() {
        IProfile p1 = factory.createProfile();
        p1.setName("VIP");
        p1.setConceptType("BusinessActor");
        IProfile p2 = factory.createProfile();
        p2.setName("Internal");
        p2.setConceptType("BusinessActor");
        concept.getProfiles().add(p1);
        concept.getProfiles().add(p2);

        ClearSpecializationCommand cmd = new ClearSpecializationCommand(concept);
        cmd.execute();
        cmd.undo();

        assertEquals("Concept should have both profiles restored",
                2, concept.getProfiles().size());
        assertTrue("First profile restored", concept.getProfiles().contains(p1));
        assertTrue("Second profile restored", concept.getProfiles().contains(p2));
    }

    @Test
    public void shouldHandleEmptyProfileList_gracefully() {
        ClearSpecializationCommand cmd = new ClearSpecializationCommand(concept);

        cmd.execute();

        assertTrue("Concept should still have no profiles",
                concept.getProfiles().isEmpty());

        cmd.undo();

        assertTrue("Concept should still have no profiles after undo",
                concept.getProfiles().isEmpty());
    }

    @Test
    public void shouldNotAffectModelProfiles_whenExecuted() {
        IProfile p1 = factory.createProfile();
        p1.setName("VIP");
        p1.setConceptType("BusinessActor");
        model.getProfiles().add(p1);
        concept.getProfiles().add(p1);

        ClearSpecializationCommand cmd = new ClearSpecializationCommand(concept);
        cmd.execute();

        assertFalse("Concept should not have the profile",
                concept.getProfiles().contains(p1));
        assertTrue("Model should still contain the profile",
                model.getProfiles().contains(p1));
    }

    // ---- Authorised-set guard (deferred-path force-delete) ----

    @Test
    public void shouldDecline_whenConceptCarriesAnUnauthorisedProfile() {
        IProfile p1 = makeProfile("P1");   // the profile being deleted
        IProfile p2 = makeProfile("P2");   // attached later by a co-queued operation
        concept.getProfiles().add(p1);
        concept.getProfiles().add(p2);

        ClearSpecializationCommand cmd = new ClearSpecializationCommand(concept, Set.of(p1));
        cmd.execute();

        assertNotNull("Must decline rather than wipe an unauthorised profile", cmd.getSkipReason());
        assertEquals("Concept must keep both profiles", 2, concept.getProfiles().size());
        assertTrue("The unauthorised profile survives", concept.getProfiles().contains(p2));
        assertTrue("The authorised profile is also left in place", concept.getProfiles().contains(p1));
    }

    @Test
    public void shouldClear_whenEveryProfileIsAuthorised() {
        IProfile p1 = makeProfile("P1");
        concept.getProfiles().add(p1);

        ClearSpecializationCommand cmd = new ClearSpecializationCommand(concept, Set.of(p1));
        cmd.execute();

        assertNull("No skip when every profile is authorised", cmd.getSkipReason());
        assertTrue("Authorised profile cleared", concept.getProfiles().isEmpty());
    }

    @Test
    public void shouldBeInertOnUndo_whenItDeclined() {
        IProfile p1 = makeProfile("P1");
        IProfile p2 = makeProfile("P2");
        concept.getProfiles().add(p1);
        concept.getProfiles().add(p2);

        ClearSpecializationCommand cmd = new ClearSpecializationCommand(concept, Set.of(p1));
        cmd.execute();
        cmd.undo();

        assertEquals("Undo of a declined clear must not change the concept",
                2, concept.getProfiles().size());
    }

    @Test
    public void shouldClearAllUnconditionally_whenConstructedWithoutAuthorisedSet() {
        // update-element/update-relationship replace semantics: clear whatever is present,
        // never decline — the authorised-set guard must not leak into this shared path.
        IProfile p1 = makeProfile("P1");
        IProfile p2 = makeProfile("P2");
        concept.getProfiles().add(p1);
        concept.getProfiles().add(p2);

        ClearSpecializationCommand cmd = new ClearSpecializationCommand(concept);
        cmd.execute();

        assertNull("The clear-all constructor never declines", cmd.getSkipReason());
        assertTrue("Replace semantics must still clear everything", concept.getProfiles().isEmpty());
    }

    private IProfile makeProfile(String name) {
        IProfile p = factory.createProfile();
        p.setName(name);
        p.setConceptType("BusinessActor");
        return p;
    }
}
