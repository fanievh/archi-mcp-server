package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IApplicationComponent;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.ICommunicationNetwork;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.INode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.ViewHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ApplyGroupSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplySpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.NestedContainerDto;
import net.vheerden.archi.mcp.response.dto.SkippedContainerDto;

/**
 * A top-level ArchiMate {@code Grouping} element must be arranged exactly as a native view group is.
 *
 * <p>An ArchiMate {@code Grouping} placed by {@code add-to-view} is an
 * {@link IDiagramModelArchimateObject}; a group created by {@code add-group-to-view} is an
 * {@link IDiagramModelGroup}. The group-arrangement family collected only the latter, so a view
 * whose top level is built from {@code Grouping} elements — the arrangement the technology and
 * deployment guidance prescribes — was left unarranged.
 *
 * <p>The failure modes differ by view shape, and the difference matters. On a <em>mixed</em> view
 * {@code arrangeGroups} positioned the native groups, skipped every {@code Grouping}, and reported
 * success — the silent case the defect was first measured as. On a view holding <em>only</em>
 * {@code Grouping} elements it instead threw "No top-level groups found", and
 * {@code optimizeGroupOrder} threw "View has no groups". Both are confidently false statements
 * about a view full of populated containers, which an agent that cannot see the canvas has no way
 * to contradict.
 *
 * <p>{@code autoLayoutAndRoute} and {@code adjustViewSpacing} rejected the same view for the same
 * reason; their coverage lives in {@code TopLevelGroupingSiblingLayoutToolsTest}, which needs a
 * display and a live command stack that these tests do not.
 *
 * <p>{@link #shouldLeaveNativeGroupGeometryUnchanged_whenTheViewHoldsOnlyNativeGroups()} is the
 * negative control: it drives the real arrangement path on a native-only view, so it fails if the
 * widened collection changes native behaviour rather than merely adding to it.
 */
public class TopLevelGroupingArrangementTest {

    private static final String SESSION = "top-level-grouping-session";
    private static final String VIEW_ID = "view-tlg";

    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private IArchimateModel model;
    /** Every leaf command the test dispatcher executed, in the order the compound held them. */
    private final List<Command> dispatchedLeafCommands = new ArrayList<>();

    /**
     * Starts recording from now. A double claim is only visible as a command count — the second
     * write overwrites the first, so the geometry alone cannot say two were built.
     */
    private List<Command> captureDispatchedCommands() {
        dispatchedLeafCommands.clear();
        return dispatchedLeafCommands;
    }

    @Before
    public void setUp() {
        stubModelManager = new StubEditorModelManager();
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ==================== arrange-groups ====================

    @Test
    public void shouldPositionATopLevelGroupingElement_whenArrangingAColumn() {
        useModel(mixedFixture());
        IDiagramModelObject zoneA = childNamed("Zone A");
        IDiagramModelObject zoneB = childNamed("Zone B");

        accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null);

        assertEquals("a Grouping element must be arranged onto the column like any other container",
                20, zoneA.getBounds().getX());
        assertEquals(20, zoneB.getBounds().getX());
        assertNotEquals("Zone A and Zone B must not be left stacked on the same y",
                zoneA.getBounds().getY(), zoneB.getBounds().getY());
    }

    @Test
    public void shouldCountGroupingElements_inGroupsPositioned() {
        useModel(mixedFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertEquals("all three top-level containers were positioned, not just the native group",
                3, result.groupsPositioned());
    }

    @Test
    public void shouldArrangeAGroupingOnlyView_whenNoNativeGroupIsPresent() {
        useModel(groupingOnlyFixture());
        IDiagramModelObject zoneA = childNamed("Zone A");
        IDiagramModelObject zoneB = childNamed("Zone B");

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertEquals(2, result.groupsPositioned());
        assertNotEquals(zoneA.getBounds().getY(), zoneB.getBounds().getY());
    }

    // ==================== the discovery step must agree with the action step ====================

    /**
     * The count {@code get-view-contents format=tree} presents must equal the number
     * {@code arrange-groups} then acts on, measured on one fixture in one run.
     *
     * <p>These two tools answer the same question — "how many top-level containers does this view
     * have?" — from opposite sides of the architecture boundary. {@code arrange-groups} asks the
     * model layer's {@code instanceof} predicate; the tree stats ask a type name on a DTO, because
     * handlers may not import EMF. Two independent definitions of one fact will drift apart unless
     * something measures them against each other, and drift here is invisible to an agent: it reads
     * the discovery number, calls the action tool, and gets a different one back with nothing to
     * say which is right.
     *
     * <p>Both directions are asserted deliberately. Widening the discovery step past the arrangement
     * family would promise containers that are never positioned; narrowing it back to native groups
     * is the original defect. The tree number is also checked against the marked nodes in the same
     * response, so the payload cannot contradict itself even while agreeing with the other tool.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void shouldReportTheSameContainerCountAsArrangeGroups_onOneMixedView() throws Exception {
        useModel(mixedFixture());

        CommandRegistry registry = new CommandRegistry();
        ViewHandler handler =
                new ViewHandler(accessor, new ResponseFormatter(), registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("get-view-contents"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("get-view-contents not registered"));
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", VIEW_ID);
        args.put("format", "tree");
        McpSchema.CallToolResult treeResult = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest("get-view-contents", args));
        assertFalse("the discovery call must succeed on this fixture", treeResult.isError());

        Map<String, Object> envelope = new ObjectMapper().readValue(
                ((McpSchema.TextContent) treeResult.content().get(0)).text(),
                new TypeReference<Map<String, Object>>() {});
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");
        Map<String, Object> stats = (Map<String, Object>) resultMap.get("stats");
        List<Map<String, Object>> tree = (List<Map<String, Object>>) resultMap.get("tree");
        int discovered = (Integer) stats.get("topLevelGroups");

        // Only now run the action tool, so the discovery number is the one an agent would have
        // read before deciding to call it.
        int arranged = accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null)
                .entity().groupsPositioned();

        assertEquals("the discovery step and the action step must count the same containers",
                arranged, discovered);

        long markedAtRoot = tree.stream()
                .filter(n -> Boolean.TRUE.equals(n.get("isGroup")))
                .count();
        assertEquals("the tree's own marked nodes must match the stat beside them",
                (long) discovered, markedAtRoot);
    }

    // ==================== the sibling tool that rejected the view outright ====================

    /**
     * {@code optimize-group-order} rejected a view of populated {@code Grouping} elements with
     * "View has no groups". The connected fixture carries an inter-group connection so the run
     * reaches the optimiser rather than stopping at the separate — and legitimate —
     * no-inter-group-connections guard, which would mask whether the collection was widened.
     */
    @Test
    public void shouldNotClaimAGroupingOnlyViewHasNoGroups_whenOptimisingGroupOrder() {
        useModel(connectedGroupingOnlyFixture());

        var result = accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "column", 40, null, null, null, false, null, null);

        assertNotNull("optimize-group-order must see the Grouping elements as groups", result);
        assertNotNull(result.entity());
    }

    // ==================== groupIds ====================

    /**
     * A {@code Grouping} element's view-object id must be accepted by {@code groupIds}. Before the
     * widening the id matched nothing in the collected list and fell through to "Group not found in
     * view" — a denial of an id that was valid, top-level, and staring the tool in the face.
     */
    @Test
    public void shouldAcceptAGroupingElementId_whenGroupIdsNamesOne() {
        useModel(mixedFixture());
        IDiagramModelObject zoneA = childNamed("Zone A");

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                List.of(zoneA.getId()), null).entity();

