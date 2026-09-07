package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * The hub-port-quality (M5) remedy band.
 *
 * <p>The rating assigns {@code hubPortQuality} four bands — {@code pass} at or above 0.95,
 * {@code good} at or above 0.75, {@code fair} at or above 0.5, {@code poor} below it — and both
 * the {@code fair} and the {@code poor} band cap the view's routing tier. The assessor's M5
 * suggestion and the {@code assess-layout} hub next-step, however, historically fired only below
 * 0.5, so the whole quarter-wide {@code fair} interval was a region in which the tool capped the
 * view and said nothing about why or what to do. These pins hold the remedy to the band the rating
 * assigns rather than to a repeated literal, so the two cannot drift apart again.</p>
 *
 * <p>The aggregate is the WORST hub face, and a face's quality is
 * {@code distinctSlots / connectionsOnFace}, so an exact in-band score is reachable only at the
 * ratios of small integers: 2/4 = 0.5 and 3/5 = 0.6 sit inside the band, 3/4 = 0.75 and 4/5 = 0.8
 * sit just outside it. The band function itself is pinned at the exact interval edges, which
 * geometry cannot produce.</p>
 */
public class HubPortQualitySilentBandTest {

    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() {
        assessor = new LayoutQualityAssessor();
    }

    // ---- The band function: the single definition both the rating and the remedy read ----

    @Test
    public void hubPortQualityBand_shouldMatchTheBandTheRatingBreakdownAssigns() {
        assertEquals("poor", LayoutQualityAssessor.hubPortQualityBand(0.0));
        assertEquals("poor", LayoutQualityAssessor.hubPortQualityBand(0.49));
        assertEquals("fair", LayoutQualityAssessor.hubPortQualityBand(0.50));
        assertEquals("fair", LayoutQualityAssessor.hubPortQualityBand(0.60));
        assertEquals("fair", LayoutQualityAssessor.hubPortQualityBand(0.7499));
        assertEquals("good", LayoutQualityAssessor.hubPortQualityBand(0.75));
        assertEquals("good", LayoutQualityAssessor.hubPortQualityBand(0.80));
        assertEquals("pass", LayoutQualityAssessor.hubPortQualityBand(0.95));
        assertEquals("pass", LayoutQualityAssessor.hubPortQualityBand(1.0));
    }

    @Test
    public void hubPortQualityBand_shouldBeTheOnlyDefinitionTheBreakdownUses() {
        // Drive the rating breakdown through geometry and assert it agrees with the band
        // function at every reachable ratio. A second copy of the if-chain would pass this
        // only for as long as nobody edited one of the two.
        for (int[] fixture : new int[][]{{4, 1}, {4, 2}, {4, 3}, {5, 3}, {5, 4}, {4, 4}}) {
            LayoutAssessmentResult r = assessOneHubFace(fixture[0], fixture[1]);
            assertEquals("breakdown band must be the band function's answer for score "
                            + r.hubPortQualityScore(),
                    LayoutQualityAssessor.hubPortQualityBand(r.hubPortQualityScore()),
                    r.ratingBreakdown().get("hubPortQuality"));
        }
    }

    // ---- The assessor's M5 suggestion fires across the whole capping region ----

    @Test
    public void assess_shouldEmitTheHubPortSuggestion_whenTheBandIsPoor() {
        // 1 distinct slot of 4 → 0.25, poor. Fired before this change too; the negative control
        // that proves the widening did not lose the band it already covered.
        LayoutAssessmentResult r = assessOneHubFace(4, 1);
        assertEquals(0.25, r.hubPortQualityScore(), 1e-9);
        assertTrue("poor must still emit the M5 suggestion: " + r.suggestions(),
                hasHubPortSuggestion(r));
    }

    @Test
    public void assess_shouldEmitTheHubPortSuggestion_whenTheBandIsFairAtTheLowerEdge() {
        LayoutAssessmentResult r = assessOneHubFace(4, 2);
        assertEquals(0.5, r.hubPortQualityScore(), 1e-9);
        assertEquals("fair", r.ratingBreakdown().get("hubPortQuality"));
        assertTrue("a view the rating caps at fair must be told why: " + r.suggestions(),
                hasHubPortSuggestion(r));
    }

