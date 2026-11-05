package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IBusinessRole;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.NudgeRecommendationScreen.Floor;
import net.vheerden.archi.mcp.model.NudgeRecommendationScreen.Withheld;
import net.vheerden.archi.mcp.model.routing.MoveRecommendation;
import net.vheerden.archi.mcp.model.routing.RoutingRecommendationEngine;

/**
 * The recommendation screen against the clamp the autoNudge loop applies.
 *
 * <p>Every parent sits at a non-zero canvas offset on both axes, so a child's relative and
 * absolute coordinates differ. A screen that read the router's absolute rectangles instead of the
 * relative bounds the mutator clamps would pass a fixture whose parent sits at the origin and fail
 * here.</p>
 */
public class NudgeRecommendationScreenTest {

    private static final int PADDING = 10;
    private static final int BAND = NestedLayoutOperations.CONTAINER_TITLE_BAND_HEIGHT;

    private final IArchimateFactory f = IArchimateFactory.eINSTANCE;
    private final IArchimateDiagramModel view = f.createArchimateDiagramModel();

    // ---- frames that differ ---------------------------------------------------------------

    @Test
    public void shouldWithholdALeftMove_whenTheChildSitsAtTheGroupsLeftPadding() {
        IDiagramModelGroup parent = group("Lane", 200, 150);
        IDiagramModelObject child = element(parent, "Step", 10, 40);

        Fixture fx = screen(rec(child, -30, 0));

        assertTrue("absolute x is 210, so a canvas-frame screen would keep this move",
                fx.kept.isEmpty());
        Withheld w = only(fx.withheld);
        assertEquals(child.getId(), w.elementId());
        assertEquals("Step", w.elementName());
        assertEquals(parent.getId(), w.parentId());
        assertEquals("Lane", w.parentName());
        assertEquals(Set.of(Floor.LEFT_PADDING), w.floors());
    }

    @Test
    public void shouldKeepTheLeftMoveUnchanged_whenTheChildHasRoomInsideTheGroup() {
        IDiagramModelGroup parent = group("Lane", 200, 150);
        IDiagramModelObject child = element(parent, "Step", 60, 40);
        MoveRecommendation rec = rec(child, -30, 0);

        Fixture fx = screen(rec);

        assertEquals(List.of(rec), fx.kept);
        assertTrue(fx.withheld.isEmpty());
        assertMutatorIsANoOp(fx);
    }

    @Test
    public void shouldScreenAnElementParentTheSameWay() {
        IDiagramModelArchimateObject role = role("Role lane", 240, 130);
        IDiagramModelObject atPadding = element(role, "At padding", 10, 60);
        IDiagramModelObject withRoom = element(role, "With room", 80, 60);

        Fixture fx = screen(rec(atPadding, -30, 0), rec(withRoom, -30, 0));

        assertEquals(1, fx.kept.size());
        assertEquals(withRoom.getId(), fx.kept.get(0).elementId());
        assertEquals(-30, fx.kept.get(0).dx());
        Withheld w = only(fx.withheld);
        assertEquals(atPadding.getId(), w.elementId());
        assertEquals(role.getId(), w.parentId());
        assertEquals("Role lane", w.parentName());
        assertEquals(Set.of(Floor.LEFT_PADDING), w.floors());
        assertMutatorIsANoOp(fx);
    }

    // ---- title band -----------------------------------------------------------------------

    @Test
    public void shouldWithholdAnUpwardMove_whenTheChildSitsAtTheTitleBand() {
        IDiagramModelObject child = element(group("Lane", 200, 150), "Step", 60, BAND);

        Fixture fx = screen(rec(child, 0, -20));

        assertTrue(fx.kept.isEmpty());
        assertEquals(Set.of(Floor.TITLE_BAND), only(fx.withheld).floors());
    }

    @Test
    public void shouldShortenAnUpwardMoveToTheBand_andRebuildItsReasonFromTheShortenedMove() {
        IDiagramModelObject child = element(group("Lane", 200, 150), "Step", 60, BAND + 30);

        Fixture fx = screen(rec(child, 0, -50));

        MoveRecommendation kept = only(fx.kept);
        assertEquals(0, kept.dx());
        assertEquals(-30, kept.dy());
        assertEquals(RoutingRecommendationEngine.buildReason(0, -30, 2), kept.reason());
        assertEquals("the unblocked count is the router's, not the screen's", 2,
                kept.connectionsUnblocked());
        assertEquals(child.getId(), kept.elementId());
        assertTrue(fx.withheld.isEmpty());
        assertMutatorIsANoOp(fx);
    }

