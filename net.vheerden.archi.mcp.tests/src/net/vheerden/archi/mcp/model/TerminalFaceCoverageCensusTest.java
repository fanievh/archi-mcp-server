package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelGroup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Measures how often a connection's stored terminal bendpoint could name the element face it
 * attaches to, on the read path, over the view fixture corpus.
 *
 * <p><strong>What this census exists to answer.</strong> A connection response could carry the
 * element face each end attaches to, derived server-side from untruncated bounds. Such a field is
 * worth a shipped record component only if it is usually present. This class measures the
 * presence rate before any such field is built, and the answer it records is <em>zero</em>: over
 * every terminal in the corpus that has stored geometry at all, not one sits on a face line.
 *
 * <p><strong>The premise the measurement refutes.</strong> The derivation assumes
 * {@code absoluteBendpoints[0]} and {@code [n - 1]} <em>are</em> the connection's attachment
 * points, so that testing them against the element box names a face. That holds only for geometry
 * the router wrote: {@code EdgeAttachmentCalculator.computeAttachmentPoint} emits the attachment
 * point one pixel outside the edge and the routing pipeline stores it as the outermost bendpoint,
 * which is why the routing-frame censuses in {@code RoutingComparisonTest} find almost every routed
 * terminal on a face line. A bendpoint that a person or an importer placed carries no such
 * meaning — it is an intermediate waypoint, and the point where the line actually meets the
 * element is the {@code ChopboxAnchor} intersection the renderer computes, which no stored value
 * names. The distance floor pinned below separates the two populations: a routed terminal is on
 * the line exactly, and the nearest stored terminal in this corpus is six pixels off it.
 *
 * <p><strong>Frame.</strong> Everything here is the reporting frame over <em>stored</em> input:
 * whole-pixel centres and the exact integer interpolation a connection response reports, driven
 * through the production {@code ConnectionResponseBuilder}. It is deliberately not the routing
 * frame — the censuses in {@code RoutingComparisonTest} route the corpus fresh and measure the
 * router's own output, a different population that does not convert into this one.
 *
 * <p><strong>Two denominators, and both are pinned.</strong> The census runs twice. Over all seven
 * view fixtures it examines 380 terminals; over the three that actually record connection
 * bendpoints it examines 138. Neither run names a single face, so the headline does not depend on
 * the choice — but the two numbers mean different things and only one of them is a measurement of
 * anything. The four excluded fixtures predate the {@code bendpoints} field and carry no such key
 * on any connection, and {@code ViewFixture} maps an absent key to an empty list, which is
 * indistinguishable from a connection that genuinely has none. Their 242 abstentions therefore
 * describe the capture format rather than any view. Both runs are pinned so that the full
 * denominator is measured rather than argued, and the narrower one carries the meaning.
 *
 * <p>Nothing here needs a display: every quantity is a pure function of the fixture coordinates and
 * the stored offsets, apart from one equivalence pin that builds a bare EMF object to hold bounds.
 */
public class TerminalFaceCoverageCensusTest {

    /** Every view fixture, in a fixed order so the full-denominator census reads as one table. */
    private static final String[] ALL_FIXTURES = {
        "app-architecture-view",
        "v4-integration-architecture-oracle",
        "hh-source-clone",
        "st-source-clone",
        "retail-bank-business-architecture",
        "retail-bank-application-collaboration",
        "retail-bank-drifted-anchors",
    };

    /**
     * The fixtures that record stored connection geometry — the meaningful denominator. The other
     * four carry no {@code bendpoints} key on any connection, asserted by
     * {@link #shouldCarryNoBendpointsKeyAtAll_whenAFixturePredatesTheField}.
     */
    private static final String[] CAPTURING_FIXTURES = {
        "retail-bank-business-architecture",
        "retail-bank-application-collaboration",
        "retail-bank-drifted-anchors",
    };

    /** The view fixtures excluded from the meaningful denominator. */
    private static final String[] NON_CAPTURING_FIXTURES = {
        "app-architecture-view",
        "v4-integration-architecture-oracle",
        "hh-source-clone",
        "st-source-clone",
    };

