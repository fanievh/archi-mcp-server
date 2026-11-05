package net.vheerden.archi.mcp.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

/**
 * Finds what deleting an element or a relationship takes with it: the element's relationships, the
 * diagram objects that draw the element, and the view connections that draw those relationships.
 *
 * <p>It removes what Archi's own delete removes. A relationship can itself be the source or target
 * of another relationship (an association to a relationship, say), so the relationships taken are
 * every relationship attached to the deleted concept, then every relationship attached to those,
 * and so on. The view connections taken are every connection that draws one of them, including a
 * connection drawn from or to another connection, and any other connection (a note's, say) drawn
 * from or to one of those connections or to a diagram object the delete removes, or to an object
 * nested in one. Leaving any of these behind would leave a
 * relationship or a connection whose end is no longer in the model, and the saved file would not
 * open again.</p>
 *
 * <p>Both delete commands call it when they first run, against the model as they meet it, so a
 * delete also removes what an earlier operation in the same request added: a view it cloned or
 * created, an object it placed, a relationship it connected. The accessor calls it once before
 * that, at prepare, for the counts the response reports and for the readers that ask a queued
 * delete what it will take off a view. That earlier answer is a projection: inside a batch it can
 * fall short of what the delete finally removes.</p>
 */
final class DeleteCascade {

    private static final Logger logger = LoggerFactory.getLogger(DeleteCascade.class);

    private DeleteCascade() {
    }

    /**
     * What deleting one element removes, each list in the order its removal runs: relationships
     * descending by index within each folder, view objects descending by index within each
     * container, so no removal shifts the index of one not yet made.
     *
     * @param relationshipsFound every relationship the element has, including one no folder holds,
     *                           which the delete cannot remove; this is the count a response reports
     */
    record OfElement(List<DeleteElementCommand.CascadedRelationship> relationships,
            List<DeleteElementCommand.CascadedViewReference> viewObjects,
            List<IDiagramModelConnection> viewConnections,
            int relationshipsFound) {
    }

    /**
     * What deleting one relationship removes besides the relationship itself, each list in the order
     * its removal runs, as {@link OfElement} orders it.
     *
     * @param relationships      the relationships attached to it, directly or through another
     *                           attached relationship, that a folder holds
     * @param viewConnections    the connections that draw it or one of those relationships
     * @param relationshipsFound every attached relationship, including one no folder holds
     */
    record OfRelationship(List<DeleteElementCommand.CascadedRelationship> relationships,
            List<IDiagramModelConnection> viewConnections, int relationshipsFound) {
    }

    /** The cascade of deleting {@code element} from the model it is in now; empty when it is in none. */
    static OfElement ofElement(IArchimateElement element) {
        Map<String, IArchimateRelationship> unique = attached(element);

        List<IDiagramModelConnection> viewConnections = new ArrayList<>();
        List<DeleteElementCommand.CascadedViewReference> viewObjects = new ArrayList<>();
        IFolder diagrams = diagramsOf(element.getArchimateModel());
        if (diagrams != null) {
            inFolder(diagrams, element.getId(), unique.keySet(), viewConnections, viewObjects,
                    new LinkedHashSet<>());
        }

        viewObjects.sort((a, b) -> {
            int byContainer = Integer.compare(System.identityHashCode(a.container()),
                    System.identityHashCode(b.container()));
            return byContainer != 0 ? byContainer
                    : Integer.compare(b.indexInContainer(), a.indexInContainer());
        });
        List<IConnectable> removedObjects = new ArrayList<>();
        viewObjects.forEach(ref -> removedObjects.add(ref.viewObject()));
        addHanging(viewConnections, removedObjects);
        return new OfElement(removable(unique.values(), "element"), List.copyOf(viewObjects),
                List.copyOf(viewConnections), unique.size());
    }

    /**
     * The cascade of deleting {@code relationship} from the model it is in now, besides the
     * relationship itself.
     */
    static OfRelationship ofRelationship(IArchimateRelationship relationship) {
        Map<String, IArchimateRelationship> unique = attached(relationship);
        Set<String> drawn = new LinkedHashSet<>(unique.keySet());
        drawn.add(relationship.getId());
        List<IDiagramModelConnection> viewConnections = new ArrayList<>();
        IFolder diagrams = diagramsOf(relationship.getArchimateModel());
        if (diagrams != null) {
            connectionsInFolder(diagrams, drawn, viewConnections, new LinkedHashSet<>());
        }
        addHanging(viewConnections, List.of());
        return new OfRelationship(removable(unique.values(), "relationship"), List.copyOf(viewConnections),
                unique.size());
    }

