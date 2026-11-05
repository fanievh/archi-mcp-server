package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

import org.eclipse.emf.ecore.EClass;

import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IInfluenceRelationship;
import com.archimatetool.model.IProperty;

import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.ParallelNotCollapsed;

/**
 * Which of the relationships a merge makes parallel are folded into one, decided from the model
 * before anything is written.
 *
 * <p>A <em>group</em> is every relationship that, once the merge has moved its ends, has one type,
 * one source and one target, where at least one was re-pointed by the merge. Its survivor is the
 * first relationship the merge did not touch (one already on the target), in model order; failing
 * that, the re-pointed one whose moved end came first in the call, then the first in model order.
 * Only re-pointed relationships are ever folded. Each is compared with the survivor as the folds
 * before it left it, so a name an earlier fold filled in counts.</p>
 *
 * <p>A relationship folds only if nothing that differs between the two would be lost: each name
 * and each documentation is blank on one side or equal on both; the property lists are empty on
 * one side or the same key/value pairs, repeats counted and order ignored; and the type's own
 * attributes and the specializations are equal. A survivor's blank name or documentation, and an
 * empty property list, take the folded one's. Features are neither compared nor copied. A
 * relationship is not folded either when a relationship the merge keeps joins it to the survivor,
 * or to one already folded into it: the move would join the survivor to itself.</p>
 *
 * <p>Each view connection of a folded relationship is removed where the survivor is already drawn
 * between the same two objects in the same direction (counting a connection an earlier fold
 * re-pointed), and otherwise re-pointed to the survivor. What hangs on the folded relationship
 * moves to the survivor: relationships by their end, and the connections that hung on a removed
 * connection onto the survivor's connection there. A fold of a relationship whose end is another
 * relationship decides its connections after the folds of its ends, with their connections as
 * those folds leave them. A pair that only such a move makes parallel is reported and not folded
 * in turn.</p>
 */
final class ParallelRelationshipFolds {

    static final String NAME_DIFFERS = "NAME_DIFFERS";
    static final String DOCUMENTATION_DIFFERS = "DOCUMENTATION_DIFFERS";
    static final String PROPERTIES_DIFFER = "PROPERTIES_DIFFER";
    static final String ATTRIBUTE_DIFFERS = "ATTRIBUTE_DIFFERS";
    static final String JOINED_TO_SURVIVOR = "JOINED_TO_SURVIVOR";
    static final String CREATED_BY_COLLAPSE = "CREATED_BY_COLLAPSE";

    /** One relationship folded into its group's survivor, and everything that fold changes. */
    static final class Fold {
        final MergeElementsPlan.ClusterPlan owner;
        final IArchimateRelationship survivor;
        final IArchimateRelationship dropped;
        final List<String> filled = new ArrayList<>();
        String nameAfter;
        String documentationAfter;
        final List<IProperty> propertiesCopied = new ArrayList<>();
        final List<IDiagramModelArchimateConnection> repointed = new ArrayList<>();
        final List<IDiagramModelArchimateConnection> removed = new ArrayList<>();
        final List<IArchimateRelationship> moved = new ArrayList<>();
        final List<IDiagramModelConnection> reattached = new ArrayList<>();

        Fold(MergeElementsPlan.ClusterPlan owner, IArchimateRelationship survivor, IArchimateRelationship dropped) {
            this.owner = owner;
            this.survivor = survivor;
            this.dropped = dropped;
        }
    }

    /** An end of a relationship that hung on a folded one, moved to the survivor. */
    record Move(IArchimateRelationship relationship, boolean sourceEnd, IArchimateRelationship to) {}

    /** A connection that hung on a removed connection, given the ends it hangs on afterwards. */
    record Reattach(IDiagramModelConnection connection, IConnectable source, IConnectable target) {}

    final List<Fold> folds = new ArrayList<>();
    final Map<IArchimateRelationship, IArchimateRelationship> foldedInto = new LinkedHashMap<>();
    final List<Move> moves = new ArrayList<>();
    final List<Reattach> reattaches = new ArrayList<>();
    /** The relationships moved by a fold, each with the cluster of the first fold that moved it. */
    final Map<IArchimateRelationship, MergeElementsPlan.ClusterPlan> movedBy = new LinkedHashMap<>();
    /** Each moved relationship's type and ends before the move, as the merge leaves them. */
    final Map<IArchimateRelationship, List<Object>> groupBeforeMoves = new IdentityHashMap<>();

    private ParallelRelationshipFolds() {}

