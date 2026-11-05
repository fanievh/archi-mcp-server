package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins that the {@code tree}, {@code graph} and {@code summary} formats of {@code get-view-contents}
 * show the images and view references a view holds.
 *
 * <p>Before, none of the three did: the tree gave a group holding an image and a reference
 * {@code childCount: 0} while {@code layout-within-group} moved both, the graph had no node for either,
 * and the summary counted neither. The view here holds both kinds on the view itself, in a group and in
 * an element, beside a note, so each format is read against every place one can sit.</p>
 */
public class GetViewContentsFormatsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-view-contents-formats");
        IArchimateElement elA = f.element("el-a");
        IArchimateElement elB = f.element("el-b");
        IDiagramModelArchimateObject a = f.object(f.view, "vo-a", elA, 40, 40, 300, 200);
        IDiagramModelGroup group = f.group(f.view, "grp-1", 400, 40, 400, 300);
        f.object(group, "vo-b", elB, 20, 40, 120, 55);
        f.note(group, "n-1", 20, 120);
        f.image(group, "img-in-group", 200, 40).setDocumentation("caption");
        f.reference(group, "ref-in-group", f.other, 200, 150);
        f.image(a, "img-in-element", 5, 5);
        f.reference(f.view, "ref-top", f.other, 900, 40);
        f.reference(f.view, "ref-no-target", null, 900, 200);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- tree -------------------------------------------------------------------------------------

    @Test
    public void tree_shouldShowImagesAndReferencesUnderTheirParent() throws Exception {
        Map<String, Object> result = result(f.serve(Map.of("format", "tree")));

        assertEquals("root: the element, the two references on the view, then the group",
                List.of("vo-a:element", "ref-top:viewReference", "ref-no-target:viewReference", "grp-1:group"),
                ids(list(result, "tree")));
        Map<String, Object> group = node(list(result, "tree"), "grp-1");
        assertEquals(List.of("vo-b:element", "n-1:note", "img-in-group:image", "ref-in-group:viewReference"),
                ids(list(group, "children")));
        assertEquals("childCount is the children the tree shows", 4, group.get("childCount"));
        Map<String, Object> element = node(list(result, "tree"), "vo-a");
        assertEquals(List.of("img-in-element:image"), ids(list(element, "children")));
        assertEquals(1, element.get("childCount"));
    }

    @Test
    public void tree_shouldGiveAnImageNoLabel_andAReferenceItsTarget() throws Exception {
        Map<String, Object> result = result(f.serve(Map.of("format", "tree")));
        List<Map<String, Object>> children = list(node(list(result, "tree"), "grp-1"), "children");

        assertEquals("an image's documentation is free text, not a label",
                Map.of("viewObjectId", "img-in-group", "type", "image"), node(children, "img-in-group"));
        assertEquals(Map.of("viewObjectId", "ref-in-group", "type", "viewReference",
                "referencedViewId", "view-other"), node(children, "ref-in-group"));
        assertEquals("a reference storing no target has no referencedViewId",
                Map.of("viewObjectId", "ref-no-target", "type", "viewReference"),
                node(list(result, "tree"), "ref-no-target"));
    }

    @Test
    public void tree_shouldCountTheImagesAndReferencesItCarries_inItsStats() throws Exception {
        Map<String, Object> result = result(f.serve(Map.of("format", "tree")));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("totalGroups", 1);
        expected.put("topLevelGroups", 1);
        expected.put("nestedGroups", 0);
        expected.put("totalElements", 2);
        expected.put("totalNotes", 1);
        expected.put("totalImages", 2);
        expected.put("totalViewReferences", 3);
        expected.put("ungroupedElements", 1);
        assertEquals(expected, result.get("stats"));
    }

    // ---- graph ------------------------------------------------------------------------------------

    @Test
    public void graph_shouldCarryANodeForEachImageAndReference() throws Exception {
        List<Map<String, Object>> nodes = list(graph(f.serve(Map.of("format", "graph"))), "nodes");

        List<String> visual = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            if (node.get("_nodeType") != null) {
                visual.add(node.get("viewObjectId") + ":" + node.get("_nodeType"));
            }
        }
        assertEquals("in the json arrays' order, which is the walk's: depth first, so the element's image "
                + "before the group's", List.of("grp-1:group", "n-1:note", "img-in-element:image", "img-in-group:image",
                "ref-in-group:viewReference", "ref-top:viewReference", "ref-no-target:viewReference"), visual);
    }

    @Test
    public void graph_shouldCarryTheRowsTheJsonFormatReturns_onEachImageAndReferenceNode() throws Exception {
        Map<String, Object> json = result(f.serve(Map.of()));
        List<Map<String, Object>> nodes = list(graph(f.serve(Map.of("format", "graph"))), "nodes");

        Map<String, Object> image = new LinkedHashMap<>(node(list(json, "images"), "img-in-group"));
        image.put("_nodeType", "image");
        assertEquals(image, node(nodes, "img-in-group"));
        Map<String, Object> reference = new LinkedHashMap<>(node(list(json, "viewReferences"), "ref-in-group"));
        reference.put("_nodeType", "viewReference");
        assertEquals(reference, node(nodes, "ref-in-group"));
    }

    @Test
    public void graph_shouldDropTheNodes_whenTheArrayIsExcluded() throws Exception {
        List<Map<String, Object>> nodes = list(graph(f.serve(
                Map.of("format", "graph", "exclude", List.of("images", "viewReferences")))), "nodes");

        for (Map<String, Object> node : nodes) {
            assertFalse(String.valueOf(node), "image".equals(node.get("_nodeType"))
                    || "viewReference".equals(node.get("_nodeType")));
        }
    }

    // ---- summary ----------------------------------------------------------------------------------

    @Test
    public void summary_shouldCountImagesAndReferences_besideTheNotes() throws Exception {
        String summary = summary(f.serve(Map.of("format", "summary")));

        assertTrue(summary, summary.startsWith("View 'Source': 2 elements, 0 relationships, 0 connections. "
                + "Containers: 1 group. Visual annotations: 1 note, 2 images, 3 view references."));
    }

    @Test
    public void summary_shouldNameEachKindInTheSingular_whenTheViewHoldsOne() throws Exception {
        IDiagramModelGroup only = f.group(f.other, "grp-other", 0, 0, 300, 200);
        f.image(only, "img-other", 10, 10);
        f.reference(f.other, "ref-other", f.view, 400, 0);

        String summary = summary(f.serve(Map.of("viewId", "view-other", "format", "summary")));

        assertTrue(summary, summary.startsWith("View 'Other': 0 elements, 0 relationships, 0 connections. "
                + "Containers: 1 group. Visual annotations: 1 image, 1 view reference."));
    }

    // ---- harness ----------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(String served) throws Exception {
        assertFalse(served, served.startsWith("ERROR "));
        return (Map<String, Object>) JSON.readValue(served, Map.class).get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> graph(String served) throws Exception {
        assertFalse(served, served.startsWith("ERROR "));
        return (Map<String, Object>) JSON.readValue(served, Map.class).get("graph");
    }

    private static String summary(String served) throws Exception {
        assertFalse(served, served.startsWith("ERROR "));
        return String.valueOf(JSON.readValue(served, Map.class).get("summary"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    private static Map<String, Object> node(List<Map<String, Object>> nodes, String viewObjectId) {
        for (Map<String, Object> node : nodes) {
            if (viewObjectId.equals(node.get("viewObjectId"))) {
                return node;
            }
        }
        throw new AssertionError("no node " + viewObjectId + " in " + nodes);
    }

    private static List<String> ids(List<Map<String, Object>> nodes) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            ids.add(node.get("viewObjectId") + ":" + node.get("type"));
        }
        return ids;
    }
}
