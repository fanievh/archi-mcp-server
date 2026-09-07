package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Build-fired guard for every published surface that states which severity band a rated metric
 * sits in, or what buckets a metric's own rating.
 *
 * <p><strong>What drifted.</strong> The overall rating moved from a single-dimension three-tier
 * model to the two-dimensional layout-tier x routing-tier model, and the surfaces describing it
 * did not all move with it. A census found ten sites still publishing the superseded model — two
 * of them stating the same swapped pair ({@code nonOrthogonalTerminals} cosmetic,
 * {@code edgeCrossings} moderate), which is the exact inverse of what the assessor applies. A
 * reader deciding whether a {@code fair} rating is worth another remediation attempt was reading
 * the wrong answer off four different trees.</p>
 *
 * <p><strong>The bands are derived, never copied.</strong> Everything this guard compares the
 * published surfaces against is measured by driving {@link LayoutQualityAssessor} itself: the
 * metric names come from the breakdown the assessor actually emits, and each metric's band comes
 * from feeding that one metric to the live tier folds and reading which level survives the cap.
 * A guard holding a second committed list of bands would drift in step with the first one and
 * certify nothing.</p>
 *
 * <p><strong>Presence and absence, scoped to the enumerating region.</strong> Each surface is
 * probed inside the region that does the enumerating — the one line, the one list item, the one
 * heading block — never across the whole file. A whole-file scan passes on a file whose corrected
 * sentence lives three sections away from the stale one it was meant to replace, which is how a
 * sibling guard in this repo once stayed green through the deletion it existed to catch. The
 * anchors are required to match exactly once and fail loudly when they stop matching, so a
 * reworded surface reports a lost anchor instead of quietly checking an empty region.</p>
 *
 * <p><strong>Not text equality.</strong> The assertions are that specific spans are present and
 * that specific refuted spans are absent. The wording around them stays free.</p>
 *
 * <p>This guard is deliberately NOT in {@code tools/osgi-excluded-tests.txt}: it runs in the
 * default headless lane and in CI. A pawl the build never pulls is not a pawl.</p>
 */
public class RatingTierSurfaceParityTest {

    private static final String LAYOUT_ENGINE = "docs/layout-engine.md";
    private static final String GLOSSARY = "docs/glossary.md";
    private static final String VIEW_PATTERNS =
            "net.vheerden.archi.mcp/resources/reference/archimate-view-patterns.md";
    private static final String PROMPT = "prompts/repo-to-archimate-model.md";
    private static final String ACCESSOR_IMPL =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java";

    /** The two breakdown keys that are outputs of the fold, not inputs to it. */
    private static final Set<String> NOT_METRICS =
            Set.of("overall", "overallExcludingAcceptedCosmetics");

    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    private static final Pattern SEPARATOR = Pattern.compile("\\|[\\s:|-]+\\|");

    // ---- Derivation: the bands, measured off the assessor -----------------------------------

    /**
     * Every band a metric belongs to, keyed by band name, measured by driving the live folds.
     *
     * <p>The routing bands are measured at a given edge-coincidence count because M4's band is
     * count-gated: {@code connectionEdgeCoincidence} is Tier-2R below
     * {@code EDGE_COINCIDENCE_EGREGIOUS_MAX} and escalates into Tier-1R at or above it. Deriving
     * once would publish a single band for a metric that has two.</p>
     */
    private Map<String, Set<String>> deriveBands(int edgeCoincidenceCount) {
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        Map<String, String> allPass = allPassBreakdown(assessor);
        Map<String, Set<String>> bands = new LinkedHashMap<>();
        for (String band : List.of("Tier 1L", "Tier 2L", "Tier 3L", "Tier 1R", "Tier 2R", "Tier 3R")) {
            bands.put(band, new TreeSet<>());
        }
        List<String> unbanded = new ArrayList<>();

        for (String metric : allPass.keySet()) {
            Map<String, String> probe = new LinkedHashMap<>(allPass);
            probe.put(metric, "poor");
            String layoutBand = bandOf(layoutLevel(assessor, probe), "L");
            String routingBand = bandOf(routingLevel(assessor, probe, edgeCoincidenceCount), "R");
            if (layoutBand != null) {
                bands.get(layoutBand).add(metric);
            }
            if (routingBand != null) {
                bands.get(routingBand).add(metric);
            }
            if (layoutBand == null && routingBand == null) {
                unbanded.add(metric);
            }
        }

        if (!unbanded.isEmpty()) {
            fail("The assessor emits breakdown entries that no tier fold reads: " + unbanded
                    + ". Either a metric was added to the breakdown and never wired into "
                    + "computeLayoutTierLevel/computeRoutingTierLevel (it then affects no rating "
                    + "and the surfaces must not imply it does), or it is an output of the fold "
                    + "like 'overall' and belongs in NOT_METRICS. Do not delete this check.");
        }
        return bands;
    }

