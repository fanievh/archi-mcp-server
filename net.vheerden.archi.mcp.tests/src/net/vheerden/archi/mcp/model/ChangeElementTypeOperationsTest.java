package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.ArchiPlugin;
import com.archimatetool.editor.preferences.IPreferenceConstants;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto;
import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;

/**
 * {@code change-element-type} at the accessor: the caller errors and the order they are checked
 * in, the approval proposal, the refusal inside a batch, and the merge it prepares. Every refusal
 * is checked to leave the model unchanged and to store no proposal, under the approval gate.
 *
 * <p>Archi's relationship rule cannot load in a plain JVM, so the operations are built here with
 * a stand-in that refuses named type triples, over the accessor's own dispatcher. The accessor's
 * forward is exercised on an element without relationships, where the rule is never asked, and
 * production's two sources of truth are pinned by reading the source.</p>
 */
public class ChangeElementTypeOperationsTest {

    private static final String SESSION = "change-type-session";
    private static final IArchimatePackage P = IArchimatePackage.eINSTANCE;

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private final Set<String> refused = new HashSet<>();
    private final ChangeElementTypeOperations.TypeRule rule = (source, target, relationship) ->
            !refused.contains(source.getName() + ">" + target.getName() + ":" + relationship.getName());
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private ArchiModelAccessorImpl accessor;
    private ChangeElementTypeOperations ops;
    private CommandStack stack;
    private final List<Command> dispatched = new ArrayList<>();
    /** Runs on an immediate call, after it is prepared and before its command executes. */
    private Runnable beforeDispatch;
    private boolean installedPlugin;

    @Before
    public void setUp() {
        if (ArchiPlugin.getInstance() == null) {
            new ArchiPlugin() {
                private final IPreferenceStore store = new PreferenceStore();

                @Override
                public IPreferenceStore getPreferenceStore() {
                    return store;
                }
            };
            installedPlugin = true;
        }
        model = factory.createArchimateModel();
        model.setId("model-change-type-ops");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        IFolder sub = factory.createFolder();
        sub.setId("folder-1");
        model.getFolder(FolderType.BUSINESS).getFolders().add(sub);
        actor("a", "Customer");
        actor("b", "Clerk");
        IArchimateElement x = factory.createApplicationComponent();
        x.setId("x");
        x.setName("Billing");
        model.getFolder(FolderType.APPLICATION).getElements().add(x);
        IArchimateElement junction = factory.createJunction();
        junction.setId("j");
        model.getFolder(FolderType.OTHER).getElements().add(junction);
        relationship(factory.createAssociationRelationship(), "rel-1", element("a"), element("x"));
        IDiagramModelArchimateObject box = box("box-a", element("a"), 0);
        IDiagramModelArchimateObject boxX = box("box-x", element("x"), 300);
        IDiagramModelArchimateConnection drawn = factory.createDiagramModelArchimateConnection();
        drawn.setId("conn-1");
        drawn.setArchimateRelationship((IArchimateRelationship) find("rel-1"));
        drawn.connect(box, boxX);
        models.setModels(List.of(model));

        stack = new CommandStack();
        MutationDispatcher dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                if (beforeDispatch != null) {
                    beforeDispatch.run();
                }
                dispatched.add(command);
                stack.execute(command);
            }

            @Override
            protected void dispatchCommand(Command command) {
                dispatched.add(command);
                stack.execute(command);
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
        ops = new ChangeElementTypeOperations(
                sessionId -> accessor.getMutationDispatcher().admit(sessionId, "change-element-type", model),
                accessor::getMutationDispatcher, () -> { }, rule, type -> 0);
    }

    @After
    public void tearDown() {
        accessor.dispose();
        if (installedPlugin) {
            ArchiPlugin.INSTANCE = null;
        }
    }

    // ---- caller errors ------------------------------------------------------------------------

