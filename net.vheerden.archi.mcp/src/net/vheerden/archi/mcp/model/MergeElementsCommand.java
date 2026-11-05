package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDocumentable;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.INameable;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;

/**
 * Collapses clusters of duplicate elements into the element named for each, re-pointing what
 * referred to a duplicate rather than recreating it, then deleting the emptied duplicates.
 *
 * <p><strong>Decided when it runs.</strong> The clusters are resolved and every effect is read
 * from the model inside {@link #execute()}, not when the call was prepared: an approved proposal
 * runs after a human may have edited the model, and a relationship added to a duplicate in the
 * meantime must move with it. If a cluster no longer resolves at that moment (an id gone, a type
 * changed), the command writes nothing and reports why through {@link #getSkipReason()}.
 * {@code canExecute} stays true: a refusal there would make the stack reject the entry.</p>
 *
 * <p>What moves may grow in the meantime, but what is deleted may not: a merge that would now
 * delete a relationship or view connection the proposal did not list writes nothing either, since
 * the human approved a card that did not show that deletion.</p>
 *
 * <p><strong>Order.</strong> Boxes first, then relationship ends, then the removals, then the
 * folds of parallel relationships, then the target's documentation and properties, then the
 * duplicates themselves. A diagram object and the
 * relationship ends it draws are always moved inside this one command, never one without the
 * other: a view connection's {@code reconnect()} re-derives its relationship's ends from the two
 * boxes it joins, so a later reconnect anywhere would move a half-merged relationship onto
 * whichever element its box shows. Undo runs every step in exact reverse, which keeps the two
 * consistent at every point.</p>
 *
 * <p>A diagram object is taken out of its parent, given its new element and put back at the
 * index it had. {@code setArchimateElement} notifies nothing, so an open diagram would otherwise
 * keep drawing the deleted duplicate; re-adding is what makes the editor redraw it, and is Archi's
 * own sequence for changing an object's element.</p>
 *
 * <p><strong>Folds.</strong> Under {@code collapseParallel}, a folded relationship's survivor is
 * filled first, then what hung on the folded one is moved to the survivor, then its view
 * connections are re-pointed, then the connections that hung on one about to be removed are put
 * on the survivor's connection there, and only then are the removed connections and the folded
 * relationships themselves taken out. A connection is re-pointed the way an object is: taken off
 * both ends' lists, given its new relationship and put back at the indexes it had, which is what
 * an open view hears. It is never {@code reconnect()}ed, since that would also re-derive the
 * relationship's ends from what the connection joins, and a connection can join a connection
 * that is about to be re-pointed or removed: every end is moved explicitly instead.</p>
 *
 * <p><strong>Undo and redo.</strong> Every write is recorded before it is made, with what it needs
 * to reverse exactly: the index an object had among its siblings, the documentation text it held,
 * the property it added. Undo reverses the record; redo replays it without reading the model
 * again, so the redone merge is the merge that was undone. An undo or a redo that fails
 * partway leaves the command not applied, as far as it can, and still throws: see
 * {@link RecordedSteps}.</p>
 *
 * <p>A write that fails part-way (a listener throwing on a notification, which EMF sends after
 * the change) is undone inside the command, the failing step included, and the rollback reverts
 * every recorded step even when one revert fails, with an exception or an {@code Error}; an
 * {@code Error}, from the write or from a revert, still reaches the caller once every revert has
 * run. A revert therefore tolerates a step that was
 * only partly made: a box, relationship or element is put back only if it is not already there,
 * and a relationship reconnected only if it is not on its ends' lists. The connection, end and
 * attribute steps need no such guard: a connection is put on an end only through
 * {@code addConnection} or {@code reconnect()}, which skip one already there, and a set or a
 * property removal is the same whether or not the write it reverses was made.</p>
 *
 * <p><strong>In a batch.</strong> Queued, it closes the batch to every operation but another
 * merge ({@link BatchClosingCommand}): what it deletes and re-points is decided at commit, after
 * every operation queued later was prepared. A later merge may not name one of this merge's
 * duplicates, which are known from its request and are gone by the time the later one runs; it may
 * name this merge's target, and then sees this merge's result.</p>
 */