    // ------------------------------------------------------------------ census outcome vocabulary

    private static final String FACE_TOP = "face-top";
    private static final String FACE_BOTTOM = "face-bottom";
    private static final String FACE_LEFT = "face-left";
    private static final String FACE_RIGHT = "face-right";
    private static final String NO_BENDPOINTS = "abstain-no-bendpoints";
    private static final String OFF_EVERY_FACE_LINE = "abstain-off-every-face-line";
    private static final String PAST_FACE_EXTENT = "abstain-past-face-extent";
    private static final String CORNER = "abstain-corner";
    private static final String UNREADABLE_BOUNDS = "abstain-unreadable-bounds";

    /**
     * The four face lines of an element, one pixel outside each edge, as
     * {@code {left, right, top, bottom}}.
     *
     * <p>Shared by the classifier and the distance floor so the two cannot drift apart. They are
     * the same four numbers asked two different questions — which line a point is on, and how far
     * it is from the nearest — and deriving them twice would let a change to one silently
     * desynchronise the other.
     */
    private static int[] faceLines(ViewFixture.FixtureElement e) {
        return new int[] {
            e.x() - 1,
            e.x() + e.w() + 1,
            e.y() - 1,
            e.y() + e.h() + 1,
        };
    }

    /**
     * Classifies one candidate terminal against the element it attaches to.
     *
     * <p>The four equality comparisons are the shape of
     * {@code RoutingPipeline.determineFaceFromTerminal}: the router places a terminal one pixel
     * outside the edge, so a terminal on a face <em>line</em> satisfies exactly one of them. Two
     * things are added that a reporting field needs and the router's own predicate does not have.
     *
     * <p>An <strong>extent clamp</strong>: the router's guard is an or over four infinite lines
     * with no bound on either axis, so a point on the left face line but far above the element
     * passes it and is named {@code LEFT}. Published as a structured fact that would be a
     * measurement-shaped guess, so a point past the face's own extent abstains here. The extent
     * runs corner to corner inclusive, the same bounds {@code RoutingComparisonTest} measures its
     * past-extent population against, so the two frames' notions of "past the extent" agree.
     *
     * <p>A <strong>corner abstention</strong>: a point on one vertical and one horizontal face line
     * has two true answers and nothing in its own position chooses between them. The router
     * resolves the ambiguity vertical-first for geometry continuity, which is right for a consumer
     * that must build a segment; a published fact cannot pick arbitrarily between two true answers,
     * so it declines. This is the one place the two frames deliberately differ.
     */
    private static String classify(int px, int py, ViewFixture.FixtureElement e) {
        int[] lines = faceLines(e);
        int left = lines[0];
        int right = lines[1];
        int top = lines[2];
        int bottom = lines[3];
        boolean onVertical = px == left || px == right;
        boolean onHorizontal = py == top || py == bottom;
        if (!onVertical && !onHorizontal) {
            return OFF_EVERY_FACE_LINE;
        }
        if (onVertical && onHorizontal) {
            return CORNER;
        }
        if (onVertical) {
            if (py < top || py > bottom) {
                return PAST_FACE_EXTENT;
            }
            return px == left ? FACE_LEFT : FACE_RIGHT;
        }
        if (px < left || px > right) {
            return PAST_FACE_EXTENT;
        }
        return py == top ? FACE_TOP : FACE_BOTTOM;
    }

    /**
     * The whole-pixel centre a connection response anchors on, for a fixture element.
     *
     * <p>Fixture coordinates are already absolute, including nested children, so there is no parent
     * chain to accumulate here — this is {@code ConnectionResponseBuilder.computeAbsoluteCenter}
     * with the walk already done. The truncating division is the load-bearing part and is kept
     * exactly as the builder has it: the reference point is Archi's own {@code ChopboxAnchor}
     * centre, {@code x + width / 2} on integer division.
     *
     * <p>That makes this a restatement of production arithmetic, which a census must not simply
     * assert. {@link #shouldAgreeWithTheProductionCentre_whenAnElementHasNoParentChain} drives the
     * real method against this one over the whole corpus, so the restatement cannot outlive the
     * behaviour it names.
     */
    private static int[] centre(ViewFixture.FixtureElement e) {
        return new int[] { e.x() + e.w() / 2, e.y() + e.h() / 2 };
    }

