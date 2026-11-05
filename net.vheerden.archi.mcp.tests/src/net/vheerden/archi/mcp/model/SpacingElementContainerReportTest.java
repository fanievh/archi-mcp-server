package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ApplyElementSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyGroupSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplySpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.SkippedContainerDto;

/**
 * What the spacing tools say about a view whose containers are ArchiMate elements holding
 * children — process steps drawn inside {@code BusinessRole} swimlanes — and that they still act
 * on exactly the views they acted on before.
 *
 * <p>Headless: these are the shared pieces the accessor composes (the collectors, the describer,
 * the sentence and its code, the decision records and the shared refusal), driven on EMF objects
 * with no display. The accessor-level arms are in the display-lane companion.</p>
 */
public class SpacingElementContainerReportTest {

    private static final String ELEMENT_CLAUSE =
            "This tool spaces the elements inside the view's own containers";
    private static final String GROUP_CLAUSE =
            "This tool inflates corridors between the view's own containers";

    private final IArchimateFactory f = IArchimateFactory.eINSTANCE;

    // ---- the predicate --------------------------------------------------------------------

    @Test
    public void holdsChildren_shouldAnswerTheSameForBothKinds_andCountANote() {
        IArchimateDiagramModel view = view();
        IDiagramModelArchimateObject role = role(view, "Lane", 0, 0);
        IDiagramModelGroup group = group(view, "G", 0, 400);
        IDiagramModelArchimateObject emptyRole = role(view, "Empty", 0, 800);
        IDiagramModelGroup notesOnly = group(view, "Notes", 0, 1200);
        step(role, "Step", 20, 50);
        step(group, "Step", 20, 50);
        IDiagramModelNote note = f.createDiagramModelNote();
        note.setBounds(f.createBounds(10, 50, 100, 40));
        notesOnly.getChildren().add(note);

        assertTrue(TopLevelGroupTargets.holdsChildren(role));
        assertTrue(TopLevelGroupTargets.holdsChildren(group));
        assertFalse(TopLevelGroupTargets.holdsChildren(emptyRole));
        assertTrue("a note is a child, as it always was in the collectors",
                TopLevelGroupTargets.holdsChildren(notesOnly));
        assertFalse(TopLevelGroupTargets.holdsChildren(note));
    }

    @Test
    public void collectors_shouldSplitTheViewBetweenGroupingContainersAndElementContainers() {
        Lanes lanes = fourRoleLanes();

        assertTrue(TopLevelGroupTargets.collectPopulated(lanes.view).isEmpty());
        assertTrue(TopLevelGroupTargets.collectOutermostPopulated(lanes.view).isEmpty());
        assertEquals(lanes.roles, TopLevelGroupTargets.collectSkipped(lanes.view));
    }

    // ---- the describer --------------------------------------------------------------------

    @Test
    public void describeSkippedForSpacing_shouldListExactlyTheRoles_withTheSpacingReason() {
        Lanes lanes = fourRoleLanes();

        List<SkippedContainerDto> skipped = TopLevelGroupTargets.describeSkippedForSpacing(lanes.view);

        assertEquals(4, skipped.size());
        for (int i = 0; i < 4; i++) {
            SkippedContainerDto entry = skipped.get(i);
            assertEquals(lanes.roles.get(i).getId(), entry.viewObjectId());
            assertEquals("Lane " + (i + 1), entry.name());
            assertEquals("BusinessRole", entry.elementType());
            assertEquals(TopLevelGroupTargets.SPACING_SKIP_REASON, entry.reason());
        }
        assertTrue(skipped.get(0).reason().contains("layout-within-group"));
        assertFalse("not arrange-groups' advice, which would be wrong for a spacing tool",
                skipped.get(0).reason().contains("groupIds"));
    }

