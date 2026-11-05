package net.vheerden.archi.mcp.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.DoublePlacement;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.DroppedRelationship;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.NameDropped;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.ParallelCollapsed;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.ParallelNotCollapsed;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.ParallelPair;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.PropertyConflict;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.SpecializationDiffers;

/**
 * Everything a merge of duplicate elements would change, read from the model without writing to it.
 *
 * <p>The same reading serves both moments a merge is looked at. When the call is proposed or
 * previewed it is the whole answer. When the command runs it is taken again from the model as it
 * is then, so a relationship a human added to a duplicate while a proposal waited is re-pointed
 * too; only the parallel relationships are measured afterwards, from the written model, because
 * they depend on where every end landed.</p>
 *
 * <p>The lists keep model order: views and their objects in the order the model holds them,
 * relationships in the order their folder holds them. Archi's own back-reference sets are hash
 * sets, and reading them directly would let two identical calls report in different orders.</p>
 */
final class MergeElementsPlan {

    /** How the duplicates' properties and documentation reach the target. */
    enum PropertyPolicy {
        UNION("union"), KEEP_TARGET("keep-target"), APPEND("append");

        final String wire;

        PropertyPolicy(String wire) {
            this.wire = wire;
        }
    }

    /** What happens to a relationship between two members of one cluster. */
    enum SelfPolicy {
        DROP("drop"), KEEP("keep");

        final String wire;

        SelfPolicy(String wire) {
            this.wire = wire;
        }
    }

    /** The property key {@code recordMergedIds} writes, one per merged duplicate. */
    static final String MERGED_FROM_KEY = "mergedFrom";

    static final String DROPPED_BY_POLICY = "DROPPED_BY_POLICY";
    static final String INVALID_SELF = "INVALID_SELF";

    /**
     * Whether Archi allows a relationship type between two concepts. Production always passes
     * {@link #ARCHI}, Archi's own rule, so a merge is never stricter nor more forgiving than the
     * editor. The seam exists because that rule loads its matrix from the running platform and
     * cannot answer in a plain JVM; it is consulted only for {@code selfRelationships: keep}.
     */
    interface RelationshipRule {
        boolean allows(IArchimateConcept source, IArchimateConcept target, EClass type);
    }

    static final RelationshipRule ARCHI = ArchimateModelUtils::isValidRelationship;

    record Options(PropertyPolicy properties, SelfPolicy self, boolean recordMergedIds,
            boolean collapseParallel, RelationshipRule rule) {}

    /** A cluster whose ids resolved: the element that survives and those that collapse into it. */
    record Resolved(IArchimateElement target, List<IArchimateElement> sources) {}

    /** A diagram object that shows a duplicate and will show the target. */
    record BoxMove(IDiagramModelArchimateObject box, IArchimateElement to) {}

    /** A relationship end that moves; a null side stays where it is. */
    record EndMove(IArchimateRelationship relationship, IArchimateElement newSource,
            IArchimateElement newTarget) {}

    /** A source property the target receives a copy of. */
    record PropertyCopy(IArchimateElement target, IProperty original) {}

    /** The target's documentation before and after the merge, when it changes. */
    record DocumentationChange(IArchimateElement target, String before, String after) {}

    /** The per-cluster reading, before anything is written. */
    static final class ClusterPlan {
        final Resolved cluster;
        int relationshipsRepointed;
        int diagramObjectsRepointed;
        final List<DroppedRelationship> dropped = new ArrayList<>();
        final List<String> kept = new ArrayList<>();
        final List<String> cascadedRelationshipIds = new ArrayList<>();
        final List<String> cascadedConnectionIds = new ArrayList<>();
        final List<PropertyConflict> conflicts = new ArrayList<>();
        final List<PropertyCopy> copies = new ArrayList<>();
        final List<String> mergedFrom = new ArrayList<>();
        int discarded;
        int documentationAppended;
        DocumentationChange documentation;
        final List<DoublePlacement> doublePlacements = new ArrayList<>();
        final List<SpecializationDiffers> specializationDiffers = new ArrayList<>();
        final List<NameDropped> namesDropped = new ArrayList<>();
        /** Filled from the written model by the command, or by simulation for a preview. */
        List<ParallelPair> parallels = new ArrayList<>();
        /** Under {@code collapseParallel}: the folds this cluster reports, and the pairs it kept. */
        final List<ParallelRelationshipFolds.Fold> folds = new ArrayList<>();
        final List<ParallelNotCollapsed> notCollapsed = new ArrayList<>();
        /** Pairs only a fold's move made parallel, filled with {@code parallels}. */
        List<ParallelNotCollapsed> createdByCollapse = new ArrayList<>();

