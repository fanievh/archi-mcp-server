package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProperty;

/**
 * Tests for {@link UpdateRelationshipCommand} (Story C10).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute/undo/redo with name, documentation, and property changes.</p>
 */
public class UpdateRelationshipCommandTest {

    private IArchimateFactory factory;
    private IArchimateRelationship relationship;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        IFolder businessFolder = model.getFolder(FolderType.BUSINESS);
        IBusinessActor source = factory.createBusinessActor();
        source.setName("Source");
        businessFolder.getElements().add(source);

        IBusinessActor target = factory.createBusinessActor();
        target.setName("Target");
        businessFolder.getElements().add(target);

        IFolder relationsFolder = model.getFolder(FolderType.RELATIONS);
        relationship = factory.createAssociationRelationship();
        relationship.setName("Original Name");
        relationship.setDocumentation("Original docs");
        relationship.setSource(source);
        relationship.setTarget(target);
        relationsFolder.getElements().add(relationship);
    }

    // ---- Name-only update ----

    @Test
    public void shouldUpdateName_whenExecuted() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, "New Name", null, null);

        cmd.execute();

        assertEquals("New Name", relationship.getName());
        assertEquals("Original docs", relationship.getDocumentation());
    }

    @Test
    public void shouldRestoreName_whenUndone() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, "New Name", null, null);
        cmd.execute();

        cmd.undo();

        assertEquals("Original Name", relationship.getName());
    }

    @Test
    public void shouldReapplyName_whenRedone() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, "New Name", null, null);
        cmd.execute();
        cmd.undo();

        cmd.redo();

        assertEquals("New Name", relationship.getName());
    }

    // ---- Documentation-only update ----

    @Test
    public void shouldUpdateDocumentation_whenExecuted() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, "New docs", null);

        cmd.execute();

        assertEquals("Original Name", relationship.getName());
        assertEquals("New docs", relationship.getDocumentation());
    }

    @Test
    public void shouldRestoreDocumentation_whenUndone() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, "New docs", null);
        cmd.execute();

        cmd.undo();

        assertEquals("Original docs", relationship.getDocumentation());
    }

    @Test
    public void shouldReapplyDocumentation_whenRedone() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, "New docs", null);
        cmd.execute();
        cmd.undo();

        cmd.redo();

        assertEquals("New docs", relationship.getDocumentation());
    }

    // ---- Empty string clears (AC-1, AC-2) ----

    @Test
    public void shouldClearName_whenEmptyStringProvided() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, "", null, null);

        cmd.execute();

        assertEquals("", relationship.getName());
    }

    @Test
    public void shouldClearDocumentation_whenEmptyStringProvided() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, "", null);

        cmd.execute();

        assertEquals("", relationship.getDocumentation());
    }

    // ---- Property add ----

    @Test
    public void shouldAddProperty_whenExecuted() {
        Map<String, String> props = Map.of("status", "active");
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, null, props);

        cmd.execute();

        assertEquals(1, relationship.getProperties().size());
        assertEquals("status", relationship.getProperties().get(0).getKey());
        assertEquals("active", relationship.getProperties().get(0).getValue());
    }

    @Test
    public void shouldRemoveAddedProperty_whenUndone() {
        Map<String, String> props = Map.of("status", "active");
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, null, props);
        cmd.execute();

        cmd.undo();

        assertTrue("Properties should be empty after undo", relationship.getProperties().isEmpty());
    }

    // ---- Property update ----

    @Test
    public void shouldUpdateExistingProperty_whenExecuted() {
        addProperty("status", "draft");

        Map<String, String> props = Map.of("status", "active");
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, null, props);

        cmd.execute();

        assertEquals(1, relationship.getProperties().size());
        assertEquals("active", relationship.getProperties().get(0).getValue());
    }

    @Test
    public void shouldRestoreOldPropertyValue_whenUndone() {
        addProperty("status", "draft");

        Map<String, String> props = Map.of("status", "active");
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, null, props);
        cmd.execute();

        cmd.undo();

        assertEquals(1, relationship.getProperties().size());
        assertEquals("draft", relationship.getProperties().get(0).getValue());
    }

    // ---- Property removal ----

    @Test
    public void shouldRemoveProperty_whenValueIsNull() {
        addProperty("status", "active");
        addProperty("owner", "team-a");

        Map<String, String> props = new LinkedHashMap<>();
        props.put("status", null); // Remove this property
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, null, props);

        cmd.execute();

        assertEquals(1, relationship.getProperties().size());
        assertEquals("owner", relationship.getProperties().get(0).getKey());
    }

    @Test
    public void shouldRestoreRemovedProperty_whenUndone() {
        addProperty("status", "active");
        addProperty("owner", "team-a");

        Map<String, String> props = new LinkedHashMap<>();
        props.put("status", null);
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, null, props);
        cmd.execute();

        cmd.undo();

        assertEquals(2, relationship.getProperties().size());
        assertEquals("status", relationship.getProperties().get(0).getKey());
        assertEquals("active", relationship.getProperties().get(0).getValue());
        assertEquals("owner", relationship.getProperties().get(1).getKey());
    }

    // ---- Combined update ----

    @Test
    public void shouldUpdateAllFields_whenCombined() {
        addProperty("status", "draft");

        Map<String, String> props = new LinkedHashMap<>();
        props.put("status", "active");
        props.put("priority", "high");
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(
                relationship, "Updated Name", "Updated docs", props);

        cmd.execute();

        assertEquals("Updated Name", relationship.getName());
        assertEquals("Updated docs", relationship.getDocumentation());
        assertEquals(2, relationship.getProperties().size());
    }

    @Test
    public void shouldRestoreAllFields_whenCombinedUndone() {
        addProperty("status", "draft");

        Map<String, String> props = new LinkedHashMap<>();
        props.put("status", "active");
        props.put("priority", "high");
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(
                relationship, "Updated Name", "Updated docs", props);
        cmd.execute();

        cmd.undo();

        assertEquals("Original Name", relationship.getName());
        assertEquals("Original docs", relationship.getDocumentation());
        assertEquals(1, relationship.getProperties().size());
        assertEquals("draft", relationship.getProperties().get(0).getValue());
    }

    @Test
    public void shouldReapplyAllFields_whenCombinedRedone() {
        addProperty("status", "draft");

        Map<String, String> props = new LinkedHashMap<>();
        props.put("status", "active");
        props.put("priority", "high");
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(
                relationship, "Updated Name", "Updated docs", props);
        cmd.execute();
        cmd.undo();

        cmd.redo();

        assertEquals("Updated Name", relationship.getName());
        assertEquals("Updated docs", relationship.getDocumentation());
        assertEquals(2, relationship.getProperties().size());
    }

    // ---- Label ----

    @Test
    public void shouldHaveDescriptiveLabel() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, "New Name", null, null);

        String label = cmd.getLabel();

        assertTrue("Label should contain relationship type", label.contains("Association"));
        assertTrue("Label should contain relationship name", label.contains("Original Name"));
    }

    // ---- Edge cases ----

    @Test
    public void shouldNotChangeName_whenNewNameIsNull() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, "New docs", null);

        cmd.execute();

        assertEquals("Original Name", relationship.getName());
    }

    @Test
    public void shouldNotChangeDocumentation_whenNewDocIsNull() {
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, "New Name", null, null);

        cmd.execute();

        assertEquals("Original docs", relationship.getDocumentation());
    }

    @Test
    public void shouldNotChangeProperties_whenNewPropertiesIsNull() {
        addProperty("status", "active");
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, "New Name", null, null);

        cmd.execute();

        assertEquals(1, relationship.getProperties().size());
        assertEquals("active", relationship.getProperties().get(0).getValue());
    }

    @Test
    public void shouldHandleRemovalOfNonexistentProperty_gracefully() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("nonexistent", null);
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, null, props);

        cmd.execute();

        assertTrue("Properties should still be empty", relationship.getProperties().isEmpty());
    }

    @Test
    public void shouldPreservePropertyOrder_afterUndo() {
        addProperty("alpha", "1");
        addProperty("beta", "2");
        addProperty("gamma", "3");

        Map<String, String> props = Map.of("beta", "updated");
        UpdateRelationshipCommand cmd = new UpdateRelationshipCommand(relationship, null, null, props);
        cmd.execute();
        cmd.undo();

        assertEquals("alpha", relationship.getProperties().get(0).getKey());
        assertEquals("beta", relationship.getProperties().get(1).getKey());
        assertEquals("gamma", relationship.getProperties().get(2).getKey());
        assertEquals("2", relationship.getProperties().get(1).getValue());
    }

    // ---- Helper ----

    private void addProperty(String key, String value) {
        IProperty prop = factory.createProperty();
        prop.setKey(key);
        prop.setValue(value);
        relationship.getProperties().add(prop);
    }
}
