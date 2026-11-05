package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.CostEstimatorAccess;

/**
 * Pins that the {@code get-view-contents} dry run prices the format the caller asked for.
 *
 * <p>Before, one estimate anchored on json was served for every format, on the reading that the other
 * three are narrower. A graph or tree response is not narrower by construction: each carries a longer
 * envelope than json, and a graph copies every group, note, image and view reference row whole with a
 * marker added, so on a view of annotations a graph serves more than json does. The pins hold the
 * estimate at or above the served length for every format on four views shaped to stress each array,
 * hold each excess constant to the served shape it was measured from, and hold a summary below json.</p>
 */
public class GetViewContentsDryRunFormatTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FORMATS = List.of("json", "graph", "summary", "tree");

    private ViewContentsFixture f;
    private final List<IArchimateDiagramModel> views = new ArrayList<>();
    private IArchimateDiagramModel empty;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-dry-run-format");
        IArchimateElement elA = f.element("el-a");
        IArchimateElement elB = f.element("el-b");
        IArchimateElement elC = f.element("el-c");
        IArchimateElement elD = f.element("el-d");

        // Elements, groups, notes and bent connections.
        IDiagramModelArchimateObject a = f.object(f.view, "vo-a", elA, 40, 40, 120, 55);
        IDiagramModelArchimateObject b = f.object(f.view, "vo-b", elB, 400, 40, 120, 55);
        IDiagramModelGroup outer = f.group(f.view, "g-outer", 600, 300, 400, 300);
        IDiagramModelArchimateObject c = f.object(outer, "vo-c", elC, 20, 40, 120, 55);
        IDiagramModelGroup inner = f.group(outer, "g-inner", 200, 40, 180, 200);
        IDiagramModelArchimateObject d = f.object(inner, "vo-d", elD, 20, 40, 120, 55);
        f.note(inner, "n-in", 20, 120);
        f.note(f.view, "n-top", 1100, 40);
        IArchimateRelationship relAb = f.relationship("rel-ab", elA, elB);
        IDiagramModelArchimateConnection ab = f.archimateConnection("conn-ab", relAb, a, b);
        ab.getBendpoints().add(f.bendpoint(100, 0, -260, 0));
        ab.getBendpoints().add(f.bendpoint(100, 40, -260, 40));
        f.archimateConnection("conn-cd", f.relationship("rel-cd", elC, elD), c, d);
        views.add(f.view);

        // Images at every depth.
        IArchimateDiagramModel images = f.view("view-images", "Images");
        IDiagramModelArchimateObject ia = f.object(images, "vo-ia", elA, 40, 40, 120, 55);
        f.image(images, "img-top", 300, 40);
        IDiagramModelGroup imageGroup = f.group(images, "g-images", 40, 200, 300, 200);
        f.image(imageGroup, "img-in-group", 20, 30).setDocumentation("caption");
        f.image(ia, "img-in-element", 5, 5);
        views.add(images);

        // References only, half of them styled and iconned: the view a graph serves widest.
        IArchimateDiagramModel references = f.view("view-references", "References");
        for (int i = 0; i < 20; i++) {
            IDiagramModelReference r = f.reference(references, "ref-" + i, f.other, 40 + 130 * (i % 5),
                    40 + 80 * (i / 5));
            if (i % 2 == 0) {
                r.setFillColor("#aabbcc");
                r.setLineStyle(1);
                r.setImagePath(ViewContentsFixture.IMAGE_PATH);
                r.setImagePosition(0);
                r.setIconVisibleState(1);
            }
        }
        views.add(references);

        // Plain connections, labelled and bent, between images.
        IArchimateDiagramModel plain = f.view("view-plain", "Plain");
        IDiagramModelImage previous = null;
        for (int i = 0; i < 12; i++) {
            IDiagramModelImage image = f.image(plain, "pimg-" + i, 40 + 120 * i, 40 + (i % 2) * 200);
            if (previous != null) {
                IDiagramModelConnection conn = f.plainConnection("pc-" + i, previous, image);
                conn.setName("label " + i);
                conn.getBendpoints().add(f.bendpoint(10, 0, -10, 0));
                conn.getBendpoints().add(f.bendpoint(10, 40, -10, 40));
                conn.setType(IDiagramModelConnection.ARROW_FILL_TARGET);
            }
            previous = image;
        }
        views.add(plain);

        // A one-char name: json carries the view's id and name and a graph does not, so the shorter they
        // are the more a graph's envelope exceeds json's. This is the shape the excess was measured at.
        empty = f.view("view-empty", "E");
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void dryRun_shouldPriceEveryFormat_atOrAboveWhatItServes() throws Exception {
        List<String> under = new ArrayList<>();
        for (IArchimateDiagramModel view : views) {
            for (String format : FORMATS) {
                int served = f.serve(Map.of("viewId", view.getId(), "format", format)).length();
                int estimated = estimate(view, format) * 4;
                if (estimated < served) {
                    under.add(view.getId() + "/" + format + ": estimate " + estimated + " < served " + served);
                }
            }
        }
        assertEquals(List.of(), under);
    }

    @Test
    public void dryRun_shouldServeTheJsonEstimate_forJsonAndSummary_andWithNoFormat() throws Exception {
        for (IArchimateDiagramModel view : views) {
            int json = estimate(view, "json");
            assertEquals(view.getId(), json, estimateWithNoFormat(view));
            assertEquals(view.getId(), json, estimate(view, "summary"));
        }
    }

    @Test
    public void dryRun_shouldChargeAGraphItsMarkersAndEnvelope_onTopOfTheJsonEstimate() throws Exception {
        IArchimateDiagramModel references = views.get(2);
        int chars = CostEstimatorAccess.graphEnvelopeExcess() + 20 * CostEstimatorAccess.graphReferenceMarker();

        assertEquals("tokens, rounded once over the whole payload", chars / 4.0,
                estimate(references, "graph") - estimate(references, "json"), 1);
        assertTrue("the graph serves more than json here, which is why it is priced apart",
                f.serve(Map.of("viewId", references.getId(), "format", "graph")).length()
                        > f.serve(Map.of("viewId", references.getId())).length());
    }

    @Test
    public void dryRun_shouldChargeATreeItsEnvelope_onTopOfTheJsonEstimate() throws Exception {
        for (IArchimateDiagramModel view : views) {
            assertEquals(view.getId(), CostEstimatorAccess.treeEnvelopeExcess() / 4.0,
                    estimate(view, "tree") - estimate(view, "json"), 1);
        }
    }

    @Test
    public void dryRun_shouldPriceATreeOnEveryArray_whenExcludeDropsThemFromJson() throws Exception {
        List<String> every = List.of("visualMetadata", "connections", "groups", "notes", "images",
                "viewReferences", "nonArchimateConnections");
        List<String> under = new ArrayList<>();
        for (IArchimateDiagramModel view : views) {
            int excluded = tokens(f.serve(Map.of("viewId", view.getId(), "format", "tree", "dryRun", true,
                    "exclude", every)));
            assertEquals("the tree ignores exclude, and so does its price: " + view.getId(),
                    estimate(view, "tree"), excluded);
            int served = f.serve(Map.of("viewId", view.getId(), "format", "tree", "exclude", every)).length();
            if (excluded * 4 < served) {
                under.add(view.getId() + ": estimate " + excluded * 4 + " < served " + served);
            }
        }
        assertEquals(List.of(), under);
        // json does follow exclude, so the same exclude does lower its price.
        IArchimateDiagramModel references = views.get(2);
        assertTrue(tokens(f.serve(Map.of("viewId", references.getId(), "dryRun", true, "exclude", every)))
                < estimate(references, "json"));
    }

    @Test
    public void graphEnvelopeExcess_shouldBeWhatAnEmptyGraphServesBeyondAnEmptyJson() {
        assertEquals(CostEstimatorAccess.graphEnvelopeExcess(),
                served(empty, "graph").length() - served(empty, "json").length());
    }

    @Test
    public void treeEnvelopeExcess_shouldCoverWhatAnEmptyTreeServesBeyondAnEmptyJson() {
        int excess = served(empty, "tree").length() - served(empty, "json").length();

        assertTrue("measured " + excess, excess <= CostEstimatorAccess.treeEnvelopeExcess());
        assertTrue("rounded up by less than five, not padded: measured " + excess,
                CostEstimatorAccess.treeEnvelopeExcess() - excess < 5);
    }

    @Test
    public void graphMarkers_shouldBeWhatEachGraphNodeAddsToItsJsonRow() throws Exception {
        Map<String, String> json = rows(served(views.get(0), "json"), "result",
                List.of("groups", "notes"));
        json.putAll(rows(served(views.get(1), "json"), "result", List.of("images")));
        json.putAll(rows(served(views.get(2), "json"), "result", List.of("viewReferences")));
        Map<String, String> graph = rows(served(views.get(0), "graph"), "graph", List.of("nodes"));
        graph.putAll(rows(served(views.get(1), "graph"), "graph", List.of("nodes")));
        graph.putAll(rows(served(views.get(2), "graph"), "graph", List.of("nodes")));

        assertEquals(CostEstimatorAccess.graphGroupMarker(), graph.get("g-inner").length() - json.get("g-inner").length());
        assertEquals(CostEstimatorAccess.graphNoteMarker(), graph.get("n-top").length() - json.get("n-top").length());
        assertEquals(CostEstimatorAccess.graphImageMarker(),
                graph.get("img-top").length() - json.get("img-top").length());
        assertEquals(CostEstimatorAccess.graphReferenceMarker(),
                graph.get("ref-0").length() - json.get("ref-0").length());
    }

    @Test
    public void summary_shouldServeNoMoreThanJson_evenOnAnEmptyView() {
        List<IArchimateDiagramModel> all = new ArrayList<>(views);
        all.add(empty);
        for (IArchimateDiagramModel view : all) {
            assertTrue(view.getId(), served(view, "summary").length() <= served(view, "json").length());
        }
    }

    // ---- harness ----------------------------------------------------------------------------------

    private String served(IArchimateDiagramModel view, String format) {
        return f.serve(Map.of("viewId", view.getId(), "format", format));
    }

    private int estimate(IArchimateDiagramModel view, String format) throws Exception {
        return tokens(f.serve(Map.of("viewId", view.getId(), "format", format, "dryRun", true)));
    }

    private int estimateWithNoFormat(IArchimateDiagramModel view) throws Exception {
        return tokens(f.serve(Map.of("viewId", view.getId(), "dryRun", true)));
    }

    @SuppressWarnings("unchecked")
    private static int tokens(String served) throws Exception {
        assertTrue(served, !served.startsWith("ERROR"));
        Map<String, Object> dryRun = (Map<String, Object>) JSON.readValue(served, Map.class).get("dryRun");
        return ((Number) dryRun.get("estimatedTokens")).intValue();
    }

    /** Each row of the named arrays, re-serialised in its served key order, by viewObjectId. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> rows(String served, String key, List<String> arrays) throws Exception {
        Map<String, Object> body = (Map<String, Object>) JSON.readValue(served, Map.class).get(key);
        Map<String, String> byId = new java.util.HashMap<>();
        for (String array : arrays) {
            for (Map<String, Object> row : (List<Map<String, Object>>) body.get(array)) {
                if (row.get("viewObjectId") != null && (row.get("_nodeType") != null || !key.equals("graph"))) {
                    byId.put((String) row.get("viewObjectId"), JSON.writeValueAsString(row));
                }
            }
        }
        return byId;
    }
}
