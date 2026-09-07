package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RenderFaceDeriver.AnchorModel;
import net.vheerden.archi.mcp.model.RenderFaceDeriver.Outcome;
import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Measures how often the face a connection is <em>drawn</em> leaving can be named, over the view
 * fixture corpus, before any of it reaches the wire.
 *
 * <p><strong>What this census exists to answer, and how it differs from its predecessor.</strong>
 * {@code TerminalFaceCoverageCensusTest} asked whether a connection's outermost stored bendpoint
 * sits on a face line, and answered zero out of 138: a stored bendpoint is a waypoint the line is
 * aimed at, not the point where it meets the element. This census asks the question that one was
 * standing in for — where the renderer's anchor actually attaches — and it needs no stored
 * geometry at all, because with no bendpoints the anchor aims at the other element's centre. Every
 * terminal with two readable boxes is therefore in scope, which is the whole corpus.
 *
 * <p><strong>Both anchor models, because the answer depends on a preference.</strong> Archi
 * installs {@code OrthogonalAnchor} by default and falls back to {@code ChopboxAnchor} when the
 * {@code orthogonalAnchor} preference is off. The two are different algorithms, not different
 * tunings of one, so the census runs both and counts where they agree. The agreement rate is the
 * evidence for how the configuration axis is ruled: a face both models produce is true whichever
 * way the preference is set.
 *
 * <p><strong>Both routers.</strong> With the default bendpoint router the reference is the first or
 * last bendpoint when the connection has any; with the manhattan router stored bendpoints are
 * ignored entirely and the reference is always the other element's centre. The two are counted
 * separately rather than assumed equal.
 *
 * <p><strong>Two denominators, and only one of them measures a view.</strong> Four of the seven
 * fixtures carry no {@code bendpoints} key on any connection, and {@code ViewFixture} maps an
 * absent key to an empty list, so on those fixtures "this connection has no bendpoints" is a fact
 * about the capture format rather than about any view. Their terminals are still counted — the
 * no-bendpoint arm is a first-class arm here, not an abstention, so excluding them the way the
 * predecessor did would hide the arm this field mostly runs on — but the two runs are reported
 * separately and never summed.
 *
 * <p><strong>Frame.</strong> Reporting frame over stored input throughout: whole-pixel centres from
 * {@code RenderFaceDeriver.centreOf} and absolute bendpoints from the production
 * {@code ConnectionResponseBuilder.convertRelativeToAbsolute}, classified by the production
 * {@code RenderFaceDeriver}. Nothing here restates production arithmetic.
 *
 * <p><strong>What the corpus cannot show.</strong> {@code ViewFixture.FixtureElement} carries no
 * element type and no figure variant, so every element here is classified as a plain rectangle with
 * corner dimension {@code (0, 0)}. The rounded-figure population is sized elsewhere — see
 * {@code RenderFaceDerivationTest} — and is not measurable on these fixtures at all.
 */
public class RenderFaceCoverageCensusTest {

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

    /** The fixtures that record stored connection geometry — the denominator that measures views. */
    private static final String[] CAPTURING_FIXTURES = {
        "retail-bank-business-architecture",
        "retail-bank-application-collaboration",
        "retail-bank-drifted-anchors",
    };

    /** A plain rectangle: the only corner dimension this type-blind corpus can support. */
    private static final int[] PLAIN = { 0, 0 };

    /** A plain rectangular figure, whose preference-off fallback is the chopbox anchor. */
    private static final RenderFaceDeriver.Anchoring PLAIN_ANCHORING =
            RenderFaceDeriver.anchoringOf("ApplicationComponent", 0, 0, 0);

    /**
     * The same geometry asked as though every element drew through a rounded delegate.
     *
     * <p>The corpus carries no element type, so this cannot be a claim about these views. It sizes
     * the <em>arm</em>: what the rounded corner and its narrower fallback cost when they apply,
     * on boxes of realistic dimensions. The population they apply to is sized elsewhere.
     */
    private static final RenderFaceDeriver.Anchoring ROUNDED_ANCHORING =
            RenderFaceDeriver.anchoringOf("ApplicationFunction", 0, 0, 0);

