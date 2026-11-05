package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.ITextAlignment;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.NonArchimateConnectionDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;

/**
 * Pins that {@code get-view-contents} lists every connection a view holds exactly once: the ones that
 * are not ArchiMate ones in {@code nonArchimateConnections}, and the ArchiMate ones drawn from another
 * connection in {@code connections}.
 *
 * <p>Before, the walk read only the connections an ArchiMate object is the source of, and kept only the
 * ArchiMate ones among them. A connection from a note, group, image or view reference, a plain connection
 * from an element, and any connection whose source is another connection were in no array. This server
 * cannot draw most of these shapes, so they are built through EMF as Archi's editor would. Every
 * expectation is a literal read off the model, or a helper the ArchiMate rows already use applied to the
 * same ends.</p>
 */
public class GetViewContentsConnectionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ViewContentsFixture f;
    private IDiagramModelArchimateObject a;
    private IDiagramModelArchimateObject b;
    private IDiagramModelArchimateObject c;
    private IDiagramModelGroup group;
    private IDiagramModelNote note;
    private IDiagramModelReference reference;
    private IDiagramModelImage image;
    private IArchimateElement elC;
    private IArchimateRelationship relAb;
    private IDiagramModelArchimateConnection ab;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-view-contents-connections");
        IArchimateElement elA = f.element("el-a");
        IArchimateElement elB = f.element("el-b");
        elC = f.element("el-c");
        a = f.object(f.view, "vo-a", elA, 40, 40, 120, 60);
        b = f.object(f.view, "vo-b", elB, 400, 40, 120, 60);
        c = f.object(f.view, "vo-c", elC, 220, 300, 120, 60);
        group = f.group(f.view, "grp-1", 40, 500, 300, 200);
        note = f.note(f.view, "n-1", 700, 40);
        reference = f.reference(f.view, "ref-1", f.other, 700, 300);
        image = f.image(f.view, "img-1", 700, 500);
        relAb = f.relationship("rel-ab", elA, elB);
        ab = f.archimateConnection("conn-ab", relAb, a, b);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- connections that are not ArchiMate ones ---------------------------------------------------

    @Test
    public void getViewContents_shouldListAPlainConnection_fromANoteToAReference() {
        f.plainConnection("p-note-ref", note, reference);

        ViewContentsDto contents = read();

        assertEquals(List.of(new NonArchimateConnectionDto("p-note-ref", "n-1", "ref-1", null, null, null, null,
                IDiagramModelConnection.CONNECTION_TEXT_POSITION_MIDDLE, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null)), contents.nonArchimateConnections());
        assertEquals("the ArchiMate rows are unchanged", List.of("conn-ab"), ids(contents.connections()));
    }

    @Test
    public void getViewContents_shouldListEveryFieldOfAPlainConnection_whenItIsStyled() {
        IDiagramModelConnection conn = f.plainConnection("p-styled", a, note);
        // Both reconstructions of each bendpoint agree (vo-a's centre 100,70; the note's 750,65), so the
        // canvas points are exact at every weight: 180,70 and 180,190.
        conn.getBendpoints().add(f.bendpoint(80, 0, -570, 5));
        conn.getBendpoints().add(f.bendpoint(80, 120, -570, 125));
        conn.setName("reads");
        conn.setDocumentation("why this line");
        conn.setLineColor("#5c5c5c");
        conn.setLineWidth(2);
        conn.setFontColor("#112233");
        conn.setFont("Arial|9|1");
        conn.setTextPosition(IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE);
        conn.setNameVisible(false);
        conn.setType(IDiagramModelConnection.LINE_DASHED | IDiagramModelConnection.ARROW_FILL_TARGET
                | IDiagramModelConnection.ARROW_HOLLOW_SOURCE);

        NonArchimateConnectionDto row = only(read().nonArchimateConnections());

        List<BendpointDto> stored = List.of(new BendpointDto(80, 0, -570, 5), new BendpointDto(80, 120, -570, 125));
        int[] source = ConnectionResponseBuilder.computeAbsoluteCenter(a);
        int[] target = ConnectionResponseBuilder.computeAbsoluteCenter(note);
        List<AbsoluteBendpointDto> absolute = ConnectionResponseBuilder.convertRelativeToAbsolute(stored,
                source[0], source[1], target[0], target[1]);
        assertEquals("the derivation the ArchiMate rows use, on the same ends",
                List.of(new AbsoluteBendpointDto(180, 70), new AbsoluteBendpointDto(180, 190)), absolute);
        assertEquals(new NonArchimateConnectionDto("p-styled", "vo-a", "n-1", "reads", "why this line", stored,
                absolute, IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE, "#5c5c5c", 2, "#112233",
                Boolean.FALSE, "Arial", 9, "bold", null, null, "dashed", "hollow", "fill", null, null), row);
    }

    @Test
    public void getViewContents_shouldReadBackTheLabelSettingsAndProperties_ofAPlainConnection() {
        IDiagramModelConnection conn = f.plainConnection("p-label", note, reference);
        conn.getFeatures().putString("labelExpression", "${name} (${property:Owner})");
        assertTrue("the platform these pins run on carries the label offset",
                RelativePositionFeature.isSupported(conn));
        RelativePositionFeature.set(conn, 1);
        conn.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_LEFT);
        IProperty owner = f.factory.createProperty();
        owner.setKey("Owner");
        owner.setValue("Payments");
        conn.getProperties().add(owner);

        NonArchimateConnectionDto row = only(read().nonArchimateConnections());

        assertEquals("${name} (${property:Owner})", row.labelExpression());
        assertEquals("the north offset anchor", Integer.valueOf(1), row.relativePosition());
        assertEquals("left", row.textAlignment());
        assertEquals(List.of(Map.of("key", "Owner", "value", "Payments")), row.properties());
        assertTrue(f.serve(Map.of()).contains("\"nonArchimateConnections\":[{\"viewConnectionId\":\"p-label\","
                + "\"sourceViewObjectId\":\"n-1\",\"targetViewObjectId\":\"ref-1\",\"textPosition\":1,"
                + "\"labelExpression\":\"${name} (${property:Owner})\",\"relativePosition\":1,"
                + "\"textAlignment\":\"left\",\"properties\":[{\"key\":\"Owner\",\"value\":\"Payments\"}]}]"));
    }

    @Test
    public void getViewContents_shouldReportRightAlignment_andOmitTheCentredDefault() {
        f.plainConnection("p-centre", note, reference);
        f.plainConnection("p-right", group, image).setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_RIGHT);

        List<NonArchimateConnectionDto> rows = read().nonArchimateConnections();

        assertNull(plainById(rows, "p-centre").textAlignment());
        assertEquals("right", plainById(rows, "p-right").textAlignment());
    }

    @Test
    public void getViewContents_shouldServeABarePlainConnection_exactlyAsBefore() {
        f.plainConnection("p-note-ref", note, reference);

        assertTrue(f.serve(Map.of()).contains("\"nonArchimateConnections\":[{\"viewConnectionId\":\"p-note-ref\","
                + "\"sourceViewObjectId\":\"n-1\",\"targetViewObjectId\":\"ref-1\",\"textPosition\":1}]"));
    }

    @Test
    public void getViewContents_shouldNameTheLineAndArrowheads_inArchisPrecedence() {
        f.plainConnection("p-dotted", a, note).setType(IDiagramModelConnection.LINE_DOTTED
                | IDiagramModelConnection.ARROW_LINE_SOURCE | IDiagramModelConnection.ARROW_HOLLOW_TARGET);
        f.plainConnection("p-both", b, note).setType(IDiagramModelConnection.LINE_DASHED
                | IDiagramModelConnection.LINE_DOTTED | IDiagramModelConnection.ARROW_FILL_SOURCE
                | IDiagramModelConnection.ARROW_LINE_SOURCE | IDiagramModelConnection.ARROW_LINE_TARGET
                | IDiagramModelConnection.ARROW_HOLLOW_TARGET);

        List<String> decorations = new ArrayList<>();
        for (NonArchimateConnectionDto row : read().nonArchimateConnections()) {
            decorations.add(row.viewConnectionId() + ":" + row.lineStyle() + "/" + row.sourceArrowhead() + "/"
                    + row.targetArrowhead());
        }

        assertEquals("dashed over dotted; per end fill over line over hollow",
                List.of("p-dotted:dotted/line/hollow", "p-both:dashed/fill/line"), decorations);
    }

    @Test
    public void getViewContents_shouldListPlainConnections_fromAGroupAndAnImage() {
        f.plainConnection("p-group", group, a);
        f.plainConnection("p-image", image, b);

        assertEquals(List.of("p-group:grp-1>vo-a", "p-image:img-1>vo-b"), ends(read().nonArchimateConnections()));
    }

    @Test
    public void getViewContents_shouldOmitTheArray_whenTheViewHoldsNoPlainConnection() {
        assertNull(read().nonArchimateConnections());
        assertFalse(f.serve(Map.of()).contains("nonArchimateConnections"));
    }

    // ---- connections drawn from or to a connection -------------------------------------------------

    @Test
    public void getViewContents_shouldListAnArchimateConnection_toAConnection_withNoAnchorAtEitherEnd() {
        IArchimateRelationship relCab = f.relationship("rel-c-ab", elC, relAb);
        f.archimateConnection("conn-c-ab", relCab, c, ab).getBendpoints().add(f.bendpoint(0, -100, 0, 100));

        ViewContentsDto contents = read();

        ViewConnectionDto row = byId(contents.connections(), "conn-c-ab");
        assertEquals("vo-c", row.sourceViewObjectId());
        assertEquals("conn-ab", row.targetViewObjectId());
        // Both ends are pinned null on purpose: the element end's geometry needs the connection end's.
        assertNull(row.targetAnchor());
        assertNull(row.targetRenderFace());
        assertNull("the shared builder abstains on both ends when one is not a figure", row.sourceAnchor());
        assertNull(row.sourceRenderFace());
        assertEquals("the stored bendpoint is still reported",
                List.of(new BendpointDto(0, -100, 0, 100)), row.bendpoints());
        assertNull("but not its canvas point, which needs both ends' reference points",
                row.absoluteBendpoints());
        assertEquals(List.of("rel-ab", "rel-c-ab"), relationshipIds(contents.relationships()));
    }

    @Test
    public void getViewContents_shouldListAnArchimateConnection_fromAConnection() {
        IArchimateRelationship relAbC = f.relationship("rel-ab-c", relAb, elC);
        f.archimateConnection("conn-ab-c", relAbC, ab, c).getBendpoints().add(f.bendpoint(0, -100, 0, 100));

        ViewContentsDto contents = read();

        ViewConnectionDto row = byId(contents.connections(), "conn-ab-c");
        assertEquals("conn-ab", row.sourceViewObjectId());
        assertEquals("vo-c", row.targetViewObjectId());
        assertNull(row.sourceAnchor());
        assertNull(row.sourceRenderFace());
        assertNull("the object end has none either", row.targetAnchor());
        assertNull(row.targetRenderFace());
        assertEquals(List.of(new BendpointDto(0, -100, 0, 100)), row.bendpoints());
        assertNull("nor the canvas point of its stored bendpoint", row.absoluteBendpoints());
        assertEquals("its relationship joins the relationships array",
                List.of("rel-ab", "rel-ab-c"), relationshipIds(contents.relationships()));
    }

    @Test
    public void getViewContents_shouldListAChainOfConnections_eachOnceAfterTheOneItIsDrawnFrom() {
        IDiagramModelConnection first = f.plainConnection("p-1", note, reference);
        IDiagramModelConnection second = f.plainConnection("p-2", first, image);
        second.getBendpoints().add(f.bendpoint(10, 10, -10, -10));
        f.plainConnection("p-3", second, a);
        f.plainConnection("p-to-conn", b, ab);

        ViewContentsDto contents = read();

        assertEquals("in the view's child order; each connection's own followers straight after it",
                List.of("p-to-conn:vo-b>conn-ab", "p-1:n-1>ref-1", "p-2:p-1>img-1", "p-3:p-2>vo-a"),
                ends(contents.nonArchimateConnections()));
        NonArchimateConnectionDto p2 = plainById(contents.nonArchimateConnections(), "p-2");
        assertEquals("stored bendpoints are reported", 1, p2.bendpoints().size());
        assertNull("no absolute bendpoints when an end is a connection", p2.absoluteBendpoints());
    }

    @Test
    public void getViewContents_shouldListEveryConnectionOnTheViewExactlyOnce() {
        IDiagramModelConnection first = f.plainConnection("p-1", note, reference);
        f.plainConnection("p-2", first, ab);
        f.archimateConnection("conn-ab-c", f.relationship("rel-ab-c", relAb, elC), ab, c);
        f.archimateConnection("conn-c-ab", f.relationship("rel-c-ab", elC, relAb), c, ab);
        f.plainConnection("p-group", group, c);

        ViewContentsDto contents = read();

        List<String> listed = new ArrayList<>(ids(contents.connections()));
        for (NonArchimateConnectionDto row : contents.nonArchimateConnections()) {
            listed.add(row.viewConnectionId());
        }
        assertEquals(List.of("conn-ab", "conn-ab-c", "conn-c-ab", "p-group", "p-1", "p-2"), listed);
    }

    // ---- an ArchiMate connection whose relationship is missing -------------------------------------

    /*
     * Only a damaged model holds one: Archi's editor never draws an ArchiMate connection without a
     * relationship, but a hand-edited file or a broken script can leave one behind, and Archi still draws
     * it. It is listed with the connections that have no relationship behind them, which is what it is.
     */

    @Test
    public void getViewContents_shouldListAConnectionWithNoRelationship_onceAmongTheOtherConnections() {
        IDiagramModelArchimateConnection broken = brokenConnection("conn-broken", a, b);
        broken.getBendpoints().add(f.bendpoint(100, 0, -260, 0));
        broken.setLineColor("#ff0000");
        broken.setLineWidth(2);

        ViewContentsDto contents = read();

        assertEquals(List.of(new NonArchimateConnectionDto("conn-broken", "vo-a", "vo-b", null, null,
                List.of(new BendpointDto(100, 0, -260, 0)), List.of(new AbsoluteBendpointDto(200, 70)),
                IDiagramModelConnection.CONNECTION_TEXT_POSITION_MIDDLE, "#ff0000", 2, null, null, null, null,
                null, null, null, null, null, null, null, null)), contents.nonArchimateConnections());
        assertEquals("not among the ArchiMate rows, which each carry a relationship",
                List.of("conn-ab"), ids(contents.connections()));
        assertEquals(List.of("rel-ab"), relationshipIds(contents.relationships()));
    }

    @Test
    public void getViewContents_shouldStillListTheConnectionsDrawnFromAConnectionWithNoRelationship() {
        IDiagramModelArchimateConnection broken = brokenConnection("conn-broken", a, b);
        f.plainConnection("p-from-broken", broken, note);
        f.archimateConnection("conn-broken-c", f.relationship("rel-c", elC, elC), broken, c);

        ViewContentsDto contents = read();

        assertEquals(List.of("conn-broken:vo-a>vo-b", "p-from-broken:conn-broken>n-1"),
                ends(contents.nonArchimateConnections()));
        assertEquals(List.of("conn-ab", "conn-broken-c"), ids(contents.connections()));
    }

    @Test
    public void graph_shouldCarryAConnectionWithNoRelationship_asAConnectionEdge() throws Exception {
        brokenConnection("conn-broken", a, b);

        List<Map<String, Object>> edges = list(served(Map.of("format", "graph"), "graph"), "edges");

        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("viewConnectionId", "conn-broken");
        plain.put("sourceViewObjectId", "vo-a");
        plain.put("targetViewObjectId", "vo-b");
        plain.put("_edgeType", "connection");
        assertEquals(2, edges.size());
        assertEquals("conn-ab", edges.get(0).get("viewConnectionId"));
        assertEquals(plain, edges.get(1));
    }

    @Test
    public void summary_shouldCountAConnectionWithNoRelationship_amongTheOtherConnections() throws Exception {
        brokenConnection("conn-broken", a, b);

        String summary = String.valueOf(JSON.readValue(f.serve(Map.of("format", "summary")), Map.class).get("summary"));

        assertTrue(summary, summary.startsWith("View 'Source': 3 elements, 1 relationships, 1 connections. "
                + "Other connections: 1 connection that is not an ArchiMate relationship."));
    }

    @Test
    public void getViewContents_shouldChargeAConnectionWithNoRelationship_inTheDryRun() throws Exception {
        int before = estimate(Map.of("dryRun", true));
        brokenConnection("conn-broken", a, b);

        assertEquals("one plain row at 410 chars, 4 chars a token", 410 / 4,
                estimate(Map.of("dryRun", true)) - before, 1);
    }

    // ---- what the caller can do with the array -----------------------------------------------------

    @Test
    public void getViewContents_shouldDropTheArray_whenExcluded() {
        f.plainConnection("p-note-ref", note, reference);

        assertTrue(f.serve(Map.of()).contains("\"nonArchimateConnections\":["));
        assertFalse(f.serve(Map.of("exclude", List.of("nonArchimateConnections"))).contains("nonArchimateConnections"));
    }

    @Test
    public void getViewContents_shouldChargeEachPlainConnection_inTheDryRun() throws Exception {
        f.plainConnection("p-1", note, reference);
        f.plainConnection("p-2", group, a);

        int with = estimate(Map.of("dryRun", true));
        int without = estimate(Map.of("dryRun", true, "exclude", List.of("nonArchimateConnections")));

        assertEquals("two rows at 410 chars, 4 chars a token", 2 * 410 / 4, with - without, 1);
    }

    // ---- graph and summary -------------------------------------------------------------------------

    @Test
    public void graph_shouldCarryAPlainConnection_asAConnectionEdge_besideTheRelationshipEdges() throws Exception {
        f.plainConnection("p-note-ref", note, reference);

        List<Map<String, Object>> edges = list(served(Map.of("format", "graph"), "graph"), "edges");

        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("viewConnectionId", "p-note-ref");
        plain.put("sourceViewObjectId", "n-1");
        plain.put("targetViewObjectId", "ref-1");
        plain.put("_edgeType", "connection");
        assertEquals(2, edges.size());
        assertEquals("rel-ab", edges.get(0).get("id"));
        assertEquals("conn-ab", edges.get(0).get("viewConnectionId"));
        assertEquals(plain, edges.get(1));
    }

    @Test
    public void summary_shouldCountPlainConnections_apartFromTheConnections() throws Exception {
        f.plainConnection("p-1", note, reference);
        f.plainConnection("p-2", group, a);

        String summary = String.valueOf(JSON.readValue(f.serve(Map.of("format", "summary")), Map.class).get("summary"));

        assertTrue(summary, summary.startsWith("View 'Source': 3 elements, 1 relationships, 1 connections. "
                + "Other connections: 2 connections that are not ArchiMate relationships."));
    }

    @Test
    public void summary_shouldNameOnePlainConnection_inTheSingular() throws Exception {
        f.plainConnection("p-1", note, reference);

        String summary = String.valueOf(JSON.readValue(f.serve(Map.of("format", "summary")), Map.class).get("summary"));

        assertTrue(summary, summary.contains(
                " Other connections: 1 connection that is not an ArchiMate relationship."));
    }

    // ---- harness -----------------------------------------------------------------------------------

    /** An ArchiMate connection between {@code source} and {@code target} whose relationship is gone. */
    private IDiagramModelArchimateConnection brokenConnection(String id, IDiagramModelArchimateObject source,
            IDiagramModelArchimateObject target) {
        // Archi's connect() reconnects the relationship too, so connect with one and then drop it.
        IDiagramModelArchimateConnection conn = f.archimateConnection(id,
                f.relationship("rel-" + id, source.getArchimateElement(), target.getArchimateElement()),
                source, target);
        f.model.getFolder(com.archimatetool.model.FolderType.RELATIONS).getElements()
                .remove(conn.getArchimateRelationship());
        conn.setArchimateRelationship(null);
        return conn;
    }

    private ViewContentsDto read() {
        return f.accessor.getViewContents(f.view.getId()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> served(Map<String, Object> args, String key) throws Exception {
        String text = f.serve(args);
        assertFalse(text, text.startsWith("ERROR "));
        return (Map<String, Object>) JSON.readValue(text, Map.class).get(key);
    }

    @SuppressWarnings("unchecked")
    private int estimate(Map<String, Object> args) throws Exception {
        Map<String, Object> dryRun = served(args, "dryRun");
        return ((Number) dryRun.get("estimatedTokens")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    private static <T> T only(List<T> rows) {
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    private static List<String> ids(List<ViewConnectionDto> rows) {
        List<String> ids = new ArrayList<>();
        for (ViewConnectionDto row : rows) {
            ids.add(row.viewConnectionId());
        }
        return ids;
    }

    private static List<String> relationshipIds(List<RelationshipDto> rows) {
        List<String> ids = new ArrayList<>();
        for (RelationshipDto row : rows) {
            ids.add(row.id());
        }
        return ids;
    }

    private static List<String> ends(List<NonArchimateConnectionDto> rows) {
        List<String> ends = new ArrayList<>();
        for (NonArchimateConnectionDto row : rows) {
            ends.add(row.viewConnectionId() + ":" + row.sourceViewObjectId() + ">" + row.targetViewObjectId());
        }
        return ends;
    }

    private static ViewConnectionDto byId(List<ViewConnectionDto> rows, String id) {
        for (ViewConnectionDto row : rows) {
            if (row.viewConnectionId().equals(id)) {
                return row;
            }
        }
        throw new AssertionError("no connection " + id + " in " + ids(rows));
    }

    private static NonArchimateConnectionDto plainById(List<NonArchimateConnectionDto> rows, String id) {
        for (NonArchimateConnectionDto row : rows) {
            if (row.viewConnectionId().equals(id)) {
                return row;
            }
        }
        throw new AssertionError("no connection " + id + " in " + ends(rows));
    }
}
