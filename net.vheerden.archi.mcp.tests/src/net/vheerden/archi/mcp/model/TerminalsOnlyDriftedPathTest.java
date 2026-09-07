package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Pins the geometry contract of the terminals-only routing mode, which is the one place a
 * reconstructed bendpoint is not merely reported but <strong>written back to the model</strong>.
 *
 * <p>That mode reads a connection's stored path, rectifies its terminal segments, and commits the
 * result. Reading is the same reconstruction a response performs, so the frame that reconstruction
 * uses is an input to a mutation and not only to a report. Three consequences follow, all of them
 * measured here against real fixture geometry rather than argued:
 *
 * <ul>
 *   <li>the rectifier's own "already orthogonal" decision is taken on the reconstructed path, so the
 *       frame can decide whether a connection is rewritten at all;</li>
 *   <li>interior bendpoints are copied through verbatim and committed with the rest, so they are
 *       rewritten at the reconstructed position even when only a terminal was rectified;</li>
 *   <li>writing back derives both stored offsets from one absolute point, so a connection that was
 *       drifted comes out of the pass with its two reconstructions agreeing — its drift is healed as
 *       a side effect of being rectified for an unrelated reason.</li>
 * </ul>
 *
 * <p>None of that was covered. The rectifier itself is well tested on hand-built paths, but nothing
 * exercised the composition — reconstruct, rectify, write back — and nothing exercised it on a
 * connection whose anchors disagree, because until the drifted fixture existed the corpus had none.
 *
 * <p>What this class does NOT cover: the surrounding model plumbing — collecting the connections,
 * the obstacle, crossing, interior-termination and zigzag vetoes, and command dispatch. Those need a
 * model and a display. What is pinned here is the geometry that plumbing carries.
 */
public class TerminalsOnlyDriftedPathTest {

    private static final String FIXTURE = "testdata/retail-bank-drifted-anchors-fixture.json";

    /** One connection's stored geometry, resolved the way the routing mode resolves it. */
    private record Subject(String source, String target, int bendpointCount, int drift,
                           int srcCentreX, int srcCentreY, int tgtCentreX, int tgtCentreY,
                           List<BendpointDto> relative,
                           List<AbsoluteBendpointDto> reconstructed,
                           List<AbsoluteBendpointDto> flatlyReconstructed,
                           RoutingRect srcRect, RoutingRect tgtRect) {}

    private static List<Subject> subjects() throws IOException {
        ViewFixture fixture = ViewFixture.load(FIXTURE);
        Map<String, ViewFixture.FixtureElement> byId = new LinkedHashMap<>();
        for (ViewFixture.FixtureElement e : fixture.getElements()) {
            byId.put(e.id(), e);
        }

        List<Subject> out = new ArrayList<>();
        for (ViewFixture.FixtureConnection conn : fixture.getConnections()) {
            if (conn.bendpoints().isEmpty()) continue;
            ViewFixture.FixtureElement src = byId.get(conn.sourceId());
            ViewFixture.FixtureElement tgt = byId.get(conn.targetId());

            // Centres and rectangles exactly as the routing mode derives them: whole-pixel centres,
            // and the rectangle rebuilt back out from the centre so the two cannot disagree.
            int scx = src.x() + src.w() / 2;
            int scy = src.y() + src.h() / 2;
            int tcx = tgt.x() + tgt.w() / 2;
            int tcy = tgt.y() + tgt.h() / 2;

            List<BendpointDto> relative = new ArrayList<>();
            int drift = 0;
            for (ViewFixture.FixtureBendpoint bp : conn.bendpoints()) {
                relative.add(new BendpointDto(bp.startX(), bp.startY(), bp.endX(), bp.endY()));
                drift = Math.max(drift, Math.max(
                        Math.abs((bp.startX() + scx) - (bp.endX() + tcx)),
                        Math.abs((bp.startY() + scy) - (bp.endY() + tcy))));
            }

            // A model of the arithmetic this project used before the render weight was adopted.
            // It no longer exists in production, so it has to be restated to compare against.
            List<AbsoluteBendpointDto> flat = new ArrayList<>();
            for (BendpointDto bp : relative) {
                flat.add(new AbsoluteBendpointDto(
                        (bp.startX() + scx + bp.endX() + tcx) / 2,
                        (bp.startY() + scy + bp.endY() + tcy) / 2));
            }

            out.add(new Subject(src.name(), tgt.name(), relative.size(), drift,
                    scx, scy, tcx, tcy, relative,
                    ConnectionResponseBuilder.convertRelativeToAbsolute(relative, scx, scy, tcx, tcy),
                    flat,
                    new RoutingRect(scx - src.w() / 2, scy - src.h() / 2, src.w(), src.h(), src.id()),
                    new RoutingRect(tcx - tgt.w() / 2, tcy - tgt.h() / 2, tgt.w(), tgt.h(), tgt.id())));
        }
        return out;
    }

