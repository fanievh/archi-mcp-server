package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pin for the ONE source of truth behind the order-preserving reflow remedy:
 * the ordered-axis predicate and the remedy copy that BOTH offer producers
 * emit.
 *
 * <p>Why this class exists: the remedy is emitted from two places — the
 * pre-loop {@link SpacingPreconditionInfeasibilityCertificate} (fires when the
 * input geometry is provably infeasible) and the in-loop
 * {@link SpacingControlLoop#buildDensityDiagnosis} (fires when the loop
 * reaches its PASS-honest density floor). A view whose element order is
 * load-bearing can reach EITHER, so a rule honoured at only one of them is a
 * rule the product does not have. These pins assert the two producers share
 * the predicate and emit the byte-same remedy sentence, so the divergence
 * cannot re-open by someone editing one copy.
 *
 * <p>Pure unit — no OSGi, no EMF, no {@code ArchiModelAccessorImpl}
 * class-loading.
 */
public class OrderedAxisRemedyTest {

    /** ST calibration geometry — the pinned provably-infeasible fixture. */
    private static final double ST_AREA = 994.0 * 975.0;
    private static final double ST_AVG_BOX = 130.7;

    /**
     * The EXACT remedy tail the in-loop diagnosis shipped before it learned
     * about ordered axes — a literal, so "outside the set the loop is
     * byte-identical" is a real byte assertion and not a paraphrase that
     * drifts with the source.
     */
    static final String LOOP_LEGACY_TAIL =
            "This layout was NOT auto-reflowed (a structural reflow moves "
            + "user-placed elements — an explicit-consent boundary). "
            + "OFFERED next step (requires your consent): re-layout this "
            + "view with a structural auto-layout, then re-run "
            + "auto-route-connections. The current view is preserved "
            + "unchanged (no degraded layout was applied).";

    private static LayoutMetrics m(double avgSpacingPx) {
        return new LayoutMetrics(/*thresholdsMet=*/ 5, /*hpq=*/ 0.5, /*m4=*/ 8,
                /*coincSeg=*/ 4, /*boundaryViolations=*/ 0, /*vp10=*/ 5.0,
                /*edgeCrossings=*/ 100, avgSpacingPx);
    }

    private static String loopDiagnosis(String viewpointType, HubExtent hub) {
        return SpacingControlLoop.buildDensityDiagnosis(
                m(112.0), hub, viewpointType);
    }

    /**
     * ST's hub: 214x68 on 7 connections, against a fan-out minimum of
     * 300x250 — genuinely under-sized, so enlarging it IS a remedy.
     */
    private static final HubExtent UNDERSIZED_HUB = new HubExtent(7, 214, 68);

    /**
     * 500x340 on 12 connections, against a fan-out minimum of 350x290 —
     * comfortably adequate, so "enlarge the dominant hub" is NOT a remedy
     * here. These are the real dimensions the live gate exercised.
     */
    private static final HubExtent ADEQUATE_HUB = new HubExtent(12, 500, 340);

    private static String certOffer(String viewpointType, HubExtent hub) {
        return SpacingPreconditionInfeasibilityCertificate.evaluate(
                23, ST_AREA, ST_AVG_BOX, /*measuredAvgSpacingPx=*/ 60.0,
                hub == null ? null : Integer.valueOf(hub.hubWidthPx()),
                hub == null ? null : Integer.valueOf(hub.hubHeightPx()),
                hub == null ? null
                        : Integer.valueOf(hub.maxHubConnectionCount()),
                viewpointType).reflowOffer();
    }

    // ==================================================================
    // 1 — The predicate: ONE definition, shared by both producers.
    // ==================================================================

