package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IProperty;

/**
 * GEF Command that updates the loaded ArchiMate model's own metadata.
 *
 * <p>Supports updating name, purpose, and custom properties. Only non-null
 * fields are modified; null fields are left unchanged. For properties, a
 * merge semantic applies: non-null values add/update, null values remove.</p>
 *
 * <p>Undo restores only the fields the update wrote, to the values they held when it ran:
 * {@link #execute()} snapshots them from the model as it meets it, not the constructor, so a
 * batched update does not revert a rename made while the batch was open. The snapshot is re-armed
 * by every {@code undo()}; a second execute with no undo between keeps it. Properties are
 * deep-copied because EMF property objects are live references, and restored as the whole list held
 * at execute.</p>
 *
 * <p>The {@code documentation} field
 * (mentioned in some jArchi documentation) is OUT-OF-SCOPE here because
 * {@code IArchimateModel} does NOT extend {@code IDocumentable} in Archi 5.7/5.8
 * — there is no model-level documentation API. The {@code purpose} field is the
 * model-level free-text Archi exposes (shown in File → Properties → "Purpose").
 * jArchi's {@code model.documentation} is most plausibly an alias to {@code model.purpose}
 * at the jArchi layer.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class UpdateModelCommand extends Command {

    // Archi's value for "no purpose": the model's default for the attribute, and what its
    // Properties editor writes when the field is emptied. Archi's own UI never writes null here.
    private static final String NO_PURPOSE = "";

    private final IArchimateModel model;

    // What undo restores: read by execute() for the fields it writes, re-armed by undo().
    private boolean snapshotTaken;
    private String oldName;
    private String oldPurpose;
    private List<PropertySnapshot> oldProperties;

    // New state (null = don't change)
    private final String newName;
    private final String newPurpose;
    private final boolean clearPurpose;
    private final Map<String, String> newProperties; // null value = remove key

    /**
     * Creates a command to update a model's metadata fields.
     *
     * @param model           the model to update
     * @param newName         new name, or null to leave unchanged
     * @param newPurpose      new purpose, or null to leave unchanged
     * @param clearPurpose    true to clear the purpose (set to the empty string, the value
     *                        Archi uses for no purpose)
     * @param newProperties   property merge map (null value = remove key), or null to leave unchanged
     */
    public UpdateModelCommand(IArchimateModel model, String newName,
            String newPurpose, boolean clearPurpose,
            Map<String, String> newProperties) {
        this.model = model;
        this.newName = newName;
        this.newPurpose = newPurpose;
        this.clearPurpose = clearPurpose;
        this.newProperties = newProperties;

        setLabel("Update model: " + model.getName());
    }

    /**
     * Snapshots the fields this update writes, from the model as it is now (unless a snapshot is
     * already held because execute ran with no undo since), then writes them. {@code redo()} is
     * GEF's default, which runs this again after the undo re-armed it.
     */
    @Override
    public void execute() {
        if (!snapshotTaken) {
            if (newName != null) {
                oldName = model.getName();
            }
            if (writesPurpose()) {
                oldPurpose = model.getPurpose();
            }
            if (newProperties != null) {
                oldProperties = snapshotProperties(model);
            }
            snapshotTaken = true;
        }
        applyNewValues();
    }

    /** Restores only the fields execute wrote, and re-arms the snapshot. */
    @Override
    public void undo() {
        if (!snapshotTaken) {
            return; // nothing was written, so there is nothing to restore
        }
        if (newName != null) {
            model.setName(oldName);
        }
        if (writesPurpose()) {
            // Undo restores the snapshot value verbatim, including a null left by an older
            // write. Note that the clear path writes "" and never null: "" is the value Archi
            // uses for "no purpose" (its model default, and what its Properties editor writes),
            // and the generated setter stores null verbatim, leaving a state the GUI cannot
            // produce.
            model.setPurpose(oldPurpose);
        }
        if (newProperties != null) {
            restoreProperties();
        }
        snapshotTaken = false;
    }

    private boolean writesPurpose() {
        return clearPurpose || newPurpose != null;
    }

    private void applyNewValues() {
        if (newName != null) {
            model.setName(newName);
        }
        if (clearPurpose) {
            model.setPurpose(NO_PURPOSE);
        } else if (newPurpose != null) {
            model.setPurpose(newPurpose);
        }
        if (newProperties != null) {
            mergeProperties();
        }
    }

    private void mergeProperties() {
        for (Map.Entry<String, String> entry : newProperties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                model.getProperties().removeIf(p -> key.equals(p.getKey()));
            } else {
                Optional<IProperty> existing = model.getProperties().stream()
                        .filter(p -> key.equals(p.getKey()))
                        .findFirst();
                if (existing.isPresent()) {
                    existing.get().setValue(value);
                } else {
                    IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
                    prop.setKey(key);
                    prop.setValue(value);
                    model.getProperties().add(prop);
                }
            }
        }
    }

    private void restoreProperties() {
        model.getProperties().clear();
        for (PropertySnapshot snapshot : oldProperties) {
            IProperty restored = IArchimateFactory.eINSTANCE.createProperty();
            restored.setKey(snapshot.key());
            restored.setValue(snapshot.value());
            model.getProperties().add(restored);
        }
    }

    private static List<PropertySnapshot> snapshotProperties(IArchimateModel model) {
        List<PropertySnapshot> snapshots = new ArrayList<>();
        for (IProperty prop : model.getProperties()) {
            snapshots.add(new PropertySnapshot(prop.getKey(), prop.getValue()));
        }
        return snapshots;
    }

    /**
     * Returns the model this command updates.
     * Package-visible for testing.
     */
    IArchimateModel getModel() {
        return model;
    }
}