        ClusterPlan(Resolved cluster) {
            this.cluster = cluster;
        }
    }

    final Options options;
    final List<ClusterPlan> clusters = new ArrayList<>();
    final List<BoxMove> boxes = new ArrayList<>();
    final List<EndMove> ends = new ArrayList<>();
    /** Every relationship this merge removes, dropped first then cascaded, in that order. */
    final List<IArchimateRelationship> relationshipsToRemove = new ArrayList<>();
    /** Every view connection this merge removes, each after any connection attached to it. */
    final List<IDiagramModelConnection> connectionsToRemove = new ArrayList<>();
    /** The relationships still in the model afterwards with at least one end moved. */
    final Set<IArchimateRelationship> repointedKept = new LinkedHashSet<>();
    /** Each duplicate and the target it collapses into. */
    final Map<IArchimateElement, IArchimateElement> mergeInto = new IdentityHashMap<>();
    /** The parallel relationships folded into one, or null unless {@code collapseParallel}. */
    ParallelRelationshipFolds folded;

    private MergeElementsPlan(Options options) {
        this.options = options;
    }

    /**
     * Reads what a merge of {@code resolved} would change in {@code model}. Writes nothing.
     */
    static MergeElementsPlan read(IArchimateModel model, List<Resolved> resolved, Options options) {
        MergeElementsPlan plan = new MergeElementsPlan(options);
        Map<IArchimateElement, ClusterPlan> clusterOf = new IdentityHashMap<>();
        for (Resolved cluster : resolved) {
            ClusterPlan clusterPlan = new ClusterPlan(cluster);
            plan.clusters.add(clusterPlan);
            clusterOf.put(cluster.target(), clusterPlan);
            for (IArchimateElement source : cluster.sources()) {
                clusterOf.put(source, clusterPlan);
                plan.mergeInto.put(source, cluster.target());
            }
        }

        DiagramIndex diagrams = DiagramIndex.of(model, clusterOf.keySet());
        plan.readBoxes(diagrams, clusterOf);
        plan.readRelationships(model, clusterOf, diagrams);
        for (ClusterPlan clusterPlan : plan.clusters) {
            plan.readMetadata(clusterPlan);
        }
        return plan;
    }

    private void readBoxes(DiagramIndex diagrams, Map<IArchimateElement, ClusterPlan> clusterOf) {
        for (Map.Entry<IDiagramModel, List<IDiagramModelArchimateObject>> view
                : diagrams.memberBoxes.entrySet()) {
            Map<ClusterPlan, List<IDiagramModelArchimateObject>> byCluster = new LinkedHashMap<>();
            for (IDiagramModelArchimateObject box : view.getValue()) {
                ClusterPlan clusterPlan = clusterOf.get(box.getArchimateElement());
                byCluster.computeIfAbsent(clusterPlan, k -> new ArrayList<>()).add(box);
                IArchimateElement to = mergeInto.get(box.getArchimateElement());
                if (to != null) {
                    boxes.add(new BoxMove(box, to));
                    clusterPlan.diagramObjectsRepointed++;
                }
            }
            for (Map.Entry<ClusterPlan, List<IDiagramModelArchimateObject>> shown : byCluster.entrySet()) {
                List<IDiagramModelArchimateObject> clusterBoxes = shown.getValue();
                boolean showsADuplicate = clusterBoxes.stream()
                        .anyMatch(box -> mergeInto.containsKey(box.getArchimateElement()));
                if (clusterBoxes.size() > 1 && showsADuplicate) {
                    shown.getKey().doublePlacements.add(new DoublePlacement(view.getKey().getId(),
                            view.getKey().getName(),
                            clusterBoxes.stream().map(IDiagramModelArchimateObject::getId).toList()));
                }
            }
        }
    }

