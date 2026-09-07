package net.vheerden.archi.mcp.model.routing;

import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline.ConnectionEndpoints;

/**
 * Best-of-K multi-start outer wrapper around the <em>entirely unchanged</em>
 * {@link RoutingPipeline}.
 *
 * <p><b>Why this exists.</b> The pipeline is a greedy polynomial approximation of a
 * latent NP-hard global optimisation (processing-order &rarr; corridor-assignment
 * &asymp; channel/track-assignment routing + crossing minimisation). The connection
 * <em>processing order</em> is established at exactly one site —
 * {@link RoutingPipeline#buildConnectionRoutingOrder} — and the sequential
 * {@link CorridorOccupancyTracker} accumulation makes the final geometry
 * order-sensitive. Measurement showed that sensitivity as a wide quality spread
 * (HH {@code V_p10 &isin; {4,4,9,4,8,4}} over 6 clones of one source). This class
 * turns that latent, currently-wasted nondeterminism into a deliberate, seeded,
 * deterministic quality lever: run the unchanged pipeline K times over
 * deterministically-shuffled processing orderings and emit the best by the same
 * aggregate the ship-gate measures.
 *
 * <p><b>Never-worse by construction (the strongest property in this lineage).</b>
 * Run&nbsp;0 always uses {@code processingOrderOverride == null}, which makes the
 * pipeline compute its own unchanged {@link RoutingPipeline#buildConnectionRoutingOrder}
 * &mdash; <b>byte-identical to current {@code main}</b>. Selection is
 * {@code argmax} over the K candidates with a <em>strictly-greater</em> replacement
 * rule, so run&nbsp;0 wins every tie. Therefore
 * {@code objective(emitted) &ge; objective(run0) = objective(current main)} for every
 * input, unconditionally. Unlike the hub-perimeter stage there is nothing to roll back &mdash; the
 * current result is always in the candidate set and always wins ties.
 * The only ways to regress are <em>non-quality</em>: a performance-budget breach
 * (mitigated by the wall-clock budget + large-view degrade-to-K=1 guard) or a
 * determinism break (the seeded RNG is a pure function of {@code (seed, run)}).
 *
 * <p><b>Atomic-swap discipline.</b> This is a NEW outer sibling. It composes
 * the pipeline through two injected SAMs and touches NONE of the 6 off-limits
 * inherited primitives ({@code VisibilityGraphRouter} / {@code EdgeAttachmentCalculator}
 * / {@code PathStraightener} / {@code CorridorOccupancyTracker} /
 * {@code ChannelNudgingPass} / {@code LayoutQualityAssessor}). It changes ONLY
 * (a) the <em>order</em> connections are fed to the unchanged pipeline (via the
 * additive {@code RoutingPipeline.routeAllConnections} processing-order-override
 * overload, wired as the {@link RouteRunner}) and (b) which of K complete results
 * is <em>selected</em> (via the {@link CandidateScorer}). The scorer is
 * deliberately assessor-agnostic so this class stays in {@code model.routing} while
 * the genuine {@code LayoutQualityAssessor} aggregate is wired at the single
 * {@code model}-layer composition point ({@code ArchiModelAccessorImpl}) where the
 * assessor is already visible &mdash; keeping {@code LayoutQualityAssessor} a strictly
 * read-only selection oracle.
 *
 * <p>The base order is obtained from the unchanged package-visible pure
 * {@link RoutingPipeline#buildConnectionRoutingOrder} (not one of the 6 off-limits
 * primitives) and shuffled with seeded Fisher&ndash;Yates. The orthogonality to the
 * V4 hub-spoke coincident falsification chain is structural: feed-order + selection
 * only, no internal cost/keying change.
 *
 * <p>Pure-geometry class &mdash; no EMF/SWT dependencies.
 */
public final class BestOfKRoutingStrategy {

    private static final Logger logger = LoggerFactory.getLogger(BestOfKRoutingStrategy.class);

