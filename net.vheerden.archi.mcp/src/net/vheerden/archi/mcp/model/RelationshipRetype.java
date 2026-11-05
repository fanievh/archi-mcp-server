package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.osgi.util.NLS;

import com.archimatetool.editor.model.commands.Messages;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IFolder;

/**
 * Changes a relationship's type in place, as Archi's editor does when a type change leaves a
 * relationship it no longer allows: the relationship is replaced by one of the new type, and
 * everything that referred to it is re-pointed rather than recreated.
 *
 * <p>The replacement keeps the old relationship's id, name, documentation, and copies of its
 * properties and features; it joins the same ends and takes the old relationship's place in its
 * folder. Every view connection that drew the old relationship draws the new one, keeping its id,
 * ends, bendpoints, styling and its place in both ends' connection lists; every relationship whose
 * source or target was the old relationship has the new one there. The specialization is not
 * carried over, since it belongs to one type, and neither are the old type's own attributes.</p>
 *
 * <p>The writes are made one at a time through the caller's recorder, each as a {@link RecordedSteps.Step} that
 * knows how to reverse itself, so the caller can undo, redo and roll back the change with the rest
 * of its own. The caller decides whether the new type is allowed.</p>
 */
final class RelationshipRetype {

    private RelationshipRetype() {}

    /** One relationship to retype and the view connections that draw it, read before anything is written. */
    static final class Plan {
        final IArchimateRelationship relationship;
        final EClass to;
        final List<IDiagramModelArchimateConnection> connections = new ArrayList<>();

        private Plan(IArchimateRelationship relationship, EClass to) {
            this.relationship = relationship;
            this.to = to;
        }

        /**
         * A plan for each relationship, in the order given, each to become {@code to}. The
         * connections keep model order: views in the order the model holds them, and their
         * connections in the order each view holds them.
         */
        static List<Plan> read(IArchimateModel model, List<IArchimateRelationship> relationships, EClass to) {
            List<Plan> plans = new ArrayList<>();
            Map<IArchimateRelationship, Plan> byRelationship = new IdentityHashMap<>();
            for (IArchimateRelationship relationship : relationships) {
                Plan plan = new Plan(relationship, to);
                plans.add(plan);
                byRelationship.put(relationship, plan);
            }
            if (byRelationship.isEmpty()) {
                return plans;
            }
            for (IDiagramModel view : model.getDiagramModels()) {
                for (TreeIterator<EObject> it = view.eAllContents(); it.hasNext();) {
                    if (it.next() instanceof IDiagramModelArchimateConnection connection) {
                        Plan owner = byRelationship.get(connection.getArchimateRelationship());
                        if (owner != null) {
                            owner.connections.add(connection);
                        }
                    }
                }
            }
            return plans;
        }
    }

    /**
     * The new relationship: the old one's id, name, documentation, and copies of its properties
     * and features, which the old relationship keeps for undo. With {@code note}, the
     * documentation starts with Archi's own "(Changed from X)" line, X being {@code label}'s name
     * for the old type, exactly as Archi's editor writes it.
     */
    static IArchimateRelationship replacementFor(IArchimateRelationship old, EClass type, boolean note,
            Function<EClass, String> label) {
        IArchimateRelationship replacement = (IArchimateRelationship) IArchimateFactory.eINSTANCE.create(type);
        replacement.setId(old.getId());
        replacement.setName(old.getName());
        replacement.getProperties().addAll(EcoreUtil.copyAll(old.getProperties()));
        replacement.getFeatures().addAll(EcoreUtil.copyAll(old.getFeatures()));
        String documentation = old.getDocumentation() == null ? "" : old.getDocumentation();
        replacement.setDocumentation(note
                ? NLS.bind(Messages.SetConceptTypeCommandFactory_2 + (documentation.isEmpty() ? "" : "\n\n"),
                        label.apply(old.eClass())) + documentation
                : documentation);
        return replacement;
    }

    /**
     * Writes the retype, each step through {@code record}, which must record the step before
     * applying it. The relationship's ends are read as they are now, so a caller that has already
     * moved them gets a replacement joining the moved ends. The relationships attached to the old
     * one are read now too, so a relationship retyped earlier in the same call is re-pointed as
     * it now stands.
     */
    static void write(Plan plan, IArchimateRelationship replacement, Consumer<RecordedSteps.Step> record) {
        IArchimateRelationship old = plan.relationship;
        record.accept(new RelationshipAdd(old, replacement));
        for (IArchimateRelationship attached : List.copyOf(old.getSourceRelationships())) {
            record.accept(new EndStep(attached, true, replacement));
        }
        for (IArchimateRelationship attached : List.copyOf(old.getTargetRelationships())) {
            record.accept(new EndStep(attached, false, replacement));
        }
        for (IDiagramModelArchimateConnection connection : plan.connections) {
            record.accept(new ConnectionRelationshipStep(connection, replacement));
        }
        if (!old.getSourceRelationships().isEmpty() || !old.getTargetRelationships().isEmpty()
                || !old.getReferencingDiagramComponents().isEmpty()) {
            throw new IllegalStateException("relationship " + CallerText.quoted(old.getId())
                    + " is still referenced after re-pointing");
        }
        record.accept(new RelationshipRemoval(old));
    }

    // ---- the steps ------------------------------------------------------------------------------

    /**
     * The new relationship joined to the old one's ends and put into the old one's folder at the
     * index the old one holds. Undo takes it out and off its ends' lists.
     */
    private static final class RelationshipAdd implements RecordedSteps.Step {
        private final IArchimateRelationship old;
        private final IArchimateRelationship replacement;
        private final IFolder folder;
        private final int index;

        RelationshipAdd(IArchimateRelationship old, IArchimateRelationship replacement) {
            this.old = old;
            this.replacement = replacement;
            this.folder = (IFolder) old.eContainer();
            this.index = folder.getElements().indexOf(old);
        }

        @Override
        public void apply() {
            replacement.connect(old.getSource(), old.getTarget());
            EList<EObject> elements = folder.getElements();
            elements.add(Math.min(index, elements.size()), replacement);
        }

        @Override
        public void revert() {
            folder.getElements().remove(replacement);
            replacement.disconnect();
        }
    }

    /** One end of a relationship moved to another concept. */
    static final class EndStep implements RecordedSteps.Step {
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
     * A view connection made to draw another relationship. It leaves both ends' lists and comes
     * back at the index it had in each, so an open view redraws it; what it joins is unchanged.
     * It is never {@code reconnect()}ed, which would append it.
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
            move(source.getSourceConnections(), sourceIndex);
            move(target.getTargetConnections(), targetIndex);
        }

        private void move(EList<IDiagramModelConnection> list, int index) {
            int now = list.indexOf(connection);
            if (index >= 0 && now >= 0 && now != index && index < list.size()) {
                list.move(index, connection);
            }
        }
    }

    /**
     * The old relationship taken out of its folder and off its ends' lists, as Archi removes one.
     * Undo puts it back at the index it had and on its ends' lists, where it is appended: those
     * lists are not saved, and Archi's own undo appends too.
     */
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
}
