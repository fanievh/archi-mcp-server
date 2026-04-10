package net.vheerden.archi.mcp.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;

/**
 * Shared stub accessor with linked test data for integration tests.
 *
 * <p>Model info references 2 elements, 1 relationship, 1 view.
 * Views list returns view "view-int-1".
 * View contents for "view-int-1" returns elements "elem-int-1" and "elem-int-2".
 * getElementById returns full ElementDto for known IDs.</p>
 *
 * <p>When constructed with {@code modelLoaded=false}, all query methods
 * throw {@link NoModelLoadedException}.</p>
 */
class IntegrationStubAccessor extends BaseTestAccessor {

    IntegrationStubAccessor(boolean modelLoaded) {
        super(modelLoaded);
    }

    @Override
    public ModelInfoDto getModelInfo() {
        if (!isModelLoaded()) throw new NoModelLoadedException();
        Map<String, Integer> typeDist = new LinkedHashMap<>();
        typeDist.put("ApplicationComponent", 2);
        Map<String, Integer> relTypeDist = new LinkedHashMap<>();
        relTypeDist.put("ServingRelationship", 1);
        Map<String, Integer> layerDist = new LinkedHashMap<>();
        layerDist.put("Application", 2);
        return new ModelInfoDto("Integration Test Model", 2, 1, 1, 0,
                typeDist, relTypeDist, layerDist);
    }

    @Override
    public Optional<ElementDto> getElementById(String id) {
        if (!isModelLoaded()) throw new NoModelLoadedException();
        if ("elem-int-1".equals(id)) {
            return Optional.of(ElementDto.standard(
                    "elem-int-1", "Customer Portal", "ApplicationComponent",
                    null, "Application", "Main web portal", List.of()));
        }
        if ("elem-int-2".equals(id)) {
            return Optional.of(ElementDto.standard(
                    "elem-int-2", "API Gateway", "ApplicationComponent",
                    null, "Application", "REST API gateway", List.of()));
        }
        return Optional.empty();
    }

    @Override
    public List<ViewDto> getViews(String viewpointFilter) {
        if (!isModelLoaded()) throw new NoModelLoadedException();
        return List.of(new ViewDto("view-int-1", "Default View", null, "Views"));
    }

    @Override
    public Optional<ViewContentsDto> getViewContents(String viewId) {
        if (!isModelLoaded()) throw new NoModelLoadedException();
        if ("view-int-1".equals(viewId)) {
            List<ElementDto> elements = List.of(
                    ElementDto.standard("elem-int-1", "Customer Portal",
                            "ApplicationComponent", null, "Application", "Main web portal", List.of()),
                    ElementDto.standard("elem-int-2", "API Gateway",
                            "ApplicationComponent", null, "Application", "REST API gateway", List.of()));
            List<RelationshipDto> relationships = List.of(
                    new RelationshipDto("rel-int-1", "Serves", "ServingRelationship",
                            "elem-int-1", "elem-int-2"));
            List<ViewNodeDto> visualMetadata = List.of(
                    new ViewNodeDto("vo-int-1", "elem-int-1", 100, 50, 120, 55),
                    new ViewNodeDto("vo-int-2", "elem-int-2", 300, 50, 120, 55));
            List<ViewConnectionDto> connections = List.of(
                    new ViewConnectionDto("vc-int-1", "rel-int-1", "ServingRelationship",
                            "vo-int-1", "vo-int-2", List.of()));
            return Optional.of(new ViewContentsDto(
                    "view-int-1", "Default View", null,
                    elements, relationships, visualMetadata, connections));
        }
        return Optional.empty();
    }

    @Override
    public List<RelationshipDto> getRelationshipsForElement(String elementId) {
        if (!isModelLoaded()) throw new NoModelLoadedException();
        RelationshipDto rel = new RelationshipDto("rel-int-1", "Serves",
                "ServingRelationship", "elem-int-1", "elem-int-2");
        if ("elem-int-1".equals(elementId) || "elem-int-2".equals(elementId)) {
            return List.of(rel);
        }
        return List.of();
    }

    @Override
    public List<ElementDto> getElementsByIds(List<String> ids) {
        if (!isModelLoaded()) throw new NoModelLoadedException();
        return ids.stream()
                .map(this::getElementById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    @Override
    public List<RelationshipDto> searchRelationships(String query, String typeFilter,
                                                      String sourceLayerFilter, String targetLayerFilter,
                                                      String specializationFilter) {
        if (!isModelLoaded()) throw new NoModelLoadedException();
        String lowerQuery = query.toLowerCase();
        RelationshipDto rel = new RelationshipDto("rel-int-1", "Serves",
                "ServingRelationship", "elem-int-1", "elem-int-2");
        List<RelationshipDto> all = List.of(rel);
        return all.stream()
                .filter(r -> typeFilter == null || typeFilter.equals(r.type()))
                .filter(r -> r.name() == null || r.name().toLowerCase().contains(lowerQuery)
                        || lowerQuery.isEmpty())
                .toList();
    }

    @Override
    public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter,
                                           String specializationFilter) {
        if (!isModelLoaded()) throw new NoModelLoadedException();
        String lowerQuery = query.toLowerCase();
        List<ElementDto> all = List.of(
                ElementDto.standard("elem-int-1", "Customer Portal",
                        "ApplicationComponent", null, "Application", "Main web portal", List.of()),
                ElementDto.standard("elem-int-2", "API Gateway",
                        "ApplicationComponent", null, "Application", "REST API gateway", List.of()));
        return all.stream()
                .filter(e -> typeFilter == null || typeFilter.equals(e.type()))
                .filter(e -> layerFilter == null || layerFilter.equals(e.layer()))
                .filter(e -> e.name().toLowerCase().contains(lowerQuery)
                        || (e.documentation() != null && e.documentation().toLowerCase().contains(lowerQuery)))
                .toList();
    }

    @Override
    public String getModelVersion() { return "integration-v1"; }

    @Override
    public Optional<String> getCurrentModelName() {
        return isModelLoaded() ? Optional.of("Integration Test Model") : Optional.empty();
    }

    @Override
    public Optional<String> getCurrentModelId() {
        return isModelLoaded() ? Optional.of("integration-test-id") : Optional.empty();
    }
}
