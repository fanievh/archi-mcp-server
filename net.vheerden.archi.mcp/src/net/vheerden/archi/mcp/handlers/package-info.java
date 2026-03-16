/**
 * MCP tool handlers grouped by domain.
 *
 * <p><strong>CRITICAL BOUNDARY:</strong> Handlers in this package MUST NOT
 * import any EMF or ArchimateTool model types. All model access goes through
 * {@link net.vheerden.archi.mcp.model.ArchiModelAccessor}.</p>
 *
 * <p>Handler classes:</p>
 * <ul>
 *   <li>ModelQueryHandler - get-element, get-model-info (Story 2.2-2.3)</li>
 *   <li>ViewHandler - get-views, get-view-contents (Story 2.4-2.5)</li>
 *   <li>SearchHandler - search-elements (Story 3.1)</li>
 *   <li>TraversalHandler - get-relationships (Story 4.1)</li>
 * </ul>
 */
package net.vheerden.archi.mcp.handlers;
