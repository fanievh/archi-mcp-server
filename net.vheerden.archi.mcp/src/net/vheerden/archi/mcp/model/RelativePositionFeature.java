package net.vheerden.archi.mcp.model;

import java.lang.reflect.Method;

import com.archimatetool.model.IDiagramModelConnection;

/**
 * Runtime feature-detect for a diagram connection's label-offset anchor ("Label Offset").
 *
 * <p>Newer Archi adds {@code getRelativePosition()}/{@code setRelativePosition(int)} on a diagram
 * connection — a compass anchor (Centre plus eight points) that offsets the label off the line, backed
 * by the connection's generic feature store under the key {@code "textRelativePosition"}. Older target
 * platforms have neither method.</p>
 *
 * <p>Access is via <strong>method reflection</strong> so the bundle COMPILES against the older platform
 * (no compile-time reference to the newer methods) and, at runtime there, the offset path is a silent
 * no-op (the methods are absent → unsupported). The methods are resolved once against
 * {@link IDiagramModelConnection}; where present they delegate to Archi's own accessors, so the default
 * and storage exactly match the host platform.</p>
 */
final class RelativePositionFeature {

    /** Anchor value for a centred (un-offset) label — the feature default where present. */
    static final int CENTER = 2;

    private static final Method GETTER = resolve("getRelativePosition", false);
    private static final Method SETTER = resolve("setRelativePosition", true);

    private RelativePositionFeature() {
        // static-only utility
    }

    private static Method resolve(String name, boolean setter) {
        try {
            return setter
                    ? IDiagramModelConnection.class.getMethod(name, int.class)
                    : IDiagramModelConnection.class.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null; // older platform — method not present
        }
    }

    /** True when the running platform exposes the label-offset accessors for this connection. */
    static boolean isSupported(IDiagramModelConnection connection) {
        return connection != null && GETTER != null && SETTER != null;
    }

    /** Current anchor, or {@link #CENTER} when the feature is absent/unreadable. */
    static int get(IDiagramModelConnection connection) {
        if (!isSupported(connection)) {
            return CENTER;
        }
        try {
            Object value = GETTER.invoke(connection);
            return (value instanceof Integer i) ? i : CENTER;
        } catch (ReflectiveOperationException e) {
            return CENTER;
        }
    }

    /**
     * Writes the anchor bitmask. No-op when the feature is absent (older platform): there is nothing to
     * call, so nothing changes and nothing breaks. A reflective failure is swallowed — a label cosmetic
     * must never break a mutation.
     */
    static void set(IDiagramModelConnection connection, int anchorMask) {
        if (!isSupported(connection)) {
            return;
        }
        try {
            SETTER.invoke(connection, anchorMask);
        } catch (ReflectiveOperationException e) {
            // intentional no-op
        }
    }
}
