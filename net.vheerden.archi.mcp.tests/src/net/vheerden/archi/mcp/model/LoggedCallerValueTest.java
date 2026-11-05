package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.NASTY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;
import org.slf4j.Logger;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * A caller's value reaches the Archi log only through {@link CallerText#logged}, and an exception's message only
 * through {@link CallerText#loggedMessage}, so a value holding a line break cannot split a log entry or forge
 * one beneath it. {@code logged} escapes exactly as a refusal's echo does, but whole and without quotes, since an
 * operator needs the full id or path.
 *
 * <p>Every logger is a {@code private static final} field, so a class's own log lines cannot be captured without
 * making them writable. The one line that can be observed is {@link ToolErrorLog}, which takes its logger as a
 * parameter, and every tool error passes through it; it is probed at each of its three levels. Every other line is
 * pinned by a scan of the shipped code: no logger call in {@code model/}, {@code handlers/} or {@code session/}
 * passes a caller's value, or an exception's message, bare.</p>
 */
public class LoggedCallerValueTest {

    /** The names a value taken from a call goes by where it is logged. */
    private static final Set<String> CALLER_VALUES = Set.of(
            "viewId", "sourceViewId", "referencedViewId", "elementId", "relationshipId", "viewObjectId",
            "viewConnectionId", "parentViewObjectId", "groupViewObjectId", "parentId", "groupId", "folderId",
            "targetFolderId", "objectId", "sourceId", "targetId", "connectedId", "id",
            "name", "newName", "effectiveName", "nameFilter", "label", "conceptType", "type", "effectiveType",
            "effectiveLayer", "effectiveSourceLayer", "effectiveTargetLayer", "effectiveViewpoint", "viewpoint",
            "effectiveQuery", "query", "direction", "mode", "effectiveMode", "strategy", "arrangement",
            "normalizedArrangement", "sortBy", "categoryField", "targetRating", "format", "imagePath",
            "clearImagePath", "filePath", "path", "fullPath", "outputDirectory", "filenameHint", "url", "uri",
            "cacheKey", "lruKey", "effectiveExclude", "purpose", "progressToken");

    private static final Pattern LOG_CALL = Pattern.compile("\\blog(?:ger)?\\.(error|warn|info|debug|trace)\\(");
    /** An exception's message, read directly or through a chain such as {@code e.getCause().getMessage()}. */
    private static final Pattern A_MESSAGE = Pattern.compile("\\w+(\\.\\w+\\(\\))*\\.getMessage\\(\\)");
    /** A filter value parsed from the call. */
    private static final Pattern A_FILTER = Pattern.compile("filters\\.\\w+");

    // ---- the form ----

    @Test
    public void logged_shouldEscapeAsTheEchoDoes_butWholeAndUnquoted() {
        List<String> wrong = new ArrayList<>();
        for (String value : NASTY) {
            String logged = CallerText.logged(value);
            String echo = CallerText.echo(value, '\'');
            boolean agrees = value.indexOf('\'') < 0
                    && (value.length() <= CallerText.MAX_ECHOED ? logged.equals(echo) : logged.startsWith(echo));
            if (!agrees) {
                wrong.add(visible(value) + ": logged " + visible(logged) + ", echo " + visible(echo));
            }
            if (!value.equals(unescape(logged))) {
                wrong.add(visible(value) + ": logged " + visible(logged) + " does not read back whole");
            }
            String raw = rawCharacter(logged);
            if (raw != null) {
                wrong.add(visible(value) + ": logged " + visible(logged) + " holds " + raw);
            }
        }
        assertEquals(List.of(), wrong);
        assertEquals("null", CallerText.logged((String) null));
        assertEquals("null", CallerText.logged((Object) null));
        assertEquals("view-1", CallerText.logged("view-1"));
        assertEquals("it's", CallerText.logged("it's"));
        String id = "a".repeat(60) + "\n";
        assertEquals("a".repeat(60) + "\\n", CallerText.logged(id));
        assertEquals("[Note, Group\\nx]", CallerText.logged(List.of("Note", "Group\nx")));
    }

    @Test
    public void loggedMessage_shouldEscapeLineBreaks_butKeepTheMessagesOwnBackslashes() {
        String escapedAlready = "Error deleting element 'p\\nq\\u2028r'";
        assertEquals(escapedAlready, CallerText.loggedMessage(escapedAlready));
        assertEquals("C:\\dir\\file.png not found", CallerText.loggedMessage("C:\\dir\\file.png not found"));
        assertEquals("p\\nq\\r\\tr\\u2028s\\u001B", CallerText.loggedMessage("p\nq\r\tr\u2028s\u001B"));
        assertEquals("null", CallerText.loggedMessage(null));
        List<String> wrong = new ArrayList<>();
        for (String value : NASTY) {
            String raw = rawCharacter(CallerText.loggedMessage(value));
            if (raw != null) {
                wrong.add(visible(value) + " holds " + raw);
            }
        }
        assertEquals(List.of(), wrong);
    }

    // ---- the observable line ----

    @Test
    public void toolErrorLog_shouldWriteOneLine_atEachLevel_whateverTheMessageHolds() {
        List<String> lines = new ArrayList<>();
        Logger log = recording(lines);
        ToolErrorLog.log(log, new ModelAccessException("View not found: p\nq", ErrorCode.VIEW_NOT_FOUND));
        ToolErrorLog.log(log, new ModelAccessException("Cancelled p\nq", new InterruptedException(),
                ErrorCode.INTERNAL_ERROR));
        ToolErrorLog.log(log, new ModelAccessException("Failed p\nq\u2028r", new IllegalStateException(),
                ErrorCode.INTERNAL_ERROR));
        ToolErrorLog.log(log, new ModelAccessException("Error reading 'p\\nq'", new IllegalStateException(),
                ErrorCode.INTERNAL_ERROR));
        assertEquals(List.of(
                "debug Tool error (expected): View not found: p\\nq [VIEW_NOT_FOUND]",
                "warn Tool interrupted, answered INTERNAL_ERROR: Cancelled p\\nq",
                "error Tool failed with INTERNAL_ERROR: Failed p\\nq\\u2028r",
                "error Tool failed with INTERNAL_ERROR: Error reading 'p\\nq'"), lines);
    }

    // ---- every other line ----

    @Test
    public void noLogCall_shouldPassACallersValueOrAMessage_bare() throws IOException {
        List<String> bare = new ArrayList<>();
        int routed = 0;
        int calls = 0;
        for (Map.Entry<String, List<String>> file : EchoToolHarness.shippedCode().entrySet()) {
            String path = file.getKey();
            if (!(path.startsWith("model/") || path.startsWith("handlers/") || path.startsWith("session/"))) {
                continue;
            }
            String code = String.join("\n", file.getValue());
            Matcher m = LOG_CALL.matcher(code);
            while (m.find()) {
                calls++;
                List<String> args = arguments(code, m.end());
                for (String arg : args.subList(1, args.size())) {
                    if (arg.startsWith("CallerText.logged(") || arg.startsWith("CallerText.loggedMessage(")) {
                        routed++;
                    } else if (CALLER_VALUES.contains(arg) || A_MESSAGE.matcher(arg).matches()
                            || A_FILTER.matcher(arg).matches() || arg.contains("args.get(") || arg.equals("request.uri()")) {
                        bare.add(path + ": " + arg);
                    }
                }
            }
        }
        assertTrue(bare.size() + " log arguments pass a caller's value bare:\n" + String.join("\n", bare),
                bare.isEmpty());
        assertTrue("found only " + calls + " log calls", calls > 500);
        assertTrue("found only " + routed + " routed log arguments", routed >= 180);
    }

    /** The top-level arguments of the call whose argument list starts at {@code from}, each stripped. */
    private static List<String> arguments(String code, int from) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inString) {
                if (c == '\\') {
                    current.append(c).append(code.charAt(++i)); // an escape: the next character cannot end the string
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                int close = code.indexOf('\'', i + (code.charAt(i + 1) == '\\' ? 3 : 2));
                current.append(code, i, close + 1); // a char literal, such as '"' or '(', is not code
                i = close;
                continue;
            } else {
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    if (depth == 0) {
                        args.add(current.toString().strip().replaceAll("\\s+", " "));
                        return args;
                    }
                    depth--;
                } else if (c == ',' && depth == 0) {
                    args.add(current.toString().strip().replaceAll("\\s+", " "));
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        throw new AssertionError("unterminated log call at " + from);
    }

    /** A logger that records each call as {@code level message}, its placeholders filled in order. */
    private static Logger recording(List<String> lines) {
        return (Logger) Proxy.newProxyInstance(LoggedCallerValueTest.class.getClassLoader(),
                new Class<?>[] {Logger.class}, (proxy, method, args) -> {
                    if (args != null && args.length > 0 && args[0] instanceof String format
                            && Set.of("error", "warn", "info", "debug", "trace").contains(method.getName())) {
                        String line = format;
                        for (int i = 1; i < args.length; i++) {
                            Object[] values = args[i] instanceof Object[] array ? array : new Object[] {args[i]};
                            for (Object value : values) {
                                if (!(value instanceof Throwable) && line.contains("{}")) {
                                    line = line.replaceFirst("\\{\\}", Matcher.quoteReplacement(
                                            String.valueOf(value)));
                                }
                            }
                        }
                        lines.add(method.getName() + " " + line);
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }

    /** Reverses {@link CallerText#logged}'s escapes. */
    private static String unescape(String logged) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < logged.length(); i++) {
            char c = logged.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char next = logged.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(logged.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    /** The first character a log line must not hold raw, named; {@code null} when there is none. */
    private static String rawCharacter(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < line.length() && Character.isLowSurrogate(line.charAt(i + 1))) {
                i++;
            } else if (Character.isISOControl(c) || Character.isSurrogate(c) || c == 0x2028 || c == 0x2029
                    || Character.getType(c) == Character.FORMAT && c != 0x200C && c != 0x200D) {
                return String.format("U+%04X at %d", (int) c, i);
            }
        }
        return null;
    }

    private static String visible(String s) {
        return CallerText.logged(s);
    }
}