    private void readRelationships(IArchimateModel model, Map<IArchimateElement, ClusterPlan> clusterOf,
            DiagramIndex diagrams) {
        Map<IArchimateRelationship, ClusterPlan> droppedBy = new LinkedHashMap<>();
        // Credited once the removals are known: a relationship the drop cascades over is re-pointed
        // on the way (so every end stays consistent with the boxes) but is not in the model after.
        Map<IArchimateRelationship, List<ClusterPlan>> credit = new LinkedHashMap<>();
        Map<IArchimateRelationship, ClusterPlan> keptBy = new LinkedHashMap<>();
        for (IArchimateRelationship relationship : relationshipsTouching(model, mergeInto.keySet())) {
            IArchimateConcept source = relationship.getSource();
            IArchimateConcept target = relationship.getTarget();
            IArchimateElement newSource = source instanceof IArchimateElement e ? mergeInto.get(e) : null;
            IArchimateElement newTarget = target instanceof IArchimateElement e ? mergeInto.get(e) : null;
            ends.add(new EndMove(relationship, newSource, newTarget));

            ClusterPlan sourceCluster = source instanceof IArchimateElement e ? clusterOf.get(e) : null;
            ClusterPlan targetCluster = target instanceof IArchimateElement e ? clusterOf.get(e) : null;
            // A loop a duplicate already had is not one the merge creates: it moves to the target
            // as the target's own loop stays, whatever the self-relationship policy.
            if (sourceCluster != null && sourceCluster == targetCluster && source != target) {
                IArchimateElement survivor = sourceCluster.cluster.target();
                if (options.self() == SelfPolicy.KEEP
                        && options.rule().allows(survivor, survivor, relationship.eClass())) {
                    keptBy.put(relationship, sourceCluster);
                    credit.put(relationship, List.of(sourceCluster));
                } else {
                    droppedBy.put(relationship, sourceCluster);
                }
                continue;
            }
            List<ClusterPlan> credited = new ArrayList<>(2);
            if (newSource != null) {
                credited.add(sourceCluster);
            }
            if (newTarget != null && targetCluster != sourceCluster) {
                credited.add(targetCluster);
            }
            credit.put(relationship, credited);
        }
        Set<IArchimateRelationship> removed = readRemovals(droppedBy, diagrams);
        if (options.collapseParallel()) {
            List<IArchimateRelationship> candidates = new ArrayList<>();
            for (IArchimateRelationship relationship : credit.keySet()) {
                if (!removed.contains(relationship)) {
                    candidates.add(relationship);
                }
            }
            Map<IArchimateElement, Integer> position = new IdentityHashMap<>();
            for (ClusterPlan clusterPlan : clusters) {
                for (IArchimateElement source : clusterPlan.cluster.sources()) {
                    position.put(source, position.size());
                }
            }
            Set<IDiagramModelConnection> connectionsRemoved = Collections.newSetFromMap(new IdentityHashMap<>());
            connectionsRemoved.addAll(connectionsToRemove);
            folded = ParallelRelationshipFolds.plan(candidates, removed, connectionsRemoved, mergeInto, position,
                    this::ownerOf, diagrams);
        }
        for (Map.Entry<IArchimateRelationship, List<ClusterPlan>> entry : credit.entrySet()) {
            // A folded relationship is re-pointed on the way, like a cascaded one, but is gone after.
            if (removed.contains(entry.getKey())
                    || folded != null && folded.foldedInto.containsKey(entry.getKey())) {
                continue;
            }
            repointedKept.add(entry.getKey());
            for (ClusterPlan credited : entry.getValue()) {
                credited.relationshipsRepointed++;
            }
            ClusterPlan keeper = keptBy.get(entry.getKey());
            if (keeper != null) {
                keeper.kept.add(entry.getKey().getId());
            }
        }
    }

