package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IApplicationComponent;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessProcess;
import com.archimatetool.model.IFolder;

/**
 * Tests for {@link CreateRelationshipCommand} (Story 7-2).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (add to folder) and undo (remove + disconnect) behavior.</p>
 */
public class CreateRelationshipCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder relationsFolder;
    private IArchimateRelationship relationship;
    private IApplicationComponent source;
    private IBusinessProcess target;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();
        relationsFolder = model.getFolder(FolderType.RELATIONS);

        source = factory.createApplicationComponent();
        source.setName("Order System");
        model.getFolder(FolderType.APPLICATION).getElements().add(source);

        target = factory.createBusinessProcess();
        target.setName("Order Processing");
        model.getFolder(FolderType.BUSINESS).getElements().add(target);

        relationship = factory.createServingRelationship();
        relationship.setName("serves");
        relationship.connect(source, target);
    }

    @Test
    public void shouldAddRelationshipToFolder_whenExecuted() {
        CreateRelationshipCommand cmd = new CreateRelationshipCommand(
                relationship, relationsFolder);

        cmd.execute();

        assertTrue("Relationship should be in folder",
                relationsFolder.getElements().contains(relationship));
    }

    @Test
    public void shouldRemoveRelationshipFromFolder_whenUndone() {
        CreateRelationshipCommand cmd = new CreateRelationshipCommand(
                relationship, relationsFolder);
        cmd.execute();

        cmd.undo();

        assertFalse("Relationship should not be in folder after undo",
                relationsFolder.getElements().contains(relationship));
    }

    @Test
    public void shouldRestoreExactState_whenExecuteThenUndo() {
        // Add an existing relationship first
        IArchimateRelationship existing = factory.createAssociationRelationship();
        existing.setName("existing");
        relationsFolder.getElements().add(existing);
        int initialCount = relationsFolder.getElements().size();

        CreateRelationshipCommand cmd = new CreateRelationshipCommand(
                relationship, relationsFolder);
        cmd.execute();
        cmd.undo();

        assertEquals("Should be back to initial count", initialCount,
                relationsFolder.getElements().size());
        assertFalse("New relationship should not be in folder after undo",
                relationsFolder.getElements().contains(relationship));
        assertTrue("Existing relationship should still be present",
                relationsFolder.getElements().contains(existing));
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        CreateRelationshipCommand cmd = new CreateRelationshipCommand(
                relationship, relationsFolder);

        String label = cmd.getLabel();

        assertTrue("Label should contain relationship type",
                label.contains("ServingRelationship"));
    }

    @Test
    public void shouldReconnectAndAddToFolder_whenRedoneAfterUndo() {
        CreateRelationshipCommand cmd = new CreateRelationshipCommand(
                relationship, relationsFolder);
        cmd.execute();
        cmd.undo();

        // After undo, disconnect() was called — redo must reconnect
        cmd.redo();

        assertTrue("Relationship should be in folder after redo",
                relationsFolder.getElements().contains(relationship));
        assertEquals("Source should be reconnected",
                source, relationship.getSource());
        assertEquals("Target should be reconnected",
                target, relationship.getTarget());
    }

    @Test
    public void shouldNotAffectExistingRelationships_whenExecuted() {
        IArchimateRelationship existing = factory.createAssociationRelationship();
        existing.setName("existing");
        relationsFolder.getElements().add(existing);
        int initialCount = relationsFolder.getElements().size();

        CreateRelationshipCommand cmd = new CreateRelationshipCommand(
                relationship, relationsFolder);
        cmd.execute();

        assertEquals("Should have one more relationship",
                initialCount + 1, relationsFolder.getElements().size());
    }
}
