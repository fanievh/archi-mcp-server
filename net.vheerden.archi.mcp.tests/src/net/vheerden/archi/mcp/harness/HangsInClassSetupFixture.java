package net.vheerden.archi.mcp.harness;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A class that never gets past its class-level setup, for {@link HeadlessTestRunnerWatchdogTest}: no
 * test has started when the deadline passes. Deliberately not named {@code *Test} so the harness scan
 * never runs it.
 */
public class HangsInClassSetupFixture {

    @BeforeClass
    public static void sleepsFarPastAnyDeadline() throws InterruptedException {
        Thread.sleep(600_000L);
    }

    @Test
    public void neverRuns() {
    }
}
