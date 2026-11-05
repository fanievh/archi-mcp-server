package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProfile;

import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto;
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto.FigureTypeReset;
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto.RelationshipConverted;
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto.SpecializationDropped;
import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;

/**
 * Changes the type of the listed elements in place: each is replaced by an element of the new
 * type that keeps its id, name, documentation, properties and features, and everything that
 * referred to the old element is re-pointed to the new one rather than recreated.
 *
 * <p>This is Archi's own type change, with two differences. The new element keeps the old one's
 * id, so a reference held outside the model still resolves; and when it stays in the old
 * element's folder it takes the old element's place there, not the end of the list. The folder
 * rule is otherwise Archi's: the old folder is kept when it is the new type's default folder or
 * lies inside it, and the new type's default folder is used otherwise. The specialization is not
 * carried over, since a specialization belongs to one type, and every box is given the new type's
 * default figure, as Archi does.</p>
 *
 * <p><strong>Decided when it runs.</strong> The call is resolved and every effect is read from the
 * model inside {@link #execute()}, not when the call was prepared: an approved proposal runs after
 * a human may have edited the model, and a relationship added to an element in the meantime must
 * move with it. The command writes nothing and reports why through {@link #getSkipReason()} when
 * the call no longer resolves (an element gone, a relationship that the new types would make
 * invalid) or when an element no longer has the type the call was prepared against.
 * {@code canExecute} stays true: a refusal there would make the stack reject the entry.</p>
 *
 * <p><strong>Order.</strong> For each element: the new element goes into its folder, then every
 * relationship end moves to it, then every box shows it, and only then does the old element leave
 * its folder. Between the first and the last step two elements of the model carry the same id;
 * nothing looks an id up in between. A box is taken out of its parent, given its new element and
 * figure and put back at the index it had: {@code setArchimateElement} notifies nothing, and the
 * re-add is what makes an open diagram redraw it. A view connection is never
 * {@code reconnect()}ed, so no relationship's ends are re-derived from the boxes it joins.</p>
 *
 * <p><strong>Relationships the new types do not allow.</strong> When the call asks for it, each
 * such relationship is replaced by an Association through {@link RelationshipRetype}, as Archi's
 * editor does once asked. This runs after every element has its new type, so that an Association
 * between two listed elements joins both new elements. The command converts only relationships
 * the call was prepared to convert: one that has become invalid since makes it write nothing, and
 * one that has become valid, or gone, since is simply left as it is. Whether Archi's
 * "(Changed from X)" note is added is read from the preference as the command runs.</p>
 *
 * <p><strong>Undo and redo.</strong> Every write is recorded as it is made, with what reversing it
 * needs. Undo reverses the record, so the old element is back in its folder at its index with
 * every reference; redo replays the record without reading the model again. An undo or a redo that fails
 * partway leaves the command not applied, as far as it can, and still throws: see
 * {@link RecordedSteps}.</p>
 */
final class ChangeElementTypeCommand extends Command implements CommitSkippableCommand {

    private final IArchimateModel model;
    private final List<ElementTypeChangeRequest> changes;
    private final String invalidRelationships;
    private final Map<String, EClass> preparedTypes;
    private final Set<String> preparedConversions;
    private final ChangeElementTypeOperations.TypeRule rule;
    private final ToIntFunction<EClass> defaultFigure;
    private final BooleanSupplier note;
    private final Function<EClass, String> label;

    private final RecordedSteps record = new RecordedSteps();
    private ChangeElementTypeResultDto result;
    private String skipReason;
    /** Set where the last run declined with nothing it wrote left behind. */
    private boolean changedNothing;
    /** Set where the last run declined and could not undo all of what it wrote. */
    private boolean leftChanges;

