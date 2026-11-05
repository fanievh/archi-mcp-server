package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.ITextPosition;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;

/**
 * Pins that {@code get-view-contents} lists every view reference a view holds, in {@code viewReferences},
 * with the same row {@code add-view-reference-to-view} answers with.
 *
 * <p>Before, the walk had no arm for a view reference and dropped it silently: an agent that placed one
 * was told to read it back through this tool and found nothing, while a group holding one listed its id
 * among the group's children and nowhere else. Every expectation here is a literal read off the model
 * (or the add tool's own response), never a value the read path computes.</p>
 */
public class GetViewContentsViewReferencesTest {

    private static final StylingParams STYLED = new StylingParams("#112233", "#445566", "#778899", 200, 3, null,
            "left", null, "Arial", 11, "bold", "dashed", "top-bottom", null, Boolean.FALSE, 128, null);

    private ViewContentsFixture f;
    private IDiagramModelArchimateObject a;
    private IDiagramModelGroup group;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-view-contents-references");
        IArchimateElement elA = f.element("el-a");
        IArchimateElement elB = f.element("el-b");
        a = f.object(f.view, "vo-a", elA, 400, 0, 120, 60);
        f.object(f.view, "vo-b", elB, 700, 200, 120, 60);
        group = f.group(f.view, "grp-1", 0, 600, 400, 300);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- the list ---------------------------------------------------------------------------------

    @Test
    public void getViewContents_shouldListTheViewReference_whenTheViewHoldsOne() {
        String id = place(10, 400, null).viewObjectId();

        List<EmbeddedViewDto> rows = contents().viewReferences();

        assertEquals(1, rows.size());
        EmbeddedViewDto row = rows.get(0);
        assertEquals(id, row.viewObjectId());
        assertEquals("view-other", row.referencedViewId());
        assertEquals(10, row.x());
        assertEquals(400, row.y());
        assertEquals("the add tool's default size", 185, row.width());
        assertEquals(80, row.height());
        assertNull("a reference on the view itself has no parent", row.parentViewObjectId());
    }

    @Test
    public void getViewContents_shouldListTheReferenceUnderItsGroup_whenAGroupHoldsIt() {
        String id = place(20, 30, "grp-1").viewObjectId();

        EmbeddedViewDto row = only(contents().viewReferences());

        assertEquals(id, row.viewObjectId());
        assertEquals("grp-1", row.parentViewObjectId());
        assertEquals("relative to the group", 20, row.x());
        assertEquals(30, row.y());
        assertEquals("the group lists the same id as its child",
                List.of(id), contents().groups().get(0).childViewObjectIds());
    }

    @Test
    public void getViewContents_shouldListTheReferenceUnderItsElement_whenAnElementHoldsIt() {
        String id = place(5, 5, "vo-a").viewObjectId();

        EmbeddedViewDto row = only(contents().viewReferences());

        assertEquals(id, row.viewObjectId());
        assertEquals("vo-a", row.parentViewObjectId());
        assertEquals("relative to the element", 5, row.x());
        assertEquals(5, row.y());
        assertEquals(1, a.getChildren().size());
    }

    @Test
    public void getViewContents_shouldListEveryReferenceInDepthFirstOrder_whenSeveralContainersHoldThem() {
        f.reference(f.view, "ref-top", f.other, 900, 0);
        f.reference(group, "ref-in-group", f.other, 20, 30);
        f.reference(a, "ref-in-element", f.other, 5, 5);

        List<String> ids = new ArrayList<>();
        for (EmbeddedViewDto row : contents().viewReferences()) {
            ids.add(row.viewObjectId() + "<" + row.parentViewObjectId());
        }

        assertEquals("the view's children in order (vo-a and what it holds first, then the group's, then "
                + "the reference after the group)",
                List.of("ref-in-element<vo-a", "ref-in-group<grp-1", "ref-top<null"), ids);
    }