    /**
     * The reorder-forbidding set is EXACTLY the two evidence-backed
     * viewpoints. This pin is the guard on a deliberate refutation: a
     * swimlane process view orders its steps BY CONNECTIVITY (the repo's own
     * process-flow recipe prescribes the connectivity-driven group
     * arrangement there), so claiming a connectivity re-layout "would
     * scramble that order" would be false on it — it must NOT join the set.
     * The customer-journey band is unreachable by any viewpoint set at all:
     * it is built as a general-purpose view with no viewpoint id, and no such
     * id exists in the tool's viewpoint vocabulary.
     */
    @Test
    public void forbidsReordering_isExactlyTheTwoOrderedAxisViewpoints() {
        assertTrue(OrderedAxisRemedy.forbidsReordering(
                "implementation_migration"));
        assertTrue(OrderedAxisRemedy.forbidsReordering("migration"));

        // Deliberately refuted candidates — order IS connectivity there, or
        // the view class carries no viewpoint id to key on.
        assertFalse("a process-cooperation view orders steps by the flow "
                        + "chain, so a connectivity re-layout re-derives that "
                        + "order rather than scrambling it",
                OrderedAxisRemedy.forbidsReordering(
                        "business_process_cooperation"));
        assertFalse(OrderedAxisRemedy.forbidsReordering("service_design"));
        assertFalse(OrderedAxisRemedy.forbidsReordering("customer_journey"));

        // Unknown / absent ⇒ behave exactly as before (the safe direction).
        assertFalse(OrderedAxisRemedy.forbidsReordering(null));
        assertFalse(OrderedAxisRemedy.forbidsReordering(""));
        assertFalse(OrderedAxisRemedy.forbidsReordering("layered"));
        assertFalse(OrderedAxisRemedy.forbidsReordering("value_stream"));
        assertFalse(OrderedAxisRemedy.forbidsReordering("project"));
        assertFalse(OrderedAxisRemedy.forbidsReordering(
                "implementation_deployment"));
    }

    /**
     * The certificate's own predicate must not fork from the shared one —
     * it is the seam this class exists to keep closed.
     */
    @Test
    public void certificatePredicate_delegatesToTheSharedOne() {
        for (String vp : new String[] {null, "", "implementation_migration",
                "migration", "business_process_cooperation", "layered",
                "  Implementation_Migration  ", "MIGRATION",
                "migration_planning", "pre_migration"}) {
            assertEquals("[" + String.valueOf(vp) + "]",
                    OrderedAxisRemedy.forbidsReordering(vp),
                    SpacingPreconditionInfeasibilityCertificate
                            .forbidsReordering(vp));
        }
    }

    /**
     * A view with NO viewpoint reads back from the model as the EMPTY STRING,
     * not as null — so "absent" arrives here in two shapes, and they must not
     * produce two different pass-through values. Without normalisation the
     * commonest case in a real model (a general-purpose view) yields a
     * {@code Decision} that merely LOOKS like the canonical pass-through:
     * equal in every field an agent reads, unequal by {@code equals}, which
     * quietly falsifies "no fire ⇒ exact pass-through" for anyone who later
     * relies on it.
     */
    @Test
    public void blankViewpoint_isTheSamePassThroughAsAbsent() {
        // HH geometry — feasible, so the certificate does NOT fire.
        double hhArea = 1460.0 * 1965.0;
        double hhBox = 147.3;
        for (String blank : new String[] {null, "", "   "}) {
            SpacingPreconditionInfeasibilityCertificate.Decision d =
                    SpacingPreconditionInfeasibilityCertificate.evaluate(
                            23, hhArea, hhBox, 161.5, null, null, null, blank);
            assertEquals("[" + String.valueOf(blank) + "] a view with no "
                            + "viewpoint must yield the canonical pass-through",
                    SpacingPreconditionInfeasibilityCertificate.Decision
                            .proceed(), d);
            assertNull("[" + String.valueOf(blank) + "] absent must have ONE "
                            + "representation downstream, so the loop's "
                            + "Request never receives a blank string",
                    d.viewpointType());
        }
        // ...and on the FIRING path too, where the value is also carried.
        SpacingPreconditionInfeasibilityCertificate.Decision fired =
                SpacingPreconditionInfeasibilityCertificate.evaluate(
                        23, ST_AREA, ST_AVG_BOX, 60.0, 214, 68, 7, "");
        assertTrue(fired.shortCircuit());
        assertNull("the firing Decision must not carry a blank viewpoint "
                + "either", fired.viewpointType());
    }