    /**
     * @param invalidRelationships what to do with a relationship the new types do not allow, as
     *        the call gave it: {@code null} or {@code refuse}, or {@code to-association}
     * @param preparedTypes each listed id with the type its element had when the call was
     *        prepared; the command declines when an element's type has changed since
     * @param preparedConversions the ids of the relationships the call was prepared to convert;
     *        the command declines when it would convert any other
     * @param rule whether ArchiMate allows a relationship type between two element types
     * @param defaultFigure the figure Archi draws a new box of a type with
     * @param note whether Archi's note is added to a converted relationship's documentation
     * @param label the name Archi gives a type, which the note quotes
     */
    ChangeElementTypeCommand(IArchimateModel model, List<ElementTypeChangeRequest> changes,
            String invalidRelationships, Map<String, EClass> preparedTypes, Set<String> preparedConversions,
            ChangeElementTypeOperations.TypeRule rule, ToIntFunction<EClass> defaultFigure, BooleanSupplier note,
            Function<EClass, String> label) {
        this.model = model;
        this.changes = List.copyOf(changes);
        this.invalidRelationships = invalidRelationships;
        this.preparedTypes = Map.copyOf(preparedTypes);
        this.preparedConversions = Set.copyOf(preparedConversions);
        this.rule = rule;
        this.defaultFigure = defaultFigure;
        this.note = note;
        this.label = label;
        setLabel("Change element type");
    }

    /** The type each listed element had when the call was prepared. */
    Map<String, EClass> getPreparedTypes() {
        return preparedTypes;
    }

    /** The ids of the relationships the call was prepared to convert to Associations. */
    Set<String> getPreparedConversions() {
        return preparedConversions;
    }

    /** What the last execute did, or null when it declined. */
    ChangeElementTypeResultDto getResult() {
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
        ChangeElementTypeOperations.Resolution resolution;
        try {
            resolution = ChangeElementTypeOperations.resolve(model, changes, invalidRelationships, rule);
        } catch (ModelAccessException e) {
            skipReason = "change-element-type wrote nothing: " + e.getMessage();
            changedNothing = true;
            return;
        }
        for (ChangeElementTypeOperations.Resolved each : resolution.elements()) {
            EClass prepared = preparedTypes.get(each.element().getId());
            if (prepared != each.element().eClass()) {
                skipReason = "change-element-type wrote nothing: " + CallerText.bare(each.element().getId())
                        + " is now a " + each.element().eClass().getName() + ", not the "
                        + (prepared != null ? prepared.getName() : "type") + " the call was prepared against";
                changedNothing = true;
                return;
            }
        }
        for (IArchimateRelationship each : resolution.conversions()) {
            if (!preparedConversions.contains(each.getId())) {
                skipReason = "change-element-type wrote nothing: the change would also convert "
                        + CallerText.bare(each.getId()) + " to an Association, which the call was not prepared "
                        + "to convert";
                changedNothing = true;
                return;
            }
        }
        Plan plan = Plan.read(model, resolution, defaultFigure);
        try {
            boolean noteAdded = !plan.conversions.isEmpty() && note.getAsBoolean();
            Map<ElementPlan, IArchimateElement> created = new IdentityHashMap<>();
            Map<IArchimateRelationship, IArchimateRelationship> converted = new IdentityHashMap<>();
            write(plan, noteAdded, created, converted);
            result = plan.toResult(created, converted, noteAdded);
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
                    ? "change-element-type stopped and undid its own changes: " + RecordedSteps.reason(e)
                    : "change-element-type stopped and could not undo all of its own changes: " + RecordedSteps.reason(e)
                            + "; undoing then failed with: " + RecordedSteps.reason(revertFailure);
        }
    }