    @Test
    public void anElementHoldingOnlyANoteOrImage_shouldNotBeReportedAsAnElementContainer() {
        IArchimateDiagramModel view = view();
        IDiagramModelArchimateObject annotated = node(view, "Annotated", 40, 40);
        IDiagramModelNote note = f.createDiagramModelNote();
        note.setBounds(f.createBounds(10, 50, 100, 40));
        annotated.getChildren().add(note);
        IDiagramModelArchimateObject pictured = node(view, "Pictured", 700, 40);
        com.archimatetool.model.IDiagramModelImage image = f.createDiagramModelImage();
        image.setBounds(f.createBounds(10, 50, 100, 40));
        pictured.getChildren().add(image);
        IDiagramModelArchimateObject host = node(view, "Host", 40, 600);
        step(host, "Inside", 40, 60);
        IDiagramModelNote aside = f.createDiagramModelNote();
        aside.setBounds(f.createBounds(300, 60, 100, 40));
        host.getChildren().add(aside);

        assertEquals("arrange-groups' wider walk is unchanged",
                List.of(annotated, pictured, host), TopLevelGroupTargets.collectSkipped(view));
        assertEquals(List.of(host), TopLevelGroupTargets.collectElementContainers(view));
        List<SkippedContainerDto> skipped = TopLevelGroupTargets.describeSkippedForSpacing(view);
        assertEquals(1, skipped.size());
        assertEquals(host.getId(), skipped.get(0).viewObjectId());
    }

    @Test
    public void aViewWhoseOnlyChildrenAreNotes_shouldKeepTheFlatAnswerAndRefusal() {
        IArchimateDiagramModel view = view();
        IDiagramModelArchimateObject annotated = node(view, "Annotated", 40, 40);
        IDiagramModelNote note = f.createDiagramModelNote();
        note.setBounds(f.createBounds(10, 50, 100, 40));
        annotated.getChildren().add(note);

        assertTrue(TopLevelGroupTargets.describeSkippedForSpacing(view).isEmpty());
        assertEquals(null, TopLevelGroupTargets.elementStepCannotPosition("v1", view, "x"));
        assertEquals(null, TopLevelGroupTargets.groupStepCannotPosition("v1", view, "x"));
        assertEquals("no refusal of its own: the caller keeps its original wording", null,
                TopLevelGroupTargets.noOwnGroupingContainersRefusal("v1", view, "x"));
    }

    @Test
    public void describeSkippedForSpacing_shouldBeEmpty_onAFlatView() {
        IArchimateDiagramModel view = view();
        step(view, "A", 0, 0);
        step(view, "B", 300, 0);
        assertTrue(TopLevelGroupTargets.describeSkippedForSpacing(view).isEmpty());
    }

    // ---- the sentence and its code --------------------------------------------------------

    @Test
    public void elementStep_shouldSayWhatTheViewHolds_andMapToTheFixedCode() {
        Lanes lanes = fourRoleLanes();

        String reason = TopLevelGroupTargets.elementStepCannotPosition("v1", lanes.view, ELEMENT_CLAUSE);

        assertNotNull(reason);
        assertTrue(reason, reason.startsWith("View v1 holds 4 element containers (ArchiMate elements "
                + "that hold children) and no populated native group or Grouping element."));
        assertTrue(reason, reason.contains(ELEMENT_CLAUSE + ", and counts only native groups"));
        assertTrue(reason, reason.contains("skippedContainers"));
        assertTrue(reason, reason.contains("layout-within-group"));
        assertNoFalseOrForeignWording(reason, 1);
        assertEquals(SpacingEntryGuardTermination.CONTAINERS_NOT_SPACED_BY_THIS_TOOL,
                SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(false, reason));
        assertEquals("a structural fact reads the same on a dry run",
                SpacingEntryGuardTermination.CONTAINERS_NOT_SPACED_BY_THIS_TOOL,
                SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(true, reason));
    }

    @Test
    public void groupStep_shouldStateTheSplit_onAMixedViewShortOfTwo() {
        IArchimateDiagramModel view = view();
        IDiagramModelArchimateObject zone = grouping(view, "Zone", 0, 0);
        step(zone, "In zone", 20, 50);
        for (int i = 0; i < 2; i++) {
            step(role(view, "Lane " + i, 600, i * 400), "Step", 20, 50);
        }

        String reason = TopLevelGroupTargets.groupStepCannotPosition("v1", view, GROUP_CLAUSE);

        assertNotNull(reason);
        assertTrue("count what is there, never 'none': " + reason, reason.startsWith(
                "View v1 holds 2 element containers (ArchiMate elements that hold children) and "
                        + "1 native group or Grouping element."));
        assertNull("the element step HAS work on this view, so it keeps it",
                TopLevelGroupTargets.elementStepCannotPosition("v1", view, ELEMENT_CLAUSE));
    }

