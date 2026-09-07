package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.INameable;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.SetViewLabelExpressionResultDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;
import net.vheerden.archi.mcp.response.dto.DiagramImageDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * Re-reads what a bulk call actually landed, so its per-operation results describe the model rather
 * than the requests that produced them.
 *
 * <h2>Why this cannot be done where the results are built</h2>
 *
 * <p>{@code executeBulk} prepares every operation first and dispatches them as one compound
 * afterwards, so each per-operation result is constructed while nothing has executed and while the
 * operations after it have not even been prepared. An operation that sets a group's height to 300
 * therefore cannot know that operation 5 will move a child down and force the parent-fit cascade to
 * grow that same group to 510. Any geometry captured at that point is a projection of an intent,
 * and projecting harder does not help: the information does not exist yet.</p>
 *
 * <p>So the read happens <em>after</em> dispatch, against live containment. That is the only point
 * at which "what the model holds" is a question with an answer, and it is what makes the reported
 * value effective state rather than a better-informed guess.</p>
 *
 * <h2>Why the batched and proposal modes are skipped</h2>
 *
 * <p>In those modes nothing has been written — the compound is queued or parked awaiting a human —
 * so there is no effective geometry to read and inventing one would be the same lie in a new place.
 * Those responses declare themselves as previews instead; the caller passes {@code dispatched} to
 * say which case it is.</p>
 */
final class BulkResultProjection {

    private BulkResultProjection() {
    }

    /**
     * Returns the operation results with post-execution state attached: the reported name, for
     * every operation that reports one, and additionally the geometry of a view object and the
     * container it landed in, or the styling, anchors, bendpoints and endpoints of a view
     * connection.
     *
     * <p>Operations on model concepts, folders and specializations get no geometry and no
     * connection report: they have neither, and an absent field is honest where a zeroed one would
     * read as measured. An entity id that no longer resolves is left untouched rather than guessed
     * at — a delete later in the same call is the ordinary way that happens, and it is also why a
     * removal that disconnected a connection reports nothing about it: the id stops resolving.</p>
     *
     * @param model      the model to re-read from
     * @param results    per-operation results as built during the prepare pass
     * @param dispatched true when the compound was actually executed; false for batched or
     *                   proposal results, where nothing has been written yet
     * @return a new list; never null
     */
    static List<BulkOperationResult> withPostDispatchState(IArchimateModel model,
            List<BulkOperationResult> results, boolean dispatched) {
        if (!dispatched || model == null || results.isEmpty()) {
            return List.copyOf(results);
        }
        List<BulkOperationResult> projected = new ArrayList<>(results.size());
        for (BulkOperationResult result : results) {
            projected.add(withLiveEntityState(model, result));
        }
        return List.copyOf(projected);
    }