    /**
     * Which band a single-metric probe landed in, or {@code null} when this dimension does not
     * read the metric at all.
     *
     * <p>One metric driven to {@code poor} (level 3) and everything else at {@code pass} makes the
     * caps legible in the result: an uncapped band returns 3, a cap-fair band returns 2, a
     * cap-good band returns 1, and a dimension that never reads the metric returns 0.</p>
     */
    private static String bandOf(int level, String dimension) {
        return switch (level) {
            case 3 -> "Tier 1" + dimension;
            case 2 -> "Tier 2" + dimension;
            case 1 -> "Tier 3" + dimension;
            default -> null;
        };
    }

    /**
     * The breakdown key set the assessor really emits, every value forced to {@code pass}.
     *
     * <p>The keys come from a live clean call rather than a list held here, so a metric added to
     * the breakdown enters this guard's scope on the commit that adds it.</p>
     */
    private Map<String, String> allPassBreakdown(LayoutQualityAssessor assessor) {
        LayoutQualityAssessor.RatingResult clean = assessor.computeRatingWithBreakdown(
                0, 0, 100.0, 100, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 0);
        assertEquals("A view clean on every input must rate excellent; if it does not, this "
                + "harness is not driving the rating it claims to drive.",
                "excellent", clean.rating());
        Map<String, String> allPass = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : clean.breakdown().entrySet()) {
            if (NOT_METRICS.contains(entry.getKey())) {
                continue;
            }
            assertEquals("Breakdown entry '" + entry.getKey() + "' is not 'pass' on a clean view, "
                    + "so the derivation below cannot isolate one metric at a time.",
                    "pass", entry.getValue());
            allPass.put(entry.getKey(), "pass");
        }
        assertTrue("The clean breakdown carried no rated metrics; the derivation would be vacuous.",
                allPass.size() >= 15);
        return allPass;
    }

    private int layoutLevel(LayoutQualityAssessor assessor, Map<String, String> breakdown) {
        return (int) invoke(assessor, "computeLayoutTierLevel",
                new Class<?>[] {Map.class}, breakdown);
    }

    private int routingLevel(LayoutQualityAssessor assessor, Map<String, String> breakdown,
            int edgeCoincidenceCount) {
        return (int) invoke(assessor, "computeRoutingTierLevel",
                new Class<?>[] {Map.class, int.class}, breakdown, edgeCoincidenceCount);
    }

    /**
     * Calls one of the private tier folds.
     *
     * <p>They are private and stay private: reaching them from the test is not a reason to widen
     * production visibility. {@code RuntimeException} is caught alongside the checked family
     * because the two most likely futures both throw one — a fold made static makes the instance
     * call a {@link NullPointerException}, and a module policy makes {@code setAccessible} an
     * {@code InaccessibleObjectException} — and either would surface as a bare stack trace
     * instead of the instruction below.</p>
     */
    private Object invoke(LayoutQualityAssessor assessor, String name, Class<?>[] types,
            Object... args) {
        try {
            Method method = LayoutQualityAssessor.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(assessor, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new AssertionError("LayoutQualityAssessor." + name + " is no longer reachable, "
                    + "so the published bands can no longer be derived from the code. Re-point "
                    + "this check at whatever now folds the tiers rather than deleting it — a "
                    + "band table nothing derives is the drift this guard exists to prevent.", e);
        }
    }

    // ---- The derivation itself, asserted so the table below has a measured source ------------

    @Test
    public void shouldDeriveEveryBandFromTheLiveTierFolds_whenDrivenOneMetricAtATime() {
        Map<String, Set<String>> normal = deriveBands(0);
        Map<String, Set<String>> egregious =
                deriveBands(LayoutQualityAssessor.EDGE_COINCIDENCE_EGREGIOUS_MAX);

        for (Map.Entry<String, Set<String>> band : normal.entrySet()) {
            assertTrue("Band " + band.getKey() + " derived empty. A band with no members means the "
                    + "fold changed shape and this derivation is measuring the wrong thing.",
                    !band.getValue().isEmpty());
        }

        // The count-gated escalation is a derived fact, not an assumption: raising the
        // edge-coincidence count to the egregious threshold moves exactly one metric into Tier 1R
        // and removes none.
        Set<String> escalated = new TreeSet<>(egregious.get("Tier 1R"));
        escalated.removeAll(normal.get("Tier 1R"));
        assertEquals("Reaching EDGE_COINCIDENCE_EGREGIOUS_MAX must escalate exactly "
                + "connectionEdgeCoincidence into Tier 1R.",
                Set.of("connectionEdgeCoincidence"), escalated);
        assertTrue("The egregious count must not remove any metric from Tier 1R.",
                egregious.get("Tier 1R").containsAll(normal.get("Tier 1R")));

        // Two claims the surfaces below repeatedly got backwards, pinned against the derivation so
        // a reader of this test can see which way round they actually are.
        assertTrue("nonOrthogonalTerminals must derive as Tier 2R (cap fair), not Tier 3R.",
                normal.get("Tier 2R").contains("nonOrthogonalTerminals"));
        assertTrue("edgeCrossings must derive as Tier 3R (cap good), not Tier 2R.",
                normal.get("Tier 3R").contains("edgeCrossings"));
    }

    // ---- Published band table vs the derivation ----------------------------------------------

    private static final String BAND_TABLE_HEADER_PREFIX = "| Band | Metrics |";

    @Test
    public void shouldPublishABandTableMatchingTheDerivedBands_inBothDirections() {
        Map<String, Set<String>> derived = deriveBands(0);
        Map<String, Set<String>> published = readBandTable();

        assertEquals("The published band table names a different set of bands than the assessor "
                + "folds.", derived.keySet(), published.keySet());

        List<String> failures = new ArrayList<>();
        for (String band : derived.keySet()) {
            Set<String> want = derived.get(band);
            Set<String> got = published.get(band);
            if (!want.equals(got)) {
                Set<String> missing = new TreeSet<>(want);
                missing.removeAll(got);
                Set<String> extra = new TreeSet<>(got);
                extra.removeAll(want);
                failures.add(band + ": table missing " + missing + ", table has unmeasured "
                        + extra);
            }
        }
        if (!failures.isEmpty()) {
            fail("The band table in " + LAYOUT_ENGINE + " disagrees with the bands derived from "
                    + "LayoutQualityAssessor. Membership is compared in BOTH directions — a subset "
                    + "check cannot see a metric the table forgot, which is how the served "
                    + "description lost its seventh Tier-2R member.\n  "
                    + String.join("\n  ", failures));
        }

        // The table publishes the unconditional Tier-1R members; the count-gated escalation is a
        // condition, not a membership, so it must be stated rather than folded into the cell.
        String region = headingBlock(read(LAYOUT_ENGINE), "#### M6 — Two-Dimensional Overall Rating");
        assertTrue("The band table must state that connectionEdgeCoincidence escalates into "
                + "Tier 1R on an egregious count; a table that only lists it under Tier 2R "
                + "publishes one band for a metric that has two.",
                region.contains("connectionEdgeCoincidence")
                        && region.contains("EDGE_COINCIDENCE_EGREGIOUS_MAX"));
    }

    /**
     * Every published value of the escalation threshold, checked against the constant.
     *
     * <p>The band table and the M4 row both spell the number out beside the constant's name, which
     * is the right thing for a reader and the wrong thing to leave unchecked: the rest of this
     * class derives what it asserts, and a bare literal in prose is exactly the second committed
     * copy the class javadoc argues against. Matched wherever the name is followed by {@code = N}
     * or {@code (N)}, so a new site publishing the threshold is covered on the commit that adds it
     * rather than on the one that notices.</p>
     */
    @Test
    public void shouldPublishTheEscalationThresholdItsRealValue_whereverItNamesTheConstant() {
        int actual = LayoutQualityAssessor.EDGE_COINCIDENCE_EGREGIOUS_MAX;
        Matcher m = Pattern.compile("EDGE_COINCIDENCE_EGREGIOUS_MAX`?\\s*(?:=|\\()\\s*(\\d+)")
                .matcher(read(LAYOUT_ENGINE));
        List<String> failures = new ArrayList<>();
        int found = 0;
        while (m.find()) {
            found++;
            if (Integer.parseInt(m.group(1)) != actual) {
                failures.add("published as " + m.group(1) + " in \"" + m.group() + "\"");
            }
        }
        assertTrue("No published value of EDGE_COINCIDENCE_EGREGIOUS_MAX was found in "
                + LAYOUT_ENGINE + ". The band table and the M4 row both state it; if the wording "
                + "changed, re-point this pattern rather than letting the check pass on nothing.",
                found > 0);
        if (!failures.isEmpty()) {
            fail("EDGE_COINCIDENCE_EGREGIOUS_MAX is " + actual + " in LayoutQualityAssessor but "
                    + LAYOUT_ENGINE + " publishes a different value — " + String.join("; ", failures)
                    + ". The constant is the authority; correct the document.");
        }
    }

    /** Parses the six-band table out of the M6 section. Scoped to that section, not the file. */
    private Map<String, Set<String>> readBandTable() {
        String[] lines = headingBlock(read(LAYOUT_ENGINE),
                "#### M6 — Two-Dimensional Overall Rating").split("\n", -1);
        int header = -1;
        int matches = 0;
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].trim().startsWith(BAND_TABLE_HEADER_PREFIX)
                    && SEPARATOR.matcher(lines[i + 1].trim()).matches()) {
                matches++;
                if (header < 0) {
                    header = i;
                }
            }
        }
        if (matches != 1) {
            throw new AssertionError("Expected exactly one band table under \"#### M6 — "
                    + "Two-Dimensional Overall Rating\" in " + LAYOUT_ENGINE + ", found " + matches
                    + ". It must start with \"" + BAND_TABLE_HEADER_PREFIX + "\" and be followed "
                    + "by a markdown separator row. Without the separator the first data row is "
                    + "read as the separator and silently dropped, so do not relax this check.");
        }
        Map<String, Set<String>> table = new LinkedHashMap<>();
        for (int i = header + 2; i < lines.length; i++) {   // +2 skips the |---| separator
            String line = lines[i].trim();
            if (line.isEmpty()) {
                break;
            }
            String[] cells = line.split("\\|", -1);
            if (cells.length != 5) {                        // leading + 3 cells + trailing
                throw new AssertionError("Band-table row " + (table.size() + 1) + " in "
                        + LAYOUT_ENGINE + " has " + (cells.length - 2) + " cells, expected 3 "
                        + "(band | metrics | cap). A literal '|' inside a cell counts as a column "
                        + "separator here — there is no escaping — so write it another way:\n  "
                        + line);
            }
            String band = cells[1].replace("*", "").trim();
            if (table.containsKey(band)) {
                throw new AssertionError("Band \"" + band + "\" appears on two rows of the band "
                        + "table in " + LAYOUT_ENGINE + ". A map would keep only the last one, so "
                        + "a stray duplicate row carrying the WRONG membership could sit beside "
                        + "the right one and never be compared against anything. Delete the "
                        + "duplicate rather than relaxing this check.");
            }
            table.put(band, new TreeSet<>(backticked(cells[2])));
        }
        return table;
    }

    // ---- Per-surface probes ------------------------------------------------------------------

    /**
     * One surveyed surface: where it is, how its enumerating region is found, and the spans that
     * must and must not appear inside that region.
     */
    private record Site(String id, String file, Region region, String anchor,
            List<String> absent, List<String> present) {}

    /**
     * How a site's enumerating region is found.
     *
     * <p>{@code WHOLE_FILE} is the one deliberate exception to this class's scope-to-the-region
     * rule, and it is used for exactly one site — the superseded rating section, whose correction
     * was to DELETE it. The rule exists because a region-less <em>presence</em> probe can be
     * satisfied by text three sections away from where it belongs. An <em>absence</em> probe
     * inverts that: whole-file is the strongest scope available, not the weakest, and it is the
     * only scope that can express "this heading is gone" at all — every anchor that could scope it
     * more narrowly was deleted along with the section. The companion presence probe is a heading
     * that must exist exactly once, asserted separately below, so the site cannot pass by having
     * lost both sections.</p>
     */
    private enum Region { LINE, HEADING, LIST_ITEM, WHOLE_FILE }

    private static List<Site> sites() {
        return List.of(
                new Site("1 view-patterns routing workflow", VIEW_PATTERNS, Region.LINE,
                        "edge crossings (connections crossing each other",
                        List.of("(Tier 2)", "Tier 3 (cosmetic"),
                        List.of("Tier 3R", "Tier 2R")),
                new Site("2 view-patterns full re-route advice", VIEW_PATTERNS, Region.LINE,
                        "accept the residual non-orthogonal terminals",
                        List.of("(Tier 3, cosmetic)"),
                        List.of("Tier 2R")),
                new Site("3 layout-engine superseded rating section", LAYOUT_ENGINE,
                        Region.WHOLE_FILE, "",
                        List.of("Overall Rating (Severity-Tiered)"),
                        List.of("#### M6 — Two-Dimensional Overall Rating")),
                new Site("4 prompt iteration advice", PROMPT, Region.LINE,
                        "keep iterating only when",
                        List.of("Tier-1/2 metric"),
                        List.of("Tier-2R")),
                new Site("5 crossing-veto rationale", ACCESSOR_IMPL, Region.LIST_ITEM,
                        "<b>Crossing veto</b>",
                        List.of("Tier 3 cosmetic", "Tier 2 moderate"),
                        List.of("revert that connection")),
                new Site("6 layout-engine M4 row", LAYOUT_ENGINE, Region.LINE,
                        "**M4**",
                        List.of("Routing Tier 1R with thresholds"),
                        List.of("Routing Tier 2R")),
                new Site("7 glossary Tier 1R bullet", GLOSSARY, Region.LINE,
                        "**Tier 1R**",
                        List.of("M4 edge coincidence"),
                        List.of("pass-throughs", "coincident segments")),
                new Site("8 layout-engine terminals bucketing", LAYOUT_ENGINE, Region.HEADING,
                        "#### Non-Orthogonal Terminals",
                        List.of("1-3 = \"fair\", 4+ = \"poor\""),
                        List.of("0.10", "0.30", "\"good\"")),
                new Site("9 layout-engine overlaps bucketing", LAYOUT_ENGINE, Region.HEADING,
                        "#### Element Overlaps",
                        List.of("1-3 = \"fair\", 4+ = \"poor\""),
                        List.of("> 0 = \"poor\"")),
                new Site("10 layout-engine label-overlaps bucketing", LAYOUT_ENGINE, Region.HEADING,
                        "#### Label Overlaps",
                        List.of("> 0 = \"fair\""),
                        List.of("<= 2 = \"good\"")));
    }

    /**
     * The file must carry exactly one overall-rating section.
     *
     * <p>The defect site 3 records was not merely a stale section — it was <em>two</em> sections
     * forty lines apart, contradicting each other, with the wrong one outranking the right one by
     * heading level. Probing only for the absence of the old title would go green on a file that
     * had grown a third one under a new name, so the surviving section is counted, not just found.
     * </p>
     */
    @Test
    public void shouldCarryExactlyOneOverallRatingSection_inTheLayoutEngineDoc() {
        List<String> headings = new ArrayList<>();
        boolean fenced = false;
        for (String line : read(LAYOUT_ENGINE).split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                fenced = !fenced;
            } else if (!fenced && trimmed.startsWith("#") && trimmed.contains("Overall Rating")) {
                headings.add(trimmed);
            }
        }
        assertEquals("Expected exactly one overall-rating section in " + LAYOUT_ENGINE
                + ", found " + headings.size() + ": " + headings + ". Two sections describing the "
                + "same rating is the drift this site exists to prevent — the superseded one is "
                + "deleted, not kept alongside.", 1, headings.size());
    }

    @Test
    public void shouldNotPublishASupersededTierClaim_atAnySurveyedSurface() {
        List<String> failures = new ArrayList<>();
        for (Site site : sites()) {
            String region = regionOf(site);
            for (String span : site.absent()) {
                if (region.contains(span)) {
                    failures.add("site " + site.id() + " (" + site.file() + ") still publishes the "
                            + "refuted span \"" + span + "\".");
                }
            }
            for (String span : site.present()) {
                if (!region.contains(span)) {
                    failures.add("site " + site.id() + " (" + site.file() + ") does not carry the "
                            + "corrected span \"" + span + "\".");
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " tier/bucketing claim(s) disagree with LayoutQualityAssessor. "
                    + "The code is the authority: correct the surface, never move a metric between "
                    + "bands to satisfy a document.\n  " + String.join("\n  ", failures));
        }
    }

    private String regionOf(Site site) {
        String text = read(site.file());
        return switch (site.region()) {
            case WHOLE_FILE -> text;
            case LINE -> singleLine(text, site.anchor(), site.file());
            case HEADING -> headingBlock(text, site.anchor());
            case LIST_ITEM -> listItemBlock(text, site.anchor(), site.file());
        };
    }

    // ---- The served description ---------------------------------------------------------------

    @Test
    public void shouldServeBandListsMatchingTheDerivedBands_forAssessLayout() {
        String served = servedDescriptionOf("assess-layout");
        Map<String, Set<String>> derived = deriveBands(0);

        Map<String, String> clauses = Map.of(
                "Tier 1R", "Tier-1R \\(critical: ([^)]*)\\)",
                "Tier 2R", "Tier-2R \\(cap 'fair': ([^)]*)\\)",
                "Tier 3R", "Tier-3R \\(cap 'good': ([^)]*)\\)",
                "Tier 1L", "Tier-1L \\(critical: ([^)]*)\\)",
                "Tier 2L", "Tier-2L \\(cap 'fair': ([^)]*)\\)",
                "Tier 3L", "Tier-3L \\(cap 'good': ([^)]*)\\)");

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> clause : clauses.entrySet()) {
            // Exactly one occurrence, matching the discipline the region helpers below apply.
            // Taking the first match and moving on would keep validating an original clause
            // while a second, drifted copy of the same enumeration sat further down the
            // description unchecked — a band list can only be authoritative if it is singular.
            Matcher m = Pattern.compile(clause.getValue()).matcher(served);
            List<String> occurrences = new ArrayList<>();
            while (m.find()) {
                occurrences.add(m.group(1));
            }
            if (occurrences.size() != 1) {
                failures.add("the served assess-layout description carries " + occurrences.size()
                        + " enumerations matching /" + clause.getValue() + "/ for \""
                        + clause.getKey() + "\"; it must carry exactly one. None means the clause "
                        + "was reworded — re-point this check rather than deleting it. Several "
                        + "means the band is published twice and the copies can drift apart.");
                continue;
            }
            Set<String> names = new TreeSet<>();
            for (String name : occurrences.get(0).split(",")) {
                if (!name.isBlank()) {
                    names.add(name.trim());
                }
            }
            if (!names.equals(derived.get(clause.getKey()))) {
                Set<String> missing = new TreeSet<>(derived.get(clause.getKey()));
                missing.removeAll(names);
                Set<String> extra = new TreeSet<>(names);
                extra.removeAll(derived.get(clause.getKey()));
                failures.add(clause.getKey() + ": served list missing " + missing
                        + ", served list has unmeasured " + extra);
            }
        }

        // The escalation is a condition on a member of Tier 2R, so it is stated rather than listed.
        //
        // The threshold is BUILT from the constant, not spelled out here. A surface that publishes
        // the number 7 beside a guard that also hardcodes 7 proves only that two copies agree; the
        // whole premise of this class is that published claims are checked against the code, and a
        // literal in the expected string would be the one place that premise silently lapsed.
        String escalation = "escalates to Tier-1R once its count reaches "
                + LayoutQualityAssessor.EDGE_COINCIDENCE_EGREGIOUS_MAX;
        if (!served.contains(escalation)) {
            failures.add("the served description does not state the count-gated escalation of "
                    + "connectionEdgeCoincidence into Tier-1R at its real threshold. Expected to "
                    + "find: \"" + escalation + "\". Without it the Tier-1R clause reads as the "
                    + "whole band when it is only the unconditional part of it — and a stale "
                    + "threshold there sends a reader chasing the wrong count.");
        }

        // A tier CAPS its contribution; it does not pin the rating. A lone Tier-2 metric bucketed
        // to 'good' contributes level 1 and the view still rates 'good'.
        //
        // The probe is conditional rather than a flat ban on the phrase, because the QUALIFIED
        // form of it is true and is itself a correction this surface already carries: a Tier-2
        // metric *rated 'fair' or 'poor'* does hold the dimension at 'fair'. What was shipped and
        // withdrawn is the unqualified claim that any non-pass Tier-2 metric does. Banning the
        // phrase outright would flag the correct sentence and push an editor into deleting it.
        //
        // Scoped to the SENTENCE, and to EVERY occurrence. Asking only whether the qualifier
        // appears somewhere in this description would let a second, unqualified sentence ship
        // anywhere in it while the existing qualified sentence kept the check satisfied — the
        // qualifier would be certifying a sentence it is not attached to. That is the same
        // region-versus-file failure this class scopes every other probe against, and a guard
        // that reintroduced it in its own body would be worth very little.
        for (int at = served.indexOf(PINNING); at >= 0; at = served.indexOf(PINNING, at + 1)) {
            String sentence = sentenceAround(served, at);
            if (!(sentence.contains("rated 'fair' or 'poor'") && sentence.contains("does not pin"))) {
                failures.add("the served description says a Tier-2 metric \"" + PINNING + "\" "
                        + "'fair' in a sentence that does not qualify the claim. A tier CAPS its "
                        + "members' contribution; only a member bucketed to 'fair' or 'poor' holds "
                        + "the dimension there, and one bucketed to 'good' leaves the view at "
                        + "'good'. The sentence at fault is:\n    " + sentence.trim()
                        + "\n  Restore the qualifier rather than removing this check.");
            }
        }

        if (!failures.isEmpty()) {
            fail("The served assess-layout band enumeration disagrees with the derived bands.\n  "
                    + String.join("\n  ", failures));
        }
    }

    private String servedDescriptionOf(String toolName) {
        CommandRegistry registry = new CommandRegistry();
        SessionManager sessions =
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(
                new BaseTestAccessor(), new ResponseFormatter(), registry, sessions);
        McpSchema.Tool tool = registry.getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + toolName))
                .tool();
        return tool.description();
    }

    /** The claim that is only true when qualified. */
    private static final String PINNING = "holds the rating at";

    /**
     * The sentence of {@code text} containing the character at {@code at}.
     *
     * <p>Bounded by a full stop followed by whitespace, or by a line break. Deliberately simple:
     * this runs over a served tool description, where the alternative — a sentence splitter that
     * understands abbreviations — would be more machinery than the check is worth, and erring
     * SHORT is safe here. A window narrower than the real sentence can only fail a qualified
     * claim (loudly, printing the span), never pass an unqualified one.</p>
     */
    private static String sentenceAround(String text, int at) {
        int start = 0;
        for (int i = at; i > 0; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || (c == ' ' && i >= 2 && text.charAt(i - 2) == '.')) {
                start = i;
                break;
            }
        }
        int end = text.length();
        for (int i = at; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '.' && i + 1 < text.length() && text.charAt(i + 1) == ' ')) {
                end = i + 1;
                break;
            }
        }
        return text.substring(start, end);
    }

    // ---- Region resolution --------------------------------------------------------------------

    /**
     * The one line containing {@code needle}.
     *
     * <p>Exactly one, or this fails. An anchor matching none has been reworded and an anchor
     * matching several makes the scan guess — either way the check stops checking what it names,
     * and it must say so rather than probe an empty string.</p>
     */
    private static String singleLine(String text, String needle, String file) {
        List<String> hits = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.contains(needle)) {
                hits.add(line);
            }
        }
        if (hits.size() != 1) {
            throw new AssertionError("Anchor \"" + needle + "\" matches " + hits.size()
                    + " lines in " + file + "; it must identify exactly one. Re-point the anchor "
                    + "at the line that now enumerates the tiers rather than widening it.");
        }
        return hits.get(0);
    }

    /**
     * The block introduced by a markdown heading, ending at the next heading.
     *
     * <p>Fenced code blocks are carried through rather than scanned for headings. The M6 section
     * opens with two ```` ``` ```` fences before its band table, so a line beginning {@code #}
     * added inside one — a comment in the fold pseudocode, say — would otherwise end the region
     * early and take the table out of scope for a reason that has nothing to do with tiers.</p>
     */
    private static String headingBlock(String text, String heading) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        int matches = 0;
        boolean fenced = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("```")) {
                fenced = !fenced;
            } else if (!fenced && lines[i].trim().equals(heading)) {
                matches++;
                if (start < 0) {
                    start = i;
                }
            }
        }
        if (matches != 1) {
            throw new AssertionError("Heading \"" + heading + "\" matches " + matches + " lines; "
                    + "it must identify exactly one section. A heading that matches none was "
                    + "renamed — re-point this check rather than letting it scan an empty region.");
        }
        StringBuilder block = new StringBuilder();
        fenced = false;
        for (int i = start + 1; i < lines.length; i++) {
            if (lines[i].trim().startsWith("```")) {
                fenced = !fenced;
            } else if (!fenced && lines[i].startsWith("#")) {
                break;
            }
            block.append(lines[i]).append('\n');
        }
        return block.toString();
    }

    /** An HTML list item in a javadoc block: the anchor line through its closing tag. */
    private static String listItemBlock(String text, String needle, String file) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        int matches = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                matches++;
                if (start < 0) {
                    start = i;
                }
            }
        }
        if (matches != 1) {
            throw new AssertionError("Anchor \"" + needle + "\" matches " + matches + " lines in "
                    + file + "; it must identify exactly one list item.");
        }
        StringBuilder block = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            block.append(lines[i]).append('\n');
            if (lines[i].contains("</li>")) {
                return block.toString();
            }
        }
        throw new AssertionError("The list item anchored by \"" + needle + "\" in " + file
                + " has no closing </li>; the region would run to the end of the file.");
    }

    private static List<String> backticked(String cell) {
        long backticks = cell.chars().filter(c -> c == '`').count();
        if (backticks % 2 != 0) {
            throw new AssertionError("Unbalanced backtick in a band-table cell — a metric name "
                    + "would be read short:\n  " + cell.trim());
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = BACKTICKED.matcher(cell);
        while (m.find()) {
            found.add(m.group(1));
        }
        return List.copyOf(found);
    }

    /** Reads a repo-relative file by walking upward from the working directory. */
    private static String read(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the checkout.");
    }
}
