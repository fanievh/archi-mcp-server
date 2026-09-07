package net.vheerden.archi.mcp.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIdentifier;

import net.vheerden.archi.mcp.response.dto.BatchStatusDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;

/**
 * Per-session state for mutation operations.
 *
 * <p>Package-private — only used within the {@code model/} package by
 * {@link MutationDispatcher}. Tracks the current operational mode, queued
 * commands, batch timing, and approval state for a single session.</p>
 *
 * <p>The command queue is not write-only: {@link #queuedContainer(String)},
 * {@link #queuedViewObject(String)}, {@link #queuedParents()}, {@link #queuedBounds()},
 * {@link #queuedCreatedView(String)} and {@link #queuedCreatedElement(String)} read it
 * back so that something made earlier in an open batch can be named by a later operation — as a
 * nesting parent, as the target of an update, as a link in the containment chain a parent-fit
 * cascade climbs, or as the view an add is placed on and the element it places — and so that
 * geometry an earlier operation established is what a later one
 * measures against, even though neither the containment nor the geometry exists until commit.
 * Because the queue is cleared on both commit and rollback, it doubles as the lifetime record for
 * those not-yet-real objects and their not-yet-applied bounds.</p>
 *
 * <p>Pending <em>proposals</em> remain per-session here. The approval-mode
 * <em>bit</em>, however, is no longer per-session: it is a single
 * global, human-owned switch read through {@code ApprovalModeProvider} in
 * {@link MutationDispatcher}. This context therefore stores proposals but not the
 * mode flag.</p>
 *
 * <p>Thread safety: All public methods are synchronized. One session maps
 * to one Jetty thread at a time, but synchronization guards against any
 * concurrent access edge cases.</p>
 *
 * <p><strong>The absence of direct references from {@code ArchiModelAccessorImpl} is by design.</strong>
 * The model facade interacts with batch and proposal state <em>exclusively</em> through
 * {@link MutationDispatcher}, which owns one {@code MutationContext} per session (its
 * {@code batchSessions} map). A "find usages" of this class from the facade therefore
 * returns nothing — that is correct encapsulation of package-private per-session state,
 * not an indication that this class is unused. The live reference path is
 * facade&nbsp;→&nbsp;{@link MutationDispatcher}&nbsp;→&nbsp;{@code MutationContext}.</p>
 *
 * <p>This concrete per-session state holder is also distinct from any prospective
 * refactoring that would route the facade's mutation-preparation glue through a
 * context or parameter object: such a change would build upon this class, not
 * supersede it.</p>
 */
class MutationContext {

    static final int MAX_PENDING_PROPOSALS = 100;

    /**
     * Bound on the queued-containment climb. Real nesting is a handful of levels deep; the bound is
     * there so a malformed queue containing a containment cycle terminates with an answer instead
     * of spinning.
     */
    private static final int MAX_QUEUED_CONTAINMENT_HOPS = 32;

    private OperationalMode mode = OperationalMode.GUI_ATTACHED;
    private final List<Command> commandQueue = new ArrayList<>();
    private final List<String> descriptions = new ArrayList<>();
    private int sequenceCounter = 0;
    private Instant batchStarted;
    private String batchDescription;
    // Agent-supplied intent for this batch (optional). Recorded but never depended on by the
    // server; kept distinct from batchDescription (the undo-history label) — never merged.
    private String batchIntent;

    // ---- Approval state (mode bit globalised — proposals stay per-session) ----
    private final LinkedHashMap<String, PendingProposal> pendingProposals = new LinkedHashMap<>();
    private int proposalCounter = 0;

    // ---- Batch operations ----

    synchronized void beginBatch(String description) {
        beginBatch(description, null);
    }

    synchronized void beginBatch(String description, String intent) {
        if (mode == OperationalMode.BATCH) {
            throw new IllegalStateException("Already in batch mode");
        }
        mode = OperationalMode.BATCH;
        batchStarted = Instant.now();
        batchDescription = description;
        batchIntent = intent;
    }

    /** The agent-supplied batch intent, or null when none was given. */
    synchronized String getBatchIntent() {
        return batchIntent;
    }

    synchronized int queueCommand(Command command, String description) {
        if (mode != OperationalMode.BATCH) {
            throw new IllegalStateException("Not in batch mode");
        }
        commandQueue.add(command);
        descriptions.add(description);
        return ++sequenceCounter;
    }

