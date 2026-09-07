package net.vheerden.archi.mcp.server;

import static org.junit.Assert.*;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;

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

    @Test
    public void shouldReturnDefaultKeystorePath() {
        String path = CertificateGenerator.getDefaultKeystorePath();

        assertNotNull(path);
        assertTrue("Should contain .archi-mcp", path.contains(".archi-mcp"));
        assertTrue("Should end with keystore.p12", path.endsWith("keystore.p12"));
    }
}
