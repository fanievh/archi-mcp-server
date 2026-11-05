package net.vheerden.archi.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import io.modelcontextprotocol.server.McpSyncServer;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * What {@link McpServerManager#start(int, String)} leaves behind when its start fails with an
 * {@link Error} rather than an exception.
 *
 * <p>Runs without OSGi. The transport is a subclass that throws the test's {@code Error} at one of
 * two points: from {@code startServer}, so no server is left running, or from the first read of the
 * MCP server after Jetty has started, so the failure arrives while the server is up. Both points come
 * before anything in the start needs the platform.</p>
 *
 * <p>The later cases make the clean-up itself fail: a session manager whose {@code dispose()} throws,
 * or a state listener that throws when told of {@link ServerState#ERROR}. The manager has no way in for
 * a session manager, model accessor or approval service other than its own start, which never gets that
 * far here, so {@link #putIntoManager} writes them into its private fields.</p>
 *
 * <p>Other cases make a call inside disposing the session manager or the model accessor throw: the
 * accessor removing a listener, the dispatcher clearing its sessions, an approval-queue listener told of
 * that clearing, or a {@code dispose()} itself. Most fail a start; one runs through {@code stop()}.</p>
 */
public class McpServerManagerFailedStartTest {

    private static final String TEST_BIND_ADDRESS = "127.0.0.1";
    private static final String MESSAGE = "thrown by the test's transport";
    private static final String SESSION_MANAGER_MESSAGE = "thrown by the test's session manager";
    private static final String LISTENER_MESSAGE = "thrown by the test's state listener";
    private static final String ACCESSOR_MESSAGE = "thrown by the test's model accessor";
    private static final String DISPATCHER_MESSAGE = "thrown by the test's mutation dispatcher";
    private static final String QUEUE_LISTENER_MESSAGE = "thrown by the test's approval-queue listener";

    private final ThrowingTransport transport = new ThrowingTransport();
    private final McpServerManager manager = new McpServerManager(transport);
    private final List<String> transitions = new CopyOnWriteArrayList<>();

    @After
    public void tearDown() {
        try {
            manager.stop();
        } finally {
            transport.stopServer();
        }
    }

    @Test(timeout = 30_000)
    public void shouldReportErrorAndAllowARetry_whenStartThrowsAnAssertionError() {
        assertErrorReportedAndRetryAttempted(new AssertionError(MESSAGE));
    }

    @Test(timeout = 30_000)
    public void shouldReportErrorAndAllowARetry_whenStartThrowsAStackOverflowError() {
        assertErrorReportedAndRetryAttempted(new StackOverflowError(MESSAGE));
    }

    @Test(timeout = 30_000)
    public void shouldStopTheServer_whenAnErrorArrivesAfterItStarted() throws Exception {
        AssertionError error = new AssertionError(MESSAGE);
        Set<String> poolBefore = TransportConfigTest.liveJettyPoolThreads();
        transport.throwOnFirstServerRead = error;

        assertSame(error, startExpectingAnError());

        assertEquals(ServerState.ERROR, manager.getState());
        assertEquals("Server initialization failed: AssertionError: " + MESSAGE, manager.getLastErrorMessage());
        assertFalse(transport.isRunning());
        assertEquals(0, transport.getPort());
        // Resources are registered before the server starts, so this shows they were cleared. Tools are
        // registered only after the point the Error comes from, so their count would say nothing.
        assertEquals(0, manager.getResourceRegistry().getResourceCount());
        TransportConfigTest.assertNoJettyPoolThreadsLeftSince(poolBefore);
    }

    // A clean-up whose stop is given up on: the message says so until that stop finishes.

    /** What a failed start adds to its message while the server it could not stop may still be running. */
    private static final String STILL_RUNNING = " The server did not finish stopping and may still be running.";

    @Test(timeout = 30_000)
    public void shouldSayTheServerMayStillBeRunningUntilItStops_whenTheCleanupOfALinkageErrorGivesUpOnTheStop()
            throws Exception {
        assertStillRunningUntilTheStopEnds(new NoClassDefFoundError(MESSAGE));
    }

    @Test(timeout = 30_000)
    public void shouldSayTheServerMayStillBeRunningUntilItStops_whenTheCleanupOfAnErrorGivesUpOnTheStop()
            throws Exception {
        assertStillRunningUntilTheStopEnds(new AssertionError(MESSAGE));
    }

    private void assertStillRunningUntilTheStopEnds(Error error) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThrowingTransport heldTransport = new ThrowingTransport(1_000, TransportConfigTest.heldUntil(release));
        McpServerManager heldManager = new McpServerManager(heldTransport);
        Set<String> poolBefore = TransportConfigTest.liveJettyPoolThreads();
        heldTransport.throwOnFirstServerRead = error;
        String failure = "Server initialization failed: " + error.getClass().getSimpleName() + ": " + MESSAGE;
        try {
            try {
                heldManager.start(0, TEST_BIND_ADDRESS);
            } catch (Error thrown) {
                assertSame(error, thrown);
            }

            assertEquals(ServerState.ERROR, heldManager.getState());
            assertEquals(failure + STILL_RUNNING, heldManager.getLastErrorMessage());
            assertTrue(heldTransport.isRunning());

            release.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!failure.equals(heldManager.getLastErrorMessage()) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals("once the stop has finished, the start's own failure alone",
                    failure, heldManager.getLastErrorMessage());
            assertEquals("the start still failed", ServerState.ERROR, heldManager.getState());
            assertFalse(heldTransport.isRunning());
            assertEquals(0, heldTransport.getPort());
            TransportConfigTest.assertNoJettyPoolThreadsLeftSince(poolBefore);
        } finally {
            release.countDown();
            try {
                heldManager.stop();
            } finally {
                heldTransport.stopServer();
            }
        }
    }

    // The existing LinkageError branch, reached through the same two points: shows that each point
    // is inside the start's try, so what differs in the cases above is the kind of Error alone.

    @Test(timeout = 30_000)
    public void shouldReportErrorWithoutThrowing_whenStartThrowsALinkageError() {
        transport.throwOnStart = new NoClassDefFoundError(MESSAGE);

        manager.start(0, TEST_BIND_ADDRESS);

        assertEquals(ServerState.ERROR, manager.getState());
        assertEquals("Server initialization failed: NoClassDefFoundError: " + MESSAGE,
                manager.getLastErrorMessage());
    }

    @Test(timeout = 30_000)
    public void shouldStopTheServerWithoutThrowing_whenALinkageErrorArrivesAfterItStarted() {
        transport.throwOnFirstServerRead = new NoClassDefFoundError(MESSAGE);

        manager.start(0, TEST_BIND_ADDRESS);

        assertEquals(ServerState.ERROR, manager.getState());
        assertEquals("Server initialization failed: NoClassDefFoundError: " + MESSAGE,
                manager.getLastErrorMessage());
        assertFalse(transport.isRunning());
    }

    // A throw from inside the clean-up: the server must still be stopped, and what leaves the start is
    // the start's own failure unless the clean-up threw an Error.

    @Test(timeout = 30_000)
    public void shouldStopTheServerWithoutThrowing_whenTheSessionManagerThrowsDuringCleanupOfALinkageError()
            throws Exception {
        Set<String> poolBefore = TransportConfigTest.liveJettyPoolThreads();
        transport.throwOnFirstServerRead = new NoClassDefFoundError(MESSAGE);
        ThrowingSessionManager sessionManager =
                putThrowingSessionManager(new IllegalStateException(SESSION_MANAGER_MESSAGE));
        AtomicInteger accessorDisposeCalls = new AtomicInteger();
        putIntoManager("modelAccessor", recordingAccessor(accessorDisposeCalls));

        manager.start(0, TEST_BIND_ADDRESS);

        assertEquals(1, sessionManager.disposeCalls.get());
        // The step after the one that threw still ran.
        assertEquals(1, accessorDisposeCalls.get());
        assertStoppedAndReleased("NoClassDefFoundError", poolBefore);
    }

    @Test(timeout = 30_000)
    public void shouldStopTheServerAndRethrowTheStartsError_whenTheSessionManagerThrowsDuringCleanupOfAnError()
            throws Exception {
        Set<String> poolBefore = TransportConfigTest.liveJettyPoolThreads();
        AssertionError error = new AssertionError(MESSAGE);
        transport.throwOnFirstServerRead = error;
        ThrowingSessionManager sessionManager =
                putThrowingSessionManager(new IllegalStateException(SESSION_MANAGER_MESSAGE));
        AtomicInteger accessorDisposeCalls = new AtomicInteger();
        putIntoManager("modelAccessor", recordingAccessor(accessorDisposeCalls));

        assertSame(error, startExpectingAnError());

        assertEquals(1, sessionManager.disposeCalls.get());
        assertEquals(1, accessorDisposeCalls.get());
        assertStoppedAndReleased("AssertionError", poolBefore);
    }

    @Test(timeout = 30_000)
    public void shouldStopTheServerAndThrowTheSessionManagersError_whenItThrowsAnErrorDuringCleanup()
            throws Exception {
        Set<String> poolBefore = TransportConfigTest.liveJettyPoolThreads();
        transport.throwOnFirstServerRead = new NoClassDefFoundError(MESSAGE);
        AssertionError sessionManagerError = new AssertionError(SESSION_MANAGER_MESSAGE);
        ThrowingSessionManager sessionManager = putThrowingSessionManager(sessionManagerError);

        assertSame(sessionManagerError, startExpectingAnError());

        assertEquals(1, sessionManager.disposeCalls.get());
        assertStoppedAndReleased("NoClassDefFoundError", poolBefore);
    }

    @Test(timeout = 30_000)
    public void shouldStopTheServerAndThrowTheListenersError_whenAListenerThrowsAtErrorAfterALinkageError()
            throws Exception {
        Set<String> poolBefore = TransportConfigTest.liveJettyPoolThreads();
        transport.throwOnFirstServerRead = new NoClassDefFoundError(MESSAGE);
        AssertionError listenerError = new AssertionError(LISTENER_MESSAGE);
        addListenerThrowingAtError(listenerError);

        assertSame(listenerError, startExpectingAnError());

        assertStoppedAndReleased("NoClassDefFoundError", poolBefore);
    }

    @Test(timeout = 30_000)
    public void shouldStopTheServerAndThrowTheListenersError_whenAListenerThrowsAtErrorAfterAnError()
            throws Exception {
        Set<String> poolBefore = TransportConfigTest.liveJettyPoolThreads();
        transport.throwOnFirstServerRead = new AssertionError(MESSAGE);
        AssertionError listenerError = new AssertionError(LISTENER_MESSAGE);
        addListenerThrowingAtError(listenerError);

        // The listener's Error leaves in place of the start's own.
        assertSame(listenerError, startExpectingAnError());

        assertStoppedAndReleased("AssertionError", poolBefore);
    }

    // A throw from one call inside disposing the session manager or the model accessor: the dispose still
    // runs and the manager lets go of the object. The start fails before Jetty starts, so its clean-up has
    // no server to stop. The manager has no getter for its session manager, so those cases call stop()
    // once more: from ERROR it runs the clean-up again, and a session manager still held is disposed a
    // second time. The accessor cases read the public getters instead.

    @Test(timeout = 30_000)
    public void shouldDisposeTheSessionManagerAndLetGoOfIt_whenRemovingItsListenerThrows() throws Exception {
        ThrowingSessionManager sessionManager = putThrowingSessionManager(null);
        ArmedAccessor accessor = putArmedAccessor(null);
        accessor.onRemoveSessionManager.set(new IllegalStateException(ACCESSOR_MESSAGE));

        failAStartThatLeavesNothingRunning();

        assertEquals(1, accessor.removeSessionManagerCalls.get());
        assertEquals(1, sessionManager.disposeCalls.get());
        manager.stop();
        assertEquals(1, sessionManager.disposeCalls.get());
    }

    @Test(timeout = 30_000)
    public void shouldLetGoOfTheSessionManager_whenItsDisposeThrows() throws Exception {
        ThrowingSessionManager sessionManager =
                putThrowingSessionManager(new IllegalStateException(SESSION_MANAGER_MESSAGE));

        failAStartThatLeavesNothingRunning();

        assertEquals(1, sessionManager.disposeCalls.get());
        manager.stop();
        assertEquals(1, sessionManager.disposeCalls.get());
    }

    @Test(timeout = 30_000)
    public void shouldDisposeTheSessionManagerAndThrowTheError_whenRemovingItsListenerThrowsAnError()
            throws Exception {
        ThrowingSessionManager sessionManager = putThrowingSessionManager(null);
        ArmedAccessor accessor = putArmedAccessor(null);
        AssertionError accessorError = new AssertionError(ACCESSOR_MESSAGE);
        accessor.onRemoveSessionManager.set(accessorError);
        transport.throwOnStart = new NoClassDefFoundError(MESSAGE);

        assertSame(accessorError, startExpectingAnError());

        assertEquals(1, accessor.removeSessionManagerCalls.get());
        assertEquals(1, sessionManager.disposeCalls.get());
    }

    @Test(timeout = 30_000)
    public void shouldDisposeTheModelAccessorAndLetGoOfIt_whenRemovingTheManagersListenerThrows() throws Exception {
        ThrowingDispatcher dispatcher = new ThrowingDispatcher(null);
        ArmedAccessor accessor = putArmedAccessor(dispatcher);
        accessor.onRemoveManager.set(new IllegalStateException(ACCESSOR_MESSAGE));

        failAStartThatLeavesNothingRunning();

        assertEquals(1, accessor.removeManagerCalls.get());
        // Clearing the dispatcher's sessions comes after the removal in the same try, so it is skipped.
        assertEquals(0, dispatcher.clearCalls.get());
        assertAccessorDisposedAndReleased(accessor);
    }

    @Test(timeout = 30_000)
    public void shouldDisposeTheModelAccessorAndLetGoOfIt_whenClearingTheDispatchersSessionsThrows()
            throws Exception {
        ThrowingDispatcher dispatcher = new ThrowingDispatcher(new IllegalStateException(DISPATCHER_MESSAGE));
        ArmedAccessor accessor = putArmedAccessor(dispatcher);

        failAStartThatLeavesNothingRunning();

        assertEquals(1, dispatcher.clearCalls.get());
        assertAccessorDisposedAndReleased(accessor);
    }

    @Test(timeout = 30_000)
    public void shouldDisposeTheModelAccessorAndThrowTheError_whenAnApprovalQueueListenerThrowsAnError()
            throws Exception {
        // The dispatcher catches a RuntimeException from a queue listener, so only an Error reaches the manager.
        MutationDispatcher dispatcher = new MutationDispatcher(() -> null);
        AssertionError listenerError = new AssertionError(QUEUE_LISTENER_MESSAGE);
        AtomicInteger listenerCalls = new AtomicInteger();
        dispatcher.addQueueListener(() -> {
            if (listenerCalls.getAndIncrement() == 0) {
                throw listenerError;
            }
        });
        ArmedAccessor accessor = putArmedAccessor(dispatcher);
        transport.throwOnStart = new NoClassDefFoundError(MESSAGE);

        assertSame(listenerError, startExpectingAnError());

        assertEquals(1, listenerCalls.get());
        assertAccessorDisposedAndReleased(accessor);
    }

    @Test(timeout = 30_000)
    public void shouldLetGoOfTheModelAccessor_whenItsDisposeThrows() throws Exception {
        ArmedAccessor accessor = putArmedAccessor(null);
        accessor.onDispose.set(new IllegalStateException(ACCESSOR_MESSAGE));

        failAStartThatLeavesNothingRunning();

        assertAccessorDisposedAndReleased(accessor);
    }

    // When two calls in one dispose throw, the later throw leaves and the earlier is lost. Here the earlier is
    // an Error and the later an exception, so the clean-up logs the exception and nothing leaves the start.

    @Test(timeout = 30_000)
    public void shouldLoseTheEarlierError_whenRemovingTheSessionManagersListenerAndItsDisposeBothThrow()
            throws Exception {
        ThrowingSessionManager sessionManager =
                putThrowingSessionManager(new IllegalStateException(SESSION_MANAGER_MESSAGE));
        ArmedAccessor accessor = putArmedAccessor(null);
        accessor.onRemoveSessionManager.set(new AssertionError(ACCESSOR_MESSAGE));

        failAStartThatLeavesNothingRunning();

        assertEquals(1, accessor.removeSessionManagerCalls.get());
        assertEquals(1, sessionManager.disposeCalls.get());
        // An exception, not an Error, left the step, so the accessor's step still ran.
        assertAccessorDisposedAndReleased(accessor);
    }

    @Test(timeout = 30_000)
    public void shouldLoseTheEarlierError_whenRemovingTheManagersListenerAndTheAccessorsDisposeBothThrow()
            throws Exception {
        ArmedAccessor accessor = putArmedAccessor(null);
        accessor.onRemoveManager.set(new AssertionError(ACCESSOR_MESSAGE));
        accessor.onDispose.set(new IllegalStateException(ACCESSOR_MESSAGE));

        failAStartThatLeavesNothingRunning();

        assertEquals(1, accessor.removeManagerCalls.get());
        assertAccessorDisposedAndReleased(accessor);
    }

    // stop() runs the same clean-up before it stops the server. The manager is put in ERROR by a failed
    // start that leaves nothing running, and the server is then started directly on the transport.

    @Test(timeout = 30_000)
    public void shouldStopTheServerWithoutThrowing_whenTheSessionManagerThrowsDuringStop() throws Exception {
        Set<String> poolBefore = TransportConfigTest.liveJettyPoolThreads();
        startTheServerBehindAFailedStart();
        ThrowingSessionManager sessionManager =
                putThrowingSessionManager(new IllegalStateException(SESSION_MANAGER_MESSAGE));
        AtomicInteger accessorDisposeCalls = new AtomicInteger();
        putIntoManager("modelAccessor", recordingAccessor(accessorDisposeCalls));

        manager.stop();

        assertEquals(1, sessionManager.disposeCalls.get());
        assertEquals(1, accessorDisposeCalls.get());
        assertEquals(ServerState.STOPPED, manager.getState());
        assertFalse(transport.isRunning());
        TransportConfigTest.assertNoJettyPoolThreadsLeftSince(poolBefore);
    }

    @Test(timeout = 30_000)
    public void shouldDisposeTheModelAccessorAndLetGoOfIt_whenRemovingTheManagersListenerThrowsDuringStop()
            throws Exception {
        Set<String> poolBefore = TransportConfigTest.liveJettyPoolThreads();
        startTheServerBehindAFailedStart();
        ArmedAccessor accessor = putArmedAccessor(null);
        accessor.onRemoveManager.set(new IllegalStateException(ACCESSOR_MESSAGE));

        manager.stop();

        assertEquals(1, accessor.removeManagerCalls.get());
        assertAccessorDisposedAndReleased(accessor);
        assertEquals(ServerState.STOPPED, manager.getState());
        assertFalse(transport.isRunning());
        TransportConfigTest.assertNoJettyPoolThreadsLeftSince(poolBefore);
    }

    @Test(timeout = 30_000)
    public void shouldStopTheServerAndThrowTheSessionManagersError_whenItThrowsAnErrorDuringStop()
            throws Exception {
        Set<String> poolBefore = TransportConfigTest.liveJettyPoolThreads();
        startTheServerBehindAFailedStart();
        AssertionError sessionManagerError = new AssertionError(SESSION_MANAGER_MESSAGE);
        ThrowingSessionManager sessionManager = putThrowingSessionManager(sessionManagerError);

        try {
            manager.stop();
            fail("expected the stop to throw the session manager's Error");
        } catch (AssertionError e) {
            assertSame(sessionManagerError, e);
        }

        assertEquals(1, sessionManager.disposeCalls.get());
        assertEquals(ServerState.STOPPED, manager.getState());
        assertFalse(transport.isRunning());
        TransportConfigTest.assertNoJettyPoolThreadsLeftSince(poolBefore);
    }

    private void assertStoppedAndReleased(String startFailure, Set<String> poolBefore) throws InterruptedException {
        assertEquals(ServerState.ERROR, manager.getState());
        assertEquals("Server initialization failed: " + startFailure + ": " + MESSAGE, manager.getLastErrorMessage());
        assertFalse(transport.isRunning());
        assertEquals(0, transport.getPort());
        assertEquals(0, manager.getResourceRegistry().getResourceCount());
        TransportConfigTest.assertNoJettyPoolThreadsLeftSince(poolBefore);
    }

    private void startTheServerBehindAFailedStart() throws ServerStartException {
        transport.throwOnStart = new NoClassDefFoundError(MESSAGE);
        manager.start(0, TEST_BIND_ADDRESS);
        assertEquals(ServerState.ERROR, manager.getState());
        transport.startServer(0, TEST_BIND_ADDRESS);
        assertTrue(transport.isRunning());
    }

    private ThrowingSessionManager putThrowingSessionManager(Throwable thrown) throws ReflectiveOperationException {
        ThrowingSessionManager sessionManager = new ThrowingSessionManager(thrown);
        putIntoManager("sessionManager", sessionManager);
        return sessionManager;
    }

    /** Writes {@code value} into the manager's private field {@code name}. */
    private void putIntoManager(String name, Object value) throws ReflectiveOperationException {
        Field field = McpServerManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    /** Throws {@code error} the first time a listener is told of {@link ServerState#ERROR}, then never again. */
    private void addListenerThrowingAtError(Error error) {
        AtomicInteger thrown = new AtomicInteger();
        manager.addStateListener((oldState, newState) -> {
            if (newState == ServerState.ERROR && thrown.getAndIncrement() == 0) {
                throw error;
            }
        });
    }

    /** A model accessor that counts {@code dispose()} into {@code disposeCalls}; see {@link ArmedAccessor}. */
    private ArchiModelAccessor recordingAccessor(AtomicInteger disposeCalls) {
        return new ArmedAccessor(disposeCalls).proxy();
    }

    /**
     * Writes an {@link ArmedAccessor} into the manager, with an approval service on {@code dispatcher} (or on
     * a dispatcher of its own when that is null) so that whether the manager lets go of it can be seen.
     */
    private ArmedAccessor putArmedAccessor(MutationDispatcher dispatcher) throws ReflectiveOperationException {
        ArmedAccessor accessor = new ArmedAccessor(new AtomicInteger());
        accessor.dispatcher = dispatcher;
        putIntoManager("modelAccessor", accessor.proxy());
        putIntoManager("approvalService",
                new ApprovalService(dispatcher != null ? dispatcher : new MutationDispatcher(() -> null)));
        assertNotNull(manager.getModelAccessor());
        assertNotNull(manager.getApprovalService());
        return accessor;
    }

    private void assertAccessorDisposedAndReleased(ArmedAccessor accessor) {
        assertEquals(1, accessor.disposeCalls.get());
        assertNull(manager.getModelAccessor());
        assertNull(manager.getApprovalService());
    }

    /** Fails a start before Jetty starts, with an error the start handles without throwing. */
    private void failAStartThatLeavesNothingRunning() {
        transport.throwOnStart = new NoClassDefFoundError(MESSAGE);
        manager.start(0, TEST_BIND_ADDRESS);
        assertEquals(ServerState.ERROR, manager.getState());
        assertEquals("Server initialization failed: NoClassDefFoundError: " + MESSAGE, manager.getLastErrorMessage());
    }

    /**
     * A model accessor that counts {@code dispose()}, and {@code removeModelChangeListener} given a session
     * manager or given the manager; each of those three throws what a case arms, once, after it is counted.
     * {@code getMutationDispatcher()} answers the case's dispatcher, {@code equals}, {@code hashCode} and
     * {@code toString} answer as for any object, and every other call answers nothing.
     */
    private final class ArmedAccessor {

        final AtomicInteger disposeCalls;
        final AtomicInteger removeSessionManagerCalls = new AtomicInteger();
        final AtomicInteger removeManagerCalls = new AtomicInteger();
        /** Thrown by {@code removeModelChangeListener} when it is given a session manager. */
        final AtomicReference<Throwable> onRemoveSessionManager = new AtomicReference<>();
        /** Thrown by {@code removeModelChangeListener} when it is given the manager. */
        final AtomicReference<Throwable> onRemoveManager = new AtomicReference<>();
        /** Thrown by {@code dispose()}. */
        final AtomicReference<Throwable> onDispose = new AtomicReference<>();
        /** Answered by {@code getMutationDispatcher()}; null unless a case sets one. */
        volatile MutationDispatcher dispatcher;

        ArmedAccessor(AtomicInteger disposeCalls) {
            this.disposeCalls = disposeCalls;
        }

        ArchiModelAccessor proxy() {
            return (ArchiModelAccessor) Proxy.newProxyInstance(ArchiModelAccessor.class.getClassLoader(),
                    new Class<?>[] { ArchiModelAccessor.class }, (proxy, method, args) -> {
                        Throwable armed = null;
                        switch (method.getName()) {
                            case "dispose" -> {
                                disposeCalls.incrementAndGet();
                                armed = onDispose.getAndSet(null);
                            }
                            case "removeModelChangeListener" -> {
                                if (args[0] instanceof SessionManager) {
                                    removeSessionManagerCalls.incrementAndGet();
                                    armed = onRemoveSessionManager.getAndSet(null);
                                } else if (args[0] == manager) {
                                    removeManagerCalls.incrementAndGet();
                                    armed = onRemoveManager.getAndSet(null);
                                }
                            }
                            case "getMutationDispatcher" -> {
                                return dispatcher;
                            }
                            case "equals" -> {
                                return proxy == args[0];
                            }
                            case "hashCode" -> {
                                return System.identityHashCode(proxy);
                            }
                            case "toString" -> {
                                return "the test's model accessor";
                            }
                            default -> {
                            }
                        }
                        if (armed != null) {
                            throw armed;
                        }
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) {
                            return false;
                        }
                        return type.isPrimitive() && type != void.class ? 0 : null;
                    });
        }
    }

    /** A mutation dispatcher whose first {@code clearAllSessions()} throws the armed exception. */
    private static final class ThrowingDispatcher extends MutationDispatcher {

        final AtomicInteger clearCalls = new AtomicInteger();
        private volatile RuntimeException thrown;

        ThrowingDispatcher(RuntimeException thrown) {
            super(() -> null);
            this.thrown = thrown;
        }

        @Override
        public void clearAllSessions() {
            clearCalls.incrementAndGet();
            RuntimeException armed = thrown;
            if (armed == null) {
                super.clearAllSessions();
                return;
            }
            thrown = null;
            throw armed;
        }
    }

    private void assertErrorReportedAndRetryAttempted(Error error) {
        manager.addStateListener((oldState, newState) -> transitions.add(oldState + "->" + newState));
        transport.throwOnStart = error;

        assertSame(error, startExpectingAnError());

        assertEquals(ServerState.ERROR, manager.getState());
        assertEquals("Server initialization failed: " + error.getClass().getSimpleName() + ": " + MESSAGE,
                manager.getLastErrorMessage());
        assertEquals(List.of("STOPPED->STARTING", "STARTING->ERROR"), transitions);

        // The retry runs the real start, which headlessly fails later for want of the platform; asserted
        // is that it was attempted rather than ignored, and that it ended settled: not STARTING, and
        // with a server running exactly when the state says RUNNING.
        manager.start(0, TEST_BIND_ADDRESS);

        assertEquals(2, transport.startCalls.get());
        assertEquals("ERROR->STARTING", transitions.get(2));
        assertNotEquals(ServerState.STARTING, manager.getState());
        assertEquals(manager.getState() == ServerState.RUNNING, transport.isRunning());
    }

    private Error startExpectingAnError() {
        try {
            manager.start(0, TEST_BIND_ADDRESS);
        } catch (Error e) {
            return e;
        }
        fail("expected the start to throw the test's Error");
        return null;
    }

    /** A transport that throws an armed {@link Error} once, then behaves as the real one. */
    private static final class ThrowingTransport extends TransportConfig {

        /** Thrown by {@code startServer} instead of starting. */
        volatile Error throwOnStart;
        /** Thrown by the first read of the Streamable-HTTP MCP server, after Jetty has started. */
        volatile Error throwOnFirstServerRead;
        final AtomicInteger startCalls = new AtomicInteger();

        ThrowingTransport() {
            super(() -> null);
        }

        ThrowingTransport(long stopTimeoutMs, JettyStop jettyStop) {
            super(() -> null, stopTimeoutMs, jettyStop);
        }

        @Override
        public void startServer(int port, String bindAddress) throws ServerStartException {
            startCalls.incrementAndGet();
            Error error = throwOnStart;
            if (error != null) {
                throwOnStart = null;
                throw error;
            }
            super.startServer(port, bindAddress);
        }

        @Override
        public McpSyncServer getStreamableMcpServer() {
            Error error = throwOnFirstServerRead;
            if (error != null) {
                throwOnFirstServerRead = null;
                throw error;
            }
            return super.getStreamableMcpServer();
        }
    }

    /**
     * A session manager whose first {@code dispose()} throws the armed exception or {@link Error}; with
     * nothing armed it only counts.
     */
    private static final class ThrowingSessionManager extends SessionManager {

        final AtomicInteger disposeCalls = new AtomicInteger();
        private volatile Throwable thrown;

        ThrowingSessionManager(Throwable thrown) {
            super(Set.of(), Set.of());
            this.thrown = thrown;
        }

        @Override
        public void dispose() {
            disposeCalls.incrementAndGet();
            Throwable armed = thrown;
            if (armed == null) {
                super.dispose();
                return;
            }
            thrown = null;
            if (armed instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) armed;
        }
    }
}
