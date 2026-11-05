package net.vheerden.archi.mcp.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelComponent;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Decides whether an open batch has already queued a change to what {@code auto-route-connections}
 * routes a view against, and refuses the call when it has.
 *
 * <p><strong>Why a refusal.</strong> Every prepare inside a batch reads a model that none of the
 * batch's earlier commands have touched; they all run at commit. Auto-route takes its endpoints and
 * obstacles from {@code getBounds()} and its connection set from what is attached, so a route
 * prepared after a queued move is computed for the endpoint's old position and stored at commit for
 * its new one: the route's two ends stop meeting. Feeding the queued geometry into the router
 * instead would duplicate, at prepare time, resolution that belongs to the executing commands — the
 * divergence a batch's labelling exists to declare — and could not see a removal or a connection the
 * batch adds without further projections. The agent cannot repair a stale route without ending the
 * batch anyway, so refusing costs it one call and never commits a view whose routes miss their
 * elements.</p>
 *
 * <p><strong>What counts as a change.</strong> Each kind below is read from the queue, never
 * recorded by the prepare that queued it, so it holds for every tool that queues that command —
 * including a {@code bulk-mutate} pass, whose compound the queue walk recurses into:</p>
 * <ol>
 *   <li><em>moved or resized</em> — {@link MutationContext#queuedBounds()} holds an object that is on
 *       the view and the queued rectangle differs from its current one. A write that leaves the
 *       rectangle as it is changes nothing a route reads.</li>
 *   <li><em>added</em> — {@link MutationContext#queuedParents()} holds an object destined for the
 *       view, directly or through a container the batch also adds.</li>
 *   <li><em>connections added</em> — an {@link AddConnectionToViewCommand} with an end on, or
 *       destined for, the view (an end may itself be a connection). Until commit the connection is attached to nothing, so a full route
 *       silently leaves it out and a route that names it reports it not found.</li>
 *   <li><em>removed</em> — a {@link RemoveFromViewCommand}, {@link RemoveViewObjectCommand} or
 *       {@link RemoveConnectionFromViewCommand} for a component on the view, or the view objects and
 *       connections a {@link DeleteElementCommand} or {@link DeleteRelationshipCommand} cascades off
 *       it; everything on it when a {@link ClearViewCommand} or {@link DeleteViewCommand} targets it;
 *       a placeholder on it that a {@link DeleteViewCommand} of the view it references cascades; and
 *       any of these nested in a forced {@link DeleteFolderCommand}, whose deletes the queue walk
 *       does not open. A route would still detour around an object the commit takes away.</li>
 *   <li><em>connection bendpoints edited</em> — an {@link UpdateViewConnectionCommand} that writes
 *       the bendpoints of a connection being routed. Read for {@code mode: "terminals-only"} only:
 *       that mode rectifies the ends of the route it reads, so it would rewrite the pre-batch route
 *       and lose the edit; and for {@code mode: "labels-only"} on any connection on the view, whether or
 *       not it is labelled, because every line is a line some label is scored against. For that mode a
 *       self-connection loop a queued route will store counts too.</li>
 *   <li><em>label positions edited</em> — read for {@code mode: "labels-only"} only: an
 *       {@link UpdateViewConnectionCommand} that writes a text position, a {@link SetTextPositionCommand} or a
 *       {@link SetTextRelativePositionCommand} (Label Offset) for a connection on the view. The pass keeps a
 *       label's current position unless it finds a better one, so it would decide from the pre-batch value
 *       and, at commit, write over the caller's.</li>
 *   <li><em>relationships renamed</em> — read for {@code mode: "labels-only"} only: an
 *       {@link UpdateRelationshipCommand} that changes the name of a relationship drawn on the view. A label's
 *       box is as wide as its text.</li>
 *   <li><em>router type changed</em> — read for {@code mode: "labels-only"} only: an {@link UpdateViewCommand}
 *       that changes the view's connection router type. A queued switch to manhattan means Archi will not draw
 *       the stored bendpoints the pass scores against, and a queued switch away from it comes with routes the
 *       pass cannot see yet.</li>
 * </ol>
 *
 * <p>Per form: {@code full} reads kinds 1–4; {@code terminals-only} kinds 1–5, the bendpoint edits of its
 * targets only; {@code strategy: "clear"} kind 3 only; {@code labels-only} every kind, over the whole view
 * (it takes no connection subset).</p>
 *
 * <p><strong>Deliberately not changes.</strong> Styling, label visibility and text position change
 * nothing a route is computed from; label visibility and styling change nothing the labels-only pass reads either
 * (it reads a queued visibility through the queue, and a label's box is a character count, not a font measure). A full or clear route over a queued bendpoint edit overwrites
 * that edit, exactly as the same two calls do one after the other outside a batch. And
 * {@code strategy: "clear"} reads no geometry at all — it empties the bendpoints of the connections
 * it is given — so it is refused only when the batch adds a connection to the view, the one input it
 * does read. The same holds for a call whose every target is a self-connection: its loop is decided
 * when its command runs, from the bounds its object has then, so nothing is computed from stale
 * geometry.</p>
 *
 * <p>Outside a batch every queue reader returns null, so the check returns before reading anything
 * and the applied and approval paths are untouched.</p>
 *
 * <p>Which commands remove what, where a staged object will land, and the staged work ahead of a
 * note's placement are read through {@link StagedViewState}, the one reader the batch-aware prepares
 * share, so this class and those prepares cannot disagree about what the batch holds.</p>
 */
final class QueuedViewGeometry {

    /** At most this many ids are named per kind of change; the rest are counted. */
    static final int MAX_NAMED_IDS = 10;

    static final String MOVED = "moved or resized";
    static final String ADDED = "added";
    static final String CONNECTIONS_ADDED = "connections added";
    static final String REMOVED = "removed";
    static final String BENDPOINTS_EDITED = "connection bendpoints edited";
    static final String LABELS_EDITED = "label positions edited";
    static final String RENAMED = "relationships renamed";
    static final String ROUTER_CHANGED = "router type changed";

    /** The routing forms and the labels-only pass, which read different parts of the view. */
    enum Form { FULL, TERMINALS_ONLY, CLEAR, LABELS_ONLY }

    private QueuedViewGeometry() {}

    /**
     * Throws the refusal when this session's open batch has queued a change to what this call would
     * route the view against. Returns without reading anything outside a batch.
     *
     * @param mode          the call's mode: {@code full}, {@code terminals-only} or {@code labels-only}
     * @param connectionIds the connections the call was asked to route, or null/empty for all
     * @throws ModelAccessException {@code INVALID_PARAMETER} naming each queued change, by kind
     */
    static void refuseIfChanged(MutationDispatcher dispatcher, String sessionId, IDiagramModel view,
            String strategy, String mode, Collection<String> connectionIds) {
        // Each projection walks the whole queue under the session lock, so each is read once.
        List<Command> leaves = dispatcher.queuedLeafCommands(sessionId);
        if (leaves == null) {
            return;
        }
        Form form = "clear".equals(strategy) ? Form.CLEAR : "terminals-only".equals(mode) ? Form.TERMINALS_ONLY
                : "labels-only".equals(mode) ? Form.LABELS_ONLY : Form.FULL;
        Map<String, List<String>> changes = changes(view, form, dispatcher.queuedBounds(sessionId),
                dispatcher.queuedParents(sessionId), leaves, connectionIds);
        if (!changes.isEmpty()) {
            throw refusal(view, changes);
        }
    }

    /**
     * The queued changes to what {@code form} routes {@code view} against, keyed by kind in a fixed
     * order, each with the ids it names; empty when there are none. Every argument but {@code view}
     * and {@code form} may be null.
     */
    static Map<String, List<String>> changes(IDiagramModel view, Form form, Map<String, int[]> queuedBounds,
            Map<String, IDiagramModelContainer> queuedParents, List<Command> leaves,
            Collection<String> connectionIds) {
        Set<String> wanted = (connectionIds == null || connectionIds.isEmpty()) ? null : Set.copyOf(connectionIds);
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (String kind : List.of(MOVED, ADDED, CONNECTIONS_ADDED, REMOVED, BENDPOINTS_EDITED, LABELS_EDITED, RENAMED,
                ROUTER_CHANGED)) {
            found.put(kind, new LinkedHashSet<>());
        }
        Map<String, IDiagramModelObject> onView = new LinkedHashMap<>();
        collectObjects(view, onView);
        Set<Command> inert = StagedViewState.inert(leaves == null ? List.of() : leaves);
        boolean labels = form == Form.LABELS_ONLY;
        boolean geometry = form != Form.CLEAR && routesAConnection(onView.values(), labels ? null : wanted, labels);
        if (geometry && queuedBounds != null) {
            queuedBounds.forEach((id, b) -> {
                IDiagramModelObject obj = onView.get(id);
                if (obj != null && !sameBounds(obj.getBounds(), b)) {
                    found.get(MOVED).add(id);
                }
            });
        }
        if (geometry && queuedParents != null) {
            queuedParents.forEach((id, parent) -> {
                if (StagedViewState.destinedDiagram(parent, queuedParents) == view) {
                    found.get(ADDED).add(id);
                }
            });
        }
        for (Command leaf : leaves == null ? List.<Command>of() : leaves) {
            if (leaf instanceof AddConnectionToViewCommand add) {
                String id = add.getConnection().getId();
                if ((wanted == null || wanted.contains(id)) && (StagedViewState.destinedDiagram(add.getSource(), queuedParents) == view
                        || StagedViewState.destinedDiagram(add.getTarget(), queuedParents) == view)) {
                    found.get(CONNECTIONS_ADDED).add(id);
                }
            } else if (geometry) {
                for (IDiagramModelComponent removed : StagedViewState.removedBy(leaf, inert)) {
                    if (removed != null && removed.getDiagramModel() == view) {
                        found.get(REMOVED).add(removed.getId());
                    }
                }
                if ((labels || form == Form.TERMINALS_ONLY) && leaf instanceof UpdateViewConnectionCommand update
                        && update.hasBendpointChange() && update.getConnection().getDiagramModel() == view
                        && (labels || wanted == null || wanted.contains(update.getConnection().getId()))) {
                    found.get(BENDPOINTS_EDITED).add(update.getConnection().getId());
                }
                if (labels) {
                    if (leaf instanceof SelfConnectionLoop.LoopCommand loop
                            && loop.getConnection().getDiagramModel() == view) {
                        found.get(BENDPOINTS_EDITED).add(loop.getConnection().getId());
                    }
                    if (leaf instanceof UpdateViewCommand update && update.changesRouterTypeOf(view)) {
                        found.get(ROUTER_CHANGED).add(view.getId());
                    }
                    String edited = labelPositionEdited(leaf, view);
                    if (edited != null) {
                        found.get(LABELS_EDITED).add(edited);
                    }
                    if (leaf instanceof UpdateRelationshipCommand rename && rename.hasNameChange()
                            && drawnOn(rename.getRelationship(), view)) {
                        found.get(RENAMED).add(rename.getRelationship().getId());
                    }
                }
            }
        }
        Map<String, List<String>> changes = new LinkedHashMap<>();
        found.forEach((kind, ids) -> {
            if (!ids.isEmpty()) {
                changes.put(kind, List.copyOf(ids));
            }
        });
        return changes;
    }

    /**
     * Throws the refusal when a note's content-relative {@code position} would be resolved against
     * content that earlier work has already changed: an operation queued in this session's open
     * batch, or one prepared earlier in the same {@code bulk-mutate} call. Neither has run when the
     * note is prepared, so the content bounds it reads are those from before them, and a note placed
     * "above the content" would land under an object the commit adds above it. Returns without
     * reading anything when there is no open batch and no earlier operation.
     *
     * @param sameCall the operations already prepared in this bulk call, or null outside one
     * @throws ModelAccessException {@code INVALID_PARAMETER} naming each change, by kind
     */
    static void refusePositionIfChanged(MutationDispatcher dispatcher, String sessionId, IDiagramModel view,
            List<PreparedMutation<?>> sameCall) {
        Map<String, List<String>> changes = contentChanges(view,
                StagedViewState.of(dispatcher, sessionId, sameCall).leaves());
        if (!changes.isEmpty()) {
            throw positionRefusal(view, changes);
        }
    }

    /**
     * The changes {@code leaves} make to the content a note's {@code position} is placed against:
     * every object on (or destined for) the view except notes, which the placement leaves out, and
     * the bendpoints of its connections. Keyed by kind in a fixed order; empty when there are none.
     */
    static Map<String, List<String>> contentChanges(IDiagramModel view, List<Command> leaves) {
        Map<String, IDiagramModelContainer> parents = new LinkedHashMap<>();
        Map<String, IDiagramModelObject> created = new LinkedHashMap<>();
        Map<String, UpdateViewObjectCommand> lastUpdate = new LinkedHashMap<>();
        for (Command leaf : leaves) {
            MutationContext.forEachCreated(leaf, c -> {
                parents.putIfAbsent(c.object().getId(), c.parent());
                created.putIfAbsent(c.object().getId(), c.object());
            });
            // An update that sets no bounds axis moves nothing when it runs, so it is not the one
            // whose rectangle says where the object goes.
            if (leaf instanceof UpdateViewObjectCommand update && update.getDiagramObject() != null
                    && !NamedAxes.NONE.equals(update.getAxes())) {
                lastUpdate.put(update.getDiagramObject().getId(), update);
            }
        }
        Set<Command> inert = StagedViewState.inert(leaves);
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (String kind : List.of(MOVED, ADDED, CONNECTIONS_ADDED, REMOVED, BENDPOINTS_EDITED)) {
            found.put(kind, new LinkedHashSet<>());
        }
        created.forEach((id, obj) -> {
            if (!(obj instanceof IDiagramModelNote) && StagedViewState.destinedDiagram(obj, parents) == view) {
                found.get(ADDED).add(id);
            }
        });
        lastUpdate.forEach((id, update) -> {
            IDiagramModelObject obj = update.getDiagramObject();
            if (!(obj instanceof IDiagramModelNote) && !found.get(ADDED).contains(id)
                    && StagedViewState.destinedDiagram(obj, parents) == view && !sameBounds(obj.getBounds(), new int[] {
                            update.getNewX(), update.getNewY(), update.getNewWidth(), update.getNewHeight() })) {
                found.get(MOVED).add(id);
            }
        });
        for (Command leaf : leaves) {
            if (leaf instanceof AddConnectionToViewCommand add) {
                if (StagedViewState.destinedDiagram(add.getSource(), parents) == view || StagedViewState.destinedDiagram(add.getTarget(), parents) == view) {
                    found.get(CONNECTIONS_ADDED).add(add.getConnection().getId());
                }
            } else if (leaf instanceof UpdateViewConnectionCommand update) {
                if (update.hasBendpointChange() && update.getConnection().getDiagramModel() == view) {
                    found.get(BENDPOINTS_EDITED).add(update.getConnection().getId());
                }
            } else {
                for (IDiagramModelComponent removed : StagedViewState.removedBy(leaf, inert)) {
                    if (removed != null && !(removed instanceof IDiagramModelNote) && removed.getDiagramModel() == view) {
                        found.get(REMOVED).add(removed.getId());
                    }
                }
            }
        }
        Map<String, List<String>> changes = new LinkedHashMap<>();
        found.forEach((kind, ids) -> {
            if (!ids.isEmpty()) {
                changes.put(kind, List.copyOf(ids));
            }
        });
        return changes;
    }

    /**
     * Whether the call routes at least one connection between two different ends. One that routes
     * none computes nothing from geometry: every target is a self-connection, whose loop is decided
     * when its command runs, from the bounds its object has then. Target connections are read too,
     * as the router collects them: a connection whose source is another connection reaches the view
     * only through its target object. With {@code selfCounts} (the labels-only pass, which places a
     * self-connection's label as it places any other) a self-connection counts too.
     */
    private static boolean routesAConnection(Collection<IDiagramModelObject> onView, Set<String> wanted,
            boolean selfCounts) {
        for (IDiagramModelObject obj : onView) {
            for (List<IDiagramModelConnection> attached : List.of(obj.getSourceConnections(), obj.getTargetConnections())) {
                for (IDiagramModelConnection conn : attached) {
                    if ((selfCounts || conn.getSource() != conn.getTarget())
                            && (wanted == null || wanted.contains(conn.getId()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The id of the connection on {@code view} whose text position or Label Offset {@code leaf} writes, or null when
     * it writes neither there.
     */
    private static String labelPositionEdited(Command leaf, IDiagramModel view) {
        IDiagramModelConnection conn = leaf instanceof UpdateViewConnectionCommand update && update.hasTextPositionChange()
                ? update.getConnection()
                : leaf instanceof SetTextPositionCommand set ? set.getConnection()
                : leaf instanceof SetTextRelativePositionCommand set ? set.getConnection() : null;
        return conn != null && conn.getDiagramModel() == view ? conn.getId() : null;
    }

    /** Whether {@code relationship} is drawn on {@code view}: some connection on the view references it. */
    private static boolean drawnOn(IArchimateRelationship relationship, IDiagramModel view) {
        for (IDiagramModelArchimateComponent component : relationship.getReferencingDiagramComponents()) {
            if (component.getDiagramModel() == view) {
                return true;
            }
        }
        return false;
    }

    private static void collectObjects(IDiagramModelContainer container, Map<String, IDiagramModelObject> into) {
        for (IDiagramModelObject child : container.getChildren()) {
            into.put(child.getId(), child);
            if (child instanceof IDiagramModelContainer nested) {
                collectObjects(nested, into);
            }
        }
    }

    private static boolean sameBounds(IBounds live, int[] queued) {
        return live.getX() == queued[0] && live.getY() == queued[1]
                && live.getWidth() == queued[2] && live.getHeight() == queued[3];
    }

    /** Each kind of change with the ids it names, at most {@link #MAX_NAMED_IDS} per kind. */
    private static String listed(Map<String, List<String>> changes) {
        StringBuilder listed = new StringBuilder();
        changes.forEach((kind, ids) -> {
            listed.append(listed.length() == 0 ? "" : "; ").append(kind).append(": ")
                    .append(String.join(", ", ids.subList(0, Math.min(ids.size(), MAX_NAMED_IDS))));
            if (ids.size() > MAX_NAMED_IDS) {
                listed.append(" (and ").append(ids.size() - MAX_NAMED_IDS).append(" more)");
            }
        });
        return listed.toString();
    }

    private static ModelAccessException positionRefusal(IDiagramModel view, Map<String, List<String>> changes) {
        return new ModelAccessException(
                "position places a note against the view's content as it was before this call and any open"
                        + " batch, and earlier operations have already changed that content on view '" + view.getId()
                        + "' — " + listed(changes) + ". None of them has run yet, so the note would be placed"
                        + " against content the view will no longer have; it was not placed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass x and y instead of position, or add the note with position in a later call (after"
                        + " end-batch when a batch is open), once those operations have run.",
                null);
    }

    private static ModelAccessException refusal(IDiagramModel view, Map<String, List<String>> changes) {
        String listed = listed(changes);
        return new ModelAccessException(
                "auto-route-connections inside a batch routes a view as it was before the batch began, and"
                        + " this batch has already queued changes to what view '" + view.getId()
                        + "' is routed against — " + listed + ". A route computed now would be stored at"
                        + " commit for geometry the view will no longer have, so nothing was queued.",
                ErrorCode.INVALID_PARAMETER, null,
                "Commit the batch with end-batch, then call auto-route-connections on the view; or call it"
                        + " before queuing these changes. The batch is still open and its queue is unchanged.",
                null);
    }
}
