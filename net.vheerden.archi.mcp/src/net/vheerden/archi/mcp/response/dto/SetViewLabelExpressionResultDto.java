package net.vheerden.archi.mcp.response.dto;

/**
 * Result of a single {@code set-view-label-expression} bulk operation.
 *
 * <p>The operation applies one label-expression template to every eligible diagram
 * object on a view in a single command, so its result is a per-view summary rather
 * than a per-object echo.</p>
 *
 * @param viewId          the diagram model the template was applied to
 * @param viewName        the diagram model's name (for the operation summary / approval card)
 * @param labelExpression the template that was applied (empty string when the operation cleared labels)
 * @param appliedCount    number of objects whose label expression was set/changed/cleared
 * @param skippedCount    number of objects left untouched (wrong type, or no name for the template to resolve)
 */
public record SetViewLabelExpressionResultDto(
    String viewId,
    String viewName,
    String labelExpression,
    int appliedCount,
    int skippedCount
) {}
