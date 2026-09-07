package net.vheerden.archi.mcp.response.dto;

/**
 * One connection whose label a routing pass hid because the label had no collision-free position.
 *
 * <p><strong>Identity, not a tally.</strong> A caller that cannot see the canvas needs to know
 * <em>which</em> labels went away, not how many: a count is an index into information the response
 * never sent, and leaves the caller unable to restore, audit or explain any individual label. Each
 * entry therefore names the connection and states why it was hidden, and the list is never
 * truncated.</p>
 *
 * <p>Every hide is reversible with an ordinary per-connection label-visibility update using the
 * {@code connectionId} reported here.</p>
 *
 * @param connectionId the view connection whose label was hidden
 * @param reason       machine-readable reason code — see {@link #REASON_NO_VALID_POSITION}
 */
public record HiddenLabelDto(String connectionId, String reason) {

    /**
     * The label collided at every one of the three candidate text positions, and no perpendicular
     * offset direction lifted it clear either.
     */
    public static final String REASON_NO_VALID_POSITION = "no_valid_position";

    /** Convenience factory for the only reason the current policy can produce. */
    public static HiddenLabelDto noValidPosition(String connectionId) {
        return new HiddenLabelDto(connectionId, REASON_NO_VALID_POSITION);
    }
}