    @Test
    public void shouldKeepTheFreeAxis_whenTheOtherAxisIsStoppedByItsFloor() {
        IDiagramModelObject child = element(group("Lane", 200, 150), "Step", 10, BAND + 40);

        Fixture fx = screen(rec(child, -30, -20));

        MoveRecommendation kept = only(fx.kept);
        assertEquals(0, kept.dx());
        assertEquals(-20, kept.dy());
        assertEquals(RoutingRecommendationEngine.buildReason(0, -20, 2), kept.reason());
        assertMutatorIsANoOp(fx);
    }

    @Test
    public void shouldNameBothFloors_whenAMoveIsStoppedOnBothAxes() {
        IDiagramModelObject child = element(group("Lane", 200, 150), "Step", 10, BAND);

        Fixture fx = screen(rec(child, -30, -20));

        assertTrue(fx.kept.isEmpty());
        assertEquals(List.of(Floor.LEFT_PADDING, Floor.TITLE_BAND),
                List.copyOf(only(fx.withheld).floors()));
    }

    // ---- contrary clamp, top level, prior deltas ------------------------------------------

    @Test
    public void shouldWithholdRatherThanReverse_whenTheChildIsAlreadyLeftOfThePaddingLine() {
        IDiagramModelObject child = element(group("Lane", 200, 150), "Step", 3, 60);

        Fixture fx = screen(rec(child, -30, 0));

        assertTrue("the clamp would push this child RIGHT; never publish that as a recommendation",
                fx.kept.isEmpty());
        assertEquals(Set.of(Floor.LEFT_PADDING), only(fx.withheld).floors());
    }

    @Test
    public void shouldLeaveATopLevelRecommendationUnchanged() {
        IDiagramModelObject topLevel = element(view, "Top", 5, 5);
        MoveRecommendation rec = rec(topLevel, -30, -20);

        Fixture fx = screen(rec);

        assertEquals(List.of(rec), fx.kept);
        assertTrue(fx.withheld.isEmpty());
    }

    @Test
    public void shouldCountEarlierIterations_whenTheyAlreadyMovedTheChildToThePadding() {
        IDiagramModelObject child = element(group("Lane", 200, 150), "Step", 35, 60);
        Map<String, int[]> prior = Map.of(child.getId(), new int[]{-25, 0});

        Fixture fx = screen(prior, rec(child, -30, 0));

        assertTrue("35 - 25 = 10, the padding line", fx.kept.isEmpty());
        assertEquals(child.getId(), only(fx.withheld).elementId());
    }

    @Test
    public void shouldLeaveAnUnresolvableIdToTheLoop() {
        MoveRecommendation rec = new MoveRecommendation("gone", "gone", -30, 0, "r", 1);

        Fixture fx = screen(rec);

        assertEquals(List.of(rec), fx.kept);
    }

    @Test
    public void shouldRememberEachWithheldElementOnce_acrossPasses() {
        IDiagramModelObject child = element(group("Lane", 200, 150), "Step", 10, 60);
        NudgeRecommendationScreen screen =
                new NudgeRecommendationScreen(viewObjects(), PADDING);

        screen.screen(List.of(rec(child, -30, 0)), Map.of());
        screen.screen(List.of(rec(child, -20, 0)), Map.of());

        assertEquals(1, screen.withheld().size());
    }

    @Test
    public void shouldNeverOfferMoreThanTheRouterAsked_whenTheClampWouldCarryTheChildPastTheMove() {
        // Left of the padding line, a small move right: the flat floor would carry the child to
        // the line, further than the router asked and further than it checked the neighbours.
        IDiagramModelObject child = element(group("Lane", 200, 150), "Step", 2, 60);
        MoveRecommendation rec = rec(child, 3, 0);

        Fixture fx = screen(rec);

        assertEquals("the router's own move is offered, never a longer one the clamp made",
                List.of(rec), fx.kept);
        assertTrue(fx.withheld.isEmpty());
    }

