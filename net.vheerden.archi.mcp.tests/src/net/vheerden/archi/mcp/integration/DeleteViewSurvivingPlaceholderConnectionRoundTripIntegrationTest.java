package net.vheerden.archi.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.Iterator;

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
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.model.DeleteViewCommand;

/**
 * Integration proof for the delete-view cascaded-placeholder connection question.
 *
 * <p>When a view B is deleted, {@link DeleteViewCommand} cascade-removes every
 * external {@link IDiagramModelReference} placeholder that points at B — but those
 * placeholders live in <em>other, surviving</em> views, and the cascade removes each
 * one from its parent's children list <strong>without</strong> {@code disconnect()}ing
 * it. A diagram connection can be attached to such a placeholder (an
 * {@code IDiagramModelReference} is an {@code IConnectable}). The open question was
 * whether that leaves a dangling cross-reference in the surviving view after a
 * {@code .archimate} save/reload — the same failure shape that once broke
 * {@code Routing Pipeline Comparison.archimate}, but harder to dismiss than the
 * sibling nested-connection case, because the affected view <em>survives</em> the
 * delete, so the "leaves atomically" reasoning does not apply.</p>
 *
 * <p>This test builds a surviving view A holding a placeholder → B, plus two survivor
 * notes, with a plain connection <em>into</em> the placeholder (survivor → placeholder,
 * the dangerous shape: the connection is EMF-contained by its surviving source, so it
 * stays while its target placeholder is removed) and a plain connection <em>out of</em>
 * the placeholder (placeholder → survivor). It deletes B, saves, and reloads, then
 * scans the reloaded surviving view for dangling references of both kinds. A clean
 * round-trip requires {@link DeleteViewCommand} to disconnect the placeholder's
 * connections on removal.</p>
 *
 * <p>Requires the OSGi/PDE runtime ({@link IEditorModelManager}'s static initializer
 * touches {@code ArchiPlugin.getInstance()}, and {@code saveModel}/{@code loadModel}
 * need it). Guarded by {@link Platform#isRunning()} so non-PDE launches skip cleanly.</p>
 */
public class DeleteViewSurvivingPlaceholderConnectionRoundTripIntegrationTest {

    private static final String SURVIVING_VIEW_NAME = "Survivor View A";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Before
    public void requireOsgiRuntime() {
        assumeTrue("requires PDE/OSGi runtime", Platform.isRunning());
    }

    @Test
    public void shouldNotOrphanConnectionOnCascadedPlaceholder_whenReferencedViewDeletedAndSerialized()
            throws Exception {
        Measurement m = runProof(tempFolder.getRoot());

        // Fixture-shape sanity (RED-equivalent): the placeholder really is captured by
        // the external cascade and the connections really are attached to it. A fixture
        // that failed these would prove nothing.
        assertTrue("Placeholder must be an external cascade target (references B, lives in A, not in B)",
                m.placeholderIsExternalCascadeTarget);
        assertTrue("A connection must be attached INTO the placeholder (survivor -> placeholder)",
                m.connectionIntoPlaceholderWired);
        assertTrue("A connection must be attached OUT of the placeholder (placeholder -> survivor)",
                m.connectionOutOfPlaceholderWired);

        // The delete removed the placeholder from the surviving view.
        assertTrue("Placeholder removed from the surviving view on delete", m.placeholderRemovedFromSurvivingView);

        // The measured round-trip result: reload succeeds with zero dangling references
        // of either kind, and the surviving view keeps its two notes.
        assertTrue("Reload of the saved .archimate must succeed: " + m.reloadError, m.reloadSucceeded);
        assertEquals("No dangling IDiagramModelReference in the surviving view after reload",
                0, m.danglingRefs);
        assertEquals("No dangling connection endpoints in the surviving view after reload",
                0, m.danglingConnectionEndpoints);
        assertEquals("Both placeholder connections were disconnected, so none survive serialization",
                0, m.reloadedConnectionsInSurvivingView);
        assertEquals("The surviving view keeps its two survivor notes", 2, m.survivingNotesInReloadedView);
    }