    @Test
    public void groupStep_shouldStayOutOfTheWay_whenItsFrameAlreadyHasTwo() {
        // One populated Grouping and one EMPTY native group: the group step's frame counts both,
        // so it has a corridor and widens it exactly as before. Gating on populated containers
        // instead would have declined this view.
        IArchimateDiagramModel view = view();
        step(grouping(view, "Zone", 0, 0), "In zone", 20, 50);
        group(view, "Empty", 600, 0);
        step(role(view, "Lane", 0, 600), "Step", 20, 50);

        assertNull(TopLevelGroupTargets.groupStepCannotPosition("v1", view, GROUP_CLAUSE));
    }

    @Test
    public void nestedRefusal_shouldStillWin_overTheElementContainerReason() {
        IArchimateDiagramModel view = view();
        IDiagramModelArchimateObject node = node(view, "Region", 0, 0);
        step(grouping(node, "Zone", 20, 50), "In zone", 20, 50);
        step(role(view, "Lane", 0, 800), "Step", 20, 50);

        String reason = TopLevelGroupTargets.elementStepCannotPosition("v1", view, ELEMENT_CLAUSE);

        assertTrue(reason, reason.contains(SpacingEntryGuardTermination.NOT_POSITIONED_MARKER));
        assertFalse(SpacingEntryGuardTermination.isElementContainersReason(reason));
        assertEquals(SpacingEntryGuardTermination.CONTAINERS_NOT_POSITIONED_BY_THIS_TOOL,
                SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(false, reason));
        assertEquals("and the hosts are still listed", 2,
                TopLevelGroupTargets.describeSkippedForSpacing(view).size());
    }

    @Test
    public void flatView_shouldGetNoNewReason() {
        IArchimateDiagramModel view = view();
        step(view, "A", 0, 0);
        step(view, "B", 300, 0);
        assertNull(TopLevelGroupTargets.elementStepCannotPosition("v1", view, ELEMENT_CLAUSE));
        assertNull(TopLevelGroupTargets.groupStepCannotPosition("v1", view, GROUP_CLAUSE));
    }

    @Test
    public void mapper_shouldPreferTheStructuralCode_overAMetHeuristicTheComposerJoinedToIt() {
        String composed = "element: current spacing 80px already meets or exceeds heuristic target "
                + "60px; group: " + SpacingEntryGuardTermination.elementContainersNotSpacedByThisTool(
                        "v1", 1, false, 2, GROUP_CLAUSE);
        assertEquals(SpacingEntryGuardTermination.CONTAINERS_NOT_SPACED_BY_THIS_TOOL,
                SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(false, composed));
    }

    // ---- the decision records -------------------------------------------------------------

    @Test
    public void elementDecision_shouldCarryTheReason_inPlaceOfTheNoGroupsSentence() {
        String reason = TopLevelGroupTargets.elementStepCannotPosition("v1", fourRoleLanes().view,
                ELEMENT_CLAUSE);
        ApplyElementSpacingDecision d = ApplyElementSpacingDecision.decide(
                3, 0, 60, false, false, false, false, reason);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertEquals(reason, d.noChangeReason());
    }

    @Test
    public void groupDecision_shouldCarryTheReason_inPlaceOfFewerThanTwo() {
        String reason = TopLevelGroupTargets.groupStepCannotPosition("v1", fourRoleLanes().view,
                GROUP_CLAUSE);
        ApplyGroupSpacingDecision d = ApplyGroupSpacingDecision.decide(
                3, 0, 0, 60, false, /*hasAtLeast2TopLevelGroups=*/ false, false, false, reason);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertEquals(reason, d.noChangeReason());

        ApplyGroupSpacingDecision old = ApplyGroupSpacingDecision.decide(
                3, 0, 0, 60, false, false, false, false, null);
        assertTrue("with no element containers the old sentence is untouched",
                old.noChangeReason().startsWith("view has fewer than 2 top-level groups"));
    }

