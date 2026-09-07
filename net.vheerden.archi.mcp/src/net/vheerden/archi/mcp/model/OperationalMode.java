package net.vheerden.archi.mcp.model;

/**
 * Operational modes for mutation dispatch.
 *
 * <p>Controls how mutations are applied to the ArchiMate model:</p>
 * <ul>
 *   <li>{@link #GUI_ATTACHED} — mutations applied immediately via CommandStack,
 *       UI updates in real-time</li>
 *   <li>{@link #BATCH} — mutations queued, applied atomically on end-batch
 *       commit via NonNotifyingCompoundCommand</li>
 * </ul>
 */
public enum OperationalMode {
    GUI_ATTACHED,
    BATCH
}