    private static List<AbsoluteBendpointDto> rectify(Subject s, List<AbsoluteBendpointDto> from) {
        return RoutingPipeline.terminalsOnlyRectifyAndClearEgress(s.srcRect(), s.tgtRect(), from);
    }

    private static int driftOf(List<BendpointDto> relative, Subject s) {
        int drift = 0;
        for (BendpointDto bp : relative) {
            drift = Math.max(drift, Math.max(
                    Math.abs((bp.startX() + s.srcCentreX()) - (bp.endX() + s.tgtCentreX())),
                    Math.abs((bp.startY() + s.srcCentreY()) - (bp.endY() + s.tgtCentreY()))));
        }
        return drift;
    }

    // ------------------------------------------------------------------ the heal

    /**
     * A connection that survives this pass comes out undrifted, whatever it went in as.
     *
     * <p>Writing back converts each absolute point into both stored offsets, and those two agree by
     * construction. So the pass silently repairs the disagreement on every connection it rewrites —
     * including connections it rewrote for a reason having nothing to do with drift. Four of the
     * fixture's seven drifted connections are rewritten and all four come out at zero.
     *
     * <p>This is a real and previously undeclared side effect: {@code anchorDriftCount} falls after a
     * terminals-only pass, on connections nobody asked to have re-anchored. It is pinned rather than
     * fixed because the direction is repair — the stored form ends up agreeing with what is drawn.
     */
    @Test
    public void shouldHealTheStoredDrift_wheneverAPathIsWrittenBack() throws IOException {
        int written = 0;
        int driftedAndWritten = 0;
        for (Subject s : subjects()) {
            List<AbsoluteBendpointDto> rectified = rectify(s, s.reconstructed());
            if (rectified == null) continue;              // nothing committed for this connection
            written++;

            List<BendpointDto> writtenBack = ConnectionResponseBuilder.convertAbsoluteToRelative(
                    rectified, s.srcCentreX(), s.srcCentreY(), s.tgtCentreX(), s.tgtCentreY());

            assertEquals("a committed path must leave the two stored reconstructions agreeing — "
                            + s.source() + " -> " + s.target(),
                    0, driftOf(writtenBack, s));

            if (s.drift() > 0) {
                driftedAndWritten++;
            }
        }
        assertEquals("connections the pass commits", 8, written);
        assertEquals("drifted connections whose drift the pass silently repairs", 4, driftedAndWritten);
    }

