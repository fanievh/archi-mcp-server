package net.vheerden.archi.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.model.DeleteViewCommand;

/**
 * Integration proof for the delete-view nested-connection question.
 *
 * <p>{@link DeleteViewCommand#execute()} captures connections by iterating only
 * the view's <em>top-level</em> children, so a connection whose <strong>both</strong>
 * endpoints are nested inside a group is never {@code disconnect()}ed — unlike the
 * sibling {@code ClearViewCommand}, which collects connections recursively. The open
 * question was whether that asymmetry loses data: does the un-disconnected nested
 * connection survive a {@code .archimate} save/reload round-trip as a dangling
 * cross-reference, the way an un-cascaded {@link IDiagramModelReference} once broke
 * {@code Routing Pipeline Comparison.archimate}?</p>
 *
 * <p>This test reproduces the exact mechanism (a group-nested source and target with a
 * connection between them, invisible to a top-level-only walk), verifies where the
 * connection is contained in EMF, deletes the view, saves, and reloads. It asserts the
 * reload succeeds with zero dangling references of either kind, and that the whole
 * subtree — group, both nested objects, and the connection — leaves the model together.
 * The conclusion: on delete-view the non-recursion is safe precisely because the entire
 * view subtree is removed in one command, so nested connections leave with their
 * endpoints rather than orphaning.</p>
 *
 * <p>Requires the OSGi/PDE runtime ({@link IEditorModelManager}'s static initializer
 * touches {@code ArchiPlugin.getInstance()}, and {@code saveModel}/{@code loadModel}
 * need it). Guarded by {@link Platform#isRunning()} so non-PDE launches skip cleanly.</p>
 */
