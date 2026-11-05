package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.ITextAlignment;

import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;

/**
 * Pins that an ArchiMate connection's row reports its text alignment and properties, as the plain
 * connection row already does, and that the add and update responses report the label settings the
 * connection holds.
 *
 * <p>Before, the ArchiMate row had no slot for either field, though the connection holds both, and the
 * add/update-view-connection responses carried a held label expression and label offset over from a
 * builder that never set them, so both read as absent. No tool writes any of these four on a connection,
 * so the model's value is the effective one on every path. The fixture sets them through EMF, as Archi's
 * editor would.</p>
 */
public class ArchimateConnectionRowTest {

    private ViewContentsFixture f;
    private IDiagramModelArchimateConnection ab;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-archimate-connection-row");
        IArchimateElement elA = f.element("el-a");
        IArchimateElement elB = f.element("el-b");
        ab = f.archimateConnection("conn-ab", f.relationship("rel-ab", elA, elB),
                f.object(f.view, "vo-a", elA, 40, 40, 120, 60),
                f.object(f.view, "vo-b", elB, 400, 40, 120, 60));
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private void holdTextAlignmentAndProperties() {
        ab.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_LEFT);
        ab.getProperties().add(property("Owner", "Payments"));
        ab.getProperties().add(property("Tier", "1"));
    }

    @Test
    public void getViewContents_shouldReadBackTheTextAlignmentAndProperties_ofAnArchimateConnection() {
        holdTextAlignmentAndProperties();

        ViewConnectionDto row = f.accessor.getViewContents(f.view.getId()).orElseThrow().connections().get(0);

        assertEquals("left", row.textAlignment());
        assertEquals(List.of(Map.of("key", "Owner", "value", "Payments"), Map.of("key", "Tier", "value", "1")),
                row.properties());
    }

    @Test
    public void getViewContents_shouldReportRightAlignment_andOmitTheCentredDefault() {
        ab.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_RIGHT);
        assertEquals("right", connectionRow().textAlignment());

        ab.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_CENTER);
        assertNull(connectionRow().textAlignment());
        assertNull("no properties reads as absent, not as an empty list", connectionRow().properties());
    }

    @Test
    public void getViewContents_shouldServeABareArchimateConnection_withNeitherField() {
        String served = f.serve(Map.of());

        assertFalse(served, served.contains("\"textAlignment\""));
        assertFalse(served, served.contains("\"properties\":[]"));
    }

    @Test
    public void updateViewConnection_shouldReportTheLabelSettingsAndFieldsTheConnectionHolds() {
        holdTextAlignmentAndProperties();
        ab.getFeatures().putString("labelExpression", "${name} (${property:Owner})");
        assertTrue("the platform these pins run on carries the label offset",
                RelativePositionFeature.isSupported(ab));
        RelativePositionFeature.set(ab, 1);

        ViewConnectionDto dto = f.accessor.updateViewConnection(ViewContentsFixture.SESSION, "conn-ab", null, null,
                null, Boolean.FALSE, null).entity();

        assertEquals(Boolean.FALSE, dto.nameVisible());
        assertEquals("${name} (${property:Owner})", dto.labelExpression());
        assertEquals("the north offset anchor", Integer.valueOf(1), dto.relativePosition());
        assertEquals("left", dto.textAlignment());
        assertEquals(2, dto.properties().size());
        assertEquals("the response and the read row agree", withoutRenderOnly(connectionRow()),
                withoutRenderOnly(dto));
    }

    private ViewConnectionDto connectionRow() {
        return f.accessor.getViewContents(f.view.getId()).orElseThrow().connections().get(0);
    }

    /** The label-and-field slots this class pins, compared as one tuple. */
    private static List<Object> withoutRenderOnly(ViewConnectionDto d) {
        return java.util.Arrays.asList(d.viewConnectionId(), d.nameVisible(), d.labelExpression(),
                d.relativePosition(), d.textAlignment(), d.properties());
    }

    private IProperty property(String key, String value) {
        IProperty p = f.factory.createProperty();
        p.setKey(key);
        p.setValue(value);
        return p;
    }
}
