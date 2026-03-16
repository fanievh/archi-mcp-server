package net.vheerden.archi.mcp.model.routing;

/**
 * Represents a connection that could not be routed within constraints (Story 10-30).
 * Enriched with crossed obstacle identity (Story 10-34).
 * Pure-geometry record — no EMF/SWT dependencies.
 *
 * @param connectionId       unique identifier for the connection
 * @param sourceId           source element ID
 * @param targetId           target element ID
 * @param constraintViolated description of which constraint was violated (e.g. "element_crossing")
 * @param crossedElementId   view object ID of the first crossed obstacle, or null if unknown
 */
public record FailedConnection(String connectionId, String sourceId,
                                String targetId, String constraintViolated,
                                String crossedElementId) {

    /** Backward-compatible constructor without crossedElementId. */
    public FailedConnection(String connectionId, String sourceId,
                            String targetId, String constraintViolated) {
        this(connectionId, sourceId, targetId, constraintViolated, null);
    }
}
