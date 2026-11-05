package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;

/**
 * Pins that {@code set-view-label-expression} reads the view as the work staged ahead of it will leave
 * it: a view created earlier in the same {@code bulk-mutate}, or queued by the enclosing batch, resolves
 * as a live one does.
 *
 * <p>Every earlier operation in a bulk call, and every command the open batch queued, is prepared before
 * any of them runs, so a prepare that reads only the live model sees the view as it was before all of
 * them. A view created in the same call is not in the model's containment until its command runs.</p>
 */
public class LabelExpressionStagedViewTest {

    private static final String SESSION = ViewContentsFixture.SESSION;
    private static final String TEMPLATE = "X ${name}";

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-label-expression-staged-view");
        f.object(f.view, "vo-a", f.element("el-a"), 40, 40, 120, 55);
        f.element("el-b");
        f.element("el-c");
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- the view slot ------------------------------------------------------------------------

    @Test
    public void shouldLabelAViewTheSameCallCreated() {
        BulkMutationResult result = bulk(op("create-view", Map.of("name", "Built"), "v"),
                place("$v.id", "el-a", 10, 10),
                label("$v.id", TEMPLATE));

        BulkOperationResult labelled = result.operations().get(2);
        assertEquals(result.operations().get(0).entityId(), labelled.entityId());
        assertEquals("Built", labelled.entityName());
    }

    @Test
    public void shouldLabelAViewTheEnclosingBatchQueued() {
        f.dispatcher.beginBatch(SESSION, "queued view");
        String viewId = f.accessor.createView(SESSION, "Queued", null, null, null).entity().id();
        assertNull("the view is only queued", view(viewId));

        BulkMutationResult result = bulk(label(viewId, TEMPLATE));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(viewId, result.operations().get(0).entityId());
        assertNotNull("the queued view committed", view(viewId));
    }

    @Test
    public void shouldAnswerTheSameNotFoundForAViewThatExistsNowhere() {
        ModelAccessException refused = refusalOf(() -> bulk(op("create-view", Map.of("name", "Built"), "v"),
                label("no-such-view", TEMPLATE)));

        assertEquals("Operation 1 (set-view-label-expression): View not found: no-such-view", refused.getMessage());
        BulkOperationFailure row = ((BulkValidationException) refused).getFailures().get(0);
        assertEquals(ErrorCode.VIEW_NOT_FOUND.name(), row.errorCode());
        assertEquals("View not found: no-such-view", row.message());
        assertEquals("Use get-views to find valid view IDs", row.suggestedCorrection());
    }

    @Test
    public void shouldAnswerTheSameNotFoundInsideABatch() {
        f.dispatcher.beginBatch(SESSION, "queued view");
        f.accessor.createView(SESSION, "Queued", null, null, null);

        ModelAccessException refused = refusalOf(() -> bulk(label("no-such-view", TEMPLATE)));
        f.dispatcher.endBatch(SESSION, false);

        assertEquals("Operation 0 (set-view-label-expression): View not found: no-such-view", refused.getMessage());
        assertEquals(ErrorCode.VIEW_NOT_FOUND.name(),
                ((BulkValidationException) refused).getFailures().get(0).errorCode());
    }

    @Test
    public void shouldLabelWhatTheSameCallPlaces_onAViewItCreated() {
        BulkMutationResult result = bulk(op("create-view", Map.of("name", "Built"), "v"),
                place("$v.id", "el-a", 10, 10),
                label("$v.id", TEMPLATE));

        assertEquals(Integer.valueOf(1), result.operations().get(2).appliedCount());
        assertEquals(List.of("el-a=" + TEMPLATE), labels(view(result.operations().get(0).entityId())));
    }

    // ---- the objects stamped ------------------------------------------------------------------

    @Test
    public void shouldLabelAnObjectAnEarlierOperationPlaced() {
        BulkMutationResult result = bulk(place(f.view.getId(), "el-b", 300, 40), label(f.view.getId(), TEMPLATE));

        assertCounts(result.operations().get(1), 2, 0);
        assertEquals(List.of("el-a=" + TEMPLATE, "el-b=" + TEMPLATE), labels(f.view));
    }

    @Test
    public void shouldLabelAnObjectPlacedInsideAGroupTheSameCallAdded() {
        BulkMutationResult result = bulk(op("add-group-to-view", Map.of("viewId", f.view.getId(), "label", "G",
                "x", 300, "y", 200, "width", 300, "height", 200), "g"),
                op("add-to-view", Map.of("viewId", f.view.getId(), "elementId", "el-b", "parentViewObjectId", "$g.id",
                        "x", 20, "y", 30), null),
                label(f.view.getId(), TEMPLATE));

        assertCounts(result.operations().get(2), 2, 1);
        assertEquals(List.of("el-a=" + TEMPLATE, "el-b=" + TEMPLATE), labels(f.view));
    }

