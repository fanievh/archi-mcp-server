package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateObject;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;

/**
 * Pins that {@code clear-view}, on both the single tool and the {@code bulk-mutate} operation, reads the
 * view as the work staged ahead of it will leave it: a view created earlier in the same call or queued by
 * the open batch resolves, and the counts it reports are of what the clear will really remove.
 *
 * <p>What the command removes was already right: it takes whatever is on the view when it runs. Only the
 * lookup and the reported counts were read before the earlier work had run.</p>
 */
public class ClearViewStagedViewTest {

    private static final String SESSION = ViewContentsFixture.SESSION;

    private ViewContentsFixture f;
    private IArchimateElement a;
    private IArchimateElement b;
    private IArchimateElement c;
    private IDiagramModelArchimateObject voA;
    private IDiagramModelArchimateObject voB;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-clear-view-staged-view");
        a = f.element("el-a");
        b = f.element("el-b");
        c = f.element("el-c");
        voA = f.object(f.view, "vo-a", a, 40, 40, 120, 55);
        voB = f.object(f.view, "vo-b", b, 300, 40, 120, 55);
        f.archimateConnection("conn-ab", f.relationship("rel-ab", a, b), voA, voB);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- the view slot ------------------------------------------------------------------------

    @Test
    public void bulk_shouldClearAViewTheSameCallCreated() {
        BulkMutationResult result = bulk(op("create-view", Map.of("name", "Built"), "v"),
                op("add-to-view", Map.of("viewId", "$v.id", "elementId", "el-a", "x", 10, "y", 10), null),
                op("clear-view", Map.of("viewId", "$v.id"), null));

        IArchimateDiagramModel built = view(result.operations().get(0).entityId());
        assertEquals(result.operations().get(0).entityId(), result.operations().get(2).entityId());
        assertNotNull(built);
        assertEquals("the view ends empty", 0, built.getChildren().size());
    }

    @Test
    public void bulk_shouldClearAViewTheEnclosingBatchQueued() {
        f.dispatcher.beginBatch(SESSION, "queued view");
        String viewId = f.accessor.createView(SESSION, "Queued", null, null, null).entity().id();

        bulk(op("clear-view", Map.of("viewId", viewId), null));
        f.dispatcher.endBatch(SESSION, true);

        assertNotNull(view(viewId));
    }

    @Test
    public void tool_shouldClearAViewTheBatchQueued() {
        f.dispatcher.beginBatch(SESSION, "queued view");
        String viewId = f.accessor.createView(SESSION, "Queued", null, null, null).entity().id();
        assertNull("the view is only queued", view(viewId));

        ClearViewResultDto cleared = f.accessor.clearView(SESSION, viewId).entity();
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(new ClearViewResultDto(viewId, "Queued", 0, 0, 0), cleared);
        assertNotNull(view(viewId));
    }

    @Test
    public void tool_shouldAnswerTheSameNotFound_outsideAndInsideABatch() {
        ModelAccessException outside = refusalOf(() -> f.accessor.clearView(SESSION, "no-such-view"));
        f.dispatcher.beginBatch(SESSION, "queued view");
        f.accessor.createView(SESSION, "Queued", null, null, null);
        ModelAccessException inside = refusalOf(() -> f.accessor.clearView(SESSION, "no-such-view"));
        f.dispatcher.endBatch(SESSION, false);

        for (ModelAccessException refused : List.of(outside, inside)) {
            assertEquals("View not found: no-such-view", refused.getMessage());
            assertEquals(ErrorCode.VIEW_NOT_FOUND, refused.getErrorCode());
            assertEquals("Use get-views to find valid view IDs", refused.getSuggestedCorrection());
        }
    }

    @Test
    public void bulk_shouldAnswerTheSameNotFound() {
        ModelAccessException refused = refusalOf(() -> bulk(op("create-view", Map.of("name", "Built"), "v"),
                op("clear-view", Map.of("viewId", "no-such-view"), null)));

        assertEquals("Operation 1 (clear-view): View not found: no-such-view", refused.getMessage());
        assertEquals(ErrorCode.VIEW_NOT_FOUND.name(),
                ((BulkValidationException) refused).getFailures().get(0).errorCode());
    }

    // ---- the counts ---------------------------------------------------------------------------

