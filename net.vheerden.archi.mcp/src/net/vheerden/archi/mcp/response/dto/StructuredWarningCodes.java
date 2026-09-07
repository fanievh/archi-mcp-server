package net.vheerden.archi.mcp.response.dto;

/**
 * Canonical {@link StructuredWarningDto#code} constants.
 *
 * <p>Test fixtures and documentation reference these constants rather than
 * string literals. Codes are stable; once published, never renamed (only
 * deprecated and superseded by a new code). This is part of the MCP response
 * contract.</p>
 */
public final class StructuredWarningCodes {

    private StructuredWarningCodes() {}

    /**
     * {@code auto-route-connections} {@code autoNudge} skipped because sibling
     * elements overlap.
     */
    public static final String AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP =
            "AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP";

    /**
     * {@code auto-route-connections} generated one or more off-face terminal
     * egress lifts and then rolled them back because applying them would narrow a
     * parallel-connection gap below the router's healthy floor. The hug is left in
     * place deliberately: the corridor is too tight for a healthy lift, so the
     * remedy is layout (widen the corridor / increase element spacing), not a
     * routing re-run.
     */
    public static final String EGRESS_LIFT_LAYOUT_BOUND =
            "EGRESS_LIFT_LAYOUT_BOUND";

    /**
     * {@code auto-route-connections} in {@code full} mode produced more edge
     * crossings than the geometry it replaced — the re-route regressed the view on
     * the crossing metric. On the immediate arm the route is applied anyway and the
     * caller decides whether to keep it. On the queued and awaiting-approval arms
     * nothing has been applied — the router still ran and still counted the
     * crossings, so the measurement stands, but the model does not yet hold the
     * paths it describes and the message says so.
     *
     * <p>Distinct from the free-text crossing-inflation message, which compares the
     * routed result against a straight-line topology estimate (a "this layout is too
     * dense to route cleanly" signal) and is silent whenever that estimate is zero.
     * This code compares against the view's actual prior geometry, so it is the only
     * signal that answers "did this call raise the view's own crossing count?" — that
     * question and no wider one. Two crossing counts cannot say whether the view reads
     * better or worse overall: crossings are the most tolerable routing defect this
     * project measures, and a re-route that adds one while clearing an interior
     * termination or a route through an element has improved the view by its own
     * weighting. The whole-view judgment belongs to the composite rating, not here.</p>
     *
     * <p>The paragraph above describes what this signal can support on every arm; the
     * <em>immediate</em> arm's message is currently the only one worded to match it. The
     * queued and awaiting-approval messages still say applying the re-route "would leave
     * the view worse than it is now", which is the same wider claim in the future tense.
     * That is a known, deliberate residue — those strings are held byte-identical as the
     * control for the immediate arm's rewrite — and it resolves when this comparison moves
     * to the composite rating on all three arms.</p>
     *
     * <p><strong>The remedy is {@code undo} once the re-route has been applied.</strong>
     * A queued re-route is not on the command stack at all, so {@code undo} there
     * reverts whichever command is really on top of it; that arm names
     * {@code end-batch}, which discards the re-route with {@code rollback:true} or
     * commits it. An awaiting-approval re-route names no tool at all, because the
     * agent cannot approve or reject its own proposal and the human rejecting it in
     * Archi is what leaves the previous paths in place. So {@code remediationTool} is
     * arm-dependent for this code and must be read, not assumed — it is the only
     * routing code of which that is true, because it is the only one whose tool
     * recovers <em>this</em> call rather than correcting the layout around it.</p>
     */
    public static final String AUTO_ROUTE_CROSSINGS_REGRESSED =
            "AUTO_ROUTE_CROSSINGS_REGRESSED";

