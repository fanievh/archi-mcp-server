package net.vheerden.archi.mcp;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.Test;

/**
 * Tests for the pure, secure-storage-free keystore-password migration policy
 * {@link McpPlugin#migrateKeystorePassword(String, Supplier, Consumer, Runnable)}.
 *
 * <p>The policy is package-visible with injectable seams (mirroring
 * {@code TransportConfig.resolveAuthToken(boolean, Supplier)}) precisely so the
 * <b>never-lose-data</b> property can be proven HEADLESSLY in the {@code ci-junit} lane —
 * no live plugin, no secure-storage provider, no display. This class therefore stays out of
 * {@code tools/osgi-excluded-tests.txt}.</p>
 *
 * <p>The most important test here is {@link #shouldNotClearLegacy_whenSecureWriteFails()}: a failed
 * migration must NEVER clear the legacy plaintext value (that would destroy a working TLS password on
 * a machine whose keychain master-password entry was lost — the {@code errSecItemNotFound -25300}
 * scenario).</p>
 */
public class KeystorePasswordMigrationTest {

    private static final String LEGACY = "legacy-keystore-pw";

    /** Happy path: legacy present + secure empty → write it, THEN clear the legacy value. */
    @Test
    public void shouldMigrateAndClear_whenLegacyPresentAndSecureEmpty() {
        AtomicReference<String> written = new AtomicReference<>();
        AtomicBoolean cleared = new AtomicBoolean(false);

        boolean migrated = McpPlugin.migrateKeystorePassword(
                LEGACY,
                () -> null,                 // secure storage empty
                written::set,               // record the written value
                () -> cleared.set(true));   // record the clear

        assertTrue("should report a migration occurred", migrated);
        assertEquals("the exact legacy value must be written to secure storage", LEGACY, written.get());
        assertTrue("the legacy plain value must be cleared after a durable write", cleared.get());
    }

    /**
     * THE anti-data-loss test: when the secure write throws, the legacy value MUST NOT be
     * cleared and nothing new is persisted — the next start retries.
     */
    @Test
    public void shouldNotClearLegacy_whenSecureWriteFails() {
        AtomicBoolean cleared = new AtomicBoolean(false);

        boolean migrated = McpPlugin.migrateKeystorePassword(
                LEGACY,
                () -> null,                 // secure storage empty
                value -> { throw new RuntimeException("simulated errSecItemNotFound -25300"); },
                () -> cleared.set(true));

        assertFalse("a failed write must not report a migration", migrated);
        assertFalse("the legacy plaintext value MUST be left intact on write failure", cleared.get());
    }

    /**
     * When secure storage is unreadable (reader throws), do not write, do not clear — leave the
     * legacy value in place for retry.
     */
    @Test
    public void shouldNotClearOrWrite_whenSecureReadFails() {
        AtomicBoolean written = new AtomicBoolean(false);
        AtomicBoolean cleared = new AtomicBoolean(false);

        boolean migrated = McpPlugin.migrateKeystorePassword(
                LEGACY,
                () -> { throw new RuntimeException("secure storage unreadable"); },
                value -> written.set(true),
                () -> cleared.set(true));

        assertFalse(migrated);
        assertFalse("must not write when the store is unreadable", written.get());
        assertFalse("must not clear the legacy value when the store is unreadable", cleared.get());
    }

    /**
     * Post-write clear failure: the secure write (the security goal) already succeeded, so the
     * migration still reports success and does NOT propagate the cleaner's exception out of the policy
     * (it would otherwise be misreported by the start() wrapper). The legacy value may persist, but the
     * password is durably in secure storage.
     */
    @Test
    public void shouldReportSuccess_whenWriteSucceedsButLegacyClearThrows() {
        AtomicReference<String> written = new AtomicReference<>();

        boolean migrated = McpPlugin.migrateKeystorePassword(
                LEGACY,
                () -> null,
                written::set,
                () -> { throw new RuntimeException("simulated preference-store clear failure"); });

        assertTrue("a successful secure write must still report migration even if the clear throws", migrated);
        assertEquals("the value must have been written to secure storage", LEGACY, written.get());
    }

    /** Idempotency: a secure value already exists → no overwrite, no clear, no-op. */
    @Test
    public void shouldNoOp_whenSecureAlreadyPopulated() {
        AtomicBoolean written = new AtomicBoolean(false);
        AtomicBoolean cleared = new AtomicBoolean(false);

        boolean migrated = McpPlugin.migrateKeystorePassword(
                LEGACY,
                () -> "already-migrated-value",   // secure storage already has a value
                value -> written.set(true),
                () -> cleared.set(true));

        assertFalse(migrated);
        assertFalse("must never overwrite an existing secure value", written.get());
        assertFalse("must not clear the legacy value when a secure value already exists", cleared.get());
    }

    /** Idempotency: nothing to migrate when the legacy value is blank — short-circuits before any seam. */
    @Test
    public void shouldNoOp_whenLegacyBlank() {
        AtomicBoolean readerCalled = new AtomicBoolean(false);
        AtomicBoolean written = new AtomicBoolean(false);
        AtomicBoolean cleared = new AtomicBoolean(false);

        Supplier<String> reader = () -> { readerCalled.set(true); return null; };
        Consumer<String> writer = value -> written.set(true);
        Runnable cleaner = () -> cleared.set(true);

        assertFalse("blank legacy → no-op", McpPlugin.migrateKeystorePassword("   ", reader, writer, cleaner));
        assertFalse("null legacy → no-op", McpPlugin.migrateKeystorePassword(null, reader, writer, cleaner));

        assertFalse("must not even read secure storage when there is nothing to migrate", readerCalled.get());
        assertFalse(written.get());
        assertFalse(cleared.get());
    }
}