    /**
     * One census run.
     *
     * @param bendpointRouter  outcomes under the default router, orthogonal anchor
     * @param chopbox          outcomes under the default router, chopbox anchor
     * @param manhattan        outcomes under the manhattan router, orthogonal anchor
     * @param modelsAgree      terminals where orthogonal and chopbox produce the same outcome
     * @param routersAgree     terminals where the two routers produce the same outcome
     * @param noBendpointConns connections carrying no stored geometry
     */
    private record Census(Map<String, Integer> bendpointRouter, Map<String, Integer> chopbox,
                          Map<String, Integer> manhattan, int modelsAgree, int routersAgree,
                          int terminals, int connections, int noBendpointConns,
                          Map<String, Integer> shipped, Map<String, Integer> shippedRounded,
                          int published, int publishedRounded) {}

    private Census runCensus(String[] fixtures) throws IOException {
        Map<String, Integer> orth = new LinkedHashMap<>();
        Map<String, Integer> chop = new LinkedHashMap<>();
        Map<String, Integer> manh = new LinkedHashMap<>();
        int modelsAgree = 0;
        int routersAgree = 0;
        int terminals = 0;
        int connections = 0;
        int noBendpointConns = 0;
        Map<String, Integer> shipped = new LinkedHashMap<>();
        Map<String, Integer> shippedRounded = new LinkedHashMap<>();
        int published = 0;
        int publishedRounded = 0;

        for (String fixture : fixtures) {
            ViewFixture fx = ViewFixture.load("testdata/" + fixture + "-fixture.json");
            for (ViewFixture.FixtureConnection conn : fx.getConnections()) {
                connections++;
                List<ViewFixture.FixtureBendpoint> bps = conn.bendpoints();
                if (bps.isEmpty()) {
                    noBendpointConns++;
                }
                ViewFixture.FixtureElement src = fx.getElementById(conn.sourceId());
                ViewFixture.FixtureElement tgt = fx.getElementById(conn.targetId());

                for (boolean sourceEnd : new boolean[] { true, false }) {
                    terminals++;
                    if (src == null || tgt == null) {
                        tally(orth, Outcome.ABSTAIN_UNREADABLE_BOUNDS);
                        tally(chop, Outcome.ABSTAIN_UNREADABLE_BOUNDS);
                        tally(manh, Outcome.ABSTAIN_UNREADABLE_BOUNDS);
                        tally(shipped, Outcome.ABSTAIN_UNREADABLE_BOUNDS);
                        tally(shippedRounded, Outcome.ABSTAIN_UNREADABLE_BOUNDS);
                        modelsAgree++;
                        routersAgree++;
                        continue;
                    }
                    int[] srcBox = box(src);
                    int[] tgtBox = box(tgt);
                    int[] own = sourceEnd ? srcBox : tgtBox;
                    int[] remote = sourceEnd ? tgtBox : srcBox;

                    int[] centreRef = RenderFaceDeriver.centreOf(remote);
                    int[] routedRef = centreRef;
                    if (!bps.isEmpty()) {
                        int[] srcCentre = RenderFaceDeriver.centreOf(srcBox);
                        int[] tgtCentre = RenderFaceDeriver.centreOf(tgtBox);
                        List<AbsoluteBendpointDto> absolute =
                                ConnectionResponseBuilder.convertRelativeToAbsolute(
                                        toDtos(bps), srcCentre[0], srcCentre[1],
                                        tgtCentre[0], tgtCentre[1]);
                        AbsoluteBendpointDto terminal =
                                absolute.get(sourceEnd ? 0 : absolute.size() - 1);
                        routedRef = new int[] { terminal.x(), terminal.y() };
                    }

                    Outcome o = RenderFaceDeriver.derive(own, remote, routedRef[0], routedRef[1],
                            PLAIN, AnchorModel.ORTHOGONAL);
                    Outcome c = RenderFaceDeriver.derive(own, remote, routedRef[0], routedRef[1],
                            PLAIN, AnchorModel.CHOPBOX);
                    Outcome m = RenderFaceDeriver.derive(own, remote, centreRef[0], centreRef[1],
                            PLAIN, AnchorModel.ORTHOGONAL);
                    tally(orth, o);
                    tally(chop, c);
                    tally(manh, m);

                    Outcome shippedOutcome = RenderFaceDeriver.classify(
                            own, remote, routedRef[0], routedRef[1], PLAIN_ANCHORING);
                    Outcome roundedOutcome = RenderFaceDeriver.classify(
                            own, remote, routedRef[0], routedRef[1], ROUNDED_ANCHORING);
                    tally(shipped, shippedOutcome);
                    tally(shippedRounded, roundedOutcome);
                    if (RenderFaceDeriver.publishedFace(
                            own, remote, routedRef[0], routedRef[1], PLAIN_ANCHORING) != null) {
                        published++;
                    }
                    if (RenderFaceDeriver.publishedFace(
                            own, remote, routedRef[0], routedRef[1], ROUNDED_ANCHORING) != null) {
                        publishedRounded++;
                    }
                    if (o == c) {
                        modelsAgree++;
                    }
                    if (o == m) {
                        routersAgree++;
                    }
                }
            }
        }
        return new Census(orth, chop, manh, modelsAgree, routersAgree,
                terminals, connections, noBendpointConns,
                shipped, shippedRounded, published, publishedRounded);
    }

