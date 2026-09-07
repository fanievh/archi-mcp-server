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

import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * Pins that the group ends up containing every object the call displaced, not only the object the
 * caller named.
 *
 * <p>Growing an object moves everything anchored to it, and grows the group around it so the new
 * rectangle still fits. Those two happened in the wrong order: the fit was computed from the
 * target's rectangle, while the anchored children's landed rectangles were not resolved until
 * afterwards, so a child was placed — accurately, and accurately reported — outside the group that
 * is supposed to hold it. The agent driving this cannot see the canvas, so a correct report of a
 * wrong geometry is still a wrong canvas.</p>
 *
 * <h2>The movement pin comes first, deliberately</h2>
 *
 * <p>{@code AnchorResolver.wrapAnchoredChildren} takes a move map whose contract is that the
 * <em>caller</em> commits what it records: supplying one is not an observation, it stops the moves
 * riding inside the returned compound. A containment-only assertion would therefore pass on a build
 * where the anchored child never moved at all — a child that stays put is trivially still inside its
 * group. Every scenario here asserts the landed position in the model before it asserts anything
 * about containment or about the response.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>The headless idiom the sibling anchored-child classes use: a real GEF {@link CommandStack}
 * driven over an ordered compound, with the production {@code NonNotifyingCompoundCommand} rebuilt
 * as a plain {@link CompoundCommand} because its {@code execute()} dereferences
 * {@code IEditorModelManager.INSTANCE}. Every add passes explicit bounds so nothing reaches
 * {@code ElementSizer}'s display-bound measurement.</p>
 */
public class ParentFitAnchoredChildContainmentTest {

    private static final String SESSION = "parent-fit-anchored-containment-session";

