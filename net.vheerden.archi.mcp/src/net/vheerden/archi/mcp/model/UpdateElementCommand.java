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
 * GEF Command that updates an existing ArchiMate element (Story 7-3).
 *
 * <p>Supports updating name, documentation, and properties. Only non-null
 * fields are modified; null fields are left unchanged. For properties,
 * a merge semantic applies: non-null values add/update, null values remove.</p>
 *
 * <p>Captures old state at construction time for full undo support.
 * Properties are deep-copied because EMF property objects are live references.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class UpdateElementCommand extends Command {

    private final IArchimateElement element;

    // Old state (captured at construction time for undo)
    private final String oldName;
    private final String oldDocumentation;
    private final List<PropertySnapshot> oldProperties;

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

        // Snapshot old state before any mutation
        this.oldName = element.getName();
        this.oldDocumentation = element.getDocumentation();
        this.oldProperties = snapshotProperties(element);

        setLabel("Update " + element.eClass().getName() + ": " + element.getName());
    }

    @Override
    public void execute() {
        applyNewValues();
    }

    @Override
    public void redo() {
        applyNewValues();
    }

    @Override
    public void undo() {
        element.setName(oldName);
        element.setDocumentation(oldDocumentation);
        restoreProperties();
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
}
