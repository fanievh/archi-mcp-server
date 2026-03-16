package net.vheerden.archi.mcp.response.dto;

/**
 * Data Transfer Object for ArchiMate relationships.
 *
 * <p>Represents a relationship between two ArchiMate concepts.
 * Used in view contents and relationship queries.</p>
 */
public record RelationshipDto(
    String id,
    String name,
    String type,
    String sourceId,
    String targetId
) {}