    private static int[] box(ViewFixture.FixtureElement e) {
        return new int[] { e.x(), e.y(), e.w(), e.h() };
    }

    private static void tally(Map<String, Integer> counts, Outcome outcome) {
        counts.merge(outcome.censusName(), 1, Integer::sum);
    }

    private static List<BendpointDto> toDtos(List<ViewFixture.FixtureBendpoint> bps) {
        List<BendpointDto> dtos = new ArrayList<>(bps.size());
        for (ViewFixture.FixtureBendpoint bp : bps) {
            dtos.add(new BendpointDto(bp.startX(), bp.startY(), bp.endX(), bp.endY()));
        }
        return dtos;
    }

    private static int faces(Map<String, Integer> counts) {
        int total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getKey().startsWith("face-")) {
                total += e.getValue();
            }
        }
        return total;
    }

    private static int corners(Map<String, Integer> counts) {
        int total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getKey().startsWith("corner-")) {
                total += e.getValue();
            }
        }
        return total;
    }

    private static int sum(Map<String, Integer> counts) {
        int total = 0;
        for (int v : counts.values()) {
            total += v;
        }
        return total;
    }


    // ------------------------------------------------------------------------------ the censuses

    /**
     * The coverage table over the three fixtures that record stored geometry — the denominator
     * that measures views rather than the capture format.
     *
     * <p>Under the anchor and router Archi installs by default, <strong>135 of 138</strong>
     * terminals name a face. The three that do not are all one reason: the reference the anchor
     * aims at lands inside the box it is attaching to, and the anchor answers with the box centre,
     * which is on no face. No terminal here resolves to a corner.
     *
     * <p>Against the predecessor's zero out of these same 138, that is the whole case for building
     * the field: the question the earlier row asked of the stored bendpoint is answerable of the
     * render attachment on all but three terminals of the corpus.
     */
    @Test
    public void shouldNameAFaceOnNearlyEveryTerminal_whenTheCorpusRecordsStoredGeometry()
            throws IOException {
        Census c = runCensus(CAPTURING_FIXTURES);
        assertEquals("terminals censused", 138, c.terminals());
        assertEquals("connections censused", 69, c.connections());
        assertEquals("connections carrying no stored geometry", 25, c.noBendpointConns());
        assertEquals("every terminal classified exactly once", 138, sum(c.bendpointRouter()));

        assertEquals("faces named under the default anchor and router", 135, faces(c.bendpointRouter()));
        assertEquals("corners under the default anchor and router", 0, corners(c.bendpointRouter()));
        assertEquals("the only abstention, and its reason", 3,
                (int) c.bendpointRouter().getOrDefault("abstain-reference-inside-box", 0));
        assertEquals("top", 32, (int) c.bendpointRouter().getOrDefault("face-top", 0));
        assertEquals("bottom", 35, (int) c.bendpointRouter().getOrDefault("face-bottom", 0));
        assertEquals("left", 34, (int) c.bendpointRouter().getOrDefault("face-left", 0));
        assertEquals("right", 34, (int) c.bendpointRouter().getOrDefault("face-right", 0));
    }

    /**
     * The two anchor models the {@code orthogonalAnchor} preference selects between never disagree
     * on a face over this corpus — they differ only where the installed one already abstains.
     *
     * <p>Agreement is 135 out of 138, which is <em>exactly</em> the number of faces the orthogonal
     * model names. The three terminals where they differ are the three where the orthogonal model
     * abstains and the chopbox model still names something. So gating publication on agreement,
     * which is how the configuration axis is settled, costs nothing measurable here: it removes no
     * terminal that would otherwise have carried a value.
     */
    @Test
    public void shouldAgreeOnEveryNamedFace_whenBothAnchorModelsAreRun() throws IOException {
        Census c = runCensus(CAPTURING_FIXTURES);
        assertEquals("chopbox names a face on every terminal", 138, faces(c.chopbox()));
        assertEquals("chopbox reaches no corner", 0, corners(c.chopbox()));
        assertEquals("terminals where the two models agree", 135, c.modelsAgree());
        assertEquals("agreement is exactly the orthogonal face count, so gating on it is free",
                faces(c.bendpointRouter()), c.modelsAgree());
    }

    /**
     * The manhattan router is not the no-bendpoint arm under another name, and this is the pin that
     * says so.
     *
     * <p>Manhattan ignores stored bendpoints entirely and always aims at the other element's
     * centre. Over the same 138 terminals it agrees with the default router on only <strong>63</strong>
     * — fewer than half — and turns 72 of them into corners the default router never reaches. A
     * derivation that assumed one arm covered both would be wrong on 75 terminals of this corpus.
     */
    @Test
    public void shouldDivergeFromTheDefaultRouter_whenTheViewRoutesManhattan() throws IOException {
        Census c = runCensus(CAPTURING_FIXTURES);
        assertEquals("terminals where the two routers reach the same outcome", 63, c.routersAgree());
        assertEquals("faces under manhattan", 66, faces(c.manhattan()));
        assertEquals("corners under manhattan", 72, corners(c.manhattan()));
        assertEquals("manhattan reaches no interior abstention on this corpus", 0,
                (int) c.manhattan().getOrDefault("abstain-reference-inside-box", 0));
    }

    /**
     * The full-denominator table, and the attribution without which it cannot be quoted.
     *
     * <p>Over all seven fixtures the orthogonal model names 193 faces of 380 terminals and resolves
     * 184 to a corner. That 51% is <strong>not</strong> a coverage rate for real views. Four of the
     * seven fixtures carry no {@code bendpoints} key at all, so every connection on them reads as
     * bendpoint-free, and {@link #shouldFallEntirelyOnTheNonCapturingFixtures_whenACornerIsReached}
     * shows all 184 corners land there. The number measures a hypothesis about views whose stored
     * geometry was never captured.
     */
    @Test
    public void shouldMeasureAHypothesis_whenTheDenominatorIsEveryFixture() throws IOException {
        Census c = runCensus(ALL_FIXTURES);
        assertEquals("terminals censused", 380, c.terminals());
        assertEquals("connections censused", 190, c.connections());
        assertEquals("connections reading as bendpoint-free", 146, c.noBendpointConns());
        assertEquals("every terminal classified exactly once", 380, sum(c.bendpointRouter()));

        assertEquals("faces under the default anchor and router", 193, faces(c.bendpointRouter()));
        assertEquals("corners under the default anchor and router", 184, corners(c.bendpointRouter()));
        assertEquals("interior abstentions", 3,
                (int) c.bendpointRouter().getOrDefault("abstain-reference-inside-box", 0));
        assertEquals("chopbox names a face on every terminal", 380, faces(c.chopbox()));
        assertEquals("agreement is again exactly the orthogonal face count",
                193, c.modelsAgree());
    }

    /**
     * The corner outcome and the capture defect are perfectly confounded in this corpus.
     *
     * <p>All 184 corners fall on the four fixtures that record no {@code bendpoints} key, and the
     * three that do record one produce none at all. A corpus that cannot separate "this connection
     * has no bendpoints" from "this fixture never captured any" cannot say how common a corner
     * really is — which is why the corner ruling rests on the capturing denominator alone.
     */
    @Test
    public void shouldFallEntirelyOnTheNonCapturingFixtures_whenACornerIsReached() throws IOException {
        Map<String, int[]> perFixture = new LinkedHashMap<>();
        for (String fixture : ALL_FIXTURES) {
            Census c = runCensus(new String[] { fixture });
            perFixture.put(fixture, new int[] {
                c.terminals(), c.noBendpointConns(), faces(c.bendpointRouter()),
                corners(c.bendpointRouter()),
                c.bendpointRouter().getOrDefault("abstain-reference-inside-box", 0),
            });
        }
        assertArrayEquals("app-architecture-view", new int[] { 62, 31, 28, 34, 0 },
                perFixture.get("app-architecture-view"));
        assertArrayEquals("v4-integration-architecture-oracle", new int[] { 60, 30, 10, 50, 0 },
                perFixture.get("v4-integration-architecture-oracle"));
        assertArrayEquals("hh-source-clone", new int[] { 60, 30, 10, 50, 0 },
                perFixture.get("hh-source-clone"));
        assertArrayEquals("st-source-clone", new int[] { 60, 30, 10, 50, 0 },
                perFixture.get("st-source-clone"));
        assertArrayEquals("retail-bank-business-architecture", new int[] { 64, 13, 64, 0, 0 },
                perFixture.get("retail-bank-business-architecture"));
        assertArrayEquals("retail-bank-application-collaboration", new int[] { 34, 6, 34, 0, 0 },
                perFixture.get("retail-bank-application-collaboration"));
        assertArrayEquals("retail-bank-drifted-anchors", new int[] { 40, 6, 37, 0, 3 },
                perFixture.get("retail-bank-drifted-anchors"));

        int cornersOnCapturing = 0;
        for (String fixture : CAPTURING_FIXTURES) {
            cornersOnCapturing += perFixture.get(fixture)[3];
        }
        assertEquals("the fixtures that capture stored geometry produce no corner at all",
                0, cornersOnCapturing);
    }

    /**
     * What the shipped path publishes, which is the number that decides whether the field is worth
     * a record component.
     *
     * <p>These counts come through {@code RenderFaceDeriver.publishedFace}, the method a connection
     * response actually calls, agreement gate and all — not through either model read separately.
     * The rounded row asks the same geometry as though every element drew through a rounded
     * delegate; it sizes the arm, not this corpus, which carries no element type at all.
     */
    @Test
    public void shouldPublishAFace_whenTheShippedGateIsDriven() throws IOException {
        Census capturing = runCensus(CAPTURING_FIXTURES);
        assertEquals("published over the capturing denominator", 135, capturing.published());
        assertEquals("the gate publishes exactly what the installed anchor named",
                faces(capturing.bendpointRouter()), capturing.published());
        assertEquals("no terminal is lost to a model disagreement", 0,
                (int) capturing.shipped().getOrDefault("abstain-anchor-models-disagree", 0));

        Census all = runCensus(ALL_FIXTURES);
        assertEquals("published over the full denominator", 193, all.published());
        assertEquals("no terminal is lost to a model disagreement there either", 0,
                (int) all.shipped().getOrDefault("abstain-anchor-models-disagree", 0));
    }

    /**
     * What a rounded corner costs on the same geometry, measured rather than assumed.
     *
     * <p>Twenty ArchiMate types draw through a rounded delegate on their <em>default</em> figure,
     * so this arm is not an edge case. Declining every one of them — the cautious reading — would
     * have been a large, silent loss. It is not needed: the four face arms of the installed anchor
     * return
     * {@code (ref.x, box.y)} and {@code (box.x, ref.y)} whatever the corner dimension is, and only
     * the arms that land on the arc itself depend on it.
     */
    @Test
    public void shouldStillPublishMostFaces_whenTheFigureDrawsRounded() throws IOException {
        Census capturing = runCensus(CAPTURING_FIXTURES);
        assertEquals("published with a rounded corner, capturing denominator",
                132, capturing.publishedRounded());
        assertEquals("terminals lost to the corner arc", 3,
                (int) capturing.shippedRounded().getOrDefault("abstain-rounded-corner-arc", 0));
        assertTrue("a rounded figure still publishes the great majority of its terminals",
                capturing.publishedRounded() * 100 >= capturing.terminals() * 90);
    }

    // ------------------------------------------------------------------------- the positive control

    /**
     * Every value the classifier can publish is reachable, on points whose answer is known without
     * running the classifier.
     *
     * <p>A census whose cells are all one value cannot be told apart from a broken classifier, so
     * the classifier is driven here against hand-placed references around a box that is deliberately
     * far from the reference it is aimed at — far enough that the anchor's centre-replacement step
     * cannot fire and confuse the reading.
     */
    @Test
    public void shouldNameEachFace_whenTheReferenceIsPlacedOnEachSide() {
        int[] own = { 100, 100, 80, 60 };
        int[] far = { 1000, 1000, 10, 10 };
        assertEquals(Outcome.FACE_TOP, orth(own, far, 140, 20));
        assertEquals(Outcome.FACE_BOTTOM, orth(own, far, 140, 400));
        assertEquals(Outcome.FACE_LEFT, orth(own, far, 20, 130));
        assertEquals(Outcome.FACE_RIGHT, orth(own, far, 400, 130));
        assertEquals(Outcome.CORNER_TOP_LEFT, orth(own, far, 20, 20));
        assertEquals(Outcome.CORNER_TOP_RIGHT, orth(own, far, 400, 20));
        assertEquals(Outcome.CORNER_BOTTOM_LEFT, orth(own, far, 20, 400));
        assertEquals(Outcome.CORNER_BOTTOM_RIGHT, orth(own, far, 400, 400));
        assertEquals(Outcome.ABSTAIN_REFERENCE_INSIDE_BOX, orth(own, far, 140, 130));
    }

    private static Outcome orth(int[] own, int[] remote, int refX, int refY) {
        return RenderFaceDeriver.derive(own, remote, refX, refY, PLAIN, AnchorModel.ORTHOGONAL);
    }

    /** The classifier's published vocabulary is the four faces and nothing else. */
    @Test
    public void shouldPublishOnlyFaceNames_whenEveryOutcomeIsAsked() {
        for (Outcome o : Outcome.values()) {
            String published = o.publishedValue();
            if (published != null) {
                assertTrue("published value must be a bare face name: " + published,
                        List.of("top", "bottom", "left", "right").contains(published));
            }
        }
        assertEquals("top", Outcome.FACE_TOP.publishedValue());
        assertEquals("bottom", Outcome.FACE_BOTTOM.publishedValue());
        assertEquals("left", Outcome.FACE_LEFT.publishedValue());
        assertEquals("right", Outcome.FACE_RIGHT.publishedValue());
        assertNull(Outcome.ABSTAIN_REFERENCE_INSIDE_BOX.publishedValue());
        assertNotNull(Outcome.ABSTAIN_UNREADABLE_BOUNDS.censusName());
    }
}