    @Test
    public void assess_shouldEmitTheHubPortSuggestion_whenTheBandIsFairInsideTheInterval() {
        LayoutAssessmentResult r = assessOneHubFace(5, 3);
        assertEquals(0.6, r.hubPortQualityScore(), 1e-9);
        assertEquals("fair", r.ratingBreakdown().get("hubPortQuality"));
        assertTrue("a view the rating caps at fair must be told why: " + r.suggestions(),
                hasHubPortSuggestion(r));
    }

    @Test
    public void assess_shouldNotEmitTheHubPortSuggestion_whenTheBandIsGoodAtItsLowerEdge() {
        // 3/4 = 0.75 exactly. The rating calls this good and takes no tier from it, so a
        // remedy here would be a false positive — this is the pin an over-wide <= would break.
        LayoutAssessmentResult r = assessOneHubFace(4, 3);
        assertEquals(0.75, r.hubPortQualityScore(), 1e-9);
        assertEquals("good", r.ratingBreakdown().get("hubPortQuality"));
        assertFalse("good must stay silent: " + r.suggestions(), hasHubPortSuggestion(r));
    }

    @Test
    public void assess_shouldNotEmitTheHubPortSuggestion_whenTheBandIsGoodAboveItsLowerEdge() {
        LayoutAssessmentResult r = assessOneHubFace(5, 4);
        assertEquals(0.8, r.hubPortQualityScore(), 1e-9);
        assertFalse("good must stay silent: " + r.suggestions(), hasHubPortSuggestion(r));
    }

    @Test
    public void assess_shouldNotEmitTheHubPortSuggestion_whenEveryPortHasItsOwnSlot() {
        LayoutAssessmentResult r = assessOneHubFace(4, 4);
        assertEquals(1.0, r.hubPortQualityScore(), 1e-9);
        assertFalse("pass must stay silent: " + r.suggestions(), hasHubPortSuggestion(r));
    }

    // ---- The violator ids the suggestion points at must exist wherever it fires ----

    @Test
    public void assess_shouldNameTheViolatorHub_whereverTheSuggestionFires() {
        // The M5 suggestion tells the caller to inspect violatorIds.hubPortLowQuality. That map
        // entry is written only when the id set is non-empty, so a suggestion emitted over an
        // empty set points at a field that is not in the response at all.
        for (int[] fixture : new int[][]{{4, 1}, {4, 2}, {5, 3}}) {
            LayoutAssessmentResult r = assessOneHubFace(fixture[0], fixture[1]);
            assertTrue("fixture " + fixture[1] + "/" + fixture[0] + " must emit",
                    hasHubPortSuggestion(r));
            assertTrue("the suggestion's own violator field must carry the hub at score "
                            + r.hubPortQualityScore() + ": " + r.violatorIds(),
                    r.violatorIds().containsKey("hubPortLowQuality")
                            && r.violatorIds().get("hubPortLowQuality").contains("hub"));
        }
    }

    @Test
    public void assess_shouldLeaveTheViolatorSetEmpty_whenNoBandCapsTheView() {
        for (int[] fixture : new int[][]{{4, 3}, {5, 4}, {4, 4}}) {
            LayoutAssessmentResult r = assessOneHubFace(fixture[0], fixture[1]);
            assertFalse("a good/pass view must not name a violator hub at score "
                            + r.hubPortQualityScore() + ": " + r.violatorIds(),
                    r.violatorIds().containsKey("hubPortLowQuality"));
        }
    }

    // ---- The rating does not move ----

