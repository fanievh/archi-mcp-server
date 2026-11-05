package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProfile;

import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto.SpecializationDropped;
import net.vheerden.archi.mcp.response.dto.ChangeRelationshipTypeResultDto;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;
import net.vheerden.archi.mcp.response.dto.RelationshipTypeChangeRequest;

/**
 * Changes the type of the listed relationships in place: each is replaced by a relationship of the
 * new type that keeps its id, name, documentation, properties and features, and everything that
 * referred to the old relationship is re-pointed to the new one rather than recreated.
 *
 * <p>This is Archi's own relationship type change, through {@link RelationshipRetype}, with two
 * differences: the new relationship keeps the old one's id, so a reference held outside the model
 * still resolves, and it takes the old one's place in its folder rather than the end of the list.
 * As in Archi, no note is added to the documentation, the specialization is not carried over, and
 * the old type's own attribute is lost; the new relationship has its type's defaults.</p>
 *
 * <p><strong>Decided when it runs.</strong> The call is resolved and every effect is read from the
 * model inside {@link #execute()}, not when the call was prepared: an approved proposal runs after
 * a human may have edited the model, and a connection drawn in the meantime must move with it. The
 * command writes nothing and reports why through {@link #getSkipReason()} when the call no longer
 * resolves (a relationship gone, a new type that ArchiMate no longer allows) or when a relationship
 * no longer has the type the call was prepared against. {@code canExecute} stays true: a refusal
 * there would make the stack reject the entry.</p>
 *
 * <p><strong>Order.</strong> The relationships are written in the order the call lists them. Each
 * write reads the relationship's ends and the relationships attached to it as they then are, so two
 * listed relationships joined to each other end up joined as the new objects whichever comes
 * first.</p>
 *
 * <p><strong>Undo and redo.</strong> Every write is recorded as it is made, with what reversing it
 * needs. Undo reverses the record, so each old relationship is back in its folder at its index with
 * every reference; redo replays the record without reading the model again. An undo or a redo that fails
 * partway leaves the command not applied, as far as it can, and still throws: see
 * {@link RecordedSteps}.</p>
 */
final class ChangeRelationshipTypeCommand extends Command implements CommitSkippableCommand {

    /**
     * The label of a type, which only Archi's documentation note quotes. No note is added here, as
     * Archi adds none to a relationship whose type is changed directly, so it is never asked for.
     */
    private static final Function<EClass, String> NO_NOTE = type -> {
        throw new IllegalStateException("no documentation note is added to a changed relationship");
    };

    private final IArchimateModel model;
    private final List<RelationshipTypeChangeRequest> changes;
    private final Map<String, EClass> preparedTypes;
    private final ChangeElementTypeOperations.TypeRule rule;
    private final MergeElementsPlan.RelationshipRule conceptRule;

    private final RecordedSteps record = new RecordedSteps();
    private ChangeRelationshipTypeResultDto result;
    private String skipReason;
    /** Set where the last run declined with nothing it wrote left behind. */
    private boolean changedNothing;
    /** Set where the last run declined and could not undo all of what it wrote. */
    private boolean leftChanges;

    /**
     * @param preparedTypes each listed id with the type its relationship had when the call was
     *        prepared; the command declines when a relationship's type has changed since
     * @param rule whether ArchiMate allows a relationship type between two concept types
     * @param conceptRule whether Archi allows a relationship type between two concepts, consulted
     *        for an end that is a Junction or a relationship
     */
    ChangeRelationshipTypeCommand(IArchimateModel model, List<RelationshipTypeChangeRequest> changes,
            Map<String, EClass> preparedTypes, ChangeElementTypeOperations.TypeRule rule,
            MergeElementsPlan.RelationshipRule conceptRule) {
        this.model = model;
        this.changes = List.copyOf(changes);
        this.preparedTypes = Map.copyOf(preparedTypes);
        this.rule = rule;
        this.conceptRule = conceptRule;
        setLabel("Change relationship type");
    }

    /** The type each listed relationship had when the call was prepared. */
    Map<String, EClass> getPreparedTypes() {
        return preparedTypes;
    }

