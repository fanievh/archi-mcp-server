package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

/**
 * Tests for {@link BulkOperationResult} — specifically the optional fan-out counts
 * ({@code appliedCount}/{@code skippedCount}) added for the set-view-label-expression op,
 * and the copier fidelity every optional component depends on.
 */
public class BulkOperationResultTest {

    @Test
    public void shouldOmitCounts_whenSingleEntityConstructorUsed() throws Exception {
        BulkOperationResult dto = new BulkOperationResult(
                0, "create-element", "created", "id-1", "ApplicationComponent", "Alpha");

        assertNull(dto.appliedCount());
        assertNull(dto.skippedCount());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("null counts must be omitted", json.contains("appliedCount"));
        assertFalse("null counts must be omitted", json.contains("skippedCount"));
    }

    @Test
    public void shouldCarryCounts_whenFanOutConstructorUsed() throws Exception {
        BulkOperationResult dto = new BulkOperationResult(
                0, "set-view-label-expression", "updated", "view-1", "ArchimateDiagramModel",
                "Context View", 12, 3);

        assertEquals(Integer.valueOf(12), dto.appliedCount());
        assertEquals(Integer.valueOf(3), dto.skippedCount());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue(json.contains("\"appliedCount\":12"));
        assertTrue(json.contains("\"skippedCount\":3"));
    }

    // ---- The post-dispatch connection report ----------------------------------------------------

    /**
     * Every copier must forward the connection report, and this fails if any single one drops it.
     *
     * <p>Not a hypothetical obligation. This record's own history is a copier that delegated to a
     * defaulting constructor and so discarded {@code movedObjects} on every dispatched operation —
     * computed, attached, thrown away. The connection report is worse exposed than that field was,
     * because the retraction pass runs <em>after</em> it is attached and reaches it through the two
     * list copiers rather than through the one it was written beside.</p>
     */
    @Test
    public void shouldPreserveEffectiveConnection_whenAnyCopierRebuildsTheResult() {
        ViewConnectionDto connection = sampleConnection();
        BulkOperationResult base = new BulkOperationResult(
                0, "add-connection-to-view", "created", "conn-1", "AssociationRelationship", null)
                .withEffectiveConnection(connection);

        assertSame("withEffectiveBounds must forward the connection report", connection,
                base.withEffectiveBounds(new BulkOperationResult.EffectiveBounds(1, 2, 3, 4))
                        .effectiveConnection());
        assertSame("withResizedAncestors must forward the connection report", connection,
                base.withResizedAncestors(List.of()).effectiveConnection());
        assertSame("withMovedObjects must forward the connection report", connection,
                base.withMovedObjects(List.of()).effectiveConnection());
        assertSame("the retraction chain — withResizedAncestors then withMovedObjects, which is "
                + "what a declined operation goes through — must forward the connection report",
                connection,
                base.withResizedAncestors(List.of()).withMovedObjects(List.of())
                        .effectiveConnection());
    }

    // ---- The post-dispatch name refresh ---------------------------------------------------------

    /**
     * The refreshed name is attached by the same after-dispatch pass and is exposed exactly as the
     * connection report is: the retraction that follows reaches the result through the two list
     * copiers, so either one dropping the name would put the pre-write value back on the wire for
     * every declined operation — silently, and only for the operations least able to afford it.
     */
    @Test
    public void shouldPreserveEntityName_whenAnyCopierRebuildsTheResult() {
        BulkOperationResult base = new BulkOperationResult(
                0, "update-relationship", "updated", "rel-1", "AssociationRelationship", "Serves")
                .withEntityName("Renamed");

        assertEquals("withEffectiveBounds must forward the refreshed name", "Renamed",
                base.withEffectiveBounds(new BulkOperationResult.EffectiveBounds(1, 2, 3, 4))
                        .entityName());
        assertEquals("withResizedAncestors must forward the refreshed name", "Renamed",
                base.withResizedAncestors(List.of()).entityName());
        assertEquals("withMovedObjects must forward the refreshed name", "Renamed",
                base.withMovedObjects(List.of()).entityName());
        assertEquals("withEffectiveConnection must forward the refreshed name", "Renamed",
                base.withEffectiveConnection(sampleConnection()).entityName());
        assertEquals("the retraction chain — withResizedAncestors then withMovedObjects, which is "
                + "what a declined operation goes through — must forward the refreshed name",
                "Renamed",
                base.withResizedAncestors(List.of()).withMovedObjects(List.of()).entityName());
    }