    /**
     * Every rating the corpus produces, before and after widening the remedy.
     *
     * <p>The widening changes what the tool SAYS, never what it SCORES: it adds no breakdown
     * entry, moves no tier and touches no metric input. That is easy to claim and easy to get
     * wrong, because the band function it now routes through also feeds the breakdown — so a
     * boundary typed one character differently there would silently re-rate views rather than
     * merely re-advise them. The golden values below were measured on the unwidened tree and
     * every one of them still holds.</p>
     *
     * <p>What DID move, on exactly one of the seven views, is the violator set: the fixture at
     * hub-port quality 0.60 sits inside the band the remedy could not previously reach, and its
     * hub is now named. That is the change this story exists to make, and it is pinned as a
     * change rather than smuggled past an identity assertion — see
     * {@link #corpus_shouldNameTheHubOnTheOneViewInsideTheBand()}.</p>
     */
    @Test
    public void corpus_shouldNotMoveAnyRating() throws java.io.IOException {
        assertCorpusRatingUnchanged("app-architecture-view",
                "poor", "excellent", "poor", 1.0,
                java.util.Map.ofEntries(
                        java.util.Map.entry("alignment", "pass"),
                        java.util.Map.entry("boundaryViolations", "pass"),
                        java.util.Map.entry("coincidentSegments", "pass"),
                        java.util.Map.entry("connectionEdgeCoincidence", "pass"),
                        java.util.Map.entry("connectionThroughNote", "pass"),
                        java.util.Map.entry("edgeCrossings", "good"),
                        java.util.Map.entry("hubNeighbourCrowding", "pass"),
                        java.util.Map.entry("hubPortQuality", "pass"),
                        java.util.Map.entry("interiorTerminations", "pass"),
                        java.util.Map.entry("labelOverlaps", "good"),
                        java.util.Map.entry("labelTruncations", "pass"),
                        java.util.Map.entry("nonOrthogonalInteriorSegments", "pass"),
                        java.util.Map.entry("nonOrthogonalTerminals", "poor"),
                        java.util.Map.entry("offCanvas", "pass"),
                        java.util.Map.entry("offFaceParallelTerminals", "pass"),
                        java.util.Map.entry("overall", "poor"),
                        java.util.Map.entry("overallExcludingAcceptedCosmetics", "poor"),
                        java.util.Map.entry("overlaps", "pass"),
                        java.util.Map.entry("parentLabelObscured", "pass"),
                        java.util.Map.entry("passThroughs", "poor"),
                        java.util.Map.entry("spacing", "pass"),
                        java.util.Map.entry("zigzags", "pass")));

        assertCorpusRatingUnchanged("hh-source-clone",
                "poor", "excellent", "poor", 1.0,
                java.util.Map.ofEntries(
                        java.util.Map.entry("alignment", "pass"),
                        java.util.Map.entry("boundaryViolations", "pass"),
                        java.util.Map.entry("coincidentSegments", "pass"),
                        java.util.Map.entry("connectionEdgeCoincidence", "pass"),
                        java.util.Map.entry("connectionThroughNote", "pass"),
                        java.util.Map.entry("edgeCrossings", "fair"),
                        java.util.Map.entry("hubNeighbourCrowding", "pass"),
                        java.util.Map.entry("hubPortQuality", "pass"),
                        java.util.Map.entry("interiorTerminations", "pass"),
                        java.util.Map.entry("labelOverlaps", "pass"),
                        java.util.Map.entry("labelTruncations", "pass"),
                        java.util.Map.entry("nonOrthogonalInteriorSegments", "pass"),
                        java.util.Map.entry("nonOrthogonalTerminals", "poor"),
                        java.util.Map.entry("offCanvas", "pass"),
                        java.util.Map.entry("offFaceParallelTerminals", "pass"),
                        java.util.Map.entry("overall", "poor"),
                        java.util.Map.entry("overallExcludingAcceptedCosmetics", "poor"),
                        java.util.Map.entry("overlaps", "pass"),
                        java.util.Map.entry("parentLabelObscured", "pass"),
                        java.util.Map.entry("passThroughs", "poor"),
                        java.util.Map.entry("spacing", "pass"),
                        java.util.Map.entry("zigzags", "pass")));

        assertCorpusRatingUnchanged("retail-bank-application-collaboration",
                "fair", "excellent", "fair", 0.75,
                java.util.Map.ofEntries(
                        java.util.Map.entry("alignment", "pass"),
                        java.util.Map.entry("boundaryViolations", "pass"),
                        java.util.Map.entry("coincidentSegments", "pass"),
                        java.util.Map.entry("connectionEdgeCoincidence", "pass"),
                        java.util.Map.entry("connectionThroughNote", "pass"),
                        java.util.Map.entry("edgeCrossings", "pass"),
                        java.util.Map.entry("hubNeighbourCrowding", "pass"),
                        java.util.Map.entry("hubPortQuality", "good"),
                        java.util.Map.entry("interiorTerminations", "pass"),
                        java.util.Map.entry("labelOverlaps", "pass"),
                        java.util.Map.entry("labelTruncations", "pass"),
                        java.util.Map.entry("nonOrthogonalInteriorSegments", "pass"),
                        java.util.Map.entry("nonOrthogonalTerminals", "poor"),
                        java.util.Map.entry("offCanvas", "pass"),
                        java.util.Map.entry("offFaceParallelTerminals", "pass"),
                        java.util.Map.entry("overall", "fair"),
                        java.util.Map.entry("overallExcludingAcceptedCosmetics", "good"),
                        java.util.Map.entry("overlaps", "pass"),
                        java.util.Map.entry("parentLabelObscured", "pass"),
                        java.util.Map.entry("passThroughs", "pass"),
                        java.util.Map.entry("spacing", "pass"),
                        java.util.Map.entry("zigzags", "pass")));

        assertCorpusRatingUnchanged("retail-bank-business-architecture",
                "fair", "excellent", "fair", 0.75,
                java.util.Map.ofEntries(
                        java.util.Map.entry("alignment", "pass"),
                        java.util.Map.entry("boundaryViolations", "pass"),
                        java.util.Map.entry("coincidentSegments", "pass"),
                        java.util.Map.entry("connectionEdgeCoincidence", "pass"),
                        java.util.Map.entry("connectionThroughNote", "pass"),
                        java.util.Map.entry("edgeCrossings", "good"),
                        java.util.Map.entry("hubNeighbourCrowding", "pass"),
                        java.util.Map.entry("hubPortQuality", "good"),
                        java.util.Map.entry("interiorTerminations", "pass"),
                        java.util.Map.entry("labelOverlaps", "pass"),
                        java.util.Map.entry("labelTruncations", "pass"),
                        java.util.Map.entry("nonOrthogonalInteriorSegments", "pass"),
                        java.util.Map.entry("nonOrthogonalTerminals", "poor"),
                        java.util.Map.entry("offCanvas", "pass"),
                        java.util.Map.entry("offFaceParallelTerminals", "pass"),
                        java.util.Map.entry("overall", "fair"),
                        java.util.Map.entry("overallExcludingAcceptedCosmetics", "good"),
                        java.util.Map.entry("overlaps", "pass"),
                        java.util.Map.entry("parentLabelObscured", "pass"),
                        java.util.Map.entry("passThroughs", "pass"),
                        java.util.Map.entry("spacing", "pass"),
                        java.util.Map.entry("zigzags", "pass")));

        assertCorpusRatingUnchanged("retail-bank-drifted-anchors",
                "poor", "fair", "poor", 0.6,
                java.util.Map.ofEntries(
                        java.util.Map.entry("alignment", "pass"),
                        java.util.Map.entry("boundaryViolations", "pass"),
                        java.util.Map.entry("coincidentSegments", "pass"),
                        java.util.Map.entry("connectionEdgeCoincidence", "pass"),
                        java.util.Map.entry("connectionThroughNote", "pass"),
                        java.util.Map.entry("edgeCrossings", "pass"),
                        java.util.Map.entry("hubNeighbourCrowding", "fair"),
                        java.util.Map.entry("hubPortQuality", "fair"),
                        java.util.Map.entry("interiorTerminations", "poor"),
                        java.util.Map.entry("labelOverlaps", "pass"),
                        java.util.Map.entry("labelTruncations", "pass"),
                        java.util.Map.entry("nonOrthogonalInteriorSegments", "fair"),
                        java.util.Map.entry("nonOrthogonalTerminals", "poor"),
                        java.util.Map.entry("offCanvas", "pass"),
                        java.util.Map.entry("offFaceParallelTerminals", "pass"),
                        java.util.Map.entry("overall", "poor"),
                        java.util.Map.entry("overallExcludingAcceptedCosmetics", "poor"),
                        java.util.Map.entry("overlaps", "pass"),
                        java.util.Map.entry("parentLabelObscured", "pass"),
                        java.util.Map.entry("passThroughs", "pass"),
                        java.util.Map.entry("spacing", "pass"),
                        java.util.Map.entry("zigzags", "pass")));

        assertCorpusRatingUnchanged("st-source-clone",
                "poor", "excellent", "poor", 1.0,
                java.util.Map.ofEntries(
                        java.util.Map.entry("alignment", "pass"),
                        java.util.Map.entry("boundaryViolations", "pass"),
                        java.util.Map.entry("coincidentSegments", "pass"),
                        java.util.Map.entry("connectionEdgeCoincidence", "pass"),
                        java.util.Map.entry("connectionThroughNote", "pass"),
                        java.util.Map.entry("edgeCrossings", "good"),
                        java.util.Map.entry("hubNeighbourCrowding", "pass"),
                        java.util.Map.entry("hubPortQuality", "pass"),
                        java.util.Map.entry("interiorTerminations", "pass"),
                        java.util.Map.entry("labelOverlaps", "fair"),
                        java.util.Map.entry("labelTruncations", "pass"),
                        java.util.Map.entry("nonOrthogonalInteriorSegments", "pass"),
                        java.util.Map.entry("nonOrthogonalTerminals", "poor"),
                        java.util.Map.entry("offCanvas", "pass"),
                        java.util.Map.entry("offFaceParallelTerminals", "pass"),
                        java.util.Map.entry("overall", "poor"),
                        java.util.Map.entry("overallExcludingAcceptedCosmetics", "poor"),
                        java.util.Map.entry("overlaps", "pass"),
                        java.util.Map.entry("parentLabelObscured", "pass"),
                        java.util.Map.entry("passThroughs", "poor"),
                        java.util.Map.entry("spacing", "pass"),
                        java.util.Map.entry("zigzags", "pass")));

        assertCorpusRatingUnchanged("v4-integration-architecture-oracle",
                "poor", "excellent", "poor", 1.0,
                java.util.Map.ofEntries(
                        java.util.Map.entry("alignment", "pass"),
                        java.util.Map.entry("boundaryViolations", "pass"),
                        java.util.Map.entry("coincidentSegments", "pass"),
                        java.util.Map.entry("connectionEdgeCoincidence", "pass"),
                        java.util.Map.entry("connectionThroughNote", "pass"),
                        java.util.Map.entry("edgeCrossings", "fair"),
                        java.util.Map.entry("hubNeighbourCrowding", "pass"),
                        java.util.Map.entry("hubPortQuality", "pass"),
                        java.util.Map.entry("interiorTerminations", "pass"),
                        java.util.Map.entry("labelOverlaps", "pass"),
                        java.util.Map.entry("labelTruncations", "pass"),
                        java.util.Map.entry("nonOrthogonalInteriorSegments", "pass"),
                        java.util.Map.entry("nonOrthogonalTerminals", "poor"),
                        java.util.Map.entry("offCanvas", "pass"),
                        java.util.Map.entry("offFaceParallelTerminals", "pass"),
                        java.util.Map.entry("overall", "poor"),
                        java.util.Map.entry("overallExcludingAcceptedCosmetics", "poor"),
                        java.util.Map.entry("overlaps", "pass"),
                        java.util.Map.entry("parentLabelObscured", "pass"),
                        java.util.Map.entry("passThroughs", "poor"),
                        java.util.Map.entry("spacing", "pass"),
                        java.util.Map.entry("zigzags", "pass")));
    }