    /**
     * Every element first, then every relationship to convert, so that a converted relationship
     * between two listed elements joins both new elements.
     */
    private void write(Plan plan, boolean noteAdded, Map<ElementPlan, IArchimateElement> created,
            Map<IArchimateRelationship, IArchimateRelationship> converted) {
        for (ElementPlan each : plan.elements) {
            IArchimateElement old = each.element;
            IArchimateElement replacement = replacementFor(old, each.to);
            created.put(each, replacement);
            record.apply(new ElementAdd(each.folder,
                    each.folderChanged ? -1 : each.folder.getElements().indexOf(old), replacement));
            for (IArchimateRelationship relationship : each.relationships) {
                if (relationship.getSource() == old) {
                    record.apply(new RelationshipRetype.EndStep(relationship, true, replacement));
                }
                if (relationship.getTarget() == old) {
                    record.apply(new RelationshipRetype.EndStep(relationship, false, replacement));
                }
            }
            for (IDiagramModelArchimateObject box : each.boxes) {
                record.apply(new BoxStep(box, replacement, each.figure));
            }
            if (!old.getSourceRelationships().isEmpty() || !old.getTargetRelationships().isEmpty()
                    || !old.getReferencingDiagramComponents().isEmpty()) {
                throw new IllegalStateException("element " + CallerText.quoted(old.getId())
                        + " is still referenced after re-pointing");
            }
            record.apply(new ElementRemoval(old));
        }
        for (ConversionPlan each : plan.conversions) {
            IArchimateRelationship replacement = RelationshipRetype.replacementFor(each.retype.relationship,
                    each.retype.to, noteAdded, label);
            converted.put(each.retype.relationship, replacement);
            RelationshipRetype.write(each.retype, replacement, record::apply);
        }
    }

    /**
     * The new element: the old one's id, name, documentation, and copies of its properties and
     * features, which the old element keeps for undo. Its specialization is not carried over.
     */
    private static IArchimateElement replacementFor(IArchimateElement old, EClass type) {
        IArchimateElement replacement = (IArchimateElement) IArchimateFactory.eINSTANCE.create(type);
        replacement.setId(old.getId());
        replacement.setName(old.getName());
        replacement.getProperties().addAll(EcoreUtil.copyAll(old.getProperties()));
        replacement.getFeatures().addAll(EcoreUtil.copyAll(old.getFeatures()));
        replacement.setDocumentation(old.getDocumentation());
        return replacement;
    }

    @Override
    public void undo() {
        record.undo();
    }

    @Override
    public void redo() {
        record.redo();
    }

    // ---- the reading ----------------------------------------------------------------------------

    /** One listed element and everything changing its type would re-point, before anything is written. */
    static final class ElementPlan {
        final IArchimateElement element;
        final EClass to;
        final List<IArchimateRelationship> relationships = new ArrayList<>();
        final List<IDiagramModelArchimateObject> boxes = new ArrayList<>();
        final Map<IDiagramModelArchimateObject, IDiagramModel> views = new IdentityHashMap<>();
        final Map<IDiagramModelArchimateObject, Integer> figuresBefore = new IdentityHashMap<>();
        final int figure;
        final IFolder folder;
        final boolean folderChanged;

        ElementPlan(IArchimateElement element, EClass to, int figure, IFolder folder, boolean folderChanged) {
            this.element = element;
            this.to = to;
            this.figure = figure;
            this.folder = folder;
            this.folderChanged = folderChanged;
        }
    }

    /**
     * A relationship to convert to an Association, what it loses, and the element it is reported
     * under: the first listed element it joins.
     */
    static final class ConversionPlan {
        final RelationshipRetype.Plan retype;
        final ElementPlan owner;
        final String fromType;
        final String sourceId;
        final String targetId;
        final RelationshipSemanticAttributes attributesDropped;
        final SpecializationDropped specializationDropped;

        ConversionPlan(RelationshipRetype.Plan retype, ElementPlan owner) {
            this.retype = retype;
            this.owner = owner;
            IArchimateRelationship relationship = retype.relationship;
            this.fromType = relationship.eClass().getName();
            this.sourceId = relationship.getSource().getId();
            this.targetId = relationship.getTarget().getId();
            String accessType = DtoMapper.accessTypeForDto(relationship);
            String influenceStrength = DtoMapper.influenceStrengthForDto(relationship);
            this.attributesDropped = accessType == null && influenceStrength == null ? null
                    : new RelationshipSemanticAttributes(accessType, null, influenceStrength);
            IProfile profile = relationship.getPrimaryProfile();
            this.specializationDropped = profile == null ? null
                    : new SpecializationDropped(profile.getName(), profile.getConceptType());
        }
    }