    /** What the last execute did, or null when it declined. */
    ChangeRelationshipTypeResultDto getResult() {
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
        List<ChangeRelationshipTypeOperations.Resolved> resolved;
        try {
            resolved = ChangeRelationshipTypeOperations.resolve(model, changes, rule, conceptRule);
        } catch (ModelAccessException e) {
            skipReason = "change-relationship-type wrote nothing: " + e.getMessage();
            changedNothing = true;
            return;
        }
        for (ChangeRelationshipTypeOperations.Resolved each : resolved) {
            EClass prepared = preparedTypes.get(each.relationship().getId());
            if (prepared != each.relationship().eClass()) {
                skipReason = "change-relationship-type wrote nothing: " + CallerText.bare(each.relationship().getId())
                        + " is now a " + each.relationship().eClass().getName() + ", not the "
                        + (prepared != null ? prepared.getName() : "type") + " the call was prepared against";
                changedNothing = true;
                return;
            }
        }
        try {
            List<RelationshipRetype.Plan> plans = plans(model, resolved);
            List<Written> written = new ArrayList<>();
            for (RelationshipRetype.Plan plan : plans) {
                written.add(write(plan));
            }
            result = report(plans, written);
        } catch (RuntimeException | Error e) {
            // Never leave a half-changed model behind: what was written is undone here, inside the
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
                    ? "change-relationship-type stopped and undid its own changes: " + RecordedSteps.reason(e)
                    : "change-relationship-type stopped and could not undo all of its own changes: "
                            + RecordedSteps.reason(e) + "; undoing then failed with: "
                            + RecordedSteps.reason(revertFailure);
        }
    }

    @Override
    public void undo() {
        record.undo();
    }

    @Override
    public void redo() {
        record.redo();
    }

    /** One relationship written: the new relationship and how many attached relationships moved to it. */
    private record Written(IArchimateRelationship replacement, int relationshipsRepointed) {}

    /**
     * Writes one relationship's retype through the record, counting the relationships attached to it
     * that are re-pointed as they are written.
     */
    private Written write(RelationshipRetype.Plan plan) {
        IArchimateRelationship replacement = RelationshipRetype.replacementFor(plan.relationship, plan.to, false,
                NO_NOTE);
        int[] attached = {0};
        RelationshipRetype.write(plan, replacement, step -> {
            if (step instanceof RelationshipRetype.EndStep) {
                attached[0]++;
            }
            record.apply(step);
        });
        return new Written(replacement, attached[0]);
    }

    /**
     * A plan for each resolved relationship, in the order given, each to become its own type. The
     * connections are read once per type asked for.
     */
    static List<RelationshipRetype.Plan> plans(IArchimateModel model,
            List<ChangeRelationshipTypeOperations.Resolved> resolved) {
        Map<EClass, List<IArchimateRelationship>> byType = new LinkedHashMap<>();
        for (ChangeRelationshipTypeOperations.Resolved each : resolved) {
            byType.computeIfAbsent(each.to(), type -> new ArrayList<>()).add(each.relationship());
        }
        Map<IArchimateRelationship, RelationshipRetype.Plan> byRelationship = new IdentityHashMap<>();
        for (Map.Entry<EClass, List<IArchimateRelationship>> entry : byType.entrySet()) {
            for (RelationshipRetype.Plan plan : RelationshipRetype.Plan.read(model, entry.getValue(), entry.getKey())) {
                byRelationship.put(plan.relationship, plan);
            }
        }
        List<RelationshipRetype.Plan> plans = new ArrayList<>();
        for (ChangeRelationshipTypeOperations.Resolved each : resolved) {
            plans.add(byRelationship.get(each.relationship()));
        }
        return plans;
    }

