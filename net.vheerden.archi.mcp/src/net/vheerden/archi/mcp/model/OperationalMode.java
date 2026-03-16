package net.vheerden.archi.mcp.model;

/**
 * Operational modes for mutation dispatch (Story 7-1).
 *
 * <p>Controls how mutations are applied to the ArchiMate model:</p>
 * <ul>
 *   <li>{@link #GUI_ATTACHED} — mutations applied immediately via CommandStack,
 *       UI updates in real-time (FR39)</li>
 *   <li>{@link #BATCH} — mutations queued, applied atomically on end-batch
 *       commit via NonNotifyingCompoundCommand (FR40)</li>
 * </ul>
 */
public enum OperationalMode {
    GUI_ATTACHED,
    BATCH
}
