package net.vheerden.archi.mcp.model;

/**
 * Abstraction for a view object passed to layout computation.
 * Decouples Zest API from the accessor's EMF object handling.
 */
record LayoutNode(String viewObjectId, double x, double y, double width, double height, String parentId) {}
