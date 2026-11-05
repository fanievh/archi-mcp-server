package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFeature;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProperty;

import net.vheerden.archi.mcp.model.RequireAttachedContainerCommand.Wording;
import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Copies a source view's metadata, objects and connections into a clone when it runs, so an
 * operation queued ahead of it in the same batch counts.
 *
 * <p>A batch prepares every call before any of them runs. A clone built at prepare copied the source
 * as it stood before the batch: a resize, a bendpoint edit, an added or removed object, or a styling
 * or metadata change queued ahead of it was missing from the copy, and a delete queued ahead of it
 * left the clone drawing a concept no longer in the model, which a saved model then failed to reopen
 * with. Reading the source here, after the earlier commands have run, copies what the source holds
 * at that point. Outside a batch the two moments see the same model, so the copy is unchanged.</p>
 *
 * <p>It runs immediately after the {@link CreateViewCommand} that attaches the clone, in the same
 * compound, rather than wrapping it: the batch's read-back of what it has queued finds that create
 * only by descending compounds, which is what lets a later operation in the batch place objects on
 * the clone. A later operation therefore prepares against the clone before it holds anything, and
 * sees it empty until the batch commits.</p>
 *
 * <p>The source may itself be a view an earlier operation of the same batch creates (a new view, or
 * another clone): it is attached by then, and holds what the operations between have put on it.</p>
 *
 * <p>It copies what Archi's own Duplicate copies: every object on the view, view references and
 * images included, and every connection, including one whose end is another connection. A view
 * reference to the source view itself points to the clone in the copy, as it does in Archi.</p>
 *
 * <p>Each object and connection is copied the way Duplicate's EMF copy does it: every attribute
 * that is set, read from the object's own class at run time rather than from a list, and every
 * contained value (bounds, bendpoints, properties and the feature map, which holds label
 * expressions, gradients, line styles, legends, label positions and this server's anchors). So a
 * value Archi adds later is copied too. The id is new. A name that Archi reads from the concept or
 * the referenced view is not written, because writing it would rename that concept or view. An
 * anchor to another object on the view is anchored to that object's copy, so the copy follows its
 * own target. The view's own features are copied with its other metadata.</p>
 *
 * <p>It declines, and leaves no clone, when there is nothing it may copy: the source view was removed
 * by an earlier operation, was never created because the operation that was to create it declined,
 * now holds an object a clone cannot copy, holds a view reference to a view that is not in the
 * model, which a copy would carry into a second place, or holds a connection with an end that is not
 * on the view, which only a hand-edited file can store. The guard on the create asks the same question
 * first ({@link #addTo}), so the clone is never attached and the pair vouches it wrote nothing. When the
 * create declined (its folder was removed, or the source fails this check), the clone is not attached
 * and this changes nothing, so the request reports one reason, not two, and vouches for it
 * ({@link #changedNothing()}). Run outside that pair, this checks again and detaches the clone the
 * create attached, and {@link #undo()} puts it back for that create's own undo to remove.</p>
 *
 * <p>Redo replays the objects and connections the first run created, never copies again, so a redo
 * restores exactly the clone that was undone.</p>
 *
 * <p><strong>CRITICAL:</strong> cloned view objects and connections reference the SAME model
 * elements and relationships as the source; nothing in the model is copied. {@code EcoreUtil.copy()}
 * is intentionally avoided on the objects and connections themselves, which hold those references,
 * their ends and their children. It is used only on the values they contain (bounds, bendpoints,
 * properties, features), which hold attributes and nothing else.</p>
 */
class CloneViewContentsCommand extends Command implements CommitSkippableCommand {

    private final IArchimateDiagramModel source;
    private final IArchimateDiagramModel clone;
    private final IArchimateModel model;

    /**
     * Whether the source was in the model when the clone was prepared. A source that was not is one
     * an earlier operation of the same batch was to create, and no batched operation can remove a view
     * the batch creates, so if it is missing at commit it was never created at all.
     */
    private final boolean sourceWasInTheModel;

    /** Non-null once {@link #execute()} has declined. See {@link CommitSkippableCommand}. */
    private String skipReason;

    /** Set where the last run found no clone to copy into, because its create declined. */
    private boolean changedNothing;

    /** Where the declined clone was attached, so {@link #undo()} can restore it for the create's undo. */
    private IFolder declinedFrom;
    private int declinedIndex;

    /** Whether the copy ran, so {@link #undo()} and {@link #redo()} touch only what it changed. */
    private boolean copied;
    private ViewMetadata metadataBefore;
    private ViewMetadata metadataAfter;
    private final List<IDiagramModelObject> copiedChildren = new ArrayList<>();
    private final List<Command> connectionCommands = new ArrayList<>();

    /**
     * @param source the view to copy from, as resolved when the call was prepared: one in the model,
     *               or one an earlier operation of the same batch will create
     * @param clone  the new, empty view its {@link CreateViewCommand} attaches
     * @param model  the model the clone is created in
     */
    CloneViewContentsCommand(IArchimateDiagramModel source, IArchimateDiagramModel clone,
            IArchimateModel model) {
        super("Copy view contents");
        this.source = source;
        this.clone = clone;
        this.model = model;
        this.sourceWasInTheModel = isAttachedTo(source, model);
    }

    @Override
    public void execute() {
        changedNothing = false;
        if (!isAttachedTo(clone, model)) {
            // The create declined, and has said why; there is nothing to copy into.
            changedNothing = true;
            return;
        }
        skipReason = describeSkip();
        if (skipReason != null) {
            declinedFrom = (IFolder) clone.eContainer();
            declinedIndex = declinedFrom.getElements().indexOf(clone);
            declinedFrom.getElements().remove(clone);
            return;
        }
        metadataBefore = ViewMetadata.of(clone);
        copyViewMetadata(source, clone);
        metadataAfter = ViewMetadata.of(clone);

        // Only what this copy adds, so undo leaves anything else in the view where it is.
        int alreadyThere = clone.getChildren().size();
        Map<String, IConnectable> idMapping = new LinkedHashMap<>();
        cloneChildren(source, clone, idMapping);
        anchorToCopies(idMapping);
        copiedChildren.addAll(clone.getChildren().subList(alreadyThere, clone.getChildren().size()));

        // Every connection is mapped before any is wired, so an end that is itself a connection
        // resolves whichever order the two are found in. A connection is held by its source end, so
        // wiring them in the order they are found wires that end first.
        List<IDiagramModelConnection> connections = new ArrayList<>();
        collectConnections(source, connections);
        for (IDiagramModelConnection conn : connections) {
            idMapping.put(conn.getId(), cloneConnection(conn));
        }
        for (IDiagramModelConnection conn : connections) {
            Command connect = connectCommand((IDiagramModelConnection) idMapping.get(conn.getId()),
                    mapped(idMapping, conn.getSource()), mapped(idMapping, conn.getTarget()));
            connect.execute();
            connectionCommands.add(connect);
        }
        copied = true;
    }

    @Override
    public void undo() {
        if (declinedFrom != null) {
            declinedFrom.getElements().add(declinedIndex, clone);
        }
        if (copied) {
            for (int i = connectionCommands.size() - 1; i >= 0; i--) {
                connectionCommands.get(i).undo();
            }
            clone.getChildren().removeAll(copiedChildren);
            metadataBefore.applyTo(clone);
        }
    }

    @Override
    public void redo() {
        if (declinedFrom != null) {
            declinedFrom.getElements().remove(clone);
        }
        if (copied) {
            metadataAfter.applyTo(clone);
            clone.getChildren().addAll(copiedChildren);
            for (Command connect : connectionCommands) {
                connect.redo();
            }
        }
    }

    @Override
    public String getSkipReason() {
        return skipReason;
    }

    /**
     * True only when the clone was not attached, so there was nothing to copy into. A decline that
     * detached the clone its create attached does not vouch.
     */
    @Override
    public boolean changedNothing() {
        return changedNothing;
    }

    /**
     * Adds the create of {@code clone} in {@code folder}, then this copy, to {@code compound}. The create's
     * guard asks this copy's check first, so a source that cannot be copied declines the pair before the
     * clone is attached.
     */
    static void addTo(CompoundCommand compound, IArchimateDiagramModel source, IArchimateDiagramModel clone,
            IFolder folder, IArchimateModel model) {
        CloneViewContentsCommand copy = new CloneViewContentsCommand(source, clone, model);
        compound.add(new RequireAttachedContainerCommand(new CreateViewCommand(clone, folder), folder, model,
                clone.getName(), "folder", Wording.CREATE_IN_FOLDER, copy::describeSkip));
        compound.add(copy);
    }

    /**
     * The reason to decline, re-checked when the command runs, or {@code null} when the source can be
     * copied.
     */
    String describeSkip() {
        String subject = "'" + displayName(clone.getName()) + "' was not created: the view '"
                + displayName(source.getName()) + "'";
        if (!isAttachedTo(source, model)) {
            if (!sourceWasInTheModel) {
                return subject + " it was to be copied from is not in the model: the operation earlier in "
                        + "the same request that was to create it was declined, so there was nothing to "
                        + "copy. Nothing was created and nothing else was affected. Clone it in a separate "
                        + "request once that view exists.";
            }
            return subject + " it was to be copied from was removed from the model "
                    + CHANGED_SINCE_PREPARED + ", so there was nothing left to copy. Nothing was "
                    + "created and nothing else was affected. Clone it before the operation that removes "
                    + "it, or in a separate request.";
        }
        IDiagramModelObject uncopyable = uncopyableObject(source);
        if (uncopyable != null) {
            return subject + " held a view object of a type a clone cannot copy ("
                    + uncopyable.eClass().getName() + ") by the time the clone was made. Nothing was "
                    + "created and nothing else was affected. Remove it with remove-from-view, " + THEN_CLONE;
        }
        IDiagramModelReference dangling = danglingReference(source, model);
        if (dangling != null) {
            return subject + " holds a reference to " + danglingTarget(dangling) + ", so a clone cannot copy "
                    + "it. Nothing was created and nothing else was affected. Remove that view reference with "
                    + "remove-from-view, " + THEN_CLONE;
        }
        IDiagramModelConnection offTheView = connectionWithAnEndOffTheView(source);
        if (offTheView != null) {
            return subject + " holds a connection with an end that is not on the view, so a clone cannot copy "
                    + "it. Nothing was created and nothing else was affected. " + OFF_THE_VIEW_REMEDY;
        }
        return null;
    }

    /**
     * A clone is checked when it is called, so it cannot follow a removal queued in the same batch:
     * the removal has not run yet.
     */
    private static final String THEN_CLONE = "then clone the view once that removal is applied; a clone "
            + "queued in the same batch as the removal is still refused.";

    /**
     * A view holds a connection through its source end, so the connection refused, whose source is on
     * the view, is removed by passing this view.
     */
    private static final String OFF_THE_VIEW_REMEDY = "Remove that connection with remove-from-view, passing "
            + "this view, " + THEN_CLONE;

    /**
     * Refuses, before anything is queued, a source view a clone cannot copy as it stands, so the error
     * reaches the caller when the call is made rather than at commit. Outside a batch the commit sees
     * the same view, and nothing would report a decline there.
     *
     * <p>Every refusal is the caller's to fix, and {@code remove-from-view} removes each object
     * named, so the remedy names that tool.</p>
     *
     * @throws ModelAccessException {@code INVALID_PARAMETER} naming the first object a clone cannot
     *                              copy, or the first view reference to a view not in the model
     */
    static void requireCloneable(IArchimateDiagramModel source, IArchimateModel model) {
        String subject = "Cannot clone the view '" + displayName(source.getName()) + "': ";
        IDiagramModelObject uncopyable = uncopyableObject(source);
        if (uncopyable != null) {
            throw new ModelAccessException(subject + "it holds a view object of a type a clone cannot copy, "
                    + uncopyable.eClass().getName() + " '" + uncopyable.getId() + "'.",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Remove that object with remove-from-view, " + THEN_CLONE,
                    null);
        }
        IDiagramModelReference dangling = danglingReference(source, model);
        if (dangling != null) {
            throw new ModelAccessException(subject + "its view reference '" + dangling.getId()
                    + "' points to " + danglingTarget(dangling) + ".",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Remove that view reference with remove-from-view, " + THEN_CLONE,
                    null);
        }
        IDiagramModelConnection offTheView = connectionWithAnEndOffTheView(source);
        if (offTheView != null) {
            throw new ModelAccessException(subject + "its connection '" + offTheView.getId()
                    + "' has an end that is not on the view.",
                    ErrorCode.INVALID_PARAMETER, null,
                    OFF_THE_VIEW_REMEDY,
                    null);
        }
    }

    /**
     * The folder a clone given no folder goes in: its source's, or, for a source an earlier operation
     * of the same batch creates, the folder that operation will add it to.
     *
     * <p>The source is read again after the queued lookup, so a batch that commits between the two
     * reads still places the clone beside its source, now attached, rather than in the root.</p>
     *
     * @param queuedFolder the folder a queued create will add the source to, or null when none does
     */
    static IFolder defaultFolder(IArchimateDiagramModel source, Supplier<IFolder> queuedFolder,
            IArchimateModel model) {
        if (source.eContainer() instanceof IFolder folder) {
            return folder;
        }
        IFolder queued = queuedFolder.get();
        if (queued != null) {
            return queued;
        }
        return source.eContainer() instanceof IFolder folder ? folder : model.getFolder(FolderType.DIAGRAMS);
    }

    /**
     * The first object under {@code container} a clone cannot copy, or null. Those are the kinds only
     * a script or a hand-edited file can put on an ArchiMate view, such as a sketch or canvas object.
     */
    private static IDiagramModelObject uncopyableObject(IDiagramModelContainer container) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (!(child instanceof IDiagramModelArchimateObject || child instanceof IDiagramModelGroup
                    || child instanceof IDiagramModelNote || child instanceof IDiagramModelReference
                    || child instanceof IDiagramModelImage)) {
                return child;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelObject found = uncopyableObject(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The first view reference under {@code container} to a view not in {@code model}, or null: one
     * whose view's create was declined, which a saved model then fails to reopen with.
     */
    private static IDiagramModelReference danglingReference(IDiagramModelContainer container,
            IArchimateModel model) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelReference reference && (reference.getReferencedModel() == null
                    || !isAttachedTo(reference.getReferencedModel(), model))) {
                return reference;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelReference found = danglingReference(nested, model);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** What a dangling reference points to, for the refusal: no view at all, or a view not in the model. */
    private static String danglingTarget(IDiagramModelReference reference) {
        IDiagramModel referenced = reference.getReferencedModel();
        return referenced == null ? "no view"
                : "the view '" + displayName(referenced.getName()) + "', which is not in the model";
    }

    /**
     * The first connection on {@code view} with an end that is not on it, or null. Archi never stores
     * one, but a hand-edited file can, and the copy would have no end to join it to. Checked before
     * anything is copied, so the clone is declined whole rather than left half built.
     */
    private static IDiagramModelConnection connectionWithAnEndOffTheView(IArchimateDiagramModel view) {
        List<IDiagramModelConnection> connections = new ArrayList<>();
        collectConnections(view, connections);
        for (IDiagramModelConnection conn : connections) {
            if (!isAttachedTo(conn.getSource(), view) || !isAttachedTo(conn.getTarget(), view)) {
                return conn;
            }
        }
        return null;
    }

    /**
     * Copies the view-level metadata: viewpoint, connection router, documentation, properties and
     * features. Overwrites whatever the target held, so copying twice leaves the second source's
     * values.
     */
    static void copyViewMetadata(IArchimateDiagramModel source, IArchimateDiagramModel target) {
        String vp = source.getViewpoint();
        target.setViewpoint(vp != null ? vp : "");
        target.setConnectionRouterType(source.getConnectionRouterType());

        // Copy documentation
        String doc = source.getDocumentation();
        target.setDocumentation(doc != null ? doc : "");

        // Copy custom properties
        target.getProperties().clear();
        copyProperties(source.getProperties(), target.getProperties());

        target.getFeatures().clear();
        for (IFeature feature : source.getFeatures()) {
            target.getFeatures().add(EcoreUtil.copy(feature));
        }
    }

    /** Appends a new property to {@code target} for each of {@code source}'s, with the same key and value. */
    private static void copyProperties(List<IProperty> source, List<IProperty> target) {
        for (IProperty prop : source) {
            IProperty newProp = IArchimateFactory.eINSTANCE.createProperty();
            newProp.setKey(prop.getKey());
            newProp.setValue(prop.getValue());
            target.add(newProp);
        }
    }

    /**
     * Recursively clones children from source container to target container.
     * Builds an ID mapping (source view object ID → cloned view object) for
     * connection resolution.
     */
    private void cloneChildren(IDiagramModelContainer source, IDiagramModelContainer target,
            Map<String, IConnectable> idMapping) {
        for (IDiagramModelObject child : source.getChildren()) {
            IDiagramModelObject cloned = cloneViewObject(child);
            target.getChildren().add(cloned);
            idMapping.put(child.getId(), cloned);

            // Recurse into containers (groups, element-as-container)
            if (child instanceof IDiagramModelContainer sourceContainer
                    && cloned instanceof IDiagramModelContainer clonedContainer) {
                cloneChildren(sourceContainer, clonedContainer, idMapping);
            }
        }
    }

    /**
     * Creates a clone of a single view object: the same concept or referenced view, and every value
     * {@link #copyValues} copies. Does NOT recurse into children — that is handled by
     * {@link #cloneChildren}. Only reached after {@link #uncopyableObject} found every object clonable.
     */
    private IDiagramModelObject cloneViewObject(IDiagramModelObject source) {
        IDiagramModelObject cloned;
        if (source instanceof IDiagramModelArchimateObject archObj) {
            IDiagramModelArchimateObject clonedArchObj =
                    IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
            clonedArchObj.setArchimateElement(archObj.getArchimateElement()); // REFERENCE, not copy
            cloned = clonedArchObj;
        } else if (source instanceof IDiagramModelGroup) {
            cloned = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        } else if (source instanceof IDiagramModelNote) {
            cloned = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        } else if (source instanceof IDiagramModelReference referenceObj) {
            IDiagramModelReference clonedReference = IArchimateFactory.eINSTANCE.createDiagramModelReference();
            // A reference to the view being cloned points to the clone, as in Archi's Duplicate.
            IDiagramModel referenced = referenceObj.getReferencedModel();
            clonedReference.setReferencedModel(referenced == this.source ? clone : referenced);
            cloned = clonedReference;
        } else if (source instanceof IDiagramModelImage) {
            cloned = IArchimateFactory.eINSTANCE.createDiagramModelImage();
        } else {
            throw new IllegalStateException(
                    "Cannot clone unsupported view object type: " + source.eClass().getName());
        }
        copyValues(source, cloned);
        return cloned;
    }

    /**
     * Copies onto {@code target} what Archi's Duplicate copies of {@code source}, other than its
     * references, ends and children: every changeable, non-derived attribute that is set, and a copy
     * of every contained value. Read from the class at run time, so it carries whatever this Archi
     * version holds. Leaves out the id, which the copy has its own of, and a name the copy reads from
     * its concept or referenced view (see {@link #isReadThrough}).
     */
    private static void copyValues(EObject source, EObject target) {
        for (EAttribute attribute : source.eClass().getEAllAttributes()) {
            if (attribute.isChangeable() && !attribute.isDerived() && source.eIsSet(attribute)
                    && attribute != IArchimatePackage.Literals.IDENTIFIER__ID
                    && !isReadThrough(source, attribute)) {
                target.eSet(attribute, source.eGet(attribute));
            }
        }
        for (EReference containment : source.eClass().getEAllContainments()) {
            if (containment == IArchimatePackage.Literals.DIAGRAM_MODEL_CONTAINER__CHILDREN
                    || containment == IArchimatePackage.Literals.CONNECTABLE__SOURCE_CONNECTIONS
                    || !containment.isChangeable() || !source.eIsSet(containment)) {
                continue;
            }
            if (containment.isMany()) {
                @SuppressWarnings("unchecked")
                List<EObject> copies = (List<EObject>) target.eGet(containment);
                for (Object value : (List<?>) source.eGet(containment)) {
                    copies.add(EcoreUtil.copy((EObject) value));
                }
            } else {
                target.eSet(containment, EcoreUtil.copy((EObject) source.eGet(containment)));
            }
        }
    }

    /**
     * Whether {@code attribute} is a name Archi keeps on something the copy only points to. An
     * ArchiMate object or connection reads and writes its name on its concept, and a view reference on
     * the view it references, so setting it on the copy would rename that concept or view.
     */
    private static boolean isReadThrough(EObject component, EAttribute attribute) {
        return attribute == IArchimatePackage.Literals.NAMEABLE__NAME
                && (component instanceof IDiagramModelArchimateComponent
                        || component instanceof IDiagramModelReference);
    }

    /**
     * Points each copied anchor at the copy of its target, so the copy follows its own target rather
     * than the source's. An anchor whose target is not on the source view has no copy to point at and
     * stays as it was. Run once every object is copied and before any connection is, so {@code idMapping} holds
     * view objects only.
     */
    private static void anchorToCopies(Map<String, IConnectable> idMapping) {
        for (IConnectable copy : idMapping.values()) {
            String target = copy.getFeatures().getString(AnchorResolver.ANCHOR_TARGET_FEATURE, null);
            IConnectable targetCopy = target == null ? null : idMapping.get(target);
            if (targetCopy != null) {
                copy.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, targetCopy.getId());
            }
        }
    }

    /**
     * Collects every connection on the view, each after the end it is held by: an object's, then those
     * whose source is one of its connections, and so on.
     */
    private static void collectConnections(IDiagramModelContainer container,
            List<IDiagramModelConnection> found) {
        for (IDiagramModelObject child : container.getChildren()) {
            collectSourceConnections(child, found);
            if (child instanceof IDiagramModelContainer nested) {
                collectConnections(nested, found);
            }
        }
    }

    private static void collectSourceConnections(IConnectable end, List<IDiagramModelConnection> found) {
        for (IDiagramModelConnection conn : end.getSourceConnections()) {
            found.add(conn);
            collectSourceConnections(conn, found);
        }
    }

    /** A new, unwired copy of {@code conn}: the same relationship, and every value {@link #copyValues} copies. */
    private static IDiagramModelConnection cloneConnection(IDiagramModelConnection conn) {
        IDiagramModelConnection clonedConn;
        if (conn instanceof IDiagramModelArchimateConnection archConn) {
            IDiagramModelArchimateConnection clonedArchConn =
                    IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
            clonedArchConn.setArchimateRelationship(
                    archConn.getArchimateRelationship()); // REFERENCE, not copy
            clonedConn = clonedArchConn;
        } else {
            // Non-archimate connection (e.g., note connection); its label is its own name.
            clonedConn = IArchimateFactory.eINSTANCE.createDiagramModelConnection();
        }
        copyValues(conn, clonedConn);
        return clonedConn;
    }

    /**
     * The copy of a connection's end. A connection with an end off the view declines before anything
     * is copied, and everything on the view has been copied by now, so a missing one is a defect.
     */
    private static IConnectable mapped(Map<String, IConnectable> idMapping, IConnectable end) {
        IConnectable copy = end == null ? null : idMapping.get(end.getId());
        if (copy == null) {
            throw new IllegalStateException("Cannot clone a connection whose end is not on the view: "
                    + (end == null ? "null" : end.getId()));
        }
        return copy;
    }

    /** The command that wires one cloned connection, with an undo and redo of its own. */
    private static Command connectCommand(IDiagramModelConnection conn, IConnectable src, IConnectable tgt) {
        if (conn instanceof IDiagramModelArchimateConnection archConn) {
            return new AddConnectionToViewCommand(archConn, src, tgt);
        }
        // Generic connection (e.g., note connection) — must override redo()
        // because disconnect() preserves internal source/target fields and
        // connect() early-returns when they already match (same issue that
        // AddConnectionToViewCommand.redo() handles for archimate connections).
        return new Command("Connect") {
            @Override public void execute() { conn.connect(src, tgt); }
            @Override public void undo() { conn.disconnect(); }
            @Override public void redo() {
                conn.setSource(null);
                conn.setTarget(null);
                conn.connect(src, tgt);
            }
        };
    }

    /**
     * Whether {@code object} still reaches {@code root} by containment: a model, or a view, which holds
     * its connections through the ends they leave from.
     */
    private static boolean isAttachedTo(EObject object, EObject root) {
        for (EObject current = object; current != null; current = current.eContainer()) {
            if (current == root) {
                return true;
            }
        }
        return false;
    }

    /** A never-null name for the agent-facing skip reason. */
    private static String displayName(String name) {
        return (name == null || name.isEmpty()) ? "(unnamed)" : name;
    }

    /** The view-level values {@link #copyViewMetadata} writes, held so undo and redo can restore them. */
    private record ViewMetadata(String viewpoint, int connectionRouterType, String documentation,
            List<IProperty> properties, List<IFeature> features) {

        static ViewMetadata of(IArchimateDiagramModel view) {
            return new ViewMetadata(view.getViewpoint(), view.getConnectionRouterType(),
                    view.getDocumentation(), List.copyOf(view.getProperties()), List.copyOf(view.getFeatures()));
        }

        void applyTo(IArchimateDiagramModel view) {
            view.setViewpoint(viewpoint);
            view.setConnectionRouterType(connectionRouterType);
            view.setDocumentation(documentation);
            view.getProperties().clear();
            view.getProperties().addAll(properties);
            view.getFeatures().clear();
            view.getFeatures().addAll(features);
        }
    }
}