    @Test
    public void shouldForgetAWithholding_whenALaterPassOffersTheElementAMoveItCanTake() {
        IDiagramModelObject child = element(group("Lane", 200, 150), "Step", 10, 60);
        NudgeRecommendationScreen screen =
                new NudgeRecommendationScreen(viewObjects(), PADDING);

        screen.screen(List.of(rec(child, -30, 0)), Map.of());
        List<MoveRecommendation> second = screen.screen(List.of(rec(child, 0, 25)), Map.of());

        assertEquals(1, second.size());
        assertTrue("an element the response offers a move must not also be reported as withheld",
                screen.withheld().isEmpty());
    }

    @Test
    public void shouldNameEveryFloorThatStoppedTheElement_acrossPasses() {
        IDiagramModelObject child = element(group("Lane", 200, 150), "Step", 10, 20);
        NudgeRecommendationScreen screen =
                new NudgeRecommendationScreen(viewObjects(), PADDING);

        screen.screen(List.of(rec(child, -30, 0)), Map.of());
        screen.screen(List.of(rec(child, 0, -10)), Map.of());

        assertEquals(EnumSet.of(Floor.LEFT_PADDING, Floor.TITLE_BAND),
                only(screen.withheld()).floors());
    }

    @Test
    public void shouldNameTheParentByIdWhenItHasNoName() {
        IDiagramModelGroup parent = group("", 200, 150);
        element(parent, "Step", 10, 60);

        Withheld w = only(screen(rec(parent.getChildren().get(0), -30, 0)).withheld);

        assertEquals(parent.getId(), w.parentName());
    }

    // ---- one rule, both sides -------------------------------------------------------------

    @Test
    public void screenSource_shouldCallTheMutatorsClamp_andComputeNoFloorOfItsOwn() {
        String code = stripComments(readProductionSource("model/NudgeRecommendationScreen.java"));

        assertTrue("the screen must run the clamp the mutator runs",
                code.contains("NestedLayoutOperations.clampInsideParent("));
        assertFalse("a floor computed here is a second copy of the rule", code.contains("Math.max("));
        assertFalse(code.contains("titleBandFloor"));
        assertFalse(code.contains("CONTAINER_TITLE_BAND_HEIGHT"));
        assertFalse("the router's absolute rectangles are the wrong frame",
                code.contains("RoutingRect"));
    }

    /**
     * The autoNudge loop cannot be reached by a display-less test (the router routes around every
     * blocker such a fixture can build), so its wiring is pinned in the source: every pass's
     * recommendations go through the screen before anything reads them, with the padding the
     * mutator's own clamp is given.
     */
    @Test
    public void autoRouteSource_shouldScreenEveryPassBeforeTheLoopOrTheResponseReadsIt() {
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");

        String firstPass = "moveRecommendations = routeResult.moveRecommendations;";
        String construct = "new NudgeRecommendationScreen(allViewObjects, DEFAULT_GROUP_PADDING)";
        String screenFirst = "moveRecommendations = screen.screen(moveRecommendations, cumulativeDeltas);";
        String screenReRoute =
                "moveRecommendations = screen.screen(reRouteResult.moveRecommendations, cumulativeDeltas);";
        String loopGate = "if (effectiveAutoNudge && !failedConnections.isEmpty()";
        String mutatorClamp = "NestedLayoutOperations.clampInsideParent(container,\n"
                + "                                    newX, newY, bounds.getY() + priorDelta[1], "
                + "DEFAULT_GROUP_PADDING);";

        assertEquals("one first-pass assignment", 1, count(facade, firstPass));
        assertEquals(1, count(facade, construct));
        assertEquals(1, count(facade, screenFirst));
        assertEquals("the re-route's list is read nowhere but through the screen",
                1, count(facade, "reRouteResult.moveRecommendations"));
        assertEquals(1, count(facade, screenReRoute));
        assertEquals("the screen and the mutator pass the same padding", 1, count(facade, mutatorClamp));

        int first = facade.indexOf(firstPass);
        int screened = facade.indexOf(screenFirst);
        int gate = facade.indexOf(loopGate);
        assertTrue("the first pass is screened after it is assigned and before the loop gate",
                first > 0 && first < screened && screened < gate);
        assertEquals("nothing reads the first pass's list before the screen does", 0,
                count(facade.substring(first + firstPass.length(), screened), "moveRecommendations"));
        assertTrue("the response's recommendations are built after the loop, from the screened list",
                facade.indexOf("for (MoveRecommendation rec : moveRecommendations) {\n"
                        + "                    String elemName", gate) > gate);
        assertEquals("withheld moves are disclosed once per call", 1, count(facade,
                "AutoRouteWarnings.emitRecommendationsWithheld(force ? List.of() : screen.withheld(),"));
    }

