package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IApplicationElement;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBusinessElement;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IImplementationMigrationElement;
import com.archimatetool.model.IInfluenceRelationship;
import com.archimatetool.model.IMotivationElement;
import com.archimatetool.model.IPhysicalElement;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.IStrategyElement;
import com.archimatetool.model.ITechnologyElement;

import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Pure {@code EObject -> DTO} mappers for elements, views, relationships, and
 * properties.
 *
 * <p>Extracted from ArchiModelAccessorImpl to improve cohesion. Every method is a
 * pure function over the EMF object(s) it is handed — no model spine, no shared
 * mutable state, no instance back-reference to the accessor. Values that require
 * cross-cluster helpers retained by the accessor (the resolved layer string and
 * the relationship semantic attributes) are passed in by the caller.</p>
 *
 * <p>Package-visible, and used by both the accessor facade and {@code BulkResultProjection}. The
 * projection reads a concept after the bulk compound has been dispatched and must describe it in
 * the same words the single-tool caller is given; sharing these mappers is what makes that true by
 * construction rather than by two readers agreeing for now.</p>
 */
final class DtoMapper {

    private static final Logger logger = LoggerFactory.getLogger(DtoMapper.class);

    private DtoMapper() {}

    /**
     * Converts an EMF {@link IArchimateElement} to an {@link ElementDto}.
     */
    static ElementDto convertToElementDto(IArchimateElement element) {
        String documentation = element.getDocumentation();
        if (documentation != null && documentation.isEmpty()) {
            documentation = null;
        }
        List<Map<String, String>> properties = convertProperties(element.getProperties());
        IProfile primaryProfile = element.getPrimaryProfile();
        return ElementDto.standard(
                element.getId(),
                element.getName(),
                element.eClass().getName(),
                (primaryProfile != null) ? primaryProfile.getName() : null,
                resolveLayer(element),
                documentation,
                properties.isEmpty() ? null : properties);
    }

    /**
     * Converts an EMF {@link IArchimateRelationship} to a {@link RelationshipDto}.
     *
     * <p>For ArchiMate semantic-attribute subtypes (Access / Association / Influence),
     * the matching semantic-attribute field is populated on the DTO.</p>
     *
     * <p>{@code forMutationResponse} selects the empty-string semantics, and is why this stays
     * ONE mapper rather than two. A read caller wants an absent field where the model holds
     * {@code ""}: a concept's documentation defaults to the empty string and is never null, so
     * without the strip {@code @JsonInclude(NON_NULL)} would put a {@code "documentation": ""}
     * on every row it returns. A caller that has just written the relationship needs the
     * opposite — a cleared value must arrive PRESENT-AND-EMPTY, because an omitted key and a key
     * holding {@code ""} are indistinguishable to an agent that cannot see the model, and one of
     * them silently reads as "unchanged". That is the same ruling the input wire already makes
     * for this field, applied to the output wire.</p>
     *
     * <p>Everything else is populated identically for both, including the resolved endpoint NAMES.
     * What keeps those from growing every row of the list-returning read tools is the field
     * preset, not this mapper: {@code documentation}, {@code properties}, {@code sourceName} and
     * {@code targetName} are named by {@code RELATIONSHIP_FULL} alone, so a read caller receives
     * them only by asking for {@code fields:"full"}. Mutation responses are not field-selected at
     * all, which is why the empty-string distinction above is the only thing this flag decides.</p>
     */
    static RelationshipDto convertToRelationshipDto(IArchimateRelationship relationship,
            boolean forMutationResponse) {
        IProfile primaryProfile = relationship.getPrimaryProfile();
        String specialization = (primaryProfile != null) ? primaryProfile.getName() : null;
        String documentation = relationship.getDocumentation();
        if (!forMutationResponse && documentation != null && documentation.isEmpty()) {
            documentation = null; // read side: normalize empty to null for @JsonInclude(NON_NULL)
        }
        List<Map<String, String>> properties = convertProperties(relationship.getProperties());
        return new RelationshipDto(
                relationship.getId(),
                relationship.getName(),
                relationship.eClass().getName(),
                specialization,
                relationship.getSource() != null ? relationship.getSource().getId() : null,
                relationship.getTarget() != null ? relationship.getTarget().getId() : null,
                false,
                documentation,
                properties.isEmpty() ? null : properties,
                relationship.getSource() != null ? relationship.getSource().getName() : null,
                relationship.getTarget() != null ? relationship.getTarget().getName() : null,
                accessTypeForDto(relationship),
                associationDirectedForDto(relationship),
                influenceStrengthForDto(relationship));
    }