    /**
     * Describes a prepared operation as a per-operation result: what kind of thing it touched, what
     * that thing is called, and any counts the operation's own result type carries.
     *
     * <p>A pure mapping over the prepared entity's type, with no accessor state, so it lives beside
     * the post-dispatch read above rather than inside the facade. The two together are everything
     * bulk-mutate says about a single operation.</p>
     *
     * <p>Deletions carry the standalone deletion tool's own cascade report verbatim, so the same
     * deletion says the same thing whether it was called directly or as one operation inside a bulk
     * call. Reusing that report rather than restating its counts is what keeps the two paths from
     * drifting apart again.</p>
     *
     * @param index    the 0-based position of this operation in the bulk array
     * @param tool     the tool that was executed
     * @param action   "created", "updated", "deleted" or "already_existed"
     * @param prepared the prepared mutation whose entity describes what was touched
     * @return the per-operation result, without geometry (see {@link #withPostDispatchState})
     */
    /**
     * Drops the collateral an operation reported for itself when that operation declined to run.
     *
     * <p>{@code resizedAncestors} and {@code movedObjects} are computed while the operation is
     * prepared, naming bounds for objects the caller never asked about. An operation that then
     * declines grew and displaced nothing, so those rectangles describe a placement that did not
     * happen — the same confident-wrong-value failure the fields were added to end. Here, unlike on
     * the queued and awaiting-approval paths, the compound has already run, so what actually
     * happened is knowable and the retraction is exact: the entry is simply absent, and
     * {@code skippedOperations} says why.</p>
     *
     * <p>Positional, because the dispatched compound holds one child per prepared operation in the
     * order the results were built — the same correspondence
     * {@link CommitSkippableCommand#collectSkipReasonsByOperation} relies on.</p>
     *
     * <p><strong>The concept reports are deliberately NOT retracted here</strong>, and neither is
     * the refreshed name. The two collateral lists describe a placement the declining operation
     * would have caused and did not, so leaving them standing would assert work that never happened.
     * {@code effectiveRelationship} and {@code effectiveElement} are the opposite kind of value: a
     * live read of an entity that demonstrably still exists — which is why it could be read at all.
     * What that entity currently holds stays true whether the operation ran or refused, and
     * {@code skippedOperations} remains the authority on which it was. This is the same ruling
     * {@link #withRefreshedName} already makes, applied rather than re-derived.</p>
     *
     * <p>Not reachable today in any case: no tool that can produce a concept report can decline.
     * {@code UpdateRelationshipCommand} and {@code UpdateElementCommand} are not
     * {@link CommitSkippableCommand}s, and the only skippable command their compound can contain is
     * built through the unconditional constructor. A create that declines never enters containment,
     * so its id does not resolve and no report is attached in the first place. The reasoning above
     * is what governs if that ever changes.</p>
     *
     * <p>The same walk carries the one value a deletion cannot get any other way — see
     * {@link #withNameAtDestruction}. Both readings of a child are the same reading of the same
     * child, so they are taken together rather than in two passes over the same list.</p>
     *
     * @param results    the per-operation results, already carrying any post-dispatch geometry
     * @param dispatched the compound that was executed, one child per operation
     * @return the results with declined operations' collateral removed
     */
    static List<BulkOperationResult> withoutRetractedCollateral(List<BulkOperationResult> results,
            CompoundCommand dispatched) {
        List<?> children = dispatched.getCommands();
        List<BulkOperationResult> retracted = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            BulkOperationResult result = results.get(i);
            Command child = (i < children.size()) ? (Command) children.get(i) : null;
            boolean declined = child != null
                    && !CommitSkippableCommand.collectSkipReasons(child).isEmpty();
            retracted.add(declined
                    ? result.withResizedAncestors(List.of()).withMovedObjects(List.of())
                    : withNameAtDestruction(result, child));
        }
        return List.copyOf(retracted);
    }

    /**
     * Replaces a deletion's reported name with the one its subject carried when the delete ran.
     *
     * <p>The one value in the whole response that {@link #withRefreshedName} is structurally unable
     * to serve. That pass resolves the operation's entity by id, and a deleted entity's id stops
     * resolving the instant the delete applies — so for the operation whose report is the
     * <em>only</em> surviving description of its subject, the prepared value stood untouched. Where
     * an earlier operation in the same call had renamed that subject, the prepared value was a name
     * the entity had already stopped using, and no re-read could tell the caller so. The deleting
     * command records the name inside its own {@code execute()} instead, and this reads it back off
     * the compound child that corresponds to this operation.</p>
     *
     * <p><strong>Nothing is claimed where nothing ran.</strong> A capture exists only if
     * {@code execute()} executed, so a queued batch — whose compound is handed here having never
     * run — finds none and keeps every prepared name. That is not a null check standing in for a
     * dispatched flag: the command is the witness to its own execution, which is a stronger
     * statement than a flag passed alongside it, and it is stated once, here. The awaiting-approval
     * path never reaches this method at all.</p>
     *
     * <p><strong>A declined delete never gets here</strong>, by the branch above. It destroyed
     * nothing, so it has no moment of destruction to describe, and both its prepared name and
     * whatever the live re-read already made of it stand — the same ruling
     * {@link #withRefreshedName} makes for declined operations, applied rather than re-derived.</p>
     *
     * <p>Gated on a name already being reported, so it corrects one and never introduces one, for
     * the reason {@link #withRefreshedName} gives. Both fields move together: {@code describe}
     * populates {@code entityName} and {@code deletion.name} from the same frozen report, so
     * correcting one alone would leave the same wrong value on the wire one field over. Operations
     * that are not deletions are reached but never changed — only the deleting commands record a
     * name, and they are built nowhere but the five delete prepares.</p>
     */
    private static BulkOperationResult withNameAtDestruction(BulkOperationResult result,
            Command child) {
        if (child == null || result.entityName() == null) {
            return result;
        }
        String destroyed = NameCapturingCommand.findNameAtExecute(child);
        if (destroyed == null || destroyed.equals(result.entityName())) {
            return result;
        }
        BulkOperationResult corrected = result.withEntityName(destroyed);
        return (corrected.deletion() == null) ? corrected
                : corrected.withDeletion(corrected.deletion().withName(destroyed));
    }

    /**
     * Projects a prepared mutation into the per-operation entry a bulk caller reads.
     *
     * <p><b>{@code entityType} is the EMF eClass name of the entity the operation touched</b>, in
     * every branch below and whichever tool produced the row. That is what makes the field safe to
     * key dedup, filtering or branching off: a group removed from a view answers to
     * {@code DiagramModelGroup} exactly as one added to it does, and a view answers to
     * {@code ArchimateDiagramModel} whether it was created, cleared, label-stamped, deleted or
     * moved. The approval card renders this same field as the row type to a human, so a second
     * vocabulary here is a second name for one thing on both surfaces at once.</p>
     *
     * <p>There is <b>one</b> deliberate exception: a specialization reports
     * {@code Specialization:<conceptType>}. An Archi specialization's identity is the concept type
     * it binds, and the bare eClass would say less rather than more.</p>
     *
     * <p>Where a producer DTO carries a coarser noun of its own — {@code objectType},
     * {@code removedObjectType}, both published in tool descriptions and branched on in code — the
     * translation happens here at the projection seam, never at the producer.</p>
     */
    /**
     * Resolves the action string for a bulk operation tool.
     *
     * <p>Lives beside {@link #describe} because that is its only reader: the projection is handed
     * the action it is to publish, and the mapping from tool name to that word is part of the same
     * decision rather than something the facade owes it.</p>
     */
    static String resolveActionString(String tool) {
        return switch (tool) {
            case "add-to-view", "add-group-to-view", "add-note-to-view", "add-image-to-view", "add-view-reference-to-view" -> "placed";
            case "add-connection-to-view" -> "connected";
            case "remove-from-view" -> "removed";
            case "move-to-folder" -> "moved";
            case "clear-view" -> "cleared";
            case "update-model", "update-folder", "update-view", "update-view-object", "update-view-connection", "update-element", "update-relationship", "update-specialization", "set-view-label-expression" -> "updated";
            case "delete-element", "delete-relationship", "delete-view", "delete-folder", "delete-specialization" -> "deleted";
            default -> "created";
        };
    }

    static BulkOperationResult describe(int index, String tool, String action,
            PreparedMutation<?> prepared) {
        Object entity = prepared.entity();
        String entityType = null;
        String entityName = null;
        Integer appliedCount = null, skippedCount = null;
        DeleteResultDto deletion = null;
        List<MovedViewObjectDto> resizedAncestors = List.of();
        List<MovedViewObjectDto> movedObjects = List.of();

        if (entity instanceof ElementDto dto) {
            entityType = dto.type();
            entityName = dto.name();
        } else if (entity instanceof RelationshipDto dto) {
            entityType = dto.type();
            entityName = dto.name();
        } else if (entity instanceof ViewDto dto) {
            entityType = "ArchimateDiagramModel";
            entityName = dto.name();
        } else if (entity instanceof AddToViewResultDto dto) {
            entityType = dto.viewObject().elementType();
            entityName = dto.viewObject().elementName();
            // A placement can grow the container it lands in and the group above that, neither of
            // which is any operation's own entity — so effectiveBounds below can never describe
            // them. Carried through here and re-read live after dispatch.
            resizedAncestors = dto.resizedAncestors();
        } else if (entity instanceof ViewConnectionDto dto) {
            entityType = dto.relationshipType();
            entityName = null;
        } else if (entity instanceof ViewObjectDto dto) {
            entityType = dto.elementType();
            entityName = dto.elementName();
            // An update can grow the group around the object and displace everything anchored to
            // it. Neither is this operation's own entity, so effectiveBounds can never describe
            // them, and dropping them here would leave the bulk caller blind to changes the
            // single-tool caller is told about.
            resizedAncestors = dto.resizedAncestors();
            movedObjects = dto.movedObjects();
        } else if (entity instanceof RemoveFromViewResultDto dto) {
            // removedObjectType keeps its own coarse vocabulary — it is published in the
            // remove-from-view description and branched on in code — so it is translated here
            // rather than changed at the producer. The four cases mirror prepareRemoveFromView's
            // guards one for one: IDiagramModelArchimateObject, IDiagramModelGroup,
            // IDiagramModelNote, IDiagramModelArchimateConnection. A fifth guard would arrive
            // here as its own unrecognised token and pass through unrelabelled, so it surfaces as
            // itself instead of being silently filed under one of these four.
            entityType = switch (dto.removedObjectType()) {
                case "viewObject" -> "DiagramModelArchimateObject";
                case "group" -> "DiagramModelGroup";
                case "note" -> "DiagramModelNote";
                case "viewConnection" -> "DiagramModelArchimateConnection";
                case null, default -> dto.removedObjectType();
            };
            entityName = null;
        } else if (entity instanceof ClearViewResultDto dto) {
            entityType = "ArchimateDiagramModel";
            entityName = dto.viewName();
        } else if (entity instanceof ViewGroupDto dto) {
            entityType = "DiagramModelGroup";
            entityName = dto.label();
        } else if (entity instanceof ViewNoteDto dto) {
            entityType = "DiagramModelNote";
            entityName = null;
        } else if (entity instanceof Map<?, ?> map) {
            // Specialization tools return Map<String,Object>
            Object ct = map.get("conceptType");
            Object nm = map.get("name");
            entityType = ct instanceof String s ? "Specialization:" + s : "Specialization";
            entityName = nm instanceof String s ? s : null;
        } else if (entity instanceof FolderDto dto) {
            entityType = "Folder";
            entityName = dto.name();
        } else if (entity instanceof ModelInfoDto dto) {
            entityType = "ArchimateModel";
            entityName = dto.name();
        } else if (entity instanceof MoveResultDto dto) {
            // The objectType() arm is vestigial on the production path: prepareMoveToFolder reads
            // elementType off eClass() for every kind it moves, and its subject is an EObject by
            // declaration, so the left arm always wins. Kept because this DTO is public and a
            // hand-built one may still carry a null there — but do NOT read the ternary as
            // evidence that some move kind still reports the coarse noun. None does.
            entityType = (dto.elementType() != null) ? dto.elementType() : dto.objectType();
            entityName = dto.name();
        } else if (entity instanceof SetViewLabelExpressionResultDto dto) {
            entityType = "ArchimateDiagramModel"; entityName = dto.viewName();
            appliedCount = dto.appliedCount(); skippedCount = dto.skippedCount();
        } else if (entity instanceof DeleteResultDto dto) {
            entityType = dto.type(); entityName = dto.name(); deletion = dto;
        }

        // Null alignment here rather than a read off the prepared DTO: this runs before anything
        // has been written, so the only honest value is none. The live read happens after dispatch,
        // in withLiveEntityState, where a later operation's re-align is already visible.
        return new BulkOperationResult(index, tool, action,
                prepared.entityId(), entityType, entityName, appliedCount, skippedCount,
                null, deletion, resizedAncestors, movedObjects, null, null, null, null,
                placementWarningsOf(entity), null);
    }

    /**
     * The placement-time disclosures an operation's entity carries, or an empty list.
     *
     * <p>The three annotation placements measure whether the rectangle they are about to occupy
     * sits on a route already drawn, and they measure it inside the prepare — which is the same
     * prepare this path runs. Dropping the result here would leave the disclosure present on the
     * single-tool call and silently absent on the bulk one, so an agent could not read its
     * absence as anything.</p>
     */
    private static List<StructuredWarningDto> placementWarningsOf(Object entity) {
        List<StructuredWarningDto> warnings = switch (entity) {
            case ViewNoteDto dto -> dto.structuredWarnings();
            case EmbeddedViewDto dto -> dto.structuredWarnings();
            case DiagramImageDto dto -> dto.structuredWarnings();
            case null, default -> null;
        };
        return warnings == null ? List.of() : warnings;
    }

    /**
     * Attaches whatever the operation's own entity turns out to be, resolved live.
     *
     * <p>Keyed on the resolved entity's type rather than on the tool that produced it. A tool list
     * would have to name both bulk branches of {@code add-connection-to-view} — the ordinary one
     * and the same-call back-reference one — and would go stale the next time a tool learns to
     * return a connection. Resolving the id covers all of them without naming any, because by this
     * point the difference between those branches has been dispatched away.</p>
     *
     * <p>The branches are mutually exclusive by the metamodel, not by the ordering here: an
     * ArchiMate concept, a {@code DiagramModelObject} and a {@code DiagramModelConnection} are
     * sibling EClasses, so no entity satisfies two of them. The name refresh sits above them all
     * precisely because it is not exclusive with any — see {@link #withRefreshedName}.</p>
     *
     * <p>The two concept branches are additionally gated on the entry already describing its
     * entity, which the type key alone does not settle. The gate is unchanged and still refuses to
     * describe an entity the envelope says nothing about; what changed is which entries that is
     * true of. {@code move-to-folder} prepares a {@code MoveResultDto}, and while
     * {@code describe} had no branch for that shape its entry carried no {@code entityType} and
     * named nothing about a subject that can be a relationship or an element — so attaching a
     * report there would have put a description of an entity on the wire for the first time under
     * cover of a bug fix, the introduction {@link #withRefreshedName} refuses. {@code describe} now
     * names that subject, so the gate's own precondition is met on its own terms: where the
     * envelope already says what the entity is, saying what state it now holds strengthens a report
     * rather than inventing one. A moved concept therefore carries one, and the coarser
     * {@code entityType} a move can report is refined by the concept report's own exact type.</p>
     */
    private static BulkOperationResult withLiveEntityState(IArchimateModel model,
            BulkOperationResult result) {
        BulkOperationResult withAncestors = withAncestorsRefreshed(model, result);
        withAncestors = withAncestors.withMovedObjects(
                refreshLive(model, withAncestors.movedObjects()));
        if (withAncestors.entityId() == null) {
            return withAncestors;
        }
        EObject entity = ArchimateModelUtils.getObjectByID(model, withAncestors.entityId());
        withAncestors = withRefreshedName(withAncestors, entity);
        if (entity instanceof IArchimateRelationship relationship) {
            return describesItsEntity(withAncestors)
                    ? withAncestors.withEffectiveRelationship(
                            DtoMapper.convertToRelationshipDto(relationship, true))
                    : withAncestors;
        }
        if (entity instanceof IArchimateElement element) {
            return describesItsEntity(withAncestors)
                    ? withAncestors.withEffectiveElement(DtoMapper.convertToElementDto(element))
                    : withAncestors;
        }
        if (entity instanceof IDiagramModelArchimateConnection connection) {
            ViewConnectionDto reported = readConnection(connection);
            return (reported == null) ? withAncestors
                    : withAncestors.withEffectiveConnection(reported);
        }
        if (!(entity instanceof IDiagramModelObject viewObject)) {
            return withAncestors;
        }
        // Above the bounds guard, not inside it. The parent is readable whenever the object is, and
        // an object whose bounds cannot be read is not a reason to stop saying where it sits — the
        // two are separate readings of the same live containment.
        BulkOperationResult located = withAncestors.withParentViewObjectId(
                PlacementParent.liveOf(viewObject));
        // Above the bounds guard for the same reason, and a third reading of the same live object.
        // A group, a note and a Grouping are stamped with their Archi type default when they are
        // placed, so the model holds an alignment the operation's own request never named — the one
        // styling value on this entry the server chose rather than echoed. Null at CENTRE, which
        // keeps the key absent rather than reporting the default back.
        located = located.withTextAlignment(StylingHelper.readTextAlignment(viewObject));
        IBounds bounds = viewObject.getBounds();
        if (bounds == null) {
            return located;
        }
        return located.withEffectiveBounds(new BulkOperationResult.EffectiveBounds(
                bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight()));
    }

    /**
     * Whether this entry already says what its entity is, which is {@code describe}'s judgment made
     * once from the prepared entity's own shape. A tool whose prepared shape has no branch there
     * reports no type, and a live read must not turn that silence into a description.
     */
    private static boolean describesItsEntity(BulkOperationResult result) {
        return result.entityType() != null;
    }

    /**
     * Replaces the reported name with the one the entity holds now.
     *
     * <p>Applied above the two branches below rather than inside either, because the entities this
     * is about reach neither: a relationship, an element and a view are none of them a view object
     * or a connection, so a refresh placed in a branch would miss every case it exists for. Read
     * through {@link INameable} for the same reason the connection report is composed from the
     * standalone readers — a second opinion about what "the name" means is how the prepared value
     * and this one would come to disagree.</p>
     *
     * <p>Gated on a name already being reported, so it only ever corrects one. Which operations
     * name something is {@code describe}'s judgment, made once, from the prepared entity's own
     * shape; introducing a name here would be a claim about tools that deliberately report none —
     * a placed connection, a note, a removal — and would put it on the wire for the first time
     * under cover of a bug fix. Folder and model operations pass that gate rather than being
     * excluded by it: {@code describe} names their subject, so what arrives here is a name to
     * correct. A model and a folder are both {@code INameable} and both resolve by id, so the read
     * above reaches them and neither falls into any branch below.</p>
     *
     * <p>A declined operation keeps whatever this reads, unlike the collateral lists that are
     * retracted from it. That is deliberate and is not an oversight of the retraction pass: those
     * lists describe a placement the declining operation would have caused and did not, whereas
     * this is a live read of an entity that demonstrably still exists — which is why it can be
     * read at all. Reporting the name that entity currently holds stays true whether the operation
     * ran or refused; {@code skippedOperations} remains the authority on whether it ran.</p>
     *
     * <p>An id that no longer resolves, and a live entity whose name is null, both leave the
     * prepared value standing, best-effort rather than strict for the reason the anchor read is: by
     * this point the compound has already been dispatched, so anything that fails here would
     * replace a whole response with an error after the model was written, telling the agent nothing
     * about work that did happen. A deletion is the one operation whose id is always gone by the
     * time this runs, so it is never corrected here and never could be; it is served instead by
     * {@link #withNameAtDestruction}, from a name the deleting command recorded while its subject
     * still existed.</p>
     */
    private static BulkOperationResult withRefreshedName(BulkOperationResult result, EObject entity) {
        if (result.entityName() == null || !(entity instanceof INameable nameable)
                || nameable.getName() == null) {
            return result;
        }
        return result.withEntityName(nameable.getName());
    }

    /**
     * Reads a connection into the same report the single-tool caller is given.
     *
     * <p>Composed from the readers the standalone prepares use, in the order they use them, rather
     * than from a reader written for this path — a second reader is how the two paths would come to
     * disagree about what "the styling this connection carries" means, which is the drift this
     * whole projection exists to end.</p>
     *
     * <p>The endpoints and anchors come from {@code getSource()}/{@code getTarget()} as they stand
     * now, not from whatever the operation named. That is the point of reading here: an operation
     * later in the same call can move an endpoint, and the anchors are absolute canvas centres
     * derived from that geometry, so a report built when this operation was prepared would have
     * been confidently wrong rather than merely absent.</p>
     *
     * @return the report, or null when the connection has no relationship to describe it by
     */
    private static ViewConnectionDto readConnection(IDiagramModelArchimateConnection connection) {
        IArchimateRelationship relationship = connection.getArchimateRelationship();
        if (relationship == null) {
            return null;
        }
        IDiagramModelArchimateObject source = archimateEndpoint(connection.getSource());
        IDiagramModelArchimateObject target = archimateEndpoint(connection.getTarget());

        ViewConnectionDto base = ConnectionResponseBuilder.buildConnectionResponseDto(
                connection.getId(), relationship,
                (source != null) ? source.getId() : null,
                (target != null) ? target.getId() : null,
                ConnectionResponseBuilder.collectBendpoints(connection),
                anchorable(source), anchorable(target), connection.getTextPosition());

        // Overlaid rather than relisted. Rebuilding at a shorter arity would silently drop
        // whatever the builder derived beyond that arity, which is how a field can be computed on
        // this path and never reported. Styling is re-read from the connection for the same reason
        // the endpoints are — this runs after dispatch, so the model is the only truthful source —
        // and that now includes the label expression and the label offset, which this path had
        // been reporting as absent whatever the connection actually held.
        return ConnectionResponseBuilder.withConnectionStyling(base, connection);
    }

    /** The endpoint as an ArchiMate view object, or null when it is a group, note or reference. */
    private static IDiagramModelArchimateObject archimateEndpoint(IConnectable endpoint) {
        return (endpoint instanceof IDiagramModelArchimateObject obj) ? obj : null;
    }

    /**
     * The endpoint to derive an anchor from, or null when its geometry cannot be read.
     *
     * <p>{@link ConnectionResponseBuilder#computeAbsoluteCenter} dereferences {@code getBounds()} on
     * the object <em>and on every ancestor it walks</em>, without guarding either. Bounds are
     * nullable on {@code IDiagramModelObject}, which this class already treats as a real state twice
     * over — the view-object branch above returns untouched when they are null, and
     * {@link #refreshLive} drops an object that has none. Reporting is best-effort by design here,
     * so an unreadable anchor must cost the anchor and nothing else.</p>
     *
     * <p>Guarded at this call site rather than inside the shared builder deliberately. That builder
     * is also on the standalone connection path, where an exception fails one tool call and the
     * caller can retry. Here the compound has <em>already been dispatched</em>, so a throw would
     * replace the whole bulk response with an error after the model was mutated — the agent would be
     * told nothing about work that did happen, which is worse than the missing field this avoids.</p>
     */
    private static IDiagramModelArchimateObject anchorable(IDiagramModelArchimateObject endpoint) {
        for (EObject current = endpoint; current instanceof IDiagramModelObject box;
                current = current.eContainer()) {
            if (box.getBounds() == null) {
                return null;
            }
        }
        return endpoint;
    }

    /**
     * Re-reads each re-sized ancestor from the model, for the same reason the operation's own
     * geometry is re-read here rather than kept from the prepare: the value the prepare computed is
     * what that operation intended, and a later operation in the same call can move it again. An
     * ancestor that no longer resolves is dropped rather than guessed at — a delete later in the
     * same call is the ordinary way that happens.
     */
    private static BulkOperationResult withAncestorsRefreshed(IArchimateModel model,
            BulkOperationResult result) {
        if (result.resizedAncestors().isEmpty()) {
            return result;
        }
        return result.withResizedAncestors(refreshLive(model, result.resizedAncestors()));
    }

    /** Re-reads each reported object from the model, dropping any that no longer resolves. */
    private static List<MovedViewObjectDto> refreshLive(IArchimateModel model,
            List<MovedViewObjectDto> reported) {
        if (reported.isEmpty()) {
            return reported;
        }
        List<MovedViewObjectDto> refreshed = new ArrayList<>(reported.size());
        for (MovedViewObjectDto entry : reported) {
            EObject live = ArchimateModelUtils.getObjectByID(model, entry.viewObjectId());
            if (!(live instanceof IDiagramModelObject obj) || obj.getBounds() == null) {
                continue;
            }
            IBounds b = obj.getBounds();
            String name = (obj.getName() != null) ? obj.getName() : entry.viewObjectId();
            refreshed.add(new MovedViewObjectDto(entry.viewObjectId(), name,
                    b.getX(), b.getY(), b.getWidth(), b.getHeight()));
        }
        return List.copyOf(refreshed);
    }
}