    @Test
    public void withheldWarning_shouldNameEveryElementItsParentAndSide_andBothRemedies() {
        IDiagramModelGroup lane = group("Lane", 200, 150);
        IDiagramModelObject atPadding = element(lane, "At padding", 10, 60);
        IDiagramModelObject atBand = element(lane, "At band", 80, BAND);
        IDiagramModelArchimateObject role = role("Role lane", 240, 500);
        IDiagramModelObject inRole = element(role, "In role", 10, 60);
        Fixture fx = screen(rec(atPadding, -30, 0), rec(atBand, 0, -20), rec(inRole, -30, 0));
        java.util.List<String> warnings = new java.util.ArrayList<>();
        java.util.List<net.vheerden.archi.mcp.response.dto.StructuredWarningDto> structured =
                new java.util.ArrayList<>();

        AutoRouteWarnings.emitRecommendationsWithheld(fx.withheld, warnings, structured);

        assertEquals("one entry per call", 1, structured.size());
        var w = structured.get(0);
        assertEquals(net.vheerden.archi.mcp.response.dto.StructuredWarningCodes
                .MOVE_RECOMMENDATION_WITHHELD_BY_PARENT, w.code());
        assertEquals("update-view-object", w.remediationTool());
        assertEquals("elements first, then each parent once",
                List.of(atPadding.getId(), atBand.getId(), inRole.getId(), lane.getId(), role.getId()),
                w.remediationViolatorIds());
        String m = w.message();
        assertEquals("mirrored into the free-text list", List.of(m), warnings);
        assertTrue(m, m.startsWith("3 move recommendation(s) withheld"));
        assertTrue(m, m.contains("At padding (" + atPadding.getId() + ") is held by the left padding "
                + "of its parent Lane (" + lane.getId() + "), so a move left cannot take effect"));
        assertTrue(m, m.contains("At band (" + atBand.getId() + ") is held by the title band of its "
                + "parent Lane (" + lane.getId() + "), so a move up cannot take effect"));
        assertTrue(m, m.contains("of its parent Role lane (" + role.getId() + ")"));
        assertTrue("remedy one: the parent", m.contains("every child moves with the parent")
                && m.contains("other end lies outside the parent"));
        assertTrue("remedy two: the element", m.contains("move the element out of the parent"));
        for (String banned : List.of("nudged by 0", "were nudged", "was nudged", "moved")) {
            assertFalse("no applied-state claim: " + banned, m.contains(banned));
        }
    }

    @Test
    public void withheldWarning_shouldPrintAnUnnamedParentsIdOnce() {
        IDiagramModelGroup parent = group("", 200, 150);
        element(parent, "Step", 10, 60);
        Fixture fx = screen(rec(parent.getChildren().get(0), -30, 0));
        java.util.List<String> warnings = new java.util.ArrayList<>();
        java.util.List<net.vheerden.archi.mcp.response.dto.StructuredWarningDto> structured =
                new java.util.ArrayList<>();

        AutoRouteWarnings.emitRecommendationsWithheld(fx.withheld, warnings, structured);

        String m = only(warnings);
        assertEquals(m, 1, count(m, parent.getId()));
        assertTrue(m, m.contains("of its parent " + parent.getId() + ", so a move left"));
    }

