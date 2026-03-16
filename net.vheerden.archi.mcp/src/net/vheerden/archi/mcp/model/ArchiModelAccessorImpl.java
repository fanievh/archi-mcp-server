package net.vheerden.archi.mcp.model;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.editor.diagram.util.DiagramUtils;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IApplicationElement;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IBusinessElement;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IFontAttribute;
import com.archimatetool.model.ILineObject;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IImplementationMigrationElement;
import com.archimatetool.model.IMotivationElement;
import com.archimatetool.model.ITextContent;
import com.archimatetool.model.IPhysicalElement;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.IStrategyElement;
import com.archimatetool.model.ITechnologyElement;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.model.routing.FailedConnection;
import net.vheerden.archi.mcp.model.routing.MoveRecommendation;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.StringSimilarity;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAssessmentSummaryDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AnchorPointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;
import net.vheerden.archi.mcp.response.dto.DuplicateCandidate;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.FailedConnectionDto;
import net.vheerden.archi.mcp.response.dto.MoveRecommendationDto;
import net.vheerden.archi.mcp.response.dto.RoutingViolationDto;
import net.vheerden.archi.mcp.response.dto.ExportViewResultDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;
import net.vheerden.archi.mcp.response.dto.LayoutViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.UndoRedoResultDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionSpec;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Implementation of {@link ArchiModelAccessor} backed by ArchimateTool's EMF model.
 *
 * <p><strong>LAYER 3 (Model Boundary):</strong> This is the ONLY class that imports
 * ArchimateTool model types ({@code com.archimatetool.model.*}) and editor types
 * ({@code com.archimatetool.editor.model.*}). No other package may import these types.</p>
 *
 * <p>Listens for model lifecycle events (open, close, content changes) via
 * {@link PropertyChangeListener} on {@link IEditorModelManager} and notifies
 * registered {@link ModelChangeListener}s when the active model changes.</p>
 *
 * <p>Thread safety: The active model reference is {@code volatile}. The version
 * counter uses {@link AtomicLong}. Listener list uses {@link CopyOnWriteArrayList}.
 * All query methods capture the volatile model reference to a local variable
 * before use to prevent NPE from concurrent model changes.</p>
 */
