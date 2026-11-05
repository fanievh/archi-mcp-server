package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * A container grown to fit what it holds can come to lie over its neighbour. These pin the warning
 * that names each sibling it newly overlaps: on update-view-object on each arm and as a bulk
 * operation, on layout-within-group, and on the helper's own rules (a pre-existing overlap is not
 * news, nothing moves, a nested container points at its parent).
 */
public class CascadeOverlapWarningTest {

    private static final String SESSION = "cascade-session";
    // Spelled out rather than read from the constant, so this class compiled before the code existed.
    private static final String CODE = "CASCADE_GROWN_INTO_SIBLING";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateDiagramModel otherView;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
        model = factory.createArchimateModel();
        model.setName("Label Fixture");
        model.setId("model-label");
        model.setDefaults();
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Labelled");
        diagrams.getElements().add(view);
        otherView = factory.createArchimateDiagramModel();
        otherView.setId("view-2");
        otherView.setName("Referenced");
        diagrams.getElements().add(otherView);
        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(toPlainCompound(command));
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

    // ---- fixture --------------------------------------------------------------------------------

    private IDiagramModelGroup group(IDiagramModelContainer parent, String id, String name,
            int x, int y, int w, int h) {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId(id);
        group.setName(name);
        group.setBounds(x, y, w, h);
        parent.getChildren().add(group);
        return group;
    }

