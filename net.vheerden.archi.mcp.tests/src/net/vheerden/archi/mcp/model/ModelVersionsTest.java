package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.impl.ENotificationImpl;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;

/**
 * Pins that each model counts its own changes: a change to one model never moves the version read for
 * another, in either direction, and a model-content event moves only the version of the model its
 * notifier belongs to.
 */
public class ModelVersionsTest {

    private ModelVersions versions;
    private IArchimateModel modelA;
    private IArchimateModel modelB;
    private IArchimateElement inA;
    private IArchimateElement inB;

    @Before
    public void setUp() {
        versions = new ModelVersions();
        modelA = newModel("model-a");
        modelB = newModel("model-b");
        inA = addActor(modelA);
        inB = addActor(modelB);
    }

    private static IArchimateModel newModel(String id) {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setId(id);
        model.setDefaults();
        return model;
    }

    private static IArchimateElement addActor(IArchimateModel model) {
        IArchimateElement actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        return actor;
    }

    private static Notification renamed(IArchimateElement element) {
        return new ENotificationImpl((InternalEObject) element, Notification.SET,
                IArchimatePackage.Literals.NAMEABLE__NAME, "old", "new");
    }

    @Test
    public void shouldMoveOnlyTheChangedModelsVersion_inBothDirections() {
        versions.bump(modelA);
        assertEquals(1, versions.get(modelA));
        assertEquals(0, versions.get(modelB));

        versions.bump(modelB);
        versions.bump(modelB);
        assertEquals(1, versions.get(modelA));
        assertEquals(2, versions.get(modelB));
    }

    @Test
    public void shouldMoveTheNotifiersOwnModel_onAContentEvent_inBothDirections() {
        versions.bumpOwnerOf(renamed(inA));
        assertEquals(1, versions.get(modelA));
        assertEquals("an edit in A does not move B", 0, versions.get(modelB));

        versions.bumpOwnerOf(renamed(inB));
        assertEquals("an edit in B does not move A", 1, versions.get(modelA));
        assertEquals(1, versions.get(modelB));
    }

    @Test
    public void shouldMoveEveryModel_whenAnEventCannotBeTracedToOne() {
        versions.get(modelA);
        versions.get(modelB);
        IArchimateElement detached = IArchimateFactory.eINSTANCE.createBusinessActor();

        versions.bumpOwnerOf(renamed(detached));
        versions.bumpOwnerOf("not a notification");

        assertEquals(2, versions.get(modelA));
        assertEquals(2, versions.get(modelB));
    }

    @Test
    public void shouldIgnoreANullModel() {
        versions.bump(null);
        assertEquals(0, versions.get(modelA));
    }
}
