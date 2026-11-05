package net.vheerden.archi.mcp.model;

import java.util.Map;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * The bounds on the coordinates and sizes a caller sends for a view object: {@code x}, {@code y}, {@code width} and
 * {@code height} on the tools that place or move one (add-to-view, add-group-to-view, add-note-to-view,
 * add-view-reference-to-view, add-image-to-view, update-view-object, apply-positions, and the same tools inside
 * bulk-mutate), {@code anchorDx} and {@code anchorDy} on update-view-object, {@code elementWidth} and
 * {@code elementHeight} on layout-within-group and optimize-group-order, and each bendpoint a caller sends on
 * add-connection-to-view, update-view-connection, an apply-positions connection entry, and the same tools inside
 * bulk-mutate.
 * Each feeds {@code int} arithmetic ({@code x + width} for a right edge, {@code x += width + spacing} along a
 * layout row), so a value near the top of the {@code int} range wraps a coordinate negative while the call answers
 * success.
 *
 * <p>The bounds are checked where the caller's value is read, never where a placement is prepared: the layout
 * passes prepare placements of their own, with computed geometry, through the same code.</p>
 */
public final class ViewGeometry {

    /**
     * The farthest from the origin a caller may place an object, on either axis and either side of it, published
     * in each schema. Far beyond any canvas a person draws, and small enough that a coordinate plus a size, or a
     * nesting of them, stays well inside {@code int}.
     */
    public static final int MAX_COORDINATE = 1_000_000;

    /**
     * The widest or tallest a caller may make an object, published in each schema. Far beyond any object a person
     * draws, and small enough that a layout row of thousands of them, spacing included, stays inside {@code int}.
     */
    public static final int MAX_SIZE = 100_000;

    /**
     * The farthest from the origin a caller may place an absolute bendpoint ({@code x}, {@code y}), on either axis,
     * published in each schema. An object's {@code x} and {@code y} are measured from its parent, so a nested object's
     * place on the canvas can lie well past {@link #MAX_COORDINATE}; this bound reaches objects nested several levels
     * deep at that bound, and stays far inside {@code int} when converted against either end.
     */
    public static final int MAX_BENDPOINT_POINT = 10 * MAX_COORDINATE;

    /**
     * The farthest a relative bendpoint ({@code startX}, {@code startY}, {@code endX}, {@code endY}) may lie from the
     * centre of the end it is measured from, published in each schema: twice {@link #MAX_BENDPOINT_POINT}, since an
     * offset runs from one place on the canvas to another, so an offset the server stores from an accepted absolute
     * point can be sent back as it is reported.
     */
    public static final int MAX_BENDPOINT_OFFSET = 2 * MAX_BENDPOINT_POINT;

    private ViewGeometry() {
    }

    /**
     * A coordinate: {@code null} when absent or null, otherwise read as {@link ToolArguments#wholeInt(Map, String)}
     * reads it and refused outside {@code -MAX_COORDINATE..MAX_COORDINATE}. A negative coordinate is legal.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key, the bounds and the value
     */
    public static Integer coordinate(Map<String, ?> args, String key) {
        Integer value = ToolArguments.wholeInt(args, key);
        if (value == null || Math.abs((long) value) <= MAX_COORDINATE) {
            return value;
        }
        throw new ModelAccessException(
                key + " must be from " + -MAX_COORDINATE + " to " + MAX_COORDINATE + ", got "
                        + ToolArguments.describe(args.get(key)) + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass " + key + " from " + -MAX_COORDINATE + " to " + MAX_COORDINATE + ", or omit it.", null);
    }

    /**
     * A width or height: {@code null} when absent or null, otherwise read as
     * {@link ToolArguments#wholeInt(Map, String)} reads it and refused above {@link #MAX_SIZE}. The lower bound is
     * the tool's own and is checked where it always was, after this read.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key, the maximum and the value
     */
    public static Integer size(Map<String, ?> args, String key) {
        return atMost(args, key, MAX_SIZE);
    }

    /**
     * The bound either side of zero on a bendpoint field: {@code x}/{@code y} absolute, {@code startX},
     * {@code startY}, {@code endX} and {@code endY} relative.
     *
     * @throws IllegalArgumentException for any other key
     */
    public static int bendpointBound(String key) {
        return switch (key) {
            case "x", "y" -> MAX_BENDPOINT_POINT;
            case "startX", "startY", "endX", "endY" -> MAX_BENDPOINT_OFFSET;
            default -> throw new IllegalArgumentException("Not a bendpoint field: " + key);
        };
    }

    /**
     * Field {@code key} of the bendpoint at {@code index}, named {@code Bendpoint[index].key} in a refusal: read as
     * {@link ToolArguments#wholeInt(Object, String)} reads it and refused past {@link #bendpointBound}.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the field, the bounds and the value
     */
    public static int bendpointField(Number value, String key, int index) {
        String name = "Bendpoint[" + index + "]." + key;
        int read = ToolArguments.wholeInt(value, name);
        int bound = bendpointBound(key);
        if (Math.abs((long) read) <= bound) {
            return read;
        }
        throw new ModelAccessException(
                name + " must be from " + -bound + " to " + bound + ", got " + ToolArguments.describe(value)
                        + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass each bendpoint's " + key + " from " + -bound + " to " + bound + ".", null);
    }

    /**
     * A whole number read as {@link ToolArguments#wholeInt(Map, String)} reads it and refused above {@code max}:
     * {@code "<key> must be at most <max>, got <value>. Nothing was changed."}.
     *
     * @throws ModelAccessException INVALID_PARAMETER naming the key, the maximum and the value
     */
    static Integer atMost(Map<String, ?> args, String key, int max) {
        Integer value = ToolArguments.wholeInt(args, key);
        if (value == null || value <= max) {
            return value;
        }
        throw new ModelAccessException(
                key + " must be at most " + max + ", got " + ToolArguments.describe(args.get(key))
                        + ". Nothing was changed.",
                ErrorCode.INVALID_PARAMETER, null,
                "Pass " + key + " up to " + max + ", or omit it.", null);
    }
}
