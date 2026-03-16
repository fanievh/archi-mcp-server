package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;
import net.vheerden.archi.mcp.response.dto.DuplicateCandidate;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;
import net.vheerden.archi.mcp.response.dto.LayoutViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.UndoRedoResultDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionSpec;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Interface for read-only access to the ArchiMate model.
 *
 * <p><strong>CRITICAL BOUNDARY:</strong> This is the ONLY interface that
 * should be used to access the ArchiMate EMF model. Handlers MUST NOT
 * import EMF or ArchimateTool model types directly.</p>
 *
 * <p>All methods return DTOs, never EMF EObjects. This ensures:</p>
 * <ul>
 *   <li>Clean separation between protocol layer and model layer</li>
 *   <li>Handlers can be unit tested without EMF runtime</li>
 *   <li>Threading concerns are encapsulated here</li>
 *   <li>Future write operations can be added without handler changes</li>
 * </ul>
 *
 * <p>Model detection and session initialization are implemented in Story 1.5.
 * Query methods (getElementById, getModelInfo, getViews, getViewContents)
 * are implemented in Story 2.1.</p>
 */
public interface ArchiModelAccessor {

    /**
     * Gets an element by its unique identifier.
     *
     * @param id the element ID
     * @return Optional containing the element, or empty if not found
     * @throws NoModelLoadedException if no model is loaded
     */
    Optional<ElementDto> getElementById(String id);

    /**
     * Gets summary information about the currently loaded model.
     *
     * @return model info DTO with counts and type distribution
     * @throws NoModelLoadedException if no model is loaded
     */
    ModelInfoDto getModelInfo();

    /**
     * Lists all views (diagrams) in the model, optionally filtered by viewpoint.
     *
     * @param viewpointFilter viewpoint name to filter by, or null for all views
     * @return list of view DTOs (may be empty)
     * @throws NoModelLoadedException if no model is loaded
     */
    List<ViewDto> getViews(String viewpointFilter);

    /**
     * Gets the contents of a specific view by its ID.
     *
     * @param viewId the view's unique identifier
     * @return Optional containing the view contents, or empty if no view matches the ID
     * @throws NoModelLoadedException if no model is loaded
     */
    Optional<ViewContentsDto> getViewContents(String viewId);

    /**
     * Gets multiple elements by their unique identifiers in a single efficient pass.
     *
     * <p>Uses a single model traversal to find all matching elements, making this
     * more efficient than calling {@link #getElementById(String)} in a loop for
     * large ID sets.</p>
     *
     * @param ids the element IDs to look up (must not be null or empty)
     * @return list of found elements as DTOs (may be empty if none match, never null).
     *         The returned list contains only elements that were found; missing IDs
     *         must be determined by the caller by comparing requested vs returned IDs.
     * @throws NoModelLoadedException if no model is loaded
     */
    List<ElementDto> getElementsByIds(List<String> ids);

    /**
     * Searches all elements in the model using case-insensitive substring matching,
     * with optional filtering by ArchiMate element type and layer.
     *
     * <p>Matches against element name, documentation, and property values.
     * Type and layer filters are applied with AND logic before text matching.</p>
     *
     * @param query the search text (case-insensitive substring match)
     * @param typeFilter ArchiMate element type to filter by (e.g., "ApplicationComponent"), or null for no type filtering
     * @param layerFilter ArchiMate layer to filter by (e.g., "Application"), or null for no layer filtering
     * @return list of matching elements as DTOs (empty list if no matches, never null)
     * @throws NoModelLoadedException if no model is loaded
     */
    List<ElementDto> searchElements(String query, String typeFilter, String layerFilter);

    /**
     * Gets all relationships where the specified element is the source or target.
     *
     * <p>Returns both incoming (target) and outgoing (source) relationships.
     * The handler is responsible for verifying element existence before calling
     * this method.</p>
     *
     * @param elementId the element ID to find relationships for
     * @return list of relationship DTOs (may be empty if element has no relationships, never null)
     * @throws NoModelLoadedException if no model is loaded
     */
    List<RelationshipDto> getRelationshipsForElement(String elementId);

    // ---- Folder navigation (Story 7-0b) ----

    /**
     * Gets all root-level folders in the model.
     *
     * @return list of root folder DTOs (9 standard ArchiMate folders)
     * @throws NoModelLoadedException if no model is loaded
     */
    List<FolderDto> getRootFolders();

