package net.vheerden.archi.mcp.model;

/**
 * Text processing utilities for view annotations (Story 9-0b).
 *
 * <p>Interprets common escape sequences in text content so that
 * LLM-generated strings containing literal {@code \n}, {@code \t},
 * {@code \r}, or {@code \\} produce actual whitespace characters
 * when stored in the ArchiMate model.</p>
 */
final class TextUtils {

    private TextUtils() {}

    /**
     * Interprets escape sequences in the given text.
     *
     * <p>Converts:
     * <ul>
     *   <li>{@code \\n} (backslash + n) → newline (U+000A)</li>
     *   <li>{@code \\t} (backslash + t) → tab (U+0009)</li>
     *   <li>{@code \\r} (backslash + r) → carriage return (U+000D)</li>
     *   <li>{@code \\\\} (backslash + backslash) → single backslash</li>
     * </ul>
     *
     * <p>Returns {@code null} if the input is {@code null}.</p>
     *
     * @param text the input text potentially containing escape sequences
     * @return text with escape sequences interpreted, or null if input is null
     */
    static String interpretEscapes(String text) {
        if (text == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