    @Test
    public void composer_shouldPrefixEachArm_andStillMapToOneCode() {
        Lanes lanes = fourRoleLanes();
        String element = TopLevelGroupTargets.elementStepCannotPosition("v1", lanes.view,
                "This arm spaces the elements inside the view's own containers");
        String group = TopLevelGroupTargets.groupStepCannotPosition("v1", lanes.view,
                "This arm inflates corridors between the view's own containers");

        ApplySpacingDecision d = ApplySpacingDecision.decide("both", 3, 0, 0, 0, 60, 80, false,
                false, false, false, false, false, false, element, group);

        assertFalse(d.shouldCallAdjustViewSpacing());
        assertEquals("element: " + element + "; group: " + group, d.noChangeReason());
        assertNoFalseOrForeignWording(d.noChangeReason(), 2);
        assertEquals(SpacingEntryGuardTermination.CONTAINERS_NOT_SPACED_BY_THIS_TOOL,
                SpacingEntryGuardTermination.mapEntryGuardToTerminationReason(false,
                        d.noChangeReason()));
    }

    // ---- the shared throw-refusal ---------------------------------------------------------

    @Test
    public void sharedRefusal_shouldNameTheElementContainers_andLayoutWithinGroup() {
        Lanes lanes = fourRoleLanes();

        ModelAccessException e = TopLevelGroupTargets.noOwnGroupingContainersRefusal("v1", lanes.view,
                "adjust-view-spacing inflates corridors between the view's own containers.");

        assertNotNull(e);
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        String message = e.getMessage();
        assertTrue(message, message.startsWith("View v1 has no native group or Grouping element "
                + "holding children, but 4 ArchiMate elements drawn on it hold children: "));
        for (IDiagramModelObject role : lanes.roles) {
            assertTrue(message.contains(role.getId()));
        }
        assertFalse(message.contains("more)"));
        assertFalse(message, message.toLowerCase(java.util.Locale.ROOT).contains("no groups"));
        assertTrue(e.getSuggestedCorrection().contains("layout-within-group"));
        assertTrue(e.getSuggestedCorrection().contains("arrange-groups does not move them"));
    }

    @Test
    public void sharedRefusal_shouldCapTheIdList_andSayHowManyItLeftOut() {
        IArchimateDiagramModel view = view();
        for (int i = 0; i < TopLevelGroupTargets.REFUSAL_ID_CAP + 3; i++) {
            step(role(view, "Lane " + i, 0, i * 400), "Step", 20, 50);
        }
        String message = TopLevelGroupTargets.noOwnGroupingContainersRefusal("v1", view, "X.")
                .getMessage();
        assertTrue(message, message.contains("(and 3 more)"));
    }

    @Test
    public void sharedRefusal_shouldStayNull_onAGenuinelyFlatView() {
        IArchimateDiagramModel view = view();
        step(view, "A", 0, 0);
        assertNull(TopLevelGroupTargets.noOwnGroupingContainersRefusal("v1", view, "X."));
    }

    // ---- the field ------------------------------------------------------------------------

    @Test
    public void field_shouldBeOmittedWhenEmpty_andCarriedByTheWither() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ApplyElementSpacingRecommendationsResultDto element =
                new ApplyElementSpacingRecommendationsResultDto("v", false, 0, 0, 60, 0, "r", null,
                        null, null, null);
        ApplyGroupSpacingRecommendationsResultDto group =
                new ApplyGroupSpacingRecommendationsResultDto("v", false, 0, 0, false, 0, 60, 0,
                        "r", null, null, null, null);
        assertFalse(mapper.writeValueAsString(element).contains("skippedContainers"));
        assertFalse(mapper.writeValueAsString(group).contains("skippedContainers"));
        assertFalse(mapper.writeValueAsString(element.withSkippedContainers(List.of()))
                .contains("skippedContainers"));

