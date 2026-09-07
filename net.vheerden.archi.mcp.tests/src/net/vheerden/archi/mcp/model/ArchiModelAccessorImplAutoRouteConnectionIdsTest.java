package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IApplicationComponent;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelConnection;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Accessor-level coverage for the {@code connectionIds} filter in
 * {@link ArchiModelAccessorImpl#autoRouteConnections}.
 *
 * <p>Two behaviours are pinned here that handler tests structurally cannot reach, because handler
 * tests mock the accessor and therefore supply the very result they assert on:</p>
 *
 * <ul>
 *   <li>An unknown connection ID raises a <em>coded</em> warning naming the offending IDs, so the
 *       response can distinguish this condition from the several unrelated routing conditions that
 *       also write free text. Callers must never have to infer it from the warnings list being
 *       non-empty.</li>
 *   <li>A call whose IDs are <em>all</em> unknown still fails loudly rather than reporting a
 *       vacuous success with zero connections routed.</li>
 * </ul>
 *
 * <p>Real EMF via {@link IArchimateFactory#eINSTANCE}; the accessor is wired with a minimal
 * {@link IEditorModelManager} stub plus a synchronous dispatcher that bypasses
 * {@code Display.syncExec} + {@code CommandStack}, mirroring the established accessor-test
 * pattern.</p>
 */
public class ArchiModelAccessorImplAutoRouteConnectionIdsTest {

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ------------------------------------------------------------------
    // A partial miss: route what resolved, and say which IDs did not — in code, not just prose.
    // ------------------------------------------------------------------

    @Test
    public void autoRoute_whenSomeConnectionIdsUnknown_emitsCodedWarningNamingTheMissingIds() {
        String realConnectionId = buildRoutableViewAndReturnConnectionId();

        MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(
                "default", "view-001", List.of(realConnectionId, "bogus-id"),
                "orthogonal", false, false, 20, 50, null);

        AutoRouteResultDto dto = result.entity();
        assertNotNull(dto);

        List<StructuredWarningDto> coded = dto.structuredWarnings().stream()
                .filter(w -> StructuredWarningCodes.CONNECTION_NOT_FOUND.equals(w.code()))
                .toList();
        assertEquals("exactly one aggregate connection-not-found warning", 1, coded.size());
        assertEquals("the missing ID is machine-readable, not buried in prose",
                List.of("bogus-id"), coded.get(0).remediationViolatorIds());

        assertTrue("the per-ID free-text surface is preserved verbatim",
                dto.warnings().contains("Connection not found on view: bogus-id"));
    }

    // ------------------------------------------------------------------
    // terminals-only must carry the code too. This branch builds its own DTO and previously
    // hardcoded an empty structured-warnings list, so a coded warning raised before the branch
    // was silently dropped on the way out.
    // ------------------------------------------------------------------

    @Test
    public void autoRoute_terminalsOnly_whenSomeConnectionIdsUnknown_stillCarriesTheCodedWarning() {
        String realConnectionId = buildRoutableViewAndReturnConnectionId();

        MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(
                "default", "view-001", List.of(realConnectionId, "bogus-id"),
                "orthogonal", false, false, 20, 50, "terminals-only");

        AutoRouteResultDto dto = result.entity();
        assertNotNull(dto);
        assertTrue("terminals-only must not drop the coded warning the filter raised",
                dto.structuredWarnings().stream().anyMatch(
                        w -> StructuredWarningCodes.CONNECTION_NOT_FOUND.equals(w.code())));
        assertTrue("and it keeps the free-text line alongside it",
                dto.warnings().contains("Connection not found on view: bogus-id"));
    }

    // ------------------------------------------------------------------
    // No over-correction: every ID resolved → no code, no free text.
    // ------------------------------------------------------------------

    @Test
    public void autoRoute_whenEveryConnectionIdResolves_emitsNoConnectionNotFoundWarning() {
        String realConnectionId = buildRoutableViewAndReturnConnectionId();

        MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(
                "default", "view-001", List.of(realConnectionId),
                "orthogonal", false, false, 20, 50, null);

        AutoRouteResultDto dto = result.entity();
        assertNotNull(dto);
        assertFalse("a clean call must not claim any ID was missing",
                dto.structuredWarnings().stream().anyMatch(
                        w -> StructuredWarningCodes.CONNECTION_NOT_FOUND.equals(w.code())));
        assertFalse("nor emit the free-text line",
                dto.warnings().stream().anyMatch(w -> w.startsWith("Connection not found on view:")));
    }

    // ------------------------------------------------------------------
    // Every ID unknown → hard error, never a vacuous success. Guards the reachability of the
    // all-unknown check, which is keyed off the collected missing-ID list.
    // ------------------------------------------------------------------

    @Test
    public void autoRoute_whenAllConnectionIdsUnknown_throwsRatherThanReportingVacuousSuccess() {
        buildRoutableViewAndReturnConnectionId();

        try {
            MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(
                    "default", "view-001", List.of("bogus-1", "bogus-2"),
                    "orthogonal", false, false, 20, 50, null);
            fail("expected ELEMENT_NOT_FOUND, but got a success result with "
                    + result.entity().connectionsRouted() + " connections routed");
        } catch (ModelAccessException e) {
            assertEquals("all-unknown IDs is a caller error, not a silent no-op",
                    ErrorCode.ELEMENT_NOT_FOUND, e.getErrorCode());
            assertTrue("the message names the condition",
                    e.getMessage().contains("None of the specified connection IDs were found"));
        }
    }

    // ------------------------------------------------------------------
    // A repeated ID names one connection, so it must route one connection. The filter appended
    // connMap.get(connId) per input entry with no uniqueness check, and the pipeline downstream is
    // index-parallel rather than id-keyed — so a duplicate was routed a second time and recorded
    // its corridor occupancy twice, perturbing the A* cost of every connection routed after it.
    // ------------------------------------------------------------------

    @Test
    public void autoRoute_whenAConnectionIdIsRepeated_routesThatConnectionOnce() {
        List<String> ids = buildTwoConnectionViewAndReturnConnectionIds();

        MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(
                "default", "view-001", List.of(ids.get(0), ids.get(0), ids.get(1)),
                "orthogonal", false, false, 20, 50, null);

        assertEquals("three input entries naming two connections must route two connections, "
                + "not three — connectionsRouted is a report of what the model holds, and a caller "
                + "who cannot see the canvas has nothing else to check it against",
                2, result.entity().connectionsRouted());
    }

    /**
     * The negative control. A dedupe that collapsed distinct ids would be a worse defect than the
     * one it replaced, and a pin on the duplicate case alone cannot see that.
     */
    @Test
    public void autoRoute_whenEveryConnectionIdIsDistinct_routesEveryOne() {
        List<String> ids = buildTwoConnectionViewAndReturnConnectionIds();

        MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(
                "default", "view-001", ids, "orthogonal", false, false, 20, 50, null);

        assertEquals("two distinct ids must still route two connections",
                2, result.entity().connectionsRouted());
    }

    /**
     * The reason the dedupe belongs on the loop's input rather than on the list it builds. A
     * repeated <em>bogus</em> id was appended to the missing-id list once per input entry, so the
     * coded warning named the same id twice and a caller counting violators over-counted. Deduping
     * the resolved connections afterwards would not have touched this half.
     */
    @Test
    public void autoRoute_whenAnUnknownConnectionIdIsRepeated_reportsItOnce() {
        List<String> ids = buildTwoConnectionViewAndReturnConnectionIds();

        MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(
                "default", "view-001", List.of(ids.get(0), "bogus-id", "bogus-id"),
                "orthogonal", false, false, 20, 50, null);

        AutoRouteResultDto dto = result.entity();
        List<StructuredWarningDto> coded = dto.structuredWarnings().stream()
                .filter(w -> StructuredWarningCodes.CONNECTION_NOT_FOUND.equals(w.code()))
                .toList();
        assertEquals("still exactly one aggregate warning", 1, coded.size());
        assertEquals("naming the missing id once, not once per time it was typed",
                List.of("bogus-id"), coded.get(0).remediationViolatorIds());
        assertEquals("and the free-text surface says it once too", 1,
                dto.warnings().stream()
                        .filter(w -> w.equals("Connection not found on view: bogus-id")).count());
    }

    // ------------------------------------------------------------------
    // Fixture: two components + a serving relationship, both placed on view-001 and connected,
    // yielding one real IDiagramModelConnection whose ID the tests target.
    // ------------------------------------------------------------------

    private String buildRoutableViewAndReturnConnectionId() {
        IArchimateModel model = factory.createArchimateModel();
        model.setName("auto-route connectionIds fixture");
        model.setId("model-auto-route-connection-ids");
        model.setDefaults();

        IApplicationComponent src = factory.createApplicationComponent();
        src.setId("ac-001");
        src.setName("Order System");
        model.getFolder(FolderType.APPLICATION).getElements().add(src);

        IApplicationComponent tgt = factory.createApplicationComponent();
        tgt.setId("ac-002");
        tgt.setName("Billing System");
        model.getFolder(FolderType.APPLICATION).getElements().add(tgt);

        IArchimateRelationship serving = factory.createServingRelationship();
        serving.setId("rel-001");
        serving.setName("serves");
        serving.connect(src, tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(serving);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Main View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Generous spacing so the router has a real corridor to work with.
        accessor.addToView("default", "view-001", "ac-001", 50, 50, 120, 55,
                false, null, null, null);
        accessor.addToView("default", "view-001", "ac-002", 400, 300, 120, 55,
                false, null, null, null);

        MutationResult<AutoConnectResultDto> connected = accessor.autoConnectView(
                "default", "view-001", null, null, null, null, null);
        assertEquals("fixture must yield exactly one drawn connection",
                1, connected.entity().connectionsCreated());

        String connectionId = findFirstConnectionId(view);
        assertNotNull("fixture must expose a real connection ID to target", connectionId);
        return connectionId;
    }

    /**
     * Fixture variant with <em>two</em> drawn connections, so a duplicate entry can be told apart
     * from a second distinct one. The single-connection fixture above structurally cannot
     * discriminate: with one connection on the view, "routed once" and "routed twice" are the only
     * outcomes and neither shows whether a distinct id would have survived.
     */
    private List<String> buildTwoConnectionViewAndReturnConnectionIds() {
        IArchimateModel model = factory.createArchimateModel();
        model.setName("auto-route duplicate-id fixture");
        model.setId("model-auto-route-duplicate-ids");
        model.setDefaults();

        IApplicationComponent src = factory.createApplicationComponent();
        src.setId("ac-001");
        src.setName("Order System");
        model.getFolder(FolderType.APPLICATION).getElements().add(src);

        IApplicationComponent mid = factory.createApplicationComponent();
        mid.setId("ac-002");
        mid.setName("Billing System");
        model.getFolder(FolderType.APPLICATION).getElements().add(mid);

        IApplicationComponent tgt = factory.createApplicationComponent();
        tgt.setId("ac-003");
        tgt.setName("Ledger System");
        model.getFolder(FolderType.APPLICATION).getElements().add(tgt);

        IArchimateRelationship first = factory.createServingRelationship();
        first.setId("rel-001");
        first.setName("serves");
        first.connect(src, mid);
        model.getFolder(FolderType.RELATIONS).getElements().add(first);

        IArchimateRelationship second = factory.createServingRelationship();
        second.setId("rel-002");
        second.setName("posts to");
        second.connect(mid, tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(second);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Main View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Generous spacing so the router has real corridors to work with.
        accessor.addToView("default", "view-001", "ac-001", 50, 50, 120, 55,
                false, null, null, null);
        accessor.addToView("default", "view-001", "ac-002", 400, 300, 120, 55,
                false, null, null, null);
        accessor.addToView("default", "view-001", "ac-003", 750, 50, 120, 55,
                false, null, null, null);

        MutationResult<AutoConnectResultDto> connected = accessor.autoConnectView(
                "default", "view-001", null, null, null, null, null);
        assertEquals("fixture must yield exactly two drawn connections",
                2, connected.entity().connectionsCreated());

        List<String> ids = findAllConnectionIds(view);
        assertEquals("fixture must expose two distinct connection IDs to target", 2, ids.size());
        return ids;
    }

    private List<String> findAllConnectionIds(IArchimateDiagramModel view) {
        List<String> ids = new ArrayList<>();
        for (Object child : view.getChildren()) {
            if (child instanceof com.archimatetool.model.IDiagramModelObject dmo) {
                for (IDiagramModelConnection conn : dmo.getSourceConnections()) {
                    if (!ids.contains(conn.getId())) {
                        ids.add(conn.getId());
                    }
                }
            }
        }
        return ids;
    }

    private String findFirstConnectionId(IArchimateDiagramModel view) {
        for (Object child : view.getChildren()) {
            if (child instanceof com.archimatetool.model.IDiagramModelObject dmo) {
                for (IDiagramModelConnection conn : dmo.getSourceConnections()) {
                    return conn.getId();
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Test plumbing — mirror of the established accessor-test pattern.
    // ------------------------------------------------------------------

    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(IArchimateModel testModel) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> testModel) {
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
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        @Override
        public List<IArchimateModel> getModels() { return models; }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

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
