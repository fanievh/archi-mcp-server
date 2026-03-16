package net.vheerden.archi.mcp.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

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
        Path path = Paths.get(keystorePath);
        Files.createDirectories(path.getParent());

        // Delete existing keystore to avoid keytool alias collision
        Files.deleteIfExists(path);

        String password = generatePassword();

        ProcessBuilder pb = new ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias", "archi-mcp",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-storetype", "PKCS12",
                "-keystore", keystorePath,
                "-storepass", password,
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1"
        );
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
