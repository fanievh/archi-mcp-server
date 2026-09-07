package net.vheerden.archi.mcp.model;

import org.eclipse.emf.ecore.EClass;

import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IInfluenceRelationship;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;

/**
 * Type-conditional validation for the ArchiMate relationship semantic attributes —
 * {@code accessType}, {@code associationDirected} and {@code influenceStrength}.
 *
 * <p>Each attribute is meaningful on exactly one relationship type, so supplying one on the wrong
 * type is a caller error rather than a value to be silently dropped. Validation happens at the
 * prepare boundary, <strong>before</strong> any EMF object is created or mutated, so a rejected
 * request leaves the model untouched.</p>
 *
 * <p>Stateless helper — extracted from the accessor facade, which is size-ratcheted.</p>
 */
final class RelationshipSemantics {

    /** Max-length cap for {@code influenceStrength} (mirrors documentation field convention). */
    static final int INFLUENCE_STRENGTH_MAX_LEN = 255;

    private RelationshipSemantics() {
        // static helper
    }

    /**
     * Maps an MCP wire-vocabulary {@code accessType} string to the EMF
     * {@code IAccessRelationship} named-constant int. Throws on invalid value.
     * Uses named constants (WRITE=0, READ=1, UNSPECIFIED=2, READWRITE=3).
     */
    static int resolveAccessTypeInt(String wireValue) {
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
     * Validates semantic attributes for {@code create-relationship}. Type-conditional
     * rejection at the prepare boundary BEFORE any EMF object is created.
     */
    static void validateForCreate(
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
    static void validateForUpdate(
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
}