    /**
     * Gets a folder by its unique identifier.
     *
     * @param id the folder ID
     * @return Optional containing the folder, or empty if not found
     * @throws NoModelLoadedException if no model is loaded
     */
    Optional<FolderDto> getFolderById(String id);

    /**
     * Gets the direct children (subfolders) of a folder.
     *
     * @param parentId the parent folder ID
     * @return list of child folder DTOs (may be empty)
     * @throws NoModelLoadedException if no model is loaded
     */
    List<FolderDto> getFolderChildren(String parentId);

    /**
     * Gets the folder hierarchy as a tree structure.
     *
     * @param rootId   root folder ID for subtree, or null for full tree
     * @param maxDepth maximum tree depth (0 or negative for unlimited)
     * @return list of folder tree DTOs with nested children
     * @throws NoModelLoadedException if no model is loaded
     */
    List<FolderTreeDto> getFolderTree(String rootId, int maxDepth);

    /**
     * Searches all folders recursively by name (case-insensitive substring match).
     *
     * @param nameQuery the search text
     * @return list of matching folder DTOs
     * @throws NoModelLoadedException if no model is loaded
     */
    List<FolderDto> searchFolders(String nameQuery);

    // ---- Discovery-first patterns (Story 7-4) ----

    /**
     * Finds existing elements of the given type whose names are similar to the
     * specified name, scored above the duplicate detection threshold.
     *
     * @param type the ArchiMate element type to filter by
     * @param name the proposed element name to compare against
     * @return list of duplicate candidates sorted by similarity score descending, capped at 10
     * @throws NoModelLoadedException if no model is loaded
     */
    List<DuplicateCandidate> findDuplicates(String type, String name);

    /**
     * Finds an existing element matching the given type and name exactly (case-insensitive).
     *
     * @param type the ArchiMate element type to match
     * @param name the element name to match (case-insensitive)
     * @return Optional containing the matching element, or empty if not found
     * @throws NoModelLoadedException if no model is loaded
     */
    Optional<ElementDto> findExactMatch(String type, String name);

    // ---- Mutation creation methods (Story 7-2) ----

    /**
     * Creates a new ArchiMate element in the model.
     *
     * <p>Validates the type string, creates the element via EMF factory,
     * sets properties, resolves the target folder, and dispatches via
     * CommandStack. Checks operational mode to dispatch immediately
     * (GUI-attached) or queue for batch.</p>
     *
     * @param sessionId     the session identifier for mode detection
     * @param type          ArchiMate element type (e.g., "BusinessActor")
     * @param name          element name (required)
     * @param documentation optional documentation text
     * @param properties    optional key-value properties map
     * @param folderId      optional target folder ID (null for type-default folder)
     * @return MutationResult containing the created ElementDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if type is invalid or folder not found
     */
    MutationResult<ElementDto> createElement(String sessionId, String type, String name,
            String documentation, Map<String, String> properties, String folderId);

    /**
     * Creates a new ArchiMate element with optional source traceability (Story 7-6).
     *
     * <p>When {@code source} is non-null, its entries are merged into the element's
     * properties prefixed with "mcp.source." (e.g., source key "tool" becomes
     * property "mcp.source.tool").</p>
     *
     * @param sessionId     the session identifier for mode detection
     * @param type          ArchiMate element type (e.g., "BusinessActor")
     * @param name          element name (required)
     * @param documentation optional documentation text
     * @param properties    optional key-value properties map
     * @param folderId      optional target folder ID (null for type-default folder)
     * @param source        optional source traceability map (keys auto-prefixed with "mcp.source.")
     * @return MutationResult containing the created ElementDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if type is invalid or folder not found
     */
    MutationResult<ElementDto> createElement(String sessionId, String type, String name,
            String documentation, Map<String, String> properties, String folderId,
            Map<String, String> source);

    /**
     * Creates a new ArchiMate relationship between two elements.
     *
     * <p>Validates the relationship type, verifies source and target elements
     * exist, checks ArchiMate specification rules, and dispatches. Returns
     * structured error with valid alternatives if spec validation fails.</p>
     *
     * @param sessionId the session identifier for mode detection
     * @param type      ArchiMate relationship type (e.g., "ServingRelationship")
     * @param sourceId  source element ID (required)
     * @param targetId  target element ID (required)
     * @param name      optional relationship name
     * @return MutationResult containing the created RelationshipDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if type invalid, elements not found, or spec violation
     */
    MutationResult<RelationshipDto> createRelationship(String sessionId, String type,
            String sourceId, String targetId, String name);

