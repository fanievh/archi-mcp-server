package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IInfluenceRelationship;
import com.archimatetool.model.IProperty;

import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;

/**
 * GEF Command that updates an existing ArchiMate relationship.
 *
 * <p>Supports updating name, documentation, and properties. Only non-null
 * fields are modified; null fields are left unchanged. For properties,
 * a merge semantic applies: non-null values add/update, null values remove.</p>
 *
 * <p>Also supports ArchiMate semantic attributes —
 * {@code accessType} (AccessRelationship), {@code associationDirected}
 * (AssociationRelationship), {@code influenceStrength} (InfluenceRelationship).
 * Same null-means-leave-unchanged semantics; empty-string {@code influenceStrength}
 * clears the underlying EMF value. Type-conditional application is enforced upstream
 * at the prepare boundary; the command itself applies whichever fields match.
 * Idempotence-guarded: same-value sets are skipped to avoid spurious EMF notifications.</p>
 *
 * <p>Source, target, and type are immutable — changing these fundamentally
 * alters the relationship's semantics and should be done via delete + create.</p>
 *
 * <p>Undo restores only the fields the update wrote, to the values they held when it ran:
 * {@link #execute()} snapshots them from the relationship as it meets it, not the constructor. A
 * proposal runs when it is approved and a batched update when the batch commits, so a snapshot
 * taken when the command was built made Undo revert whatever changed in between — including fields
 * this update never touched. The snapshot is re-armed by every {@code undo()}; a second execute
 * with no undo between keeps it. Properties are deep-copied because EMF property objects are live
 * references, and restored as the whole list held at execute.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class UpdateRelationshipCommand extends Command {

    private final IArchimateRelationship relationship;

    // The name when the command was built. Not undo state: the batch readers ask hasNameChange()
    // while preparing, before this command runs, to project which labels a queued rename redraws.
    private final String nameAtBuild;

    // What undo restores: read by execute() for the fields it writes, re-armed by undo().
    private boolean snapshotTaken;
    private String oldName;
    private String oldDocumentation;
    private List<PropertySnapshot> oldProperties;
    // The three semantic attributes, each read only when named and held by this relationship.
    private Integer oldAccessType;
    private Boolean oldAssociationDirected;
    private String oldInfluenceStrength;

    // New state (null = don't change)
    private final String newName;
    private final String newDocumentation;
    private final Map<String, String> newProperties; // null value = remove key
    // New-state — each null = leave unchanged.
    private final RelationshipSemanticAttributes newSemanticAttributes;

    /**
     * Back-compat 4-arg constructor preserving the prior signature. Delegates to the
     * 5-arg constructor with {@link RelationshipSemanticAttributes#NONE}.
     */
    public UpdateRelationshipCommand(IArchimateRelationship relationship, String newName,
            String newDocumentation, Map<String, String> newProperties) {
        this(relationship, newName, newDocumentation, newProperties,
                RelationshipSemanticAttributes.NONE);
    }

    /**
     * Creates a command to update a relationship's fields (canonical).
     *
     * @param relationship           the relationship to update
     * @param newName                new name, or null to leave unchanged
     * @param newDocumentation       new documentation, or null to leave unchanged
     * @param newProperties          property merge map (null value = remove key), or null to leave unchanged
     * @param newSemanticAttributes  semantic-attribute bundle; pass NONE for none
     */
    public UpdateRelationshipCommand(IArchimateRelationship relationship, String newName,
            String newDocumentation, Map<String, String> newProperties,
            RelationshipSemanticAttributes newSemanticAttributes) {
        this.relationship = relationship;
        this.newName = newName;
        this.newDocumentation = newDocumentation;
        this.newProperties = newProperties;
        this.newSemanticAttributes = (newSemanticAttributes != null)
                ? newSemanticAttributes : RelationshipSemanticAttributes.NONE;

        this.nameAtBuild = relationship.getName();

        setLabel("Update " + relationship.eClass().getName() + ": " + relationship.getName());
    }

    /**
     * Snapshots the fields this update writes, from the relationship as it is now (unless a
     * snapshot is already held because execute ran with no undo since), then writes them.
     * {@code redo()} is GEF's default, which runs this again after the undo re-armed it.
     */
    @Override
    public void execute() {
        if (!snapshotTaken) {
            if (newName != null) {
                oldName = relationship.getName();
            }
            if (newDocumentation != null) {
                oldDocumentation = relationship.getDocumentation();
            }
            if (newProperties != null) {
                oldProperties = snapshotProperties(relationship);
            }
            if (writesAccessType()) {
                oldAccessType = ((IAccessRelationship) relationship).getAccessType();
            }
            if (writesAssociationDirected()) {
                oldAssociationDirected = ((IAssociationRelationship) relationship).isDirected();
            }
            if (writesInfluenceStrength()) {
                oldInfluenceStrength = ((IInfluenceRelationship) relationship).getStrength();
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
            relationship.setName(oldName);
        }
        if (newDocumentation != null) {
            relationship.setDocumentation(oldDocumentation);
        }
        if (newProperties != null) {
            restoreProperties();
        }
        restoreSemanticAttributes();
        snapshotTaken = false;
    }

    private boolean writesAccessType() {
        return newSemanticAttributes.accessType() != null && relationship instanceof IAccessRelationship;
    }

    private boolean writesAssociationDirected() {
        return newSemanticAttributes.associationDirected() != null
                && relationship instanceof IAssociationRelationship;
    }

    private boolean writesInfluenceStrength() {
        return newSemanticAttributes.influenceStrength() != null && relationship instanceof IInfluenceRelationship;
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
        applySemanticAttributes();
    }

    /**
     * Apply semantic attributes with idempotence guards.
     * EMF setters fire notifications even on same-value writes; we guard
     * to avoid spurious model-dirty flips on no-op update-relationship calls.
     */
    private void applySemanticAttributes() {
        if (!newSemanticAttributes.hasAny()) {
            return;
        }
        if (newSemanticAttributes.accessType() != null
                && relationship instanceof IAccessRelationship ar) {
            int target = resolveAccessTypeInt(newSemanticAttributes.accessType());
            if (target != ar.getAccessType()) {
                ar.setAccessType(target);
            }
        }
        if (newSemanticAttributes.associationDirected() != null
                && relationship instanceof IAssociationRelationship asr) {
            boolean target = newSemanticAttributes.associationDirected();
            if (target != asr.isDirected()) {
                asr.setDirected(target);
            }
        }
        if (newSemanticAttributes.influenceStrength() != null
                && relationship instanceof IInfluenceRelationship ir) {
            String target = newSemanticAttributes.influenceStrength();
            String current = ir.getStrength();
            // Treat null and "" as equivalent for idempotence (mirrors Archi GUI's
            // free-text field where unset == empty).
            String currentNorm = (current == null) ? "" : current;
            if (!target.equals(currentNorm)) {
                ir.setStrength(target);
            }
        }
    }

    /**
     * Restore, on undo, the semantic attributes execute wrote — and no other, so an attribute
     * changed by someone else since is left as they set it.
     *
     * <p>The {@code influenceStrength} restore uses null-vs-empty normalisation because both
     * {@code null} and {@code ""} are semantically valid "cleared" states — a {@code null}
     * {@code oldInfluenceStrength} is meaningful (it was unset before, restore to unset). The
     * idempotence guards skip a restore that would write the value already held, as on apply.</p>
     */
    private void restoreSemanticAttributes() {
        if (writesAccessType()) {
            IAccessRelationship ar = (IAccessRelationship) relationship;
            if (oldAccessType != ar.getAccessType()) {
                ar.setAccessType(oldAccessType);
            }
        }
        if (writesAssociationDirected()) {
            IAssociationRelationship asr = (IAssociationRelationship) relationship;
            if (oldAssociationDirected != asr.isDirected()) {
                asr.setDirected(oldAssociationDirected);
            }
        }
        if (writesInfluenceStrength()) {
            IInfluenceRelationship ir = (IInfluenceRelationship) relationship;
            String oldNorm = (oldInfluenceStrength == null) ? "" : oldInfluenceStrength;
            String currentNorm = (ir.getStrength() == null) ? "" : ir.getStrength();
            if (!oldNorm.equals(currentNorm)) {
                ir.setStrength(oldInfluenceStrength);
            }
        }
    }

    /**
     * Resolves an MCP wire-vocabulary {@code accessType} string to the EMF named-constant int.
     * Mirrors {@code ArchiModelAccessorImpl.resolveAccessTypeInt}; duplicated here to keep the
     * command package self-contained (no upward dependency on the accessor impl).
     */
    private static int resolveAccessTypeInt(String wireValue) {
        return switch (wireValue) {
            case "access" -> IAccessRelationship.UNSPECIFIED_ACCESS;
            case "read" -> IAccessRelationship.READ_ACCESS;
            case "write" -> IAccessRelationship.WRITE_ACCESS;
            case "readwrite" -> IAccessRelationship.READ_WRITE_ACCESS;
            default -> throw new IllegalStateException(
                    "Invalid accessType '" + wireValue + "' — validated upstream at prepare boundary");
        };
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
     * Whether this command changes the relationship's name, which is the text of every label drawn for it.
     *
     * <p>A projection, read while preparing — before this command runs — so it compares against the
     * name when the command was built, not against the undo snapshot execute takes.</p>
     */
    boolean hasNameChange() {
        return newName != null && !newName.equals(nameAtBuild);
    }

    /**
     * Returns the relationship this command updates.
     * Package-visible for testing.
     */
    IArchimateRelationship getRelationship() {
        return relationship;
    }
}
