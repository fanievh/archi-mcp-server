package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
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
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IGrouping;

import net.vheerden.archi.mcp.response.dto.ResizeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.ResizedGroupDto;
import net.vheerden.archi.mcp.response.dto.SkippedContainerDto;

/**
 * Pins how {@code resize-elements-to-fit} treats the two kinds of container Archi renders as a
 * labelled box: a native view group and an ArchiMate {@code Grouping} element.
 *
 * <p><strong>The defect these pins close.</strong> The target walk collects every element
 * view-object, and an ArchiMate {@code Grouping} is one. It therefore reached the label-driven
 * sizer twice over: with children it landed in the parent pass, whose width is re-fitted to
 * {@code max(label, children) + padding} and so could <em>shrink</em> (measured: 400x300 to
 * 160x300); with no children it landed in the leaf pass and was sized to its own name on both axes
 * (measured: 400x300 to 120x55 for a name of fifteen characters or fewer, which is the
 * label-independent floor). An agent that cannot see the canvas had been told by this server's own
 * recipes to build zones out of {@code Grouping} elements, and by the routing-preconditions
 * checklist to reach for this tool when a child overflows its parent — so the recommended remedy
 * destroyed the layout it was called to repair.</p>
 *
 * <p><strong>Why the fix is not "skip a {@code Grouping}".</strong> That remedy works only because
 * a {@code Grouping} is collected and lands in the parent pass, where growth closes the overflow.
 * Growth is load-bearing and is pinned separately here; only shrinkage and label-sizing are
 * removed.</p>
 *
 * <p><strong>Headless by construction.</strong> Every label in these fixtures is fifteen characters
 * or fewer, so {@code ElementSizer.computeAutoSize} and {@code computeLabelHeight} return their
 * defaults without ever calling {@code measureText} — no display is touched. The commit path runs
 * through a plain {@code CommandStack} rather than the OSGi-bound dispatcher, the pattern
 * {@link ResizeElementsToFitAnchoredChildrenTest} established.</p>
 */
public class ResizeElementsToFitGroupingZoneTest {

    private static final String SESSION = "resize-grouping-zone-session";

    /** {@code ElementSizer} defaults for a short name — no display measurement. */
    private static final int AUTO_W = 120;
    private static final int AUTO_H = 55;

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private Command lastDispatched;
    private int actorSeq;
    private int groupingSeq;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Grouping Zone Fixture");
        model.setId("model-grouping-zone");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Zones");
        diagrams.getElements().add(view);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                lastDispatched = command;
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                lastDispatched = command;
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

    // ---- fixture helpers -------------------------------------------------------------------------

    /** An ArchiMate element view-object whose concept is an actor — the ordinary, non-zone case. */
    private IDiagramModelObject element(String id, String name, int x, int y, int w, int h,
            IDiagramModelContainer parent) {
        IArchimateElement concept = factory.createBusinessActor();
        concept.setId("actor-" + (++actorSeq));
        concept.setName(name);
        model.getFolder(FolderType.BUSINESS).getElements().add(concept);
        return place(id, concept, x, y, w, h, parent);
    }

    /** An ArchiMate {@code Grouping} element view-object — a zone. */
    private IDiagramModelObject zone(String id, String name, int x, int y, int w, int h,
            IDiagramModelContainer parent) {
        IGrouping concept = factory.createGrouping();
        concept.setId("grouping-" + (++groupingSeq));
        concept.setName(name);
        model.getFolder(FolderType.OTHER).getElements().add(concept);
        return place(id, concept, x, y, w, h, parent);
    }