    /** Distance from a point to the nearest of the element's four face lines. */
    private static int distanceToNearestFaceLine(int px, int py, ViewFixture.FixtureElement e) {
        int[] lines = faceLines(e);
        int toLeft = Math.abs(px - lines[0]);
        int toRight = Math.abs(px - lines[1]);
        int toTop = Math.abs(py - lines[2]);
        int toBottom = Math.abs(py - lines[3]);
        return Math.min(Math.min(toLeft, toRight), Math.min(toTop, toBottom));
    }

    /**
     * One census run's outcome tallies, plus the distance floor over the terminals that exist.
     *
     * @param disagreeingPoints terminals where the two reconstructions resolve to different
     *     coordinates
     * @param disagreeingOutcomes terminals where the two reconstructions resolve to a different
     *     <em>classification</em> — a strictly stronger question than the coordinate one, and the
     *     one the frame-agreement claim actually rests on
     */
    private record Census(Map<String, Integer> p1, Map<String, Integer> p2,
                          int disagreeingPoints, int disagreeingOutcomes,
                          int nearestFaceLineDistance, int terminals) {}

    /**
     * Runs the census over the named fixtures, classifying every terminal under both candidate
     * reconstructions of the stored bendpoint.
     *
     * <p>Archi stores each bendpoint twice, as an offset from the source centre and as an offset
     * from the target centre. Two candidate points therefore exist for a terminal, and they are the
     * same point only while the two reconstructions agree:
     *
     * <ul>
     *   <li><strong>P1</strong> — the published terminal, the value a caller already holds in
     *       {@code absoluteBendpoints}. Produced here by the production
     *       {@code ConnectionResponseBuilder.convertRelativeToAbsolute}, not by a restatement of
     *       it, so the census cannot drift from what a response reports.</li>
     *   <li><strong>P2</strong> — the endpoint-anchored reconstruction, the frame the attachment
     *       was written in, which moves with its own element and can still name a face once an
     *       endpoint has been moved or resized. It is a point no response contains.</li>
     * </ul>
     */
    private Census runCensus(String[] fixtures) throws IOException {
        Map<String, Integer> p1 = new LinkedHashMap<>();
        Map<String, Integer> p2 = new LinkedHashMap<>();
        int disagreeingPoints = 0;
        int disagreeingOutcomes = 0;
        int nearest = Integer.MAX_VALUE;
        int terminals = 0;

        for (String fixture : fixtures) {
            ViewFixture fx = ViewFixture.load("testdata/" + fixture + "-fixture.json");
            for (ViewFixture.FixtureConnection conn : fx.getConnections()) {
                ViewFixture.FixtureElement src = fx.getElementById(conn.sourceId());
                ViewFixture.FixtureElement tgt = fx.getElementById(conn.targetId());
                for (boolean sourceEnd : new boolean[] { true, false }) {
                    terminals++;
                    ViewFixture.FixtureElement own = sourceEnd ? src : tgt;
                    if (own == null || src == null || tgt == null) {
                        tally(p1, UNREADABLE_BOUNDS);
                        tally(p2, UNREADABLE_BOUNDS);
                        continue;
                    }
                    List<ViewFixture.FixtureBendpoint> bps = conn.bendpoints();
                    if (bps.isEmpty()) {
                        tally(p1, NO_BENDPOINTS);
                        tally(p2, NO_BENDPOINTS);
                        continue;
                    }

                    int[] srcCentre = centre(src);
                    int[] tgtCentre = centre(tgt);
                    int index = sourceEnd ? 0 : bps.size() - 1;
                    ViewFixture.FixtureBendpoint bp = bps.get(index);

                    List<AbsoluteBendpointDto> absolute =
                            ConnectionResponseBuilder.convertRelativeToAbsolute(
                                    toDtos(bps), srcCentre[0], srcCentre[1],
                                    tgtCentre[0], tgtCentre[1]);
                    AbsoluteBendpointDto published = absolute.get(index);

                    int anchoredX = sourceEnd ? srcCentre[0] + bp.startX() : tgtCentre[0] + bp.endX();
                    int anchoredY = sourceEnd ? srcCentre[1] + bp.startY() : tgtCentre[1] + bp.endY();

                    String p1Outcome = classify(published.x(), published.y(), own);
                    String p2Outcome = classify(anchoredX, anchoredY, own);
                    tally(p1, p1Outcome);
                    tally(p2, p2Outcome);
                    if (published.x() != anchoredX || published.y() != anchoredY) {
                        disagreeingPoints++;
                    }
                    if (!p1Outcome.equals(p2Outcome)) {
                        disagreeingOutcomes++;
                    }
                    nearest = Math.min(nearest,
                            distanceToNearestFaceLine(anchoredX, anchoredY, own));
                    nearest = Math.min(nearest,
                            distanceToNearestFaceLine(published.x(), published.y(), own));
                }
            }
        }
        return new Census(p1, p2, disagreeingPoints, disagreeingOutcomes, nearest, terminals);
    }

