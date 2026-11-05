package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
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
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * {@code auto-layout-and-route} keeps every self-connection a loop. Its routing pass routes the
 * view's connections, and the orthogonal router turns a self-connection into one bendpoint beside
 * its object, a stub; so the pass takes them out, as {@code auto-route-connections} does: one with
 * no bendpoints is given Archi's default loop, one with bendpoints keeps them, and the response
 * names them once per call however many times the pass runs. The spacing tool re-routes through
 * the same pass.
 *
 * <p>Display lane: the layout sizes elements and the routing pass assesses its candidates, both of
 * which reach SWT.</p>
 */
public class AutoLayoutAndRouteSelfConnectionTest {

    private static final String SESSION = "auto-layout-self-connection-session";
    private static final String VIEW_ID = "view-auto-layout-self";
    /** A loop a human drew, deliberately not Archi's default shape. */
    private static final List<BendpointDto> DRAWN = List.of(new BendpointDto(-90, 0, -90, 0),
            new BendpointDto(-90, -70, -90, -70), new BendpointDto(0, -70, 0, -70));

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private IDiagramModelArchimateObject bareObj;
    private IDiagramModelArchimateConnection bare;
    private IDiagramModelArchimateConnection loop;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-auto-layout-self");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId(VIEW_ID);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IDiagramModelGroup left = group("grp-left", 0, 0, 400, 300);
        IDiagramModelGroup right = group("grp-right", 600, 0, 400, 300);
        bareObj = object(left, "bare", 20, 40);
        IDiagramModelArchimateObject a = object(left, "a", 200, 160);
        IDiagramModelArchimateObject loopObj = object(right, "loop", 20, 40);
        IDiagramModelArchimateObject b = object(right, "b", 200, 160);
        bare = connect("self-without-loop", bareObj, bareObj);
        loop = connect("loop-self", loopObj, loopObj);
        ConnectionResponseBuilder.applyBendpointsToConnection(loop, DRAWN);
        connect("a-b", a, b);
        connect("bare-a", bareObj, a);
        connect("loop-b", loopObj, b);

        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.models = List.of(model);
        accessor = new ArchiModelAccessorImpl(mgr, dispatcher());
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    @Test
    public void grouped_shouldLoopABareSelfConnection_andKeepAStoredLoop() {
        AutoLayoutAndRouteResultDto dto = run("grouped", null);
        assertLoops("grouped", dto);
    }

    @Test
    public void groupedWithTarget_shouldLoopABareSelfConnection_andKeepAStoredLoop_namingThemOnce() {
        AutoLayoutAndRouteResultDto dto = run("grouped", "excellent");
        assertLoops("grouped, target", dto);
    }

    /**
     * Three objects in each group, each linked to all three across: a graph that cannot be drawn
     * without crossings, so the loop does not reach its target and runs pass after pass. The
     * self-connections are still named once, from the pass the loop keeps.
     */
    @Test
    public void groupedWithTarget_namesTheSelfConnectionsOnce_whenTheLoopRunsSeveralPasses() {
        IDiagramModelGroup left = (IDiagramModelGroup) bareObj.eContainer();
        IDiagramModelGroup right = (IDiagramModelGroup) ((IDiagramModelObject) loop.getSource()).eContainer();
        List<IDiagramModelArchimateObject> lefts = new java.util.ArrayList<>();
        List<IDiagramModelArchimateObject> rights = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            lefts.add(object(left, "l" + i, 20 + i * 130, 220));
            rights.add(object(right, "r" + i, 20 + i * 130, 220));
        }
        for (IDiagramModelArchimateObject l : lefts) {
            for (IDiagramModelArchimateObject r : rights) {
                connect(l.getId() + "-" + r.getId(), l, r);
            }
        }

        AutoLayoutAndRouteResultDto dto = run("grouped", "excellent");