    /**
     * The full round-trip, factored out so it can be driven either from this class's own
     * {@code @Test} or from a borrowed launch harness. Performs every <em>measurement</em>
     * and returns the results; it does <strong>not</strong> assert, so a caller can read
     * the raw numbers even when the current code leaves the round-trip dirty.
     */
    public static Measurement runProof(File tempDir) throws Exception {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;

        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();
        model.setName("Surviving Placeholder Connection Round-Trip Pin");

        IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);

        // The view that will be deleted.
        IArchimateDiagramModel viewB = factory.createArchimateDiagramModel();
        viewB.setId("view-b");
        viewB.setName("View B (delete target)");
        diagramsFolder.getElements().add(viewB);

        // The surviving view that holds the external placeholder pointing at B.
        IArchimateDiagramModel viewA = factory.createArchimateDiagramModel();
        viewA.setId("view-a");
        viewA.setName(SURVIVING_VIEW_NAME);
        diagramsFolder.getElements().add(viewA);

        IDiagramModelReference placeholder = factory.createDiagramModelReference();
        placeholder.setId("placeholder-to-b");
        placeholder.setReferencedModel(viewB);
        placeholder.setBounds(200, 0, 185, 80);
        viewA.getChildren().add(placeholder);

        // Two survivor notes in view A (IConnectable, NOT references to B, so not cascaded).
        IDiagramModelNote noteSource = factory.createDiagramModelNote();
        noteSource.setId("note-source");
        noteSource.setBounds(0, 0, 120, 55);
        viewA.getChildren().add(noteSource);

        IDiagramModelNote noteTarget = factory.createDiagramModelNote();
        noteTarget.setId("note-target");
        noteTarget.setBounds(0, 200, 120, 55);
        viewA.getChildren().add(noteTarget);

        // Connection INTO the placeholder: survivor note -> placeholder. This is the
        // dangerous shape — the connection is EMF-contained by its (surviving) source, so
        // it stays in view A while its target placeholder is removed, leaving a dangling
        // target IDREF.
        IDiagramModelConnection connIntoPlaceholder = factory.createDiagramModelConnection();
        connIntoPlaceholder.setId("conn-into-placeholder");
        connIntoPlaceholder.connect(noteSource, placeholder);

        // Connection OUT of the placeholder: placeholder -> survivor note. Contained by the
        // placeholder, so it leaves with it; included to prove the fix disconnects both
        // directions (mirrors DeleteElementCommand, which disconnects source+target).
        IDiagramModelConnection connOutOfPlaceholder = factory.createDiagramModelConnection();
        connOutOfPlaceholder.setId("conn-out-of-placeholder");
        connOutOfPlaceholder.connect(placeholder, noteTarget);

        // ---- Fixture-shape sanity, measured before delete ----
        boolean placeholderIsExternalCascadeTarget =
                placeholder.getReferencedModel() == viewB
                && !isContainedIn(placeholder, viewB)
                && placeholder.eContainer() == viewA;
        boolean connectionIntoPlaceholderWired =
                placeholder.getTargetConnections().contains(connIntoPlaceholder);
        boolean connectionOutOfPlaceholderWired =
                placeholder.getSourceConnections().contains(connOutOfPlaceholder);

        // Containment, verified before delete: a connection is contained by its SOURCE.
        assertSame("Connection into the placeholder is contained by its surviving source note",
                noteSource, connIntoPlaceholder.eContainer());
        assertSame("Connection out of the placeholder is contained by the placeholder",
                placeholder, connOutOfPlaceholder.eContainer());

        // Persist, delete, save, reload.
        IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(model);
        model.setAdapter(IArchiveManager.class, archiveManager);

        File savedFile = new File(tempDir, "surviving-placeholder-conn-pin.archimate");
        model.setFile(savedFile);

        int viewBIndex = diagramsFolder.getElements().indexOf(viewB);
        DeleteViewCommand cmd = new DeleteViewCommand(viewB, diagramsFolder, viewBIndex);
        cmd.execute();

        boolean placeholderRemovedFromSurvivingView = !viewA.getChildren().contains(placeholder);

        archiveManager.saveModel();

