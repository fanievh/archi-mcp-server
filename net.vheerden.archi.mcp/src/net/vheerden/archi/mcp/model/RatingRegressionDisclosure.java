package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Did this call leave the view worse than it found it?
 *
 * <p>One comparison, shared by every tool that commits a state it also measured. The predicate is
 * the composite the quality loops rank their own attempts by — the rating ordinal first, the
 * tier-weighted score as the tie-break — read off those loops rather than restated. Two sources for
 * "is this worse?" can disagree; one cannot.</p>
 *
 * <p><strong>Why the caller supplies the code, the subject and the tail.</strong> The predicate is
 * the same for every tool; only the sentence differs, and only in who did it and what the remedy
 * costs. Hard-coding those meant a second tool needed a second copy of the comparison. Passing them
 * keeps the copy count at one. The warning code is a parameter of
 * {@link #putDisclosure} as well as of {@link #warnings} for a specific reason: when the emitter
 * carried a parameter and the approval-card filter kept a literal, generalising the first silently
 * dropped the disclosure from the second. Handing both the same value makes that divergence
 * unrepresentable.</p>
 *
 * <p>The two assessment readers the comparison is taken on — {@code tierWeightedScore} and
 * {@code getMetricCount} — deliberately stay on {@link QualityTargetTermination}: they have callers
 * and pins of their own that have nothing to do with disclosure.</p>
 */
public final class RatingRegressionDisclosure {

    private RatingRegressionDisclosure() {}

    /**
     * Every metric the tier-weighted score reads, paired with the words a caller reads, in the
     * order the score itself weights them (worst tier first).
     *
     * <p>The keys are exactly {@link QualityTargetTermination#getMetricCount}'s, so the delta report cannot drift away from
     * the number the comparison was actually taken on. {@code hubPortQuality} is a binary
     * below-threshold flag rather than a population, and its label says so.</p>
     */
    private static final String HUB_PORT_QUALITY = "hubPortQuality";

    private static final String[][] SCORED_METRICS = {
        {"overlaps", "element overlaps"},
        {"boundaryViolations", "boundary violations"},
        {"parentLabelObscured", "obscured container labels"},
        {"passThroughs", "connections crossing elements"},
        {"interiorTerminations", "connection ends inside an element"},
        {"zigzags", "zigzagging connections"},
        {"coincidentSegments", "coincident segments"},
        {"nonOrthogonalTerminals", "non-orthogonal terminals"},
        {"connectionEdgeCoincidence", "connections running along an element edge"},
        {HUB_PORT_QUALITY, "hub port quality fell below the fair threshold"},
        {"labelOverlaps", "label overlaps"},
        {"labelTruncations", "truncated labels"},
        {"edgeCrossings", "edge crossings"},
    };

    /**
     * The rating-band inputs {@link QualityTargetTermination#tierWeightedScore} deliberately ignores, paired with the words a
     * caller reads.
     *
     * <p>Each caps the overall band on its own — {@code LayoutQualityAssessor.computeLayoutTierLevel}
     * reads {@code spacing}, {@code offCanvas} and {@code hubNeighbourCrowding} at Tier 2L and
     * {@code alignment} at Tier 3L, and {@code computeRoutingTierLevel} reads
     * {@code nonOrthogonalInteriorSegments} and {@code offFaceParallelTerminals} — while contributing
     * nothing to the score. So a band drop can be driven entirely by one of them with every scored
     * metric flat, and reporting only {@link #SCORED_METRICS} would leave that regression with no
     * evidence beside it at all.</p>

     * <p>Together the two arrays cover every dimension either tier reads, which is what lets the
     * message promise evidence for any regression it declares.</p>
     *
     * <p>Only {@code offCanvas} has an integer count; the rest carry none, so
     * these are reported by their <em>rating</em> ({@code good -> fair}) read from the breakdown rather
     * than by a count. A named dimension moving between two named bands is still a fact the caller can
     * check — an omitted clause is not.</p>
     */
    /** {@code LayoutQualityAssessor.ratingOrdinal} for "not-applicable" and for anything unrecognised. */
    private static final int UNRATED_ORDINAL = 0;

    private static final String[][] BAND_ONLY_METRICS = {
        {"spacing", "element spacing"},
        {"offCanvas", "off-canvas elements"},
        {"hubNeighbourCrowding", "hub neighbour crowding"},
        {"alignment", "element alignment"},
        {"nonOrthogonalInteriorSegments", "non-orthogonal interior segments"},
        {"offFaceParallelTerminals", "off-face parallel terminals"},
    };

    /**
     * True when {@code after} is worse than {@code before} on the same composite the quality loops
     * rank their own attempts by: the rating ordinal first, the tier-weighted score as the
     * tie-break.
     *
     * <p>Read off the loops' comparison rather than restated. Two sources for "is this worse?" can
     * disagree; one cannot — and a tool that reports <em>no regression</em> about a change it
     * internally scored as worse is a false all-clear. The score is the tie-break for a concrete
     * reason: the five rating bands are too coarse to discriminate on their own, so a metric can
     * grow substantially inside one band without the ordinal moving at all.</p>
     *
     * <p>A missing measurement is not a regression. This is a disclosure about a comparison that was
     * actually taken, so with either side absent it makes no claim.</p>
     */
    public static boolean hasRegressed(
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        if (before == null || after == null) {
            return false;
        }
        int beforeOrdinal = LayoutQualityAssessor.ratingOrdinal(before.overallRating());
        int afterOrdinal = LayoutQualityAssessor.ratingOrdinal(after.overallRating());
        if (beforeOrdinal == UNRATED_ORDINAL || afterOrdinal == UNRATED_ORDINAL) {
            // "not-applicable" — a view too small to rate — and any rating this version does not
            // recognise both land on ordinal 0. Neither side was ever a band, so there is no band to
            // have dropped and no tie to break: a disclosure phrased around a rating that never meant
            // anything is worse than no disclosure.
            return false;
        }
        if (afterOrdinal != beforeOrdinal) {
            return afterOrdinal < beforeOrdinal;
        }
        return QualityTargetTermination.tierWeightedScore(after)
                > QualityTargetTermination.tierWeightedScore(before);
    }

    /**
     * The rating-regression disclosure for a completed quality loop: a single-entry list when the
     * committed state is worse than the state the loop was handed, empty otherwise.
     *
     * <p>Both assessments are in hand at the comparison site, so the message names the metrics that
     * moved and by how much. That is not decoration. On a same-band regression the two ratings are
     * <em>identical</em> on the wire, and a regression warning beside two equal ratings reads as a
     * self-contradiction the caller has no way to check. The deltas are the facts that make it
     * checkable.</p>
     *
     * <p>The tier-weighted score itself is deliberately absent from the message and from the
     * response. Its weights are an internal heuristic that moves whenever the tier model does;
     * publishing the number would make it a permanent response contract and invite callers to
     * optimise against it. Facts the caller can act on go in structured fields, judgment goes in
     * prose, and the raw score is neither.</p>
     */
    public static List<StructuredWarningDto> warnings(String warningCode, String subject,
            String appliedTail, AssessLayoutResultDto before, AssessLayoutResultDto after) {
        return warnings(warningCode, subject, appliedTail, "undo", before, after);
    }

    /**
     * The same disclosure with the remedy named explicitly, for a caller whose remedy is not
     * {@code undo}.
     *
     * <p>The evidence half of the sentence — which way the rating moved and which metrics moved
     * with it — is composed by the same code on every arm, so a rescoped disclosure cannot quietly
     * carry less measurement than the applied one. What varies is the tail and the tool named
     * beside it: a run that was queued or is awaiting approval has not been applied, and
     * {@code undo} there would revert whatever command actually sits on top of the stack.</p>
     *
     * <p>Pass an empty {@code remediationTool} when no MCP tool is the recovery — the field is
     * {@code NON_EMPTY} and drops out of the JSON rather than naming a tool that does not help.
     * Abstaining is the honest option; a wrong tool in a structured field reads as measured fact.</p>
     */
    public static List<StructuredWarningDto> warnings(String warningCode, String subject,
            String tail, String remediationTool,
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        if (!hasRegressed(before, after)) {
            return List.of();
        }
        return List.of(new StructuredWarningDto(
                warningCode,
                describeRegression(subject, tail, before, after),
                remediationTool, List.of()));
    }

    /**
     * Writes the rating disclosure onto an approval card's {@code proposedChanges} map.
     *
     * <p>Approval mode overrides {@code nextSteps} with fixed boilerplate for every tool, so the card
     * and the {@code preview} entity are the only channels a regression can travel on that path — and
     * {@code proposedChanges} is the only one a later {@code list-pending-approvals} exposes. Carrying
     * the pre-call rating alone is not enough: on a <em>within-band</em> regression it equals
     * {@code achievedRating} exactly, so a human reading the card sees two identical ratings and no
     * indication that approving makes the view worse.</p>
     *
     * <p>The regression entry is the warning's own message — the metric deltas and the remedy — not a
     * bare flag. A flag would say something changed while leaving the reader unable to say what to.</p>
     */
    public static void putDisclosure(Map<String, Object> proposedChanges, String warningCode,
            String ratingBefore, List<StructuredWarningDto> structuredWarnings) {
        proposedChanges.put("ratingBefore", ratingBefore);
        if (structuredWarnings == null) {
            return;
        }
        structuredWarnings.stream()
                .filter(w -> warningCode.equals(w.code()))
                .findFirst()
                .ifPresent(w -> proposedChanges.put("ratingRegression", w.message()));
    }

    /** The disclosure sentence: which way the rating moved, what got worse, and how to get back. */
    private static String describeRegression(String subject, String appliedTail,
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        StringBuilder sb = new StringBuilder(
                subject + " left this view worse than it found it: ");
        String beforeRating = before.overallRating();
        String afterRating = after.overallRating();
        if (LayoutQualityAssessor.ratingOrdinal(afterRating)
                < LayoutQualityAssessor.ratingOrdinal(beforeRating)) {
            sb.append("overall rating dropped from '").append(beforeRating)
                    .append("' to '").append(afterRating).append("'");
        } else {
            sb.append("the overall rating band is unchanged at '").append(afterRating)
                    .append("', but the metrics behind it got worse");
        }
        String worsened = describeWorsenedMetrics(before, after);
        String worsenedInputs = describeWorsenedBandInputs(before, after);
        if (worsened != null) {
            sb.append(". Worsened: ").append(worsened);
        }
        if (worsenedInputs != null) {
            sb.append(worsened != null ? ", " : ". Worsened: ").append(worsenedInputs);
        }
        if (worsened == null && worsenedInputs == null) {
            // Reachable only when the breakdown is absent: every path that sets the predicate true
            // moves either a scored count or a band input. Say so rather than trailing off — a
            // regression claim with no evidence beside it is the thing this message exists to avoid.
            sb.append(". The per-metric detail is unavailable for this run, so compare the two "
                    + "ratings and re-run assess-layout for the breakdown");
        }
        sb.append(". ").append(appliedTail);
        return sb.toString();
    }

    /** Every scored metric whose count rose, with its before and after value; null when none did. */
    private static String describeWorsenedMetrics(
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        StringBuilder sb = new StringBuilder();
        for (String[] metric : SCORED_METRICS) {
            int beforeCount = QualityTargetTermination.getMetricCount(metric[0], before);
            int afterCount = QualityTargetTermination.getMetricCount(metric[0], after);
            if (afterCount > beforeCount) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                if (HUB_PORT_QUALITY.equals(metric[0])) {
                    // A binary below-threshold flag over one aggregate score, not a population.
                    // Rendered "0 -> 1" beside twelve genuine counts it reads as "one hub face
                    // degraded", and no such per-face number exists.
                    sb.append(metric[1]);
                } else {
                    sb.append(metric[1]).append(' ').append(beforeCount)
                            .append(" -> ").append(afterCount);
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Every band input whose <em>rating</em> fell, as {@code name good -> fair}; null when none did.
     *
     * <p>Read from the breakdown rather than from a count because three of the four have no count to
     * read. Without this a band drop driven only by spacing, off-canvas placement, hub crowding or
     * alignment would be disclosed with no evidence at all — see {@link #BAND_ONLY_METRICS}.</p>
     */
    private static String describeWorsenedBandInputs(
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        Map<String, String> beforeBreakdown = before.ratingBreakdown();
        Map<String, String> afterBreakdown = after.ratingBreakdown();
        if (beforeBreakdown == null || afterBreakdown == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String[] metric : BAND_ONLY_METRICS) {
            String beforeRating = beforeBreakdown.get(metric[0]);
            String afterRating = afterBreakdown.get(metric[0]);
            if (beforeRating == null || afterRating == null) {
                continue;
            }
            if (breakdownOrdinal(afterRating) < breakdownOrdinal(beforeRating)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(metric[1]).append(' ').append(beforeRating)
                        .append(" -> ").append(afterRating);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * A breakdown entry's rank, where "pass" is the top and outranks every band.
     *
     * <p>A {@code ratingBreakdown} entry is not one of the five overall-rating bands. A dimension
     * with nothing to report reads {@code "pass"} — cleaner than {@code "excellent"}, not a lesser
     * form of it — and {@code LayoutQualityAssessor.ratingOrdinal} does not know the word, so it
     * lands on the unknown-ordinal 0. Ranked that way, a dimension going {@code pass -> fair} sorts
     * as an <em>improvement</em> and disappears from the message.</p>
     *
     * <p>That is the most common shape of a spacing regression, not a corner case: a run that
     * pushes elements off the canvas moves {@code offCanvas} exactly {@code pass -> fair}. Measured
     * on a live view, a run that doubled the overlaps and pushed ten elements off-canvas named the
     * overlaps and the crossings and said nothing about the off-canvas move at all — while this
     * class's own contract promises evidence for every dimension that worsened.</p>
     *
     * <p>Unknown values still rank 0, so a value this version does not recognise can never be
     * reported as having fallen from or to anything.</p>
     */
    private static int breakdownOrdinal(String breakdownRating) {
        return "pass".equals(breakdownRating)
                ? LayoutQualityAssessor.ratingOrdinal("excellent") + 1
                : LayoutQualityAssessor.ratingOrdinal(breakdownRating);
    }
}
