package net.vheerden.archi.mcp.model;

import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * The refusals for a view object an open batch has queued but not yet placed, named where it cannot
 * serve: as a parent, as a relationship's connection end, or as the object to remove.
 *
 * <p>A queued object is detached until the batch commits, so the live lookups behind those three uses
 * cannot find it, and their answer, "not found", names an id the caller was handed by an earlier call
 * of the same batch. Each refusal says what the object is and why it cannot serve there instead. Only an
 * id that would otherwise have been answered "not found" reaches a refusal: a queued group or element
 * view object still nests and still connects, as it always has.</p>
 */
final class QueuedObjectRefusals {

    static final String PARENT_ADVICE = "A note, an image and a view reference hold no other objects. Name a "
            + "group or an element view object as parentViewObjectId.";

    static final String CONNECTION_END_ADVICE = "A relationship's connection joins the element view objects of "
            + "its source and target. A group, a note, an image and a view reference stand for no element, so "
            + "none can be its end.";

    private QueuedObjectRefusals() {
    }

    /** Refuses a queued object as a parent; called only when no queued container has that id. */
    static void refuseAsParent(QueuedViewObject queued, String id) {
        if (queued != null) {
            throw refusal("Parent view object must be a group or element: " + id + " is the "
                    + kindOf(queued.object()) + " this batch places", PARENT_ADVICE);
        }
    }

    /**
     * Refuses a queued object as a connection end when it stands for no element. A queued element view
     * object destined for another view is left to the ordinary not-found answer, as before.
     */
    static void refuseAsConnectionEnd(QueuedViewObject queued, String id) {
        if (queued != null && !(queued.object() instanceof IDiagramModelArchimateObject)) {
            throw refusal("Connection end must be an element view object: " + id + " is the "
                    + kindOf(queued.object()) + " this batch places", CONNECTION_END_ADVICE);
        }
    }

    /** Refuses removing any object the batch has queued for placement but not yet placed. */
    static void refuseRemoving(QueuedViewObject queued, String id) {
        if (queued != null) {
            throw refusal("Cannot remove a view object this batch places: " + id + " is the "
                    + kindOf(queued.object()) + " an earlier operation of this batch places",
                    "It is not on the view until the batch commits. Commit with end-batch and remove it in a "
                            + "later call, or end the batch with rollback: true to discard what it queued.");
        }
    }

    /** What a refusal calls the object: the kinds an add tool places, and a plain fallback. */
    static String kindOf(IDiagramModelObject obj) {
        if (obj instanceof IDiagramModelImage) {
            return "image";
        }
        if (obj instanceof IDiagramModelReference) {
            return "view reference";
        }
        if (obj instanceof IDiagramModelNote) {
            return "note";
        }
        if (obj instanceof IDiagramModelGroup) {
            return "group";
        }
        return obj instanceof IDiagramModelArchimateObject ? "element view object" : "view object";
    }

    private static ModelAccessException refusal(String message, String suggestion) {
        return new ModelAccessException(message, ErrorCode.INVALID_PARAMETER, null, suggestion, null);
    }
}
