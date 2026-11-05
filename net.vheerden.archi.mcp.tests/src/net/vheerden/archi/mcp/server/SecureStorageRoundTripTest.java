package net.vheerden.archi.mcp.server;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.junit.Test;

/**
 * Headless pins for the rule that decides whether a secure-storage round trip may run
 * ({@link SecureStorageRoundTrip#refusalReason(String, String)}) and for the restore of the prior value.
 * The round trips themselves run only under the isolated PDE launch; the refusal is what keeps every
 * other launch away from a real store.
 */
public class SecureStorageRoundTripTest {

    private static final String AREA = "file:/Users/me/junit-workspace/";
    private static final String LAUNCH_HEAD = "-os\nmacosx\n-ws\ncocoa\n";
    private static final String KEYRING = "-eclipse.keyring\n/Users/me/eclipse-workspace/../junit-workspace/secure_storage\n";
    private static final String PASSWORD = "-eclipse.password\n/Users/me/pw.txt\n";

    @Test
    public void shouldAllow_whenKeyringIsInsideTheInstanceAreaAndAPasswordIsGiven() {
        assertNull(SecureStorageRoundTrip.refusalReason(LAUNCH_HEAD + KEYRING + PASSWORD + "-consoleLog", AREA));
    }

    @Test
    public void shouldRefuse_whenHeadless() {
        assertEquals("no Equinox launch command line (headless)", SecureStorageRoundTrip.refusalReason(null, null));
    }

    @Test
    public void shouldRefuse_whenLaunchNamesNoKeyring() {
        assertRefused("names no -eclipse.keyring", LAUNCH_HEAD + PASSWORD + "-consoleLog", AREA);
    }

    @Test
    public void shouldRefuse_whenKeyringIsArchisRealStore() {
        assertRefused("Archi's real secure storage", LAUNCH_HEAD
                + "-eclipse.keyring\n@user.home/Library/Application Support/Archi/secure_storage\n" + PASSWORD,
                "file:/Users/me/Library/Application Support/Archi/");
    }

    @Test
    public void shouldRefuse_whenArchisStoreIsSpelledInAnotherCase() {
        assertRefused("Archi's real secure storage",
                "-eclipse.keyring\n/Users/me/library/APPLICATION SUPPORT/archi/secure_storage\n" + PASSWORD,
                "file:/Users/me/library/APPLICATION SUPPORT/archi/");
    }

    @Test
    public void shouldRefuse_whenKeyringIsOutsideTheInstanceArea() {
        assertRefused("is not inside this launch's instance area",
                "-eclipse.keyring\n/Users/me/.eclipse/org.eclipse.equinox.security/secure_storage\n" + PASSWORD, AREA);
    }

    @Test
    public void shouldRefuse_whenKeyringClimbsOutOfTheInstanceArea() {
        assertRefused("is not inside this launch's instance area",
                "-eclipse.keyring\n/Users/me/junit-workspace/../other/secure_storage\n" + PASSWORD, AREA);
    }

    @Test
    public void shouldRefuse_whenAnyOfSeveralKeyringsIsOutsideTheInstanceArea() {
        assertRefused("is not inside this launch's instance area", KEYRING + PASSWORD
                + "-eclipse.keyring\n/Users/me/elsewhere/secure_storage\n", AREA);
    }

    @Test
    public void shouldRefuse_whenThereIsNoInstanceArea() {
        assertRefused("no usable osgi.instance.area", KEYRING + PASSWORD, null);
    }

    @Test
    public void shouldRefuse_whenKeyringHasNoPath() {
        assertEquals("-eclipse.keyring is given without a path",
                SecureStorageRoundTrip.refusalReason(LAUNCH_HEAD + PASSWORD + "-eclipse.keyring", AREA));
    }

    @Test
    public void shouldRefuse_whenKeyringIsFollowedByAnotherOption() {
        assertEquals("-eclipse.keyring is given without a path",
                SecureStorageRoundTrip.refusalReason("-eclipse.keyring\n" + PASSWORD, AREA));
    }

    @Test
    public void shouldRefuse_whenNoPasswordIsGiven() {
        assertRefused("no -eclipse.password", LAUNCH_HEAD + KEYRING + "-consoleLog", AREA);
    }

    @Test
    public void shouldPutThePriorValueBack_whenTheBodyChangedIt() throws Exception {
        Map<String, String> values = new HashMap<>(Map.of("k", "real"));
        SecureStorageRoundTrip.restoringPrior(node(values, false), "k", () -> values.put("k", "sample"));
        assertEquals("real", values.get("k"));
    }

    @Test
    public void shouldLeaveTheKeyAbsent_whenItWasAbsentBefore() throws Exception {
        Map<String, String> values = new HashMap<>();
        SecureStorageRoundTrip.restoringPrior(node(values, false), "k", () -> values.put("k", "sample"));
        assertFalse(values.containsKey("k"));
    }

    @Test
    public void shouldReportTheBodyFailure_whenTheRestoreAlsoFails() {
        Map<String, String> values = new HashMap<>();
        AssertionError bodyFailure = new AssertionError("round trip failed");
        try {
            SecureStorageRoundTrip.restoringPrior(node(values, true), "k", () -> {
                throw bodyFailure;
            });
            fail("the body's failure must propagate");
        } catch (AssertionError e) {
            assertSame(bodyFailure, e);
            assertEquals(1, e.getSuppressed().length);
            assertTrue(e.getSuppressed()[0] instanceof IOException);
        } catch (Exception e) {
            fail("the restore's failure replaced the body's: " + e);
        }
    }

    private static void assertRefused(String expectedPart, String commands, String area) {
        String reason = SecureStorageRoundTrip.refusalReason(commands, area);
        assertNotNull("expected a refusal containing: " + expectedPart, reason);
        assertTrue(reason, reason.contains(expectedPart));
    }

    /** A map-backed node; {@code flush()} throws when {@code failFlush}. */
    private static ISecurePreferences node(Map<String, String> values, boolean failFlush) {
        return (ISecurePreferences) Proxy.newProxyInstance(ISecurePreferences.class.getClassLoader(),
                new Class<?>[] {ISecurePreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "get":
                            return values.getOrDefault((String) args[0], (String) args[1]);
                        case "put":
                            values.put((String) args[0], (String) args[1]);
                            return null;
                        case "remove":
                            values.remove((String) args[0]);
                            return null;
                        case "flush":
                            if (failFlush) {
                                throw new IOException("simulated flush failure");
                            }
                            return null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