    /**
     * Dropping a relationship takes with it, as deleting it in Archi does, every relationship
     * attached to it (at any depth) and every view connection that draws one of them or hangs on
     * one that is removed.
     */
    private Set<IArchimateRelationship> readRemovals(Map<IArchimateRelationship, ClusterPlan> droppedBy,
            DiagramIndex diagrams) {
        Map<IArchimateRelationship, ClusterPlan> removedBy = new LinkedHashMap<>(droppedBy);
        Deque<IArchimateRelationship> pending = new ArrayDeque<>(droppedBy.keySet());
        List<IArchimateRelationship> cascaded = new ArrayList<>();
        while (!pending.isEmpty()) {
            IArchimateRelationship removed = pending.poll();
            ClusterPlan owner = removedBy.get(removed);
            Set<IArchimateRelationship> attached = new LinkedHashSet<>(removed.getSourceRelationships());
            attached.addAll(removed.getTargetRelationships());
            for (IArchimateRelationship hanging : diagrams.inModelOrder(attached)) {
                if (!removedBy.containsKey(hanging)) {
                    removedBy.put(hanging, owner);
                    cascaded.add(hanging);
                    pending.add(hanging);
                    owner.cascadedRelationshipIds.add(hanging.getId());
                }
            }
        }
        Set<IDiagramModelConnection> seen = new LinkedHashSet<>();
        for (Map.Entry<IArchimateRelationship, ClusterPlan> entry : removedBy.entrySet()) {
            IArchimateRelationship relationship = entry.getKey();
            ClusterPlan owner = entry.getValue();
            List<String> ownIds = new ArrayList<>();
            for (IDiagramModelArchimateConnection drawn : diagrams.connectionsOf(relationship)) {
                collectConnection(drawn, seen, ownIds, owner, droppedBy.containsKey(relationship));
            }
            if (droppedBy.containsKey(relationship)) {
                // Under keep, a self-relationship is dropped only because the rule refused it.
                String reason = options.self() == SelfPolicy.KEEP ? INVALID_SELF : DROPPED_BY_POLICY;
                owner.dropped.add(new DroppedRelationship(relationship.getId(),
                        relationship.eClass().getName(), reason, List.copyOf(ownIds)));
            }
        }
        relationshipsToRemove.addAll(droppedBy.keySet());
        relationshipsToRemove.addAll(cascaded);
        return removedBy.keySet();
    }

    /**
     * Queues a connection for removal after every connection attached to it, so none is ever left
     * hanging on one already gone. A connection the dropped relationship draws itself is reported
     * with that relationship; everything else removed on its account is reported as cascaded.
     */
    private void collectConnection(IDiagramModelConnection connection, Set<IDiagramModelConnection> seen,
            List<String> ownIds, ClusterPlan owner, boolean own) {
        if (!seen.add(connection)) {
            return;
        }
        List<IDiagramModelConnection> attached = new ArrayList<>(connection.getSourceConnections());
        attached.addAll(connection.getTargetConnections());
        for (IDiagramModelConnection hanging : attached) {
            collectConnection(hanging, seen, ownIds, owner, false);
        }
        connectionsToRemove.add(connection);
        if (own) {
            ownIds.add(connection.getId());
        } else {
            owner.cascadedConnectionIds.add(connection.getId());
        }
    }

