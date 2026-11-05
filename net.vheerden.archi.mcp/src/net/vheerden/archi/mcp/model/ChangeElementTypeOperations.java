package net.vheerden.archi.mcp.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.editor.ArchiPlugin;
import com.archimatetool.editor.preferences.IPreferenceConstants;
import com.archimatetool.editor.ui.ArchiLabelProvider;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto;
import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;

/**
 * The {@code change-element-type} tool, model side: validates a call, previews it, proposes it for
 * approval or runs it, and reports what it did.
 *
 * <p>A collaborator of {@code ArchiModelAccessorImpl}, which keeps a one-line forward to it. It
 * reaches the model through the admitted-model function the accessor injects, and writes only by
 * dispatching a {@link ChangeElementTypeCommand}; the command decides every effect when it runs.</p>
 *
 * <p><strong>Relationships the new types do not allow.</strong> Archi's editor turns a relationship
 * that the new type does not allow into an Association, after asking. By default this tool refuses
 * the call instead, naming every such relationship, and changes nothing; the caller deletes them
 * or picks another type. A call that passes {@code to-association} gives Archi's answer: each such
 * relationship becomes an Association. A relationship's validity is judged with the type each of
 * its ends will have when the call is done, so two related elements can change together.</p>
 *
 * <p><strong>Not in a batch.</strong> The change replaces each listed element with a new object,
 * and an operation queued around it in a batch would hold the object the change removes. The call
 * is therefore refused while a batch is open, and nothing is queued.</p>
 */
final class ChangeElementTypeOperations {

    private static final Logger logger = LoggerFactory.getLogger(ChangeElementTypeOperations.class);

    /**
     * Whether ArchiMate allows a relationship type from one element type to another. Production
     * always passes {@link #ARCHI}, Archi's own rule, so the tool is never stricter nor more
     * forgiving than the editor. The seam exists because that rule loads its matrix from the
     * running platform and cannot answer in a plain JVM.
     */
    interface TypeRule {
        boolean allows(EClass source, EClass target, EClass relationship);
    }

    static final TypeRule ARCHI = ArchimateModelUtils::isValidRelationship;

    /**
     * The figure Archi gives a new box of a type: the user's default-figure preference for it, 0
     * when unset. Archi resets a box to this when it changes the type of the element shown.
     */
    static final ToIntFunction<EClass> ARCHI_FIGURE = type -> ArchiPlugin.getInstance().getPreferenceStore()
            .getInt(IPreferenceConstants.DEFAULT_FIGURE_PREFIX + type.getName());

    /**
     * Whether Archi's editor adds a "(Changed from X)" note to the documentation of a relationship
     * its type change turns into an Association: the user's preference for it, off by default.
     */
    static final BooleanSupplier ARCHI_NOTE = () -> ArchiPlugin.getInstance().getPreferenceStore()
            .getBoolean(IPreferenceConstants.ADD_DOCUMENTATION_NOTE_ON_RELATION_CHANGE);

    /**
     * The name Archi's editor gives a type, such as "Serving relation", which its note quotes. Read
     * through a seam because Archi's label provider needs the running platform's registry.
     */
    static final Function<EClass, String> ARCHI_LABEL = type -> ArchiLabelProvider.INSTANCE.getDefaultName(type);

    /** A listed element that resolved, and the type it is to have. */
    record Resolved(IArchimateElement element, EClass to) {}

    /**
     * Every listed element resolved, and the relationships the new types do not allow that the
     * call converts to Associations, in the order their folder holds them, each once.
     */
    record Resolution(List<Resolved> elements, List<IArchimateRelationship> conversions) {}

    /** What the call does with a relationship the new types do not allow. */
    enum InvalidRelationships {
        REFUSE("refuse"),
        TO_ASSOCIATION("to-association");

        final String wire;

        InvalidRelationships(String wire) {
            this.wire = wire;
        }
    }

    private final Function<String, IArchimateModel> admittedModel;
    private final Supplier<MutationDispatcher> dispatcher;
    private final Runnable bumpVersion;
    private final TypeRule rule;
    private final ToIntFunction<EClass> defaultFigure;
    private final BooleanSupplier note;
    private final Function<EClass, String> label;

    ChangeElementTypeOperations(Function<String, IArchimateModel> admittedModel,
            Supplier<MutationDispatcher> dispatcher, Runnable bumpVersion) {
        this(admittedModel, dispatcher, bumpVersion, ARCHI, ARCHI_FIGURE);
    }

