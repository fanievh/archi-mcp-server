package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IProperty;

/**
 * GEF Command that updates an existing ArchiMate relationship (Story C10).
 *
 * <p>Supports updating name, documentation, and properties. Only non-null
 * fields are modified; null fields are left unchanged. For properties,
 * a merge semantic applies: non-null values add/update, null values remove.</p>
 *
 * <p>Source, target, and type are immutable — changing these fundamentally
 * alters the relationship's semantics and should be done via delete + create.</p>
 *
 * <p>Captures old state at construction time for full undo support.
 * Properties are deep-copied because EMF property objects are live references.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class UpdateRelationshipCommand extends Command {

    private final IArchimateRelationship relationship;

    // Old state (captured at construction time for undo)
    private final String oldName;
    private final String oldDocumentation;
    private final List<PropertySnapshot> oldProperties;

    // New state (null = don't change)
    private final String newName;
    private final String newDocumentation;
    private final Map<String, String> newProperties; // null value = remove key

    /**
     * Creates a command to update a relationship's fields.
     *
     * @param relationship   the relationship to update
     * @param newName        new name, or null to leave unchanged
     * @param newDocumentation new documentation, or null to leave unchanged
     * @param newProperties  property merge map (null value = remove key), or null to leave unchanged
     */
    public UpdateRelationshipCommand(IArchimateRelationship relationship, String newName,
            String newDocumentation, Map<String, String> newProperties) {
        this.relationship = relationship;
        this.newName = newName;
        this.newDocumentation = newDocumentation;
        this.newProperties = newProperties;

        // Snapshot old state before any mutation
        this.oldName = relationship.getName();
        this.oldDocumentation = relationship.getDocumentation();
        this.oldProperties = snapshotProperties(relationship);

        setLabel("Update " + relationship.eClass().getName() + ": " + relationship.getName());
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
        relationship.setName(oldName);
        relationship.setDocumentation(oldDocumentation);
        restoreProperties();
    }

    private void applyNewValues() {
        if (newName != null) {
            relationship.setName(newName);
        }
        if (newDocumentation != null) {
            relationship.setDocumentation(newDocumentation);
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
                relationship.getProperties().removeIf(p -> key.equals(p.getKey()));
            } else {
                Optional<IProperty> existing = relationship.getProperties().stream()
                        .filter(p -> key.equals(p.getKey()))
                        .findFirst();
                if (existing.isPresent()) {
                    existing.get().setValue(value);
                } else {
                    IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
                    prop.setKey(key);
                    prop.setValue(value);
                    relationship.getProperties().add(prop);
                }
            }
        }
    }

    private void restoreProperties() {
        relationship.getProperties().clear();
        for (PropertySnapshot snapshot : oldProperties) {
            IProperty restored = IArchimateFactory.eINSTANCE.createProperty();
            restored.setKey(snapshot.key());
            restored.setValue(snapshot.value());
            relationship.getProperties().add(restored);
        }
    }

    private static List<PropertySnapshot> snapshotProperties(IArchimateRelationship relationship) {
        List<PropertySnapshot> snapshots = new ArrayList<>();
        for (IProperty prop : relationship.getProperties()) {
            snapshots.add(new PropertySnapshot(prop.getKey(), prop.getValue()));
        }
        return snapshots;
    }

    /**
     * Returns the relationship this command updates.
     * Package-visible for testing.
     */
    IArchimateRelationship getRelationship() {
        return relationship;
    }
}
