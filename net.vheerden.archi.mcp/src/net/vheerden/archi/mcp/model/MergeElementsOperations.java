package net.vheerden.archi.mcp.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.emf.ecore.EObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IJunction;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;

/**
 * The {@code merge-elements} tool, model side: validates a call, previews it, proposes it for
 * approval or runs it, and reports what it did.
 *
 * <p>A collaborator of {@code ArchiModelAccessorImpl}, which keeps a one-line forward to it. It
 * reaches the model through the admitted-model function the accessor injects, and writes only by
 * dispatching a {@link MergeElementsCommand}; the command decides every effect when it runs.</p>
 *
 * <p><strong>In a batch.</strong> The call is queued as one operation and decided at commit, after
 * every operation queued before it has run, so it sees what they did. Its preview reads the model
 * before the batch commits. Every operation queued after a merge was prepared against a model in
 * which the merge has not happened, so a queued merge closes the batch to everything but another
 * merge ({@link BatchClosingCommand}). A target or duplicate that only an operation in the same batch
 * creates is refused: a merge merges elements that exist before the batch commits.</p>
 */
final class MergeElementsOperations {

    private static final Logger logger = LoggerFactory.getLogger(MergeElementsOperations.class);

    private final Function<String, IArchimateModel> admittedModel;
    private final Supplier<MutationDispatcher> dispatcher;
    private final Runnable bumpVersion;

    MergeElementsOperations(Function<String, IArchimateModel> admittedModel,
            Supplier<MutationDispatcher> dispatcher, Runnable bumpVersion) {
        this.admittedModel = admittedModel;
        this.dispatcher = dispatcher;
        this.bumpVersion = bumpVersion;
    }

