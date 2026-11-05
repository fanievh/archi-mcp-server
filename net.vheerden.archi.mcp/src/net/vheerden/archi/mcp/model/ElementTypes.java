package net.vheerden.archi.mcp.model;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;

import com.archimatetool.model.IArchimatePackage;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Resolves the element type a caller names for a new element. A name that is unknown, names a class
 * that is not an element, or names an abstract element class (which the factory cannot build) is
 * refused as {@link ErrorCode#INVALID_ELEMENT_TYPE}.
 */
final class ElementTypes {

    private ElementTypes() {}

    /** The concrete element class {@code type} names, or a refusal saying why it names none. */
    static EClass resolve(String type) {
        EClassifier classifier = IArchimatePackage.eINSTANCE.getEClassifier(type);
        if (classifier == null || !(classifier instanceof EClass eClass)) {
            throw new ModelAccessException(
                    "Invalid ArchiMate element type: " + CallerText.bare(type),
                    ErrorCode.INVALID_ELEMENT_TYPE);
        }
        if (!IArchimatePackage.eINSTANCE.getArchimateElement().isSuperTypeOf(eClass)) {
            throw new ModelAccessException(
                    CallerText.quoted(type) + " is not an ArchiMate element type",
                    ErrorCode.INVALID_ELEMENT_TYPE);
        }
        if (eClass.isAbstract() || eClass.isInterface()) {
            throw new ModelAccessException(
                    "Invalid ArchiMate element type: " + CallerText.bare(type)
                            + " is abstract: it groups element types and names none of them",
                    ErrorCode.INVALID_ELEMENT_TYPE, null,
                    "Use a concrete element type, such as BusinessActor, ApplicationComponent or Node.", null);
        }
        return eClass;
    }
}