    private IDiagramModelObject place(String id, IArchimateElement concept, int x, int y, int w,
            int h, IDiagramModelContainer parent) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(concept);
        obj.setBounds(x, y, w, h);
        parent.getChildren().add(obj);
        return obj;
    }

    /** A native view group — the diagram-only device with no model semantics. */
    private IDiagramModelGroup nativeGroup(String id, String name, int x, int y, int w, int h) {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId(id);
        group.setName(name);
        group.setBounds(x, y, w, h);
        view.getChildren().add(group);
        return group;
    }

    private static void assertBounds(String what, IDiagramModelObject obj, int w, int h) {
        assertEquals(what + " width", w, obj.getBounds().getWidth());
        assertEquals(what + " height", h, obj.getBounds().getHeight());
    }

    private static boolean namesElement(ResizeElementsResultDto dto, String id) {
        for (ResizeElementsResultDto.ResizedElement e : dto.resizedElements()) {
            if (id.equals(e.elementId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesGroup(ResizeElementsResultDto dto, String id) {
        for (ResizedGroupDto g : dto.resizedGroups()) {
            if (id.equals(g.viewObjectId())) {
                return true;
            }
        }
        return false;
    }

    // ---- a Grouping is a zone: never collapsed ---------------------------------------------------

    /**
     * The headline. An empty zone box is not a label-bearing element, and sizing it to its own name
     * is how a 400x300 zone came back at the element defaults. Measured before the fix: 120x55.
     */
    @Test
    public void shouldLeaveAChildlessGroupingZoneAtItsOwnSize_whenTheViewIsResizedToFit() {
        IDiagramModelObject empty = zone("z-empty", "Zone B", 0, 0, 400, 300, view);

        accessor.resizeElementsToFit(SESSION, view.getId(), null);

        assertBounds("an empty zone must keep the size its author gave it", empty, 400, 300);
    }

    /**
     * The other half of the same defect, on the other pass. A populated zone reached the parent
     * pass, whose width is re-fitted to {@code max(label, children) + padding} with no floor at the
     * current width — so a zone wider than its contents was pulled in around them. Measured before
     * the fix: 400x300 became 160x300, the height held only by the explicit never-shrink guard.
     */
    @Test
    public void shouldNotShrinkAPopulatedGroupingZone_whenItsChildrenAreNarrowerThanItIs() {
        IDiagramModelObject populated = zone("z-full", "Zone A", 0, 0, 400, 300, view);
        element("e-in", "Actor 1", 20, 40, 60, 30, (IDiagramModelContainer) populated);

        accessor.resizeElementsToFit(SESSION, view.getId(), null);

        assertBounds("a zone is never pulled in around its contents", populated, 400, 300);
    }

    /**
     * Growth is load-bearing and must survive the fix. {@code assess-layout} reports a child outside
     * its parent as {@code boundaryViolationCount}, and both the served checklist and
     * {@code ViewPlacementHandler}'s prose name this tool as the remedy. That remedy only ever
     * needed growth, and a fix that removed the zone from the walk would have silently broken it.
     *
     * <p>Geometry is derived from the variable under test: the child's right edge (410) and bottom
     * (310) both lie outside the zone's 400x300 box, so the assertion cannot pass for the wrong
     * reason.</p>
     */
    @Test
    public void shouldStillGrowAGroupingZone_whenAChildOverflowsIt() {
        IDiagramModelObject populated = zone("z-grow", "Zone A", 0, 0, 400, 300, view);
        element("e-over", "Actor 1", 350, 280, 60, 30, (IDiagramModelContainer) populated);

        accessor.resizeElementsToFit(SESSION, view.getId(), null);

        assertTrue("the zone must grow past its child's right edge, or the boundaryViolationCount "
                        + "remedy this tool is named for stops working: width="
                        + populated.getBounds().getWidth(),
                populated.getBounds().getWidth() >= 410);
        assertTrue("and past its child's bottom edge: height=" + populated.getBounds().getHeight(),
                populated.getBounds().getHeight() >= 310);
    }

    // ---- nothing else moved ----------------------------------------------------------------------

    /**
     * The tool's actual job, unchanged. A childless {@code ApplicationComponent} — or any
     * non-{@code Grouping} element — is still sized to its label, which is the whole point of the
     * leaf pass. A fix that skipped every container-shaped object would take this with it.
     */
    @Test
    public void shouldStillSizeANonGroupingLeafToItsLabel() {
        IDiagramModelObject leaf = element("e-leaf", "Actor 1", 0, 0, 400, 300, view);

        accessor.resizeElementsToFit(SESSION, view.getId(), null);

        assertBounds("an ordinary leaf element is still sized to its label", leaf, AUTO_W, AUTO_H);
    }

    /**
     * The parent pass, unchanged for a non-zone. An element that holds children is still fitted
     * around them, width included — the shrink this story removes is scoped to a {@code Grouping}
     * and to nothing else.
     */
    @Test
    public void shouldStillFitANonGroupingParentToItsChildren() {
        IDiagramModelObject parent = element("e-parent", "Actor 1", 0, 0, 400, 300, view);
        element("e-child", "Actor 2", 20, 40, 60, 30, (IDiagramModelContainer) parent);

        accessor.resizeElementsToFit(SESSION, view.getId(), null);

        assertEquals("a non-zone parent is still re-fitted around its children, and that width "
                        + "still shrinks: max(label 120, childRight 140) + 2*10",
                160, parent.getBounds().getWidth());
    }

    /**
     * A native view group is never collected as a target: the walk descends through it to reach the
     * elements inside. With nothing overflowing, it is left exactly where it is and is named
     * nowhere in the response.
     */
    @Test
    public void shouldNeverCollectANativeViewGroupAsATarget() {
        IDiagramModelGroup group = nativeGroup("nat-quiet", "Zone C", 0, 0, 400, 300);
        element("e-nat", "Actor 1", 20, 40, 60, 30, group);

        ResizeElementsResultDto dto =
                accessor.resizeElementsToFit(SESSION, view.getId(), null).entity();

        assertBounds("a native group no child overflowed is untouched", group, 400, 300);
        assertTrue("a native group is not a resize TARGET, so it is never a resized element",
                !namesElement(dto, group.getId()));
        assertTrue("and nothing grew it, so it is not a resized group either",
                !namesGroup(dto, group.getId()));
    }

    /**
     * "A native view group is not touched at all" is measurably false, and three published surfaces
     * said it. Every target this tool resizes goes through {@code prepareUpdateViewObjectDirect},
     * which calls {@code ParentFitCascade.fitAround} — and that cascade acts on native view groups
     * <em>only</em>, growing one around a child this pass widened. It is never a resize target; it
     * is still grown, and that growth is already reported in {@code resizedGroups}.
     *
     * <p>The child is placed so that the leaf pass alone provokes the overflow: at (320, 260) a
     * 60x30 actor sized to 120x55 reaches x=440 and y=315, both outside the group's 400x300 box.</p>
     */
    @Test
    public void shouldStillGrowANativeViewGroupViaTheCascade_whenAResizedChildOverflowsIt() {
        IDiagramModelGroup group = nativeGroup("nat-grown", "Zone C", 0, 0, 400, 300);
        element("e-nat-over", "Actor 1", 320, 260, 60, 30, group);

        ResizeElementsResultDto dto =
                accessor.resizeElementsToFit(SESSION, view.getId(), null).entity();

        assertTrue("the cascade must grow the native group around the child this pass widened: "
                        + "width=" + group.getBounds().getWidth(),
                group.getBounds().getWidth() > 400);
        assertTrue("and past the child's new bottom: height=" + group.getBounds().getHeight(),
                group.getBounds().getHeight() > 300);
        assertTrue("that growth must be REPORTED — an agent that cannot see the canvas has no "
                        + "other way to learn the group moved",
                namesGroup(dto, group.getId()));
        assertNotNull("the pass dispatched a compound", lastDispatched);
    }

    // ---- the invariant holds on the wrapFit arm too -----------------------------------------------

    /**
     * {@code wrapFit} is grow-only, height-only and width-preserving, so it never collapsed a zone —
     * but it would still have grown one's height to wrap the zone's own name, which is the same act
     * of treating a zone as a label-bearing element. The published sentence says a {@code Grouping}
     * is never sized to its own name, and a claim that holds on one arm and not the other is not an
     * invariant.
     *
     * <p>This pin is also its own display guard: {@code ElementSizer.computeWrapFitDimensions} has
     * no short-name shortcut and calls {@code measureText} for any non-empty label, so a zone that
     * still reached the leaf pass here would reach for a {@code Display} that no headless lane has.
     * Skipping the zone is what keeps this test in the headless lane at all.</p>
     */
    @Test
    public void shouldLeaveAChildlessGroupingZoneAlone_whenWrapFitIsRequested() {
        IDiagramModelObject empty = zone("z-wrap", "Zone D", 0, 0, 400, 300, view);

        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(empty.getId()), true);

        assertBounds("wrap-fit must not grow a zone to wrap its own name", empty, 400, 300);
    }

    // ---- what the response says about a zone this pass declined to size --------------------------

    /**
     * A caller that named the zone in {@code elementIds} asked a question about that object, and a
     * success mentioning neither it nor the reason reads as "resized". {@code resizedCount} cannot
     * carry this: a count reports that something changed, never what it changed to or why one thing
     * did not — which is exactly the shape of report this project's effective-state rule rejects.
     */
    @Test
    public void shouldDiscloseADeclinedZone_whenTheCallerNamedItInElementIds() {
        IDiagramModelObject empty = zone("z-named", "Zone B", 0, 0, 400, 300, view);

        ResizeElementsResultDto dto = accessor
                .resizeElementsToFit(SESSION, view.getId(), List.of(empty.getId())).entity();

        assertEquals("the zone the caller named must be disclosed, not silently dropped",
                1, dto.skippedContainers().size());
        SkippedContainerDto skipped = dto.skippedContainers().get(0);
        assertEquals("z-named", skipped.viewObjectId());
        assertEquals("Zone B", skipped.name());
        assertEquals("Grouping", skipped.elementType());
        assertTrue("the reason must be actionable prose, not a bare code: " + skipped.reason(),
                skipped.reason() != null && skipped.reason().contains("update-view-object"));
        assertBounds("and the zone itself is untouched", empty, 400, 300);
    }

    /**
     * The complement, and the parity that makes it defensible. A zone the walk merely found is left
     * out of the report entirely — which is precisely what a native view group in the same position
     * gets. The caller asked about the view, not about that object, and an entry per untouched zone
     * would bury the elements it did ask about.
     */
    @Test
    public void shouldStaySilentAboutADeclinedZone_whenTheWalkCollectedItImplicitly() {
        zone("z-implicit", "Zone B", 0, 0, 400, 300, view);
        IDiagramModelObject leaf = element("e-also", "Actor 1", 500, 0, 400, 300, view);

        ResizeElementsResultDto dto =
                accessor.resizeElementsToFit(SESSION, view.getId(), null).entity();

        assertTrue("a zone the caller never named is not disclosed, exactly as a native view group "
                        + "in the same position is not", dto.skippedContainers().isEmpty());
        assertTrue("and the elements the caller did ask about are still reported",
                namesElement(dto, leaf.getId()));
    }

    // ---- a zone is grown around its CHILDREN, never around its own name ---------------------------

    /**
     * A zone's height must follow what it holds, not what it is called. Pass 2 reserves a label
     * band above a parent's children — correct for an element, whose name renders across the top of
     * the box — and then adds that band to the height. A `Grouping` renders its name in a corner
     * tab and is not a label-bearing element, so a band reserved for it grows the zone on account
     * of its own name, which is exactly what four published surfaces now say this tool never does.
     *
     * <p>Geometry is derived from the variable under test. The zone is 60 tall; its child is sized
     * by pass 1 to the 120x55 default and so reaches bottom 65, which the zone must grow to contain
     * — to <b>75</b>, that bottom plus the containment padding. The label band is 25 (a short name,
     * so no display is touched), and adding it — plus the 15px downward shift it provokes — takes
     * the zone to <b>115</b> instead. The two numbers cannot be confused, and neither equals the
     * starting height, so the pin cannot pass for the wrong reason.</p>
     */
    @Test
    public void shouldGrowAGroupingZoneOnlyAsFarAsItsChildren_notForItsOwnLabelBand() {
        IDiagramModelObject populated = zone("z-band", "Zone A", 0, 0, 400, 60, view);
        element("e-band", "Actor 1", 10, 10, 60, 30, (IDiagramModelContainer) populated);

        accessor.resizeElementsToFit(SESSION, view.getId(), null);

        assertEquals("the zone grows to contain its child (65) plus padding, and no further — a "
                        + "label band added here is the zone being sized to its own name",
                75, populated.getBounds().getHeight());
    }

    /**
     * The same label band, seen from the other side. Before it is added to the height it is used to
     * push the zone's children down out of it — so a zone whose author placed a child near the top
     * has that child moved, by a tool that is documented to size elements to their labels. Moving
     * the contents of a zone is `layout-within-group`'s job, and the caller never asked for it.
     */
    @Test
    public void shouldNotShiftAZonesChildrenDown_toClearALabelBandItDoesNotHave() {
        IDiagramModelObject populated = zone("z-shift", "Zone A", 0, 0, 400, 300, view);
        IDiagramModelObject child =
                element("e-shift", "Actor 1", 10, 10, 60, 30, (IDiagramModelContainer) populated);

        accessor.resizeElementsToFit(SESSION, view.getId(), null);

        assertEquals("a child inside a zone stays where its author put it", 10,
                child.getBounds().getY());
    }

    // ---- naming the container that overflowed is the documented remedy ----------------------------

    /**
     * The remedy, called the way every surface tells a caller to call it. `assess-layout` reports a
     * child outside its parent as `boundaryViolationCount`; the served checklist, the accessor's own
     * `nextSteps` and this tool's MCP description all answer *"`resize-elements-to-fit` on the
     * parent"*. A caller following that advice names **the parent** — the zone that overflowed — and
     * not the child, which is not the object it was told to fix.
     *
     * <p>The parent/child map is built from the <em>filtered</em> target list, so naming only the
     * zone removed its child from that list, left the zone looking childless, and dropped it into
     * the leaf pass — where it is now skipped. The zone was therefore not grown at all, and the
     * response said so in words that were false: *"This zone holds no children."* The published
     * remedy has to work when it is called as published.</p>
     *
     * <p>The child is NOT named, so it must not be re-sized: it keeps its 60x30 and its position,
     * and the zone grows around those live bounds — right edge 410, bottom 310.</p>
     */
    @Test
    public void shouldStillGrowAGroupingZone_whenTheCallerNamedOnlyTheZoneThatOverflowed() {
        IDiagramModelObject populated = zone("z-named-only", "Zone A", 0, 0, 400, 300, view);
        IDiagramModelObject child =
                element("e-unnamed", "Actor 1", 350, 280, 60, 30, (IDiagramModelContainer) populated);

        ResizeElementsResultDto dto = accessor
                .resizeElementsToFit(SESSION, view.getId(), List.of(populated.getId())).entity();

        assertBounds("the zone grows around the child it holds, whether or not that child was "
                + "named — otherwise the remedy fails exactly when it is called as documented",
                populated, 430, 320);
        assertEquals("a child the caller did not name is not re-sized", 60,
                child.getBounds().getWidth());
        assertEquals("nor moved", 280, child.getBounds().getY());
        assertTrue("a zone that WAS grown is not a skipped container, and must never be described "
                        + "as holding no children: " + dto.skippedContainers(),
                dto.skippedContainers().isEmpty());
    }

    /**
     * The disclosure's identity half. `SkippedContainerDto` documents `name` as falling back to the
     * id when the view cannot name it, and the sibling construction site implements exactly that —
     * an entry that names neither leaves the caller an id it cannot match to anything it can see.
     */
    @Test
    public void shouldFallBackToTheIdInTheDisclosure_whenTheZoneHasNoName() {
        IDiagramModelObject unnamed = zone("z-blank", "  ", 0, 0, 400, 300, view);

        ResizeElementsResultDto dto = accessor
                .resizeElementsToFit(SESSION, view.getId(), List.of(unnamed.getId())).entity();

        assertEquals(1, dto.skippedContainers().size());
        assertEquals("a blank-named zone is disclosed by its id, not by an empty string",
                "z-blank", dto.skippedContainers().get(0).name());
        assertEquals("and its type is read from the model, not asserted by the call site",
                "Grouping", dto.skippedContainers().get(0).elementType());
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
