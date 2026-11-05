package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IProperty;

/**
 * GEF Command that updates an existing ArchiMate element.
 *
 * <p>Supports updating name, documentation, and properties. Only non-null
 * fields are modified; null fields are left unchanged. For properties,
 * a merge semantic applies: non-null values add/update, null values remove.</p>
 *
 * <p>Undo restores only the fields the update wrote, to the values they held when it ran:
 * {@link #execute()} snapshots them from the element as it meets it, not the constructor. A
 * proposal runs when it is approved and a batched update when the batch commits, so a snapshot
 * taken when the command was built made Undo revert whatever changed in between — including fields
 * this update never touched, such as a human's rename under a documentation-only update. The
 * snapshot is re-armed by every {@code undo()}; a second execute with no undo between keeps it.
 * Properties are deep-copied because EMF property objects are live references, and restored as the
 * whole list held at execute: under the stack's last-in-first-out order the list at undo time is
 * what this command left, so that reverts only this command's own merges.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class UpdateElementCommand extends Command {

    private final IArchimateElement element;

    // What undo restores: read by execute() for the fields it writes, re-armed by undo().
    private boolean snapshotTaken;
    private String oldName;
    private String oldDocumentation;
    private List<PropertySnapshot> oldProperties;

    // New state (null = don't change)
    private final String newName;
    private final String newDocumentation;
    private final Map<String, String> newProperties; // null value = remove key

    /**
     * Creates a command to update an element's fields.
     *
     * @param element        the element to update
     * @param newName        new name, or null to leave unchanged
     * @param newDocumentation new documentation, or null to leave unchanged
     * @param newProperties  property merge map (null value = remove key), or null to leave unchanged
     */
    public UpdateElementCommand(IArchimateElement element, String newName,
            String newDocumentation, Map<String, String> newProperties) {
        this.element = element;
        this.newName = newName;
        this.newDocumentation = newDocumentation;
        this.newProperties = newProperties;

        setLabel("Update " + element.eClass().getName() + ": " + element.getName());
    }

    /**
     * Snapshots the fields this update writes, from the element as it is now (unless a snapshot is
     * already held because execute ran with no undo since), then writes them. {@code redo()} is
     * GEF's default, which runs this again after the undo re-armed it.
     */
    @Override
    public void execute() {
        if (!snapshotTaken) {
            if (newName != null) {
                oldName = element.getName();
            }
            if (newDocumentation != null) {
                oldDocumentation = element.getDocumentation();
            }
            if (newProperties != null) {
                oldProperties = snapshotProperties(element);
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
            element.setName(oldName);
        }
        if (newDocumentation != null) {
            element.setDocumentation(oldDocumentation);
        }
        if (newProperties != null) {
            restoreProperties();
        }
        snapshotTaken = false;
    }

    private void applyNewValues() {
        if (newName != null) {
            element.setName(newName);
        }
        if (newDocumentation != null) {
            element.setDocumentation(newDocumentation);
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
                element.getProperties().removeIf(p -> key.equals(p.getKey()));
            } else {
                Optional<IProperty> existing = element.getProperties().stream()
                        .filter(p -> key.equals(p.getKey()))
                        .findFirst();
                if (existing.isPresent()) {
                    existing.get().setValue(value);
                } else {
                    IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
                    prop.setKey(key);
                    prop.setValue(value);
                    element.getProperties().add(prop);
                }
            }
        }
    }

    private void restoreProperties() {
        element.getProperties().clear();
        for (PropertySnapshot snapshot : oldProperties) {
            IProperty restored = IArchimateFactory.eINSTANCE.createProperty();
            restored.setKey(snapshot.key());
            restored.setValue(snapshot.value());
            element.getProperties().add(restored);
        }
    }

    private static List<PropertySnapshot> snapshotProperties(IArchimateElement element) {
        List<PropertySnapshot> snapshots = new ArrayList<>();
        for (IProperty prop : element.getProperties()) {
            snapshots.add(new PropertySnapshot(prop.getKey(), prop.getValue()));
        }
        return snapshots;
    }

    /**
     * Returns the element this command updates.
     * Package-visible for testing.
     */
    IArchimateElement getElement() {
        return element;
    }

    /** The name this command gives the element, or null when it leaves the name alone. */
    String getNewName() {
        return newName;
    }
}
