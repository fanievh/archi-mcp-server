package net.vheerden.archi.mcp.response;

/**
 * Response format options for query commands (Story 6.3).
 *
 * <p>Controls the shape of the response envelope's primary data key:
 * <ul>
 *   <li>{@link #JSON} — Standard {@code result} key (default, backward compatible)</li>
 *   <li>{@link #GRAPH} — {@code graph} key with {@code nodes}/{@code edges} structure</li>
 *   <li>{@link #SUMMARY} — {@code summary} key with natural language text</li>
 * </ul>
 */
public enum ResponseFormat {

    JSON("json"),
    GRAPH("graph"),
    SUMMARY("summary");

    private final String value;

    ResponseFormat(String value) {
        this.value = value;
    }

    /**
     * Returns the lowercase string representation used in tool parameters.
     */
    public String value() {
        return value;
    }

    /**
     * Parses a format string into a {@link ResponseFormat}.
     *
     * @param value the format string (e.g., "json", "graph", "summary")
     * @return the matching format, or {@code null} if invalid or null input
     */
    public static ResponseFormat fromString(String value) {
        if (value == null) return null;
        for (ResponseFormat format : values()) {
            if (format.value.equals(value)) {
                return format;
            }
        }
        return null;
    }
}