    /**
     * What a type change would do to the model as it is now, read without writing to it. The
     * lists keep model order: relationships in the order their folder holds them, views and their
     * boxes in the order the model holds them.
     */
    static final class Plan {
        final List<ElementPlan> elements = new ArrayList<>();
        final List<ConversionPlan> conversions = new ArrayList<>();

        static Plan read(IArchimateModel model, ChangeElementTypeOperations.Resolution resolution,
                ToIntFunction<EClass> defaultFigure) {
            List<ChangeElementTypeOperations.Resolved> resolved = resolution.elements();
            Plan plan = new Plan();
            Map<IArchimateElement, ElementPlan> byElement = new IdentityHashMap<>();
            Map<IArchimateRelationship, Integer> order = ChangeElementTypeOperations.relationshipOrder(model);
            for (ChangeElementTypeOperations.Resolved each : resolved) {
                IArchimateElement element = each.element();
                IFolder current = (IFolder) element.eContainer();
                IFolder folder = folderFor(model, element, each.to());
                ElementPlan elementPlan = new ElementPlan(element, each.to(), defaultFigure.applyAsInt(each.to()),
                        folder, folder != current);
                elementPlan.relationships.addAll(ChangeElementTypeOperations.relationshipsOf(order, element));
                plan.elements.add(elementPlan);
                byElement.put(element, elementPlan);
            }
            for (IDiagramModel view : model.getDiagramModels()) {
                for (TreeIterator<EObject> it = view.eAllContents(); it.hasNext();) {
                    if (it.next() instanceof IDiagramModelArchimateObject box) {
                        ElementPlan owner = byElement.get(box.getArchimateElement());
                        if (owner != null) {
                            owner.boxes.add(box);
                            owner.views.put(box, view);
                            owner.figuresBefore.put(box, box.getType());
                        }
                    }
                }
            }
            for (RelationshipRetype.Plan retype : RelationshipRetype.Plan.read(model, resolution.conversions(),
                    IArchimatePackage.eINSTANCE.getAssociationRelationship())) {
                ElementPlan owner = null;
                for (ElementPlan each : plan.elements) {
                    if (each.relationships.contains(retype.relationship)) {
                        owner = each;
                        break;
                    }
                }
                plan.conversions.add(new ConversionPlan(retype, owner));
            }
            return plan;
        }

        /**
         * Archi's rule: the element's own folder when the new type's default folder is that folder
         * or one of its ancestors, the new type's default folder otherwise.
         */
        private static IFolder folderFor(IArchimateModel model, IArchimateElement element, EClass to) {
            IFolder defaultFolder = model.getDefaultFolderForObject(IArchimateFactory.eINSTANCE.create(to));
            for (EObject e = element.eContainer(); e instanceof IFolder parent; e = parent.eContainer()) {
                if (parent == defaultFolder) {
                    return (IFolder) element.eContainer();
                }
            }
            return defaultFolder;
        }

