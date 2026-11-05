package net.vheerden.archi.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import net.vheerden.archi.mcp.McpPlugin;

/**
 * What {@link McpServerManager#stop()} reports when the transport stops, or gives up on stopping.
 *
 * <p>Runs without OSGi. With no plugin running, {@code start()} ends in {@link ServerState#ERROR}
 * before it reaches anything that needs the platform, and {@code stop()} still acts on a manager in
 * ERROR; the transport is started directly, so the stop has a live server to act on.</p>
 */
public class McpServerManagerStopTest {

    private static final String TEST_BIND_ADDRESS = "127.0.0.1";

    private final CountDownLatch release = new CountDownLatch(1);
    /** How many times an MCP SDK server was closed, through {@link #countingClose()}. */
    private final AtomicInteger closes = new AtomicInteger();
    private final List<String> transitions = new CopyOnWriteArrayList<>();
    private TransportConfig transport;

    @After
    public void tearDown() {
        release.countDown();
        if (transport != null) {
            transport.stopServer();
        }
    }

    @Test(timeout = 15_000)
    public void shouldReportError_whenTheStopIsGivenUpOn() throws Exception {
        transport = new TransportConfig(() -> null, 1_000, TransportConfigTest.heldUntil(release));
        McpServerManager manager = startedManager();

        manager.stop();

        assertEquals(ServerState.ERROR, manager.getState());
        assertEquals("Server stop failed — Jetty may still be running.", manager.getLastErrorMessage());
    }

    @Test(timeout = 15_000)
    public void shouldReportStopped_whenTheStopFinishesInTime() throws Exception {
        transport = new TransportConfig(() -> null);
        McpServerManager manager = startedManager();

        manager.stop();

        assertEquals(ServerState.STOPPED, manager.getState());
        assertNull(manager.getLastErrorMessage());
    }

    // ---- A stop given up on that finishes later ----

    @Test(timeout = 15_000)
    public void shouldReportStopped_whenAStopGivenUpOnFinishesLater() throws Exception {
        transport = new TransportConfig(() -> null, 1_000, TransportConfigTest.heldUntil(release), countingClose());
        McpServerManager manager = startedManager();
        manager.stop();
        assertEquals(ServerState.ERROR, manager.getState());
        assertEquals("Server stop failed — Jetty may still be running.", manager.getLastErrorMessage());
        assertEquals("nothing is closed while the stop is stuck", 0, closes.get());

        release.countDown();

        awaitState(manager, ServerState.STOPPED);
        assertNull(manager.getLastErrorMessage());
        assertFalse(transport.isRunning());
        assertEquals(0, transport.getPort());
        assertEquals("both MCP SDK servers are closed", 2, closes.get());
        assertEquals(List.of("ERROR->STOPPING", "STOPPING->ERROR", "ERROR->STOPPED"), transitions);
    }

    @Test(timeout = 15_000)
    public void shouldReportStoppedWithoutWaitingOutTheBound_whenTheStopFinishesDuringTheWait() throws Exception {
        long bound = 4_000;
        long releasedAfter = bound / 2;
        transport = new TransportConfig(() -> null, bound, TransportConfigTest.heldUntil(release), countingClose());
        McpServerManager manager = startedManager();
        Thread opener = new Thread(() -> {
            try {
                Thread.sleep(releasedAfter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                release.countDown();
            }
        });
        opener.setDaemon(true);

        long started = System.nanoTime();
        opener.start();
        manager.stop();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // the stop is told it has ended while stop() waits for it holding the manager; were that notice
        // to need the manager before the stop counts as ended, the wait would run to the bound
        assertTrue("stop() returned after " + tookMs + " ms; the stop was released after " + releasedAfter
                + " ms and is bounded at " + bound + " ms",
                tookMs >= releasedAfter - 100 && tookMs < bound - 1_000);
        assertEquals(ServerState.STOPPED, manager.getState());
        assertNull(manager.getLastErrorMessage());
        assertEquals(2, closes.get());
    }

    @Test(timeout = 15_000)
    public void shouldChangeNothing_whenAStopThatFinishedInTimeSaysItHasEnded() throws Exception {
        transport = new TransportConfig(() -> null, 5_000, server -> {
            server.stop();
            server.join();
        }, countingClose());
        McpServerManager manager = startedManager();
        // added after the manager's own, so it is told after the manager has handled the same notice
        CountDownLatch told = new CountDownLatch(1);
        transport.addStopEndedListener(told::countDown);

        manager.stop();
        assertTrue("the stop says it has ended", told.await(5, TimeUnit.SECONDS));

        assertEquals(ServerState.STOPPED, manager.getState());
        assertNull(manager.getLastErrorMessage());
        assertEquals("no second close", 2, closes.get());
        assertEquals(List.of("ERROR->STOPPING", "STOPPING->STOPPED"), transitions);
    }

    @Test(timeout = 20_000)
    public void shouldKeepTheRefusedStartsError_whenTheStopItWasRefusedOverFinishesLater() throws Exception {
        transport = new TransportConfig(() -> null, 1_000, TransportConfigTest.heldUntil(release), countingClose());
        McpServerManager manager = startedManager();
        manager.stop();
        // a start while that stop is still stuck waits for it once more, then is refused
        manager.start(0, TEST_BIND_ADDRESS);
        String refused = "The previous MCP Server has not finished stopping. Try again shortly, or restart Archi.";
        assertEquals(ServerState.ERROR, manager.getState());
        assertEquals(refused, manager.getLastErrorMessage());

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (transport.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertFalse("the finished stop is released", transport.isRunning());
        // the manager releases it holding its own lock: once that is free, it has also decided its state
        synchronized (manager) {
            assertEquals(2, closes.get());
        }
        assertEquals("the state reports the refused start, which a finished stop does not undo",
                ServerState.ERROR, manager.getState());
        assertEquals(refused, manager.getLastErrorMessage());
    }

    private TransportConfig.McpServerClose countingClose() {
        return server -> {
            closes.incrementAndGet();
            server.close();
        };
    }

    /** Waits up to 5 s for the manager to reach {@code expected}, then asserts it. */
    static void awaitState(McpServerManager manager, ServerState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (manager.getState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(expected, manager.getState());
    }

    private McpServerManager startedManager() throws Exception {
        assumeTrue("needs no running plugin: under OSGi start() would really start the server",
                McpPlugin.getDefault() == null);
        transport.startServer(0, TEST_BIND_ADDRESS);
        McpServerManager manager = new McpServerManager(transport);
        manager.start();
        assertEquals("precondition: without a plugin, start ends in ERROR", ServerState.ERROR, manager.getState());
        manager.addStateListener((oldState, newState) -> transitions.add(oldState + "->" + newState));
        return manager;
    }
}
