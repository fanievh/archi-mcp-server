package net.vheerden.archi.mcp.response.dto;

/**
 * Data Transfer Object for a potential duplicate element found during
 * duplicate detection on element creation.
 *
 * <p>Contains the existing element's identity plus a similarity score
 * indicating how closely the name matches the proposed new element name.</p>
 */
public record DuplicateCandidate(
    String id,
    String name,
    String type,
    double similarityScore
) {
}
