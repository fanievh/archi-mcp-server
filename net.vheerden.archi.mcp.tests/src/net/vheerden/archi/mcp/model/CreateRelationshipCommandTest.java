package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
 * Tests for {@link CreateRelationshipCommand} (Story 7-2, B19).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (connect + add to folder) and undo (remove + disconnect) behavior.</p>
 *
 * <p><strong>B19:</strong> Tests verify that connect() is deferred to execute(),
 * not called during preparation. The relationship is NOT connected in setUp().</p>
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

        // B19: relationship is NOT connected — connect() happens inside execute()
        relationship = factory.createServingRelationship();
        relationship.setName("serves");
    }

    @Test
    public void shouldConnectAndAddRelationshipToFolder_whenExecuted() {
        CreateRelationshipCommand cmd = new CreateRelationshipCommand(
                relationship, relationsFolder, source, target);

        cmd.execute();

        assertTrue("Relationship should be in folder",
                relationsFolder.getElements().contains(relationship));
        assertEquals("Source should be connected",
                source, relationship.getSource());
        assertEquals("Target should be connected",
                target, relationship.getTarget());
    }

    @Test
    public void shouldNotBeConnected_beforeExecute() {
        // B19: verify deferred connect — relationship has no cross-refs before execute
        new CreateRelationshipCommand(relationship, relationsFolder, source, target);

        assertNull("Source should be null before execute",
                relationship.getSource());
        assertNull("Target should be null before execute",
                relationship.getTarget());
        assertTrue("Should not appear in source's relationships",
                source.getSourceRelationships().isEmpty());
    }

    @Test
    public void shouldRemoveRelationshipFromFolder_whenUndone() {
        CreateRelationshipCommand cmd = new CreateRelationshipCommand(
                relationship, relationsFolder, source, target);
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
                relationship, relationsFolder, source, target);
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
                relationship, relationsFolder, source, target);

        String label = cmd.getLabel();

        assertTrue("Label should contain relationship type",
                label.contains("ServingRelationship"));
    }

    @Test
    public void shouldReconnectAndAddToFolder_whenRedoneAfterUndo() {
        CreateRelationshipCommand cmd = new CreateRelationshipCommand(
                relationship, relationsFolder, source, target);
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
                relationship, relationsFolder, source, target);
        cmd.execute();

        assertEquals("Should have one more relationship",
                initialCount + 1, relationsFolder.getElements().size());
    }
}