    /** The copier must name every other component too, not default the optional ones away. */
    @Test
    public void shouldPreserveEveryOtherComponent_whenTheNameIsRefreshed() {
        ViewConnectionDto connection = sampleConnection();
        List<MovedViewObjectDto> moved = List.of(new MovedViewObjectDto("vo-9", "Nine", 1, 2, 3, 4));
        BulkOperationResult refreshed = new BulkOperationResult(
                1, "update-view-object", "updated", "vo-1", "BusinessActor", "Alpha",
                7, 2, new BulkOperationResult.EffectiveBounds(5, 6, 7, 8), null, moved, moved,
                connection)
                .withEntityName("Omega");

        assertEquals("Omega", refreshed.entityName());
        assertEquals(1, refreshed.index());
        assertEquals("update-view-object", refreshed.tool());
        assertEquals("updated", refreshed.action());
        assertEquals("vo-1", refreshed.entityId());
        assertEquals("BusinessActor", refreshed.entityType());
        assertEquals(Integer.valueOf(7), refreshed.appliedCount());
        assertEquals(Integer.valueOf(2), refreshed.skippedCount());
        assertEquals(new BulkOperationResult.EffectiveBounds(5, 6, 7, 8), refreshed.effectiveBounds());
        assertNull(refreshed.deletion());
        assertEquals(moved, refreshed.resizedAncestors());
        assertEquals(moved, refreshed.movedObjects());
        assertSame(connection, refreshed.effectiveConnection());
    }

    // ---- The deletion's execute-time name --------------------------------------------------------

    /**
     * Every copier must forward the cascade report, and this fails if any single one drops it.
     *
     * <p>The report is the only surviving description of an entity that no longer exists — its id
     * stops resolving the instant the delete applies, so nothing downstream can rebuild it from the
     * model. A copier that defaulted it away would take the whole account of what was destroyed off
     * the wire, and no later pass could notice.</p>
     */
    @Test
    public void shouldPreserveTheDeletionReport_whenAnyCopierRebuildsTheResult() {
        DeleteResultDto deletion = sampleDeletion();
        BulkOperationResult base = new BulkOperationResult(
                0, "delete-element", "deleted", "ba-1", "BusinessActor", "Customer",
                null, null, null, deletion, List.of(), List.of());

        assertSame("withEffectiveBounds must forward the cascade report", deletion,
                base.withEffectiveBounds(new BulkOperationResult.EffectiveBounds(1, 2, 3, 4))
                        .deletion());
        assertSame("withResizedAncestors must forward the cascade report", deletion,
                base.withResizedAncestors(List.of()).deletion());
        assertSame("withMovedObjects must forward the cascade report", deletion,
                base.withMovedObjects(List.of()).deletion());
        assertSame("withEntityName must forward the cascade report", deletion,
                base.withEntityName("Renamed").deletion());
        assertSame("withEffectiveConnection must forward the cascade report", deletion,
                base.withEffectiveConnection(sampleConnection()).deletion());
        assertSame("the retraction chain — withResizedAncestors then withMovedObjects, which is "
                + "what a declined operation goes through — must forward the cascade report",
                deletion,
                base.withResizedAncestors(List.of()).withMovedObjects(List.of()).deletion());
    }

    /** The new copier must name every other component too, not default the optional ones away. */
    @Test
    public void shouldPreserveEveryOtherComponent_whenTheDeletionReportIsReplaced() {
        ViewConnectionDto connection = sampleConnection();
        List<MovedViewObjectDto> moved = List.of(new MovedViewObjectDto("vo-9", "Nine", 1, 2, 3, 4));
        DeleteResultDto replacement = sampleDeletion().withName("Renamed");
        BulkOperationResult corrected = new BulkOperationResult(
                1, "delete-element", "deleted", "ba-1", "BusinessActor", "Renamed",
                7, 2, new BulkOperationResult.EffectiveBounds(5, 6, 7, 8), sampleDeletion(),
                moved, moved, connection)
                .withDeletion(replacement);

        assertSame(replacement, corrected.deletion());
        assertEquals(1, corrected.index());
        assertEquals("delete-element", corrected.tool());
        assertEquals("deleted", corrected.action());
        assertEquals("ba-1", corrected.entityId());
        assertEquals("BusinessActor", corrected.entityType());
        assertEquals("Renamed", corrected.entityName());
        assertEquals(Integer.valueOf(7), corrected.appliedCount());
        assertEquals(Integer.valueOf(2), corrected.skippedCount());
        assertEquals(new BulkOperationResult.EffectiveBounds(5, 6, 7, 8), corrected.effectiveBounds());
        assertEquals(moved, corrected.resizedAncestors());
        assertEquals(moved, corrected.movedObjects());
        assertSame(connection, corrected.effectiveConnection());
    }

