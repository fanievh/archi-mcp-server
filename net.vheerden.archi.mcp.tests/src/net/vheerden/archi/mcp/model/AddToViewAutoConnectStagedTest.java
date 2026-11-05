package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
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
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;

/**
 * Pins that {@code add-to-view} with {@code autoConnect} draws, in a {@code bulk-mutate} call and inside
 * a {@code begin-batch}, the connections the direct call would draw at that point: to objects placed and
 * for relationships created earlier in the call or the batch, not to objects or for relationships the
 * staged work removes, and not for a relationship created later. The result names what it drew.
 *
 * <p>{@code create-relationship} cannot run in this lane (Archi's relationship matrix does not
 * initialise outside the PDE runtime), so a relationship the batch creates is queued as its command,
 * which is exactly what the tool queues; a relationship the same call creates is pinned at the reader.</p>
 */
public class AddToViewAutoConnectStagedTest {

    private static final String SESSION = ViewContentsFixture.SESSION;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ViewContentsFixture f;
    private IArchimateElement p1;
    private IArchimateElement p2;
    private IArchimateRelationship r12;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-auto-connect-staged");
        p1 = f.element("p1");
        p2 = f.element("p2");
        r12 = relationship("r12", p1, p2);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- bulk-mutate ------------------------------------------------------------------------------

    @Test
    public void shouldConnectTwoObjectsPlacedOnAViewTheSameCallCreated() {
        BulkMutationResult result = bulk(op("create-view", Map.of("name", "Built"), "v"),
                place("$v.id", "p1", 40, false), place("$v.id", "p2", 300, true));

        IArchimateDiagramModel view = view(result.operations().get(0).entityId());
        List<IDiagramModelArchimateConnection> drawn = connections(view);
        assertEquals(1, drawn.size());
        List<ViewConnectionDto> reported = result.operations().get(2).autoConnections();
        assertEquals(1, reported.size());
        assertEquals(drawn.get(0).getId(), reported.get(0).viewConnectionId());
        assertEquals("r12", reported.get(0).relationshipId());
        assertEquals(result.operations().get(1).entityId(), reported.get(0).sourceViewObjectId());
        assertEquals(result.operations().get(2).entityId(), reported.get(0).targetViewObjectId());
    }

    @Test
    public void shouldConnectToAnObjectAlreadyOnTheView() {
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);

        BulkMutationResult result = bulk(place(f.view.getId(), "p2", 300, true));

