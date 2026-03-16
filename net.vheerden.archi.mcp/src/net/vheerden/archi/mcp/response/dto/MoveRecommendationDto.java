package net.vheerden.archi.mcp.response.dto;

/**
 * Response DTO for a move recommendation to unblock failed connection routes (Story 10-31).
 *
 * @param elementId           view object ID of the blocking element
 * @param elementName         human-readable name of the blocking element
 * @param dx                  recommended horizontal displacement in pixels (positive = right)
 * @param dy                  recommended vertical displacement in pixels (positive = down)
 * @param reason              human-readable explanation of the move
 * @param connectionsUnblocked number of failed connections this move would unblock
 */
public record MoveRecommendationDto(String elementId, String elementName,
                                     int dx, int dy, String reason,
                                     int connectionsUnblocked) {}
