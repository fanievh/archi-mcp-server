package net.vheerden.archi.mcp.model;

/**
 * Axis-aligned bounding box of all visual content on a view (Story 11-29).
 * Coordinates are absolute canvas coordinates (same as {@link AssessmentNode}).
 */
public record ContentBounds(double x, double y, double width, double height) {}
