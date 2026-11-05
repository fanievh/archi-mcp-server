package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;

import net.vheerden.archi.mcp.model.CapturedViewHarness.Ctx;
import net.vheerden.archi.mcp.model.routing.LabelPolicy;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;

/**
 * {@code auto-route-connections} picks each label's position on the route the pipeline chose. A pick is written only
 * with that route: a connection whose route the call does not apply keeps its old route, and a label the policy hides
 * draws nothing, so neither label moves, and {@code labelsOptimized} counts neither.
 *
 * <p>The views are built by hand. The first boxes its target in on all four sides, so no route reaches it clear of the
 * ring and the call (without {@code force}) applies none. The second runs one connection down a 40 px corridor between
 * two wide boxes, so its label collides wherever it goes and the hiding policy hides it. Labels are named, so the real
 * collector measures their text: these tests need a display.</p>
 */
public class RoutingLabelWritesFollowTheRouteTest {

    private static final int SOURCE = 0;
    private static final int MIDDLE = 1;

    @Test
    public void shouldLeaveTheLabelWhereItIs_whenTheRouteIsNotApplied() {
        Ctx c = boxedInTarget();
        IDiagramModelArchimateConnection conn = connection(c);

        AutoRouteResultDto dto = route(c, false, null);

        assertEquals("precondition: the route is not applied", 1, dto.connectionsFailed());
        assertEquals(0, dto.connectionsRouted());
        assertTrue("precondition: the old route is kept", conn.getBendpoints().isEmpty());
        assertEquals("the label stays on the old route where it was", MIDDLE, conn.getTextPosition());
        assertEquals("no label was written", 0, dto.labelsOptimized());
    }

    @Test
    public void shouldWriteTheLabelWithTheRoute_whenForceAppliesTheViolatedRoute() {
        Ctx c = boxedInTarget();
        IDiagramModelArchimateConnection conn = connection(c);

        AutoRouteResultDto dto = route(c, true, null);

        assertEquals("precondition: force applies the route", 1, dto.connectionsRouted());
        assertEquals("the pick made on that route ships with it", SOURCE, conn.getTextPosition());
        assertEquals(1, dto.labelsOptimized());
    }

    @Test
    public void shouldMoveTheCorridorLabel_whenThePolicyKeepsIt() {
        // The hiding test below proves something only if this label would otherwise move.
        Ctx c = corridor();
        IDiagramModelArchimateConnection conn = connection(c);

        AutoRouteResultDto dto = route(c, false, null);

        assertEquals("precondition: the route is applied", 1, dto.connectionsRouted());
        assertTrue("the label is shown", LabelVisibilityReadback.isShown(conn, null));
        assertNotEquals("the pick moves the label off Middle", MIDDLE, conn.getTextPosition());
        assertEquals(1, dto.labelsOptimized());
    }

    @Test
    public void shouldLeaveAHiddenLabelWhereItIs_whenThePolicyHidesIt() {
        Ctx c = corridor();
        IDiagramModelArchimateConnection conn = connection(c);

        AutoRouteResultDto dto = route(c, false, LabelPolicy.AUTO_HIDE_ON_COLLISION.wireValue());

        assertEquals("precondition: the route is applied", 1, dto.connectionsRouted());
        assertEquals("precondition: the policy hid the label", 1, dto.hiddenLabels().size());
        assertFalse(LabelVisibilityReadback.isShown(conn, null));
        assertEquals("a hidden label is not moved", MIDDLE, conn.getTextPosition());
        assertEquals("and not counted", 0, dto.labelsOptimized());
    }

    @Test
    public void shouldWriteEveryPick_onTheLayoutPassThatAppliesEveryRoute() {
        // auto-layout-and-route's routing pass applies violated routes too, so under the default policy nothing is
        // held back: the pick on the boxed-in connection ships with its route.
        Ctx c = boxedInTarget();
        ArchiModelAccessorImpl accessor = accessor(c);
        try {
            AutoRoutePassResult pass = accessor.computeAutoRoutePass(
                    CapturedViewHarness.VIEW_ID, c.view, c.model, LabelPolicy.KEEP);

            assertEquals(1, pass.routedCount);
            assertEquals(1, pass.labelsOptimized);
            assertEquals(1, LabelOffsetSupport.countOptimizedLabels(pass.compound));
        } finally {
            accessor.dispose();
        }
    }

