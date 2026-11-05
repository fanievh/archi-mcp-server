package net.vheerden.archi.mcp.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * The one rule for which arguments a tool reads, applied before any tool runs and to each operation of
 * {@code bulk-mutate}. Nothing validates a call's arguments against the tool's served input schema (the
 * MCP SDK validates only structured output), so an argument a tool does not read used to be dropped in
 * silence. Where a default fires on "nothing read", a dropped key changes what the call does: a
 * misspelled {@code rollback} committed a batch, a misspelled {@code dryRun} ran for real, and a
 * misspelled id filter widened a call to the whole view. So every argument outside a tool's accepted set
 * is refused, every such key named at once, and nothing runs.
 *
 * <p>A tool's accepted set is the keys its served schema declares, plus {@link #MODEL_ID} (read before
 * every tool, or ignored by design on the tools that touch no model), plus the few keys a tool reads
 * without declaring them ({@link #UNDECLARED_READ}). Inner keys of an argument (a {@code properties}
 * map, an entry of an array) are not checked here.</p>
 */
public final class ToolArguments {

    /** Read by the call scope before every tool runs; accepted on every tool. */
    public static final String MODEL_ID = "modelId";

    /** The most unknown keys a refusal names; any beyond are counted. */
    public static final int MAX_KEYS_NAMED = 10;

    /**
     * Keys a tool reads although its served schema does not declare them. {@code lineStyle} is accepted on
     * the three connection tools so that it reaches its own refusal, which says why a connection has no
     * line style; answering it as an unknown key would say less.
     */
    public static final Map<String, Set<String>> UNDECLARED_READ = Map.of(
            "update-view-connection", Set.of("lineStyle"),
            "add-connection-to-view", Set.of("lineStyle"),
            "auto-connect-view", Set.of("lineStyle"));

    /**
     * The keys a tool declares, in its schema's order, the ones it requires, and the declared keys this
     * context cannot honour, each with the reason a refusal gives ({@code refused}; empty for the tool
     * itself).
     */
    public record Shape(String tool, List<String> declared, List<String> required, Map<String, String> refused) {

        public Shape {
            declared = List.copyOf(declared);
            required = required == null ? List.of() : List.copyOf(required);
            refused = refused == null ? Map.of() : Map.copyOf(refused);
        }

        public Shape(String tool, List<String> declared, List<String> required) {
            this(tool, declared, required, null);
        }

        /** Every key the tool reads. */
        public Set<String> accepted() {
            Set<String> accepted = new LinkedHashSet<>(declared);
            accepted.add(MODEL_ID);
            accepted.addAll(UNDECLARED_READ.getOrDefault(tool, Set.of()));
            return accepted;
        }
    }

    /**
     * {@code set-view-label-expression} exists only as a {@code bulk-mutate} operation, so it has no served
     * schema; these are the keys its operation reads. {@code labelExpression} is required too, but an empty
     * one is a value (it clears), so only the id is listed as required here.
     */
    public static final Shape SET_VIEW_LABEL_EXPRESSION = new Shape("set-view-label-expression",
            List.of("viewId", "labelExpression", "objectTypes"), List.of("viewId"));

    /** Why a {@code bulk-mutate} {@code update-view-object} refuses the anchor keys. */
    static final String BULK_ANCHOR_REASON = "anchoring is set with the update-view-object tool, which also "
            + "queues inside begin-batch.";

    private ToolArguments() {
    }

    /**
     * The shape a tool's {@code bulk-mutate} operation is held to: the tool's own, except that the
     * operation's prepare cannot honour every declared key. {@code update-view-object} there does not
     * set an anchor, so its four anchor keys are refused with the reason rather than dropped. Null for
     * a null shape.
     */
    public static Shape inBulk(Shape shape) {
        if (shape == null || !"update-view-object".equals(shape.tool())) {
            return shape;
        }
        Map<String, String> refused = new LinkedHashMap<>();
        for (String key : List.of("anchorTarget", "anchorEdge", "anchorDx", "anchorDy")) {
            refused.put(key, BULK_ANCHOR_REASON);
        }
        return new Shape(shape.tool(), shape.declared(), shape.required(), refused);
    }

    /**
     * The shape a bulk operation is held to when its caller supplied none: the bulk-only operation's, and
     * {@code update-view-connection}'s, whose keys {@link ConnectionUpdateArguments} lists. Null otherwise.
     */
    public static Shape knownShape(String tool) {
        if (SET_VIEW_LABEL_EXPRESSION.tool().equals(tool)) {
            return SET_VIEW_LABEL_EXPRESSION;
        }
        if ("update-view-connection".equals(tool)) {
            return new Shape(tool, ConnectionUpdateArguments.DECLARED, List.of("viewConnectionId"));
        }
        return null;
    }

    /**
     * Refuses every key of {@code args} the tool does not read. Steps aside when a required key is absent,
     * so the tool's own read answers first and can name a near-miss spelling ({@link ParamNameDiagnostics}).
     * Does nothing for a null shape or null arguments.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming every unknown key, sorted
     */
    public static void refuseUnknown(Shape shape, Map<String, ?> args) {
        if (shape == null || args == null) {
            return;
        }
        for (String key : shape.required()) {
            if (args.get(key) == null) {
                return;
            }
        }
        Set<String> accepted = shape.accepted();
        TreeSet<String> unknown = new TreeSet<>();
        TreeSet<String> refused = new TreeSet<>();
        for (String key : args.keySet()) {
            if (shape.refused().containsKey(key)) {
                refused.add(key);
            } else if (!accepted.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty() && refused.isEmpty()) {
            return;
        }
        String accepts = shape.tool() + " accepts " + String.join(", ", shape.declared()) + ".";
        String message = unknown.isEmpty() ? "" : shape.tool() + " does not accept " + quoted(unknown) + ". An argument "
                + "the tool cannot read is refused rather than ignored, and nothing was changed. " + accepts;
        String correction = unknown.isEmpty() ? "" : "Remove " + (unknown.size() == 1 ? "it" : "them")
                + ", or correct the spelling. " + accepts;
        if (!refused.isEmpty()) {
            // Every refused key here shares one reason today; group by reason if that ever changes.
            String why = shape.refused().get(refused.first());
            message += (message.isEmpty() ? "" : " ") + shape.tool() + " in bulk-mutate cannot apply "
                    + quoted(refused) + ": " + why + " " + (refused.size() == 1 ? "It is" : "They are")
                    + " refused rather than ignored, and nothing was changed.";
            correction += (correction.isEmpty() ? "" : " ") + "Remove " + (refused.size() == 1 ? "it" : "them")
                    + " from this operation and call " + shape.tool() + " with " + (refused.size() == 1 ? "it" : "them")
                    + " instead.";
        }
        throw new ModelAccessException(message, ErrorCode.INVALID_PARAMETER, null, correction, null);
    }

    /**
     * Refuses every key of one array entry outside {@code accepted}: {@code entry} names the array the
     * entry sits in, as a caller reads it.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming every unknown key, sorted
     */
    public static void refuseUnknownEntryKeys(String entry, List<String> accepted, Map<String, ?> args) {
        TreeSet<String> unknown = new TreeSet<>();
        for (String key : args.keySet()) {
            if (!accepted.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        String accepts = "A " + entry + " entry accepts " + String.join(", ", accepted) + ".";
        throw new ModelAccessException(
                "the entry does not accept " + quoted(unknown) + ". A key the tool cannot read is refused "
                        + "rather than ignored, and nothing was changed. " + accepts,
                ErrorCode.INVALID_PARAMETER, null,
                "Remove " + (unknown.size() == 1 ? "it" : "them") + ", or correct the spelling. " + accepts,
                null);
    }

    /**
     * An on/off argument whose absence turns something on or off: {@code null} when absent or null, a
     * boolean as sent, or the string {@code "true"} or {@code "false"} in any case. Any other value is
     * refused rather than read as the default, because the default is the very thing the caller may have
     * been switching off.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key and the value
     */
    public static Boolean flag(Map<String, ?> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
            return Boolean.valueOf(s);
        }
        throw new ModelAccessException(
                key + " must be true or false, got " + describe(value) + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass " + key + ": true or " + key + ": false, or omit it.", null);
    }

    /** As {@link #flag(Map, String)}, with {@code absent} when the key is absent or null. */
    public static boolean flag(Map<String, ?> args, String key, boolean absent) {
        Boolean value = flag(args, key);
        return value == null ? absent : value;
    }

    /**
     * A whole-number argument: {@code null} when absent or null, otherwise a JSON number with no fractional
     * part ({@code 20}, {@code 20.0}, or one beyond {@code long}, which saturates at the {@code long} range so
     * the caller's clamp still applies; a literal too large for a double, read as infinity, saturates the same
     * way). A fractional number, NaN, a string or a boolean is refused rather than truncated or read as the
     * default.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key and the value
     */
    public static Long wholeNumber(Map<String, ?> args, String key) {
        return wholeNumber(args, key, ", or omit it.");
    }

    private static Long wholeNumber(Map<String, ?> args, String key, String end) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0) {
            // Saturate by the count of integer digits before converting: expanding a huge exponent
            // (1E+999999999) overflows BigInteger or runs for as long as it builds the digits. Zero first,
            // since 0E+999999999 counts a billion digits. Long arithmetic, as precision - scale can pass int.
            if (decimal.signum() == 0) {
                return 0L;
            }
            if ((long) decimal.precision() - decimal.scale() > 19) {
                return decimal.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            value = decimal.toBigInteger();
        }
        if (value instanceof BigInteger big) {
            return big.bitLength() < Long.SIZE ? big.longValue() : big.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        if (value instanceof Number n && !(value instanceof BigDecimal) && !Double.isNaN(n.doubleValue())
                && n.doubleValue() == Math.rint(n.doubleValue())) {
            return (long) n.doubleValue();
        }
        throw new ModelAccessException(
                key + " must be a whole number, got " + describe(value) + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass " + key + " as a whole number" + end, null);
    }

    /**
     * As {@link #wholeNumber}, held to {@code min} to {@code max} inclusive: a whole number outside that
     * range is refused naming the range, rather than clamped or narrowed (narrowing to {@code int} is what
     * let {@code 3000000000} wrap to a negative). {@code null} when absent or null.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key, the range and the value
     */
    public static Integer wholeNumberWithin(Map<String, ?> args, String key, int min, int max) {
        Long value = wholeNumber(args, key);
        if (value == null || (value >= min && value <= max)) {
            return value == null ? null : value.intValue();
        }
        throw new ModelAccessException(
                key + " must be a whole number from " + min + " to " + max + ", got "
                        + describe(args.get(key)) + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass " + key + " from " + min + " to " + max + ", or omit it.", null);
    }

    /**
     * As {@link #wholeNumber}, held to the {@code int} range: {@code null} when absent or null. A whole number
     * past that range is refused rather than narrowed, since narrowing wraps it, and {@code 2^32 + n} wraps
     * to {@code n}, a value that then passes every range check after the read. The ranges a key publishes
     * are checked where they always were, after this read.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key and the value
     */
    public static Integer wholeInt(Map<String, ?> args, String key) {
        return args == null ? null : wholeInt(args.get(key), key, ", or omit it.");
    }

    /**
     * As {@link #wholeInt(Map, String)} for a required value that is not read from an argument map by its
     * key, such as a field of an array entry, named {@code name} in the refusal. {@code value} is not null.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming {@code name} and the value
     */
    public static int wholeInt(Object value, String name) {
        return wholeInt(value, name, ".");
    }

    private static Integer wholeInt(Object value, String name, String end) {
        Long whole = wholeNumber(value == null ? null : Map.of(name, value), name, end);
        if (whole == null || (whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE)) {
            return whole == null ? null : whole.intValue();
        }
        throw new ModelAccessException(
                name + " must be a whole number within the integer range, got " + describe(value)
                        + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass " + name + " as a whole number from " + Integer.MIN_VALUE + " to " + Integer.MAX_VALUE + end,
                null);
    }

    /**
     * A string argument: {@code null} when absent or null, otherwise the string as sent. A value that is not
     * a string is refused, and so is a blank one (whitespace only), rather than read as absent: either used
     * to drop the argument in silence, so the call did something other than what was asked. The empty
     * string is a value only where it clears a setting ({@code emptyClears}), and is returned as
     * {@code ""}; otherwise it is refused as blank.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key and the value
     */
    public static String text(Map<String, ?> args, String key, boolean emptyClears) {
        Object value = args == null ? null : args.get(key);
        if (value == null || (value instanceof String s && (!s.isBlank() || (s.isEmpty() && emptyClears)))) {
            return (String) value;
        }
        String correction = "Pass " + key + " as a non-blank string" + (emptyClears ? ", \"\" to clear it," : "")
                + " or omit it.";
        throw new ModelAccessException(
                key + (value instanceof String ? " must not be blank" : " must be a string") + ", got "
                        + describe(value) + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null, correction, null);
    }

    /**
     * An optional string argument read as the lenient readers always read it, except for its type: {@code null}
     * when absent or null, {@code null} for a blank string unless {@code keepEmpty} (which returns any string as
     * sent, so {@code ""} can clear a setting), and the string otherwise. A value that is present and not a
     * string (a number, a boolean, an array, an object) is refused naming the key and the value: it used to be
     * read as absent, so the call went ahead with the argument dropped.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key and the value
     */
    public static String optionalText(Map<String, ?> args, String key, boolean keepEmpty) {
        Object value = args == null ? null : args.get(key);
        if (value != null && !(value instanceof String)) {
            throw notAString(key, value, "Pass " + key + " as a string, or omit it.");
        }
        String s = (String) value;
        return s == null || keepEmpty || !s.isBlank() ? s : null;
    }

    /**
     * One entry of a list of strings, read as {@link #optionalText} reads an argument: {@code null} when null or
     * blank, the string otherwise, and a value of another type refused under {@code name}.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the entry and the value
     */
    public static String textItem(Object item, String name) {
        if (item != null && !(item instanceof String)) {
            throw notAString(name, item, "Pass " + name + " as a string.");
        }
        return item == null || ((String) item).isBlank() ? null : (String) item;
    }

    /**
     * Refuses a required string argument that is present and not a string, so the caller hears that the value
     * has the wrong type rather than that the argument is missing. An absent, null or blank value is left to the
     * caller's own missing-argument refusal.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key and the value
     */
    public static void requireTextType(Map<String, ?> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value != null && !(value instanceof String)) {
            throw notAString(key, value, "Pass " + key + " as a string.");
        }
    }

    /** The refusal of a value given for a string argument that is not a string. */
    public static ModelAccessException notAString(String key, Object value, String correction) {
        return new ModelAccessException(key + " must be a string, got " + describe(value) + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null, correction, null);
    }

    /**
     * A filter (of ids or type names) whose absence means "act on all": {@code null} when absent or null,
     * otherwise a non-empty array of non-blank strings. A value that is not an array, an empty array, or an item that is not a
     * non-blank string is refused: each of those used to be read as no filter, widening the call to
     * everything.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key (and the item's index)
     */
    public static List<String> idFilter(Map<String, ?> args, String key) {
        String omit = "omit " + key + " to act on all.";
        return strings(args, key, key + " is an empty array. An empty filter is refused rather than read as all, "
                + "and nothing was changed.", "Name at least one entry in " + key + ", or " + omit, omit);
    }

    /**
     * A list of names (of fields, say) whose absence leaves a setting alone: {@code null} when absent or null,
     * otherwise a non-empty array of non-blank strings. A value that is not an array, an empty array, or an
     * item that is not a non-blank string is refused rather than dropped, as {@link #idFilter} refuses them,
     * in words that do not speak of acting on all.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key (and the item's index)
     */
    public static List<String> names(Map<String, ?> args, String key, String noun) {
        return strings(args, key, key + " must name at least one " + noun + ", got an empty array. Nothing was "
                + "changed.", "Name at least one " + noun + " in " + key + ", or omit it.", "omit it.");
    }

    private static List<String> strings(Map<String, ?> args, String key, String empty, String emptyCorrection,
            String omit) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new ModelAccessException(
                    key + " must be an array of strings, got " + describe(value) + ". Nothing was changed.",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Pass " + key + " as an array of strings, or " + omit, null);
        }
        if (list.isEmpty()) {
            throw new ModelAccessException(empty, ErrorCode.INVALID_PARAMETER, null, emptyCorrection, null);
        }
        List<String> ids = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof String s) || s.isBlank()) {
                throw new ModelAccessException(
                        key + "[" + i + "] must be a non-empty string, got " + describe(list.get(i))
                                + ". Nothing was changed.",
                        ErrorCode.INVALID_PARAMETER, null,
                        "Pass every entry of " + key + " as a non-empty string, or " + omit, null);
            }
            ids.add(s);
        }
        return ids;
    }

    /**
     * An object argument whose every value is a string: {@code null} when absent, null or empty, otherwise
     * its entries in the caller's order. A value that is not an object, or an entry whose value is not a
     * string, is refused rather than dropped.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key (and the entry's key)
     */
    public static Map<String, String> stringValues(Map<String, ?> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new ModelAccessException(
                    key + " must be an object, got " + describe(value) + ". Nothing was changed.",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Pass " + key + " as an object of string values, or omit it.", null);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof String s)) {
                throw new ModelAccessException(
                        key + " entry " + CallerText.quoted(name) + " must be a string, got " + describe(entry.getValue())
                                + ". Nothing was changed.",
                        ErrorCode.INVALID_PARAMETER, null,
                        "Pass every value of " + key + " as a string, or omit it.", null);
            }
            out.put(name, s);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * A caller's value as a refusal names it: a string well-formed and bounded, an array or object by its
     * kind only, anything else (a number, a boolean) by its type and value.
     */
    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return s.isEmpty() ? "an empty string" : "the string " + CallerText.quoted(s);
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

    private static String quoted(Iterable<String> keys) {
        List<String> out = new ArrayList<>();
        int total = 0;
        for (String key : keys) {
            if (total++ < MAX_KEYS_NAMED) {
                out.add(key.length() <= CallerText.MAX_ECHOED ? "'" + CallerText.echo(key, '\'') + "'"
                        : "a key of " + key.length() + " characters starting '" + CallerText.echo(key, '\'') + "'");
            }
        }
        String named = String.join(", ", out);
        return total <= MAX_KEYS_NAMED ? named : named + " and " + (total - MAX_KEYS_NAMED) + " more";
    }
}
