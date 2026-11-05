package net.vheerden.archi.mcp;

/**
 * Reduces Java source to its code, for tests that check a property by scanning production source.
 * A mention in a comment or a string is prose, not a call, so it must not count as one.
 */
public final class JavaSourceText {

    private JavaSourceText() {
    }

    /**
     * The source with every comment and every string, character and text-block literal replaced by
     * spaces (line breaks kept), so only code is matched.
     */
    public static String blank(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                i = spaces(src, i, end, out);
            } else if (src.startsWith("\"\"\"", i)) {
                // An escape (\""" included) never closes the block
                int end = i + 3;
                while (end < n && !src.startsWith("\"\"\"", end)) {
                    end += src.charAt(end) == '\\' ? 2 : 1;
                }
                i = spaces(src, i, Math.min(end + 3, n), out);
            } else if (c == '"' || c == '\'') {
                // Ends at its closing quote or, if unterminated, at the line break, even after a backslash
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' && j + 1 < n && src.charAt(j + 1) != '\n' ? 2 : 1;
                }
                i = spaces(src, i, Math.min(j + 1, n), out);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int spaces(String src, int from, int to, StringBuilder out) {
        for (int k = from; k < to; k++) {
            out.append(src.charAt(k) == '\n' ? '\n' : ' ');
        }
        return to;
    }
}
