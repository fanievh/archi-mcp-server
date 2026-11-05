package net.vheerden.archi.mcp.harness;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

/**
 * A class that starts a child process sharing the runner's standard output, then makes standard
 * output block forever and overruns its deadline, for {@link HeadlessTestRunnerWatchdogTest}: the
 * timeout report can then never be written, so only the halt that does not wait for it can end the
 * child. Deliberately not named {@code *Test} so the harness scan never runs it.
 */
public class SpawnsAChildThenBlocksStandardOutputFixture {

    @Test
    public void spawnsAChildThenBlocksStandardOutputThenOverruns() throws Exception {
        SpawnsAChildThenSleepsFixture.spawnInheritingChild();
        CountDownLatch never = new CountDownLatch(1);
        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, true));
        Thread.sleep(600_000L);
    }
}
