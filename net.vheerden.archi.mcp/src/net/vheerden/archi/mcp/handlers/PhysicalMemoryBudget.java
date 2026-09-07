package net.vheerden.archi.mcp.handlers;

import java.lang.management.ManagementFactory;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supplies the number of bytes of physical memory currently <em>free</em>, for
 * the pre-allocation raster guard in {@link RenderHandler}.
 *
 * <p><strong>Free, not "available".</strong> Operating systems report two
 * different figures: memory that is free right now, and a larger "available"
 * figure that counts page cache the kernel would evict under pressure. This
 * class reports the first, and says so, because the second is a number nobody
 * here has verified the allocator can actually obtain. The evidence points the
 * same way: the render that exhausted the machine did so with the entire swap
 * file untouched and roughly 412 MB free, so on this platform the free figure
 * was the binding constraint and the reclaimable cache did not save the
 * allocation. Reporting the smaller number errs toward refusing, which is the
 * safe direction for a guard.</p>
 *
 * <p><strong>Why physical memory and not the Java heap.</strong> Archi renders a
 * view by asking SWT for the whole diagram as one native {@code Image}. On macOS
 * that is an {@code NSBitmapImageRep} allocated outside the Java heap, so a
 * render large enough to exhaust the machine kills the process with a native
 * fault while the heap is still almost empty. {@code Runtime.getRuntime()}
 * measures the heap and therefore reports a resource that was never scarce;
 * it must not be used here.</p>
 *
 * <p><strong>Why the reading is guarded.</strong>
 * {@code com.sun.management.OperatingSystemMXBean} is not a {@code java.*} type,
 * so its visibility from inside this bundle depends on the framework's system
 * package exports rather than on anything this bundle declares. Rather than
 * assume it resolves, the reading is attempted and any failure degrades to
 * {@link #FALLBACK_FREE_BYTES}. A guard that throws where it cannot measure
 * would be worse than one that measures conservatively.</p>
 */
public final class PhysicalMemoryBudget implements LongSupplier {

    private static final Logger logger = LoggerFactory.getLogger(PhysicalMemoryBudget.class);

    /**
     * Number of full-size copies of the raster the export path holds at the same
     * time, and therefore the number of times the projected byte count must fit
     * in the free pool before one render is safe.
     *
     * <p>Derived from the render path itself, not from any observed failure:
     * {@code ViewExportService.renderPng} (and its JPG twin) obtains the native
     * {@code Image}, then immediately takes an {@code ImageData} copy of its
     * pixels and keeps that copy live across the encode — the native image is
     * released only in the enclosing {@code finally}. Two full-size rasters are
     * therefore resident together.
     *
     * <p>The encoder's output buffer and the array copy taken from it are real
     * demand this constant does not count, so the budget under-states what a
     * render needs — but the shortfall is small and measured, not open-ended.
     * Across the eleven-view reference corpus the least compressible diagram
     * encoded at 34:1, so the encoded image is under 3% of one raw raster; even
     * counting the buffer growth and the array copy that briefly hold it several
     * times over, the uncounted demand stays a few percent of a single raster
     * against a budget that reserves a whole one. Raising the divisor to swallow
     * that margin would refuse renders this project has watched succeed, which is
     * why it is disclosed rather than padded.</p>
     */
    static final int CONCURRENT_RASTER_COPIES = 2;

    /**
     * Budget used when free physical memory cannot be read.
     *
     * <p>Chosen inside a window whose endpoints are both measured rather than
     * picked. The lower endpoint is the largest raster export this project has
     * observed to complete — a 6777 x 6538 view at scale 1.0, 178.3 MB — which
     * needs a budget above {@code 2 x 178.3 MB = 357 MB} to survive the guard.
     * The upper endpoint is the render that exhausted the machine, 711 MB, which
     * must still be refused and therefore requires a budget below
     * {@code 2 x 711 MB = 1422 MB}. 512 MB clears the lower endpoint by 43% and
     * sits 2.8x below the upper.</p>
     */
    static final long FALLBACK_FREE_BYTES = 512L * 1024L * 1024L;

    @Override
    public long getAsLong() {
        return readFreePhysicalBytes();
    }

    /**
     * Reads free physical memory, degrading to {@link #FALLBACK_FREE_BYTES} when
     * the platform bean cannot be reached.
     *
     * @return bytes of physical memory currently free
     */
    static long readFreePhysicalBytes() {
        try {
            return freePhysicalBytesFrom(ManagementFactory.getOperatingSystemMXBean());
        } catch (Throwable t) {
            logger.warn("Could not obtain the operating system bean; "
                    + "falling back to a static memory budget: {}", t.toString());
            return FALLBACK_FREE_BYTES;
        }
    }

    /**
     * Extracts free physical memory from an operating system bean, or falls back
     * when the bean is absent, is not the extended platform type, or reports a
     * non-positive figure.
     *
     * <p>Taken as {@code Object} so the fallback arm is reachable from a test on
     * any platform: the extended type is referenced only inside the try, so a
     * runtime that cannot resolve it raises a linkage error here and degrades
     * exactly as an absent bean would.</p>
     *
     * @param osBean the operating system bean, or any other value
     * @return free bytes, or {@link #FALLBACK_FREE_BYTES}
     */
    static long freePhysicalBytesFrom(Object osBean) {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean extended) {
                long free = extended.getFreeMemorySize();
                if (free > 0L) {
                    return free;
                }
                logger.warn("Operating system bean reported {} bytes of free physical "
                        + "memory; falling back to a static memory budget", free);
            } else {
                logger.warn("Operating system bean does not expose free physical memory; "
                        + "falling back to a static memory budget");
            }
        } catch (Throwable t) {
            logger.warn("Free physical memory is not readable from this runtime; "
                    + "falling back to a static memory budget: {}", t.toString());
        }
        return FALLBACK_FREE_BYTES;
    }

    /**
     * Returns the share of a free pool that one bitmap may claim.
     *
     * <p>Two bounds are applied. The first is {@link #CONCURRENT_RASTER_COPIES}:
     * the export path holds the raster twice over, so one projected bitmap may
     * claim at most half of what is free. The second is a ceiling of last
     * resort at {@code Integer.MAX_VALUE}, because the buffers the raster is
     * copied into are indexed by {@code int} and cannot address more than that
     * however much memory the machine has free.</p>
     *
     * @param freeBytes bytes of physical memory currently free
     * @return the largest projected raster, in bytes, that may be allowed
     */
    static long singleBitmapBudget(long freeBytes) {
        if (freeBytes <= 0L) {
            return 0L;
        }
        return Math.min(freeBytes / CONCURRENT_RASTER_COPIES, Integer.MAX_VALUE);
    }
}
