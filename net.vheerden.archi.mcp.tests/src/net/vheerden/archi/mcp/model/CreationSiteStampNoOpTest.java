package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.ITextPosition;

/**
 * Establishes which diagram-object kinds can actually diverge from what Archi's own palette would
 * have written, and which cannot.
 *
 * <p>Archi stamps a type's UI-provider text defaults onto a diagram object when the object is
 * created from the palette. This server builds objects straight from the EMF factory, which stamps
 * nothing, so an object keeps the EMF default. A divergence therefore exists for exactly those types
 * whose provider default differs from the EMF default — and for every other type, writing the
 * provider default would write the value the object already holds.</p>
 *
 * <h2>Why the provider half is asserted from source rather than executed</h2>
 *
 * <p>{@code ObjectUIFactory} resolves providers through the Eclipse extension registry: its static
 * initialiser calls {@code Platform.getExtensionRegistry()} and instantiates every contributed
 * provider. Outside an OSGi runtime the registry is null and the class fails to initialise with an
 * {@code ExceptionInInitializerError}, so no lane available to this project can execute a provider
 * lookup. The provider values below are therefore quoted from Archi's source with file and line, and
 * only the EMF half is executed here. That split is stated rather than hidden: an unexecutable claim
 * is recorded as a source citation, never quietly downgraded to an assumption.</p>
 *
 * <p>Provider defaults, read from Archi 5.10 source:</p>
 * <ul>
 *   <li>{@code AbstractGraphicalObjectUIProvider:45-47} returns {@code TEXT_ALIGNMENT_CENTER}, and
 *       {@code DiagramConnectionUIProvider:28}, {@code DiagramImageUIProvider:30} and
 *       {@code DiagramModelReferenceUIProvider:35} all extend it <em>without overriding</em>
 *       {@code getDefaultTextAlignment()} — so connections, images and references default CENTRE.</li>
 *   <li>{@code GroupingUIProvider:71-73}, {@code GroupUIProvider:68-70} and
 *       {@code NoteUIProvider:65-67} each override it to return {@code TEXT_ALIGNMENT_LEFT}.</li>
 *   <li>{@code AbstractGraphicalObjectUIProvider:49-52} returns {@code TEXT_POSITION_TOP}, and the
 *       only override reachable here, {@code GroupingUIProvider:76-78}, restates TOP.</li>
 * </ul>
 */
public class CreationSiteStampNoOpTest {

    private static final IArchimateFactory FACTORY = IArchimateFactory.eINSTANCE;

    /**
     * Archi's shared CENTRE constant, quoted so the assertions below read against a name rather
     * than a bare 2. {@code DiagramModelObject:133} and {@code DiagramModelConnection:175} both
     * declare {@code TEXT_ALIGNMENT_EDEFAULT = 2}, which is this value.
     */
    private static final int CENTRE = ITextAlignment.TEXT_ALIGNMENT_CENTER;

    // ---- the kinds a stamp would leave unchanged -------------------------------------------------

    @Test
    public void shouldAlreadyHoldTheProviderDefault_whenAConnectionIsCreated() {
        IDiagramModelArchimateConnection archiConn =
                FACTORY.createDiagramModelArchimateConnection();
        IDiagramModelConnection plainConn = FACTORY.createDiagramModelConnection();

        assertEquals("an ArchiMate connection is born CENTRE, which is what its provider "
                + "would have stamped — writing it changes nothing",
                CENTRE, archiConn.getTextAlignment());
        assertEquals("a plain connection likewise", CENTRE, plainConn.getTextAlignment());
    }

    @Test
    public void shouldAlreadyHoldTheProviderDefault_whenAnImageIsCreated() {
        IDiagramModelImage image = FACTORY.createDiagramModelImage();

        assertEquals("an image is born CENTRE, which is what DiagramImageUIProvider inherits",
                CENTRE, image.getTextAlignment());
        // No vertical assertion: IDiagramModelImage does not extend ITextPosition at all, so the
        // feature does not exist on it and the factory could not stamp it even in principle.
    }

    @Test
    public void shouldAlreadyHoldTheProviderDefault_whenAViewReferenceIsCreated() {
        IDiagramModelReference reference = FACTORY.createDiagramModelReference();

        assertEquals("a view reference is born CENTRE, which is what "
                + "DiagramModelReferenceUIProvider inherits",
                CENTRE, reference.getTextAlignment());
        assertEquals("and TOP vertically", ITextPosition.TEXT_POSITION_TOP,
                reference.getTextPosition());
    }

    // ---- the kinds a stamp would actually change --------------------------------------------------

    /**
     * The other half of the same claim. If these were already LEFT there would be no divergence to
     * fix, so the three assertions below are what make the stamp meaningful rather than decorative.
     */
    @Test
    public void shouldNotYetHoldTheProviderDefault_whenAGroupOrNoteOrElementIsCreated() {
        IDiagramModelGroup group = FACTORY.createDiagramModelGroup();
        IDiagramModelNote note = FACTORY.createDiagramModelNote();
        IDiagramModelArchimateObject element = FACTORY.createDiagramModelArchimateObject();

        assertEquals("a group is born CENTRE while GroupUIProvider would have stamped LEFT",
                CENTRE, group.getTextAlignment());
        assertEquals("a note is born CENTRE while NoteUIProvider would have stamped LEFT",
                CENTRE, note.getTextAlignment());
        assertEquals("and an ArchiMate object is born CENTRE — which matches its provider for every "
                + "element type except Grouping, whose provider stamps LEFT",
                CENTRE, element.getTextAlignment());
    }

    /**
     * The vertical axis is a no-op on every kind this server creates: each is born TOP, and every
     * provider reachable here returns TOP. Pinned so that a later change to the vertical default is
     * a deliberate decision rather than a silent side effect of touching the horizontal one.
     */
    @Test
    public void shouldAlreadyHoldTheProviderTextPosition_whenAnyObjectIsCreated() {
        assertEquals(ITextPosition.TEXT_POSITION_TOP,
                FACTORY.createDiagramModelArchimateObject().getTextPosition());
        assertEquals(ITextPosition.TEXT_POSITION_TOP,
                FACTORY.createDiagramModelGroup().getTextPosition());
        assertEquals(ITextPosition.TEXT_POSITION_TOP,
                FACTORY.createDiagramModelNote().getTextPosition());
        assertEquals(ITextPosition.TEXT_POSITION_TOP,
                FACTORY.createDiagramModelReference().getTextPosition());
    }
}
