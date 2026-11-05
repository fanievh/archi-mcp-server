package net.vheerden.archi.mcp.model;

import java.util.Locale;

/**
 * The one rule for repeating a caller's text in a refusal. A refusal reaches the client as the error
 * message and the log as a line, so what the caller sent is bounded and escaped before it is repeated:
 * a value cannot split a log entry, leave half a character in a response, or flood the message.
 */
public final class CallerText {

    /** The longest caller-supplied string a refusal repeats back in full. */
    public static final int MAX_ECHOED = 40;

    private CallerText() {}

    /**
     * The value between single quotes, as a refusal repeats it: {@code 'bold-ish'}. A value longer than
     * {@link #MAX_ECHOED} reads {@code '<its start>' (the start of N characters)}, N being its length in
     * UTF-16 units, so the reader can tell the echo is partial. An ordinary value (no quote, backslash or
     * control character, at most {@link #MAX_ECHOED} long) reads exactly as it was sent.
     */
    public static String quoted(String value) {
        if (value == null) {
            return "'null'";
        }
        String start = "'" + echo(value, '\'') + "'";
        return value.length() <= MAX_ECHOED ? start : start + " (the start of " + value.length() + " characters)";
    }

    /**
     * The value as a refusal repeats it without quotes: {@code View not found: ghost-1}. A value that
     * {@link #quoted} would repeat exactly as sent reads bare, as it always has; any other value reads as
     * {@link #quoted} repeats it, so the reader can see where escaped or cut text starts and ends.
     * {@code null} reads {@code null}.
     */
    public static String bare(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= MAX_ECHOED && echo(value, '\'').equals(value) ? value : quoted(value);
    }

    /**
     * Caller text as it goes between {@code quote}s in a refusal, which reaches the client and the log.
     * At most {@link #MAX_ECHOED} of the caller's UTF-16 units are kept, one fewer when the last would
     * start a surrogate pair, so a character is dropped rather than split.
     *
     * <p>Newline, carriage return and tab read as {@code \n}, {@code \r} and {@code \t}. Every other
     * control character, unpaired surrogate, line or paragraph separator and format character (a direction
     * mark or override, a zero-width space, a byte-order mark, a soft hyphen) reads as a backslash, {@code u}
     * and four upper-case hex digits: raw, these break a log line, leave half a character, reorder how the
     * line is displayed, or cannot be seen at all. The zero-width joiner and non-joiner are kept, since joined
     * emoji and some scripts need them, and so is a surrogate pair (an emoji).
     * The quote and the backslash are escaped too, so a caller's literal backslash-n still reads
     * differently from a newline.</p>
     */
    public static String echo(String text, char quote) {
        int end = text.length() <= MAX_ECHOED ? text.length()
                : Character.isHighSurrogate(text.charAt(MAX_ECHOED - 1)) ? MAX_ECHOED - 1 : MAX_ECHOED;
        return escape(text, end, quote, true);
    }

    /**
     * Caller text as a log line repeats it: escaped exactly as {@link #echo} escapes it, but whole and with
     * no quotes, since an operator reading the log needs the full id or path. What makes a value unsafe in a
     * log is a line break or a hidden character, not its length. {@code null} reads {@code null}.
     */
    public static String logged(String value) {
        return value == null ? "null" : escape(value, value.length(), NO_QUOTE, true);
    }

    /** {@link #logged(String)} of the value's text, for a set, a key or any other value built from a call. */
    public static String logged(Object value) {
        return logged(value == null ? null : String.valueOf(value));
    }

    /**
     * A message that is already written for the client, as its log line repeats it: every character
     * {@link #logged} escapes is escaped but the backslash. A caller's text inside the message is already
     * escaped (its backslash doubled), so this keeps the log line identical to the message the client reads,
     * while a raw line break or hidden character from anywhere else still cannot split or hide part of it.
     */
    public static String loggedMessage(String message) {
        return message == null ? "null" : escape(message, message.length(), NO_QUOTE, false);
    }

    /** No quote character to escape: a {@code char} can never equal it. */
    private static final int NO_QUOTE = -1;

    /** The one escape loop: {@link #echo}, {@link #logged} and {@link #loggedMessage} differ only in its inputs. */
    private static String escape(String text, int end, int quote, boolean backslash) {
        StringBuilder out = new StringBuilder(end);
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                out.append(backslash ? "\\\\" : "\\");
            } else if (c == quote) {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(text.charAt(i + 1))) {
                out.append(c).append(text.charAt(++i));
            } else if (Character.isISOControl(c) || Character.isSurrogate(c) || isHidden(c)) {
                out.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Line and paragraph separators, and every format character but the zero-width joiner and non-joiner. */
    private static boolean isHidden(char c) {
        return c == 0x2028 || c == 0x2029
                || (Character.getType(c) == Character.FORMAT && c != 0x200C && c != 0x200D);
    }
}
