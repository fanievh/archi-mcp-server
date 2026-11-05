package net.vheerden.archi.mcp.model;

import java.util.function.Supplier;

import org.eclipse.draw2d.geometry.Dimension;

import com.archimatetool.editor.ui.factory.elements.JunctionUIProvider;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IJunction;

/**
 * The size a box takes when the caller gives none, read the way Archi's palette reads it.
 *
 * <p>Archi's palette gives a new junction its provider's default size, 15x15, and applies it even
 * over a rectangle the user drags. Every other element takes the generic 120x55 box (Archi's own
 * factory default for its element-size preference). A junction also never takes a size from its
 * name: it draws no label inside its box, so a size fitted to the name is meaningless. The
 * size-to-label tools therefore leave a junction alone, and an automatic size on placement falls
 * back to the junction's palette default.</p>
 *
 * <p>The junction size is read from Archi's {@link JunctionUIProvider}, constructed directly.
 * Archi's provider registry is not used: it needs the running platform's extension registry, and
 * outside it the registry class fails its initialiser and stays unusable for the rest of the JVM.</p>
 */
public final class PaletteDefaultSize {

    /**
     * The junction size used only if Archi's provider cannot be constructed in some environment.
     * It is the value Archi 5.8 to 5.10 return; falling back to the generic 120x55 box instead
     * would place the oversized junction this class exists to prevent.
     */
    static final int JUNCTION_FALLBACK_SIDE = 15;

    /** Archi's palette size for a new junction, as {width, height}. */
    static final Supplier<int[]> ARCHI_JUNCTION = () -> {
        try {
            Dimension size = new JunctionUIProvider().getDefaultSize();
            return new int[] { size.width, size.height };
        } catch (LinkageError | RuntimeException e) {
            return new int[] { JUNCTION_FALLBACK_SIDE, JUNCTION_FALLBACK_SIDE };
        }
    };

    private static volatile int[] junctionSize;

    private PaletteDefaultSize() {
    }

    /** The width a new box for {@code concept} takes when the caller gives none. */
    static int width(Object concept) {
        return concept instanceof IJunction ? junction()[0] : ElementSizer.DEFAULT_WIDTH;
    }

    /** The height a new box for {@code concept} takes when the caller gives none. */
    static int height(Object concept) {
        return concept instanceof IJunction ? junction()[1] : ElementSizer.DEFAULT_HEIGHT;
    }

    /**
     * Whether a box for {@code concept} may be sized to its name. False for a junction, which draws
     * no label inside its box.
     */
    static boolean sizesToLabel(Object concept) {
        return !(concept instanceof IJunction);
    }

    /** {@link #sizesToLabel(Object)} for the concept a view object shows; true for a non-element box. */
    static boolean sizesToLabel(IDiagramModelObject box) {
        return !(box instanceof IDiagramModelArchimateObject obj) || sizesToLabel(obj.getArchimateElement());
    }

    /**
     * {@link #sizesToLabel(Object)} for a caller that holds only the published type name of an
     * element, as reported in an element's {@code type} field. A null name is an unknown element,
     * which is sized as before.
     */
    public static boolean sizesToLabel(String publishedTypeName) {
        return !IArchimatePackage.Literals.JUNCTION.getName().equals(publishedTypeName);
    }

    private static int[] junction() {
        int[] size = junctionSize;
        if (size == null) {
            size = ARCHI_JUNCTION.get();
            junctionSize = size;
        }
        return size;
    }
}