    @Test
    public void shouldLeaveAHiddenLabelWhereItIs_onTheLayoutPassToo() {
        Ctx c = corridor();
        ArchiModelAccessorImpl accessor = accessor(c);
        try {
            AutoRoutePassResult kept = accessor.computeAutoRoutePass(
                    CapturedViewHarness.VIEW_ID, c.view, c.model, LabelPolicy.KEEP);
            assertEquals("precondition: under the default policy the pick is written", 1, kept.labelsOptimized);

            AutoRoutePassResult hidden = accessor.computeAutoRoutePass(
                    CapturedViewHarness.VIEW_ID, c.view, c.model, LabelPolicy.AUTO_HIDE_ON_COLLISION);
            assertEquals(1, hidden.routedCount);
            assertEquals("precondition: the policy hid the label", 1, hidden.hiddenLabelIds.size());
            assertEquals("the hidden label is not moved", 0, LabelOffsetSupport.countOptimizedLabels(hidden.compound));
            assertEquals("nor counted", 0, hidden.labelsOptimized);
        } finally {
            accessor.dispose();
        }
    }

    // ==================== views ====================

    /** A source above a target walled in by four touching boxes; one named connection, label held at Middle. */
    private static Ctx boxedInTarget() {
        Ctx c = CapturedViewHarness.newView();
        CapturedViewHarness.addBox(c, "t", "ApplicationComponent", null, 400, 400, 120, 55);
        CapturedViewHarness.addBox(c, "top", "ApplicationComponent", null, 340, 340, 240, 40);
        CapturedViewHarness.addBox(c, "bot", "ApplicationComponent", null, 340, 475, 240, 40);
        CapturedViewHarness.addBox(c, "lft", "ApplicationComponent", null, 340, 380, 40, 95);
        CapturedViewHarness.addBox(c, "rgt", "ApplicationComponent", null, 540, 380, 40, 95);
        CapturedViewHarness.addBox(c, "s", "ApplicationComponent", null, 420, 0, 120, 55);
        return named(c, "a medium label");
    }

    /** A source above a target, the run between them flanked by two wide boxes 40 px apart. */
    private static Ctx corridor() {
        Ctx c = CapturedViewHarness.newView();
        CapturedViewHarness.addBox(c, "s", "ApplicationComponent", null, 0, 0, 120, 55);
        CapturedViewHarness.addBox(c, "t", "ApplicationComponent", null, 0, 400, 120, 55);
        CapturedViewHarness.addBox(c, "l", "ApplicationComponent", null, -300, 60, 340, 335);
        CapturedViewHarness.addBox(c, "r", "ApplicationComponent", null, 80, 60, 300, 335);
        return named(c, "carries the nightly settlement batch");
    }

    private static Ctx named(Ctx c, String label) {
        CapturedViewHarness.connect(c, "conn-st", "obj-s", "obj-t");
        IDiagramModelArchimateConnection conn = connection(c);
        conn.getArchimateRelationship().setName(label);
        conn.setTextPosition(MIDDLE);
        return c;
    }

    private static IDiagramModelArchimateConnection connection(Ctx c) {
        for (IDiagramModelConnection conn : c.objects.get("s").getSourceConnections()) {
            return (IDiagramModelArchimateConnection) conn;
        }
        throw new AssertionError("no connection");
    }

    // ==================== routing ====================

    private static AutoRouteResultDto route(Ctx c, boolean force, String labelPolicy) {
        ArchiModelAccessorImpl accessor = accessor(c);
        try {
            return accessor.autoRouteConnections("labels", CapturedViewHarness.VIEW_ID, null, "orthogonal", force,
                    false, 0, 0, null, true, labelPolicy).entity();
        } finally {
            accessor.dispose();
        }
    }

    private static ArchiModelAccessorImpl accessor(Ctx c) {
        AutoRouteArmFixture.StubEditorModelManager manager = new AutoRouteArmFixture.StubEditorModelManager();
        manager.setModels(List.of(c.model));
        MutationDispatcher dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
        dispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(manager, dispatcher);
    }
}