    @Test
    public void shouldRefuseAnEmptyCall_andAnEntryWithoutAnIdOrAType_namingTheIndex() {
        assertRefusedList(ErrorCode.INVALID_PARAMETER, "changes must list at least one element", List.of());
        assertRefusedList(ErrorCode.INVALID_PARAMETER, "changes must list at least one element", null);
        assertRefused(ErrorCode.INVALID_PARAMETER, "changes[1] has no elementId",
                change("a", "BusinessRole"), change(null, "BusinessRole"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "changes[0] has no elementId", change(" ", "BusinessRole"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "changes[0] has no type", change("a", null));
        assertRefusedList(ErrorCode.INVALID_PARAMETER, "changes[0] has no elementId", Arrays.asList(
                (ElementTypeChangeRequest) null));
    }

    @Test
    public void shouldRefuseAnElementListedTwice_namingBothIndexes() {
        assertRefused(ErrorCode.INVALID_PARAMETER, "Element a appears at both changes[0] and changes[2]",
                change("a", "BusinessRole"), change("b", "BusinessRole"), change("a", "BusinessEvent"));
    }

    @Test
    public void shouldRefuseAnIdThatNamesNothing_asElementNotFound() {
        assertRefused(ErrorCode.ELEMENT_NOT_FOUND, "Element not found: ghost (changes[0])",
                change("ghost", "BusinessRole"));
    }

    @Test
    public void shouldRefuseAnIdOfAnotherKind_namingWhatItIs() {
        assertRefused(ErrorCode.INVALID_PARAMETER, "rel-1 (changes[0]) is a relationship",
                change("rel-1", "BusinessRole"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "view-1 (changes[0]) is a view", change("view-1", "BusinessRole"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "folder-1 (changes[0]) is a folder",
                change("folder-1", "BusinessRole"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "box-a (changes[0]) is a view object",
                change("box-a", "BusinessRole"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "conn-1 (changes[0]) is a view connection",
                change("conn-1", "BusinessRole"));
    }

    @Test
    public void shouldRefuseATypeThatNamesNoConcreteElementType() {
        ModelAccessException unknown = assertRefused(ErrorCode.INVALID_ELEMENT_TYPE,
                "Invalid ArchiMate element type: Wizard (changes[0])", change("a", "Wizard"));
        assertTrue(unknown.getMessage().startsWith("Invalid ArchiMate element type: "));
        assertRefused(ErrorCode.INVALID_ELEMENT_TYPE,
                "Invalid ArchiMate element type: AssociationRelationship (changes[0]) is a relationship type",
                change("a", "AssociationRelationship"));
        assertRefused(ErrorCode.INVALID_ELEMENT_TYPE,
                "Invalid ArchiMate element type: DiagramModelNote (changes[0]) is not an element type",
                change("a", "DiagramModelNote"));
        assertRefused(ErrorCode.INVALID_ELEMENT_TYPE,
                "Invalid ArchiMate element type: ArchimateElement (changes[0]) is abstract",
                change("a", "ArchimateElement"));
        assertRefused(ErrorCode.INVALID_ELEMENT_TYPE,
                "Invalid ArchiMate element type: BusinessElement (changes[0]) is abstract",
                change("a", "BusinessElement"));
    }

    @Test
    public void shouldRefuseTheTypeTheElementAlreadyHas() {
        assertRefused(ErrorCode.INVALID_ELEMENT_TYPE, "a (changes[0]) is already a BusinessActor",
                change("a", "BusinessActor"));
    }

    @Test
    public void shouldRefuseAJunction_eitherWay_asArchiDoes() {
        assertRefused(ErrorCode.INVALID_ELEMENT_TYPE,
                "j (changes[0]) is a Junction: Archi does not change a Junction's type", change("j", "BusinessRole"));
        ModelAccessException intoJunction = assertRefused(ErrorCode.INVALID_ELEMENT_TYPE,
                "changes[0] asks for Junction: Archi does not change an element into a Junction",
                change("a", "Junction"));
        assertFalse("a is not a Junction, so its refusal gives only its own reason",
                intoJunction.getMessage().contains("a Junction's type"));
    }

    @Test
    public void shouldRefuseEveryRelationshipTheNewTypesWouldMakeInvalid_namingEachOne() {
        relationship(factory.createAssignmentRelationship(), "rel-assign", element("a"), element("b"));
        refused.add("BusinessEvent>ApplicationComponent:AssociationRelationship");
        refused.add("BusinessEvent>BusinessActor:AssignmentRelationship");

        ModelAccessException e = assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "The new types would leave 2 relationships that ArchiMate does not allow: "
                        + "rel-1 (AssociationRelationship from a as BusinessEvent to x as ApplicationComponent); "
                        + "rel-assign (AssignmentRelationship from a as BusinessEvent to b as BusinessActor). "
                        + "Nothing was changed.",
                change("a", "BusinessEvent"));
        assertTrue(e.getSuggestedCorrection().contains("delete-relationship"));
    }

    @Test
    public void shouldJudgeARelationshipBetweenTwoListedElements_byBothNewTypes() {
        relationship(factory.createAssignmentRelationship(), "rel-assign", element("a"), element("b"));
        // valid only when both ends change; invalid with either one alone
        refused.add("BusinessRole>BusinessActor:AssignmentRelationship");
        refused.add("BusinessActor>BusinessRole:AssignmentRelationship");
        refused.add("BusinessActor>BusinessActor:AssignmentRelationship");

        ops.changeElementType(SESSION, List.of(change("a", "BusinessRole"), change("b", "BusinessRole")));

        assertEquals(P.getBusinessRole(), element("a").eClass());
        assertEquals(P.getBusinessRole(), element("b").eClass());

        refused.clear();
        dispatched.clear();
        // valid with the partner as it is, invalid with the partner's new type
        refused.add("BusinessCollaboration>BusinessEvent:AssignmentRelationship");
        assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED, "rel-assign (AssignmentRelationship from a as "
                + "BusinessCollaboration to b as BusinessEvent)",
                change("a", "BusinessCollaboration"), change("b", "BusinessEvent"));
    }

    @Test
    public void shouldEchoAnUnusualIdEscaped() {
        assertRefused(ErrorCode.ELEMENT_NOT_FOUND, "Element not found: 'p\\nq' (changes[0])", change("p\nq", "X"));
        assertRefused(ErrorCode.INVALID_ELEMENT_TYPE, "Invalid ArchiMate element type: 'W\\tz' (changes[0])",
                change("a", "W\tz"));
    }

    @Test
    public void shouldCheckEveryEntryInTheStatedOrder_beforeAnyIsChanged() {
        // shape of every entry before any id is checked
        assertRefused(ErrorCode.INVALID_PARAMETER, "changes[1] has no type",
                change("ghost", "BusinessRole"), change("a", null));
        // repeated ids before resolution
        assertRefused(ErrorCode.INVALID_PARAMETER, "appears at both",
                change("ghost", "BusinessRole"), change("ghost", "BusinessRole"));
        // resolution of every entry before types
        assertRefused(ErrorCode.ELEMENT_NOT_FOUND, "Element not found: ghost",
                change("a", "Wizard"), change("ghost", "BusinessRole"));
        // types of every entry before relationships
        refused.add("BusinessEvent>ApplicationComponent:AssociationRelationship");
        assertRefused(ErrorCode.INVALID_ELEMENT_TYPE, "Invalid ArchiMate element type: Wizard",
                change("a", "BusinessEvent"), change("b", "Wizard"));
        // and a good entry before a bad one is not changed
        assertRefused(ErrorCode.INVALID_ELEMENT_TYPE, "already a BusinessActor",
                change("b", "BusinessRole"), change("a", "BusinessActor"));
        assertEquals(P.getBusinessActor(), element("b").eClass());
    }

    // ---- approval -----------------------------------------------------------------------------

    @Test
    public void shouldStoreOneProposal_describingTheChange_andLeaveTheModelUntilApproval() throws Exception {
        approvalOn();
        com.archimatetool.model.IProfile profile = factory.createProfile();
        profile.setId("profile-vip");
        profile.setName("VIP");
        profile.setConceptType("BusinessActor");
        model.getProfiles().add(profile);
        element("a").getProfiles().add(profile);
        IArchimateElement original = element("a");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        MutationResult<ChangeElementTypeResultDto> result = ops.changeElementType(SESSION,
                List.of(change("a", "BusinessRole"), change("b", "BusinessRole")));

        assertNotNull(result.proposalContext());
        assertNotNull("the preview is the entity", result.entity());
        assertEquals(1, accessor.getMutationDispatcher().getPendingProposals(SESSION).size());
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(SESSION,
                result.proposalContext().proposalId());
        assertEquals("change-element-type", pending.tool());
        assertEquals("Change the type of 2 elements (1 relationship re-pointed, 1 view object re-pointed, "
                + "1 specialization dropped)", pending.description());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) pending.proposedChanges().get("changes");
        assertEquals(2, changes.size());
        Map<String, Object> first = changes.get(0);
        assertEquals("a", first.get("elementId"));
        assertEquals("Customer", first.get("name"));
        assertEquals("BusinessActor", first.get("fromType"));
        assertEquals("BusinessRole", first.get("toType"));
        assertEquals(1, first.get("relationshipsRepointed"));
        assertEquals(1, first.get("diagramObjectsRepointed"));
        assertEquals(false, first.get("folderChanged"));
        assertEquals(model.getFolder(FolderType.BUSINESS).getId(), first.get("folderId"));
        assertEquals("VIP", first.get("specializationDropped"));
        assertTrue(!changes.get(1).containsKey("specializationDropped"));
        assertEquals(Set.of("a", "b"), Set.copyOf(pending.capture().targetIds()));
        MergeElementsCommandTest.assertSnapshot("nothing is written until approval", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertTrue(dispatched.isEmpty());

        accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());

        assertEquals(P.getBusinessRole(), element("a").eClass());
        assertEquals("one stack entry for the whole call", 1, dispatched.size());
        stack.undo();
        MergeElementsCommandTest.assertSnapshot("one undo restores the approved change", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame(original, element("a"));
    }

    @Test
    public void shouldRejectTheProposalAsStale_whenAnElementIsRemovedWhileItWaits() {
        approvalOn();
        MutationResult<ChangeElementTypeResultDto> result = ops.changeElementType(SESSION,
                List.of(change("a", "BusinessRole"), change("b", "BusinessRole")));
        model.getFolder(FolderType.BUSINESS).getElements().remove(element("b"));

        try {
            accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());
            fail("an element removed while the proposal waited must make it stale");
        } catch (MutationException expected) {
            // stale
        }
        assertEquals(P.getBusinessActor(), element("a").eClass());
        assertTrue(dispatched.isEmpty());
    }

    /**
     * The approved change is read again from the model, so a valid relationship added while the
     * card waits moves too; one the new type does not allow was not on the card, so approval
     * refuses it with the relationship named and writes nothing.
     */
    @Test
    public void shouldDeclineAnApprovedChange_thatAnInvalidRelationshipAddedMeanwhileWouldBreak() {
        approvalOn();
        MutationResult<ChangeElementTypeResultDto> result = ops.changeElementType(SESSION,
                List.of(change("a", "BusinessRole")));
        IArchimateRelationship late = relationship(factory.createAssignmentRelationship(), "rel-late",
                element("a"), element("x"));
        refused.add("BusinessRole>ApplicationComponent:AssignmentRelationship");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        try {
            accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());
            fail("the rebuild must refuse the call as the model now stands");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("rel-late (AssignmentRelationship from a as "
                    + "BusinessRole to x as ApplicationComponent)"));
        }
        assertTrue(dispatched.isEmpty());
        assertEquals("the card stays, with its reason", 1,
                accessor.getMutationDispatcher().getPendingProposals(SESSION).size());

        MergeElementsCommandTest.assertSnapshot("the approved change must write nothing", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame(element("a"), late.getSource());
        assertEquals(P.getBusinessActor(), element("a").eClass());
    }

    @Test
    public void shouldMoveAValidRelationshipAddedWhileTheCardWaits() {
        approvalOn();
        MutationResult<ChangeElementTypeResultDto> result = ops.changeElementType(SESSION,
                List.of(change("a", "BusinessRole")));
        IArchimateRelationship late = relationship(factory.createAssociationRelationship(), "rel-late",
                element("b"), element("a"));

        accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());

        assertEquals(P.getBusinessRole(), element("a").eClass());
        assertSame(element("a"), late.getTarget());
    }

    /**
     * An immediate change that declines as it runs, with nothing written, answers the same refusal
     * as before and leaves the model version alone; one that runs advances it once.
     */
    @Test
    public void shouldLeaveTheVersion_whenAnImmediateChangeDeclinesWithNothingWritten() {
        java.util.concurrent.atomic.AtomicInteger bumps = new java.util.concurrent.atomic.AtomicInteger();
        ChangeElementTypeOperations counted = new ChangeElementTypeOperations(
                sessionId -> accessor.getMutationDispatcher().admit(sessionId, "change-element-type", model),
                accessor::getMutationDispatcher, bumps::incrementAndGet, rule, type -> 0);
        beforeDispatch = () -> {
            relationship(factory.createAssignmentRelationship(), "rel-late", element("a"), element("x"));
            refused.add("BusinessRole>ApplicationComponent:AssignmentRelationship");
        };

        ModelAccessException e = refusal(() -> counted.changeElementType(SESSION, List.of(change("a", "BusinessRole"))));

        assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
        assertEquals("change-element-type wrote nothing: The new types would leave 1 relationship that ArchiMate "
                + "does not allow: rel-late (AssignmentRelationship from a as BusinessRole to x as "
                + "ApplicationComponent). Nothing was changed.", e.getMessage());
        assertEquals("Nothing was changed. Re-read the elements with get-element and call change-element-type "
                + "again; if it stops the same way, the change cannot be made as asked.", e.getSuggestedCorrection());
        assertEquals("a decline that wrote nothing is no new version", 0, bumps.get());

        beforeDispatch = null;
        counted.changeElementType(SESSION, List.of(change("b", "BusinessRole")));
        assertEquals(P.getBusinessRole(), element("b").eClass());
        assertEquals("a change that ran is one new version", 1, bumps.get());
    }

    // ---- batch --------------------------------------------------------------------------------

    @Test
    public void shouldRefuseInsideABatch_queueNothing_andLeaveTheBatchUsable() {
        accessor.getMutationDispatcher().beginBatch(SESSION, "cleanup");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        int queued = accessor.getMutationDispatcher().getBatchStatus(SESSION).queuedCount();

        ModelAccessException e = refusal(() -> ops.changeElementType(SESSION, List.of(change("a", "BusinessRole"))));

        assertEquals(ErrorCode.BATCH_ALREADY_ACTIVE, e.getErrorCode());
        assertEquals("change-element-type cannot run inside a batch: it replaces each element with one of the new "
                + "type, which a queued operation could not see. Nothing was queued.", e.getMessage());
        assertEquals("Call end-batch first (with rollback: true to discard the queue), then change-element-type "
                + "on its own.", e.getSuggestedCorrection());
        assertEquals(queued, (int) accessor.getMutationDispatcher().getBatchStatus(SESSION).queuedCount());
        MergeElementsCommandTest.assertSnapshot("model unchanged", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));

        accessor.updateElement(SESSION, "b", "Clerk 2", null, null, null);
        assertEquals("the batch still takes the next call", queued + 1,
                (int) accessor.getMutationDispatcher().getBatchStatus(SESSION).queuedCount());
    }