    private static void tally(Map<String, Integer> counts, String outcome) {
        counts.merge(outcome, 1, Integer::sum);
    }

    private static List<BendpointDto> toDtos(List<ViewFixture.FixtureBendpoint> bps) {
        List<BendpointDto> dtos = new ArrayList<>(bps.size());
        for (ViewFixture.FixtureBendpoint bp : bps) {
            dtos.add(new BendpointDto(bp.startX(), bp.startY(), bp.endX(), bp.endY()));
        }
        return dtos;
    }

    private static int faces(Map<String, Integer> counts) {
        return counts.getOrDefault(FACE_TOP, 0) + counts.getOrDefault(FACE_BOTTOM, 0)
                + counts.getOrDefault(FACE_LEFT, 0) + counts.getOrDefault(FACE_RIGHT, 0);
    }

    private static int sum(Map<String, Integer> counts) {
        int total = 0;
        for (int v : counts.values()) {
            total += v;
        }
        return total;
    }

    // ------------------------------------------------------------------------------- the census

    /**
     * The read-path coverage table over the fixtures that record stored geometry, and the reason a
     * terminal-face field was not built.
     *
     * <p>Over 138 terminals — every end of every connection in the three fixtures that record
     * stored geometry — <strong>neither candidate reconstruction names a single face</strong>. The
     * table is identical for both, and the two abstention reasons partition it:
     *
     * <table>
     *   <caption>Terminal face coverage on the read path, stored input, reporting frame</caption>
     *   <tr><th>outcome</th><th>P1 published terminal</th><th>P2 endpoint-anchored</th></tr>
     *   <tr><td>any face named</td><td>0</td><td>0</td></tr>
     *   <tr><td>abstain, connection has no bendpoints</td><td>50</td><td>50</td></tr>
     *   <tr><td>abstain, terminal on no face line</td><td>88</td><td>88</td></tr>
     *   <tr><td>abstain, past the face extent</td><td>0</td><td>0</td></tr>
     *   <tr><td>abstain, corner</td><td>0</td><td>0</td></tr>
     * </table>
     *
     * <p>Red here means the corpus gained routed or re-authored geometry and the presence rate has
     * moved off zero. That is the event which would reopen the question, so it should be explained
     * beside the new numbers rather than absorbed by editing the expectation.
     */
    @Test
    public void shouldNameNoFaceOnAnyStoredTerminal_whenTheReadPathCorpusIsCensused()
            throws IOException {
        Census census = runCensus(CAPTURING_FIXTURES);

        assertEquals("terminals censused", 138, census.terminals());
        assertEquals("faces named from the published terminal", 0, faces(census.p1()));
        assertEquals("faces named from the endpoint-anchored terminal", 0, faces(census.p2()));

        assertEquals("published-terminal abstentions, no bendpoints",
                Integer.valueOf(50), census.p1().get(NO_BENDPOINTS));
        assertEquals("published-terminal abstentions, off every face line",
                Integer.valueOf(88), census.p1().get(OFF_EVERY_FACE_LINE));
        assertEquals("endpoint-anchored abstentions, no bendpoints",
                Integer.valueOf(50), census.p2().get(NO_BENDPOINTS));
        assertEquals("endpoint-anchored abstentions, off every face line",
                Integer.valueOf(88), census.p2().get(OFF_EVERY_FACE_LINE));

        assertEquals("past-extent abstentions are not what suppresses the field",
                null, census.p1().get(PAST_FACE_EXTENT));
        assertEquals("corner abstentions are not what suppresses the field",
                null, census.p1().get(CORNER));
    }