    /**
     * Creates a new ArchiMate view (diagram) in the model.
     *
     * @param sessionId the session identifier for mode detection
     * @param name      view name (required)
     * @param viewpoint optional viewpoint type
     * @param folderId  optional target folder ID (null for default Views folder)
     * @return MutationResult containing the created ViewDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if folder not found
     */
    MutationResult<ViewDto> createView(String sessionId, String name,
            String viewpoint, String folderId, String connectionRouterType);

    // ---- Mutation update methods (Story 7-3) ----

    /**
     * Updates an existing ArchiMate element's fields.
     *
     * <p>Only non-null parameters are modified; null parameters leave the
     * corresponding field unchanged. For properties, a merge semantic applies:
     * non-null values add/update, null values remove the property key.</p>
     *
     * @param sessionId     the session identifier for mode detection
     * @param id            element ID (required)
     * @param name          new name, or null to leave unchanged
     * @param documentation new documentation, or null to leave unchanged
     * @param properties    property merge map (null value = remove key), or null to leave unchanged
     * @return MutationResult containing the updated ElementDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if element not found or no fields to update
     */
    MutationResult<ElementDto> updateElement(String sessionId, String id, String name,
            String documentation, Map<String, String> properties);

    /**
     * Updates an existing ArchiMate view's metadata fields.
     * Only non-null parameters are modified; null parameters leave the
     * corresponding field unchanged. For properties, a merge semantic applies:
     * non-null values add/update, null values remove the property key.
     *
     * @param sessionId      the session identifier for mode detection
     * @param id             view ID (required)
     * @param name           new name, or null to leave unchanged
     * @param viewpoint      new viewpoint, or null to leave unchanged; empty string clears the viewpoint
     * @param documentation  new documentation, or null to leave unchanged
     * @param properties     property merge map (null value = remove key), or null to leave unchanged
     * @return MutationResult containing the updated ViewDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or no fields to update
     */
    MutationResult<ViewDto> updateView(String sessionId, String id, String name,
            String viewpoint, String documentation, Map<String, String> properties,
            String connectionRouterType);

    // ---- View placement (Story 7-7) ----

    /**
     * Places an existing model element onto a view as a diagram object.
     *
     * <p>Optionally positions at the given x/y coordinates with given width/height,
     * or uses auto-placement when coordinates are omitted. When autoConnect is true,
     * also creates visual connections for any existing relationships to elements
     * already on the view.</p>
     *
     * <p><strong>Story 8-6:</strong> Added optional parentViewObjectId to nest
     * elements inside visual groups.</p>
     *
     * @param sessionId          the session identifier for mode detection
     * @param viewId             the view's unique identifier (required)
     * @param elementId          the element's unique identifier (required)
     * @param x                  x coordinate (null for auto-placement)
     * @param y                  y coordinate (null for auto-placement)
     * @param width              width (null for default 120)
     * @param height             height (null for default 55)
     * @param autoConnect        true to auto-create connections to existing view objects
     * @param parentViewObjectId optional group viewObjectId to nest element inside (null for top-level)
     * @return MutationResult containing the AddToViewResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view/element not found, already on view, or parent is not a group
     */
    MutationResult<AddToViewResultDto> addToView(String sessionId, String viewId,
            String elementId, Integer x, Integer y, Integer width, Integer height,
            boolean autoConnect, String parentViewObjectId, StylingParams styling);

    /**
     * Creates a visual grouping rectangle on a view diagram (Story 8-6).
     *
     * @param sessionId          the session identifier for mode detection
     * @param viewId             the view's unique identifier (required)
     * @param label              the group display label (required, must not be blank)
     * @param x                  x coordinate (null for auto-placement)
     * @param y                  y coordinate (null for auto-placement)
     * @param width              width (null for default 300)
     * @param height             height (null for default 200)
     * @param parentViewObjectId optional group viewObjectId to nest inside (null for top-level)
     * @return MutationResult containing the ViewGroupDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found, label is blank, or parent is not a group
     */
    MutationResult<ViewGroupDto> addGroupToView(String sessionId, String viewId,
            String label, Integer x, Integer y, Integer width, Integer height,
            String parentViewObjectId, StylingParams styling);

