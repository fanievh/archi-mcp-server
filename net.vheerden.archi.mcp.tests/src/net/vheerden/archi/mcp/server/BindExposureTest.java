package net.vheerden.archi.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Pins the one answer to "is this bind exposed?" and each consumer's closed mapping of a bind that
 * cannot be classified: the Origin/Host guard enforces, server file access is refused, and the
 * preference page does not warn (the address field shows its own error). Every resolvable bind
 * classifies as it always did.
 */
public class BindExposureTest {

    /** Every bind the table covers; the guard's matrix test walks the same list. */
    static final String[] BINDS = {"127.0.0.1", "127.0.0.2", "localhost", "::1", "0.0.0.0", "::", "192.168.1.10",
            "8.8.8.8", null, "", "  ", "no.such.host.invalid"};

    private static final Map<String, BindExposure> EXPECTED = new LinkedHashMap<>();
    static {
        for (String loopback : new String[] {"127.0.0.1", "127.0.0.2", "localhost", "::1"}) {
            EXPECTED.put(loopback, BindExposure.LOOPBACK);
        }
        for (String exposed : new String[] {"0.0.0.0", "::", "192.168.1.10", "8.8.8.8"}) {
            EXPECTED.put(exposed, BindExposure.EXPOSED);
        }
        for (String unknown : new String[] {null, "", "  ", "no.such.host.invalid"}) {
            EXPECTED.put(unknown, BindExposure.UNKNOWN);
        }
    }

    @Test
    public void shouldClassifyEveryBind_asLoopbackExposedOrUnknown() {
        assertEquals("the table covers every bind", BINDS.length, EXPECTED.size());
        List<String> wrong = new ArrayList<>();
        for (String bind : BINDS) {
            if (BindExposure.of(bind) != EXPECTED.get(bind)) {
                wrong.add("[" + bind + "] expected " + EXPECTED.get(bind) + " but was " + BindExposure.of(bind));
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldMapEachClassification_toEachConsumersAnswer() {
        assertTrue(BindExposure.LOOPBACK.enforcesOriginHost());
        assertTrue(BindExposure.LOOPBACK.allowsServerFileAccess());
        assertFalse(BindExposure.LOOPBACK.warnsOperator());

        assertFalse(BindExposure.EXPOSED.enforcesOriginHost());
        assertFalse(BindExposure.EXPOSED.allowsServerFileAccess());
        assertTrue(BindExposure.EXPOSED.warnsOperator());

        assertTrue("the guard's closed answer is to enforce", BindExposure.UNKNOWN.enforcesOriginHost());
        assertFalse("file access's closed answer is to refuse", BindExposure.UNKNOWN.allowsServerFileAccess());
        assertFalse(BindExposure.UNKNOWN.warnsOperator());
    }

    @Test
    public void shouldRefuseServerFileAccess_onEveryBindThatIsNotLoopback_andWarnIffTheGuardIsOff() {
        List<String> wrong = new ArrayList<>();
        for (String bind : BINDS) {
            BindExposure exposure = BindExposure.of(bind);
            if (exposure.allowsServerFileAccess() != (exposure == BindExposure.LOOPBACK)) {
                wrong.add("file access on [" + bind + "]");
            }
            if (exposure.warnsOperator() != !exposure.enforcesOriginHost()) {
                wrong.add("warning vs guard on [" + bind + "]");
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldResolveAsOfDoes_andThrowOnlyForANamedBindThatDoesNotResolve() throws Exception {
        for (String bind : BINDS) {
            if (bind != null && !bind.isEmpty() && BindExposure.of(bind) == BindExposure.UNKNOWN) {
                try {
                    BindExposure.resolve(bind);
                    throw new AssertionError("resolve did not throw for [" + bind + "]");
                } catch (java.net.UnknownHostException expected) {
                    // the start path refuses this bind
                }
            } else {
                assertEquals("[" + bind + "]", BindExposure.of(bind), BindExposure.resolve(bind));
            }
        }
    }

    /**
     * One lookup decides exposure: no other class in the server or UI layers resolves a host name, so
     * no second lookup can give the guard and the file-access decision different answers.
     */
    @Test
    public void shouldLookUpABindOnlyInTheClassifier_acrossTheServerAndUiLayers() throws IOException {
        Path src = repoRoot().resolve("net.vheerden.archi.mcp/src/net/vheerden/archi/mcp");
        Pattern lookup = Pattern.compile("\\bInetAddress\\s*\\.\\s*get(?:All)?ByName\\b");
        List<String> found = new ArrayList<>();
        for (String layer : new String[] {"server", "ui"}) {
            try (Stream<Path> files = Files.walk(src.resolve(layer))) {
                for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    Matcher m = lookup.matcher(Files.readString(file, StandardCharsets.UTF_8));
                    while (m.find()) {
                        found.add(src.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
        }
        assertEquals(List.of("server/BindExposure.java"), found);
    }

    /** The server classifies once as it binds, and the file-access refusal reads that classification. */
    @Test
    public void shouldJudgeServerFileAccess_byTheClassificationTheServerMadeAsItBound() throws IOException {
        Path server = repoRoot().resolve("net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/server");
        String transport = Files.readString(server.resolve("TransportConfig.java"), StandardCharsets.UTF_8);
        assertEquals("one classification in the start path", 1, count(transport, "BindExposure.resolve(bindAddress)"));
        assertEquals(0, count(transport, "BindExposure.of("));
        assertTrue(transport.contains("authToken), bindAddress, exposure));"));
        assertTrue(transport.contains("activeBindExposure = exposure;"));
        String manager = Files.readString(server.resolve("McpServerManager.java"), StandardCharsets.UTF_8);
        assertEquals(0, count(manager, "BindExposure.of("));
        assertEquals(1, count(manager, "transportConfig.getBindExposure()"));
        String guard = Files.readString(server.resolve("OriginHostValidationHandler.java"), StandardCharsets.UTF_8);
        assertTrue(guard.contains("public OriginHostValidationHandler(Handler wrapped, String bindAddress, BindExposure exposure)"));
        assertTrue("the two-argument constructor is not for the server", Arrays.stream(guard.split("\n"))
                .noneMatch(l -> l.contains("public OriginHostValidationHandler(Handler wrapped, String bindAddress) {")));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("net.vheerden.archi.mcp/src"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate the repository above " + Path.of("").toAbsolutePath());
    }
}
