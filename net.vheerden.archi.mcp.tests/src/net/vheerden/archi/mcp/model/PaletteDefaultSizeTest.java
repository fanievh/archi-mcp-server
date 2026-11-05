package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.draw2d.geometry.Dimension;
import org.junit.Test;

import com.archimatetool.editor.ui.factory.elements.JunctionUIProvider;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IJunction;

/**
 * Pins the size a new box takes when no size is given against Archi's palette: a junction takes
 * its provider's 15x15, read from Archi rather than written here, and every other element the
 * generic 120x55 box. Runs without a platform, which is the case the provider is constructed
 * directly for.
 */
public class PaletteDefaultSizeTest {

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;

    /**
     * The provider itself answers here, not the fallback: the fallback holds the same 15x15, so
     * reading through the supplier alone could not tell an unusable provider from a working one.
     */
    @Test
    public void shouldReadArchisJunctionProvider_withoutTheRunningPlatform() {
        Dimension size = new JunctionUIProvider().getDefaultSize();
        assertArrayEquals(new int[] {15, 15}, new int[] {size.width, size.height});
        assertArrayEquals(new int[] {size.width, size.height}, PaletteDefaultSize.ARCHI_JUNCTION.get());
    }

    @Test
    public void shouldSizeAJunctionAsThePalette_andAnOrJunctionAlike() {
        IJunction and = factory.createJunction();
        IJunction or = factory.createJunction();
        or.setType(IJunction.OR_JUNCTION_TYPE);
        for (IJunction junction : new IJunction[] {and, or}) {
            assertEquals(15, PaletteDefaultSize.width(junction));
            assertEquals(15, PaletteDefaultSize.height(junction));
        }
    }

    @Test
    public void shouldKeepTheGenericBox_forEveryOtherElement() {
        assertEquals(120, PaletteDefaultSize.width(factory.createBusinessActor()));
        assertEquals(55, PaletteDefaultSize.height(factory.createBusinessActor()));
        assertEquals(120, PaletteDefaultSize.width(factory.createGrouping()));
        assertEquals(55, PaletteDefaultSize.height(factory.createGrouping()));
    }

    @Test
    public void shouldNeverSizeAJunctionToItsName() {
        assertFalse(PaletteDefaultSize.sizesToLabel(factory.createJunction()));
        assertTrue(PaletteDefaultSize.sizesToLabel(factory.createBusinessActor()));

        IDiagramModelArchimateObject junctionBox = factory.createDiagramModelArchimateObject();
        junctionBox.setArchimateElement(factory.createJunction());
        IDiagramModelArchimateObject actorBox = factory.createDiagramModelArchimateObject();
        actorBox.setArchimateElement(factory.createBusinessActor());
        assertFalse(PaletteDefaultSize.sizesToLabel(junctionBox));
        assertTrue(PaletteDefaultSize.sizesToLabel(actorBox));
        assertTrue(PaletteDefaultSize.sizesToLabel(factory.createDiagramModelGroup()));
    }

    @Test
    public void shouldNeverSizeAJunctionToItsName_byItsPublishedTypeName() {
        assertFalse(PaletteDefaultSize.sizesToLabel("Junction"));
        assertTrue(PaletteDefaultSize.sizesToLabel("BusinessActor"));
        assertTrue(PaletteDefaultSize.sizesToLabel((String) null));
    }
}
