package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;

/**
 * Pins the cascade counts reported by {@code prepareDeleteView}.
 *
 * <p>Drives the REAL accessor against a real EMF model, asserting on
 * {@code PreparedMutation.entity()} directly — no dispatch, no OSGi, no JSON.
 * Handler-level tests stub the accessor and therefore can only prove that a DTO
 * field survives serialization; they stay green whether or not the value is
 * measured. This class is the one that proves measurement.</p>
 *
 * <p>Pure standard JUnit (no OSGi / Plug-in Test).</p>
 */
public class DeleteViewCascadeCountTest {

    private static final String SESSION = "batch-count-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher testDispatcher;
    private IArchimateModel model;
    private IFolder diagrams;
    private IArchimateDiagramModel target;
    private IArchimateDiagramModel other;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Delete-View Count Fixture");
        model.setId("model-delete-view-counts");
        model.setDefaults();
        diagrams = model.getFolder(FolderType.DIAGRAMS);

        target = factory.createArchimateDiagramModel();
        target.setId("view-target");
        target.setName("Target View");
        diagrams.getElements().add(target);

        other = factory.createArchimateDiagramModel();
        other.setId("view-other");
        other.setName("Other View");
        diagrams.getElements().add(other);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers ----

    private IBusinessActor addActor(String id) {
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId(id);
        actor.setName(id);
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        return actor;
    }

    private IAssociationRelationship addAssociation(String id,
            com.archimatetool.model.IArchimateConcept source,
            com.archimatetool.model.IArchimateConcept target) {
        IAssociationRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    private IDiagramModelArchimateObject place(
            com.archimatetool.model.IDiagramModelContainer parent,
            com.archimatetool.model.IArchimateElement element, String id) {
        IDiagramModelArchimateObject dmo = factory.createDiagramModelArchimateObject();
        dmo.setId(id);
        dmo.setArchimateElement(element);
        dmo.setBounds(0, 0, 120, 55);
        parent.getChildren().add(dmo);
        return dmo;
    }

    private IDiagramModelArchimateConnection connect(IArchimateRelationship rel,
            IDiagramModelArchimateObject src, IDiagramModelArchimateObject tgt,
            String id) {
        IDiagramModelArchimateConnection conn =
                factory.createDiagramModelArchimateConnection();
        conn.setId(id);
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
        return conn;
    }

    private IDiagramModelGroup addGroup(
            com.archimatetool.model.IDiagramModelContainer parent, String id) {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId(id);
        group.setName(id);
        group.setBounds(0, 0, 400, 300);
        parent.getChildren().add(group);
        return group;
    }

    private IDiagramModelReference newRefTo(IArchimateDiagramModel referenced) {
        IDiagramModelReference ref = factory.createDiagramModelReference();
        ref.setReferencedModel(referenced);
        ref.setBounds(0, 0, 185, 80);
        return ref;
    }

    private IArchimateDiagramModel createView(String id, String name) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId(id);
        view.setName(name);
        diagrams.getElements().add(view);
        return view;
    }

    /**
     * Builds five distinct connections in {@code target}, deliberately including
     * one whose BOTH endpoints are nested inside a group. {@code DeleteViewCommand}
     * only captures connections touching top-level children, so a count taken from
     * the command's own captured list would miss that one — the count must come
     * from a recursive walk.
     */
    private void buildFiveConnections() {
        IBusinessActor a1 = addActor("actor-1");
        IBusinessActor a2 = addActor("actor-2");
        IBusinessActor a3 = addActor("actor-3");
        IBusinessActor a4 = addActor("actor-4");

        IDiagramModelArchimateObject top1 = place(target, a1, "dmo-top-1");
        IDiagramModelArchimateObject top2 = place(target, a2, "dmo-top-2");
        IDiagramModelGroup group = addGroup(target, "group-1");
        IDiagramModelArchimateObject nested1 = place(group, a3, "dmo-nested-1");
        IDiagramModelArchimateObject nested2 = place(group, a4, "dmo-nested-2");

        connect(addAssociation("rel-1", a1, a2), top1, top2, "conn-top-to-top");
        // The load-bearing case: nested -> nested, invisible to a top-level-only walk.
        connect(addAssociation("rel-2", a3, a4), nested1, nested2, "conn-nested-to-nested");
        connect(addAssociation("rel-3", a1, a3), top1, nested1, "conn-top-to-nested");
        connect(addAssociation("rel-4", a2, a4), top2, nested2, "conn-top-to-nested-2");
        connect(addAssociation("rel-5", a3, a1), nested1, top1, "conn-nested-to-top");
    }

    /**
     * Adds three external placeholders pointing at {@code target} — one top-level,
     * one nested inside a group, one in a view filed under a user subfolder — plus
     * a self-reference INSIDE target that must not be counted.
     */
    private void buildThreeExternalReferences() {
        other.getChildren().add(newRefTo(target));

        IDiagramModelGroup groupInOther = addGroup(other, "group-in-other");
        groupInOther.getChildren().add(newRefTo(target));

        IFolder userFolder = factory.createFolder();
        userFolder.setName("User subfolder");
        diagrams.getFolders().add(userFolder);
        IArchimateDiagramModel filedView = factory.createArchimateDiagramModel();
        filedView.setId("view-filed");
        filedView.setName("Filed View");
        userFolder.getElements().add(filedView);
        filedView.getChildren().add(newRefTo(target));

        // Self-reference: dies with the view, is NOT a cascade removal.
        target.getChildren().add(newRefTo(target));
    }

