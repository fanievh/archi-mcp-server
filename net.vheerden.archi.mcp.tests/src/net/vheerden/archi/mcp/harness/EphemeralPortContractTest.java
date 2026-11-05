package net.vheerden.archi.mcp.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Keeps the test tree free of probe-then-close port selection.
 *
 * <p>Opening a socket on port 0, reading its port, closing it and only then handing the number to a
 * server leaves a window in which any process on the machine can take the port. A server test asks
 * the server itself for port 0 and reads back what it bound ({@code TransportConfig.getPort()}), so no
 * window exists. This class fails the build if the shape that was removed comes back:</p>
 * <ul>
 *   <li>any call or declaration of a method whose name contains "free" followed by "port", in any
 *       case ({@code freePort}, {@code findFreePort}, {@code getFreeTcpPort}…);</li>
 *   <li>any literal {@code new ServerSocket(0…)} outside the allow-list below. Each allowed site holds
 *       its socket open for as long as its port matters, or wants a port that nothing listens on;</li>
 *   <li>any fixed listen port: a {@code static final int} constant whose name contains "port" (not as
 *       part of "report") and whose value starts with a number of four or more digits, or a
 *       {@code startServer(…)} / {@code .start(…)} call whose first argument starts with one. A fixed
 *       port fails whenever something else on the machine already holds it.</li>
 * </ul>
 * <p>It is a tripwire for those shapes, not a proof that no probe or fixed port exists: a probe
 * written another way (an unbound socket bound to port 0 later, a channel, a constant that happens to
 * be 0), a port held in a local variable or an instance field, and anything split across lines all
 * pass it. A literal in a comment counts like code.</p>
 * <p>Allowed counts are exact and only go down: a site that disappears must be removed from the list
 * in the same change, so the list never permits more than the tree contains.</p>
 */
public class EphemeralPortContractTest {

    private static final String TESTS_SOURCE = "net.vheerden.archi.mcp.tests/src";

    /** A probe helper's name, split so this file never matches its own rule. */
    private static final Pattern PROBE_HELPER =
            Pattern.compile("(?i)\\b\\w*" + "free" + "\\w*port\\w*\\s*\\(");

    private static final Pattern EPHEMERAL_SERVER_SOCKET =
            Pattern.compile("new\\s+(?:java\\.net\\.)?ServerSocket\\s*\\(\\s*0\\s*[,)]");

    /**
     * A constant holding a port-sized number, the shape a test uses to start servers on fixed ports.
     * "port" inside "report" is not a port.
     */
    private static final Pattern FIXED_PORT_CONSTANT = Pattern.compile(
            "\\bstatic\\s+final\\s+int\\s+\\w*(?<!(?i:re))(?i:port)\\w*\\s*=\\s*[1-9][\\d_]{3,}");

    /** A server started on a port-sized int literal, alone or at the head of an expression. */
    private static final Pattern LITERAL_PORT_START =
            Pattern.compile("(?:\\bstartServer|\\.start)\\s*\\(\\s*[1-9][\\d_]{3,}");

    /** File name (relative to the tests source root) to the exact number of allowed sites, with why. */
    private static final Map<String, Integer> ALLOWED = new TreeMap<>(Map.of(
            // Each held open across startServer, so the port is occupied for the whole call: the
            // port-in-use path (its error, and that it leaves no Jetty threads), and the
            // already-running no-op, are exercised without any window.
            "net/vheerden/archi/mcp/server/TransportConfigTest.java", 3,
            // Each held open across the manager's start on its port: the port-conflict error paths,
            // and the already-running no-op asked for a different port, without any window.
            "net/vheerden/archi/mcp/server/McpServerManagerTest.java", 5,
            // Closed on purpose: the test needs a port nothing listens on, and the URL fetch it drives
            // is bounded by its connect and request timeouts, so a stolen port fails it, never hangs it.
            "net/vheerden/archi/mcp/model/ImageDownloadFailureTest.java", 1));

    @Test
    public void shouldFindNoProbeHelper_whenScanningTheTestTree() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path file : javaFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (PROBE_HELPER.matcher(lines.get(i)).find()) {
                    hits.add(relative(file) + ":" + (i + 1) + " -> " + lines.get(i).trim());
                }
            }
        }
        assertTrue("probe-then-close port helpers found — start the server on port 0 and read "
                + "getPort() instead:\n" + String.join("\n", hits), hits.isEmpty());
    }

    @Test
    public void shouldMatchTheAllowList_whenCountingEphemeralServerSockets() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        for (Path file : javaFiles()) {
            Matcher m = EPHEMERAL_SERVER_SOCKET.matcher(Files.readString(file, StandardCharsets.UTF_8));
            int count = 0;
            while (m.find()) {
                count++;
            }
            if (count > 0) {
                found.put(relative(file), count);
            }
        }
        assertEquals("sites of new ServerSocket(0…) per file. A new site must hold its socket open "
                + "while the port is used (or be argued into the allow-list); a removed site must be "
                + "removed from the allow-list too", ALLOWED, found);
    }

    @Test
    public void shouldFindNoFixedListenPort_whenScanningTheTestTree() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path file : javaFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (FIXED_PORT_CONSTANT.matcher(line).find() || LITERAL_PORT_START.matcher(line).find()) {
                    hits.add(relative(file) + ":" + (i + 1) + " -> " + line.trim());
                }
            }
        }
        assertTrue("fixed listen ports found — a port the test picks can already be taken by anything "
                + "on the machine; start the server on port 0 and read getPort() instead:\n"
                + String.join("\n", hits), hits.isEmpty());
    }

    private static List<Path> javaFiles() throws IOException {
        Path root = locateRepoFile(TESTS_SOURCE);
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String relative(Path file) {
        return locateRepoFile(TESTS_SOURCE).relativize(file).toString().replace('\\', '/');
    }

    /** Resolves a repo-relative path by walking upward from the working directory. */
    private static Path locateRepoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the checkout.");
    }
}
