package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.canvas.model.ICanvasFactory;
import com.archimatetool.canvas.model.ICanvasModel;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IBusinessObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.ISketchModel;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins what a bulk operation's {@code entityName} means: the name the model holds once the whole
 * call has been applied, rather than the name it held while that operation was prepared.
 *
 * <h2>Why a name needs pinning at all</h2>
 *
 * <p>Every operation in a bulk call is prepared before any of them runs, so a result built at that
 * point describes the model as it was before the write. Geometry has been re-read after dispatch
 * for a while; the name was not, and a rename therefore handed back the name the entity used to
 * have — the one string true of neither the request nor the result. An agent reading it as
 * confirmation concludes the rename did not take.</p>
 *
 * <h2>What is deliberately still absent</h2>
 *
 * <p>The refresh replaces a name that is already reported; it never introduces one. View
 * connections, notes and removals report no name, and the tests below pin that absence: populating
 * any of them would be a new claim about tools nothing here measured.</p>
 *
 * <p>Folder operations, model updates and moves were in that list until the projection gained a
 * branch for their prepared shapes. They are no longer absent-by-omission, so their tests assert
 * presence — and, for a folder and the model, that the name is the POST-DISPATCH one, which is
 * what proves the refresh reaches an entity that is neither a concept nor a view object.</p>
 */
public class BulkEntityNameEffectiveStateTest {

    private static final String SESSION = "bulk-entity-name-session";

    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private IArchimateModel model;

    @Before
    public void setUp() {
        stubModelManager = new StubEditorModelManager();
        model = fixture();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ==================== the siblings that share the defect ====================

    @Test
    public void shouldReportTheNewName_whenBulkMutateRenamesAnElement() {
        Map<String, Object> params = new HashMap<>();
        params.put("id", "ba-001");
        params.put("name", "Renamed Actor");

        BulkOperationResult op = runOne("update-element", params, "rename an element");

        assertEquals("update-element must report the name the model holds, not the one it held",
                "Renamed Actor", op.entityName());
    }

    @Test
    public void shouldReportTheNewName_whenBulkMutateRenamesAView() {
        Map<String, Object> params = new HashMap<>();
        params.put("viewId", "view-1");
        params.put("name", "Renamed View");

        BulkOperationResult op = runOne("update-view", params, "rename a view");

        assertEquals("update-view must report the name the model holds, not the one it held",
                "Renamed View", op.entityName());
    }

    /**
     * The arm that tells a re-read apart from a better-informed echo. Both operations name the same
     * element, so the first one's requested name is not the model's final name — an echo of the
     * request reports "Interim" and only a read of the applied model reports "Final".
     */
    @Test
    public void shouldReportTheFinalName_whenTwoOperationsInOneCallRenameTheSameElement() {
        Map<String, Object> first = new HashMap<>();
        first.put("id", "ba-001");
        first.put("name", "Interim");
        Map<String, Object> second = new HashMap<>();
        second.put("id", "ba-001");
        second.put("name", "Final");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-element", first),
                        new BulkOperation("update-element", second)),
                "rename twice", false);

