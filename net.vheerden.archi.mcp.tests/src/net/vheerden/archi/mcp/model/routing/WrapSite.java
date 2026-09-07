package net.vheerden.archi.mcp.model.routing;

/**
 * The five terminal-anchoring wrap sites enumerated for parameterised dispatch in
 * {@link ChopboxAnchorDegeneracyTest}. Each constant names exactly one
 * mutator that wraps a {@code path[0]} / {@code path[last]} mutation under
 * {@link TerminalAnchoring#preservesEndpoints}.
 *
 * <p>This enum is the cartesian dimension that lifts the 81-row generator matrix
 * to 5 × 81 = 405 parameterised assertions.
 *
 * <p><strong>Shared predicate, shared rollback policy — but the pin is per
 * constant.</strong> Every constant here evaluates the same predicate, which is
 * what the generator matrix asserts, and all five now roll back on the same rule:
 * a mutation that flips a terminal off a faceline it was on, decided per end.
 * What still varies is the second arm. {@code SNAP_TO_STRAIGHT},
 * {@code COLLAPSE_STAIRCASE_JOGS} and {@code APPLY_OFFSETS} additionally refuse to
 * move a terminal that arrived off-face, because their write ranges can reach a
 * terminal and nothing else bounds them; {@code COLLAPSE_BENDS} and
 * {@code ELIMINATE_REVERSALS} do not, being bounded by an exact-collinearity
 * precondition and by {@code protectTerminals} respectively. Do not read a
 * constant's membership here as a claim about its rollback behaviour.
 */
public enum WrapSite {
    ELIMINATE_REVERSALS,
    COLLAPSE_BENDS,
    SNAP_TO_STRAIGHT,
    COLLAPSE_STAIRCASE_JOGS,
    APPLY_OFFSETS
}
