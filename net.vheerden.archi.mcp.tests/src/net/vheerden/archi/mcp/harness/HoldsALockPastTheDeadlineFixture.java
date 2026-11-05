package net.vheerden.archi.mcp.harness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.Test;

/**
 * A class whose test waits past its deadline for a lock another thread holds, for
 * {@link HeadlessTestRunnerWatchdogTest}: the dump must name both the waiting test and the thread that
 * owns the lock. Deliberately not named {@code *Test} so the harness scan never runs it.
 */
public class HoldsALockPastTheDeadlineFixture {

    /** Name of the thread that takes the lock and never lets it go. */
    public static final String HOLDER_THREAD = "fixture-lock-holder";

    private static final ReentrantLock LOCK = new ReentrantLock();

    @Test
    public void waitsForALockAnotherThreadHolds() throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(() -> holdForever(held), HOLDER_THREAD);
        holder.setDaemon(true);
        holder.start();
        held.await();
        LOCK.lockInterruptibly();
    }

    private static void holdForever(CountDownLatch held) {
        LOCK.lock();
        try {
            held.countDown();
            Thread.sleep(600_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            LOCK.unlock();
        }
    }
}
