package net.vheerden.archi.mcp.response.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for ArchiMate views (diagrams).
 *
 * <p>Returned by the get-views and update-view commands. Provides summary
 * information about a diagram model including its viewpoint, location in
 * the folder hierarchy, and optionally documentation and custom properties.</p>
 *
 * <p>Field inclusion is controlled by the field preset:
 * {@code minimal} = id + name, {@code standard} = id + name + viewpointType + folderPath,
 * {@code full} = all fields including documentation and properties.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewDto(
    String id,
    String name,
    String viewpointType,
    String connectionRouterType,
    String folderPath,
    String documentation,
    Map<String, String> properties
) {
    /**
     * Backward-compatible constructor for call sites that don't need
     * documentation or properties (standard preset).
     */
    public ViewDto(String id, String name, String viewpointType, String folderPath) {
        this(id, name, viewpointType, null, folderPath, null, null);
    }
}
