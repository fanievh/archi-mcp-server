package net.vheerden.archi.mcp.harness;

import org.junit.Test;

/**
 * A class that never finishes in time, for {@link HeadlessTestRunnerWatchdogTest}. Deliberately not
 * named {@code *Test} so the harness scan never runs it as a real class.
 */
public class SleepsPastTheDeadlineFixture {

    @Test
    public void sleepsFarPastAnyDeadline() throws InterruptedException {
        Thread.sleep(600_000L);
    }
}