    /**
     * {@code auto-layout-and-route}'s quality loop produced a view whose overall quality is worse
     * than the view it was handed. On the immediate arm the layout and routes are applied anyway
     * and the caller decides whether to keep them. On the batched and awaiting-approval arms
     * nothing has been applied yet — the loop still measured the regression, inside its own
     * temporary dispatch window, so the disclosure is owed there too, but the remedy is that arm's
     * and not {@code undo}.
     *
     * <p>Mirrors {@link #AUTO_ROUTE_CROSSINGS_REGRESSED} in shape and remedy on a different metric:
     * that one compares edge crossings, this one compares the assessment the loop already ranks its
     * own attempts by. The loop tracks the best of the states it <em>produced</em>; until this code
     * existed it never compared any of them against the state it <em>started from</em>, so
     * {@code achievedRating} was a statement about the outcome and never a comparison against the
     * input. A caller that had not itself run {@code assess-layout} immediately beforehand could not
     * tell a failure to improve a poor view from an active regression of a good one.
     *
     * <p><strong>The predicate is ordinal-then-score</strong> — the same composite the loop ranks
     * attempts by. A rating-band drop regresses; so does an equal band whose underlying metrics got
     * worse, because the five bands are too coarse to discriminate on their own. Reporting no
     * regression about a change the tool internally scored as worse would be a false all-clear.
     *
     * <p><strong>The remedy is {@code undo} once the run has been applied</strong>, unlike
     * {@link #CONNECTION_ROUTED_THROUGH_NOTE}: here the applied state is the thing that should
     * change, and recovery is a single call because the winning attempt is merged into one
     * compound command before dispatch. A queued run has nothing to undo and names
     * {@code end-batch}; an awaiting-approval run has nothing to undo and names no tool at all,
     * because the human holding the card is the one who decides. So {@code remediationTool} is
     * arm-dependent for this code and must be read, not assumed.
     *
     * <p>Emitted independently of {@code terminationReason}. A run can meet its target and still
     * have made the view worse, because the target test is "at least as good as", not "better than
     * before": on an already-excellent view an attempt producing {@code good} satisfies a
     * {@code good} target, stops the loop and commits the downgrade. That case reports success on
     * every other field, which is exactly why this one must not be gated on the target being
     * missed.
     *
     * <p><strong>Cardinality: at most one entry per call.</strong> The message names both ratings
     * and every metric that worsened, with its before and after count, so a same-band regression —
     * where the two ratings are identical on the wire — is still checkable by the caller.
     */
    public static final String AUTO_LAYOUT_RATING_REGRESSED =
            "AUTO_LAYOUT_RATING_REGRESSED";

    /**
     * One of the three spacing convenience tools
     * ({@code apply-element-spacing-recommendations},
     * {@code apply-group-spacing-recommendations}, {@code apply-spacing-recommendations})
     * committed a view whose overall quality is worse than the view it was handed. The spacing is
     * still applied; the caller decides whether to keep it.
     *
     * <p><strong>Why the loop could not catch this itself.</strong> The control loop accepts or
     * rejects each step on {@code LayoutQualityScalar.qualityScalar}, a six-input scalar over
     * {@code [0, 12]}: three correctness bits (boundary violations, pass-throughs, overlaps) plus
     * three graded band credits (edge-coincidence, coincident segments, hub-port quality). Element
     * overlaps enter it as <em>one binary bit</em>, however many there are, and four of the
     * dimensions the overall rating reads — cousin overlaps, off-canvas placement, content bounds
     * and the rating itself — are not inputs to it at all. Inflating spacing reliably improves
     * edge-coincidence and coincident segments, which is what it is for. So a step can lose the
     * single overlap bit, gain two or three band credits, score net-positive and be accepted while
     * overlaps appear, cousins collide and elements leave the canvas. Widening the scalar would
     * change what every accepted step is on all three tools; reporting the outcome does not.
     *
     * <p><strong>The comparison is wider than the step scalar, and its exact reach is worth
     * knowing.</strong> This code compares the two {@code assess-layout} snapshots the response
     * already carries, on the ordinal-then-score composite the quality loops rank their own
     * attempts by — not on the step scalar. A rating-band drop regresses; so does an equal band
     * whose underlying <em>scored</em> metrics got worse, because the five bands are too coarse to
     * discriminate on their own.
     *
     * <p><strong>Known limitation — it does not fire on a band-input-only regression.</strong> The
     * tie-break is the tier-weighted score, which is built from counted metrics (overlaps,
     * crossings, pass-throughs, coincident segments and their siblings). The rating also reads six
     * dimensions that carry no count — {@code spacing}, {@code offCanvas},
     * {@code hubNeighbourCrowding}, {@code alignment}, {@code nonOrthogonalInteriorSegments},
     * {@code offFaceParallelTerminals} — and those reach the <em>message</em> but not the
     * <em>decision</em>. So a run that degrades only one of them, while the overall band holds and
     * every counted metric holds, is <strong>not</strong> reported here. Measured: a view already
     * rated {@code poor} whose elements are pushed off-canvas moves {@code offCanvas} from
     * {@code pass} to {@code fair} with the band and the score both flat, and this code stays
     * silent. Widening the predicate is deliberately out of scope, because it is the same
     * predicate {@code auto-layout-and-route} commits its own attempts on and changing it changes
     * that tool's behaviour too. Callers who need that case must compare {@code before} and
     * {@code after} {@code ratingBreakdown} maps themselves — both are on the wire.
     *
     * <p><strong>The remedy is {@code undo}</strong>, and it is a single call: every accepted
     * iteration from one tool call is wrapped in one compound command before dispatch, so one undo
     * restores the pre-call state exactly. The composed tool merges both arms into that same
     * compound.
     *
     * <p><strong>Not emitted on a queued (batch) call.</strong> When a batch is open the accepted
     * commands are queued rather than executed and the loop has already reset the model, so the
     * {@code after} snapshot re-reads the unmutated view and the comparison is structurally blind.
     * Absence of this code on a queued call therefore certifies nothing; {@code nextSteps} says so
     * in words rather than leaving the silence to be read as a clean result.
     *
     * <p><strong>Cardinality: at most one entry per call</strong>, on each of the three tools. The
     * message names both ratings and every metric that moved with its before and after value, so a
     * same-band regression — where the two ratings are identical on the wire — is still checkable.
     */
    public static final String SPACING_RATING_REGRESSED =
            "SPACING_RATING_REGRESSED";

