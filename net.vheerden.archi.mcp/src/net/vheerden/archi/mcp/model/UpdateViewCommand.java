package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IProperty;

/**
 * GEF Command that updates an existing ArchiMate view's metadata.
 *
 * <p>Supports updating name, viewpoint, documentation, and properties. Only
 * non-null fields are modified; null fields are left unchanged. For properties,
 * a merge semantic applies: non-null values add/update, null values remove.</p>
 *
 * <p>Captures old state at construction time for full undo support.
 * Properties are deep-copied because EMF property objects are live references.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class UpdateViewCommand extends Command {

    // Archi's value for "no viewpoint": the model's default for the attribute, and what the
    // GUI's "None" choice writes. Archi's own UI never writes null here.
    private static final String NO_VIEWPOINT = "";

    private final IArchimateDiagramModel view;

    // Old state (captured at construction time for undo)
    private final String oldName;
    private final String oldViewpoint;
    private final String oldDocumentation;
    private final List<PropertySnapshot> oldProperties;
    private final int oldConnectionRouterType;

    // New state (null = don't change)
    private final String newName;
    private final String newViewpoint;
    private final boolean clearViewpoint;
    private final String newDocumentation;
    private final Map<String, String> newProperties; // null value = remove key
    private final Integer newConnectionRouterType; // null = no change

    /**
     * Creates a command to update a view's metadata fields.
     *
     * @param view                     the view to update
     * @param newName                  new name, or null to leave unchanged
     * @param newViewpoint             new viewpoint, or null to leave unchanged
     * @param clearViewpoint           true to clear the viewpoint (set to the empty string,
     *                                 the value Archi uses for no viewpoint)
     * @param newDocumentation         new documentation, or null to leave unchanged
     * @param newProperties            property merge map (null value = remove key), or null to leave unchanged
     * @param newConnectionRouterType  new router type int, or null to leave unchanged
     */
    public UpdateViewCommand(IArchimateDiagramModel view, String newName,
            String newViewpoint, boolean clearViewpoint, String newDocumentation,
            Map<String, String> newProperties, Integer newConnectionRouterType) {
        this.view = view;
        this.newName = newName;
        this.newViewpoint = newViewpoint;
        this.clearViewpoint = clearViewpoint;
        this.newDocumentation = newDocumentation;
        this.newProperties = newProperties;
        this.newConnectionRouterType = newConnectionRouterType;

        // Snapshot old state before any mutation
        this.oldName = view.getName();
        this.oldViewpoint = view.getViewpoint();
        this.oldDocumentation = view.getDocumentation();
        this.oldProperties = snapshotProperties(view);
        this.oldConnectionRouterType = view.getConnectionRouterType();

        setLabel("Update view: " + view.getName());
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
        view.setName(oldName);
        // Undo restores the captured value verbatim, including a null left by an older
        // write. Note that the clear path writes "" and never null: "" is the value Archi
        // uses for "no viewpoint" (its model default, and what the GUI's "None" choice
        // writes), while null is not a state the GUI can produce and Archi's viewpoint
        // actions dereference the value unguarded, so the view's editor fails to open.
        view.setViewpoint(oldViewpoint);
        view.setDocumentation(oldDocumentation);
        restoreProperties();
        view.setConnectionRouterType(oldConnectionRouterType);
    }

    private void applyNewValues() {
        if (newName != null) {
            view.setName(newName);
        }
        if (clearViewpoint) {
            view.setViewpoint(NO_VIEWPOINT);
        } else if (newViewpoint != null) {
            view.setViewpoint(newViewpoint);
        }
        if (newDocumentation != null) {
            view.setDocumentation(newDocumentation);
        }
        if (newProperties != null) {
            mergeProperties();
        }
        if (newConnectionRouterType != null) {
            view.setConnectionRouterType(newConnectionRouterType);
        }
    }

    private void mergeProperties() {
        for (Map.Entry<String, String> entry : newProperties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                view.getProperties().removeIf(p -> key.equals(p.getKey()));
            } else {
                Optional<IProperty> existing = view.getProperties().stream()
                        .filter(p -> key.equals(p.getKey()))
                        .findFirst();
                if (existing.isPresent()) {
                    existing.get().setValue(value);
                } else {
                    IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
                    prop.setKey(key);
                    prop.setValue(value);
                    view.getProperties().add(prop);
                }
            }
        }
    }

    private void restoreProperties() {
        view.getProperties().clear();
        for (PropertySnapshot snapshot : oldProperties) {
            IProperty restored = IArchimateFactory.eINSTANCE.createProperty();
            restored.setKey(snapshot.key());
            restored.setValue(snapshot.value());
            view.getProperties().add(restored);
        }
    }

    private static List<PropertySnapshot> snapshotProperties(IArchimateDiagramModel view) {
        List<PropertySnapshot> snapshots = new ArrayList<>();
        for (IProperty prop : view.getProperties()) {
            snapshots.add(new PropertySnapshot(prop.getKey(), prop.getValue()));
        }
        return snapshots;
    }

    /**
     * Returns the view this command updates.
     * Package-visible for testing.
     */
    IArchimateDiagramModel getView() {
        return view;
    }

    /** Whether this command changes the connection router type of {@code target}. */
    boolean changesRouterTypeOf(IDiagramModel target) {
        return view == target && newConnectionRouterType != null
                && newConnectionRouterType != oldConnectionRouterType;
    }
}
