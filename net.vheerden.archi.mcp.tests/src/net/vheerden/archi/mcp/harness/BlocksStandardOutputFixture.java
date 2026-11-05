package net.vheerden.archi.mcp.harness;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

/**
 * A class that overruns its deadline after making standard output block forever, for
 * {@link HeadlessTestRunnerWatchdogTest}: the runner's timeout report can then never be written, so
 * only a halt that does not wait for the report ends the run. Deliberately not named {@code *Test} so
 * the harness scan never runs it.
 */
public class BlocksStandardOutputFixture {

    @Test
    public void blocksStandardOutputThenOverruns() throws InterruptedException {
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