        int danglingRefs = 0;
        int danglingConnectionEndpoints = 0;
        int reloadedConnectionsInSurvivingView = 0;
        int survivingNotesInReloadedView = 0;
        boolean reloadSucceeded = false;
        String reloadError = "none";
        IArchimateModel reloaded = null;
        try {
            reloaded = IEditorModelManager.INSTANCE.loadModel(savedFile);
            reloadSucceeded = reloaded != null;
            assertNotNull("Reload returned null", reloaded);

            IDiagramModel reloadedViewA = findViewByName(reloaded, SURVIVING_VIEW_NAME);
            assertNotNull("Surviving view A must exist after reload", reloadedViewA);

            for (Iterator<EObject> iter = reloadedViewA.eAllContents(); iter.hasNext(); ) {
                EObject node = iter.next();
                if (node instanceof IDiagramModelNote) {
                    survivingNotesInReloadedView++;
                }
                if (node instanceof IDiagramModelReference ref
                        && (ref.getReferencedModel() == null || ref.getReferencedModel().eIsProxy())) {
                    danglingRefs++;
                }
                if (node instanceof IDiagramModelConnection conn) {
                    reloadedConnectionsInSurvivingView++;
                    if (isDangling(conn.getSource()) || isDangling(conn.getTarget())) {
                        danglingConnectionEndpoints++;
                    }
                }
            }
        } catch (Exception | AssertionError e) {
            // A dangling IDREF can surface as a load-time failure ("Unresolved reference …").
            // Record it as the dirty outcome rather than masking the measurement.
            reloadError = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (reloaded != null) {
                IEditorModelManager.INSTANCE.closeModel(reloaded);
            }
        }

        return new Measurement(placeholderIsExternalCascadeTarget, connectionIntoPlaceholderWired,
                connectionOutOfPlaceholderWired, placeholderRemovedFromSurvivingView, reloadSucceeded,
                danglingRefs, danglingConnectionEndpoints, reloadedConnectionsInSurvivingView,
                survivingNotesInReloadedView, reloadError);
    }

    /** Measured outcome of {@link #runProof(File)} — a value object so callers can read raw numbers. */
    public record Measurement(
            boolean placeholderIsExternalCascadeTarget,
            boolean connectionIntoPlaceholderWired,
            boolean connectionOutOfPlaceholderWired,
            boolean placeholderRemovedFromSurvivingView,
            boolean reloadSucceeded,
            int danglingRefs,
            int danglingConnectionEndpoints,
            int reloadedConnectionsInSurvivingView,
            int survivingNotesInReloadedView,
            String reloadError) {

        public String summary() {
            return "placeholderIsExternalCascadeTarget=" + placeholderIsExternalCascadeTarget
                    + " connectionIntoPlaceholderWired=" + connectionIntoPlaceholderWired
                    + " connectionOutOfPlaceholderWired=" + connectionOutOfPlaceholderWired
                    + " placeholderRemovedFromSurvivingView=" + placeholderRemovedFromSurvivingView
                    + " reloadSucceeded=" + reloadSucceeded
                    + " danglingRefs=" + danglingRefs
                    + " danglingConnectionEndpoints=" + danglingConnectionEndpoints
                    + " reloadedConnectionsInSurvivingView=" + reloadedConnectionsInSurvivingView
                    + " survivingNotesInReloadedView=" + survivingNotesInReloadedView
                    + " reloadError=[" + reloadError + "]";
        }
    }

    // ---- helpers ----

    private static IDiagramModel findViewByName(IArchimateModel model, String name) {
        for (Object o : model.getFolder(FolderType.DIAGRAMS).getElements()) {
            if (o instanceof IDiagramModel view && name.equals(view.getName())) {
                return view;
            }
        }
        return null;
    }

    /** True iff {@code node}'s containment chain reaches {@code ancestor}. */
    private static boolean isContainedIn(EObject node, EObject ancestor) {
        for (EObject cur = node.eContainer(); cur != null; cur = cur.eContainer()) {
            if (cur == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDangling(IConnectable endpoint) {
        return endpoint == null || endpoint.eIsProxy();
    }
}