    @Test
    public void tool_shouldCountWhatTheBatchPlaced() {
        f.dispatcher.beginBatch(SESSION, "queued placement");
        bulk(op("add-to-view", Map.of("viewId", f.view.getId(), "elementId", "el-c", "x", 500, "y", 40), "c"),
                op("add-connection-to-view", Map.of("viewId", f.view.getId(), "relationshipId",
                        f.relationship("rel-ac", a, c).getId(),
                        "sourceViewObjectId", "vo-a", "targetViewObjectId", "$c.id"), null));
        f.accessor.addGroupToView(SESSION, f.view.getId(), "G", 40, 300, 200, 150, null, null, null);
        assertEquals("the additions are only queued", 2, f.view.getChildren().size());

        ClearViewResultDto cleared = f.accessor.clearView(SESSION, f.view.getId()).entity();
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(new ClearViewResultDto("view-source", "Source", 4, 2, 1), cleared);
        assertEquals(0, f.view.getChildren().size());
    }

    @Test
    public void tool_shouldNotCountWhatTheBatchRemoved_norAConnectionLeftWithoutAnEnd() {
        f.dispatcher.beginBatch(SESSION, "queued removal");
        bulk(op("remove-from-view", Map.of("viewId", f.view.getId(), "viewObjectId", "vo-b"), null));
        assertEquals("the removal is only queued", 2, f.view.getChildren().size());

        ClearViewResultDto cleared = f.accessor.clearView(SESSION, f.view.getId()).entity();
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(new ClearViewResultDto("view-source", "Source", 1, 0, 0), cleared);
    }

    /**
     * The deletes list what their element or relationship has on the view as the live model holds it when
     * they are prepared, so neither lists what the batch placed; but each removes all of it when it runs.
     */
    @Test
    public void tool_shouldNotCountWhatTheBatchPlacedAndThenDeleted() {
        f.relationship("rel-ab-2", a, b);
        f.dispatcher.beginBatch(SESSION, "placed then deleted");
        bulk(op("add-to-view", Map.of("viewId", f.view.getId(), "elementId", "el-c", "x", 500, "y", 40), null),
                op("delete-element", Map.of("elementId", "el-c"), null),
                op("add-connection-to-view", Map.of("viewId", f.view.getId(), "relationshipId", "rel-ab-2",
                        "sourceViewObjectId", "vo-a", "targetViewObjectId", "vo-b"), null),
                op("delete-relationship", Map.of("relationshipId", "rel-ab-2"), null));

        ClearViewResultDto cleared = f.accessor.clearView(SESSION, f.view.getId()).entity();
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(new ClearViewResultDto("view-source", "Source", 2, 1, 0), cleared);
        assertEquals(0, f.view.getChildren().size());
    }

    @Test
    public void bulk_shouldClearWhatTheBatchPlacedOnAViewItQueued() {
        f.dispatcher.beginBatch(SESSION, "queued view");
        String viewId = f.accessor.createView(SESSION, "Queued", null, null, null).entity().id();
        bulk(op("add-to-view", Map.of("viewId", viewId, "elementId", "el-a", "x", 10, "y", 10), null));

        BulkMutationResult result = bulk(op("clear-view", Map.of("viewId", viewId), null));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(viewId, result.operations().get(0).entityId());
        assertEquals("the view ends empty", 0, view(viewId).getChildren().size());
    }

    @Test
    public void tool_shouldCountAsBefore_whenNothingIsStaged() {
        f.note(f.view, "note-a", 500, 300);

        ClearViewResultDto cleared = f.accessor.clearView(SESSION, f.view.getId()).entity();

        assertEquals(new ClearViewResultDto("view-source", "Source", 3, 1, 1), cleared);
        assertEquals(0, f.view.getChildren().size());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static BulkOperation op(String tool, Map<String, Object> params, String as) {
        return new BulkOperation(tool, params, as);
    }

    private BulkMutationResult bulk(BulkOperation... operations) {
        return f.accessor.executeBulk(SESSION, List.of(operations), "staged view", false, null);
    }

    private static ModelAccessException refusalOf(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        throw new AssertionError("no refusal");
    }

    private IArchimateDiagramModel view(String id) {
        return f.model.getDiagramModels().stream().filter(v -> v.getId().equals(id))
                .map(IArchimateDiagramModel.class::cast).findFirst().orElse(null);
    }
}