    /**
     * Plans the folds among {@code repointed}, the relationships the merge keeps with an end
     * moved, in model order. {@code removed} is what the merge deletes anyway, and
     * {@code connectionsRemoved} the view connections it deletes; neither takes part.
     * {@code position} gives each duplicate its place in the call, and {@code owner} the cluster
     * that reports a group with the given ends.
     */
    static ParallelRelationshipFolds plan(List<IArchimateRelationship> repointed,
            Set<IArchimateRelationship> removed, Set<IDiagramModelConnection> connectionsRemoved,
            Map<IArchimateElement, IArchimateElement> mergeInto, Map<IArchimateElement, Integer> position,
            BiFunction<IArchimateConcept, IArchimateConcept, MergeElementsPlan.ClusterPlan> owner,
            MergeElementsPlan.DiagramIndex diagrams) {
        ParallelRelationshipFolds out = new ParallelRelationshipFolds();
        Set<IArchimateRelationship> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        touched.addAll(repointed);
        Map<List<Object>, List<IArchimateRelationship>> groups = new LinkedHashMap<>();
        for (IArchimateRelationship relationship : repointed) {
            groups.computeIfAbsent(List.of(relationship.eClass(), endOf(relationship.getSource(), mergeInto),
                    endOf(relationship.getTarget(), mergeInto)), k -> new ArrayList<>()).add(relationship);
        }
        Comparator<IArchimateRelationship> callOrder = Comparator
                .<IArchimateRelationship>comparingInt(r -> callPosition(r, mergeInto, position))
                .thenComparing(diagrams.modelOrder());
        for (Map.Entry<List<Object>, List<IArchimateRelationship>> group : groups.entrySet()) {
            EClass type = (EClass) group.getKey().get(0);
            IArchimateConcept source = (IArchimateConcept) group.getKey().get(1);
            IArchimateConcept target = (IArchimateConcept) group.getKey().get(2);
            List<IArchimateRelationship> members = new ArrayList<>(group.getValue());
            members.sort(callOrder);
            List<IArchimateRelationship> untouched = new ArrayList<>();
            for (IArchimateRelationship other : source.getSourceRelationships()) {
                if (other.eClass() == type && other.getTarget() == target && !touched.contains(other)
                        && !removed.contains(other)) {
                    untouched.add(other);
                }
            }
            untouched.sort(diagrams.modelOrder());
            IArchimateRelationship survivor = untouched.isEmpty() ? members.remove(0) : untouched.get(0);
            if (members.isEmpty()) {
                continue;
            }
            out.compare(owner.apply(source, target), survivor, members, removed);
        }
        out.planConnections(connectionsRemoved, diagrams);
        out.planMoves(removed, mergeInto, diagrams);
        return out;
    }

    /** The survivor's name, documentation and properties as the folds so far left them. */
    private static final class Standing {
        String name;
        String documentation;
        List<List<String>> properties;

        Standing(IArchimateRelationship survivor) {
            name = survivor.getName();
            documentation = survivor.getDocumentation();
            properties = pairs(survivor.getProperties());
        }
    }

    private void compare(MergeElementsPlan.ClusterPlan owner, IArchimateRelationship survivor,
            List<IArchimateRelationship> members, Set<IArchimateRelationship> removed) {
        Standing standing = new Standing(survivor);
        for (IArchimateRelationship member : members) {
            List<List<String>> properties = pairs(member.getProperties());
            List<String> reasons = new ArrayList<>();
            if (!agrees(standing.name, member.getName())) {
                reasons.add(NAME_DIFFERS);
            }
            if (!agrees(standing.documentation, member.getDocumentation())) {
                reasons.add(DOCUMENTATION_DIFFERS);
            }
            if (!standing.properties.isEmpty() && !properties.isEmpty()
                    && !counted(standing.properties).equals(counted(properties))) {
                reasons.add(PROPERTIES_DIFFER);
            }
            if (!sameAttributes(survivor, member)) {
                reasons.add(ATTRIBUTE_DIFFERS);
            }
            if (joinedToSurvivor(member, survivor, removed)) {
                reasons.add(JOINED_TO_SURVIVOR);
            }
            if (!reasons.isEmpty()) {
                owner.notCollapsed.add(new ParallelNotCollapsed(member.getId(), survivor.getId(),
                        member.eClass().getName(), List.copyOf(reasons)));
                continue;
            }
            Fold fold = new Fold(owner, survivor, member);
            if (blank(standing.name) && !blank(member.getName())) {
                fold.nameAfter = member.getName();
                standing.name = member.getName();
                fold.filled.add("name");
            }
            if (blank(standing.documentation) && !blank(member.getDocumentation())) {
                fold.documentationAfter = member.getDocumentation();
                standing.documentation = member.getDocumentation();
                fold.filled.add("documentation");
            }
            if (standing.properties.isEmpty() && !properties.isEmpty()) {
                fold.propertiesCopied.addAll(member.getProperties());
                standing.properties = properties;
                fold.filled.add("properties");
            }
            folds.add(fold);
            owner.folds.add(fold);
            foldedInto.put(member, survivor);
        }
    }