    @Test
    public void corpus_shouldNameTheHubOnTheOneViewInsideTheBand() throws java.io.IOException {
        // The measured blast radius, stated as a test rather than as prose: of the seven corpus
        // views, six report hub-port quality at 0.75 or above and stay silent, and exactly one
        // sits at 0.60 inside the previously unexplained band. Two of the six score EXACTLY 0.75,
        // which is why the comparison is exclusive: an inclusive one would newly advise a hub
        // resize on two views the rating calls good.
        int inBand = 0;
        int atTheGoodEdge = 0;
        for (String view : CORPUS) {
            LayoutAssessmentResult r = assessCorpusView(view);
            String band = LayoutQualityAssessor.hubPortQualityBand(r.hubPortQualityScore());
            boolean named = r.violatorIds().containsKey("hubPortLowQuality");
            if ("fair".equals(band) || "poor".equals(band)) {
                inBand++;
                assertTrue(view + " is inside the capping band, so its hub must be named",
                        named);
            } else {
                assertFalse(view + " is good or better, so no hub may be named", named);
            }
            if (Math.abs(r.hubPortQualityScore() - 0.75) < 1e-9) {
                atTheGoodEdge++;
            }
        }
        assertEquals("exactly one corpus view moves from silent to advised", 1, inBand);
        assertEquals("and two sit exactly on the good edge, which must stay silent",
                2, atTheGoodEdge);
    }