    /**
     * Creates a text note on a view diagram (Story 8-6).
     *
     * @param sessionId          the session identifier for mode detection
     * @param viewId             the view's unique identifier (required)
     * @param content            the note text content (required, must not be null)
     * @param x                  x coordinate (null for auto-placement)
     * @param y                  y coordinate (null for auto-placement)
     * @param width              width (null for default 185)
     * @param height             height (null for default 80)
     * @param parentViewObjectId optional group viewObjectId to nest inside (null for top-level)
     * @return MutationResult containing the ViewNoteDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found, content is null, or parent is not a group
     */
    MutationResult<ViewNoteDto> addNoteToView(String sessionId, String viewId,
            String content, Integer x, Integer y, Integer width, Integer height,
            String parentViewObjectId, StylingParams styling);

    /**
     * Adds a visual connection between two view objects on a view.
     *
     * <p>Links an existing model relationship as a visual connection on the diagram.
     * The relationship's source/target elements must match the view objects' elements
     * in either orientation (forward or reversed).</p>
     *
     * @param sessionId           the session identifier for mode detection
     * @param viewId              the view's unique identifier (required)
     * @param relationshipId      the relationship's unique identifier (required)
     * @param sourceViewObjectId  the source view object ID on the view (required)
     * @param targetViewObjectId  the target view object ID on the view (required)
     * @param bendpoints          optional list of routing bendpoints in relative format (null for straight line)
     * @param absoluteBendpoints  optional list of routing bendpoints in absolute canvas coordinates
     *                            (mutually exclusive with bendpoints)
     * @return MutationResult containing the ViewConnectionDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if any reference is invalid or connection exists
     */
    MutationResult<ViewConnectionDto> addConnectionToView(String sessionId, String viewId,
            String relationshipId, String sourceViewObjectId, String targetViewObjectId,
            List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints);

    // ---- View editing and removal (Story 7-8) ----

    /**
     * Updates the visual bounds and optionally text of a view object on a diagram.
     *
     * <p>Only non-null parameters are modified; null parameters leave the
     * corresponding field unchanged. At least one of x, y, width, height, text
     * must be non-null.</p>
     *
     * <p><strong>Story 8-6:</strong> The text parameter updates the label for
     * groups or content for notes. It is rejected with INVALID_PARAMETER when
     * the viewObjectId references an ArchiMate element view object.</p>
     *
     * @param sessionId    the session identifier for mode detection
     * @param viewObjectId the view object's unique identifier (required)
     * @param x            new X coordinate, or null to leave unchanged
     * @param y            new Y coordinate, or null to leave unchanged
     * @param width        new width, or null to leave unchanged
     * @param height       new height, or null to leave unchanged
     * @param text         new text for groups (label) or notes (content), or null to leave unchanged
     * @return MutationResult containing the updated ViewObjectDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view object not found, no fields to update, or text on element
     */
    MutationResult<ViewObjectDto> updateViewObject(String sessionId,
            String viewObjectId, Integer x, Integer y, Integer width, Integer height,
            String text, StylingParams styling);

    /**
     * Replaces the bendpoints of a connection on a view.
     *
     * <p>An empty list clears all bendpoints (straight line). Each bendpoint
     * has startX/startY (offset from source) and endX/endY (offset from target).
     * Alternatively, absolute canvas coordinates can be provided.</p>
     *
     * @param sessionId          the session identifier for mode detection
     * @param viewConnectionId   the connection's unique identifier (required)
     * @param bendpoints         the new set of bendpoints in relative format (may be null)
     * @param absoluteBendpoints the new set of bendpoints in absolute canvas coordinates
     *                           (mutually exclusive with bendpoints)
     * @return MutationResult containing the updated ViewConnectionDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if connection not found
     */
    MutationResult<ViewConnectionDto> updateViewConnection(String sessionId,
            String viewConnectionId, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling);

    /**
     * Removes a visual element or connection from a view without deleting
     * the underlying model object.
     *
     * <p>When removing an element, attached connections are cascade-removed.
     * The viewObjectId can reference either a view object (element) or a
     * view connection.</p>
     *
     * @param sessionId    the session identifier for mode detection
     * @param viewId       the view's unique identifier (required)
     * @param viewObjectId the ID of the view object or connection to remove (required)
     * @return MutationResult containing RemoveFromViewResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view or view object not found
     */
    MutationResult<RemoveFromViewResultDto> removeFromView(String sessionId,
            String viewId, String viewObjectId);