        assertEquals(List.of("r12:vo-1->" + result.operations().get(0).entityId()), lines(f.view));
        assertEquals(connections(f.view).get(0).getId(),
                result.operations().get(0).autoConnections().get(0).viewConnectionId());
    }

    @Test
    public void shouldDrawOneConnection_whenBothPlacementsAskForAutoConnect() {
        BulkMutationResult result = bulk(place(f.view.getId(), "p1", 40, true), place(f.view.getId(), "p2", 300, true));

        assertEquals(1, connections(f.view).size());
        assertEquals("the first placement has nothing to connect to yet", List.of(),
                result.operations().get(0).autoConnections());
        assertEquals(1, result.operations().get(1).autoConnections().size());
    }

    /** The control: without autoConnect the result carries exactly the keys it carried before. */
    @Test
    public void shouldAnswerAsBefore_whenAutoConnectIsNotAsked() throws Exception {
        BulkMutationResult result = bulk(place(f.view.getId(), "p1", 40, false), place(f.view.getId(), "p2", 300, false));

        assertEquals(List.of(), connections(f.view));
        for (BulkOperationResult op : result.operations()) {
            assertNull(op.autoConnections());
            assertEquals(List.of("index", "tool", "action", "entityId", "entityType", "entityName", "effectiveBounds"),
                    keys(op));
        }
    }

    @Test
    public void shouldAnswerAnEmptyList_whenAutoConnectFindsNothing() throws Exception {
        BulkMutationResult result = bulk(place(f.view.getId(), "p2", 300, true));

        assertEquals(List.of(), result.operations().get(0).autoConnections());
        assertTrue(keys(result.operations().get(0)).contains("autoConnections"));
        assertFalse(keys(result.operations().get(0)).contains("skippedDueToNesting"));
        assertFalse(keys(result.operations().get(0)).contains("skippedByCap"));
    }

    @Test
    public void shouldConnectForARelationshipTheEnclosingBatchQueued() {
        IArchimateElement a = f.element("a");
        IArchimateElement b = f.element("b");
        f.dispatcher.beginBatch(SESSION, "queued relationship");
        queueRelationship("r-ab", a, b);

        BulkMutationResult result = bulk(place(f.view.getId(), "a", 40, false), place(f.view.getId(), "b", 300, true));
        assertEquals("only queued so far", List.of(), connections(f.view));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of("r-ab:" + result.operations().get(0).entityId() + "->"
                + result.operations().get(1).entityId()), lines(f.view));
        assertEquals("r-ab", result.operations().get(1).autoConnections().get(0).relationshipId());
    }

    @Test
    public void shouldNotConnectForARelationshipTheBatchDeletesEarlier() {
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);
        f.dispatcher.beginBatch(SESSION, "queued delete");
        f.accessor.deleteRelationship(SESSION, "r12");

        BulkMutationResult result = bulk(place(f.view.getId(), "p2", 300, true));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), result.operations().get(0).autoConnections());
        assertEquals(List.of(), connections(f.view));
    }

    @Test
    public void shouldUndoThePlacementsAndTheConnectionAsOne() {
        BulkMutationResult result = bulk(op("create-view", Map.of("name", "Built"), "v"),
                place("$v.id", "p1", 40, false), place("$v.id", "p2", 300, true));
        IArchimateDiagramModel view = view(result.operations().get(0).entityId());
        assertEquals(1, connections(view).size());

        f.stack.undo();

        assertNull("the view the call created is gone", view(view.getId()));
        assertEquals(List.of(), view.getChildren());
        assertEquals(List.of(), p1.getSourceRelationships().get(0).getReferencingDiagramComponents());
    }

    @Test
    public void shouldListTheConnectionOnTheCard_andDrawExactlyThatOnApproval() {
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);
        f.approval = true;

        BulkMutationResult proposed = bulk(place(f.view.getId(), "p2", 300, true));
        String placed = proposed.operations().get(0).entityId();
        PendingProposal card = f.dispatcher.getProposal(SESSION, proposed.proposalContext().proposalId());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) card.proposedChanges().get("operations")).get(0);
        assertEquals(List.of(Map.of("relationshipId", "r12", "relationshipType", "AssociationRelationship",
                "sourceViewObjectId", "vo-1", "targetViewObjectId", placed)), row.get("autoConnections"));
        assertEquals("nothing drawn before approval", List.of(), connections(f.view));

        f.dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());

        assertEquals(List.of("r12:vo-1->" + placed), lines(f.view));
    }

    @Test
    public void shouldPutNoAutoConnectKeyOnTheCard_whenAutoConnectIsNotAsked() {
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);
        f.approval = true;

        BulkMutationResult proposed = bulk(place(f.view.getId(), "p2", 300, false));
        PendingProposal card = f.dispatcher.getProposal(SESSION, proposed.proposalContext().proposalId());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) card.proposedChanges().get("operations")).get(0);

        assertEquals(List.of("index", "tool", "name", "type"), new ArrayList<>(row.keySet()));
    }

    /**
     * A placement into a group the same call nests inside an element the same call placed sits inside
     * that element, as it would live, so the line to it is declined and named. The live climb stops at
     * the group, which is not attached yet.
     */
    @Test
    public void shouldDeclineAndNameTheLine_whenThePlacementNestsInsideAnElementTheCallPlacedTwoLevelsUp() {
        IArchimateElement c = f.element("c");
        IArchimateElement fe = f.element("fe");
        IArchimateRelationship composition = f.factory.createCompositionRelationship();
        composition.setId("comp");
        composition.connect(c, fe);
        f.model.getFolder(FolderType.RELATIONS).getElements().add(composition);

        BulkMutationResult result = bulk(
                op("add-to-view", Map.of("viewId", f.view.getId(), "elementId", "c", "x", 40, "y", 40,
                        "width", 600, "height", 400), "c"),
                op("add-group-to-view", Map.of("viewId", f.view.getId(), "label", "G", "parentViewObjectId", "$c.id",
                        "x", 20, "y", 30, "width", 400, "height", 300), "g"),
                op("add-to-view", Map.of("viewId", f.view.getId(), "elementId", "fe", "parentViewObjectId", "$g.id",
                        "x", 20, "y", 30, "autoConnect", true), null));

        BulkOperationResult placed = result.operations().get(2);
        assertEquals(List.of(), placed.autoConnections());
        assertEquals(1, placed.skippedDueToNesting().size());
        AddToViewResultDto.SkippedConnection skipped = placed.skippedDueToNesting().get(0);
        assertEquals(result.operations().get(0).entityId(), skipped.sourceViewObjectId());
        assertEquals(placed.entityId(), skipped.targetViewObjectId());
        assertEquals("comp", skipped.relationshipId());
        assertEquals(AutoConnectSkip.NESTING, skipped.reason());
        assertEquals(List.of(), connections(f.view));
    }

    /**
     * Drawing a connection re-connects its relationship, which moves it to the end of its element's
     * lists, so the direct call is made on a second, identical model rather than after the bulk one.
     */
    @Test
    public void shouldCapAtTheSamePairsAsTheDirectCall() throws Exception {
        ViewContentsFixture twin = new ViewContentsFixture("model-auto-connect-staged-twin");
        try {
            for (ViewContentsFixture each : List.of(f, twin)) {
                IArchimateElement hub = each.element("hub");
                for (int i = 0; i < PlacementAutoConnect.MAX_AUTO_CONNECTIONS + 1; i++) {
                    IArchimateElement spoke = each.element("spoke-" + i);
                    IArchimateRelationship rel = each.factory.createAssociationRelationship();
                    rel.setId("r-" + i);
                    rel.connect(hub, spoke);
                    each.model.getFolder(FolderType.RELATIONS).getElements().add(rel);
                    each.object(each.view, "vo-" + i, spoke, 10 * i, 300, 120, 55);
                }
            }

            BulkOperationResult bulked = bulk(place(f.view.getId(), "hub", 40, true)).operations().get(0);
            AddToViewResultDto immediate = twin.accessor.addToView(SESSION, twin.view.getId(), "hub", 40, 40, null,
                    null, true, null, null, null).entity();

            assertEquals(PlacementAutoConnect.MAX_AUTO_CONNECTIONS, bulked.autoConnections().size());
            assertEquals(PlacementAutoConnect.MAX_AUTO_CONNECTIONS, connections(f.view).size());
            assertEquals(1, bulked.skippedByCap().size());
            assertEquals("r-50", bulked.skippedByCap().get(0).relationshipId());
            assertEquals(relationshipIds(immediate.autoConnections()), relationshipIds(bulked.autoConnections()));
            assertEquals(immediate.skippedByCap().get(0).relationshipId(), bulked.skippedByCap().get(0).relationshipId());
        } finally {
            twin.dispose();
        }
    }

    @Test
    public void shouldReadTheAutoConnectFlagAsTheDirectCallDoes() {
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);

        BulkMutationResult result = bulk(op("add-to-view", Map.of("viewId", f.view.getId(), "elementId", "p2",
                "x", 300, "y", 40, "autoConnect", "TRUE"), null));

        assertEquals(1, result.operations().get(0).autoConnections().size());
        ModelAccessException refused = LabelExpressionStagedViewTest.refusalOf(() -> bulk(op("add-to-view",
                Map.of("viewId", f.view.getId(), "elementId", "p2", "x", 300, "y", 40, "autoConnect", "yes"), null)));
        assertTrue(refused.getMessage(), refused.getMessage().contains("autoConnect"));
    }

    // ---- begin-batch, the single tool ------------------------------------------------------------

    @Test
    public void batch_shouldConnectTwoObjectsQueuedOnAViewTheBatchQueued() {
        f.dispatcher.beginBatch(SESSION, "queued view");
        String viewId = f.accessor.createView(SESSION, "Queued", null, null, null).entity().id();
        String first = add(viewId, "p1", 40, false).viewObject().viewObjectId();
        AddToViewResultDto second = add(viewId, "p2", 300, true);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of("r12:" + first + "->" + second.viewObject().viewObjectId()), lines(view(viewId)));
        assertEquals(connections(view(viewId)).get(0).getId(), second.autoConnections().get(0).viewConnectionId());
    }

    @Test
    public void batch_shouldConnectToAnObjectTheBatchQueuedOnALiveView() {
        f.dispatcher.beginBatch(SESSION, "queued placement");
        String first = add(f.view.getId(), "p1", 40, false).viewObject().viewObjectId();
        AddToViewResultDto second = add(f.view.getId(), "p2", 300, true);
        assertEquals("only queued so far", List.of(), connections(f.view));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of("r12:" + first + "->" + second.viewObject().viewObjectId()), lines(f.view));
    }

    @Test
    public void batch_shouldConnectForARelationshipTheBatchQueuedEarlier() {
        IArchimateElement a = f.element("a");
        IArchimateElement b = f.element("b");
        f.dispatcher.beginBatch(SESSION, "queued relationship");
        queueRelationship("r-ab", a, b);
        add(f.view.getId(), "a", 40, false);
        AddToViewResultDto placed = add(f.view.getId(), "b", 300, true);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(1, connections(f.view).size());
        assertEquals("r-ab", placed.autoConnections().get(0).relationshipId());
    }

    /** Evaluated where the placement is staged: a relationship the batch creates after it is not drawn. */
    @Test
    public void batch_shouldNotConnectForARelationshipTheBatchCreatesLater() {
        IArchimateElement a = f.element("a");
        IArchimateElement b = f.element("b");
        f.dispatcher.beginBatch(SESSION, "relationship after the placement");
        add(f.view.getId(), "a", 40, false);
        AddToViewResultDto placed = add(f.view.getId(), "b", 300, true);
        queueRelationship("r-ab", a, b);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), placed.autoConnections());
        assertEquals(List.of(), connections(f.view));
        assertEquals("the relationship itself was created", 1, a.getSourceRelationships().size());
    }

    /** The removal sits between the object on the view and the placement that would connect to it. */
    @Test
    public void batch_shouldNotConnectToAnObjectTheBatchRemovesEarlier() {
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);
        f.dispatcher.beginBatch(SESSION, "queued removal");
        f.accessor.removeFromView(SESSION, f.view.getId(), "vo-1");
        AddToViewResultDto placed = add(f.view.getId(), "p2", 300, true);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), placed.autoConnections());
        assertEquals(List.of(), connections(f.view));
    }

    @Test
    public void batch_shouldNotConnectForARelationshipTheBatchDeletesEarlier() {
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);
        f.dispatcher.beginBatch(SESSION, "queued delete");
        f.accessor.deleteRelationship(SESSION, "r12");
        AddToViewResultDto placed = add(f.view.getId(), "p2", 300, true);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), placed.autoConnections());
        assertEquals(List.of(), connections(f.view));
    }

    /**
     * Evaluated where the placement is staged, so the line is proposed; a removal of its other end the
     * batch queues after it takes the line off with the object at commit, leaving no line behind.
     */
    @Test
    public void batch_shouldLeaveNoLine_whenTheBatchRemovesTheOtherEndAfterThePlacement() {
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);
        f.dispatcher.beginBatch(SESSION, "removal after the placement");
        AddToViewResultDto placed = add(f.view.getId(), "p2", 300, true);
        f.accessor.removeFromView(SESSION, f.view.getId(), "vo-1");
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("proposed where it was staged", List.of("r12"), relationshipIds(placed.autoConnections()));
        assertNull(object(f.view, "vo-1"));
        assertEquals(List.of(), connections(f.view));
        assertEquals("no line is left on the relationship", List.of(), r12.getReferencingDiagramComponents());
    }

    @Test
    public void batch_shouldLeaveTheModelAsItWas_onRollback() {
        f.dispatcher.beginBatch(SESSION, "rolled back");
        add(f.view.getId(), "p1", 40, false);
        add(f.view.getId(), "p2", 300, true);
        f.dispatcher.endBatch(SESSION, false);

        assertEquals(List.of(), f.view.getChildren());
        assertEquals(List.of(), r12.getReferencingDiagramComponents());
    }

    /** The control: outside a batch, the direct call answers exactly as it did. */
    @Test
    public void immediate_shouldAnswerAsBefore() throws Exception {
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);

        AddToViewResultDto placed = add(f.view.getId(), "p2", 300, true);

        IDiagramModelArchimateConnection drawn = connections(f.view).get(0);
        assertEquals("{\"viewObject\":{\"viewObjectId\":\"" + placed.viewObject().viewObjectId()
                + "\",\"elementId\":\"p2\",\"elementName\":\"p2\",\"elementType\":\"ApplicationComponent\","
                + "\"x\":300,\"y\":40,\"width\":120,\"height\":55},\"autoConnections\":[{\"viewConnectionId\":\""
                + drawn.getId() + "\",\"relationshipId\":\"r12\",\"relationshipType\":\"AssociationRelationship\","
                + "\"sourceViewObjectId\":\"vo-1\",\"targetViewObjectId\":\"" + placed.viewObject().viewObjectId()
                + "\"}]}", JSON.writeValueAsString(placed));
    }

    @Test
    public void approval_shouldListTheConnectionOnTheCard_andDrawItOnApproval() {
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);
        f.approval = true;

        MutationResult<AddToViewResultDto> proposed = f.accessor.addToView(SESSION, f.view.getId(), "p2", 300, 40,
                null, null, true, null, null, null);
        PendingProposal card = f.dispatcher.getProposal(SESSION, proposed.proposalContext().proposalId());
        String placed = proposed.entity().viewObject().viewObjectId();

        assertEquals(List.of(Map.of("relationshipId", "r12", "relationshipType", "AssociationRelationship",
                "sourceViewObjectId", "vo-1", "targetViewObjectId", placed)), card.proposedChanges().get("autoConnections"));
        f.dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());
        assertEquals(1, connections(f.view).size());
        assertEquals("r12", connections(f.view).get(0).getArchimateRelationship().getId());
    }

    // ---- the reader -----------------------------------------------------------------------------

    @Test
    public void relationshipsOf_shouldListARelationshipTheSameCallCreates_withItsEndsFromTheCommand() {
        IArchimateElement a = f.element("a");
        IArchimateElement b = f.element("b");
        IArchimateRelationship created = f.factory.createAssociationRelationship();
        created.setId("r-new");
        StagedViewState staged = StagedViewState.of(null, null, List.of(new PreparedMutation<>(
                new CreateRelationshipCommand(created, f.model.getFolder(FolderType.RELATIONS), a, b), null, "r-new")));

        List<PlacementAutoConnect.Joined> outgoing = staged.relationshipsOf(a, true);
        List<PlacementAutoConnect.Joined> incoming = staged.relationshipsOf(b, false);

        assertEquals(1, outgoing.size());
        assertSame(created, outgoing.get(0).relationship());
        assertSame(b, outgoing.get(0).otherEnd());
        assertSame(a, incoming.get(0).otherEnd());
        assertNull("not connected until it runs", created.getSource());
    }

    @Test
    public void relationshipsOf_shouldListARelationshipOnce_whenTheStagedWorkNamesALiveOneAgain() {
        StagedViewState staged = StagedViewState.of(null, null, List.of(new PreparedMutation<>(
                new CreateRelationshipCommand(r12, f.model.getFolder(FolderType.RELATIONS), p1, p2), null, "r12")));

        List<PlacementAutoConnect.Joined> outgoing = staged.relationshipsOf(p1, true);

        assertEquals(1, outgoing.size());
        assertSame(r12, outgoing.get(0).relationship());
    }

    @Test
    public void relationshipsOf_shouldDropTheRelationshipsOfAnElementAStagedDeleteRemoves() {
        IArchimateElement a = f.element("a");
        IArchimateRelationship created = f.factory.createAssociationRelationship();
        created.setId("r-new");
        f.dispatcher.beginBatch(SESSION, "delete");
        f.dispatcher.queueForBatch(SESSION, new CreateRelationshipCommand(created,
                f.model.getFolder(FolderType.RELATIONS), a, p2), "queued relationship");
        f.accessor.deleteElement(SESSION, "p1");

        StagedViewState staged = StagedViewState.of(f.dispatcher, SESSION, null);
        List<String> incoming = new ArrayList<>();
        for (PlacementAutoConnect.Joined joined : staged.relationshipsOf(p2, false)) {
            incoming.add(joined.relationship().getId());
        }
        f.dispatcher.endBatch(SESSION, false);

        assertEquals("r12 goes with p1; the staged create from a stays", List.of("r-new"), incoming);
    }

    /** A {@code delete-folder} deletes what its cascade names, at any depth: a relationship, and an element with its relationships. */
    @Test
    public void relationshipsOf_shouldDropWhatAStagedFolderDeleteCascades() {
        IArchimateElement x = f.element("x");
        relationship("rx2", x, p2);
        IFolder outer = folder("f-outer", f.model.getFolder(FolderType.RELATIONS));
        IFolder inner = folder("f-inner", outer);
        inner.getElements().add(r12);
        IFolder holder = folder("f-holder", f.model.getFolder(FolderType.APPLICATION));
        holder.getElements().add(x);
        f.dispatcher.beginBatch(SESSION, "folder deletes");
        f.accessor.deleteFolder(SESSION, "f-outer", true);
        f.accessor.deleteFolder(SESSION, "f-holder", true);

        List<String> incoming = new ArrayList<>();
        for (PlacementAutoConnect.Joined joined : StagedViewState.of(f.dispatcher, SESSION, null).relationshipsOf(p2, false)) {
            incoming.add(joined.relationship().getId());
        }
        f.dispatcher.endBatch(SESSION, false);

        assertEquals(List.of(), incoming);
    }

    /** A queued type change replaces the relationship with a new object when it runs: nothing can be drawn for the old one. */
    @Test
    public void relationshipsOf_shouldNotListARelationshipAStagedTypeChangeReplaces() {
        f.dispatcher.beginBatch(SESSION, "retype");
        f.dispatcher.queueForBatch(SESSION, new ChangeRelationshipTypeCommand(f.model, List.of(),
                Map.of("r12", r12.eClass()), null, null), "queued type change");

        List<PlacementAutoConnect.Joined> incoming = StagedViewState.of(f.dispatcher, SESSION, null).relationshipsOf(p2, false);
        f.dispatcher.endBatch(SESSION, false);

        assertEquals(List.of(), relationships(incoming));
    }

    @Test
    public void relationshipsOf_shouldAnswerTheLiveListsInOrder_whenNothingIsStaged() {
        IArchimateElement x = f.element("x");
        IArchimateRelationship r1x = relationship("r1x", p1, x);
        IArchimateRelationship rx1 = relationship("rx1", x, p1);
        StagedViewState staged = StagedViewState.of(null, null, null);

        assertEquals(List.of(r12, r1x), relationships(staged.relationshipsOf(p1, true)));
        assertEquals(List.of(rx1), relationships(staged.relationshipsOf(p1, false)));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    IFolder folder(String id, IFolder parent) {
        IFolder folder = f.factory.createFolder();
        folder.setId(id);
        folder.setName(id);
        parent.getFolders().add(folder);
        return folder;
    }

    IArchimateRelationship relationship(String id, IArchimateElement source, IArchimateElement target) {
        IArchimateRelationship rel = f.factory.createAssociationRelationship();
        rel.setId(id);
        rel.connect(source, target);
        f.model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    /** Queues a relationship create as {@code create-relationship} queues it, without its validation. */
    void queueRelationship(String id, IArchimateElement source, IArchimateElement target) {
        IArchimateRelationship rel = f.factory.createAssociationRelationship();
        rel.setId(id);
        f.dispatcher.queueForBatch(SESSION, new CreateRelationshipCommand(rel,
                f.model.getFolder(FolderType.RELATIONS), source, target), "queued relationship");
    }

    static BulkOperation op(String tool, Map<String, Object> params, String as) {
        return new BulkOperation(tool, params, as);
    }

    static BulkOperation place(String viewId, String elementId, int x, boolean autoConnect) {
        return autoConnect
                ? op("add-to-view", Map.of("viewId", viewId, "elementId", elementId, "x", x, "y", 40,
                        "autoConnect", true), null)
                : op("add-to-view", Map.of("viewId", viewId, "elementId", elementId, "x", x, "y", 40), null);
    }

    AddToViewResultDto add(String viewId, String elementId, int x, boolean autoConnect) {
        return f.accessor.addToView(SESSION, viewId, elementId, x, 40, null, null, autoConnect, null, null, null).entity();
    }

    BulkMutationResult bulk(BulkOperation... operations) {
        return f.accessor.executeBulk(SESSION, List.of(operations), "auto-connect", false, null);
    }

    IArchimateDiagramModel view(String id) {
        return f.model.getDiagramModels().stream().filter(v -> v.getId().equals(id))
                .map(IArchimateDiagramModel.class::cast).findFirst().orElse(null);
    }

    static List<String> keys(Object value) throws Exception {
        JsonNode node = JSON.valueToTree(value);
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
            keys.add(it.next());
        }
        return keys;
    }

    static List<String> relationshipIds(List<ViewConnectionDto> connections) {
        List<String> ids = new ArrayList<>();
        for (ViewConnectionDto connection : connections) {
            ids.add(connection.relationshipId());
        }
        return ids;
    }

    static List<IArchimateRelationship> relationships(List<PlacementAutoConnect.Joined> joined) {
        List<IArchimateRelationship> out = new ArrayList<>();
        for (PlacementAutoConnect.Joined each : joined) {
            out.add(each.relationship());
        }
        return out;
    }

    /** Every element-to-element connection on {@code container} at any depth. */
    static List<IDiagramModelArchimateConnection> connections(IDiagramModelContainer container) {
        List<IDiagramModelArchimateConnection> out = new ArrayList<>();
        for (IDiagramModelObject child : container.getChildren()) {
            for (Object connection : child.getSourceConnections()) {
                if (connection instanceof IDiagramModelArchimateConnection archimate) {
                    out.add(archimate);
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                out.addAll(connections(nested));
            }
        }
        return out;
    }

    /** Each connection as {@code relationshipId:sourceObjectId->targetObjectId}. */
    static List<String> lines(IDiagramModelContainer container) {
        List<String> out = new ArrayList<>();
        for (IDiagramModelArchimateConnection connection : connections(container)) {
            assertNotNull(connection.getArchimateRelationship());
            out.add(connection.getArchimateRelationship().getId() + ":" + connection.getSource().getId() + "->"
                    + connection.getTarget().getId());
        }
        return out;
    }

    static IDiagramModelArchimateObject object(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child.getId().equals(id) && child instanceof IDiagramModelArchimateObject archimate) {
                return archimate;
            }
        }
        return null;
    }
}