        assertEquals("the earlier operation must describe the applied model, not its own request",
                "Final", result.operations().get(0).entityName());
        assertEquals("Final", result.operations().get(1).entityName());
    }

    /**
     * The same discriminator for specializations, whose prepared name was already the requested one
     * rather than the stale one. Reported and requested agree whenever a single operation succeeds,
     * so only a second rename of the same profile can show that the value is now measured.
     */
    @Test
    public void shouldReportTheFinalName_whenTwoOperationsInOneCallRenameTheSameSpecialization() {
        Map<String, Object> first = new HashMap<>();
        first.put("name", "Gold");
        first.put("conceptType", "BusinessActor");
        first.put("newName", "Silver");
        Map<String, Object> second = new HashMap<>();
        second.put("name", "Silver");
        second.put("conceptType", "BusinessActor");
        second.put("newName", "Platinum");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-specialization", first),
                        new BulkOperation("update-specialization", second)),
                "rename a specialization twice", false);

        assertEquals("the profile the model holds is what both operations must report",
                "Platinum", model.getProfiles().get(0).getName());
        assertEquals("Platinum", result.operations().get(0).entityName());
        assertEquals("Platinum", result.operations().get(1).entityName());
    }

    /**
     * A placement reports the name of the element behind it, and the re-read reaches it too — the
     * id it carries is the view object's, and a view object's name delegates to the concept it
     * shows. So placing an element that an earlier operation in the same call renamed reports the
     * new name, on a tool the name refresh was not written for.
     */
    @Test
    public void shouldReportTheNewName_whenAPlacementFollowsARenameOfTheSameElement() {
        Map<String, Object> rename = new HashMap<>();
        rename.put("id", "ba-001");
        rename.put("name", "Renamed Actor");
        Map<String, Object> place = new HashMap<>();
        place.put("viewId", "view-1");
        place.put("elementId", "ba-001");
        place.put("x", 10);
        place.put("y", 10);
        place.put("width", 120);
        place.put("height", 55);

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-element", rename),
                        new BulkOperation("add-to-view", place)),
                "rename then place", false);

        assertEquals("the placement must name the element as it now stands, not as it was when the "
                + "placement was prepared",
                "Renamed Actor", result.operations().get(1).entityName());
    }

    // ==================== a deletion names what it destroyed ====================

    /**
     * A deletion reports the name its subject carried at the moment the delete ran, not the one it
     * carried when the operation was prepared. The post-dispatch re-read cannot serve this — the id
     * stops resolving the instant the delete applies — so the deleting command records the name
     * inside its own {@code execute()} and the projection reads it back off the compound.
     *
     * <p>The <strong>rename</strong> operation's assertion below deliberately stays at the
     * pre-rename name. That entry is a rename of an entity that no longer exists by the time
     * anything can be read, and no honest post-dispatch value exists for it: unlike the deletion,
     * it is not a report of what was destroyed, so there is nothing for a capture to be a capture
     * <em>of</em>. It is left pinned as it stands rather than quietly changed, so a later reader
     * does not mistake it for an oversight.</p>
     */
    @Test
    public void shouldReportTheNameItWasDestroyedUnder_whenADeleteFollowsARenameOfTheSameEntity() {
        Map<String, Object> rename = new HashMap<>();
        rename.put("id", "ba-001");
        rename.put("name", "Renamed Actor");
        Map<String, Object> delete = new HashMap<>();
        delete.put("elementId", "ba-001");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-element", rename),
                        new BulkOperation("delete-element", delete)),
                "rename then delete", false);

        BulkOperationResult deletion = result.operations().get(1);
        assertEquals("the element was destroyed carrying the new name, so that is what it reports",
                "Renamed Actor", deletion.entityName());
        assertEquals("and the same name in the cascade report one field over",
                "Renamed Actor", deletion.deletion().name());
        assertEquals("the rename operation keeps the prepared name: see the javadoc, this is "
                + "deliberate and not an oversight",
                "Customer", result.operations().get(0).entityName());
    }

    @Test
    public void shouldReportTheNameItWasDestroyedUnder_whenADeleteFollowsARenameOfTheSameRelationship() {
        Map<String, Object> rename = new HashMap<>();
        rename.put("id", "rel-1");
        rename.put("name", "Renamed Association");
        Map<String, Object> delete = new HashMap<>();
        delete.put("relationshipId", "rel-1");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-relationship", rename),
                        new BulkOperation("delete-relationship", delete)),
                "rename then delete a relationship", false);

        BulkOperationResult deletion = result.operations().get(1);
        assertEquals("Renamed Association", deletion.entityName());
        assertEquals("Renamed Association", deletion.deletion().name());
    }

    @Test
    public void shouldReportTheNameItWasDestroyedUnder_whenADeleteFollowsARenameOfTheSameView() {
        Map<String, Object> rename = new HashMap<>();
        rename.put("viewId", "view-1");
        rename.put("name", "Renamed View");
        Map<String, Object> delete = new HashMap<>();
        delete.put("viewId", "view-1");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-view", rename),
                        new BulkOperation("delete-view", delete)),
                "rename then delete a view", false);

        BulkOperationResult deletion = result.operations().get(1);
        assertEquals("Renamed View", deletion.entityName());
        assertEquals("Renamed View", deletion.deletion().name());
    }

    /**
     * The folder arm is the one where the rename that makes the deletion stale is itself invisible:
     * {@code update-folder} reports no {@code entityName} at all, so a caller reading the response
     * has nothing to reconcile the deletion's name against. That makes the deletion's own name the
     * only place the rename can be seen, and the only reason it is worth reading.
     */
    @Test
    public void shouldReportTheNameItWasDestroyedUnder_whenADeleteFollowsARenameOfTheSameFolder() {
        String folderId = subfolder().getId();
        Map<String, Object> rename = new HashMap<>();
        rename.put("id", folderId);
        rename.put("name", "Renamed Folder");
        Map<String, Object> delete = new HashMap<>();
        delete.put("folderId", folderId);

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-folder", rename),
                        new BulkOperation("delete-folder", delete)),
                "rename then delete a folder", false);

        BulkOperationResult deletion = result.operations().get(1);
        assertEquals("Renamed Folder", deletion.entityName());
        assertEquals("Renamed Folder", deletion.deletion().name());
        assertEquals("the rename reports the POST-DISPATCH name — the name operation 1 then "
                + "destroyed the folder under — which is both the describe branch landing and the "
                + "name refresh reaching a folder",
                "Renamed Folder", result.operations().get(0).entityName());
    }

    /**
     * A specialization is addressed by name rather than by id, so the deletion's prepared name is
     * whatever the caller typed. Addressing it by the name it had <em>before</em> a rename in the
     * same call still resolves — the bulk profile cache is re-keyed at prepare while the profile
     * itself is not renamed until dispatch — and the prepared value is then the one name the
     * profile had already stopped using.
     */
    @Test
    public void shouldReportTheNameItWasDestroyedUnder_whenTheDeleteAddressedTheSpecializationByItsOldName() {
        Map<String, Object> rename = new HashMap<>();
        rename.put("name", "Gold");
        rename.put("conceptType", "BusinessActor");
        rename.put("newName", "Platinum");
        Map<String, Object> delete = new HashMap<>();
        delete.put("name", "Gold");
        delete.put("conceptType", "BusinessActor");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-specialization", rename),
                        new BulkOperation("delete-specialization", delete)),
                "rename then delete a specialization by its old name", false);

        assertEquals("the profile was destroyed as 'Platinum'",
                "Platinum", result.operations().get(1).entityName());
        assertNull("a specialization deletion carries no cascade report to correct",
                result.operations().get(1).deletion());
    }

    /**
     * The other half of the same shape, measured rather than assumed: addressing the delete by the
     * post-rename name was already correct before the capture existed, and must stay correct after.
     */
    @Test
    public void shouldStillReportTheDestroyedName_whenTheDeleteAddressedTheSpecializationByItsNewName() {
        Map<String, Object> rename = new HashMap<>();
        rename.put("name", "Gold");
        rename.put("conceptType", "BusinessActor");
        rename.put("newName", "Platinum");
        Map<String, Object> delete = new HashMap<>();
        delete.put("name", "Platinum");
        delete.put("conceptType", "BusinessActor");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-specialization", rename),
                        new BulkOperation("delete-specialization", delete)),
                "rename then delete a specialization by its new name", false);

        assertEquals("Platinum", result.operations().get(1).entityName());
    }

    /**
     * The ordering that tells an execute-time capture apart from a post-dispatch read of the
     * command's own subject.
     *
     * <p>An EMF object removed from its container is detached, not destroyed: the command still
     * holds it and {@code getName()} still answers. So a tempting one-line "fix" is to read the name
     * off the command after dispatch. <strong>That design is what this test rules out.</strong> Here
     * the delete runs first and detaches the element; the update then writes {@code "Ghost"} onto
     * the detached object. A post-dispatch read would report the deletion as having destroyed
     * {@code "Ghost"} — a name the element never carried while it existed. Only a capture taken
     * inside the deleting command's own {@code execute()} reports the truth.</p>
     *
     * <p>This arm passes against the unmodified baseline as well, because the prepared name happens
     * to be the destroyed name when nothing renamed the entity first. It is not a regression pin for
     * the defect; it is a pin on the <em>design</em>, and it is the arm that fails if the capture is
     * ever replaced by a detached read.</p>
     */
    @Test
    public void shouldReportThePreGhostName_whenTheRenameFollowsTheDeleteOfTheSameElement() {
        Map<String, Object> delete = new HashMap<>();
        delete.put("elementId", "ba-001");
        Map<String, Object> rename = new HashMap<>();
        rename.put("id", "ba-001");
        rename.put("name", "Ghost");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("delete-element", delete),
                        new BulkOperation("update-element", rename)),
                "delete then rename", false);

        BulkOperationResult deletion = result.operations().get(0);
        assertEquals("the element was destroyed as 'Customer'; 'Ghost' was written onto the "
                + "detached object afterwards and was never a name it existed under",
                "Customer", deletion.entityName());
        assertEquals("Customer", deletion.deletion().name());
    }

    /**
     * Each deletion reports its own subject, not the one next to it.
     *
     * <p>The read walks the dispatched compound positionally — result {@code i} against child
     * {@code i} — so a shift of one would hand every deletion its neighbour's name. Two deletions
     * of differently-named entities in one call is the arm that can see that: every other test here
     * pairs a delete with an operation that captures nothing, where a misread yields the prepared
     * name and looks like the old defect rather than like a shift.</p>
     */
    @Test
    public void shouldReportItsOwnSubject_whenOneCallDeletesTwoDifferentlyNamedEntities() {
        Map<String, Object> deleteRelationship = new HashMap<>();
        deleteRelationship.put("relationshipId", "rel-1");
        Map<String, Object> deleteElement = new HashMap<>();
        deleteElement.put("elementId", "bo-001");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("delete-relationship", deleteRelationship),
                        new BulkOperation("delete-element", deleteElement)),
                "delete two different entities", false);

        assertEquals("Serves", result.operations().get(0).entityName());
        assertEquals("Serves", result.operations().get(0).deletion().name());
        assertEquals("Order", result.operations().get(1).entityName());
        assertEquals("Order", result.operations().get(1).deletion().name());
    }

    /**
     * A delete that removed nothing captures nothing, even though its subject is still reachable
     * and still answers {@code getName()}.
     *
     * <p>Two deletes of the same id both prepare — nothing has executed yet, so the id resolves for
     * both — and both run. The first destroys the element; the second finds it already gone and
     * removes nothing. Between them a rename writes onto the detached object, so by the time the
     * second delete reads its subject it is called {@code "Ghost"}. Capturing there would report the
     * redundant delete as having destroyed something called {@code "Ghost"} — a name the element
     * never carried while it existed, which is the exact lie the execute-time capture was introduced
     * to end, reappearing one ordering over.</p>
     *
     * <p>So the capture is gated on the removal actually happening. A command that destroyed nothing
     * has no moment of destruction to describe, and its prepared name stands.</p>
     */
    @Test
    public void shouldNotCaptureAName_whenASecondDeleteOfTheSameElementRemovedNothing() {
        Map<String, Object> firstDelete = new HashMap<>();
        firstDelete.put("elementId", "ba-001");
        Map<String, Object> rename = new HashMap<>();
        rename.put("id", "ba-001");
        rename.put("name", "Ghost");
        Map<String, Object> secondDelete = new HashMap<>();
        secondDelete.put("elementId", "ba-001");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("delete-element", firstDelete),
                        new BulkOperation("update-element", rename),
                        new BulkOperation("delete-element", secondDelete)),
                "delete, rename the corpse, delete again", false);

        BulkOperationResult destroyed = result.operations().get(0);
        assertEquals("the delete that really destroyed it names it as it was",
                "Customer", destroyed.entityName());
        assertEquals("Customer", destroyed.deletion().name());

        BulkOperationResult redundant = result.operations().get(2);
        assertEquals("the redundant delete destroyed nothing, so it must not report the name that "
                + "was written onto the detached object after the real deletion",
                "Customer", redundant.entityName());
        assertEquals("Customer", redundant.deletion().name());
    }

    /**
     * Nothing is claimed where nothing executed. A queued bulk call has written nothing by the time
     * the response is built, so no command has captured anything and the prepared name is the only
     * thing there is to report — exactly as before this change.
     */
    @Test
    public void shouldReportThePreparedName_whenTheRenameAndDeleteAreQueuedIntoAnOpenBatch() {
        MutationDispatcher batching = decomposingDispatcher(model);
        ArchiModelAccessorImpl batched =
                new ArchiModelAccessorImpl(stubModelManager, batching);
        try {
            batching.beginBatch(SESSION, "queued rename then delete");
            Map<String, Object> rename = new HashMap<>();
            rename.put("id", "ba-001");
            rename.put("name", "Renamed Actor");
            Map<String, Object> delete = new HashMap<>();
            delete.put("elementId", "ba-001");

            BulkMutationResult result = batched.executeBulk(SESSION,
                    List.of(new BulkOperation("update-element", rename),
                            new BulkOperation("delete-element", delete)),
                    "queued rename then delete", false);

            assertNotNull("this arm is only meaningful if the call really was queued",
                    result.batchSequenceNumber());
            BulkOperationResult deletion = result.operations().get(1);
            assertEquals("nothing has run, so the prepared name is all there is",
                    "Customer", deletion.entityName());
            assertEquals("Customer", deletion.deletion().name());
        } finally {
            batched.dispose();
        }
    }

    /**
     * A delete that declined destroyed nothing, so no name is captured for it and its cascade report
     * keeps the name it was prepared with. {@code DeleteFolderCommand} refuses at execution time
     * when the folder has gained a child the request was never authorised to destroy — here a
     * {@code move-to-folder} earlier in the same call — and it refuses <em>before</em> reaching the
     * point where a name would be recorded.
     *
     * <p>{@code deletion.name} is the observable that discriminates, and it is the reason this test
     * asserts on it rather than on {@code entityName}. A declining folder still exists, so its id
     * still resolves and the live re-read replaces {@code entityName} with the name it now holds —
     * which is the standing ruling for declined operations, and is true: the folder is called that.
     * Only {@code deletion.name} can tell "nothing was captured" apart from "the capture happened
     * and agreed", because a capture would have written the post-rename name into both.</p>
     */
    @Test
    public void shouldNotCaptureAName_whenTheFolderDeleteDeclinedToRun() {
        String folderId = subfolder().getId();
        Map<String, Object> rename = new HashMap<>();
        rename.put("id", folderId);
        rename.put("name", "Renamed Folder");
        Map<String, Object> move = new HashMap<>();
        move.put("objectId", "bo-001");
        move.put("targetFolderId", folderId);
        Map<String, Object> delete = new HashMap<>();
        delete.put("folderId", folderId);

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-folder", rename),
                        new BulkOperation("move-to-folder", move),
                        new BulkOperation("delete-folder", delete)),
                "rename, fill, then try to delete a folder", false);

        assertEquals("this arm is only meaningful if the delete really declined",
                1, result.skippedOperations().size());
        BulkOperationResult declined = result.operations().get(2);
        assertEquals("nothing was destroyed, so the cascade report keeps its prepared name",
                "Sub Folder", declined.deletion().name());
        assertEquals("and the surviving folder's live name stands, as it does for every declined "
                + "operation that can still be read",
                "Renamed Folder", declined.entityName());
    }

    /** The negative control: a create names an object that did not exist to be stale about. */
    @Test
    public void shouldReportTheCreatedName_whenBulkMutateCreatesAnElement() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "BusinessRole");
        params.put("name", "Fresh Role");

        BulkOperationResult op = runOne("create-element", params, "create an element");

        assertEquals("Fresh Role", op.entityName());
    }

    // ==================== absent stays absent ====================

    /**
     * The folder half of the describe branches. This previously pinned an absence — the projection
     * had no branch for a prepared folder, so both the type and the name were missing — which is a
     * different defect from staleness and was closed separately. It now asserts the POST-DISPATCH
     * name, so it pins the branch and the name refresh reaching a folder in one call.
     */
    @Test
    public void shouldReportTheFolderName_whenTheOperationUpdatesAFolder() {
        IFolder folder = model.getFolder(FolderType.BUSINESS);
        Map<String, Object> params = new HashMap<>();
        params.put("id", folder.getId());
        params.put("name", "Renamed Folder");

        BulkOperationResult op = runOne("update-folder", params, "rename a folder");

        assertEquals("the folder really was renamed", "Renamed Folder", folder.getName());
        assertEquals("the name the folder holds after dispatch, read back from the model",
                "Renamed Folder", op.entityName());
        assertEquals("Folder", op.entityType());
    }

    /** The create half of the same shape, measured rather than inferred from its sibling. */
    @Test
    public void shouldReportTheFolderName_whenTheOperationCreatesAFolder() {
        Map<String, Object> params = new HashMap<>();
        params.put("parentId", model.getFolder(FolderType.BUSINESS).getId());
        params.put("name", "New Folder");

        BulkOperationResult op = runOne("create-folder", params, "create a folder");

        assertEquals("asserted against the folder read back from the model, not the request",
                subfolderNamed(op.entityId()).getName(), op.entityName());
        assertEquals("New Folder", op.entityName());
        assertEquals("Folder", op.entityType());
    }

    /** As above for the model itself, whose prepared shape the projection also has a branch for. */
    @Test
    public void shouldReportTheModelName_whenTheOperationUpdatesTheModel() {
        Map<String, Object> params = new HashMap<>();
        params.put("name", "Renamed Model");

        BulkOperationResult op = runOne("update-model", params, "rename the model");

        assertEquals("the model really was renamed", "Renamed Model", model.getName());
        assertEquals("the post-dispatch model name, never getModelInfo()'s pre-dispatch read",
                "Renamed Model", op.entityName());
        assertEquals("ArchimateModel", op.entityType());
    }

    @Test
    public void shouldReportNoEntityName_whenTheOperationPlacedAConnection() {
        String sourceId = place("ba-001");
        String targetId = place("bo-001");
        Map<String, Object> params = new HashMap<>();
        params.put("viewId", "view-1");
        params.put("relationshipId", "rel-1");
        params.put("sourceViewObjectId", sourceId);
        params.put("targetViewObjectId", targetId);

        BulkOperationResult op = runOne("add-connection-to-view", params, "place a connection");

        assertNotNull("the connection was placed", op.entityId());
        assertNull("a placed connection reports no name and must not acquire one", op.entityName());
    }

    @Test
    public void shouldReportNoEntityName_whenTheOperationPlacedANote() {
        Map<String, Object> params = new HashMap<>();
        params.put("viewId", "view-1");
        params.put("content", "A note");
        params.put("x", 50);
        params.put("y", 50);
        // Height pinned deliberately: leaving it out makes the note fit itself to its wrapped
        // content, which measures text through SWT and needs a display this class does not have.
        params.put("width", 185);
        params.put("height", 80);

        BulkOperationResult op = runOne("add-note-to-view", params, "place a note");

        assertNull("a placed note reports no name and must not acquire one", op.entityName());
    }

    @Test
    public void shouldReportNoEntityName_whenTheOperationRemovedAnObjectFromAView() {
        String placed = place("ba-001");
        Map<String, Object> params = new HashMap<>();
        params.put("viewId", "view-1");
        params.put("viewObjectId", placed);

        BulkOperationResult op = runOne("remove-from-view", params, "remove from the view");

        assertNull("a removal reports no name and must not acquire one", op.entityName());
    }

    /**
     * A deletion keeps the name the entity had. Its id stops resolving the moment the delete
     * applies, so there is nothing live to read and the prepared value is the only honest answer —
     * and it is the one the caller needs, since it names what was destroyed.
     */
    @Test
    public void shouldReportTheNameTheEntityHad_whenTheOperationDeletedIt() {
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", "ba-001");

        BulkOperationResult op = runOne("delete-element", params, "delete an element");

        assertEquals("Customer", op.entityName());
    }

    // ==================== the projection's own edges ====================
    //
    // Driven through the projection directly rather than through a tool. No tool can leave an
    // entity live under a null name, and none reports a name for an id it also destroyed in the
    // same operation, so these arms are unreachable end to end — and an unreachable guard that
    // nothing exercises is how a best-effort read becomes an exception on an already-written model.

    @Test
    public void shouldLeaveThePreparedNameStanding_whenTheEntityIdNoLongerResolves() {
        BulkOperationResult prepared = new BulkOperationResult(
                0, "update-element", "updated", "no-such-id", "BusinessActor", "Prepared Name");

        assertEquals("Prepared Name", projectOne(prepared).entityName());
    }

    @Test
    public void shouldLeaveThePreparedNameStanding_whenTheLiveEntityHasNoName() {
        IBusinessActor nameless = (IBusinessActor) model.getFolder(FolderType.BUSINESS)
                .getElements().get(0);
        nameless.setName(null);
        Assume.assumeTrue("this arm needs a live entity whose name really is null",
                nameless.getName() == null);
        BulkOperationResult prepared = new BulkOperationResult(
                0, "update-element", "updated", "ba-001", "BusinessActor", "Prepared Name");

        assertEquals("a null live name must not delete the field from the wire",
                "Prepared Name", projectOne(prepared).entityName());
    }

    @Test
    public void shouldNotInventAnEntityName_whenTheOperationReportedNone() {
        BulkOperationResult prepared = new BulkOperationResult(
                0, "add-note-to-view", "placed", "ba-001", "DiagramModelNote", null);

        assertNull("a refresh replaces a reported name; it never introduces one",
                projectOne(prepared).entityName());
    }

    // ---- folder / model / move describe branches ----

    /**
     * The queued half of the model rename. Nothing has executed, so the only honest preview is the
     * name the operation asked for — the same value {@code prepareUpdateFolder} already previews
     * for its sibling. What it must never be is the name the model holds NOW, which is the name
     * this operation is about to replace: a confident wrong value in a preview is the
     * prepare/execute divergence the preview label exists to declare, not discharge.
     */
    @Test
    public void shouldReportTheRequestedModelName_whenTheModelRenameIsQueuedIntoAnOpenBatch() {
        MutationDispatcher batching = decomposingDispatcher(model);
        ArchiModelAccessorImpl batched = new ArchiModelAccessorImpl(stubModelManager, batching);
        try {
            batching.beginBatch(SESSION, "queued model rename");
            Map<String, Object> rename = new HashMap<>();
            rename.put("name", "Renamed Model");

            BulkMutationResult result = batched.executeBulk(SESSION,
                    List.of(new BulkOperation("update-model", rename)),
                    "queued model rename", false);

            assertNotNull("this arm is only meaningful if the call really was queued",
                    result.batchSequenceNumber());
            assertEquals("nothing has executed, so the model still holds its old name",
                    "Bulk Entity Name Test Model", model.getName());
            assertEquals("the queued preview reports the REQUESTED name, never the pre-change name "
                    + "the model still holds", "Renamed Model",
                    result.operations().get(0).entityName());
        } finally {
            batched.dispose();
        }
    }

    /** A moved element names itself and its own ArchiMate type. */
    @Test
    public void shouldReportTheSubject_whenTheOperationMovesAnElement() {
        IFolder target = newSubfolderUnder(FolderType.BUSINESS, "f-el", "Elements");

        BulkOperationResult op = runOne("move-to-folder", moveParams("ba-001", target), "move");

        assertEquals("Customer", op.entityName());
        assertEquals("the element's own type, not the coarse move shape",
                "BusinessActor", op.entityType());
    }

    /** A moved relationship names itself, and reports its exact relationship eClass. */
    @Test
    public void shouldReportTheSubject_whenTheOperationMovesARelationship() {
        IFolder target = newSubfolderUnder(FolderType.RELATIONS, "f-rel", "Relations");

        BulkOperationResult op = runOne("move-to-folder", moveParams("rel-1", target), "move");

        assertEquals("Serves", op.entityName());
        assertEquals("AssociationRelationship", op.entityType());
    }

    /** A moved view names itself. */
    @Test
    public void shouldReportTheSubject_whenTheOperationMovesAView() {
        IFolder target = newSubfolderUnder(FolderType.DIAGRAMS, "f-view", "Views");

        BulkOperationResult op = runOne("move-to-folder", moveParams("view-1", target), "move");

        assertEquals("Original View", op.entityName());
        assertEquals("ArchimateDiagramModel", op.entityType());
    }

    /** A moved folder names itself — the fourth subject kind a move can have. */
    @Test
    public void shouldReportTheSubject_whenTheOperationMovesAFolder() {
        IFolder target = newSubfolderUnder(FolderType.BUSINESS, "f-nest", "Nest");

        BulkOperationResult op = runOne("move-to-folder", moveParams("folder-sub", target), "move");

        assertEquals("Sub Folder", op.entityName());
        assertEquals("Folder", op.entityType());
    }

    /**
     * Consequent to the move branch: the entry now says what its entity is, so the gate that was
     * only ever "does this entry describe its entity" opens for a moved concept. Asserted rather
     * than merely allowed — this is a new field on the wire and it should be visible as one.
     */
    @Test
    public void shouldAttachTheConceptReport_whenAMovedSubjectIsAConcept() {
        IFolder elementTarget = newSubfolderUnder(FolderType.BUSINESS, "f-el", "Elements");
        IFolder relationTarget = newSubfolderUnder(FolderType.RELATIONS, "f-rel", "Relations");

        BulkOperationResult moved =
                runOne("move-to-folder", moveParams("ba-001", elementTarget), "move element");
        assertNotNull("a moved element now carries its post-move state", moved.effectiveElement());
        assertEquals("Customer", moved.effectiveElement().name());

        BulkOperationResult movedRel =
                runOne("move-to-folder", moveParams("rel-1", relationTarget), "move relationship");
        assertNotNull("a moved relationship likewise", movedRel.effectiveRelationship());
        assertEquals("Serves", movedRel.effectiveRelationship().name());
    }

    /**
     * A sketch view is an {@code IDiagramModel} but NOT an {@code IArchimateDiagramModel}, so the
     * move prepare's type chain used to miss it and fall to its last-resort arm, which reports the
     * caller's own id string AS the name. That was invisible while the projection had no branch for
     * a move; giving it one would have put a raw id on the wire wearing a name's field — a
     * confident wrong value where there had been an honest absence.
     *
     * <p>Dispatched, the post-dispatch re-read would paper over it, since a sketch is nameable and
     * resolves by id. So this asserts the PREPARED value through the projection alone, which is
     * what a queued batch and an approval card actually show.</p>
     */
    @Test
    public void shouldReportTheSketchViewsName_whenTheOperationMovesASketchView() {
        ISketchModel sketch = IArchimateFactory.eINSTANCE.createSketchModel();
        sketch.setId("sketch-1");
        sketch.setName("Ideas Sketch");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(sketch);
        IFolder target = newSubfolderUnder(FolderType.DIAGRAMS, "f-sketch", "Sketches");

        BulkOperationResult op = BulkResultProjection.describe(0, "move-to-folder", "moved",
                accessor.prepareMoveToFolder("sketch-1", target.getId()));

        assertEquals("the sketch's name, never the id the caller passed in — asserted FIRST so a "
                + "regression here reports the raw id rather than being masked by the type check",
                "Ideas Sketch", op.entityName());
        assertEquals("a sketch view reports its OWN eClass — the discriminator proving the move "
                + "branch reads eClass() rather than mapping the coarse noun to an ArchiMate view",
                "SketchModel", op.entityType());
    }

    /**
     * The canvas half of the same claim. A canvas view is the other non-ArchiMate diagram
     * {@code move-to-folder} accepts through its {@code IDiagramModel} guard, and the published
     * release note names {@code CanvasModel} beside {@code SketchModel} — so it is pinned here
     * rather than left resting on the sketch case and an argument about the mechanism.
     * <p>
     * {@code ICanvasModel} lives in {@code com.archimatetool.canvas}, which the PRODUCTION bundle
     * deliberately does not require (see {@code ArchiModelAccessorImpl#deriveViewKind}). This
     * fragment carries the optional {@code Require-Bundle} entry that makes it visible to the
     * Eclipse PDE compile — {@code tools/run-tests.sh} globs every Archi jar and so never needed
     * it, which is how the missing entry reached the IDE as an unresolved import. Do not drop the
     * manifest entry: the fragment is never packaged, so it adds nothing to the shipped plug-in.
     */
    @Test
    public void shouldReportTheCanvasEClass_whenTheOperationMovesACanvasView() {
        ICanvasModel canvas = ICanvasFactory.eINSTANCE.createCanvasModel();
        canvas.setId("canvas-1");
        canvas.setName("Business Model Canvas");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(canvas);
        IFolder target = newSubfolderUnder(FolderType.DIAGRAMS, "f-canvas", "Canvases");

        BulkOperationResult op = BulkResultProjection.describe(0, "move-to-folder", "moved",
                accessor.prepareMoveToFolder("canvas-1", target.getId()));

        assertEquals("Business Model Canvas", op.entityName());
        assertEquals("CanvasModel", op.entityType());
    }

    /**
     * {@code elementType} is the field the precision actually rides on, and widening it from
     * elements-only to every moved kind changed the {@code move-to-folder} payload's SHAPE: the
     * DTO is {@code NON_NULL}, so for a folder, a view and a relationship the key went from absent
     * to present. Nothing pinned that, and the branch that would have caught a regression —
     * {@code describe}'s {@code elementType() != null ? … : objectType()} — cannot, because it
     * reports the identical string either way for a folder.
     *
     * <p>Asserted against the real accessor on all four kinds a move accepts, so the published
     * claim that {@code elementType} carries the exact type of "whatever moved" is earned rather
     * than inferred from the element case.</p>
     */
    @Test
    public void shouldPopulateElementType_forEveryKindAMoveAccepts() {
        IFolder elTarget = newSubfolderUnder(FolderType.BUSINESS, "f-el-t", "Elements");
        IFolder relTarget = newSubfolderUnder(FolderType.RELATIONS, "f-rel-t", "Relations");
        IFolder viewTarget = newSubfolderUnder(FolderType.DIAGRAMS, "f-view-t", "Views");
        IFolder folderTarget = newSubfolderUnder(FolderType.BUSINESS, "f-fold-t", "Folders");

        assertEquals("an element still reports its eClass, as it always did",
                "BusinessActor",
                accessor.prepareMoveToFolder("ba-001", elTarget.getId()).entity().elementType());
        assertEquals("a relationship's elementType was NULL before this widened",
                "AssociationRelationship",
                accessor.prepareMoveToFolder("rel-1", relTarget.getId()).entity().elementType());
        assertEquals("a view's elementType was NULL before this widened",
                "ArchimateDiagramModel",
                accessor.prepareMoveToFolder("view-1", viewTarget.getId()).entity().elementType());
        assertEquals("a folder's elementType was NULL before this widened — and it is the one kind "
                + "entityType cannot detect a regression in, since objectType reads 'Folder' too",
                "Folder",
                accessor.prepareMoveToFolder("folder-sub", folderTarget.getId()).entity()
                        .elementType());
    }

    /**
     * The discriminator that tells a post-dispatch re-read apart from a prepared value that merely
     * happened to be right. Every other move test is a single operation whose name never changes,
     * so it cannot distinguish the two. Here a later operation renames the very element the move
     * reported, so an entry echoing its prepare says "Customer" and only a live read says "Renamed
     * After Move" — and the concept report attached to the move must agree with it.
     */
    @Test
    public void shouldReportTheFinalName_whenALaterOperationRenamesTheMovedElement() {
        IFolder target = newSubfolderUnder(FolderType.BUSINESS, "f-el", "Elements");
        Map<String, Object> rename = new HashMap<>();
        rename.put("id", "ba-001");
        rename.put("name", "Renamed After Move");

        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("move-to-folder", moveParams("ba-001", target)),
                        new BulkOperation("update-element", rename)),
                "move then rename the same element", false);

        BulkOperationResult moved = result.operations().get(0);
        assertEquals("the move must describe the applied model, not its own prepare",
                "Renamed After Move", moved.entityName());
        assertEquals("the concept report is read after the write too, so it must agree",
                "Renamed After Move", moved.effectiveElement().name());
    }

    /** A moved view is not a concept, so no concept report is attached to it. */
    @Test
    public void shouldAttachNoConceptReport_whenAMovedSubjectIsAView() {
        IFolder target = newSubfolderUnder(FolderType.DIAGRAMS, "f-view", "Views");

        BulkOperationResult op = runOne("move-to-folder", moveParams("view-1", target), "move");

        assertNull("a view is neither an element nor a relationship", op.effectiveElement());
        assertNull(op.effectiveRelationship());
    }

    /**
     * The verb beside the name. {@code resolveActionString} names every tool's action and falls
     * back to "created", which is right for the five create-shaped tools that still reach the
     * default and wrong for {@code update-folder} — a rename is not a creation. Only visible once
     * the branches above gave these rows a name to sit beside: an anonymous row's wrong verb reads
     * as noise, a named row's reads as a claim.
     *
     * <p>{@code create-folder} is the control: it must keep "created", or this would be a fix that
     * moved the error rather than removing it.</p>
     */
    @Test
    public void shouldReportTheUpdateVerb_whenTheOperationRenamesAFolder() {
        Map<String, Object> rename = new HashMap<>();
        rename.put("id", subfolder().getId());
        rename.put("name", "Renamed Folder");
        Map<String, Object> create = new HashMap<>();
        create.put("parentId", model.getFolder(FolderType.BUSINESS).getId());
        create.put("name", "New Folder");

        assertEquals("a rename is not a creation",
                "updated", runOne("update-folder", rename, "rename a folder").action());
        assertEquals("the create-shaped default is still right for an actual create",
                "created", runOne("create-folder", create, "create a folder").action());
    }

    /**
     * The other half of the same defect, no longer deferred. A move is not a creation either, and
     * {@code move-to-folder}'s honest verb needed a new {@code case} rather than a free seat on the
     * existing "updated" list — the coarser verb would have been permanent, and a move is not an
     * update any more than it is a creation.
     */
    @Test
    public void shouldReportTheMoveVerb_whenTheOperationMovesAnElement() {
        IFolder target = newSubfolderUnder(FolderType.BUSINESS, "f-el", "Elements");

        assertEquals("a move is neither a creation nor an update",
                "moved", runOne("move-to-folder", moveParams("ba-001", target), "move").action());
    }

    /**
     * The approval card renders one named row per operation from {@code entityName} /
     * {@code entityType}, so the three branches above are also the first time a folder, a model
     * and a move appear on that card as anything but an anonymous row. Pinned here because the
     * card is a human-facing surface and a sibling row already records it under-reporting.
     */
    @Test
    public void shouldRenderNamedApprovalRows_whenAFolderModelAndMoveAwaitApproval() {
        IFolder target = newSubfolderUnder(FolderType.BUSINESS, "f-el", "Elements");
        MutationDispatcher approving = decomposingDispatcher(model);
        approving.setApprovalModeProvider(() -> true);
        ArchiModelAccessorImpl proposing =
                new ArchiModelAccessorImpl(stubModelManager, approving);
        try {
            Map<String, Object> folderRename = new HashMap<>();
            folderRename.put("id", subfolder().getId());
            folderRename.put("name", "Renamed Folder");
            Map<String, Object> modelRename = new HashMap<>();
            modelRename.put("name", "Renamed Model");

            proposing.executeBulk(SESSION,
                    List.of(new BulkOperation("update-folder", folderRename),
                            new BulkOperation("update-model", modelRename),
                            new BulkOperation("move-to-folder", moveParams("ba-001", target))),
                    "folder, model and move awaiting approval", false);

            List<Map<String, Object>> rows = approvalRows(approving);
            assertEquals("Renamed Folder", rows.get(0).get("name"));
            assertEquals("Folder", rows.get(0).get("type"));
            assertEquals("the card shows the name the model WILL hold, not the one it still holds",
                    "Renamed Model", rows.get(1).get("name"));
            assertEquals("ArchimateModel", rows.get(1).get("type"));
            assertEquals("Customer", rows.get(2).get("name"));
            assertEquals("BusinessActor", rows.get(2).get("type"));
        } finally {
            proposing.dispose();
        }
    }

    /**
     * The one place the {@code entityType} vocabulary is actually branched on. A moved relationship
     * has no captured endpoint names — {@code move-to-folder} prepares no relationship DTO — and the
     * tool name does not contain "relationship" either, so the approval card can only find its
     * source and target through the {@code endsWith("Relationship")} arm reading this very field.
     * That coupling survived the old spelling by coincidence: {@code "Relationship"} and
     * {@code "AssociationRelationship"} happen to end in the same word. Pinned so a future
     * vocabulary that does not — a bare {@code "Association"}, say — is caught here rather than by a
     * human noticing a card row lost its endpoints.
     */
    @Test
    public void shouldStillResolveEndpoints_whenAMovedRelationshipReachesTheApprovalCard() {
        IFolder target = newSubfolderUnder(FolderType.RELATIONS, "f-rel-approve", "Relations");
        MutationDispatcher approving = decomposingDispatcher(model);
        approving.setApprovalModeProvider(() -> true);
        ArchiModelAccessorImpl proposing =
                new ArchiModelAccessorImpl(stubModelManager, approving);
        try {
            proposing.executeBulk(SESSION,
                    List.of(new BulkOperation("move-to-folder", moveParams("rel-1", target))),
                    "a moved relationship awaiting approval", false);

            Map<String, Object> row = approvalRows(approving).get(0);
            assertEquals("AssociationRelationship", row.get("type"));
            assertEquals("resolved through the entityType arm, not a captured endpoint pair",
                    "Customer", row.get("source"));
            assertEquals("Order", row.get("target"));
        } finally {
            proposing.dispose();
        }
    }

    // ============ the alignment a placement stamped, on the wire ============

    /**
     * The three placement tools whose objects are born carrying an alignment the caller never
     * asked for. {@code StylingHelper.applyStylingToNewObject} stamps Archi's type default onto a
     * group, a note and a {@code Grouping}, so the model holds {@code textAlignment: "left"} the
     * moment any of them is placed — through the standalone tool or through bulk alike. Only the
     * report diverged: the standalone tools serialize their whole prepared DTO, while the bulk
     * projection read type, name and geometry off it and nothing else.
     *
     * <p>Every assertion here reads the <b>serialized envelope</b> rather than the
     * {@link BulkOperationResult} the accessor returns, and that is the point rather than a
     * stylistic preference. The per-operation entry is not serialized from the record: it is
     * rebuilt key by key into a {@code LinkedHashMap} at the handler boundary, and this codebase
     * has already lost a field of this exact family there once, with a typed assertion on the
     * result object staying green while nothing reached the wire. A record-level assertion is
     * green through the very defect it exists to catch.</p>
     */
    @Test
    public void shouldReportTheStampedAlignmentOnTheWire_whenBulkPlacesAGrouping() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "add-to-view", "params", Map.of(
                        "viewId", "view-1", "elementId", "grp-001",
                        "x", 10, "y", 10, "width", 200, "height", 120))),
                "description", "place a grouping")));

        assertTrue("a placed Grouping holds textAlignment 'left' and the entry must say so. "
                + "Response was: " + json,
                json.contains("\"textAlignment\":\"left\""));
    }

    @Test
    public void shouldReportTheStampedAlignmentOnTheWire_whenBulkPlacesANativeGroup() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "add-group-to-view", "params", Map.of(
                        "viewId", "view-1", "label", "Layer",
                        "x", 0, "y", 0, "width", 400, "height", 300))),
                "description", "place a native group")));

        assertTrue("a placed group holds textAlignment 'left' and the entry must say so. "
                + "Response was: " + json,
                json.contains("\"textAlignment\":\"left\""));
    }

    @Test
    public void shouldReportTheStampedAlignmentOnTheWire_whenBulkPlacesANote() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "add-note-to-view", "params", Map.of(
                        "viewId", "view-1", "content", "Caption",
                        "x", 20, "y", 20, "width", 160, "height", 60))),
                "description", "place a note")));

        assertTrue("a placed note holds textAlignment 'left' and the entry must say so. "
                + "Response was: " + json,
                json.contains("\"textAlignment\":\"left\""));
    }

    /**
     * The negative control, without which every assertion above passes on a projection that emits
     * a constant. {@code add-to-view} stamps a type default for a group, a note and a
     * {@code Grouping} only — a plain element is left at Archi's CENTRE default, which
     * {@code readTextAlignment} maps to null and {@code @JsonInclude(NON_NULL)} then drops. So the
     * key must be wholly absent here, not present carrying "centre".
     */
    @Test
    public void shouldOmitTheAlignmentOnTheWire_whenTheBulkPlacedObjectIsAtCentre() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "add-to-view", "params", Map.of(
                        "viewId", "view-1", "elementId", "ba-001",
                        "x", 10, "y", 10, "width", 120, "height", 55))),
                "description", "place a plain element")));

        assertFalse("a plain element stays at CENTRE, so the key must be absent rather than "
                + "carrying the default back. Response was: " + json,
                json.contains("textAlignment"));
    }

    /**
     * What tells a live read apart from a better-informed projection. Both operations run in one
     * call: the first places a {@code Grouping}, which the model stamps LEFT, and the second moves
     * it to RIGHT. A value captured when operation one was prepared reports "left"; only a read of
     * the applied model reports "right".
     */
    @Test
    public void shouldReportTheFinalAlignment_whenALaterOperationInTheSameCallChangesIt()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(
                        Map.of("tool", "add-to-view", "params", Map.of(
                                "viewId", "view-1", "elementId", "grp-001",
                                "x", 10, "y", 10, "width", 200, "height", 120)),
                        Map.of("tool", "update-view-object", "params", Map.of(
                                "viewId", "view-1", "viewObjectId", "$0.id",
                                "textAlignment", "right"))),
                "description", "place then realign"));
        String json = json(envelope);

        // Scoped to operation 0 rather than asserted over the whole envelope. Operation 1 targets
        // the same object and carries an alignment of its own, so a substring check across the
        // reply could be satisfied by the entry that was never in question — and this test exists
        // to pin the PLACEMENT entry.
        assertEquals("the placement entry must carry the alignment the call left behind, not the "
                        + "one it stamped. Response was: " + json,
                "right", operationsOf(envelope).get(0).get("textAlignment"));
        assertFalse("no entry may still be claiming the superseded value. Response was: " + json,
                json.contains("\"textAlignment\":\"left\""));
    }

    /**
     * Nothing is effective in a queued call by construction, so the projection is skipped wholesale
     * and this field must be absent with the rest of it. Reporting an alignment there would be a
     * projection wearing a measurement's name — the batch sibling in the envelope is what a queued
     * call says instead.
     */
    @Test
    public void shouldOmitTheAlignmentOnTheWire_whenTheBulkCallIsQueuedIntoABatch() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        invokeTool(registry, "begin-batch", Map.of());

        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "add-to-view", "params", Map.of(
                        "viewId", "view-1", "elementId", "grp-001",
                        "x", 10, "y", 10, "width", 200, "height", 120))),
                "description", "queue a grouping placement")));

        assertFalse("nothing has been written, so no alignment may be reported. Response was: "
                + json, json.contains("textAlignment"));
    }

    /**
     * The copier hazard the record's own javadoc records, aimed at the new component. The
     * after-dispatch pass attaches the alignment and the geometry through separate copiers, and the
     * retraction pass that follows re-copies the result again — so a copier that names every other
     * component and forgets this one takes it off the wire for every operation that reaches it.
     * Asserted on the record because that is where a copier can drop it.
     */
    @Test
    public void shouldSurviveACopierRoundTrip_soAWithCallCannotSilentlyDropTheAlignment() {
        BulkOperationResult stamped = new BulkOperationResult(0, "add-to-view", "added",
                "vo-1", "Grouping", "Domain", null, null, null, null, List.of(), List.of(),
                null, null, null, null, List.of(), "left");

        BulkOperationResult copied = stamped
                .withEffectiveBounds(new BulkOperationResult.EffectiveBounds(1, 2, 3, 4))
                .withResizedAncestors(List.of())
                .withMovedObjects(List.of())
                .withParentViewObjectId("parent-1")
                .withEntityName("Domain");

        assertEquals("every copier must forward the alignment, for the reason the record's own "
                + "javadoc gives about the components before it", "left", copied.textAlignment());
    }

    // ==================== harness ====================

    /** The structured per-operation rows the approval card renders. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> approvalRows(MutationDispatcher dispatcher) {
        Map<String, Object> changes =
                dispatcher.getPendingProposalDtos(SESSION).get(0).proposedChanges();
        return (List<Map<String, Object>>) changes.get("operations");
    }

    private Map<String, Object> moveParams(String objectId, IFolder target) {
        Map<String, Object> params = new HashMap<>();
        params.put("objectId", objectId);
        params.put("targetFolderId", target.getId());
        return params;
    }

    /** A fresh user subfolder under the given layer root, so a move has somewhere legal to go. */
    private IFolder newSubfolderUnder(FolderType type, String id, String name) {
        IFolder created = IArchimateFactory.eINSTANCE.createFolder();
        created.setId(id);
        created.setName(name);
        model.getFolder(type).getFolders().add(created);
        return created;
    }

    /**
     * The subfolder of the Business root with the given id. Keyed on id rather than on name so the
     * caller can assert the NAME against it — a lookup keyed on the name being asserted proves only
     * that some folder carries that literal, which is true by construction.
     */
    private IFolder subfolderNamed(String id) {
        return model.getFolder(FolderType.BUSINESS).getFolders().stream()
                .filter(f -> id.equals(f.getId())).findFirst().orElseThrow();
    }

    private BulkOperationResult runOne(String tool, Map<String, Object> params, String label) {
        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation(tool, params)), label, false);
        return result.operations().get(0);
    }

    private BulkOperationResult projectOne(BulkOperationResult prepared) {
        return BulkResultProjection.withPostDispatchState(model, List.of(prepared), true).get(0);
    }

    /** The fixture's one user-created subfolder — the only folder a delete is allowed to touch. */
    private IFolder subfolder() {
        return model.getFolder(FolderType.BUSINESS).getFolders().get(0);
    }

    /** Places an element on the fixture view and returns the new view object's id. */
    private String place(String elementId) {
        return accessor.addToView(SESSION, "view-1", elementId, 10, 10, 120, 55,
                false, null, null, null).entity().viewObject().viewObjectId();
    }

    private IArchimateModel fixture() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel built = factory.createArchimateModel();
        built.setName("Bulk Entity Name Test Model");
        built.setId("model-bulk-entity-name");
        built.setDefaults();

        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("ba-001");
        actor.setName("Customer");
        built.getFolder(FolderType.BUSINESS).getElements().add(actor);

        IBusinessObject object = factory.createBusinessObject();
        object.setId("bo-001");
        object.setName("Order");
        built.getFolder(FolderType.BUSINESS).getElements().add(object);

        IAssociationRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.setName("Serves");
        rel.connect(actor, object);
        built.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Original View");
        built.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IFolder subfolder = factory.createFolder();
        subfolder.setId("folder-sub");
        subfolder.setName("Sub Folder");
        built.getFolder(FolderType.BUSINESS).getFolders().add(subfolder);

        // A Grouping, because it is one of the three types add-to-view stamps a type-default
        // alignment onto. A plain element placed by the same tool is left at CENTRE, which is what
        // makes the negative control below able to fail.
        IGrouping grouping = factory.createGrouping();
        grouping.setId("grp-001");
        grouping.setName("Domain");
        built.getFolder(FolderType.OTHER).getElements().add(grouping);

        IProfile profile = factory.createProfile();
        profile.setId("prof-1");
        profile.setName("Gold");
        profile.setConceptType("BusinessActor");
        built.getProfiles().add(profile);

        return built;
    }

    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(IArchimateModel target) {
        return new ArchiModelAccessorImpl(stubModelManager, decomposingDispatcher(target));
    }

    /**
     * A dispatcher that recursively flattens compounds and executes each leaf.
     *
     * <p>Every leaf's own {@code execute()} still runs, so a command that records something at
     * execution time — a declined delete's skip reason, or the name its subject carried when it
     * ran — behaves here exactly as it does under the real command stack.</p>
     */
    private static MutationDispatcher decomposingDispatcher(IArchimateModel target) {
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

    /**
     * Minimal {@link IEditorModelManager} stub — only supports {@code setModels()} /
     * {@code getModels()} and listener registration. Mirrors {@code ArchiModelAccessorImplTest}.
     */
    /**
     * A registry over the live accessor, so a call runs the real prepare, the real dispatch, the
     * real projection and the real handler-side envelope build rather than any stub of them.
     */
    private CommandRegistry registryOverLiveAccessor() {
        CommandRegistry registry = new CommandRegistry();
        SessionManager sessions =
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry, sessions);
        return registry;
    }

    private Map<String, Object> invokeTool(CommandRegistry registry, String toolName,
            Map<String, Object> args) throws Exception {
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec =
                registry.getToolSpecifications().stream()
                        .filter(s -> s.tool().name().equals(toolName))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
        io.modelcontextprotocol.spec.McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                        toolName, args));
        io.modelcontextprotocol.spec.McpSchema.TextContent content =
                (io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().get(0);
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(content.text(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /** The per-operation entries, in call order, as they reach the wire. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operationsOf(Map<String, Object> envelope) {
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        return (List<Map<String, Object>>) result.get("operations");
    }

    /** The envelope as it actually goes out, which is the only thing the wire assertions may read. */
    private static String json(Map<String, Object> envelope) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        @Override
        public List<IArchimateModel> getModels() {
            return models;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

        @SuppressWarnings("unused")
        void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
            PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
            for (PropertyChangeListener listener : new ArrayList<>(listeners)) {
                listener.propertyChange(evt);
            }
        }

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
