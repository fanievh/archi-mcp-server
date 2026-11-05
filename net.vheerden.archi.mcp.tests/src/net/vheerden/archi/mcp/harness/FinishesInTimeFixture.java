package net.vheerden.archi.mcp.harness;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A class that finishes well inside its deadline, for {@link HeadlessTestRunnerWatchdogTest}.
 * Deliberately not named {@code *Test} so the harness scan never runs it as a real class.
 */
public class FinishesInTimeFixture {

    @Test
    public void finishesImmediately() {
        assertTrue(true);
    }
}
