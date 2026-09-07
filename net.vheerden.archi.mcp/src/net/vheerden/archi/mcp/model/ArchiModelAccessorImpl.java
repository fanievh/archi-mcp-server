package net.vheerden.archi.mcp.model;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gef.commands.Command;

import net.vheerden.archi.mcp.model.RequireAttachedContainerCommand.Wording;
import org.eclipse.gef.commands.CompoundCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.FolderType;
import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IInfluenceRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IFontAttribute;
import com.archimatetool.model.ILineObject;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.ITextContent;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.ISketchModel;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.model.routing.BestOfKRoutingStrategy;
import net.vheerden.archi.mcp.model.routing.FailedConnection;
import net.vheerden.archi.mcp.model.routing.LabelPolicy;
import net.vheerden.archi.mcp.model.routing.LabelPositionOptimizer;
import net.vheerden.archi.mcp.model.routing.MoveRecommendation;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.model.routing.VisibilityGraphRouter;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.StringSimilarity;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddImageResultDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAssessmentSummaryDto;
import net.vheerden.archi.mcp.response.dto.AdjustViewSpacingResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyElementSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyGroupSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplySpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteBlockedReasons;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.ConceptUsageDto;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;
import net.vheerden.archi.mcp.response.dto.DetectHubElementsResultDto;
import net.vheerden.archi.mcp.response.dto.DiagramImageDto;
import net.vheerden.archi.mcp.response.dto.DuplicateCandidate;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;
import net.vheerden.archi.mcp.response.dto.HubElementEntryDto;
import net.vheerden.archi.mcp.response.dto.FailedConnectionDto;
import net.vheerden.archi.mcp.response.dto.MoveRecommendationDto;
import net.vheerden.archi.mcp.response.dto.RoutingViolationDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;
import net.vheerden.archi.mcp.response.dto.LayoutFlatViewResultDto;
import net.vheerden.archi.mcp.response.dto.ResizeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.SkippedContainerDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.ModelImageDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;
import net.vheerden.archi.mcp.response.dto.NudgedElementDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ResizedGroupDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;
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

    /**
     * Thread-local cache of profiles resolved during a single bulk-mutate prepare phase.
     * Set in {@link #executeBulk} before the prepare loop, cleared in {@code finally}.
     *
     * <p><strong>Why:</strong> {@code bulk-mutate} runs all prepare methods first
     * (building commands) before dispatching any of them. Without this cache, the
     * second and later prepares in a batch that all reference the same new specialization
     * each call {@code getProfileByNameAndType()} which still returns {@code null}
     * (the first prepare's command hasn't executed yet) and create their own duplicate
     * {@link IProfile} instances. The result is N shadow profiles in {@code model.getProfiles()}
     * where one was intended — breaking {@code list-specializations}, {@code update-specialization},
     * {@code delete-specialization}, and {@code get-specialization-usage}.
     *
     * <p>Scoped per-thread because Phase 1 (validate + build commands) runs on the
     * Jetty request thread, and multiple bulk-mutate calls may be in flight concurrently.
     */
    private final ThreadLocal<Map<String, IProfile>> bulkProfileCache = new ThreadLocal<>();

    /**
     * Batch-scoped effective bounds from earlier surviving ops of the in-flight bulk-mutate —
     * explicit sets AND parent-fit (cascade) grows ({@code viewObjectId → {x,y,w,h}}; per-thread
     * like {@link #bulkProfileCache}). Despite the name it tracks EVERY view object, not just groups.
     * Phase 1 prepares each op against the pre-batch model, so a later op reading {@code getBounds()}
     * sees STALE bounds: seeding {@code virtualGroupBounds} stops a child-move clobbering a group an
     * earlier op sized; seeding {@code mergeBounds}'s base stops a same-object re-edit reverting an
     * untouched dimension. Null for non-bulk → byte-identical.
     */
    private final ThreadLocal<Map<String, int[]>> bulkPendingGroupBounds = new ThreadLocal<>();

    /** Batch-scoped {@code viewObjectId → parent container} for the in-flight bulk-mutate: an add op builds a
     * DETACHED view object (EMF containment lands when its command executes, after every op is prepared), so a
     * later same-batch update addressed by back-reference has no {@code eContainer()} to auto-fit against.
     * Serves BOTH levels of the fit: the child's own parent lookup AND every ancestor hop of
     * {@link ParentFitCascade#resize}'s walk, which is why {@code add-group-to-view} records here too.
     * The queue-mode equivalent is not a second map here but {@code MutationDispatcher.queuedParents},
     * derived from the batch's own command queue and passed to the same walk.
     * Per-thread like {@link #bulkProfileCache}; null for non-bulk → those paths are unchanged. */
    private final ThreadLocal<Map<String, IDiagramModelContainer>> bulkPendingParents = new ThreadLocal<>();

    // View placement constants
    private static final int DEFAULT_VIEW_OBJECT_WIDTH = 120;
    private static final int DEFAULT_VIEW_OBJECT_HEIGHT = 55;
    private static final int DEFAULT_GROUP_WIDTH = 300;
    private static final int DEFAULT_GROUP_HEIGHT = 200;
    private static final int DEFAULT_NOTE_WIDTH = 185;
    private static final int DEFAULT_NOTE_HEIGHT = 80;
    // View-reference default bounds.
    // Mirrors note default for parity; ratified empirically.
    private static final int DEFAULT_VIEW_REF_WIDTH = 185;
    private static final int DEFAULT_VIEW_REF_HEIGHT = 80;

    /**
     * Default bounds for an {@code add-image-to-view} image visual when the
     * caller omits width/height AND the archive natural-dimension read fails
     * (image-visual fallback).
     */
    private static final int DEFAULT_IMAGE_VISUAL_WIDTH = 200;
    private static final int DEFAULT_IMAGE_VISUAL_HEIGHT = 200;
    private static final int START_X = 50;
    private static final int START_Y = 50;
    private static final int H_GAP = 30;
    private static final int V_GAP = 30;
    private static final int MAX_ROW_WIDTH = 800;
    private static final int MAX_ATTEMPTS = 100;
    private static final int MAX_AUTO_CONNECTIONS = 50;

    private final ElkLayoutEngine elkLayoutEngine = new ElkLayoutEngine();
    private final LayoutQualityAssessor layoutQualityAssessor = new LayoutQualityAssessor();
    // Model-side image operations, peeled out behind one-line forwards. Reaches the model
    // only through requireAndCaptureModel (passed as a Supplier) — no shared mutable state.
    private final ImageOperations imageOps = new ImageOperations(this::requireAndCaptureModel);
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

    // ---- Query methods ----

    @Override
    public Optional<ElementDto> getElementById(String id) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            EObject obj = ArchimateModelUtils.getObjectByID(model, id);
            if (obj instanceof IArchimateElement element) {
                return Optional.of(DtoMapper.convertToElementDto(element));
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

    // ---- Concept where-used ----

    @Override
    public Optional<ConceptUsageDto> findConceptUsage(String conceptId) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            EObject obj = ArchimateModelUtils.getObjectByID(model, conceptId);
            if (!(obj instanceof IArchimateConcept concept)) {
                return Optional.empty();
            }
            return Optional.of(buildConceptUsageDto(concept));
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving concept usage for ID '" + conceptId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Builds the {@link ConceptUsageDto} for a resolved concept by walking
     * Archi's typed back-reference lists. Pure-geometry helper extracted for
     * test-friendliness (the synthetic-model accessor test can drive this
     * directly with an {@code IArchimateFactory.eINSTANCE} fixture).
     *
     * <p>Element path uses the narrowed {@code IArchimateElement.getReferencingDiagramObjects()};
     * relationship path uses {@code IArchimateRelationship.getReferencingDiagramConnections()}.
     * Both lists are maintained eagerly by Archi's EMF notifier — O(1) lookup,
     * no model walking.</p>
     */
    static ConceptUsageDto buildConceptUsageDto(IArchimateConcept concept) {
        boolean isElement = concept instanceof IArchimateElement;
        String conceptKind = isElement ? "element" : "relationship";
        String conceptType = concept.eClass().getName();
        String conceptName = concept.getName() != null ? concept.getName() : "";

        // Group placements by view (one ViewReferenceDto per view, even if the
        // concept is placed multiple times on the same view).
        Map<String, List<IDiagramModelArchimateComponent>> placementsByView = new LinkedHashMap<>();
        int visualReferenceCount = 0;

        List<? extends IDiagramModelArchimateComponent> placements;
        if (isElement) {
            placements = ((IArchimateElement) concept).getReferencingDiagramObjects();
        } else {
            placements = ((IArchimateRelationship) concept).getReferencingDiagramConnections();
        }
        for (IDiagramModelArchimateComponent placement : placements) {
            IDiagramModel view = placement.getDiagramModel();
            if (view == null) {
                // EMF orphan — skip; shouldn't happen but defensive
                continue;
            }
            placementsByView.computeIfAbsent(view.getId(), k -> new ArrayList<>())
                    .add(placement);
            visualReferenceCount++;
        }

        List<ConceptUsageDto.ViewReferenceDto> viewReferences = new ArrayList<>();
        for (Map.Entry<String, List<IDiagramModelArchimateComponent>> entry : placementsByView.entrySet()) {
            IDiagramModel view = entry.getValue().get(0).getDiagramModel();
            String viewKind = deriveViewKind(view);
            String viewpointType = (view instanceof IArchimateDiagramModel adm)
                    ? adm.getViewpoint() : null;
            if (viewpointType != null && viewpointType.isEmpty()) {
                viewpointType = null;
            }

            List<ConceptUsageDto.VisualObjectReferenceDto> visualObjects = new ArrayList<>();
            for (IDiagramModelArchimateComponent placement : entry.getValue()) {
                String kind = (placement instanceof IDiagramModelArchimateConnection)
                        ? "connection" : "object";
                visualObjects.add(new ConceptUsageDto.VisualObjectReferenceDto(
                        placement.getId(), kind));
            }
            visualObjects.sort(Comparator.comparing(
                    ConceptUsageDto.VisualObjectReferenceDto::viewObjectId));

            viewReferences.add(new ConceptUsageDto.ViewReferenceDto(
                    view.getId(),
                    view.getName() != null ? view.getName() : "",
                    viewpointType,
                    viewKind,
                    visualObjects));
        }
        // viewName is normalised to "" above (never null), so a plain natural order
        // is sufficient here — the earlier `nullsFirst` was dead code.
        viewReferences.sort(Comparator
                .comparing(ConceptUsageDto.ViewReferenceDto::viewName)
                .thenComparing(ConceptUsageDto.ViewReferenceDto::viewId));

        return new ConceptUsageDto(
                concept.getId(),
                conceptName,
                conceptType,
                conceptKind,
                viewReferences.size(),
                visualReferenceCount,
                viewReferences,
                null);  // embeddingViewReferences reserved for batch-view support
    }

    /**
     * Derives the {@code viewKind} string for {@link ConceptUsageDto.ViewReferenceDto}.
     * Canvas views collapse to {@code "other"} because {@code ICanvasModel} lives
     * in the separate {@code com.archimatetool.canvas} plugin not on this bundle's
     * classpath (Task 0 OUTCOME pin — net negative to add a Require-Bundle for one
     * {@code instanceof} check that almost never fires).
     */
    static String deriveViewKind(IDiagramModel view) {
        if (view instanceof IArchimateDiagramModel) {
            return "archimate";
        }
        if (view instanceof ISketchModel) {
            return "sketch";
        }
        return "other";
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
            int specializationCount = model.getProfiles().size();
            Map<String, Integer> typeDistribution = buildTypeDistribution(allElements);
            Map<String, Integer> relTypeDistribution = buildRelationshipTypeDistribution(allRelationships);
            Map<String, Integer> layerDistribution = buildLayerDistribution(allElements);

            // Surface the model's own metadata for read-write parity
            // with update-model. Normalize empty → null (mirrors buildViewDto:12102-12109);
            // Jackson @JsonInclude(NON_NULL) omits null fields, preserving byte-identical
            // legacy responses on freshly-created models.
            String purpose = model.getPurpose();
            if (purpose != null && purpose.isEmpty()) {
                purpose = null;
            }
            Map<String, String> modelProperties = null;
            if (model.getProperties() != null && !model.getProperties().isEmpty()) {
                modelProperties = new LinkedHashMap<>();
                for (IProperty p : model.getProperties()) {
                    modelProperties.put(p.getKey(), p.getValue());
                }
            }

            return new ModelInfoDto(
                    model.getName(),
                    purpose,
                    modelProperties,
                    allElements.size(),
                    allRelationships.size(),
                    viewCount,
                    specializationCount,
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
            List<DiagramImageDto> images = new ArrayList<>();
            Set<String> seenElementIds = new HashSet<>();
            Set<String> seenRelationshipIds = new HashSet<>();

            collectViewContents(diagramModel, elements, relationships, visualMetadata,
                    connections, groups, notes, images,
                    seenElementIds, seenRelationshipIds, null);

            String viewpoint = diagramModel.getViewpoint();
            if (viewpoint != null && viewpoint.isEmpty()) {
                viewpoint = null;
            }
            String routerType = DtoMapper.mapConnectionRouterType(
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
                    notes.isEmpty() ? null : notes,
                    images.isEmpty() ? null : images));
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving view contents for view ID '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Batch retrieval methods ----

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
                    results.add(DtoMapper.convertToElementDto(element));
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

    // ---- Search methods ----

    @Override
    public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter,
                                           String specializationFilter) {
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
                if (layerFilter != null && !DtoMapper.resolveLayer(element).equals(layerFilter)) {
                    continue;
                }
                // Apply specialization filter (exact match, case-insensitive)
                if (specializationFilter != null) {
                    IProfile elemProfile = element.getPrimaryProfile();
                    if (elemProfile == null || !specializationFilter.equalsIgnoreCase(elemProfile.getName())) {
                        continue;
                    }
                }
                if (matchesQuery(element, lowerQuery)) {
                    results.add(DtoMapper.convertToElementDto(element));
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
        IProfile profile = element.getPrimaryProfile();
        if (profile != null && containsIgnoreCase(profile.getName(), lowerQuery)) {
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

    // ---- Relationship search methods ----

    @Override
    public List<RelationshipDto> searchRelationships(String query, String typeFilter,
                                                      String sourceLayerFilter, String targetLayerFilter,
                                                      String specializationFilter) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            String lowerQuery = query.toLowerCase();

            List<IArchimateRelationship> allRelationships = new ArrayList<>();
            for (IFolder folder : model.getFolders()) {
                collectRelationships(folder, allRelationships);
            }

            List<RelationshipDto> results = new ArrayList<>();
            for (IArchimateRelationship rel : allRelationships) {
                // skip orphaned relationships (not in containment tree)
                if (rel.eContainer() == null) {
                    logger.warn("Skipping orphaned relationship {} (no container)", rel.getId());
                    continue;
                }
                // Apply type filter before text matching (cheaper check first)
                if (typeFilter != null && !rel.eClass().getName().equals(typeFilter)) {
                    continue;
                }
                // Apply source layer filter
                if (sourceLayerFilter != null) {
                    IArchimateElement sourceElement = (IArchimateElement) rel.getSource();
                    if (sourceElement == null || !DtoMapper.resolveLayer(sourceElement).equals(sourceLayerFilter)) {
                        continue;
                    }
                }
                // Apply target layer filter
                if (targetLayerFilter != null) {
                    IArchimateElement targetElement = (IArchimateElement) rel.getTarget();
                    if (targetElement == null || !DtoMapper.resolveLayer(targetElement).equals(targetLayerFilter)) {
                        continue;
                    }
                }
                // Apply specialization filter (exact match, case-insensitive)
                if (specializationFilter != null) {
                    IProfile relSpecProfile = rel.getPrimaryProfile();
                    if (relSpecProfile == null || !specializationFilter.equalsIgnoreCase(relSpecProfile.getName())) {
                        continue;
                    }
                }
                if (matchesRelationshipQuery(rel, lowerQuery)) {
                    results.add(DtoMapper.convertToRelationshipDto(rel, false));
                }
            }
            return results;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error searching relationships with query '" + query + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    private boolean matchesRelationshipQuery(IArchimateRelationship relationship, String lowerQuery) {
        if (lowerQuery.isEmpty()) {
            return true; // wildcard: empty query matches all
        }
        if (containsIgnoreCase(relationship.getName(), lowerQuery)) {
            return true;
        }
        IProfile relProfile = relationship.getPrimaryProfile();
        if (relProfile != null && containsIgnoreCase(relProfile.getName(), lowerQuery)) {
            return true;
        }
        if (containsIgnoreCase(relationship.getDocumentation(), lowerQuery)) {
            return true;
        }
        for (IProperty property : relationship.getProperties()) {
            if (containsIgnoreCase(property.getValue(), lowerQuery)) {
                return true;
            }
        }
        return false;
    }

    // ---- Specialization listing ----

    @Override
    public List<Map<String, Object>> listSpecializations(String conceptTypeFilter) {
        IArchimateModel model = requireAndCaptureModel();
        try {
            List<Map<String, Object>> results = new ArrayList<>();
            for (IProfile profile : model.getProfiles()) {
                String conceptType = profile.getConceptType();
                if (conceptTypeFilter != null && !conceptTypeFilter.equals(conceptType)) {
                    continue;
                }

                String layer = null;
                EClass conceptClass = profile.getConceptClass();
                if (conceptClass != null) {
                    // Create a temporary instance to resolve the layer
                    org.eclipse.emf.ecore.EObject temp =
                            com.archimatetool.model.IArchimateFactory.eINSTANCE.create(conceptClass);
                    if (temp instanceof IArchimateElement tempElement) {
                        layer = DtoMapper.resolveLayer(tempElement);
                    } else {
                        layer = "Relationship";
                    }
                }

                int usageCount = ArchimateModelUtils.findProfileUsage(profile).size();

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", profile.getName());
                entry.put("conceptType", conceptType);
                entry.put("conceptTypeLayer", layer);
                entry.put("usageCount", usageCount);
                // AXIS D — surface imagePath when set (NON_NULL omission).
                String specImagePath = profile.getImagePath();
                if (specImagePath != null && !specImagePath.isEmpty()) {
                    entry.put("imagePath", specImagePath);
                }
                results.add(entry);
            }
            return results;
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error listing specializations",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Specialization mutations ----

    /**
     * Resolves the layer string for a profile's concept type. Reuses the
     * temporary-instance trick from {@link #listSpecializations(String)}.
     * Returns null if the conceptType cannot be resolved to a concrete EClass.
     */
    private String resolveLayerForConceptType(String conceptType) {
        EClassifier classifier = IArchimatePackage.eINSTANCE.getEClassifier(conceptType);
        if (!(classifier instanceof EClass eClass) || eClass.isAbstract()) {
            return null;
        }
        try {
            EObject temp = IArchimateFactory.eINSTANCE.create(eClass);
            if (temp instanceof IArchimateElement el) {
                return DtoMapper.resolveLayer(el);
            }
            return "Relationship";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates that a concept-type string resolves to a concrete, non-abstract
     * ArchiMate concept EClass. Throws {@link ModelAccessException} with
     * {@code INVALID_PARAMETER} on rejection. Five reject branches:
     * (1) null/blank, (2) unknown name, (3) not an EClass, (4) not a concept,
     * (5) abstract.
     */
    private EClass requireValidConceptType(String conceptType) {
        if (conceptType == null || conceptType.isBlank()) {
            throw new ModelAccessException(
                    "conceptType is required",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a concrete ArchiMate concept type, e.g., 'Node', "
                            + "'BusinessActor', 'ApplicationComponent', 'FlowRelationship'",
                    null);
        }
        EClassifier classifier = IArchimatePackage.eINSTANCE.getEClassifier(conceptType);
        if (classifier == null) {
            throw new ModelAccessException(
                    "Unknown ArchiMate concept type: '" + conceptType + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use a valid concrete EClass name, e.g., 'Node', 'BusinessActor', "
                            + "'ApplicationComponent', 'FlowRelationship', 'ServingRelationship', 'AndJunction'",
                    null);
        }
        if (!(classifier instanceof EClass eClass)) {
            throw new ModelAccessException(
                    "'" + conceptType + "' is not an EClass",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use a valid concrete EClass name, e.g., 'Node', 'BusinessActor', "
                            + "'FlowRelationship'",
                    null);
        }
        Class<?> instanceClass = eClass.getInstanceClass();
        if (instanceClass == null
                || !IArchimateConcept.class.isAssignableFrom(instanceClass)) {
            throw new ModelAccessException(
                    "'" + conceptType + "' is not an ArchiMate concept type",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Specializations can only bind to concept types (elements, relationships, "
                            + "junctions). Use e.g., 'Node', 'BusinessActor', 'FlowRelationship'",
                    null);
        }
        if (eClass.isAbstract()) {
            throw new ModelAccessException(
                    "'" + conceptType + "' is an abstract concept type and cannot have specializations",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use a concrete subtype, e.g., 'Node' instead of 'TechnologyElement', "
                            + "'BusinessActor' instead of 'BusinessElement'",
                    null);
        }
        return eClass;
    }

    /**
     * Builds the standard profile DTO map returned by all specialization tools.
     *
     * <p>Includes {@code imagePath} when non-null
     * (specialization-icon round-trip surface).</p>
     */
    private Map<String, Object> buildProfileMap(String name, String conceptType,
            String conceptTypeLayer, Boolean created, String imagePath) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("conceptType", conceptType);
        if (conceptTypeLayer != null) {
            map.put("conceptTypeLayer", conceptTypeLayer);
        }
        if (created != null) {
            map.put("created", created);
        }
        if (imagePath != null) {
            map.put("imagePath", imagePath);
        }
        return map;
    }

    /**
     * Prepares a create-specialization mutation. Idempotent: if the profile
     * already exists (case-insensitive name + conceptType match), returns a
     * NoOp command and {@code created: false}.
     *
     * <p>Optional {@code imagePath} sets the specialization's
     * icon. Validated at prepare boundary — empty string and missing
     * archive entries reject before EMF mutation. Idempotent re-creation
     * preserves the existing profile's imagePath (does not overwrite).</p>
     */
    private PreparedMutation<Map<String, Object>> prepareCreateSpecialization(
            String name, String conceptType, String imagePath) {
        IArchimateModel model = requireAndCaptureModel();

        if (name == null || name.isBlank()) {
            throw new ModelAccessException(
                    "Specialization name is required",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a non-blank name for the specialization",
                    null);
        }
        // imagePath empty-string explicitly rejected (closed
        // semantic — omit the parameter for "no icon"). isBlank() chosen to
        // match AXIS A's prepareAddImageToView discipline, keeping the
        // empty-check semantic consistent across axes.
        if (imagePath != null && imagePath.isBlank()) {
            throw new ModelAccessException(
                    "imagePath cannot be empty",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Omit the parameter to leave the specialization without an icon, "
                            + "or supply a valid path from add-image-to-model / list-model-images.",
                    null);
        }
        EClass eClass = requireValidConceptType(conceptType);
        // Use the canonical EClass name so the stored profile and the response DTO
        // use the metamodel's case (e.g., "Node" not "node").
        String canonicalConceptType = eClass.getName();
        String layer = resolveLayerForConceptType(canonicalConceptType);

        // Strict imagePath validation.
        if (imagePath != null) {
            validateImagePathExists(model, imagePath);
        }

        // Cache-aware existence check: also matches profiles created by prior ops
        // in the same bulk-mutate batch (keeps create-spec idempotent
        // even when bulked alongside another create-spec for the same name).
        IProfile existing = findProfileForBulkPrepare(model, name, canonicalConceptType);
        if (existing != null) {
            // Idempotent: return existing profile, no-op command, created=false.
            // Re-creation returns the EXISTING imagePath (not the
            // newly-supplied one) so that re-running a create-spec script doesn't
            // silently mutate icons. Use update-specialization to change the icon.
            Map<String, Object> dto = buildProfileMap(
                    existing.getName(), canonicalConceptType, layer, false,
                    existing.getImagePath());
            return new PreparedMutation<>(new NoOpCommand(), dto, existing.getId(), existing);
        }

        IProfile profile = IArchimateFactory.eINSTANCE.createProfile();
        profile.setName(InputValidation.reject(name, "name"));
        profile.setConceptType(canonicalConceptType);
        // Apply imagePath BEFORE CreateProfileCommand runs (mirror 14-7's
        // pre-command-attribute-application pattern).
        if (imagePath != null) {
            profile.setImagePath(imagePath);
        }
        // Publish to the bulk profile cache so that update/delete-specialization or
        // inline-specialization ops later in the same batch can find this in-flight
        // profile before the CreateProfileCommand has actually executed.
        Map<String, IProfile> cache = bulkProfileCache.get();
        if (cache != null) {
            cache.put(profileCacheKey(name, canonicalConceptType), profile);
        }
        Command cmd = new CreateProfileCommand(profile, model);
        // Read the name back off the profile the command will store, not off the request.
        Map<String, Object> dto = buildProfileMap(profile.getName(), canonicalConceptType, layer, true, imagePath);
        return new PreparedMutation<>(cmd, dto, profile.getId(), profile);
    }

    @Override
    public MutationResult<Map<String, Object>> createSpecialization(String sessionId,
            String name, String conceptType, String imagePath) {
        logger.info("Creating specialization: name={}, conceptType={}, imagePath={}",
                name, conceptType, imagePath);
        requireAndCaptureModel();
        try {
            PreparedMutation<Map<String, Object>> prepared = prepareCreateSpecialization(
                    name, conceptType, imagePath);

            // If the profile already existed (NoOp), short-circuit without dispatch
            // or approval — there is no change to commit.
            if (prepared.command() instanceof NoOpCommand) {
                return new MutationResult<>(prepared.entity(), null);
            }

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Create specialization: " + name
                        + " (" + conceptType + ")";
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("name", name);
                proposedChanges.put("conceptType", conceptType);
                if (imagePath != null) {
                    proposedChanges.put("imagePath", imagePath);
                }
                ProposalContext ctx = storeAsProposal(sessionId, "create-specialization",
                        () -> prepareCreateSpecialization(name, conceptType, imagePath),
                        targetIds(prepared.entityId()), prepared.entity(), description,
                        null, proposedChanges, "Specialization prepared for creation.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Create specialization: " + name);
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }
            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error creating specialization '" + name + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Prepares an update-specialization mutation (rename +
     * imagePath set / clear).
     *
     * <p>Relaxes the {@code newName}-required guard to an
     * "at least one of newName / imagePath / clearImagePath" guard. The
     * mutex between {@code imagePath} and {@code clearImagePath} is
     * enforced (providing both rejects with INVALID_PARAMETER).</p>
     */
    private PreparedMutation<Map<String, Object>> prepareUpdateSpecialization(
            String name, String conceptType, String newName,
            String imagePath, boolean clearImagePath) {
        IArchimateModel model = requireAndCaptureModel();

        // mutex — providing both imagePath and clearImagePath rejects.
        if (imagePath != null && clearImagePath) {
            throw new ModelAccessException(
                    "Provide either imagePath or clearImagePath, not both",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Supply imagePath to set/change the icon, OR clearImagePath=true to "
                            + "remove the icon — but not both in the same call.",
                    null);
        }

        // empty-string imagePath rejected (closed semantic).
        // isBlank() chosen to match AXIS A's prepareAddImageToView discipline
        // (keeps the empty-check semantic consistent across axes).
        if (imagePath != null && imagePath.isBlank()) {
            throw new ModelAccessException(
                    "imagePath cannot be empty",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use clearImagePath: true to explicitly clear the icon, or omit the "
                            + "parameter to leave it unchanged.",
                    null);
        }

        // at-least-one-of guard relaxes the OLD "newName required" guard.
        boolean willChangeName = newName != null && !newName.isBlank();
        boolean willChangeImagePath = imagePath != null || clearImagePath;
        if (!willChangeName && !willChangeImagePath) {
            throw new ModelAccessException(
                    "No fields to update — provide at least one of: newName, imagePath, clearImagePath",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Supply newName to rename, imagePath to set/change the icon, or "
                            + "clearImagePath=true to remove the icon.",
                    null);
        }

        EClass eClass = requireValidConceptType(conceptType);
        String canonicalConceptType = eClass.getName();
        String layer = resolveLayerForConceptType(canonicalConceptType);

        // Cache-aware lookup: matches profiles created by earlier bulk ops in the
        // same batch, before their commands have executed.
        IProfile profile = findProfileForBulkPrepare(model, name, canonicalConceptType);
        if (profile == null) {
            throw new ModelAccessException(
                    "Specialization not found: name='" + name + "', conceptType='"
                            + canonicalConceptType + "'",
                    ErrorCode.OBJECT_NOT_FOUND,
                    null,
                    "Use list-specializations to see defined specializations",
                    null);
        }

        // Collision check on rename (only when newName supplied).
        if (willChangeName) {
            IProfile collision = findProfileForBulkPrepare(model, newName, canonicalConceptType);
            if (collision != null && collision != profile) {
                throw new ModelAccessException(
                        "A specialization named '" + newName + "' already exists for "
                                + canonicalConceptType,
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Choose a different newName, or manually re-assign concepts to "
                                + "the existing specialization first if you intend to merge",
                        null);
            }
        }

        // Strict imagePath validation (only when setting).
        if (imagePath != null) {
            validateImagePathExists(model, imagePath);
        }

        UpdateProfileCommand.ImagePathChange imagePathChange;
        if (imagePath != null) {
            imagePathChange = UpdateProfileCommand.ImagePathChange.setTo(imagePath);
        } else if (clearImagePath) {
            imagePathChange = UpdateProfileCommand.ImagePathChange.clear();
        } else {
            imagePathChange = UpdateProfileCommand.ImagePathChange.unchanged();
        }

        // Determine the effective name + imagePath that the response DTO should reflect. The rename
        // is validated once and shared with the command below, so the reported name is the one that
        // will be written rather than the one that was requested.
        String validatedNewName = willChangeName ? InputValidation.reject(newName, "name") : null;

        String effectiveName = willChangeName ? validatedNewName : profile.getName();
        String effectiveImagePath;
        if (imagePath != null) {
            effectiveImagePath = imagePath;
        } else if (clearImagePath) {
            effectiveImagePath = null;
        } else {
            effectiveImagePath = profile.getImagePath();
        }

        Command cmd = new UpdateProfileCommand(profile, validatedNewName, imagePathChange);
        Map<String, Object> dto = buildProfileMap(effectiveName, canonicalConceptType,
                layer, null, effectiveImagePath);

        // Re-key the bulk cache so subsequent ops can find this profile under its new name. The
        // IProfile object's actual name remains the old name until UpdateProfileCommand executes
        // during phase 2, but the cache lookup keys off the *intended* state of the batch.
        //
        // Method-end (prepare succeeded) so a dropped op leaves no poisoned entry behind, the same
        // discipline AnchorResolver's pending-parent and effective-bounds writes follow. Written
        // any earlier, a rename this method goes on to refuse would strand the cache with the old
        // key gone and the new key bound to a profile that is never renamed; the call now keeps
        // going past a failed operation, so a later one in the same call would read that entry.
        // Placed after every statement that can throw rather than merely after the validation,
        // so the guarantee does not depend on nothing throwing between here and the return.
        //
        // Keyed by the VALIDATED name, which is what UpdateProfileCommand above will write.
        // InputValidation.reject is a pass-through that returns its argument unchanged or throws,
        // so this is the same string the caller supplied — keying off the validated value means
        // the cache cannot diverge from the command if that ever stops being true.
        if (willChangeName) {
            Map<String, IProfile> cache = bulkProfileCache.get();
            if (cache != null) {
                cache.remove(profileCacheKey(name, canonicalConceptType));
                cache.put(profileCacheKey(validatedNewName, canonicalConceptType), profile);
            }
        }

        return new PreparedMutation<>(cmd, dto, profile.getId(), profile);
    }

    @Override
    public MutationResult<Map<String, Object>> updateSpecialization(String sessionId,
            String name, String conceptType, String newName,
            String imagePath, boolean clearImagePath) {
        logger.info("Updating specialization: name={}, conceptType={}, newName={}, "
                + "imagePath={}, clearImagePath={}",
                name, conceptType, newName, imagePath, clearImagePath);
        requireAndCaptureModel();
        try {
            PreparedMutation<Map<String, Object>> prepared = prepareUpdateSpecialization(
                    name, conceptType, newName, imagePath, clearImagePath);

            boolean willChangeName = newName != null && !newName.isBlank();
            String descriptionSuffix;
            if (willChangeName && (imagePath != null || clearImagePath)) {
                descriptionSuffix = " (rename + icon)";
            } else if (willChangeName) {
                descriptionSuffix = " (rename)";
            } else if (clearImagePath) {
                descriptionSuffix = " (clear icon)";
            } else {
                descriptionSuffix = " (set icon)";
            }
            String description = "Update specialization: " + name + " (" + conceptType + ")"
                    + descriptionSuffix;

            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("name", name);
                proposedChanges.put("conceptType", conceptType);
                if (willChangeName) {
                    proposedChanges.put("newName", newName);
                }
                if (imagePath != null) {
                    proposedChanges.put("imagePath", imagePath);
                }
                if (clearImagePath) {
                    proposedChanges.put("clearImagePath", true);
                }
                ProposalContext ctx = storeAsProposal(sessionId, "update-specialization",
                        () -> prepareUpdateSpecialization(name, conceptType, newName, imagePath, clearImagePath),
                        targetIds(prepared.entityId()), prepared.entity(), description,
                        null, proposedChanges, "Specialization update prepared.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    description);
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }
            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error renaming specialization '" + name + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Prepares a delete-specialization mutation. With {@code force=false},
     * refuses if the profile is in use. With {@code force=true}, runs the
     * multi-profile guard then builds a compound that clears references and
     * deletes the profile atomically.
     */
    private PreparedMutation<Map<String, Object>> prepareDeleteSpecialization(
            String name, String conceptType, boolean force) {
        IArchimateModel model = requireAndCaptureModel();

        EClass eClass = requireValidConceptType(conceptType);
        String canonicalConceptType = eClass.getName();
        String layer = resolveLayerForConceptType(canonicalConceptType);

        // Cache-aware lookup: matches profiles created by earlier bulk ops in the
        // same batch, before their commands have executed.
        IProfile profile = findProfileForBulkPrepare(model, name, canonicalConceptType);
        if (profile == null) {
            throw new ModelAccessException(
                    "Specialization not found: name='" + name + "', conceptType='"
                            + canonicalConceptType + "'",
                    ErrorCode.OBJECT_NOT_FOUND,
                    null,
                    "Use list-specializations to see defined specializations",
                    null);
        }

        // ArchimateModelUtils.findProfileUsage returns a List<IProfiles> (Archi's
        // mixin interface for things-that-have-profiles). In practice every result
        // is also an IArchimateConcept since profiles only attach to concepts —
        // we narrow at iteration time.
        var rawUsages = ArchimateModelUtils.findProfileUsage(profile);
        List<IArchimateConcept> usages = new ArrayList<>();
        for (Object u : rawUsages) {
            if (u instanceof IArchimateConcept c) {
                usages.add(c);
            }
        }
        int usageCount = usages.size();

        if (usageCount > 0 && !force) {
            throw new ModelAccessException(
                    "Specialization '" + name + "' has " + usageCount + " usage"
                            + (usageCount == 1 ? "" : "s") + " and cannot be deleted",
                    ErrorCode.INVALID_PARAMETER,
                    "usageCount=" + usageCount,
                    "Pass force=true to clear references and delete in one undoable operation, "
                            + "or use get-specialization-usage to inspect impact first, "
                            + "or use update-specialization to rename instead",
                    null);
        }

        Command cmd;
        if (usages.isEmpty()) {
            // On a deferred path (queued/bulk) an earlier op in the same request can attach this
            // profile after prepare, when usages was still empty. Pass force so the command clears
            // that late attach at execution time instead of the plain delete declining on it.
            cmd = new DeleteProfileCommand(profile, model, force);
        } else {
            // Multi-profile guard: refuse force-delete if any usage concept holds
            // more than one profile, to prevent silent loss of co-existing
            // specializations. ClearSpecializationCommand wipes ALL profiles from
            // a concept, so we can only safely reuse it when each victim concept
            // holds exactly the one profile we're deleting.
            List<String> multiProfileViolations = new ArrayList<>();
            for (IArchimateConcept concept : usages) {
                if (concept.getProfiles().size() > 1) {
                    // Enumerate the *other* profiles that would be lost so the
                    // user knows which ones to detach manually before retrying.
                    List<String> otherProfileNames = new ArrayList<>();
                    for (IProfile other : concept.getProfiles()) {
                        if (other != profile) {
                            otherProfileNames.add("'" + other.getName() + "'");
                        }
                    }
                    multiProfileViolations.add("'" + concept.getName() + "' ("
                            + concept.getId() + ") would lose: "
                            + String.join(", ", otherProfileNames));
                }
            }
            if (!multiProfileViolations.isEmpty()) {
                String list = String.join("; ", multiProfileViolations);
                throw new ModelAccessException(
                        "Cannot force-delete: the following concepts have multiple "
                                + "specializations and would lose other profiles: " + list,
                        ErrorCode.INVALID_PARAMETER,
                        "multiProfileConceptCount=" + multiProfileViolations.size(),
                        "Detach the other specializations manually first via "
                                + "update-element/update-relationship, then retry the delete",
                        null);
            }

            NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(
                    "Delete specialization: " + name);
            for (IArchimateConcept concept : usages) {
                compound.add(new ClearSpecializationCommand(concept, Set.of(profile)));
            }
            // force is necessarily true on this branch (usages>0 with !force throws above). Pass it
            // so any usage attached AFTER prepare — beyond the ClearSpecializationCommands built
            // from the prepare-time snapshot — is also cleared at execution time.
            compound.add(new DeleteProfileCommand(profile, model, force));
            cmd = compound;
        }

        // Evict from the bulk profile cache so subsequent ops in the same batch
        // can no longer find this profile. The DeleteProfileCommand will remove
        // it from the model during phase 2 dispatch.
        Map<String, IProfile> cache = bulkProfileCache.get();
        if (cache != null) {
            cache.remove(profileCacheKey(name, canonicalConceptType));
        }

        Map<String, Object> dto = buildProfileMap(name, canonicalConceptType, layer, null,
                profile.getImagePath());
        dto.put("deleted", true);
        dto.put("clearedFromConcepts", usageCount);
        return new PreparedMutation<>(cmd, dto, profile.getId(), profile);
    }

    @Override
    public MutationResult<Map<String, Object>> deleteSpecialization(String sessionId,
            String name, String conceptType, boolean force) {
        logger.info("Deleting specialization: name={}, conceptType={}, force={}",
                name, conceptType, force);
        requireAndCaptureModel();
        try {
            PreparedMutation<Map<String, Object>> prepared = prepareDeleteSpecialization(
                    name, conceptType, force);

            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Delete specialization: " + name + " (" + conceptType + ")"
                        + (force ? " [force]" : "");
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("name", name);
                proposedChanges.put("conceptType", conceptType);
                proposedChanges.put("force", force);
                Object clearedCount = prepared.entity().get("clearedFromConcepts");
                if (clearedCount != null) {
                    proposedChanges.put("clearedFromConcepts", clearedCount);
                }
                ProposalContext ctx = storeAsProposal(sessionId, "delete-specialization",
                        () -> prepareDeleteSpecialization(name, conceptType, force),
                        targetIds(prepared.entityId()), prepared.entity(), description,
                        null, proposedChanges, "Specialization deletion prepared.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Delete specialization: " + name);
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }
            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error deleting specialization '" + name + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public Map<String, Object> getSpecializationUsage(String name, String conceptType) {
        logger.info("Getting specialization usage: name={}, conceptType={}", name, conceptType);
        IArchimateModel model = requireAndCaptureModel();
        try {
            EClass eClass = requireValidConceptType(conceptType);
            String canonicalConceptType = eClass.getName();
            String layer = resolveLayerForConceptType(canonicalConceptType);

            IProfile profile = ArchimateModelUtils.getProfileByNameAndType(
                    model, name, canonicalConceptType);
            if (profile == null) {
                throw new ModelAccessException(
                        "Specialization not found: name='" + name + "', conceptType='"
                                + canonicalConceptType + "'",
                        ErrorCode.OBJECT_NOT_FOUND,
                        null,
                        "Use list-specializations to see defined specializations",
                        null);
            }

            // findProfileUsage returns a List<IProfiles>; narrow to IArchimateConcept.
            var rawUsages = ArchimateModelUtils.findProfileUsage(profile);
            List<Map<String, Object>> elements = new ArrayList<>();
            List<Map<String, Object>> relationships = new ArrayList<>();
            int totalUsageCount = 0;
            for (Object u : rawUsages) {
                if (!(u instanceof IArchimateConcept concept)) {
                    continue;
                }
                totalUsageCount++;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", concept.getId());
                entry.put("name", concept.getName());
                entry.put("type", concept.eClass().getName());
                if (concept instanceof IArchimateRelationship) {
                    relationships.add(entry);
                } else {
                    elements.add(entry);
                }
            }

            Map<String, Object> result = buildProfileMap(
                    profile.getName(), canonicalConceptType, layer, null,
                    profile.getImagePath());
            result.put("totalUsageCount", totalUsageCount);
            result.put("elements", elements);
            result.put("relationships", relationships);
            return result;

        } catch (NoModelLoadedException | ModelAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error retrieving specialization usage for '" + name + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Relationship methods ----

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
                // skip orphaned relationships (not in containment tree)
                if (rel.eContainer() == null) continue;
                results.add(DtoMapper.convertToRelationshipDto(rel, false));
            }
            for (IArchimateRelationship rel : element.getTargetRelationships()) {
                // skip orphaned relationships (not in containment tree)
                if (rel.eContainer() == null) continue;
                results.add(DtoMapper.convertToRelationshipDto(rel, false));
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

    // ---- Folder navigation methods ----

    @Override
    public List<FolderDto> getRootFolders() {
        IArchimateModel model = requireAndCaptureModel();
        try {
            return FolderOperations.getRootFolders(model);
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
            return FolderOperations.getFolderById(model, id);
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
            return FolderOperations.getFolderChildren(model, parentId);
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
            return FolderOperations.getFolderTree(model, rootId, maxDepth);
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
            return FolderOperations.searchFolders(model, nameQuery);
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error searching folders with query '" + nameQuery + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Discovery-first patterns ----

    // Design note: Full model traversal via collectElements() is O(N) per call.
    // This is acceptable at typical ArchiMate model scale (hundreds to low thousands
    // of elements). If models grow significantly larger, consider an indexed cache.
    @Override
    public List<DuplicateCandidate> findDuplicates(String type, String name, String specialization) {
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
                // Specialization-aware comparison:
                // - Two elements with same name+type but different specializations are NOT duplicates
                // - Null specialization matches only null specialization (not "any")
                IProfile primaryProfile = element.getPrimaryProfile();
                String elementSpec = (primaryProfile != null) ? primaryProfile.getName() : null;
                if (!specializationsEqual(elementSpec, specialization)) {
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

    /**
     * Finds an existing relationship matching (type, source, target, specialization) exactly.
     * Used for idempotent create-relationship — returns existing instead of duplicating.
     * Efficient: only iterates source element's outgoing relationships, not the full model.
     *
     * <p>Specialization-aware: two relationships with the same type, source, and
     * target but different primary-profile names are NOT considered duplicates. Null
     * specialization matches only null specialization (case-insensitive when both non-null).</p>
     */
    private Optional<IArchimateRelationship> findDuplicateRelationship(
            EClass relClass, IArchimateElement source, IArchimateElement target,
            String specialization) {
        for (IArchimateRelationship rel : source.getSourceRelationships()) {
            if (rel.eClass() != relClass || rel.getTarget() != target) {
                continue;
            }
            IProfile primary = rel.getPrimaryProfile();
            String relSpec = (primary != null) ? primary.getName() : null;
            if (specializationsEqual(relSpec, specialization)) {
                return Optional.of(rel);
            }
        }
        return Optional.empty();
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
                    return Optional.of(DtoMapper.convertToElementDto(element));
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

    // ---- Mutation creation methods ----

    @Override
    public MutationResult<ElementDto> createElement(String sessionId, String type, String name,
            String documentation, Map<String, String> properties, String folderId,
            String specialization) {
        return createElement(sessionId, type, name, documentation, properties, folderId, null, specialization);
    }

    @Override
    public MutationResult<ElementDto> createElement(String sessionId, String type, String name,
            String documentation, Map<String, String> properties, String folderId,
            Map<String, String> source, String specialization) {
        logger.info("Creating element: type={}, name={}", type, name);
        requireAndCaptureModel();
        try {
            // Merge source traceability properties
            Map<String, String> mergedProperties = ConceptMetadata.mergeSourceProperties(properties, source);

            PreparedMutation<ElementDto> prepared = prepareCreateElement(type, name,
                    documentation, mergedProperties, folderId, specialization);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Create " + type + ": " + name;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("type", type);
                proposedChanges.put("name", name);
                ProposalBuilder.putIfPresent(proposedChanges, "documentation", documentation, "folderId", folderId,
                        "properties", properties, "source", source, "specialization", specialization);
                ProposalContext ctx = storeAsProposal(sessionId, "create-element",
                        () -> prepareCreateElement(type, name, documentation, mergedProperties, folderId, specialization),
                        targetIds(prepared.entityId()), prepared.entity(), description,
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
            String sourceId, String targetId, String name, String specialization,
            RelationshipSemanticAttributes semanticAttributes,
            String documentation, Map<String, String> properties, Map<String, String> source) {
        logger.info("Creating relationship: type={}, source={}, target={}", type, sourceId, targetId);
        requireAndCaptureModel();
        RelationshipSemanticAttributes attrs = (semanticAttributes != null)
                ? semanticAttributes : RelationshipSemanticAttributes.NONE;
        // Merged here, as createElement does, so no prepare can store an unprefixed source key.
        Map<String, String> mergedProperties = ConceptMetadata.mergeSourceProperties(properties, source);
        try {
            PreparedMutation<RelationshipDto> prepared = prepareCreateRelationship(
                    type, sourceId, targetId, name, specialization, attrs,
                    documentation, mergedProperties);

            // Duplicate detected: return existing relationship without dispatching
            if (prepared.entity().alreadyExisted()) {
                return new MutationResult<>(prepared.entity(), null);
            }

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Create " + type + ": " + sourceId + " → " + targetId;
                // rich, non-spoofable effect text naming the endpoints (degrade to id when
                // an endpoint has no name). The entity already carries the resolved source/target
                // names (prepareCreateRelationship), so no extra model lookup is needed here.
                RelationshipDto relDto = prepared.entity();
                String srcDisplay = (relDto.sourceName() != null && !relDto.sourceName().isBlank())
                        ? relDto.sourceName() : sourceId;
                String tgtDisplay = (relDto.targetName() != null && !relDto.targetName().isBlank())
                        ? relDto.targetName() : targetId;
                String effectDescription = formatRelationshipEffect(
                        "Create", type, srcDisplay, tgtDisplay, null);
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("type", type);
                proposedChanges.put("sourceId", sourceId);
                proposedChanges.put("targetId", targetId);
                ProposalBuilder.putIfPresent(proposedChanges, "name", name, "specialization", specialization,
                        "accessType", attrs.accessType(), "associationDirected", attrs.associationDirected(), "influenceStrength", attrs.influenceStrength());
                // Blank-guarded like the write, so the card cannot disclose a declined value.
                if (documentation != null && !documentation.isBlank()) proposedChanges.put("documentation", documentation);
                ProposalBuilder.putIfPresent(proposedChanges, "properties", properties, "source", source);
                ProposalContext ctx = storeAsProposal(sessionId, "create-relationship",
                        () -> prepareCreateRelationship(type, sourceId, targetId, name, specialization, attrs,
                                documentation, mergedProperties),
                        targetIds(sourceId, targetId), prepared.entity(), description,
                        null, proposedChanges,
                        "Relationship type valid. Source and target exist. ArchiMate spec compliant.",
                        effectDescription, null);
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

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Create view: " + name;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("name", name);
                ProposalBuilder.putIfPresent(proposedChanges, "viewpoint", viewpoint, "folderId", folderId);
                if (connectionRouterType != null) {
                    proposedChanges.put("connectionRouterType", connectionRouterType);
                }
                ProposalContext ctx = storeAsProposal(sessionId, "create-view",
                        () -> prepareCreateView(name, viewpoint, folderId, connectionRouterType),
                        targetIds(prepared.entityId()), prepared.entity(), description,
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

    @Override
    public MutationResult<ViewDto> cloneView(String sessionId, String sourceViewId,
            String newName, String folderId) {
        logger.info("Cloning view: sourceViewId={}, newName={}", sourceViewId, newName);
        requireAndCaptureModel();
        try {
            PreparedMutation<ViewDto> prepared = prepareCloneView(sourceViewId, newName, folderId);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Clone view: " + newName + " (from " + sourceViewId + ")";
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("sourceViewId", sourceViewId);
                proposedChanges.put("newName", newName);
                if (folderId != null) proposedChanges.put("folderId", folderId);
                ProposalContext ctx = storeAsProposal(sessionId, "clone-view",
                        () -> prepareCloneView(sourceViewId, newName, folderId),
                        targetIds(sourceViewId), prepared.entity(), description,
                        null, proposedChanges, "View clone prepared.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Clone view: " + newName);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error cloning view from '{}'", sourceViewId, e);
            throw new ModelAccessException(
                    "Error cloning view from '" + sourceViewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Mutation update methods ----

    @Override
    public MutationResult<ElementDto> updateElement(String sessionId, String id, String name,
            String documentation, Map<String, String> properties, String specialization) {
        logger.info("Updating element: id={}", id);
        requireAndCaptureModel();
        try {
            PreparedMutation<ElementDto> prepared = prepareUpdateElement(id, name,
                    documentation, properties, specialization);

            // Approval gate
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
                ProposalBuilder.putIfPresent(proposedChanges, "name", name, "documentation", documentation,
                        "properties", properties, "specialization", specialization);
                ProposalContext ctx = storeAsProposal(sessionId, "update-element",
                        () -> prepareUpdateElement(id, name, documentation, properties, specialization),
                        targetIds(id), prepared.entity(), description,
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
                entity = DtoMapper.convertToElementDto(elem);
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
    public MutationResult<RelationshipDto> updateRelationship(String sessionId, String id,
            String name, String documentation, Map<String, String> properties, String specialization,
            RelationshipSemanticAttributes semanticAttributes) {
        logger.info("Updating relationship: id={}", id);
        requireAndCaptureModel();
        RelationshipSemanticAttributes attrs = (semanticAttributes != null)
                ? semanticAttributes : RelationshipSemanticAttributes.NONE;
        try {
            PreparedMutation<RelationshipDto> prepared = prepareUpdateRelationship(id, name,
                    documentation, properties, specialization, attrs);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Update relationship: " + id;
                RelationshipDto current = prepared.entity();
                Map<String, Object> currentState = new LinkedHashMap<>();
                currentState.put("name", current.name());
                if (current.documentation() != null) {
                    currentState.put("documentation", current.documentation());
                }
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                ProposalBuilder.putIfPresent(proposedChanges, "name", name, "documentation", documentation,
                        "properties", properties, "specialization", specialization, "accessType", attrs.accessType(),
                        "associationDirected", attrs.associationDirected(), "influenceStrength", attrs.influenceStrength());
                ProposalContext ctx = storeAsProposal(sessionId, "update-relationship",
                        () -> prepareUpdateRelationship(id, name, documentation, properties, specialization, attrs),
                        targetIds(id), prepared.entity(), description,
                        currentState, proposedChanges, "Relationship exists. All changes valid.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Update relationship: " + id);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            // Re-read relationship state after command execution
            RelationshipDto entity;
            if (batchSeq == null && prepared.rawObject() instanceof IArchimateRelationship rel) {
                entity = DtoMapper.convertToRelationshipDto(rel, true);
            } else {
                entity = prepared.entity();
            }

            return new MutationResult<>(entity, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error updating relationship with ID '" + id + "'",
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

            // Approval gate
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
                ProposalBuilder.putIfPresent(proposedChanges, "documentation", documentation, "properties", properties);
                if ("".equals(connectionRouterType)) {
                    proposedChanges.put("connectionRouterType", "(clear to manual)");
                } else if (connectionRouterType != null) {
                    proposedChanges.put("connectionRouterType", connectionRouterType);
                }
                ProposalContext ctx = storeAsProposal(sessionId, "update-view",
                        () -> prepareUpdateView(id, name, viewpoint, documentation, properties, connectionRouterType),
                        targetIds(id), prepared.entity(), description,
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
                entity = DtoMapper.buildViewDto(view);
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

    // ---- Model metadata mutation ----

    @Override
    public MutationResult<ModelInfoDto> updateModel(String sessionId, String name,
            String purpose, Map<String, String> properties) {
        logger.info("Updating model metadata: name={}, purpose={}, properties={}",
                name != null ? "<set>" : "<unchanged>",
                purpose != null ? ("\"" + purpose + "\"") : "<unchanged>",
                properties != null ? properties.size() + " entries" : "<unchanged>");
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<ModelInfoDto> prepared = prepareUpdateModel(name, purpose, properties);

            // Approval gate — mirror updateView shape
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Update model: " + model.getName();
                ModelInfoDto current = prepared.entity();
                Map<String, Object> currentState = new LinkedHashMap<>();
                currentState.put("name", current.name());
                if (current.purpose() != null) {
                    currentState.put("purpose", current.purpose());
                }
                if (current.properties() != null) {
                    currentState.put("properties", current.properties());
                }
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                if (name != null) proposedChanges.put("name", name);
                // Show "(clear)" for empty-string purpose so reviewer understands intent
                if ("".equals(purpose)) {
                    proposedChanges.put("purpose", "(clear)");
                } else if (purpose != null) {
                    proposedChanges.put("purpose", purpose);
                }
                // Empty map is a no-op (same as null/omitted); don't surface it to the approver.
                if (properties != null && !properties.isEmpty()) {
                    proposedChanges.put("properties", properties);
                }
                ProposalContext ctx = storeAsProposal(sessionId, "update-model",
                        () -> prepareUpdateModel(name, purpose, properties),
                        targetIds(), prepared.entity(), description,
                        currentState, proposedChanges, "Model exists. All changes valid.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Update model: " + model.getName());

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            // Re-read model state after command execution. Re-calling getModelInfo() performs
            // a full element/relationship traversal for counts/distributions; acceptable here
            // because update-model is a low-frequency, model-level operation (NOT per-element).
            // Cross-LLM review Finding 3 (LOW, acknowledged).
            ModelInfoDto entity = batchSeq == null ? getModelInfo() : prepared.entity();

            return new MutationResult<>(entity, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error updating model metadata",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- View export ----

    @Override
    public ExportResult exportView(String viewId, String format, double scale, int quality,
            boolean inline, String outputDirectory) {
        logger.info("Exporting view: viewId={}, format={}, scale={}, quality={}, inline={}, outputDirectory={}",
                viewId, format, scale, quality, inline, outputDirectory);
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
                return ViewExportService.renderPng(diagramModel, scale, inline, outputDirectory);
            } else if ("jpg".equals(format)) {
                return ViewExportService.renderJpg(diagramModel, scale, quality, inline,
                        outputDirectory);
            } else if ("svg".equals(format)) {
                return ViewExportService.renderSvg(diagramModel, scale, inline, outputDirectory);
            } else if ("pdf".equals(format)) {
                return ViewExportService.renderPdf(diagramModel, scale, inline, outputDirectory);
            } else {
                throw new ModelAccessException(
                        "Unsupported export format: " + format
                                + ". Supported formats: png, jpg, svg, pdf",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Use one of: png (lossless raster), jpg (lossy raster), svg (vector), pdf (vector, print-ready)",
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

    // ---- View placement ----

    @Override
    public MutationResult<AddToViewResultDto> addToView(String sessionId, String viewId,
            String elementId, Integer x, Integer y, Integer width, Integer height,
            boolean autoConnect, String parentViewObjectId, StylingParams styling,
            ImageParams imageParams) {
        logger.info("Adding element to view: viewId={}, elementId={}, autoConnect={}, parentViewObjectId={}",
                viewId, elementId, autoConnect, parentViewObjectId);
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<AddToViewResultDto> prepared = prepareAddToView(
                    sessionId, viewId, elementId, x, y, width, height, autoConnect, parentViewObjectId,
                    mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId), styling, imageParams,
                    mutationDispatcher.queuedCreatedView(sessionId, viewId),
                    mutationDispatcher.queuedCreatedElement(sessionId, elementId));

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String elementName = prepared.entity().viewObject().elementName();
                String elementType = prepared.entity().viewObject().elementType();
                String description = "Add " + elementType + " '" + elementName + "' to view"
                        + viewNameClause(resolveViewName(model, viewId));
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("elementId", elementId);
                ProposalBuilder.putBounds(proposedChanges, x, y, width, height);
                proposedChanges.put("autoConnect", autoConnect);
                ProposalBuilder.putIfPresent(proposedChanges, "parentViewObjectId", parentViewObjectId);
                ProposalBuilder.putContainerVisuals(proposedChanges, styling, imageParams);
                String validationSummary = AutoConnectSkip.discloseOnCard(proposedChanges, prepared.entity());
                ProposalContext ctx = storeAsProposal(sessionId, "add-to-view",
                        () -> prepareAddToView(sessionId, viewId, elementId, x, y, width, height, autoConnect,
                                parentViewObjectId, mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId), styling, imageParams,
                                mutationDispatcher.queuedCreatedView(sessionId, viewId),
                                mutationDispatcher.queuedCreatedElement(sessionId, elementId)),
                        targetIds(viewId, elementId), prepared.entity(), description,
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
            String parentViewObjectId, StylingParams styling, ImageParams imageParams) {
        logger.info("Adding group to view: viewId={}, label={}", viewId, label);
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<ViewGroupDto> prepared = prepareAddGroupToView(
                    sessionId, viewId, label, x, y, width, height, parentViewObjectId,
                    mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId), styling, imageParams,
                    mutationDispatcher.queuedCreatedView(sessionId, viewId));

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = (label.isBlank() ? "Add an untitled group to view" : "Add group '" + label + "' to view")
                        + viewNameClause(resolveViewName(model, viewId));
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("label", label);
                ProposalBuilder.putBounds(proposedChanges, x, y, width, height);
                ProposalBuilder.putIfPresent(proposedChanges, "parentViewObjectId", parentViewObjectId);
                ProposalBuilder.putContainerVisuals(proposedChanges, styling, imageParams);
                ProposalContext ctx = storeAsProposal(sessionId, "add-group-to-view",
                        () -> prepareAddGroupToView(sessionId, viewId, label, x, y, width, height,
                                parentViewObjectId, mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId), styling, imageParams,
                                mutationDispatcher.queuedCreatedView(sessionId, viewId)),
                        targetIds(viewId), prepared.entity(), description,
                        null, proposedChanges, "Group ready for placement on view.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    label.isBlank() ? "Add an untitled group to view" : "Add group to view: " + label);

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
            String content, String position, Integer gap, Integer x, Integer y,
            Integer width, Integer height,
            String parentViewObjectId, StylingParams styling, ImageParams imageParams) {
        logger.info("Adding note to view: viewId={}", viewId);
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<ViewNoteDto> prepared = prepareAddNoteToView(
                    sessionId, viewId, content, position, gap, x, y, width, height,
                    parentViewObjectId, mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId), styling, imageParams,
                    mutationDispatcher.queuedCreatedView(sessionId, viewId));

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String truncatedContent = content.length() > 40 ? content.substring(0, 40) + "..." : content;
                String description = (content.isBlank() ? "Add an empty note to view" : "Add note to view") + viewNameClause(resolveViewName(model, viewId)) + (content.isBlank() ? "" : ": " + truncatedContent);
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("content", content);
                ProposalBuilder.putIfPresent(proposedChanges, "position", position, "gap", gap);
                ProposalBuilder.putBounds(proposedChanges, x, y, width, height);
                ProposalBuilder.putIfPresent(proposedChanges, "parentViewObjectId", parentViewObjectId);
                ProposalBuilder.putVisuals(proposedChanges, styling, imageParams);
                ProposalContext ctx = storeAsProposal(sessionId, "add-note-to-view",
                        () -> prepareAddNoteToView(sessionId, viewId, content, position, gap, x, y, width, height,
                                parentViewObjectId, mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId), styling, imageParams,
                                mutationDispatcher.queuedCreatedView(sessionId, viewId)),
                        targetIds(viewId), prepared.entity(), description,
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
    public MutationResult<EmbeddedViewDto> addViewReferenceToView(String sessionId,
            String viewId, String referencedViewId, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            StylingParams styling) {
        logger.info("Adding view-reference to view: viewId={}, referencedViewId={}",
                viewId, referencedViewId);
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<EmbeddedViewDto> prepared = prepareAddViewReferenceToView(
                    sessionId, viewId, referencedViewId, x, y, width, height,
                    parentViewObjectId, mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId),
                    mutationDispatcher.queuedCreatedView(sessionId, viewId), null, styling);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Add view-reference to view"
                        + viewNameClause(resolveViewName(model, viewId)) + ": " + referencedViewId;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("referencedViewId", referencedViewId);
                ProposalBuilder.putBounds(proposedChanges, x, y, width, height);
                ProposalBuilder.putIfPresent(proposedChanges, "parentViewObjectId", parentViewObjectId);
                ProposalBuilder.putStyling(proposedChanges, styling);
                ProposalContext ctx = storeAsProposal(sessionId, "add-view-reference-to-view",
                        () -> prepareAddViewReferenceToView(sessionId, viewId, referencedViewId, x, y, width, height,
                                parentViewObjectId, mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId),
                                mutationDispatcher.queuedCreatedView(sessionId, viewId), null, styling),
                        targetIds(viewId, referencedViewId), prepared.entity(), description,
                        null, proposedChanges, "View-reference ready for placement on view.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Add view reference to view");

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error adding view-reference to view '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<DiagramImageDto> addImageToView(String sessionId,
            String viewId, String imagePath, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            StylingParams styling, String borderColor, String documentation) {
        logger.info("Adding image visual to view: viewId={}, imagePath={}",
                viewId, imagePath);
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<DiagramImageDto> prepared = prepareAddImageToView(
                    sessionId, viewId, imagePath, x, y, width, height,
                    parentViewObjectId, mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId),
                    mutationDispatcher.queuedCreatedView(sessionId, viewId), styling,
                    borderColor, documentation);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Add image visual to view"
                        + viewNameClause(resolveViewName(model, viewId)) + ": " + imagePath;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("imagePath", imagePath);
                ProposalBuilder.putBounds(proposedChanges, x, y, width, height);
                ProposalBuilder.putIfPresent(proposedChanges, "parentViewObjectId", parentViewObjectId,
                        "borderColor", borderColor, "documentation", documentation);
                ProposalBuilder.putStyling(proposedChanges, styling);
                ProposalContext ctx = storeAsProposal(sessionId, "add-image-to-view",
                        () -> prepareAddImageToView(sessionId, viewId, imagePath, x, y, width, height,
                                parentViewObjectId, mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId),
                                mutationDispatcher.queuedCreatedView(sessionId, viewId), styling, borderColor, documentation),
                        targetIds(viewId), prepared.entity(), description,
                        null, proposedChanges, "Image visual ready for placement on view.");
                return new MutationResult<>(prepared.entity(), null, ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, prepared.command(),
                    "Add image to view");

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(prepared.entity(), batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error adding image visual to view '" + viewId + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public MutationResult<ViewConnectionDto> addConnectionToView(String sessionId, String viewId,
            String relationshipId, String sourceViewObjectId, String targetViewObjectId,
            List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints,
            StylingParams styling, Boolean showLabel, Integer textPosition) {
        logger.info("Adding connection to view: viewId={}, relationshipId={}", viewId, relationshipId);
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<ViewConnectionDto> prepared = prepareAddConnectionToView(
                    sessionId, viewId, relationshipId, sourceViewObjectId, targetViewObjectId,
                    bendpoints, absoluteBendpoints, styling, showLabel, textPosition);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                // name the destination view (the relationship pre-exists → viewId resolves
                // cleanly pre-execute, no dangling-endpoint trap). The dangling "to view" token gets the name spliced
                // in; an unresolvable view degrades to the bare "to view".
                String viewName = resolveViewName(model, viewId);
                String description = "Add connection for relationship " + relationshipId + " to view"
                        + viewNameClause(viewName);
                // name the relationship's endpoints (the relationship already exists on the
                // visual-connection path → resolves cleanly pre-execute, no dangling-endpoint trap). A nameless
                // endpoint degrades to its id; an unresolvable relationship falls back to description.
                String[] ends = resolveRelationshipEndpointNames(model, relationshipId);
                String relType = prepared.entity().relationshipType();
                String effectDescription = (ends != null && relType != null && !relType.isBlank())
                        ? formatRelationshipEffect("Add connection", relType,
                                ends[0] != null ? ends[0] : relationshipId,
                                ends[1] != null ? ends[1] : relationshipId,
                                viewPhrase(viewName, "to"))
                        : description;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("relationshipId", relationshipId);
                proposedChanges.put("sourceViewObjectId", sourceViewObjectId);
                proposedChanges.put("targetViewObjectId", targetViewObjectId);
                if (bendpoints != null) proposedChanges.put("bendpointCount", bendpoints.size());
                if (absoluteBendpoints != null) proposedChanges.put("absoluteBendpointCount", absoluteBendpoints.size());
                ProposalBuilder.putIfPresent(proposedChanges, "showLabel", showLabel, "textPosition", textPosition);
                ProposalBuilder.putStyling(proposedChanges, styling);
                ProposalContext ctx = storeAsProposal(sessionId, "add-connection-to-view",
                        // The queue lookups sit inside the rebuild so they re-run on approve. A batch
                        // that commits or rolls back in between correctly stops resolving, and the
                        // live lookups take over.
                        () -> prepareAddConnectionToView(sessionId, viewId, relationshipId, sourceViewObjectId,
                                targetViewObjectId, bendpoints, absoluteBendpoints, styling, showLabel, textPosition),
                        targetIds(viewId, relationshipId, sourceViewObjectId, targetViewObjectId),
                        prepared.entity(), description,
                        null, proposedChanges, "Connection ready for placement on view.",
                        effectDescription, null);
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

    // ---- View editing and removal ----

    @Override
    public MutationResult<ViewObjectDto> updateViewObject(String sessionId, String viewObjectId,
            Integer x, Integer y, Integer width, Integer height, String text,
            StylingParams styling, ImageParams imageParams, String labelExpression, String anchorTarget, String anchorEdge, Integer anchorDx, Integer anchorDy) {
        logger.info("Updating view object: viewObjectId={}, text={}, labelExpression={}",
                viewObjectId, text != null ? "provided" : "null",
                labelExpression != null ? "provided" : "null");
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<ViewObjectDto> prepared = prepareUpdateViewObject(
                    viewObjectId, x, y, width, height, text, styling, imageParams, labelExpression,
                    anchorTarget, anchorEdge, anchorDx, anchorDy,
                    mutationDispatcher.queuedViewObject(sessionId, viewObjectId), mutationDispatcher.queuedViewObject(sessionId, anchorTarget),
                    mutationDispatcher.queuedParents(sessionId), mutationDispatcher.queuedBounds(sessionId), mutationDispatcher.queuedAnchors(sessionId), null, null);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String elementName = prepared.entity().elementName();
                String elementType = prepared.entity().elementType();
                // name the view the object lives in (resolved from its owning diagram, since
                // this tool takes only viewObjectId). Unresolvable view degrades to no clause.
                // viewPhrase returns null on degradation (base has no dangling "view" token to fill),
                // so the ternary is required here — not viewNameClause, which would add a trailing space.
                String viewClause = viewPhrase(resolveViewName(model, viewObjectId), "in");
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                ProposalBuilder.putBounds(proposedChanges, x, y, width, height);
                ProposalBuilder.putIfPresent(proposedChanges, "text", text, "labelExpression", labelExpression,
                        "anchorTarget", anchorTarget, "anchorEdge", anchorEdge, "anchorDx", anchorDx, "anchorDy", anchorDy);
                ProposalBuilder.putVisuals(proposedChanges, styling, imageParams);
                String description = UpdateViewObjectCardText.description(elementType, elementName, viewClause, proposedChanges);
                ProposalContext ctx = storeAsProposal(sessionId, "update-view-object",
                        () -> prepareUpdateViewObject(viewObjectId, x, y, width, height, text,
                                styling, imageParams, labelExpression,
                                anchorTarget, anchorEdge, anchorDx, anchorDy,
                                mutationDispatcher.queuedViewObject(sessionId, viewObjectId), mutationDispatcher.queuedViewObject(sessionId, anchorTarget),
                                mutationDispatcher.queuedParents(sessionId), mutationDispatcher.queuedBounds(sessionId), mutationDispatcher.queuedAnchors(sessionId), null, null),
                        targetIds(viewObjectId), prepared.entity(), description,
                        null, proposedChanges, UpdateViewObjectCardText.validationSummary(proposedChanges));
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
            List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling,
            Boolean showLabel, Integer textPosition) {
        logger.info("Updating view connection: viewConnectionId={}", viewConnectionId);
        IArchimateModel model = requireAndCaptureModel();
        try {
            // A connection an earlier operation in this batch added is still detached, so it is
            // handed over directly rather than looked up by id; null for a live one.
            PreparedMutation<ViewConnectionDto> prepared = prepareUpdateViewConnection(
                    viewConnectionId, bendpoints, absoluteBendpoints, styling, showLabel, textPosition,
                    mutationDispatcher.queuedViewConnection(sessionId, viewConnectionId));

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String relType = prepared.entity().relationshipType();
                // name the view the connection lives in (resolved from its owning diagram, since
                // this tool takes only viewConnectionId). Unresolvable view degrades to no clause —
                // viewPhrase returns null (base has no dangling "view" token to fill) and the card
                // text drops it, rather than viewNameClause, which would add a trailing space.
                String viewName = resolveViewName(model, viewConnectionId);
                String viewClause = viewPhrase(viewName, "in");
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                if (bendpoints != null) proposedChanges.put("bendpointCount", bendpoints.size());
                if (absoluteBendpoints != null) proposedChanges.put("absoluteBendpointCount", absoluteBendpoints.size());
                ProposalBuilder.putIfPresent(proposedChanges, "showLabel", showLabel, "textPosition", textPosition);
                ProposalBuilder.putStyling(proposedChanges, styling);
                String description = UpdateViewConnectionCardText.description(relType, viewClause, proposedChanges);
                // name the relationship's endpoints; bendpoint detail drops to Technical details
                // (proposedChanges). The relationship pre-exists → resolves cleanly (no dangling-endpoint trap).
                String relationshipId = prepared.entity().relationshipId();
                String[] ends = resolveRelationshipEndpointNames(model, relationshipId);
                String effectDescription = (ends != null && relType != null && !relType.isBlank())
                        ? formatRelationshipEffect("Update connection", relType,
                                ends[0] != null ? ends[0] : relationshipId,
                                ends[1] != null ? ends[1] : relationshipId,
                                viewClause)
                        : description;
                // The queue lookup sits inside the rebuild so it re-runs on approve. A batch that
                // commits or rolls back in between correctly stops resolving, and the live lookup
                // takes over: by then the connection either exists or genuinely does not.
                ProposalContext ctx = storeAsProposal(sessionId, "update-view-connection",
                        () -> prepareUpdateViewConnection(viewConnectionId, bendpoints,
                                absoluteBendpoints, styling, showLabel, textPosition,
                                mutationDispatcher.queuedViewConnection(sessionId, viewConnectionId)),
                        targetIds(viewConnectionId), prepared.entity(), description,
                        null, proposedChanges, UpdateViewConnectionCardText.validationSummary(proposedChanges),
                        effectDescription, null);
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

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description;
                RemoveFromViewResultDto dto = prepared.entity();
                // name the view (this tool carries viewId). The name is spliced after the
                // "from view" token (before the cascade clause); unresolvable view degrades to bare.
                String viewClause = viewNameClause(resolveViewName(model, viewId));
                if ("viewObject".equals(dto.removedObjectType())) {
                    int cascadeCount = dto.cascadeRemovedConnectionIds() != null
                            ? dto.cascadeRemovedConnectionIds().size() : 0;
                    // Resolve element type/name for traceability
                    String elementInfo = resolveElementInfo(model, viewObjectId);
                    description = "Remove " + elementInfo + " from view" + viewClause
                            + (cascadeCount > 0 ? " (and " + cascadeCount + " attached connection"
                            + (cascadeCount > 1 ? "s" : "") + ")" : "");
                } else {
                    // Resolve relationship type for traceability
                    String connectionInfo = resolveConnectionInfo(model, viewObjectId);
                    description = "Remove connection (" + connectionInfo + ") from view" + viewClause;
                }
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("viewObjectId", viewObjectId);
                proposedChanges.put("removedObjectType", dto.removedObjectType());
                ProposalContext ctx = storeAsProposal(sessionId, "remove-from-view",
                        () -> prepareRemoveFromView(viewId, viewObjectId),
                        targetIds(viewId, viewObjectId), prepared.entity(), description,
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

            // Approval gate
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
                        () -> prepareClearView(viewId),
                        targetIds(viewId), prepared.entity(), description,
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

    // ---- Compound layout method ----

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

            // Validate view exists. Every id this tool takes — the view, each position's object and
            // each connection — may name something an earlier operation in the same batch queued but
            // has not created yet, so all three resolve through the queue before the live lookup.
            resolveViewOrThrow(model, viewId, mutationDispatcher.queuedCreatedView(sessionId, viewId));

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

            // Phase 1: Validate all entries and build commands (Jetty thread). Both walks run to
            // completion rather than unwinding at the first bad entry: a caller replaying a saved
            // layout onto a rebuilt view has every id stale, and refusing one at a time costs it
            // one whole round-trip per defect on a payload that may carry ten thousand of them.
            List<Command> commands = new ArrayList<>();
            Map<String, int[]> passBounds = sameBatchBounds(sessionId); Map<String, IDiagramModelContainer> qParents = mutationDispatcher.queuedParents(sessionId); Map<String, int[]> qBounds = mutationDispatcher.queuedBounds(sessionId); Map<String, String[]> qAnchors = mutationDispatcher.queuedAnchors(sessionId); Map<String, Command> passFitCommands = new LinkedHashMap<>(); // one map for the whole call, so entry i+1 fits a shared group against what entry i decided rather than its pre-call size. All four are read ONCE: every projection walks the whole command queue under the session lock, nothing dispatches until Phase 2 so none can change mid-loop, and this loop runs up to MAX_LAYOUT_OPERATIONS times
            int positionCount = 0;
            int connectionCount = 0;
            LayoutValidationFailures failures = new LayoutValidationFailures();

            if (hasPositions) {
                for (int i = 0; i < positions.size(); i++) {
                    ViewPositionSpec pos = positions.get(i);
                    try {
                        PreparedMutation<ViewObjectDto> prepared =
                                prepareUpdateViewObject(pos.viewObjectId(),
                                        pos.x(), pos.y(), pos.width(), pos.height(),
                                        null, null, null, null, null, null, null, null, // no text/styling/image/labelExpression/anchor for layout
                                        mutationDispatcher.queuedViewObject(sessionId, pos.viewObjectId()), null, qParents,
                                        qBounds, qAnchors, passBounds, passFitCommands); // the anchor slot alone stays null: this tool extracts no anchor params
                        commands.add(prepared.command());
                        positionCount++;
                    } catch (ModelAccessException e) {
                        failures.recordPosition(i, pos.viewObjectId(), e);
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
                                        bps, absBps, null, null, null,
                                        mutationDispatcher.queuedViewConnection(
                                                sessionId, conn.viewConnectionId()));
                        commands.add(prepared.command());
                        connectionCount++;
                    } catch (ModelAccessException e) {
                        failures.recordConnection(i, conn.viewConnectionId(), e);
                    }
                }
            }

            // Refuse once, after both walks, so a bad positions entry cannot hide the whole
            // connections array. Nothing has been dispatched: the compound, the approval gate and
            // the queue all sit below this line.
            if (!failures.isEmpty()) {
                throw failures.toException();
            }

            // Phase 2: Build compound command (single undo unit)
            String label = (description != null && !description.isBlank())
                    ? description
                    : "Apply view layout (" + positionCount + " positions, "
                            + connectionCount + " connections)";

            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);
            // LAST, so a group's rectangle answers to every object the call landed — the order the
            // spacing pass and resize-elements-to-fit already hold. A fit an entry has already
            // satisfied was retired as that entry was prepared, so going last supersedes a group
            // asked for too small without undoing one asked for larger. Into the COMPOUND, not
            // `commands`: that list is one per entry and IS the published operation count.
            for (Command groupResize : passFitCommands.values()) compound.add(groupResize);

            ApplyViewLayoutResultDto dto = new ApplyViewLayoutResultDto(
                    viewId, positionCount, connectionCount,
                    commands.size());

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("positionsUpdated", positionCount);
                proposedChanges.put("connectionsUpdated", connectionCount);
                proposedChanges.put("totalOperations", commands.size());
                // layout compounds use reviewed-or-reject — the deferred handle returns the
                // already-reviewed compound (re-running the layout algorithm could differ from what
                // was reviewed). The staleness guard rejects-stale if a targeted
                // object was removed; finer per-object move-staleness is a follow-up.
                ProposalContext ctx = storeAsProposal(sessionId,
                        "apply-positions",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId), dto, label,
                        null, proposedChanges,
                        "View layout ready for application." + ProposalBuilder.REVIEWED_OR_REJECT);
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

    // ---- Layout quality assessment ----

    @Override
    public AssessLayoutResultDto assessLayout(String viewId) {
        return assessLayout(viewId, false);
    }

    @Override
    public AssessLayoutResultDto assessLayout(String viewId, boolean includeViolatorIds) {
        logger.info("Assess layout: viewId={}, includeViolatorIds={}", viewId, includeViolatorIds);
        IArchimateModel model = requireAndCaptureModel();
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
            throw new ModelAccessException(
                    "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
        }
        return assessLayout(diagramModel, viewId, includeViolatorIds);
    }

    /**
     * Assesses a diagram object directly (no live-model ID lookup). Used by the
     * route-normalized baseline probe to measure a detached throwaway copy.
     */
    AssessLayoutResultDto assessLayout(IArchimateDiagramModel diagramModel,
            String viewId, boolean includeViolatorIds) {
        // 1. Collect all view objects (including nested, with parentId)
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);

        // 2. Detect orphaned connections — connections whose
        // source/target view objects are missing from the view hierarchy.
        // This runs before filtering so orphans are counted even though they
        // can't be included in geometry-based assessment.
        OrphanDetectionResult orphanResult = detectOrphanedConnections(diagramModel, nodes);

        // 3. Collect connections with reconstructed visual paths. Collected BEFORE the degenerate
        // short-circuit because a lone object can carry a self-referencing connection, which that
        // path must count rather than assert away as zero.
        List<AssessmentConnection> connections =
                AssessmentCollector.collectAssessmentConnections(diagramModel, nodes);

        if (nodes.size() <= 1) {
            return degenerateAssessment(viewId, nodes, connections, orphanResult);
        }

        // 4. Run assessment
        LayoutAssessmentResult result =
                layoutQualityAssessor.assess(nodes, connections, includeViolatorIds);

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
                result.noteOverlapCount(), emptyToNull(result.noteOverlapDescriptions()),
                result.noteClipCount(), emptyToNull(result.noteClipDescriptions()),
                result.hasGroups(),
                result.coincidentSegmentCount(),
                result.nonOrthogonalTerminalCount(),
                mapContentBounds(result.contentBounds()),
                result.labelTruncationCount(),
                emptyToNull(result.labelTruncations()),
                result.parentLabelObscuredCount(),
                emptyToNull(result.parentLabelObscuredDescriptions()),
                result.imageSiblingOverlapCount(), emptyToNull(result.imageSiblingOverlapDescriptions()),
                result.overlayIconCollisionCount(), emptyToNull(result.overlayIconCollisionDescriptions()),
                mapViolatorIds(result.violatorIds()),
                result.suggestions(),
                // M2-M6 (appended; backwards-compat)
                result.interiorTerminationCount(),
                emptyToNull(result.interiorTerminationDescriptions()),
                result.zigzagCount(),
                emptyToNull(result.zigzagDescriptions()),
                result.connectionEdgeCoincidenceCount(),
                emptyToNull(result.edgeCoincidenceDescriptions()),
                Math.round(result.hubPortQualityScore() * 100.0) / 100.0,
                mapHubFaceDetails(result.hubPortQualityFaces()),
                result.layoutRating(),
                result.routingRating(),
                // R8 (appended)
                Math.round(result.corridorUtilisationScore() * 100.0) / 100.0,
                mapCorridorUtilisationChannels(result.corridorUtilisationChannels()),
                // parallelConnectionGap (appended)
                result.vAxisParallelGapP10() != null
                        ? Math.round(result.vAxisParallelGapP10() * 100.0) / 100.0 : null,
                result.vAxisParallelGapNarrow25Count(), result.hAxisParallelGapNarrow25Count(),
                mapParallelGapDetail(result.parallelConnectionGapDetail()), Math.round(result.hubNeighbourClearanceMin() * 100.0) / 100.0,
                // Coverage declaration (registry-driven); then connection-through-note/image + redundant-bendpoint + non-orthogonal-interior-segment + container-fill==child (informational)
                result.coverage(), result.connectionThroughNoteCount(), emptyToNull(result.connectionThroughNoteDescriptions()), result.connectionRedundantBendpointCount(), emptyToNull(result.connectionRedundantBendpointDescriptions()), result.nonOrthogonalInteriorSegmentCount(), emptyToNull(result.nonOrthogonalInteriorSegmentDescriptions()), result.containerFillEqualsChildCount(), emptyToNull(result.containerFillEqualsChildDescriptions()), result.connectionGrazesVisualCount(), emptyToNull(result.connectionGrazesVisualDescriptions()), result.labelOnNoteCount(), emptyToNull(result.labelOnNoteDescriptions()), result.labelOnGroupCount(), emptyToNull(result.labelOnGroupDescriptions()), result.edgeCoincidenceGrazedElementCount(), result.offFaceParallelTerminalCount(), emptyToNull(result.offFaceParallelTerminalDescriptions()), result.coincidentFacePortCount(), emptyToNull(result.coincidentFacePortDescriptions()), result.ownIconOverLabelCount(), emptyToNull(result.ownIconOverLabelDescriptions()), result.cousinOverlapCount(), emptyToNull(result.cousinOverlaps()), result.boundaryViolationCount(), result.anchorDriftCount(), emptyToNull(result.anchorDriftDescriptions()), result.lateralJogReversalCount(), emptyToNull(result.lateralJogReversalDescriptions()), result.zeroBendpointNonOrthogonalTerminalCount(), result.routedNonOrthogonalTerminalCount(), emptyToNullHubs(HubDataCollector.unmetHubPreconditions(diagramModel)), LayoutQualityAssessor.contextualPartialDimensions(result.coverage()), result.crossElementPassThroughCount());
    }

    /**
     * The response for a view holding at most one object. The view is not rated — no arrangement
     * exists to judge — but the detectors that ARE computable on a single object do run, so a real
     * defect on that object is reported instead of being suppressed into a zero it never earned.
     * The coverage map declares which detectors ran. {@code nodes} counts view objects: elements,
     * groups and notes alike.
     */
    private AssessLayoutResultDto degenerateAssessment(String viewId, List<AssessmentNode> nodes,
            List<AssessmentConnection> connections, OrphanDetectionResult orphanResult) {
        LayoutQualityAssessor.DegenerateAssessment assessment =
                layoutQualityAssessor.assessDegenerate(nodes, connections);
        return AssessLayoutResultDto.degenerate(viewId, nodes.size(), assessment.connectionCount(),
                orphanResult.count(), orphanResult.descriptions(),
                assessment.offCanvasWarnings(),
                assessment.labelTruncationCount(), assessment.labelTruncations(),
                assessment.noteClipCount(), assessment.noteClipDescriptions(),
                assessment.ownIconOverLabelCount(), assessment.ownIconOverLabelDescriptions(),
                assessment.suggestions(), assessment.coverage(), LayoutQualityAssessor.contextualPartialDimensions(assessment.coverage()));
    }

    /** Maps internal ParallelConnectionGapDetail to DTO format. */
    private AssessLayoutResultDto.ParallelConnectionGapDetailDto mapParallelGapDetail(
            LayoutAssessmentResult.ParallelConnectionGapDetail detail) {
        if (detail == null) return null;
        return new AssessLayoutResultDto.ParallelConnectionGapDetailDto(
                mapParallelGapAxisDetail(detail.vAxis()),
                mapParallelGapAxisDetail(detail.hAxis()));
    }

    private AssessLayoutResultDto.ParallelConnectionGapAxisDetailDto mapParallelGapAxisDetail(
            LayoutAssessmentResult.ParallelConnectionGapAxisDetail a) {
        if (a == null) return null;
        return new AssessLayoutResultDto.ParallelConnectionGapAxisDetailDto(
                a.qualifyingSegmentCount(), a.mean(), a.min(), a.p10(),
                a.narrowGapCount15(), a.narrowGapCount25(), a.narrowGapCount40());
    }

    /**
     * Whether this call's commands are still WAITING to run, so a disclosure computed here can say
     * what its basis is. TWO arms defer, and both need the caveat: an open {@code begin-batch},
     * and {@code bulk-mutate}'s own window, which prepares every operation before dispatching any
     * of them — the pending-parent map is live for exactly that window, which is what detects it.
     */
    private boolean isQueuedCall(String sessionId) {
        return mutationDispatcher.getMode(sessionId) == OperationalMode.BATCH || bulkPendingParents.get() != null;
    }

    /** One warning to a list, or null so the field is omitted rather than published empty. */
    private static List<StructuredWarningDto> warningsOrNull(StructuredWarningDto warning) {
        return warning == null ? null : List.of(warning);
    }

    /** Empty to null, so an absent field reads as "measured, nothing unmet" via NON_NULL. */
    private static List<AssessLayoutResultDto.HubPreconditionDto> emptyToNullHubs(
            List<AssessLayoutResultDto.HubPreconditionDto> rows) {
        return rows.isEmpty() ? null : rows;
    }

    /**
     * Maps internal HubFaceDetail records to DTO format (M5).
     * Returns null if input is null or empty (NON_NULL JSON inclusion suppresses field).
     */
    private List<AssessLayoutResultDto.HubFaceDetailDto> mapHubFaceDetails(
            List<LayoutAssessmentResult.HubFaceDetail> faces) {
        if (faces == null || faces.isEmpty()) return null;
        List<AssessLayoutResultDto.HubFaceDetailDto> result = new ArrayList<>();
        for (LayoutAssessmentResult.HubFaceDetail f : faces) {
            result.add(new AssessLayoutResultDto.HubFaceDetailDto(
                    f.elementId(), f.face(), f.connectionsOnFace(),
                    f.distinctSlots(), Math.round(f.quality() * 100.0) / 100.0));
        }
        return result;
    }

    /** Maps internal CorridorUtilisationDetail records to DTO format (R8). */
    private List<AssessLayoutResultDto.CorridorUtilisationDetailDto> mapCorridorUtilisationChannels(
            List<LayoutAssessmentResult.CorridorUtilisationDetail> channels) {
        if (channels == null || channels.isEmpty()) return null;
        List<AssessLayoutResultDto.CorridorUtilisationDetailDto> result = new ArrayList<>();
        for (LayoutAssessmentResult.CorridorUtilisationDetail c : channels) {
            result.add(new AssessLayoutResultDto.CorridorUtilisationDetailDto(
                    c.axis(), c.sharedCoord(), c.wallLowId(), c.wallHighId(),
                    c.occupantCount(), c.span(), c.available(),
                    Math.round(c.spreadRatio() * 100.0) / 100.0));
        }
        return result;
    }

    /** Maps internal violatorIds (Set) to DTO format (List). Returns null if input is null. */
    private Map<String, List<String>> mapViolatorIds(Map<String, java.util.Set<String>> violatorIds) {
        if (violatorIds == null) return null;
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, java.util.Set<String>> entry : violatorIds.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    /** Maps internal ContentBounds to DTO. Returns null if input is null. */
    private AssessLayoutResultDto.ContentBoundsDto mapContentBounds(ContentBounds bounds) {
        if (bounds == null) return null;
        return new AssessLayoutResultDto.ContentBoundsDto(
                bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    @Override
    public ContentBounds getContentBounds(String viewId) {
        IArchimateModel model = requireAndCaptureModel();

        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
            throw new ModelAccessException(
                    "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
        }

        return computeContentBoundsForView(diagramModel);
    }

    /**
     * Computes content bounds for an already-resolved diagram model.
     * Filters out notes so bounds reflect diagram content only.
     * Includes connection bendpoint extents so that position-based note
     * placement clears connections that extend beyond element bounds.
     * Returns null if the view has no non-note content.
     */
    private ContentBounds computeContentBoundsForView(IArchimateDiagramModel diagramModel) {
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);

        // Filter out notes — content bounds should reflect diagram content only,
        // so existing notes don't push position-based placement further away.
        List<AssessmentNode> contentNodes = nodes.stream()
                .filter(n -> !n.isNote())
                .toList();

        if (contentNodes.isEmpty()) {
            return null;
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (AssessmentNode node : contentNodes) {
            if (node.x() < minX) minX = node.x();
            if (node.y() < minY) minY = node.y();
            double right = node.x() + node.width();
            double bottom = node.y() + node.height();
            if (right > maxX) maxX = right;
            if (bottom > maxY) maxY = bottom;
        }

        // Extend bounds to include connection bendpoints that may extend
        // beyond element boundaries (e.g., routed paths arcing above/below content).
        for (IDiagramModelConnection conn : AssessmentCollector.collectAllConnections(diagramModel)) {
            IConnectable source = conn.getSource();
            IConnectable target = conn.getTarget();
            if (!(source instanceof IDiagramModelObject sourceObj)
                    || !(target instanceof IDiagramModelObject targetObj)) {
                continue;
            }
            int[] srcCenter = ConnectionResponseBuilder.computeAbsoluteCenter(sourceObj);
            int[] tgtCenter = ConnectionResponseBuilder.computeAbsoluteCenter(targetObj);
            // Bendpoints are stored as relative offsets from the source and target centres, and
            // the drawn point interpolates the two reconstructions at Archi's render weight,
            // (i + 1) / (n + 1). Averaging them instead would understate how far a drifted
            // connection reaches near its terminals — where the shear is largest — and these
            // bounds exist so note placement clears the connections that extend past the
            // elements. Kept in floating point: this feeds a bounding box, not a reported point.
            int bendpointCount = conn.getBendpoints().size();
            int bendpointIndex = 0;
            for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
                double weight = (bendpointIndex + 1.0) / (bendpointCount + 1.0);
                bendpointIndex++;
                double absX = (bp.getStartX() + srcCenter[0]) * (1.0 - weight)
                        + (bp.getEndX() + tgtCenter[0]) * weight;
                double absY = (bp.getStartY() + srcCenter[1]) * (1.0 - weight)
                        + (bp.getEndY() + tgtCenter[1]) * weight;
                if (absX < minX) minX = absX;
                if (absY < minY) minY = absY;
                if (absX > maxX) maxX = absX;
                if (absY > maxY) maxY = absY;
            }
        }

        return new ContentBounds(minX, minY, maxX - minX, maxY - minY);
    }

    /** Result of orphaned connection detection. */
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

    // ---- Resize elements to fit ----

    /** Bottom padding for parent elements containing children. */
    private static final int CONTAINMENT_PADDING_BOTTOM = 10;
    /** Left/right padding for parent elements containing children. */
    private static final int CONTAINMENT_PADDING_SIDE = 10;
    /** Why a named ArchiMate Grouping came back unsized. Reported, never inferred from a count. */
    private static final String ZONE_NOT_LABEL_SIZED =
            "An ArchiMate Grouping is a zone, not a label-bearing element: this tool grows one to "
            + "contain its children and never shrinks one or sizes one to its own name. No "
            + "ArchiMate element is nested inside this one on this view, so there was nothing to "
            + "grow it around. Set its bounds with update-view-object, or arrange what is inside "
            + "it with layout-within-group.";

    @Override
    public MutationResult<ResizeElementsResultDto> resizeElementsToFit(
            String sessionId, String viewId, List<String> elementIds) {
        return resizeElementsToFit(sessionId, viewId, elementIds, false);
    }

    @Override
    public MutationResult<ResizeElementsResultDto> resizeElementsToFit(
            String sessionId, String viewId, List<String> elementIds, boolean wrapFit) {
        logger.info("Resize elements to fit: viewId={}, elementIds={}, wrapFit={}", viewId,
                elementIds != null ? elementIds.size() + " specified" : "all", wrapFit);
        IArchimateModel model = requireAndCaptureModel();

        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs.",
                    null);
        }

        // Collect all element view objects (not groups, not notes)
        List<IDiagramModelArchimateObject> targets = new ArrayList<>();
        TopLevelGroupTargets.collectElementViewObjects(diagramModel, targets);

        // Filter to specific IDs if provided. The set outlives the filter: a zone the caller
        // NAMED and this pass then declined to size is disclosed below, where one the walk merely
        // found is left silent.
        java.util.Set<String> namedIds = elementIds == null
                ? java.util.Set.<String>of() : new java.util.HashSet<>(elementIds);
        if (!namedIds.isEmpty()) {
            targets.removeIf(obj -> !namedIds.contains(obj.getId()));
        }

        // Parent-child map for nested containment. Its key set IS the set of parents, so a target
        // it does not key is a leaf -- one population, not two that can disagree.
        Map<String, List<IDiagramModelArchimateObject>> childrenByParentId =
                TopLevelGroupTargets.containmentMap(targets);

        // Two-pass: size leaf elements first, then parents (bottom-up by depth)
        List<IDiagramModelArchimateObject> leafElements = new ArrayList<>();
        List<IDiagramModelArchimateObject> parentElements = new ArrayList<>();
        for (IDiagramModelArchimateObject t : targets) {
            if (childrenByParentId.containsKey(t.getId())) {
                parentElements.add(t);
            } else {
                leafElements.add(t);
            }
        }

        // Sort parents bottom-up by nesting depth so children-parents are sized
        // before grandparents (supports recursive nesting)
        parentElements.sort((a, b) -> Integer.compare(TopLevelGroupTargets.nestingDepth(b), TopLevelGroupTargets.nestingDepth(a)));

        // Track computed sizes (and the parent-group fit) so Pass 2 uses resized dimensions, not stale bounds.
        // Both pass maps start from the geometry this unit of work has already established — what an open
        // batch has queued, or what a bulk pass has recorded — so a second call in one window measures a
        // shared container against the first call's result instead of the pre-batch size and overwriting it.
        // passObjectBounds is kept apart from the fit map because the fit map is REPORTED: mixing element
        // geometry into it would name elements as resized groups. passFitSeed snapshots the floor so the
        // report can exclude groups this pass merely knew about, and is safe as a shallow copy because the
        // walk replaces a group's entry rather than mutating it.
        Map<String, int[]> computedSizes = new LinkedHashMap<>();
        Map<String, int[]> passObjectBounds = sameBatchBounds(sessionId);
        Map<String, int[]> passFitBounds = new LinkedHashMap<>(passObjectBounds);
        Map<String, int[]> passFitSeed = new LinkedHashMap<>(passObjectBounds);
        Map<String, Command> passFitCommands = new LinkedHashMap<>();
        Map<String, Command> passAnchorMoves = new LinkedHashMap<>();

        List<PreparedMutation<ViewObjectDto>> mutations = new ArrayList<>();
        List<ResizeElementsResultDto.ResizedElement> resizedList = new ArrayList<>();
        List<SkippedContainerDto> skippedZones = new ArrayList<>();
        int unchangedCount = 0;

        // Pass 1: Size leaf elements using ElementSizer
        for (IDiagramModelArchimateObject leaf : leafElements) {
            String elName = leaf.getArchimateConcept() != null
                    ? leaf.getArchimateConcept().getName() : "";
            if (TopLevelGroupTargets.isGroupingZone(leaf)) {
                // A Grouping is a zone, not a label-bearing element. Holding no children, there is
                // nothing to grow it around, and the only thing left to size it to would be its own
                // name -- which is how a 400x300 zone came back at the element defaults.
                if (namedIds.contains(leaf.getId())) {
                    skippedZones.add(TopLevelGroupTargets.describe(leaf, ZONE_NOT_LABEL_SIZED));
                }
                continue;
            }
            IBounds bounds = leaf.getBounds();
            int oldW = bounds.getWidth();
            int oldH = bounds.getHeight();
            int[] computed;
            if (wrapFit) {
                // Wrap-fit: keep the existing width, grow height (only) so the label wraps and fits.
                // Grow-only — never shrink a box below its current height.
                int[] wf = ElementSizer.computeWrapFitDimensions(elName, oldW);
                computed = new int[] { oldW, Math.max(oldH, wf[1]) };
            } else {
                computed = ElementSizer.computeAutoSize(elName);
            }

            computedSizes.put(leaf.getId(), computed);

            if (computed[0] != oldW || computed[1] != oldH) {
                PreparedMutation<ViewObjectDto> pm = prepareUpdateViewObjectDirect(
                        sessionId, leaf, null, null, computed[0], computed[1], null, null, null, null, passObjectBounds, passFitBounds, passFitCommands, passAnchorMoves);
                mutations.add(pm);
                resizedList.add(new ResizeElementsResultDto.ResizedElement(
                        leaf.getId(), elName, oldW, oldH, computed[0], computed[1]));
            } else {
                unchangedCount++;
            }
        }

        // Pass 2: Size parent elements based on children bounds + own label (dynamic label height).
        // Non-wrapFit path is UNCHANGED (byte-identical). wrapFit uses a height-only containment pass
        // (else branch) that preserves each parent's WIDTH so the dense grid does not shift horizontally.
        if (!wrapFit) {
        for (IDiagramModelArchimateObject parent : parentElements) {
            String parentName = parent.getArchimateConcept() != null
                    ? parent.getArchimateConcept().getName() : "";

            // Get auto-size for the parent's own label
            int[] labelSize = ElementSizer.computeAutoSize(parentName);
            int labelWidth = labelSize[0];

            // Compute children bounding box using computed sizes (not stale bounds)
            List<IDiagramModelArchimateObject> children = childrenByParentId.get(parent.getId());
            int childMaxRight = 0;
            int childMaxBottom = 0;
            int minChildY = Integer.MAX_VALUE;
            if (children != null) {
                for (IDiagramModelArchimateObject child : children) {
                    IBounds cb = child.getBounds();
                    int[] childSize = computedSizes.getOrDefault(child.getId(),
                            new int[] { cb.getWidth(), cb.getHeight() });
                    int childRight = cb.getX() + childSize[0];
                    int childBottom = cb.getY() + childSize[1];
                    childMaxRight = Math.max(childMaxRight, childRight);
                    childMaxBottom = Math.max(childMaxBottom, childBottom);
                    minChildY = Math.min(minChildY, cb.getY());
                }
            }

            // Step 1: Compute width (needed for label height word-wrap calculation). A zone's
            // width follows its CHILDREN only and is grow-only, so this tool still closes a
            // boundary violation around a Grouping and never pulls one in around its contents.
            boolean zone = TopLevelGroupTargets.isGroupingZone(parent);
            int newWidth = (zone ? childMaxRight : Math.max(labelWidth, childMaxRight))
                    + 2 * CONTAINMENT_PADDING_SIDE;
            newWidth = Math.max(newWidth,
                    zone ? parent.getBounds().getWidth() : ElementSizer.DEFAULT_WIDTH);

            // Step 2: Compute dynamic label height based on actual text wrapping. A zone reserves
            // NO band: its name renders in a corner tab, so a band would both move the children
            // their author placed and grow the zone on account of its own name.
            int dynamicLabelTop = zone ? 0 : ElementSizer.computeLabelHeight(parentName, newWidth);

            // Step 3: Shift children down if topmost child overlaps label area
            if (!zone && children != null && !children.isEmpty() && minChildY < dynamicLabelTop) {
                int shiftDelta = dynamicLabelTop - minChildY;
                for (IDiagramModelArchimateObject child : children) {
                    IBounds cb = child.getBounds();
                    int newY = cb.getY() + shiftDelta;
                    PreparedMutation<ViewObjectDto> pm = prepareUpdateViewObjectDirect(
                            sessionId, child, null, newY, null, null, null, null, null, null, passObjectBounds, passFitBounds, passFitCommands, passAnchorMoves);
                    mutations.add(pm);
                }
                childMaxBottom += shiftDelta;
            }

            // Step 4: Compute height with dynamic label top
            int newHeight = dynamicLabelTop + childMaxBottom + CONTAINMENT_PADDING_BOTTOM;
            newHeight = Math.max(newHeight, ElementSizer.DEFAULT_HEIGHT);

            // Never shrink parent height
            IBounds bounds = parent.getBounds();
            int oldW = bounds.getWidth();
            int oldH = bounds.getHeight();
            newHeight = Math.max(newHeight, oldH);

            computedSizes.put(parent.getId(), new int[] { newWidth, newHeight });

            if (newWidth != oldW || newHeight != oldH) {
                PreparedMutation<ViewObjectDto> pm = prepareUpdateViewObjectDirect(
                        sessionId, parent, null, null, newWidth, newHeight, null, null, null, null, passObjectBounds, passFitBounds, passFitCommands, passAnchorMoves);
                mutations.add(pm);
                resizedList.add(new ResizeElementsResultDto.ResizedElement(
                        parent.getId(), parentName, oldW, oldH, newWidth, newHeight));
            } else {
                unchangedCount++;
            }
        }
        } else {
            // wrapFit Pass-2: grow each ancestor of a (potentially taller) leaf HEIGHT-ONLY so it
            // contains its children. Preserves parent WIDTH (zero horizontal shift); grow-only;
            // bottom-up by nesting depth so inner parents are sized before their ancestors (the
            // ancestor reads the inner parent's grown height via computedSizes).
            // NOTE: only IDiagramModelArchimateObject ancestors are grown — the walk stops at the
            // first non-archimate container (e.g. an IDiagramModelGroup). This mirrors the
            // non-wrapFit parent detection above (line ~3228, which also keys on
            // IDiagramModelArchimateObject). Leaves nested directly inside a visual GROUP are not
            // group-contained by this pass; the live View-G target nests functions inside
            // ApplicationComponents (archimate objects), so it is fully covered. Group-parent
            // containment is a documented follow-up if a future caller needs it.
            java.util.Set<IDiagramModelArchimateObject> wrapFitAncestors = new java.util.LinkedHashSet<>();
            for (IDiagramModelArchimateObject leaf : leafElements) {
                EObject p = leaf.eContainer();
                while (p instanceof IDiagramModelArchimateObject pa) {
                    wrapFitAncestors.add(pa);
                    p = pa.eContainer();
                }
            }
            List<IDiagramModelArchimateObject> wrapFitAncestorList = new ArrayList<>(wrapFitAncestors);
            wrapFitAncestorList.sort((a, b) -> Integer.compare(TopLevelGroupTargets.nestingDepth(b), TopLevelGroupTargets.nestingDepth(a)));
            for (IDiagramModelArchimateObject parent : wrapFitAncestorList) {
                String parentName = parent.getArchimateConcept() != null
                        ? parent.getArchimateConcept().getName() : "";
                IBounds bounds = parent.getBounds();
                int oldW = bounds.getWidth();
                int oldH = bounds.getHeight();

                // Lowest child bottom, using grown sizes (computedSizes) where available.
                int childMaxBottom = 0;
                for (IDiagramModelObject c : parent.getChildren()) {
                    if (c instanceof IDiagramModelArchimateObject childObj) {
                        IBounds cb = childObj.getBounds();
                        int[] cs = computedSizes.getOrDefault(childObj.getId(),
                                new int[] { cb.getWidth(), cb.getHeight() });
                        childMaxBottom = Math.max(childMaxBottom, cb.getY() + cs[1]);
                    }
                }

                // Grow-only, height-only, width preserved.
                int newHeight = Math.max(oldH, childMaxBottom + CONTAINMENT_PADDING_BOTTOM);
                computedSizes.put(parent.getId(), new int[] { oldW, newHeight });

                if (newHeight != oldH) {
                    PreparedMutation<ViewObjectDto> pm = prepareUpdateViewObjectDirect(
                            sessionId, parent, null, null, null, newHeight, null, null, null, null, passObjectBounds, passFitBounds, passFitCommands, passAnchorMoves);
                    mutations.add(pm);
                    resizedList.add(new ResizeElementsResultDto.ResizedElement(
                            parent.getId(), parentName, oldW, oldH, oldW, newHeight));
                } else {
                    unchangedCount++;
                }
            }
        }

        // Every target has landed, so the rectangles the anchored children were moved to are final.
        // Fit their groups around them here rather than inside each prepare: the map accumulates
        // across the whole pass, and a grow-only fit run against an intermediate landing could never
        // be taken back. It must also happen BEFORE the compound is assembled below — a resize added
        // after that point is reported to the caller and never executed.
        ParentFitCascade.fitDisplaced(passAnchorMoves, DEFAULT_GROUP_PADDING,
                passFitBounds, passFitCommands, bulkPendingParents.get(), null);

        ResizeElementsResultDto dto = new ResizeElementsResultDto(
                viewId, resizedList.size(), unchangedCount, resizedList,
                ParentFitCascade.project(passFitBounds, passFitSeed, diagramModel),
                AnchorResolver.projectMoves(passAnchorMoves, diagramModel), skippedZones);

        // Nothing to commit. No longer merely a forward guard: a call whose only target is a
        // Grouping zone this pass declined to size lands here, and must still carry its disclosure.
        if (mutations.isEmpty() && passFitCommands.isEmpty()) {
            return new MutationResult<>(dto, (Integer) null);
        }

        // Build compound command (single undo unit); the label counts resized ELEMENTS, not commands.
        String label = "Resize " + mutations.size() + " elements to fit labels";
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(label);
        for (PreparedMutation<ViewObjectDto> pm : mutations) {
            compound.add(pm.command());
        }
        for (Command groupResize : passFitCommands.values()) compound.add(groupResize);
        for (Command anchoredMove : passAnchorMoves.values()) compound.add(anchoredMove); // last: every target has landed

        // Check approval mode
        if (mutationDispatcher.isApprovalRequired(sessionId)) {
            Map<String, Object> proposedChanges = new LinkedHashMap<>();
            proposedChanges.put("resizedCount", resizedList.size());
            proposedChanges.put("unchangedCount", unchangedCount);
            proposedChanges.put("wrapFit", wrapFit);
            ProposalContext ctx = storeAsProposal(sessionId,
                    "resize-elements-to-fit",
                    () -> new PreparedMutation<>(compound, dto, viewId),
                    compoundTargetIds(compound, viewId), dto, label,
                    null, proposedChanges,
                    "Element resize ready for application." + ProposalBuilder.REVIEWED_OR_REJECT);
            return new MutationResult<>(dto, null, ctx);
        }

        Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
        if (batchSeq == null) {
            versionCounter.incrementAndGet();
        }
        return new MutationResult<>(dto, batchSeq);
    }

    // ---- Hub element detection ----

    @Override
    public DetectHubElementsResultDto detectHubElements(String viewId) {
        logger.info("Detect hub elements: viewId={}", viewId);
        IArchimateModel model = requireAndCaptureModel();

        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
            throw new ModelAccessException(
                    "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
        }

        // Collect all archimate view objects and count connections per viewObjectId
        Map<String, Integer> connectionCounts = new HashMap<>();
        Map<String, IDiagramModelArchimateObject> viewObjectMap = new HashMap<>();
        Map<String, Integer> maxLabelWidths = new HashMap<>();
        int totalConnections = HubDataCollector.collect(diagramModel, connectionCounts, viewObjectMap, maxLabelWidths);

        // Count total elements on view (including zero-connection ones)
        int totalElements = viewObjectMap.size();

        // Filter to elements with at least 1 connection, sort descending (stable tie-break by viewObjectId)
        List<HubElementEntryDto> entries = connectionCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    IDiagramModelArchimateObject obj = viewObjectMap.get(entry.getKey());
                    IArchimateElement element = obj.getArchimateElement();
                    IBounds bounds = obj.getBounds();
                    return new HubElementEntryDto(
                            obj.getId(),
                            element.getId(),
                            element.getName(),
                            element.eClass().getName(),
                            entry.getValue(),
                            bounds.getWidth(),
                            bounds.getHeight(),
                            maxLabelWidths.getOrDefault(entry.getKey(), 0));
                })
                .collect(Collectors.toList());

        // Summary statistics
        double avgConnectionCount = entries.isEmpty() ? 0.0
                : entries.stream().mapToInt(HubElementEntryDto::connectionCount).average().orElse(0.0);
        // Round to 1 decimal place
        avgConnectionCount = Math.round(avgConnectionCount * 10.0) / 10.0;

        // Generate sizing suggestions for hub elements (>6 connections)
        List<String> suggestions = HubSizingSuggestionBuilder.buildSuggestions(entries);

        return new DetectHubElementsResultDto(
                viewId, totalElements, totalConnections, avgConnectionCount,
                entries.isEmpty() ? List.of() : entries,
                suggestions.isEmpty() ? null : suggestions);
    }

    // ---- Auto-route connections ----

    /** Maximum nudge iterations for autoNudge mode. */
    private static final int MAX_NUDGE_ITERATIONS = 2;

    @Override
    public MutationResult<AutoRouteResultDto> autoRouteConnections(
            String sessionId, String viewId,
            List<String> connectionIds, String strategy, boolean force,
            boolean autoNudge, int snapThreshold, int perimeterMargin, String mode) {
        return autoRouteConnections(sessionId, viewId, connectionIds, strategy, force,
                autoNudge, snapThreshold, perimeterMargin, mode,
                RoutingPipeline.DEFAULT_ENABLE_CHANNEL_NUDGING, null);
    }

    @Override
    public MutationResult<AutoRouteResultDto> autoRouteConnections(
            String sessionId, String viewId,
            List<String> connectionIds, String strategy, boolean force,
            boolean autoNudge, int snapThreshold, int perimeterMargin, String mode,
            boolean enableChannelNudging, String labelPolicy) {
        LabelPolicy resolvedLabelPolicy = requireLabelPolicy(labelPolicy);
        logger.info("Auto-route connections: viewId={}, strategy={}, mode={}, connectionIds={}, force={}, autoNudge={}, snapThreshold={}, perimeterMargin={}, enableChannelNudging={}",
                viewId, strategy, mode, connectionIds != null ? connectionIds.size() : "all", force, autoNudge, snapThreshold, perimeterMargin, enableChannelNudging);
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

            // 2b. Validate mode (terminals-only routing)
            String effectiveMode = (mode == null || mode.isBlank()) ? "full" : mode;
            if (!"full".equals(effectiveMode) && !"terminals-only".equals(effectiveMode)) {
                throw new ModelAccessException(
                        "Invalid mode: '" + effectiveMode + "'. Valid: full, terminals-only",
                        ErrorCode.INVALID_PARAMETER);
            }
            boolean terminalsOnly = "terminals-only".equals(effectiveMode);
            if (terminalsOnly && "clear".equals(effectiveStrategy)) {
                throw new ModelAccessException(
                        "strategy 'clear' cannot be combined with mode 'terminals-only'"
                                + " — they are mutually exclusive",
                        ErrorCode.INVALID_PARAMETER);
            }
            if (terminalsOnly && autoNudge) {
                throw new ModelAccessException(
                        "autoNudge cannot be combined with mode 'terminals-only'"
                                + " — terminals-only never moves elements",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 3. Collect connections
            List<IDiagramModelConnection> allConnections =
                    AssessmentCollector.collectAllConnections(diagramModel);

            // 4. Filter by connectionIds if provided
            List<IDiagramModelConnection> targetConnections;
            List<String> warnings = new ArrayList<>();
            // Coded warnings accumulate alongside the free-text list; both ship in parallel.
            List<StructuredWarningDto> structuredWarnings = new ArrayList<>();
            if (connectionIds != null && !connectionIds.isEmpty()) {
                Map<String, IDiagramModelConnection> connMap = new LinkedHashMap<>();
                for (IDiagramModelConnection conn : allConnections) {
                    connMap.put(conn.getId(), conn);
                }
                targetConnections = new ArrayList<>();
                List<String> missingConnectionIds = new ArrayList<>();
                // Deduped on the loop's INPUT, not on the list it builds. A repeated id names one
                // connection, and the routing pipeline downstream is index-parallel rather than
                // id-keyed — so a second entry routes the same connection again and records its
                // corridor occupancy twice, perturbing the A* cost of everything routed after it.
                // Doing it here also collapses a repeated *bogus* id, which would otherwise be
                // reported once per time it was typed; deduping targetConnections afterwards would
                // fix the routing half and leave the warning half over-counting.
                for (String connId : new LinkedHashSet<>(connectionIds)) {
                    IDiagramModelConnection conn = connMap.get(connId);
                    if (conn == null) {
                        missingConnectionIds.add(connId);
                        continue;
                    }
                    targetConnections.add(conn);
                }
                AutoRouteWarnings.emitConnectionsNotFound(missingConnectionIds, mutationDispatcher.armFor(sessionId), warnings, structuredWarnings);
                if (targetConnections.isEmpty() && !missingConnectionIds.isEmpty()) {
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

            // 5b. terminals-only mode: dispatch to a focused branch that only
            // adjusts the first/last bendpoint of each connection. No A* router, no
            // obstacle building, no autoNudge, no label optimizer, no router-type
            // switch (terminals-only never adds new bendpoints to a manhattan-routed
            // view; if the view is in manhattan mode, the existing bendpoints are
            // ignored anyway and the operation is a no-op).
            if (terminalsOnly) {
                LabelVisibilityReadback.requireRoutingPass(resolvedLabelPolicy, "terminals-only mode",
                        "Omit mode=\"terminals-only\" to run the full router, which evaluates label positions");
                return runTerminalsOnly(sessionId, viewId, diagramModel,
                        targetConnections, effectiveStrategy, force, mutationDispatcher.armFor(sessionId), warnings, structuredWarnings);
            }

            // 6. Build commands
            List<Command> commands = new ArrayList<>();
            int routedCount = 0;
            int labelsOptimized = 0;
            int crossingsBefore = 0; int crossingsAfter = 0;
            int straightLineCrossings = 0;
            List<String> hiddenLabelIds = List.of();
            // Set when a connection carries bendpoints: the replaced geometry was itself routed.
            boolean inputWasRouted = false;
            List<FailedConnection> failedConnections = List.of();
            List<MoveRecommendation> moveRecommendations = List.of();
            List<NudgedElementDto> nudgedElements = new ArrayList<>();
            List<ResizedGroupDto> resizedGroups = new ArrayList<>();
            // Captures the autoNudge-blocked state after the
            // sibling-overlap gate flips effectiveAutoNudge inside the orthogonal else
            // branch below; read at the response-shaping site to split recommendations
            // into advisory (autoNudge=false) vs blocked (autoNudge=true + sibling-overlap).
            // The "clear" strategy branch below never writes this flag — autoNudge does not
            // apply to clear (no recommendation engine runs, moveRecommendations stays empty),
            // so leaving the default false correctly yields empty blockedRecommendations +
            // null nudgeBlockedReason at the response-shaping site for clear-strategy calls.
            boolean autoNudgeBlocked = false;

            if ("clear".equals(effectiveStrategy)) {
                // Clear: empty bendpoints for each connection
                for (IDiagramModelConnection conn : targetConnections) {
                    if (conn instanceof IDiagramModelArchimateConnection archConn) {
                        PreparedMutation<ViewConnectionDto> prepared =
                                prepareUpdateViewConnection(null, List.of(), null, null, null, null, archConn);
                        commands.add(prepared.command());
                        routedCount++;
                    }
                }
                AutoRouteWarnings.emitClearedRoutesThroughNote(
                        targetConnections, diagramModel, mutationDispatcher.armFor(sessionId), warnings, structuredWarnings);
            } else {
                // Orthogonal: compute routing for each connection
                List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);

                // Crossing count before routing:
                // Collect assessment connections for targeted connections only
                Set<String> targetIds = new HashSet<>();
                for (IDiagramModelConnection tc : targetConnections) {
                    targetIds.add(tc.getId());
                }
                List<AssessmentConnection> assessmentConns =
                        AssessmentCollector.collectAssessmentConnections(diagramModel, nodes);
                List<List<double[]>> beforePaths = new ArrayList<>();
                Map<String, List<double[]>> beforePathMap = new LinkedHashMap<>();
                for (AssessmentConnection ac : assessmentConns) {
                    if (targetIds.contains(ac.id())) {
                        beforePaths.add(ac.pathPoints());
                        if (ac.pathPoints().size() > 2) { inputWasRouted = true; }
                        beforePathMap.put(ac.id(), ac.pathPoints());
                    }
                }
                crossingsBefore = LayoutQualityAssessor.countPathCrossings(beforePaths);

                // Pre-route validation: detect stacked elements sharing identical positions
                AutoRouteWarnings.emitStackedElements(nodes, model, warnings);

                // Route all connections via shared helper
                // Catch degenerate geometry (zero-gap, touching) that can
                // crash the routing pipeline. Return partial result instead of INTERNAL_ERROR.
                OrthogonalRoutingResult routeResult;
                try {
                    routeResult = buildOrthogonalRoutingCommands(
                            diagramModel, targetConnections, nodes, force, snapThreshold, perimeterMargin,
                            VisibilityGraphRouter.DEFAULT_OCCUPANCY_WEIGHT, enableChannelNudging, true,
                            resolvedLabelPolicy);
                } catch (RuntimeException e) {
                    logger.warn("Routing pipeline failed for view {} due to degenerate geometry: {}",
                            viewId, e.getMessage());
                    logger.debug("Routing pipeline failure stack trace", e);
                    AutoRouteWarnings.emitRoutingFailedDegenerateGeometry(warnings, structuredWarnings);
                    AutoRouteResultDto dto = new AutoRouteResultDto(
                            viewId, 0, effectiveStrategy, false, warnings, structuredWarnings);
                    return new MutationResult<>(dto, null);
                }
                commands.addAll(routeResult.commands);
                routedCount = routeResult.routedCount;
                labelsOptimized = routeResult.labelsOptimized;
                failedConnections = routeResult.failedConnections;
                moveRecommendations = routeResult.moveRecommendations;
                straightLineCrossings = routeResult.straightLineCrossings;
                hiddenLabelIds = LabelVisibilityReadback.excludeQueuedHides(
                        routeResult.hiddenLabelIds, mutationDispatcher.queuedLabelVisibility(sessionId));
                AutoRouteWarnings.emitEgressLiftLayoutBound(routeResult.egressRolledBack, mutationDispatcher.armFor(sessionId), warnings, structuredWarnings);

                // 6b. Auto-nudge: apply move recommendations and re-route
                // Ignored when force=true (force already applies all routes) or clear strategy
                boolean effectiveAutoNudge = autoNudge && !force;

                // Skip autoNudge when sibling elements overlap — degenerate geometry
                // can crash the re-routing pipeline. Fall back to standard failure reporting.
                // Containment overlaps (parent-child nesting) are excluded — they are intentional.
                if (effectiveAutoNudge && OverlapResolver.hasOverlappingElements(nodes)) {
                    effectiveAutoNudge = false;
                    logger.warn("Auto-nudge skipped: overlapping sibling elements detected on view {}. "
                            + "Resolve overlaps first (e.g., layout-flat-view or layout-within-group), "
                            + "then re-route.", viewId);
                    String autoNudgeSkipMessage =
                            "autoNudge skipped because sibling elements have overlapping bounding boxes. "
                                    + "Use layout-flat-view or layout-within-group to separate elements first.";
                    warnings.add(autoNudgeSkipMessage);
                    structuredWarnings.add(new StructuredWarningDto(
                            StructuredWarningCodes.AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP,
                            autoNudgeSkipMessage,
                            "layout-within-group",
                            OverlapResolver.findOverlappingElementIds(nodes)));
                }

                // Hoist shared maps OUT of the autoNudge gate so the post-routing
                // overflow pass (below) can share the same virtualGroupBounds +
                // groupResizeCommands consolidation. When the gate below does NOT
                // enter (e.g., routing succeeded without failed connections so
                // autoNudge has nothing to do), the fit map holds only its seed.
                //
                // Seeded from the open batch: without it, two prepares of one batch each measure a
                // shared group against its PRE-BATCH size and emit competing absolute resizes for
                // it, so whichever executes last silently discards the other. The seed snapshot is
                // what keeps the report honest afterwards — a group the batch merely established is
                // in the map without this pass having grown it, and naming it as resized would be a
                // false statement about the model dressed as a measurement.
                Map<String, IDiagramModelObject> allViewObjects = new LinkedHashMap<>();
                collectAllViewObjectMap(diagramModel, allViewObjects);
                Map<String, int[]> cumulativeDeltas = new LinkedHashMap<>();
                Map<String, int[]> virtualGroupBounds = sameBatchBounds(sessionId);
                Map<String, int[]> virtualGroupSeed = new LinkedHashMap<>(virtualGroupBounds);
                Map<String, Command> groupResizeCommands = new LinkedHashMap<>();

                if (effectiveAutoNudge && !failedConnections.isEmpty()
                        && !moveRecommendations.isEmpty()) {
                    // Build connection lookup for re-routing specific failed connections
                    Map<String, IDiagramModelConnection> connLookup = new LinkedHashMap<>();
                    for (IDiagramModelConnection conn : targetConnections) {
                        connLookup.put(conn.getId(), conn);
                    }

                    // Track cumulative deltas per element for consolidation (M-1)
                    Map<String, String> elementNames = new LinkedHashMap<>();

                    for (int iteration = 0; iteration < MAX_NUDGE_ITERATIONS; iteration++) {
                        if (failedConnections.isEmpty() || moveRecommendations.isEmpty()) {
                            break;
                        }

                        logger.info("Auto-nudge iteration {}: {} recommendations, {} failed connections",
                                iteration + 1, moveRecommendations.size(), failedConnections.size());

                        // Apply each move recommendation
                        for (MoveRecommendation rec : moveRecommendations) {
                            IDiagramModelObject dmo = allViewObjects.get(rec.elementId());
                            if (dmo == null) {
                                logger.warn("Auto-nudge: view object {} not found, skipping",
                                        rec.elementId());
                                continue;
                            }

                            IBounds bounds = dmo.getBounds();
                            // Account for prior iteration deltas — EMF model is never
                            // mutated, so bounds always returns the original position.
                            int[] priorDelta = cumulativeDeltas.getOrDefault(
                                    rec.elementId(), new int[]{0, 0});
                            int newX = bounds.getX() + priorDelta[0] + rec.dx();
                            int newY = bounds.getY() + priorDelta[1] + rec.dy();

                            // Keep a nested child inside its parent and clear of the parent's
                            // title band: a padding-only floor let a nudge lift a child into the
                            // band, trading a cosmetic routing defect for a critical layout one.
                            EObject container = dmo.eContainer();
                            int[] clamped = NestedLayoutOperations.clampInsideParent(container,
                                    newX, newY, bounds.getY() + priorDelta[1], DEFAULT_GROUP_PADDING);
                            newX = clamped[0];
                            newY = clamped[1];

                            // Compute actual displacement after clamping
                            int actualDx = newX - bounds.getX() - priorDelta[0];
                            int actualDy = newY - bounds.getY() - priorDelta[1];

                            // Apply move command
                            commands.add(new UpdateViewObjectCommand(dmo, newX, newY,
                                    bounds.getWidth(), bounds.getHeight()));

                            // Resize parent group using virtual bounds
                            if (container instanceof IDiagramModelGroup parentGroup) {
                                ParentFitCascade.resize(parentGroup, dmo, newX, newY,
                                        bounds.getWidth(), bounds.getHeight(), DEFAULT_GROUP_PADDING,
                                        virtualGroupBounds, groupResizeCommands, bulkPendingParents.get(), null);
                            }

                            String elemName = dmo.getName() != null ? dmo.getName() : rec.elementId();

                            // Accumulate deltas per element for consolidated response
                            cumulativeDeltas.merge(rec.elementId(),
                                    new int[]{actualDx, actualDy},
                                    (old, neu) -> new int[]{old[0] + neu[0], old[1] + neu[1]});
                            elementNames.putIfAbsent(rec.elementId(), elemName);
                        }

                        // Re-collect nodes from unmodified EMF model, then apply
                        // cumulative nudge deltas virtually. Direct setBounds() is
                        // forbidden here — it triggers EMF notifications that cascade
                        // to SWT widgets, causing Invalid thread access (SWTException)
                        // since MCP handlers run on a Reactor thread pool, not the
                        // SWT Display thread.
                        // Also apply virtual group bounds for accurate re-routing.
                        nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);
                        nodes = applyNudgeDeltas(nodes, cumulativeDeltas, virtualGroupBounds);

                        // Identify failed connections to re-route
                        List<IDiagramModelConnection> failedConns = new ArrayList<>();
                        for (FailedConnection fc : failedConnections) {
                            IDiagramModelConnection conn = connLookup.get(fc.connectionId());
                            if (conn != null) {
                                failedConns.add(conn);
                            }
                        }

                        if (failedConns.isEmpty()) {
                            break;
                        }

                        // Re-route only the previously failed connections
                        // Wrap in try-catch — degenerate geometry after nudge
                        // (zero-gap, overlapping) can crash the routing pipeline.
                        // Fall back to pre-nudge results instead of INTERNAL_ERROR.
                        try {
                            OrthogonalRoutingResult reRouteResult = buildOrthogonalRoutingCommands(
                                    diagramModel, failedConns, nodes, false, snapThreshold, perimeterMargin,
                                    VisibilityGraphRouter.DEFAULT_OCCUPANCY_WEIGHT, enableChannelNudging, true,
                            resolvedLabelPolicy);
                            commands.addAll(reRouteResult.commands);
                            routedCount += reRouteResult.routedCount;
                            labelsOptimized += reRouteResult.labelsOptimized;
                            hiddenLabelIds = LabelVisibilityReadback.merge(
                                    hiddenLabelIds, reRouteResult.hiddenLabelIds);
                            failedConnections = reRouteResult.failedConnections;
                            moveRecommendations = reRouteResult.moveRecommendations;
                            // Merge re-routed paths for crossing delta
                            routeResult.routedPaths.putAll(reRouteResult.routedPaths);

                            logger.info("Auto-nudge iteration {} result: {} re-routed, {} still failed",
                                    iteration + 1, reRouteResult.routedCount,
                                    reRouteResult.failedConnections.size());
                        } catch (RuntimeException e) {
                            logger.warn("Auto-nudge re-routing failed (iteration {}): {} — "
                                    + "falling back to pre-nudge results",
                                    iteration + 1, e.getMessage());
                            logger.debug("Auto-nudge re-routing failure stack trace", e);
                            warnings.add("autoNudge re-routing failed due to degenerate geometry. "
                                    + "Pre-nudge routing results preserved.");
                            break;
                        }
                    }

                    // Resize commands accumulated in
                    // groupResizeCommands are committed AFTER the Successor-E post-routing
                    // overflow pass below, so both autoNudge nudge-driven resizes AND
                    // post-pass overflow-detection resizes share one consolidation map
                    // (one command per group — the latest resize wins via Map.put).

                    // Re-align terminals and re-encode relative BPs using final
                    // post-nudge centers. Connections routed in iteration N use element
                    // centers from that iteration. If an element is nudged in iteration N+1:
                    // (1) the center-aligned BPs target stale centers, and
                    // (2) Archi's relative→absolute formula averages both endpoints, so
                    //     the stale encoding shifts all absolute BPs by half the nudge delta.
                    // Fix: re-run center alignment against final centers, then re-encode.
                    if (!cumulativeDeltas.isEmpty()) {
                        int correctedCount = 0;
                        for (Map.Entry<String, List<AbsoluteBendpointDto>> entry
                                : routeResult.routedPaths.entrySet()) {
                            IDiagramModelConnection conn = connLookup.get(entry.getKey());
                            if (conn == null
                                    || !(conn instanceof IDiagramModelArchimateConnection archConn)) {
                                continue;
                            }
                            IConnectable srcConn = conn.getSource();
                            IConnectable tgtConn = conn.getTarget();
                            if (!(srcConn instanceof IDiagramModelArchimateObject srcObj)
                                    || !(tgtConn instanceof IDiagramModelArchimateObject tgtObj)) {
                                continue;
                            }
                            // Check if source or target was nudged
                            int[] srcDelta = cumulativeDeltas.get(srcObj.getId());
                            int[] tgtDelta = cumulativeDeltas.get(tgtObj.getId());
                            if (srcDelta == null && tgtDelta == null) {
                                continue; // neither endpoint nudged — BPs are correct
                            }
                            // Compute final post-nudge element rects and centers
                            int[] srcCenter = ConnectionResponseBuilder.computeAbsoluteCenter(srcObj);
                            int[] tgtCenter = ConnectionResponseBuilder.computeAbsoluteCenter(tgtObj);
                            int sdx = srcDelta != null ? srcDelta[0] : 0;
                            int sdy = srcDelta != null ? srcDelta[1] : 0;
                            int tdx = tgtDelta != null ? tgtDelta[0] : 0;
                            int tdy = tgtDelta != null ? tgtDelta[1] : 0;
                            int finalSrcCX = srcCenter[0] + sdx;
                            int finalSrcCY = srcCenter[1] + sdy;
                            int finalTgtCX = tgtCenter[0] + tdx;
                            int finalTgtCY = tgtCenter[1] + tdy;

                            // Build final post-nudge element rects for center alignment
                            IBounds srcBounds = srcObj.getBounds();
                            IBounds tgtBounds = tgtObj.getBounds();
                            // Compute absolute position (sum parent chain)
                            int srcAbsX = srcBounds.getX();
                            int srcAbsY = srcBounds.getY();
                            Object srcParent = srcObj.eContainer();
                            while (srcParent instanceof IDiagramModelObject p) {
                                srcAbsX += p.getBounds().getX();
                                srcAbsY += p.getBounds().getY();
                                srcParent = p.eContainer();
                            }
                            int tgtAbsX = tgtBounds.getX();
                            int tgtAbsY = tgtBounds.getY();
                            Object tgtParent = tgtObj.eContainer();
                            while (tgtParent instanceof IDiagramModelObject p) {
                                tgtAbsX += p.getBounds().getX();
                                tgtAbsY += p.getBounds().getY();
                                tgtParent = p.eContainer();
                            }

                            RoutingRect finalSrcRect = new RoutingRect(
                                    srcAbsX + sdx, srcAbsY + sdy,
                                    srcBounds.getWidth(), srcBounds.getHeight(), null);
                            RoutingRect finalTgtRect = new RoutingRect(
                                    tgtAbsX + tdx, tgtAbsY + tdy,
                                    tgtBounds.getWidth(), tgtBounds.getHeight(), null);

                            // Fix center-terminated terminals before re-aligning
                            // against final nudged positions. Without this,
                            // alignTerminalsWithCenter can insert a BP at exact center
                            // coordinates when the existing terminal's perpendicular
                            // axis already matches center.
                            List<AbsoluteBendpointDto> path = entry.getValue();
                            RoutingPipeline.ConnectionEndpoints finalEndpoints =
                                    new RoutingPipeline.ConnectionEndpoints(
                                            entry.getKey(), finalSrcRect, finalTgtRect,
                                            List.of(), "", 0);
                            RoutingPipeline.fixCenterTerminatedPath(path, finalEndpoints);
                            RoutingPipeline.fixInteriorTerminalBPs(path, finalEndpoints);
                            RoutingPipeline.alignTerminalsWithCenter(path, finalEndpoints);
                            RoutingPipeline.removeDuplicatePoints(path);
                            RoutingPipeline.removeCollinearPoints(path);

                            // Re-encode relative BPs using final centers
                            List<BendpointDto> correctedBps =
                                    ConnectionResponseBuilder.convertAbsoluteToRelative(
                                            path, finalSrcCX, finalSrcCY, finalTgtCX, finalTgtCY);
                            PreparedMutation<ViewConnectionDto> prepared =
                                    prepareUpdateViewConnection(
                                            null, correctedBps, null, null, null, null, archConn);
                            commands.add(prepared.command());
                            correctedCount++;
                        }
                        if (correctedCount > 0) {
                            logger.info("Auto-nudge: corrected {} connections with nudged endpoints "
                                    + "(re-aligned terminals + re-encoded relative BPs)",
                                    correctedCount);
                        }
                    }

                    // Report only the elements that ended somewhere new; an element whose
                    // iterations cancelled out is named as net-zero instead of counted as nudged.
                    NudgeConsolidation.Result consolidated =
                            NudgeConsolidation.consolidate(cumulativeDeltas, elementNames);
                    nudgedElements.addAll(consolidated.moved());
                    AutoRouteWarnings.emitNetZeroNudge(consolidated.netZero(),
                            mutationDispatcher.armFor(sessionId), warnings, structuredWarnings);

                }

                // AFTER autoNudge, never before it: the nudge merges freshly-routed paths, rewrites
                // bendpoints whose endpoints moved, and re-collects nodes. See the emitter's docs.
                AutoRouteWarnings.emitConnectionThroughNote(routeResult.routedPaths,
                        assessmentConns, nodes, mutationDispatcher.armFor(sessionId), warnings, structuredWarnings);

                // Post-routing overflow detection pass — closes the residual gap in
                // the group-bounds fix when the autoNudge block above does NOT enter
                // (e.g., routing succeeded without failed connections so autoNudge has
                // nothing to nudge, OR the OverlapResolver early-skip bypassed
                // autoNudge due to pre-existing sibling overlap). In those
                // cases, any pre-existing element/group overflow from prior workflow
                // steps (e.g., apply-element-spacing-recommendations inflated geometry
                // in a non-autoNudge code path) would persist because
                // the parent-fit cascade never fires.
                //
                // Gated by effectiveAutoNudge so the pass ONLY fires when the caller
                // opted in to autoNudge — preserving the caller's intent when they
                // explicitly disabled it (force=true OR autoNudge=false).
                //
                // The pass reuses the hoisted virtualGroupBounds + groupResizeCommands
                // maps so any resizes the autoNudge block already emitted are not
                // duplicated — Map.put consolidates by groupId (latest resize wins,
                // and since cumulativeDeltas are applied before measuring, the latest
                // resize accounts for nudge-shifted positions too).
                //
                // Scope: this pass is autoNudge-path-only. The composed-tool spacing
                // path (apply-element-spacing-recommendations etc.) routes via
                // computeAutoRoutePass(force=true) and BYPASSES autoNudge entirely;
                // that gap is sibling scope.
                if (effectiveAutoNudge) {
                    ParentFitCascade.fitAll(allViewObjects, cumulativeDeltas, DEFAULT_GROUP_PADDING,
                            virtualGroupBounds, groupResizeCommands, bulkPendingParents.get(), null);
                }

                // Commit consolidated group resize commands
                // (one per group — autoNudge + post-pass share the same map; latest
                // resize wins via Map.put). When effectiveAutoNudge=false the maps are
                // empty and this is a no-op.
                commands.addAll(groupResizeCommands.values());

                // Report every group the cascade grew (both the autoNudge nudge-driven resizes and
                // the post-pass overflow-detection ones — one map, one source), excluding the ones
                // the map was seeded with: those are groups an open batch had already sized, which
                // this pass measured against but did not touch.
                resizedGroups.addAll(
                        ParentFitCascade.project(virtualGroupBounds, virtualGroupSeed, diagramModel));

                // 6c. Compute crossings after routing
                // Build after-paths from routed paths (merged with autoNudge re-routes)
                // + original paths for connections that were not routed.
                // Note: source/target centers are from pre-nudge state. After autoNudge,
                // nudged element centers shift but the relative BP correction pass
                // ensures absolute BPs match the final post-nudge positions.
                List<List<double[]>> afterPaths = new ArrayList<>();
                for (Map.Entry<String, List<double[]>> entry : beforePathMap.entrySet()) {
                    String connId = entry.getKey();
                    List<AbsoluteBendpointDto> routedBps = routeResult.routedPaths.get(connId);
                    if (routedBps != null) {
                        // Build full path: source center → bendpoints → target center
                        List<double[]> origPath = entry.getValue();
                        double[] srcCenter = origPath.get(0);
                        double[] tgtCenter = origPath.get(origPath.size() - 1);
                        List<double[]> afterPath = new ArrayList<>();
                        afterPath.add(srcCenter);
                        for (AbsoluteBendpointDto bp : routedBps) {
                            afterPath.add(new double[]{bp.x(), bp.y()});
                        }
                        afterPath.add(tgtCenter);
                        afterPaths.add(afterPath);
                    } else {
                        // Connection not routed — use original path
                        afterPaths.add(entry.getValue());
                    }
                }
                crossingsAfter = LayoutQualityAssessor.countPathCrossings(afterPaths);

                // Capture the autoNudge-blocked state at the boundary of the
                // orthogonal else branch (where effectiveAutoNudge is in scope). The
                // response-shaping site below splits recommendations into advisory vs
                // blocked using this flag. The sibling-overlap gate at lines 3961-3980 is
                // byte-unchanged.
                autoNudgeBlocked = autoNudge && !force && !effectiveAutoNudge;
            }

            // 6d. Crossing warnings — straight-line density inflation, plus a re-route that
            // raised crossings above the geometry it replaced (non-monotonic; reported, not blocked).
            RoutingPipeline.appendCrossingWarnings(crossingsBefore, crossingsAfter,
                    straightLineCrossings, inputWasRouted, mutationDispatcher.armFor(sessionId), warnings, structuredWarnings);

            // 7. Switch view to bendpoint mode if needed
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
                    + ", " + routedCount + " connections"
                    + (nudgedElements.isEmpty() ? "" : ", " + nudgedElements.size() + " nudged")
                    + (resizedGroups.isEmpty() ? "" : ", " + resizedGroups.size() + " groups resized")
                    + ")";
            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            // Build view-object-ID → name lookup for failed connections and recommendations
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
                // Include all view objects for recommendation element name resolution
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

            // Build response DTOs based on force mode
            AutoRouteResultDto dto;
            if (force) {
                // Force mode: all routes applied, report violations instead of failures
                List<RoutingViolationDto> violationDtos = new ArrayList<>();
                for (FailedConnection fc : failedConnections) {
                    String srcName = viewObjectNameMap.getOrDefault(fc.sourceId(), fc.sourceId());
                    String tgtName = viewObjectNameMap.getOrDefault(fc.targetId(), fc.targetId());
                    String crossedId = fc.crossedElementId();
                    String crossedName = AutoRouteWarnings.resolveCrossedElementName(crossedId, viewObjectNameMap, model);
                    violationDtos.add(new RoutingViolationDto(
                            fc.connectionId(), srcName, tgtName,
                            fc.constraintViolated(), severityFor(fc.constraintViolated()),
                            crossedId, crossedName));
                }
                dto = new AutoRouteResultDto(
                        viewId, routedCount, 0, effectiveStrategy,
                        routerTypeSwitched, labelsOptimized,
                        crossingsBefore, crossingsAfter, straightLineCrossings,
                        warnings, List.of(), List.of(),
                        violationDtos, nudgedElements, resizedGroups,
                        structuredWarnings);
            } else {
                // Default mode: failed connections excluded, report failures + recommendations
                List<FailedConnectionDto> failedDtos = new ArrayList<>();
                for (FailedConnection fc : failedConnections) {
                    String srcName = viewObjectNameMap.getOrDefault(fc.sourceId(), fc.sourceId());
                    String tgtName = viewObjectNameMap.getOrDefault(fc.targetId(), fc.targetId());
                    String crossedId = fc.crossedElementId();
                    String crossedName = AutoRouteWarnings.resolveCrossedElementName(crossedId, viewObjectNameMap, model);
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
                // split advisory recommendations from blocked-by-sibling-overlap
                // recommendations using the autoNudgeBlocked flag captured at the end of the
                // orthogonal else branch above. When autoNudge was requested but the Story
                // 13-9 gate flipped effectiveAutoNudge to false (sibling-overlap), surface
                // the recommendations under blockedRecommendations + nudgeBlockedReason so
                // the agent does not see the mixed "recommendations populated, nudgedElements
                // empty" signal. The structuredWarnings entry
                // (AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP) still emits in parallel from the gate
                // site.
                List<MoveRecommendationDto> advisoryDtos = autoNudgeBlocked
                        ? List.of()
                        : recommendationDtos;
                List<MoveRecommendationDto> blockedDtos = autoNudgeBlocked
                        ? recommendationDtos
                        : List.of();
                String nudgeBlockedReason = autoNudgeBlocked
                        ? AutoRouteBlockedReasons.SIBLING_OVERLAP
                        : null;
                dto = new AutoRouteResultDto(
                        viewId, routedCount, failedDtos.size(), effectiveStrategy,
                        routerTypeSwitched, labelsOptimized,
                        crossingsBefore, crossingsAfter, straightLineCrossings,
                        0, 0, 0, 0, 0,
                        warnings, failedDtos,
                        advisoryDtos, List.of(), nudgedElements, resizedGroups,
                        structuredWarnings, blockedDtos, nudgeBlockedReason,
                        LabelVisibilityReadback.projected(hiddenLabelIds));
            }

            // 10. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("strategy", effectiveStrategy);
                proposedChanges.put("force", force);
                proposedChanges.put("connectionsRouted", routedCount);
                if (routerTypeSwitched) {
                    proposedChanges.put("routerTypeSwitched",
                            "manhattan -> manual (bendpoint mode)");
                }
                if (!nudgedElements.isEmpty()) {
                    proposedChanges.put("nudgedElements", nudgedElements.size());
                }
                if (!resizedGroups.isEmpty()) {
                    proposedChanges.put("resizedGroups", resizedGroups.size());
                }
                // surface autoNudge-blocked reason in the human-readable
                // approval preview so the owner sees the blocked-nudge state without
                // having to inspect the stored DTO. Mirrors the routerTypeSwitched /
                // nudgedElements / resizedGroups size-key pattern above.
                if (dto.nudgeBlockedReason() != null) {
                    proposedChanges.put("nudgeBlockedReason", dto.nudgeBlockedReason());
                }
                ProposalContext ctx = storeAsProposal(sessionId,
                        "auto-route-connections",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId), dto, label,
                        null, proposedChanges,
                        "Connection routing computed and ready for application."
                        + LabelVisibilityReadback.describeFrozenHides(hiddenLabelIds)
                        + ProposalBuilder.REVIEWED_OR_REJECT);
                return new MutationResult<>(dto, null, ctx);
            }

            // 11. Dispatch or queue
            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return new MutationResult<>(
                    dto.withHiddenLabels(LabelVisibilityReadback.report(
                            model, hiddenLabelIds, batchSeq == null)),
                    batchSeq);

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

    /**
     * Applies cumulative nudge deltas to a list of assessment nodes without mutating
     * the EMF model. Returns a new list where nudged elements have adjusted absolute
     * positions. Non-nudged elements are returned unchanged.
     *
     * <p>This avoids calling {@code dmo.setBounds()} which triggers EMF notifications
     * that cascade to SWT widgets (Invalid thread access when called from non-SWT threads).
     */
    static List<AssessmentNode> applyNudgeDeltas(
            List<AssessmentNode> nodes, Map<String, int[]> cumulativeDeltas) {
        if (cumulativeDeltas.isEmpty()) {
            return nodes;
        }
        List<AssessmentNode> adjusted = new ArrayList<>(nodes.size());
        for (AssessmentNode node : nodes) {
            int[] delta = cumulativeDeltas.get(node.id());
            if (delta != null) {
                adjusted.add(new AssessmentNode(
                        node.id(), node.x() + delta[0], node.y() + delta[1],
                        node.width(), node.height(),
                        node.parentId(), node.isGroup(), node.isNote(),
                        node.name(), node.labelTextWidth(),
                        // The note-overflow and image-overlap detection fields are informational and unused on
                        // this routing-only nudge path, so leave them at the 0.0 sentinel; isJunction, fillColor,
                        // isContainer, textAlignment and textPosition ARE carried (a nudge moves a box, it does not restyle it).
                        node.imagePath(), node.imagePosition(), 0.0, 0.0, 0.0, node.isJunction(), node.fillColor(), node.isContainer(), node.textAlignment(), node.textPosition()));
            } else {
                adjusted.add(node);
            }
        }
        return adjusted;
    }

    /**
     * Applies cumulative nudge deltas AND virtual group bounds to assessment nodes.
     * Extends the two-parameter overload with group-level size adjustments for accurate re-routing.
     *
     * <p>For each group in {@code virtualGroupBounds}, the group node's size is updated to the
     * virtual dimensions. Position is unchanged (right/bottom expansion only, per clamping).
     *
     * @param nodes              original nodes from {@code AssessmentCollector}
     * @param cumulativeDeltas   element nudge deltas (same as two-parameter overload)
     * @param virtualGroupBounds group ID → [relX, relY, newWidth, newHeight] from resize tracking
     */
    static List<AssessmentNode> applyNudgeDeltas(
            List<AssessmentNode> nodes, Map<String, int[]> cumulativeDeltas,
            Map<String, int[]> virtualGroupBounds) {
        if (cumulativeDeltas.isEmpty()
                && (virtualGroupBounds == null || virtualGroupBounds.isEmpty())) {
            return nodes;
        }
        if (virtualGroupBounds == null || virtualGroupBounds.isEmpty()) {
            return applyNudgeDeltas(nodes, cumulativeDeltas);
        }

        // Build a lookup: group ID → original EMF bounds [x, y, w, h] from the node list
        // and compute position deltas per group
        Map<String, double[]> groupPositionDeltas = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            int[] vBounds = virtualGroupBounds.get(node.id());
            if (vBounds != null && node.isContainer()) {
                // Clamping prevents negative child positions, so groups only expand
                // right/bottom. Position unchanged (delta=0), only size updated.
                groupPositionDeltas.put(node.id(),
                        new double[]{0, 0, vBounds[2], vBounds[3]});
            }
        }

        List<AssessmentNode> adjusted = new ArrayList<>(nodes.size());
        for (AssessmentNode node : nodes) {
            double adjX = node.x();
            double adjY = node.y();
            double adjW = node.width();
            double adjH = node.height();

            // Apply element nudge delta
            int[] delta = cumulativeDeltas.get(node.id());
            if (delta != null) {
                adjX += delta[0];
                adjY += delta[1];
            }

            // Apply group size adjustment
            double[] groupAdj = groupPositionDeltas.get(node.id());
            if (groupAdj != null) {
                // groupAdj = [dx, dy, newWidth, newHeight]
                adjX += groupAdj[0];
                adjY += groupAdj[1];
                adjW = groupAdj[2];
                adjH = groupAdj[3];
            }

            if (delta != null || groupAdj != null) {
                adjusted.add(new AssessmentNode(
                        node.id(), adjX, adjY, adjW, adjH,
                        node.parentId(), node.isGroup(), node.isNote(),
                        node.name(), node.labelTextWidth(),
                        // The note-overflow and image-overlap detection fields are informational and unused on
                        // this routing-only nudge path, so leave them at the 0.0 sentinel; isJunction, fillColor,
                        // isContainer, textAlignment and textPosition ARE carried (a nudge moves a box, it does not restyle it).
                        node.imagePath(), node.imagePosition(), 0.0, 0.0, 0.0, node.isJunction(), node.fillColor(), node.isContainer(), node.textAlignment(), node.textPosition()));
            } else {
                adjusted.add(node);
            }
        }
        return adjusted;
    }

    /**
     * Overflow predicate for the parent-fit cascade — see
     * {@link ParentFitCascade#childExceedsParentBounds(int, int, int, int, int, int, int)}, which
     * owns the definition. Kept here as a forward because the post-routing overflow pass and its
     * pins reach it through the accessor.
     */
    static boolean childExceedsParentBounds(
            int childNewX, int childNewY, int childW, int childH,
            int parentW, int parentH, int padding) {
        return ParentFitCascade.childExceedsParentBounds(
                childNewX, childNewY, childW, childH, parentW, parentH, padding);
    }

    /** Maps a constraint violation type to a severity string. */
    private static String severityFor(String constraintViolated) {
        if ("element_crossing".equals(constraintViolated)) {
            return "warning";
        }
        return "info";
    }

    // ---- Auto-layout-and-route ----

    /** Maximum iterations for targetRating quality loop. */
    private static final int MAX_TARGET_RATING_ITERATIONS = 5;
    /** Spacing increment per iteration for targetRating quality loop. */
    private static final int TARGET_RATING_SPACING_INCREMENT = 20;
    /** Maximum occupancy weight to prevent extreme detour pathologies. 4x default. */
    private static final double MAX_OCCUPANCY_WEIGHT = 3.0;

    @Override
    public MutationResult<AutoLayoutAndRouteResultDto> autoLayoutAndRoute(
            String sessionId, String viewId, String mode,
            String direction, int spacing, String targetRating, String labelPolicy) {
        LabelPolicy resolvedLabelPolicy = requireLabelPolicy(labelPolicy);
        String effectiveMode = (mode != null) ? mode.toLowerCase() : "auto";
        logger.info("Auto-layout-and-route: viewId={}, mode={}, direction={}, spacing={}, targetRating={}",
                viewId, effectiveMode, direction, spacing, targetRating);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1. Validate view
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 2. Route based on mode
            if ("grouped".equals(effectiveMode)) {
                return executeGroupedMode(sessionId, viewId, direction, resolvedLabelPolicy,
                        spacing, targetRating, model, diagramModel);
            }

            // Auto mode (ELK) — original behavior
            // 3. Collect all nodes (including nested children) for ELK
            List<LayoutNode> nodes = collectLayoutNodesRecursive(diagramModel);
            List<LayoutEdge> edges = collectLayoutEdgesRecursive(diagramModel, nodes);

            if (nodes.isEmpty()) {
                throw new ModelAccessException(
                        "View has no elements to layout",
                        ErrorCode.INVALID_PARAMETER);
            }

            // No targetRating — single-pass mode (backward compatible)
            if (targetRating == null) {
                // ELK's own edge routes; no label optimizer runs, so no evidence about label positions.
                LabelVisibilityReadback.requireRoutingPass(resolvedLabelPolicy,
                        "flat mode without targetRating",
                        "Set targetRating (e.g. \"good\"), or use mode=\"grouped\", or run "
                        + "auto-route-connections with labelPolicy after this call");
                return executeSingleLayoutPass(sessionId, viewId, direction,
                        spacing, model, diagramModel, nodes, edges);
            }

            // quality target iteration loop
            return executeQualityTargetLoop(sessionId, viewId, direction,
                    spacing, targetRating, model, diagramModel, nodes, edges, resolvedLabelPolicy);

        } catch (NoModelLoadedException | ModelAccessException
                | MutationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("auto-layout-and-route INTERNAL_ERROR: mode={}, viewId={}, direction={}, "
                    + "spacing={}, targetRating={}", effectiveMode, viewId, direction, spacing,
                    targetRating, e);
            throw new ModelAccessException(
                    "Error computing/applying layout for view '"
                    + (viewId != null ? viewId : "<null>") + "': "
                    + e.getClass().getSimpleName() + " — " + e.getMessage(),
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
                sessionId, viewId, direction, spacing, model, diagramModel, nodes, edges);

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
                    "auto-layout-and-route",
                    () -> new PreparedMutation<>(pass.compound, dto, viewId),
                    compoundTargetIds(pass.compound, viewId), dto, pass.compound.getLabel(),
                    null, proposedChanges,
                    "ELK layout computed and ready for application." + ProposalBuilder.REVIEWED_OR_REJECT);
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
     * Quality target iteration loop.
     * Tunes whichever lever the worst metric calls for and keeps the best result. Five distinct
     * exits: the target is met, every metric passes, the limiting factor is one no iteration can
     * move, the factor plateaus, or the attempt budget is spent. Each records a distinct
     * {@link QualityTargetTermination} reason, because the last of them was merely cut short while
     * the others concluded — and they can report the same limiting factor.
     *
     * <p>Each iteration temporarily applies the layout (via dispatchImmediate) so that
     * assess-layout can read EMF positions, then undoes it. The final best result is
     * dispatched through the standard approval/batch/dispatch path.</p>
     */
    private MutationResult<AutoLayoutAndRouteResultDto> executeQualityTargetLoop(
            String sessionId, String viewId, String direction, int baseSpacing,
            String targetRating, IArchimateModel model,
            IArchimateDiagramModel diagramModel,
            List<LayoutNode> nodes, List<LayoutEdge> edges, LabelPolicy labelPolicy) {

        String effectiveDirection = direction != null ? direction.toUpperCase() : "DOWN";
        int effectiveBaseSpacing = baseSpacing > 0 ? baseSpacing : 50;

        // The state the caller handed us, measured once before anything is applied. The loop below
        // ranks the states it PRODUCES against each other; without this it never compares any of
        // them against the view it was given, so a run that made the view worse looks identical on
        // the wire to a run that failed to improve one. Reporting only — it must never become a
        // candidate in the best-tracking comparison below.
        AssessLayoutResultDto preCallAssessment = assessLayout(viewId);

        // Track best result across iterations
        NonNotifyingCompoundCommand bestCompound = null;
        String bestRating = "not-applicable";
        int bestScore = Integer.MAX_VALUE; // tier-weighted score (lower is better) — see QualityTargetTermination.tierWeightedScore()
        int bestPositionCount = 0;
        int bestRoutedCount = 0;
        int bestLabelsOptimized = 0;
        List<String> bestHiddenLabelIds = List.of();
        boolean bestRouterTypeSwitched = false;
        int bestSpacing = effectiveBaseSpacing;
        AssessLayoutResultDto bestAssessment = null;

        String previousRating = null;
        String previousLimitingFactor = null;
        int previousFactorCount = 0;
        int iterationsPerformed = 0;
        String currentLimitingFactor = null;
        int spacingStep = 0;
        String terminationReason = null;

        for (int i = 0; i < MAX_TARGET_RATING_ITERATIONS; i++) {
            int currentSpacing = effectiveBaseSpacing
                    + (spacingStep * TARGET_RATING_SPACING_INCREMENT);

            String remediationType = QualityTargetTermination.remediationTypeFor(
                    "auto", i, currentLimitingFactor);

            // Early exit for non-remediable factors (best already tracked from prior iteration)
            if (QualityTargetTermination.isEarlyExit(remediationType)) {
                logger.info("Quality target iteration {}: limitingFactor={}, remediation={}",
                        i + 1, currentLimitingFactor, remediationType);
                terminationReason = QualityTargetTermination.limitingFactorNotRemediable(
                        currentLimitingFactor);
                break;
            }

            iterationsPerformed = i + 1;

            // Re-collect nodes/edges for each iteration (EMF state changes after undo)
            List<LayoutNode> iterNodes = (i == 0) ? nodes
                    : collectLayoutNodesRecursive(diagramModel);
            List<LayoutEdge> iterEdges = (i == 0) ? edges
                    : collectLayoutEdgesRecursive(diagramModel, iterNodes);

            ElkLayoutPassResult pass = null;
            OptimizeGroupOrderPassResult optimizeResult = null;
            AutoRoutePassResult routeResult = null;
            int undoCount = 0;

            if ("reroute-only".equals(remediationType)) {
                // Routing-only remediation: skip ELK layout, re-route with boosted
                // corridor diversity to force exploration of alternative paths
                double boostedWeight = Math.min(
                        VisibilityGraphRouter.DEFAULT_OCCUPANCY_WEIGHT * (1.0 + i * 0.5),
                        MAX_OCCUPANCY_WEIGHT);
                logger.info("Quality target iteration {}: limitingFactor={}, remediation=reroute-only(occupancy={})",
                        iterationsPerformed, currentLimitingFactor,
                        String.format("%.2f", boostedWeight));
                routeResult = computeAutoRoutePass(viewId, diagramModel, model, boostedWeight, labelPolicy);
                if (routeResult != null) {
                    mutationDispatcher.dispatchImmediate(routeResult.compound);
                    undoCount++;
                }
            } else {
                // Full pipeline / ELK spacing increase: ELK layout + optimize (if groups) + route
                logger.info("Quality target iteration {}: limitingFactor={}, remediation={}",
                        iterationsPerformed, currentLimitingFactor, remediationType);
                pass = computeElkLayoutPass(
                        sessionId, viewId, direction, currentSpacing, model,
                        diagramModel, iterNodes, iterEdges);
                mutationDispatcher.dispatchImmediate(pass.compound);
                undoCount++;

                // optimize-group-order pass (grouped views only)
                optimizeResult = computeOptimizeGroupOrderPass(diagramModel, model, direction);
                if (optimizeResult != null) {
                    mutationDispatcher.dispatchImmediate(optimizeResult.compound);
                    undoCount++;

                    // Re-route connections after element reordering
                    routeResult = computeAutoRoutePass(viewId, diagramModel, model, labelPolicy);
                    if (routeResult != null) {
                        mutationDispatcher.dispatchImmediate(routeResult.compound);
                        undoCount++;
                    }
                }

                // Spacing used — advance for next layout iteration
                spacingStep++;
            }

            // Assess layout quality
            AssessLayoutResultDto assessment = assessLayout(viewId);
            String rating = assessment.overallRating();
            int score = QualityTargetTermination.tierWeightedScore(assessment);

            // Update limiting factor for next iteration
            currentLimitingFactor = QualityTargetTermination.findLimitingFactor(assessment);

            logger.info("Quality target iteration {}: spacing={}, rating={}, avgSpacing={}, overlaps={}, crossings={}{}",
                    iterationsPerformed, currentSpacing, rating,
                    assessment.averageSpacing(),
                    assessment.overlapCount(), assessment.edgeCrossingCount(),
                    optimizeResult != null ? " [+optimize-group-order]" : "");

            // Track best result — merge all compounds into one for atomic undo (tier-weighted + veto)
            if (!QualityTargetTermination.hasTier1Regression(assessment, bestAssessment)
                    && (LayoutQualityAssessor.ratingOrdinal(rating) > LayoutQualityAssessor.ratingOrdinal(bestRating)
                        || (LayoutQualityAssessor.ratingOrdinal(rating) == LayoutQualityAssessor.ratingOrdinal(bestRating)
                            && score < bestScore))) {
                NonNotifyingCompoundCommand mergedCompound =
                        new NonNotifyingCompoundCommand(
                                "ELK layout iter " + iterationsPerformed
                                        + " (" + remediationType + ")");
                if (pass != null) {
                    NestedLayoutOperations.appendAll(mergedCompound, pass.compound);
                }
                if (optimizeResult != null) {
                    NestedLayoutOperations.appendAll(mergedCompound, optimizeResult.compound);
                }
                if (routeResult != null) {
                    NestedLayoutOperations.appendAll(mergedCompound, routeResult.compound);
                }
                bestCompound = mergedCompound;
                bestRating = rating;
                bestScore = score;
                bestPositionCount = (pass != null ? pass.positionCount : 0)
                        + (optimizeResult != null ? optimizeResult.positionCount : 0);
                bestRoutedCount = (pass != null ? pass.routedCount : 0)
                        + (routeResult != null ? routeResult.routedCount : 0);
                bestLabelsOptimized = (routeResult != null ? routeResult.labelsOptimized : 0);
                bestHiddenLabelIds = LabelVisibilityReadback.excludeQueuedHides(
                        routeResult != null ? routeResult.hiddenLabelIds : List.of(),
                        mutationDispatcher.queuedLabelVisibility(sessionId));
                bestRouterTypeSwitched = (pass != null && pass.routerTypeSwitched);
                bestSpacing = currentSpacing;
                bestAssessment = assessment;
            }

            // Undo ALL dispatched commands — finalization re-applies best via proper channels
            if (undoCount > 0) {
                mutationDispatcher.undo(undoCount);
            }

            // Target met — break to finalization
            if (LayoutQualityAssessor.meetsTarget(rating, targetRating)) {
                logger.info("Quality target '{}' met with rating '{}' on iteration {}",
                        targetRating, rating, iterationsPerformed);
                terminationReason =
                        QualityTargetTermination.goalReachedAtIteration(iterationsPerformed);
                break;
            }

            // Null limiting factor means all metrics pass — target should be met
            if (currentLimitingFactor == null) {
                logger.info("Quality target: no limiting factor — all metrics pass on iteration {}",
                        iterationsPerformed);
                terminationReason =
                        QualityTargetTermination.allMetricsPassAtIteration(iterationsPerformed);
                break;
            }

            // Factor-aware plateau detection
            int currentFactorCount = QualityTargetTermination.getMetricCount(currentLimitingFactor, assessment);

            if (i > 0 && isFactorAwarePlateauReached(
                    currentLimitingFactor, previousLimitingFactor,
                    currentFactorCount, previousFactorCount,
                    rating, previousRating)) {
                logger.info("Quality target plateau detected at iteration {} — "
                    + "factor={}, count={}, stopping early", iterationsPerformed,
                    currentLimitingFactor, currentFactorCount);
                terminationReason =
                        QualityTargetTermination.plateauAtIteration(iterationsPerformed);
                break;
            }

            previousRating = rating;
            previousLimitingFactor = currentLimitingFactor;
            previousFactorCount = currentFactorCount;
        }

        // Falling out of the loop rather than breaking means the budget ran out.
        if (terminationReason == null) {
            terminationReason = QualityTargetTermination.budgetExhaustedAfter(iterationsPerformed);
        }

        // Finalize: nothing is currently applied — dispatch best via approval/batch
        if (bestCompound == null) {
            throw new ModelAccessException(
                    "Quality target iteration produced no results",
                    ErrorCode.INTERNAL_ERROR);
        }

        // Label optimization fallback
        int labelFallbackTrials = 0;
        boolean targetMetBeforeFallback =
                LayoutQualityAssessor.meetsTarget(bestRating, targetRating);
        LabelFallbackResult fallback = executeLabelFallback(
                bestCompound, bestRating, bestScore, bestLabelsOptimized,
                bestAssessment, targetRating, viewId, diagramModel, model);
        if (fallback != null) {
            labelFallbackTrials = fallback.trials;
            bestRating = fallback.rating;
            bestScore = fallback.score;
            bestAssessment = fallback.assessment;
            bestLabelsOptimized = fallback.labelsOptimized;
        }
        // The fallback overwrites bestRating, so it can falsify the reason captured at the break.
        terminationReason = QualityTargetTermination.reconcileAfterLoop(terminationReason,
                targetMetBeforeFallback,
                LayoutQualityAssessor.meetsTarget(bestRating, targetRating));

        logger.info("Quality target loop complete: best='{}' after {} iterations "
                + "(target='{}', termination={})",
                bestRating, iterationsPerformed, targetRating, terminationReason);

        AutoLayoutAndRouteResultDto dto = buildQualityTargetDto(
                "auto", viewId, effectiveDirection, bestSpacing,
                bestPositionCount, bestRoutedCount, bestRouterTypeSwitched,
                bestCompound.size(), 0, bestLabelsOptimized, labelFallbackTrials,
                targetRating, bestRating,
                iterationsPerformed, bestAssessment, null, bestHiddenLabelIds,
                terminationReason, preCallAssessment, List.of());

        // Approval gate (edge case #4: applies to final iteration only)
        if (mutationDispatcher.isApprovalRequired(sessionId)) {
            Map<String, Object> proposedChanges = new LinkedHashMap<>();
            proposedChanges.put("direction", effectiveDirection);
            proposedChanges.put("spacing", bestSpacing);
            proposedChanges.put("elementsRepositioned", bestPositionCount);
            proposedChanges.put("connectionsRouted", bestRoutedCount);
            proposedChanges.put("targetRating", targetRating);
            proposedChanges.put("achievedRating", bestRating);
            QualityTargetTermination.putProposedRatingDisclosure(proposedChanges, dto.ratingBefore(), preCallAssessment, bestAssessment);
            proposedChanges.put("iterationsPerformed", iterationsPerformed);
            proposedChanges.put("terminationReason", terminationReason);
            proposedChanges.put("limitingFactor", dto.limitingFactor());
            final var approvedCompound = bestCompound; // effectively-final capture for the rebuild handle
            ProposalContext ctx = storeAsProposal(sessionId,
                    "auto-layout-and-route",
                    () -> new PreparedMutation<>(approvedCompound, dto, viewId),
                    compoundTargetIds(approvedCompound, viewId), dto,
                    bestCompound.getLabel(), null, proposedChanges,
                    "ELK layout with quality target '" + targetRating
                    + "' computed — achieved '" + bestRating
                    + "' after " + iterationsPerformed + " iteration(s)."
                    + LabelVisibilityReadback.describeFrozenHides(bestHiddenLabelIds)
                    + ProposalBuilder.REVIEWED_OR_REJECT);
            return new MutationResult<>(QualityTargetTermination.rescopeAsProposed(dto, preCallAssessment, bestAssessment), null, ctx);
        }

        // Dispatch or queue (batch support)
        Integer batchSeq = dispatchOrQueue(sessionId, bestCompound,
                bestCompound.getLabel());
        if (batchSeq == null) {
            versionCounter.incrementAndGet();
        }
        return new MutationResult<>(QualityTargetTermination.rescopeIfQueued(
                dto.withHiddenLabels(LabelVisibilityReadback.report(
                        model, bestHiddenLabelIds, batchSeq == null)),
                batchSeq, preCallAssessment, bestAssessment), batchSeq);
    }

    // ---- Grouped mode ----

    /**
     * Result from a single grouped layout pass.
     * Contains layout-within-group + arrange-groups commands only.
     * Optimize-group-order and auto-route are handled separately by the caller
     * because they require the layout commands to be dispatched first (EMF state).
     */
    private static class GroupedLayoutPassResult {
        final NonNotifyingCompoundCommand compound;
        final int elementsRepositioned;
        final int groupsArranged;
        /** Each nested container this pass re-fitted, with the rectangle it landed at. */
        final List<MovedViewObjectDto> nestedContainersFitted;
        /** Each leaf the descent re-sized, RAW: a later pass can still supersede its rectangle. */
        final Map<String, Command> resizedLeaves;
        final boolean depthCapHit;

        GroupedLayoutPassResult(NonNotifyingCompoundCommand compound, int elementsRepositioned,
                int groupsArranged, List<MovedViewObjectDto> nestedContainersFitted,
                Map<String, Command> resizedLeaves, boolean depthCapHit) {
            this.compound = compound;
            this.elementsRepositioned = elementsRepositioned;
            this.groupsArranged = groupsArranged;
            this.nestedContainersFitted = nestedContainersFitted; this.resizedLeaves = resizedLeaves;
            this.depthCapHit = depthCapHit;
        }
    }

    /**
     * Entry point for grouped mode.
     * Validates that the view has groups, then delegates to single-pass or quality loop.
     */
    private MutationResult<AutoLayoutAndRouteResultDto> executeGroupedMode(
            String sessionId, String viewId, String direction, LabelPolicy labelPolicy, int spacing,
            String targetRating, IArchimateModel model,
            IArchimateDiagramModel diagramModel) {

        // Flat-view guard: check for populated top-level containers of either kind
        List<IDiagramModelObject> topLevelGroups =
                TopLevelGroupTargets.collectPopulated(diagramModel);
        if (topLevelGroups.isEmpty()) {
            // Calling this view flat would be false: it holds populated zones, just not on the
            // canvas. Grouped mode arranges the view's own containers, so it still declines.
            ModelAccessException allNested = TopLevelGroupTargets.containersAreAllNested(
                    viewId, diagramModel,
                    "mode='grouped' arranges the view's own containers.");
            if (allNested != null) throw allNested;
            throw new ModelAccessException(
                    "mode='grouped' requires a view with groups. "
                    + "Use mode='auto' (default) for flat views.",
                    ErrorCode.INVALID_PARAMETER);
        }

        String effectiveDirection = direction != null ? direction.toUpperCase() : "DOWN";
        int effectiveSpacing = spacing > 0 ? spacing : 50;

        if (targetRating == null) {
            // Single-pass grouped mode
            return executeGroupedSinglePass(sessionId, viewId, effectiveDirection,
                    effectiveSpacing, model, diagramModel, topLevelGroups, labelPolicy);
        }

        // Quality target iteration loop for grouped mode
        return executeGroupedQualityTargetLoop(sessionId, viewId, effectiveDirection,
                effectiveSpacing, targetRating, model, diagramModel, topLevelGroups, labelPolicy);
    }

    /** Maximum retries for arrange-groups gap enforcement within a single layout pass. */
    private static final int MAX_ARRANGE_GAP_RETRIES = 5;
    /** Inter-group spacing increment per gap-enforcement retry. */
    private static final int ARRANGE_GAP_RETRY_INCREMENT = 20;

    /**
     * Computes a single grouped layout pass: layout-within-group for each group,
     * then arrange-groups with virtual bounds and gap enforcement.
     * Returns layout + arrange commands merged into a single compound.
     * Optimize-group-order and auto-route are handled by the caller.
     */
    private GroupedLayoutPassResult computeGroupedLayoutPass(
            String viewId, String direction, int intraGroupSpacing, int interGroupSpacing,
            IArchimateModel model, IArchimateDiagramModel diagramModel,
            List<IDiagramModelObject> topLevelGroups) {

        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand("Grouped Layout (mode=grouped)");
        int elementsRepositioned = 0;
        int groupsArranged = 0;

        // Step 1: Layout the whole subtree under each top-level group.
        //
        // Not just its direct children. Treating a child that has children of its own as a leaf
        // sized it from its OWN label text, with no reference to what it contains, and then never
        // descended into it — so its contents kept their old coordinates and escaped the box that
        // had just been shrunk around them. That applies equally to an ArchiMate element acting as
        // a container and to a nested group; the loop below could not tell them apart and still
        // does not need to. The post-order recursion that layout-within-group's recursiveChildren
        // option drives already sizes a container from its contents, honours the depth cap, and
        // reserves each container kind's title band, so it is reused here rather than reimplemented
        // — the shared-collaborator direction its own class comment already anticipated.
        int resolvedPadding = DEFAULT_GROUP_PADDING;

        // Track virtual group bounds (post-resize dimensions) for arrange-groups
        Map<String, int[]> virtualGroupBounds = new HashMap<>();
        Map<String, Command> fittedContainers = new LinkedHashMap<>(), resizedLeaves = new LinkedHashMap<>();
        boolean depthCapHit = false;

        for (IDiagramModelObject group : topLevelGroups) {
            // A container holding nothing but notes has nothing to arrange.
            if (TopLevelGroupTargets.layoutChildrenOf(group).isEmpty()) continue;

            // Arrangement is derived per level from that level's own child count, not once from
            // the top-level group's — see buildGroupedLayoutCommands for why that distinction is
            // not cosmetic.
            NestedLayoutOperations.NestedLayoutResult nested =
                    NestedLayoutOperations.buildGroupedLayoutCommands(group,
                            intraGroupSpacing, resolvedPadding, direction);
            nested.commands().forEach(compound::add);
            elementsRepositioned += nested.elementsRepositioned();
            fittedContainers.putAll(nested.fittedContainers()); resizedLeaves.putAll(nested.resizedLeaves());
            depthCapHit |= nested.depthCapHit();

            // Track virtual bounds for arrange-groups. This is the FITTED rectangle — the size the
            // group ended at after its descendants were sized — so the inter-group arrangement
            // measures the boxes that will actually be drawn. Measuring the pre-descent size here
            // would trade boundary violations for group overlaps.
            virtualGroupBounds.put(group.getId(),
                    new int[]{nested.rootFittedWidth(), nested.rootFittedHeight()});

            // Resize ancestors if nested. Native groups only: the upward walk is the case the
            // codebase deliberately scoped away from ArchiMate-element containers, and every
            // target here is top-level anyway, so the walk finds the view and stops immediately.
            if (group instanceof IDiagramModelGroup nativeGroup) {
                List<Command> ancestorCommands = new ArrayList<>();
                NestedLayoutOperations.resizeAncestorGroups(
                        nativeGroup, ancestorCommands, resolvedPadding);
                ancestorCommands.forEach(compound::add);
            }
        }

        // Remember command count before arrange-groups (for retry: clear arrange commands only)
        int layoutCommandCount = compound.size();

        // Step 2: Arrange groups by topology
        String arrangement = ("RIGHT".equalsIgnoreCase(direction)
                || "LEFT".equalsIgnoreCase(direction)) ? "row" : "column";

        // Re-read top-level containers. Intentionally includes empty ones.
        List<IDiagramModelObject> arrangeTargets = TopLevelGroupTargets.collect(diagramModel);

        if (!arrangeTargets.isEmpty()) {
            // Build topology ordering (computed once, reused across retries)
            Map<String, Map<String, Integer>> weights =
                    TopLevelGroupTargets.interContainerWeights(diagramModel, arrangeTargets);

            List<String> groupIdsList = new ArrayList<>();
            for (IDiagramModelObject g : arrangeTargets) {
                groupIdsList.add(g.getId());
            }

            GroupTopologyOrderer orderer = new GroupTopologyOrderer();
            List<String> orderedIds = orderer.orderLinear(groupIdsList, weights);

            Map<String, IDiagramModelObject> groupById = new LinkedHashMap<>();
            for (IDiagramModelObject g : arrangeTargets) {
                groupById.put(g.getId(), g);
            }
            List<IDiagramModelObject> orderedGroups = new ArrayList<>();
            for (String id : orderedIds) {
                IDiagramModelObject g = groupById.get(id);
                if (g != null) orderedGroups.add(g);
            }

            // Classify qualifying
            // standalone top-level elements + assign each to an inter-group gap.
            // mode='grouped' parity with the user-facing arrangeGroups path.
            List<ArrangeGroupsStandaloneLane.QualifyingStandaloneElement> qualifyingElements =
                    ArrangeGroupsStandaloneLane.classify(
                            diagramModel.getChildren(), orderedGroups);
            Map<Integer, List<ArrangeGroupsStandaloneLane.QualifyingStandaloneElement>>
                    gapAssignments = ArrangeGroupsStandaloneLane.assignToGaps(
                            qualifyingElements, orderedGroups);

            // Arrange-groups with gap enforcement retry loop.
            // Currently defensive: linear row/column arrangement with positive spacing
            // cannot produce overlaps. Retry becomes load-bearing if arrangement
            // changes to 2D grid or topology-aware placement.
            int currentInterGroupSpacing = interGroupSpacing;
            for (int retry = 0; retry <= MAX_ARRANGE_GAP_RETRIES; retry++) {
                if (retry > 0) {
                    // Clear previous arrange+resize commands, keep layout commands
                    while (compound.size() > layoutCommandCount) {
                        compound.getCommands().remove(compound.size() - 1);
                    }
                    currentInterGroupSpacing += ARRANGE_GAP_RETRY_INCREMENT;
                    logger.info("Arrange-groups gap retry {}/{}: interGroupSpacing={}",
                            retry, MAX_ARRANGE_GAP_RETRIES, currentInterGroupSpacing);
                }

                // Compute arrange positions using virtual bounds (post-resize dimensions)
                int arrangeStartX = ContainerArrangement.ORIGIN;
                int arrangeStartY = ContainerArrangement.ORIGIN;
                List<int[]> positions;

                // Lane sizes per retry (depend on currentInterGroupSpacing).
                boolean rowLane = "row".equals(arrangement);
                List<Integer> laneSizes;
                if (!gapAssignments.isEmpty()) {
                    laneSizes = ArrangeGroupsStandaloneLane.computeLaneSizes(
                            gapAssignments, orderedGroups.size(),
                            currentInterGroupSpacing, rowLane);
                } else {
                    laneSizes = java.util.Collections.emptyList();
                }

                int nOrdered = orderedGroups.size();
                if (rowLane) {
                    positions = new ArrayList<>();
                    int curX = arrangeStartX;
                    for (int i = 0; i < nOrdered; i++) {
                        IDiagramModelObject g = orderedGroups.get(i);
                        int[] vb = virtualGroupBounds.get(g.getId());
                        int w = (vb != null) ? vb[0] : g.getBounds().getWidth();
                        positions.add(new int[]{curX, arrangeStartY});
                        curX += w;
                        if (i < nOrdered - 1) {
                            int laneSize = (i < laneSizes.size()) ? laneSizes.get(i) : 0;
                            curX += (laneSize > 0) ? laneSize : currentInterGroupSpacing;
                        }
                    }
                } else {
                    positions = new ArrayList<>();
                    int curY = arrangeStartY;
                    for (int i = 0; i < nOrdered; i++) {
                        IDiagramModelObject g = orderedGroups.get(i);
                        int[] vb = virtualGroupBounds.get(g.getId());
                        int h = (vb != null) ? vb[1] : g.getBounds().getHeight();
                        positions.add(new int[]{arrangeStartX, curY});
                        curY += h;
                        if (i < nOrdered - 1) {
                            int laneSize = (i < laneSizes.size()) ? laneSizes.get(i) : 0;
                            curY += (laneSize > 0) ? laneSize : currentInterGroupSpacing;
                        }
                    }
                }

                // Build arrange commands using virtual bounds for dimensions
                List<int[]> groupDimsList = new ArrayList<>();
                for (int i = 0; i < orderedGroups.size(); i++) {
                    IDiagramModelObject g = orderedGroups.get(i);
                    int[] vb = virtualGroupBounds.get(g.getId());
                    int w = (vb != null) ? vb[0] : g.getBounds().getWidth();
                    int h = (vb != null) ? vb[1] : g.getBounds().getHeight();
                    int[] pos = positions.get(i);
                    compound.add(new UpdateViewObjectCommand(g,
                            pos[0], pos[1], w, h));
                    groupDimsList.add(new int[]{w, h});
                }
                groupsArranged = orderedGroups.size();

                // Emit qualifier
                // placement commands (parallels primary site at arrangeGroups :10785+).
                if (!gapAssignments.isEmpty()) {
                    List<ArrangeGroupsStandaloneLane.QualifierPlacement> placements =
                            ArrangeGroupsStandaloneLane.placeQualifiers(
                                    gapAssignments, orderedGroups.size(),
                                    positions, groupDimsList,
                                    currentInterGroupSpacing, rowLane);
                    for (ArrangeGroupsStandaloneLane.QualifierPlacement p : placements) {
                        compound.add(new UpdateViewObjectCommand(p.element(),
                                p.x(), p.y(), p.width(), p.height()));
                    }
                }

                // Step 3: Validate group gaps (no overlaps)
                List<int[]> groupRects = new ArrayList<>();
                for (int i = 0; i < orderedGroups.size(); i++) {
                    IDiagramModelObject g = orderedGroups.get(i);
                    int[] vb = virtualGroupBounds.get(g.getId());
                    int w = (vb != null) ? vb[0] : g.getBounds().getWidth();
                    int h = (vb != null) ? vb[1] : g.getBounds().getHeight();
                    int[] pos = positions.get(i);
                    groupRects.add(new int[]{pos[0], pos[1], w, h});
                }

                if (GroupLayoutCalculator.validateGroupGaps(groupRects)) {
                    logger.info("Arrange-groups gap validation passed (retry={})", retry);
                    break;
                }

                if (retry == MAX_ARRANGE_GAP_RETRIES) {
                    logger.warn("Arrange-groups gap validation failed after {} retries, "
                            + "proceeding with best available layout", MAX_ARRANGE_GAP_RETRIES);
                }
            }
        }

        return new GroupedLayoutPassResult(compound, elementsRepositioned, groupsArranged,
                AnchorResolver.projectMoves(fittedContainers, diagramModel), resizedLeaves, depthCapHit);
    }

    /**
     * Single-pass grouped mode without quality iteration.
     */
    private MutationResult<AutoLayoutAndRouteResultDto> executeGroupedSinglePass(
            String sessionId, String viewId, String direction, int spacing,
            IArchimateModel model, IArchimateDiagramModel diagramModel,
            List<IDiagramModelObject> topLevelGroups, LabelPolicy labelPolicy) {

        int interGroupSpacing = (int) (spacing * 1.5);
        logger.info("Grouped single-pass: step 1/4 — computing layout for {} groups, "
                + "intraGroupSpacing={}, interGroupSpacing={}",
                topLevelGroups.size(), spacing, interGroupSpacing);
        GroupedLayoutPassResult layoutPass = computeGroupedLayoutPass(
                viewId, direction, spacing, interGroupSpacing, model, diagramModel,
                topLevelGroups);

        // Apply layout+arrange temporarily to compute optimize+route
        logger.info("Grouped single-pass: step 2/4 — applying layout temporarily");
        mutationDispatcher.dispatchImmediate(layoutPass.compound);
        int undoCount = 1;

        // Optimize group order
        logger.info("Grouped single-pass: step 3/4 — optimizing group order");
        OptimizeGroupOrderPassResult optimizeResult =
                computeOptimizeGroupOrderPass(diagramModel, model, direction);
        if (optimizeResult != null) {
            mutationDispatcher.dispatchImmediate(optimizeResult.compound);
            undoCount++;
        }

        // Auto-route connections
        logger.info("Grouped single-pass: step 4/4 — routing connections");
        AutoRoutePassResult routeResult = computeAutoRoutePass(viewId, diagramModel, model, labelPolicy);
        if (routeResult != null) {
            mutationDispatcher.dispatchImmediate(routeResult.compound);
            undoCount++;
        }

        // Undo all temporary dispatches
        mutationDispatcher.undo(undoCount);

        // Merge all compounds into one for final dispatch
        NonNotifyingCompoundCommand mergedCompound =
                new NonNotifyingCompoundCommand(layoutPass.compound.getLabel());
        NestedLayoutOperations.appendAll(mergedCompound, layoutPass.compound);
        if (optimizeResult != null) {
            NestedLayoutOperations.appendAll(mergedCompound, optimizeResult.compound);
        }
        if (routeResult != null) {
            NestedLayoutOperations.appendAll(mergedCompound, routeResult.compound);
        }

        int totalPositionCount = layoutPass.elementsRepositioned
                + (optimizeResult != null ? optimizeResult.positionCount : 0);
        int totalRoutedCount = (routeResult != null ? routeResult.routedCount : 0);
        int labelsOptimized = (routeResult != null ? routeResult.labelsOptimized : 0);
        boolean routerTypeSwitched = (routeResult != null && routeResult.routerTypeSwitched);

        List<String> hiddenLabelIds = LabelVisibilityReadback.excludeQueuedHides(
                routeResult != null ? routeResult.hiddenLabelIds : List.of(),
                mutationDispatcher.queuedLabelVisibility(sessionId));

        AutoLayoutAndRouteResultDto dto = new AutoLayoutAndRouteResultDto(
                viewId, "grouped", direction, spacing,
                totalPositionCount, totalRoutedCount, routerTypeSwitched,
                mergedCompound.size(), layoutPass.groupsArranged,
                labelsOptimized, 0, null, null, null, null, null, null, null, null,
                layoutPass.nestedContainersFitted, layoutPass.depthCapHit,
                LabelVisibilityReadback.projected(hiddenLabelIds), List.of(),
                AnchorResolver.projectResized(layoutPass.resizedLeaves,
                    optimizeResult == null ? null : optimizeResult.resizedChildren,
                    mergedCompound, diagramModel));

        // Approval gate
        if (mutationDispatcher.isApprovalRequired(sessionId)) {
            Map<String, Object> proposedChanges = new LinkedHashMap<>();
            proposedChanges.put("mode", "grouped");
            proposedChanges.put("direction", direction);
            proposedChanges.put("spacing", spacing);
            proposedChanges.put("elementsRepositioned", totalPositionCount);
            proposedChanges.put("connectionsRouted", totalRoutedCount);
            proposedChanges.put("groupsArranged", layoutPass.groupsArranged);
            ProposalContext ctx = storeAsProposal(sessionId,
                    "auto-layout-and-route",
                    () -> new PreparedMutation<>(mergedCompound, dto, viewId),
                    compoundTargetIds(mergedCompound, viewId), dto,
                    mergedCompound.getLabel(), null, proposedChanges,
                    "Grouped layout computed and ready for application."
                    + LabelVisibilityReadback.describeFrozenHides(hiddenLabelIds)
                    + ProposalBuilder.REVIEWED_OR_REJECT);
            return new MutationResult<>(dto, null, ctx);
        }

        // Dispatch or queue
        Integer batchSeq = dispatchOrQueue(sessionId, mergedCompound,
                mergedCompound.getLabel());
        if (batchSeq == null) {
            versionCounter.incrementAndGet();
        }
        return new MutationResult<>(
                dto.withHiddenLabels(LabelVisibilityReadback.report(
                        model, hiddenLabelIds, batchSeq == null)),
                batchSeq);
    }

    /**
     * Quality target iteration loop for grouped mode.
     * Same iteration strategy as ELK mode: increase spacing, track best, plateau detection.
     */
    private MutationResult<AutoLayoutAndRouteResultDto> executeGroupedQualityTargetLoop(
            String sessionId, String viewId, String direction, int baseSpacing,
            String targetRating, IArchimateModel model,
            IArchimateDiagramModel diagramModel,
            List<IDiagramModelObject> topLevelGroups, LabelPolicy labelPolicy) {

        // The state the caller handed us, measured once before anything is applied. The loop below
        // ranks the states it PRODUCES against each other; without this it never compares any of
        // them against the view it was given, so a run that made the view worse looks identical on
        // the wire to a run that failed to improve one. Reporting only — it must never become a
        // candidate in the best-tracking comparison below.
        AssessLayoutResultDto preCallAssessment = assessLayout(viewId);

        // Track best result across iterations
        NonNotifyingCompoundCommand bestCompound = null;
        String bestRating = "not-applicable";
        int bestScore = Integer.MAX_VALUE;
        int bestPositionCount = 0;
        int bestRoutedCount = 0;
        int bestLabelsOptimized = 0;
        List<String> bestHiddenLabelIds = List.of();
        boolean bestRouterTypeSwitched = false;
        int bestGroupsArranged = 0;
        GroupedLayoutPassResult bestLayoutPass = null; Map<String, Command> bestOptimizeResizes = null;
        int bestSpacing = baseSpacing;
        AssessLayoutResultDto bestAssessment = null;

        String previousRating = null;
        String previousLimitingFactor = null;
        int previousFactorCount = 0;
        int iterationsPerformed = 0;
        String currentLimitingFactor = null;
        int spacingStep = 0;
        String terminationReason = null;

        for (int i = 0; i < MAX_TARGET_RATING_ITERATIONS; i++) {
            int currentIntraSpacing = baseSpacing
                    + (spacingStep * TARGET_RATING_SPACING_INCREMENT);
            int currentInterSpacing = (int) (currentIntraSpacing * 1.5)
                    + (spacingStep * TARGET_RATING_SPACING_INCREMENT);

            String remediationType = QualityTargetTermination.remediationTypeFor(
                    "grouped", i, currentLimitingFactor);

            // Early exit for non-remediable factors (best already tracked from prior iteration)
            if (QualityTargetTermination.isEarlyExit(remediationType)) {
                logger.info("Quality target iteration {}: limitingFactor={}, remediation={}",
                        i + 1, currentLimitingFactor, remediationType);
                terminationReason = QualityTargetTermination.limitingFactorNotRemediable(
                        currentLimitingFactor);
                break;
            }

            iterationsPerformed = i + 1;

            // Re-discover top-level containers each iteration (EMF state changes after undo)
            List<IDiagramModelObject> iterGroups = (i == 0)
                    ? topLevelGroups
                    : TopLevelGroupTargets.collectPopulated(diagramModel);

            GroupedLayoutPassResult layoutPass = null;
            OptimizeGroupOrderPassResult optimizeResult = null;
            AutoRoutePassResult routeResult = null;
            int undoCount = 0;

            if ("reroute-only".equals(remediationType)) {
                // Routing-only remediation: skip layout and group order, re-route with boosted
                // corridor diversity to force exploration of alternative paths
                double boostedWeight = Math.min(
                        VisibilityGraphRouter.DEFAULT_OCCUPANCY_WEIGHT * (1.0 + i * 0.5),
                        MAX_OCCUPANCY_WEIGHT);
                logger.info("Quality target iteration {}: limitingFactor={}, remediation=reroute-only(occupancy={})",
                        iterationsPerformed, currentLimitingFactor,
                        String.format("%.2f", boostedWeight));
                routeResult = computeAutoRoutePass(viewId, diagramModel, model, boostedWeight, labelPolicy);
                if (routeResult != null) {
                    mutationDispatcher.dispatchImmediate(routeResult.compound);
                    undoCount++;
                }
            } else if ("reorder-and-reroute".equals(remediationType)) {
                // Crossing remediation: reorder groups with reverse sweep + re-route
                logger.info("Quality target iteration {}: limitingFactor={}, remediation={}",
                        iterationsPerformed, currentLimitingFactor, remediationType);
                optimizeResult = computeOptimizeGroupOrderPass(
                        diagramModel, model, direction, /* reverseSweep= */ true);
                if (optimizeResult != null) {
                    mutationDispatcher.dispatchImmediate(optimizeResult.compound);
                    undoCount++;

                    // Re-route only when group order actually changed
                    routeResult = computeAutoRoutePass(viewId, diagramModel, model, labelPolicy);
                    if (routeResult != null) {
                        mutationDispatcher.dispatchImmediate(routeResult.compound);
                        undoCount++;
                    }
                }
            } else {
                // Full pipeline: layout + optimize + route (iteration 0, overlaps, spacing, alignment)
                logger.info("Quality target iteration {}: limitingFactor={}, remediation={}",
                        iterationsPerformed, currentLimitingFactor, remediationType);
                layoutPass = computeGroupedLayoutPass(
                        viewId, direction, currentIntraSpacing, currentInterSpacing,
                        model, diagramModel, iterGroups);
                mutationDispatcher.dispatchImmediate(layoutPass.compound);
                undoCount++;

                optimizeResult = computeOptimizeGroupOrderPass(diagramModel, model, direction);
                if (optimizeResult != null) {
                    mutationDispatcher.dispatchImmediate(optimizeResult.compound);
                    undoCount++;
                }

                routeResult = computeAutoRoutePass(viewId, diagramModel, model, labelPolicy);
                if (routeResult != null) {
                    mutationDispatcher.dispatchImmediate(routeResult.compound);
                    undoCount++;
                }

                // Spacing used — advance for next layout iteration
                spacingStep++;
            }

            // Assess layout quality
            AssessLayoutResultDto assessment = assessLayout(viewId);
            String rating = assessment.overallRating();
            int score = QualityTargetTermination.tierWeightedScore(assessment);

            // Update limiting factor for next iteration
            currentLimitingFactor = QualityTargetTermination.findLimitingFactor(assessment);

            logger.info("Grouped quality target iteration {}: intraSpacing={}, "
                    + "interSpacing={}, rating={}, overlaps={}, crossings={}, groups={}",
                    iterationsPerformed, currentIntraSpacing, currentInterSpacing,
                    rating, assessment.overlapCount(), assessment.edgeCrossingCount(),
                    layoutPass != null ? layoutPass.groupsArranged : 0);

            // Track best result — merge all compounds into one (tier-weighted + veto)
            if (!QualityTargetTermination.hasTier1Regression(assessment, bestAssessment)
                    && (LayoutQualityAssessor.ratingOrdinal(rating)
                            > LayoutQualityAssessor.ratingOrdinal(bestRating)
                        || (LayoutQualityAssessor.ratingOrdinal(rating)
                                == LayoutQualityAssessor.ratingOrdinal(bestRating)
                            && score < bestScore))) {

                NonNotifyingCompoundCommand mergedCompound =
                        new NonNotifyingCompoundCommand(
                                "Grouped layout iter " + iterationsPerformed
                                        + " (" + remediationType + ")");
                if (layoutPass != null) {
                    NestedLayoutOperations.appendAll(mergedCompound, layoutPass.compound);
                }
                if (optimizeResult != null) {
                    NestedLayoutOperations.appendAll(mergedCompound, optimizeResult.compound);
                }
                if (routeResult != null) {
                    NestedLayoutOperations.appendAll(mergedCompound, routeResult.compound);
                }
                bestCompound = mergedCompound;
                bestRating = rating;
                bestScore = score;
                bestPositionCount = (layoutPass != null ? layoutPass.elementsRepositioned : 0)
                        + (optimizeResult != null ? optimizeResult.positionCount : 0);
                bestRoutedCount = (routeResult != null ? routeResult.routedCount : 0);
                bestLabelsOptimized = (routeResult != null ? routeResult.labelsOptimized : 0);
                bestHiddenLabelIds = LabelVisibilityReadback.excludeQueuedHides(
                        routeResult != null ? routeResult.hiddenLabelIds : List.of(),
                        mutationDispatcher.queuedLabelVisibility(sessionId));
                bestRouterTypeSwitched = (routeResult != null && routeResult.routerTypeSwitched);
                bestGroupsArranged = (layoutPass != null ? layoutPass.groupsArranged : 0);
                bestLayoutPass = layoutPass; bestOptimizeResizes = optimizeResult == null ? null : optimizeResult.resizedChildren;
                bestSpacing = currentIntraSpacing;
                bestAssessment = assessment;
            }

            // Undo ALL dispatched commands
            if (undoCount > 0) {
                mutationDispatcher.undo(undoCount);
            }

            // Target met — break
            if (LayoutQualityAssessor.meetsTarget(rating, targetRating)) {
                logger.info("Grouped quality target '{}' met with rating '{}' on iteration {}",
                        targetRating, rating, iterationsPerformed);
                terminationReason =
                        QualityTargetTermination.goalReachedAtIteration(iterationsPerformed);
                break;
            }

            // Null limiting factor means all metrics pass — target should be met
            if (currentLimitingFactor == null) {
                logger.info("Grouped quality target: no limiting factor — all metrics pass on iteration {}",
                        iterationsPerformed);
                terminationReason =
                        QualityTargetTermination.allMetricsPassAtIteration(iterationsPerformed);
                break;
            }

            // Factor-aware plateau detection
            int currentFactorCount = QualityTargetTermination.getMetricCount(currentLimitingFactor, assessment);

            if (i > 0 && isFactorAwarePlateauReached(
                    currentLimitingFactor, previousLimitingFactor,
                    currentFactorCount, previousFactorCount,
                    rating, previousRating)) {
                logger.info("Grouped quality target plateau detected at iteration {} — "
                    + "factor={}, count={}, stopping early", iterationsPerformed,
                    currentLimitingFactor, currentFactorCount);
                terminationReason =
                        QualityTargetTermination.plateauAtIteration(iterationsPerformed);
                break;
            }

            previousRating = rating;
            previousLimitingFactor = currentLimitingFactor;
            previousFactorCount = currentFactorCount;
        }

        // Falling out of the loop rather than breaking means the budget ran out.
        if (terminationReason == null) {
            terminationReason = QualityTargetTermination.budgetExhaustedAfter(iterationsPerformed);
        }

        if (bestCompound == null) {
            throw new ModelAccessException(
                    "Grouped quality target iteration produced no results",
                    ErrorCode.INTERNAL_ERROR);
        }

        // Label optimization fallback
        int labelFallbackTrials = 0;
        boolean targetMetBeforeFallback =
                LayoutQualityAssessor.meetsTarget(bestRating, targetRating);
        LabelFallbackResult fallback = executeLabelFallback(
                bestCompound, bestRating, bestScore, bestLabelsOptimized,
                bestAssessment, targetRating, viewId, diagramModel, model);
        if (fallback != null) {
            labelFallbackTrials = fallback.trials;
            bestRating = fallback.rating;
            bestScore = fallback.score;
            bestAssessment = fallback.assessment;
            bestLabelsOptimized = fallback.labelsOptimized;
        }
        // The fallback overwrites bestRating, so it can falsify the reason captured at the break.
        terminationReason = QualityTargetTermination.reconcileAfterLoop(terminationReason,
                targetMetBeforeFallback,
                LayoutQualityAssessor.meetsTarget(bestRating, targetRating));

        logger.info("Grouped quality target loop complete: best='{}' after {} iterations "
                + "(target='{}', termination={})",
                bestRating, iterationsPerformed, targetRating, terminationReason);

        AutoLayoutAndRouteResultDto dto = buildQualityTargetDto(
                "grouped", viewId, direction, bestSpacing,
                bestPositionCount, bestRoutedCount, bestRouterTypeSwitched,
                bestCompound.size(), bestGroupsArranged,
                bestLabelsOptimized, labelFallbackTrials,
                targetRating, bestRating,
                iterationsPerformed, bestAssessment, bestLayoutPass, bestHiddenLabelIds,
                terminationReason, preCallAssessment, bestLayoutPass == null ? List.of()
                        : AnchorResolver.projectResized(bestLayoutPass.resizedLeaves,
                                bestOptimizeResizes, bestCompound, diagramModel));

        // Approval gate
        if (mutationDispatcher.isApprovalRequired(sessionId)) {
            Map<String, Object> proposedChanges = new LinkedHashMap<>();
            proposedChanges.put("mode", "grouped");
            proposedChanges.put("direction", direction);
            proposedChanges.put("spacing", bestSpacing);
            proposedChanges.put("elementsRepositioned", bestPositionCount);
            proposedChanges.put("connectionsRouted", bestRoutedCount);
            proposedChanges.put("groupsArranged", bestGroupsArranged);
            proposedChanges.put("targetRating", targetRating);
            proposedChanges.put("achievedRating", bestRating);
            QualityTargetTermination.putProposedRatingDisclosure(proposedChanges, dto.ratingBefore(), preCallAssessment, bestAssessment);
            proposedChanges.put("iterationsPerformed", iterationsPerformed);
            proposedChanges.put("terminationReason", terminationReason);
            proposedChanges.put("limitingFactor", dto.limitingFactor());
            final var approvedCompound = bestCompound; // effectively-final capture for the rebuild handle
            ProposalContext ctx = storeAsProposal(sessionId,
                    "auto-layout-and-route",
                    () -> new PreparedMutation<>(approvedCompound, dto, viewId),
                    compoundTargetIds(approvedCompound, viewId), dto,
                    bestCompound.getLabel(), null, proposedChanges,
                    "Grouped layout with quality target '" + targetRating
                    + "' computed — achieved '" + bestRating
                    + "' after " + iterationsPerformed + " iteration(s)."
                    + LabelVisibilityReadback.describeFrozenHides(bestHiddenLabelIds)
                    + ProposalBuilder.REVIEWED_OR_REJECT);
            return new MutationResult<>(QualityTargetTermination.rescopeAsProposed(dto, preCallAssessment, bestAssessment), null, ctx);
        }

        // Dispatch or queue
        Integer batchSeq = dispatchOrQueue(sessionId, bestCompound,
                bestCompound.getLabel());
        if (batchSeq == null) {
            versionCounter.incrementAndGet();
        }
        return new MutationResult<>(QualityTargetTermination.rescopeIfQueued(
                dto.withHiddenLabels(LabelVisibilityReadback.report(
                        model, bestHiddenLabelIds, batchSeq == null)),
                batchSeq, preCallAssessment, bestAssessment), batchSeq);
    }

    /**
     * Attempts label optimization as a zero-layout-cost fallback.
     * Dispatches bestCompound temporarily, runs multi-trial label optimization,
     * and if improved, merges label commands into bestCompound.
     *
     * @return result with updated metrics, or null if no fallback needed or no improvement
     */
    private LabelFallbackResult executeLabelFallback(
            NonNotifyingCompoundCommand bestCompound,
            String bestRating, int bestScore, int bestLabelsOptimized,
            AssessLayoutResultDto bestAssessment, String targetRating,
            String viewId, IArchimateDiagramModel diagramModel, IArchimateModel model) {

        if (bestAssessment == null || bestAssessment.labelOverlapCount() == 0) {
            return null;
        }

        Map<String, String> breakdown = bestAssessment.ratingBreakdown();
        String labelRating = breakdown != null ? breakdown.get("labelOverlaps") : null;
        if (labelRating == null || LayoutQualityAssessor.meetsTarget(labelRating, targetRating)) {
            return null;
        }

        logger.info("Label fallback triggered: labelOverlaps rating='{}' < target='{}', overlaps={}",
                labelRating, targetRating, bestAssessment.labelOverlapCount());

        // Temporarily re-apply best layout to read EMF state
        mutationDispatcher.dispatchImmediate(bestCompound);

        LabelOptimizationPass.Result labelResult =
                LabelOptimizationPass.compute(diagramModel, 10);

        if (labelResult == null) {
            // No label improvements found — undo best compound only
            mutationDispatcher.undo(1);
            return null;
        }

        int labelFallbackTrials = labelResult.trials;

        // Apply label changes and re-assess
        mutationDispatcher.dispatchImmediate(labelResult.compound);
        AssessLayoutResultDto fallbackAssessment = assessLayout(viewId);
        String fallbackRating = fallbackAssessment.overallRating();
        int fallbackScore = QualityTargetTermination.tierWeightedScore(fallbackAssessment);

        // tier-weighted + veto comparison
        boolean improved = !QualityTargetTermination.hasTier1Regression(fallbackAssessment, bestAssessment)
                && (LayoutQualityAssessor.ratingOrdinal(fallbackRating)
                        > LayoutQualityAssessor.ratingOrdinal(bestRating)
                    || (LayoutQualityAssessor.ratingOrdinal(fallbackRating)
                            == LayoutQualityAssessor.ratingOrdinal(bestRating)
                        && fallbackScore < bestScore));

        logger.info("Label fallback: {} trials, rating {} -> {}, labelOverlaps {} -> {}",
                labelResult.trials, bestRating, fallbackRating,
                bestAssessment.labelOverlapCount(),
                fallbackAssessment.labelOverlapCount());

        String resultRating = bestRating;
        int resultScore = bestScore;
        AssessLayoutResultDto resultAssessment = bestAssessment;
        int resultLabelsOptimized = bestLabelsOptimized;

        if (improved) {
            LabelOffsetSupport.merge(bestCompound, labelResult.compound, false); // along-path + offsets
            resultRating = fallbackRating;
            resultScore = fallbackScore;
            resultAssessment = fallbackAssessment;
            // Dedup across BOTH command types (a position change + an offset on one connection count once).
            resultLabelsOptimized = LabelOffsetSupport.countOptimizedLabels(bestCompound);
        } else if (LabelOffsetSupport.hasOffsetCommand(labelResult.compound)) {
            // Metric unchanged but the pass found perpendicular offsets clearing an own-endpoint/element
            // bleed the labelOverlap metric cannot see (it derives bounds from textPosition, not
            // relativePosition). Keep ONLY the offsets (render-positive, metric-neutral); rating stays best.
            LabelOffsetSupport.merge(bestCompound, labelResult.compound, true);
            resultLabelsOptimized = LabelOffsetSupport.countOptimizedLabels(bestCompound);
        }

        // Undo label + best compound (2 dispatches)
        mutationDispatcher.undo(2);

        return new LabelFallbackResult(labelFallbackTrials, resultRating, resultScore,
                resultAssessment, resultLabelsOptimized);
    }

    private AutoLayoutAndRouteResultDto buildQualityTargetDto(
            String mode, String viewId, String direction, int spacing,
            int positionCount, int routedCount, boolean routerTypeSwitched,
            int totalOperations, int groupsArranged,
            int labelsOptimized, int labelFallbackTrials,
            String targetRating, String achievedRating,
            int iterationsPerformed, AssessLayoutResultDto assessment,
            GroupedLayoutPassResult layoutPass, List<String> hiddenLabelIds,
            String terminationReason, AssessLayoutResultDto preCallAssessment,
            List<MovedViewObjectDto> resizedElements) {
        AutoLayoutAssessmentSummaryDto summary = assessment != null
                ? new AutoLayoutAssessmentSummaryDto(
                        assessment.overlapCount(),
                        assessment.edgeCrossingCount(),
                        assessment.averageSpacing(),
                        assessment.alignmentScore(),
                        assessment.overallRating(),
                        assessment.suggestions())
                : null;

        // Compute limiting factor when target not met
        String limitingFactor = null;
        String suggestedRemediation = null;
        if (targetRating != null && !LayoutQualityAssessor.meetsTarget(achievedRating, targetRating)
                && assessment != null && assessment.ratingBreakdown() != null) {
            limitingFactor = QualityTargetTermination.findLimitingFactor(assessment);
            if (limitingFactor != null) {
                suggestedRemediation = QualityTargetTermination.getRemediation(limitingFactor);
            }
        }

        return new AutoLayoutAndRouteResultDto(
                viewId, mode, direction, spacing,
                positionCount, routedCount, routerTypeSwitched, totalOperations,
                groupsArranged, labelsOptimized, labelFallbackTrials,
                targetRating, achievedRating,
                preCallAssessment != null ? preCallAssessment.overallRating() : null,
                iterationsPerformed, summary, limitingFactor, suggestedRemediation,
                terminationReason,
                layoutPass != null ? layoutPass.nestedContainersFitted : List.of(),
                layoutPass != null && layoutPass.depthCapHit,
                LabelVisibilityReadback.projected(hiddenLabelIds),
                QualityTargetTermination.ratingRegressionWarnings(preCallAssessment, assessment),
                resizedElements);
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
     * Result from buildOrthogonalRoutingCommands — shared routing logic.
     */
    private static class OrthogonalRoutingResult {
        final List<Command> commands;
        final int routedCount;
        final List<FailedConnection> failedConnections;
        final List<MoveRecommendation> moveRecommendations;
        final int labelsOptimized;
        /** Routed absolute bendpoints per connection ID (crossing delta). */
        final Map<String, List<AbsoluteBendpointDto>> routedPaths;
        /** Straight-line crossing estimate. */
        final int straightLineCrossings;
        final int egressRolledBack;
        /** Connections whose label this pass hid because it had no placeable position. */
        final List<String> hiddenLabelIds;

        OrthogonalRoutingResult(List<Command> commands, int routedCount,
                List<FailedConnection> failedConnections,
                List<MoveRecommendation> moveRecommendations,
                int labelsOptimized,
                Map<String, List<AbsoluteBendpointDto>> routedPaths,
                int straightLineCrossings, int egressRolledBack,
                List<String> hiddenLabelIds) {
            this.hiddenLabelIds = (hiddenLabelIds != null) ? hiddenLabelIds : List.of();
            this.commands = commands;
            this.routedCount = routedCount;
            this.failedConnections = failedConnections;
            this.moveRecommendations = moveRecommendations;
            this.labelsOptimized = labelsOptimized;
            this.routedPaths = new LinkedHashMap<>(routedPaths);
            this.straightLineCrossings = straightLineCrossings;
            this.egressRolledBack = egressRolledBack;
        }
    }

    /**
     * Result from computeOptimizeGroupOrderPass.
     */
    static class OptimizeGroupOrderPassResult {
        final NonNotifyingCompoundCommand compound;
        final int positionCount;
        /** Each child this pass re-sized, RAW so a later pass's rectangle can supersede it. */
        final Map<String, Command> resizedChildren;

        OptimizeGroupOrderPassResult(NonNotifyingCompoundCommand compound, int positionCount,
                Map<String, Command> resizedChildren) {
            this.compound = compound; this.resizedChildren = resizedChildren;
            this.positionCount = positionCount;
        }
    }

    /**
     * Result from computeAutoRoutePass.
     */
    static class AutoRoutePassResult {
        final NonNotifyingCompoundCommand compound;
        final int routedCount;
        final int labelsOptimized;
        final boolean routerTypeSwitched;
        /** Connections whose label this pass hid because it had no placeable position. */
        final List<String> hiddenLabelIds;

        AutoRoutePassResult(NonNotifyingCompoundCommand compound, int routedCount,
                int labelsOptimized, boolean routerTypeSwitched) {
            this(compound, routedCount, labelsOptimized, routerTypeSwitched, List.of());
        }

        AutoRoutePassResult(NonNotifyingCompoundCommand compound, int routedCount,
                int labelsOptimized, boolean routerTypeSwitched, List<String> hiddenLabelIds) {
            this.compound = compound;
            this.routedCount = routedCount;
            this.labelsOptimized = labelsOptimized;
            this.routerTypeSwitched = routerTypeSwitched;
            this.hiddenLabelIds = (hiddenLabelIds != null) ? hiddenLabelIds : List.of();
        }
    }

    /**
     * Result from label optimization fallback (extracted from quality loops).
     */
    private static class LabelFallbackResult {
        final int trials;
        final String rating;
        final int score;
        final AssessLayoutResultDto assessment;
        final int labelsOptimized;

        LabelFallbackResult(int trials, String rating, int score,
                AssessLayoutResultDto assessment, int labelsOptimized) {
            this.trials = trials;
            this.rating = rating;
            this.score = score;
            this.assessment = assessment;
            this.labelsOptimized = labelsOptimized;
        }
    }

    /**
     * Computes a single ELK layout pass: ELK positions + connection routes + router switch.
     * Returns the compound command without executing it.
     */
    private ElkLayoutPassResult computeElkLayoutPass(
            String sessionId, String viewId, String direction, int spacing,
            IArchimateModel model, IArchimateDiagramModel diagramModel,
            List<LayoutNode> nodes, List<LayoutEdge> edges) {

        // Compute ELK layout (positions + routes)
        ElkLayoutResult elkResult = elkLayoutEngine.computeLayout(
                nodes, edges, direction, spacing);

        // Build commands for element position updates
        List<Command> commands = new ArrayList<>();
        int positionCount = 0;
        Map<String, int[]> passBounds = sameBatchBounds(sessionId); Map<String, IDiagramModelContainer> qParents = mutationDispatcher.queuedParents(sessionId); Map<String, int[]> qBounds = mutationDispatcher.queuedBounds(sessionId); Map<String, String[]> qAnchors = mutationDispatcher.queuedAnchors(sessionId); // the collector emits a container BEFORE its children, so the group's own new rectangle is the base their fits measure against. Read once, not per node: every projection is a full queue walk under the session lock
        for (ViewPositionSpec pos : elkResult.positions()) {
            PreparedMutation<ViewObjectDto> prepared = prepareUpdateViewObject(pos.viewObjectId(), pos.x(), pos.y(), pos.width(), pos.height(),
                    null, null, null, null, null, null, null, null, null, null, qParents, qBounds, qAnchors, passBounds, null); // no pass-owned command map: this collector emits a container BEFORE its children, so each child measures a rectangle the container's own entry already set and the cascade emits nothing to consolidate — measured at zero on a flat and a nested view. It is also the one layout pass whose published operation count IS its compound's size, so appending to that compound would move a reported field with no redundancy to remove
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
                    prepareUpdateViewConnection(null, relativeBendpoints, null, null, null, null, archConn);
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

    // ---- Terminals-only routing mode ----

    /**
     * Result from {@link #buildTerminalsOnlyCommands}. {@code skippedCount}
     * is the total across three disjoint categories, broken out so callers can
     * tell "nothing to do here" from "nothing I could do here safely":
     * <ul>
     *   <li>{@code alreadyOrthogonalCount}: terminal segments already within 5°
     *       of a cardinal axis — no rectification needed</li>
     *   <li>{@code vetoedByObstacleCount}: rectification proposed but rejected
     *       because the new L-bend would cross an unrelated element</li>
     *   <li>{@code vetoedByCrossingCount}: rectification proposed but rejected
     *       because the new path would add more edge crossings with other
     *       connections than the old path</li>
     *   <li>{@code vetoedByInteriorCount}: rectification proposed but rejected
     *       because the new L-bend would terminate strictly inside the source or
     *       target element (an interior termination — a Tier-1 routing defect)</li>
     *   <li>{@code vetoedByZigzagCount}: rectification proposed but rejected
     *       because the new L-bend would introduce a zigzag/reversal the original
     *       path did not have (a Tier-1 routing defect; sibling of the interior veto)</li>
     * </ul>
     * {@code skippedCount == alreadyOrthogonalCount + vetoedByObstacleCount
     * + vetoedByCrossingCount + vetoedByInteriorCount + vetoedByZigzagCount}.
     */
    private static final class TerminalsOnlyResult {
        final List<Command> commands;
        final int routedCount;
        final int skippedCount;
        final int alreadyOrthogonalCount;
        final int vetoedByObstacleCount;
        final int vetoedByCrossingCount;
        final int vetoedByInteriorCount;
        final int vetoedByZigzagCount;

        TerminalsOnlyResult(List<Command> commands, int routedCount,
                int alreadyOrthogonalCount, int vetoedByObstacleCount,
                int vetoedByCrossingCount, int vetoedByInteriorCount,
                int vetoedByZigzagCount) {
            this.commands = commands;
            this.routedCount = routedCount;
            this.alreadyOrthogonalCount = alreadyOrthogonalCount;
            this.vetoedByObstacleCount = vetoedByObstacleCount;
            this.vetoedByCrossingCount = vetoedByCrossingCount;
            this.vetoedByInteriorCount = vetoedByInteriorCount;
            this.vetoedByZigzagCount = vetoedByZigzagCount;
            this.skippedCount = alreadyOrthogonalCount
                    + vetoedByObstacleCount + vetoedByCrossingCount
                    + vetoedByInteriorCount + vetoedByZigzagCount;
        }
    }

    /**
     * Dispatcher for the terminals-only branch of {@link #autoRouteConnections}.
     * Re-routes only the first/last segment of each target connection so terminal
     * segments become orthogonal, leaving all intermediate bendpoints unchanged.
     * Never moves elements; never invokes the visibility-graph A* router.
     */
    private MutationResult<AutoRouteResultDto> runTerminalsOnly(
            String sessionId, String viewId, IArchimateDiagramModel diagramModel,
            List<IDiagramModelConnection> targetConnections, String effectiveStrategy,
            boolean force, DispatchArm arm, List<String> warnings, List<StructuredWarningDto> structuredWarnings) {

        TerminalsOnlyResult result = buildTerminalsOnlyCommands(
                diagramModel, targetConnections, force, arm, warnings, structuredWarnings);

        List<Command> commands = new ArrayList<>(result.commands);

        // Switch view to bendpoint mode if needed — terminals-only writes stored
        // bendpoints, which manhattan router ignores. Mirrors the orthogonal branch.
        boolean routerTypeSwitched = false;
        int currentRouterType = diagramModel.getConnectionRouterType();
        if (!commands.isEmpty()
                && currentRouterType != IDiagramModel.CONNECTION_ROUTER_BENDPOINT) {
            commands.add(new UpdateViewCommand(diagramModel,
                    null, null, false, null, null,
                    IDiagramModel.CONNECTION_ROUTER_BENDPOINT));
            routerTypeSwitched = true;
            logger.info("Switching view {} from router type {} to bendpoint mode "
                    + "so terminals-only L-bends render correctly",
                    viewId, currentRouterType);
        }

        if (commands.size() > MAX_LAYOUT_OPERATIONS) {
            throw new ModelAccessException(
                    "Auto-route operation count (" + commands.size()
                            + ") exceeds maximum (" + MAX_LAYOUT_OPERATIONS + ")",
                    ErrorCode.INVALID_PARAMETER);
        }

        String label = "Auto-route connections (terminals-only, "
                + result.routedCount + " modified, " + result.skippedCount + " skipped)";
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(label);
        commands.forEach(compound::add);

        AutoRouteResultDto dto = new AutoRouteResultDto(
                viewId,
                result.routedCount,
                0,                              // connectionsFailed
                effectiveStrategy,
                routerTypeSwitched,
                0,                              // labelsOptimized
                0,                              // crossingsBefore
                0,                              // crossingsAfter
                0,                              // straightLineCrossings
                result.skippedCount,            // connectionsSkipped
                result.vetoedByObstacleCount,   // vetoedByObstacle
                result.vetoedByCrossingCount,   // vetoedByCrossing
                result.vetoedByInteriorCount,   // vetoedByInterior (interior-termination veto)
                result.vetoedByZigzagCount,     // vetoedByZigzag (zigzag-introduction veto)
                warnings,
                List.of(),                      // failed
                List.of(),                      // recommendations
                List.of(),                      // violations
                List.of(),                      // nudgedElements
                List.of(),                      // resizedGroups
                structuredWarnings);            // carries any connection-not-found code from the caller

        if (mutationDispatcher.isApprovalRequired(sessionId)) {
            Map<String, Object> proposedChanges = new LinkedHashMap<>();
            proposedChanges.put("strategy", effectiveStrategy);
            proposedChanges.put("force", force);
            proposedChanges.put("mode", "terminals-only");
            proposedChanges.put("connectionsRouted", result.routedCount);
            proposedChanges.put("connectionsSkipped", result.skippedCount);
            if (routerTypeSwitched) {
                proposedChanges.put("routerTypeSwitched",
                        "manhattan -> manual (bendpoint mode)");
            }
            ProposalContext ctx = storeAsProposal(sessionId,
                    "auto-route-connections",
                    () -> new PreparedMutation<>(compound, dto, viewId),
                    compoundTargetIds(compound, viewId), dto, label,
                    null, proposedChanges,
                    "Terminal-segment rectification computed and ready for application." + ProposalBuilder.REVIEWED_OR_REJECT);
            return new MutationResult<>(dto, null, ctx);
        }

        Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
        if (batchSeq == null) {
            versionCounter.incrementAndGet();
        }
        return new MutationResult<>(dto, batchSeq);
    }

    /**
     * EMF-aware orchestrator for terminals-only routing. Iterates over the target
     * connections, decodes stored bendpoints to absolute coordinates, delegates geometry
     * to {@link RoutingPipeline#terminalsOnlyRectifyAndClearEgress}, and — per live validation
     * on View 3 (retail bank, 2026-04-12) — applies two vetoes before emitting a
     * command:
     *
     * <ol>
     *   <li><b>Obstacle veto</b>: if any segment of the rectified path crosses an
     *       unrelated element (excluding source, target, and both endpoints' ancestors
     *       and descendants), revert that connection. This catches the pass-throughs
     *       that the naive approach introduced on the dense App Collaboration view.</li>
     *   <li><b>Crossing veto</b>: for each rectified connection, compute the segment
     *       crossings of the new path vs. all other connection paths on the view and
     *       compare to the old path's crossings. If the new path crosses <em>more</em>
     *       other paths, revert that connection. Straightening one terminal at the cost
     *       of new crossings is not a trade a reader wins: a crossing makes the eye
     *       disambiguate two lines, a slight diagonal entry does not.</li>
     * </ol>
     *
     * <p>Both vetoes count as "skipped" in the response, alongside genuine no-ops.
     * Pure-geometry logic (the rectification itself) stays in {@link RoutingPipeline}
     * for plain-JUnit testability (CLAUDE.md "Pure-geometry rule"); the vetoes live
     * here because they need view-wide obstacle + other-path context.</p>
     */
    private TerminalsOnlyResult buildTerminalsOnlyCommands(
            IArchimateDiagramModel diagramModel,
            List<IDiagramModelConnection> targetConnections,
            boolean force, DispatchArm arm, List<String> warnings,
            List<StructuredWarningDto> structuredWarnings) {

        // Pre-collect view-wide context once
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);
        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }
        List<AssessmentConnection> allAssessment =
                AssessmentCollector.collectAssessmentConnections(diagramModel, nodes);
        Map<String, List<double[]>> pathById = new LinkedHashMap<>();
        for (AssessmentConnection ac : allAssessment) {
            pathById.put(ac.id(), ac.pathPoints());
        }

        List<Command> commands = new ArrayList<>();
        Map<String, List<AbsoluteBendpointDto>> appliedPaths = new LinkedHashMap<>();
        int routed = 0;
        int alreadyOrthogonal = 0;
        int vetoedObstacle = 0;
        int vetoedCrossing = 0;
        int vetoedInterior = 0;
        int vetoedZigzag = 0;

        for (IDiagramModelConnection conn : targetConnections) {
            if (!(conn instanceof IDiagramModelArchimateConnection archConn)) {
                continue;
            }
            IConnectable srcConnectable = conn.getSource();
            IConnectable tgtConnectable = conn.getTarget();
            if (!(srcConnectable instanceof IDiagramModelArchimateObject srcObj)
                    || !(tgtConnectable instanceof IDiagramModelArchimateObject tgtObj)) {
                continue;
            }

            int[] srcCenter = ConnectionResponseBuilder.computeAbsoluteCenter(srcObj);
            int[] tgtCenter = ConnectionResponseBuilder.computeAbsoluteCenter(tgtObj);
            int srcCX = srcCenter[0], srcCY = srcCenter[1];
            int tgtCX = tgtCenter[0], tgtCY = tgtCenter[1];

            List<BendpointDto> existingRel =
                    ConnectionResponseBuilder.collectBendpoints(archConn);
            List<AbsoluteBendpointDto> existingAbs =
                    ConnectionResponseBuilder.convertRelativeToAbsolute(
                            existingRel, srcCX, srcCY, tgtCX, tgtCY);

            IBounds srcBounds = srcObj.getBounds();
            IBounds tgtBounds = tgtObj.getBounds();
            RoutingRect srcRect = new RoutingRect(
                    srcCX - srcBounds.getWidth() / 2,
                    srcCY - srcBounds.getHeight() / 2,
                    srcBounds.getWidth(), srcBounds.getHeight(), srcObj.getId());
            RoutingRect tgtRect = new RoutingRect(
                    tgtCX - tgtBounds.getWidth() / 2,
                    tgtCY - tgtBounds.getHeight() / 2,
                    tgtBounds.getWidth(), tgtBounds.getHeight(), tgtObj.getId());

            List<AbsoluteBendpointDto> newAbs =
                    RoutingPipeline.terminalsOnlyRectifyAndClearEgress(srcRect, tgtRect, existingAbs);
            if (newAbs == null) {
                alreadyOrthogonal++;
                continue;
            }

            // Build new full path as List<double[]> for obstacle + crossing checks
            List<double[]> newFullPath = new ArrayList<>(newAbs.size() + 2);
            newFullPath.add(new double[]{srcCX, srcCY});
            for (AbsoluteBendpointDto bp : newAbs) {
                newFullPath.add(new double[]{bp.x(), bp.y()});
            }
            newFullPath.add(new double[]{tgtCX, tgtCY});

            // force=true bypasses the obstacle + crossing vetoes — the caller
            // has accepted that an inserted L-bend may cross an unrelated element
            // (this matches the existing force semantics on the orthogonal strategy).
            if (!force) {
                // Interior-termination veto (Tier-1). Reject if the rectified L-bend would land a
                // terminal STRICTLY INSIDE its own element — an interior termination, which
                // forces the routing rating to "poor" and is strictly worse than the diagonal
                // terminal it was meant to fix. Checked FIRST (most severe), and the obstacle
                // veto below cannot catch it because it excludes the source/target elements
                // themselves. Predicate is parity-identical to assess-layout M2
                // (LayoutQualityAssessor.isStrictlyInside), so the veto rejects exactly the
                // set the assessor would flag.
                if (RoutingPipeline.terminalsOnlyTerminatesInside(srcRect, tgtRect, newAbs)) {
                    vetoedInterior++;
                    logger.debug("Interior-termination veto for connection {} — rectified "
                            + "L-bend would terminate inside the source or target element",
                            archConn.getId());
                    continue;
                }

                // Zigzag/reversal veto (Tier-1; sibling of the interior veto). Reject if the
                // rectified L-bend INTRODUCES a zigzag/reversal triple the original path did
                // not have — another way terminals-only can create a Tier-1 "poor" defect that
                // neither the obstacle nor crossing veto catches. Parity with assess-layout M3
                // (LayoutQualityAssessor.countZigzags). Compared (not absolute) so a connection
                // whose ELK body already zigzags is not vetoed for a defect we did not cause.
                if (RoutingPipeline.terminalsOnlyIntroducesZigzag(
                        pathById.get(archConn.getId()), newFullPath)) {
                    vetoedZigzag++;
                    logger.debug("Zigzag veto for connection {} — rectified L-bend would "
                            + "introduce a zigzag/reversal", archConn.getId());
                    continue;
                }

                // Build obstacle list (exclude source, target, ancestors, descendants,
                // containers, notes — mirrors buildOrthogonalRoutingCommands exclusion logic)
                Set<String> excludeIds = new HashSet<>();
                excludeIds.add(srcObj.getId());
                excludeIds.add(tgtObj.getId());
                excludeIds.addAll(RoutingExcludeSets.ancestorIds(srcObj.getId(), nodeMap));
                excludeIds.addAll(RoutingExcludeSets.ancestorIds(tgtObj.getId(), nodeMap));
                excludeIds.addAll(RoutingExcludeSets.descendantIds(srcObj.getId(), nodes));
                excludeIds.addAll(RoutingExcludeSets.descendantIds(tgtObj.getId(), nodes));
                List<RoutingRect> obstacles = new ArrayList<>();
                for (AssessmentNode node : nodes) {
                    if (excludeIds.contains(node.id())) continue;
                    if (node.isContainer() || node.isNote()) continue;
                    obstacles.add(new RoutingRect(
                            (int) node.x(), (int) node.y(),
                            (int) node.width(), (int) node.height(), node.id()));
                }

                // Obstacle veto: reject if any segment of the new path crosses an obstacle
                if (pathCrossesObstacles(newFullPath, obstacles)) {
                    vetoedObstacle++;
                    logger.debug("Obstacle veto for connection {} — new path crosses "
                            + "an unrelated element", archConn.getId());
                    continue;
                }

                // Crossing veto: reject if new path crosses more other-connection paths
                // than the old path did. Comparison is against the pre-modification state
                // of all other connections (first-order check — second-order interaction
                // between multiple modifications in the same call is accepted as bounded
                // drift).
                List<double[]> oldFullPath = pathById.get(archConn.getId());
                int oldCross = 0;
                int newCross = 0;
                for (Map.Entry<String, List<double[]>> entry : pathById.entrySet()) {
                    if (entry.getKey().equals(archConn.getId())) continue;
                    List<double[]> otherPath = entry.getValue();
                    if (oldFullPath != null) {
                        oldCross += LayoutQualityAssessor.countSegmentCrossings(oldFullPath, otherPath);
                    }
                    newCross += LayoutQualityAssessor.countSegmentCrossings(newFullPath, otherPath);
                }
                if (newCross > oldCross) {
                    vetoedCrossing++;
                    logger.debug("Crossing veto for connection {} — new path would add {} "
                            + "edge crossing(s) (old={}, new={})",
                            archConn.getId(), newCross - oldCross, oldCross, newCross);
                    continue;
                }
            }

            List<BendpointDto> newRel = ConnectionResponseBuilder.convertAbsoluteToRelative(
                    newAbs, srcCX, srcCY, tgtCX, tgtCY);
            PreparedMutation<ViewConnectionDto> prepared =
                    prepareUpdateViewConnection(null, newRel, null, null, null, null, archConn);
            commands.add(prepared.command());
            appliedPaths.put(archConn.getId(), newAbs);
            routed++;
        }

        if (vetoedObstacle > 0 || vetoedCrossing > 0 || vetoedInterior > 0 || vetoedZigzag > 0) {
            logger.info("Terminals-only skipped {} connection(s) by vetoes "
                    + "(obstacle={}, crossing={}, interior={}, zigzag={}) to preserve view quality",
                    vetoedObstacle + vetoedCrossing + vetoedInterior + vetoedZigzag,
                    vetoedObstacle, vetoedCrossing, vetoedInterior, vetoedZigzag);
        }

        // Only connections that survived every veto reach appliedPaths, so a vetoed connection
        // keeps its old geometry and is never disclosed on the strength of a route not written.
        AutoRouteWarnings.emitConnectionThroughNote(appliedPaths, allAssessment, nodes,
                arm, warnings, structuredWarnings);
        return new TerminalsOnlyResult(commands, routed,
                alreadyOrthogonal, vetoedObstacle, vetoedCrossing, vetoedInterior, vetoedZigzag);
    }

    /**
     * Obstacle veto helper — returns true if any segment of the given full path
     * (srcCenter → bendpoints → tgtCenter) intersects any obstacle rectangle. Uses
     * {@link RoutingPipeline#segmentIntersectsAnyObstacle} which performs proper
     * segment-vs-rectangle intersection (the same test used by the orthogonal router
     * and PathStraightener).
     */
    private static boolean pathCrossesObstacles(
            List<double[]> fullPath, List<RoutingRect> obstacles) {
        if (obstacles.isEmpty() || fullPath.size() < 2) return false;
        for (int i = 0; i < fullPath.size() - 1; i++) {
            double[] a = fullPath.get(i);
            double[] b = fullPath.get(i + 1);
            if (RoutingPipeline.segmentIntersectsAnyObstacle(
                    (int) a[0], (int) a[1], (int) b[0], (int) b[1], obstacles)) {
                return true;
            }
        }
        return false;
    }

    /**
     * best-of-K candidate objective — the SAME
     * aggregate the agent-in-loop ship-gate measures, NOT a per-metric proxy.
     * Builds
     * candidate {@link AssessmentConnection}s from the candidate's routed
     * bendpoints overlaid on the (unchanged) assessment {@code nodes} — no EMF
     * mutation; the live {@link LayoutQualityAssessor} is run read-only (it is
     * the selection oracle). Returns a single total order: the assessor
     * {@code overallRating} ordinal dominates (it already subsumes the Tier-1
     * severity tiering); a bounded sub-metric composite (HPQ&uarr; /
     * M4&darr; / coincSeg&darr;) is a deterministic tie-break WITHIN equal
     * rating that can never flip the rating order. Higher = better. Run on the
     * single model-layer composition point so the wrapper stays in
     * {@code model.routing} and assessor-agnostic.
     */
    private double scoreRoutingCandidate(RoutingResult candidate,
            List<AssessmentNode> nodes,
            List<RoutingPipeline.ConnectionEndpoints> batchInput) {
        // SCORER-1: score the SAME bendpoint representation the downstream
        // apply path commits and the fixture-test scorer measures — merge
        // violatedRoutes over routed (mirrors routesToApply at the apply site +
        // BestOfKRoutingStrategyV4FixtureTest). Symmetric across candidates so
        // never-worse is unaffected; this closes the selection-objective vs
        // ship-gate-aggregate fidelity gap.
        List<AssessmentConnection> candidateConnections =
                RoutedAssessmentPaths.overlay(batchInput, candidate);
        LayoutAssessmentResult r =
                layoutQualityAssessor.assess(nodes, candidateConnections, false);
        // Dominant key: the ship-gate aggregate rating (poor<fair<good<excellent).
        double ratingTerm = 1000.0 * LayoutQualityAssessor.ratingOrdinal(r.overallRating());
        // Bounded tie-break composite (|composite| < 3 ≪ 1000 ⇒ never flips rating).
        double hpqTerm = Math.max(0.0, Math.min(1.0, r.hubPortQualityScore()));
        double m4Term = Math.min(r.connectionEdgeCoincidenceCount(), 30) / 30.0;
        double coincTerm = Math.min(r.coincidentSegmentCount(), 30) / 30.0;
        return ratingTerm + hpqTerm - m4Term - coincTerm;
    }

    /**
     * Shared orthogonal routing logic: builds batch routing inputs, routes all connections and
     * converts the results to commands. {@code emitLabelOffsets} is true only for position-preserving
     * {@code autoRouteConnections} (the perpendicular "Label Offset" is the only channel that lifts a
     * Middle label off a box when elements are not moved); false for the auto-layout path, which keeps
     * offsets in {@code executeLabelFallback}.
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
            boolean force, int snapThreshold, int perimeterMargin,
            double occupancyWeight, boolean enableChannelNudging,
            boolean emitLabelOffsets, LabelPolicy labelPolicy) {

        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        // Build batch routing input with per-connection obstacle exclusion
        RoutingPipeline pipeline = new RoutingPipeline(
                RoutingPipeline.DEFAULT_BEND_PENALTY, RoutingPipeline.DEFAULT_MARGIN,
                RoutingPipeline.DEFAULT_CONGESTION_WEIGHT, perimeterMargin, occupancyWeight);
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

            // Build obstacle list: exclude source, target, both endpoints' ancestors and their
            // descendants at any depth, and all groups (transparent containers) (Pattern 2 fix)
            Set<String> excludeIds = new HashSet<>();
            excludeIds.add(srcNode.id());
            excludeIds.add(tgtNode.id());
            excludeIds.addAll(RoutingExcludeSets.ancestorIds(srcNode.id(), nodeMap));
            excludeIds.addAll(RoutingExcludeSets.ancestorIds(tgtNode.id(), nodeMap));
            excludeIds.addAll(RoutingExcludeSets.descendantIds(srcNode.id(), nodes));
            excludeIds.addAll(RoutingExcludeSets.descendantIds(tgtNode.id(), nodes));

            List<RoutingRect> obstacles = new ArrayList<>();
            // Per-connection container boundaries for wall-clearance cost, excluding containers
            // that are ancestors of either endpoint. Both kinds belong here: a transparent box is
            // a wall to keep clear of, never a rectangle to route around.
            List<RoutingRect> groupBoundaries = new ArrayList<>();
            for (AssessmentNode node : nodes) {
                if (excludeIds.contains(node.id())) {
                    continue;
                }
                // Notes are not routing obstacles — routing should use all
                // available space; note placement adjusts to avoid connections.
                if (node.isNote()) {
                    continue;
                }
                if (node.isContainer()) {
                    groupBoundaries.add(new RoutingRect(
                            (int) node.x(), (int) node.y(),
                            (int) node.width(), (int) node.height(),
                            node.id()));
                } else {
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
                    labelText, archConn.getTextPosition(), groupBoundaries));
            batchConnections.add(archConn);
        }

        if (batchInput.isEmpty()) {
            return new OrthogonalRoutingResult(
                    List.of(), 0, List.of(), List.of(), 0, Map.of(), 0, 0, List.of());
        }

        // Build unified obstacle list for corridor width and neighbor collision checks.
        // Exclude containers — a native group and an ArchiMate Grouping are both transparent.
        // NOTE THE ASYMMETRY WITH THE PER-CONNECTION LIST BUILT ABOVE, WHICH DROPS NOTES: this one
        // KEEPS them, and images and view references with them. Both are intended and both are
        // load-bearing. A note is empty air to the PATHFINDER, because a large note in the middle
        // of the corridors would over-constrain A* — but it is a solid rectangle to everything
        // measuring the space AROUND a settled path (edge nudging, coincident-segment offsets,
        // label clearance, hub-perimeter routing), because a label or a nudged segment landing on
        // a note is just as unreadable as one landing on an element. Do not "fix" either list to
        // match the other; the routes a caller can see are the product of both.
        List<RoutingRect> allObstacles = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (!node.isContainer()) {
                allObstacles.add(new RoutingRect(
                        (int) node.x(), (int) node.y(),
                        (int) node.width(), (int) node.height(),
                        node.id()));
            }
        }

        // Build per-connection label exclusion sets (source, target, ancestors, descendants)
        // for label position optimizer — matches LayoutQualityAssessor exclusion logic.
        Map<String, Set<String>> labelExcludeSets = new LinkedHashMap<>();
        Map<String, AssessmentNode> nodeMapForExclude = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMapForExclude.put(node.id(), node);
        }
        for (RoutingPipeline.ConnectionEndpoints conn : batchInput) {
            Set<String> excludeIds = new HashSet<>();
            if (conn.source().id() != null) {
                excludeIds.add(conn.source().id());
                excludeIds.addAll(RoutingExcludeSets.ancestorIds(conn.source().id(), nodeMapForExclude));
                excludeIds.addAll(RoutingExcludeSets.descendantIds(conn.source().id(), nodes));
            }
            if (conn.target().id() != null) {
                excludeIds.add(conn.target().id());
                excludeIds.addAll(RoutingExcludeSets.ancestorIds(conn.target().id(), nodeMapForExclude));
                excludeIds.addAll(RoutingExcludeSets.descendantIds(conn.target().id(), nodes));
            }
            labelExcludeSets.put(conn.connectionId(), excludeIds);
        }

        // Route all connections with path ordering and edge nudging.
        // best-of-K multi-start: the unchanged pipeline is invoked K
        // times over seeded-shuffled processing orderings; the best complete
        // result by the ship-gate aggregate is selected. Run 0 uses the null
        // override (≡ current main) and wins all ties ⇒ never-worse by
        // construction. The wrapper is assessor-agnostic; the genuine
        // LayoutQualityAssessor aggregate is wired here — the single model-layer
        // composition point (the spike's single-seam decision).
        BestOfKRoutingStrategy.RouteRunner bestOfKRunner = order ->
                pipeline.routeAllConnections(batchInput, allObstacles, labelExcludeSets,
                        snapThreshold, enableChannelNudging, order);
        BestOfKRoutingStrategy.CandidateScorer bestOfKScorer = candidate ->
                scoreRoutingCandidate(candidate, nodes, batchInput);
        RoutingResult routingResult =
                new BestOfKRoutingStrategy(bestOfKRunner, bestOfKScorer)
                        .selectBest(batchInput);

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
        List<String> hiddenLabelIds = new ArrayList<>();
        boolean hideUnplaceableLabels = labelPolicy != null && labelPolicy.hidesUnplaceableLabels();
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

            // Only a connection this pass actually routed can be hidden: the residual is a claim about
            // THIS geometry. A label already hidden by hand is left alone and never claimed as a policy
            // hide, so a caller's own visibility choice always outranks the policy.
            Boolean hideLabel = null;
            if (hideUnplaceableLabels
                    && routingResult.unresolvableLabels().contains(archConn.getId())
                    && archConn.isNameVisible()) {
                hideLabel = Boolean.FALSE;
                hiddenLabelIds.add(archConn.getId());
            }

            PreparedMutation<ViewConnectionDto> prepared = prepareUpdateViewConnection(
                    null, relativeBendpoints, null, null, hideLabel, null, archConn);
            commands.add(prepared.command());
            routedCount++;
        }

        // Apply label positions, plus (auto-route only) the perpendicular "Label Offset" for any Middle label
        // still ON a box at its chosen position — computed here from the routed paths; feature-guarded (no-op 5.7).
        Map<String, Integer> optimalPositions = routingResult.optimalPositions();
        Map<String, Integer> labelOffsets = emitLabelOffsets
                ? new LabelPositionOptimizer().computeOffsetsForPositions(
                        batchInput, routesToApply, allObstacles, labelExcludeSets, optimalPositions)
                : Map.of();
        if (!optimalPositions.isEmpty() || !labelOffsets.isEmpty()) {
            commands.addAll(LabelOffsetSupport.buildLabelCommands(
                    optimalPositions, labelOffsets, batchConnections));
        }

        return new OrthogonalRoutingResult(commands, routedCount,
                routingResult.failed(), routingResult.recommendations(),
                routingResult.labelsOptimized(), routesToApply,
                routingResult.straightLineCrossings(), routingResult.egressRolledBack(),
                hiddenLabelIds);
    }

    /**
     * Computes an optimize-group-order pass using forward sweep (standard order).
     * Delegates to the 4-param overload with {@code reverseSweep = false}.
     */
    OptimizeGroupOrderPassResult computeOptimizeGroupOrderPass(
            IArchimateDiagramModel diagramModel, IArchimateModel model,
            String direction) {
        return computeOptimizeGroupOrderPass(diagramModel, model, direction, false);
    }

    /**
     * Computes an optimize-group-order pass for a grouped view.
     * Returns result with compound command and position count,
     * or null if the view has no groups, no inter-group connections, or reordering
     * doesn't improve crossing count.
     *
     * <p>Reads the CURRENT EMF state (should be called after ELK dispatch so positions
     * reflect the latest layout). Uses "column" arrangement for vertical flow directions
     * (DOWN/UP) and "row" for horizontal (RIGHT/LEFT).</p>
     *
     * @param reverseSweep if true, processes groups in reversed order during the
     *                     barycentric phase to escape forward-sweep local minima
     */
    OptimizeGroupOrderPassResult computeOptimizeGroupOrderPass(
            IArchimateDiagramModel diagramModel, IArchimateModel model,
            String direction, boolean reverseSweep) {

        // 1. Collect top-level containers
        List<IDiagramModelObject> topLevelGroups = TopLevelGroupTargets.collect(diagramModel);
        if (topLevelGroups.isEmpty()) {
            return null; // Flat view — no groups to optimize
        }

        // 2. Build CrossingMinimizer inputs
        Map<String, IDiagramModelObject> groupMap = new LinkedHashMap<>();
        Map<String, String> elementToGroupId = new HashMap<>();
        List<CrossingMinimizer.GroupInfo> groupInfos = new ArrayList<>();

        for (IDiagramModelObject group : topLevelGroups) {
            String groupId = group.getId();
            groupMap.put(groupId, group);

            List<String> elementIds = new ArrayList<>();
            List<int[]> centers = new ArrayList<>();

            for (IDiagramModelObject child : TopLevelGroupTargets.childrenOf(group)) {
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
                minimizer.optimize(groupInfos, edges, reverseSweep);

        // 5. Check if improvement was found
        if (optResult.crossingsAfter() >= optResult.crossingsBefore()) {
            return null; // No improvement — discard
        }

        // 6. Build position commands for reordered elements
        int resolvedSpacing = DEFAULT_GROUP_SPACING;
        int resolvedPadding = DEFAULT_GROUP_PADDING;
        int startX = resolvedPadding;

        List<Command> commands = new ArrayList<>();
        int positionCount = 0; Map<String, Command> resizedChildren = new LinkedHashMap<>();

        for (CrossingMinimizer.GroupInfo groupInfo : groupInfos) {
            String groupId = groupInfo.groupId();
            IDiagramModelObject group = groupMap.get(groupId);
            List<String> newOrder = optResult.newOrderByGroup().get(groupId);
            boolean reordered = optResult.reorderedGroups().contains(groupId);

            if (!reordered || newOrder == null) continue;

            // Per container, not once for the pass: an element container reserves a taller title
            // band than a native group, and this loop now sees both kinds.
            int startY = resolvedPadding + NestedLayoutOperations.labelHeightFor(group);

            List<IDiagramModelObject> orderedChildren =
                    TopLevelGroupTargets.childrenInOrder(group, newOrder);

            // Compute new positions using arrangement heuristic
            String intraArrangement = GroupLayoutCalculator.chooseIntraGroupArrangement(
                    orderedChildren.size(), direction);
            List<int[]> positions;
            switch (intraArrangement) {
            case "row":
                positions = computeRowLayout(orderedChildren, startX, startY,
                        resolvedSpacing, null, null, true, null);
                break;
            case "grid":
                int gridCols = GroupLayoutCalculator.computeGridColumns(orderedChildren.size());
                GroupLayoutCalculator.GridLayoutResult gridResult =
                        computeGridLayout(orderedChildren, startX, startY,
                                resolvedSpacing, resolvedPadding, 0,
                                null, null, true, gridCols, null);
                positions = gridResult.positions();
                break;
            default: // "column"
                positions = computeColumnLayout(orderedChildren, startX, startY,
                        resolvedSpacing, null, null, true, null);
                break;
            }

            // Re-lays out with autoWidth on, so it re-sizes children too — through the same
            // collaborator, so those resizes are observed rather than invisible to the report.
            commands.addAll(NestedLayoutOperations.placeChildren(
                    orderedChildren, positions, resizedChildren, null));
            positionCount += orderedChildren.size();

            // Auto-resize group to fit. Deliberately NOT floored at the batch's queued rectangle:
            // this pass runs only inside auto-layout-and-route, whose grouped layout pass has
            // already chosen every group's size from its contents and its position from topology.
            // Flooring here would make one call honour a queued size for the groups it happened to
            // reorder and discard it for the rest — a worse contract than re-laying out uniformly.
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
        return new OptimizeGroupOrderPassResult(compound, positionCount, resizedChildren);
    }

    /**
     * Computes an auto-route pass for all connections on a view.
     * Returns result with compound command and routed count,
     * or null if no connections exist on the view.
     *
     * <p>Reads the CURRENT EMF state for element positions and routes all
     * connections using the shared orthogonal routing pipeline (force mode).</p>
     */
    AutoRoutePassResult computeAutoRoutePass(
            String viewId, IArchimateDiagramModel diagramModel,
            IArchimateModel model) {
        return computeAutoRoutePass(viewId, diagramModel, model,
                VisibilityGraphRouter.DEFAULT_OCCUPANCY_WEIGHT);
    }

    /** Overload carrying a label policy; every other caller keeps labels untouched. */
    AutoRoutePassResult computeAutoRoutePass(
            String viewId, IArchimateDiagramModel diagramModel,
            IArchimateModel model, LabelPolicy labelPolicy) {
        return computeAutoRoutePass(viewId, diagramModel, model,
                VisibilityGraphRouter.DEFAULT_OCCUPANCY_WEIGHT, labelPolicy);
    }

    /**
     * Overload with configurable occupancy weight for corridor diversity boost.
     */
    AutoRoutePassResult computeAutoRoutePass(
            String viewId, IArchimateDiagramModel diagramModel,
            IArchimateModel model, double occupancyWeight) {
        return computeAutoRoutePass(viewId, diagramModel, model, occupancyWeight, LabelPolicy.KEEP);
    }

    /** Canonical overload: occupancy weight plus the label policy. */
    AutoRoutePassResult computeAutoRoutePass(
            String viewId, IArchimateDiagramModel diagramModel,
            IArchimateModel model, double occupancyWeight, LabelPolicy labelPolicy) {

        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);
        List<IDiagramModelConnection> allConnections = AssessmentCollector.collectAllConnections(diagramModel);
        if (allConnections.isEmpty()) {
            return null;
        }

        // Route via shared helper (force=true for best quality during iteration)
        OrthogonalRoutingResult routeResult = buildOrthogonalRoutingCommands(
                diagramModel, allConnections, nodes, true, RoutingPipeline.DEFAULT_SNAP_THRESHOLD,
                RoutingPipeline.DEFAULT_PERIMETER_MARGIN, occupancyWeight,
                RoutingPipeline.DEFAULT_ENABLE_CHANNEL_NUDGING, false, labelPolicy);
        if (routeResult.commands.isEmpty()) {
            return null;
        }

        List<Command> commands = new ArrayList<>(routeResult.commands);

        // Switch to bendpoint mode if needed
        boolean routerTypeSwitched = false;
        int currentRouterType = diagramModel.getConnectionRouterType();
        if (currentRouterType != IDiagramModel.CONNECTION_ROUTER_BENDPOINT) {
            commands.add(new UpdateViewCommand(diagramModel,
                    null, null, false, null, null,
                    IDiagramModel.CONNECTION_ROUTER_BENDPOINT));
            routerTypeSwitched = true;
        }

        String label = "Auto-route connections (re-route after optimize-group-order, "
                + routeResult.routedCount + " connections)";
        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand(label);
        commands.forEach(compound::add);
        return new AutoRoutePassResult(compound, routeResult.routedCount,
                routeResult.labelsOptimized, routerTypeSwitched, routeResult.hiddenLabelIds);
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
                continue; // Notes are not laid out
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
                    edges.add(new LayoutEdge(child.getId(), targetObj.getId(), conn.getId(),
                            LabelWidthEstimator.estimateWidth(StylingHelper.resolveConnectionLabelText(conn))));
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                collectEdgesFromContainer(nested, nodeIds, seen, edges);
            }
        }
    }

    /**
     * Resolves the caller's label-policy value, rejecting an unrecognised one rather than silently
     * falling back to the default — a caller that misspells the opt-in must be told, not quietly
     * given a pass that does nothing.
     */
    private static LabelPolicy requireLabelPolicy(String labelPolicy) {
        LabelPolicy resolved = LabelPolicy.parse(labelPolicy);
        if (resolved == null) {
            throw new ModelAccessException(
                    "Invalid labelPolicy: '" + labelPolicy + "'", ErrorCode.INVALID_PARAMETER,
                    null, "Use one of: " + LabelPolicy.allowedValues(), null);
        }
        return resolved;
    }

    // ---- Auto-connect view ----

    @Override
    public MutationResult<AutoConnectResultDto> autoConnectView(
            String sessionId, String viewId,
            List<String> elementIds, List<String> relationshipTypes,
            List<String> relationshipIds,
            Boolean showLabel, StylingParams styling) {
        logger.info("Auto-connect view: viewId={}, elementIds={}, relationshipTypes={}",
                viewId, elementIds != null ? elementIds.size() : "all",
                relationshipTypes != null ? relationshipTypes.size() : "all");
        IArchimateModel model = requireAndCaptureModel();

        try {
            // Validate styling params early
            if (styling != null && styling.hasAnyValue()) {
                StylingHelper.validateConnectionStylingParams(styling);
            }
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

            Set<String> idFilter = RelationshipIdAllowList.validate(model, relationshipIds);

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
            // Ancestor/descendant pairs we decline to draw (self-pass-throughs);
            // threaded into every return-site DTO below.
            List<AutoConnectResultDto.SkippedNestingPair> skippedDueToNesting =
                    new ArrayList<>();

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
                    // skip orphaned relationships (not in containment tree)
                    if (rel.eContainer() == null) continue;

                    String relId = rel.getId();
                    if (processedRelationships.contains(relId)) continue;
                    processedRelationships.add(relId);

                    // Check type + relationship-ID allow-list filters (both AND-composed)
                    if (typeFilter != null
                            && !typeFilter.contains(rel.eClass().getName())) {
                        continue;
                    }
                    if (idFilter != null && !idFilter.contains(relId)) continue;

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

                    // skip ancestor/descendant pairs (self-pass-through; assess-layout M4)
                    if (AutoConnectSkip.isAncestorOnView(viewObject, targetViewObj)
                            || AutoConnectSkip.isAncestorOnView(targetViewObj, viewObject)) {
                        skippedDueToNesting.add(
                                new AutoConnectResultDto.SkippedNestingPair(
                                        viewObject.getId(), targetViewObj.getId(),
                                        rel.eClass().getName(),
                                        "ancestor_descendant_on_view"));
                        continue;
                    }

                    IDiagramModelArchimateConnection conn =
                            StylingHelper.newStyledConnection(rel, showLabel, styling);
                    commands.add(guardEndpoint(new AddConnectionToViewCommand(
                            conn, viewObject, targetViewObj), targetViewObj, model, rel.getName()));
                    connectedRelationshipIds.add(relId);
                }

                // Scan target relationships
                for (IArchimateRelationship rel : element.getTargetRelationships()) {
                    // skip orphaned relationships (not in containment tree)
                    if (rel.eContainer() == null) continue;

                    String relId = rel.getId();
                    if (processedRelationships.contains(relId)) continue;
                    processedRelationships.add(relId);

                    // Check type + relationship-ID allow-list filters (both AND-composed)
                    if (typeFilter != null
                            && !typeFilter.contains(rel.eClass().getName())) {
                        continue;
                    }
                    if (idFilter != null && !idFilter.contains(relId)) continue;

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

                    // skip ancestor/descendant pairs (self-pass-through; assess-layout M4).
                    // Here viewObject is the relationship TARGET and sourceViewObj the
                    // SOURCE — note the role swap when populating the skipped-pair record.
                    if (AutoConnectSkip.isAncestorOnView(viewObject, sourceViewObj)
                            || AutoConnectSkip.isAncestorOnView(sourceViewObj, viewObject)) {
                        skippedDueToNesting.add(
                                new AutoConnectResultDto.SkippedNestingPair(
                                        sourceViewObj.getId(), viewObject.getId(),
                                        rel.eClass().getName(),
                                        "ancestor_descendant_on_view"));
                        continue;
                    }

                    IDiagramModelArchimateConnection conn =
                            StylingHelper.newStyledConnection(rel, showLabel, styling);
                    commands.add(guardEndpoint(new AddConnectionToViewCommand(
                            conn, sourceViewObj, viewObject), sourceViewObj, model, rel.getName()));
                    connectedRelationshipIds.add(relId);
                }
            }

            // 7. Handle empty result
            if (commands.isEmpty()) {
                AutoConnectResultDto dto = new AutoConnectResultDto(
                        viewId, 0, skippedCount, List.of(),
                        List.copyOf(skippedDueToNesting));
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
                    connectedRelationshipIds,
                    List.copyOf(skippedDueToNesting));

            // 10. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("connectionsCreated", commands.size());
                proposedChanges.put("connectionsSkipped", skippedCount);
                ProposalBuilder.putIfPresent(proposedChanges, "skippedDueToNesting", skippedDueToNesting.isEmpty() ? null : List.copyOf(skippedDueToNesting));
                ProposalContext ctx = storeAsProposal(sessionId,
                        "auto-connect-view",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId), dto, label,
                        null, proposedChanges,
                        "Auto-connect computed and ready for application." + ProposalBuilder.REVIEWED_OR_REJECT);
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

    // ---- Adjust view spacing ----

    @Override
    public MutationResult<AdjustViewSpacingResultDto> adjustViewSpacing(
            String sessionId, String viewId,
            Integer interElementDelta, Integer paddingDelta,
            Integer interGroupDelta, boolean recursive) {
        logger.info("Adjust view spacing: viewId={}, interElementDelta={}, paddingDelta={}, "
                + "interGroupDelta={}, recursive={}",
                viewId, interElementDelta, paddingDelta, interGroupDelta, recursive);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // Delegate steps 1-11 (validate, density-aware default,
            // build+temp-dispatch+route+observe+overflow+undo+merge) to the
            // private helper computeAdjustViewSpacing(...). The helper returns
            // a result record with either a populated mergedCompound (mutation
            // ready to dispatch) or null (zero-delta short-circuit).
            //
            // Refactored 2026-05-15 to a shared pure core —
            // extraction of the common path makes
            // the same merged-compound-building logic reusable inside the
            // new SpacingControlLoop callbacks without duplicating ~200 LOC
            // of intricate routing/overflow orchestration. Existing public
            // contract preserved verbatim (single dispatchOrQueue at step 12,
            // same DTO shape).
            ComputeAdjustViewSpacingResult helper = computeAdjustViewSpacing(
                    sessionId, viewId, model, interElementDelta, paddingDelta,
                    interGroupDelta, recursive);

            if (helper.mergedCompound() == null) {
                // 3b. Zero-delta short-circuit: return DTO with no mutation.
                AssessLayoutResultDto assessment = helper.assessment();
                AdjustViewSpacingResultDto dto = new AdjustViewSpacingResultDto(
                        viewId, 0, 0, 0, 0,
                        assessment.edgeCrossingCount(),
                        assessment.edgeCrossingCount(),
                        assessment.overallRating(),
                        assessment.ratingBreakdown(),
                        assessment.coincidentSegmentCount(),
                        assessment.nonOrthogonalTerminalCount(),
                        assessment.averageSpacing(),
                        assessment.suggestions(),
                        helper.resolvedInterElementDelta(),
                        helper.defaultResolutionReason());
                return new MutationResult<>(dto, null);
            }

            // 12. Final dispatch as single undo step
            Integer batchSeq = dispatchOrQueue(sessionId, helper.mergedCompound(),
                    helper.mergedCompound().getLabel());
            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            // 13. Build result DTO from helper's per-iteration metadata +
            //     post-state assessment captured before step 10 undo.
            AssessLayoutResultDto assessment = helper.assessment();
            AdjustViewSpacingResultDto dto = new AdjustViewSpacingResultDto(
                    viewId,
                    helper.groupsAdjusted(),
                    helper.elementsRepositioned(),
                    helper.connectionsRouted(),
                    helper.connectionsFailed(),
                    helper.crossingsBefore(),
                    assessment.edgeCrossingCount(),
                    assessment.overallRating(),
                    assessment.ratingBreakdown(),
                    assessment.coincidentSegmentCount(),
                    assessment.nonOrthogonalTerminalCount(),
                    assessment.averageSpacing(),
                    assessment.suggestions(),
                    helper.resolvedInterElementDelta(),
                    helper.defaultResolutionReason(),
                    helper.resizedAncestors(), helper.resizedElements());

            return new MutationResult<>(dto, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error adjusting view spacing for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Result record returned by {@link #computeAdjustViewSpacing} — captures
     * the merged compound + intermediate metadata so callers can either
     * dispatch immediately (existing single-shot path) or wrap the compound
     * in a {@link SpacingMutationCommand} for the control loop's
     * speculative-execute-and-undo discipline.
     *
     * <p>{@code mergedCompound} is null when all resolved deltas are zero —
     * the helper short-circuited at step 3b. In that case the caller should
     * return a no-mutation DTO with the helper's pre-mutation
     * {@code assessment}.</p>
     *
     * <p>Refactored out of {@link #adjustViewSpacing} 2026-05-15 so the control
     * loop can reuse the orchestration instead of duplicating it.</p>
     */
    private record ComputeAdjustViewSpacingResult(
            NonNotifyingCompoundCommand mergedCompound,
            AssessLayoutResultDto assessment,
            int crossingsBefore,
            int groupsAdjusted,
            int elementsRepositioned,
            int connectionsRouted,
            int connectionsFailed,
            int resolvedInterElementDelta,
            String defaultResolutionReason,
            List<MovedViewObjectDto> resizedAncestors, List<MovedViewObjectDto> resizedElements) {
    }

    /**
     * Builds the {@link NonNotifyingCompoundCommand} representing one
     * spacing-and-route mutation as a function of (interElementDelta,
     * paddingDelta, interGroupDelta) WITHOUT dispatching it through the
     * public command stack. Performs steps 1-11 of the original
     * {@code adjustViewSpacing} flow: validate, density-aware default
     * resolution, build intra-group inflation commands, build inter-group
     * shift commands, temporarily dispatch + auto-route + overflow detect
     * + undo, merge into a single compound.
     *
     * <p>On return, the model is restored to its pre-call state (the
     * temporary dispatches at step 7 + 8 are undone at step 10). The
     * returned {@code mergedCompound} is ready to be executed by the
     * caller — either via {@code dispatchOrQueue(...)} (single-shot path)
     * OR via {@link SpacingMutationCommand#execute()} inside the control
     * loop.</p>
     *
     * <p>Refactored out of {@link #adjustViewSpacing} 2026-05-15 so the control
     * loop can reuse the orchestration instead of duplicating it.</p>
     */
    private ComputeAdjustViewSpacingResult computeAdjustViewSpacing(
            String sessionId, String viewId, IArchimateModel model,
            Integer interElementDelta, Integer paddingDelta,
            Integer interGroupDelta, boolean recursive)
            throws MutationException {

        // 1. Validate view
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
            throw new ModelAccessException(
                    "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
        }

        // 2. Flat-view guard: require populated containers of either kind
        List<IDiagramModelObject> topLevelGroups =
                TopLevelGroupTargets.collectPopulated(diagramModel);
        if (topLevelGroups.isEmpty()) {
            ModelAccessException allNested = TopLevelGroupTargets.containersAreAllNested(
                    viewId, diagramModel,
                    "adjust-view-spacing inflates corridors between the view's own containers.");
            if (allNested != null) throw allNested;
            throw new ModelAccessException(
                    "adjust-view-spacing requires a view with groups. "
                    + "This view has no groups with children.",
                    ErrorCode.INVALID_PARAMETER);
        }

        // 2b. Density-aware default resolution (Story
        //     RoutingPreconditions.InterElement.DensityAwareDefault).
        Integer resolvedInterElementDelta;
        String defaultResolutionReason;
        if (interElementDelta == null) {
            AssessLayoutResultDto triggerAssessment = assessLayout(viewId);
            PerGroupSpacingScan triggerScan =
                    scanPerGroupSpacing(topLevelGroups);
            DetectHubElementsResultDto triggerHubResult =
                    detectHubElements(viewId);
            boolean triggerHasLargeHubs =
                    HubSpacingSignal.hasLargeHubs(triggerHubResult);
            AdjustViewSpacingDefaultResolutionDecision decision =
                    AdjustViewSpacingDefaultResolutionDecision.decide(
                            /*callerProvidedDelta=*/ null,
                            triggerAssessment.coincidentSegmentCount(),
                            triggerAssessment.connectionEdgeCoincidenceCount(),
                            triggerAssessment.connectionCount(),
                            triggerScan.minSpacing(),
                            /*hasGroups=*/ true,
                            triggerScan.anyGroupHasMultipleChildren(),
                            triggerHasLargeHubs);
            resolvedInterElementDelta = decision.resolvedDelta();
            defaultResolutionReason = decision.reason();
        } else {
            resolvedInterElementDelta = interElementDelta;
            defaultResolutionReason = null;
        }

        // 3. Resolve deltas (null → 0)
        int elementDelta = resolvedInterElementDelta;
        int padDelta = (paddingDelta != null) ? paddingDelta : 0;
        int groupDelta = (interGroupDelta != null) ? interGroupDelta : 0;

        // 3b. Short-circuit when all deltas are zero. Return a
        //     result with null mergedCompound; caller builds no-mutation DTO.
        if (elementDelta == 0 && padDelta == 0 && groupDelta == 0) {
            AssessLayoutResultDto assessment = assessLayout(viewId);
            return new ComputeAdjustViewSpacingResult(
                    /*mergedCompound=*/ null,
                    assessment,
                    /*crossingsBefore=*/ assessment.edgeCrossingCount(),
                    /*groupsAdjusted=*/ 0,
                    /*elementsRepositioned=*/ 0,
                    /*connectionsRouted=*/ 0,
                    /*connectionsFailed=*/ 0,
                    resolvedInterElementDelta,
                    defaultResolutionReason,
                    /*resizedAncestors=*/ List.of(), /*resizedElements=*/ List.of());
        }

        // 3c. Capture crossings before inflation
        AssessLayoutResultDto beforeAssessment = assessLayout(viewId);
        int crossingsBefore = beforeAssessment.edgeCrossingCount();

        // 4. Build compound command for all spacing changes.
        //    The same-batch geometry is resolved ONCE here, above the passes that need it: every
        //    absolute rectangle this method writes — the per-group re-fit, its ancestor walk, the
        //    inter-group shift, and the step-9b overflow cascade — must measure against what the
        //    batch has already queued rather than the pre-batch getBounds().
        Map<String, int[]> sameBatchBounds = sameBatchBounds(sessionId);
        NonNotifyingCompoundCommand spacingCompound =
                new NonNotifyingCompoundCommand("Adjust view spacing");
        int elementsRepositioned = 0; int groupsAdjusted = 0;

        // 5. For each top-level group: detect arrangement, inflate spacing/padding
        for (IDiagramModelObject group : topLevelGroups) {
            int repositioned = inflateGroupSpacing(group, elementDelta, padDelta,
                    recursive, spacingCompound, 0, sameBatchBounds);
            if (repositioned > 0) {
                elementsRepositioned += repositioned;
                groupsAdjusted++;
            }
        }

        // 6. Inter-group shifts
        if (groupDelta != 0 && topLevelGroups.size() > 1) {
            List<Command> pendingCommands = NestedLayoutOperations.commandsOf(spacingCompound);

            // The shift is measured from where each group EFFECTIVELY is: a group an earlier
            // operation of this batch moved must be shifted from its queued position, not from the
            // pre-batch one it is about to leave. This arm is the one place a queued position is
            // legitimately overridden — inter-group spacing exists to move groups — but it must
            // still start the arithmetic from the truth.
            List<int[]> groupPositions = new ArrayList<>();
            for (IDiagramModelObject group : topLevelGroups) {
                int[] eff = AnchorResolver.effectiveRect(group, sameBatchBounds);
                int[] dims = AnchorResolver.effectiveDims(group,
                        NestedLayoutOperations.findPendingDimensions(pendingCommands, group),
                        sameBatchBounds);
                groupPositions.add(new int[]{eff[0], eff[1], dims[0], dims[1]});
            }

            List<int[]> shifted = GroupLayoutCalculator.computeInterGroupShifts(
                    groupPositions, groupDelta);

            for (int i = 0; i < topLevelGroups.size(); i++) {
                IDiagramModelObject group = topLevelGroups.get(i);
                int[] newPos = shifted.get(i);
                int[] before = groupPositions.get(i);
                if (newPos[0] != before[0] || newPos[1] != before[1]) {
                    spacingCompound.add(new UpdateViewObjectCommand(group,
                            newPos[0], newPos[1], before[2], before[3]));
                }
            }
        }

        // 7. Dispatch spacing temporarily so routing sees updated positions
        //
        // SAFETY (root-cause fix for
        // disposition B INTERNAL_ERROR + partial-commit regression observed
        // 2026-05-15): the temp-dispatch + assess + undo cycle MUST be
        // bracketed by try-finally so that ANY exception in steps 8 / 9 / 9b
        // (e.g., routing pipeline NPE on post-hub-resize state, assessor
        // failure on degenerate geometry, overflow-detect failure) is
        // recovered by guaranteed undo at step 10. Without this guard, an
        // exception in 8-9b leaks a partially-applied state: spacing AND
        // (optionally) route stay applied, modelVersion advances, and
        // subsequent control-loop iterations + retries observe stale
        // `currentSpacing` values that mis-classify the heuristic as
        // already-met. Pinned by `SpacingControlLoopPartialCommitRegressionTest`.
        mutationDispatcher.dispatchImmediate(spacingCompound);
        int undoCount = 1;

        AssessLayoutResultDto assessment;
        int connectionsRouted = 0;
        int connectionsFailed = 0;
        AutoRoutePassResult routeResult = null;
        Map<String, Command> groupResizeCommands = new LinkedHashMap<>();
        try {
            // 8. Compute routing pass (builds compound without dispatching).
            // Deliberately KEEP: this tool adjusts spacing and must not change label visibility.
            routeResult = computeAutoRoutePass(viewId, diagramModel, model, LabelPolicy.KEEP);
            if (routeResult != null) {
                mutationDispatcher.dispatchImmediate(routeResult.compound);
                undoCount++;
                connectionsRouted = routeResult.routedCount;
            }

            // 9. Assess layout (on the temporarily applied state)
            assessment = assessLayout(viewId);

            // 9b. Post-spacing/routing overflow-detection pass.
            // Seeded from the open batch for the same reason as the routing pass: an unseeded fit
            // map measures a shared group against its pre-batch size and emits a competing absolute
            // resize that discards what an earlier prepare of this batch queued for it. A fresh copy
            // per pass — the cascade mutates the map it is given.
            Map<String, int[]> virtualGroupBounds = AnchorResolver.seedPending(sameBatchBounds);
            Map<String, IDiagramModelObject> allViewObjects = new LinkedHashMap<>();
            collectAllViewObjectMap(diagramModel, allViewObjects);
            ParentFitCascade.fitAll(allViewObjects, null, DEFAULT_GROUP_PADDING,
                    virtualGroupBounds, groupResizeCommands, bulkPendingParents.get(), null);
        } finally {
            // 10. Undo temporary dispatches — guaranteed even on exception.
            mutationDispatcher.undo(undoCount);
        }

        // 11. Merge all commands into one compound for single undo
        NonNotifyingCompoundCommand mergedCompound =
                new NonNotifyingCompoundCommand("Adjust view spacing");
        NestedLayoutOperations.appendAll(mergedCompound, spacingCompound);
        if (routeResult != null) {
            NestedLayoutOperations.appendAll(mergedCompound, routeResult.compound);
        }
        for (Command cmd : groupResizeCommands.values()) {
            mergedCompound.add(cmd);
        }

        // The merge order above is load-bearing: the cascade's commands go LAST, so where both
        // fields name one object they agree, and the ancestor walk's earlier command for a group
        // is superseded by that group's own re-fit. resizedAncestors names a MECHANISM — the
        // cascade's COMMAND map, never the seeded bounds map, whose seeds include groups the batch
        // merely established. resizedElements names an OBSERVATION — the merged compound itself,
        // so it covers every rectangle this call writes rather than the ones a pass remembered.
        // Step 10's finally has undone both dispatches, so sizes measure the state the caller saw.
        return new ComputeAdjustViewSpacingResult(
                mergedCompound,
                assessment,
                crossingsBefore,
                groupsAdjusted,
                elementsRepositioned,
                connectionsRouted,
                connectionsFailed,
                resolvedInterElementDelta,
                defaultResolutionReason,
                AnchorResolver.projectMoves(groupResizeCommands, diagramModel),
                AnchorResolver.projectResizedAcrossIterations(mergedCompound, sameBatchBounds, diagramModel));
    }

    // ---- Apply element spacing recommendations (RoutingPreconditions.InterElement) ----

    /**
     * Default per-tool iteration budget for the element-spacing convenience
     * tool's embedded control loop (architecture-spec § 1.4).
     * Manual agents converged in 5-8 tool calls per 2026-05-15
     * trajectory data.
     */
    private static final int DEFAULT_ELEMENT_ITERATION_BUDGET = 5;

    /**
     * The control-loop callbacks every spacing arm drives: build one iteration's mutation from the
     * shared spacing helper, hand back the metrics that build already captured, and offer the
     * one-shot escalate hub-resize.
     *
     * <p>One body, three arms. The element and group tools each drive a single arm, and the
     * composer drives both in turn; {@code isElementArm} picks which delta parameter the helper
     * receives (the other is null), which was the only thing that ever differed between them
     * besides the words in the diagnostics. Three copies of this had to stay in step by hand,
     * including through their exception handling, which is the part that matters least until it
     * matters most.</p>
     *
     * <p>{@code lastBuilt} is the closure's own state: {@code observeLayout()} returns the metrics
     * the most recent build cached during the helper's temp-apply + assess + undo cycle, rather
     * than paying for another assessment pass per iteration.</p>
     *
     * <p><strong>Graceful degradation.</strong> An unexpected {@code RuntimeException} out of the
     * helper — a null dereference from the routing pipeline on a degenerate post-hub-resize state,
     * an assessor failure on degenerate geometry — must NOT propagate as an internal error. The
     * helper's own try-finally has already restored the model to its pre-iteration state, so
     * returning null terminates the loop as budget-exhausted and preserves every iteration accepted
     * before it: the caller gets a usable response instead of an error, and the cause is still
     * observable at WARN.</p>
     *
     * @param isComposerArm selects the composer's diagnostic wording, which names the arm and says
     *                      the ARM terminated; the standalone tools say the loop did
     * @param arm           the arm's name, as the loop reports it
     */
    private SpacingControlLoop.Callbacks spacingLoopCallbacks(
            String sessionId, String viewId, IArchimateModel model,
            boolean isElementArm, boolean isComposerArm, String arm) {
        final GefSpacingMutationCommand[] lastBuilt =
                new GefSpacingMutationCommand[1];
        return new SpacingControlLoop.Callbacks() {
            @Override
            public SpacingMutationCommand buildMutationCommand(
                    int proposedDeltaPx) {
                try {
                    ComputeAdjustViewSpacingResult helper =
                            computeAdjustViewSpacing(sessionId, viewId, model,
                                    /*interElementDelta=*/
                                    isElementArm ? proposedDeltaPx : null,
                                    /*paddingDelta=*/ null,
                                    /*interGroupDelta=*/
                                    isElementArm ? null : proposedDeltaPx,
                                    /*recursive=*/ true);
                    if (helper.mergedCompound() == null) {
                        // No-op delta (ladder reached zero-headroom).
                        lastBuilt[0] = null;
                        return null;
                    }
                    LayoutMetrics post = LayoutQualityScalar.toLayoutMetrics(helper.assessment());
                    lastBuilt[0] = new GefSpacingMutationCommand(
                            helper.mergedCompound(), post);
                    return lastBuilt[0];
                } catch (MutationException e) {
                    throw new RuntimeException(isComposerArm
                            ? "Composer arm " + arm + " failed during "
                              + "mutation-command construction"
                            : "Control-loop iteration failed during "
                              + "mutation-command construction", e);
                } catch (RuntimeException e) {
                    if (isComposerArm) {
                        logger.warn("Composer arm {} iteration failed during "
                                + "mutation-command construction (delta={}, "
                                + "viewId={}); terminating arm with budget-"
                                + "exhausted to preserve prior accepted iterations",
                                arm, proposedDeltaPx, viewId, e);
                    } else {
                        logger.warn("Control-loop {}-spacing iteration "
                                + "failed during mutation-command construction "
                                + "(delta={}, viewId={}); terminating loop "
                                + "with budget-exhausted to preserve prior "
                                + "accepted iterations",
                                arm, proposedDeltaPx, viewId, e);
                    }
                    lastBuilt[0] = null;
                    return null;
                }
            }

            @Override
            public LayoutMetrics observeLayout() {
                return lastBuilt[0].postMetrics();
            }

            @Override
            public SpacingMutationCommand buildHubResizeCommand() {
                // one-shot escalate hub-resize (Scoped Option B).
                return buildDensityHubResizeCommand(viewId, model);
            }
        };
    }

    @Override
    public MutationResult<ApplyElementSpacingRecommendationsResultDto>
            applyElementSpacingRecommendations(
                    String sessionId, String viewId,
                    boolean dryRun, Integer targetSpacingOverride) {
        // Backwards-compat: delegate to the 5-arg control-loop entry point
        // with the per-tool default iteration budget. Single source of truth
        // for the control-loop body — existing callers (4-arg) get the
        // redesigned behaviour automatically. Refactored 2026-05-15 as a
        // default interface method plus a canonical-impl override, so this
        // 4-arg signature stays source-compatible.
        return applyElementSpacingRecommendations(
                sessionId, viewId, dryRun, targetSpacingOverride,
                /*iterationBudget=*/ null);
    }

    @Override
    public MutationResult<ApplyElementSpacingRecommendationsResultDto>
            applyElementSpacingRecommendations(
                    String sessionId, String viewId,
                    boolean dryRun, Integer targetSpacingOverride,
                    Integer iterationBudget) {
        final int budget = resolveIterationBudget(
                iterationBudget, DEFAULT_ELEMENT_ITERATION_BUDGET);
        logger.info("Apply element spacing recommendations (control loop): "
                + "viewId={}, dryRun={}, targetSpacingOverride={}, "
                + "iterationBudget={}",
                viewId, dryRun, targetSpacingOverride, budget);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1-5. Entry-guard prelude — IDENTICAL to the prior single-shot
            //      path. The decision record continues to serve as the
            //      entry guard for the loop (per Task 0 SIBLING-REPLACE
            //      decision + architecture-spec § 1.7).
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            AssessLayoutResultDto before = assessLayout(viewId);
            int connectionCount = before.connectionCount();

            List<IDiagramModelObject> topLevelGroups =
                    TopLevelGroupTargets.collectPopulated(diagramModel);
            PerGroupSpacingScan scan = scanPerGroupSpacing(topLevelGroups);
            String notPositioned = TopLevelGroupTargets.elementStepCannotPosition(
                    viewId, diagramModel,
                    "This tool spaces the elements inside the view's own containers");

            DetectHubElementsResultDto hubResult = detectHubElements(viewId);
            boolean hasLargeHubs = HubSpacingSignal.hasLargeHubs(hubResult);

            int heuristicTarget = ElementSpacingHeuristic
                    .targetSpacingForConnectionCount(
                            connectionCount, hasLargeHubs);
            int targetSpacingPx = (targetSpacingOverride != null)
                    ? targetSpacingOverride : heuristicTarget;
            Integer heuristicRecommendation = (targetSpacingOverride != null)
                    ? heuristicTarget : null;

            ApplyElementSpacingDecision decision =
                    ApplyElementSpacingDecision.decide(
                            connectionCount, scan.minSpacing(),
                            targetSpacingPx, dryRun,
                            !topLevelGroups.isEmpty(),
                            scan.anyGroupHasMultipleChildren(),
                            targetSpacingOverride != null,
                            notPositioned);

            // 6. Short-circuit / dryRun branches. Build
            //    envelope with terminationReason taxonomy + return without
            //    entering the loop.
            if (!decision.shouldCallAdjustViewSpacing()) {
                String terminationReason = SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(
                        dryRun, decision.noChangeReason());
                ApplyElementSpacingRecommendationsResultDto dto =
                        new ApplyElementSpacingRecommendationsResultDto(
                                viewId, dryRun, connectionCount,
                                scan.minSpacing(), targetSpacingPx,
                                decision.interElementDelta(),
                                decision.noChangeReason(),
                                heuristicRecommendation, before,
                                /*after=*/ null, /*adjustResult=*/ null,
                                terminationReason,
                                /*iterationCount=*/ 0,
                                /*appliedDeltas=*/ List.of());
                return new MutationResult<>(dto, null);
            }

            // 7. Control-loop entry. The loop iterates with small-step
            //    deltas (30/40/50/60/70/...) up to the heuristic target
            //    via SpacingIterationDecision.decideNextStep, observing
            //    layout state between iterations and backing off on
            //    aggregate thresholdsMet regression.
            int initialSpacingPx = scan.minSpacing();
            int targetSpacing = initialSpacingPx + decision.interElementDelta();

            // Route-normalize the baseline so it is measured
            // on the same routing basis as every per-step postState.
            // The
            // guarded form: bare input returned untouched when the reroute
            // pass materially degraded it.
            RouteNormalizedBaseline rnb =
                    routeNormalizedBaseline(viewId, model, before);
            if (rnb.degraded()) {
                ApplyElementSpacingRecommendationsResultDto dto =
                        new ApplyElementSpacingRecommendationsResultDto(
                                viewId, dryRun, connectionCount,
                                scan.minSpacing(), targetSpacingPx,
                                decision.interElementDelta(),
                                decision.noChangeReason(),
                                heuristicRecommendation, before,
                                /*after=*/ null, /*adjustResult=*/ null,
                                SpacingControlLoop
                                        .REASON_REROUTE_DEGRADED_INPUT_BASELINE,
                                /*iterationCount=*/ 0,
                                /*appliedDeltas=*/ List.of());
                return new MutationResult<>(dto, null);
            }
            LayoutMetrics initialMetrics = rnb.metrics();

            // SOUND one-sided pre-routing infeasibility
            // certificate (the escalate lever).
            // Sibling-symmetric with the rnb.degraded() short-circuit
            // above: a pure pre-loop test ⇒ DTO-return WITHOUT entering the
            // loop. Zero false-positives by construction ⇒ cannot
            // reflow-claim-while-below-regime;
            // The loop's DECISION surface is untouched. Element-arm site of
            // the ONE arm-agnostic precheck (group + composer siblings).
            SpacingPreconditionInfeasibilityCertificate.Decision precert =
                    evaluateSpacingPrecondition(
                            model, viewId, initialMetrics);
            if (precert.shortCircuit()) {
                ApplyElementSpacingRecommendationsResultDto dto =
                        new ApplyElementSpacingRecommendationsResultDto(
                                viewId, dryRun, connectionCount,
                                scan.minSpacing(), targetSpacingPx,
                                decision.interElementDelta(),
                                decision.noChangeReason(),
                                heuristicRecommendation, before,
                                /*after=*/ null, /*adjustResult=*/ null,
                                precert.terminationReason(),
                                /*iterationCount=*/ 0,
                                /*appliedDeltas=*/ List.of(),
                                /*densityFloorDiagnosis=*/
                                precert.reflowOffer());
                return new MutationResult<>(dto, null);
            }

            SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                    initialSpacingPx, targetSpacing, budget,
                    /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                    initialMetrics, "element",
                    /*hubExtent=*/ captureHubExtent(viewId),
                    precert.viewpointType());

            SpacingControlLoop.Callbacks callbacks = spacingLoopCallbacks(
                    sessionId, viewId, model, /*isElementArm=*/ true,
                    /*isComposerArm=*/ false, /*arm=*/ "element");

            SpacingControlLoop.Result loopResult =
                    SpacingControlLoop.iterate(request, callbacks);

            // 8. Build outer compound from ACCEPTED iteration commands +
            //    push via dispatchOrQueue for ONE undo entry.
            //    Empty list → no mutation occurred (e.g., first-iteration
            //    regression). DTO surfaces terminationReason; null
            //    adjustResult.
            List<Integer> appliedDeltas = loopResult.iterations().stream()
                    .filter(s -> !s.backedOff())
                    .map(SpacingIterationStep::deltaApplied)
                    .toList();

            Integer batchSeq = null;
            AssessLayoutResultDto after = before;
            AdjustViewSpacingResultDto adjustResult = null;
            int finalElementDelta = 0;

            if (!loopResult.acceptedCommands().isEmpty()) {
                NonNotifyingCompoundCommand outerCompound =
                        new NonNotifyingCompoundCommand(
                                "Apply element spacing recommendations "
                                + "(control loop, " + appliedDeltas.size()
                                + " accepted iterations)");
                for (SpacingMutationCommand cmd
                        : loopResult.acceptedCommands()) {
                    GefSpacingMutationCommand gef =
                            (GefSpacingMutationCommand) cmd;
                    outerCompound.add(gef.gefCommand());
                }
                List<MovedViewObjectDto> resized =
                        AnchorResolver.projectResizedAcrossIterations(outerCompound,
                                sameBatchBounds(sessionId), diagramModel);
                batchSeq = dispatchOrQueue(sessionId, outerCompound,
                        outerCompound.getLabel());
                if (batchSeq == null) {
                    versionCounter.incrementAndGet();
                }
                after = assessLayout(viewId);
                finalElementDelta = loopResult.finalSpacingPx()
                        - initialSpacingPx;
                adjustResult = synthesizeAdjustResultForControlLoop(
                        viewId, finalElementDelta, before, after,
                        appliedDeltas.size(), resized);
            }

            ApplyElementSpacingRecommendationsResultDto dto =
                    new ApplyElementSpacingRecommendationsResultDto(
                            viewId, /*dryRun=*/ false, connectionCount,
                            initialSpacingPx, targetSpacingPx,
                            finalElementDelta,
                            /*noChangeReason=*/ null,
                            heuristicRecommendation, before, after,
                            adjustResult,
                            loopResult.terminationReason(),
                            appliedDeltas.size(),
                            appliedDeltas,
                            loopResult.densityDiagnosis(),
                            SpacingQualityDisclosure.warningsForDispatch(
                                    SpacingQualityDisclosure.ELEMENT_TOOL, batchSeq, before, after));
            return new MutationResult<>(dto, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof MutationException me) {
                throw me;
            }
            throw new ModelAccessException(
                    "Error applying element spacing recommendations for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error applying element spacing recommendations for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Synthesizes a representative {@link AdjustViewSpacingResultDto} for the
     * control-loop happy path. The single-shot {@code adjustViewSpacing}
     * primitive populates this DTO directly; for the control-loop variant
     * we synthesize the aggregate from {@code before}/{@code after}
     * snapshots since the loop ran N inner compounds rather than ONE call
     * to {@code adjustViewSpacing}. Preserves DTO field-shape compatibility
     * for downstream LLM agents that read {@code adjustResult} from the
     * envelope.
     *
     * <p>{@code resizedAncestors} is deliberately empty here. A projection of the dispatched
     * compound reports what each object LANDS at; across several iterations it cannot say which
     * mechanism put it there, and an object cascade-grown in one iteration and inflation-resized in
     * the next belongs to both. The invariant asks what changed and to what, which one list answers
     * completely — so the whole report goes into {@code resizedElements} rather than being split on
     * an attribution the loop does not preserve.</p>
     *
     * @param resizedElements every object the dispatched compound lands at a different size,
     *                        already projected from that compound rather than accumulated from the
     *                        iterations that proposed it
     */
    private AdjustViewSpacingResultDto synthesizeAdjustResultForControlLoop(
            String viewId, int finalDelta,
            AssessLayoutResultDto before, AssessLayoutResultDto after,
            int iterationCount, List<MovedViewObjectDto> resizedElements) {
        return new AdjustViewSpacingResultDto(
                viewId,
                /*groupsAdjusted=*/ 0,
                /*elementsRepositioned=*/ 0,
                /*connectionsRouted=*/ 0,
                /*connectionsFailed=*/ 0,
                before.edgeCrossingCount(),
                after.edgeCrossingCount(),
                after.overallRating(),
                after.ratingBreakdown(),
                after.coincidentSegmentCount(),
                after.nonOrthogonalTerminalCount(),
                after.averageSpacing(),
                after.suggestions(),
                /*resolvedInterElementDelta=*/ finalDelta,
                /*defaultResolutionReason=*/ "control_loop_synthesized_after_"
                        + iterationCount + "_iterations",
                /*resizedAncestors=*/ List.of(), resizedElements);
    }

    // ---- Apply group spacing recommendations (RoutingPreconditions.InterGroup) ----

    /**
     * Default per-tool iteration budget for the group-spacing convenience
     * tool's embedded control loop (architecture-spec § 1.4).
     * Sibling-symmetric with the element-spacing default.
     */
    private static final int DEFAULT_GROUP_ITERATION_BUDGET = 5;

    @Override
    public MutationResult<ApplyGroupSpacingRecommendationsResultDto>
            applyGroupSpacingRecommendations(
                    String sessionId, String viewId,
                    boolean dryRun, Integer targetSpacingOverride) {
        return applyGroupSpacingRecommendations(
                sessionId, viewId, dryRun, targetSpacingOverride,
                /*iterationBudget=*/ null);
    }

    @Override
    public MutationResult<ApplyGroupSpacingRecommendationsResultDto>
            applyGroupSpacingRecommendations(
                    String sessionId, String viewId,
                    boolean dryRun, Integer targetSpacingOverride,
                    Integer iterationBudget) {
        final int budget = resolveIterationBudget(
                iterationBudget, DEFAULT_GROUP_ITERATION_BUDGET);
        logger.info("Apply group spacing recommendations (control loop): "
                + "viewId={}, dryRun={}, targetSpacingOverride={}, "
                + "iterationBudget={}",
                viewId, dryRun, targetSpacingOverride, budget);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1-5. Entry-guard prelude — IDENTICAL to the prior single-shot
            //      path. Sibling-symmetric with applyElementSpacingRecommendations.
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            AssessLayoutResultDto before = assessLayout(viewId);
            int connectionCount = before.connectionCount();

            // Gates and counters alike read the OUTERMOST containers, so the count of
            // connections crossing between them and the test for whether two of them exist
            // cannot answer about different sets. Reading the view's direct children here while
            // the count walked the containment chain is what let one response say that
            // connections crossed a boundary the same response said did not exist. Narrowed to
            // ONE coordinate frame, and to the SAME threshold the refusal reads, before any
            // geometry is read — see positioningFrame.
            List<IDiagramModelObject> topLevelGroups = TopLevelGroupTargets.groupStepFrame(
                    diagramModel, TopLevelGroupTargets.collectOutermost(diagramModel));
            String notPositioned = TopLevelGroupTargets.groupStepCannotPosition(
                    viewId, diagramModel,
                    "This tool inflates corridors between the view's own containers");
            int interGroupConnectionCount = TopLevelGroupTargets
                    .countInterGroupConnections(diagramModel);
            boolean isConnected = interGroupConnectionCount > 0;
            int currentSpacingPx =
                    TopLevelGroupTargets.sharedFrameSpacing(topLevelGroups);

            DetectHubElementsResultDto hubResult = detectHubElements(viewId);
            boolean hasLargeHubs = HubSpacingSignal.hasLargeHubs(hubResult);

            int heuristicTarget = GroupSpacingHeuristic
                    .targetSpacingForConnectionCount(connectionCount,
                            isConnected, hasLargeHubs);
            int targetSpacingPx = (targetSpacingOverride != null)
                    ? targetSpacingOverride : heuristicTarget;
            Integer heuristicRecommendation = (targetSpacingOverride != null)
                    ? heuristicTarget : null;

            ApplyGroupSpacingDecision decision =
                    ApplyGroupSpacingDecision.decide(
                            connectionCount, interGroupConnectionCount,
                            currentSpacingPx, targetSpacingPx, dryRun,
                            topLevelGroups.size() >= 2,
                            isConnected,
                            targetSpacingOverride != null,
                            notPositioned);

            // 6. Short-circuit / dryRun branches.
            if (!decision.shouldCallAdjustViewSpacing()) {
                String terminationReason = SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(
                        dryRun, decision.noChangeReason());
                ApplyGroupSpacingRecommendationsResultDto dto =
                        new ApplyGroupSpacingRecommendationsResultDto(
                                viewId, dryRun, connectionCount,
                                interGroupConnectionCount, isConnected,
                                currentSpacingPx, targetSpacingPx,
                                decision.interGroupDelta(),
                                decision.noChangeReason(),
                                heuristicRecommendation, before,
                                /*after=*/ null, /*adjustResult=*/ null,
                                terminationReason,
                                /*iterationCount=*/ 0,
                                /*appliedDeltas=*/ List.of());
                return new MutationResult<>(dto, null);
            }

            // 7. Control-loop entry. Sibling-symmetric with element-spacing
            //    accessor — only difference: closure passes interGroupDelta
            //    (not interElementDelta) to computeAdjustViewSpacing.
            int initialSpacingPx = currentSpacingPx;
            int targetSpacing = initialSpacingPx + decision.interGroupDelta();

            // Route-normalized baseline, sibling-symmetric with the
            // element-spacing closure.
            RouteNormalizedBaseline rnb =
                    routeNormalizedBaseline(viewId, model, before);
            if (rnb.degraded()) {
                ApplyGroupSpacingRecommendationsResultDto dto =
                        new ApplyGroupSpacingRecommendationsResultDto(
                                viewId, dryRun, connectionCount,
                                interGroupConnectionCount, isConnected,
                                currentSpacingPx, targetSpacingPx,
                                decision.interGroupDelta(),
                                decision.noChangeReason(),
                                heuristicRecommendation, before,
                                /*after=*/ null, /*adjustResult=*/ null,
                                SpacingControlLoop
                                        .REASON_REROUTE_DEGRADED_INPUT_BASELINE,
                                /*iterationCount=*/ 0,
                                /*appliedDeltas=*/ List.of());
                return new MutationResult<>(dto, null);
            }
            LayoutMetrics initialMetrics = rnb.metrics();

            // Group-arm site of the ONE shared arm-agnostic
            // SOUND pre-routing infeasibility precheck (element + composer
            // siblings). Sibling-symmetric with rnb.degraded() above; the
            // loop's DECISION surface untouched; dissolved by soundness.
            SpacingPreconditionInfeasibilityCertificate.Decision precert =
                    evaluateSpacingPrecondition(
                            model, viewId, initialMetrics);
            if (precert.shortCircuit()) {
                ApplyGroupSpacingRecommendationsResultDto dto =
                        new ApplyGroupSpacingRecommendationsResultDto(
                                viewId, dryRun, connectionCount,
                                interGroupConnectionCount, isConnected,
                                currentSpacingPx, targetSpacingPx,
                                decision.interGroupDelta(),
                                decision.noChangeReason(),
                                heuristicRecommendation, before,
                                /*after=*/ null, /*adjustResult=*/ null,
                                precert.terminationReason(),
                                /*iterationCount=*/ 0,
                                /*appliedDeltas=*/ List.of(),
                                /*densityFloorDiagnosis=*/
                                precert.reflowOffer());
                return new MutationResult<>(dto, null);
            }

            SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                    initialSpacingPx, targetSpacing, budget,
                    /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                    initialMetrics, "group",
                    /*hubExtent=*/ captureHubExtent(viewId),
                    precert.viewpointType());

            SpacingControlLoop.Callbacks callbacks = spacingLoopCallbacks(
                    sessionId, viewId, model, /*isElementArm=*/ false,
                    /*isComposerArm=*/ false, /*arm=*/ "group");

            SpacingControlLoop.Result loopResult =
                    SpacingControlLoop.iterate(request, callbacks);

            // 8. Build outer compound from ACCEPTED iterations.
            List<Integer> appliedDeltas = loopResult.iterations().stream()
                    .filter(s -> !s.backedOff())
                    .map(SpacingIterationStep::deltaApplied)
                    .toList();

            Integer batchSeq = null;
            AssessLayoutResultDto after = before;
            AdjustViewSpacingResultDto adjustResult = null;
            int finalGroupDelta = 0;

            if (!loopResult.acceptedCommands().isEmpty()) {
                NonNotifyingCompoundCommand outerCompound =
                        new NonNotifyingCompoundCommand(
                                "Apply group spacing recommendations "
                                + "(control loop, " + appliedDeltas.size()
                                + " accepted iterations)");
                for (SpacingMutationCommand cmd
                        : loopResult.acceptedCommands()) {
                    GefSpacingMutationCommand gef =
                            (GefSpacingMutationCommand) cmd;
                    outerCompound.add(gef.gefCommand());
                }
                List<MovedViewObjectDto> resized =
                        AnchorResolver.projectResizedAcrossIterations(outerCompound,
                                sameBatchBounds(sessionId), diagramModel);
                batchSeq = dispatchOrQueue(sessionId, outerCompound,
                        outerCompound.getLabel());
                if (batchSeq == null) {
                    versionCounter.incrementAndGet();
                }
                after = assessLayout(viewId);
                finalGroupDelta = loopResult.finalSpacingPx()
                        - initialSpacingPx;
                adjustResult = synthesizeAdjustResultForControlLoop(
                        viewId, finalGroupDelta, before, after,
                        appliedDeltas.size(), resized);
            }

            ApplyGroupSpacingRecommendationsResultDto dto =
                    new ApplyGroupSpacingRecommendationsResultDto(
                            viewId, /*dryRun=*/ false, connectionCount,
                            interGroupConnectionCount, isConnected,
                            initialSpacingPx, targetSpacingPx,
                            finalGroupDelta,
                            /*noChangeReason=*/ null,
                            heuristicRecommendation, before, after,
                            adjustResult,
                            loopResult.terminationReason(),
                            appliedDeltas.size(),
                            appliedDeltas,
                            loopResult.densityDiagnosis(),
                            SpacingQualityDisclosure.warningsForDispatch(
                                    SpacingQualityDisclosure.GROUP_TOOL, batchSeq, before, after));
            return new MutationResult<>(dto, batchSeq);

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof MutationException me) {
                throw me;
            }
            throw new ModelAccessException(
                    "Error applying group spacing recommendations for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error applying group spacing recommendations for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- Apply spacing recommendations (composed; RoutingPreconditions.Composed) ----

    /**
     * Default per-tool iteration budget for the apply-spacing-recommendations
     * composer's embedded control loops (architecture-spec § 1.4 /
     * § 1.4). Composer iterates BOTH element + group arms, so the
     * default is 8 (4+4 redistribution per arch-spec § 1.7 Option A).
     */
    private static final int DEFAULT_COMPOSER_ITERATION_BUDGET = 8;

    @Override
    public MutationResult<ApplySpacingRecommendationsResultDto>
            applySpacingRecommendations(
                    String sessionId, String viewId,
                    String scope, boolean dryRun,
                    Integer elementTargetSpacingOverride,
                    Integer groupTargetSpacingOverride) {
        return applySpacingRecommendations(
                sessionId, viewId, scope, dryRun,
                elementTargetSpacingOverride, groupTargetSpacingOverride,
                /*iterationBudget=*/ null);
    }

    @Override
    public MutationResult<ApplySpacingRecommendationsResultDto>
            applySpacingRecommendations(
                    String sessionId, String viewId,
                    String scope, boolean dryRun,
                    Integer elementTargetSpacingOverride,
                    Integer groupTargetSpacingOverride,
                    Integer iterationBudget) {
        final int budget = resolveIterationBudget(
                iterationBudget, DEFAULT_COMPOSER_ITERATION_BUDGET);
        // Element-arm budget = floor(budget/2); group-arm budget = ceil(budget/2)
        // per architecture-spec § 1.7 Option A (default 4+4 for budget=8).
        final int elementBudget = budget / 2;
        final int groupBudget = budget - elementBudget;
        String resolvedScope = scope;
        // Build-identity —
        // verify-patch-loaded sentinel (a 0/N empirical
        // with "zero improvement" is otherwise indistinguishable from
        // "plugin not reloaded"; grep this line in the live Archi log
        // post-restart before trusting any reproduction result). NOT a
        // patch — instrumentation only; no behaviour change.
        logger.info("density-fixes instrumentation loaded (build-identity sentinel) — "
                + "applySpacingRecommendations entry: viewId={}, scope={}, "
                + "dryRun={}, elementTargetOverride={}, groupTargetOverride={}, "
                + "iterationBudget={} (element={}, group={})",
                viewId, resolvedScope, dryRun, elementTargetSpacingOverride,
                groupTargetSpacingOverride, budget, elementBudget, groupBudget);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1-5. Entry-guard prelude — IDENTICAL to the prior single-shot
            //      composer path.
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            AssessLayoutResultDto before = assessLayout(viewId);
            int connectionCount = before.connectionCount();

            // Both arms gate on the OUTERMOST containers, for the same reason the group-spacing
            // tool does: the inter-group count below walks the containment chain, and an arm that
            // called the view flat while that count was non-zero was contradicting its own
            // envelope rather than describing the view.
            // The arms need DIFFERENT numbers of the view's own containers before they have work,
            // and each arm's frame and refusal must read that arm's own — see positioningFrame.
            List<IDiagramModelObject> nonEmptyTopLevelGroups =
                    TopLevelGroupTargets.elementStepFrame(diagramModel,
                            TopLevelGroupTargets.collectOutermostPopulated(diagramModel));
            List<IDiagramModelObject> allTopLevelGroups = TopLevelGroupTargets.groupStepFrame(
                    diagramModel, TopLevelGroupTargets.collectOutermost(diagramModel));
            String elementNotPositioned = TopLevelGroupTargets.elementStepCannotPosition(
                    viewId, diagramModel,
                    "This arm spaces the elements inside the view's own containers");
            String groupNotPositioned = TopLevelGroupTargets.groupStepCannotPosition(
                    viewId, diagramModel,
                    "This arm inflates corridors between the view's own containers");
            PerGroupSpacingScan elementScan =
                    scanPerGroupSpacing(nonEmptyTopLevelGroups);
            int interGroupConnectionCount = TopLevelGroupTargets
                    .countInterGroupConnections(diagramModel);
            boolean isConnected = interGroupConnectionCount > 0;
            int currentGroupSpacingPx =
                    TopLevelGroupTargets.sharedFrameSpacing(allTopLevelGroups);

            DetectHubElementsResultDto hubResult = detectHubElements(viewId);
            boolean hasLargeHubs = HubSpacingSignal.hasLargeHubs(hubResult);

            int elementHeuristicTarget = ElementSpacingHeuristic
                    .targetSpacingForConnectionCount(
                            connectionCount, hasLargeHubs);
            int groupHeuristicTarget = GroupSpacingHeuristic
                    .targetSpacingForConnectionCount(
                            connectionCount, isConnected, hasLargeHubs);
            int elementTargetSpacingPx =
                    (elementTargetSpacingOverride != null)
                            ? elementTargetSpacingOverride
                            : elementHeuristicTarget;
            int groupTargetSpacingPx =
                    (groupTargetSpacingOverride != null)
                            ? groupTargetSpacingOverride
                            : groupHeuristicTarget;

            ApplySpacingDecision decision = ApplySpacingDecision.decide(
                    resolvedScope, connectionCount, interGroupConnectionCount,
                    elementScan.minSpacing(), currentGroupSpacingPx,
                    elementTargetSpacingPx, groupTargetSpacingPx, dryRun,
                    !nonEmptyTopLevelGroups.isEmpty(),
                    elementScan.anyGroupHasMultipleChildren(),
                    allTopLevelGroups.size() >= 2,
                    isConnected,
                    elementTargetSpacingOverride != null,
                    groupTargetSpacingOverride != null,
                    elementNotPositioned, groupNotPositioned);

            // 6. Short-circuit / dryRun branches. Composer-level
            //    short-circuit surfaces per-arm null fields (both arms idle).
            if (!decision.shouldCallAdjustViewSpacing()) {
                String terminationReason = SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(
                        dryRun, decision.noChangeReason());
                ApplySpacingRecommendationsResultDto dto =
                        new ApplySpacingRecommendationsResultDto(
                                viewId, resolvedScope, dryRun,
                                connectionCount, interGroupConnectionCount,
                                isConnected, hasLargeHubs,
                                elementScan.minSpacing(),
                                currentGroupSpacingPx,
                                elementTargetSpacingPx, groupTargetSpacingPx,
                                decision.proposedElementDelta(),
                                decision.proposedGroupDelta(),
                                decision.interElementDelta(),
                                decision.interGroupDelta(),
                                decision.elementKneeClampApplied(),
                                decision.groupKneeClampApplied(),
                                decision.noChangeReason(),
                                elementTargetSpacingOverride,
                                groupTargetSpacingOverride,
                                before,
                                /*after=*/ null, /*adjustResult=*/ null,
                                /*elementTerminationReason=*/ terminationReason,
                                /*elementIterationCount=*/ 0,
                                /*elementAppliedDeltas=*/ List.of(),
                                /*groupTerminationReason=*/ terminationReason,
                                /*groupIterationCount=*/ 0,
                                /*groupAppliedDeltas=*/ List.of());
                return new MutationResult<>(dto, null);
            }

            // 7. CONTROL LOOPS — Option A per architecture-spec § 1.7:
            //    element-arm loop first, then group-arm loop. Per-arm
            //    iteration step caps are re-purposed from the existing
            //    composer knee-clamp constants
            //    (ELEMENT_KNEE_LIMIT_PX = 80, GROUP_KNEE_LIMIT_PX = 100)
            //    per the composer's step-cap option.
            ComposerArmResult elementArm = ComposerArmResult.idle();
            ComposerArmResult groupArm = ComposerArmResult.idle();

            // Element arm — fires only when delta > 0 (scope-included AND
            // current spacing < target after clamp).
            if (decision.interElementDelta() > 0) {
                elementArm = runComposerArm(
                        sessionId, viewId, model,
                        elementScan.minSpacing(),
                        elementScan.minSpacing() + decision.interElementDelta(),
                        elementBudget,
                        ApplySpacingDecision.ELEMENT_KNEE_LIMIT_PX,
                        before,
                        /*arm=*/ "composer.element",
                        /*isElementArm=*/ true);
            }

            // Group arm — fires only when delta > 0; observes the state AFTER
            // the element arm's accepted iterations are applied (so the group-
            // arm initial metrics reflect the element arm's effect).
            if (decision.interGroupDelta() > 0) {
                // Speculatively apply the element arm's accepted commands to
                // capture the group arm's initial state. Story
                // Threading: this
                // replay MUST be SWT-marshalled — the accepted commands are
                // raw NonNotifyingCompoundCommands whose execute() fires
                // firePropertyChange → TreeModelView.doRefreshFromNotifications
                // → Display.getCurrent().asyncExec(); off the reactor worker
                // thread Display.getCurrent() is null → NPE → INTERNAL_ERROR
                // + partial-commit (captured stack trace 2026-05-17; the one
                // composer path the SwtUiThreadDispatcher
                // marshalling did not cover). An extension, not a
                // re-architecture.
                ComposerSpeculativeReplay.replayForward(
                        elementArm.acceptedCommands);
                AssessLayoutResultDto groupArmInitial = assessLayout(viewId);
                try {
                    groupArm = runComposerArm(
                            sessionId, viewId, model,
                            currentGroupSpacingPx,
                            currentGroupSpacingPx + decision.interGroupDelta(),
                            groupBudget,
                            ApplySpacingDecision.GROUP_KNEE_LIMIT_PX,
                            groupArmInitial,
                            /*arm=*/ "composer.group",
                            /*isElementArm=*/ false);
                } finally {
                    // Reset the model: undo the element arm's commands in
                    // reverse so the composer can dispatch a single combined
                    // compound for ONE undo entry. Threading: same
                    // SWT-marshalling as the forward replay above (the
                    // finally-undo has the identical off-UI-thread
                    // NonNotifyingCompoundCommand.undo() → firePropertyChange
                    // → TreeModelView NPE exposure).
                    ComposerSpeculativeReplay.undoReverse(
                            elementArm.acceptedCommands);
                }
            }

            // 8. Build outer compound — both arms' accepted commands.
            List<Integer> elementDeltas = elementArm.appliedDeltas;
            List<Integer> groupDeltas = groupArm.appliedDeltas;
            int totalAcceptedCount = elementArm.acceptedCommands.size()
                    + groupArm.acceptedCommands.size();

            Integer batchSeq = null;
            AssessLayoutResultDto after = before;
            AdjustViewSpacingResultDto adjustResult = null;
            int finalElementDelta = 0;
            int finalGroupDelta = 0;

            if (totalAcceptedCount > 0) {
                NonNotifyingCompoundCommand outerCompound =
                        new NonNotifyingCompoundCommand(
                                "Apply spacing recommendations (composer "
                                + "control loop, " + elementDeltas.size()
                                + " element + " + groupDeltas.size()
                                + " group accepted iterations)");
                for (Command c : elementArm.acceptedCommands) {
                    outerCompound.add(c);
                }
                for (Command c : groupArm.acceptedCommands) {
                    outerCompound.add(c);
                }
                List<MovedViewObjectDto> resized =
                        AnchorResolver.projectResizedAcrossIterations(outerCompound,
                                sameBatchBounds(sessionId), diagramModel);
                batchSeq = dispatchOrQueue(sessionId, outerCompound,
                        outerCompound.getLabel());
                if (batchSeq == null) {
                    versionCounter.incrementAndGet();
                }
                after = assessLayout(viewId);
                finalElementDelta = elementArm.finalSpacingPx
                        - elementScan.minSpacing();
                finalGroupDelta = groupArm.finalSpacingPx
                        - currentGroupSpacingPx;
                if (finalElementDelta < 0) finalElementDelta = 0;
                if (finalGroupDelta < 0) finalGroupDelta = 0;
                adjustResult = synthesizeAdjustResultForControlLoop(
                        viewId, finalElementDelta + finalGroupDelta,
                        before, after,
                        elementDeltas.size() + groupDeltas.size(), resized);
            }

            ApplySpacingRecommendationsResultDto dto =
                    new ApplySpacingRecommendationsResultDto(
                            viewId, resolvedScope, /*dryRun=*/ false,
                            connectionCount, interGroupConnectionCount,
                            isConnected, hasLargeHubs,
                            elementScan.minSpacing(),
                            currentGroupSpacingPx,
                            elementTargetSpacingPx, groupTargetSpacingPx,
                            decision.proposedElementDelta(),
                            decision.proposedGroupDelta(),
                            finalElementDelta,
                            finalGroupDelta,
                            decision.elementKneeClampApplied(),
                            decision.groupKneeClampApplied(),
                            /*noChangeReason=*/ null,
                            elementTargetSpacingOverride,
                            groupTargetSpacingOverride,
                            before, after,
                            adjustResult,
                            elementArm.terminationReason,
                            elementDeltas.size(),
                            elementDeltas,
                            groupArm.terminationReason,
                            groupDeltas.size(),
                            groupDeltas,
                            elementArm.densityDiagnosis,
                            groupArm.densityDiagnosis,
                            SpacingQualityDisclosure.warningsForDispatch(
                                    SpacingQualityDisclosure.COMPOSED_TOOL,
                                    batchSeq, before, after));
            return new MutationResult<>(dto, batchSeq);

        } catch (IllegalArgumentException e) {
            throw new ModelAccessException(
                    "Invalid argument for apply-spacing-recommendations: "
                    + e.getMessage(),
                    e, ErrorCode.INVALID_PARAMETER);
        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof MutationException me) {
                throw me;
            }
            // Throw-site capture —
            // capture the ACTUAL throw-site stack trace BEFORE wrapping to
            // INTERNAL_ERROR. This is the envelope-catch that produced the
            // "INFO-only / zero error-severity archi.mcp" preliminary log
            // datapoint signature: the composer two-arm
            // orchestration (element→group hand-off speculative execute /
            // assessLayout / dispatchOrQueue / DTO build, ~L8249–8375) and
            // the new escalate+one-shot-hub-resize path surface here with no
            // error line. Logging the throwable here is the
            // stack-trace-before-patch gate (instrumentation, NOT a fix —
            // the same ModelAccessException is still thrown unchanged).
            logger.error("apply-spacing-recommendations composer two-arm "
                    + "boundary threw (throw-site capture): viewId={}, scope={} — "
                    + "actual throw site follows",
                    (viewId != null ? viewId : "<null>"), resolvedScope, e);
            throw new ModelAccessException(
                    "Error applying spacing recommendations for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        } catch (Exception e) {
            logger.error("apply-spacing-recommendations composer two-arm "
                    + "boundary threw (throw-site capture, checked): viewId={}, "
                    + "scope={} — actual throw site follows",
                    (viewId != null ? viewId : "<null>"), resolvedScope, e);
            throw new ModelAccessException(
                    "Error applying spacing recommendations for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Per-arm result captured by the composer's two-arm control loop
     * (architecture-spec § 1.7 Option A). Holds the accepted GEF commands
     * (extracted from the loop's {@link GefSpacingMutationCommand} wrappers
     * via downcast), the per-iteration applied deltas, the final cumulative
     * spacing, and the {@code terminationReason} string the loop returned.
     */
    private static final class ComposerArmResult {
        final List<Command> acceptedCommands;
        final List<Integer> appliedDeltas;
        final String terminationReason;
        final int finalSpacingPx;
        /** Per-arm actionable PASS-honest reflow diagnosis; null otherwise. */
        final String densityDiagnosis;

        ComposerArmResult(List<Command> acceptedCommands,
                List<Integer> appliedDeltas,
                String terminationReason,
                int finalSpacingPx,
                String densityDiagnosis) {
            this.acceptedCommands = acceptedCommands;
            this.appliedDeltas = appliedDeltas;
            this.terminationReason = terminationReason;
            this.finalSpacingPx = finalSpacingPx;
            this.densityDiagnosis = densityDiagnosis;
        }

        static ComposerArmResult idle() {
            return new ComposerArmResult(List.of(), List.of(),
                    /*terminationReason=*/ null, /*finalSpacingPx=*/ 0,
                    /*densityDiagnosis=*/ null);
        }

        /**
         * Guarded-form result — the arm's reroute pass
         * materially degraded the input baseline; the arm contributes no
         * commands and surfaces
         * {@link SpacingControlLoop#REASON_REROUTE_DEGRADED_INPUT_BASELINE}
         * in its per-arm {@code terminationReason}.
         */
        static ComposerArmResult rerouteDegraded() {
            return new ComposerArmResult(List.of(), List.of(),
                    SpacingControlLoop.REASON_REROUTE_DEGRADED_INPUT_BASELINE,
                    /*finalSpacingPx=*/ 0, /*densityDiagnosis=*/ null);
        }

        /**
         * The SOUND one-sided pre-routing certificate proved
         * this arm's input geometry provably-infeasible BEFORE the loop;
         * the arm contributes NO commands and surfaces the NEW precondition
         * termination reason + the actionable, consent-gated reflow OFFER
         * (carried in the EXISTING per-arm {@code densityDiagnosis}
         * surface). Sibling-symmetric with {@link #rerouteDegraded()};
         * {@link SpacingControlLoop} is never entered here, so none of its
         * behaviour applies. With both arms short-circuited, the composer builds the
         * final DTO with {@code totalAcceptedCount=0} ⇒ {@code after==before}
         * ⇒ the view is preserved unchanged (no degraded layout).
         */
        static ComposerArmResult preconditionInfeasible(
                String terminationReason, String reflowOffer) {
            return new ComposerArmResult(List.of(), List.of(),
                    terminationReason, /*finalSpacingPx=*/ 0,
                    /*densityDiagnosis=*/ reflowOffer);
        }
    }

    /**
     * Runs ONE arm of the composer's two-arm control loop (architecture-
     * spec § 1.7 Option A). The {@code isElementArm} flag picks which
     * delta parameter to pass to {@link #computeAdjustViewSpacing} (the
     * other arm's delta is null).
     *
     * <p>On return, the model is at the pre-arm state (the loop's
     * {@code finalizeWithReset} has undone all accepted commands).
     * Returns the accepted GEF commands so the composer can splice them
     * into a single outer compound dispatched ONCE for single-undo.
     */
    private ComposerArmResult runComposerArm(
            String sessionId, String viewId, IArchimateModel model,
            int initialSpacingPx, int targetSpacingPx,
            int iterationBudget, int perIterationStepCapPx,
            AssessLayoutResultDto initialAssessment,
            String arm, boolean isElementArm) {

        // Route-normalize this arm's baseline so it shares the per-step
        // routing basis, exactly as the two standalone entry points do
        // before they build their own request; the guarded form surfaces a
        // per-arm degraded reason.
        RouteNormalizedBaseline rnb =
                routeNormalizedBaseline(viewId, model, initialAssessment);
        if (rnb.degraded()) {
            return ComposerArmResult.rerouteDegraded();
        }

        // Composer-per-arm site of the ONE shared arm-agnostic SOUND
        // pre-routing infeasibility precheck (element + group standalone
        // siblings). runComposerArm is invoked once per arm (element +
        // group) ⇒ both composer arms see the SAME per-view geometry ⇒ the
        // SAME decision ⇒ both short-circuit identically; the composer then
        // builds the final DTO from the per-arm reason + OFFER with
        // totalAcceptedCount=0 (after==before — view preserved unchanged,
        // NO degraded layout). Sibling-symmetric with rnb.degraded() above;
        // the loop's DECISION surface untouched; dissolved by soundness.
        SpacingPreconditionInfeasibilityCertificate.Decision precert =
                evaluateSpacingPrecondition(model, viewId, rnb.metrics());
        if (precert.shortCircuit()) {
            return ComposerArmResult.preconditionInfeasible(
                    precert.terminationReason(), precert.reflowOffer());
        }

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                initialSpacingPx, targetSpacingPx, iterationBudget,
                perIterationStepCapPx,
                rnb.metrics(), arm,
                /*hubExtent=*/ captureHubExtent(viewId),
                precert.viewpointType());

        SpacingControlLoop.Callbacks callbacks = spacingLoopCallbacks(
                sessionId, viewId, model, isElementArm,
                /*isComposerArm=*/ true, arm);

        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, callbacks);

        List<Command> acceptedCommands = result.acceptedCommands().stream()
                .map(c -> ((GefSpacingMutationCommand) c).gefCommand())
                .toList();
        List<Integer> appliedDeltas = result.iterations().stream()
                .filter(s -> !s.backedOff())
                .map(SpacingIterationStep::deltaApplied)
                .toList();
        return new ComposerArmResult(acceptedCommands, appliedDeltas,
                result.terminationReason(), result.finalSpacingPx(),
                result.densityDiagnosis());
    }


    /**
     * Result of a per-group spacing scan. {@code minSpacing} is the minimum
     * detected spacing across all groups that had at least 2 non-note
     * children (most-tight wins, aligns with visual-severity hierarchy).
     * {@code anyGroupHasMultipleChildren} is true when at least one group
     * had at least 2 non-note children — without that, "current spacing"
     * is undefined and the convenience tool short-circuits with a
     * "no groups with 2+ elements" reason instead of falsely computing a
     * delta against a 0-pseudo-spacing.
     *
     * <p>Returning these as a single record (instead of a sentinel-zero
     * spacing) addresses Sonnet 4.6 cross-model code review action item
     * [MEDIUM] 2026-05-04.</p>
     */
    private record PerGroupSpacingScan(
            int minSpacing,
            boolean anyGroupHasMultipleChildren) {}

    /**
     * Scans the given top-level groups and computes the minimum per-group
     * element spacing across those that have at least 2 non-note children,
     * reusing {@link GroupLayoutCalculator#detectSpacingFromPositions}
     * (single source of truth — same utility {@code adjustViewSpacing} uses
     * inside {@code inflateGroupSpacing}).
     *
     * <p>Returns {@link PerGroupSpacingScan#anyGroupHasMultipleChildren()}
     * = false when no group qualifies; in that case the
     * {@link PerGroupSpacingScan#minSpacing()} is 0 but should NOT be used
     * to compute a delta — the convenience tool short-circuits explicitly
     * via {@link ApplyElementSpacingDecision}.</p>
     */
    private PerGroupSpacingScan scanPerGroupSpacing(
            List<IDiagramModelObject> topLevelGroups) {
        int min = Integer.MAX_VALUE;
        boolean anyMultiChild = false;
        for (IDiagramModelObject group : topLevelGroups) {
            List<int[]> childPositions = new ArrayList<>();
            for (IDiagramModelObject child : TopLevelGroupTargets.childrenOf(group)) {
                if (child instanceof IDiagramModelNote) continue;
                IBounds b = child.getBounds();
                childPositions.add(new int[]{
                        b.getX(), b.getY(), b.getWidth(), b.getHeight()});
            }
            if (childPositions.size() < 2) continue;
            anyMultiChild = true;
            ArrangementDetector.DetectedArrangement detected =
                    ArrangementDetector.detect(childPositions);
            int spacing = GroupLayoutCalculator.detectSpacingFromPositions(
                    childPositions, detected.type());
            if (spacing < min) min = spacing;
        }
        int resolvedMin = (min == Integer.MAX_VALUE) ? 0 : min;
        return new PerGroupSpacingScan(resolvedMin, anyMultiChild);
    }

    /**
     * Inflates spacing and padding for a single group and optionally recurses
     * into nested subgroups (bottom-up). Returns count of elements repositioned. Nothing is
     * threaded back out for reporting: the caller projects the compound this fills, which covers
     * all three write ranges here — child placement, the container's re-fit, the ancestor walk.
     */
    private int inflateGroupSpacing(IDiagramModelObject group,
            int elementDelta, int padDelta, boolean recursive,
            NonNotifyingCompoundCommand compound, int depth, Map<String, int[]> sameBatchBounds) {
        if (depth > MAX_RECURSIVE_RESIZE_DEPTH) return 0;

        // Collect direct children (skip notes)
        List<IDiagramModelObject> children = new ArrayList<>();
        List<IDiagramModelObject> nestedGroups = new ArrayList<>();
        for (IDiagramModelObject child : TopLevelGroupTargets.childrenOf(group)) {
            if (child instanceof IDiagramModelNote) continue;
            children.add(child);
            if (TopLevelGroupTargets.isTarget(child)
                    && !TopLevelGroupTargets.childrenOf(child).isEmpty()) {
                nestedGroups.add(child);
            }
        }
        if (children.isEmpty()) return 0;

        int repositioned = 0;

        // Recurse into nested subgroups first (bottom-up) so parent resize
        // reflects children's inflated bounds
        if (recursive) {
            for (IDiagramModelObject nested : nestedGroups) {
                repositioned += inflateGroupSpacing(nested, elementDelta, padDelta,
                        true, compound, depth + 1, sameBatchBounds);
            }
        }

        // Detect current arrangement from child positions.
        // After recursion, nested groups may have been resized in the compound,
        // so use pending dimensions instead of stale getBounds().
        List<Command> pendingCmds = NestedLayoutOperations.commandsOf(compound);
        List<int[]> childPositions = new ArrayList<>();
        List<int[]> childSizes = new ArrayList<>();
        for (IDiagramModelObject child : children) {
            IBounds b = child.getBounds();
            int[] dims = AnchorResolver.effectiveDims(child,
                    NestedLayoutOperations.findPendingDimensions(pendingCmds, child), sameBatchBounds);
            childSizes.add(dims);
            childPositions.add(new int[]{b.getX(), b.getY(), dims[0], dims[1]});
        }
        ArrangementDetector.DetectedArrangement detected =
                ArrangementDetector.detect(childPositions);

        // Detect current spacing and padding
        int currentSpacing = GroupLayoutCalculator.detectSpacingFromPositions(
                childPositions, detected.type());
        int[] groupRect = AnchorResolver.effectiveRect(group, sameBatchBounds);
        int currentPadding = GroupLayoutCalculator.detectPaddingFromPositions(
                childPositions, groupRect[2], groupRect[3]);

        // Compute inflated values
        int newSpacing = Math.max(0, currentSpacing + elementDelta);
        int newPadding = Math.max(0, currentPadding + padDelta);
        int startX = newPadding;
        // An element container reserves a taller title band than a native group, so the band is
        // asked for per container rather than assumed — otherwise a Grouping's first row would be
        // laid out underneath its own label.
        int labelBand = NestedLayoutOperations.labelHeightFor(group);
        int startY = newPadding + labelBand;

        // Re-compute positions with inflated spacing, preserving each child's EFFECTIVE dimensions.
        // The sizes list is passed straight to the calculator rather than resolved from live bounds
        // again: a child the batch re-sized, or a nested group this pass has already re-fitted, must
        // be laid out — and enclosed — at the size it will actually have.
        List<int[]> newPositions;
        switch (detected.type()) {
        case "row":
            newPositions = GroupLayoutCalculator.computeRowLayout(childSizes, startX, startY,
                    newSpacing);
            break;
        case "grid":
            Integer gridCols = detected.gridColumns();
            if (gridCols == null) {
                gridCols = GroupLayoutCalculator.computeGridColumns(children.size());
            }
            newPositions = GroupLayoutCalculator.computeGridLayout(childSizes, startX, startY,
                    newSpacing, newPadding, groupRect[2], gridCols).positions();
            break;
        default: // "column"
            newPositions = GroupLayoutCalculator.computeColumnLayout(childSizes, startX, startY,
                    newSpacing);
            break;
        }

        // A full rectangle goes to every child, so a child can land at a size nobody asked for: the
        // grid arm's uniform cell width, or a nested container this pass has just re-fitted.
        NestedLayoutOperations.placeChildren(children, newPositions, sameBatchBounds).forEach(compound::add);
        repositioned += children.size();

        // Auto-resize group to fit inflated children, never below the rectangle the batch queued
        int[] rect = AnchorResolver.refitRect(group,
                computeAutoResizeDimensions(newPositions, newPadding, labelBand),
                sameBatchBounds);
        compound.add(new UpdateViewObjectCommand(group, rect[0], rect[1], rect[2], rect[3]));

        // Resize ancestor groups if this is a nested group. Seed with the
        // compound's existing commands so resizeAncestorGroups can see
        // the current group's resize via findPendingDimensions.
        //
        // Native groups only. The upward walk is the case the codebase deliberately scoped away
        // from ArchiMate-element containers, so a Grouping does not grow to fit contents that grew
        // inside it. That gap is visible rather than silent: the overflow shows up as a boundary
        // violation in assess-layout, and resize-elements-to-fit closes it on request.
        if (depth > 0 && group instanceof IDiagramModelGroup nestedNativeGroup) {
            List<Command> allCommands = NestedLayoutOperations.commandsOf(compound);
            int beforeSize = allCommands.size();
            NestedLayoutOperations.resizeAncestorGroups(nestedNativeGroup, allCommands, newPadding,
                    null, sameBatchBounds);
            // Add only the newly appended ancestor commands to compound
            for (int i = beforeSize; i < allCommands.size(); i++) {
                compound.add(allCommands.get(i));
            }
        }

        return repositioned;
    }

    // ---- Layout within group ----

    /** Default spacing between elements in pixels (increased from 20 for routing corridors). */
    private static final int DEFAULT_GROUP_SPACING = 40;
    /** Default padding from group edges in pixels. Package-visible so containment pins measure against it, not a copy. */
    static final int DEFAULT_GROUP_PADDING = 10;
    /** Approximate height of the group label bar in Archi's rendering. */
    private static final int GROUP_LABEL_HEIGHT = 24;
    // Auto-width constants delegated to GroupLayoutCalculator
    static final int AVG_CHAR_WIDTH = GroupLayoutCalculator.AVG_CHAR_WIDTH;
    static final int HORIZONTAL_PADDING = GroupLayoutCalculator.HORIZONTAL_PADDING;
    static final int MIN_AUTO_WIDTH = GroupLayoutCalculator.MIN_AUTO_WIDTH;
    static final int DEFAULT_ELEMENT_WIDTH = GroupLayoutCalculator.DEFAULT_ELEMENT_WIDTH;

    /** Maximum recursion depth for recursive auto-resize. */
    private static final int MAX_RECURSIVE_RESIZE_DEPTH = 10;

    @Override
    public MutationResult<LayoutWithinGroupResultDto> layoutWithinGroup(
            String sessionId, String viewId, String groupViewObjectId,
            String arrangement, Integer spacing, Integer padding,
            Integer elementWidth, Integer elementHeight, boolean autoResize,
            boolean autoWidth, Integer columns, boolean recursive,
            boolean recursiveChildren) {
        logger.info("Layout within group: viewId={}, groupViewObjectId={}, arrangement={}, columns={}, recursive={}, recursiveChildren={}",
                viewId, groupViewObjectId, arrangement, columns, recursive, recursiveChildren);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1. Validate view exists
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel layoutView)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 2. Validate container exists and is a layout-eligible container (polymorphic extension).
            // Accepts native visual groups (IDiagramModelGroup) AND ArchiMate-element view-objects
            // (IDiagramModelArchimateObject) that have nested children. Notes implement
            // IDiagramModelContainer in EMF but are excluded per project-context.md coordinate model.
            // Two locals: 'container' for getChildren() (IDiagramModelContainer) and 'containerObject'
            // for getBounds() / UpdateViewObjectCommand (IDiagramModelObject) — both runtime subtypes
            // implement both interfaces as siblings (neither extends the other in Archi's EMF model).
            EObject groupObj = ArchimateModelUtils.getObjectByID(model, groupViewObjectId);
            IDiagramModelContainer container;
            IDiagramModelObject containerObject;
            if (groupObj instanceof IDiagramModelGroup group) {
                container = group;
                containerObject = group;
            } else if (groupObj instanceof IDiagramModelArchimateObject elementContainer) {
                container = elementContainer;
                containerObject = elementContainer;
            } else {
                throw new ModelAccessException(
                        "Container not found or not a layout-eligible container: " + groupViewObjectId
                                + ". Eligible containers: visual groups (IDiagramModelGroup) and "
                                + "ArchiMate-element view-objects (IDiagramModelArchimateObject — "
                                + "Components, Nodes, Functions, etc. that have nested children).",
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Use get-view-contents to find valid container view-object IDs in the 'groups' "
                                + "list or in 'visualMetadata' (any element with non-empty children). "
                                + "Notes, connections, and view-references cannot be containers.",
                        null);
            }

            // 2b. Verify container belongs to the specified view
            EObject ancestor = container.eContainer();
            while (ancestor != null && !(ancestor instanceof IArchimateDiagramModel)) {
                ancestor = ancestor.eContainer();
            }
            if (ancestor == null || !viewId.equals(((IArchimateDiagramModel) ancestor).getId())) {
                throw new ModelAccessException(
                        "Container " + groupViewObjectId + " does not belong to view " + viewId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Ensure the groupViewObjectId is from the specified view's groups list or visualMetadata.",
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
            validateGroupLayoutParams(spacing, padding, columns);
            int resolvedSpacing = (spacing != null) ? spacing : DEFAULT_GROUP_SPACING;
            int resolvedPadding = (padding != null) ? padding : DEFAULT_GROUP_PADDING;
            logger.debug("Layout params: spacing={}, padding={}, elementWidth={}, "
                    + "elementHeight={}, autoResize={}, autoWidth={}, columns={}, recursive={}, recursiveChildren={}",
                    resolvedSpacing, resolvedPadding, elementWidth, elementHeight,
                    autoResize, autoWidth, columns, recursive, recursiveChildren);

            // 5. Collect direct children (skip notes)
            List<IDiagramModelObject> children = new ArrayList<>();
            for (IDiagramModelObject child : container.getChildren()) {
                if (child instanceof IDiagramModelNote) {
                    continue; // Notes are not laid out
                }
                children.add(child);
            }

            if (children.isEmpty()) {
                throw new ModelAccessException(
                        "Container has no children to layout",
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

            // 7. Compute the layout commands.
            // Coordinates are RELATIVE to each container's origin (0,0 at top-left).
            // elementWidth takes precedence over autoWidth (explicit override wins).
            boolean effectiveAutoWidth = autoWidth && (elementWidth == null);

            List<Command> commands = new ArrayList<>();
            int elementsRepositioned;
            Integer newGroupWidth = null;
            Integer newGroupHeight = null;
            boolean overflow = false;
            Map<String, Command> ancestorResizes = new LinkedHashMap<>(), nestedFits = new LinkedHashMap<>(), resizedLeaves = new LinkedHashMap<>();
            Integer columnsUsed = null; // only set for grid arrangement
            int nestedContainersArranged = 0;
            int maxDepthReached = 0;

            Map<String, int[]> pendingBounds = sameBatchBounds(sessionId);
            if (recursiveChildren) {
                // Recursive DESCENDANT layout: arrange the whole nesting hierarchy
                // bottom-up (innermost containers arranged and sized first, then each
                // level up). Inner containers always resize to fit; the root honours
                // autoResize. Orthogonal to 'recursive', which resizes ANCESTORS upward.
                NestedLayoutOperations.NestedLayoutResult r =
                        NestedLayoutOperations.buildRecursiveLayoutCommands(
                                container, containerObject, normalizedArrangement,
                                resolvedSpacing, resolvedPadding,
                                elementWidth, elementHeight, effectiveAutoWidth,
                                columns, autoResize, pendingBounds);
                commands.addAll(r.commands());
                elementsRepositioned = r.elementsRepositioned();
                nestedContainersArranged = r.nestedContainersArranged();
                maxDepthReached = r.maxDepthReached();
                nestedFits.putAll(r.fittedContainers()); resizedLeaves.putAll(r.resizedLeaves());
                if ("grid".equals(normalizedArrangement)) {
                    columnsUsed = (columns != null)
                            ? Math.min(columns, children.size())
                            : GroupLayoutCalculator.computeGridColumns(children.size());
                }
                if (autoResize) {
                    newGroupWidth = r.rootFittedWidth();
                    newGroupHeight = r.rootFittedHeight();
                } else {
                    int[] cur = AnchorResolver.effectiveRect(containerObject, pendingBounds);
                    overflow = r.rootFittedWidth() > cur[2] || r.rootFittedHeight() > cur[3];
                }
                if (r.depthCapHit()) {
                    logger.debug("Recursive layout hit depth cap {} — deeper containers left in place",
                            NestedLayoutOperations.MAX_RECURSIVE_LAYOUT_DEPTH);
                }
            } else {
                // Single-level layout: only the direct children of this container.
                int startX = resolvedPadding;
                int startY = resolvedPadding + GROUP_LABEL_HEIGHT;
                List<int[]> positions = new ArrayList<>(); // [x, y, w, h] per child

                switch (normalizedArrangement) {
                case "row":
                    positions = computeRowLayout(children, startX, startY,
                            resolvedSpacing, elementWidth, elementHeight,
                            effectiveAutoWidth, pendingBounds);
                    break;
                case "column":
                    positions = computeColumnLayout(children, startX, startY,
                            resolvedSpacing, elementWidth, elementHeight,
                            effectiveAutoWidth, pendingBounds);
                    break;
                case "grid":
                    // The column count is derived from this width when the caller named none, so a
                    // pre-batch read here picks the shape of the whole grid from a rectangle the
                    // container is about to stop having.
                    int groupWidth = AnchorResolver.effectiveRect(containerObject, pendingBounds)[2];
                    GroupLayoutCalculator.GridLayoutResult gridResult = computeGridLayout(children, startX, startY,
                            resolvedSpacing, resolvedPadding, groupWidth,
                            elementWidth, elementHeight, effectiveAutoWidth, columns, pendingBounds);
                    positions = gridResult.positions();
                    columnsUsed = gridResult.columnsUsed();
                    break;
                }

                // 8. Build commands, recording each child the placement re-sizes. A grid cell here
                //    takes the width of the widest element anywhere in the grid, so a narrow leaf
                //    beside a wide sibling is stretched to it — with no recursion involved at all.
                commands.addAll(NestedLayoutOperations.placeChildren(
                        children, positions, resizedLeaves, pendingBounds));
                elementsRepositioned = children.size();

                // 10. Auto-resize group if requested, detect overflow otherwise.
                //     Both arms measure the container at its same-batch rectangle: an absolute
                //     re-fit built from the pre-batch read discards a size an earlier operation of
                //     this batch queued, and an overflow verdict taken against it describes a
                //     container the model is about to stop having.
                int[] currentRect = AnchorResolver.effectiveRect(containerObject, pendingBounds);
                int[] requiredDims = computeAutoResizeDimensions(
                        positions, resolvedPadding, GROUP_LABEL_HEIGHT);
                if (autoResize) {
                    int[] rect = AnchorResolver.refitRect(containerObject, requiredDims, pendingBounds);
                    newGroupWidth = rect[2];
                    newGroupHeight = rect[3];
                    commands.add(new UpdateViewObjectCommand(containerObject,
                            rect[0], rect[1], newGroupWidth, newGroupHeight));
                } else if (requiredDims[0] > currentRect[2] || requiredDims[1] > currentRect[3]) {
                    overflow = true;
                    logger.debug("Children overflow container bounds: required={}x{}, actual={}x{}",
                            requiredDims[0], requiredDims[1], currentRect[2], currentRect[3]);
                }
            }

            // 10a/11a. Ancestor auto-resize (UPWARD). Orthogonal to the descendant recursion and
            // composes with it: the container's own resize command is already in 'commands' from
            // whichever arm ran, so each ancestor fits against the size the container is about to
            // have. The collaborator owns the preconditions as well as the walk — it reports which
            // of them stopped it, and only the code beside the walk can name where the walk ended.
            NestedLayoutOperations.AncestorPropagation upward =
                    NestedLayoutOperations.propagateToAncestors(containerObject, recursive,
                            autoResize, commands, resolvedPadding, ancestorResizes, pendingBounds);
            int ancestorsResized = upward.ancestorsResized();

            // 11. Build compound command
            String label = "Layout within group ("
                    + normalizedArrangement + ", "
                    + elementsRepositioned + " elements"
                    + (recursiveChildren ? ", recursive descendants" : "")
                    + (autoResize ? ", auto-resized" : "")
                    + (ancestorsResized > 0 ? ", " + ancestorsResized + " ancestors resized" : "")
                    + (nestedContainersArranged > 0 ? ", " + nestedContainersArranged + " nested containers" : "")
                    + ")";

            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            boolean groupResized = NestedLayoutOperations.grewContainer(
                    containerObject, autoResize, newGroupWidth, newGroupHeight);
            LayoutWithinGroupResultDto dto = new LayoutWithinGroupResultDto(
                    viewId, groupViewObjectId, normalizedArrangement,
                    elementsRepositioned, groupResized, newGroupWidth, newGroupHeight,
                    overflow, effectiveAutoWidth, columnsUsed, ancestorsResized,
                    nestedContainersArranged, maxDepthReached,
                    AnchorResolver.projectMoves(ancestorResizes, layoutView),
                    AnchorResolver.projectMoves(nestedFits, layoutView), upward.reason(),
                    AnchorResolver.projectMoves(resizedLeaves, layoutView));

            // 12. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("arrangement", normalizedArrangement);
                proposedChanges.put("elementsRepositioned", elementsRepositioned);
                proposedChanges.put("groupResized", groupResized);
                if (ancestorsResized > 0) proposedChanges.put("ancestorsResized", ancestorsResized);
                // Unconditional: the count is on the card only when it is positive, so the reason is
                // the only thing left that can explain the card that says nothing about ancestors.
                proposedChanges.put("ancestorPropagation", upward.reason());
                if (!resizedLeaves.isEmpty()) proposedChanges.put("elementsResized", resizedLeaves.size()); // count named like ancestorsResized; the rectangles ride under preview as resizedElements
                if (recursiveChildren) proposedChanges.put("nestedContainersArranged", nestedContainersArranged);
                ProposalBuilder.putIfPresent(proposedChanges, "newGroupWidth", newGroupWidth, "newGroupHeight", newGroupHeight);
                ProposalContext ctx = storeAsProposal(sessionId,
                        "layout-within-group",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId, groupViewObjectId), dto, label,
                        null, proposedChanges,
                        "Group layout computed and ready for application." + ProposalBuilder.REVIEWED_OR_REJECT);
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

    // ---- layout-flat-view ----

    private static final int DEFAULT_FLAT_VIEW_SPACING = 40;
    private static final int DEFAULT_FLAT_VIEW_PADDING = 20;
    private static final int CATEGORY_SPACING_MULTIPLIER = 2;
    private static final int ELEMENT_LABEL_HEIGHT = 25;

    /** Standard ArchiMate layer ordering for sort/category purposes. */
    private static final List<String> LAYER_ORDER = List.of(
            "Strategy", "Business", "Application", "Technology",
            "Physical", "Implementation & Migration", "Motivation", "Other");

    @Override
    public MutationResult<LayoutFlatViewResultDto> layoutFlatView(
            String sessionId, String viewId, String arrangement,
            Integer spacing, Integer padding, String sortBy,
            String categoryField, Integer columns,
            boolean autoLayoutChildren) {
        logger.info("Layout flat view: viewId={}, arrangement={}, sortBy={}, categoryField={}",
                viewId, arrangement, sortBy, categoryField);
        IArchimateModel model = requireAndCaptureModel();

        try {
            // 1. Validate view
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 2. Validate arrangement
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

            // 3. Validate optional parameters
            validateGroupLayoutParams(spacing, padding, columns);
            if (sortBy != null && !sortBy.isBlank()) {
                String normalizedSort = sortBy.toLowerCase().trim();
                if (!"name".equals(normalizedSort) && !"type".equals(normalizedSort)
                        && !"layer".equals(normalizedSort)) {
                    throw new ModelAccessException(
                            "Invalid sortBy: '" + sortBy
                                    + "'. Valid values: name, type, layer.",
                            ErrorCode.INVALID_PARAMETER);
                }
            }
            if (categoryField != null && !categoryField.isBlank()) {
                String normalizedCategory = categoryField.toLowerCase().trim();
                if (!"type".equals(normalizedCategory) && !"layer".equals(normalizedCategory)) {
                    throw new ModelAccessException(
                            "Invalid categoryField: '" + categoryField
                                    + "'. Valid values: type, layer.",
                            ErrorCode.INVALID_PARAMETER);
                }
            }

            int resolvedSpacing = (spacing != null) ? spacing : DEFAULT_FLAT_VIEW_SPACING;
            int resolvedPadding = (padding != null) ? padding : DEFAULT_FLAT_VIEW_PADDING;

            // 4. Collect top-level elements (skip notes)
            List<IDiagramModelObject> topLevelElements = new ArrayList<>();
            for (IDiagramModelObject child : diagramModel.getChildren()) {
                if (child instanceof IDiagramModelNote) {
                    continue; // Notes are not laid out
                }
                topLevelElements.add(child);
            }

            if (topLevelElements.isEmpty()) {
                throw new ModelAccessException(
                        "View has no top-level elements to layout",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 5. Sort elements if requested
            String normalizedSort = (sortBy != null && !sortBy.isBlank())
                    ? sortBy.toLowerCase().trim() : null;
            if (normalizedSort != null) {
                sortFlatViewElements(topLevelElements, normalizedSort);
            }

            // 6. Compute positions (with or without category grouping)
            String normalizedCategory = (categoryField != null && !categoryField.isBlank())
                    ? categoryField.toLowerCase().trim() : null;
            List<int[]> positions;
            List<String> categories = null;
            Integer columnsUsed = null;

            // Declared before the computers below, not after them: every one of them measures the
            // elements it lays out, and inside an open batch that measurement must be of the
            // rectangle the batch has queued rather than the pre-batch one.
            Map<String, int[]> pendingBounds = sameBatchBounds(sessionId);

            if (normalizedCategory != null) {
                // Category-based layout: partition elements by category, lay out each section
                FlatCategoryLayoutResult catResult = computeFlatCategoryLayout(
                        topLevelElements, normalizedArrangement, normalizedCategory,
                        resolvedSpacing, resolvedPadding, columns, pendingBounds);
                positions = catResult.positions();
                categories = catResult.categories();
                columnsUsed = catResult.columnsUsed();
                // Re-order topLevelElements to match category-sorted order
                topLevelElements = catResult.orderedElements();
            } else {
                // Simple layout: all elements in one group
                positions = computeFlatPositions(topLevelElements, normalizedArrangement,
                        resolvedSpacing, resolvedPadding, columns, pendingBounds);
                if ("grid".equals(normalizedArrangement)) {
                    columnsUsed = computeFlatGridColumns(topLevelElements, columns);
                }
            }

            // 7. Auto-layout embedded children within parent elements
            int childrenRepositioned = 0;
            // Maps parent index -> child positions (for command building)
            Map<Integer, List<int[]>> parentChildPositions = new LinkedHashMap<>();
            // Maps parent index -> list of child objects
            Map<Integer, List<IDiagramModelObject>> parentChildObjects = new LinkedHashMap<>();

            if (autoLayoutChildren) {
                boolean anyParentResized = false;

                for (int i = 0; i < topLevelElements.size(); i++) {
                    IDiagramModelObject element = topLevelElements.get(i);
                    if (!(element instanceof IDiagramModelContainer container)) {
                        continue;
                    }
                    // Collect non-note children
                    List<IDiagramModelObject> children = new ArrayList<>();
                    for (IDiagramModelObject child : container.getChildren()) {
                        if (!(child instanceof IDiagramModelNote)) {
                            children.add(child);
                        }
                    }
                    if (children.isEmpty()) {
                        continue;
                    }

                    // Compute column layout for children within parent bounds (relative coords)
                    int childStartX = DEFAULT_GROUP_PADDING;
                    int childStartY = ELEMENT_LABEL_HEIGHT;
                    List<int[]> childPositions = computeColumnLayout(children,
                            childStartX, childStartY, DEFAULT_GROUP_SPACING,
                            null, null, false, pendingBounds);

                    parentChildPositions.put(i, childPositions);
                    parentChildObjects.put(i, children);
                    childrenRepositioned += children.size();

                    // Check if children exceed parent bounds and resize if needed
                    int[] autoSize = computeAutoResizeDimensions(childPositions,
                            DEFAULT_GROUP_PADDING, ELEMENT_LABEL_HEIGHT);
                    int[] pos = positions.get(i);
                    int currentWidth = pos[2];
                    int currentHeight = pos[3];

                    if (autoSize[0] > currentWidth || autoSize[1] > currentHeight) {
                        int newWidth = Math.max(currentWidth, autoSize[0]);
                        int newHeight = Math.max(currentHeight, autoSize[1]);
                        positions.set(i, new int[]{pos[0], pos[1], newWidth, newHeight});
                        anyParentResized = true;
                    }
                }

                // Re-layout top-level elements if any parent was resized
                // Must use positions array's sizes (not getBounds()) since parents were resized
                if (anyParentResized) {
                    if (normalizedCategory != null) {
                        recomputeFlatCategoryPositionsInPlace(positions, topLevelElements,
                                normalizedArrangement, normalizedCategory,
                                resolvedSpacing, resolvedPadding, columns);
                    } else {
                        recomputeFlatPositionsInPlace(positions, normalizedArrangement,
                                resolvedSpacing, resolvedPadding, columns);
                    }
                }
            }

            // 8. Build commands. A full rectangle goes to every object, so a parent grown to hold
            // the children this pass laid out changes size without being asked. Compared against
            // the EFFECTIVE rectangle, so a size an open batch already queued is the basis — the
            // same map the position computers above already sized their elements from.
            Map<String, Command> resizedElements = new LinkedHashMap<>();
            List<Command> commands = new ArrayList<>(NestedLayoutOperations.placeChildren(
                    topLevelElements, positions, resizedElements, pendingBounds));

            // This range cannot land a differing size, inside a batch or out: the column layout
            // above resolves sizes with a null width, a null height and autoWidth false, so each
            // child is written back the size it effectively has — and it resolves that from the
            // same queued basis the observation compares against, so the two cannot disagree. It
            // shares the observation anyway, because that equality is a property of those three
            // literals rather than a guarantee: change any of them and the range can resize again.
            for (Map.Entry<Integer, List<int[]>> entry : parentChildPositions.entrySet()) {
                commands.addAll(NestedLayoutOperations.placeChildren(
                        parentChildObjects.get(entry.getKey()), entry.getValue(),
                        resizedElements, pendingBounds));
            }

            // 9. Build compound command and result DTO
            String label = "Layout flat view ("
                    + normalizedArrangement + ", "
                    + topLevelElements.size() + " elements"
                    + (childrenRepositioned > 0 ? ", " + childrenRepositioned + " children" : "")
                    + (normalizedSort != null ? ", sorted by " + normalizedSort : "")
                    + (normalizedCategory != null ? ", grouped by " + normalizedCategory : "")
                    + ")";

            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            LayoutFlatViewResultDto dto = new LayoutFlatViewResultDto(
                    viewId, normalizedArrangement, topLevelElements.size(),
                    childrenRepositioned,
                    normalizedSort, normalizedCategory, categories, columnsUsed,
                    AnchorResolver.projectMoves(resizedElements, diagramModel));

            // 10. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("arrangement", normalizedArrangement);
                proposedChanges.put("elementsRepositioned", topLevelElements.size());
                if (childrenRepositioned > 0) proposedChanges.put("childrenRepositioned", childrenRepositioned);
                ProposalBuilder.putIfPresent(proposedChanges, "sortBy", normalizedSort, "categoryField", normalizedCategory);
                ProposalContext ctx = storeAsProposal(sessionId,
                        "layout-flat-view",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId), dto, label,
                        null, proposedChanges,
                        "Flat view layout computed and ready for application." + ProposalBuilder.REVIEWED_OR_REJECT);
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
                    "Error computing/applying flat view layout for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Sorts flat view elements by name, type, or layer.
     */
    private void sortFlatViewElements(List<IDiagramModelObject> elements, String sortBy) {
        elements.sort((a, b) -> {
            String aVal = getFlatViewSortKey(a, sortBy);
            String bVal = getFlatViewSortKey(b, sortBy);
            return aVal.compareToIgnoreCase(bVal);
        });
    }

    private String getFlatViewSortKey(IDiagramModelObject obj, String sortBy) {
        if (obj instanceof IDiagramModelArchimateObject archObj) {
            IArchimateElement element = archObj.getArchimateElement();
            switch (sortBy) {
            case "name":
                return element.getName() != null ? element.getName() : "";
            case "type":
                return element.eClass().getName();
            case "layer":
                String layer = DtoMapper.resolveLayer(element);
                int idx = LAYER_ORDER.indexOf(layer);
                // Pad with leading zeros for correct sort order, then append name for stability
                return String.format("%02d-%s", idx >= 0 ? idx : 99,
                        element.getName() != null ? element.getName() : "");
            default:
                return element.getName() != null ? element.getName() : "";
            }
        }
        // Groups and other non-archimate objects: sort by name
        if (obj instanceof IDiagramModelGroup group) {
            return switch (sortBy) {
                case "type" -> "ZZGroup"; // Groups sort last by type
                case "layer" -> String.format("%02d-%s", 99,
                        group.getName() != null ? group.getName() : "");
                default -> group.getName() != null ? group.getName() : "";
            };
        }
        return "";
    }

    private String getFlatViewCategoryValue(IDiagramModelObject obj, String categoryField) {
        if (obj instanceof IDiagramModelArchimateObject archObj) {
            IArchimateElement element = archObj.getArchimateElement();
            return switch (categoryField) {
                case "type" -> element.eClass().getName();
                case "layer" -> DtoMapper.resolveLayer(element);
                default -> "Other";
            };
        }
        if (obj instanceof IDiagramModelGroup) {
            return switch (categoryField) {
                case "type" -> "Group";
                case "layer" -> "Other";
                default -> "Other";
            };
        }
        return "Other";
    }

    /** Result of category-based flat layout computation. */
    private record FlatCategoryLayoutResult(
            List<int[]> positions,
            List<String> categories,
            List<IDiagramModelObject> orderedElements,
            Integer columnsUsed) {}

    /**
     * Computes positions for elements grouped by category.
     * Each category section is laid out separately with extra spacing between sections.
     */
    private FlatCategoryLayoutResult computeFlatCategoryLayout(
            List<IDiagramModelObject> elements, String arrangement,
            String categoryField, int spacing, int padding, Integer columns, Map<String, int[]> pending) {

        // Partition elements by category, preserving order within each category
        Map<String, List<IDiagramModelObject>> categoryMap = new LinkedHashMap<>();
        for (IDiagramModelObject obj : elements) {
            String category = getFlatViewCategoryValue(obj, categoryField);
            categoryMap.computeIfAbsent(category, k -> new ArrayList<>()).add(obj);
        }

        // Sort categories: for layer use standard layer order, for type use alphabetical
        List<String> sortedCategories;
        if ("layer".equals(categoryField)) {
            sortedCategories = new ArrayList<>(categoryMap.keySet());
            sortedCategories.sort((a, b) -> {
                int idxA = LAYER_ORDER.indexOf(a);
                int idxB = LAYER_ORDER.indexOf(b);
                return Integer.compare(idxA >= 0 ? idxA : 99, idxB >= 0 ? idxB : 99);
            });
        } else {
            sortedCategories = new ArrayList<>(categoryMap.keySet());
            sortedCategories.sort(String::compareToIgnoreCase);
        }

        int categorySpacing = spacing * CATEGORY_SPACING_MULTIPLIER;
        List<int[]> allPositions = new ArrayList<>();
        List<IDiagramModelObject> orderedElements = new ArrayList<>();
        Integer columnsUsed = null;

        // Layout each category section
        int currentX = padding;
        int currentY = padding;

        for (String category : sortedCategories) {
            List<IDiagramModelObject> catElements = categoryMap.get(category);
            orderedElements.addAll(catElements);

            // Compute positions for this category section
            List<int[]> sectionPositions;
            switch (arrangement) {
            case "row":
                sectionPositions = computeFlatRowPositions(catElements, currentX, currentY, spacing,
                        pending);
                allPositions.addAll(sectionPositions);
                // Next category starts below this row
                int rowMaxH = sectionPositions.stream()
                        .mapToInt(p -> p[3]).max().orElse(0);
                currentY += rowMaxH + categorySpacing;
                break;
            case "column":
                sectionPositions = computeFlatColumnPositions(catElements, currentX, currentY, spacing,
                        pending);
                allPositions.addAll(sectionPositions);
                // Next category starts to the right of this column
                int colMaxW = sectionPositions.stream()
                        .mapToInt(p -> p[2]).max().orElse(0);
                currentX += colMaxW + categorySpacing;
                break;
            case "grid":
                int cols = computeFlatGridColumns(catElements, columns);
                if (columnsUsed == null || cols > columnsUsed) {
                    columnsUsed = cols; // Track max across categories (varies when auto-detected)
                }
                FlatGridResult gridResult = computeFlatGridPositions(
                        catElements, currentX, currentY, spacing, cols, pending);
                allPositions.addAll(gridResult.positions());
                // Next category starts below this grid
                currentY = gridResult.maxY() + categorySpacing;
                break;
            }
        }

        return new FlatCategoryLayoutResult(allPositions, sortedCategories,
                orderedElements, columnsUsed);
    }

    /**
     * Computes positions for all elements without category grouping.
     */
    private List<int[]> computeFlatPositions(
            List<IDiagramModelObject> elements, String arrangement,
            int spacing, int padding, Integer columns, Map<String, int[]> pending) {
        switch (arrangement) {
        case "row":
            return computeFlatRowPositions(elements, padding, padding, spacing, pending);
        case "column":
            return computeFlatColumnPositions(elements, padding, padding, spacing, pending);
        case "grid":
            int cols = computeFlatGridColumns(elements, columns);
            return computeFlatGridPositions(elements, padding, padding, spacing, cols, pending)
                    .positions();
        default:
            return computeFlatRowPositions(elements, padding, padding, spacing, pending);
        }
    }

    /**
     * Row layout: elements placed left-to-right, preserving current sizes — which inside an open
     * batch means the size the batch has queued, not the pre-batch one it is about to lose.
     */
    private List<int[]> computeFlatRowPositions(
            List<IDiagramModelObject> elements, int startX, int startY, int spacing,
            Map<String, int[]> pending) {
        List<int[]> positions = new ArrayList<>();
        int currentX = startX;
        for (IDiagramModelObject element : elements) {
            int[] eff = AnchorResolver.effectiveRect(element, pending);
            int w = eff[2];
            int h = eff[3];
            positions.add(new int[]{currentX, startY, w, h});
            currentX += w + spacing;
        }
        return positions;
    }

    /** Column layout: elements placed top-to-bottom, preserving their effective sizes. */
    private List<int[]> computeFlatColumnPositions(
            List<IDiagramModelObject> elements, int startX, int startY, int spacing,
            Map<String, int[]> pending) {
        List<int[]> positions = new ArrayList<>();
        int currentY = startY;
        for (IDiagramModelObject element : elements) {
            int[] eff = AnchorResolver.effectiveRect(element, pending);
            int w = eff[2];
            int h = eff[3];
            positions.add(new int[]{startX, currentY, w, h});
            currentY += h + spacing;
        }
        return positions;
    }

    /** Determines grid column count: explicit, or auto-detected from element count. */
    private int computeFlatGridColumns(List<IDiagramModelObject> elements, Integer columns) {
        if (columns != null) {
            return Math.min(columns, elements.size());
        }
        // Auto-detect: ceil(sqrt(n)) gives a roughly square grid
        return Math.max(1, (int) Math.ceil(Math.sqrt(elements.size())));
    }

    private record FlatGridResult(List<int[]> positions, int maxY) {}

    /**
     * Grid layout: elements in rows and columns, using max width/height for uniform cells. BOTH
     * reads are effective ones: the cell size is derived from the widest and tallest element the
     * pass measures, so a stale read there moves every sibling and not only the element itself.
     */
    private FlatGridResult computeFlatGridPositions(
            List<IDiagramModelObject> elements, int startX, int startY,
            int spacing, int cols, Map<String, int[]> pending) {
        // Determine max cell size for uniform grid
        int maxW = 0;
        int maxH = 0;
        for (IDiagramModelObject element : elements) {
            int[] eff = AnchorResolver.effectiveRect(element, pending);
            maxW = Math.max(maxW, eff[2]);
            maxH = Math.max(maxH, eff[3]);
        }

        List<int[]> positions = new ArrayList<>();
        int currentX = startX;
        int currentY = startY;
        int col = 0;

        for (IDiagramModelObject element : elements) {
            int[] eff = AnchorResolver.effectiveRect(element, pending);
            // Preserve actual size but use uniform grid cell spacing
            positions.add(new int[]{currentX, currentY, eff[2], eff[3]});

            col++;
            if (col >= cols) {
                col = 0;
                currentX = startX;
                currentY += maxH + spacing;
            } else {
                currentX += maxW + spacing;
            }
        }

        // Calculate maxY: bottom of last row
        int lastRowY = positions.isEmpty() ? startY
                : positions.get(positions.size() - 1)[1]
                + positions.get(positions.size() - 1)[3];
        return new FlatGridResult(positions, lastRowY);
    }

    /**
     * Recomputes x,y positions in-place for a list of position arrays using their
     * current width/height values instead of reading from model objects.
     * Used after parent elements are resized to prevent top-level overlaps.
     */
    private void recomputeFlatPositionsInPlace(List<int[]> positions,
            String arrangement, int spacing, int padding, Integer columns) {
        if (positions.isEmpty()) return;

        switch (arrangement) {
        case "row": {
            int currentX = padding;
            for (int[] pos : positions) {
                pos[0] = currentX;
                pos[1] = padding;
                currentX += pos[2] + spacing;
            }
            break;
        }
        case "column": {
            int currentY = padding;
            for (int[] pos : positions) {
                pos[0] = padding;
                pos[1] = currentY;
                currentY += pos[3] + spacing;
            }
            break;
        }
        case "grid": {
            int maxW = 0;
            int maxH = 0;
            for (int[] pos : positions) {
                maxW = Math.max(maxW, pos[2]);
                maxH = Math.max(maxH, pos[3]);
            }
            int cols = (columns != null) ? Math.min(columns, positions.size())
                    : Math.max(1, (int) Math.ceil(Math.sqrt(positions.size())));
            int currentX = padding;
            int currentY = padding;
            int col = 0;
            for (int[] pos : positions) {
                pos[0] = currentX;
                pos[1] = currentY;
                col++;
                if (col >= cols) {
                    col = 0;
                    currentX = padding;
                    currentY += maxH + spacing;
                } else {
                    currentX += maxW + spacing;
                }
            }
            break;
        }
        default:
            break;
        }
    }

    /**
     * Recomputes positions in-place for category-based layouts after parent resize.
     * Uses sizes already stored in positions array rather than reading stale getBounds().
     * Preserves category grouping with 2x category spacing between sections.
     */
    private void recomputeFlatCategoryPositionsInPlace(List<int[]> positions,
            List<IDiagramModelObject> elements, String arrangement,
            String categoryField, int spacing, int padding, Integer columns) {
        if (positions.isEmpty()) return;

        int categorySpacing = spacing * CATEGORY_SPACING_MULTIPLIER;

        // Group indices by category, preserving element order
        Map<String, List<Integer>> categoryIndices = new LinkedHashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            String category = getFlatViewCategoryValue(elements.get(i), categoryField);
            categoryIndices.computeIfAbsent(category, k -> new ArrayList<>()).add(i);
        }

        // Sort categories same as computeFlatCategoryLayout
        List<String> sortedCategories;
        if ("layer".equals(categoryField)) {
            sortedCategories = new ArrayList<>(categoryIndices.keySet());
            sortedCategories.sort((a, b) -> {
                int idxA = LAYER_ORDER.indexOf(a);
                int idxB = LAYER_ORDER.indexOf(b);
                return Integer.compare(idxA >= 0 ? idxA : 99, idxB >= 0 ? idxB : 99);
            });
        } else {
            sortedCategories = new ArrayList<>(categoryIndices.keySet());
            sortedCategories.sort(String::compareToIgnoreCase);
        }

        int currentX = padding;
        int currentY = padding;

        for (String category : sortedCategories) {
            List<Integer> indices = categoryIndices.get(category);

            switch (arrangement) {
            case "row": {
                int rowX = padding;
                int maxH = 0;
                for (int idx : indices) {
                    int[] pos = positions.get(idx);
                    pos[0] = rowX;
                    pos[1] = currentY;
                    rowX += pos[2] + spacing;
                    maxH = Math.max(maxH, pos[3]);
                }
                currentY += maxH + categorySpacing;
                break;
            }
            case "column": {
                int colY = padding;
                int maxW = 0;
                for (int idx : indices) {
                    int[] pos = positions.get(idx);
                    pos[0] = currentX;
                    pos[1] = colY;
                    colY += pos[3] + spacing;
                    maxW = Math.max(maxW, pos[2]);
                }
                currentX += maxW + categorySpacing;
                break;
            }
            case "grid": {
                int maxW = 0;
                int maxH = 0;
                for (int idx : indices) {
                    int[] pos = positions.get(idx);
                    maxW = Math.max(maxW, pos[2]);
                    maxH = Math.max(maxH, pos[3]);
                }
                int cols = (columns != null) ? Math.min(columns, indices.size())
                        : Math.max(1, (int) Math.ceil(Math.sqrt(indices.size())));
                int gridX = padding;
                int gridY = currentY;
                int col = 0;
                for (int idx : indices) {
                    int[] pos = positions.get(idx);
                    pos[0] = gridX;
                    pos[1] = gridY;
                    col++;
                    if (col >= cols) {
                        col = 0;
                        gridX = padding;
                        gridY += maxH + spacing;
                    } else {
                        gridX += maxW + spacing;
                    }
                }
                // Compute bottom of grid for next category
                int lastRowY = currentY;
                for (int idx : indices) {
                    lastRowY = Math.max(lastRowY, positions.get(idx)[1]);
                }
                currentY = lastRowY + maxH + categorySpacing;
                break;
            }
            default:
                break;
            }
        }
    }

    // ---- optimize-group-order ----

    @Override
    public MutationResult<OptimizeGroupOrderResultDto> optimizeGroupOrder(
            String sessionId, String viewId, String arrangement,
            Integer spacing, Integer padding, Integer elementWidth,
            Integer elementHeight, boolean autoWidth, Integer columns,
            Map<String, String> groupArrangements) {
        logger.info("Optimize group order: viewId={}, arrangement={}", viewId, arrangement);
        IArchimateModel model = requireAndCaptureModel();

        try {
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                throw new ModelAccessException(
                        "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
            }

            // 1. Validate arrangement (now optional — null means auto-detect)
            String normalizedArrangement = null;
            if (arrangement != null && !arrangement.isBlank()) {
                normalizedArrangement = arrangement.trim().toLowerCase();
                if (!normalizedArrangement.equals("row")
                        && !normalizedArrangement.equals("column")
                        && !normalizedArrangement.equals("grid")) {
                    throw new ModelAccessException(
                            "Invalid arrangement '" + arrangement
                            + "'. Must be 'row', 'column', or 'grid'.",
                            ErrorCode.INVALID_PARAMETER);
                }
            }

            // Validate groupArrangements values if provided
            if (groupArrangements != null) {
                for (Map.Entry<String, String> entry : groupArrangements.entrySet()) {
                    String val = entry.getValue().trim().toLowerCase();
                    if (!val.equals("row") && !val.equals("column") && !val.equals("grid")) {
                        throw new ModelAccessException(
                                "Invalid arrangement '" + entry.getValue()
                                + "' for group '" + entry.getKey()
                                + "'. Must be 'row', 'column', or 'grid'.",
                                ErrorCode.INVALID_PARAMETER);
                    }
                }
            }

            // 2. Validate optional params
            validateGroupLayoutParams(spacing, padding, columns);
            int resolvedSpacing = (spacing != null) ? spacing : DEFAULT_GROUP_SPACING;
            int resolvedPadding = (padding != null) ? padding : DEFAULT_GROUP_PADDING;

            // 3. Collect top-level containers and their children
            List<IDiagramModelObject> topLevelGroups =
                    TopLevelGroupTargets.collect(diagramModel);

            if (topLevelGroups.isEmpty()) {
                ModelAccessException allNested = TopLevelGroupTargets.containersAreAllNested(
                        viewId, diagramModel,
                        "optimize-group-order reorders the view's own containers.");
                if (allNested != null) throw allNested;
                throw new ModelAccessException(
                        "View has no groups — optimize-group-order requires groups "
                        + "with inter-group connections.",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 4. Build group info with element centers (relative-to-group positions)
            // Map groupId → group object for later reference
            Map<String, IDiagramModelObject> groupMap = new LinkedHashMap<>();
            // Map elementViewObjectId → groupId for connection mapping
            Map<String, String> elementToGroupId = new HashMap<>();
            List<CrossingMinimizer.GroupInfo> groupInfos = new ArrayList<>();
            // Map groupId → relative child positions [x, y, w, h] for arrangement detection
            Map<String, List<int[]>> groupChildPositions = new LinkedHashMap<>();

            for (IDiagramModelObject group : topLevelGroups) {
                String groupId = group.getId();
                groupMap.put(groupId, group);

                List<String> elementIds = new ArrayList<>();
                List<int[]> centers = new ArrayList<>();
                List<int[]> childPositions = new ArrayList<>();

                for (IDiagramModelObject child : TopLevelGroupTargets.childrenOf(group)) {
                    if (child instanceof IDiagramModelNote) {
                        continue; // Skip notes
                    }
                    String childId = child.getId();
                    elementIds.add(childId);
                    elementToGroupId.put(childId, groupId);

                    IBounds bounds = child.getBounds();
                    // Store relative positions for arrangement detection
                    childPositions.add(new int[]{
                            bounds.getX(), bounds.getY(),
                            bounds.getWidth(), bounds.getHeight()});

                    // Compute center in absolute coordinates for crossing calculation
                    IBounds groupBounds = group.getBounds();
                    int absCenterX = groupBounds.getX() + bounds.getX() + bounds.getWidth() / 2;
                    int absCenterY = groupBounds.getY() + bounds.getY() + bounds.getHeight() / 2;
                    centers.add(new int[]{absCenterX, absCenterY});
                }

                if (!elementIds.isEmpty()) {
                    groupInfos.add(new CrossingMinimizer.GroupInfo(
                            groupId, elementIds, centers));
                    groupChildPositions.put(groupId, childPositions);
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
            List<Command> commands = new ArrayList<>(); Map<String, Command> resizedElements = new LinkedHashMap<>();
            boolean effectiveAutoWidth = autoWidth && (elementWidth == null);
            int startX = resolvedPadding;
            // Read once for the whole pass rather than per group: every command this loop builds is
            // accumulated locally and dispatched only after it ends, so the queue cannot change
            // underneath it and a per-group re-read would return the same snapshot each time.
            Map<String, int[]> pendingBounds = sameBatchBounds(sessionId);

            List<OptimizeGroupOrderResultDto.GroupDetail> groupDetails = new ArrayList<>();

            for (CrossingMinimizer.GroupInfo groupInfo : groupInfos) {
                String groupId = groupInfo.groupId();
                IDiagramModelObject group = groupMap.get(groupId);
                List<String> newOrder = optResult.newOrderByGroup().get(groupId);
                boolean reordered = optResult.reorderedGroups().contains(groupId);

                // Per container, not once for the pass: an element container reserves a taller
                // title band than a native group, and this loop now sees both kinds.
                int startY = resolvedPadding + NestedLayoutOperations.labelHeightFor(group);

                // Resolve per-group arrangement: override > detected > fallback
                String resolvedGroupArrangement;
                String arrangementSource;
                Integer resolvedGridColumns = columns; // start with global columns param

                if (groupArrangements != null
                        && groupArrangements.containsKey(groupId)) {
                    // Priority 1: explicit per-group override
                    resolvedGroupArrangement = groupArrangements.get(groupId)
                            .trim().toLowerCase();
                    arrangementSource = "override";
                } else if (normalizedArrangement == null) {
                    // Priority 2: auto-detect (when no global arrangement provided)
                    List<int[]> childPositions = groupChildPositions.get(groupId);
                    ArrangementDetector.DetectedArrangement detected =
                            ArrangementDetector.detect(childPositions);
                    resolvedGroupArrangement = detected.type();
                    arrangementSource = "detected";
                    if ("grid".equals(detected.type())
                            && detected.gridColumns() != null
                            && columns == null) {
                        resolvedGridColumns = detected.gridColumns();
                    }
                } else {
                    // Priority 3: global arrangement fallback
                    resolvedGroupArrangement = normalizedArrangement;
                    arrangementSource = "fallback";
                }

                groupDetails.add(new OptimizeGroupOrderResultDto.GroupDetail(
                        groupId, group.getName(),
                        groupInfo.elementIds().size(), reordered,
                        resolvedGroupArrangement, arrangementSource));

                if (!reordered || newOrder == null) continue;

                List<IDiagramModelObject> orderedChildren =
                        TopLevelGroupTargets.childrenInOrder(group, newOrder);

                // Compute new positions using the resolved per-group arrangement
                List<int[]> positions;
                switch (resolvedGroupArrangement) {
                case "row":
                    positions = computeRowLayout(orderedChildren, startX, startY,
                            resolvedSpacing, elementWidth, elementHeight,
                            effectiveAutoWidth, pendingBounds);
                    break;
                case "column":
                    positions = computeColumnLayout(orderedChildren, startX, startY,
                            resolvedSpacing, elementWidth, elementHeight,
                            effectiveAutoWidth, pendingBounds);
                    break;
                case "grid":
                    // Derives the column count when the caller named none, so it must measure the
                    // container at the rectangle it will actually have, not the pre-batch one.
                    int groupWidth = AnchorResolver.effectiveRect(group, pendingBounds)[2];
                    GroupLayoutCalculator.GridLayoutResult gridResult = computeGridLayout(
                            orderedChildren, startX, startY,
                            resolvedSpacing, resolvedPadding, groupWidth,
                            elementWidth, elementHeight, effectiveAutoWidth,
                            resolvedGridColumns, pendingBounds);
                    positions = gridResult.positions();
                    break;
                default:
                    positions = computeColumnLayout(orderedChildren, startX, startY,
                            resolvedSpacing, elementWidth, elementHeight,
                            effectiveAutoWidth, pendingBounds);
                }

                // Re-running the arrangement writes a full rectangle to every child, so a grid's
                // uniform cell, autoWidth, or an explicit elementWidth can land one at a size the
                // caller cannot otherwise learn. Compared against the effective rectangle.
                commands.addAll(NestedLayoutOperations.placeChildren(
                        orderedChildren, positions, resizedElements, pendingBounds));

                // Auto-resize group to fit, never below what the batch already queued for it
                int[] rect = AnchorResolver.refitRect(group,
                        computeAutoResizeDimensions(positions, resolvedPadding, GROUP_LABEL_HEIGHT),
                        pendingBounds);
                commands.add(new UpdateViewObjectCommand(group, rect[0], rect[1], rect[2], rect[3]));
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
                    optResult.elementMoves(), groupDetails,
                    AnchorResolver.projectMoves(resizedElements, diagramModel));

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
                        "optimize-group-order",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId), dto, label,
                        null, proposedChanges,
                        "Group element order optimized and ready for application." + ProposalBuilder.REVIEWED_OR_REJECT);
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
     * Delegates to {@link GroupLayoutCalculator#computeAutoWidth(String)}.
     */
    int computeAutoWidth(IDiagramModelObject child) {
        return GroupLayoutCalculator.computeAutoWidth(getDisplayName(child));
    }

    /**
     * Resolves element sizes for layout computation, applying elementWidth/elementHeight
     * overrides and autoWidth as needed.
     *
     * <p>An axis the caller did not override falls back to the child's
     * {@link AnchorResolver#effectiveRect effective} rectangle, so inside an open batch it reads
     * the size an earlier operation of that batch queued instead of the pre-batch one the child is
     * about to stop having. {@code pending} is a PARAMETER rather than a lookup because one caller
     * must keep measuring live bounds — see the relay pass in the grouped layout loop, whose sizes
     * are chosen from contents rather than preserved.
     */
    private List<int[]> resolveElementSizes(List<IDiagramModelObject> children,
            Integer elementWidth, Integer elementHeight, boolean autoWidth,
            Map<String, int[]> pending) {
        List<int[]> sizes = new ArrayList<>();
        for (IDiagramModelObject child : children) {
            int[] eff = AnchorResolver.effectiveRect(child, pending);
            int w = (elementWidth != null) ? elementWidth
                    : autoWidth ? computeAutoWidth(child)
                    : eff[2];
            int h = (elementHeight != null) ? elementHeight : eff[3];
            sizes.add(new int[]{w, h});
        }
        return sizes;
    }

    /**
     * Computes row arrangement positions (left-to-right).
     * Delegates to {@link GroupLayoutCalculator#computeRowLayout}.
     */
    private List<int[]> computeRowLayout(List<IDiagramModelObject> children,
            int startX, int startY, int spacing,
            Integer elementWidth, Integer elementHeight,
            boolean autoWidth, Map<String, int[]> pending) {
        List<int[]> sizes = resolveElementSizes(children, elementWidth, elementHeight, autoWidth,
                pending);
        return GroupLayoutCalculator.computeRowLayout(sizes, startX, startY, spacing);
    }

    /**
     * Computes column arrangement positions (top-to-bottom).
     * Delegates to {@link GroupLayoutCalculator#computeColumnLayout}.
     */
    private List<int[]> computeColumnLayout(List<IDiagramModelObject> children,
            int startX, int startY, int spacing,
            Integer elementWidth, Integer elementHeight,
            boolean autoWidth, Map<String, int[]> pending) {
        List<int[]> sizes = resolveElementSizes(children, elementWidth, elementHeight, autoWidth,
                pending);
        return GroupLayoutCalculator.computeColumnLayout(sizes, startX, startY, spacing);
    }

    /**
     * Computes grid arrangement positions (left-to-right, top-to-bottom).
     * Delegates to {@link GroupLayoutCalculator#computeGridLayout}.
     */
    private GroupLayoutCalculator.GridLayoutResult computeGridLayout(
            List<IDiagramModelObject> children,
            int startX, int startY, int spacing, int padding, int groupWidth,
            Integer elementWidth, Integer elementHeight,
            boolean autoWidth, Integer columns, Map<String, int[]> pending) {
        List<int[]> sizes = resolveElementSizes(children, elementWidth, elementHeight, autoWidth,
                pending);
        return GroupLayoutCalculator.computeGridLayout(sizes, startX, startY, spacing,
                padding, groupWidth, columns);
    }

    /**
     * Computes the auto-resize dimensions for a group based on child positions.
     * Delegates to {@link GroupLayoutCalculator#computeAutoResizeDimensions}.
     * The labelHeight parameter is retained for caller compatibility but is not
     * forwarded — it is already baked into positions via startY.
     */
    private int[] computeAutoResizeDimensions(List<int[]> positions,
            int padding, int labelHeight) {
        return GroupLayoutCalculator.computeAutoResizeDimensions(positions, padding);
    }

    /**
     * The geometry this session's open batch has already queued, ready for a pass that must not
     * measure a container against the pre-batch {@code getBounds()} it is about to stop having.
     * Empty outside a batch, which is what makes every such pass byte-identical there.
     */
    private Map<String, int[]> sameBatchBounds(String sessionId) {
        return AnchorResolver.seedPending(mutationDispatcher.queuedBounds(sessionId),
                bulkPendingGroupBounds.get());
    }

    // ---- Arrange Groups ----

    // The static spacing fallback for the omitted-spacing path lives on
    // {@link ArrangeGroupsDefaultResolutionDecision#DEFAULT_ARRANGE_GROUPS_SPACING}
    // — the decision record is the single source of truth so JUnit pins +
    // production code stay in lockstep.

    @Override
    public MutationResult<ArrangeGroupsResultDto> arrangeGroups(
            String sessionId, String viewId, String arrangement,
            Integer columns, Integer spacing, List<String> groupIds,
            String direction) {
        logger.info("Arrange groups: viewId={}, arrangement={}, columns={}, spacing={}, groupIds={}, direction={}",
                viewId, arrangement, columns, spacing,
                groupIds != null ? groupIds.size() : "all", direction);
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
                    && !"column".equals(normalizedArrangement)
                    && !"topology".equals(normalizedArrangement)) {
                throw new ModelAccessException(
                        "Invalid arrangement: '" + arrangement
                                + "'. Valid values: grid, row, column, topology.",
                        ErrorCode.INVALID_PARAMETER);
            }

            // 3. Validate spacing (defer resolvedSpacing computation to step
            //    6c — density-aware default-resolution needs targetGroups +
            //    inter-group-connection topology, which are computed below).
            if (spacing != null && spacing < 0) {
                throw new ModelAccessException(
                        "spacing must be non-negative", ErrorCode.INVALID_PARAMETER);
            }

            // 4. Validate columns
            if (columns != null && columns < 1) {
                throw new ModelAccessException(
                        "columns must be positive (>= 1)", ErrorCode.INVALID_PARAMETER);
            }

            // 5. Resolve the containers this call arranges: the view's own top-level targets, plus
            //    any non-target container the caller named explicitly.
            List<IDiagramModelObject> requestedContainers = TopLevelGroupTargets.resolveRequested(
                    model, viewId, view, groupIds);

            // Split by coordinate space, not by type. A container drawn on the view is positioned
            // in canvas coordinates; one drawn inside a host is positioned relative to that host,
            // and putting the two in one grid would place the nested box inside its host while
            // reserving a canvas slot for it.
            List<IDiagramModelObject> targetGroups = new ArrayList<>();
            java.util.Set<String> requestedNestedZoneIds = new java.util.LinkedHashSet<>();
            for (IDiagramModelObject container : requestedContainers) {
                if (container.eContainer() == view) {
                    targetGroups.add(container);
                } else {
                    requestedNestedZoneIds.add(container.getId());
                }
            }
            java.util.Set<String> nestedRestriction =
                    (groupIds == null || groupIds.isEmpty()) ? null : requestedNestedZoneIds;

            // Zones drawn inside a host the predicate declines are arranged in that host's own
            // coordinate space rather than on the canvas, so they are resolved separately from
            // the canvas targets and never enter the grid the loop below computes.
            List<NestedContainerArrangement.HostPlan> hostPlans =
                    java.util.Collections.emptyList();

            if (targetGroups.isEmpty() && NestedContainerArrangement
                    .plan(view, "column", null, 0, DEFAULT_GROUP_PADDING, nestedRestriction)
                    .isEmpty()) {
                // Two different views reach this refusal and they need opposite advice. One
                // holds a host the caller can name straight back in groupIds; the other holds
                // nothing container-shaped at all, and telling THAT caller to name a container
                // sends it looking for an id that does not exist.
                boolean holdsAHost = !TopLevelGroupTargets.describeSkipped(view).isEmpty();
                throw new ModelAccessException(
                        "No top-level groups found in view " + viewId,
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        holdsAHost
                                ? "Name a top-level container in groupIds to arrange it whatever "
                                        + "its type — skippedContainers on a successful call "
                                        + "names them. A container drawn inside such a host is "
                                        + "arranged without being named, inside that host."
                                : "This view holds no container at any depth — not on the canvas "
                                        + "and not inside a host. Add one: a native group via "
                                        + "add-group-to-view, or an ArchiMate Grouping via "
                                        + "add-to-view. Both are arranged.",
                        null);
            }

            // 6b. Topology reordering: reorder targetGroups based on connection density.
            // Also classify qualifying
            // standalone top-level Node/Path/CommunicationNetwork elements connected to ≥ 2
            // of the topology-ordered target groups, and assign each to an inter-group gap.
            // Default empty so direct row/column/grid calls are byte-identical (Gate 2).
            List<ArrangeGroupsStandaloneLane.QualifyingStandaloneElement> qualifyingElements =
                    java.util.Collections.emptyList();
            Map<Integer, List<ArrangeGroupsStandaloneLane.QualifyingStandaloneElement>> gapAssignments =
                    java.util.Collections.emptyMap();
            if ("topology".equals(normalizedArrangement)) {
                // Build adjacency weight matrix from view connections. Attribution runs through
                // the same walk the published inter-container count uses, so the order this
                // arrangement produces and the number the response reports describe one view.
                Map<String, Map<String, Integer>> weights =
                        TopLevelGroupTargets.interContainerWeights(view, targetGroups);

                List<String> groupIds2 = new ArrayList<>();
                for (IDiagramModelObject g : targetGroups) {
                    groupIds2.add(g.getId());
                }

                GroupTopologyOrderer orderer = new GroupTopologyOrderer();
                List<String> orderedIds;
                if (columns != null && columns > 0) {
                    orderedIds = orderer.orderGrid(groupIds2, weights, columns);
                } else {
                    orderedIds = orderer.orderLinear(groupIds2, weights);
                }

                // Reorder targetGroups to match topology order
                Map<String, IDiagramModelObject> groupById = new LinkedHashMap<>();
                for (IDiagramModelObject g : targetGroups) {
                    groupById.put(g.getId(), g);
                }
                List<IDiagramModelObject> reordered = new ArrayList<>();
                for (String id : orderedIds) {
                    IDiagramModelObject g = groupById.get(id);
                    if (g != null) {
                        reordered.add(g);
                    }
                }
                targetGroups = reordered;

                // Topology defaults to column arrangement (unless columns specified → grid)
                if (columns != null && columns > 0) {
                    normalizedArrangement = "grid";
                } else if ("horizontal".equalsIgnoreCase(direction)) {
                    normalizedArrangement = "row";
                } else {
                    normalizedArrangement = "column"; // default: vertical
                }

                // Standalone-element classification: recipe text already promises "between"
                // (application-integration.md:37 + technology-deployment.md:35).
                // Wired ONLY for the row/column axes — topology+columns produces a 2D grid
                // where the "between" semantics are not well-defined, so qualifier
                // classification is skipped (the standalone element keeps its source
                // position; DTO standaloneElementsPlaced stays 0). This makes the silent-
                // drop into a deliberate-skip with an explicit log line for observability.
                if ("row".equals(normalizedArrangement) || "column".equals(normalizedArrangement)) {
                    qualifyingElements = ArrangeGroupsStandaloneLane.classify(
                            view.getChildren(), targetGroups);
                    gapAssignments = ArrangeGroupsStandaloneLane.assignToGaps(
                            qualifyingElements, targetGroups);
                } else {
                    logger.info("Topology+columns → grid: standalone-element lane "
                            + "classification skipped ('between' semantics undefined for 2D grid)");
                }

                logger.info("Topology arrangement: reordered {} groups, using {} layout"
                        + " (standalone qualifiers classified: {})",
                        targetGroups.size(), normalizedArrangement, qualifyingElements.size());
            }
            String reportedArrangement = arrangement.toLowerCase().trim();

            // 6c. Density-aware default-resolution (Story
            //     RoutingPreconditions.InterGroup.DensityAwareDefault).
            //     When spacing is omitted (null) AND the view has at least 2
            //     top-level groups AND the view has at least one inter-group
            //     connection (the Model B trigger), derive a heuristic-
            //     driven default from GroupSpacingHeuristic rather than the
            //     static ArrangeGroupsDefaultResolutionDecision
            //     .DEFAULT_ARRANGE_GROUPS_SPACING. The decision is delegated
            //     to a pure-unit record so the JUnit pin runs without OSGi
            //     context.
            //
            //     Null vs explicit-zero distinction is preserved by the
            //     decision record's `callerProvidedSpacing != null` short-
            //     circuit: explicit spacing (including 0) skips the
            //     heuristic; omitted parameter enters the heuristic path.
            //
            //     Single-source-of-truth shared with the convenience-tool
            //     sibling apply-group-spacing-recommendations (both call
            //     GroupSpacingHeuristic.targetSpacingForConnectionCount with
            //     the Row C hub-aware signature).
            int resolvedSpacing;
            String defaultResolutionReason;
            if (spacing != null) {
                resolvedSpacing = spacing;
                defaultResolutionReason = null;
            } else {
                // Every container THIS call arranges — the canvas ones and the zones it is about
                // to arrange inside their hosts. Scoping this to the canvas half alone made a
                // call that arranges two connected zones report that it had fewer than two
                // containers and that nothing connected them, in the same envelope that listed
                // both of them as arranged.
                List<IDiagramModelObject> arrangedContainers = NestedContainerArrangement
                        .allArrangedContainers(view, targetGroups, nestedRestriction);
                int interGroupConnectionCount = TopLevelGroupTargets
                        .countInterGroupConnections(view, arrangedContainers);
                boolean isConnected = interGroupConnectionCount > 0;
                AssessLayoutResultDto triggerAssessment = assessLayout(viewId);
                // Row C: derive hasLargeHubs upstream through the shared
                // signal, so this default cannot drift from the spacing tools.
                DetectHubElementsResultDto triggerHubResult =
                        detectHubElements(viewId);
                boolean triggerHasLargeHubs =
                        HubSpacingSignal.hasLargeHubs(triggerHubResult);
                ArrangeGroupsDefaultResolutionDecision decision =
                        ArrangeGroupsDefaultResolutionDecision.decide(
                                /*callerProvidedSpacing=*/ null,
                                triggerAssessment.connectionCount(),
                                interGroupConnectionCount,
                                isConnected,
                                arrangedContainers.size() >= 2,
                                triggerHasLargeHubs);
                resolvedSpacing = decision.resolvedSpacing();
                defaultResolutionReason = decision.reason();
            }

            // 7. Compute positions based on arrangement
            int startX = ContainerArrangement.ORIGIN;
            int startY = ContainerArrangement.ORIGIN;
            List<int[]> positions; // [x, y] per group (preserve existing width/height)
            Integer columnsUsed;
            int layoutWidth;
            int layoutHeight;

            // Lane sizes per inter-group
            // gap. Empty for grid + direct row/column calls (gapAssignments is empty in those
            // paths because classifier only runs inside the topology block). Byte-identical
            // back-compat when laneSizes is empty.
            boolean rowLane = "row".equals(normalizedArrangement);
            boolean colLane = "column".equals(normalizedArrangement);
            List<Integer> laneSizes;
            if ((rowLane || colLane) && !gapAssignments.isEmpty()) {
                laneSizes = ArrangeGroupsStandaloneLane.computeLaneSizes(
                        gapAssignments, targetGroups.size(), resolvedSpacing, rowLane);
            } else {
                laneSizes = java.util.Collections.emptyList();
            }

            hostPlans = NestedContainerArrangement.plan(view, normalizedArrangement,
                    columns, resolvedSpacing, DEFAULT_GROUP_PADDING, nestedRestriction);

            ContainerArrangement.Placement placement;
            try {
                placement = ContainerArrangement.compute(targetGroups, normalizedArrangement,
                        columns, resolvedSpacing, laneSizes, startX, startY);
            } catch (IllegalArgumentException e) {
                throw new ModelAccessException(
                        "Unexpected arrangement: " + normalizedArrangement,
                        ErrorCode.INTERNAL_ERROR);
            }
            positions = placement.positions();
            layoutWidth = placement.layoutWidth();
            layoutHeight = placement.layoutHeight();
            columnsUsed = placement.columnsUsed();

            // 8. Build commands — reposition only, preserve width/height
            List<Command> commands = new ArrayList<>();
            for (int i = 0; i < targetGroups.size(); i++) {
                IDiagramModelObject g = targetGroups.get(i);
                IBounds b = g.getBounds();
                int[] pos = positions.get(i);
                commands.add(new UpdateViewObjectCommand(g,
                        pos[0], pos[1], b.getWidth(), b.getHeight()));
            }

            // 8a. Arrange each host's zones in that host's own coordinate space. Same
            // arrangement, same resolved spacing, different frame: a nested object's x/y are
            // stored relative to its immediate parent, so a canvas position written here would
            // put the zone inside its host and leave a canvas slot reserved for nothing.
            for (NestedContainerArrangement.HostPlan plan : hostPlans) {
                if (!plan.fits()) {
                    // Declined whole: a host that cannot hold the arrangement is left alone
                    // rather than half-filled, and is told so through its skipped entry.
                    continue;
                }
                for (int i = 0; i < plan.zones().size(); i++) {
                    IDiagramModelObject zone = plan.zones().get(i);
                    IBounds b = zone.getBounds();
                    int[] pos = plan.positions().get(i);
                    commands.add(new UpdateViewObjectCommand(zone,
                            pos[0], pos[1], b.getWidth(), b.getHeight()));
                }
            }

            // 8b. Place each qualifier centred in its assigned inter-group lane.
            // Shared with computeGroupedLayoutPass via ArrangeGroupsStandaloneLane.placeQualifiers
            // (single source of truth, prevents drift).
            List<ArrangeGroupsStandaloneLane.QualifierPlacement> placements =
                    java.util.Collections.emptyList();
            if ((rowLane || colLane) && !gapAssignments.isEmpty()) {
                List<int[]> groupDims = new ArrayList<>();
                for (IDiagramModelObject g : targetGroups) {
                    IBounds gb = g.getBounds();
                    groupDims.add(new int[]{gb.getWidth(), gb.getHeight()});
                }
                placements = ArrangeGroupsStandaloneLane.placeQualifiers(
                        gapAssignments, targetGroups.size(),
                        positions, groupDims, resolvedSpacing, rowLane);
                for (ArrangeGroupsStandaloneLane.QualifierPlacement p : placements) {
                    commands.add(new UpdateViewObjectCommand(p.element(),
                            p.x(), p.y(), p.width(), p.height()));
                }
            }
            int standaloneElementsPlaced = placements.size();

            // 9. Build compound command
            String label = "Arrange groups ("
                    + reportedArrangement + ", "
                    + targetGroups.size() + " groups"
                    + (standaloneElementsPlaced > 0
                            ? ", " + standaloneElementsPlaced + " standalone" : "")
                    + ")";
            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand(label);
            commands.forEach(compound::add);

            ArrangeGroupsResultDto dto = ArrangeGroupsReport.describe(view, viewId, targetGroups,
                    placements, layoutWidth, layoutHeight, columnsUsed, reportedArrangement,
                    normalizedArrangement, resolvedSpacing, defaultResolutionReason,
                    NestedContainerArrangement.arrangedHostIds(view, hostPlans),
                    NestedContainerArrangement.declinedHostIds(view, hostPlans));

            // 10. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("arrangement", reportedArrangement);
                proposedChanges.put("groupsPositioned", targetGroups.size());
                if (columnsUsed != null) proposedChanges.put("columnsUsed", columnsUsed);
                proposedChanges.put("layoutWidth", layoutWidth);
                proposedChanges.put("layoutHeight", layoutHeight);
                if (standaloneElementsPlaced > 0) {
                    proposedChanges.put("standaloneElementsPlaced", standaloneElementsPlaced);
                }
                ProposalContext ctx = storeAsProposal(sessionId,
                        "arrange-groups",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId), dto, label,
                        null, proposedChanges,
                        "Group arrangement computed and ready for application." + ProposalBuilder.REVIEWED_OR_REJECT);
                return new MutationResult<>(dto, null, ctx);
            }

            // 11. Dispatch or queue
            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
            if (batchSeq != null) {
                return new MutationResult<>(dto, batchSeq);
            }
            versionCounter.incrementAndGet();
            // Read back where each container actually landed. Only on the applied path: a queued
            // call has moved nothing yet, and reporting the computed rectangle there would be the
            // request wearing the outcome's name.
            return new MutationResult<>(dto
                    .withEffectiveGeometry(
                            TopLevelGroupTargets.effectiveGeometryOf(targetGroups))
                    .withNestedContainers(
                            NestedContainerArrangement.effectiveGeometryOf(hostPlans)), null);

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

    // ---- Topology arrangement helpers (Tech Spec 13-2) ----




    // ---- Deletion methods ----

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
                        () -> prepareDeleteElement(elementId),
                        targetIds(elementId), prepared.entity(), description,
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
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<DeleteResultDto> prepared = prepareDeleteRelationship(relationshipId);

            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                DeleteResultDto dto = prepared.entity();
                String cascade = "(cascade: " + dto.viewConnectionsRemoved() + " view connection"
                        + (dto.viewConnectionsRemoved() != 1 ? "s" : "") + ")";
                String description = "Delete " + dto.type() + " " + cascade;
                // name both endpoints, resolved from the still-present relationship
                // (pre-execute); a missing/nameless endpoint degrades to its id (never blank).
                String[] ends = resolveRelationshipEndpointNames(model, relationshipId);
                String effectDescription = (ends != null)
                        ? formatRelationshipEffect("Delete", dto.type(),
                                ends[0] != null ? ends[0] : relationshipId,
                                ends[1] != null ? ends[1] : relationshipId, cascade)
                        : description;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("relationshipId", relationshipId);
                proposedChanges.put("viewConnectionsRemoved", dto.viewConnectionsRemoved());
                ProposalContext ctx = storeAsProposal(sessionId, "delete-relationship",
                        () -> prepareDeleteRelationship(relationshipId),
                        targetIds(relationshipId), prepared.entity(), description,
                        null, proposedChanges, "Relationship ready for deletion with cascade.",
                        effectDescription, null);
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
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                DeleteApprovalCardText.putViewCounts(proposedChanges, dto);
                ProposalContext ctx = storeAsProposal(sessionId, "delete-view",
                        () -> prepareDeleteView(viewId),
                        targetIds(viewId), prepared.entity(),
                        DeleteApprovalCardText.viewDescription(dto),
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
                DeleteApprovalCardText.putFolderCounts(proposedChanges, dto, force);
                ProposalContext ctx = storeAsProposal(sessionId, "delete-folder",
                        () -> prepareDeleteFolder(folderId, force),
                        targetIds(folderId), prepared.entity(),
                        DeleteApprovalCardText.folderDescription(dto, force),
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

    // ---- Specialization helpers ----

    /**
     * Result of resolving a specialization name to a profile.
     */
    record ProfileResolution(IProfile profile, boolean isNew) {}

    /**
     * Resolves a specialization name to an existing or newly created profile.
     *
     * <p>Uses {@code ArchimateModelUtils.getProfileByNameAndType()} for case-insensitive
     * name lookup. If no matching profile exists, creates a new one via
     * {@code IArchimateFactory} but does NOT add it to the model — the
     * {@link ApplySpecializationCommand} handles model addition.</p>
     *
     * <p><strong>Bulk batch deduplication:</strong> when called inside a
     * {@link #executeBulk} prepare phase, consults {@link #bulkProfileCache} first
     * so that multiple operations in the same batch referencing the same new
     * specialization reuse one {@link IProfile} instance. The first call returns
     * {@code isNew=true} (its command will add the profile to the model);
     * subsequent calls return the cached profile with {@code isNew=false} so the
     * profile is added exactly once. {@link ApplySpecializationCommand}'s undo
     * logic already tolerates compound commands that share a new profile across
     * multiple instances.</p>
     *
     * @param model          the ArchiMate model for profile lookup
     * @param specialization the specialization name to resolve
     * @param conceptType    the ArchiMate concept type (e.g., "BusinessActor", "Node")
     * @return a ProfileResolution containing the profile and whether it's new
     */
    private ProfileResolution resolveOrCreateProfile(IArchimateModel model,
            String specialization, String conceptType) {
        Map<String, IProfile> cache = bulkProfileCache.get();
        String cacheKey = (cache != null) ? profileCacheKey(specialization, conceptType) : null;
        if (cache != null) {
            IProfile cached = cache.get(cacheKey);
            if (cached != null) {
                return new ProfileResolution(cached, false);
            }
        }

        IProfile existing = ArchimateModelUtils.getProfileByNameAndType(
                model, specialization, conceptType);
        if (existing != null) {
            if (cache != null) {
                cache.put(cacheKey, existing);
            }
            return new ProfileResolution(existing, false);
        }

        IProfile newProfile = IArchimateFactory.eINSTANCE.createProfile();
        newProfile.setName(InputValidation.reject(specialization, "name"));
        newProfile.setConceptType(conceptType);
        if (cache != null) {
            cache.put(cacheKey, newProfile);
        }
        return new ProfileResolution(newProfile, true);
    }

    /**
     * Builds a normalized key for {@link #bulkProfileCache} lookups. Lowercases
     * the name (matching {@code getProfileByNameAndType}'s case-insensitive
     * semantics) so that case variants of the same specialization name converge
     * on a single cache entry within a bulk-mutate batch.
     */
    private static String profileCacheKey(String name, String conceptType) {
        String n = (name != null) ? name.toLowerCase(java.util.Locale.ROOT) : "";
        String t = (conceptType != null) ? conceptType : "";
        return n + "\u0000" + t;
    }

    /**
     * Cache-aware profile lookup used by prepare-phase methods that need to
     * see profiles still in flight from earlier ops in the same bulk-mutate batch.
     *
     * <p>Order of resolution:
     * <ol>
     *   <li>{@link #bulkProfileCache} (in-flight from prior bulk prepare ops)</li>
     *   <li>{@code ArchimateModelUtils.getProfileByNameAndType()} (committed in model)</li>
     * </ol>
     *
     * <p>Returns {@code null} if neither source has the profile. Used by
     * {@code prepareUpdateSpecialization} and {@code prepareDeleteSpecialization}
     * so that within a single bulk-mutate batch, op N can rename or delete a
     * specialization that op M&lt;N created. Outside of a bulk batch the cache
     * is null and behaviour collapses to a plain model lookup.
     */
    private IProfile findProfileForBulkPrepare(IArchimateModel model,
            String name, String conceptType) {
        Map<String, IProfile> cache = bulkProfileCache.get();
        if (cache != null) {
            IProfile cached = cache.get(profileCacheKey(name, conceptType));
            if (cached != null) {
                return cached;
            }
        }
        return ArchimateModelUtils.getProfileByNameAndType(model, name, conceptType);
    }

    /**
     * Compares two specialization names for duplicate detection (case-insensitive).
     * Null matches only null. Mirrors the lookup semantics of
     * {@code ArchimateModelUtils.getProfileByNameAndType()}.
     */
    private boolean specializationsEqual(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    // ---- Prepare methods for bulk support ----

    /**
     * Prepares a create-element mutation without dispatching.
     * Validates type, creates EMF object, configures properties, resolves folder, builds command.
     */
    private PreparedMutation<ElementDto> prepareCreateElement(String type, String name,
            String documentation, Map<String, String> properties, String folderId,
            String specialization) {
        IArchimateModel model = requireAndCaptureModel();

        EClass eClass = resolveElementType(type);
        IArchimateElement element = (IArchimateElement) IArchimateFactory.eINSTANCE.create(eClass);
        element.setName(InputValidation.reject(name, "name"));
        ConceptMetadata.apply(element, documentation, properties);

        IFolder targetFolder = resolveTargetFolder(model, element, folderId);

        Command cmd;
        String dtoSpecialization = null;
        if (specialization != null && !specialization.isEmpty()) {
            ProfileResolution resolution = resolveOrCreateProfile(
                    model, specialization, eClass.getName());
            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand("Create specialized element");
            compound.add(new ApplySpecializationCommand(
                    element, resolution.profile(), model, resolution.isNew()));
            compound.add(new CreateElementCommand(element, targetFolder));
            cmd = compound;
            dtoSpecialization = resolution.profile().getName();
        } else {
            cmd = new CreateElementCommand(element, targetFolder);
        }

        // Build DTO manually when specialization is set, since the profile-assignment
        // command has not yet executed and convertToElementDto would read null from
        // element.getPrimaryProfile(). Mirrors prepareCreateRelationship pattern.
        ElementDto dto = (dtoSpecialization != null)
                ? DtoMapper.buildElementDtoWithSpecialization(element, dtoSpecialization, DtoMapper.resolveLayer(element))
                : DtoMapper.convertToElementDto(element);

        // The folder was live when it was resolved above, but on a deferred path an operation
        // earlier in the same request can remove it before this command runs — legitimately, since
        // it is genuinely empty at its own turn. Guarding the whole prepared command covers the
        // specialization compound too, whose profile write would otherwise survive a create that
        // never happened. Inert when the folder is still attached, which is every immediate call.
        Command guarded = new RequireAttachedContainerCommand(
                cmd, targetFolder, model, element.getName(), "folder", Wording.CREATE_IN_FOLDER);
        return new PreparedMutation<>(guarded, dto, element.getId(), element);
    }

    /**
     * Prepares a create-relationship mutation by looking up source/target by ID.
     */
    private PreparedMutation<RelationshipDto> prepareCreateRelationship(
            String type, String sourceId, String targetId, String name, String specialization,
            RelationshipSemanticAttributes attrs, String documentation,
            Map<String, String> properties) {
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

        return prepareCreateRelationship(type, relClass, sourceElement, targetElement, name, specialization, attrs,
                documentation, properties, model);
    }

    /**
     * Prepares a create-relationship mutation with direct element references.
     * Used by bulk executor when source/target are back-referenced
     * (not yet in the model).
     */
    private PreparedMutation<RelationshipDto> prepareCreateRelationshipDirect(
            String type, IArchimateElement sourceElement, IArchimateElement targetElement,
            String name, String specialization, RelationshipSemanticAttributes attrs,
            String documentation, Map<String, String> properties) {
        IArchimateModel model = requireAndCaptureModel();
        EClass relClass = resolveRelationshipType(type);
        return prepareCreateRelationship(type, relClass, sourceElement, targetElement, name, specialization, attrs,
                documentation, properties, model);
    }

    /**
     * Shared implementation for relationship preparation.
     */
    private PreparedMutation<RelationshipDto> prepareCreateRelationship(
            String type, EClass relClass, IArchimateElement sourceElement,
            IArchimateElement targetElement, String name, String specialization,
            RelationshipSemanticAttributes attrs, String documentation,
            Map<String, String> properties, IArchimateModel model) {

        // Validate semantic attributes against the resolved relationship class
        // BEFORE creating the EMF object — intentionally runs BEFORE ArchiMate spec-validity
        // (isValidRelationship) AND BEFORE duplicate-detection. Rationale: a misapplied semantic attr
        // (e.g., accessType on CompositionRelationship) is the user's clearest signal that the
        // request is wrong; failing fast with INVALID_PARAMETER beats a spec-validity error
        // that obscures the type mismatch. This is intentional and stricter than specified.
        RelationshipSemantics.validateForCreate(attrs, relClass);

        boolean valid = ArchimateModelUtils.isValidRelationship(
                sourceElement, targetElement, relClass);
        if (!valid) {
            // Composed, not getValidRelationships: that returns EClass[] in the target platform
            String validNames = Arrays.stream(ArchimateModelUtils.getRelationsClasses())
                    .filter(candidate -> ArchimateModelUtils.isValidRelationship(
                            sourceElement.eClass(), targetElement.eClass(), candidate))
                    .map(EClass::getName).collect(Collectors.joining(", "));
            throw new ModelAccessException(
                    type + " is not valid between " + sourceElement.eClass().getName()
                            + " (source) and " + targetElement.eClass().getName()
                            + " (target). Valid types for this combination: " + validNames,
                    ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                    "Valid relationship types: " + validNames,
                    "Try one of the valid types listed above, or use "
                            + "AssociationRelationship which is valid between most elements",
                    "ArchiMate 3.2 specification, relationship rules");
        }

        // Duplicate detection: return existing relationship if (type, source, target,
        // specialization) match. Specialization-aware so a "Data Flow" FlowRelationship and
        // an unspecialized FlowRelationship between the same elements are NOT duplicates.
        Optional<IArchimateRelationship> existing = findDuplicateRelationship(
                relClass, sourceElement, targetElement, specialization);
        if (existing.isPresent()) {
            IArchimateRelationship existingRel = existing.get();
            RelationshipDto base = DtoMapper.convertToRelationshipDto(existingRel, true);
            // Preserve the existing relationship's specialization + semantic attributes
            // in the response DTO (the 6-arg convenience constructor would drop them).
            // (duplicate-detection + semantic-attribute paths)
            RelationshipDto dto = new RelationshipDto(
                    base.id(), base.name(), base.type(),
                    base.specialization(),
                    base.sourceId(), base.targetId(),
                    true, base.documentation(), base.properties(), base.sourceName(), base.targetName(),
                    base.accessType(), base.associationDirected(), base.influenceStrength());
            // No-op command: nothing to execute on the command stack
            return new PreparedMutation<>(new NoOpCommand(), dto, existingRel.getId(), existingRel);
        }

        IArchimateRelationship relationship =
                (IArchimateRelationship) IArchimateFactory.eINSTANCE.create(relClass);
        if (name != null && !name.isBlank()) {
            relationship.setName(InputValidation.reject(name, "name"));
        }
        // Apply semantic attributes to the EMF relationship BEFORE connect().
        // The typed setters (setAccessType / setDirected / setStrength) work the moment the
        // object exists; connect() only wires source/target cross-refs.
        applySemanticAttributesToRelationship(relationship, attrs);
        // Documentation and properties are safe here for the same reason: they touch only the
        // relationship's own fields. The duplicate arm above has already returned, so a create that
        // deduped never reaches this write and the existing relationship is left untouched.
        ConceptMetadata.apply(relationship, documentation, properties);
        // connect() deferred to command execution — prevents orphaned EMF cross-refs
        // if the command never executes (partial failure, approval mode, concurrency race)

        IFolder relationsFolder = model.getFolder(FolderType.RELATIONS);
        Command createRelCmd = new CreateRelationshipCommand(relationship, relationsFolder,
                sourceElement, targetElement);

        Command cmd;
        String dtoSpecialization = null;
        if (specialization != null && !specialization.isEmpty()) {
            ProfileResolution resolution = resolveOrCreateProfile(
                    model, specialization, relClass.getName());
            NonNotifyingCompoundCommand compound =
                    new NonNotifyingCompoundCommand("Create specialized relationship");
            compound.add(new ApplySpecializationCommand(
                    relationship, resolution.profile(), model, resolution.isNew()));
            compound.add(createRelCmd);
            cmd = compound;
            dtoSpecialization = resolution.profile().getName();
        } else {
            cmd = createRelCmd;
        }

        // Build DTO manually since connect() hasn't been called yet (source/target not set on relationship).
        // Populate semantic attributes from the EMF object (which we already mutated above) for
        // the matching subtype; populate as `null` otherwise.
        // Carry the source/target NAMES from the elements in hand (the relationship's own
        // getSource()/getTarget() are null pre-connect) so the approval-card effect text can
        // name the endpoints instead of degrading to ids.
        // Read back off the EMF object and pass it through exactly as the duplicate arm's mapper
        // does, so the same create reports the same shape whether or not it happened to be first.
        String effectiveDoc = relationship.getDocumentation();
        List<Map<String, String>> effectiveProps = DtoMapper.convertProperties(relationship.getProperties());
        RelationshipDto dto = new RelationshipDto(
                relationship.getId(), relationship.getName(), relationship.eClass().getName(),
                dtoSpecialization,
                sourceElement.getId(), targetElement.getId(),
                false, effectiveDoc,
                effectiveProps.isEmpty() ? null : effectiveProps,
                sourceElement.getName(), targetElement.getName(),
                DtoMapper.accessTypeForDto(relationship),
                DtoMapper.associationDirectedForDto(relationship),
                DtoMapper.influenceStrengthForDto(relationship));

        return new PreparedMutation<>(cmd, dto, relationship.getId(), relationship);
    }

    /**
     * Prepares a create-view mutation without dispatching.
     */
    private PreparedMutation<ViewDto> prepareCreateView(String name, String viewpoint,
            String folderId, String connectionRouterType) {
        IArchimateModel model = requireAndCaptureModel();

        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setName(InputValidation.reject(name, "name"));
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

        Command cmd = new RequireAttachedContainerCommand(
                new CreateViewCommand(view, targetFolder), targetFolder, model, name, "folder",
                Wording.CREATE_IN_FOLDER);

        ViewDto dto = DtoMapper.buildViewDto(view, FolderOperations.buildFolderPath(targetFolder));

        return new PreparedMutation<>(cmd, dto, view.getId(), view);
    }

    /**
     * Prepares a clone-view mutation: deep-copies visual layout, references same model elements.
     *
     * <p>Strategy (Option A): Build the complete view tree in memory before creating the
     * command. A single {@link CreateViewCommand} adds the fully-populated view to the
     * folder. EMF containment cascade handles undo (removing the view removes all children
     * and connections).</p>
     *
     * <p><strong>CRITICAL:</strong> View objects reference the SAME underlying model elements
     * and relationships — no copies are created. {@code EcoreUtil.copy()} is intentionally
     * avoided because it deep-copies referenced model elements, breaking model integrity.</p>
     */
    private PreparedMutation<ViewDto> prepareCloneView(String sourceViewId, String newName,
            String folderId) {
        IArchimateModel model = requireAndCaptureModel();

        // 1. Find source view
        EObject sourceObj = ArchimateModelUtils.getObjectByID(model, sourceViewId);
        if (!(sourceObj instanceof IArchimateDiagramModel sourceView)) {
            throw new ModelAccessException(
                    "Source view not found: " + sourceViewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        // 2. Create new view with metadata from source
        IArchimateDiagramModel clonedView = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        clonedView.setName(InputValidation.reject(newName, "name"));
        String vp = sourceView.getViewpoint();
        if (vp != null && !vp.isEmpty()) {
            clonedView.setViewpoint(vp);
        }
        clonedView.setConnectionRouterType(sourceView.getConnectionRouterType());

        // Copy documentation
        String doc = sourceView.getDocumentation();
        if (doc != null && !doc.isEmpty()) {
            clonedView.setDocumentation(doc);
        }

        // Copy custom properties
        for (IProperty prop : sourceView.getProperties()) {
            IProperty newProp = IArchimateFactory.eINSTANCE.createProperty();
            newProp.setKey(prop.getKey());
            newProp.setValue(prop.getValue());
            clonedView.getProperties().add(newProp);
        }

        // 3. Resolve target folder
        IFolder targetFolder;
        if (folderId != null && !folderId.isBlank()) {
            targetFolder = FolderOperations.findFolderById(model, folderId);
            if (targetFolder == null) {
                throw new ModelAccessException(
                        "Folder not found: " + folderId,
                        ErrorCode.FOLDER_NOT_FOUND);
            }
        } else {
            // Same folder as source view
            if (sourceView.eContainer() instanceof IFolder sourceFolder) {
                targetFolder = sourceFolder;
            } else {
                targetFolder = model.getFolder(FolderType.DIAGRAMS);
            }
        }

        // 4. Recursively clone children with ID mapping
        Map<String, IDiagramModelObject> idMapping = new LinkedHashMap<>();
        cloneChildren(sourceView, clonedView, idMapping);

        // 5. Collect deferred connections (can't call connect() during preparation —
        // it fires EMF notifications that cascade to SWT UI thread)
        List<Object[]> deferredConnections = new ArrayList<>();
        collectDeferredConnections(sourceView, idMapping, deferredConnections);

        // 6. Build compound command: create view + wire all connections
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(
                "Clone view: " + newName);
        compound.add(new RequireAttachedContainerCommand(
                new CreateViewCommand(clonedView, targetFolder), targetFolder, model, newName,
                "folder", Wording.CREATE_IN_FOLDER));
        for (Object[] dc : deferredConnections) {
            IDiagramModelConnection conn = (IDiagramModelConnection) dc[0];
            IConnectable src = (IConnectable) dc[1];
            IConnectable tgt = (IConnectable) dc[2];
            if (conn instanceof IDiagramModelArchimateConnection archConn) {
                compound.add(new AddConnectionToViewCommand(archConn, src, tgt));
            } else {
                // Generic connection (e.g., note connection) — must override redo()
                // because disconnect() preserves internal source/target fields and
                // connect() early-returns when they already match (same issue that
                // AddConnectionToViewCommand.redo() handles for archimate connections).
                compound.add(new Command("Connect") {
                    @Override public void execute() { conn.connect(src, tgt); }
                    @Override public void undo() { conn.disconnect(); }
                    @Override public void redo() {
                        conn.setSource(null);
                        conn.setTarget(null);
                        conn.connect(src, tgt);
                    }
                });
            }
        }

        ViewDto dto = DtoMapper.buildViewDto(clonedView, FolderOperations.buildFolderPath(targetFolder));

        return new PreparedMutation<>(compound, dto, clonedView.getId(), clonedView);
    }

    /**
     * Recursively clones children from source container to target container.
     * Builds an ID mapping (source view object ID → cloned view object) for
     * connection resolution.
     */
    private void cloneChildren(IDiagramModelContainer source, IDiagramModelContainer target,
            Map<String, IDiagramModelObject> idMapping) {
        for (IDiagramModelObject child : source.getChildren()) {
            IDiagramModelObject cloned = cloneViewObject(child);
            target.getChildren().add(cloned);
            idMapping.put(child.getId(), cloned);

            // Recurse into containers (groups, element-as-container)
            if (child instanceof IDiagramModelContainer sourceContainer
                    && cloned instanceof IDiagramModelContainer clonedContainer) {
                cloneChildren(sourceContainer, clonedContainer, idMapping);
            }
        }
    }

    /**
     * Creates a clone of a single view object, copying all visual properties.
     * Does NOT recurse into children — that is handled by {@link #cloneChildren}.
     */
    private IDiagramModelObject cloneViewObject(IDiagramModelObject source) {
        if (source instanceof IDiagramModelArchimateObject archObj) {
            IDiagramModelArchimateObject cloned =
                    IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
            cloned.setArchimateElement(archObj.getArchimateElement()); // REFERENCE, not copy
            copyDiagramObjectProperties(source, cloned);
            // figureType / type
            cloned.setType(archObj.getType());
            return cloned;

        } else if (source instanceof IDiagramModelGroup groupObj) {
            IDiagramModelGroup cloned = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
            cloned.setName(groupObj.getName());
            String groupDoc = groupObj.getDocumentation();
            if (groupDoc != null && !groupDoc.isEmpty()) {
                cloned.setDocumentation(groupDoc);
            }
            copyDiagramObjectProperties(source, cloned);
            return cloned;

        } else if (source instanceof IDiagramModelNote noteObj) {
            IDiagramModelNote cloned = IArchimateFactory.eINSTANCE.createDiagramModelNote();
            cloned.setContent(noteObj.getContent());
            copyDiagramObjectProperties(source, cloned);
            return cloned;

        } else {
            // Fail loudly rather than creating an invalid EMF object without a backing
            // model element — an orphan IDiagramModelArchimateObject would NPE on any
            // subsequent access and could corrupt the .archimate file if persisted.
            throw new ModelAccessException(
                    "Cannot clone unsupported view object type: " + source.eClass().getName(),
                    ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Copies visual properties common to all diagram objects:
     * bounds, fill/line/font colors, opacity, line width, font, text alignment,
     * text position, and image properties.
     */
    private void copyDiagramObjectProperties(IDiagramModelObject source, IDiagramModelObject target) {
        // Bounds
        IBounds srcBounds = source.getBounds();
        if (srcBounds != null) {
            target.setBounds(srcBounds.getX(), srcBounds.getY(),
                    srcBounds.getWidth(), srcBounds.getHeight());
        }

        // Colors
        if (source.getFillColor() != null) {
            target.setFillColor(source.getFillColor());
        }
        target.setAlpha(source.getAlpha());

        // Line properties
        if (source instanceof ILineObject srcLine && target instanceof ILineObject tgtLine) {
            if (srcLine.getLineColor() != null) {
                tgtLine.setLineColor(srcLine.getLineColor());
            }
            tgtLine.setLineWidth(srcLine.getLineWidth());
        }

        // Font properties
        if (source instanceof IFontAttribute srcFont && target instanceof IFontAttribute tgtFont) {
            if (srcFont.getFont() != null) {
                tgtFont.setFont(srcFont.getFont());
            }
            if (srcFont.getFontColor() != null) {
                tgtFont.setFontColor(srcFont.getFontColor());
            }
        }

        // Title placement — alignment and position together; see StylingHelper.copyTextFeatures.
        StylingHelper.copyTextFeatures(source, target);

        // Image properties (via ImageHelper pattern)
        ImageHelper.copyImageProperties(source, target);
    }

    /**
     * Collects deferred connections from the source view using the ID mapping.
     * Connections cannot be wired during preparation because {@code connect()} fires
     * EMF notifications that cascade to SWT UI thread. Instead, connection info is
     * collected and wired later inside command execution on the UI thread.
     *
     * <p>Each entry in the list is a 3-element array: [clonedConnection, clonedSource, clonedTarget].</p>
     */
    private void collectDeferredConnections(IDiagramModelContainer sourceContainer,
            Map<String, IDiagramModelObject> idMapping,
            List<Object[]> deferredConnections) {
        for (IDiagramModelObject child : sourceContainer.getChildren()) {
            // Clone source connections for this view object
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                IDiagramModelObject clonedSource = idMapping.get(child.getId());
                IConnectable target = conn.getTarget();
                IDiagramModelObject clonedTarget = (target != null)
                        ? idMapping.get(target.getId()) : null;

                if (clonedSource == null || clonedTarget == null) {
                    logger.warn("Skipping connection clone — source or target not in mapping: "
                            + "source={}, target={}", child.getId(),
                            target != null ? target.getId() : "null");
                    continue;
                }

                IDiagramModelConnection clonedConn;
                if (conn instanceof IDiagramModelArchimateConnection archConn) {
                    IDiagramModelArchimateConnection clonedArchConn =
                            IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
                    clonedArchConn.setArchimateRelationship(
                            archConn.getArchimateRelationship()); // REFERENCE, not copy
                    clonedConn = clonedArchConn;
                } else {
                    // Non-archimate connection (e.g., note connection)
                    clonedConn = IArchimateFactory.eINSTANCE.createDiagramModelConnection();
                    if (conn instanceof ITextContent tc && clonedConn instanceof ITextContent clonedTc) {
                        String text = tc.getContent();
                        if (text != null && !text.isEmpty()) {
                            clonedTc.setContent(text);
                        }
                    }
                }

                // Copy connection visual properties
                copyConnectionProperties(conn, clonedConn);

                // Defer wiring — will be executed on UI thread via command
                deferredConnections.add(new Object[]{
                        clonedConn, clonedSource, clonedTarget});

                // Warn if connection has its own source connections (connection-on-connection).
                // These are not cloned — would require a second pass with a separate
                // IConnectable mapping. Extremely rare in ArchiMate (relationship-to-relationship).
                if (!conn.getSourceConnections().isEmpty()) {
                    logger.warn("Connection-on-connection detected during clone — {} "
                            + "connection(s) on connection {} will not be cloned",
                            conn.getSourceConnections().size(), conn.getId());
                }
            }

            // Recurse into containers
            if (child instanceof IDiagramModelContainer container) {
                collectDeferredConnections(container, idMapping, deferredConnections);
            }
        }
    }

    /**
     * Copies visual properties from source connection to target connection:
     * bendpoints, line color, line width, font, font color, text position,
     * type (line style), opacity, and name visibility.
     */
    private void copyConnectionProperties(IDiagramModelConnection source,
            IDiagramModelConnection target) {
        // Bendpoints (relative coordinates)
        for (IDiagramModelBendpoint bp : source.getBendpoints()) {
            IDiagramModelBendpoint clonedBp =
                    IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
            clonedBp.setStartX(bp.getStartX());
            clonedBp.setStartY(bp.getStartY());
            clonedBp.setEndX(bp.getEndX());
            clonedBp.setEndY(bp.getEndY());
            target.getBendpoints().add(clonedBp);
        }

        // Line properties
        if (source.getLineColor() != null) {
            target.setLineColor(source.getLineColor());
        }
        target.setLineWidth(source.getLineWidth());

        // Font properties
        if (source.getFont() != null) {
            target.setFont(source.getFont());
        }
        if (source.getFontColor() != null) {
            target.setFontColor(source.getFontColor());
        }

        // Text position and line type
        target.setTextPosition(source.getTextPosition());
        target.setType(source.getType());

        // Name visibility (label show/hide) — only on archimate connections
        if (source instanceof IDiagramModelArchimateConnection srcArch
                && target instanceof IDiagramModelArchimateConnection tgtArch) {
            tgtArch.setNameVisible(srcArch.isNameVisible());
        }
    }

    /**
     * Prepares an update-element mutation without dispatching.
     */
    private PreparedMutation<ElementDto> prepareUpdateElement(String id, String name,
            String documentation, Map<String, String> properties, String specialization) {
        IArchimateModel model = requireAndCaptureModel();

        if (name == null && documentation == null && properties == null && specialization == null) {
            throw new ModelAccessException(
                    "No fields to update — provide at least one of: name, documentation, properties, specialization",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Specify name, documentation, properties, or specialization to update",
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

        Command cmd = buildUpdateConceptCommand(element, model, InputValidation.reject(name, "name"), documentation, properties,
                specialization, element.eClass().getName(),
                () -> new UpdateElementCommand(element, InputValidation.reject(name, "name"), documentation, properties));

        return new PreparedMutation<>(cmd, DtoMapper.convertToElementDto(element), element.getId(), element);
    }

    /**
     * Builds the command for updating a concept (element or relationship), handling specialization
     * assign/clear/no-change semantics. When specialization is non-null, wraps the base update
     * command in a compound command together with profile commands for atomic undo.
     */
    private Command buildUpdateConceptCommand(IArchimateConcept concept, IArchimateModel model,
            String name, String documentation, Map<String, String> properties,
            String specialization, String conceptType,
            Supplier<Command> baseCmdSupplier) {
        boolean hasFieldUpdates = name != null || documentation != null || properties != null;

        if (specialization == null) {
            // No change to specialization — return base update command directly
            return baseCmdSupplier.get();
        }

        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand("Update concept with specialization");

        // Always clear existing profiles when specialization is provided (replace semantics)
        if (!concept.getProfiles().isEmpty()) {
            compound.add(new ClearSpecializationCommand(concept));
        }

        if (!specialization.isEmpty()) {
            // Assign new specialization
            ProfileResolution resolution = resolveOrCreateProfile(model, specialization, conceptType);
            compound.add(new ApplySpecializationCommand(
                    concept, resolution.profile(), model, resolution.isNew()));
        }
        // else: empty string means clear-only (already handled above)

        if (hasFieldUpdates) {
            compound.add(baseCmdSupplier.get());
        }

        // Edge case: specialization="" (clear) on a concept with no profiles and no other
        // field updates produces an empty compound. CompoundCommand.canExecute() returns
        // false for empty compounds, and CommandStack.execute() then returns SILENTLY --
        // reporting success having done nothing. Return a NoOpCommand instead.
        if (compound.getCommands().isEmpty()) {
            return new NoOpCommand();
        }

        return compound;
    }

    /**
     * Prepares an update-relationship mutation without dispatching.
     */
    private PreparedMutation<RelationshipDto> prepareUpdateRelationship(String id, String name,
            String documentation, Map<String, String> properties, String specialization,
            RelationshipSemanticAttributes attrs) {
        IArchimateModel model = requireAndCaptureModel();

        boolean hasG1 = attrs != null && attrs.hasAny();
        if (name == null && documentation == null && properties == null && specialization == null
                && !hasG1) {
            throw new ModelAccessException(
                    "No fields to update — provide at least one of: name, documentation, properties, "
                            + "specialization, accessType, associationDirected, influenceStrength",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Specify name, documentation, properties, specialization, accessType, "
                            + "associationDirected, or influenceStrength to update",
                    null);
        }

        EObject obj = ArchimateModelUtils.getObjectByID(model, id);
        if (!(obj instanceof IArchimateRelationship relationship)) {
            throw new ModelAccessException(
                    "Relationship not found: " + id,
                    ErrorCode.RELATIONSHIP_NOT_FOUND,
                    null,
                    "Use search-relationships or get-relationships to find the correct relationship ID",
                    null);
        }

        // Validate semantic attributes against the actual resolved relationship class.
        // Type-conditional rejection at the prepare boundary — never mutate before validation passes.
        RelationshipSemantics.validateForUpdate(attrs, relationship);

        RelationshipSemanticAttributes safeAttrs = (attrs != null) ? attrs : RelationshipSemanticAttributes.NONE;
        Command cmd = buildUpdateConceptCommand(relationship, model, InputValidation.reject(name, "name"), documentation, properties,
                specialization, relationship.eClass().getName(),
                () -> new UpdateRelationshipCommand(relationship, InputValidation.reject(name, "name"), documentation, properties,
                        safeAttrs));

        return new PreparedMutation<>(cmd, DtoMapper.convertToRelationshipDto(relationship, true), relationship.getId(), relationship);
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

        Command cmd = new UpdateViewCommand(view, InputValidation.reject(name, "name"), effectiveViewpoint,
                clearViewpoint, documentation, properties, routerTypeInt);

        return new PreparedMutation<>(cmd, DtoMapper.buildViewDto(view), view.getId(), view);
    }

    /**
     * Prepares an update-model mutation. Mirrors {@link #prepareUpdateView}.
     *
     * <p>Validates that at least one field is provided; rejects empty-string name;
     * converts empty-string purpose into a {@code clearPurpose} flag (mirroring the
     * {@code clearViewpoint} pattern from {@link UpdateViewCommand}).</p>
     *
     * <p><strong>Empty-properties contract:</strong> a non-null but empty {@code properties}
     * map is accepted as a benign no-op (matching "{} means no-op on properties — same as
     * null/omitted, NOT 'clear all'"). The "no fields to update" guard
     * intentionally treats only {@code null} as omitted; an empty map slips through and
     * {@link UpdateModelCommand#mergeProperties} iterates over zero entries (no-op).
     * Cross-LLM review Finding 2 (LOW, acknowledged).</p>
     */
    private PreparedMutation<ModelInfoDto> prepareUpdateModel(String name,
            String purpose, Map<String, String> properties) {
        IArchimateModel model = requireAndCaptureModel();

        if (name == null && purpose == null && properties == null) {
            throw new ModelAccessException(
                    "No fields to update — provide at least one of: name, purpose, properties",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Specify name, purpose, or properties to update",
                    null);
        }

        if (name != null && name.isEmpty()) {
            throw new ModelAccessException(
                    "Model name must not be empty.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a non-empty name, or omit the parameter to leave the name unchanged.",
                    null);
        }

        // Purpose clear semantics: empty string = clear, non-empty = set, null = no change.
        // Cross-ref: empty string preserved by ModelQueryHandler.handleUpdateModel() and
        // ArchiModelAccessorImpl.prepareBulkOperation("update-model").
        boolean clearPurpose = "".equals(purpose);
        String effectivePurpose = clearPurpose ? null : purpose;

        String validated = InputValidation.reject(name, "name");
        Command cmd = new UpdateModelCommand(model, validated, effectivePurpose, clearPurpose,
                properties);
        return new PreparedMutation<>(cmd, getModelInfo().withName(validated), model.getId(), model);
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
     * Rejects the group-layout knobs the three grouped-layout passes share, in the words all three
     * used verbatim before they shared them.
     */
    private static void validateGroupLayoutParams(Integer spacing, Integer padding, Integer columns) {
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
    }

    // ---- View placement prepare methods ----

    /**
     * Wraps a prepared placement so it declines, whole, if the container it was resolved against is
     * no longer attached when it runs.
     *
     * <p>Inert whenever the container is still attached, which is every immediate call. It matters
     * only on the deferred paths, where an operation earlier in the same request can legitimately
     * remove the container after this one was prepared — a view this request deletes, or a group it
     * takes off the view.</p>
     */
    private static Command guardPlacement(Command cmd, IDiagramModelContainer parentContainer,
            IArchimateModel model, String subject) {
        String kind = (parentContainer instanceof IArchimateDiagramModel) ? "view"
                : (parentContainer instanceof IDiagramModelGroup) ? "group" : "element";
        return new RequireAttachedContainerCommand(cmd, parentContainer, model, subject, kind,
                Wording.ADD_TO_CONTAINER);
    }

    /**
     * Wraps a prepared connection so it declines if the endpoint it was resolved against is no
     * longer attached when it runs.
     *
     * <p>A connection has two endpoints and no container, so both are guarded by nesting two of
     * these rather than by teaching the guard to hold a pair. When the outer one declines the inner
     * never runs and contributes no reason, and when only one endpoint went missing only that one
     * speaks — so an operation still reports a single line either way.</p>
     */
    private static Command guardEndpoint(Command cmd, EObject endpoint, IArchimateModel model,
            String subject) {
        return new RequireAttachedContainerCommand(cmd, endpoint, model, subject, "view object",
                Wording.CONNECT_ENDPOINT);
    }

    /**
     * Resolves the view a placement targets, preferring one created earlier in the same request.
     *
     * <p>{@code batchView} is non-null only when an earlier operation in the same batch or bulk call
     * created the view, which is why it wins outright: the created view is not yet reachable by id
     * from the model, so looking it up would fail for a view the request itself is about to add.</p>
     *
     * @param model     the model to resolve {@code viewId} against
     * @param viewId    the caller's view id, ignored when {@code batchView} is supplied
     * @param batchView a view created earlier in the same request, or null
     * @return the view to place into, never null
     */
    private static IArchimateDiagramModel resolveViewOrThrow(IArchimateModel model, String viewId,
            IArchimateDiagramModel batchView) {
        if (batchView != null) {
            return batchView;
        }
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel resolvedView)) {
            throw new ModelAccessException(
                    "View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }
        return resolvedView;
    }

    /**
     * Prepares an add-to-view mutation: validates, creates EMF objects, builds command.
     * When batchParentContainer is non-null, it overrides parentViewObjectId lookup
     * (used for groups created earlier in the same batch).
     * When batchView is non-null, it overrides viewId lookup
     * (used for views created earlier in the same batch).
     * When batchElement is non-null, it overrides elementId lookup
     * (used for elements created earlier in the same batch).
     */
    private PreparedMutation<AddToViewResultDto> prepareAddToView(
            String sessionId, String viewId, String elementId, Integer x, Integer y,
            Integer width, Integer height, boolean autoConnect,
            String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling, ImageParams imageParams,
            IArchimateDiagramModel batchView, IArchimateElement batchElement) {
        IArchimateModel model = requireAndCaptureModel();

        // Validate imagePath against archive — closes the asymmetry
        // created on add-image-to-view. Empty-string is the clear sentinel
        // (see ImageHelper.applyImageToNewObject:82) — must NOT validate it.
        if (imageParams != null
                && imageParams.imagePath() != null
                && !imageParams.imagePath().isEmpty()) {
            validateImagePathExists(model, imageParams.imagePath());
        }

        // Validate x/y both-or-neither
        InputValidation.requireCoordinatePair(x, y);

        // Find view — use batch-created view if available
        IArchimateDiagramModel view = resolveViewOrThrow(model, viewId, batchView);

        // Find element — use batch-created element if available
        IArchimateElement element = batchElement;
        if (element == null) {
            EObject elemObj = ArchimateModelUtils.getObjectByID(model, elementId);
            if (!(elemObj instanceof IArchimateElement resolvedElement)) {
                throw new ModelAccessException(
                        "Element not found: " + elementId,
                        ErrorCode.ELEMENT_NOT_FOUND,
                        null,
                        "Use search-elements to find valid element IDs",
                        null);
            }
            element = resolvedElement;
        }

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer, sessionId);

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

        // Apply styling at creation time
        StylingHelper.applyStylingToNewObject(diagramObj, styling);

        // Apply image at creation time
        ImageHelper.applyImageToNewObject(diagramObj, imageParams);

        // Build view object DTO (include styling; include image fields;
        // include figureType + textAlignment + verticalTextAlignment;
        // includes typography + gradient + borderType + deriveLineColor + outlineOpacity)
        ViewObjectDto viewObjectDto = new ViewObjectDto(
                diagramObj.getId(), element.getId(), element.getName(),
                element.eClass().getName(), resolvedX, resolvedY,
                resolvedWidth, resolvedHeight,
                StylingHelper.readFillColor(diagramObj), StylingHelper.readLineColor(diagramObj),
                StylingHelper.readFontColor(diagramObj), StylingHelper.readOpacity(diagramObj),
                StylingHelper.readLineWidth(diagramObj),
                ImageHelper.readImagePath(diagramObj), ImageHelper.readImagePosition(diagramObj),
                ImageHelper.readShowIcon(diagramObj), null, null,
                StylingHelper.readFigureType(diagramObj),
                StylingHelper.readTextAlignment(diagramObj),
                StylingHelper.readVerticalTextAlignment(diagramObj),
                null,  // labelExpression — set via update path, not add-to-view
                StylingHelper.readFontName(diagramObj),
                StylingHelper.readFontSize(diagramObj),
                StylingHelper.readFontStyle(diagramObj),
                StylingHelper.readGradient(diagramObj),
                StylingHelper.readBorderType(diagramObj),
                StylingHelper.readDeriveLineColor(diagramObj),
                StylingHelper.readOutlineOpacity(diagramObj),
                StylingHelper.readLineStyle(diagramObj), PlacementParent.of(parentContainer));

        Command cmd;
        List<ViewConnectionDto> autoConnections = null;
        Integer skippedAutoConnections = null;
        List<AddToViewResultDto.SkippedConnection> declined = new ArrayList<>(), cappedOut = new ArrayList<>();

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
                // skip orphaned relationships (not in containment tree)
                if (rel.eContainer() == null) continue;

                IArchimateElement targetElement = (IArchimateElement) rel.getTarget();
                IDiagramModelArchimateObject targetViewObj =
                        findViewObjectForElement(viewObjectMap, targetElement.getId());
                if (targetViewObj != null) {
                    // The nesting this very placement is creating already says what the line would
                    // say, and the line would leave the box and re-enter it. The sibling
                    // auto-connect-view has declined this pair since it learned to see it; this
                    // path drew it, on exactly the shape this server's own guidance prescribes.
                    if (AutoConnectSkip.willNestInside(parentContainer, targetViewObj)) {
                        declined.add(AutoConnectSkip.of(diagramObj, targetViewObj, rel, AutoConnectSkip.NESTING));
                    } else if (connectionCount < MAX_AUTO_CONNECTIONS) {
                        eligibleCount++;
                        IDiagramModelArchimateConnection conn =
                                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
                        conn.setArchimateRelationship(rel);
                        compound.add(guardEndpoint(
                                new AddConnectionToViewCommand(conn, diagramObj, targetViewObj),
                                targetViewObj, model, rel.getName()));
                        autoConnections.add(new ViewConnectionDto(
                                conn.getId(), rel.getId(), rel.eClass().getName(),
                                diagramObj.getId(), targetViewObj.getId(), null));
                        connectionCount++;
                    } else {
                        eligibleCount++;
                        cappedOut.add(AutoConnectSkip.of(diagramObj, targetViewObj, rel, AutoConnectSkip.CAP_REACHED));
                    }
                }
            }

            // Scan target relationships
            for (IArchimateRelationship rel : element.getTargetRelationships()) {
                // skip orphaned relationships (not in containment tree)
                if (rel.eContainer() == null) continue;

                IArchimateElement sourceElement = (IArchimateElement) rel.getSource();
                IDiagramModelArchimateObject sourceViewObj =
                        findViewObjectForElement(viewObjectMap, sourceElement.getId());
                if (sourceViewObj != null) {
                    if (AutoConnectSkip.willNestInside(parentContainer, sourceViewObj)) {
                        declined.add(AutoConnectSkip.of(sourceViewObj, diagramObj, rel, AutoConnectSkip.NESTING));
                    } else if (connectionCount < MAX_AUTO_CONNECTIONS) {
                        eligibleCount++;
                        IDiagramModelArchimateConnection conn =
                                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
                        conn.setArchimateRelationship(rel);
                        compound.add(guardEndpoint(
                                new AddConnectionToViewCommand(conn, sourceViewObj, diagramObj),
                                sourceViewObj, model, rel.getName()));
                        autoConnections.add(new ViewConnectionDto(
                                conn.getId(), rel.getId(), rel.eClass().getName(),
                                sourceViewObj.getId(), diagramObj.getId(), null));
                        connectionCount++;
                    } else {
                        eligibleCount++;
                        cappedOut.add(AutoConnectSkip.of(sourceViewObj, diagramObj, rel, AutoConnectSkip.CAP_REACHED));
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

        // Reserve the parent's icon corner if this child would land in it, so the growth and the
        // add are one undo unit. Every no-op case short-circuits inside and returns null, leaving
        // bounds byte-identical. iconBandResizes collects whatever it grew — the container and any
        // group above it — none of which the caller named, so all of it is reported back.
        Map<String, Command> iconBandResizes = new LinkedHashMap<>();
        Command iconBandResize = IconBandReservation.reserve(parentContainer,
                resolvedX, resolvedY, resolvedWidth, resolvedHeight,
                ImageHelper.iconCornerOrNone(diagramObj), DEFAULT_GROUP_PADDING,
                mutationDispatcher.queuedBounds(sessionId), bulkPendingGroupBounds.get(),
                bulkPendingParents.get(), mutationDispatcher.queuedParents(sessionId), iconBandResizes);
        cmd = AnchorResolver.wrapWithIconBandResize(cmd, iconBandResize,
                "Add view object with icon-band parent-resize");
        cmd = RecedeContainerFillCommand.wrap(cmd, parentContainer, styling); // recede null-fill element/group parent (no-op for root view)
        cmd = guardPlacement(cmd, parentContainer, model, element.getName());

        // Method-end (prepare succeeded) so a dropped op leaves no parent behind; see field doc.
        AnchorResolver.recordPendingParent(bulkPendingParents.get(), diagramObj.getId(), parentContainer);
        AddToViewResultDto resultDto = new AddToViewResultDto(
                viewObjectDto, autoConnections, skippedAutoConnections,
                AnchorResolver.projectMoves(iconBandResizes, view), List.copyOf(declined), List.copyOf(cappedOut));
        return new PreparedMutation<>(cmd, resultDto, diagramObj.getId(), diagramObj);
    }

    /**
     * Prepares an add-group-to-view mutation with optional pre-resolved batch parent container.
     * When batchParentContainer is non-null, it overrides parentViewObjectId lookup
     * (used for groups created earlier in the same batch).
     * When batchView is non-null, it overrides viewId lookup
     * (used for views created earlier in the same batch).
     */
    private PreparedMutation<ViewGroupDto> prepareAddGroupToView(
            String sessionId, String viewId, String label, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling, ImageParams imageParams,
            IArchimateDiagramModel batchView) {
        IArchimateModel model = requireAndCaptureModel();

        // Validate label — "" is an untitled group, which Archi holds and renders, so only an absent label is an error. The canonical stored value is "", never null.
        if (label == null) {
            throw new ModelAccessException(
                    "Group label must not be null",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a label for the group; an empty string creates an untitled group",
                    null);
        }

        // Validate x/y both-or-neither
        InputValidation.requireCoordinatePair(x, y);

        // Find view — use batch-created view if available
        IArchimateDiagramModel view = resolveViewOrThrow(model, viewId, batchView);

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer, sessionId);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Interpret escape sequences in group label BEFORE sizing so the
        // helper measures the rendered string (with real newlines) rather than the
        // pre-interpretation escape form. Moved up from after setBounds.
        label = TextUtils.interpretEscapes(InputValidation.reject(label, "label"));

        // Resolve dimensions
        int resolvedWidth = (width != null) ? width : DEFAULT_GROUP_WIDTH;
        int resolvedHeight;
        if (height != null) {
            // Caller-pinned height wins (back-compat).
            resolvedHeight = height;
        } else {
            // Fit label band to wrapped label so long descriptive labels don't clip
            // Subtract horizontal text inset
            // so the wrap simulation uses the actual content width.
            int labelContentWidth = Math.max(1, resolvedWidth - ElementSizer.HORIZONTAL_TEXT_INSET);
            int labelBandHeight = ElementSizer.fitTextBoxHeightToContent(
                    label, labelContentWidth, ElementSizer.LABEL_VERTICAL_PADDING,
                    DEFAULT_GROUP_HEIGHT, ElementSizer.MAX_GROUP_LABEL_BAND);
            // short-circuit: when the label fits in default-height, resolvedHeight
            // is DEFAULT_GROUP_HEIGHT literally (byte-identical to today). Guards against
            // any future helper change silently returning 200+ε for short labels.
            resolvedHeight = (labelBandHeight <= DEFAULT_GROUP_HEIGHT)
                    ? DEFAULT_GROUP_HEIGHT
                    : labelBandHeight;
        }

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

        // Create group
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setName(label);
        group.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Apply styling at creation time
        StylingHelper.applyStylingToNewObject(group, styling);

        // Apply image at creation time
        ImageHelper.applyImageToNewObject(group, imageParams);

        // Build command — recede an unauthored (null-fill) element/group parent gaining this child.
        Command cmd = guardPlacement(RecedeContainerFillCommand.wrap(
                new AddGroupToViewCommand(group, parentContainer), parentContainer, styling),
                parentContainer, model, label);

        // Build DTO (include styling; include image fields;
        // include figureType + textAlignment + verticalTextAlignment)
        ViewGroupDto dto = new ViewGroupDto(
                group.getId(), label, resolvedX, resolvedY,
                resolvedWidth, resolvedHeight, PlacementParent.of(parentContainer), List.of(),
                StylingHelper.readFillColor(group), StylingHelper.readLineColor(group),
                StylingHelper.readFontColor(group), StylingHelper.readOpacity(group),
                StylingHelper.readLineWidth(group),
                ImageHelper.readImagePath(group), ImageHelper.readImagePosition(group),
                ImageHelper.readShowIcon(group),
                StylingHelper.readFigureType(group),
                StylingHelper.readTextAlignment(group),
                StylingHelper.readVerticalTextAlignment(group));

        // Method-end (prepare succeeded) so a dropped op leaves no parent behind; see field doc.
        AnchorResolver.recordPendingParent(bulkPendingParents.get(), group.getId(), parentContainer);
        return new PreparedMutation<>(cmd, dto, group.getId(), group);
    }

    /**
     * Prepares an add-note-to-view mutation with optional pre-resolved batch parent container.
     * When batchParentContainer is non-null, it overrides parentViewObjectId lookup
     * (used for groups created earlier in the same batch).
     * When batchView is non-null, it overrides viewId lookup
     * (used for views created earlier in the same batch).
     * Position-based placement (above-content, below-content).
     */
    private PreparedMutation<ViewNoteDto> prepareAddNoteToView(
            String sessionId, String viewId, String content, String position, Integer gap,
            Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling, ImageParams imageParams,
            IArchimateDiagramModel batchView) {
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

        // Validate position enum value
        if (position != null && !position.equals("above-content")
                && !position.equals("below-content")) {
            throw new ModelAccessException(
                    "Invalid position value: '" + position
                            + "'. Must be 'above-content' or 'below-content'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use position='above-content' for title notes above the diagram, "
                    + "or 'below-content' for notes below",
                    null);
        }

        // Validate x/y both-or-neither (only when position is not set)
        if (position == null) {
            InputValidation.requireCoordinatePair(x, y);
        }

        // Find view — use batch-created view if available
        IArchimateDiagramModel view = resolveViewOrThrow(model, viewId, batchView);

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer, sessionId);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Interpret escape sequences in note content BEFORE sizing so the
        // helper measures the rendered string (with real newlines) rather than the
        // pre-interpretation escape form. Moved up from after setBounds.
        content = TextUtils.interpretEscapes(InputValidation.reject(content, "content"));

        // Resolve dimensions
        int resolvedWidth = (width != null) ? width : DEFAULT_NOTE_WIDTH;
        // When caller did not pin height, fit it to the wrapped content so descriptive
        // title-style notes don't silently clip.
        // Subtract horizontal text inset so the wrap simulation uses the actual content
        // width.
        int noteContentWidth = Math.max(1, resolvedWidth - ElementSizer.HORIZONTAL_TEXT_INSET);
        int resolvedHeight = (height != null) ? height
                : ElementSizer.fitTextBoxHeightToContent(
                        content, noteContentWidth, ElementSizer.LABEL_VERTICAL_PADDING,
                        DEFAULT_NOTE_HEIGHT, ElementSizer.MAX_NOTE_HEIGHT);

        // Resolve position — position-based placement
        int resolvedX;
        int resolvedY;
        String positionNote = null;
        if (position != null) {
            // Position takes precedence over explicit x/y
            if (x != null || y != null) {
                positionNote = "position='" + position + "' takes precedence over explicit x/y coordinates";
            }
            int resolvedGap = (gap != null) ? gap : 10;
            ContentBounds bounds = computeContentBoundsForView(view);
            if (bounds != null) {
                // Content-relative placement uses the same edge helper as view anchoring (byte-identical).
                boolean above = "above-content".equals(position);
                int[] placed = AnchorResolver.resolveByEdge(
                        above ? AnchorResolver.EDGE_ABOVE : AnchorResolver.EDGE_BELOW,
                        bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                        resolvedWidth, resolvedHeight, 0, resolvedGap);
                resolvedX = placed[0];
                resolvedY = placed[1];
            } else {
                // Empty view fallback
                resolvedX = 10;
                resolvedY = 10;
                positionNote = "View has no content — note placed at default position (10, 10)";
            }
        } else if (x != null) {
            resolvedX = x;
            resolvedY = y;
        } else {
            int[] pos = calculateAutoPlacement(view, resolvedWidth, resolvedHeight);
            resolvedX = pos[0];
            resolvedY = pos[1];
        }

        // Note: content was escape-interpreted earlier (before sizing).

        // Create note
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setContent(content);
        note.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Apply styling at creation time
        StylingHelper.applyStylingToNewObject(note, styling);

        // Apply image at creation time
        ImageHelper.applyImageToNewObject(note, imageParams);

        // Build command
        Command cmd = guardPlacement(new AddNoteToViewCommand(note, parentContainer),
                parentContainer, model, "note");

        // Build DTO — include positionNote if set, image fields;
        // include textAlignment + verticalTextAlignment
        // (notes are excluded from figureType)
        String parentVoId = PlacementParent.of(parentContainer);
        StructuredWarningDto corridor = AnnotationCorridorWarning.detect(view, parentContainer,
                resolvedX, resolvedY, resolvedWidth, resolvedHeight,
                AnnotationCorridorWarning.Kind.NOTE, DispatchArm.of(mutationDispatcher.isApprovalRequired(sessionId), isQueuedCall(sessionId)));
        ViewNoteDto dto = new ViewNoteDto(
                note.getId(), content, resolvedX, resolvedY,
                resolvedWidth, resolvedHeight, parentVoId,
                StylingHelper.readFillColor(note), StylingHelper.readLineColor(note),
                StylingHelper.readFontColor(note), StylingHelper.readOpacity(note),
                StylingHelper.readLineWidth(note), positionNote,
                ImageHelper.readImagePath(note), ImageHelper.readImagePosition(note),
                ImageHelper.readShowIcon(note),
                StylingHelper.readTextAlignment(note),
                StylingHelper.readVerticalTextAlignment(note),
                null, null, null, null, null, null, null, null, null, warningsOrNull(corridor));

        // Method-end (prepare succeeded) so a dropped op leaves no parent behind; see field doc.
        AnchorResolver.recordPendingParent(bulkPendingParents.get(), note.getId(), parentContainer);
        return new PreparedMutation<>(cmd, dto, note.getId(), note);
    }

    /**
     * Prepares an add-view-reference-to-view mutation with optional pre-resolved
     * batch parent container + batch view.
     *
     * <p>When {@code batchParentContainer} or {@code batchView} is non-null, it
     * overrides the corresponding ID lookup — used for views/groups created
     * earlier in the same bulk-mutate batch (Stories 9-8 / 10-20).</p>
     *
     * <p>Per Task-0 OUTCOME: cycle (A→B→A) and self-reference (X→X) are
     * intentionally NOT rejected here — Archi's EMF setter accepts them; we
     * mirror per the project validation-sync principle. Cross-cutting agent
     * usage (landscape/index views) often legitimately requires both shapes.</p>
     */
    private PreparedMutation<EmbeddedViewDto> prepareAddViewReferenceToView(
            String sessionId, String viewId, String referencedViewId, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            IArchimateDiagramModel batchView,
            IArchimateDiagramModel batchReferencedView,
            StylingParams styling) {
        IArchimateModel model = requireAndCaptureModel();

        // Validate referencedViewId
        if (referencedViewId == null || referencedViewId.isBlank()) {
            throw new ModelAccessException(
                    "Required parameter 'referencedViewId' is missing or invalid",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a valid view ID. Use get-views to find IDs.",
                    null);
        }

        // Validate x/y both-or-neither
        InputValidation.requireCoordinatePair(x, y);

        // Resolve TARGET view (where the visual is placed) — use batch-created view if available
        IArchimateDiagramModel view = resolveViewOrThrow(model, viewId, batchView);

        // Resolve REFERENCED view (the source view being embedded), preferring one an earlier
        // operation in the same call created — it is not attached to a folder until commit, so the
        // id lookup below cannot see it. Two routes reach that state and neither serves the other:
        // a bulk call hands the object over from its back-reference map, a batch resolves it out of
        // the command queue. Default scope: IArchimateDiagramModel only (mirrors create-view). Kept
        // out of resolveViewOrThrow: that reports the host-view failure, and a caller has to keep
        // being able to tell the two apart.
        IArchimateDiagramModel referencedView = (batchReferencedView != null) ? batchReferencedView
                : mutationDispatcher.queuedCreatedView(sessionId, referencedViewId);
        if (referencedView == null) {
            EObject refViewObj = ArchimateModelUtils.getObjectByID(model, referencedViewId);
            if (!(refViewObj instanceof IArchimateDiagramModel resolvedRef)) {
                throw new ModelAccessException(
                        "Referenced view not found or is not an ArchiMate view: "
                                + referencedViewId,
                        ErrorCode.VIEW_NOT_FOUND,
                        null,
                        "Use get-views to find valid view IDs",
                        null);
            }
            referencedView = resolvedRef;
        }

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer, sessionId);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Resolve dimensions (defaults pinned at 185×80)
        int resolvedWidth = (width != null) ? width : DEFAULT_VIEW_REF_WIDTH;
        int resolvedHeight = (height != null) ? height : DEFAULT_VIEW_REF_HEIGHT;

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

        // Create the typed view-reference visual (verified surface).
        IDiagramModelReference viewRef =
                IArchimateFactory.eINSTANCE.createDiagramModelReference();
        viewRef.setReferencedModel(referencedView);
        viewRef.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Apply styling at creation time — generic IDiagramModelObject path
        // (IDiagramModelReference inherits the full styling surface).
        StylingHelper.applyStylingToNewObject(viewRef, styling);

        // Build command
        Command cmd = guardPlacement(new AddViewReferenceToViewCommand(viewRef, parentContainer),
                parentContainer, model, referencedView.getName());

        // Build DTO — read styling back via the generic IDiagramModelObject readers.
        // referencedViewId reads from the live EMF reference; @JsonInclude(NON_NULL)
        // omits cleanly if Archi clears the cross-ref post-cascade-delete.
        // Note on parentViewObjectId echo: the batch-parent path at the bulk-mutate
        // call site (search "case \"add-view-reference-to-view\"") deliberately
        // passes null for parentViewObjectId so the DTO omits the field — see
        // the cross-model review disposition on that path.
        String resolvedRefViewId = (viewRef.getReferencedModel() != null)
                ? viewRef.getReferencedModel().getId() : null;
        StructuredWarningDto refCorridor = AnnotationCorridorWarning.detect(view, parentContainer,
                resolvedX, resolvedY, resolvedWidth, resolvedHeight,
                AnnotationCorridorWarning.Kind.VIEW_REFERENCE, DispatchArm.of(mutationDispatcher.isApprovalRequired(sessionId), isQueuedCall(sessionId)));
        EmbeddedViewDto dto = new EmbeddedViewDto(
                viewRef.getId(), resolvedRefViewId,
                resolvedX, resolvedY, resolvedWidth, resolvedHeight,
                parentViewObjectId,
                StylingHelper.readFillColor(viewRef),
                StylingHelper.readLineColor(viewRef),
                StylingHelper.readFontColor(viewRef),
                StylingHelper.readOpacity(viewRef),
                StylingHelper.readLineWidth(viewRef),
                StylingHelper.readFontName(viewRef),
                StylingHelper.readFontSize(viewRef),
                StylingHelper.readFontStyle(viewRef),
                StylingHelper.readGradient(viewRef),
                StylingHelper.readDeriveLineColor(viewRef),
                StylingHelper.readOutlineOpacity(viewRef),
                StylingHelper.readLineStyle(viewRef),
                StylingHelper.readTextAlignment(viewRef),
                StylingHelper.readVerticalTextAlignment(viewRef),
                null, warningsOrNull(refCorridor));

        return new PreparedMutation<>(cmd, dto, viewRef.getId(), viewRef);
    }

    // ---- add-image-to-view ----

    /**
     * Prepares an add-image-to-view mutation with optional pre-resolved batch
     * parent container + batch view (mirrors the
     * prepareAddViewReferenceToView shape).
     *
     * <p>Validation order: resolve view → validate parent → validate
     * imagePath exists in archive (NEW {@link #validateImagePathExists}) →
     * read natural dimensions → create EMF object → apply styling →
     * build command.</p>
     *
     * <p>By deliberate design, this method deviates from
     * the {@code project-context.md} validation-sync principle and REJECTS
     * imagePath values that don't resolve in the model archive (Archi GUI
     * would silently render a broken-image placeholder). Rationale in story
     * Dev Notes §"Why we deviate".</p>
     */
    private PreparedMutation<DiagramImageDto> prepareAddImageToView(
            String sessionId, String viewId, String imagePath, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            IArchimateDiagramModel batchView,
            StylingParams styling,
            String borderColor, String documentation) {
        IArchimateModel model = requireAndCaptureModel();

        // Validate imagePath
        if (imagePath == null || imagePath.isBlank()) {
            throw new ModelAccessException(
                    "Required parameter 'imagePath' is missing or invalid",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a valid archive imagePath. Use list-model-images to "
                            + "browse stored images, or add-image-to-model to import a new one.",
                    null);
        }

        // Validate x/y both-or-neither
        InputValidation.requireCoordinatePair(x, y);

        // Resolve TARGET view (where the visual is placed)
        IArchimateDiagramModel view = resolveViewOrThrow(model, viewId, batchView);

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer, sessionId);

        // Strict imagePath validation — REJECT typo'd paths so the
        // failure is loud at the prepare boundary rather than rendered as a
        // broken-image placeholder silently downstream.
        validateImagePathExists(model, imagePath);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Default: resolve natural dimensions from archive bytes; fallback
        // 200×200 if width/height omitted AND archive read fails.
        int resolvedWidth;
        int resolvedHeight;
        if (width != null && height != null) {
            resolvedWidth = width;
            resolvedHeight = height;
        } else {
            int[] natural = tryReadNaturalImageDimensions(model, imagePath);
            if (natural != null) {
                resolvedWidth = (width != null) ? width : natural[0];
                resolvedHeight = (height != null) ? height : natural[1];
            } else {
                resolvedWidth = (width != null) ? width : DEFAULT_IMAGE_VISUAL_WIDTH;
                resolvedHeight = (height != null) ? height : DEFAULT_IMAGE_VISUAL_HEIGHT;
            }
        }

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

        // Create the typed image visual (verified surface).
        IDiagramModelImage image =
                IArchimateFactory.eINSTANCE.createDiagramModelImage();
        image.setImagePath(imagePath);
        image.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Apply styling at creation time — generic IDiagramModelObject path
        // (DiagramModelImage extends DiagramModelObject so all 16
        // styling fields flow through verbatim at the EMF state level).
        StylingHelper.applyStylingToNewObject(image, styling);

        // Follow-up (empirical Step 5 gap): apply IBorderObject
        // borderColor + IDocumentable documentation BEFORE the command runs.
        // These are NOT part of the StylingHelper styling surface (they're typed
        // interfaces specific to IDiagramModelImage's parent set).
        if (borderColor != null) {
            image.setBorderColor(borderColor.isEmpty() ? null : borderColor);
        }
        if (documentation != null) {
            image.setDocumentation(documentation);
        }

        // Build command
        Command cmd = guardPlacement(new AddImageToViewCommand(image, parentContainer),
                parentContainer, model, imagePath);

        // Build DTO — deliberately minimal: bounds + imagePath + parent + the two fields
        // IDiagramModelImage actually surfaces (borderColor via IBorderObject,
        // documentation via IDocumentable). Other styling fields silently dropped
        // by Archi's image renderer are intentionally omitted from the DTO.
        String docs = image.getDocumentation();
        if (docs != null && docs.isEmpty()) docs = null;
        DiagramImageDto dto = new DiagramImageDto(
                image.getId(), imagePath,
                resolvedX, resolvedY, resolvedWidth, resolvedHeight,
                parentViewObjectId,
                image.getBorderColor(),
                docs, warningsOrNull(AnnotationCorridorWarning.detect(view, parentContainer,
                        resolvedX, resolvedY, resolvedWidth, resolvedHeight,
                        AnnotationCorridorWarning.Kind.IMAGE, DispatchArm.of(mutationDispatcher.isApprovalRequired(sessionId), isQueuedCall(sessionId)))));

        return new PreparedMutation<>(cmd, dto, image.getId(), image);
    }

    /**
     * Validates that the supplied {@code imagePath} resolves to bytes in the
     * model archive (strict-validation default).
     *
     * <p>Deliberately deviates from the validation-sync principle
     * to fail loud on agent-typed typo'd paths.</p>
     *
     * @throws ModelAccessException with {@code IMAGE_NOT_FOUND} when miss.
     */
    private void validateImagePathExists(IArchimateModel model, String imagePath) {
        IArchiveManager archiveManager =
                (IArchiveManager) model.getAdapter(IArchiveManager.class);
        if (archiveManager == null) {
            throw new ModelAccessException(
                    "Cannot access model archive to validate imagePath '" + imagePath + "'",
                    ErrorCode.INTERNAL_ERROR, null,
                    "The model has no archive manager attached. Re-open the model in Archi.",
                    null);
        }
        byte[] bytes = archiveManager.getBytesFromEntry(imagePath);
        if (bytes == null) {
            throw new ModelAccessException(
                    "imagePath '" + imagePath
                            + "' does not resolve to an image in the model archive",
                    ErrorCode.IMAGE_NOT_FOUND, null,
                    "Use list-model-images to browse stored images, or "
                            + "add-image-to-model to import a new one.",
                    null);
        }
    }

    /**
     * Reads the natural pixel dimensions of an archive-stored image, or
     * returns {@code null} if the archive read fails (
     * Default — fallback to {@code DEFAULT_IMAGE_VISUAL_*}).
     */
    private int[] tryReadNaturalImageDimensions(IArchimateModel model, String imagePath) {
        return ImageHelper.readNaturalImageDimensions(model, imagePath);
    }

    /**
     * Validates that a relationship's own ends are the elements the two view objects reference,
     * in either orientation, and throws the mismatch error when they are not.
     *
     * <p>Both connection prepares carried this check written out, which is how one came to read its
     * ends by cast and the other through the dispatcher. The ends arrive resolved, so each caller
     * keeps the resolution its path needs — a queued relationship's ends are read off the create
     * that will connect it, a directly-referenced one narrows its own.</p>
     *
     * <p>Either end may be null, and that is a real model shape rather than a defect: ArchiMate
     * permits a relationship whose endpoint is another relationship, which no view object can
     * reference. Such an end cannot match, so it falls to the mismatch throw below — a reason the
     * caller can act on — instead of the cast failure or null dereference that used to surface as
     * an internal error. The detail names each end from whichever of the two is known.</p>
     */
    private void validateConnectionEndpointMatch(String relationshipId,
            IArchimateRelationship relationship, IArchimateElement relSource,
            IArchimateElement relTarget, IDiagramModelArchimateObject sourceViewObj,
            IDiagramModelArchimateObject targetViewObj) {
        IArchimateElement sourceElem = sourceViewObj.getArchimateElement();
        IArchimateElement targetElem = targetViewObj.getArchimateElement();
        boolean bothEnds = relSource != null && relTarget != null;
        boolean forwardMatch = bothEnds && relSource.getId().equals(sourceElem.getId())
                && relTarget.getId().equals(targetElem.getId());
        boolean reversedMatch = bothEnds && relSource.getId().equals(targetElem.getId())
                && relTarget.getId().equals(sourceElem.getId());
        if (forwardMatch || reversedMatch) {
            return;
        }
        throw new ModelAccessException(
                "Relationship '" + relationshipId + "' does not connect the elements "
                        + "referenced by the source and target view objects",
                ErrorCode.RELATIONSHIP_MISMATCH,
                "Relationship connects " + endIdForReport(relSource, relationship.getSource())
                        + " -> " + endIdForReport(relTarget, relationship.getTarget())
                        + ", but view objects reference " + sourceElem.getId()
                        + " and " + targetElem.getId(),
                "Verify the relationship connects the correct elements, "
                        + "or use different view objects",
                null);
    }

    /** One end's id for an error detail: the resolved element, else the raw concept, else nothing. */
    private static String endIdForReport(IArchimateElement resolved, IArchimateConcept raw) {
        IArchimateConcept named = resolved != null ? resolved : raw;
        return named == null ? "nothing" : named.getId();
    }

    /**
     * Resolves one end of a connection to the view object it names, preferring an object an
     * earlier operation in the same request created.
     *
     * <p>Written out at both ends of both connection prepares before this, which is how the four
     * copies drifted to two different suggestions for the same failure. One place stops them
     * drifting again, and keeps the richer hint — the one that names the field the id is read
     * from.</p>
     *
     * <p>The lookup map is supplied rather than built here, because both ends of one connection
     * search the same view and building it per end walked that view's containment twice per call.
     * The direct prepare, handed both ends outright, leaves it empty and walks nothing.</p>
     *
     * @param viewObjects the view's objects by id, searched when no resolved object is supplied
     * @param direct an object the same request created, or null to resolve {@code id} against the view
     * @param id     the caller's view object id
     * @param end    which end this is, naming the failure
     * @return the resolved view object, never null
     */
    private IDiagramModelArchimateObject resolveConnectionEndOrThrow(
            Map<String, IDiagramModelArchimateObject> viewObjects,
            IDiagramModelArchimateObject direct, String id, String end) {
        if (direct != null) {
            return direct;
        }
        IDiagramModelArchimateObject found = findViewObjectById(viewObjects, id);
        if (found == null) {
            throw new ModelAccessException(
                    end + " view object not found: " + id,
                    ErrorCode.VIEW_OBJECT_NOT_FOUND,
                    null,
                    "Use get-view-contents to find valid view object IDs (viewObjectId field in visualMetadata)",
                    null);
        }
        return found;
    }

    /**
     * Prepares an add-connection-to-view mutation: validates, creates EMF objects, builds command.
     */
    private PreparedMutation<ViewConnectionDto> prepareAddConnectionToView(
            String sessionId, String viewId, String relationshipId, String sourceViewObjectId,
            String targetViewObjectId, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            StylingParams styling, Boolean showLabel, Integer textPosition) {
        IArchimateModel model = requireAndCaptureModel();

        // Find view, preferring one this batch queued — otherwise a batch could create a view,
        // place two elements on it and still not connect them, failing here before either endpoint
        // is looked at.
        IArchimateDiagramModel view = resolveViewOrThrow(model, viewId,
                mutationDispatcher.queuedCreatedView(sessionId, viewId));

        // Find relationship, preferring one an earlier operation in this batch queued: its
        // create defers both connect() and the folder attachment to commit, so until then the id
        // the caller was handed names nothing in containment.
        IArchimateRelationship relationship = mutationDispatcher.queuedCreatedRelationship(
                sessionId, relationshipId);
        if (relationship == null) {
            EObject relObj = ArchimateModelUtils.getObjectByID(model, relationshipId);
            if (!(relObj instanceof IArchimateRelationship found)) {
                throw new ModelAccessException(
                        "Relationship not found: " + relationshipId,
                        ErrorCode.RELATIONSHIP_NOT_FOUND,
                        null,
                        "Use get-relationships to find valid relationship IDs",
                        null);
            }
            relationship = found;
        }

        // Find source/target view objects, preferring ones this batch has queued — an add-to-view
        // leaves its object detached until commit, so a batch could not place two elements and
        // connect them without this. A queued object of a kind that cannot be an endpoint resolves
        // to null and takes the ordinary not-found path below.
        Map<String, IDiagramModelArchimateObject> viewObjects = new LinkedHashMap<>();
        collectViewObjectMap(view, viewObjects);
        IDiagramModelArchimateObject sourceViewObj = resolveConnectionEndOrThrow(viewObjects,
                mutationDispatcher.queuedConnectionEnd(sessionId, sourceViewObjectId, view),
                sourceViewObjectId, "Source");
        IDiagramModelArchimateObject targetViewObj = resolveConnectionEndOrThrow(viewObjects,
                mutationDispatcher.queuedConnectionEnd(sessionId, targetViewObjectId, view),
                targetViewObjectId, "Target");

        // Validate relationship-element match (allow both orientations). A queued relationship is
        // not connected until commit, so its ends are read off the create that will connect it.
        validateConnectionEndpointMatch(relationshipId, relationship,
                mutationDispatcher.relationshipSource(sessionId, relationship),
                mutationDispatcher.relationshipTarget(sessionId, relationship),
                sourceViewObj, targetViewObj);

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

        // Convert absolute bendpoints to relative if provided
        List<BendpointDto> effectiveBendpoints = bendpoints;
        if (absoluteBendpoints != null && !absoluteBendpoints.isEmpty()) {
            effectiveBendpoints = ConnectionResponseBuilder.convertAbsoluteToRelative(
                    absoluteBendpoints, sourceViewObj, targetViewObj);
        }

        // Validate styling if provided
        if (styling != null && styling.hasAnyValue()) {
            StylingHelper.validateConnectionStylingParams(styling);
        }

        // Create connection
        IDiagramModelArchimateConnection conn =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(relationship);
        ConnectionResponseBuilder.applyBendpointsToConnection(conn, effectiveBendpoints);

        // Apply styling at creation time
        if (styling != null && styling.hasAnyValue()) {
            if (styling.lineColor() != null) {
                conn.setLineColor(styling.lineColor().isEmpty() ? null : styling.lineColor());
            }
            if (styling.fontColor() != null) {
                conn.setFontColor(styling.fontColor().isEmpty() ? null : styling.fontColor());
            }
            if (styling.lineWidth() != null) {
                conn.setLineWidth(styling.lineWidth());
            }
            // Typography composite + lineStyle bitmask (arrow bits preserved).
            StylingHelper.applyConnectionStyling(conn, styling);
        }

        // Apply label visibility at creation time
        if (showLabel != null) {
            conn.setNameVisible(showLabel);
        }

        // Apply label position at creation time
        if (textPosition != null) {
            conn.setTextPosition(textPosition);
        }

        Command cmd = guardEndpoint(guardEndpoint(
                new AddConnectionToViewCommand(conn, sourceViewObj, targetViewObj),
                targetViewObj, model, relationship.getName()),
                sourceViewObj, model, relationship.getName());

        // Build DTO with styling info included in response (includes typography;
        // lineStyle is view-object-only — not surfaced on connection DTOs).
        String dtoLineColor = StylingHelper.readConnectionLineColor(conn);
        String dtoFontColor = StylingHelper.readConnectionFontColor(conn);
        Integer dtoLineWidth = StylingHelper.readConnectionLineWidth(conn);
        Boolean dtoNameVisible = StylingHelper.readConnectionNameVisible(conn);
        String dtoFontName = StylingHelper.readConnectionFontName(conn);
        Integer dtoFontSize = StylingHelper.readConnectionFontSize(conn);
        String dtoFontStyle = StylingHelper.readConnectionFontStyle(conn);

        ViewConnectionDto baseDto = ConnectionResponseBuilder.buildConnectionResponseDto(
                conn.getId(), relationship, sourceViewObjectId, targetViewObjectId,
                effectiveBendpoints, sourceViewObj, targetViewObj, conn.getTextPosition());

        ViewConnectionDto dto = ConnectionResponseBuilder.withConnectionStyling(baseDto,
                dtoLineColor, dtoLineWidth, dtoFontColor, dtoNameVisible,
                dtoFontName, dtoFontSize, dtoFontStyle);

        return new PreparedMutation<>(cmd, dto, conn.getId(), conn);
    }

    // ---- View editing/removal prepare methods ----

    /**
     * Prepares an update-view-object mutation: validates, reads current bounds,
     * merges with provided values, builds command and DTO.
     *
     * <p>{@code batchViewObject} pre-resolves the target for an object an open batch queued but has
     * not attached yet. Such an object is real and addressable by id, yet reachable neither by the
     * live lookup below nor through {@code eContainer()}, because its add command executes at
     * commit. Supplying it therefore serves two purposes: it names the object to update, and it
     * carries the container the parent-fit cascade would otherwise not find. Null on every path
     * that is not inside a batch, which is what keeps those paths byte-identical.</p>
     *
     * <p>{@code queuedParents} carries that answer for every <em>other</em> queued object, which the
     * cascade's ancestor walk needs once it climbs into groups that are themselves still detached;
     * {@code queuedBounds} what it has already re-sized; {@code queuedAnchors} the anchors it has
     * declared but not yet written, which is how the cascade below finds objects that must follow
     * this one; {@code batchAnchorTarget} an anchor target it built but has not attached. All null
     * outside a batch, which is what keeps every non-batch prepare byte-identical. And
     * {@code passPendingBounds}, when non-null, <em>is</em> a whole pass's working map rather than a
     * fresh copy per call, so objects prepared into one compound fit a shared container against what
     * the earlier ones established instead of each measuring it at its pre-pass size and emitting a
     * competing absolute resize the last writer wins — {@code prepareUpdateViewObjectDirect}'s
     * {@code passFitBounds}, in the prepare that had no such parameter. Null is the per-call copy.</p>
     *
     * <p>{@code passFitCommands} is that same asymmetry closed for the resize <em>commands</em>, and
     * mirrors {@code prepareUpdateViewObjectDirect}'s parameter of that name: non-null it IS the
     * cascade's command map for a whole pass, so the caller commits one resize per group once every
     * object has landed, and this method then neither wraps those resizes into the returned command
     * nor projects them into its DTO — a pass map names every container the pass grew, so reporting
     * it per object would name them all on each one, at a view walk per entry. Null is the per-call
     * copy, reported beside the object it belongs to.</p>
     */
    private PreparedMutation<ViewObjectDto> prepareUpdateViewObject(
            String viewObjectId, Integer x, Integer y, Integer width, Integer height,
            String text, StylingParams styling, ImageParams imageParams,
            String labelExpression, String anchorTarget, String anchorEdge, Integer anchorDx, Integer anchorDy,
            QueuedViewObject batchViewObject, QueuedViewObject batchAnchorTarget, Map<String, IDiagramModelContainer> queuedParents, Map<String, int[]> queuedBounds, Map<String, String[]> queuedAnchors, Map<String, int[]> passPendingBounds, Map<String, Command> passFitCommands) {
        IArchimateModel model = requireAndCaptureModel();

        // Validate hex colours before any other processing
        if (styling != null) {
            StylingHelper.validateStylingParams(styling);
        }

        // Validate image params before any other processing
        if (imageParams != null) {
            ImageHelper.validateImageParams(imageParams);
            // Validate imagePath against archive — closes the asymmetry
            // created on add-image-to-view. Empty-string is the clear sentinel
            // (see ImageHelper.applyImageToNewObject:82) — must NOT validate it.
            if (imageParams.imagePath() != null
                    && !imageParams.imagePath().isEmpty()) {
                validateImagePathExists(model, imageParams.imagePath());
            }
        }

        // Validate at least one field provided (including styling; image; labelExpression)
        boolean hasStyling = styling != null && styling.hasAnyValue();
        boolean hasImage = imageParams != null && imageParams.hasAnyValue();
        boolean hasLabelExpression = labelExpression != null;
        if (x == null && y == null && width == null && height == null && text == null && !hasStyling && !hasImage && !hasLabelExpression && anchorTarget == null) {
            throw new ModelAccessException(
                    "At least one of x, y, width, height, text, styling, image, labelExpression, or anchorTarget parameter must be provided",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "At least one of x, y, width, height, text, fillColor, lineColor, fontColor, opacity, lineWidth, imagePath, imagePosition, showIcon, labelExpression, anchorTarget must be provided.",
                    null);
        }

        // Find view object — accept element objects, groups, and notes. A same-batch queued target
        // is supplied directly; the live walk cannot see it until its add command executes.
        EObject obj = (batchViewObject != null) ? batchViewObject.object() : ArchimateModelUtils.getObjectByID(model, viewObjectId);
        if (!(obj instanceof IDiagramModelObject diagramObj)) {
            throw new ModelAccessException(
                    "View object not found: " + viewObjectId,
                    ErrorCode.VIEW_OBJECT_NOT_FOUND,
                    null,
                    BulkBackReferences.VIEW_TARGET_ADVICE,
                    null);
        }

        text = TextUtils.acceptTextFor(diagramObj, text);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Merge requested bounds with current; anchor resolves x/y from target. Prefer same-batch pending bounds — the bulk pass's, else what the open batch has queued — over the stale getBounds(), for this object AND for the anchor target; empty outside a batch → byte-identical.
        Map<String, int[]> sameBatchBounds = (passPendingBounds != null) ? passPendingBounds : AnchorResolver.seedPending(queuedBounds, bulkPendingGroupBounds.get());
        int[] merged = AnchorResolver.mergeBounds(diagramObj, x, y, width, height, anchorTarget, anchorEdge, anchorDx, anchorDy, AnchorResolver.pendingBounds(sameBatchBounds, viewObjectId), sameBatchBounds, batchViewObject, batchAnchorTarget, queuedParents, bulkPendingParents.get());
        int mergedX = merged[0], mergedY = merged[1], mergedWidth = merged[2], mergedHeight = merged[3];

        // Two height adjustments at the MUTATION moment the caller never named: a note whose text or width
        // changed re-fits to its wrapped content, and a bottom-corner `imagePosition` grows a CONTAINER so its
        // icon clears the children. They cannot both fire — a note is not an IDiagramModelContainer — so the
        // order is defensive. Both feed the parent-fit cascade below, which re-cascades up to a grandparent.
        int refitHeight = TextUtils.refitNoteHeight(diagramObj, text, width, height, mergedWidth, mergedHeight, DEFAULT_NOTE_HEIGHT);
        int iconBandHeight = ImageHelper.iconBandGrownHeight(diagramObj, imageParams, mergedWidth, refitHeight, ImageHelper.ICON_SIZE, ImageHelper.ICON_MARGIN);
        boolean heightAdjusted = iconBandHeight != mergedHeight;
        mergedHeight = iconBandHeight;

        // Build command (with optional text, styling, image, label-expression, and anchor update)
        Command cmd = new UpdateViewObjectCommand(diagramObj, mergedX, mergedY, mergedWidth, mergedHeight, text, styling, imageParams, InputValidation.reject(labelExpression, "labelExpression"), anchorTarget, anchorEdge, anchorDx, anchorDy);

        // Post-command-build parent-bounds check on the raw update-view-object
        // path. Sibling-symmetric with the post-autoNudge pass in
        // auto-route-connections and the post-spacing-tool pass in
        // adjust-view-spacing — together they make ParentFitCascade the single
        // source of truth across the three convenience-tool layers
        // (autoNudge / spacing / update-view-object).
        //
        // Gate: only run when the caller explicitly modified bounds OR the
        // block above changed mergedHeight — a note re-fit or an icon-band grow —
        // without the caller passing a bounds field, so the grandparent-group cascade
        // still fires for those (else a re-fitted note hangs outside its group). Skips no-op styling-only updates so
        // pre-existing overflow from prior workflow steps is not silently
        // "fixed" as a side effect.
        //
        // When overflow is detected, the user's UpdateViewObjectCommand and the
        // helper's parent-resize commands are wrapped into a single
        // NonNotifyingCompoundCommand so they execute as one undo step.
        boolean boundsModified = (x != null) || (y != null) || (width != null) || (height != null) || heightAdjusted || (anchorTarget != null && !anchorTarget.isEmpty());
        Map<String, int[]> cascadeGroupBounds = null; // post-cascade group bounds, recorded at method end
        // Two things this call changes that the caller never named, each recorded where it happens
        // and reported from the commands actually emitted rather than from any bounds map.
        Map<String, Command> groupResizeCommands = (passFitCommands != null) ? passFitCommands : new LinkedHashMap<>();
        Map<String, Command> anchoredMoves = new LinkedHashMap<>();
        // Anchored children resolve FIRST: where they land is an outcome of this same call, and the
        // fit below has to measure those rectangles or it sizes the group to the object the caller
        // named while an object the call moved hangs outside it. Their moves ride inside this
        // compound; the group resizes wrap it afterwards, so a fit provoked by a displaced child is
        // still carried by the command that executes rather than only by the report.
        cmd = AnchorResolver.wrapAnchoredChildren(cmd, diagramObj, mergedX, mergedY, mergedWidth, mergedHeight, boundsModified, queuedAnchors, sameBatchBounds, null, anchoredMoves);
        if (boundsModified) {
            // The seeded copy IS the cascade's working map, so a fit returns it and no fit returns null.
            cascadeGroupBounds = ParentFitCascade.fitAround(
                    QueuedViewObject.containerOf(diagramObj, batchViewObject), diagramObj,
                    mergedX, mergedY, mergedWidth, mergedHeight, DEFAULT_GROUP_PADDING,
                    sameBatchBounds, groupResizeCommands, anchoredMoves,
                    bulkPendingParents.get(), queuedParents);
            if (passFitCommands == null) cmd = AnchorResolver.wrapWithGroupResizes(cmd, groupResizeCommands); else AnchorResolver.retireCoveredResize(passFitCommands, viewObjectId, mergedX, mergedY, mergedWidth, mergedHeight); // one decision, two arms: who owns the resizes, and therefore who must retire one this object has already satisfied
        }

        // Build DTO — generic for all view object types (include post-execution styling; image;
        // include post-execution figureType + textAlignment + verticalTextAlignment;
        // includes typography + gradient + borderType + deriveLineColor + outlineOpacity)
        StylingHelper.PostStyling post = StylingHelper.computePostStyling(diagramObj, styling);
        String dtoFigureType = StylingHelper.computePostStylingFigureType(StylingHelper.readFigureType(diagramObj), styling != null ? styling.figureType() : null);
        String dtoTextAlignment = StylingHelper.computePostStylingTextAlignment(StylingHelper.readTextAlignment(diagramObj), styling != null ? styling.textAlignment() : null);
        String dtoVerticalTextAlignment = StylingHelper.computePostStylingVerticalTextAlignment(StylingHelper.readVerticalTextAlignment(diagramObj), styling != null ? styling.verticalTextAlignment() : null);

        // Compute post-execution image fields
        String dtoImagePath = ImageHelper.computePostImagePath(diagramObj, imageParams);
        String dtoImagePosition = ImageHelper.computePostImagePosition(diagramObj, imageParams);
        String dtoShowIcon = ImageHelper.computePostShowIcon(diagramObj, imageParams);
        // computeImageCoverage already answers CoverageReport.NONE (null, null) for an absent path,
        // which is exactly what the guard this replaces reproduced by hand.
        ImageHelper.CoverageReport dtoCoverage = computeImageCoverage(
                dtoImagePath, mergedWidth, mergedHeight, dtoImagePosition);
        Double dtoCoveragePercent = dtoCoverage.percent();
        String dtoCoverageWarning = dtoCoverage.warning();

        // Post-execution label expression, read from the IFeatures store.
        // The command captured the old value at construction; the new value (after emptyToNull
        // normalization) is what Archi will hold after dispatch. We compute the would-be
        // post-state here for the DTO without executing the command — empty string → null
        // (clears), non-null → the new value verbatim, null → existing value unchanged.
        String dtoLabelExpression = computePostLabelExpression(diagramObj, labelExpression);
        AnchorResolver.AnchorInfo anchor = AnchorResolver.computePostAnchor(diagramObj, anchorTarget, anchorEdge, anchorDx, anchorDy);

        // A group or note has no element association, so it names and types itself. A group's name is
        // exactly what `text` writes, so report the post-write value, not the prepare-time read.
        IArchimateElement element = (diagramObj instanceof IDiagramModelArchimateObject archObj)
                ? archObj.getArchimateElement() : null;
        ViewObjectDto dto = new ViewObjectDto(
                viewObjectId,
                (element != null) ? element.getId() : null,
                (element != null) ? element.getName() : (text != null && diagramObj instanceof IDiagramModelGroup) ? text : diagramObj.getName(),
                (element != null) ? element.eClass().getName() : diagramObj.eClass().getName(),
                mergedX, mergedY,
                mergedWidth, mergedHeight,
                post.fillColor(), post.lineColor(), post.fontColor(), post.opacity(), post.lineWidth(),
                dtoImagePath, dtoImagePosition, dtoShowIcon,
                dtoCoveragePercent, dtoCoverageWarning,
                dtoFigureType, dtoTextAlignment, dtoVerticalTextAlignment,
                dtoLabelExpression,
                post.fontName(), post.fontSize(), post.fontStyle(),
                post.gradient(), post.borderType(), post.deriveLineColor(), post.outlineOpacity(), post.lineStyle(), anchor.target(), anchor.edge(), anchor.dx(), anchor.dy(), PlacementParent.liveOf(diagramObj),
                AnchorResolver.projectMoves(anchoredMoves, diagramObj.getDiagramModel()),
                AnchorResolver.projectMoves((passFitCommands != null) ? null : groupResizeCommands, diagramObj.getDiagramModel()));

        // Record geometry (explicit set + cascade grows) for EVERY view object so a later same-batch re-edit inherits it; method-end → a dropped op leaves no phantom floor.
        AnchorResolver.recordEffective((passPendingBounds != null) ? passPendingBounds : bulkPendingGroupBounds.get(), cascadeGroupBounds, viewObjectId, boundsModified, mergedX, mergedY, mergedWidth, mergedHeight);

        return new PreparedMutation<>(cmd, dto, viewObjectId);
    }

    /**
     * Computes the post-execution label-expression value for the DTO from the
     * pre-state on the diagram object and the requested new value. Mirrors the
     * normalization performed inside {@link UpdateViewObjectCommand}: null leaves
     * the current value unchanged, empty string clears (DTO shows null), non-null
     * sets verbatim.
     */
    private static String computePostLabelExpression(IDiagramModelObject diagramObj,
                                                      String requestedLabelExpression) {
        if (requestedLabelExpression == null) {
            // "No change" branch: read the current stored value. We treat empty-string-stored
            // as absent (returning null) for symmetry — this plugin always uses remove(...)
            // on clear via UpdateViewObjectCommand.applyLabelExpression, so an empty-string
            // feature cannot arise from this codepath; the isEmpty() guard is defensive only.
            String current = diagramObj.getFeatures().getString("labelExpression", null);
            return (current == null || current.isEmpty()) ? null : current;
        }
        if (requestedLabelExpression.isEmpty()) {
            return null;
        }
        return requestedLabelExpression;
    }

    /**
     * Prepares an update-view-object mutation from a resolved object rather than an id, for an
     * object an earlier operation in the same call created and whose add command has not executed
     * yet — the id lookup cannot see it.
     *
     * <p>Takes {@link IDiagramModelObject} rather than the element-backed subtype so a group an
     * earlier operation in the same call created can be updated too. A group has no associated
     * element, so the DTO's element fields fall back to the object's own, exactly as the
     * non-direct prepare does.</p>
     *
     * @param text            group label or note content; rejected for an element-backed object,
     *                        matching the non-direct path, since an element is named through
     *                        update-element
     * @param labelExpression null leaves it unchanged, empty clears it, non-null sets the
     *                        per-view-object dynamic label template
     * @param passFitBounds   pass-scoped parent-fit bounds map, or null for a call-scoped one
     * @param passFitCommands pass-scoped parent-fit commands; non-null means the CALLER commits them
     */
    private PreparedMutation<ViewObjectDto> prepareUpdateViewObjectDirect(
            String sessionId, IDiagramModelObject diagramObj, Integer x, Integer y,
            Integer width, Integer height, String text, StylingParams styling, ImageParams imageParams,
            String labelExpression, Map<String, int[]> passObjectBounds, Map<String, int[]> passFitBounds, Map<String, Command> passFitCommands, Map<String, Command> passAnchorMoves) {

        // Validate styling colours before other processing
        if (styling != null) {
            StylingHelper.validateStylingParams(styling);
        }

        // Validate image params
        if (imageParams != null) {
            ImageHelper.validateImageParams(imageParams);
            // Validate imagePath against archive — closes the asymmetry
            // created on add-image-to-view. Empty-string is the clear sentinel
            // (see ImageHelper.applyImageToNewObject:82) — must NOT validate it.
            // Bulk back-ref overload: the upstream bulk dispatcher already called
            // requireAndCaptureModel() at executeBulk entry, so calling it again here
            // is idempotent and returns the session model. Deriving via
            // diagramObj.getDiagramModel() would NPE for a freshly-allocated back-ref
            // view object whose add-to-view command has not yet executed in Phase 2
            // (eContainer() is null until commit).
            if (imageParams.imagePath() != null
                    && !imageParams.imagePath().isEmpty()) {
                IArchimateModel model = requireAndCaptureModel();
                validateImagePathExists(model, imageParams.imagePath());
            }
        }

        text = TextUtils.acceptTextFor(diagramObj, text);

        // Validate at least one field provided
        boolean hasStyling = styling != null && styling.hasAnyValue();
        boolean hasImage = imageParams != null && imageParams.hasAnyValue();
        boolean hasLabelExpression = labelExpression != null;
        if (x == null && y == null && width == null && height == null && text == null && !hasStyling && !hasImage && !hasLabelExpression) {
            throw new ModelAccessException(
                    "At least one of x, y, width, height, text, styling, image, or labelExpression parameter must be provided",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "At least one of x, y, width, height, text, fillColor, lineColor, fontColor, opacity, lineWidth, imagePath, imagePosition, showIcon, labelExpression must be provided.",
                    null);
        }

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Read current bounds and merge; prefer this object's pending same-unit-of-work bounds over the stale getBounds() (prepares run pre-execute). The pass map, when the caller owns one, already carries the bulk map's contents plus everything this pass has written — including an earlier mutation of THIS object in THIS call, which a partial later one would otherwise revert.
        int[] pendingDirect = (passObjectBounds != null) ? AnchorResolver.pendingBounds(passObjectBounds, diagramObj.getId()) : AnchorResolver.pendingBounds(bulkPendingGroupBounds.get(), diagramObj.getId());
        IBounds bounds = diagramObj.getBounds();
        int mergedX = (x != null) ? x : (pendingDirect != null ? pendingDirect[0] : bounds.getX());
        int mergedY = (y != null) ? y : (pendingDirect != null ? pendingDirect[1] : bounds.getY());
        int mergedWidth = (width != null) ? width : (pendingDirect != null ? pendingDirect[2] : bounds.getWidth());
        int mergedHeight = (height != null) ? height : (pendingDirect != null ? pendingDirect[3] : bounds.getHeight());

        // Note text re-fit then icon-band grow, shared with `prepareUpdateViewObject` so the same request means the same
        // thing on both paths. LIVE, not a forward guard: the bulk back-ref arm hands a note to THIS prepare whenever an update-view-object names one the same call created — the only route to a note whose add command has not executed yet.
        int refitHeight = TextUtils.refitNoteHeight(diagramObj, text, width, height, mergedWidth, mergedHeight, DEFAULT_NOTE_HEIGHT);
        int iconBandHeight = ImageHelper.iconBandGrownHeight(diagramObj, imageParams, mergedWidth, refitHeight, ImageHelper.ICON_SIZE, ImageHelper.ICON_MARGIN);
        boolean heightAdjusted = iconBandHeight != mergedHeight;
        mergedHeight = iconBandHeight;

        // Build command (with optional text, styling, image, and label-expression update)
        Command cmd = new UpdateViewObjectCommand(diagramObj, mergedX, mergedY, mergedWidth, mergedHeight,
                text, styling, imageParams, InputValidation.reject(labelExpression, "labelExpression"));

        // Post-command-build parent-group auto-fit, mirroring prepareUpdateViewObject so the same
        // move means the same thing on both update-view-object dispatcher branches. Inside a bulk
        // batch this object may still be DETACHED (its add-to-view command has not executed), so
        // eContainer() falls back to the container the batch recorded for it. The fit is seeded
        // from — and folded back into — the batch's pending bounds, so an explicit same-batch group
        // size is a floor and a grow here is visible to later ops. The height-adjusted OR-in: a note
        // re-fit or an icon-band grow must cascade even when the caller passed no bounds field.
        // A caller preparing MANY mutations before any executes owns the fit maps for its whole pass —
        // its children then accumulate into ONE resize per group instead of competing; it commits them.
        boolean boundsModified = (x != null) || (y != null) || (width != null) || (height != null) || heightAdjusted;
        Map<String, int[]> cascadeGroupBounds = null; // post-cascade group bounds, folded in at method end
        // Reported only when THIS call owns the maps. When a pass owns them they accumulate across
        // every object the pass touches and the pass does its own projection, so reporting them
        // per-object here would name every container the whole pass grew on each one of them.
        Map<String, Command> ownedResizes = null;
        Map<String, Command> ownedMoves = (passAnchorMoves == null) ? new LinkedHashMap<>() : null;

        // Anchored-children cascade, mirroring prepareUpdateViewObject: an object this path re-sizes
        // may be somebody's anchor TARGET, and until now only the other update path repositioned them,
        // so the same growth meant two different things depending on which branch delivered it. A pass
        // that re-sizes one target several times owns the move map, which keys by anchored object so
        // the pass emits ONE reposition per child — the last, computed from the target's final bounds —
        // instead of a stack of competing absolute moves in a single compound.
        // Resolved BEFORE the fit for the same reason as on the other update path: the fit has to
        // measure the rectangles these children land on, or the group is sized to the object the
        // caller named while an object this call moved hangs outside it.
        cmd = AnchorResolver.wrapAnchoredChildren(cmd, diagramObj, mergedX, mergedY, mergedWidth, mergedHeight, boundsModified, mutationDispatcher.queuedAnchors(sessionId), passObjectBounds, passAnchorMoves, ownedMoves);
        if (boundsModified) {
            EObject container = diagramObj.eContainer();
            if (container == null) container = AnchorResolver.pendingParent(bulkPendingParents.get(), diagramObj.getId());
            Map<String, int[]> virtualGroupBounds = (passFitBounds != null) ? passFitBounds : AnchorResolver.seedPending(bulkPendingGroupBounds.get());
            Map<String, Command> groupResizeCommands = (passFitCommands != null) ? passFitCommands : new LinkedHashMap<>();
            // ownedMoves is null exactly when a pass owns the move map, and then the moves are still
            // accumulating across the pass — the owner drives the second fit once, after its last
            // prepare, rather than each prepare fitting an intermediate landing that grow-only
            // arithmetic could never take back.
            cascadeGroupBounds = ParentFitCascade.fitAround(container, diagramObj,
                    mergedX, mergedY, mergedWidth, mergedHeight, DEFAULT_GROUP_PADDING,
                    virtualGroupBounds, groupResizeCommands, ownedMoves,
                    bulkPendingParents.get(), null);
            if (passFitCommands == null) {
                cmd = AnchorResolver.wrapWithGroupResizes(cmd, groupResizeCommands);
                ownedResizes = groupResizeCommands;
            }
        }

        // Build DTO with post-execution styling and image fields
        StylingHelper.PostStyling post = StylingHelper.computePostStyling(diagramObj, styling);

        String dtoImagePath = ImageHelper.computePostImagePath(diagramObj, imageParams);
        String dtoImagePosition = ImageHelper.computePostImagePosition(diagramObj, imageParams);
        String dtoShowIcon = ImageHelper.computePostShowIcon(diagramObj, imageParams);
        // computeImageCoverage already answers CoverageReport.NONE (null, null) for an absent path,
        // which is exactly what the guard this replaces reproduced by hand.
        ImageHelper.CoverageReport dtoCoverage = computeImageCoverage(
                dtoImagePath, mergedWidth, mergedHeight, dtoImagePosition);
        Double dtoCoveragePercent = dtoCoverage.percent();
        String dtoCoverageWarning = dtoCoverage.warning();

        // A group has no element association, so it names and types itself — the same post-write
        // resolution the non-direct prepare uses, kept identical so both paths agree.
        IArchimateElement element = (diagramObj instanceof IDiagramModelArchimateObject archObj)
                ? archObj.getArchimateElement() : null;
        String dtoLabelExpression = computePostLabelExpression(diagramObj, labelExpression);
        ViewObjectDto dto = new ViewObjectDto(
                diagramObj.getId(),
                (element != null) ? element.getId() : null,
                (element != null) ? element.getName() : (text != null && diagramObj instanceof IDiagramModelGroup) ? text : diagramObj.getName(),
                (element != null) ? element.eClass().getName() : diagramObj.eClass().getName(),
                mergedX, mergedY,
                mergedWidth, mergedHeight,
                post.fillColor(), post.lineColor(), post.fontColor(), post.opacity(), post.lineWidth(),
                dtoImagePath, dtoImagePosition, dtoShowIcon,
                dtoCoveragePercent, dtoCoverageWarning,
                StylingHelper.readFigureType(diagramObj),
                StylingHelper.readTextAlignment(diagramObj),
                StylingHelper.readVerticalTextAlignment(diagramObj),
                dtoLabelExpression,
                post.fontName(), post.fontSize(), post.fontStyle(),
                post.gradient(), post.borderType(), post.deriveLineColor(), post.outlineOpacity(), post.lineStyle(),
                null, null, null, null, PlacementParent.liveOf(diagramObj),
                AnchorResolver.projectMoves(ownedMoves, diagramObj.getDiagramModel()),
                AnchorResolver.projectMoves(ownedResizes, diagramObj.getDiagramModel()));

        // Record effective bounds for later same-unit-of-work re-edits of this object (mirror the primary path). The pass map, when the caller owns one, is where later mutations of this same call read from; the bulk map keeps serving later operations of the pass.
        AnchorResolver.recordEffective(passObjectBounds, null, diagramObj.getId(), boundsModified, mergedX, mergedY, mergedWidth, mergedHeight);
        AnchorResolver.recordEffective(bulkPendingGroupBounds.get(), cascadeGroupBounds, diagramObj.getId(), boundsModified, mergedX, mergedY, mergedWidth, mergedHeight);

        return new PreparedMutation<>(cmd, dto, diagramObj.getId());
    }

    /**
     * Prepares an update-view-connection mutation: validates, creates new bendpoints,
     * builds command and DTO.
     *
     * @param batchConnection a connection resolved by the caller, for a connection an earlier
     *                        operation in the same call created. Its add command has not executed
     *                        yet, so the live id lookup below cannot see it; passing the object
     *                        skips that lookup. Null on the ordinary path, where the id is resolved
     *                        against the model as usual.
     */
    private PreparedMutation<ViewConnectionDto> prepareUpdateViewConnection(
            String viewConnectionId, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling,
            Boolean showLabel, Integer textPosition,
            IDiagramModelArchimateConnection batchConnection) {
        // Only the id lookup needs a model, so a pre-resolved connection does not capture one.
        IArchimateModel model = (batchConnection == null) ? requireAndCaptureModel() : null;

        // Validate hex colours before any other processing
        if (styling != null) {
            StylingHelper.validateConnectionStylingParams(styling);
        }

        // Find connection
        IDiagramModelArchimateConnection connection = batchConnection;
        if (connection == null) {
            EObject obj = ArchimateModelUtils.getObjectByID(model, viewConnectionId);
            if (!(obj instanceof IDiagramModelArchimateConnection found)) {
                throw new ModelAccessException(
                        "View object not found: " + viewConnectionId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        BulkBackReferences.VIEW_TARGET_ADVICE,
                        null);
            }
            connection = found;
        } else {
            viewConnectionId = connection.getId();
        }

        // Convert absolute bendpoints to relative if provided
        List<BendpointDto> effectiveBendpoints = ConnectionResponseBuilder.resolveEffectiveBendpointsFromConnection(
                bendpoints, absoluteBendpoints, connection);

        // Build command (with optional styling). Null bendpoints mean the caller asked for no
        // bendpoint change, which the command leaves alone; an empty list still means clear. A
        // prepare-time snapshot here would be written back over whatever ran in between.
        List<IDiagramModelBendpoint> emfBendpoints = (effectiveBendpoints == null) ? null
                : ConnectionResponseBuilder.createEmfBendpoints(effectiveBendpoints);
        Command cmd = new UpdateViewConnectionCommand(connection, emfBendpoints, styling, showLabel, textPosition);

        // Build DTO — extract source/target for anchor points
        IDiagramModelArchimateObject sourceViewObj =
                (connection.getSource() instanceof IDiagramModelArchimateObject src) ? src : null;
        IDiagramModelArchimateObject targetViewObj =
                (connection.getTarget() instanceof IDiagramModelArchimateObject tgt) ? tgt : null;
        String sourceVoId = sourceViewObj != null ? sourceViewObj.getId() : null;
        String targetVoId = targetViewObj != null ? targetViewObj.getId() : null;

        // Build base DTO then overlay post-execution styling
        // When effectiveBendpoints is null (styling-only), read current bendpoints for response
        List<BendpointDto> responseBendpoints = (effectiveBendpoints != null)
                ? effectiveBendpoints : ConnectionResponseBuilder.collectBendpoints(connection);

        // Compute post-execution text position
        int dtoTextPosition = (textPosition != null) ? textPosition : connection.getTextPosition();

        ViewConnectionDto baseDto = ConnectionResponseBuilder.buildConnectionResponseDto(
                viewConnectionId, connection.getArchimateRelationship(),
                sourceVoId, targetVoId, responseBendpoints, sourceViewObj, targetViewObj,
                dtoTextPosition);

        // Compute post-execution connection styling
        String dtoLineColor = StylingHelper.computePostStylingColor(
                StylingHelper.readConnectionLineColor(connection), styling != null ? styling.lineColor() : null);
        String dtoFontColor = StylingHelper.computePostStylingColor(
                StylingHelper.readConnectionFontColor(connection), styling != null ? styling.fontColor() : null);
        Integer dtoLineWidth = StylingHelper.computePostStylingLineWidth(
                StylingHelper.readConnectionLineWidth(connection), styling != null ? styling.lineWidth() : null);

        // Compute post-execution label visibility
        Boolean dtoNameVisible;
        if (showLabel != null) {
            dtoNameVisible = showLabel ? null : Boolean.FALSE;
        } else {
            dtoNameVisible = StylingHelper.readConnectionNameVisible(connection);
        }

        // Post-execution typography. No lineStyle: in Archi 5.8 that is a view-object property,
        // not a connection one. The merged composite font is assembled ONCE and all three
        // sub-fields parsed off it, rather than assembling it three times.
        String mergedConnFont = (styling != null
                && (styling.fontName() != null || styling.fontSize() != null || styling.fontStyle() != null))
                ? StylingHelper.assembleFontString(connection.getFont(),
                        styling.fontName(), styling.fontSize(), styling.fontStyle())
                : connection.getFont();
        String dtoConnFontName = StylingHelper.parseFontName(mergedConnFont);
        Integer dtoConnFontSize = StylingHelper.parseFontSize(mergedConnFont);
        String dtoConnFontStyle = StylingHelper.parseFontStyle(mergedConnFont);

        ViewConnectionDto dto = ConnectionResponseBuilder.withConnectionStyling(baseDto,
                dtoLineColor, dtoLineWidth, dtoFontColor, dtoNameVisible,
                dtoConnFontName, dtoConnFontSize, dtoConnFontStyle);

        return new PreparedMutation<>(cmd, dto, viewConnectionId);
    }

    /**
     * Prepares a remove-from-view mutation: finds the object (element, group,
     * note, or connection); for element / group / note resolves the immediate
     * parent container (the view itself for top-level placements, or a nested
     * container for nested view-objects); collects attached connections for
     * cascade-disconnect; builds command and DTO.
     *
     * <p>(v1.6): the element branch resolves the real parent via
     * {@link #findParentContainer(IDiagramModelContainer, IDiagramModelObject)},
     * sibling-symmetric with the group and note branches. Before
     * this change the element branch passed the view itself as the container,
     * which silently no-op'd for nested view-objects placed via
     * element-as-container nesting.</p>
     */
    private PreparedMutation<RemoveFromViewResultDto> prepareRemoveFromView(
            String viewId, String viewObjectId) {
        IArchimateModel model = requireAndCaptureModel();

        // Find view
        IArchimateDiagramModel view = resolveViewOrThrow(model, viewId, null);

        // Look up the viewObjectId — could be an element or a connection
        EObject targetObj = ArchimateModelUtils.getObjectByID(model, viewObjectId);

        // Case 1: It's a view object (element on the view)
        if (targetObj instanceof IDiagramModelArchimateObject diagramObj) {
            // Verify it's actually on this view
            if (!isChildOfView(view, diagramObj)) {
                throw viewObjectNotFound("View object not found on view: " + viewObjectId);
            }

            // (v1.6): resolve the real parent container — sibling-symmetric with
            // the group/note branches below. For top-level objects this is the view;
            // for nested objects (element-as-container) this is the
            // enclosing component / node / group view-object.
            IDiagramModelContainer parent = findParentContainer(view, diagramObj);
            if (parent == null) {
                // Invariant violation between isChildOfView (just returned true) and
                // findParentContainer — surface as INTERNAL_ERROR, not VIEW_OBJECT_NOT_FOUND,
                // because the object IS reachable in the container tree.
                throw new ModelAccessException(
                        "Parent resolution failed for element on view: " + viewObjectId,
                        ErrorCode.INTERNAL_ERROR,
                        null,
                        null,
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

            Command cmd = new RemoveFromViewCommand(diagramObj, parent, attached);

            List<String> cascadeIds = attached.isEmpty() ? null
                    : attached.stream().map(IDiagramModelArchimateConnection::getId).toList();
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    viewObjectId, "viewObject", cascadeIds);

            return new PreparedMutation<>(cmd, dto, viewObjectId);
        }

        // Case 1b: It's a group on the view
        if (targetObj instanceof IDiagramModelGroup groupObj) {
            if (!isChildOfViewGeneric(view, groupObj)) {
                throw viewObjectNotFound("View object not found on view: " + viewObjectId);
            }
            IDiagramModelContainer parent = findParentContainer(view, groupObj);
            Command cmd = new RemoveViewObjectCommand(groupObj, parent);
            List<String> cascadeIds = collectDescendantIds(groupObj);
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    viewObjectId, "group", cascadeIds.isEmpty() ? null : cascadeIds);
            return new PreparedMutation<>(cmd, dto, viewObjectId);
        }

        // Case 1c: It's a note on the view
        if (targetObj instanceof IDiagramModelNote noteObj) {
            if (!isChildOfViewGeneric(view, noteObj)) {
                throw viewObjectNotFound("View object not found on view: " + viewObjectId);
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
                throw viewObjectNotFound("View object not found on view: " + viewObjectId);
            }
            Command cmd = new RemoveConnectionFromViewCommand(connection);
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    viewObjectId, "viewConnection", null);
            return new PreparedMutation<>(cmd, dto, viewObjectId);
        }

        // Neither element nor connection found — the branch a model-relationship id (or
        // model-element id) falls into, since neither resolves to any IDiagramModel* instance.
        throw viewObjectNotFound("View object not found: " + viewObjectId);
    }

    /**
     * The refusal every arm of {@link #prepareRemoveFromView} raises when an id resolves to
     * something that is not a view object on this view. Single-sourced because the advice below is
     * the whole remedy — it names the four fields a caller can read a visual id out of — and five
     * copies of it can drift into five different answers to the same question without any of them
     * looking wrong on its own.
     */
    private static ModelAccessException viewObjectNotFound(String message) {
        return new ModelAccessException(message, ErrorCode.VIEW_OBJECT_NOT_FOUND, null,
                "A model id (relationship or element) is not a view-object id and will not resolve here. "
                        + "Use get-view-contents to find the VISUAL ids: viewConnectionId / viewObjectId on "
                        + "format=graph edges / nodes, or viewConnectionId from format=json connections and "
                        + "viewObjectId from visualMetadata.",
                null);
    }

    /**
     * Prepares a clear-view mutation without dispatching.
     * Finds view, counts children and connections, builds ClearViewCommand and DTO.
     */
    private PreparedMutation<ClearViewResultDto> prepareClearView(String viewId) {
        IArchimateModel model = requireAndCaptureModel();

        // Find view
        IArchimateDiagramModel view = resolveViewOrThrow(model, viewId, null);

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
        // to give an accurate count in the DTO
        List<IDiagramModelConnection> allConnections = AssessmentCollector.collectAllConnections(view);

        Command cmd = new ClearViewCommand(view);
        ClearViewResultDto dto = new ClearViewResultDto(
                viewId, view.getName(), totalChildren, allConnections.size(),
                nonArchimateCount);

        return new PreparedMutation<>(cmd, dto, viewId);
    }

    // ---- Deletion prepare methods ----

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
        // Orphaned relationships (eContainer == null) are skipped — they exist
        // only in EMF cross-references and cannot be safely removed from a folder.
        List<DeleteElementCommand.CascadedRelationship> cascadedRels = new ArrayList<>();
        for (IArchimateRelationship rel : allRels) {
            if (rel.eContainer() == null) {
                logger.warn("Skipping orphaned relationship {} during element deletion "
                        + "(exists in cross-references but not in containment tree)",
                        rel.getId());
                continue;
            }
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

        IArchimateDiagramModel view = resolveViewOrThrow(model, viewId, null);

        IFolder viewFolder = (IFolder) view.eContainer();
        int viewIndex = viewFolder.getElements().indexOf(view);

        Command cmd = new DeleteViewCommand(view, viewFolder, viewIndex);

        // 0/null tail is correct: a view delete removes no model concepts, no folders.
        DeleteResultDto dto = new DeleteResultDto(
                view.getId(), view.getName(), view.eClass().getName(), 0,
                DeleteViewCommand.countExternalPlaceholders(view),
                AssessmentCollector.collectAllConnections(view).size(), null, null, null);

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
                // only track contained relationships — orphans won't be cascade-deleted
                for (IArchimateRelationship rel : element.getSourceRelationships()) {
                    if (rel.eContainer() != null) cascadedRelIds.add(rel.getId());
                }
                for (IArchimateRelationship rel : element.getTargetRelationships()) {
                    if (rel.eContainer() != null) cascadedRelIds.add(rel.getId());
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
            } else if (obj instanceof IDiagramModel view) {
                // Sketches/canvases are IArchimateDiagramModel SIBLINGS removed by containment regardless. Fold the view's own placeholders/connections in, mirroring the element/relationship arms — the sum is order-invariant (all prepares precede all executes) and every counted placeholder ceases to exist (scrubbed by its target's cascade, or removed with the also-deleted sibling that held it).
                int viewIndex = folder.getElements().indexOf(view);
                subCommands.add(new DeleteViewCommand(view, folder, viewIndex));
                counts[2]++; // views
                counts[4] += DeleteViewCommand.countExternalPlaceholders(view);
                counts[5] += view instanceof IArchimateDiagramModel adm ? AssessmentCollector.collectAllConnections(adm).size() : 0; // ArchiMate-only; sketch/canvas connections out of scope
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
     * Resolves the destination view's name from a {@code viewId} OR from a view
     * object/connection id (via its owning diagram). Lets every view-visual add/update/delete
     * proposal name WHICH view the change lands in. Returns {@code null} when the id is unresolvable
     * or the view name is null/blank — callers degrade to the un-named text (never "view ''", never
     * an NPE). Private: no {@code ArchiModelAccessor} interface surface.
     */
    private String resolveViewName(IArchimateModel model, String id) {
        EObject obj = ArchimateModelUtils.getObjectByID(model, id);
        IDiagramModel dm = null;
        if (obj instanceof IDiagramModel d) {
            dm = d;
        } else if (obj instanceof IDiagramModelObject o) {
            dm = o.getDiagramModel();
        } else if (obj instanceof IDiagramModelConnection c) {
            dm = c.getDiagramModel();
        }
        if (dm != null) {
            String name = dm.getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
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
     * Used for group/note removal to determine the correct parent.
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
     * Generic version that works for groups, notes, and element objects.
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

    // ---- View placement helpers ----

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
     * Used for resolving parentViewObjectId and general view object lookup.
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

    // ---- Folder mutation methods ----

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
                ProposalBuilder.putIfPresent(proposedChanges, "documentation", documentation, "properties", properties);
                ProposalContext ctx = storeAsProposal(sessionId, "create-folder",
                        () -> prepareCreateFolder(parentId, name, documentation, properties),
                        targetIds(parentId), prepared.entity(), description,
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
                ProposalBuilder.putIfPresent(proposedChanges, "name", name, "documentation", documentation,
                        "properties", properties);
                ProposalContext ctx = storeAsProposal(sessionId, "update-folder",
                        () -> prepareUpdateFolder(id, name, documentation, properties),
                        targetIds(id), prepared.entity(), description,
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
                        () -> prepareMoveToFolder(objectId, targetFolderId),
                        targetIds(objectId, targetFolderId), prepared.entity(), description,
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

    // ---- Folder mutation prepare methods ----

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
        newFolder.setName(InputValidation.reject(name, "name"));
        newFolder.setType(FolderType.USER);

        if (documentation != null) {
            newFolder.setDocumentation(documentation);
        }

        ConceptMetadata.applyProperties(newFolder, properties);

        Command cmd = new RequireAttachedContainerCommand(
                new CreateFolderCommand(newFolder, parentFolder), parentFolder, model, name,
                "folder", Wording.CREATE_IN_FOLDER);

        // Path is built from the name the folder actually carries, not the requested string, so
        // the two cannot disagree if a validator ever normalises what it is handed.
        String parentPath = FolderOperations.buildFolderPath(parentFolder);
        String newPath = parentPath + "/" + newFolder.getName();
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

        // Validate ONCE and hand the same value to the command and to the response, so the reported
        // name is the name that will be written rather than the name that was asked for.
        String validatedName = InputValidation.reject(name, "name");
        Command cmd = new UpdateFolderCommand(folder, validatedName, documentation, properties);

        // Build the DTO reflecting the proposed new state
        String effectiveName = validatedName != null ? validatedName : folder.getName();
        String effectiveDoc = documentation != null ? documentation : folder.getDocumentation();
        String path = FolderOperations.pathAfterRename(folder, effectiveName, validatedName != null);

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
        if (isFolder && FolderOperations.isOrDescendsFrom(targetFolder, (IFolder) object)) {
            throw new ModelAccessException(
                    "Cannot move folder into its own subtree — this would create a circular reference",
                    ErrorCode.CIRCULAR_FOLDER_REFERENCE,
                    null,
                    "Choose a target folder that is not a descendant of the folder being moved",
                    null);
        }

        // Views can only live within the Views (DIAGRAMS) hierarchy. IDiagramModel (not just
        // IArchimateDiagramModel) so sketch and canvas views — which Archi also governs under the
        // Views root — are covered, matching host Archi's save-time checkIntegrity.
        if (object instanceof IDiagramModel
                && FolderOperations.isViewTargetOutsideDiagrams(model, targetFolder)) {
            throw new ModelAccessException(
                    "Views can only be moved within the Views folder hierarchy",
                    ErrorCode.INVALID_MOVE_TARGET,
                    null,
                    "Choose a target folder that is within the Views hierarchy. "
                            + "Use get-folders to find folders under the Views root.",
                    null);
        }

        // Concepts (elements + relationships) must live under their governing layer folder —
        // mirror the create-element guard so create and move reject identically (Archi's
        // save-time checkIntegrity would otherwise refuse to save a mis-filed concept).
        if (object instanceof IArchimateConcept concept) {
            validateFolderLayerMatch(concept, targetFolder);
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
        // eClass for every kind moved, not only elements — objectType below stays the coarse noun.
        String elementType = (object instanceof EObject eo) ? eo.eClass().getName() : null;

        if (isFolder) {
            objectName = ((IFolder) object).getName();
            objectType = "Folder";
        } else if (object instanceof IArchimateElement element) {
            objectName = element.getName();
            objectType = "Element";
        } else if (object instanceof IArchimateRelationship rel) {
            objectName = rel.getName() != null ? rel.getName() : rel.eClass().getName();
            objectType = "Relationship";
        } else if (object instanceof IDiagramModel view) {
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

    // ---- Bulk mutation ----

    @Override
    public BulkMutationResult executeBulk(String sessionId, List<BulkOperation> operations,
            String description, boolean continueOnError) {
        return executeBulk(sessionId, operations, description, continueOnError, null);
    }

    @Override
    public BulkMutationResult executeBulk(String sessionId, List<BulkOperation> operations,
            String description, boolean continueOnError, String intent) {
        logger.info("Executing bulk mutation: {} operations, continueOnError={}",
                operations.size(), continueOnError);
        IArchimateModel model = requireAndCaptureModel();

        // Activate the bulk profile cache for the duration of this call. See
        // bulkProfileCache field doc for the rationale: it prevents duplicate IProfile
        // creation when multiple operations in one batch share a new specialization.
        // Cleared in finally so the ThreadLocal cannot leak across Jetty requests.
        bulkProfileCache.set(new HashMap<>());
        bulkPendingGroupBounds.set(new LinkedHashMap<>()); // see field doc; cleared in finally
        bulkPendingParents.set(new LinkedHashMap<>()); // see field doc; cleared in finally
        try {
            // Phase 1: Validate all operations and build commands (Jetty thread)
            List<PreparedMutation<?>> preparedMutations = new ArrayList<>();
            List<BulkOperationResult> operationResults = new ArrayList<>();
            // Both halves of "what failed" in one object: the rows a caller is shown and the
            // index set the cascade check consults. Keeping them in step is a correctness
            // requirement, not tidiness — an index recorded as failed but missing from the set
            // lets a dependent operation resolve its $N.id to null and proceed.
            BulkValidationFailures failures = new BulkValidationFailures();
            // Tracks EMF element objects for back-reference resolution
            Map<Integer, IArchimateElement> createdElements = new LinkedHashMap<>();
            // Tracks EMF view objects for view-level back-reference resolution
            Map<Integer, IDiagramModelArchimateObject> createdViewObjects = new LinkedHashMap<>();
            // Tracks raw relationships so a later operation can address them by back-reference
            Map<Integer, IArchimateRelationship> createdRelationships = new LinkedHashMap<>();
            // Tracks raw view connections for back-reference by update-view-connection
            Map<Integer, IDiagramModelArchimateConnection> createdViewConnections = new LinkedHashMap<>();
            // Tracks EMF group objects for parentViewObjectId resolution in add-to-view
            Map<Integer, IDiagramModelGroup> createdGroups = new LinkedHashMap<>();
            // Tracks EMF view objects created by create-view for viewId back-reference resolution
            Map<Integer, IArchimateDiagramModel> createdViews = new LinkedHashMap<>();
            // Tracks EMF notes so update-view-object can address one this call created. Separate
            // from createdGroups/createdViewObjects because a note is not an IDiagramModelContainer.
            Map<Integer, IDiagramModelNote> createdNotes = new LinkedHashMap<>();
            Map<Integer, String> createdEntityIds = new LinkedHashMap<>();
            Map<Integer, String> operationTools = new LinkedHashMap<>();
            // Per-op resolved relationship endpoint display names {sourceName, targetName},
            // captured from the prepared RelationshipDto (the raw relationship's endpoints are null
            // pre-connect) so the approval card's bulk rows can name source→target.
            Map<Integer, String[]> relationshipEndpointNames = new LinkedHashMap<>();
            // Resolvable secondary endpoint ids the batch depends on — the source/target
            // element ids of create-relationship ops (pre-existing, param-resolvable at propose) so the
            // staleness guard rejects-stale if the human deletes/edits an endpoint during review. Ids that
            // name a just-created element ($N.id) are skipped by capture; created ids stay on the safety net.
            Set<String> bulkSecondaryTargetIds = new java.util.LinkedHashSet<>();
            // Names declared with `as`, indexed once. Both things a declaration can get wrong — an
            // illegal name, and one two operations both claim — are properties of the whole array
            // rather than of the operation being prepared, so they are settled before the loop.
            Map<String, Integer> labelIndices = BulkBackReferences.indexLabels(operations, failures);
            for (int i = 0; i < operations.size(); i++) {
                BulkOperation op = operations.get(i);
                operationTools.put(i, op.tool());
                // Refused above over its own name. Preparing it anyway would let it execute under
                // continueOnError while its caller had already been told it failed.
                if (failures.failedIndices().contains(i)) {
                    continue;
                }

                try {
                    // Check for back-reference cascade: if this operation references a failed one.
                    // Ungated, because the loop no longer stops at the first failure on either
                    // path. Left gated it would let a reference to a failed operation resolve to
                    // null: on a required parameter that is a misleading "missing parameter" for
                    // one the caller supplied, and on an optional parameter it is dropped in
                    // silence and the operation succeeds somewhere else entirely.
                    if (!failures.failedIndices().isEmpty()) {
                        String cascadeError = BulkBackReferences.checkCascade(
                                op.params(), failures.failedIndices(), labelIndices);
                        if (cascadeError != null) {
                            failures.recordCascade(i, op.tool(), cascadeError);
                            continue;
                        }
                    }

                    // Resolve back-references in params
                    Map<String, Object> resolvedParams = BulkBackReferences.resolve(
                            op.params(), i, createdEntityIds, operationTools, labelIndices);

                    PreparedMutation<?> prepared = prepareOperation(
                            sessionId, op.tool(), resolvedParams, i, createdElements,
                            createdViewObjects, createdRelationships,
                            createdViewConnections, createdGroups, createdViews, createdNotes);
                    preparedMutations.add(prepared);

                    // Store for future back-references
                    createdEntityIds.put(i, prepared.entityId());

                    // Build per-operation result
                    String action = BulkResultProjection.resolveActionString(op.tool());
                    if (prepared.entity() instanceof RelationshipDto relDto) {
                        if (relDto.alreadyExisted()) {
                            action = "already_existed";
                        }
                        // Capture the resolved endpoint NAMES now (the DTO carries them even
                        // though the relationship is not yet connected) for the approval-card rows.
                        if (relDto.sourceName() != null || relDto.targetName() != null) {
                            relationshipEndpointNames.put(i,
                                    new String[] { relDto.sourceName(), relDto.targetName() });
                        }
                        // Track the endpoint element ids too — pre-existing endpoints are
                        // resolvable now; a just-created endpoint id is skipped at capture (safety net).
                        if (relDto.sourceId() != null) {
                            bulkSecondaryTargetIds.add(relDto.sourceId());
                        }
                        if (relDto.targetId() != null) {
                            bulkSecondaryTargetIds.add(relDto.targetId());
                        }
                    }
                    BulkOperationResult opResult = BulkResultProjection.describe(
                            i, op.tool(), action, prepared);
                    operationResults.add(opResult);

                } catch (ModelAccessException e) {
                    // Record and keep going on BOTH paths. The all-or-nothing contract is about
                    // what is applied, not about how much is inspected: nothing is dispatched
                    // while any operation has failed, and the prepare phase builds detached
                    // objects and defers every attachment to its command, so running the rest of
                    // the loop costs a caller nothing and buys it the whole list in one refusal
                    // instead of one round-trip per defect on a payload it must rebuild each time.
                    failures.record(i, op.tool(), e);
                }
            }

            // One refusal carrying every operation that failed. Thrown after the loop rather than
            // at the first failure, and before the compound is built, so nothing is dispatched.
            if (!continueOnError && !failures.isEmpty()) {
                throw failures.toException();
            }

            // If continueOnError and ALL operations failed, return without dispatching
            if (continueOnError && operationResults.isEmpty()) {
                logger.warn("Bulk mutation: all {} operations failed, no model change",
                        operations.size());
                return new BulkMutationResult(
                        List.of(),
                        failures.rows(),
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
                label = continueOnError && !failures.isEmpty()
                        ? description + " (" + succeededCount + "/" + totalCount + " operations)"
                        : description;
            } else {
                label = continueOnError && !failures.isEmpty()
                        ? "Bulk mutation (" + succeededCount + "/" + totalCount + " operations)"
                        : "Bulk mutation (" + totalCount + " operations)";
            }

            NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(label);
            for (PreparedMutation<?> pm : preparedMutations) {
                compound.add(pm.command());
            }

            // Approval gate: store compound as single proposal
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("operationCount", succeededCount);
                proposedChanges.put("continueOnError", continueOnError);
                // Structured per-op shape so the card renders NAMED rows without the
                // human opening raw Technical-details JSON. Each op carries {index, tool, name, type}
                // (name/type already populated on BulkOperationResult) and, for relationship ops,
                // the resolved source→target endpoint NAMES. ApprovalCardModel.opsList already
                // unwraps a Map's "tool" key, so the destructive/amber classification is preserved.
                List<Map<String, Object>> opSummaries = new ArrayList<>();
                for (BulkOperationResult opResult : operationResults) {
                    Map<String, Object> op = new LinkedHashMap<>();
                    op.put("index", opResult.index());
                    op.put("tool", opResult.tool());
                    if (opResult.entityName() != null) {
                        op.put("name", opResult.entityName());
                    }
                    if (opResult.entityType() != null) {
                        op.put("type", opResult.entityType());
                    }
                    String[] endpoints = resolveBulkOpEndpoints(
                            model, relationshipEndpointNames, opResult);
                    if (endpoints != null) {
                        // Omit an unresolved endpoint rather than writing a literal
                        // "(unknown)" — the card's bulkRow only renders a named source→target
                        // row when BOTH keys are present, otherwise it falls back to the honest
                        // coarse noun. A placeholder string would surface as a fake element name.
                        if (endpoints[0] != null) {
                            op.put("source", endpoints[0]);
                        }
                        if (endpoints[1] != null) {
                            op.put("target", endpoints[1]);
                        }
                    }
                    opSummaries.add(op);
                }
                proposedChanges.put("operations", opSummaries);
                if (!failures.isEmpty()) {
                    List<String> failSummaries = new ArrayList<>();
                    for (BulkOperationFailure f : failures.rows()) {
                        failSummaries.add(f.index() + ": " + f.tool() + " — " + f.message());
                    }
                    proposedChanges.put("failedOperations", failSummaries);
                }
                String validationMsg = failures.isEmpty()
                        ? "All " + totalCount + " operations validated successfully."
                        : succeededCount + " of " + totalCount + " operations validated successfully. "
                                + failures.rows().size() + " failed validation.";
                // bulk uses reviewed-or-reject (the deferred handle returns the reviewed compound —
                // re-running the whole bulk could differ from what was reviewed), so the tracked set is
                // the ONLY staleness check: there is no rebuild here that could throw. Hence the same
                // compound walk the other thirteen frozen sites use, not just each op's own new id.
                ProposalContext ctx = storeAsProposal(sessionId, "bulk-mutate",
                        () -> new PreparedMutation<>(compound, operationResults, null),
                        BulkStalenessTargets.bulkTargetIds(
                                compound, operationResults, bulkSecondaryTargetIds),
                        operationResults, label,
                        null, proposedChanges, validationMsg + ProposalBuilder.REVIEWED_OR_REJECT, null, intent);
                return new BulkMutationResult(
                        List.copyOf(operationResults),
                        failures.rows(),
                        totalCount,
                        failures.isEmpty(),
                        null,
                        ctx);
            }

            Integer batchSeq = dispatchOrQueue(sessionId, compound, label);

            if (batchSeq == null) {
                versionCounter.incrementAndGet();
            }

            return BulkMutationResult.of(
                    BulkResultProjection.withoutRetractedCollateral(
                            BulkResultProjection.withPostDispatchState(
                                    model, operationResults, batchSeq == null),
                            compound),
                    failures.rows(),
                    totalCount,
                    batchSeq,
                    CommitSkippableCommand.collectSkipReasonsByOperation(compound));

        } catch (NoModelLoadedException | ModelAccessException | MutationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error executing bulk mutation",
                    e, ErrorCode.INTERNAL_ERROR);
        } finally {
            bulkProfileCache.remove();
            bulkPendingGroupBounds.remove();
            bulkPendingParents.remove();
        }
    }

    /**
     * Dispatches a single operation to the appropriate prepare method.
     */
    private PreparedMutation<?> prepareOperation(String sessionId, String tool, Map<String, Object> params,
            int operationIndex, Map<Integer, IArchimateElement> createdElements,
            Map<Integer, IDiagramModelArchimateObject> createdViewObjects,
            Map<Integer, IArchimateRelationship> createdRelationships,
            Map<Integer, IDiagramModelArchimateConnection> createdViewConnections,
            Map<Integer, IDiagramModelGroup> createdGroups,
            Map<Integer, IArchimateDiagramModel> createdViews, Map<Integer, IDiagramModelNote> createdNotes) {
        return switch (tool) {
            case "create-element" -> {
                String type = requireParam(params, "type");
                String name = requireParam(params, "name");
                String documentation = optionalParam(params, "documentation");
                Map<String, String> properties = ConceptMetadata.mergeSourceProperties(optionalStringMap(params, "properties"), optionalStringMap(params, "source"));
                String folderId = optionalParam(params, "folderId");
                String specialization = optionalParam(params, "specialization");

                PreparedMutation<ElementDto> prepared = prepareCreateElement(
                        type, name, documentation, properties, folderId, specialization);

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
                String specialization = optionalParam(params, "specialization");
                // Semantic attributes for bulk-mutate
                RelationshipSemanticAttributes attrs = readSemanticAttributesFromParams(params);
                String documentation = optionalParam(params, "documentation");
                Map<String, String> properties = ConceptMetadata.mergeSourceProperties(
                        optionalStringMap(params, "properties"), optionalStringMap(params, "source"));

                // Check if source/target are back-referenced elements (not yet in model)
                IArchimateElement sourceElement = findBackReferenced(sourceId, createdElements);
                IArchimateElement targetElement = findBackReferenced(targetId, createdElements);

                PreparedMutation<RelationshipDto> prepared;
                if (sourceElement != null && targetElement != null) {
                    prepared = prepareCreateRelationshipDirect(
                            type, sourceElement, targetElement, name, specialization, attrs,
                            documentation, properties);
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
                            type, sourceElement, target, name, specialization, attrs,
                            documentation, properties);
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
                            type, source, targetElement, name, specialization, attrs,
                            documentation, properties);
                } else {
                    // Both are existing model elements — standard path
                    prepared = prepareCreateRelationship(type, sourceId, targetId, name, specialization, attrs,
                            documentation, properties);
                }
                // Store raw relationship so a later operation can address it by back-reference
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
                PreparedMutation<ViewDto> viewPrepared =
                        prepareCreateView(name, viewpoint, folderId, connectionRouterType);
                // Store raw view for viewId back-reference resolution
                if (viewPrepared.rawObject() instanceof IArchimateDiagramModel view) {
                    createdViews.put(operationIndex, view);
                }
                yield viewPrepared;
            }
            case "update-model" -> {
                // Bulk-mutate parity for update-model.
                String name = optionalParam(params, "name");
                String purpose = optionalParam(params, "purpose");
                // Name empty-string preservation: pass "" through so prepareUpdateModel's
                // guard rejects it with "Model name must not be empty." (NOT the misleading
                // "No fields to update" fallback). optionalParam strips "" to null via its
                // !isBlank() filter; this re-check restores it. Empirically caught in the
                // 14-3 empirical run (2026-05-26).
                if (params.containsKey("name") && "".equals(params.get("name"))) {
                    name = "";
                }
                // Purpose clear semantics: empty string means "clear purpose".
                // Cross-ref: same logic in ModelQueryHandler.handleUpdateModel() and
                // ArchiModelAccessorImpl.prepareUpdateModel() which converts "" to clearPurpose=true.
                if (params.containsKey("purpose") && "".equals(params.get("purpose"))) {
                    purpose = "";
                }
                Map<String, String> properties = optionalStringMapWithNulls(params, "properties");
                yield prepareUpdateModel(name, purpose, properties);
            }
            case "update-element" -> {
                String id = requireParam(params, "id");
                String name = optionalParam(params, "name");
                String documentation = optionalParam(params, "documentation");
                Map<String, String> properties = optionalStringMapWithNulls(params, "properties");
                // Specialization clear semantics: empty string means "clear all profiles".
                String specialization = optionalParam(params, "specialization");
                if (params.containsKey("specialization") && "".equals(params.get("specialization"))) {
                    specialization = "";
                }
                yield prepareUpdateElement(id, name, documentation, properties, specialization);
            }
            case "update-relationship" -> {
                String id = requireParam(params, "id");
                String name = optionalAllowEmptyParam(params, "name"); // "" clears, per the schema
                String documentation = optionalAllowEmptyParam(params, "documentation"); // "" clears
                Map<String, String> properties = optionalStringMapWithNulls(params, "properties");
                String specialization = optionalParam(params, "specialization");
                if (params.containsKey("specialization") && "".equals(params.get("specialization"))) {
                    specialization = "";
                }
                // Semantic attributes for bulk-mutate
                RelationshipSemanticAttributes attrs = readSemanticAttributesFromParams(params);
                yield prepareUpdateRelationship(id, name, documentation, properties, specialization, attrs);
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
                Integer x = optionalIntParam(params, "x"); Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width"); Integer height = optionalIntParam(params, "height");
                Boolean autoSize = optionalBoolParam(params, "autoSize");
                String parentViewObjectId = optionalParam(params, "parentViewObjectId");
                StylingParams bulkStyling = extractBulkStylingParams(params);
                ImageParams bulkImageParams = extractBulkImageParams(params);

                // Auto-size element to fit label when autoSize=true and no explicit dimensions
                if (Boolean.TRUE.equals(autoSize) && width == null && height == null) {
                    // Check back-referenced element first, then model lookup
                    IArchimateElement backRef = findBackReferenced(elementId, createdElements);
                    String elName = backRef != null ? backRef.getName() : "";
                    if (elName.isEmpty()) {
                        // Try model lookup
                        Optional<ElementDto> elOpt = getElementById(elementId);
                        elName = elOpt.map(ElementDto::name).orElse("");
                    }
                    int[] computed = ElementSizer.computeAutoSize(elName);
                    width = computed[0];
                    height = computed[1];
                }

                // Resolve batch-created parent container (group or element
                // created earlier in this batch via add-group-to-view or add-to-view)
                IDiagramModelContainer batchParent = findBatchCreatedObject(
                        parentViewObjectId, createdGroups, createdViewObjects);

                // Check if elementId is a back-referenced element (not yet in model), or one the
                // enclosing batch has queued but not yet created.
                IArchimateElement backRefElement = findBackReferenced(elementId, createdElements);
                if (backRefElement == null) {
                    backRefElement = mutationDispatcher.queuedCreatedElement(sessionId, elementId);
                }

                // Resolve batch-created view for viewId back-reference, falling back to one the
                // enclosing batch queued — the same value the single-tool entry already passes.
                IArchimateDiagramModel batchView = coalesceQueuedView(sessionId, viewId, createdViews);

                // autoConnect forced false in bulk context. A back-referenced element is handed
                // over as the resolved batch element, which is the same seam a batch-created view
                // and parent already use — so the back-reference needs no separate prepare.
                PreparedMutation<AddToViewResultDto> addToViewPrepared = prepareAddToView(
                        sessionId, viewId, elementId, x, y, width, height, false,
                        batchParent != null ? null : parentViewObjectId,
                        batchParent, bulkStyling, bulkImageParams, batchView, backRefElement);

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
                StylingParams connStyling = extractBulkStylingParams(params);
                Boolean showLabel = optionalBoolParam(params, "showLabel");
                Integer textPosition = parseBulkLabelPosition(params);

                // A relationship an earlier operation in this call created, or null for a live one.
                IArchimateRelationship directRelationship = findBackReferenced(
                        relationshipId, createdRelationships);

                // Check if source/target are back-referenced view objects
                IDiagramModelArchimateObject sourceViewObj = findBackReferenced(sourceViewObjectId, createdViewObjects);
                IDiagramModelArchimateObject targetViewObj = findBackReferenced(targetViewObjectId, createdViewObjects);

                // Resolve batch-created view for viewId back-reference
                IArchimateDiagramModel connBatchView = findBackReferenced(viewId, createdViews);

                PreparedMutation<ViewConnectionDto> connPrepared;
                if (sourceViewObj != null || targetViewObj != null
                        || directRelationship != null || connBatchView != null) {
                    connPrepared = prepareAddConnectionToViewDirect(
                            sessionId, viewId, relationshipId,
                            sourceViewObj, sourceViewObjectId,
                            targetViewObj, targetViewObjectId,
                            bendpoints, absoluteBendpoints, directRelationship,
                            connStyling, showLabel, textPosition, connBatchView);
                } else {
                    connPrepared = prepareAddConnectionToView(
                            sessionId, viewId, relationshipId,
                            sourceViewObjectId, targetViewObjectId,
                            bendpoints, absoluteBendpoints,
                            connStyling, showLabel, textPosition);
                }
                // Recorded so a later operation in this call can address it by back-reference.
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
                Integer x = optionalIntParam(params, "x"); Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width"); Integer height = optionalIntParam(params, "height");
                String text = optionalAllowEmptyParam(params, "text");
                StylingParams voStyling = extractBulkStylingParams(params);
                ImageParams voImageParams = extractBulkImageParams(params);
                // Text and label-expression both reach bulk through the allow-empty helper, so they
                // keep the single-tool semantics: empty string clears, absent key leaves unchanged.
                String voLabelExpression = optionalAllowEmptyParam(params, "labelExpression");

                // An object an earlier operation in this same call created is still detached, so it
                // is handed over directly rather than looked up by id. Groups count: the same id an
                // add-group-to-view returned is usable here and also as a parentViewObjectId. A
                // NOTE answers a second lookup rather than the container one, which cannot hold it:
                // see findBatchCreatedObject's javadoc for why the two may stand side by side.
                IDiagramModelObject backRefViewObj = (findBatchCreatedObject(viewObjectId, createdGroups, createdViewObjects) instanceof IDiagramModelObject sameCallContainer)
                        ? sameCallContainer : findBackReferenced(viewObjectId, createdNotes);
                if (backRefViewObj != null) {
                    yield prepareUpdateViewObjectDirect(sessionId, backRefViewObj, x, y, width, height,
                            text, voStyling, voImageParams, voLabelExpression, null, null, null, null);
                }
                // An id an enclosing batch queued resolves here, so the same five queue arguments
                // the single-tool entry passes are passed here too. Identity alone is not enough:
                // without the pending-geometry slots the cascade would size a queued parent against
                // the live model and silently under-grow it. The four anchor parameters stay null —
                // this arm extracts no anchor params, so there is nothing for them to carry.
                yield prepareUpdateViewObject(viewObjectId, x, y, width, height, text,
                        voStyling, voImageParams, voLabelExpression, null, null, null, null,
                        mutationDispatcher.queuedViewObject(sessionId, viewObjectId), mutationDispatcher.queuedViewObject(sessionId, null),
                        mutationDispatcher.queuedParents(sessionId), mutationDispatcher.queuedBounds(sessionId), mutationDispatcher.queuedAnchors(sessionId), null, null);
            }
            case "update-view-connection" -> {
                String viewConnectionId = requireParam(params, "viewConnectionId");
                List<BendpointDto> bendpoints = parseBendpoints(params);
                List<AbsoluteBendpointDto> absoluteBendpoints = parseAbsoluteBendpoints(params);
                validateBendpointMutualExclusion(bendpoints, absoluteBendpoints);
                StylingParams connStyling = extractBulkStylingParams(params);
                Boolean showLabel = optionalBoolParam(params, "showLabel");
                Integer textPosition = parseBulkLabelPosition(params);

                // Neither format provided means clear bendpoints (consistent with handler path)
                if (bendpoints == null && absoluteBendpoints == null && connStyling == null
                        && showLabel == null && textPosition == null) {
                    bendpoints = List.of();
                }

                // A connection an earlier operation in this call created is still detached, so it
                // is handed over directly rather than looked up by id; null for a live one.
                IDiagramModelArchimateConnection backRefConn = findBackReferenced(
                        viewConnectionId, createdViewConnections);
                if (backRefConn == null) {
                    backRefConn = mutationDispatcher.queuedViewConnection(sessionId, viewConnectionId);
                }
                yield prepareUpdateViewConnection(viewConnectionId, bendpoints,
                        absoluteBendpoints, connStyling, showLabel, textPosition, backRefConn);
            }
            case "set-view-label-expression" ->
                    SetViewLabelExpressionCommand.prepare(requireAndCaptureModel(), params);
            case "clear-view" -> {
                String viewId = requireParam(params, "viewId");
                yield prepareClearView(viewId);
            }
            case "add-group-to-view" -> {
                String viewId = requireParam(params, "viewId");
                // Required, but "" is a value (an untitled group). The presence check stays here rather than deferring to the prepare's null guard: letting that absorb it would keep the check and lose the near-miss key report.
                String label = optionalAllowEmptyParam(params, "label");
                if (label == null) throw ParamNameDiagnostics.missingBulkParameter(params, "label", true);
                Integer x = optionalIntParam(params, "x"); Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width"); Integer height = optionalIntParam(params, "height");
                String parentVoId = optionalParam(params, "parentViewObjectId");
                StylingParams groupStyling = extractBulkStylingParams(params);
                ImageParams groupImageParams = extractBulkImageParams(params);

                // Resolve batch-created parent container (group or element)
                IDiagramModelContainer batchParent = findBatchCreatedObject(parentVoId, createdGroups, createdViewObjects);

                // Resolve batch-created view for viewId back-reference
                IArchimateDiagramModel batchView = coalesceQueuedView(sessionId, viewId, createdViews);

                PreparedMutation<ViewGroupDto> groupPrepared =
                        prepareAddGroupToView(sessionId, viewId, label, x, y, width, height,
                                batchParent != null ? null : parentVoId,
                                batchParent, groupStyling, groupImageParams, batchView);
                // Track group for parentViewObjectId resolution in add-to-view
                if (groupPrepared.rawObject() instanceof IDiagramModelGroup group) {
                    createdGroups.put(operationIndex, group);
                }
                yield groupPrepared;
            }
            case "add-note-to-view" -> {
                String viewId = requireParam(params, "viewId");
                // Required, but "" is a value (an empty placeholder note). Same shape as add-group-to-view's label: the presence check stays here so the near-miss key report is not lost to the prepare's null guard.
                String content = optionalAllowEmptyParam(params, "content");
                if (content == null) throw ParamNameDiagnostics.missingBulkParameter(params, "content", true);
                Integer x = optionalIntParam(params, "x"); Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width"); Integer height = optionalIntParam(params, "height");
                String parentVoId = optionalParam(params, "parentViewObjectId");
                StylingParams noteStyling = extractBulkStylingParams(params);
                ImageParams noteImageParams = extractBulkImageParams(params);

                // Resolve batch-created parent container (group or element)
                IDiagramModelContainer batchParent = findBatchCreatedObject(parentVoId, createdGroups, createdViewObjects);

                // Resolve batch-created view for viewId back-reference
                IArchimateDiagramModel noteBatchView = coalesceQueuedView(sessionId, viewId, createdViews);

                PreparedMutation<ViewNoteDto> notePrepared = prepareAddNoteToView(sessionId, viewId, content, null, null, x, y, width, height,
                        batchParent != null ? null : parentVoId,
                        batchParent, noteStyling, noteImageParams, noteBatchView);
                // Track note for viewObjectId resolution in update-view-object
                if (notePrepared.rawObject() instanceof IDiagramModelNote note) { createdNotes.put(operationIndex, note); }
                yield notePrepared;
            }
            case "add-view-reference-to-view" -> {
                String viewId = requireParam(params, "viewId");
                String referencedViewId = requireParam(params, "referencedViewId");
                Integer x = optionalIntParam(params, "x"); Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width"); Integer height = optionalIntParam(params, "height");
                String parentVoId = optionalParam(params, "parentViewObjectId");
                StylingParams refStyling = extractBulkStylingParams(params);

                // Resolve batch-created parent container (group or element)
                IDiagramModelContainer batchParent = findBatchCreatedObject(parentVoId, createdGroups, createdViewObjects);

                // Resolve batch-created TARGET view, and separately the REFERENCED one: two ids,
                // two slots, so their failures stay tellable apart.
                IArchimateDiagramModel refBatchView = coalesceQueuedView(sessionId, viewId, createdViews);
                IArchimateDiagramModel embedded = findBackReferenced(referencedViewId, createdViews);

                yield prepareAddViewReferenceToView(sessionId, viewId, referencedViewId, x, y,
                        width, height,
                        batchParent != null ? null : parentVoId,
                        batchParent, refBatchView, embedded, refStyling);
            }
            case "add-image-to-view" -> {
                String viewId = requireParam(params, "viewId");
                String imagePath = requireParam(params, "imagePath");
                Integer x = optionalIntParam(params, "x"); Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width"); Integer height = optionalIntParam(params, "height");
                String parentVoId = optionalParam(params, "parentViewObjectId");
                StylingParams imgStyling = extractBulkStylingParams(params);
                // Follow-up: Step 5 borderColor + documentation surface.
                String imgBorderColor = optionalParam(params, "borderColor");
                String imgDocumentation = optionalParam(params, "documentation");

                // Resolve batch-created parent container (group or element)
                IDiagramModelContainer batchParent = findBatchCreatedObject(parentVoId, createdGroups, createdViewObjects);

                // Resolve batch-created TARGET view
                IArchimateDiagramModel imgBatchView = coalesceQueuedView(sessionId, viewId, createdViews);

                yield prepareAddImageToView(sessionId, viewId, imagePath, x, y, width, height,
                        batchParent != null ? null : parentVoId,
                        batchParent, imgBatchView, imgStyling,
                        imgBorderColor, imgDocumentation);
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
            case "create-specialization" -> {
                String name = requireParam(params, "name");
                String conceptType = requireParam(params, "conceptType");
                String imagePath = optionalParam(params, "imagePath");
                yield prepareCreateSpecialization(name, conceptType, imagePath);
            }
            case "update-specialization" -> {
                String name = requireParam(params, "name");
                String conceptType = requireParam(params, "conceptType");
                // newName is now optional (at-least-one-of guard).
                String newName = optionalParam(params, "newName");
                String imagePath = optionalParam(params, "imagePath");
                boolean clearImagePath = Boolean.TRUE.equals(
                        optionalBoolParam(params, "clearImagePath"));
                yield prepareUpdateSpecialization(name, conceptType, newName,
                        imagePath, clearImagePath);
            }
            case "delete-specialization" -> {
                String name = requireParam(params, "name");
                String conceptType = requireParam(params, "conceptType");
                boolean force = Boolean.TRUE.equals(optionalBoolParam(params, "force"));
                yield prepareDeleteSpecialization(name, conceptType, force);
            }
            default -> throw new ModelAccessException(
                    "Unsupported tool '" + tool + "'. Supported: "
                            + BulkOperation.SUPPORTED_TOOLS,
                    ErrorCode.INVALID_PARAMETER);
        };
    }

    /**
     * Finds an object an earlier operation in this same bulk call created, by entity ID, in the
     * back-reference map for its kind. One lookup serves element, relationship, view, view object
     * and view connection: the maps differ only in what they hold and all are {@link IIdentifier}.
     *
     * <p>Comparing {@code entityId.equals(candidate.getId())} behind a null-ID guard keeps all five
     * answering null for an absent {@code entityId} — the other order already did,
     * {@code "x".equals(null)} being false rather than a failure — and changes only the reverse: a
     * candidate whose own ID is null used to raise in four of the five, and is now not a match.</p>
     */
    private <T extends IIdentifier> T findBackReferenced(String entityId, Map<Integer, T> created) {
        if (entityId == null) {
            return null;
        }
        for (T candidate : created.values()) {
            if (entityId.equals(candidate.getId())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Finds an object an earlier operation in this same call created, by id, across both tracking
     * maps. Answers addressability — "is this id something this call has already made?" — not
     * suitability for any particular use.
     *
     * <p>Nesting was the first question asked of it, but the same answer decides whether an id can
     * be an {@code update-view-object} target. Keeping one finder is what stops the two drifting:
     * while the update path had its own groups-blind finder, an id that worked as a
     * {@code parentViewObjectId} failed as a {@code viewObjectId} in the same call. Whether a hit
     * may be a parent is a separate question, answered downstream where parents are resolved and
     * notes and connections are rejected.</p>
     *
     * <p>ONE EXCEPTION, admissible only because it cannot drift: a NOTE. The update arm consults
     * {@code findBackReferenced(viewObjectId, createdNotes)} when this finder misses. That second
     * lookup cannot reopen the asymmetry above, because the parent answer for a note is a PERMANENT
     * no — {@code resolveParentContainer} admits groups and elements only — so the two questions
     * genuinely differ for a note where for a group they must not. Nothing that CAN be nested was
     * widened: this still returns {@code IDiagramModelContainer}, so the five parent call sites keep
     * a compile-time guarantee instead of five runtime checks.</p>
     */
    private IDiagramModelContainer findBatchCreatedObject(
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
     *
     * <p>The pre-resolved case serves both deferred paths. {@code bulk-mutate} supplies a container
     * from its batch-created-parent maps; an open {@code begin-batch} window supplies one from the
     * commands it has queued but not yet executed. Either way the container is a real object that is
     * not yet reachable by walking the view, which is precisely why it cannot be found by the
     * live-lookup arm below. Both sources admit only groups and elements, so the group-or-element
     * check the live arm performs is already satisfied by construction.</p>
     *
     * <p>The one check the pre-resolved arm does <em>not</em> get for free is view membership. The
     * live arm enforces it by searching the named view; the pre-resolved arm is handed a container
     * that belongs to no view yet, so the operation's own {@code viewId} was silently ignored and
     * the child landed wherever the parent was destined. Comparing the parent's
     * {@code getDiagramModel()} against the view cannot fix that — a detached parent answers null,
     * so it would reject every legitimate nested add — hence the comparison against the destined
     * diagram both deferred paths already record. An unknown destination abstains.</p>
     */
    private IDiagramModelContainer resolveParentContainer(
            IArchimateDiagramModel view,
            String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            String sessionId) {
        // A queued container coalesces in ABOVE the check below, not beside it: entering through
        // the live arm would resolve a parent without ever testing which view it is destined for.
        IDiagramModelContainer resolved = (batchParentContainer != null) ? batchParentContainer
                : mutationDispatcher.queuedParentContainer(sessionId, parentViewObjectId);
        if (resolved != null) {
            // Both sources admit only groups and elements, so this always matches; the pattern is
            // what narrows a container to the object whose destined diagram can be asked for.
            IDiagramModel destined = (resolved instanceof IDiagramModelObject parent)
                    ? AnchorResolver.destinedDiagramOf(parent, bulkPendingParents.get(),
                            mutationDispatcher.queuedParents(sessionId))
                    : null;
            if (destined != null && destined != view) {
                throw new ModelAccessException(
                        "Parent view object '" + resolved.getId() + "' belongs to view '"
                                + destined.getName() + "' (" + destined.getId() + "), but this "
                                + "operation names view '" + view.getName() + "' ("
                                + view.getId() + ").",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "A parent and its child must be in the same view. Either name the parent's "
                                + "own view on this operation, or create a parent in the view you "
                                + "named.",
                        null);
            }
            return resolved;
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

    /** A view this call created, else one the enclosing batch queued, else null. */
    private IArchimateDiagramModel coalesceQueuedView(String sessionId, String viewId,
            Map<Integer, IArchimateDiagramModel> createdViews) {
        IArchimateDiagramModel batchView = findBackReferenced(viewId, createdViews);
        return batchView != null ? batchView
                : mutationDispatcher.queuedCreatedView(sessionId, viewId);
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
     * Prepares an add-connection-to-view mutation with direct view object references
     * (for bulk back-references). Hybrid mode: accepts raw IDiagramModelArchimateObject
     * for source/target that are back-referenced, or null to look up from the diagram.
     *
     * <p>{@code batchView} carries a view the same call created, which is not yet reachable through
     * committed containment. It had its own null-passing overload until the last caller of that
     * overload went away; the parameter is explicit at the one call site instead.</p>
     */
    private PreparedMutation<ViewConnectionDto> prepareAddConnectionToViewDirect(
            String sessionId, String viewId, String relationshipId,
            IDiagramModelArchimateObject directSource, String sourceViewObjectId,
            IDiagramModelArchimateObject directTarget, String targetViewObjectId,
            List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints,
            IArchimateRelationship directRelationship,
            StylingParams styling, Boolean showLabel, Integer textPosition,
            IArchimateDiagramModel batchView) {
        IArchimateModel model = requireAndCaptureModel();

        // Find view — this call's own creation first, then one an enclosing batch queued
        IArchimateDiagramModel view = resolveViewOrThrow(model, viewId, batchView != null
                ? batchView : mutationDispatcher.queuedCreatedView(sessionId, viewId));

        // Find relationship — the direct reference, then an enclosing batch's queue, then the model
        IArchimateRelationship relationship = directRelationship != null ? directRelationship
                : mutationDispatcher.queuedCreatedRelationship(sessionId, relationshipId);
        if (relationship == null) {
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

        // Resolve source/target view objects — a back-referenced object wins over the id lookup,
        // and an enclosing batch's queued object stands in before the containment walk. That
        // fallback is view-scoped by construction, so an object destined for a different view keeps
        // taking the ordinary not-found path rather than joining two diagrams together.
        // Populated only when an id still has to be resolved: a call that back-references both ends
        // supplies them directly and never walks the view's containment at all.
        Map<String, IDiagramModelArchimateObject> viewObjects = new LinkedHashMap<>();
        if (directSource == null || directTarget == null) {
            collectViewObjectMap(view, viewObjects);
        }
        IDiagramModelArchimateObject sourceViewObj = resolveConnectionEndOrThrow(viewObjects,
                directSource != null ? directSource
                        : mutationDispatcher.queuedConnectionEnd(sessionId, sourceViewObjectId, view),
                sourceViewObjectId, "Source");
        IDiagramModelArchimateObject targetViewObj = resolveConnectionEndOrThrow(viewObjects,
                directTarget != null ? directTarget
                        : mutationDispatcher.queuedConnectionEnd(sessionId, targetViewObjectId, view),
                targetViewObjectId, "Target");

        // Validate relationship-element match.
        // Skip validation only for a relationship THIS CALL created — connect() is deferred to
        // command execution, so getSource()/getTarget() are null AND the relationship is not yet in
        // containment (eContainer == null), and nothing outside this call knows its ends. A
        // relationship pulled from an ENCLOSING batch's queue satisfies those same three conjuncts
        // while its ends ARE knowable, so the direct reference is what tells the two apart; without
        // it the skip would fire and silently drop the check on every outer-queued relationship.
        // The ends are read through the dispatcher, which answers for a live relationship directly
        // and for a queued one off the create that will connect it. Both reads are RAW: an end
        // narrowed to IArchimateElement first would read as null for a relationship-typed end,
        // which satisfies all three conjuncts and would skip the check on exactly the shape it
        // exists to reject.
        boolean isSameCallBackRef = directRelationship != null
                && relationship.getSource() == null
                && relationship.getTarget() == null && relationship.eContainer() == null;
        if (!isSameCallBackRef) {
            validateConnectionEndpointMatch(relationshipId, relationship,
                    mutationDispatcher.relationshipSource(sessionId, relationship),
                    mutationDispatcher.relationshipTarget(sessionId, relationship),
                    sourceViewObj, targetViewObj);
        }

        // Convert absolute bendpoints to relative if provided
        List<BendpointDto> effectiveBendpoints = bendpoints;
        if (absoluteBendpoints != null && !absoluteBendpoints.isEmpty()) {
            effectiveBendpoints = ConnectionResponseBuilder.convertAbsoluteToRelative(
                    absoluteBendpoints, sourceViewObj, targetViewObj);
        }

        // Validate styling if provided
        if (styling != null && styling.hasAnyValue()) {
            StylingHelper.validateConnectionStylingParams(styling);
        }

        // Create connection
        IDiagramModelArchimateConnection conn =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(relationship);
        ConnectionResponseBuilder.applyBendpointsToConnection(conn, effectiveBendpoints);

        // Apply styling at creation time
        if (styling != null && styling.hasAnyValue()) {
            if (styling.lineColor() != null) {
                conn.setLineColor(styling.lineColor().isEmpty() ? null : styling.lineColor());
            }
            if (styling.fontColor() != null) {
                conn.setFontColor(styling.fontColor().isEmpty() ? null : styling.fontColor());
            }
            if (styling.lineWidth() != null) {
                conn.setLineWidth(styling.lineWidth());
            }
            // Typography composite + lineStyle bitmask (arrow bits preserved).
            StylingHelper.applyConnectionStyling(conn, styling);
        }

        // Apply label visibility at creation time
        if (showLabel != null) {
            conn.setNameVisible(showLabel);
        }

        // Apply label position at creation time
        if (textPosition != null) {
            conn.setTextPosition(textPosition);
        }

        Command cmd = guardEndpoint(guardEndpoint(
                new AddConnectionToViewCommand(conn, sourceViewObj, targetViewObj),
                targetViewObj, model, relationship.getName()),
                sourceViewObj, model, relationship.getName());

        // Build DTO with styling info included in response (includes typography;
        // lineStyle is view-object-only — not surfaced on connection DTOs).
        String dtoLineColor = StylingHelper.readConnectionLineColor(conn);
        String dtoFontColor = StylingHelper.readConnectionFontColor(conn);
        Integer dtoLineWidth = StylingHelper.readConnectionLineWidth(conn);
        Boolean dtoNameVisible = StylingHelper.readConnectionNameVisible(conn);
        String dtoFontName = StylingHelper.readConnectionFontName(conn);
        Integer dtoFontSize = StylingHelper.readConnectionFontSize(conn);
        String dtoFontStyle = StylingHelper.readConnectionFontStyle(conn);

        ViewConnectionDto baseDto = ConnectionResponseBuilder.buildConnectionResponseDto(
                conn.getId(), relationship, sourceViewObj.getId(), targetViewObj.getId(),
                effectiveBendpoints, sourceViewObj, targetViewObj, conn.getTextPosition());

        ViewConnectionDto dto = ConnectionResponseBuilder.withConnectionStyling(baseDto,
                dtoLineColor, dtoLineWidth, dtoFontColor, dtoNameVisible,
                dtoFontName, dtoFontSize, dtoFontStyle);

        return new PreparedMutation<>(cmd, dto, conn.getId(), conn);
    }

    // ---- Bulk parameter helpers ----

    private String requireParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (!(value instanceof String str) || str.isBlank()) {
            throw ParamNameDiagnostics.missingBulkParameter(params, key);
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
     * Extracts an optional string param preserving empty strings (for set/change/clear semantics).
     * Returns null if absent; empty string if explicitly set to "" (clear); the value otherwise.
     * Mirrors {@code HandlerUtils.optionalStringParamAllowEmpty} for the bulk-mutate path.
     */
    private String optionalAllowEmptyParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof String str) {
            return str;
        }
        return null;
    }

    /**
     * Extracts optional styling parameters from bulk operation params.
     * Returns null if no styling params are present.
     *
     * <p>Includes the three
     * new fields {@code figureType}, {@code textAlignment}, {@code verticalTextAlignment}
     * — read via {@link #optionalParam} (empty-string treated as null since these have
     * no symmetric "clear" semantics, matching the handler's
     * {@code extractStylingParams} convention).</p>
     */
    private StylingParams extractBulkStylingParams(Map<String, Object> params) {
        String fillColor = optionalAllowEmptyParam(params, "fillColor");
        String lineColor = optionalAllowEmptyParam(params, "lineColor");
        String fontColor = optionalAllowEmptyParam(params, "fontColor");
        Integer opacity = optionalIntParam(params, "opacity");
        Integer lineWidth = optionalIntParam(params, "lineWidth");
        String figureType = optionalParam(params, "figureType");
        String textAlignment = optionalParam(params, "textAlignment");
        String verticalTextAlignment = optionalParam(params, "verticalTextAlignment");

        // Typography (fontName allows empty for system-default clear); gradient/borderType/lineStyle allow empty for clear-to-default symmetry.
        String fontName = optionalAllowEmptyParam(params, "fontName");
        Integer fontSize = optionalIntParam(params, "fontSize");
        String fontStyle = optionalParam(params, "fontStyle");
        String lineStyle = optionalAllowEmptyParam(params, "lineStyle");
        String gradient = optionalAllowEmptyParam(params, "gradient");
        String borderType = optionalAllowEmptyParam(params, "borderType");
        Boolean deriveLineColor = (params.get("deriveLineColor") instanceof Boolean b) ? b : null;
        Integer outlineOpacity = optionalIntParam(params, "outlineOpacity");
        Boolean recede = (params.get("recede") instanceof Boolean rb) ? rb : null;

        if (fillColor == null && lineColor == null && fontColor == null
                && opacity == null && lineWidth == null
                && figureType == null && textAlignment == null && verticalTextAlignment == null
                && fontName == null && fontSize == null && fontStyle == null
                && lineStyle == null && gradient == null && borderType == null
                && deriveLineColor == null && outlineOpacity == null && recede == null) {
            return null;
        }
        return new StylingParams(fillColor, lineColor, fontColor, opacity, lineWidth,
                figureType, textAlignment, verticalTextAlignment,
                fontName, fontSize, fontStyle, lineStyle, gradient, borderType,
                deriveLineColor, outlineOpacity, recede);
    }

    /**
     * Extracts optional image parameters from bulk operation params.
     * Returns null if no image params are present.
     * Cross-ref: ViewPlacementHandler.extractImageParams() uses
     * HandlerUtils.optionalStringParamAllowEmpty() for the same empty-string semantics.
     * This method inlines that logic because the model layer uses optionalParam() instead.
     */
    private ImageParams extractBulkImageParams(Map<String, Object> params) {
        String imagePath = optionalParam(params, "imagePath");
        // Allow empty string for imagePath (means "remove image") —
        // mirrors HandlerUtils.optionalStringParamAllowEmpty() semantics
        if (imagePath == null && params.containsKey("imagePath")) {
            Object raw = params.get("imagePath");
            if (raw instanceof String s && s.isEmpty()) {
                imagePath = "";
            }
        }
        String imagePosition = optionalParam(params, "imagePosition");
        String showIcon = optionalParam(params, "showIcon");
        if (imagePath == null && imagePosition == null && showIcon == null) {
            return null;
        }
        return new ImageParams(imagePath, imagePosition, showIcon);
    }

    /**
     * Parses a labelPosition string ("source"/"middle"/"target") to integer (0/1/2)
     * from bulk-mutate operation parameters. Mirrors the handler-level parseLabelPosition.
     */
    private Integer parseBulkLabelPosition(Map<String, Object> params) {
        Object value = params.get("labelPosition");
        if (value == null) return null;
        String pos = value.toString().toLowerCase();
        return switch (pos) {
            case "source" -> 0;
            case "middle" -> 1;
            case "target" -> 2;
            default -> throw new IllegalArgumentException(
                    "Invalid labelPosition: '" + pos + "'. Must be 'source', 'middle', or 'target'.");
        };
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

    // ---- Mutation helper methods ----

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
     * Validates that the target folder's root layer matches the concept's expected layer,
     * as defined by Archi's own default-folder mapping (the same authority host Archi's
     * save-time checkIntegrity uses). User-created subfolders inherit their layer from their
     * root ancestor folder. Applies to elements, junctions and relationships alike.
     */
    void validateFolderLayerMatch(IArchimateConcept concept, IFolder folder) {
        // Delegate the decision to the shared model/ predicate so create-element, immediate move,
        // and the move command's execute-time re-check all reject identically. A false return also
        // covers the no-governing-folder case (non-ArchiMate / outside layer governance): such an
        // object is not subject to folder-type checkIntegrity either, so skipping stays consistent
        // with host Archi — NOT a silent-acceptance gap.
        IArchimateModel folderModel = folder.getArchimateModel();
        if (!FolderOperations.hasLayerMismatch(folderModel, concept, folder)) {
            return;
        }

        FolderType expectedType = folderModel.getDefaultFolderForObject(concept).getType();
        FolderType actualType = FolderOperations.getRootFolder(folder).getType();
        String elementType = concept.eClass().getName();
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

    /**
     * Walks up the folder hierarchy to find the root folder (direct child of model).
     * Thin delegate to {@link FolderOperations#getRootFolder} — kept for direct callers.
     */
    IFolder getRootFolder(IFolder folder) {
        return FolderOperations.getRootFolder(folder);
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

    // ---- Control-loop adapters (2026-05-15) ----

    /**
     * Captures the dominant-hub descriptor for the density-aware
     * discriminator's hub sub-signal + the PASS-honest diagnosis, from
     * the EXISTING {@code detectHubElements} read ONLY (NOT a new
     * {@code LayoutQualityAssessor} metric). The first element is the
     * highest-fan-out hub ({@code detectHubElements} sorts by connection
     * count descending). Returns {@code null} (hub sub-signal absent) on any
     * failure or when the view has no hub element.
     */
    private HubExtent captureHubExtent(String viewId) {
        try {
            DetectHubElementsResultDto hubs = detectHubElements(viewId);
            if (hubs == null || hubs.elements() == null
                    || hubs.elements().isEmpty()) {
                return null;
            }
            HubElementEntryDto top = hubs.elements().get(0);
            return new HubExtent(top.connectionCount(),
                    top.width(), top.height());
        } catch (RuntimeException e) {
            logger.warn("captureHubExtent failed (viewId={}); density-aware "
                    + "hub sub-signal absent for this run", viewId, e);
            return null;
        }
    }

    /**
     * The thin EMF read-site for the SOUND one-sided pre-routing
     * infeasibility certificate (the escalate lever).
     * Sibling-symmetric with the shipped {@code rnb.degraded()} pre-loop
     * short-circuit: a pure pre-loop test ⇒ DTO-return-without-loop-entry.
     *
     * <p>Computes the element-union canvas geometry over the view's
     * <strong>ArchiMate elements ONLY</strong> (groups / notes excluded — the
     * calibrated N) using the SAME absolute-coordinate convention
     * {@link ConnectionResponseBuilder#computeAbsoluteCenter} uses (the
     * parent-chain offset walk), reusing the EXISTING
     * {@link #collectAllViewObjectMap} reader. NO new
     * {@code LayoutQualityAssessor} metric — only reads already
     * available at the Request-build site. The (pure, JUnitCore-pinned)
     * decision is delegated to
     * {@link SpacingPreconditionInfeasibilityCertificate}; this method never
     * touches — and is byte-disjoint from — {@link SpacingControlLoop}.
     *
     * <p>Any failure ⇒ {@link SpacingPreconditionInfeasibilityCertificate.Decision#proceed()}
     * (Type-II safe — the loop runs exactly as today; zero regression).
     */
    private SpacingPreconditionInfeasibilityCertificate.Decision
            evaluateSpacingPrecondition(IArchimateModel model,
                    String viewId, LayoutMetrics initialMetrics) {
        try {
            EObject viewObj =
                    ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
                return SpacingPreconditionInfeasibilityCertificate.Decision
                        .proceed();
            }
            Map<String, IDiagramModelObject> all = new LinkedHashMap<>();
            collectAllViewObjectMap(diagramModel, all);
            int n = 0;
            long minX = Long.MAX_VALUE;
            long minY = Long.MAX_VALUE;
            long maxX = Long.MIN_VALUE;
            long maxY = Long.MIN_VALUE;
            double boxDimSum = 0.0;
            for (IDiagramModelObject dmo : all.values()) {
                if (!(dmo instanceof IDiagramModelArchimateObject)) {
                    continue;   // ArchiMate elements ONLY (no groups/notes)
                }
                IBounds b = dmo.getBounds();
                long ax = b.getX();
                long ay = b.getY();
                // Resolve to absolute canvas coords — SAME parent-chain walk
                // as ConnectionResponseBuilder.computeAbsoluteCenter (groups
                // ARE IDiagramModelObject so their offsets are summed).
                Object parent = dmo.eContainer();
                while (parent instanceof IDiagramModelObject p) {
                    IBounds pb = p.getBounds();
                    ax += pb.getX();
                    ay += pb.getY();
                    parent = p.eContainer();
                }
                int w = b.getWidth();
                int h = b.getHeight();
                minX = Math.min(minX, ax);
                minY = Math.min(minY, ay);
                maxX = Math.max(maxX, ax + w);
                maxY = Math.max(maxY, ay + h);
                boxDimSum += (w + h) / 2.0;
                n++;
            }
            if (n <= 0) {
                return SpacingPreconditionInfeasibilityCertificate.Decision
                        .proceed();
            }
            double area = (double) (maxX - minX) * (double) (maxY - minY);
            double avgBoxDim = boxDimSum / n;
            HubExtent hub = captureHubExtent(viewId);
            double measuredAvg = initialMetrics != null
                    ? initialMetrics.avgSpacingPx() : Double.NaN;
            Integer hubW = hub != null ? hub.hubWidthPx() : null;
            Integer hubH = hub != null ? hub.hubHeightPx() : null;
            Integer hubC = hub != null
                    ? hub.maxHubConnectionCount() : null;
            return SpacingPreconditionInfeasibilityCertificate.evaluate(
                    n, area, avgBoxDim, measuredAvg, hubW, hubH, hubC,
                    diagramModel.getViewpoint());
        } catch (RuntimeException e) {
            logger.warn("Spacing-precondition certificate evaluation failed "
                    + "(viewId={}); proceeding to the loop as today", viewId,
                    e);
            return SpacingPreconditionInfeasibilityCertificate.Decision
                    .proceed();
        }
    }

    /**
     * Builds the ONE-SHOT escalate
     * hub-resize ("Scoped Option B"). Resizes the dominant hub toward the HH-like fan-out regime
     * (the
     * <strong>fan-out-scaled</strong> {@code ≥
     * SpacingControlLoop.requiredHubMinWidthPx(conns) ×
     * requiredHubMinHeightPx(conns)} — the SAME minimum the predicate
     * {@code hubUnderSizedForFanOut} uses, NOT the flat 300×250 base; a
     * flagged hub therefore ALWAYS resizes strictly larger) via
     * the EXISTING {@link UpdateViewObjectCommand} (clean — the
     * convenience-tool mutation type {@code computeAdjustViewSpacing}
     * already emits for group bounds; NOT a {@code RoutingPipeline} /
     * sibling primitive), wrapped in the SWT-dispatch
     * {@link GefSpacingMutationCommand} so it inherits the
     * SWT-marshalling + partial-commit graceful-degradation guard and the
     * single-undo finalize machinery.
     *
     * <p>Returns {@code null} when there is no LARGE under-sized hub to
     * resize (escalation then degrades to spacing-only — the loop is
     * unaffected). The {@link SpacingControlLoop} guarantees this is invoked
     * at most ONCE per loop (first escalate iteration).</p>
     */
    private SpacingMutationCommand buildDensityHubResizeCommand(
            String viewId, IArchimateModel model) {
        try {
            HubExtent he = captureHubExtent(viewId);
            // Deliberately asked twice: this call short-circuits BEFORE the detectHubElements scan
            // below, which is the expensive part. The rectangle helper asks again because it must
            // not depend on a caller having done so.
            if (!SpacingControlLoop.hubUnderSizedForFanOut(he)) {
                return null;
            }
            DetectHubElementsResultDto hubs = detectHubElements(viewId);
            HubElementEntryDto top = hubs.elements().get(0);
            EObject obj = ArchimateModelUtils.getObjectByID(
                    model, top.viewObjectId());
            if (!(obj instanceof IDiagramModelObject dmo)) {
                return null;
            }
            IBounds b = dmo.getBounds();
            // The rectangle itself is SpacingControlLoop's, so the target is derived from the same
            // connection count the predicate judged and the two cannot drift apart across a file
            // boundary. Null means nothing to resize.
            int[] rect = SpacingControlLoop.escalateHubResizeRect(
                    he, b.getX(), b.getY(), b.getWidth(), b.getHeight());
            if (rect == null) {
                return null;
            }
            // postMetrics unused for the hub-resize adapter — the loop only
            // drives execute()/undo() on it; observeLayout() reads the
            // spacing command's cached metrics.
            return new GefSpacingMutationCommand(
                    new UpdateViewObjectCommand(dmo, rect[0], rect[1], rect[2], rect[3]),
                    /*postMetrics=*/ null);
        } catch (RuntimeException e) {
            logger.warn("buildDensityHubResizeCommand failed (viewId={}); "
                    + "escalation degrades to spacing-only", viewId, e);
            return null;
        }
    }

    /**
     * Either the route-normalized baseline metrics ({@code degraded == false})
     * or a signal that the tool's own reroute pass materially degraded the
     * input baseline ({@code degraded == true}; {@code metrics} carries the bare
     * input metrics, returned untouched per the guarded-form safety net — see
     * {@link SpacingControlLoop#REASON_REROUTE_DEGRADED_INPUT_BASELINE}).
     */
    private record RouteNormalizedBaseline(
            LayoutMetrics metrics, boolean degraded) {
        static RouteNormalizedBaseline ok(LayoutMetrics m) {
            return new RouteNormalizedBaseline(m, false);
        }
        static RouteNormalizedBaseline degraded(LayoutMetrics bare) {
            return new RouteNormalizedBaseline(bare, true);
        }
    }

    /**
     * Route-normalizes the pre-loop baseline so it is measured on the SAME
     * routing basis as every per-step {@code postState}.
     *
     * <p><strong>Why.</strong> The loop seeds its best-state from a bare,
     * un-rerouted assessment while every per-step state is freshly re-routed.
     * Comparing across different routing bases made the first rerouted step
     * ≈ always regress vs the baseline; measuring the baseline on a routed
     * basis removes that false regression.</p>
     *
     * <p><strong>How.</strong> Route a DETACHED copy of the view and assess
     * that — never the live model — so the measurement is invisible: zero ecore
     * notifications, no command-stack trace, no model-changed signal. The copy
     * is discarded, so no undo is needed. {@link SpacingControlLoop} stays
     * pure-EMF-free; route-normalization never enters {@code iterate}.</p>
     *
     * <p><strong>Guarded form.</strong> If the route-normalized baseline scores
     * a strictly lower {@link LayoutMetrics#thresholdsMet()} than the bare
     * baseline, the tool's own reroute would degrade the input — return
     * {@link RouteNormalizedBaseline#degraded} so the caller returns the bare
     * input untouched with
     * {@link SpacingControlLoop#REASON_REROUTE_DEGRADED_INPUT_BASELINE}. If
     * {@code computeAutoRoutePass} returns null or throws, fall back to the
     * bare baseline.</p>
     *
     * @param viewId     the view under control-loop optimization
     * @param model      the owning model (for {@code getObjectByID})
     * @param bareBefore the bare {@code assessLayout(viewId)} the caller
     *                   already captured
     * @return route-normalized baseline, or a degraded-input signal
     */
    private RouteNormalizedBaseline routeNormalizedBaseline(
            String viewId, IArchimateModel model,
            AssessLayoutResultDto bareBefore) {
        LayoutMetrics bare = LayoutQualityScalar.toLayoutMetrics(bareBefore);
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
            return RouteNormalizedBaseline.ok(bare);
        }
        AssessLayoutResultDto routeNormAssessment;
        try {
            // Measure on a DETACHED copy of the view, never the live model, so
            // the measurement emits ZERO ecore notifications and leaves no
            // command-stack trace. sourceConnections are containment, so the
            // copy carries the connections + geometry; the routing helpers
            // operate purely on the passed diagram object.
            IArchimateDiagramModel measured =
                    (IArchimateDiagramModel) EcoreUtil.copy(diagramModel);
            AutoRoutePassResult routeResult =
                    computeAutoRoutePass(viewId, measured, model);
            if (routeResult == null) {
                // Nothing to route — bare basis == route-normalized basis.
                return RouteNormalizedBaseline.ok(bare);
            }
            // Execute on the copy via a PLAIN compound — NonNotifyingCompoundCommand
            // fires global start/end events that NPE off the SWT UI thread and add
            // nothing on a detached tree. The silent-measurement window is a
            // belt-and-suspenders guard against ever advancing the changed signal.
            CompoundCommand measure = new CompoundCommand();
            for (Object c : routeResult.compound.getCommands()) {
                measure.add((Command) c);
            }
            mutationDispatcher.beginSilentMeasurement();
            try {
                measure.execute();
                routeNormAssessment = assessLayout(measured, viewId, false);
            } finally {
                mutationDispatcher.endSilentMeasurement();
            }
        } catch (RuntimeException e) {
            logger.warn("Route-normalized baseline pass failed "
                    + "(viewId={}); falling back to bare baseline", viewId, e);
            return RouteNormalizedBaseline.ok(bare);
        }
        LayoutMetrics routeNorm = LayoutQualityScalar.toLayoutMetrics(routeNormAssessment);
        if (routeNorm.thresholdsMet() < bare.thresholdsMet()) {
            // Guarded form — the tool's reroute degraded the
            // input. Preserve the safety net deliberately.
            return RouteNormalizedBaseline.degraded(bare);
        }
        return RouteNormalizedBaseline.ok(routeNorm);
    }

    /**
     * Validates and resolves the optional caller-supplied iteration budget
     * against the per-tool default (5 element / 5 group / 8 composer).
     * Null → default. Out-of-range [1, 20] → throw
     * {@link ModelAccessException} INVALID_PARAMETER per architecture-spec
     * § 1.4 handler-level validation contract.
     */
    private static int resolveIterationBudget(
            Integer caller, int defaultBudget) throws ModelAccessException {
        int budget = (caller != null) ? caller : defaultBudget;
        if (budget < 1 || budget > 20) {
            throw new ModelAccessException(
                    "iterationBudget must be in [1, 20]; got: " + budget,
                    ErrorCode.INVALID_PARAMETER);
        }
        return budget;
    }

    /**
     * Adapter wrapping a GEF {@link Command} as a
     * {@link SpacingMutationCommand} so the {@link SpacingControlLoop} can
     * drive {@link Command#execute()} + {@link Command#undo()} via the
     * pure-EMF-free callback interface. Carries the iteration's post-state
     * {@link LayoutMetrics} captured by the
     * {@link #computeAdjustViewSpacing} helper during its temp-apply +
     * assess + undo dance (architecture-spec § 1.6).
     */
    private static final class GefSpacingMutationCommand
            implements SpacingMutationCommand {
        private final Command gefCommand;
        private final LayoutMetrics postMetrics;

        GefSpacingMutationCommand(Command gefCommand, LayoutMetrics postMetrics) {
            this.gefCommand = gefCommand;
            this.postMetrics = postMetrics;
        }

        Command gefCommand() {
            return gefCommand;
        }

        LayoutMetrics postMetrics() {
            return postMetrics;
        }

        /**
         * Marshals execute to the SWT UI thread via
         * {@link SwtUiThreadDispatcher#runOnUiThread} — targeted
         * marshalling fix (2026-05-15) for the
         * {@code iteration_apply_failed_at_iteration_0} deterministic failure.
         */
        @Override
        public void execute() {
            SwtUiThreadDispatcher.runOnUiThread(gefCommand::execute);
        }

        /**
         * Marshals undo to the SWT UI thread — symmetric with execute. See
         * {@link #execute()} for fix rationale.
         */
        @Override
        public void undo() {
            SwtUiThreadDispatcher.runOnUiThread(gefCommand::undo);
        }
    }

    // ---- Approval helpers (reshaped to store-the-request) ----
    // ---- Store-the-request proposal storage (deferred rebuild + staleness capture) ----

    /**
     * Stores a proposal: it holds a <strong>deferred rebuild handle</strong> ({@code rebuild}) plus a
     * {@link StalenessCapture} over {@code targetIds}. Most callers pass a handle that re-invokes the
     * same {@code prepareXxx(...)} against the current model at approve-time; the fourteen
     * reviewed-or-reject sites pass one returning the already-built compound, and for those the tracked
     * set is the only staleness check there is. The propose-time {@code entity}/{@code effectDescription}/{@code
     * intent} still feed the card unchanged. The caller computes the card's
     * {@code entity}/{@code effectDescription} from its own propose-time {@code prepareXxx} call and
     * discards that command; only this deferred handle's command is ever executed.
     *
     * @param targetIds the pre-existing object ids whose human edit/removal between propose and approve
     *                  should reject-stale the proposal (empty for pure creates)
     */
    private ProposalContext storeAsProposal(String sessionId, String tool,
            Supplier<PreparedMutation<?>> rebuild, Set<String> targetIds, Object entity, String description,
            Map<String, Object> currentState, Map<String, Object> proposedChanges,
            String validationSummary, String effectDescription, String intent) {
        Instant now = Instant.now();
        StalenessCapture capture = mutationDispatcher.captureStaleness(sessionId, targetIds);
        PendingProposal proposal = new PendingProposal(
                null, tool, description, rebuild, capture, entity,
                currentState, proposedChanges, validationSummary, now,
                effectDescription, intent);
        String proposalId = mutationDispatcher.storeProposal(sessionId, proposal);
        logger.info("Stored proposal '{}' for session '{}': {}", proposalId, sessionId,
                effectDescription != null ? effectDescription : description);
        return new ProposalContext(proposalId, description, now);
    }

    /** As above without the {@code effectDescription}/{@code intent} (both null — NON_NULL omission). */
    private ProposalContext storeAsProposal(String sessionId, String tool,
            Supplier<PreparedMutation<?>> rebuild, Set<String> targetIds, Object entity, String description,
            Map<String, Object> currentState, Map<String, Object> proposedChanges,
            String validationSummary) {
        return storeAsProposal(sessionId, tool, rebuild, targetIds, entity, description,
                currentState, proposedChanges, validationSummary, null, null);
    }

    /**
     * Builds an immutable, null-filtered set of target ids for {@link StalenessCapture}. The
     * pre-existing objects whose human edit/removal during the review window should reject-stale a
     * proposal — typically the entity being updated/deleted, plus obvious relationship/view endpoints.
     * A pure create passes no ids. Missed secondary targets degrade safely: the approve-time rebuild's
     * {@code prepareXxx} still throws on a vanished endpoint, surfacing as stale via {@link ProposalBuilder}.
     */
    private static Set<String> targetIds(String... ids) {
        Set<String> set = new java.util.LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    set.add(id);
                }
            }
        }
        return set;
    }

    /**
     * Builds the staleness target set for a <strong>compound</strong> proposal
     * (disposition (a)). Seeds with the given view/anchor ids, then
     * walks the already-built {@code compound}'s child commands and unions the id of every pre-existing
     * diagram object / connection the compound touches — read via each project-owned command's typed
     * accessor. This is <em>not</em> opaque GEF command-walking: these compounds are built exclusively
     * from our own {@code UpdateViewObjectCommand} / {@code UpdateViewConnectionCommand} /
     * {@code SetTextPositionCommand} / {@code AddConnectionToViewCommand} (Archi's accessor-less
     * {@code SetConstraintCommand} is never used here), so the touched objects are recoverable by type.
     *
     * <p>The broadened set lets the {@link ProposalStalenessGuard} reject-stale a frozen compound whose
     * affected child the human <em>deleted</em> ({@code decide} removed-path, no human-intervene needed)
     * or <em>edited/moved</em> ({@code decide} fingerprint-path; bounds folded in by disposition (b)) in
     * the review window — closing the coarse-policy residual for compounds. No layout
     * or routing algorithm is re-run; this only reads ids already resolved at propose-time.</p>
     *
     * <p>A connection an {@code AddConnectionToViewCommand} is <em>creating</em> is not yet in the model
     * (its id will not resolve at {@code capture}) — so it is harmlessly skipped, and its pre-existing
     * source/target endpoints are tracked instead. Any id {@code capture} cannot resolve degrades to the
     * {@link ProposalBuilder} rebuild-throw safety net, never an NPE.</p>
     */
    private static Set<String> compoundTargetIds(CompoundCommand compound, String... anchorIds) {
        return CompoundChildTargets.collect(compound, anchorIds);
    }

    // ---- Relationship endpoint-name resolution + effect-text formatting ----

    /**
     * Resolves a relationship's source/target element display names from the live model,
     * degrading each endpoint to its id when it has no name. Returns {@code null} when the id
     * does not resolve to an {@link IArchimateRelationship} (caller falls back to id-based text).
     *
     * <p>Mirrors {@link #resolveElementInfo}/{@link #resolveConnectionInfo} but pulls the
     * relationship's two endpoints. Private — no new {@code ArchiModelAccessor} interface surface.
     * Used at propose time (the relationship is still resolvable, pre-execute).</p>
     *
     * @return {@code String[]{sourceDisplay, targetDisplay}}, or {@code null} if not a relationship
     */
    private String[] resolveRelationshipEndpointNames(IArchimateModel model, String relationshipId) {
        EObject obj = ArchimateModelUtils.getObjectByID(model, relationshipId);
        if (obj instanceof IArchimateRelationship rel) {
            return new String[] {
                    endpointDisplayName(rel.getSource()),
                    endpointDisplayName(rel.getTarget())
            };
        }
        return null;
    }

    /** Display name for a relationship endpoint: its name, or its id when nameless, or null. */
    private static String endpointDisplayName(IArchimateConcept concept) {
        if (concept == null) {
            return null;
        }
        String name = concept.getName();
        return (name != null && !name.isBlank()) ? name : concept.getId();
    }

    /**
     * Pure formatter for relationship effect text — {@code <verb> <type>: '<source>' → '<target>'}
     * with an optional trailing consequence suffix (e.g. cascade counts). Package-private and
     * {@code static} so it is unit-testable headlessly (no EMF/OSGi). Callers pass already-resolved
     * display names (each degraded to id when nameless, or a literal id when resolution failed).
     */
    static String formatRelationshipEffect(String verb, String type,
            String sourceDisplay, String targetDisplay, String suffix) {
        String base = verb + " " + type + ": '" + sourceDisplay + "' → '" + targetDisplay + "'";
        return (suffix != null && !suffix.isBlank()) ? base + " " + suffix : base;
    }

    /**
     * {@code " '<viewName>'"} (with a leading space) when the name is usable, else {@code ""}.
     * Spliced directly after a proposal's dangling {@code view} token (e.g. {@code "... to view"} →
     * {@code "... to view 'Main View'"}). Blank/null degrades to no clause — never {@code "view ''"}.
     * Package-private and {@code static} so it is unit-testable headlessly (mirrors
     * {@link #formatRelationshipEffect}).
     */
    static String viewNameClause(String viewName) {
        return (viewName != null && !viewName.isBlank()) ? " '" + viewName + "'" : "";
    }

    /**
     * {@code "<preposition> view '<viewName>'"} when the name is usable, else {@code null} — for
     * use as a {@link #formatRelationshipEffect} suffix (connection effects) or an appended update
     * clause (e.g. {@code "in view 'Main View'"}). Null degrades to no clause. Package-private and
     * {@code static} for headless unit testing.
     */
    static String viewPhrase(String viewName, String preposition) {
        return (viewName != null && !viewName.isBlank())
                ? preposition + " view '" + viewName + "'" : null;
    }

    /**
     * Resolves a bulk op's relationship source/target display names, or {@code null} when the op is
     * not a relationship. Prefers the endpoint names captured from the prepared {@code RelationshipDto}
     * during the prepare phase ({@code endpointNames}, keyed by op index) — a being-created
     * relationship's own {@code getSource()/getTarget()} are null until the command executes,
     * so they cannot be read off the raw object here. Falls back to a model lookup by entity id for
     * relationship ops whose endpoints were not captured (e.g. update/delete-relationship in bulk).
     * Each returned name degrades to the endpoint id when the element is nameless.
     */
    private String[] resolveBulkOpEndpoints(IArchimateModel model,
            Map<Integer, String[]> endpointNames, BulkOperationResult opResult) {
        String[] captured = endpointNames.get(opResult.index());
        if (captured != null) {
            return new String[] {
                    (captured[0] != null && !captured[0].isBlank()) ? captured[0] : null,
                    (captured[1] != null && !captured[1].isBlank()) ? captured[1] : null
            };
        }
        boolean looksRelational = (opResult.tool() != null && opResult.tool().contains("relationship"))
                || (opResult.entityType() != null && opResult.entityType().endsWith("Relationship"));
        if (looksRelational && opResult.entityId() != null) {
            return resolveRelationshipEndpointNames(model, opResult.entityId());
        }
        return null;
    }

    // ---- Command stack undo/redo ----

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
                    state.canUndo(), state.canRedo(), state.blockedReason());
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
                    state.canUndo(), state.canRedo(), state.blockedReason());
        } catch (MutationException e) {
            throw new ModelAccessException(e.getMessage(), ErrorCode.MUTATION_FAILED);
        }
    }

    // ---- Mutation support ----

    @Override
    public MutationDispatcher getMutationDispatcher() {
        return mutationDispatcher;
    }

    // ---- Volatile capture + null check ----

    /**
     * Captures the volatile model reference and throws if null.
     * Prevents volatile re-read NPE (retrospective action item).
     */
    private IArchimateModel requireAndCaptureModel() {
        IArchimateModel model = this.activeModel;
        if (model == null) {
            throw new NoModelLoadedException();
        }
        return model;
    }

    /**
     * Applies the semantic attributes to the EMF relationship. Called from
     * {@code prepareCreateRelationship} after validation passes.
     */
    private static void applySemanticAttributesToRelationship(
            IArchimateRelationship relationship, RelationshipSemanticAttributes attrs) {
        if (attrs == null || !attrs.hasAny()) {
            return;
        }
        if (attrs.accessType() != null && relationship instanceof IAccessRelationship ar) {
            ar.setAccessType(RelationshipSemantics.resolveAccessTypeInt(attrs.accessType()));
        }
        if (attrs.associationDirected() != null && relationship instanceof IAssociationRelationship asr) {
            asr.setDirected(attrs.associationDirected());
        }
        if (attrs.influenceStrength() != null && relationship instanceof IInfluenceRelationship ir) {
            ir.setStrength(attrs.influenceStrength());
        }
    }

    /**
     * Reads the semantic-attribute params from a bulk-mutate operation's
     * params map and returns a {@link RelationshipSemanticAttributes} bundle.
     * Instance-scoped because it calls the instance-scoped {@code optionalParam}.
     */
    private RelationshipSemanticAttributes readSemanticAttributesFromParams(
            Map<String, Object> params) {
        String accessType = optionalParam(params, "accessType");
        Boolean associationDirected = null;
        if (params.containsKey("associationDirected")) {
            Object raw = params.get("associationDirected");
            if (raw instanceof Boolean b) {
                associationDirected = b;
            } else if (raw instanceof String s && !s.isBlank()) {
                associationDirected = Boolean.parseBoolean(s);
            }
        }
        // influenceStrength may legitimately be an empty string (clear semantic) — use containsKey
        String influenceStrength = null;
        if (params.containsKey("influenceStrength")) {
            Object raw = params.get("influenceStrength");
            if (raw instanceof String s) {
                influenceStrength = s;
            } else if (raw != null) {
                influenceStrength = String.valueOf(raw);
            }
        }
        if (accessType == null && associationDirected == null && influenceStrength == null) {
            return RelationshipSemanticAttributes.NONE;
        }
        return new RelationshipSemanticAttributes(accessType, associationDirected, influenceStrength);
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
                    views.add(DtoMapper.buildViewDto(diagram, currentPath));
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
                                     List<DiagramImageDto> images,
                                     Set<String> seenElementIds,
                                     Set<String> seenRelationshipIds,
                                     String parentViewObjectId) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject archimateObject) {
                IArchimateElement element = archimateObject.getArchimateElement();
                if (element != null) {
                    // Dedup elements (same element can appear multiple times in a view)
                    if (seenElementIds.add(element.getId())) {
                        elements.add(DtoMapper.convertToElementDto(element));
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
                                StylingHelper.readLineWidth(archimateObject),
                                ImageHelper.readImagePath(archimateObject),
                                ImageHelper.readImagePosition(archimateObject),
                                ImageHelper.readShowIcon(archimateObject),
                                StylingHelper.readFigureType(archimateObject),
                                StylingHelper.readTextAlignment(archimateObject),
                                StylingHelper.readVerticalTextAlignment(archimateObject),
                                StylingHelper.readLabelExpression(archimateObject),
                                StylingHelper.readFontName(archimateObject),
                                StylingHelper.readFontSize(archimateObject),
                                StylingHelper.readFontStyle(archimateObject),
                                StylingHelper.readGradient(archimateObject),
                                StylingHelper.readDeriveLineColor(archimateObject),
                                StylingHelper.readOutlineOpacity(archimateObject),
                                StylingHelper.readLineStyle(archimateObject)));
                    }
                }

                // Collect connections from this object
                collectConnections(archimateObject, relationships, connections,
                        seenRelationshipIds);
            }

            // Collect groups
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
                        StylingHelper.readLineWidth(groupObj),
                        ImageHelper.readImagePath(groupObj),
                        ImageHelper.readImagePosition(groupObj),
                        ImageHelper.readShowIcon(groupObj),
                        StylingHelper.readFigureType(groupObj),
                        StylingHelper.readTextAlignment(groupObj),
                        StylingHelper.readVerticalTextAlignment(groupObj),
                        StylingHelper.readLabelExpression(groupObj),
                        StylingHelper.readFontName(groupObj),
                        StylingHelper.readFontSize(groupObj),
                        StylingHelper.readFontStyle(groupObj),
                        StylingHelper.readGradient(groupObj),
                        StylingHelper.readDeriveLineColor(groupObj),
                        StylingHelper.readOutlineOpacity(groupObj),
                        StylingHelper.readLineStyle(groupObj)));

                // Recurse into group's children
                collectViewContents(groupObj, elements, relationships, visualMetadata,
                        connections, groups, notes, images, seenElementIds, seenRelationshipIds,
                        groupObj.getId());
                continue; // Skip the generic container recursion below
            }

            // Collect notes
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
                        StylingHelper.readLineWidth(noteObj),
                        null, // note field
                        ImageHelper.readImagePath(noteObj),
                        ImageHelper.readImagePosition(noteObj),
                        ImageHelper.readShowIcon(noteObj),
                        StylingHelper.readTextAlignment(noteObj),
                        StylingHelper.readVerticalTextAlignment(noteObj),
                        StylingHelper.readLabelExpression(noteObj),
                        StylingHelper.readFontName(noteObj),
                        StylingHelper.readFontSize(noteObj),
                        StylingHelper.readFontStyle(noteObj),
                        StylingHelper.readGradient(noteObj),
                        StylingHelper.readBorderType(noteObj),
                        StylingHelper.readDeriveLineColor(noteObj),
                        StylingHelper.readOutlineOpacity(noteObj),
                        // A read reports what is on the view, never a placement-time disclosure:
                        // the routes a note was placed among may have moved since.
                        StylingHelper.readLineStyle(noteObj), null));
                continue; // Notes are not containers, no recursion needed
            }

            // Collect IDiagramModelImage visuals (leaf — no recursion).
            // Distinct from IIconic-based imagePath on element/group/note view-objects
            // (which are icon overlays); this is a first-class image node placed
            // directly on the view.
            if (child instanceof IDiagramModelImage imageObj) {
                IBounds bounds = imageObj.getBounds();
                String docs = imageObj.getDocumentation();
                if (docs != null && docs.isEmpty()) docs = null;
                images.add(new DiagramImageDto(
                        imageObj.getId(),
                        imageObj.getImagePath(),
                        bounds != null ? bounds.getX() : 0,
                        bounds != null ? bounds.getY() : 0,
                        bounds != null ? bounds.getWidth() : DEFAULT_IMAGE_VISUAL_WIDTH,
                        bounds != null ? bounds.getHeight() : DEFAULT_IMAGE_VISUAL_HEIGHT,
                        parentViewObjectId,
                        imageObj.getBorderColor(),
                        docs));
                continue; // Image visuals are leaves (no children to recurse into)
            }

            // Recurse into nested containers (element-as-container)
            // Pass child's view object ID as parent so nested children report correct parentViewObjectId
            if (child instanceof IDiagramModelContainer nestedContainer) {
                collectViewContents(nestedContainer, elements, relationships, visualMetadata,
                        connections, groups, notes, images,
                        seenElementIds, seenRelationshipIds,
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
                        relationships.add(DtoMapper.convertToRelationshipDto(rel, false));
                    }
                    // ViewConnectionDto: every visual connection collected (each has unique ID).
                    // Geometry from the shared builder, styling overlaid — this path used to
                    // re-implement both, which is how the two could describe the same connection
                    // differently.
                    connections.add(ConnectionResponseBuilder.withConnectionStyling(
                            ConnectionResponseBuilder.buildConnectionResponseDto(
                                    archimateConn.getId(), rel,
                                    archimateConn.getSource().getId(),
                                    archimateConn.getTarget().getId(),
                                    ConnectionResponseBuilder.collectBendpoints(archimateConn),
                                    ConnectionResponseBuilder.archimateEndpoint(archimateConn.getSource()),
                                    ConnectionResponseBuilder.archimateEndpoint(archimateConn.getTarget()),
                                    archimateConn.getTextPosition()),
                            archimateConn));
                }
            }
        });
    }

    /**
     * Determines whether the quality iteration loop has plateaued.
     * A plateau is reached when the rating and score are unchanged AND
     * average spacing has not improved by more than 1.0px.
     * Package-visible for testing.
     * Superseded by {@link #isFactorAwarePlateauReached} which considers
     * limiting-factor shifts and factor-count improvements.
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
     * Factor-aware plateau detection. Returns true (plateau) only when the
     * limiting factor is the same, its count has not improved, and the rating is unchanged.
     * A factor shift (e.g., crossings → pass-throughs) or factor count improvement
     * indicates structural progress and prevents premature stopping.
     * Package-visible for testing.
     */
    static boolean isFactorAwarePlateauReached(
            String limitingFactor, String previousLimitingFactor,
            int factorCount, int previousFactorCount,
            String rating, String previousRating) {
        // First iteration (no previous state) — never a plateau
        if (previousLimitingFactor == null) {
            return false;
        }
        // All metrics pass (null current factor) — not a plateau; target-met handles exit
        if (limitingFactor == null) {
            return false;
        }
        // Factor shifted — structural progress, not a plateau
        if (!limitingFactor.equals(previousLimitingFactor)) {
            return false;
        }
        // Factor count improved — progress on the same bottleneck
        if (factorCount < previousFactorCount) {
            return false;
        }
        // Same factor, same or worse count, same rating — plateau
        return rating.equals(previousRating);
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

    // ---- Image management ----

    @Override
    public AddImageResultDto addImageToModel(String sessionId, byte[] imageData, String filenameHint) {
        return imageOps.addImageToModel(sessionId, imageData, filenameHint);
    }

    @Override
    public AddImageResultDto addImageFromFilePath(String sessionId, String filePath) {
        return imageOps.addImageFromFilePath(sessionId, filePath);
    }

    // ---- Bounded URL image download (cap gates the allocation) -----------------------
    // The connect/request timeouts and the 1 MB cap already existed; the residual gap was
    // *allocation order* — BodyHandlers.ofByteArray() buffered the WHOLE body into a byte[]
    // BEFORE the size check, so a chunked / no-Content-Length body that lies about (or omits)
    // its size could stream gigabytes and OOM the JVM that is Archi (same blast-radius class
    // as the transport guardrail, closed at the Jetty layer). These bounds make the cap gate the
    // allocation: the body is streamed and aborted the instant it exceeds the cap. Bounds are
    // named constants, not preferences — matching the single-user-desktop decision.

    /**
     * Hard ceiling on a URL-downloaded image, in bytes (1 MiB). Matches the local-file and
     * base64 image paths. Enforced DURING streaming (see {@link #downloadBoundedBytes}) so an
     * oversized body is aborted before it is fully buffered — a missing / lying
     * {@code Content-Length} cannot bypass it.
     */
    static final long MAX_IMAGE_BYTES = 1_048_576L; // 1 MiB

    /** Connect timeout for the URL image fetch (time to establish the TCP/TLS connection). */
    static final Duration URL_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Time-to-first-response budget for the blocking {@code send()} (until the response headers
     * are available). NOTE: this does NOT bound the streaming body read on a server that trickles
     * bytes slowly — that is what {@link #URL_DOWNLOAD_DEADLINE} covers.
     */
    static final Duration URL_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Overall wall-clock budget for streaming the response body. Checked between reads so a
     * slow-drip (slowloris-style) body cannot pin a worker far past the intended timeout.
     * Exceeding it raises an {@link java.net.http.HttpTimeoutException}, mapped to the same
     * timeout-class error as the existing per-request timeout.
     *
     * <p>The three timeouts guard distinct phases, so the aggregate worst-case ceiling for a
     * URL fetch is {@code URL_CONNECT_TIMEOUT + URL_REQUEST_TIMEOUT + URL_DOWNLOAD_DEADLINE}
     * (~10 s + 30 s + 30 s).</p>
     */
    static final Duration URL_DOWNLOAD_DEADLINE = Duration.ofSeconds(30);

    /** Read-chunk size for the bounded streaming download. */
    private static final int DOWNLOAD_CHUNK_BYTES = 8192;

    /**
     * Streams an HTTP response body into a {@code byte[]}, enforcing a hard size cap DURING the
     * read (the body is aborted the moment cumulative bytes exceed {@code maxBytes}, before an
     * over-cap buffer is ever allocated) and an overall wall-clock {@code deadline} between reads.
     *
     * <p>Pure I/O + byte work — no {@code com.archimatetool.model.*}, {@code org.eclipse.emf.*},
     * or SWT references — so it is unit-testable headlessly against a loopback HttpServer
     * (the "pure core" discipline applied to a network path). {@code addImageFromUrl} keeps
     * its model capture + {@code storeImageFile} orchestration and delegates the fetch here.</p>
     *
     * <p>Allocation is bounded: a single buffer of exactly {@code maxBytes} is allocated once,
     * independent of what the remote sends; an oversized body is rejected before any larger
     * allocation. The deadline is checked between reads, so it bounds a body that trickles bytes
     * slowly; the per-request {@code timeout} on {@code request} remains the backstop for the
     * time-to-first-response.</p>
     *
     * @param client   the HttpClient to send with (caller owns its lifecycle)
     * @param request  the prepared GET request
     * @param maxBytes the hard size cap (see {@link #MAX_IMAGE_BYTES})
     * @param deadline the overall body-streaming budget (see {@link #URL_DOWNLOAD_DEADLINE})
     * @param url      the source URL, used only for error messages
     * @return the downloaded bytes (length in {@code 1..maxBytes})
     * @throws ModelAccessException for non-200 status, empty body, or oversize body (structured,
     *         matching the messages the buffer-then-check path produced)
     * @throws java.net.http.HttpTimeoutException if the body read exceeds {@code deadline}
     * @throws IOException          on transport failure
     * @throws InterruptedException if the send is interrupted
     */
    static byte[] downloadBoundedBytes(HttpClient client, HttpRequest request,
            long maxBytes, Duration deadline, String url)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            // Close the body without draining — signals abort to the client; the per-call
            // HttpClient discards the connection, so a non-200 never leaks a socket.
            response.body().close();
            throw new ModelAccessException(
                    "HTTP " + response.statusCode() + " downloading image from URL: " + url,
                    ErrorCode.INTERNAL_ERROR,
                    "HTTP status: " + response.statusCode(),
                    "Check the URL is correct and the server is accessible.",
                    null);
        }

        // Single fixed buffer sized to the cap — allocated once, never grows with the body.
        // maxBytes is a build-time constant well under 2 GiB; guard the narrowing cast so a
        // future misconfiguration fails loudly instead of via a negative array size.
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxBytes must be in 1.." + Integer.MAX_VALUE + ": " + maxBytes);
        }
        int cap = (int) maxBytes;
        byte[] data = new byte[cap];
        int total = 0;
        long deadlineNanos = System.nanoTime() + deadline.toNanos();

        try (InputStream in = response.body()) {
            while (total < cap) {
                if (System.nanoTime() > deadlineNanos) {
                    throw new java.net.http.HttpTimeoutException(
                            "Download exceeded deadline of " + deadline.toSeconds() + "s");
                }
                int n = in.read(data, total, Math.min(DOWNLOAD_CHUNK_BYTES, cap - total));
                if (n == -1) {
                    break;
                }
                total += n;
            }
            // Buffer is full to the cap: one more readable byte means the body is oversized.
            // (A body of exactly maxBytes is still accepted, matching the old `> 1_048_576` check.)
            // The probe read() is deadline-guarded too, so a server that sends exactly cap bytes
            // then stalls cannot pin the worker past the deadline on this one-byte probe.
            if (total == cap) {
                if (System.nanoTime() > deadlineNanos) {
                    throw new java.net.http.HttpTimeoutException(
                            "Download exceeded deadline of " + deadline.toSeconds() + "s");
                }
                if (in.read() != -1) {
                    throw new ModelAccessException(
                            "Downloaded image exceeds 1MB limit (more than " + maxBytes + " bytes)",
                            ErrorCode.INVALID_PARAMETER,
                            null,
                            "Provide a URL to a smaller image (max 1MB).",
                            null);
                }
            }
        }

        if (total == 0) {
            throw new ModelAccessException(
                    "Empty response body from URL: " + url,
                    ErrorCode.INTERNAL_ERROR,
                    null,
                    "The URL returned no data. Check the URL points to an image.",
                    null);
        }

        return (total == cap) ? data : Arrays.copyOf(data, total);
    }
    // ---- end bounded URL image download ----------------------------------------------------

    @Override
    public AddImageResultDto addImageFromUrl(String sessionId, String url) {
        logger.info("Adding image from URL: {}", url);
        IArchimateModel model = requireAndCaptureModel();

        if (url == null || url.isBlank()) {
            throw new ModelAccessException(
                    "url must not be empty",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide an HTTP or HTTPS URL to an image file.",
                    null);
        }

        // Validate URL scheme
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ModelAccessException(
                    "Only http:// and https:// URLs are supported. Got: " + url,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use filePath for local files. Use http:// or https:// URLs for remote images.",
                    null);
        }

        // Validate URL syntax early (clear error for malformed URLs)
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ModelAccessException(
                    "Malformed URL: " + url,
                    ErrorCode.INVALID_PARAMETER,
                    e.getMessage(),
                    "Ensure the URL is properly encoded and contains no invalid characters.",
                    null);
        }

        File tempFile = null;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(URL_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            // Download image — stream with a hard size cap enforced DURING the read,
            // so an oversized chunked / no-Content-Length body is aborted before it is fully
            // buffered, and a slow-drip body is bounded by the overall download deadline.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(URL_REQUEST_TIMEOUT)
                    .GET()
                    .build();
            byte[] data = downloadBoundedBytes(
                    client, request, MAX_IMAGE_BYTES, URL_DOWNLOAD_DEADLINE, url);

            // Detect extension from URL path (pure, locale-independent, unit-tested there)
            String ext = ImageOperations.extensionFromUrlPath(uri.getPath());

            tempFile = File.createTempFile("archi-mcp-image-", "." + ext);
            Files.write(tempFile.toPath(), data);
            return imageOps.storeImageFile(tempFile, model);
        } catch (ModelAccessException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ModelAccessException(
                    "Timeout downloading image from URL: " + url,
                    ErrorCode.INTERNAL_ERROR,
                    e.getMessage(),
                    "The server did not respond within the timeout. Try again or use filePath with a locally downloaded file.",
                    null);
        } catch (InterruptedException e) {
            // Restore the interrupt flag so cooperative shutdown (e.g. Jetty stopping its
            // workers) is not silently swallowed by the generic catch below.
            Thread.currentThread().interrupt();
            throw new ModelAccessException(
                    "Image download interrupted: " + url,
                    ErrorCode.INTERNAL_ERROR,
                    e.getMessage(),
                    "The download was interrupted. Try again.",
                    null);
        } catch (Exception e) {
            if (e instanceof org.eclipse.swt.SWTException) {
                throw new ModelAccessException(
                        "Downloaded content is not a valid image: " + e.getMessage(),
                        ErrorCode.INVALID_PARAMETER,
                        e.getMessage(),
                        "The URL does not point to a valid image. Supported formats: PNG, JPEG, GIF, BMP, ICO, TIFF. " + ImageOperations.SVG_HOST_SUPPORT_NOTE,
                        null);
            }
            throw ImageDownloadFailure.forCause(url, e);
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    @Override
    public List<ModelImageDto> listModelImages(String sessionId) {
        return imageOps.listModelImages(sessionId);
    }

    // ---- Image helper methods for DTO construction ----

    /**
     * Computes the drawn image coverage and its advisory warning for an archive image on an
     * element of the given size. Returns an empty report when the dimensions cannot be read.
     */
    private ImageHelper.CoverageReport computeImageCoverage(String imagePath, int elementWidth,
            int elementHeight, String imagePosition) {
        if (imagePath == null || imagePath.isEmpty()) return ImageHelper.CoverageReport.NONE;
        try {
            int[] dims = ImageHelper.readNaturalImageDimensions(requireAndCaptureModel(), imagePath);
            if (dims == null) return ImageHelper.CoverageReport.NONE;
            return ImageHelper.coverageReport(dims[0], dims[1],
                    elementWidth, elementHeight, imagePosition);
        } catch (Exception e) {
            logger.debug("Could not compute image coverage for path: {}", imagePath, e);
            return ImageHelper.CoverageReport.NONE;
        }
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
            String layer = DtoMapper.resolveLayer(element);
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
        mutationDispatcher.onModelInactive(); // release the staleness guard's stack listener
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
                    // No active model — drop the staleness guard's stack listener (no leak).
                    mutationDispatcher.onModelInactive();
                    logger.warn("No ArchiMate model loaded — server will return NO_MODEL_LOADED errors");
                    fireModelChanged(null, null);
                }
            }
        }
    }

    private void handleModelContentChanged() {
        if (mutationDispatcher.isSilentMeasurementActive()) {
            return; // net-zero measurement in flight — not a real model change
        }
        long newVersion = versionCounter.incrementAndGet();
        logger.debug("Model content changed — version incremented to {}", newVersion);
    }

    private void setActiveModel(IArchimateModel model) {
        this.activeModel = model;
        this.versionCounter.incrementAndGet();
        // (re-)register the staleness guard's single CommandStack listener on the now-active model.
        // Idempotent + tolerant of a model with no CommandStack adapter.
        this.mutationDispatcher.onModelActive(model);
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

