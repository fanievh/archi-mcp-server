package net.vheerden.archi.mcp.model;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;

import com.archimatetool.model.IArchimatePackage;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Resolves the relationship type a caller names, for a new relationship or a relationship-type filter.
 * A name that is unknown, names a class that is not a relationship, or names an abstract relationship
 * class (which no relationship is an instance of, so none can be built or matched) is refused as
 * {@link ErrorCode#INVALID_RELATIONSHIP_TYPE}.
 */
final class RelationshipTypes {

    private RelationshipTypes() {}

    /** The concrete relationship class {@code type} names, or a refusal saying why it names none. */
    static EClass resolve(String type) {
        EClassifier classifier = IArchimatePackage.eINSTANCE.getEClassifier(type);
        if (classifier == null || !(classifier instanceof EClass eClass)) {
            throw new ModelAccessException(
                    "Invalid ArchiMate relationship type: " + CallerText.bare(type),
                    ErrorCode.INVALID_RELATIONSHIP_TYPE);
        }
        if (!IArchimatePackage.eINSTANCE.getArchimateRelationship().isSuperTypeOf(eClass)) {
            throw new ModelAccessException(
                    CallerText.quoted(type) + " is not an ArchiMate relationship type",
                    ErrorCode.INVALID_RELATIONSHIP_TYPE);
        }
        if (eClass.isAbstract() || eClass.isInterface()) {
            throw new ModelAccessException(
                    "Invalid ArchiMate relationship type: " + CallerText.bare(type)
                            + " is abstract: it groups relationship types and names none of them",
                    ErrorCode.INVALID_RELATIONSHIP_TYPE, null,
                    "Use a concrete relationship type, such as ServingRelationship, FlowRelationship or"
                            + " AssociationRelationship.", null);
        }
        return eClass;
    }
}
