package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for individual mutation operation results (Story 7-1).
 *
 * <p>Used by future mutation tools (create-element, update-element)
 * to report the result of a single mutation operation.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MutationResultDto(
    boolean success,
    String description,
    Integer sequenceNumber
) {}