    // ---- the two measured counts ----

    @Test
    public void shouldCountAllConnectionsRecursively_whenPreparingDeleteView() {
        buildFiveConnections();

        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteView(target.getId());

        assertEquals("All connections in the view are counted, including one whose "
                        + "both endpoints are nested inside a group",
                5, prepared.entity().viewConnectionsRemoved());
    }

    @Test
    public void shouldCountExternalPlaceholders_excludingSelfReference() {
        buildThreeExternalReferences();

        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteView(target.getId());

        assertEquals("Top-level, group-nested and user-subfolder placeholders count; "
                        + "the self-reference inside the deleted view does not",
                3, prepared.entity().viewReferencesRemoved());
    }

    @Test
    public void shouldReportBothCountsIndependently_whenViewHasConnectionsAndReferences() {
        buildFiveConnections();
        buildThreeExternalReferences();

        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteView(target.getId());

        // Distinct expected values: a transposed-argument bug cannot pass both.
        assertEquals(5, prepared.entity().viewConnectionsRemoved());
        assertEquals(3, prepared.entity().viewReferencesRemoved());
    }

    @Test
    public void shouldReportZeroCounts_whenViewIsEmptyAndUnreferenced() {
        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteView(target.getId());

        assertEquals(0, prepared.entity().viewConnectionsRemoved());
        assertEquals(0, prepared.entity().viewReferencesRemoved());
    }

    /**
     * The reported placeholder count must equal what the command actually removes.
     * This is the anti-drift pin for the shared-scan requirement: if the count is
     * ever computed by a second, independently-written scan, this test is what
     * catches the divergence.
     */
    @Test
    public void shouldMatchWhatTheCommandActuallyRemoves() {
        buildThreeExternalReferences();
        int reported = accessor.prepareDeleteView(target.getId())
                .entity().viewReferencesRemoved();

        int before = countExternalReferencesTo(target);
        Command cmd = accessor.prepareDeleteView(target.getId()).command();
        cmd.execute();
        int after = countExternalReferencesTo(target);

        assertEquals("Reported count equals the number of placeholders the command removed",
                reported, before - after);
    }

