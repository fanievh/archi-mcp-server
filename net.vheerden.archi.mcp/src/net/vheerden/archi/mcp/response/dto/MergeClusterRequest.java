package net.vheerden.archi.mcp.response.dto;

import java.util.List;

/**
 * One cluster of a {@code merge-elements} call, as the caller sent it: the element that survives
 * and the duplicates that collapse into it.
 *
 * <p>Either component may be null when the caller left it out; the accessor refuses that as a
 * caller error rather than the handler guessing a default.</p>
 */
public record MergeClusterRequest(String targetId, List<String> sourceIds) {
}