    /**
     * Resolves the ArchiMate layer for an element using instanceof checks.
     */
    static String resolveLayer(IArchimateElement element) {
        if (element instanceof IBusinessElement) return "Business";
        if (element instanceof IApplicationElement) return "Application";
        if (element instanceof ITechnologyElement) return "Technology";
        if (element instanceof IPhysicalElement) return "Physical";
        if (element instanceof IStrategyElement) return "Strategy";
        if (element instanceof IMotivationElement) return "Motivation";
        if (element instanceof IImplementationMigrationElement) return "Implementation & Migration";
        return "Other";
    }

    /**
     * Populates the DTO {@code accessType} field for the given relationship.
     * Always populates when the relationship is an AccessRelationship (the int field
     * always has a value — defaults to {@code 0 = WRITE_ACCESS} on fresh objects);
     * returns {@code null} otherwise so {@code @JsonInclude(NON_NULL)} omits the field.
     */
    static String accessTypeForDto(IArchimateRelationship relationship) {
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
    static Boolean associationDirectedForDto(IArchimateRelationship relationship) {
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
    static String influenceStrengthForDto(IArchimateRelationship relationship) {
        if (relationship instanceof IInfluenceRelationship ir) {
            String s = ir.getStrength();
            return (s == null || s.isEmpty()) ? null : s;
        }
        return null;
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
     * Builds an ElementDto with an explicit specialization override, for use when the
     * profile-assignment command has not yet executed. The resolved layer string is
     * passed in by the caller.
     */
    static ElementDto buildElementDtoWithSpecialization(IArchimateElement element,
            String specialization, String layer) {
        String type = element.eClass().getName();
        List<Map<String, String>> properties = convertProperties(element.getProperties());
        String documentation = element.getDocumentation();
        if (documentation != null && documentation.isEmpty()) {
            documentation = null;
        }
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
     * Builds a ViewDto from an IArchimateDiagramModel, resolving the folder path
     * from the view's container.
     */
    static ViewDto buildViewDto(IArchimateDiagramModel view) {
        String folderPath = null;
        if (view.eContainer() instanceof IFolder parentFolder) {
            folderPath = FolderOperations.buildFolderPath(parentFolder);
        }
        return buildViewDto(view, folderPath);
    }

    /**
     * Builds a ViewDto from an IArchimateDiagramModel with an explicit folder path.
     * Shared by {@link #buildViewDto(IArchimateDiagramModel)} and the view-collection
     * read path to avoid duplicating viewpoint/documentation normalization and
     * property extraction logic.
     */
    static ViewDto buildViewDto(IArchimateDiagramModel view, String folderPath) {
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

    static List<Map<String, String>> convertProperties(
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

    /**
     * Maps an Archi EMF router type int to an MCP string.
     * Returns null for the default (manual/bendpoint) to keep responses compact.
     */
    static String mapConnectionRouterType(int routerType) {
        if (routerType == IDiagramModel.CONNECTION_ROUTER_MANHATTAN) {
            return "manhattan";
        }
        if (routerType != IDiagramModel.CONNECTION_ROUTER_BENDPOINT) {
            logger.warn("Unknown connection router type value: {}. "
                    + "Treating as default (manual).", routerType);
        }
        return null;
    }
}
