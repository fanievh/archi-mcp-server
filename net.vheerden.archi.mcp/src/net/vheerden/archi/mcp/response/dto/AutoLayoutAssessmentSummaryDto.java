package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Summary of layout quality assessment performed during auto-layout-and-route
 * with targetRating.
 *
 * <p>{@code crossingsPerConnection} is assess-layout's own value on the same state, rounded as it
 * rounds it; null (omitted) only where a caller built the summary without it.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutoLayoutAssessmentSummaryDto(
		int overlapCount,
		int edgeCrossingCount,
		double averageSpacing,
		int alignmentScore,
		String overallRating,
		List<String> suggestions,
		Double crossingsPerConnection) {

	/** The summary without the crossing ratio, as it was before the ratio was added. */
	public AutoLayoutAssessmentSummaryDto(int overlapCount, int edgeCrossingCount, double averageSpacing,
			int alignmentScore, String overallRating, List<String> suggestions) {
		this(overlapCount, edgeCrossingCount, averageSpacing, alignmentScore, overallRating, suggestions, null);
	}
}