        assertTrue("precondition: the loop ran more than one pass: " + dto.iterationsPerformed()
                + " " + dto.terminationReason(),
                dto.iterationsPerformed() != null && dto.iterationsPerformed() > 1);
        assertLoops("grouped, several passes", dto);
    }

    /**
     * Flat mode lays the view out with ELK, which draws every connection, a self-connection as a
     * loop; the routing pass runs only when reordering the groups gains something, which on this
     * view it does not. Each loop is drawn beside its own object, inside its own group: ELK gives
     * an edge between two children of a group in the group's frame.
     */
    @Test
    public void flatWithTarget_shouldLeaveEverySelfConnectionALoopBesideItsObject_namingThemAtMostOnce() {
        AutoLayoutAndRouteResultDto dto = run("auto", "excellent");
        for (IDiagramModelArchimateConnection conn : List.of(bare, loop)) {
            List<BendpointDto> bps = stored(conn);
            assertTrue("flat, target: " + conn.getId() + " is a loop, not a stub: " + bps, bps.size() >= 2);
            IDiagramModelObject object = (IDiagramModelObject) conn.getSource();
            IDiagramModelObject group = (IDiagramModelObject) object.eContainer();
            IBounds g = group.getBounds();
            int[] centre = ConnectionResponseBuilder.computeAbsoluteCenter(object);
            for (BendpointDto bp : bps) {
                int x = centre[0] + bp.startX();
                int y = centre[1] + bp.startY();
                assertTrue("flat, target: " + conn.getId() + " bendpoint (" + x + "," + y
                        + ") lies inside its group " + g.getX() + "," + g.getY() + " " + g.getWidth()
                        + "x" + g.getHeight(), x >= g.getX() && x <= g.getX() + g.getWidth()
                                && y >= g.getY() && y <= g.getY() + g.getHeight());
            }
        }
        assertTrue("flat, target: " + dto.structuredWarnings(), selfWarnings(dto).size() <= 1);
    }

    /** The spacing tool re-routes through the same pass, so it keeps every loop too. */
    @Test
    public void adjustViewSpacing_shouldLoopABareSelfConnection_andKeepAStoredLoop() {
        accessor.adjustViewSpacing(SESSION, VIEW_ID, 40, null, 40, true);
        assertEquals("spacing: the bare one is given Archi's default loop",
                SelfConnectionLoop.defaultLoop(bareObj), stored(bare));
        assertEquals("spacing: the stored loop is kept byte for byte", DRAWN, stored(loop));
    }

    /**
     * A recursive {@code layout-within-group} fits each group with the same arrangement the grouped
     * layout uses, so it leaves room for each loop too: the stored one kept as drawn, and the one
     * Archi's default loop will give the bare self-connection once it is routed.
     */
    @Test
    public void layoutWithinGroupRecursive_shouldFitEachGroupAroundItsLoops() {
        for (String groupId : List.of("grp-left", "grp-right")) {
            accessor.layoutWithinGroup(SESSION, VIEW_ID, groupId, "row", null, null, null, null, true,
                    false, null, false, true);
        }
        assertEquals(List.of(), loopPointsOutsideTheirGroups());
    }

    /**
     * optimize-group-order called on its own re-lays out each group it reorders: crossing lines
     * between the groups make it reorder one, and every loop in a group it re-laid out must fit it.
     * A group it leaves alone is not its to fix.
     */
    @Test
    public void optimizeGroupOrder_shouldFitEachReorderedGroupAroundItsLoops() {
        IDiagramModelGroup left = (IDiagramModelGroup) bareObj.eContainer();
        IDiagramModelGroup right = (IDiagramModelGroup) ((IDiagramModelObject) loop.getSource()).eContainer();
        IDiagramModelArchimateObject lower = object(left, "lower", 20, 220);
        IDiagramModelArchimateObject upper = object(right, "upper", 20, 220);
        connect("bare-upper", bareObj, upper);
        connect("lower-loop", lower, (IDiagramModelArchimateObject) loop.getSource());
        for (String arrangement : List.of("row", "column", "grid")) {
            MutationResult<OptimizeGroupOrderResultDto> result = accessor.optimizeGroupOrder(SESSION, VIEW_ID,
                    arrangement, null, null, null, null, false, null, null);
            if (result.entity().groupsOptimized() > 0) {
                List<String> relaid = result.entity().groupDetails().stream()
                        .filter(OptimizeGroupOrderResultDto.GroupDetail::reordered)
                        .map(OptimizeGroupOrderResultDto.GroupDetail::groupId).toList();
                List<String> outside = new java.util.ArrayList<>();
                for (IDiagramModelArchimateConnection conn : List.of(bare, loop)) {
                    String groupId = ((IDiagramModelObject) conn.getSource().eContainer()).getId();
                    if (relaid.contains(groupId)) {
                        loopPointsOutsideTheirGroups().stream().filter(row -> row.startsWith(conn.getId() + " "))
                                .forEach(outside::add);
                    }
                }
                assertEquals(arrangement + " re-laid out " + relaid, List.of(), outside);
                return;
            }
        }
        org.junit.Assert.fail("no arrangement reordered a group, so the fixture proves nothing");
    }

    private void assertLoops(String label, AutoLayoutAndRouteResultDto dto) {
        assertEquals(label + ": the bare one is given Archi's default loop",
                SelfConnectionLoop.defaultLoop(bareObj), stored(bare));
        assertEquals(label + ": the stored loop is kept byte for byte", DRAWN, stored(loop));
        List<StructuredWarningDto> warnings = selfWarnings(dto);
        assertEquals(label + ": one entry per call: " + dto.structuredWarnings(), 1, warnings.size());
        assertEquals(label + ": every loop lies inside its group", List.of(), loopPointsOutsideTheirGroups());
    }

    /**
     * Every point of each self-connection's loop that falls outside its group or over a neighbour in
     * it: the stored loop, or for one stored without bendpoints the default loop Archi gives it from
     * the object's size.
     */
    private List<String> loopPointsOutsideTheirGroups() {
        List<String> outside = new java.util.ArrayList<>();
        for (IDiagramModelArchimateConnection conn : List.of(bare, loop)) {
            IDiagramModelObject object = (IDiagramModelObject) conn.getSource();
            List<BendpointDto> bps = stored(conn).isEmpty() ? SelfConnectionLoop.defaultLoop(object) : stored(conn);
            IBounds g = ((IDiagramModelObject) object.eContainer()).getBounds();
            int[] centre = ConnectionResponseBuilder.computeAbsoluteCenter(object);
            for (BendpointDto bp : bps) {
                int x = centre[0] + bp.startX();
                int y = centre[1] + bp.startY();
                if (x < g.getX() || x > g.getX() + g.getWidth() || y < g.getY() || y > g.getY() + g.getHeight()) {
                    outside.add(conn.getId() + " (" + x + "," + y + ") outside " + g.getX() + "," + g.getY()
                            + " " + g.getWidth() + "x" + g.getHeight());
                }
                // nor over a neighbour: the groups are top-level, so a sibling's box is group-relative
                for (IDiagramModelObject sibling : ((IDiagramModelGroup) object.eContainer()).getChildren()) {
                    IBounds b = sibling.getBounds();
                    int sx = g.getX() + b.getX();
                    int sy = g.getY() + b.getY();
                    if (sibling != object && x > sx && x < sx + b.getWidth() && y > sy && y < sy + b.getHeight()) {
                        outside.add(conn.getId() + " (" + x + "," + y + ") over " + sibling.getName());
                    }
                }
            }
        }
        return outside;
    }

    private AutoLayoutAndRouteResultDto run(String mode, String targetRating) {
        return accessor.autoLayoutAndRoute(SESSION, VIEW_ID, mode, "DOWN", 50, targetRating, null).entity();
    }

    private static List<StructuredWarningDto> selfWarnings(AutoLayoutAndRouteResultDto dto) {
        return dto.structuredWarnings().stream()
                .filter(w -> StructuredWarningCodes.SELF_CONNECTION_NOT_ROUTED.equals(w.code())).toList();
    }

    private static List<BendpointDto> stored(IDiagramModelArchimateConnection conn) {
        return ConnectionResponseBuilder.collectBendpoints(conn);
    }

    private IDiagramModelGroup group(String id, int x, int y, int w, int h) {
        IDiagramModelGroup g = factory.createDiagramModelGroup();
        g.setId(id);
        g.setBounds(x, y, w, h);
        view.getChildren().add(g);
        return g;
    }

    private IDiagramModelArchimateObject object(IDiagramModelContainer parent, String id, int x, int y) {
        IArchimateElement el = factory.createApplicationComponent();
        el.setId("el-" + id);
        model.getFolder(FolderType.APPLICATION).getElements().add(el);
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId("vo-" + id);
        obj.setArchimateElement(el);
        obj.setBounds(x, y, 120, 55);
        parent.getChildren().add(obj);
        return obj;
    }

    private IDiagramModelArchimateConnection connect(String id, IDiagramModelArchimateObject src,
            IDiagramModelArchimateObject tgt) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-" + id);
        rel.setSource(src.getArchimateElement());
        rel.setTarget(tgt.getArchimateElement());
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setId(id);
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
        return conn;
    }

    private MutationDispatcher dispatcher() {
        MutationDispatcher dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(toPlainCompound(command));
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        return dispatcher;
    }

    private static Command toPlainCompound(Command command) {
        if (command instanceof CompoundCommand compound) {
            CompoundCommand plain = new CompoundCommand(compound.getLabel());
            for (Object child : compound.getCommands()) {
                plain.add(toPlainCompound((Command) child));
            }
            return plain;
        }
        return command;
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

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