    @Test
    public void getViewContents_shouldListTheReferenceInNoOtherArray_whenTheViewHoldsOne() {
        String id = place(10, 400, null).viewObjectId();
        f.reference(group, "ref-in-group", f.other, 20, 30);

        ViewContentsDto contents = contents();
        String elsewhere = String.valueOf(contents.visualMetadata()) + contents.connections()
                + contents.notes() + contents.images();

        assertFalse(elsewhere.contains(id));
        assertFalse(elsewhere.contains("ref-in-group"));
        assertNull(contents.notes());
        assertNull(contents.images());
        assertEquals(2, contents.visualMetadata().size());
    }

    @Test
    public void getViewContents_shouldOmitTheList_whenTheViewHoldsNoReference() {
        assertNull("absent, not empty, so a view without one reads as it always did",
                contents().viewReferences());
        assertFalse(f.serve(Map.of()).contains("viewReferences"));
    }

    // ---- one shape: the row the add tool answers with ------------------------------------------------

    @Test
    public void getViewContents_shouldReadBackTheRowTheAddToolAnswered_whenTheReferenceIsStyled() {
        EmbeddedViewDto added = place(900, 400, null, STYLED);

        EmbeddedViewDto row = only(contents().viewReferences());

        assertEquals(withoutWarnings(added), row);
        assertEquals("#112233", row.fillColor());
        assertEquals("#445566", row.lineColor());
        assertEquals("#778899", row.fontColor());
        assertEquals(Integer.valueOf(200), row.opacity());
        assertEquals(Integer.valueOf(3), row.lineWidth());
        assertEquals("Arial", row.fontName());
        assertEquals(Integer.valueOf(11), row.fontSize());
        assertEquals("bold", row.fontStyle());
        assertEquals("top-bottom", row.gradient());
        assertEquals(Boolean.FALSE, row.deriveLineColor());
        assertEquals(Integer.valueOf(128), row.outlineOpacity());
        assertEquals("dashed", row.lineStyle());
        assertEquals("left", row.textAlignment());
        assertNull(row.note());
        assertNull("a read reports what is on the view, never a placement-time disclosure",
                row.structuredWarnings());
    }

    @Test
    public void addViewReferenceToView_shouldAnswerTheSameStyledRow_whenAppliedImmediately() throws Exception {
        assertEquals(ADDED_STYLED, masked(place(900, 400, null, STYLED)));
    }

    @Test
    public void addViewReferenceToView_shouldAnswerTheSameStyledRow_whenApprovalIsRequired() throws Exception {
        f.approval = true;
        MutationResult<EmbeddedViewDto> proposed = f.accessor.addViewReferenceToView(ViewContentsFixture.SESSION,
                f.view.getId(), f.other.getId(), 900, 400, 150, 70, null, STYLED);

        assertTrue(proposed.isProposal());
        assertEquals(ADDED_STYLED, masked(proposed.entity()));
    }

    @Test
    public void addViewReferenceToView_shouldAnswerTheSameStyledRow_whenQueuedInABatch() throws Exception {
        f.dispatcher.beginBatch(ViewContentsFixture.SESSION, "styled reference");
        MutationResult<EmbeddedViewDto> queued = f.accessor.addViewReferenceToView(ViewContentsFixture.SESSION,
                f.view.getId(), f.other.getId(), 900, 400, 150, 70, null, STYLED);

        assertTrue(queued.isBatched());
        assertEquals(ADDED_STYLED, masked(queued.entity()));
    }

    // ---- the icon and the text position --------------------------------------------------------------

