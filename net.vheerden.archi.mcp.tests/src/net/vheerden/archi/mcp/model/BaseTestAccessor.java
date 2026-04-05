package net.vheerden.archi.mcp.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddImageResultDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.DetectHubElementsResultDto;
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
import net.vheerden.archi.mcp.response.dto.LayoutFlatViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.ModelImageDto;
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
 * Base test accessor with default no-op implementations for all
 * {@link ArchiModelAccessor} methods. Test-specific stub accessors
 * extend this class and override only the methods they need.
 *
 * <p>Supports a {@code modelLoaded} flag that controls whether query
 * methods throw {@link NoModelLoadedException} (matching the pattern
 * established by existing test stubs).</p>
 *
 * <p><strong>Story 7-0b:</strong> Created to prevent existing test
 * StubAccessor classes from breaking when new accessor methods are
 * added (e.g., folder navigation methods).</p>
 */
public class BaseTestAccessor implements ArchiModelAccessor {

    private final boolean modelLoaded;

    public BaseTestAccessor() {
        this(true);
    }

    public BaseTestAccessor(boolean modelLoaded) {
        this.modelLoaded = modelLoaded;
    }

    @Override
    public boolean isModelLoaded() {
        return modelLoaded;
    }

    @Override
    public ModelInfoDto getModelInfo() {
        if (!modelLoaded) throw new NoModelLoadedException();
        return null;
    }

