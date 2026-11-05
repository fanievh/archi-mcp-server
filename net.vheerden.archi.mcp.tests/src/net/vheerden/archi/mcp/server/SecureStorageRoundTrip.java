package net.vheerden.archi.mcp.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.junit.Assume;

/**
 * Shared guard for the secure-storage round-trip tests in {@link BearerTokenStoreTest} and
 * {@link KeystorePasswordStoreTest}.
 *
 * <p>Both stores write to {@link SecurePreferencesFactory#getDefault()}, which is whatever store the
 * running launch names with {@code -eclipse.keyring} — or, without that argument, the Equinox default
 * under {@code ~/.eclipse}, which the Eclipse IDE also uses. A round trip therefore runs only when every
 * {@code -eclipse.keyring} on the command line names a store inside the launch's own instance area
 * ({@code osgi.instance.area}, the workspace a PDE JUnit launch clears before each run), never Archi's
 * (Archi.app and an Eclipse runtime launch can both point at
 * {@code ~/Library/Application Support/Archi/secure_storage}, which holds real secrets), and when
 * {@code -eclipse.password} is given (without it a fresh store asks for a master password in a dialog
 * that blocks the run). Otherwise the test is assumption-skipped with the launch arguments to add. The
 * command line is checked before {@code getDefault()} is called, so a refused launch never opens a
 * store at all.</p>
 *
 * <p>The PDE launch this is meant for ({@code AllPluginTestsRunner}) needs, as program arguments:
 * {@code -eclipse.keyring ${workspace_loc}/../junit-workspace/secure_storage} (inside that launch's
 * workspace, so each run creates a fresh store) and
 * {@code -eclipse.password <file holding a dummy password>} (it only works for a store created under
 * it). Not a {@code *Test} class: the headless harness does not run it.</p>
 */
final class SecureStorageRoundTrip {

    /** A keyring path containing this (in any case) is Archi's real secure storage on macOS. */
    static final String ARCHI_KEYRING_MARKER = "library/application support/archi";

    static final String LAUNCH_ARGS_HINT = "add to the launch's program arguments: "
            + "-eclipse.keyring ${workspace_loc}/../junit-workspace/secure_storage "
            + "-eclipse.password <file holding a dummy password>";

    /** The body of a round trip; it may throw anything a test may. */
    interface Body {
        void run() throws Exception;
    }

    private SecureStorageRoundTrip() {
    }

    /**
     * Why the given launch must not run a round trip, or {@code null} when it may.
     *
     * @param eclipseCommands the {@code eclipse.commands} system property: the launch arguments, one per
     *        line ({@code null} outside an Equinox launch)
     * @param instanceArea the {@code osgi.instance.area} system property, a {@code file:} URL or a path
     *        ({@code null} when there is none)
     */
    static String refusalReason(String eclipseCommands, String instanceArea) {
        if (eclipseCommands == null) {
            return "no Equinox launch command line (headless)";
        }
        String[] args = eclipseCommands.split("\\R");
        List<String> keyrings = new ArrayList<>();
        boolean password = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i].strip();
            if (!"-eclipse.keyring".equals(arg) && !"-eclipse.password".equals(arg)) {
                continue;
            }
            String value = i + 1 < args.length ? args[i + 1].strip() : "";
            if (value.isEmpty() || value.startsWith("-")) {
                return arg + " is given without a path";
            }
            if ("-eclipse.keyring".equals(arg)) {
                keyrings.add(value);
            } else {
                password = true;
            }
        }
        if (keyrings.isEmpty()) {
            return "the launch names no -eclipse.keyring, so the round trip would use the shared default store";
        }
        Path area = toPath(instanceArea);
        if (area == null) {
            return "no usable osgi.instance.area (" + instanceArea + ") to keep the keyring inside";
        }
        for (String keyring : keyrings) {
            if (keyring.replace('\\', '/').toLowerCase(Locale.ROOT).contains(ARCHI_KEYRING_MARKER)) {
                return "-eclipse.keyring points at Archi's real secure storage (" + keyring + ")";
            }
            Path store = toPath(keyring);
            if (store == null || !store.startsWith(area) || store.equals(area)) {
                return "-eclipse.keyring " + keyring + " is not inside this launch's instance area " + area;
            }
        }
        if (!password) {
            return "no -eclipse.password, so a fresh store would block the run on a master-password dialog";
        }
        return null;
    }

    /**
     * This plugin's node in an isolated secure store, or an assumption skip that says why there is none.
     */
    static ISecurePreferences isolatedNodeOrSkip(String nodePath) {
        String refusal = refusalReason(System.getProperty("eclipse.commands"),
                System.getProperty("osgi.instance.area"));
        Assume.assumeTrue("secure-storage round trip refused: " + refusal + "; " + LAUNCH_ARGS_HINT,
                refusal == null);

        // Off OSGi getDefault() throws (the internal AuthPlugin is uninitialised) rather than returning null.
        ISecurePreferences root;
        try {
            root = SecurePreferencesFactory.getDefault();
        } catch (Throwable providerUnavailable) {
            Assume.assumeNoException("Equinox secure-storage provider unavailable", providerUnavailable);
            return null; // unreachable: assumeNoException aborts the test as skipped
        }
        Assume.assumeNotNull(root);
        return root.node(nodePath);
    }

    /**
     * Runs {@code body} and then puts back the value {@code key} held before it ({@code null}: absent).
     * A restore failure after a failing body is attached to the body's failure, not reported instead of it.
     */
    static void restoringPrior(ISecurePreferences node, String key, Body body) throws Exception {
        String prior = node.get(key, null);
        try {
            body.run();
        } catch (Throwable bodyFailure) {
            try {
                restore(node, key, prior);
            } catch (Exception restoreFailure) {
                bodyFailure.addSuppressed(restoreFailure);
            }
            throw bodyFailure;
        }
        restore(node, key, prior);
    }

    private static void restore(ISecurePreferences node, String key, String prior)
            throws StorageException, IOException {
        if (prior != null) {
            node.put(key, prior, /* encrypt */ true);
        } else {
            node.remove(key);
        }
        node.flush();
    }

    /** Absolute, normalised, and with symbolic links resolved as far as the path exists. */
    private static Path toPath(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String raw = location.startsWith("file:") ? location.substring("file:".length()) : location;
        Path path;
        try {
            path = Paths.get(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(path));
        } catch (IOException e) {
            return path;
        }
    }
}
