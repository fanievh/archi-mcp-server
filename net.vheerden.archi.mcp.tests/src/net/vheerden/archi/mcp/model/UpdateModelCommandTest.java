package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IProperty;

/**
 * Tests for {@link UpdateModelCommand} (G6).
 *
 * <p>Uses real EMF objects (IArchimateFactory.eINSTANCE) to verify the command
 * correctly sets, clears, undoes, and redoes name/purpose/properties changes
 * on the loaded model. Pure JUnit — no OSGi runtime required.</p>
 *
 * <p>Mirrors {@link UpdateViewCommandTest} test conventions and naming.
 * The {@code documentation} field is intentionally NOT exercised —
 * {@code IArchimateModel} is not {@code IDocumentable} (per Task 0 OUTCOME,
 * outcome C).</p>
 */
public class UpdateModelCommandTest {

    private IArchimateModel model;

    @Before
    public void setUp() {
        model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setName("Old Model Name");
        model.setPurpose("Old purpose");
    }

    @Test
    public void shouldSetName_whenExecuted() {
        UpdateModelCommand cmd = new UpdateModelCommand(model, "New Model Name", null, false, null);
        cmd.execute();
        assertEquals("New Model Name", model.getName());
    }

    @Test
    public void shouldSetPurpose_whenExecuted() {
        UpdateModelCommand cmd = new UpdateModelCommand(model, null, "New purpose", false, null);
        cmd.execute();
        assertEquals("New purpose", model.getPurpose());
    }

    @Test
    public void shouldClearPurpose_whenEmptyStringPassed() {
        // Caller passes "" via JSON, which the boundary converts to clearPurpose=true + null payload.
        UpdateModelCommand cmd = new UpdateModelCommand(model, null, null, true, null);
        cmd.execute();
        // Archi/EMF stores "no purpose" as "" once the feature has been set; setPurpose(null) on a
        // previously-set feature yields "" (not null). Both mean "no purpose" — see
        // UpdateModelCommand.undo()'s null<->"" note. Accept either.
        assertTrue("purpose should be cleared (null or empty)",
                model.getPurpose() == null || model.getPurpose().isEmpty());
    }

    @Test
    public void shouldMergeProperties_whenPropertyMapProvided() {
        // Pre-existing property
        IProperty existing = IArchimateFactory.eINSTANCE.createProperty();
        existing.setKey("Author");
        existing.setValue("Old Author");
        model.getProperties().add(existing);

        Map<String, String> props = new LinkedHashMap<>();
        props.put("Author", "Jane Doe"); // update existing
        UpdateModelCommand cmd = new UpdateModelCommand(model, null, null, false, props);
        cmd.execute();

        assertEquals(1, model.getProperties().size());
        assertEquals("Jane Doe", model.getProperties().get(0).getValue());
    }

    @Test
    public void shouldRemoveProperty_whenValueIsNull() {
        IProperty toRemove = IArchimateFactory.eINSTANCE.createProperty();
        toRemove.setKey("OldKey");
        toRemove.setValue("OldValue");
        model.getProperties().add(toRemove);

        Map<String, String> props = new LinkedHashMap<>();
        props.put("OldKey", null); // null = remove
        UpdateModelCommand cmd = new UpdateModelCommand(model, null, null, false, props);
        cmd.execute();

        assertTrue("OldKey should be removed", model.getProperties().isEmpty());
    }

    @Test
    public void shouldAddNewProperty_whenKeyMissing() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("Tag", "draft");
        UpdateModelCommand cmd = new UpdateModelCommand(model, null, null, false, props);
        cmd.execute();