    /**
     * Whether a relationship the merge keeps joins {@code member} to {@code survivor}, or to a
     * relationship already folded into it, so that moving its end would join the survivor to
     * itself. One that already joins {@code member} to itself only moves with it.
     */
    private boolean joinedToSurvivor(IArchimateRelationship member, IArchimateRelationship survivor,
            Set<IArchimateRelationship> removed) {
        List<IArchimateRelationship> attached = new ArrayList<>(member.getSourceRelationships());
        attached.addAll(member.getTargetRelationships());
        for (IArchimateRelationship joining : attached) {
            if (removed.contains(joining)) {
                continue;
            }
            IArchimateConcept other = joining.getSource() == member ? joining.getTarget() : joining.getSource();
            if (other == survivor || foldedInto.get(other) == survivor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decides each folded relationship's view connections, in fold order and then in the order
     * the views hold them, so a connection re-pointed by an earlier fold counts as the survivor
     * drawn there for a later one. A fold whose relationship hangs on another relationship comes
     * after the folds of what it hangs on, and sees each connection a fold removes as the
     * connection that replaces it.
     */
    private void planConnections(Set<IDiagramModelConnection> connectionsRemoved,
            MergeElementsPlan.DiagramIndex diagrams) {
        Map<IDiagramModelArchimateConnection, IArchimateRelationship> drawsAfter = new IdentityHashMap<>();
        Set<IDiagramModelConnection> gone = Collections.newSetFromMap(new IdentityHashMap<>());
        gone.addAll(connectionsRemoved);
        Map<IConnectable, IConnectable> replacedBy = new IdentityHashMap<>();
        List<IDiagramModelArchimateConnection> removedInOrder = new ArrayList<>();
        Map<IDiagramModelConnection, Fold> removedBy = new IdentityHashMap<>();
        List<Fold> inOrder = new ArrayList<>(folds);
        inOrder.sort(Comparator.comparingInt(fold -> depth(fold.dropped, new LinkedHashSet<>())));
        for (Fold fold : inOrder) {
            for (IDiagramModelArchimateConnection drawn : diagrams.connectionsOf(fold.dropped)) {
                if (gone.contains(drawn)) {
                    continue;
                }
                IDiagramModelConnection there = drawnThere(replacedBy.getOrDefault(drawn.getSource(), drawn.getSource()),
                        replacedBy.getOrDefault(drawn.getTarget(), drawn.getTarget()), fold.survivor, drawsAfter, gone,
                        replacedBy, removedInOrder);
                if (there != null) {
                    fold.removed.add(drawn);
                    gone.add(drawn);
                    replacedBy.put(drawn, there);
                    removedInOrder.add(drawn);
                    removedBy.put(drawn, fold);
                } else {
                    fold.repointed.add(drawn);
                    drawsAfter.put(drawn, fold.survivor);
                }
            }
        }
        Set<IDiagramModelConnection> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IDiagramModelArchimateConnection removedConnection : removedInOrder) {
            List<IDiagramModelConnection> hanging = new ArrayList<>(removedConnection.getSourceConnections());
            hanging.addAll(removedConnection.getTargetConnections());
            for (IDiagramModelConnection connection : hanging) {
                if (gone.contains(connection) || !seen.add(connection)) {
                    continue;
                }
                IConnectable source = replacedBy.getOrDefault(connection.getSource(), connection.getSource());
                IConnectable target = replacedBy.getOrDefault(connection.getTarget(), connection.getTarget());
                reattaches.add(new Reattach(connection, source, target));
                removedBy.get(removedConnection).reattached.add(connection);
            }
        }
    }

    /**
     * The first connection that will leave {@code source} for {@code target} and draw
     * {@code survivor}, or null: those on {@code source} in its own order, then those that will
     * move onto it from a connection removed before, in the order they were removed.
     */
    private static IDiagramModelConnection drawnThere(IConnectable source, IConnectable target,
            IArchimateRelationship survivor, Map<IDiagramModelArchimateConnection, IArchimateRelationship> drawsAfter,
            Set<IDiagramModelConnection> gone, Map<IConnectable, IConnectable> replacedBy,
            List<IDiagramModelArchimateConnection> removedInOrder) {
        List<IDiagramModelConnection> leaving = new ArrayList<>(source.getSourceConnections());
        for (IDiagramModelArchimateConnection removedConnection : removedInOrder) {
            if (replacedBy.get(removedConnection) == source) {
                leaving.addAll(removedConnection.getSourceConnections());
            }
        }
        for (IDiagramModelConnection connection : leaving) {
            if (replacedBy.getOrDefault(connection.getTarget(), connection.getTarget()) == target
                    && !gone.contains(connection)
                    && connection instanceof IDiagramModelArchimateConnection drawn
                    && drawsAfter.getOrDefault(drawn, drawn.getArchimateRelationship()) == survivor) {
                return connection;
            }
        }
        return null;
    }

    /** How deep {@code concept} hangs: 0 for an element, one more than its deepest end for a relationship. */
    private static int depth(IArchimateConcept concept, Set<IArchimateConcept> visiting) {
        if (!(concept instanceof IArchimateRelationship relationship) || !visiting.add(concept)) {
            return 0;
        }
        return 1 + Math.max(depth(relationship.getSource(), visiting), depth(relationship.getTarget(), visiting));
    }

    /** Moves each end that is a folded relationship to its survivor. */
    private void planMoves(Set<IArchimateRelationship> removed, Map<IArchimateElement, IArchimateElement> mergeInto,
            MergeElementsPlan.DiagramIndex diagrams) {
        for (Fold fold : folds) {
            Set<IArchimateRelationship> attached = new LinkedHashSet<>(fold.dropped.getSourceRelationships());
            attached.addAll(fold.dropped.getTargetRelationships());
            for (IArchimateRelationship hanging : diagrams.inModelOrder(attached)) {
                if (foldedInto.containsKey(hanging) || removed.contains(hanging)) {
                    continue;
                }
                if (hanging.getSource() == fold.dropped) {
                    moves.add(new Move(hanging, true, fold.survivor));
                }
                if (hanging.getTarget() == fold.dropped) {
                    moves.add(new Move(hanging, false, fold.survivor));
                }
                fold.moved.add(hanging);
                movedBy.putIfAbsent(hanging, fold.owner);
                groupBeforeMoves.putIfAbsent(hanging, List.of(hanging.eClass(),
                        endOf(hanging.getSource(), mergeInto), endOf(hanging.getTarget(), mergeInto)));
            }
        }
    }

    /** Where an end lands once the duplicates are merged. */
    static IArchimateConcept endOf(IArchimateConcept end, Map<IArchimateElement, IArchimateElement> mergeInto) {
        IArchimateElement into = end instanceof IArchimateElement element ? mergeInto.get(element) : null;
        return into != null ? into : end;
    }

    /** The place in the call of the duplicate a re-pointed relationship's moved end came from. */
    private static int callPosition(IArchimateRelationship relationship,
            Map<IArchimateElement, IArchimateElement> mergeInto, Map<IArchimateElement, Integer> position) {
        if (relationship.getSource() instanceof IArchimateElement source && mergeInto.containsKey(source)) {
            return position.get(source);
        }
        if (relationship.getTarget() instanceof IArchimateElement target && mergeInto.containsKey(target)) {
            return position.get(target);
        }
        return Integer.MAX_VALUE;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static boolean agrees(String a, String b) {
        return blank(a) || blank(b) || a.equals(b);
    }

    private static List<List<String>> pairs(List<IProperty> properties) {
        List<List<String>> out = new ArrayList<>(properties.size());
        for (IProperty property : properties) {
            out.add(java.util.Arrays.asList(property.getKey(), property.getValue()));
        }
        return out;
    }

    /** The pairs as a multiset: each pair with how often it occurs. */
    private static Map<List<String>, Integer> counted(List<List<String>> pairs) {
        Map<List<String>, Integer> counts = new HashMap<>();
        for (List<String> pair : pairs) {
            counts.merge(pair, 1, Integer::sum);
        }
        return counts;
    }

    /** The attributes a relationship type adds, and the specializations, are the same on both. */
    private static boolean sameAttributes(IArchimateRelationship a, IArchimateRelationship b) {
        if (a instanceof IAccessRelationship access && access.getAccessType()
                != ((IAccessRelationship) b).getAccessType()) {
            return false;
        }
        if (a instanceof IInfluenceRelationship influence
                && !Objects.equals(influence.getStrength(), ((IInfluenceRelationship) b).getStrength())) {
            return false;
        }
        if (a instanceof IAssociationRelationship association
                && association.isDirected() != ((IAssociationRelationship) b).isDirected()) {
            return false;
        }
        if (a.getProfiles().size() != b.getProfiles().size()) {
            return false;
        }
        for (int i = 0; i < a.getProfiles().size(); i++) {
            if (a.getProfiles().get(i) != b.getProfiles().get(i)) {
                return false;
            }
        }
        return true;
    }
}
