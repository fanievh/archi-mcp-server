package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddImageResultDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AdjustViewSpacingResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyElementSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyGroupSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplySpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.ConceptUsageDto;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;
import net.vheerden.archi.mcp.response.dto.DetectHubElementsResultDto;
import net.vheerden.archi.mcp.response.dto.DiagramImageDto;
import net.vheerden.archi.mcp.response.dto.DuplicateCandidate;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;
import net.vheerden.archi.mcp.response.dto.LayoutFlatViewResultDto;
import net.vheerden.archi.mcp.response.dto.ResizeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.ModelImageDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;
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
 * <p>Query methods include getElementById, getModelInfo, getViews, and
 * getViewContents.</p>
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
     * Returns the cross-view "where used" footprint for an ArchiMate concept
     * (element or relationship).
     *
     * <p>Lists every view + visual object/connection that references the concept.
     * Inverse of {@link #getViewContents(String)}; intended for impact analysis
     * before delete / rename / re-type workflows.</p>
     *
     * @param conceptId the concept ID (element or relationship)
     * @return Optional containing the usage DTO; empty if no concept with that ID
     *         exists OR the ID resolves to a non-IArchimateConcept (e.g., folder,
     *         view). The handler disambiguates these cases via a second lookup
     *         (see ModelQueryHandler.handleFindConceptUsage).
     * @throws NoModelLoadedException if no model is loaded
     */
    Optional<ConceptUsageDto> findConceptUsage(String conceptId);

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
     * @param specializationFilter specialization name to filter by (exact match, case-insensitive), or null for no filtering
     * @return list of matching elements as DTOs (empty list if no matches, never null)
     * @throws NoModelLoadedException if no model is loaded
     */
    List<ElementDto> searchElements(String query, String typeFilter, String layerFilter,
                                    String specializationFilter);

    /**
     * Searches all relationships in the model by text, type, and source/target element layer.
     *
     * <p>Case-insensitive substring matching against relationship name, documentation,
     * and property values. An empty query string returns all relationships (wildcard).
     * Filters are optional (null = no filtering).</p>
     *
     * @param query text to search for (required, empty string for wildcard)
     * @param typeFilter ArchiMate relationship type to filter by (e.g., "FlowRelationship"), or null
     * @param sourceLayerFilter ArchiMate layer of source element (e.g., "Application"), or null
     * @param targetLayerFilter ArchiMate layer of target element (e.g., "Business"), or null
     * @param specializationFilter specialization name to filter by (exact match, case-insensitive), or null for no filtering
     * @return list of matching relationships as DTOs (empty list if no matches, never null)
     * @throws NoModelLoadedException if no model is loaded
     */
    List<RelationshipDto> searchRelationships(String query, String typeFilter,
                                              String sourceLayerFilter, String targetLayerFilter,
                                              String specializationFilter);

    // ---- Specialization listing ----

    /**
     * Lists all specialization (profile) definitions in the model.
     *
     * <p>Each entry contains: name, conceptType (e.g., "Node"), conceptTypeLayer
     * (e.g., "Technology"), and usageCount (number of concepts referencing this profile).</p>
     *
     * @param conceptTypeFilter optional concept type to filter by (e.g., "Node"), or null for all
     * @return list of specialization maps (empty list if no profiles, never null)
     * @throws NoModelLoadedException if no model is loaded
     */
    List<Map<String, Object>> listSpecializations(String conceptTypeFilter);

    // ---- Specialization mutations ----

    /**
     * Creates a new specialization (profile) definition. Idempotent: if a profile
     * with the same {@code (name, conceptType)} already exists (case-insensitive),
     * the existing profile is returned and {@code created: false} is set.
     *
     * <p>Routed through the standard mutation pipeline (immediate / batch /
     * approval modes).</p>
     *
     * @param sessionId   the session ID for operational mode/approval routing
     * @param name        the profile name (required, non-blank)
     * @param conceptType the ArchiMate concept EClass name (e.g., "Node", "BusinessActor")
     * @return MutationResult containing a map with {@code name}, {@code conceptType},
     *         {@code conceptTypeLayer}, {@code created} fields
     * @throws NoModelLoadedException if no model is loaded
     */
    MutationResult<Map<String, Object>> createSpecialization(String sessionId,
            String name, String conceptType, String imagePath);

    /**
     * Back-compat overload — delegates to the canonical
     * 4-arg signature with {@code imagePath = null} (no icon).
     *
     * @deprecated use {@link #createSpecialization(String, String, String, String)}
     */
    @Deprecated
    default MutationResult<Map<String, Object>> createSpecialization(String sessionId,
            String name, String conceptType) {
        return createSpecialization(sessionId, name, conceptType, null);
    }

    /**
     * Renames an existing specialization (profile). Refuses to merge: if a
     * profile with {@code (newName, conceptType)} already exists (and is not the
     * same instance), the operation fails with {@code VALIDATION_ERROR}.
     *
     * <p>The optional {@code imagePath} (set/change icon)
     * and {@code clearImagePath} (explicit clear) parameters are mutually
     * exclusive; providing both rejects with {@code INVALID_PARAMETER}. At least
     * one of {@code newName} / {@code imagePath} / {@code clearImagePath=true}
     * MUST be supplied.</p>
     *
     * @param sessionId       the session ID for operational mode/approval routing
     * @param name            the current profile name (required)
     * @param conceptType     the ArchiMate concept EClass name (required)
     * @param newName         the new profile name; null leaves the name unchanged
     * @param imagePath       archive imagePath to apply as the new icon; null leaves unchanged
     * @param clearImagePath  if true, explicitly clear the icon; mutually exclusive with imagePath
     * @return MutationResult containing the updated profile fields
     * @throws NoModelLoadedException if no model is loaded
     */
    MutationResult<Map<String, Object>> updateSpecialization(String sessionId,
            String name, String conceptType, String newName,
            String imagePath, boolean clearImagePath);

    /**
     * Back-compat overload — delegates to the canonical
     * 6-arg signature with {@code imagePath = null} and {@code clearImagePath = false}.
     *
     * @deprecated use {@link #updateSpecialization(String, String, String, String, String, boolean)}
     */
    @Deprecated
    default MutationResult<Map<String, Object>> updateSpecialization(String sessionId,
            String name, String conceptType, String newName) {
        return updateSpecialization(sessionId, name, conceptType, newName, null, false);
    }

    /**
     * Deletes a specialization (profile) definition. By default, refuses if the
     * profile is in use; pass {@code force=true} to clear references and delete
     * in one atomic operation.
     *
     * <p><strong>Multi-profile guard:</strong> when {@code force=true}, the
     * operation refuses if any usage concept holds more than one profile, to
     * prevent silent loss of co-existing specializations. The user must detach
     * the other specializations manually first via update-element /
     * update-relationship.</p>
     *
     * @param sessionId   the session ID for operational mode/approval routing
     * @param name        the profile name (required)
     * @param conceptType the ArchiMate concept EClass name (required)
     * @param force       if true, clear references then delete; if false, refuse on usage
     * @return MutationResult containing {@code deleted: true} and
     *         {@code clearedFromConcepts} count
     * @throws NoModelLoadedException if no model is loaded
     */
    MutationResult<Map<String, Object>> deleteSpecialization(String sessionId,
            String name, String conceptType, boolean force);

    /**
     * Returns where a specialization is used in the model — pure query, no mutation.
     *
     * @param name        the profile name (required)
     * @param conceptType the ArchiMate concept EClass name (required)
     * @return a map containing {@code name}, {@code conceptType}, {@code conceptTypeLayer},
     *         {@code totalUsageCount}, {@code elements}, {@code relationships}
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException with {@code NOT_FOUND} if the profile does not exist
     */
    Map<String, Object> getSpecializationUsage(String name, String conceptType);

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

    // ---- Folder navigation ----

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

    // ---- Discovery-first patterns ----

    /**
     * Finds existing elements of the given type whose names are similar to the
     * specified name, scored above the duplicate detection threshold.
     *
     * <p>When {@code specialization} is non-null, only candidates whose <em>primary</em>
     * profile name matches (case-insensitive) are returned. When {@code specialization}
     * is null, only candidates without a primary profile match. Two elements with the
     * same name and type but different specializations are NOT considered duplicates.</p>
     *
     * <p><strong>Tier 1 limitation:</strong> Only the primary profile is considered.
     * Concepts with multiple profiles where the requested specialization matches a
     * non-primary profile will be treated as having a different specialization. This
     * matches the single-specialization semantics of the Tier 1 inline tools.</p>
     *
     * @param type           the ArchiMate element type to filter by
     * @param name           the proposed element name to compare against
     * @param specialization the proposed specialization, or null for unspecialized
     * @return list of duplicate candidates sorted by similarity score descending, capped at 10
     * @throws NoModelLoadedException if no model is loaded
     */
    List<DuplicateCandidate> findDuplicates(String type, String name, String specialization);

    /**
     * Finds an existing element matching the given type and name exactly (case-insensitive).
     *
     * @param type the ArchiMate element type to match
     * @param name the element name to match (case-insensitive)
     * @return Optional containing the matching element, or empty if not found
     * @throws NoModelLoadedException if no model is loaded
     */
    Optional<ElementDto> findExactMatch(String type, String name);

    // ---- Mutation creation methods ----

    /**
     * Creates a new ArchiMate element in the model.
     *
     * <p>Validates the type string, creates the element via EMF factory,
     * sets properties, resolves the target folder, and dispatches via
     * CommandStack. Checks operational mode to dispatch immediately
     * (GUI-attached) or queue for batch.</p>
     *
     * @param sessionId      the session identifier for mode detection
     * @param type           ArchiMate element type (e.g., "BusinessActor")
     * @param name           element name (required)
     * @param documentation  optional documentation text
     * @param properties     optional key-value properties map
     * @param folderId       optional target folder ID (null for type-default folder)
     * @param specialization optional specialization name; if non-null, the named profile is
     *                       resolved (case-insensitive) or auto-created and assigned as the
     *                       primary profile. Profile creation + element creation are wrapped
     *                       in a compound command for atomic undo.
     * @return MutationResult containing the created ElementDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if type is invalid or folder not found
     */
    MutationResult<ElementDto> createElement(String sessionId, String type, String name,
            String documentation, Map<String, String> properties, String folderId,
            String specialization);

    /**
     * Creates a new ArchiMate element with optional source traceability.
     *
     * <p>When {@code source} is non-null, its entries are merged into the element's
     * properties prefixed with "mcp.source." (e.g., source key "tool" becomes
     * property "mcp.source.tool").</p>
     *
     * @param sessionId      the session identifier for mode detection
     * @param type           ArchiMate element type (e.g., "BusinessActor")
     * @param name           element name (required)
     * @param documentation  optional documentation text
     * @param properties     optional key-value properties map
     * @param folderId       optional target folder ID (null for type-default folder)
     * @param source         optional source traceability map (keys auto-prefixed with "mcp.source.")
     * @param specialization optional specialization name
     * @return MutationResult containing the created ElementDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if type is invalid or folder not found
     */
    MutationResult<ElementDto> createElement(String sessionId, String type, String name,
            String documentation, Map<String, String> properties, String folderId,
            Map<String, String> source, String specialization);

    /**
     * Creates a new ArchiMate relationship between two elements.
     *
     * <p>Validates the relationship type, verifies source and target elements
     * exist, checks ArchiMate specification rules, and dispatches. Returns
     * structured error with valid alternatives if spec validation fails.</p>
     *
     * @param sessionId          the session identifier for mode detection
     * @param type               ArchiMate relationship type (e.g., "ServingRelationship")
     * @param sourceId           source element ID (required)
     * @param targetId           target element ID (required)
     * @param name               optional relationship name
     * @param specialization     optional specialization name; auto-creates profile if absent
     * @param semanticAttributes optional bundle of ArchiMate semantic attributes — type-conditional:
     *                           {@code accessType} for AccessRelationship, {@code associationDirected}
     *                           for AssociationRelationship, {@code influenceStrength} for
     *                           InfluenceRelationship. Pass
     *                           {@link RelationshipSemanticAttributes#NONE} or {@code null} for none.
     * @return MutationResult containing the created RelationshipDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if type invalid, elements not found, spec violation,
     *                              or semantic attribute applied to the wrong relationship type
     */
    MutationResult<RelationshipDto> createRelationship(String sessionId, String type,
            String sourceId, String targetId, String name, String specialization,
            RelationshipSemanticAttributes semanticAttributes);

    /**
     * Back-compat overload preserving the 6-arg signature for callers that don't
     * supply semantic attributes. Delegates to the 7-arg overload with
     * {@link RelationshipSemanticAttributes#NONE}.
     */
    default MutationResult<RelationshipDto> createRelationship(String sessionId, String type,
            String sourceId, String targetId, String name, String specialization) {
        return createRelationship(sessionId, type, sourceId, targetId, name, specialization,
                RelationshipSemanticAttributes.NONE);
    }

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

    /**
     * Clones an existing view (deep copy of visual layout, not model elements).
     *
     * <p>Creates a new view with the same visual structure: all view objects,
     * connections, groups, and notes are duplicated with identical positions,
     * sizes, styling, and routing. Model elements and relationships are
     * REFERENCED (not copied) — the clone shares the same underlying model
     * objects as the source view.</p>
     *
     * @param sessionId    the session identifier for mode detection
     * @param sourceViewId the ID of the view to clone (required)
     * @param newName      the name for the cloned view (required)
     * @param folderId     optional target folder ID (null to use source view's folder)
     * @return MutationResult containing the cloned ViewDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if source view not found or folder not found
     */
    MutationResult<ViewDto> cloneView(String sessionId, String sourceViewId,
            String newName, String folderId);

    // ---- Mutation update methods ----

    /**
     * Updates an existing ArchiMate element's fields.
     *
     * <p>Only non-null parameters are modified; null parameters leave the
     * corresponding field unchanged. For properties, a merge semantic applies:
     * non-null values add/update, null values remove the property key.</p>
     *
     * @param sessionId      the session identifier for mode detection
     * @param id             element ID (required)
     * @param name           new name, or null to leave unchanged
     * @param documentation  new documentation, or null to leave unchanged
     * @param properties     property merge map (null value = remove key), or null to leave unchanged
     * @param specialization new specialization name, empty string to clear all profiles, or null
     *                       to leave unchanged. Setting a value REPLACES any existing profiles
     *                       (single-profile semantics for Tier 1 tools).
     * @return MutationResult containing the updated ElementDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if element not found or no fields to update
     */
    MutationResult<ElementDto> updateElement(String sessionId, String id, String name,
            String documentation, Map<String, String> properties, String specialization);

    /**
     * Updates an existing ArchiMate relationship's mutable fields.
     *
     * <p>Only non-null parameters are modified; null parameters leave the
     * corresponding field unchanged. For properties, a merge semantic applies:
     * non-null values add/update, null values remove the property key.</p>
     *
     * <p>Source, target, and type are immutable — changing these fundamentally
     * alters the relationship's semantics and should be done via delete + create.</p>
     *
     * @param sessionId          the session identifier for mode detection
     * @param id                 relationship ID (required)
     * @param name               new name, or null to leave unchanged
     * @param documentation      new documentation, or null to leave unchanged
     * @param properties         property merge map (null value = remove key), or null to leave unchanged
     * @param specialization     new specialization name, empty string to clear, or null to leave
     *                           unchanged
     * @param semanticAttributes optional bundle of ArchiMate semantic attributes —
     *                           {@code accessType} (AccessRelationship), {@code associationDirected}
     *                           (AssociationRelationship), {@code influenceStrength}
     *                           (InfluenceRelationship). For each field, {@code null} = leave unchanged;
     *                           empty-string {@code influenceStrength} clears the value. Pass
     *                           {@link RelationshipSemanticAttributes#NONE} or {@code null} for none.
     * @return MutationResult containing the updated RelationshipDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if relationship not found, no fields to update,
     *                              or semantic attribute applied to the wrong relationship type
     */
    MutationResult<RelationshipDto> updateRelationship(String sessionId, String id, String name,
            String documentation, Map<String, String> properties, String specialization,
            RelationshipSemanticAttributes semanticAttributes);

    /**
     * Back-compat overload preserving the 6-arg signature for callers that don't
     * supply semantic attributes. Delegates to the 7-arg overload with
     * {@link RelationshipSemanticAttributes#NONE}.
     */
    default MutationResult<RelationshipDto> updateRelationship(String sessionId, String id,
            String name, String documentation, Map<String, String> properties,
            String specialization) {
        return updateRelationship(sessionId, id, name, documentation, properties, specialization,
                RelationshipSemanticAttributes.NONE);
    }

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

    // ---- Model metadata mutation ----

    /**
     * Updates the loaded model's own metadata — name, purpose, and custom properties.
     *
     * <p>Only non-null parameters are modified; null parameters leave the
     * corresponding field unchanged. For properties, a merge semantic applies:
     * non-null values add/update, null values remove the property key.</p>
     *
     * <p>Closes the model-level read/write parity gap with
     * jArchi. {@code documentation} is intentionally NOT a parameter —
     * {@code IArchimateModel} has no {@code setDocumentation(String)} in Archi 5.7/5.8
     * (the model's free-text field IS {@code purpose}).</p>
     *
     * @param sessionId      the session identifier for mode detection
     * @param name           new name, or null to leave unchanged
     * @param purpose        new purpose, or null to leave unchanged; empty string clears the purpose
     * @param properties     property merge map (null value = remove key), or null to leave unchanged
     * @return MutationResult containing the updated ModelInfoDto and optional batch sequence
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if no fields to update or name is empty
     */
    MutationResult<ModelInfoDto> updateModel(String sessionId, String name,
            String purpose, Map<String, String> properties);

    // ---- View placement ----

    /**
     * Places an existing model element onto a view as a diagram object.
     *
     * <p>Optionally positions at the given x/y coordinates with given width/height,
     * or uses auto-placement when coordinates are omitted. When autoConnect is true,
     * also creates visual connections for any existing relationships to elements
     * already on the view.</p>
     *
     * <p>The optional parentViewObjectId nests
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
            boolean autoConnect, String parentViewObjectId, StylingParams styling,
            ImageParams imageParams);

    /**
     * Creates a visual grouping rectangle on a view diagram.
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
            String parentViewObjectId, StylingParams styling, ImageParams imageParams);

    /**
     * Creates a text note on a view diagram.
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
            String content, String position, Integer gap, Integer x, Integer y,
            Integer width, Integer height,
            String parentViewObjectId, StylingParams styling, ImageParams imageParams);

    /**
     * Creates a view-reference visual object on a view that embeds another view
     * as a clickable thumbnail. The visual displays the referenced
     * view's name dynamically — Archi's figure reads {@code referencedModel.getName()}
     * at render time, so there is no stored name field on the reference (and renaming
     * the referenced view auto-updates every embedding visual without a separate
     * mutation).
     *
     * @param sessionId          the session identifier for mode detection
     * @param viewId             the TARGET view ID (required) — where the view-reference
     *                           visual is placed
     * @param referencedViewId   the SOURCE view ID being referenced (required); must
     *                           resolve to {@code IArchimateDiagramModel}
     *                           (ArchiMate views only)
     * @param x                  x coordinate (null for auto-placement; both x and y
     *                           must be specified together or both omitted)
     * @param y                  y coordinate (see x)
     * @param width              width (null for default 185)
     * @param height             height (null for default 80)
     * @param parentViewObjectId optional group/element viewObjectId to nest inside;
     *                           when non-null, x/y are RELATIVE to the parent's origin
     * @param styling            optional styling surface (StylingHelper applies)
     * @return MutationResult containing the EmbeddedViewDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found, referenced view not found or
     *                              not an ArchiMate view, parent is invalid, or bounds
     *                              fail validation
     */
    MutationResult<EmbeddedViewDto> addViewReferenceToView(String sessionId,
            String viewId, String referencedViewId, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            StylingParams styling);

    /**
     * Adds a standalone image visual ({@code IDiagramModelImage}) to a view
     * Mirrors {@link #addViewReferenceToView}, swapping
     * the typed-reference EMF class for the image-visual EMF class.
     *
     * <p>Distinct from {@code IIconic.imagePath} on element/group/note
     * view-objects — that surface is an icon overlay on an existing element;
     * this surface is a first-class image node placed directly on the view.</p>
     *
     * @param sessionId           the session identifier for mode detection
     * @param viewId              the target view's unique identifier (required)
     * @param imagePath           archive path from {@code add-image-to-model} /
     *                            {@code list-model-images} (required); must
     *                            resolve to existing bytes — typo'd paths reject
     *                            with {@code IMAGE_NOT_FOUND}
     * @param x                   optional X coordinate (with y — both-or-neither;
     *                            relative when parent set, absolute otherwise)
     * @param y                   optional Y coordinate (with x — both-or-neither)
     * @param width               optional width (default: natural image dimensions
     *                            read from archive, fallback 200)
     * @param height              optional height (default: natural image dimensions
     *                            read from archive, fallback 200)
     * @param parentViewObjectId  optional group/element viewObjectId to nest
     *                            inside; when non-null x/y are relative to parent
     * @param styling             optional styling surface
     * @return MutationResult containing the DiagramImageDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found, imagePath does not exist
     *                              in archive, parent is invalid, or bounds fail
     *                              validation
     */
    MutationResult<DiagramImageDto> addImageToView(String sessionId,
            String viewId, String imagePath, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            StylingParams styling, String borderColor, String documentation);

    /**
     * Back-compat 9-arg overload. Delegates to the canonical 11-arg signature with
     * {@code borderColor = null} and {@code documentation = null}.
     *
     * @deprecated use {@link #addImageToView(String, String, String, Integer, Integer, Integer, Integer, String, StylingParams, String, String)}
     */
    @Deprecated
    default MutationResult<DiagramImageDto> addImageToView(String sessionId,
            String viewId, String imagePath, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            StylingParams styling) {
        return addImageToView(sessionId, viewId, imagePath, x, y, width, height,
                parentViewObjectId, styling, null, null);
    }

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
     * @param styling             optional connection styling (lineColor, fontColor, lineWidth), null for defaults
     * @param showLabel           optional label visibility override, null to leave default (true)
     * @param textPosition        optional label position (0=source, 1=middle, 2=target), null for default
     * @return MutationResult containing the ViewConnectionDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if any reference is invalid or connection exists
     */
    MutationResult<ViewConnectionDto> addConnectionToView(String sessionId, String viewId,
            String relationshipId, String sourceViewObjectId, String targetViewObjectId,
            List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints,
            StylingParams styling, Boolean showLabel, Integer textPosition);

    // ---- View editing and removal ----

    /**
     * Updates the visual bounds and optionally text of a view object on a diagram.
     *
     * <p>Only non-null parameters are modified; null parameters leave the
     * corresponding field unchanged. At least one of x, y, width, height, text,
     * styling, image, or labelExpression must be non-null.</p>
     *
     * <p>The text parameter updates the label for
     * groups or content for notes. It is rejected with INVALID_PARAMETER when
     * the viewObjectId references an ArchiMate element view object.</p>
     *
     * <p>The {@code labelExpression} parameter
     * writes Archi's per-view-object dynamic label template (e.g. {@code "${name}"},
     * {@code "${property:Owner}"}). Null leaves unchanged; empty string clears
     * (Archi falls back to the element's static name). Stored as a generic
     * {@code IFeatures} entry on the diagram object (no token validation; Archi
     * owns the grammar).</p>
     *
     * @param sessionId       the session identifier for mode detection
     * @param viewObjectId    the view object's unique identifier (required)
     * @param x               new X coordinate, or null to leave unchanged
     * @param y               new Y coordinate, or null to leave unchanged
     * @param width           new width, or null to leave unchanged
     * @param height          new height, or null to leave unchanged
     * @param text            new text for groups (label) or notes (content), or null to leave unchanged
     * @param styling         styling parameters, or null for no styling change
     * @param imageParams     image parameters, or null for no image change
     * @param labelExpression new label expression, or null to leave unchanged; empty string clears
     * @param anchorTarget    view-object id this object is anchored to, or null to leave the anchor
     *                        unchanged; empty string clears the anchor
     * @param anchorEdge      the target edge to track ({@code below}/{@code above}/{@code right}/{@code left}),
     *                        or null to default to {@code below} when setting an anchor
     * @param anchorDx        offset along/against the anchor edge (x), or null for 0
     * @param anchorDy        gap from the anchor edge (y), or null for 0
     * @return MutationResult containing the updated ViewObjectDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view object not found, no fields to update, or text on element
     */
    MutationResult<ViewObjectDto> updateViewObject(String sessionId,
            String viewObjectId, Integer x, Integer y, Integer width, Integer height,
            String text, StylingParams styling, ImageParams imageParams,
            String labelExpression, String anchorTarget, String anchorEdge,
            Integer anchorDx, Integer anchorDy);

    /**
     * Backward-compatible overload without anchor parameters — forwards with a null anchor
     * (no anchor change). Existing callers keep the pre-anchor 10-argument signature.
     */
    default MutationResult<ViewObjectDto> updateViewObject(String sessionId,
            String viewObjectId, Integer x, Integer y, Integer width, Integer height,
            String text, StylingParams styling, ImageParams imageParams,
            String labelExpression) {
        return updateViewObject(sessionId, viewObjectId, x, y, width, height, text,
                styling, imageParams, labelExpression, null, null, null, null);
    }

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
     * @param showLabel           optional label visibility override, null to leave unchanged
     * @param textPosition        optional label position (0=source, 1=middle, 2=target), null for no change
     * @return MutationResult containing the updated ViewConnectionDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if connection not found
     */
    MutationResult<ViewConnectionDto> updateViewConnection(String sessionId,
            String viewConnectionId, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling,
            Boolean showLabel, Integer textPosition);

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
     * in a single undo unit with no operation count limit.
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

    // ---- Layout assessment ----

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

    /**
     * Assesses layout quality with optional per-metric violator IDs.
     *
     * @param viewId the view to assess
     * @param includeViolatorIds if true, response includes violatorIds map
     * @return assessment result DTO
     * @throws ModelAccessException if view not found
     */
    AssessLayoutResultDto assessLayout(String viewId, boolean includeViolatorIds);

    /**
     * Returns the content bounding box for a view, excluding notes.
     * Used by add-note-to-view to compute position-based placement.
     *
     * @param viewId the view's unique identifier (required)
     * @return ContentBounds with absolute canvas coordinates, or null if view is empty
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found
     */
    ContentBounds getContentBounds(String viewId);

    // ---- Auto-route connections ----

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
     * @param autoNudge     when true, automatically applies move recommendations and re-routes
     *                      in a single atomic operation. Ignored when force is true.
     * @param perimeterMargin exterior perimeter extension in pixels beyond outermost obstacles.
     *                        Larger values give A* more space for exterior routing around dense element clusters.
     * @param mode            scope of path the call may touch: "full" (default) re-routes
     *                        whole connections via the visibility-graph A* router; "terminals-only"
     *                        leaves all intermediate bendpoints unchanged and only modifies the
     *                        first and/or last bendpoint to ensure terminal segments are orthogonal.
     *                        terminals-only is mutually exclusive with strategy="clear" and
     *                        autoNudge=true. null or blank treated as "full".
     * @return MutationResult containing AutoRouteResultDto with routing counts
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found, connection not found, or invalid strategy/mode
     */
    MutationResult<AutoRouteResultDto> autoRouteConnections(
            String sessionId, String viewId,
            List<String> connectionIds, String strategy, boolean force,
            boolean autoNudge, int snapThreshold, int perimeterMargin, String mode);

    /**
     * Overload with the channel-global ordered nudging gate.
     *
     * <p>When {@code enableChannelNudging} is true (default), routes are post-processed by
     * a channel-global ordered nudging pass that centres single-occupant routes in their
     * corridors and fans out parallel runs sharing a corridor. Set false to A/B compare or
     * to opt out.</p>
     */
    MutationResult<AutoRouteResultDto> autoRouteConnections(
            String sessionId, String viewId,
            List<String> connectionIds, String strategy, boolean force,
            boolean autoNudge, int snapThreshold, int perimeterMargin, String mode,
            boolean enableChannelNudging);

    // ---- Auto-layout-and-route ----

    /**
     * Applies ELK Layered algorithm to compute both element positions AND
     * connection routes in a single operation. Replaces all element positions
     * and computes orthogonal connection bendpoints.
     *
     * <p>Supports two modes: "auto" (default, ELK Layered) and "grouped" (orchestrated
     * Branch 2 workflow: layout-within-group + arrange-groups + optimize-group-order +
     * auto-route-connections). Grouped mode is best for views with structural groups.</p>
     *
     * @param sessionId    the session identifier for mode detection
     * @param viewId       the view's unique identifier (required)
     * @param mode         layout mode: "auto" (ELK, default) or "grouped" (Branch 2 orchestration)
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
            String sessionId, String viewId, String mode,
            String direction, int spacing, String targetRating);

    // ---- Adjust view spacing ----

    /**
     * Inflates inter-element, parent-padding, and inter-group spacing by user-specified
     * deltas, preserving relative positions, then auto-re-routes connections and returns
     * combined routing + assessment results in a single call.
     *
     * <p>Only works on grouped views. For each top-level group, detects the current
     * arrangement and spacing, then re-runs layoutWithinGroup with inflated values.
     * When {@code recursive} is true (default), nested subgroups are inflated bottom-up.
     * After intra-group inflation, groups are pushed apart by {@code interGroupDelta}.
     * The entire operation (inflate + re-route) is a single undo step.</p>
     *
     * @param sessionId         the session identifier for mode detection
     * @param viewId            the view's unique identifier (required)
     * @param interElementDelta pixels to add between elements within groups (null = 0)
     * @param paddingDelta      pixels to add to group edge padding (null = 0)
     * @param interGroupDelta   pixels to add between adjacent groups (null = 0)
     * @param recursive         if true, inflate nested subgroups too (default true)
     * @return MutationResult containing AdjustViewSpacingResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found, no groups, or invalid parameters
     */
    MutationResult<AdjustViewSpacingResultDto> adjustViewSpacing(
            String sessionId, String viewId,
            Integer interElementDelta, Integer paddingDelta,
            Integer interGroupDelta, boolean recursive);

    // ---- Apply element spacing recommendations (RoutingPreconditions.InterElement) ----

    /**
     * Convenience tool: reads the view's current connection count and per-group
     * element spacing, consults the inter-element heuristics table (≤15 → 60px,
     * 16–30 → 80px, &gt;30 → 100px), computes {@code interElementDelta =
     * max(0, target - current)}, and (when not dryRun and delta &gt; 0) calls
     * {@link #adjustViewSpacing(String, String, Integer, Integer, Integer, boolean)}.
     * Returns a single envelope containing the before snapshot, the after
     * snapshot, the delegate adjust-view-spacing result, and the heuristic
     * computation transparency fields.
     *
     * <p>Uses the same connection-count source as {@link #assessLayout(String)}
     * (single source of truth — both call
     * {@code AssessmentCollector.collectAssessmentConnections(...)} via the
     * same `assessLayout` invocation). Uses the same spacing-detection utility
     * as {@link #adjustViewSpacing(String, String, Integer, Integer, Integer, boolean)}
     * — both call {@code GroupLayoutCalculator.detectSpacingFromPositions(...)}.</p>
     *
     * <p>{@code currentSpacingPx} is computed as the MIN per-group element
     * spacing across all top-level groups that have at least 2 non-note
     * children (most-tight group wins; aligns with visual-severity hierarchy
     * where coincident segments form where spacing is tightest). When the
     * view has no top-level groups OR has groups but none with 2+ non-note
     * children, the call **gracefully short-circuits** with a populated
     * {@code noChangeReason} — it does NOT throw the
     * "requires a view with groups" exception that {@code adjustViewSpacing}
     * raises (the convenience tool's role is to be safely callable from any
     * view; an exception would be a footgun, and a dry-run user wants
     * informational feedback rather than an error). Likewise when
     * {@code connectionCount == 0}, the tool short-circuits with a
     * {@code noChangeReason} explaining that the heuristic does not apply
     * to a view with no connections.</p>
     *
     * <p>{@code dryRun=true} short-circuits before calling
     * {@code adjustViewSpacing} — no mutation occurs and no speculative-mutate-
     * and-undo. The response carries the {@code before} snapshot and the
     * recommendation only.</p>
     *
     * @param sessionId             the session identifier for mode detection
     * @param viewId                the view's unique identifier (required)
     * @param dryRun                when true, compute the recommendation
     *                              without mutating; when false (default),
     *                              apply the inflation
     * @param targetSpacingOverride optional explicit target spacing in pixels;
     *                              when non-null, overrides the heuristic
     *                              tier lookup. The response still reports
     *                              {@code heuristicRecommendation} for
     *                              transparency.
     * @return MutationResult containing ApplyElementSpacingRecommendationsResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or invalid parameters
     */
    MutationResult<ApplyElementSpacingRecommendationsResultDto>
            applyElementSpacingRecommendations(
                    String sessionId, String viewId,
                    boolean dryRun, Integer targetSpacingOverride);

    /**
     * Control-loop entry point for the apply-element-spacing-recommendations
     * convenience tool. Sibling-symmetric with the
     * existing 4-arg signature; adds an explicit {@code iterationBudget}
     * parameter so callers can cap the observe → decide → back-off control
     * loop's iteration count.
     *
     * <p>The default interface implementation delegates to the existing
     * 4-arg signature, IGNORING {@code iterationBudget} — preserves
     * backwards-compat for the 30+ test stubs that implement only the 4-arg
     * via {@code BaseTestAccessor} extends pattern. The canonical
     * {@link ArchiModelAccessorImpl} overrides
     * BOTH signatures: the 4-arg delegates to this 5-arg with the default
     * budget (5); the 5-arg embeds the {@code SpacingControlLoop} iterate
     * body.</p>
     *
     * @param iterationBudget caller-tunable iteration budget; null →
     *                        default 5; out-of-range [1, 20] →
     *                        {@code INVALID_PARAMETER}
     */
    default MutationResult<ApplyElementSpacingRecommendationsResultDto>
            applyElementSpacingRecommendations(
                    String sessionId, String viewId,
                    boolean dryRun, Integer targetSpacingOverride,
                    Integer iterationBudget) {
        return applyElementSpacingRecommendations(
                sessionId, viewId, dryRun, targetSpacingOverride);
    }

    // ---- Apply group spacing recommendations (RoutingPreconditions.InterGroup) ----

    /**
     * Convenience tool: reads the view's current connection count + inter-
     * group connection count + current minimum inter-group spacing, consults
     * the inter-group heuristics table (≤15 → 80px connected / 40px
     * unconnected, 16–30 → 100/40px, &gt;30 → 120/60px), computes
     * {@code interGroupDelta = max(0, target - current)}, and (when not
     * dryRun and delta &gt; 0) calls
     * {@link #adjustViewSpacing(String, String, Integer, Integer, Integer, boolean)}
     * with that {@code interGroupDelta}. Returns a single envelope containing
     * the before snapshot, the after snapshot, the delegate adjust-view-
     * spacing result, and the heuristic computation transparency fields.
     *
     * <p>Uses the same connection-count source as {@link #assessLayout(String)}
     * (single source of truth — both call
     * {@code AssessmentCollector.collectAllConnections(...)} via the same
     * {@code assessLayout} invocation). Uses
     * {@code GroupLayoutCalculator.detectInterGroupSpacing(groupRects)} for
     * current-spacing detection (sibling utility to
     * {@code detectSpacingFromPositions} used by the inter-element tool).</p>
     *
     * <p>The {@code isConnected} column-selection determination is computed
     * by walking the same connection enumeration and counting connections
     * whose source and target resolve to DIFFERENT top-level groups (where
     * "top-level group" means an {@code IDiagramModelGroup} whose immediate
     * container is the {@code IArchimateDiagramModel} itself, not a nested
     * group). One-side-grouped pairings (one endpoint in a group, the other
     * ungrouped) are NOT counted as inter-group — the heuristic's
     * connected/unconnected distinction is about <em>between-group</em>
     * routing-corridor demand, which requires two groups.</p>
     *
     * <p><strong>Composition strategy:</strong> this tool composes
     * {@link #adjustViewSpacing(String, String, Integer, Integer, Integer, boolean)}
     * with {@code interGroupDelta} non-null and the other deltas null —
     * inflate-only / preserves manual placement / single undo step. It does
     * NOT call {@code optimizeGroupOrder} or {@code arrangeGroups}; for
     * topology-driven re-layout call those primitives directly.</p>
     *
     * <p>{@code currentSpacingPx} is the MIN gap between adjacent top-level
     * groups along the dominant axis (most-tight pair wins; aligns with
     * visual-severity hierarchy where edge-coincident routing forms in the
     * tightest inter-group corridor). When the view has fewer than 2 top-
     * level groups OR has 2+ groups but the current spacing already meets/
     * exceeds the heuristic target, the call <strong>gracefully short-
     * circuits</strong> with a populated {@code noChangeReason} — it does
     * NOT throw an exception (the convenience tool's role is to be safely
     * callable from any view; an exception would be a footgun).</p>
     *
     * <p>{@code dryRun=true} short-circuits before calling
     * {@code adjustViewSpacing} — no mutation occurs and no speculative-
     * mutate-and-undo. The response carries the {@code before} snapshot and
     * the recommendation only.</p>
     *
     * @param sessionId             the session identifier for mode detection
     * @param viewId                the view's unique identifier (required)
     * @param dryRun                when true, compute the recommendation
     *                              without mutating; when false (default),
     *                              apply the inflation
     * @param targetSpacingOverride optional explicit target spacing in
     *                              pixels; when non-null, overrides the
     *                              heuristic tier+column lookup. The
     *                              response still reports
     *                              {@code heuristicRecommendation} for
     *                              transparency.
     * @return MutationResult containing ApplyGroupSpacingRecommendationsResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or invalid parameters
     */
    MutationResult<ApplyGroupSpacingRecommendationsResultDto>
            applyGroupSpacingRecommendations(
                    String sessionId, String viewId,
                    boolean dryRun, Integer targetSpacingOverride);

    /**
     * Control-loop entry point for the apply-group-spacing-recommendations
     * convenience tool. Sibling-symmetric with
     * {@link #applyElementSpacingRecommendations(String, String, boolean, Integer, Integer)}.
     *
     * @param iterationBudget caller-tunable iteration budget; null →
     *                        default 5
     */
    default MutationResult<ApplyGroupSpacingRecommendationsResultDto>
            applyGroupSpacingRecommendations(
                    String sessionId, String viewId,
                    boolean dryRun, Integer targetSpacingOverride,
                    Integer iterationBudget) {
        return applyGroupSpacingRecommendations(
                sessionId, viewId, dryRun, targetSpacingOverride);
    }

    // ---- Apply spacing recommendations (composed; RoutingPreconditions.Composed) ----

    /**
     * Composed convenience tool: reads the view's current spacing baselines
     * AND connection counts (total + inter-group) in a single pass, consults
     * BOTH the inter-element heuristic
     * ({@link ElementSpacingHeuristic#targetSpacingForConnectionCount}) AND
     * the inter-group heuristic
     * ({@link GroupSpacingHeuristic#targetSpacingForConnectionCount}),
     * clamps each proposed delta to the inflation-knee guard
     * ({@value ApplySpacingDecision#ELEMENT_KNEE_LIMIT_PX}px element /
     * {@value ApplySpacingDecision#GROUP_KNEE_LIMIT_PX}px inter-group), and
     * (when not {@code dryRun} and at least one clamped delta &gt; 0) calls
     * {@link #adjustViewSpacing(String, String, Integer, Integer, Integer, boolean)}
     * ONCE with the scope-appropriate non-null deltas. Returns a single
     * envelope with both deltas + clamp metadata + before/after snapshots.
     *
     * <p><strong>Distinctive value-prop versus the two sibling tools
     * ({@link #applyElementSpacingRecommendations} +
     * {@link #applyGroupSpacingRecommendations})</strong> is THREE structural
     * disciplines they cannot offer when called separately:
     * (1) single transactional call + single undo step + single re-route
     * pass; (2) inflation-knee guard enforced INSIDE the tool (per-call
     * clamp; not session-tracked); (3) bundled scope dispatch via the
     * {@code scope} enum.</p>
     *
     * <p><strong>{@code scope} dispatch</strong>:
     * <ul>
     *   <li>{@code "both"} (default) — compute BOTH element + group deltas
     *       and pass both to a single {@code adjustViewSpacing} call.</li>
     *   <li>{@code "element"} — compute element delta only; pass
     *       {@code interGroupDelta=null} to {@code adjustViewSpacing}.
     *       Equivalent to {@link #applyElementSpacingRecommendations} PLUS
     *       the knee-clamp guard.</li>
     *   <li>{@code "group"} — compute group delta only; pass
     *       {@code interElementDelta=null} to {@code adjustViewSpacing}.
     *       Equivalent to {@link #applyGroupSpacingRecommendations} PLUS
     *       the knee-clamp guard.</li>
     *   <li>Any other value — {@link IllegalArgumentException} raised by
     *       {@link ApplySpacingDecision#decide}, translated to the
     *       {@code error.code = "invalid_argument"} envelope by the
     *       handler.</li>
     * </ul></p>
     *
     * <p><strong>Knee-guard rule.</strong> Each proposed delta is clamped
     * to NO MORE than +{@value ApplySpacingDecision#ELEMENT_KNEE_LIMIT_PX}px
     * (element) / +{@value ApplySpacingDecision#GROUP_KNEE_LIMIT_PX}px
     * (inter-group) from the view's current spacing baselines. Beyond
     * this cumulative-from-current point, spacing diagnostics
     * showed passThroughs / nonOrthogonalTerminals /
     * xings-per-connection regress. The clamp fires per-call (not session-
     * tracked); successive calls each re-detect current spacing.</p>
     *
     * <p><strong>Short-circuit behaviour.</strong> When BOTH clamped deltas
     * are 0 (whether from structural impossibility — no groups, fewer than
     * 2 top-level groups, no connections, no children-with-multiple-siblings
     * — OR from deltas computing to zero against current spacing), the call
     * gracefully short-circuits with a populated {@code noChangeReason} —
     * NO {@code adjustViewSpacing} invocation. When ONE arm produces a
     * non-zero clamped delta and the other is 0, {@code adjustViewSpacing}
     * is invoked ONCE with the non-zero arm + {@code null} for the
     * zero-delta arm.</p>
     *
     * <p>{@code dryRun=true} short-circuits before calling
     * {@code adjustViewSpacing} — no mutation occurs and no speculative-
     * mutate-and-undo. The response carries the {@code before} snapshot,
     * both deltas, both clamp flags, and both proposedXxxDelta values; the
     * {@code after} snapshot and {@code adjustResult} are null.</p>
     *
     * <p>Pinned by {@code ApplySpacingRecommendationsToolTest}.</p>
     *
     * @param sessionId                  the session identifier for mode
     *                                   detection
     * @param viewId                     the view's unique identifier
     *                                   (required)
     * @param scope                      one of {@code "both"} (default
     *                                   when null),
     *                                   {@code "element"}, {@code "group"}
     * @param dryRun                     when true, compute the recommendation
     *                                   without mutating; when false
     *                                   (default), apply the inflation
     * @param elementTargetSpacingOverride optional explicit target element
     *                                   spacing; non-null overrides the
     *                                   heuristic. The knee-clamp still
     *                                   applies on top of the override.
     * @param groupTargetSpacingOverride optional explicit target inter-group
     *                                   spacing; non-null overrides the
     *                                   heuristic. The knee-clamp still
     *                                   applies on top of the override.
     * @return MutationResult containing ApplySpacingRecommendationsResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or invalid parameters
     */
    MutationResult<ApplySpacingRecommendationsResultDto>
            applySpacingRecommendations(
                    String sessionId, String viewId,
                    String scope, boolean dryRun,
                    Integer elementTargetSpacingOverride,
                    Integer groupTargetSpacingOverride);

    /**
     * Control-loop entry point for the apply-spacing-recommendations composer.
     * Sibling-symmetric with the apply-element + apply-group control-loop
     * entry points; composer fires TWO coordinated control loops
     * (element-arm first, then group-arm).
     *
     * @param iterationBudget caller-tunable iteration budget; null →
     *                        default 8 (composer covers both arms);
     *                        out-of-range [1, 20] → {@code INVALID_PARAMETER}
     */
    default MutationResult<ApplySpacingRecommendationsResultDto>
            applySpacingRecommendations(
                    String sessionId, String viewId,
                    String scope, boolean dryRun,
                    Integer elementTargetSpacingOverride,
                    Integer groupTargetSpacingOverride,
                    Integer iterationBudget) {
        return applySpacingRecommendations(
                sessionId, viewId, scope, dryRun,
                elementTargetSpacingOverride, groupTargetSpacingOverride);
    }

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
     * @param showLabel         optional: set to false to suppress labels on all created connections,
     *                          true to show labels explicitly, or null to use Archi default (shown)
     * @return MutationResult containing AutoConnectResultDto with connection counts
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found, element not on view, or invalid type
     */
    MutationResult<AutoConnectResultDto> autoConnectView(
            String sessionId, String viewId,
            List<String> elementIds, List<String> relationshipTypes,
            Boolean showLabel, StylingParams styling);

    // ---- Layout within group ----

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
            Integer columns, Integer spacing, java.util.List<String> groupIds,
            String direction);

    /**
     * Optimizes element order within groups to minimize inter-group edge crossings.
     *
     * <p>Uses a barycentric heuristic to reorder elements within each group,
     * then re-lays them out using the specified arrangement pattern. Group structure
     * and membership are preserved — no elements are moved between groups.</p>
     *
     * @param sessionId          the session identifier for mode detection
     * @param viewId             the view to optimize (required)
     * @param arrangement        arrangement pattern for re-layout: "row", "column", or "grid" (optional;
     *                           when omitted, each group's arrangement is auto-detected from current positions)
     * @param spacing            space between elements in pixels (default: 20)
     * @param padding            space from group edges in pixels (default: 10)
     * @param elementWidth       optional: resize all children to this width
     * @param elementHeight      optional: resize all children to this height
     * @param autoWidth          compute each element's width from its label text (default: false)
     * @param columns            optional: number of columns for grid arrangement
     * @param groupArrangements  optional per-group arrangement overrides (groupId → "row"/"column"/"grid")
     * @return MutationResult containing OptimizeGroupOrderResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or no groups with inter-group connections
     */
    MutationResult<OptimizeGroupOrderResultDto> optimizeGroupOrder(
            String sessionId, String viewId, String arrangement,
            Integer spacing, Integer padding, Integer elementWidth,
            Integer elementHeight, boolean autoWidth, Integer columns,
            Map<String, String> groupArrangements);

    // ---- Flat view layout ----

    /**
     * Positions all top-level elements on a flat view using a configurable arrangement.
     *
     * <p>Collects all top-level view objects (elements and groups, excluding notes)
     * and repositions them using row, column, or grid arrangement. Respects each
     * element's current size (heterogeneous sizes supported). Does not affect
     * elements nested inside groups. Does not modify connection routing.</p>
     *
     * @param sessionId     the session identifier for mode detection
     * @param viewId        the view to layout (required)
     * @param arrangement   layout pattern: "row", "column", or "grid" (required)
     * @param spacing       space between elements in pixels (default: 40)
     * @param padding       margin from view origin in pixels (default: 20)
     * @param sortBy        optional: sort elements by "name", "type", or "layer" before layout
     * @param categoryField optional: group elements into visual sections by "type" or "layer"
     * @param columns       optional: number of columns for grid arrangement (auto-detected if null)
     * @param autoLayoutChildren auto-layout embedded children within parent elements (default: true)
     * @return MutationResult containing LayoutFlatViewResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or invalid parameters
     */
    MutationResult<LayoutFlatViewResultDto> layoutFlatView(
            String sessionId, String viewId, String arrangement,
            Integer spacing, Integer padding, String sortBy,
            String categoryField, Integer columns,
            boolean autoLayoutChildren);

    // ---- Resize elements to fit ----

    /**
     * Resizes elements on a view to fit their label text using font-metrics-based sizing.
     * Two-pass algorithm for nested containment: children sized first, then parents.
     *
     * @param sessionId  the session identifier for mode detection
     * @param viewId     the view's unique identifier (required)
     * @param elementIds optional list of specific element view object IDs to resize;
     *                   if null or empty, resizes all elements on the view
     * @return MutationResult containing ResizeElementsResultDto
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found
     */
    MutationResult<ResizeElementsResultDto> resizeElementsToFit(
            String sessionId, String viewId, List<String> elementIds);

    /**
     * Resizes elements to fit their labels, with an opt-in <strong>wrap-fit</strong> mode.
     *
     * <p>When {@code wrapFit} is {@code false}, behaviour is identical to
     * {@link #resizeElementsToFit(String, String, List)} (single-line aspect-ratio sizing via
     * {@code ElementSizer.computeAutoSize}). When {@code true}, each targeted leaf <em>keeps its
     * current width</em> and only grows its height so the label wraps and fits
     * ({@code ElementSizer.computeWrapFitDimensions}); ancestor containers are grown
     * height-only to contain the now-taller children. This preserves a dense grid's horizontal
     * pitch (no sibling shift) — the lever that takes embedded ApplicationFunctions out of the
     * clipped 150×26 state without the bloat/destruction of single-line sizing.</p>
     *
     * <p>Default implementation delegates to the 3-arg overload (ignoring {@code wrapFit}) so that
     * test stubs and alternate accessors need no change; {@code ArchiModelAccessorImpl} overrides
     * this with the real wrap-fit path.</p>
     *
     * @param sessionId  the session identifier for mode detection
     * @param viewId     the view's unique identifier (required)
     * @param elementIds optional list of specific element view object IDs to resize;
     *                   if null or empty, resizes all elements on the view
     * @param wrapFit    when true, keep width and grow height to wrap; when false, single-line sizing
     * @return MutationResult containing ResizeElementsResultDto
     */
    default MutationResult<ResizeElementsResultDto> resizeElementsToFit(
            String sessionId, String viewId, List<String> elementIds, boolean wrapFit) {
        return resizeElementsToFit(sessionId, viewId, elementIds);
    }

    // ---- Hub element detection ----

    /**
     * Detects hub elements on a view by counting visual connections per element,
     * sorted by connection count descending. Read-only — no model modifications.
     *
     * @param viewId the view's unique identifier (required)
     * @return DetectHubElementsResultDto with sorted elements, summary stats, and suggestions
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found
     */
    DetectHubElementsResultDto detectHubElements(String viewId);

    // ---- Deletion methods ----

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

    // ---- Folder mutation methods ----

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

    // ---- Bulk mutation ----

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
     * @param operations      the list of operations to execute (max {@value BulkOperation#MAX_OPERATIONS})
     * @param description     optional label for the compound command (undo history), may be null
     * @param continueOnError if true, failed operations are skipped and reported separately
     * @return BulkMutationResult with per-operation results (and failures when continueOnError)
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException   if any operation fails validation and continueOnError is false
     * @throws net.vheerden.archi.mcp.model.exceptions.MutationException if dispatch fails
     */
    BulkMutationResult executeBulk(String sessionId, List<BulkOperation> operations,
            String description, boolean continueOnError);

    /**
     * As {@link #executeBulk(String, List, boolean)} but carrying the optional agent-supplied
     * {@code intent}. When approval mode is on, {@code intent} is persisted on the
     * single bulk proposal ({@code PendingProposal.intent}) and flows to the card's quiet
     * {@code agent's note:} line. The server never depends on {@code intent}; with it null this
     * behaves byte-identically to the four-arg overload.
     *
     * @param intent the agent's optional stated intent for this batch, or null
     */
    BulkMutationResult executeBulk(String sessionId, List<BulkOperation> operations,
            String description, boolean continueOnError, String intent);

    // ---- View export ----

    /**
     * Renders a view to an image in the specified format.
     *
     * <p>PNG and JPG rendering use SWT {@code DiagramUtils.createImage()} on
     * the Display thread (core SWT, no external bundle). SVG and PDF rendering
     * require the optional {@code com.archimatetool.export.svg} bundle (the
     * same bundle provides both formats; PDF uses Apache FOP transcoders that
     * ship inside the bundle's {@code lib/} directory).</p>
     *
     * @param viewId          the view's unique identifier (required)
     * @param format          output format: "png", "jpg", "svg", or "pdf" (required)
     * @param scale           rendering scale factor (1.0 = 100%); applies to
     *                        raster formats (PNG/JPG); vector providers (SVG/PDF)
     *                        ignore scale because they emit resolution-independent output
     * @param quality         JPEG encoding quality (1–100); applied only when
     *                        {@code format} is "jpg"; silently ignored for other formats
     * @param inline          true to return image bytes, false to write to file
     * @param outputDirectory custom output directory path (null to use temp dir)
     * @return ExportResult containing metadata + optional image bytes/SVG content
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if view not found or format not available
     */
    ExportResult exportView(String viewId, String format, double scale, int quality,
            boolean inline, String outputDirectory);

    // ---- Command stack undo/redo ----

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

    // ---- Mutation support ----

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

    // ---- Image management ----

    /**
     * Adds an image to the model's archive for use on view objects.
     *
     * @param sessionId    the session identifier for mode detection
     * @param imageData    raw image bytes (decoded from base64)
     * @param filenameHint filename hint for extension detection (e.g. "icon.png")
     * @return AddImageResultDto containing archive path, dimensions, and format
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if image data is invalid or format unsupported
     */
    AddImageResultDto addImageToModel(String sessionId, byte[] imageData, String filenameHint);

    /**
     * Adds an image to the model's archive from a local file path.
     *
     * @param sessionId the session identifier for mode detection
     * @param filePath  absolute path to a local image file
     * @return AddImageResultDto containing archive path, dimensions, and format
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if file doesn't exist, is not readable, exceeds 1MB, or is not a valid image
     */
    AddImageResultDto addImageFromFilePath(String sessionId, String filePath);

    /**
     * Adds an image to the model's archive by downloading from a URL.
     *
     * @param sessionId the session identifier for mode detection
     * @param url       HTTP or HTTPS URL to download the image from
     * @return AddImageResultDto containing archive path, dimensions, and format
     * @throws NoModelLoadedException if no model is loaded
     * @throws ModelAccessException if URL is unreachable, download fails, exceeds 1MB, or content is not a valid image
     */
    AddImageResultDto addImageFromUrl(String sessionId, String url);

    /**
     * Lists all images currently stored in the model's archive.
     *
     * @param sessionId the session identifier
     * @return list of ModelImageDto with paths and dimensions
     * @throws NoModelLoadedException if no model is loaded
     */
    List<ModelImageDto> listModelImages(String sessionId);

    /**
     * Releases resources and unregisters listeners.
     * Must be called when the server stops.
     */
    void dispose();

}