    /**
     * The heal does not move a reported coordinate, and moves the drawn line by less than a pixel.
     *
     * <p>Two different claims, and they are not the same claim. A bendpoint that passes through the
     * rectifier untouched is committed at exactly the integer it was reconstructed to, so reading it
     * back afterwards returns that same integer — the <em>reported</em> value is unchanged exactly,
     * not approximately. What the renderer draws does move: before the heal it interpolates the two
     * disagreeing reconstructions at full precision and lands on a fraction; afterwards both offsets
     * name one integer and it lands on that. The gap is the single truncation, strictly under a
     * pixel, and it is the only thing about this pass a reader could notice.
     *
     * <p>It was not always under a pixel. Reconstructing at a flat one half and then committing that
     * point healed the connection onto the <em>midpoint</em> — a position the renderer never drew —
     * so the line was yanked by the whole shear. On this fixture that is <strong>15 px</strong>,
     * which the mutation of the blend reproduces exactly. Reading at the render weight means the
     * pass now heals a connection onto the line it is already drawn along, and this assertion is
     * what holds it there.
     */
    @Test
    public void shouldNotMoveAReportedCoordinate_andShouldMoveTheDrawnLineByUnderAPixel()
            throws IOException {
        int passThroughPoints = 0;
        double worstDrawnShift = 0.0;
        for (Subject s : subjects()) {
            List<AbsoluteBendpointDto> rectified = rectify(s, s.reconstructed());
            if (rectified == null) continue;

            List<BendpointDto> writtenBack = ConnectionResponseBuilder.convertAbsoluteToRelative(
                    rectified, s.srcCentreX(), s.srcCentreY(), s.tgtCentreX(), s.tgtCentreY());
            List<AbsoluteBendpointDto> rereported = ConnectionResponseBuilder
                    .convertRelativeToAbsolute(writtenBack, s.srcCentreX(), s.srcCentreY(),
                            s.tgtCentreX(), s.tgtCentreY());

            for (int i = 0; i < rectified.size(); i++) {
                assertEquals("committing then re-reading must return the committed point",
                        rectified.get(i), rereported.get(i));
            }

            // For every input point the rectifier passed through unchanged, compare what the
            // renderer drew before the heal against what it draws after.
            int n = s.bendpointCount();
            for (int i = 0; i < n; i++) {
                AbsoluteBendpointDto before = s.reconstructed().get(i);
                if (!rectified.contains(before)) continue;
                passThroughPoints++;

                BendpointDto bp = s.relative().get(i);
                double weight = (i + 1.0) / (n + 1.0);
                double drawnBeforeX = (bp.startX() + s.srcCentreX()) * (1.0 - weight)
                        + (bp.endX() + s.tgtCentreX()) * weight;
                double drawnBeforeY = (bp.startY() + s.srcCentreY()) * (1.0 - weight)
                        + (bp.endY() + s.tgtCentreY()) * weight;
                worstDrawnShift = Math.max(worstDrawnShift, Math.max(
                        Math.abs(drawnBeforeX - before.x()), Math.abs(drawnBeforeY - before.y())));
            }
        }
        assertTrue("the sweep must actually reach pass-through points", passThroughPoints > 0);
        assertTrue("the drawn line must not jump a whole pixel, but moved " + worstDrawnShift,
                worstDrawnShift < 1.0);
    }

    // ------------------------------------------------------------------ the decision

    /**
     * The frame decides whether a connection is rewritten at all — on exactly one of the fixture's
     * connections, and in the conservative direction.
     *
     * <p>The rectifier judges "already orthogonal" from the first and last reconstructed points. On a
     * drifted connection those two are precisely where the frame moves things most, because the shear
     * is largest at the terminals. So the reconstruction is an input to a mutation decision, not only
     * to a report.
     *
     * <p>Measured: one connection changes verdict, and it changes from rewrite to leave-alone. The
     * pass touches strictly less geometry under the render weight than it did under a flat blend,
     * which is the direction to prefer — but the count is pinned in both directions so a future
     * change that makes it touch more has to come here and say so.
     */
    @Test
    public void shouldDecideWhetherToRewriteFromTheReconstructedPath_flippingOneRealConnection()
            throws IOException {
        int flippedToNoOp = 0;
        int flippedToWrite = 0;
        String flippedConnection = null;
        for (Subject s : subjects()) {
            boolean writesNow = rectify(s, s.reconstructed()) != null;
            boolean wroteFlat = rectify(s, s.flatlyReconstructed()) != null;
            if (writesNow == wroteFlat) continue;
            if (wroteFlat) {
                flippedToNoOp++;
                flippedConnection = s.source() + " -> " + s.target();
                assertTrue("only a drifted connection can change verdict — the two frames agree "
                        + "wherever the anchors do", s.drift() > 0);
            } else {
                flippedToWrite++;
            }
        }
        assertEquals("connections the render weight now leaves alone that a flat blend rewrote",
                1, flippedToNoOp);
        assertEquals("connections it now rewrites that a flat blend left alone", 0, flippedToWrite);
        assertEquals("Digital Experience API (BFF) -> Enterprise API Gateway", flippedConnection);
    }

