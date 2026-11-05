package net.vheerden.archi.mcp.server;

import static org.junit.Assert.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for {@link CertificateGenerator}.
 *
 * <p>Uses a temporary folder for keystore generation to avoid polluting the user's home directory.</p>
 */
public class CertificateGeneratorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void shouldGenerateKeystoreFile() throws Exception {
        String keystorePath = tempFolder.getRoot().toPath()
                .resolve("test-keystore.p12").toString();

        CertificateGenerator.Result result = CertificateGenerator.generate(keystorePath);

        assertNotNull(result);
        assertEquals(keystorePath, result.keystorePath());
        assertNotNull(result.password());
        assertFalse(result.password().isEmpty());
        assertTrue("Keystore file should exist", Files.exists(Path.of(keystorePath)));
    }

    @Test
    public void shouldGenerateValidPkcs12Keystore() throws Exception {
        String keystorePath = tempFolder.getRoot().toPath()
                .resolve("test-keystore.p12").toString();

        CertificateGenerator.Result result = CertificateGenerator.generate(keystorePath);

        // Load and verify the keystore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, result.password().toCharArray());
        }

        assertTrue("Keystore should contain 'archi-mcp' alias", ks.containsAlias("archi-mcp"));
        assertNotNull("Should have private key", ks.getKey("archi-mcp", result.password().toCharArray()));
    }

    @Test
    public void shouldGenerateCertificateWithCorrectSubject() throws Exception {
        String keystorePath = tempFolder.getRoot().toPath()
                .resolve("test-keystore.p12").toString();

        CertificateGenerator.Result result = CertificateGenerator.generate(keystorePath);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, result.password().toCharArray());
        }

        Certificate cert = ks.getCertificate("archi-mcp");
        assertNotNull("Certificate should exist", cert);
        assertTrue("Should be X.509", cert instanceof X509Certificate);

        X509Certificate x509 = (X509Certificate) cert;
        assertTrue("Subject should contain CN=localhost",
                x509.getSubjectX500Principal().getName().contains("CN=localhost"));
    }

    @Test
    public void shouldGenerateCertificateWithSanEntries() throws Exception {
        String keystorePath = tempFolder.getRoot().toPath()
                .resolve("test-keystore.p12").toString();

        CertificateGenerator.Result result = CertificateGenerator.generate(keystorePath);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, result.password().toCharArray());
        }

        X509Certificate x509 = (X509Certificate) ks.getCertificate("archi-mcp");
        Collection<java.util.List<?>> sans = x509.getSubjectAlternativeNames();
        assertNotNull("Should have Subject Alternative Names", sans);

        // SAN type 2 = DNS, type 7 = IP
        boolean hasLocalhostDns = false;
        boolean hasLoopbackIp = false;
        for (java.util.List<?> san : sans) {
            int type = (Integer) san.get(0);
            String value = san.get(1).toString();
            if (type == 2 && "localhost".equals(value)) hasLocalhostDns = true;
            if (type == 7 && "127.0.0.1".equals(value)) hasLoopbackIp = true;
        }
        assertTrue("Should have DNS:localhost SAN", hasLocalhostDns);
        assertTrue("Should have IP:127.0.0.1 SAN", hasLoopbackIp);
    }

    @Test
    public void shouldGenerateUniquePasswords() {
        String pw1 = CertificateGenerator.generatePassword();
        String pw2 = CertificateGenerator.generatePassword();

        assertNotNull(pw1);
        assertNotNull(pw2);
        assertFalse("Passwords should be unique", pw1.equals(pw2));
        assertTrue("Password should be at least 12 chars", pw1.length() >= 12);
    }

    @Test
    public void shouldCreateParentDirectories() throws Exception {
        String keystorePath = tempFolder.getRoot().toPath()
                .resolve("nested/dir/keystore.p12").toString();

        CertificateGenerator.Result result = CertificateGenerator.generate(keystorePath);

        assertTrue("Keystore file should exist", Files.exists(Path.of(keystorePath)));
        assertNotNull(result);
    }

    @Test
    public void shouldOverwriteExistingKeystore() throws Exception {
        String keystorePath = tempFolder.getRoot().toPath()
                .resolve("test-keystore.p12").toString();

        // Generate twice — second should succeed without alias collision
        CertificateGenerator.generate(keystorePath);
        CertificateGenerator.Result result = CertificateGenerator.generate(keystorePath);

        assertNotNull(result);
        assertTrue(Files.exists(Path.of(keystorePath)));
    }

    @Test
    public void shouldGenerateKeystore_whenPasswordStartsWithLauncherOption() throws Exception {
        // The JDK launcher pre-scans argv and swallows any argument beginning with "-J" as a JVM
        // option, so a password drawn from the Base64 URL alphabet can begin with a launcher option.
        String password = "-JxGNf2OuLXZr6Ef";
        String keystorePath = tempFolder.getRoot().toPath()
                .resolve("launcher-option-keystore.p12").toString();

        CertificateGenerator.Result result = CertificateGenerator.generate(keystorePath, password);

        assertEquals(password, result.password());

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, password.toCharArray());
        }

        assertTrue("Keystore should contain 'archi-mcp' alias", ks.containsAlias("archi-mcp"));
        assertNotNull("Private key should unlock with the exact generated password",
                ks.getKey("archi-mcp", password.toCharArray()));
    }

    @Test
    public void shouldRejectNullPassword_whenGeneratingWithSuppliedPassword() throws Exception {
        // A null reaching the environment map surfaces as an opaque NPE from java.lang after the
        // keystore directory has already been created. Both halves are asserted: the exception
        // names the offending parameter, and it is thrown before any filesystem work happens.
        Path nested = tempFolder.getRoot().toPath().resolve("null-pw/dir");
        String keystorePath = nested.resolve("keystore.p12").toString();

        try {
            CertificateGenerator.generate(keystorePath, null);
            fail("Expected NullPointerException for a null password");
        } catch (NullPointerException expected) {
            assertEquals("password", expected.getMessage());
        }

        assertFalse("Should fail before creating the keystore directory", Files.exists(nested));
    }

    @Test(timeout = 30_000)
    public void shouldStopTheChildAndThrow_whenItDoesNotFinishWithinTheBound() throws Exception {
        String secret = "secret-env-value-0123";
        ProcessBuilder pb = pausedJvm();
        pb.environment().put("ARCHI_MCP_KEYSTORE_PASSWORD", secret);

        long start = System.nanoTime();
        IOException thrown = null;
        try {
            CertificateGenerator.runBounded(pb, HANG_BOUND_MS);
        } catch (IOException e) {
            thrown = e;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Lower bound first: a call that returns at once must not pass as "bounded".
        assertTrue("Returned after " + elapsedMs + " ms, before the bound", elapsedMs >= HANG_BOUND_MS);
        assertTrue("Returned after " + elapsedMs + " ms, past bound + margin",
                elapsedMs < HANG_BOUND_MS + HANG_MARGIN_MS);
        assertNotNull("Expected an IOException for a child that did not finish", thrown);
        assertEquals("keytool did not finish within 2 s and was stopped", thrown.getMessage());
        assertFalse("The message must not carry the child's environment", thrown.getMessage().contains(secret));
        assertChildGone(pausedPid());
    }

    @Test(timeout = 30_000)
    public void shouldStopTheChildAndRethrow_whenInterruptedDuringTheWait() throws Exception {
        boolean[] interruptedAfterThrow = new boolean[1];
        FutureTask<Void> call = new FutureTask<>(() -> {
            try {
                CertificateGenerator.runBounded(pausedJvm(), 60_000L);
            } catch (InterruptedException e) {
                interruptedAfterThrow[0] = Thread.currentThread().isInterrupted();
                throw e;
            }
            return null;
        });
        Thread caller = new Thread(call, "certificate-generator-test-caller");
        caller.setDaemon(true);
        caller.start();

        long pid = awaitPausedPid();
        caller.interrupt();
        try {
            call.get(HANG_MARGIN_MS + 5_000, TimeUnit.MILLISECONDS);
            fail("Expected an InterruptedException");
        } catch (ExecutionException e) {
            assertTrue("Expected InterruptedException, got " + e.getCause(),
                    e.getCause() instanceof InterruptedException);
        }
        // Java's convention: the throw of InterruptedException clears the interrupt status.
        assertFalse("Interrupt status should be cleared by the throw", interruptedAfterThrow[0]);
        assertChildGone(pid);
    }

    @Test(timeout = 20_000)
    public void shouldEndAtOnceWithoutAKeystore_whenKeytoolPrompts() throws Exception {
        String keystorePath = tempFolder.getRoot().toPath().resolve("prompt-keystore.p12").toString();

        long start = System.nanoTime();
        try {
            CertificateGenerator.generate(keystorePath, "pw0123456789abcd", pb -> {
                // Without the env-var option keytool asks for the password on its stdin.
                int option = pb.command().indexOf("-storepass:env");
                assertTrue("The built command no longer passes -storepass:env", option >= 0);
                pb.command().subList(option, option + 2).clear();
            }, CertificateGenerator.KEYTOOL_TIMEOUT_MS);
            fail("Expected an IOException for a keytool that wrote no keystore");
        } catch (IOException e) {
            assertEquals("Keystore file was not created at: " + keystorePath, e.getMessage());
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue("A prompt should meet a closed stdin and end well inside the bound, took " + elapsedMs + " ms",
                elapsedMs < 5_000);
    }

    @Test(timeout = 30_000)
    public void shouldDeleteThePartialKeystore_whenKeytoolDoesNotFinish() throws Exception {
        Path keystore = tempFolder.getRoot().toPath().resolve("partial-keystore.p12");

        try {
            CertificateGenerator.generate(keystore.toString(), "pw0123456789abcd", pb -> {
                try {
                    // Stands in for a keystore the child had begun to write.
                    Files.write(keystore, new byte[] {1, 2, 3});
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                pb.command(pausedJvm().command());
                pb.directory(tempFolder.getRoot());
            }, HANG_BOUND_MS);
            fail("Expected an IOException for a keytool that did not finish");
        } catch (IOException e) {
            assertEquals("keytool did not finish within 2 s and was stopped", e.getMessage());
        }

        assertFalse("A half-written keystore must not be left behind", Files.exists(keystore));
        assertChildGone(pausedPid());
    }

    @Test
    public void shouldReturnWithinThirtySevenSeconds_whateverKeytoolDoes() {
        // The release notes publish this sum: 30 s for keytool plus up to 7 s to stop it and collect its output.
        assertEquals(30_000L, CertificateGenerator.KEYTOOL_TIMEOUT_MS);
        assertEquals(37_000L, CertificateGenerator.KEYTOOL_TIMEOUT_MS + CertificateGenerator.EXIT_GRACE_MS
                + CertificateGenerator.DRAIN_GRACE_MS);
    }

    @Test(timeout = 30_000)
    public void shouldRunTheKeytoolOfTheRunningJavaRuntime_whenGenerating() throws Exception {
        Path expected = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "keytool.exe" : "keytool");
        assertTrue("This test runtime has no keytool at " + expected + ", which the case needs",
                Files.isExecutable(expected));
        String keystorePath = tempFolder.getRoot().toPath().resolve("runtime-keytool.p12").toString();
        String password = "pw0123456789abcd";
        String[] program = new String[1];

        CertificateGenerator.generate(keystorePath, password, pb -> program[0] = pb.command().get(0),
                CertificateGenerator.KEYTOOL_TIMEOUT_MS);

        assertEquals(expected.toString(), program[0]);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, password.toCharArray());
        }
        assertTrue("Keystore should contain 'archi-mcp' alias", ks.containsAlias("archi-mcp"));
    }

    @Test
    public void shouldUseTheRuntimeKeytool_whenItIsExecutable() throws Exception {
        Path javaHome = javaHomeWith("keytool");

        assertEquals(javaHome.resolve("bin").resolve("keytool").toString(),
                CertificateGenerator.keytoolProgram(javaHome.toString(), false));
    }

    @Test
    public void shouldUseTheExeName_whenOnWindows() throws Exception {
        Path javaHome = javaHomeWith("keytool.exe");

        assertEquals(javaHome.resolve("bin").resolve("keytool.exe").toString(),
                CertificateGenerator.keytoolProgram(javaHome.toString(), true));
    }

    @Test
    public void shouldFallBackToPath_whenOnWindowsAndTheRuntimeHasNoExe() throws Exception {
        Path javaHome = javaHomeWith("keytool");

        assertEquals("keytool", CertificateGenerator.keytoolProgram(javaHome.toString(), true));
    }

    @Test
    public void shouldFallBackToPath_whenTheRuntimeHasNoKeytool() throws Exception {
        Path javaHome = javaHomeWith();

        assertEquals("keytool", CertificateGenerator.keytoolProgram(javaHome.toString(), false));
        assertEquals("keytool", CertificateGenerator.keytoolProgram(
                tempFolder.getRoot().toPath().resolve("no-such-runtime").toString(), false));
    }

    @Test
    public void shouldFallBackToPath_whenJavaHomeIsNullOrBlank() {
        assertEquals("keytool", CertificateGenerator.keytoolProgram(null, false));
        assertEquals("keytool", CertificateGenerator.keytoolProgram("", false));
        assertEquals("keytool", CertificateGenerator.keytoolProgram("  ", false));
    }

    @Test
    public void shouldFallBackToPath_whenTheRuntimeKeytoolIsNotExecutable() throws Exception {
        // On Windows every readable file counts as executable, so there is no such file to make.
        Assume.assumeFalse("Windows has no non-executable readable file", WINDOWS);
        Path javaHome = javaHomeWith("keytool");
        Path keytool = javaHome.resolve("bin").resolve("keytool");
        assertTrue(keytool.toFile().setExecutable(false, false));
        assertTrue("Expected the fixture to exist", Files.exists(keytool));

        assertEquals("keytool", CertificateGenerator.keytoolProgram(javaHome.toString(), false));
    }

    @Test
    public void shouldFallBackToPath_whenTheRuntimeKeytoolIsADirectory() throws Exception {
        // A directory with search permission counts as executable, but cannot be started.
        Path javaHome = javaHomeWith();
        Path keytool = Files.createDirectory(javaHome.resolve("bin").resolve(WINDOWS ? "keytool.exe" : "keytool"));
        assertTrue("Expected the fixture to count as executable", Files.isExecutable(keytool));

        assertEquals("keytool", CertificateGenerator.keytoolProgram(javaHome.toString(), WINDOWS));
    }

    @Test(timeout = 30_000)
    public void shouldGenerateWithTheKeytoolOnPath_whenTheRuntimeHasNone() throws Exception {
        Assume.assumeTrue("No keytool on this JVM's PATH", keytoolOnPath());
        Path javaHome = javaHomeWith();
        String keystorePath = tempFolder.getRoot().toPath().resolve("path-keytool.p12").toString();
        String password = "pw0123456789abcd";
        String[] program = new String[1];

        CertificateGenerator.generate(keystorePath, password, javaHome.toString(),
                pb -> program[0] = pb.command().get(0), CertificateGenerator.KEYTOOL_TIMEOUT_MS);

        assertEquals("keytool", program[0]);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, password.toCharArray());
        }
        assertTrue("Keystore should contain 'archi-mcp' alias", ks.containsAlias("archi-mcp"));
    }

    @Test(timeout = 30_000)
    public void shouldSayJavaHomeIsMissing_whenNoKeytoolCanBeStartedWithoutOne() throws Exception {
        for (String javaHome : new String[] {null, " "}) {
            String keystorePath = tempFolder.getRoot().toPath().resolve("no-java-home.p12").toString();
            try {
                CertificateGenerator.generate(keystorePath, "pw0123456789abcd", javaHome,
                        pb -> pb.command().set(0, UNSTARTABLE_PROGRAM), CertificateGenerator.KEYTOOL_TIMEOUT_MS);
                fail("Expected an IOException for a keytool that cannot be started");
            } catch (IOException e) {
                assertEquals("Cannot run keytool: java.home is empty or not set, and \"keytool\" could not be "
                        + "started from PATH", e.getMessage());
            }
        }
    }

    @Test(timeout = 30_000)
    public void shouldNameWhatWasTried_whenNoKeytoolCanBeStarted() throws Exception {
        Path javaHome = javaHomeWith();
        Path keystore = tempFolder.getRoot().toPath().resolve("fallback-keystore.p12");
        String password = "pw0123456789abcd";

        try {
            CertificateGenerator.generate(keystore.toString(), password, javaHome.toString(), pb -> {
                try {
                    // Stands in for a file left at the path; the failure must not leave it there.
                    Files.write(keystore, new byte[] {1, 2, 3});
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                // The test cannot take keytool off this JVM's own PATH, so the fallback's bare
                // name is swapped for one that is on no PATH.
                assertEquals("keytool", pb.command().get(0));
                pb.command().set(0, UNSTARTABLE_PROGRAM);
            }, CertificateGenerator.KEYTOOL_TIMEOUT_MS);
            fail("Expected an IOException for a keytool that cannot be started");
        } catch (IOException e) {
            Path tried = javaHome.resolve("bin").resolve(WINDOWS ? "keytool.exe" : "keytool");
            assertEquals("Cannot run keytool: no executable keytool at " + tried
                    + ", and \"keytool\" could not be started from PATH", e.getMessage());
            assertNotNull("The start failure should be kept as the cause", e.getCause());
            assertTrue("The cause should be the start failure, was " + e.getCause(),
                    e.getCause().getMessage().contains(UNSTARTABLE_PROGRAM));
            assertFalse("The message must not carry the password", e.getMessage().contains(password));
        }

        assertFalse("No keystore file may be left at the path", Files.exists(keystore));
    }

    @Test(timeout = 30_000)
    public void shouldAddNothingToTheStartFailure_whenTheRuntimeKeytoolWasUsed() throws Exception {
        Path javaHome = javaHomeWith(WINDOWS ? "keytool.exe" : "keytool");
        String missing = tempFolder.getRoot().toPath().resolve("missing").resolve("keytool").toString();
        String keystorePath = tempFolder.getRoot().toPath().resolve("runtime-start-keystore.p12").toString();

        try {
            CertificateGenerator.generate(keystorePath, "pw0123456789abcd", javaHome.toString(), pb -> {
                assertEquals(javaHome.resolve("bin").resolve(WINDOWS ? "keytool.exe" : "keytool").toString(),
                        pb.command().get(0));
                pb.command().set(0, missing);
            }, CertificateGenerator.KEYTOOL_TIMEOUT_MS);
            fail("Expected an IOException for a keytool that cannot be started");
        } catch (IOException e) {
            // The JDK's own message already names the absolute path it could not run.
            assertTrue("Expected the JDK's start failure, was " + e.getMessage(),
                    e.getMessage().startsWith("Cannot run program \"" + missing + "\""));
        }
    }

    @Test(timeout = 30_000)
    public void shouldKeepTheTimeoutMessage_whenTheFallbackKeytoolDoesNotFinish() throws Exception {
        Path javaHome = javaHomeWith();
        String keystorePath = tempFolder.getRoot().toPath().resolve("fallback-timeout-keystore.p12").toString();

        try {
            CertificateGenerator.generate(keystorePath, "pw0123456789abcd", javaHome.toString(), pb -> {
                pb.command(pausedJvm().command());
                pb.directory(tempFolder.getRoot());
            }, HANG_BOUND_MS);
            fail("Expected an IOException for a keytool that did not finish");
        } catch (IOException e) {
            assertEquals("keytool did not finish within 2 s and was stopped", e.getMessage());
        }
        assertChildGone(pausedPid());
    }

    @Test
    public void shouldReturnDefaultKeystorePath() {
        String path = CertificateGenerator.getDefaultKeystorePath();

        assertNotNull(path);
        assertTrue("Should contain .archi-mcp", path.contains(".archi-mcp"));
        assertTrue("Should end with keystore.p12", path.endsWith("keystore.p12"));
    }

    /** Short bound for the hanging child; long enough for the child to write its pause file first. */
    private static final long HANG_BOUND_MS = 2_000L;

    /** Slack over the bound for the stop, its exit wait and the output drain. */
    private static final long HANG_MARGIN_MS = 4_000L;

    /**
     * A JVM that pauses before {@code main} until {@code vm.paused.<pid>} in its working directory
     * is deleted: a child that writes nothing, keeps its output open and never exits by itself.
     * The file name carries the child's pid. HotSpot only.
     */
    private ProcessBuilder pausedJvm() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-XX:+UnlockDiagnosticVMOptions", "-XX:+PauseAtStartup",
                "-version")
                .directory(tempFolder.getRoot())
                .redirectErrorStream(true);
        // A "Picked up ..." banner from these would become output and change the exact message.
        pb.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        return pb;
    }

    /** The pid of the one paused child started in the temporary folder. */
    private long pausedPid() {
        String[] files = tempFolder.getRoot().list((dir, name) -> name.startsWith(PAUSE_FILE_PREFIX));
        assertNotNull(files);
        assertEquals("Expected one pause file, found " + Arrays.toString(files)
                + " (none: the child was stopped before it wrote one, a start slower than the bound)",
                1, files.length);
        return Long.parseLong(files[0].substring(PAUSE_FILE_PREFIX.length()));
    }

    private long awaitPausedPid() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String[] files = tempFolder.getRoot().list((dir, name) -> name.startsWith(PAUSE_FILE_PREFIX));
            if (files != null && files.length > 0) {
                return pausedPid();
            }
            Thread.sleep(20);
        }
        fail("The paused child wrote no pause file within 10 s");
        return -1;
    }

    /** Polls up to 2 s for the OS to reap the child: destroyed, not abandoned. */
    private static void assertChildGone(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return;
            }
            Thread.sleep(20);
        }
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        fail("Child " + pid + " is still running: it was abandoned, not stopped");
    }

    private static final String PAUSE_FILE_PREFIX = "vm.paused.";

    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    /** A bare program name that no PATH holds. */
    private static final String UNSTARTABLE_PROGRAM = "archi-mcp-test-no-such-program";

    /** Whether starting the bare name {@code keytool} would find one on this JVM's own PATH. */
    private static boolean keytoolOnPath() {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, WINDOWS ? "keytool.exe" : "keytool"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A directory laid out like a Java runtime, whose {@code bin} holds the named files, each
     * empty and executable. The resolver only checks them; nothing runs them.
     */
    private Path javaHomeWith(String... binFiles) throws IOException {
        Path javaHome = Files.createTempDirectory(tempFolder.getRoot().toPath(), "runtime");
        Path bin = Files.createDirectory(javaHome.resolve("bin"));
        for (String name : binFiles) {
            Path file = Files.createFile(bin.resolve(name));
            assertTrue("Could not make " + file + " executable", file.toFile().setExecutable(true, false));
        }
        return javaHome;
    }
}