    /**
     * Counts live placeholders pointing at {@code view} from OUTSIDE it, anywhere
     * under DIAGRAMS. Self-references inside the view are excluded: they vanish
     * with the view's own subtree rather than being cascade-removed, so counting
     * them would make this assertion measure something the field does not claim.
     */
    private int countExternalReferencesTo(IArchimateDiagramModel view) {
        int count = 0;
        for (java.util.Iterator<org.eclipse.emf.ecore.EObject> it =
                diagrams.eAllContents(); it.hasNext(); ) {
            org.eclipse.emf.ecore.EObject node = it.next();
            if (node instanceof IDiagramModelReference ref
                    && ref.getReferencedModel() == view
                    && !isInside(ref, view)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isInside(org.eclipse.emf.ecore.EObject node,
            org.eclipse.emf.ecore.EObject ancestor) {
        for (org.eclipse.emf.ecore.EObject cur = node.eContainer();
                cur != null; cur = cur.eContainer()) {
            if (cur == ancestor) {
                return true;
            }
        }
        return false;
    }

    // ---- the four fields that are correct as-is ----

    /**
     * These four values are CORRECT, not unfinished. Deleting a view removes no
     * model concepts and no folders, so there is nothing for them to report. Do
     * not "fix" them into measurements — {@code elementsRemoved} in particular
     * means MODEL elements on delete-folder, so repurposing it here to mean
     * visual objects would give one field two meanings across two tools.
     */
    @Test
    public void shouldLeaveModelConceptCountsAtZeroOrAbsent() {
        buildFiveConnections();
        buildThreeExternalReferences();

        DeleteResultDto dto = accessor.prepareDeleteView(target.getId()).entity();

        assertEquals("View deletion deletes no model relationships",
                0, dto.relationshipsRemoved());
        assertNull("View deletion deletes no model elements", dto.elementsRemoved());
        assertNull("The deleted view is identified by id/name, not counted here",
                dto.viewsRemoved());
        assertNull("View deletion deletes no folders", dto.foldersRemoved());
    }

    @Test
    public void shouldReportIdentityFields_unchanged() {
        DeleteResultDto dto = accessor.prepareDeleteView(target.getId()).entity();

        assertEquals("view-target", dto.id());
        assertEquals("Target View", dto.name());
        assertEquals("the eClass its three sibling delete prepares already report, not the coarse "
                + "noun this one alone used to ship", "ArchimateDiagramModel", dto.type());
    }

    // ---- delete-folder aggregates a contained view's own cascade counts ----

    /**
     * {@code buildFolderDeleteSubCommands} folds a directly-contained view's own
     * external placeholders and connections into the folder's cascade counts, the
     * same way it already folds the counts of cascaded elements and relationships.
     * The counts report how many objects the operation causes to cease to exist;
     * the sum is order-invariant because every prepare completes before any execute,
     * and a counted external placeholder always disappears — scrubbed by its target
     * view's own cascade, or removed with the sibling view that held it (also being
     * deleted). The two cross-reference probes below hold that invariant against the
     * order-dependence hazard that once justified NOT aggregating.
     */
    @Test
    public void shouldAggregateContainedViewCounts_whenFolderForceDeletesAConnectedReferencedView() {
        IFolder sub = factory.createFolder();
        sub.setName("Sub");
        sub.setId("folder-sub");
        diagrams.getFolders().add(sub);

        IArchimateDiagramModel filed = factory.createArchimateDiagramModel();
        filed.setId("view-in-folder");
        filed.setName("View In Folder");
        sub.getElements().add(filed);

        IBusinessActor a1 = addActor("f-actor-1");
        IBusinessActor a2 = addActor("f-actor-2");
        IDiagramModelArchimateObject o1 = place(filed, a1, "f-dmo-1");
        IDiagramModelArchimateObject o2 = place(filed, a2, "f-dmo-2");
        connect(addAssociation("f-rel-1", a1, a2), o1, o2, "f-conn-1");

        // Referenced from a view OUTSIDE the folder being deleted.
        other.getChildren().add(newRefTo(filed));

        DeleteResultDto dto = accessor.prepareDeleteFolder(sub.getId(), true).entity();

        assertEquals("One view removed", Integer.valueOf(1), dto.viewsRemoved());
        assertEquals("No model elements live in this folder",
                Integer.valueOf(0), dto.elementsRemoved());
        assertEquals("No subfolders", Integer.valueOf(0), dto.foldersRemoved());
        assertEquals("Folder delete does not aggregate a view's model-relationship count",
                0, dto.relationshipsRemoved());
        assertEquals("The external placeholder pointing at the contained view is counted",
                1, dto.viewReferencesRemoved());
        assertEquals("The contained view's own connection is counted",
                1, dto.viewConnectionsRemoved());
    }

    /**
     * The aggregated connection count comes from a recursive walk, not the command's
     * own top-level capture: a connection whose endpoints are both nested inside a
     * group must still be counted. A top-level-only count would report 0 here.
     */
    @Test
    public void shouldCountNestedConnectionsRecursively_whenFolderForceDeletesAView() {
        IFolder sub = factory.createFolder();
        sub.setName("Sub");
        sub.setId("folder-grouped");
        diagrams.getFolders().add(sub);

        IArchimateDiagramModel filed = factory.createArchimateDiagramModel();
        filed.setId("view-grouped");
        filed.setName("Grouped View");
        sub.getElements().add(filed);

        IBusinessActor a1 = addActor("g-actor-1");
        IBusinessActor a2 = addActor("g-actor-2");
        IDiagramModelGroup group = addGroup(filed, "g-group");
        IDiagramModelArchimateObject n1 = place(group, a1, "g-nested-1");
        IDiagramModelArchimateObject n2 = place(group, a2, "g-nested-2");
        // Both endpoints nested inside the group — invisible to a top-level-only walk.
        connect(addAssociation("g-rel-1", a1, a2), n1, n2, "g-conn-nested");

        DeleteResultDto dto = accessor.prepareDeleteFolder(sub.getId(), true).entity();

        assertEquals("A group-nested connection is counted via the recursive walk",
                1, dto.viewConnectionsRemoved());
    }

    /**
     * Over-report probe. Two sibling views inside the deleted folder cross-reference
     * each other. The prepare-time aggregate must equal the number of external
     * placeholders that actually cease to exist — no over-count — whatever order the
     * sub-commands execute in.
     */
    @Test
    public void shouldNotOverReport_whenSiblingViewsInsideFolderCrossReference() {
        IFolder sub = factory.createFolder();
        sub.setName("Sub");
        sub.setId("folder-cross");
        diagrams.getFolders().add(sub);

        IArchimateDiagramModel viewX = factory.createArchimateDiagramModel();
        viewX.setId("view-x");
        viewX.setName("View X");
        sub.getElements().add(viewX);

        IArchimateDiagramModel viewY = factory.createArchimateDiagramModel();
        viewY.setId("view-y");
        viewY.setName("View Y");
        sub.getElements().add(viewY);

        // Bidirectional: X holds a placeholder to Y, Y holds a placeholder to X.
        viewX.getChildren().add(newRefTo(viewY));
        viewY.getChildren().add(newRefTo(viewX));

        int externalBefore =
                countExternalReferencesTo(viewX) + countExternalReferencesTo(viewY);

        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteFolder(sub.getId(), true);
        int reported = prepared.entity().viewReferencesRemoved();
        prepared.command().execute();
        int externalAfter =
                countExternalReferencesTo(viewX) + countExternalReferencesTo(viewY);

        assertEquals("Both cross-references are counted", 2, reported);
        assertEquals("Reported equals the external placeholders that ceased to exist",
                reported, externalBefore - externalAfter);
    }

    /**
     * The backlog's "reported 1 / actual 0" shape: view A holds a placeholder to B,
     * B holds none. A's placeholder disappears with A rather than through B's cascade
     * step — but it still ceases to exist, so the aggregate is 1 and matches the
     * actual removal count. This is the falsification of the order-dependence hazard.
     */
    @Test
    public void shouldAggregateOneDirectionalIntraFolderReference_matchingActualRemovals() {
        IFolder sub = factory.createFolder();
        sub.setName("Sub");
        sub.setId("folder-onedir");
        diagrams.getFolders().add(sub);

        IArchimateDiagramModel viewA = factory.createArchimateDiagramModel();
        viewA.setId("view-a");
        viewA.setName("View A");
        sub.getElements().add(viewA);

        IArchimateDiagramModel viewB = factory.createArchimateDiagramModel();
        viewB.setId("view-b");
        viewB.setName("View B");
        sub.getElements().add(viewB);

        viewA.getChildren().add(newRefTo(viewB));

        int externalBefore =
                countExternalReferencesTo(viewA) + countExternalReferencesTo(viewB);

        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteFolder(sub.getId(), true);
        int reported = prepared.entity().viewReferencesRemoved();
        prepared.command().execute();
        int externalAfter =
                countExternalReferencesTo(viewA) + countExternalReferencesTo(viewB);

        assertEquals("The one cross-reference is counted", 1, reported);
        assertEquals("Reported equals the external placeholders that ceased to exist",
                reported, externalBefore - externalAfter);
    }

    // ---- cross-arm counting: element/view arms are layer-exclusive, so no double count ----

    /**
     * Guard against a cross-arm over-count. A view's own connection is tallied by the
     * view arm via {@code collectAllConnections}; a cascaded element's connections are
     * tallied by the element arm via {@code discoverCascadeTargets}, a model-wide scan
     * that also finds connections living inside views. Those two tallies would
     * double-count the SAME connection — but only if an element and a view shared one
     * force-deleted subtree, which cannot happen: a force-delete targets a single
     * non-default root folder, and Archi bars a model concept from a Views-rooted
     * folder ({@link #shouldRejectFilingAnElement_inAViewsRootedFolder}). Here the
     * diagrammed elements live in their Business folder, OUTSIDE the deleted Views
     * folder, so only the view arm fires and the connection is counted exactly once.
     */
    @Test
    public void shouldCountAConnectionOnce_whenItsElementsLiveOutsideTheDeletedViewsFolder() {
        IFolder sub = factory.createFolder();
        sub.setName("Sub");
        sub.setId("folder-single-count");
        diagrams.getFolders().add(sub);

        IArchimateDiagramModel filed = factory.createArchimateDiagramModel();
        filed.setId("view-single-count");
        filed.setName("Diagram");
        sub.getElements().add(filed);

        // Elements + relationship live in their own layer folders, NOT the deleted folder.
        IBusinessActor a1 = addActor("sc-actor-1");
        IBusinessActor a2 = addActor("sc-actor-2");
        IDiagramModelArchimateObject o1 = place(filed, a1, "sc-dmo-1");
        IDiagramModelArchimateObject o2 = place(filed, a2, "sc-dmo-2");
        connect(addAssociation("sc-rel-1", a1, a2), o1, o2, "sc-conn-1");

        DeleteResultDto dto = accessor.prepareDeleteFolder(sub.getId(), true).entity();

        assertEquals("The view's connection is counted exactly once (only the view arm fires)",
                1, dto.viewConnectionsRemoved());
        assertEquals("No model relationship is deleted — its elements live outside the folder",
                0, dto.relationshipsRemoved());
    }

    /**
     * The invariant that makes the cross-arm double-count unreachable: Archi bars a
     * model concept from a folder whose root layer does not match, delegating to
     * Archi's own default-folder authority. So an element can never share a delete
     * subtree with a view.
     */
    @Test
    public void shouldRejectFilingAnElement_inAViewsRootedFolder() {
        IBusinessActor actor = addActor("lm-actor");
        IFolder viewsSub = factory.createFolder();
        viewsSub.setType(FolderType.DIAGRAMS);
        viewsSub.setId("folder-views-sub");
        viewsSub.setName("Views Sub");
        diagrams.getFolders().add(viewsSub);

        try {
            accessor.validateFolderLayerMatch(actor, viewsSub);
            org.junit.Assert.fail("Expected FOLDER_LAYER_MISMATCH — an element must not "
                    + "be accepted into a Views-rooted folder");
        } catch (ModelAccessException e) {
            assertEquals("FOLDER_LAYER_MISMATCH", e.getErrorCode().name());
        }
    }

    // ---- batch preview: per-op counts vs what the commit actually removes ----
    //
    // In a batch every delete is PREPARED (its preview count computed) when the tool
    // is called, and QUEUED; nothing executes until commit builds one compound and runs
    // the queued commands in order. So all previews are taken against the intact model,
    // exactly like the folder-cascade case above — the difference is only that each view's
    // preview is surfaced to the caller per operation rather than summed into one number.
    // These tests drive the REAL beginBatch/deleteView/endBatch path; the test dispatcher's
    // commit executes the queued commands in queue order (it steps through the compound's
    // children, so commit ORDER is preserved and the headless-only INSTANCE deref on the
    // production compound's own execute() is bypassed).

    /**
     * The batch queues without executing until commit. This is what makes every preview a
     * snapshot of the intact model: after both deletes are queued the views are still present
     * and the cross-view placeholder still points live, and the preview already reports the
     * placeholder it will remove. If a prepare ever ran against a sibling-mutated model this
     * assertion would fail — the guard that the folder-cascade order-invariance argument also
     * holds here, where the prepares are separate tool calls rather than one folder walk.
     */
    @Test
    public void shouldQueueWithoutExecuting_untilBatchCommits() throws Exception {
        IArchimateDiagramModel holder = createView("batch-hold", "Holder");
        IArchimateDiagramModel referenced = createView("batch-ref", "Referenced");
        holder.getChildren().add(newRefTo(referenced));

        testDispatcher.beginBatch(SESSION, "queue two deletes");
        accessor.deleteView(SESSION, holder.getId());
        DeleteResultDto previewReferenced = accessor.deleteView(SESSION, referenced.getId()).entity();

        // Nothing has executed yet: model still intact, so the prepare that produced the
        // preview above saw the placeholder live.
        assertTrue("Queued, not executed: the holder view is still in the folder",
                diagrams.getElements().contains(holder));
        assertTrue("Queued, not executed: the referenced view is still in the folder",
                diagrams.getElements().contains(referenced));
        assertEquals("The placeholder is still live before commit",
                1, countExternalReferencesTo(referenced));
        assertEquals("The referenced view's preview counts the placeholder pointing at it",
                1, previewReferenced.viewReferencesRemoved());

        testDispatcher.endBatch(SESSION, true);

        assertFalse("After commit the holder view is gone", diagrams.getElements().contains(holder));
        assertFalse("After commit the referenced view is gone",
                diagrams.getElements().contains(referenced));
        assertEquals("After commit the placeholder has ceased to exist",
                0, countExternalReferencesTo(referenced));
    }

    /**
     * Bidirectional cross-reference, both views deleted in one batch. The batch-wide union of
     * the per-op previews equals the number of external placeholders that cease to exist — no
     * over-count. Order-invariant: both placeholders always disappear (each is scrubbed by its
     * target view's cascade or removed with the sibling view that held it).
     */
    @Test
    public void shouldNotOverReportBatchUnion_whenSiblingViewsCrossReference() throws Exception {
        IArchimateDiagramModel viewX = createView("batch-x", "View X");
        IArchimateDiagramModel viewY = createView("batch-y", "View Y");
        viewX.getChildren().add(newRefTo(viewY));
        viewY.getChildren().add(newRefTo(viewX));

        int externalBefore = countExternalReferencesTo(viewX) + countExternalReferencesTo(viewY);

        testDispatcher.beginBatch(SESSION, "delete both cross-referencing views");
        int previewX = accessor.deleteView(SESSION, viewX.getId()).entity().viewReferencesRemoved();
        int previewY = accessor.deleteView(SESSION, viewY.getId()).entity().viewReferencesRemoved();
        testDispatcher.endBatch(SESSION, true);

        int externalAfter = countExternalReferencesTo(viewX) + countExternalReferencesTo(viewY);

        assertEquals("Each view's preview counts the one placeholder pointing at it", 1, previewX);
        assertEquals("Each view's preview counts the one placeholder pointing at it", 1, previewY);
        assertEquals("The batch-wide union equals the placeholders that ceased to exist",
                previewX + previewY, externalBefore - externalAfter);
        assertEquals("Both placeholders ceased to exist", 2, externalBefore - externalAfter);
    }

    /**
     * One-directional, holder committed first (queue order [holder, referenced]). This is the
     * exact shape once alleged as an over-report: the referenced view's preview reports 1, but at commit the holder's
     * own {@code getChildren().clear()} removes the placeholder before the referenced view's
     * cascade step runs. The batch union is still faithful — the placeholder ceases to exist
     * once — and the holder's preview is 0, so union == actual.
     */
    @Test
    public void shouldMatchActualRemovals_whenHolderCommitsFirst() throws Exception {
        IArchimateDiagramModel holder = createView("batch-h1", "Holder");
        IArchimateDiagramModel referenced = createView("batch-r1", "Referenced");
        holder.getChildren().add(newRefTo(referenced));

        int externalBefore = countExternalReferencesTo(holder) + countExternalReferencesTo(referenced);

        testDispatcher.beginBatch(SESSION, "holder first");
        int previewHolder = accessor.deleteView(SESSION, holder.getId()).entity().viewReferencesRemoved();
        int previewReferenced =
                accessor.deleteView(SESSION, referenced.getId()).entity().viewReferencesRemoved();
        testDispatcher.endBatch(SESSION, true);

        int externalAfter = countExternalReferencesTo(holder) + countExternalReferencesTo(referenced);

        assertEquals("Nothing points at the holder", 0, previewHolder);
        assertEquals("The referenced view's preview counts the placeholder pointing at it",
                1, previewReferenced);
        assertEquals("Union equals the placeholders that ceased to exist",
                previewHolder + previewReferenced, externalBefore - externalAfter);
        assertEquals("The single placeholder ceased to exist", 1, externalBefore - externalAfter);
    }

    /**
     * The same one-directional fixture with the queue reversed [referenced, holder]. Here the
     * referenced view's cascade scrubs the placeholder from the still-present holder, so the
     * removal matches the referenced view's own step — yet the batch union is identical to the
     * holder-first order. Together with the previous test this pins order-invariance of the
     * union.
     */
    @Test
    public void shouldMatchActualRemovals_whenReferencedCommitsFirst() throws Exception {
        IArchimateDiagramModel holder = createView("batch-h2", "Holder");
        IArchimateDiagramModel referenced = createView("batch-r2", "Referenced");
        holder.getChildren().add(newRefTo(referenced));

        int externalBefore = countExternalReferencesTo(holder) + countExternalReferencesTo(referenced);

        testDispatcher.beginBatch(SESSION, "referenced first");
        int previewReferenced =
                accessor.deleteView(SESSION, referenced.getId()).entity().viewReferencesRemoved();
        int previewHolder = accessor.deleteView(SESSION, holder.getId()).entity().viewReferencesRemoved();
        testDispatcher.endBatch(SESSION, true);

        int externalAfter = countExternalReferencesTo(holder) + countExternalReferencesTo(referenced);

        assertEquals("Nothing points at the holder", 0, previewHolder);
        assertEquals("The referenced view's preview counts the placeholder pointing at it",
                1, previewReferenced);
        assertEquals("Union equals the placeholders that ceased to exist, same as holder-first",
                previewReferenced + previewHolder, externalBefore - externalAfter);
        assertEquals("The single placeholder ceased to exist", 1, externalBefore - externalAfter);
    }

    /**
     * The mechanism the union hides. Executing the two prepared commands in each order — exactly
     * what commit does with the queued compound — shows the per-op ATTRIBUTION shift once alleged
     * as an over-report: in holder-first order the referenced view's preview is 1 while the referenced
     * command's own execute removes 0 (the holder's clear got there first); in referenced-first
     * order the same preview of 1 matches its own removal of 1. The per-op number is a count of
     * placeholders pointing at the view that will be removed by the batch, not a count of this
     * command's own removals — and the total is faithful either way.
     */
    @Test
    public void shouldShiftPerOpAttribution_butKeepUnionFaithful_acrossCommitOrder() {
        // Holder-first: [holder, referenced]
        IArchimateDiagramModel holderA = createView("attr-h1", "Holder A");
        IArchimateDiagramModel refA = createView("attr-r1", "Ref A");
        holderA.getChildren().add(newRefTo(refA));

        PreparedMutation<DeleteResultDto> holderPrepared = accessor.prepareDeleteView(holderA.getId());
        PreparedMutation<DeleteResultDto> refPrepared = accessor.prepareDeleteView(refA.getId());
        int previewHolder = holderPrepared.entity().viewReferencesRemoved();
        int previewRef = refPrepared.entity().viewReferencesRemoved();

        int before = countExternalReferencesTo(refA);
        holderPrepared.command().execute();
        int removedByHolder = before - countExternalReferencesTo(refA);
        int afterHolder = countExternalReferencesTo(refA);
        refPrepared.command().execute();
        int removedByRef = afterHolder - countExternalReferencesTo(refA);

        assertEquals("Holder preview counts nothing pointing at it", 0, previewHolder);
        assertEquals("Referenced preview counts the placeholder pointing at it", 1, previewRef);
        assertEquals("The holder's own clear removed the placeholder", 1, removedByHolder);
        assertEquals("The referenced command's own execute removed nothing — the holder got there first",
                0, removedByRef);
        assertEquals("Union of previews equals total removed", previewHolder + previewRef,
                removedByHolder + removedByRef);

        // Referenced-first: [referenced, holder] — attribution now matches the referenced preview.
        IArchimateDiagramModel holderB = createView("attr-h2", "Holder B");
        IArchimateDiagramModel refB = createView("attr-r2", "Ref B");
        holderB.getChildren().add(newRefTo(refB));

        PreparedMutation<DeleteResultDto> refPrepared2 = accessor.prepareDeleteView(refB.getId());
        PreparedMutation<DeleteResultDto> holderPrepared2 = accessor.prepareDeleteView(holderB.getId());
        int previewRef2 = refPrepared2.entity().viewReferencesRemoved();

        int before2 = countExternalReferencesTo(refB);
        refPrepared2.command().execute();
        int removedByRef2 = before2 - countExternalReferencesTo(refB);
        holderPrepared2.command().execute();

        assertEquals("Referenced preview counts the placeholder pointing at it", 1, previewRef2);
        assertEquals("Referenced-first, the referenced command's own execute removes the placeholder",
                1, removedByRef2);
    }

    /**
     * {@code viewConnectionsRemoved} has no cross-op hazard: each connection belongs to exactly
     * one view and is removed with it, so a batch total is a plain independent sum with no
     * re-attribution. Distinct per-view values (2 vs 1) guard against a transposed count, and
     * the second view's single connection is nested inside a group so a top-level-only walk
     * would report 0 — the value proves the recursive path ran.
     */
    @Test
    public void shouldNotCrossAttributeConnectionCounts_inBatch() throws Exception {
        IArchimateDiagramModel viewA = createView("batch-conn-a", "Batch Conn A");
        IBusinessActor a1 = addActor("bca-1");
        IBusinessActor a2 = addActor("bca-2");
        IBusinessActor a3 = addActor("bca-3");
        IDiagramModelArchimateObject o1 = place(viewA, a1, "bca-dmo-1");
        IDiagramModelArchimateObject o2 = place(viewA, a2, "bca-dmo-2");
        IDiagramModelArchimateObject o3 = place(viewA, a3, "bca-dmo-3");
        connect(addAssociation("bca-rel-1", a1, a2), o1, o2, "bca-conn-1");
        connect(addAssociation("bca-rel-2", a2, a3), o2, o3, "bca-conn-2");

        IArchimateDiagramModel viewB = createView("batch-conn-b", "Batch Conn B");
        IBusinessActor b1 = addActor("bcb-1");
        IBusinessActor b2 = addActor("bcb-2");
        IDiagramModelGroup group = addGroup(viewB, "bcb-group");
        IDiagramModelArchimateObject n1 = place(group, b1, "bcb-nested-1");
        IDiagramModelArchimateObject n2 = place(group, b2, "bcb-nested-2");
        connect(addAssociation("bcb-rel", b1, b2), n1, n2, "bcb-conn-nested");

        testDispatcher.beginBatch(SESSION, "delete two connected views");
        int previewA = accessor.deleteView(SESSION, viewA.getId()).entity().viewConnectionsRemoved();
        int previewB = accessor.deleteView(SESSION, viewB.getId()).entity().viewConnectionsRemoved();
        testDispatcher.endBatch(SESSION, true);

        assertEquals("View A's two top-level connections are counted to itself", 2, previewA);
        assertEquals("View B's group-nested connection is counted via the recursive walk", 1, previewB);
        assertFalse("View A is removed", diagrams.getElements().contains(viewA));
        assertFalse("View B is removed", diagrams.getElements().contains(viewB));
    }

    /**
     * The limit of the union-faithfulness property, made explicit. Each per-op preview is an
     * INDEPENDENT prepare-time snapshot taken against the still-intact model, so a batch that
     * redundantly queues the SAME view for deletion twice returns the same nonzero count twice —
     * while commit removes the placeholder once and the second queued command is a no-op against
     * the already-deleted view. So summing per-op previews is faithful only for DISTINCT queued
     * views; a redundant batch double-counts. This is the extreme of the prepare-time-snapshot
     * property the whole delete/approval/batch family already carries (a preview reflects the
     * model as it stood when the op was prepared, not what a later sibling leaves for it to do),
     * not a new hazard — and it corrupts nothing: the view is deleted exactly once and the
     * placeholder ceases to exist exactly once. Rejecting or deduping redundant queued deletes
     * would be a cross-cutting change to the shared queue plumbing for every delete kind, out of
     * scope here; this pin locks the observed behaviour and scopes the faithfulness claim honestly.
     */
    @Test
    public void shouldDoubleCountButNotCorrupt_whenSameViewQueuedTwiceInOneBatch() throws Exception {
        IArchimateDiagramModel holder = createView("dup-hold", "Holder");
        IArchimateDiagramModel referenced = createView("dup-ref", "Referenced");
        holder.getChildren().add(newRefTo(referenced));

        int externalBefore = countExternalReferencesTo(referenced);

        testDispatcher.beginBatch(SESSION, "redundantly queue the same view twice");
        int previewFirst =
                accessor.deleteView(SESSION, referenced.getId()).entity().viewReferencesRemoved();
        int previewSecond =
                accessor.deleteView(SESSION, referenced.getId()).entity().viewReferencesRemoved();
        testDispatcher.endBatch(SESSION, true);

        int externalAfter = countExternalReferencesTo(referenced);

        assertEquals("First preview snapshots the intact model", 1, previewFirst);
        assertEquals("Second preview is the SAME prepare-time snapshot, not what commit leaves it to remove",
                1, previewSecond);
        assertEquals("Commit removes the placeholder exactly once", 1, externalBefore - externalAfter);
        assertFalse("The view is deleted exactly once — no corruption",
                diagrams.getElements().contains(referenced));
        assertEquals("Summing per-op previews over a redundant batch double-counts (2 vs 1 actual)",
                2, previewFirst + previewSecond);
    }

    /**
     * The commit summary of a redundant batch, measured directly. {@code delete-view} is not a
     * {@code CommitSkippableCommand}, so a second queued delete of the same view runs its
     * {@code execute()} against the already-removed view as a silent no-op — it is NOT reported
     * as a skipped operation. The summary therefore counts both queued operations while the batch
     * had one net effect: the operation count is a count of operations queued, not of net effect,
     * exactly as its contract states. This is the server-emitted number a caller could read, so it
     * is pinned here alongside the agent-side per-op preview sum.
     */
    @Test
    public void shouldCountRedundantOpInSummary_withNoSkipLine_forDeleteView() throws Exception {
        IArchimateDiagramModel referenced = createView("sum-ref", "Referenced");

        testDispatcher.beginBatch(SESSION, "queue the same view twice");
        accessor.deleteView(SESSION, referenced.getId());
        accessor.deleteView(SESSION, referenced.getId());
        BatchSummaryDto summary = testDispatcher.endBatch(SESSION, true);

        assertEquals("The summary counts BOTH queued operations, including the redundant no-op",
                2, summary.operationCount());
        assertNull("delete-view is not CommitSkippable, so the redundant no-op yields NO skip line",
                summary.skippedOperations());
        assertFalse("The view is deleted exactly once", diagrams.getElements().contains(referenced));
    }

    /**
     * Undo integrity of a redundant batch. Two
     * queued {@code DeleteViewCommand}s for the same view each capture their undo anchor
     * ({@code viewIndex} / successor) at construction time, before any execute, so both hold the
     * same pre-delete position. Committing removes the view once; the compound's reverse-order undo
     * must restore it EXACTLY once, with its child subtree and the external placeholder back, and
     * no duplicate view left in the folder. A plain GEF {@link CompoundCommand} is used because it
     * has the same forward-execute / reverse-undo ordering as the {@code NonNotifyingCompoundCommand}
     * the batch commit builds (which only adds headless-unsafe notification suppression on top).
     */
    @Test
    public void shouldUndoCleanly_whenSameViewQueuedTwiceInOneBatch() throws Exception {
        IArchimateDiagramModel holder = createView("undo-hold", "Holder");
        IArchimateDiagramModel referenced = createView("undo-ref", "Referenced");
        holder.getChildren().add(newRefTo(referenced));

        IBusinessActor actor = addActor("undo-actor");
        IDiagramModelArchimateObject child = place(referenced, actor, "undo-child");

        int externalBefore = countExternalReferencesTo(referenced);

        Command first = accessor.prepareDeleteView(referenced.getId()).command();
        Command second = accessor.prepareDeleteView(referenced.getId()).command();
        CompoundCommand compound = new CompoundCommand("dup-delete");
        compound.add(first);
        compound.add(second);

        compound.execute();
        assertFalse("Forward: the view is removed", diagrams.getElements().contains(referenced));
        assertEquals("Forward: the placeholder ceased to exist exactly once",
                0, countExternalReferencesTo(referenced));

        compound.undo();

        assertEquals("Undo restores the view EXACTLY once — no duplicate re-insertion",
                1, Collections.frequency(diagrams.getElements(), referenced));
        assertEquals("Undo restores the external placeholder exactly once",
                externalBefore, countExternalReferencesTo(referenced));
        assertTrue("Undo restores the view's child subtree", referenced.getChildren().contains(child));
        assertEquals("Undo restores exactly the one child", 1, referenced.getChildren().size());
    }

    /**
     * The human approval-card contract for {@code delete-view} (this story). In approval mode the
     * stored proposal must carry the two measured cascade counts BOTH in the human-facing
     * {@code description} — the card's visible change row reads that sentence, not the raw
     * {@code proposedChanges} map — and in {@code proposedChanges} (the Technical-details /
     * Copy-JSON disclosure). {@code relationshipsRemoved} is a permanent 0 for a view and must NOT
     * appear in the sentence. The proposal path must NOT mutate the model (execution is deferred to
     * approval).
     */
    @Test
    public void shouldSurfaceCascadeCountsOnApprovalCard_forDeleteView() {
        buildFiveConnections();          // 5 distinct connections in `target`
        buildThreeExternalReferences();  // 3 external placeholders to `target` (+ 1 self-ref, uncounted)

        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);
        MutationResult<DeleteResultDto> result = accessor.deleteView(SESSION, target.getId());

        assertNotNull("Approval mode must produce a proposal, not execute", result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher()
                .getProposal(SESSION, result.proposalContext().proposalId());
        assertNotNull("the stored proposal must be retrievable", pending);

        // The description IS the card's visible row (delete-view has no effectDescription and no
        // structured name), so both counts must be folded into it, correctly pluralised.
        assertEquals("Delete view: Target View (cascade: 5 view connections, 3 view references)",
                pending.description());
        assertFalse("a view card must never mention relationships (always a correct 0)",
                pending.description().toLowerCase().contains("relationship"));

        // proposedChanges carries the primitive counts for the raw-JSON / Copy-JSON disclosure.
        assertEquals(Integer.valueOf(5), pending.proposedChanges().get("viewConnectionsRemoved"));
        assertEquals(Integer.valueOf(3), pending.proposedChanges().get("viewReferencesRemoved"));
        assertEquals("view id preserved", target.getId(), pending.proposedChanges().get("viewId"));

        assertTrue("the view is still present — approval defers execution",
                diagrams.getElements().contains(target));
    }

    // ---- infrastructure ----

    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(IArchimateModel testModel) {
        testDispatcher = new MutationDispatcher(() -> testModel) {
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

        void setModels(List<IArchimateModel> models) { this.models = models; }

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
