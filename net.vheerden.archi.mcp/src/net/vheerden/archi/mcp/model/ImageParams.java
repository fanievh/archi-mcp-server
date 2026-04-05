package net.vheerden.archi.mcp.model;

import java.util.Map;

/**
 * Value object bundling optional image parameters (Story C4).
 *
 * <p>Used to pass image parameters through accessor method signatures
 * without bloating individual parameter lists. All fields are nullable:
 * null means "not specified" (leave unchanged), empty string for imagePath
 * means "clear/remove image".</p>
 *
 * @param imagePath     archive path from add-image-to-model, empty string to remove, null = unchanged
 * @param imagePosition kebab-case position string (e.g. "bottom-left"), null = unchanged
 * @param showIcon      icon visibility: "if-no-image", "always", "never", null = unchanged
 */
public record ImageParams(
    String imagePath,
    String imagePosition,
    String showIcon
) {

    /** An empty ImageParams indicating no image changes. */
    public static final ImageParams NONE = new ImageParams(null, null, null);

    /**
     * Returns true if at least one image parameter is specified (non-null).
     */
    public boolean hasAnyValue() {
        return imagePath != null || imagePosition != null || showIcon != null;
    }

    // ---- Position mapping: kebab-case string to IIconic int constant ----

    private static final Map<String, Integer> POSITION_MAP = Map.ofEntries(
        Map.entry("top-left", 0),
        Map.entry("top-centre", 1),
        Map.entry("top-right", 2),
        Map.entry("middle-left", 3),
        Map.entry("middle-centre", 4),
        Map.entry("middle-right", 5),
        Map.entry("bottom-left", 6),
        Map.entry("bottom-centre", 7),
        Map.entry("bottom-right", 8),
        Map.entry("fill", 9)
    );

    private static final Map<Integer, String> REVERSE_POSITION_MAP = Map.ofEntries(
        Map.entry(0, "top-left"),
        Map.entry(1, "top-centre"),
        Map.entry(2, "top-right"),
        Map.entry(3, "middle-left"),
        Map.entry(4, "middle-centre"),
        Map.entry(5, "middle-right"),
        Map.entry(6, "bottom-left"),
        Map.entry(7, "bottom-centre"),
        Map.entry(8, "bottom-right"),
        Map.entry(9, "fill")
    );

    // ---- Show icon mapping ----

    private static final Map<String, Integer> SHOW_ICON_MAP = Map.of(
        "if-no-image", 0,
        "always", 1,
        "never", 2
    );

    private static final Map<Integer, String> REVERSE_SHOW_ICON_MAP = Map.of(
        0, "if-no-image",
        1, "always",
        2, "never"
    );

    /**
     * Converts a kebab-case position string to the IIconic int constant.
     *
     * @param position kebab-case position string
     * @return IIconic position constant (0-9)
     * @throws IllegalArgumentException if position is not recognized
     */
    public static int positionToInt(String position) {
        Integer value = POSITION_MAP.get(position);
        if (value == null) {
            throw new IllegalArgumentException(
                "Invalid image position: '" + position + "'. Valid values: " + POSITION_MAP.keySet());
        }
        return value;
    }

    /**
     * Converts an IIconic int constant to the kebab-case position string.
     *
     * @param position IIconic position constant (0-9)
     * @return kebab-case position string, or null if not recognized
     */
    public static String positionToString(int position) {
        return REVERSE_POSITION_MAP.get(position);
    }

    /**
     * Converts a kebab-case showIcon string to the int constant.
     *
     * @param showIcon kebab-case show icon string
     * @return show icon constant (0-2)
     * @throws IllegalArgumentException if showIcon is not recognized
     */
    public static int showIconToInt(String showIcon) {
        Integer value = SHOW_ICON_MAP.get(showIcon);
        if (value == null) {
            throw new IllegalArgumentException(
                "Invalid showIcon value: '" + showIcon + "'. Valid values: " + SHOW_ICON_MAP.keySet());
        }
        return value;
    }

    /**
     * Converts an int constant to the kebab-case showIcon string.
     *
     * @param showIcon show icon constant (0-2)
     * @return kebab-case showIcon string, or null if not recognized
     */
    public static String showIconToString(int showIcon) {
        return REVERSE_SHOW_ICON_MAP.get(showIcon);
    }
}