    /**
     * The same census over the <em>full</em> corpus, so the wider denominator is measured rather
     * than argued.
     *
     * <p>All seven fixtures, 380 terminals, and still <strong>no face on either
     * reconstruction</strong>. The extra 242 terminals all land in {@code abstain-no-bendpoints},
     * which is exactly the point: they come from four fixtures that never captured a
     * {@code bendpoints} key, so they measure the capture format and not any view. Pinning both
     * runs means the narrower denominator is a stated choice about meaning rather than a
     * denominator quietly chosen to suit the result — the result is the same either way.
     */
    @Test
    public void shouldNameNoFaceOnAnyStoredTerminal_whenEveryFixtureIsCensused()
            throws IOException {
        Census census = runCensus(ALL_FIXTURES);

        assertEquals("terminals censused across the whole corpus", 380, census.terminals());
        assertEquals("faces named from the published terminal", 0, faces(census.p1()));
        assertEquals("faces named from the endpoint-anchored terminal", 0, faces(census.p2()));
        assertEquals("the wider denominator adds only bendpoint-free terminals",
                Integer.valueOf(292), census.p1().get(NO_BENDPOINTS));
        assertEquals("and no additional terminal to classify",
                Integer.valueOf(88), census.p1().get(OFF_EVERY_FACE_LINE));
        assertEquals("every terminal is accounted for", 380, sum(census.p1()));
    }

    /**
     * The distance floor that makes the zero above structural rather than a tuning question.
     *
     * <p>A presence rate of zero would be worth re-examining if the terminals were sitting just
     * beside their face lines, because then the predicate — the one-pixel offset, the extent clamp,
     * the corner rule — would be what suppressed the field. They are not. The nearest stored
     * terminal in the corpus is <strong>six pixels</strong> off every face line of its own element,
     * and the median is twenty-eight, so no admissible tolerance recovers a face without inventing
     * one. The stored outermost bendpoint is a waypoint, not an attachment point.
     *
     * <p>Contrast the routing frame, where the same predicate finds almost every terminal on a
     * line: there the router placed the point, exactly one pixel outside the edge, by construction.
     * The two populations are different geometry, which is why the routing censuses cannot stand in
     * for this one.
     */
    @Test
    public void shouldSitFarFromEveryFaceLine_whenAStoredTerminalIsMeasuredAgainstItsElement()
            throws IOException {
        Census census = runCensus(CAPTURING_FIXTURES);
        assertEquals("nearest stored terminal to any face line, in pixels",
                6, census.nearestFaceLineDistance());
        assertTrue("a one-pixel attachment offset cannot explain a six-pixel gap",
                census.nearestFaceLineDistance() > 1);
    }