    @Test
    public void shouldNotLabelAnObjectAnEarlierOperationRemoves() {
        IDiagramModelObject removed = f.view.getChildren().get(0);

        BulkMutationResult result = bulk(place(f.view.getId(), "el-b", 300, 40),
                op("remove-from-view", Map.of("viewId", f.view.getId(), "viewObjectId", "vo-a"), null),
                label(f.view.getId(), TEMPLATE));

        assertCounts(result.operations().get(2), 1, 0);
        assertEquals(List.of("el-b=" + TEMPLATE), labels(f.view));
        assertNoLabel(removed);
    }

    @Test
    public void shouldNotLabelANestedObjectWhoseGroupAnEarlierOperationRemoves() {
        IDiagramModelContainer group = f.group(f.view, "grp", 300, 200, 300, 200);
        IDiagramModelObject nested = f.object(group, "vo-nested", f.element("el-d"), 20, 30, 120, 55);

        BulkMutationResult result = bulk(op("remove-from-view", Map.of("viewId", f.view.getId(), "viewObjectId", "grp"),
                null), label(f.view.getId(), TEMPLATE));

        assertCounts(result.operations().get(1), 1, 0);
        assertEquals(List.of("el-a=" + TEMPLATE), labels(f.view));
        assertNoLabel(nested);
    }

    /**
     * A delete lists the view objects of its element as the live model has them when it is prepared, so
     * it does not list the one placed earlier in the same call; but when it runs it removes every object
     * of its element, that one included, so the label never reaches it.
     */
    @Test
    public void shouldNotLabelAnObjectWhoseElementAnEarlierOperationDeletes() {
        BulkMutationResult result = bulk(place(f.view.getId(), "el-b", 300, 40),
                op("delete-element", Map.of("elementId", "el-b"), null),
                label(f.view.getId(), TEMPLATE));

        assertCounts(result.operations().get(2), 1, 0);
        assertEquals(List.of("el-a=" + TEMPLATE), labels(f.view));
    }

    @Test
    public void undo_shouldRestoreOnlyWhatTheCommandWrote_whenItNeverRan() {
        PreparedMutation<?> prepared = SetViewLabelExpressionCommand.prepare(f.model,
                Map.of("viewId", f.view.getId(), "labelExpression", TEMPLATE));

        prepared.command().undo();

        assertNoLabel(f.view.getChildren().get(0));
    }

    /**
     * Read as "live, plus added, minus what each removal names", this call would also label el-b: the
     * clear names only the objects on the view before the call. The clear runs after el-b is placed, so
     * only el-c survives it.
     */
    @Test
    public void shouldLabelOnlyWhatIsPlacedAfterAnEarlierClear() {
        BulkMutationResult result = bulk(place(f.view.getId(), "el-b", 300, 40),
                op("clear-view", Map.of("viewId", f.view.getId()), null),
                place(f.view.getId(), "el-c", 500, 40),
                label(f.view.getId(), TEMPLATE));

        assertCounts(result.operations().get(3), 1, 0);
        assertEquals(List.of("el-c=" + TEMPLATE), labels(f.view));
    }

    @Test
    public void shouldNotLabelWhatALaterOperationPlaces() {
        BulkMutationResult result = bulk(label(f.view.getId(), TEMPLATE), place(f.view.getId(), "el-b", 300, 40));

        assertCounts(result.operations().get(0), 1, 0);
        assertEquals(List.of("el-a=" + TEMPLATE, "el-b=null"), labels(f.view));
    }

    @Test
    public void shouldLabelWhatTheEnclosingBatchQueued() {
        f.dispatcher.beginBatch(SESSION, "queued placement");
        bulk(place(f.view.getId(), "el-b", 300, 40));
        assertEquals("el-b is only queued", 1, f.view.getChildren().size());

        BulkMutationResult result = bulk(label(f.view.getId(), TEMPLATE));
        f.dispatcher.endBatch(SESSION, true);

        assertCounts(result.operations().get(0), 2, 0);
        assertEquals(List.of("el-a=" + TEMPLATE, "el-b=" + TEMPLATE), labels(f.view));
    }

    @Test
    public void shouldCountAndApplyTheSameObjects_whenApprovalIsRequired() {
        f.approval = true;
        BulkMutationResult proposed = bulk(place(f.view.getId(), "el-b", 300, 40), label(f.view.getId(), TEMPLATE));
        assertNull("nothing applied yet", f.view.getChildren().get(0).getFeatures()
                .getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));