final class MergeElementsCommand extends Command implements CommitSkippableCommand, BatchClosingCommand {

    private static final String TOOL = "merge-elements";

    private final IArchimateModel model;
    private final List<MergeClusterRequest> merges;
    private final MergeElementsPlan.Options options;
    private final Set<String> listedRemovals;

    private final RecordedSteps record = new RecordedSteps();
    private MergeElementsResultDto result;
    private String skipReason;
    /** Set where the last run declined with nothing it wrote left behind. */
    private boolean changedNothing;
    /** Set where the last run declined and could not undo all of what it wrote. */
    private boolean leftChanges;

    MergeElementsCommand(IArchimateModel model, List<MergeClusterRequest> merges,
            MergeElementsPlan.Options options) {
        this(model, merges, options, null);
    }

    /**
     * @param listedRemovals the ids of the relationships and view connections the caller was shown
     *        as deleted; the command declines to delete any other. {@code null} sets no limit.
     */
    MergeElementsCommand(IArchimateModel model, List<MergeClusterRequest> merges,
            MergeElementsPlan.Options options, Set<String> listedRemovals) {
        this.model = model;
        this.merges = List.copyOf(merges);
        this.options = options;
        this.listedRemovals = listedRemovals == null ? null : Set.copyOf(listedRemovals);
        setLabel("Merge elements");
    }

    /** The deletions this command may make, or null when it sets no limit. */
    Set<String> getListedRemovals() {
        return listedRemovals;
    }

    @Override
    public Refusal refuseFollower(int seq, Command follower, String tool) {
        if (!(follower instanceof MergeElementsCommand later)) {
            return closedTo(seq, tool);
        }
        for (int i = 0; i < later.merges.size(); i++) {
            MergeClusterRequest cluster = later.merges.get(i);
            Refusal refusal = refuseDuplicate(seq, cluster.targetId(), "merges[" + i + "].targetId");
            for (int j = 0; refusal == null && j < cluster.sourceIds().size(); j++) {
                refusal = refuseDuplicate(seq, cluster.sourceIds().get(j), "merges[" + i + "].sourceIds[" + j + "]");
            }
            if (refusal != null) {
                return refusal;
            }
        }
        return null;
    }

    /**
     * A merge proposal may wait for approval: whether it collides with this merge is decided when it is
     * approved and queued. Any other tool's proposal is refused now.
     */
    @Override
    public Refusal refuseProposal(int seq, String tool) {
        return TOOL.equals(tool) ? null : closedTo(seq, tool);
    }

    private static Refusal closedTo(int seq, String tool) {
        String who = tool != null ? tool : "This operation";
        return new Refusal(who + " cannot be queued after merge-elements (operation #" + seq
                + ") in this batch: it was prepared before the merge runs, and the merge deletes or "
                + "re-points what it may name. Nothing was queued.", ErrorCode.BATCH_ALREADY_ACTIVE,
                "Call end-batch to commit the merge (or with rollback: true to discard the queue), then call "
                        + (tool != null ? tool : "it again") + ". Queue merges last in a batch.");
    }

    /** Refuses {@code id} when it is one of this merge's duplicates, which this merge deletes. */
    private Refusal refuseDuplicate(int seq, String id, String position) {
        for (MergeClusterRequest cluster : merges) {
            if (cluster.sourceIds().contains(id)) {
                return new Refusal(CallerText.bare(id) + " (" + position + ") is a duplicate that merge-elements "
                        + "(operation #" + seq + ") in this batch merges into " + CallerText.bare(cluster.targetId())
                        + " and deletes; a later merge cannot name it. Nothing was queued.",
                        ErrorCode.INVALID_PARAMETER, "Name " + CallerText.bare(cluster.targetId())
                                + " instead, or call end-batch first, then merge-elements.");
            }
        }
        return null;
    }