    /**
     * A card proposed outside a batch and approved inside one would otherwise be queued, where the
     * operations queued after it hold the element it replaces.
     */
    @Test
    public void shouldRefuseToApproveInsideABatch_keepTheCard_andQueueNothing() {
        approvalOn();
        MutationResult<ChangeElementTypeResultDto> result = ops.changeElementType(SESSION,
                List.of(change("a", "BusinessRole")));
        accessor.getMutationDispatcher().beginBatch(SESSION, "cleanup");
        int queued = accessor.getMutationDispatcher().getBatchStatus(SESSION).queuedCount();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        try {
            accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());
            fail("approving inside a batch must be refused like the call itself");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("change-element-type cannot run inside a batch"));
        }
        assertEquals(queued, (int) accessor.getMutationDispatcher().getBatchStatus(SESSION).queuedCount());
        assertEquals("the card stays, with its reason", 1,
                accessor.getMutationDispatcher().getPendingProposals(SESSION).size());
        MergeElementsCommandTest.assertSnapshot("model unchanged", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    @Test
    public void shouldRefuseInsideABatch_evenACallThatWouldOtherwiseBeACallerError() {
        accessor.getMutationDispatcher().beginBatch(SESSION, "cleanup");
        ModelAccessException e = refusal(() -> ops.changeElementType(SESSION, List.of(change("ghost", "X"))));
        assertEquals("the batch is the reason, checked before the entries", ErrorCode.BATCH_ALREADY_ACTIVE,
                e.getErrorCode());
    }

    // ---- the accessor's forward and production's rules ----------------------------------------

    @Test
    public void shouldChangeTheTypeThroughTheAccessor_withArchisDefaultFigure() {
        IArchimateElement lone = factory.createBusinessActor();
        lone.setId("lone");
        model.getFolder(FolderType.BUSINESS).getElements().add(lone);
        IDiagramModelArchimateObject box = box("box-lone", lone, 600);
        ArchiPlugin.getInstance().getPreferenceStore().setValue(
                IPreferenceConstants.DEFAULT_FIGURE_PREFIX + "BusinessRole", 1);
        try {
            MutationResult<ChangeElementTypeResultDto> result = accessor.changeElementType(SESSION,
                    List.of(change("lone", "BusinessRole")));

            assertNull(result.proposalContext());
            assertEquals(P.getBusinessRole(), element("lone").eClass());
            assertEquals("the figure comes from Archi's preference", 1, box.getType());
            assertEquals(Map.of("lone", "lone"), result.entity().idMap());
            stack.undo();
            assertSame(lone, element("lone"));
        } finally {
            ArchiPlugin.getInstance().getPreferenceStore().setToDefault(
                    IPreferenceConstants.DEFAULT_FIGURE_PREFIX + "BusinessRole");
        }
    }

    @Test
    public void shouldAskArchisRuleAndArchisPreference_inProduction() throws Exception {
        String ops = Files.readString(repoFile(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ChangeElementTypeOperations.java"));
        assertTrue("production's rule is Archi's isValidRelationship and nothing else",
                ops.contains("static final TypeRule ARCHI = ArchimateModelUtils::isValidRelationship;"));
        assertTrue("production's figure is Archi's default-figure preference",
                ops.contains("static final ToIntFunction<EClass> ARCHI_FIGURE = type -> ArchiPlugin.getInstance()"
                        + ".getPreferenceStore()\n            .getInt(IPreferenceConstants.DEFAULT_FIGURE_PREFIX "
                        + "+ type.getName());"));
        assertTrue("the accessor's constructor passes both",
                ops.contains("this(admittedModel, dispatcher, bumpVersion, ARCHI, ARCHI_FIGURE);"));
        String impl = Files.readString(repoFile(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java"));
        assertTrue("the accessor builds the operations with production's rules",
                impl.contains("new ChangeElementTypeOperations(sessionId -> getMutationDispatcher().admit(sessionId, "
                        + "\"change-element-type\", requireAndCaptureModel()), this::getMutationDispatcher, "
                        + "this::bumpVersion);"));
    }

    // ---- the merge it prepares ----------------------------------------------------------------

    @Test
    public void shouldRefuseToMergeADuplicateOfAnotherType_namingThisTool() {
        IArchimateElement role = factory.createBusinessRole();
        role.setId("role");
        model.getFolder(FolderType.BUSINESS).getElements().add(role);
        ModelAccessException e = refusal(() -> accessor.mergeElements(SESSION,
                List.of(new MergeClusterRequest("a", List.of("role"))), null, null, false, false));
        assertEquals(ErrorCode.INVALID_ELEMENT_TYPE, e.getErrorCode());
        assertEquals("Change its type first with change-element-type, or leave it out of this cluster.",
                e.getSuggestedCorrection());
    }

    @Test
    public void shouldMergeACrossTypeDuplicate_afterChangingItsType_withTheSameIds() {
        IArchimateElement role = factory.createBusinessRole();
        role.setId("role");
        role.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(role);
        IArchimateRelationship onRole = relationship(factory.createAssociationRelationship(), "rel-role",
                role, element("x"));
        IDiagramModelArchimateObject roleBox = box("box-role", role, 900);

        ModelAccessException refusedMerge = refusal(() -> accessor.mergeElements(SESSION,
                List.of(new MergeClusterRequest("a", List.of("role"))), null, null, false, false));
        assertEquals(ErrorCode.INVALID_ELEMENT_TYPE, refusedMerge.getErrorCode());

        ChangeElementTypeResultDto changed = ops.changeElementType(SESSION,
                List.of(change("role", "BusinessActor"))).entity();
        assertEquals(Map.of("role", "role"), changed.idMap());
        assertEquals("the duplicate now has the target's type", P.getBusinessActor(), element("role").eClass());

        MergeElementsResultDto merged = accessor.mergeElements(SESSION,
                List.of(new MergeClusterRequest("a", List.of(changed.idMap().get("role")))), null, null, false, false)
                .entity();

        assertEquals(List.of("role"), merged.clusters().get(0).mergedSourceIds());
        assertNull(find("role"));
        assertSame(element("a"), onRole.getSource());
        assertSame(element("a"), roleBox.getArchimateElement());
        assertSame(element("a"), ((IArchimateRelationship) find("rel-1")).getSource());
    }

    // ---- relationships converted to Associations ----------------------------------------------

    /** Leaving the choice out, or choosing refuse, is the refusal this tool has always given. */
    @Test
    public void shouldGiveTheSameRefusal_whenInvalidRelationshipsIsRefuseOrAbsent() {
        refused.add("BusinessEvent>ApplicationComponent:AssociationRelationship");
        String expected = "The new types would leave 1 relationship that ArchiMate does not allow: "
                + "rel-1 (AssociationRelationship from a as BusinessEvent to x as ApplicationComponent). "
                + "Nothing was changed.";

        ModelAccessException absent = assertRefusedWith(null, ErrorCode.RELATIONSHIP_NOT_ALLOWED, expected,
                change("a", "BusinessEvent"));
        ModelAccessException refuse = assertRefusedWith("refuse", ErrorCode.RELATIONSHIP_NOT_ALLOWED, expected,
                change("a", "BusinessEvent"));

        assertEquals(expected, absent.getMessage());
        assertEquals(absent.getMessage(), refuse.getMessage());
        assertEquals(absent.getSuggestedCorrection(), refuse.getSuggestedCorrection());
        assertEquals("Delete those relationships first (delete-relationship), or choose a type that allows them.",
                refuse.getSuggestedCorrection());
    }

    @Test
    public void shouldRefuseAnyOtherInvalidRelationshipsValue_namingBothAccepted() {
        for (String value : List.of("association", "Refuse", "TO-ASSOCIATION", "")) {
            ModelAccessException e = assertRefusedWith(value, ErrorCode.INVALID_PARAMETER,
                    "invalidRelationships " + CallerText.quoted(value) + " is not one of refuse, to-association",
                    change("a", "BusinessRole"));
            assertEquals("Use refuse (the default) or to-association.", e.getSuggestedCorrection());
        }
    }

    @Test
    public void shouldCheckInvalidRelationshipsWithTheShapeOfTheEntries_beforeRepeatedIds() {
        assertRefusedWith("bogus", ErrorCode.INVALID_PARAMETER, "changes[1] has no type",
                change("a", "BusinessRole"), change("b", null));
        assertRefusedWith("bogus", ErrorCode.INVALID_PARAMETER, "invalidRelationships 'bogus'",
                change("a", "BusinessRole"), change("a", "BusinessRole"));
        assertRefusedWith("to-association", ErrorCode.INVALID_PARAMETER, "appears at both",
                change("a", "BusinessRole"), change("a", "BusinessRole"));
    }

    /** ArchiMate allows an Association wherever this runs; were it not to, the call is refused. */
    @Test
    public void shouldRefuseAConversion_thatTheRuleWouldNotAllowAsAnAssociationEither() {
        refused.add("BusinessEvent>ApplicationComponent:AssociationRelationship");
        relationship(factory.createAssignmentRelationship(), "rel-assign", element("a"), element("b"));
        refused.add("BusinessEvent>BusinessActor:AssignmentRelationship");

        ModelAccessException e = assertRefusedWith("to-association", ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "The new types would leave 1 relationship that ArchiMate does not allow, and that cannot be made "
                        + "an Association either: rel-1 (AssociationRelationship from a as BusinessEvent to x as "
                        + "ApplicationComponent). Nothing was changed.",
                change("a", "BusinessEvent"));
        assertEquals("Delete those relationships first (delete-relationship), or choose a type that allows them.",
                e.getSuggestedCorrection());
    }

    @Test
    public void shouldConvertAndReportIt_whenCalledImmediately() {
        relationship(factory.createServingRelationship(), "rel-serve", element("x"), element("a"));
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");

        ChangeElementTypeResultDto result = ops.changeElementType(SESSION, List.of(change("a", "BusinessEvent")),
                "to-association").entity();

        assertEquals(P.getAssociationRelationship(), ((IArchimateRelationship) find("rel-serve")).eClass());
        assertEquals("rel-serve", result.changes().get(0).relationshipsConverted().get(0).resultingId());
        assertEquals(1, result.totals().relationshipsConverted());
        assertEquals(1, dispatched.size());
        stack.undo();
        assertEquals(P.getServingRelationship(), ((IArchimateRelationship) find("rel-serve")).eClass());
        assertEquals(P.getBusinessActor(), element("a").eClass());
    }

    @Test
    public void shouldListEveryRelationshipItWouldConvert_onTheCard() throws Exception {
        approvalOn();
        com.archimatetool.model.IAccessRelationship access = factory.createAccessRelationship();
        relationship(access, "rel-access", element("a"), element("x"));
        access.setAccessType(com.archimatetool.model.IAccessRelationship.READ_ACCESS);
        com.archimatetool.model.IProfile profile = factory.createProfile();
        profile.setId("profile-strong");
        profile.setName("Strong");
        profile.setConceptType("AccessRelationship");
        model.getProfiles().add(profile);
        access.getProfiles().add(profile);
        relationship(factory.createServingRelationship(), "rel-serve", element("x"), element("a"));
        refused.add("BusinessEvent>ApplicationComponent:AccessRelationship");
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        MutationResult<ChangeElementTypeResultDto> result = ops.changeElementType(SESSION,
                List.of(change("a", "BusinessEvent")), "to-association");

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(SESSION,
                result.proposalContext().proposalId());
        assertEquals("Change the type of 1 element (3 relationships re-pointed, 1 view object re-pointed, "
                + "0 specializations dropped, 2 relationships converted to Association)", pending.description());
        assertEquals("to-association", pending.proposedChanges().get("invalidRelationships"));
        assertEquals("added to each converted relationship if Archi's 'add documentation note' preference is on "
                + "when the change runs", pending.proposedChanges().get("documentationNote"));
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = ((List<Map<String, Object>>) pending.proposedChanges().get("changes")).get(0);
        assertEquals(List.of(
                Map.of("relationshipId", "rel-access", "fromType", "AccessRelationship", "source", "a",
                        "target", "x", "attributesDropped", Map.of("accessType", "read"),
                        "specializationDropped", "Strong"),
                Map.of("relationshipId", "rel-serve", "fromType", "ServingRelationship", "source", "x",
                        "target", "a")),
                entry.get("relationshipsConverted"));
        assertEquals(Set.of("a", "rel-access", "rel-serve"), Set.copyOf(pending.capture().targetIds()));
        assertEquals("the preview nests what it would convert", 2,
                result.entity().changes().get(0).relationshipsConverted().size());
        MergeElementsCommandTest.assertSnapshot("nothing is written until approval", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));

        accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());

        assertEquals(P.getAssociationRelationship(), ((IArchimateRelationship) find("rel-access")).eClass());
        assertEquals(P.getAssociationRelationship(), ((IArchimateRelationship) find("rel-serve")).eClass());
        assertEquals("one stack entry for the whole call", 1, dispatched.size());
    }

    @Test
    public void shouldKeepTheCardAsItWas_whenTheChoiceIsLeftOut() {
        approvalOn();
        MutationResult<ChangeElementTypeResultDto> result = ops.changeElementType(SESSION,
                List.of(change("a", "BusinessRole")));
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(SESSION,
                result.proposalContext().proposalId());
        assertEquals("Change the type of 1 element (1 relationship re-pointed, 1 view object re-pointed, "
                + "0 specializations dropped)", pending.description());
        assertEquals(List.of("changes"), List.copyOf(pending.proposedChanges().keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = ((List<Map<String, Object>>) pending.proposedChanges().get("changes")).get(0);
        assertTrue(!entry.containsKey("relationshipsConverted"));
    }

    /**
     * An approved card converts only the relationships it listed: one that became invalid while
     * the card waited makes the approved change write nothing, and the approval says so, keeps the
     * card and leaves the model version where it was.
     */
    @Test
    public void shouldWriteNothingOnApproval_whenARelationshipTheCardDidNotListMustBeConverted() {
        approvalOn();
        relationship(factory.createServingRelationship(), "rel-serve", element("x"), element("a"));
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
        MutationResult<ChangeElementTypeResultDto> result = ops.changeElementType(SESSION,
                List.of(change("a", "BusinessEvent")), "to-association");
        relationship(factory.createFlowRelationship(), "rel-late", element("x"), element("a"));
        refused.add("ApplicationComponent>BusinessEvent:FlowRelationship");
        ops.changeElementType(SESSION, List.of(change("b", "BusinessRole")));
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        String version = accessor.getModelVersion();

        try {
            accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());
            fail("an approval that wrote nothing must not report success");
        } catch (MutationException e) {
            assertEquals("change-element-type wrote nothing: the change would also convert rel-late to an "
                    + "Association, which the call was not prepared to convert. Nothing was changed. Reject this "
                    + "proposal and ask the agent to make the change again.", e.getMessage());
        }

        assertEquals(1, dispatched.size());
        assertEquals("change-element-type wrote nothing: the change would also convert rel-late to an "
                + "Association, which the call was not prepared to convert",
                ((ChangeElementTypeCommand) dispatched.get(0)).getSkipReason());
        MergeElementsCommandTest.assertSnapshot("the approved change must write nothing", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals("the card stays, in its place ahead of the later one", List.of("p-1", "p-2"),
                accessor.getMutationDispatcher().getPendingProposals(SESSION).stream()
                        .map(PendingProposal::proposalId).toList());
        assertEquals("nothing was written, so the version stays", version, accessor.getModelVersion());
    }

    @Test
    public void shouldAskArchisNotePreferenceAndLabel_inProduction() throws Exception {
        String ops = Files.readString(repoFile(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ChangeElementTypeOperations.java"));
        assertTrue("production's note is Archi's add-documentation-note preference",
                ops.contains("static final BooleanSupplier ARCHI_NOTE = () -> ArchiPlugin.getInstance()"
                        + ".getPreferenceStore()\n            .getBoolean(IPreferenceConstants"
                        + ".ADD_DOCUMENTATION_NOTE_ON_RELATION_CHANGE);"));
        assertTrue("production's label is Archi's default name for the type",
                ops.contains("static final Function<EClass, String> ARCHI_LABEL = type -> "
                        + "ArchiLabelProvider.INSTANCE.getDefaultName(type);"));
        assertTrue("the rule-and-figure constructor passes both",
                ops.contains("this(admittedModel, dispatcher, bumpVersion, rule, defaultFigure, ARCHI_NOTE, "
                        + "ARCHI_LABEL);"));
        String retype = Files.readString(repoFile(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/RelationshipRetype.java"));
        assertTrue("the note is Archi's own text",
                retype.contains("NLS.bind(Messages.SetConceptTypeCommandFactory_2"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Refused with this code and cause, the model unchanged and no proposal stored, under approval. */
    private ModelAccessException assertRefusedWith(String invalidRelationships, ErrorCode code, String messagePart,
            ElementTypeChangeRequest... changes) {
        approvalOn();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        ModelAccessException e = refusal(() -> ops.changeElementType(SESSION, Arrays.asList(changes),
                invalidRelationships));
        assertEquals(e.getMessage(), code, e.getErrorCode());
        assertTrue("message '" + e.getMessage() + "' should contain '" + messagePart + "'",
                e.getMessage().contains(messagePart));
        MergeElementsCommandTest.assertSnapshot("refused means unchanged", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertTrue("refused means nothing proposed",
                accessor.getMutationDispatcher().getPendingProposals(SESSION).isEmpty());
        assertTrue(dispatched.isEmpty());
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> false);
        return e;
    }

    private void approvalOn() {
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);
    }

    private ModelAccessException assertRefused(ErrorCode code, String messagePart, ElementTypeChangeRequest... changes) {
        return assertRefusedList(code, messagePart, Arrays.asList(changes));
    }

    /** Refused with this code and cause, the model unchanged and no proposal stored, under approval. */
    private ModelAccessException assertRefusedList(ErrorCode code, String messagePart,
            List<ElementTypeChangeRequest> changes) {
        approvalOn();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        ModelAccessException e = refusal(() -> ops.changeElementType(SESSION, changes));
        assertEquals(e.getMessage(), code, e.getErrorCode());
        assertTrue("message '" + e.getMessage() + "' should contain '" + messagePart + "'",
                e.getMessage().contains(messagePart));
        assertNotNull("a caller error says what to do", e.getSuggestedCorrection());
        MergeElementsCommandTest.assertSnapshot("refused means unchanged", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertTrue("refused means nothing proposed",
                accessor.getMutationDispatcher().getPendingProposals(SESSION).isEmpty());
        assertTrue(dispatched.isEmpty());
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> false);
        return e;
    }

    private static ModelAccessException refusal(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        fail("expected a refusal");
        return null;
    }

    private static ElementTypeChangeRequest change(String id, String type) {
        return new ElementTypeChangeRequest(id, type);
    }

    private void actor(String id, String name) {
        IArchimateElement actor = factory.createBusinessActor();
        actor.setId(id);
        actor.setName(name);
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
    }

    private IArchimateRelationship relationship(IArchimateRelationship relationship, String id,
            IArchimateConcept source, IArchimateConcept target) {
        relationship.setId(id);
        relationship.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);
        return relationship;
    }

    private IDiagramModelArchimateObject box(String id, IArchimateElement element, int x) {
        IDiagramModelArchimateObject box = factory.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element);
        box.setBounds(x, 0, 120, 55);
        view.getChildren().add(box);
        return box;
    }

    private IArchimateElement element(String id) {
        return (IArchimateElement) ArchimateModelUtils.getObjectByID(model, id);
    }

    private Object find(String id) {
        return ArchimateModelUtils.getObjectByID(model, id);
    }

    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(relative))) {
            dir = dir.getParent();
        }
        assertNotNull("repository root not found above the working directory", dir);
        return dir.resolve(relative);
    }
}
