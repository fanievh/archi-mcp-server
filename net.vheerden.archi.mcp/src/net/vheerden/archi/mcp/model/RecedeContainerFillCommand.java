package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;

/**
 * GEF Command that recedes a container's fill to a subtle neutral backdrop the
 * moment the container gains a nested child, so a nested view never collapses
 * into a flat single-colour "blob" where the parent and its children are
 * visually indistinguishable.
 *
 * <p><strong>Provenance gate (load-bearing):</strong> recession only ever applies
 * to an <em>unauthored</em> fill (the container's {@code getFillColor()} is
 * {@code null} — the EMF default for an object our code never stamped a fill on).
 * A non-null fill is treated as authored by the caller and is left byte-identical.
 * That single guard also makes the recede idempotent: after the first recede the
 * fill is non-null, so a second nested child does not re-recede.</p>
 *
 * <p>The recede touches <em>only</em> the fill colour — opacity/alpha, line, font,
 * and every other styling attribute are untouched (recede by lightness, not by
 * opacity). {@link #undo()} restores the prior (null) fill; combined atomically
 * with the add via {@link #wrap} so the parent fill and the child membership are a
 * single undoable unit.</p>
 *
 * <p><strong>CRITICAL:</strong> like its sibling add commands this MUST be executed
 * via {@code CommandStack.execute()} through {@link MutationDispatcher}. Direct
 * invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class RecedeContainerFillCommand extends Command {

    /**
     * The recession backdrop fill (very light neutral grey). A fixed neutral is
     * deterministic and headless-testable, renders as an obvious recessed backdrop
     * behind layer-coloured children, and — unlike resolving Archi's live theme —
     * keeps the model layer free of UI/theme dependencies. Graduated per-depth
     * shading is a separate post-assembly concern (auto-layout), not this emitter.
     */
    static final String CONTAINER_RECESSION_FILL = "#F4F4F4";

    private final IDiagramModelObject container;
    private String priorFill;

    /** Package-visible — constructed only by the emit paths via {@link #wrap}. */
    RecedeContainerFillCommand(IDiagramModelObject container) {
        this.container = container;
        setLabel("Recede container fill");
    }

    @Override
    public void execute() {
        priorFill = container.getFillColor();
        container.setFillColor(CONTAINER_RECESSION_FILL);
    }

    @Override
    public void undo() {
        container.setFillColor(priorFill);
    }

    /**
     * Wraps {@code base} (the add-to-view / add-group-to-view command) so that, when
     * the parent qualifies, the parent's fill recedes atomically with the add. When
     * the parent does not qualify (root view, non-element/group container, authored
     * fill, or {@code recede:false}), {@code base} is returned unchanged — no compound,
     * no behaviour change.
     */
    static Command wrap(Command base, IDiagramModelContainer parent, StylingParams styling) {
        if (!shouldRecede(parent, styling)) {
            return base;
        }
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(base.getLabel());
        compound.add(new RecedeContainerFillCommand((IDiagramModelObject) parent));
        compound.add(base);
        return compound;
    }

    /**
     * True when nesting a child into {@code parent} should recede the parent's fill:
     * the parent is an element or group (NOT the root view or any other container),
     * its fill is unauthored ({@code null}), and the caller has not opted out via
     * {@code recede:false}.
     */
    static boolean shouldRecede(IDiagramModelContainer parent, StylingParams styling) {
        if (!(parent instanceof IDiagramModelArchimateObject) && !(parent instanceof IDiagramModelGroup)) {
            return false; // root view, or any non-element/group container — never recede
        }
        if (((IDiagramModelObject) parent).getFillColor() != null) {
            return false; // authored fill is sacrosanct (provenance gate + idempotency)
        }
        return styling == null || styling.recede() == null || styling.recede();
    }

    /** Package-visible for testing. */
    IDiagramModelObject getContainer() {
        return container;
    }
}