    /**
     * The name copier on the report itself must carry every count across. The counts are the whole
     * point of the report, so a copier that reset one would replace a measured cascade with a
     * confident zero — the same class of lie as the stale name it exists to correct.
     */
    @Test
    public void shouldPreserveEveryCount_whenTheDeletionReportIsRenamed() {
        DeleteResultDto renamed = new DeleteResultDto(
                "f-1", "Sub Folder", "Folder", 4, 5, 6, 7, 8, 9).withName("Renamed Folder");

        assertEquals("Renamed Folder", renamed.name());
        assertEquals("f-1", renamed.id());
        assertEquals("Folder", renamed.type());
        assertEquals(4, renamed.relationshipsRemoved());
        assertEquals(5, renamed.viewReferencesRemoved());
        assertEquals(6, renamed.viewConnectionsRemoved());
        assertEquals(Integer.valueOf(7), renamed.elementsRemoved());
        assertEquals(Integer.valueOf(8), renamed.viewsRemoved());
        assertEquals(Integer.valueOf(9), renamed.foldersRemoved());
    }

    private static DeleteResultDto sampleDeletion() {
        return new DeleteResultDto("ba-1", "Customer", "BusinessActor", 2, 1, 3, null, null, null);
    }

    /** The back-compat arity every existing construction site uses must still default it away. */
    @Test
    public void shouldOmitEffectiveConnection_whenBuiltByTheBackCompatibleArity() throws Exception {
        BulkOperationResult dto = new BulkOperationResult(
                0, "update-view-object", "updated", "vo-1", "BusinessActor", "Alpha",
                null, null, null, null, List.of(), List.of());

        assertNull(dto.effectiveConnection());
        assertFalse("a null connection report must be omitted from the wire",
                new ObjectMapper().writeValueAsString(dto).contains("effectiveConnection"));
    }

