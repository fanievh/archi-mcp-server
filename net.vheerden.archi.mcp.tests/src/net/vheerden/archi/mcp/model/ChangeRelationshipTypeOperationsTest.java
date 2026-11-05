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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IJunction;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ChangeRelationshipTypeResultDto;
import net.vheerden.archi.mcp.response.dto.RelationshipTypeChangeRequest;

/**
 * {@code change-relationship-type} at the accessor: the caller errors and the order they are
 * checked in, which new types ArchiMate allows, the approval proposal, and the refusal inside a
 * batch. Every refusal is checked to leave the model unchanged and to store no proposal, under the
 * approval gate.
 *
 * <p>Archi's type rule cannot load its matrix in a plain JVM, so the operations are built here
 * with stand-ins: a type rule that refuses named triples, a concept rule that records each call
 * and refuses a Junction end, and one that reads a Junction as Archi's does (see
 * {@link #junctionRule}). One test drives Archi's own concept rule, on a Junction it refuses before
 * reaching the matrix; production's two rules are pinned by reading the source.</p>
 */
public class ChangeRelationshipTypeOperationsTest {

    private static final String SESSION = "change-relationship-type-session";
    private static final IArchimatePackage P = IArchimatePackage.eINSTANCE;

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private final Set<String> refused = new HashSet<>();
    private final ChangeElementTypeOperations.TypeRule rule = (source, target, relationship) ->
            !refused.contains(source.getName() + ">" + target.getName() + ":" + relationship.getName());
    /** Each concept-rule call, as "source>target:type" by id and type name. */
    private final List<String> conceptCalls = new ArrayList<>();
    private final MergeElementsPlan.RelationshipRule conceptRule = (source, target, type) -> {
        conceptCalls.add(source.getId() + ">" + target.getId() + ":" + type.getName());
        return !(source instanceof IJunction || target instanceof IJunction);
    };
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private ArchiModelAccessorImpl accessor;
    private ChangeRelationshipTypeOperations ops;
    private CommandStack stack;
    private final List<Command> dispatched = new ArrayList<>();
    /** Runs on an immediate call, after it is prepared and before its command executes. */
    private Runnable beforeDispatch;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-change-relationship-type-ops");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        IFolder sub = factory.createFolder();
        sub.setId("folder-1");
        model.getFolder(FolderType.RELATIONS).getFolders().add(sub);
        element(factory.createApplicationComponent(), "x", "Billing", FolderType.APPLICATION);
        element(factory.createApplicationComponent(), "y", "Ledger", FolderType.APPLICATION);
        element(factory.createDataObject(), "d", "Invoice", FolderType.APPLICATION);
        element(factory.createBusinessProcess(), "e", "Bill", FolderType.BUSINESS);
        element(factory.createJunction(), "j", null, FolderType.OTHER);
        relationship(factory.createFlowRelationship(), "rel-1", find("x"), find("y")).setName("sends");
        IAccessRelationship access = factory.createAccessRelationship();
        relationship(access, "rel-acc", find("x"), find("d"));
        access.setAccessType(IAccessRelationship.READ_ACCESS);
        relationship(factory.createFlowRelationship(), "rel-j", find("e"), find("j"));
        IDiagramModelArchimateObject boxX = box("box-x", (IArchimateElement) find("x"), 0);
        IDiagramModelArchimateObject boxY = box("box-y", (IArchimateElement) find("y"), 300);
        IDiagramModelArchimateConnection drawn = factory.createDiagramModelArchimateConnection();
        drawn.setId("conn-1");
        drawn.setArchimateRelationship(relationship("rel-1"));
        drawn.connect(boxX, boxY);
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
        ops = operations(() -> { });
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    // ---- caller errors ------------------------------------------------------------------------