    public MutationResult<MergeElementsResultDto> mergeElements(String sessionId,
            List<MergeClusterRequest> merges, String propertyPolicy, String selfRelationships,
            boolean recordMergedIds, boolean collapseParallel) {
        logger.info("Merging elements: clusters={}", merges == null ? 0 : merges.size());
        IArchimateModel model = admittedModel.apply(sessionId);
        MutationDispatcher mutations = dispatcher.get();
        try {
            MergeElementsPlan.Options options = options(propertyPolicy, selfRelationships, recordMergedIds,
                    collapseParallel);
            Function<String, IArchimateElement> queuedCreated = id -> mutations.queuedCreatedElement(sessionId, id);
            PreparedMutation<MergeElementsResultDto> prepared = prepare(model, merges, options, null, queuedCreated);

            if (mutations.isApprovalRequired(sessionId)) {
                MergeElementsResultDto preview = prepared.entity();
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("merges", describeMerges(merges));
                proposedChanges.put("propertyPolicy", options.properties().wire);
                proposedChanges.put("selfRelationships", options.self().wire);
                proposedChanges.put("recordMergedIds", recordMergedIds);
                proposedChanges.put("collapseParallel", collapseParallel);
                proposedChanges.put("clusters", describeClusters(preview));
                // Approval rebuilds the command on the model as it is then; what it may delete stays
                // what this card shows.
                Set<String> listed = ((MergeElementsCommand) prepared.command()).getListedRemovals();
                ProposalContext ctx = storeAsProposal(sessionId, "merge-elements",
                        () -> prepare(model, merges, options, listed, queuedCreated),
                        mergeTargetIds(merges), preview, describe(preview), proposedChanges);
                return new MutationResult<>(preview, null, ctx);
            }

            if (mutations.getMode(sessionId) == OperationalMode.BATCH) {
                int seq = mutations.queueForBatch(sessionId, prepared.command(), describe(prepared.entity()));
                return new MutationResult<>(prepared.entity(), seq);
            }

            try {
                mutations.dispatchImmediate(prepared.command());
            } catch (AgentAuthoredCompoundCommand.ChangesLeft left) {
                // Part of it may still be in the model: a new version, and no claim that nothing changed.
                bumpVersion.run();
                throw left.refusal();
            }
            MergeElementsCommand command = (MergeElementsCommand) prepared.command();
            // A decline that left the model as it was is no new version; one that may have written is.
            if (!command.changedNothing()) {
                bumpVersion.run();
            }
            if (command.getSkipReason() != null) {
                throw new ModelAccessException(command.getSkipReason(), ErrorCode.MUTATION_FAILED, null,
                        "Nothing was changed. Re-read the elements with get-element and call merge-elements "
                                + "again; if it stops the same way, the merge cannot be made as asked.", null);
            }
            return new MutationResult<>(command.getResult(), null);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException("Error merging elements into " + targetsForMessage(merges),
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Validates the call against {@code model} and builds its command and preview. The preview is
     * what the merge would do to the model as it is now; the command reads the model again when
     * it runs.
     */
    static PreparedMutation<MergeElementsResultDto> prepare(IArchimateModel model,
            List<MergeClusterRequest> merges, MergeElementsPlan.Options options) {
        return prepare(model, merges, options, null);
    }

    /**
     * As {@link #prepare(IArchimateModel, List, MergeElementsPlan.Options)}, with the deletions the
     * command may make fixed to {@code listedRemovals}; {@code null} takes them from this preview.
     */
    static PreparedMutation<MergeElementsResultDto> prepare(IArchimateModel model,
            List<MergeClusterRequest> merges, MergeElementsPlan.Options options, Set<String> listedRemovals) {
        return prepare(model, merges, options, listedRemovals, null);
    }

    /**
     * As {@link #prepare(IArchimateModel, List, MergeElementsPlan.Options, Set)}, refusing by name an
     * id that {@code queuedCreated} finds among the elements an open batch will create; null skips
     * that lookup.
     */
    static PreparedMutation<MergeElementsResultDto> prepare(IArchimateModel model,
            List<MergeClusterRequest> merges, MergeElementsPlan.Options options, Set<String> listedRemovals,
            Function<String, IArchimateElement> queuedCreated) {
        List<MergeElementsPlan.Resolved> resolved = resolve(model, merges, queuedCreated);
        MergeElementsPlan plan = MergeElementsPlan.read(model, resolved, options);
        plan.fillParallelsAsPlanned();
        MergeElementsResultDto preview = plan.toResult();
        Set<String> allowed = listedRemovals != null ? listedRemovals : plan.removalIds();
        return new PreparedMutation<>(new MergeElementsCommand(model, merges, options, allowed), preview,
                resolved.get(0).target().getId());
    }

    /** The two policies, defaulted when absent, refused by name when not one of the allowed values. */
    static MergeElementsPlan.Options options(String propertyPolicy, String selfRelationships,
            boolean recordMergedIds, boolean collapseParallel) {
        MergeElementsPlan.PropertyPolicy properties = MergeElementsPlan.PropertyPolicy.UNION;
        if (propertyPolicy != null) {
            properties = null;
            for (MergeElementsPlan.PropertyPolicy each : MergeElementsPlan.PropertyPolicy.values()) {
                if (each.wire.equals(propertyPolicy)) {
                    properties = each;
                }
            }
            if (properties == null) {
                throw invalid("propertyPolicy " + CallerText.quoted(propertyPolicy)
                        + " is not one of union, keep-target, append",
                        "Use union (the default), keep-target or append.");
            }
        }
        MergeElementsPlan.SelfPolicy self = MergeElementsPlan.SelfPolicy.DROP;
        if (selfRelationships != null) {
            self = null;
            for (MergeElementsPlan.SelfPolicy each : MergeElementsPlan.SelfPolicy.values()) {
                if (each.wire.equals(selfRelationships)) {
                    self = each;
                }
            }
            if (self == null) {
                throw invalid("selfRelationships " + CallerText.quoted(selfRelationships)
                        + " is not one of drop, keep", "Use drop (the default) or keep.");
            }
        }
        return new MergeElementsPlan.Options(properties, self, recordMergedIds, collapseParallel,
                MergeElementsPlan.ARCHI);
    }

    /**
     * Resolves every cluster, or refuses the whole call with the first problem found. The checks
     * run in this order over all clusters before any is resolved further: the shape of each
     * cluster, then that no id appears twice anywhere in the call, then that every id names an
     * element of this model, then that each duplicate has its target's type.
     */
    static List<MergeElementsPlan.Resolved> resolve(IArchimateModel model, List<MergeClusterRequest> merges) {
        return resolve(model, merges, null);
    }

    /**
     * As {@link #resolve(IArchimateModel, List)}; an id missing from the model that
     * {@code queuedCreated} finds is refused as created in the open batch rather than as not found.
     */
    static List<MergeElementsPlan.Resolved> resolve(IArchimateModel model, List<MergeClusterRequest> merges,
            Function<String, IArchimateElement> queuedCreated) {
        if (merges == null || merges.isEmpty()) {
            throw invalid("merges must list at least one cluster",
                    "Pass merges: [{targetId, sourceIds: [...]}, ...].");
        }
        for (int i = 0; i < merges.size(); i++) {
            MergeClusterRequest cluster = merges.get(i);
            if (cluster == null || cluster.targetId() == null || cluster.targetId().isBlank()) {
                throw invalid("merges[" + i + "] has no targetId",
                        "Give every cluster the id of the element that survives as targetId.");
            }
            if (cluster.sourceIds() == null || cluster.sourceIds().isEmpty()) {
                throw invalid("merges[" + i + "] has no sourceIds",
                        "List the ids of the duplicates to merge into "
                                + CallerText.bare(cluster.targetId()) + " as sourceIds.");
            }
            for (int j = 0; j < cluster.sourceIds().size(); j++) {
                String id = cluster.sourceIds().get(j);
                if (id == null || id.isBlank()) {
                    throw invalid("merges[" + i + "].sourceIds[" + j + "] is blank",
                            "Every source id must name an element.");
                }
            }
        }

        Map<String, String> firstSeen = new HashMap<>();
        for (int i = 0; i < merges.size(); i++) {
            MergeClusterRequest cluster = merges.get(i);
            checkOnce(firstSeen, cluster.targetId(), "merges[" + i + "].targetId");
            for (int j = 0; j < cluster.sourceIds().size(); j++) {
                checkOnce(firstSeen, cluster.sourceIds().get(j), "merges[" + i + "].sourceIds[" + j + "]");
            }
        }

        List<IArchimateElement> targets = new ArrayList<>();
        List<List<IArchimateElement>> sources = new ArrayList<>();
        for (int i = 0; i < merges.size(); i++) {
            MergeClusterRequest cluster = merges.get(i);
            targets.add(element(model, cluster.targetId(), "merges[" + i + "].targetId", queuedCreated));
            List<IArchimateElement> clusterSources = new ArrayList<>();
            for (int j = 0; j < cluster.sourceIds().size(); j++) {
                clusterSources.add(element(model, cluster.sourceIds().get(j),
                        "merges[" + i + "].sourceIds[" + j + "]", queuedCreated));
            }
            sources.add(clusterSources);
        }

        List<MergeElementsPlan.Resolved> resolved = new ArrayList<>();
        for (int i = 0; i < merges.size(); i++) {
            IArchimateElement target = targets.get(i);
            for (IArchimateElement source : sources.get(i)) {
                checkSameType(target, source);
            }
            resolved.add(new MergeElementsPlan.Resolved(target, List.copyOf(sources.get(i))));
        }
        return resolved;
    }

    private static void checkOnce(Map<String, String> firstSeen, String id, String position) {
        String earlier = firstSeen.putIfAbsent(id, position);
        if (earlier != null) {
            throw invalid("Element " + CallerText.bare(id) + " appears at both " + earlier + " and "
                    + position + ": each element can appear once in a call, as a target or as a source",
                    "Name each element once. A cluster's target must not also be listed as a source, "
                            + "in that cluster or another.");
        }
    }

    private static IArchimateElement element(IArchimateModel model, String id, String position,
            Function<String, IArchimateElement> queuedCreated) {
        EObject found = ArchimateModelUtils.getObjectByID(model, id);
        if (found instanceof IArchimateElement element) {
            return element;
        }
        if (found == null && queuedCreated != null && queuedCreated.apply(id) != null) {
            throw invalid(CallerText.bare(id) + " (" + position + ") is created by an operation queued in this "
                    + "batch; merge-elements merges elements that exist before the batch commits",
                    "Call end-batch first, then merge-elements; or create the element outside the batch.");
        }
        if (found == null) {
            throw new ModelAccessException("Element not found: " + CallerText.bare(id) + " (" + position + ")",
                    ErrorCode.ELEMENT_NOT_FOUND, null,
                    "Use search-elements or get-element to find valid element IDs", null);
        }
        String kind = found instanceof IArchimateRelationship ? "a relationship"
                : found instanceof IDiagramModel ? "a view"
                : found instanceof IFolder ? "a folder"
                : "not an element";
        throw invalid(CallerText.bare(id) + " (" + position + ") is " + kind
                        + "; merge-elements merges elements only",
                "Pass element ids. search-elements finds the duplicates of an element.");
    }

    private static void checkSameType(IArchimateElement target, IArchimateElement source) {
        if (source.eClass() != target.eClass()) {
            throw new ModelAccessException("Cannot merge " + CallerText.bare(source.getId()) + " ("
                    + source.eClass().getName() + ") into " + CallerText.bare(target.getId()) + " ("
                    + target.eClass().getName() + "): merge-elements merges elements of one type only",
                    ErrorCode.INVALID_ELEMENT_TYPE, null,
                    "Change its type first with change-element-type, or leave it out of this cluster.", null);
        }
        if (target instanceof IJunction targetJunction && source instanceof IJunction sourceJunction
                && !targetJunction.getType().equals(sourceJunction.getType())) {
            throw new ModelAccessException("Cannot merge " + CallerText.bare(source.getId()) + " (an "
                    + sourceJunction.getType() + " junction) into " + CallerText.bare(target.getId())
                    + " (an " + targetJunction.getType() + " junction): the two junction types mean "
                    + "different things", ErrorCode.INVALID_ELEMENT_TYPE, null,
                    "Merge junctions of one type only: and with and, or with or.", null);
        }
    }

    private static ModelAccessException invalid(String message, String suggestion) {
        return new ModelAccessException(message, ErrorCode.INVALID_PARAMETER, null, suggestion, null);
    }

    /** The card's visible line: what collapses into what, and the counts a reviewer weighs. */
    static String describe(MergeElementsResultDto preview) {
        MergeElementsResultDto.Totals t = preview.totals();
        return "Merge " + t.sourcesMerged() + " duplicate" + (t.sourcesMerged() != 1 ? "s" : "")
                + " into " + t.clusters() + " element" + (t.clusters() != 1 ? "s" : "")
                + " (" + t.relationshipsRepointed() + " relationship"
                + (t.relationshipsRepointed() != 1 ? "s" : "") + " re-pointed, "
                + t.diagramObjectsRepointed() + " view object" + (t.diagramObjectsRepointed() != 1 ? "s" : "")
                + " re-pointed, " + t.selfRelationshipsDropped() + " self-relationship"
                + (t.selfRelationshipsDropped() != 1 ? "s" : "") + " dropped"
                + (preview.collapseParallel() ? ", " + t.parallelRelationshipsCollapsed() + " parallel relationship"
                        + (t.parallelRelationshipsCollapsed() != 1 ? "s" : "") + " collapsed" : "")
                + ")";
    }

    private static List<Map<String, Object>> describeMerges(List<MergeClusterRequest> merges) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MergeClusterRequest cluster : merges) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("targetId", cluster.targetId());
            entry.put("sourceIds", List.copyOf(cluster.sourceIds()));
            out.add(entry);
        }
        return out;
    }