    /**
     * Claims, for a delete that removes several relationships, what deleting {@code relationship}
     * takes: it, the relationships attached to it, and the connections that draw any of them. Each
     * id is claimed once, whichever delete claims it first, so the counts it answers add up to what
     * the whole delete removes, in any order.
     *
     * @return the relationships and the view connections claimed now, in that order
     */
    static int[] claim(IArchimateRelationship relationship, Set<String> claimed) {
        OfRelationship cascade = ofRelationship(relationship);
        int relationships = claimed.add(relationship.getId()) ? 1 : 0;
        for (DeleteElementCommand.CascadedRelationship rel : cascade.relationships()) {
            relationships += claimed.add(rel.relationship().getId()) ? 1 : 0;
        }
        int connections = 0;
        for (IDiagramModelConnection conn : cascade.viewConnections()) {
            connections += claimed.add(conn.getId()) ? 1 : 0;
        }
        return new int[] {relationships, connections};
    }

    /**
     * Every relationship attached to {@code concept}, then every relationship attached to those, and
     * so on, each once, nearest first; {@code concept} itself is not among them. A self-referencing
     * relationship appears among both the source and the target relationships; keyed by id, each is
     * taken once.
     */
    private static Map<String, IArchimateRelationship> attached(IArchimateConcept concept) {
        Map<String, IArchimateRelationship> unique = new LinkedHashMap<>();
        Deque<IArchimateConcept> toVisit = new ArrayDeque<>(List.of(concept));
        while (!toVisit.isEmpty()) {
            IArchimateConcept next = toVisit.removeFirst();
            List<IArchimateRelationship> ends = new ArrayList<>(next.getSourceRelationships());
            ends.addAll(next.getTargetRelationships());
            for (IArchimateRelationship rel : ends) {
                if (rel != concept && unique.putIfAbsent(rel.getId(), rel) == null) {
                    toVisit.addLast(rel);
                }
            }
        }
        return unique;
    }

    /**
     * The relationships a delete can remove, in the order it removes them: descending by index within
     * each folder. A relationship no folder holds exists only as a cross-reference and cannot be
     * removed from one, so it is left as it is.
     */
    private static List<DeleteElementCommand.CascadedRelationship> removable(
            Collection<IArchimateRelationship> found, String deleted) {
        List<DeleteElementCommand.CascadedRelationship> relationships = new ArrayList<>();
        for (IArchimateRelationship rel : found) {
            if (!(rel.eContainer() instanceof IFolder folder)) {
                logger.warn("Skipping orphaned relationship {} during {} deletion "
                        + "(exists in cross-references but not in containment tree)", rel.getId(), deleted);
                continue;
            }
            relationships.add(new DeleteElementCommand.CascadedRelationship(rel, folder,
                    folder.getElements().indexOf(rel), rel.getSource(), rel.getTarget()));
        }
        relationships.sort((a, b) -> {
            int byFolder = Integer.compare(System.identityHashCode(a.folder()),
                    System.identityHashCode(b.folder()));
            return byFolder != 0 ? byFolder : Integer.compare(b.indexInFolder(), a.indexInFolder());
        });
        return List.copyOf(relationships);
    }

    private static IFolder diagramsOf(IArchimateModel model) {
        return model == null ? null : model.getFolder(FolderType.DIAGRAMS);
    }

    private static void inFolder(IFolder folder, String elementId, Set<String> relationshipIds,
            List<IDiagramModelConnection> viewConnections,
            List<DeleteElementCommand.CascadedViewReference> viewObjects, Set<IDiagramModelConnection> walked) {
        for (EObject obj : folder.getElements()) {
            if (obj instanceof IArchimateDiagramModel view) {
                inContainer(view, elementId, relationshipIds, viewConnections, viewObjects, walked);
            }
        }
        for (IFolder sub : folder.getFolders()) {
            inFolder(sub, elementId, relationshipIds, viewConnections, viewObjects, walked);
        }
    }