    @Test
    public void withheldWarning_shouldEmitNothing_whenNothingWasWithheld() {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        java.util.List<net.vheerden.archi.mcp.response.dto.StructuredWarningDto> structured =
                new java.util.ArrayList<>();
        AutoRouteWarnings.emitRecommendationsWithheld(List.of(), warnings, structured);
        assertTrue(warnings.isEmpty() && structured.isEmpty());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // ---- helpers --------------------------------------------------------------------------

    private record Fixture(List<MoveRecommendation> kept, List<Withheld> withheld,
            Map<String, int[]> prior, Map<String, IDiagramModelObject> objects) {
    }

    private Fixture screen(MoveRecommendation... recs) {
        return screen(Map.of(), recs);
    }

    private Fixture screen(Map<String, int[]> prior, MoveRecommendation... recs) {
        Map<String, IDiagramModelObject> objects = viewObjects();
        NudgeRecommendationScreen screen = new NudgeRecommendationScreen(objects, PADDING);
        List<MoveRecommendation> kept = screen.screen(List.of(recs), prior);
        return new Fixture(kept, screen.withheld(), prior, objects);
    }

    /**
     * Applies the autoNudge loop's arithmetic to every kept recommendation and asserts the clamp
     * lets it through unchanged: on a screened list the mutator's clamp is a no-op.
     */
    private static void assertMutatorIsANoOp(Fixture fx) {
        for (MoveRecommendation rec : fx.kept) {
            IDiagramModelObject dmo = fx.objects.get(rec.elementId());
            IBounds b = dmo.getBounds();
            int[] prior = fx.prior.getOrDefault(rec.elementId(), new int[]{0, 0});
            int newX = b.getX() + prior[0] + rec.dx();
            int newY = b.getY() + prior[1] + rec.dy();
            int[] clamped = NestedLayoutOperations.clampInsideParent(dmo.eContainer(),
                    newX, newY, b.getY() + prior[1], PADDING);
            assertArrayEquals("the mutator would move " + rec.elementId() + " other than published",
                    new int[]{newX, newY}, clamped);
        }
    }

    private Map<String, IDiagramModelObject> viewObjects() {
        Map<String, IDiagramModelObject> map = new LinkedHashMap<>();
        collect(view, map);
        return map;
    }

    private static void collect(IDiagramModelContainer container,
            Map<String, IDiagramModelObject> map) {
        for (IDiagramModelObject child : container.getChildren()) {
            map.put(child.getId(), child);
            if (child instanceof IDiagramModelContainer nested) {
                collect(nested, map);
            }
        }
    }

    private IDiagramModelGroup group(String name, int x, int y) {
        IDiagramModelGroup group = f.createDiagramModelGroup();
        group.setId(java.util.UUID.randomUUID().toString());
        group.setName(name);
        group.setBounds(f.createBounds(x, y, 400, 300));
        view.getChildren().add(group);
        return group;
    }

    private IDiagramModelArchimateObject role(String name, int x, int y) {
        IBusinessRole concept = f.createBusinessRole();
        concept.setName(name);
        IDiagramModelArchimateObject dmo = f.createDiagramModelArchimateObject();
        dmo.setId(java.util.UUID.randomUUID().toString());
        dmo.setArchimateElement(concept);
        dmo.setBounds(f.createBounds(x, y, 400, 300));
        view.getChildren().add(dmo);
        return dmo;
    }

    private IDiagramModelObject element(IDiagramModelContainer parent, String name, int x, int y) {
        IBusinessRole concept = f.createBusinessRole();
        concept.setName(name);
        IDiagramModelArchimateObject dmo = f.createDiagramModelArchimateObject();
        dmo.setId(java.util.UUID.randomUUID().toString());
        dmo.setArchimateElement(concept);
        dmo.setBounds(f.createBounds(x, y, 120, 55));
        parent.getChildren().add(dmo);
        return dmo;
    }

    private static MoveRecommendation rec(IDiagramModelObject dmo, int dx, int dy) {
        return new MoveRecommendation(dmo.getId(), dmo.getId(), dx, dy,
                RoutingRecommendationEngine.buildReason(dx, dy, 2), 2);
    }

    private static <T> T only(List<T> list) {
        assertEquals(list.toString(), 1, list.size());
        return list.get(0);
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** Reads a production source file, failing rather than silently covering nothing. */
    static String readProductionSource(String relativePath) {
        for (String candidate : new String[]{
                "net.vheerden.archi.mcp/src", "../net.vheerden.archi.mcp/src"}) {
            Path path = Paths.get(candidate, "net/vheerden/archi/mcp", relativePath);
            if (Files.isRegularFile(path)) {
                try {
                    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new AssertionError("could not resolve " + relativePath + " from "
                + Paths.get("").toAbsolutePath() + " — this pin cannot silently cover nothing");
    }
}
