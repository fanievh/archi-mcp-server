package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;

/**
 * Pins the served {@code get-view-contents} text, byte for byte, on views that hold none of the objects
 * or connections the read used to leave out (view references, connections that are not ArchiMate ones,
 * connections drawn from another connection).
 *
 * <p>Those views must read exactly as they always did in every format: the arrays the read gained are
 * absent when a view holds nothing of their kind, and the walk that finds them visits the objects and
 * connections it always visited in the same order. The expected strings were recorded from the build
 * before the walk moved out of the accessor and before any of those arrays existed.</p>
 */
public class GetViewContentsByteIdentityTest {

    private ViewContentsFixture f;
    private IArchimateDiagramModel imageView;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-view-contents-identity");
        IArchimateElement elA = f.element("el-a");
        IArchimateElement elB = f.element("el-b");
        IArchimateElement elC = f.element("el-c");
        IArchimateElement elD = f.element("el-d");
        IArchimateElement elE = f.element("el-e");
        IArchimateElement elHost = f.element("el-host");
        IArchimateElement grouping = f.factory.createGrouping();
        grouping.setId("el-grouping");
        grouping.setName("Grouping");
        f.model.getFolder(FolderType.OTHER).getElements().add(grouping);
        IArchimateElement elF = f.element("el-f");

        IDiagramModelArchimateObject a = f.object(f.view, "vo-a", elA, 40, 40, 120, 55);
        a.setFillColor("#ffeecc");
        IDiagramModelArchimateObject b = f.object(f.view, "vo-b", elB, 400, 40, 120, 55);
        IDiagramModelArchimateObject a2 = f.object(f.view, "vo-a2", elA, 40, 300, 120, 55);
        IDiagramModelGroup outer = f.group(f.view, "g-outer", 600, 300, 400, 300);
        IDiagramModelArchimateObject c = f.object(outer, "vo-c", elC, 20, 40, 120, 55);
        IDiagramModelGroup inner = f.group(outer, "g-inner", 200, 40, 180, 200);
        IDiagramModelArchimateObject d = f.object(inner, "vo-d", elD, 20, 40, 120, 55);
        f.note(inner, "n-in", 20, 120);
        IDiagramModelArchimateObject host = f.object(f.view, "vo-host", elHost, 40, 600, 300, 200);
        IDiagramModelArchimateObject e = f.object(host, "vo-e", elE, 30, 40, 120, 55);
        IDiagramModelArchimateObject groupingObject = f.object(f.view, "vo-grouping", grouping, 400, 600, 300, 200);
        f.object(groupingObject, "vo-f", elF, 20, 40, 120, 55);
        f.note(f.view, "n-top", 1100, 40);

        IArchimateRelationship relAb = f.relationship("rel-ab", elA, elB);
        IDiagramModelArchimateConnection ab = f.archimateConnection("conn-ab", relAb, a, b);
        ab.getBendpoints().add(f.bendpoint(100, 0, -260, 0));
        ab.getBendpoints().add(f.bendpoint(100, 40, -260, 40));
        ab.setLineColor("#ff0000");
        ab.setLineWidth(2);
        f.archimateConnection("conn-cd", f.relationship("rel-cd", elC, elD), c, d);
        f.archimateConnection("conn-eb", f.relationship("rel-eb", elE, elB), e, b);
        f.archimateConnection("conn-a2host", f.relationship("rel-ahost", elA, elHost), a2, host);
        f.archimateConnection("conn-a2b", relAb, a2, b);

        imageView = f.view("view-images", "Images");
        IDiagramModelArchimateObject ia = f.object(imageView, "vo-ia", elA, 40, 40, 120, 55);
        f.image(imageView, "img-top", 300, 40);
        IDiagramModelGroup imageGroup = f.group(imageView, "g-images", 40, 200, 300, 200);
        f.image(imageGroup, "img-in-group", 20, 30).setDocumentation("caption");
        f.image(ia, "img-in-element", 5, 5);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void getViewContents_shouldServeTheSameJson_whenTheViewHoldsNoneOfTheKindsTheReadGained() {
        assertEquals(JSON, f.serve(Map.of()));
    }

