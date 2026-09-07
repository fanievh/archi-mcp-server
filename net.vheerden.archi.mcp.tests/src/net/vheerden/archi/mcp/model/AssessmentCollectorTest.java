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
import com.archimatetool.model.IApplicationComponent;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IJunction;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.ITextPosition;

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
    public void shouldReadTextAlignmentFromTheModel_whenCollectingAssessmentNodes() {
        // The own-icon-over-label detector places an element's title from its textAlignment, so the
        // value has to come off the MODEL rather than a constant. Only a collector-level test can
        // show that: a detector test builds AssessmentNode by hand and would pass identically
        // against a hardcoded default, which is exactly the bug this guards.
        //
        // The field genuinely varies in the wild: a Grouping, group or note is stamped LEFT at
        // creation, an object this server created before it stamped anything holds the EMF default
        // CENTRE, and any object's alignment can be set explicitly — so a collector that ignored
        // the feature would report all three populations identically.
        //
        // These fixtures are built through EMF directly rather than through the server, so they
        // carry exactly the alignment set on them here and are unaffected by what the creation
        // path stamps. That is deliberate: the subject is whether the collector reads the model,
        // and routing the fixture through the server would couple this test to creation defaults.
        IArchimateModel model = view.getArchimateModel();

        IDiagramModelArchimateObject leftObj = factory.createDiagramModelArchimateObject();
        IApplicationComponent leftEl = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(leftEl);
        leftObj.setId("left");
        leftObj.setArchimateElement(leftEl);
        leftObj.setBounds(0, 300, 120, 55);
        leftObj.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_LEFT);
        view.getChildren().add(leftObj);

        IDiagramModelArchimateObject rightObj = factory.createDiagramModelArchimateObject();
        IApplicationComponent rightEl = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(rightEl);
        rightObj.setId("right");
        rightObj.setArchimateElement(rightEl);
        rightObj.setBounds(200, 300, 120, 55);
        rightObj.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_RIGHT);
        view.getChildren().add(rightObj);

        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(view);

        AssessmentNode left = nodes.stream().filter(n -> "left".equals(n.id())).findFirst().orElseThrow();
        AssessmentNode right = nodes.stream().filter(n -> "right".equals(n.id())).findFirst().orElseThrow();
        AssessmentNode untouched = nodes.stream().filter(n -> "s".equals(n.id())).findFirst().orElseThrow();

        assertEquals("LEFT must survive the collector, not be flattened to a default",
                AssessmentNode.TEXT_ALIGNMENT_LEFT, left.textAlignment());
        assertEquals("RIGHT must survive the collector too — two distinct non-default values, so a\n"
                        + "  collector returning any single constant fails at least one of them",
                AssessmentNode.TEXT_ALIGNMENT_RIGHT, right.textAlignment());
        assertEquals("an object nobody aligned collects at Archi's EMF default, CENTRE",
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, untouched.textAlignment());
    }

    @Test
    public void shouldReadTextPositionFromTheModel_whenCollectingAssessmentNodes() {
        // The exact counterpart of the alignment test above, and it exists for the identical
        // reason: the own-icon-over-label detector now anchors an element's title band from its
        // textPosition, so the value has to come off the MODEL rather than a constant — and every
        // detector-level test for that band builds AssessmentNode by hand, so all of them would
        // pass unchanged against a collector that hardcoded TOP. Only a collector-level test can
        // tell the difference.
        //
        // The feature genuinely varies: TOP is Archi's EMF default, and this server's published
        // verticalTextAlignment parameter writes CENTRE or BOTTOM on any object.
        //
        // Built through EMF directly rather than through the server, for the same reason as above:
        // the subject is whether the collector reads the model, not what a creation path stamps.
        IArchimateModel model = view.getArchimateModel();

        IDiagramModelArchimateObject centreObj = factory.createDiagramModelArchimateObject();
        IApplicationComponent centreEl = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(centreEl);
        centreObj.setId("vcentre");
        centreObj.setArchimateElement(centreEl);
        centreObj.setBounds(0, 500, 120, 55);
        centreObj.setTextPosition(ITextPosition.TEXT_POSITION_CENTRE);
        view.getChildren().add(centreObj);

        IDiagramModelArchimateObject bottomObj = factory.createDiagramModelArchimateObject();
        IApplicationComponent bottomEl = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(bottomEl);
        bottomObj.setId("vbottom");
        bottomObj.setArchimateElement(bottomEl);
        bottomObj.setBounds(200, 500, 120, 55);
        bottomObj.setTextPosition(ITextPosition.TEXT_POSITION_BOTTOM);
        view.getChildren().add(bottomObj);

        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(view);

        AssessmentNode centre = nodes.stream().filter(n -> "vcentre".equals(n.id())).findFirst().orElseThrow();
        AssessmentNode bottom = nodes.stream().filter(n -> "vbottom".equals(n.id())).findFirst().orElseThrow();
        AssessmentNode untouched = nodes.stream().filter(n -> "s".equals(n.id())).findFirst().orElseThrow();

        assertEquals("CENTRE must survive the collector, not be flattened to a default",
                AssessmentNode.TEXT_POSITION_CENTRE, centre.textPosition());
        assertEquals("BOTTOM must survive it too — two distinct non-default values, so a collector\n"
                        + "  returning any single constant fails at least one of them",
                AssessmentNode.TEXT_POSITION_BOTTOM, bottom.textPosition());
        assertEquals("an object nobody positioned collects at Archi's EMF default, TOP",
                AssessmentNode.TEXT_POSITION_TOP, untouched.textPosition());
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

    /**
     * Every measurement guard in the collector must catch {@code SWTError}, not only
     * {@code Exception}.
     *
     * <p>This walk measures label text, note content and image dimensions through SWT, and a
     * display-less {@code Display.getDefault()} raises an {@code SWTError} on some platforms and
     * an {@code SWTException} on others. A catch written for {@code Exception} alone lets the
     * {@code Error} straight through — which is exactly what happened: a placement path started
     * calling this walk, and every annotation placement on a host without a display began
     * throwing, taking 30 tests across four classes down in the headless lane while passing on a
     * developer machine that could obtain one.</p>
     *
     * <p>The assertion is deliberately about the SHAPE of the guard rather than about behaviour,
     * because the failure needs a display-less host to reproduce and no automated lane here can
     * manufacture one on demand. A bare {@code catch (Exception e)} added to this file is
     * therefore invisible to every other test until CI turns red on another machine.</p>
     */
    @Test
    public void everyMeasurementGuard_shouldCatchSwtErrorAndNotOnlyException() throws Exception {
        String source = readCollectorSource();

        assertFalse("a bare 'catch (Exception e)' in this collector lets a display-less "
                + "Display.getDefault() escape as an Error — pair it with SWTError, the way "
                + "ElementSizer.fitTextBoxHeightToContentOrElse does",
                source.contains("catch (Exception e)"));
        assertTrue("the SWTError-paired guard must be the one actually used here",
                source.contains("catch (Exception | SWTError e)"));
    }

    /** Reads the collector's own source, failing rather than silently covering nothing. */
    private static String readCollectorSource() throws Exception {
        for (String root : new String[]{
                "../net.vheerden.archi.mcp/src", "net.vheerden.archi.mcp/src"}) {
            java.nio.file.Path path = java.nio.file.Paths.get(
                    root, "net/vheerden/archi/mcp/model/AssessmentCollector.java");
            if (java.nio.file.Files.isRegularFile(path)) {
                return java.nio.file.Files.readString(path);
            }
        }
        throw new AssertionError("Could not resolve AssessmentCollector.java from "
                + java.nio.file.Paths.get("").toAbsolutePath()
                + " — this guard cannot silently cover nothing");
    }
}