    /**
     * Finds the container that a still-queued command will create, by its object id.
     *
     * <p>An {@code add-group-to-view} / {@code add-to-view} prepared inside a batch builds its
     * EMF object immediately but defers containment to {@code execute()}, which does not run until
     * commit. Between those two moments the object is <em>detached</em>: it has a real, agent-visible
     * id but is reachable from no view. A later operation in the same batch naming that id as its
     * parent therefore cannot find it by walking live containment, and fails. This lookup closes
     * that window by resolving the id against the commands still waiting to run.</p>
     *
     * <p>The queue <em>is</em> the record, so there is no separate registry to keep in step:
     * {@link #reset()} clears it on both commit and rollback, which is exactly the desired
     * lifetime (a rolled-back object must stop being addressable), and a prepare that throws never
     * reaches {@code queueCommand}, so a failed operation can never leave a phantom parent behind.</p>
     *
     * <p>Only the two container-creating command types match, mirroring the bulk path's
     * batch-created-parent maps so the two modes cannot drift on what may be a parent. A queued
     * note, image or view-reference id returns null here and falls through to the caller's ordinary
     * live lookup, which produces the usual not-found error.</p>
     *
     * <p>The walk recurses into compounds: an add is routinely wrapped (parent-fill recession,
     * auto-connect, icon-band parent resize), so a top-level-only scan would miss most real adds.</p>
     *
     * <p>Cost is a linear scan of the queue per lookup. At realistic batch sizes this is a few
     * hundred string comparisons and is not worth caching.</p>
     *
     * <p>The batch-mode check lives here rather than in the caller so that it and the queue scan
     * happen under one lock on one context. A caller that tested the mode separately would be
     * reading it from a different critical section, and a batch ending in between could make it
     * miss a container the first read had just proved was there.</p>
     *
     * @param id the object id to resolve
     * @return the queued container with that id, or null if no queued command creates one
     */
    synchronized IDiagramModelContainer queuedContainer(String id) {
        QueuedViewObject hit = queuedViewObject(id);
        if (hit == null) {
            return null;
        }
        // Nesting admits groups and elements only. The kinds are named explicitly rather than
        // tested with `instanceof IDiagramModelContainer` so that widening the queue walk to serve
        // other callers can never silently widen what may be a parent.
        if (hit.object() instanceof IDiagramModelGroup group) {
            return group;
        }
        if (hit.object() instanceof IDiagramModelArchimateObject element) {
            return element;
        }
        return null;
    }

