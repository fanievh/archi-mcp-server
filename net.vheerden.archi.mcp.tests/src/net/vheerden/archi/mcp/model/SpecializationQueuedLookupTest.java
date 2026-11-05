package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IProfile;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins that a specialization is found by name as the session's open batch will leave the catalog:
 * one an earlier operation in the batch created is found (and reused, never created a second time),
 * one an earlier operation renamed is found under its new name and not its old one, and one an
 * earlier operation deleted is not found. The same holds between operations of one bulk call.
 * Outside a batch the lookup is the model's.
 */
public class SpecializationQueuedLookupTest {

    private static final String SESSION = ViewContentsFixture.SESSION;
    private static final String TYPE = "BusinessActor";

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-specialization-queued");
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- single tools inside a batch --------------------------------------------------------------

    @Test
    public void shouldNotCreateASecondProfile_whenTheBatchAlreadyQueuedOne() {
        f.dispatcher.beginBatch(SESSION, "twice");
        Map<String, Object> first = f.accessor.createSpecialization(SESSION, "Gold", TYPE, null).entity();
        Map<String, Object> second = f.accessor.createSpecialization(SESSION, "gold", TYPE, null).entity();
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(true, first.get("created"));
        assertEquals("the second finds the one the batch queued", false, second.get("created"));
        assertEquals(List.of("Gold"), names());
    }

