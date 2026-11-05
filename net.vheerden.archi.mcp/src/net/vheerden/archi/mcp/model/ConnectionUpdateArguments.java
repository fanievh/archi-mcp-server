package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * The one rule for which arguments {@code update-view-connection} reads, shared by the standalone
 * handler and by {@code bulk-mutate}'s per-operation read. Its {@link #labelPosition} reader also
 * serves {@code add-connection-to-view}, and its {@link #showLabel} reader serves
 * {@code add-connection-to-view} and {@code auto-connect-view}, each on every path it has, so no tool
 * or path keeps a parser of its own.
 *
 * <p>A call that sets nothing straightens the connection: that is the tool's published default. Both
 * paths used to decide "sets nothing" from what their parsers returned rather than from the keys
 * they were given, and the parsers return nothing for a key they do not know or a value of the
 * wrong type. So a misspelled styling field, another tool's key, or {@code lineWidth: "2"} each
 * erased every stored bendpoint behind a success response. No JSON-schema validator stands in front
 * of either path (see {@link ParamNameDiagnostics}), so the rule has to be applied here.</p>
 *
 * <p>The rule, decided by the keys present:</p>
 * <ul>
 *   <li>a key outside {@link #ACCEPTED} is refused, every such key named at once;</li>
 *   <li>an empty {@code bendpoints} or {@code absoluteBendpoints} array clears the route, whatever
 *       else the call sets;</li>
 *   <li>a call with no key besides {@code viewConnectionId} and {@code modelId} clears the route;</li>
 *   <li>a call that carries accepted keys and yet reads nothing to change is refused, naming them
 *       and the type each expects;</li>
 *   <li>every other call must read every argument it sends, by that key's own rule: a key that does
 *       not read (a value of the wrong type, a blank {@code fontStyle}, JSON null) is refused for the
 *       whole call, even beside one that reads, so nothing is applied in part.</li>
 * </ul>
 */
public final class ConnectionUpdateArguments {

    /** The keys the tool declares in its served input schema, in the order they are listed. */
    public static final List<String> DECLARED = List.of(
            "viewConnectionId", "bendpoints", "absoluteBendpoints",
            "lineColor", "fontColor", "lineWidth", "fontName", "fontSize", "fontStyle",
            "showLabel", "labelPosition", "modelId");

    /**
     * Every key the tool reads. {@code lineStyle} is undeclared but accepted so that it reaches its
     * own value-independent refusal, which says why a connection has no line style; answering it as
     * an unknown key would say less.
     */
    public static final List<String> ACCEPTED = concat(DECLARED, "lineStyle");

    /** The {@code labelPosition} values, each at the index Archi stores as its text position. */
    private static final List<String> LABEL_POSITIONS = List.of("source", "middle", "target");

    /** Keys that address the call rather than ask for a change. */
    private static final List<String> ADDRESSING = List.of("viewConnectionId", "modelId");

    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("bendpoints", "an array of {startX, startY, endX, endY}"),
            Map.entry("absoluteBendpoints", "an array of {x, y}"),
            Map.entry("lineColor", "a #RRGGBB string, or \"\" to clear"),
            Map.entry("fontColor", "a #RRGGBB string, or \"\" to clear"),
            Map.entry("lineWidth", "an integer from 1 to 3"),
            Map.entry("fontName", "a string, or \"\" to clear"),
            Map.entry("fontSize", "a positive integer"),
            Map.entry("fontStyle", "one of normal, bold, italic, bold-italic"),
            Map.entry("showLabel", "true or false"),
            Map.entry("labelPosition", "one of source, middle, target"),
            Map.entry("lineStyle", "nothing: a connection's line style follows its relationship type"));

    private ConnectionUpdateArguments() {
    }

    /**
     * Refuses every key the tool does not read, by the rule every tool shares ({@link ToolArguments}).
     * Runs after the required {@code viewConnectionId} is read, so a call that spelled the id another
     * tool's way is told that spelling first.
     */
    public static void refuseUnknownKeys(Map<String, ?> args) {
        ToolArguments.refuseUnknown(ToolArguments.knownShape("update-view-connection"), args);
    }

    /**
     * {@code showLabel} as a boolean, or the string {@code "true"} or {@code "false"} in any case;
     * {@code null} when absent or null. Any other value is refused rather than read as false.
     */
    public static Boolean showLabel(Map<String, ?> args) {
        Object value = args.get("showLabel");
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
            return Boolean.valueOf(s);
        }
        throw new ModelAccessException(
                "showLabel must be true or false, got " + describe(value) + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass showLabel: false to hide the relationship name, or true to show it.", null);
    }

    /**
     * {@code labelPosition} as Archi's text position: 0, 1 or 2 for source, middle or target in any
     * case; {@code null} when absent or null. {@code add-connection-to-view} reads it here as well, on
     * both paths. Any other value is refused rather than answered as a server fault.
     */
    public static Integer labelPosition(Map<String, ?> args) {
        Object value = args.get("labelPosition");
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            // Locale.ROOT: under a Turkish default locale "MIDDLE" lower-cases to a dotless i.
            int position = LABEL_POSITIONS.indexOf(s.toLowerCase(Locale.ROOT));
            if (position >= 0) {
                return position;
            }
        }
        throw new ModelAccessException(
                "Invalid labelPosition: expected source, middle or target, got " + describe(value)
                        + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass labelPosition: \"source\", \"middle\" or \"target\", or omit it.", null);
    }

    /**
     * The relative bendpoints to hand the accessor: an empty list when the call clears the route,
     * otherwise {@code parsed} unchanged ({@code null} leaves the route as it is).
     *
     * @param parsed       the relative bendpoints the caller's parser read, {@code null} for none
     * @param readsAChange whether the parsers read anything to change from the call
     * @throws ModelAccessException INVALID_PARAMETER when an empty array of one format sits beside any
     *         value of the other format but an empty array, when accepted keys are present and read
     *         as nothing, or when any present key does not read by its own rule
     */
    public static <T> List<T> bendpointsToApply(Map<String, ?> args, List<T> parsed, boolean readsAChange) {
        Object relative = args.get("bendpoints");
        Object absolute = args.get("absoluteBendpoints");
        boolean clearsRelative = relative instanceof List<?> l && l.isEmpty();
        boolean clearsAbsolute = absolute instanceof List<?> l && l.isEmpty();
        // The other format beside an explicit clear is a second instruction about the same route:
        // refused whatever it holds, so a value the parsers cannot read is not cleared over in silence.
        if ((clearsRelative && absolute != null && !clearsAbsolute)
                || (clearsAbsolute && relative != null && !clearsRelative)) {
            throw new ModelAccessException(
                    "Cannot provide both 'bendpoints' and 'absoluteBendpoints'",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Use either relative bendpoints (startX/startY/endX/endY) or absolute "
                            + "bendpoints ({x, y}), not both",
                    null);
        }
        List<String> unreadable = new ArrayList<>();
        for (String key : ACCEPTED) {
            if (args.containsKey(key) && !ADDRESSING.contains(key) && !reads(key, args.get(key))) {
                unreadable.add(key + " (expected " + EXPECTED.get(key) + ", got " + describe(args.get(key)) + ")");
            }
        }
        if (!unreadable.isEmpty() && (readsAChange || clearsRelative || clearsAbsolute)) {
            throw new ModelAccessException(
                    "update-view-connection cannot read " + String.join("; ", unreadable)
                            + ". Every argument sent must read, so nothing was changed, and the route was kept.",
                    ErrorCode.INVALID_PARAMETER, null, "Pass each value as the type named, or omit it.", null);
        }
        if (clearsRelative || clearsAbsolute) {
            return List.of();
        }
        if (readsAChange) {
            return parsed;
        }
        List<String> unread = new ArrayList<>();
        for (String key : ACCEPTED) {
            if (args.containsKey(key) && !ADDRESSING.contains(key)) {
                unread.add(key);
            }
        }
        if (unread.isEmpty()) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        for (String key : unread) {
            problems.add(key + " (expected " + EXPECTED.get(key) + ", got " + describe(args.get(key)) + ")");
        }
        throw new ModelAccessException(
                "update-view-connection read nothing to change from " + String.join("; ", problems)
                        + ". Nothing was changed, and the route was kept.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass each value as the type named. To straighten the connection, pass "
                        + "bendpoints: [].",
                null);
    }

    /**
     * {@code args} as the styling reader is to read them: without a styling value that does not read by its
     * key's rule (the same instance when none is dropped): a {@code lineWidth} or {@code fontSize} that is not a
     * whole number, or a colour, font name, font style or line style that is not a string. The styling reader
     * would refuse that value on its own; dropped, it is refused instead by {@link #bendpointsToApply}, which
     * names every unreadable field of the call together, with the type each expects.
     */
    public static Map<String, Object> forStyling(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        Map<String, Object> readable = args;
        for (String key : List.of("lineWidth", "fontSize", "lineColor", "fontColor", "fontName", "lineStyle",
                "fontStyle")) {
            if (args.get(key) != null && !reads(key, args.get(key))) {
                if (readable == args) {
                    readable = new java.util.LinkedHashMap<>(args);
                }
                readable.remove(key);
            }
        }
        return readable;
    }

    /**
     * Whether a present value reads by its key's own rule, the rule that key's reader applies on both
     * paths. JSON null never reads: a key that is sent must say something.
     */
    private static boolean reads(String key, Object value) {
        if (value == null) {
            return false;
        }
        return switch (key) {
            case "bendpoints", "absoluteBendpoints" -> value instanceof List<?>;
            // "" clears a colour or the font name; any lineStyle string reaches its own refusal.
            case "lineColor", "fontColor", "fontName", "lineStyle" -> value instanceof String;
            case "lineWidth", "fontSize" -> readsAsWholeInt(value, key);
            case "fontStyle" -> value instanceof String s && !s.isBlank();
            // showLabel and labelPosition: their readers have already refused what they cannot read.
            default -> true;
        };
    }

    /** Whether the styling reader takes {@code value}: a whole number within the {@code int} range. */
    private static boolean readsAsWholeInt(Object value, String key) {
        try {
            ToolArguments.wholeInt(value, key);
            return true;
        } catch (ModelAccessException e) {
            return false;
        }
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            if (s.isEmpty()) {
                return "an empty string";
            }
            // Bounded and escaped: the caller's value is echoed into the error and the log, not stored.
            return s.length() <= CallerText.MAX_ECHOED ? "the string \"" + CallerText.echo(s, '"') + "\""
                    : "a string of " + s.length() + " characters starting \"" + CallerText.echo(s, '"') + "\"";
        }
        if (value instanceof List<?>) {
            return "an array";
        }
        if (value instanceof Map<?, ?>) {
            return "an object";
        }
        // Bounded like a string: a JSON number can be arbitrarily long.
        return "the " + value.getClass().getSimpleName().toLowerCase(Locale.ROOT) + " "
                + CallerText.bare(String.valueOf(value));
    }

    private static List<String> concat(List<String> head, String tail) {
        List<String> all = new ArrayList<>(head);
        all.add(tail);
        return List.copyOf(all);
    }
}
