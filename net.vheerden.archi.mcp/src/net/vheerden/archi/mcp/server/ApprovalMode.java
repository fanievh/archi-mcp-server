package net.vheerden.archi.mcp.server;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.McpPlugin;

/**
 * The human-owned, authoritative approval-mode bit (control plane → human).
 *
 * <p><strong>Control/data-plane split.</strong> The human owns this gate; the agent
 * may only observe it. There is intentionally <em>no</em> MCP-callable setter — the
 * one and only writer is the SWT toggle handler (verify by grep: the sole caller of
 * {@link #setOn(boolean)} and the sole writer of {@link McpPlugin#PREF_APPROVAL_MODE}
 * is the UI handler). The {@code model/} layer reads it through the read-only
 * {@link net.vheerden.archi.mcp.model.ApprovalModeProvider} seam.</p>
 *
 * <p><strong>Persisted across restarts.</strong> The human's last
 * choice survives a restart via the stored {@link McpPlugin#PREF_APPROVAL_MODE} preference
 * (a desktop preference like the others). The singleton is created GATED
 * ({@link McpPlugin#DEFAULT_APPROVAL_MODE} = on) at class-load — fail safe, and with <em>no</em>
 * dependency on the plugin being started yet — and the stored value is applied later via
 * {@link #restoreFromPreference()}, called once from {@code McpPlugin.start()} when the
 * preference store is guaranteed available. The stored value is read only through
 * {@link McpPlugin#isApprovalModeEnabled()}, which falls back to GATED when the preference is missing or
 * unusable, so a fresh install (preference never set) stays GATED even if no default was registered.
 * {@link #setOn(boolean)} writes the preference so the next start restores it.</p>
 *
 * <p>Reading the preference at a well-defined lifecycle point (rather than in the static
 * initializer) avoids any class-load-ordering race: if {@code ApprovalMode} were touched
 * before {@code McpPlugin.start()}, a static-init read would silently see a null plugin and
 * lock in the default forever.</p>
 *
 * <p>The in-memory value is the effective bit; the preference is its durable backing store.
 * The persister is an injectable seam so the holder's read/write behaviour is unit-testable
 * headlessly (no live plugin or pref store).</p>
 */
public final class ApprovalMode {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalMode.class);

    /** Singleton: GATED at class-load (no plugin dependency); restored from the pref at plugin start. */
    private static final ApprovalMode INSTANCE =
            new ApprovalMode(McpPlugin.DEFAULT_APPROVAL_MODE, ApprovalMode::persistIntent);

    /** Effective bit. {@code volatile} — read from Jetty threads, written from the UI thread. */
    private volatile boolean on;

    private final Consumer<Boolean> intentPersister;

    /**
     * Listeners notified when the effective bit <em>changes</em>. The Pending Approvals
     * view subscribes so it can swap its empty-gated ↔ gate-off panel the instant the human toggles
     * the mode — a mode flip is neither a queue change nor a server-state change, so without this the
     * view only updated on a manual Refresh. {@link CopyOnWriteArrayList} so add/remove during a fire
     * is safe. Listeners must be cheap and non-throwing (guarded below).
     */
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * Package/test-visible constructor. Production uses the singleton; tests pass an explicit
     * initial value and a no-op persister to exercise seed + read/write headlessly.
     *
     * @param initial         the initial effective bit (production: the fail-safe default)
     * @param intentPersister persists the human's intent to the durable backing store
     */
    ApprovalMode(boolean initial, Consumer<Boolean> intentPersister) {
        this.on = initial;
        this.intentPersister = intentPersister;
    }

    /**
     * @return the process-wide approval-mode holder
     */
    public static ApprovalMode getInstance() {
        return INSTANCE;
    }

    /**
     * Restores the effective bit from the stored {@link McpPlugin#PREF_APPROVAL_MODE} preference, read
     * through {@link McpPlugin#isApprovalModeEnabled()} (GATED when the value is missing or unusable).
     * Called once from {@code McpPlugin.start()} — after the plugin is
     * activated — so the read is deterministic and never races
     * class-loading. A no-op (leaves the fail-safe default) when the plugin is unavailable.
     * Does not re-persist: this reads the durable value, it does not write one.
     */
    public void restoreFromPreference() {
        McpPlugin plugin = McpPlugin.getDefault();
        if (plugin == null) {
            return; // leave the fail-safe default (GATED)
        }
        this.on = plugin.isApprovalModeEnabled();
        logger.info("Approval mode restored from preference: {}", on ? "ON (gated)" : "OFF (direct apply)");
    }

    /**
     * @return {@code true} if approval mode is on (effective, in-memory bit)
     */
    public boolean isOn() {
        return on;
    }

    /**
     * Sets the effective approval bit and persists the human's intent for display.
     *
     * <p><strong>Only the SWT toggle handler may call this.</strong> No MCP request,
     * handler, or model-layer code path reaches it — that absence is the safety control
     * (the only robust guard is non-existence on the agent side).</p>
     *
     * @param value {@code true} to gate (require approval), {@code false} to allow direct apply
     */
    public void setOn(boolean value) {
        boolean changed = this.on != value;
        this.on = value;
        logger.info("Approval mode set by human: {}", value ? "ON (gated)" : "OFF (direct apply)");
        if (intentPersister != null) {
            intentPersister.accept(value);
        }
        if (changed) {
            fireChanged();
        }
    }

    /**
     * Registers a listener notified whenever the effective bit changes. Idempotent —
     * a re-registered listener is added once. The Pending Approvals view adds itself on open and
     * removes on dispose.
     *
     * @param listener the listener to add (ignored if null)
     */
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.addIfAbsent(listener);
        }
    }

    /**
     * Removes a previously registered change listener.
     *
     * @param listener the listener to remove (no-op if null or not registered)
     */
    public void removeChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.remove(listener);
        }
    }

    /** Notifies change listeners that the effective bit changed. Each callback is guarded. */
    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                logger.warn("Approval-mode change listener threw (ignored)", e);
            }
        }
    }

    /** Persists the human's intent to the preference store (the durable backing store). */
    private static void persistIntent(boolean value) {
        McpPlugin plugin = McpPlugin.getDefault();
        if (plugin != null) {
            plugin.getPreferenceStore().setValue(McpPlugin.PREF_APPROVAL_MODE, value);
        }
    }
}
