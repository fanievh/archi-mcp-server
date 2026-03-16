package net.vheerden.archi.mcp.response.dto;

import java.util.List;

/**
 * Result DTO for auto-connect-view (Story 9-6).
 *
 * @param viewId                  the view that was auto-connected
 * @param connectionsCreated      number of new visual connections created
 * @param connectionsSkipped      number of connections skipped (already exist on view)
 * @param relationshipIdsConnected list of relationship IDs for newly created connections
 */
public record AutoConnectResultDto(
        String viewId,
        int connectionsCreated,
        int connectionsSkipped,
        List<String> relationshipIdsConnected) {}