    private void readMetadata(ClusterPlan clusterPlan) {
        IArchimateElement target = clusterPlan.cluster.target();
        List<String[]> held = new ArrayList<>();
        for (IProperty property : target.getProperties()) {
            held.add(new String[] {property.getKey(), property.getValue()});
        }
        String documentation = target.getDocumentation() == null ? "" : target.getDocumentation();
        String before = documentation;
        IProfile targetProfile = target.getPrimaryProfile();

        for (IArchimateElement source : clusterPlan.cluster.sources()) {
            if (!Objects.equals(source.getName(), target.getName())) {
                clusterPlan.namesDropped.add(new NameDropped(source.getId(), source.getName()));
            }
            IProfile sourceProfile = source.getPrimaryProfile();
            if (sourceProfile != targetProfile) {
                clusterPlan.specializationDiffers.add(new SpecializationDiffers(source.getId(),
                        sourceProfile == null ? null : sourceProfile.getName(),
                        targetProfile == null ? null : targetProfile.getName()));
            }

            if (options.properties() == PropertyPolicy.KEEP_TARGET) {
                clusterPlan.discarded += source.getProperties().size();
                continue;
            }
            for (IProperty property : source.getProperties()) {
                if (options.properties() == PropertyPolicy.UNION) {
                    String[] sameKey = null;
                    boolean exact = false;
                    for (String[] pair : held) {
                        if (Objects.equals(pair[0], property.getKey())) {
                            if (sameKey == null) {
                                sameKey = pair;
                            }
                            if (Objects.equals(pair[1], property.getValue())) {
                                exact = true;
                            }
                        }
                    }
                    if (exact) {
                        continue;
                    }
                    // Lineage from an earlier merge is a list, not a value to keep one of.
                    if (sameKey != null && !MERGED_FROM_KEY.equals(property.getKey())) {
                        clusterPlan.conflicts.add(new PropertyConflict(property.getKey(), sameKey[1],
                                property.getValue(), source.getId()));
                        continue;
                    }
                }
                held.add(new String[] {property.getKey(), property.getValue()});
                clusterPlan.copies.add(new PropertyCopy(target, property));
            }
            String appended = appendDocumentation(documentation, source);
            if (!appended.equals(documentation)) {
                documentation = appended;
                clusterPlan.documentationAppended++;
            }
        }
        if (options.recordMergedIds()) {
            for (IArchimateElement source : clusterPlan.cluster.sources()) {
                clusterPlan.mergedFrom.add(source.getId());
            }
        }
        if (!documentation.equals(before)) {
            clusterPlan.documentation = new DocumentationChange(target, target.getDocumentation(),
                    documentation);
        }
    }

    /**
     * The target's documentation with {@code source}'s added, or unchanged when the source has
     * none, says exactly what the target says, or was already added as a block of its own.
     */
    static String appendDocumentation(String current, IArchimateElement source) {
        String text = source.getDocumentation();
        if (text == null || text.isBlank() || text.equals(current) || holdsBlock(current, text)) {
            return current;
        }
        String separator = current.isBlank() ? "" : "\n\n";
        String name = source.getName() == null || source.getName().isBlank() ? "" : source.getName() + " ";
        return current + separator + "from " + name + "[" + source.getId() + "]:\n" + text;
    }

    /** Whether {@code current} already carries {@code text} as the body of an appended block. */
    private static boolean holdsBlock(String current, String text) {
        String marker = "]:\n" + text;
        int at = current.indexOf(marker);
        while (at >= 0) {
            int end = at + marker.length();
            if (end == current.length() || current.startsWith("\n\n", end)) {
                return true;
            }
            at = current.indexOf(marker, at + 1);
        }
        return false;
    }

