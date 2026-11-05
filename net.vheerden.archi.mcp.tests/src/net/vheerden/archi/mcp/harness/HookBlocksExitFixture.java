package net.vheerden.archi.mcp.harness;

import org.junit.Test;

/**
 * A class that overruns its deadline while holding a lock that a shutdown hook needs, for
 * {@link HeadlessTestRunnerWatchdogTest}: an orderly JVM exit would wait for that hook forever, so
 * only a halt ends the run. Deliberately not named {@code *Test} so the harness scan never runs it.
 */
public class HookBlocksExitFixture {

    private static final Object LOCK = new Object();

    @Test
    public void holdsALockAShutdownHookNeeds() throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (LOCK) {
                // reached only once the stuck test releases the lock — never
            }
        }));
        synchronized (LOCK) {
            Thread.sleep(600_000L);
        }
    }
}