        f.dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());

        assertCounts(proposed.operations().get(1), 2, 0);
        assertEquals(List.of("el-a=" + TEMPLATE, "el-b=" + TEMPLATE), labels(f.view));
    }

    @Test
    public void shouldWarnOfAMissingPropertyOnAnObjectAnEarlierOperationPlaced() {
        com.archimatetool.model.IProperty key = f.factory.createProperty();
        key.setKey("Owner");
        key.setValue("team");
        ((IDiagramModelArchimateObject) f.view.getChildren().get(0)).getArchimateElement().getProperties().add(key);

        BulkMutationResult result = bulk(place(f.view.getId(), "el-b", 300, 40),
                label(f.view.getId(), "${property:Owner}"));

        String placed = result.operations().get(0).entityId();
        List<net.vheerden.archi.mcp.response.dto.StructuredWarningDto> warnings =
                result.operations().get(1).structuredWarnings();
        assertEquals(warnings.toString(), 1, warnings.size());
        assertContains(warnings.get(0).message(), "'Owner'");
        assertEquals(List.of(placed), warnings.get(0).remediationViolatorIds());
    }

    @Test
    public void shouldAnswerAsBefore_whenNothingIsStaged() {
        f.object(f.view, "vo-unnamed", f.element("el-e"), 300, 300, 120, 55).getArchimateElement().setName(" ");
        f.note(f.view, "note-a", 500, 300);

        BulkOperationResult labelled = bulk(label(f.view.getId(), TEMPLATE)).operations().get(0);

        assertEquals("view-source", labelled.entityId());
        assertEquals("Source", labelled.entityName());
        assertEquals("updated", labelled.action());
        assertCounts(labelled, 1, 2);
        assertEquals(List.of(), labelled.structuredWarnings());
        assertEquals(List.of("el-a=" + TEMPLATE, "el-e=null"), labels(f.view));
    }

    // ---- old values -------------------------------------------------------------------------------

    @Test
    public void shouldUndoToTheValueAnEarlierCommandWrote_notTheValueWhenBuilt() {
        IDiagramModelObject a = f.view.getChildren().get(0);
        a.getFeatures().putString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, "before");
        PreparedMutation<?> prepared = SetViewLabelExpressionCommand.prepare(f.model,
                Map.of("viewId", f.view.getId(), "labelExpression", "Y"));
        a.getFeatures().putString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, "X");

        prepared.command().execute();
        assertEquals("Y", a.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
        prepared.command().undo();

        assertEquals("X", a.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
    }

    @Test
    public void shouldUndoTheWholeCallToThePreCallValue() {
        IDiagramModelObject a = f.view.getChildren().get(0);
        a.getFeatures().putString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, "before");

        bulk(op("update-view-object", Map.of("viewObjectId", "vo-a", "labelExpression", "X"), null),
                label(f.view.getId(), "Y"));
        assertEquals("Y", a.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
        f.stack.undo();

        assertEquals("before", a.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
    }

    @Test
    public void shouldLeaveAPlacedObjectWithoutAnExpression_afterUndo() {
        bulk(place(f.view.getId(), "el-b", 300, 40), label(f.view.getId(), TEMPLATE));
        IDiagramModelObject placed = f.view.getChildren().get(1);

        f.stack.undo();

        assertEquals(1, f.view.getChildren().size());
        assertNoLabel(f.view.getChildren().get(0));
        assertNoLabel(placed);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    static void assertCounts(BulkOperationResult result, int applied, int skipped) {
        assertEquals("appliedCount", Integer.valueOf(applied), result.appliedCount());
        assertEquals("skippedCount", Integer.valueOf(skipped), result.skippedCount());
    }

    static BulkOperation op(String tool, Map<String, Object> params, String as) {
        return new BulkOperation(tool, params, as);
    }

    static BulkOperation place(String viewId, String elementId, int x, int y) {
        return op("add-to-view", Map.of("viewId", viewId, "elementId", elementId, "x", x, "y", y), null);
    }

    static BulkOperation label(String viewId, String template) {
        return op("set-view-label-expression", Map.of("viewId", viewId, "labelExpression", template), null);
    }

    BulkMutationResult bulk(BulkOperation... operations) {
        return f.accessor.executeBulk(SESSION, List.of(operations), "staged view", false, null);
    }

    static ModelAccessException refusalOf(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        throw new AssertionError("no refusal");
    }

    IArchimateDiagramModel view(String id) {
        return f.model.getDiagramModels().stream().filter(v -> v.getId().equals(id))
                .map(IArchimateDiagramModel.class::cast).findFirst().orElse(null);
    }

    /** Each element object on {@code container} at any depth, as {@code elementId=labelExpression}. */
    static List<String> labels(IDiagramModelContainer container) {
        List<String> out = new ArrayList<>();
        walk(container, out);
        return out;
    }

    private static void walk(IDiagramModelContainer container, List<String> out) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject object) {
                IArchimateElement element = object.getArchimateElement();
                out.add(element.getId() + "=" + object.getFeatures().getString(
                        UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
            }
            if (child instanceof IDiagramModelContainer nested) {
                walk(nested, out);
            }
        }
    }

    static void assertNoLabel(IDiagramModelObject object) {
        assertNull(object.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
    }

    static void assertContains(String text, String part) {
        assertTrue(text, text.contains(part));
    }
}