    @Test
    public void shouldRefuseAnEmptyCall_andAnEntryWithoutAnIdOrAType_namingTheIndex() {
        assertRefusedList(ErrorCode.INVALID_PARAMETER, "changes must list at least one relationship", List.of());
        assertRefusedList(ErrorCode.INVALID_PARAMETER, "changes must list at least one relationship", null);
        assertRefused(ErrorCode.INVALID_PARAMETER, "changes[1] has no relationshipId",
                change("rel-1", "TriggeringRelationship"), change(null, "TriggeringRelationship"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "changes[0] has no relationshipId",
                change(" ", "TriggeringRelationship"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "changes[0] has no type", change("rel-1", null));
        assertRefused(ErrorCode.INVALID_PARAMETER, "changes[0] has no type", change("rel-1", " "));
        assertRefusedList(ErrorCode.INVALID_PARAMETER, "changes[0] has no relationshipId", Arrays.asList(
                (RelationshipTypeChangeRequest) null));
    }

    @Test
    public void shouldRefuseARelationshipListedTwice_namingBothIndexes() {
        assertRefused(ErrorCode.INVALID_PARAMETER, "Relationship rel-1 appears at both changes[0] and changes[2]",
                change("rel-1", "TriggeringRelationship"), change("rel-acc", "TriggeringRelationship"),
                change("rel-1", "ServingRelationship"));
    }

    @Test
    public void shouldRefuseAnIdThatNamesNothing_asRelationshipNotFound() {
        assertRefused(ErrorCode.RELATIONSHIP_NOT_FOUND, "Relationship not found: ghost (changes[0])",
                change("ghost", "TriggeringRelationship"));
    }

    @Test
    public void shouldRefuseAnIdOfAnotherKind_namingWhatItIs() {
        ModelAccessException element = assertRefused(ErrorCode.INVALID_PARAMETER,
                "x (changes[0]) is an element; change-relationship-type changes relationships only",
                change("x", "TriggeringRelationship"));
        assertTrue(element.getSuggestedCorrection(), element.getSuggestedCorrection().contains("change-element-type"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "view-1 (changes[0]) is a view",
                change("view-1", "TriggeringRelationship"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "folder-1 (changes[0]) is a folder",
                change("folder-1", "TriggeringRelationship"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "box-x (changes[0]) is a view object",
                change("box-x", "TriggeringRelationship"));
        ModelAccessException connection = assertRefused(ErrorCode.INVALID_PARAMETER,
                "conn-1 (changes[0]) is a view connection", change("conn-1", "TriggeringRelationship"));
        assertTrue(connection.getSuggestedCorrection(),
                connection.getSuggestedCorrection().contains("get-view-contents gives the relationship id each "
                        + "view connection draws"));
    }

    /** The type is resolved by the one relationship-type resolver, with its own texts. */
    @Test
    public void shouldRefuseATypeThatNamesNoConcreteRelationshipType_withTheResolversTexts() {
        assertRefused(ErrorCode.INVALID_RELATIONSHIP_TYPE, "Invalid ArchiMate relationship type: Wizard",
                change("rel-1", "Wizard"));
        assertRefused(ErrorCode.INVALID_RELATIONSHIP_TYPE, "'BusinessActor' is not an ArchiMate relationship type",
                change("rel-1", "BusinessActor"));
        assertRefused(ErrorCode.INVALID_RELATIONSHIP_TYPE, "Invalid ArchiMate relationship type: "
                + "ArchimateRelationship is abstract: it groups relationship types and names none of them",
                change("rel-1", "ArchimateRelationship"));
    }

    @Test
    public void shouldRefuseTheTypeTheRelationshipAlreadyHas() {
        assertRefused(ErrorCode.INVALID_RELATIONSHIP_TYPE, "rel-1 (changes[0]) is already a FlowRelationship",
                change("rel-1", "FlowRelationship"));
    }

    @Test
    public void shouldEchoAnUnusualIdEscaped() {
        assertRefused(ErrorCode.RELATIONSHIP_NOT_FOUND, "Relationship not found: 'p\\nq' (changes[0])",
                change("p\nq", "TriggeringRelationship"));
    }

    @Test
    public void shouldCheckEveryEntryInTheStatedOrder_beforeAnyIsChanged() {
        // shape of every entry before any id is checked
        assertRefused(ErrorCode.INVALID_PARAMETER, "changes[1] has no type",
                change("ghost", "TriggeringRelationship"), change("rel-1", null));
        // repeated ids before resolution
        assertRefused(ErrorCode.INVALID_PARAMETER, "appears at both",
                change("ghost", "TriggeringRelationship"), change("ghost", "TriggeringRelationship"));
        // resolution of every entry before types
        assertRefused(ErrorCode.RELATIONSHIP_NOT_FOUND, "Relationship not found: ghost",
                change("rel-1", "Wizard"), change("ghost", "TriggeringRelationship"));
        // types of every entry before validity
        refused.add("ApplicationComponent>ApplicationComponent:TriggeringRelationship");
        assertRefused(ErrorCode.INVALID_RELATIONSHIP_TYPE, "Invalid ArchiMate relationship type: Wizard",
                change("rel-1", "TriggeringRelationship"), change("rel-acc", "Wizard"));
        // and a good entry before a bad one is not changed
        refused.clear();
        assertRefused(ErrorCode.INVALID_RELATIONSHIP_TYPE, "already a FlowRelationship",
                change("rel-acc", "AssociationRelationship"), change("rel-1", "FlowRelationship"));
        assertEquals(P.getAccessRelationship(), relationship("rel-acc").eClass());
    }

    // ---- which types ArchiMate allows -----------------------------------------------------------

    @Test
    public void shouldRefuseEveryRelationshipTheNewTypeWouldMakeInvalid_namingEachOne() {
        refused.add("ApplicationComponent>ApplicationComponent:TriggeringRelationship");
        refused.add("ApplicationComponent>DataObject:FlowRelationship");

        ModelAccessException e = assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "The new types would leave 2 relationships that ArchiMate does not allow: "
                        + "rel-1 (FlowRelationship → TriggeringRelationship from x as ApplicationComponent to y as "
                        + "ApplicationComponent); "
                        + "rel-acc (AccessRelationship → FlowRelationship from x as ApplicationComponent to d as "
                        + "DataObject). Nothing was changed.",
                change("rel-1", "TriggeringRelationship"), change("rel-acc", "FlowRelationship"));
        assertTrue(e.getSuggestedCorrection(), e.getSuggestedCorrection().contains("choose another type"));
    }

    @Test
    public void shouldRefuseAType_thatARelationshipAttachedToItWouldNotAllow() {
        relationship(factory.createAssociationRelationship(), "rel-on", find("d"), relationship("rel-1"));
        refused.add("DataObject>TriggeringRelationship:AssociationRelationship");

        assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "rel-1 (FlowRelationship → TriggeringRelationship from x as ApplicationComponent to y as "
                        + "ApplicationComponent): the relationship rel-on (AssociationRelationship) attached to it "
                        + "would not be allowed",
                change("rel-1", "TriggeringRelationship"));
    }

    /**
     * Two listed relationships joined to each other are judged with both new types: each end and
     * each attached relationship by the type it will have. Under Archi's own matrix only an
     * Association touches a relationship, so this is equivalent by construction there; the
     * stand-in rule can tell the two apart.
     */
    @Test
    public void shouldJudgeTwoJoinedListedRelationships_byBothNewTypes() {
        relationship(factory.createAssociationRelationship(), "rel-on", find("d"), relationship("rel-1"));
        // refused only when a relationship is judged by the class it has now
        refused.add("DataObject>FlowRelationship:FlowRelationship");
        refused.add("DataObject>TriggeringRelationship:AssociationRelationship");

        ops.changeRelationshipType(SESSION, List.of(change("rel-1", "TriggeringRelationship"),
                change("rel-on", "FlowRelationship")));

        assertEquals(P.getTriggeringRelationship(), relationship("rel-1").eClass());
        assertEquals(P.getFlowRelationship(), relationship("rel-on").eClass());
        assertSame(relationship("rel-1"), relationship("rel-on").getTarget());
    }

    // ---- a Junction, judged by the types the call leaves -------------------------------------------

    /**
     * A relationship alone at a Junction already leaves it one type: the rule at the Junction is
     * asked of the state the call leaves, and here it allows the change.
     */
    @Test
    public void shouldChangeTheOnlyRelationshipAtAJunction_whenArchisRuleAllowsTheStateItLeaves() {
        ChangeRelationshipTypeOperations judged = junctionOperations();

        judged.changeRelationshipType(SESSION, List.of(change("rel-j", "TriggeringRelationship")));

        assertEquals(P.getTriggeringRelationship(), relationship("rel-j").eClass());
        assertEquals(List.of("e>j:TriggeringRelationship [rel-j:TriggeringRelationship]",
                "e>j:TriggeringRelationship [rel-j:TriggeringRelationship]"), conceptCalls);
    }

    @Test
    public void shouldChangeEveryRelationshipAtAJunction_whenTheCallListsThemAllWithOneType() {
        wholeJunction();
        ChangeRelationshipTypeOperations judged = junctionOperations();

        judged.changeRelationshipType(SESSION, List.of(change("r-a", "TriggeringRelationship"),
                change("r-b", "TriggeringRelationship"), change("r-out", "TriggeringRelationship")));

        for (String id : List.of("r-a", "r-b", "r-out")) {
            assertEquals(id, P.getTriggeringRelationship(), relationship(id).eClass());
        }
        IJunction junction = (IJunction) find("J");
        assertEquals(List.of(relationship("r-out")), junction.getSourceRelationships());
        assertEquals(Set.of(relationship("r-a"), relationship("r-b")), Set.copyOf(junction.getTargetRelationships()));
        assertEquals(2, junction.getTargetRelationships().size());
        String seen = " [r-a:TriggeringRelationship, r-b:TriggeringRelationship, r-out:TriggeringRelationship]";
        assertEquals("each listed relationship is asked once when prepared and once as it runs, and the rule "
                + "sees every relationship at the Junction by the type the call leaves",
                List.of("a>J:TriggeringRelationship" + seen, "b>J:TriggeringRelationship" + seen,
                        "J>c:TriggeringRelationship" + seen, "a>J:TriggeringRelationship" + seen,
                        "b>J:TriggeringRelationship" + seen, "J>c:TriggeringRelationship" + seen),
                conceptCalls);
    }

    @Test
    public void shouldApproveAWholeJunctionChange_asOneCardAndOneUndo_whenTheOneOutIsListedLast() {
        approveAWholeJunctionChange(false);
    }

    @Test
    public void shouldApproveAWholeJunctionChange_asOneCardAndOneUndo_whenTheOneOutIsListedFirst() {
        approveAWholeJunctionChange(true);
    }

    private void approveAWholeJunctionChange(boolean outFirst) {
        wholeJunction();
        ChangeRelationshipTypeOperations judged = junctionOperations();
        approvalOn();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, Set<String>> endsBefore = ChangeRelationshipTypeCommandTest.endLists(model);
        IArchimateRelationship oldA = relationship("r-a");
        List<RelationshipTypeChangeRequest> changes = outFirst
                ? List.of(change("r-out", "TriggeringRelationship"), change("r-a", "TriggeringRelationship"),
                        change("r-b", "TriggeringRelationship"))
                : List.of(change("r-a", "TriggeringRelationship"), change("r-b", "TriggeringRelationship"),
                        change("r-out", "TriggeringRelationship"));

        MutationResult<ChangeRelationshipTypeResultDto> result = judged.changeRelationshipType(SESSION, changes);
        assertEquals(1, accessor.getMutationDispatcher().getPendingProposals(SESSION).size());
        accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());

        for (String id : List.of("r-a", "r-b", "r-out")) {
            assertEquals(id, P.getTriggeringRelationship(), relationship(id).eClass());
        }
        assertEquals("one stack entry for the whole Junction", 1, dispatched.size());
        stack.undo();
        MergeElementsCommandTest.assertSnapshot("one undo restores the whole Junction", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(endsBefore, ChangeRelationshipTypeCommandTest.endLists(model));
        assertSame(oldA, relationship("r-a"));
    }

    @Test
    public void shouldRefuseAJunctionChange_namingWhatItLeavesOut_whenTheCallListsOnlySome() {
        wholeJunction();
        ops = junctionOperations();

        ModelAccessException e = assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "The new types would leave 2 relationships that ArchiMate does not allow: r-a (FlowRelationship → "
                        + "TriggeringRelationship from a as BusinessProcess to J as Junction): it is joined to "
                        + "Junction J, where every relationship must share one type, and r-out (FlowRelationship) "
                        + "at that Junction would keep another type; r-b (FlowRelationship → TriggeringRelationship "
                        + "from b as BusinessEvent to J as Junction): it is joined to Junction J, where every "
                        + "relationship must share one type, and r-out (FlowRelationship) at that Junction would "
                        + "keep another type. Nothing was changed.",
                change("r-a", "TriggeringRelationship"), change("r-b", "TriggeringRelationship"));
        assertEquals("Leave those relationships out, or choose another type for them. For a relationship joined to "
                + "a Junction, list every relationship at that Junction in the same call, with one type.",
                e.getSuggestedCorrection());
    }

    @Test
    public void shouldRefuseAJunctionChange_namingTheOnesOfAnotherNewType_whenTheCallListsThemWithTwoTypes() {
        wholeJunction();
        ops = junctionOperations();

        assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "The new types would leave 3 relationships that ArchiMate does not allow: r-a (FlowRelationship → "
                        + "TriggeringRelationship from a as BusinessProcess to J as Junction): it is joined to "
                        + "Junction J, where every relationship must share one type, and r-out (ServingRelationship) "
                        + "at that Junction would keep another type; r-b (FlowRelationship → TriggeringRelationship "
                        + "from b as BusinessEvent to J as Junction): it is joined to Junction J, where every "
                        + "relationship must share one type, and r-out (ServingRelationship) at that Junction would "
                        + "keep another type; r-out (FlowRelationship → ServingRelationship from J as Junction to c "
                        + "as BusinessFunction): it is joined to Junction J, where every relationship must share one "
                        + "type, and r-a (TriggeringRelationship), r-b (TriggeringRelationship) at that Junction "
                        + "would keep another type. Nothing was changed.",
                change("r-a", "TriggeringRelationship"), change("r-b", "TriggeringRelationship"),
                change("r-out", "ServingRelationship"));
    }

    /** Archi's own rule answers here without its matrix: nothing leaves the Junction, and one Flow stays. */
    @Test
    public void shouldRefuseAJunctionChange_byArchisOwnRule_namingTheRelationshipLeftOut() {
        element(factory.createBusinessProcess(), "e2", "Pay", FolderType.BUSINESS);
        relationship(factory.createFlowRelationship(), "rel-j2", find("e2"), find("j"));
        ops = new ChangeRelationshipTypeOperations(
                sessionId -> accessor.getMutationDispatcher().admit(sessionId, "change-relationship-type", model),
                accessor::getMutationDispatcher, () -> { }, rule, MergeElementsPlan.ARCHI);

        assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "rel-j (FlowRelationship → TriggeringRelationship from e as BusinessProcess to j as Junction): it is "
                        + "joined to Junction j, where every relationship must share one type, and rel-j2 "
                        + "(FlowRelationship) at that Junction would keep another type. Nothing was changed.",
                change("rel-j", "TriggeringRelationship"));
    }