        assertEquals("only the named Grouping is arranged", 1, result.groupsPositioned());
        assertEquals(zoneA.getId(), result.positionedContainers().get(0).viewObjectId());
    }

    /**
     * A nested {@code Grouping} named in {@code groupIds} must get the accurate "not a top-level
     * group" message, not the generic not-found. The branch asks the same predicate the collection
     * uses, so the two answers cannot disagree about what a container is.
     */
    @Test
    public void shouldSayNotTopLevel_whenGroupIdsNamesANestedGrouping() {
        useModel(nestedGroupingFixture());
        // By id, not by index: the zone's first child is its host node, and picking index 0 would
        // silently test the not-a-container path instead of the nested-container one.
        String innerId = "obj-grp-inner";

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, List.of(innerId), null);
            fail("a nested Grouping is not a top-level target and must be rejected as such");
        } catch (ModelAccessException e) {
            assertTrue("the message must say it is not top-level, not that it was never found. "
                    + "Actual: " + e.getMessage(),
                    e.getMessage().contains("not a top-level group"));
        }
    }

    // ==================== the outermost-container collection ====================

    /**
     * On every view whose targets are already the view's own children, the outermost walk and the
     * direct-children walk must return the SAME list — element for element, in the same order.
     *
     * <p>This is the compatibility argument the migration rests on, and it is asserted as one
     * equality over ids rather than as two size checks: two lists of the same length holding
     * different containers would satisfy a pair of size assertions and change every arrangement
     * on the corpus.</p>
     */
    @Test
    public void shouldReturnExactlyTheDirectChildrenWalk_whenNoTargetIsNestedUnderANonTarget()
            throws Exception {
        // Enumerated by reflection rather than by hand. A hand-written list is a denominator that
        // silently stops growing: a fixture added later is simply absent from it, and the equality
        // then certifies whatever subset someone last remembered to name. Every no-argument
        // fixture method in this class is driven, and the four shapes that MUST diverge are named
        // as exclusions with their reason — so adding a fixture either exercises the equality or
        // forces a deliberate entry here.
        java.util.Set<String> deliberatelyDivergent = java.util.Set.of(
                "hostedZonesFixture", "hostedZonesBoxToBoxFixture",
                "hostedZonesAndALoosePeerFixture", "hostInsideHostFixture",
                "mixedCanvasAndHostedFixture", "twoCanvasZonesAndThreeHostedFixture",
                "oneHostedZoneFixture", "hostedZonesAlreadySpacedFixture",
                "emptyCanvasBoxBesideHostedZonesFixture", "twoFrameElementSpacingFixture");
        int driven = 0;
        for (java.lang.reflect.Method method : getClass().getDeclaredMethods()) {
            if (method.getParameterCount() != 0
                    || !IArchimateModel.class.equals(method.getReturnType())
                    || !method.getName().endsWith("Fixture")) {
                continue;
            }
            if (deliberatelyDivergent.contains(method.getName())) {
                continue;
            }
            method.setAccessible(true);
            IArchimateDiagramModel v = firstViewOf((IArchimateModel) method.invoke(this));
            assertEquals("outermost and direct-children walks must agree on " + method.getName(),
                    idsOf(TopLevelGroupTargets.collect(v)),
                    idsOf(TopLevelGroupTargets.collectOutermost(v)));
            driven++;
        }
        assertTrue("the reflective sweep found only " + driven + " fixtures, so it is not reading "
                + "this class properly and the equality is asserting almost nothing",
                driven >= 30);

        // The exclusions are not an escape hatch: each must actually diverge, or it belongs above.
        for (String name : deliberatelyDivergent) {
            java.lang.reflect.Method method = getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            IArchimateDiagramModel v = firstViewOf((IArchimateModel) method.invoke(this));
            assertNotEquals(name + " is excluded from the equality but does not diverge, so the "
                    + "exclusion is hiding nothing and should be removed",
                    idsOf(TopLevelGroupTargets.collect(v)),
                    idsOf(TopLevelGroupTargets.collectOutermost(v)));
        }
    }

    /**
     * The one shape they must NOT agree on: two zones drawn inside a host the predicate declines.
     * A direct-children walk sees no container at all; the outermost walk sees both zones.
     */
    @Test
    public void shouldFindTheNestedZones_whenTheOnlyViewChildIsANonTargetHost() {
        useModel(hostedZonesFixture());
        IArchimateDiagramModel v = view();

        assertEquals("the direct-children walk sees only the host, which is not a target",
                List.of(), idsOf(TopLevelGroupTargets.collect(v)));
        assertEquals("the outermost walk descends the host and finds both zones",
                List.of("obj-grp-a", "obj-grp-b"),
                idsOf(TopLevelGroupTargets.collectOutermost(v)));
    }

    /**
     * A target inside a TARGET is a member of it and is never collected — the outermost walk stops
     * descending the moment it admits a container. Without this the widening would flatten every
     * nested zone onto its parent's arrangement.
     */
    @Test
    public void shouldNotDescendIntoATargetItAlreadyAdmitted() {
        assertEquals("a Grouping inside a Grouping is a member, not a second target",
                List.of("obj-grp-a"),
                idsOf(TopLevelGroupTargets.collectOutermost(firstViewOf(nestedGroupingFixture()))));
        assertEquals("a Grouping inside a NATIVE group is equally a member of it",
                List.of("obj-nat-outer"),
                idsOf(TopLevelGroupTargets.collectOutermost(
                        firstViewOf(zoneInsideNativeGroupFixture()))));
    }

    /**
     * The single-sourcing property, asserted over every object on the view rather than over two
     * hand-picked ones: the container an object resolves UP to is always one the walk finds coming
     * DOWN. Two walks that can answer differently are what the two definitions were.
     */
    @Test
    public void shouldResolveEveryObjectToAContainerTheOutermostWalkFound() {
        for (IArchimateModel m : List.of(hostedZonesFixture(), nestedGroupingFixture(),
                zoneInsideNativeGroupFixture(), mixedFixture(), fallThroughFixture())) {
            IArchimateDiagramModel v = firstViewOf(m);
            List<String> collected = idsOf(TopLevelGroupTargets.collectOutermost(v));
            List<IDiagramModelObject> everyObject = new ArrayList<>();
            collectEveryObject(v, everyObject);
            assertFalse("the fixture must actually hold objects to quantify over",
                    everyObject.isEmpty());
            for (IDiagramModelObject obj : everyObject) {
                IDiagramModelObject resolved = TopLevelGroupTargets.topLevelGroupOf(obj);
                if (resolved != null) {
                    assertTrue(obj.getId() + " resolves to " + resolved.getId()
                            + ", which the outermost walk did not find: " + collected,
                            collected.contains(resolved.getId()));
                }
            }
        }
    }

    /** Every view object drawn on the view, at any depth, containers included. */
    private void collectEveryObject(com.archimatetool.model.IDiagramModelContainer container,
            List<IDiagramModelObject> out) {
        for (IDiagramModelObject child : container.getChildren()) {
            out.add(child);
            if (child instanceof com.archimatetool.model.IDiagramModelContainer nested) {
                collectEveryObject(nested, out);
            }
        }
    }

    private List<String> idsOf(List<IDiagramModelObject> objects) {
        List<String> ids = new ArrayList<>();
        for (IDiagramModelObject obj : objects) {
            ids.add(obj.getId());
        }
        return ids;
    }

    // ============ one response may not contradict itself about its own containers ============

    /**
     * A response may not report connections crossing a boundary the same response says does not
     * exist. Asserted across the two fields together rather than on either alone: each field was
     * individually defensible — one counted the outermost containers, the other the view's own
     * children — and a per-field pin is blind to a pair that cannot both be true.
     */
    @Test
    public void shouldNotCountInterGroupConnections_whileSayingTheViewHasFewerThanTwoGroups() {
        useModel(hostedZonesFixture());

        ApplyGroupSpacingRecommendationsResultDto result = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, /*dryRun=*/ true, null)
                .entity();

        assertFalse("a run reporting " + result.interGroupConnectionCount()
                + " inter-group connections may not also refuse for want of two containers. "
                + "noChangeReason: " + result.noChangeReason(),
                result.interGroupConnectionCount() > 0
                        && saysFewerThanTwoGroups(result.noChangeReason()));
        assertFalse("isConnected=true may not sit beside a fewer-than-two refusal",
                result.isConnected() && saysFewerThanTwoGroups(result.noChangeReason()));
    }

    /**
     * The same invariant on the sibling tool, on BOTH arms. The element arm short-circuits with
     * "view has no groups" and the group arm with the fewer-than-two sentence; each was reached
     * from a direct-children walk while the count beside it came from the outermost one.
     */
    @Test
    public void shouldNotSayTheViewHasNoGroups_whileCountingConnectionsBetweenThem() {
        useModel(hostedZonesFixture());

        ApplySpacingRecommendationsResultDto result = accessor
                .applySpacingRecommendations(SESSION, VIEW_ID, "both", /*dryRun=*/ true, null, null)
                .entity();

        String reason = result.noChangeReason() == null ? "" : result.noChangeReason();
        assertFalse("the element arm may not call the view flat while the group count is "
                + result.interGroupConnectionCount() + ". noChangeReason: " + reason,
                result.isConnected() && reason.contains("view has no groups"));
        assertFalse("the group arm may not refuse for want of two containers while counting "
                + result.interGroupConnectionCount() + " connections between them. "
                + "noChangeReason: " + reason,
                result.interGroupConnectionCount() > 0 && saysFewerThanTwoGroups(reason));
    }

    /**
     * The spacing the response publishes must have been measured on this view rather than reached
     * through the fewer-than-two short circuit, which returns a constant. The zones sit a known
     * 30px apart, so the constant and the measurement are distinguishable — a fixture whose real
     * gap happened to equal the default could not tell the two apart at all.
     */
    @Test
    public void shouldMeasureTheSpacingBetweenNestedZones_ratherThanReturnTheDefault() {
        useModel(hostedZonesFixture());

        ApplyGroupSpacingRecommendationsResultDto result = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, /*dryRun=*/ true, null)
                .entity();

        assertEquals("the zones are 30px apart and the response must say so",
                30, result.currentSpacingPx());
    }

    // ====== a tool must decline the shape its own positioning step cannot act on ======

    /** The code every tool publishes for this refusal, asserted as the literal it goes out as. */
    private static final String NOT_POSITIONED_CODE =
            "structural_no_change_containers_not_positioned_by_this_tool";

    /**
     * The gate and the positioning step must agree about which containers this tool can act on.
     *
     * <p>On a view whose zones all sit inside a host the gate passed and the step's own guard did
     * not, so the tool published a 50px inter-group recommendation it could never apply and — on
     * the applied path — attributed the failure to canvas density. It declines instead, and names
     * the tool that does position these containers.</p>
     */
    @Test
    public void shouldDeclineTheShape_whenEveryContainerIsDrawnInsideAHost() {
        useModel(hostedZonesFixture());

        ApplyGroupSpacingRecommendationsResultDto result = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, /*dryRun=*/ true, null)
                .entity();

        assertEquals("a delta this tool will never apply on this shape must not be published",
                0, result.interGroupDelta());
        assertNotNull("the refusal must be stated, not left null under a dry-run label",
                result.noChangeReason());
        assertTrue("the refusal must say the containers are drawn inside a host. Actual: "
                + result.noChangeReason(),
                result.noChangeReason().contains("drawn inside a host"));
        assertTrue("the refusal must name the tool that does position them. Actual: "
                + result.noChangeReason(),
                result.noChangeReason().contains("arrange-groups"));
        assertEquals("the structural code outranks the dry-run label: a dry run promising what "
                + "the applied path cannot deliver is the same defect one step earlier",
                NOT_POSITIONED_CODE, result.terminationReason());
    }

    /**
     * The same refusal on the composer, on each scope's own arm. Under {@code both} the composed
     * reason names both arms, because that is where the per-arm detail lives.
     */
    @Test
    public void shouldDeclineTheShapeOnEveryScope_whenEveryContainerIsDrawnInsideAHost() {
        for (String scope : List.of("both", "element", "group")) {
            useModel(hostedZonesFixture());

            ApplySpacingRecommendationsResultDto result = accessor
                    .applySpacingRecommendations(SESSION, VIEW_ID, scope, /*dryRun=*/ true,
                            null, null)
                    .entity();

            String reason = result.noChangeReason();
            assertNotNull(scope + ": the refusal must be stated", reason);
            assertEquals(scope + ": no element delta is applicable on this shape",
                    0, result.interElementDelta());
            assertEquals(scope + ": no group delta is applicable on this shape",
                    0, result.interGroupDelta());
            assertTrue(scope + ": the refusal must say the containers are drawn inside a host. "
                    + "Actual: " + reason, reason.contains("drawn inside a host"));
            if (!"group".equals(scope)) {
                assertTrue(scope + ": the element arm must carry it. Actual: " + reason,
                        reason.contains("element: "));
            }
            if (!"element".equals(scope)) {
                assertTrue(scope + ": the group arm must carry it. Actual: " + reason,
                        reason.contains("group: "));
            }
            assertEquals(scope + ": both arms report the structural code",
                    NOT_POSITIONED_CODE, result.elementTerminationReason());
            assertEquals(scope + ": both arms report the structural code",
                    NOT_POSITIONED_CODE, result.groupTerminationReason());
        }
    }

    /**
     * The element tool stops calling a view holding two populated zones flat.
     *
     * <p>Its own gate reads the view's direct children and was right to; the sentence it reached
     * through them was not. Both halves of the old wording are asserted absent, because either
     * alone is a confident false statement about this canvas.</p>
     */
    @Test
    public void shouldNotCallTheViewFlat_whenItsZonesAreDrawnInsideAHost() {
        useModel(hostedZonesFixture());

        net.vheerden.archi.mcp.response.dto.ApplyElementSpacingRecommendationsResultDto result =
                accessor.applyElementSpacingRecommendations(SESSION, VIEW_ID, /*dryRun=*/ true,
                        null).entity();

        String reason = result.noChangeReason();
        assertNotNull("the refusal must be stated", reason);
        assertFalse("a view holding two populated zones has groups. Actual: " + reason,
                reason.contains("view has no groups"));
        assertFalse("and it is not flat. Actual: " + reason, reason.contains("flat view"));
        assertTrue("it must say what is actually true of the view. Actual: " + reason,
                reason.contains("drawn inside a host"));
        assertEquals(NOT_POSITIONED_CODE, result.terminationReason());
    }

    // ====== the predicate is pinned to its source, not to its answer ======

    /**
     * The predicate must be decided from the collection the positioning step's own guard reads.
     *
     * <p>Re-deriving it from the outermost walk returns the same boolean on a flat view and on a
     * fully nested one, so a pin over those two shapes certifies nothing. This drives the two
     * shapes where the collections DISAGREE: on the hosted view the outermost walk finds two
     * containers and the step's own walk finds none, and on the mixed view they find three and
     * one. A predicate sourced from the outermost walk answers false on both.</p>
     */
    @Test
    public void shouldDecideTheRefusalFromTheStepsOwnContainerWalk_notTheOutermostOne() {
        for (IArchimateModel fixture : List.of(
                hostedZonesFixture(), mixedCanvasAndHostedFixture())) {
            IArchimateDiagramModel v = firstViewOf(fixture);

            assertTrue("the fixture must be one the two walks disagree on, or this proves nothing",
                    TopLevelGroupTargets.collectOutermost(v).size()
                            > TopLevelGroupTargets.collectPopulated(v).size());
            assertTrue("the outermost walk finds a corridor here, so a predicate sourced from it "
                    + "would answer false and the refusal would never fire",
                    TopLevelGroupTargets.collectOutermost(v).size() >= 2);

            assertTrue("the step that widens a corridor needs two of the view's OWN containers "
                    + "and has " + TopLevelGroupTargets.collectPopulated(v).size(),
                    TopLevelGroupTargets.positioningStepIsShortOfOwnContainers(v, 2));
        }
    }

    /**
     * The negative control the second half of the predicate exists for: a view whose only
     * container is an empty box on the canvas has an empty populated walk too, so a predicate
     * written as that walk alone would tell this caller its canvas box is drawn inside a host.
     */
    @Test
    public void shouldNotClaimAContainerIsHosted_whenTheViewsOnlyBoxSitsOnTheCanvas() {
        for (IArchimateModel fixture : List.of(
                emptyTopLevelContainerOnlyFixture(), nativeOnlyFixture(), groupingOnlyFixture())) {
            IArchimateDiagramModel v = firstViewOf(fixture);
            assertFalse("nothing on this view is drawn inside a host, whatever the populated "
                    + "walk returns",
                    TopLevelGroupTargets.positioningStepIsShortOfOwnContainers(v, 2));
            assertFalse(TopLevelGroupTargets.positioningStepIsShortOfOwnContainers(v, 1));
        }
    }

    /** The same control end to end: the published wording must not change on those views. */
    @Test
    public void shouldKeepTheExistingWording_onAViewWithNothingDrawnInsideAHost() {
        useModel(emptyTopLevelContainerOnlyFixture());
        ApplyGroupSpacingRecommendationsResultDto group = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, true, null).entity();
        assertTrue("an empty canvas box is a fewer-than-two refusal, not a hosted one. Actual: "
                + group.noChangeReason(),
                saysFewerThanTwoGroups(group.noChangeReason()));
        assertFalse("and it must not be told its box is drawn inside a host",
                group.noChangeReason().contains("drawn inside a host"));

        useModel(emptyTopLevelContainerOnlyFixture());
        net.vheerden.archi.mcp.response.dto.ApplyElementSpacingRecommendationsResultDto element =
                accessor.applyElementSpacingRecommendations(SESSION, VIEW_ID, true, null).entity();
        assertTrue("a view whose only box is empty keeps the no-groups wording. Actual: "
                + element.noChangeReason(),
                element.noChangeReason().contains("view has no groups"));
    }

    // ====== precedence, in both directions ======

    /**
     * One nested zone is a fewer-than-two refusal, not this one. Both sentences are true there
     * and that one is the more actionable: no corridor exists at all, whoever positions it.
     */
    @Test
    public void shouldPreferTheFewerThanTwoRefusal_whenOnlyOneZoneIsHosted() {
        useModel(oneHostedZoneFixture());

        ApplyGroupSpacingRecommendationsResultDto result = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, true, null).entity();

        assertTrue("a single container has no corridor for anyone. Actual: "
                + result.noChangeReason(), saysFewerThanTwoGroups(result.noChangeReason()));
        assertNotEquals(NOT_POSITIONED_CODE, result.terminationReason());
    }

    /**
     * A structural impossibility outranks a met heuristic. A view whose hosted zones happen to sit
     * at the target would otherwise be told its spacing already meets it — which implies a corridor
     * was measured that this tool could have widened, and none was.
     */
    @Test
    public void shouldPreferTheStructuralRefusal_overSpacingThatAlreadyMeetsTheTarget() {
        useModel(hostedZonesAlreadySpacedFixture());

        ApplyGroupSpacingRecommendationsResultDto result = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, true, null).entity();

        assertFalse("the already-met sentence claims a measurement this tool could have acted on. "
                + "Actual: " + result.noChangeReason(),
                result.noChangeReason().contains("already meets"));
        assertEquals(NOT_POSITIONED_CODE, result.terminationReason());
    }

    /**
     * On {@code both}, one arm's already-met reason and the other's structural one are composed
     * into a single string. The structural half must decide the code — the composed sentence
     * contains "already meets", which the mapper's existing test would otherwise match.
     */
    @Test
    public void shouldMapAComposedReasonToTheStructuralCode_whenOneArmIsStructural() {
        String composed = "element: current spacing 40px already meets or exceeds heuristic "
                + "target 40px; group: " + TopLevelGroupTargets.containersNotPositionedBy(
                        VIEW_ID, firstViewOf(hostedZonesFixture()), 2,
                        "This arm inflates corridors between the view's own containers");

        assertTrue("the fixture string must carry BOTH halves or it tests one branch",
                composed.contains("already meets"));
        assertEquals("a structural impossibility outranks a met heuristic wherever the two are "
                + "composed", NOT_POSITIONED_CODE,
                SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(
                        /*dryRun=*/ false, composed));
        assertEquals("and the dry-run label does not outrank it either",
                NOT_POSITIONED_CODE,
                SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(
                        /*dryRun=*/ true, composed));
    }

    // ====== the gate measures the frame its own positioning step acts in ======

    /**
     * A view offering two frames must be measured in the one this tool's step will act in, not in
     * whichever holds the most containers.
     *
     * <p>Two canvas zones 60px apart and three hosted zones 30px apart: the larger frame is the
     * host's, and a gate reading it publishes 30 as the corridor while the step widens the one
     * that is 60. Both numbers are real and they describe different corridors.</p>
     */
    @Test
    public void shouldMeasureTheFrameItsOwnStepActsIn_whenALargerFrameExistsElsewhere() {
        IArchimateDiagramModel v = firstViewOf(twoCanvasZonesAndThreeHostedFixture());

        assertEquals("the host's frame must be the larger one, or the fixture proves nothing",
                List.of("obj-grp-a", "obj-grp-b", "obj-grp-c"),
                idsOf(TopLevelGroupTargets.largestSharedFrame(
                        v, TopLevelGroupTargets.collectOutermost(v))));
        assertEquals("the step acts on the view's own containers, so the gate measures theirs",
                List.of("obj-grp-c1", "obj-grp-c2"),
                idsOf(TopLevelGroupTargets.positioningFrame(
                        v, TopLevelGroupTargets.collectOutermost(v),
                        TopLevelGroupTargets.CONTAINERS_A_GROUP_STEP_NEEDS)));

        useModel(twoCanvasZonesAndThreeHostedFixture());
        ApplyGroupSpacingRecommendationsResultDto result = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, true, null).entity();
        assertEquals("the canvas corridor is 60px and that is the one this tool widens",
                60, result.currentSpacingPx());
    }

    /**
     * Below two of its own containers the step has no corridor here, so the gate keeps the
     * measurement it actually took rather than narrowing to a frame too small to measure in —
     * which would publish a placeholder in a field that reads as a measured pixel count.
     */
    @Test
    public void shouldKeepTheMeasurementItTook_whenItsOwnFrameIsTooSmallToMeasureIn() {
        IArchimateDiagramModel v = firstViewOf(mixedCanvasAndHostedFixture());

        assertEquals("one own container is too few to widen between",
                1, TopLevelGroupTargets.collectPopulated(v).size());
        assertEquals("so the frame is left as the largest, and the refusal explains it",
                List.of("obj-grp-a", "obj-grp-b"),
                idsOf(TopLevelGroupTargets.positioningFrame(
                        v, TopLevelGroupTargets.collectOutermost(v),
                        TopLevelGroupTargets.CONTAINERS_A_GROUP_STEP_NEEDS)));
    }

    /**
     * The mixed shape end to end: one populated container on the canvas and two zones inside a
     * host. The step's own guard PASSES here — it tests for an empty walk, not for a corridor —
     * so nothing throws, nothing short-circuits, and the tool used to publish a 50px delta
     * derived entirely from the host's frame.
     */
    @Test
    public void shouldDeclineTheMixedShape_whenTheCorridorLiesInAFrameItCannotActIn() {
        useModel(mixedCanvasAndHostedFixture());

        ApplyGroupSpacingRecommendationsResultDto result = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, true, null).entity();

        assertEquals("the 50px delta was computed from a gap inside the host",
                0, result.interGroupDelta());
        assertNotNull(result.noChangeReason());
        assertTrue("the refusal must not claim EVERY container is hosted — one is on the canvas. "
                + "Actual: " + result.noChangeReason(),
                result.noChangeReason().contains("1 drawn on the view itself"));
        assertFalse("and it must not say every one is hosted. Actual: " + result.noChangeReason(),
                result.noChangeReason().contains("every one is drawn inside a host"));
        assertEquals(NOT_POSITIONED_CODE, result.terminationReason());
    }

    /**
     * The remedy the refusal names must be one the caller can actually run.
     *
     * <p>A published remedy that returns nothing is the defect this line of work exists to remove,
     * one level up: the sentence would be accurate about what this tool cannot do and wrong about
     * what the other one can. A host is never grown to fit, so an arrangement that would overflow
     * its host is declined for that host — which is why this is measured rather than assumed.</p>
     */
    @Test
    public void shouldLeaveTheNamedRemedyExecutable_onTheShapeTheRefusalIsPublishedFor() {
        useModel(hostedZonesFixture());
        IArchimateDiagramModel view = view();
        int before = gapBetween(view, "obj-grp-a", "obj-grp-b");
        assertEquals("the fixture's zones start 30px apart", 30, before);

        ArrangeGroupsResultDto result = accessor
                .arrangeGroups(SESSION, VIEW_ID, "column", null, 80, null, null).entity();

        assertTrue("the remedy must arrange the containers the refusal points it at, not skip "
                + "them. Skipped: " + result.skippedContainers(),
                result.nestedContainersArranged().size() >= 1);
        // The host itself is reported as left where it is, which is correct and is not a
        // decline: its own entry says the containers inside it WERE arranged. A decline for want
        // of room would leave the gap below unchanged, which is what the measurement catches.
        assertFalse("the host must not have been declined for want of room. Skipped: "
                + result.skippedContainers(),
                result.skippedContainers().toString().contains("would not fit"));
        assertEquals("and the gap it was asked for must be the gap it produced",
                80, gapBetween(view, "obj-grp-a", "obj-grp-b"));
    }

    /** The vertical gap between two named view objects, in their shared parent's frame. */
    private int gapBetween(IArchimateDiagramModel view, String topId, String bottomId) {
        com.archimatetool.model.IBounds top = boundsOf(view, topId);
        com.archimatetool.model.IBounds bottom = boundsOf(view, bottomId);
        return bottom.getY() - (top.getY() + top.getHeight());
    }

    private com.archimatetool.model.IBounds boundsOf(IArchimateDiagramModel view, String id) {
        List<IDiagramModelArchimateObject> all = new ArrayList<>();
        TopLevelGroupTargets.collectElementViewObjects(view, all);
        for (IDiagramModelArchimateObject obj : all) {
            if (id.equals(obj.getId())) {
                return obj.getBounds();
            }
        }
        throw new IllegalStateException("no view object " + id);
    }

    // ====== review: the frame must follow the STEP's need, not a fixed number ======

    /**
     * A view with one populated container on the canvas and a bigger frame inside a host: the
     * element arm has its one container and does NOT decline, so the spacing it publishes must be
     * the one inside that container, not the one inside the host's zones.
     *
     * <p>The decline question and the frame question share a threshold — how many of the view's own
     * containers this step needs — and a frame helper that hardcodes one of them answers the other
     * arm's question. The element arm needs ONE container to space inside; the group arm needs TWO
     * to widen between. Asserted through the published spacing rather than over the helper, so it
     * is the arm's own measurement that is pinned and not an arity.</p>
     *
     * <p>The fixture is built so the two frames cannot be confused: 40px between the canvas
     * container's children, 15px between each hosted zone's. A frame error is therefore visible as
     * a number.</p>
     */
    @Test
    public void shouldMeasureTheElementArmsOwnFrame_whenOneOwnContainerIsEnoughForThatStep() {
        IArchimateDiagramModel v = firstViewOf(twoFrameElementSpacingFixture());
        assertEquals("the element step needs one own container and this view has one",
                1, TopLevelGroupTargets.collectPopulated(v).size());
        assertFalse("so that arm does not decline",
                TopLevelGroupTargets.positioningStepIsShortOfOwnContainers(v, 1));

        useModel(twoFrameElementSpacingFixture());
        ApplySpacingRecommendationsResultDto result = accessor
                .applySpacingRecommendations(SESSION, VIEW_ID, "element", true, null, null)
                .entity();

        assertEquals("the element arm acts inside the view's own container, so 40px is the gap it "
                + "measured; 15px is the gap inside the host, which it cannot act on",
                40, result.currentElementSpacingPx());

        // The measurement alone does not pin the decision: an arm that wrongly declined would
        // still have scanned the right frame on its way there. Both halves of the threshold are
        // load-bearing, so both are asserted — and asserted on the CODE, because the refusal has
        // two sentence forms and a phrase from one of them is absent from the other.
        assertNotEquals("this arm has the one own container it needs, so it must not refuse the "
                + "view for want of containers it can act on. Reason: " + result.noChangeReason(),
                NOT_POSITIONED_CODE, result.elementTerminationReason());
    }

    // ====== review: the refusal must not deny a container that is on the canvas ======

    /**
     * An EMPTY container on the canvas beside populated zones inside a host.
     *
     * <p>The step still has nothing to do — an empty box holds no corridor and no elements — so the
     * refusal is right to fire. What it may not do is say every container is drawn inside a host,
     * because one is not, nor leave that one out of the count it publishes. The counts describe the
     * view's topology; the DECISION is still taken from the step's own collection.</p>
     */
    @Test
    public void shouldNotDenyACanvasContainer_whenItIsEmptyAndTheRestAreHosted() {
        useModel(emptyCanvasBoxBesideHostedZonesFixture());

        ApplyGroupSpacingRecommendationsResultDto result = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, true, null).entity();

        String reason = result.noChangeReason();
        assertNotNull("the step still has no corridor, so it still declines", reason);
        assertFalse("but one container IS drawn on the view itself. Actual: " + reason,
                reason.contains("every one is drawn inside a host"));
        assertTrue("and the count must include it — the view holds three. Actual: " + reason,
                reason.contains("has 3 top-level containers"));
        assertEquals(NOT_POSITIONED_CODE, result.terminationReason());
    }

    /** The all-nested fixture still gets the "every one" wording — the count change must not blur it. */
    @Test
    public void shouldStillSayEveryOne_whenNoContainerIsDrawnOnTheViewAtAll() {
        useModel(hostedZonesFixture());

        ApplyGroupSpacingRecommendationsResultDto result = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, true, null).entity();

        assertTrue("nothing is on the canvas here, so the stronger sentence is the true one. "
                + "Actual: " + result.noChangeReason(),
                result.noChangeReason().contains("every one is drawn inside a host"));
        assertTrue("and the count is the two zones. Actual: " + result.noChangeReason(),
                result.noChangeReason().contains("has 2 top-level containers"));
    }

    // ====== review: the mixed decline must say which spacing it published ======

    /** The declined mixed shape still publishes a measurement, and it must be the one it took. */
    @Test
    public void shouldPublishTheSpacingItActuallyMeasured_whenItDeclinesTheMixedShape() {
        useModel(mixedCanvasAndHostedFixture());

        ApplyGroupSpacingRecommendationsResultDto result = accessor
                .applyGroupSpacingRecommendations(SESSION, VIEW_ID, true, null).entity();

        assertEquals("the gap it measured is the one between the hosted zones, and the refusal "
                + "beside it says which frame that is",
                30, result.currentSpacingPx());
    }

    // ====== review: nothing else may collide with the mapper's marker ======

    /**
     * The mapper routes on a marker substring, checked ahead of the dry-run and already-met tests.
     * Any other refusal this family publishes that happened to contain that phrase would be
     * silently re-routed to the structural code AND would suppress the dry-run label.
     */
    @Test
    public void shouldNotCollideWithTheMarker_acrossEveryOtherReasonThisFamilyPublishes() {
        List<String> otherReasons = new ArrayList<>();
        otherReasons.add(ApplyGroupSpacingDecision.decide(
                1, 0, 30, 80, false, false, false, false, null).noChangeReason());
        otherReasons.add(ApplyGroupSpacingDecision.decide(
                1, 1, 80, 80, false, true, true, false, null).noChangeReason());
        otherReasons.add(ApplyElementSpacingDecision.decide(
                1, 30, 60, false, false, false, false, null).noChangeReason());
        otherReasons.add(ApplyElementSpacingDecision.decide(
                1, 30, 60, false, true, false, false, null).noChangeReason());
        otherReasons.add(ApplyElementSpacingDecision.decide(
                0, 30, 60, false, true, true, false, null).noChangeReason());
        otherReasons.add(ApplyElementSpacingDecision.decide(
                1, 60, 60, false, true, true, false, null).noChangeReason());
        for (String scope : List.of("both", "element", "group")) {
            otherReasons.add(ApplySpacingDecision.decide(
                    scope, 1, 0, 30, 30, 60, 80, false, false, false, false, false, false, false,
                    null, null).noChangeReason());
            otherReasons.add(ApplySpacingDecision.decide(
                    scope, 1, 1, 60, 80, 60, 80, false, true, true, true, true, false, false,
                    null, null).noChangeReason());
        }

        int nonNull = 0;
        for (String reason : otherReasons) {
            if (reason == null) {
                continue;
            }
            nonNull++;
            assertFalse("this reason collides with the marker the mapper routes on, so it would be "
                    + "published as the structural code and would suppress the dry-run label: "
                    + reason,
                    reason.contains("positions none of them"));
        }
        assertTrue("the sweep found only " + nonNull + " reasons, so it is asserting almost "
                + "nothing", nonNull >= 8);
    }

    /**
     * The composer's element arm puts the structural refusal FIRST, and the published sentence
     * depends on that order — so it is pinned in the direction that can regress.
     *
     * <p>On this fixture every zone holds exactly one child, so {@code hasGroupWithMultipleChildren}
     * is false and the arm's next branch would fire with "no groups with 2+ elements detected" — a
     * sentence about containers this arm does not act on, reached before it ever said so. The
     * standalone element tool inverts its order for the same reason; the composer must not order
     * one view's structural refusals differently from its sibling.</p>
     */
    @Test
    public void shouldPreferTheStructuralRefusal_overTheNoMultiChildSentenceOnTheComposer() {
        useModel(hostedZonesFixture());

        ApplySpacingRecommendationsResultDto result = accessor
                .applySpacingRecommendations(SESSION, VIEW_ID, "element", /*dryRun=*/ true,
                        null, null)
                .entity();

        String reason = result.noChangeReason();
        assertNotNull(reason);
        assertFalse("the arm has not established that it acts on these containers at all, so it "
                + "may not report what their children look like. Actual: " + reason,
                reason.contains("no groups with 2+ elements"));
        assertTrue("it must say whose containers these are first. Actual: " + reason,
                reason.contains("drawn inside a host"));
        assertEquals(NOT_POSITIONED_CODE, result.elementTerminationReason());
    }

    /**
     * The other direction: on a view this arm DOES act on, the no-multi-child sentence still wins.
     * Without this, the pin above is satisfied by a branch that fires unconditionally.
     */
    @Test
    public void shouldStillReportNoMultiChildElements_whenTheContainersAreTheViewsOwn() {
        useModel(groupingOnlyFixture());

        ApplySpacingRecommendationsResultDto result = accessor
                .applySpacingRecommendations(SESSION, VIEW_ID, "element", /*dryRun=*/ true,
                        null, null)
                .entity();

        String reason = result.noChangeReason();
        assertNotNull(reason);
        assertFalse("nothing on this view is drawn inside a host. Actual: " + reason,
                reason.contains("drawn inside a host"));
        assertTrue("so the arm's own structural sentence is the true one. Actual: " + reason,
                reason.contains("no groups with 2+ elements")
                        || reason.contains("view has no connections"));
    }

    /** Whether a no-change reason is the fewer-than-two-containers refusal. */
    private boolean saysFewerThanTwoGroups(String noChangeReason) {
        return noChangeReason != null && noChangeReason.contains("fewer than 2 top-level groups");
    }

    // ============ zones inside a host are arranged in the host's own space ============

    /**
     * The refusal this shape used to get was a false statement about a view holding two populated
     * zones. It must now arrange them — and arrange them INSIDE the host, in the host's coordinate
     * space, because that is the space their stored x/y are measured in.
     */
    @Test
    public void shouldArrangeZonesInsideTheirHost_ratherThanRefuseTheView() {
        useModel(hostedZonesFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        List<NestedContainerDto> nested = result.nestedContainersArranged();
        assertEquals("both zones were arranged", 2, nested.size());
        for (NestedContainerDto entry : nested) {
            assertEquals("every entry must name the host its coordinates are relative to",
                    "obj-host-cr", entry.hostViewObjectId());
        }
        assertEquals("obj-grp-a", nested.get(0).viewObjectId());
        assertEquals("obj-grp-b", nested.get(1).viewObjectId());
        assertEquals("the column arrangement starts at the host's own content origin",
                20, nested.get(0).newX());
        assertEquals(20, nested.get(0).newY());
        assertEquals(20, nested.get(1).newX());
        assertEquals("the second zone clears the first by the resolved spacing",
                20 + 150 + 40, nested.get(1).newY());
    }

    /**
     * The reported geometry must be read back from the model after the write, not the rectangle
     * the arrangement computed. Archi re-fits a container to its children on write, so the two can
     * differ and only one of them is a report.
     */
    @Test
    public void shouldReportNestedGeometryReadBackFromTheModel() {
        useModel(hostedZonesFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        IDiagramModelObject host = view().getChildren().get(0);
        for (NestedContainerDto entry : result.nestedContainersArranged()) {
            IDiagramModelObject zone = childById(host, entry.viewObjectId());
            assertNotNull("the reported zone must exist in the model", zone);
            assertEquals("x must be what the model holds", zone.getBounds().getX(), entry.newX());
            assertEquals("y must be what the model holds", zone.getBounds().getY(), entry.newY());
            assertEquals(zone.getBounds().getWidth(), entry.newWidth());
            assertEquals(zone.getBounds().getHeight(), entry.newHeight());
        }
    }

    /**
     * The four buckets still partition the view's DIRECT children, and no nested id leaks into
     * {@code positionedContainers}. The identity is published on several surfaces and asserted over
     * ids; a nested container counted among the arranged ones would make it arithmetically false
     * on exactly the views the new field exists for.
     */
    @Test
    public void shouldKeepTheFourBucketIdentity_whenZonesWereArrangedInsideAHost() {
        useModel(hostedZonesFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertEquals("the view holds exactly one direct child, the host",
                1, result.topLevelObjects());
        assertEquals("no canvas container was arranged", 0, result.groupsPositioned());
        assertEquals(result.topLevelObjects(),
                result.groupsPositioned() + result.standaloneElementsPlaced()
                        + result.skippedContainers().size() + result.unhandled().size());
        for (MovedViewObjectDto moved : result.positionedContainers()) {
            assertTrue("a nested zone must never be claimed as a canvas placement",
                    childNamedId(moved.viewObjectId()) != null);
        }
        assertTrue("the nested zones are reported, just not in the canvas buckets",
                result.nestedContainersArranged().size() == 2);
    }

    /**
     * The host is still left standing, so it is still skipped — but a caller told only that it was
     * "left where it is" would plan against a layout that no longer exists inside it. Both arms are
     * pinned: a disclosure written on one branch is unreachable for the other case.
     */
    @Test
    public void shouldTellTheHostThatItsZonesWereArranged_andOnlyWhenTheyWere() {
        useModel(hostedZonesFixture());
        ArrangeGroupsResultDto arranged =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertEquals(1, arranged.skippedContainers().size());
        String withZones = arranged.skippedContainers().get(0).reason();
        assertTrue("the host entry must say the zones inside it were arranged. Actual: "
                + withZones, withZones.contains("WERE arranged"));
        assertTrue("and must point at the field that says where they went. Actual: " + withZones,
                withZones.contains("nestedContainersArranged"));

        // The other arm: a host holding no zone at all keeps the message it always had.
        useModel(unmovedHostFixture());
        ArrangeGroupsResultDto untouched =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();
        assertFalse("a host whose contents this call did not arrange must not claim it did",
                untouched.skippedContainers().isEmpty());
        for (SkippedContainerDto entry : untouched.skippedContainers()) {
            assertFalse("Actual: " + entry.reason(), entry.reason().contains("WERE arranged"));
        }
    }

    /**
     * A host too small for the arrangement is declined whole rather than half-filled. The parent
     * fit cascade grows native view groups; an ArchiMate host is not one, so growing it is not on
     * offer and moving some of its zones outside its own box would be worse than moving none.
     */
    @Test
    public void shouldDeclineAHostTooSmallToHoldTheArrangement() {
        useModel(hostedZonesFixture());
        IDiagramModelObject host = view().getChildren().get(0);
        host.setBounds(0, 0, 400, 200);

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertTrue("nothing is reported as arranged inside a host that cannot hold it",
                result.nestedContainersArranged().isEmpty());
        assertEquals("and the zones are left exactly where the caller had them — 200 is the "
                + "fixture's own y for the second zone, and a column arrangement would have "
                + "moved it to 210",
                200, childById(host, "obj-grp-b").getBounds().getY());
    }

    /**
     * A zone nested inside a TARGET is unchanged by any of this: it is a member of that target,
     * which is arranged on the canvas, and it is not arranged separately.
     */
    @Test
    public void shouldNotArrangeAZoneNestedInsideAnotherTarget() {
        useModel(nestedGroupingFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertEquals("the outer zone is arranged on the canvas as it always was",
                1, result.groupsPositioned());
        assertTrue("and the zone inside it is a member, not a separate arrangement",
                result.nestedContainersArranged().isEmpty());
    }

    /** The child of {@code parent} with the given view-object id, or null. */
    private IDiagramModelObject childById(IDiagramModelObject parent, String id) {
        for (IDiagramModelObject child : TopLevelGroupTargets.childrenOf(parent)) {
            if (child.getId().equals(id)) {
                return child;
            }
        }
        return null;
    }

    /** The view's direct child with the given id, or null when it has none. */
    private String childNamedId(String id) {
        for (IDiagramModelObject child : view().getChildren()) {
            if (child.getId().equals(id)) {
                return child.getId();
            }
        }
        return null;
    }

    /**
     * Naming a nested zone restricts the arrangement to it, and arranges it in its host's space.
     * The remedy the refusal used to publish — "name a top-level container in groupIds" — could
     * not be executed for the object that needed it, because naming the zone was itself refused.
     */
    @Test
    public void shouldArrangeOnlyTheNamedZone_whenGroupIdsNamesANestedOne() {
        useModel(hostedZonesFixture());

        ArrangeGroupsResultDto result = accessor.arrangeGroups(
                SESSION, VIEW_ID, "column", null, 40, List.of("obj-grp-b"), null).entity();

        assertEquals("only the named zone is arranged", 1, result.nestedContainersArranged().size());
        assertEquals("obj-grp-b", result.nestedContainersArranged().get(0).viewObjectId());
        assertEquals("obj-host-cr", result.nestedContainersArranged().get(0).hostViewObjectId());

        IDiagramModelObject host = view().getChildren().get(0);
        assertEquals("the zone the caller did not name keeps its position",
                20, childById(host, "obj-grp-a").getBounds().getY());
        assertEquals("and the named one moves to the host's content origin",
                20, childById(host, "obj-grp-b").getBounds().getY());
    }

    /**
     * A container nested inside another TARGET keeps today's refusal verbatim: it is a member of
     * the zone that holds it and moves with it. The split this story introduces is between a zone
     * under a HOST and a zone under a ZONE, and only the first is newly admissible.
     */
    @Test
    public void shouldStillRefuseAContainerNestedInsideAnotherTarget() {
        useModel(nestedGroupingFixture());

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                    List.of("obj-grp-inner"), null);
            fail("a container inside a target is a member of it, not a separate target");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("the message is unchanged. Actual: " + e.getMessage(),
                    e.getMessage().contains("not a top-level group"));
        }
    }

    /**
     * The zero arm of the no-gap reason. It became reachable the moment a view whose containers
     * all sit inside a host stopped being refused: the canvas arrangement places nothing, so a
     * loose lane-eligible element is told there is no gap — and telling it "only one container was
     * arranged" would send the caller looking for a container that is not on the canvas at all.
     */
    @Test
    public void shouldSayNoContainerWasArranged_whenEveryContainerSitsInsideAHost() {
        useModel(hostedZonesAndALoosePeerFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null, 40, null, null).entity();

        String reason = unhandledReasonFor(result, "obj-peer");
        assertNotNull("the loose peer must be accounted for", reason);
        assertEquals("no-inter-container-gap", codeOf(reason));
        assertTrue("the sentence must not claim a container was arranged. Actual: " + reason,
                reason.contains("no container was arranged"));
        assertFalse("Actual: " + reason, reason.contains("only one container"));
    }

    // ============ what the default-resolution route actually counts ============

    /**
     * Two zones, one child each, one child-to-child connection: the route COUNTS it and the
     * density-aware default fires. Measured rather than read, because the published sentence
     * about this route described the opposite case.
     */
    @Test
    public void shouldCountAChildToChildConnection_inTheDefaultResolutionRoute() {
        useModel(twoTopLevelZonesWiredInsideFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, null, null, null).entity();

        assertTrue("a connection between children of two zones is an inter-container connection. "
                + "Actual: " + result.defaultResolutionReason(),
                result.defaultResolutionReason().contains("isConnected=true with 1 inter-group"));
    }

    /**
     * Two zones, one connection drawn between the two BOXES: the route counts NOTHING. A
     * container is not inside itself, so neither endpoint resolves to a container and the
     * connection is a connection TO a zone rather than one running between two of them.
     *
     * <p>The published sentence used to name the child-to-child case as the excluded one and the
     * direct-between-containers case as the counted one, which is both of these backwards.</p>
     */
    @Test
    public void shouldNotCountABoxToBoxConnection_inTheDefaultResolutionRoute() {
        useModel(twoTopLevelZonesWiredBoxToBoxFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, null, null, null).entity();

        String reason = result.defaultResolutionReason();
        assertTrue("the box-to-box connection must not make the view connected. Actual: " + reason,
                reason.contains("no connection crosses between the arranged containers"));
        assertTrue("and the sentence must say a box-to-box connection is the excluded case. "
                + "Actual: " + reason,
                reason.contains("drawn directly between two container boxes is not counted"));
        assertTrue("while a child-to-child connection is the counted one. Actual: " + reason,
                reason.contains("objects inside two different containers"));
    }

    /**
     * A sibling that positions the view's OWN containers still declines this shape — the ruling
     * widened what the family SEES, not what these tools position — but it may not describe the
     * view falsely while declining. "View has no groups" was a confident wrong statement about a
     * canvas holding two populated zones, and an agent that cannot see the canvas cannot
     * contradict it.
     */
    @Test
    public void shouldNotCallTheViewGroupless_whenEveryContainerSitsInsideAHost() {
        useModel(hostedZonesFixture());

        try {
            accessor.optimizeGroupOrder(SESSION, VIEW_ID, null, null, null, null, null,
                    false, null, null);
            fail("optimize-group-order still has nothing of its own to reorder here");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertFalse("the old wording was false about this view. Actual: " + e.getMessage(),
                    e.getMessage().contains("View has no groups"));
            assertTrue("it must report the containers it can see. Actual: " + e.getMessage(),
                    e.getMessage().contains("has 2 top-level containers"));
            assertTrue("and say why they are not its to reorder. Actual: " + e.getMessage(),
                    e.getMessage().contains("drawn inside a host"));
            assertTrue("the remedy must name the tool that does position them. Actual: "
                    + e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("arrange-groups"));
        }
    }

    /**
     * The other arm of the same refusal: a view holding no container at any depth keeps the
     * message it always had. A shared refusal that fired on both cases would replace one false
     * statement with another, telling a genuinely flat view it has containers inside a host.
     */
    @Test
    public void shouldKeepTheOriginalRefusal_whenTheViewHoldsNoContainerAtAnyDepth() {
        // A host with a plain child and nothing else: container-SHAPED, but holding no zone at
        // any depth, so the outermost walk finds nothing and the original wording is the true one.
        useModel(hostOnlyFixture());

        try {
            accessor.optimizeGroupOrder(SESSION, VIEW_ID, null, null, null, null, null,
                    false, null, null);
            fail("a view with no container anywhere has nothing to reorder");
        } catch (ModelAccessException e) {
            assertTrue("Actual: " + e.getMessage(), e.getMessage().contains("View has no groups"));
            assertFalse("it must not claim containers are nested somewhere. Actual: "
                    + e.getMessage(), e.getMessage().contains("drawn inside a host"));
        }
    }

    /**
     * The shared refusal must fire only when every container really IS inside a host. Its callers
     * gate on different populations — one on the view's own containers, one on the POPULATED ones
     * — so a view whose single container is a top-level box that happens to be empty reaches the
     * second caller's branch with nothing nested anywhere. Telling that caller its container is
     * "drawn inside a host" and pointing at layout-within-group is a false statement about a box
     * sitting on the canvas, produced by the very machinery added to stop false statements about
     * container topology.
     */
    @Test
    public void shouldNotClaimAContainerIsNested_whenItIsAnEmptyDirectChildOfTheView() {
        useModel(emptyTopLevelContainerOnlyFixture());

        assertNull("an empty container on the canvas is not nested inside anything",
                TopLevelGroupTargets.containersAreAllNested(
                        VIEW_ID, view(), "some tool arranges the view's own containers."));
    }

    /** The positive control: a view whose only containers really are inside a host still fires. */
    @Test
    public void shouldStillFireTheSharedRefusal_whenTheContainersAreGenuinelyNested() {
        useModel(hostedZonesFixture());

        ModelAccessException refusal = TopLevelGroupTargets.containersAreAllNested(
                VIEW_ID, view(), "some tool arranges the view's own containers.");

        assertNotNull("this view's containers ARE all inside a host", refusal);
        assertTrue("Actual: " + refusal.getMessage(),
                refusal.getMessage().contains("has 2 top-level containers"));
    }

    /**
     * A response may not report arranging containers while telling the caller no connection runs
     * between the containers it arranged. The default-spacing route scoped its count to the CANVAS
     * containers, so on a view whose zones all sit inside a host it counted zero however many
     * connections crossed between them — and then published that zero as a reason beside the very
     * zones it had just arranged. Same self-contradiction class as the defect this work closed,
     * one field along.
     */
    @Test
    public void shouldNotSayNoConnectionCrosses_whileArrangingConnectedNestedZones() {
        useModel(hostedZonesFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, null, null, null).entity();

        assertEquals("the fixture's two zones were arranged", 2,
                result.nestedContainersArranged().size());
        String reason = result.defaultResolutionReason();
        assertFalse("a call that arranged two connected containers may not report that no "
                + "connection crosses between the containers it arranged. Actual: " + reason,
                reason.contains("no connection crosses between the arranged containers"));
        // The sentence that actually fired before the fix, and the one this whole change exists
        // to stop being published beside a non-zero count. Asserting only the clause above was a
        // pin that passed while a worse one was true.
        assertFalse("nor may it claim fewer than two containers while listing two arranged. "
                + "Actual: " + reason,
                reason.contains("fewer than 2 top-level groups"));
    }

    /**
     * And the consequence of that blindness: the density-aware tier is chosen from the connection
     * count, so a count of zero silently drops the whole heuristic for a nested-only view — the
     * spacing it then hands to the nested arrangement is the flat fallback rather than the tier
     * the view's connectivity earns.
     */
    @Test
    public void shouldResolveTheDensityAwareTier_whenOnlyNestedZonesAreArranged() {
        useModel(hostedZonesFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, null, null, null).entity();

        assertTrue("the density-aware default must fire on a connected nested-only view. Actual: "
                + result.defaultResolutionReason(),
                result.defaultResolutionReason().contains("isConnected=true"));
    }

    // ============ review findings: frames, empty runs, and deeper nesting ============

    /**
     * Geometry may only be compared inside one coordinate frame. A view holding a canvas zone AND
     * zones inside a host offers two, and subtracting a host-relative rectangle from an absolute
     * one produces a number that is not a distance — which the response then publishes as a
     * measured pixel gap.
     */
    @Test
    public void shouldMeasureSpacingInOneCoordinateFrame_whenTheViewOffersTwo() {
        IArchimateModel m = hostedZonesFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        addGroupingElement(m, v, "Canvas Z", "grp-canvas");

        List<IDiagramModelObject> outermost = TopLevelGroupTargets.collectOutermost(v);
        assertEquals("the fixture must actually span two frames, or this proves nothing",
                3, outermost.size());

        List<IDiagramModelObject> frame = TopLevelGroupTargets.largestSharedFrame(v, outermost);
        assertEquals("the two zones inside the host are the larger frame",
                List.of("obj-grp-a", "obj-grp-b"), idsOf(frame));
        EObject onlyParent = frame.get(0).eContainer();
        for (IDiagramModelObject container : frame) {
            assertTrue("every container measured together must share one parent",
                    container.eContainer() == onlyParent);
        }
    }

    /** Ties go to the canvas, so a view that never held a nested container measures as before. */
    @Test
    public void shouldPreferTheCanvasFrame_whenTwoFramesHoldEquallyMany() {
        IArchimateModel m = hostedZonesFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        addGroupingElement(m, v, "Canvas A", "grp-c1");
        addGroupingElement(m, v, "Canvas B", "grp-c2");

        assertEquals("with two in each frame the view's own children win",
                List.of("obj-grp-c1", "obj-grp-c2"),
                idsOf(TopLevelGroupTargets.largestSharedFrame(
                        v, TopLevelGroupTargets.collectOutermost(v))));
    }

    /**
     * An arrangement of nothing must not crash and must not report a column count. The grid's
     * automatic column count divides by the widest container, which is zero when there is none —
     * and {@code spacing: 0} is a legal argument, so the divisor becomes zero too.
     */
    @Test
    public void shouldNotComputeAGridOverNoContainers() {
        for (String arrangement : List.of("row", "column", "grid")) {
            ContainerArrangement.Placement placement = ContainerArrangement.compute(
                    List.of(), arrangement, null, 0, List.of(),
                    ContainerArrangement.ORIGIN, ContainerArrangement.ORIGIN);
            assertTrue("nothing is placed", placement.positions().isEmpty());
            assertNull("no grid was computed, so there is no column count to report — 0 would "
                    + "read as a grid that resolved to zero columns",
                    placement.columnsUsed());
        }
    }

    /**
     * The disclosure has to survive a second level of nesting. A plan is keyed by the zone's
     * immediate parent, but the skipped bucket only ever lists the view's own children — so with
     * a host inside a host, the entry the caller reads is the outer one and a lookup by the inner
     * id misses it entirely.
     */
    @Test
    public void shouldTellTheOutermostHost_whenTheArrangedZoneSitsTwoLevelsDeep() {
        useModel(hostInsideHostFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertEquals("the zone two levels down was arranged",
                1, result.nestedContainersArranged().size());
        assertEquals("its coordinates are relative to the INNER host",
                "obj-host-inner", result.nestedContainersArranged().get(0).hostViewObjectId());
        assertEquals("and the outer host is the entry the caller actually reads",
                1, result.skippedContainers().size());
        assertEquals("obj-host-outer", result.skippedContainers().get(0).viewObjectId());
        assertTrue("which must say a container inside it moved. Actual: "
                + result.skippedContainers().get(0).reason(),
                result.skippedContainers().get(0).reason().contains("WERE arranged"));
    }

    /**
     * A host measured and declined for want of room is a different outcome from a host that never
     * held a zone, and the caller acts on it differently — one is fixed by enlarging the host and
     * nothing else is. Reporting both with the same sentence is the silent shortfall again.
     */
    @Test
    public void shouldSayTheZonesWereDeclined_whenTheHostCannotHoldTheArrangement() {
        useModel(hostedZonesFixture());
        view().getChildren().get(0).setBounds(300, 250, 400, 200);

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertTrue("nothing fitted", result.nestedContainersArranged().isEmpty());
        String reason = result.skippedContainers().get(0).reason();
        assertTrue("the caller must learn the zones were evaluated and rejected. Actual: "
                + reason, reason.contains("were NOT arranged"));
        assertTrue("and what would change it. Actual: " + reason,
                reason.contains("enlarge this host"));
        assertFalse("it must not read as the host having nothing inside it. Actual: " + reason,
                reason.contains("WERE arranged"));
    }

    /**
     * {@code adjust-view-spacing} still inflates the view's own containers only, but it may not
     * describe a canvas full of populated zones as having none — the ruling widened what the
     * whole family SEES, refusal wording included.
     */
    @Test
    public void shouldNotSayAdjustViewSpacingHasNoGroups_whenTheyAreAllInsideAHost() {
        useModel(hostedZonesFixture());

        try {
            accessor.adjustViewSpacing(SESSION, VIEW_ID, 40, 10, 40, true);
            fail("adjust-view-spacing has no container of its own to inflate here");
        } catch (ModelAccessException e) {
            assertFalse("the old wording was false about this view. Actual: " + e.getMessage(),
                    e.getMessage().contains("This view has no groups with children"));
            assertTrue("Actual: " + e.getMessage(),
                    e.getMessage().contains("has 2 top-level containers"));
            assertTrue("Actual: " + e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("arrange-groups"));
        }
    }

    // ==================== guards ====================

    /**
     * Negative control (drives the real path, does not merely assert a predicate): a view holding
     * only native groups must arrange byte-identically to how it always has.
     */
    @Test
    public void shouldLeaveNativeGroupGeometryUnchanged_whenTheViewHoldsOnlyNativeGroups() {
        useModel(nativeOnlyFixture());

        accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null);

        List<IDiagramModelObject> children = view().getChildren();
        assertEquals(20, children.get(0).getBounds().getX());
        assertEquals(20, children.get(0).getBounds().getY());
        assertEquals(20, children.get(1).getBounds().getX());
        assertEquals(210, children.get(1).getBounds().getY());
    }

    /**
     * Only the view's own children are targets. A {@code Grouping} nested inside another container
     * is a member of that container and must not be lifted onto the top-level arrangement.
     */
    @Test
    public void shouldNotTreatANestedGroupingAsATopLevelTarget() {
        useModel(nestedGroupingFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertEquals("the nested Grouping is a member of Zone A, not a top-level target",
                1, result.groupsPositioned());
    }

    /**
     * Arranged order must be stable and must follow the view's own child order, so a caller can
     * predict the result from what it built.
     */
    @Test
    public void shouldArrangeInViewChildOrder_whenBothKindsAreMixed() {
        useModel(mixedFixture());
        List<String> before = new ArrayList<>();
        for (IDiagramModelObject c : view().getChildren()) {
            before.add(c.getId());
        }

        accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null);

        List<IDiagramModelObject> sorted = new ArrayList<>(view().getChildren());
        sorted.sort((a, b) -> Integer.compare(a.getBounds().getY(), b.getBounds().getY()));
        List<String> afterByY = new ArrayList<>();
        for (IDiagramModelObject c : sorted) {
            afterByY.add(c.getId());
        }
        assertEquals("top-to-bottom order must match the view's child order", before, afterByY);
    }

    // ==================== the title band, and the guard that has no observable ====================

    /**
     * The shared rule every pass that positions a container's children must ask.
     *
     * <p>An element container reserves a taller title band than a native group. Three separate
     * passes used to hard-code the native group's 24 px, which is invisible to a type census
     * because those lines never mention {@code IDiagramModelGroup} — widening the collection alone
     * would have laid a {@code Grouping}'s first row of children underneath its own label.
     */
    @Test
    public void shouldReserveATallerTitleBandForAnElementContainerThanForANativeGroup() {
        useModel(mixedFixture());

        int groupingBand = NestedLayoutOperations.labelHeightFor(childNamed("Zone A"));
        int nativeBand = NestedLayoutOperations.labelHeightFor(childNamed("Native Group"));

        assertTrue("an element container's title band must exceed a native group's, or its first "
                + "row of children is laid out under its own label: "
                + groupingBand + " vs " + nativeBand, groupingBand > nativeBand);
    }

    /**
     * Wiring pin for the arrange-target exclusion in the standalone lane.
     *
     * <p>This one has no behavioural observable and cannot get one: the exclusion only bites for an
     * object that is BOTH an arrange target AND lane-eligible, and no such object exists today —
     * the lane admits {@code Node}/{@code Device}/{@code Path}/{@code CommunicationNetwork} and a
     * target is a native group or an {@code IGrouping}. Deleting the guard entirely would not fail
     * a single behavioural test, which is exactly why it would rot.
     *
     * <p>So the pin is on the wiring instead: the lane must ask the shared predicate rather than
     * name one concrete type. If the qualifier set or the target set ever widens into an overlap,
     * the guard is already asking the right question — and if someone narrows it back to an
     * {@code instanceof}, this fails and says why.
     */
    @Test
    public void shouldExcludeArrangeTargetsFromTheLaneByAskingTheSharedPredicate() {
        String source = readRepoFile(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/"
                        + "ArrangeGroupsStandaloneLane.java");

        assertTrue("the lane's member walk must exclude arrange targets via the shared predicate, "
                + "so the two roles cannot collide if either set widens",
                source.contains("if (TopLevelGroupTargets.isTarget(child)) continue;"));
        assertFalse("the lane must not re-narrow the exclusion to one concrete container type",
                source.contains("if (child instanceof IDiagramModelGroup) continue;"));
    }

    // ---- populated top-level containers arrange-groups deliberately leaves standing ----

    @Test
    public void describeSkipped_shouldNameAPopulatedNodeContainer_whichIsNotArranged() {
        // A Node holding children is a host, not a zone (TopLevelGroupTargets.isTarget), so it is
        // deliberately not arranged. That is sound and it is invisible: on the canvas the object is
        // container-shaped, so a caller has no reason to expect it to sit still. Measured
        // 2026-08-14: groupsPositioned came back 8 on a view with 9 populated top-level containers,
        // and the ninth held an entire account branch.
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addGroupingElement(m, v, "Zone A", "grp-a");
        IDiagramModelArchimateObject host = addGroupingElement(m, v, "Region", "rgn-1");
        // Re-type the second container's concept to a Node so it becomes a host rather than a zone.
        INode node = IArchimateFactory.eINSTANCE.createNode();
        node.setId("rgn-1-node-concept");
        node.setName("Region");
        m.getFolder(FolderType.OTHER).getElements().add(node);
        host.setArchimateConcept(node);

        List<SkippedContainerDto> skipped = TopLevelGroupTargets.describeSkipped(v);

        assertEquals("the Node container must be reported, the Grouping must not", 1, skipped.size());
        assertEquals("obj-rgn-1", skipped.get(0).viewObjectId());
        assertEquals("Region", skipped.get(0).name());
        assertEquals("the type is what made it ineligible, so name it", "Node",
                skipped.get(0).elementType());
        assertNotNull("a bare id and name reads as an unexplained omission", skipped.get(0).reason());
        // This string IS the response field the follow-up call reads, so the remedy it names has
        // to be the one that works. apply-positions moves the box to coordinates the caller has
        // to invent; groupIds hands the id straight back to the tool that just declined it.
        assertTrue("the reason must tell the caller what to do about it, and the direct remedy is "
                        + "to name this id in groupIds: " + skipped.get(0).reason(),
                skipped.get(0).reason().contains("groupIds"));
    }

    @Test
    public void describeSkipped_shouldBeEmpty_whenEveryPopulatedContainerIsArrangeable() {
        // The negative control. Without it this reporting could fire on everything and still pass
        // the test above.
        List<SkippedContainerDto> skipped =
                TopLevelGroupTargets.describeSkipped(firstViewOf(mixedFixture()));
        assertTrue("a view of Groupings and native groups has nothing to report: " + skipped,
                skipped.isEmpty());
    }

    @Test
    public void describeSkipped_shouldIgnoreAnEmptyNode_whichIsNotAContainerAtAll() {
        // An empty box that did not move is not a finding, and reporting it would bury the one
        // that matters.
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addGroupingElement(m, v, "Zone A", "grp-a");
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode lone = f.createNode();
        lone.setId("lone-concept");
        lone.setName("Standalone Host");
        m.getFolder(FolderType.OTHER).getElements().add(lone);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-lone");
        obj.setArchimateConcept(lone);
        obj.setBounds(0, 0, 120, 55);
        v.getChildren().add(obj);

        assertTrue("a childless Node is not a skipped container",
                TopLevelGroupTargets.describeSkipped(v).isEmpty());
    }

    // ---- the denominator, and the residual population between the buckets ----

    /**
     * The response must carry the total it was measured against.
     *
     * <p>{@code groupsPositioned}, {@code standaloneElementsPlaced} and {@code skippedContainers}
     * are three counters with nothing to sum against, so "8" can never be wrong: no reported value
     * disagrees with it. A shortfall was therefore undetectable from the response by construction,
     * not by oversight — the caller could only re-derive the tool's own traversal and diff it.
     *
     * <p>Every direct child counts, a note included. Filtering a class of object out of the
     * denominator before counting is the move that produced the defect one level up: naming the
     * skipped containers closed the container case and left the leaf case looking identical.
     */
    @Test
    public void arrangeGroups_shouldDeclareEveryDirectChildOfTheView_asTheDenominator() {
        useModel(fallThroughFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertEquals("the denominator is every direct child of the view, unfiltered",
                view().getChildren().size(), result.topLevelObjects());
    }

    /**
     * The four buckets must sum to the denominator on every shape of view.
     *
     * <p>Asserted as an invariant that fails the build rather than documented as a convention.
     * Each fixture holds one of the populations that used to fall between the buckets.
     */
    @Test
    public void arrangeGroups_shouldReconcileTheBuckets_onEveryFallThroughPopulation() {
        assertReconciles("a childless element of a lane-eligible type, one connected container",
                oneConnectionFixture(), "topology", null, null);
        assertReconciles("a childless element whose type the lane never places",
                plainElementFixture(), "column", null, null);
        assertReconciles("a lane-eligible element on a call where the lane never runs",
                laneEligibleFixture(), "column", null, null);
        assertReconciles("a note, which is never an arrangement target of any kind",
                noteFixture(), "column", null, null);
        assertReconciles("a target the caller did not name in groupIds",
                mixedFixture(), "column", null, List.of("obj-grp-a"));
        assertReconciles("a topology call that engages the standalone lane",
                laneEngagedFixture(), "topology", null, null);
        assertReconciles("a topology call that resolves to a grid, so the lane is skipped",
                laneEngagedFixture(), "topology", 2, null);
        assertReconciles("a host the caller named in groupIds, which is arranged rather than skipped",
                unmovedHostFixture(), "column", null,
                List.of("obj-grp-a", "obj-grp-b", "obj-host"));
        assertReconciles("a named host the lane would otherwise have claimed as well",
                lanePlacedHostFixture(), "topology", null,
                List.of("obj-grp-a", "obj-grp-b", "obj-host"));
    }

    /**
     * Coverage and disjointness over ids, which is the assertion the arithmetic cannot make.
     *
     * <p>{@code unhandled} is a residual — the other three buckets subtracted from the view's own
     * children — so the count reconciles by construction and a sum alone proves nothing. What can
     * still go wrong is an object claimed by two buckets, or a traversal that drifts away from
     * {@code getChildren()}. Both are visible only over the ids.
     */
    @Test
    public void arrangeGroups_shouldKeepTheBucketsDisjointAndCovering_overViewObjectIds() {
        assertBucketsPartitionTheView(fallThroughFixture(), "column", null, null);
        assertBucketsPartitionTheView(laneEngagedFixture(), "topology", null, null);
        assertBucketsPartitionTheView(mixedFixture(), "column", null, List.of("obj-grp-a"));
        assertBucketsPartitionTheView(lanePlacedHostFixture(), "topology", null, null);
        // A named host is arranged, so it must leave skippedContainers and enter groupsPositioned.
        // Leave it in both and this is where it shows, as a duplicate rather than as a sum.
        assertBucketsPartitionTheView(unmovedHostFixture(), "column", null,
                List.of("obj-grp-a", "obj-grp-b", "obj-host"));
        // The same view on the axis that engages the lane: the host is claimed by the arrangement
        // and by the lane at once, which the id partition sees and the arithmetic does not.
        assertBucketsPartitionTheView(lanePlacedHostFixture(), "topology", null,
                List.of("obj-grp-a", "obj-grp-b", "obj-host"));
    }

    @Test
    public void arrangeGroups_shouldNameATopLevelNote_asNotAnArchimateElement() {
        useModel(noteFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        SkippedContainerDto entry = onlyUnhandled(result);
        assertEquals("note-1", entry.viewObjectId());
        assertNull("a note carries no ArchiMate concept, so there is no type to name",
                entry.elementType());
        assertTrue("a note must read as a deliberate exclusion the caller can filter on, "
                + "not as a near-miss. Actual: " + entry.reason(),
                entry.reason().startsWith("not-an-archimate-element:"));
    }

    /**
     * A target the caller did not name in {@code groupIds} belongs to no bucket today: it is
     * filtered out of the arranged list, and {@code collectSkipped} excludes it because it IS a
     * target. On a restricted call the reconciliation is arithmetically false without this reason.
     */
    @Test
    public void arrangeGroups_shouldNameAContainerTheCallerDidNotRequest_whenGroupIdsRestrictsTheCall() {
        useModel(mixedFixture());

        ArrangeGroupsResultDto result = accessor.arrangeGroups(
                SESSION, VIEW_ID, "column", null, 40, List.of("obj-grp-a"), null).entity();

        assertEquals("only the named container is arranged", 1, result.groupsPositioned());
        assertEquals("the other two containers must be named, not silently dropped",
                2, result.unhandled().size());
        for (SkippedContainerDto entry : result.unhandled()) {
            assertTrue("an unrequested container must say so rather than borrow an element reason. "
                    + "Actual: " + entry.reason(),
                    entry.reason().startsWith("not-requested:"));
            assertTrue("the reason must name the parameter that excluded it",
                    entry.reason().contains("groupIds"));
        }
    }

    @Test
    public void arrangeGroups_shouldSayTheLaneDidNotRun_whenTheArrangementIsNotTopology() {
        useModel(laneEligibleFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        SkippedContainerDto entry = onlyUnhandled(result);
        assertEquals("Node", entry.elementType());
        assertTrue("a lane-eligible element skipped because the lane never ran must not be told "
                + "its type is wrong. Actual: " + entry.reason(),
                entry.reason().startsWith("lane-not-run:"));
        assertTrue("the reason must name the condition that would have run the lane",
                entry.reason().contains("topology"));
    }

    @Test
    public void arrangeGroups_shouldSayTooFewConnections_whenTheLaneRanAndTheElementReachesOneContainer() {
        useModel(oneConnectionFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null, 40, null, null).entity();

        assertEquals("one connected container is below the lane's threshold",
                0, result.standaloneElementsPlaced());
        SkippedContainerDto entry = onlyUnhandled(result);
        assertTrue("the near-miss must be distinguishable from a type that never qualifies. "
                + "Actual: " + entry.reason(),
                entry.reason().startsWith("insufficient-connections:"));
    }

    @Test
    public void arrangeGroups_shouldSayTheTypeIsNotLaneEligible_forAPlainApplicationComponent() {
        useModel(plainElementFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null, 40, null, null).entity();

        SkippedContainerDto entry = onlyUnhandled(result);
        assertEquals("ApplicationComponent", entry.elementType());
        assertTrue("a type the lane never places must say so rather than report a near-miss. "
                + "Actual: " + entry.reason(),
                entry.reason().startsWith("type-not-lane-eligible:"));
    }

    /**
     * {@code arrangeGroups} collects targets with {@code collect}, which has no children guard, so
     * an empty container IS arranged and IS counted. A residual bucket that claimed it would put
     * the same object in two places and break the reconciliation.
     */
    @Test
    public void arrangeGroups_shouldCountAnEmptyContainerAsArranged_notAsResidual() {
        useModel(emptyContainerFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        assertEquals("an empty container is still a target and is still positioned",
                2, result.groupsPositioned());
        assertTrue("an arranged container must never appear in the residual: " + result.unhandled(),
                result.unhandled().isEmpty());
    }

    /**
     * The lane's own placements must reconcile too, and the placed element must leave the residual.
     */
    @Test
    public void arrangeGroups_shouldPlaceTheStandaloneElement_andLeaveItOutOfTheResidual() {
        useModel(laneEngagedFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null, 40, null, null).entity();

        assertEquals("the lane must place the element wired to both zones",
                1, result.standaloneElementsPlaced());
        for (SkippedContainerDto entry : result.unhandled()) {
            assertNotEquals("an element the lane placed is not unhandled",
                    "obj-hub", entry.viewObjectId());
        }
    }

    /**
     * {@code standaloneElementsPlaced} counts emitted placements, not classifications, and
     * {@code assignToGaps} can drop a classified qualifier when its connected containers collapse
     * to a single index. That branch is unreachable from this call site today — every connected id
     * is a member of the same ordered list the assignment indexes, so the range cannot be empty.
     *
     * <p>Pinned rather than assumed: the day the drop becomes reachable, the reconciliation must
     * fail here instead of silently losing an element into neither bucket.
     */
    @Test
    public void arrangeGroups_shouldPlaceEveryClassifiedQualifier_soTheCountIsNotAClassification() {
        useModel(laneEngagedFixture());
        List<IDiagramModelObject> targets = new ArrayList<>();
        for (IDiagramModelObject child : view().getChildren()) {
            if (TopLevelGroupTargets.isTarget(child)) {
                targets.add(child);
            }
        }
        int classified = ArrangeGroupsStandaloneLane
                .classify(view().getChildren(), targets).size();

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null, 40, null, null).entity();

        assertEquals("every classified qualifier must reach a placement, or the reconciliation "
                + "loses it into no bucket at all", classified, result.standaloneElementsPlaced());
    }

    /**
     * The ordinary skipped-container case, driven through the tool rather than the collector.
     *
     * <p>The existing coverage for {@code skippedContainers} calls
     * {@link TopLevelGroupTargets#describeSkipped(com.archimatetool.model.IDiagramModelContainer)}
     * directly, which is now the single-argument overload the production path no longer uses. A
     * green suite therefore said nothing about what the tool actually returns. This drives
     * {@code arrangeGroups} end to end and pins that a populated host the lane did not move is
     * still reported, with its identity, its type and its original reason intact — so the narrowing
     * that keeps a lane-placed host out of this list cannot quietly grow into emptying it.
     */
    @Test
    public void arrangeGroups_shouldStillReportAPopulatedHost_whenTheLaneDidNotMoveIt() {
        useModel(unmovedHostFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null, 40, null, null).entity();

        assertEquals("the host is wired to nothing, so the lane leaves it alone",
                0, result.standaloneElementsPlaced());
        assertEquals("and it must still be named as a container left standing",
                1, result.skippedContainers().size());
        SkippedContainerDto skipped = result.skippedContainers().get(0);
        assertEquals("obj-host", skipped.viewObjectId());
        assertEquals("Node", skipped.elementType());
        assertTrue("the pre-existing reason text must survive the narrowing: " + skipped.reason(),
                skipped.reason().contains("host rather than a zone")
                        && skipped.reason().contains("groupIds"));
        assertTrue("a skipped container is not also unhandled: " + result.unhandled(),
                result.unhandled().stream()
                        .noneMatch(u -> "obj-host".equals(u.viewObjectId())));
    }

    /**
     * A populated host the lane moved must not also be reported as left standing.
     *
     * <p>The two predicates overlap: {@code collectSkipped} admits any populated non-target, and
     * the lane admits any non-target of a technology type wired to two containers. A populated
     * {@code Node} satisfies both, and the response would then count it twice AND tell the caller
     * it was "left where it is" about an object that moved. Measured across the local model corpus
     * at 0 occurrences today, and one predicate away on 401 objects — reachable, not hypothetical.
     */
    @Test
    public void arrangeGroups_shouldNotReportALanePlacedHost_asASkippedContainer() {
        useModel(lanePlacedHostFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null, 40, null, null).entity();

        assertEquals("the populated host is wired to both zones, so the lane places it",
                1, result.standaloneElementsPlaced());
        for (SkippedContainerDto entry : result.skippedContainers()) {
            assertNotEquals("an object this call moved was not skipped, and saying so is a false "
                    + "statement about the tool's own behaviour",
                    "obj-host", entry.viewObjectId());
        }
    }

    /**
     * The denominator and the residual are decided from the view as read, so they are honest on a
     * queued call and must be populated there. Unlike the effective geometry, nothing here is an
     * outcome; it breaks the moment the computation moves after the gate.
     */
    @Test
    public void arrangeGroups_shouldReportTheDenominatorAndResidual_onAQueuedCall() {
        useModel(fallThroughFixture());
        dispatcher.beginBatch(SESSION, "queue one arrangement");

        MutationResult<ArrangeGroupsResultDto> queued =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null);

        assertTrue("the call must queue rather than apply", queued.isBatched());
        ArrangeGroupsResultDto result = queued.entity();
        assertEquals("the denominator is read from the view, not produced by the write",
                view().getChildren().size(), result.topLevelObjects());
        assertFalse("the residual is known before anything is applied, so a queued call must "
                + "still name it", result.unhandled().isEmpty());
    }

    /**
     * The same on the approval path. The report is built before the gate, which is what makes all
     * three paths free — and what makes moving it a silent regression on two of them.
     */
    @Test
    public void arrangeGroups_shouldReportTheDenominatorAndResidual_onAProposedCall() {
        useModel(fallThroughFixture());
        dispatcher.setApprovalModeProvider(() -> true);

        MutationResult<ArrangeGroupsResultDto> proposed =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null);

        assertTrue("the call must be held for approval rather than applied", proposed.isProposal());
        ArrangeGroupsResultDto result = proposed.entity();
        assertEquals("the denominator is a fact about the view, not about the pending write",
                view().getChildren().size(), result.topLevelObjects());
        assertFalse("the residual is knowable before approval, so withholding it would be a "
                + "second silence", result.unhandled().isEmpty());
        assertTrue("the effective geometry, by contrast, genuinely does not exist yet",
                result.positionedContainers().isEmpty());
    }

    /**
     * A container named twice in {@code groupIds} is arranged once.
     *
     * <p>Every consumer of the resolved list counted the copies. The arrangement reserved a slot
     * for a container that does not exist, so the real one landed in the <em>second</em> slot;
     * {@code groupsPositioned} said two containers had been positioned on a view holding one; the
     * effective-geometry read-back listed the same view object id twice, in a list the coverage
     * assertion below relies on being duplicate-free; and the reconciliation over-counted by
     * exactly the number of repeats, making the invariant this story publishes false on an input
     * a caller can send by accident.
     */
    @Test
    public void arrangeGroups_shouldArrangeAContainerOnce_whenGroupIdsNamesItTwice() {
        useModel(mixedFixture());

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                List.of("obj-grp-a", "obj-grp-a"), null).entity();

        assertEquals("one container was named, however many times", 1, result.groupsPositioned());
        assertEquals("and it is reported once", 1, result.positionedContainers().size());
        assertEquals("in the first slot, not the second — a repeat must not reserve a phantom",
                20, result.positionedContainers().get(0).newY());
        assertEquals("the reconciliation must hold on a repeated id", result.topLevelObjects(),
                result.groupsPositioned() + result.standaloneElementsPlaced()
                        + result.skippedContainers().size() + result.unhandled().size());
    }

    /**
     * A repeat must not swallow a real error. Validity is decided before the repeat is dropped, so
     * a caller that names a bad id twice is still told the id is bad.
     */
    @Test
    public void arrangeGroups_shouldStillRejectAnUnknownId_whenItIsNamedTwice() {
        useModel(mixedFixture());

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                    List.of("no-such-id", "no-such-id"), null);
            fail("an unknown id is an error however many times it is repeated");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
            assertTrue("Actual: " + e.getMessage(), e.getMessage().contains("Unknown id"));
        }
    }

    /**
     * The reason each object gets, asserted per id on a view holding every population at once.
     *
     * <p>The reconciliation cannot catch a mis-attributed reason: {@code unhandled} is a residual,
     * so the count is right however the reasons are shuffled, and the id partition is right too —
     * every object is still claimed exactly once, just described wrongly. Only pinning the pairing
     * catches a reason handed to the wrong object.
     */
    @Test
    public void arrangeGroups_shouldGiveEachObjectItsOwnReason_whenEveryPopulationIsPresentAtOnce() {
        useModel(fallThroughFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null, 40, null, null).entity();

        Map<String, String> reasonById = new HashMap<>();
        for (SkippedContainerDto entry : result.unhandled()) {
            reasonById.put(entry.viewObjectId(), entry.reason());
        }
        assertEquals("a note carries no concept and no lane could ever take it",
                "not-an-archimate-element", codeOf(reasonById.get("note-1")));
        assertEquals("a lane-eligible type the lane ran for, but which reaches no container",
                "insufficient-connections", codeOf(reasonById.get("obj-gw")));
        assertEquals("a type the lane never places, whether or not it ran",
                "type-not-lane-eligible", codeOf(reasonById.get("obj-billing")));
        assertTrue("the populated host belongs to the skipped bucket, not the residual",
                !reasonById.containsKey("obj-host"));
    }

    /**
     * A topology call carrying {@code columns} resolves to a grid, where "between two containers"
     * has no single meaning, so the lane is skipped entirely. An element left unplaced there fell
     * through for that reason and not for want of connections — telling it otherwise would send the
     * caller looking for connections to add that would change nothing.
     */
    @Test
    public void arrangeGroups_shouldSayTheLaneDidNotRun_whenTopologyResolvesToAGrid() {
        useModel(laneEngagedFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "topology", 2, 40, null, null).entity();

        assertEquals("the grid axis skips the lane", 0, result.standaloneElementsPlaced());
        assertEquals("lane-not-run", codeOf(onlyUnhandled(result).reason()));
    }

    /**
     * The published claim is that {@code unhandled} covers a note, an image <em>and</em> a view
     * reference. Two of the three were asserted only in prose.
     */
    @Test
    public void arrangeGroups_shouldNameAnImageAndAViewReference_asNotArchimateElements() {
        useModel(nonArchimateFixture());

        ArrangeGroupsResultDto result =
                accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null).entity();

        Map<String, String> reasonById = new HashMap<>();
        for (SkippedContainerDto entry : result.unhandled()) {
            reasonById.put(entry.viewObjectId(), entry.reason());
            assertNull("none of these carries an ArchiMate concept", entry.elementType());
        }
        assertEquals("three non-ArchiMate objects sit at the top level", 3, reasonById.size());
        for (String id : List.of("note-1", "image-1", "ref-1")) {
            assertEquals(id + " must be named, not silently dropped",
                    "not-an-archimate-element", codeOf(reasonById.get(id)));
        }
    }

    /**
     * Every reason is one line an agent can triage on: a stable leading code plus a short phrase.
     * The long guidance belongs in the tool description, which is transmitted once rather than once
     * per entry — a 97-entry residual occurs on one view in the corpus and must stay readable.
     */
    @Test
    public void arrangeGroups_shouldKeepEveryReasonShortAndCoded() {
        for (IArchimateModel fixture : List.of(fallThroughFixture(), laneEngagedFixture())) {
            useModel(fixture);
            ArrangeGroupsResultDto result = accessor.arrangeGroups(
                    SESSION, VIEW_ID, "column", null, 40, null, null).entity();
            for (SkippedContainerDto entry : result.unhandled()) {
                assertTrue("a reason longer than 90 characters is prose, and prose repeated per "
                        + "entry is what floods the field: " + entry.reason(),
                        entry.reason().length() <= 90);
                assertTrue("every reason must lead with a stable code so the field can be triaged "
                        + "without reading the prose: " + entry.reason(),
                        entry.reason().matches("^[a-z][a-z-]+: .+"));
            }
        }
    }

    /**
     * The arrangement itself must not move. Reporting more about a call is worth nothing if it
     * changes what the call did — and the published claim that {@code get-view-contents}'
     * {@code topLevelGroups} equals this tool's {@code groupsPositioned} depends on it.
     *
     * <p>The expected values are <b>captured from the released code</b>, by running this fixture
     * through the arrangement as it stood before any of this reporting work existed and recording
     * what came back. They are literals here on purpose: re-running the current implementation
     * twice and comparing the two runs proves only that it is deterministic, which a regression
     * would be too. Only a number written down before the change can say the change did not move
     * it.</p>
     *
     * <p>Each row is {@code arrangement | columns | groupIds -> groupsPositioned, layoutWidth,
     * layoutHeight, standaloneElementsPlaced, then x,y,width,height per top-level object in view
     * child order}. {@code obj-hub} at {@code 0,0,120,55} means the lane did not move it, which is
     * itself part of the pin.</p>
     */
    @Test
    public void arrangeGroups_shouldLeaveTheArrangementByteIdentical_acrossEveryAxis() {
        assertGeometryMatchesBaseline("grid", null, null, 2, 460, 170, 0,
                "20,20,200,150", "260,20,200,150", "0,0,120,55");
        assertGeometryMatchesBaseline("row", null, null, 2, 460, 170, 0,
                "20,20,200,150", "260,20,200,150", "0,0,120,55");
        assertGeometryMatchesBaseline("column", null, null, 2, 220, 360, 0,
                "20,20,200,150", "20,210,200,150", "0,0,120,55");
        // topology is the only axis that engages the lane: the hub is lifted into the reserved
        // corridor between the two zones, which is why Zone B sits 95px further down than it does
        // on a plain column.
        assertGeometryMatchesBaseline("topology", null, null, 2, 220, 455, 1,
                "20,20,200,150", "20,305,200,150", "60,210,120,55");
        assertGeometryMatchesBaseline("topology", 2, null, 2, 460, 170, 0,
                "20,20,200,150", "260,20,200,150", "0,0,120,55");
        // A restricted call leaves everything it did not name exactly where it was.
        assertGeometryMatchesBaseline("column", null, List.of("obj-grp-a"), 1, 220, 170, 0,
                "20,20,200,150", "0,0,200,150", "0,0,120,55");
    }

    // ==================== a container the caller names explicitly ====================

    /**
     * A populated {@code Node} host named in {@code groupIds} must be arranged like any other
     * container, with its rectangle preserved.
     *
     * <p>The default predicate declines it deliberately — a host is not a zone — but a caller that
     * named the id has already overridden that default with information the tool does not have: it
     * can see the canvas, and it was told by {@code skippedContainers} which object was left
     * standing. Refusing it made that report end in an affordance nothing pointed at.</p>
     */
    @Test
    public void arrangeGroups_shouldArrangeAHostTheCallerNamed_whateverItsElementType() {
        useModel(unmovedHostFixture());
        IDiagramModelObject host = childNamed("Region");

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                List.of("obj-grp-a", "obj-grp-b", "obj-host"), null).entity();

        assertEquals("the named host is one of the containers this call arranged",
                3, result.groupsPositioned());
        assertEquals(20, host.getBounds().getX());
        assertEquals("the host takes the third column slot, 40px below Zone B's foot",
                400, host.getBounds().getY());
        assertEquals("width is preserved, as for every other target",
                200, host.getBounds().getWidth());
        assertEquals("height is preserved, as for every other target",
                150, host.getBounds().getHeight());

        MovedViewObjectDto reported = null;
        for (MovedViewObjectDto moved : result.positionedContainers()) {
            if ("obj-host".equals(moved.viewObjectId())) {
                reported = moved;
            }
        }
        assertNotNull("the host must be named in positionedContainers, with its landed rectangle: "
                + result.positionedContainers(), reported);
        assertEquals(400, reported.newY());
        assertEquals(150, reported.newHeight());
    }

    /**
     * The host's own children travel with it and keep their relative coordinates.
     *
     * <p>Nested coordinates are relative to the immediate parent, so moving the host is enough —
     * but "enough" is a claim about Archi's coordinate model, and the whole point of arranging a
     * host is that it carries a branch. A child rewritten to absolute coordinates would land
     * outside the box that moved.</p>
     */
    @Test
    public void arrangeGroups_shouldCarryTheHostsChildren_whenItIsNamedInGroupIds() {
        useModel(unmovedHostFixture());
        IDiagramModelObject inner = TopLevelGroupTargets.childrenOf(childNamed("Region")).get(0);

        accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                List.of("obj-grp-a", "obj-grp-b", "obj-host"), null);

        assertEquals("a nested child's x is relative to its parent and must not be rewritten",
                10, inner.getBounds().getX());
        assertEquals(10, inner.getBounds().getY());
    }

    /** No children guard: a named empty container is arranged and counted, like an empty group. */
    @Test
    public void arrangeGroups_shouldArrangeANamedEmptyHost_becauseTheDefaultCollectionHasNoChildrenGuard() {
        useModel(emptyHostFixture());

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                List.of("obj-grp-a", "obj-empty-host"), null).entity();

        assertEquals("an opt-in stricter than the rule it extends would need a reason nobody has",
                2, result.groupsPositioned());
        assertEquals(210, childNamed("Empty Region").getBounds().getY());
    }

    /** It was arranged, so it is not "left where it is". */
    @Test
    public void arrangeGroups_shouldNotAlsoReportANamedHost_asASkippedContainer() {
        useModel(unmovedHostFixture());

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                List.of("obj-grp-a", "obj-grp-b", "obj-host"), null).entity();

        assertTrue("a container this call positioned must not be described as left standing: "
                + result.skippedContainers(), result.skippedContainers().isEmpty());
    }

    /**
     * A host the caller did <em>not</em> name is still reported as skipped, with its reason intact.
     *
     * <p>The negative control for the assertion above: subtracting the positioned ids must remove
     * exactly what this call moved and nothing else.</p>
     */
    @Test
    public void arrangeGroups_shouldStillReportAHostTheCallerDidNotName_asASkippedContainer() {
        useModel(unmovedHostFixture());

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                List.of("obj-grp-a", "obj-grp-b"), null).entity();

        assertEquals(1, result.skippedContainers().size());
        assertEquals("obj-host", result.skippedContainers().get(0).viewObjectId());
    }

    /**
     * The standalone lane must not claim a container the arrangement just positioned.
     *
     * <p>The lane excludes arrangement targets by asking {@code isTarget}, and a per-call opt-in
     * does not change {@code isTarget}. So a named {@code Node} host wired to two arranged zones
     * was classified as a lane qualifier as well: it received an {@code UpdateViewObjectCommand}
     * from the arrangement <em>and</em> a second from the lane in the same compound, and because
     * the lane's is added later it silently won — the arrangement the caller asked for was
     * discarded, and the object was counted in two buckets at once.</p>
     *
     * <p>Measured on this fixture before the fix: {@code groupsPositioned 3 + lane 1 + skipped 0}
     * against a denominator of {@code 3}, with the host sitting in the lane corridor at y=210
     * rather than at an arrangement slot.</p>
     *
     * <p>Asserted end to end rather than on {@code classify}: the defect is that two commands reach
     * the same compound, which a unit test on the classifier cannot see.</p>
     */
    @Test
    public void arrangeGroups_shouldNotLetTheLaneClaimAContainerTheArrangementPositioned() {
        useModel(lanePlacedHostFixture());
        List<Command> dispatched = captureDispatchedCommands();

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null,
                40, List.of("obj-grp-a", "obj-grp-b", "obj-host"), null).entity();

        assertEquals("a container this call arranges is not also a loose element for the lane",
                0, result.standaloneElementsPlaced());
        assertEquals("exactly one command per arranged container, and none for the lane: "
                + dispatched.size() + " were built", 3, dispatched.size());

        List<Integer> ys = new ArrayList<>();
        for (IDiagramModelObject child : view().getChildren()) {
            ys.add(child.getBounds().getY());
        }
        java.util.Collections.sort(ys);
        assertEquals("all three containers must sit on the column's own slots, so the host landed "
                + "in an arrangement slot and not in the lane corridor. Actual: " + ys,
                List.of(20, 210, 400), ys);
        assertEquals("the buckets must still partition the view", result.topLevelObjects(),
                result.groupsPositioned() + result.standaloneElementsPlaced()
                        + result.skippedContainers().size() + result.unhandled().size());
    }

    /**
     * The lane still places a loose element the caller did not name — the negative control for the
     * exclusion above, so the new guard cannot pass by disabling the lane.
     */
    @Test
    public void arrangeGroups_shouldStillPlaceALoosePeerInTheLane_whenTheCallerDidNotNameIt() {
        useModel(laneEngagedFixture());

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null,
                40, null, null).entity();

        assertEquals("the lane must still run for an element that is not being arranged",
                1, result.standaloneElementsPlaced());
        assertEquals(210, childNamed("Hub").getBounds().getY());
    }

    /**
     * The new containment guard must be a no-op when every target came from the default
     * collection — which is exactly what the other {@code classify} call site feeds it.
     *
     * <p>This is the behavioural half of that claim: drive {@code classify} with a target list
     * built by {@link TopLevelGroupTargets#collect}, as {@code auto-layout-and-route} mode=grouped
     * does, and the lane must still classify the loose peer it always did. Every member of such a
     * list satisfies {@code isTarget}, so the pre-existing guard consumes them all and the new
     * line never fires. Deleting the new line would leave this green — that is the point; it is
     * the negative control that stops the guard silently narrowing the grouped path.
     */
    @Test
    public void classify_shouldStillQualifyALoosePeer_whenEveryTargetCameFromTheDefaultCollection() {
        IArchimateModel m = laneEngagedFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        List<IDiagramModelObject> defaultTargets = TopLevelGroupTargets.collect(v);
        List<ArrangeGroupsStandaloneLane.QualifyingStandaloneElement> qualifiers =
                ArrangeGroupsStandaloneLane.classify(v.getChildren(), defaultTargets);

        assertEquals("the two zones are the default targets on this fixture",
                2, defaultTargets.size());
        assertEquals("the hub must still qualify: it is not one of the targets, so neither guard "
                + "excludes it", 1, qualifiers.size());
        assertEquals("obj-hub", qualifiers.get(0).element().getId());
    }

    /**
     * And the wiring half: mode=grouped must keep building its target list through the shared
     * default predicate. The behavioural test above is only evidence about that call site while
     * this stays true — if the grouped path ever collected targets some other way, its list could
     * contain a non-target and the guard would start firing there.
     */
    @Test
    public void theGroupedLayoutCallSite_shouldFeedTheLaneOnlyDefaultTargets() {
        String source = readRepoFile(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java");

        assertTrue("mode=grouped must keep collecting its arrange targets through the shared "
                + "default predicate, which is what makes the lane's new guard a no-op there",
                source.contains(
                        "List<IDiagramModelObject> arrangeTargets = TopLevelGroupTargets.collect("));
    }

    // ============ which endpoints put the candidate in touch with an arranged container ============

    /**
     * An endpoint nested inside an arranged container puts the candidate in touch with it. The
     * long-standing case, kept as the control the other eight are read against.
     */
    @Test
    public void arrangeGroups_shouldCountAnElementInsideAZone_asReachingThatZone() {
        ArrangeGroupsResultDto result =
                arrangeTopology(candidateWiredInsideBothZonesFixture(), null);

        assertEquals("an endpoint inside each of two zones reaches two of them",
                1, result.standaloneElementsPlaced());
        assertNull("a placed candidate leaves the residual: " + result.unhandled(),
                unhandledReasonFor(result, "obj-dc"));
        assertBucketsPartitionTheView(candidateWiredInsideBothZonesFixture(), "topology", null, null);
    }

    /**
     * An endpoint that IS an arranged container counts, exactly as an endpoint inside it does.
     *
     * <p>The endpoint resolution ran every endpoint through the element-to-container map, which is
     * built from each container's children and never holds the container's own id. A connection
     * drawn to the box therefore resolved to nothing and contributed no zone, so a candidate
     * associated with two zones was counted at one and told it had reached fewer than two.</p>
     */
    @Test
    public void arrangeGroups_shouldCountTheZoneItself_whenTheConnectionTerminatesOnTheBox() {
        ArrangeGroupsResultDto result =
                arrangeTopology(candidateWiredToBothZoneBoxesFixture(), null);

        assertEquals("a connection to the zone box reaches the zone, as one to an element in it "
                + "does. Residual: " + result.unhandled(),
                1, result.standaloneElementsPlaced());
        assertNull("a placed candidate leaves the residual",
                unhandledReasonFor(result, "obj-dc"));
        assertBucketsPartitionTheView(candidateWiredToBothZoneBoxesFixture(), "topology", null, null);
    }

    /**
     * The measured shape, on a live technology view: a {@code CommunicationNetwork} wired to an
     * element inside one zone and to a {@code Node} host that this call arranges only because the
     * caller named it. The recipe prescribes exactly this — build each infrastructure node as a
     * container box, then associate the nodes to the network rather than drawing node-to-node
     * links — so the shape the guidance produces was the shape the predicate could not see.
     */
    @Test
    public void arrangeGroups_shouldCountANamedHost_whenTheConnectionTerminatesOnTheHostItself() {
        ArrangeGroupsResultDto result = arrangeTopology(candidateWiredToANamedHostBoxFixture(),
                List.of("obj-grp-a", "obj-host"));

        assertEquals("a host the caller arranged is a container the candidate can reach. "
                + "Residual: " + result.unhandled(),
                1, result.standaloneElementsPlaced());
        assertNull("a placed candidate leaves the residual",
                unhandledReasonFor(result, "obj-dc"));
        assertBucketsPartitionTheView(candidateWiredToANamedHostBoxFixture(), "topology", null,
                List.of("obj-grp-a", "obj-host"));
    }

    /**
     * A container this call filtered out is not arranged, so reaching it reaches no gap. The
     * candidate is wired into a zone the caller left out of {@code groupIds} and into one it kept.
     */
    @Test
    public void arrangeGroups_shouldNotCountAZoneTheCallerExcludedFromThisCall() {
        ArrangeGroupsResultDto result = arrangeTopology(candidateWiredIntoAnExcludedZoneFixture(),
                List.of("obj-grp-a", "obj-grp-b"));

        assertEquals("only the arranged containers count, and one of the two is not arranged",
                0, result.standaloneElementsPlaced());
        assertEquals("insufficient-connections",
                codeOf(unhandledReasonFor(result, "obj-dc")));
        assertBucketsPartitionTheView(candidateWiredIntoAnExcludedZoneFixture(), "topology", null,
                List.of("obj-grp-a", "obj-grp-b"));
    }

    /** An element inside a host this call did not name sits inside nothing this call arranged. */
    @Test
    public void arrangeGroups_shouldNotCountAnElementInsideAHostThisCallDidNotName() {
        ArrangeGroupsResultDto result =
                arrangeTopology(candidateWiredIntoAnUnnamedHostFixture(), null);

        assertEquals("a host left standing is not a container the lane can place between",
                0, result.standaloneElementsPlaced());
        assertEquals("insufficient-connections",
                codeOf(unhandledReasonFor(result, "obj-dc")));
        assertBucketsPartitionTheView(candidateWiredIntoAnUnnamedHostFixture(), "topology", null, null);
    }

    /** A loose top-level peer is in no container, so a connection to it reaches no zone. */
    @Test
    public void arrangeGroups_shouldNotCountALooseTopLevelPeer_whichSitsInNoContainer() {
        ArrangeGroupsResultDto result =
                arrangeTopology(candidateWiredToALoosePeerFixture(), null);

        assertEquals("a peer at the view's own top level is not a zone",
                0, result.standaloneElementsPlaced());
        assertEquals("insufficient-connections",
                codeOf(unhandledReasonFor(result, "obj-dc")));
        assertBucketsPartitionTheView(candidateWiredToALoosePeerFixture(), "topology", null, null);
    }

    /**
     * A connection that loops back to the candidate reaches nothing. The candidate is excluded
     * from the arranged set by construction, so its own id is never a container id — but that is
     * an argument, and this is the fixture that holds it to it.
     */
    @Test
    public void arrangeGroups_shouldNotCountTheCandidateItself_whenAConnectionLoopsBackToIt() {
        ArrangeGroupsResultDto result =
                arrangeTopology(candidateWiredToItselfFixture(), null);

        assertEquals("a self-loop adds no zone",
                0, result.standaloneElementsPlaced());
        assertEquals("insufficient-connections",
                codeOf(unhandledReasonFor(result, "obj-dc")));
        assertBucketsPartitionTheView(candidateWiredToItselfFixture(), "topology", null, null);
    }

    /**
     * A container nested inside an arranged one is a member of it, so an endpoint there counts for
     * the OUTERMOST arranged ancestor. Were the nested box's own id the key, it would not be in
     * the arranged set and the candidate would fall one short — which is what the placement here
     * rules out.
     */
    @Test
    public void arrangeGroups_shouldCountANestedZone_asItsOutermostArrangedAncestor() {
        ArrangeGroupsResultDto result =
                arrangeTopology(candidateWiredToANestedZoneFixture(), null);

        assertEquals("a nested container resolves to the zone that holds it. Residual: "
                + result.unhandled(),
                1, result.standaloneElementsPlaced());
        assertNull("a placed candidate leaves the residual",
                unhandledReasonFor(result, "obj-dc"));
        assertBucketsPartitionTheView(candidateWiredToANestedZoneFixture(), "topology", null, null);
    }

    /**
     * A note drawn inside a zone is inside the zone, and a connection to it reaches the zone. It
     * counts because the element-to-container map holds every child, notes included; asserted here
     * so the answer is a decision rather than a side effect of that recursion.
     */
    @Test
    public void arrangeGroups_shouldCountANoteInsideAZone_asReachingThatZone() {
        ArrangeGroupsResultDto result =
                arrangeTopology(candidateWiredToANoteInAZoneFixture(), null);

        assertEquals("a note inside a zone is inside the zone. Residual: " + result.unhandled(),
                1, result.standaloneElementsPlaced());
        assertNull("a placed candidate leaves the residual",
                unhandledReasonFor(result, "obj-dc"));
        assertBucketsPartitionTheView(candidateWiredToANoteInAZoneFixture(), "topology", null, null);
    }

    /**
     * Two routes to one zone are one zone. The candidate is wired to a zone's box and to an element
     * inside that same zone, which resolve to the same key — so it is still a near-miss, and the
     * caller is told to add a connection because it genuinely has only one.
     */
    @Test
    public void arrangeGroups_shouldCountOneZoneOnce_whenBothRoutesReachTheSameZone() {
        ArrangeGroupsResultDto result =
                arrangeTopology(candidateWiredToOneZoneByBothRoutesFixture(), null);

        assertEquals("the box and an element inside it are one container, not two",
                0, result.standaloneElementsPlaced());
        assertEquals("insufficient-connections",
                codeOf(unhandledReasonFor(result, "obj-dc")));
        assertBucketsPartitionTheView(candidateWiredToOneZoneByBothRoutesFixture(),
                "topology", null, null);
    }

    /**
     * The grouped layout pass builds its target list and its element map the way this test does,
     * and hands both to the same classifier — so the widened endpoint resolution reaches it with
     * no code of its own. Driven at the classifier because the grouped pass itself runs a real
     * routing and assessment pass that needs a display; its end-to-end half lives beside the other
     * display-lane pins in {@code TopLevelGroupingSiblingLayoutToolsTest}.
     */
    @Test
    public void classify_shouldCountTheZoneItself_whenFedTheGroupedPassesArguments() {
        IArchimateModel m = candidateWiredToBothZoneBoxesFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        List<IDiagramModelObject> defaultTargets = TopLevelGroupTargets.collect(v);
        List<ArrangeGroupsStandaloneLane.QualifyingStandaloneElement> qualifiers =
                ArrangeGroupsStandaloneLane.classify(
                        v.getChildren(), defaultTargets);

        assertEquals("the two zones are the default targets on this fixture",
                2, defaultTargets.size());
        assertEquals("the candidate reaches both zone boxes and must qualify on this call site too",
                1, qualifiers.size());
        assertEquals("obj-dc", qualifiers.get(0).element().getId());
    }

    /**
     * Every id a qualifier carries is an id of a group the same call is arranging, so the gap
     * assignment always has an index for it and can never drop a qualified element for want of one.
     *
     * <p>That is what makes the defensive {@code continue} in the gap assignment unreachable: the
     * endpoint resolution only ever contributes an id it has just tested against the arranged set,
     * and both call sites pass the very list they classified against. The branch is kept as a
     * guard, not published as an outcome — a reason code for a state nothing can enter would be a
     * wire contract for a case a caller can never be in.</p>
     */
    @Test
    public void classify_shouldOnlyEverConnectAQualifierToGroupsTheSameCallIsArranging() {
        List<IArchimateModel> fixtures = List.of(
                candidateWiredInsideBothZonesFixture(),
                candidateWiredToBothZoneBoxesFixture(),
                candidateWiredToANestedZoneFixture(),
                candidateWiredToANoteInAZoneFixture(),
                candidateWiredToOneZoneByBothRoutesFixture());
        int qualifiersSeen = 0;
        for (IArchimateModel m : fixtures) {
            IArchimateDiagramModel v = firstViewOf(m);
            List<IDiagramModelObject> targets = TopLevelGroupTargets.collect(v);
            List<String> arrangedIds = new ArrayList<>();
            for (IDiagramModelObject g : targets) {
                arrangedIds.add(g.getId());
            }

            for (ArrangeGroupsStandaloneLane.QualifyingStandaloneElement q
                    : ArrangeGroupsStandaloneLane.classify(
                            v.getChildren(), targets)) {
                qualifiersSeen++;
                for (String gid : q.connectedTargetGroupIds()) {
                    assertTrue("a qualifier may only carry ids of groups this call is arranging, "
                            + "or the gap assignment has no index for one of them: " + gid
                            + " not in " + arrangedIds,
                            arrangedIds.contains(gid));
                }
            }
        }
        assertTrue("the invariant must be asserted over qualifiers that exist, not over none",
                qualifiersSeen >= 4);
    }

    /**
     * With one container arranged there is no inter-container gap, so no number of connections
     * would place the element. Telling that caller it reached fewer than two containers invites a
     * connection that changes nothing, which is why this outcome carries its own code and is
     * decided before the connection count.
     */
    @Test
    public void arrangeGroups_shouldSayThereIsNoGap_whenOnlyOneContainerWasArranged() {
        ArrangeGroupsResultDto result = arrangeTopology(oneZoneAndACandidateFixture(), null);

        assertEquals("one container offers no gap to place anything in",
                0, result.standaloneElementsPlaced());
        String reason = unhandledReasonFor(result, "obj-dc");
        assertEquals("the absent gap outranks the connection count: the candidate does reach one "
                + "container, and reaching a second would still leave it unplaced. Actual: "
                + reason,
                "no-inter-container-gap", codeOf(reason));
        assertTrue("a reason longer than 90 characters is prose, and prose repeated per entry is "
                + "what floods the field: " + reason.length() + " chars",
                reason.length() <= 90);
        assertTrue("every reason must lead with a stable code: " + reason,
                reason.matches("^[a-z][a-z-]+: .+"));
        assertBucketsPartitionTheView(oneZoneAndACandidateFixture(), "topology", null, null);
    }

    /**
     * The same one-container shape reached by narrowing the call rather than by drawing a
     * single-zone view: the count that decides this is what this call arranged, not what the view
     * holds.
     */
    @Test
    public void arrangeGroups_shouldSayThereIsNoGap_whenGroupIdsNarrowedTheCallToOneContainer() {
        ArrangeGroupsResultDto result =
                arrangeTopology(candidateWiredInsideBothZonesFixture(), List.of("obj-grp-a"));

        assertEquals("no gap exists on a call that arranges one container",
                0, result.standaloneElementsPlaced());
        assertEquals("no-inter-container-gap",
                codeOf(unhandledReasonFor(result, "obj-dc")));
        assertBucketsPartitionTheView(candidateWiredInsideBothZonesFixture(), "topology", null,
                List.of("obj-grp-a"));
    }

    /**
     * Every code the response can emit is in the published enumeration, so a code added as a bare
     * literal beside the others cannot slip past the surface-parity guard that iterates it.
     */
    @Test
    public void arrangeGroups_shouldEmitNoReasonCodeOutsideThePublishedEnumeration() {
        List<IArchimateModel> fixtures = List.of(
                fallThroughFixture(), laneEngagedFixture(), oneZoneAndACandidateFixture(),
                nonArchimateFixture(), candidateWiredToALoosePeerFixture());
        for (IArchimateModel fixture : fixtures) {
            for (String arrangement : List.of("topology", "column")) {
                useModel(fixture);
                ArrangeGroupsResultDto result = accessor.arrangeGroups(
                        SESSION, VIEW_ID, arrangement, null, 40, null, null).entity();
                for (SkippedContainerDto entry : result.unhandled()) {
                    assertTrue("this code was emitted without going through the shared "
                            + "enumeration, so the guard that checks the served description "
                            + "against that enumeration cannot see it: " + entry.reason(),
                            SkippedContainerDto.ARRANGE_GROUPS_UNHANDLED_REASON_CODES
                                    .contains(codeOf(entry.reason())));
                }
            }
        }
    }

    // ==================== the refusals ====================

    @Test
    public void arrangeGroups_shouldSayUnknownId_whenNothingInTheModelCarriesIt() {
        useModel(unmovedHostFixture());

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                    List.of("no-such-id"), null);
            fail("an id that names nothing must be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
            assertTrue("it must read as an unknown id, not as an object missing from this view. "
                    + "Actual: " + e.getMessage(), e.getMessage().contains("Unknown id"));
            assertTrue("and say where a valid id is read from. Actual: " + e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("get-view-contents"));
        }
    }

    @Test
    public void arrangeGroups_shouldSayItBelongsToAnotherView_whenTheIdNamesOneOfItsChildren() {
        useModel(twoViewFixture());

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                    List.of("obj-other-zone"), null);
            fail("a container on a different view is not this view's to arrange");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("the message must name the view it does belong to, so the caller can call "
                    + "that one. Actual: " + e.getMessage(),
                    e.getMessage().contains("view-other") && e.getMessage().contains(VIEW_ID));
        }
    }

    @Test
    public void arrangeGroups_shouldSayItIsAConcept_whenTheIdNamesTheModelElementNotTheViewObject() {
        useModel(unmovedHostFixture());

        try {
            // "host" is the Node concept; "obj-host" is the view object drawn from it.
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, List.of("host"), null);
            fail("a model concept id is not a view-object id");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("telling a concept id it belongs to another view would be false. Actual: "
                    + e.getMessage(), e.getMessage().contains("model concept"));
            assertTrue("Actual: " + e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("view-object id"));
        }
    }

    @Test
    public void arrangeGroups_shouldSayNotAContainer_whenGroupIdsNamesATopLevelNote() {
        useModel(noteFixture());

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, List.of("note-1"), null);
            fail("a note holds no children and is never arranged");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("Actual: " + e.getMessage(), e.getMessage().contains("not a container"));
            assertTrue("the remedy must be the tool that can actually move it. Actual: "
                    + e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("apply-positions")
                            && e.getSuggestedCorrection().contains("update-view-object"));
        }
    }

    /**
     * The nested case keeps today's message and gains the remedy the other three carry.
     * {@link #shouldSayNotTopLevel_whenGroupIdsNamesANestedGrouping} pins the message itself.
     */
    @Test
    public void arrangeGroups_shouldNameTheAncestor_whenGroupIdsNamesANestedContainer() {
        useModel(nestedGroupingFixture());

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                    List.of("obj-grp-inner"), null);
            fail("a nested container is not top-level");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("Actual: " + e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("layout-within-group"));
        }
    }

    /**
     * The nested branch is reached by ANY non-direct-child, not only by a container — so its
     * remedy must not assume the named object has children of its own.
     *
     * <p>A note nested inside a container is the cheapest object that proves it. Telling that note
     * to "lay out its children with layout-within-group" would be a false statement about an
     * object that structurally cannot have any, and the tool would be advertising a call that
     * cannot work. The remedy therefore points {@code layout-within-group} at the <em>ancestor</em>,
     * which is true whether or not the named object holds anything.
     */
    @Test
    public void arrangeGroups_shouldNotTellANestedLeafToLayOutItsOwnChildren() {
        useModel(nestedNoteFixture());

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                    List.of("note-nested"), null);
            fail("an object nested inside a container is not top-level");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            String remedy = e.getSuggestedCorrection();
            assertFalse("a note has no children, so this remedy would be a false statement about "
                    + "the object it is handed to. Actual: " + remedy,
                    remedy.contains("its children"));
            assertTrue("the remedy must point at the container it sits in, which is true whatever "
                    + "the named object is. Actual: " + remedy,
                    remedy.contains("outermost container it sits in"));
        }
    }

    /**
     * The view's own id is the mistake a caller makes when it confuses {@code viewId} with
     * {@code groupIds}. A view is an {@code IDiagramModelComponent} but never an
     * {@code IDiagramModelObject}, so a single view-object test answers it "that is a model
     * concept, pass the view-object id" — false twice over, and unactionable, because the view has
     * no view-object id to pass.
     */
    @Test
    public void arrangeGroups_shouldSayItIsTheView_whenGroupIdsNamesTheViewItself() {
        useModel(unmovedHostFixture());

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, List.of(VIEW_ID), null);
            fail("the view is not one of its own children");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("it must be named for what it is, not called a model concept. Actual: "
                    + e.getMessage(), e.getMessage().contains("is a view"));
            assertTrue("Actual: " + e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("viewId"));
        }
    }

    /**
     * A connection reaches {@code IDiagramModelComponent} through {@code IConnectable}, so it is
     * genuinely drawn on this view — calling it a model concept is false, and telling it to pass
     * "the view-object id instead" names nothing it could pass.
     */
    @Test
    public void arrangeGroups_shouldSayItIsAConnection_whenGroupIdsNamesOne() {
        useModel(laneEngagedFixture());

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                    List.of("conn-hub-a"), null);
            fail("a connection is not a container");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("a connection IS drawn on this view, so the concept answer would be false. "
                    + "Actual: " + e.getMessage(), e.getMessage().contains("is a connection"));
            assertTrue("the remedy must name a tool that can actually move it. Actual: "
                    + e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("auto-route-connections"));
        }
    }

    /**
     * The empty-target refusal must stop advising the caller to add a container when the
     * view already holds one it can now name.
     */
    @Test
    public void arrangeGroups_shouldOfferGroupIds_whenTheViewHoldsOnlyHosts() {
        useModel(hostOnlyFixture());

        try {
            accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40, null, null);
            fail("a view with no default targets still has nothing to arrange by default");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("'add a container first' is false advice on a view that holds one. Actual: "
                    + e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("groupIds"));
        }
    }

    /** And the precondition itself: naming the host means the branch is never reached. */
    @Test
    public void arrangeGroups_shouldNotRefuseAHostOnlyView_whenTheCallerNamesTheHost() {
        useModel(hostOnlyFixture());

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                List.of("obj-host"), null).entity();

        assertEquals(1, result.groupsPositioned());
    }

    // ==================== every path reports it ====================

    @Test
    public void arrangeGroups_shouldCountANamedHost_onAQueuedCall() {
        useModel(unmovedHostFixture());
        dispatcher.beginBatch(SESSION, "arrange", null);

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                List.of("obj-grp-a", "obj-grp-b", "obj-host"), null).entity();

        assertEquals("the report is decided from the view as read, so it is honest while queued",
                3, result.groupsPositioned());
        assertTrue("nothing is effective behind the batch, so no geometry may be claimed",
                result.positionedContainers() == null || result.positionedContainers().isEmpty());
        assertEquals("and the host has not moved yet", 0, childNamed("Region").getBounds().getY());
    }

    @Test
    public void arrangeGroups_shouldCountANamedHost_onAProposedCall() {
        useModel(unmovedHostFixture());
        dispatcher.setApprovalModeProvider(() -> true);

        ArrangeGroupsResultDto result = accessor.arrangeGroups(SESSION, VIEW_ID, "column", null, 40,
                List.of("obj-grp-a", "obj-grp-b", "obj-host"), null).entity();

        assertEquals(3, result.groupsPositioned());
        assertTrue(result.positionedContainers() == null
                || result.positionedContainers().isEmpty());
        assertEquals(0, childNamed("Region").getBounds().getY());
    }

    // ---- helpers for the reconciliation assertions ----

    private void assertReconciles(String shape, IArchimateModel fixture,
            String arrangement, Integer columns, List<String> groupIds) {
        useModel(fixture);
        ArrangeGroupsResultDto r = accessor.arrangeGroups(
                SESSION, VIEW_ID, arrangement, columns, 40, groupIds, null).entity();
        assertEquals("the buckets must sum to the denominator on a view holding " + shape,
                r.topLevelObjects(),
                r.groupsPositioned() + r.standaloneElementsPlaced()
                        + r.skippedContainers().size() + r.unhandled().size());
    }

    private void assertBucketsPartitionTheView(IArchimateModel fixture,
            String arrangement, Integer columns, List<String> groupIds) {
        useModel(fixture);
        List<String> childIds = new ArrayList<>();
        for (IDiagramModelObject child : view().getChildren()) {
            childIds.add(child.getId());
        }
        ArrangeGroupsResultDto r = accessor.arrangeGroups(
                SESSION, VIEW_ID, arrangement, columns, 40, groupIds, null).entity();

        List<String> claimed = new ArrayList<>();
        for (MovedViewObjectDto moved : r.positionedContainers()) {
            claimed.add(moved.viewObjectId());
        }
        for (SkippedContainerDto entry : r.skippedContainers()) {
            claimed.add(entry.viewObjectId());
        }
        for (SkippedContainerDto entry : r.unhandled()) {
            claimed.add(entry.viewObjectId());
        }
        // The lane reports a count rather than ids, so the placed elements are identified as the
        // children no other bucket named. That is exactly what makes the size check below load-
        // bearing: a double claim shows up as a duplicate, a drift as a stranger.
        assertEquals("no direct child may be claimed by two buckets: " + claimed,
                claimed.size(), new java.util.HashSet<>(claimed).size());
        assertTrue("every claimed id must be a direct child of the view: " + claimed,
                childIds.containsAll(claimed));
        assertEquals("the buckets together must cover every direct child of the view",
                childIds.size(), claimed.size() + r.standaloneElementsPlaced());
    }

    /**
     * Drives one arrangement and compares every reported value and every rectangle against numbers
     * recorded from the pre-change implementation.
     *
     * @param expectedBounds one {@code "x,y,width,height"} per top-level object, in view child order
     */
    private void assertGeometryMatchesBaseline(String arrangement, Integer columns,
            List<String> groupIds, int expectedPositioned, int expectedWidth, int expectedHeight,
            int expectedStandalone, String... expectedBounds) {
        useModel(laneEngagedFixture());
        String where = arrangement + (columns != null ? "+columns=" + columns : "")
                + (groupIds != null ? " restricted" : "");

        ArrangeGroupsResultDto r = accessor.arrangeGroups(
                SESSION, VIEW_ID, arrangement, columns, 40, groupIds, null).entity();

        assertEquals(where + ": groupsPositioned", expectedPositioned, r.groupsPositioned());
        assertEquals(where + ": layoutWidth", expectedWidth, r.layoutWidth());
        assertEquals(where + ": layoutHeight", expectedHeight, r.layoutHeight());
        assertEquals(where + ": resolvedSpacing", Integer.valueOf(40), r.resolvedSpacing());
        assertEquals(where + ": standaloneElementsPlaced",
                expectedStandalone, r.standaloneElementsPlaced());

        List<IDiagramModelObject> children = view().getChildren();
        assertEquals(where + ": the fixture must hold the objects the baseline was taken from",
                expectedBounds.length, children.size());
        for (int i = 0; i < children.size(); i++) {
            IDiagramModelObject child = children.get(i);
            com.archimatetool.model.IBounds b = child.getBounds();
            String actual = b.getX() + "," + b.getY() + "," + b.getWidth() + "," + b.getHeight();
            assertEquals(where + ": " + child.getId() + " moved from where the released code put it",
                    expectedBounds[i], actual);
        }
    }

    /** The stable code a reason leads with — the half a caller filters on. */
    private String codeOf(String reason) {
        assertNotNull("no reason was reported for that object", reason);
        int colon = reason.indexOf(':');
        assertTrue("a reason must lead with a code: " + reason, colon > 0);
        return reason.substring(0, colon);
    }

    private SkippedContainerDto onlyUnhandled(ArrangeGroupsResultDto result) {
        assertEquals("exactly one object should have fallen through on this fixture: "
                + result.unhandled(), 1, result.unhandled().size());
        return result.unhandled().get(0);
    }

    /** Runs the axis that engages the lane, on a fixture already wired for the case under test. */
    private ArrangeGroupsResultDto arrangeTopology(IArchimateModel m, List<String> groupIds) {
        useModel(m);
        return accessor.arrangeGroups(SESSION, VIEW_ID, "topology", null, 40, groupIds, null)
                .entity();
    }

    /** The reason reported for one view object, or null when nothing in the residual names it. */
    private String unhandledReasonFor(ArrangeGroupsResultDto result, String viewObjectId) {
        for (SkippedContainerDto entry : result.unhandled()) {
            if (viewObjectId.equals(entry.viewObjectId())) {
                return entry.reason();
            }
        }
        return null;
    }


    private static String readRepoFile(String relative) {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            java.nio.file.Path candidate = dir.resolve(relative);
            if (java.nio.file.Files.exists(candidate)) {
                try {
                    return java.nio.file.Files.readString(
                            candidate, java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AssertionError("Could not read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " from "
                + java.nio.file.Path.of("").toAbsolutePath());
    }

    // ==================== fixtures ====================

    private void useModel(IArchimateModel m) {
        this.model = m;
        stubModelManager.setModels(List.of(m));
        accessor = createAccessorWithTestDispatcher(m);
    }

    private IArchimateDiagramModel view() {
        return firstViewOf(model);
    }

    /** The view a fixture already created. {@link #viewOf} ADDS one; this one finds it. */
    private IArchimateDiagramModel firstViewOf(IArchimateModel m) {
        return (IArchimateDiagramModel) m.getFolder(FolderType.DIAGRAMS).getElements().get(0);
    }

    private IDiagramModelObject childNamed(String name) {
        for (IDiagramModelObject child : view().getChildren()) {
            if (name.equals(child.getName())) {
                return child;
            }
        }
        throw new AssertionError("no top-level child named " + name);
    }

    /** Two Grouping elements and one native group, each holding one node. */
    private IArchimateModel mixedFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addGroupingElement(m, v, "Zone A", "grp-a");
        addGroupingElement(m, v, "Zone B", "grp-b");
        addNativeGroup(m, v, "Native Group", "nat-1");
        return m;
    }

    /** Two Grouping elements, no native group — the shape the deployment guidance produces. */
    private IArchimateModel groupingOnlyFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addGroupingElement(m, v, "Zone A", "grp-a");
        addGroupingElement(m, v, "Zone B", "grp-b");
        return m;
    }

    /**
     * As {@link #groupingOnlyFixture}, plus a connection between the two zones' hosts. A plain
     * diagram connection rather than one wrapping an ArchiMate relationship: relationship
     * validation calls into Archi's static matrix, which needs an OSGi context this fixture does
     * not have, and the collection under test reads only the view's connection graph.
     */
    private IArchimateModel connectedGroupingOnlyFixture() {
        IArchimateModel m = groupingOnlyFixture();
        IArchimateDiagramModel v = (IArchimateDiagramModel)
                m.getFolder(FolderType.DIAGRAMS).getElements().get(0);

        IDiagramModelConnection conn =
                IArchimateFactory.eINSTANCE.createDiagramModelConnection();
        conn.setId("conn-a-b");
        conn.connect(hostOf(v, "Zone A"), hostOf(v, "Zone B"));
        return m;
    }

    /** The single node child of the named top-level zone. */
    private IDiagramModelObject hostOf(IArchimateDiagramModel v, String zoneName) {
        for (IDiagramModelObject child : v.getChildren()) {
            if (zoneName.equals(child.getName())) {
                return ((com.archimatetool.model.IDiagramModelContainer) child)
                        .getChildren().get(0);
            }
        }
        throw new AssertionError("no top-level zone named " + zoneName);
    }

    /** Two native groups — the pre-existing shape, for the negative control. */
    private IArchimateModel nativeOnlyFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addNativeGroup(m, v, "Native One", "nat-1");
        addNativeGroup(m, v, "Native Two", "nat-2");
        return m;
    }

    /**
     * One of every population that used to fall between the buckets, on one view: two arranged
     * zones, a populated host that is skipped, a note, a childless lane-eligible element and a
     * childless element of a type the lane never places.
     */
    private IArchimateModel fallThroughFixture() {
        IArchimateModel m = mixedFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        addPopulatedHost(m, v, "Region", "host");
        addNote(v, "A caption", "note-1");
        addLooseNode(m, v, "Edge Gateway", "gw");
        addLooseComponent(m, v, "Billing", "billing");
        return m;
    }

    /** Two zones plus a note. */
    private IArchimateModel noteFixture() {
        IArchimateModel m = groupingOnlyFixture();
        addNote(firstViewOf(m), "A caption", "note-1");
        return m;
    }

    /** Two zones plus all three objects Archi draws that carry no ArchiMate concept. */
    private IArchimateModel nonArchimateFixture() {
        IArchimateModel m = groupingOnlyFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        addNote(v, "A caption", "note-1");

        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        com.archimatetool.model.IDiagramModelImage image = f.createDiagramModelImage();
        image.setId("image-1");
        image.setBounds(0, 0, 120, 80);
        v.getChildren().add(image);

        com.archimatetool.model.IDiagramModelReference ref = f.createDiagramModelReference();
        ref.setId("ref-1");
        ref.setName("Another View");
        ref.setBounds(0, 0, 120, 55);
        v.getChildren().add(ref);
        return m;
    }

    /**
     * A populated host that the lane does NOT place — the ordinary skipped-container case, driven
     * through the tool rather than through the collector.
     */
    private IArchimateModel unmovedHostFixture() {
        IArchimateModel m = groupingOnlyFixture();
        addPopulatedHost(m, firstViewOf(m), "Region", "host");
        return m;
    }

    /** Two zones, with a note nested inside the first — a non-direct-child that has no children. */
    private IArchimateModel nestedNoteFixture() {
        IArchimateModel m = groupingOnlyFixture();
        IDiagramModelObject zoneA = null;
        for (IDiagramModelObject child : firstViewOf(m).getChildren()) {
            if ("Zone A".equals(child.getName())) {
                zoneA = child;
            }
        }
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setId("note-nested");
        note.setContent("A caption inside the zone");
        note.setBounds(10, 80, 120, 40);
        ((com.archimatetool.model.IDiagramModelContainer) zoneA).getChildren().add(note);
        return m;
    }

    /** One zone plus a top-level host that holds nothing — the no-children-guard case. */
    private IArchimateModel emptyHostFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addGroupingElement(m, v, "Zone A", "grp-a");
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode concept = f.createNode();
        concept.setId("empty-host");
        concept.setName("Empty Region");
        m.getFolder(FolderType.TECHNOLOGY).getElements().add(concept);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-empty-host");
        obj.setArchimateConcept(concept);
        obj.setBounds(0, 0, 200, 150);
        v.getChildren().add(obj);
        return m;
    }

    /** A view whose only top-level container is a host — no default target at all. */
    private IArchimateModel hostOnlyFixture() {
        IArchimateModel m = emptyModel();
        addPopulatedHost(m, viewOf(m), "Region", "host");
        return m;
    }

    /** Two views, so an id can be valid, current, and still not this view's to arrange. */
    private IArchimateModel twoViewFixture() {
        IArchimateModel m = groupingOnlyFixture();
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateDiagramModel other = f.createArchimateDiagramModel();
        other.setId("view-other");
        other.setName("Other View");
        m.getFolder(FolderType.DIAGRAMS).getElements().add(other);

        IGrouping concept = f.createGrouping();
        concept.setId("other-zone");
        concept.setName("Other Zone");
        m.getFolder(FolderType.OTHER).getElements().add(concept);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-other-zone");
        obj.setArchimateConcept(concept);
        obj.setBounds(0, 0, 200, 150);
        other.getChildren().add(obj);
        return m;
    }

    /** Two zones plus a childless element of a type the standalone lane never places. */
    private IArchimateModel plainElementFixture() {
        IArchimateModel m = groupingOnlyFixture();
        addLooseComponent(m, firstViewOf(m), "Billing", "billing");
        return m;
    }

    /** Two zones plus a childless lane-eligible element with no connections at all. */
    private IArchimateModel laneEligibleFixture() {
        IArchimateModel m = groupingOnlyFixture();
        addLooseNode(m, firstViewOf(m), "Edge Gateway", "gw");
        return m;
    }

    /** Two zones plus a lane-eligible element wired to one zone only — below the threshold. */
    private IArchimateModel oneConnectionFixture() {
        IArchimateModel m = groupingOnlyFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        IDiagramModelObject gw = addLooseNode(m, v, "Edge Gateway", "gw");
        connect(gw, hostOf(v, "Zone A"), "conn-gw-a");
        return m;
    }

    /** Two zones plus a lane-eligible element wired to both — the lane places it. */
    private IArchimateModel laneEngagedFixture() {
        IArchimateModel m = groupingOnlyFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        IDiagramModelObject hub = addLooseNode(m, v, "Hub", "hub");
        connect(hub, hostOf(v, "Zone A"), "conn-hub-a");
        connect(hub, hostOf(v, "Zone B"), "conn-hub-b");
        return m;
    }

    /**
     * As {@link #laneEngagedFixture}, but the wired element HOLDS CHILDREN. It therefore satisfies
     * the skipped-container predicate and the lane's predicate at the same time.
     */
    private IArchimateModel lanePlacedHostFixture() {
        IArchimateModel m = groupingOnlyFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        IDiagramModelObject host = addPopulatedHost(m, v, "Region", "host");
        connect(host, hostOf(v, "Zone A"), "conn-host-a");
        connect(host, hostOf(v, "Zone B"), "conn-host-b");
        return m;
    }

    /** One populated zone and one empty one — an empty target is still arranged. */
    private IArchimateModel emptyContainerFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addGroupingElement(m, v, "Zone A", "grp-a");
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IGrouping concept = f.createGrouping();
        concept.setId("grp-empty");
        concept.setName("Empty Zone");
        m.getFolder(FolderType.OTHER).getElements().add(concept);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-grp-empty");
        obj.setArchimateConcept(concept);
        obj.setBounds(0, 0, 200, 150);
        v.getChildren().add(obj);
        return m;
    }

    /** A top-level Node that holds one child — container-shaped, but not an arrangement target. */
    private IDiagramModelArchimateObject addPopulatedHost(
            IArchimateModel m, IArchimateDiagramModel v, String name, String id) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode concept = f.createNode();
        concept.setId(id);
        concept.setName(name);
        m.getFolder(FolderType.TECHNOLOGY).getElements().add(concept);

        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(concept);
        obj.setBounds(0, 0, 200, 150);
        v.getChildren().add(obj);
        addNodeChild(m, obj, name + " Instance", id + "-inner");
        return obj;
    }

    /** A childless top-level Node — lane-eligible by type. */
    private IDiagramModelArchimateObject addLooseNode(
            IArchimateModel m, IArchimateDiagramModel v, String name, String id) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode concept = f.createNode();
        concept.setId(id);
        concept.setName(name);
        m.getFolder(FolderType.TECHNOLOGY).getElements().add(concept);

        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(concept);
        obj.setBounds(0, 0, 120, 55);
        v.getChildren().add(obj);
        return obj;
    }

    /** A childless top-level element of a type the standalone lane never places. */
    private IDiagramModelArchimateObject addLooseComponent(
            IArchimateModel m, IArchimateDiagramModel v, String name, String id) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IApplicationComponent concept = f.createApplicationComponent();
        concept.setId(id);
        concept.setName(name);
        m.getFolder(FolderType.APPLICATION).getElements().add(concept);

        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(concept);
        obj.setBounds(0, 0, 120, 55);
        v.getChildren().add(obj);
        return obj;
    }

    private IDiagramModelNote addNote(IArchimateDiagramModel v, String text, String id) {
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setId(id);
        note.setContent(text);
        note.setBounds(0, 0, 185, 80);
        v.getChildren().add(note);
        return note;
    }

    /**
     * A plain diagram connection rather than one wrapping an ArchiMate relationship: relationship
     * validation calls into Archi's static matrix, which needs an OSGi context these fixtures do
     * not have, and the lane reads only the view's connection graph.
     */
    private void connect(IDiagramModelObject source, IDiagramModelObject target, String id) {
        IDiagramModelConnection conn =
                IArchimateFactory.eINSTANCE.createDiagramModelConnection();
        conn.setId(id);
        conn.connect(source, target);
    }

    /** One top-level Grouping that itself contains a Grouping. */
    // ---- zones nested inside a NON-target host: the shape the two container walks disagree on ----

    /**
     * A {@code Node} typing a cloud region, holding two {@code Grouping} zones with one element
     * each and one connection running between those elements.
     *
     * <p>This is the shape where the view's direct children and the outermost qualifying
     * containers are different sets: the view's only child is the host, which is not a target, so
     * a direct-children walk answers "no containers" while an outermost walk answers "two". Every
     * label is short enough that auto-sizing returns its default without measuring text, which is
     * what keeps the fixture in the display-less lane.</p>
     */
    private IArchimateModel hostedZonesFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelArchimateObject host = addHostShell(m, v, "Cloud Region", "host-cr");
        IDiagramModelArchimateObject zoneA = addNestedZone(m, host, "Zone A", "grp-a", 20, 20);
        IDiagramModelArchimateObject zoneB = addNestedZone(m, host, "Zone B", "grp-b", 20, 200);
        connect(childOf(zoneA), childOf(zoneB), "conn-a-b");
        return m;
    }

    /**
     * As {@link #hostedZonesFixture}, with a single connection drawn between the two zone BOXES
     * rather than between the elements inside them. Separates the two cases the not-connected
     * sentence talks about.
     */
    private IArchimateModel hostedZonesBoxToBoxFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelArchimateObject host = addHostShell(m, v, "Cloud Region", "host-cr");
        IDiagramModelArchimateObject zoneA = addNestedZone(m, host, "Zone A", "grp-a", 20, 20);
        IDiagramModelArchimateObject zoneB = addNestedZone(m, host, "Zone B", "grp-b", 20, 200);
        connect(zoneA, zoneB, "conn-box-box");
        return m;
    }

    /**
     * Two top-level zones with one element each and one element-to-element connection — the same
     * connectivity as {@link #hostedZonesFixture} at the view's own top level. The control that
     * says whether a measurement belongs to the nesting or to the wiring.
     */
    private IArchimateModel twoTopLevelZonesWiredInsideFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelArchimateObject zoneA = addGroupingElement(m, v, "Zone A", "grp-a");
        IDiagramModelArchimateObject zoneB = addGroupingElement(m, v, "Zone B", "grp-b");
        connect(childOf(zoneA), childOf(zoneB), "conn-a-b");
        return m;
    }

    /** Two top-level zones joined by a single connection between the zone BOXES themselves. */
    private IArchimateModel twoTopLevelZonesWiredBoxToBoxFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelArchimateObject zoneA = addGroupingElement(m, v, "Zone A", "grp-a");
        IDiagramModelArchimateObject zoneB = addGroupingElement(m, v, "Zone B", "grp-b");
        connect(zoneA, zoneB, "conn-box-box");
        return m;
    }

    /**
     * A {@code Grouping} zone drawn inside a NATIVE VIEW GROUP. A native group is itself a target,
     * so the zone is nested-inside-a-target and both walks must answer with the outer group — the
     * control that keeps the widening about DEPTH past a non-target and not about depth as such.
     */
    private IArchimateModel zoneInsideNativeGroupFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelGroup outer = addNativeGroup(m, v, "Outer", "nat-outer");
        addNestedZone(m, outer, "Zone A", "grp-a", 20, 20);
        return m;
    }

    /**
     * The hosted-zones shape plus a loose lane-eligible element at the view's own top level, so
     * the unhandled bucket has something to describe on a call that arranged nothing on the canvas.
     */
    private IArchimateModel hostedZonesAndALoosePeerFixture() {
        IArchimateModel m = hostedZonesFixture();
        addLooseNode(m, firstViewOf(m), "Peer", "peer");
        return m;
    }

    /** A {@code Node} host inside another {@code Node} host, with one zone at the bottom. */
    private IArchimateModel hostInsideHostFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelArchimateObject outer = addHostShell(m, v, "Outer Host", "host-outer");
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode innerConcept = f.createNode();
        innerConcept.setId("host-inner");
        innerConcept.setName("Inner Host");
        m.getFolder(FolderType.TECHNOLOGY).getElements().add(innerConcept);
        IDiagramModelArchimateObject inner = f.createDiagramModelArchimateObject();
        inner.setId("obj-host-inner");
        inner.setArchimateConcept(innerConcept);
        inner.setBounds(10, 10, 340, 380);
        outer.getChildren().add(inner);
        addNestedZone(m, inner, "Zone A", "grp-a", 20, 20);
        return m;
    }

    /**
     * One populated zone drawn ON the canvas, plus a host holding two wired zones — the shape
     * where the gate's frame and the positioning step's frame are different and BOTH are
     * non-empty, so nothing throws and nothing short-circuits on its own.
     *
     * <p>The canvas zone sits well clear of the host so the two frames' gaps are distinguishable:
     * 30px between the hosted zones, 80px between the canvas zone and the host. A number read
     * from the wrong frame is therefore visible as a number, not merely as a wrong frame.</p>
     */
    private IArchimateModel mixedCanvasAndHostedFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addNestedZone(m, v, "Canvas Zone", "grp-canvas", 20, 20);
        IDiagramModelArchimateObject host = addHostShell(m, v, "Cloud Region", "host-cr");
        IDiagramModelArchimateObject zoneA = addNestedZone(m, host, "Zone A", "grp-a", 20, 20);
        IDiagramModelArchimateObject zoneB = addNestedZone(m, host, "Zone B", "grp-b", 20, 200);
        connect(childOf(zoneA), childOf(zoneB), "conn-a-b");
        return m;
    }

    /**
     * TWO populated zones on the canvas and THREE inside a host: the positioning step has a
     * corridor of its own, and it is not the frame holding the most containers.
     *
     * <p>The canvas pair is 60px apart and the hosted trio 30px apart, so a gate reading the
     * larger frame publishes 30 for a corridor the step will widen from 60. Nothing declines
     * here — this is the shape where the only wrong thing a tool can do is measure.</p>
     */
    private IArchimateModel twoCanvasZonesAndThreeHostedFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addNestedZone(m, v, "Canvas One", "grp-c1", 20, 20);
        addNestedZone(m, v, "Canvas Two", "grp-c2", 20, 230);
        IDiagramModelArchimateObject host = addHostShell(m, v, "Cloud Region", "host-cr");
        IDiagramModelArchimateObject zoneA = addNestedZone(m, host, "Zone A", "grp-a", 20, 20);
        IDiagramModelArchimateObject zoneB = addNestedZone(m, host, "Zone B", "grp-b", 20, 200);
        addNestedZone(m, host, "Zone C", "grp-c", 20, 380);
        connect(childOf(zoneA), childOf(zoneB), "conn-a-b");
        return m;
    }

    /** A host holding exactly ONE zone — containers exist, nested, but no corridor anywhere. */
    private IArchimateModel oneHostedZoneFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelArchimateObject host = addHostShell(m, v, "Cloud Region", "host-cr");
        addNestedZone(m, host, "Zone A", "grp-a", 20, 20);
        return m;
    }

    /**
     * As {@link #hostedZonesFixture}, with the two zones already 100px apart — far enough that
     * the group heuristic's target is met. Separates the structural refusal from the
     * already-met one, which would otherwise never be observed together.
     */
    private IArchimateModel hostedZonesAlreadySpacedFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelArchimateObject host = addHostShell(m, v, "Cloud Region", "host-cr");
        IDiagramModelArchimateObject zoneA = addNestedZone(m, host, "Zone A", "grp-a", 20, 20);
        IDiagramModelArchimateObject zoneB = addNestedZone(m, host, "Zone B", "grp-b", 20, 290);
        connect(childOf(zoneA), childOf(zoneB), "conn-a-b");
        return m;
    }

    /**
     * One populated container on the canvas whose two children sit 40px apart, plus a host holding
     * two zones whose children sit 15px apart. The element step can act only in the canvas
     * container; the host frame holds more containers and would win a largest-frame contest.
     */
    private IArchimateModel twoFrameElementSpacingFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelArchimateObject canvas =
                addNestedZone(m, v, "Canvas Zone", "grp-canvas", 20, 20);
        canvas.setBounds(20, 20, 300, 300);
        addNodeChild(m, canvas, "Canvas Two", "canvas-two");
        childOf(canvas).setBounds(10, 10, 120, 55);
        canvas.getChildren().get(1).setBounds(10, 105, 120, 55);

        IDiagramModelArchimateObject host = addHostShell(m, v, "Cloud Region", "host-cr");
        for (String id : new String[] {"grp-a", "grp-b"}) {
            IDiagramModelArchimateObject zone = addNestedZone(
                    m, host, "Zone " + id, id, 20, "grp-a".equals(id) ? 20 : 200);
            zone.setBounds(zone.getBounds().getX(), zone.getBounds().getY(), 200, 200);
            addNodeChild(m, zone, "Second " + id, id + "-two");
            zone.getChildren().get(0).setBounds(10, 10, 120, 55);
            zone.getChildren().get(1).setBounds(10, 80, 120, 55);
        }
        return m;
    }

    /**
     * An EMPTY {@code Grouping} on the canvas beside a host holding two populated zones — the
     * shape where the populated walk and the containment topology disagree about whether anything
     * is drawn on the view itself.
     */
    private IArchimateModel emptyCanvasBoxBesideHostedZonesFixture() {
        IArchimateModel m = hostedZonesFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IGrouping concept = f.createGrouping();
        concept.setId("grp-empty");
        concept.setName("Empty Zone");
        m.getFolder(FolderType.OTHER).getElements().add(concept);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-grp-empty");
        obj.setArchimateConcept(concept);
        obj.setBounds(20, 20, 200, 150);
        v.getChildren().add(obj);
        return m;
    }

    /** A view whose ONLY container is an empty {@code Grouping} sitting on the canvas. */
    private IArchimateModel emptyTopLevelContainerOnlyFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IGrouping concept = f.createGrouping();
        concept.setId("grp-empty");
        concept.setName("Empty Zone");
        m.getFolder(FolderType.OTHER).getElements().add(concept);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-grp-empty");
        obj.setArchimateConcept(concept);
        obj.setBounds(20, 20, 200, 150);
        v.getChildren().add(obj);
        return m;
    }

    /** A top-level {@code Node} host with no children of its own — nothing nested to find. */
    private IArchimateModel emptyHostShellFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addHostShell(m, v, "Cloud Region", "host-cr");
        return m;
    }

    /** A childless {@code Node} drawn on the view — a host before anything is drawn inside it. */
    private IDiagramModelArchimateObject addHostShell(
            IArchimateModel m, IArchimateDiagramModel v, String name, String id) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode concept = f.createNode();
        concept.setId(id);
        concept.setName(name);
        m.getFolder(FolderType.TECHNOLOGY).getElements().add(concept);

        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(concept);
        // Deliberately NOT at the canvas origin. A host at (0,0) makes canvas coordinates and
        // host-relative coordinates numerically identical, so a pin over a fixture like that
        // cannot tell a correct nested arrangement from one written in the wrong frame — which is
        // the single hazard this whole split exists to avoid.
        obj.setBounds(300, 250, 400, 420);
        v.getChildren().add(obj);
        return obj;
    }

    /**
     * A {@code Grouping} zone holding one node, drawn inside {@code parent} at the given
     * parent-relative origin.
     */
    private IDiagramModelArchimateObject addNestedZone(IArchimateModel m,
            com.archimatetool.model.IDiagramModelContainer parent, String name, String id,
            int x, int y) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IGrouping concept = f.createGrouping();
        concept.setId(id);
        concept.setName(name);
        m.getFolder(FolderType.OTHER).getElements().add(concept);

        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(concept);
        obj.setBounds(x, y, 200, 150);
        parent.getChildren().add(obj);

        addNodeChild(m, obj, name + " Svc", id + "-node");
        return obj;
    }

    /** The single child of a zone built by {@link #addNestedZone} or {@link #addGroupingElement}. */
    private IDiagramModelObject childOf(IDiagramModelArchimateObject zone) {
        return zone.getChildren().get(0);
    }

    private IArchimateModel nestedGroupingFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelArchimateObject outer = addGroupingElement(m, v, "Zone A", "grp-a");

        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IGrouping innerConcept = f.createGrouping();
        innerConcept.setId("grp-inner");
        innerConcept.setName("Inner Zone");
        m.getFolder(FolderType.OTHER).getElements().add(innerConcept);
        IDiagramModelArchimateObject inner = f.createDiagramModelArchimateObject();
        inner.setId("obj-grp-inner");
        inner.setArchimateConcept(innerConcept);
        inner.setBounds(10, 10, 160, 90);
        outer.getChildren().add(inner);
        return m;
    }

    private IArchimateModel emptyModel() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setName("Top-Level Grouping Test");
        m.setId("model-tlg");
        m.setDefaults();
        return m;
    }

    private IArchimateDiagramModel viewOf(IArchimateModel m) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateDiagramModel v = f.createArchimateDiagramModel();
        v.setId(VIEW_ID);
        v.setName("Zones View");
        m.getFolder(FolderType.DIAGRAMS).getElements().add(v);
        return v;
    }

    /** An ArchiMate {@code Grouping} element placed on the view, holding one node. */
    private IDiagramModelArchimateObject addGroupingElement(
            IArchimateModel m, IArchimateDiagramModel v, String name, String id) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IGrouping concept = f.createGrouping();
        concept.setId(id);
        concept.setName(name);
        m.getFolder(FolderType.OTHER).getElements().add(concept);

        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(concept);
        obj.setBounds(0, 0, 200, 150);
        v.getChildren().add(obj);

        addNodeChild(m, obj, name + " Host", id + "-node");
        return obj;
    }

    /** A native view group holding one node. */
    private IDiagramModelGroup addNativeGroup(
            IArchimateModel m, IArchimateDiagramModel v, String name, String id) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelGroup group = f.createDiagramModelGroup();
        group.setId("obj-" + id);
        group.setName(name);
        group.setBounds(0, 0, 200, 150);
        v.getChildren().add(group);

        addNodeChild(m, group, name + " Host", id + "-node");
        return group;
    }

    private void addNodeChild(IArchimateModel m,
            com.archimatetool.model.IDiagramModelContainer parent, String name, String id) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode node = f.createNode();
        node.setId(id);
        node.setName(name);
        m.getFolder(FolderType.TECHNOLOGY).getElements().add(node);

        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(node);
        obj.setBounds(10, 10, 120, 55);
        parent.getChildren().add(obj);
    }

    // ---- the endpoint-kind fixtures: two zones, one candidate, one wiring each ----

    /**
     * Two zones, each holding one node, plus a top-level {@code CommunicationNetwork} candidate
     * with no connections yet. Each fixture below wires it differently and changes nothing else,
     * so the only thing that can move a result between them is the endpoint kind.
     */
    private IArchimateModel twoZonesAndACandidateFixture() {
        IArchimateModel m = groupingOnlyFixture();
        addLooseNetwork(m, firstViewOf(m), "Direct Connect", "dc");
        return m;
    }

    /** Wired to an element INSIDE each zone. */
    private IArchimateModel candidateWiredInsideBothZonesFixture() {
        IArchimateModel m = twoZonesAndACandidateFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone A"), "conn-dc-a");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone B"), "conn-dc-b");
        return m;
    }

    /** Wired to each zone's BOX — the container object itself, not a child of it. */
    private IArchimateModel candidateWiredToBothZoneBoxesFixture() {
        IArchimateModel m = twoZonesAndACandidateFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        connect(topLevelNamed(v, "Direct Connect"), topLevelNamed(v, "Zone A"), "conn-dc-a");
        connect(topLevelNamed(v, "Direct Connect"), topLevelNamed(v, "Zone B"), "conn-dc-b");
        return m;
    }

    /**
     * The live shape: one zone entered through an element inside it, and a {@code Node} host
     * entered on the box, that host arranged only because the caller named it in {@code groupIds}.
     */
    private IArchimateModel candidateWiredToANamedHostBoxFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addGroupingElement(m, v, "Zone A", "grp-a");
        addPopulatedHost(m, v, "Region", "host");
        addLooseNetwork(m, v, "Direct Connect", "dc");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone A"), "conn-dc-a");
        connect(topLevelNamed(v, "Direct Connect"), topLevelNamed(v, "Region"), "conn-dc-host");
        return m;
    }

    /** Wired into a third zone the caller leaves out of {@code groupIds}, and into one it keeps. */
    private IArchimateModel candidateWiredIntoAnExcludedZoneFixture() {
        IArchimateModel m = twoZonesAndACandidateFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        addGroupingElement(m, v, "Zone C", "grp-c");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone A"), "conn-dc-a");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone C"), "conn-dc-c");
        return m;
    }

    /** Wired into one zone and into an element inside a host no call named. */
    private IArchimateModel candidateWiredIntoAnUnnamedHostFixture() {
        IArchimateModel m = twoZonesAndACandidateFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        addPopulatedHost(m, v, "Region", "host");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone A"), "conn-dc-a");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Region"), "conn-dc-host");
        return m;
    }

    /** Wired into one zone and to a loose top-level element that is inside nothing. */
    private IArchimateModel candidateWiredToALoosePeerFixture() {
        IArchimateModel m = twoZonesAndACandidateFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        addLooseNode(m, v, "Edge Gateway", "gw");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone A"), "conn-dc-a");
        connect(topLevelNamed(v, "Direct Connect"), topLevelNamed(v, "Edge Gateway"), "conn-dc-gw");
        return m;
    }

    /**
     * Wired into one zone and back to itself. {@code connect} accepts a source that is also the
     * target — measured, not assumed — and registers the one connection in both the object's
     * source and target lists, so the walk meets the candidate's own id twice and this fixture
     * genuinely reaches the case.
     */
    private IArchimateModel candidateWiredToItselfFixture() {
        IArchimateModel m = twoZonesAndACandidateFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        IDiagramModelObject candidate = topLevelNamed(v, "Direct Connect");
        connect(candidate, hostOf(v, "Zone A"), "conn-dc-a");
        connect(candidate, candidate, "conn-dc-self");
        return m;
    }

    /** Wired to a container nested INSIDE one zone, and into the other. */
    private IArchimateModel candidateWiredToANestedZoneFixture() {
        IArchimateModel m = twoZonesAndACandidateFixture();
        IArchimateDiagramModel v = firstViewOf(m);

        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IGrouping innerConcept = f.createGrouping();
        innerConcept.setId("grp-inner");
        innerConcept.setName("Inner Zone");
        m.getFolder(FolderType.OTHER).getElements().add(innerConcept);
        IDiagramModelArchimateObject inner = f.createDiagramModelArchimateObject();
        inner.setId("obj-grp-inner");
        inner.setArchimateConcept(innerConcept);
        inner.setBounds(10, 80, 160, 60);
        ((com.archimatetool.model.IDiagramModelContainer) topLevelNamed(v, "Zone A"))
                .getChildren().add(inner);

        connect(topLevelNamed(v, "Direct Connect"), inner, "conn-dc-inner");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone B"), "conn-dc-b");
        return m;
    }

    /** Wired to a note drawn inside one zone, and into the other. */
    private IArchimateModel candidateWiredToANoteInAZoneFixture() {
        IArchimateModel m = twoZonesAndACandidateFixture();
        IArchimateDiagramModel v = firstViewOf(m);

        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setId("note-in-zone");
        note.setContent("A caption inside the zone");
        note.setBounds(10, 80, 120, 40);
        ((com.archimatetool.model.IDiagramModelContainer) topLevelNamed(v, "Zone A"))
                .getChildren().add(note);

        connect(topLevelNamed(v, "Direct Connect"), note, "conn-dc-note");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone B"), "conn-dc-b");
        return m;
    }

    /** Wired to one zone's box AND to an element inside that same zone — one container, two routes. */
    private IArchimateModel candidateWiredToOneZoneByBothRoutesFixture() {
        IArchimateModel m = twoZonesAndACandidateFixture();
        IArchimateDiagramModel v = firstViewOf(m);
        connect(topLevelNamed(v, "Direct Connect"), topLevelNamed(v, "Zone A"), "conn-dc-box");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone A"), "conn-dc-inside");
        return m;
    }

    /** One zone and a candidate wired into it — there is no second container, so there is no gap. */
    private IArchimateModel oneZoneAndACandidateFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addGroupingElement(m, v, "Zone A", "grp-a");
        addLooseNetwork(m, v, "Direct Connect", "dc");
        connect(topLevelNamed(v, "Direct Connect"), hostOf(v, "Zone A"), "conn-dc-a");
        return m;
    }

    /** A childless top-level {@code CommunicationNetwork} — lane-eligible by type. */
    private IDiagramModelArchimateObject addLooseNetwork(
            IArchimateModel m, IArchimateDiagramModel v, String name, String id) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        ICommunicationNetwork concept = f.createCommunicationNetwork();
        concept.setId(id);
        concept.setName(name);
        m.getFolder(FolderType.TECHNOLOGY).getElements().add(concept);

        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(concept);
        obj.setBounds(0, 0, 120, 55);
        v.getChildren().add(obj);
        return obj;
    }

    /** The named top-level child of the view — the box itself. {@link #hostOf} returns its child. */
    private IDiagramModelObject topLevelNamed(IArchimateDiagramModel v, String name) {
        for (IDiagramModelObject child : v.getChildren()) {
            if (name.equals(child.getName())) {
                return child;
            }
        }
        throw new AssertionError("no top-level object named " + name);
    }

    // ==================== harness ====================

    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(IArchimateModel target) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> target) {
            @Override
            public void dispatchImmediate(Command command) {
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    dispatchedLeafCommands.add(command);
                    command.execute();
                }
            }
        };
        testDispatcher.setApprovalModeProvider(() -> false);
        this.dispatcher = testDispatcher;
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
    }

    /**
     * Minimal {@link IEditorModelManager} stub — only supports {@code setModels()} /
     * {@code getModels()} and listener registration.
     */
    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        @Override
        public List<IArchimateModel> getModels() {
            return models;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

        @SuppressWarnings("unused")
        void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
            PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
            for (PropertyChangeListener listener : new ArrayList<>(listeners)) {
                listener.propertyChange(evt);
            }
        }

        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel model) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel model) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel model, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel model) { return false; }
        @Override public boolean saveModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel model) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object source, String prop, Object oldValue, Object newValue) {}
    }
}