    private static final String[] CORPUS = {
        "app-architecture-view", "hh-source-clone", "retail-bank-application-collaboration",
        "retail-bank-business-architecture", "retail-bank-drifted-anchors",
        "st-source-clone", "v4-integration-architecture-oracle",
    };

    private void assertCorpusRatingUnchanged(String view, String overall, String layout,
            String routing, double hubPortQualityScore,
            java.util.Map<String, String> breakdown) throws java.io.IOException {
        LayoutAssessmentResult r = assessCorpusView(view);
        assertEquals(view + " overallRating", overall, r.overallRating());
        assertEquals(view + " layoutRating", layout, r.layoutRating());
        assertEquals(view + " routingRating", routing, r.routingRating());
        assertEquals(view + " hubPortQualityScore",
                hubPortQualityScore, r.hubPortQualityScore(), 1e-9);
        assertEquals(view + " ratingBreakdown must be byte-identical, entry for entry",
                new java.util.TreeMap<>(breakdown), new java.util.TreeMap<>(r.ratingBreakdown()));
    }

    /** The corpus view's stored geometry, assessed exactly as the accessor assesses it. */
    private LayoutAssessmentResult assessCorpusView(String view) throws java.io.IOException {
        net.vheerden.archi.mcp.model.routing.ViewFixture f =
                net.vheerden.archi.mcp.model.routing.ViewFixture.load(
                        "testdata/" + view + "-fixture.json");
        List<AssessmentNode> nodes = new ArrayList<>();
        for (net.vheerden.archi.mcp.model.routing.ViewFixture.FixtureElement e : f.getElements()) {
            nodes.add(new AssessmentNode(e.id(), e.x(), e.y(), e.w(), e.h(), e.parentId(),
                    false, false, e.name(), 0.0, null, null, 0.0, 0.0, 0.0));
        }
        List<AssessmentConnection> conns = new ArrayList<>();
        for (net.vheerden.archi.mcp.model.routing.ViewFixture.FixtureConnection c
                : f.getConnections()) {
            conns.add(new AssessmentConnection(c.id(), c.sourceId(), c.targetId(),
                    f.buildStoredPath(c), c.label() == null ? "" : c.label(), 1));
        }
        return assessor.assess(nodes, conns, true);
    }