    /** The padding the update paths pass to the cascade, read from the production constant. */
    private static final int PADDING = ArchiModelAccessorImpl.DEFAULT_GROUP_PADDING;

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private List<Command> dispatched;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Parent Fit Anchored Containment Fixture");
        model.setId("model-parent-fit-anchored-containment");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Containment");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 4; i++) {
            IBusinessActor a = factory.createBusinessActor();
            a.setId("actor-" + i);
            a.setName("Actor " + i);
            business.getElements().add(a);
        }

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatched = new ArrayList<>();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                dispatched.add(command);
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                dispatched.add(command);
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
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

    private String add(String actorId, int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), actorId, x, y, w, h, false, parentId,
                null, null).entity().viewObject().viewObjectId();
    }

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label, x, y, w, h, parentId, null, null)
                .entity().viewObjectId();
    }

    private void anchor(String childId, String targetId, String edge, int dx, int dy) {
        accessor.updateViewObject(SESSION, childId, null, null, null, null,
                null, null, null, null, targetId, edge, dx, dy);
    }

    private ViewObjectDto resizeHeight(String viewObjectId, int height) {
        return accessor.updateViewObject(SESSION, viewObjectId, null, null, null, height,
                null, null, null, null).entity();
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

    /**
     * Asserts {@code childId} sits inside {@code parentId} using the cascade's own overflow
     * predicate, so the test and the production fit cannot disagree about what "inside" means.
     */
    private void assertContained(String childId, String parentId, String why) {
        IBounds child = find(view, childId).getBounds();
        IBounds parent = find(view, parentId).getBounds();
        assertFalse(why + " — child (" + child.getX() + "," + child.getY() + ") "
                        + child.getWidth() + "x" + child.getHeight()
                        + " is outside parent " + parent.getWidth() + "x" + parent.getHeight()
                        + " (padding " + PADDING + ")",
                ParentFitCascade.childExceedsParentBounds(
                        child.getX(), child.getY(), child.getWidth(), child.getHeight(),
                        parent.getWidth(), parent.getHeight(), PADDING));
    }

    /** The dispatched command tree, flattened to {@code label|class} lines for comparison. */
    private static String describe(Command command) {
        StringBuilder sb = new StringBuilder();
        describeInto(command, 0, sb);
        return sb.toString();
    }

    private static void describeInto(Command command, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth))
          .append(command.getClass().getSimpleName())
          .append('|')
          .append(command.getLabel())
          .append('\n');
        if (command instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                describeInto((Command) child, depth + 1, sb);
            }
        }
    }

    // ---- the row's own live geometry -----------------------------------------------------------

    /**
     * The movement guard for the row's geometry: the anchored child really is displaced by the
     * resize, in the model. Without this every containment assertion below could be satisfied by a
     * build that simply stopped moving anchored children.
     */
    @Test
    public void shouldStillMoveTheAnchoredChild_whenTheAnchorTargetGrowsInsideAGroup() {
        String outerId = group("Outer", 0, 0, 400, 300, null);
        String innerId = group("Inner", 10, 10, 300, 200, outerId);
        String targetId = add("actor-1", 30, 30, 60, 30, innerId);
        String childId = add("actor-2", 30, 70, 60, 55, innerId);
        anchor(childId, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        resizeHeight(targetId, 500);

        IBounds child = find(view, childId).getBounds();
        assertEquals("the anchored child must land directly below the grown target",
                30 + 500 + 10, child.getY());
        assertTrue("fixture must actually displace the child, or the containment pins prove nothing: "
                + childYBefore + " -> " + child.getY(), child.getY() != childYBefore);
    }

    /**
     * The defect itself, at the numbers it was observed at. The target grows to a bottom of 530 and
     * the group is fitted to 540 around it; the anchored child then lands at y=540 and occupies
     * 540..595, so it hangs fifty-five pixels past the bottom edge of the group that holds it, and
     * the group is sixty-five short of the 605 that would enclose it with the usual padding. Both
     * figures describe the same gap and it is worth keeping them apart: the fit is driven by the
     * padded requirement, so 605 is what the corrected pass has to produce.
     */
    @Test
    public void shouldContainTheAnchoredChild_whenTheAnchorTargetGrowsInsideAGroup() {
        String outerId = group("Outer", 0, 0, 400, 300, null);
        String innerId = group("Inner", 10, 10, 300, 200, outerId);
        String targetId = add("actor-1", 30, 30, 60, 30, innerId);
        String childId = add("actor-2", 30, 70, 60, 55, innerId);
        anchor(childId, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        resizeHeight(targetId, 500);

        IBounds child = find(view, childId).getBounds();
        assertTrue("fixture must actually displace the child: " + childYBefore + " -> "
                + child.getY(), child.getY() != childYBefore);
        assertEquals("fixture guard: the child lands where the row measured it", 540, child.getY());
        assertEquals("fixture guard: the child is 55 tall, so its bottom is 595", 55, child.getHeight());

        assertContained(childId, innerId,
                "the group must grow around the child the resize displaced, not only around the "
                        + "object the caller named");
    }

    /**
     * The second level. The row measured the outer group fitted to 560 — derived from the inner
     * group's wrong height, so it is wrong for the same reason one level up. Whatever the inner
     * group grows to must cascade all the way to the root.
     */
    @Test
    public void shouldGrowTheGrandparentToo_whenTheAnchoredChildPushesTheInnerGroup() {
        String outerId = group("Outer", 0, 0, 400, 300, null);
        String innerId = group("Inner", 10, 10, 300, 200, outerId);
        String targetId = add("actor-1", 30, 30, 60, 30, innerId);
        String childId = add("actor-2", 30, 70, 60, 55, innerId);
        anchor(childId, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        resizeHeight(targetId, 500);

        assertTrue("fixture must actually displace the child",
                find(view, childId).getBounds().getY() != childYBefore);

        assertContained(childId, innerId, "the inner group must contain the displaced child");
        assertContained(innerId, outerId, "the outer group must contain the grown inner group");
    }

    /**
     * Trap of the shape this fix is most likely to fall into: the compound is assembled from a
     * snapshot of the resize map, so a fit run <em>after</em> the wrap updates the report and the
     * bounds map while the command tree still carries the earlier, smaller resize. The response
     * would then promise a group height the model never holds.
     *
     * <p>The oracle is deliberately a fresh read of the executed model compared against the
     * response. It passes on the pre-story build (both say 540, both wrong together) and on the
     * corrected build (both say the grown value); it fails only on the naive fix, where they
     * disagree.</p>
     */
    @Test
    public void shouldCommitTheGroupResizeItReports_whenTheAnchoredChildForcedTheGrowth() {
        String outerId = group("Outer", 0, 0, 400, 300, null);
        String innerId = group("Inner", 10, 10, 300, 200, outerId);
        String targetId = add("actor-1", 30, 30, 60, 30, innerId);
        String childId = add("actor-2", 30, 70, 60, 55, innerId);
        anchor(childId, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        ViewObjectDto dto = resizeHeight(targetId, 500);

        assertTrue("fixture must actually displace the child",
                find(view, childId).getBounds().getY() != childYBefore);
        assertFalse("resizedAncestors must name the groups it grew", dto.resizedAncestors().isEmpty());

        for (MovedViewObjectDto grown : dto.resizedAncestors()) {
            IDiagramModelObject actual = find(view, grown.viewObjectId());
            assertNotNull("resizedAncestors named a group the view does not hold: "
                    + grown.viewObjectId(), actual);
            IBounds bounds = actual.getBounds();
            assertEquals("the model must hold the width reported for " + grown.name()
                    + " — a resize that is reported but not committed is a promise the model "
                    + "never keeps", bounds.getWidth(), grown.newWidth());
            assertEquals("the model must hold the height reported for " + grown.name()
                    + " — a resize that is reported but not committed is a promise the model "
                    + "never keeps", bounds.getHeight(), grown.newHeight());
        }
    }

    /**
     * The no-anchored-children path must be untouched. Mechanically it is: with nothing anchored to
     * the target, {@code wrapAnchoredChildren} returns the base command unchanged and the map it
     * fills stays empty, so the second fit is driven over an empty map and emits nothing. Asserted
     * rather than argued, on both observables the client and the model can see — the serialized
     * response and the dispatched command tree.
     */
    @Test
    public void shouldLeaveTheResponseAndCommandTreeAlone_whenTheTargetHasNoAnchoredChildren()
            throws Exception {
        String outerId = group("Outer", 0, 0, 400, 300, null);
        String innerId = group("Inner", 10, 10, 300, 200, outerId);
        String targetId = add("actor-1", 30, 30, 60, 30, innerId);
        add("actor-2", 30, 70, 60, 55, innerId);

        dispatched.clear();
        ViewObjectDto dto = resizeHeight(targetId, 500);

        assertTrue("this scenario must displace nothing", dto.movedObjects().isEmpty());
        assertEquals("exactly one command tree is dispatched for one update", 1, dispatched.size());
        assertEquals("the group cascade must still fit the target itself",
                30 + 500 + PADDING, find(view, innerId).getBounds().getHeight());

        String tree = describe(dispatched.get(0));
        assertEquals("with no anchored children the command tree must be the cascade's own shape: "
                        + "the update, wrapped once with the two group resizes\n" + tree,
                "NonNotifyingCompoundCommand|Update view object bounds with parent-group resize\n"
                        + "  UpdateViewObjectCommand|Update view object\n"
                        + "  UpdateViewObjectCommand|Update view object\n"
                        + "  UpdateViewObjectCommand|Update view object\n",
                tree);

        String body = new ObjectMapper().writeValueAsString(dto);
        assertFalse("no anchored child exists, so nothing may be reported as moved: " + body,
                body.contains("\"movedObjects\":[{"));
    }

    /**
     * A declared boundary, not an oversight.
     *
     * <p>A child anchored {@code above} or {@code left} with a large enough offset lands at a
     * negative coordinate inside its parent. The cascade's answer to a negative child is to expand
     * the group leftwards or upwards by moving the group's own origin — and because nested
     * coordinates are relative to the immediate parent, relocating the group visually displaces
     * every other child of it while their stored coordinates stay put. The child that provoked it
     * keeps its negative coordinate too, so the overflow predicate still reports it as outside.</p>
     *
     * <p>None of that is new here. It is what {@code ParentFitCascade.resize} has always done, and
     * it is reachable today through the target's own rectangle; this change only gives the same
     * arithmetic a second rectangle to be asked about. Changing what the cascade computes for a
     * negative child is a different question from whether the cascade gets to see the children a
     * call displaced, and it is deliberately not answered here. This test exists so the next reader
     * sees a decision rather than a gap.</p>
     */
    @Test
    public void shouldStillShiftTheGroupOrigin_whenAnAnchoredChildLandsAtANegativeCoordinate() {
        String outerId = group("Outer", 0, 0, 600, 600, null);
        String innerId = group("Inner", 10, 10, 300, 450, outerId);
        String targetId = add("actor-1", 30, 300, 60, 30, innerId);
        String childId = add("actor-2", 30, 20, 60, 55, innerId);
        anchor(childId, targetId, "above", 0, 200);

        IBounds innerBefore = find(view, innerId).getBounds();
        int childYBefore = find(view, childId).getBounds().getY();
        // Moving the target UP drags an above-anchored child to a negative coordinate.
        accessor.updateViewObject(SESSION, targetId, null, 100, null, null,
                null, null, null, null);

        IBounds child = find(view, childId).getBounds();
        IBounds inner = find(view, innerId).getBounds();

        assertTrue("fixture must actually displace the child: " + childYBefore + " -> "
                + child.getY(), child.getY() != childYBefore);
        assertTrue("fixture must land the child at a negative coordinate, or this documents "
                + "nothing: y=" + child.getY(), child.getY() < 0);

        assertTrue("DECLARED BOUNDARY: the cascade answers a negative child by moving the group's "
                        + "origin, which displaces every sibling. Inner y " + innerBefore.getY()
                        + " -> " + inner.getY(),
                inner.getY() < innerBefore.getY());
        assertTrue("DECLARED BOUNDARY: the child keeps its negative coordinate, so it is still "
                        + "outside by the shared predicate even after the group grew",
                ParentFitCascade.childExceedsParentBounds(
                        child.getX(), child.getY(), child.getWidth(), child.getHeight(),
                        inner.getWidth(), inner.getHeight(), PADDING));
    }

    /**
     * The second half of that boundary, and the one this change makes newly reachable.
     *
     * <p>The cascade's negative branch shifts the group's origin by adding the child's offset to the
     * origin it has already accumulated, where the right/bottom branch a few lines above takes a
     * {@code Math.max}. Fit one negative child and the answer is right; fit two into the same group
     * in one pass and their offsets compound, so the group is moved and widened by roughly the sum
     * of what each child needed rather than by the largest.</p>
     *
     * <p>The arithmetic is pre-existing and untouched here — the whole-view pass has always been
     * able to walk two negative children into one group. What is new is that this pass can now do
     * it too, because it fits the children a call displaced and a target can have several. The
     * error is in the over-generous direction: the group ends up larger than needed and still
     * encloses everything, so it costs canvas rather than correctness.</p>
     *
     * <p>Left alone deliberately. Changing what the cascade computes for a negative child is a
     * different question from whether the cascade gets to see the children a call displaced, and
     * correcting it would alter a shared walk that several other layers drive. Pinned as observed so
     * the next reader finds a measurement rather than a surprise.</p>
     */
    @Test
    public void shouldCompoundTheOriginShift_whenTwoDisplacedChildrenBothLandNegative() {
        String outerId = group("Outer", 0, 0, 900, 900, null);
        String innerId = group("Inner", 100, 100, 300, 450, outerId);
        String targetId = add("actor-1", 30, 300, 60, 30, innerId);
        String childA = add("actor-2", 30, 20, 60, 55, innerId);
        String childB = add("actor-3", 30, 40, 60, 55, innerId);
        anchor(childA, targetId, "above", 0, 200);
        anchor(childB, targetId, "above", 0, 260);

        IBounds innerBefore = find(view, innerId).getBounds();
        int aBefore = find(view, childA).getBounds().getY();
        int bBefore = find(view, childB).getBounds().getY();

        accessor.updateViewObject(SESSION, targetId, null, 100, null, null,
                null, null, null, null);

        IBounds a = find(view, childA).getBounds();
        IBounds b = find(view, childB).getBounds();
        IBounds inner = find(view, innerId).getBounds();

        assertTrue("fixture must displace both children", a.getY() != aBefore && b.getY() != bBefore);
        assertTrue("fixture must land BOTH at negative coordinates, or nothing compounds: a="
                + a.getY() + " b=" + b.getY(), a.getY() < 0 && b.getY() < 0);

        int deepest = Math.min(a.getY(), b.getY());
        int minimalY = innerBefore.getY() + deepest - PADDING;
        assertTrue("DECLARED BOUNDARY: two negative children compound, so the origin moves further "
                        + "than the deepest child alone requires. Inner y " + innerBefore.getY()
                        + " -> " + inner.getY() + "; the deepest child at " + deepest
                        + " alone would need y=" + minimalY,
                inner.getY() < minimalY);
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
