package net.vheerden.archi.mcp.model;

/**
 * The entry-guard half of the spacing family's termination taxonomy: the reasons a spacing tool
 * declines before it enters its control loop, and the machine code each maps to.
 *
 * <p>Three tools short-circuit on the same guards and publish the same taxonomy. Building the
 * prose here rather than at each of them is what stops three accounts of one view; mapping it here
 * rather than at each of them is what stops three spellings of one code.</p>
 *
 * <p>Pure strings — no EMF, no ArchiMate types — so it runs in the display-less test lane and can
 * be exercised without a model.</p>
 */
final class SpacingEntryGuardTermination {

    private SpacingEntryGuardTermination() {
    }

    /**
     * The termination code for a view whose containers are not ones the declining tool positions.
     *
     * <p>A fixed code rather than a slug of the prose below. {@link #mapEntryGuardToTerminationReason}
     * builds the {@code structural_no_change_<reason>} family by slugifying the reason, and this
     * reason is long enough to produce a ninety-character code that would change shape every time
     * a word of the sentence was edited.</p>
     *
     * <p>This is a new reason INSIDE the published {@code structural_no_change_<reason>} branch,
     * not an eleventh branch of the taxonomy — which is why it is named here and not as a
     * {@code REASON_*} constant on {@link SpacingControlLoop} or on
     * {@link SpacingPreconditionInfeasibilityCertificate}, where the reflective doc-sync guard
     * enumerates the canonical branch names.</p>
     */
    static final String CONTAINERS_NOT_POSITIONED_BY_THIS_TOOL =
            "structural_no_change_containers_not_positioned_by_this_tool";

    /**
     * The stable substring {@link #mapEntryGuardToTerminationReason} matches on. Present in every
     * sentence {@link #containersNotPositionedByThisTool} builds, in both of its forms, so the code
     * survives a rewording of everything around it.
     */
    static final String NOT_POSITIONED_MARKER = "positions none of them";

    /**
     * The refusal a spacing tool owes a view whose containers form a corridor its own positioning
     * step cannot act in.
     *
     * <p>Two shapes reach this, and they are different views that must not be described in one
     * sentence. When nothing is drawn on the view itself, every container really is inside a host.
     * When something is, saying otherwise would be false about it — the sentence splits the count
     * instead.</p>
     *
     * <p><strong>The counts are topological, not the step's usable subset.</strong> An EMPTY
     * container on the canvas is invisible to the walk that decides whether the step has work, so
     * counting that way produced "every one is drawn inside a host" about a view with an unhosted
     * box on it. What licenses the refusal and what the refusal describes are different
     * questions.</p>
     *
     * <p>Deliberately free of "already meets" / "already at" / "already exceeds", which
     * {@link #mapEntryGuardToTerminationReason} maps to the heuristic-already-met code, and free of
     * "no groups" / "no containers" / "flat view", which are the false statements about a canvas
     * full of populated zones that this whole line of work exists to remove.</p>
     *
     * @param viewId          the view being declined, so the sentence is about a nameable thing
     * @param ownContainers   how many top-level containers are drawn on the view itself, empty
     *                        ones included — a count of the view, not of the step's opportunities
     * @param nestedContainers how many are drawn inside a host
     * @param stepClause      what this tool's step does, completing "… and positions none of them"
     */
    static String containersNotPositionedByThisTool(
            String viewId, int ownContainers, int nestedContainers, String stepClause) {
        int total = ownContainers + nestedContainers;
        StringBuilder sentence = new StringBuilder()
                .append("View ").append(viewId).append(" has ").append(total)
                .append(" top-level container").append(total == 1 ? "" : "s");
        if (ownContainers == 0) {
            sentence.append(", but every one is drawn inside a host rather than on the view "
                    + "itself. ");
        } else {
            // Never "every one" here: one IS on the view, and saying otherwise is the same class
            // of confident falsehood this refusal replaced. The split is stated rather than
            // diagnosed — why the step still has nothing to do differs by step, and the clause
            // below says which step is speaking.
            sentence.append(": ").append(ownContainers)
                    .append(" drawn on the view itself and ").append(nestedContainers)
                    .append(" inside a host. ");
        }
        return sentence
                .append(stepClause)
                .append(" and ").append(NOT_POSITIONED_MARKER).append(". ")
                .append("arrange-groups positions those containers inside their host; to space ")
                .append("the elements within one of them, call layout-within-group on that ")
                .append("container.")
                .toString();
    }

    /**
     * Maps a decision record's {@code noChangeReason} to the published termination taxonomy.
     *
     * <p>Inputs:
     * <ul>
     *   <li>{@code dryRun} — true → {@code "dry_run_recommendation_not_applied"} (the (e) sub-string).</li>
     *   <li>{@code noChangeReason} — the decision record's reason string;
     *       mapped verbatim or prefixed with {@code structural_no_change_}.</li>
     * </ul></p>
     *
     * <p>The decision-record's {@code noChangeReason} strings are
     * human-meaningful (e.g., "Current spacing already meets/exceeds heuristic")
     * and surface verbatim to the LLM agent via the
     * {@code structural_no_change_<reason>} prefix. Dry-run + heuristic-already-
     * met fall under the (e) sub-string family.</p>
     *
     * <p><strong>The not-positioned test runs first, ahead of both.</strong> Ahead of
     * {@code dryRun}, because a dry run whose recommendation the applied path could never honour is
     * the same defect one step earlier, and reporting it as a withheld recommendation hides that.
     * Ahead of the already-met test, because on {@code scope: "both"} the composer joins one arm's
     * already-met reason to the other arm's structural one, and a structural impossibility outranks
     * a met heuristic wherever the two are composed.</p>
     */
    static String mapEntryGuardToTerminationReason(
            boolean dryRun, String noChangeReason) {
        if (noChangeReason != null && noChangeReason.contains(NOT_POSITIONED_MARKER)) {
            return CONTAINERS_NOT_POSITIONED_BY_THIS_TOOL;
        }
        if (dryRun) {
            return "dry_run_recommendation_not_applied";
        }
        if (noChangeReason == null) {
            return "structural_no_change_unknown";
        }
        String lower = noChangeReason.toLowerCase();
        if (lower.contains("already meets") || lower.contains("already at")
                || lower.contains("already exceeds")) {
            return SpacingControlLoop.REASON_HEURISTIC_ALREADY_MET;
        }
        return "structural_no_change_"
                + noChangeReason
                        .replaceAll("[^A-Za-z0-9]+", "_")
                        .toLowerCase()
                        .replaceAll("^_+|_+$", "");
    }
}