    /** A real viewpoint id still rides through to the loop untouched. */
    @Test
    public void realViewpoint_isCarriedThroughToTheLoopSeam() {
        SpacingPreconditionInfeasibilityCertificate.Decision d =
                SpacingPreconditionInfeasibilityCertificate.evaluate(
                        23, 1460.0 * 1965.0, 147.3, 161.5, null, null, null,
                        "implementation_migration");
        assertFalse("feasible geometry must not fire", d.shortCircuit());
        assertEquals("implementation_migration", d.viewpointType());
    }

    // ==================================================================
    // 2 — The copy: ONE remedy sentence, emitted by BOTH producers.
    // ==================================================================

    /**
     * The load-bearing parity assertion: for the same view class and the same
     * hub availability, the pre-loop offer and the in-loop diagnosis carry
     * the BYTE-SAME order-preserving remedy. Asserted by taking the shared
     * text and requiring both emitted strings to contain it verbatim — so a
     * future edit to one producer's copy cannot silently diverge.
     */
    @Test
    public void bothProducers_emitTheByteSameOrderPreservingRemedy() {
        for (String vp : new String[] {"implementation_migration",
                "migration"}) {
            for (HubExtent hub : new HubExtent[] {UNDERSIZED_HUB,
                    ADEQUATE_HUB, null}) {
                String shared =
                        OrderedAxisRemedy.orderPreservingRemedy(vp, hub);
                assertFalse(shared.isBlank());

                String cert = certOffer(vp, hub);
                assertNotNull(cert);
                assertTrue("[" + vp + ", hub=" + hub + "] the pre-loop "
                                + "offer must carry the shared remedy "
                                + "verbatim: " + cert,
                        cert.contains(shared));

                String loop = loopDiagnosis(vp, hub);
                assertNotNull(loop);
                assertTrue("[" + vp + ", hub=" + hub + "] the in-loop "
                                + "diagnosis must carry the SAME shared "
                                + "remedy verbatim: " + loop,
                        loop.contains(shared));
            }
        }
    }

    /**
     * The remedy names the view class it is protecting, and the interpolated
     * id is always a real one: the branch renders only for a value that is IN
     * the set, so there is no absent-id case that could emit "(null)" or
     * "()". Read as a whole string, not probed with contains() for a single
     * token — a grammatical or arithmetic contradiction in copy is invisible
     * to contains().
     */
    @Test
    public void remedy_interpolatesARealViewpointId_neverNullOrEmpty() {
        for (String vp : new String[] {"implementation_migration",
                "migration", "  Implementation_Migration  "}) {
            for (HubExtent hub : new HubExtent[] {UNDERSIZED_HUB,
                    ADEQUATE_HUB, null}) {
                String s = OrderedAxisRemedy.orderPreservingRemedy(vp, hub);
                assertFalse("no null leakage: " + s, s.contains("null"));
                assertFalse("no empty parenthetical: " + s, s.contains("()"));
                assertTrue("must name the viewpoint that makes order "
                                + "meaningful: " + s,
                        s.contains("(" + vp.trim() + ")"));
                assertTrue("must still offer an actionable next step: " + s,
                        s.contains("OFFERED"));
                assertTrue("must offer the axis-preserving grow: " + s,
                        s.contains("grow the view along its ordered axis"));
                assertFalse("must never offer the reordering re-layout: " + s,
                        s.contains("re-layout this view with a structural "
                                + "auto-layout"));
            }
        }
    }

