package net.vheerden.archi.mcp.server;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.McpPlugin;

/**
 * Tests for {@link TransportConfig}.
 *
 * <p>Every server is started on port 0 and the bound port is read back with
 * {@link TransportConfig#getPort()}, so no test depends on a particular port being free.
 * Tests that require actual server start/stop exercise the full Jetty + MCP SDK wiring.</p>
 */
public class TransportConfigTest {

    private static final String TEST_BIND_ADDRESS = "127.0.0.1";

    private TransportConfig transportConfig = new TransportConfig();

    @After
    public void tearDown() {
        transportConfig.stopServer();
    }

    @Test
    public void shouldReturnFalse_whenServerNotStarted() {
        assertFalse(transportConfig.isRunning());
    }

    @Test
    public void shouldReturnZeroPort_whenServerNotStarted() {
        assertEquals(0, transportConfig.getPort());
    }

    @Test
    public void shouldReportTheBoundPort_whenAnEphemeralPortIsRequested() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
        int port = transportConfig.getPort();
        assertTrue("port 0 must be reported as the port actually bound, was " + port, port > 0);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(TEST_BIND_ADDRESS, port), 3000);
        }

        transportConfig.stopServer();
        assertEquals(0, transportConfig.getPort());
    }

    @Test
    public void shouldKeepTheRequestedPort_whenANonZeroPortIsRequested() {
        assertEquals(8080, TransportConfig.effectivePort(8080, 51234));
    }

    @Test
    public void shouldUseTheBoundPort_whenPortZeroIsRequested() {
        assertEquals(51234, TransportConfig.effectivePort(0, 51234));
    }

    @Test
    public void shouldReturnTrue_whenServerStarted() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
    }

    @Test
    public void shouldReportAListeningPort_whenServerStarted() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.getPort() > 0);
    }

    @Test
    public void shouldCreateServerWithConfiguredBindAddress_whenValidAddressProvided() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
        assertEquals(TEST_BIND_ADDRESS, transportConfig.getBindAddress());
    }

    @Test
    public void shouldKeepTheBindsClassification_whileRunning_andForgetItOnStop() throws Exception {
        assertNull(transportConfig.getBindExposure());
        transportConfig.startServer(0, "127.0.0.1");

        assertEquals(BindExposure.LOOPBACK, transportConfig.getBindExposure());
        transportConfig.stopServer();
        assertNull(transportConfig.getBindExposure());
    }

    @Test
    public void shouldStillRefuseToStart_onAWhitespaceBind() {
        try {
            transportConfig.startServer(0, "  ");
            fail("Expected ServerStartException for a whitespace bind address");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_BIND_ADDRESS, e.getErrorCode());
            assertNull(transportConfig.getBindExposure());
        }
    }

    @Test
    public void shouldHandlePortConflict_whenPortAlreadyInUse() throws Exception {
        // Bind the port first to create a conflict
        // The blocker stays open across startServer, so the port is occupied for the whole call.
        try (ServerSocket blocker = new ServerSocket(0, 1,
                java.net.InetAddress.getByName(TEST_BIND_ADDRESS))) {

            try {
                transportConfig.startServer(blocker.getLocalPort(), TEST_BIND_ADDRESS);
                fail("Expected ServerStartException for port conflict");
            } catch (ServerStartException e) {
                assertEquals(ServerStartException.PORT_IN_USE, e.getErrorCode());
                assertFalse(transportConfig.isRunning());
            }
        }
    }

    @Test
    public void shouldStopCleanly_whenServerIsRunning() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        assertTrue(transportConfig.isRunning());

        transportConfig.stopServer();

        assertFalse(transportConfig.isRunning());
        assertEquals(0, transportConfig.getPort());
    }

    /** Bound on a stop under test: long enough for a normal stop on a loaded machine, short for a test. */
    private static final long TEST_STOP_TIMEOUT_MS = 2_000;

    /**
     * A stop that sticks inside Jetty's own stop, with the server STOPPING, until {@code release}
     * opens: a component added to the server will not finish stopping before then.
     */
    static TransportConfig.JettyStop heldUntil(CountDownLatch release) {
        return server -> {
            AbstractLifeCycle sticks = new AbstractLifeCycle() {
                @Override
                protected void doStop() throws Exception {
                    release.await();
                }
            };
            sticks.start(); // the server only stops components that are running
            server.addBean(sticks, true);
            server.stop();
            server.join();
        };
    }

    @Test(timeout = 15_000)
    public void shouldGiveUpOnTheStop_whenJettyDoesNotStopInTime() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release));
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS);
            int port = transportConfig.getPort();

            long started = System.nanoTime();
            transportConfig.stopServer();
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue("stopServer returned after " + tookMs + " ms",
                    tookMs >= TEST_STOP_TIMEOUT_MS && tookMs < TEST_STOP_TIMEOUT_MS + 3_000);
            assertTrue("a stop that was given up on must not report the server stopped",
                    transportConfig.isRunning());
            assertEquals("and it still names the port it may hold", port, transportConfig.getPort());
            String threads = TransportConfig.jettyThreads();
            assertTrue("the log names the stuck stop and what it waits on:\n" + threads,
                    threads.contains("\"archi-mcp-jetty-stop\"") && threads.contains("CountDownLatch"));
            assertTrue("with every frame, not the first eight:\n" + threads,
                    threads.contains("java.util.concurrent.FutureTask.run"));
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 15_000)
    public void shouldStopOnARetry_whenAnEarlierStopWasGivenUpOn() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release));
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS);
            transportConfig.stopServer();
            assertTrue(transportConfig.isRunning());
        } finally {
            release.countDown();
        }

        transportConfig.stopServer();

        assertFalse(transportConfig.isRunning());
        assertEquals(0, transportConfig.getPort());
    }

    @Test(timeout = 20_000)
    public void shouldWaitOnTheSameStop_whenStoppingAgainWhileTheFirstIsStuck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release));
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS);
            transportConfig.stopServer();
            transportConfig.stopServer();

            // a second stop thread would wait on Jetty's own lifecycle lock, not the latch: count them all
            long stopThreads = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().equals("archi-mcp-jetty-stop") && t.isAlive())
                    .count();
            assertEquals("one stop, waited on twice", 1, stopThreads);
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 20_000)
    public void shouldRefuseToStart_whenAGivenUpStopHasStillNotFinished() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release));
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS);
            transportConfig.stopServer();

            try {
                transportConfig.startServer(0, TEST_BIND_ADDRESS);
                fail("a start over a server that may still be running must fail, not be ignored");
            } catch (ServerStartException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("has not finished stopping"));
            }
            assertTrue(transportConfig.isRunning());
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 20_000)
    public void shouldStartAgain_whenAGivenUpStopHasFinishedSince() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release));
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        transportConfig.stopServer();
        release.countDown();

        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
        assertTrue(transportConfig.getPort() > 0);
    }

    // ---- What a stop logs ----
    // Without OSGi the class logger discards everything, so these record through the logger the
    // transport is given.

    /** One call on a recorded logger: the method (its level) and its arguments, varargs flattened. */
    record LogCall(String method, List<Object> args) {

        String format() {
            return args.isEmpty() ? "" : String.valueOf(args.get(0));
        }
    }

    /** A logger that records every logging call into {@code calls} and reports every level enabled. */
    static Logger recordingLogger(List<LogCall> calls) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(), new Class<?>[] {Logger.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("is") && method.getReturnType() == boolean.class) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "recording";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "recording logger";
                    }
                    List<Object> flat = new ArrayList<>();
                    for (Object arg : args == null ? new Object[0] : args) {
                        if (arg instanceof Object[] varargs) {
                            flat.addAll(Arrays.asList(varargs));
                        } else {
                            flat.add(arg);
                        }
                    }
                    calls.add(new LogCall(name, flat));
                    return null;
                });
    }

    static List<LogCall> callsAt(List<LogCall> calls, String level) {
        return calls.stream().filter(call -> call.method().equals(level)).toList();
    }

    @Test(timeout = 15_000)
    public void shouldLogOneErrorNamingTheBoundThePortAndTheThreads_whenAStopIsGivenUpOn() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<LogCall> calls = new CopyOnWriteArrayList<>();
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release),
                McpSyncServer::close, recordingLogger(calls));
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS);
            int port = transportConfig.getPort();
            calls.clear();

            transportConfig.stopServer();

            List<LogCall> errors = callsAt(calls, "error");
            assertEquals("one ERROR line: " + calls, 1, errors.size());
            LogCall error = errors.get(0);
            assertTrue(error.format(), error.format().contains("did not finish stopping"));
            assertTrue("it names the bound: " + error.args(), error.args().contains(TEST_STOP_TIMEOUT_MS));
            assertTrue("and the port the server may still hold: " + error.args(), error.args().contains(port));
            assertTrue("and the threads: " + error.args(), error.args().stream()
                    .anyMatch(arg -> arg instanceof String text && text.contains("archi-mcp-jetty-stop")));
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 15_000)
    public void shouldLogTheStopAndNoError_whenTheStopFinishesInTime() throws Exception {
        List<LogCall> calls = new CopyOnWriteArrayList<>();
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS,
                server -> {
                    server.stop();
                    server.join();
                },
                McpSyncServer::close, recordingLogger(calls));
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        int port = transportConfig.getPort();
        calls.clear();

        transportConfig.stopServer();

        assertEquals("no ERROR line: " + calls, List.of(), callsAt(calls, "error"));
        List<LogCall> infos = callsAt(calls, "info");
        assertEquals("one INFO line: " + calls, 1, infos.size());
        assertTrue(infos.get(0).format(), infos.get(0).format().contains("MCP Server stopped (was on port"));
        assertEquals(List.of(port), infos.get(0).args().subList(1, infos.get(0).args().size()));
    }

    // ---- Releasing a stop given up on once it has finished, without waiting ----

    @Test(timeout = 15_000)
    public void shouldReleaseTheServer_whenTheStopGivenUpOnHasFinishedSince() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<LogCall> calls = new CopyOnWriteArrayList<>();
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release),
                McpSyncServer::close, recordingLogger(calls));
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<Boolean> released = new AtomicReference<>();
        // released from the listener itself, on the stop's thread: told before the stop counts as ended,
        // the release would find it still running and do nothing
        transportConfig.addStopEndedListener(() -> {
            released.set(transportConfig.releaseFinishedStop());
            ended.countDown();
        });
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS);
            transportConfig.stopServer();
            assertTrue(transportConfig.isRunning());
            calls.clear();
        } finally {
            release.countDown();
        }
        assertTrue("the stop says it has ended", ended.await(5, TimeUnit.SECONDS));

        assertEquals(Boolean.TRUE, released.get());
        assertFalse(transportConfig.isRunning());
        assertEquals(0, transportConfig.getPort());
        assertNull(transportConfig.getBindAddress());
        assertNull(transportConfig.getStreamableMcpServer());
        assertNull(transportConfig.getSseMcpServer());
        assertEquals("logged as any stop is: " + calls, 1, callsAt(calls, "info").size());
        assertTrue(callsAt(calls, "info").get(0).format().contains("MCP Server stopped (was on port"));
        assertEquals(List.of(), callsAt(calls, "error"));
    }

    @Test(timeout = 15_000)
    public void shouldReleaseNothing_whenNoStopWasGivenUpOn() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        int port = transportConfig.getPort();

        assertFalse(transportConfig.releaseFinishedStop());

        assertTrue(transportConfig.isRunning());
        assertEquals(port, transportConfig.getPort());
    }

    @Test(timeout = 15_000)
    public void shouldReturnAtOnceAndReleaseNothing_whenTheStopGivenUpOnIsStillRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release));
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS);
            int port = transportConfig.getPort();
            transportConfig.stopServer();

            long started = System.nanoTime();
            boolean released = transportConfig.releaseFinishedStop();
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertFalse(released);
            assertTrue("it must not wait for the stop; took " + tookMs + " ms of a " + TEST_STOP_TIMEOUT_MS
                    + " ms bound", tookMs < TEST_STOP_TIMEOUT_MS / 4);
            assertTrue(transportConfig.isRunning());
            assertEquals(port, transportConfig.getPort());
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 20_000)
    public void shouldReleaseNothing_whenALaterStartAlreadyPickedTheFinishedStopUp() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release));
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        transportConfig.stopServer();
        release.countDown();
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        int port = transportConfig.getPort();
        assertTrue(transportConfig.isRunning());

        assertFalse(transportConfig.releaseFinishedStop());

        assertTrue("the new server keeps running", transportConfig.isRunning());
        assertEquals(port, transportConfig.getPort());
    }

    @Test(timeout = 15_000)
    public void shouldNameTheHolderOfALockAJettyThreadWaitsOn_whenTheHolderIsNotAJettyThread()
            throws Exception {
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                held.countDown();
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "test-lock-holder");
        Thread waiter = new Thread(() -> {
            lock.lock();
            lock.unlock();
        }, "archi-mcp-jetty-test-waiter");
        holder.setDaemon(true);
        waiter.setDaemon(true);
        holder.start();
        held.await();
        waiter.start();
        try {
            while (waiter.getState() != Thread.State.WAITING) {
                Thread.sleep(10);
            }
            String threads = TransportConfig.jettyThreads();

            // the waiter's own line already says "owned by" the holder: the holder needs an entry of its own
            assertTrue("the waiter, and an entry for the thread it waits for:\n" + threads,
                    threads.startsWith("\"archi-mcp-jetty-test-waiter\"")
                            || threads.contains("\n\"archi-mcp-jetty-test-waiter\""));
            assertTrue("an entry for the holder:\n" + threads,
                    threads.startsWith("\"test-lock-holder\"") || threads.contains("\n\"test-lock-holder\""));
        } finally {
            done.countDown();
        }
    }

    @Test
    public void shouldBoundTheStopAtTenSeconds_byDefault() {
        assertEquals(10_000L, TransportConfig.STOP_TIMEOUT_MS);
    }

    @Test
    public void shouldNotThrow_whenStoppingServerThatIsNotRunning() {
        assertFalse(transportConfig.isRunning());
        transportConfig.stopServer(); // Should not throw
        assertFalse(transportConfig.isRunning());
    }

    @Test
    public void shouldNotStartTwice_whenAlreadyRunning() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        assertTrue(transportConfig.isRunning());
        int firstPort = transportConfig.getPort();

        // Second start should be a no-op (already running), even for a different port. That port is
        // held by a blocker, so a start that did try to bind it would fail instead of passing quietly.
        try (ServerSocket blocker = new ServerSocket(0, 1, java.net.InetAddress.getByName(TEST_BIND_ADDRESS))) {
            transportConfig.startServer(blocker.getLocalPort(), TEST_BIND_ADDRESS);
        }
        assertTrue(transportConfig.isRunning());
        // Port should remain the original one
        assertEquals(firstPort, transportConfig.getPort());
    }

    @Test
    public void shouldExposeMcpServerInstances_whenStarted() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertNotNull(transportConfig.getStreamableMcpServer());
        assertNotNull(transportConfig.getSseMcpServer());
    }

    @Test
    public void shouldReturnNullMcpServers_whenNotStarted() {
        assertNull(transportConfig.getStreamableMcpServer());
        assertNull(transportConfig.getSseMcpServer());
    }

    @Test
    public void shouldReturnNullBindAddress_whenNotStarted() {
        assertNull(transportConfig.getBindAddress());
    }

    @Test
    public void shouldRejectInvalidBindAddress_whenAddressUnresolvable() {
        try {
            transportConfig.startServer(0, "999.invalid.address");
            fail("Expected ServerStartException for invalid bind address");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_BIND_ADDRESS, e.getErrorCode());
            assertFalse(transportConfig.isRunning());
        }
    }

    @Test
    public void shouldHaveCorrectErrorCode_whenPortInUse() {
        ServerStartException ex = new ServerStartException(
                "Port in use", new java.net.BindException("Address already in use"));
        assertEquals(ServerStartException.PORT_IN_USE, ex.getErrorCode());
    }

    @Test
    public void shouldHaveGenericErrorCode_whenOtherFailure() {
        ServerStartException ex = new ServerStartException(
                "Other failure", new RuntimeException("something else"));
        assertEquals(ServerStartException.SERVER_START_FAILED, ex.getErrorCode());
    }

    @Test
    public void shouldAcceptResourceSpecifications_withoutError() {
        transportConfig.setResourceSpecifications(List.of(createResourceSpec(
                "archimate://test/resource", "Test", "A test resource")));
        // No exception expected
    }

    @Test
    public void shouldAcceptNullResourceSpecifications() {
        transportConfig.setResourceSpecifications(null);
        // No exception expected — null treated as empty list
    }

    @Test
    public void shouldStartServer_withResourceSpecifications() throws Exception {
        transportConfig.setResourceSpecifications(List.of(createResourceSpec(
                "archimate://test/resource", "Test Resource", "A test resource")));
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
        assertNotNull(transportConfig.getStreamableMcpServer());
        assertNotNull(transportConfig.getSseMcpServer());
    }

    @Test
    public void shouldStartServer_withEmptyResourceSpecifications() throws Exception {
        transportConfig.setResourceSpecifications(Collections.emptyList());
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
    }

    // ---- TLS Tests ----

    @Test
    public void shouldStartWithTls_whenKeystoreConfigured() throws Exception {
        // Generate a keystore first
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-keystore-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);

        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS,
                    true, certResult.keystorePath(), certResult.password());

            assertTrue(transportConfig.isRunning());
            assertTrue(transportConfig.isTlsEnabled());
            assertTrue(transportConfig.getPort() > 0);
        } finally {
            transportConfig.stopServer();
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }
    }

    @Test
    public void shouldFailWithTlsError_whenKeystoreInvalid() {
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS,
                    true, "/nonexistent/keystore.p12", "password");
            fail("Expected ServerStartException for invalid keystore");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertFalse(transportConfig.isRunning());
        }
    }

    @Test
    public void shouldFailWithTlsError_whenKeystorePathEmpty() {
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS,
                    true, "", "password");
            fail("Expected ServerStartException for empty keystore path");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertFalse(transportConfig.isRunning());
        }
    }

    @Test
    public void shouldFailWithTlsError_whenPasswordEmpty() throws Exception {
        // Create a real keystore file to pass the file-exists check
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-nopw-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);

        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS,
                    true, certResult.keystorePath(), "");
            fail("Expected ServerStartException for empty password");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertFalse(transportConfig.isRunning());
        } finally {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }
    }

    @Test
    public void shouldStartWithoutTls_whenTlsDisabled() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS,
                false, null, null);

        assertTrue(transportConfig.isRunning());
        assertFalse(transportConfig.isTlsEnabled());
    }

    @Test
    public void shouldReturnTlsDisabled_whenNotRunning() {
        assertFalse(transportConfig.isTlsEnabled());
    }

    // ---- Runtime keystore-load classification ----
    // validateTlsConfig only pre-flights filesystem facts (path/exists/readable/blank-password). A
    // keystore that EXISTS + is READABLE but cannot be LOADED (wrong password, corrupt bytes) fails
    // only inside jettyServer.start() SSL init — that failure must be classified INVALID_TLS_CONFIG,
    // not swept into the generic SERVER_START_FAILED bucket.

    @Test
    public void shouldFailWithTlsError_whenKeystorePasswordWrong() throws Exception {
        // A valid keystore protected by one password, opened with a different (non-blank) one.
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-wrongpw-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);

        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS,
                    true, certResult.keystorePath(), certResult.password() + "-not-the-password");
            fail("Expected ServerStartException for a wrong keystore password");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertFalse(transportConfig.isRunning());
        } finally {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }
    }

    @Test
    public void shouldFailWithTlsError_whenKeystoreFileCorrupt() throws Exception {
        // A readable, non-blank-named .p12 whose bytes are not a valid keystore — passes the
        // pre-flight checks, fails when SslContextFactory tries to load it.
        java.nio.file.Path keystore = java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "test-tls-corrupt-" + System.nanoTime() + ".p12");
        java.nio.file.Files.write(keystore, "not a real keystore".getBytes(StandardCharsets.UTF_8));

        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS,
                    true, keystore.toString(), "anyPassword");
            fail("Expected ServerStartException for a corrupt keystore file");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertFalse(transportConfig.isRunning());
        } finally {
            java.nio.file.Files.deleteIfExists(keystore);
        }
    }

    // ---- A failed start leaves no Jetty threads behind ----
    // A keystore is loaded only after Jetty has started its thread pool, so a keystore failure leaves
    // a FAILED server with a running pool. Counted as a before/after difference by thread name: other
    // threads with the prefix may exist in the JVM (another server, an OSGi runtime).

    /** Live threads of Jetty's pool, by name; the stop's own thread is not part of the pool. */
    static Set<String> liveJettyPoolThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("archi-mcp-jetty") && !name.equals("archi-mcp-jetty-stop"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Waits up to 5 s for every pool thread started since {@code before} to end; names the survivors. */
    static void assertNoJettyPoolThreadsLeftSince(Set<String> before) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Set<String> left;
        do {
            left = liveJettyPoolThreads();
            left.removeAll(before);
            if (left.isEmpty()) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail(left.size() + " Jetty pool threads were left running: " + left);
    }

    @Test
    public void shouldLeaveNoJettyThreads_whenTheKeystorePasswordIsWrong() throws Exception {
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-leak-wrongpw-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);
        Set<String> before = liveJettyPoolThreads();

        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS,
                    true, certResult.keystorePath(), certResult.password() + "-not-the-password");
            fail("Expected ServerStartException for a wrong keystore password");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("Keystore could not be loaded: "));
            assertNoJettyPoolThreadsLeftSince(before);
        } finally {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }
    }

    @Test
    public void shouldLeaveNoJettyThreads_whenTheKeystoreIsCorrupt() throws Exception {
        java.nio.file.Path keystore = java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "test-tls-leak-corrupt-" + System.nanoTime() + ".p12");
        java.nio.file.Files.write(keystore, "not a real keystore".getBytes(StandardCharsets.UTF_8));
        Set<String> before = liveJettyPoolThreads();

        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS,
                    true, keystore.toString(), "anyPassword");
            fail("Expected ServerStartException for a corrupt keystore file");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("Keystore could not be loaded: "));
            assertNoJettyPoolThreadsLeftSince(before);
        } finally {
            java.nio.file.Files.deleteIfExists(keystore);
        }
    }

    @Test
    public void shouldCountTheRunningServersPoolThreads_whenTheProbeLooksForThem() throws Exception {
        // the probe's own check: were the pool renamed, every "no threads left" case above would pass
        // without looking at anything
        Set<String> before = liveJettyPoolThreads();
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        Set<String> started = liveJettyPoolThreads();
        started.removeAll(before);
        assertTrue("a running server has at least " + TransportConfig.MIN_THREADS + " pool threads, the probe saw "
                + started, started.size() >= TransportConfig.MIN_THREADS);

        transportConfig.stopServer();
        assertNoJettyPoolThreadsLeftSince(before);
    }

    @Test
    public void shouldLeaveNoJettyThreads_whenThePortIsInUse() throws Exception {
        // the port is bound before the pool starts, so this start never had threads to leave: unlike
        // the keystore failures, it passes with or without the failed start's stop
        Set<String> before = liveJettyPoolThreads();
        try (ServerSocket blocker = new ServerSocket(0, 1, java.net.InetAddress.getByName(TEST_BIND_ADDRESS))) {
            try {
                transportConfig.startServer(blocker.getLocalPort(), TEST_BIND_ADDRESS);
                fail("Expected ServerStartException for port conflict");
            } catch (ServerStartException e) {
                assertEquals(ServerStartException.PORT_IN_USE, e.getErrorCode());
                assertEquals("Port " + blocker.getLocalPort() + " already in use", e.getMessage());
                assertNoJettyPoolThreadsLeftSince(before);
            }
        }
    }

    @Test(timeout = 30_000)
    public void shouldThrowTheStartFailureWithinTheStopBound_whenTheFailedServerDoesNotFinishStopping()
            throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release));
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-heldstop-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);
        Set<String> before = liveJettyPoolThreads();
        try {
            long started = System.nanoTime();
            try {
                transportConfig.startServer(0, TEST_BIND_ADDRESS,
                        true, certResult.keystorePath(), certResult.password() + "-not-the-password");
                fail("Expected ServerStartException for a wrong keystore password");
            } catch (ServerStartException e) {
                assertEquals("the start's failure, not the stop's",
                        ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            }
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            // the time includes the keystore load and Jetty's own start, hence the wide upper margin; an
            // unbounded wait never returns at all, and fails on the test's timeout instead
            assertTrue("the failed server's stop is waited on up to the bound, then given up on; returned after "
                    + tookMs + " ms", tookMs >= TEST_STOP_TIMEOUT_MS && tookMs < TEST_STOP_TIMEOUT_MS + 8_000);
            assertFalse("a server that never started is not reported running", transportConfig.isRunning());
            assertEquals(0, transportConfig.getPort());

            release.countDown();
            // the stop given up on goes on by itself, and still releases the pool when it ends
            assertNoJettyPoolThreadsLeftSince(before);
        } finally {
            release.countDown();
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }
    }

    @Test(timeout = 30_000)
    public void shouldLogAnErrorNamingTheWait_whenTheFailedServerDoesNotFinishStopping() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<LogCall> calls = new CopyOnWriteArrayList<>();
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release),
                McpSyncServer::close, recordingLogger(calls));
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-heldstop-log-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);
        try {
            try {
                transportConfig.startServer(0, TEST_BIND_ADDRESS,
                        true, certResult.keystorePath(), certResult.password() + "-not-the-password");
                fail("Expected ServerStartException for a wrong keystore password");
            } catch (ServerStartException e) {
                assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            }

            List<LogCall> stuck = callsAt(calls, "error").stream()
                    .filter(call -> call.format().contains("Jetty did not finish stopping after a failed start"))
                    .toList();
            assertEquals("one ERROR line for the stuck stop: " + calls, 1, stuck.size());
            assertTrue("it says how long it waited: " + stuck.get(0).args(),
                    stuck.get(0).args().contains("waited up to " + TEST_STOP_TIMEOUT_MS + " ms"));
        } finally {
            release.countDown();
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }
    }

    @Test(timeout = 15_000)
    public void shouldThrowTheStartFailure_whenStoppingTheFailedServerFails() throws Exception {
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, server -> {
            server.stop();
            throw new IllegalStateException("the stop failed");
        });
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-stopfails-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS,
                    true, certResult.keystorePath(), certResult.password() + "-not-the-password");
            fail("Expected ServerStartException for a wrong keystore password");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("Keystore could not be loaded: "));
            assertFalse(transportConfig.isRunning());
        } finally {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }
    }

    @Test(timeout = 30_000)
    public void shouldStartAgain_whileTheStopOfAnEarlierStartThatFailedOnTheKeystoreIsStillStuck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release));
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-retry-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);
        try {
            try {
                transportConfig.startServer(0, TEST_BIND_ADDRESS,
                        true, certResult.keystorePath(), certResult.password() + "-not-the-password");
                fail("Expected ServerStartException for a wrong keystore password");
            } catch (ServerStartException e) {
                assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            }

            // the failed start's stop is still held: a new start must not wait on it or be refused
            transportConfig.startServer(0, TEST_BIND_ADDRESS);

            assertTrue(transportConfig.isRunning());
            assertTrue(transportConfig.getPort() > 0);
        } finally {
            release.countDown();
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }

        transportConfig.stopServer();
        assertFalse(transportConfig.isRunning());
        assertEquals(0, transportConfig.getPort());
    }

    // ---- A start that fails with an Error ----
    // No production trigger for a JVM Error inside Jetty's start is known (a LinkageError while Jetty loads
    // TLS classes would be one), so the tests raise one from the keystore load. For the start only, a provider
    // ahead of every other serves KeyStore type JKS; on the thread that starts the server its load throws the
    // test's Error, and any other thread asking for JKS in that window gets the JDK's own keystore (the provider
    // list is JVM-wide, and under OSGi it is shared with the platform's threads). Jetty loads the keystore on
    // the thread that calls start, after it has started the server's thread pool.

    /** Serves KeyStore type JKS: on {@code armed}, one whose load throws {@code error}; elsewhere, the JDK's. */
    private static final class ThrowingKeyStoreProvider extends Provider {
        static final String NAME = "ArchiMcpTestThrowingKeyStore";

        private static final long serialVersionUID = 1L;

        ThrowingKeyStoreProvider(Error error, Thread armed) {
            super(NAME, "1.0", "KeyStore JKS whose load throws a test's Error on one thread");
            // newInstance is overridden, so the class name is never loaded by name
            putService(new Service(this, "KeyStore", "JKS", ThrowingKeyStoreSpi.class.getName(), null, null) {
                @Override
                public Object newInstance(Object parameter) throws NoSuchAlgorithmException {
                    if (Thread.currentThread() != armed) {
                        return Security.getProvider("SUN").getService("KeyStore", "JKS").newInstance(parameter);
                    }
                    return new ThrowingKeyStoreSpi(error);
                }
            });
        }
    }

    private static final class ThrowingKeyStoreSpi extends KeyStoreSpi {
        private final Error error;

        ThrowingKeyStoreSpi(Error error) {
            this.error = error;
        }

        @Override
        public void engineLoad(InputStream stream, char[] password) {
            throw error;
        }

        // never reached: the load above is the first call Jetty makes
        @Override public Key engineGetKey(String alias, char[] password) { throw new UnsupportedOperationException(); }
        @Override public Certificate[] engineGetCertificateChain(String alias) { throw new UnsupportedOperationException(); }
        @Override public Certificate engineGetCertificate(String alias) { throw new UnsupportedOperationException(); }
        @Override public Date engineGetCreationDate(String alias) { throw new UnsupportedOperationException(); }
        @Override public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) { throw new UnsupportedOperationException(); }
        @Override public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) { throw new UnsupportedOperationException(); }
        @Override public void engineSetCertificateEntry(String alias, Certificate cert) { throw new UnsupportedOperationException(); }
        @Override public void engineDeleteEntry(String alias) { throw new UnsupportedOperationException(); }
        @Override public Enumeration<String> engineAliases() { throw new UnsupportedOperationException(); }
        @Override public boolean engineContainsAlias(String alias) { throw new UnsupportedOperationException(); }
        @Override public int engineSize() { throw new UnsupportedOperationException(); }
        @Override public boolean engineIsKeyEntry(String alias) { throw new UnsupportedOperationException(); }
        @Override public boolean engineIsCertificateEntry(String alias) { throw new UnsupportedOperationException(); }
        @Override public String engineGetCertificateAlias(Certificate cert) { throw new UnsupportedOperationException(); }
        @Override public void engineStore(OutputStream stream, char[] password) { throw new UnsupportedOperationException(); }
    }

    /**
     * Starts {@code config} with TLS on a .jks file that is not a keystore and returns what the start threw
     * (null if it returned). With an {@code error}, the keystore load throws it; without one, the file is
     * loaded as it is. The provider is in the JVM's list for this call only.
     */
    static Throwable startTlsOnAJksThatIsNotAKeystore(TransportConfig config, Error error) throws Exception {
        if (error != null) {
            assertEquals("the test's provider goes first, and is not one left by an earlier test", 1,
                    Security.insertProviderAt(new ThrowingKeyStoreProvider(error, Thread.currentThread()), 1));
        }
        Path keystore = Path.of(System.getProperty("java.io.tmpdir"), "test-tls-error-" + System.nanoTime() + ".jks");
        Throwable thrown = null;
        try {
            Files.write(keystore, "not a keystore".getBytes(StandardCharsets.UTF_8));
            config.startServer(0, TEST_BIND_ADDRESS, true, keystore.toString(), "anyPassword");
        } catch (Throwable t) {
            thrown = t;
        } finally {
            Security.removeProvider(ThrowingKeyStoreProvider.NAME);
            Files.deleteIfExists(keystore);
        }
        assertNull("the test's provider is removed", Security.getProvider(ThrowingKeyStoreProvider.NAME));
        return thrown;
    }

    /** The start throws {@code error} as it was, and leaves no pool threads, MCP servers or port behind. */
    private void assertAStartThatFailsWithReleasesEverything(Error error) throws Exception {
        Set<String> before = liveJettyPoolThreads();

        assertSame("the start's Error, as it was thrown", error,
                startTlsOnAJksThatIsNotAKeystore(transportConfig, error));

        assertNoJettyPoolThreadsLeftSince(before);
        assertNull("the streamable transport's MCP server is closed", transportConfig.getStreamableMcpServer());
        assertNull("the SSE transport's MCP server is closed", transportConfig.getSseMcpServer());
        assertFalse(transportConfig.isRunning());
        assertEquals(0, transportConfig.getPort());
    }

    @Test
    public void shouldLeaveNoJettyThreads_whenLoadingTheKeystoreThrowsALinkageError() throws Exception {
        assertAStartThatFailsWithReleasesEverything(new NoClassDefFoundError("thrown by the test's keystore provider"));
    }

    @Test
    public void shouldLeaveNoJettyThreads_whenLoadingTheKeystoreThrowsAnAssertionError() throws Exception {
        assertAStartThatFailsWithReleasesEverything(new AssertionError("thrown by the test's keystore provider"));
    }

    @Test
    public void shouldFailWithTlsError_whenTheJksThatIsNotAKeystoreIsLoadedWithoutTheTestsProvider() throws Exception {
        // the seam's own check: the cases above fail on the provider's Error, not on the file not being a keystore
        Throwable thrown = startTlsOnAJksThatIsNotAKeystore(transportConfig, null);

        assertTrue("expected a ServerStartException, was " + thrown, thrown instanceof ServerStartException);
        assertEquals(ServerStartException.INVALID_TLS_CONFIG, ((ServerStartException) thrown).getErrorCode());
    }

    @Test(timeout = 30_000)
    public void shouldThrowTheErrorWithinTheStopBound_whenTheServerItFailedDoesNotFinishStopping() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release));
        Error error = new NoClassDefFoundError("thrown by the test's keystore provider");
        Set<String> before = liveJettyPoolThreads();
        try {
            long started = System.nanoTime();
            Throwable thrown = startTlsOnAJksThatIsNotAKeystore(transportConfig, error);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertSame("the start's Error, not the stop's", error, thrown);
            // as for a keystore that fails to load: the margin covers Jetty's own start
            assertTrue("the failed server's stop is waited on up to the bound, then given up on; returned after "
                    + tookMs + " ms", tookMs >= TEST_STOP_TIMEOUT_MS && tookMs < TEST_STOP_TIMEOUT_MS + 8_000);
            assertFalse(transportConfig.isRunning());

            release.countDown();
            assertNoJettyPoolThreadsLeftSince(before);
        } finally {
            release.countDown();
        }
    }

    @Test
    public void shouldStartAgain_afterAStartThatFailedWithAnError() throws Exception {
        Error error = new NoClassDefFoundError("thrown by the test's keystore provider");
        assertSame(error, startTlsOnAJksThatIsNotAKeystore(transportConfig, error));

        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
        assertTrue(transportConfig.getPort() > 0);
        transportConfig.stopServer();
        assertFalse(transportConfig.isRunning());
        assertEquals(0, transportConfig.getPort());
    }

    // ---- An MCP SDK server whose close throws an Error ----
    // The SDK closes a server by subscribing to its graceful close on the caller's thread. Of the Errors raised
    // there, Reactor rethrows the JVM-fatal ones (a LinkageError, a VirtualMachineError, a ThreadDeath) and drops
    // the rest. No production trigger is known, so the tests replace the close: it closes every server for real,
    // and once armed, throws the test's Error after closing the server it is armed for.

    private static final TransportConfig.JettyStop STOP_AND_JOIN = server -> {
        server.stop();
        server.join();
    };

    /** Closes each server for real and records it; once armed, then throws the test's Error for one close. */
    private static final class ThrowingClose implements TransportConfig.McpServerClose {
        final List<McpSyncServer> closed = new CopyOnWriteArrayList<>();
        /** The servers closed after the test's Error was thrown. */
        final List<McpSyncServer> closedAfterTheError = new CopyOnWriteArrayList<>();
        private final AtomicReference<Error> armed = new AtomicReference<>();
        private volatile McpSyncServer target;
        private volatile McpSyncServer threwFor;

        /** The next close of {@code server} throws {@code error}; with a null server, the next close of any. */
        void arm(Error error, McpSyncServer server) {
            target = server;
            armed.set(error);
        }

        McpSyncServer threwFor() {
            return threwFor;
        }

        @Override
        public void close(McpSyncServer server) {
            server.close();
            closed.add(server);
            if (threwFor != null) {
                closedAfterTheError.add(server);
            }
            if (target == null || target == server) {
                Error error = armed.getAndSet(null);
                if (error != null) {
                    threwFor = server;
                    throw error;
                }
            }
        }
    }

    private TransportConfig closingWith(ThrowingClose close) {
        return new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, STOP_AND_JOIN, close);
    }

    /** Not running, no port, and neither MCP server kept. */
    private void assertStoppedAndReleased() {
        assertFalse(transportConfig.isRunning());
        assertEquals(0, transportConfig.getPort());
        assertNull("the streamable transport's MCP server is released", transportConfig.getStreamableMcpServer());
        assertNull("the SSE transport's MCP server is released", transportConfig.getSseMcpServer());
    }

    /** The first close threw, and the other server was closed after it. */
    private static void assertClosedBothAfterTheFirstThrew(ThrowingClose close) {
        assertNotNull("the test's close threw", close.threwFor());
        assertEquals("both MCP servers are closed", 2, close.closed.size());
        assertEquals("the other one after the Error", 1, close.closedAfterTheError.size());
        assertNotSame("and it is not the one the Error was thrown for",
                close.threwFor(), close.closedAfterTheError.get(0));
    }

    @Test
    public void shouldCloseTheOtherServerAndStop_whenClosingTheStreamableServerThrowsALinkageError() throws Exception {
        ThrowingClose close = new ThrowingClose();
        transportConfig = closingWith(close);
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        McpSyncServer streamable = transportConfig.getStreamableMcpServer();
        McpSyncServer sse = transportConfig.getSseMcpServer();
        close.arm(new NoClassDefFoundError("thrown by the test's close"), streamable);

        transportConfig.stopServer();

        assertSame("the test's close threw for the streamable server", streamable, close.threwFor());
        assertEquals("the SSE server is still closed, after the Error", List.of(sse), close.closedAfterTheError);
        assertStoppedAndReleased();
    }

    @Test(timeout = 20_000)
    public void shouldReportStoppedAndStartAgain_whenAClosingErrorFollowsAGivenUpStop() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThrowingClose close = new ThrowingClose();
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, heldUntil(release), close);
        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS);
            McpSyncServer streamable = transportConfig.getStreamableMcpServer();
            transportConfig.stopServer();
            assertTrue("the first stop was given up on", transportConfig.isRunning());
            release.countDown();
            close.arm(new NoClassDefFoundError("thrown by the test's close"), streamable);

            transportConfig.stopServer();

            assertSame("the test's close threw for the streamable server", streamable, close.threwFor());
            assertFalse("a stop that has ended is not still reported running", transportConfig.isRunning());
            assertEquals(0, transportConfig.getPort());
        } finally {
            release.countDown();
        }

        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.getPort() > 0);
        transportConfig.stopServer();
        assertFalse(transportConfig.isRunning());
        assertEquals(0, transportConfig.getPort());
    }

    @Test
    public void shouldThrowTheStartFailure_whenClosingAnMcpServerAfterItThrowsALinkageError() throws Exception {
        ThrowingClose close = new ThrowingClose();
        transportConfig = closingWith(close);
        close.arm(new NoClassDefFoundError("thrown by the test's close"), null);
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-closeerror-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);
        Set<String> before = liveJettyPoolThreads();

        try {
            transportConfig.startServer(0, TEST_BIND_ADDRESS,
                    true, certResult.keystorePath(), certResult.password() + "-not-the-password");
            fail("Expected ServerStartException for a wrong keystore password");
        } catch (ServerStartException e) {
            assertEquals("the start's failure, not the close's",
                    ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("Keystore could not be loaded: "));
        } finally {
            Files.deleteIfExists(Path.of(keystorePath));
        }

        assertClosedBothAfterTheFirstThrew(close);
        assertNoJettyPoolThreadsLeftSince(before);
        assertStoppedAndReleased();
    }

    @Test
    public void shouldThrowTheStartsOwnError_whenClosingAnMcpServerAfterItThrowsALinkageError() throws Exception {
        ThrowingClose close = new ThrowingClose();
        transportConfig = closingWith(close);
        close.arm(new NoClassDefFoundError("thrown by the test's close"), null);
        Error error = new NoClassDefFoundError("thrown by the test's keystore provider");
        Set<String> before = liveJettyPoolThreads();

        assertSame("the start's own Error, not the close's", error,
                startTlsOnAJksThatIsNotAKeystore(transportConfig, error));

        assertClosedBothAfterTheFirstThrew(close);
        assertNoJettyPoolThreadsLeftSince(before);
        assertStoppedAndReleased();
    }

    @Test
    public void shouldStillThrowAVirtualMachineErrorFromAClose_whenTheServerIsReleasedFirst() throws Exception {
        ThrowingClose close = new ThrowingClose();
        transportConfig = closingWith(close);
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        McpSyncServer streamable = transportConfig.getStreamableMcpServer();
        McpSyncServer sse = transportConfig.getSseMcpServer();
        Error error = new OutOfMemoryError("thrown by the test's close");
        close.arm(error, streamable);

        try {
            transportConfig.stopServer();
            fail("a VirtualMachineError from a close is not swallowed");
        } catch (OutOfMemoryError e) {
            assertSame("the close's own Error", error, e);
        }

        assertEquals("the SSE server is still closed, after the Error", List.of(sse), close.closedAfterTheError);
        assertStoppedAndReleased();
    }

    // ---- MCP sessions open across a stop ----
    // Closing an MCP SDK server has no time limit of its own, and a streamable session's close waits for any
    // write in flight to that session, which blocks while the client does not read. The stop's closes are not
    // left to do that: both transports are servlets, Jetty's stop (which is bounded) closes the connections and
    // then destroys the servlets, and each destroy() closes its sessions. So by the time the MCP servers are
    // closed, no session is left; this test pins that for sessions a client holds open.

    @Test(timeout = 20_000)
    public void shouldFindNoOpenSessions_whenClosingTheMcpServersAfterAStop() throws Exception {
        ThrowingClose close = new ThrowingClose();
        Map<McpSyncServer, Integer> sessionsAtClose = new ConcurrentHashMap<>();
        transportConfig = new TransportConfig(() -> null, TEST_STOP_TIMEOUT_MS, STOP_AND_JOIN, server -> {
            try {
                sessionsAtClose.put(server, openSessions(server));
            } finally {
                close.close(server); // closed for real even when the count cannot be read
            }
        });
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        int port = transportConfig.getPort();
        McpSyncServer streamable = transportConfig.getStreamableMcpServer();
        McpSyncServer sse = transportConfig.getSseMcpServer();

        try (Socket streamableStream = openStreamableListeningStream(port, streamable);
                Socket sseStream = openSseStream(port)) {
            assertEquals("a client holds a streamable session open", 1, openSessions(streamable));
            assertEquals("and an SSE session", 1, openSessions(sse));

            transportConfig.stopServer();

            assertFalse("the stop finished within " + TEST_STOP_TIMEOUT_MS + " ms", transportConfig.isRunning());
            assertEquals("both MCP servers are closed", Set.of(streamable, sse), Set.copyOf(close.closed));
            assertEquals("with no streamable session left to close",
                    Integer.valueOf(0), sessionsAtClose.get(streamable));
            assertEquals("nor an SSE one", Integer.valueOf(0), sessionsAtClose.get(sse));
        }
    }

    /**
     * The sessions an MCP SDK server's transport holds open. The SDK does not expose them, so they are read
     * from its private fields; a test that fails here needs the field names of the SDK in use.
     */
    private static int openSessions(McpSyncServer server) {
        Object provider = null;
        try {
            provider = fieldValue(server.getAsyncServer(), "mcpTransportProvider");
            return ((Map<?, ?>) fieldValue(provider, "sessions")).size();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new AssertionError("cannot read the sessions of " + server + " (transport "
                    + (provider == null ? "unknown" : provider.getClass().getName()) + ")", e);
        }
    }

    /** The value of the named field, declared by the object's class or one of its superclasses. */
    private static Object fieldValue(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name + " in " + target.getClass().getName() + " or its superclasses");
    }

    /**
     * Opens a streamable session and its listening stream (a GET that the server keeps open to send the
     * client notifications), and returns the stream's connection once a notification has arrived on it.
     */
    private static Socket openStreamableListeningStream(int port, McpSyncServer streamable) throws Exception {
        String sessionId;
        java.net.URI endpoint = java.net.URI.create("http://" + TEST_BIND_ADDRESS + ":" + port + "/mcp");
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            java.net.http.HttpResponse<String> initialized = client.send(mcpPost(endpoint, null,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals("initialize, body: " + initialized.body(), 200, initialized.statusCode());
            sessionId = initialized.headers().firstValue("mcp-session-id").orElseThrow();
            java.net.http.HttpResponse<String> notified = client.send(mcpPost(endpoint, sessionId,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals("notifications/initialized, body: " + notified.body(), 202, notified.statusCode());
        }
        Socket stream = openEventStream(port, "/mcp", "mcp-session-id: " + sessionId + "\r\n");
        // the server writes nothing on this stream until it has a notification to send
        return readUntil(stream, "data:", streamable::notifyToolsListChanged);
    }

    private static java.net.http.HttpRequest mcpPost(java.net.URI endpoint, String sessionId, String body) {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(java.time.Duration.ofSeconds(3))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header("mcp-session-id", sessionId);
        }
        return request.build();
    }

    /** Opens an SSE session, and returns its connection once the server has sent the session's endpoint. */
    private static Socket openSseStream(int port) throws Exception {
        return readUntil(openEventStream(port, "/sse", ""), "event: endpoint", () -> { });
    }

    private static Socket openEventStream(int port, String path, String extraHeaders) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(TEST_BIND_ADDRESS, port), 1000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + TEST_BIND_ADDRESS + ":" + port + "\r\n"
                    + "Accept: text/event-stream\r\n" + extraHeaders + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return socket;
        } catch (Exception e) {
            socket.close();
            throw e;
        }
    }

    /**
     * Reads the stream until {@code marker} arrives, running {@code meanwhile} before each read, and returns the
     * stream; closes it if the marker does not arrive.
     */
    private static Socket readUntil(Socket stream, String marker, Runnable meanwhile) throws Exception {
        try {
            stream.setSoTimeout(200);
            InputStream in = stream.getInputStream();
            StringBuilder read = new StringBuilder();
            byte[] buffer = new byte[8192];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (read.indexOf(marker) < 0) {
                assertTrue("no '" + marker + "' within 3 s, read: " + read, System.nanoTime() < deadline);
                meanwhile.run();
                try {
                    int n = in.read(buffer);
                    assertTrue("the stream ended before '" + marker + "', read: " + read, n >= 0);
                    read.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                } catch (java.net.SocketTimeoutException e) {
                    // nothing yet
                }
            }
            return stream;
        } catch (Throwable t) {
            stream.close();
            throw t;
        }
    }

    // ---- Pure start-failure classifier (over-classification guard) ----
    // classifyStartFailure walks the FULL cause chain; tested directly with hand-built chains so the
    // nested-BindException case is deterministic (independent of how Jetty happens to wrap it).

    @Test
    public void shouldClassifyPortInUse_whenBindExceptionNestedDeepInChain() {
        Throwable nested = new RuntimeException("wrap",
                new java.io.IOException("io", new java.net.BindException("Address already in use")));
        assertEquals(ServerStartException.PORT_IN_USE,
                TransportConfig.classifyStartFailure(nested, false));
        // A bind conflict wins even on a TLS run (checked before the keystore branch).
        assertEquals(ServerStartException.PORT_IN_USE,
                TransportConfig.classifyStartFailure(nested, true));
    }

    @Test
    public void shouldClassifyPortInUse_whenBindExceptionIsRoot() {
        assertEquals(ServerStartException.PORT_IN_USE,
                TransportConfig.classifyStartFailure(new java.net.BindException("in use"), false));
    }

    @Test
    public void shouldClassifyTlsConfig_whenWrongPasswordChainUnderTls() {
        // Empirical wrong-password shape: IOException caused by UnrecoverableKeyException.
        Throwable wrongPw = new java.io.IOException("keystore password was incorrect",
                new java.security.UnrecoverableKeyException("failed to decrypt safe contents entry"));
        assertEquals(ServerStartException.INVALID_TLS_CONFIG,
                TransportConfig.classifyStartFailure(wrongPw, true));
    }

    @Test
    public void shouldClassifyTlsConfig_whenCorruptKeystoreChainUnderTls() {
        // Empirical corrupt-file shape: a bare EOFException, no JSSE-specific type.
        assertEquals(ServerStartException.INVALID_TLS_CONFIG,
                TransportConfig.classifyStartFailure(new java.io.EOFException(), true));
    }

    @Test
    public void shouldNotClassifyTlsConfig_whenKeystoreShapedFailureButTlsDisabled() {
        // The same IOException must NOT be called a TLS problem when TLS is off (no keystore loaded).
        assertEquals(ServerStartException.SERVER_START_FAILED,
                TransportConfig.classifyStartFailure(new java.io.EOFException(), false));
    }

    @Test
    public void shouldNotClassifyTlsConfig_whenNonKeystoreProgrammingErrorUnderTls() {
        // A RuntimeException with no IOException/GeneralSecurityException in the chain is NOT a
        // keystore failure even under TLS — it stays the generic code (over-classification guard).
        assertEquals(ServerStartException.SERVER_START_FAILED,
                TransportConfig.classifyStartFailure(new IllegalStateException("bug"), true));
    }

    @Test
    public void shouldClassifyGeneric_whenNullFailure() {
        assertEquals(ServerStartException.SERVER_START_FAILED,
                TransportConfig.classifyStartFailure(null, true));
        assertEquals(ServerStartException.SERVER_START_FAILED,
                TransportConfig.classifyStartFailure(null, false));
    }

    @Test
    public void shouldTerminate_whenCauseChainIsCyclic() {
        // A self-referential chain must not loop forever; the identity-visited set breaks the cycle.
        // Note: Throwable.initCause only forbids DIRECT self-causation (cause == this); it does not
        // walk the chain, so a.initCause(b) where b.getCause()==a succeeds and forms a real a->b->a
        // cycle. hasCauseOfType must return (not hang) — proven by this completing.
        Throwable a = new RuntimeException("a");
        Throwable b = new RuntimeException("b", a);
        a.initCause(b); // a -> b -> a cycle (initCause does NOT throw here)
        assertFalse(TransportConfig.hasCauseOfType(a, java.net.BindException.class));
    }

    @Test
    public void shouldStopAtDepthBound_whenTargetDeeperThanMaxCauseDepth() {
        // The depth bound (MAX_CAUSE_DEPTH) is a safety stop for pathological chains: a target buried
        // far deeper than any realistic Jetty wrapping (~2-4 levels) is intentionally NOT found, so a
        // runaway/forged chain can't cost an unbounded walk. Build a BindException 30 levels down.
        Throwable t = new java.net.BindException("deep");
        for (int i = 0; i < 30; i++) {
            t = new RuntimeException("layer " + i, t);
        }
        assertFalse("BindException beyond the depth bound must not be found",
                TransportConfig.hasCauseOfType(t, java.net.BindException.class));
        // Sanity: the SAME type shallow in the chain IS found (the bound is the only reason above).
        assertTrue(TransportConfig.hasCauseOfType(
                new RuntimeException("shallow", new java.net.BindException("x")),
                java.net.BindException.class));
    }

    // ---- Resource guardrail tests ----

    @Test
    public void shouldEnforceConfiguredIdleTimeout_whenServerStarted() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertEquals(TransportConfig.IDLE_TIMEOUT_MS, transportConfig.getIdleTimeout());
    }

    @Test
    public void shouldUseBoundedThreadPool_whenServerStarted() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertEquals(TransportConfig.MAX_THREADS, transportConfig.getMaxThreads());
    }

    @Test
    public void shouldReturnSentinelGuardrails_whenNotStarted() {
        assertEquals(-1L, transportConfig.getIdleTimeout());
        assertEquals(-1, transportConfig.getMaxThreads());
    }

    @Test
    public void shouldReturnSentinelGuardrails_afterServerStopped() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        transportConfig.stopServer();

        // Both accessors gate on isRunning(), so the sentinels stay consistent once stopped.
        assertEquals(-1L, transportConfig.getIdleTimeout());
        assertEquals(-1, transportConfig.getMaxThreads());
    }

    @Test
    public void shouldReject413_whenRequestBodyExceedsMax() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        int port = transportConfig.getPort();

        // Declare a Content-Length one byte over the cap. SizeLimitHandler rejects on the declared
        // length up-front (413) before reading the body, so no oversized payload is actually sent.
        long oversized = TransportConfig.MAX_REQUEST_BODY_BYTES + 1;
        int status = postStatus(port, "/mcp", oversized, new byte[0]);

        assertEquals(413, status);
    }

    @Test
    public void shouldNotReject413_whenRequestBodyUnderMax() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        int port = transportConfig.getPort();

        // A small, well-under-limit body must not trip the size guard. The MCP servlet may answer
        // with its own status (e.g. 400/406) for a non-protocol body — we only assert it is NOT 413.
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        int status = postStatus(port, "/mcp", body.length, body);

        assertNotEquals(413, status);
    }

    // ---- Helpers ----

    /**
     * Sends a raw HTTP/1.1 POST with an explicit {@code Content-Length} header and the given body
     * bytes, then returns the numeric HTTP status from the response status line. Uses a raw socket
     * (not HttpURLConnection) so the declared Content-Length can exceed the bytes actually written —
     * this exercises {@link TransportConfig}'s SizeLimitHandler up-front 413 without transmitting an
     * oversized payload. {@code Connection: close} keeps it single-shot.
     */
    private int postStatus(int port, String path, long contentLength, byte[] body) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(TEST_BIND_ADDRESS, port), 5000);
            socket.setSoTimeout(5000);

            String requestHead = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + TEST_BIND_ADDRESS + ":" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + contentLength + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";

            OutputStream out = socket.getOutputStream();
            out.write(requestHead.getBytes(StandardCharsets.US_ASCII));
            if (body.length > 0) {
                out.write(body);
            }
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine;
            try {
                statusLine = reader.readLine(); // e.g. "HTTP/1.1 413 Payload Too Large"
            } catch (java.net.SocketTimeoutException e) {
                // Cleaner diagnostic than an opaque errored test if the server never responds.
                fail("Server did not send an HTTP status line within the read timeout: " + e.getMessage());
                return -1; // unreachable (fail() throws)
            }
            assertNotNull("No HTTP status line received", statusLine);
            String[] parts = statusLine.split(" ");
            assertTrue("Malformed status line: " + statusLine, parts.length >= 2);
            return Integer.parseInt(parts[1]);
        }
    }

    // ---- serverInfo.version -------------------------------------------------------------------

    private static final String SEAM_VERSION = "9.8.7.test";

    @Test
    public void shouldReturnSuppliedVersion_whenSupplierHasOne() {
        assertEquals(SEAM_VERSION, TransportConfig.serverVersion(() -> SEAM_VERSION));
    }

    @Test
    public void shouldTrimSuppliedVersion_whenItHasSurroundingSpaces() {
        assertEquals("9.8.7", TransportConfig.serverVersion(() -> "  9.8.7  "));
    }

    @Test
    public void shouldReturnUnknown_whenSupplierReturnsNull() {
        assertEquals("unknown", TransportConfig.serverVersion(() -> null));
    }

    @Test
    public void shouldReturnUnknown_whenSupplierReturnsEmpty() {
        assertEquals("unknown", TransportConfig.serverVersion(() -> ""));
    }

    @Test
    public void shouldReturnUnknown_whenSupplierReturnsBlank() {
        assertEquals("unknown", TransportConfig.serverVersion(() -> "   "));
    }

    @Test
    public void shouldReturnUnknown_whenSupplierThrowsRuntimeException() {
        assertEquals("unknown", TransportConfig.serverVersion(() -> {
            throw new IllegalStateException("no bundle");
        }));
    }

    @Test
    public void shouldReturnUnknown_whenSupplierThrowsLinkageError() {
        assertEquals("unknown", TransportConfig.serverVersion(() -> {
            throw new NoClassDefFoundError("org/osgi/framework/Bundle");
        }));
    }

    @Test
    public void shouldReportRunningPluginVersionOnBothTransports_whenDefaultConstructed() throws Exception {
        // Headless there is no running plugin, so the answer is "unknown"; in the plug-in test runner the
        // plugin is active and the answer is its bundle version, which pins the production lookup itself.
        McpPlugin plugin = McpPlugin.getDefault();
        String expected = plugin == null ? "unknown" : plugin.getBundle().getVersion().toString();
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        McpSchema.Implementation streamable = transportConfig.getStreamableMcpServer().getServerInfo();
        McpSchema.Implementation sse = transportConfig.getSseMcpServer().getServerInfo();
        assertEquals(expected, streamable.version());
        assertEquals(expected, sse.version());
        assertEquals("ArchiMate MCP Server", streamable.name());
        assertEquals("ArchiMate MCP Server", sse.name());
    }

    @Test
    public void shouldReportSuppliedVersionOnBothTransports_whenStarted() throws Exception {
        transportConfig = new TransportConfig(() -> SEAM_VERSION);
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertEquals("streamable", SEAM_VERSION,
                transportConfig.getStreamableMcpServer().getServerInfo().version());
        assertEquals("sse", SEAM_VERSION, transportConfig.getSseMcpServer().getServerInfo().version());
    }

    @Test
    public void shouldAnswerInitializeWithSuppliedVersion_whenClientConnects() throws Exception {
        transportConfig = new TransportConfig(() -> SEAM_VERSION);
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        int port = transportConfig.getPort();

        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://" + TEST_BIND_ADDRESS + ":" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(java.time.Duration.ofSeconds(10))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(initialize))
                .build();
        java.net.http.HttpResponse<String> response;
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        }

        assertEquals("HTTP status, body: " + response.body(), 200, response.statusCode());
        com.fasterxml.jackson.databind.JsonNode reply = jsonRpcReply(response.body(), 1);
        assertEquals(reply.toString(), SEAM_VERSION, reply.path("result").path("serverInfo").path("version").asText());
    }

    @Test
    public void shouldAnnounceTheReadOnlyInstructionsOnBothTransports_whenSet() throws Exception {
        transportConfig.setInstructions(TransportConfig.READ_ONLY_INSTRUCTIONS);
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertEquals(TransportConfig.READ_ONLY_INSTRUCTIONS,
                fieldValue(transportConfig.getStreamableMcpServer().getAsyncServer(), "instructions"));
        assertEquals(TransportConfig.READ_ONLY_INSTRUCTIONS,
                fieldValue(transportConfig.getSseMcpServer().getAsyncServer(), "instructions"));
        com.fasterxml.jackson.databind.JsonNode reply = initializeOverHttp(transportConfig.getPort());
        assertEquals(reply.toString(), TransportConfig.READ_ONLY_INSTRUCTIONS,
                reply.path("result").path("instructions").asText());
        assertTrue(TransportConfig.READ_ONLY_INSTRUCTIONS.startsWith("This server is read-only."));
    }

    @Test
    public void shouldAnnounceNoInstructions_whenNoneAreSet() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);

        assertNull(fieldValue(transportConfig.getStreamableMcpServer().getAsyncServer(), "instructions"));
        assertNull(fieldValue(transportConfig.getSseMcpServer().getAsyncServer(), "instructions"));
        com.fasterxml.jackson.databind.JsonNode reply = initializeOverHttp(transportConfig.getPort());
        assertTrue(reply.toString(), reply.path("result").path("instructions").isMissingNode()
                || reply.path("result").path("instructions").isNull());
    }

    private static com.fasterxml.jackson.databind.JsonNode initializeOverHttp(int port) throws Exception {
        java.net.URI endpoint = java.net.URI.create("http://" + TEST_BIND_ADDRESS + ":" + port + "/mcp");
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            java.net.http.HttpResponse<String> response = client.send(mcpPost(endpoint, null,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals("initialize, body: " + response.body(), 200, response.statusCode());
            return jsonRpcReply(response.body(), 1);
        }
    }

    /**
     * On a read-only server a tool that can change the model is not registered, so it is neither listed
     * nor callable: the SDK answers a call to it with a JSON-RPC error before any handler is reached.
     */
    @Test
    public void shouldNeitherListNorRunAWriteTool_whenReadOnly() throws Exception {
        transportConfig.startServer(0, TEST_BIND_ADDRESS);
        net.vheerden.archi.mcp.registry.CommandRegistry registry = new net.vheerden.archi.mcp.registry.CommandRegistry();
        registry.setMcpServers(transportConfig.getStreamableMcpServer(), transportConfig.getSseMcpServer());
        net.vheerden.archi.mcp.handlers.HandlerRegistrar.registerAll(
                new net.vheerden.archi.mcp.model.BaseTestAccessor(true),
                new net.vheerden.archi.mcp.response.ResponseFormatter(), registry,
                new net.vheerden.archi.mcp.session.SessionManager(
                        net.vheerden.archi.mcp.handlers.SearchHandler.VALID_TYPES,
                        net.vheerden.archi.mcp.handlers.SearchHandler.VALID_LAYERS),
                true);
        java.net.URI endpoint = java.net.URI.create("http://" + TEST_BIND_ADDRESS + ":" + transportConfig.getPort() + "/mcp");

        com.fasterxml.jackson.databind.JsonNode listed;
        com.fasterxml.jackson.databind.JsonNode called;
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            java.net.http.HttpResponse<String> initialized = client.send(mcpPost(endpoint, null,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals("initialize, body: " + initialized.body(), 200, initialized.statusCode());
            String sessionId = initialized.headers().firstValue("mcp-session-id").orElseThrow();
            client.send(mcpPost(endpoint, sessionId, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            listed = jsonRpcReply(client.send(mcpPost(endpoint, sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body(), 2);
            called = jsonRpcReply(client.send(mcpPost(endpoint, sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"create-element\",\"arguments\":{\"type\":\"BusinessActor\","
                            + "\"name\":\"Nobody\"}}}"),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body(), 3);
        }

        Set<String> names = new TreeSet<>();
        listed.path("result").path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        assertEquals(listed.toString(), 20, names.size());
        assertTrue(names.contains("get-model-info"));
        assertFalse(names.contains("create-element"));
        assertTrue("a call to a withheld tool is a JSON-RPC error, not a tool result: " + called,
                called.has("error"));
        assertFalse(called.toString(), called.has("result"));
        assertEquals(called.toString(), -32602, called.path("error").path("code").asInt());
        assertTrue("the error names the tool that was called: " + called,
                called.path("error").path("data").asText().contains("create-element"));
    }

    /**
     * The JSON-RPC reply with the given id, from a body sent either as plain JSON or as server-sent events
     * (one message per {@code data:} line, so a notification sent before the reply is skipped).
     */
    private static com.fasterxml.jackson.databind.JsonNode jsonRpcReply(String body, int id) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        boolean sawEvent = false;
        for (String line : body.split("\r?\n")) {
            if (line.startsWith("data:")) {
                sawEvent = true;
                com.fasterxml.jackson.databind.JsonNode message = mapper.readTree(line.substring("data:".length()));
                if (message.path("id").asInt(-1) == id) {
                    return message;
                }
            }
        }
        if (sawEvent) {
            fail("No reply with id " + id + " among the events: " + body);
        }
        return mapper.readTree(body);
    }

    private McpServerFeatures.SyncResourceSpecification createResourceSpec(
            String uri, String name, String description) {
        McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri(uri)
                .name(name)
                .description(description)
                .mimeType("text/markdown")
                .build();

        BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> handler =
                (exchange, request) -> new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents(request.uri(), "text/markdown", "test")));

        return new McpServerFeatures.SyncResourceSpecification(resource, handler);
    }
}
