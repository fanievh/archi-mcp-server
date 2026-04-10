package net.vheerden.archi.mcp.response.dto;

import java.util.Map;

/**
 * Data Transfer Object for model summary information.
 *
 * <p>Returned by the get-model-info command. Provides a high-level
 * overview of the loaded ArchiMate model including element counts,
 * type distributions, and layer distribution.</p>
 */
public record ModelInfoDto(
    String name,
    int elementCount,
    int relationshipCount,
    int viewCount,
    int specializationCount,
    Map<String, Integer> elementTypeDistribution,
    Map<String, Integer> relationshipTypeDistribution,
    Map<String, Integer> layerDistribution
) {}
