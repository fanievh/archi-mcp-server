package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProperty;

/**
 * Tests for {@link UpdateElementCommand} (Story 7-3).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute/undo/redo with name, documentation, and property changes.</p>
 */
public class UpdateElementCommandTest {

    private IArchimateFactory factory;
    private IBusinessActor element;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();
        IFolder folder = model.getFolder(FolderType.BUSINESS);

        element = factory.createBusinessActor();
        element.setName("Original Name");
        element.setDocumentation("Original docs");
        folder.getElements().add(element);
    }

    // ---- Name-only update ----

    @Test
    public void shouldUpdateName_whenExecuted() {
        UpdateElementCommand cmd = new UpdateElementCommand(element, "New Name", null, null);

        cmd.execute();

        assertEquals("New Name", element.getName());
        assertEquals("Original docs", element.getDocumentation());
    }

    @Test
    public void shouldRestoreName_whenUndone() {
        UpdateElementCommand cmd = new UpdateElementCommand(element, "New Name", null, null);
        cmd.execute();

        cmd.undo();

        assertEquals("Original Name", element.getName());
    }

    @Test
    public void shouldReapplyName_whenRedone() {
        UpdateElementCommand cmd = new UpdateElementCommand(element, "New Name", null, null);
        cmd.execute();
        cmd.undo();

        cmd.redo();

        assertEquals("New Name", element.getName());
    }

    // ---- Documentation-only update ----

    @Test
    public void shouldUpdateDocumentation_whenExecuted() {
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, "New docs", null);

        cmd.execute();

        assertEquals("Original Name", element.getName());
        assertEquals("New docs", element.getDocumentation());
    }

    @Test
    public void shouldRestoreDocumentation_whenUndone() {
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, "New docs", null);
        cmd.execute();

        cmd.undo();

        assertEquals("Original docs", element.getDocumentation());
    }

    // ---- Property add ----

    @Test
    public void shouldAddProperty_whenExecuted() {
        Map<String, String> props = Map.of("status", "active");
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, null, props);

        cmd.execute();

        assertEquals(1, element.getProperties().size());
        assertEquals("status", element.getProperties().get(0).getKey());
        assertEquals("active", element.getProperties().get(0).getValue());
    }

    @Test
    public void shouldRemoveAddedProperty_whenUndone() {
        Map<String, String> props = Map.of("status", "active");
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, null, props);
        cmd.execute();

        cmd.undo();

        assertTrue("Properties should be empty after undo", element.getProperties().isEmpty());
    }

    // ---- Property update ----

    @Test
    public void shouldUpdateExistingProperty_whenExecuted() {
        addProperty("status", "draft");

        Map<String, String> props = Map.of("status", "active");
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, null, props);

        cmd.execute();

        assertEquals(1, element.getProperties().size());
        assertEquals("active", element.getProperties().get(0).getValue());
    }

    @Test
    public void shouldRestoreOldPropertyValue_whenUndone() {
        addProperty("status", "draft");

        Map<String, String> props = Map.of("status", "active");
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, null, props);
        cmd.execute();

        cmd.undo();

        assertEquals(1, element.getProperties().size());
        assertEquals("draft", element.getProperties().get(0).getValue());
    }

    // ---- Property removal ----

    @Test
    public void shouldRemoveProperty_whenValueIsNull() {
        addProperty("status", "active");
        addProperty("owner", "team-a");

        Map<String, String> props = new LinkedHashMap<>();
        props.put("status", null); // Remove this property
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, null, props);

        cmd.execute();

        assertEquals(1, element.getProperties().size());
        assertEquals("owner", element.getProperties().get(0).getKey());
    }

    @Test
    public void shouldRestoreRemovedProperty_whenUndone() {
        addProperty("status", "active");
        addProperty("owner", "team-a");

        Map<String, String> props = new LinkedHashMap<>();
        props.put("status", null);
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, null, props);
        cmd.execute();

        cmd.undo();

        assertEquals(2, element.getProperties().size());
        assertEquals("status", element.getProperties().get(0).getKey());
        assertEquals("active", element.getProperties().get(0).getValue());
        assertEquals("owner", element.getProperties().get(1).getKey());
    }

    // ---- Combined update ----

    @Test
    public void shouldUpdateAllFields_whenCombined() {
        addProperty("status", "draft");

        Map<String, String> props = new LinkedHashMap<>();
        props.put("status", "active");
        props.put("priority", "high");
        UpdateElementCommand cmd = new UpdateElementCommand(
                element, "Updated Name", "Updated docs", props);

        cmd.execute();

        assertEquals("Updated Name", element.getName());
        assertEquals("Updated docs", element.getDocumentation());
        assertEquals(2, element.getProperties().size());
    }

    @Test
    public void shouldRestoreAllFields_whenCombinedUndone() {
        addProperty("status", "draft");

        Map<String, String> props = new LinkedHashMap<>();
        props.put("status", "active");
        props.put("priority", "high");
        UpdateElementCommand cmd = new UpdateElementCommand(
                element, "Updated Name", "Updated docs", props);
        cmd.execute();

        cmd.undo();

        assertEquals("Original Name", element.getName());
        assertEquals("Original docs", element.getDocumentation());
        assertEquals(1, element.getProperties().size());
        assertEquals("draft", element.getProperties().get(0).getValue());
    }

    @Test
    public void shouldReapplyAllFields_whenCombinedRedone() {
        addProperty("status", "draft");

        Map<String, String> props = new LinkedHashMap<>();
        props.put("status", "active");
        props.put("priority", "high");
        UpdateElementCommand cmd = new UpdateElementCommand(
                element, "Updated Name", "Updated docs", props);
        cmd.execute();
        cmd.undo();

        cmd.redo();

        assertEquals("Updated Name", element.getName());
        assertEquals("Updated docs", element.getDocumentation());
        assertEquals(2, element.getProperties().size());
    }

    // ---- Label ----

    @Test
    public void shouldHaveDescriptiveLabel() {
        UpdateElementCommand cmd = new UpdateElementCommand(element, "New Name", null, null);

        String label = cmd.getLabel();

        assertTrue("Label should contain element type", label.contains("BusinessActor"));
        assertTrue("Label should contain element name", label.contains("Original Name"));
    }

    // ---- Edge cases ----

    @Test
    public void shouldNotChangeName_whenNewNameIsNull() {
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, "New docs", null);

        cmd.execute();

        assertEquals("Original Name", element.getName());
    }

    @Test
    public void shouldNotChangeDocumentation_whenNewDocIsNull() {
        UpdateElementCommand cmd = new UpdateElementCommand(element, "New Name", null, null);

        cmd.execute();

        assertEquals("Original docs", element.getDocumentation());
    }

    @Test
    public void shouldNotChangeProperties_whenNewPropertiesIsNull() {
        addProperty("status", "active");
        UpdateElementCommand cmd = new UpdateElementCommand(element, "New Name", null, null);

        cmd.execute();

        assertEquals(1, element.getProperties().size());
        assertEquals("active", element.getProperties().get(0).getValue());
    }

    @Test
    public void shouldHandleRemovalOfNonexistentProperty_gracefully() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("nonexistent", null);
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, null, props);

        cmd.execute();

        assertTrue("Properties should still be empty", element.getProperties().isEmpty());
    }

    @Test
    public void shouldPreservePropertyOrder_afterUndo() {
        addProperty("alpha", "1");
        addProperty("beta", "2");
        addProperty("gamma", "3");

        Map<String, String> props = Map.of("beta", "updated");
        UpdateElementCommand cmd = new UpdateElementCommand(element, null, null, props);
        cmd.execute();
        cmd.undo();

        assertEquals("alpha", element.getProperties().get(0).getKey());
        assertEquals("beta", element.getProperties().get(1).getKey());
        assertEquals("gamma", element.getProperties().get(2).getKey());
        assertEquals("2", element.getProperties().get(1).getValue());
    }

    // ---- Helper ----

    private void addProperty(String key, String value) {
        IProperty prop = factory.createProperty();
        prop.setKey(key);
        prop.setValue(value);
        element.getProperties().add(prop);
    }
}
