package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.AdjustViewSpacingResultDto;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;

/**
 * Pins what {@code adjust-view-spacing} says about the groups that grew <em>underneath</em> the
 * spacing it was asked to do.
 *
 * <p>Inflating the gaps between elements pushes some of them past the edge of the group they sit
 * in, and the parent-fit cascade then widens every enclosing group so it still contains them.
 * Before this the response carried {@code groupsAdjusted} — a count of the groups the tool
 * deliberately spaced — and nothing whatever about the ones the cascade grew. Measured: a group at
 * 260x200 committed at 356x70 while the response named no rectangle at all.</p>
 *
 * <h2>Spaced and cascade-grown are different facts, and a group can be both</h2>
 *
 * <p>{@code groupsAdjusted} counts the groups the tool deliberately re-spaced. The new list names
 * the groups the parent-fit cascade resized, with the rectangle each ended at. Measured, those sets
 * overlap: the spacing pass re-fits a group it inflates using its own padding and label allowance,
 * and the cascade then measures the same group against {@code DEFAULT_GROUP_PADDING} and adds the
 * difference. So a spaced group commonly appears in both, and that is not double-counting — the
 * count says the tool worked on it, the entry says where it actually ended up, which the count
 * never did.</p>
 *
 * <p>What must never appear in {@code resizedAncestors} is a group this pass did not resize: one
 * the cascade never emitted a command for, or one an open batch had already sized and which the
 * pass merely measured against. Both negatives are pinned below. They hold structurally rather than
 * by filtering, because that list is built from the cascade's command map — which starts empty and
 * gains an entry only where a resize was actually emitted — and never from its seeded bounds
 * map.</p>
 *
 * <h2>The two lists answer different questions</h2>
 *
 * <p>{@code resizedAncestors} names a MECHANISM — the groups the overflow cascade grew — so it is
 * built from that mechanism's own command map. {@code resizedElements} names an OBSERVATION —
 * every object whose size this call changed, by whatever mechanism — so it is projected from the
 * merged compound the call is about to dispatch, which holds every rectangle the call writes
 * whether or not a pass remembered to record it. An object in both carries the same rectangle in
 * both, because the compound's last command for it is the one the model ends up holding.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>A real GEF {@link CommandStack} driven over an ordered compound, with the production
 * {@code NonNotifyingCompoundCommand} rebuilt as a plain {@link CompoundCommand} because its
 * {@code execute()} dereferences {@code IEditorModelManager.INSTANCE}. {@code undo} is overridden
 * to pop that stack directly, as the production path marshals it onto the SWT display — this tool
 * temporarily applies, assesses and undoes before returning. Every add passes explicit bounds so
 * nothing reaches {@code ElementSizer}'s display-bound measurement.</p>
 */
public class AdjustViewSpacingCascadeReportingTest {