    // ---- Fixture ----

    private static boolean hasHubPortSuggestion(LayoutAssessmentResult r) {
        return r.suggestions().stream().anyMatch(s -> s.contains("Hub-port allocation quality"));
    }

    /**
     * A single hub whose BOTTOM face carries {@code connections} terminals spread over
     * {@code distinctSlots} distinct along-face positions, so the view aggregate is exactly
     * {@code distinctSlots / connections}. Every spoke is its own element with one connection, so
     * no second face reaches the four-terminal face guard and the hub's BOTTOM face is the
     * view's only hub face — the minimum is therefore the ratio asked for.
     */
    private LayoutAssessmentResult assessOneHubFace(int connections, int distinctSlots) {
        AssessmentNode hub = node("hub", 600, 200, 300, 120);
        double faceY = 320;
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(hub);
        List<AssessmentConnection> conns = new ArrayList<>();
        for (int i = 0; i < connections; i++) {
            double slotX = 620 + 40 * (i % distinctSlots);
            double tx = 200 + 260 * i;
            double ty = 900;
            AssessmentNode spoke = node("s" + i, tx, ty, 80, 50);
            nodes.add(spoke);
            conns.add(new AssessmentConnection("c" + i, "hub", spoke.id(),
                    List.of(new double[]{750, 260},
                            new double[]{slotX, faceY},
                            new double[]{tx + 40, faceY},
                            new double[]{tx + 40, ty},
                            new double[]{tx + 40, ty + 25}),
                    "", 1));
        }
        return assessor.assess(nodes, conns, true);
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null,
                0.0, 0.0, 0.0);
    }
}
