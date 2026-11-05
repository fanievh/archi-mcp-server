package net.vheerden.archi.mcp.handlers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The zoom, in percent, at which SWT backs an image on this display: 200 on a Retina display, 100 on most others.
 *
 * <p>SWT allocates an image's bitmap at this zoom ({@code Image.getRepresentation()} asks for the representation
 * at {@code DPIUtil.getDeviceZoom()}), so a view rendered at 1000 x 1000 points occupies 2000 x 2000 pixels on a
 * Retina display: four times the bytes. The read is a static field, safe off the UI thread.</p>
 *
 * <p>A zoom that cannot be read answers 0, which {@link RenderHandler} counts as {@link #UNKNOWN_ZOOM_AS}, the larger
 * common zoom, so a bitmap is never under-counted.</p>
 */
final class DeviceZoom implements IntSupplier {

    private static final Logger logger = LoggerFactory.getLogger(DeviceZoom.class);

    /** The zoom assumed when the platform's cannot be read, or is not positive. */
    static final int UNKNOWN_ZOOM_AS = 200;

    /** Set once the fallback has been logged, so a server that cannot read its zoom logs it once, not per export. */
    private static final AtomicBoolean FALLBACK_LOGGED = new AtomicBoolean();

    @Override
    public int getAsInt() {
        try {
            return org.eclipse.swt.internal.DPIUtil.getDeviceZoom();
        } catch (LinkageError | RuntimeException e) {
            logFallback("could not be read", e);
            return 0;
        }
    }

    /**
     * {@code zoom}, or {@link #UNKNOWN_ZOOM_AS} when it is not positive or {@code source} throws. Falling back is
     * logged at WARN, once per run.
     */
    static int effective(IntSupplier source) {
        int zoom;
        try {
            zoom = source.getAsInt();
        } catch (RuntimeException e) {
            logFallback("could not be read", e);
            return UNKNOWN_ZOOM_AS;
        }
        if (zoom > 0) {
            return zoom;
        }
        logFallback("was " + zoom + "%", null);
        return UNKNOWN_ZOOM_AS;
    }

    private static void logFallback(String why, Throwable cause) {
        if (FALLBACK_LOGGED.compareAndSet(false, true)) {
            logger.warn("The display's zoom {}; raster exports are projected at {}%", why, UNKNOWN_ZOOM_AS, cause);
        }
    }
}
