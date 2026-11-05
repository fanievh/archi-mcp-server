package net.vheerden.archi.mcp.model;

import java.util.Map;

/**
 * The bound on the spacing and padding the layout tools accept ({@code auto-layout-and-route},
 * {@code layout-within-group}, {@code arrange-groups}, {@code optimize-group-order} and
 * {@code layout-flat-view}). Each feeds {@code int} coordinate arithmetic ({@code x += width + spacing},
 * multiples of the spacing), so a value near the top of the {@code int} range wraps the layout's coordinates
 * negative while the call answers success.
 */
public final class LayoutSpacing {

    /**
     * The widest spacing or padding a layout tool accepts, published in each schema. Far beyond any useful
     * spacing, and small enough that no layout arithmetic overflows.
     */
    public static final int MAX_LAYOUT_SPACING = 1000;

    private LayoutSpacing() {
    }

    /**
     * A layout spacing or padding: {@code null} when absent or null, otherwise read as
     * {@link ToolArguments#wholeInt(Map, String)} reads it and refused above {@link #MAX_LAYOUT_SPACING}. The
     * lower bound is the tool's own and is checked where it always was, after this read.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key, the maximum and the value
     */
    public static Integer atMost(Map<String, ?> args, String key) {
        return ViewGeometry.atMost(args, key, MAX_LAYOUT_SPACING);
    }
}
