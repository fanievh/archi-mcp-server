package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Summary of layout quality assessment performed during auto-layout-and-route
 * with targetRating (Story 11-16).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutoLayoutAssessmentSummaryDto(
		int overlapCount,
		int edgeCrossingCount,
		double averageSpacing,
		int alignmentScore,
		String overallRating,
		List<String> suggestions) {
}