    /**
     * Resolves a view-object id against the objects this session's open batch has queued but not
     * yet attached, returning the object together with the container it is destined for.
     *
     * <p>Unlike {@link #queuedContainer(String)} this admits <em>every</em> kind an
     * {@code add-*-to-view} can create — element, group, note, image and view-reference — because
     * an operation that targets an object by id, rather than nesting under it, is not restricted to
     * containers.</p>
     *
     * <p>Returns null outside batch mode, for an unknown id, and for a null id, so every caller
     * that is not inside a batch keeps its existing behaviour by construction. See
     * {@link #queuedContainer(String)} for why the mode check lives here and why the walk recurses
     * into compounds.</p>
     *
     * @param id the object id to resolve
     * @return the queued object and its destined parent, or null if no queued command creates it
     */
    synchronized QueuedViewObject queuedViewObject(String id) {
        if (id == null || mode != OperationalMode.BATCH) {
            return null;
        }
        for (Command queued : commandQueue) {
            QueuedViewObject hit = objectCreatedBy(queued, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * The {@code object id → destined parent} containment this session's queued commands imply, or
     * null outside batch mode.
     *
     * <p>Where {@link #queuedViewObject(String)} answers for one id, this answers for all of them at
     * once, which is what an <em>ancestor walk</em> needs: growing a queued group to fit its child
     * immediately raises the same question about that group's own container, and again above it. One
     * scan of the queue serves the whole walk, so a chain N deep costs one traversal rather than N.
     *
     * <p>Returns the containment as it is <em>declared</em> by the pending commands — the same
     * record {@link #queuedContainer(String)} and {@link #queuedViewObject(String)} read, so the
     * three cannot disagree about who a queued object belongs to. Admitting every add kind here is
     * deliberate and safe: this map answers "what will contain this object", never "may this object
     * be a parent", and the caller still tests the ancestor's own kind before climbing into it.
     *
     * <p>Mode check inside, under this lock, for the same reason as its siblings.
     *
     * @return the queued containment, empty when nothing is queued, or null outside batch mode
     */
    synchronized Map<String, IDiagramModelContainer> queuedParents() {
        if (mode != OperationalMode.BATCH) {
            return null;
        }
        Map<String, IDiagramModelContainer> parents = new LinkedHashMap<>();
        for (Command queued : commandQueue) {
            // First queued add of an id wins, matching the single-id lookup's first-match rule.
            forEachCreated(queued, created -> parents.putIfAbsent(created.object().getId(), created.parent()));
        }
        return parents;
    }

    /**
     * The {@code object id → effective bounds} {@code [x, y, width, height]} this session's queued
     * commands imply, or null outside batch mode.
     *
     * <p>An operation prepared inside an open batch measures against a model in which no earlier
     * command of that batch has executed, so {@code getBounds()} still returns the pre-batch value
     * for anything the batch has already re-sized. Two operations that grow a shared container each
     * compute their growth from that same stale number, and the later one overwrites the earlier —
     * silently, since both report success. This is what a prepare measures against instead.</p>
     *
     * <p>Derived from the queue rather than recorded by the prepare that computed it, for the same
     * reason as {@link #queuedParents()}. <em>Every</em> writer of a view object's bounds — the two
     * update prepares, the parent-fit cascade's own group resizes, the spacing tools, the routing
     * overflow passes, the icon-band reservation — queues an {@link UpdateViewObjectCommand}, so all
     * of them are visible here; a record kept by one prepare path would see only its own writes, and
     * a bulk pass running inside the batch discards its thread-local record when the pass ends. The
     * lifetime comes free too: {@link #reset()} clears the queue on commit and on rollback, and a
     * prepare that throws never reaches {@link #queueCommand}, so nothing can leave a phantom floor
     * behind — including a stored proposal, which is not in the queue until it is approved.</p>
     *
     * <p><strong>The last write wins here, the opposite of {@link #queuedParents()}'s first-match
     * rule.</strong> An id is created once, so its parent cannot change; its bounds can be rewritten
     * any number of times, and the write that survives is the one that executes last — which, since
     * {@link #buildCompoundCommand()} preserves queue order, is the last one this walk sees.</p>
     *
     * <p>An object the batch created but never re-sized is deliberately absent: an add command
     * configures its object's bounds at construction, so the caller's ordinary {@code getBounds()}
     * read already holds the value the add will attach.</p>
     *
     * <p>The bounds are those the queued command was <em>built</em> with. A command that re-resolves
     * its own position at execute time — an anchored object tracking a target that moves later in
     * the same batch — can still land elsewhere; that projection is tracked separately.</p>
     *
     * <p>Mode check inside, under this lock, for the same reason as its siblings.</p>
     *
     * @return the queued geometry, empty when nothing is queued, or null outside batch mode
     */
    /**
     * The {@code connection id → label visibility} this session's queued commands imply, or null
     * outside batch mode.
     *
     * <p>A routing pass inside a batch reads a model where none of the batch's own commands have
     * run, so a connection whose label an earlier operation already hid still reports itself as
     * visible. Without this the label policy would claim that hide as its own and report a false
     * cause for it. Last queued write wins, which is what will actually be true at commit.</p>
     */
    synchronized Map<String, Boolean> queuedLabelVisibility() {
        if (mode != OperationalMode.BATCH) {
            return null;
        }
        Map<String, Boolean> visibility = new LinkedHashMap<>();
        for (Command queued : commandQueue) {
            forEachCommand(queued, leaf -> {
                if (leaf instanceof UpdateViewConnectionCommand update) {
                    Boolean nameVisible = update.getNewNameVisible();
                    if (nameVisible != null && update.getConnection() != null) {
                        visibility.put(update.getConnection().getId(), nameVisible);
                    }
                }
            });
        }
        return visibility;
    }

    synchronized Map<String, int[]> queuedBounds() {
        if (mode != OperationalMode.BATCH) {
            return null;
        }
        Map<String, int[]> bounds = new LinkedHashMap<>();
        for (Command queued : commandQueue) {
            forEachCommand(queued, leaf -> {
                if (leaf instanceof UpdateViewObjectCommand update) {
                    IDiagramModelObject target = update.getDiagramObject();
                    if (target != null) {
                        bounds.put(target.getId(), new int[] { update.getNewX(), update.getNewY(),
                                update.getNewWidth(), update.getNewHeight() });
                    }
                }
            });
        }
        return bounds;
    }

    /**
     * The {@code object id → anchor} {@code {target, edge, dx, dy}} this session's queued commands
     * declare, or null outside batch mode.
     *
     * <p>{@link #queuedBounds()} answers what the batch has decided an object's rectangle is. This
     * answers the prior question for the objects whose position is not a rectangle it chose but a
     * relationship to another object. The distinction matters because an anchor is stored as feature
     * entries on the anchored object, written by that object's own command when it executes: while
     * the batch is open no anchor it declares is readable from the model. The cascade that moves
     * every object anchored to a target whose bounds are changing reads exactly those features, so
     * inside a batch it sees only anchors that predate the batch and silently leaves the batch's own
     * anchored objects behind.</p>
     *
     * <p>Derived from the queue, not recorded by a prepare, for the same reason as its siblings — and
     * with the same last-write-wins rule as {@link #queuedBounds()}: an anchor can be re-declared or
     * cleared any number of times, and the declaration that survives is the one that executes last.
     * A command that clears an anchor leaves a null target, which is recorded as such so a later
     * reader sees the clear rather than an earlier stale anchor.</p>
     *
     * <p>Mode check inside, under this lock, for the same reason as its siblings.</p>
     *
     * @return the queued anchors, empty when nothing is queued, or null outside batch mode
     */
    synchronized Map<String, String[]> queuedAnchors() {
        if (mode != OperationalMode.BATCH) {
            return null;
        }
        Map<String, String[]> anchors = new LinkedHashMap<>();
        for (Command queued : commandQueue) {
            forEachCommand(queued, leaf -> {
                if (leaf instanceof UpdateViewObjectCommand update && update.hasAnchorChange()) {
                    IDiagramModelObject target = update.getDiagramObject();
                    if (target != null) {
                        anchors.put(target.getId(), new String[] { update.getNewAnchorTarget(),
                                update.getNewAnchorEdge(), update.getNewAnchorDx(),
                                update.getNewAnchorDy() });
                    }
                }
            });
        }
        return anchors;
    }

    /**
     * Resolves a view id against the views this session's open batch has queued but not yet created.
     *
     * <p>Its siblings above answer for <em>view objects</em> — the things an {@code add-*-to-view}
     * builds. This answers for the diagram itself. {@code create-view} constructs its
     * {@code IArchimateDiagramModel} immediately but defers the folder attachment to
     * {@code execute()}, so between prepare and commit the view has a real, agent-visible id while
     * hanging off no folder. A later {@code add-*-to-view} in the same batch resolves its target by
     * walking committed containment, finds nothing, and throws — even though the id it was handed is
     * the one this batch is about to create.</p>
     *
     * <p>Deliberately <em>not</em> expressed as a {@link QueuedViewObject}: that record pairs an
     * object with the container it is destined for, and a created view is destined for a folder,
     * not a container. A typed lookup states only what is actually known.</p>
     *
     * @param id the view id to resolve
     * @return the queued view with that id, or null if no queued command creates one
     */
    synchronized IArchimateDiagramModel queuedCreatedView(String id) {
        return queuedCreated(id,
                leaf -> leaf instanceof CreateViewCommand create ? create.getView() : null);
    }

    /**
     * Resolves an element id against the elements this session's open batch has queued but not yet
     * created.
     *
     * <p>The model-object sibling of {@link #queuedCreatedView(String)}, and the other half of
     * building a view from scratch inside one batch: {@code create-element} defers its folder
     * attachment the same way, so an {@code add-to-view} naming a just-created element could not
     * find it either. Kept a separate typed lookup rather than one untyped id resolver so that
     * naming a queued view where an element belongs — or the reverse — still takes the ordinary
     * not-found path instead of resolving to an object of the wrong kind.</p>
     *
     * @param id the element id to resolve
     * @return the queued element with that id, or null if no queued command creates one
     */
    synchronized IArchimateElement queuedCreatedElement(String id) {
        return queuedCreated(id,
                leaf -> leaf instanceof CreateElementCommand create ? create.getElement() : null);
    }

    /**
     * Resolves a relationship id against the relationships this session's open batch has queued but
     * not yet created.
     *
     * <p>The third of the create-then-use family, and the one that completes it for connections:
     * {@code create-relationship} defers both the {@code connect()} and the folder attachment to
     * {@code execute()}, so between prepare and commit the relationship has a real, agent-visible
     * id while hanging off nothing. An {@code add-connection-to-view} naming it in the same batch
     * walked committed containment and reported it missing — which meant a batch could create a
     * relationship and could not draw it.</p>
     *
     * <p>A specialized relationship queues its create inside a compound, so this depends on the
     * recursion in {@link #forEachCommand(Command, Consumer)} exactly as its siblings do.</p>
     *
     * <p>Kept a separate typed lookup for the same reason as its siblings: naming a queued element
     * or view object where a relationship belongs still takes the ordinary not-found path rather
     * than resolving to an object of the wrong kind.</p>
     *
     * @param id the relationship id to resolve
     * @return the queued relationship with that id, or null if no queued command creates one
     */
    synchronized IArchimateRelationship queuedCreatedRelationship(String id) {
        return queuedCreated(id,
                leaf -> leaf instanceof CreateRelationshipCommand create
                        ? create.getRelationship() : null);
    }

    /**
     * Resolves a view-object id against the queued objects eligible to be a connection endpoint
     * <em>on the given view</em>, or null when nothing queued qualifies.
     *
     * <p>Two conditions, and both matter. The object must be an {@link IDiagramModelArchimateObject}
     * — a connection endpoint is validated against the element behind it, and a group or note has
     * none. And it must be destined for {@code view}: the live lookup this stands in for searches
     * one view's containment, so an endpoint has always had to belong to the view the connection is
     * being drawn on. A queued object carries its destination in the queue rather than in
     * {@code eContainer()}, so answering without checking it would let a batch join two objects
     * landing on different diagrams — a connection the live path cannot express and Archi has no
     * way to draw.</p>
     *
     * <p>The destination is reached by climbing the queued containment, because the object may be
     * nested in a group this same batch queued, whose own container is likewise not attached yet.
     * The climb prefers a queued parent and falls back to a live one, so a mixed chain — a queued
     * object inside a committed group — resolves too. It is bounded: a malformed queue that
     * contained a containment cycle would otherwise spin here rather than answer.</p>
     *
     * @param id   the requested object id, may be null
     * @param view the view the connection is being drawn on
     * @return the queued object when it can be an endpoint on {@code view}, else null
     */
    synchronized IDiagramModelArchimateObject queuedConnectionEnd(String id,
            IArchimateDiagramModel view) {
        QueuedViewObject queued = queuedViewObject(id);
        if (view == null || queued == null
                || !(queued.object() instanceof IDiagramModelArchimateObject archObj)) {
            return null;
        }
        Map<String, IDiagramModelContainer> parents = queuedParents();
        IDiagramModelContainer container = queued.parent();
        for (int hop = 0; container != null && hop < MAX_QUEUED_CONTAINMENT_HOPS; hop++) {
            if (container == view) {
                return archObj;
            }
            if (!(container instanceof IDiagramModelObject nested)) {
                return null; // reached a diagram, and it is not the one being drawn on
            }
            IDiagramModelContainer queuedParent =
                    (parents != null) ? parents.get(nested.getId()) : null;
            container = (queuedParent != null) ? queuedParent
                    : (nested.eContainer() instanceof IDiagramModelContainer live ? live : null);
        }
        return null;
    }

    /**
     * Resolves an id against anything this session's open batch has queued, whatever kind it is.
     *
     * <p>The deliberate exception to the typed-lookup rule its siblings follow. They stay separate
     * so that naming a queued view where an element belongs takes the ordinary not-found path — the
     * caller knows which kind it needs, and resolving the wrong one is worse than failing. This
     * caller does not: the staleness snapshot is asking "does this id name something this request
     * has made", to fingerprint it and compare later, and it treats every kind identically.</p>
     *
     * <p>Enumerating the siblings rather than adding a sixth walk keeps every rule they encode —
     * mode, lifetime, null-id, first-match-wins, compound recursion — in one place still.</p>
     *
     * @param id the id to resolve
     * @return the queued object with that id, of whatever kind, or null when nothing queues it
     */
    synchronized EObject queuedAny(String id) {
        QueuedViewObject viewObject = queuedViewObject(id);
        if (viewObject != null) {
            return viewObject.object();
        }
        IArchimateDiagramModel view = queuedCreatedView(id);
        if (view != null) {
            return view;
        }
        IArchimateElement element = queuedCreatedElement(id);
        if (element != null) {
            return element;
        }
        IArchimateRelationship relationship = queuedCreatedRelationship(id);
        if (relationship != null) {
            return relationship;
        }
        return queuedViewConnection(id);
    }

    /**
     * The element a queued relationship's create will connect at one end, or null when this session
     * has no queued create for that id.
     *
     * <p>Separate from {@link #queuedCreatedRelationship(String)} because the relationship object it
     * returns cannot answer this: {@code connect()} runs at execute, so between prepare and commit
     * the relationship's own source and target are both null. Anything validating a queued
     * relationship against other objects has to read the ends off the command that will connect it,
     * and reading them anywhere else is how a caller ends up dereferencing null.</p>
     *
     * <p>First match wins and the mode check is inside, both for the same reasons as
     * {@link #queuedCreated(String, Function)}, whose walk this deliberately mirrors rather than
     * reuses — that helper matches on the <em>created object's</em> id, and the object wanted here
     * is an endpoint whose id is not the one being looked up.</p>
     *
     * @param relationshipId the queued relationship's id
     * @param wantSource     true for the source end, false for the target end
     * @return the element that end will connect to, or null when nothing queued creates that id
     */
    synchronized IArchimateElement queuedRelationshipEnd(String relationshipId, boolean wantSource) {
        if (relationshipId == null || mode != OperationalMode.BATCH) {
            return null;
        }
        List<IArchimateElement> hit = new ArrayList<>(1);
        for (Command queued : commandQueue) {
            forEachCommand(queued, leaf -> {
                if (hit.isEmpty() && leaf instanceof CreateRelationshipCommand create
                        && relationshipId.equals(create.getRelationship().getId())) {
                    hit.add(wantSource ? create.getSource() : create.getTarget());
                }
            });
            if (!hit.isEmpty()) {
                return hit.get(0);
            }
        }
        return null;
    }

    /**
     * Resolves a connection id against the connections this session's open batch has queued but not
     * yet connected.
     *
     * <p>The connection analogue of the two siblings above, and the last id an
     * {@code add-*-to-view} hands back that a later operation in the same batch could not name.
     * {@code add-connection-to-view} builds its {@code IDiagramModelArchimateConnection} and
     * returns its id at prepare time, but the connection joins its endpoints only when the command
     * executes — at commit, inside a batch — so until then it hangs off no diagram and a later
     * {@code update-view-connection} resolving by id finds nothing.</p>
     *
     * <p>Reaching it depends on the recursion in {@link #forEachCommand(Command, Consumer)} more
     * heavily than its siblings do: {@code add-connection-to-view} never queues a bare
     * {@link AddConnectionToViewCommand}, it queues one wrapped in an endpoint guard per endpoint,
     * so the command answered for here always sits two levels down.</p>
     *
     * <p>Kept a separate typed lookup for the same reason as its siblings: naming a queued view
     * object where a connection belongs still takes the ordinary not-found path rather than
     * resolving to an object of the wrong kind.</p>
     *
     * @param id the connection id to resolve
     * @return the queued connection with that id, or null if no queued command creates one
     */
    synchronized IDiagramModelArchimateConnection queuedViewConnection(String id) {
        return queuedCreated(id,
                leaf -> leaf instanceof AddConnectionToViewCommand add ? add.getConnection() : null);
    }

    /**
     * Resolves an id against the <em>model</em> objects this session's queued commands will create,
     * given the accessor that recognises the creating command kind.
     *
     * <p>The single place that knows how a queued create is matched by id, so the view lookup and
     * any sibling that follows cannot drift on lifetime, mode or nesting. It reads the queue through
     * {@link #forEachCommand(Command, Consumer)} like every other read-back, which is what makes it
     * see a create nested inside a compound — {@code clone-view} queues its
     * {@link CreateViewCommand} that way, and a top-level-only scan would miss it.</p>
     *
     * <p>First match wins, matching {@link #queuedViewObject(String)}: an id is created once, so a
     * second hit would mean a malformed queue rather than a later value to prefer.</p>
     *
     * <p>Returns null outside batch mode, for an unknown id and for a null id, so every caller that
     * is not inside a batch keeps its existing behaviour by construction. See
     * {@link #queuedContainer(String)} for why the mode check lives here rather than in the
     * caller.</p>
     */
    private <T extends IIdentifier> T queuedCreated(String id, Function<Command, T> createdBy) {
        if (id == null || mode != OperationalMode.BATCH) {
            return null;
        }
        List<T> hit = new ArrayList<>(1);
        for (Command queued : commandQueue) {
            forEachCommand(queued, leaf -> {
                T created = hit.isEmpty() ? createdBy.apply(leaf) : null;
                if (created != null && id.equals(created.getId())) {
                    hit.add(created);
                }
            });
            if (!hit.isEmpty()) {
                return hit.get(0);
            }
        }
        return null;
    }

    /**
     * Matches a single queued command (or, recursively, any command inside a compound) against
     * the wanted id, returning the object it will create and the container it will attach it to.
     */
    private static QueuedViewObject objectCreatedBy(Command command, String id) {
        QueuedViewObject[] hit = new QueuedViewObject[1];
        forEachCreated(command, created -> {
            if (hit[0] == null && id.equals(created.object().getId())) {
                hit[0] = created;
            }
        });
        return hit[0];
    }

    /**
     * Visits every view object the given queued command will create.
     *
     * <p>The single place that knows which command kinds create a view object and where each one
     * declares its parent. Both the single-id lookup and the whole-queue containment map read the
     * queue through it, so neither can drift from the other on what the batch has actually queued.</p>
     */
    private static void forEachCreated(Command command, Consumer<QueuedViewObject> sink) {
        forEachCommand(command, leaf -> {
            if (leaf instanceof AddGroupToViewCommand add) {
                sink.accept(new QueuedViewObject(add.getGroup(), add.getParent()));
            } else if (leaf instanceof AddToViewCommand add) {
                sink.accept(new QueuedViewObject(add.getDiagramObject(), add.getView()));
            } else if (leaf instanceof AddNoteToViewCommand add) {
                sink.accept(new QueuedViewObject(add.getNote(), add.getParent()));
            } else if (leaf instanceof AddImageToViewCommand add) {
                sink.accept(new QueuedViewObject(add.getImage(), add.getParent()));
            } else if (leaf instanceof AddViewReferenceToViewCommand add) {
                sink.accept(new QueuedViewObject(add.getViewReference(), add.getParent()));
            }
        });
    }

    /**
     * Visits every non-compound command the given queue entry will run, recursing into compounds —
     * one queued operation is routinely a compound (parent-fill recession, auto-connect, an
     * icon-band reservation, the group resizes a parent-fit cascade provoked), so a top-level-only
     * scan would miss most real work.
     *
     * <p>The single place that knows how a queued operation nests. Every read-back of the queue
     * runs through it, so the containment map and the geometry map cannot disagree with each other,
     * or with the single-id lookup, about which commands the batch actually holds.</p>
     */
    private static void forEachCommand(Command command, Consumer<Command> sink) {
        if (command instanceof CompoundCommand compound) {
            // GEF's CompoundCommand.getCommands() is a raw List on this target platform.
            for (Object child : compound.getCommands()) {
                forEachCommand((Command) child, sink);
            }
        } else {
            sink.accept(command);
        }
    }

    synchronized NonNotifyingCompoundCommand buildCompoundCommand() {
        String label = batchDescription != null
                ? batchDescription
                : "Batch mutation (" + commandQueue.size() + " operations)";
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(label);
        commandQueue.forEach(compound::add);
        return compound;
    }

    /**
     * Builds the commit summary. Must be called <strong>after</strong> the compound has been
     * dispatched: a {@link CommitSkippableCommand} only knows whether it declined once it has
     * been given the chance to run, so collecting skips any earlier would always report none.
     */
    synchronized BatchSummaryDto buildCommitSummary() {
        Duration elapsed = Duration.between(batchStarted, Instant.now());
        String duration = formatDuration(elapsed);
        return new BatchSummaryDto(
                commandQueue.size(),
                List.copyOf(descriptions),
                duration,
                false,
                collectSkippedOperations());
    }

    /**
     * Pairs each declined command with the description the caller gave it, so the agent is
     * told which of its own operations did not happen rather than just that something did not.
     *
     * <p>Walks into compounds via {@link CommitSkippableCommand#collectSkipReasons}: a single
     * queued operation — a force delete-specialization, say — can be a compound whose declining
     * command is a leaf, and a top-level-only scan would miss it and report a silent success. A
     * queue entry that declines in several places (its clear and its profile-removal both refusing)
     * is reported as one line so the count still matches the operations the caller queued.</p>
     *
     * @return the skipped operations, or null when every command ran (omitted from JSON)
     */
    private List<String> collectSkippedOperations() {
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < commandQueue.size(); i++) {
            List<String> reasons = CommitSkippableCommand.collectSkipReasons(commandQueue.get(i));
            if (!reasons.isEmpty()) {
                String description = i < descriptions.size() ? descriptions.get(i) : "operation " + (i + 1);
                skipped.add(description + " — " + String.join("; ", reasons));
            }
        }
        return skipped.isEmpty() ? null : List.copyOf(skipped);
    }

    synchronized BatchSummaryDto buildRollbackSummary() {
        Duration elapsed = Duration.between(batchStarted, Instant.now());
        String duration = formatDuration(elapsed);
        return new BatchSummaryDto(
                commandQueue.size(),
                List.copyOf(descriptions),
                duration,
                true,
                null);
    }

    /**
     * Resets batch state. Approval state is NOT cleared — pending
     * proposals survive batch commit/rollback.
     */
    synchronized void reset() {
        mode = OperationalMode.GUI_ATTACHED;
        commandQueue.clear();
        descriptions.clear();
        sequenceCounter = 0;
        batchStarted = null;
        batchDescription = null;
        batchIntent = null;
    }

    synchronized OperationalMode getMode() {
        return mode;
    }

    synchronized int getQueuedCount() {
        return commandQueue.size();
    }

    synchronized BatchStatusDto getBatchStatus() {
        // Approval bit is global — MutationDispatcher.getBatchStatus overlays it.
        Integer pendingCount = pendingProposals.isEmpty() ? null : pendingProposals.size();

        if (mode == OperationalMode.GUI_ATTACHED) {
            return new BatchStatusDto(
                    mode.name(), null, null, null,
                    null, pendingCount);
        }
        return new BatchStatusDto(
                mode.name(),
                commandQueue.size(),
                List.copyOf(descriptions),
                batchStarted != null ? batchStarted.toString() : null,
                null, pendingCount);
    }

    // ---- Approval operations (proposal storage only — mode bit globalised) ----

    /**
     * Stores a proposal and assigns it an ID.
     *
     * @param proposal the proposal (with null proposalId — will be replaced)
     * @return the assigned proposal ID
     * @throws IllegalStateException if max pending proposals reached
     */
    synchronized String storeProposal(PendingProposal proposal) {
        if (pendingProposals.size() >= MAX_PENDING_PROPOSALS) {
            throw new IllegalStateException(
                    "Maximum pending proposals reached (" + MAX_PENDING_PROPOSALS
                    + "). Approve or reject existing proposals before creating new ones.");
        }
        String id = "p-" + (++proposalCounter);
        // Arity-proof copy that stamps the id (record-arity discipline) — preserves the deferred rebuild
        // handle + staleness capture + all card fields without re-listing them here.
        PendingProposal withId = proposal.withProposalId(id);
        pendingProposals.put(id, withId);
        return id;
    }

    /**
     * Sweeps proposals older than {@code ttl} (proposal expiry). Relieves the
     * {@link #MAX_PENDING_PROPOSALS hard cap} so abandoned proposals do not block new ones; non-destructive
     * to the model (a proposal is an un-applied request). The proposal currently being approved is never
     * passed here — {@link MutationDispatcher#approveProposal} removes it from this map <em>before</em>
     * rebuilding/dispatching, so it cannot be swept mid-approve.
     *
     * @param now the current instant (injected for testability)
     * @param ttl the time-to-live; proposals with {@code createdAt} older than this are removed
     * @return the proposal ids that were swept (empty if none)
     */
    synchronized List<String> sweepExpired(Instant now, Duration ttl) {
        List<String> swept = new ArrayList<>();
        var it = pendingProposals.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (isExpired(entry.getValue().createdAt(), now, ttl)) {
                swept.add(entry.getKey());
                it.remove();
            }
        }
        return swept;
    }

    /**
     * Pure TTL-expiry predicate. A proposal is expired when its age
     * ({@code now - createdAt}) strictly exceeds {@code ttl}. Null {@code createdAt} is treated as
     * not-expired (defensive — a proposal always has a timestamp).
     */
    static boolean isExpired(Instant createdAt, Instant now, Duration ttl) {
        if (createdAt == null || now == null || ttl == null) {
            return false;
        }
        return Duration.between(createdAt, now).compareTo(ttl) > 0;
    }

    synchronized PendingProposal getProposal(String proposalId) {
        return pendingProposals.get(proposalId);
    }

    synchronized PendingProposal removeProposal(String proposalId) {
        return pendingProposals.remove(proposalId);
    }

    synchronized List<PendingProposal> getPendingProposals() {
        return List.copyOf(pendingProposals.values());
    }

    synchronized int getPendingCount() {
        return pendingProposals.size();
    }

    synchronized void clearProposals() {
        pendingProposals.clear();
    }

    private String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        double seconds = millis / 1000.0;
        return String.format("%.1fs", seconds);
    }
}
