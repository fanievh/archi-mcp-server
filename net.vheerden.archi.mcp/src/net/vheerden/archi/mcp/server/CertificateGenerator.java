package net.vheerden.archi.mcp.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Generates self-signed certificates and PKCS12 keystores using the JDK's
 * {@code keytool} command-line utility.
 *
 * <p>Pure Java utility — no Eclipse/OSGi dependencies. The generated keystore
 * is suitable for Jetty's {@code SslContextFactory.Server}.</p>
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
     * @throws IOException if keystore directory creation fails or keytool execution fails
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
     * @throws IOException if generation fails
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
     * @throws IOException if generation fails
     * @throws InterruptedException if the keytool process is interrupted
     * @throws NullPointerException if {@code password} is null
     */
    static Result generate(String keystorePath, String password) throws IOException, InterruptedException {
        // Fail here rather than several lines later inside the environment map, where a null
        // surfaces as an opaque NullPointerException from java.lang with no mention of a password.
        Objects.requireNonNull(password, "password");

        Path path = Paths.get(keystorePath);
        Files.createDirectories(path.getParent());

        // Delete existing keystore to avoid keytool alias collision
        Files.deleteIfExists(path);

        ProcessBuilder pb = new ProcessBuilder(
                "keytool",
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

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("keytool failed (exit code " + exitCode + "): " + output.trim());
        }

        if (!Files.exists(path)) {
            throw new IOException("Keystore file was not created at: " + keystorePath);
        }

        return new Result(keystorePath, password);
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