    private static void inContainer(IDiagramModelContainer container, String elementId,
            Set<String> relationshipIds, List<IDiagramModelConnection> viewConnections,
            List<DeleteElementCommand.CascadedViewReference> viewObjects, Set<IDiagramModelConnection> walked) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject archObj && archObj.getArchimateElement() != null
                    && elementId.equals(archObj.getArchimateElement().getId())) {
                viewObjects.add(new DeleteElementCommand.CascadedViewReference(archObj, container,
                        container.getChildren().indexOf(archObj)));
            }
            addConnectionsOf(child, relationshipIds, viewConnections, walked);
            if (child instanceof IDiagramModelContainer nested) {
                inContainer(nested, elementId, relationshipIds, viewConnections, viewObjects, walked);
            }
        }
    }

    private static void connectionsInFolder(IFolder folder, Set<String> relationshipIds,
            List<IDiagramModelConnection> viewConnections, Set<IDiagramModelConnection> walked) {
        for (EObject obj : folder.getElements()) {
            if (obj instanceof IArchimateDiagramModel view) {
                connectionsInContainer(view, relationshipIds, viewConnections, walked);
            }
        }
        for (IFolder sub : folder.getFolders()) {
            connectionsInFolder(sub, relationshipIds, viewConnections, walked);
        }
    }

    private static void connectionsInContainer(IDiagramModelContainer container, Set<String> relationshipIds,
            List<IDiagramModelConnection> viewConnections, Set<IDiagramModelConnection> walked) {
        for (IDiagramModelObject child : container.getChildren()) {
            addConnectionsOf(child, relationshipIds, viewConnections, walked);
            if (child instanceof IDiagramModelContainer nested) {
                connectionsInContainer(nested, relationshipIds, viewConnections, walked);
            }
        }
    }

    /**
     * The connections leaving {@code from} that draw one of {@code relationshipIds}, then those
     * leaving each of its connections, and so on: a connection can be drawn from another connection.
     * Each connection is walked once, and one that draws a relationship is listed before any drawn
     * from it.
     */
    private static void addConnectionsOf(IConnectable from, Set<String> relationshipIds,
            List<IDiagramModelConnection> viewConnections, Set<IDiagramModelConnection> walked) {
        for (IDiagramModelConnection conn : from.getSourceConnections()) {
            if (!walked.add(conn)) {
                continue;
            }
            if (conn instanceof IDiagramModelArchimateConnection archConn
                    && archConn.getArchimateRelationship() != null
                    && relationshipIds.contains(archConn.getArchimateRelationship().getId())) {
                viewConnections.add(archConn);
            }
            addConnectionsOf(conn, relationshipIds, viewConnections, walked);
        }
    }

    /**
     * Adds every connection, of any kind, drawn from or to a listed connection, a removed diagram
     * object or an object nested in one, that is not listed yet; then those drawn from or to it, and so
     * on. Left behind, such a connection (a note's line to a relationship's, say) keeps an end that is
     * no longer in the view, and the saved file would not open again. Archi's own delete takes it.
     */
    private static void addHanging(List<IDiagramModelConnection> viewConnections,
            List<IConnectable> removedObjects) {
        Set<IDiagramModelConnection> listed = new LinkedHashSet<>(viewConnections);
        Deque<IConnectable> ends = new ArrayDeque<>(viewConnections);
        for (IConnectable removed : removedObjects) {
            addWithNested(removed, ends);
        }
        while (!ends.isEmpty()) {
            IConnectable end = ends.poll();
            List<IDiagramModelConnection> attached = new ArrayList<>(end.getSourceConnections());
            attached.addAll(end.getTargetConnections());
            for (IDiagramModelConnection conn : attached) {
                if (listed.add(conn)) {
                    viewConnections.add(conn);
                    ends.add(conn);
                }
            }
        }
    }

    /** {@code object} and every diagram object nested in it, at any depth. */
    private static void addWithNested(IConnectable object, Deque<IConnectable> into) {
        into.add(object);
        if (object instanceof IDiagramModelContainer container) {
            for (IDiagramModelObject child : container.getChildren()) {
                addWithNested(child, into);
            }
        }
    }
}
