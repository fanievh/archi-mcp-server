package net.vheerden.archi.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.model.RemoveFromViewCommand;

/**
 * Integration regression pin (v1.6) — remove-from-view nested-parent
 * resolution + SOUND postcondition gate.
 *
 * <p>Reproduces the on-disk failure mode that the 2026-05-28 retail-bank
 * agent-in-loop run surfaced: a nested ArchiMate-element view-object
 * (element-as-container nesting, e.g. {@code z/OS} placed under
 * {@code Mobile App Binary}) is sent to {@code remove-from-view}; the tool
 * returns {@code {action:"removed"}}; but the ghost view-object persists to the
 * saved {@code .archimate} file. On v1.5 the asymmetry inside
 * {@code prepareRemoveFromView} (element branch passed the top-level view as
 * the container, while group/note branches resolved the real parent via
 * {@code findParentContainer}) caused {@code List.remove(diagramObject)} to
 * silently no-op for nested objects. The fix brings the element branch into
 * sibling-symmetry with group/note and adds a SOUND postcondition certificate
 * inside the command. This integration test pins the post-fix behaviour
 * against the actual save+reload round-trip.</p>
 *
 * <p>Distinct from {@link DeleteViewCascadeIntegrationTest}
 * which pinned the EMF dangling-xref class: these ghosts referenced live
 * archimateElement IDs (model.xml:613/634 on the retail-bank model), so the file
 * opened cleanly in Archi but contained extra view-objects the user never
 * intended. The user-impact gate is "open the saved file and see ghosts vs not see
 * ghosts" — only the OSGi save→drop→reload round-trip exercises that gate.</p>
 *
 * <p>Requires the OSGi/PDE runtime ({@link IEditorModelManager}'s static
 * initializer touches {@code ArchiPlugin.getInstance()}). Guarded by
 * {@link Platform#isRunning()} so non-PDE launches skip cleanly.</p>
 */
public class RemoveFromViewCascadeIntegrationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Before
    public void requireOsgiRuntime() {
        assumeTrue("requires PDE/OSGi runtime", Platform.isRunning());
    }

    /** No ghost view-object survives save+reload after nested remove. */
    @Test
    public void shouldNotProduceGhostViewObjects_whenNestedElementRemovedAndSerialized() throws Exception {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;

        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();
        model.setName("Remove-From-View Cascade Integration Pin");

        IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);
        IFolder applicationFolder = model.getFolder(FolderType.APPLICATION);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("Nested Container View");
        diagramsFolder.getElements().add(view);

        IArchimateElement containerElement = factory.createApplicationComponent();
        containerElement.setName("Mobile App Binary-like container");
        applicationFolder.getElements().add(containerElement);
        IDiagramModelArchimateObject componentA = factory.createDiagramModelArchimateObject();
        componentA.setArchimateElement(containerElement);
        componentA.setBounds(50, 50, 320, 240);
        view.getChildren().add(componentA);

        IArchimateElement nestedArchimateElement = factory.createApplicationComponent();
        nestedArchimateElement.setName("Nested under container (z/OS-like)");
        applicationFolder.getElements().add(nestedArchimateElement);
        IDiagramModelArchimateObject nestedE = factory.createDiagramModelArchimateObject();
        nestedE.setArchimateElement(nestedArchimateElement);
        nestedE.setBounds(20, 30, 120, 55);
        // Mimic `add-to-view parentViewObjectId=<componentA.id>` — nested under the component,
        // not at the top level of the view. This is the element-as-container shape.
        componentA.getChildren().add(nestedE);

        String nestedId = nestedE.getId();
        String parentId = componentA.getId();
        assertNotNull("nested element id should be populated", nestedId);
        assertNotNull("parent component id should be populated", parentId);

        IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(model);
        model.setAdapter(IArchiveManager.class, archiveManager);

        File savedFile = new File(tempFolder.getRoot(), "story-b-cascade-pin.archimate");
        model.setFile(savedFile);

        // Fix exercise: remove the nested element via the post-fix command
        // (parent = the real container, not the view). On v1.5 the prepare site
        // passed `view` instead, and List.remove returned false silently.
        RemoveFromViewCommand cmd = new RemoveFromViewCommand(nestedE, componentA, List.of());
        cmd.execute();

        assertFalse("In-memory: nested element must be gone from container post-execute",
                componentA.getChildren().contains(nestedE));

        archiveManager.saveModel();
        assertTrue("Saved file should exist", savedFile.exists());

        // Drop the in-memory model so the reload comes from disk, not from a cached EObject graph.
        // Assert the close succeeded — if closeModel returned false (e.g. an unanswered ask-save
        // dialog), loadModel below could pick up the cached graph and the test would trivially pass
        // on the already-mutated in-memory model instead of validating round-trip persistence.
        assertTrue("closeModel must succeed so reload reads from disk, not from the cached EObject graph",
                IEditorModelManager.INSTANCE.closeModel(model));

        IArchimateModel reloaded = IEditorModelManager.INSTANCE.loadModel(savedFile);
        try {
            assertNotNull("Reload should succeed", reloaded);

            IFolder reloadedDiagrams = reloaded.getFolder(FolderType.DIAGRAMS);
            assertEquals("Exactly one view on the reloaded model",
                    1, reloadedDiagrams.getElements().size());

            IArchimateDiagramModel reloadedView =
                    (IArchimateDiagramModel) reloadedDiagrams.getElements().get(0);

            // Find the reloaded container by id; assert it has zero children (the nested
            // element must not have ridden the save+reload back into the parent's child list).
            IDiagramModelArchimateObject reloadedContainer = null;
            for (IDiagramModelObject child : reloadedView.getChildren()) {
                if (child instanceof IDiagramModelArchimateObject ao && parentId.equals(ao.getId())) {
                    reloadedContainer = ao;
                    break;
                }
            }
            assertNotNull("Reloaded view should still contain the parent container", reloadedContainer);
            assertEquals("Reloaded container should have zero children "
                    + "(the nested element must not survive save+reload as a ghost — "
                    + "this is the exact 1708 retail-bank View J failure mode)",
                    0, reloadedContainer.getChildren().size());

            // Defensive: also confirm the nested id doesn't appear anywhere under the view.
            boolean ghostFound = false;
            java.util.Iterator<org.eclipse.emf.ecore.EObject> iter = reloadedView.eAllContents();
            while (iter.hasNext()) {
                org.eclipse.emf.ecore.EObject node = iter.next();
                if (node instanceof IDiagramModelArchimateObject ao && nestedId.equals(ao.getId())) {
                    ghostFound = true;
                    break;
                }
            }
            assertFalse("Nested element id must not appear anywhere in the reloaded view tree",
                    ghostFound);
        } finally {
            if (reloaded != null) {
                IEditorModelManager.INSTANCE.closeModel(reloaded);
            }
        }
    }
}
