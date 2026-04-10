package net.vheerden.archi.mcp.model;

/**
 * A view object's geometry for layout quality assessment.
 * All coordinates are absolute canvas coordinates (parent offsets accumulated).
 * Includes parentId for boundary violation detection, isGroup to distinguish
 * container groups from leaf elements (Story 9-2), and isNote to identify
 * annotation notes that should be excluded from layout scoring (Story 11-15).
 * B53: name, labelTextWidth, imagePath, imagePosition for informational detection.
 */
record AssessmentNode(String id, double x, double y, double width, double height,
                      String parentId, boolean isGroup, boolean isNote,
                      String name, double labelTextWidth,
                      String imagePath, String imagePosition) {}
