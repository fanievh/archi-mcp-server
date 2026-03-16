package net.vheerden.archi.mcp.model.routing;

/**
 * Recommends moving a blocking element to unblock failed connection routes (Story 10-31).
 * Pure-geometry record — no EMF/SWT dependencies.
 *
 * @param elementId           view object ID of the blocking element
 * @param elementName         display name (set to elementId in routing layer; accessor resolves real name)
 * @param dx                  recommended horizontal displacement in pixels (positive = right)
 * @param dy                  recommended vertical displacement in pixels (positive = down)
 * @param reason              human-readable explanation of the move
 * @param connectionsUnblocked number of failed connections this move would unblock
 */
public record MoveRecommendation(String elementId, String elementName,
                                  int dx, int dy, String reason,
                                  int connectionsUnblocked) {}