    @Test
    public void shouldSerializeEffectiveConnection_whenTheOperationTouchedAConnection()
            throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new BulkOperationResult(0, "update-view-connection", "updated", "conn-1",
                        "AssociationRelationship", null)
                        .withEffectiveConnection(sampleConnection()));

        assertTrue(json.contains("\"effectiveConnection\""));
        assertTrue(json.contains("\"lineColor\":\"#D35400\""));
        assertTrue(json.contains("\"lineWidth\":2"));
    }

    /**
     * The concept reports are attached by the same after-dispatch pass and are exposed the same
     * way: the retraction that follows reaches the result through the two list copiers, so either
     * one dropping a report would silently take it off the wire for every operation that declined —
     * the failure the displaced-object list already suffered once, on exactly this record.
     */
    @Test
    public void shouldPreserveTheConceptReports_whenAnyCopierRebuildsTheResult() {
        RelationshipDto relationship = sampleRelationship();
        ElementDto element = sampleElement();
        BulkOperationResult base = new BulkOperationResult(
                0, "update-relationship", "updated", "rel-1", "InfluenceRelationship", "Drives")
                .withEffectiveRelationship(relationship)
                .withEffectiveElement(element);

        assertSame("withEffectiveBounds must forward the relationship report", relationship,
                base.withEffectiveBounds(new BulkOperationResult.EffectiveBounds(1, 2, 3, 4))
                        .effectiveRelationship());
        assertSame("withResizedAncestors must forward it", relationship,
                base.withResizedAncestors(List.of()).effectiveRelationship());
        assertSame("withMovedObjects must forward it", relationship,
                base.withMovedObjects(List.of()).effectiveRelationship());
        assertSame("withEffectiveConnection must forward it", relationship,
                base.withEffectiveConnection(sampleConnection()).effectiveRelationship());
        assertSame("withEntityName must forward it", relationship,
                base.withEntityName("Renamed").effectiveRelationship());
        assertSame("withEffectiveElement must forward it", relationship,
                base.withEffectiveElement(element).effectiveRelationship());
        assertSame("the retraction chain — withResizedAncestors then withMovedObjects, which is "
                + "what a declined operation goes through — must forward it", relationship,
                base.withResizedAncestors(List.of()).withMovedObjects(List.of())
                        .effectiveRelationship());

        assertSame("withEffectiveBounds must forward the element report", element,
                base.withEffectiveBounds(new BulkOperationResult.EffectiveBounds(1, 2, 3, 4))
                        .effectiveElement());
        assertSame("withResizedAncestors must forward it", element,
                base.withResizedAncestors(List.of()).effectiveElement());
        assertSame("withMovedObjects must forward it", element,
                base.withMovedObjects(List.of()).effectiveElement());
        assertSame("withEffectiveConnection must forward it", element,
                base.withEffectiveConnection(sampleConnection()).effectiveElement());
        assertSame("withEntityName must forward it", element,
                base.withEntityName("Renamed").effectiveElement());
        assertSame("withEffectiveRelationship must forward it", element,
                base.withEffectiveRelationship(relationship).effectiveElement());
        assertSame("the retraction chain must forward it", element,
                base.withResizedAncestors(List.of()).withMovedObjects(List.of())
                        .effectiveElement());
    }

    /** The back-compat arities every existing construction site uses must still default them away. */
    @Test
    public void shouldOmitTheConceptReports_whenBuiltByTheBackCompatibleArity() throws Exception {
        BulkOperationResult dto = new BulkOperationResult(
                0, "update-view-object", "updated", "vo-1", "BusinessActor", "Alpha",
                null, null, null, null, List.of(), List.of(), sampleConnection());

        assertNull(dto.effectiveRelationship());
        assertNull(dto.effectiveElement());
        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("a null relationship report must be omitted from the wire",
                json.contains("effectiveRelationship"));
        assertFalse("a null element report must be omitted from the wire",
                json.contains("effectiveElement"));
    }

    /**
     * Every copier forwards every component — checked reflectively, so the next component added
     * to the record is covered the day it is added.
     *
     * <p>The hand-written blocks above each cover one component and were each written after that
     * component was silently dropped. The record's own javadoc records the pattern: a copier that
     * delegates to a back-compatible constructor defaults the optional components away, and the
     * loss is invisible because the copier is called after they were populated. Naming the
     * components explicitly is the fix; this is the check that the fix was applied to ALL of
     * them, rather than to the one whose loss was noticed.</p>
     *
     * <p>Method: build a result with every component populated, then call each copier with the
     * value that component already holds. A faithful copier returns something equal to what it
     * was given; one that drops anything does not.</p>
     */
    @Test
    public void shouldForwardEveryComponent_throughEveryCopier() throws Exception {
        BulkOperationResult base = new BulkOperationResult(
                7, "add-note-to-view", "created", "note-1", "DiagramModelNote", "Legend",
                3, 1, new BulkOperationResult.EffectiveBounds(10, 20, 30, 40),
                sampleDeletion(),
                List.of(new MovedViewObjectDto("a", "A", 1, 2, 3, 4)),
                List.of(new MovedViewObjectDto("b", "B", 5, 6, 7, 8)),
                sampleConnection(), sampleRelationship(), sampleElement(), "parent-1",
                List.of(new StructuredWarningDto("SOME_CODE", "message", "tool", List.of("x"))),
                "left");

        int copiers = 0;
        for (java.lang.reflect.Method method : BulkOperationResult.class.getMethods()) {
            if (!method.getName().startsWith("with") || method.getParameterCount() != 1) {
                continue;
            }
            String component = Character.toLowerCase(method.getName().charAt(4))
                    + method.getName().substring(5);
            Object current = BulkOperationResult.class.getMethod(component).invoke(base);
            assertEquals(method.getName() + " must return a result equal to the one it copied — "
                            + "a difference here is a component it silently dropped",
                    base, method.invoke(base, current));
            copiers++;
        }
        assertEquals("every copier on the record must be exercised; if this number falls, a "
                + "copier was renamed away from the withComponent convention this check relies on",
                10, copiers);
    }

    private static ViewConnectionDto sampleConnection() {
        return new ViewConnectionDto("conn-1", "rel-1", "AssociationRelationship",
                "vo-src", "vo-tgt", List.of(new BendpointDto(10, 20, -10, 20)), null,
                new AnchorPointDto(60, 30), new AnchorPointDto(260, 30), 2,
                "#D35400", 2, null, null, null, null, null);
    }

    private static RelationshipDto sampleRelationship() {
        return new RelationshipDto("rel-1", "Drives", "InfluenceRelationship", null,
                "el-src", "el-tgt", false, null, null, null, null, null, null, "+++");
    }

    private static ElementDto sampleElement() {
        return ElementDto.standard("el-1", "Alpha", "BusinessActor", null, "Business",
                "Rewritten docs", null);
    }
}
