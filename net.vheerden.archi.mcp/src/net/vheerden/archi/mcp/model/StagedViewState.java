package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelComponent;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Reads a view as the work staged ahead of the asking operation will leave it: the commands this
 * session's open batch has queued, then the operations already prepared earlier in the same
 * {@code bulk-mutate} call. None of that work has run when the asking operation is prepared, so the
 * live model alone answers for the view as it was before them.
 *
 * <p>Read-only and short-lived: it never changes the model or the queue, reads the queue only through
 * {@link MutationDispatcher#queuedLeafCommands(String)} (which takes the context's lock), and is built
 * for one prepare call. Outside a batch, with nothing prepared earlier in the call, there is nothing
 * staged and every answer is the live model's.</p>
 */
final class StagedViewState {

    private final MutationDispatcher dispatcher;
    private final String sessionId;
    private final List<Command> leaves;
    /** See {@link #inert(List)}. */
    private final Set<Command> inert;

    private StagedViewState(MutationDispatcher dispatcher, String sessionId, List<Command> leaves) {
        this.dispatcher = dispatcher;
        this.sessionId = sessionId;
        this.leaves = leaves;
        this.inert = inert(leaves);
    }

    /**
     * The staged work ahead of an operation: the open batch's queue in order, then each operation
     * prepared earlier in this call, every compound flattened to the commands it runs.
     *
     * @param dispatcher the session's dispatcher, or null to read no queue (a prepare outside any batch)
     * @param sameCall   the operations already prepared in this bulk call, or null outside one
     */
    static StagedViewState of(MutationDispatcher dispatcher, String sessionId, List<PreparedMutation<?>> sameCall) {
        List<Command> queued = dispatcher == null ? null : dispatcher.queuedLeafCommands(sessionId);
        List<Command> leaves = new ArrayList<>(queued == null ? List.of() : queued);
        for (PreparedMutation<?> prepared : sameCall == null ? List.<PreparedMutation<?>>of() : sameCall) {
            if (prepared.command() != null) {
                MutationContext.forEachCommand(prepared.command(), leaves::add);
            }
        }
        return new StagedViewState(dispatcher, sessionId, leaves);
    }

    /**
     * The deletes that will remove nothing when they run, by identity: each folder delete that will
     * decline (see {@link #declines}), at the top of the staged work or nested in a cascade, and each
     * view delete in a folder cascade whose view an earlier command moves out of the folder first,
     * which the cascade leaves whole (see {@link DeleteFolderCommand#leftBehind}). Read once from the
     * ordered leaves; nothing is run or changed. {@link QueuedViewGeometry} reads it too, so its
     * removals and this reader's agree.
     */
    static Set<Command> inert(List<Command> leaves) {
        Set<Command> inert = Collections.newSetFromMap(new IdentityHashMap<>());
        forEachFolderDelete(leaves, (delete, earlier) -> {
            // Folder deletes are visited in run order, so every earlier one is already decided here
            if (declines(delete, earlier, inert)) {
                inert.add(delete);
            }
            for (Command nested : delete.getSubCommands()) {
                if (nested instanceof DeleteViewCommand deleteView && movedOut(deleteView.getView(), earlier)) {
                    inert.add(deleteView);
                }
            }
        });
        return inert;
    }

    /** Each folder delete in the staged work, nested ones included, with the commands that run before it. */
    private static void forEachFolderDelete(List<Command> leaves,
            java.util.function.BiConsumer<DeleteFolderCommand, List<Command>> visit) {
        for (int k = 0; k < leaves.size(); k++) {
            if (leaves.get(k) instanceof DeleteFolderCommand delete) {
                visitFolderDelete(delete, leaves.subList(0, k), visit);
            }
        }
    }

    private static void visitFolderDelete(DeleteFolderCommand delete, List<Command> earlier,
            java.util.function.BiConsumer<DeleteFolderCommand, List<Command>> visit) {
        visit.accept(delete, earlier);
        for (Command nested : delete.getSubCommands()) {
            if (nested instanceof DeleteFolderCommand inner) {
                visitFolderDelete(inner, earlier, visit);
            }
        }
    }

    /**
     * Whether {@code delete} will decline when it runs: an earlier command puts a direct child into
     * its folder that the delete was not prepared with, by a move, a create (a clone's included) or
     * a new subfolder. The delete then leaves the folder and everything in it in place. A child
     * added after the delete does not count; a child taken out of the folder does not make it
     * decline. A nested folder's decline does not void its siblings' deletes, which still run. A move
     * of an object a command before it deletes does not count either: that move declines, so nothing
     * arrives. A move that runs before the object's delete does count.
     *
     * @param inert the deletes already read as removing nothing, for the folder deletes before this one
     */
    private static boolean declines(DeleteFolderCommand delete, List<Command> earlier, Set<Command> inert) {
        IFolder folder = delete.getFolder();
        for (int k = 0; k < earlier.size(); k++) {
            Command leaf = earlier.get(k);
            if ((leaf instanceof MoveToFolderCommand move && move.getTargetFolder() == folder
                            && !delete.wasPreparedWith(move.getObject())
                            && !deletedBefore(move.getObject(), earlier.subList(0, k), inert))
                    || (leaf instanceof CreateElementCommand create && create.getFolder() == folder)
                    || (leaf instanceof CreateViewCommand create && create.getFolder() == folder)
                    || (leaf instanceof CreateRelationshipCommand create && create.getFolder() == folder)
                    || (leaf instanceof CreateFolderCommand create && create.getParentFolder() == folder)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a command in {@code earlier} deletes {@code object}: itself, as a relationship an element
     * or relationship delete takes along, or in a folder delete's cascade.
     */
    private static boolean deletedBefore(Object object, List<Command> earlier, Set<Command> inert) {
        for (Command leaf : earlier) {
            if (deletes(leaf, object, inert)) {
                return true;
            }
        }
        return false;
    }

    private static boolean deletes(Command leaf, Object object, Set<Command> inert) {
        if (inert.contains(leaf)) {
            return false;
        }
        // An element or relationship delete also removes the relationships attached to it
        if (leaf instanceof DeleteElementCommand delete) {
            return delete.getElement() == object || delete.removesRelationship(object);
        } else if (leaf instanceof DeleteRelationshipCommand delete) {
            return delete.getRelationship() == object || delete.removesRelationship(object);
        } else if (leaf instanceof DeleteViewCommand delete) {
            return delete.getView() == object;
        } else if (leaf instanceof DeleteFolderCommand delete) {
            if (delete.getFolder() == object) {
                return true;
            }
            for (Command nested : delete.getSubCommands()) {
                if (deletes(nested, object, inert)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether an earlier command moves {@code view} out of the folder it is in now. */
    private static boolean movedOut(Object view, List<Command> earlier) {
        // The last earlier move of the view says where it is when the delete runs: a move out
        // that a later move brings back leaves it in the folder, and the delete takes it.
        MoveToFolderCommand last = null;
        for (Command leaf : earlier) {
            if (leaf instanceof MoveToFolderCommand move && move.getObject() == view) {
                last = move;
            }
        }
        return last != null && last.getTargetFolder() != ((EObject) view).eContainer();
    }

    /**
     * The view {@code viewId} names: one this call created, else one the open batch has queued, else
     * the live view; null when it names none of them, or when the staged work deletes it ahead of the
     * asking operation (a {@code delete-view} of it, or a {@code delete-folder} holding it). This call's
     * own views shadow the batch's, as every other same-call lookup does. A created view is not in the
     * model's containment until its command runs, which is why the live lookup alone cannot find it.
     *
     * @param createdViews the views created earlier in this bulk call, or null outside one
     */
    IArchimateDiagramModel viewOrNull(IArchimateModel model, String viewId,
            Map<Integer, IArchimateDiagramModel> createdViews) {
        IArchimateDiagramModel view = stagedOrLive(model, viewId, createdViews);
        if (view == null) {
            return null;
        }
        for (Command leaf : leaves) {
            if (deletes(leaf, view)) {
                return null;
            }
        }
        return view;
    }

    private IArchimateDiagramModel stagedOrLive(IArchimateModel model, String viewId,
            Map<Integer, IArchimateDiagramModel> createdViews) {
        for (IArchimateDiagramModel created : createdViews == null ? List.<IArchimateDiagramModel>of()
                : createdViews.values()) {
            if (viewId.equals(created.getId())) {
                return created;
            }
        }
        IArchimateDiagramModel queued = dispatcher == null ? null : dispatcher.queuedCreatedView(sessionId, viewId);
        if (queued != null) {
            return queued;
        }
        return ArchimateModelUtils.getObjectByID(model, viewId) instanceof IArchimateDiagramModel live ? live : null;
    }

    /**
     * Whether {@code leaf} deletes {@code view}: a delete of it, or a folder delete whose cascade does,
     * unless the delete is inert (see {@link #inert(List)}): the folder delete will decline, or the
     * view is moved out of the folder first.
     */
    private boolean deletes(Command leaf, IDiagramModel view) {
        if (inert.contains(leaf)) {
            return false;
        }
        if (leaf instanceof DeleteViewCommand delete) {
            return delete.getView() == view;
        }
        if (leaf instanceof DeleteFolderCommand delete) {
            for (Command nested : delete.getSubCommands()) {
                if (deletes(nested, view)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The name {@code object} will show when the asking operation runs: for an element's object, the
     * name the last staged {@code update-element} rename gives its element; for a group, the text the
     * last staged {@code update-view-object} gives it; else the live name. A staged update that leaves
     * the name alone does not count.
     */
    String nameOf(IDiagramModelObject object) {
        String name = object.getName();
        for (Command leaf : leaves) {
            if (object instanceof IDiagramModelArchimateObject archimate
                    && leaf instanceof UpdateElementCommand update && update.getNewName() != null
                    && update.getElement() == archimate.getArchimateElement()) {
                name = update.getNewName();
            } else if (object instanceof IDiagramModelGroup
                    && leaf instanceof UpdateViewObjectCommand update && update.getNewText() != null
                    && update.getDiagramObject() == object) {
                name = update.getNewText();
            }
        }
        return name;
    }

    /** The staged commands, in the order they will run. */
    List<Command> leaves() {
        return leaves;
    }

    /** The staged commands with each {@code delete-folder} followed by the deletes its cascade runs, at any depth. */
    private List<Command> cascaded() {
        List<Command> all = new ArrayList<>();
        for (Command leaf : leaves) {
            addCascade(leaf, all);
        }
        return all;
    }

    private void addCascade(Command leaf, List<Command> into) {
        if (inert.contains(leaf)) {
            return;
        }
        into.add(leaf);
        if (leaf instanceof DeleteFolderCommand delete) {
            for (Command nested : delete.getSubCommands()) {
                addCascade(nested, into);
            }
        }
    }

    /**
     * The diagram objects, at any depth, that will be on {@code view} when the asking operation runs:
     * the live ones, then the staged work replayed in the order it will run. An add whose parent is the
     * view, or a container present at that point, puts its object on; a removal takes its objects off
     * with everything nested in them; a delete of an element takes off every object of that element
     * present at that point, including one placed earlier in the replay, which the delete's own list,
     * made from the live model when it was prepared, does not name; and a clear or a delete of the view
     * takes off everything present at that point, including objects added earlier in the replay.
     *
     * <p>Order matters, so this is a replay rather than "live plus added minus removed": an object
     * added after a clear stays, one added before it does not. Live objects come first, depth-first, in
     * the order the view holds them, so with nothing staged the answer is exactly the live view's.</p>
     */
    List<IDiagramModelObject> objectsOn(IDiagramModel view) {
        return List.copyOf(replayed(view).present());
    }

    /**
     * The objects {@link #objectsOn} answers whose parent is the view itself: what a clear removes as
     * units, each taking whatever is nested in it along.
     */
    List<IDiagramModelObject> topLevelOn(IDiagramModel view) {
        Replayed replayed = replayed(view);
        List<IDiagramModelObject> top = new ArrayList<>();
        for (IDiagramModelObject object : replayed.present()) {
            Object parent = object.eContainer() != null ? object.eContainer() : replayed.parents().get(object.getId());
            if (parent == view) {
                top.add(object);
            }
        }
        return top;
    }

    /** The element objects among those {@link #objectsOn} answers, in the same order. */
    List<IDiagramModelArchimateObject> elementObjectsOn(IDiagramModel view) {
        List<IDiagramModelArchimateObject> elements = new ArrayList<>();
        for (IDiagramModelObject object : replayed(view).present()) {
            if (object instanceof IDiagramModelArchimateObject element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /**
     * The relationships that will join {@code element} when the asking operation runs, each with its
     * other end: the live ones in the element's own order, then those the staged work creates in the
     * order it will run, less every one a staged delete of the relationship removes and every one a
     * staged delete of either end takes with it. A delete of an element removes all its relationships
     * when it runs, not only those the live model held when it was prepared, so it takes a staged
     * create along too; a {@code delete-folder} deletes what its cascade names. A relationship a staged
     * {@code change-relationship-type} lists is not listed: the command replaces it with a new object
     * when it runs, so no connection can be drawn for it before then. Each relationship is listed once.
     *
     * <p>A relationship the staged work creates is not connected to either end until its command runs,
     * which is why its ends are read from the command rather than from the relationship.</p>
     *
     * @param outgoing true for the relationships {@code element} is the source of, false for the
     *                 target of
     */
    List<PlacementAutoConnect.Joined> relationshipsOf(IArchimateElement element, boolean outgoing) {
        List<PlacementAutoConnect.Joined> joined = new ArrayList<>(PlacementAutoConnect.live(element, outgoing));
        Set<Object> deleted = new HashSet<>();
        Set<String> retyped = new HashSet<>();
        for (Command leaf : cascaded()) {
            if (leaf instanceof CreateRelationshipCommand create
                    && (outgoing ? create.getSource() : create.getTarget()) == element) {
                joined.add(new PlacementAutoConnect.Joined(create.getRelationship(),
                        outgoing ? create.getTarget() : create.getSource()));
            } else if (leaf instanceof DeleteRelationshipCommand delete) {
                deleted.add(delete.getRelationship());
            } else if (leaf instanceof DeleteElementCommand delete) {
                deleted.add(delete.getElement());
            } else if (leaf instanceof ChangeRelationshipTypeCommand change) {
                retyped.addAll(change.getPreparedTypes().keySet());
            }
        }
        Set<String> listed = new HashSet<>();
        joined.removeIf(each -> deleted.contains(each.relationship()) || deleted.contains(each.otherEnd())
                || deleted.contains(element) || retyped.contains(each.relationship().getId())
                || !listed.add(each.relationship().getId()));
        return joined;
    }

    /**
     * The boxes, in absolute canvas coordinates, of the notes that will be on {@code view} when the
     * asking operation runs, at any depth: the notes {@link #objectsOn} answers, so a staged add puts
     * one on and a staged removal, clear or delete takes one off, in the order they run. Each box is
     * where the last staged {@code update-view-object} that sets bounds puts the note, else where the
     * note is now, or where its add placed it: a staged note's rectangle is resolved when it is
     * prepared, so it is the one it will have. A container's offset is read the same way. A note of
     * no width or height is not counted, as the assessor does not count it.
     */
    List<int[]> noteBoundsOn(IDiagramModel view) {
        Replayed replayed = replayed(view);
        Map<IDiagramModelObject, int[]> moved = new HashMap<>();
        for (Command leaf : leaves) {
            if (leaf instanceof UpdateViewObjectCommand update && !NamedAxes.NONE.equals(update.getAxes())
                    && update.getDiagramObject() != null) {
                // The prepare seeds the axes an update does not name from the queued bounds, so
                // the last update's rectangle is the whole box the object will have.
                moved.put(update.getDiagramObject(), new int[] { update.getNewX(), update.getNewY(),
                        update.getNewWidth(), update.getNewHeight() });
            }
        }
        List<int[]> boxes = new ArrayList<>();
        for (IDiagramModelObject object : replayed.present()) {
            int[] own = boundsOf(object, moved);
            if (!(object instanceof IDiagramModelNote) || own[2] <= 0 || own[3] <= 0) {
                continue;
            }
            int x = own[0];
            int y = own[1];
            Object up = parentOf(object, replayed.parents());
            for (int hop = 0; up instanceof IDiagramModelObject container && hop <= replayed.present().size(); hop++) {
                int[] offset = boundsOf(container, moved);
                x += offset[0];
                y += offset[1];
                up = parentOf(container, replayed.parents());
            }
            boxes.add(new int[] { x, y, own[2], own[3] });
        }
        return boxes;
    }

    private static int[] boundsOf(IDiagramModelObject object, Map<IDiagramModelObject, int[]> moved) {
        int[] staged = moved.get(object);
        return staged != null ? staged : new int[] { object.getBounds().getX(), object.getBounds().getY(),
                object.getBounds().getWidth(), object.getBounds().getHeight() };
    }

    /** The live container of {@code object}, or the one its staged add names when it is not attached yet. */
    private static Object parentOf(IDiagramModelObject object, Map<String, IDiagramModelContainer> parents) {
        return object.eContainer() != null ? object.eContainer() : parents.get(object.getId());
    }

    /**
     * Whether an object placed into {@code pendingParent} will sit inside {@code possibleAncestor}, as
     * {@link AutoConnectSkip#willNestInside} asks it, climbing the containers the staged work adds as
     * well as live containment: a container this batch places is not attached yet, so the live climb
     * stops at it.
     */
    boolean willNestInside(IDiagramModelContainer pendingParent, IDiagramModelObject possibleAncestor) {
        return pendingParent instanceof IDiagramModelObject box && within(box, possibleAncestor, stagedParents());
    }

    /**
     * The connections that will join the objects {@link #objectsOn} answers when the asking operation
     * runs: the live connections of those objects and the connections the staged work adds, each kept
     * only while every end that is an object is still among them, less every connection a staged
     * removal takes off and every connection of a relationship a staged delete removes.
     */
    List<IDiagramModelConnection> connectionsOn(IDiagramModel view) {
        Set<IDiagramModelObject> present = replayed(view).present();
        Set<IDiagramModelComponent> gone = new LinkedHashSet<>();
        Set<Object> deletedRelationships = new LinkedHashSet<>();
        for (Command leaf : leaves) {
            gone.addAll(removedBy(leaf, inert));
        }
        for (Command leaf : cascaded()) {
            if (leaf instanceof DeleteRelationshipCommand delete) {
                deletedRelationships.add(delete.getRelationship());
            }
        }
        Set<IDiagramModelConnection> connections = new LinkedHashSet<>();
        for (IDiagramModelObject object : present) {
            for (List<IDiagramModelConnection> attached : List.of(object.getSourceConnections(),
                    object.getTargetConnections())) {
                for (IDiagramModelConnection connection : attached) {
                    if (endsOn(connection.getSource(), connection.getTarget(), present)) {
                        connections.add(connection);
                    }
                }
            }
        }
        for (Command leaf : leaves) {
            if (leaf instanceof AddConnectionToViewCommand add && endsOn(add.getSource(), add.getTarget(), present)) {
                connections.add(add.getConnection());
            }
        }
        connections.removeAll(gone);
        connections.removeIf(connection -> connection instanceof IDiagramModelArchimateConnection archimate
                && deletedRelationships.contains(archimate.getArchimateRelationship()));
        return List.copyOf(connections);
    }

    /** Whether each end that is an object is present; an end that is a connection does not decide it. */
    private static boolean endsOn(Object source, Object target, Set<IDiagramModelObject> present) {
        return (!(source instanceof IDiagramModelObject s) || present.contains(s))
                && (!(target instanceof IDiagramModelObject t) || present.contains(t));
    }

    private record Replayed(Set<IDiagramModelObject> present, Map<String, IDiagramModelContainer> parents) {}

    /** The container each object the staged work adds is placed into, by the object's id. */
    Map<String, IDiagramModelContainer> stagedParents() {
        Map<String, IDiagramModelContainer> parents = new LinkedHashMap<>();
        for (Command leaf : leaves) {
            MutationContext.forEachCreated(leaf, created -> parents.putIfAbsent(created.object().getId(),
                    created.parent()));
        }
        return parents;
    }

    private Replayed replayed(IDiagramModel view) {
        Map<String, IDiagramModelContainer> parents = stagedParents();
        Set<IDiagramModelObject> present = new LinkedHashSet<>();
        collectLive(view, present);
        for (Command leaf : leaves) {
            replay(leaf, view, present, parents);
        }
        return new Replayed(present, parents);
    }

    private void replay(Command leaf, IDiagramModel view, Set<IDiagramModelObject> present,
            Map<String, IDiagramModelContainer> parents) {
        if (inert.contains(leaf)) {
            return;
        }
        if ((leaf instanceof ClearViewCommand clear && clear.getView() == view)
                || (leaf instanceof DeleteViewCommand delete && delete.getView() == view)) {
            present.clear();
        } else if (leaf instanceof DeleteFolderCommand delete) {
            for (Command nested : delete.getSubCommands()) {
                replay(nested, view, present, parents);
            }
        } else {
            MutationContext.forEachCreated(leaf, created -> {
                if (created.parent() == view || present.contains(created.parent())) {
                    present.add(created.object());
                }
            });
            List<IDiagramModelObject> removed = new ArrayList<>();
            for (IDiagramModelComponent component : removedBy(leaf, inert)) {
                if (component instanceof IDiagramModelObject object) {
                    removed.add(object);
                }
            }
            if (leaf instanceof DeleteElementCommand delete) {
                for (IDiagramModelObject object : present) {
                    if (object instanceof IDiagramModelArchimateObject archimate
                            && archimate.getArchimateElement() == delete.getElement()) {
                        removed.add(object);
                    }
                }
            }
            for (IDiagramModelObject object : removed) {
                present.removeIf(candidate -> within(candidate, object, parents));
            }
        }
    }

    /**
     * Whether {@code candidate} is {@code container} or nested in it, climbing live containment and,
     * for an object not attached yet, the container its add names. Bounded against a malformed queue.
     */
    private static boolean within(IDiagramModelObject candidate, IDiagramModelObject container,
            Map<String, IDiagramModelContainer> parents) {
        Object current = candidate;
        int queuedHops = 0;
        while (current instanceof IDiagramModelObject object) {
            if (object == container) {
                return true;
            }
            Object up = object.eContainer();
            if (up == null) {
                if (queuedHops++ > parents.size()) {
                    return false;
                }
                up = parents.get(object.getId());
            }
            current = up;
        }
        return false;
    }

    private static void collectLive(IDiagramModelContainer container, Set<IDiagramModelObject> into) {
        for (IDiagramModelObject child : container.getChildren()) {
            into.add(child);
            if (child instanceof IDiagramModelContainer nested) {
                collectLive(nested, into);
            }
        }
    }

    /**
     * The view components a queued removal takes off their diagram; empty for any other command, and
     * for a command in {@code inert} (see {@link #inert(List)}), which removes nothing when it runs.
     * There is no form without {@code inert}: a reading that ignores it reports a declining folder
     * delete's cascade as removed.
     */
    static List<? extends IDiagramModelComponent> removedBy(Command leaf, Set<Command> inert) {
        if (inert.contains(leaf)) {
            return List.of();
        }
        if (leaf instanceof RemoveFromViewCommand remove) {
            return List.of(remove.getDiagramObject());
        } else if (leaf instanceof RemoveViewObjectCommand remove) {
            return List.of(remove.getDiagramObject());
        } else if (leaf instanceof RemoveConnectionFromViewCommand remove) {
            return List.of(remove.getConnection());
        } else if (leaf instanceof DeleteElementCommand delete) {
            List<IDiagramModelComponent> removed = new ArrayList<>(delete.getViewObjectsRemoved());
            removed.addAll(delete.getViewConnectionsToDisconnect());
            return removed;
        } else if (leaf instanceof DeleteRelationshipCommand delete) {
            return delete.getViewConnectionsToDisconnect();
        } else if (leaf instanceof ClearViewCommand clear) {
            return clear.getView().getChildren();
        } else if (leaf instanceof DeleteViewCommand delete) {
            List<IDiagramModelComponent> removed = new ArrayList<>(delete.getView().getChildren());
            removed.addAll(delete.externalPlaceholders());
            return removed;
        } else if (leaf instanceof DeleteFolderCommand delete) {
            List<IDiagramModelComponent> removed = new ArrayList<>();
            for (Command nested : delete.getSubCommands()) {
                removed.addAll(removedBy(nested, inert));
            }
            return removed;
        }
        return List.of();
    }

    /**
     * The diagram {@code start} is on or will be on: its live diagram when it is attached, otherwise
     * the one its queued containment climbs to, through any container the batch also adds. A
     * connection the batch has not attached yet resolves to nothing. Bounded, like every
     * queued-containment climb, against a malformed queue.
     */
    static IDiagramModel destinedDiagram(Object start, Map<String, IDiagramModelContainer> queuedParents) {
        Object current = start;
        int bound = (queuedParents == null ? 0 : queuedParents.size()) + 1;
        for (int hop = 0; current != null && hop <= bound; hop++) {
            if (current instanceof IDiagramModel diagram) {
                return diagram;
            }
            if (!(current instanceof IDiagramModelComponent component)) {
                return null;
            }
            if (component.getDiagramModel() != null) {
                return component.getDiagramModel();
            }
            current = queuedParents == null || !(component instanceof IDiagramModelObject) ? null
                    : queuedParents.get(component.getId());
        }
        return null;
    }
}