    @Test
    public void getViewContents_shouldReadBackTheIcon_spelledAsAnElementRowSpellsIt() {
        String id = place(10, 400, null).viewObjectId();
        for (String target : List.of(id, "vo-a")) {
            assertFalse(f.serve("update-view-object", Map.of("viewObjectId", target,
                    "imagePath", ViewContentsFixture.IMAGE_PATH, "imagePosition", "bottom-left",
                    "showIcon", "always")).startsWith("ERROR"));
        }

        EmbeddedViewDto row = only(contents().viewReferences());
        ViewNodeDto element = contents().visualMetadata().get(0);

        assertEquals("vo-a", element.viewObjectId());
        assertEquals(ViewContentsFixture.IMAGE_PATH, row.imagePath());
        assertEquals(element.imagePath(), row.imagePath());
        assertEquals("bottom-left", row.imagePosition());
        assertEquals(element.imagePosition(), row.imagePosition());
        assertEquals("always", row.showIcon());
        assertEquals(element.showIcon(), row.showIcon());
        assertTrue(f.serve(Map.of()).contains("\"viewReferences\":[{\"viewObjectId\":\""
                + id + "\",\"referencedViewId\":\"view-other\",\"x\":10,\"y\":400,\"width\":185,\"height\":80,"
                + "\"imagePath\":\"images/logo.png\",\"imagePosition\":\"bottom-left\",\"showIcon\":\"always\"}]"));
    }

    @Test
    public void graph_shouldCarryTheIcon_onTheReferenceNode() throws Exception {
        IDiagramModelReference reference = f.reference(f.view, "ref-icon", f.other, 900, 0);
        reference.setImagePath(ViewContentsFixture.IMAGE_PATH);
        reference.setImagePosition(0);
        reference.setIconVisibleState(1);

        String graph = f.serve(Map.of("format", "graph"));

        assertTrue(graph, graph.contains("{\"viewObjectId\":\"ref-icon\",\"referencedViewId\":\"view-other\","
                + "\"x\":900,\"y\":0,\"width\":120,\"height\":55,\"imagePath\":\"images/logo.png\","
                + "\"imagePosition\":\"top-left\",\"showIcon\":\"always\",\"_nodeType\":\"viewReference\"}"));
    }

    @Test
    public void getViewContents_shouldReadBackTheLabelExpression_spelledAsAnElementRowSpellsIt() {
        // update-view-object writes a label expression onto a reference and reads it back on its own
        // answer; the reference's row on this tool used to drop it, so the only read of a template
        // the tool had just accepted said there was none.
        String id = place(10, 400, null).viewObjectId();
        for (String target : List.of(id, "vo-a")) {
            assertFalse(f.serve("update-view-object", Map.of("viewObjectId", target,
                    "labelExpression", "${name} (view)")).startsWith("ERROR"));
        }

        EmbeddedViewDto row = only(contents().viewReferences());
        ViewNodeDto element = contents().visualMetadata().get(0);

        assertEquals("${name} (view)", row.labelExpression());
        assertEquals(element.labelExpression(), row.labelExpression());
        assertTrue(f.serve(Map.of()).contains("\"viewReferences\":[{\"viewObjectId\":\""
                + id + "\",\"referencedViewId\":\"view-other\",\"x\":10,\"y\":400,\"width\":185,\"height\":80,"
                + "\"labelExpression\":\"${name} (view)\"}]"));
    }

    @Test
    public void graph_shouldCarryTheLabelExpression_onTheReferenceNode() throws Exception {
        IDiagramModelReference reference = f.reference(f.view, "ref-label", f.other, 900, 0);
        reference.getFeatures().putString("labelExpression", "${property:Owner}");

        String graph = f.serve(Map.of("format", "graph"));

        assertTrue(graph, graph.contains("{\"viewObjectId\":\"ref-label\",\"referencedViewId\":\"view-other\","
                + "\"x\":900,\"y\":0,\"width\":120,\"height\":55,\"labelExpression\":\"${property:Owner}\","
                + "\"_nodeType\":\"viewReference\"}"));
    }

    @Test
    public void getViewContents_shouldOmitTheLabelExpression_whenTheReferenceHasNone() {
        f.reference(f.view, "ref-bare", f.other, 900, 0).getFeatures().putString("labelExpression", "");

        assertNull("an empty stored value is no template", only(contents().viewReferences()).labelExpression());
        assertFalse(f.serve(Map.of()).contains("labelExpression\":\"\""));
    }