    /**
     * Removes all visual elements and connections from a view without deleting
     * the underlying model objects.
     *
     * <p>This is an atomic operation that clears the entire view contents in a
     * single command, dramatically more efficient than calling removeFromView
     * for each individual element.</p>
     *
     * @param sessionId the session identifier for mode detection
     * @param viewId    the view's unique identifier (required)
     * @return MutationResult containing ClearViewResultDto with removal counts
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found
     */
    MutationResult<ClearViewResultDto> clearView(String sessionId, String viewId);

    /**
     * Atomically applies a complete visual layout to a view.
     * Repositions elements/groups/notes and updates connection bendpoints
     * in a single undo unit with no operation count limit (Story 9-0a).
     *
     * @param sessionId   the session identifier for mode detection
     * @param viewId      the view's unique identifier (required)
     * @param positions   position/size updates for view objects (may be null/empty)
     * @param connections bendpoint updates for connections (may be null/empty)
     * @param description optional label for the undo history entry
     * @return MutationResult containing ApplyViewLayoutResultDto with update counts
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or any entry fails validation
     */
    MutationResult<ApplyViewLayoutResultDto> applyViewLayout(
            String sessionId,
            String viewId,
            List<ViewPositionSpec> positions,
            List<ViewConnectionSpec> connections,
            String description);

    /**
     * Computes and applies an automatic layout to a view using the
     * specified algorithm or preset. Repositions all elements and clears
     * all connection bendpoints (straight lines) in a single undo unit.
     *
     * @param sessionId the session identifier for mode detection
     * @param viewId    the view's unique identifier (required)
     * @param algorithm layout algorithm name, or null if using preset
     * @param preset    semantic preset name, or null if using algorithm
     * @param options   optional layout parameters (e.g., "spacing")
     * @return MutationResult containing LayoutViewResultDto with update counts
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found, invalid algorithm/preset, or both provided
     */
    MutationResult<LayoutViewResultDto> layoutView(
            String sessionId,
            String viewId,
            String algorithm,
            String preset,
            Map<String, Object> options);

    // ---- Layout assessment (Story 9-2) ----

    /**
     * Assesses the layout quality of a view, returning objective metrics
     * and improvement suggestions. Read-only — no model modifications.
     *
     * @param viewId the view's unique identifier (required)
     * @return AssessLayoutResultDto with metrics and suggestions
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found
     */
    AssessLayoutResultDto assessLayout(String viewId);

    // ---- Auto-route connections (Story 9-5) ----

    /**
     * Applies automated orthogonal routing to connections on a view.
     *
     * <p>Computes right-angle bendpoints for clean connection paths. The "clear"
     * strategy removes all bendpoints (straight lines). All connection updates
     * are wrapped in a single compound command for atomic undo.</p>
     *
     * @param sessionId     the session identifier for mode detection
     * @param viewId        the view's unique identifier (required)
     * @param connectionIds optional list of specific connection IDs to route (null for all)
     * @param strategy      routing strategy: "orthogonal" (default) or "clear"
     * @param force         when true, applies all routes including constraint-violating ones
     *                      and reports violations instead of failures
     * @return MutationResult containing AutoRouteResultDto with routing counts
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found, connection not found, or invalid strategy
     */
    MutationResult<AutoRouteResultDto> autoRouteConnections(
            String sessionId, String viewId,
            List<String> connectionIds, String strategy, boolean force);

    // ---- Auto-layout-and-route (Story 10-29) ----

    /**
     * Applies ELK Layered algorithm to compute both element positions AND
     * connection routes in a single operation. Replaces all element positions
     * and computes orthogonal connection bendpoints.
     *
     * <p>Unlike {@code layoutView} (positions only) or {@code autoRouteConnections}
     * (routes only), this tool delegates both tasks to ELK simultaneously.</p>
     *
     * @param sessionId    the session identifier for mode detection
     * @param viewId       the view's unique identifier (required)
     * @param direction    layout direction: DOWN, RIGHT, UP, LEFT (default DOWN)
     * @param spacing      inter-element spacing in pixels (default 50)
     * @param targetRating optional quality target ("excellent", "good", "fair");
     *                     when non-null, iterates with increasing spacing until
     *                     assess-layout reaches the target rating or max iterations (5)
     * @return MutationResult containing AutoLayoutAndRouteResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or invalid parameters
     */
    MutationResult<AutoLayoutAndRouteResultDto> autoLayoutAndRoute(
            String sessionId, String viewId,
            String direction, int spacing, String targetRating);