    /**
     * Fills each cluster's {@code parallels}: every pair of relationships of one type that share
     * both ends, where at least one of the two is a relationship this merge re-pointed. A pair
     * that was parallel before and is untouched by the merge is not reported.
     *
     * <p>{@code endOf} says where an end is, and {@code leaving} which relationships leave a
     * concept. On the written model the first is the identity and the second the concept's own
     * list; for a preview they follow the merge, so a duplicate's relationships count as leaving
     * the target they will leave. A pair is reported once, under the first cluster in the call
     * whose target is one of its ends.</p>
     */
    void fillParallels(Function<IArchimateConcept, IArchimateConcept> endOf,
            Function<IArchimateConcept, Collection<IArchimateRelationship>> leaving) {
        for (ClusterPlan c : clusters) {
            c.parallels = new ArrayList<>();
            c.createdByCollapse = new ArrayList<>();
        }
        Set<IArchimateRelationship> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        removed.addAll(relationshipsToRemove);
        // What a fold moved can be parallel to something only because it moved.
        Set<IArchimateRelationship> looked = new LinkedHashSet<>(repointedKept);
        Map<IArchimateRelationship, ClusterPlan> movedBy = Map.of();
        if (folded != null) {
            removed.addAll(folded.foldedInto.keySet());
            movedBy = folded.movedBy;
            looked.addAll(movedBy.keySet());
        }
        Set<String> seen = new HashSet<>();
        for (IArchimateRelationship relationship : looked) {
            IArchimateConcept source = endOf.apply(relationship.getSource());
            IArchimateConcept target = endOf.apply(relationship.getTarget());
            for (IArchimateRelationship other : new LinkedHashSet<>(leaving.apply(source))) {
                if (other == relationship || removed.contains(other)
                        || other.eClass() != relationship.eClass()
                        || endOf.apply(other.getSource()) != source
                        || endOf.apply(other.getTarget()) != target) {
                    continue;
                }
                String key = relationship.getId().compareTo(other.getId()) < 0
                        ? relationship.getId() + "\n" + other.getId()
                        : other.getId() + "\n" + relationship.getId();
                if (!seen.add(key)) {
                    continue;
                }
                IArchimateRelationship moved = movedBy.containsKey(relationship) ? relationship
                        : movedBy.containsKey(other) ? other : null;
                ClusterPlan owner = ownerOf(source, target);
                if (owner == null) {
                    owner = movedBy.get(moved);
                }
                owner.parallels.add(new ParallelPair(List.of(relationship.getId(), other.getId()),
                        relationship.eClass().getName()));
                // Two that were parallel already before the move hung on the folded one together.
                if (moved != null && !(movedBy.containsKey(relationship) && movedBy.containsKey(other)
                        && folded.groupBeforeMoves.get(relationship).equals(folded.groupBeforeMoves.get(other)))) {
                    owner.createdByCollapse.add(new ParallelNotCollapsed(moved.getId(),
                            (moved == relationship ? other : relationship).getId(), relationship.eClass().getName(),
                            List.of(ParallelRelationshipFolds.CREATED_BY_COLLAPSE)));
                }
            }
        }
    }

    /**
     * The cluster that reports what happens between {@code source} and {@code target}: the first
     * in the call whose target is one of them, or null when neither is.
     */
    ClusterPlan ownerOf(IArchimateConcept source, IArchimateConcept target) {
        for (ClusterPlan c : clusters) {
            if (c.cluster.target() == source || c.cluster.target() == target) {
                return c;
            }
        }
        return null;
    }

    /** The ids of every relationship and view connection this plan deletes. */
    Set<String> removalIds() {
        Set<String> ids = new LinkedHashSet<>();
        relationshipsToRemove.forEach(r -> ids.add(r.getId()));
        connectionsToRemove.forEach(c -> ids.add(c.getId()));
        if (folded != null) {
            for (ParallelRelationshipFolds.Fold fold : folded.folds) {
                ids.add(fold.dropped.getId());
                fold.removed.forEach(c -> ids.add(c.getId()));
            }
        }
        return ids;
    }