        List<SkippedContainerDto> skipped = List.of(
                new SkippedContainerDto("r1", "Lane", "BusinessRole", "x"));
        ApplyElementSpacingRecommendationsResultDto withList = element.withSkippedContainers(skipped);
        assertEquals(skipped, withList.skippedContainers());
        assertEquals("every other field is carried over", element,
                withList.withSkippedContainers(List.of()));
        assertTrue(mapper.writeValueAsString(withList).contains("\"skippedContainers\":[{"));
        assertEquals(skipped, group.withSkippedContainers(skipped).skippedContainers());
        ApplySpacingRecommendationsResultDto composed = composedDto();
        assertEquals(skipped, composed.withSkippedContainers(skipped).skippedContainers());
        assertEquals(composed, composed.withSkippedContainers(skipped).withSkippedContainers(List.of()));
    }

    // ---- fixtures -------------------------------------------------------------------------

    private record Lanes(IArchimateDiagramModel view, List<IDiagramModelObject> roles) {
    }

    /** Four BusinessRole swimlanes 40px apart, each holding steps; no group or Grouping at all. */
    private Lanes fourRoleLanes() {
        IArchimateDiagramModel view = view();
        java.util.List<IDiagramModelObject> roles = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            IDiagramModelArchimateObject role = role(view, "Lane " + (i + 1), 40, 40 + i * 240);
            for (int s = 0; s < 2 + (i % 2); s++) {
                step(role, "Step " + i + "." + s, 40 + s * 180, 60);
            }
            roles.add(role);
        }
        return new Lanes(view, roles);
    }

    private static void assertNoFalseOrForeignWording(String reason, int arms) {
        String lower = reason.toLowerCase(java.util.Locale.ROOT);
        for (String banned : List.of("no groups", "flat view", "already meets", "already at",
                "already exceeds", SpacingEntryGuardTermination.NOT_POSITIONED_MARKER)) {
            assertFalse("'" + banned + "' in: " + reason, lower.contains(banned));
        }
        assertEquals("the marker appears once per arm", arms,
                reason.split(SpacingEntryGuardTermination.NOT_SPACED_MARKER, -1).length - 1);
    }

    private ApplySpacingRecommendationsResultDto composedDto() {
        return new ApplySpacingRecommendationsResultDto("v", "both", false, 0, 0, false, false,
                0, 0, 60, 80, 0, 0, 0, 0, false, false, "r", null, null, null, null, null,
                null, 0, List.of(), null, 0, List.of(), null, null, List.of());
    }

    private IArchimateDiagramModel view() {
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        view.setId("v1");
        return view;
    }

    private IDiagramModelArchimateObject role(IDiagramModelContainer parent, String name, int x, int y) {
        return object(parent, f.createBusinessRole(), name, x, y, 560, 200);
    }

    private IDiagramModelArchimateObject node(IDiagramModelContainer parent, String name, int x, int y) {
        return object(parent, f.createNode(), name, x, y, 560, 400);
    }

    private IDiagramModelArchimateObject grouping(IDiagramModelContainer parent, String name, int x,
            int y) {
        return object(parent, f.createGrouping(), name, x, y, 400, 300);
    }

    private IDiagramModelGroup group(IDiagramModelContainer parent, String name, int x, int y) {
        IDiagramModelGroup group = f.createDiagramModelGroup();
        group.setId(java.util.UUID.randomUUID().toString());
        group.setName(name);
        group.setBounds(f.createBounds(x, y, 400, 300));
        parent.getChildren().add(group);
        return group;
    }

    private IDiagramModelArchimateObject step(IDiagramModelContainer parent, String name, int x, int y) {
        return object(parent, f.createBusinessProcess(), name, x, y, 120, 55);
    }

    private IDiagramModelArchimateObject object(IDiagramModelContainer parent,
            com.archimatetool.model.IArchimateElement concept, String name, int x, int y, int w, int h) {
        concept.setName(name);
        IDiagramModelArchimateObject dmo = f.createDiagramModelArchimateObject();
        dmo.setId(java.util.UUID.randomUUID().toString());
        dmo.setArchimateElement(concept);
        dmo.setBounds(f.createBounds(x, y, w, h));
        parent.getChildren().add(dmo);
        return dmo;
    }
}