    ChangeElementTypeOperations(Function<String, IArchimateModel> admittedModel,
            Supplier<MutationDispatcher> dispatcher, Runnable bumpVersion, TypeRule rule,
            ToIntFunction<EClass> defaultFigure) {
        this(admittedModel, dispatcher, bumpVersion, rule, defaultFigure, ARCHI_NOTE, ARCHI_LABEL);
    }

    ChangeElementTypeOperations(Function<String, IArchimateModel> admittedModel,
            Supplier<MutationDispatcher> dispatcher, Runnable bumpVersion, TypeRule rule,
            ToIntFunction<EClass> defaultFigure, BooleanSupplier note, Function<EClass, String> label) {
        this.admittedModel = admittedModel;
        this.dispatcher = dispatcher;
        this.bumpVersion = bumpVersion;
        this.rule = rule;
        this.defaultFigure = defaultFigure;
        this.note = note;
        this.label = label;
    }

    public MutationResult<ChangeElementTypeResultDto> changeElementType(String sessionId,
            List<ElementTypeChangeRequest> changes) {
        return changeElementType(sessionId, changes, null);
    }

    public MutationResult<ChangeElementTypeResultDto> changeElementType(String sessionId,
            List<ElementTypeChangeRequest> changes, String invalidRelationships) {
        logger.info("Changing element types: elements={}", changes == null ? 0 : changes.size());
        IArchimateModel model = admittedModel.apply(sessionId);
        MutationDispatcher mutations = dispatcher.get();
        refuseInsideABatch(mutations, sessionId);
        try {
            PreparedMutation<ChangeElementTypeResultDto> prepared = prepare(model, changes, invalidRelationships,
                    null, null);

            if (mutations.isApprovalRequired(sessionId)) {
                ChangeElementTypeResultDto preview = prepared.entity();
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("changes", describeChanges(preview));
                if (invalidRelationships != null) {
                    proposedChanges.put("invalidRelationships", invalidRelationships);
                }
                if (preview.totals().relationshipsConverted() > 0) {
                    proposedChanges.put("documentationNote", "added to each converted relationship if Archi's "
                            + "'add documentation note' preference is on when the change runs");
                }
                // Approval rebuilds the command on the model as it is then, against the types this
                // card shows and converting only the relationships it lists. A batch opened while
                // the card waits would queue it, so approval is refused then, as the call is.
                ChangeElementTypeCommand command = (ChangeElementTypeCommand) prepared.command();
                Map<String, EClass> preparedTypes = command.getPreparedTypes();
                Set<String> preparedConversions = command.getPreparedConversions();
                ProposalContext ctx = storeAsProposal(sessionId, "change-element-type", () -> {
                            refuseInsideABatch(mutations, sessionId);
                            return prepare(model, changes, invalidRelationships, preparedTypes, preparedConversions);
                        }, changeTargetIds(changes, preview), preview,
                        describe(preview), proposedChanges);
                return new MutationResult<>(preview, null, ctx);
            }

            try {
                mutations.dispatchImmediate(prepared.command());
            } catch (AgentAuthoredCompoundCommand.ChangesLeft left) {
                // Part of it may still be in the model: a new version, and no claim that nothing changed.
                bumpVersion.run();
                throw left.refusal();
            }
            ChangeElementTypeCommand command = (ChangeElementTypeCommand) prepared.command();
            // A decline that left the model as it was is no new version; one that may have written is.
            if (!command.changedNothing()) {
                bumpVersion.run();
            }
            if (command.getSkipReason() != null) {
                throw new ModelAccessException(command.getSkipReason(), ErrorCode.MUTATION_FAILED, null,
                        "Nothing was changed. Re-read the elements with get-element and call "
                                + "change-element-type again; if it stops the same way, the change cannot be "
                                + "made as asked.", null);
            }
            return new MutationResult<>(command.getResult(), null);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException("Error changing the type of " + elementsForMessage(changes),
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /** As {@link #prepare(IArchimateModel, List, String, Map, Set)}, refusing any relationship it would break. */
    PreparedMutation<ChangeElementTypeResultDto> prepare(IArchimateModel model,
            List<ElementTypeChangeRequest> changes, Map<String, EClass> preparedTypes) {
        return prepare(model, changes, null, preparedTypes, null);
    }

    /**
     * Validates the call against {@code model} and builds its command and preview. The preview is
     * what the change would do to the model as it is now; the command reads the model again when
     * it runs, and declines if an element's type is no longer the one in {@code preparedTypes}, or
     * if it would convert a relationship whose id is not in {@code preparedConversions}
     * ({@code null} takes either from the model now).
     */
    PreparedMutation<ChangeElementTypeResultDto> prepare(IArchimateModel model,
            List<ElementTypeChangeRequest> changes, String invalidRelationships, Map<String, EClass> preparedTypes,
            Set<String> preparedConversions) {
        Resolution resolution = resolve(model, changes, invalidRelationships, rule);
        Map<String, EClass> types = preparedTypes;
        if (types == null) {
            types = new HashMap<>();
            for (Resolved each : resolution.elements()) {
                types.put(each.element().getId(), each.element().eClass());
            }
        }
        Set<String> conversions = preparedConversions;
        if (conversions == null) {
            conversions = new LinkedHashSet<>();
            for (IArchimateRelationship each : resolution.conversions()) {
                conversions.add(each.getId());
            }
        }
        ChangeElementTypeResultDto preview = ChangeElementTypeCommand.Plan.read(model, resolution, defaultFigure)
                .toResult(Map.of(), Map.of(), false);
        return new PreparedMutation<>(new ChangeElementTypeCommand(model, changes, invalidRelationships, types,
                conversions, rule, defaultFigure, note, label), preview, resolution.elements().get(0).element().getId());
    }

    /**
     * Resolves every entry, or refuses the whole call with the first problem found. The checks
     * run in this order over all entries before any is looked at further: the shape of each entry,
     * then that no element is listed twice, then that every id names an element of this model,
     * then that every type is a concrete element type the element can change to, then that no
     * relationship of a listed element would be invalid under the types the call leaves, or, with
     * {@code to-association}, that each such relationship could be an Association. The choice of
     * {@code invalidRelationships} is checked with the shape of the entries.
     */
    static Resolution resolve(IArchimateModel model, List<ElementTypeChangeRequest> changes,
            String invalidRelationships, TypeRule rule) {
        if (changes == null || changes.isEmpty()) {
            throw invalid("changes must list at least one element",
                    "Pass changes: [{elementId, type}, ...].");
        }
        for (int i = 0; i < changes.size(); i++) {
            ElementTypeChangeRequest change = changes.get(i);
            if (change == null || change.elementId() == null || change.elementId().isBlank()) {
                throw invalid("changes[" + i + "] has no elementId",
                        "Give every entry the id of the element to change as elementId.");
            }
            if (change.type() == null || change.type().isBlank()) {
                throw invalid("changes[" + i + "] has no type",
                        "Give every entry the ArchiMate element type it should have, such as BusinessRole.");
            }
        }
        InvalidRelationships mode = invalidRelationships(invalidRelationships);

        Map<String, Integer> firstSeen = new HashMap<>();
        for (int i = 0; i < changes.size(); i++) {
            Integer earlier = firstSeen.putIfAbsent(changes.get(i).elementId(), i);
            if (earlier != null) {
                throw invalid("Element " + CallerText.bare(changes.get(i).elementId()) + " appears at both changes["
                        + earlier + "] and changes[" + i + "]: each element can be listed once in a call",
                        "Name each element once, with the one type it should have.");
            }
        }

        List<IArchimateElement> elements = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            elements.add(element(model, changes.get(i).elementId(), "changes[" + i + "]"));
        }

        List<Resolved> resolved = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            IArchimateElement element = elements.get(i);
            EClass to = elementType(changes.get(i).type(), "changes[" + i + "]");
            EClass junction = IArchimatePackage.eINSTANCE.getJunction();
            if (element.eClass() == junction || to == junction) {
                throw new ModelAccessException(element.eClass() == junction
                        ? CallerText.bare(element.getId()) + " (changes[" + i + "]) is a Junction: Archi does not "
                                + "change a Junction's type"
                        : "changes[" + i + "] asks for Junction: Archi does not change an element into a Junction",
                        ErrorCode.INVALID_ELEMENT_TYPE, null,
                        "Leave junctions out of this call.", null);
            }
            if (element.eClass() == to) {
                throw new ModelAccessException(CallerText.bare(element.getId()) + " (changes[" + i
                        + "]) is already a " + to.getName(), ErrorCode.INVALID_ELEMENT_TYPE, null,
                        "Leave it out of this call, or give the type it should change to.", null);
            }
            resolved.add(new Resolved(element, to));
        }

        return new Resolution(resolved, checkRelationships(model, resolved, mode, rule));
    }

    /** The choice as the call gave it: refuse when absent, refused by name when not one of the two. */
    private static InvalidRelationships invalidRelationships(String value) {
        if (value == null) {
            return InvalidRelationships.REFUSE;
        }
        for (InvalidRelationships each : InvalidRelationships.values()) {
            if (each.wire.equals(value)) {
                return each;
            }
        }
        throw invalid("invalidRelationships " + CallerText.quoted(value) + " is not one of refuse, to-association",
                "Use refuse (the default) or to-association.");
    }

    /**
     * The relationships of the listed elements that would be invalid once the call is done. Under
     * {@code refuse} any such relationship refuses the call; under {@code to-association} they are
     * returned, each once, in the order their folder holds them, after checking that each could be
     * an Association, as Archi checks before it converts one: between its ends, and with every
     * relationship attached to it.
     */
    private static List<IArchimateRelationship> checkRelationships(IArchimateModel model, List<Resolved> resolved,
            InvalidRelationships mode, TypeRule rule) {
        Map<IArchimateConcept, EClass> finalTypes = new IdentityHashMap<>();
        for (Resolved each : resolved) {
            finalTypes.put(each.element(), each.to());
        }
        Map<IArchimateRelationship, Integer> order = relationshipOrder(model);
        Set<IArchimateRelationship> seen = new LinkedHashSet<>();
        List<IArchimateRelationship> invalidRelationships = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        List<String> notAnAssociation = new ArrayList<>();
        EClass association = IArchimatePackage.eINSTANCE.getAssociationRelationship();
        for (Resolved each : resolved) {
            for (IArchimateRelationship relationship : relationshipsOf(order, each.element())) {
                if (!seen.add(relationship)) {
                    continue;
                }
                IArchimateConcept source = relationship.getSource();
                IArchimateConcept target = relationship.getTarget();
                EClass sourceType = finalTypes.getOrDefault(source, source.eClass());
                EClass targetType = finalTypes.getOrDefault(target, target.eClass());
                if (!rule.allows(sourceType, targetType, relationship.eClass())) {
                    String described = CallerText.bare(relationship.getId()) + " (" + relationship.eClass().getName()
                            + " from " + CallerText.bare(source.getId()) + " as " + sourceType.getName()
                            + " to " + CallerText.bare(target.getId()) + " as " + targetType.getName() + ")";
                    invalidRelationships.add(relationship);
                    invalid.add(described);
                    if (mode == InvalidRelationships.TO_ASSOCIATION
                            && !allowsAnAssociation(relationship, sourceType, targetType, association, finalTypes, rule)) {
                        notAnAssociation.add(described);
                    }
                }
            }
        }
        if (invalid.isEmpty()) {
            return List.of();
        }
        if (mode == InvalidRelationships.TO_ASSOCIATION) {
            if (!notAnAssociation.isEmpty()) {
                throw new ModelAccessException("The new types would leave " + notAnAssociation.size()
                        + " relationship" + (notAnAssociation.size() != 1 ? "s" : "")
                        + " that ArchiMate does not allow, and that cannot be made an Association either: "
                        + String.join("; ", notAnAssociation) + ". Nothing was changed.",
                        ErrorCode.RELATIONSHIP_NOT_ALLOWED, null,
                        "Delete those relationships first (delete-relationship), or choose a type that allows them.",
                        null);
            }
            invalidRelationships.sort(Comparator.comparingInt(r -> order.getOrDefault(r, Integer.MAX_VALUE)));
            return List.copyOf(invalidRelationships);
        }
        throw new ModelAccessException("The new types would leave " + invalid.size() + " relationship"
                + (invalid.size() != 1 ? "s" : "") + " that ArchiMate does not allow: "
                + String.join("; ", invalid) + ". Nothing was changed.",
                ErrorCode.RELATIONSHIP_NOT_ALLOWED, null,
                "Delete those relationships first (delete-relationship), or choose a type that allows them.",
                null);
    }

    /**
     * Whether {@code relationship} could be an Association once the call is done: between its two
     * ends, and as the end of each relationship attached to it.
     */
    private static boolean allowsAnAssociation(IArchimateRelationship relationship, EClass sourceType,
            EClass targetType, EClass association, Map<IArchimateConcept, EClass> finalTypes, TypeRule rule) {
        if (!rule.allows(sourceType, targetType, association)) {
            return false;
        }
        for (IArchimateRelationship attached : relationship.getSourceRelationships()) {
            IArchimateConcept end = attached.getTarget();
            if (!rule.allows(association, finalTypes.getOrDefault(end, end.eClass()), attached.eClass())) {
                return false;
            }
        }
        for (IArchimateRelationship attached : relationship.getTargetRelationships()) {
            IArchimateConcept end = attached.getSource();
            if (!rule.allows(finalTypes.getOrDefault(end, end.eClass()), association, attached.eClass())) {
                return false;
            }
        }
        return true;
    }

    /** The relationships with an end on {@code element}, once each, in the order their folder holds them. */
    static List<IArchimateRelationship> relationshipsOf(Map<IArchimateRelationship, Integer> order,
            IArchimateElement element) {
        Set<IArchimateRelationship> touching = new LinkedHashSet<>(element.getSourceRelationships());
        touching.addAll(element.getTargetRelationships());
        List<IArchimateRelationship> sorted = new ArrayList<>(touching);
        sorted.sort(Comparator.comparingInt(r -> order.getOrDefault(r, Integer.MAX_VALUE)));
        return sorted;
    }

    /** The order the relationships folder holds relationships in. */
    static Map<IArchimateRelationship, Integer> relationshipOrder(IArchimateModel model) {
        Map<IArchimateRelationship, Integer> order = new IdentityHashMap<>();
        IFolder relations = model.getFolder(FolderType.RELATIONS);
        if (relations != null) {
            int i = 0;
            for (TreeIterator<EObject> it = relations.eAllContents(); it.hasNext();) {
                if (it.next() instanceof IArchimateRelationship relationship) {
                    order.put(relationship, i++);
                }
            }
        }
        return order;
    }

    private static IArchimateElement element(IArchimateModel model, String id, String position) {
        EObject found = ArchimateModelUtils.getObjectByID(model, id);
        if (found instanceof IArchimateElement element) {
            return element;
        }
        if (found == null) {
            throw new ModelAccessException("Element not found: " + CallerText.bare(id) + " (" + position + ")",
                    ErrorCode.ELEMENT_NOT_FOUND, null,
                    "Use search-elements or get-element to find valid element IDs", null);
        }
        String kind = found instanceof IArchimateRelationship ? "a relationship"
                : found instanceof IDiagramModel ? "a view"
                : found instanceof IFolder ? "a folder"
                : found instanceof IDiagramModelConnection ? "a view connection"
                : found instanceof IDiagramModelObject ? "a view object"
                : "not an element";
        throw invalid(CallerText.bare(id) + " (" + position + ") is " + kind
                        + "; change-element-type changes elements only",
                "Pass element ids. get-view-contents gives the element id each view object shows.");
    }

    /** The concrete element type {@code name} names, or a refusal saying why it names none. */
    private static EClass elementType(String name, String position) {
        EClassifier classifier = IArchimatePackage.eINSTANCE.getEClassifier(name);
        String prefix = "Invalid ArchiMate element type: " + CallerText.bare(name) + " (" + position + ")";
        String suggestion = "Use an element type name as create-element takes, such as BusinessRole, "
                + "ApplicationComponent or Node.";
        if (!(classifier instanceof EClass eClass)) {
            throw new ModelAccessException(prefix, ErrorCode.INVALID_ELEMENT_TYPE, null, suggestion, null);
        }
        if (IArchimatePackage.eINSTANCE.getArchimateRelationship().isSuperTypeOf(eClass)) {
            throw new ModelAccessException(prefix + " is a relationship type", ErrorCode.INVALID_ELEMENT_TYPE,
                    null, suggestion, null);
        }
        if (!IArchimatePackage.eINSTANCE.getArchimateElement().isSuperTypeOf(eClass)) {
            throw new ModelAccessException(prefix + " is not an element type", ErrorCode.INVALID_ELEMENT_TYPE,
                    null, suggestion, null);
        }
        if (eClass.isAbstract() || eClass.isInterface()) {
            throw new ModelAccessException(prefix + " is abstract: it groups element types and names none of them",
                    ErrorCode.INVALID_ELEMENT_TYPE, null, suggestion, null);
        }
        return eClass;
    }

    private static ModelAccessException invalid(String message, String suggestion) {
        return new ModelAccessException(message, ErrorCode.INVALID_PARAMETER, null, suggestion, null);
    }

    /** The card's visible line: how many elements change, and the counts a reviewer weighs. */
    static String describe(ChangeElementTypeResultDto preview) {
        ChangeElementTypeResultDto.Totals t = preview.totals();
        return "Change the type of " + t.elementsChanged() + " element" + (t.elementsChanged() != 1 ? "s" : "")
                + " (" + t.relationshipsRepointed() + " relationship" + (t.relationshipsRepointed() != 1 ? "s" : "")
                + " re-pointed, " + t.diagramObjectsRepointed() + " view object"
                + (t.diagramObjectsRepointed() != 1 ? "s" : "") + " re-pointed, " + t.specializationsDropped()
                + " specialization" + (t.specializationsDropped() != 1 ? "s" : "") + " dropped"
                + (t.relationshipsConverted() > 0 ? ", " + t.relationshipsConverted() + " relationship"
                        + (t.relationshipsConverted() != 1 ? "s" : "") + " converted to Association" : "")
                + ")";
    }

    private static List<Map<String, Object>> describeChanges(ChangeElementTypeResultDto preview) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChangeElementTypeResultDto.Change change : preview.changes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("elementId", change.elementId());
            entry.put("name", change.name());
            entry.put("fromType", change.fromType());
            entry.put("toType", change.toType());
            entry.put("relationshipsRepointed", change.relationshipsRepointed());
            entry.put("diagramObjectsRepointed", change.diagramObjectsRepointed());
            entry.put("figureTypesReset", change.figureTypesReset() == null ? 0 : change.figureTypesReset().size());
            entry.put("folderId", change.folderId());
            entry.put("folderChanged", change.folderChanged());
            if (change.specializationDropped() != null) {
                entry.put("specializationDropped", change.specializationDropped().name());
            }
            if (change.relationshipsConverted() != null) {
                List<Map<String, Object>> converted = new ArrayList<>();
                for (ChangeElementTypeResultDto.RelationshipConverted each : change.relationshipsConverted()) {
                    Map<String, Object> relationship = new LinkedHashMap<>();
                    relationship.put("relationshipId", each.relationshipId());
                    relationship.put("fromType", each.fromType());
                    relationship.put("source", each.sourceId());
                    relationship.put("target", each.targetId());
                    if (each.attributesDropped() != null) {
                        Map<String, Object> attributes = new LinkedHashMap<>();
                        if (each.attributesDropped().accessType() != null) {
                            attributes.put("accessType", each.attributesDropped().accessType());
                        }
                        if (each.attributesDropped().influenceStrength() != null) {
                            attributes.put("influenceStrength", each.attributesDropped().influenceStrength());
                        }
                        relationship.put("attributesDropped", attributes);
                    }
                    if (each.specializationDropped() != null) {
                        relationship.put("specializationDropped", each.specializationDropped().name());
                    }
                    converted.add(relationship);
                }
                entry.put("relationshipsConverted", converted);
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * Every listed element and every relationship the card converts: a human edit to any of them
     * while the proposal waits makes the proposal stale rather than approving a change the card no
     * longer describes.
     */
    private static Set<String> changeTargetIds(List<ElementTypeChangeRequest> changes,
            ChangeElementTypeResultDto preview) {
        Set<String> ids = new LinkedHashSet<>();
        for (ElementTypeChangeRequest change : changes) {
            ids.add(change.elementId());
        }
        for (ChangeElementTypeResultDto.Change change : preview.changes()) {
            if (change.relationshipsConverted() != null) {
                for (ChangeElementTypeResultDto.RelationshipConverted each : change.relationshipsConverted()) {
                    ids.add(each.relationshipId());
                }
            }
        }
        return ids;
    }

    private static String elementsForMessage(List<ElementTypeChangeRequest> changes) {
        if (changes == null || changes.isEmpty() || changes.get(0) == null) {
            return "the requested elements";
        }
        String first = CallerText.quoted(changes.get(0).elementId());
        int others = changes.size() - 1;
        return others == 0 ? first : first + " and " + others + " other element" + (others != 1 ? "s" : "");
    }

    /**
     * Refuses while the session's batch is open: a queued operation would hold the element this
     * replaces.
     */
    private static void refuseInsideABatch(MutationDispatcher mutations, String sessionId) {
        if (mutations.getMode(sessionId) == OperationalMode.BATCH) {
            throw new ModelAccessException(
                    "change-element-type cannot run inside a batch: it replaces each element with one of "
                            + "the new type, which a queued operation could not see. Nothing was queued.",
                    ErrorCode.BATCH_ALREADY_ACTIVE, null,
                    "Call end-batch first (with rollback: true to discard the queue), then "
                            + "change-element-type on its own.", null);
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