    /** What the last execute did, or null when it declined. */
    MergeElementsResultDto getResult() {
        return result;
    }

    @Override
    public String getSkipReason() {
        return skipReason;
    }

    @Override
    public boolean changedNothing() {
        return changedNothing;
    }

    @Override
    public boolean leftChangesItCouldNotUndo() {
        return leftChanges;
    }

    @Override
    public void execute() {
        record.clear();
        result = null;
        skipReason = null;
        changedNothing = false;
        leftChanges = false;
        List<MergeElementsPlan.Resolved> resolved;
        try {
            resolved = MergeElementsOperations.resolve(model, merges);
        } catch (ModelAccessException e) {
            skipReason = "merge-elements wrote nothing: " + e.getMessage();
            changedNothing = true;
            return;
        }
        MergeElementsPlan plan = MergeElementsPlan.read(model, resolved, options);
        if (listedRemovals != null) {
            List<String> unlisted = new ArrayList<>(plan.removalIds());
            unlisted.removeAll(listedRemovals);
            if (!unlisted.isEmpty()) {
                skipReason = "merge-elements wrote nothing: it would now also delete " + unlisted.size()
                        + " relationship(s) or view connection(s) the proposal did not list ("
                        + CallerText.quoted(String.join(", ", unlisted)) + ")";
                changedNothing = true;
                return;
            }
        }
        try {
            write(plan);
            plan.fillParallels(Function.identity(), IArchimateConcept::getSourceRelationships);
            result = plan.toResult();
        } catch (RuntimeException | Error e) {
            // Never leave a half-merged model behind: what was written is undone here, inside the
            // command, so the stack entry that follows is a no-op rather than an irreversible change.
            Throwable revertFailure = record.rollBack(e);
            record.clear();
            result = null;
            if (e instanceof Error error) {
                RecordedSteps.suppress(error, revertFailure);
                throw error;
            }
            if (revertFailure instanceof Error error) {
                RecordedSteps.suppress(error, e);
                throw error;
            }
            changedNothing = revertFailure == null;
            leftChanges = revertFailure != null;
            skipReason = revertFailure == null
                    ? "merge-elements stopped and undid its own changes: " + RecordedSteps.reason(e)
                    : "merge-elements stopped and could not undo all of its own changes: " + RecordedSteps.reason(e)
                            + "; undoing then failed with: " + RecordedSteps.reason(revertFailure);
        }
    }

    private void write(MergeElementsPlan plan) {
        for (MergeElementsPlan.BoxMove move : plan.boxes) {
            apply(new BoxStep(move.box(), move.to()));
        }
        for (MergeElementsPlan.EndMove move : plan.ends) {
            if (move.newSource() != null) {
                apply(new EndStep(move.relationship(), true, move.newSource()));
            }
            if (move.newTarget() != null) {
                apply(new EndStep(move.relationship(), false, move.newTarget()));
            }
        }
        for (IDiagramModelConnection connection : plan.connectionsToRemove) {
            apply(new ConnectionRemoval(connection));
        }
        for (IArchimateRelationship relationship : plan.relationshipsToRemove) {
            apply(new RelationshipRemoval(relationship));
        }
        if (plan.folded != null) {
            writeFolds(plan.folded);
        }
        for (MergeElementsPlan.ClusterPlan cluster : plan.clusters) {
            if (cluster.documentation != null) {
                apply(new DocumentationStep(cluster.documentation.target(),
                        cluster.documentation.before(), cluster.documentation.after()));
            }
            for (MergeElementsPlan.PropertyCopy copy : cluster.copies) {
                apply(new PropertyAdd(copy.target(), EcoreUtil.copy(copy.original())));
            }
            for (String sourceId : cluster.mergedFrom) {
                IProperty mergedFrom = IArchimateFactory.eINSTANCE.createProperty();
                mergedFrom.setKey(MergeElementsPlan.MERGED_FROM_KEY);
                mergedFrom.setValue(sourceId);
                apply(new PropertyAdd(cluster.cluster.target(), mergedFrom));
            }
        }
        for (MergeElementsPlan.ClusterPlan cluster : plan.clusters) {
            for (IArchimateElement source : cluster.cluster.sources()) {
                if (!source.getSourceRelationships().isEmpty() || !source.getTargetRelationships().isEmpty()
                        || !source.getReferencingDiagramComponents().isEmpty()) {
                    throw new IllegalStateException("duplicate " + CallerText.quoted(source.getId())
                            + " is still referenced after re-pointing");
                }
                apply(new ElementRemoval(source));
            }
        }
    }