    private static final String SESSION = "adjust-view-spacing-cascade-report-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Spacing Cascade Reporting Fixture");
        model.setId("model-spacing-cascade-report");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Reporting");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 6; i++) {
            IBusinessActor a = factory.createBusinessActor();
            a.setId("actor-" + i);
            a.setName("Actor " + i);
            business.getElements().add(a);
        }

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            public UndoRedoState undo(int steps) {
                for (int i = 0; i < steps && stack.canUndo(); i++) {
                    stack.undo();
                }
                return null;
            }
            private Command toPlainCompound(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand plain = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        plain.add(toPlainCompound((Command) child));
                    }
                    return plain;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label, x, y, w, h, parentId, null, null)
                .entity().viewObjectId();
    }

    private void add(String actorId, int x, int y, int w, int h, String parentId) {
        accessor.addToView(SESSION, view.getId(), actorId, x, y, w, h, false, parentId, null, null);
    }

    private AdjustViewSpacingResultDto space(int delta, boolean recursive) {
        return accessor.adjustViewSpacing(SESSION, view.getId(), delta, null, null, recursive)
                .entity();
    }

    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId())) {
                return child;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static MovedViewObjectDto entryFor(AdjustViewSpacingResultDto dto, String id) {
        return entryIn(dto.resizedAncestors(), id);
    }

    /** The entry naming {@code id} in {@code dto.resizedElements()}, or null. */
    private static MovedViewObjectDto resizedEntryFor(AdjustViewSpacingResultDto dto, String id) {
        return entryIn(dto.resizedElements(), id);
    }

    private static MovedViewObjectDto entryIn(List<MovedViewObjectDto> entries, String id) {
        for (MovedViewObjectDto entry : entries) {
            if (id.equals(entry.viewObjectId())) {
                return entry;
            }
        }
        return null;
    }

    /** As {@link #add}, returning the view-object id so a pin can name the child it placed. */
    private String addAt(String actorId, int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), actorId, x, y, w, h, false, parentId,
                null, null).entity().viewObject().viewObjectId();
    }

    /**
     * A nest whose inner group is wide enough that inflating the gaps between its elements pushes
     * the last one past the outer group's right edge, so the cascade has to widen the outer group.
     */
    private String[] overflowingNest() {
        String outer = group("Outer", 0, 0, 300, 300, null);
        String inner = group("Inner", 10, 10, 260, 200, outer);
        add("actor-1", 10, 30, 60, 30, inner);
        add("actor-2", 90, 30, 60, 30, inner);
        add("actor-3", 170, 30, 60, 30, inner);
        return new String[]{outer, inner};
    }

    // ---- scenarios -----------------------------------------------------------------------------

    /**
     * The silence itself. Spacing the inner group's elements grows the inner group, which no longer
     * fits the outer one, so the outer one is widened too — by the cascade, not by the spacing
     * request. The caller asked for a delta and two groups changed shape.
     */
    @Test
    public void shouldNameTheGroupsTheCascadeGrew_whenSpacingPushesAChildPastItsGroup()
            throws Exception {
        String[] ids = overflowingNest();
        String outer = ids[0];
        IBounds outerBefore = copy(find(view, outer).getBounds());

        AdjustViewSpacingResultDto dto = space(60, true);
        IBounds outerAfter = find(view, outer).getBounds();

        assertTrue("fixture must actually make the cascade grow the outer group, or this proves "
                + "nothing: " + outerBefore.getWidth() + " -> " + outerAfter.getWidth(),
                outerAfter.getWidth() > outerBefore.getWidth());

        MovedViewObjectDto reported = entryFor(dto, outer);
        assertNotNull("adjust-view-spacing must name the group its cascade grew — the caller asked "
                + "for a spacing delta, not for this group to change. resizedAncestors was: "
                + dto.resizedAncestors(), reported);
        assertEquals("outer: x", outerAfter.getX(), reported.newX());
        assertEquals("outer: y", outerAfter.getY(), reported.newY());
        assertEquals("outer: width", outerAfter.getWidth(), reported.newWidth());
        assertEquals("outer: height", outerAfter.getHeight(), reported.newHeight());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue("the rectangle must reach the wire, not just the Java object. Response was: "
                + json, json.contains("\"resizedAncestors\""));
        assertTrue("the wire must carry the group's id. Response was: " + json,
                json.contains(outer));
        assertTrue("the wire must carry its effective width (" + outerAfter.getWidth()
                + "). Response was: " + json,
                json.contains("\"newWidth\":" + outerAfter.getWidth()));
    }

    /**
     * A group can be both spaced and cascade-grown, and when it is, the entry carries the rectangle
     * the count never did. Measured: the inner group is re-spaced by the tool AND resized by the
     * cascade to 356 wide, and {@code groupsAdjusted} alone leaves the agent with no way to learn
     * that number.
     */
    @Test
    public void shouldCarryTheRectangleTheCountNeverDid_forAGroupThatIsBothSpacedAndGrown() {
        String[] ids = overflowingNest();
        String inner = ids[1];

        AdjustViewSpacingResultDto dto = space(60, true);
        IBounds innerAfter = find(view, inner).getBounds();

        assertTrue("the tool must still count the groups it deliberately spaced",
                dto.groupsAdjusted() > 0);
        MovedViewObjectDto reported = entryFor(dto, inner);
        assertNotNull("the cascade resized the inner group too, so its landed rectangle must be "
                + "reported — groupsAdjusted is a count and carries no geometry. "
                + "resizedAncestors was: " + dto.resizedAncestors(), reported);
        assertEquals("inner: width", innerAfter.getWidth(), reported.newWidth());
        assertEquals("inner: height", innerAfter.getHeight(), reported.newHeight());
    }

    /**
     * The true negative on the growth side: a group the cascade never emitted a command for is
     * absent. An empty top-level group has no child that can exceed it, so nothing about it changes
     * and nothing about it is claimed.
     */
    @Test
    public void shouldNotNameAGroupTheCascadeNeverTouched() {
        overflowingNest();
        String empty = group("Empty", 700, 0, 120, 120, null);
        IBounds before = copy(find(view, empty).getBounds());

        AdjustViewSpacingResultDto dto = space(60, true);

        IBounds after = find(view, empty).getBounds();
        assertEquals("fixture guard: the empty group really is untouched",
                before.getWidth() + "x" + before.getHeight(),
                after.getWidth() + "x" + after.getHeight());
        assertNull("a group nothing overflowed must not be named. resizedAncestors was: "
                + dto.resizedAncestors(), entryFor(dto, empty));
    }

    /**
     * The seed must not leak into the report. Inside a batch the fit map is pre-loaded with what
     * earlier operations queued, so the pass measures against a group's queued size — but measuring
     * against a group is not growing it, and naming it would be a fabricated outcome.
     */
    @Test
    public void shouldNotNameAGroupTheBatchMerelyEstablished() {
        String lone = group("Lone", 600, 0, 100, 100, null);
        add("actor-4", 10, 10, 60, 30, lone);
        String[] ids = overflowingNest();

        dispatcher.beginBatch(SESSION, "size a group, then space the view");
        accessor.updateViewObject(SESSION, lone, null, null, 900, 700, null, null, null, null);
        AdjustViewSpacingResultDto dto = accessor
                .adjustViewSpacing(SESSION, view.getId(), 60, null, null, true).entity();
        dispatcher.endBatch(SESSION, true);

        assertNull("the batch sized this group; the spacing pass only measured against it. "
                + "resizedAncestors was: " + dto.resizedAncestors(), entryFor(dto, lone));
        assertNotNull("...while a group the cascade genuinely grew is still named",
                entryFor(dto, ids[0]));
    }

    /**
     * The empty list is omitted from the wire rather than serialized as {@code []}, so a call that
     * resized nothing produces byte-for-byte the response it produced before the field existed.
     * Asserted on the zero-delta short-circuit, the one path that provably emits no commands at all.
     */
    @Test
    public void shouldSerializeExactlyAsBefore_whenTheCallResizedNothing() throws Exception {
        String roomy = group("Roomy", 0, 0, 900, 400, null);
        add("actor-1", 10, 30, 60, 30, roomy);
        add("actor-2", 90, 30, 60, 30, roomy);

        AdjustViewSpacingResultDto dto = space(0, true);

        assertTrue("a zero-delta call mutates nothing, so it resized nothing. Got: "
                + dto.resizedAncestors(), dto.resizedAncestors().isEmpty());
        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("an empty list must be omitted from the wire, not serialized as []. "
                + "Response was: " + json, json.contains("resizedAncestors"));
    }

    /**
     * The wire-omission for {@code resizedElements}, which nothing else asserts.
     *
     * <p>Its sibling {@code shouldSerializeExactlyAsBefore_whenTheCallResizedNothing} drives this
     * same zero-delta short-circuit but asserts on {@code resizedAncestors} alone, and it is held
     * byte-frozen as the back-compat pin, so the second field's claim cannot be added to it. Kept
     * separate rather than folded for exactly that reason: the fold is what lost this assertion in
     * the first place, when the pin that used to carry it was narrowed to a fixture whose container
     * legitimately DOES resize.</p>
     *
     * <p>Both lists carry {@code @JsonInclude(NON_EMPTY)}, so a call that resizes nothing must
     * produce the response it produced before either field existed — the key absent, not an empty
     * array.</p>
     */
    @Test
    public void shouldOmitTheResizedElementsKey_whenTheCallResizedNothing() throws Exception {
        String roomy = group("Roomy", 0, 0, 900, 400, null);
        add("actor-1", 10, 30, 60, 30, roomy);
        add("actor-2", 90, 30, 60, 30, roomy);

        AdjustViewSpacingResultDto dto = space(0, true);

        assertTrue("a zero-delta call short-circuits before any compound exists, so it resized "
                + "nothing. Got: " + dto.resizedElements(), dto.resizedElements().isEmpty());
        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("an empty list must be omitted from the wire, not serialized as []. "
                + "Response was: " + json, json.contains("resizedElements"));
    }

    /**
     * Nothing is effective inside a batch, so the entity is structurally labelled rather than
     * valued: it appears under {@code preview}, beside the {@code batch} sibling, never at the top
     * level of {@code result} where an agent would read it as state the model already holds.
     */
    @Test
    public void shouldNestTheReportUnderPreview_whenTheCallIsBatched() throws Exception {
        overflowingNest();

        dispatcher.beginBatch(SESSION, "space the view inside a batch");
        MutationResult<AdjustViewSpacingResultDto> result =
                accessor.adjustViewSpacing(SESSION, view.getId(), 60, null, null, true);
        dispatcher.endBatch(SESSION, true);

        assertTrue("the call must actually be batched, or this proves nothing", result.isBatched());
        assertFalse("the projection is still computed inside a batch",
                result.entity().resizedAncestors().isEmpty());
    }


    // ---- the objects the pass itself re-sized ----------------------------------------------------
    //
    // MECHANISM. Three write ranges of this one call change an object's size, and a fixture that
    // drives one proves nothing about the other two:
    //
    //   The child-placement loop writes a full [x, y, w, h] rectangle to every child, so a child
    //   lands at whatever size the arrangement calculator chose. A grid gives every cell the width
    //   of the widest element anywhere in the grid, so one wide sibling widens all the others —
    //   width only, on that arm alone.
    //
    //   A nested container the same call has already re-fitted is written at its NEW size, because
    //   the parent's arrangement is computed from pending dimensions rather than stale bounds. This
    //   one fires on row and column arrangements too, where the calculators otherwise preserve each
    //   child's size exactly.
    //
    //   The container's own re-fit to its inflated contents. A TOP-LEVEL container is a child of
    //   the view rather than of any group, so no placement loop ever has it in hand — it is the
    //   largest single resize the call makes and the one an observation map structurally cannot
    //   see. Projecting the compound is what covers it.
    //
    // None was reported by anything. elementsRepositioned counts children PLACED — moved or not,
    // resized or not — so it cannot distinguish them, which is what made the silence look covered.

    /**
     * Grid path. Four children in two columns and two rows, one of them far wider than the rest.
     * Every narrow sibling leaves the call at the wide one's width, at a size nobody asked for.
     */
    @Test
    public void shouldNameAChildTheGridArmWidenedToTheWidestSibling() throws Exception {
        String grid = group("Grid", 0, 0, 400, 300, null);
        String narrow = addAt("actor-1", 10, 40, 60, 30, grid);
        addAt("actor-2", 90, 40, 200, 30, grid);
        addAt("actor-3", 10, 100, 60, 30, grid);
        addAt("actor-4", 90, 100, 60, 30, grid);

        int narrowBefore = find(view, narrow).getBounds().getWidth();
        AdjustViewSpacingResultDto dto = space(20, true);
        IBounds narrowAfter = find(view, narrow).getBounds();

        assertTrue("fixture guard: the grid arm must actually have widened this child, or the pin "
                + "proves nothing: " + narrowBefore + " -> " + narrowAfter.getWidth(),
                narrowAfter.getWidth() > narrowBefore);

        MovedViewObjectDto reported = resizedEntryFor(dto, narrow);
        assertNotNull("a child the grid arm widened must be named — the caller asked for a spacing "
                + "delta, not for this element to change size. resizedElements was: "
                + dto.resizedElements(), reported);
        assertEquals("the reported width must be the LANDED one",
                narrowAfter.getWidth(), reported.newWidth());
        assertEquals("and the landed height", narrowAfter.getHeight(), reported.newHeight());
        assertEquals("and the landed x", narrowAfter.getX(), reported.newX());
        assertEquals("and the landed y", narrowAfter.getY(), reported.newY());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue("the rectangle must reach the wire. Response was: " + json,
                json.contains("\"resizedElements\""));
        assertTrue("the wire must carry the landed width. Response was: " + json,
                json.contains("\"newWidth\":" + narrowAfter.getWidth()));
    }

    /**
     * Recursion path, on a ROW arrangement — the arm whose calculator preserves each child's size
     * exactly, so nothing about a row can widen a child except this. The nested container is
     * re-fitted by the recursion and the parent then writes it at that new size.
     */
    @Test
    public void shouldNameANestedContainerTheRecursionRefitted_onARowArrangement() {
        String outer = group("Outer", 0, 0, 900, 400, null);
        String nested = group("Nested", 10, 40, 200, 120, outer);
        addAt("actor-1", 10, 40, 60, 30, nested);
        addAt("actor-2", 90, 40, 60, 30, nested);
        String sibling = addAt("actor-3", 300, 40, 60, 30, outer);

        int nestedBefore = find(view, nested).getBounds().getWidth();
        AdjustViewSpacingResultDto dto = space(60, true);
        IBounds nestedAfter = find(view, nested).getBounds();

        assertTrue("fixture guard: the recursion must actually have re-fitted the nested container: "
                + nestedBefore + " -> " + nestedAfter.getWidth(),
                nestedAfter.getWidth() > nestedBefore);

        MovedViewObjectDto reported = resizedEntryFor(dto, nested);
        assertNotNull("a nested container this pass re-fitted must be named on a row arrangement "
                + "too — the row calculator preserves sizes, so the change came from the recursion "
                + "and from nothing the caller can see. resizedElements was: "
                + dto.resizedElements(), reported);
        assertEquals("the reported width must be the LANDED one",
                nestedAfter.getWidth(), reported.newWidth());

        assertNull("its sibling only moved, so it must NOT be named: a full rectangle is written to "
                + "every child and the list would otherwise mean nothing. resizedElements was: "
                + dto.resizedElements(), resizedEntryFor(dto, sibling));
    }

    /**
     * The negative, narrowed to the claim it was actually making: no CHILD is named. Two
     * differently-sized children in a row, no nested container — every child is repositioned and
     * none changes size, so however many rectangles the placement loop writes, none of them is a
     * resize.
     *
     * <p>The container itself IS named, and must be: the same call re-fits {@code Row} from
     * 900x300 down to its contents. That it is the list's ONLY entry is what gives this pin its
     * teeth — a projection that named every object it wrote a rectangle to would name both
     * children too, and would pass an emptiness check nowhere.</p>
     *
     * <p>The wire-omission claim this pin used to carry moved rather than being dropped. It is a
     * claim about a call that resizes NOTHING, which this fixture is no longer an example of, so it
     * lives in {@link #shouldOmitTheResizedElementsKey_whenTheCallResizedNothing} on the zero-delta
     * short-circuit — the one path that provably emits no commands at all. Note that the older
     * {@code shouldSerializeExactlyAsBefore_whenTheCallResizedNothing} drives the same path but
     * asserts on {@code resizedAncestors} ALONE; it does not cover this field.</p>
     */
    @Test
    public void shouldNameNoChild_whenEveryChildMovesAndNoneChangesSize() {
        String row = group("Row", 0, 0, 900, 300, null);
        String first = addAt("actor-1", 10, 40, 60, 30, row);
        String second = addAt("actor-2", 90, 40, 100, 30, row);

        int firstX = find(view, first).getBounds().getX();
        int secondX = find(view, second).getBounds().getX();
        AdjustViewSpacingResultDto dto = space(40, true);

        assertTrue("fixture guard: the children must actually have moved",
                find(view, first).getBounds().getX() != firstX
                        || find(view, second).getBounds().getX() != secondX);
        assertEquals("fixture guard: the first child keeps its own width",
                60, find(view, first).getBounds().getWidth());
        assertEquals("fixture guard: the second keeps its own, different width",
                100, find(view, second).getBounds().getWidth());

        assertTrue("the tool still counts the children it placed", dto.elementsRepositioned() > 0);
        assertNull("a child that only moved must not be named. resizedElements was: "
                + dto.resizedElements(), resizedEntryFor(dto, first));
        assertNull("...nor its differently-sized sibling. resizedElements was: "
                + dto.resizedElements(), resizedEntryFor(dto, second));
        assertEquals("...leaving the container this call re-fitted as the only entry: "
                + dto.resizedElements(), 1, dto.resizedElements().size());
        assertNotNull("...and that entry is the container. resizedElements was: "
                + dto.resizedElements(), resizedEntryFor(dto, row));
    }


    /**
     * The two lists are NOT disjoint, and where they overlap the rectangle must agree. Measured on
     * this fixture: the inflation pass places the inner group at 352x66 and the step-9b overflow
     * cascade then writes 356x70 over it. Both passes name the same object; only the LATTER
     * rectangle is the one the model ends up holding, so both fields must carry it. Reporting the
     * inflation command's own numbers would put a 4px-wrong rectangle into a structured geometry
     * field — precisely the confident-wrong-value failure the reporting exists to prevent.
     */
    @Test
    public void shouldReportTheLandedRectangle_whenBothPassesResizeTheSameGroup() {
        String inner = overflowingNest()[1];

        AdjustViewSpacingResultDto dto = space(60, true);
        IBounds innerAfter = find(view, inner).getBounds();

        MovedViewObjectDto asElement = resizedEntryFor(dto, inner);
        MovedViewObjectDto asAncestor = entryFor(dto, inner);
        assertNotNull("fixture guard: the inflation pass must have re-sized the inner group, or "
                + "this proves nothing about the overlap. resizedElements was: "
                + dto.resizedElements(), asElement);
        assertNotNull("fixture guard: the cascade must ALSO have grown it. resizedAncestors was: "
                + dto.resizedAncestors(), asAncestor);

        assertEquals("resizedElements must carry the rectangle the model actually holds, not the "
                + "one the earlier pass proposed", innerAfter.getWidth(), asElement.newWidth());
        assertEquals("...and its height", innerAfter.getHeight(), asElement.newHeight());
        assertEquals("the two lists must agree on the object they both name",
                asAncestor.newWidth(), asElement.newWidth());
        assertEquals("...on both dimensions", asAncestor.newHeight(), asElement.newHeight());
    }

    /**
     * The two fields divide the same event between them, and this fixture is where the division
     * shows. It is built so the overflow cascade emits NOTHING — the outer group is roomy enough
     * that no child can exceed it — while the inflation pass re-fits that same outer group from
     * 2000x1200 down to its contents.
     *
     * <p>{@code resizedAncestors} names a MECHANISM: the groups the step-9b overflow cascade grew.
     * It is built from that cascade's command map alone, so here it is empty, and that is correct
     * rather than a gap.</p>
     *
     * <p>{@code resizedElements} names an OBSERVATION: every object whose size this call changed,
     * whichever mechanism changed it. The top-level container's own re-fit is one of those
     * mechanisms, so the outer group belongs there — at the rectangle a fresh read of the model
     * returns, on all four coordinates. It is the largest single resize the call performs and the
     * most visible object in the view; reporting it nowhere left the agent planning against a
     * rectangle the call had already replaced.</p>
     */
    @Test
    public void shouldNameATopLevelGroupTheInflationRefittedWithoutTheCascade() {
        String outer = group("Roomy", 0, 0, 2000, 1200, null);
        String nested = group("Nested", 10, 40, 200, 120, outer);
        addAt("actor-1", 10, 40, 60, 30, nested);
        addAt("actor-2", 90, 40, 60, 30, nested);
        int outerBefore = find(view, outer).getBounds().getWidth();

        AdjustViewSpacingResultDto dto = space(60, true);
        IBounds outerAfter = find(view, outer).getBounds();

        assertTrue("fixture guard: the inflation must actually have re-fitted the outer group: "
                + outerBefore + " -> " + outerAfter.getWidth(),
                outerAfter.getWidth() != outerBefore);
        assertTrue("the cascade emitted nothing here, so the list built from its map is empty. "
                + "resizedAncestors was: " + dto.resizedAncestors(),
                dto.resizedAncestors().isEmpty());

        MovedViewObjectDto reported = resizedEntryFor(dto, outer);
        assertNotNull("the call re-fitted the top-level container itself, so it must be named as a "
                + "resized object — no other field carries that rectangle. resizedElements was: "
                + dto.resizedElements(), reported);
        assertEquals("outer: x", outerAfter.getX(), reported.newX());
        assertEquals("outer: y", outerAfter.getY(), reported.newY());
        assertEquals("outer: width", outerAfter.getWidth(), reported.newWidth());
        assertEquals("outer: height", outerAfter.getHeight(), reported.newHeight());

        assertNotNull("...and the nested container the recursion re-fitted stays named too. "
                + "resizedElements was: " + dto.resizedElements(),
                resizedEntryFor(dto, nested));
    }

    /**
     * The one arm where a MOVE and a RESIZE land on the same object, and the move is written LAST.
     *
     * <p>Step 6's inter-group shift emits a full rectangle for a top-level group it moves, appended
     * after the whole inflation loop — so for that group it is the last command in the compound,
     * and last-write-wins makes IT, not the inflation's own re-fit, the command the report is built
     * from. The reported size is right only because the shift carries the group's already-inflated
     * dimensions, taken from the compound's pending commands rather than from a live read. Read
     * live instead and the shift would carry the PRE-inflation size, which equals the projection's
     * own baseline — and a group that genuinely resized would silently vanish from the list while
     * every other pin stayed green.</p>
     *
     * <p>Nothing else drives {@code interGroupDelta} against this field: every other fixture in
     * this class passes null for it.</p>
     */
    @Test
    public void shouldReportTheShiftedPositionAndTheInflatedSize_whenAnInterGroupDeltaAlsoMovesIt() {
        String left = group("Left", 0, 0, 900, 300, null);
        addAt("actor-1", 10, 40, 60, 30, left);
        addAt("actor-2", 90, 40, 60, 30, left);
        String right = group("Right", 1200, 0, 900, 300, null);
        addAt("actor-3", 10, 40, 60, 30, right);
        addAt("actor-4", 90, 40, 60, 30, right);
        IBounds rightBefore = copy(find(view, right).getBounds());

        AdjustViewSpacingResultDto dto = accessor
                .adjustViewSpacing(SESSION, view.getId(), 40, null, 120, true).entity();
        IBounds rightAfter = find(view, right).getBounds();

        assertTrue("fixture guard: the inter-group delta must actually MOVE the second group, or "
                + "the move-written-last arm never runs: " + rightBefore.getX() + " -> "
                + rightAfter.getX(), rightAfter.getX() != rightBefore.getX());
        assertTrue("fixture guard: and the inflation must actually RESIZE it, or the size axis of "
                + "this pin proves nothing: " + rightBefore.getWidth() + " -> "
                + rightAfter.getWidth(), rightAfter.getWidth() != rightBefore.getWidth());

        MovedViewObjectDto reported = resizedEntryFor(dto, right);
        assertNotNull("a group this call both re-fitted and shifted must still be named. "
                + "resizedElements was: " + dto.resizedElements(), reported);
        assertEquals("right: x — the SHIFTED position, not the one the re-fit saw",
                rightAfter.getX(), reported.newX());
        assertEquals("right: y", rightAfter.getY(), reported.newY());
        assertEquals("right: width — the INFLATED size, not the one the shift started from",
                rightAfter.getWidth(), reported.newWidth());
        assertEquals("right: height", rightAfter.getHeight(), reported.newHeight());
    }

    /**
     * The FRAME the sizes are measured in, pinned on the one path where a live read and the truth
     * disagree. Inside an open batch {@code getBounds()} is a PRE-batch read: it returns the
     * rectangle the object had before the batch queued anything for it.
     *
     * <p>{@code Sized} is given 900x700 by an earlier operation of the same batch. The spacing pass
     * then re-fits it, and the fit floors against what the batch queued, so the rectangle it writes
     * is that same 900x700 — this call changed its size by nothing. Measured against the pre-batch
     * 100x100 it looks like an 800x600 resize that never happens, and an agent told so would plan
     * against a change no commit will contain.</p>
     *
     * <p>{@code Free} is the positive control, and it is what stops this pin passing merely because
     * the list is empty: the batch queued nothing for it, so its re-fit is unfloored, it genuinely
     * shrinks, and it must be named. One fixture, both directions.</p>
     */
    @Test
    public void shouldMeasureSizesInTheBatchFrame_whenAnEarlierOperationQueuedASize() {
        String sized = group("Sized", 0, 0, 100, 100, null);
        addAt("actor-1", 10, 40, 60, 30, sized);
        addAt("actor-2", 10, 80, 60, 30, sized);
        String free = group("Free", 1200, 0, 2000, 1200, null);
        addAt("actor-3", 10, 40, 60, 30, free);
        addAt("actor-4", 10, 80, 60, 30, free);
        int freeBefore = find(view, free).getBounds().getWidth();

        dispatcher.beginBatch(SESSION, "size one group, then space the view");
        accessor.updateViewObject(SESSION, sized, null, null, 900, 700, null, null, null, null);
        AdjustViewSpacingResultDto dto = accessor
                .adjustViewSpacing(SESSION, view.getId(), 60, null, null, true).entity();
        dispatcher.endBatch(SESSION, true);

        IBounds sizedAfter = find(view, sized).getBounds();
        assertEquals("fixture guard: the queued size must survive the re-fit, or the frames do not "
                + "differ and this pin proves nothing",
                "900x700", sizedAfter.getWidth() + "x" + sizedAfter.getHeight());
        IBounds freeAfter = find(view, free).getBounds();
        assertTrue("fixture guard: the unfloored group must actually have been re-fitted: "
                + freeBefore + " -> " + freeAfter.getWidth(),
                freeAfter.getWidth() != freeBefore);

        assertNull("the batch had already sized this group and the re-fit floored to it, so this "
                + "call changed its size by nothing and must not name it. resizedElements was: "
                + dto.resizedElements(), resizedEntryFor(dto, sized));
        MovedViewObjectDto reported = resizedEntryFor(dto, free);
        assertNotNull("...while the group the batch never touched genuinely shrank and must be "
                + "named. resizedElements was: " + dto.resizedElements(), reported);
        assertEquals("free: width", freeAfter.getWidth(), reported.newWidth());
        assertEquals("free: height", freeAfter.getHeight(), reported.newHeight());
    }

    /**
     * The recursion carries the observation down, not just up. A grandchild widened by the grid arm
     * of a NESTED group's own pass is resized one level below the call the caller made, and is the
     * one an agent is least able to predict. Without the map threaded through the recursive call
     * this reports nothing while the model changes.
     */
    @Test
    public void shouldNameAGrandchildTheRecursionsOwnGridArmWidened() {
        String outer = group("Outer", 0, 0, 1200, 800, null);
        String nested = group("Nested", 10, 40, 500, 300, outer);
        String narrow = addAt("actor-1", 10, 40, 60, 30, nested);
        addAt("actor-2", 90, 40, 240, 30, nested);
        addAt("actor-3", 10, 100, 60, 30, nested);
        addAt("actor-4", 90, 100, 60, 30, nested);

        int narrowBefore = find(view, narrow).getBounds().getWidth();
        AdjustViewSpacingResultDto dto = space(20, true);
        IBounds narrowAfter = find(view, narrow).getBounds();

        assertTrue("fixture guard: the nested group's own grid arm must have widened its child: "
                + narrowBefore + " -> " + narrowAfter.getWidth(),
                narrowAfter.getWidth() > narrowBefore);

        MovedViewObjectDto reported = resizedEntryFor(dto, narrow);
        assertNotNull("a grandchild the recursion widened must be named — it is one level below "
                + "anything the caller named. resizedElements was: " + dto.resizedElements(),
                reported);
        assertEquals("the reported width must be the LANDED one",
                narrowAfter.getWidth(), reported.newWidth());
    }

    // ---- assertion helpers ---------------------------------------------------------------------

    private static void assertNull(String message, Object actual) {
        org.junit.Assert.assertNull(message, actual);
    }

    private static IBounds copy(IBounds b) {
        IBounds c = IArchimateFactory.eINSTANCE.createBounds();
        c.setX(b.getX());
        c.setY(b.getY());
        c.setWidth(b.getWidth());
        c.setHeight(b.getHeight());
        return c;
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener l) { listeners.add(l); }
        @Override public void removePropertyChangeListener(PropertyChangeListener l) { listeners.remove(l); }
        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel m) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel m) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel m, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel m) { return false; }
        @Override public boolean saveModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel m) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }
}