    private IDiagramModelArchimateObject element(IDiagramModelContainer parent, String id,
            int x, int y, int w, int h) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setId("el-" + id);
        element.setName("Element " + id);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(element);
        obj.setBounds(x, y, w, h);
        parent.getChildren().add(obj);
        return obj;
    }

    /** Group "g" at (10,10) 300x200 holding "child"; sibling group "h" 20 px to its right. */
    private IDiagramModelGroup twoGroupsSideBySide() {
        IDiagramModelGroup g = group(view, "g", "Grown", 10, 10, 300, 200);
        element(g, "child", 20, 40, 120, 55);
        group(view, "h", "Neighbour", 330, 10, 300, 200);
        return g;
    }

    private static List<StructuredWarningDto> cascade(List<StructuredWarningDto> all) {
        List<StructuredWarningDto> out = new ArrayList<>();
        for (StructuredWarningDto w : all) {
            if (CODE.equals(w.code())) {
                out.add(w);
            }
        }
        return out;
    }

    private MutationResult<ViewObjectDto> widenChild() {
        return accessor.updateViewObject(SESSION, "child", null, null, 400, null,
                null, null, null, null);
    }

    private static int[] rect(IBounds b) {
        return new int[] { b.getX(), b.getY(), b.getWidth(), b.getHeight() };
    }

    // ---- update-view-object ---------------------------------------------------------------------

    @Test
    public void updateViewObject_growingAGroupIntoItsSibling_namesTheSibling_andMovesNothing() {
        twoGroupsSideBySide();
        int[] neighbourBefore = rect(((IDiagramModelGroup) view.getChildren().get(1)).getBounds());

        MutationResult<ViewObjectDto> result = widenChild();

        assertFalse("the cascade grew the group, so the resize is reported",
                result.entity().resizedAncestors().isEmpty());
        List<StructuredWarningDto> found = cascade(result.entity().structuredWarnings());
        assertEquals(1, found.size());
        StructuredWarningDto warning = found.get(0);
        assertEquals(List.of("h"), warning.remediationViolatorIds());
        assertEquals("arrange-groups", warning.remediationTool());
        assertTrue(warning.message(), warning.message().contains("'Grown' (view object 'g')")
                && warning.message().contains("'Neighbour' (view object 'h')")
                && warning.message().contains("which it did not overlap before")
                && warning.message().contains("Nothing was moved to make room."));
        assertTrue("the sibling is not moved",
                java.util.Arrays.equals(neighbourBefore,
                        rect(((IDiagramModelGroup) view.getChildren().get(1)).getBounds())));
    }

    @Test
    public void updateViewObject_aSiblingAlreadyOverlapped_isNotNews() {
        IDiagramModelGroup g = group(view, "g", "Grown", 10, 10, 300, 200);
        element(g, "child", 20, 40, 120, 55);
        group(view, "h", "Neighbour", 200, 10, 300, 200); // already under g's right half

        assertEquals(List.of(), cascade(widenChild().entity().structuredWarnings()));
    }

    @Test
    public void updateViewObject_aGrowthThatStaysClear_warnsNothing() {
        IDiagramModelGroup g = group(view, "g", "Grown", 10, 10, 300, 200);
        element(g, "child", 20, 40, 120, 55);
        group(view, "h", "Neighbour", 900, 10, 300, 200);

        MutationResult<ViewObjectDto> result = widenChild();

        assertFalse(result.entity().resizedAncestors().isEmpty());
        assertEquals(List.of(), cascade(result.entity().structuredWarnings()));
    }

    @Test
    public void updateViewObject_aNestedGroup_pointsAtLayoutWithinGroupOnItsParent() {
        IDiagramModelGroup outer = group(view, "outer", "Outer", 0, 0, 2000, 1000);
        IDiagramModelGroup g = group(outer, "g", "Grown", 10, 30, 300, 200);
        element(g, "child", 20, 40, 120, 55);
        element(outer, "e", 330, 30, 120, 55);

        List<StructuredWarningDto> found = cascade(widenChild().entity().structuredWarnings());

        assertEquals(1, found.size());
        assertEquals(List.of("e"), found.get(0).remediationViolatorIds());
        assertEquals("layout-within-group", found.get(0).remediationTool());
        assertTrue(found.get(0).message(), found.get(0).message().contains("'outer'"));
    }

    @Test
    public void updateViewObject_queued_labelsTheOverlapAProjection() {
        twoGroupsSideBySide();
        dispatcher.beginBatch(SESSION, "queued growth");

        MutationResult<ViewObjectDto> result = widenChild();

        assertTrue(result.isBatched());
        List<StructuredWarningDto> found = cascade(result.entity().structuredWarnings());
        assertEquals(1, found.size());
        assertTrue(found.get(0).message(), found.get(0).message().contains("will then overlap")
                && found.get(0).message().contains("an operation queued beside this one may still move"));
    }

    @Test
    public void updateViewObject_awaitingApproval_saysIfApproved() {
        twoGroupsSideBySide();
        dispatcher.setApprovalModeProvider(() -> true);

        MutationResult<ViewObjectDto> result = widenChild();

        assertTrue(result.isProposal());
        List<StructuredWarningDto> found = cascade(result.entity().structuredWarnings());
        assertEquals(1, found.size());
        assertTrue(found.get(0).message(), found.get(0).message().startsWith("If this change is approved"));
    }

    @Test
    public void bulkUpdateViewObject_carriesTheWarningOnItsOperation() {
        twoGroupsSideBySide();

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(new BulkOperation(
                "update-view-object", Map.of("viewObjectId", "child", "width", 400))), null, false);

        List<StructuredWarningDto> found = cascade(result.operations().get(0).structuredWarnings());
        assertEquals(1, found.size());
        assertEquals(List.of("h"), found.get(0).remediationViolatorIds());
    }

    // ---- layout-within-group --------------------------------------------------------------------

    @Test
    public void layoutWithinGroup_autoResizeIntoASibling_namesIt() {
        IDiagramModelGroup g = group(view, "g", "Grown", 10, 10, 200, 120);
        for (int i = 0; i < 6; i++) {
            element(g, "c" + i, 10, 30, 120, 55);
        }
        group(view, "h", "Neighbour", 230, 10, 300, 200);

        LayoutWithinGroupResultDto dto = accessor.layoutWithinGroup(SESSION, "view-1", "g",
                "row", null, null, null, null, true, false, null, false, false).entity();

        assertTrue("the fixture must grow the group", dto.groupResized());
        List<StructuredWarningDto> found = cascade(dto.structuredWarnings());
        assertEquals(1, found.size());
        assertEquals(List.of("h"), found.get(0).remediationViolatorIds());
    }

    // ---- the helper's rules -----------------------------------------------------------------------

    @Test
    public void forGroups_aSiblingTheCallAlsoMovesClear_isNotNamed() {
        twoGroupsSideBySide();
        List<StructuredWarningDto> found = CascadeOverlapWarning.forGroups(view,
                List.of(new net.vheerden.archi.mcp.response.dto.ResizedGroupDto("g", "Grown",
                        10, 10, 500, 200)),
                Map.of("h", new int[] { 600, 10, 300, 200 }), DispatchArm.APPLIED);
        assertEquals(List.of(), found);
    }

    @Test
    public void forGroups_notesAreLeftOut_asTheOverlapCountLeavesThemOut() {
        IDiagramModelGroup g = group(view, "g", "Grown", 10, 10, 300, 200);
        element(g, "child", 20, 40, 120, 55);
        var note = factory.createDiagramModelNote();
        note.setId("n");
        note.setBounds(330, 10, 100, 50);
        view.getChildren().add(note);

        assertEquals(List.of(), CascadeOverlapWarning.forGroups(view,
                List.of(new net.vheerden.archi.mcp.response.dto.ResizedGroupDto("g", "Grown",
                        10, 10, 500, 200)), null, DispatchArm.APPLIED));
    }

    @Test
    public void forGroups_namesEverySiblingStruck_inOneWarning() {
        twoGroupsSideBySide();
        element(view, "e", 330, 250, 120, 55);

        List<StructuredWarningDto> found = CascadeOverlapWarning.forGroups(view,
                List.of(new net.vheerden.archi.mcp.response.dto.ResizedGroupDto("g", "Grown",
                        10, 10, 500, 400)), null, DispatchArm.APPLIED);

        assertEquals(1, found.size());
        assertEquals(List.of("h", "e"), found.get(0).remediationViolatorIds());
        assertTrue(found.get(0).message(), found.get(0).message().contains("its siblings "));
    }

    @Test
    public void forCommand_countsOnlyAContainerThatGrew() {
        IDiagramModelGroup g = twoGroupsSideBySide();
        CompoundCommand moveOnly = new CompoundCommand();
        moveOnly.add(new UpdateViewObjectCommand(g, 40, 10, 300, 200)); // moved onto h, not grown
        assertEquals("a move is not a growth", List.of(),
                CascadeOverlapWarning.forCommand(view, moveOnly, DispatchArm.APPLIED));

        CompoundCommand grow = new CompoundCommand();
        grow.add(new UpdateViewObjectCommand(g, 10, 10, 400, 200));
        assertEquals(1, CascadeOverlapWarning.forCommand(view, grow, DispatchArm.APPLIED).size());
    }

    @Test
    public void aMeasurementThatFails_answersNothing_ratherThanFailingTheWrite() {
        twoGroupsSideBySide();
        List<net.vheerden.archi.mcp.response.dto.MovedViewObjectDto> broken = new ArrayList<>();
        broken.add(null);

        assertEquals(List.of(), CascadeOverlapWarning.forMoves(view, broken, null, DispatchArm.APPLIED));
    }

    @Test
    public void emitInto_pairsEachProseWarningWithItsStructuredTwin() {
        twoGroupsSideBySide();
        List<String> prose = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        CascadeOverlapWarning.emitInto(view,
                List.of(new net.vheerden.archi.mcp.response.dto.ResizedGroupDto("g", "Grown",
                        10, 10, 500, 200)), List.of(), DispatchArm.APPLIED, prose, structured);

        assertEquals(1, structured.size());
        assertEquals(List.of(structured.get(0).message()), prose);
    }

    /**
     * The four tools whose fixtures this lane cannot build cheaply (an icon corner, a nudge, a
     * measured label, a spacing pass) are held to the wiring here, one call each; the behaviour they
     * share is pinned above through update-view-object and layout-within-group, which reach the same
     * helper.
     */
    @Test
    public void everyResizeReportingTool_measuresTheOverlap() {
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");
        assertEquals("add-to-view and both update-view-object paths", 3,
                countOf(facade, "CascadeOverlapWarning.forMoves("));
        assertEquals("layout-within-group and adjust-view-spacing", 2,
                countOf(facade, "CascadeOverlapWarning.forCommand("));
        assertEquals("resize-elements-to-fit", 1, countOf(facade, "CascadeOverlapWarning.forGroups("));
        assertEquals("auto-route-connections", 1, countOf(facade, "CascadeOverlapWarning.emitInto("));
        assertEquals("adjust-view-spacing carries the measurement onto its response", 1,
                countOf(facade, "helper.cascadeWarnings()"));
        assertEquals("bulk-mutate projects an add-to-view operation's warnings", 1,
                countOf(readProductionSource("model/BulkResultProjection.java"),
                        "case AddToViewResultDto dto -> dto.structuredWarnings();"));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    private static String readProductionSource(String relativePath) {
        for (String candidate : new String[]{
                "../net.vheerden.archi.mcp/src", "net.vheerden.archi.mcp/src"}) {
            java.nio.file.Path path =
                    java.nio.file.Paths.get(candidate, "net/vheerden/archi/mcp", relativePath);
            if (java.nio.file.Files.isRegularFile(path)) {
                try {
                    return new String(java.nio.file.Files.readAllBytes(path),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }
        throw new AssertionError("Could not resolve " + relativePath
                + " — this gate cannot silently cover nothing");
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