public class DeleteViewNestedConnectionRoundTripIntegrationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Before
    public void requireOsgiRuntime() {
        assumeTrue("requires PDE/OSGi runtime", Platform.isRunning());
    }

    @Test
    public void shouldNotOrphanNestedToNestedConnection_whenViewDeletedAndSerialized() throws Exception {
        runProof(tempFolder.getRoot());
    }

    /**
     * The full round-trip proof, factored out so it can be driven either from this
     * class's own {@code @Test} or from a borrowed launch harness. Performs every
     * assertion and returns a human-readable summary of the measured values on success;
     * a failing assertion throws {@link AssertionError} as usual.
     */
    public static String runProof(File tempDir) throws Exception {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;

        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();
        model.setName("Nested Connection Round-Trip Pin");

        IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("View With Nested Connection");
        diagramsFolder.getElements().add(view);

        // A group is the ONLY top-level child of the view. Both connected objects live
        // inside it, so DeleteViewCommand's top-level capture loop never sees the
        // connection between them.
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setName("Channel Edge");
        group.setBounds(0, 0, 400, 300);
        view.getChildren().add(group);

        IBusinessActor actorA = factory.createBusinessActor();
        actorA.setId("actor-a");
        actorA.setName("actor-a");
        model.getFolder(FolderType.BUSINESS).getElements().add(actorA);
        IBusinessActor actorB = factory.createBusinessActor();
        actorB.setId("actor-b");
        actorB.setName("actor-b");
        model.getFolder(FolderType.BUSINESS).getElements().add(actorB);

        IDiagramModelArchimateObject nestedSource = place(factory, group, actorA, "dmo-nested-source");
        IDiagramModelArchimateObject nestedTarget = place(factory, group, actorB, "dmo-nested-target");

        IAssociationRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-nested");
        rel.connect(actorA, actorB);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IDiagramModelArchimateConnection nestedConn = factory.createDiagramModelArchimateConnection();
        nestedConn.setId("conn-nested-to-nested");
        nestedConn.setArchimateRelationship(rel);
        nestedConn.connect(nestedSource, nestedTarget);

        // Fixture-shape sanity: prove this genuinely exercises the missed path.
        // A top-level-only capture (what DeleteViewCommand does) must NOT find the
        // connection; a recursive capture (what ClearViewCommand does) MUST.
        Set<IDiagramModelConnection> topLevelOnly = collectTopLevelConnections(view);
        assertFalse("Fixture is only meaningful if a top-level-only walk misses the nested "
                + "connection — otherwise DeleteViewCommand would disconnect it and this proves nothing",
                topLevelOnly.contains(nestedConn));
        Set<IDiagramModelConnection> recursive = new LinkedHashSet<>();
        collectConnectionsRecursive(group, recursive);
        assertTrue("A recursive walk must find the nested connection (sanity: the fixture is wired)",
                recursive.contains(nestedConn));

        // Containment, verified before delete: a diagram connection is contained by its
        // SOURCE object (sourceConnections is a containment feature). That is the fact the
        // "leaves atomically" reasoning rests on: conn -> nestedSource -> group -> view.
        assertSame("Connection is contained by its source object",
                nestedSource, nestedConn.eContainer());
        assertSame("Source is contained by the group", group, nestedSource.eContainer());
        assertSame("Group is contained by the view", view, group.eContainer());

        // Persist, delete, save, reload.
        IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(model);
        model.setAdapter(IArchiveManager.class, archiveManager);

        File savedFile = new File(tempDir, "nested-conn-pin.archimate");
        model.setFile(savedFile);

        int viewIndex = diagramsFolder.getElements().indexOf(view);
        DeleteViewCommand cmd = new DeleteViewCommand(view, diagramsFolder, viewIndex);
        cmd.execute();

        // Reachability after delete: the whole subtree left the live model together.
        assertFalse("View removed from its folder",
                diagramsFolder.getElements().contains(view));
        assertFalse("Deleted subtree (group, nested objects, connection) is no longer reachable "
                + "from the live model's DIAGRAMS folder",
                reachableFromDiagrams(model, group, nestedSource, nestedTarget, nestedConn));

        archiveManager.saveModel();
        assertTrue("Saved file should exist", savedFile.exists());

        IArchimateModel reloaded = IEditorModelManager.INSTANCE.loadModel(savedFile);
        try {
            assertNotNull("Reload should succeed", reloaded);

            // Post-reload dangling scan: zero dangling cross-references of EITHER kind.
            int danglingRefs = 0;
            int danglingConnectionEndpoints = 0;
            int reloadedConnections = 0;
            IFolder reloadedDiagrams = reloaded.getFolder(FolderType.DIAGRAMS);
            for (Iterator<EObject> iter = reloadedDiagrams.eAllContents(); iter.hasNext(); ) {
                EObject node = iter.next();
                if (node instanceof IDiagramModelReference ref) {
                    if (ref.getReferencedModel() == null || ref.getReferencedModel().eIsProxy()) {
                        danglingRefs++;
                    }
                }
                if (node instanceof IDiagramModelConnection conn) {
                    reloadedConnections++;
                    if (isDangling(conn.getSource()) || isDangling(conn.getTarget())) {
                        danglingConnectionEndpoints++;
                    }
                }
            }

            assertEquals("No dangling IDiagramModelReference after reload", 0, danglingRefs);
            assertEquals("No dangling connection endpoints after reload", 0, danglingConnectionEndpoints);
            // The deleted view's nested connection must NOT survive the round-trip — it left
            // with its subtree, so the reloaded model contains no connections at all.
            assertEquals("Deleted view's nested connection did not survive serialization",
                    0, reloadedConnections);

            return "topLevelOnlyMissesNestedConn=true recursiveFindsNestedConn=true "
                    + "connContainer=source subtreeUnreachableAfterDelete=true "
                    + "danglingRefs=" + danglingRefs
                    + " danglingConnectionEndpoints=" + danglingConnectionEndpoints
                    + " reloadedConnections=" + reloadedConnections
                    + " reloadSucceeded=true";
        } finally {
            if (reloaded != null) {
                IEditorModelManager.INSTANCE.closeModel(reloaded);
            }
        }
    }

    // ---- helpers ----

    private static IDiagramModelArchimateObject place(IArchimateFactory factory,
            IDiagramModelGroup parent, com.archimatetool.model.IArchimateElement element, String id) {
        IDiagramModelArchimateObject dmo = factory.createDiagramModelArchimateObject();
        dmo.setId(id);
        dmo.setArchimateElement(element);
        dmo.setBounds(0, 0, 120, 55);
        parent.getChildren().add(dmo);
        return dmo;
    }

    /**
     * Replicates {@link DeleteViewCommand}'s capture: connections reachable from the
     * view's TOP-LEVEL children only (no recursion into containers).
     */
    private static Set<IDiagramModelConnection> collectTopLevelConnections(IArchimateDiagramModel view) {
        Set<IDiagramModelConnection> out = new LinkedHashSet<>();
        for (IDiagramModelObject child : view.getChildren()) {
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                out.add(conn);
            }
            for (IDiagramModelConnection conn : child.getTargetConnections()) {
                out.add(conn);
            }
        }
        return out;
    }

    /** Replicates {@code ClearViewCommand.collectConnectionsRecursive}. */
    private static void collectConnectionsRecursive(IDiagramModelObject obj,
            Set<IDiagramModelConnection> connections) {
        for (IDiagramModelConnection conn : obj.getSourceConnections()) {
            connections.add(conn);
        }
        for (IDiagramModelConnection conn : obj.getTargetConnections()) {
            connections.add(conn);
        }
        if (obj instanceof IDiagramModelContainer container) {
            for (IDiagramModelObject child : container.getChildren()) {
                collectConnectionsRecursive(child, connections);
            }
        }
    }

    /** True iff any of the given objects is still reachable from the model's DIAGRAMS folder. */
    private static boolean reachableFromDiagrams(IArchimateModel model, EObject... targets) {
        Set<EObject> wanted = new LinkedHashSet<>();
        for (EObject t : targets) {
            wanted.add(t);
        }
        for (Iterator<EObject> iter = model.getFolder(FolderType.DIAGRAMS).eAllContents();
                iter.hasNext(); ) {
            if (wanted.contains(iter.next())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDangling(IConnectable endpoint) {
        return endpoint == null || endpoint.eIsProxy();
    }
}
