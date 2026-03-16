package net.vheerden.archi.mcp.model;

/**
 * A view object's geometry for layout quality assessment.
 * All coordinates are absolute canvas coordinates (parent offsets accumulated).
 * Includes parentId for boundary violation detection, isGroup to distinguish
 * container groups from leaf elements (Story 9-2), and isNote to identify
 * annotation notes that should be excluded from layout scoring (Story 11-15).
 */
record AssessmentNode(String id, double x, double y, double width, double height,
                      String parentId, boolean isGroup, boolean isNote) {}
