package net.vheerden.archi.mcp.server;

import static org.junit.Assert.*;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.junit.Test;

/**
 * Tests for {@link KeystorePasswordStore} (the secure-storage helper for the keystore password).
 *
 * <p><b>Pure-vs-PDE split (mirrors {@code BearerTokenStoreTest}).</b> The input-validation guard in
 * {@link KeystorePasswordStore#set(String)} rejects null/blank BEFORE touching secure storage, so it and
 * the distinct-key check run headlessly in {@code ci-junit} (the {@code testsRun > 0} guard). The
 * secure-storage round-trip ({@code set}/{@code get}/{@code clear}) needs the Equinox secure-storage
 * provider, so it runs in the Eclipse PDE {@code AllPluginTestsRunner} lane, and only when that launch
 * names its own throwaway {@code -eclipse.keyring} ({@link SecureStorageRoundTrip}); everywhere else,
 * including every headless lane, it is assumption-skipped by design. This class is therefore NOT in
 * {@code tools/osgi-excluded-tests.txt} and is display-free.</p>
 */
public class KeystorePasswordStoreTest {

    private static final String SAMPLE = "s3cr3t-keystore-pw";

    // ---- Headless: pure input-validation guard (no secure-storage provider needed) ----

    /** A null password is rejected before any storage access (fail fast, no provider required). */
    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNull_whenSetting() {
        KeystorePasswordStore.set(null);
    }

    /** A blank password is rejected before any storage access. */
    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlank_whenSetting() {
        KeystorePasswordStore.set("   ");
    }

    // ---- PDE (AllPluginTestsRunner, isolated keyring): secure-storage round-trip ----

    /**
     * Round-trip: a stored password reads back identically, and {@code clear()} removes it. Runs only in an
     * isolated secure store ({@link SecureStorageRoundTrip#isolatedNodeOrSkip(String)}); any value the key held
     * before is put back afterwards.
     */
    @Test
    public void shouldRoundTripAndClear_whenProviderAvailable() throws Exception {
        ISecurePreferences node = SecureStorageRoundTrip.isolatedNodeOrSkip(KeystorePasswordStore.NODE_PATH);
        SecureStorageRoundTrip.restoringPrior(node, KeystorePasswordStore.KEY_KEYSTORE_PASSWORD, () -> {
            KeystorePasswordStore.set(SAMPLE);
            assertEquals("stored password must read back identically", SAMPLE, KeystorePasswordStore.get());
            KeystorePasswordStore.clear();
            assertNull("after clear(), the keystore password must be absent", KeystorePasswordStore.get());
        });
    }

    /** Distinct key: the keystore-password key must never collide with the bearer-token key. */
    @Test
    public void shouldUseKeyDistinctFromBearerToken() {
        assertNotEquals("keystore-password key must differ from the bearer-token key",
                BearerTokenStore.KEY_TOKEN, KeystorePasswordStore.KEY_KEYSTORE_PASSWORD);
        assertEquals("must share the same secure-storage node as the bearer token",
                BearerTokenStore.NODE_PATH, KeystorePasswordStore.NODE_PATH);
    }
}
