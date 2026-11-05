package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;

/**
 * Builds a captured view fixture as a real EMF view and routes it through the accessor exactly as
 * {@code auto-route-connections} does by default — the full pipeline under best-of-K with the
 * production scorer, then the write path.
 *
 * <p>Every element, group, note and relationship is left UNNAMED: a label reaches SWT text
 * measurement, which throws an {@code Error} on a display-less runner (see
 * {@code AutoRouteNoteCrossingDisclosureTest#addHost}). Routing reads geometry only. A fixture
 * element without a {@code type} is built as an {@code ApplicationComponent}, the kind those
 * captures hold, so a connection may end on any box, top-level or nested.</p>
 */
final class CapturedViewHarness {

    static final String VIEW_ID = "view-captured";
    private static final String SESSION = "captured-view-session";

    /** A built view: the model, its objects by fixture id, and each object's absolute origin. */
    static final class Ctx {
        IArchimateModel model;
        IArchimateDiagramModel view;
        final Map<String, IDiagramModelObject> objects = new HashMap<>();
        final Map<String, int[]> absolute = new HashMap<>();
        final Set<String> connectionIds = new LinkedHashSet<>();
    }

    private CapturedViewHarness() {}

    static Ctx newView() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        Ctx c = new Ctx();
        c.model = f.createArchimateModel();
        c.model.setId("model-captured");
        c.model.setDefaults();
        c.view = f.createArchimateDiagramModel();
        c.view.setId(VIEW_ID);
        c.model.getFolder(FolderType.DIAGRAMS).getElements().add(c.view);
        return c;
    }

    /** Builds the captured view: containers first, then children at parent-relative bounds. */
    static Ctx fromFixture(String path) throws IOException {
        return fromFixture(ViewFixture.load(path));
    }

    static Ctx fromFixture(ViewFixture fx) {
        Ctx c = newView();
        List<ViewFixture.FixtureElement> pending = new ArrayList<>(fx.getElements());
        while (!pending.isEmpty()) {
            int before = pending.size();
            for (var it = pending.iterator(); it.hasNext();) {
                ViewFixture.FixtureElement e = it.next();
                if (e.parentId() != null && !c.objects.containsKey(e.parentId())) {
                    continue;
                }
                String type = e.type() != null ? e.type() : "ApplicationComponent";
                addBox(c, e.id(), type, e.parentId(), e.x(), e.y(), e.w(), e.h());
                it.remove();
            }
            assertTrue("fixture parent chain resolves", pending.size() < before);
        }
        for (ViewFixture.FixtureConnection conn : fx.getConnections()) {
            connect(c, conn.id(), conn.sourceId(), conn.targetId());
        }
        return c;
    }

    /** Adds a box at ABSOLUTE bounds, converting to parent-relative when nested. Unnamed. */
    static void addBox(Ctx c, String id, String type, String parentId, int x, int y, int w, int h) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        String objId = id.startsWith("id-") ? id : "obj-" + id;
        IDiagramModelObject obj;
        if ("Group".equals(type)) {
            IDiagramModelGroup g = f.createDiagramModelGroup();
            obj = g;
        } else if ("Note".equals(type)) {
            IDiagramModelNote n = f.createDiagramModelNote();
            obj = n;
        } else {
            EClass eClass = (EClass) IArchimatePackage.eINSTANCE.getEClassifier(type);
            IArchimateElement element = (IArchimateElement) f.create(eClass);
            element.setId(id + "-el");
            c.model.getDefaultFolderForObject(element).getElements().add(element);
            IDiagramModelArchimateObject o = f.createDiagramModelArchimateObject();
            o.setArchimateConcept(element);
            obj = o;
        }
        obj.setId(objId);
        int rx = x;
        int ry = y;
        IDiagramModelContainer parent = c.view;
        if (parentId != null) {
            int[] p = c.absolute.get(parentId);
            rx -= p[0];
            ry -= p[1];
            parent = (IDiagramModelContainer) c.objects.get(parentId);
        }
        obj.setBounds(rx, ry, w, h);
        parent.getChildren().add(obj);
        c.objects.put(id, obj);
        c.absolute.put(id, new int[] {x, y});
    }

    /** An unnamed Association, built directly (the validity matrix needs OSGi). */
    static void connect(Ctx c, String connId, String sourceId, String targetId) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject src = (IDiagramModelArchimateObject) object(c, sourceId);
        IDiagramModelArchimateObject tgt = (IDiagramModelArchimateObject) object(c, targetId);
        IArchimateRelationship rel = f.createAssociationRelationship();
        rel.setId(connId + "-rel");
        rel.setSource(src.getArchimateConcept());
        rel.setTarget(tgt.getArchimateConcept());
        c.model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        IDiagramModelArchimateConnection conn = f.createDiagramModelArchimateConnection();
        conn.setId(connId);
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
        c.connectionIds.add(connId);
    }

    static IDiagramModelObject object(Ctx c, String id) {
        IDiagramModelObject o = c.objects.get(id);
        if (o == null) {
            o = c.objects.get(id.startsWith("obj-") ? id.substring(4) : id);
        }
        assertNotNull("no view object " + id, o);
        return o;
    }

    /** Routes the whole view with the tool's defaults; {@code read} runs on the routed model. */
    static <T> T route(Ctx c, java.util.function.BiFunction<AutoRouteResultDto, ArchiModelAccessorImpl, T> read) {
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.models = List.of(c.model);
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher(c.model));
        try {
            AutoRouteResultDto dto = accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal",
                    false, false, 0, 0, null).entity();
            return read.apply(dto, accessor);
        } finally {
            accessor.dispose();
        }
    }

    /** Routes the whole view with the tool's defaults. */
    static AutoRouteResultDto route(Ctx c) {
        return route(c, (dto, accessor) -> dto);
    }

    /** Routes the whole view with the tool's defaults but the given {@code autoNudge}. */
    static AutoRouteResultDto routeWith(Ctx c, boolean autoNudge) {
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.models = List.of(c.model);
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher(c.model));
        try {
            return accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal",
                    false, autoNudge, 0, 0, null).entity();
        } finally {
            accessor.dispose();
        }
    }

    private static MutationDispatcher dispatcher(IArchimateModel target) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> target) {
            @Override
            public void dispatchImmediate(Command command) {
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    command.execute();
                }
            }
        };
        testDispatcher.setApprovalModeProvider(() -> false);
        return testDispatcher;
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        List<IArchimateModel> models = new ArrayList<>();
        private final Set<PropertyChangeListener> listeners = new LinkedHashSet<>();

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener l) { listeners.add(l); }
        @Override public void removePropertyChangeListener(PropertyChangeListener l) { listeners.remove(l); }
        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel model) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel model) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel model, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel model) { return false; }
        @Override public boolean saveModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel model) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object source, String prop, Object oldValue, Object newValue) {}
    }
}
