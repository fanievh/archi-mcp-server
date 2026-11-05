package net.vheerden.archi.mcp.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Generates self-signed certificates and PKCS12 keystores using the {@code keytool}
 * command-line utility of the Java runtime that runs this code ({@code java.home}).
 *
 * <p>That runtime's {@code bin/keytool} ({@code keytool.exe} on Windows) is run by its absolute
 * path, so neither {@code PATH} nor {@code JAVA_HOME} decides which {@code keytool} writes the
 * keystore. {@code keytool} is looked up on {@code PATH} only when the runtime has no executable
 * {@code keytool} file there.</p>
 *
 * <p>Pure Java utility — no Eclipse/OSGi dependencies. The generated keystore
 * is suitable for Jetty's {@code SslContextFactory.Server}.</p>
 *
 * <p>{@code keytool} runs as a child process with its stdin closed, so a {@code keytool}
 * that asks for input ends at once instead of waiting for an answer. A {@code keytool} that
 * does not finish within {@link #KEYTOOL_TIMEOUT_MS} is stopped, any keystore file at the path is
 * deleted, and the generation fails with an {@link IOException}. Stopping it and collecting its
 * output take at most {@link #EXIT_GRACE_MS} and {@link #DRAIN_GRACE_MS} more, so a call returns
 * within the sum of the three whatever {@code keytool} does.</p>
 */
public class CertificateGenerator {

    /** Default keystore directory relative to user home. */
    private static final String KEYSTORE_DIR = ".archi-mcp";

    /** Default keystore filename. */
    private static final String KEYSTORE_FILENAME = "keystore.p12";

    /**
     * Environment variable carrying the keystore password to {@code keytool}.
     *
     * <p>Only the variable <em>name</em> reaches the command line; the value is read by
     * {@code keytool} from the child process environment.</p>
     */
    private static final String PASSWORD_ENV_VAR = "ARCHI_MCP_KEYSTORE_PASSWORD";

    /**
     * How long {@code keytool} may run before it is stopped and the generation fails. A normal run
     * takes well under a second; the slowest seen took about 9 s.
     */
    static final long KEYTOOL_TIMEOUT_MS = 30_000L;

    /** How long to wait for a forcibly stopped {@code keytool} to exit. */
    static final long EXIT_GRACE_MS = 5_000L;

    /** How long to wait for the rest of the output once {@code keytool} has ended. */
    static final long DRAIN_GRACE_MS = 2_000L;

    private static final String OUTPUT_THREAD_NAME = "archi-mcp-keytool-output";

    private CertificateGenerator() {
        // Utility class
    }

    /**
     * Result of certificate generation.
     *
     * @param keystorePath absolute path to the generated keystore file
     * @param password     the password used to protect the keystore
     */
    public record Result(String keystorePath, String password) {}

    /**
     * Generates a self-signed X.509 certificate and PKCS12 keystore.
     *
     * <p>The certificate uses RSA 2048-bit key, is valid for 365 days,
     * with CN=localhost and SAN=dns:localhost,ip:127.0.0.1.</p>
     *
     * <p>The keystore is saved to {@code ~/.archi-mcp/keystore.p12}.</p>
     *
     * @return the generation result containing keystore path and password
     * @throws IOException if keystore directory creation fails, keytool fails or writes no keystore,
     *         or keytool does not finish within {@link #KEYTOOL_TIMEOUT_MS} and is stopped
     * @throws InterruptedException if the keytool process is interrupted
     */
    public static Result generate() throws IOException, InterruptedException {
        return generate(getDefaultKeystorePath());
    }

    /**
     * Generates a self-signed certificate at the specified keystore path.
     *
     * @param keystorePath absolute path for the keystore file
     * @return the generation result
     * @throws IOException if generation fails, including when keytool does not finish within
     *         {@link #KEYTOOL_TIMEOUT_MS} and is stopped
     * @throws InterruptedException if the keytool process is interrupted
     */
    public static Result generate(String keystorePath) throws IOException, InterruptedException {
        return generate(keystorePath, generatePassword());
    }

    /**
     * Generates a self-signed certificate protected by a caller-supplied password.
     *
     * <p>Package-private seam: lets tests pin a specific password instead of taking
     * whatever {@link #generatePassword()} happens to draw.</p>
     *
     * @param keystorePath absolute path for the keystore file
     * @param password     the password to protect the keystore and its private key; must be non-null
     * @return the generation result
     * @throws IOException if generation fails, including when keytool does not finish within
     *         {@link #KEYTOOL_TIMEOUT_MS} and is stopped
     * @throws InterruptedException if the keytool process is interrupted
     * @throws NullPointerException if {@code password} is null
     */
    static Result generate(String keystorePath, String password) throws IOException, InterruptedException {
        return generate(keystorePath, password, command -> { }, KEYTOOL_TIMEOUT_MS);
    }

    /**
     * Generates as {@link #generate(String, String)} does, with the bound and the {@code keytool}
     * command open to the caller.
     *
     * <p>Package-private seam: lets tests change the built command just before it starts (to make
     * {@code keytool} prompt, or to run a child that never ends) and use a short bound.</p>
     *
     * @param keystorePath  absolute path for the keystore file
     * @param password      the password to protect the keystore and its private key; must be non-null
     * @param adjustCommand applied to the fully built command just before it starts
     * @param timeoutMs     how long keytool may run before it is stopped, in milliseconds
     * @return the generation result
     * @throws IOException if generation fails, including when keytool does not finish within
     *         {@code timeoutMs} and is stopped, or when the runtime has no {@code keytool} and the one
     *         on {@code PATH} cannot be started either (the message names both); when running keytool
     *         fails this way, any keystore file at the path is deleted (a delete that fails is attached
     *         to the exception as suppressed)
     * @throws InterruptedException if the keytool process is interrupted; it is stopped and any
     *         keystore file at the path deleted first
     * @throws NullPointerException if {@code password} is null
     */
    static Result generate(String keystorePath, String password, Consumer<ProcessBuilder> adjustCommand,
            long timeoutMs) throws IOException, InterruptedException {
        return generate(keystorePath, password, System.getProperty("java.home"), adjustCommand, timeoutMs);
    }

    /**
     * Generates as {@link #generate(String, String, Consumer, long)} does, taking {@code keytool}
     * from the given Java runtime directory instead of {@code java.home}.
     *
     * <p>Package-private seam: lets tests point the generator at a runtime directory that has no
     * {@code keytool}.</p>
     *
     * @param javaHome the Java runtime directory whose {@code bin} is searched for {@code keytool};
     *                 null or blank means none
     * @throws IOException as {@link #generate(String, String, Consumer, long)}; when the runtime had
     *         no {@code keytool} and the one on {@code PATH} cannot be started either, the message
     *         names both, and the start failure is the cause
     */
    static Result generate(String keystorePath, String password, String javaHome,
            Consumer<ProcessBuilder> adjustCommand, long timeoutMs) throws IOException, InterruptedException {
        // Fail here rather than several lines later inside the environment map, where a null
        // surfaces as an opaque NullPointerException from java.lang with no mention of a password.
        Objects.requireNonNull(password, "password");

        Path path = Paths.get(keystorePath);
        Files.createDirectories(path.getParent());

        // Delete existing keystore to avoid keytool alias collision
        Files.deleteIfExists(path);

        boolean windows = isWindows();
        String keytool = keytoolProgram(javaHome, windows);
        ProcessBuilder pb = new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias", "archi-mcp",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-storetype", "PKCS12",
                "-keystore", keystorePath,
                "-storepass:env", PASSWORD_ENV_VAR,
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1"
        );
        // The password travels in the environment, never in argv: the JDK launcher pre-scans argv
        // and consumes any argument beginning with "-J" as a JVM option before keytool's own parser
        // runs, so a password drawn from the Base64 URL alphabet (which includes '-') could be eaten
        // and its remainder handed to the JVM as a main class name. keytool derives the PKCS12 key
        // password from the store password, so no -keypass is needed.
        pb.environment().put(PASSWORD_ENV_VAR, password);
        pb.redirectErrorStream(true);
        adjustCommand.accept(pb);

        KeytoolRun run;
        try {
            run = runBounded(start(pb, keytool, javaHome, windows), timeoutMs);
        } catch (IOException | InterruptedException e) {
            // A stopped keytool may have left a half-written keystore where a TLS start would read it,
            // and whatever failed, the password is never returned, so a keystore left here could not
            // be opened.
            try {
                Files.deleteIfExists(path);
            } catch (IOException deleteFailure) {
                e.addSuppressed(deleteFailure);
            }
            throw e;
        }

        if (run.exitCode() != 0) {
            throw new IOException("keytool failed (exit code " + run.exitCode() + "): " + run.output().trim());
        }

        if (!Files.exists(path)) {
            throw new IOException("Keystore file was not created at: " + keystorePath);
        }

        return new Result(keystorePath, password);
    }

    /**
     * Runs a child process with its stdin closed, collecting its output on a daemon thread, and
     * waits at most {@code timeoutMs} for it to exit.
     *
     * <p>A child still running when the bound runs out, or when the calling thread is interrupted,
     * is stopped forcibly, and its exit is awaited for at most {@link #EXIT_GRACE_MS} (an interrupt
     * does not cut that wait short), before this method throws. After the child ends, the output is
     * awaited for at most {@link #DRAIN_GRACE_MS}; output a process outliving the child still holds
     * open is reported as possibly incomplete instead of being waited for. So the call returns within
     * {@code timeoutMs} plus both grace periods.</p>
     *
     * @param pb        the configured builder; its output should already be merged with its errors
     * @param timeoutMs how long to wait for the child to exit, in milliseconds
     * @return the child's exit code and output
     * @throws IOException if the child cannot be started, does not finish within the bound, or its
     *         output cannot be read. On the bound, the message says whether the stopped child was seen
     *         to exit; an interrupt that arrives while it is being stopped leaves the interrupt status
     *         set and does not replace this exception
     * @throws InterruptedException if the calling thread is interrupted while waiting; the child is
     *         stopped first. The interrupt status is cleared by the throw, as by any blocking call,
     *         unless another interrupt arrived while the child was being stopped, which is kept
     */
    static KeytoolRun runBounded(ProcessBuilder pb, long timeoutMs) throws IOException, InterruptedException {
        return runBounded(pb.start(), timeoutMs);
    }

    /** Runs as {@link #runBounded(ProcessBuilder, long)} does, on a child already started. */
    private static KeytoolRun runBounded(Process process, long timeoutMs) throws IOException, InterruptedException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FutureTask<Void> drain = new FutureTask<>(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            }
            return null;
        });
        boolean exited = false;
        boolean stopped = true;
        try {
            // A child that asks for input meets end-of-input at once, not a pipe held open for ever.
            process.getOutputStream().close();
            Thread drainer = new Thread(drain, OUTPUT_THREAD_NAME);
            drainer.setDaemon(true);
            drainer.start();
            exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            if (!exited) {
                stopped = stopWithinBound(process);
            }
        }
        if (!exited) {
            String partial;
            try {
                partial = awaitOutput(drain, output).trim();
            } catch (IOException e) {
                partial = output.toString().trim();
            } catch (InterruptedException e) {
                // The timeout is the failure to report; keep the interrupt for the caller.
                Thread.currentThread().interrupt();
                partial = output.toString().trim();
            }
            throw new IOException("keytool did not finish within " + seconds(timeoutMs) + " s and "
                    + (stopped ? "was stopped" : "had not exited " + seconds(EXIT_GRACE_MS) + " s after being stopped")
                    + (partial.isEmpty() ? "" : ": " + partial));
        }
        return new KeytoolRun(process.exitValue(), awaitOutput(drain, output));
    }

    /** Exit code and merged output of a finished child. */
    record KeytoolRun(int exitCode, String output) {}

    /**
     * Stops the child forcibly and waits at most {@link #EXIT_GRACE_MS} for it to exit, so a file it
     * held open is released before the caller deletes it. An interrupt during the wait does not cut
     * it short; it is kept in the thread's interrupt status.
     *
     * @return true if the child exited within the wait
     */
    private static boolean stopWithinBound(Process process) {
        process.destroyForcibly();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXIT_GRACE_MS);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return process.waitFor(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String awaitOutput(FutureTask<Void> drain, ByteArrayOutputStream output)
            throws IOException, InterruptedException {
        try {
            drain.get(DRAIN_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException("Could not read keytool output", e.getCause());
        } catch (TimeoutException e) {
            return output.toString() + " (output may be incomplete: still open " + seconds(DRAIN_GRACE_MS)
                    + " s after keytool ended)";
        }
        return output.toString();
    }

    private static String seconds(long millis) {
        return BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString();
    }

    /** The bare name, which starting a child resolves from {@code PATH}. */
    static final String PATH_KEYTOOL = "keytool";

    /**
     * Returns the {@code keytool} to run: the absolute path of {@code bin/keytool} (or
     * {@code bin/keytool.exe} on Windows) under {@code javaHome} when that is an executable regular
     * file, otherwise {@link #PATH_KEYTOOL}.
     *
     * @param javaHome the Java runtime directory; null or blank means none
     * @param windows  whether the executable is named {@code keytool.exe}
     */
    static String keytoolProgram(String javaHome, boolean windows) {
        Path runtimeKeytool = runtimeKeytool(javaHome, windows);
        return runtimeKeytool != null && Files.isRegularFile(runtimeKeytool) && Files.isExecutable(runtimeKeytool)
                ? runtimeKeytool.toString()
                : PATH_KEYTOOL;
    }

    /** Where {@code javaHome} would hold {@code keytool}, or null when there is no {@code javaHome}. */
    private static Path runtimeKeytool(String javaHome, boolean windows) {
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }
        return Path.of(javaHome, "bin", windows ? "keytool.exe" : "keytool");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    /**
     * Starts {@code keytool}. When {@code keytool} is the {@link #PATH_KEYTOOL} fallback and cannot be
     * started, the failure names where the runtime's own {@code keytool} was looked for as well; a
     * runtime {@code keytool} that cannot be started already has its absolute path in the message.
     */
    private static Process start(ProcessBuilder pb, String keytool, String javaHome, boolean windows)
            throws IOException {
        try {
            return pb.start();
        } catch (IOException e) {
            if (!PATH_KEYTOOL.equals(keytool)) {
                throw e;
            }
            Path tried = runtimeKeytool(javaHome, windows);
            throw new IOException("Cannot run keytool: "
                    + (tried == null ? "java.home is empty or not set" : "no executable keytool at " + tried)
                    + ", and \"" + PATH_KEYTOOL + "\" could not be started from PATH", e);
        }
    }

    /**
     * Returns the default keystore path: {@code ~/.archi-mcp/keystore.p12}.
     */
    static String getDefaultKeystorePath() {
        return Paths.get(System.getProperty("user.home"), KEYSTORE_DIR, KEYSTORE_FILENAME)
                .toAbsolutePath().toString();
    }

    /**
     * Generates a random 16-character alphanumeric password.
     */
    static String generatePassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
