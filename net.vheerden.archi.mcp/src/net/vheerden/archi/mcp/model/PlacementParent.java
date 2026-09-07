package net.vheerden.archi.mcp.model;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

/**
 * The container a view object sits in, named by its view-object id.
 *
 * <p>Without it the geometry beside it cannot be read. A nested object's x/y are relative to its
 * immediate parent's top-left corner, so a response reporting {@code x: 30, y: 30} and nothing else
 * is a frame with no origin — the client is an agent that cannot see the canvas, and 30 from the
 * left of <em>what</em> is exactly the question it cannot answer. Naming the parent does not merely
 * add a fact; it makes the numbers already on the wire interpretable.</p>
 *
 * <p>Both readings below answer with the container as it stands, never with whatever the request
 * asked for. A request names a parent as a string that still has to be resolved — through a
 * same-call back-reference, through a queued container an open batch has not executed, or through a
 * live lookup on the view — and a placement whose reference resolved to a container the caller did
 * not intend is precisely the case a report has to survive. Echoing the request there would repeat
 * the caller's own mistake back to it as confirmation.</p>
 */
final class PlacementParent {

    private PlacementParent() {
    }

    /**
     * The parent an object is being placed into, resolved.
     *
     * <p>For the prepare paths, whose command has not executed yet. The container is already the
     * object the command will place into — resolution happened before this point — so this reports
     * where the object lands rather than what was asked for.</p>
     *
     * @param container the resolved container, which is the view itself for a top-level placement
     * @return the parent's view-object id, or null when the object sits on the view
     */
    static String of(IDiagramModelContainer container) {
        return (container instanceof IDiagramModelObject box) ? box.getId() : null;
    }

    /**
     * The parent an object sits in now, read from live containment.
     *
     * <p>For any path with a written model to read: the after-dispatch pass over a bulk call, and
     * the update prepares, whose subject is already in containment. A view root is not an
     * {@link IDiagramModelObject}, so a top-level object answers null through the same test the
     * resolved reading uses rather than through a second opinion about what "top-level" means.</p>
     *
     * @param object the view object to read, or null
     * @return the parent's view-object id, or null when the object sits on the view or is unreadable
     */
    static String liveOf(EObject object) {
        EObject parent = (object == null) ? null : object.eContainer();
        return (parent instanceof IDiagramModelObject box) ? box.getId() : null;
    }
}
