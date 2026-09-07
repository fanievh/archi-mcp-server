package net.vheerden.archi.mcp.model;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

/**
 * A view object an open batch has queued for addition, paired with the container its queued
 * command will attach it to.
 *
 * <p>Every {@code add-*-to-view} builds its object <em>detached</em> and defers
 * {@code parent.getChildren().add(object)} to the command's {@code execute()}. Inside a batch that
 * runs at commit, so between the two calls the object is real and addressable by id but reachable
 * neither by walking the view nor through {@code eContainer()}. A later operation in that same
 * batch therefore needs both halves: the object itself, to act on, and the container it is destined
 * for, because the parent-fit cascade would otherwise see no parent at all and silently skip.</p>
 *
 * <p>Both halves come from the queued command, which is the batch's own authoritative record — no
 * separate registry is maintained, so nothing can leak when a batch rolls back.</p>
 */
record QueuedViewObject(IDiagramModelObject object, IDiagramModelContainer parent) {

    /**
     * Returns the container the object currently lives in, falling back to the container a queued
     * add will attach it to.
     *
     * <p>A queued object's {@code eContainer()} is null until its add command executes, which is
     * precisely when a same-batch bounds change is being prepared. Resolving through the queued
     * parent lets the parent-fit cascade behave the same way it does on the bulk path, where the
     * equivalent fallback reads a pass-scoped pending-parent map.</p>
     *
     * @param object the object whose container is wanted
     * @param queued the queued record for that object, or null when it is already attached
     * @return the live container, the queued one, or null when neither is known
     */
    static EObject containerOf(IDiagramModelObject object, QueuedViewObject queued) {
        EObject live = object.eContainer();
        if (live != null) {
            return live;
        }
        return queued == null ? null : queued.parent();
    }
}