    /**
     * {@code auto-route-connections} was given one or more {@code connectionIds} that are not on
     * the target view. The connections that were found are routed normally; the unknown IDs are
     * skipped. {@link StructuredWarningDto#remediationViolatorIds} lists the IDs that missed, so
     * callers do not have to parse the free-text warnings to learn which ones they were.
     *
     * <p>Emitted only for a genuine lookup miss. Callers must not infer this condition from the
     * mere presence of free-text warnings — several unrelated routing conditions write there
     * too.</p>
     */
    public static final String CONNECTION_NOT_FOUND =
            "CONNECTION_NOT_FOUND";

    /**
     * {@code auto-route-connections} applied a route that penetrates a note.
     *
     * <p><strong>This is a disclosure, not a violation.</strong> A note is excluded from the A*
     * obstacle set on purpose — notes are often large, and treating one as solid would
     * over-constrain routing — so a connection is routed straight through a note that sits in its
     * corridor, exactly as the tool's description says it will be. Nothing was broken and no
     * constraint was defeated, which is why the crossing does not appear in {@code violations}:
     * that channel reports a route that failed a constraint, and its only reason code
     * ({@code element_crossing}) would be a false statement about an object that is not an
     * element.
     *
     * <p>What was missing was per-call attribution. The description is transmitted once, at schema
     * time, and says that <em>a</em> crossing can happen — never <em>which</em> one did. Learning
     * that required a second call to {@code assess-layout}, which answers with a count whose ids
     * are recoverable only from prose. This code puts the crossed note's id in the hand of the
     * agent on the call that created the crossing.
     *
     * <p><strong>Cardinality: exactly one entry per call</strong>, naming every (connection, note)
     * pair with the pair count in the message, and never truncated.
     * {@link StructuredWarningDto#remediationViolatorIds} holds the <em>note</em> ids, because the
     * note is what the caller moves — hence {@code update-view-object} as the remedy rather than
     * {@code undo}: the route is not the thing that should change.
     *
     * <p>Note that {@code assess-layout} counts per (connection × visual), so one connection
     * crossing three notes is 3 there and one entry here; the message states the pair count so
     * that difference in shape does not read as a disagreement. The two share one geometry and so
     * agree on every pair <em>this call routed</em> — but they are not the same population, and
     * {@code connectionThroughNoteCount} can legitimately be the larger number. It is a whole-view
     * figure, so it also counts notes crossed by connections this call did not route (a partial
     * {@code connectionIds} call), and it counts element-embedded images, which this code does not
     * report at all.
     */
    public static final String CONNECTION_ROUTED_THROUGH_NOTE =
            "CONNECTION_ROUTED_THROUGH_NOTE";

