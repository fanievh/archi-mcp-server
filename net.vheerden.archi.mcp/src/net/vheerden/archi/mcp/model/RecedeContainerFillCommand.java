package net.vheerden.archi.mcp.model;

import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.RecededParentDto;

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
 * <p><strong>Decided when it runs.</strong> {@link #shouldRecede} decides at prepare time whether
 * to wrap the add at all, but the fill is read again in {@link #execute()}: in a batch or a bulk
 * call every earlier operation has run by then, so a fill one of them gave the container is
 * respected rather than overwritten by a decision made against the state before it.</p>
 *
 * <p><strong>A shade neither neighbour holds.</strong> The backdrop is the first of
 * {@link #RECESSION_SHADES} that equals neither the fill of the container's own parent (when that
 * parent is an element or group) nor the fill of any of its direct children, the new one included
 * (ignoring case, as {@code assess-layout} compares them). A container receded inside a receded
 * container therefore takes the next shade instead of repeating its parent's, and the two stay
 * distinguishable at any depth. When every shade is taken the container is left unauthored. At the
 * first level the result is unchanged: {@link #CONTAINER_RECESSION_FILL}.</p>
 *
 * <p>The recede touches <em>only</em> the fill colour — opacity/alpha, line, font,
 * and every other styling attribute are untouched (recede by lightness, not by
 * opacity). {@link #undo()} restores the prior (null) fill when, and only when, the
 * recede wrote one; combined atomically with the add via {@link #wrap} so the parent
 * fill and the child membership are a single undoable unit.</p>
 *
 * <p>What it wrote is recorded in a {@link RecededParentDto}, which the placement's response
 * carries ({@link #reportOf}).</p>
 *
 * <p><strong>CRITICAL:</strong> like its sibling add commands this MUST be executed
 * via {@code CommandStack.execute()} through {@link MutationDispatcher}. Direct
 * invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class RecedeContainerFillCommand extends Command {

    /**
     * The first-level recession backdrop fill (very light neutral grey). A fixed neutral is
     * deterministic and headless-testable, renders as an obvious recessed backdrop behind
     * layer-coloured children, and — unlike resolving Archi's live theme — keeps the model layer
     * free of UI/theme dependencies.
     */
    static final String CONTAINER_RECESSION_FILL = "#F4F4F4";

    /** The backdrops tried in order; the first one no neighbour holds is used. */
    static final List<String> RECESSION_SHADES = List.of(CONTAINER_RECESSION_FILL, "#FFFFFF", "#E8E8E8");

    private final IDiagramModelObject container;
    private final IDiagramModelObject incoming;
    private final RecededParentDto report = new RecededParentDto();
    private boolean receded;

    /** Package-visible — constructed only by the emit paths via {@link #wrap}. */
    RecedeContainerFillCommand(IDiagramModelObject container) {
        this(container, null);
    }

    /**
     * @param container the container gaining a child
     * @param incoming  the child being added, not attached yet when this runs, or null
     */
    RecedeContainerFillCommand(IDiagramModelObject container, IDiagramModelObject incoming) {
        this.container = container;
        this.incoming = incoming;
        setLabel("Recede container fill");
        // Nothing is recorded until execute runs: the shade depends on what the container and its
        // neighbours hold then, so a queued or proposed placement reports no recede.
    }

    @Override
    public void execute() {
        String shade = container.getFillColor() == null ? shade() : null;
        receded = shade != null;
        if (receded) {
            container.setFillColor(shade);
        }
        report.set(container.getId(), shade);
    }

    @Override
    public void undo() {
        if (receded) {
            container.setFillColor(null);
            receded = false;
        }
        report.set(container.getId(), null);
    }

    /** The first shade neither the container's parent nor any of its direct children holds, or null. */
    private String shade() {
        String parentFill = container.eContainer() instanceof IDiagramModelObject parent
                ? parent.getFillColor() : null;
        for (String shade : RECESSION_SHADES) {
            if (!shade.equalsIgnoreCase(parentFill) && !heldByAChild(shade)) {
                return shade;
            }
        }
        return null;
    }

    private boolean heldByAChild(String shade) {
        if (incoming != null && shade.equalsIgnoreCase(incoming.getFillColor())) {
            return true;
        }
        if (container instanceof IDiagramModelContainer parent) {
            for (IDiagramModelObject child : parent.getChildren()) {
                if (shade.equalsIgnoreCase(child.getFillColor())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Wraps {@code base} (the add-to-view / add-group-to-view command) so that, when
     * the parent qualifies, the parent's fill recedes atomically with the add. When
     * the parent does not qualify (root view, non-element/group container, authored
     * fill, or {@code recede:false}), {@code base} is returned unchanged — no compound,
     * no behaviour change.
     */
    static Command wrap(Command base, IDiagramModelContainer parent, StylingParams styling) {
        return wrap(base, parent, styling, null);
    }

    /**
     * As {@link #wrap(Command, IDiagramModelContainer, StylingParams)}, naming the child being added
     * so the backdrop chosen never equals a fill that child is given.
     */
    static Command wrap(Command base, IDiagramModelContainer parent, StylingParams styling,
            IDiagramModelObject incoming) {
        if (!shouldRecede(parent, styling)) {
            return base;
        }
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(base.getLabel());
        compound.add(new RecedeContainerFillCommand((IDiagramModelObject) parent, incoming));
        compound.add(base);
        return compound;
    }

    /**
     * True when nesting a child into {@code parent} should be wrapped with a recede:
     * the parent is an element or group (NOT the root view or any other container),
     * its fill is unauthored ({@code null}) as far as the prepare can see, and the caller
     * has not opted out via {@code recede:false}. The final decision is made again when
     * the recede runs.
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

    /**
     * The recede report of the recede {@code cmd} carries, looked for through guards and compounds,
     * or null when {@code cmd} recedes nothing.
     */
    static RecededParentDto reportOf(Command cmd) {
        if (cmd instanceof RecedeContainerFillCommand recede) {
            return recede.report;
        }
        if (cmd instanceof RequireAttachedContainerCommand guard) {
            return reportOf(guard.getGuarded());
        }
        if (cmd instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                RecededParentDto found = reportOf((Command) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Package-visible for testing. */
    IDiagramModelObject getContainer() {
        return container;
    }
}