    @Test
    public void getViewContents_shouldServeTheSameTree_whenTheViewHoldsNoneOfTheKindsTheReadGained() {
        assertEquals(TREE, f.serve(Map.of("format", "tree")));
    }

    @Test
    public void getViewContents_shouldServeTheSameGraph_whenTheViewHoldsNoneOfTheKindsTheReadGained() {
        assertEquals(GRAPH, f.serve(Map.of("format", "graph")));
    }

    @Test
    public void getViewContents_shouldServeTheSameSummary_whenTheViewHoldsNoneOfTheKindsTheReadGained() {
        assertEquals(SUMMARY, f.serve(Map.of("format", "summary")));
    }

    @Test
    public void getViewContents_shouldServeTheSameEstimate_whenTheViewHoldsNoneOfTheKindsTheReadGained() {
        assertEquals(DRY_RUN, f.serve(Map.of("dryRun", true)));
        assertEquals(DRY_RUN_EXCLUDED,
                f.serve(Map.of("dryRun", true, "exclude", List.of("visualMetadata", "connections"))));
    }

    @Test
    public void getViewContents_shouldServeTheSameJson_whenTheViewHoldsImagesAtEveryDepth() {
        assertEquals(IMAGES_JSON, f.serve(Map.of("viewId", imageView.getId())));
    }