    /**
     * {@code auto-route-connections} applied a move to an element under {@code autoNudge=true} and
     * the element ended at the position it started from — a net displacement of {@code (0, 0)}.
     *
     * <p><strong>This is a disclosure, not a violation.</strong> Nothing was broken: the nudge loop
     * ran to completion and the model holds a consistent state. What it reports is an
     * <em>absence</em> — the element is not in {@code nudgedElements}, because reporting a
     * zero-displacement row there made the response contradict itself. The same response's
     * {@code failed} array still listed the connection the move was meant to unblock, and its
     * {@code recommendations} repeated the identical move, so a caller reading
     * <em>"1 element was automatically nudged"</em> was told the opposite of what the two adjacent
     * fields said. Filtering the row without this code would replace a visible contradiction with a
     * silence, which is worse: the caller would see a nudge that simply never happened.
     *
     * <p><strong>The message states the observation, not a mechanism.</strong> Two different causes
     * produce a zero sum and the accumulated map cannot tell them apart: a move applied on one
     * iteration and reversed on the next, and a move fully absorbed on its only iteration by the
     * clamp that keeps a nested child inside its parent and clear of the parent's title band. Both
     * leave the caller in the same position, and naming either one specifically would be a
     * false statement whenever the other was the cause — so the message names the outcome and says
     * plainly that the two are not distinguished.
     *
     * <p><strong>The remedy is layout, never a re-run.</strong> Both producers are properties of
     * the geometry the tool was handed: an element with no room to move to, or one already sitting
     * on its parent's floor. Re-invoking {@code auto-route-connections} recomputes the same
     * recommendation against the same geometry and reproduces the same zero, so the remediation
     * names a spacing lever — the same reasoning as {@link #EGRESS_LIFT_LAYOUT_BOUND}.
     *
     * <p><strong>Cardinality: at most one entry per call</strong>, naming every element that netted
     * to zero, with {@link StructuredWarningDto#remediationViolatorIds} carrying their view-object
     * ids so a caller does not have to parse the prose. Neither the list nor the message is
     * truncated.
     */
    public static final String AUTO_NUDGE_NET_ZERO =
            "AUTO_NUDGE_NET_ZERO";

    /**
     * An annotation was placed onto a rectangle that at least one already-drawn route passes
     * through — emitted by {@code add-note-to-view}, {@code add-view-reference-to-view} and
     * {@code add-image-to-view}.
     *
     * <p>This is a <strong>disclosure at the moment of the call</strong>, not a rejection: the
     * placement is applied exactly as asked. The published ordering rule for annotations already
     * says to place them after routing, and doing so is necessary without being sufficient —
     * after routing is precisely when there are corridors to land in — so the rule cannot be
     * strengthened into a fix and the gap it leaves is measurement, taken against the routes on
     * the view as they stand.
     *
     * <p><strong>Severity differs by tool</strong>, and the message says which applies. A route
     * crossing a note is counted in the informational {@code connectionThroughNoteCount}, which
     * caps a view at good; a route crossing a view-reference or a standalone image is a rated
     * {@code connectionPassThroughs}, which can drive a view to poor. The two non-notes are the
     * damaging ones because the assessor treats them as ordinary layout nodes.
     *
     * <p><strong>Cardinality: at most one entry per call</strong>, naming every (connection,
     * annotation) pair found, with {@link StructuredWarningDto#remediationViolatorIds} carrying
     * the <strong>connection</strong> ids rather than the annotation's. The annotation's own id
     * is already the subject of the response that carries the warning, so repeating it would say
     * nothing; the connections are the set the caller cannot otherwise enumerate, and are what a
     * follow-up assess-layout will name back. Neither the list nor the message is truncated.
     *
     * <p>The remediation names {@code update-view-object} on the annotation, never a re-route:
     * moving one rectangle is cheaper than re-routing the connections around it, and re-routing
     * spends route quality on a crossing that did not have to exist.
     */
    public static final String ANNOTATION_PLACED_IN_ROUTED_CORRIDOR =
            "ANNOTATION_PLACED_IN_ROUTED_CORRIDOR";
}