    /**
     * Retroactively creates visual connections on a view for all existing model
     * relationships between elements already placed on that view.
     *
     * <p>Only creates missing connections — existing visual connections are not
     * duplicated. All created connections are wrapped in a single compound command
     * for atomic undo.</p>
     *
     * @param sessionId         the session identifier for mode detection
     * @param viewId            the view's unique identifier (required)
     * @param elementIds        optional filter: only consider relationships involving these elements
     * @param relationshipTypes optional filter: only connect relationships of these types
     * @return MutationResult containing AutoConnectResultDto with connection counts
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found, element not on view, or invalid type
     */
    MutationResult<AutoConnectResultDto> autoConnectView(
            String sessionId, String viewId,
            List<String> elementIds, List<String> relationshipTypes);

    // ---- Layout within group (Story 9-9) ----

    /**
     * Arranges child elements within a visual group using row, column, or grid
     * patterns. Computes positions server-side so the LLM doesn't need to
     * calculate coordinates.
     *
     * <p>Only repositions direct children of the specified group (not recursive
     * into sub-groups). Sub-groups are treated as single elements for positioning.</p>
     *
     * @param sessionId          the session identifier for mode detection
     * @param viewId             the view's unique identifier (required)
     * @param groupViewObjectId  the group's view object ID (required)
     * @param arrangement        arrangement pattern: "row", "column", or "grid" (required)
     * @param spacing            space between elements in pixels (default: 20)
     * @param padding            space from group edges in pixels (default: 10)
     * @param elementWidth       optional: resize all children to this width
     * @param elementHeight      optional: resize all children to this height
     * @param autoResize         resize the group to fit its children (default: false)
     * @param autoWidth          compute each element's width from its label text (default: false);
     *                           ignored when elementWidth is set (explicit override wins)
     * @param columns           optional: number of columns for grid arrangement (auto-detected if null)
     * @param recursive         if true and autoResize is true, recursively resize ancestor groups
     * @return MutationResult containing LayoutWithinGroupResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view/group not found or invalid arrangement
     */
    MutationResult<LayoutWithinGroupResultDto> layoutWithinGroup(
            String sessionId, String viewId, String groupViewObjectId,
            String arrangement, Integer spacing, Integer padding,
            Integer elementWidth, Integer elementHeight, boolean autoResize,
            boolean autoWidth, Integer columns, boolean recursive);

    /**
     * Arranges top-level groups in a view using a specified arrangement pattern.
     *
     * <p>Groups are positioned relative to each other in a grid, row, or column layout.
     * Only top-level groups (direct children of the view that are IDiagramModelGroup)
     * are repositioned. Optionally, a subset of groups can be targeted via groupIds.</p>
     *
     * @param sessionId   the session identifier for mode detection
     * @param viewId      the view containing the groups (required)
     * @param arrangement layout pattern: "grid", "row", or "column" (required)
     * @param columns     optional: number of columns for grid arrangement (auto-detected if null)
     * @param spacing     optional: gap in pixels between groups (default: 40)
     * @param groupIds    optional: list of specific group IDs to arrange (all top-level groups if null/empty)
     * @return MutationResult containing ArrangeGroupsResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or invalid parameters
     */
    MutationResult<ArrangeGroupsResultDto> arrangeGroups(
            String sessionId, String viewId, String arrangement,
            Integer columns, Integer spacing, java.util.List<String> groupIds);

    /**
     * Optimizes element order within groups to minimize inter-group edge crossings.
     *
     * <p>Uses a barycentric heuristic to reorder elements within each group,
     * then re-lays them out using the specified arrangement pattern. Group structure
     * and membership are preserved — no elements are moved between groups.</p>
     *
     * @param sessionId     the session identifier for mode detection
     * @param viewId        the view to optimize (required)
     * @param arrangement   arrangement pattern for re-layout: "row", "column", or "grid" (required)
     * @param spacing       space between elements in pixels (default: 20)
     * @param padding       space from group edges in pixels (default: 10)
     * @param elementWidth  optional: resize all children to this width
     * @param elementHeight optional: resize all children to this height
     * @param autoWidth     compute each element's width from its label text (default: false)
     * @param columns       optional: number of columns for grid arrangement
     * @return MutationResult containing OptimizeGroupOrderResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or no groups with inter-group connections
     */
    MutationResult<OptimizeGroupOrderResultDto> optimizeGroupOrder(
            String sessionId, String viewId, String arrangement,
            Integer spacing, Integer padding, Integer elementWidth,
            Integer elementHeight, boolean autoWidth, Integer columns);