    /** Parallels as they will be once this plan is written, read from the unwritten model. */
    void fillParallelsAsPlanned() {
        Map<IArchimateConcept, IArchimateConcept> into = new IdentityHashMap<>(mergeInto);
        if (folded != null) {
            into.putAll(folded.foldedInto);
        }
        Map<IArchimateConcept, List<IArchimateConcept>> mergedInto = new IdentityHashMap<>();
        for (Map.Entry<IArchimateConcept, IArchimateConcept> merge : into.entrySet()) {
            mergedInto.computeIfAbsent(merge.getValue(), k -> new ArrayList<>()).add(merge.getKey());
        }
        fillParallels(end -> into.getOrDefault(end, end), end -> {
            List<IArchimateRelationship> out = new ArrayList<>(end.getSourceRelationships());
            for (IArchimateConcept merged : mergedInto.getOrDefault(end, List.of())) {
                out.addAll(merged.getSourceRelationships());
            }
            return out;
        });
    }

    /** The relationships with an end on one of {@code members}, in the order their folder holds them. */
    private static List<IArchimateRelationship> relationshipsTouching(IArchimateModel model,
            Set<IArchimateElement> members) {
        Set<IArchimateRelationship> touching = new LinkedHashSet<>();
        for (IArchimateElement member : members) {
            touching.addAll(member.getSourceRelationships());
            touching.addAll(member.getTargetRelationships());
        }
        List<IArchimateRelationship> ordered = new ArrayList<>();
        IFolder relations = model.getFolder(FolderType.RELATIONS);
        if (relations != null) {
            for (TreeIterator<EObject> it = relations.eAllContents(); it.hasNext();) {
                EObject each = it.next();
                if (each instanceof IArchimateRelationship relationship && touching.remove(relationship)) {
                    ordered.add(relationship);
                }
            }
        }
        ordered.addAll(touching);
        return ordered;
    }

    /**
     * One walk of every view: the boxes showing a cluster member, and the connections drawing each
     * relationship, both in the order the model holds them.
     */
    static final class DiagramIndex {
        final Map<IDiagramModel, List<IDiagramModelArchimateObject>> memberBoxes = new LinkedHashMap<>();
        private final Map<IArchimateRelationship, List<IDiagramModelArchimateConnection>> drawn =
                new IdentityHashMap<>();
        private final Map<IArchimateRelationship, Integer> order = new IdentityHashMap<>();

        static DiagramIndex of(IArchimateModel model, Set<IArchimateElement> members) {
            DiagramIndex index = new DiagramIndex();
            for (IDiagramModel view : model.getDiagramModels()) {
                for (TreeIterator<EObject> it = view.eAllContents(); it.hasNext();) {
                    EObject each = it.next();
                    if (each instanceof IDiagramModelArchimateObject box
                            && members.contains(box.getArchimateElement())) {
                        index.memberBoxes.computeIfAbsent(view, k -> new ArrayList<>()).add(box);
                    } else if (each instanceof IDiagramModelArchimateConnection connection) {
                        index.drawn.computeIfAbsent(connection.getArchimateRelationship(),
                                k -> new ArrayList<>()).add(connection);
                    }
                }
            }
            IFolder relations = model.getFolder(FolderType.RELATIONS);
            if (relations != null) {
                int i = 0;
                for (TreeIterator<EObject> it = relations.eAllContents(); it.hasNext();) {
                    if (it.next() instanceof IArchimateRelationship relationship) {
                        index.order.put(relationship, i++);
                    }
                }
            }
            return index;
        }

        List<IDiagramModelArchimateConnection> connectionsOf(IArchimateRelationship relationship) {
            return drawn.getOrDefault(relationship, List.of());
        }

        List<IArchimateRelationship> inModelOrder(Set<IArchimateRelationship> relationships) {
            List<IArchimateRelationship> sorted = new ArrayList<>(relationships);
            sorted.sort(modelOrder());
            return sorted;
        }

        /** The order the relationships folder holds relationships in. */
        Comparator<IArchimateRelationship> modelOrder() {
            return Comparator.comparingInt(r -> order.getOrDefault(r, Integer.MAX_VALUE));
        }
    }