    /**
     * Pins that only one fixture can tell the two candidate reconstructions apart at all, and that
     * even there they reach the same answer.
     *
     * <p>The two reconstructions describe the same point while both endpoints sit where they sat
     * when the route was written, so a corpus without drift is evidence for neither candidate. Only
     * the drifted-anchors fixture disagrees, at fourteen of its forty terminals.
     *
     * <p>The second assertion compares the <em>classification</em> of every terminal rather than
     * the face totals, because two runs can both name zero faces while disagreeing about why —
     * one landing on a corner where the other lands off every line — and comparing totals would
     * call that agreement.
     *
     * <p><strong>On this corpus that assertion is weak, and saying so is the point.</strong> It was
     * mutation-tested by shifting one candidate a pixel sideways, and it stayed green: every one of
     * the 88 classified terminals is at least six pixels off every face line, so a small
     * displacement cannot move any of them out of {@code abstain-off-every-face-line}. A single
     * shared bucket makes agreement cheap. The check therefore guards a <em>future</em> corpus that
     * has terminals near their face lines, not this one — and the control below proves the
     * comparison can see a divergence at all, so that its zero is a measurement rather than an
     * artefact of the comparison never firing.
     */
    @Test
    public void shouldDisagreeOnlyOnTheDriftedFixture_whenBothReconstructionsAreCompared()
            throws IOException {
        Census census = runCensus(CAPTURING_FIXTURES);
        assertEquals("terminals where the two reconstructions differ", 14,
                census.disagreeingPoints());
        assertEquals("terminals where they reach a different classification", 0,
                census.disagreeingOutcomes());

        // The control for the comparison itself. Two candidate points for the same terminal, one
        // on a face line and one a pixel off it, must classify differently — otherwise the zero
        // above would hold however far apart the two reconstructions drifted.
        ViewFixture.FixtureElement box =
                new ViewFixture.FixtureElement("box", "Box", 400, 300, 120, 55, false, null);
        assertNotEquals("a divergence in classification is detectable",
                classify(399, 330, box), classify(398, 330, box));
        assertEquals(FACE_LEFT, classify(399, 330, box));
        assertEquals(OFF_EVERY_FACE_LINE, classify(398, 330, box));
    }

    /**
     * The positive control for the census: the classifier does name faces, on geometry that has
     * them.
     *
     * <p>A census whose every cell reads zero is indistinguishable from a classifier that can never
     * return a face at all — a broken comparison, a wrong offset sign, a guard that swallows every
     * input — and the zero above would then be measuring this class rather than the corpus. So the
     * classifier is driven here on hand-built points where the answer is known independently: the
     * four attachment points {@code EdgeAttachmentCalculator} would place on a box, one pixel
     * outside each edge and centred on that edge, plus one point of each abstaining shape.
     *
     * <p>The element is deliberately given an odd width and an odd height. That is the population
     * a server-side field would exist for — a caller reconstructing bounds from the truncated
     * centre a response publishes is out by the discarded half pixel on such an element, while the
     * face test is exact integer equality — so the control also demonstrates the advantage the
     * field would have had, on the one case where it is real.
     */
    @Test
    public void shouldNameEachFace_whenATerminalSitsWhereTheRouterWouldAttachIt() {
        ViewFixture.FixtureElement odd =
                new ViewFixture.FixtureElement("odd", "Odd", 100, 200, 181, 55, false, null);
        int midX = 100 + 181 / 2;
        int midY = 200 + 55 / 2;

        assertEquals(FACE_LEFT, classify(100 - 1, midY, odd));
        assertEquals(FACE_RIGHT, classify(100 + 181 + 1, midY, odd));
        assertEquals(FACE_TOP, classify(midX, 200 - 1, odd));
        assertEquals(FACE_BOTTOM, classify(midX, 200 + 55 + 1, odd));

        assertEquals("a point on two face lines has two true answers",
                CORNER, classify(100 - 1, 200 - 1, odd));
        assertEquals("on the left face line, far above the element",
                PAST_FACE_EXTENT, classify(100 - 1, 200 - 60, odd));
        assertEquals("on no face line at all",
                OFF_EVERY_FACE_LINE, classify(midX, midY, odd));

        assertEquals("the whole-pixel centre a response publishes for an odd element",
                190, centre(odd)[0]);
        assertEquals(227, centre(odd)[1]);

        // What a caller reconstructing bounds from that published centre and the published width
        // would test against. The near edge survives the round trip — the same truncation is
        // added and subtracted — but the far edge is short by the discarded half pixel, and the
        // face test is exact integer equality, so a terminal genuinely on the right face line
        // fails the caller's test and reads to it as off every face line.
        int callerRightFaceLine = centre(odd)[0] + 181 / 2 + 1;
        int trueRightFaceLine = 100 + 181 + 1;
        assertEquals("the caller's reconstructed near edge is exact", 100 - 1,
                centre(odd)[0] - 181 / 2 - 1);
        assertEquals("the caller's reconstructed far edge is a pixel short", 281,
                callerRightFaceLine);
        assertEquals(282, trueRightFaceLine);
        assertEquals("and the server, holding the real bounds, names the face the caller misses",
                FACE_RIGHT, classify(trueRightFaceLine, midY, odd));
        assertEquals(OFF_EVERY_FACE_LINE, classify(callerRightFaceLine, midY, odd));
    }