    /**
     * Every relationship at the Junction listed with one type, but a pair of concepts it joins may
     * not be: only the relationships that would carry it through the Junction are refused.
     */
    @Test
    public void shouldRefuseAJunctionChange_thatArchiMateDoesNotAllowThroughIt() {
        wholeJunction();
        ops = junctionOperations();
        refused.add("BusinessProcess>BusinessFunction:TriggeringRelationship");

        assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "The new types would leave 2 relationships that ArchiMate does not allow: r-a (FlowRelationship → "
                        + "TriggeringRelationship from a as BusinessProcess to J as Junction): it is joined to "
                        + "Junction J, and ArchiMate does not allow a TriggeringRelationship through it between "
                        + "every pair of concepts it joins; r-out (FlowRelationship → TriggeringRelationship from J "
                        + "as Junction to c as BusinessFunction): it is joined to Junction J, and ArchiMate does not "
                        + "allow a TriggeringRelationship through it between every pair of concepts it joins. "
                        + "Nothing was changed.",
                change("r-a", "TriggeringRelationship"), change("r-b", "TriggeringRelationship"),
                change("r-out", "TriggeringRelationship"));
    }

    @Test
    public void shouldJudgeEachOfTwoJoinedJunctions_overItsOwnRelationships() {
        twoJunctions();
        ops = junctionOperations();

        assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "The new types would leave 2 relationships that ArchiMate does not allow: r-1 (FlowRelationship → "
                        + "TriggeringRelationship from a as BusinessProcess to J1 as Junction): it is joined to "
                        + "Junction J1, where every relationship must share one type, and r-mid (FlowRelationship) at "
                        + "that Junction would keep another type; r-2 (FlowRelationship → TriggeringRelationship from "
                        + "J2 as Junction to c as BusinessFunction): it is joined to Junction J2, where every "
                        + "relationship must share one type, and r-mid (FlowRelationship) at that Junction would keep "
                        + "another type. Nothing was changed.",
                change("r-1", "TriggeringRelationship"), change("r-2", "TriggeringRelationship"));

        conceptCalls.clear();
        ops.changeRelationshipType(SESSION, List.of(change("r-1", "TriggeringRelationship"),
                change("r-mid", "TriggeringRelationship"), change("r-2", "TriggeringRelationship")));

        for (String id : List.of("r-1", "r-mid", "r-2")) {
            assertEquals(id, P.getTriggeringRelationship(), relationship(id).eClass());
        }
        assertEquals("the relationship joining them is seen once at each, by its new type",
                "J1>J2:TriggeringRelationship [r-1:TriggeringRelationship, r-2:TriggeringRelationship, "
                        + "r-mid:TriggeringRelationship]", conceptCalls.get(1));
    }

    /**
     * Judging the state the call leaves writes nothing and announces nothing: a refused call leaves
     * every end's lists as they were, object for object, and an allowed one is heard only from its
     * command.
     */
    @Test
    public void shouldJudgeAJunctionWithoutTouchingTheModel() {
        wholeJunction();
        ops = junctionOperations();
        List<Notification> heard = new ArrayList<>();
        EContentAdapter listener = new EContentAdapter() {
            @Override
            public void notifyChanged(Notification notification) {
                super.notifyChanged(notification);
                heard.add(notification);
            }
        };
        Map<IArchimateConcept, List<List<IArchimateRelationship>>> listsBefore = endListsByIdentity();
        model.eAdapters().add(listener);

        refusal(() -> ops.changeRelationshipType(SESSION, List.of(change("r-a", "TriggeringRelationship"))));

        assertEquals("a refused call is not heard", List.of(), heard);
        Map<IArchimateConcept, List<List<IArchimateRelationship>>> listsAfter = endListsByIdentity();
        assertEquals(listsBefore.keySet(), listsAfter.keySet());
        List<String> moved = new ArrayList<>();
        for (Map.Entry<IArchimateConcept, List<List<IArchimateRelationship>>> each : listsBefore.entrySet()) {
            List<List<IArchimateRelationship>> after = listsAfter.get(each.getKey());
            for (int side = 0; side < 2; side++) {
                List<IArchimateRelationship> was = each.getValue().get(side);
                List<IArchimateRelationship> is = after.get(side);
                boolean same = was.size() == is.size();
                for (int i = 0; same && i < was.size(); i++) {
                    same = was.get(i) == is.get(i);
                }
                if (!same) {
                    moved.add(each.getKey().getId() + (side == 0 ? " as source" : " as target"));
                }
            }
        }
        assertEquals("every end's lists hold the same objects in the same order", List.of(), moved);

        AtomicInteger checkedBeforeDispatch = new AtomicInteger();
        beforeDispatch = () -> {
            assertEquals("nothing is heard before the command runs", List.of(), heard);
            checkedBeforeDispatch.incrementAndGet();
        };
        ops.changeRelationshipType(SESSION, List.of(change("r-a", "TriggeringRelationship"),
                change("r-b", "TriggeringRelationship"), change("r-out", "TriggeringRelationship")));
        assertEquals("the check before the command ran once", 1, checkedBeforeDispatch.get());
        assertEquals(P.getTriggeringRelationship(), relationship("r-out").eClass());
        assertFalse("the command itself is heard", heard.isEmpty());
        model.eAdapters().remove(listener);
    }

    /** A relationship joined to the Junction while the card waits is judged when the card is approved. */
    @Test
    public void shouldRefuseToApproveAWholeJunctionChange_whenARelationshipJoinedTheJunctionWhileTheCardWaits() {
        wholeJunction();
        ops = junctionOperations();
        approvalOn();
        MutationResult<ChangeRelationshipTypeResultDto> result = ops.changeRelationshipType(SESSION,
                List.of(change("r-a", "TriggeringRelationship"), change("r-b", "TriggeringRelationship"),
                        change("r-out", "TriggeringRelationship")));
        element(factory.createBusinessProcess(), "late", "Late", FolderType.BUSINESS);
        relationship(factory.createFlowRelationship(), "r-late", find("late"), find("J"));
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        try {
            accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());
            fail("approval must judge the Junction as it is then");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("r-a (FlowRelationship → TriggeringRelationship from a "
                    + "as BusinessProcess to J as Junction): it is joined to Junction J, where every relationship "
                    + "must share one type, and r-late (FlowRelationship) at that Junction would keep another type"));
        }
        MergeElementsCommandTest.assertSnapshot("the approved change must write nothing", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(P.getFlowRelationship(), relationship("r-a").eClass());
        assertEquals("the card stays, with its reason", 1,
                accessor.getMutationDispatcher().getPendingProposals(SESSION).size());
    }

    /** A relationship from a Junction to itself is one relationship at it, named once. */
    @Test
    public void shouldCountAJunctionsLoopOnce_andChangeItWithTheRest() {
        relationship(factory.createFlowRelationship(), "rel-loop", find("j"), find("j"));
        ops = junctionOperations();

        assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "The new types would leave 1 relationship that ArchiMate does not allow: rel-j (FlowRelationship → "
                        + "TriggeringRelationship from e as BusinessProcess to j as Junction): it is joined to "
                        + "Junction j, where every relationship must share one type, and rel-loop (FlowRelationship) "
                        + "at that Junction would keep another type. Nothing was changed.",
                change("rel-j", "TriggeringRelationship"));

        ops.changeRelationshipType(SESSION, List.of(change("rel-j", "TriggeringRelationship"),
                change("rel-loop", "TriggeringRelationship")));
        assertEquals(P.getTriggeringRelationship(), relationship("rel-j").eClass());
        assertEquals(P.getTriggeringRelationship(), relationship("rel-loop").eClass());
        assertSame(find("j"), relationship("rel-loop").getSource());
        assertSame(find("j"), relationship("rel-loop").getTarget());
    }

    /**
     * Between two Junctions with nothing left to list, the type may not pass through one of them:
     * both are named, since either may be the one.
     */
    @Test
    public void shouldNameBothJunctions_whenTheTypeMayNotPassBetweenThem() {
        twoJunctions();
        ops = junctionOperations();
        refused.add("Junction>BusinessFunction:TriggeringRelationship");

        assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "The new types would leave 2 relationships that ArchiMate does not allow: r-mid (FlowRelationship → "
                        + "TriggeringRelationship from J1 as Junction to J2 as Junction): it is joined to Junctions J1 "
                        + "and J2, and ArchiMate does not allow a TriggeringRelationship through them between every "
                        + "pair of concepts they join; r-2 (FlowRelationship → TriggeringRelationship from J2 as "
                        + "Junction to c as BusinessFunction): it is joined to Junction J2, and ArchiMate does not "
                        + "allow a TriggeringRelationship through it between every pair of concepts it joins. "
                        + "Nothing was changed.",
                change("r-1", "TriggeringRelationship"), change("r-mid", "TriggeringRelationship"),
                change("r-2", "TriggeringRelationship"));
    }

    /**
     * A Junction at one end and a relationship at the other is asked of its ends as they are, and
     * refused as any relationship ArchiMate does not allow, with no Junction reason.
     */
    @Test
    public void shouldRefuseARelationshipBetweenAJunctionAndARelationship_withoutAJunctionReason() {
        relationship(factory.createAssociationRelationship(), "rel-jr", find("j"), relationship("rel-1"));

        assertRefused(ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                "The new types would leave 1 relationship that ArchiMate does not allow: rel-jr "
                        + "(AssociationRelationship → FlowRelationship from j as Junction to rel-1 as "
                        + "FlowRelationship). Nothing was changed.",
                change("rel-jr", "FlowRelationship"));
        assertEquals("asked of the ends as they are", List.of("j>rel-1:FlowRelationship"), conceptCalls);
    }

    /** Between two elements with no Junction, the concept rule adds nothing and is not asked. */
    @Test
    public void shouldAskTheConceptRule_onlyForAJunctionOrRelationshipEnd() {
        relationship(factory.createAssociationRelationship(), "rel-on", find("d"), relationship("rel-1"));

        ops.changeRelationshipType(SESSION, List.of(change("rel-1", "TriggeringRelationship"),
                change("rel-acc", "AssociationRelationship")));
        assertEquals("two element ends: never asked", List.of(), conceptCalls);

        ops.changeRelationshipType(SESSION, List.of(change("rel-on", "FlowRelationship")));
        assertEquals("a relationship end: asked with the ends as they are, when prepared and again as it runs",
                List.of("d>rel-1:FlowRelationship", "d>rel-1:FlowRelationship"), conceptCalls);
    }

    @Test
    public void shouldPassArchisTwoRules_inProduction() throws Exception {
        String source = Files.readString(repoFile(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ChangeRelationshipTypeOperations.java"));
        assertTrue("production's type rule is Archi's", source.contains(
                "this(admittedModel, dispatcher, bumpVersion, ChangeElementTypeOperations.ARCHI, MergeElementsPlan.ARCHI);"));
        assertEquals("one production constructor chain", 1, source.split("ChangeElementTypeOperations.ARCHI", -1).length - 1);
        assertEquals(1, source.split("MergeElementsPlan.ARCHI", -1).length - 1);
    }

    // ---- approval -----------------------------------------------------------------------------

    @Test
    public void shouldStoreOneProposal_describingTheChange_andLeaveTheModelUntilApproval() {
        approvalOn();
        com.archimatetool.model.IProfile profile = factory.createProfile();
        profile.setId("profile-strong");
        profile.setName("Strong");
        profile.setConceptType("AccessRelationship");
        model.getProfiles().add(profile);
        relationship("rel-acc").getProfiles().add(profile);
        relationship(factory.createAssociationRelationship(), "rel-on", find("d"), relationship("rel-1"));
        IArchimateRelationship original = relationship("rel-1");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        MutationResult<ChangeRelationshipTypeResultDto> result = ops.changeRelationshipType(SESSION,
                List.of(change("rel-1", "TriggeringRelationship"), change("rel-acc", "AssociationRelationship")));

        assertNotNull(result.proposalContext());
        assertNotNull("the preview is the entity", result.entity());
        assertEquals(1, accessor.getMutationDispatcher().getPendingProposals(SESSION).size());
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(SESSION,
                result.proposalContext().proposalId());
        assertEquals("change-relationship-type", pending.tool());
        assertEquals("Change the type of 2 relationships (1 view connection re-pointed, 1 attached relationship "
                + "re-pointed, 1 type attribute dropped, 1 specialization dropped)", pending.description());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) pending.proposedChanges().get("changes");
        assertEquals(2, changes.size());
        Map<String, Object> first = changes.get(0);
        assertEquals(List.of("relationshipId", "name", "fromType", "toType", "sourceId", "targetId",
                "connectionsRepointed", "relationshipsRepointed"), List.copyOf(first.keySet()));
        assertEquals("rel-1", first.get("relationshipId"));
        assertEquals("sends", first.get("name"));
        assertEquals("FlowRelationship", first.get("fromType"));
        assertEquals("TriggeringRelationship", first.get("toType"));
        assertEquals("x", first.get("sourceId"));
        assertEquals("y", first.get("targetId"));
        assertEquals(1, first.get("connectionsRepointed"));
        assertEquals(1, first.get("relationshipsRepointed"));
        Map<String, Object> second = changes.get(1);
        assertEquals(Map.of("accessType", "read"), second.get("attributesDropped"));
        assertEquals("Strong", second.get("specializationDropped"));
        assertEquals(Set.of("rel-1", "rel-acc"), Set.copyOf(pending.capture().targetIds()));
        MergeElementsCommandTest.assertSnapshot("nothing is written until approval", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertTrue(dispatched.isEmpty());

        accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());

        assertEquals(P.getTriggeringRelationship(), relationship("rel-1").eClass());
        assertEquals(P.getAssociationRelationship(), relationship("rel-acc").eClass());
        assertEquals("one stack entry for the whole call", 1, dispatched.size());
        stack.undo();
        MergeElementsCommandTest.assertSnapshot("one undo restores the approved change", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame(original, relationship("rel-1"));
    }

    @Test
    public void shouldDescribeOnlyWhatIsThere_onTheCard() {
        assertEquals("Change the type of 1 relationship (0 view connections re-pointed)",
                ChangeRelationshipTypeOperations.describe(new ChangeRelationshipTypeResultDto(List.of(), Map.of(),
                        new ChangeRelationshipTypeResultDto.Totals(1, 0, 0, 0, 0))));
        assertEquals("Change the type of 3 relationships (2 view connections re-pointed, 2 attached relationships "
                + "re-pointed, 2 type attributes dropped, 3 specializations dropped)",
                ChangeRelationshipTypeOperations.describe(new ChangeRelationshipTypeResultDto(List.of(), Map.of(),
                        new ChangeRelationshipTypeResultDto.Totals(3, 2, 2, 2, 3))));
    }

    /**
     * The approved change is resolved again on the model as it is then: a relationship whose class
     * was changed while the card waits makes approval refuse, with nothing written and the card kept.
     */
    @Test
    public void shouldRefuseToApproveAChange_whoseRelationshipChangedClassWhileTheCardWaits() {
        approvalOn();
        MutationResult<ChangeRelationshipTypeResultDto> result = ops.changeRelationshipType(SESSION,
                List.of(change("rel-1", "TriggeringRelationship")));
        IArchimateRelationship old = relationship("rel-1");
        model.getFolder(FolderType.RELATIONS).getElements().remove(old);
        old.disconnect();
        IArchimateRelationship replaced = relationship(factory.createAssignmentRelationship(), "rel-1", find("x"),
                find("y"));
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        try {
            accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());
            fail("approval must refuse a change the card no longer describes");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("rel-1"));
        }
        assertTrue(dispatched.stream().allMatch(c -> c instanceof ChangeRelationshipTypeCommand command
                && command.changedNothing()));
        assertEquals("the card stays, with its reason", 1,
                accessor.getMutationDispatcher().getPendingProposals(SESSION).size());
        MergeElementsCommandTest.assertSnapshot("the approved change must write nothing", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame(replaced, relationship("rel-1"));
    }

    /**
     * An immediate change that declines as it runs, with nothing written, answers a caller-facing
     * refusal and leaves the model version alone; one that runs advances it once.
     */
    @Test
    public void shouldLeaveTheVersion_whenAnImmediateChangeDeclinesWithNothingWritten() {
        AtomicInteger bumps = new AtomicInteger();
        ChangeRelationshipTypeOperations counted = operations(bumps::incrementAndGet);
        beforeDispatch = () -> refused.add("ApplicationComponent>ApplicationComponent:TriggeringRelationship");

        ModelAccessException e = refusal(() -> counted.changeRelationshipType(SESSION,
                List.of(change("rel-1", "TriggeringRelationship"))));

        assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
        assertEquals("change-relationship-type wrote nothing: The new types would leave 1 relationship that "
                + "ArchiMate does not allow: rel-1 (FlowRelationship → TriggeringRelationship from x as "
                + "ApplicationComponent to y as ApplicationComponent). Nothing was changed.", e.getMessage());
        assertEquals("Nothing was changed. Re-read the relationships with get-relationships and call "
                + "change-relationship-type again; if it stops the same way, the change cannot be made as asked.",
                e.getSuggestedCorrection());
        assertEquals("a decline that wrote nothing is no new version", 0, bumps.get());

        beforeDispatch = null;
        refused.clear();
        counted.changeRelationshipType(SESSION, List.of(change("rel-1", "TriggeringRelationship")));
        assertEquals(P.getTriggeringRelationship(), relationship("rel-1").eClass());
        assertEquals("a change that ran is one new version", 1, bumps.get());
    }

    /**
     * An immediate call answers what its command did when it ran, not what the call was prepared
     * against: a connection drawn between the two is re-pointed and counted.
     */
    @Test
    public void shouldAnswerWhatTheCommandDid_notThePreparedPreview_onAnImmediateCall() {
        beforeDispatch = () -> {
            IDiagramModelArchimateConnection late = factory.createDiagramModelArchimateConnection();
            late.setId("conn-late");
            late.setArchimateRelationship(relationship("rel-1"));
            late.connect((IDiagramModelArchimateObject) ArchimateModelUtils.getObjectByID(model, "box-x"),
                    (IDiagramModelArchimateObject) ArchimateModelUtils.getObjectByID(model, "box-y"));
        };

        ChangeRelationshipTypeResultDto result = ops.changeRelationshipType(SESSION,
                List.of(change("rel-1", "TriggeringRelationship"))).entity();

        assertEquals("both connections drawing it when the command ran", 2,
                result.changes().get(0).connectionsRepointed());
        assertEquals(2, result.totals().connectionsRepointed());
        assertSame(relationship("rel-1"), ((IDiagramModelArchimateConnection) ArchimateModelUtils
                .getObjectByID(model, "conn-late")).getArchimateRelationship());
    }

    // ---- batch --------------------------------------------------------------------------------

    @Test
    public void shouldRefuseInsideABatch_queueNothing_andLeaveTheBatchUsable() {
        accessor.getMutationDispatcher().beginBatch(SESSION, "cleanup");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        int queued = accessor.getMutationDispatcher().getBatchStatus(SESSION).queuedCount();

        ModelAccessException e = refusal(() -> ops.changeRelationshipType(SESSION,
                List.of(change("rel-1", "TriggeringRelationship"))));

        assertEquals(ErrorCode.BATCH_ALREADY_ACTIVE, e.getErrorCode());
        assertEquals("change-relationship-type cannot run inside a batch: it replaces each relationship with one "
                + "of the new type, which a queued operation could not see. Nothing was queued.", e.getMessage());
        assertEquals("Call end-batch first (with rollback: true to discard the queue), then change-relationship-type "
                + "on its own.", e.getSuggestedCorrection());
        assertEquals(queued, (int) accessor.getMutationDispatcher().getBatchStatus(SESSION).queuedCount());
        MergeElementsCommandTest.assertSnapshot("model unchanged", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));

        accessor.updateElement(SESSION, "x", "Billing 2", null, null, null);
        assertEquals("the batch still takes the next call", queued + 1,
                (int) accessor.getMutationDispatcher().getBatchStatus(SESSION).queuedCount());
    }

    @Test
    public void shouldRefuseToApproveInsideABatch_keepTheCard_andQueueNothing() {
        approvalOn();
        MutationResult<ChangeRelationshipTypeResultDto> result = ops.changeRelationshipType(SESSION,
                List.of(change("rel-1", "TriggeringRelationship")));
        accessor.getMutationDispatcher().beginBatch(SESSION, "cleanup");
        int queued = accessor.getMutationDispatcher().getBatchStatus(SESSION).queuedCount();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        try {
            accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());
            fail("approving inside a batch must be refused like the call itself");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("change-relationship-type cannot run inside a batch"));
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
        ModelAccessException e = refusal(() -> ops.changeRelationshipType(SESSION, List.of(change("ghost", "X"))));
        assertEquals("the batch is the reason, checked before the entries", ErrorCode.BATCH_ALREADY_ACTIVE,
                e.getErrorCode());
    }

    // ---- the accessor's forward -------------------------------------------------------------------

    /**
     * The accessor forwards to the operations with production's rules, whose matrix cannot load
     * here; a caller error and the batch refusal answer before either rule is asked.
     */
    @Test
    public void shouldForwardThroughTheAccessor() {
        ModelAccessException missing = refusal(() -> accessor.changeRelationshipType(SESSION,
                List.of(change("ghost", "TriggeringRelationship"))));
        assertEquals(ErrorCode.RELATIONSHIP_NOT_FOUND, missing.getErrorCode());
        accessor.getMutationDispatcher().beginBatch(SESSION, "cleanup");
        ModelAccessException batch = refusal(() -> accessor.changeRelationshipType(SESSION,
                List.of(change("rel-1", "TriggeringRelationship"))));
        assertEquals(ErrorCode.BATCH_ALREADY_ACTIVE, batch.getErrorCode());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private ChangeRelationshipTypeOperations operations(Runnable bumpVersion) {
        return new ChangeRelationshipTypeOperations(
                sessionId -> accessor.getMutationDispatcher().admit(sessionId, "change-relationship-type", model),
                accessor::getMutationDispatcher, bumpVersion, rule, conceptRule);
    }

    /**
     * A stand-in for Archi's own rule between two concepts, as it reads a Junction, with
     * {@code rule} in place of Archi's matrix: no direct relationship between the two; for a source
     * Junction, each relationship into it must allow the type from its source to the target; for a
     * target Junction, each relationship out of it must allow the type from the source to its
     * target; every relationship at a Junction must have the type; and the type must be allowed
     * between the two. Archi's exemption for a Grouping or Location is left out. Each call is
     * recorded as "source>target:type" by id and type name, then every relationship at either
     * Junction end as id:type, sorted.
     */
    static MergeElementsPlan.RelationshipRule junctionRule(ChangeElementTypeOperations.TypeRule rule,
            List<String> calls) {
        return (source, target, type) -> {
            Set<String> seen = new TreeSet<>();
            for (IArchimateConcept end : List.of(source, target)) {
                if (end instanceof IJunction) {
                    for (IArchimateRelationship each : ArchimateModelUtils.getAllRelationshipsForConcept(end)) {
                        seen.add(each.getId() + ":" + each.eClass().getName());
                    }
                }
            }
            calls.add(source.getId() + ">" + target.getId() + ":" + type.getName() + (seen.isEmpty() ? "" : " " + seen));
            if (ArchimateModelUtils.hasDirectRelationship(source, target)) {
                return false;
            }
            if (source instanceof IJunction) {
                for (IArchimateRelationship each : source.getTargetRelationships()) {
                    if (!rule.allows(each.getSource().eClass(), target.eClass(), type)) {
                        return false;
                    }
                }
                if (!sharesOneType(source, type)) {
                    return false;
                }
            }
            if (target instanceof IJunction) {
                for (IArchimateRelationship each : target.getSourceRelationships()) {
                    if (!rule.allows(source.eClass(), each.getTarget().eClass(), type)) {
                        return false;
                    }
                }
                if (!sharesOneType(target, type)) {
                    return false;
                }
            }
            return rule.allows(source.eClass(), target.eClass(), type);
        };
    }

    private static boolean sharesOneType(IArchimateConcept junction, EClass type) {
        for (IArchimateRelationship each : ArchimateModelUtils.getAllRelationshipsForConcept(junction)) {
            if (each.eClass() != type) {
                return false;
            }
        }
        return true;
    }

    /** These operations with the stand-in for Archi's rule at a Junction, recording into {@link #conceptCalls}. */
    private ChangeRelationshipTypeOperations junctionOperations() {
        return new ChangeRelationshipTypeOperations(
                sessionId -> accessor.getMutationDispatcher().admit(sessionId, "change-relationship-type", model),
                accessor::getMutationDispatcher, () -> { }, rule, junctionRule(rule, conceptCalls));
    }

    /** a and b flow into Junction J, which flows to c; three different element types tell the ends apart. */
    private void wholeJunction() {
        element(factory.createBusinessProcess(), "a", "Receive", FolderType.BUSINESS);
        element(factory.createBusinessEvent(), "b", "Arrived", FolderType.BUSINESS);
        element(factory.createBusinessFunction(), "c", "Settle", FolderType.BUSINESS);
        element(factory.createJunction(), "J", null, FolderType.OTHER);
        relationship(factory.createFlowRelationship(), "r-a", find("a"), find("J"));
        relationship(factory.createFlowRelationship(), "r-b", find("b"), find("J"));
        relationship(factory.createFlowRelationship(), "r-out", find("J"), find("c"));
    }

    /** a flows into Junction J1, which flows into Junction J2, which flows to c. */
    private void twoJunctions() {
        element(factory.createBusinessProcess(), "a", "Receive", FolderType.BUSINESS);
        element(factory.createBusinessFunction(), "c", "Settle", FolderType.BUSINESS);
        element(factory.createJunction(), "J1", null, FolderType.OTHER);
        element(factory.createJunction(), "J2", null, FolderType.OTHER);
        relationship(factory.createFlowRelationship(), "r-1", find("a"), find("J1"));
        relationship(factory.createFlowRelationship(), "r-mid", find("J1"), find("J2"));
        relationship(factory.createFlowRelationship(), "r-2", find("J2"), find("c"));
    }

    /** Every concept's source and target relationship lists, as copies holding the same objects. */
    private Map<IArchimateConcept, List<List<IArchimateRelationship>>> endListsByIdentity() {
        Map<IArchimateConcept, List<List<IArchimateRelationship>>> out = new IdentityHashMap<>();
        for (var it = model.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IArchimateConcept concept) {
                out.put(concept, List.of(new ArrayList<>(concept.getSourceRelationships()),
                        new ArrayList<>(concept.getTargetRelationships())));
            }
        }
        return out;
    }

    private void approvalOn() {
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);
    }

    private ModelAccessException assertRefused(ErrorCode code, String messagePart,
            RelationshipTypeChangeRequest... changes) {
        return assertRefusedList(code, messagePart, Arrays.asList(changes));
    }

    /** Refused with this code and cause, the model unchanged and no proposal stored, under approval. */
    private ModelAccessException assertRefusedList(ErrorCode code, String messagePart,
            List<RelationshipTypeChangeRequest> changes) {
        approvalOn();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        ModelAccessException e = refusal(() -> ops.changeRelationshipType(SESSION, changes));
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

    private static ModelAccessException refusal(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        fail("expected a refusal");
        return null;
    }

    private static RelationshipTypeChangeRequest change(String id, String type) {
        return new RelationshipTypeChangeRequest(id, type);
    }

    private void element(IArchimateElement element, String id, String name, FolderType folder) {
        element.setId(id);
        element.setName(name);
        model.getFolder(folder).getElements().add(element);
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

    private IArchimateRelationship relationship(String id) {
        return (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, id);
    }

    private IArchimateConcept find(String id) {
        return (IArchimateConcept) ArchimateModelUtils.getObjectByID(model, id);
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