    /** What was done, read off each written relationship as the model now holds it. */
    private static ChangeRelationshipTypeResultDto report(List<RelationshipRetype.Plan> plans, List<Written> written) {
        List<ChangeRelationshipTypeResultDto.Change> out = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            RelationshipRetype.Plan plan = plans.get(i);
            IArchimateRelationship now = written.get(i).replacement();
            out.add(change(plan.relationship, now, plan.to, plan.connections.size(),
                    written.get(i).relationshipsRepointed(), now.getSource().getId(), now.getTarget().getId(), now.eContainer(), attributesOf(now)));
        }
        return withTotals(out);
    }

    /**
     * What the call would do to the model as it is now, read without writing to it: each
     * relationship keeps its id, its ends and its folder, and takes its new type's defaults.
     */
    static ChangeRelationshipTypeResultDto preview(List<RelationshipRetype.Plan> plans) {
        List<ChangeRelationshipTypeResultDto.Change> out = new ArrayList<>();
        for (RelationshipRetype.Plan plan : plans) {
            IArchimateRelationship old = plan.relationship;
            out.add(change(old, old, plan.to, plan.connections.size(),
                    old.getSourceRelationships().size() + old.getTargetRelationships().size(),
                    old.getSource().getId(), old.getTarget().getId(), old.eContainer(),
                    attributesOf((IArchimateRelationship) IArchimateFactory.eINSTANCE.create(plan.to))));
        }
        return withTotals(out);
    }

    private static ChangeRelationshipTypeResultDto.Change change(IArchimateRelationship old,
            IArchimateRelationship now, EClass to, int connections, int attached, String sourceId, String targetId,
            EObject folder, RelationshipSemanticAttributes attributes) {
        IProfile profile = old.getPrimaryProfile();
        return new ChangeRelationshipTypeResultDto.Change(old.getId(), now.getId(), old.getName(),
                old.eClass().getName(), to.getName(), sourceId, targetId, connections,
                attached, folder instanceof IIdentifier identified ? identified.getId() : null, dropped(old),
                attributes, profile == null ? null : new SpecializationDropped(profile.getName(),
                        profile.getConceptType()));
    }

    private static ChangeRelationshipTypeResultDto withTotals(List<ChangeRelationshipTypeResultDto.Change> changes) {
        Map<String, String> idMap = new LinkedHashMap<>();
        int connections = 0;
        int attached = 0;
        int attributes = 0;
        int specializations = 0;
        for (ChangeRelationshipTypeResultDto.Change each : changes) {
            idMap.put(each.relationshipId(), each.resultingId());
            connections += each.connectionsRepointed();
            attached += each.relationshipsRepointed();
            attributes += each.attributesDropped() != null ? 1 : 0;
            specializations += each.specializationDropped() != null ? 1 : 0;
        }
        return new ChangeRelationshipTypeResultDto(List.copyOf(changes), idMap,
                new ChangeRelationshipTypeResultDto.Totals(changes.size(), connections, attached, attributes,
                        specializations));
    }

    /**
     * The old type's own attribute, which the new type cannot carry: an Access type always, an
     * Influence strength when one is set and not blank, an Association's direction when it is
     * directed (undirected is the default, so nothing is lost); null when there is nothing to lose.
     */
    private static RelationshipSemanticAttributes dropped(IArchimateRelationship old) {
        String accessType = DtoMapper.accessTypeForDto(old);
        Boolean directed = Boolean.TRUE.equals(DtoMapper.associationDirectedForDto(old)) ? Boolean.TRUE : null;
        String strength = DtoMapper.influenceStrengthForDto(old);
        String influenceStrength = strength == null || strength.isBlank() ? null : strength;
        return accessType == null && directed == null && influenceStrength == null ? null
                : new RelationshipSemanticAttributes(accessType, directed, influenceStrength);
    }

    /** A relationship's own attribute as the model holds it, or null when it has none set. */
    private static RelationshipSemanticAttributes attributesOf(IArchimateRelationship relationship) {
        RelationshipSemanticAttributes attributes = new RelationshipSemanticAttributes(
                DtoMapper.accessTypeForDto(relationship), DtoMapper.associationDirectedForDto(relationship),
                DtoMapper.influenceStrengthForDto(relationship));
        return attributes.hasAny() ? attributes : null;
    }
}