    private void writeFolds(ParallelRelationshipFolds folded) {
        for (ParallelRelationshipFolds.Fold fold : folded.folds) {
            if (fold.nameAfter != null) {
                apply(new NameStep(fold.survivor, fold.survivor.getName(), fold.nameAfter));
            }
            if (fold.documentationAfter != null) {
                apply(new DocumentationStep(fold.survivor, fold.survivor.getDocumentation(),
                        fold.documentationAfter));
            }
            for (IProperty property : fold.propertiesCopied) {
                apply(new PropertyAdd(fold.survivor, EcoreUtil.copy(property)));
            }
        }
        for (ParallelRelationshipFolds.Move move : folded.moves) {
            apply(new EndStep(move.relationship(), move.sourceEnd(), move.to()));
        }
        for (ParallelRelationshipFolds.Fold fold : folded.folds) {
            for (IDiagramModelArchimateConnection connection : fold.repointed) {
                apply(new ConnectionRelationshipStep(connection, fold.survivor));
            }
        }
        for (ParallelRelationshipFolds.Reattach reattach : folded.reattaches) {
            apply(new ConnectionEndStep(reattach.connection(), reattach.source(), reattach.target()));
        }
        for (ParallelRelationshipFolds.Fold fold : folded.folds) {
            for (IDiagramModelConnection connection : fold.removed) {
                apply(new ConnectionRemoval(connection));
            }
        }
        for (ParallelRelationshipFolds.Fold fold : folded.folds) {
            apply(new RelationshipRemoval(fold.dropped));
        }
    }

    /** Records the step, then makes it: see {@link RecordedSteps}. */
    private void apply(RecordedSteps.Step step) {
        record.apply(step);
    }

    @Override
    public void undo() {
        record.undo();
    }

    @Override
    public void redo() {
        record.redo();
    }

    /** A diagram object shown with another element, at the index it held among its siblings. */
    private static final class BoxStep implements RecordedSteps.Step {
        private final IDiagramModelArchimateObject box;
        private final IDiagramModelContainer parent;
        private final int index;
        private final IArchimateElement from;
        private final IArchimateElement to;

        BoxStep(IDiagramModelArchimateObject box, IArchimateElement to) {
            this.box = box;
            this.parent = (IDiagramModelContainer) box.eContainer();
            this.index = parent.getChildren().indexOf(box);
            this.from = box.getArchimateElement();
            this.to = to;
        }

        @Override
        public void apply() {
            show(to);
        }

        @Override
        public void revert() {
            show(from);
        }

        private void show(IArchimateElement element) {
            EList<IDiagramModelObject> children = parent.getChildren();
            children.remove(box);
            box.setArchimateElement(element);
            if (!children.contains(box)) {
                children.add(Math.min(index, children.size()), box);
            }
        }
    }

    /** One end of a relationship moved to another concept. */
    private static final class EndStep implements RecordedSteps.Step {
        private final IArchimateRelationship relationship;
        private final boolean sourceEnd;
        private final IArchimateConcept from;
        private final IArchimateConcept to;

        EndStep(IArchimateRelationship relationship, boolean sourceEnd, IArchimateConcept to) {
            this.relationship = relationship;
            this.sourceEnd = sourceEnd;
            this.from = sourceEnd ? relationship.getSource() : relationship.getTarget();
            this.to = to;
        }