        assertEquals(1, model.getProperties().size());
        assertEquals("Tag", model.getProperties().get(0).getKey());
        assertEquals("draft", model.getProperties().get(0).getValue());
    }

    @Test
    public void shouldLeaveNameUnchanged_whenNameNull() {
        UpdateModelCommand cmd = new UpdateModelCommand(model, null, "Some purpose", false, null);
        cmd.execute();
        assertEquals("Old Model Name", model.getName());
    }

    @Test
    public void shouldLeavePurposeUnchanged_whenPurposeNull() {
        UpdateModelCommand cmd = new UpdateModelCommand(model, "Renamed", null, false, null);
        cmd.execute();
        assertEquals("Old purpose", model.getPurpose());
    }

    @Test
    public void shouldLeavePropertiesUnchanged_whenPropertiesNull() {
        IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
        prop.setKey("Keep");
        prop.setValue("Me");
        model.getProperties().add(prop);

        UpdateModelCommand cmd = new UpdateModelCommand(model, "Renamed", null, false, null);
        cmd.execute();

        assertEquals(1, model.getProperties().size());
        assertEquals("Keep", model.getProperties().get(0).getKey());
        assertEquals("Me", model.getProperties().get(0).getValue());
    }

    @Test
    public void shouldRestoreOldState_whenUndone() {
        IProperty originalProp = IArchimateFactory.eINSTANCE.createProperty();
        originalProp.setKey("Original");
        originalProp.setValue("Value");
        model.getProperties().add(originalProp);

        Map<String, String> newProps = new LinkedHashMap<>();
        newProps.put("Original", null); // remove
        newProps.put("NewKey", "NewValue"); // add

        UpdateModelCommand cmd = new UpdateModelCommand(
                model, "Renamed", "New purpose", false, newProps);
        cmd.execute();

        assertEquals("Renamed", model.getName());
        assertEquals("New purpose", model.getPurpose());
        assertEquals(1, model.getProperties().size());
        assertEquals("NewKey", model.getProperties().get(0).getKey());

        cmd.undo();

        assertEquals("Old Model Name", model.getName());
        assertEquals("Old purpose", model.getPurpose());
        assertEquals(1, model.getProperties().size());
        assertEquals("Original", model.getProperties().get(0).getKey());
        assertEquals("Value", model.getProperties().get(0).getValue());
    }

    @Test
    public void shouldRestoreNullPurpose_whenUndoneAfterSet() {
        // Start with no purpose. A freshly created IArchimateModel reports "no purpose" as
        // null OR "" depending on the EMF runtime (Archi treats them equivalently — see
        // UpdateModelCommand.undo()'s null<->"" note), so accept either as the baseline.
        IArchimateModel m = IArchimateFactory.eINSTANCE.createArchimateModel();
        m.setName("X");
        assertTrue("fresh model should have no purpose (null or empty)",
                m.getPurpose() == null || m.getPurpose().isEmpty());

        UpdateModelCommand cmd = new UpdateModelCommand(m, null, "Set purpose", false, null);
        cmd.execute();
        assertEquals("Set purpose", m.getPurpose());

        cmd.undo();
        // UpdateModelCommand.undo() deliberately restores the captured oldPurpose verbatim and
        // does NOT normalize null<->"" (see its undo() comment — Archi stores "no purpose" as ""
        // and treats null/"" as equivalent; this is a project-wide convention mirrored in
        // UpdateViewCommand). EMF returns "" rather than null after setPurpose(null) on a feature
        // that has been set, so accept either as "no purpose".
        assertTrue("purpose should be cleared (null or empty) after undo",
                m.getPurpose() == null || m.getPurpose().isEmpty());
    }

    @Test
    public void shouldReexecute_whenRedoCalled() {
        UpdateModelCommand cmd = new UpdateModelCommand(
                model, "Renamed", "New purpose", false, null);
        cmd.execute();
        assertEquals("Renamed", model.getName());

        cmd.undo();
        assertEquals("Old Model Name", model.getName());

        cmd.redo();
        assertEquals("Renamed", model.getName());
        assertEquals("New purpose", model.getPurpose());
    }

    @Test
    public void shouldCaptureOldStateAtConstruction_notExecute() {
        // Mutate AFTER construction; undo must restore the CONSTRUCTION-TIME snapshot
        UpdateModelCommand cmd = new UpdateModelCommand(model, "Renamed", null, false, null);

        // Out-of-band mutation between construction and execute
        model.setName("Tampered");
        assertEquals("Tampered", model.getName());

        cmd.execute();
        assertEquals("Renamed", model.getName());

        cmd.undo();
        // Snapshot was taken at construction time (when name was "Old Model Name"),
        // so undo restores "Old Model Name" — NOT the post-tamper "Tampered".
        assertEquals("Old Model Name", model.getName());
        assertNotNull(model.getName());
    }
}