    @Test
    public void getViewContents_shouldReadBackAnIconSetInABatch_afterTheBatchCommits() {
        String id = place(10, 400, null).viewObjectId();
        f.dispatcher.beginBatch(ViewContentsFixture.SESSION, "icon");
        assertTrue(f.accessor.updateViewObject(ViewContentsFixture.SESSION, id, null, null, null, null, null, null,
                new ImageParams(ViewContentsFixture.IMAGE_PATH, null, null), null).isBatched());
        assertNull("the read sees the committed model", only(contents().viewReferences()).imagePath());

        f.dispatcher.endBatch(ViewContentsFixture.SESSION, true);

        assertEquals(ViewContentsFixture.IMAGE_PATH, only(contents().viewReferences()).imagePath());
    }

    @Test
    public void getViewContents_shouldReportArchisTextPosition_asVerticalTextAlignment() {
        IDiagramModelReference reference = f.reference(f.view, "ref-bottom", f.other, 900, 0);
        reference.setTextPosition(ITextPosition.TEXT_POSITION_BOTTOM);

        assertEquals("bottom", only(contents().viewReferences()).verticalTextAlignment());
    }

    // ---- what the model holds ------------------------------------------------------------------------

    @Test
    public void getViewContents_shouldReportTheStoredTarget_whenTheReferencedViewIsNoLongerInTheModel() {
        IArchimateDiagramModel gone = f.factory.createArchimateDiagramModel();
        gone.setId("view-gone");
        f.reference(f.view, "ref-dangling", gone, 1200, 0);

        EmbeddedViewDto row = only(contents().viewReferences());

        assertEquals("ref-dangling", row.viewObjectId());
        assertEquals("view-gone", row.referencedViewId());
    }

    @Test
    public void getViewContents_shouldOmitTheTarget_whenTheReferenceHoldsNone() {
        f.reference(f.view, "ref-null", null, 1200, 100);

        EmbeddedViewDto row = only(contents().viewReferences());

        assertEquals("ref-null", row.viewObjectId());
        assertNull(row.referencedViewId());
        assertFalse(f.serve(Map.of()).contains("\"referencedViewId\""));
    }

    @Test
    public void getViewContents_shouldNameTheViewItself_whenTheReferencePointsAtTheViewHoldingIt() {
        String id = f.accessor.addViewReferenceToView(ViewContentsFixture.SESSION, f.view.getId(), f.view.getId(),
                600, 400, 100, 50, null, null).entity().viewObjectId();

        EmbeddedViewDto row = only(contents().viewReferences());

        assertEquals(id, row.viewObjectId());
        assertEquals("view-source", row.referencedViewId());
    }

    @Test
    public void getViewContents_shouldListTheReferenceAfterCommit_whenABatchQueuedIt() throws Exception {
        f.dispatcher.beginBatch(ViewContentsFixture.SESSION, "queued reference");
        f.accessor.addViewReferenceToView(ViewContentsFixture.SESSION, f.view.getId(), f.other.getId(), 10, 400,
                120, 55, null, null);

        assertNull("the read sees the committed model", contents().viewReferences());

        f.dispatcher.endBatch(ViewContentsFixture.SESSION, true);
        assertEquals(1, contents().viewReferences().size());
    }

    // ---- the served tool ----------------------------------------------------------------------------

    @Test
    public void getViewContents_shouldServeTheReference_whereTheAddToolSaysToLookForIt() {
        String served = f.serve("add-view-reference-to-view", Map.of("viewId", f.view.getId(),
                "referencedViewId", f.other.getId(), "x", 10, "y", 400));
        String id = only(contents().viewReferences()).viewObjectId();

        assertTrue(served, served.contains("Use get-view-contents to see the full target view including the new "
                + "view-reference (viewObjectId='" + id + "')."));
        assertTrue(f.serve(Map.of()).contains("\"viewReferences\":[{\"viewObjectId\":\"" + id
                + "\",\"referencedViewId\":\"view-other\",\"x\":10,\"y\":400,\"width\":185,\"height\":80}]"));
    }

    @Test
    public void getViewContents_shouldDropTheList_whenTheCallerExcludesIt() {
        place(10, 400, null);

        String served = f.serve(Map.of("exclude", List.of("viewReferences")));

        assertFalse(served, served.startsWith("ERROR"));
        assertFalse(served.contains("viewReferences"));
        assertTrue("the other arrays stay", served.contains("\"visualMetadata\""));
    }

