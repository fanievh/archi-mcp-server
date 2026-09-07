package net.vheerden.archi.mcp.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Validates the optional relationship-ID allow-list passed to
 * {@code auto-connect-view}.
 *
 * <p>Each supplied ID must resolve to a real {@link IArchimateRelationship} in
 * the model — a typo or hallucinated ID fails fast with
 * {@link ErrorCode#RELATIONSHIP_NOT_FOUND} before any connection is created. A
 * valid but not-drawable ID (endpoint off the view, already connected, nested)
 * is <em>not</em> an error here; the caller's scan loops apply the membership
 * test and let those cases fall through their existing skip paths.
 */
final class RelationshipIdAllowList {

    private RelationshipIdAllowList() {
    }

    /**
     * @param model           the loaded model to resolve IDs against
     * @param relationshipIds caller-supplied allow-list (may be null/empty)
     * @return the set of allow-listed relationship IDs, or {@code null} when no
     *         allow-list was supplied (meaning "no ID filter")
     * @throws ModelAccessException if any ID does not resolve to a relationship
     */
    static Set<String> validate(IArchimateModel model, List<String> relationshipIds) {
        if (relationshipIds == null || relationshipIds.isEmpty()) {
            return null;
        }
        Set<String> idFilter = new HashSet<>();
        for (String allowedId : relationshipIds) {
            EObject relObj = ArchimateModelUtils.getObjectByID(model, allowedId);
            if (!(relObj instanceof IArchimateRelationship)) {
                throw new ModelAccessException(
                        "Relationship not found: " + allowedId,
                        ErrorCode.RELATIONSHIP_NOT_FOUND,
                        null,
                        "Use get-relationships or search-relationships to find valid relationship IDs",
                        null);
            }
            idFilter.add(allowedId);
        }
        return idFilter;
    }
}