    /**
     * Default multi-start count, chosen at the design spike: 2&times; the
     * hub-perimeter stage's 6-clone evidence window &mdash; enough headroom for the search to find
     * a good ordering while bounding the {@code K&middot;(route+assess)} cost.
     */
    public static final int DEFAULT_K = 12;

    /** Fixed default PRNG seed (reproducibility cornerstone; test-injectable). */
    public static final long DEFAULT_SEED = 0xBE57_0FCAL;

    /**
     * Large-view guard: above this connection count the search degrades to
     * K=1 (never-worse preserved even under the
     * guard). Gate views are ~30 connections; the threshold leaves generous
     * head-room for normal views while preventing a pathological view from
     * incurring K&times; routing + scoring cost.
     */
    public static final int DEFAULT_LARGE_VIEW_CONNECTION_THRESHOLD = 120;

    /**
     * Hard wall-clock budget (ms). Checked between runs; on breach the
     * search stops and returns the best-so-far &mdash; which always includes
     * run&nbsp;0, so never-worse-by-construction holds even on an early budget cut.
     */
    public static final long DEFAULT_BUDGET_MILLIS = 20_000L;

    /** SplitMix64-style odd multiplier for run-independent seed derivation. */
    private static final long SEED_MIX = 0x9E3779B97F4A7C15L;

    /**
     * The unchanged-pipeline invocation seam. {@code processingOrderOverride}
     * {@code == null} MUST make the pipeline compute its own
     * {@link RoutingPipeline#buildConnectionRoutingOrder}; a non-null array is the
     * exact processing order to use.
     */
    @FunctionalInterface
    public interface RouteRunner {
        RoutingResult route(Integer[] processingOrderOverride);
    }

    /**
     * The selection objective. Higher = better. Implementors MUST encode the
     * ship-gate <em>aggregate</em> total order (Tier-1 defect count ascending
     * &rarr; assessor {@code overall} rating ordinal descending &rarr; a single
     * sub-metric composite), NOT an independent per-metric veto &mdash; per
     * {@code feedback_discipline_rules_aggregate_not_per_metric}.
     */
    @FunctionalInterface
    public interface CandidateScorer {
        double score(RoutingResult candidate);
    }

    private final RouteRunner runner;
    private final CandidateScorer scorer;
    private final int k;
    private final long seed;
    private final int largeViewThreshold;
    /**
     * Wall-clock budget for the K-1 shuffled runs, checked between runs (run-0
     * always completes first ⇒ never-worse preserved on a budget cut). A value
     * of {@code 0} <b>disables the wall-clock check entirely</b>
     * (all K runs execute regardless of elapsed time; intended for tests / a
     * deliberately unbounded search), it does NOT mean "expire immediately".
     * Must be {@code >= 0}; production default {@link #DEFAULT_BUDGET_MILLIS}.
     */
    private final long budgetMillis;

    /** Production wiring: defaults for K / seed / large-view guard / budget. */
    public BestOfKRoutingStrategy(RouteRunner runner, CandidateScorer scorer) {
        this(runner, scorer, DEFAULT_K, DEFAULT_SEED,
                DEFAULT_LARGE_VIEW_CONNECTION_THRESHOLD, DEFAULT_BUDGET_MILLIS);
    }