    /**
     * Pins the census's restated centre against the production method it restates.
     *
     * <p>{@link #centre} recomputes what {@code ConnectionResponseBuilder.computeAbsoluteCenter}
     * produces, because the production method takes an EMF view object and the fixtures are plain
     * coordinates. A restatement that is only asserted to match is exactly the shape of test that
     * outlives the behaviour it names, so the real method is driven here over every element of
     * every fixture, against a bare EMF object carrying the same bounds and no parent.
     *
     * <p>The no-parent condition is the precondition that makes the restatement legitimate rather
     * than merely convenient: {@code computeAbsoluteCenter} accumulates the parent chain, and
     * fixture coordinates are already absolute — a nested child in these files sits at coordinates
     * outside its own parent's box, which relative coordinates could not produce. Accumulating a
     * chain over them would double-count. This pin therefore covers the arithmetic and deliberately
     * not the walk; the walk belongs to a test that has a real containment tree.
     */
    @Test
    public void shouldAgreeWithTheProductionCentre_whenAnElementHasNoParentChain()
            throws IOException {
        int checked = 0;
        for (String fixture : ALL_FIXTURES) {
            ViewFixture fx = ViewFixture.load("testdata/" + fixture + "-fixture.json");
            for (ViewFixture.FixtureElement e : fx.getElements()) {
                IDiagramModelGroup obj = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
                obj.setBounds(e.x(), e.y(), e.w(), e.h());
                int[] production = ConnectionResponseBuilder.computeAbsoluteCenter(obj);
                assertEquals("centre x for " + fixture + "/" + e.id(),
                        production[0], centre(e)[0]);
                assertEquals("centre y for " + fixture + "/" + e.id(),
                        production[1], centre(e)[1]);
                checked++;
            }
        }
        assertEquals("elements checked against the production centre", 191, checked);
    }

    /**
     * Drives the arms the corpus never reaches, so a latent bug in them would not be invisible.
     *
     * <p>Three branches are real code that no fixture exercises: a connection with exactly one
     * bendpoint, where the source end and the target end resolve to the <em>same</em> index and the
     * collapse is easy to get wrong; a degenerate element with no extent; and an element at
     * negative coordinates, where the truncating division rounds toward zero rather than down. The
     * corpus carries bendpoint counts of zero, two, three and four only — never one — so without
     * this the index-collapse arithmetic runs in no test at all.
     */
    @Test
    public void shouldClassifyTheArmsTheCorpusNeverReaches_whenDrivenDirectly() {
        // A single bendpoint is both the source terminal and the target terminal: index 0 and
        // index n - 1 are the same element of the list, and each end is tested against its own box.
        List<BendpointDto> single = List.of(new BendpointDto(30, 0, -30, 0));
        List<AbsoluteBendpointDto> resolved =
                ConnectionResponseBuilder.convertRelativeToAbsolute(single, 100, 100, 160, 100);
        assertEquals("one bendpoint yields one point", 1, resolved.size());
        assertEquals("and both ends read the same element of the list",
                resolved.get(0), resolved.get(resolved.size() - 1));

        // A zero-extent element still has four distinct face lines, because the offsets are
        // applied outside the box: left and right stay two pixels apart even at zero width.
        ViewFixture.FixtureElement degenerate =
                new ViewFixture.FixtureElement("zero", "Zero", 50, 60, 0, 0, false, null);
        int[] lines = faceLines(degenerate);
        assertEquals("left face line", 49, lines[0]);
        assertEquals("right face line", 51, lines[1]);
        assertTrue("a zero-width element does not collapse its vertical face lines",
                lines[0] < lines[1]);
        assertEquals("its centre is its origin", 50, centre(degenerate)[0]);
        assertEquals(CORNER, classify(49, 59, degenerate));
        assertEquals(FACE_LEFT, classify(49, 60, degenerate));
        assertEquals(OFF_EVERY_FACE_LINE, classify(50, 60, degenerate));

        // A negative-coordinate element: the truncating division rounds toward zero, so the centre
        // of a box at a negative origin is not the mirror of one at a positive origin.
        ViewFixture.FixtureElement negative =
                new ViewFixture.FixtureElement("neg", "Neg", -300, -200, 181, 55, false, null);
        assertEquals("centre x truncates toward zero", -300 + 90, centre(negative)[0]);
        assertEquals(FACE_LEFT, classify(-301, -200, negative));
    }