    @Override
    public Optional<ElementDto> getElementById(String id) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Optional.empty();
    }

    @Override
    public List<ElementDto> getElementsByIds(List<String> ids) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Collections.emptyList();
    }

    @Override
    public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Collections.emptyList();
    }

    @Override
    public List<RelationshipDto> searchRelationships(String query, String typeFilter,
                                                      String sourceLayerFilter, String targetLayerFilter) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Collections.emptyList();
    }

    @Override
    public List<RelationshipDto> getRelationshipsForElement(String elementId) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Collections.emptyList();
    }

    @Override
    public List<ViewDto> getViews(String viewpointFilter) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Collections.emptyList();
    }

    @Override
    public Optional<ViewContentsDto> getViewContents(String viewId) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Optional.empty();
    }

    @Override
    public List<FolderDto> getRootFolders() {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Collections.emptyList();
    }

    @Override
    public Optional<FolderDto> getFolderById(String id) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Optional.empty();
    }

    @Override
    public List<FolderDto> getFolderChildren(String parentId) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Collections.emptyList();
    }

    @Override
    public List<FolderTreeDto> getFolderTree(String rootId, int maxDepth) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Collections.emptyList();
    }

    @Override
    public List<FolderDto> searchFolders(String nameQuery) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Collections.emptyList();
    }

    @Override
    public List<DuplicateCandidate> findDuplicates(String type, String name) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Collections.emptyList();
    }

    @Override
    public Optional<ElementDto> findExactMatch(String type, String name) {
        if (!modelLoaded) throw new NoModelLoadedException();
        return Optional.empty();
    }

    @Override
    public BulkMutationResult executeBulk(String sessionId, List<BulkOperation> operations,
            String description, boolean continueOnError) {
        throw new UnsupportedOperationException("executeBulk not implemented in test accessor");
    }

    @Override
    public MutationResult<ElementDto> createElement(String sessionId, String type, String name,
            String documentation, Map<String, String> properties, String folderId) {
        throw new UnsupportedOperationException("createElement not implemented in test accessor");
    }

    @Override
    public MutationResult<ElementDto> createElement(String sessionId, String type, String name,
            String documentation, Map<String, String> properties, String folderId,
            Map<String, String> source) {
        throw new UnsupportedOperationException("createElement not implemented in test accessor");
    }

    @Override
    public MutationResult<RelationshipDto> createRelationship(String sessionId, String type,
            String sourceId, String targetId, String name) {
        throw new UnsupportedOperationException("createRelationship not implemented in test accessor");
    }

    @Override
    public MutationResult<ViewDto> createView(String sessionId, String name,
            String viewpoint, String folderId, String connectionRouterType) {
        throw new UnsupportedOperationException("createView not implemented in test accessor");
    }

    @Override
    public MutationResult<ElementDto> updateElement(String sessionId, String id, String name,
            String documentation, Map<String, String> properties) {
        throw new UnsupportedOperationException("updateElement not implemented in test accessor");
    }

    @Override
    public MutationResult<ViewDto> updateView(String sessionId, String id, String name,
            String viewpoint, String documentation, Map<String, String> properties,
            String connectionRouterType) {
        throw new UnsupportedOperationException("updateView not implemented in test accessor");
    }

    @Override
    public MutationResult<AddToViewResultDto> addToView(String sessionId, String viewId,
            String elementId, Integer x, Integer y, Integer width, Integer height,
            boolean autoConnect, String parentViewObjectId, StylingParams styling,
            ImageParams imageParams) {
        throw new UnsupportedOperationException("addToView not implemented in test accessor");
    }

    @Override
    public MutationResult<ViewGroupDto> addGroupToView(String sessionId, String viewId,
            String label, Integer x, Integer y, Integer width, Integer height,
            String parentViewObjectId, StylingParams styling, ImageParams imageParams) {
        throw new UnsupportedOperationException("addGroupToView not implemented in test accessor");
    }

    @Override
    public MutationResult<ViewNoteDto> addNoteToView(String sessionId, String viewId,
            String content, String position, Integer gap, Integer x, Integer y,
            Integer width, Integer height,
            String parentViewObjectId, StylingParams styling, ImageParams imageParams) {
        throw new UnsupportedOperationException("addNoteToView not implemented in test accessor");
    }

    @Override
    public MutationResult<ViewConnectionDto> addConnectionToView(String sessionId, String viewId,
            String relationshipId, String sourceViewObjectId, String targetViewObjectId,
            List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints,
            StylingParams styling, Boolean showLabel, Integer textPosition) {
        throw new UnsupportedOperationException("addConnectionToView not implemented in test accessor");
    }

    @Override
    public MutationResult<ViewObjectDto> updateViewObject(String sessionId, String viewObjectId,
            Integer x, Integer y, Integer width, Integer height, String text,
            StylingParams styling, ImageParams imageParams) {
        throw new UnsupportedOperationException("updateViewObject not implemented in test accessor");
    }

    @Override
    public MutationResult<ViewConnectionDto> updateViewConnection(String sessionId,
            String viewConnectionId, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling,
            Boolean showLabel, Integer textPosition) {
        throw new UnsupportedOperationException("updateViewConnection not implemented in test accessor");
    }

    @Override
    public MutationResult<RemoveFromViewResultDto> removeFromView(String sessionId,
            String viewId, String viewObjectId) {
        throw new UnsupportedOperationException("removeFromView not implemented in test accessor");
    }

    @Override
    public MutationResult<ClearViewResultDto> clearView(String sessionId, String viewId) {
        throw new UnsupportedOperationException("clearView not implemented in test accessor");
    }

    @Override
    public MutationResult<ApplyViewLayoutResultDto> applyViewLayout(String sessionId,
            String viewId, List<ViewPositionSpec> positions,
            List<ViewConnectionSpec> connections, String description) {
        throw new UnsupportedOperationException(
                "applyViewLayout not implemented in test accessor");
    }

    @Override
    public MutationResult<LayoutViewResultDto> layoutView(String sessionId,
            String viewId, String algorithm, String preset,
            Map<String, Object> options) {
        throw new UnsupportedOperationException(
                "layoutView not implemented in test accessor");
    }

    @Override
    public AssessLayoutResultDto assessLayout(String viewId) {
        throw new UnsupportedOperationException(
                "assessLayout not implemented in test accessor");
    }

    @Override
    public ContentBounds getContentBounds(String viewId) {
        throw new UnsupportedOperationException(
                "getContentBounds not implemented in test accessor");
    }

    @Override
    public MutationResult<LayoutFlatViewResultDto> layoutFlatView(
            String sessionId, String viewId, String arrangement,
            Integer spacing, Integer padding, String sortBy,
            String categoryField, Integer columns,
            boolean autoLayoutChildren) {
        throw new UnsupportedOperationException(
                "layoutFlatView not implemented in test accessor");
    }

    @Override
    public DetectHubElementsResultDto detectHubElements(String viewId) {
        throw new UnsupportedOperationException(
                "detectHubElements not implemented in test accessor");
    }

    @Override
    public MutationResult<AutoRouteResultDto> autoRouteConnections(
            String sessionId, String viewId,
            List<String> connectionIds, String strategy, boolean force,
            boolean autoNudge, int snapThreshold, int perimeterMargin) {
        throw new UnsupportedOperationException(
                "autoRouteConnections not implemented in test accessor");
    }

    @Override
    public MutationResult<AutoLayoutAndRouteResultDto> autoLayoutAndRoute(
            String sessionId, String viewId, String mode,
            String direction, int spacing, String targetRating) {
        throw new UnsupportedOperationException(
                "autoLayoutAndRoute not implemented in test accessor");
    }

    @Override
    public MutationResult<AutoConnectResultDto> autoConnectView(
            String sessionId, String viewId,
            List<String> elementIds, List<String> relationshipTypes,
            Boolean showLabel) {
        throw new UnsupportedOperationException(
                "autoConnectView not implemented in test accessor");
    }

    @Override
    public MutationResult<LayoutWithinGroupResultDto> layoutWithinGroup(
            String sessionId, String viewId, String groupViewObjectId,
            String arrangement, Integer spacing, Integer padding,
            Integer elementWidth, Integer elementHeight, boolean autoResize,
            boolean autoWidth, Integer columns, boolean recursive) {
        throw new UnsupportedOperationException(
                "layoutWithinGroup not implemented in test accessor");
    }

    @Override
    public MutationResult<ArrangeGroupsResultDto> arrangeGroups(
            String sessionId, String viewId, String arrangement,
            Integer columns, Integer spacing, java.util.List<String> groupIds,
            String direction) {
        throw new UnsupportedOperationException(
                "arrangeGroups not implemented in test accessor");
    }

    @Override
    public MutationResult<OptimizeGroupOrderResultDto> optimizeGroupOrder(
            String sessionId, String viewId, String arrangement,
            Integer spacing, Integer padding, Integer elementWidth,
            Integer elementHeight, boolean autoWidth, Integer columns,
            Map<String, String> groupArrangements) {
        throw new UnsupportedOperationException(
                "optimizeGroupOrder not implemented in test accessor");
    }

    @Override
    public MutationResult<FolderDto> createFolder(String sessionId, String parentId, String name,
            String documentation, Map<String, String> properties) {
        throw new UnsupportedOperationException("createFolder not implemented in test accessor");
    }

    @Override
    public MutationResult<FolderDto> updateFolder(String sessionId, String id, String name,
            String documentation, Map<String, String> properties) {
        throw new UnsupportedOperationException("updateFolder not implemented in test accessor");
    }

    @Override
    public MutationResult<MoveResultDto> moveToFolder(String sessionId,
            String objectId, String targetFolderId) {
        throw new UnsupportedOperationException("moveToFolder not implemented in test accessor");
    }

    @Override
    public MutationResult<DeleteResultDto> deleteElement(String sessionId, String elementId) {
        throw new UnsupportedOperationException("deleteElement not implemented in test accessor");
    }

    @Override
    public MutationResult<DeleteResultDto> deleteRelationship(String sessionId,
            String relationshipId) {
        throw new UnsupportedOperationException("deleteRelationship not implemented in test accessor");
    }

    @Override
    public MutationResult<DeleteResultDto> deleteView(String sessionId, String viewId) {
        throw new UnsupportedOperationException("deleteView not implemented in test accessor");
    }

    @Override
    public MutationResult<DeleteResultDto> deleteFolder(String sessionId, String folderId,
            boolean force) {
        throw new UnsupportedOperationException("deleteFolder not implemented in test accessor");
    }

    @Override
    public UndoRedoResultDto undo(int steps) {
        throw new UnsupportedOperationException("undo not implemented in test accessor");
    }

    @Override
    public UndoRedoResultDto redo(int steps) {
        throw new UnsupportedOperationException("redo not implemented in test accessor");
    }

    @Override
    public ExportResult exportView(String viewId, String format, double scale, boolean inline,
            String outputDirectory) {
        throw new UnsupportedOperationException("exportView not implemented in test accessor");
    }

    @Override
    public String getModelVersion() {
        return "42";
    }

    @Override
    public Optional<String> getCurrentModelName() {
        return modelLoaded ? Optional.of("Test Model") : Optional.empty();
    }

    @Override
    public Optional<String> getCurrentModelId() {
        return modelLoaded ? Optional.of("test-id") : Optional.empty();
    }

    @Override
    public MutationDispatcher getMutationDispatcher() {
        return null;
    }

    @Override
    public void addModelChangeListener(ModelChangeListener listener) {
        // no-op
    }

    @Override
    public void removeModelChangeListener(ModelChangeListener listener) {
        // no-op
    }

    @Override
    public AddImageResultDto addImageToModel(String sessionId, byte[] imageData, String filenameHint) {
        throw new UnsupportedOperationException("addImageToModel not implemented in test accessor");
    }

    @Override
    public AddImageResultDto addImageFromFilePath(String sessionId, String filePath) {
        throw new UnsupportedOperationException("addImageFromFilePath not implemented in test accessor");
    }

    @Override
    public AddImageResultDto addImageFromUrl(String sessionId, String url) {
        throw new UnsupportedOperationException("addImageFromUrl not implemented in test accessor");
    }

    @Override
    public List<ModelImageDto> listModelImages(String sessionId) {
        throw new UnsupportedOperationException("listModelImages not implemented in test accessor");
    }

    @Override
    public void dispose() {
        // no-op
    }
}