        /**
         * The report. {@code created} holds the element written for each plan and
         * {@code converted} the Association written for each relationship converted, or both are
         * empty for a preview, which reports the ids they will keep.
         */
        ChangeElementTypeResultDto toResult(Map<ElementPlan, IArchimateElement> created,
                Map<IArchimateRelationship, IArchimateRelationship> converted, boolean noteAdded) {
            List<ChangeElementTypeResultDto.Change> out = new ArrayList<>();
            Map<String, String> idMap = new LinkedHashMap<>();
            Set<IArchimateRelationship> relationships = new LinkedHashSet<>();
            int boxes = 0;
            int figures = 0;
            int specializations = 0;
            int folders = 0;
            for (ElementPlan each : elements) {
                IArchimateElement written = created.get(each);
                String resultingId = written != null ? written.getId() : each.element.getId();
                List<FigureTypeReset> reset = new ArrayList<>();
                for (IDiagramModelArchimateObject box : each.boxes) {
                    int from = each.figuresBefore.get(box);
                    if (from != each.figure) {
                        reset.add(new FigureTypeReset(box.getId(), each.views.get(box).getId(), from, each.figure));
                    }
                }
                IProfile profile = each.element.getPrimaryProfile();
                SpecializationDropped dropped = profile == null ? null
                        : new SpecializationDropped(profile.getName(), profile.getConceptType());
                List<RelationshipConverted> conversionsHere = new ArrayList<>();
                for (ConversionPlan conversion : conversions) {
                    if (conversion.owner == each) {
                        IArchimateRelationship old = conversion.retype.relationship;
                        IArchimateRelationship writtenRelationship = converted.get(old);
                        conversionsHere.add(new RelationshipConverted(old.getId(),
                                writtenRelationship != null ? writtenRelationship.getId() : old.getId(),
                                conversion.fromType, conversion.retype.to.getName(), conversion.sourceId,
                                conversion.targetId, conversion.retype.connections.size(),
                                conversion.attributesDropped, conversion.specializationDropped,
                                writtenRelationship != null && noteAdded ? Boolean.TRUE : null));
                    }
                }
                out.add(new ChangeElementTypeResultDto.Change(each.element.getId(), resultingId,
                        each.element.getName(), each.element.eClass().getName(), each.to.getName(),
                        each.relationships.size(), each.boxes.size(), each.folder.getId(), each.folderChanged,
                        reset.isEmpty() ? null : List.copyOf(reset), dropped,
                        conversionsHere.isEmpty() ? null : List.copyOf(conversionsHere)));
                idMap.put(each.element.getId(), resultingId);
                relationships.addAll(each.relationships);
                boxes += each.boxes.size();
                figures += reset.size();
                specializations += dropped != null ? 1 : 0;
                folders += each.folderChanged ? 1 : 0;
            }
            return new ChangeElementTypeResultDto(out, idMap, new ChangeElementTypeResultDto.Totals(elements.size(),
                    relationships.size(), boxes, figures, specializations, folders, conversions.size()));
        }
    }

    // ---- the steps ------------------------------------------------------------------------------

    /** The new element put into its folder, at {@code index} or, when that is -1, at the end. */
    private static final class ElementAdd implements RecordedSteps.Step {
        private final IFolder folder;
        private final int index;
        private final IArchimateElement element;

        ElementAdd(IFolder folder, int index, IArchimateElement element) {
            this.folder = folder;
            this.index = index;
            this.element = element;
        }

        @Override
        public void apply() {
            EList<EObject> elements = folder.getElements();
            if (index < 0) {
                elements.add(element);
            } else {
                elements.add(index, element);
            }
        }

        @Override
        public void revert() {
            folder.getElements().remove(element);
        }
    }

    /**
     * A box shown with the new element and figure, at the index it held among its siblings; undo
     * puts back the old element and the figure it had.
     */
    private static final class BoxStep implements RecordedSteps.Step {
        private final IDiagramModelArchimateObject box;
        private final IDiagramModelContainer parent;
        private final int index;
        private final IArchimateElement from;
        private final IArchimateElement to;
        private final int figureFrom;
        private final int figureTo;

        BoxStep(IDiagramModelArchimateObject box, IArchimateElement to, int figureTo) {
            this.box = box;
            this.parent = (IDiagramModelContainer) box.eContainer();
            this.index = parent.getChildren().indexOf(box);
            this.from = box.getArchimateElement();
            this.to = to;
            this.figureFrom = box.getType();
            this.figureTo = figureTo;
        }

        @Override
        public void apply() {
            show(to, figureTo);
        }

        @Override
        public void revert() {
            show(from, figureFrom);
        }

        private void show(IArchimateElement element, int figure) {
            parent.getChildren().remove(box);
            box.setArchimateElement(element);
            box.setType(figure);
            parent.getChildren().add(Math.min(index, parent.getChildren().size()), box);
        }
    }

    /** The old element taken out of its folder; undo puts it back at the index it had. */
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
}
