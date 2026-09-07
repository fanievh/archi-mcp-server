package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.lang.management.ManagementFactory;

import org.junit.Test;

/**
 * Unit tests for {@link PhysicalMemoryBudget}.
 *
 * <p>The degraded arm is exercised directly rather than left reachable only on a
 * platform that cannot answer. A fallback that no test can reach is a fallback
 * nobody has run.</p>
 */
public class PhysicalMemoryBudgetTest {

    @Test
    public void shouldFallBack_whenThereIsNoOperatingSystemBean() {
        assertEquals(PhysicalMemoryBudget.FALLBACK_FREE_BYTES,
                PhysicalMemoryBudget.freePhysicalBytesFrom(null));
    }

    @Test
    public void shouldFallBack_whenBeanDoesNotExposeFreePhysicalMemory() {
        assertEquals(PhysicalMemoryBudget.FALLBACK_FREE_BYTES,
                PhysicalMemoryBudget.freePhysicalBytesFrom("not an operating system bean"));
    }

    @Test
    public void shouldAnswerWithoutThrowing_onAnyPlatform() {
        // Deliberately weak, and named for what it pins: the production entry
        // points answer rather than throw, whether the platform resolves the
        // extended bean or not. The test below is the one that proves a real
        // reading actually happens.
        assertTrue(PhysicalMemoryBudget.readFreePhysicalBytes() > 0L);
        assertTrue(new PhysicalMemoryBudget().getAsLong() > 0L);
    }

    @Test
    public void shouldReturnARealReading_whenPlatformExposesTheExtendedBean() {
        Object bean = ManagementFactory.getOperatingSystemMXBean();
        assumeTrue("platform does not expose the extended bean; nothing to prove here",
                bean instanceof com.sun.management.OperatingSystemMXBean);

        long reading = PhysicalMemoryBudget.freePhysicalBytesFrom(bean);

        // Without this, the real-bean arm could become dead code and every other
        // test in this class would still pass on the fallback alone.
        assertTrue("a real reading must be positive", reading > 0L);
        assertNotEquals("must be a reading from the platform, not the static fallback",
                PhysicalMemoryBudget.FALLBACK_FREE_BYTES, reading);
        assertEquals("the production entry point must take the same arm",
                reading > 0L, PhysicalMemoryBudget.readFreePhysicalBytes() > 0L);
    }

    @Test
    public void shouldAllowOneBitmapOnlyItsShareOfThePool() {
        // The export path holds the raster twice over, so one bitmap gets half.
        assertEquals(500L, PhysicalMemoryBudget.singleBitmapBudget(1000L));
        assertEquals(256L * 1024L * 1024L,
                PhysicalMemoryBudget.singleBitmapBudget(512L * 1024L * 1024L));
    }

    @Test
    public void shouldCapAtTheAddressableCeiling_whenMemoryIsAbundant() {
        assertEquals(Integer.MAX_VALUE,
                PhysicalMemoryBudget.singleBitmapBudget(Long.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE,
                PhysicalMemoryBudget.singleBitmapBudget(64L * 1024L * 1024L * 1024L));
    }

    @Test
    public void shouldAllowNothing_whenNoMemoryIsAvailable() {
        assertEquals(0L, PhysicalMemoryBudget.singleBitmapBudget(0L));
        assertEquals(0L, PhysicalMemoryBudget.singleBitmapBudget(-1L));
    }

    @Test
    public void shouldSitInsideTheWindowItsRationaleNames() {
        // Above twice the largest export observed to complete (178.3 MB), so that
        // work still passes; below twice the render that exhausted the machine
        // (711 MB), so that one is still refused.
        assertTrue(PhysicalMemoryBudget.FALLBACK_FREE_BYTES > 2L * 178_298_904L);
        assertTrue(PhysicalMemoryBudget.FALLBACK_FREE_BYTES < 2L * 711_060_416L);
    }
}