    // ---- Deletion methods (Story 8-4) ----

    /**
     * Deletes an ArchiMate element with full cascade.
     *
     * <p>Cascade-removes all relationships involving this element and all view
     * references (diagram objects and connections) across all views.</p>
     *
     * @param sessionId the session identifier for mode detection
     * @param elementId the element's unique identifier (required)
     * @return MutationResult containing DeleteResultDto with cascade counts
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if element not found
     */
    MutationResult<DeleteResultDto> deleteElement(String sessionId, String elementId);

    /**
     * Deletes an ArchiMate relationship with cascade.
     *
     * <p>Cascade-removes all view connections representing this relationship.
     * The connected elements are NOT deleted.</p>
     *
     * @param sessionId      the session identifier for mode detection
     * @param relationshipId the relationship's unique identifier (required)
     * @return MutationResult containing DeleteResultDto with cascade counts
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if relationship not found
     */
    MutationResult<DeleteResultDto> deleteRelationship(String sessionId, String relationshipId);

    /**
     * Deletes an ArchiMate view (diagram) from the model.
     *
     * <p>Removes the view and all its visual contents. The underlying model
     * elements and relationships are NOT deleted.</p>
     *
     * @param sessionId the session identifier for mode detection
     * @param viewId    the view's unique identifier (required)
     * @return MutationResult containing DeleteResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found
     */
    MutationResult<DeleteResultDto> deleteView(String sessionId, String viewId);

    /**
     * Deletes a folder from the model.
     *
     * <p>Empty folders are deleted immediately. Non-empty folders require
     * {@code force=true} to cascade-delete all contents. Top-level default
     * ArchiMate folders cannot be deleted.</p>
     *
     * @param sessionId the session identifier for mode detection
     * @param folderId  the folder's unique identifier (required)
     * @param force     true to cascade-delete non-empty folder contents
     * @return MutationResult containing DeleteResultDto with cascade counts
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if folder not found, is default folder, or non-empty without force
     */
    MutationResult<DeleteResultDto> deleteFolder(String sessionId, String folderId, boolean force);

    // ---- Folder mutation methods (Story 8-5) ----

    /**
     * Creates a new subfolder within a parent folder.
     *
     * @param sessionId     the session identifier for mode detection
     * @param parentId      parent folder ID (required)
     * @param name          folder name (required)
     * @param documentation optional documentation text
     * @param properties    optional key-value properties map
     * @return MutationResult containing the created FolderDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if parent folder not found
     */
    MutationResult<FolderDto> createFolder(String sessionId, String parentId, String name,
            String documentation, Map<String, String> properties);

    /**
     * Updates an existing folder's metadata.
     *
     * <p>Only non-null parameters are modified; null parameters leave the
     * corresponding field unchanged. For properties, a merge semantic applies:
     * non-null values add/update, null values remove the property key.</p>
     *
     * @param sessionId     the session identifier for mode detection
     * @param id            folder ID (required)
     * @param name          new name, or null to leave unchanged
     * @param documentation new documentation, or null to leave unchanged
     * @param properties    property merge map (null value = remove key), or null to leave unchanged
     * @return MutationResult containing the updated FolderDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if folder not found or no fields to update
     */
    MutationResult<FolderDto> updateFolder(String sessionId, String id, String name,
            String documentation, Map<String, String> properties);

    /**
     * Moves a model object (element, relationship, view, or folder) to a
     * different parent folder.
     *
     * @param sessionId      the session identifier for mode detection
     * @param objectId       the object to move (required)
     * @param targetFolderId the target parent folder (required)
     * @return MutationResult containing MoveResultDto with source/target paths
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if object/folder not found, circular move, or default folder
     */
    MutationResult<MoveResultDto> moveToFolder(String sessionId,
            String objectId, String targetFolderId);

    // ---- Bulk mutation (Story 7-5, enhanced Story 11-9) ----

