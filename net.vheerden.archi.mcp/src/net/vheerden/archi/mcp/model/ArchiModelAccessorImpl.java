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
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
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
import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.IApplicationElement;
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
import com.archimatetool.model.IBusinessElement;
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
import com.archimatetool.model.IIconic;
import com.archimatetool.model.IImplementationMigrationElement;
import com.archimatetool.model.IMotivationElement;
import com.archimatetool.model.ITextContent;
import com.archimatetool.model.IPhysicalElement;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.ISketchModel;
import com.archimatetool.model.IStrategyElement;
import com.archimatetool.model.ITechnologyElement;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.model.routing.BestOfKRoutingStrategy;
import net.vheerden.archi.mcp.model.routing.FailedConnection;
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
import net.vheerden.archi.mcp.response.dto.AnchorPointDto;
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
import net.vheerden.archi.mcp.response.dto.ExportViewResultDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;
import net.vheerden.archi.mcp.response.dto.LayoutFlatViewResultDto;
import net.vheerden.archi.mcp.response.dto.ResizeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.ModelImageDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;
import net.vheerden.archi.mcp.response.dto.NudgedElementDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
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
import net.vheerden.archi.mcp.response.dto.SetViewLabelExpressionResultDto;
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

    // View placement constants
    private static final int DEFAULT_VIEW_OBJECT_WIDTH = 120;
    private static final int DEFAULT_VIEW_OBJECT_HEIGHT = 55;
    private static final int DEFAULT_GROUP_WIDTH = 300;
    private static final int DEFAULT_GROUP_HEIGHT = 200;
    private static final int DEFAULT_NOTE_WIDTH = 185;
    private static final int DEFAULT_NOTE_HEIGHT = 80;
    // G8: view-reference default bounds (Task 0.8 / Q1 disposition).
    // Mirrors note default for parity; empirical Task 7.3.1 ratifies.
    private static final int DEFAULT_VIEW_REF_WIDTH = 185;
    private static final int DEFAULT_VIEW_REF_HEIGHT = 80;

    /**
     * Default bounds for an {@code add-image-to-view} image visual when the
     * caller omits width/height AND the archive natural-dimension read fails
     * (G16, Open Question 1 fallback).
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

    // ---- Concept where-used (G10) ----

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
                null);  // embeddingViewReferences reserved for G8
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

            // G6: surface model's own metadata for read-write parity
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
                if (layerFilter != null && !resolveLayer(element).equals(layerFilter)) {
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
                    if (sourceElement == null || !resolveLayer(sourceElement).equals(sourceLayerFilter)) {
                        continue;
                    }
                }
                // Apply target layer filter
                if (targetLayerFilter != null) {
                    IArchimateElement targetElement = (IArchimateElement) rel.getTarget();
                    if (targetElement == null || !resolveLayer(targetElement).equals(targetLayerFilter)) {
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
                    results.add(DtoMapper.convertToSearchRelationshipDto(rel,
                            accessTypeForDto(rel), associationDirectedForDto(rel),
                            influenceStrengthForDto(rel)));
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
                        layer = resolveLayer(tempElement);
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
                return resolveLayer(el);
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
     * <p>G16: includes {@code imagePath} when non-null
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
     * <p>G16: optional {@code imagePath} sets the specialization's
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
        // match AXIS A's prepareAddImageToView discipline (cross-LLM-review
        // FA2 — consistent empty-check semantic across axes).
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

        // Q4 strict imagePath validation.
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
        Map<String, Object> dto = buildProfileMap(name, canonicalConceptType, layer, true, imagePath);
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
     * G16 imagePath set / clear).
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
        // (cross-LLM-review FA2 — consistent empty-check semantic).
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

        // Q4 strict imagePath validation (only when setting).
        if (imagePath != null) {
            validateImagePathExists(model, imagePath);
        }

        // Re-key the bulk cache so subsequent ops can find this profile under its
        // new name. The IProfile object's actual name remains the old name until
        // UpdateProfileCommand executes during phase 2, but the cache lookup keys
        // off the *intended* state of the batch.
        if (willChangeName) {
            Map<String, IProfile> cache = bulkProfileCache.get();
            if (cache != null) {
                cache.remove(profileCacheKey(name, canonicalConceptType));
                cache.put(profileCacheKey(newName, canonicalConceptType), profile);
            }
        }

        UpdateProfileCommand.ImagePathChange imagePathChange;
        if (imagePath != null) {
            imagePathChange = UpdateProfileCommand.ImagePathChange.setTo(imagePath);
        } else if (clearImagePath) {
            imagePathChange = UpdateProfileCommand.ImagePathChange.clear();
        } else {
            imagePathChange = UpdateProfileCommand.ImagePathChange.unchanged();
        }

        // Determine the effective name + imagePath that the response DTO should reflect.
        String effectiveName = willChangeName ? newName : profile.getName();
        String effectiveImagePath;
        if (imagePath != null) {
            effectiveImagePath = imagePath;
        } else if (clearImagePath) {
            effectiveImagePath = null;
        } else {
            effectiveImagePath = profile.getImagePath();
        }

        Command cmd = new UpdateProfileCommand(profile,
                willChangeName ? InputValidation.reject(newName, "name") : null, imagePathChange);
        Map<String, Object> dto = buildProfileMap(effectiveName, canonicalConceptType,
                layer, null, effectiveImagePath);
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
            cmd = new DeleteProfileCommand(profile, model);
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
                compound.add(new ClearSpecializationCommand(concept));
            }
            compound.add(new DeleteProfileCommand(profile, model));
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
                results.add(convertToRelationshipDto(rel));
            }
            for (IArchimateRelationship rel : element.getTargetRelationships()) {
                // skip orphaned relationships (not in containment tree)
                if (rel.eContainer() == null) continue;
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
            Map<String, String> mergedProperties = mergeSourceProperties(properties, source);

            PreparedMutation<ElementDto> prepared = prepareCreateElement(type, name,
                    documentation, mergedProperties, folderId, specialization);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Create " + type + ": " + name;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("type", type);
                proposedChanges.put("name", name);
                if (documentation != null) proposedChanges.put("documentation", documentation);
                if (folderId != null) proposedChanges.put("folderId", folderId);
                if (source != null) proposedChanges.put("source", source);
                if (specialization != null) proposedChanges.put("specialization", specialization);
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
            RelationshipSemanticAttributes semanticAttributes) {
        logger.info("Creating relationship: type={}, source={}, target={}", type, sourceId, targetId);
        requireAndCaptureModel();
        RelationshipSemanticAttributes attrs = (semanticAttributes != null)
                ? semanticAttributes : RelationshipSemanticAttributes.NONE;
        try {
            PreparedMutation<RelationshipDto> prepared = prepareCreateRelationship(
                    type, sourceId, targetId, name, specialization, attrs);

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
                if (name != null) proposedChanges.put("name", name);
                if (specialization != null) proposedChanges.put("specialization", specialization);
                if (attrs.accessType() != null) proposedChanges.put("accessType", attrs.accessType());
                if (attrs.associationDirected() != null) proposedChanges.put("associationDirected", attrs.associationDirected());
                if (attrs.influenceStrength() != null) proposedChanges.put("influenceStrength", attrs.influenceStrength());
                ProposalContext ctx = storeAsProposal(sessionId, "create-relationship",
                        () -> prepareCreateRelationship(type, sourceId, targetId, name, specialization, attrs),
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
                if (viewpoint != null) proposedChanges.put("viewpoint", viewpoint);
                if (folderId != null) proposedChanges.put("folderId", folderId);
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
                if (name != null) proposedChanges.put("name", name);
                if (documentation != null) proposedChanges.put("documentation", documentation);
                if (properties != null) proposedChanges.put("properties", properties);
                if (specialization != null) proposedChanges.put("specialization", specialization);
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
                if (name != null) proposedChanges.put("name", name);
                if (documentation != null) proposedChanges.put("documentation", documentation);
                if (properties != null) proposedChanges.put("properties", properties);
                if (specialization != null) proposedChanges.put("specialization", specialization);
                if (attrs.accessType() != null) proposedChanges.put("accessType", attrs.accessType());
                if (attrs.associationDirected() != null) proposedChanges.put("associationDirected", attrs.associationDirected());
                if (attrs.influenceStrength() != null) proposedChanges.put("influenceStrength", attrs.influenceStrength());
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
                entity = convertToRelationshipDto(rel);
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
                if (documentation != null) proposedChanges.put("documentation", documentation);
                if (properties != null) proposedChanges.put("properties", properties);
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

    // ---- Model metadata mutation (G6) ----

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
                    viewId, elementId, x, y, width, height, autoConnect, parentViewObjectId,
                    null, styling, imageParams);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String elementName = prepared.entity().viewObject().elementName();
                String elementType = prepared.entity().viewObject().elementType();
                String description = "Add " + elementType + " '" + elementName + "' to view"
                        + viewNameClause(resolveViewName(model, viewId));
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
                        () -> prepareAddToView(viewId, elementId, x, y, width, height, autoConnect,
                                parentViewObjectId, null, styling, imageParams),
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
                    viewId, label, x, y, width, height, parentViewObjectId,
                    null, styling, imageParams);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Add group '" + label + "' to view"
                        + viewNameClause(resolveViewName(model, viewId));
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("label", label);
                if (x != null) proposedChanges.put("x", x);
                if (y != null) proposedChanges.put("y", y);
                if (width != null) proposedChanges.put("width", width);
                if (height != null) proposedChanges.put("height", height);
                if (parentViewObjectId != null) proposedChanges.put("parentViewObjectId", parentViewObjectId);
                ProposalContext ctx = storeAsProposal(sessionId, "add-group-to-view",
                        () -> prepareAddGroupToView(viewId, label, x, y, width, height,
                                parentViewObjectId, null, styling, imageParams),
                        targetIds(viewId), prepared.entity(), description,
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
            String content, String position, Integer gap, Integer x, Integer y,
            Integer width, Integer height,
            String parentViewObjectId, StylingParams styling, ImageParams imageParams) {
        logger.info("Adding note to view: viewId={}", viewId);
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<ViewNoteDto> prepared = prepareAddNoteToView(
                    viewId, content, position, gap, x, y, width, height,
                    parentViewObjectId, null, styling, imageParams);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String truncatedContent = content.length() > 40
                        ? content.substring(0, 40) + "..." : content;
                String description = "Add note to view"
                        + viewNameClause(resolveViewName(model, viewId)) + ": " + truncatedContent;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("content", content);
                if (position != null) proposedChanges.put("position", position);
                if (gap != null) proposedChanges.put("gap", gap);
                if (x != null) proposedChanges.put("x", x);
                if (y != null) proposedChanges.put("y", y);
                if (width != null) proposedChanges.put("width", width);
                if (height != null) proposedChanges.put("height", height);
                if (parentViewObjectId != null) proposedChanges.put("parentViewObjectId", parentViewObjectId);
                ProposalContext ctx = storeAsProposal(sessionId, "add-note-to-view",
                        () -> prepareAddNoteToView(viewId, content, position, gap, x, y, width, height,
                                parentViewObjectId, null, styling, imageParams),
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
                    viewId, referencedViewId, x, y, width, height,
                    parentViewObjectId, null, null, styling);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Add view-reference to view"
                        + viewNameClause(resolveViewName(model, viewId)) + ": " + referencedViewId;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("referencedViewId", referencedViewId);
                if (x != null) proposedChanges.put("x", x);
                if (y != null) proposedChanges.put("y", y);
                if (width != null) proposedChanges.put("width", width);
                if (height != null) proposedChanges.put("height", height);
                if (parentViewObjectId != null) {
                    proposedChanges.put("parentViewObjectId", parentViewObjectId);
                }
                ProposalContext ctx = storeAsProposal(sessionId, "add-view-reference-to-view",
                        () -> prepareAddViewReferenceToView(viewId, referencedViewId, x, y, width, height,
                                parentViewObjectId, null, null, styling),
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
                    viewId, imagePath, x, y, width, height,
                    parentViewObjectId, null, null, styling,
                    borderColor, documentation);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String description = "Add image visual to view"
                        + viewNameClause(resolveViewName(model, viewId)) + ": " + imagePath;
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                proposedChanges.put("imagePath", imagePath);
                if (x != null) proposedChanges.put("x", x);
                if (y != null) proposedChanges.put("y", y);
                if (width != null) proposedChanges.put("width", width);
                if (height != null) proposedChanges.put("height", height);
                if (parentViewObjectId != null) {
                    proposedChanges.put("parentViewObjectId", parentViewObjectId);
                }
                ProposalContext ctx = storeAsProposal(sessionId, "add-image-to-view",
                        () -> prepareAddImageToView(viewId, imagePath, x, y, width, height,
                                parentViewObjectId, null, null, styling, borderColor, documentation),
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
                    viewId, relationshipId, sourceViewObjectId, targetViewObjectId,
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
                ProposalContext ctx = storeAsProposal(sessionId, "add-connection-to-view",
                        () -> prepareAddConnectionToView(viewId, relationshipId, sourceViewObjectId,
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
                    anchorTarget, anchorEdge, anchorDx, anchorDy);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String elementName = prepared.entity().elementName();
                String elementType = prepared.entity().elementType();
                // name the view the object lives in (resolved from its owning diagram, since
                // this tool takes only viewObjectId). Unresolvable view degrades to no clause.
                // viewPhrase returns null on degradation (base has no dangling "view" token to fill),
                // so the ternary is required here — not viewNameClause, which would add a trailing space.
                String viewClause = viewPhrase(resolveViewName(model, viewObjectId), "in");
                String description = "Update view object bounds for " + elementType
                        + " '" + elementName + "'" + (viewClause != null ? " " + viewClause : "");
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                if (x != null) proposedChanges.put("x", x);
                if (y != null) proposedChanges.put("y", y);
                if (width != null) proposedChanges.put("width", width);
                if (height != null) proposedChanges.put("height", height);
                ProposalContext ctx = storeAsProposal(sessionId, "update-view-object",
                        () -> prepareUpdateViewObject(viewObjectId, x, y, width, height, text,
                                styling, imageParams, labelExpression,
                                anchorTarget, anchorEdge, anchorDx, anchorDy),
                        targetIds(viewObjectId), prepared.entity(), description,
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
            List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling,
            Boolean showLabel, Integer textPosition) {
        logger.info("Updating view connection: viewConnectionId={}", viewConnectionId);
        IArchimateModel model = requireAndCaptureModel();
        try {
            PreparedMutation<ViewConnectionDto> prepared = prepareUpdateViewConnection(
                    viewConnectionId, bendpoints, absoluteBendpoints, styling, showLabel, textPosition);

            // Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                String relType = prepared.entity().relationshipType();
                // name the view the connection lives in (resolved from its owning diagram, since
                // this tool takes only viewConnectionId). Unresolvable view degrades to no clause.
                // viewPhrase returns null on degradation (base has no dangling "view" token to fill),
                // so the ternary is required here — not viewNameClause, which would add a trailing space.
                String viewName = resolveViewName(model, viewConnectionId);
                String viewClause = viewPhrase(viewName, "in");
                String description = "Update bendpoints for connection (" + relType + ")"
                        + (viewClause != null ? " " + viewClause : "");
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                int bpCount = (bendpoints != null) ? bendpoints.size()
                        : (absoluteBendpoints != null) ? absoluteBendpoints.size() : 0;
                proposedChanges.put("bendpointCount", bpCount);
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
                ProposalContext ctx = storeAsProposal(sessionId, "update-view-connection",
                        () -> prepareUpdateViewConnection(viewConnectionId, bendpoints,
                                absoluteBendpoints, styling, showLabel, textPosition),
                        targetIds(viewConnectionId), prepared.entity(), description,
                        null, proposedChanges, "Connection bendpoints ready for update.",
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
                                        null, null, null, null, null, null, null, null); // no text/styling/image/labelExpression/anchor for layout
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
                                        bps, absBps, null, null, null);
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

        // 1. Collect all view objects (including nested, with parentId)
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);

        // 2. Detect orphaned connections — connections whose
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
                    0, null, false, 0, 0, null,
                    0, null, 0, null, 0, null, null,
                    List.of("View has no elements — layout assessment is not applicable."),
                    // Assessor.Redesign M2-M6 (appended; backwards-compat)
                    0, null, 0, null, 0, null, 1.0, null,
                    "not-applicable", "not-applicable",
                    // R8 (appended)
                    1.0, null);
        }
        if (nodes.size() == 1) {
            return new AssessLayoutResultDto(
                    viewId, 1, 0, 0, 0, 0, 0.0, 0.0, 0,
                    "not-applicable", Map.of("overall", "not-applicable"),
                    null, null, null, null, 0, null,
                    orphanResult.count(), emptyToNull(orphanResult.descriptions()),
                    0, null, false, 0, 0, null,
                    0, null, 0, null, 0, null, null,
                    List.of("View has only one element — layout assessment is not applicable."),
                    // Assessor.Redesign M2-M6 (appended; backwards-compat)
                    0, null, 0, null, 0, null, 1.0, null,
                    "not-applicable", "not-applicable",
                    // R8 (appended)
                    1.0, null);
        }

        // 3. Collect connections with reconstructed visual paths
        List<AssessmentConnection> connections =
                AssessmentCollector.collectAssessmentConnections(diagramModel, nodes);

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
                result.imageSiblingOverlapCount(),
                emptyToNull(result.imageSiblingOverlapDescriptions()),
                mapViolatorIds(result.violatorIds()),
                result.suggestions(),
                // Assessor.Redesign M2-M6 (appended; backwards-compat)
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
                // Successor D parallelConnectionGap (appended)
                result.vAxisParallelGapP10() != null
                        ? Math.round(result.vAxisParallelGapP10() * 100.0) / 100.0 : null,
                result.vAxisParallelGapNarrow25Count(),
                mapParallelGapDetail(result.parallelConnectionGapDetail()), Math.round(result.hubNeighbourClearanceMin() * 100.0) / 100.0,
                // Coverage declaration (registry-driven); then connection-through-note/image + redundant-bendpoint + non-orthogonal-interior-segment + container-fill==child (informational)
                result.coverage(), result.connectionThroughNoteCount(), emptyToNull(result.connectionThroughNoteDescriptions()), result.connectionRedundantBendpointCount(), emptyToNull(result.connectionRedundantBendpointDescriptions()), result.nonOrthogonalInteriorSegmentCount(), emptyToNull(result.nonOrthogonalInteriorSegmentDescriptions()), result.containerFillEqualsChildCount(), emptyToNull(result.containerFillEqualsChildDescriptions()), result.connectionGrazesVisualCount(), emptyToNull(result.connectionGrazesVisualDescriptions()), result.labelOnNoteCount(), emptyToNull(result.labelOnNoteDescriptions()), result.labelOnGroupCount(), emptyToNull(result.labelOnGroupDescriptions()), result.edgeCoincidenceGrazedElementCount(), result.offFaceParallelTerminalCount(), emptyToNull(result.offFaceParallelTerminalDescriptions()), result.coincidentFacePortCount(), emptyToNull(result.coincidentFacePortDescriptions()));
    }

    /** Maps internal ParallelConnectionGapDetail to DTO format (Successor D). */
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
     * Maps internal HubFaceDetail records to DTO format (Assessor.Redesign M5).
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
            for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
                // Bendpoints are stored as relative offsets from source/target centers;
                // absolute position is the average of source-relative and target-relative.
                double absX = (bp.getStartX() + srcCenter[0] + bp.getEndX() + tgtCenter[0]) / 2.0;
                double absY = (bp.getStartY() + srcCenter[1] + bp.getEndY() + tgtCenter[1]) / 2.0;
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
        collectElementViewObjects(diagramModel, targets);

        // Filter to specific IDs if provided
        if (elementIds != null && !elementIds.isEmpty()) {
            java.util.Set<String> idSet = new java.util.HashSet<>(elementIds);
            targets.removeIf(obj -> !idSet.contains(obj.getId()));
        }

        // Build parent-child map for nested containment
        Map<String, List<IDiagramModelArchimateObject>> childrenByParentId = new LinkedHashMap<>();
        java.util.Set<String> hasChildren = new java.util.HashSet<>();
        for (IDiagramModelArchimateObject t : targets) {
            if (t.eContainer() instanceof IDiagramModelArchimateObject parent) {
                childrenByParentId
                        .computeIfAbsent(parent.getId(), k -> new ArrayList<>())
                        .add(t);
                hasChildren.add(parent.getId());
            }
        }

        // Two-pass: size leaf elements first, then parents (bottom-up by depth)
        List<IDiagramModelArchimateObject> leafElements = new ArrayList<>();
        List<IDiagramModelArchimateObject> parentElements = new ArrayList<>();
        for (IDiagramModelArchimateObject t : targets) {
            if (hasChildren.contains(t.getId())) {
                parentElements.add(t);
            } else {
                leafElements.add(t);
            }
        }

        // Sort parents bottom-up by nesting depth so children-parents are sized
        // before grandparents (supports recursive nesting)
        parentElements.sort((a, b) -> Integer.compare(nestingDepth(b), nestingDepth(a)));

        // Track computed sizes so Pass 2 uses resized dimensions, not stale bounds
        Map<String, int[]> computedSizes = new LinkedHashMap<>();

        List<PreparedMutation<ViewObjectDto>> mutations = new ArrayList<>();
        List<ResizeElementsResultDto.ResizedElement> resizedList = new ArrayList<>();
        int unchangedCount = 0;

        // Pass 1: Size leaf elements using ElementSizer
        for (IDiagramModelArchimateObject leaf : leafElements) {
            String elName = leaf.getArchimateConcept() != null
                    ? leaf.getArchimateConcept().getName() : "";
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
                        leaf, null, null, computed[0], computed[1]);
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

            // Step 1: Compute width (needed for label height word-wrap calculation)
            int newWidth = Math.max(labelWidth, childMaxRight) + 2 * CONTAINMENT_PADDING_SIDE;
            newWidth = Math.max(newWidth, ElementSizer.DEFAULT_WIDTH);

            // Step 2: Compute dynamic label height based on actual text wrapping
            int dynamicLabelTop = ElementSizer.computeLabelHeight(parentName, newWidth);

            // Step 3: Shift children down if topmost child overlaps label area
            if (children != null && !children.isEmpty() && minChildY < dynamicLabelTop) {
                int shiftDelta = dynamicLabelTop - minChildY;
                for (IDiagramModelArchimateObject child : children) {
                    IBounds cb = child.getBounds();
                    int newY = cb.getY() + shiftDelta;
                    PreparedMutation<ViewObjectDto> pm = prepareUpdateViewObjectDirect(
                            child, null, newY, null, null);
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
                        parent, null, null, newWidth, newHeight);
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
            wrapFitAncestorList.sort((a, b) -> Integer.compare(nestingDepth(b), nestingDepth(a)));
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
                            parent, null, null, null, newHeight);
                    mutations.add(pm);
                    resizedList.add(new ResizeElementsResultDto.ResizedElement(
                            parent.getId(), parentName, oldW, oldH, oldW, newHeight));
                } else {
                    unchangedCount++;
                }
            }
        }

        if (mutations.isEmpty()) {
            return new MutationResult<>(
                    new ResizeElementsResultDto(viewId, 0, unchangedCount, List.of()),
                    (Integer) null);
        }

        // Build compound command (single undo unit)
        String label = "Resize " + mutations.size() + " elements to fit labels";
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(label);
        for (PreparedMutation<ViewObjectDto> pm : mutations) {
            compound.add(pm.command());
        }

        ResizeElementsResultDto dto = new ResizeElementsResultDto(
                viewId, resizedList.size(), unchangedCount, resizedList);

        // Check approval mode
        if (mutationDispatcher.isApprovalRequired(sessionId)) {
            Map<String, Object> proposedChanges = new LinkedHashMap<>();
            proposedChanges.put("resizedCount", resizedList.size());
            proposedChanges.put("unchangedCount", unchangedCount);
            ProposalContext ctx = storeAsProposal(sessionId,
                    "resize-elements-to-fit",
                    () -> new PreparedMutation<>(compound, dto, viewId),
                    compoundTargetIds(compound, viewId), dto, label,
                    null, proposedChanges,
                    "Element resize ready for application.");
            return new MutationResult<>(dto, null, ctx);
        }

        Integer batchSeq = dispatchOrQueue(sessionId, compound, label);
        if (batchSeq == null) {
            versionCounter.incrementAndGet();
        }
        return new MutationResult<>(dto, batchSeq);
    }

    private void collectElementViewObjects(IDiagramModelContainer container,
            List<IDiagramModelArchimateObject> result) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject archObj) {
                result.add(archObj);
                // Recurse into nested elements
                collectElementViewObjects(archObj, result);
            } else if (child instanceof IDiagramModelGroup group) {
                // Recurse into groups to find elements inside them
                collectElementViewObjects(group, result);
            }
        }
    }

    /**
     * Computes nesting depth of a view object (0 = direct child of view).
     */
    private int nestingDepth(IDiagramModelObject obj) {
        int depth = 0;
        EObject container = obj.eContainer();
        while (container instanceof IDiagramModelObject) {
            depth++;
            container = container.eContainer();
        }
        return depth;
    }

    /**
     * Returns true iff {@code possibleAncestor} appears in the view-containment
     * chain of {@code possibleDescendant} above {@code possibleDescendant} itself.
     *
     * <p>Walks {@code eContainer()} from {@code possibleDescendant} upward until
     * the chain reaches a non-{@link IDiagramModelObject} (the view root or null).
     * Returns false if the two arguments are the same instance or if either is null.
     *
     * <p>Used by {@link #autoConnectView} to skip connections
     * between an ancestor and its descendant on the view — such a connection
     * is, by construction, a self-pass-through.
     */
    private static boolean isAncestorOnView(IDiagramModelObject possibleAncestor,
                                            IDiagramModelObject possibleDescendant) {
        if (possibleAncestor == null || possibleDescendant == null) {
            return false;
        }
        if (possibleAncestor == possibleDescendant) {
            return false;
        }
        EObject current = possibleDescendant.eContainer();
        while (current instanceof IDiagramModelObject) {
            if (current == possibleAncestor) {
                return true;
            }
            current = current.eContainer();
        }
        return false;
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
        int totalConnections = collectHubData(diagramModel, connectionCounts, viewObjectMap, maxLabelWidths);

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

    /**
     * Collects hub detection data by traversing view objects and counting connections.
     * Also tracks the maximum connection label width per view object for label-aware sizing.
     *
     * @return total connection count on the view
     */
    private int collectHubData(IDiagramModelContainer container,
                               Map<String, Integer> connectionCounts,
                               Map<String, IDiagramModelArchimateObject> viewObjectMap,
                               Map<String, Integer> maxLabelWidths) {
        int totalConnections = 0;
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject archimateObject) {
                IArchimateElement element = archimateObject.getArchimateElement();
                if (element != null) {
                    viewObjectMap.put(archimateObject.getId(), archimateObject);

                    // Count connections where this object is source
                    for (IDiagramModelConnection conn : archimateObject.getSourceConnections()) {
                        if (conn instanceof IDiagramModelArchimateConnection archConn) {
                            String sourceId = conn.getSource().getId();
                            String targetId = conn.getTarget().getId();
                            connectionCounts.merge(sourceId, 1, Integer::sum);
                            connectionCounts.merge(targetId, 1, Integer::sum);
                            totalConnections++;

                            // Track max label width per element (both ends)
                            String relName = archConn.getArchimateRelationship() != null
                                    ? archConn.getArchimateRelationship().getName() : null;
                            if (relName != null && !relName.isEmpty()) {
                                int labelWidth = (int) Math.ceil(relName.length() * 8.0 + 10.0);
                                maxLabelWidths.merge(sourceId, labelWidth, Math::max);
                                maxLabelWidths.merge(targetId, labelWidth, Math::max);
                            }
                        }
                    }
                }
            }

            // Recurse into containers (groups and nested elements)
            if (child instanceof IDiagramModelContainer nestedContainer) {
                totalConnections += collectHubData(nestedContainer, connectionCounts,
                        viewObjectMap, maxLabelWidths);
            }
        }
        return totalConnections;
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
                RoutingPipeline.DEFAULT_ENABLE_CHANNEL_NUDGING);
    }

    @Override
    public MutationResult<AutoRouteResultDto> autoRouteConnections(
            String sessionId, String viewId,
            List<String> connectionIds, String strategy, boolean force,
            boolean autoNudge, int snapThreshold, int perimeterMargin, String mode,
            boolean enableChannelNudging) {
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
            // RoutingPreconditions.AutoRouteStructuredWarning (Row E):
            // structured-warning emissions accumulate alongside the legacy
            // free-text `warnings` list; both surfaces ship in parallel for back-compat.
            List<StructuredWarningDto> structuredWarnings = new ArrayList<>();
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

            // 5b. terminals-only mode: dispatch to a focused branch that only
            // adjusts the first/last bendpoint of each connection. No A* router, no
            // obstacle building, no autoNudge, no label optimizer, no router-type
            // switch (terminals-only never adds new bendpoints to a manhattan-routed
            // view; if the view is in manhattan mode, the existing bendpoints are
            // ignored anyway and the operation is a no-op).
            if (terminalsOnly) {
                return runTerminalsOnly(sessionId, viewId, diagramModel,
                        targetConnections, effectiveStrategy, force, warnings);
            }

            // 6. Build commands
            List<Command> commands = new ArrayList<>();
            int routedCount = 0;
            int labelsOptimized = 0;
            int crossingsBefore = 0;
            int crossingsAfter = 0;
            int straightLineCrossings = 0;
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
                                prepareUpdateViewConnectionDirect(archConn, List.of(), null, null, null, null);
                        commands.add(prepared.command());
                        routedCount++;
                    }
                }
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
                        beforePathMap.put(ac.id(), ac.pathPoints());
                    }
                }
                crossingsBefore = LayoutQualityAssessor.countPathCrossings(beforePaths);

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
                // Catch degenerate geometry (zero-gap, touching) that can
                // crash the routing pipeline. Return partial result instead of INTERNAL_ERROR.
                OrthogonalRoutingResult routeResult;
                try {
                    routeResult = buildOrthogonalRoutingCommands(
                            diagramModel, targetConnections, nodes, force, snapThreshold, perimeterMargin,
                            VisibilityGraphRouter.DEFAULT_OCCUPANCY_WEIGHT, enableChannelNudging, true);
                } catch (RuntimeException e) {
                    logger.warn("Routing pipeline failed for view {} due to degenerate geometry: {}",
                            viewId, e.getMessage());
                    logger.debug("Routing pipeline failure stack trace", e);
                    warnings.add("Routing failed due to degenerate element geometry (overlapping or "
                            + "zero-gap elements). Use layout-flat-view or layout-within-group to "
                            + "separate elements first, then re-route.");
                    AutoRouteResultDto dto = new AutoRouteResultDto(
                            viewId, 0, effectiveStrategy, false, warnings);
                    return new MutationResult<>(dto, null);
                }
                commands.addAll(routeResult.commands);
                routedCount = routeResult.routedCount;
                labelsOptimized = routeResult.labelsOptimized;
                failedConnections = routeResult.failedConnections;
                moveRecommendations = routeResult.moveRecommendations;
                straightLineCrossings = routeResult.straightLineCrossings;
                AutoRouteWarnings.emitEgressLiftLayoutBound(routeResult.egressRolledBack, warnings, structuredWarnings);

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

                // Backlog-b15 + Successor E: Hoist shared maps OUT of the autoNudge
                // gate so the Successor-E post-routing overflow pass (below) can share
                // the same virtualGroupBounds + groupResizeCommands consolidation. When
                // the gate at line 3463 does NOT enter (e.g., routing succeeded without
                // failed connections so autoNudge has nothing to do), the maps stay
                // empty here and the post-pass starts fresh from EMF bounds.
                Map<String, IDiagramModelObject> allViewObjects = new LinkedHashMap<>();
                collectAllViewObjectMap(diagramModel, allViewObjects);
                Map<String, int[]> cumulativeDeltas = new LinkedHashMap<>();
                Map<String, int[]> virtualGroupBounds = new LinkedHashMap<>();
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
                        List<NudgedElementDto> iterationNudges = new ArrayList<>();
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

                            // Clamp nested elements to non-negative relative
                            // position within parent group. This prevents parent left/top
                            // expansion that cascades to negative canvas coordinates.
                            EObject container = dmo.eContainer();
                            if (container instanceof IDiagramModelGroup) {
                                int minPos = DEFAULT_GROUP_PADDING;
                                if (newX < minPos) newX = minPos;
                                if (newY < minPos) newY = minPos;
                            }

                            // Compute actual displacement after clamping
                            int actualDx = newX - bounds.getX() - priorDelta[0];
                            int actualDy = newY - bounds.getY() - priorDelta[1];

                            // Apply move command
                            commands.add(new UpdateViewObjectCommand(dmo, newX, newY,
                                    bounds.getWidth(), bounds.getHeight()));

                            // Resize parent group using virtual bounds
                            if (container instanceof IDiagramModelGroup parentGroup) {
                                resizeParentGroupIfNeeded(parentGroup, dmo, newX, newY,
                                        bounds.getWidth(), bounds.getHeight(),
                                        virtualGroupBounds, groupResizeCommands);
                            }

                            String elemName = dmo.getName() != null ? dmo.getName() : rec.elementId();
                            iterationNudges.add(new NudgedElementDto(
                                    rec.elementId(), elemName, actualDx, actualDy));

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
                        // Backlog-b15: Also apply virtual group bounds for accurate re-routing.
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
                                    VisibilityGraphRouter.DEFAULT_OCCUPANCY_WEIGHT, enableChannelNudging, true);
                            commands.addAll(reRouteResult.commands);
                            routedCount += reRouteResult.routedCount;
                            labelsOptimized += reRouteResult.labelsOptimized;
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

                    // Backlog-b15 + Successor E: Resize commands accumulated in
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
                                    prepareUpdateViewConnectionDirect(
                                            archConn, correctedBps, null, null, null, null);
                            commands.add(prepared.command());
                            correctedCount++;
                        }
                        if (correctedCount > 0) {
                            logger.info("Auto-nudge: corrected {} connections with nudged endpoints "
                                    + "(re-aligned terminals + re-encoded relative BPs)",
                                    correctedCount);
                        }
                    }

                    // Build consolidated nudgedElements list (one entry per element)
                    for (Map.Entry<String, int[]> entry : cumulativeDeltas.entrySet()) {
                        int[] deltas = entry.getValue();
                        nudgedElements.add(new NudgedElementDto(
                                entry.getKey(), elementNames.get(entry.getKey()),
                                deltas[0], deltas[1]));
                    }

                }

                // Successor E (RoutingAutoNudge.GroupBoundsFollowup, 2026-05-13).
                // Post-routing overflow detection pass — closes the residual gap in
                // the group-bounds fix when the autoNudge gate at line 3451 does NOT enter
                // (e.g., routing succeeded without failed connections so autoNudge has
                // nothing to nudge, OR the OverlapResolver early-skip at line 3435
                // bypassed autoNudge due to pre-existing sibling overlap). In those
                // cases, any pre-existing element/group overflow from prior workflow
                // steps (e.g., apply-element-spacing-recommendations inflated geometry
                // in a non-autoNudge code path) would persist because
                // resizeParentGroupIfNeeded never fires.
                //
                // Gated by effectiveAutoNudge so the pass ONLY fires when the caller
                // opted in to autoNudge — preserving the caller's intent when they
                // explicitly disabled it (force=true OR autoNudge=false).
                //
                // The pass reuses the hoisted virtualGroupBounds + groupResizeCommands
                // maps so any resizes the autoNudge block already emitted are not
                // duplicated — Map.put consolidates by groupId (latest resize wins,
                // and since we apply cumulativeDeltas before measuring, the latest
                // resize accounts for nudge-shifted positions too).
                //
                // Scope: this pass is autoNudge-path-only. The Row B / composed-tool
                // spacing path (apply-element-spacing-recommendations etc.) routes via
                // computeAutoRoutePass(force=true) and BYPASSES autoNudge entirely;
                // that gap is sibling scope (Successor E.b backlog row).
                if (effectiveAutoNudge) {
                    for (Map.Entry<String, IDiagramModelObject> entry
                            : allViewObjects.entrySet()) {
                        IDiagramModelObject dmo = entry.getValue();
                        EObject container = dmo.eContainer();
                        if (!(container instanceof IDiagramModelGroup parentGroup)) {
                            continue;
                        }
                        IBounds bounds = dmo.getBounds();
                        int[] delta = cumulativeDeltas.getOrDefault(
                                entry.getKey(), new int[]{0, 0});
                        int childNewX = bounds.getX() + delta[0];
                        int childNewY = bounds.getY() + delta[1];
                        // resizeParentGroupIfNeeded internally checks virtualGroupBounds
                        // first and falls back to parent EMF bounds; same shared-map
                        // consolidation pattern as the autoNudge loop above.
                        resizeParentGroupIfNeeded(parentGroup, dmo,
                                childNewX, childNewY,
                                bounds.getWidth(), bounds.getHeight(),
                                virtualGroupBounds, groupResizeCommands);
                    }
                }

                // Backlog-b15 + Successor E: Commit consolidated group resize commands
                // (one per group — autoNudge + post-pass share the same map; latest
                // resize wins via Map.put). When effectiveAutoNudge=false the maps are
                // empty and this is a no-op.
                commands.addAll(groupResizeCommands.values());

                // Successor E: Build resizedGroups DTO list from
                // virtual bounds (covers both autoNudge nudge-driven resizes AND
                // Successor-E post-pass overflow-detection resizes — single source).
                for (Map.Entry<String, int[]> entry : virtualGroupBounds.entrySet()) {
                    String groupId = entry.getKey();
                    int[] bounds = entry.getValue();
                    IDiagramModelObject groupObj = allViewObjects.get(groupId);
                    String groupName = groupObj != null && groupObj.getName() != null
                            ? groupObj.getName() : groupId;
                    resizedGroups.add(new ResizedGroupDto(
                            groupId, groupName,
                            bounds[0], bounds[1], bounds[2], bounds[3]));
                }

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

            // 6d. Crossing inflation warning
            String inflationWarning = RoutingPipeline.buildCrossingInflationWarning(
                    crossingsAfter, straightLineCrossings);
            if (inflationWarning != null) {
                warnings.add(inflationWarning);
            }

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
                    String crossedName = resolveCrossedElementName(crossedId, viewObjectNameMap, model);
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
                        structuredWarnings,
                        blockedDtos, nudgeBlockedReason);
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
                        // this routing-only nudge path, so leave them at the 0.0 sentinel; isJunction + fillColor
                        // ARE carried through (own-endpoint and container-fill checks re-run after the nudge).
                        node.imagePath(), node.imagePosition(), 0.0, 0.0, 0.0, node.isJunction(), node.fillColor()));
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
            if (vBounds != null && node.isGroup()) {
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
                        // this routing-only nudge path, so leave them at the 0.0 sentinel; isJunction + fillColor
                        // ARE carried through (own-endpoint and container-fill checks re-run after the nudge).
                        node.imagePath(), node.imagePosition(), 0.0, 0.0, 0.0, node.isJunction(), node.fillColor()));
            } else {
                adjusted.add(node);
            }
        }
        return adjusted;
    }

    /**
     * Resizes a parent group if the moved child element exceeds the group's current bounds.
     * Walks up the ancestor chain to resize grandparent groups as needed.
     *
     * <p>Backlog-b15: Uses {@code virtualGroupBounds} to track accumulated resize state across
     * multiple children in the same group within one nudge iteration. This prevents stale EMF
     * bounds from causing later resize commands to overwrite earlier ones.
     *
     * @param virtualGroupBounds  accumulated group bounds [x, y, w, h] keyed by group ID;
     *                            checked before EMF bounds, updated on resize
     * @param groupResizeCommands consolidated resize commands keyed by group ID; one per group
     */
    private void resizeParentGroupIfNeeded(IDiagramModelGroup parentGroup,
            IDiagramModelObject child, int childNewX, int childNewY,
            int childW, int childH,
            Map<String, int[]> virtualGroupBounds,
            Map<String, Command> groupResizeCommands) {
        String groupId = parentGroup.getId();
        int padding = DEFAULT_GROUP_PADDING;

        // Use accumulated virtual bounds if this group was already resized in this iteration,
        // otherwise fall back to stale EMF bounds
        int parentX, parentY, parentW, parentH;
        int[] vBounds = virtualGroupBounds.get(groupId);
        if (vBounds != null) {
            parentX = vBounds[0]; parentY = vBounds[1];
            parentW = vBounds[2]; parentH = vBounds[3];
        } else {
            IBounds parentBounds = parentGroup.getBounds();
            parentX = parentBounds.getX(); parentY = parentBounds.getY();
            parentW = parentBounds.getWidth(); parentH = parentBounds.getHeight();
        }

        // Check if child's new position exceeds parent's current dimensions.
        // Handle both right/bottom overflow and left/top overflow (M-2).
        // Successor E (2026-05-13): extracted predicate as static method so the
        // post-routing overflow pass + JUnit pin (`AutoNudgeGroupBoundsFollowupTest`)
        // can share the same overflow definition — single source of truth per
        // shared-helper guidance.
        boolean needsResize = childExceedsParentBounds(
                childNewX, childNewY, childW, childH, parentW, parentH, padding);

        if (needsResize) {
            // Formula mirrors childExceedsParentBounds — must stay in sync if padding logic changes.
            int requiredWidth = childNewX + childW + padding;
            int requiredHeight = childNewY + childH + padding;
            int newWidth = Math.max(parentW, requiredWidth);
            int newHeight = Math.max(parentH, requiredHeight);
            int newX = parentX;
            int newY = parentY;

            // Expand left/top if child has negative position within parent
            if (childNewX < 0) {
                newX += childNewX - padding;
                newWidth += -(childNewX - padding);
            }
            if (childNewY < 0) {
                newY += childNewY - padding;
                newHeight += -(childNewY - padding);
            }

            // Store accumulated bounds and consolidated command (one per group)
            virtualGroupBounds.put(groupId, new int[]{newX, newY, newWidth, newHeight});
            groupResizeCommands.put(groupId, new UpdateViewObjectCommand(parentGroup,
                    newX, newY, newWidth, newHeight));
            logger.debug("Auto-nudge: resized parent group {} to ({},{}) {}x{}",
                    groupId, newX, newY, newWidth, newHeight);

            // Recursively resize ancestor groups
            EObject grandparent = parentGroup.eContainer();
            if (grandparent instanceof IDiagramModelGroup grandparentGroup) {
                resizeParentGroupIfNeeded(grandparentGroup, parentGroup,
                        newX, newY,
                        newWidth, newHeight,
                        virtualGroupBounds, groupResizeCommands);
            }
        }
    }

    /**
     * Returns true iff the given child rectangle exceeds the parent group's
     * dimensions in ANY of the four overflow directions (right / bottom /
     * left / top) after the padding allowance.
     *
     * <p>Successor E (2026-05-13): extracted from the
     * inlined predicate inside {@link #resizeParentGroupIfNeeded} so the
     * post-routing overflow detection pass (inside
     * {@code autoRouteConnections} after the autoNudge block) and the new
     * dedicated JUnit pin class
     * {@code AutoNudgeGroupBoundsFollowupTest} share the same overflow
     * definition. Single source of truth per shared-helper guidance.</p>
     *
     * <p>Coordinate convention: {@code childNewX} / {@code childNewY} are
     * relative-to-parent, matching Archi's nested-object storage convention
     * (Tier-1 R1 overflow specification).</p>
     *
     * @param childNewX child's prospective relative-to-parent X (post-nudge or post-resize)
     * @param childNewY child's prospective relative-to-parent Y
     * @param childW    child width (unchanged across moves)
     * @param childH    child height
     * @param parentW   parent group's current width
     * @param parentH   parent group's current height
     * @param padding   padding allowance (typically
     *                  {@link #DEFAULT_GROUP_PADDING})
     * @return true if any of the four overflow conditions hold
     */
    static boolean childExceedsParentBounds(
            int childNewX, int childNewY, int childW, int childH,
            int parentW, int parentH, int padding) {
        int requiredWidth = childNewX + childW + padding;
        int requiredHeight = childNewY + childH + padding;
        return requiredWidth > parentW
                || requiredHeight > parentH
                || childNewX < 0 || childNewY < 0;
    }

    // ---- W2 icon-band parent-resize lever ----

    /**
     * Icon px size of Archi's cloud-icon family (16×16 — see
     * {@code archimate-view-patterns.md:420-429} cloud-icon mandate).
     */
    private static final int W2_ICON_SIZE = 16;
    /** Safety margin between icon and adjacent child (px). */
    private static final int W2_ICON_MARGIN = 8;

    /**
     * Computes an optional parent-resize command for W2 (icon-band reservation
     * at the CREATION moment, Task-1.2).
     *
     * <p>Fires when a new child is being added to a container parent that
     * already has a non-default corner-anchored icon AND the new child or any
     * existing sibling occupies that corner. Returns the parent-resize command
     * (possibly compounded with grandparent-group cascade commands) to wrap
     * with the {@code AddToViewCommand} so they execute atomically; returns
     * null when no resize is needed (Case A short-circuit when the
     * parent has no image position, AND Case B short-circuit when the corner
     * is empty).</p>
     *
     * <p>MVP scope (per Task-0.7 outcome): fires for bottom corners
     * (6 = bottom-left, 8 = bottom-right) only. Top-left (0) is recognised by
     * the pure-geometry predicate but no resize command is issued because the
     * accessor-layer fix would require shifting all existing siblings down by
     * {@code ICON_BAND_HEIGHT} — a much larger blast radius than the W2
     * retail-bank bug warrants. Top-right (2) is excluded as the Archi
     * default sentinel (byte-identical back-compat).</p>
     *
     * <p><strong>Grandparent-group cascade:</strong> when the
     * icon-bearing parent itself sits inside an {@link IDiagramModelGroup}
     * grandparent and the parent's new height would exceed the grandparent's
     * bounds, the cascade flows through the shared
     * {@link #resizeParentGroupIfNeeded} so the grandparent grows in lock-step
     * — single source of truth
     * (sibling-symmetric with the H6 logic at {@code prepareUpdateViewObject}).</p>
     *
     * <p><strong>Growth amount (design choice):</strong> the lever always
     * grows by exactly {@link ImageHelper#ICON_BAND_HEIGHT} when it fires —
     * NOT the precise child-intrusion shortfall. The over-grow is bounded
     * (max 24 px), one-shot (the predicate becomes false after growth so
     * subsequent children don't re-trigger), and avoids a second traversal
     * of the child list to compute precise depth. Sibling-symmetric with
     * {@code GROUP_LABEL_HEIGHT = 24}: groups always reserve 24 px for the
     * label band regardless of how tall the label actually is.</p>
     *
     * @param parentContainer the resolved parent container (may be the view itself)
     * @param newChildX       new child's bounds (relative-to-parent)
     * @param newChildY       …
     * @param newChildW       …
     * @param newChildH       …
     * @return parent-resize command (possibly compounded with grandparent
     *         cascade), or null when no resize is needed
     */
    private Command computeIconBandParentResizeCommand(
            IDiagramModelContainer parentContainer,
            int newChildX, int newChildY, int newChildW, int newChildH) {
        if (!(parentContainer instanceof IDiagramModelObject parentObj)) {
            return null; // parent is the view itself — no icon-band reservation
        }
        if (!(parentObj instanceof IIconic)) {
            return null; // parent doesn't carry an image — nothing to reserve
        }
        int parentImgPos = ImageHelper.readImagePositionInt(parentObj);
        // Restrict to non-default corners (skip 2 = top-right Archi default;
        // skip 0 = top-left because the fix requires child-shift, deferred).
        if (parentImgPos != 6 && parentImgPos != 8) {
            return null;
        }
        IBounds pb = parentObj.getBounds();
        int parentX = pb.getX();
        int parentY = pb.getY();
        int parentW = pb.getWidth();
        int parentH = pb.getHeight();

        List<int[]> rects = new ArrayList<>();
        for (IDiagramModelObject sib : parentContainer.getChildren()) {
            IBounds sb = sib.getBounds();
            rects.add(new int[] {sb.getX(), sb.getY(), sb.getWidth(), sb.getHeight()});
        }
        // Include the prospective new child (parent-relative coords — matches
        // Archi's nested-object storage convention, see `childExceedsParentBounds`
        // javadoc at line 4158-4161).
        rects.add(new int[] {newChildX, newChildY, newChildW, newChildH});

        if (!ImageHelper.anyChildOccupiesIconBand(parentW, parentH, parentImgPos,
                W2_ICON_SIZE, W2_ICON_MARGIN, rects)) {
            return null; // Case B short-circuit — corner empty, byte-identical
        }

        int newParentH = parentH + ImageHelper.ICON_BAND_HEIGHT;
        Command parentResize = new UpdateViewObjectCommand(parentObj, parentX, parentY, parentW, newParentH);

        // Grandparent-group cascade (applied in-session
        // 2026-05-20). When the icon-bearing parent itself lives inside an
        // IDiagramModelGroup grandparent, flow through the shared
        // resizeParentGroupIfNeeded helper so the grandparent grows when the
        // now-taller parent would exceed grandparent bounds. Sibling-symmetric
        // with the H6 MUTATION-moment cascade at prepareUpdateViewObject :12595.
        EObject grandparent = parentObj.eContainer();
        if (grandparent instanceof IDiagramModelGroup grandparentGroup) {
            Map<String, int[]> virtualGroupBounds = new LinkedHashMap<>();
            Map<String, Command> groupResizeCommands = new LinkedHashMap<>();
            resizeParentGroupIfNeeded(grandparentGroup, parentObj,
                    parentX, parentY, parentW, newParentH,
                    virtualGroupBounds, groupResizeCommands);
            if (!groupResizeCommands.isEmpty()) {
                NonNotifyingCompoundCommand cascade = new NonNotifyingCompoundCommand(
                        "Icon-band parent-resize with grandparent-group cascade");
                cascade.add(parentResize);
                for (Command resize : groupResizeCommands.values()) {
                    cascade.add(resize);
                }
                return cascade;
            }
        }

        return parentResize;
    }

    /** Maps a constraint violation type to a severity string. */
    private static String severityFor(String constraintViolated) {
        if ("element_crossing".equals(constraintViolated)) {
            return "warning";
        }
        return "info";
    }

    /**
     * Resolves the display name for a crossed element ID.
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
            String direction, int spacing, String targetRating) {
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
                return executeGroupedMode(sessionId, viewId, direction,
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
                return executeSingleLayoutPass(sessionId, viewId, direction,
                        spacing, model, diagramModel, nodes, edges);
            }

            // quality target iteration loop
            return executeQualityTargetLoop(sessionId, viewId, direction,
                    spacing, targetRating, model, diagramModel, nodes, edges);

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
                    "auto-layout-and-route",
                    () -> new PreparedMutation<>(pass.compound, dto, viewId),
                    compoundTargetIds(pass.compound, viewId), dto, pass.compound.getLabel(),
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
     * Quality target iteration loop.
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
        int bestScore = Integer.MAX_VALUE; // tier-weighted score (lower is better) — see tierWeightedScore()
        int bestPositionCount = 0;
        int bestRoutedCount = 0;
        int bestLabelsOptimized = 0;
        boolean bestRouterTypeSwitched = false;
        int bestSpacing = effectiveBaseSpacing;
        AssessLayoutResultDto bestAssessment = null;

        String previousRating = null;
        String previousLimitingFactor = null;
        int previousFactorCount = 0;
        int iterationsPerformed = 0;
        String currentLimitingFactor = null;
        int spacingStep = 0;

        for (int i = 0; i < MAX_TARGET_RATING_ITERATIONS; i++) {
            int currentSpacing = effectiveBaseSpacing
                    + (spacingStep * TARGET_RATING_SPACING_INCREMENT);

            // Factor-aware dispatch: determine remediation type for this iteration
            // ELK mode: no separate crossing remediation — ELK handles its own crossing reduction
            String remediationType;
            if (i == 0 || currentLimitingFactor == null) {
                remediationType = "full-pipeline";
            } else {
                remediationType = switch (currentLimitingFactor) {
                    case "overlaps", "edgeCrossings", "spacing", "alignment"
                            -> "elk-spacing-increase";
                    case "passThroughs", "coincidentSegments" -> "reroute-only";
                    case "labelOverlaps" -> "early-exit-label";
                    case "nonOrthogonalTerminals" -> "early-exit-nonorth";
                    default -> "elk-spacing-increase";
                };
            }

            // Early exit for non-remediable factors (best already tracked from prior iteration)
            if ("early-exit-label".equals(remediationType)
                    || "early-exit-nonorth".equals(remediationType)) {
                logger.info("Quality target iteration {}: limitingFactor={}, remediation={}",
                        i + 1, currentLimitingFactor, remediationType);
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
                routeResult = computeAutoRoutePass(viewId, diagramModel, model, boostedWeight);
                if (routeResult != null) {
                    mutationDispatcher.dispatchImmediate(routeResult.compound);
                    undoCount++;
                }
            } else {
                // Full pipeline / ELK spacing increase: ELK layout + optimize (if groups) + route
                logger.info("Quality target iteration {}: limitingFactor={}, remediation={}",
                        iterationsPerformed, currentLimitingFactor, remediationType);
                pass = computeElkLayoutPass(
                        viewId, direction, currentSpacing, model,
                        diagramModel, iterNodes, iterEdges);
                mutationDispatcher.dispatchImmediate(pass.compound);
                undoCount++;

                // optimize-group-order pass (grouped views only)
                optimizeResult = computeOptimizeGroupOrderPass(diagramModel, model, direction);
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

                // Spacing used — advance for next layout iteration
                spacingStep++;
            }

            // Assess layout quality
            AssessLayoutResultDto assessment = assessLayout(viewId);
            String rating = assessment.overallRating();
            int score = tierWeightedScore(assessment);

            // Update limiting factor for next iteration
            currentLimitingFactor = findLimitingFactor(assessment);

            logger.info("Quality target iteration {}: spacing={}, rating={}, avgSpacing={}, overlaps={}, crossings={}{}",
                    iterationsPerformed, currentSpacing, rating,
                    assessment.averageSpacing(),
                    assessment.overlapCount(), assessment.edgeCrossingCount(),
                    optimizeResult != null ? " [+optimize-group-order]" : "");

            // Track best result — merge all compounds into one for atomic undo (tier-weighted + veto)
            if (!hasTier1Regression(assessment, bestAssessment)
                    && (LayoutQualityAssessor.ratingOrdinal(rating) > LayoutQualityAssessor.ratingOrdinal(bestRating)
                        || (LayoutQualityAssessor.ratingOrdinal(rating) == LayoutQualityAssessor.ratingOrdinal(bestRating)
                            && score < bestScore))) {
                NonNotifyingCompoundCommand mergedCompound =
                        new NonNotifyingCompoundCommand(
                                "ELK layout iter " + iterationsPerformed
                                        + " (" + remediationType + ")");
                if (pass != null) {
                    for (Object cmd : pass.compound.getCommands()) {
                        mergedCompound.add((Command) cmd);
                    }
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
                bestPositionCount = (pass != null ? pass.positionCount : 0)
                        + (optimizeResult != null ? optimizeResult.positionCount : 0);
                bestRoutedCount = (pass != null ? pass.routedCount : 0)
                        + (routeResult != null ? routeResult.routedCount : 0);
                bestLabelsOptimized = (routeResult != null ? routeResult.labelsOptimized : 0);
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
                break;
            }

            // Null limiting factor means all metrics pass — target should be met
            if (currentLimitingFactor == null) {
                logger.info("Quality target: no limiting factor — all metrics pass on iteration {}",
                        iterationsPerformed);
                break;
            }

            // Factor-aware plateau detection
            int currentFactorCount = getMetricCount(currentLimitingFactor, assessment);

            if (i > 0 && isFactorAwarePlateauReached(
                    currentLimitingFactor, previousLimitingFactor,
                    currentFactorCount, previousFactorCount,
                    rating, previousRating)) {
                logger.info("Quality target plateau detected at iteration {} — "
                    + "factor={}, count={}, stopping early", iterationsPerformed,
                    currentLimitingFactor, currentFactorCount);
                break;
            }

            previousRating = rating;
            previousLimitingFactor = currentLimitingFactor;
            previousFactorCount = currentFactorCount;
        }

        // Finalize: nothing is currently applied — dispatch best via approval/batch
        if (bestCompound == null) {
            throw new ModelAccessException(
                    "Quality target iteration produced no results",
                    ErrorCode.INTERNAL_ERROR);
        }

        // Label optimization fallback
        int labelFallbackTrials = 0;
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

        logger.info("Quality target loop complete: best='{}' after {} iterations (target='{}')",
                bestRating, iterationsPerformed, targetRating);

        AutoLayoutAndRouteResultDto dto = buildQualityTargetDto(
                "auto", viewId, effectiveDirection, bestSpacing,
                bestPositionCount, bestRoutedCount, bestRouterTypeSwitched,
                bestCompound.size(), 0, bestLabelsOptimized, labelFallbackTrials,
                targetRating, bestRating,
                iterationsPerformed, bestAssessment);

        // Approval gate (edge case #4: applies to final iteration only)
        if (mutationDispatcher.isApprovalRequired(sessionId)) {
            Map<String, Object> proposedChanges = new LinkedHashMap<>();
            proposedChanges.put("direction", effectiveDirection);
            proposedChanges.put("spacing", bestSpacing);
            proposedChanges.put("elementsRepositioned", bestPositionCount);
            proposedChanges.put("connectionsRouted", bestRoutedCount);
            proposedChanges.put("targetRating", targetRating);
            proposedChanges.put("achievedRating", bestRating);
            proposedChanges.put("iterationsPerformed", iterationsPerformed);
            final var approvedCompound = bestCompound; // effectively-final capture for the rebuild handle
            ProposalContext ctx = storeAsProposal(sessionId,
                    "auto-layout-and-route",
                    () -> new PreparedMutation<>(approvedCompound, dto, viewId),
                    compoundTargetIds(approvedCompound, viewId), dto,
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

        GroupedLayoutPassResult(NonNotifyingCompoundCommand compound,
                int elementsRepositioned, int groupsArranged) {
            this.compound = compound;
            this.elementsRepositioned = elementsRepositioned;
            this.groupsArranged = groupsArranged;
        }
    }

    /**
     * Entry point for grouped mode.
     * Validates that the view has groups, then delegates to single-pass or quality loop.
     */
    private MutationResult<AutoLayoutAndRouteResultDto> executeGroupedMode(
            String sessionId, String viewId, String direction, int spacing,
            String targetRating, IArchimateModel model,
            IArchimateDiagramModel diagramModel) {

        // Flat-view guard: check for top-level groups with children
        List<IDiagramModelGroup> topLevelGroups = new ArrayList<>();
        for (IDiagramModelObject child : diagramModel.getChildren()) {
            if (child instanceof IDiagramModelGroup group
                    && !group.getChildren().isEmpty()) {
                topLevelGroups.add(group);
            }
        }
        if (topLevelGroups.isEmpty()) {
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
                    effectiveSpacing, model, diagramModel, topLevelGroups);
        }

        // Quality target iteration loop for grouped mode
        return executeGroupedQualityTargetLoop(sessionId, viewId, effectiveDirection,
                effectiveSpacing, targetRating, model, diagramModel, topLevelGroups);
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
            List<IDiagramModelGroup> topLevelGroups) {

        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand("Grouped Layout (mode=grouped)");
        int elementsRepositioned = 0;
        int groupsArranged = 0;

        // Step 1: Layout within each top-level group
        int resolvedPadding = DEFAULT_GROUP_PADDING;
        int startX = resolvedPadding;
        int startY = resolvedPadding + GROUP_LABEL_HEIGHT;

        // Track virtual group bounds (post-resize dimensions) for arrange-groups
        Map<String, int[]> virtualGroupBounds = new HashMap<>();

        for (IDiagramModelGroup group : topLevelGroups) {
            // Collect direct children (skip notes)
            List<IDiagramModelObject> children = new ArrayList<>();
            for (IDiagramModelObject child : group.getChildren()) {
                if (!(child instanceof IDiagramModelNote)) {
                    children.add(child);
                }
            }
            if (children.isEmpty()) continue;

            // Choose intra-group arrangement based on element count and flow direction
            boolean effectiveAutoWidth = true;
            String intraArrangement = GroupLayoutCalculator.chooseIntraGroupArrangement(
                    children.size(), direction);
            List<int[]> positions;
            switch (intraArrangement) {
            case "row":
                positions = computeRowLayout(children, startX, startY,
                        intraGroupSpacing, null, null, effectiveAutoWidth);
                break;
            case "grid":
                int gridCols = GroupLayoutCalculator.computeGridColumns(children.size());
                GroupLayoutCalculator.GridLayoutResult gridResult =
                        computeGridLayout(children, startX, startY,
                                intraGroupSpacing, resolvedPadding, 0,
                                null, null, effectiveAutoWidth, gridCols);
                positions = gridResult.positions();
                break;
            default: // "column"
                positions = computeColumnLayout(children, startX, startY,
                        intraGroupSpacing, null, null, effectiveAutoWidth);
                break;
            }

            // Build update commands for each child
            for (int i = 0; i < children.size(); i++) {
                IDiagramModelObject child = children.get(i);
                int[] pos = positions.get(i);
                compound.add(new UpdateViewObjectCommand(child,
                        pos[0], pos[1], pos[2], pos[3]));
                elementsRepositioned++;
            }

            // Auto-resize group
            int[] groupDims = computeAutoResizeDimensions(
                    positions, resolvedPadding, GROUP_LABEL_HEIGHT);
            IBounds currentBounds = group.getBounds();
            compound.add(new UpdateViewObjectCommand(group,
                    currentBounds.getX(), currentBounds.getY(),
                    groupDims[0], groupDims[1]));

            // Track virtual bounds for arrange-groups (uses post-resize dimensions)
            virtualGroupBounds.put(group.getId(),
                    new int[]{groupDims[0], groupDims[1]});

            // Resize ancestors if nested
            List<Command> ancestorCommands = new ArrayList<>();
            resizeAncestorGroups(group, ancestorCommands, resolvedPadding);
            ancestorCommands.forEach(compound::add);
        }

        // Remember command count before arrange-groups (for retry: clear arrange commands only)
        int layoutCommandCount = compound.size();

        // Step 2: Arrange groups by topology
        String arrangement = ("RIGHT".equalsIgnoreCase(direction)
                || "LEFT".equalsIgnoreCase(direction)) ? "row" : "column";

        // Re-read top-level groups. Intentionally includes empty groups.
        List<IDiagramModelGroup> arrangeTargets = new ArrayList<>();
        for (IDiagramModelObject child : diagramModel.getChildren()) {
            if (child instanceof IDiagramModelGroup group) {
                arrangeTargets.add(group);
            }
        }

        if (!arrangeTargets.isEmpty()) {
            // Build topology ordering (computed once, reused across retries)
            Map<String, String> elementToGroup = new HashMap<>();
            for (IDiagramModelGroup group : arrangeTargets) {
                mapElementsToGroup(group, group.getId(), elementToGroup);
            }

            Map<String, Map<String, Integer>> weights = new HashMap<>();
            for (IDiagramModelObject child : diagramModel.getChildren()) {
                collectConnectionWeights(child, elementToGroup, weights);
            }

            List<String> groupIdsList = new ArrayList<>();
            for (IDiagramModelGroup g : arrangeTargets) {
                groupIdsList.add(g.getId());
            }

            GroupTopologyOrderer orderer = new GroupTopologyOrderer();
            List<String> orderedIds = orderer.orderLinear(groupIdsList, weights);

            Map<String, IDiagramModelGroup> groupById = new LinkedHashMap<>();
            for (IDiagramModelGroup g : arrangeTargets) {
                groupById.put(g.getId(), g);
            }
            List<IDiagramModelGroup> orderedGroups = new ArrayList<>();
            for (String id : orderedIds) {
                IDiagramModelGroup g = groupById.get(id);
                if (g != null) orderedGroups.add(g);
            }

            // Classify qualifying
            // standalone top-level elements + assign each to an inter-group gap.
            // mode='grouped' parity with the user-facing arrangeGroups path.
            List<ArrangeGroupsStandaloneLane.QualifyingStandaloneElement> qualifyingElements =
                    ArrangeGroupsStandaloneLane.classify(
                            diagramModel.getChildren(), orderedGroups, elementToGroup);
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
                int arrangeStartX = ARRANGE_GROUPS_ORIGIN;
                int arrangeStartY = ARRANGE_GROUPS_ORIGIN;
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
                        IDiagramModelGroup g = orderedGroups.get(i);
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
                        IDiagramModelGroup g = orderedGroups.get(i);
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
                    IDiagramModelGroup g = orderedGroups.get(i);
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
                    IDiagramModelGroup g = orderedGroups.get(i);
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

        return new GroupedLayoutPassResult(compound, elementsRepositioned, groupsArranged);
    }

    /**
     * Single-pass grouped mode without quality iteration.
     */
    private MutationResult<AutoLayoutAndRouteResultDto> executeGroupedSinglePass(
            String sessionId, String viewId, String direction, int spacing,
            IArchimateModel model, IArchimateDiagramModel diagramModel,
            List<IDiagramModelGroup> topLevelGroups) {

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
        AutoRoutePassResult routeResult = computeAutoRoutePass(viewId, diagramModel, model);
        if (routeResult != null) {
            mutationDispatcher.dispatchImmediate(routeResult.compound);
            undoCount++;
        }

        // Undo all temporary dispatches
        mutationDispatcher.undo(undoCount);

        // Merge all compounds into one for final dispatch
        NonNotifyingCompoundCommand mergedCompound =
                new NonNotifyingCompoundCommand(layoutPass.compound.getLabel());
        for (Object cmd : layoutPass.compound.getCommands()) {
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

        int totalPositionCount = layoutPass.elementsRepositioned
                + (optimizeResult != null ? optimizeResult.positionCount : 0);
        int totalRoutedCount = (routeResult != null ? routeResult.routedCount : 0);
        int labelsOptimized = (routeResult != null ? routeResult.labelsOptimized : 0);
        boolean routerTypeSwitched = (routeResult != null && routeResult.routerTypeSwitched);

        AutoLayoutAndRouteResultDto dto = new AutoLayoutAndRouteResultDto(
                viewId, "grouped", direction, spacing,
                totalPositionCount, totalRoutedCount, routerTypeSwitched,
                mergedCompound.size(), layoutPass.groupsArranged,
                labelsOptimized, 0, null, null, null, null, null, null);

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
                    "Grouped layout computed and ready for application.");
            return new MutationResult<>(dto, null, ctx);
        }

        // Dispatch or queue
        Integer batchSeq = dispatchOrQueue(sessionId, mergedCompound,
                mergedCompound.getLabel());
        if (batchSeq == null) {
            versionCounter.incrementAndGet();
        }
        return new MutationResult<>(dto, batchSeq);
    }

    /**
     * Quality target iteration loop for grouped mode.
     * Same iteration strategy as ELK mode: increase spacing, track best, plateau detection.
     */
    private MutationResult<AutoLayoutAndRouteResultDto> executeGroupedQualityTargetLoop(
            String sessionId, String viewId, String direction, int baseSpacing,
            String targetRating, IArchimateModel model,
            IArchimateDiagramModel diagramModel,
            List<IDiagramModelGroup> topLevelGroups) {

        // Track best result across iterations
        NonNotifyingCompoundCommand bestCompound = null;
        String bestRating = "not-applicable";
        int bestScore = Integer.MAX_VALUE;
        int bestPositionCount = 0;
        int bestRoutedCount = 0;
        int bestLabelsOptimized = 0;
        boolean bestRouterTypeSwitched = false;
        int bestGroupsArranged = 0;
        int bestSpacing = baseSpacing;
        AssessLayoutResultDto bestAssessment = null;

        String previousRating = null;
        String previousLimitingFactor = null;
        int previousFactorCount = 0;
        int iterationsPerformed = 0;
        String currentLimitingFactor = null;
        int spacingStep = 0;

        for (int i = 0; i < MAX_TARGET_RATING_ITERATIONS; i++) {
            int currentIntraSpacing = baseSpacing
                    + (spacingStep * TARGET_RATING_SPACING_INCREMENT);
            int currentInterSpacing = (int) (currentIntraSpacing * 1.5)
                    + (spacingStep * TARGET_RATING_SPACING_INCREMENT);

            // Factor-aware dispatch: determine remediation type for this iteration
            String remediationType;
            if (i == 0 || currentLimitingFactor == null) {
                remediationType = "full-pipeline";
            } else {
                remediationType = switch (currentLimitingFactor) {
                    case "overlaps", "spacing", "alignment" -> "spacing-increase";
                    case "edgeCrossings" -> "reorder-and-reroute";
                    case "passThroughs", "coincidentSegments" -> "reroute-only";
                    case "labelOverlaps" -> "early-exit-label";
                    case "nonOrthogonalTerminals" -> "early-exit-nonorth";
                    default -> "spacing-increase";
                };
            }

            // Early exit for non-remediable factors (best already tracked from prior iteration)
            if ("early-exit-label".equals(remediationType)
                    || "early-exit-nonorth".equals(remediationType)) {
                logger.info("Quality target iteration {}: limitingFactor={}, remediation={}",
                        i + 1, currentLimitingFactor, remediationType);
                break;
            }

            iterationsPerformed = i + 1;

            // Re-discover top-level groups each iteration (EMF state changes after undo)
            List<IDiagramModelGroup> iterGroups;
            if (i == 0) {
                iterGroups = topLevelGroups;
            } else {
                iterGroups = new ArrayList<>();
                for (IDiagramModelObject child : diagramModel.getChildren()) {
                    if (child instanceof IDiagramModelGroup group
                            && !group.getChildren().isEmpty()) {
                        iterGroups.add(group);
                    }
                }
            }

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
                routeResult = computeAutoRoutePass(viewId, diagramModel, model, boostedWeight);
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
                    routeResult = computeAutoRoutePass(viewId, diagramModel, model);
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

                routeResult = computeAutoRoutePass(viewId, diagramModel, model);
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
            int score = tierWeightedScore(assessment);

            // Update limiting factor for next iteration
            currentLimitingFactor = findLimitingFactor(assessment);

            logger.info("Grouped quality target iteration {}: intraSpacing={}, "
                    + "interSpacing={}, rating={}, overlaps={}, crossings={}, groups={}",
                    iterationsPerformed, currentIntraSpacing, currentInterSpacing,
                    rating, assessment.overlapCount(), assessment.edgeCrossingCount(),
                    layoutPass != null ? layoutPass.groupsArranged : 0);

            // Track best result — merge all compounds into one (tier-weighted + veto)
            if (!hasTier1Regression(assessment, bestAssessment)
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
                    for (Object cmd : layoutPass.compound.getCommands()) {
                        mergedCompound.add((Command) cmd);
                    }
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
                bestPositionCount = (layoutPass != null ? layoutPass.elementsRepositioned : 0)
                        + (optimizeResult != null ? optimizeResult.positionCount : 0);
                bestRoutedCount = (routeResult != null ? routeResult.routedCount : 0);
                bestLabelsOptimized = (routeResult != null ? routeResult.labelsOptimized : 0);
                bestRouterTypeSwitched = (routeResult != null && routeResult.routerTypeSwitched);
                bestGroupsArranged = (layoutPass != null ? layoutPass.groupsArranged : 0);
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
                break;
            }

            // Null limiting factor means all metrics pass — target should be met
            if (currentLimitingFactor == null) {
                logger.info("Grouped quality target: no limiting factor — all metrics pass on iteration {}",
                        iterationsPerformed);
                break;
            }

            // Factor-aware plateau detection
            int currentFactorCount = getMetricCount(currentLimitingFactor, assessment);

            if (i > 0 && isFactorAwarePlateauReached(
                    currentLimitingFactor, previousLimitingFactor,
                    currentFactorCount, previousFactorCount,
                    rating, previousRating)) {
                logger.info("Grouped quality target plateau detected at iteration {} — "
                    + "factor={}, count={}, stopping early", iterationsPerformed,
                    currentLimitingFactor, currentFactorCount);
                break;
            }

            previousRating = rating;
            previousLimitingFactor = currentLimitingFactor;
            previousFactorCount = currentFactorCount;
        }

        if (bestCompound == null) {
            throw new ModelAccessException(
                    "Grouped quality target iteration produced no results",
                    ErrorCode.INTERNAL_ERROR);
        }

        // Label optimization fallback
        int labelFallbackTrials = 0;
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

        logger.info("Grouped quality target loop complete: best='{}' after {} iterations (target='{}')",
                bestRating, iterationsPerformed, targetRating);

        AutoLayoutAndRouteResultDto dto = buildQualityTargetDto(
                "grouped", viewId, direction, bestSpacing,
                bestPositionCount, bestRoutedCount, bestRouterTypeSwitched,
                bestCompound.size(), bestGroupsArranged,
                bestLabelsOptimized, labelFallbackTrials,
                targetRating, bestRating,
                iterationsPerformed, bestAssessment);

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
            proposedChanges.put("iterationsPerformed", iterationsPerformed);
            final var approvedCompound = bestCompound; // effectively-final capture for the rebuild handle
            ProposalContext ctx = storeAsProposal(sessionId,
                    "auto-layout-and-route",
                    () -> new PreparedMutation<>(approvedCompound, dto, viewId),
                    compoundTargetIds(approvedCompound, viewId), dto,
                    bestCompound.getLabel(), null, proposedChanges,
                    "Grouped layout with quality target '" + targetRating
                    + "' computed — achieved '" + bestRating
                    + "' after " + iterationsPerformed + " iteration(s).");
            return new MutationResult<>(dto, null, ctx);
        }

        // Dispatch or queue
        Integer batchSeq = dispatchOrQueue(sessionId, bestCompound,
                bestCompound.getLabel());
        if (batchSeq == null) {
            versionCounter.incrementAndGet();
        }
        return new MutationResult<>(dto, batchSeq);
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

        LabelOptimizationPassResult labelResult =
                computeLabelOptimizationPass(diagramModel, model, 10);

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
        int fallbackScore = tierWeightedScore(fallbackAssessment);

        // tier-weighted + veto comparison
        boolean improved = !hasTier1Regression(fallbackAssessment, bestAssessment)
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

        // Compute limiting factor when target not met
        String limitingFactor = null;
        String suggestedRemediation = null;
        if (targetRating != null && !LayoutQualityAssessor.meetsTarget(achievedRating, targetRating)
                && assessment != null && assessment.ratingBreakdown() != null) {
            limitingFactor = findLimitingFactor(assessment);
            if (limitingFactor != null) {
                suggestedRemediation = getRemediation(limitingFactor);
            }
        }

        return new AutoLayoutAndRouteResultDto(
                viewId, mode, direction, spacing,
                positionCount, routedCount, routerTypeSwitched, totalOperations,
                groupsArranged, labelsOptimized, labelFallbackTrials,
                targetRating, achievedRating,
                iterationsPerformed, summary, limitingFactor, suggestedRemediation);
    }

    /**
     * Finds the worst-performing metric from the rating breakdown.
     * Skips "overall" and "pass" ratings. Tie-breaks by count from the assessment.
     * When counts are equal (e.g. spacing and alignment both have count 0),
     * the first metric in iteration order wins (LinkedHashMap from LayoutQualityAssessor).
     */
    static String findLimitingFactor(AssessLayoutResultDto assessment) {
        Map<String, String> breakdown = assessment.ratingBreakdown();
        String worstMetric = null;
        int worstOrdinal = Integer.MAX_VALUE;
        int worstCount = -1;

        for (Map.Entry<String, String> entry : breakdown.entrySet()) {
            String metric = entry.getKey();
            String rating = entry.getValue();

            // Skip the aggregate "overall" entry and "pass" (not-applicable) metrics
            if ("overall".equals(metric) || "pass".equals(rating)) {
                continue;
            }

            int ordinal = LayoutQualityAssessor.ratingOrdinal(rating);
            int count = getMetricCount(metric, assessment);

            if (ordinal < worstOrdinal || (ordinal == worstOrdinal && count > worstCount)) {
                worstOrdinal = ordinal;
                worstCount = count;
                worstMetric = metric;
            }
        }
        return worstMetric;
    }

    /**
     * Computes a tier-weighted quality score reflecting the M6 two-dimensional severity
     * hierarchy (layout-tier × routing-tier — see {@code LayoutQualityAssessor.computeLayoutTier}
     * + {@code computeRoutingTier}). Lower is better.
     * <p>Layout tier 1L: overlaps ×10, boundaryViolations ×10, parentLabelObscured ×6.
     * Routing tier 1R: passThroughs ×8, interiorTerminations ×8, zigzags ×8, coincidentSegments ×6.
     * Routing tier 2R: nonOrthogonalTerminals ×3, connectionEdgeCoincidence ×3,
     * hubPortQuality (binary &lt; FAIR threshold) ×2, labelOverlaps ×2, labelTruncations ×2.
     * Routing tier 3R: edgeCrossings ×1.
     * <p>Layout tier 2L (offCanvas, averageSpacing) and 3L (alignmentScore) do not contribute —
     * informational/cosmetic metrics covered by the rating ordinal at the iteration callsite.
     */
    static int tierWeightedScore(AssessLayoutResultDto a) {
        int ptCount = a.connectionPassThroughs() != null ? a.connectionPassThroughs().size() : 0;
        int boundaryCount = a.boundaryViolations() != null ? a.boundaryViolations().size() : 0;
        int hubPortLowQuality = a.hubPortQualityScore() < LayoutQualityAssessor.HUB_PORT_QUALITY_FAIR_THRESHOLD ? 1 : 0;
        return (a.overlapCount() * 10)
             + (boundaryCount * 10)
             + (a.parentLabelObscuredCount() * 6)
             + (ptCount * 8)
             + (a.interiorTerminationCount() * 8)
             + (a.zigzagCount() * 8)
             + (a.coincidentSegmentCount() * 6)
             + (a.nonOrthogonalTerminalCount() * 3)
             + (a.connectionEdgeCoincidenceCount() * 3)
             + (hubPortLowQuality * 2)
             + (a.labelOverlapCount() * 2)
             + (a.labelTruncationCount() * 2)
             + (a.edgeCrossingCount() * 1);
    }

    /**
     * Returns true if the current assessment has regressed on any Tier-1 metric
     * compared to the best assessment. Any Tier-1 regression vetoes the iteration
     * regardless of improvements in lower tiers.
     * <p>Tier-1L (layout): overlaps, boundaryViolations, parentLabelObscured.
     * Tier-1R (routing): passThroughs, interiorTerminations, zigzags, coincidentSegments.
     * Mirrors {@code LayoutQualityAssessor.computeLayoutTier}
     * /{@code computeRoutingTier} Tier-1 classification.
     */
    static boolean hasTier1Regression(AssessLayoutResultDto current, AssessLayoutResultDto best) {
        if (best == null) {
            return false; // No baseline to regress against (first iteration)
        }
        int currentPt = current.connectionPassThroughs() != null ? current.connectionPassThroughs().size() : 0;
        int bestPt = best.connectionPassThroughs() != null ? best.connectionPassThroughs().size() : 0;
        int currentBoundary = current.boundaryViolations() != null ? current.boundaryViolations().size() : 0;
        int bestBoundary = best.boundaryViolations() != null ? best.boundaryViolations().size() : 0;
        return current.overlapCount() > best.overlapCount()
            || currentBoundary > bestBoundary
            || current.parentLabelObscuredCount() > best.parentLabelObscuredCount()
            || currentPt > bestPt
            || current.interiorTerminationCount() > best.interiorTerminationCount()
            || current.zigzagCount() > best.zigzagCount()
            || current.coincidentSegmentCount() > best.coincidentSegmentCount();
    }

    /**
     * Returns the count associated with a breakdown metric for tie-breaking in
     * {@code findLimitingFactor}. Maps each key emitted by
     * {@code LayoutQualityAssessor.computeRatingWithBreakdown} ({@code LayoutQualityAssessor.java:739-879})
     * to its assessment field accessor.
     * <p>Mapped keys (14): overlaps, edgeCrossings, labelOverlaps, passThroughs,
     * coincidentSegments, nonOrthogonalTerminals, boundaryViolations, parentLabelObscured,
     * offCanvas, labelTruncations, interiorTerminations, zigzags,
     * connectionEdgeCoincidence, hubPortQuality (binary low-quality flag).
     * <p>Default branch: {@code spacing} + {@code alignment} (informational with no integer count).
     */
    static int getMetricCount(String metric, AssessLayoutResultDto assessment) {
        return switch (metric) {
            case "overlaps" -> assessment.overlapCount();
            case "edgeCrossings" -> assessment.edgeCrossingCount();
            case "labelOverlaps" -> assessment.labelOverlapCount();
            case "passThroughs" -> assessment.connectionPassThroughs() != null
                    ? assessment.connectionPassThroughs().size() : 0;
            case "coincidentSegments" -> assessment.coincidentSegmentCount();
            case "nonOrthogonalTerminals" -> assessment.nonOrthogonalTerminalCount();
            case "boundaryViolations" -> assessment.boundaryViolations() != null
                    ? assessment.boundaryViolations().size() : 0;
            case "parentLabelObscured" -> assessment.parentLabelObscuredCount();
            case "offCanvas" -> assessment.offCanvasWarnings() != null
                    ? assessment.offCanvasWarnings().size() : 0;
            case "labelTruncations" -> assessment.labelTruncationCount();
            case "interiorTerminations" -> assessment.interiorTerminationCount();
            case "zigzags" -> assessment.zigzagCount();
            case "connectionEdgeCoincidence" -> assessment.connectionEdgeCoincidenceCount();
            case "hubPortQuality" -> assessment.hubPortQualityScore() < LayoutQualityAssessor.HUB_PORT_QUALITY_FAIR_THRESHOLD ? 1 : 0;
            default -> 0; // spacing, alignment — no direct count
        };
    }

    /**
     * Maps a limiting factor to an actionable remediation string. Covers every key
     * mapped in {@code getMetricCount} except the count-less {@code spacing} +
     * {@code alignment} (which have their own remediation strings).
     */
    static String getRemediation(String limitingFactor) {
        return switch (limitingFactor) {
            case "labelOverlaps" -> "Use update-view-connection to set labelPosition "
                    + "(source/middle/target) on overlapping labels, or suppress labels "
                    + "with showLabel=false";
            case "overlaps" -> "Increase spacing parameter or use layout-within-group "
                    + "to reposition overlapping elements";
            case "edgeCrossings" -> "Run optimize-group-order to reduce inter-group crossings, "
                    + "or reposition hub elements manually";
            case "passThroughs" -> "Reposition elements that connections pass through, "
                    + "or increase spacing to create routing corridors";
            case "spacing" -> "Increase the spacing parameter "
                    + "(current spacing may be too tight for element count)";
            case "alignment" -> "Use layout-within-group with consistent arrangement "
                    + "to improve alignment within groups";
            case "boundaryViolations" -> "Move children outside parent group bounds back inside, "
                    + "or grow the parent group via auto-size";
            case "parentLabelObscured" -> "Move overlapping children away from the parent group's "
                    + "name area, or grow the parent group via auto-size";
            case "offCanvas" -> "Reposition off-canvas elements onto the visible canvas via "
                    + "update-element-position, or run clean-canvas to recompute view bounds";
            case "labelTruncations" -> "Use update-view-connection to set labelPosition "
                    + "(source/middle/target) on truncated labels, or shorten the label text";
            case "interiorTerminations" -> "Re-run auto-route-connections after element repositioning — "
                    + "interior terminations indicate stored bendpoints inside element bounds, "
                    + "requiring face-aware re-routing";
            case "zigzags" -> "Re-run auto-route-connections with a higher iteration target — "
                    + "zigzag patterns indicate PathStraightener.eliminateReversals did not converge "
                    + "for this connection";
            case "connectionEdgeCoincidence" -> "Reposition the coincident-aligned element via "
                    + "update-element-position to break the parallel alignment, or re-run "
                    + "auto-route-connections to choose a different corridor";
            case "hubPortQuality" -> "Run auto-route-connections with port-distribution enabled — "
                    + "multiple connections share a single port slot on a hub face";
            case "coincidentSegments" -> "Re-route the affected connections via auto-route-connections "
                    + "to choose distinct corridors, or reposition source/target elements to break "
                    + "the parallel alignment";
            case "nonOrthogonalTerminals" -> "Re-run auto-route-connections after element repositioning, "
                    + "or use update-element-position to adjust source/target positions so terminal "
                    + "segments approach element edges orthogonally";
            default -> "Review the assess-layout ratingBreakdown for details";
        };
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

        OrthogonalRoutingResult(List<Command> commands, int routedCount,
                List<FailedConnection> failedConnections,
                List<MoveRecommendation> moveRecommendations,
                int labelsOptimized,
                Map<String, List<AbsoluteBendpointDto>> routedPaths,
                int straightLineCrossings, int egressRolledBack) {
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

        OptimizeGroupOrderPassResult(NonNotifyingCompoundCommand compound, int positionCount) {
            this.compound = compound;
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

        AutoRoutePassResult(NonNotifyingCompoundCommand compound, int routedCount,
                int labelsOptimized, boolean routerTypeSwitched) {
            this.compound = compound;
            this.routedCount = routedCount;
            this.labelsOptimized = labelsOptimized;
            this.routerTypeSwitched = routerTypeSwitched;
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
     * Result from computeLabelOptimizationPass.
     */
    private static class LabelOptimizationPassResult {
        final NonNotifyingCompoundCommand compound;
        final int labelsOptimized;
        final int trials;

        LabelOptimizationPassResult(NonNotifyingCompoundCommand compound,
                int labelsOptimized, int trials) {
            this.compound = compound;
            this.labelsOptimized = labelsOptimized;
            this.trials = trials;
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
                            null, null, null, null, null, null, null, null);
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
                    prepareUpdateViewConnectionDirect(archConn, relativeBendpoints, null, null, null, null);
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
            boolean force, List<String> warnings) {

        TerminalsOnlyResult result =
                buildTerminalsOnlyCommands(diagramModel, targetConnections, force);

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
            logger.info("B61: Switching view {} from router type {} to bendpoint mode "
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
                List.of());                     // structuredWarnings (Row E — terminals-only is autoNudge-mutex, never populated here)

        if (mutationDispatcher.isApprovalRequired(sessionId)) {
            Map<String, Object> proposedChanges = new LinkedHashMap<>();
            proposedChanges.put("strategy", effectiveStrategy);
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
                    "Terminal-segment rectification computed and ready for application.");
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
     *       unrelated element (excluding source, target, their ancestors, and their
     *       visual children), revert that connection. This catches the pass-throughs
     *       that the naive approach introduced on the dense App Collaboration view.</li>
     *   <li><b>Crossing veto</b>: for each rectified connection, compute the segment
     *       crossings of the new path vs. all other connection paths on the view and
     *       compare to the old path's crossings. If the new path crosses <em>more</em>
     *       other paths, revert that connection. This enforces the user's severity
     *       hierarchy (non-orthogonal terminals are Tier 3 cosmetic; edge crossings
     *       are Tier 2 moderate — trading one for the other is a rating regression).</li>
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
            boolean force) {

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
                // groups, notes — mirrors buildOrthogonalRoutingCommands exclusion logic)
                Set<String> excludeIds = new HashSet<>();
                excludeIds.add(srcObj.getId());
                excludeIds.add(tgtObj.getId());
                excludeIds.addAll(getAncestorIds(srcObj.getId(), nodeMap, nodes));
                excludeIds.addAll(getAncestorIds(tgtObj.getId(), nodeMap, nodes));
                excludeIds.addAll(getChildIds(srcObj.getId(), nodes));
                excludeIds.addAll(getChildIds(tgtObj.getId(), nodes));
                List<RoutingRect> obstacles = new ArrayList<>();
                for (AssessmentNode node : nodes) {
                    if (excludeIds.contains(node.id())) continue;
                    if (node.isGroup() || node.isNote()) continue;
                    obstacles.add(new RoutingRect(
                            (int) node.x(), (int) node.y(),
                            (int) node.width(), (int) node.height(), node.id()));
                }

                // Obstacle veto: reject if any segment of the new path crosses an obstacle
                if (pathCrossesObstacles(newFullPath, obstacles)) {
                    vetoedObstacle++;
                    logger.debug("B61: obstacle veto for connection {} — new path crosses "
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
                    logger.debug("B61: crossing veto for connection {} — new path would add {} "
                            + "edge crossing(s) (old={}, new={})",
                            archConn.getId(), newCross - oldCross, oldCross, newCross);
                    continue;
                }
            }

            List<BendpointDto> newRel = ConnectionResponseBuilder.convertAbsoluteToRelative(
                    newAbs, srcCX, srcCY, tgtCX, tgtCY);
            PreparedMutation<ViewConnectionDto> prepared =
                    prepareUpdateViewConnectionDirect(archConn, newRel, null, null, null, null);
            commands.add(prepared.command());
            routed++;
        }

        if (vetoedObstacle > 0 || vetoedCrossing > 0 || vetoedInterior > 0 || vetoedZigzag > 0) {
            logger.info("B61: terminals-only skipped {} connection(s) by vetoes "
                    + "(obstacle={}, crossing={}, interior={}, zigzag={}) to preserve view quality",
                    vetoedObstacle + vetoedCrossing + vetoedInterior + vetoedZigzag,
                    vetoedObstacle, vetoedCrossing, vetoedInterior, vetoedZigzag);
        }

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
     * best-of-K (row 762, Task-0 spike D3) candidate objective — the SAME
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
        Map<String, List<AbsoluteBendpointDto>> candidateBendpoints =
                new LinkedHashMap<>(candidate.routed());
        candidateBendpoints.putAll(candidate.violatedRoutes());
        List<AssessmentConnection> candidateConnections = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : batchInput) {
            double srcX = ep.source().centerX();
            double srcY = ep.source().centerY();
            double tgtX = ep.target().centerX();
            double tgtY = ep.target().centerY();
            List<double[]> pathPoints = new ArrayList<>();
            pathPoints.add(new double[]{srcX, srcY});
            List<AbsoluteBendpointDto> bps = candidateBendpoints.get(ep.connectionId());
            if (bps != null) {
                for (AbsoluteBendpointDto bp : bps) {
                    pathPoints.add(new double[]{bp.x(), bp.y()});
                }
            }
            pathPoints.add(new double[]{tgtX, tgtY});
            candidateConnections.add(new AssessmentConnection(
                    ep.connectionId(), ep.source().id(), ep.target().id(),
                    pathPoints, ep.labelText(), ep.textPosition()));
        }
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
     * Shared orthogonal routing logic used by both autoRouteConnections and
     * computeAutoRoutePass. Builds batch routing inputs,
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
            boolean force, int snapThreshold, int perimeterMargin) {
        return buildOrthogonalRoutingCommands(diagramModel, targetConnections, nodes,
                force, snapThreshold, perimeterMargin,
                VisibilityGraphRouter.DEFAULT_OCCUPANCY_WEIGHT,
                RoutingPipeline.DEFAULT_ENABLE_CHANNEL_NUDGING, false);
    }

    /**
     * Overload with configurable occupancy weight for corridor diversity boost.
     */
    private OrthogonalRoutingResult buildOrthogonalRoutingCommands(
            IArchimateDiagramModel diagramModel,
            List<IDiagramModelConnection> targetConnections,
            List<AssessmentNode> nodes,
            boolean force, int snapThreshold, int perimeterMargin,
            double occupancyWeight) {
        return buildOrthogonalRoutingCommands(diagramModel, targetConnections, nodes,
                force, snapThreshold, perimeterMargin, occupancyWeight,
                RoutingPipeline.DEFAULT_ENABLE_CHANNEL_NUDGING, false);
    }

    /**
     * Canonical overload with the channel nudging gate and the {@code emitLabelOffsets} switch.
     * {@code emitLabelOffsets} is true only for position-preserving {@code autoRouteConnections} (the
     * perpendicular "Label Offset" is the only channel that lifts a Middle label off a box when elements
     * are not moved); false for the auto-layout path, which keeps offsets in {@code executeLabelFallback}.
     */
    private OrthogonalRoutingResult buildOrthogonalRoutingCommands(
            IArchimateDiagramModel diagramModel,
            List<IDiagramModelConnection> targetConnections,
            List<AssessmentNode> nodes,
            boolean force, int snapThreshold, int perimeterMargin,
            double occupancyWeight, boolean enableChannelNudging,
            boolean emitLabelOffsets) {

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

            // Build obstacle list: exclude source, target, their ancestors, their visual children,
            // and all groups (transparent containers) (Pattern 2 fix)
            Set<String> excludeIds = new HashSet<>();
            excludeIds.add(srcNode.id());
            excludeIds.add(tgtNode.id());
            excludeIds.addAll(getAncestorIds(srcNode.id(), nodeMap, nodes));
            excludeIds.addAll(getAncestorIds(tgtNode.id(), nodeMap, nodes));
            excludeIds.addAll(getChildIds(srcNode.id(), nodes));
            excludeIds.addAll(getChildIds(tgtNode.id(), nodes));

            List<RoutingRect> obstacles = new ArrayList<>();
            // Build per-connection group boundaries for group-wall clearance cost.
            // Exclude groups that are ancestors of either endpoint (connections inside a group
            // should not see their own group wall as a clearance boundary).
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
                if (node.isGroup()) {
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
                    List.of(), 0, List.of(), List.of(), 0, Map.of(), 0, 0);
        }

        // Build unified obstacle list for corridor width and neighbor collision checks.
        // Exclude groups — they are transparent containers.
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

        // Route all connections with path ordering and edge nudging.
        // best-of-K multi-start (row 762): the unchanged pipeline is invoked K
        // times over seeded-shuffled processing orderings; the best complete
        // result by the ship-gate aggregate is selected. Run 0 uses the null
        // override (≡ current main) and wins all ties ⇒ never-worse by
        // construction. The wrapper is assessor-agnostic; the genuine
        // LayoutQualityAssessor aggregate is wired here — the single model-layer
        // composition point (Task-0 spike D1/D3).
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
                    prepareUpdateViewConnectionDirect(archConn, relativeBendpoints, null, null, null, null);
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
                routingResult.straightLineCrossings(), routingResult.egressRolledBack());
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
                minimizer.optimize(groupInfos, edges, reverseSweep);

        // 5. Check if improvement was found
        if (optResult.crossingsAfter() >= optResult.crossingsBefore()) {
            return null; // No improvement — discard
        }

        // 6. Build position commands for reordered elements
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

            // Compute new positions using arrangement heuristic
            String intraArrangement = GroupLayoutCalculator.chooseIntraGroupArrangement(
                    orderedChildren.size(), direction);
            List<int[]> positions;
            switch (intraArrangement) {
            case "row":
                positions = computeRowLayout(orderedChildren, startX, startY,
                        resolvedSpacing, null, null, true);
                break;
            case "grid":
                int gridCols = GroupLayoutCalculator.computeGridColumns(orderedChildren.size());
                GroupLayoutCalculator.GridLayoutResult gridResult =
                        computeGridLayout(orderedChildren, startX, startY,
                                resolvedSpacing, resolvedPadding, 0,
                                null, null, true, gridCols);
                positions = gridResult.positions();
                break;
            default: // "column"
                positions = computeColumnLayout(orderedChildren, startX, startY,
                        resolvedSpacing, null, null, true);
                break;
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

    /**
     * Overload with configurable occupancy weight for corridor diversity boost.
     */
    AutoRoutePassResult computeAutoRoutePass(
            String viewId, IArchimateDiagramModel diagramModel,
            IArchimateModel model, double occupancyWeight) {

        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);
        List<IDiagramModelConnection> allConnections = AssessmentCollector.collectAllConnections(diagramModel);
        if (allConnections.isEmpty()) {
            return null;
        }

        // Route via shared helper (force=true for best quality during iteration)
        OrthogonalRoutingResult routeResult = buildOrthogonalRoutingCommands(
                diagramModel, allConnections, nodes, true, RoutingPipeline.DEFAULT_SNAP_THRESHOLD,
                RoutingPipeline.DEFAULT_PERIMETER_MARGIN, occupancyWeight);
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
                routeResult.labelsOptimized, routerTypeSwitched);
    }

    /**
     * Computes a standalone label optimization pass.
     * Reads current EMF state (element positions and connection bendpoints),
     * runs multi-trial label position optimization, and returns commands to
     * apply the best label positions found.
     *
     * <p>This method does NOT re-route connections — it only changes label
     * text positions via {@link SetTextPositionCommand}.</p>
     *
     * @param diagramModel the diagram to optimize labels for
     * @param model        the parent ArchiMate model
     * @param trials       number of optimization trials to run
     * @return result with compound command and optimization counts, or null if no improvements
     */
    private LabelOptimizationPassResult computeLabelOptimizationPass(
            IArchimateDiagramModel diagramModel, IArchimateModel model, int trials) {

        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);
        List<IDiagramModelConnection> allConnections =
                AssessmentCollector.collectAllConnections(diagramModel);
        if (allConnections.isEmpty()) {
            return null;
        }

        // Build node map for ancestor/child lookups
        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        // Build optimizer inputs: ConnectionEndpoints + paths from current EMF state
        List<RoutingPipeline.ConnectionEndpoints> batchInput = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> batchPaths = new ArrayList<>();
        List<IDiagramModelArchimateConnection> batchConnections = new ArrayList<>();

        for (IDiagramModelConnection conn : allConnections) {
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

            // Extract label text
            String labelText = "";
            IArchimateRelationship connRel = archConn.getArchimateRelationship();
            if (connRel != null && connRel.getName() != null) {
                labelText = connRel.getName();
            }
            if (labelText.isEmpty()) {
                continue; // skip unlabeled connections
            }

            RoutingRect srcRect = new RoutingRect(
                    (int) srcNode.x(), (int) srcNode.y(),
                    (int) srcNode.width(), (int) srcNode.height(),
                    srcNode.id());
            RoutingRect tgtRect = new RoutingRect(
                    (int) tgtNode.x(), (int) tgtNode.y(),
                    (int) tgtNode.width(), (int) tgtNode.height(),
                    tgtNode.id());

            // Read current bendpoints from EMF state (absolute coordinates in bendpoint mode)
            List<AbsoluteBendpointDto> path = new ArrayList<>();
            for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
                path.add(new AbsoluteBendpointDto(bp.getStartX(), bp.getStartY()));
            }

            batchInput.add(new RoutingPipeline.ConnectionEndpoints(
                    archConn.getId(), srcRect, tgtRect, List.of(),
                    labelText, archConn.getTextPosition()));
            batchPaths.add(path);
            batchConnections.add(archConn);
        }

        if (batchInput.isEmpty()) {
            return null;
        }

        // Build obstacle list (non-group elements)
        List<RoutingRect> allObstacles = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (!node.isGroup()) {
                allObstacles.add(new RoutingRect(
                        (int) node.x(), (int) node.y(),
                        (int) node.width(), (int) node.height(),
                        node.id()));
            }
        }

        // Build per-connection label exclusion sets
        Map<String, Set<String>> labelExcludeSets = new LinkedHashMap<>();
        for (RoutingPipeline.ConnectionEndpoints conn : batchInput) {
            Set<String> excludeIds = new HashSet<>();
            if (conn.source().id() != null) {
                excludeIds.add(conn.source().id());
                excludeIds.addAll(getAncestorIds(conn.source().id(), nodeMap, nodes));
                excludeIds.addAll(getChildIds(conn.source().id(), nodes));
            }
            if (conn.target().id() != null) {
                excludeIds.add(conn.target().id());
                excludeIds.addAll(getAncestorIds(conn.target().id(), nodeMap, nodes));
                excludeIds.addAll(getChildIds(conn.target().id(), nodes));
            }
            labelExcludeSets.put(conn.connectionId(), excludeIds);
        }

        // Run multi-trial optimization (seeded for reproducibility given same EMF state)
        LabelPositionOptimizer optimizer = new LabelPositionOptimizer();
        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                batchInput, batchPaths, allObstacles, labelExcludeSets,
                trials, new Random(batchInput.size() * 31L + allObstacles.size()));

        // Offsets are metric-neutral (own-endpoint bleed clears with NO along-path change) → need BOTH empty.
        if (result.changedPositions().isEmpty() && result.offsets().isEmpty()) {
            return null;
        }

        // Build SetTextPositionCommands for changed positions
        Map<String, IDiagramModelArchimateConnection> connLookup = new LinkedHashMap<>();
        for (IDiagramModelArchimateConnection archConn : batchConnections) {
            connLookup.put(archConn.getId(), archConn);
        }

        List<Command> commands = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : result.changedPositions().entrySet()) {
            IDiagramModelArchimateConnection conn = connLookup.get(entry.getKey());
            if (conn != null && conn.getTextPosition() != entry.getValue()) {
                commands.add(new SetTextPositionCommand(conn, entry.getValue()));
            }
        }

        // Perpendicular "Label Offset" commands (Archi 5.10+); feature-guarded → emits nothing on 5.7.
        commands.addAll(LabelOffsetSupport.buildOffsetCommands(result.offsets(), connLookup));

        if (commands.isEmpty()) {
            return null;
        }

        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand("Label optimization fallback ("
                        + commands.size() + " labels, " + trials + " trials)");
        commands.forEach(compound::add);
        return new LabelOptimizationPassResult(compound, commands.size(), trials);
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

    // ---- Auto-connect view ----

    @Override
    public MutationResult<AutoConnectResultDto> autoConnectView(
            String sessionId, String viewId,
            List<String> elementIds, List<String> relationshipTypes,
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
            // Accumulator for ancestor/descendant pairs we decline
            // to draw (they would render as self-pass-throughs). Threaded into
            // every return-site DTO below.
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

                    // skip connections between ancestor and descendant
                    // on the view (would render as a self-pass-through; assess-layout
                    // flags as M4).
                    if (isAncestorOnView(viewObject, targetViewObj)
                            || isAncestorOnView(targetViewObj, viewObject)) {
                        skippedDueToNesting.add(
                                new AutoConnectResultDto.SkippedNestingPair(
                                        viewObject.getId(), targetViewObj.getId(),
                                        rel.eClass().getName(),
                                        "ancestor_descendant_on_view"));
                        continue;
                    }

                    // Create connection
                    IDiagramModelArchimateConnection conn =
                            IArchimateFactory.eINSTANCE
                                    .createDiagramModelArchimateConnection();
                    conn.setArchimateRelationship(rel);
                    if (showLabel != null) {
                        conn.setNameVisible(showLabel);
                    }
                    // Apply styling (G5)
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
                        // G5: typography composite + lineStyle bitmask.
                        StylingHelper.applyConnectionStyling(conn, styling);
                    }
                    commands.add(new AddConnectionToViewCommand(
                            conn, viewObject, targetViewObj));
                    connectedRelationshipIds.add(relId);
                }

                // Scan target relationships
                for (IArchimateRelationship rel : element.getTargetRelationships()) {
                    // skip orphaned relationships (not in containment tree)
                    if (rel.eContainer() == null) continue;

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

                    // skip connections between ancestor and descendant
                    // on the view (would render as a self-pass-through; assess-layout
                    // flags as M4). Here viewObject is the relationship TARGET and
                    // sourceViewObj is the relationship SOURCE — note the role swap
                    // when populating the skipped-pair record.
                    if (isAncestorOnView(viewObject, sourceViewObj)
                            || isAncestorOnView(sourceViewObj, viewObject)) {
                        skippedDueToNesting.add(
                                new AutoConnectResultDto.SkippedNestingPair(
                                        sourceViewObj.getId(), viewObject.getId(),
                                        rel.eClass().getName(),
                                        "ancestor_descendant_on_view"));
                        continue;
                    }

                    // Create connection
                    IDiagramModelArchimateConnection conn =
                            IArchimateFactory.eINSTANCE
                                    .createDiagramModelArchimateConnection();
                    conn.setArchimateRelationship(rel);
                    if (showLabel != null) {
                        conn.setNameVisible(showLabel);
                    }
                    // Apply styling (G5)
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
                        // G5: typography composite + lineStyle bitmask.
                        StylingHelper.applyConnectionStyling(conn, styling);
                    }
                    commands.add(new AddConnectionToViewCommand(
                            conn, sourceViewObj, viewObject));
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
                proposedChanges.put("skippedDueToNesting", skippedDueToNesting.size());
                ProposalContext ctx = storeAsProposal(sessionId,
                        "auto-connect-view",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId), dto, label,
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
     * Gets all direct visual child IDs nested inside a parent element.
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
            // Refactored 2026-05-15 under
            // Task 3.5a per architecture-spec § 1.10 O1 — extraction makes
            // the same merged-compound-building logic reusable inside the
            // new SpacingControlLoop callbacks without duplicating ~200 LOC
            // of intricate routing/overflow orchestration. Existing public
            // contract preserved verbatim (single dispatchOrQueue at step 12,
            // same DTO shape).
            ComputeAdjustViewSpacingResult helper = computeAdjustViewSpacing(
                    viewId, model, interElementDelta, paddingDelta,
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
                    helper.defaultResolutionReason());

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
     * <p>Refactored out of {@link #adjustViewSpacing} 2026-05-15 under
     * Task 3.5a per architecture-spec § 1.10 O1.</p>
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
            String defaultResolutionReason) {
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
     * <p>Refactored out of {@link #adjustViewSpacing} 2026-05-15 under
     * Task 3.5a per architecture-spec § 1.10 O1.</p>
     */
    private ComputeAdjustViewSpacingResult computeAdjustViewSpacing(
            String viewId, IArchimateModel model,
            Integer interElementDelta, Integer paddingDelta,
            Integer interGroupDelta, boolean recursive)
            throws MutationException {

        // 1. Validate view
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
            throw new ModelAccessException(
                    "View not found: " + viewId, ErrorCode.VIEW_NOT_FOUND);
        }

        // 2. Flat-view guard: require groups
        List<IDiagramModelGroup> topLevelGroups = new ArrayList<>();
        for (IDiagramModelObject child : diagramModel.getChildren()) {
            if (child instanceof IDiagramModelGroup group
                    && !group.getChildren().isEmpty()) {
                topLevelGroups.add(group);
            }
        }
        if (topLevelGroups.isEmpty()) {
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
            boolean triggerHasLargeHubs = triggerHubResult.elements().stream()
                    .anyMatch(e -> e.connectionCount() > 6);
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

        // 3b. Short-circuit when all deltas are zero (F5 fix). Return a
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
                    defaultResolutionReason);
        }

        // 3c. Capture crossings before inflation (F3 fix)
        AssessLayoutResultDto beforeAssessment = assessLayout(viewId);
        int crossingsBefore = beforeAssessment.edgeCrossingCount();

        // 4. Build compound command for all spacing changes
        NonNotifyingCompoundCommand spacingCompound =
                new NonNotifyingCompoundCommand("Adjust view spacing");
        int elementsRepositioned = 0;
        int groupsAdjusted = 0;

        // 5. For each top-level group: detect arrangement, inflate spacing/padding
        for (IDiagramModelGroup group : topLevelGroups) {
            int repositioned = inflateGroupSpacing(group, elementDelta, padDelta,
                    recursive, spacingCompound, 0);
            if (repositioned > 0) {
                elementsRepositioned += repositioned;
                groupsAdjusted++;
            }
        }

        // 6. Inter-group shifts
        if (groupDelta != 0 && topLevelGroups.size() > 1) {
            List<Command> pendingCommands = new ArrayList<>();
            for (Object cmd : spacingCompound.getCommands()) {
                pendingCommands.add((Command) cmd);
            }

            List<int[]> groupPositions = new ArrayList<>();
            for (IDiagramModelGroup group : topLevelGroups) {
                IBounds b = group.getBounds();
                int[] pendingDims = findPendingDimensions(pendingCommands, group);
                int w = (pendingDims != null) ? pendingDims[0] : b.getWidth();
                int h = (pendingDims != null) ? pendingDims[1] : b.getHeight();
                groupPositions.add(new int[]{b.getX(), b.getY(), w, h});
            }

            List<int[]> shifted = GroupLayoutCalculator.computeInterGroupShifts(
                    groupPositions, groupDelta);

            for (int i = 0; i < topLevelGroups.size(); i++) {
                IDiagramModelGroup group = topLevelGroups.get(i);
                int[] newPos = shifted.get(i);
                IBounds b = group.getBounds();
                if (newPos[0] != b.getX() || newPos[1] != b.getY()) {
                    int[] pendingDims = findPendingDimensions(pendingCommands, group);
                    int w = (pendingDims != null) ? pendingDims[0] : b.getWidth();
                    int h = (pendingDims != null) ? pendingDims[1] : b.getHeight();
                    spacingCompound.add(new UpdateViewObjectCommand(group,
                            newPos[0], newPos[1], w, h));
                }
            }
        }

        // 7. Dispatch spacing temporarily so routing sees updated positions
        //
        // SAFETY (Decision-A.1 / Session 7, root-cause fix for
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
            // 8. Compute routing pass (builds compound without dispatching)
            routeResult = computeAutoRoutePass(viewId, diagramModel, model);
            if (routeResult != null) {
                mutationDispatcher.dispatchImmediate(routeResult.compound);
                undoCount++;
                connectionsRouted = routeResult.routedCount;
            }

            // 9. Assess layout (on the temporarily applied state)
            assessment = assessLayout(viewId);

            // 9b. Post-spacing/routing overflow-detection pass (Successor E.b).
            Map<String, int[]> virtualGroupBounds = new LinkedHashMap<>();
            Map<String, IDiagramModelObject> allViewObjects = new LinkedHashMap<>();
            collectAllViewObjectMap(diagramModel, allViewObjects);
            for (IDiagramModelObject dmo : allViewObjects.values()) {
                EObject container = dmo.eContainer();
                if (!(container instanceof IDiagramModelGroup parentGroup)) {
                    continue;
                }
                IBounds bounds = dmo.getBounds();
                resizeParentGroupIfNeeded(parentGroup, dmo,
                        bounds.getX(), bounds.getY(),
                        bounds.getWidth(), bounds.getHeight(),
                        virtualGroupBounds, groupResizeCommands);
            }
        } finally {
            // 10. Undo temporary dispatches — guaranteed even on exception.
            mutationDispatcher.undo(undoCount);
        }

        // 11. Merge all commands into one compound for single undo
        NonNotifyingCompoundCommand mergedCompound =
                new NonNotifyingCompoundCommand("Adjust view spacing");
        for (Object cmd : spacingCompound.getCommands()) {
            mergedCompound.add((Command) cmd);
        }
        if (routeResult != null) {
            for (Object cmd : routeResult.compound.getCommands()) {
                mergedCompound.add((Command) cmd);
            }
        }
        for (Command cmd : groupResizeCommands.values()) {
            mergedCompound.add(cmd);
        }

        return new ComputeAdjustViewSpacingResult(
                mergedCompound,
                assessment,
                crossingsBefore,
                groupsAdjusted,
                elementsRepositioned,
                connectionsRouted,
                connectionsFailed,
                resolvedInterElementDelta,
                defaultResolutionReason);
    }

    // ---- Apply element spacing recommendations (RoutingPreconditions.InterElement) ----

    /**
     * Default per-tool iteration budget for the element-spacing convenience
     * tool's embedded control loop (architecture-spec § 1.4 / HALT 0.1
     * Q2=A). Arm A manual agents converged in 5-8 tool calls per row 701
     * 2026-05-15 trajectory data.
     */
    private static final int DEFAULT_ELEMENT_ITERATION_BUDGET = 5;

    @Override
    public MutationResult<ApplyElementSpacingRecommendationsResultDto>
            applyElementSpacingRecommendations(
                    String sessionId, String viewId,
                    boolean dryRun, Integer targetSpacingOverride) {
        // Backwards-compat: delegate to the 5-arg control-loop entry point
        // with the per-tool default iteration budget. Single source of truth
        // for the control-loop body — existing callers (4-arg) get the
        // redesigned behaviour automatically. Refactored 2026-05-15 under
        // Task 3.5 per architecture-spec § 1.10 O6 (default-interface-method
        // + canonical-impl-override strategy).
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

            List<IDiagramModelGroup> topLevelGroups = new ArrayList<>();
            for (IDiagramModelObject child : diagramModel.getChildren()) {
                if (child instanceof IDiagramModelGroup group
                        && !group.getChildren().isEmpty()) {
                    topLevelGroups.add(group);
                }
            }
            PerGroupSpacingScan scan = scanPerGroupSpacing(topLevelGroups);

            DetectHubElementsResultDto hubResult = detectHubElements(viewId);
            boolean hasLargeHubs = hubResult.elements().stream()
                    .anyMatch(e -> e.connectionCount() > 6);

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
                            targetSpacingOverride != null);

            // 6. Short-circuit / dryRun branches. Build
            //    envelope with terminationReason taxonomy + return without
            //    entering the loop.
            if (!decision.shouldCallAdjustViewSpacing()) {
                String terminationReason = mapEntryGuardToTerminationReason(
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

            // Fix-1 (RC-1) — route-normalize the baseline so it is measured
            // on the same routing basis as every per-step postState
            // (Decision-A.1.3 = α''', Session 11, Task 10.5). The
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
            // certificate (Lever B).
            // Sibling-symmetric with the rnb.degraded() short-circuit
            // above: a pure pre-loop test ⇒ DTO-return WITHOUT entering the
            // loop. Zero false-positives by construction ⇒ cannot
            // reflow-claim-while-below-regime;
            // SpacingControlLoop is byte-UNTOUCHED. Element-arm site of the
            // ONE shared arm-agnostic precheck (group + composer siblings).
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
                    /*hubExtent=*/ captureHubExtent(viewId));

            // Closure-state: track the most-recent built command so
            // observeLayout() can return its cached postMetrics (avoids
            // an extra assessLayout call per iteration — the helper has
            // already captured it during its temp-apply + assess + undo
            // dance per architecture-spec § 1.6).
            final GefSpacingMutationCommand[] lastBuilt =
                    new GefSpacingMutationCommand[1];
            SpacingControlLoop.Callbacks callbacks =
                    new SpacingControlLoop.Callbacks() {
                @Override
                public SpacingMutationCommand buildMutationCommand(
                        int proposedDeltaPx) {
                    try {
                        ComputeAdjustViewSpacingResult helper =
                                computeAdjustViewSpacing(viewId, model,
                                        /*interElementDelta=*/ proposedDeltaPx,
                                        /*paddingDelta=*/ null,
                                        /*interGroupDelta=*/ null,
                                        /*recursive=*/ true);
                        if (helper.mergedCompound() == null) {
                            // No-op delta (ladder reached zero-headroom).
                            lastBuilt[0] = null;
                            return null;
                        }
                        LayoutMetrics post = toLayoutMetrics(helper.assessment());
                        lastBuilt[0] = new GefSpacingMutationCommand(
                                helper.mergedCompound(), post);
                        return lastBuilt[0];
                    } catch (MutationException e) {
                        throw new RuntimeException(
                                "Control-loop iteration failed during "
                                + "mutation-command construction", e);
                    } catch (RuntimeException e) {
                        // GRACEFUL DEGRADATION (Decision-A.1 / Session 7,
                        // sibling-symmetric with `applyGroupSpacingRecommendations`
                        // + composer): if the helper throws an unexpected
                        // RuntimeException (e.g., NPE from the routing
                        // pipeline on a degenerate post-hub-resize state, or
                        // an IllegalStateException from the assessor), do
                        // NOT propagate as INTERNAL_ERROR. The helper's
                        // try-finally (this method, lines 7381+) has
                        // already restored the model to its pre-iteration
                        // state, so returning null signals the
                        // SpacingControlLoop to terminate with
                        // budget-exhausted-after-N preserving any earlier
                        // accepted iterations. Logged at WARN so the
                        // underlying issue is still observable, but the
                        // tool returns a usable response instead of
                        // INTERNAL_ERROR. Pinned by
                        // `SpacingControlLoopPartialCommitRegressionTest`
                        // graceful-degradation assertion.
                        logger.warn("Control-loop element-spacing iteration "
                                + "failed during mutation-command construction "
                                + "(delta={}, viewId={}); terminating loop "
                                + "with budget-exhausted to preserve prior "
                                + "accepted iterations",
                                proposedDeltaPx, viewId, e);
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
                        appliedDeltas.size());
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
                            loopResult.densityDiagnosis());
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
     */
    private AdjustViewSpacingResultDto synthesizeAdjustResultForControlLoop(
            String viewId, int finalDelta,
            AssessLayoutResultDto before, AssessLayoutResultDto after,
            int iterationCount) {
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
                        + iterationCount + "_iterations");
    }

    // ---- Apply group spacing recommendations (RoutingPreconditions.InterGroup) ----

    /**
     * Default per-tool iteration budget for the group-spacing convenience
     * tool's embedded control loop (architecture-spec § 1.4 / HALT 0.1
     * Q2=A). Sibling-symmetric with the element-spacing default.
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

            List<IDiagramModelGroup> topLevelGroups = new ArrayList<>();
            for (IDiagramModelObject child : diagramModel.getChildren()) {
                if (child instanceof IDiagramModelGroup group) {
                    topLevelGroups.add(group);
                }
            }
            int interGroupConnectionCount = countInterGroupConnections(
                    diagramModel);
            boolean isConnected = interGroupConnectionCount > 0;
            int currentSpacingPx = detectCurrentInterGroupSpacing(topLevelGroups);

            DetectHubElementsResultDto hubResult = detectHubElements(viewId);
            boolean hasLargeHubs = hubResult.elements().stream()
                    .anyMatch(e -> e.connectionCount() > 6);

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
                            targetSpacingOverride != null);

            // 6. Short-circuit / dryRun branches.
            if (!decision.shouldCallAdjustViewSpacing()) {
                String terminationReason = mapEntryGuardToTerminationReason(
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

            // Fix-1 (RC-1) — sibling-symmetric with the element-spacing
            // closure (Decision-A.1.3 = α''', Session 11, Task 10.5).
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
            // siblings). Sibling-symmetric with rnb.degraded() above;
            // SpacingControlLoop byte-UNTOUCHED; dissolved by soundness.
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
                    /*hubExtent=*/ captureHubExtent(viewId));

            final GefSpacingMutationCommand[] lastBuilt =
                    new GefSpacingMutationCommand[1];
            SpacingControlLoop.Callbacks callbacks =
                    new SpacingControlLoop.Callbacks() {
                @Override
                public SpacingMutationCommand buildMutationCommand(
                        int proposedDeltaPx) {
                    try {
                        ComputeAdjustViewSpacingResult helper =
                                computeAdjustViewSpacing(viewId, model,
                                        /*interElementDelta=*/ null,
                                        /*paddingDelta=*/ null,
                                        /*interGroupDelta=*/ proposedDeltaPx,
                                        /*recursive=*/ true);
                        if (helper.mergedCompound() == null) {
                            lastBuilt[0] = null;
                            return null;
                        }
                        LayoutMetrics post = toLayoutMetrics(helper.assessment());
                        lastBuilt[0] = new GefSpacingMutationCommand(
                                helper.mergedCompound(), post);
                        return lastBuilt[0];
                    } catch (MutationException e) {
                        throw new RuntimeException(
                                "Control-loop iteration failed during "
                                + "mutation-command construction", e);
                    } catch (RuntimeException e) {
                        // Sibling-symmetric graceful degradation with
                        // applyElementSpacingRecommendations 5-arg closure
                        // above (Decision-A.1 / Session 7).
                        logger.warn("Control-loop group-spacing iteration "
                                + "failed during mutation-command construction "
                                + "(delta={}, viewId={}); terminating loop "
                                + "with budget-exhausted to preserve prior "
                                + "accepted iterations",
                                proposedDeltaPx, viewId, e);
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
                        appliedDeltas.size());
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
                            loopResult.densityDiagnosis());
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
     * HALT 0.1 Q2=A). Composer iterates BOTH element + group arms, so the
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
        // Fix-1 —
        // verify-patch-loaded sentinel (Session-7 lesson: a 0/N empirical
        // with "zero improvement" is otherwise indistinguishable from
        // "plugin not reloaded"; grep this line in the live Archi log
        // post-restart before trusting any reproduction result). NOT a
        // patch — instrumentation only; no behaviour change.
        logger.info("density-fixes PATCH-0 (Fix-1 instrumentation) loaded — "
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

            List<IDiagramModelGroup> nonEmptyTopLevelGroups = new ArrayList<>();
            List<IDiagramModelGroup> allTopLevelGroups = new ArrayList<>();
            for (IDiagramModelObject child : diagramModel.getChildren()) {
                if (child instanceof IDiagramModelGroup group) {
                    allTopLevelGroups.add(group);
                    if (!group.getChildren().isEmpty()) {
                        nonEmptyTopLevelGroups.add(group);
                    }
                }
            }
            PerGroupSpacingScan elementScan =
                    scanPerGroupSpacing(nonEmptyTopLevelGroups);
            int interGroupConnectionCount =
                    countInterGroupConnections(diagramModel);
            boolean isConnected = interGroupConnectionCount > 0;
            int currentGroupSpacingPx =
                    detectCurrentInterGroupSpacing(allTopLevelGroups);

            DetectHubElementsResultDto hubResult = detectHubElements(viewId);
            boolean hasLargeHubs = !hubResult.elements().isEmpty();

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
                    groupTargetSpacingOverride != null);

            // 6. Short-circuit / dryRun branches. Composer-level
            //    short-circuit surfaces per-arm null fields (both arms idle).
            if (!decision.shouldCallAdjustViewSpacing()) {
                String terminationReason = mapEntryGuardToTerminationReason(
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
            //    per Task 0 Option α.
            ComposerArmResult elementArm = ComposerArmResult.idle();
            ComposerArmResult groupArm = ComposerArmResult.idle();

            // Element arm — fires only when delta > 0 (scope-included AND
            // current spacing < target after clamp).
            if (decision.interElementDelta() > 0) {
                elementArm = runComposerArm(
                        viewId, model,
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
                // Fix-1: this
                // replay MUST be SWT-marshalled — the accepted commands are
                // raw NonNotifyingCompoundCommands whose execute() fires
                // firePropertyChange → TreeModelView.doRefreshFromNotifications
                // → Display.getCurrent().asyncExec(); off the reactor worker
                // thread Display.getCurrent() is null → NPE → INTERNAL_ERROR
                // + partial-commit (captured stack trace 2026-05-17; the one
                // composer path the Session-9 SwtUiThreadDispatcher
                // marshalling did not cover). Extends Session-9, not a
                // re-architecture (row-774).
                ComposerSpeculativeReplay.replayForward(
                        elementArm.acceptedCommands);
                AssessLayoutResultDto groupArmInitial = assessLayout(viewId);
                try {
                    groupArm = runComposerArm(
                            viewId, model,
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
                    // compound for ONE undo entry. Fix-1: same
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
                        elementDeltas.size() + groupDeltas.size());
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
                            groupArm.densityDiagnosis);
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
            // Fix-1 —
            // capture the ACTUAL throw-site stack trace BEFORE wrapping to
            // INTERNAL_ERROR. This is the envelope-catch that produced the
            // "INFO-only / zero error-severity archi.mcp" preliminary log
            // datapoint (Session-8 signature): the composer two-arm
            // orchestration (element→group hand-off speculative execute /
            // assessLayout / dispatchOrQueue / DTO build, ~L8249–8375) and
            // the new escalate+one-shot-hub-resize path surface here with no
            // error line. Logging the throwable here is the
            // stack-trace-before-patch gate (instrumentation, NOT a fix —
            // the same ModelAccessException is still thrown unchanged).
            logger.error("apply-spacing-recommendations composer two-arm "
                    + "boundary threw (Fix-1 capture): viewId={}, scope={} — "
                    + "actual throw site follows",
                    (viewId != null ? viewId : "<null>"), resolvedScope, e);
            throw new ModelAccessException(
                    "Error applying spacing recommendations for view '"
                    + (viewId != null ? viewId : "<null>") + "'",
                    e, ErrorCode.INTERNAL_ERROR);
        } catch (Exception e) {
            logger.error("apply-spacing-recommendations composer two-arm "
                    + "boundary threw (Fix-1 capture, checked): viewId={}, "
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
         * Fix-1 (RC-1) guarded-form result — the arm's reroute pass
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
         * {@link SpacingControlLoop} byte-UNTOUCHED (the loop is never
         * entered). With both arms short-circuited, the composer builds the
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
            String viewId, IArchimateModel model,
            int initialSpacingPx, int targetSpacingPx,
            int iterationBudget, int perIterationStepCapPx,
            AssessLayoutResultDto initialAssessment,
            String arm, boolean isElementArm) {

        // Fix-1 (RC-1) — sibling-symmetric with the element + group 5-arg
        // closures (Decision-A.1.3 = α''', Session 11, Task 10.5). Route-
        // normalize this arm's baseline so it shares the per-step routing
        // basis; the guarded form surfaces a per-arm degraded reason.
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
        // SpacingControlLoop byte-UNTOUCHED; dissolved by soundness.
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
                /*hubExtent=*/ captureHubExtent(viewId));

        final GefSpacingMutationCommand[] lastBuilt =
                new GefSpacingMutationCommand[1];
        SpacingControlLoop.Callbacks callbacks =
                new SpacingControlLoop.Callbacks() {
            @Override
            public SpacingMutationCommand buildMutationCommand(
                    int proposedDeltaPx) {
                try {
                    ComputeAdjustViewSpacingResult helper =
                            computeAdjustViewSpacing(viewId, model,
                                    /*interElementDelta=*/
                                    isElementArm ? proposedDeltaPx : null,
                                    /*paddingDelta=*/ null,
                                    /*interGroupDelta=*/
                                    isElementArm ? null : proposedDeltaPx,
                                    /*recursive=*/ true);
                    if (helper.mergedCompound() == null) {
                        lastBuilt[0] = null;
                        return null;
                    }
                    LayoutMetrics post = toLayoutMetrics(helper.assessment());
                    lastBuilt[0] = new GefSpacingMutationCommand(
                            helper.mergedCompound(), post);
                    return lastBuilt[0];
                } catch (MutationException e) {
                    throw new RuntimeException(
                            "Composer arm " + arm + " failed during "
                            + "mutation-command construction", e);
                } catch (RuntimeException e) {
                    // Sibling-symmetric graceful degradation with element +
                    // group 5-arg closures (Decision-A.1 / Session 7).
                    logger.warn("Composer arm {} iteration failed during "
                            + "mutation-command construction (delta={}, "
                            + "viewId={}); terminating arm with budget-"
                            + "exhausted to preserve prior accepted iterations",
                            arm, proposedDeltaPx, viewId, e);
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
                // one-shot escalate hub-resize (Scoped Option B) —
                // sibling-symmetric with the element + group closures.
                return buildDensityHubResizeCommand(viewId, model);
            }
        };

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
     * Counts connections on the view whose source and target visual elements
     * resolve to DIFFERENT top-level groups. One-side-grouped pairings (one
     * endpoint in a group, the other ungrouped) are NOT counted — the
     * heuristic's connected/unconnected distinction is about between-group
     * routing-corridor demand, which requires two groups. Reuses the same
     * connection enumeration as {@link AssessmentCollector#collectAllConnections}
     * for source-of-truth symmetry with {@code assessLayout}'s
     * {@code connectionCount}.
     *
     * <p>Pinned by {@code ApplyGroupSpacingRecommendationsToolTest}
     * + {@code ApplySpacingRecommendationsToolTest} (single-source-
     * of-truth reuse).</p>
     */
    private static int countInterGroupConnections(
            IArchimateDiagramModel diagramModel) {
        int count = 0;
        for (IDiagramModelConnection conn :
                AssessmentCollector.collectAllConnections(diagramModel)) {
            IDiagramModelObject source =
                    (conn.getSource() instanceof IDiagramModelObject src)
                            ? src : null;
            IDiagramModelObject target =
                    (conn.getTarget() instanceof IDiagramModelObject tgt)
                            ? tgt : null;
            if (source == null || target == null) continue;
            IDiagramModelGroup sourceGroup = topLevelGroupOf(source);
            IDiagramModelGroup targetGroup = topLevelGroupOf(target);
            if (sourceGroup == null || targetGroup == null) continue;
            if (sourceGroup != targetGroup) count++;
        }
        return count;
    }

    /**
     * Resolves the top-level diagram-model group ancestor of the given visual
     * object, or null if the object is not contained in any top-level group.
     * "Top-level group" = an {@link IDiagramModelGroup} whose immediate
     * {@code eContainer()} is the {@link IArchimateDiagramModel} itself
     * (not a nested group). Walks the {@code eContainer()} chain upward.
     */
    private static IDiagramModelGroup topLevelGroupOf(IDiagramModelObject obj) {
        // Walk eContainer() chain from obj outward (inner → outer). The LAST
        // (outermost) IDiagramModelGroup seen before we reach the diagram
        // model is the top-level group by definition (its immediate container
        // is the diagram model). Track the most-recent group as we walk; when
        // we hit the diagram model, return that group — the walk's last-
        // observed group is the outermost ancestor.
        EObject current = obj.eContainer();
        IDiagramModelGroup outermostAncestorGroup = null;
        while (current != null) {
            if (current instanceof IDiagramModelGroup group) {
                outermostAncestorGroup = group;
            }
            if (current instanceof IArchimateDiagramModel) {
                // The outermost group seen along the walk is the top-level
                // group; if no group was encountered, the object is ungrouped.
                return outermostAncestorGroup;
            }
            current = current.eContainer();
        }
        return null;
    }

    /**
     * Detects the current minimum inter-group spacing across the given top-
     * level groups, delegating to
     * {@link GroupLayoutCalculator#detectInterGroupSpacing(List)} for the
     * geometric measurement (single source of truth — same utility any
     * future caller of inter-group-spacing detection should use).
     *
     * <p>Returns {@link GroupLayoutCalculator#DEFAULT_DETECTED_SPACING} when
     * fewer than 2 groups (degenerate input — no inter-group concept exists);
     * the convenience tool's
     * {@link ApplyGroupSpacingDecision#decide} short-circuits this case
     * separately via the {@code hasAtLeast2TopLevelGroups} guard, so this
     * default value is never consumed in delta computation on the no-groups
     * path.</p>
     */
    private static int detectCurrentInterGroupSpacing(
            List<IDiagramModelGroup> topLevelGroups) {
        if (topLevelGroups.size() < 2) {
            return GroupLayoutCalculator.DEFAULT_DETECTED_SPACING;
        }
        List<int[]> groupRects = new ArrayList<>();
        for (IDiagramModelGroup group : topLevelGroups) {
            IBounds b = group.getBounds();
            groupRects.add(new int[]{
                    b.getX(), b.getY(), b.getWidth(), b.getHeight()});
        }
        return GroupLayoutCalculator.detectInterGroupSpacing(groupRects);
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
            List<IDiagramModelGroup> topLevelGroups) {
        int min = Integer.MAX_VALUE;
        boolean anyMultiChild = false;
        for (IDiagramModelGroup group : topLevelGroups) {
            List<int[]> childPositions = new ArrayList<>();
            for (IDiagramModelObject child : group.getChildren()) {
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
     * into nested subgroups (bottom-up). Returns count of elements repositioned.
     */
    private int inflateGroupSpacing(IDiagramModelGroup group,
            int elementDelta, int padDelta, boolean recursive,
            NonNotifyingCompoundCommand compound, int depth) {
        if (depth > MAX_RECURSIVE_RESIZE_DEPTH) return 0;

        // Collect direct children (skip notes)
        List<IDiagramModelObject> children = new ArrayList<>();
        List<IDiagramModelGroup> nestedGroups = new ArrayList<>();
        for (IDiagramModelObject child : group.getChildren()) {
            if (child instanceof IDiagramModelNote) continue;
            children.add(child);
            if (child instanceof IDiagramModelGroup nestedGroup
                    && !nestedGroup.getChildren().isEmpty()) {
                nestedGroups.add(nestedGroup);
            }
        }
        if (children.isEmpty()) return 0;

        int repositioned = 0;

        // Recurse into nested subgroups first (bottom-up) so parent resize
        // reflects children's inflated bounds
        if (recursive) {
            for (IDiagramModelGroup nested : nestedGroups) {
                repositioned += inflateGroupSpacing(nested, elementDelta, padDelta,
                        true, compound, depth + 1);
            }
        }

        // Detect current arrangement from child positions.
        // After recursion, nested groups may have been resized in the compound
        // (F2 fix) — use pending dimensions instead of stale getBounds().
        List<Command> pendingCmds = new ArrayList<>();
        for (Object cmd : compound.getCommands()) {
            pendingCmds.add((Command) cmd);
        }
        List<int[]> childPositions = new ArrayList<>();
        for (IDiagramModelObject child : children) {
            IBounds b = child.getBounds();
            int[] pendingDims = findPendingDimensions(pendingCmds, child);
            int w = (pendingDims != null) ? pendingDims[0] : b.getWidth();
            int h = (pendingDims != null) ? pendingDims[1] : b.getHeight();
            childPositions.add(new int[]{b.getX(), b.getY(), w, h});
        }
        ArrangementDetector.DetectedArrangement detected =
                ArrangementDetector.detect(childPositions);

        // Detect current spacing and padding
        int currentSpacing = GroupLayoutCalculator.detectSpacingFromPositions(
                childPositions, detected.type());
        IBounds groupBounds = group.getBounds();
        int currentPadding = GroupLayoutCalculator.detectPaddingFromPositions(
                childPositions, groupBounds.getWidth(), groupBounds.getHeight());

        // Compute inflated values
        int newSpacing = Math.max(0, currentSpacing + elementDelta);
        int newPadding = Math.max(0, currentPadding + padDelta);
        int startX = newPadding;
        int startY = newPadding + GROUP_LABEL_HEIGHT;

        // Re-compute positions with inflated spacing (preserve element dimensions)
        List<int[]> newPositions;
        switch (detected.type()) {
        case "row":
            newPositions = computeRowLayout(children, startX, startY,
                    newSpacing, null, null, false);
            break;
        case "grid":
            Integer gridCols = detected.gridColumns();
            if (gridCols == null) {
                gridCols = GroupLayoutCalculator.computeGridColumns(children.size());
            }
            GroupLayoutCalculator.GridLayoutResult gridResult = computeGridLayout(
                    children, startX, startY,
                    newSpacing, newPadding, groupBounds.getWidth(),
                    null, null, false, gridCols);
            newPositions = gridResult.positions();
            break;
        default: // "column"
            newPositions = computeColumnLayout(children, startX, startY,
                    newSpacing, null, null, false);
            break;
        }

        // Build update commands for each child (position only, preserve dimensions)
        for (int i = 0; i < children.size(); i++) {
            IDiagramModelObject child = children.get(i);
            int[] pos = newPositions.get(i);
            compound.add(new UpdateViewObjectCommand(child,
                    pos[0], pos[1], pos[2], pos[3]));
            repositioned++;
        }

        // Auto-resize group to fit inflated children
        int[] groupDims = computeAutoResizeDimensions(
                newPositions, newPadding, GROUP_LABEL_HEIGHT);
        compound.add(new UpdateViewObjectCommand(group,
                groupBounds.getX(), groupBounds.getY(),
                groupDims[0], groupDims[1]));

        // Resize ancestor groups if this is a nested group (F1 fix:
        // seed with compound's existing commands so resizeAncestorGroups
        // can see the current group's resize via findPendingDimensions)
        if (depth > 0) {
            List<Command> allCommands = new ArrayList<>();
            for (Object cmd : compound.getCommands()) {
                allCommands.add((Command) cmd);
            }
            int beforeSize = allCommands.size();
            resizeAncestorGroups(group, allCommands, newPadding);
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
    /** Default padding from group edges in pixels. */
    private static final int DEFAULT_GROUP_PADDING = 10;
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
                IBounds groupBounds = containerObject.getBounds();
                int groupWidth = groupBounds.getWidth();
                GroupLayoutCalculator.GridLayoutResult gridResult = computeGridLayout(children, startX, startY,
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
                IBounds currentBounds = containerObject.getBounds();
                commands.add(new UpdateViewObjectCommand(containerObject,
                        currentBounds.getX(), currentBounds.getY(),
                        newGroupWidth, newGroupHeight));

                // 10a. Recursive auto-resize ancestor groups
                // DEFAULT — recursion stays group-ancestor-scoped.
                // When the requested container is an ArchiMate-element, the recursion no-ops
                // (ancestorsResized stays 0) — an honest no-op rather than walking element ancestors.
                if (recursive && containerObject instanceof IDiagramModelGroup groupForRecursion) {
                    ancestorsResized = resizeAncestorGroups(groupForRecursion, commands, resolvedPadding);
                }
            } else {
                // Check if children overflow the current container bounds
                IBounds currentBounds = containerObject.getBounds();
                int[] requiredDims = computeAutoResizeDimensions(
                        positions, resolvedPadding, GROUP_LABEL_HEIGHT);
                if (requiredDims[0] > currentBounds.getWidth()
                        || requiredDims[1] > currentBounds.getHeight()) {
                    overflow = true;
                    logger.debug("Children overflow container bounds: required={}x{}, actual={}x{}",
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
                        "layout-within-group",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId, groupViewObjectId), dto, label,
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

            if (normalizedCategory != null) {
                // Category-based layout: partition elements by category, lay out each section
                FlatCategoryLayoutResult catResult = computeFlatCategoryLayout(
                        topLevelElements, normalizedArrangement, normalizedCategory,
                        resolvedSpacing, resolvedPadding, columns);
                positions = catResult.positions();
                categories = catResult.categories();
                columnsUsed = catResult.columnsUsed();
                // Re-order topLevelElements to match category-sorted order
                topLevelElements = catResult.orderedElements();
            } else {
                // Simple layout: all elements in one group
                positions = computeFlatPositions(topLevelElements, normalizedArrangement,
                        resolvedSpacing, resolvedPadding, columns);
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
                            null, null, false);

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

            // 8. Build commands
            List<Command> commands = new ArrayList<>();
            for (int i = 0; i < topLevelElements.size(); i++) {
                IDiagramModelObject element = topLevelElements.get(i);
                int[] pos = positions.get(i);
                commands.add(new UpdateViewObjectCommand(element,
                        pos[0], pos[1], pos[2], pos[3]));
            }

            // Add child repositioning commands
            for (Map.Entry<Integer, List<int[]>> entry : parentChildPositions.entrySet()) {
                List<IDiagramModelObject> children = parentChildObjects.get(entry.getKey());
                List<int[]> childPositions = entry.getValue();
                for (int j = 0; j < children.size(); j++) {
                    IDiagramModelObject child = children.get(j);
                    int[] childPos = childPositions.get(j);
                    commands.add(new UpdateViewObjectCommand(child,
                            childPos[0], childPos[1], childPos[2], childPos[3]));
                }
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
                    normalizedSort, normalizedCategory, categories, columnsUsed);

            // 10. Approval gate
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("arrangement", normalizedArrangement);
                proposedChanges.put("elementsRepositioned", topLevelElements.size());
                if (childrenRepositioned > 0) proposedChanges.put("childrenRepositioned", childrenRepositioned);
                if (normalizedSort != null) proposedChanges.put("sortBy", normalizedSort);
                if (normalizedCategory != null) proposedChanges.put("categoryField", normalizedCategory);
                ProposalContext ctx = storeAsProposal(sessionId,
                        "layout-flat-view",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId), dto, label,
                        null, proposedChanges,
                        "Flat view layout computed and ready for application.");
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
                String layer = resolveLayer(element);
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
                case "layer" -> resolveLayer(element);
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
            String categoryField, int spacing, int padding, Integer columns) {

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
                sectionPositions = computeFlatRowPositions(catElements, currentX, currentY, spacing);
                allPositions.addAll(sectionPositions);
                // Next category starts below this row
                int rowMaxH = sectionPositions.stream()
                        .mapToInt(p -> p[3]).max().orElse(0);
                currentY += rowMaxH + categorySpacing;
                break;
            case "column":
                sectionPositions = computeFlatColumnPositions(catElements, currentX, currentY, spacing);
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
                        catElements, currentX, currentY, spacing, cols);
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
            int spacing, int padding, Integer columns) {
        switch (arrangement) {
        case "row":
            return computeFlatRowPositions(elements, padding, padding, spacing);
        case "column":
            return computeFlatColumnPositions(elements, padding, padding, spacing);
        case "grid":
            int cols = computeFlatGridColumns(elements, columns);
            return computeFlatGridPositions(elements, padding, padding, spacing, cols).positions();
        default:
            return computeFlatRowPositions(elements, padding, padding, spacing);
        }
    }

    /** Row layout: elements placed left-to-right, preserving current sizes. */
    private List<int[]> computeFlatRowPositions(
            List<IDiagramModelObject> elements, int startX, int startY, int spacing) {
        List<int[]> positions = new ArrayList<>();
        int currentX = startX;
        for (IDiagramModelObject element : elements) {
            IBounds bounds = element.getBounds();
            int w = bounds.getWidth();
            int h = bounds.getHeight();
            positions.add(new int[]{currentX, startY, w, h});
            currentX += w + spacing;
        }
        return positions;
    }

    /** Column layout: elements placed top-to-bottom, preserving current sizes. */
    private List<int[]> computeFlatColumnPositions(
            List<IDiagramModelObject> elements, int startX, int startY, int spacing) {
        List<int[]> positions = new ArrayList<>();
        int currentY = startY;
        for (IDiagramModelObject element : elements) {
            IBounds bounds = element.getBounds();
            int w = bounds.getWidth();
            int h = bounds.getHeight();
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

    /** Grid layout: elements in rows and columns, using max width/height for uniform cells. */
    private FlatGridResult computeFlatGridPositions(
            List<IDiagramModelObject> elements, int startX, int startY,
            int spacing, int cols) {
        // Determine max cell size for uniform grid
        int maxW = 0;
        int maxH = 0;
        for (IDiagramModelObject element : elements) {
            IBounds bounds = element.getBounds();
            maxW = Math.max(maxW, bounds.getWidth());
            maxH = Math.max(maxH, bounds.getHeight());
        }

        List<int[]> positions = new ArrayList<>();
        int currentX = startX;
        int currentY = startY;
        int col = 0;

        for (IDiagramModelObject element : elements) {
            IBounds bounds = element.getBounds();
            // Preserve actual size but use uniform grid cell spacing
            positions.add(new int[]{currentX, currentY, bounds.getWidth(), bounds.getHeight()});

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
            // Map groupId → relative child positions [x, y, w, h] for arrangement detection
            Map<String, List<int[]>> groupChildPositions = new LinkedHashMap<>();

            for (IDiagramModelGroup group : topLevelGroups) {
                String groupId = group.getId();
                groupMap.put(groupId, group);

                List<String> elementIds = new ArrayList<>();
                List<int[]> centers = new ArrayList<>();
                List<int[]> childPositions = new ArrayList<>();

                for (IDiagramModelObject child : group.getChildren()) {
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

                // Compute new positions using the resolved per-group arrangement
                List<int[]> positions;
                switch (resolvedGroupArrangement) {
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
                    GroupLayoutCalculator.GridLayoutResult gridResult = computeGridLayout(
                            orderedChildren, startX, startY,
                            resolvedSpacing, resolvedPadding, groupWidth,
                            elementWidth, elementHeight, effectiveAutoWidth,
                            resolvedGridColumns);
                    positions = gridResult.positions();
                    break;
                default:
                    positions = computeColumnLayout(orderedChildren, startX, startY,
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
                        "optimize-group-order",
                        () -> new PreparedMutation<>(compound, dto, viewId),
                        compoundTargetIds(compound, viewId), dto, label,
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
     * Delegates to {@link GroupLayoutCalculator#computeAutoWidth(String)}.
     */
    int computeAutoWidth(IDiagramModelObject child) {
        return GroupLayoutCalculator.computeAutoWidth(getDisplayName(child));
    }

    /**
     * Resolves element sizes for layout computation, applying elementWidth/elementHeight
     * overrides and autoWidth as needed.
     */
    private List<int[]> resolveElementSizes(List<IDiagramModelObject> children,
            Integer elementWidth, Integer elementHeight, boolean autoWidth) {
        List<int[]> sizes = new ArrayList<>();
        for (IDiagramModelObject child : children) {
            IBounds bounds = child.getBounds();
            int w = (elementWidth != null) ? elementWidth
                    : autoWidth ? computeAutoWidth(child)
                    : bounds.getWidth();
            int h = (elementHeight != null) ? elementHeight : bounds.getHeight();
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
            boolean autoWidth) {
        List<int[]> sizes = resolveElementSizes(children, elementWidth, elementHeight, autoWidth);
        return GroupLayoutCalculator.computeRowLayout(sizes, startX, startY, spacing);
    }

    /**
     * Computes column arrangement positions (top-to-bottom).
     * Delegates to {@link GroupLayoutCalculator#computeColumnLayout}.
     */
    private List<int[]> computeColumnLayout(List<IDiagramModelObject> children,
            int startX, int startY, int spacing,
            Integer elementWidth, Integer elementHeight,
            boolean autoWidth) {
        List<int[]> sizes = resolveElementSizes(children, elementWidth, elementHeight, autoWidth);
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
            boolean autoWidth, Integer columns) {
        List<int[]> sizes = resolveElementSizes(children, elementWidth, elementHeight, autoWidth);
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

    // ---- Arrange Groups ----

    // The static spacing fallback for the omitted-spacing path lives on
    // {@link ArrangeGroupsDefaultResolutionDecision#DEFAULT_ARRANGE_GROUPS_SPACING}
    // — the decision record is the single source of truth so JUnit pins +
    // production code stay in lockstep.
    private static final int ARRANGE_GROUPS_ORIGIN = 20;
    private static final int ARRANGE_GROUPS_ESTIMATED_CANVAS_WIDTH = 1200;

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
                // Build adjacency weight matrix from view connections
                Map<String, String> elementToGroup = new HashMap<>();
                for (IDiagramModelGroup group : targetGroups) {
                    mapElementsToGroup(group, group.getId(), elementToGroup);
                }

                Map<String, Map<String, Integer>> weights = new HashMap<>();
                for (IDiagramModelObject child : view.getChildren()) {
                    collectConnectionWeights(child, elementToGroup, weights);
                }

                List<String> groupIds2 = new ArrayList<>();
                for (IDiagramModelGroup g : targetGroups) {
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
                Map<String, IDiagramModelGroup> groupById = new LinkedHashMap<>();
                for (IDiagramModelGroup g : targetGroups) {
                    groupById.put(g.getId(), g);
                }
                List<IDiagramModelGroup> reordered = new ArrayList<>();
                for (String id : orderedIds) {
                    IDiagramModelGroup g = groupById.get(id);
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
                            view.getChildren(), targetGroups, elementToGroup);
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
            //     connection (Q4=(b) Model B trigger), derive a heuristic-
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
                // Compute inter-group connection state via the same primitives
                // the topology block uses (single source of truth).
                Map<String, String> defaultResolutionElementToGroup =
                        new HashMap<>();
                for (IDiagramModelGroup g : targetGroups) {
                    mapElementsToGroup(g, g.getId(),
                            defaultResolutionElementToGroup);
                }
                Map<String, Map<String, Integer>> defaultResolutionWeights =
                        new HashMap<>();
                for (IDiagramModelObject child : view.getChildren()) {
                    collectConnectionWeights(child,
                            defaultResolutionElementToGroup,
                            defaultResolutionWeights);
                }
                int interGroupConnectionCount =
                        sumInterGroupConnections(defaultResolutionWeights);
                boolean isConnected = interGroupConnectionCount > 0;
                AssessLayoutResultDto triggerAssessment = assessLayout(viewId);
                // Row C: derive hasLargeHubs upstream — reuses the canonical
                // detect-hub-elements path. Sibling-symmetric with the
                // adjustViewSpacing density-aware-default + the convenience
                // tool accessors.
                DetectHubElementsResultDto triggerHubResult =
                        detectHubElements(viewId);
                boolean triggerHasLargeHubs = triggerHubResult.elements().stream()
                        .anyMatch(e -> e.connectionCount() > 6);
                ArrangeGroupsDefaultResolutionDecision decision =
                        ArrangeGroupsDefaultResolutionDecision.decide(
                                /*callerProvidedSpacing=*/ null,
                                triggerAssessment.connectionCount(),
                                interGroupConnectionCount,
                                isConnected,
                                targetGroups.size() >= 2,
                                triggerHasLargeHubs);
                resolvedSpacing = decision.resolvedSpacing();
                defaultResolutionReason = decision.reason();
            }

            // 7. Compute positions based on arrangement
            int startX = ARRANGE_GROUPS_ORIGIN;
            int startY = ARRANGE_GROUPS_ORIGIN;
            List<int[]> positions; // [x, y] per group (preserve existing width/height)
            Integer columnsUsed = null;
            int layoutWidth = 0;
            int layoutHeight = 0;

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

            switch (normalizedArrangement) {
            case "row": {
                positions = new ArrayList<>();
                int curX = startX;
                int maxH = 0;
                int n = targetGroups.size();
                for (int i = 0; i < n; i++) {
                    IDiagramModelGroup g = targetGroups.get(i);
                    IBounds b = g.getBounds();
                    positions.add(new int[]{curX, startY});
                    curX += b.getWidth();
                    if (i < n - 1) {
                        int laneSize = (i < laneSizes.size()) ? laneSizes.get(i) : 0;
                        curX += (laneSize > 0) ? laneSize : resolvedSpacing;
                    }
                    maxH = Math.max(maxH, b.getHeight());
                }
                layoutWidth = curX;
                layoutHeight = startY + maxH;
                break;
            }
            case "column": {
                positions = new ArrayList<>();
                int curY = startY;
                int maxW = 0;
                int n = targetGroups.size();
                for (int i = 0; i < n; i++) {
                    IDiagramModelGroup g = targetGroups.get(i);
                    IBounds b = g.getBounds();
                    positions.add(new int[]{startX, curY});
                    curY += b.getHeight();
                    if (i < n - 1) {
                        int laneSize = (i < laneSizes.size()) ? laneSizes.get(i) : 0;
                        curY += (laneSize > 0) ? laneSize : resolvedSpacing;
                    }
                    maxW = Math.max(maxW, b.getWidth());
                }
                layoutWidth = startX + maxW;
                layoutHeight = curY;
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

            // 8b. Place each qualifier centred in its assigned inter-group lane.
            // Shared with computeGroupedLayoutPass via ArrangeGroupsStandaloneLane.placeQualifiers
            // (Task 1.1 — single source of truth, prevents drift).
            int standaloneElementsPlaced = 0;
            if ((rowLane || colLane) && !gapAssignments.isEmpty()) {
                List<int[]> groupDims = new ArrayList<>();
                for (IDiagramModelGroup g : targetGroups) {
                    IBounds gb = g.getBounds();
                    groupDims.add(new int[]{gb.getWidth(), gb.getHeight()});
                }
                List<ArrangeGroupsStandaloneLane.QualifierPlacement> placements =
                        ArrangeGroupsStandaloneLane.placeQualifiers(
                                gapAssignments, targetGroups.size(),
                                positions, groupDims, resolvedSpacing, rowLane);
                for (ArrangeGroupsStandaloneLane.QualifierPlacement p : placements) {
                    commands.add(new UpdateViewObjectCommand(p.element(),
                            p.x(), p.y(), p.width(), p.height()));
                    standaloneElementsPlaced++;
                }
            }

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

            ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                    viewId, targetGroups.size(), layoutWidth, layoutHeight,
                    columnsUsed, reportedArrangement,
                    resolvedSpacing, defaultResolutionReason,
                    standaloneElementsPlaced);

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

    // ---- Topology arrangement helpers (Tech Spec 13-2) ----

    /**
     * Recursively maps all elements within a group (and nested groups) to their
     * top-level group ID for connection weight counting.
     */
    private void mapElementsToGroup(IDiagramModelObject container, String topLevelGroupId,
                                     Map<String, String> elementToGroup) {
        if (container instanceof IDiagramModelContainer modelContainer) {
            for (IDiagramModelObject child : modelContainer.getChildren()) {
                elementToGroup.put(child.getId(), topLevelGroupId);
                mapElementsToGroup(child, topLevelGroupId, elementToGroup);
            }
        }
    }

    /**
     * Sums all inter-group connection weights from a pre-built weights map
     * (the per-pair adjacency map produced by {@link #collectConnectionWeights}).
     * Used by the density-aware default-resolution path inside
     * {@code arrangeGroups} to derive {@code interGroupConnectionCount}.
     */
    private static int sumInterGroupConnections(
            Map<String, Map<String, Integer>> weights) {
        int total = 0;
        for (Map<String, Integer> targetMap : weights.values()) {
            for (int count : targetMap.values()) {
                total += count;
            }
        }
        return total;
    }

    /**
     * Collects connection weights between groups by traversing view connections.
     * For each connection, if source and target are in different groups, increments
     * the weight for that group pair.
     */
    private void collectConnectionWeights(IDiagramModelObject obj,
                                           Map<String, String> elementToGroup,
                                           Map<String, Map<String, Integer>> weights) {
        // Check outgoing connections from this object
        if (obj instanceof IConnectable connectable) {
            for (IDiagramModelConnection conn : connectable.getSourceConnections()) {
                String sourceGroup = elementToGroup.get(conn.getSource().getId());
                String targetGroup = elementToGroup.get(conn.getTarget().getId());
                if (sourceGroup != null && targetGroup != null && !sourceGroup.equals(targetGroup)) {
                    weights.computeIfAbsent(sourceGroup, k -> new HashMap<>())
                            .merge(targetGroup, 1, Integer::sum);
                }
            }
        }
        // Recurse into children
        if (obj instanceof IDiagramModelContainer container) {
            for (IDiagramModelObject child : container.getChildren()) {
                collectConnectionWeights(child, elementToGroup, weights);
            }
        }
    }

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
                String description = "Delete view: " + dto.name();
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("viewId", viewId);
                ProposalContext ctx = storeAsProposal(sessionId, "delete-view",
                        () -> prepareDeleteView(viewId),
                        targetIds(viewId), prepared.entity(), description,
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
                        () -> prepareDeleteFolder(folderId, force),
                        targetIds(folderId), prepared.entity(), description,
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
        // element.getPrimaryProfile(). Mirrors prepareCreateRelationship pattern. (C3b H1)
        ElementDto dto = (dtoSpecialization != null)
                ? DtoMapper.buildElementDtoWithSpecialization(element, dtoSpecialization, resolveLayer(element))
                : convertToElementDto(element);

        return new PreparedMutation<>(cmd, dto, element.getId(), element);
    }

    /**
     * Prepares a create-relationship mutation by looking up source/target by ID.
     */
    private PreparedMutation<RelationshipDto> prepareCreateRelationship(
            String type, String sourceId, String targetId, String name, String specialization,
            RelationshipSemanticAttributes attrs) {
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

        return prepareCreateRelationship(type, relClass, sourceElement, targetElement, name, specialization, attrs, model);
    }

    /**
     * Prepares a create-relationship mutation with direct element references.
     * Used by bulk executor when source/target are back-referenced
     * (not yet in the model).
     */
    private PreparedMutation<RelationshipDto> prepareCreateRelationshipDirect(
            String type, IArchimateElement sourceElement, IArchimateElement targetElement,
            String name, String specialization, RelationshipSemanticAttributes attrs) {
        IArchimateModel model = requireAndCaptureModel();
        EClass relClass = resolveRelationshipType(type);
        return prepareCreateRelationship(type, relClass, sourceElement, targetElement, name, specialization, attrs, model);
    }

    /**
     * Shared implementation for relationship preparation.
     */
    private PreparedMutation<RelationshipDto> prepareCreateRelationship(
            String type, EClass relClass, IArchimateElement sourceElement,
            IArchimateElement targetElement, String name, String specialization,
            RelationshipSemanticAttributes attrs, IArchimateModel model) {

        // G1: validate semantic attributes against the resolved relationship class
        // BEFORE creating the EMF object — intentionally runs BEFORE ArchiMate spec-validity
        // (isValidRelationship) AND BEFORE duplicate-detection. Rationale: a misapplied G1 attr
        // (e.g., accessType on CompositionRelationship) is the user's clearest signal that the
        // request is wrong; failing fast with INVALID_PARAMETER beats a spec-validity error
        // that obscures the type mismatch. This is intentional and stricter than specified.
        validateSemanticAttributesForCreate(attrs, relClass);

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
        // an unspecialized FlowRelationship between the same elements are NOT duplicates. (C3b H2)
        Optional<IArchimateRelationship> existing = findDuplicateRelationship(
                relClass, sourceElement, targetElement, specialization);
        if (existing.isPresent()) {
            IArchimateRelationship existingRel = existing.get();
            RelationshipDto base = convertToRelationshipDto(existingRel);
            // Preserve the existing relationship's specialization + G1 semantic attributes
            // in the response DTO (the 6-arg convenience constructor would drop them).
            // (H2 + G1)
            RelationshipDto dto = new RelationshipDto(
                    base.id(), base.name(), base.type(),
                    base.specialization(),
                    base.sourceId(), base.targetId(),
                    true, null, null, null, null,
                    base.accessType(), base.associationDirected(), base.influenceStrength());
            // No-op command: nothing to execute on the command stack
            return new PreparedMutation<>(new NoOpCommand(), dto, existingRel.getId(), existingRel);
        }

        IArchimateRelationship relationship =
                (IArchimateRelationship) IArchimateFactory.eINSTANCE.create(relClass);
        if (name != null && !name.isBlank()) {
            relationship.setName(InputValidation.reject(name, "name"));
        }
        // G1: apply semantic attributes to the EMF relationship BEFORE connect().
        // The typed setters (setAccessType / setDirected / setStrength) work the moment the
        // object exists; connect() only wires source/target cross-refs.
        applyG1AttributesToRelationship(relationship, attrs);
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
        // Populate G1 semantic attributes from the EMF object (which we already mutated above) for
        // the matching subtype; populate as `null` otherwise (G1).
        // Carry the source/target NAMES from the elements in hand (the relationship's own
        // getSource()/getTarget() are null pre-connect) so the approval-card effect text can
        // name the endpoints instead of degrading to ids.
        RelationshipDto dto = new RelationshipDto(
                relationship.getId(), relationship.getName(), relationship.eClass().getName(),
                dtoSpecialization,
                sourceElement.getId(), targetElement.getId(),
                false, null, null,
                sourceElement.getName(), targetElement.getName(),
                accessTypeForDto(relationship),
                associationDirectedForDto(relationship),
                influenceStrengthForDto(relationship));

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

        Command cmd = new CreateViewCommand(view, targetFolder);

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
        compound.add(new CreateViewCommand(clonedView, targetFolder));
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

        // Text alignment
        target.setTextAlignment(source.getTextAlignment());

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

        return new PreparedMutation<>(cmd, convertToElementDto(element), element.getId(), element);
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
        // false for empty compounds, which the dispatcher would treat as a hard failure.
        // Return a NoOpCommand instead so the mutation reports cleanly. (C3b M2)
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

        // G1: validate semantic attributes against the actual resolved relationship class.
        // Type-conditional rejection at the prepare boundary — never mutate before validation passes.
        validateSemanticAttributesForUpdate(attrs, relationship);

        RelationshipSemanticAttributes safeAttrs = (attrs != null) ? attrs : RelationshipSemanticAttributes.NONE;
        Command cmd = buildUpdateConceptCommand(relationship, model, InputValidation.reject(name, "name"), documentation, properties,
                specialization, relationship.eClass().getName(),
                () -> new UpdateRelationshipCommand(relationship, InputValidation.reject(name, "name"), documentation, properties,
                        safeAttrs));

        return new PreparedMutation<>(cmd, convertToRelationshipDto(relationship), relationship.getId(), relationship);
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
     * Prepares an update-model mutation (G6). Mirrors {@link #prepareUpdateView}.
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

        Command cmd = new UpdateModelCommand(model, InputValidation.reject(name, "name"), effectivePurpose,
                clearPurpose, properties);

        return new PreparedMutation<>(cmd, getModelInfo(), model.getId(), model);
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

    // ---- View placement prepare methods ----

    /**
     * Prepares an add-to-view mutation: validates, creates EMF objects, builds command.
     */
    private PreparedMutation<AddToViewResultDto> prepareAddToView(
            String viewId, String elementId, Integer x, Integer y,
            Integer width, Integer height, boolean autoConnect,
            String parentViewObjectId) {
        return prepareAddToView(viewId, elementId, x, y, width, height,
                autoConnect, parentViewObjectId, null, null, null);
    }

    /**
     * Prepares an add-to-view mutation with optional pre-resolved batch parent container.
     * When batchParentContainer is non-null, it overrides parentViewObjectId lookup
     * (used for groups created earlier in the same bulk-mutate batch).
     * When batchView is non-null, it overrides viewId lookup
     * (used for views created earlier in the same bulk-mutate batch).
     */
    private PreparedMutation<AddToViewResultDto> prepareAddToView(
            String viewId, String elementId, Integer x, Integer y,
            Integer width, Integer height, boolean autoConnect,
            String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling, ImageParams imageParams) {
        return prepareAddToView(viewId, elementId, x, y, width, height,
                autoConnect, parentViewObjectId, batchParentContainer,
                styling, imageParams, null);
    }

    private PreparedMutation<AddToViewResultDto> prepareAddToView(
            String viewId, String elementId, Integer x, Integer y,
            Integer width, Integer height, boolean autoConnect,
            String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling, ImageParams imageParams,
            IArchimateDiagramModel batchView) {
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
        if ((x == null) != (y == null)) {
            throw new ModelAccessException(
                    "Both x and y must be specified together, or both omitted for auto-placement",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide both x and y coordinates, or omit both for auto-placement",
                    null);
        }

        // Find view — use batch-created view if available
        IArchimateDiagramModel view = batchView;
        if (view == null) {
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel resolvedView)) {
                throw new ModelAccessException(
                        "View not found: " + viewId,
                        ErrorCode.VIEW_NOT_FOUND,
                        null,
                        "Use get-views to find valid view IDs",
                        null);
            }
            view = resolvedView;
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

        // Apply styling at creation time
        StylingHelper.applyStylingToNewObject(diagramObj, styling);

        // Apply image at creation time
        ImageHelper.applyImageToNewObject(diagramObj, imageParams);

        // Build view object DTO (include styling; include image fields;
        // include figureType + textAlignment + verticalTextAlignment;
        // G5: include typography + gradient + borderType + deriveLineColor + outlineOpacity)
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
                StylingHelper.readLineStyle(diagramObj));

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
                // skip orphaned relationships (not in containment tree)
                if (rel.eContainer() == null) continue;

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
                // skip orphaned relationships (not in containment tree)
                if (rel.eContainer() == null) continue;

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

        // W2 icon-band parent-resize at the CREATION moment (Task-0.6 (ii)).
        // When the new child is added to a parent container that already has
        // a non-default corner-anchored icon AND the new child + existing
        // siblings occupy the corner, wrap the AddToViewCommand with a
        // parent-resize so the corner is reserved atomically. Case A
        // (parent has no image properties) and Case B (corner empty) both
        // short-circuit inside the helper (returns null).
        Command w2ParentResize = computeIconBandParentResizeCommand(
                parentContainer, resolvedX, resolvedY, resolvedWidth, resolvedHeight);
        if (w2ParentResize != null) {
            NonNotifyingCompoundCommand w2Wrap = new NonNotifyingCompoundCommand("Add view object with icon-band parent-resize");
            w2Wrap.add(w2ParentResize);
            w2Wrap.add(cmd);
            cmd = w2Wrap;
        }
        cmd = RecedeContainerFillCommand.wrap(cmd, parentContainer, styling); // recede null-fill element/group parent (no-op for root view)

        AddToViewResultDto resultDto = new AddToViewResultDto(
                viewObjectDto, autoConnections, skippedAutoConnections);
        return new PreparedMutation<>(cmd, resultDto, diagramObj.getId(), diagramObj);
    }

    private PreparedMutation<ViewGroupDto> prepareAddGroupToView(
            String viewId, String label, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId) {
        return prepareAddGroupToView(viewId, label, x, y, width, height,
                parentViewObjectId, null, null, null);
    }

    /**
     * Prepares an add-group-to-view mutation with optional pre-resolved batch parent container.
     * When batchParentContainer is non-null, it overrides parentViewObjectId lookup
     * (used for groups created earlier in the same bulk-mutate batch).
     * When batchView is non-null, it overrides viewId lookup
     * (used for views created earlier in the same bulk-mutate batch).
     */
    private PreparedMutation<ViewGroupDto> prepareAddGroupToView(
            String viewId, String label, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling, ImageParams imageParams) {
        return prepareAddGroupToView(viewId, label, x, y, width, height,
                parentViewObjectId, batchParentContainer, styling, imageParams, null);
    }

    private PreparedMutation<ViewGroupDto> prepareAddGroupToView(
            String viewId, String label, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling, ImageParams imageParams,
            IArchimateDiagramModel batchView) {
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

        // Find view — use batch-created view if available
        IArchimateDiagramModel view = batchView;
        if (view == null) {
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel resolvedView)) {
                throw new ModelAccessException(
                        "View not found: " + viewId,
                        ErrorCode.VIEW_NOT_FOUND,
                        null,
                        "Use get-views to find valid view IDs",
                        null);
            }
            view = resolvedView;
        }

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Interpret escape sequences in group label BEFORE sizing so the
        // helper measures the rendered string (with real newlines) rather than the
        // pre-interpretation escape form. Moved up from after setBounds per review M1.
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
            // so the wrap simulation uses the actual content width (review L2).
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
        Command cmd = RecedeContainerFillCommand.wrap(new AddGroupToViewCommand(group, parentContainer), parentContainer, styling);

        // Build DTO (include styling; include image fields;
        // include figureType + textAlignment + verticalTextAlignment)
        ViewGroupDto dto = new ViewGroupDto(
                group.getId(), label, resolvedX, resolvedY,
                resolvedWidth, resolvedHeight, null, List.of(),
                StylingHelper.readFillColor(group), StylingHelper.readLineColor(group),
                StylingHelper.readFontColor(group), StylingHelper.readOpacity(group),
                StylingHelper.readLineWidth(group),
                ImageHelper.readImagePath(group), ImageHelper.readImagePosition(group),
                ImageHelper.readShowIcon(group),
                StylingHelper.readFigureType(group),
                StylingHelper.readTextAlignment(group),
                StylingHelper.readVerticalTextAlignment(group));

        return new PreparedMutation<>(cmd, dto, group.getId(), group);
    }

    private PreparedMutation<ViewNoteDto> prepareAddNoteToView(
            String viewId, String content, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId) {
        return prepareAddNoteToView(viewId, content, null, null, x, y, width, height,
                parentViewObjectId, null, null, null);
    }

    /**
     * Prepares an add-note-to-view mutation with optional pre-resolved batch parent container.
     * When batchParentContainer is non-null, it overrides parentViewObjectId lookup
     * (used for groups created earlier in the same bulk-mutate batch).
     * Position-based placement (above-content, below-content).
     */
    private PreparedMutation<ViewNoteDto> prepareAddNoteToView(
            String viewId, String content, String position, Integer gap,
            Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            StylingParams styling, ImageParams imageParams) {
        return prepareAddNoteToView(viewId, content, position, gap, x, y,
                width, height, parentViewObjectId, batchParentContainer,
                styling, imageParams, null);
    }

    private PreparedMutation<ViewNoteDto> prepareAddNoteToView(
            String viewId, String content, String position, Integer gap,
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
        if (position == null && (x == null) != (y == null)) {
            throw new ModelAccessException(
                    "Both x and y must be specified together, or both omitted for auto-placement",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide both x and y coordinates, or omit both for auto-placement",
                    null);
        }

        // Find view — use batch-created view if available
        IArchimateDiagramModel view = batchView;
        if (view == null) {
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel resolvedView)) {
                throw new ModelAccessException(
                        "View not found: " + viewId,
                        ErrorCode.VIEW_NOT_FOUND,
                        null,
                        "Use get-views to find valid view IDs",
                        null);
            }
            view = resolvedView;
        }

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Interpret escape sequences in note content BEFORE sizing so the
        // helper measures the rendered string (with real newlines) rather than the
        // pre-interpretation escape form. Moved up from after setBounds per review M1.
        content = TextUtils.interpretEscapes(InputValidation.reject(content, "content"));

        // Resolve dimensions
        int resolvedWidth = (width != null) ? width : DEFAULT_NOTE_WIDTH;
        // When caller did not pin height, fit it to the wrapped content so descriptive
        // title-style notes don't silently clip.
        // Subtract horizontal text inset so the wrap simulation uses the actual content
        // width (review L2).
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

        // Note: content was escape-interpreted earlier (before sizing); see review M1.

        // Create note
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setContent(content);
        note.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Apply styling at creation time
        StylingHelper.applyStylingToNewObject(note, styling);

        // Apply image at creation time
        ImageHelper.applyImageToNewObject(note, imageParams);

        // Build command
        Command cmd = new AddNoteToViewCommand(note, parentContainer);

        // Build DTO — include positionNote if set, image fields;
        // include textAlignment + verticalTextAlignment
        // (notes are excluded from figureType per Task-2.3)
        String parentVoId = (parentViewObjectId != null) ? parentViewObjectId : null;
        ViewNoteDto dto = new ViewNoteDto(
                note.getId(), content, resolvedX, resolvedY,
                resolvedWidth, resolvedHeight, parentVoId,
                StylingHelper.readFillColor(note), StylingHelper.readLineColor(note),
                StylingHelper.readFontColor(note), StylingHelper.readOpacity(note),
                StylingHelper.readLineWidth(note), positionNote,
                ImageHelper.readImagePath(note), ImageHelper.readImagePosition(note),
                ImageHelper.readShowIcon(note),
                StylingHelper.readTextAlignment(note),
                StylingHelper.readVerticalTextAlignment(note));

        return new PreparedMutation<>(cmd, dto, note.getId(), note);
    }

    /**
     * Prepares an add-view-reference-to-view mutation (G8).
     * Convenience overload — delegates to the canonical 10-arg form.
     */
    private PreparedMutation<EmbeddedViewDto> prepareAddViewReferenceToView(
            String viewId, String referencedViewId, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            StylingParams styling) {
        return prepareAddViewReferenceToView(viewId, referencedViewId, x, y,
                width, height, parentViewObjectId, null, null, styling);
    }

    /**
     * Prepares an add-view-reference-to-view mutation with optional pre-resolved
     * batch parent container + batch view (G8).
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
            String viewId, String referencedViewId, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer,
            IArchimateDiagramModel batchView,
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
        if ((x == null) != (y == null)) {
            throw new ModelAccessException(
                    "Both x and y must be specified together, or both omitted for auto-placement",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide both x and y coordinates, or omit both for auto-placement",
                    null);
        }

        // Resolve TARGET view (where the visual is placed) — use batch-created view if available
        IArchimateDiagramModel view = batchView;
        if (view == null) {
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel resolvedView)) {
                throw new ModelAccessException(
                        "View not found: " + viewId,
                        ErrorCode.VIEW_NOT_FOUND,
                        null,
                        "Use get-views to find valid view IDs",
                        null);
            }
            view = resolvedView;
        }

        // Resolve REFERENCED view (the source view being embedded).
        // Q3 default: IArchimateDiagramModel only (mirror create-view scope).
        EObject refViewObj = ArchimateModelUtils.getObjectByID(model, referencedViewId);
        if (!(refViewObj instanceof IArchimateDiagramModel referencedView)) {
            throw new ModelAccessException(
                    "Referenced view not found or is not an ArchiMate view: "
                            + referencedViewId,
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

        // Resolve dimensions (Task 0.8 / Q1 defaults pinned at 185×80)
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

        // Create the typed view-reference visual (Task 0.3 / 0.4 verified surface).
        IDiagramModelReference viewRef =
                IArchimateFactory.eINSTANCE.createDiagramModelReference();
        viewRef.setReferencedModel(referencedView);
        viewRef.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Apply G5 styling at creation time — generic IDiagramModelObject path
        // (Task 0.4: IDiagramModelReference inherits the full G5 surface).
        StylingHelper.applyStylingToNewObject(viewRef, styling);

        // Build command
        Command cmd = new AddViewReferenceToViewCommand(viewRef, parentContainer);

        // Build DTO — read styling back via the generic IDiagramModelObject readers.
        // referencedViewId reads from the live EMF reference; @JsonInclude(NON_NULL)
        // omits cleanly if Archi clears the cross-ref post-cascade-delete (Task 0.9).
        // Note on parentViewObjectId echo: the batch-parent path at the bulk-mutate
        // call site (search "case \"add-view-reference-to-view\"") deliberately
        // passes null for parentViewObjectId so the DTO omits the field — see
        // M1 cross-LLM review disposition.
        String resolvedRefViewId = (viewRef.getReferencedModel() != null)
                ? viewRef.getReferencedModel().getId() : null;
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
                null);

        return new PreparedMutation<>(cmd, dto, viewRef.getId(), viewRef);
    }

    // ---- add-image-to-view (G16) ----

    private PreparedMutation<DiagramImageDto> prepareAddImageToView(
            String viewId, String imagePath, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            StylingParams styling) {
        return prepareAddImageToView(viewId, imagePath, x, y, width, height,
                parentViewObjectId, null, null, styling, null, null);
    }

    /**
     * Prepares an add-image-to-view mutation with optional pre-resolved batch
     * parent container + batch view (G16, mirrors the
     * prepareAddViewReferenceToView shape).
     *
     * <p>Validation order: resolve view → validate parent → validate
     * imagePath exists in archive (NEW {@link #validateImagePathExists}) →
     * read natural dimensions (Q1) → create EMF object → apply styling →
     * build command.</p>
     *
     * <p>Per Task-0 / Q4 disposition: this method deliberately deviates from
     * the {@code project-context.md} validation-sync principle and REJECTS
     * imagePath values that don't resolve in the model archive (Archi GUI
     * would silently render a broken-image placeholder). Rationale in story
     * Dev Notes §"Why we deviate".</p>
     */
    private PreparedMutation<DiagramImageDto> prepareAddImageToView(
            String viewId, String imagePath, Integer x, Integer y,
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
        if ((x == null) != (y == null)) {
            throw new ModelAccessException(
                    "Both x and y must be specified together, or both omitted for auto-placement",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide both x and y coordinates, or omit both for auto-placement",
                    null);
        }

        // Resolve TARGET view (where the visual is placed)
        IArchimateDiagramModel view = batchView;
        if (view == null) {
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel resolvedView)) {
                throw new ModelAccessException(
                        "View not found: " + viewId,
                        ErrorCode.VIEW_NOT_FOUND,
                        null,
                        "Use get-views to find valid view IDs",
                        null);
            }
            view = resolvedView;
        }

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer);

        // Q4 strict imagePath validation — REJECT typo'd paths so the
        // failure is loud at the prepare boundary rather than rendered as a
        // broken-image placeholder silently downstream.
        validateImagePathExists(model, imagePath);

        // Validate dimensions
        validatePositiveDimension(width, "width");
        validatePositiveDimension(height, "height");

        // Q1 default: resolve natural dimensions from archive bytes; fallback
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

        // Create the typed image visual (Task 0.2 / 0.3 verified surface).
        IDiagramModelImage image =
                IArchimateFactory.eINSTANCE.createDiagramModelImage();
        image.setImagePath(imagePath);
        image.setBounds(resolvedX, resolvedY, resolvedWidth, resolvedHeight);

        // Apply G5 styling at creation time — generic IDiagramModelObject path
        // (Task 0.6: DiagramModelImage extends DiagramModelObject so all 16
        // G5 styling fields flow through verbatim at the EMF state level).
        StylingHelper.applyStylingToNewObject(image, styling);

        // Follow-up (empirical Step 5 gap): apply IBorderObject
        // borderColor + IDocumentable documentation BEFORE the command runs.
        // These are NOT part of the StylingHelper G5 surface (they're typed
        // interfaces specific to IDiagramModelImage's parent set).
        if (borderColor != null) {
            image.setBorderColor(borderColor.isEmpty() ? null : borderColor);
        }
        if (documentation != null) {
            image.setDocumentation(documentation);
        }

        // Build command
        Command cmd = new AddImageToViewCommand(image, parentContainer);

        // Build DTO — Q5 minimal: bounds + imagePath + parent + the two fields
        // IDiagramModelImage actually surfaces (borderColor via IBorderObject,
        // documentation via IDocumentable). Other G5 fields silently dropped
        // by Archi's image renderer are intentionally omitted from the DTO.
        String docs = image.getDocumentation();
        if (docs != null && docs.isEmpty()) docs = null;
        DiagramImageDto dto = new DiagramImageDto(
                image.getId(), imagePath,
                resolvedX, resolvedY, resolvedWidth, resolvedHeight,
                parentViewObjectId,
                image.getBorderColor(),
                docs);

        return new PreparedMutation<>(cmd, dto, image.getId(), image);
    }

    /**
     * Validates that the supplied {@code imagePath} resolves to bytes in the
     * model archive (G16, Q4 strict-validation default).
     *
     * <p>Per Task-0.5 disposition: deviates from the validation-sync principle
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
     * returns {@code null} if the archive read fails (G16,
     * Q1 default — fallback to {@code DEFAULT_IMAGE_VISUAL_*}).
     */
    private int[] tryReadNaturalImageDimensions(IArchimateModel model, String imagePath) {
        return ImageHelper.readNaturalImageDimensions(model, imagePath);
    }

    /**
     * Prepares an add-connection-to-view mutation: validates, creates EMF objects, builds command.
     */
    private PreparedMutation<ViewConnectionDto> prepareAddConnectionToView(
            String viewId, String relationshipId, String sourceViewObjectId,
            String targetViewObjectId, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            StylingParams styling, Boolean showLabel, Integer textPosition) {
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

        // Apply styling at creation time (G5)
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
            // G5: typography composite + lineStyle bitmask (arrow bits preserved).
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

        Command cmd = new AddConnectionToViewCommand(conn, sourceViewObj, targetViewObj);

        // Build DTO with styling info included in response (G5: add typography;
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

        ViewConnectionDto dto = new ViewConnectionDto(
                baseDto.viewConnectionId(), baseDto.relationshipId(),
                baseDto.relationshipType(), baseDto.sourceViewObjectId(),
                baseDto.targetViewObjectId(), baseDto.bendpoints(),
                baseDto.absoluteBendpoints(), baseDto.sourceAnchor(),
                baseDto.targetAnchor(), baseDto.textPosition(),
                dtoLineColor, dtoLineWidth, dtoFontColor, dtoNameVisible,
                dtoFontName, dtoFontSize, dtoFontStyle);

        return new PreparedMutation<>(cmd, dto, conn.getId(), conn);
    }

    // ---- View editing/removal prepare methods ----

    /**
     * Prepares an update-view-object mutation: validates, reads current bounds,
     * merges with provided values, builds command and DTO.
     */
    private PreparedMutation<ViewObjectDto> prepareUpdateViewObject(
            String viewObjectId, Integer x, Integer y, Integer width, Integer height,
            String text, StylingParams styling, ImageParams imageParams,
            String labelExpression, String anchorTarget, String anchorEdge, Integer anchorDx, Integer anchorDy) {
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

        // Find view object — accept element objects, groups, and notes
        EObject obj = ArchimateModelUtils.getObjectByID(model, viewObjectId);
        if (!(obj instanceof IDiagramModelObject diagramObj)) {
            throw new ModelAccessException(
                    "View object not found: " + viewObjectId,
                    ErrorCode.VIEW_OBJECT_NOT_FOUND,
                    null,
                    "Use get-view-contents to find valid view object IDs and connection IDs on the view.",
                    null);
        }

        // Validate text parameter
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

        // Interpret escape sequences in text for notes/groups
        text = TextUtils.interpretEscapes(InputValidation.reject(text, "text"));

        // Merge requested bounds with current; when setting an anchor, resolve x/y from the target.
        int[] merged = AnchorResolver.mergeBounds(diagramObj, x, y, width, height, anchorTarget, anchorEdge, anchorDx, anchorDy);
        int mergedX = merged[0], mergedY = merged[1], mergedWidth = merged[2], mergedHeight = merged[3];

        // W2 icon-band parent-resize at the MUTATION moment (Task-0.6 (iii)).
        // Fires when the LLM sets a bottom-corner `imagePosition` on
        // a container whose existing children already occupy that corner —
        // grows the container's height by ICON_BAND_HEIGHT (24 px) so the
        // icon does not collide. Sibling-symmetric with the H6 cascade
        // below, which then re-cascades the grown height up to any
        // grandparent group.
        //
        // Case A short-circuit (no imageParams) is the outer `if` —
        // skip the block entirely when no image position is staged.
        // Case B short-circuit (corner empty) is the inner predicate
        // — explicit `if/else`, NOT a `max(...)` expression, so a non-firing
        // case is byte-identical to today.
        boolean w2IconBandFired = false;
        if (imageParams != null && imageParams.imagePosition() != null
                && diagramObj instanceof IIconic
                && diagramObj instanceof IDiagramModelContainer w2Container) {
            int requestedPos = ImageParams.positionToInt(imageParams.imagePosition());
            // Bottom corners only (top corners require child-shift — deferred;
            // top-right is the Archi default sentinel — excluded).
            if (requestedPos == 6 || requestedPos == 8) {
                List<int[]> childRects = new ArrayList<>();
                for (IDiagramModelObject c : w2Container.getChildren()) {
                    IBounds cb = c.getBounds();
                    childRects.add(new int[] {cb.getX(), cb.getY(), cb.getWidth(), cb.getHeight()});
                }
                if (ImageHelper.anyChildOccupiesIconBand(mergedWidth, mergedHeight,
                        requestedPos, W2_ICON_SIZE, W2_ICON_MARGIN, childRects)) {
                    mergedHeight = mergedHeight + ImageHelper.ICON_BAND_HEIGHT;
                    w2IconBandFired = true;
                }
            }
        }

        // Build command (with optional text, styling, image, label-expression, and anchor update)
        Command cmd = new UpdateViewObjectCommand(diagramObj, mergedX, mergedY, mergedWidth, mergedHeight, text, styling, imageParams, InputValidation.reject(labelExpression, "labelExpression"), anchorTarget, anchorEdge, anchorDx, anchorDy);

        // Successor H6 (2026-05-14):
        // post-command-build parent-bounds check on the raw update-view-object
        // path. Sibling-symmetric with Successor E's post-autoNudge pass (line
        // 3706-3752) and Successor E.b's post-spacing-tool pass (line 7291-7340)
        // — together they make resizeParentGroupIfNeeded the single source of
        // truth across the three convenience-tool layers (autoNudge / spacing
        // / update-view-object).
        //
        // Gate: only run when the caller explicitly modified bounds OR the
        // W2 icon-band block above grew mergedHeight without the caller
        // passing a bounds field (so the grandparent-group cascade still
        // fires for that case). Skips no-op styling-only updates so
        // pre-existing overflow from prior workflow steps is not silently
        // "fixed" as a side effect.
        //
        // When overflow is detected, the user's UpdateViewObjectCommand and the
        // helper's parent-resize commands are wrapped into a single
        // NonNotifyingCompoundCommand so they execute as one undo step.
        boolean boundsModified = (x != null) || (y != null) || (width != null) || (height != null) || w2IconBandFired || (anchorTarget != null && !anchorTarget.isEmpty());
        if (boundsModified) {
            EObject container = diagramObj.eContainer();
            if (container instanceof IDiagramModelGroup parentGroup) {
                Map<String, int[]> virtualGroupBounds = new LinkedHashMap<>();
                Map<String, Command> groupResizeCommands = new LinkedHashMap<>();
                resizeParentGroupIfNeeded(parentGroup, diagramObj,
                        mergedX, mergedY, mergedWidth, mergedHeight,
                        virtualGroupBounds, groupResizeCommands);
                if (!groupResizeCommands.isEmpty()) {
                    NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(
                            "Update view object bounds with parent-group resize (Successor H6)");
                    compound.add(cmd);
                    for (Command resize : groupResizeCommands.values()) {
                        compound.add(resize);
                    }
                    cmd = compound;
                }
            }
        }
        cmd = AnchorResolver.wrapAnchoredChildren(cmd, diagramObj, mergedX, mergedY, mergedWidth, mergedHeight, boundsModified);

        // Build DTO — generic for all view object types (include post-execution styling; image;
        // include post-execution figureType + textAlignment + verticalTextAlignment;
        // G5: include typography + gradient + borderType + deriveLineColor + outlineOpacity)
        String dtoFillColor = StylingHelper.computePostStylingColor(StylingHelper.readFillColor(diagramObj), styling != null ? styling.fillColor() : null);
        String dtoLineColor = StylingHelper.computePostStylingColor(StylingHelper.readLineColor(diagramObj), styling != null ? styling.lineColor() : null);
        String dtoFontColor = StylingHelper.computePostStylingColor(StylingHelper.readFontColor(diagramObj), styling != null ? styling.fontColor() : null);
        Integer dtoOpacity = StylingHelper.computePostStylingOpacity(StylingHelper.readOpacity(diagramObj), styling != null ? styling.opacity() : null);
        Integer dtoLineWidth = StylingHelper.computePostStylingLineWidth(StylingHelper.readLineWidth(diagramObj), styling != null ? styling.lineWidth() : null);
        String dtoFigureType = StylingHelper.computePostStylingFigureType(StylingHelper.readFigureType(diagramObj), styling != null ? styling.figureType() : null);
        String dtoTextAlignment = StylingHelper.computePostStylingTextAlignment(StylingHelper.readTextAlignment(diagramObj), styling != null ? styling.textAlignment() : null);
        String dtoVerticalTextAlignment = StylingHelper.computePostStylingVerticalTextAlignment(StylingHelper.readVerticalTextAlignment(diagramObj), styling != null ? styling.verticalTextAlignment() : null);

        // G5: post-execution G5 fields. Each computePostStyling* helper takes the
        // pre-execution read + the requested styling value and reconciles.
        String dtoFontName = StylingHelper.computePostStylingFontName(StylingHelper.readFontName(diagramObj), styling != null ? styling.fontName() : null);
        Integer dtoFontSize = StylingHelper.computePostStylingFontSize(StylingHelper.readFontSize(diagramObj), styling != null ? styling.fontSize() : null);
        String dtoFontStyle = StylingHelper.computePostStylingFontStyle(StylingHelper.readFontStyle(diagramObj), styling != null ? styling.fontStyle() : null);
        String dtoGradient = StylingHelper.computePostStylingGradient(StylingHelper.readGradient(diagramObj), styling != null ? styling.gradient() : null);
        String dtoBorderType = StylingHelper.computePostStylingBorderType(StylingHelper.readBorderType(diagramObj), styling != null ? styling.borderType() : null);
        Boolean dtoDeriveLineColor = StylingHelper.computePostStylingDeriveLineColor(StylingHelper.readDeriveLineColor(diagramObj), styling != null ? styling.deriveLineColor() : null);
        Integer dtoOutlineOpacity = StylingHelper.computePostStylingOutlineOpacity(StylingHelper.readOutlineOpacity(diagramObj), styling != null ? styling.outlineOpacity() : null);
        String dtoLineStyle = StylingHelper.computePostStylingLineStyle(StylingHelper.readLineStyle(diagramObj), styling != null ? styling.lineStyle() : null);

        // Compute post-execution image fields
        String dtoImagePath = computePostImagePath(diagramObj, imageParams);
        String dtoImagePosition = computePostImagePosition(diagramObj, imageParams);
        String dtoShowIcon = computePostShowIcon(diagramObj, imageParams);
        Double dtoCoveragePercent = null;
        String dtoCoverageWarning = null;

        // Compute coverage if image is being set (not cleared)
        if (dtoImagePath != null && !dtoImagePath.isEmpty()) {
            dtoCoveragePercent = computeImageCoverage(dtoImagePath, mergedWidth, mergedHeight);
            if (dtoCoveragePercent != null) {
                dtoCoverageWarning = ImageHelper.coverageWarning(dtoCoveragePercent);
            }
        }

        // G4: post-execution label expression read from the IFeatures store.
        // The command captured the old value at construction; the new value (after emptyToNull
        // normalization) is what Archi will hold after dispatch. We compute the would-be
        // post-state here for the DTO without executing the command — empty string → null
        // (clears), non-null → the new value verbatim, null → existing value unchanged.
        String dtoLabelExpression = computePostLabelExpression(diagramObj, labelExpression);
        AnchorResolver.AnchorInfo anchor = AnchorResolver.computePostAnchor(diagramObj, anchorTarget, anchorEdge, anchorDx, anchorDy);

        ViewObjectDto dto;
        if (diagramObj instanceof IDiagramModelArchimateObject archObj) {
            IArchimateElement element = archObj.getArchimateElement();
            dto = new ViewObjectDto(
                    viewObjectId, element.getId(), element.getName(),
                    element.eClass().getName(), mergedX, mergedY,
                    mergedWidth, mergedHeight,
                    dtoFillColor, dtoLineColor, dtoFontColor, dtoOpacity, dtoLineWidth,
                    dtoImagePath, dtoImagePosition, dtoShowIcon,
                    dtoCoveragePercent, dtoCoverageWarning,
                    dtoFigureType, dtoTextAlignment, dtoVerticalTextAlignment,
                    dtoLabelExpression,
                    dtoFontName, dtoFontSize, dtoFontStyle,
                    dtoGradient, dtoBorderType, dtoDeriveLineColor, dtoOutlineOpacity, dtoLineStyle, anchor.target(), anchor.edge(), anchor.dx(), anchor.dy());
        } else {
            // Group or note — no element association
            dto = new ViewObjectDto(
                    viewObjectId, null, diagramObj.getName(),
                    diagramObj.eClass().getName(), mergedX, mergedY,
                    mergedWidth, mergedHeight,
                    dtoFillColor, dtoLineColor, dtoFontColor, dtoOpacity, dtoLineWidth,
                    dtoImagePath, dtoImagePosition, dtoShowIcon,
                    dtoCoveragePercent, dtoCoverageWarning,
                    dtoFigureType, dtoTextAlignment, dtoVerticalTextAlignment,
                    dtoLabelExpression,
                    dtoFontName, dtoFontSize, dtoFontStyle,
                    dtoGradient, dtoBorderType, dtoDeriveLineColor, dtoOutlineOpacity, dtoLineStyle, anchor.target(), anchor.edge(), anchor.dx(), anchor.dy());
        }

        return new PreparedMutation<>(cmd, dto, viewObjectId);
    }

    /**
     * Computes the post-execution label-expression value for the DTO from the
     * pre-state on the diagram object and the requested new value. Mirrors the
     * normalization performed inside {@link UpdateViewObjectCommand}: null leaves
     * the current value unchanged, empty string clears (DTO shows null), non-null
     * sets verbatim. G4.
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
     * Prepares an update-view-object mutation using a direct EMF reference (for bulk back-references).
     * H2 fix: bypasses ArchimateModelUtils.getObjectByID for back-referenced view objects.
     */
    private PreparedMutation<ViewObjectDto> prepareUpdateViewObjectDirect(
            IDiagramModelArchimateObject diagramObj, Integer x, Integer y,
            Integer width, Integer height) {
        return prepareUpdateViewObjectDirect(diagramObj, x, y, width, height, null, null, null);
    }

    /**
     * Extended Direct variant that also handles styling and image params.
     * Used by bulk-mutate when the view object was created earlier in the same batch.
     * G4: adds optional labelExpression — null leaves unchanged, empty clears,
     * non-null sets the per-view-object dynamic label template.
     */
    private PreparedMutation<ViewObjectDto> prepareUpdateViewObjectDirect(
            IDiagramModelArchimateObject diagramObj, Integer x, Integer y,
            Integer width, Integer height, StylingParams styling, ImageParams imageParams,
            String labelExpression) {

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

        // Validate at least one field provided
        boolean hasStyling = styling != null && styling.hasAnyValue();
        boolean hasImage = imageParams != null && imageParams.hasAnyValue();
        boolean hasLabelExpression = labelExpression != null;
        if (x == null && y == null && width == null && height == null && !hasStyling && !hasImage && !hasLabelExpression) {
            throw new ModelAccessException(
                    "At least one of x, y, width, height, styling, image, or labelExpression parameter must be provided",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "At least one of x, y, width, height, fillColor, lineColor, fontColor, opacity, lineWidth, imagePath, imagePosition, showIcon, labelExpression must be provided.",
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

        // Backlog W2 sibling-symmetric with `prepareUpdateViewObject` (see
        // matching block in the primary path): icon-band parent-resize at the
        // MUTATION moment on the bulk-mutate back-reference path.
        boolean w2IconBandFired = false;
        if (imageParams != null && imageParams.imagePosition() != null
                && diagramObj instanceof IDiagramModelContainer w2Container) {
            int requestedPos = ImageParams.positionToInt(imageParams.imagePosition());
            if (requestedPos == 6 || requestedPos == 8) {
                List<int[]> childRects = new ArrayList<>();
                for (IDiagramModelObject c : w2Container.getChildren()) {
                    IBounds cb = c.getBounds();
                    childRects.add(new int[] {cb.getX(), cb.getY(), cb.getWidth(), cb.getHeight()});
                }
                if (ImageHelper.anyChildOccupiesIconBand(mergedWidth, mergedHeight,
                        requestedPos, W2_ICON_SIZE, W2_ICON_MARGIN, childRects)) {
                    mergedHeight = mergedHeight + ImageHelper.ICON_BAND_HEIGHT;
                    w2IconBandFired = true;
                }
            }
        }

        // Build command (with optional styling, image, and label-expression update)
        Command cmd = new UpdateViewObjectCommand(diagramObj, mergedX, mergedY, mergedWidth, mergedHeight, null, styling, imageParams, InputValidation.reject(labelExpression, "labelExpression"));

        // Successor H6 (2026-05-14): post-command-build parent-bounds check on
        // the bulk-mutate back-reference path — sibling-symmetric with the
        // primary prepareUpdateViewObject insertion (see comment block at the
        // matching site above) so the H6 invariant holds across BOTH MCP
        // dispatcher branches in the case "update-view-object" handler.
        // W2 (2026-05-20): the icon-band block above may have grown
        // mergedHeight without the caller passing a bounds field; OR-in
        // w2IconBandFired so the grandparent-group cascade fires for that case.
        boolean boundsModified = (x != null) || (y != null) || (width != null) || (height != null) || w2IconBandFired;
        if (boundsModified) {
            EObject container = diagramObj.eContainer();
            if (container instanceof IDiagramModelGroup parentGroup) {
                Map<String, int[]> virtualGroupBounds = new LinkedHashMap<>();
                Map<String, Command> groupResizeCommands = new LinkedHashMap<>();
                resizeParentGroupIfNeeded(parentGroup, diagramObj,
                        mergedX, mergedY, mergedWidth, mergedHeight,
                        virtualGroupBounds, groupResizeCommands);
                if (!groupResizeCommands.isEmpty()) {
                    NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(
                            "Update view object bounds with parent-group resize (Successor H6)");
                    compound.add(cmd);
                    for (Command resize : groupResizeCommands.values()) {
                        compound.add(resize);
                    }
                    cmd = compound;
                }
            }
        }

        // Build DTO with post-execution styling and image fields
        String dtoFillColor = StylingHelper.computePostStylingColor(StylingHelper.readFillColor(diagramObj), styling != null ? styling.fillColor() : null);
        String dtoLineColor = StylingHelper.computePostStylingColor(StylingHelper.readLineColor(diagramObj), styling != null ? styling.lineColor() : null);
        String dtoFontColor = StylingHelper.computePostStylingColor(StylingHelper.readFontColor(diagramObj), styling != null ? styling.fontColor() : null);
        Integer dtoOpacity = StylingHelper.computePostStylingOpacity(StylingHelper.readOpacity(diagramObj), styling != null ? styling.opacity() : null);
        Integer dtoLineWidth = StylingHelper.computePostStylingLineWidth(StylingHelper.readLineWidth(diagramObj), styling != null ? styling.lineWidth() : null);

        String dtoImagePath = computePostImagePath(diagramObj, imageParams);
        String dtoImagePosition = computePostImagePosition(diagramObj, imageParams);
        String dtoShowIcon = computePostShowIcon(diagramObj, imageParams);
        Double dtoCoveragePercent = null;
        String dtoCoverageWarning = null;
        if (dtoImagePath != null && !dtoImagePath.isEmpty()) {
            dtoCoveragePercent = computeImageCoverage(dtoImagePath, mergedWidth, mergedHeight);
            if (dtoCoveragePercent != null) {
                dtoCoverageWarning = ImageHelper.coverageWarning(dtoCoveragePercent);
            }
        }

        IArchimateElement element = diagramObj.getArchimateElement();
        String dtoLabelExpression = computePostLabelExpression(diagramObj, labelExpression);
        // G5: include typography + gradient + borderType + deriveLineColor + outlineOpacity + lineStyle in DTO.
        String dtoFontName = StylingHelper.computePostStylingFontName(StylingHelper.readFontName(diagramObj), styling != null ? styling.fontName() : null);
        Integer dtoFontSize = StylingHelper.computePostStylingFontSize(StylingHelper.readFontSize(diagramObj), styling != null ? styling.fontSize() : null);
        String dtoFontStyle = StylingHelper.computePostStylingFontStyle(StylingHelper.readFontStyle(diagramObj), styling != null ? styling.fontStyle() : null);
        String dtoGradient = StylingHelper.computePostStylingGradient(StylingHelper.readGradient(diagramObj), styling != null ? styling.gradient() : null);
        String dtoBorderType = StylingHelper.computePostStylingBorderType(StylingHelper.readBorderType(diagramObj), styling != null ? styling.borderType() : null);
        Boolean dtoDeriveLineColor = StylingHelper.computePostStylingDeriveLineColor(StylingHelper.readDeriveLineColor(diagramObj), styling != null ? styling.deriveLineColor() : null);
        Integer dtoOutlineOpacity = StylingHelper.computePostStylingOutlineOpacity(StylingHelper.readOutlineOpacity(diagramObj), styling != null ? styling.outlineOpacity() : null);
        String dtoLineStyle = StylingHelper.computePostStylingLineStyle(StylingHelper.readLineStyle(diagramObj), styling != null ? styling.lineStyle() : null);
        ViewObjectDto dto = new ViewObjectDto(
                diagramObj.getId(), element.getId(), element.getName(),
                element.eClass().getName(), mergedX, mergedY,
                mergedWidth, mergedHeight,
                dtoFillColor, dtoLineColor, dtoFontColor, dtoOpacity, dtoLineWidth,
                dtoImagePath, dtoImagePosition, dtoShowIcon,
                dtoCoveragePercent, dtoCoverageWarning,
                StylingHelper.readFigureType(diagramObj),
                StylingHelper.readTextAlignment(diagramObj),
                StylingHelper.readVerticalTextAlignment(diagramObj),
                dtoLabelExpression,
                dtoFontName, dtoFontSize, dtoFontStyle,
                dtoGradient, dtoBorderType, dtoDeriveLineColor, dtoOutlineOpacity, dtoLineStyle);

        return new PreparedMutation<>(cmd, dto, diagramObj.getId());
    }

    /**
     * Prepares an update-view-connection mutation: validates, creates new bendpoints,
     * builds command and DTO.
     */
    private PreparedMutation<ViewConnectionDto> prepareUpdateViewConnection(
            String viewConnectionId, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling,
            Boolean showLabel, Integer textPosition) {
        IArchimateModel model = requireAndCaptureModel();

        // Validate hex colours before any other processing
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

        // Convert absolute bendpoints to relative if provided
        List<BendpointDto> effectiveBendpoints = ConnectionResponseBuilder.resolveEffectiveBendpointsFromConnection(
                bendpoints, absoluteBendpoints, connection);

        // Build command (with optional styling)
        // When effectiveBendpoints is null (styling-only update), preserve existing bendpoints
        List<IDiagramModelBendpoint> emfBendpoints;
        if (effectiveBendpoints == null) {
            emfBendpoints = new ArrayList<>(connection.getBendpoints());
        } else {
            emfBendpoints = ConnectionResponseBuilder.createEmfBendpoints(effectiveBendpoints);
        }
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

        // G5: post-execution typography in connection DTO (no lineStyle —
        // lineStyle is a view-object property in Archi 5.8, see Task-9 empirical correction).
        // Compute the merged composite font ONCE and parse all three sub-fields off it
        // (cross-LLM review M2: avoids 3× duplicated assembleFontString invocation).
        String mergedConnFont = (styling != null
                && (styling.fontName() != null || styling.fontSize() != null || styling.fontStyle() != null))
                ? StylingHelper.assembleFontString(connection.getFont(),
                        styling.fontName(), styling.fontSize(), styling.fontStyle())
                : connection.getFont();
        String dtoConnFontName = StylingHelper.parseFontName(mergedConnFont);
        Integer dtoConnFontSize = StylingHelper.parseFontSize(mergedConnFont);
        String dtoConnFontStyle = StylingHelper.parseFontStyle(mergedConnFont);

        ViewConnectionDto dto = new ViewConnectionDto(
                baseDto.viewConnectionId(), baseDto.relationshipId(),
                baseDto.relationshipType(), baseDto.sourceViewObjectId(),
                baseDto.targetViewObjectId(), baseDto.bendpoints(),
                baseDto.absoluteBendpoints(), baseDto.sourceAnchor(),
                baseDto.targetAnchor(), baseDto.textPosition(),
                dtoLineColor, dtoLineWidth, dtoFontColor, dtoNameVisible,
                dtoConnFontName, dtoConnFontSize, dtoConnFontStyle);

        return new PreparedMutation<>(cmd, dto, viewConnectionId);
    }

    /**
     * Prepares an update-view-connection mutation using a direct EMF reference (for bulk back-references).
     * H1 fix: bypasses ArchimateModelUtils.getObjectByID for back-referenced view connections.
     */
    private PreparedMutation<ViewConnectionDto> prepareUpdateViewConnectionDirect(
            IDiagramModelArchimateConnection connection, List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            StylingParams styling, Boolean showLabel, Integer textPosition) {

        // Validate styling if provided
        if (styling != null) {
            StylingHelper.validateConnectionStylingParams(styling);
        }

        // Convert absolute bendpoints to relative if provided
        List<BendpointDto> effectiveBendpoints = ConnectionResponseBuilder.resolveEffectiveBendpointsFromConnection(
                bendpoints, absoluteBendpoints, connection);

        // Build command (with optional styling, label visibility, and text position)
        // When effectiveBendpoints is null (styling-only update), preserve existing bendpoints
        List<IDiagramModelBendpoint> emfBendpoints;
        if (effectiveBendpoints == null) {
            emfBendpoints = new ArrayList<>(connection.getBendpoints());
        } else {
            emfBendpoints = ConnectionResponseBuilder.createEmfBendpoints(effectiveBendpoints);
        }
        Command cmd = new UpdateViewConnectionCommand(connection, emfBendpoints, styling, showLabel, textPosition);

        // Build DTO — extract source/target for anchor points
        String viewConnectionId = connection.getId();
        IDiagramModelArchimateObject sourceViewObj =
                (connection.getSource() instanceof IDiagramModelArchimateObject src) ? src : null;
        IDiagramModelArchimateObject targetViewObj =
                (connection.getTarget() instanceof IDiagramModelArchimateObject tgt) ? tgt : null;
        String sourceVoId = sourceViewObj != null ? sourceViewObj.getId() : null;
        String targetVoId = targetViewObj != null ? targetViewObj.getId() : null;

        // Build base DTO then overlay post-execution styling
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

        // G5: post-execution typography in connection DTO (no lineStyle —
        // lineStyle is a view-object property in Archi 5.8, see Task-9 empirical correction).
        // Compute the merged composite font ONCE and parse all three sub-fields off it
        // (cross-LLM review M2: avoids 3× duplicated assembleFontString invocation).
        String mergedConnFont = (styling != null
                && (styling.fontName() != null || styling.fontSize() != null || styling.fontStyle() != null))
                ? StylingHelper.assembleFontString(connection.getFont(),
                        styling.fontName(), styling.fontSize(), styling.fontStyle())
                : connection.getFont();
        String dtoConnFontName = StylingHelper.parseFontName(mergedConnFont);
        Integer dtoConnFontSize = StylingHelper.parseFontSize(mergedConnFont);
        String dtoConnFontStyle = StylingHelper.parseFontStyle(mergedConnFont);

        ViewConnectionDto dto = new ViewConnectionDto(
                baseDto.viewConnectionId(), baseDto.relationshipId(),
                baseDto.relationshipType(), baseDto.sourceViewObjectId(),
                baseDto.targetViewObjectId(), baseDto.bendpoints(),
                baseDto.absoluteBendpoints(), baseDto.sourceAnchor(),
                baseDto.targetAnchor(), baseDto.textPosition(),
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
                        "A model id (relationship or element) is not a view-object id and will not resolve here. "
                                + "Use get-view-contents to find the VISUAL ids: viewConnectionId / viewObjectId on "
                                + "format=graph edges / nodes, or viewConnectionId from format=json connections and "
                                + "viewObjectId from visualMetadata.",
                        null);
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
                throw new ModelAccessException(
                        "View object not found on view: " + viewObjectId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "A model id (relationship or element) is not a view-object id and will not resolve here. "
                                + "Use get-view-contents to find the VISUAL ids: viewConnectionId / viewObjectId on "
                                + "format=graph edges / nodes, or viewConnectionId from format=json connections and "
                                + "viewObjectId from visualMetadata.",
                        null);
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
                throw new ModelAccessException(
                        "View object not found on view: " + viewObjectId,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "A model id (relationship or element) is not a view-object id and will not resolve here. "
                                + "Use get-view-contents to find the VISUAL ids: viewConnectionId / viewObjectId on "
                                + "format=graph edges / nodes, or viewConnectionId from format=json connections and "
                                + "viewObjectId from visualMetadata.",
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
                        "A model id (relationship or element) is not a view-object id and will not resolve here. "
                                + "Use get-view-contents to find the VISUAL ids: viewConnectionId / viewObjectId on "
                                + "format=graph edges / nodes, or viewConnectionId from format=json connections and "
                                + "viewObjectId from visualMetadata.",
                        null);
            }
            Command cmd = new RemoveConnectionFromViewCommand(connection);
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    viewObjectId, "viewConnection", null);
            return new PreparedMutation<>(cmd, dto, viewObjectId);
        }

        // Neither element nor connection found — the branch a model-relationship id (or
        // model-element id) falls into, since neither resolves to any IDiagramModel* instance.
        throw new ModelAccessException(
                "View object not found: " + viewObjectId,
                ErrorCode.VIEW_OBJECT_NOT_FOUND,
                null,
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
                if (documentation != null) proposedChanges.put("documentation", documentation);
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
                if (name != null) proposedChanges.put("name", name);
                if (documentation != null) proposedChanges.put("documentation", documentation);
                if (properties != null) proposedChanges.put("properties", properties);
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

        Command cmd = new UpdateFolderCommand(folder, InputValidation.reject(name, "name"), documentation, properties);

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

    // ---- Bulk mutation ----

    private static final Pattern BACK_REFERENCE_PATTERN = Pattern.compile("\\$(\\d+)\\.id");
    private static final Set<String> CREATE_TOOLS = Set.of(
            "create-element", "create-relationship", "create-view",
            "add-to-view", "add-connection-to-view",
            "add-group-to-view", "add-note-to-view");

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
        try {
            // Phase 1: Validate all operations and build commands (Jetty thread)
            List<PreparedMutation<?>> preparedMutations = new ArrayList<>();
            List<BulkOperationResult> operationResults = new ArrayList<>();
            List<BulkOperationFailure> failedResults = new ArrayList<>();
            // Tracks EMF element objects for back-reference resolution
            Map<Integer, IArchimateElement> createdElements = new LinkedHashMap<>();
            // Tracks EMF view objects for view-level back-reference resolution
            Map<Integer, IDiagramModelArchimateObject> createdViewObjects = new LinkedHashMap<>();
            // Tracks raw relationships for cross-level back-reference (C1 fix)
            Map<Integer, IArchimateRelationship> createdRelationships = new LinkedHashMap<>();
            // Tracks raw view connections for back-reference by update-view-connection (H1 fix)
            Map<Integer, IDiagramModelArchimateConnection> createdViewConnections = new LinkedHashMap<>();
            // Tracks EMF group objects for parentViewObjectId resolution in add-to-view
            Map<Integer, IDiagramModelGroup> createdGroups = new LinkedHashMap<>();
            // Tracks EMF view objects created by create-view for viewId back-reference resolution
            Map<Integer, IArchimateDiagramModel> createdViews = new LinkedHashMap<>();
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
            // Tracks failed operation indices for back-reference cascade
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
                            createdViewConnections, createdGroups, createdViews);
                    preparedMutations.add(prepared);

                    // Store for future back-references
                    createdEntityIds.put(i, prepared.entityId());

                    // Build per-operation result
                    String action = resolveActionString(op.tool());
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

            // Approval gate: store compound as single proposal
            if (mutationDispatcher.isApprovalRequired(sessionId)) {
                Map<String, Object> proposedChanges = new LinkedHashMap<>();
                proposedChanges.put("operationCount", succeededCount);
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
                // bulk uses reviewed-or-reject (the deferred handle returns the reviewed compound —
                // re-running the whole bulk could differ from what was reviewed). Track each op's
                // pre-existing entity id so the guard rejects-stale if the human edited/removed one;
                // new-create ids are not yet resolvable at propose and are harmlessly skipped.
                ProposalContext ctx = storeAsProposal(sessionId, "bulk-mutate",
                        () -> new PreparedMutation<>(compound, operationResults, null),
                        bulkTargetIds(operationResults, bulkSecondaryTargetIds),
                        operationResults, label,
                        null, proposedChanges, validationMsg, null, intent);
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
        } finally {
            bulkProfileCache.remove();
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
     * Validates a back-reference index. Distinguishes self-reference (index ==
     * current), forward-reference (index > current), and out-of-range (index <
     * 0 or unknown) so agents reading the error get an actionable hint instead
     * of one uniform "future operation" message.
     */
    private void validateBackReference(int refIndex, int currentIndex,
            Map<Integer, String> operationTools) {
        if (refIndex == currentIndex) {
            String suggestion = refIndex > 0
                    ? " Did you mean '$" + (refIndex - 1) + ".id' (the operation immediately before this one)?"
                    : "";
            throw new ModelAccessException(
                    "Back-reference '$" + refIndex + ".id' references the current operation itself "
                            + "(index " + currentIndex + "). Back-references must point to a previous operation."
                            + suggestion,
                    ErrorCode.INVALID_PARAMETER);
        }
        if (refIndex > currentIndex) {
            throw new ModelAccessException(
                    "Back-reference '$" + refIndex + ".id' references a future operation "
                            + "(index " + refIndex + ", current is " + currentIndex + "). "
                            + "Back-references can only point to operations earlier in the batch.",
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
     * Checks if any back-reference in the params targets a failed operation.
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
            Map<Integer, IDiagramModelGroup> createdGroups,
            Map<Integer, IArchimateDiagramModel> createdViews) {
        return switch (tool) {
            case "create-element" -> {
                String type = requireParam(params, "type");
                String name = requireParam(params, "name");
                String documentation = optionalParam(params, "documentation");
                Map<String, String> properties = optionalStringMap(params, "properties");
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
                // G1: semantic attributes for bulk-mutate
                RelationshipSemanticAttributes attrs = readSemanticAttributesFromParams(params);

                // Check if source/target are back-referenced elements (not yet in model)
                IArchimateElement sourceElement = findBackReferencedElement(
                        sourceId, createdElements);
                IArchimateElement targetElement = findBackReferencedElement(
                        targetId, createdElements);

                PreparedMutation<RelationshipDto> prepared;
                if (sourceElement != null && targetElement != null) {
                    prepared = prepareCreateRelationshipDirect(
                            type, sourceElement, targetElement, name, specialization, attrs);
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
                            type, sourceElement, target, name, specialization, attrs);
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
                            type, source, targetElement, name, specialization, attrs);
                } else {
                    // Both are existing model elements — standard path
                    prepared = prepareCreateRelationship(type, sourceId, targetId, name, specialization, attrs);
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
                PreparedMutation<ViewDto> viewPrepared =
                        prepareCreateView(name, viewpoint, folderId, connectionRouterType);
                // Store raw view for viewId back-reference resolution
                if (viewPrepared.rawObject() instanceof IArchimateDiagramModel view) {
                    createdViews.put(operationIndex, view);
                }
                yield viewPrepared;
            }
            case "update-model" -> {
                // G6: bulk-mutate parity for update-model.
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
                String name = optionalParam(params, "name");
                String documentation = optionalParam(params, "documentation");
                Map<String, String> properties = optionalStringMapWithNulls(params, "properties");
                String specialization = optionalParam(params, "specialization");
                if (params.containsKey("specialization") && "".equals(params.get("specialization"))) {
                    specialization = "";
                }
                // G1: semantic attributes for bulk-mutate
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
                Integer x = optionalIntParam(params, "x");
                Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width");
                Integer height = optionalIntParam(params, "height");
                Boolean autoSize = optionalBoolParam(params, "autoSize");
                String parentViewObjectId = optionalParam(params, "parentViewObjectId");
                StylingParams bulkStyling = extractBulkStylingParams(params);
                ImageParams bulkImageParams = extractBulkImageParams(params);

                // Auto-size element to fit label when autoSize=true and no explicit dimensions
                if (Boolean.TRUE.equals(autoSize) && width == null && height == null) {
                    // Check back-referenced element first, then model lookup
                    IArchimateElement backRef = findBackReferencedElement(elementId, createdElements);
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
                IDiagramModelContainer batchParent = findBatchCreatedParentContainer(
                        parentViewObjectId, createdGroups, createdViewObjects);

                // Check if elementId is a back-referenced element (not yet in model)
                IArchimateElement backRefElement = findBackReferencedElement(
                        elementId, createdElements);

                // Resolve batch-created view for viewId back-reference
                IArchimateDiagramModel batchView = findBackReferencedView(viewId, createdViews);

                PreparedMutation<AddToViewResultDto> addToViewPrepared;
                if (backRefElement != null) {
                    addToViewPrepared = prepareAddToViewDirect(viewId, backRefElement,
                            x, y, width, height,
                            batchParent != null ? null : parentViewObjectId,
                            batchParent, bulkStyling, bulkImageParams, batchView);
                } else {
                    // autoConnect forced false in bulk context
                    addToViewPrepared = prepareAddToView(viewId, elementId,
                            x, y, width, height, false,
                            batchParent != null ? null : parentViewObjectId,
                            batchParent, bulkStyling, bulkImageParams, batchView);
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
                StylingParams connStyling = extractBulkStylingParams(params);
                Boolean showLabel = optionalBoolParam(params, "showLabel");
                Integer textPosition = parseBulkLabelPosition(params);

                // C1 fix: check if relationship is a back-referenced relationship
                IArchimateRelationship directRelationship = findBackReferencedRelationship(
                        relationshipId, createdRelationships);

                // Check if source/target are back-referenced view objects
                IDiagramModelArchimateObject sourceViewObj = findBackReferencedViewObject(
                        sourceViewObjectId, createdViewObjects);
                IDiagramModelArchimateObject targetViewObj = findBackReferencedViewObject(
                        targetViewObjectId, createdViewObjects);

                // Resolve batch-created view for viewId back-reference
                IArchimateDiagramModel connBatchView = findBackReferencedView(viewId, createdViews);

                PreparedMutation<ViewConnectionDto> connPrepared;
                if (sourceViewObj != null || targetViewObj != null
                        || directRelationship != null || connBatchView != null) {
                    connPrepared = prepareAddConnectionToViewDirect(
                            viewId, relationshipId,
                            sourceViewObj, sourceViewObjectId,
                            targetViewObj, targetViewObjectId,
                            bendpoints, absoluteBendpoints, directRelationship,
                            connStyling, showLabel, textPosition, connBatchView);
                } else {
                    connPrepared = prepareAddConnectionToView(
                            viewId, relationshipId,
                            sourceViewObjectId, targetViewObjectId,
                            bendpoints, absoluteBendpoints,
                            connStyling, showLabel, textPosition);
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
                ImageParams voImageParams = extractBulkImageParams(params);
                // G4: label-expression flows through bulk-mutate via the
                // generic optional-param helper. Empty string clears (same semantic as
                // the single-tool path); absent key leaves unchanged.
                String voLabelExpression = optionalAllowEmptyParam(params, "labelExpression");

                // H2 fix: check if viewObjectId is a back-referenced view object
                IDiagramModelArchimateObject backRefViewObj = findBackReferencedViewObject(
                        viewObjectId, createdViewObjects);
                if (backRefViewObj != null) {
                    yield prepareUpdateViewObjectDirect(backRefViewObj, x, y, width, height,
                            voStyling, voImageParams, voLabelExpression);
                }
                yield prepareUpdateViewObject(viewObjectId, x, y, width, height, text,
                        voStyling, voImageParams, voLabelExpression, null, null, null, null);
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

                // H1 fix: check if viewConnectionId is a back-referenced view connection
                IDiagramModelArchimateConnection backRefConn = findBackReferencedViewConnection(
                        viewConnectionId, createdViewConnections);
                if (backRefConn != null) {
                    yield prepareUpdateViewConnectionDirect(backRefConn, bendpoints,
                            absoluteBendpoints, connStyling, showLabel, textPosition);
                }
                yield prepareUpdateViewConnection(viewConnectionId, bendpoints,
                        absoluteBendpoints, connStyling, showLabel, textPosition);
            }
            case "set-view-label-expression" ->
                    SetViewLabelExpressionCommand.prepare(requireAndCaptureModel(), params);
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
                ImageParams groupImageParams = extractBulkImageParams(params);

                // Resolve batch-created parent container (group or element)
                IDiagramModelContainer batchParent = findBatchCreatedParentContainer(
                        parentVoId, createdGroups, createdViewObjects);

                // Resolve batch-created view for viewId back-reference
                IArchimateDiagramModel batchView = findBackReferencedView(viewId, createdViews);

                PreparedMutation<ViewGroupDto> groupPrepared =
                        prepareAddGroupToView(viewId, label, x, y, width, height,
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
                String content = requireParam(params, "content");
                Integer x = optionalIntParam(params, "x");
                Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width");
                Integer height = optionalIntParam(params, "height");
                String parentVoId = optionalParam(params, "parentViewObjectId");
                StylingParams noteStyling = extractBulkStylingParams(params);
                ImageParams noteImageParams = extractBulkImageParams(params);

                // Resolve batch-created parent container (group or element)
                IDiagramModelContainer batchParent = findBatchCreatedParentContainer(
                        parentVoId, createdGroups, createdViewObjects);

                // Resolve batch-created view for viewId back-reference
                IArchimateDiagramModel noteBatchView = findBackReferencedView(viewId, createdViews);

                yield prepareAddNoteToView(viewId, content, null, null, x, y, width, height,
                        batchParent != null ? null : parentVoId,
                        batchParent, noteStyling, noteImageParams, noteBatchView);
            }
            case "add-view-reference-to-view" -> {
                String viewId = requireParam(params, "viewId");
                String referencedViewId = requireParam(params, "referencedViewId");
                Integer x = optionalIntParam(params, "x");
                Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width");
                Integer height = optionalIntParam(params, "height");
                String parentVoId = optionalParam(params, "parentViewObjectId");
                StylingParams refStyling = extractBulkStylingParams(params);

                // Resolve batch-created parent container (group or element)
                IDiagramModelContainer batchParent = findBatchCreatedParentContainer(
                        parentVoId, createdGroups, createdViewObjects);

                // Resolve batch-created TARGET view
                IArchimateDiagramModel refBatchView = findBackReferencedView(viewId, createdViews);

                yield prepareAddViewReferenceToView(viewId, referencedViewId, x, y,
                        width, height,
                        batchParent != null ? null : parentVoId,
                        batchParent, refBatchView, refStyling);
            }
            case "add-image-to-view" -> {
                String viewId = requireParam(params, "viewId");
                String imagePath = requireParam(params, "imagePath");
                Integer x = optionalIntParam(params, "x");
                Integer y = optionalIntParam(params, "y");
                Integer width = optionalIntParam(params, "width");
                Integer height = optionalIntParam(params, "height");
                String parentVoId = optionalParam(params, "parentViewObjectId");
                StylingParams imgStyling = extractBulkStylingParams(params);
                // Follow-up: Step 5 borderColor + documentation surface.
                String imgBorderColor = optionalParam(params, "borderColor");
                String imgDocumentation = optionalParam(params, "documentation");

                // Resolve batch-created parent container (group or element)
                IDiagramModelContainer batchParent = findBatchCreatedParentContainer(
                        parentVoId, createdGroups, createdViewObjects);

                // Resolve batch-created TARGET view
                IArchimateDiagramModel imgBatchView = findBackReferencedView(viewId, createdViews);

                yield prepareAddImageToView(viewId, imagePath, x, y, width, height,
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
     * Finds an EMF diagram model from the back-reference map by entity ID.
     * Returns null if the ID doesn't match any back-referenced view.
     */
    private IArchimateDiagramModel findBackReferencedView(String viewId,
            Map<Integer, IArchimateDiagramModel> createdViews) {
        if (viewId == null || createdViews.isEmpty()) return null;
        for (IArchimateDiagramModel view : createdViews.values()) {
            if (view.getId().equals(viewId)) {
                return view;
            }
        }
        return null;
    }

    /**
     * Finds a batch-created parent container (group or element view object) from tracking maps.
     * Checks both createdGroups and createdViewObjects to enable element-to-element nesting
     * within a single bulk-mutate batch.
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
     * Optional batchParentGroup for nesting inside a group created in the same batch.
     * Optional parentViewObjectId for nesting inside an existing group on the view.
     */
    private PreparedMutation<AddToViewResultDto> prepareAddToViewDirect(
            String viewId, IArchimateElement element, Integer x, Integer y,
            Integer width, Integer height, String parentViewObjectId,
            IDiagramModelContainer batchParentContainer, StylingParams styling,
            ImageParams imageParams, IArchimateDiagramModel batchView) {
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
        if ((x == null) != (y == null)) {
            throw new ModelAccessException(
                    "Both x and y must be specified together, or both omitted for auto-placement",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide both x and y coordinates, or omit both for auto-placement",
                    null);
        }

        // Find view — use batch-created view if available
        IArchimateDiagramModel view = batchView;
        if (view == null) {
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel resolvedView)) {
                throw new ModelAccessException(
                        "View not found: " + viewId,
                        ErrorCode.VIEW_NOT_FOUND,
                        null,
                        "Use get-views to find valid view IDs",
                        null);
            }
            view = resolvedView;
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

        // Apply image at creation time (consistent with prepareAddToView)
        ImageHelper.applyImageToNewObject(diagramObj, imageParams);

        // Build view object DTO with styling and image fields
        // (include figureType + textAlignment + verticalTextAlignment;
        // G5: include typography + gradient + borderType + deriveLineColor + outlineOpacity)
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
                null,  // labelExpression — bulk add path does not set
                StylingHelper.readFontName(diagramObj),
                StylingHelper.readFontSize(diagramObj),
                StylingHelper.readFontStyle(diagramObj),
                StylingHelper.readGradient(diagramObj),
                StylingHelper.readBorderType(diagramObj),
                StylingHelper.readDeriveLineColor(diagramObj),
                StylingHelper.readOutlineOpacity(diagramObj),
                StylingHelper.readLineStyle(diagramObj));

        IDiagramModelContainer parentContainer = resolveParentContainer(
                view, parentViewObjectId, batchParentContainer);

        // autoConnect forced false for bulk (no connection scanning)
        Command cmd = new AddToViewCommand(diagramObj, parentContainer);

        // Backlog W2 sibling-symmetric with `prepareAddToView` (see post-wrap
        // block at line ~12035): icon-band parent-resize on the bulk-mutate
        // back-reference path so corner-anchored icons stay un-obscured
        // whether the LLM uses the single-tool surface or the bulk one.
        Command w2ParentResize = computeIconBandParentResizeCommand(
                parentContainer, resolvedX, resolvedY, resolvedWidth, resolvedHeight);
        if (w2ParentResize != null) {
            NonNotifyingCompoundCommand w2Wrap = new NonNotifyingCompoundCommand("Add view object with icon-band parent-resize (bulk-mutate path)");
            w2Wrap.add(w2ParentResize);
            w2Wrap.add(cmd);
            cmd = w2Wrap;
        }
        cmd = RecedeContainerFillCommand.wrap(cmd, parentContainer, styling); // recede null-fill element/group parent (no-op for root view)

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
            IArchimateRelationship directRelationship,
            StylingParams styling, Boolean showLabel, Integer textPosition) {
        return prepareAddConnectionToViewDirect(viewId, relationshipId,
                directSource, sourceViewObjectId, directTarget, targetViewObjectId,
                bendpoints, absoluteBendpoints, directRelationship,
                styling, showLabel, textPosition, null);
    }

    private PreparedMutation<ViewConnectionDto> prepareAddConnectionToViewDirect(
            String viewId, String relationshipId,
            IDiagramModelArchimateObject directSource, String sourceViewObjectId,
            IDiagramModelArchimateObject directTarget, String targetViewObjectId,
            List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints,
            IArchimateRelationship directRelationship,
            StylingParams styling, Boolean showLabel, Integer textPosition,
            IArchimateDiagramModel batchView) {
        IArchimateModel model = requireAndCaptureModel();

        // Find view — use batch-created view if available
        IArchimateDiagramModel view = batchView;
        if (view == null) {
            EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
            if (!(viewObj instanceof IArchimateDiagramModel resolvedView)) {
                throw new ModelAccessException(
                        "View not found: " + viewId,
                        ErrorCode.VIEW_NOT_FOUND,
                        null,
                        "Use get-views to find valid view IDs",
                        null);
            }
            view = resolvedView;
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
        // Skip validation only for same-batch back-referenced relationships —
        // connect() is deferred to command execution, so getSource()/getTarget() are null
        // AND the relationship is not yet in containment (eContainer == null).
        // Committed relationships (eContainer != null) must always be validated.
        IArchimateElement relSource = (IArchimateElement) relationship.getSource();
        IArchimateElement relTarget = (IArchimateElement) relationship.getTarget();
        boolean isSameBatchBackRef = relSource == null && relTarget == null
                && relationship.eContainer() == null;
        if (!isSameBatchBackRef) {
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

        // Apply styling at creation time (G5)
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
            // G5: typography composite + lineStyle bitmask (arrow bits preserved).
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

        Command cmd = new AddConnectionToViewCommand(conn, sourceViewObj, targetViewObj);

        // Build DTO with styling info included in response (G5: add typography;
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

        ViewConnectionDto dto = new ViewConnectionDto(
                baseDto.viewConnectionId(), baseDto.relationshipId(),
                baseDto.relationshipType(), baseDto.sourceViewObjectId(),
                baseDto.targetViewObjectId(), baseDto.bendpoints(),
                baseDto.absoluteBendpoints(), baseDto.sourceAnchor(),
                baseDto.targetAnchor(), baseDto.textPosition(),
                dtoLineColor, dtoLineWidth, dtoFontColor, dtoNameVisible,
                dtoFontName, dtoFontSize, dtoFontStyle);

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
            case "update-model", "update-view", "update-view-object", "update-view-connection", "update-element", "update-relationship", "update-specialization", "set-view-label-expression" -> "updated";
            case "delete-element", "delete-relationship", "delete-view", "delete-folder", "delete-specialization" -> "deleted";
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
        Integer appliedCount = null, skippedCount = null;

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
        } else if (entity instanceof Map<?, ?> map) {
            // Specialization tools return Map<String,Object>
            Object ct = map.get("conceptType");
            Object nm = map.get("name");
            entityType = ct instanceof String s ? "Specialization:" + s : "Specialization";
            entityName = nm instanceof String s ? s : null;
        } else if (entity instanceof SetViewLabelExpressionResultDto dto) {
            entityType = "DiagramModel"; entityName = dto.viewName();
            appliedCount = dto.appliedCount(); skippedCount = dto.skippedCount();
        }

        return new BulkOperationResult(index, tool, action,
                prepared.entityId(), entityType, entityName, appliedCount, skippedCount);
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

        // G5 — typography (fontName allows empty for system-default clear); gradient/borderType/lineStyle allow empty for clear-to-default symmetry.
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
        // Delegate to Archi's own type→folder authority so we are never stricter nor more
        // forgiving than host Archi — reuses the default-folder picker as the validator.
        IArchimateModel folderModel = folder.getArchimateModel();
        IFolder defaultFolder = folderModel == null ? null : folderModel.getDefaultFolderForObject(concept);
        if (defaultFolder == null) {
            // Archi assigns no governing folder to this object (non-ArchiMate / outside layer
            // governance). It is not subject to folder-type checkIntegrity either, so skipping
            // here stays consistent with host Archi — NOT a silent-acceptance gap. Unreachable
            // for IArchimateConcept inputs in practice (every element/relationship maps).
            return;
        }
        FolderType expectedType = defaultFolder.getType();

        IFolder rootFolder = getRootFolder(folder);
        FolderType actualType = rootFolder.getType();

        if (actualType != expectedType) {
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

    // ---- Control-loop adapters (Task 3.5b, 2026-05-15) ----

    /**
     * Converts an {@link AssessLayoutResultDto} into the pure-EMF-free
     * {@link LayoutMetrics} snapshot the {@link SpacingControlLoop} consumes.
     *
     * <p><strong>Decision-A.1.3 = α''' Fix-2 (RC-2), Session 11 (2026-05-16),
     * Task 10.5.</strong> The {@code thresholdsMet} aggregate was the
     * 4-condition binary-at-zero pseudo-aggregate resolved at architecture-spec
     * § 1.10 Appendix Q1 (Session-3): {@code (coincSeg==0) +
     * (M4==0) + (boundaryViolations.isEmpty()) + (HPQ>=0.75)}, range [0,4]. RC-2
     * (Task 10.2 root-cause diagnosis): on dense gate views the {@code M4==0} /
     * {@code coincSeg==0} bits are dead weight (M4 stays 3–18), collapsing the
     * signal to a hypersensitive 2-bit proxy → deterministic iteration-0 revert
     * across Sessions 6–10. It is now the graded intrinsic
     * {@link LayoutQualityScalar#qualityScalar} (range [0,
     * {@value LayoutQualityScalar#MAX_QUALITY_SCALAR}]) per the Task 10.4
     * ratified design
     * (§ 3.2). The back-off predicate
     * {@link SpacingControlLoop#acceptStepDecision} is UNCHANGED — it still
     * compares this value as a single opaque scalar; only the range widens
     * [0,4] → [0,12], preserving the aggregate-not-per-metric discipline.</p>
     */
    private static LayoutMetrics toLayoutMetrics(AssessLayoutResultDto a) {
        int boundaryViolationsCount = (a.boundaryViolations() == null)
                ? 0 : a.boundaryViolations().size();
        int passThroughCount = (a.connectionPassThroughs() == null)
                ? 0 : a.connectionPassThroughs().size();
        int thresholdsMet = LayoutQualityScalar.qualityScalar(
                boundaryViolationsCount,
                passThroughCount,
                a.overlapCount(),
                a.connectionEdgeCoincidenceCount(),
                a.coincidentSegmentCount(),
                a.hubPortQualityScore());
        double vp10 = (a.vAxisParallelGapP10() == null)
                ? 0.0 : a.vAxisParallelGapP10();
        return new LayoutMetrics(
                thresholdsMet,
                a.hubPortQualityScore(),
                a.connectionEdgeCoincidenceCount(),
                a.coincidentSegmentCount(),
                boundaryViolationsCount,
                vp10,
                a.edgeCrossingCount(),
                // The spacing-regime-position axis input, sourced
                // from the EXISTING assess read (NOT a new
                // LayoutQualityAssessor metric). Canonical 8-arg form — every
                // OTHER `new LayoutMetrics(...)` site keeps the 7-arg
                // delegating ctor (avgSpacingPx = NaN → density discriminator
                // inert → row-703 pin baseline preserved).
                a.averageSpacing());
    }

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
     * infeasibility certificate (Lever B).
     * Sibling-symmetric with the shipped {@code rnb.degraded()} pre-loop
     * short-circuit: a pure pre-loop test ⇒ DTO-return-without-loop-entry.
     *
     * <p>Computes the element-union canvas geometry over the view's
     * <strong>ArchiMate elements ONLY</strong> (groups / notes excluded — the
     * Task-0.3 calibration N) using the SAME absolute-coordinate convention
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
                    n, area, avgBoxDim, measuredAvg, hubW, hubH, hubC);
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
     * (Fix-2: the
     * <strong>fan-out-scaled</strong> {@code ≥
     * SpacingControlLoop.requiredHubMinWidthPx(conns) ×
     * requiredHubMinHeightPx(conns)} — the SAME minimum the predicate
     * {@code hubUnderSizedForFanOut} uses, NOT the flat 300×250 base; a
     * flagged hub therefore ALWAYS resizes strictly larger) via
     * the EXISTING {@link UpdateViewObjectCommand} (clean — the
     * convenience-tool mutation type {@code computeAdjustViewSpacing}
     * already emits for group bounds; NOT a {@code RoutingPipeline} /
     * sibling primitive), wrapped in the SWT-dispatch
     * {@link GefSpacingMutationCommand} so it inherits the row-703 Session-9
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
            // Fix-2 — the
            // resize target reads the SAME fan-out-scaled minimum the
            // predicate {@code hubUnderSizedForFanOut} just used (keyed off
            // THIS hub's connection count), NOT the flat 300×250 base. This
            // is the predicate↔resize-target consistency invariant: a hub the
            // predicate flagged as under-sized for its fan-out ALWAYS resizes
            // to a strictly-larger target (≥1 dimension grows), so escalate
            // never degrades to a no-op loop on the very hub it diagnosed.
            int conns = he.maxHubConnectionCount();
            int newW = Math.max(b.getWidth(),
                    SpacingControlLoop.requiredHubMinWidthPx(conns));
            int newH = Math.max(b.getHeight(),
                    SpacingControlLoop.requiredHubMinHeightPx(conns));
            if (newW == b.getWidth() && newH == b.getHeight()) {
                return null; // already adequate — nothing to resize
            }
            Command resize = new UpdateViewObjectCommand(
                    dmo, b.getX(), b.getY(), newW, newH);
            // postMetrics unused for the hub-resize adapter — the loop only
            // drives execute()/undo() on it; observeLayout() reads the
            // spacing command's cached metrics.
            return new GefSpacingMutationCommand(resize, /*postMetrics=*/ null);
        } catch (RuntimeException e) {
            logger.warn("buildDensityHubResizeCommand failed (viewId={}); "
                    + "escalation degrades to spacing-only", viewId, e);
            return null;
        }
    }

    /**
     * Fix-1 (RC-1) carrier — Decision-A.1.3 = α''', Session 11 (2026-05-16),
     * Task 10.5. Either the route-normalized baseline metrics ({@code degraded
     * == false}) OR a signal that the tool's own reroute pass materially
     * degraded the input baseline ({@code degraded == true}; {@code metrics}
     * carries the bare input metrics, returned untouched per the
     * guarded-form safety net — see
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
     * Fix-1 (RC-1) — Decision-A.1.3 = α''', Session 11 (2026-05-16), Task
     * 10.5. Route-normalizes the pre-loop baseline so it is measured on the
     * SAME routing basis as every per-step {@code postState}.
     *
     * <p><strong>Root cause (Task 10.2 RC-1).</strong> The accessor closures
     * seeded {@code SpacingControlLoop.iterate}'s {@code bestState =
     * request.initialMetrics()} from {@code toLayoutMetrics(before)} where
     * {@code before = assessLayout(viewId)} — a bare, <em>un-rerouted</em>
     * post-hub-resize assessment. Every per-step {@code postState} is
     * <em>freshly re-routed</em> ({@code computeAdjustViewSpacing} step 8).
     * The STOP predicate compared states on different routing bases, so
     * the first rerouted step ≈ always strictly regressed vs the un-rerouted
     * baseline → the deterministic
     * {@code aggregate_threshold_regressed_at_iteration_0_reverted_to_initial_state}
     * symptom (100% of Arm B convenience-tool calls, Sessions 6–10).</p>
     *
     * <p><strong>Fix.</strong> Capture the baseline via the SAME temp-route →
     * assess → undo dance {@link #computeAdjustViewSpacing} performs (steps
     * 8–10) with NO spacing delta (route-only). Lives in the accessor (EMF)
     * layer — preserves the EMF-free invariant ({@link SpacingControlLoop} stays pure-EMF-free;
     * route-normalization never enters {@code iterate}). The temp route
     * dispatch is undone in a {@code finally} → ZERO net mutation leaked
     * (Task 10.6 T1 pins zero leak; the route compound is never added to
     * any accepted-commands compound).</p>
     *
     * <p><strong>Guarded form (§ 2).</strong> If the
     * route-normalized baseline scores a strictly lower
     * {@link LayoutMetrics#thresholdsMet()} than the bare baseline, the
     * tool's own reroute degraded the input — return
     * {@link RouteNormalizedBaseline#degraded} so the caller returns the bare
     * input untouched with
     * {@link SpacingControlLoop#REASON_REROUTE_DEGRADED_INPUT_BASELINE},
     * preserving the accidental safety net deliberately. If
     * {@code computeAutoRoutePass} returns null (nothing to route) or throws,
     * fall back to the bare baseline (route-only normalization was a no-op or
     * unavailable; bare == normalized basis).</p>
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
        LayoutMetrics bare = toLayoutMetrics(bareBefore);
        EObject viewObj = ArchimateModelUtils.getObjectByID(model, viewId);
        if (!(viewObj instanceof IArchimateDiagramModel diagramModel)) {
            return RouteNormalizedBaseline.ok(bare);
        }
        AssessLayoutResultDto routeNormAssessment;
        try {
            AutoRoutePassResult routeResult =
                    computeAutoRoutePass(viewId, diagramModel, model);
            if (routeResult == null) {
                // Nothing to route — bare basis == route-normalized basis.
                return RouteNormalizedBaseline.ok(bare);
            }
            // zero-leak: the temp route dispatch is undone iff it
            // actually happened. The `dispatched` counter-guard (matching
            // the established `undoCount` idiom in computeAdjustViewSpacing
            // steps 7-10) makes the undo UNCONDITIONAL for every path where
            // dispatchImmediate succeeded — and a no-op (correctly) when
            // dispatchImmediate itself threw before mutating, so no phantom
            // undo of a non-dispatch. Addresses review Finding 1
            // (Session 11 cross-model review).
            boolean dispatched = false;
            try {
                mutationDispatcher.dispatchImmediate(routeResult.compound);
                dispatched = true;
                routeNormAssessment = assessLayout(viewId);
            } finally {
                if (dispatched) {
                    mutationDispatcher.undo(1);
                }
            }
        } catch (RuntimeException e) {
            logger.warn("Route-normalized baseline pass failed "
                    + "(viewId={}); falling back to bare baseline", viewId, e);
            return RouteNormalizedBaseline.ok(bare);
        }
        LayoutMetrics routeNorm = toLayoutMetrics(routeNormAssessment);
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
     * Maps an entry-guard short-circuit (from {@code ApplyXxxDecision.decide}
     * or {@code ApplySpacingDecision.decide}) to the (d)/(e)
     * {@code terminationReason} taxonomy string per architecture-spec § 1.5.
     *
     * <p>Inputs:
     * <ul>
     *   <li>{@code dryRun} — true → {@code "dry_run_recommendation_not_applied"} (the (e) sub-string).</li>
     *   <li>{@code noChangeReason} — the decision record's reason string;
     *       mapped verbatim or prefixed with {@code structural_no_change_}.</li>
     * </ul></p>
     *
     * <p>The decision-record's {@code noChangeReason} strings are
     * human-meaningful (e.g., "Current spacing already meets/exceeds heuristic")
     * and surface verbatim to the LLM agent via the
     * {@code structural_no_change_<reason>} prefix. Dry-run + heuristic-already-
     * met fall under the (e) sub-string family.</p>
     */
    private static String mapEntryGuardToTerminationReason(
            boolean dryRun, String noChangeReason) {
        if (dryRun) {
            return "dry_run_recommendation_not_applied";
        }
        if (noChangeReason == null) {
            return "structural_no_change_unknown";
        }
        String lower = noChangeReason.toLowerCase();
        if (lower.contains("already meets") || lower.contains("already at")
                || lower.contains("already exceeds")) {
            return SpacingControlLoop.REASON_HEURISTIC_ALREADY_MET;
        }
        return "structural_no_change_"
                + noChangeReason
                        .replaceAll("[^A-Za-z0-9]+", "_")
                        .toLowerCase()
                        .replaceAll("^_+|_+$", "");
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
         * {@link SwtUiThreadDispatcher#runOnUiThread} — Decision-A.1.2 = α''
         * targeted fix (Session 9, 2026-05-15) for the Sessions 6-8
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
     * Stores a proposal: it holds a <strong>deferred rebuild handle</strong> ({@code rebuild},
     * a {@code Supplier<PreparedMutation<?>>} that re-invokes the same {@code prepareXxx(...)} against the
     * current model at approve-time) plus a {@link StalenessCapture} over {@code targetIds} — never a
     * frozen pre-built command. The propose-time {@code entity}/{@code effectDescription}/{@code
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
        StalenessCapture capture = mutationDispatcher.captureStaleness(targetIds);
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

    /**
     * Tracked-id set for a {@code bulk-mutate} proposal (disposition (a)). Unions
     * each op's pre-existing entity id with the resolvable secondary endpoint ids the
     * batch touches — the source/target element ids of create-relationship ops, captured from the prepared
     * {@code RelationshipDto} at propose. An endpoint id naming a just-created element ({@code $N.id}
     * back-reference) is not yet resolvable and is harmlessly skipped by {@link ProposalStalenessGuard#capture};
     * created relationship/connection ids stay on the {@link ProposalBuilder} rebuild-throw safety net
     * (rebuild-throw safety net).
     */
    private static Set<String> bulkTargetIds(List<BulkOperationResult> operationResults,
            Set<String> secondaryTargetIds) {
        Set<String> ids = targetIds(operationResults.stream()
                .map(BulkOperationResult::entityId).toArray(String[]::new));
        if (secondaryTargetIds != null) {
            for (String id : secondaryTargetIds) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
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
        List<Map<String, String>> properties = DtoMapper.convertProperties(element.getProperties());

        String documentation = element.getDocumentation();
        if (documentation != null && documentation.isEmpty()) {
            documentation = null;
        }

        IProfile primaryProfile = element.getPrimaryProfile();
        String specialization = (primaryProfile != null) ? primaryProfile.getName() : null;

        return ElementDto.standard(
                element.getId(),
                element.getName(),
                type,
                specialization,
                layer,
                documentation,
                properties.isEmpty() ? null : properties);
    }

    /**
     * Converts an EMF {@link IArchimateRelationship} to a {@link RelationshipDto}.
     *
     * <p>For ArchiMate semantic-attribute subtypes (Access / Association / Influence),
     * the matching G1 field is populated on the DTO.</p>
     */
    RelationshipDto convertToRelationshipDto(IArchimateRelationship relationship) {
        IProfile primaryProfile = relationship.getPrimaryProfile();
        String specialization = (primaryProfile != null) ? primaryProfile.getName() : null;
        return new RelationshipDto(
                relationship.getId(),
                relationship.getName(),
                relationship.eClass().getName(),
                specialization,
                relationship.getSource() != null ? relationship.getSource().getId() : null,
                relationship.getTarget() != null ? relationship.getTarget().getId() : null,
                false, null, null, null, null,
                accessTypeForDto(relationship),
                associationDirectedForDto(relationship),
                influenceStrengthForDto(relationship));
    }

    // ==================== G1: semantic-attribute helpers ====================

    /** Max-length cap for {@code influenceStrength} (mirrors documentation field convention). */
    static final int INFLUENCE_STRENGTH_MAX_LEN = 255;

    /**
     * Maps an MCP wire-vocabulary {@code accessType} string to the EMF
     * {@code IAccessRelationship} named-constant int. Throws on invalid value.
     * Uses named constants (WRITE=0, READ=1, UNSPECIFIED=2, READWRITE=3).
     */
    private static int resolveAccessTypeInt(String wireValue) {
        return switch (wireValue) {
            case "access" -> IAccessRelationship.UNSPECIFIED_ACCESS;
            case "read" -> IAccessRelationship.READ_ACCESS;
            case "write" -> IAccessRelationship.WRITE_ACCESS;
            case "readwrite" -> IAccessRelationship.READ_WRITE_ACCESS;
            default -> throw new ModelAccessException(
                    "Invalid accessType '" + wireValue + "'. Valid: access, read, write, readwrite.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use one of: access, read, write, readwrite (or omit to leave unchanged)",
                    null);
        };
    }

    /**
     * Maps an EMF {@code IAccessRelationship} int back to the MCP wire-vocabulary string.
     */
    private static String resolveAccessTypeString(int rawInt) {
        return switch (rawInt) {
            case IAccessRelationship.WRITE_ACCESS -> "write";
            case IAccessRelationship.READ_ACCESS -> "read";
            case IAccessRelationship.UNSPECIFIED_ACCESS -> "access";
            case IAccessRelationship.READ_WRITE_ACCESS -> "readwrite";
            default -> "access";  // graceful fall-back for forward-compat
        };
    }

    /**
     * Validates semantic attributes for {@code create-relationship}. Type-conditional
     * rejection at the prepare boundary BEFORE any EMF object is created.
     */
    private static void validateSemanticAttributesForCreate(
            RelationshipSemanticAttributes attrs, EClass relClass) {
        if (attrs == null || !attrs.hasAny()) {
            return;
        }
        EClass accessRelEClass = IArchimatePackage.eINSTANCE.getAccessRelationship();
        EClass assocRelEClass = IArchimatePackage.eINSTANCE.getAssociationRelationship();
        EClass influenceRelEClass = IArchimatePackage.eINSTANCE.getInfluenceRelationship();

        if (attrs.accessType() != null) {
            if (!accessRelEClass.isSuperTypeOf(relClass)) {
                throw new ModelAccessException(
                        "accessType only applies to AccessRelationship; got "
                                + relClass.getName() + ".",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Use type='AccessRelationship' to apply accessType, or remove the accessType parameter.",
                        null);
            }
            if (attrs.accessType().isEmpty()) {
                throw new ModelAccessException(
                        "accessType cannot be empty. Use 'access' for unspecified, or omit to leave unchanged.",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Use 'access' for unspecified, or omit to leave unchanged.",
                        null);
            }
            // Enum check (also throws INVALID_PARAMETER for unknown values)
            resolveAccessTypeInt(attrs.accessType());
        }

        if (attrs.associationDirected() != null && !assocRelEClass.isSuperTypeOf(relClass)) {
            throw new ModelAccessException(
                    "associationDirected only applies to AssociationRelationship; got "
                            + relClass.getName() + ".",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use type='AssociationRelationship' to apply associationDirected, "
                            + "or remove the associationDirected parameter.",
                    null);
        }

        if (attrs.influenceStrength() != null) {
            if (!influenceRelEClass.isSuperTypeOf(relClass)) {
                throw new ModelAccessException(
                        "influenceStrength only applies to InfluenceRelationship; got "
                                + relClass.getName() + ".",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Use type='InfluenceRelationship' to apply influenceStrength, "
                                + "or remove the influenceStrength parameter.",
                        null);
            }
            if (attrs.influenceStrength().length() > INFLUENCE_STRENGTH_MAX_LEN) {
                throw new ModelAccessException(
                        "influenceStrength exceeds " + INFLUENCE_STRENGTH_MAX_LEN + " characters.",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Provide influenceStrength of up to " + INFLUENCE_STRENGTH_MAX_LEN + " characters.",
                        null);
            }
        }
    }

    /**
     * Validates semantic attributes for {@code update-relationship} against the
     * RESOLVED relationship's actual class.
     */
    private static void validateSemanticAttributesForUpdate(
            RelationshipSemanticAttributes attrs, IArchimateRelationship relationship) {
        if (attrs == null || !attrs.hasAny()) {
            return;
        }
        String actualClass = relationship.eClass().getName();

        if (attrs.accessType() != null) {
            if (!(relationship instanceof IAccessRelationship)) {
                throw new ModelAccessException(
                        "accessType only applies to AccessRelationship; got " + actualClass + ".",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Omit accessType, or update a relationship of type AccessRelationship.",
                        null);
            }
            if (attrs.accessType().isEmpty()) {
                throw new ModelAccessException(
                        "accessType cannot be empty. Use 'access' for unspecified, or omit to leave unchanged.",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Use 'access' for unspecified, or omit to leave unchanged.",
                        null);
            }
            resolveAccessTypeInt(attrs.accessType());
        }

        if (attrs.associationDirected() != null && !(relationship instanceof IAssociationRelationship)) {
            throw new ModelAccessException(
                    "associationDirected only applies to AssociationRelationship; got "
                            + actualClass + ".",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Omit associationDirected, or update a relationship of type AssociationRelationship.",
                    null);
        }

        if (attrs.influenceStrength() != null) {
            if (!(relationship instanceof IInfluenceRelationship)) {
                throw new ModelAccessException(
                        "influenceStrength only applies to InfluenceRelationship; got "
                                + actualClass + ".",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Omit influenceStrength, or update a relationship of type InfluenceRelationship.",
                        null);
            }
            if (attrs.influenceStrength().length() > INFLUENCE_STRENGTH_MAX_LEN) {
                throw new ModelAccessException(
                        "influenceStrength exceeds " + INFLUENCE_STRENGTH_MAX_LEN + " characters.",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Provide influenceStrength of up to " + INFLUENCE_STRENGTH_MAX_LEN + " characters.",
                        null);
            }
        }
    }

    /**
     * Applies G1 semantic attributes to the EMF relationship. Called from
     * {@code prepareCreateRelationship} after validation passes.
     */
    private static void applyG1AttributesToRelationship(
            IArchimateRelationship relationship, RelationshipSemanticAttributes attrs) {
        if (attrs == null || !attrs.hasAny()) {
            return;
        }
        if (attrs.accessType() != null && relationship instanceof IAccessRelationship ar) {
            ar.setAccessType(resolveAccessTypeInt(attrs.accessType()));
        }
        if (attrs.associationDirected() != null && relationship instanceof IAssociationRelationship asr) {
            asr.setDirected(attrs.associationDirected());
        }
        if (attrs.influenceStrength() != null && relationship instanceof IInfluenceRelationship ir) {
            ir.setStrength(attrs.influenceStrength());
        }
    }

    /**
     * Reads the G1 semantic-attribute params from a bulk-mutate operation's
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

    /**
     * Populates the DTO {@code accessType} field for the given relationship.
     * Always populates when the relationship is an AccessRelationship (the int field
     * always has a value — defaults to {@code 0 = WRITE_ACCESS} on fresh objects);
     * returns {@code null} otherwise so {@code @JsonInclude(NON_NULL)} omits the field.
     */
    private static String accessTypeForDto(IArchimateRelationship relationship) {
        if (relationship instanceof IAccessRelationship ar) {
            return resolveAccessTypeString(ar.getAccessType());
        }
        return null;
    }

    /**
     * Populates the DTO {@code associationDirected} field for the given relationship.
     * Always populates when the relationship is an AssociationRelationship (the
     * boolean field always has a value — defaults to {@code false} on fresh objects).
     */
    private static Boolean associationDirectedForDto(IArchimateRelationship relationship) {
        if (relationship instanceof IAssociationRelationship asr) {
            return asr.isDirected();
        }
        return null;
    }

    /**
     * Populates the DTO {@code influenceStrength} field for the given relationship.
     * Populates only when non-null and non-empty (mirrors the documentation-field
     * null/empty normalisation pattern used elsewhere in this class).
     */
    private static String influenceStrengthForDto(IArchimateRelationship relationship) {
        if (relationship instanceof IInfluenceRelationship ir) {
            String s = ir.getStrength();
            if (s == null || s.isEmpty()) {
                return null;
            }
            return s;
        }
        return null;
    }

    // ==================== End G1 helpers ====================

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
                        StylingHelper.readLineStyle(noteObj)));
                continue; // Notes are not containers, no recursion needed
            }

            // G16: Collect IDiagramModelImage visuals (leaf — no recursion).
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
                        relationships.add(convertToRelationshipDto(rel));
                    }
                    // ViewConnectionDto: every visual connection collected (each has unique ID)
                    List<BendpointDto> relativeBps = ConnectionResponseBuilder.collectBendpoints(archimateConn);

                    // Compute absolute bendpoints and anchor points
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
                            StylingHelper.readConnectionFontColor(archimateConn),
                            StylingHelper.readConnectionNameVisible(archimateConn),
                            StylingHelper.readConnectionFontName(archimateConn),
                            StylingHelper.readConnectionFontSize(archimateConn),
                            StylingHelper.readConnectionFontStyle(archimateConn),
                            StylingHelper.readConnectionLabelExpression(archimateConn), StylingHelper.readConnectionRelativePosition(archimateConn)));
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

    // ---- Image management ----

    @Override
    public AddImageResultDto addImageToModel(String sessionId, byte[] imageData, String filenameHint) {
        return imageOps.addImageToModel(sessionId, imageData, filenameHint);
    }

    @Override
    public AddImageResultDto addImageFromFilePath(String sessionId, String filePath) {
        return imageOps.addImageFromFilePath(sessionId, filePath);
    }

    // ---- Bounded URL image download (closes audit finding P2) ------------------------
    // The connect/request timeouts and the 1 MB cap already existed; the residual gap was
    // *allocation order* — BodyHandlers.ofByteArray() buffered the WHOLE body into a byte[]
    // BEFORE the size check, so a chunked / no-Content-Length body that lies about (or omits)
    // its size could stream gigabytes and OOM the JVM that is Archi (same blast-radius class
    // as audit S3, closed at the Jetty layer). These bounds make the cap gate the
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

        // Validate URL syntax early (M3: clear error for malformed URLs)
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

            // Detect extension from URL path
            String ext = "png";
            String urlPath = uri.getPath();
            if (urlPath != null && urlPath.contains(".")) {
                ext = urlPath.substring(urlPath.lastIndexOf('.') + 1).toLowerCase();
                if (ext.length() > 5 || ext.contains("/")) {
                    ext = "png"; // fallback for weird URLs
                }
            }

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
                        "The URL does not point to a valid image. Supported formats: PNG, JPEG, GIF, BMP, ICO, TIFF.",
                        null);
            }
            throw new ModelAccessException(
                    "Failed to download image from URL: " + e.getMessage(),
                    ErrorCode.INTERNAL_ERROR,
                    e.getMessage(),
                    null,
                    null);
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

    private String computePostImagePath(IDiagramModelObject diagramObj, ImageParams imageParams) {
        if (imageParams == null || imageParams.imagePath() == null) {
            return ImageHelper.readImagePath(diagramObj);
        }
        return imageParams.imagePath().isEmpty() ? null : imageParams.imagePath();
    }

    private String computePostImagePosition(IDiagramModelObject diagramObj, ImageParams imageParams) {
        if (imageParams == null || imageParams.imagePosition() == null) {
            return ImageHelper.readImagePosition(diagramObj);
        }
        return imageParams.imagePosition();
    }

    private String computePostShowIcon(IDiagramModelObject diagramObj, ImageParams imageParams) {
        if (imageParams == null || imageParams.showIcon() == null) {
            return ImageHelper.readShowIcon(diagramObj);
        }
        return imageParams.showIcon();
    }

    /**
     * Computes image coverage percentage given an archive image path and element dimensions.
     * Returns null if dimensions cannot be determined.
     */
    private Double computeImageCoverage(String imagePath, int elementWidth, int elementHeight) {
        if (imagePath == null || imagePath.isEmpty()) return null;
        try {
            IArchimateModel model = requireAndCaptureModel();
            IArchiveManager archiveManager = (IArchiveManager) model.getAdapter(IArchiveManager.class);
            if (archiveManager == null) return null;
            ImageData data = archiveManager.createImageData(imagePath);
            if (data == null) return null;
            double coverage = ImageHelper.calculateCoverage(data.width, data.height, elementWidth, elementHeight);
            return Math.round(coverage * 10.0) / 10.0; // round to 1 decimal
        } catch (Exception e) {
            logger.debug("Could not compute image coverage for path: {}", imagePath, e);
            return null;
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

