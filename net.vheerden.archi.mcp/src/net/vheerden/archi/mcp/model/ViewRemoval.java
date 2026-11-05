package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;

/**
 * What taking one object or connection off a view takes with it, and how a removal names what it
 * removes.
 *
 * <p>The cascade is Archi's own: {@code DiagramCommandFactory.createDeleteDiagramObjectCommand}
 * deletes, with an object, every connection attached to it or to anything inside it, and
 * {@code createDeleteDiagramConnectionCommand} first deletes every connection attached to the
 * connection it deletes, recursively. No type is excluded. A connection left attached to something
 * that is no longer in the model is saved as a reference the file cannot resolve, so the model
 * fails to reopen.</p>
 */
final class ViewRemoval {

    private ViewRemoval() {
    }

    /**
     * Every connection removing {@code removed} takes with it, in a stable order: those attached to
     * the object (or, for a connection, to the connection itself) and its descendants first, then,
     * recursively, those attached to the connections already listed. A connection being removed is
     * never listed as its own cascade.
     */
    static List<IDiagramModelConnection> cascade(IConnectable removed) {
        Set<IDiagramModelConnection> found = new LinkedHashSet<>();
        attachedTo(removed, found);
        List<IDiagramModelConnection> ordered = new ArrayList<>(found);
        for (int i = 0; i < ordered.size(); i++) {
            for (IDiagramModelConnection onIt : attached(ordered.get(i))) {
                if (onIt != removed && found.add(onIt)) {
                    ordered.add(onIt);
                }
            }
        }
        return ordered;
    }

    private static void attachedTo(IConnectable connectable, Set<IDiagramModelConnection> found) {
        found.addAll(attached(connectable));
        if (connectable instanceof IDiagramModelContainer container) {
            for (IDiagramModelObject child : container.getChildren()) {
                attachedTo(child, found);
            }
        }
    }

    private static List<IDiagramModelConnection> attached(IConnectable connectable) {
        List<IDiagramModelConnection> attached = new ArrayList<>(connectable.getSourceConnections());
        attached.addAll(connectable.getTargetConnections());
        return attached;
    }

    /**
     * The {@code removedObjectType} a removal reports: the coarse nouns callers branch on for the
     * kinds the add tools place, {@code connection} for a connection that is not an ArchiMate one,
     * and the Archi type name for any other view object.
     */
    static String typeToken(EObject removed) {
        return switch (removed) {
            case IDiagramModelArchimateObject o -> "viewObject";
            case IDiagramModelGroup g -> "group";
            case IDiagramModelNote n -> "note";
            case IDiagramModelReference r -> "viewReference";
            case IDiagramModelImage i -> "image";
            case IDiagramModelArchimateConnection c -> "viewConnection";
            case IDiagramModelConnection c -> "connection";
            default -> removed.eClass().getName();
        };
    }

    /**
     * What an approval card says it removes, e.g. {@code view reference to 'Other'}. An element
     * reads {@code ApplicationComponent 'name'} and an ArchiMate connection
     * {@code connection (ServingRelationship)}; either degrades to {@code view object} or
     * {@code connection (unknown)} when the id no longer resolves.
     */
    static String subject(EObject removed) {
        return switch (removed) {
            case IDiagramModelArchimateObject o when o.getArchimateElement() != null ->
                    elementInfo(o.getArchimateElement());
            case IDiagramModelArchimateObject o -> "view object";
            case IDiagramModelGroup g -> "group" + quoted(g.getName());
            case IDiagramModelNote n -> "note";
            case IDiagramModelReference r -> "view reference"
                    + (r.getReferencedModel() == null ? "" : " to" + quoted(r.getReferencedModel().getName()));
            case IDiagramModelImage i -> "image";
            case IDiagramModelArchimateConnection c when c.getArchimateRelationship() != null ->
                    "connection (" + c.getArchimateRelationship().eClass().getName() + ")";
            case IDiagramModelArchimateConnection c -> "connection (unknown)";
            case IDiagramModelConnection c -> "connection";
            case IDiagramModelObject o -> o.eClass().getName();
            case null, default -> "view object";
        };
    }

    private static String elementInfo(IArchimateElement el) {
        return el.eClass().getName() + " '" + el.getName() + "'";
    }

    private static String quoted(String name) {
        return name == null || name.isBlank() ? "" : " '" + name + "'";
    }
}
