package net.vheerden.archi.mcp.response.dto;

import java.util.List;

/**
 * Input specification for a single connection bendpoint update
 * within an apply-positions operation (Story 9-0a, renamed 11-8).
 *
 * <p>Bendpoints and absoluteBendpoints are mutually exclusive.
 * If neither is provided, all bendpoints are cleared (straight line).</p>
 */
public record ViewConnectionSpec(
    String viewConnectionId,
    List<BendpointDto> bendpoints,
    List<AbsoluteBendpointDto> absoluteBendpoints) {

    public ViewConnectionSpec {
        if (viewConnectionId == null || viewConnectionId.isBlank()) {
            throw new IllegalArgumentException("viewConnectionId must not be null or blank");
        }
        bendpoints = bendpoints != null ? List.copyOf(bendpoints) : null;
        absoluteBendpoints = absoluteBendpoints != null ? List.copyOf(absoluteBendpoints) : null;
    }
}
