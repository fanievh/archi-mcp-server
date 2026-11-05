package net.vheerden.archi.mcp.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IJunction;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ChangeRelationshipTypeResultDto;
import net.vheerden.archi.mcp.response.dto.RelationshipTypeChangeRequest;

/**
 * The {@code change-relationship-type} tool, model side: validates a call, previews it, proposes it
 * for approval or runs it, and reports what it did.
 *
 * <p>A collaborator of {@code ArchiModelAccessorImpl}, which keeps a one-line forward to it. It
 * reaches the model through the admitted-model function the accessor injects, and writes only by
 * dispatching a {@link ChangeRelationshipTypeCommand}; the command decides every effect when it
 * runs.</p>
 *
 * <p><strong>Which new types are allowed.</strong> Archi's own check, with one widening. Each listed
 * relationship's new type must be allowed between its ends, and must allow every relationship
 * attached to it; both are judged with the type each listed relationship will have when the call is
 * done, so two listed relationships joined to each other change together. When an end is a Junction
 * or a relationship, Archi's own rule between the two concepts must also allow the new type. For an
 * end that is a relationship it is asked of the two ends as they are. For a Junction end it is asked
 * of the Junction as the call leaves it: a copy outside the model, holding every relationship at the
 * Junction by the type it will have, joined to its far ends. Archi's rule requires every relationship
 * at a Junction to share one type, so listing every relationship at a Junction with one type changes
 * the whole Junction, and leaving one out is refused, naming it; whatever else that rule checks at a
 * Junction, in the version of Archi running, decides as before. A relationship between a Junction and
 * a relationship is asked of its ends as they are, and refused without a Junction reason; ArchiMate
 * allows no relationship there. Archi
 * silently skips a relationship it would not allow; this tool refuses the whole call instead, naming
 * every such relationship, and changes nothing.</p>
 *
 * <p><strong>Where this differs from Archi's Change Type.</strong> Archi judges each relationship
 * alone, under its current type, so it never changes a relationship joined to a Junction; this tool
 * judges the call as a whole. It does not breach the project's validation-sync rule: the model the
 * call leaves is one in which Archi's own create-time rule allows every changed relationship as it
 * stands, which is the sense in which "never more forgiving than Archi" is kept.</p>
 *
 * <p><strong>Not in a batch.</strong> The change replaces each listed relationship with a new
 * object, and an operation queued around it in a batch would hold the object the change removes.
 * The call is therefore refused while a batch is open, and nothing is queued.</p>
 */
final class ChangeRelationshipTypeOperations {

    private static final Logger logger = LoggerFactory.getLogger(ChangeRelationshipTypeOperations.class);

    /** A listed relationship that resolved, and the type it is to have. */
    record Resolved(IArchimateRelationship relationship, EClass to) {}

    private final Function<String, IArchimateModel> admittedModel;
    private final Supplier<MutationDispatcher> dispatcher;
    private final Runnable bumpVersion;
    private final ChangeElementTypeOperations.TypeRule rule;
    private final MergeElementsPlan.RelationshipRule conceptRule;

    ChangeRelationshipTypeOperations(Function<String, IArchimateModel> admittedModel,
            Supplier<MutationDispatcher> dispatcher, Runnable bumpVersion) {
        this(admittedModel, dispatcher, bumpVersion, ChangeElementTypeOperations.ARCHI, MergeElementsPlan.ARCHI);
    }

    /**
     * @param rule whether ArchiMate allows a relationship type between two concept types
     * @param conceptRule whether Archi allows a relationship type between two concepts, asked only
     *        for an end that is a Junction or a relationship
     */
    ChangeRelationshipTypeOperations(Function<String, IArchimateModel> admittedModel,
            Supplier<MutationDispatcher> dispatcher, Runnable bumpVersion, ChangeElementTypeOperations.TypeRule rule,
            MergeElementsPlan.RelationshipRule conceptRule) {
        this.admittedModel = admittedModel;
        this.dispatcher = dispatcher;
        this.bumpVersion = bumpVersion;
        this.rule = rule;
        this.conceptRule = conceptRule;
    }

