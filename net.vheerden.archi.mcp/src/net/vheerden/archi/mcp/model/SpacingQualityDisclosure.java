package net.vheerden.archi.mcp.model;

import java.util.List;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * The spacing family's half of the rating-regression disclosure: which tool did it, what recovery
 * costs, and under which published code.
 *
 * <p>The comparison itself is {@link RatingRegressionDisclosure}, shared with the quality-target
 * loop. Only the sentence differs between the two families, and only in the subject and the tail —
 * which is exactly what is kept here so that the comparison exists once.</p>
 *
 * <p><strong>Why the three tools share one code and one tail but not one subject.</strong> The
 * blind spot is in the step scalar, not in the composition: all three tools accept steps on the
 * same six-input {@code LayoutQualityScalar} and are equally unable to see a rating drop, so they
 * carry the same signal under the same code. The subject differs because an agent reading the
 * message is choosing what to call next, and being told a tool it did not invoke made the view
 * worse sends it to the wrong place.</p>
 *
 * <p><strong>The tail's "single call" claim is load-bearing and true.</strong> Each tool undoes its
 * accepted commands before splicing them into one {@code NonNotifyingCompoundCommand} and
 * dispatching that once; the composed tool merges both arms into the same compound. So the whole
 * run is one entry on the undo stack however many iterations it took, and a caller who does not
 * know that may not attempt the undo at all.</p>
 */
public final class SpacingQualityDisclosure {

    private SpacingQualityDisclosure() {}

    /** Subject for {@code apply-element-spacing-recommendations}. */
    public static final String ELEMENT_TOOL = "apply-element-spacing-recommendations";

    /** Subject for {@code apply-group-spacing-recommendations}. */
    public static final String GROUP_TOOL = "apply-group-spacing-recommendations";

    /** Subject for {@code apply-spacing-recommendations}. */
    public static final String COMPOSED_TOOL = "apply-spacing-recommendations";

    /**
     * The tail: that the spacing was committed regardless, and that recovery is one call.
     *
     * <p>Shared by all three because the undo shape is the same on all three.</p>
     */
    private static final String APPLIED_TAIL =
            "The spacing was applied anyway — undo restores the previous state, and it is a "
                    + "single call because every accepted iteration from this call is committed "
                    + "as one compound operation.";

    /**
     * The disclosure for a completed spacing run: a single-entry list when the committed state is
     * worse than the state the tool was handed, empty otherwise.
     *
     * <p>Abstains — returns empty — whenever either snapshot is absent or was never rated. That is
     * not a clean result and must not be reported as one; on the queued path, where {@code after}
     * re-reads a view the queued commands have not touched, the caller is told in words that the
     * comparison was unavailable rather than left to read the silence.</p>
     *
     * @param tool  the tool the caller actually invoked — one of {@link #ELEMENT_TOOL},
     *              {@link #GROUP_TOOL}, {@link #COMPOSED_TOOL}
     * @param before the {@code assess-layout} snapshot taken before any mutation
     * @param after  the {@code assess-layout} snapshot taken after the dispatch
     */
    public static List<StructuredWarningDto> warningsFor(String tool,
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        return RatingRegressionDisclosure.warnings(
                StructuredWarningCodes.SPACING_RATING_REGRESSED, tool, APPLIED_TAIL, before, after);
    }

    /**
     * The disclosure for a run that may have been queued rather than executed — abstains outright
     * when it was queued.
     *
     * <p>On the queued path the accepted commands are handed to the batch instead of being
     * dispatched, and the loop has already undone every one of them, so the {@code after} snapshot
     * re-reads a view the queued commands have not touched. The comparison would then return "no
     * regression" for the wrong reason: not because the state held, but because nothing was
     * measured. A detector that cannot verify a claim must abstain rather than emit a false
     * all-clear, so this returns empty without consulting the snapshots at all — and the caller is
     * told in words, in {@code nextSteps}, that the comparison was unavailable.</p>
     *
     * @param batchSequenceNumber the queue position when the call was queued; {@code null} when it
     *                            was dispatched immediately and the snapshots therefore describe
     *                            two different states
     */
    public static List<StructuredWarningDto> warningsForDispatch(String tool,
            Integer batchSequenceNumber,
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        if (batchSequenceNumber != null) {
            return List.of();
        }
        return warningsFor(tool, before, after);
    }
}