    /**
     * Pins that no terminal in the corpus reaches the unreadable-bounds arm, and that both
     * reconstructions tally the same total.
     *
     * <p>No fixture carries a dangling endpoint, so {@code abstain-unreadable-bounds} runs nowhere
     * in the census proper. An arm that silently tallied the wrong bucket, or tallied only one of
     * the two reconstructions, would leave the totals inconsistent without failing anything — so
     * the totals are pinned against each other and against the terminal count.
     */
    @Test
    public void shouldAbstainOnBothReconstructions_whenAnEndpointCannotBeResolved()
            throws IOException {
        for (String fixture : ALL_FIXTURES) {
            ViewFixture fx = ViewFixture.load("testdata/" + fixture + "-fixture.json");
            for (ViewFixture.FixtureConnection conn : fx.getConnections()) {
                assertTrue("unexpected dangling source on " + conn.id(),
                        fx.getElementById(conn.sourceId()) != null);
                assertTrue("unexpected dangling target on " + conn.id(),
                        fx.getElementById(conn.targetId()) != null);
            }
        }
        Census census = runCensus(ALL_FIXTURES);
        assertEquals("no terminal in the corpus reaches the unreadable-bounds arm",
                null, census.p1().get(UNREADABLE_BOUNDS));
        assertEquals("the two reconstructions tally the same total",
                sum(census.p1()), sum(census.p2()));
        assertEquals("which is every terminal examined", 380, sum(census.p1()));
    }

    /**
     * Pins the denominator's exclusion at the level the claim is made — the absent JSON key.
     *
     * <p>{@code ViewFixture} maps a missing {@code bendpoints} key to an empty list, so reading the
     * parsed fixture cannot tell "this fixture never recorded geometry" from "this connection has
     * none". The distinction is the whole justification for the narrower denominator, so it is
     * checked against the raw JSON rather than the parsed model: these four files must carry no
     * {@code bendpoints} key on any connection at all, and the three census fixtures must carry it
     * on every connection.
     *
     * <p>If one of them is ever recaptured with geometry, this goes red and the census gains a
     * fixture, rather than quietly absorbing it as another abstention.
     */
    @Test
    public void shouldCarryNoBendpointsKeyAtAll_whenAFixturePredatesTheField() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        for (String name : NON_CAPTURING_FIXTURES) {
            JsonNode root = mapper.readTree(fixtureFile(name).toFile());
            for (JsonNode conn : root.get("connections")) {
                assertFalse(name + " unexpectedly records a bendpoints key on "
                        + conn.get("id").asText(), conn.has("bendpoints"));
            }
        }
        for (String name : CAPTURING_FIXTURES) {
            JsonNode root = mapper.readTree(fixtureFile(name).toFile());
            for (JsonNode conn : root.get("connections")) {
                assertTrue(name + " unexpectedly omits the bendpoints key on "
                        + conn.get("id").asText(), conn.has("bendpoints"));
            }
        }
    }

    /** Locates a fixture on disk, from either the repo root or the test project directory. */
    private static Path fixtureFile(String name) throws IOException {
        Path direct = Path.of("net.vheerden.archi.mcp.tests/testdata/" + name + "-fixture.json");
        if (Files.exists(direct)) {
            return direct;
        }
        Path nested = Path.of("testdata/" + name + "-fixture.json");
        if (Files.exists(nested)) {
            return nested;
        }
        throw new IOException("Fixture not found on disk: " + name);
    }
}