    @Test
    public void getViewContents_shouldNameTheListAmongTheValidExcludes_whenAnExcludeIsUnknown() {
        String served = f.serve(Map.of("exclude", List.of("viewReference")));

        assertTrue(served, served.startsWith("ERROR"));
        assertTrue(served, served.contains("Valid exclude fields: ") && served.contains("type, viewReferences, "));
    }

    @Test
    public void getViewContents_shouldChargeEachReference_whenSizingTheResponse() {
        int without = estimate(Map.of("dryRun", true));
        place(10, 400, null);
        f.reference(group, "ref-in-group", f.other, 20, 30);

        int with = estimate(Map.of("dryRun", true));
        int excluded = estimate(Map.of("dryRun", true, "exclude", List.of("viewReferences")));

        // Two rows at the published 250-char width, four chars to the token; the group row is charged
        // at a flat width, so the child id it now lists adds nothing. One token either way is the
        // rounding of the whole payload's character count.
        assertEquals(without + 2 * 250 / 4, with, 1);
        assertEquals("nothing for an array the caller dropped", without, excluded);
    }

    // ---- harness ----------------------------------------------------------------------------------

    private EmbeddedViewDto place(int x, int y, String parent) {
        return f.accessor.addViewReferenceToView(ViewContentsFixture.SESSION, f.view.getId(), f.other.getId(), x, y,
                null, null, parent, null).entity();
    }

    private EmbeddedViewDto place(int x, int y, String parent, StylingParams styling) {
        return f.accessor.addViewReferenceToView(ViewContentsFixture.SESSION, f.view.getId(), f.other.getId(), x, y,
                150, 70, parent, styling).entity();
    }

    private ViewContentsDto contents() {
        return f.accessor.getViewContents(f.view.getId()).orElseThrow();
    }

    private static <T> T only(List<T> rows) {
        assertEquals(String.valueOf(rows), 1, rows == null ? 0 : rows.size());
        return rows.get(0);
    }

    private static EmbeddedViewDto withoutWarnings(EmbeddedViewDto d) {
        return new EmbeddedViewDto(d.viewObjectId(), d.referencedViewId(), d.x(), d.y(), d.width(), d.height(),
                d.parentViewObjectId(), d.fillColor(), d.lineColor(), d.fontColor(), d.opacity(), d.lineWidth(),
                d.fontName(), d.fontSize(), d.fontStyle(), d.gradient(), d.deriveLineColor(), d.outlineOpacity(),
                d.lineStyle(), d.textAlignment(), d.verticalTextAlignment(),
                d.imagePath(), d.imagePosition(), d.showIcon(), d.note(), null, d.labelExpression());
    }

    private static String masked(EmbeddedViewDto dto) throws Exception {
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper.writeValueAsString(dto).replaceFirst("\"viewObjectId\":\"[^\"]*\"", "\"viewObjectId\":\"_\"");
    }

    private int estimate(Map<String, Object> args) {
        String served = f.serve(args);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"estimatedTokens\":([0-9]+)").matcher(served);
        assertTrue(served, m.find());
        return Integer.parseInt(m.group(1));
    }

    /** The styled reference's add response, recorded before the add path and the read shared a mapper. */
    private static final String ADDED_STYLED =
            "{\"viewObjectId\":\"_\",\"referencedViewId\":\"view-other\",\"x\":900,\"y\":400,\"width\":150,\"height\":7"
            + "0,\"fillColor\":\"#112233\",\"lineColor\":\"#445566\",\"fontColor\":\"#778899\",\"opacity\":200,\"lineWid"
            + "th\":3,\"fontName\":\"Arial\",\"fontSize\":11,\"fontStyle\":\"bold\",\"gradient\":\"top-bottom\",\"deriveL"
            + "ineColor\":false,\"outlineOpacity\":128,\"lineStyle\":\"dashed\",\"textAlignment\":\"left\"}";
}
