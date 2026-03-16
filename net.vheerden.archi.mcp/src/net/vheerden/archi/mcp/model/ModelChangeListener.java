package net.vheerden.archi.mcp.model;

/**
 * Callback interface for receiving model change notifications.
 *
 * <p>Used by {@link ArchiModelAccessorImpl} to notify the server layer
 * when the active ArchiMate model changes (opened, closed, or switched).
 * The server layer uses this to log model changes and clear session state.</p>
 *
 * <p>Callbacks may be invoked on any thread (UI thread or background thread).
 * Implementations that update UI must use {@code Display.asyncExec()}.</p>
 */
@FunctionalInterface
public interface ModelChangeListener {

    /**
     * Called when the active model changes.
     *
     * @param modelName the name of the new active model, or null if no model is loaded
     * @param modelId   the ID of the new active model, or null if no model is loaded
     */
    void onModelChanged(String modelName, String modelId);
}
