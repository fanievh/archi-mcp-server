package net.vheerden.archi.mcp.response.dto;

/**
 * Data Transfer Object for an image stored in the model's archive (Story C4).
 *
 * <p>Used by the list-model-images tool response. Each entry represents
 * one image file in the archive with its path and dimensions.</p>
 */
public record ModelImageDto(
    String imagePath,
    int width,
    int height
) {}