    /**
     * The frame also changes the SHAPE of what gets written, not only whether anything is.
     *
     * <p>One connection gains a bendpoint it would not have gained: reconstructed at the render
     * weight its terminal reads as non-orthogonal, so the rectifier inserts an L-bend, where the flat
     * blend produced a terminal it accepted. A connection can therefore leave this pass with a
     * different number of stored bendpoints purely because of how its existing path was read.
     */
    @Test
    public void shouldChangeTheWrittenShape_onOneConnection() throws IOException {
        int shapeChanged = 0;
        String changed = null;
        for (Subject s : subjects()) {
            List<AbsoluteBendpointDto> now = rectify(s, s.reconstructed());
            List<AbsoluteBendpointDto> flat = rectify(s, s.flatlyReconstructed());
            if (now == null || flat == null) continue;
            if (now.size() == flat.size()) continue;
            shapeChanged++;
            changed = s.source() + " -> " + s.target();
            assertEquals("the render weight inserts the extra bend, not the flat blend",
                    flat.size() + 1, now.size());
            assertTrue("only a drifted connection can change shape", s.drift() > 0);
        }
        assertEquals("connections committed with a different bendpoint count", 1, shapeChanged);
        assertEquals("Open Banking API Platform -> Enterprise API Gateway", changed);
    }

    /**
     * An undrifted connection is decided and shaped identically under either frame.
     *
     * <p>The control. Where the two reconstructions agree every weight returns the same point, so
     * nothing about this pass can depend on the frame — which is why the router-written corpus could
     * never have surfaced any of the three findings above.
     */
    @Test
    public void shouldBehaveIdenticallyUnderEitherFrame_whenTheAnchorsAgree() throws IOException {
        int undrifted = 0;
        for (Subject s : subjects()) {
            if (s.drift() != 0) continue;
            undrifted++;
            assertEquals("an undrifted path reconstructs the same way under either frame",
                    s.flatlyReconstructed(), s.reconstructed());

            List<AbsoluteBendpointDto> now = rectify(s, s.reconstructed());
            List<AbsoluteBendpointDto> flat = rectify(s, s.flatlyReconstructed());
            assertEquals("and is therefore committed, or not, identically",
                    now == null, flat == null);
            if (now != null) {
                assertEquals("with identical geometry", flat, now);
            }
        }
        assertEquals("undrifted controls in the fixture", 7, undrifted);
    }

    /**
     * The fixture still carries what these tests need. A pin on the subject, so a later edit that
     * drains the drifted population cannot leave the tests above passing vacuously.
     */
    @Test
    public void shouldKeepTheFixtureAbleToExerciseThisPath() throws IOException {
        List<Subject> subjects = subjects();
        assertEquals("bent connections", 14, subjects.size());
        assertEquals("drifted connections", 7,
                subjects.stream().filter(s -> s.drift() > 0).count());
        assertNotEquals("and at least one of them carries more than two bendpoints", 0,
                subjects.stream().filter(s -> s.drift() > 0 && s.bendpointCount() > 2).count());
    }
}
