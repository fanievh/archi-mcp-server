package net.vheerden.archi.mcp.harness;

import org.junit.Test;

/**
 * A class that starts a child process sharing the runner's standard output and then passes, for
 * {@link HeadlessTestRunnerWatchdogTest}. Deliberately not named {@code *Test} so the harness scan
 * never runs it as a real class.
 */
public class SpawnsAChildThenFinishesFixture {

    @Test
    public void spawnsAChildThenPasses() throws Exception {
        SpawnsAChildThenSleepsFixture.spawnInheritingChild();
    }
}
