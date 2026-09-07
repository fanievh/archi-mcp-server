package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

/**
 * GEF Command that deletes an ArchiMate view (diagram) from the model.
 *
 * <p>On execute, captures the view's top-level children and the connections
 * attached to them, disconnects those connections, clears the children, and
 * removes the view from its parent folder. The underlying model elements
 * and relationships are NOT deleted.</p>
 *
 * <p>The connection capture is not recursive, unlike
 * {@link ClearViewCommand}: a connection whose both endpoints are nested inside
 * a group is never explicitly {@code disconnect()}ed. This is safe here — and
 * only here — because the entire view subtree is removed from the model in this
 * same command ({@code view.getChildren().clear()}), so a nested connection,
 * being EMF-contained by its source object, leaves the model together with the
 * two nested objects it joins rather than being orphaned. {@code ClearViewCommand}
 * must recurse precisely because it leaves the view in place, where an
 * un-disconnected nested connection would survive as a dangling reference. A
 * save/reload round-trip over a nested-to-nested connection confirms this delete
 * path leaves no dangling cross-reference.</p>
 *
 * <p><strong>Cascade:</strong> also captures every
 * {@link IDiagramModelReference} placeholder elsewhere in the model whose
 * {@code getReferencedModel()} is this view, and removes them on execute /
 * restores them on undo. Mirrors Archi GUI's
 * {@code com.archimatetool.editor/.../DeleteCommandHandler
 * .getDiagramModelReferencesToDelete()}. Without this cascade, the EMF
 * cross-reference becomes a dangling {@code model="..."} attribute on
 * {@code .archimate} save and Archi cannot reopen the file
 * ({@code "Unresolved reference ..."}). Placeholders contained inside the
 * view being deleted are NOT cascaded — they disappear with the view via
 * the existing {@code view.getChildren().clear()} step. Any diagram
 * connections attached to a cascaded placeholder are {@code disconnect()}ed
 * before the placeholder is removed (and {@code reconnect()}ed on undo):
 * unlike the deleted view's own subtree, these placeholders sit in views that
 * SURVIVE the delete, so a connection touching one — being EMF-contained by
 * its source object — would otherwise be left dangling in a surviving view.</p>
 *
 * <p>State is captured once on first {@code execute()} and reused on
 * subsequent {@code redo()} calls.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class DeleteViewCommand extends Command implements NameCapturingCommand {

    private final IDiagramModel view;
    private final IFolder viewFolder;
    private final int viewIndex;

    // Surviving-successor anchor for the view among its sibling views, captured at
    // construction (prepare time). undo() re-inserts the view directly before this
    // sibling rather than at the stale absolute viewIndex, so the folder order
    // survives a compound that deletes several sibling views and undoes them in
    // reverse. The cascaded external-placeholder indices below need no such anchor:
    // they are captured at execute time relative to the collapsing list, so reverse
    // compound undo unwinds them self-consistently.
    private final EObject viewSuccessor;

    private String nameAtExecute;

    // Captured on first execute for undo/redo
    private List<IDiagramModelObject> capturedChildren;
    private List<IDiagramModelConnection> capturedConnections;
    private List<Integer> capturedIndices;
    private List<CascadedRef> capturedCascadedRefs;
    // Connections attached to the external cascaded placeholders. Those placeholders live
    // in OTHER views that SURVIVE this delete, so a connection touching one — being
    // EMF-contained by its source object — would otherwise be left dangling in the
    // surviving view (a broken cross-reference on save/reload). Disconnected on execute,
    // reconnected on undo, mirroring how DeleteElementCommand handles the connections on
    // its cascaded view objects.
    private List<IDiagramModelConnection> capturedCascadedConnections;

    /** Captured external placeholder for cascade undo. */
    private record CascadedRef(IDiagramModelContainer parent, int index, IDiagramModelReference ref) { }

    /**
     * Creates a command to delete a view from the model.
     *
     * @param view       the view to delete
     * @param viewFolder the folder containing the view
     * @param viewIndex  the view's index in the folder
     */
    public DeleteViewCommand(IDiagramModel view, IFolder viewFolder, int viewIndex) {
        this.view = Objects.requireNonNull(view, "view must not be null");
        this.viewFolder = Objects.requireNonNull(viewFolder, "viewFolder must not be null");
        this.viewIndex = viewIndex;
        this.viewSuccessor = SiblingUndoAnchor.successorOf(viewFolder.getElements(), view);
        setLabel("Delete view: " + view.getName());
    }

    @Override
    public void execute() {
        // Cleared here and set only if the removal below actually removes something. Outside the
        // first-execution guard deliberately: the children are captured once because re-capturing
        // them after an undo would read a restored-but-different list, whereas the name is a fact
        // about this execution and a redo is a new one.
        nameAtExecute = null;

        // Capture state only on first execution; reuse on redo
        if (capturedChildren == null) {
            capturedChildren = new ArrayList<>(view.getChildren());
            capturedIndices = new ArrayList<>();
            for (int i = 0; i < capturedChildren.size(); i++) {
                capturedIndices.add(i);
            }

            Set<IDiagramModelConnection> uniqueConnections = new LinkedHashSet<>();
            for (IDiagramModelObject child : capturedChildren) {
                for (Object conn : child.getSourceConnections()) {
                    if (conn instanceof IDiagramModelConnection dc) {
                        uniqueConnections.add(dc);
                    }
                }
                for (Object conn : child.getTargetConnections()) {
                    if (conn instanceof IDiagramModelConnection dc) {
                        uniqueConnections.add(dc);
                    }
                }
            }
            capturedConnections = new ArrayList<>(uniqueConnections);

            capturedCascadedRefs = captureExternalPlaceholders();
            capturedCascadedConnections = collectCascadedConnections(capturedCascadedRefs);
        }

        // 1. Disconnect all connections
        for (IDiagramModelConnection conn : capturedConnections) {
            conn.disconnect();
        }

        // 2. Clear all children
        view.getChildren().clear();

        // 3. Remove view from folder, recording what it was called as it goes. Kept only when the
        // removal removed something — see DeleteElementCommand for why a redundant delete must
        // not report the name its detached subject carries by then.
        String subjectName = view.getName();
        if (viewFolder.getElements().remove(view)) {
            nameAtExecute = subjectName;
        }

        // 4. Cascade: remove every external IDiagramModelReference placeholder
        //    that pointed at this view, otherwise serialization writes a
        //    dangling model="<deleted-id>" attribute and reload fails. Disconnect
        //    any connections attached to those placeholders FIRST — the placeholders
        //    live in surviving views, so a connection touching one would otherwise be
        //    left as a dangling cross-reference in a view that stays.
        for (IDiagramModelConnection conn : capturedCascadedConnections) {
            conn.disconnect();
        }
        for (CascadedRef cascaded : capturedCascadedRefs) {
            cascaded.parent().getChildren().remove(cascaded.ref());
        }
    }

    // Default redo() calls execute() — safe because the lazy capture guard
    // (capturedChildren == null) ensures state is only captured once.

    @Override
    public String getNameAtExecute() {
        return nameAtExecute;
    }

    @Override
    public void undo() {
        // The re-insertions below (cascaded placeholders, own children) are raw adds without an
        // already-present guard, unlike the view→folder restore via SiblingUndoAnchor. They are
        // safe against a redundant/overlapping batch ONLY because capturedChildren /
        // capturedCascadedRefs are captured lazily at THIS instance's first execute(): when the
        // same view is queued for deletion twice, the later instance executes against an
        // already-gutted view and captures empty lists, so only one instance ever re-adds these.
        // A refactor that precomputes this state at construction (as DeleteElementCommand does)
        // would reintroduce the duplicate-add throw here and must add contains() guards.
        // 4. Restore cascaded placeholders first, sorted by index ascending
        //    so each insertion targets a position that already has every
        //    smaller-index sibling restored. Each entry was captured at its
        //    pre-removal index in its parent's children list.
        List<CascadedRef> sortedCascaded = new ArrayList<>(capturedCascadedRefs);
        sortedCascaded.sort((a, b) -> Integer.compare(a.index(), b.index()));
        for (CascadedRef cascaded : sortedCascaded) {
            IDiagramModelContainer parent = cascaded.parent();
            int idx = cascaded.index();
            if (idx >= 0 && idx <= parent.getChildren().size()) {
                parent.getChildren().add(idx, cascaded.ref());
            } else {
                parent.getChildren().add(cascaded.ref());
            }
        }
        // Reconnect the placeholders' connections after the placeholders are back in
        // their surviving views, so the model is consistent (every connection's endpoints
        // are present) before the connections are re-added to the endpoints' lists.
        for (IDiagramModelConnection conn : capturedCascadedConnections) {
            conn.reconnect();
        }

        // 3. Re-add view to folder, anchored to its surviving successor
        SiblingUndoAnchor.restore(viewFolder.getElements(), view, viewIndex, viewSuccessor);

        // 2. Re-add children at original indices
        for (int i = 0; i < capturedChildren.size(); i++) {
            int idx = capturedIndices.get(i);
            IDiagramModelObject child = capturedChildren.get(i);
            if (idx >= 0 && idx <= view.getChildren().size()) {
                view.getChildren().add(idx, child);
            } else {
                view.getChildren().add(child);
            }
        }

        // 1. Reconnect all connections
        for (IDiagramModelConnection conn : capturedConnections) {
            conn.reconnect();
        }
    }

    private List<CascadedRef> captureExternalPlaceholders() {
        return captureExternalPlaceholders(view);
    }

    /**
     * Collect the unique connections attached to the cascaded placeholders (both the
     * connections they source and those they target). Order-preserving and deduplicated
     * so a connection joining two cascaded placeholders is disconnected exactly once.
     */
    private static List<IDiagramModelConnection> collectCascadedConnections(List<CascadedRef> cascadedRefs) {
        Set<IDiagramModelConnection> unique = new LinkedHashSet<>();
        for (CascadedRef cascaded : cascadedRefs) {
            IDiagramModelReference ref = cascaded.ref();
            for (Object conn : ref.getSourceConnections()) {
                if (conn instanceof IDiagramModelConnection dc) {
                    unique.add(dc);
                }
            }
            for (Object conn : ref.getTargetConnections()) {
                if (conn instanceof IDiagramModelConnection dc) {
                    unique.add(dc);
                }
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * Number of external placeholders a delete of {@code view} would cascade-remove.
     *
     * <p>Delegates to the same scan the cascade itself uses, so a reported count
     * can never drift from the removal behaviour. Callers that only need the size
     * get it without exposing the captured undo state.</p>
     */
    static int countExternalPlaceholders(IDiagramModel view) {
        return captureExternalPlaceholders(view).size();
    }

    /**
     * Scan the model's DIAGRAMS folder for every {@link IDiagramModelReference}
     * whose {@code getReferencedModel() == view}, excluding refs contained
     * inside the view being deleted (those die with the view).
     */
    private static List<CascadedRef> captureExternalPlaceholders(IDiagramModel view) {
        List<CascadedRef> out = new ArrayList<>();
        IArchimateModel model = view.getArchimateModel();
        if (model == null) {
            return out;
        }
        IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);
        if (diagramsFolder == null) {
            return out;
        }
        for (Iterator<EObject> iter = diagramsFolder.eAllContents(); iter.hasNext(); ) {
            EObject node = iter.next();
            if (!(node instanceof IDiagramModelReference ref)) {
                continue;
            }
            if (ref.getReferencedModel() != view) {
                continue;
            }
            if (isContainedIn(ref, view)) {
                continue;
            }
            EObject container = ref.eContainer();
            if (!(container instanceof IDiagramModelContainer parent)) {
                continue;
            }
            int index = parent.getChildren().indexOf(ref);
            out.add(new CascadedRef(parent, index, ref));
        }
        return out;
    }

    /** True iff {@code node}'s containment chain reaches {@code ancestor}. */
    private static boolean isContainedIn(EObject node, EObject ancestor) {
        for (EObject cur = node.eContainer(); cur != null; cur = cur.eContainer()) {
            if (cur == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Package-visible for testing. */
    IDiagramModel getView() { return view; }

    /** Package-visible for testing. */
    IFolder getViewFolder() { return viewFolder; }

    /** Package-visible for testing. */
    List<CascadedRef> getCapturedCascadedRefs() {
        return capturedCascadedRefs;
    }
}