    private static final String JSON =
            "{\"result\":{\"viewId\":\"view-source\",\"viewName\":\"Source\",\"elements\":[{\"id\":\"el-a\",\"name\":\"el-a\",\"ty"
            + "pe\":\"ApplicationComponent\",\"layer\":\"Application\"},{\"id\":\"el-b\",\"name\":\"el-b\",\"type\":\"Application"
            + "Component\",\"layer\":\"Application\"},{\"id\":\"el-c\",\"name\":\"el-c\",\"type\":\"ApplicationComponent\",\"laye"
            + "r\":\"Application\"},{\"id\":\"el-d\",\"name\":\"el-d\",\"type\":\"ApplicationComponent\",\"layer\":\"Application\""
            + "},{\"id\":\"el-host\",\"name\":\"el-host\",\"type\":\"ApplicationComponent\",\"layer\":\"Application\"},{\"id\":\"e"
            + "l-e\",\"name\":\"el-e\",\"type\":\"ApplicationComponent\",\"layer\":\"Application\"},{\"id\":\"el-grouping\",\"nam"
            + "e\":\"Grouping\",\"type\":\"Grouping\",\"layer\":\"Other\"},{\"id\":\"el-f\",\"name\":\"el-f\",\"type\":\"ApplicationC"
            + "omponent\",\"layer\":\"Application\"}],\"relationships\":[{\"id\":\"rel-ab\",\"name\":\"\",\"type\":\"AssociationR"
            + "elationship\",\"sourceId\":\"el-a\",\"targetId\":\"el-b\",\"associationDirected\":false},{\"id\":\"rel-ahost\","
            + "\"name\":\"\",\"type\":\"AssociationRelationship\",\"sourceId\":\"el-a\",\"targetId\":\"el-host\",\"associationDi"
            + "rected\":false},{\"id\":\"rel-cd\",\"name\":\"\",\"type\":\"AssociationRelationship\",\"sourceId\":\"el-c\",\"targ"
            + "etId\":\"el-d\",\"associationDirected\":false},{\"id\":\"rel-eb\",\"name\":\"\",\"type\":\"AssociationRelationsh"
            + "ip\",\"sourceId\":\"el-e\",\"targetId\":\"el-b\",\"associationDirected\":false}],\"visualMetadata\":[{\"viewOb"
            + "jectId\":\"vo-a\",\"elementId\":\"el-a\",\"x\":40,\"y\":40,\"width\":120,\"height\":55,\"fillColor\":\"#ffeecc\"},{"
            + "\"viewObjectId\":\"vo-b\",\"elementId\":\"el-b\",\"x\":400,\"y\":40,\"width\":120,\"height\":55},{\"viewObjectId\""
            + ":\"vo-a2\",\"elementId\":\"el-a\",\"x\":40,\"y\":300,\"width\":120,\"height\":55},{\"viewObjectId\":\"vo-c\",\"elem"
            + "entId\":\"el-c\",\"x\":20,\"y\":40,\"width\":120,\"height\":55,\"parentViewObjectId\":\"g-outer\"},{\"viewObject"
            + "Id\":\"vo-d\",\"elementId\":\"el-d\",\"x\":20,\"y\":40,\"width\":120,\"height\":55,\"parentViewObjectId\":\"g-inne"
            + "r\"},{\"viewObjectId\":\"vo-host\",\"elementId\":\"el-host\",\"x\":40,\"y\":600,\"width\":300,\"height\":200},{\"v"
            + "iewObjectId\":\"vo-e\",\"elementId\":\"el-e\",\"x\":30,\"y\":40,\"width\":120,\"height\":55,\"parentViewObjectId"
            + "\":\"vo-host\"},{\"viewObjectId\":\"vo-grouping\",\"elementId\":\"el-grouping\",\"x\":400,\"y\":600,\"width\":300"
            + ",\"height\":200},{\"viewObjectId\":\"vo-f\",\"elementId\":\"el-f\",\"x\":20,\"y\":40,\"width\":120,\"height\":55,\""
            + "parentViewObjectId\":\"vo-grouping\"}],\"connections\":[{\"viewConnectionId\":\"conn-ab\",\"relationshipId"
            + "\":\"rel-ab\",\"relationshipType\":\"AssociationRelationship\",\"sourceViewObjectId\":\"vo-a\",\"targetViewO"
            + "bjectId\":\"vo-b\",\"bendpoints\":[{\"startX\":100,\"startY\":0,\"endX\":-260,\"endY\":0},{\"startX\":100,\"star"
            + "tY\":40,\"endX\":-260,\"endY\":40}],\"absoluteBendpoints\":[{\"x\":200,\"y\":67},{\"x\":200,\"y\":107}],\"source"
            + "Anchor\":{\"x\":100,\"y\":67},\"targetAnchor\":{\"x\":460,\"y\":67},\"textPosition\":1,\"lineColor\":\"#ff0000\","
            + "\"lineWidth\":2,\"sourceRenderFace\":\"right\"},{\"viewConnectionId\":\"conn-a2host\",\"relationshipId\":\"re"
            + "l-ahost\",\"relationshipType\":\"AssociationRelationship\",\"sourceViewObjectId\":\"vo-a2\",\"targetViewOb"
            + "jectId\":\"vo-host\",\"sourceAnchor\":{\"x\":100,\"y\":327},\"targetAnchor\":{\"x\":190,\"y\":700},\"textPositio"
            + "n\":1,\"sourceRenderFace\":\"bottom\",\"targetRenderFace\":\"top\"},{\"viewConnectionId\":\"conn-a2b\",\"relat"
            + "ionshipId\":\"rel-ab\",\"relationshipType\":\"AssociationRelationship\",\"sourceViewObjectId\":\"vo-a2\",\"t"
            + "argetViewObjectId\":\"vo-b\",\"sourceAnchor\":{\"x\":100,\"y\":327},\"targetAnchor\":{\"x\":460,\"y\":67},\"text"
            + "Position\":1},{\"viewConnectionId\":\"conn-cd\",\"relationshipId\":\"rel-cd\",\"relationshipType\":\"Associa"
            + "tionRelationship\",\"sourceViewObjectId\":\"vo-c\",\"targetViewObjectId\":\"vo-d\",\"sourceAnchor\":{\"x\":68"
            + "0,\"y\":367},\"targetAnchor\":{\"x\":880,\"y\":407},\"textPosition\":1,\"sourceRenderFace\":\"right\",\"targetR"
            + "enderFace\":\"left\"},{\"viewConnectionId\":\"conn-eb\",\"relationshipId\":\"rel-eb\",\"relationshipType\":\"A"
            + "ssociationRelationship\",\"sourceViewObjectId\":\"vo-e\",\"targetViewObjectId\":\"vo-b\",\"sourceAnchor\":{"
            + "\"x\":130,\"y\":667},\"targetAnchor\":{\"x\":460,\"y\":67},\"textPosition\":1}],\"groups\":[{\"viewObjectId\":\"g"
            + "-outer\",\"label\":\"g-outer\",\"x\":600,\"y\":300,\"width\":400,\"height\":300,\"childViewObjectIds\":[\"vo-c\","
            + "\"g-inner\"]},{\"viewObjectId\":\"g-inner\",\"label\":\"g-inner\",\"x\":200,\"y\":40,\"width\":180,\"height\":200,"
            + "\"parentViewObjectId\":\"g-outer\",\"childViewObjectIds\":[\"vo-d\",\"n-in\"]}],\"notes\":[{\"viewObjectId\":\""
            + "n-in\",\"content\":\"n-in\",\"x\":20,\"y\":120,\"width\":100,\"height\":50,\"parentViewObjectId\":\"g-inner\"},{\""
            + "viewObjectId\":\"n-top\",\"content\":\"n-top\",\"x\":1100,\"y\":40,\"width\":100,\"height\":50}]},\"nextSteps\":["
            + "\"Use get-element with an element ID for detailed information\",\"Use get-relationships with an ele"
            + "ment ID to explore connections beyond this view\"],\"_meta\":{\"modelVersion\":\"_\",\"resultCount\":12,\""
            + "isTruncated\":false,\"totalCount\":12,\"sessionActive\":true}}";
    private static final String TREE =
            "{\"result\":{\"viewId\":\"view-source\",\"viewName\":\"Source\",\"tree\":[{\"viewObjectId\":\"vo-a\",\"type\":\"ele"
            + "ment\",\"name\":\"el-a\",\"elementType\":\"ApplicationComponent\"},{\"viewObjectId\":\"vo-b\",\"type\":\"element"
            + "\",\"name\":\"el-b\",\"elementType\":\"ApplicationComponent\"},{\"viewObjectId\":\"vo-a2\",\"type\":\"element\",\""
            + "name\":\"el-a\",\"elementType\":\"ApplicationComponent\"},{\"viewObjectId\":\"vo-host\",\"type\":\"element\",\"n"
            + "ame\":\"el-host\",\"elementType\":\"ApplicationComponent\",\"childCount\":1,\"children\":[{\"viewObjectId\":\""
            + "vo-e\",\"type\":\"element\",\"name\":\"el-e\",\"elementType\":\"ApplicationComponent\"}]},{\"viewObjectId\":\"vo"
            + "-grouping\",\"type\":\"element\",\"name\":\"Grouping\",\"elementType\":\"Grouping\",\"isGroup\":true,\"childCoun"
            + "t\":1,\"children\":[{\"viewObjectId\":\"vo-f\",\"type\":\"element\",\"name\":\"el-f\",\"elementType\":\"Applicatio"
            + "nComponent\"}]},{\"viewObjectId\":\"n-top\",\"type\":\"note\",\"label\":\"n-top\"},{\"viewObjectId\":\"g-outer\","
            + "\"type\":\"group\",\"label\":\"g-outer\",\"isGroup\":true,\"childCount\":2,\"children\":[{\"viewObjectId\":\"vo-c"
            + "\",\"type\":\"element\",\"name\":\"el-c\",\"elementType\":\"ApplicationComponent\"},{\"viewObjectId\":\"g-inner\""
            + ",\"type\":\"group\",\"label\":\"g-inner\",\"isGroup\":true,\"childCount\":2,\"children\":[{\"viewObjectId\":\"vo-"
            + "d\",\"type\":\"element\",\"name\":\"el-d\",\"elementType\":\"ApplicationComponent\"},{\"viewObjectId\":\"n-in\",\""
            + "type\":\"note\",\"label\":\"n-in\"}]}]}],\"stats\":{\"totalGroups\":3,\"topLevelGroups\":2,\"nestedGroups\":1,\""
            + "totalElements\":9,\"totalNotes\":2,\"totalImages\":0,\"totalViewReferences\":0,"
            + "\"ungroupedElements\":4}},\"nextSteps\":[\"Use layout-within-group wi"
            + "th a group's viewObjectId to layout its children\",\"Use arrange-groups to position groups relativ"
            + "e to each other\",\"Use optimize-group-order to minimize inter-group edge crossings\",\"Use get-view"
            + "-contents format=json for full element and relationship detail\"],\"_meta\":{\"modelVersion\":\"_\",\"re"
            + "sultCount\":12,\"isTruncated\":false,\"totalCount\":12,\"sessionActive\":true}}";
    private static final String GRAPH =
            "{\"graph\":{\"nodes\":[{\"id\":\"el-a\",\"name\":\"el-a\",\"type\":\"ApplicationComponent\",\"layer\":\"Application"
            + "\",\"viewObjectIds\":[\"vo-a\",\"vo-a2\"]},{\"id\":\"el-b\",\"name\":\"el-b\",\"type\":\"ApplicationComponent\",\"la"
            + "yer\":\"Application\",\"viewObjectId\":\"vo-b\"},{\"id\":\"el-c\",\"name\":\"el-c\",\"type\":\"ApplicationComponen"
            + "t\",\"layer\":\"Application\",\"viewObjectId\":\"vo-c\"},{\"id\":\"el-d\",\"name\":\"el-d\",\"type\":\"ApplicationCo"
            + "mponent\",\"layer\":\"Application\",\"viewObjectId\":\"vo-d\"},{\"id\":\"el-host\",\"name\":\"el-host\",\"type\":\"A"
            + "pplicationComponent\",\"layer\":\"Application\",\"viewObjectId\":\"vo-host\"},{\"id\":\"el-e\",\"name\":\"el-e\","
            + "\"type\":\"ApplicationComponent\",\"layer\":\"Application\",\"viewObjectId\":\"vo-e\"},{\"id\":\"el-grouping\",\""
            + "name\":\"Grouping\",\"type\":\"Grouping\",\"layer\":\"Other\",\"viewObjectId\":\"vo-grouping\",\"isGroup\":true},"
            + "{\"id\":\"el-f\",\"name\":\"el-f\",\"type\":\"ApplicationComponent\",\"layer\":\"Application\",\"viewObjectId\":\"v"
            + "o-f\"},{\"viewObjectId\":\"g-outer\",\"label\":\"g-outer\",\"x\":600,\"y\":300,\"width\":400,\"height\":300,\"chil"
            + "dViewObjectIds\":[\"vo-c\",\"g-inner\"],\"_nodeType\":\"group\",\"isGroup\":true},{\"viewObjectId\":\"g-inner\""
            + ",\"label\":\"g-inner\",\"x\":200,\"y\":40,\"width\":180,\"height\":200,\"parentViewObjectId\":\"g-outer\",\"child"
            + "ViewObjectIds\":[\"vo-d\",\"n-in\"],\"_nodeType\":\"group\",\"isGroup\":true},{\"viewObjectId\":\"n-in\",\"conte"
            + "nt\":\"n-in\",\"x\":20,\"y\":120,\"width\":100,\"height\":50,\"parentViewObjectId\":\"g-inner\",\"_nodeType\":\"no"
            + "te\"},{\"viewObjectId\":\"n-top\",\"content\":\"n-top\",\"x\":1100,\"y\":40,\"width\":100,\"height\":50,\"_nodeTyp"
            + "e\":\"note\"}],\"edges\":[{\"id\":\"rel-ab\",\"name\":\"\",\"type\":\"AssociationRelationship\",\"sourceId\":\"el-a\""
            + ",\"targetId\":\"el-b\",\"associationDirected\":false,\"viewConnectionIds\":[\"conn-ab\",\"conn-a2b\"]},{\"id\""
            + ":\"rel-ahost\",\"name\":\"\",\"type\":\"AssociationRelationship\",\"sourceId\":\"el-a\",\"targetId\":\"el-host\",\""
            + "associationDirected\":false,\"viewConnectionId\":\"conn-a2host\"},{\"id\":\"rel-cd\",\"name\":\"\",\"type\":\"As"
            + "sociationRelationship\",\"sourceId\":\"el-c\",\"targetId\":\"el-d\",\"associationDirected\":false,\"viewConn"
            + "ectionId\":\"conn-cd\"},{\"id\":\"rel-eb\",\"name\":\"\",\"type\":\"AssociationRelationship\",\"sourceId\":\"el-e\""
            + ",\"targetId\":\"el-b\",\"associationDirected\":false,\"viewConnectionId\":\"conn-eb\"}]},\"nextSteps\":[\"Use"
            + " get-element with a node ID for detailed information\",\"Use get-relationships with format=graph t"
            + "o explore connections beyond this view\",\"Graph edges carry viewConnectionId and nodes carry view"
            + "ObjectId (the VISUAL ids) — pass these to remove-from-view or update-view-object; the edge id/no"
            + "de id are MODEL ids those tools reject. Multi-drawn relationships / multi-placed elements expose"
            + " viewConnectionIds[] / viewObjectIds[] instead.\"],\"_meta\":{\"modelVersion\":\"_\",\"resultCount\":12,\""
            + "edgeCount\":4,\"totalCount\":12,\"isTruncated\":false,\"sessionActive\":true}}";
    private static final String SUMMARY =
            "{\"summary\":\"View 'Source': 8 elements, 4 relationships, 5 connections. Containers: 3 groups. Vis"
            + "ual annotations: 2 notes. Elements: 7 ApplicationComponent, and 1 Grouping. Relationships: 4 Ass"
            + "ociationRelationship.\",\"nextSteps\":[\"Re-run with format=json for full element and relationship d"
            + "ata\",\"Re-run with format=graph for deduplicated node/edge structure\",\"Use get-element with an el"
            + "ement ID for detailed information\"],\"_meta\":{\"modelVersion\":\"_\",\"resultCount\":12,\"totalCount\":12"
            + ",\"isTruncated\":false,\"sessionActive\":true}}";
    private static final String DRY_RUN =
            "{\"dryRun\":{\"estimatedResultCount\":12,\"estimatedTokens\":2449,\"recommendedPreset\":\"standard\",\"reco"
            + "mmendation\":\"Medium result set (12 items, ~2449 tokens at standard). Consider using limit parame"
            + "ter for paginated retrieval.\"},\"nextSteps\":[\"Execute the query without dryRun to retrieve result"
            + "s\",\"Use fields=minimal to reduce token usage\",\"Use exclude=['visualMetadata','connections'] to o"
            + "mit position and routing data\"],\"_meta\":{\"modelVersion\":\"_\",\"dryRun\":true,\"sessionActive\":true}}";
    private static final String DRY_RUN_EXCLUDED =
            "{\"dryRun\":{\"estimatedResultCount\":12,\"estimatedTokens\":1188,\"recommendedPreset\":\"standard\",\"reco"
            + "mmendation\":\"Small result set (12 items, ~1188 tokens at standard). Safe to execute as-is.\"},\"ne"
            + "xtSteps\":[\"Execute the query without dryRun to retrieve results\"],\"_meta\":{\"modelVersion\":\"_\",\"d"
            + "ryRun\":true,\"sessionActive\":true}}";
    private static final String IMAGES_JSON =
            "{\"result\":{\"viewId\":\"view-images\",\"viewName\":\"Images\",\"elements\":[{\"id\":\"el-a\",\"name\":\"el-a\",\"ty"
            + "pe\":\"ApplicationComponent\",\"layer\":\"Application\"}],\"relationships\":[],\"visualMetadata\":[{\"viewOb"
            + "jectId\":\"vo-ia\",\"elementId\":\"el-a\",\"x\":40,\"y\":40,\"width\":120,\"height\":55}],\"connections\":[],\"gro"
            + "ups\":[{\"viewObjectId\":\"g-images\",\"label\":\"g-images\",\"x\":40,\"y\":200,\"width\":300,\"height\":200,\"chi"
            + "ldViewObjectIds\":[\"img-in-group\"]}],\"images\":[{\"viewObjectId\":\"img-in-element\",\"imagePath\":\"imag"
            + "es/logo.png\",\"x\":5,\"y\":5,\"width\":64,\"height\":48,\"parentViewObjectId\":\"vo-ia\"},{\"viewObjectId\":\"i"
            + "mg-top\",\"imagePath\":\"images/logo.png\",\"x\":300,\"y\":40,\"width\":64,\"height\":48},{\"viewObjectId\":\"im"
            + "g-in-group\",\"imagePath\":\"images/logo.png\",\"x\":20,\"y\":30,\"width\":64,\"height\":48,\"parentViewObject"
            + "Id\":\"g-images\",\"documentation\":\"caption\"}]},\"nextSteps\":[\"Use get-element with an element ID for"
            + " detailed information\",\"Use get-relationships with an element ID to explore connections beyond t"
            + "his view\"],\"_meta\":{\"modelVersion\":\"_\",\"resultCount\":1,\"isTruncated\":false,\"totalCount\":1,\"sessi"
            + "onActive\":true}}";
}
