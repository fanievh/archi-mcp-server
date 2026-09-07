package net.vheerden.archi.mcp.model;

/**
 * Which of the three dispatch outcomes a response describes.
 *
 * <p>A tool measures the same things whichever arm it is on; what differs is what the caller can
 * <em>do</em> about it. An applied write is reverted with {@code undo}, a queued one is discarded
 * or committed with {@code end-batch}, and a proposal is recovered by no MCP tool at all — the
 * human rejects it in Archi.</p>
 *
 * <p><strong>It lives in the model layer because the disclosures that need it do.</strong> The
 * handler layer composes prose per arm and could hold its own copy — it held two, byte-identical,
 * one per handler. But the machine-readable half of the same disclosures is composed by the
 * emitters, which are model-layer classes and must not import a handler type. A third private copy
 * would have made three declarations of one concept, and the first divergence between them would
 * have been a response whose two halves disagreed about which arm the call was on. That is the
 * defect this type exists to make unrepresentable, so there is exactly one of it, and it sits
 * beside {@link MutationResult} and {@code ProposalContext}, which describe the same split.</p>
 */
public enum DispatchArm {
    /** Written to the model before this response was built. */
    APPLIED,
    /** Queued into an open batch; it lands, or does not, at {@code end-batch}. */
    QUEUED,
    /** Held for a human's decision in Archi; no tool the agent can call recovers it. */
    AWAITING_APPROVAL;

    /**
     * Resolves the arm from the two gates, in the order the accessor checks them.
     *
     * <p><strong>Approval wins.</strong> Every mutating tool tests the approval gate and returns a
     * proposal <em>before</em> it reaches {@code dispatchOrQueue}, so a session that is both in
     * approval mode and deferring proposes rather than queues. That ordering is the whole content
     * of this method, and it lives here so the two callers that need it cannot come to disagree
     * about which gate wins — a disagreement that would surface as one response telling a caller to
     * run {@code end-batch} to commit something no batch holds.</p>
     *
     * <p>What counts as {@code deferred} is deliberately the caller's to decide, because it is not
     * the same question everywhere. For a tool that cannot appear inside {@code bulk-mutate}, an
     * open batch is the only way to defer. For one that can, a bulk call defers too, without the
     * session ever entering batch mode. Folding either answer in here would make this method right
     * for one family and quietly wrong for the other.</p>
     *
     * @param approvalRequired whether a human holds the decision for this session
     * @param deferred         whether the write is being held rather than executed now
     */
    public static DispatchArm of(boolean approvalRequired, boolean deferred) {
        if (approvalRequired) {
            return AWAITING_APPROVAL;
        }
        return deferred ? QUEUED : APPLIED;
    }
}
