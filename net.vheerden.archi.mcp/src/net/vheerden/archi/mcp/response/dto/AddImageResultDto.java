package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for the add-image-to-model response (Story C4).
 *
 * <p>Returns the archive path where the image is stored (for use with
 * imagePath on view objects), the detected image dimensions, and the
 * format detected from the image bytes.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddImageResultDto(
    String imagePath,
    int width,
    int height,
    String formatDetected
) {}