    /** Test-injection ctor (K / seed / guard / budget overridable). */
    public BestOfKRoutingStrategy(RouteRunner runner, CandidateScorer scorer,
            int k, long seed, int largeViewThreshold, long budgetMillis) {
        if (runner == null) {
            throw new IllegalArgumentException("runner must not be null");
        }
        if (scorer == null) {
            throw new IllegalArgumentException("scorer must not be null");
        }
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1, got " + k);
        }
        if (largeViewThreshold < 1) {
            throw new IllegalArgumentException(
                    "largeViewThreshold must be >= 1, got " + largeViewThreshold);
        }
        if (budgetMillis < 0) {
            throw new IllegalArgumentException(
                    "budgetMillis must be >= 0, got " + budgetMillis);
        }
        this.runner = runner;
        this.scorer = scorer;
        this.k = k;
        this.seed = seed;
        this.largeViewThreshold = largeViewThreshold;
        this.budgetMillis = budgetMillis;
    }

    /**
     * Runs the unchanged pipeline up to K times over deterministically-shuffled
     * processing orderings and returns the best result by the injected aggregate
     * objective. <b>Never worse than current {@code main} by construction</b>
     * (run&nbsp;0 = unshuffled {@code == null} override; strictly-greater
     * replacement so run&nbsp;0 wins all ties).
     *
     * @param connections the connections to route (used only to derive the base
     *                     processing order via the unchanged pure
     *                     {@link RoutingPipeline#buildConnectionRoutingOrder})
     * @return the best of K complete {@link RoutingResult}s; exactly the current
     *         {@code main} result when K collapses to 1 or no shuffle can beat it
     */
    public RoutingResult selectBest(List<ConnectionEndpoints> connections) {
        // Run 0 — null override ⇒ pipeline computes its own unchanged
        // buildConnectionRoutingOrder ⇒ byte-identical to current main.
        RoutingResult best = runner.route(null);

        Integer[] base = RoutingPipeline.buildConnectionRoutingOrder(connections);
        int effectiveK = effectiveK(connections.size(), base.length);
        if (effectiveK <= 1) {
            logger.debug("best-of-K: K collapsed to 1 (connections={}, baseLen={}) "
                    + "— returning current-main result (never-worse preserved)",
                    connections.size(), base.length);
            return best;
        }

        double bestScore = scorer.score(best);
        int bestRun = 0;
        long deadlineNanos = System.nanoTime() + budgetMillis * 1_000_000L;

        for (int run = 1; run < effectiveK; run++) {
            if (budgetMillis > 0 && System.nanoTime() > deadlineNanos) {
                logger.info("best-of-K: wall-clock budget {}ms exhausted after run {} "
                        + "— returning best-so-far (run {}, never-worse preserved)",
                        budgetMillis, run - 1, bestRun);
                break;
            }
            Integer[] order = shuffledOrder(base, run);
            RoutingResult candidate = runner.route(order);
            double s = scorer.score(candidate);
            // Strictly-greater ⇒ run 0 wins every tie ⇒ never-worse by construction.
            if (s > bestScore) {
                best = candidate;
                bestScore = s;
                bestRun = run;
            }
        }
        logger.info("best-of-K: selected run {} of {} (score={})", bestRun, effectiveK, bestScore);
        return best;
    }

    /**
     * Effective K after the large-view guard and the trivial-view
     * short-circuit. {@code baseLength < 2} ⇒ only one possible ordering ⇒ K=1.
     */
    int effectiveK(int connectionCount, int baseLength) {
        if (baseLength < 2) {
            return 1;
        }
        if (connectionCount > largeViewThreshold) {
            return 1;
        }
        return Math.max(1, k);
    }

    /**
     * Seeded Fisher&ndash;Yates shuffle of a copy of {@code base}. Pure function
     * of {@code (seed, run)} &mdash; same inputs ⇒ identical permutation
     * (reproducibility). Distinct seeds ⇒ distinct RNG streams ⇒ distinct
     * orderings (the search is real, not a no-op).
     */
    Integer[] shuffledOrder(Integer[] base, int run) {
        Integer[] a = base.clone();
        Random rng = rngFor(run);
        for (int i = a.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Integer tmp = a[i];
            a[i] = a[j];
            a[j] = tmp;
        }
        return a;
    }

    /** Per-run RNG, a pure deterministic function of {@code (seed, run)}. */
    Random rngFor(int run) {
        return new Random(seed ^ (SEED_MIX * (run + 1L)));
    }
}