    public MutationResult<ChangeRelationshipTypeResultDto> changeRelationshipType(String sessionId,
            List<RelationshipTypeChangeRequest> changes) {
        logger.info("Changing relationship types: relationships={}", changes == null ? 0 : changes.size());
        IArchimateModel model = admittedModel.apply(sessionId);
        MutationDispatcher mutations = dispatcher.get();
        refuseInsideABatch(mutations, sessionId);
        try {
            PreparedMutation<ChangeRelationshipTypeResultDto> prepared = prepare(model, changes, null);

            if (mutations.isApprovalRequired(sessionId)) {
                ChangeRelationshipTypeResultDto preview = prepared.entity();
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("changes", describeChanges(preview));
                // Approval rebuilds the command on the model as it is then, against the types this
                // card shows. A batch opened while the card waits would queue it, so approval is
                // refused then, as the call is.
                Map<String, EClass> preparedTypes = ((ChangeRelationshipTypeCommand) prepared.command())
                        .getPreparedTypes();
                ProposalContext ctx = storeAsProposal(sessionId, "change-relationship-type", () -> {
                            refuseInsideABatch(mutations, sessionId);
                            return prepare(model, changes, preparedTypes);
                        }, changeTargetIds(changes), preview, describe(preview), proposedChanges);
                return new MutationResult<>(preview, null, ctx);
            }

            try {
                mutations.dispatchImmediate(prepared.command());
            } catch (AgentAuthoredCompoundCommand.ChangesLeft left) {
                // Part of it may still be in the model: a new version, and no claim that nothing changed.
                bumpVersion.run();
                throw left.refusal();
            }
            ChangeRelationshipTypeCommand command = (ChangeRelationshipTypeCommand) prepared.command();
            // A decline that left the model as it was is no new version; one that may have written is.
            if (!command.changedNothing()) {
                bumpVersion.run();
            }
            if (command.getSkipReason() != null) {
                throw new ModelAccessException(command.getSkipReason(), ErrorCode.MUTATION_FAILED, null,
                        "Nothing was changed. Re-read the relationships with get-relationships and call "
                                + "change-relationship-type again; if it stops the same way, the change cannot "
                                + "be made as asked.", null);
            }
            return new MutationResult<>(command.getResult(), null);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException("Error changing the relationship type of " + relationshipsForMessage(changes),
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Validates the call against {@code model} and builds its command and preview. The preview is
     * what the change would do to the model as it is now; the command reads the model again when
     * it runs, and declines if a relationship's type is no longer the one in {@code preparedTypes}
     * ({@code null} takes them from the model now).
     */
    PreparedMutation<ChangeRelationshipTypeResultDto> prepare(IArchimateModel model,
            List<RelationshipTypeChangeRequest> changes, Map<String, EClass> preparedTypes) {
        List<Resolved> resolved = resolve(model, changes, rule, conceptRule);
        Map<String, EClass> types = preparedTypes;
        if (types == null) {
            types = new HashMap<>();
            for (Resolved each : resolved) {
                types.put(each.relationship().getId(), each.relationship().eClass());
            }
        }
        ChangeRelationshipTypeResultDto preview = ChangeRelationshipTypeCommand.preview(
                ChangeRelationshipTypeCommand.plans(model, resolved));
        return new PreparedMutation<>(new ChangeRelationshipTypeCommand(model, changes, types, rule, conceptRule),
                preview, resolved.get(0).relationship().getId());
    }

    /**
     * Resolves every entry, or refuses the whole call with the first problem found. The checks
     * run in this order over all entries before any is looked at further: the shape of each entry,
     * then that no relationship is listed twice, then that every id names a relationship of this
     * model, then that every type is a concrete relationship type other than the one the
     * relationship has, then that ArchiMate allows every new type, judged as the class Javadoc
     * says; the last refusal names every relationship it would not allow.
     */
    static List<Resolved> resolve(IArchimateModel model, List<RelationshipTypeChangeRequest> changes,
            ChangeElementTypeOperations.TypeRule rule, MergeElementsPlan.RelationshipRule conceptRule) {
        if (changes == null || changes.isEmpty()) {
            throw invalid("changes must list at least one relationship",
                    "Pass changes: [{relationshipId, type}, ...].");
        }
        for (int i = 0; i < changes.size(); i++) {
            RelationshipTypeChangeRequest change = changes.get(i);
            if (change == null || change.relationshipId() == null || change.relationshipId().isBlank()) {
                throw invalid("changes[" + i + "] has no relationshipId",
                        "Give every entry the id of the relationship to change as relationshipId.");
            }
            if (change.type() == null || change.type().isBlank()) {
                throw invalid("changes[" + i + "] has no type",
                        "Give every entry the ArchiMate relationship type it should have, such as "
                                + "TriggeringRelationship.");
            }
        }

        Map<String, Integer> firstSeen = new HashMap<>();
        for (int i = 0; i < changes.size(); i++) {
            Integer earlier = firstSeen.putIfAbsent(changes.get(i).relationshipId(), i);
            if (earlier != null) {
                throw invalid("Relationship " + CallerText.bare(changes.get(i).relationshipId())
                        + " appears at both changes[" + earlier + "] and changes[" + i
                        + "]: each relationship can be listed once in a call",
                        "Name each relationship once, with the one type it should have.");
            }
        }

        List<IArchimateRelationship> relationships = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            relationships.add(relationship(model, changes.get(i).relationshipId(), "changes[" + i + "]"));
        }

        List<Resolved> resolved = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            IArchimateRelationship relationship = relationships.get(i);
            EClass to = RelationshipTypes.resolve(changes.get(i).type());
            if (relationship.eClass() == to) {
                throw new ModelAccessException(CallerText.bare(relationship.getId()) + " (changes[" + i
                        + "]) is already a " + to.getName(), ErrorCode.INVALID_RELATIONSHIP_TYPE, null,
                        "Leave it out of this call, or give the type it should change to.", null);
            }
            resolved.add(new Resolved(relationship, to));
        }

        checkAllowed(resolved, rule, conceptRule);
        return resolved;
    }

    /**
     * Refuses the call when ArchiMate would not allow any new type, naming every relationship it
     * would not allow and why.
     */
    private static void checkAllowed(List<Resolved> resolved, ChangeElementTypeOperations.TypeRule rule,
            MergeElementsPlan.RelationshipRule conceptRule) {
        Map<IArchimateConcept, EClass> finalTypes = new IdentityHashMap<>();
        for (Resolved each : resolved) {
            finalTypes.put(each.relationship(), each.to());
        }
        List<String> refusals = new ArrayList<>();
        for (Resolved each : resolved) {
            IArchimateRelationship relationship = each.relationship();
            EClass to = each.to();
            IArchimateConcept source = relationship.getSource();
            IArchimateConcept target = relationship.getTarget();
            EClass sourceType = finalTypes.getOrDefault(source, source.eClass());
            EClass targetType = finalTypes.getOrDefault(target, target.eClass());
            List<String> reasons = new ArrayList<>();
            boolean allowed = rule.allows(sourceType, targetType, to);
            for (IArchimateRelationship attached : relationship.getSourceRelationships()) {
                IArchimateConcept end = attached.getTarget();
                if (!rule.allows(to, finalTypes.getOrDefault(end, end.eClass()),
                        finalTypes.getOrDefault(attached, attached.eClass()))) {
                    reasons.add(attachedReason(attached, finalTypes));
                }
            }
            for (IArchimateRelationship attached : relationship.getTargetRelationships()) {
                IArchimateConcept end = attached.getSource();
                if (!rule.allows(finalTypes.getOrDefault(end, end.eClass()), to,
                        finalTypes.getOrDefault(attached, attached.eClass()))) {
                    reasons.add(attachedReason(attached, finalTypes));
                }
            }
            if (isJunctionOrRelationship(source) || isJunctionOrRelationship(target)) {
                boolean atJunction = source instanceof IJunction || target instanceof IJunction;
                boolean asLeft = atJunction && !(source instanceof IArchimateRelationship)
                        && !(target instanceof IArchimateRelationship);
                boolean allows = asLeft ? allowsAsTheCallLeavesIt(relationship, finalTypes, conceptRule)
                        : conceptRule.allows(source, target, to);
                if (!allows) {
                    if (asLeft) {
                        reasons.add(junctionReason(relationship, to, finalTypes));
                    } else {
                        allowed = false;
                    }
                }
            }
            if (!allowed || !reasons.isEmpty()) {
                String described = CallerText.bare(relationship.getId()) + " (" + relationship.eClass().getName()
                        + " → " + to.getName() + " from " + CallerText.bare(source.getId()) + " as "
                        + sourceType.getName() + " to " + CallerText.bare(target.getId()) + " as "
                        + targetType.getName() + ")";
                refusals.add(reasons.isEmpty() ? described : described + ": " + String.join("; and ", reasons));
            }
        }
        if (!refusals.isEmpty()) {
            throw new ModelAccessException("The new types would leave " + refusals.size() + " relationship"
                    + (refusals.size() != 1 ? "s" : "") + " that ArchiMate does not allow: "
                    + String.join("; ", refusals) + ". Nothing was changed.",
                    ErrorCode.RELATIONSHIP_NOT_ALLOWED, null,
                    "Leave those relationships out, or choose another type for them. For a relationship "
                            + "joined to a Junction, list every relationship at that Junction in the same call, "
                            + "with one type.", null);
        }
    }

    /**
     * Asks {@code conceptRule} about {@code relationship} as the call leaves the model. Each of its
     * Junction ends is copied outside any model, with a copy of every relationship at it, of the type
     * that relationship will have, joined to a copy of its far end. Archi's rule reads only a
     * Junction's own relationships and the class of each concept they join, so the copies answer as
     * the model would after the call. Each Junction is copied once, so every relationship at it joins
     * the same copy; nothing in the model is written.
     */
    private static boolean allowsAsTheCallLeavesIt(IArchimateRelationship relationship,
            Map<IArchimateConcept, EClass> finalTypes, MergeElementsPlan.RelationshipRule conceptRule) {
        Set<IArchimateRelationship> atJunctions = new LinkedHashSet<>();
        for (IJunction junction : junctionEnds(relationship)) {
            atJunctions.addAll(junction.getSourceRelationships());
            atJunctions.addAll(junction.getTargetRelationships());
        }
        Map<IArchimateConcept, IArchimateConcept> copies = new IdentityHashMap<>();
        for (IArchimateRelationship each : atJunctions) {
            copyOf(each, copies, atJunctions, finalTypes);
        }
        return conceptRule.allows(copyOf(relationship.getSource(), copies, atJunctions, finalTypes),
                copyOf(relationship.getTarget(), copies, atJunctions, finalTypes),
                finalTypes.get(relationship));
    }

    /**
     * A model-less copy of {@code concept} of the type it will have, with its id; a relationship at
     * one of the Junctions is joined to copies of its ends, on the same sides.
     */
    private static IArchimateConcept copyOf(IArchimateConcept concept, Map<IArchimateConcept, IArchimateConcept> copies,
            Set<IArchimateRelationship> atJunctions, Map<IArchimateConcept, EClass> finalTypes) {
        IArchimateConcept copy = copies.get(concept);
        if (copy != null) {
            return copy;
        }
        copy = (IArchimateConcept) IArchimateFactory.eINSTANCE.create(finalTypes.getOrDefault(concept, concept.eClass()));
        copy.setId(concept.getId());
        if (concept instanceof IJunction junction) {
            ((IJunction) copy).setType(junction.getType());
        }
        copies.put(concept, copy);
        if (concept instanceof IArchimateRelationship relationship && atJunctions.contains(relationship)) {
            ((IArchimateRelationship) copy).connect(copyOf(relationship.getSource(), copies, atJunctions, finalTypes),
                    copyOf(relationship.getTarget(), copies, atJunctions, finalTypes));
        }
        return copy;
    }

    /** The relationship's Junction ends, source first, each once. */
    private static List<IJunction> junctionEnds(IArchimateRelationship relationship) {
        List<IJunction> ends = new ArrayList<>();
        if (relationship.getSource() instanceof IJunction source) {
            ends.add(source);
        }
        if (relationship.getTarget() instanceof IJunction target && target != relationship.getSource()) {
            ends.add(target);
        }
        return ends;
    }

    /**
     * Why a relationship joined to a Junction is refused: at each Junction end, the relationships
     * whose type when the call is done would differ from {@code to} (its relationships out, then in,
     * each once); or, when none would, that the type may not pass through the Junction.
     */
    private static String junctionReason(IArchimateRelationship relationship, EClass to,
            Map<IArchimateConcept, EClass> finalTypes) {
        List<IJunction> junctions = junctionEnds(relationship);
        List<String> clauses = new ArrayList<>();
        for (IJunction junction : junctions) {
            Set<IArchimateRelationship> named = new LinkedHashSet<>();
            List<String> others = new ArrayList<>();
            List<IArchimateRelationship> at = new ArrayList<>(junction.getSourceRelationships());
            at.addAll(junction.getTargetRelationships());
            for (IArchimateRelationship each : at) {
                EClass type = finalTypes.getOrDefault(each, each.eClass());
                if (named.add(each) && type != to) {
                    others.add(CallerText.bare(each.getId()) + " (" + type.getName() + ")");
                }
            }
            if (!others.isEmpty()) {
                clauses.add("it is joined to Junction " + CallerText.bare(junction.getId())
                        + ", where every relationship must share one type, and " + String.join(", ", others)
                        + " at that Junction would keep another type");
            }
        }
        if (clauses.isEmpty() && junctions.size() == 2) {
            return "it is joined to Junctions " + CallerText.bare(junctions.get(0).getId()) + " and "
                    + CallerText.bare(junctions.get(1).getId()) + ", and ArchiMate does not allow a " + to.getName()
                    + " through them between every pair of concepts they join";
        }
        if (clauses.isEmpty()) {
            return "it is joined to Junction " + CallerText.bare(junctions.get(0).getId())
                    + ", and ArchiMate does not allow a " + to.getName()
                    + " through it between every pair of concepts it joins";
        }
        return String.join("; and ", clauses);
    }

    private static String attachedReason(IArchimateRelationship attached, Map<IArchimateConcept, EClass> finalTypes) {
        return "the relationship " + CallerText.bare(attached.getId()) + " ("
                + finalTypes.getOrDefault(attached, attached.eClass()).getName()
                + ") attached to it would not be allowed";
    }

    private static boolean isJunctionOrRelationship(IArchimateConcept concept) {
        return concept instanceof IJunction || concept instanceof IArchimateRelationship;
    }

    private static IArchimateRelationship relationship(IArchimateModel model, String id, String position) {
        EObject found = ArchimateModelUtils.getObjectByID(model, id);
        if (found instanceof IArchimateRelationship relationship) {
            return relationship;
        }
        if (found == null) {
            throw new ModelAccessException("Relationship not found: " + CallerText.bare(id) + " (" + position + ")",
                    ErrorCode.RELATIONSHIP_NOT_FOUND, null,
                    "Use get-relationships to find valid relationship IDs", null);
        }
        String kind = found instanceof IArchimateElement ? "an element"
                : found instanceof IDiagramModel ? "a view"
                : found instanceof IFolder ? "a folder"
                : found instanceof IDiagramModelConnection ? "a view connection"
                : found instanceof IDiagramModelObject ? "a view object"
                : "not a relationship";
        String suggestion = found instanceof IArchimateElement
                ? "Pass relationship ids. To change an element's type, use change-element-type."
                : found instanceof IDiagramModelConnection
                        ? "Pass relationship ids. get-view-contents gives the relationship id each view connection "
                                + "draws."
                        : "Pass relationship ids. get-relationships lists them.";
        throw invalid(CallerText.bare(id) + " (" + position + ") is " + kind
                + "; change-relationship-type changes relationships only", suggestion);
    }

    private static ModelAccessException invalid(String message, String suggestion) {
        return new ModelAccessException(message, ErrorCode.INVALID_PARAMETER, null, suggestion, null);
    }

    /**
     * The card's visible line: how many relationships change, and the counts a reviewer weighs. The
     * view connections re-pointed are always given; the attached relationships re-pointed, the type
     * attributes dropped and the specializations dropped only when there are any.
     */
    static String describe(ChangeRelationshipTypeResultDto preview) {
        ChangeRelationshipTypeResultDto.Totals t = preview.totals();
        StringBuilder line = new StringBuilder("Change the type of ").append(counted(t.relationshipsChanged(),
                "relationship")).append(" (").append(counted(t.connectionsRepointed(), "view connection"))
                .append(" re-pointed");
        if (t.relationshipsRepointed() > 0) {
            line.append(", ").append(counted(t.relationshipsRepointed(), "attached relationship")).append(" re-pointed");
        }
        if (t.attributesDropped() > 0) {
            line.append(", ").append(counted(t.attributesDropped(), "type attribute")).append(" dropped");
        }
        if (t.specializationsDropped() > 0) {
            line.append(", ").append(counted(t.specializationsDropped(), "specialization")).append(" dropped");
        }
        return line.append(')').toString();
    }

    private static String counted(int n, String noun) {
        return n + " " + noun + (n != 1 ? "s" : "");
    }

    private static List<Map<String, Object>> describeChanges(ChangeRelationshipTypeResultDto preview) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChangeRelationshipTypeResultDto.Change change : preview.changes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("relationshipId", change.relationshipId());
            entry.put("name", change.name());
            entry.put("fromType", change.fromType());
            entry.put("toType", change.toType());
            entry.put("sourceId", change.sourceId());
            entry.put("targetId", change.targetId());
            entry.put("connectionsRepointed", change.connectionsRepointed());
            entry.put("relationshipsRepointed", change.relationshipsRepointed());
            if (change.attributesDropped() != null) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                if (change.attributesDropped().accessType() != null) {
                    attributes.put("accessType", change.attributesDropped().accessType());
                }
                if (change.attributesDropped().associationDirected() != null) {
                    attributes.put("associationDirected", change.attributesDropped().associationDirected());
                }
                if (change.attributesDropped().influenceStrength() != null) {
                    attributes.put("influenceStrength", change.attributesDropped().influenceStrength());
                }
                entry.put("attributesDropped", attributes);
            }
            if (change.specializationDropped() != null) {
                entry.put("specializationDropped", change.specializationDropped().name());
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * Every listed relationship: a human edit to any of them while the proposal waits makes the
     * proposal stale rather than approving a change the card no longer describes.
     */
    private static Set<String> changeTargetIds(List<RelationshipTypeChangeRequest> changes) {
        Set<String> ids = new LinkedHashSet<>();
        for (RelationshipTypeChangeRequest change : changes) {
            ids.add(change.relationshipId());
        }
        return ids;
    }

    private static String relationshipsForMessage(List<RelationshipTypeChangeRequest> changes) {
        if (changes == null || changes.isEmpty() || changes.get(0) == null) {
            return "the requested relationships";
        }
        String first = CallerText.quoted(changes.get(0).relationshipId());
        int others = changes.size() - 1;
        return others == 0 ? first : first + " and " + others + " other relationship" + (others != 1 ? "s" : "");
    }

    /**
     * Refuses while the session's batch is open: a queued operation would hold the relationship this
     * replaces.
     */
    private static void refuseInsideABatch(MutationDispatcher mutations, String sessionId) {
        if (mutations.getMode(sessionId) == OperationalMode.BATCH) {
            throw new ModelAccessException(
                    "change-relationship-type cannot run inside a batch: it replaces each relationship with one "
                            + "of the new type, which a queued operation could not see. Nothing was queued.",
                    ErrorCode.BATCH_ALREADY_ACTIVE, null,
                    "Call end-batch first (with rollback: true to discard the queue), then "
                            + "change-relationship-type on its own.", null);
        }
    }

    /**
     * Stores the proposal. The rebuild is run again when the proposal is approved, and only the
     * command it returns executes, so an approved change acts on the model as it is then.
     */
    private ProposalContext storeAsProposal(String sessionId, String tool,
            Supplier<PreparedMutation<?>> rebuild, Set<String> targetIds, Object entity, String description,
            Map<String, Object> proposedChanges) {
        MutationDispatcher mutations = dispatcher.get();
        Instant now = Instant.now();
        StalenessCapture capture = mutations.captureStaleness(sessionId, targetIds);
        PendingProposal proposal = new PendingProposal(null, tool, description, rebuild, capture, entity,
                null, proposedChanges, "Type change prepared.", now, null, null);
        String proposalId = mutations.storeProposal(sessionId, proposal);
        logger.info("Stored proposal '{}' for session '{}': {}", proposalId, sessionId, description);
        return new ProposalContext(proposalId, description, now);
    }
}
