package net.vheerden.archi.mcp.model;

import java.util.Map;
import java.util.Set;

/**
 * The propose-time snapshot a {@link ProposalStalenessGuard} needs to decide, at approve-time,
 * whether a pending proposal went stale.
 *
 * <p>Captured when a proposal is stored:</p>
 * <ul>
 *   <li>{@code sequence} — the guard's monotonic {@code CommandStack} sequence at propose. A later
 *       <em>human</em> command advancing the guard's {@code lastHumanSequence} past this value is the
 *       cheap "a human edited <em>something</em> since I proposed" signal.</li>
 *   <li>{@code fingerprints} — a per-target {@code id → attribute-fingerprint} map captured against the
 *       live model at propose. At approve each target is re-resolved and re-fingerprinted; an id that no
 *       longer resolves, or whose fingerprint changed <em>while a human command intervened</em>, marks the
 *       proposal stale (the conservative coarse policy). Targets the proposal does not name are not
 *       fingerprinted, so an unrelated human edit never trips staleness.</li>
 *   <li>{@code names} — a per-target {@code id → display-name} map captured at propose, used to name the
 *       touched/removed object in the plain-language reject-stale message even after it is gone.</li>
 * </ul>
 *
 * <p>Package-private — lives wholly inside the {@code model/} layer (no handler ever sees it). Construction
 * sites that have no targets to track (creates, and the legacy/test command-bridge ctor on
 * {@link PendingProposal}) use {@link #EMPTY}, which is always fresh.</p>
 *
 * <p>A fourth map {@code bounds} carries an
 * {@code id → bounds-fingerprint} for the subset of targets that are {@link com.archimatetool.model.IDiagramModelObject
 * diagram objects} (x/y/width/height). It is tracked <em>orthogonally</em> to the attribute
 * {@code fingerprints} so the guard can tell a pure <em>move</em> (drag) apart from an attribute
 * <em>edit</em> (rename/retype) and name each correctly. Non-diagram targets carry no bounds
 * entry, so {@code bounds.keySet()} ⊆ {@code fingerprints.keySet()}.</p>
 *
 * <p>A fifth map {@code connections} carries, for the subset of targets that are connections, what
 * the attribute fingerprint cannot see: the {@linkplain Connection#route() route}, which is a
 * containment reference, where each of its {@linkplain Connection#endBounds() ends} sits on the canvas,
 * and a label naming the connection for a human. It is tracked orthogonally to
 * the other maps for the same reason bounds is, so {@code connections.keySet()} ⊆
 * {@code fingerprints.keySet()}.</p>
 *
 * @param sequence     the guard's stack sequence at propose-time
 * @param fingerprints {@code id → attribute-fingerprint} for each tracked target (non-null values)
 * @param names        {@code id → display name} for each tracked target (non-null values)
 * @param bounds       {@code id → bounds-fingerprint} for tracked diagram-object targets
 * @param trackId      which model's stack sequence {@code sequence} was read from, or 0 when none was
 *                     recorded; a capture is only comparable with the same model's sequence
 * @param connections  {@code id → route and label} for tracked connection targets
 */
record StalenessCapture(long sequence, Map<String, String> fingerprints, Map<String, String> names,
        Map<String, String> bounds, long trackId, Map<String, Connection> connections) {

    /**
     * What a tracked connection looks like apart from its attributes.
     *
     * @param route its bendpoints in order, each as {@code startX,startY,endX,endY}, joined by
     *              {@code |}; a connection with no bendpoints has the empty route, which is a state
     *              like any other (clearing a route changes it)
     * @param label the connection as a human would recognise it, quoted: its name, or its two ends
     *              when it has none — most connections carry no name of their own
     * @param endBounds {@link #SOURCE} / {@link #TARGET} {@code → end id@absolute canvas bounds}, or the
     *              bare end id for an end that is itself a connection. A stored bendpoint is two offsets,
     *              from the source's and the target's centre, so a route means something only against
     *              which object holds each role and where it was. Absolute, so a nested end carried by its
     *              parent's drag counts as moved. A role with no end is left out
     * @param endNames the same roles {@code → display name}, so the message can name an end that is no
     *              longer one
     */
    record Connection(String route, String label, Map<String, String> endBounds, Map<String, String> endNames) {
        static final String SOURCE = "source";
        static final String TARGET = "target";

        Connection {
            endBounds = Map.copyOf(endBounds);
            endNames = Map.copyOf(endNames);
        }
    }

    /** A no-target capture (sequence 0) — always vets as fresh. Used by creates and the command-bridge ctor. */
    static final StalenessCapture EMPTY = new StalenessCapture(0L, Map.of(), Map.of(), Map.of());

    /**
     * Constructor at the prior canonical arity: no track recorded, so the sequence is read against
     * the vetted model's own track.
     */
    StalenessCapture(long sequence, Map<String, String> fingerprints, Map<String, String> names,
            Map<String, String> bounds) {
        this(sequence, fingerprints, names, bounds, 0L);
    }

    /** Constructor at the prior canonical arity: no connection targets recorded. */
    StalenessCapture(long sequence, Map<String, String> fingerprints, Map<String, String> names,
            Map<String, String> bounds, long trackId) {
        this(sequence, fingerprints, names, bounds, trackId, Map.of());
    }

    StalenessCapture {
        // Defensive immutable copies — the guard hands in mutable LinkedHashMaps it built.
        fingerprints = Map.copyOf(fingerprints);
        names = Map.copyOf(names);
        bounds = Map.copyOf(bounds);
        connections = Map.copyOf(connections);
    }

    /**
     * Back-compat convenience constructor for prior call-sites (and tests) that carry no bounds map.
     * Delegates to the canonical 4-arg ctor with an empty bounds map (record-arity discipline —
     * never throws). New diagram-object
     * move-staleness only fires when the bounds map is populated, so these sites keep their exact prior behaviour.
     */
    StalenessCapture(long sequence, Map<String, String> fingerprints, Map<String, String> names) {
        this(sequence, fingerprints, names, Map.of());
    }

    /** The set of pre-existing object ids this proposal targets (empty for creates). */
    Set<String> targetIds() {
        return fingerprints.keySet();
    }
}