        @Override
        public void apply() {
            set(to);
        }

        @Override
        public void revert() {
            set(from);
        }

        private void set(IArchimateConcept end) {
            if (sourceEnd) {
                relationship.setSource(end);
            } else {
                relationship.setTarget(end);
            }
        }
    }

    /**
     * A view connection taken off its view. It is put back at the positions it held in both ends'
     * connection lists, which {@code reconnect()} alone would not do: it appends.
     */
    private static final class ConnectionRemoval implements RecordedSteps.Step {
        private final IDiagramModelConnection connection;
        private final IConnectable source;
        private final IConnectable target;
        private final int sourceIndex;
        private final int targetIndex;

        ConnectionRemoval(IDiagramModelConnection connection) {
            this.connection = connection;
            this.source = connection.getSource();
            this.target = connection.getTarget();
            this.sourceIndex = source.getSourceConnections().indexOf(connection);
            this.targetIndex = target.getTargetConnections().indexOf(connection);
        }

        @Override
        public void apply() {
            connection.disconnect();
        }

        @Override
        public void revert() {
            connection.reconnect();
            restore(source.getSourceConnections(), sourceIndex);
            restore(target.getTargetConnections(), targetIndex);
        }

        private void restore(EList<IDiagramModelConnection> list, int index) {
            int now = list.indexOf(connection);
            if (index >= 0 && now >= 0 && now != index && index < list.size()) {
                list.move(index, connection);
            }
        }
    }

    /** A relationship taken out of its folder and off its ends' lists, as Archi deletes one. */
    private static final class RelationshipRemoval implements RecordedSteps.Step {
        private final IArchimateRelationship relationship;
        private final IFolder folder;
        private final int index;

        RelationshipRemoval(IArchimateRelationship relationship) {
            this.relationship = relationship;
            this.folder = (IFolder) relationship.eContainer();
            this.index = folder.getElements().indexOf(relationship);
        }

        @Override
        public void apply() {
            folder.getElements().remove(relationship);
            relationship.disconnect();
        }

        @Override
        public void revert() {
            EList<EObject> elements = folder.getElements();
            if (!elements.contains(relationship)) {
                elements.add(Math.min(index, elements.size()), relationship);
            }
            if (!connected(relationship)) {
                relationship.reconnect();
            }
        }
    }

    /** Whether the relationship is on its source's and target's lists. */
    private static boolean connected(IArchimateRelationship relationship) {
        IArchimateConcept source = relationship.getSource();
        IArchimateConcept target = relationship.getTarget();
        return source != null && source.getSourceRelationships().contains(relationship)
                && target != null && target.getTargetRelationships().contains(relationship);
    }

    /** A duplicate element taken out of its folder. */
    private static final class ElementRemoval implements RecordedSteps.Step {
        private final IArchimateElement element;
        private final IFolder folder;
        private final int index;

        ElementRemoval(IArchimateElement element) {
            this.element = element;
            this.folder = (IFolder) element.eContainer();
            this.index = folder.getElements().indexOf(element);
        }

        @Override
        public void apply() {
            folder.getElements().remove(element);
        }

        @Override
        public void revert() {
            EList<EObject> elements = folder.getElements();
            if (!elements.contains(element)) {
                elements.add(Math.min(index, elements.size()), element);
            }
        }
    }

    /** The target's documentation replaced; undo restores the text it held, byte for byte. */
    private static final class DocumentationStep implements RecordedSteps.Step {
        private final IDocumentable element;
        private final String before;
        private final String after;

        DocumentationStep(IDocumentable element, String before, String after) {
            this.element = element;
            this.before = before;
            this.after = after;
        }

        @Override
        public void apply() {
            element.setDocumentation(after);
        }

        @Override
        public void revert() {
            element.setDocumentation(before);
        }
    }

