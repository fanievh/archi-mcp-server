package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IJunction;
import com.archimatetool.model.IProfile;

/**
 * Tests for {@link AssessmentCollector} connection collection, focused on
 * label-text resolution. A connection whose label is suppressed (name not
 * visible) must resolve to an empty label so the layout quality assessor does
 * not report a label that is never rendered. Uses real EMF objects via
 * {@link IArchimateFactory#eINSTANCE}; no SWT runtime required (assessment
 * nodes are built directly to avoid glyph measurement).
 */
public class AssessmentCollectorTest {

    private IArchimateFactory factory;
    private IArchimateDiagramModel view;
    private IDiagramModelArchimateObject sourceViewObj;
    private IDiagramModelArchimateObject targetViewObj;
    private IDiagramModelArchimateConnection connection;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        view = factory.createArchimateDiagramModel();
        view.setName("Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IArchimateElement source = factory.createApplicationComponent();
        source.setName("Source");
        model.getFolder(FolderType.APPLICATION).getElements().add(source);

        IArchimateElement target = factory.createApplicationComponent();
        target.setName("Target");
        model.getFolder(FolderType.APPLICATION).getElements().add(target);

        IArchimateRelationship rel = factory.createServingRelationship();
        rel.setName("Serving");
        rel.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        sourceViewObj = factory.createDiagramModelArchimateObject();
        sourceViewObj.setId("s");
        sourceViewObj.setArchimateElement(source);
        sourceViewObj.setBounds(0, 0, 100, 50);
        view.getChildren().add(sourceViewObj);

        targetViewObj = factory.createDiagramModelArchimateObject();
        targetViewObj.setId("t");
        targetViewObj.setArchimateElement(target);
        targetViewObj.setBounds(400, 0, 100, 50);
        view.getChildren().add(targetViewObj);

        connection = factory.createDiagramModelArchimateConnection();
        connection.setId("c1");
        connection.setArchimateRelationship(rel);
        connection.connect(sourceViewObj, targetViewObj);
    }