    private static List<Map<String, Object>> describeClusters(MergeElementsResultDto preview) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MergeElementsResultDto.Cluster cluster : preview.clusters()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("targetId", cluster.targetId());
            entry.put("targetName", cluster.targetName());
            entry.put("mergedSourceIds", cluster.mergedSourceIds());
            entry.put("relationshipsRepointed", cluster.relationshipsRepointed());
            entry.put("diagramObjectsRepointed", cluster.diagramObjectsRepointed());
            entry.put("selfRelationshipsDropped", cluster.selfRelationshipsDropped().size());
            entry.put("cascadedRelationships", cluster.cascadedRelationshipIds().size());
            int connections = cluster.cascadedConnectionIds().size();
            for (MergeElementsResultDto.DroppedRelationship dropped : cluster.selfRelationshipsDropped()) {
                connections += dropped.connectionIds().size();
            }
            if (cluster.parallelsCollapsed() != null) {
                for (MergeElementsResultDto.ParallelCollapsed collapsed : cluster.parallelsCollapsed()) {
                    connections += collapsed.connectionsRemoved().size();
                }
            }
            entry.put("viewConnectionsRemoved", connections);
            entry.put("parallelRelationships", cluster.parallelRelationships().size());
            if (cluster.parallelsCollapsed() != null) {
                entry.put("parallelsCollapsed", cluster.parallelsCollapsed().size());
            }
            entry.put("doublePlacements", cluster.doublePlacements().size());
            if (cluster.propertyConflicts() != null) {
                entry.put("propertyConflicts", cluster.propertyConflicts().size());
            }
            entry.put("namesDropped", cluster.namesDropped().size());
            out.add(entry);
        }
        return out;
    }

    /**
     * Every target and every duplicate: a human edit to any of them while the proposal waits
     * makes the proposal stale rather than approving a merge the card no longer describes.
     */
    private static Set<String> mergeTargetIds(List<MergeClusterRequest> merges) {
        Set<String> ids = new LinkedHashSet<>();
        for (MergeClusterRequest cluster : merges) {
            ids.add(cluster.targetId());
            ids.addAll(cluster.sourceIds());
        }
        return ids;
    }

    private static String targetsForMessage(List<MergeClusterRequest> merges) {
        if (merges == null || merges.isEmpty() || merges.get(0) == null) {
            return "the requested targets";
        }
        String first = CallerText.quoted(merges.get(0).targetId());
        int others = merges.size() - 1;
        return others == 0 ? first : first + " and " + others + " other target" + (others != 1 ? "s" : "");
    }

    /**
     * Stores the proposal. The rebuild is run again when the proposal is approved, and only the
     * command it returns executes, so an approved merge acts on the model as it is then.
     */
    private ProposalContext storeAsProposal(String sessionId, String tool,
            Supplier<PreparedMutation<?>> rebuild, Set<String> targetIds, Object entity, String description,
            Map<String, Object> proposedChanges) {
        MutationDispatcher mutations = dispatcher.get();
        Instant now = Instant.now();
        StalenessCapture capture = mutations.captureStaleness(sessionId, targetIds);
        PendingProposal proposal = new PendingProposal(null, tool, description, rebuild, capture, entity,
                null, proposedChanges, "Merge prepared.", now, null, null);
        String proposalId = mutations.storeProposal(sessionId, proposal);
        logger.info("Stored proposal '{}' for session '{}': {}", proposalId, sessionId, description);
        return new ProposalContext(proposalId, description, now);
    }
}