    /**
     * Executes multiple mutation operations as a single compound command.
     *
     * <p>Two-phase execution: Phase 1 validates all operations and builds EMF objects
     * + Commands on the Jetty thread. Phase 2 dispatches the compound command via
     * {@code Display.syncExec()} for atomic CommandStack execution.</p>
     *
     * <p>When {@code continueOnError} is false (default), if any operation fails
     * validation in Phase 1, no mutations are applied (all-or-nothing). When
     * {@code continueOnError} is true, failed operations are skipped and successful
     * operations are executed together. Operations referencing a failed operation
     * via back-references ($N.id) also fail with a cascade error.</p>
     *
     * <p>Supports back-references: {@code $N.id} in parameter values resolves to the
     * entity ID created by operation at index N. For {@code create-relationship},
     * direct EMF element references are used for source/target wiring.</p>
     *
     * @param sessionId       the session identifier for mode detection
     * @param operations      the list of operations to execute (max 50)
     * @param description     optional label for the compound command (undo history), may be null
     * @param continueOnError if true, failed operations are skipped and reported separately
     * @return BulkMutationResult with per-operation results (and failures when continueOnError)
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException   if any operation fails validation and continueOnError is false
     * @throws net.vheerden.archi.mcp.model.exceptions.MutationException if dispatch fails
     */
    BulkMutationResult executeBulk(String sessionId, List<BulkOperation> operations,
            String description, boolean continueOnError);

    // ---- View export (Story 8-1) ----

    /**
     * Renders a view to an image in the specified format.
     *
     * <p>PNG rendering uses SWT {@code DiagramUtils.createImage()} on the Display thread.
     * SVG rendering requires the optional {@code com.archimatetool.export.svg} bundle.</p>
     *
     * @param viewId the view's unique identifier (required)
     * @param format output format: "png" or "svg" (required)
     * @param scale  rendering scale factor (1.0 = 100%)
     * @param inline true to return image bytes, false to write to file
     * @return ExportResult containing metadata + optional image bytes/SVG content
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or format not available
     */
    ExportResult exportView(String viewId, String format, double scale, boolean inline);

    // ---- Command stack undo/redo (Story 11-1) ----

    /**
     * Undoes the specified number of most recent operations from the command stack.
     *
     * <p>Each step undoes one top-level CommandStack entry (which may be a compound
     * command wrapping multiple sub-operations). If fewer than {@code steps}
     * operations are available, all available operations are undone.</p>
     *
     * @param steps number of operations to undo (must be >= 1)
     * @return UndoRedoResultDto with operation counts, labels, and stack state
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if undo fails
     */
    UndoRedoResultDto undo(int steps);

    /**
     * Redoes the specified number of most recently undone operations.
     *
     * <p>Each step redoes one top-level CommandStack entry. If fewer than
     * {@code steps} operations are available, all available operations are redone.</p>
     *
     * @param steps number of operations to redo (must be >= 1)
     * @return UndoRedoResultDto with operation counts, labels, and stack state
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if redo fails
     */
    UndoRedoResultDto redo(int steps);

    // ---- Mutation support (Story 7-1) ----

    /**
     * Returns the MutationDispatcher for mutation operations.
     *
     * <p>Returns null for read-only accessor implementations (e.g., test stubs).
     * Callers must null-check before invoking mutation operations.</p>
     *
     * @return the MutationDispatcher, or null if mutations are not supported
     */
    MutationDispatcher getMutationDispatcher();

    /**
     * Checks if a model is currently loaded.
     *
     * @return true if a model is available for querying
     */
    boolean isModelLoaded();

    /**
     * Gets the current model version identifier.
     * Used for detecting model changes during a session.
     *
     * @return version string, or null if no model loaded
     */
    String getModelVersion();

    /**
     * Gets the name of the currently active model.
     *
     * @return Optional containing the model name, or empty if no model loaded
     */
    Optional<String> getCurrentModelName();

    /**
     * Gets the unique identifier of the currently active model.
     *
     * @return Optional containing the model ID, or empty if no model loaded
     */
    Optional<String> getCurrentModelId();

    /**
     * Registers a listener for model change events.
     *
     * @param listener the listener to add
     */
    void addModelChangeListener(ModelChangeListener listener);

    /**
     * Removes a previously registered model change listener.
     *
     * @param listener the listener to remove
     */
    void removeModelChangeListener(ModelChangeListener listener);

    /**
     * Releases resources and unregisters listeners.
     * Must be called when the server stops.
     */
    void dispose();

}