    /** The report for this plan, with {@code parallels} already filled in on each cluster. */
    MergeElementsResultDto toResult() {
        List<MergeElementsResultDto.Cluster> out = new ArrayList<>();
        Map<String, String> idMap = new LinkedHashMap<>();
        int sources = 0, dropped = 0, kept = 0, cascadedRelationships = 0, cascadedConnections = 0;
        int parallelPairs = 0, conflicts = 0, copied = 0, doubles = 0, boxesMoved = 0, collapsed = 0;
        boolean collapse = options.collapseParallel();
        for (ClusterPlan c : clusters) {
            IArchimateElement target = c.cluster.target();
            List<String> sourceIds = c.cluster.sources().stream().map(IArchimateElement::getId).toList();
            for (String id : sourceIds) {
                idMap.put(id, target.getId());
            }
            boolean union = options.properties() == PropertyPolicy.UNION;
            boolean keepTarget = options.properties() == PropertyPolicy.KEEP_TARGET;
            out.add(new MergeElementsResultDto.Cluster(target.getId(), target.getName(),
                    target.eClass().getName(), sourceIds, c.relationshipsRepointed,
                    c.diagramObjectsRepointed, List.copyOf(c.dropped),
                    options.self() == SelfPolicy.KEEP ? List.copyOf(c.kept) : null,
                    List.copyOf(c.cascadedRelationshipIds), List.copyOf(c.cascadedConnectionIds),
                    List.copyOf(c.parallels), collapse ? collapsed(c) : null,
                    collapse ? notCollapsed(c) : null, union ? List.copyOf(c.conflicts) : null,
                    c.copies.size(), keepTarget ? c.discarded : null,
                    keepTarget ? null : c.documentationAppended,
                    List.copyOf(c.doublePlacements), List.copyOf(c.specializationDiffers),
                    List.copyOf(c.namesDropped)));
            sources += sourceIds.size();
            dropped += c.dropped.size();
            kept += c.kept.size();
            cascadedRelationships += c.cascadedRelationshipIds.size();
            cascadedConnections += c.cascadedConnectionIds.size();
            parallelPairs += c.parallels.size();
            conflicts += c.conflicts.size();
            copied += c.copies.size();
            doubles += c.doublePlacements.size();
            boxesMoved += c.diagramObjectsRepointed;
            collapsed += c.folds.size();
        }
        MergeElementsResultDto.Totals totals = new MergeElementsResultDto.Totals(clusters.size(), sources,
                repointedKept.size(), boxesMoved, dropped, kept, cascadedRelationships,
                cascadedConnections, parallelPairs, conflicts, copied, doubles, collapse ? collapsed : null);
        Map<String, String> relationshipIdMap = null;
        if (collapse) {
            relationshipIdMap = new LinkedHashMap<>();
            for (Map.Entry<IArchimateRelationship, IArchimateRelationship> fold : folded.foldedInto.entrySet()) {
                relationshipIdMap.put(fold.getKey().getId(), fold.getValue().getId());
            }
        }
        return new MergeElementsResultDto(List.copyOf(out), idMap, totals, options.properties().wire,
                options.self().wire, options.recordMergedIds(), collapse, relationshipIdMap);
    }

    private static List<ParallelCollapsed> collapsed(ClusterPlan c) {
        List<ParallelCollapsed> out = new ArrayList<>();
        for (ParallelRelationshipFolds.Fold fold : c.folds) {
            out.add(new ParallelCollapsed(fold.survivor.getId(), fold.dropped.getId(), fold.dropped.eClass().getName(),
                    List.copyOf(fold.filled), ids(fold.repointed), ids(fold.removed), ids(fold.moved),
                    ids(fold.reattached)));
        }
        return out;
    }

    private static List<ParallelNotCollapsed> notCollapsed(ClusterPlan c) {
        List<ParallelNotCollapsed> out = new ArrayList<>(c.notCollapsed);
        out.addAll(c.createdByCollapse);
        return out;
    }

    private static List<String> ids(List<? extends com.archimatetool.model.IIdentifier> objects) {
        return objects.stream().map(com.archimatetool.model.IIdentifier::getId).toList();
    }
}