public class ArchiModelAccessorImpl implements ArchiModelAccessor, PropertyChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(ArchiModelAccessorImpl.class);

    private final IEditorModelManager modelManager;
    private final MutationDispatcher mutationDispatcher;
    private final List<ModelChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong versionCounter = new AtomicLong(0);

    // View placement constants (Story 7-7)
    private static final int DEFAULT_VIEW_OBJECT_WIDTH = 120;
    private static final int DEFAULT_VIEW_OBJECT_HEIGHT = 55;
    private static final int DEFAULT_GROUP_WIDTH = 300;
    private static final int DEFAULT_GROUP_HEIGHT = 200;
    private static final int DEFAULT_NOTE_WIDTH = 185;
    private static final int DEFAULT_NOTE_HEIGHT = 80;
    private static final int START_X = 50;
    private static final int START_Y = 50;
    private static final int H_GAP = 30;
    private static final int V_GAP = 30;
    private static final int MAX_ROW_WIDTH = 800;
    private static final int MAX_ATTEMPTS = 100;
    private static final int MAX_AUTO_CONNECTIONS = 50;

    private final LayoutEngine layoutEngine = new LayoutEngine();
    private final ElkLayoutEngine elkLayoutEngine = new ElkLayoutEngine();
    private final LayoutQualityAssessor layoutQualityAssessor = new LayoutQualityAssessor();
    private volatile IArchimateModel activeModel;
    private volatile boolean disposed;

    /**
     * Creates an accessor using the global {@link IEditorModelManager#INSTANCE}.
     */
    public ArchiModelAccessorImpl() {
        this(IEditorModelManager.INSTANCE);
    }

    /**
     * Creates an accessor with the given model manager.
     * Package-visible for testing with a mock.
     *
     * @param modelManager the editor model manager to use
     */
    ArchiModelAccessorImpl(IEditorModelManager modelManager) {
        this.modelManager = modelManager;
        this.mutationDispatcher = new MutationDispatcher(() -> this.activeModel);
        this.mutationDispatcher.setOnImmediateDispatchCallback(() -> this.versionCounter.incrementAndGet());
        this.modelManager.addPropertyChangeListener(this);
        detectActiveModel();
    }

    /**
     * Creates an accessor with the given model manager and mutation dispatcher.
     * Package-visible for testing with overridden dispatch behavior.
     *
     * @param modelManager the editor model manager to use
     * @param dispatcher   the mutation dispatcher to use
     */
    ArchiModelAccessorImpl(IEditorModelManager modelManager, MutationDispatcher dispatcher) {
        this.modelManager = modelManager;
        this.mutationDispatcher = dispatcher;
        this.mutationDispatcher.setOnImmediateDispatchCallback(() -> this.versionCounter.incrementAndGet());
        this.modelManager.addPropertyChangeListener(this);
        detectActiveModel();
    }

    @Override
    public boolean isModelLoaded() {
        return activeModel != null;
    }

    @Override
    public Optional<String> getCurrentModelName() {
        IArchimateModel model = activeModel;
        if (model == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(model.getName());
    }

    @Override
    public Optional<String> getCurrentModelId() {
        IArchimateModel model = activeModel;
        if (model == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(model.getId());
    }

    @Override
    public String getModelVersion() {
        if (activeModel == null) {
            return null;
        }
        return String.valueOf(versionCounter.get());
    }

    // ---- Query methods (Story 2.1) ----

    @Override
    public Optional<ElementDto> getElementById(String id) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            EObject obj = ArchimateModelUtils.getObjectByID(model, id);
            if (obj instanceof IArchimateElement element) {
                return Optional.of(convertToElementDto(element));
            }
            return Optional.empty();
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving element with ID '" + id + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public ModelInfoDto getModelInfo() {
        IArchimateModel model = requireAndCaptureModel();
        try {
            List<IArchimateElement> allElements = new ArrayList<>();
            List<IArchimateRelationship> allRelationships = new ArrayList<>();

            for (IFolder folder : model.getFolders()) {
                collectElements(folder, allElements);
                collectRelationships(folder, allRelationships);
            }

            int viewCount = countViews(model);
            Map<String, Integer> typeDistribution = buildTypeDistribution(allElements);
            Map<String, Integer> relTypeDistribution = buildRelationshipTypeDistribution(allRelationships);
            Map<String, Integer> layerDistribution = buildLayerDistribution(allElements);

            return new ModelInfoDto(
                    model.getName(),
                    allElements.size(),
                    allRelationships.size(),
                    viewCount,
                    typeDistribution,
                    relTypeDistribution,
                    layerDistribution);
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving model info", e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public List<ViewDto> getViews(String viewpointFilter) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            List<ViewDto> views = new ArrayList<>();
            IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);
            if (diagramsFolder != null) {
                collectViews(diagramsFolder, "", viewpointFilter, views);
            }
            return views;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving views", e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public Optional<ViewContentsDto> getViewContents(String viewId) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            EObject obj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(obj instanceof IArchimateDiagramModel diagramModel)) {
                return Optional.empty();
            }

            List<ElementDto> elements = new ArrayList<>();
            List<RelationshipDto> relationships = new ArrayList<>();
            List<ViewNodeDto> visualMetadata = new ArrayList<>();
            List<ViewConnectionDto> connections = new ArrayList<>();
            List<ViewGroupDto> groups = new ArrayList<>();
            List<ViewNoteDto> notes = new ArrayList<>();
            Set<String> seenElementIds = new HashSet<>();
            Set<String> seenRelationshipIds = new HashSet<>();

            collectViewContents(diagramModel, elements, relationships, visualMetadata,
                    connections, groups, notes, seenElementIds, seenRelationshipIds, null);

            String viewpoint = diagramModel.getViewpoint();
            if (viewpoint != null && viewpoint.isEmpty()) {
                viewpoint = null;
            }
            String routerType = mapConnectionRouterType(
                    diagramModel.getConnectionRouterType());

            return Optional.of(new ViewContentsDto(
                    diagramModel.getId(),
                    diagramModel.getName(),
                    viewpoint,
                    routerType,
                    elements,
                    relationships,
                    visualMetadata,
                    connections,
                    groups.isEmpty() ? null : groups,
                    notes.isEmpty() ? null : notes));
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving view contents for view ID '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Batch retrieval methods (Story 3.3) ----

    @Override
    public List<ElementDto> getElementsByIds(List<String> ids) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            Set<String> idSet = new HashSet<>(ids);
            List<ElementDto> results = new ArrayList<>();

            List<IArchimateElement> allElements = new ArrayList<>();
            for (IFolder folder : model.getFolders()) {
                collectElements(folder, allElements);
            }

            for (IArchimateElement element : allElements) {
                if (idSet.contains(element.getId())) {
                    results.add(convertToElementDto(element));
                    idSet.remove(element.getId());
                    if (idSet.isEmpty()) {
                        break; // Early exit when all IDs found
                    }
                }
            }
            return results;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Failed to retrieve elements by IDs: " + e.getMessage(),
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Search methods (Story 3.1) ----

    @Override
    public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            String lowerQuery = query.toLowerCase();

            List<IArchimateElement> allElements = new ArrayList<>();
            for (IFolder folder : model.getFolders()) {
                collectElements(folder, allElements);
            }

            List<ElementDto> results = new ArrayList<>();
            for (IArchimateElement element : allElements) {
                // Apply type filter before text matching (cheaper check first)
                if (typeFilter != null && !element.eClass().getName().equals(typeFilter)) {
                    continue;
                }
                // Apply layer filter before text matching
                if (layerFilter != null && !resolveLayer(element).equals(layerFilter)) {
                    continue;
                }
                if (matchesQuery(element, lowerQuery)) {
                    results.add(convertToElementDto(element));
                }
            }
            return results;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error searching elements with query '" + query + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    private boolean matchesQuery(IArchimateElement element, String lowerQuery) {
        if (containsIgnoreCase(element.getName(), lowerQuery)) {
            return true;
        }
        if (containsIgnoreCase(element.getDocumentation(), lowerQuery)) {
            return true;
        }
        for (IProperty property : element.getProperties()) {
            if (containsIgnoreCase(property.getValue(), lowerQuery)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(String text, String lowerQuery) {
        return text != null && text.toLowerCase().contains(lowerQuery);
    }

    // ---- Relationship methods (Story 4.1) ----

    @Override
    public List<RelationshipDto> getRelationshipsForElement(String elementId) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            EObject obj = ArchimateModelUtils.getObjectByID(model, elementId);
            if (!(obj instanceof IArchimateElement element)) {
                return List.of();
            }
            List<RelationshipDto> results = new ArrayList<>();
            for (IArchimateRelationship rel : element.getSourceRelationships()) {
                results.add(convertToRelationshipDto(rel));
            }
            for (IArchimateRelationship rel : element.getTargetRelationships()) {
                results.add(convertToRelationshipDto(rel));
            }
            return results;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving relationships for element '" + elementId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Folder navigation methods (Story 7-0b) ----

    @Override
    public List<FolderDto> getRootFolders() {
        IArchimateModel model = requireAndCaptureModel();
        try {
            List<FolderDto> result = new ArrayList<>();
            for (IFolder folder : model.getFolders()) {
                result.add(FolderOperations.convertToFolderDto(folder));
            }
            return result;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving root folders", e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public Optional<FolderDto> getFolderById(String id) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            IFolder found = FolderOperations.findFolderById(model, id);
            if (found == null) {
                return Optional.empty();
            }
            return Optional.of(FolderOperations.convertToFolderDto(found));
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving folder with ID '" + id + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public List<FolderDto> getFolderChildren(String parentId) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            IFolder parent = FolderOperations.findFolderById(model, parentId);
            if (parent == null) {
                return List.of();
            }
            List<FolderDto> result = new ArrayList<>();
            for (IFolder child : parent.getFolders()) {
                result.add(FolderOperations.convertToFolderDto(child));
            }
            return result;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving children of folder '" + parentId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public List<FolderTreeDto> getFolderTree(String rootId, int maxDepth) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            if (rootId != null) {
                IFolder root = FolderOperations.findFolderById(model, rootId);
                if (root == null) {
                    return List.of();
                }
                return List.of(FolderOperations.buildFolderTree(root, maxDepth, 0));
            }
            // Full tree: all root folders
            List<FolderTreeDto> result = new ArrayList<>();
            for (IFolder folder : model.getFolders()) {
                result.add(FolderOperations.buildFolderTree(folder, maxDepth, 0));
            }
            return result;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error building folder tree", e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public List<FolderDto> searchFolders(String nameQuery) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            String lowerQuery = nameQuery.toLowerCase();
            List<FolderDto> result = new ArrayList<>();
            for (IFolder folder : model.getFolders()) {
                FolderOperations.collectMatchingFolders(folder, lowerQuery, result);
            }
            return result;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error searching folders with query '" + nameQuery + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Discovery-first patterns (Story 7-4) ----

    // Design note: Full model traversal via collectElements() is O(N) per call.
    // This is acceptable at typical ArchiMate model scale (hundreds to low thousands
    // of elements). If models grow significantly larger, consider an indexed cache.
    @Override
    public List<DuplicateCandidate> findDuplicates(String type, String name) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            List<IArchimateElement> allElements = new ArrayList<>();
            for (IFolder folder : model.getFolders()) {
                collectElements(folder, allElements);
            }

            List<DuplicateCandidate> candidates = new ArrayList<>();
            for (IArchimateElement element : allElements) {
                if (!element.eClass().getName().equals(type)) {
                    continue;
                }
                double score = StringSimilarity.compositeSimilarity(element.getName(), name);
                if (score >= StringSimilarity.DUPLICATE_THRESHOLD) {
                    candidates.add(new DuplicateCandidate(
                            element.getId(), element.getName(),
                            element.eClass().getName(), score));
                }
            }

            candidates.sort((a, b) -> Double.compare(b.similarityScore(), a.similarityScore()));
            if (candidates.size() > 10) {
                candidates = new ArrayList<>(candidates.subList(0, 10));
            }
            return candidates;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error finding duplicates for type '" + type + "' name '" + name + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // Design note: Returns the first matching element. ArchiMate allows non-unique
    // names within a type, so multiple matches are possible. Returning the first is
    // intentional for get-or-create idempotency — the caller gets a valid existing
    // element without needing to disambiguate.
    @Override
    public Optional<ElementDto> findExactMatch(String type, String name) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            List<IArchimateElement> allElements = new ArrayList<>();
            for (IFolder folder : model.getFolders()) {
                collectElements(folder, allElements);
            }

            for (IArchimateElement element : allElements) {
                if (!element.eClass().getName().equals(type)) {
                    continue;
                }
                if (element.getName() != null && element.getName().equalsIgnoreCase(name)) {
                    return Optional.of(convertToElementDto(element));
                }
            }
            return Optional.empty();
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error finding exact match for type '" + type + "' name '" + name + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Mutation creation methods (Story 7-2) ----

    @Override
    public MutationResult<ElementDto> createElement(String sessionId, String type, String name,
            String documentation, Map<String, String> properties, String folderId) {
        return createElement(sessionId, type, name, documentation, properties, folderId, null);
    }

    @Override
    public MutationResult<ElementDto> createElement(String sessionId, String type, String name,
            String documentation, Map<String, String> properties, String folderId,
            Map<String, String> source) {
        logger.info("Creating element: type={}, name={}", type, name);
        requireAndCaptureModel();
        try {
            // Merge source traceability properties (Story 7-6)
            Map<String, String> mergedProperties = mergeSourceProperties(properties, source);

            PreparedMutation<ElementDto> prepared = prepareCreateElement(type, name,
                    documentation, mergedProperties, folderId);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Create " + type + ": " + name;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("type", type);
                proposedChanges.put("name", name);
                if (documentation != null) proposedChanges.put("documentation", documentation);
                if (folderId != null) proposedChanges.put("folderId", folderId);
                if (source != null) proposedChanges.put("source", source);
                ProposalContext ctx = storeAsProposal(sessionId, "create-element",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "Type valid. Element prepared for creation.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Create " + type + ": " + name);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error creating element of type '" + type + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<RelationshipDto> createRelationship(String sessionId, String type,
            String sourceId, String targetId, String name) {
        logger.info("Creating relationship: type={}, source={}, target={}", type, sourceId, targetId);
        requireAndCaptureModel();
        try {
            PreparedMutation<RelationshipDto> prepared = prepareCreateRelationship(
                    type, sourceId, targetId, name);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Create " + type + ": " + sourceId + " → " + targetId;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("type", type);
                proposedChanges.put("sourceId", sourceId);
                proposedChanges.put("targetId", targetId);
                if (name != null) proposedChanges.put("name", name);
                ProposalContext ctx = storeAsProposal(sessionId, "create-relationship",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges,
                        "Relationship type valid. Source and target exist. ArchiMate spec compliant.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Create " + type + ": " + sourceId + " → " + targetId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error creating relationship of type '" + type + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<ViewDto> createView(String sessionId, String name,
            String viewpoint, String folderId, String connectionRouterType) {
        logger.info("Creating view: name={}, viewpoint={}", name, viewpoint);
        requireAndCaptureModel();
        try {
            PreparedMutation<ViewDto> prepared = prepareCreateView(name, viewpoint, folderId,
                    connectionRouterType);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Create view: " + name;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("name", name);
                if (viewpoint != null) proposedChanges.put("viewpoint", viewpoint);
                if (folderId != null) proposedChanges.put("folderId", folderId);
                if (connectionRouterType != null) {
                    proposedChanges.put("connectionRouterType", connectionRouterType);
                }
                ProposalContext ctx = storeAsProposal(sessionId, "create-view",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "View prepared for creation.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Create view: " + name);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error creating view '" + name + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Mutation update methods (Story 7-3) ----

    @Override
    public MutationResult<ElementDto> updateElement(String sessionId, String id, String name,
            String documentation, Map<String, String> properties) {
        logger.info("Updating element: id={}", id);
        requireAndCaptureModel();
        try {
            PreparedMutation<ElementDto> prepared = prepareUpdateElement(id, name,
                    documentation, properties);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Update element: " + id;
                // Current state from the pre-update entity DTO
                ElementDto current = prepared.entity();
                Map<String, Object> currentState = new LinkedHashMap<>();
                currentState.put("name", current.name());
                if (current.documentation() != null) {
                    currentState.put("documentation", current.documentation());
                }
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                if (name != null) proposedChanges.put("name", name);
                if (documentation != null) proposedChanges.put("documentation", documentation);
                if (properties != null) proposedChanges.put("properties", properties);
                ProposalContext ctx = storeAsProposal(sessionId, "update-element",
                        prepared.command(), prepared.entity(), description,
                        currentState, proposedChanges, "Element exists. All changes valid.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Update element: " + id);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            // Re-read element state after command execution (the DTO from prepare
            // captures pre-update values since UpdateElementCommand applies in execute())
            ElementDto entity;
            if (batchSeq == null && prepared.rawObject() instanceof IArchimateElement elem) {
                entity = convertToElementDto(elem);
            } else {
                entity = prepared.entity();
            }

            return new MutationResult<>(entity, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error updating element with ID '" + id + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<ViewDto> updateView(String sessionId, String id, String name,
            String viewpoint, String documentation, Map<String, String> properties,
            String connectionRouterType) {
        logger.info("Updating view: id={}", id);
        requireAndCaptureModel();
        try {
            PreparedMutation<ViewDto> prepared = prepareUpdateView(id, name,
                    viewpoint, documentation, properties, connectionRouterType);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Update view: " + id;
                ViewDto current = prepared.entity();
                Map<String, Object> currentState = new LinkedHashMap<>();
                currentState.put("name", current.name());
                if (current.viewpointType() != null) {
                    currentState.put("viewpoint", current.viewpointType());
                }
                if (current.documentation() != null) {
                    currentState.put("documentation", current.documentation());
                }
                if (current.properties() != null) {
                    currentState.put("properties", current.properties());
                }
                currentState.put("connectionRouterType",
                        current.connectionRouterType() != null
                                ? current.connectionRouterType() : "manual");
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                if (name != null) proposedChanges.put("name", name);
                // Show "(clear)" for empty-string viewpoint so reviewer understands intent
                if ("".equals(viewpoint)) {
                    proposedChanges.put("viewpoint", "(clear)");
                } else if (viewpoint != null) {
                    proposedChanges.put("viewpoint", viewpoint);
                }
                if (documentation != null) proposedChanges.put("documentation", documentation);
                if (properties != null) proposedChanges.put("properties", properties);
                if ("".equals(connectionRouterType)) {
                    proposedChanges.put("connectionRouterType", "(clear to manual)");
                } else if (connectionRouterType != null) {
                    proposedChanges.put("connectionRouterType", connectionRouterType);
                }
                ProposalContext ctx = storeAsProposal(sessionId, "update-view",
                        prepared.command(), prepared.entity(), description,
                        currentState, proposedChanges, "View exists. All changes valid.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Update view: " + id);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            // Re-read view state after command execution
            ViewDto entity;
            if (batchSeq == null && prepared.rawObject() instanceof IArchimateDiagramModel view) {
                entity = buildViewDto(view);
            } else {
                entity = prepared.entity();
            }

            return new MutationResult<>(entity, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error updating view with ID '" + id + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- View export (Story 8-1) ----

    @Override
    public ExportResult exportView(String viewId, String format, double scale, boolean inline) {
        logger.info("Exporting view: viewId={}, format={}, scale={}, inline={}",
                viewId, format, scale, inline);
        IArchimateModel model = requireAndCaptureModel();
        try {
            EObject obj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(obj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId,
                        ErrorCode.ELEMENT_NOT_FOUND,
                        null,
                        "Use get-views to list available view IDs",
                        null);
            }

            if ("png".equals(format)) {
                return ViewExportService.renderPng(diagramModel, scale, inline);
            } else if ("svg".equals(format)) {
                return ViewExportService.renderSvg(diagramModel, scale, inline);
            } else {
                throw new ModelAccessException(
                        "Unsupported export format: " + format
                                + ". Supported formats: png, svg",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Use format 'png' or 'svg'",
                        null);
            }
        } catch (NoModelLoadedException | ModelAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error exporting view '" + viewId + "': " + e.getMessage(),
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- View placement (Story 7-7) ----

    @Override
    public MutationResult<AddToViewResultDto> addToView(String sessionId, String viewId,
            String elementId, Integer x, Integer y, Integer width, Integer height,
            boolean autoConnect, String parentViewObjectId, StylingParams styling) {
        logger.info("Adding element to view: viewId={}, elementId={}, autoConnect={}, parentViewObjectId={}",
                viewId, elementId, autoConnect, parentViewObjectId);
        requireAndCaptureModel();
        try {
            PreparedMutation<AddToViewResultDto> prepared = prepareAddToView(
                    viewId, elementId, x, y, width, height, autoConnect, parentViewObjectId,
                    null, styling);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String elementName = prepared.entity().viewObject().elementName();
                String elementType = prepared.entity().viewObject().elementType();
                String description = "Add " + elementType + " '" + elementName + "' to view";
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("elementId", elementId);
                if (x != null) proposedChanges.put("x", x);
                if (y != null) proposedChanges.put("y", y);
                if (width != null) proposedChanges.put("width", width);
                if (height != null) proposedChanges.put("height", height);
                proposedChanges.put("autoConnect", autoConnect);
                int autoCount = prepared.entity().autoConnections() != null
                        ? prepared.entity().autoConnections().size() : 0;
                String validationSummary = "Element ready for placement on view."
                        + (autoCount > 0 ? " " + autoCount + " auto-connection(s) will be created." : "");
                ProposalContext ctx = storeAsProposal(sessionId, "add-to-view",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, validationSummary);
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Add element to view: " + elementId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error adding element '" + elementId + "' to view '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<ViewGroupDto> addGroupToView(String sessionId, String viewId,
            String label, Integer x, Integer y, Integer width, Integer height,
            String parentViewObjectId, StylingParams styling) {
        logger.info("Adding group to view: viewId={}, label={}", viewId, label);
        requireAndCaptureModel();
        try {
            PreparedMutation<ViewGroupDto> prepared = prepareAddGroupToView(
                    viewId, label, x, y, width, height, parentViewObjectId,
                    null, styling);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Add group '" + label + "' to view";
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("label", label);
                if (x != null) proposedChanges.put("x", x);
                if (y != null) proposedChanges.put("y", y);
                if (width != null) proposedChanges.put("width", width);
                if (height != null) proposedChanges.put("height", height);
                if (parentViewObjectId != null) proposedChanges.put("parentViewObjectId", parentViewObjectId);
                ProposalContext ctx = storeAsProposal(sessionId, "add-group-to-view",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "Group ready for placement on view.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Add group to view: " + label);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error adding group '" + label + "' to view '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<ViewNoteDto> addNoteToView(String sessionId, String viewId,
            String content, Integer x, Integer y, Integer width, Integer height,
            String parentViewObjectId, StylingParams styling) {
        logger.info("Adding note to view: viewId={}", viewId);
        requireAndCaptureModel();
        try {
            PreparedMutation<ViewNoteDto> prepared = prepareAddNoteToView(
                    viewId, content, x, y, width, height, parentViewObjectId,
                    null, styling);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String truncatedContent = content.length() > 40
                        ? content.substring(0, 40) + "..." : content;
                String description = "Add note to view: " + truncatedContent;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("content", content);
                if (x != null) proposedChanges.put("x", x);
                if (y != null) proposedChanges.put("y", y);
                if (width != null) proposedChanges.put("width", width);
                if (height != null) proposedChanges.put("height", height);
                if (parentViewObjectId != null) proposedChanges.put("parentViewObjectId", parentViewObjectId);
                ProposalContext ctx = storeAsProposal(sessionId, "add-note-to-view",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "Note ready for placement on view.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Add note to view");

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error adding note to view '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<ViewConnectionDto> addConnectionToView(String sessionId, String viewId,
            String relationshipId, String sourceViewObjectId, String targetViewObjectId,
            List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints) {
        logger.info("Adding connection to view: viewId={}, relationshipId={}", viewId, relationshipId);
        requireAndCaptureModel();
        try {
            PreparedMutation<ViewConnectionDto> prepared = prepareAddConnectionToView(
                    viewId, relationshipId, sourceViewObjectId, targetViewObjectId,
                    bendpoints, absoluteBendpoints);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Add connection for relationship " + relationshipId + " to view";
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("relationshipId", relationshipId);
                proposedChanges.put("sourceViewObjectId", sourceViewObjectId);
                proposedChanges.put("targetViewObjectId", targetViewObjectId);
                if (bendpoints != null) proposedChanges.put("bendpointCount", bendpoints.size());
                if (absoluteBendpoints != null) proposedChanges.put("absoluteBendpointCount", absoluteBendpoints.size());
                ProposalContext ctx = storeAsProposal(sessionId, "add-connection-to-view",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "Connection ready for placement on view.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Add connection to view: " + relationshipId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error adding connection for relationship '" + relationshipId
                            + "' to view '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- View editing and removal (Story 7-8) ----

    @Override
    public MutationResult<ViewObjectDto> updateViewObject(String sessionId, String viewObjectId,
            Integer x, Integer y, Integer width, Integer height, String text,
            StylingParams styling) {
        logger.info("Updating view object: viewObjectId={}, text={}", viewObjectId, text != null ? "provided" : "null");
        requireAndCaptureModel();
        try {
            PreparedMutation<ViewObjectDto> prepared = prepareUpdateViewObject(
                    viewObjectId, x, y, width, height, text, styling);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String elementName = prepared.entity().elementName();
                String elementType = prepared.entity().elementType();
                String description = "Update view object bounds for " + elementType
                        + " '" + elementName + "'";
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                if (x != null) proposedChanges.put("x", x);
                if (y != null) proposedChanges.put("y", y);
                if (width != null) proposedChanges.put("width", width);
                if (height != null) proposedChanges.put("height", height);
                ProposalContext ctx = storeAsProposal(sessionId, "update-view-object",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "View object bounds ready for update.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Update view object bounds: " + viewObjectId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error updating view object '" + viewObjectId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<ViewConnectionDto> updateViewConnection(String sessionId,
            String viewConnectionId, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling) {
        logger.info("Updating view connection: viewConnectionId={}", viewConnectionId);
        requireAndCaptureModel();
        try {
            PreparedMutation<ViewConnectionDto> prepared = prepareUpdateViewConnection(
                    viewConnectionId, bendpoints, absoluteBendpoints, styling);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String relType = prepared.entity().relationshipType();
                String description = "Update bendpoints for connection (" + relType + ")";
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                int bpCount = (bendpoints != null) ? bendpoints.size()
                        : (absoluteBendpoints != null) ? absoluteBendpoints.size() : 0;
                proposedChanges.put("bendpointCount", bpCount);
                ProposalContext ctx = storeAsProposal(sessionId, "update-view-connection",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "Connection bendpoints ready for update.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Update connection bendpoints: " + viewConnectionId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error updating view connection '" + viewConnectionId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<RemoveFromViewResultDto> removeFromView(String sessionId,
            String viewId, String viewObjectId) {
        logger.info("Removing from view: viewId={}, viewObjectId={}", viewId, viewObjectId);
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<RemoveFromViewResultDto> prepared = prepareRemoveFromView(
                    viewId, viewObjectId);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description;
                RemoveFromViewResultDto dto = prepared.entity();
                if ("viewObject".equals(dto.removedObjectType())) {
                    int cascadeCount = dto.cascadeRemovedConnectionIds() != null
                            ? dto.cascadeRemovedConnectionIds().size() : 0;
                    // Resolve element type/name for traceability
                    String elementInfo = resolveElementInfo(model, viewObjectId);
                    description = "Remove " + elementInfo + " from view"
                            + (cascadeCount > 0 ? " (and " + cascadeCount + " attached connection"
                            + (cascadeCount > 1 ? "s" : "") + ")" : "");
                } else {
                    // Resolve relationship type for traceability
                    String connectionInfo = resolveConnectionInfo(model, viewObjectId);
                    description = "Remove connection (" + connectionInfo + ") from view";
                }
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("viewObjectId", viewObjectId);
                proposedChanges.put("removedObjectType", dto.removedObjectType());
                ProposalContext ctx = storeAsProposal(sessionId, "remove-from-view",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "View object ready for removal.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Remove from view: " + viewObjectId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error removing '" + viewObjectId + "' from view '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<ClearViewResultDto> clearView(String sessionId, String viewId) {
        logger.info("Clearing view: viewId={}", viewId);
        try {
            PreparedMutation<ClearViewResultDto> prepared = prepareClearView(viewId);

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                ClearViewResultDto dto = prepared.entity();
                String description = "Clear view '" + dto.viewName() + "' ("
                        + dto.elementsRemoved() + " object"
                        + (dto.elementsRemoved() != 1 ? "s" : "") + ", "
                        + dto.connectionsRemoved() + " connection"
                        + (dto.connectionsRemoved() != 1 ? "s" : "") + ")";
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("elementsRemoved", dto.elementsRemoved());
                proposedChanges.put("connectionsRemoved", dto.connectionsRemoved());
                proposedChanges.put("nonArchimateObjectsRemoved", dto.nonArchimateObjectsRemoved());
                ProposalContext ctx = storeAsProposal(sessionId, "clear-view",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "View ready for clearing.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Clear view: " + viewId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error clearing view '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Compound layout method (Story 9-0a) ----

    /**
     * Maximum number of total entries (positions + connections) for a single
     * apply-positions call. Prevents resource exhaustion from oversized payloads
     * while accommodating any realistic view layout.
     */
    static final int MAX_LAYOUT_OPERATIONS = 10000;

    @Override
    public MutationResult<ApplyViewLayoutResultDto> applyViewLayout(
            String sessionId, String viewId,
            List<ViewPositionSpec> positions,
            List<ViewConnectionSpec> connections,
            String description) {
        logger.info("Applying view layout: viewId={}, positions={}, connections={}",
                viewId,
                positions != null ? positions.size() : 0,
                connections != null ? connections.size() : 0);

        try {
            IArchimateModel model = requireAndCaptureModel();

            // Validate view exists
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId,
                        ErrorCode.VIEW_NOT_FOUND,
                        null,
                        "Use get-views to find valid view IDs",
                        null);
            }

            // Validate at least one array provided and non-empty
            boolean hasPositions = positions != null && !positions.isEmpty();
            boolean hasConnections = connections != null && !connections.isEmpty();
            if (!hasPositions && !hasConnections) {
                throw new ModelAccessException(
                        "At least one of 'positions' or 'connections' must be provided and non-empty",
                        ErrorCode.INVALID_PARAMETER);
            }

            // Validate total entry count does not exceed safety limit
            int totalEntries = (hasPositions ? positions.size() : 0)
                    + (hasConnections ? connections.size() : 0);
            if (totalEntries > MAX_LAYOUT_OPERATIONS) {
                throw new ModelAccessException(
                        "Layout operation count (" + totalEntries
                                + ") exceeds maximum (" + MAX_LAYOUT_OPERATIONS + ")",
                        ErrorCode.INVALID_PARAMETER);
            }

            // Phase 1: Validate all entries and build commands (Jetty thread)
            List<Command> commands = new ArrayList<>();
            int positionCount = 0;
            int connectionCount = 0;

            if (hasPositions) {
                for (int i = 0; i < positions.size(); i++) {
                    ViewPositionSpec pos = positions.get(i);
                    try {
                        PreparedMutation<ViewObjectDto> prepared =
                                prepareUpdateViewObject(pos.viewObjectId(),
                                        pos.x(), pos.y(), pos.width(), pos.height(),
                                        null, null); // no text/styling for layout
                        commands.add(prepared.command());
                        positionCount++;
                    } catch (ModelAccessException e) {
                        throw new ModelAccessException(
                                "Position entry [" + i + "] (viewObjectId='"
                                        + pos.viewObjectId() + "'): " + e.getMessage(),
                                e, e.getErrorCode());
                    }
                }
            }

            if (hasConnections) {
                for (int i = 0; i < connections.size(); i++) {
                    ViewConnectionSpec conn = connections.get(i);
                    try {
                        // Normalize: if neither bendpoints format provided,
                        // default to empty list (clear = straight line)
                        List<BendpointDto> bps = conn.bendpoints();
                        List<AbsoluteBendpointDto> absBps = conn.absoluteBendpoints();
                        if (bps == null && absBps == null) {
                            bps = List.of();
                        }
                        PreparedMutation<ViewConnectionDto> prepared =
                                prepareUpdateViewConnection(
                                        conn.viewConnectionId(),
                                        bps, absBps, null);
                        commands.add(prepared.command());
                        connectionCount++;
                    } catch (ModelAccessException e) {
                        throw new ModelAccessException(
                                "Connection entry [" + i + "] (viewConnectionId='"
                                        + conn.viewConnectionId() + "'): " + e.getMessage(),
                                e, e.getErrorCode());
                    }
                }
            }

            // Phase 2: Build compound command (single undo unit)
            String label = (description != null && !description.isBlank())
                    ? description
                    : "Apply view layout (" + positionCount + " positions, "
                            + connectionCount + " connections)";

            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            ApplyViewLayoutResultDto dto = new ApplyViewLayoutResultDto(
                    viewId, positionCount, connectionCount,
                    commands.size());

            // Approval gate (Story 7-6)
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("positionsUpdated", positionCount);
                proposedChanges.put("connectionsUpdated", connectionCount);
                proposedChanges.put("totalOperations", commands.size());
                ProposalContext ctx = storeAsProposal(sessionId,
                        "apply-positions",
                        compound, dto, label,
                        null, proposedChanges,
                        "View layout ready for application.");
                return new MutationResult<>(dto, null, ctx);
            }

            // Dispatch or queue
            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(dto, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error applying view layout to '"
                            + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Layout algorithm methods (Story 9-1) ----

    @Override
    public MutationResult<LayoutViewResultDto> layoutView(
            String sessionId, String viewId,
            String algorithm, String preset,
            Map<String, Object> options) {
        logger.info("Layout view: viewId={}, algorithm={}, preset={}",
                viewId, algorithm, preset);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1. Validate view exists
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 2. Resolve algorithm from preset or direct algorithm name
            String resolvedAlgorithm;
            Map<String, Object> resolvedOptions;
            if (algorithm != null && !algorithm.isBlank()
                    && preset != null && !preset.isBlank()) {
                throw new ModelAccessException(
                        "Parameters 'algorithm' and 'preset' are mutually exclusive. "
                        + "Provide one or the other.",
                        ErrorCode.INVALID_PARAMETER);
            } else if (preset != null && !preset.isBlank()) {
                LayoutPreset p = LayoutPreset.resolve(preset);
                resolvedAlgorithm = p.algorithmName();
                resolvedOptions = mergeLayoutOptions(p.defaultOptions(), options);
            } else if (algorithm != null && !algorithm.isBlank()) {
                resolvedAlgorithm = algorithm;
                resolvedOptions = options != null ? options : Map.of();
            } else {
                throw new ModelAccessException(
                        "Either 'algorithm' or 'preset' must be provided. "
                        + "Valid algorithms: " + layoutEngine.listAlgorithms()
                        + ". Valid presets: " + LayoutPreset.listPresets(),
                        ErrorCode.INVALID_PARAMETER);
            }

            // 3. Collect view objects and connections
            List<LayoutNode> nodes = collectLayoutNodes(diagramModel);
            List<LayoutEdge> edges = collectLayoutEdges(diagramModel, nodes);

            if (nodes.isEmpty()) {
                throw new ModelAccessException(
                        "View has no elements to layout",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 4. Compute layout
            List<ViewPositionSpec> positions =
                    layoutEngine.computeLayout(nodes, edges,
                            resolvedAlgorithm, resolvedOptions);

            // 5. Build commands for positions
            List<Command> commands = new ArrayList<>();
            int positionCount = 0;
            for (int i = 0; i < positions.size(); i++) {
                ViewPositionSpec pos = positions.get(i);
                PreparedMutation<ViewObjectDto> prepared =
                        prepareUpdateViewObject(pos.viewObjectId(),
                                pos.x(), pos.y(), pos.width(), pos.height(),
                                null, null);
                commands.add(prepared.command());
                positionCount++;
            }

            // 6. Clear all connection bendpoints (straight lines)
            int connectionCount = 0;
            for (IDiagramModelConnection conn :
                    AssessmentCollector.collectAllConnections(diagramModel)) {
                String connId = conn.getId();
                PreparedMutation<ViewConnectionDto> prepared =
                        prepareUpdateViewConnection(connId, List.of(), null, null);
                commands.add(prepared.command());
                connectionCount++;
            }

            // 7. Validate total operation count
            if (commands.size() > MAX_LAYOUT_OPERATIONS) {
                throw new ModelAccessException(
                        "Layout operation count (" + commands.size()
                                + ") exceeds maximum (" + MAX_LAYOUT_OPERATIONS + ")",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 8. Build compound command
            String label = "Layout view ("
                    + (preset != null && !preset.isBlank()
                            ? "preset=" + preset
                            : "algorithm=" + resolvedAlgorithm)
                    + ", " + positionCount + " positions, "
                    + connectionCount + " connections cleared)";

            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            LayoutViewResultDto dto = new LayoutViewResultDto(
                    viewId,
                    resolvedAlgorithm,
                    preset,
                    positionCount,
                    connectionCount,
                    commands.size());

            // 9. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("algorithmUsed", resolvedAlgorithm);
                proposedChanges.put("presetUsed", preset);
                proposedChanges.put("elementsRepositioned", positionCount);
                proposedChanges.put("connectionsCleared", connectionCount);
                ProposalContext ctx = storeAsProposal(sessionId,
                        "compute-layout", compound, dto, label,
                        null, proposedChanges,
                        "View layout computed and ready for application.");
                return new MutationResult<>(dto, null, ctx);
            }

            // 10. Dispatch or queue
            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(dto, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException
                | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error computing/applying layout for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    private List<LayoutNode> collectLayoutNodes(IArchimateDiagramModel diagramModel) {
        List<LayoutNode> nodes = new ArrayList<>();
        for (IDiagramModelObject child : diagramModel.getChildren()) {
            if (child instanceof IDiagramModelNote) {
                continue; // Notes are not laid out (Story 11-15)
            }
            IBounds bounds = child.getBounds();
            double w = bounds.getWidth();
            double h = bounds.getHeight();
            if (w <= 0 || h <= 0) {
                logger.warn("Skipping element '{}' (id={}) with zero/negative bounds: w={}, h={}",
                        child.getName(), child.getId(), w, h);
                continue;
            }
            nodes.add(new LayoutNode(child.getId(),
                    bounds.getX(), bounds.getY(), w, h, null));
        }
        return nodes;
    }

    private List<LayoutEdge> collectLayoutEdges(
            IArchimateDiagramModel diagramModel, List<LayoutNode> nodes) {
        Set<String> topLevelIds = new HashSet<>();
        for (LayoutNode node : nodes) {
            topLevelIds.add(node.viewObjectId());
        }

        List<LayoutEdge> edges = new ArrayList<>();
        for (IDiagramModelObject child : diagramModel.getChildren()) {
            if (!topLevelIds.contains(child.getId())) {
                continue;
            }
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                IConnectable target = conn.getTarget();
                if (target instanceof IDiagramModelObject targetObj
                        && topLevelIds.contains(targetObj.getId())) {
                    edges.add(new LayoutEdge(child.getId(), targetObj.getId(), null));
                }
            }
        }
        return edges;
    }


    private Map<String, Object> mergeLayoutOptions(
            Map<String, Object> defaults, Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return defaults;
        }
        Map<String, Object> merged = new LinkedHashMap<>(defaults);
        merged.putAll(overrides);
        return merged;
    }

    // ---- Layout quality assessment (Story 9-2) ----

    @Override
    public AssessLayoutResultDto assessLayout(String viewId) {
        logger.info("Assess layout: viewId={}", viewId);
        IArchimateModel model = requireAndCaptureModel();

        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
            throw new ModelAccessException(
                    "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
        }

        // 1. Collect all view objects (including nested, with parentId)
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);

        // 2. Detect orphaned connections (Story 10-14) — connections whose
        // source/target view objects are missing from the view hierarchy.
        // This runs before filtering so orphans are counted even though they
        // can't be included in geometry-based assessment.
        OrphanDetectionResult orphanResult = detectOrphanedConnections(diagramModel, nodes);

        if (nodes.isEmpty()) {
            return new AssessLayoutResultDto(
                    viewId, 0, 0, 0, 0, 0, 0.0, 0.0, 0,
                    "not-applicable", Map.of("overall", "not-applicable"),
                    null, null, null, null, 0, null,
                    orphanResult.count(), emptyToNull(orphanResult.descriptions()),
                    0, null, false, 0, null,
                    List.of("View has no elements — layout assessment is not applicable."));
        }
        if (nodes.size() == 1) {
            return new AssessLayoutResultDto(
                    viewId, 1, 0, 0, 0, 0, 0.0, 0.0, 0,
                    "not-applicable", Map.of("overall", "not-applicable"),
                    null, null, null, null, 0, null,
                    orphanResult.count(), emptyToNull(orphanResult.descriptions()),
                    0, null, false, 0, null,
                    List.of("View has only one element — layout assessment is not applicable."));
        }

        // 3. Collect connections with reconstructed visual paths
        List<AssessmentConnection> connections =
                AssessmentCollector.collectAssessmentConnections(diagramModel, nodes);

        // 4. Run assessment
        LayoutAssessmentResult result =
                layoutQualityAssessor.assess(nodes, connections);

        // 5. Build DTO
        return new AssessLayoutResultDto(
                viewId,
                nodes.size(),
                connections.size(),
                result.overlapCount(),
                result.containmentOverlapCount(),
                result.edgeCrossingCount(),
                Math.round(result.crossingsPerConnection() * 100.0) / 100.0,
                Math.round(result.averageSpacing() * 10.0) / 10.0,
                result.alignmentScore(),
                result.overallRating(),
                result.ratingBreakdown(),
                emptyToNull(result.overlaps()),
                emptyToNull(result.boundaryViolations()),
                emptyToNull(result.connectionPassThroughs()),
                emptyToNull(result.offCanvasWarnings()),
                result.labelOverlapCount(),
                emptyToNull(result.labelOverlaps()),
                orphanResult.count(), emptyToNull(orphanResult.descriptions()),
                result.noteOverlapCount(),
                emptyToNull(result.noteOverlapDescriptions()),
                result.hasGroups(),
                result.coincidentSegmentCount(),
                mapContentBounds(result.contentBounds()),
                result.suggestions());
    }

    /** Maps internal ContentBounds to DTO (Story 11-29). Returns null if input is null. */
    private AssessLayoutResultDto.ContentBoundsDto mapContentBounds(ContentBounds bounds) {
        if (bounds == null) return null;
        return new AssessLayoutResultDto.ContentBoundsDto(
                bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    /** Result of orphaned connection detection (Story 10-14). */
    record OrphanDetectionResult(int count, List<String> descriptions) {}

    /**
     * Detects connections on a view whose source or target view objects are not
     * present in the view's node hierarchy. Such orphans arise when connections
     * are not properly disconnected before their endpoint objects are removed
     * (e.g., by clear-view on views with nested groups).
     */
    private OrphanDetectionResult detectOrphanedConnections(
            IArchimateDiagramModel diagramModel,
            List<AssessmentNode> nodes) {
        Set<String> nodeIds = new HashSet<>();
        for (AssessmentNode node : nodes) {
            nodeIds.add(node.id());
        }

        int count = 0;
        List<String> descriptions = new ArrayList<>();

        // collectAllConnections already deduplicates by ID internally
        for (IDiagramModelConnection conn : AssessmentCollector.collectAllConnections(diagramModel)) {
            IConnectable source = conn.getSource();
            IConnectable target = conn.getTarget();
            boolean sourceOrphan = source == null
                    || !(source instanceof IDiagramModelObject)
                    || !nodeIds.contains(source.getId());
            boolean targetOrphan = target == null
                    || !(target instanceof IDiagramModelObject)
                    || !nodeIds.contains(target.getId());

            if (sourceOrphan || targetOrphan) {
                count++;
                if (descriptions.size() < 10) {
                    String srcName = source != null ? source.getId() : "null";
                    String tgtName = target != null ? target.getId() : "null";
                    descriptions.add("Connection '" + conn.getId()
                            + "' references missing view object(s): source="
                            + srcName + " target=" + tgtName);
                }
            }
        }

        return new OrphanDetectionResult(count, descriptions);
    }

    /**
     * Recursively collects all view objects as AssessmentNode records,
     * including nested children with parentId references.
     * All coordinates are converted to absolute canvas space by accumulating
     * parent offsets during recursion (Fix for adversarial review finding #1).
     */

    /**
     * Returns null if list is empty, so @JsonInclude(NON_NULL) omits empty arrays.
     */
    private List<String> emptyToNull(List<String> list) {
        return (list == null || list.isEmpty()) ? null : list;
    }

    // ---- Auto-route connections (Story 9-5) ----

    @Override
    public MutationResult<AutoRouteResultDto> autoRouteConnections(
            String sessionId, String viewId,
            List<String> connectionIds, String strategy, boolean force) {
        logger.info("Auto-route connections: viewId={}, strategy={}, connectionIds={}, force={}",
                viewId, strategy, connectionIds != null ? connectionIds.size() : "all", force);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1. Validate view
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 2. Validate strategy
            String effectiveStrategy = (strategy == null || strategy.isBlank())
                    ? "orthogonal" : strategy;
            if (!"orthogonal".equals(effectiveStrategy) && !"clear".equals(effectiveStrategy)) {
                throw new ModelAccessException(
                        "Invalid strategy: '" + effectiveStrategy
                                + "'. Valid: orthogonal, clear",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 3. Collect connections
            List<IDiagramModelConnection> allConnections =
                    AssessmentCollector.collectAllConnections(diagramModel);

            // 4. Filter by connectionIds if provided
            List<IDiagramModelConnection> targetConnections;
            List<String> warnings = new ArrayList<>();
            if (connectionIds != null && !connectionIds.isEmpty()) {
                Map<String, IDiagramModelConnection> connMap = new LinkedHashMap<>();
                for (IDiagramModelConnection conn : allConnections) {
                    connMap.put(conn.getId(), conn);
                }
                targetConnections = new ArrayList<>();
                for (String connId : connectionIds) {
                    IDiagramModelConnection conn = connMap.get(connId);
                    if (conn == null) {
                        warnings.add("Connection not found on view: " + connId);
                        continue;
                    }
                    targetConnections.add(conn);
                }
                if (targetConnections.isEmpty() && !warnings.isEmpty()) {
                    throw new ModelAccessException(
                            "None of the specified connection IDs were found on the view",
                            ErrorCode.ELEMENT_NOT_FOUND);
                }
            } else {
                targetConnections = allConnections;
            }

            // 5. Handle empty view / no connections
            if (targetConnections.isEmpty()) {
                AutoRouteResultDto dto = new AutoRouteResultDto(
                        viewId, 0, effectiveStrategy, false);
                return new MutationResult<>(dto, null);
            }

            // 6. Build commands
            List<Command> commands = new ArrayList<>();
            int routedCount = 0;
            int labelsOptimized = 0;
            List<FailedConnection> failedConnections = List.of();
            List<MoveRecommendation> moveRecommendations = List.of();

            if ("clear".equals(effectiveStrategy)) {
                // Clear: empty bendpoints for each connection
                for (IDiagramModelConnection conn : targetConnections) {
                    if (conn instanceof IDiagramModelArchimateConnection archConn) {
                        PreparedMutation<ViewConnectionDto> prepared =
                                prepareUpdateViewConnectionDirect(archConn, List.of(), null);
                        commands.add(prepared.command());
                        routedCount++;
                    }
                }
            } else {
                // Orthogonal: compute routing for each connection
                List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);

                // Pre-route validation: detect stacked elements sharing identical positions
                {
                    Map<String, List<String>> positionMap = new LinkedHashMap<>();
                    for (AssessmentNode node : nodes) {
                        if (!node.isGroup()) {
                            String posKey = (int) node.x() + "," + (int) node.y();
                            // Resolve element name via model for meaningful warning messages
                            String nodeName = node.id();
                            EObject nodeObj = ArchimateModelUtils.getObjectByID(model, node.id());
                            if (nodeObj instanceof IDiagramModelObject dmo && dmo.getName() != null) {
                                nodeName = dmo.getName();
                            }
                            positionMap.computeIfAbsent(posKey, k -> new ArrayList<>()).add(nodeName);
                        }
                    }
                    for (Map.Entry<String, List<String>> entry : positionMap.entrySet()) {
                        if (entry.getValue().size() > 1) {
                            warnings.add("Stacked elements at position (" + entry.getKey()
                                    + "): " + entry.getValue()
                                    + ". Run layout-within-group first to separate them for cleaner routing.");
                        }
                    }
                }

                // Route all connections via shared helper
                OrthogonalRoutingResult routeResult = buildOrthogonalRoutingCommands(
                        diagramModel, targetConnections, nodes, force);
                commands.addAll(routeResult.commands);
                routedCount = routeResult.routedCount;
                labelsOptimized = routeResult.labelsOptimized;
                failedConnections = routeResult.failedConnections;
                moveRecommendations = routeResult.moveRecommendations;
            }

            // 7. Switch view to bendpoint mode if needed (Story 10-11)
            // Manhattan mode ignores stored bendpoints — our A* paths are invisible
            // unless the view uses bendpoint (manual) mode.
            boolean routerTypeSwitched = false;
            int currentRouterType = diagramModel.getConnectionRouterType();
            if (!"clear".equals(effectiveStrategy)
                    && currentRouterType
                            != IDiagramModel.CONNECTION_ROUTER_BENDPOINT) {
                commands.add(new UpdateViewCommand(diagramModel,
                        null, null, false, null, null,
                        IDiagramModel.CONNECTION_ROUTER_BENDPOINT));
                routerTypeSwitched = true;
                logger.info("Switching view {} from router type {} to bendpoint mode "
                        + "so computed paths render correctly",
                        viewId, currentRouterType);
            }

            // 8. Validate total operation count
            if (commands.size() > MAX_LAYOUT_OPERATIONS) {
                throw new ModelAccessException(
                        "Auto-route operation count (" + commands.size()
                                + ") exceeds maximum (" + MAX_LAYOUT_OPERATIONS + ")",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 9. Build compound command
            String label = "Auto-route connections (" + effectiveStrategy
                    + ", " + routedCount + " connections)";
            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            // Build view-object-ID → name lookup for failed connections and recommendations (Story 10-30, 10-31)
            Map<String, String> viewObjectNameMap = new LinkedHashMap<>();
            if (!failedConnections.isEmpty() || !moveRecommendations.isEmpty()) {
                for (IDiagramModelConnection conn : targetConnections) {
                    if (conn.getSource() instanceof IDiagramModelObject src) {
                        viewObjectNameMap.put(src.getId(), src.getName());
                    }
                    if (conn.getTarget() instanceof IDiagramModelObject tgt) {
                        viewObjectNameMap.put(tgt.getId(), tgt.getName());
                    }
                }
                // Include all view objects for recommendation element name resolution (Story 10-31)
                // Recommendation element IDs are view object IDs from RoutingRect.id()
                // Resolve names via the model for any IDs not already in the map
                for (MoveRecommendation rec : moveRecommendations) {
                    if (!viewObjectNameMap.containsKey(rec.elementId())) {
                        EObject obj = ArchimateModelUtils.getObjectByID(model, rec.elementId());
                        if (obj instanceof IDiagramModelObject dmo) {
                            viewObjectNameMap.put(rec.elementId(), dmo.getName());
                        }
                    }
                }
            }

            // Build response DTOs based on force mode (Story 10-30, 10-31, 10-32)
            AutoRouteResultDto dto;
            if (force) {
                // Force mode: all routes applied, report violations instead of failures
                List<RoutingViolationDto> violationDtos = new ArrayList<>();
                for (FailedConnection fc : failedConnections) {
                    String srcName = viewObjectNameMap.getOrDefault(fc.sourceId(), fc.sourceId());
                    String tgtName = viewObjectNameMap.getOrDefault(fc.targetId(), fc.targetId());
                    String crossedId = fc.crossedElementId();
                    String crossedName = resolveCrossedElementName(crossedId, viewObjectNameMap, model);
                    violationDtos.add(new RoutingViolationDto(
                            fc.connectionId(), srcName, tgtName,
                            fc.constraintViolated(), severityFor(fc.constraintViolated()),
                            crossedId, crossedName));
                }
                dto = new AutoRouteResultDto(
                        viewId, routedCount, 0, effectiveStrategy,
                        routerTypeSwitched, labelsOptimized, warnings, List.of(), List.of(), violationDtos);
            } else {
                // Default mode: failed connections excluded, report failures + recommendations
                List<FailedConnectionDto> failedDtos = new ArrayList<>();
                for (FailedConnection fc : failedConnections) {
                    String srcName = viewObjectNameMap.getOrDefault(fc.sourceId(), fc.sourceId());
                    String tgtName = viewObjectNameMap.getOrDefault(fc.targetId(), fc.targetId());
                    String crossedId = fc.crossedElementId();
                    String crossedName = resolveCrossedElementName(crossedId, viewObjectNameMap, model);
                    failedDtos.add(new FailedConnectionDto(
                            fc.connectionId(), srcName, tgtName, fc.constraintViolated(),
                            crossedId, crossedName));
                }
                List<MoveRecommendationDto> recommendationDtos = new ArrayList<>();
                for (MoveRecommendation rec : moveRecommendations) {
                    String elemName = viewObjectNameMap.getOrDefault(rec.elementId(), rec.elementId());
                    recommendationDtos.add(new MoveRecommendationDto(
                            rec.elementId(), elemName,
                            rec.dx(), rec.dy(), rec.reason(), rec.connectionsUnblocked()));
                }
                dto = new AutoRouteResultDto(
                        viewId, routedCount, failedDtos.size(), effectiveStrategy,
                        routerTypeSwitched, labelsOptimized, warnings, failedDtos,
                        recommendationDtos, List.of());
            }

            // 10. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("strategy", effectiveStrategy);
                proposedChanges.put("connectionsRouted", routedCount);
                if (routerTypeSwitched) {
                    proposedChanges.put("routerTypeSwitched",
                            "manhattan -> manual (bendpoint mode)");
                }
                ProposalContext ctx = storeAsProposal(sessionId,
                        "auto-route-connections", compound, dto, label,
                        null, proposedChanges,
                        "Connection routing computed and ready for application.");
                return new MutationResult<>(dto, null, ctx);
            }

            // 11. Dispatch or queue
            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(dto, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException
                | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error auto-routing connections for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /** Maps a constraint violation type to a severity string (Story 10-32). */
    private static String severityFor(String constraintViolated) {
        if ("element_crossing".equals(constraintViolated)) {
            return "warning";
        }
        return "info";
    }

    /**
     * Resolves the display name for a crossed element ID (Story 10-34).
     * Checks the viewObjectNameMap first, then falls back to model lookup.
     */
    private static String resolveCrossedElementName(String crossedId,
            Map<String, String> viewObjectNameMap, IArchimateModel model) {
        if (crossedId == null) {
            return null;
        }
        String name = viewObjectNameMap.get(crossedId);
        if (name != null) {
            return name;
        }
        EObject obj = ArchimateModelUtils.getObjectByID(model, crossedId);
        if (obj instanceof IDiagramModelObject dmo) {
            return dmo.getName();
        }
        return crossedId; // fallback to ID if name can't be resolved
    }

    // ---- Auto-layout-and-route (Story 10-29, quality target Story 11-16) ----

    /** Maximum iterations for targetRating quality loop (Story 11-16). */
    private static final int MAX_TARGET_RATING_ITERATIONS = 5;
    /** Spacing increment per iteration for targetRating quality loop (Story 11-16). */
    private static final int TARGET_RATING_SPACING_INCREMENT = 20;

    @Override
    public MutationResult<AutoLayoutAndRouteResultDto> autoLayoutAndRoute(
            String sessionId, String viewId,
            String direction, int spacing, String targetRating) {
        logger.info("Auto-layout-and-route: viewId={}, direction={}, spacing={}, targetRating={}",
                viewId, direction, spacing, targetRating);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1. Validate view
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 2. Collect all nodes (including nested children) for ELK
            List<LayoutNode> nodes = collectLayoutNodesRecursive(diagramModel);
            List<LayoutEdge> edges = collectLayoutEdgesRecursive(diagramModel, nodes);

            if (nodes.isEmpty()) {
                throw new ModelAccessException(
                        "View has no elements to layout",
                        ErrorCode.INVALID_PARAMETER);
            }

            // No targetRating — single-pass mode (backward compatible)
            if (targetRating == null) {
                return executeSingleLayoutPass(sessionId, viewId, direction,
                        spacing, model, diagramModel, nodes, edges);
            }

            // Story 11-16: quality target iteration loop
            return executeQualityTargetLoop(sessionId, viewId, direction,
                    spacing, targetRating, model, diagramModel, nodes, edges);

        } catch (NoModelLoadedException | ModelAccessException
                | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error computing/applying ELK layout for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Single-pass ELK layout (original behavior, no quality iteration).
     */
    private MutationResult<AutoLayoutAndRouteResultDto> executeSingleLayoutPass(
            String sessionId, String viewId, String direction, int spacing,
            IArchimateModel model, IArchimateDiagramModel diagramModel,
            List<LayoutNode> nodes, List<LayoutEdge> edges) {

        ElkLayoutPassResult pass = computeElkLayoutPass(
                viewId, direction, spacing, model, diagramModel, nodes, edges);

        AutoLayoutAndRouteResultDto dto = new AutoLayoutAndRouteResultDto(
                viewId,
                direction != null ? direction.toUpperCase() : "DOWN",
                spacing > 0 ? spacing : 50,
                pass.positionCount, pass.routedCount,
                pass.routerTypeSwitched, pass.compound.size());

        // Approval gate
        if (mutationDispatcher.isApprovalRequired(sessionId)) {
            Map<String, Object> proposedChanges = new LinkedHashMap<>();
            proposedChanges.put("direction", direction != null ? direction : "DOWN");
            proposedChanges.put("spacing", spacing > 0 ? spacing : 50);
            proposedChanges.put("elementsRepositioned", pass.positionCount);
            proposedChanges.put("connectionsRouted", pass.routedCount);
            ProposalContext ctx = storeAsProposal(sessionId,
                    "auto-layout-and-route", pass.compound, dto, pass.compound.getLabel(),
                    null, proposedChanges,
                    "ELK layout computed and ready for application.");
            return new MutationResult<>(dto, null, ctx);
        }

        // Dispatch or queue
        Integer batchSeq = dispatchOrQueue(sessionId, pass.compound, pass.compound.getLabel());
        if (batchSeq == null) {
            versionCounter.incrementAndGet();
        }
        return new MutationResult<>(dto, batchSeq);
    }

    /**
     * Quality target iteration loop (Story 11-16).
     * Iterates with increasing spacing until target rating is met, plateau detected,
     * or max iterations reached. Keeps the best result.
     *
     * <p>Each iteration temporarily applies the layout (via dispatchImmediate) so that
     * assess-layout can read EMF positions, then undoes it. The final best result is
     * dispatched through the standard approval/batch/dispatch path.</p>
     */
    private MutationResult<AutoLayoutAndRouteResultDto> executeQualityTargetLoop(
            String sessionId, String viewId, String direction, int baseSpacing,
            String targetRating, IArchimateModel model,
            IArchimateDiagramModel diagramModel,
            List<LayoutNode> nodes, List<LayoutEdge> edges) {

        String effectiveDirection = direction != null ? direction.toUpperCase() : "DOWN";
        int effectiveBaseSpacing = baseSpacing > 0 ? baseSpacing : 50;

        // Track best result across iterations
        NonNotifyingCompoundCommand bestCompound = null;
        String bestRating = "not-applicable";
        int bestScore = Integer.MAX_VALUE; // overlaps + crossings (lower is better)
        int bestPositionCount = 0;
        int bestRoutedCount = 0;
        int bestLabelsOptimized = 0;
        boolean bestRouterTypeSwitched = false;
        int bestSpacing = effectiveBaseSpacing;
        AssessLayoutResultDto bestAssessment = null;

        String previousRating = null;
        int previousScore = -1;
        double previousAvgSpacing = 0;
        int iterationsPerformed = 0;

        for (int i = 0; i < MAX_TARGET_RATING_ITERATIONS; i++) {
            int currentSpacing = effectiveBaseSpacing + (i * TARGET_RATING_SPACING_INCREMENT);
            iterationsPerformed = i + 1;
            logger.info("Quality target iteration {}/{}: spacing={}, target={}",
                    iterationsPerformed, MAX_TARGET_RATING_ITERATIONS,
                    currentSpacing, targetRating);

            // Re-collect nodes/edges for each iteration (EMF state changes after undo)
            List<LayoutNode> iterNodes = (i == 0) ? nodes
                    : collectLayoutNodesRecursive(diagramModel);
            List<LayoutEdge> iterEdges = (i == 0) ? edges
                    : collectLayoutEdgesRecursive(diagramModel, iterNodes);

            // Compute and apply layout temporarily for assessment
            ElkLayoutPassResult pass = computeElkLayoutPass(
                    viewId, direction, currentSpacing, model,
                    diagramModel, iterNodes, iterEdges);

            // Execute temporarily so assess-layout can read updated positions
            mutationDispatcher.dispatchImmediate(pass.compound);
            int undoCount = 1;

            // Story 11-27: optimize-group-order pass (grouped views only)
            OptimizeGroupOrderPassResult optimizeResult =
                    computeOptimizeGroupOrderPass(diagramModel, model, direction);
            AutoRoutePassResult routeResult = null;
            if (optimizeResult != null) {
                mutationDispatcher.dispatchImmediate(optimizeResult.compound);
                undoCount++;

                // Re-route connections after element reordering
                routeResult = computeAutoRoutePass(viewId, diagramModel, model);
                if (routeResult != null) {
                    mutationDispatcher.dispatchImmediate(routeResult.compound);
                    undoCount++;
                }
            }

            // Assess layout quality
            AssessLayoutResultDto assessment = assessLayout(viewId);
            String rating = assessment.overallRating();
            int score = assessment.overlapCount() + assessment.edgeCrossingCount();

            logger.info("Quality target iteration {}: spacing={}, rating={}, avgSpacing={}, overlaps={}, crossings={}{}",
                    iterationsPerformed, currentSpacing, rating,
                    assessment.averageSpacing(),
                    assessment.overlapCount(), assessment.edgeCrossingCount(),
                    optimizeResult != null ? " [+optimize-group-order]" : "");

            // Track best result — merge all compounds into one for atomic undo
            if (LayoutQualityAssessor.ratingOrdinal(rating) > LayoutQualityAssessor.ratingOrdinal(bestRating)
                    || (LayoutQualityAssessor.ratingOrdinal(rating) == LayoutQualityAssessor.ratingOrdinal(bestRating)
                        && score < bestScore)) {
                // Merge ELK + optimize + route into single compound for final dispatch
                NonNotifyingCompoundCommand mergedCompound =
                        new NonNotifyingCompoundCommand(pass.compound.getLabel());
                for (Object cmd : pass.compound.getCommands()) {
                    mergedCompound.add((Command) cmd);
                }
                if (optimizeResult != null) {
                    for (Object cmd : optimizeResult.compound.getCommands()) {
                        mergedCompound.add((Command) cmd);
                    }
                }
                if (routeResult != null) {
                    for (Object cmd : routeResult.compound.getCommands()) {
                        mergedCompound.add((Command) cmd);
                    }
                }
                bestCompound = mergedCompound;
                bestRating = rating;
                bestScore = score;
                bestPositionCount = pass.positionCount
                        + (optimizeResult != null ? optimizeResult.positionCount : 0);
                bestRoutedCount = pass.routedCount
                        + (routeResult != null ? routeResult.routedCount : 0);
                bestLabelsOptimized = (routeResult != null ? routeResult.labelsOptimized : 0);
                bestRouterTypeSwitched = pass.routerTypeSwitched;
                bestSpacing = currentSpacing;
                bestAssessment = assessment;
            }

            // Undo ALL dispatched commands — finalization re-applies best via proper channels
            mutationDispatcher.undo(undoCount);

            // Target met — break to finalization
            if (LayoutQualityAssessor.meetsTarget(rating, targetRating)) {
                logger.info("Quality target '{}' met with rating '{}' on iteration {}",
                        targetRating, rating, iterationsPerformed);
                break;
            }

            // Plateau detection: same rating AND same score AND no spacing improvement
            double avgSpacing = assessment.averageSpacing();

            if (i > 0 && isPlateauReached(rating, previousRating, score,
                    previousScore, avgSpacing, previousAvgSpacing)) {
                logger.info("Quality target plateau detected at iteration {} — stopping early",
                        iterationsPerformed);
                break;
            }

            previousRating = rating;
            previousScore = score;
            previousAvgSpacing = avgSpacing;
        }

        // Finalize: nothing is currently applied — dispatch best via approval/batch
        if (bestCompound == null) {
            throw new ModelAccessException(
                    "Quality target iteration produced no results",
                    ErrorCode.INTERNAL_ERROR);
        }

        logger.info("Quality target loop complete: best='{}' after {} iterations (target='{}')",
                bestRating, iterationsPerformed, targetRating);

        AutoLayoutAndRouteResultDto dto = buildQualityTargetDto(
                viewId, effectiveDirection, bestSpacing,
                bestPositionCount, bestRoutedCount, bestRouterTypeSwitched,
                bestCompound.size(), bestLabelsOptimized, targetRating, bestRating,
                iterationsPerformed, bestAssessment);

        // Approval gate (Story 11-16 edge case #4: applies to final iteration only)
        if (mutationDispatcher.isApprovalRequired(sessionId)) {
            Map<String, Object> proposedChanges = new LinkedHashMap<>();
            proposedChanges.put("direction", effectiveDirection);
            proposedChanges.put("spacing", bestSpacing);
            proposedChanges.put("elementsRepositioned", bestPositionCount);
            proposedChanges.put("connectionsRouted", bestRoutedCount);
            proposedChanges.put("targetRating", targetRating);
            proposedChanges.put("achievedRating", bestRating);
            proposedChanges.put("iterationsPerformed", iterationsPerformed);
            ProposalContext ctx = storeAsProposal(sessionId,
                    "auto-layout-and-route", bestCompound, dto,
                    bestCompound.getLabel(), null, proposedChanges,
                    "ELK layout with quality target '" + targetRating
                    + "' computed — achieved '" + bestRating
                    + "' after " + iterationsPerformed + " iteration(s).");
            return new MutationResult<>(dto, null, ctx);
        }

        // Dispatch or queue (batch support)
        Integer batchSeq = dispatchOrQueue(sessionId, bestCompound,
                bestCompound.getLabel());
        if (batchSeq == null) {
            versionCounter.incrementAndGet();
        }
        return new MutationResult<>(dto, batchSeq);
    }

    private AutoLayoutAndRouteResultDto buildQualityTargetDto(
            String viewId, String direction, int spacing,
            int positionCount, int routedCount, boolean routerTypeSwitched,
            int totalOperations, int labelsOptimized,
            String targetRating, String achievedRating,
            int iterationsPerformed, AssessLayoutResultDto assessment) {
        AutoLayoutAssessmentSummaryDto summary = assessment != null
                ? new AutoLayoutAssessmentSummaryDto(
                        assessment.overlapCount(),
                        assessment.edgeCrossingCount(),
                        assessment.averageSpacing(),
                        assessment.alignmentScore(),
                        assessment.overallRating(),
                        assessment.suggestions())
                : null;
        return new AutoLayoutAndRouteResultDto(
                viewId, direction, spacing,
                positionCount, routedCount, routerTypeSwitched, totalOperations,
                labelsOptimized, targetRating, achievedRating,
                iterationsPerformed, summary);
    }

    /**
     * Result of a single ELK layout computation pass.
     * Contains the compound command and counts, but does NOT execute the command.
     */
    private static class ElkLayoutPassResult {
        final NonNotifyingCompoundCommand compound;
        final int positionCount;
        final int routedCount;
        final boolean routerTypeSwitched;

        ElkLayoutPassResult(NonNotifyingCompoundCommand compound,
                int positionCount, int routedCount, boolean routerTypeSwitched) {
            this.compound = compound;
            this.positionCount = positionCount;
            this.routedCount = routedCount;
            this.routerTypeSwitched = routerTypeSwitched;
        }
    }

    /**
     * Result from buildOrthogonalRoutingCommands — shared routing logic (Story 11-27 refactor).
     */
    private static class OrthogonalRoutingResult {
        final List<Command> commands;
        final int routedCount;
        final List<FailedConnection> failedConnections;
        final List<MoveRecommendation> moveRecommendations;
        final int labelsOptimized;

        OrthogonalRoutingResult(List<Command> commands, int routedCount,
                List<FailedConnection> failedConnections,
                List<MoveRecommendation> moveRecommendations,
                int labelsOptimized) {
            this.commands = commands;
            this.routedCount = routedCount;
            this.failedConnections = failedConnections;
            this.moveRecommendations = moveRecommendations;
            this.labelsOptimized = labelsOptimized;
        }
    }

    /**
     * Result from computeOptimizeGroupOrderPass (Story 11-27).
     */
    static class OptimizeGroupOrderPassResult {
        final NonNotifyingCompoundCommand compound;
        final int positionCount;

        OptimizeGroupOrderPassResult(NonNotifyingCompoundCommand compound, int positionCount) {
            this.compound = compound;
            this.positionCount = positionCount;
        }
    }

    /**
     * Result from computeAutoRoutePass (Story 11-27).
     */
    static class AutoRoutePassResult {
        final NonNotifyingCompoundCommand compound;
        final int routedCount;
        final int labelsOptimized;

        AutoRoutePassResult(NonNotifyingCompoundCommand compound, int routedCount,
                int labelsOptimized) {
            this.compound = compound;
            this.routedCount = routedCount;
            this.labelsOptimized = labelsOptimized;
        }
    }

    /**
     * Computes a single ELK layout pass: ELK positions + connection routes + router switch.
     * Returns the compound command without executing it.
     */
    private ElkLayoutPassResult computeElkLayoutPass(
            String viewId, String direction, int spacing,
            IArchimateModel model, IArchimateDiagramModel diagramModel,
            List<LayoutNode> nodes, List<LayoutEdge> edges) {

        // Compute ELK layout (positions + routes)
        ElkLayoutResult elkResult = elkLayoutEngine.computeLayout(
                nodes, edges, direction, spacing);

        // Build commands for element position updates
        List<Command> commands = new ArrayList<>();
        int positionCount = 0;
        for (ViewPositionSpec pos : elkResult.positions()) {
            PreparedMutation<ViewObjectDto> prepared =
                    prepareUpdateViewObject(pos.viewObjectId(),
                            pos.x(), pos.y(), pos.width(), pos.height(),
                            null, null);
            commands.add(prepared.command());
            positionCount++;
        }

        // Build commands for connection bendpoint updates
        int routedCount = 0;
        Map<String, ViewPositionSpec> positionById = new LinkedHashMap<>();
        for (ViewPositionSpec pos : elkResult.positions()) {
            positionById.put(pos.viewObjectId(), pos);
        }
        Map<String, int[]> elkCenterByViewObjectId =
                computeElkAbsoluteCenters(positionById, nodes);

        for (Map.Entry<String, List<AbsoluteBendpointDto>> entry :
                elkResult.connectionBendpoints().entrySet()) {
            String connectionId = entry.getKey();
            List<AbsoluteBendpointDto> absBendpoints = entry.getValue();

            EObject connObj = ArchimateModelUtils.getObjectByID(model, connectionId);
            if (!(connObj instanceof IDiagramModelArchimateConnection archConn)) {
                continue;
            }

            IConnectable srcConn = archConn.getSource();
            IConnectable tgtConn = archConn.getTarget();
            if (!(srcConn instanceof IDiagramModelObject srcObj)
                    || !(tgtConn instanceof IDiagramModelObject tgtObj)) {
                continue;
            }

            int[] srcCenter = elkCenterByViewObjectId.get(srcObj.getId());
            int[] tgtCenter = elkCenterByViewObjectId.get(tgtObj.getId());
            if (srcCenter == null || tgtCenter == null) {
                if (srcCenter == null) srcCenter = ConnectionResponseBuilder.computeAbsoluteCenter(srcObj);
                if (tgtCenter == null) tgtCenter = ConnectionResponseBuilder.computeAbsoluteCenter(tgtObj);
            }

            List<BendpointDto> relativeBendpoints = ConnectionResponseBuilder.convertAbsoluteToRelative(
                    absBendpoints,
                    srcCenter[0], srcCenter[1],
                    tgtCenter[0], tgtCenter[1]);

            PreparedMutation<ViewConnectionDto> prepared =
                    prepareUpdateViewConnectionDirect(archConn, relativeBendpoints, null);
            commands.add(prepared.command());
            routedCount++;
        }

        // Switch view to bendpoint mode if needed
        boolean routerTypeSwitched = false;
        int currentRouterType = diagramModel.getConnectionRouterType();
        if (currentRouterType != IDiagramModel.CONNECTION_ROUTER_BENDPOINT) {
            commands.add(new UpdateViewCommand(diagramModel,
                    null, null, false, null, null,
                    IDiagramModel.CONNECTION_ROUTER_BENDPOINT));
            routerTypeSwitched = true;
            logger.info("Switching view {} from router type {} to bendpoint mode",
                    diagramModel.getId(), currentRouterType);
        }

        // Validate total operation count
        if (commands.size() > MAX_LAYOUT_OPERATIONS) {
            throw new ModelAccessException(
                    "Auto-layout-and-route operation count (" + commands.size()
                            + ") exceeds maximum (" + MAX_LAYOUT_OPERATIONS + ")",
                    ErrorCode.INVALID_PARAMETER);
        }

        // Build compound command
        String label = "Auto-layout-and-route (ELK Layered, direction="
                + (direction != null ? direction : "DOWN")
                + ", spacing=" + spacing
                + ", " + positionCount + " positions, "
                + routedCount + " connections)";
        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand(label);
        commands.forEach(compound::add);

        return new ElkLayoutPassResult(compound, positionCount, routedCount, routerTypeSwitched);
    }

    /**
     * Shared orthogonal routing logic used by both autoRouteConnections and
     * computeAutoRoutePass (Story 11-27 refactor). Builds batch routing inputs,
     * routes all connections, and converts results to commands.
     *
     * @param targetConnections connections to route (all or filtered subset)
     * @param nodes pre-collected assessment nodes for obstacle building
     * @param force if true, apply violated routes too (force mode)
     * @return routing result with commands, counts, and pipeline diagnostics
     */
    private OrthogonalRoutingResult buildOrthogonalRoutingCommands(
            IArchimateDiagramModel diagramModel,
            List<IDiagramModelConnection> targetConnections,
            List<AssessmentNode> nodes,
            boolean force) {

        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        // Build batch routing input with per-connection obstacle exclusion
        RoutingPipeline pipeline = new RoutingPipeline();
        List<RoutingPipeline.ConnectionEndpoints> batchInput = new ArrayList<>();
        List<IDiagramModelArchimateConnection> batchConnections = new ArrayList<>();

        for (IDiagramModelConnection conn : targetConnections) {
            if (!(conn instanceof IDiagramModelArchimateConnection archConn)) {
                continue;
            }

            IConnectable srcConn = conn.getSource();
            IConnectable tgtConn = conn.getTarget();
            if (!(srcConn instanceof IDiagramModelObject)
                    || !(tgtConn instanceof IDiagramModelObject)) {
                continue;
            }

            AssessmentNode srcNode = nodeMap.get(srcConn.getId());
            AssessmentNode tgtNode = nodeMap.get(tgtConn.getId());
            if (srcNode == null || tgtNode == null) {
                continue;
            }

            // Build obstacle list: exclude source, target, their ancestors, their visual children,
            // and all groups (transparent containers) (Story 10-25, Pattern 2 fix)
            Set<String> excludeIds = new HashSet<>();
            excludeIds.add(srcNode.id());
            excludeIds.add(tgtNode.id());
            excludeIds.addAll(getAncestorIds(srcNode.id(), nodeMap, nodes));
            excludeIds.addAll(getAncestorIds(tgtNode.id(), nodeMap, nodes));
            excludeIds.addAll(getChildIds(srcNode.id(), nodes));
            excludeIds.addAll(getChildIds(tgtNode.id(), nodes));

            List<RoutingRect> obstacles = new ArrayList<>();
            for (AssessmentNode node : nodes) {
                if (!excludeIds.contains(node.id()) && !node.isGroup()) {
                    obstacles.add(new RoutingRect(
                            (int) node.x(), (int) node.y(),
                            (int) node.width(), (int) node.height(),
                            node.id()));
                }
            }

            RoutingRect srcRect = new RoutingRect(
                    (int) srcNode.x(), (int) srcNode.y(),
                    (int) srcNode.width(), (int) srcNode.height(),
                    srcNode.id());
            RoutingRect tgtRect = new RoutingRect(
                    (int) tgtNode.x(), (int) tgtNode.y(),
                    (int) tgtNode.width(), (int) tgtNode.height(),
                    tgtNode.id());

            // Extract label text for label clearance
            String labelText = "";
            IArchimateRelationship connRel = archConn.getArchimateRelationship();
            if (connRel != null && connRel.getName() != null) {
                labelText = connRel.getName();
            }

            batchInput.add(new RoutingPipeline.ConnectionEndpoints(
                    archConn.getId(), srcRect, tgtRect, obstacles,
                    labelText, archConn.getTextPosition()));
            batchConnections.add(archConn);
        }

        if (batchInput.isEmpty()) {
            return new OrthogonalRoutingResult(
                    List.of(), 0, List.of(), List.of(), 0);
        }

        // Build unified obstacle list for corridor width and neighbor collision checks.
        // Exclude groups — they are transparent containers (Story 10-22, 10-33).
        List<RoutingRect> allObstacles = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (!node.isGroup()) {
                allObstacles.add(new RoutingRect(
                        (int) node.x(), (int) node.y(),
                        (int) node.width(), (int) node.height(),
                        node.id()));
            }
        }

        // Build per-connection label exclusion sets (source, target, ancestors, descendants)
        // for label position optimizer — matches LayoutQualityAssessor exclusion logic (AC3).
        Map<String, Set<String>> labelExcludeSets = new LinkedHashMap<>();
        Map<String, AssessmentNode> nodeMapForExclude = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMapForExclude.put(node.id(), node);
        }
        for (RoutingPipeline.ConnectionEndpoints conn : batchInput) {
            Set<String> excludeIds = new HashSet<>();
            if (conn.source().id() != null) {
                excludeIds.add(conn.source().id());
                excludeIds.addAll(getAncestorIds(conn.source().id(), nodeMapForExclude, nodes));
                excludeIds.addAll(getChildIds(conn.source().id(), nodes));
            }
            if (conn.target().id() != null) {
                excludeIds.add(conn.target().id());
                excludeIds.addAll(getAncestorIds(conn.target().id(), nodeMapForExclude, nodes));
                excludeIds.addAll(getChildIds(conn.target().id(), nodes));
            }
            labelExcludeSets.put(conn.connectionId(), excludeIds);
        }

        // Route all connections with path ordering and edge nudging
        RoutingResult routingResult =
                pipeline.routeAllConnections(batchInput, allObstacles, labelExcludeSets);

        // Build routes to apply based on force mode
        Map<String, List<AbsoluteBendpointDto>> routesToApply;
        if (force) {
            routesToApply = new LinkedHashMap<>(routingResult.routed());
            routesToApply.putAll(routingResult.violatedRoutes());
        } else {
            routesToApply = routingResult.routed();
        }

        // Convert results to relative bendpoints and build commands
        List<Command> commands = new ArrayList<>();
        int routedCount = 0;
        for (int i = 0; i < batchConnections.size(); i++) {
            IDiagramModelArchimateConnection archConn = batchConnections.get(i);
            RoutingPipeline.ConnectionEndpoints endpoints = batchInput.get(i);
            List<AbsoluteBendpointDto> absBendpoints =
                    routesToApply.get(archConn.getId());
            if (absBendpoints == null) {
                continue;
            }

            int srcCX = endpoints.source().centerX();
            int srcCY = endpoints.source().centerY();
            int tgtCX = endpoints.target().centerX();
            int tgtCY = endpoints.target().centerY();
            List<BendpointDto> relativeBendpoints = ConnectionResponseBuilder.convertAbsoluteToRelative(
                    absBendpoints, srcCX, srcCY, tgtCX, tgtCY);

            PreparedMutation<ViewConnectionDto> prepared =
                    prepareUpdateViewConnectionDirect(archConn, relativeBendpoints, null);
            commands.add(prepared.command());
            routedCount++;
        }

        // Apply label position optimization results (Story 11-31)
        Map<String, Integer> optimalPositions = routingResult.optimalPositions();
        if (!optimalPositions.isEmpty()) {
            // Build connection lookup for efficient text position write-back
            Map<String, IDiagramModelArchimateConnection> connLookup = new LinkedHashMap<>();
            for (IDiagramModelArchimateConnection archConn : batchConnections) {
                connLookup.put(archConn.getId(), archConn);
            }
            for (Map.Entry<String, Integer> entry : optimalPositions.entrySet()) {
                IDiagramModelArchimateConnection conn = connLookup.get(entry.getKey());
                if (conn != null && conn.getTextPosition() != entry.getValue()) {
                    commands.add(new SetTextPositionCommand(conn, entry.getValue()));
                }
            }
        }

        return new OrthogonalRoutingResult(
                commands, routedCount,
                routingResult.failed(), routingResult.recommendations(),
                routingResult.labelsOptimized());
    }

    /**
     * Computes an optimize-group-order pass for a grouped view (Story 11-27).
     * Returns result with compound command and position count,
     * or null if the view has no groups, no inter-group connections, or reordering
     * doesn't improve crossing count.
     *
     * <p>Reads the CURRENT EMF state (should be called after ELK dispatch so positions
     * reflect the latest layout). Uses "column" arrangement for vertical flow directions
     * (DOWN/UP) and "row" for horizontal (RIGHT/LEFT).</p>
     */
    OptimizeGroupOrderPassResult computeOptimizeGroupOrderPass(
            IArchimateDiagramModel diagramModel, IArchimateModel model,
            String direction) {

        // 1. Collect top-level groups
        List<IDiagramModelGroup> topLevelGroups = new ArrayList<>();
        for (IDiagramModelObject child : diagramModel.getChildren()) {
            if (child instanceof IDiagramModelGroup group) {
                topLevelGroups.add(group);
            }
        }
        if (topLevelGroups.isEmpty()) {
            return null; // Flat view — no groups to optimize
        }

        // 2. Build CrossingMinimizer inputs
        Map<String, IDiagramModelGroup> groupMap = new LinkedHashMap<>();
        Map<String, String> elementToGroupId = new HashMap<>();
        List<CrossingMinimizer.GroupInfo> groupInfos = new ArrayList<>();

        for (IDiagramModelGroup group : topLevelGroups) {
            String groupId = group.getId();
            groupMap.put(groupId, group);

            List<String> elementIds = new ArrayList<>();
            List<int[]> centers = new ArrayList<>();

            for (IDiagramModelObject child : group.getChildren()) {
                if (child instanceof IDiagramModelNote) {
                    continue;
                }
                String childId = child.getId();
                elementIds.add(childId);
                elementToGroupId.put(childId, groupId);

                IBounds bounds = child.getBounds();
                IBounds groupBounds = group.getBounds();
                int absCenterX = groupBounds.getX() + bounds.getX() + bounds.getWidth() / 2;
                int absCenterY = groupBounds.getY() + bounds.getY() + bounds.getHeight() / 2;
                centers.add(new int[]{absCenterX, absCenterY});
            }

            if (!elementIds.isEmpty()) {
                groupInfos.add(new CrossingMinimizer.GroupInfo(
                        groupId, elementIds, centers));
            }
        }

        // 3. Collect inter-group connections
        List<CrossingMinimizer.InterGroupEdge> edges = new ArrayList<>();
        List<IDiagramModelConnection> allConnections = AssessmentCollector.collectAllConnections(diagramModel);
        for (IDiagramModelConnection conn : allConnections) {
            if (!(conn.getSource() instanceof IDiagramModelObject source)
                    || !(conn.getTarget() instanceof IDiagramModelObject target)) {
                continue;
            }
            String sourceGroupId = elementToGroupId.get(source.getId());
            String targetGroupId = elementToGroupId.get(target.getId());
            if (sourceGroupId != null && targetGroupId != null
                    && !sourceGroupId.equals(targetGroupId)) {
                edges.add(new CrossingMinimizer.InterGroupEdge(
                        source.getId(), sourceGroupId,
                        target.getId(), targetGroupId));
            }
        }

        if (edges.isEmpty()) {
            return null; // No inter-group connections — nothing to optimize
        }

        // 4. Run optimization
        CrossingMinimizer minimizer = new CrossingMinimizer();
        CrossingMinimizer.OptimizationResult optResult =
                minimizer.optimize(groupInfos, edges);

        // 5. Check if improvement was found
        if (optResult.crossingsAfter() >= optResult.crossingsBefore()) {
            return null; // No improvement — discard
        }

        // 6. Build position commands for reordered elements
        String arrangement = ("RIGHT".equalsIgnoreCase(direction)
                || "LEFT".equalsIgnoreCase(direction)) ? "row" : "column";
        int resolvedSpacing = DEFAULT_GROUP_SPACING;
        int resolvedPadding = DEFAULT_GROUP_PADDING;
        int startX = resolvedPadding;
        int startY = resolvedPadding + GROUP_LABEL_HEIGHT;

        List<Command> commands = new ArrayList<>();
        int positionCount = 0;

        for (CrossingMinimizer.GroupInfo groupInfo : groupInfos) {
            String groupId = groupInfo.groupId();
            IDiagramModelGroup group = groupMap.get(groupId);
            List<String> newOrder = optResult.newOrderByGroup().get(groupId);
            boolean reordered = optResult.reorderedGroups().contains(groupId);

            if (!reordered || newOrder == null) continue;

            // Reorder children list to match new order
            Map<String, IDiagramModelObject> childById = new LinkedHashMap<>();
            for (IDiagramModelObject child : group.getChildren()) {
                if (!(child instanceof IDiagramModelNote)) {
                    childById.put(child.getId(), child);
                }
            }

            List<IDiagramModelObject> orderedChildren = new ArrayList<>();
            for (String elemId : newOrder) {
                IDiagramModelObject child = childById.get(elemId);
                if (child != null) {
                    orderedChildren.add(child);
                }
            }

            // Compute new positions using the arrangement
            List<int[]> positions;
            if ("row".equals(arrangement)) {
                positions = computeRowLayout(orderedChildren, startX, startY,
                        resolvedSpacing, null, null, true);
            } else {
                positions = computeColumnLayout(orderedChildren, startX, startY,
                        resolvedSpacing, null, null, true);
            }

            // Build update commands for each child
            for (int i = 0; i < orderedChildren.size(); i++) {
                IDiagramModelObject child = orderedChildren.get(i);
                int[] pos = positions.get(i);
                commands.add(new UpdateViewObjectCommand(child,
                        pos[0], pos[1], pos[2], pos[3]));
                positionCount++;
            }

            // Auto-resize group to fit
            int[] groupDims = computeAutoResizeDimensions(
                    positions, resolvedPadding, GROUP_LABEL_HEIGHT);
            IBounds currentBounds = group.getBounds();
            commands.add(new UpdateViewObjectCommand(group,
                    currentBounds.getX(), currentBounds.getY(),
                    groupDims[0], groupDims[1]));
        }

        if (commands.isEmpty()) {
            return null;
        }

        double reductionPercent = optResult.crossingsBefore() > 0
                ? (1.0 - (double) optResult.crossingsAfter()
                        / optResult.crossingsBefore()) * 100.0
                : 0.0;

        logger.info("optimize-group-order: {} → {} crossings ({}% reduction)",
                optResult.crossingsBefore(), optResult.crossingsAfter(),
                Math.round(reductionPercent));

        String label = "Optimize group element order ("
                + optResult.reorderedGroups().size() + " groups, "
                + optResult.crossingsBefore() + " → "
                + optResult.crossingsAfter() + " crossings)";
        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand(label);
        commands.forEach(compound::add);
        return new OptimizeGroupOrderPassResult(compound, positionCount);
    }

    /**
     * Computes an auto-route pass for all connections on a view (Story 11-27).
     * Returns result with compound command and routed count,
     * or null if no connections exist on the view.
     *
     * <p>Reads the CURRENT EMF state for element positions and routes all
     * connections using the shared orthogonal routing pipeline (force mode).</p>
     */
    AutoRoutePassResult computeAutoRoutePass(
            String viewId, IArchimateDiagramModel diagramModel,
            IArchimateModel model) {

        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);
        List<IDiagramModelConnection> allConnections = AssessmentCollector.collectAllConnections(diagramModel);
        if (allConnections.isEmpty()) {
            return null;
        }

        // Route via shared helper (force=true for best quality during iteration)
        OrthogonalRoutingResult routeResult = buildOrthogonalRoutingCommands(
                diagramModel, allConnections, nodes, true);
        if (routeResult.commands.isEmpty()) {
            return null;
        }

        List<Command> commands = new ArrayList<>(routeResult.commands);

        // Switch to bendpoint mode if needed
        int currentRouterType = diagramModel.getConnectionRouterType();
        if (currentRouterType != IDiagramModel.CONNECTION_ROUTER_BENDPOINT) {
            commands.add(new UpdateViewCommand(diagramModel,
                    null, null, false, null, null,
                    IDiagramModel.CONNECTION_ROUTER_BENDPOINT));
        }

        String label = "Auto-route connections (re-route after optimize-group-order, "
                + routeResult.routedCount + " connections)";
        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand(label);
        commands.forEach(compound::add);
        return new AutoRoutePassResult(compound, routeResult.routedCount,
                routeResult.labelsOptimized);
    }

    /**
     * Collects all layout nodes recursively, including nested children.
     * Top-level nodes have parentId=null; children have their parent's view object ID.
     */
    private List<LayoutNode> collectLayoutNodesRecursive(
            IArchimateDiagramModel diagramModel) {
        List<LayoutNode> nodes = new ArrayList<>();
        collectLayoutNodesFromContainer(diagramModel, null, nodes);
        return nodes;
    }

    private void collectLayoutNodesFromContainer(
            IDiagramModelContainer container, String parentId,
            List<LayoutNode> nodes) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelNote) {
                continue; // Notes are not laid out (Story 11-15)
            }
            IBounds bounds = child.getBounds();
            double w = bounds.getWidth();
            double h = bounds.getHeight();
            if (w <= 0 || h <= 0) {
                logger.warn("Skipping element '{}' (id={}) with zero/negative bounds",
                        child.getName(), child.getId());
                continue;
            }
            nodes.add(new LayoutNode(child.getId(),
                    bounds.getX(), bounds.getY(), w, h, parentId));

            // Recurse into containers (groups, elements with children)
            if (child instanceof IDiagramModelContainer nestedContainer) {
                collectLayoutNodesFromContainer(nestedContainer, child.getId(), nodes);
            }
        }
    }

    /**
     * Collects all edges from all connections on the view, mapping between
     * view object IDs of source and target endpoints.
     */
    private List<LayoutEdge> collectLayoutEdgesRecursive(
            IArchimateDiagramModel diagramModel, List<LayoutNode> nodes) {
        Set<String> nodeIds = new HashSet<>();
        for (LayoutNode node : nodes) {
            nodeIds.add(node.viewObjectId());
        }

        List<LayoutEdge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectEdgesFromContainer(diagramModel, nodeIds, seen, edges);
        return edges;
    }

    private void collectEdgesFromContainer(IDiagramModelContainer container,
            Set<String> nodeIds, Set<String> seen, List<LayoutEdge> edges) {
        for (IDiagramModelObject child : container.getChildren()) {
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                if (!seen.add(conn.getId())) continue;
                IConnectable target = conn.getTarget();
                if (target instanceof IDiagramModelObject targetObj
                        && nodeIds.contains(child.getId())
                        && nodeIds.contains(targetObj.getId())) {
                    edges.add(new LayoutEdge(child.getId(), targetObj.getId(),
                            conn.getId()));
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                collectEdgesFromContainer(nested, nodeIds, seen, edges);
            }
        }
    }

    // ---- Auto-connect view (Story 9-6) ----

    @Override
    public MutationResult<AutoConnectResultDto> autoConnectView(
            String sessionId, String viewId,
            List<String> elementIds, List<String> relationshipTypes) {
        logger.info("Auto-connect view: viewId={}, elementIds={}, relationshipTypes={}",
                viewId, elementIds != null ? elementIds.size() : "all",
                relationshipTypes != null ? relationshipTypes.size() : "all");
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1. Validate view
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 2. Validate relationshipTypes if provided
            Set<String> typeFilter = null;
            if (relationshipTypes != null && !relationshipTypes.isEmpty()) {
                typeFilter = new HashSet<>();
                for (String type : relationshipTypes) {
                    resolveRelationshipType(type); // throws if invalid
                    typeFilter.add(type);
                }
            }

            // 3. Collect all ArchiMate view objects: elementId → viewObject
            Map<String, IDiagramModelArchimateObject> elementToViewObject =
                    new LinkedHashMap<>();
            collectViewObjectMap(diagramModel, elementToViewObject);
            // Re-key by element ID (collectViewObjectMap keys by view object ID)
            Map<String, IDiagramModelArchimateObject> elementIdMap = new LinkedHashMap<>();
            for (IDiagramModelArchimateObject vo : elementToViewObject.values()) {
                if (vo.getArchimateElement() != null) {
                    elementIdMap.put(vo.getArchimateElement().getId(), vo);
                }
            }

            // 4. Validate elementIds filter if provided
            Set<String> elementFilter = null;
            if (elementIds != null && !elementIds.isEmpty()) {
                elementFilter = new HashSet<>();
                for (String elemId : elementIds) {
                    if (!elementIdMap.containsKey(elemId)) {
                        throw new ModelAccessException(
                                "Element not found on view: " + elemId,
                                ErrorCode.ELEMENT_NOT_FOUND,
                                null,
                                "Use get-view-contents to find elements on this view",
                                null);
                    }
                    elementFilter.add(elemId);
                }
            }

            // 5. Collect existing visual connection relationship IDs
            Set<String> existingRelationshipIds = new HashSet<>();
            List<IDiagramModelConnection> allConnections =
                    AssessmentCollector.collectAllConnections(diagramModel);
            for (IDiagramModelConnection conn : allConnections) {
                if (conn instanceof IDiagramModelArchimateConnection archConn
                        && archConn.getArchimateRelationship() != null) {
                    existingRelationshipIds.add(
                            archConn.getArchimateRelationship().getId());
                }
            }

            // 6. Find eligible relationships and build commands
            Set<String> processedRelationships = new HashSet<>();
            List<Command> commands = new ArrayList<>();
            List<String> connectedRelationshipIds = new ArrayList<>();
            int skippedCount = 0;

            Set<String> elementsToScan = (elementFilter != null)
                    ? elementFilter : elementIdMap.keySet();

            for (String elemId : elementsToScan) {
                IDiagramModelArchimateObject viewObject = elementIdMap.get(elemId);
                if (viewObject == null || viewObject.getArchimateElement() == null) {
                    continue;
                }
                IArchimateElement element = viewObject.getArchimateElement();

                // Scan source relationships
                for (IArchimateRelationship rel : element.getSourceRelationships()) {
                    String relId = rel.getId();
                    if (processedRelationships.contains(relId)) continue;
                    processedRelationships.add(relId);

                    // Check type filter
                    if (typeFilter != null
                            && !typeFilter.contains(rel.eClass().getName())) {
                        continue;
                    }

                    // Check target is on view
                    IArchimateElement targetElement =
                            (IArchimateElement) rel.getTarget();
                    IDiagramModelArchimateObject targetViewObj =
                            elementIdMap.get(targetElement.getId());
                    if (targetViewObj == null) continue;

                    // Check elementIds filter — both ends must involve a filtered element
                    if (elementFilter != null
                            && !elementFilter.contains(elemId)
                            && !elementFilter.contains(targetElement.getId())) {
                        continue;
                    }

                    // Check if already connected
                    if (existingRelationshipIds.contains(relId)) {
                        skippedCount++;
                        continue;
                    }

                    // Create connection
                    IDiagramModelArchimateConnection conn =
                            IArchimateFactory.eINSTANCE
                                    .createDiagramModelArchimateConnection();
                    conn.setArchimateRelationship(rel);
                    commands.add(new AddConnectionToViewCommand(
                            conn, viewObject, targetViewObj));
                    connectedRelationshipIds.add(relId);
                }

                // Scan target relationships
                for (IArchimateRelationship rel : element.getTargetRelationships()) {
                    String relId = rel.getId();
                    if (processedRelationships.contains(relId)) continue;
                    processedRelationships.add(relId);

                    // Check type filter
                    if (typeFilter != null
                            && !typeFilter.contains(rel.eClass().getName())) {
                        continue;
                    }

                    // Check source is on view
                    IArchimateElement sourceElement =
                            (IArchimateElement) rel.getSource();
                    IDiagramModelArchimateObject sourceViewObj =
                            elementIdMap.get(sourceElement.getId());
                    if (sourceViewObj == null) continue;

                    // Check elementIds filter
                    if (elementFilter != null
                            && !elementFilter.contains(elemId)
                            && !elementFilter.contains(sourceElement.getId())) {
                        continue;
                    }

                    // Check if already connected
                    if (existingRelationshipIds.contains(relId)) {
                        skippedCount++;
                        continue;
                    }

                    // Create connection
                    IDiagramModelArchimateConnection conn =
                            IArchimateFactory.eINSTANCE
                                    .createDiagramModelArchimateConnection();
                    conn.setArchimateRelationship(rel);
                    commands.add(new AddConnectionToViewCommand(
                            conn, sourceViewObj, viewObject));
                    connectedRelationshipIds.add(relId);
                }
            }

            // 7. Handle empty result
            if (commands.isEmpty()) {
                AutoConnectResultDto dto = new AutoConnectResultDto(
                        viewId, 0, skippedCount, List.of());
                return new MutationResult<>(dto, null);
            }

            // 8. Validate operation count cap
            if (commands.size() > MAX_LAYOUT_OPERATIONS) {
                throw new ModelAccessException(
                        "Auto-connect operation count (" + commands.size()
                                + ") exceeds maximum (" + MAX_LAYOUT_OPERATIONS + ")",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 9. Build compound command
            String label = "Auto-connect view (" + commands.size()
                    + " connections)";
            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            AutoConnectResultDto dto = new AutoConnectResultDto(
                    viewId, commands.size(), skippedCount,
                    connectedRelationshipIds);

            // 10. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("connectionsCreated", commands.size());
                proposedChanges.put("connectionsSkipped", skippedCount);
                ProposalContext ctx = storeAsProposal(sessionId,
                        "auto-connect-view", compound, dto, label,
                        null, proposedChanges,
                        "Auto-connect computed and ready for application.");
                return new MutationResult<>(dto, null, ctx);
            }

            // 11. Dispatch or queue
            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(dto, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException
                | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error auto-connecting view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Builds transitive containment pairs from assessment nodes.
     * Used for excluding ancestor groups from obstacle lists.
     */
    private Set<String> buildTransitiveContainmentPairs(List<AssessmentNode> nodes) {
        Map<String, AssessmentNode> nodeMap = new HashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }
        Set<String> pairs = new HashSet<>();
        for (AssessmentNode node : nodes) {
            if (node.parentId() != null) {
                String descendantId = node.id();
                AssessmentNode current = nodeMap.get(node.parentId());
                while (current != null) {
                    pairs.add(current.id() + ":" + descendantId);
                    if (current.parentId() == null) break;
                    current = nodeMap.get(current.parentId());
                }
            }
        }
        return pairs;
    }

    /**
     * Gets all ancestor IDs for a node by walking the parentId chain.
     * Used for excluding ancestor groups from routing obstacles.
     */
    private Set<String> getAncestorIds(String nodeId,
            Map<String, AssessmentNode> nodeMap,
            List<AssessmentNode> nodes) {
        Set<String> ancestors = new HashSet<>();
        AssessmentNode node = nodeMap.get(nodeId);
        while (node != null && node.parentId() != null) {
            ancestors.add(node.parentId());
            node = nodeMap.get(node.parentId());
        }
        return ancestors;
    }

    /**
     * Gets all direct visual child IDs nested inside a parent element (Story 10-25).
     * Used for excluding child elements from routing obstacles when the parent
     * is a source/target — connections exiting a parent must not be blocked by its children.
     */
    private Set<String> getChildIds(String parentNodeId, List<AssessmentNode> nodes) {
        Set<String> children = new HashSet<>();
        for (AssessmentNode node : nodes) {
            if (parentNodeId.equals(node.parentId())) {
                children.add(node.id());
            }
        }
        return children;
    }

    // ---- Layout within group (Story 9-9) ----

    /** Default spacing between elements in pixels. */
    private static final int DEFAULT_GROUP_SPACING = 20;
    /** Default padding from group edges in pixels. */
    private static final int DEFAULT_GROUP_PADDING = 10;
    /** Approximate height of the group label bar in Archi's rendering. */
    private static final int GROUP_LABEL_HEIGHT = 24;
    /** Average character width in pixels for Archi's ~9pt sans-serif font. */
    static final int AVG_CHAR_WIDTH = 7;
    /** Horizontal padding for element icon space + text margins. */
    static final int HORIZONTAL_PADDING = 30;
    /** Minimum auto-computed width to prevent degenerate sizing. */
    static final int MIN_AUTO_WIDTH = 60;
    /** Default width for elements with null/empty names (Archi's default). */
    static final int DEFAULT_ELEMENT_WIDTH = 120;

    /** Maximum recursion depth for recursive auto-resize (Story 11-18). */
    private static final int MAX_RECURSIVE_RESIZE_DEPTH = 10;

    @Override
    public MutationResult<LayoutWithinGroupResultDto> layoutWithinGroup(
            String sessionId, String viewId, String groupViewObjectId,
            String arrangement, Integer spacing, Integer padding,
            Integer elementWidth, Integer elementHeight, boolean autoResize,
            boolean autoWidth, Integer columns, boolean recursive) {
        logger.info("Layout within group: viewId={}, groupViewObjectId={}, arrangement={}, columns={}, recursive={}",
                viewId, groupViewObjectId, arrangement, columns, recursive);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1. Validate view exists
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 2. Validate group exists and is a group
            EObject groupObj = ArchimateModelUtils.getObjectByID(model, groupViewObjectId);
            if (!(groupObj instanceof IDiagramModelGroup group)) {
                throw new ModelAccessException(
                        "Group not found or not a group: " + groupViewObjectId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Use get-view-contents to find valid group view object IDs in the 'groups' list.",
                        null);
            }

            // 2b. Verify group belongs to the specified view
            EObject container = group.eContainer();
            while (container != null && !(container instanceof IArchimateDiagramModel)) {
                container = container.eContainer();
            }
            if (container == null || !viewId.equals(((IArchimateDiagramModel) container).getId())) {
                throw new ModelAccessException(
                        "Group " + groupViewObjectId + " does not belong to view " + viewId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Ensure the groupViewObjectId is from the specified view's groups list.",
                        null);
            }

            // 3. Validate arrangement
            if (arrangement == null || arrangement.isBlank()) {
                throw new ModelAccessException(
                        "Parameter 'arrangement' is required. Valid values: row, column, grid.",
                        ErrorCode.INVALID_PARAMETER);
            }
            String normalizedArrangement = arrangement.toLowerCase().trim();
            if (!"row".equals(normalizedArrangement)
                    && !"column".equals(normalizedArrangement)
                    && !"grid".equals(normalizedArrangement)) {
                throw new ModelAccessException(
                        "Invalid arrangement: '" + arrangement
                                + "'. Valid values: row, column, grid.",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 4. Validate and resolve defaults
            if (spacing != null && spacing < 0) {
                throw new ModelAccessException(
                        "spacing must be non-negative", ErrorCode.INVALID_PARAMETER);
            }
            if (padding != null && padding < 0) {
                throw new ModelAccessException(
                        "padding must be non-negative", ErrorCode.INVALID_PARAMETER);
            }
            if (columns != null && columns < 1) {
                throw new ModelAccessException(
                        "columns must be positive (>= 1)", ErrorCode.INVALID_PARAMETER);
            }
            int resolvedSpacing = (spacing != null) ? spacing : DEFAULT_GROUP_SPACING;
            int resolvedPadding = (padding != null) ? padding : DEFAULT_GROUP_PADDING;
            logger.debug("Layout params: spacing={}, padding={}, elementWidth={}, "
                    + "elementHeight={}, autoResize={}, autoWidth={}, columns={}, recursive={}",
                    resolvedSpacing, resolvedPadding, elementWidth, elementHeight,
                    autoResize, autoWidth, columns, recursive);

            // 5. Collect direct children (skip notes — Story 11-15)
            List<IDiagramModelObject> children = new ArrayList<>();
            for (IDiagramModelObject child : group.getChildren()) {
                if (child instanceof IDiagramModelNote) {
                    continue; // Notes are not laid out
                }
                children.add(child);
            }

            if (children.isEmpty()) {
                throw new ModelAccessException(
                        "Group has no children to layout",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 6. Optionally resize children first
            if (elementWidth != null && elementWidth <= 0) {
                throw new ModelAccessException(
                        "elementWidth must be positive", ErrorCode.INVALID_PARAMETER);
            }
            if (elementHeight != null && elementHeight <= 0) {
                throw new ModelAccessException(
                        "elementHeight must be positive", ErrorCode.INVALID_PARAMETER);
            }

            // 7. Compute positions based on arrangement
            // Coordinates are RELATIVE to the group's origin (0,0 at group top-left)
            // elementWidth takes precedence over autoWidth (explicit override wins)
            boolean effectiveAutoWidth = autoWidth && (elementWidth == null);
            int startX = resolvedPadding;
            int startY = resolvedPadding + GROUP_LABEL_HEIGHT;
            List<int[]> positions = new ArrayList<>(); // [x, y, w, h] per child
            Integer columnsUsed = null; // only set for grid arrangement

            switch (normalizedArrangement) {
            case "row":
                positions = computeRowLayout(children, startX, startY,
                        resolvedSpacing, elementWidth, elementHeight,
                        effectiveAutoWidth);
                break;
            case "column":
                positions = computeColumnLayout(children, startX, startY,
                        resolvedSpacing, elementWidth, elementHeight,
                        effectiveAutoWidth);
                break;
            case "grid":
                IBounds groupBounds = group.getBounds();
                int groupWidth = groupBounds.getWidth();
                GridLayoutResult gridResult = computeGridLayout(children, startX, startY,
                        resolvedSpacing, resolvedPadding, groupWidth,
                        elementWidth, elementHeight, effectiveAutoWidth, columns);
                positions = gridResult.positions();
                columnsUsed = gridResult.columnsUsed();
                break;
            }

            // 8. Build commands
            List<Command> commands = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                IDiagramModelObject child = children.get(i);
                int[] pos = positions.get(i);
                commands.add(new UpdateViewObjectCommand(child,
                        pos[0], pos[1], pos[2], pos[3]));
            }

            // 10. Auto-resize group if requested, detect overflow otherwise
            Integer newGroupWidth = null;
            Integer newGroupHeight = null;
            boolean overflow = false;
            int ancestorsResized = 0;
            if (autoResize) {
                int[] groupDims = computeAutoResizeDimensions(
                        positions, resolvedPadding, GROUP_LABEL_HEIGHT);
                newGroupWidth = groupDims[0];
                newGroupHeight = groupDims[1];
                IBounds currentBounds = group.getBounds();
                commands.add(new UpdateViewObjectCommand(group,
                        currentBounds.getX(), currentBounds.getY(),
                        newGroupWidth, newGroupHeight));

                // 10a. Recursive auto-resize ancestor groups (Story 11-18)
                if (recursive) {
                    ancestorsResized = resizeAncestorGroups(group, commands, resolvedPadding);
                }
            } else {
                // Check if children overflow the current group bounds
                IBounds currentBounds = group.getBounds();
                int[] requiredDims = computeAutoResizeDimensions(
                        positions, resolvedPadding, GROUP_LABEL_HEIGHT);
                if (requiredDims[0] > currentBounds.getWidth()
                        || requiredDims[1] > currentBounds.getHeight()) {
                    overflow = true;
                    logger.debug("Children overflow group bounds: required={}x{}, actual={}x{}",
                            requiredDims[0], requiredDims[1],
                            currentBounds.getWidth(), currentBounds.getHeight());
                }
            }

            // 11. Build compound command
            String label = "Layout within group ("
                    + normalizedArrangement + ", "
                    + children.size() + " elements"
                    + (autoResize ? ", auto-resized" : "")
                    + (ancestorsResized > 0 ? ", " + ancestorsResized + " ancestors resized" : "")
                    + ")";

            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            LayoutWithinGroupResultDto dto = new LayoutWithinGroupResultDto(
                    viewId, groupViewObjectId, normalizedArrangement,
                    children.size(), autoResize, newGroupWidth, newGroupHeight,
                    overflow, effectiveAutoWidth, columnsUsed, ancestorsResized);

            // 12. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("arrangement", normalizedArrangement);
                proposedChanges.put("elementsRepositioned", children.size());
                proposedChanges.put("groupResized", autoResize);
                if (newGroupWidth != null) proposedChanges.put("newGroupWidth", newGroupWidth);
                if (newGroupHeight != null) proposedChanges.put("newGroupHeight", newGroupHeight);
                ProposalContext ctx = storeAsProposal(sessionId,
                        "layout-within-group", compound, dto, label,
                        null, proposedChanges,
                        "Group layout computed and ready for application.");
                return new MutationResult<>(dto, null, ctx);
            }

            // 13. Dispatch or queue
            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(dto, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException
                | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error computing/applying layout within group '"
                    + (groupViewObjectId != null ? groupViewObjectId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- optimize-group-order (Story 11-25) ----

    @Override
    public MutationResult<OptimizeGroupOrderResultDto> optimizeGroupOrder(
            String sessionId, String viewId, String arrangement,
            Integer spacing, Integer padding, Integer elementWidth,
            Integer elementHeight, boolean autoWidth, Integer columns) {
        logger.info("Optimize group order: viewId={}, arrangement={}", viewId, arrangement);
        IArchimateModel model = requireAndCaptureModel();

        try {
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 1. Validate arrangement
            if (arrangement == null || arrangement.isBlank()) {
                throw new ModelAccessException(
                        "arrangement is required (row, column, or grid)",
                        ErrorCode.INVALID_PARAMETER);
            }
            String normalizedArrangement = arrangement.trim().toLowerCase();
            if (!normalizedArrangement.equals("row")
                    && !normalizedArrangement.equals("column")
                    && !normalizedArrangement.equals("grid")) {
                throw new ModelAccessException(
                        "Invalid arrangement '" + arrangement
                        + "'. Must be 'row', 'column', or 'grid'.",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 2. Validate optional params
            if (spacing != null && spacing < 0) {
                throw new ModelAccessException(
                        "spacing must be non-negative", ErrorCode.INVALID_PARAMETER);
            }
            if (padding != null && padding < 0) {
                throw new ModelAccessException(
                        "padding must be non-negative", ErrorCode.INVALID_PARAMETER);
            }
            if (columns != null && columns < 1) {
                throw new ModelAccessException(
                        "columns must be positive (>= 1)", ErrorCode.INVALID_PARAMETER);
            }
            int resolvedSpacing = (spacing != null) ? spacing : DEFAULT_GROUP_SPACING;
            int resolvedPadding = (padding != null) ? padding : DEFAULT_GROUP_PADDING;

            // 3. Collect top-level groups and their children
            List<IDiagramModelGroup> topLevelGroups = new ArrayList<>();
            for (IDiagramModelObject child : diagramModel.getChildren()) {
                if (child instanceof IDiagramModelGroup group) {
                    topLevelGroups.add(group);
                }
            }

            if (topLevelGroups.isEmpty()) {
                throw new ModelAccessException(
                        "View has no groups — optimize-group-order requires groups "
                        + "with inter-group connections.",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 4. Build group info with element centers (relative-to-group positions)
            // Map groupId → group object for later reference
            Map<String, IDiagramModelGroup> groupMap = new LinkedHashMap<>();
            // Map elementViewObjectId → groupId for connection mapping
            Map<String, String> elementToGroupId = new HashMap<>();
            List<CrossingMinimizer.GroupInfo> groupInfos = new ArrayList<>();

            for (IDiagramModelGroup group : topLevelGroups) {
                String groupId = group.getId();
                groupMap.put(groupId, group);

                List<String> elementIds = new ArrayList<>();
                List<int[]> centers = new ArrayList<>();

                for (IDiagramModelObject child : group.getChildren()) {
                    if (child instanceof IDiagramModelNote) {
                        continue; // Skip notes
                    }
                    String childId = child.getId();
                    elementIds.add(childId);
                    elementToGroupId.put(childId, groupId);

                    // Compute center in absolute coordinates for crossing calculation
                    IBounds bounds = child.getBounds();
                    IBounds groupBounds = group.getBounds();
                    int absCenterX = groupBounds.getX() + bounds.getX() + bounds.getWidth() / 2;
                    int absCenterY = groupBounds.getY() + bounds.getY() + bounds.getHeight() / 2;
                    centers.add(new int[]{absCenterX, absCenterY});
                }

                if (!elementIds.isEmpty()) {
                    groupInfos.add(new CrossingMinimizer.GroupInfo(
                            groupId, elementIds, centers));
                }
            }

            // 5. Collect inter-group connections
            List<CrossingMinimizer.InterGroupEdge> edges = new ArrayList<>();
            List<IDiagramModelConnection> allConnections = AssessmentCollector.collectAllConnections(diagramModel);
            for (IDiagramModelConnection conn : allConnections) {
                if (!(conn.getSource() instanceof IDiagramModelObject source)
                        || !(conn.getTarget() instanceof IDiagramModelObject target)) {
                    continue;
                }

                String sourceGroupId = elementToGroupId.get(source.getId());
                String targetGroupId = elementToGroupId.get(target.getId());

                // Only inter-group connections between different top-level groups
                if (sourceGroupId != null && targetGroupId != null
                        && !sourceGroupId.equals(targetGroupId)) {
                    edges.add(new CrossingMinimizer.InterGroupEdge(
                            source.getId(), sourceGroupId,
                            target.getId(), targetGroupId));
                }
            }

            if (edges.isEmpty()) {
                throw new ModelAccessException(
                        "No inter-group connections found — optimize-group-order "
                        + "requires connections between elements in different groups.",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 6. Run optimization
            CrossingMinimizer minimizer = new CrossingMinimizer();
            CrossingMinimizer.OptimizationResult optResult =
                    minimizer.optimize(groupInfos, edges);

            // 7. Re-layout each reordered group and build commands
            List<Command> commands = new ArrayList<>();
            boolean effectiveAutoWidth = autoWidth && (elementWidth == null);
            int startX = resolvedPadding;
            int startY = resolvedPadding + GROUP_LABEL_HEIGHT;

            List<OptimizeGroupOrderResultDto.GroupDetail> groupDetails = new ArrayList<>();

            for (CrossingMinimizer.GroupInfo groupInfo : groupInfos) {
                String groupId = groupInfo.groupId();
                IDiagramModelGroup group = groupMap.get(groupId);
                List<String> newOrder = optResult.newOrderByGroup().get(groupId);
                boolean reordered = optResult.reorderedGroups().contains(groupId);

                groupDetails.add(new OptimizeGroupOrderResultDto.GroupDetail(
                        groupId, group.getName(),
                        groupInfo.elementIds().size(), reordered));

                if (!reordered || newOrder == null) continue;

                // Reorder children list to match new order
                Map<String, IDiagramModelObject> childById = new LinkedHashMap<>();
                for (IDiagramModelObject child : group.getChildren()) {
                    if (!(child instanceof IDiagramModelNote)) {
                        childById.put(child.getId(), child);
                    }
                }

                List<IDiagramModelObject> orderedChildren = new ArrayList<>();
                for (String elemId : newOrder) {
                    IDiagramModelObject child = childById.get(elemId);
                    if (child != null) {
                        orderedChildren.add(child);
                    }
                }

                // Compute new positions using the arrangement
                List<int[]> positions;
                switch (normalizedArrangement) {
                case "row":
                    positions = computeRowLayout(orderedChildren, startX, startY,
                            resolvedSpacing, elementWidth, elementHeight,
                            effectiveAutoWidth);
                    break;
                case "column":
                    positions = computeColumnLayout(orderedChildren, startX, startY,
                            resolvedSpacing, elementWidth, elementHeight,
                            effectiveAutoWidth);
                    break;
                case "grid":
                    IBounds groupBounds = group.getBounds();
                    int groupWidth = groupBounds.getWidth();
                    GridLayoutResult gridResult = computeGridLayout(
                            orderedChildren, startX, startY,
                            resolvedSpacing, resolvedPadding, groupWidth,
                            elementWidth, elementHeight, effectiveAutoWidth, columns);
                    positions = gridResult.positions();
                    break;
                default:
                    positions = computeRowLayout(orderedChildren, startX, startY,
                            resolvedSpacing, elementWidth, elementHeight,
                            effectiveAutoWidth);
                }

                // Build update commands for each child
                for (int i = 0; i < orderedChildren.size(); i++) {
                    IDiagramModelObject child = orderedChildren.get(i);
                    int[] pos = positions.get(i);
                    commands.add(new UpdateViewObjectCommand(child,
                            pos[0], pos[1], pos[2], pos[3]));
                }

                // Auto-resize group to fit
                int[] groupDims = computeAutoResizeDimensions(
                        positions, resolvedPadding, GROUP_LABEL_HEIGHT);
                IBounds currentBounds = group.getBounds();
                commands.add(new UpdateViewObjectCommand(group,
                        currentBounds.getX(), currentBounds.getY(),
                        groupDims[0], groupDims[1]));
            }

            // 8. Build compound command
            double reductionPercent = optResult.crossingsBefore() > 0
                    ? (1.0 - (double) optResult.crossingsAfter()
                            / optResult.crossingsBefore()) * 100.0
                    : 0.0;

            String label = "Optimize group element order ("
                    + optResult.reorderedGroups().size() + " groups, "
                    + optResult.crossingsBefore() + " → "
                    + optResult.crossingsAfter() + " crossings)";

            OptimizeGroupOrderResultDto dto = new OptimizeGroupOrderResultDto(
                    viewId, optResult.crossingsBefore(), optResult.crossingsAfter(),
                    Math.round(reductionPercent * 100.0) / 100.0,
                    optResult.reorderedGroups().size(),
                    optResult.elementMoves(), groupDetails);

            if (commands.isEmpty()) {
                // No reordering needed — return result without executing
                return new MutationResult<>(dto, null);
            }

            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            // 9. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("crossingsBefore", optResult.crossingsBefore());
                proposedChanges.put("crossingsAfter", optResult.crossingsAfter());
                proposedChanges.put("reductionPercent", reductionPercent);
                proposedChanges.put("groupsOptimized", optResult.reorderedGroups().size());
                ProposalContext ctx = storeAsProposal(sessionId,
                        "optimize-group-order", compound, dto, label,
                        null, proposedChanges,
                        "Group element order optimized and ready for application.");
                return new MutationResult<>(dto, null, ctx);
            }

            // 10. Dispatch or queue
            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(dto, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException
                | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error optimizing group element order for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Gets the display name for a view object (element name for ArchiMate objects,
     * direct name for groups/notes).
     */
    private String getDisplayName(IDiagramModelObject child) {
        if (child instanceof IDiagramModelArchimateObject archObj) {
            IArchimateElement element = archObj.getArchimateElement();
            return (element != null) ? element.getName() : null;
        }
        return child.getName();
    }

    /**
     * Computes auto-width for a single element based on its label text.
     * Returns DEFAULT_ELEMENT_WIDTH for null/empty names, MIN_AUTO_WIDTH floor applied.
     */
    int computeAutoWidth(IDiagramModelObject child) {
        String name = getDisplayName(child);
        if (name == null || name.isEmpty()) {
            return DEFAULT_ELEMENT_WIDTH;
        }
        int estimatedWidth = (name.length() * AVG_CHAR_WIDTH) + HORIZONTAL_PADDING;
        return Math.max(MIN_AUTO_WIDTH, estimatedWidth);
    }

    /**
     * Computes row arrangement positions (left-to-right).
     */
    private List<int[]> computeRowLayout(List<IDiagramModelObject> children,
            int startX, int startY, int spacing,
            Integer elementWidth, Integer elementHeight,
            boolean autoWidth) {
        List<int[]> positions = new ArrayList<>();
        int currentX = startX;
        for (IDiagramModelObject child : children) {
            IBounds bounds = child.getBounds();
            int w = (elementWidth != null) ? elementWidth
                    : autoWidth ? computeAutoWidth(child)
                    : bounds.getWidth();
            int h = (elementHeight != null) ? elementHeight : bounds.getHeight();
            positions.add(new int[]{currentX, startY, w, h});
            currentX += w + spacing;
        }
        return positions;
    }

    /**
     * Computes column arrangement positions (top-to-bottom).
     */
    private List<int[]> computeColumnLayout(List<IDiagramModelObject> children,
            int startX, int startY, int spacing,
            Integer elementWidth, Integer elementHeight,
            boolean autoWidth) {
        List<int[]> positions = new ArrayList<>();
        int currentY = startY;
        for (IDiagramModelObject child : children) {
            IBounds bounds = child.getBounds();
            int w = (elementWidth != null) ? elementWidth
                    : autoWidth ? computeAutoWidth(child)
                    : bounds.getWidth();
            int h = (elementHeight != null) ? elementHeight : bounds.getHeight();
            positions.add(new int[]{startX, currentY, w, h});
            currentY += h + spacing;
        }
        return positions;
    }

    /** Result of grid layout computation, including positions and the actual column count used. */
    private record GridLayoutResult(List<int[]> positions, int columnsUsed) {}

    /**
     * Computes grid arrangement positions (left-to-right, top-to-bottom).
     * If columns is non-null, uses the specified column count (capped at element count).
     * Otherwise auto-detects from available group width.
     */
    private GridLayoutResult computeGridLayout(List<IDiagramModelObject> children,
            int startX, int startY, int spacing, int padding, int groupWidth,
            Integer elementWidth, Integer elementHeight,
            boolean autoWidth, Integer columns) {
        List<int[]> positions = new ArrayList<>();

        // Determine effective element size (use max if widths vary)
        // For autoWidth grid: compute max auto-width across all elements for uniform columns
        int maxW = 0;
        int maxH = 0;
        for (IDiagramModelObject child : children) {
            IBounds bounds = child.getBounds();
            int w = (elementWidth != null) ? elementWidth
                    : autoWidth ? computeAutoWidth(child)
                    : bounds.getWidth();
            int h = (elementHeight != null) ? elementHeight : bounds.getHeight();
            maxW = Math.max(maxW, w);
            maxH = Math.max(maxH, h);
        }

        // Calculate column count: explicit columns param (capped at element count),
        // or auto-detect from available width (Story 11-18)
        int cols;
        if (columns != null) {
            cols = Math.min(columns, children.size());
        } else {
            int availableWidth = groupWidth - 2 * padding;
            cols = Math.max(1, (availableWidth + spacing) / (maxW + spacing));
        }

        int currentX = startX;
        int currentY = startY;
        int col = 0;

        for (IDiagramModelObject child : children) {
            // Grid uses uniform maxW for all elements (including autoWidth)
            IBounds bounds = child.getBounds();
            int h = (elementHeight != null) ? elementHeight : bounds.getHeight();
            positions.add(new int[]{currentX, currentY, maxW, h});

            col++;
            if (col >= cols) {
                col = 0;
                currentX = startX;
                currentY += maxH + spacing;
            } else {
                currentX += maxW + spacing;
            }
        }
        return new GridLayoutResult(positions, cols);
    }

    /**
     * Computes the auto-resize dimensions for a group based on child positions.
     */
    private int[] computeAutoResizeDimensions(List<int[]> positions,
            int padding, int labelHeight) {
        int maxRight = 0;
        int maxBottom = 0;
        for (int[] pos : positions) {
            maxRight = Math.max(maxRight, pos[0] + pos[2]);
            maxBottom = Math.max(maxBottom, pos[1] + pos[3]);
        }
        int newWidth = maxRight + padding;
        int newHeight = maxBottom + padding;
        return new int[]{newWidth, newHeight};
    }

    /**
     * Recursively resizes ancestor groups to fit their children, walking up the
     * parent chain from the specified group. Stops at view level or max depth.
     * Uses the specified padding for consistency with the target group's layout.
     * Returns the number of ancestors resized.
     */
    private int resizeAncestorGroups(IDiagramModelGroup startGroup,
            List<Command> commands, int padding) {
        int resized = 0;
        EObject current = startGroup.eContainer();
        int depth = 0;

        while (current instanceof IDiagramModelGroup parentGroup
                && depth < MAX_RECURSIVE_RESIZE_DEPTH) {
            // Compute required dimensions from all children of this parent
            List<int[]> childPositions = new ArrayList<>();
            for (IDiagramModelObject child : parentGroup.getChildren()) {
                IBounds b = child.getBounds();
                // Check if this child has a pending resize command
                int[] dims = findPendingDimensions(commands, child);
                int cx = b.getX();
                int cy = b.getY();
                int cw = (dims != null) ? dims[0] : b.getWidth();
                int ch = (dims != null) ? dims[1] : b.getHeight();
                childPositions.add(new int[]{cx, cy, cw, ch});
            }

            int[] parentDims = computeAutoResizeDimensions(
                    childPositions, padding, GROUP_LABEL_HEIGHT);
            IBounds parentBounds = parentGroup.getBounds();
            commands.add(new UpdateViewObjectCommand(parentGroup,
                    parentBounds.getX(), parentBounds.getY(),
                    parentDims[0], parentDims[1]));
            resized++;
            logger.debug("Recursive resize: ancestor group {} resized to {}x{}",
                    parentGroup.getId(), parentDims[0], parentDims[1]);

            current = parentGroup.eContainer();
            depth++;
        }
        return resized;
    }

    /**
     * Finds pending resize dimensions (width, height) for a view object in the
     * commands list. Returns null if no pending command exists.
     */
    private int[] findPendingDimensions(List<Command> commands, IDiagramModelObject obj) {
        // Walk backwards to find the latest command for this object
        for (int i = commands.size() - 1; i >= 0; i--) {
            Command cmd = commands.get(i);
            if (cmd instanceof UpdateViewObjectCommand updateCmd
                    && updateCmd.getDiagramObject() == obj) {
                return new int[]{updateCmd.getNewWidth(), updateCmd.getNewHeight()};
            }
        }
        return null;
    }

    // ---- Arrange Groups (Story 11-20) ----

    private static final int DEFAULT_ARRANGE_GROUPS_SPACING = 40;
    private static final int ARRANGE_GROUPS_ORIGIN = 20;
    private static final int ARRANGE_GROUPS_ESTIMATED_CANVAS_WIDTH = 1200;

    @Override
    public MutationResult<ArrangeGroupsResultDto> arrangeGroups(
            String sessionId, String viewId, String arrangement,
            Integer columns, Integer spacing, List<String> groupIds) {
        logger.info("Arrange groups: viewId={}, arrangement={}, columns={}, spacing={}, groupIds={}",
                viewId, arrangement, columns, spacing,
                groupIds != null ? groupIds.size() : "all");
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1. Validate view exists
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel view)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 2. Validate arrangement
            if (arrangement == null || arrangement.isBlank()) {
                throw new ModelAccessException(
                        "Parameter 'arrangement' is required. Valid values: grid, row, column.",
                        ErrorCode.INVALID_PARAMETER);
            }
            String normalizedArrangement = arrangement.toLowerCase().trim();
            if (!"grid".equals(normalizedArrangement)
                    && !"row".equals(normalizedArrangement)
                    && !"column".equals(normalizedArrangement)) {
                throw new ModelAccessException(
                        "Invalid arrangement: '" + arrangement
                                + "'. Valid values: grid, row, column.",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 3. Validate spacing
            if (spacing != null && spacing < 0) {
                throw new ModelAccessException(
                        "spacing must be non-negative", ErrorCode.INVALID_PARAMETER);
            }
            int resolvedSpacing = (spacing != null) ? spacing : DEFAULT_ARRANGE_GROUPS_SPACING;

            // 4. Validate columns
            if (columns != null && columns < 1) {
                throw new ModelAccessException(
                        "columns must be positive (>= 1)", ErrorCode.INVALID_PARAMETER);
            }

            // 5. Collect top-level groups from view
            List<IDiagramModelGroup> allTopLevelGroups = new ArrayList<>();
            for (IDiagramModelObject child : view.getChildren()) {
                if (child instanceof IDiagramModelGroup group) {
                    allTopLevelGroups.add(group);
                }
            }

            // 6. Filter by groupIds if provided
            List<IDiagramModelGroup> targetGroups;
            if (groupIds != null && !groupIds.isEmpty()) {
                targetGroups = new ArrayList<>();
                for (String gid : groupIds) {
                    boolean found = false;
                    for (IDiagramModelGroup g : allTopLevelGroups) {
                        if (g.getId().equals(gid)) {
                            targetGroups.add(g);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // Check if it exists but is not a top-level group
                        EObject obj = ArchimateModelUtils.getObjectByID(model, gid);
                        if (obj instanceof IDiagramModelGroup) {
                            throw new ModelAccessException(
                                    "Group " + gid + " is not a top-level group in view " + viewId
                                            + ". arrange-groups only positions top-level groups.",
                                    ErrorCode.INVALID_PARAMETER);
                        }
                        throw new ModelAccessException(
                                "Group not found in view: " + gid,
                                ErrorCode.VIEW_OBJECT_NOT_FOUND,
                                null,
                                "Use get-view-contents to find valid group IDs in the 'groups' list.",
                                null);
                    }
                }
            } else {
                targetGroups = allTopLevelGroups;
            }

            if (targetGroups.isEmpty()) {
                throw new ModelAccessException(
                        "No top-level groups found in view " + viewId,
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Add groups to the view first using add-group-to-view.",
                        null);
            }

            // 7. Compute positions based on arrangement
            int startX = ARRANGE_GROUPS_ORIGIN;
            int startY = ARRANGE_GROUPS_ORIGIN;
            List<int[]> positions; // [x, y] per group (preserve existing width/height)
            Integer columnsUsed = null;
            int layoutWidth = 0;
            int layoutHeight = 0;

            switch (normalizedArrangement) {
            case "row": {
                positions = new ArrayList<>();
                int curX = startX;
                int maxH = 0;
                for (IDiagramModelGroup g : targetGroups) {
                    IBounds b = g.getBounds();
                    positions.add(new int[]{curX, startY});
                    curX += b.getWidth() + resolvedSpacing;
                    maxH = Math.max(maxH, b.getHeight());
                }
                layoutWidth = curX - resolvedSpacing; // last spacing not needed
                layoutHeight = startY + maxH;
                break;
            }
            case "column": {
                positions = new ArrayList<>();
                int curY = startY;
                int maxW = 0;
                for (IDiagramModelGroup g : targetGroups) {
                    IBounds b = g.getBounds();
                    positions.add(new int[]{startX, curY});
                    curY += b.getHeight() + resolvedSpacing;
                    maxW = Math.max(maxW, b.getWidth());
                }
                layoutWidth = startX + maxW;
                layoutHeight = curY - resolvedSpacing;
                break;
            }
            case "grid": {
                positions = new ArrayList<>();
                // Auto-detect columns if not specified
                int cols;
                if (columns != null) {
                    cols = columns;
                } else {
                    // Find widest group
                    int maxGroupWidth = 0;
                    for (IDiagramModelGroup g : targetGroups) {
                        maxGroupWidth = Math.max(maxGroupWidth, g.getBounds().getWidth());
                    }
                    int estimatedCanvasWidth = ARRANGE_GROUPS_ESTIMATED_CANVAS_WIDTH;
                    cols = Math.max(1, (estimatedCanvasWidth + resolvedSpacing)
                            / (maxGroupWidth + resolvedSpacing));
                    // Don't use more columns than groups
                    cols = Math.min(cols, targetGroups.size());
                }
                columnsUsed = cols;

                // Compute per-row max heights for variable-sized groups
                int curX = startX;
                int curY = startY;
                int colIdx = 0;
                int rowMaxH = 0;
                for (int i = 0; i < targetGroups.size(); i++) {
                    IBounds b = targetGroups.get(i).getBounds();
                    positions.add(new int[]{curX, curY});
                    rowMaxH = Math.max(rowMaxH, b.getHeight());
                    layoutWidth = Math.max(layoutWidth, curX + b.getWidth());
                    colIdx++;
                    if (colIdx >= cols && i < targetGroups.size() - 1) {
                        // New row
                        curX = startX;
                        curY += rowMaxH + resolvedSpacing;
                        colIdx = 0;
                        rowMaxH = 0;
                    } else {
                        curX += b.getWidth() + resolvedSpacing;
                    }
                }
                layoutHeight = curY + rowMaxH;
                break;
            }
            default:
                throw new ModelAccessException(
                        "Unexpected arrangement: " + normalizedArrangement,
                        ErrorCode.INTERNAL_ERROR);
            }

            // 8. Build commands — reposition only, preserve width/height
            List<Command> commands = new ArrayList<>();
            for (int i = 0; i < targetGroups.size(); i++) {
                IDiagramModelGroup g = targetGroups.get(i);
                IBounds b = g.getBounds();
                int[] pos = positions.get(i);
                commands.add(new UpdateViewObjectCommand(g,
                        pos[0], pos[1], b.getWidth(), b.getHeight()));
            }

            // 9. Build compound command
            String label = "Arrange groups ("
                    + normalizedArrangement + ", "
                    + targetGroups.size() + " groups)";
            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                    viewId, targetGroups.size(), layoutWidth, layoutHeight,
                    columnsUsed, normalizedArrangement);

            // 10. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("arrangement", normalizedArrangement);
                proposedChanges.put("groupsPositioned", targetGroups.size());
                if (columnsUsed != null) proposedChanges.put("columnsUsed", columnsUsed);
                proposedChanges.put("layoutWidth", layoutWidth);
                proposedChanges.put("layoutHeight", layoutHeight);
                ProposalContext ctx = storeAsProposal(sessionId,
                        "arrange-groups", compound, dto, label,
                        null, proposedChanges,
                        "Group arrangement computed and ready for application.");
                return new MutationResult<>(dto, null, ctx);
            }

            // 11. Dispatch or queue
            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(dto, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException
                | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error arranging groups in view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Deletion methods (Story 8-4) ----

    @Override
    public MutationResult<DeleteResultDto> deleteElement(String sessionId, String elementId) {
        logger.info("Deleting element: elementId={}", elementId);
        requireAndCaptureModel();
        try {
            PreparedMutation<DeleteResultDto> prepared = prepareDeleteElement(elementId);

            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                DeleteResultDto dto = prepared.entity();
                String description = "Delete " + dto.type() + ": " + dto.name()
                        + " (cascade: " + dto.relationshipsRemoved() + " relationship"
                        + (dto.relationshipsRemoved() != 1 ? "s" : "") + ", "
                        + dto.viewReferencesRemoved() + " view reference"
                        + (dto.viewReferencesRemoved() != 1 ? "s" : "") + ")";
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("elementId", elementId);
                proposedChanges.put("relationshipsRemoved", dto.relationshipsRemoved());
                proposedChanges.put("viewReferencesRemoved", dto.viewReferencesRemoved());
                proposedChanges.put("viewConnectionsRemoved", dto.viewConnectionsRemoved());
                ProposalContext ctx = storeAsProposal(sessionId, "delete-element",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "Element ready for deletion with cascade.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Delete element: " + elementId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error deleting element '" + elementId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<DeleteResultDto> deleteRelationship(String sessionId,
            String relationshipId) {
        logger.info("Deleting relationship: relationshipId={}", relationshipId);
        requireAndCaptureModel();
        try {
            PreparedMutation<DeleteResultDto> prepared = prepareDeleteRelationship(relationshipId);

            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                DeleteResultDto dto = prepared.entity();
                String description = "Delete " + dto.type()
                        + " (cascade: " + dto.viewConnectionsRemoved() + " view connection"
                        + (dto.viewConnectionsRemoved() != 1 ? "s" : "") + ")";
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("relationshipId", relationshipId);
                proposedChanges.put("viewConnectionsRemoved", dto.viewConnectionsRemoved());
                ProposalContext ctx = storeAsProposal(sessionId, "delete-relationship",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "Relationship ready for deletion with cascade.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Delete relationship: " + relationshipId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error deleting relationship '" + relationshipId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<DeleteResultDto> deleteView(String sessionId, String viewId) {
        logger.info("Deleting view: viewId={}", viewId);
        requireAndCaptureModel();
        try {
            PreparedMutation<DeleteResultDto> prepared = prepareDeleteView(viewId);

            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                DeleteResultDto dto = prepared.entity();
                String description = "Delete view: " + dto.name();
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                ProposalContext ctx = storeAsProposal(sessionId, "delete-view",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "View ready for deletion.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Delete view: " + viewId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error deleting view '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<DeleteResultDto> deleteFolder(String sessionId, String folderId,
            boolean force) {
        logger.info("Deleting folder: folderId={}, force={}", folderId, force);
        requireAndCaptureModel();
        try {
            PreparedMutation<DeleteResultDto> prepared = prepareDeleteFolder(folderId, force);

            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                DeleteResultDto dto = prepared.entity();
                String description = "Delete folder: " + dto.name()
                        + (force ? " (force cascade)" : "");
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("folderId", folderId);
                proposedChanges.put("force", force);
                if (dto.elementsRemoved() != null) {
                    proposedChanges.put("elementsRemoved", dto.elementsRemoved());
                }
                if (dto.viewsRemoved() != null) {
                    proposedChanges.put("viewsRemoved", dto.viewsRemoved());
                }
                if (dto.foldersRemoved() != null) {
                    proposedChanges.put("foldersRemoved", dto.foldersRemoved());
                }
                ProposalContext ctx = storeAsProposal(sessionId, "delete-folder",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "Folder ready for deletion.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Delete folder: " + folderId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error deleting folder '" + folderId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Prepare methods for bulk support (Story 7-5) ----

    /**
     * Prepares a create-element mutation without dispatching.
     * Validates type, creates EMF object, configures properties, resolves folder, builds command.
     */
    private PreparedMutation<ElementDto> prepareCreateElement(String type, String name,
            String documentation, Map<String, String> properties, String folderId) {
        IArchimateModel model = requireAndCaptureModel();

        EClass eClass = resolveElementType(type);
        IArchimateElement element = (IArchimateElement) IArchimateFactory.eINSTANCE.create(eClass);
        element.setName(name);
        if (documentation != null && !documentation.isBlank()) {
            element.setDocumentation(documentation);
        }

        if (properties != null) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
                prop.setKey(entry.getKey());
                prop.setValue(entry.getValue());
                element.getProperties().add(prop);
            }
        }

        IFolder targetFolder = resolveTargetFolder(model, element, folderId);
        Command cmd = new CreateElementCommand(element, targetFolder);

        return new PreparedMutation<>(cmd, convertToElementDto(element), element.getId(), element);
    }

    /**
     * Prepares a create-relationship mutation by looking up source/target by ID.
     */
    private PreparedMutation<RelationshipDto> prepareCreateRelationship(
            String type, String sourceId, String targetId, String name) {
        IArchimateModel model = requireAndCaptureModel();

        EClass relClass = resolveRelationshipType(type);

        EObject sourceObj = ArchimateModelUtils.getObjectByID(model, sourceId);
        if (!(sourceObj instanceof IArchimateElement sourceElement)) {
            throw new ModelAccessException(
                    "Source element not found: " + sourceId,
                    ErrorCode.SOURCE_ELEMENT_NOT_FOUND);
        }

        EObject targetObj = ArchimateModelUtils.getObjectByID(model, targetId);
        if (!(targetObj instanceof IArchimateElement targetElement)) {
            throw new ModelAccessException(
                    "Target element not found: " + targetId,
                    ErrorCode.TARGET_ELEMENT_NOT_FOUND);
        }

        return prepareCreateRelationship(type, relClass, sourceElement, targetElement, name, model);
    }

    /**
     * Prepares a create-relationship mutation with direct element references.
     * Used by bulk executor when source/target are back-referenced
     * (not yet in the model).
     */
    private PreparedMutation<RelationshipDto> prepareCreateRelationshipDirect(
            String type, IArchimateElement sourceElement, IArchimateElement targetElement,
            String name) {
        IArchimateModel model = requireAndCaptureModel();
        EClass relClass = resolveRelationshipType(type);
        return prepareCreateRelationship(type, relClass, sourceElement, targetElement, name, model);
    }

    /**
     * Shared implementation for relationship preparation.
     */
    private PreparedMutation<RelationshipDto> prepareCreateRelationship(
            String type, EClass relClass, IArchimateElement sourceElement,
            IArchimateElement targetElement, String name, IArchimateModel model) {

        boolean valid = ArchimateModelUtils.isValidRelationship(
                sourceElement, targetElement, relClass);
        if (!valid) {
            EClass[] validTypes = ArchimateModelUtils.getValidRelationships(
                    sourceElement.eClass(), targetElement.eClass());
            String validNames = Arrays.stream(validTypes)
                    .map(EClass::getName)
                    .collect(Collectors.joining(", "));
            throw new ModelAccessException(
                    type + " is not valid between " + sourceElement.eClass().getName()
                            + " (source) and " + targetElement.eClass().getName() + " (target)",
                    ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                    "Valid relationship types: " + validNames,
                    "Try one of the valid types listed in details, or use "
                            + "AssociationRelationship which is valid between most elements",
                    "ArchiMate 3.2 specification, relationship rules");
        }

        IArchimateRelationship relationship =
                (IArchimateRelationship) IArchimateFactory.eINSTANCE.create(relClass);
        if (name != null && !name.isBlank()) {
            relationship.setName(name);
        }
        relationship.connect(sourceElement, targetElement);

        IFolder relationsFolder = model.getFolder(FolderType.RELATIONS);
        Command cmd = new CreateRelationshipCommand(relationship, relationsFolder);

        return new PreparedMutation<>(cmd, convertToRelationshipDto(relationship),
                relationship.getId(), relationship);
    }

    /**
     * Prepares a create-view mutation without dispatching.
     */
    private PreparedMutation<ViewDto> prepareCreateView(String name, String viewpoint,
            String folderId, String connectionRouterType) {
        IArchimateModel model = requireAndCaptureModel();

        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setName(name);
        if (viewpoint != null && !viewpoint.isBlank()) {
            view.setViewpoint(viewpoint);
        }
        Integer routerInt = resolveConnectionRouterType(connectionRouterType);
        if (routerInt != null) {
            view.setConnectionRouterType(routerInt);
        }

        IFolder targetFolder;
        if (folderId != null && !folderId.isBlank()) {
            targetFolder = FolderOperations.findFolderById(model, folderId);
            if (targetFolder == null) {
                throw new ModelAccessException(
                        "Folder not found: " + folderId,
                        ErrorCode.FOLDER_NOT_FOUND);
            }
        } else {
            targetFolder = model.getFolder(FolderType.DIAGRAMS);
        }

        Command cmd = new CreateViewCommand(view, targetFolder);

        ViewDto dto = buildViewDto(view, FolderOperations.buildFolderPath(targetFolder));

        return new PreparedMutation<>(cmd, dto, view.getId());
    }

    /**
     * Prepares an update-element mutation without dispatching.
     */
    private PreparedMutation<ElementDto> prepareUpdateElement(String id, String name,
            String documentation, Map<String, String> properties) {
        IArchimateModel model = requireAndCaptureModel();

        if (name == null && documentation == null && properties == null) {
            throw new ModelAccessException(
                    "No fields to update — provide at least one of: name, documentation, properties",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Specify name, documentation, or properties to update",
                    null);
        }

        EObject obj = ArchimateModelUtils.getObjectByID(model, id);
        if (!(obj instanceof IArchimateElement element)) {
            throw new ModelAccessException(
                    "Element not found: " + id,
                    ErrorCode.ELEMENT_NOT_FOUND,
                    null,
                    "Use search-elements to find the correct element ID",
                    null);
        }

        Command cmd = new UpdateElementCommand(element, name, documentation, properties);

        return new PreparedMutation<>(cmd, convertToElementDto(element), element.getId(), element);
    }

    /**
     * Prepares an update-view mutation without dispatching.
     */
    private PreparedMutation<ViewDto> prepareUpdateView(String id, String name,
            String viewpoint, String documentation, Map<String, String> properties,
            String connectionRouterType) {
        IArchimateModel model = requireAndCaptureModel();

        Integer routerTypeInt = resolveConnectionRouterType(connectionRouterType);

        if (name == null && viewpoint == null && documentation == null
                && properties == null && routerTypeInt == null) {
            throw new ModelAccessException(
                    "No fields to update — provide at least one of: name, viewpoint, "
                            + "documentation, properties, connectionRouterType",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Specify name, viewpoint, documentation, properties, "
                            + "or connectionRouterType to update",
                    null);
        }

        EObject obj = ArchimateModelUtils.getObjectByID(model, id);
        if (!(obj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + id,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find the correct view ID",
                    null);
        }

        // Viewpoint clear semantics: empty string = clear, non-empty = set, null = no change.
        // Cross-ref: empty string preserved by ViewHandler.handleUpdateView() and
        // ArchiModelAccessorImpl.prepareBulkOperation("update-view").
        boolean clearViewpoint = "".equals(viewpoint);
        String effectiveViewpoint = clearViewpoint ? null : viewpoint;

        Command cmd = new UpdateViewCommand(view, name, effectiveViewpoint,
                clearViewpoint, documentation, properties, routerTypeInt);

        return new PreparedMutation<>(cmd, buildViewDto(view), view.getId(), view);
    }

    /**
     * Builds a ViewDto from an IArchimateDiagramModel, resolving the folder path
     * from the view's container.
     */
    private ViewDto buildViewDto(IArchimateDiagramModel view) {
        String folderPath = null;
        if (view.eContainer() instanceof IFolder parentFolder) {
            folderPath = FolderOperations.buildFolderPath(parentFolder);
        }
        return buildViewDto(view, folderPath);
    }

    /**
     * Builds a ViewDto from an IArchimateDiagramModel with an explicit folder path.
     * Shared by {@link #buildViewDto(IArchimateDiagramModel)} and
     * {@link #collectViews(IFolder, String, String, List)} to avoid duplicating
     * viewpoint/documentation normalization and property extraction logic.
     */
    private ViewDto buildViewDto(IArchimateDiagramModel view, String folderPath) {
        String vp = view.getViewpoint();
        if (vp != null && vp.isEmpty()) {
            vp = null;
        }
        String doc = view.getDocumentation();
        if (doc != null && doc.isEmpty()) {
            doc = null;
        }
        Map<String, String> props = null;
        if (view.getProperties() != null && !view.getProperties().isEmpty()) {
            props = new LinkedHashMap<>();
            for (IProperty p : view.getProperties()) {
                props.put(p.getKey(), p.getValue());
            }
        }
        String routerType = mapConnectionRouterType(view.getConnectionRouterType());
        return new ViewDto(view.getId(), view.getName(), vp, routerType, folderPath, doc, props);
    }

    /**
     * Maps an MCP router type string to the Archi EMF int constant.
     * Returns null if the input is null (no change).
     */
    private Integer resolveConnectionRouterType(String routerType) {
        if (routerType == null) {
            return null;
        }
        return switch (routerType.toLowerCase()) {
            case "manhattan" -> IDiagramModel.CONNECTION_ROUTER_MANHATTAN;
            case "manual", "" -> IDiagramModel.CONNECTION_ROUTER_BENDPOINT;
            default -> throw new ModelAccessException(
                    "Invalid connectionRouterType: '" + routerType
                            + "'. Valid values: manual, manhattan",
                    ErrorCode.INVALID_PARAMETER);
        };
    }

    /**
     * Maps an Archi EMF router type int to an MCP string.
     * Returns null for the default (manual/bendpoint) to keep responses compact.
     */
    private String mapConnectionRouterType(int routerType) {
        if (routerType == IDiagramModel.CONNECTION_ROUTER_MANHATTAN) {
            return "manhattan";
        }
        if (routerType != IDiagramModel.CONNECTION_ROUTER_BENDPOINT) {
            logger.warn("Unknown connection router type value: {}. "
                    + "Treating as default (manual).", routerType);
        }
        return null;
    }

    // ---- View placement prepare methods (Story 7-7) ----

    /**
     * Prepares an add-to-view mutation: validates, creates EMF objects, builds command.
     */
    private PreparedMutation<AddToViewResultDto> prepareAddToView(
            String viewId, String elementId, Integer x, Integer y,
            Integer width, Integer height, boolean autoConnect,
            String parentViewObjectId) {
        return prepareAddToView(viewId, elementId, x, y, width, height,
                autoConnect, parentViewObjectId, null, null);
    }

    /**
     * Prepares an add-to-view mutation with optional pre-resolved batch parent container.
     * When batchParentContainer is non-null, it overrides parentViewObjectId lookup
     * (used for groups created earlier in the same bulk-mutate batch, Story 9-0g).
     */
    private PreparedMutation<AddToViewResultDto> prepareAddToView(
            String viewId, String elementId, Integer x, Integer y,
            Integer width, Integer height, boolean autoConnect,
            String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling) {
        IArchimateModel model = requireAndCaptureModel();

        // Validate x/y both-or-neither
        if ((x == null) != (y == null)) {
            throw new ModelAccessException(
                    "Both x and y must be specified together, or both omitted for auto-placement",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide both x and y coordinates, or omit both for auto-placement",
                    null);
        }

        // Find view
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        // Find element
        EObject elemObj = ArchimateModelUtils.getObjectByID(model, elementId);
        if (!(elemObj instanceof IArchimateElement element)) {
            throw new ModelAccessException(
                    "Element not found: " + elementId,
                    ErrorCode.ELEMENT_NOT_FOUND,
                    null,
                    "Use search-elements to find valid element IDs",
                    null);
        }

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Resolve dimensions
        int resolvedWidth = (width != null) ? width : DEFAULT_VIEW_OBJECT_WIDTH;
        int resolvedHeight = (height != null) ? height : DEFAULT_VIEW_OBJECT_HEIGHT;

        // Resolve position
        int resolvedX;
        int resolvedY;
        if (x != null) {
            resolvedX = x;
            resolvedY = y;
        } else {
            int[] pos = calculateAutoPlacement(view, resolvedWidth, resolvedHeight);
            resolvedX = pos[0];
            resolvedY = pos[1];
        }

        // Create diagram object
        IDiagramModelArchimateObject diagramObj =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        diagramObj.setArchimateElement(element);
        diagramObj.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Story 11-2: Apply styling at creation time
        StylingHelper.applyStylingToNewObject(diagramObj, styling);

        // Build view object DTO (Story 11-2: include styling applied to new object)
        ViewObjectDto viewObjectDto = new ViewObjectDto(
                diagramObj.getId(), element.getId(), element.getName(),
                element.eClass().getName(), resolvedX, resolvedY,
                resolvedWidth, resolvedHeight,
                StylingHelper.readFillColor(diagramObj), StylingHelper.readLineColor(diagramObj),
                StylingHelper.readFontColor(diagramObj), StylingHelper.readOpacity(diagramObj),
                StylingHelper.readLineWidth(diagramObj));

        Command cmd;
        List<ViewConnectionDto> autoConnections = null;
        Integer skippedAutoConnections = null;

        if (autoConnect) {
            // Build view object map for auto-connect element lookups
            Map<String, IDiagramModelArchimateObject> viewObjectMap = new LinkedHashMap<>();
            collectViewObjectMap(view, viewObjectMap);

            NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(
                    "Add " + element.eClass().getName() + " to view with connections");
            compound.add(new AddToViewCommand(diagramObj, parentContainer));

            autoConnections = new ArrayList<>();
            int connectionCount = 0;
            int eligibleCount = 0;

            // Scan source relationships
            for (IArchimateRelationship rel : element.getSourceRelationships()) {
                IArchimateElement targetElement = (IArchimateElement) rel.getTarget();
                IDiagramModelArchimateObject targetViewObj =
                        findViewObjectForElement(viewObjectMap, targetElement.getId());
                if (targetViewObj != null) {
                    eligibleCount++;
                    if (connectionCount < MAX_AUTO_CONNECTIONS) {
                        IDiagramModelArchimateConnection conn =
                                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
                        conn.setArchimateRelationship(rel);
                        compound.add(new AddConnectionToViewCommand(conn, diagramObj, targetViewObj));
                        autoConnections.add(new ViewConnectionDto(
                                conn.getId(), rel.getId(), rel.eClass().getName(),
                                diagramObj.getId(), targetViewObj.getId(), null));
                        connectionCount++;
                    }
                }
            }

            // Scan target relationships
            for (IArchimateRelationship rel : element.getTargetRelationships()) {
                IArchimateElement sourceElement = (IArchimateElement) rel.getSource();
                IDiagramModelArchimateObject sourceViewObj =
                        findViewObjectForElement(viewObjectMap, sourceElement.getId());
                if (sourceViewObj != null) {
                    eligibleCount++;
                    if (connectionCount < MAX_AUTO_CONNECTIONS) {
                        IDiagramModelArchimateConnection conn =
                                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
                        conn.setArchimateRelationship(rel);
                        compound.add(new AddConnectionToViewCommand(conn, sourceViewObj, diagramObj));
                        autoConnections.add(new ViewConnectionDto(
                                conn.getId(), rel.getId(), rel.eClass().getName(),
                                sourceViewObj.getId(), diagramObj.getId(), null));
                        connectionCount++;
                    }
                }
            }

            if (eligibleCount > MAX_AUTO_CONNECTIONS) {
                skippedAutoConnections = eligibleCount - MAX_AUTO_CONNECTIONS;
            }

            cmd = compound;
        } else {
            cmd = new AddToViewCommand(diagramObj, parentContainer);
        }

        AddToViewResultDto resultDto = new AddToViewResultDto(
                viewObjectDto, autoConnections, skippedAutoConnections);
        return new PreparedMutation<>(cmd, resultDto, diagramObj.getId(), diagramObj);
    }

    private PreparedMutation<ViewGroupDto> prepareAddGroupToView(
            String viewId, String label, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId) {
        return prepareAddGroupToView(viewId, label, x, y, width, height,
                parentViewObjectId, null, null);
    }

    /**
     * Prepares an add-group-to-view mutation with optional pre-resolved batch parent container.
     * When batchParentContainer is non-null, it overrides parentViewObjectId lookup
     * (used for groups created earlier in the same bulk-mutate batch, Story 9-8).
     */
    private PreparedMutation<ViewGroupDto> prepareAddGroupToView(
            String viewId, String label, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling) {
        IArchimateModel model = requireAndCaptureModel();

        // Validate label
        if (label == null || label.isBlank()) {
            throw new ModelAccessException(
                    "Group label must not be blank",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a non-blank label for the group",
                    null);
        }

        // Validate x/y both-or-neither
        if ((x == null) != (y == null)) {
            throw new ModelAccessException(
                    "Both x and y must be specified together, or both omitted for auto-placement",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide both x and y coordinates, or omit both for auto-placement",
                    null);
        }

        // Find view
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Resolve dimensions
        int resolvedWidth = (width != null) ? width : DEFAULT_GROUP_WIDTH;
        int resolvedHeight = (height != null) ? height : DEFAULT_GROUP_HEIGHT;

        // Resolve position
        int resolvedX;
        int resolvedY;
        if (x != null) {
            resolvedX = x;
            resolvedY = y;
        } else {
            int[] pos = calculateAutoPlacement(view, resolvedWidth, resolvedHeight);
            resolvedX = pos[0];
            resolvedY = pos[1];
        }

        // Story 9-0b: Interpret escape sequences in group label
        label = TextUtils.interpretEscapes(label);

        // Create group
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setName(label);
        group.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Story 11-2: Apply styling at creation time
        StylingHelper.applyStylingToNewObject(group, styling);

        // Build command
        Command cmd = new AddGroupToViewCommand(group, parentContainer);

        // Build DTO (Story 11-2: include styling applied to new object)
        ViewGroupDto dto = new ViewGroupDto(
                group.getId(), label, resolvedX, resolvedY,
                resolvedWidth, resolvedHeight, null, List.of(),
                StylingHelper.readFillColor(group), StylingHelper.readLineColor(group),
                StylingHelper.readFontColor(group), StylingHelper.readOpacity(group),
                StylingHelper.readLineWidth(group));

        return new PreparedMutation<>(cmd, dto, group.getId(), group);
    }

    private PreparedMutation<ViewNoteDto> prepareAddNoteToView(
            String viewId, String content, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId) {
        return prepareAddNoteToView(viewId, content, x, y, width, height,
                parentViewObjectId, null, null);
    }

    /**
     * Prepares an add-note-to-view mutation with optional pre-resolved batch parent container.
     * When batchParentContainer is non-null, it overrides parentViewObjectId lookup
     * (used for groups created earlier in the same bulk-mutate batch, Story 9-8).
     */
    private PreparedMutation<ViewNoteDto> prepareAddNoteToView(
            String viewId, String content, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling) {
        IArchimateModel model = requireAndCaptureModel();

        // Validate content
        if (content == null) {
            throw new ModelAccessException(
                    "Note content must not be null",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide content for the note (empty string is allowed for placeholder notes)",
                    null);
        }

        // Validate x/y both-or-neither
        if ((x == null) != (y == null)) {
            throw new ModelAccessException(
                    "Both x and y must be specified together, or both omitted for auto-placement",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide both x and y coordinates, or omit both for auto-placement",
                    null);
        }

        // Find view
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Resolve dimensions
        int resolvedWidth = (width != null) ? width : DEFAULT_NOTE_WIDTH;
        int resolvedHeight = (height != null) ? height : DEFAULT_NOTE_HEIGHT;

        // Resolve position
        int resolvedX;
        int resolvedY;
        if (x != null) {
            resolvedX = x;
            resolvedY = y;
        } else {
            int[] pos = calculateAutoPlacement(view, resolvedWidth, resolvedHeight);
            resolvedX = pos[0];
            resolvedY = pos[1];
        }

        // Story 9-0b: Interpret escape sequences in note content
        content = TextUtils.interpretEscapes(content);

        // Create note
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setContent(content);
        note.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Story 11-2: Apply styling at creation time
        StylingHelper.applyStylingToNewObject(note, styling);

        // Build command
        Command cmd = new AddNoteToViewCommand(note, parentContainer);

        // Build DTO (Story 11-2: include styling applied to new object)
        ViewNoteDto dto = new ViewNoteDto(
                note.getId(), content, resolvedX, resolvedY,
                resolvedWidth, resolvedHeight, null,
                StylingHelper.readFillColor(note), StylingHelper.readLineColor(note),
                StylingHelper.readFontColor(note), StylingHelper.readOpacity(note),
                StylingHelper.readLineWidth(note));

        return new PreparedMutation<>(cmd, dto, note.getId(), note);
    }

    /**
     * Prepares an add-connection-to-view mutation: validates, creates EMF objects, builds command.
     */
    private PreparedMutation<ViewConnectionDto> prepareAddConnectionToView(
            String viewId, String relationshipId, String sourceViewObjectId,
            String targetViewObjectId, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints) {
        IArchimateModel model = requireAndCaptureModel();

        // Find view
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        // Find relationship
        EObject relObj = ArchimateModelUtils.getObjectByID(model, relationshipId);
        if (!(relObj instanceof IArchimateRelationship relationship)) {
            throw new ModelAccessException(
                    "Relationship not found: " + relationshipId,
                    ErrorCode.RELATIONSHIP_NOT_FOUND,
                    null,
                    "Use get-relationships to find valid relationship IDs",
                    null);
        }

        // Build view object map and find source/target view objects
        Map<String, IDiagramModelArchimateObject> viewObjectMap = new LinkedHashMap<>();
        collectViewObjectMap(view, viewObjectMap);

        IDiagramModelArchimateObject sourceViewObj = findViewObjectById(viewObjectMap, sourceViewObjectId);
        if (sourceViewObj == null) {
            throw new ModelAccessException(
                    "Source view object not found: " + sourceViewObjectId,
                    ErrorCode.VIEW_OBJECT_NOT_FOUND,
                    null,
                    "Use get-view-contents to find valid view object IDs (viewObjectId field in visualMetadata)",
                    null);
        }

        IDiagramModelArchimateObject targetViewObj = findViewObjectById(viewObjectMap, targetViewObjectId);
        if (targetViewObj == null) {
            throw new ModelAccessException(
                    "Target view object not found: " + targetViewObjectId,
                    ErrorCode.VIEW_OBJECT_NOT_FOUND,
                    null,
                    "Use get-view-contents to find valid view object IDs (viewObjectId field in visualMetadata)",
                    null);
        }

        // Validate relationship-element match (allow both orientations)
        IArchimateElement relSource = (IArchimateElement) relationship.getSource();
        IArchimateElement relTarget = (IArchimateElement) relationship.getTarget();
        IArchimateElement sourceElem = sourceViewObj.getArchimateElement();
        IArchimateElement targetElem = targetViewObj.getArchimateElement();

        boolean forwardMatch = relSource.getId().equals(sourceElem.getId())
                && relTarget.getId().equals(targetElem.getId());
        boolean reversedMatch = relSource.getId().equals(targetElem.getId())
                && relTarget.getId().equals(sourceElem.getId());

        if (!forwardMatch && !reversedMatch) {
            throw new ModelAccessException(
                    "Relationship '" + relationshipId + "' does not connect the elements "
                            + "referenced by the source and target view objects",
                    ErrorCode.RELATIONSHIP_MISMATCH,
                    "Relationship connects " + relSource.getId() + " -> " + relTarget.getId()
                            + ", but view objects reference " + sourceElem.getId()
                            + " and " + targetElem.getId(),
                    "Verify the relationship connects the correct elements, "
                            + "or use different view objects",
                    null);
        }

        // Check connection-already-on-view
        if (hasExistingConnection(sourceViewObj, targetViewObj, relationshipId)) {
            throw new ModelAccessException(
                    "A connection for relationship '" + relationshipId
                            + "' already exists between these view objects",
                    ErrorCode.CONNECTION_ALREADY_ON_VIEW,
                    null,
                    "The connection already exists on this view",
                    null);
        }

        // Story 8-0d: convert absolute bendpoints to relative if provided
        List<BendpointDto> effectiveBendpoints = bendpoints;
        if (absoluteBendpoints != null && !absoluteBendpoints.isEmpty()) {
            effectiveBendpoints = ConnectionResponseBuilder.convertAbsoluteToRelative(
                    absoluteBendpoints, sourceViewObj, targetViewObj);
        }

        // Create connection
        IDiagramModelArchimateConnection conn =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(relationship);
        ConnectionResponseBuilder.applyBendpointsToConnection(conn, effectiveBendpoints);

        Command cmd = new AddConnectionToViewCommand(conn, sourceViewObj, targetViewObj);

        ViewConnectionDto dto = ConnectionResponseBuilder.buildConnectionResponseDto(
                conn.getId(), relationship, sourceViewObjectId, targetViewObjectId,
                effectiveBendpoints, sourceViewObj, targetViewObj, conn.getTextPosition());

        return new PreparedMutation<>(cmd, dto, conn.getId(), conn);
    }

    // ---- View editing/removal prepare methods (Story 7-8) ----

    /**
     * Prepares an update-view-object mutation: validates, reads current bounds,
     * merges with provided values, builds command and DTO.
     */
    private PreparedMutation<ViewObjectDto> prepareUpdateViewObject(
            String viewObjectId, Integer x, Integer y, Integer width, Integer height,
            String text, StylingParams styling) {
        IArchimateModel model = requireAndCaptureModel();

        // Story 11-2: Validate hex colours before any other processing
        if (styling != null) {
            StylingHelper.validateStylingParams(styling);
        }

        // Validate at least one field provided (including styling, Story 11-2)
        boolean hasStyling = styling != null && styling.hasAnyValue();
        if (x == null && y == null && width == null && height == null && text == null && !hasStyling) {
            throw new ModelAccessException(
                    "At least one of x, y, width, height, text, or styling parameter must be provided",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "At least one of x, y, width, height, text, fillColor, lineColor, fontColor, opacity, lineWidth must be provided.",
                    null);
        }

        // Find view object — accept element objects, groups, and notes (Story 8-6)
        EObject obj = ArchimateModelUtils.getObjectByID(model, viewObjectId);
        if (!(obj instanceof IDiagramModelObject diagramObj)) {
            throw new ModelAccessException(
                    "View object not found: " + viewObjectId,
                    ErrorCode.VIEW_OBJECT_NOT_FOUND,
                    null,
                    "Use get-view-contents to find valid view object IDs and connection IDs on the view.",
                    null);
        }

        // Story 8-6: Validate text parameter
        if (text != null && diagramObj instanceof IDiagramModelArchimateObject) {
            throw new ModelAccessException(
                    "Cannot set text on an ArchiMate element view object",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "The 'text' parameter is only valid for groups (label) and notes (content). "
                            + "Use update-element to change an element's name.",
                    null);
        }

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Story 9-0b: Interpret escape sequences in text for notes/groups
        text = TextUtils.interpretEscapes(text);

        // Read current bounds and merge
        IBounds bounds = diagramObj.getBounds();
        int mergedX = (x != null) ? x : bounds.getX();
        int mergedY = (y != null) ? y : bounds.getY();
        int mergedWidth = (width != null) ? width : bounds.getWidth();
        int mergedHeight = (height != null) ? height : bounds.getHeight();

        // Build command (with optional text and styling update, Story 11-2)
        Command cmd = new UpdateViewObjectCommand(diagramObj, mergedX, mergedY, mergedWidth, mergedHeight, text, styling);

        // Build DTO — generic for all view object types (Story 11-2: include post-execution styling)
        String dtoFillColor = StylingHelper.computePostStylingColor(StylingHelper.readFillColor(diagramObj), styling != null ? styling.fillColor() : null);
        String dtoLineColor = StylingHelper.computePostStylingColor(StylingHelper.readLineColor(diagramObj), styling != null ? styling.lineColor() : null);
        String dtoFontColor = StylingHelper.computePostStylingColor(StylingHelper.readFontColor(diagramObj), styling != null ? styling.fontColor() : null);
        Integer dtoOpacity = StylingHelper.computePostStylingOpacity(StylingHelper.readOpacity(diagramObj), styling != null ? styling.opacity() : null);
        Integer dtoLineWidth = StylingHelper.computePostStylingLineWidth(StylingHelper.readLineWidth(diagramObj), styling != null ? styling.lineWidth() : null);

        ViewObjectDto dto;
        if (diagramObj instanceof IDiagramModelArchimateObject archObj) {
            IArchimateElement element = archObj.getArchimateElement();
            dto = new ViewObjectDto(
                    viewObjectId, element.getId(), element.getName(),
                    element.eClass().getName(), mergedX, mergedY,
                    mergedWidth, mergedHeight,
                    dtoFillColor, dtoLineColor, dtoFontColor, dtoOpacity, dtoLineWidth);
        } else {
            // Group or note — no element association
            dto = new ViewObjectDto(
                    viewObjectId, null, diagramObj.getName(),
                    diagramObj.eClass().getName(), mergedX, mergedY,
                    mergedWidth, mergedHeight,
                    dtoFillColor, dtoLineColor, dtoFontColor, dtoOpacity, dtoLineWidth);
        }

        return new PreparedMutation<>(cmd, dto, viewObjectId);
    }

    /**
     * Prepares an update-view-object mutation using a direct EMF reference (for bulk back-references).
     * H2 fix: bypasses ArchimateModelUtils.getObjectByID for back-referenced view objects.
     */
    private PreparedMutation<ViewObjectDto> prepareUpdateViewObjectDirect(
            IDiagramModelArchimateObject diagramObj, Integer x, Integer y,
            Integer width, Integer height) {

        // Validate at least one field provided
        if (x == null && y == null && width == null && height == null) {
            throw new ModelAccessException(
                    "At least one of x, y, width, height must be provided",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "At least one of x, y, width, height must be provided.",
                    null);
        }

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Read current bounds and merge
        IBounds bounds = diagramObj.getBounds();
        int mergedX = (x != null) ? x : bounds.getX();
        int mergedY = (y != null) ? y : bounds.getY();
        int mergedWidth = (width != null) ? width : bounds.getWidth();
        int mergedHeight = (height != null) ? height : bounds.getHeight();

        // Build command
        Command cmd = new UpdateViewObjectCommand(diagramObj, mergedX, mergedY, mergedWidth, mergedHeight);

        // Build DTO (include current styling for consistency)
        IArchimateElement element = diagramObj.getArchimateElement();
        ViewObjectDto dto = new ViewObjectDto(
                diagramObj.getId(), element.getId(), element.getName(),
                element.eClass().getName(), mergedX, mergedY,
                mergedWidth, mergedHeight,
                StylingHelper.readFillColor(diagramObj), StylingHelper.readLineColor(diagramObj),
                StylingHelper.readFontColor(diagramObj), StylingHelper.readOpacity(diagramObj),
                StylingHelper.readLineWidth(diagramObj));

        return new PreparedMutation<>(cmd, dto, diagramObj.getId());
    }

    /**
     * Prepares an update-view-connection mutation: validates, creates new bendpoints,
     * builds command and DTO.
     */
    private PreparedMutation<ViewConnectionDto> prepareUpdateViewConnection(
            String viewConnectionId, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling) {
        IArchimateModel model = requireAndCaptureModel();

        // Story 11-2: Validate hex colours before any other processing
        if (styling != null) {
            StylingHelper.validateConnectionStylingParams(styling);
        }

        // Find connection
        EObject obj = ArchimateModelUtils.getObjectByID(model, viewConnectionId);
        if (!(obj instanceof IDiagramModelArchimateConnection connection)) {
            throw new ModelAccessException(
                    "View object not found: " + viewConnectionId,
                    ErrorCode.VIEW_OBJECT_NOT_FOUND,
                    null,
                    "Use get-view-contents to find valid view object IDs and connection IDs on the view.",
                    null);
        }

        // Story 8-0d: convert absolute bendpoints to relative if provided
        List<BendpointDto> effectiveBendpoints = ConnectionResponseBuilder.resolveEffectiveBendpointsFromConnection(
                bendpoints, absoluteBendpoints, connection);

        // Build command (with optional styling, Story 11-2)
        // When effectiveBendpoints is null (styling-only update), preserve existing bendpoints
        List<IDiagramModelBendpoint> emfBendpoints;
        if (effectiveBendpoints == null) {
            emfBendpoints = new ArrayList<>(connection.getBendpoints());
        } else {
            emfBendpoints = ConnectionResponseBuilder.createEmfBendpoints(effectiveBendpoints);
        }
        Command cmd = new UpdateViewConnectionCommand(connection, emfBendpoints, styling);

        // Build DTO — extract source/target for anchor points
        IDiagramModelArchimateObject sourceViewObj =
                (connection.getSource() instanceof IDiagramModelArchimateObject src) ? src : null;
        IDiagramModelArchimateObject targetViewObj =
                (connection.getTarget() instanceof IDiagramModelArchimateObject tgt) ? tgt : null;
        String sourceVoId = sourceViewObj != null ? sourceViewObj.getId() : null;
        String targetVoId = targetViewObj != null ? targetViewObj.getId() : null;

        // Build base DTO then overlay post-execution styling (Story 11-2)
        // When effectiveBendpoints is null (styling-only), read current bendpoints for response
        List<BendpointDto> responseBendpoints = (effectiveBendpoints != null)
                ? effectiveBendpoints : ConnectionResponseBuilder.collectBendpoints(connection);
        ViewConnectionDto baseDto = ConnectionResponseBuilder.buildConnectionResponseDto(
                viewConnectionId, connection.getArchimateRelationship(),
                sourceVoId, targetVoId, responseBendpoints, sourceViewObj, targetViewObj,
                connection.getTextPosition());

        // Compute post-execution connection styling
        String dtoLineColor = StylingHelper.computePostStylingColor(
                StylingHelper.readConnectionLineColor(connection), styling != null ? styling.lineColor() : null);
        String dtoFontColor = StylingHelper.computePostStylingColor(
                StylingHelper.readConnectionFontColor(connection), styling != null ? styling.fontColor() : null);
        Integer dtoLineWidth = StylingHelper.computePostStylingLineWidth(
                StylingHelper.readConnectionLineWidth(connection), styling != null ? styling.lineWidth() : null);

        ViewConnectionDto dto = new ViewConnectionDto(
                baseDto.viewConnectionId(), baseDto.relationshipId(),
                baseDto.relationshipType(), baseDto.sourceViewObjectId(),
                baseDto.targetViewObjectId(), baseDto.bendpoints(),
                baseDto.absoluteBendpoints(), baseDto.sourceAnchor(),
                baseDto.targetAnchor(), baseDto.textPosition(),
                dtoLineColor, dtoLineWidth, dtoFontColor);

        return new PreparedMutation<>(cmd, dto, viewConnectionId);
    }

    /**
     * Prepares an update-view-connection mutation using a direct EMF reference (for bulk back-references).
     * H1 fix: bypasses ArchimateModelUtils.getObjectByID for back-referenced view connections.
     */
    private PreparedMutation<ViewConnectionDto> prepareUpdateViewConnectionDirect(
            IDiagramModelArchimateConnection connection, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints) {

        // Story 8-0d: convert absolute bendpoints to relative if provided
        List<BendpointDto> effectiveBendpoints = ConnectionResponseBuilder.resolveEffectiveBendpointsFromConnection(
                bendpoints, absoluteBendpoints, connection);

        // Build command
        Command cmd = new UpdateViewConnectionCommand(connection, ConnectionResponseBuilder.createEmfBendpoints(effectiveBendpoints));

        // Build DTO — extract source/target for anchor points
        String viewConnectionId = connection.getId();
        IDiagramModelArchimateObject sourceViewObj =
                (connection.getSource() instanceof IDiagramModelArchimateObject src) ? src : null;
        IDiagramModelArchimateObject targetViewObj =
                (connection.getTarget() instanceof IDiagramModelArchimateObject tgt) ? tgt : null;
        String sourceVoId = sourceViewObj != null ? sourceViewObj.getId() : null;
        String targetVoId = targetViewObj != null ? targetViewObj.getId() : null;

        ViewConnectionDto dto = ConnectionResponseBuilder.buildConnectionResponseDto(
                viewConnectionId, connection.getArchimateRelationship(),
                sourceVoId, targetVoId, effectiveBendpoints, sourceViewObj, targetViewObj,
                connection.getTextPosition());

        return new PreparedMutation<>(cmd, dto, viewConnectionId);
    }

    /**
     * Prepares a remove-from-view mutation: finds the object (element or connection),
     * collects attached connections for cascade, builds command and DTO.
     */
    private PreparedMutation<RemoveFromViewResultDto> prepareRemoveFromView(
            String viewId, String viewObjectId) {
        IArchimateModel model = requireAndCaptureModel();

        // Find view
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        // Look up the viewObjectId — could be an element or a connection
        EObject targetObj = ArchimateModelUtils.getObjectByID(model, viewObjectId);

        // Case 1: It's a view object (element on the view)
        if (targetObj instanceof IDiagramModelArchimateObject diagramObj) {
            // Verify it's actually on this view
            if (!isChildOfView(view, diagramObj)) {
                throw new ModelAccessException(
                        "View object not found on view: " + viewObjectId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Use get-view-contents to find valid view object IDs and connection IDs on the view.",
                        null);
            }

            // Collect attached connections
            List<IDiagramModelArchimateConnection> attached = new ArrayList<>();
            for (Object conn : diagramObj.getSourceConnections()) {
                if (conn instanceof IDiagramModelArchimateConnection ac) attached.add(ac);
            }
            for (Object conn : diagramObj.getTargetConnections()) {
                if (conn instanceof IDiagramModelArchimateConnection ac) attached.add(ac);
            }

            Command cmd = new RemoveFromViewCommand(diagramObj, view, attached);

            List<String> cascadeIds = attached.isEmpty() ? null
                    : attached.stream().map(IDiagramModelArchimateConnection::getId).toList();
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    viewObjectId, "viewObject", cascadeIds);

            return new PreparedMutation<>(cmd, dto, viewObjectId);
        }

        // Story 8-6: Case 1b: It's a group on the view
        if (targetObj instanceof IDiagramModelGroup groupObj) {
            if (!isChildOfViewGeneric(view, groupObj)) {
                throw new ModelAccessException(
                        "View object not found on view: " + viewObjectId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Use get-view-contents to find valid view object IDs and connection IDs on the view.",
                        null);
            }
            IDiagramModelContainer parent = findParentContainer(view, groupObj);
            Command cmd = new RemoveViewObjectCommand(groupObj, parent);
            List<String> cascadeIds = collectDescendantIds(groupObj);
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    viewObjectId, "group", cascadeIds.isEmpty() ? null : cascadeIds);
            return new PreparedMutation<>(cmd, dto, viewObjectId);
        }

        // Story 8-6: Case 1c: It's a note on the view
        if (targetObj instanceof IDiagramModelNote noteObj) {
            if (!isChildOfViewGeneric(view, noteObj)) {
                throw new ModelAccessException(
                        "View object not found on view: " + viewObjectId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Use get-view-contents to find valid view object IDs and connection IDs on the view.",
                        null);
            }
            IDiagramModelContainer parent = findParentContainer(view, noteObj);
            Command cmd = new RemoveViewObjectCommand(noteObj, parent);
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    viewObjectId, "note", null);
            return new PreparedMutation<>(cmd, dto, viewObjectId);
        }

        // Case 2: It's a connection on the view
        if (targetObj instanceof IDiagramModelArchimateConnection connection) {
            // Verify the connection belongs to this view by checking its source/target
            if (!isConnectionOnView(view, connection)) {
                throw new ModelAccessException(
                        "View object not found on view: " + viewObjectId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Use get-view-contents to find valid view object IDs and connection IDs on the view.",
                        null);
            }
            Command cmd = new RemoveConnectionFromViewCommand(connection);
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    viewObjectId, "viewConnection", null);
            return new PreparedMutation<>(cmd, dto, viewObjectId);
        }

        // Neither element nor connection found
        throw new ModelAccessException(
                "View object not found: " + viewObjectId,
                ErrorCode.VIEW_OBJECT_NOT_FOUND,
                null,
                "Use get-view-contents to find valid view object IDs and connection IDs on the view.",
                null);
    }

    /**
     * Prepares a clear-view mutation without dispatching (Story 8-0c).
     * Finds view, counts children and connections, builds ClearViewCommand and DTO.
     */
    private PreparedMutation<ClearViewResultDto> prepareClearView(String viewId) {
        IArchimateModel model = requireAndCaptureModel();

        // Find view
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        // Count top-level children (groups are removed as single units; nested
        // elements cascade with their parent). Connection count below is recursive
        // because ClearViewCommand explicitly disconnects nested connections.
        int totalChildren = view.getChildren().size();
        int archimateCount = 0;
        for (Object child : view.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject) {
                archimateCount++;
            }
        }
        int nonArchimateCount = totalChildren - archimateCount;

        // Count all connections recursively (including nested elements inside groups)
        // to give an accurate count in the DTO (Story 10-14)
        List<IDiagramModelConnection> allConnections = AssessmentCollector.collectAllConnections(view);

        Command cmd = new ClearViewCommand(view);
        ClearViewResultDto dto = new ClearViewResultDto(
                viewId, view.getName(), totalChildren, allConnections.size(),
                nonArchimateCount);

        return new PreparedMutation<>(cmd, dto, viewId);
    }

    // ---- Deletion prepare methods (Story 8-4) ----

    /**
     * Prepares a delete-element mutation: discovers all cascade targets
     * (relationships, view objects, view connections), builds the command and DTO.
     */
    PreparedMutation<DeleteResultDto> prepareDeleteElement(String elementId) {
        IArchimateModel model = requireAndCaptureModel();

        EObject obj = ArchimateModelUtils.getObjectByID(model, elementId);
        if (!(obj instanceof IArchimateElement element)) {
            throw new ModelAccessException(
                    "Element not found: " + elementId,
                    ErrorCode.ELEMENT_NOT_FOUND,
                    null,
                    "Use search-elements or get-element to find valid element IDs",
                    null);
        }

        IFolder elementFolder = (IFolder) element.eContainer();
        int elementIndex = elementFolder.getElements().indexOf(element);

        // Discover relationships (both directions), deduplicate self-loops
        // A self-referencing relationship (source == target == element) appears in
        // both getSourceRelationships() and getTargetRelationships(). Using a
        // LinkedHashMap keyed by ID ensures each relationship is processed exactly once.
        Map<String, IArchimateRelationship> uniqueRels = new LinkedHashMap<>();
        for (IArchimateRelationship rel : element.getSourceRelationships()) {
            uniqueRels.putIfAbsent(rel.getId(), rel);
        }
        for (IArchimateRelationship rel : element.getTargetRelationships()) {
            uniqueRels.putIfAbsent(rel.getId(), rel);
        }
        List<IArchimateRelationship> allRels = new ArrayList<>(uniqueRels.values());
        Set<String> relationshipIds = uniqueRels.keySet();

        List<IDiagramModelConnection> allViewConnections = new ArrayList<>();
        List<DeleteElementCommand.CascadedViewReference> viewRefs = new ArrayList<>();
        discoverCascadeTargets(model, element.getId(), relationshipIds,
                allViewConnections, viewRefs);

        // Build relationship cascade records (sorted descending by index)
        List<DeleteElementCommand.CascadedRelationship> cascadedRels = new ArrayList<>();
        for (IArchimateRelationship rel : allRels) {
            IFolder relFolder = (IFolder) rel.eContainer();
            int relIndex = relFolder.getElements().indexOf(rel);
            cascadedRels.add(new DeleteElementCommand.CascadedRelationship(
                    rel, relFolder, relIndex,
                    rel.getSource(),
                    rel.getTarget()));
        }
        // Sort by folder identity first, then descending by index within each folder.
        // This ensures removals from the same folder happen in descending index order,
        // preventing index shift issues during removal.
        cascadedRels.sort((a, b) -> {
            int folderCmp = Integer.compare(
                    System.identityHashCode(a.folder()),
                    System.identityHashCode(b.folder()));
            return folderCmp != 0 ? folderCmp
                    : Integer.compare(b.indexInFolder(), a.indexInFolder());
        });

        // Sort view refs by container identity first, then descending by index
        viewRefs.sort((a, b) -> {
            int containerCmp = Integer.compare(
                    System.identityHashCode(a.container()),
                    System.identityHashCode(b.container()));
            return containerCmp != 0 ? containerCmp
                    : Integer.compare(b.indexInContainer(), a.indexInContainer());
        });

        Command cmd = new DeleteElementCommand(element, elementFolder, elementIndex,
                cascadedRels, viewRefs, allViewConnections);

        DeleteResultDto dto = new DeleteResultDto(
                element.getId(), element.getName(), element.eClass().getName(),
                allRels.size(), viewRefs.size(), allViewConnections.size(),
                null, null, null);

        return new PreparedMutation<>(cmd, dto, element.getId());
    }

    /**
     * Prepares a delete-relationship mutation: discovers view connections to cascade.
     */
    PreparedMutation<DeleteResultDto> prepareDeleteRelationship(String relationshipId) {
        IArchimateModel model = requireAndCaptureModel();

        EObject obj = ArchimateModelUtils.getObjectByID(model, relationshipId);
        if (!(obj instanceof IArchimateRelationship relationship)) {
            throw new ModelAccessException(
                    "Relationship not found: " + relationshipId,
                    ErrorCode.RELATIONSHIP_NOT_FOUND,
                    null,
                    "Use get-relationships to find valid relationship IDs",
                    null);
        }

        IFolder folder = (IFolder) relationship.eContainer();
        int indexInFolder = folder.getElements().indexOf(relationship);
        IArchimateConcept source = relationship.getSource();
        IArchimateConcept target = relationship.getTarget();

        // Find view connections for this relationship
        List<IDiagramModelConnection> viewConnections = new ArrayList<>();
        Set<String> relIds = Set.of(relationshipId);
        discoverViewConnectionsForRelationships(model, relIds, viewConnections);

        Command cmd = new DeleteRelationshipCommand(relationship, folder, indexInFolder,
                source, target, viewConnections);

        DeleteResultDto dto = new DeleteResultDto(
                relationship.getId(), relationship.getName(),
                relationship.eClass().getName(),
                0, 0, viewConnections.size(),
                null, null, null);

        return new PreparedMutation<>(cmd, dto, relationship.getId());
    }

    /**
     * Prepares a delete-view mutation.
     */
    PreparedMutation<DeleteResultDto> prepareDeleteView(String viewId) {
        IArchimateModel model = requireAndCaptureModel();

        EObject obj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(obj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        IFolder viewFolder = (IFolder) view.eContainer();
        int viewIndex = viewFolder.getElements().indexOf(view);

        Command cmd = new DeleteViewCommand(view, viewFolder, viewIndex);

        DeleteResultDto dto = new DeleteResultDto(
                view.getId(), view.getName(), "View",
                0, 0, 0, null, null, null);

        return new PreparedMutation<>(cmd, dto, view.getId());
    }

    /**
     * Prepares a delete-folder mutation with validation for default folders
     * and empty-folder checks.
     */
    PreparedMutation<DeleteResultDto> prepareDeleteFolder(String folderId, boolean force) {
        IArchimateModel model = requireAndCaptureModel();

        IFolder folder = FolderOperations.findFolderById(model, folderId);
        if (folder == null) {
            throw new ModelAccessException(
                    "Folder not found: " + folderId,
                    ErrorCode.FOLDER_NOT_FOUND,
                    null,
                    "Use get-folders to find valid folder IDs",
                    null);
        }

        // Reject default top-level folders
        if (folder.eContainer() instanceof IArchimateModel) {
            throw new ModelAccessException(
                    "Cannot delete default ArchiMate folder: " + folder.getName(),
                    ErrorCode.CANNOT_DELETE_DEFAULT_FOLDER,
                    null,
                    "Default ArchiMate layer folders cannot be deleted. "
                            + "Only user-created subfolders can be removed.",
                    null);
        }

        IFolder parentFolder = (IFolder) folder.eContainer();
        int folderIndex = parentFolder.getFolders().indexOf(folder);

        int elementCount = folder.getElements().size();
        int subfolderCount = folder.getFolders().size();
        boolean isEmpty = elementCount == 0 && subfolderCount == 0;

        if (!isEmpty && !force) {
            throw new ModelAccessException(
                    "Folder '" + folder.getName() + "' is not empty: "
                            + elementCount + " element(s), " + subfolderCount + " subfolder(s). "
                            + "Use force: true to cascade-delete all contents.",
                    ErrorCode.FOLDER_NOT_EMPTY,
                    null,
                    "Use force: true to cascade-delete, or move contents first.",
                    null);
        }

        List<Command> subCommands = new ArrayList<>();
        int elementsRemoved = 0;
        int relationshipsRemoved = 0;
        int viewsRemoved = 0;
        int foldersRemoved = 0;
        int viewReferencesRemoved = 0;
        int viewConnectionsRemoved = 0;

        if (force && !isEmpty) {
            // Collect cascade counts from depth-first traversal
            int[] counts = new int[6]; // [elements, relationships, views, folders,
                                       //  viewReferences, viewConnections]
            buildFolderDeleteSubCommands(folder, subCommands, counts, model,
                    new HashSet<>());
            elementsRemoved = counts[0];
            relationshipsRemoved = counts[1];
            viewsRemoved = counts[2];
            foldersRemoved = counts[3];
            viewReferencesRemoved = counts[4];
            viewConnectionsRemoved = counts[5];
        }

        Command cmd = new DeleteFolderCommand(folder, parentFolder, folderIndex, subCommands);

        DeleteResultDto dto = new DeleteResultDto(
                folder.getId(), folder.getName(), "Folder",
                relationshipsRemoved, viewReferencesRemoved, viewConnectionsRemoved,
                force ? elementsRemoved : null,
                force ? viewsRemoved : null,
                force ? foldersRemoved : null);

        return new PreparedMutation<>(cmd, dto, folder.getId());
    }

    /**
     * Recursively builds sub-commands for force-deleting a folder's contents.
     * Depth-first: process subfolders first, then elements/views in this folder.
     *
     * <p>The {@code cascadedRelIds} set tracks relationship IDs that have already
     * been claimed by element cascade deletions across the entire folder tree.
     * This prevents double-deletion when a relationship and its connected element
     * both reside within the folder hierarchy being force-deleted.</p>
     *
     * @param counts [elements, relationships, views, folders, viewReferences, viewConnections]
     * @param cascadedRelIds relationship IDs already cascade-claimed by element deletions
     */
    private void buildFolderDeleteSubCommands(IFolder folder, List<Command> subCommands,
            int[] counts, IArchimateModel model, Set<String> cascadedRelIds) {
        // Recurse into subfolders first (depth-first)
        for (IFolder subfolder : new ArrayList<>(folder.getFolders())) {
            buildFolderDeleteSubCommands(subfolder, subCommands, counts, model, cascadedRelIds);
            // Add command to remove subfolder (contents handled by sub-commands above)
            int subfolderIndex = folder.getFolders().indexOf(subfolder);
            subCommands.add(new DeleteFolderCommand(subfolder, folder, subfolderIndex,
                    List.of()));
            counts[3]++; // folders
        }

        // Process elements in this folder
        for (Object obj : new ArrayList<>(folder.getElements())) {
            if (obj instanceof IArchimateElement element) {
                // Track relationship IDs that will be cascade-deleted with this element
                for (IArchimateRelationship rel : element.getSourceRelationships()) {
                    cascadedRelIds.add(rel.getId());
                }
                for (IArchimateRelationship rel : element.getTargetRelationships()) {
                    cascadedRelIds.add(rel.getId());
                }
                PreparedMutation<DeleteResultDto> prepared = prepareDeleteElement(element.getId());
                subCommands.add(prepared.command());
                counts[0]++; // elements
                counts[1] += prepared.entity().relationshipsRemoved(); // cascaded relationships
                counts[4] += prepared.entity().viewReferencesRemoved(); // cascaded view refs
                counts[5] += prepared.entity().viewConnectionsRemoved(); // cascaded view conns
            } else if (obj instanceof IArchimateRelationship relationship) {
                // Skip if already cascade-claimed by an element deletion
                if (cascadedRelIds.contains(relationship.getId())) {
                    continue;
                }
                PreparedMutation<DeleteResultDto> prepared =
                        prepareDeleteRelationship(relationship.getId());
                subCommands.add(prepared.command());
                counts[1]++; // relationships
                counts[5] += prepared.entity().viewConnectionsRemoved(); // cascaded view conns
            } else if (obj instanceof IArchimateDiagramModel view) {
                PreparedMutation<DeleteResultDto> prepared = prepareDeleteView(view.getId());
                subCommands.add(prepared.command());
                counts[2]++; // views
            }
        }
    }

    /**
     * Discovers all view connections and view objects that need to be cascade-removed
     * when deleting an element. Single pass over all views in the model.
     */
    private void discoverCascadeTargets(IArchimateModel model, String elementId,
            Set<String> relationshipIds,
            List<IDiagramModelConnection> viewConnections,
            List<DeleteElementCommand.CascadedViewReference> viewRefs) {
        IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);
        if (diagramsFolder != null) {
            discoverCascadeInFolder(diagramsFolder, elementId, relationshipIds,
                    viewConnections, viewRefs, new LinkedHashSet<>());
        }
    }

    private void discoverCascadeInFolder(IFolder folder, String elementId,
            Set<String> relationshipIds,
            List<IDiagramModelConnection> viewConnections,
            List<DeleteElementCommand.CascadedViewReference> viewRefs,
            Set<String> seenConnectionIds) {
        for (EObject obj : folder.getElements()) {
            if (obj instanceof IArchimateDiagramModel view) {
                discoverCascadeInView(view, elementId, relationshipIds,
                        viewConnections, viewRefs, seenConnectionIds);
            }
        }
        for (IFolder sub : folder.getFolders()) {
            discoverCascadeInFolder(sub, elementId, relationshipIds,
                    viewConnections, viewRefs, seenConnectionIds);
        }
    }

    private void discoverCascadeInView(IDiagramModelContainer container, String elementId,
            Set<String> relationshipIds,
            List<IDiagramModelConnection> viewConnections,
            List<DeleteElementCommand.CascadedViewReference> viewRefs,
            Set<String> seenConnectionIds) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject archObj) {
                // Check if this view object represents our element
                if (archObj.getArchimateElement() != null
                        && elementId.equals(archObj.getArchimateElement().getId())) {
                    int index = container.getChildren().indexOf(archObj);
                    viewRefs.add(new DeleteElementCommand.CascadedViewReference(
                            archObj, container, index));
                }

                // Check source connections for relationship matches
                for (Object conn : archObj.getSourceConnections()) {
                    if (conn instanceof IDiagramModelArchimateConnection archConn) {
                        IArchimateRelationship rel = archConn.getArchimateRelationship();
                        if (rel != null && relationshipIds.contains(rel.getId())) {
                            if (seenConnectionIds.add(archConn.getId())) {
                                viewConnections.add(archConn);
                            }
                        }
                    }
                }
            }
            // Recurse into nested containers
            if (child instanceof IDiagramModelContainer nested) {
                discoverCascadeInView(nested, elementId, relationshipIds,
                        viewConnections, viewRefs, seenConnectionIds);
            }
        }
    }

    /**
     * Discovers view connections for a set of relationship IDs across all views.
     */
    private void discoverViewConnectionsForRelationships(IArchimateModel model,
            Set<String> relationshipIds,
            List<IDiagramModelConnection> viewConnections) {
        IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);
        if (diagramsFolder != null) {
            discoverViewConnectionsInFolder(diagramsFolder, relationshipIds,
                    viewConnections, new LinkedHashSet<>());
        }
    }

    private void discoverViewConnectionsInFolder(IFolder folder,
            Set<String> relationshipIds,
            List<IDiagramModelConnection> viewConnections,
            Set<String> seenConnectionIds) {
        for (EObject obj : folder.getElements()) {
            if (obj instanceof IArchimateDiagramModel view) {
                discoverViewConnectionsInContainer(view, relationshipIds,
                        viewConnections, seenConnectionIds);
            }
        }
        for (IFolder sub : folder.getFolders()) {
            discoverViewConnectionsInFolder(sub, relationshipIds,
                    viewConnections, seenConnectionIds);
        }
    }

    private void discoverViewConnectionsInContainer(IDiagramModelContainer container,
            Set<String> relationshipIds,
            List<IDiagramModelConnection> viewConnections,
            Set<String> seenConnectionIds) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject archObj) {
                for (Object conn : archObj.getSourceConnections()) {
                    if (conn instanceof IDiagramModelArchimateConnection archConn) {
                        IArchimateRelationship rel = archConn.getArchimateRelationship();
                        if (rel != null && relationshipIds.contains(rel.getId())) {
                            if (seenConnectionIds.add(archConn.getId())) {
                                viewConnections.add(archConn);
                            }
                        }
                    }
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                discoverViewConnectionsInContainer(nested, relationshipIds,
                        viewConnections, seenConnectionIds);
            }
        }
    }

    /**
     * Resolves a human-readable label for a view object: "{ElementType} '{ElementName}'".
     * Falls back to "view object" if the ID cannot be resolved.
     */
    private String resolveElementInfo(IArchimateModel model, String viewObjectId) {
        EObject obj = ArchimateModelUtils.getObjectByID(model, viewObjectId);
        if (obj instanceof IDiagramModelArchimateObject dmo && dmo.getArchimateElement() != null) {
            IArchimateElement el = dmo.getArchimateElement();
            return el.eClass().getName() + " '" + el.getName() + "'";
        }
        return "view object";
    }

    /**
     * Resolves a human-readable label for a view connection's relationship type.
     * Falls back to "unknown" if the ID cannot be resolved.
     */
    private String resolveConnectionInfo(IArchimateModel model, String viewConnectionId) {
        EObject obj = ArchimateModelUtils.getObjectByID(model, viewConnectionId);
        if (obj instanceof IDiagramModelArchimateConnection conn
                && conn.getArchimateRelationship() != null) {
            return conn.getArchimateRelationship().eClass().getName();
        }
        return "unknown";
    }

    /**
     * Checks whether a diagram object is a child (possibly nested) of the given view.
     */
    private boolean isChildOfView(IDiagramModelContainer view, IDiagramModelArchimateObject diagramObj) {
        for (IDiagramModelObject child : view.getChildren()) {
            if (child == diagramObj) return true;
            if (child instanceof IDiagramModelContainer nested) {
                if (isChildOfView(nested, diagramObj)) return true;
            }
        }
        return false;
    }

    /**
     * Finds the immediate parent container of a diagram object within a view hierarchy.
     * Story 8-6: Used for group/note removal to determine the correct parent.
     */
    private IDiagramModelContainer findParentContainer(IDiagramModelContainer container,
                                                        IDiagramModelObject target) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child == target) return container;
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelContainer found = findParentContainer(nested, target);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Checks whether any IDiagramModelObject is a child (possibly nested) of the given container.
     * Story 8-6: Generic version that works for groups, notes, and element objects.
     */
    private boolean isChildOfViewGeneric(IDiagramModelContainer view, IDiagramModelObject target) {
        for (IDiagramModelObject child : view.getChildren()) {
            if (child == target) return true;
            if (child instanceof IDiagramModelContainer nested) {
                if (isChildOfViewGeneric(nested, target)) return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a connection belongs to the given view by verifying that
     * its source view object is a child of the view.
     */
    private boolean isConnectionOnView(IDiagramModelContainer view,
                                        IDiagramModelArchimateConnection connection) {
        if (connection.getSource() instanceof IDiagramModelArchimateObject sourceObj) {
            return isChildOfView(view, sourceObj);
        }
        return false;
    }

    // ---- View placement helpers (Story 7-7) ----

    /**
     * Recursively collects all view objects on a view into a map keyed by view object ID.
     */
    private void collectViewObjectMap(IDiagramModelContainer container,
                                       Map<String, IDiagramModelArchimateObject> map) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject archObj) {
                map.put(archObj.getId(), archObj);
            }
            if (child instanceof IDiagramModelContainer nested) {
                collectViewObjectMap(nested, map);
            }
        }
    }

    /**
     * Collects ALL view objects (elements, groups, notes) into a map keyed by ID.
     * Story 8-6: Used for resolving parentViewObjectId and general view object lookup.
     */
    private void collectAllViewObjectMap(IDiagramModelContainer container,
                                          Map<String, IDiagramModelObject> map) {
        for (IDiagramModelObject child : container.getChildren()) {
            map.put(child.getId(), child);
            if (child instanceof IDiagramModelContainer nested) {
                collectAllViewObjectMap(nested, map);
            }
        }
    }

    /**
     * Finds a view object by its ID from the pre-built map.
     */
    private IDiagramModelArchimateObject findViewObjectById(
            Map<String, IDiagramModelArchimateObject> map, String viewObjectId) {
        return map.get(viewObjectId);
    }

    /**
     * Finds the view object representing a given element ID on the view.
     */
    private IDiagramModelArchimateObject findViewObjectForElement(
            Map<String, IDiagramModelArchimateObject> map, String elementId) {
        for (IDiagramModelArchimateObject vo : map.values()) {
            if (vo.getArchimateElement() != null
                    && vo.getArchimateElement().getId().equals(elementId)) {
                return vo;
            }
        }
        return null;
    }

    /**
     * Checks if a connection for the given relationship already exists between
     * the source and target view objects (in either direction).
     *
     * <p>Only {@code getSourceConnections()} is checked on both view objects because
     * every connection appears exactly once in its visual source's source-connections list.
     * A forward connection (source→target) is in {@code source.getSourceConnections()};
     * a reversed connection (target→source) is in {@code target.getSourceConnections()}.
     * Checking {@code getTargetConnections()} would be redundant — it mirrors the other
     * end's source-connections list.</p>
     */
    private boolean hasExistingConnection(IDiagramModelArchimateObject source,
                                           IDiagramModelArchimateObject target,
                                           String relationshipId) {
        // Check source's outgoing connections (covers forward direction)
        for (Object connObj : source.getSourceConnections()) {
            if (connObj instanceof IDiagramModelArchimateConnection conn
                    && conn.getArchimateRelationship() != null
                    && conn.getArchimateRelationship().getId().equals(relationshipId)) {
                return true;
            }
        }
        // Check target's outgoing connections (covers reversed visual direction)
        for (Object connObj : target.getSourceConnections()) {
            if (connObj instanceof IDiagramModelArchimateConnection conn
                    && conn.getArchimateRelationship() != null
                    && conn.getArchimateRelationship().getId().equals(relationshipId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculates an auto-placement position for a new element on a view.
     * Uses grid-based positioning with bounding-box overlap detection.
     *
     * @return int[2] with [x, y] position
     */
    private int[] calculateAutoPlacement(IDiagramModelContainer view,
                                          int elementWidth, int elementHeight) {
        List<IBounds> existingBounds = new ArrayList<>();
        collectAllBounds(view, existingBounds);

        if (existingBounds.isEmpty()) {
            return new int[]{START_X, START_Y};
        }

        // Pass 1: find maxBottomY (lowest edge across all elements)
        int maxBottomY = 0;
        for (IBounds b : existingBounds) {
            int bottom = b.getY() + b.getHeight();
            if (bottom > maxBottomY) {
                maxBottomY = bottom;
            }
        }

        // Pass 2: find bottomRowY (min Y) and rightmost element in the bottom row.
        // Bottom row = elements whose bottom edge is within V_GAP of maxBottomY.
        int bottomRowY = Integer.MAX_VALUE;
        int rightmostX = 0;
        int rightmostWidth = 0;
        for (IBounds b : existingBounds) {
            int bottom = b.getY() + b.getHeight();
            if (bottom >= maxBottomY - V_GAP) {
                // Member of the bottom row
                if (b.getY() < bottomRowY) {
                    bottomRowY = b.getY();
                }
                if (b.getX() > rightmostX
                        || (b.getX() == rightmostX && b.getWidth() > rightmostWidth)) {
                    rightmostX = b.getX();
                    rightmostWidth = b.getWidth();
                }
            }
        }

        // Calculate candidate position
        int candidateX = rightmostX + rightmostWidth + H_GAP;
        int candidateY = bottomRowY;

        // Wrap to new row if exceeds MAX_ROW_WIDTH
        if (candidateX + elementWidth > MAX_ROW_WIDTH) {
            candidateX = START_X;
            candidateY = maxBottomY + V_GAP;
        }

        // Overlap check with retry
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (!overlapsAny(candidateX, candidateY, elementWidth, elementHeight, existingBounds)) {
                return new int[]{candidateX, candidateY};
            }
            // Shift right
            candidateX += H_GAP + DEFAULT_VIEW_OBJECT_WIDTH;
            if (candidateX + elementWidth > MAX_ROW_WIDTH) {
                candidateX = START_X;
                candidateY += elementHeight + V_GAP;
            }
        }

        // Fallback: place below all existing elements
        return new int[]{START_X, maxBottomY + V_GAP};
    }

    /**
     * Recursively collects all diagram object bounds from a container.
     */
    private void collectAllBounds(IDiagramModelContainer container, List<IBounds> bounds) {
        for (IDiagramModelObject child : container.getChildren()) {
            IBounds b = child.getBounds();
            if (b != null) {
                bounds.add(b);
            }
            if (child instanceof IDiagramModelContainer nested) {
                collectAllBounds(nested, bounds);
            }
        }
    }

    /**
     * Checks if a candidate rectangle overlaps any existing bounds.
     */
    private boolean overlapsAny(int cX, int cY, int cW, int cH, List<IBounds> existing) {
        for (IBounds b : existing) {
            // Bounding box collision: NOT (separated on any axis)
            if (!(cX + cW <= b.getX() || b.getX() + b.getWidth() <= cX
                    || cY + cH <= b.getY() || b.getY() + b.getHeight() <= cY)) {
                return true;
            }
        }
        return false;
    }

    // ---- Folder mutation methods (Story 8-5) ----

    @Override
    public MutationResult<FolderDto> createFolder(String sessionId, String parentId, String name,
            String documentation, Map<String, String> properties) {
        logger.info("Creating folder: parentId={}, name={}", parentId, name);
        requireAndCaptureModel();
        try {
            PreparedMutation<FolderDto> prepared = prepareCreateFolder(parentId, name,
                    documentation, properties);

            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Create folder: " + name;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("parentId", parentId);
                proposedChanges.put("name", name);
                if (documentation != null) proposedChanges.put("documentation", documentation);
                ProposalContext ctx = storeAsProposal(sessionId, "create-folder",
                        prepared.command(), prepared.entity(), description,
                        null, proposedChanges, "Parent folder valid. Folder prepared for creation.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Create folder: " + name);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error creating folder '" + name + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<FolderDto> updateFolder(String sessionId, String id, String name,
            String documentation, Map<String, String> properties) {
        logger.info("Updating folder: id={}", id);
        requireAndCaptureModel();
        try {
            PreparedMutation<FolderDto> prepared = prepareUpdateFolder(id, name,
                    documentation, properties);

            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Update folder: " + prepared.entity().name();
                Map<String, Object> currentState = new LinkedHashMap<>();
                Optional<FolderDto> current = getFolderById(id);
                if (current.isPresent()) {
                    currentState.put("name", current.get().name());
                    currentState.put("path", current.get().path());
                }
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                if (name != null) proposedChanges.put("name", name);
                if (documentation != null) proposedChanges.put("documentation", documentation);
                if (properties != null) proposedChanges.put("properties", properties);
                ProposalContext ctx = storeAsProposal(sessionId, "update-folder",
                        prepared.command(), prepared.entity(), description,
                        currentState, proposedChanges, "Folder found. Update prepared.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Update folder: " + id);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error updating folder '" + id + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<MoveResultDto> moveToFolder(String sessionId,
            String objectId, String targetFolderId) {
        logger.info("Moving object to folder: objectId={}, targetFolderId={}", objectId, targetFolderId);
        requireAndCaptureModel();
        try {
            PreparedMutation<MoveResultDto> prepared = prepareMoveToFolder(objectId, targetFolderId);

            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                MoveResultDto dto = prepared.entity();
                String description = "Move " + dto.objectType() + " '" + dto.name()
                        + "' to " + dto.targetFolderPath();
                Map<String, Object> currentState = new LinkedHashMap<>();
                currentState.put("objectId", objectId);
                currentState.put("name", dto.name());
                currentState.put("objectType", dto.objectType());
                currentState.put("currentFolderPath", dto.sourceFolderPath());
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("objectId", objectId);
                proposedChanges.put("targetFolderId", targetFolderId);
                proposedChanges.put("sourceFolderPath", dto.sourceFolderPath());
                proposedChanges.put("targetFolderPath", dto.targetFolderPath());
                ProposalContext ctx = storeAsProposal(sessionId, "move-to-folder",
                        prepared.command(), prepared.entity(), description,
                        currentState, proposedChanges, "Object and target folder valid. Move prepared.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Move " + objectId + " to folder " + targetFolderId);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error moving object '" + objectId + "' to folder '" + targetFolderId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Folder mutation prepare methods (Story 8-5) ----

    PreparedMutation<FolderDto> prepareCreateFolder(String parentId, String name,
            String documentation, Map<String, String> properties) {
        IArchimateModel model = requireAndCaptureModel();

        if (parentId == null || parentId.isBlank()) {
            throw new ModelAccessException(
                    "parentId is required — top-level ArchiMate folders are model-managed",
                    ErrorCode.CANNOT_CREATE_ROOT_FOLDER,
                    null,
                    "Use get-folders to find a parent folder ID to create a subfolder under",
                    null);
        }

        if (name == null || name.isBlank()) {
            throw new ModelAccessException(
                    "Folder name must not be null or blank",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a non-empty name for the new folder",
                    null);
        }

        IFolder parentFolder = FolderOperations.findFolderById(model, parentId);
        if (parentFolder == null) {
            throw new ModelAccessException(
                    "Parent folder not found: " + parentId,
                    ErrorCode.FOLDER_NOT_FOUND,
                    null,
                    "Use get-folders to find valid parent folder IDs",
                    null);
        }

        IFolder newFolder = IArchimateFactory.eINSTANCE.createFolder();
        newFolder.setName(name);
        newFolder.setType(FolderType.USER);

        if (documentation != null) {
            newFolder.setDocumentation(documentation);
        }

        if (properties != null) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
                prop.setKey(entry.getKey());
                prop.setValue(entry.getValue());
                newFolder.getProperties().add(prop);
            }
        }

        Command cmd = new CreateFolderCommand(newFolder, parentFolder);

        // Build path for the new folder (parentPath + "/" + name)
        String parentPath = FolderOperations.buildFolderPath(parentFolder);
        String newPath = parentPath + "/" + name;
        FolderDto dto = new FolderDto(
                newFolder.getId(), newFolder.getName(),
                newFolder.getType().name(), newPath, 0, 0);

        return new PreparedMutation<>(cmd, dto, newFolder.getId());
    }

    PreparedMutation<FolderDto> prepareUpdateFolder(String id, String name,
            String documentation, Map<String, String> properties) {
        IArchimateModel model = requireAndCaptureModel();

        if (name == null && documentation == null && properties == null) {
            throw new ModelAccessException(
                    "No fields to update — provide at least one of: name, documentation, properties",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Specify name, documentation, or properties to update",
                    null);
        }

        if (name != null && name.isBlank()) {
            throw new ModelAccessException(
                    "Folder name must not be blank",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a non-empty name, or omit the name field to leave it unchanged",
                    null);
        }

        IFolder folder = FolderOperations.findFolderById(model, id);
        if (folder == null) {
            throw new ModelAccessException(
                    "Folder not found: " + id,
                    ErrorCode.FOLDER_NOT_FOUND,
                    null,
                    "Use get-folders to find valid folder IDs",
                    null);
        }

        Command cmd = new UpdateFolderCommand(folder, name, documentation, properties);

        // Build the DTO reflecting the proposed new state
        String effectiveName = name != null ? name : folder.getName();
        String effectiveDoc = documentation != null ? documentation : folder.getDocumentation();
        // Rebuild path if name changed
        String path;
        if (name != null) {
            EObject parent = folder.eContainer();
            if (parent instanceof IFolder parentFolder) {
                path = FolderOperations.buildFolderPath(parentFolder) + "/" + effectiveName;
            } else {
                path = effectiveName;
            }
        } else {
            path = FolderOperations.buildFolderPath(folder);
        }

        FolderDto dto = new FolderDto(
                folder.getId(), effectiveName,
                folder.getType().name(), path,
                folder.getElements().size(), folder.getFolders().size());

        return new PreparedMutation<>(cmd, dto, folder.getId());
    }

    PreparedMutation<MoveResultDto> prepareMoveToFolder(String objectId, String targetFolderId) {
        IArchimateModel model = requireAndCaptureModel();

        // Find target folder
        IFolder targetFolder = FolderOperations.findFolderById(model, targetFolderId);
        if (targetFolder == null) {
            throw new ModelAccessException(
                    "Target folder not found: " + targetFolderId,
                    ErrorCode.FOLDER_NOT_FOUND,
                    null,
                    "Use get-folders to find valid target folder IDs",
                    null);
        }

        // Find the object and its parent
        ObjectAndParent found = findObjectAndParent(model, objectId);
        if (found == null) {
            throw new ModelAccessException(
                    "Object not found: " + objectId
                            + ". The ID must refer to an element, relationship, view, or folder.",
                    ErrorCode.OBJECT_NOT_FOUND,
                    null,
                    "Use search-elements or get-folders to find valid object IDs",
                    null);
        }

        EObject object = found.object;
        IFolder sourceFolder = found.parentFolder;
        boolean isFolder = found.isFolder;

        // Reject moving default top-level folders (parentFolder is null for these)
        if (isFolder && sourceFolder == null) {
            throw new ModelAccessException(
                    "Cannot move default ArchiMate folder: " + ((IFolder) object).getName(),
                    ErrorCode.CANNOT_MOVE_DEFAULT_FOLDER,
                    null,
                    "Default ArchiMate layer folders cannot be moved. "
                            + "Only user-created subfolders can be moved.",
                    null);
        }

        // Check already in target
        if (sourceFolder.getId().equals(targetFolder.getId())) {
            throw new ModelAccessException(
                    "Object is already in the target folder: " + FolderOperations.buildFolderPath(targetFolder),
                    ErrorCode.ALREADY_IN_TARGET_FOLDER,
                    null,
                    "The object is already a direct child of the specified folder",
                    null);
        }

        // Check circular folder reference
        if (isFolder && isOrDescendsFrom(targetFolder, (IFolder) object)) {
            throw new ModelAccessException(
                    "Cannot move folder into its own subtree — this would create a circular reference",
                    ErrorCode.CIRCULAR_FOLDER_REFERENCE,
                    null,
                    "Choose a target folder that is not a descendant of the folder being moved",
                    null);
        }

        // Views can only live within the Views (DIAGRAMS) hierarchy
        if (object instanceof IArchimateDiagramModel) {
            IFolder diagramsRoot = model.getFolder(FolderType.DIAGRAMS);
            if (diagramsRoot != null
                    && !targetFolder.getId().equals(diagramsRoot.getId())
                    && !isOrDescendsFrom(targetFolder, diagramsRoot)) {
                throw new ModelAccessException(
                        "Views can only be moved within the Views folder hierarchy",
                        ErrorCode.INVALID_MOVE_TARGET,
                        null,
                        "Choose a target folder that is within the Views hierarchy. "
                                + "Use get-folders to find folders under the Views root.",
                        null);
            }
        }

        // Determine source index for undo
        int sourceIndex;
        if (isFolder) {
            sourceIndex = sourceFolder.getFolders().indexOf((IFolder) object);
        } else {
            sourceIndex = sourceFolder.getElements().indexOf(object);
        }

        Command cmd = new MoveToFolderCommand(object, isFolder, sourceFolder, sourceIndex,
                targetFolder);

        // Build result DTO
        String objectName;
        String objectType;
        String elementType = null;

        if (isFolder) {
            objectName = ((IFolder) object).getName();
            objectType = "Folder";
        } else if (object instanceof IArchimateElement element) {
            objectName = element.getName();
            objectType = "Element";
            elementType = element.eClass().getName();
        } else if (object instanceof IArchimateRelationship rel) {
            objectName = rel.getName() != null ? rel.getName() : rel.eClass().getName();
            objectType = "Relationship";
        } else if (object instanceof IArchimateDiagramModel view) {
            objectName = view.getName();
            objectType = "View";
        } else {
            objectName = objectId;
            objectType = "Unknown";
        }

        MoveResultDto dto = new MoveResultDto(
                objectId, objectName, objectType, elementType,
                FolderOperations.buildFolderPath(sourceFolder), FolderOperations.buildFolderPath(targetFolder));

        return new PreparedMutation<>(cmd, dto, objectId);
    }

    /**
     * Finds an object by ID across all model folders and determines its parent
     * folder and whether it is itself a folder.
     *
     * <p>Searches elements (including relationships stored in element lists),
     * views, and subfolders across the entire model hierarchy. Uses
     * {@code ArchimateModelUtils.getObjectByID()} for elements/relationships/views,
     * then uses {@code eContainer()} to determine the parent folder.</p>
     *
     * @return ObjectAndParent if found, null otherwise
     */
    private ObjectAndParent findObjectAndParent(IArchimateModel model, String objectId) {
        // Try as a folder first
        IFolder asFolder = FolderOperations.findFolderById(model, objectId);
        if (asFolder != null) {
            EObject container = asFolder.eContainer();
            if (container instanceof IFolder parentFolder) {
                return new ObjectAndParent(asFolder, parentFolder, true);
            }
            // Top-level folder — parent is model, not a folder
            // We still return it; the caller will reject moving default folders
            return new ObjectAndParent(asFolder, null, true);
        }

        // Try as element, relationship, or view via ArchimateModelUtils
        EObject obj = ArchimateModelUtils.getObjectByID(model, objectId);
        if (obj != null) {
            EObject container = obj.eContainer();
            if (container instanceof IFolder parentFolder) {
                return new ObjectAndParent(obj, parentFolder, false);
            }
        }

        return null;
    }

    /**
     * Checks whether {@code folder} is the same as, or descends from,
     * {@code ancestor}. Used to detect circular folder moves — if the move
     * target is a descendant of the folder being moved, the move would
     * create a cycle.
     *
     * @param folder   the folder to test (typically the move-target folder)
     * @param ancestor the folder that might be an ancestor (typically the folder being moved)
     * @return true if {@code folder} equals or descends from {@code ancestor}
     */
    private boolean isOrDescendsFrom(IFolder folder, IFolder ancestor) {
        if (folder.getId().equals(ancestor.getId())) {
            return true;
        }
        EObject current = folder.eContainer();
        while (current instanceof IFolder parentFolder) {
            if (parentFolder.getId().equals(ancestor.getId())) {
                return true;
            }
            current = parentFolder.eContainer();
        }
        return false;
    }

    /**
     * Result of finding an object and its parent folder.
     * Package-visible for testing.
     */
    static final class ObjectAndParent {
        final EObject object;
        final IFolder parentFolder;
        final boolean isFolder;

        ObjectAndParent(EObject object, IFolder parentFolder, boolean isFolder) {
            this.object = object;
            this.parentFolder = parentFolder;
            this.isFolder = isFolder;
        }
    }

    // ---- Bulk mutation (Story 7-5) ----

    private static final Pattern BACK_REFERENCE_PATTERN = Pattern.compile("\\$(\\d+)\\.id");
    private static final Set<String> CREATE_TOOLS = Set.of(
            "create-element", "create-relationship", "create-view",
            "add-to-view", "add-connection-to-view",
            "add-group-to-view", "add-note-to-view");

    @Override
    public BulkMutationResult executeBulk(String sessionId, List<BulkOperation> operations,
            String description, boolean continueOnError) {
        logger.info("Executing bulk mutation: {} operations, continueOnError={}",
                operations.size(), continueOnError);
        requireAndCaptureModel();

        try {
            // Phase 1: Validate all operations and build commands (Jetty thread)
            List<PreparedMutation<?>> preparedMutations = new ArrayList<>();
            List<BulkOperationResult> operationResults = new ArrayList<>();
            List<BulkOperationFailure> failedResults = new ArrayList<>();
            // Tracks EMF element objects for back-reference resolution
            Map<Integer, IArchimateElement> createdElements = new LinkedHashMap<>();
            // Tracks EMF view objects for view-level back-reference resolution (Story 8-0b)
            Map<Integer, IDiagramModelArchimateObject> createdViewObjects = new LinkedHashMap<>();
            // Tracks raw relationships for cross-level back-reference (C1 fix)
            Map<Integer, IArchimateRelationship> createdRelationships = new LinkedHashMap<>();
            // Tracks raw view connections for back-reference by update-view-connection (H1 fix)
            Map<Integer, IDiagramModelArchimateConnection> createdViewConnections = new LinkedHashMap<>();
            // Tracks EMF group objects for parentViewObjectId resolution in add-to-view (Story 9-0g)
            Map<Integer, IDiagramModelGroup> createdGroups = new LinkedHashMap<>();
            Map<Integer, String> createdEntityIds = new LinkedHashMap<>();
            Map<Integer, String> operationTools = new LinkedHashMap<>();
            // Tracks failed operation indices for back-reference cascade (Story 11-9)
            Set<Integer> failedIndices = new HashSet<>();

            for (int i = 0; i < operations.size(); i++) {
                BulkOperation op = operations.get(i);
                operationTools.put(i, op.tool());

                try {
                    // Check for back-reference cascade: if this operation references a failed one
                    if (continueOnError && !failedIndices.isEmpty()) {
                        String cascadeError = checkBackReferenceCascade(
                                op.params(), i, failedIndices);
                        if (cascadeError != null) {
                            failedIndices.add(i);
                            failedResults.add(new BulkOperationFailure(
                                    i, op.tool(), "BACK_REFERENCE_FAILED",
                                    cascadeError,
                                    "Fix the referenced operation first, or remove the dependency"));
                            continue;
                        }
                    }

                    // Resolve back-references in params
                    Map<String, Object> resolvedParams = resolveBackReferences(
                            op.params(), i, createdEntityIds, operationTools);

                    PreparedMutation<?> prepared = prepareOperation(
                            op.tool(), resolvedParams, i, createdElements,
                            createdViewObjects, createdRelationships,
                            createdViewConnections, createdGroups);
                    preparedMutations.add(prepared);

                    // Store for future back-references
                    createdEntityIds.put(i, prepared.entityId());

                    // Build per-operation result
                    String action = resolveActionString(op.tool());
                    BulkOperationResult opResult = buildOperationResult(
                            i, op.tool(), action, prepared);
                    operationResults.add(opResult);

                } catch (ModelAccessException e) {
                    if (!continueOnError) {
                        throw new ModelAccessException(
                                "Operation " + i + " (" + op.tool() + "): " + e.getMessage(),
                                ErrorCode.BULK_VALIDATION_FAILED,
                                "failedOperationIndex=" + i + ", failedTool=" + op.tool(),
                                e.getSuggestedCorrection() != null
                                        ? e.getSuggestedCorrection()
                                        : "Fix the failed operation and retry the entire bulk-mutate call",
                                e.getArchiMateReference());
                    }
                    // continueOnError: record failure and continue
                    failedIndices.add(i);
                    failedResults.add(new BulkOperationFailure(
                            i, op.tool(),
                            e.getErrorCode() != null ? e.getErrorCode().name() : "UNKNOWN",
                            e.getMessage(),
                            e.getSuggestedCorrection()));
                }
            }

            // If continueOnError and ALL operations failed, return without dispatching
            if (continueOnError && operationResults.isEmpty()) {
                logger.warn("Bulk mutation: all {} operations failed, no model change",
                        operations.size());
                return new BulkMutationResult(
                        List.of(),
                        List.copyOf(failedResults),
                        operations.size(),
                        false,
                        null,
                        null);
            }

            // Phase 2: Build compound command and dispatch (UI thread)
            int succeededCount = operationResults.size();
            int totalCount = operations.size();
            String label;
            if (description != null && !description.isBlank()) {
                label = continueOnError && !failedResults.isEmpty()
                        ? description + " (" + succeededCount + "/" + totalCount + " operations)"
                        : description;
            } else {
                label = continueOnError && !failedResults.isEmpty()
                        ? "Bulk mutation (" + succeededCount + "/" + totalCount + " operations)"
                        : "Bulk mutation (" + totalCount + " operations)";
            }

            NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(label);
            for (PreparedMutation<?> pm : preparedMutations) {
                compound.add(pm.command());
            }

            // Approval gate (Story 7-6): store compound as single proposal
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("operationCount", succeededCount);
                List<String> opSummaries = new ArrayList<>();
                for (BulkOperationResult opResult : operationResults) {
                    opSummaries.add(opResult.index() + ": " + opResult.tool());
                }
                proposedChanges.put("operations", opSummaries);
                if (!failedResults.isEmpty()) {
                    List<String> failSummaries = new ArrayList<>();
                    for (BulkOperationFailure f : failedResults) {
                        failSummaries.add(f.index() + ": " + f.tool() + " — " + f.message());
                    }
                    proposedChanges.put("failedOperations", failSummaries);
                }
                String validationMsg = failedResults.isEmpty()
                        ? "All " + totalCount + " operations validated successfully."
                        : succeededCount + " of " + totalCount + " operations validated successfully. "
                                + failedResults.size() + " failed validation.";
                ProposalContext ctx = storeAsProposal(sessionId, "bulk-mutate",
                        compound, operationResults, label,
                        null, proposedChanges, validationMsg);
                return new BulkMutationResult(
                        List.copyOf(operationResults),
                        List.copyOf(failedResults),
                        totalCount,
                        failedResults.isEmpty(),
                        null,
                        ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new BulkMutationResult(
                    List.copyOf(operationResults),
                    List.copyOf(failedResults),
                    totalCount,
                    failedResults.isEmpty(),
                    batchSeq,
                    null);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error executing bulk mutation",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Resolves $N.id back-references in parameter values.
     */
    private Map<String, Object> resolveBackReferences(
            Map<String, Object> params, int currentIndex,
            Map<Integer, String> createdEntityIds,
            Map<Integer, String> operationTools) {

        Map<String, Object> resolved = new LinkedHashMap<>(params);
        for (Map.Entry<String, Object> entry : resolved.entrySet()) {
            if (entry.getValue() instanceof String strValue) {
                Matcher matcher = BACK_REFERENCE_PATTERN.matcher(strValue);
                if (matcher.matches()) {
                    int refIndex = Integer.parseInt(matcher.group(1));
                    validateBackReference(refIndex, currentIndex, operationTools);
                    entry.setValue(createdEntityIds.get(refIndex));
                }
            }
        }
        return resolved;
    }

    /**
     * Validates a back-reference index.
     */
    private void validateBackReference(int refIndex, int currentIndex,
            Map<Integer, String> operationTools) {
        if (refIndex >= currentIndex) {
            throw new ModelAccessException(
                    "Back-reference '$" + refIndex + ".id' references a future operation "
                            + "(index " + refIndex + ", current is " + currentIndex + ")",
                    ErrorCode.INVALID_PARAMETER);
        }
        if (refIndex < 0 || !operationTools.containsKey(refIndex)) {
            throw new ModelAccessException(
                    "Invalid back-reference '$" + refIndex + ".id' — only "
                            + currentIndex + " previous operations available",
                    ErrorCode.INVALID_PARAMETER);
        }
        String refTool = operationTools.get(refIndex);
        if (!CREATE_TOOLS.contains(refTool)) {
            throw new ModelAccessException(
                    "Back-reference '$" + refIndex + ".id' targets an " + refTool
                            + " operation — only create operations can be referenced",
                    ErrorCode.INVALID_PARAMETER);
        }
    }

    /**
     * Checks if any back-reference in the params targets a failed operation (Story 11-9).
     * Returns an error message if a cascade failure is detected, null otherwise.
     */
    private String checkBackReferenceCascade(
            Map<String, Object> params, int currentIndex,
            Set<Integer> failedIndices) {

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() instanceof String strValue) {
                Matcher matcher = BACK_REFERENCE_PATTERN.matcher(strValue);
                if (matcher.matches()) {
                    int refIndex = Integer.parseInt(matcher.group(1));
                    if (failedIndices.contains(refIndex)) {
                        return "Back-reference $" + refIndex
                                + ".id unavailable \u2014 operation " + refIndex + " failed";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Dispatches a single operation to the appropriate prepare method.
     */
    private PreparedMutation<?> prepareOperation(String tool, Map<String, Object> params,
            int operationIndex, Map<Integer, IArchimateElement> createdElements,
            Map<Integer, IDiagramModelArchimateObject> createdViewObjects,
            Map<Integer, IArchimateRelationship> createdRelationships,
            Map<Integer, IDiagramModelArchimateConnection> createdViewConnections,
            Map<Integer, IDiagramModelGroup> createdGroups) {
        return switch (tool) {
            case "create-element" -> {
                String type = requireParam(params, "type");
                String name = requireParam(params, "name");
                String documentation = optionalParam(params, "documentation");
                Map<String, String> properties = optionalStringMap(params, "properties");
                String folderId = optionalParam(params, "folderId");

                PreparedMutation<ElementDto> prepared = prepareCreateElement(
                        type, name, documentation, properties, folderId);

                // Store raw EMF element for back-reference by create-relationship
                if (prepared.rawObject() instanceof IArchimateElement element) {
                    createdElements.put(operationIndex, element);
                }
                yield prepared;
            }
            case "create-relationship" -> {
                String type = requireParam(params, "type");
                String sourceId = requireParam(params, "sourceId");
                String targetId = requireParam(params, "targetId");
                String name = optionalParam(params, "name");

                // Check if source/target are back-referenced elements (not yet in model)
                IArchimateElement sourceElement = findBackReferencedElement(
                        sourceId, createdElements);
                IArchimateElement targetElement = findBackReferencedElement(
                        targetId, createdElements);

                PreparedMutation<RelationshipDto> prepared;
                if (sourceElement != null && targetElement != null) {
                    prepared = prepareCreateRelationshipDirect(
                            type, sourceElement, targetElement, name);
                } else if (sourceElement != null) {
                    // Source is back-ref, target is in model — need to look up target
                    IArchimateModel model = requireAndCaptureModel();
                    EObject targetObj = ArchimateModelUtils.getObjectByID(model, targetId);
                    if (!(targetObj instanceof IArchimateElement target)) {
                        throw new ModelAccessException(
                                "Target element not found: " + targetId,
                                ErrorCode.TARGET_ELEMENT_NOT_FOUND);
                    }
                    prepared = prepareCreateRelationshipDirect(
                            type, sourceElement, target, name);
                } else if (targetElement != null) {
                    // Target is back-ref, source is in model
                    IArchimateModel model = requireAndCaptureModel();
                    EObject sourceObj = ArchimateModelUtils.getObjectByID(model, sourceId);
                    if (!(sourceObj instanceof IArchimateElement source)) {
                        throw new ModelAccessException(
                                "Source element not found: " + sourceId,
                                ErrorCode.SOURCE_ELEMENT_NOT_FOUND);
                    }
                    prepared = prepareCreateRelationshipDirect(
                            type, source, targetElement, name);
                } else {
                    // Both are existing model elements — standard path
                    prepared = prepareCreateRelationship(type, sourceId, targetId, name);
                }
                // Store raw relationship for cross-level back-reference (C1 fix)
                if (prepared.rawObject() instanceof IArchimateRelationship rel) {
                    createdRelationships.put(operationIndex, rel);
                }
                yield prepared;
            }
            case "create-view" -> {
                String name = requireParam(params, "name");
                String viewpoint = optionalParam(params, "viewpoint");
                String folderId = optionalParam(params, "folderId");
                String connectionRouterType = optionalParam(params, "connectionRouterType");
                yield prepareCreateView(name, viewpoint, folderId, connectionRouterType);
            }
            case "update-element" -> {
                String id = requireParam(params, "id");
                String name = optionalParam(params, "name");
                String documentation = optionalParam(params, "documentation");
                Map<String, String> properties = optionalStringMapWithNulls(params, "properties");
                yield prepareUpdateElement(id, name, documentation, properties);
            }
            case "update-view" -> {
                String viewId = requireParam(params, "viewId");
                String name = optionalParam(params, "name");
                String viewpoint = optionalParam(params, "viewpoint");
                // Viewpoint clear semantics: empty string means "clear viewpoint".
                // Cross-ref: same logic in ViewHandler.handleUpdateView() and
                // ArchiModelAccessorImpl.prepareUpdateView() which converts "" to clearViewpoint=true.
                if (params.containsKey("viewpoint") && "".equals(params.get("viewpoint"))) {
                    viewpoint = "";
                }
                String documentation = optionalParam(params, "documentation");
                Map<String, String> properties = optionalStringMapWithNulls(params, "properties");
                String connectionRouterType = optionalParam(params, "connectionRouterType");
                yield prepareUpdateView(viewId, name, viewpoint, documentation, properties,
                        connectionRouterType);
            }
            case "add-to-view" -> {
                String viewId = requireParam(params, "viewId");
                String elementId = requireParam(params, "elementId");
                Integer x = optionalIntParam(params, "x");
                Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width");
                Integer height = optionalIntParam(params, "height");
                String parentViewObjectId = optionalParam(params, "parentViewObjectId");
                StylingParams bulkStyling = extractBulkStylingParams(params);

                // Story 10-20: Resolve batch-created parent container (group or element
                // created earlier in this batch via add-group-to-view or add-to-view)
                IDiagramModelContainer batchParent = findBatchCreatedParentContainer(
                        parentViewObjectId, createdGroups, createdViewObjects);

                // Check if elementId is a back-referenced element (not yet in model)
                IArchimateElement backRefElement = findBackReferencedElement(
                        elementId, createdElements);

                PreparedMutation<AddToViewResultDto> addToViewPrepared;
                if (backRefElement != null) {
                    addToViewPrepared = prepareAddToViewDirect(viewId, backRefElement,
                            x, y, width, height,
                            batchParent != null ? null : parentViewObjectId,
                            batchParent, bulkStyling);
                } else {
                    // autoConnect forced false in bulk context
                    addToViewPrepared = prepareAddToView(viewId, elementId,
                            x, y, width, height, false,
                            batchParent != null ? null : parentViewObjectId,
                            batchParent, bulkStyling);
                }

                // Store raw view object for back-reference by add-connection-to-view
                if (addToViewPrepared.rawObject() instanceof IDiagramModelArchimateObject viewObj) {
                    createdViewObjects.put(operationIndex, viewObj);
                }
                yield addToViewPrepared;
            }
            case "add-connection-to-view" -> {
                String viewId = requireParam(params, "viewId");
                String relationshipId = requireParam(params, "relationshipId");
                String sourceViewObjectId = requireParam(params, "sourceViewObjectId");
                String targetViewObjectId = requireParam(params, "targetViewObjectId");
                List<BendpointDto> bendpoints = parseBendpoints(params);
                List<AbsoluteBendpointDto> absoluteBendpoints = parseAbsoluteBendpoints(params);
                validateBendpointMutualExclusion(bendpoints, absoluteBendpoints);

                // C1 fix: check if relationship is a back-referenced relationship
                IArchimateRelationship directRelationship = findBackReferencedRelationship(
                        relationshipId, createdRelationships);

                // Check if source/target are back-referenced view objects
                IDiagramModelArchimateObject sourceViewObj = findBackReferencedViewObject(
                        sourceViewObjectId, createdViewObjects);
                IDiagramModelArchimateObject targetViewObj = findBackReferencedViewObject(
                        targetViewObjectId, createdViewObjects);

                PreparedMutation<ViewConnectionDto> connPrepared;
                if (sourceViewObj != null || targetViewObj != null || directRelationship != null) {
                    connPrepared = prepareAddConnectionToViewDirect(
                            viewId, relationshipId,
                            sourceViewObj, sourceViewObjectId,
                            targetViewObj, targetViewObjectId,
                            bendpoints, absoluteBendpoints, directRelationship);
                } else {
                    connPrepared = prepareAddConnectionToView(
                            viewId, relationshipId,
                            sourceViewObjectId, targetViewObjectId,
                            bendpoints, absoluteBendpoints);
                }
                // H1 fix: store raw view connection for back-reference by update-view-connection
                if (connPrepared.rawObject() instanceof IDiagramModelArchimateConnection conn) {
                    createdViewConnections.put(operationIndex, conn);
                }
                yield connPrepared;
            }
            case "remove-from-view" -> {
                String viewId = requireParam(params, "viewId");
                String viewObjectId = requireParam(params, "viewObjectId");
                yield prepareRemoveFromView(viewId, viewObjectId);
            }
            case "update-view-object" -> {
                String viewObjectId = requireParam(params, "viewObjectId");
                Integer x = optionalIntParam(params, "x");
                Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width");
                Integer height = optionalIntParam(params, "height");
                String text = optionalParam(params, "text");
                StylingParams voStyling = extractBulkStylingParams(params);

                // H2 fix: check if viewObjectId is a back-referenced view object
                IDiagramModelArchimateObject backRefViewObj = findBackReferencedViewObject(
                        viewObjectId, createdViewObjects);
                if (backRefViewObj != null) {
                    yield prepareUpdateViewObjectDirect(backRefViewObj, x, y, width, height);
                }
                yield prepareUpdateViewObject(viewObjectId, x, y, width, height, text, voStyling);
            }
            case "update-view-connection" -> {
                String viewConnectionId = requireParam(params, "viewConnectionId");
                List<BendpointDto> bendpoints = parseBendpoints(params);
                List<AbsoluteBendpointDto> absoluteBendpoints = parseAbsoluteBendpoints(params);
                validateBendpointMutualExclusion(bendpoints, absoluteBendpoints);
                StylingParams connStyling = extractBulkStylingParams(params);

                // Neither format provided means clear bendpoints (consistent with handler path)
                if (bendpoints == null && absoluteBendpoints == null && connStyling == null) {
                    bendpoints = List.of();
                }

                // H1 fix: check if viewConnectionId is a back-referenced view connection
                IDiagramModelArchimateConnection backRefConn = findBackReferencedViewConnection(
                        viewConnectionId, createdViewConnections);
                if (backRefConn != null) {
                    yield prepareUpdateViewConnectionDirect(backRefConn, bendpoints,
                            absoluteBendpoints);
                }
                yield prepareUpdateViewConnection(viewConnectionId, bendpoints,
                        absoluteBendpoints, connStyling);
            }
            case "clear-view" -> {
                String viewId = requireParam(params, "viewId");
                yield prepareClearView(viewId);
            }
            case "add-group-to-view" -> {
                String viewId = requireParam(params, "viewId");
                String label = requireParam(params, "label");
                Integer x = optionalIntParam(params, "x");
                Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width");
                Integer height = optionalIntParam(params, "height");
                String parentVoId = optionalParam(params, "parentViewObjectId");
                StylingParams groupStyling = extractBulkStylingParams(params);

                // Story 10-20: Resolve batch-created parent container (group or element)
                IDiagramModelContainer batchParent = findBatchCreatedParentContainer(
                        parentVoId, createdGroups, createdViewObjects);

                PreparedMutation<ViewGroupDto> groupPrepared =
                        prepareAddGroupToView(viewId, label, x, y, width, height,
                                batchParent != null ? null : parentVoId,
                                batchParent, groupStyling);
                // Story 9-0g: Track group for parentViewObjectId resolution in add-to-view
                if (groupPrepared.rawObject() instanceof IDiagramModelGroup group) {
                    createdGroups.put(operationIndex, group);
                }
                yield groupPrepared;
            }
            case "add-note-to-view" -> {
                String viewId = requireParam(params, "viewId");
                String content = requireParam(params, "content");
                Integer x = optionalIntParam(params, "x");
                Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width");
                Integer height = optionalIntParam(params, "height");
                String parentVoId = optionalParam(params, "parentViewObjectId");
                StylingParams noteStyling = extractBulkStylingParams(params);

                // Story 10-20: Resolve batch-created parent container (group or element)
                IDiagramModelContainer batchParent = findBatchCreatedParentContainer(
                        parentVoId, createdGroups, createdViewObjects);

                yield prepareAddNoteToView(viewId, content, x, y, width, height,
                        batchParent != null ? null : parentVoId,
                        batchParent, noteStyling);
            }
            case "delete-element" -> {
                String elementId = requireParam(params, "elementId");
                yield prepareDeleteElement(elementId);
            }
            case "delete-relationship" -> {
                String relationshipId = requireParam(params, "relationshipId");
                yield prepareDeleteRelationship(relationshipId);
            }
            case "delete-view" -> {
                String viewId = requireParam(params, "viewId");
                yield prepareDeleteView(viewId);
            }
            case "delete-folder" -> {
                String folderId = requireParam(params, "folderId");
                boolean force = Boolean.TRUE.equals(optionalBoolParam(params, "force"));
                yield prepareDeleteFolder(folderId, force);
            }
            case "create-folder" -> {
                String parentId = requireParam(params, "parentId");
                String name = requireParam(params, "name");
                String documentation = optionalParam(params, "documentation");
                Map<String, String> properties = optionalStringMap(params, "properties");
                yield prepareCreateFolder(parentId, name, documentation, properties);
            }
            case "update-folder" -> {
                String id = requireParam(params, "id");
                String name = optionalParam(params, "name");
                String documentation = optionalParam(params, "documentation");
                Map<String, String> properties = optionalStringMapWithNulls(params, "properties");
                yield prepareUpdateFolder(id, name, documentation, properties);
            }
            case "move-to-folder" -> {
                String objectId = requireParam(params, "objectId");
                String targetFolderId = requireParam(params, "targetFolderId");
                yield prepareMoveToFolder(objectId, targetFolderId);
            }
            default -> throw new ModelAccessException(
                    "Unsupported tool '" + tool + "'. Supported: "
                            + BulkOperation.SUPPORTED_TOOLS,
                    ErrorCode.INVALID_PARAMETER);
        };
    }

    /**
     * Finds an EMF element from the back-reference map by entity ID.
     * Returns null if the ID doesn't match any back-referenced element.
     */
    private IArchimateElement findBackReferencedElement(String entityId,
            Map<Integer, IArchimateElement> createdElements) {
        for (IArchimateElement element : createdElements.values()) {
            if (element.getId().equals(entityId)) {
                return element;
            }
        }
        return null;
    }

    /**
     * Finds an EMF relationship from the back-reference map by entity ID.
     * Returns null if the ID doesn't match any back-referenced relationship.
     */
    private IArchimateRelationship findBackReferencedRelationship(String entityId,
            Map<Integer, IArchimateRelationship> createdRelationships) {
        for (IArchimateRelationship rel : createdRelationships.values()) {
            if (rel.getId().equals(entityId)) {
                return rel;
            }
        }
        return null;
    }

    /**
     * Finds an EMF view connection from the back-reference map by entity ID.
     * Returns null if the ID doesn't match any back-referenced view connection.
     */
    private IDiagramModelArchimateConnection findBackReferencedViewConnection(String entityId,
            Map<Integer, IDiagramModelArchimateConnection> createdViewConnections) {
        for (IDiagramModelArchimateConnection conn : createdViewConnections.values()) {
            if (conn.getId().equals(entityId)) {
                return conn;
            }
        }
        return null;
    }

    /**
     * Finds an EMF view object from the back-reference map by entity ID.
     * Returns null if the ID doesn't match any back-referenced view object.
     */
    private IDiagramModelArchimateObject findBackReferencedViewObject(String entityId,
            Map<Integer, IDiagramModelArchimateObject> createdViewObjects) {
        for (IDiagramModelArchimateObject viewObj : createdViewObjects.values()) {
            if (viewObj.getId().equals(entityId)) {
                return viewObj;
            }
        }
        return null;
    }

    /**
     * Finds a batch-created parent container (group or element view object) from tracking maps.
     * Checks both createdGroups and createdViewObjects to enable element-to-element nesting
     * within a single bulk-mutate batch (Story 10-20).
     */
    private IDiagramModelContainer findBatchCreatedParentContainer(
            String parentId,
            Map<Integer, IDiagramModelGroup> createdGroups,
            Map<Integer, IDiagramModelArchimateObject> createdViewObjects) {
        if (parentId == null) {
            return null;
        }
        for (IDiagramModelGroup group : createdGroups.values()) {
            if (group.getId().equals(parentId)) {
                return group;
            }
        }
        for (IDiagramModelArchimateObject vo : createdViewObjects.values()) {
            if (vo.getId().equals(parentId)) {
                return vo;
            }
        }
        return null;
    }

    /**
     * Resolves the parent container for nesting a view object.
     * Handles three cases: pre-resolved batch parent, existing view object lookup, or view root.
     * Validates that the parent is a group or element (rejects notes and connections).
     */
    private IDiagramModelContainer resolveParentContainer(
            IArchimateDiagramModel view,
            String parentViewObjectId,
            IDiagramModelContainer batchParentContainer) {
        if (batchParentContainer != null) {
            return batchParentContainer;
        } else if (parentViewObjectId != null) {
            Map<String, IDiagramModelObject> allObjectMap = new LinkedHashMap<>();
            collectAllViewObjectMap(view, allObjectMap);
            IDiagramModelObject parentObj = allObjectMap.get(parentViewObjectId);
            if (parentObj == null) {
                throw new ModelAccessException(
                        "Parent view object not found: " + parentViewObjectId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Use get-view-contents to find valid group or element viewObjectIds",
                        null);
            }
            if (!(parentObj instanceof IDiagramModelGroup)
                    && !(parentObj instanceof IDiagramModelArchimateObject)) {
                throw new ModelAccessException(
                        "Parent view object must be a group or element: " + parentViewObjectId,
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "parentViewObjectId must reference a group or element view object, not a "
                                + parentObj.getClass().getSimpleName(),
                        null);
            }
            return (IDiagramModelContainer) parentObj;
        } else {
            return view;
        }
    }

    /**
     * Extracts an optional Integer parameter from a bulk operation params map.
     * JSON numbers may arrive as Integer, Long, or Double — handle all numeric types.
     */
    private Integer optionalIntParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Number num) {
            return num.intValue();
        }
        return null;
    }

    /**
     * Parses an optional bendpoints array from bulk operation params into a list of BendpointDto.
     * Returns null if absent or empty (consistent with handler-level extraction).
     */
    private List<BendpointDto> parseBendpoints(Map<String, Object> params) {
        Object raw = params.get("bendpoints");
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return null;
        }
        List<BendpointDto> result = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (item instanceof Map<?, ?> map) {
                int startX = numFromMap(map, "startX");
                int startY = numFromMap(map, "startY");
                int endX = numFromMap(map, "endX");
                int endY = numFromMap(map, "endY");
                result.add(new BendpointDto(startX, startY, endX, endY));
            } else {
                throw new ModelAccessException(
                        "Bendpoint[" + i + "] must be an object with startX, startY, endX, endY",
                        ErrorCode.INVALID_PARAMETER);
            }
        }
        return result;
    }

    /**
     * Parses optional absolute bendpoints from bulk operation parameters.
     * Each item must have {x, y} integer fields.
     * Returns null if absent or empty (consistent with handler-level extraction).
     */
    private List<AbsoluteBendpointDto> parseAbsoluteBendpoints(Map<String, Object> params) {
        Object raw = params.get("absoluteBendpoints");
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return null;
        }
        List<AbsoluteBendpointDto> result = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (item instanceof Map<?, ?> map) {
                int x = numFromMap(map, "x");
                int y = numFromMap(map, "y");
                result.add(new AbsoluteBendpointDto(x, y));
            } else {
                throw new ModelAccessException(
                        "absoluteBendpoints[" + i + "] must be an object with x, y",
                        ErrorCode.INVALID_PARAMETER);
            }
        }
        return result;
    }

    private static int numFromMap(Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (val == null) {
            throw new ModelAccessException(
                    "Missing required bendpoint field: " + key,
                    ErrorCode.INVALID_PARAMETER);
        }
        if (!(val instanceof Number num)) {
            throw new ModelAccessException(
                    "Bendpoint field '" + key + "' must be a number, got: "
                            + val.getClass().getSimpleName(),
                    ErrorCode.INVALID_PARAMETER);
        }
        return num.intValue();
    }

    /**
     * Prepares an add-to-view mutation with a direct element reference (for bulk back-references).
     * Accepts a raw IArchimateElement instead of looking up by ID from the model.
     * Optional batchParentGroup for nesting inside a group created in the same batch (Story 9-0g).
     * Optional parentViewObjectId for nesting inside an existing group on the view.
     */
    private PreparedMutation<AddToViewResultDto> prepareAddToViewDirect(
            String viewId, IArchimateElement element, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer, StylingParams styling) {
        IArchimateModel model = requireAndCaptureModel();

        // Validate x/y both-or-neither
        if ((x == null) != (y == null)) {
            throw new ModelAccessException(
                    "Both x and y must be specified together, or both omitted for auto-placement",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide both x and y coordinates, or omit both for auto-placement",
                    null);
        }

        // Find view
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Resolve dimensions
        int resolvedWidth = (width != null) ? width : DEFAULT_VIEW_OBJECT_WIDTH;
        int resolvedHeight = (height != null) ? height : DEFAULT_VIEW_OBJECT_HEIGHT;

        // Resolve position
        int resolvedX;
        int resolvedY;
        if (x != null) {
            resolvedX = x;
            resolvedY = y;
        } else {
            int[] pos = calculateAutoPlacement(view, resolvedWidth, resolvedHeight);
            resolvedX = pos[0];
            resolvedY = pos[1];
        }

        // Create diagram object
        IDiagramModelArchimateObject diagramObj =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        diagramObj.setArchimateElement(element);
        diagramObj.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Apply styling at creation time (consistent with prepareAddToView)
        StylingHelper.applyStylingToNewObject(diagramObj, styling);

        // Build view object DTO with styling
        ViewObjectDto viewObjectDto = new ViewObjectDto(
                diagramObj.getId(), element.getId(), element.getName(),
                element.eClass().getName(), resolvedX, resolvedY,
                resolvedWidth, resolvedHeight,
                StylingHelper.readFillColor(diagramObj), StylingHelper.readLineColor(diagramObj),
                StylingHelper.readFontColor(diagramObj), StylingHelper.readOpacity(diagramObj),
                StylingHelper.readLineWidth(diagramObj));

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer);

        // autoConnect forced false for bulk (no connection scanning)
        Command cmd = new AddToViewCommand(diagramObj, parentContainer);

        AddToViewResultDto resultDto = new AddToViewResultDto(
                viewObjectDto, null, null);
        return new PreparedMutation<>(cmd, resultDto, diagramObj.getId(), diagramObj);
    }

    /**
     * Prepares an add-connection-to-view mutation with direct view object references
     * (for bulk back-references). Hybrid mode: accepts raw IDiagramModelArchimateObject
     * for source/target that are back-referenced, or null to look up from the diagram.
     */
    private PreparedMutation<ViewConnectionDto> prepareAddConnectionToViewDirect(
            String viewId, String relationshipId,
            IDiagramModelArchimateObject directSource, String sourceViewObjectId,
            IDiagramModelArchimateObject directTarget, String targetViewObjectId,
            List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints,
            IArchimateRelationship directRelationship) {
        IArchimateModel model = requireAndCaptureModel();

        // Find view
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel view)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        // Find relationship — use direct reference if available (C1 fix), else look up
        IArchimateRelationship relationship;
        if (directRelationship != null) {
            relationship = directRelationship;
        } else {
            EObject relObj = ArchimateModelUtils.getObjectByID(model, relationshipId);
            if (!(relObj instanceof IArchimateRelationship foundRel)) {
                throw new ModelAccessException(
                        "Relationship not found: " + relationshipId,
                        ErrorCode.RELATIONSHIP_NOT_FOUND,
                        null,
                        "Use get-relationships to find valid relationship IDs",
                        null);
            }
            relationship = foundRel;
        }

        // Resolve source view object
        IDiagramModelArchimateObject sourceViewObj = directSource;
        if (sourceViewObj == null) {
            Map<String, IDiagramModelArchimateObject> viewObjectMap = new LinkedHashMap<>();
            collectViewObjectMap(view, viewObjectMap);
            sourceViewObj = findViewObjectById(viewObjectMap, sourceViewObjectId);
            if (sourceViewObj == null) {
                throw new ModelAccessException(
                        "Source view object not found: " + sourceViewObjectId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Use get-view-contents to find valid view object IDs",
                        null);
            }
        }

        // Resolve target view object
        IDiagramModelArchimateObject targetViewObj = directTarget;
        if (targetViewObj == null) {
            Map<String, IDiagramModelArchimateObject> viewObjectMap = new LinkedHashMap<>();
            collectViewObjectMap(view, viewObjectMap);
            targetViewObj = findViewObjectById(viewObjectMap, targetViewObjectId);
            if (targetViewObj == null) {
                throw new ModelAccessException(
                        "Target view object not found: " + targetViewObjectId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Use get-view-contents to find valid view object IDs",
                        null);
            }
        }

        // Validate relationship-element match
        IArchimateElement relSource = (IArchimateElement) relationship.getSource();
        IArchimateElement relTarget = (IArchimateElement) relationship.getTarget();
        IArchimateElement sourceElem = sourceViewObj.getArchimateElement();
        IArchimateElement targetElem = targetViewObj.getArchimateElement();

        boolean forwardMatch = relSource.getId().equals(sourceElem.getId())
                && relTarget.getId().equals(targetElem.getId());
        boolean reversedMatch = relSource.getId().equals(targetElem.getId())
                && relTarget.getId().equals(sourceElem.getId());

        if (!forwardMatch && !reversedMatch) {
            throw new ModelAccessException(
                    "Relationship '" + relationshipId + "' does not connect the elements "
                            + "referenced by the source and target view objects",
                    ErrorCode.RELATIONSHIP_MISMATCH,
                    "Relationship connects " + relSource.getId() + " -> " + relTarget.getId()
                            + ", but view objects reference " + sourceElem.getId()
                            + " and " + targetElem.getId(),
                    "Verify the relationship connects the correct elements, "
                            + "or use different view objects",
                    null);
        }

        // Story 8-0d: convert absolute bendpoints to relative if provided
        List<BendpointDto> effectiveBendpoints = bendpoints;
        if (absoluteBendpoints != null && !absoluteBendpoints.isEmpty()) {
            effectiveBendpoints = ConnectionResponseBuilder.convertAbsoluteToRelative(
                    absoluteBendpoints, sourceViewObj, targetViewObj);
        }

        // Create connection
        IDiagramModelArchimateConnection conn =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(relationship);
        ConnectionResponseBuilder.applyBendpointsToConnection(conn, effectiveBendpoints);

        Command cmd = new AddConnectionToViewCommand(conn, sourceViewObj, targetViewObj);

        ViewConnectionDto dto = ConnectionResponseBuilder.buildConnectionResponseDto(
                conn.getId(), relationship, sourceViewObj.getId(), targetViewObj.getId(),
                effectiveBendpoints, sourceViewObj, targetViewObj, conn.getTextPosition());

        return new PreparedMutation<>(cmd, dto, conn.getId(), conn);
    }

    /**
     * Resolves the action string for a bulk operation tool.
     */
    private static String resolveActionString(String tool) {
        return switch (tool) {
            case "add-to-view", "add-group-to-view", "add-note-to-view" -> "placed";
            case "add-connection-to-view" -> "connected";
            case "remove-from-view" -> "removed";
            case "clear-view" -> "cleared";
            case "update-view", "update-view-object", "update-view-connection", "update-element" -> "updated";
            case "delete-element", "delete-relationship", "delete-view", "delete-folder" -> "deleted";
            default -> "created";
        };
    }

    /**
     * Builds a BulkOperationResult from a prepared mutation.
     */
    private BulkOperationResult buildOperationResult(int index, String tool, String action,
            PreparedMutation<?> prepared) {
        Object entity = prepared.entity();
        String entityType = null;
        String entityName = null;

        if (entity instanceof ElementDto dto) {
            entityType = dto.type();
            entityName = dto.name();
        } else if (entity instanceof RelationshipDto dto) {
            entityType = dto.type();
            entityName = dto.name();
        } else if (entity instanceof ViewDto dto) {
            entityType = "ArchimateDiagramModel";
            entityName = dto.name();
        } else if (entity instanceof AddToViewResultDto dto) {
            entityType = dto.viewObject().elementType();
            entityName = dto.viewObject().elementName();
        } else if (entity instanceof ViewConnectionDto dto) {
            entityType = dto.relationshipType();
            entityName = null;
        } else if (entity instanceof ViewObjectDto dto) {
            entityType = dto.elementType();
            entityName = dto.elementName();
        } else if (entity instanceof RemoveFromViewResultDto dto) {
            entityType = dto.removedObjectType();
            entityName = null;
        } else if (entity instanceof ClearViewResultDto dto) {
            entityType = "view";
            entityName = dto.viewName();
        } else if (entity instanceof ViewGroupDto dto) {
            entityType = "DiagramModelGroup";
            entityName = dto.label();
        } else if (entity instanceof ViewNoteDto dto) {
            entityType = "DiagramModelNote";
            entityName = null;
        }

        return new BulkOperationResult(index, tool, action,
                prepared.entityId(), entityType, entityName);
    }

    // ---- Bulk parameter helpers ----

    private String requireParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (!(value instanceof String str) || str.isBlank()) {
            throw new ModelAccessException(
                    "Missing required parameter '" + key + "'",
                    ErrorCode.INVALID_PARAMETER);
        }
        return str;
    }

    private String optionalParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }

    /**
     * Extracts optional colour param preserving empty strings (for clear-to-default).
     * Returns null if absent; empty string if explicitly set to "".
     */
    private String optionalColorParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof String str) {
            return str;
        }
        return null;
    }

    /**
     * Extracts optional styling parameters from bulk operation params.
     * Returns null if no styling params are present.
     */
    private StylingParams extractBulkStylingParams(Map<String, Object> params) {
        String fillColor = optionalColorParam(params, "fillColor");
        String lineColor = optionalColorParam(params, "lineColor");
        String fontColor = optionalColorParam(params, "fontColor");
        Integer opacity = optionalIntParam(params, "opacity");
        Integer lineWidth = optionalIntParam(params, "lineWidth");
        if (fillColor == null && lineColor == null && fontColor == null
                && opacity == null && lineWidth == null) {
            return null;
        }
        return new StylingParams(fillColor, lineColor, fontColor, opacity, lineWidth);
    }

    private Boolean optionalBoolParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> optionalStringMap(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Map<?, ?> rawMap && !rawMap.isEmpty()) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String k && entry.getValue() != null) {
                    result.put(k, String.valueOf(entry.getValue()));
                }
            }
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> optionalStringMapWithNulls(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Map<?, ?> rawMap && !rawMap.isEmpty()) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String k) {
                    result.put(k, entry.getValue() != null
                            ? String.valueOf(entry.getValue()) : null);
                }
            }
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    // ---- Mutation helper methods (Story 7-2) ----

    private EClass resolveElementType(String type) {
        EClassifier classifier = IArchimatePackage.eINSTANCE.getEClassifier(type);
        if (classifier == null || !(classifier instanceof EClass eClass)) {
            throw new ModelAccessException(
                    "Invalid ArchiMate element type: " + type,
                    ErrorCode.INVALID_ELEMENT_TYPE);
        }
        if (!IArchimatePackage.eINSTANCE.getArchimateElement().isSuperTypeOf(eClass)) {
            throw new ModelAccessException(
                    "'" + type + "' is not an ArchiMate element type",
                    ErrorCode.INVALID_ELEMENT_TYPE);
        }
        return eClass;
    }

    private EClass resolveRelationshipType(String type) {
        EClassifier classifier = IArchimatePackage.eINSTANCE.getEClassifier(type);
        if (classifier == null || !(classifier instanceof EClass eClass)) {
            throw new ModelAccessException(
                    "Invalid ArchiMate relationship type: " + type,
                    ErrorCode.INVALID_RELATIONSHIP_TYPE);
        }
        if (!IArchimatePackage.eINSTANCE.getArchimateRelationship().isSuperTypeOf(eClass)) {
            throw new ModelAccessException(
                    "'" + type + "' is not an ArchiMate relationship type",
                    ErrorCode.INVALID_RELATIONSHIP_TYPE);
        }
        return eClass;
    }

    private IFolder resolveTargetFolder(IArchimateModel model, IArchimateElement element,
            String folderId) {
        if (folderId != null && !folderId.isBlank()) {
            IFolder folder = FolderOperations.findFolderById(model, folderId);
            if (folder == null) {
                throw new ModelAccessException(
                        "Folder not found: " + folderId,
                        ErrorCode.FOLDER_NOT_FOUND);
            }
            validateFolderLayerMatch(element, folder);
            return folder;
        }
        return model.getDefaultFolderForObject(element);
    }

    /**
     * Validates that the target folder's root layer matches the element type's expected layer.
     * User-created subfolders inherit their layer from their root ancestor folder.
     */
    void validateFolderLayerMatch(IArchimateElement element, IFolder folder) {
        FolderType expectedType = getExpectedFolderType(element);
        if (expectedType == null) {
            return; // Unknown element type — skip validation
        }

        IFolder rootFolder = getRootFolder(folder);
        FolderType actualType = rootFolder.getType();

        if (actualType != expectedType) {
            String elementType = element.eClass().getName();
            String expectedLayer = folderTypeToLayerName(expectedType);
            String actualLayer = folderTypeToLayerName(actualType);
            throw new ModelAccessException(
                    elementType + " elements belong to the " + expectedLayer
                            + " layer but the target folder '" + folder.getName()
                            + "' is under the " + actualLayer + " layer",
                    ErrorCode.FOLDER_LAYER_MISMATCH,
                    "Expected root folder type: " + expectedType
                            + ", actual root folder type: " + actualType,
                    "Either omit folderId to use the default " + expectedLayer
                            + " folder, or provide a folder under the "
                            + expectedLayer + " root folder.",
                    "ArchiMate 3.2 specification, element classification");
        }
    }

    /**
     * Maps an ArchiMate element to its expected root FolderType.
     */
    FolderType getExpectedFolderType(IArchimateElement element) {
        if (element instanceof IStrategyElement) return FolderType.STRATEGY;
        if (element instanceof IBusinessElement) return FolderType.BUSINESS;
        if (element instanceof IApplicationElement) return FolderType.APPLICATION;
        if (element instanceof ITechnologyElement) return FolderType.TECHNOLOGY;
        if (element instanceof IPhysicalElement) return FolderType.TECHNOLOGY; // Archi stores Physical elements in Technology folder
        if (element instanceof IMotivationElement) return FolderType.MOTIVATION;
        if (element instanceof IImplementationMigrationElement) return FolderType.IMPLEMENTATION_MIGRATION;
        return null;
    }

    /**
     * Walks up the folder hierarchy to find the root folder (direct child of model).
     */
    IFolder getRootFolder(IFolder folder) {
        IFolder current = folder;
        while (current.eContainer() instanceof IFolder parent) {
            current = parent;
        }
        return current;
    }

    /**
     * Converts a FolderType to a human-readable layer name.
     */
    private String folderTypeToLayerName(FolderType type) {
        return switch (type) {
            case STRATEGY -> "Strategy";
            case BUSINESS -> "Business";
            case APPLICATION -> "Application";
            case TECHNOLOGY -> "Technology";
            case MOTIVATION -> "Motivation";
            case IMPLEMENTATION_MIGRATION -> "Implementation & Migration";
            case RELATIONS -> "Relations";
            case DIAGRAMS -> "Diagrams";
            case OTHER -> "Other";
            default -> type.name();
        };
    }

    /**
     * Dispatches a command immediately or queues for batch based on session mode.
     *
     * @return batch sequence number if queued, null if dispatched immediately
     */
    private Integer dispatchOrQueue(String sessionId, Command cmd, String description) {
        OperationalMode mode = mutationDispatcher.getMode(sessionId);
        if (mode == OperationalMode.BATCH) {
            int seq = mutationDispatcher.queueForBatch(sessionId, cmd, description);
            return seq;
        }
        mutationDispatcher.dispatchImmediate(cmd);
        return null;
    }

    // ---- Approval helpers (Story 7-6) ----

    /**
     * Stores a mutation as a pending proposal and returns the proposal context.
     *
     * @param sessionId         the session identifier
     * @param tool              the MCP tool name (e.g., "create-element")
     * @param cmd               the GEF Command ready for execution
     * @param entity            the DTO representing the proposed result
     * @param description       human-readable description
     * @param currentState      snapshot of current state (null for creates)
     * @param proposedChanges   map of field names to proposed values
     * @param validationSummary human-readable validation result
     * @return ProposalContext with the assigned proposal ID
     */
    private ProposalContext storeAsProposal(String sessionId, String tool,
            Command cmd, Object entity, String description,
            Map<String, Object> currentState, Map<String, Object> proposedChanges,
            String validationSummary) {
        Instant now = Instant.now();
        PendingProposal proposal = new PendingProposal(
                null, tool, description, cmd, entity,
                currentState, proposedChanges, validationSummary, now);
        String proposalId = mutationDispatcher.storeProposal(sessionId, proposal);
        logger.info("Stored proposal '{}' for session '{}': {}", proposalId, sessionId, description);
        return new ProposalContext(proposalId, description, now);
    }

    /**
     * Merges source traceability properties into the element properties map.
     * Source entries are prefixed with "mcp.source." (e.g., "mcp.source.tool").
     *
     * @param properties existing properties (may be null)
     * @param source     source traceability map (may be null)
     * @return merged properties map, or original if source is null
     */
    private Map<String, String> mergeSourceProperties(Map<String, String> properties,
            Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return properties;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (properties != null) {
            merged.putAll(properties);
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                merged.put("mcp.source." + entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    // ---- Command stack undo/redo (Story 11-1) ----

    @Override
    public UndoRedoResultDto undo(int steps) {
        logger.info("Undo: steps={}", steps);
        requireAndCaptureModel();
        try {
            MutationDispatcher.UndoRedoState state = mutationDispatcher.undo(steps);
            if (!state.labels().isEmpty()) {
                versionCounter.incrementAndGet();
            }
            return new UndoRedoResultDto(
                    steps, state.labels().size(), state.labels(),
                    state.canUndo(), state.canRedo());
        } catch (MutationException e) {
            throw new ModelAccessException(e.getMessage(), ErrorCode.MUTATION_FAILED);
        }
    }

    @Override
    public UndoRedoResultDto redo(int steps) {
        logger.info("Redo: steps={}", steps);
        requireAndCaptureModel();
        try {
            MutationDispatcher.UndoRedoState state = mutationDispatcher.redo(steps);
            if (!state.labels().isEmpty()) {
                versionCounter.incrementAndGet();
            }
            return new UndoRedoResultDto(
                    steps, state.labels().size(), state.labels(),
                    state.canUndo(), state.canRedo());
        } catch (MutationException e) {
            throw new ModelAccessException(e.getMessage(), ErrorCode.MUTATION_FAILED);
        }
    }

    // ---- Mutation support (Story 7-1) ----

    @Override
    public MutationDispatcher getMutationDispatcher() {
        return mutationDispatcher;
    }

    // ---- Volatile capture + null check ----

    /**
     * Captures the volatile model reference and throws if null.
     * Prevents volatile re-read NPE (Epic 1 retro action item).
     */
    private IArchimateModel requireAndCaptureModel() {
        IArchimateModel model = this.activeModel;
        if (model == null) {
            throw new NoModelLoadedException();
        }
        return model;
    }

    // ---- DTO conversion helpers ----

    /**
     * Converts an EMF {@link IArchimateElement} to an {@link ElementDto}.
     */
    ElementDto convertToElementDto(IArchimateElement element) {
        String type = element.eClass().getName();
        String layer = resolveLayer(element);
        List<Map<String, String>> properties = convertProperties(element.getProperties());

        String documentation = element.getDocumentation();
        if (documentation != null && documentation.isEmpty()) {
            documentation = null;
        }

        return ElementDto.standard(
                element.getId(),
                element.getName(),
                type,
                layer,
                documentation,
                properties.isEmpty() ? null : properties);
    }

    /**
     * Converts an EMF {@link IArchimateRelationship} to a {@link RelationshipDto}.
     */
    RelationshipDto convertToRelationshipDto(IArchimateRelationship relationship) {
        return new RelationshipDto(
                relationship.getId(),
                relationship.getName(),
                relationship.eClass().getName(),
                relationship.getSource() != null ? relationship.getSource().getId() : null,
                relationship.getTarget() != null ? relationship.getTarget().getId() : null);
    }

    /**
     * Resolves the ArchiMate layer for an element using instanceof checks.
     */
    String resolveLayer(IArchimateElement element) {
        if (element instanceof IBusinessElement) return "Business";
        if (element instanceof IApplicationElement) return "Application";
        if (element instanceof ITechnologyElement) return "Technology";
        if (element instanceof IPhysicalElement) return "Physical";
        if (element instanceof IStrategyElement) return "Strategy";
        if (element instanceof IMotivationElement) return "Motivation";
        if (element instanceof IImplementationMigrationElement) return "Implementation & Migration";
        return "Other";
    }

    // ---- Model traversal helpers ----

    private void collectElements(IFolder folder, List<IArchimateElement> elements) {
        for (EObject obj : folder.getElements()) {
            if (obj instanceof IArchimateElement element) {
                elements.add(element);
            }
        }
        for (IFolder subFolder : folder.getFolders()) {
            collectElements(subFolder, elements);
        }
    }

    private void collectRelationships(IFolder folder, List<IArchimateRelationship> relationships) {
        for (EObject obj : folder.getElements()) {
            if (obj instanceof IArchimateRelationship relationship) {
                relationships.add(relationship);
            }
        }
        for (IFolder subFolder : folder.getFolders()) {
            collectRelationships(subFolder, relationships);
        }
    }

    private int countViews(IArchimateModel model) {
        IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);
        if (diagramsFolder == null) {
            return 0;
        }
        return countViewsInFolder(diagramsFolder);
    }

    private int countViewsInFolder(IFolder folder) {
        int count = 0;
        for (EObject obj : folder.getElements()) {
            if (obj instanceof IArchimateDiagramModel) {
                count++;
            }
        }
        for (IFolder subFolder : folder.getFolders()) {
            count += countViewsInFolder(subFolder);
        }
        return count;
    }

    private void collectViews(IFolder folder, String parentPath,
                              String viewpointFilter, List<ViewDto> views) {
        String currentPath = parentPath.isEmpty()
                ? folder.getName()
                : parentPath + "/" + folder.getName();

        for (EObject obj : folder.getElements()) {
            if (obj instanceof IArchimateDiagramModel diagram) {
                String viewpoint = diagram.getViewpoint();
                if (viewpoint != null && viewpoint.isEmpty()) {
                    viewpoint = null;
                }

                if (viewpointFilter == null || viewpointFilter.equals(viewpoint)) {
                    views.add(buildViewDto(diagram, currentPath));
                }
            }
        }
        for (IFolder subFolder : folder.getFolders()) {
            collectViews(subFolder, currentPath, viewpointFilter, views);
        }
    }

    private void collectViewContents(IDiagramModelContainer container,
                                     List<ElementDto> elements,
                                     List<RelationshipDto> relationships,
                                     List<ViewNodeDto> visualMetadata,
                                     List<ViewConnectionDto> connections,
                                     List<ViewGroupDto> groups,
                                     List<ViewNoteDto> notes,
                                     Set<String> seenElementIds,
                                     Set<String> seenRelationshipIds,
                                     String parentViewObjectId) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject archimateObject) {
                IArchimateElement element = archimateObject.getArchimateElement();
                if (element != null) {
                    // Dedup elements (same element can appear multiple times in a view)
                    if (seenElementIds.add(element.getId())) {
                        elements.add(convertToElementDto(element));
                    }

                    // Visual metadata always collected (different positions are meaningful)
                    IBounds bounds = archimateObject.getBounds();
                    if (bounds != null) {
                        visualMetadata.add(new ViewNodeDto(
                                archimateObject.getId(),
                                element.getId(),
                                bounds.getX(),
                                bounds.getY(),
                                bounds.getWidth(),
                                bounds.getHeight(),
                                parentViewObjectId,
                                StylingHelper.readFillColor(archimateObject),
                                StylingHelper.readLineColor(archimateObject),
                                StylingHelper.readFontColor(archimateObject),
                                StylingHelper.readOpacity(archimateObject),
                                StylingHelper.readLineWidth(archimateObject)));
                    }
                }

                // Collect connections from this object
                collectConnections(archimateObject, relationships, connections,
                        seenRelationshipIds);
            }

            // Story 8-6: Collect groups
            if (child instanceof IDiagramModelGroup groupObj) {
                IBounds bounds = groupObj.getBounds();
                List<String> childIds = new ArrayList<>();
                for (IDiagramModelObject groupChild : groupObj.getChildren()) {
                    childIds.add(groupChild.getId());
                }
                groups.add(new ViewGroupDto(
                        groupObj.getId(),
                        groupObj.getName(),
                        bounds != null ? bounds.getX() : 0,
                        bounds != null ? bounds.getY() : 0,
                        bounds != null ? bounds.getWidth() : DEFAULT_GROUP_WIDTH,
                        bounds != null ? bounds.getHeight() : DEFAULT_GROUP_HEIGHT,
                        parentViewObjectId,
                        childIds.isEmpty() ? null : childIds,
                        StylingHelper.readFillColor(groupObj),
                        StylingHelper.readLineColor(groupObj),
                        StylingHelper.readFontColor(groupObj),
                        StylingHelper.readOpacity(groupObj),
                        StylingHelper.readLineWidth(groupObj)));

                // Recurse into group's children
                collectViewContents(groupObj, elements, relationships, visualMetadata,
                        connections, groups, notes, seenElementIds, seenRelationshipIds,
                        groupObj.getId());
                continue; // Skip the generic container recursion below
            }

            // Story 8-6: Collect notes
            if (child instanceof IDiagramModelNote noteObj) {
                IBounds bounds = noteObj.getBounds();
                notes.add(new ViewNoteDto(
                        noteObj.getId(),
                        noteObj.getContent(),
                        bounds != null ? bounds.getX() : 0,
                        bounds != null ? bounds.getY() : 0,
                        bounds != null ? bounds.getWidth() : DEFAULT_NOTE_WIDTH,
                        bounds != null ? bounds.getHeight() : DEFAULT_NOTE_HEIGHT,
                        parentViewObjectId,
                        StylingHelper.readFillColor(noteObj),
                        StylingHelper.readLineColor(noteObj),
                        StylingHelper.readFontColor(noteObj),
                        StylingHelper.readOpacity(noteObj),
                        StylingHelper.readLineWidth(noteObj)));
                continue; // Notes are not containers, no recursion needed
            }

            // Story 10-20: Recurse into nested containers (element-as-container)
            // Pass child's view object ID as parent so nested children report correct parentViewObjectId
            if (child instanceof IDiagramModelContainer nestedContainer) {
                collectViewContents(nestedContainer, elements, relationships, visualMetadata,
                        connections, groups, notes, seenElementIds, seenRelationshipIds,
                        child.getId());
            }
        }
    }

    private void collectConnections(IDiagramModelArchimateObject archimateObject,
                                    List<RelationshipDto> relationships,
                                    List<ViewConnectionDto> connections,
                                    Set<String> seenRelationshipIds) {
        archimateObject.getSourceConnections().forEach(conn -> {
            if (conn instanceof IDiagramModelArchimateConnection archimateConn) {
                IArchimateRelationship rel = archimateConn.getArchimateRelationship();
                if (rel != null) {
                    // RelationshipDto: deduplicated by relationship ID
                    if (seenRelationshipIds.add(rel.getId())) {
                        relationships.add(convertToRelationshipDto(rel));
                    }
                    // ViewConnectionDto: every visual connection collected (each has unique ID)
                    List<BendpointDto> relativeBps = ConnectionResponseBuilder.collectBendpoints(archimateConn);

                    // Story 8-0d: compute absolute bendpoints and anchor points
                    AnchorPointDto sourceAnchor = null;
                    AnchorPointDto targetAnchor = null;
                    List<AbsoluteBendpointDto> absoluteBps = null;

                    IConnectable srcConnectable = archimateConn.getSource();
                    IConnectable tgtConnectable = archimateConn.getTarget();
                    if (srcConnectable instanceof IDiagramModelArchimateObject srcObj
                            && tgtConnectable instanceof IDiagramModelArchimateObject tgtObj) {
                        int[] srcAbsCenter = ConnectionResponseBuilder.computeAbsoluteCenter(srcObj);
                        int[] tgtAbsCenter = ConnectionResponseBuilder.computeAbsoluteCenter(tgtObj);

                        sourceAnchor = new AnchorPointDto(srcAbsCenter[0], srcAbsCenter[1]);
                        targetAnchor = new AnchorPointDto(tgtAbsCenter[0], tgtAbsCenter[1]);

                        if (!relativeBps.isEmpty()) {
                            absoluteBps = convertRelativeToAbsolute(
                                    relativeBps, srcAbsCenter[0], srcAbsCenter[1],
                                    tgtAbsCenter[0], tgtAbsCenter[1]);
                        }
                    }

                    connections.add(new ViewConnectionDto(
                            archimateConn.getId(),
                            rel.getId(),
                            rel.eClass().getName(),
                            archimateConn.getSource().getId(),
                            archimateConn.getTarget().getId(),
                            relativeBps.isEmpty() ? null : relativeBps,
                            absoluteBps,
                            sourceAnchor,
                            targetAnchor,
                            archimateConn.getTextPosition(),
                            StylingHelper.readConnectionLineColor(archimateConn),
                            StylingHelper.readConnectionLineWidth(archimateConn),
                            StylingHelper.readConnectionFontColor(archimateConn)));
                }
            }
        });
    }

    /**
     * Computes the absolute canvas center of a view object by walking up the parent
     * chain and accumulating offsets. For top-level elements (parent is IDiagramModel),
     * local coordinates equal absolute coordinates so the loop body never executes.
     *
     * <p>Package-visible for testability.</p>
     */
    static int[] computeAbsoluteCenter(IDiagramModelObject obj) {
        return ConnectionResponseBuilder.computeAbsoluteCenter(obj);
    }

    /**
     * Determines whether the quality iteration loop has plateaued.
     * A plateau is reached when the rating and score are unchanged AND
     * average spacing has not improved by more than 1.0px.
     * Package-visible for testing.
     */
    static boolean isPlateauReached(String rating, String previousRating,
            int score, int previousScore,
            double avgSpacing, double previousAvgSpacing) {
        boolean spacingImproved = avgSpacing > 0
                && Math.abs(avgSpacing - previousAvgSpacing) > 1.0;
        return rating.equals(previousRating) && score == previousScore
                && !spacingImproved;
    }

    /**
     * Computes absolute center coordinates from ELK's NEW positions.
     * For nested elements, walks up the parent chain using the new positions
     * (not the stale EMF positions). This ensures bendpoint conversion uses
     * the correct coordinate system matching ELK's edge routing output.
     */
    static Map<String, int[]> computeElkAbsoluteCenters(
            Map<String, ViewPositionSpec> positionById,
            List<LayoutNode> nodes) {
        // Build parentId lookup from LayoutNode data
        Map<String, String> parentIdMap = new LinkedHashMap<>();
        for (LayoutNode node : nodes) {
            if (node.parentId() != null) {
                parentIdMap.put(node.viewObjectId(), node.parentId());
            }
        }

        Map<String, int[]> centers = new LinkedHashMap<>();
        for (ViewPositionSpec pos : positionById.values()) {
            if (pos.x() == null || pos.y() == null
                    || pos.width() == null || pos.height() == null) {
                continue; // Skip partial position specs
            }
            int centerX = pos.x() + pos.width() / 2;
            int centerY = pos.y() + pos.height() / 2;

            // Walk up parent chain accumulating offsets from new positions
            String parentId = parentIdMap.get(pos.viewObjectId());
            while (parentId != null) {
                ViewPositionSpec parentPos = positionById.get(parentId);
                if (parentPos != null && parentPos.x() != null
                        && parentPos.y() != null) {
                    centerX += parentPos.x();
                    centerY += parentPos.y();
                    parentId = parentIdMap.get(parentId);
                } else {
                    break;
                }
            }
            centers.put(pos.viewObjectId(), new int[] { centerX, centerY });
        }
        return centers;
    }

    static List<BendpointDto> convertAbsoluteToRelative(
            List<AbsoluteBendpointDto> absoluteBendpoints,
            int srcCenterX, int srcCenterY, int tgtCenterX, int tgtCenterY) {
        return ConnectionResponseBuilder.convertAbsoluteToRelative(
                absoluteBendpoints, srcCenterX, srcCenterY, tgtCenterX, tgtCenterY);
    }

    static List<AbsoluteBendpointDto> convertRelativeToAbsolute(
            List<BendpointDto> relativeBendpoints,
            int srcCenterX, int srcCenterY,
            int tgtCenterX, int tgtCenterY) {
        return ConnectionResponseBuilder.convertRelativeToAbsolute(
                relativeBendpoints, srcCenterX, srcCenterY, tgtCenterX, tgtCenterY);
    }

    /**
     * Validates that bendpoints and absoluteBendpoints are mutually exclusive.
     *
     * @throws ModelAccessException with INVALID_PARAMETER if both are provided
     */
    private void validateBendpointMutualExclusion(List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints) {
        if (bendpoints != null && !bendpoints.isEmpty()
                && absoluteBendpoints != null && !absoluteBendpoints.isEmpty()) {
            throw new ModelAccessException(
                    "Cannot provide both 'bendpoints' and 'absoluteBendpoints'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use either relative bendpoints (startX/startY/endX/endY) or absolute "
                            + "bendpoints ({x, y}), not both",
                    null);
        }
    }

    /**
     * Recursively collects IDs of all descendant view objects and their connections
     * within a container (group). Used to report cascadeIds when removing groups.
     */
    private List<String> collectDescendantIds(IDiagramModelContainer container) {
        List<String> ids = new ArrayList<>();
        for (IDiagramModelObject child : container.getChildren()) {
            ids.add(child.getId());
            // Collect connection IDs from source/target connections
            for (Object conn : child.getSourceConnections()) {
                if (conn instanceof IDiagramModelConnection dc) {
                    ids.add(dc.getId());
                }
            }
            for (Object conn : child.getTargetConnections()) {
                if (conn instanceof IDiagramModelConnection dc) {
                    ids.add(dc.getId());
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                ids.addAll(collectDescendantIds(nested));
            }
        }
        return ids;
    }

    /**
     * Validates that a dimension value (width or height) is positive when provided.
     */
    private void validatePositiveDimension(Integer value, String fieldName) {
        if (value != null && value <= 0) {
            throw new ModelAccessException(
                    fieldName + " must be positive, got: " + value,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a positive integer for " + fieldName + ", or omit for default.",
                    null);
        }
    }

    private List<Map<String, String>> convertProperties(
            org.eclipse.emf.common.util.EList<IProperty> properties) {
        if (properties == null || properties.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (IProperty prop : properties) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("key", prop.getKey());
            entry.put("value", prop.getValue());
            result.add(entry);
        }
        return result;
    }

    private Map<String, Integer> buildTypeDistribution(List<IArchimateElement> elements) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (IArchimateElement element : elements) {
            String type = element.eClass().getName();
            distribution.merge(type, 1, Integer::sum);
        }
        return distribution;
    }

    private Map<String, Integer> buildRelationshipTypeDistribution(
            List<IArchimateRelationship> relationships) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (IArchimateRelationship rel : relationships) {
            String type = rel.eClass().getName();
            distribution.merge(type, 1, Integer::sum);
        }
        return distribution;
    }

    private Map<String, Integer> buildLayerDistribution(List<IArchimateElement> elements) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (IArchimateElement element : elements) {
            String layer = resolveLayer(element);
            // Skip "Other" — not a real ArchiMate layer and not a valid filter value
            if (!"Other".equals(layer)) {
                distribution.merge(layer, 1, Integer::sum);
            }
        }
        return distribution;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        modelManager.removePropertyChangeListener(this);
        activeModel = null;
        changeListeners.clear();
        logger.debug("ArchiModelAccessorImpl disposed");
    }

    /**
     * Registers a listener for model change events.
     *
     * @param listener the listener to add
     */
    @Override
    public void addModelChangeListener(ModelChangeListener listener) {
        if (disposed) {
            logger.warn("Attempted to add listener to disposed ArchiModelAccessorImpl");
            return;
        }
        changeListeners.add(listener);
    }

    /**
     * Removes a previously registered model change listener.
     *
     * @param listener the listener to remove
     */
    @Override
    public void removeModelChangeListener(ModelChangeListener listener) {
        changeListeners.remove(listener);
    }

    // ---- PropertyChangeListener (model lifecycle events) ----

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (disposed) {
            return;
        }

        String property = evt.getPropertyName();

        if (IEditorModelManager.PROPERTY_MODEL_OPENED.equals(property)
                || IEditorModelManager.PROPERTY_MODEL_LOADED.equals(property)) {
            handleModelOpened(evt);
        } else if (IEditorModelManager.PROPERTY_MODEL_REMOVED.equals(property)) {
            handleModelRemoved(evt);
        } else if (IEditorModelManager.PROPERTY_ECORE_EVENT.equals(property)) {
            handleModelContentChanged();
        }
    }

    // ---- Internal helpers ----

    /**
     * Detects the currently active model on initialization.
     * Uses the first loaded model as the active model.
     */
    private void detectActiveModel() {
        List<IArchimateModel> models = modelManager.getModels();
        if (models != null && !models.isEmpty()) {
            IArchimateModel model = models.get(0);
            setActiveModel(model);
            logger.info("Detected loaded model: '{}' (id: {})",
                    model.getName(), model.getId());
        } else {
            logger.debug("No ArchiMate model currently loaded");
        }
    }

    private void handleModelOpened(PropertyChangeEvent evt) {
        Object newValue = evt.getNewValue();
        if (newValue instanceof IArchimateModel openedModel) {
            IArchimateModel previousModel = activeModel;
            setActiveModel(openedModel);

            if (previousModel != null && previousModel != openedModel) {
                logger.info("Model switched from '{}' to '{}'",
                        previousModel.getName(), openedModel.getName());
                fireModelChanged(openedModel.getName(), openedModel.getId());
            } else if (previousModel == null) {
                logger.info("Model loaded: '{}' (id: {})",
                        openedModel.getName(), openedModel.getId());
                fireModelChanged(openedModel.getName(), openedModel.getId());
            }
        }
    }

    private void handleModelRemoved(PropertyChangeEvent evt) {
        Object oldValue = evt.getOldValue();
        if (oldValue instanceof IArchimateModel removedModel) {
            if (removedModel == activeModel) {
                logger.info("Active model '{}' was closed", removedModel.getName());

                // Try to switch to another loaded model
                List<IArchimateModel> remaining = modelManager.getModels();
                if (remaining != null && !remaining.isEmpty()) {
                    IArchimateModel newActive = remaining.get(0);
                    setActiveModel(newActive);
                    logger.info("Switched to model: '{}' (id: {})",
                            newActive.getName(), newActive.getId());
                    fireModelChanged(newActive.getName(), newActive.getId());
                } else {
                    activeModel = null;
                    versionCounter.incrementAndGet();
                    logger.warn("No ArchiMate model loaded — server will return NO_MODEL_LOADED errors");
                    fireModelChanged(null, null);
                }
            }
        }
    }

    private void handleModelContentChanged() {
        long newVersion = versionCounter.incrementAndGet();
        logger.debug("Model content changed — version incremented to {}", newVersion);
    }

    private void setActiveModel(IArchimateModel model) {
        this.activeModel = model;
        this.versionCounter.incrementAndGet();
    }

    private void fireModelChanged(String modelName, String modelId) {
        for (ModelChangeListener listener : changeListeners) {
            try {
                listener.onModelChanged(modelName, modelId);
            } catch (Exception e) {
                logger.warn("Model change listener threw exception", e);
            }
        }
    }

}