    /** Source/target assessment nodes whose centers define the connection path (50,25)->(450,25). */
    private List<AssessmentNode> endpointNodes() {
        return List.of(
                new AssessmentNode("s", 0, 0, 100, 50, null, false, false, "Source", 0.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("t", 400, 0, 100, 50, null, false, false, "Target", 0.0, null, null, 0.0, 0.0, 0.0));
    }

    @Test
    public void shouldFlagJunctionNode_whenCollectingAssessmentNodes() {
        // The own-endpoint label-overlap check needs to tell a Junction (solid dark fill, no usable
        // interior) from a normal box, so the collector must set isJunction from the diagram object's
        // archimate concept. A Junction view object collects with the flag set; a component does not.
        IArchimateModel model = view.getArchimateModel();
        IJunction junctionEl = factory.createJunction();
        model.getFolder(FolderType.OTHER).getElements().add(junctionEl);

        IDiagramModelArchimateObject junctionObj = factory.createDiagramModelArchimateObject();
        junctionObj.setId("j");
        junctionObj.setArchimateElement(junctionEl);
        junctionObj.setBounds(200, 0, 14, 14);
        view.getChildren().add(junctionObj);

        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(view);

        AssessmentNode jNode = nodes.stream().filter(n -> "j".equals(n.id())).findFirst().orElseThrow();
        assertTrue("A Junction view object collects with isJunction=true", jNode.isJunction());
        AssessmentNode sNode = nodes.stream().filter(n -> "s".equals(n.id())).findFirst().orElseThrow();
        assertFalse("A normal component collects with isJunction=false", sNode.isJunction());
    }

    @Test
    public void shouldResolveRelationshipName_whenLabelVisible() {
        List<AssessmentConnection> connections =
                AssessmentCollector.collectAssessmentConnections(view, endpointNodes());

        assertEquals(1, connections.size());
        assertEquals("Serving", connections.get(0).labelText());
    }

    @Test
    public void shouldResolveEmptyLabel_whenLabelHidden() {
        connection.setNameVisible(false);

        List<AssessmentConnection> connections =
                AssessmentCollector.collectAssessmentConnections(view, endpointNodes());

        assertEquals(1, connections.size());
        assertEquals("", connections.get(0).labelText());
    }

    @Test
    public void shouldResolveEmptyLabel_forNonArchimateConnection() {
        // A plain (non-archimate) connection carries no relationship and never
        // contributes a label in the assessor; the visibility guard must leave that
        // branch resolving to "" regardless of name visibility (guard added before
        // the archimate instanceof, so the instanceof still gates the label).
        IDiagramModelConnection plain = factory.createDiagramModelConnection();
        plain.setId("plain");
        plain.setName("PlainName");
        plain.connect(sourceViewObj, targetViewObj);

        List<AssessmentConnection> connections =
                AssessmentCollector.collectAssessmentConnections(view, endpointNodes());

        AssessmentConnection plainConn = connections.stream()
                .filter(c -> "plain".equals(c.id())).findFirst().orElseThrow();
        assertEquals("", plainConn.labelText());
    }

    @Test
    public void shouldNotCountPhantomLabelOverlap_whenLabelHidden() {
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        // An unrelated node sitting under the mid-path label position (250,25).
        AssessmentNode mid =
                new AssessmentNode("c", 220, 5, 60, 40, null, false, false, "Mid", 0.0, null, null, 0.0, 0.0, 0.0);
        List<AssessmentNode> assessNodes = List.of(
                new AssessmentNode("s", 0, 0, 100, 50, null, false, false, "Source", 0.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("t", 400, 0, 100, 50, null, false, false, "Target", 0.0, null, null, 0.0, 0.0, 0.0),
                mid);

        // Visible label: the rendered label overlaps the mid node -> counted.
        List<AssessmentConnection> visible =
                AssessmentCollector.collectAssessmentConnections(view, endpointNodes());
        LayoutAssessmentResult visibleResult = assessor.assess(assessNodes, visible, true);
        assertTrue("Visible label should produce at least one label overlap",
                visibleResult.labelOverlapCount() >= 1);

        // Suppressed label: nothing is rendered there -> no phantom overlap.
        connection.setNameVisible(false);
        List<AssessmentConnection> hidden =
                AssessmentCollector.collectAssessmentConnections(view, endpointNodes());
        LayoutAssessmentResult hiddenResult = assessor.assess(assessNodes, hidden, true);
        assertEquals("Suppressed label must not be counted as overlapping",
                0, hiddenResult.labelOverlapCount());
        assertTrue("No label-overlap violators for a suppressed label",
                hiddenResult.labelOverlaps() == null || hiddenResult.labelOverlaps().isEmpty());
    }

    @Test
    public void collectAssessmentNodes_shouldResolveProfileIcon_intoImagePath() {
        // Give the source object a specialization icon via its element's profile and
        // set the image source to profile. The collector must surface that path so the
        // image-overlap detector examines specialization icons, not just custom images.
        IProfile profile = factory.createProfile();
        profile.setImagePath("images/spec-icon.png");
        sourceViewObj.getArchimateElement().getProfiles().add(profile);
        sourceViewObj.setImageSource(IDiagramModelArchimateObject.IMAGE_SOURCE_PROFILE);

        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(view);

        AssessmentNode src = nodes.stream()
                .filter(n -> "s".equals(n.id())).findFirst().orElseThrow();
        assertEquals("images/spec-icon.png", src.imagePath());
        assertEquals("top-right", src.imagePosition());
        // Headless has no archive manager, so natural dimensions stay at the 0.0
        // fallback sentinel — the real-pixel read is exercised at the live gate.
        assertEquals(0.0, src.imageNaturalWidth(), 0.0);
        assertEquals(0.0, src.imageNaturalHeight(), 0.0);
    }
}