    @Test
    public void shouldRenameASpecializationTheBatchCreated() {
        f.dispatcher.beginBatch(SESSION, "create then rename");
        f.accessor.createSpecialization(SESSION, "Gold", TYPE, null);
        f.accessor.updateSpecialization(SESSION, "Gold", TYPE, "Platinum", null, false);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of("Platinum"), names());
    }

    @Test
    public void shouldDeleteASpecializationTheBatchCreated() {
        f.dispatcher.beginBatch(SESSION, "create then delete");
        f.accessor.createSpecialization(SESSION, "Gold", TYPE, null);
        f.accessor.deleteSpecialization(SESSION, "Gold", TYPE, false);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), names());
    }

    /** An inline specialization on two concepts in one batch shares one profile. */
    @Test
    public void shouldShareOneProfile_whenTwoConceptsInTheBatchNameTheSameNewSpecialization() {
        f.dispatcher.beginBatch(SESSION, "inline twice");
        f.accessor.createElement(SESSION, TYPE, "first", null, null, null, "Gold");
        f.accessor.createElement(SESSION, TYPE, "second", null, null, null, "Gold");
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of("Gold"), names());
        IProfile gold = f.model.getProfiles().get(0);
        for (String name : List.of("first", "second")) {
            assertSame(name, gold, element(name).getPrimaryProfile());
        }
    }

    @Test
    public void shouldReuseTheQueuedProfile_whenALaterUpdateNamesIt() {
        IArchimateElement actor = actor("actor");
        f.dispatcher.beginBatch(SESSION, "create then apply");
        f.accessor.createSpecialization(SESSION, "Gold", TYPE, null);
        f.accessor.updateElement(SESSION, actor.getId(), null, null, null, "Gold");
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of("Gold"), names());
        assertSame(f.model.getProfiles().get(0), actor.getPrimaryProfile());
    }

    @Test
    public void shouldFindTheNewNameAndNotTheOld_afterAQueuedRename() {
        live("Gold");
        f.dispatcher.beginBatch(SESSION, "queued rename");
        f.accessor.updateSpecialization(SESSION, "Gold", TYPE, "Platinum", null, false);

        assertEquals(notFound("Gold"), refusal(() -> f.accessor.updateSpecialization(SESSION, "Gold", TYPE, "Silver", null, false)));
        f.accessor.updateSpecialization(SESSION, "Platinum", TYPE, "Diamond", null, false);
        assertEquals("the old name is free again", true,
                f.accessor.createSpecialization(SESSION, "Gold", TYPE, null).entity().get("created"));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of("Diamond", "Gold"), names());
    }

    @Test
    public void shouldNotFindASpecialization_afterAQueuedDelete() {
        live("Gold");
        f.dispatcher.beginBatch(SESSION, "queued delete");
        f.accessor.deleteSpecialization(SESSION, "Gold", TYPE, false);

        assertEquals(notFound("Gold"), refusal(() -> f.accessor.updateSpecialization(SESSION, "Gold", TYPE, "Silver", null, false)));
        assertEquals(notFound("Gold"), refusal(() -> f.accessor.deleteSpecialization(SESSION, "Gold", TYPE, false)));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), names());
    }

    @Test
    public void shouldLeaveTheCatalogAsItWas_whenTheBatchRollsBack() {
        live("Gold");
        f.dispatcher.beginBatch(SESSION, "rolled back");
        f.accessor.createSpecialization(SESSION, "Gold", TYPE, null);
        f.accessor.createSpecialization(SESSION, "Silver", TYPE, null);
        f.dispatcher.endBatch(SESSION, false);

        assertEquals(List.of("Gold"), names());
    }

    // ---- bulk-mutate ------------------------------------------------------------------------------

    @Test
    public void shouldRenameInABulkCall_aSpecializationTheEnclosingBatchCreated() {
        f.dispatcher.beginBatch(SESSION, "bulk in batch");
        f.accessor.createSpecialization(SESSION, "Gold", TYPE, null);
        BulkMutationResult result = bulk(op("update-specialization",
                Map.of("name", "Gold", "conceptType", TYPE, "newName", "Platinum")));
        f.dispatcher.endBatch(SESSION, true);

        assertTrue(result.allSucceeded());
        assertEquals(List.of("Platinum"), names());
    }

    @Test
    public void shouldNotFindInTheSameCall_aSpecializationAnEarlierOperationDeleted() {
        live("Gold");
        ModelAccessException refused = refused(() -> bulk(
                op("delete-specialization", Map.of("name", "Gold", "conceptType", TYPE)),
                op("update-specialization", Map.of("name", "Gold", "conceptType", TYPE, "newName", "Silver"))));

        assertEquals("Operation 1 (update-specialization): " + notFound("Gold"), refused.getMessage());
        assertEquals(List.of("Gold"), names());
    }

    @Test
    public void shouldNotFindInTheSameCall_theNameAnEarlierOperationRenamedAway() {
        live("Gold");
        ModelAccessException refused = refused(() -> bulk(
                op("update-specialization", Map.of("name", "Gold", "conceptType", TYPE, "newName", "Platinum")),
                op("update-specialization", Map.of("name", "Gold", "conceptType", TYPE, "newName", "Silver"))));

        assertEquals("Operation 1 (update-specialization): " + notFound("Gold"), refused.getMessage());
    }

    // ---- control ----------------------------------------------------------------------------------

    @Test
    public void shouldAnswerFromTheModel_outsideABatch() {
        assertEquals(true, f.accessor.createSpecialization(SESSION, "Gold", TYPE, null).entity().get("created"));
        assertEquals(false, f.accessor.createSpecialization(SESSION, "Gold", TYPE, null).entity().get("created"));
        assertEquals(notFound("Silver"), refusal(() -> f.accessor.updateSpecialization(SESSION, "Silver", TYPE, "Bronze", null, false)));
        assertEquals(List.of("Gold"), names());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    static String notFound(String name) {
        return "Specialization not found: name='" + name + "', conceptType='" + TYPE + "'";
    }

    void live(String name) {
        IProfile profile = IArchimateFactory.eINSTANCE.createProfile();
        profile.setName(name);
        profile.setConceptType(TYPE);
        f.model.getProfiles().add(profile);
    }

    List<String> names() {
        return f.model.getProfiles().stream().map(IProfile::getName).sorted().collect(Collectors.toList());
    }

    IArchimateElement actor(String id) {
        IArchimateElement actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setId(id);
        actor.setName(id);
        f.model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        return actor;
    }

    IArchimateElement element(String name) {
        return f.model.getFolder(FolderType.BUSINESS).getElements().stream()
                .map(IArchimateElement.class::cast).filter(e -> name.equals(e.getName())).findFirst().orElseThrow();
    }

    BulkMutationResult bulk(BulkOperation... operations) {
        return f.accessor.executeBulk(SESSION, List.of(operations), "specializations", false, null);
    }

    static BulkOperation op(String tool, Map<String, Object> params) {
        return new BulkOperation(tool, params, null);
    }

    static ModelAccessException refused(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        fail("expected a refusal");
        return null;
    }

    static String refusal(Runnable call) {
        return refused(call).getMessage();
    }
}