    /**
     * The intro must AGREE with the number of remedies it then emits. With a
     * hub there are two and the plural intro is correct; with no hub there is
     * exactly one, and promising "steps ... both of which" while emitting a
     * single item reads as truncated output to the agent consuming it.
     */
    @Test
    public void remedy_itemCountAgreesWithItsIntro_atBothProducers() {
        String plural = OrderedAxisRemedy.orderPreservingRemedy(
                "implementation_migration", UNDERSIZED_HUB);
        assertTrue(plural.contains("OFFERED next steps (each requires your "
                + "consent), both of which preserve the existing element "
                + "order:"));
        assertTrue("plural intro must deliver (1)", plural.contains("(1)"));
        assertTrue("plural intro must deliver (2)", plural.contains("(2)"));

        String singular = OrderedAxisRemedy.orderPreservingRemedy(
                "implementation_migration", /*dominantHub=*/ null);
        assertTrue(singular.contains("OFFERED next step (requires your "
                + "consent), which preserves the existing element order:"));
        assertFalse("must not dangle a (2) it never emits",
                singular.contains("(2)"));
        assertFalse("must not promise 'both' and deliver one",
                singular.toLowerCase().contains("both"));

        // ...and the same must hold through BOTH producers' rendered output,
        // where the hub sentence that remedy (1) refers to actually lives.
        assertTrue(certOffer("implementation_migration", UNDERSIZED_HUB)
                .contains("(2)"));
        assertFalse(certOffer("implementation_migration", null)
                .contains("(2)"));
        assertTrue(loopDiagnosis("implementation_migration", UNDERSIZED_HUB)
                .contains("(2)"));
        assertFalse(loopDiagnosis("implementation_migration", null)
                .contains("(2)"));
    }

    /**
     * The gating that matters: a hub that ALREADY meets its fan-out minimum
     * must not be handed an "enlarge the dominant hub" remedy. The offer is
     * the agent's only ground truth — it cannot see the canvas — so an
     * inapplicable remedy is not a harmless extra suggestion: the agent
     * enlarges the hub, re-measures, finds nothing improved, and has no way
     * to learn that the remedy never applied to its view.
     *
     * <p>This also keeps ONE message self-consistent. The in-loop diagnosis
     * declines to call an adequate hub under-sized in its hub sentence; it
     * must not then tell the reader to enlarge it two sentences later.
     */
    @Test
    public void remedy_offersHubEnlargement_onlyWhenTheHubIsUnderSized() {
        // Same viewpoint, same producer, hub adequacy the ONLY variable.
        String underSized = OrderedAxisRemedy.orderPreservingRemedy(
                "implementation_migration", UNDERSIZED_HUB);
        String adequate = OrderedAxisRemedy.orderPreservingRemedy(
                "implementation_migration", ADEQUATE_HUB);

        assertTrue("an under-sized hub makes enlargement a real remedy: "
                        + underSized,
                underSized.contains("enlarge the dominant hub"));
        assertFalse("an ADEQUATE hub must not be handed an enlarge remedy: "
                        + adequate,
                adequate.contains("enlarge the dominant hub"));

        // ...and the intro must follow the item count down with it.
        assertFalse("adequate hub ⇒ one remedy ⇒ no dangling (2): " + adequate,
                adequate.contains("(2)"));
        assertFalse("adequate hub ⇒ must not promise 'both': " + adequate,
                adequate.toLowerCase().contains("both"));
        assertTrue("adequate hub ⇒ singular intro: " + adequate,
                adequate.contains("OFFERED next step (requires your "
                        + "consent), which preserves the existing element "
                        + "order:"));
        // The order-preserving remedy itself survives either way — gating
        // remedy (1) must not take remedy (2) with it.
        assertTrue(adequate.contains("grow the view along its ordered axis"));
        assertTrue(underSized.contains("grow the view along its ordered "
                + "axis"));

        // Both producers agree, through their rendered output.
        assertFalse("cert: adequate hub ⇒ no enlarge remedy",
                certOffer("implementation_migration", ADEQUATE_HUB)
                        .contains("enlarge the dominant hub"));
        assertFalse("loop: adequate hub ⇒ no enlarge remedy",
                loopDiagnosis("implementation_migration", ADEQUATE_HUB)
                        .contains("enlarge the dominant hub"));
        // ...while still naming the hub extent as a measured fact.
        assertTrue(loopDiagnosis("implementation_migration", ADEQUATE_HUB)
                .contains("500x340px absorbing 12 connections"));
    }
}