    /** A copy of a property added to the end of the target's list; undo removes that copy. */
    private static final class PropertyAdd implements RecordedSteps.Step {
        private final IProperties element;
        private final IProperty property;

        PropertyAdd(IProperties element, IProperty property) {
            this.element = element;
            this.property = property;
        }

        @Override
        public void apply() {
            element.getProperties().add(property);
        }

        @Override
        public void revert() {
            element.getProperties().remove(property);
        }
    }

    /** A survivor's blank name filled in; undo restores what it held. */
    private static final class NameStep implements RecordedSteps.Step {
        private final INameable named;
        private final String before;
        private final String after;

        NameStep(INameable named, String before, String after) {
            this.named = named;
            this.before = before;
            this.after = after;
        }

        @Override
        public void apply() {
            named.setName(after);
        }

        @Override
        public void revert() {
            named.setName(before);
        }
    }

    /**
     * A view connection made to draw another relationship. It leaves both ends' lists and comes
     * back at the index it had in each, so an open view redraws it; what it joins is unchanged.
     */
    private static final class ConnectionRelationshipStep implements RecordedSteps.Step {
        private final IDiagramModelArchimateConnection connection;
        private final IArchimateRelationship from;
        private final IArchimateRelationship to;
        private final IConnectable source;
        private final IConnectable target;
        private final int sourceIndex;
        private final int targetIndex;

        ConnectionRelationshipStep(IDiagramModelArchimateConnection connection, IArchimateRelationship to) {
            this.connection = connection;
            this.from = connection.getArchimateRelationship();
            this.to = to;
            this.source = connection.getSource();
            this.target = connection.getTarget();
            this.sourceIndex = source.getSourceConnections().indexOf(connection);
            this.targetIndex = target.getTargetConnections().indexOf(connection);
        }

        @Override
        public void apply() {
            draw(to);
        }

        @Override
        public void revert() {
            draw(from);
        }

        private void draw(IArchimateRelationship relationship) {
            connection.disconnect();
            connection.setArchimateRelationship(relationship);
            source.addConnection(connection);
            target.addConnection(connection);
            move(source.getSourceConnections(), connection, sourceIndex);
            move(target.getTargetConnections(), connection, targetIndex);
        }
    }

    /**
     * A connection that hung on a removed connection, put on new ends. The end that stays keeps
     * the index the connection had in its list; undo puts it back on both old ends at their
     * indexes.
     */
    private static final class ConnectionEndStep implements RecordedSteps.Step {
        private final IDiagramModelConnection connection;
        private final IConnectable fromSource;
        private final IConnectable fromTarget;
        private final IConnectable toSource;
        private final IConnectable toTarget;
        private final int sourceIndex;
        private final int targetIndex;

        ConnectionEndStep(IDiagramModelConnection connection, IConnectable toSource, IConnectable toTarget) {
            this.connection = connection;
            this.fromSource = connection.getSource();
            this.fromTarget = connection.getTarget();
            this.toSource = toSource;
            this.toTarget = toTarget;
            this.sourceIndex = fromSource.getSourceConnections().indexOf(connection);
            this.targetIndex = fromTarget.getTargetConnections().indexOf(connection);
        }

        @Override
        public void apply() {
            attach(toSource, toTarget);
        }

        @Override
        public void revert() {
            attach(fromSource, fromTarget);
        }

        private void attach(IConnectable source, IConnectable target) {
            connection.disconnect();
            connection.setSource(source);
            connection.setTarget(target);
            source.addConnection(connection);
            target.addConnection(connection);
            if (source == fromSource) {
                move(source.getSourceConnections(), connection, sourceIndex);
            }
            if (target == fromTarget) {
                move(target.getTargetConnections(), connection, targetIndex);
            }
        }
    }

    private static void move(EList<IDiagramModelConnection> list, IDiagramModelConnection connection, int index) {
        int now = list.indexOf(connection);
        if (index >= 0 && now >= 0 && now != index && index < list.size()) {
            list.move(index, connection);
        }
    }
}
