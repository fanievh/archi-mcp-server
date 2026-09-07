package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ImageParams;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.model.StylingParams;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.AdjustViewSpacingResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * What eight conditional {@code nextSteps} builders say, and on which dispatch arm they say it.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Each of these eight builders returns early when the mutation was queued, so every conditional
 * step it composes — an auto-connect cap, a cascade count, a crossing reduction — reaches only the
 * caller whose write already landed. On the approval arm the silence has a different mechanism and
 * the same effect: the proposal formatter replaces {@code nextSteps} wholesale with three fixed
 * lines, so a builder that hands its steps to the five-argument
 * {@code HandlerUtils.formatMutationResponse} is discarded there whatever it composed.</p>
 *
 * <p>The applied arm is pinned <em>by value</em>, list-equal rather than by substring, because a
 * rescope and a rewrite look identical to a presence check. The four incumbent assertions over
 * these builders elsewhere in the suite are all presence-only and all applied-arm: they stay green
 * through a completely wrong re-tensing, so they are not the proof.</p>
 *
 * <h2>What a deferred arm may state</h2>
 *
 * <p>Not every one of the eight owes the same disclosure, and the axis is not the field but the
 * command. A compound frozen at prepare time will do exactly what the prepare-time DTO describes,
 * so a future-tense sentence about it is true. A command that re-reads the model in
 * {@code execute()} describes something else, and restating its counts in the future tense would
 * fabricate a measurement rather than rescope one. The approval arm adds a second axis: the stored
 * proposal is a deferred <em>rebuild</em> handle, so every value there is re-derived when the human
 * approves — a count becomes a prediction, and a freshly-minted identifier becomes a guaranteed
 * wrong answer.</p>
 */
public class ConditionalStepArmScopeTest {

	/**
	 * The three lines every approval response opens with, in order.
	 *
	 * <p>Approval mode is human-owned, so these are the correct instruction for every tool and are
	 * never rescoped per tool. A disclosure is appended after them; it never replaces them.</p>
	 */
	private static final List<String> FIXED_APPROVAL_LINES = List.of(
			"This change is pending the human's approval and was NOT applied",
			"Tell the user to approve or reject it in Archi (the agent cannot approve its own changes)",
			"Use list-pending-approvals to see all changes awaiting the human's decision");

	/** The queue's own three trailing lines, appended after whatever the tool discloses. */
	private static final List<String> QUEUE_TAIL = List.of(
			"Mutation queued as operation #4 in current batch",
			"Use get-batch-status to check batch progress",
			"Use end-batch to commit all queued mutations");

	private static final int BATCH_SEQ = 4;

	private ObjectMapper objectMapper;
	private ArmStubAccessor accessor;
	private ViewPlacementHandler viewHandler;
	private SpecializationHandler specializationHandler;

	@Before
	public void setUp() {
		objectMapper = new ObjectMapper();
		accessor = new ArmStubAccessor();
		ResponseFormatter formatter = new ResponseFormatter();
		viewHandler = new ViewPlacementHandler(accessor, formatter, new CommandRegistry(), null);
		specializationHandler =
				new SpecializationHandler(accessor, formatter, new CommandRegistry(), null);
	}

	// ---- add-to-view --------------------------------------------------------------------------

	@Test
	public void addToView_appliedArm_isPinnedByValue_withAutoConnectionsAndACap() throws Exception {
		accessor.addToView = applied(addToViewDto(2, 5));

		assertEquals(List.of(
				"Use get-view-contents to verify the element placement",
				"2 connection(s) were auto-created. Use auto-connect-view later "
						+ "if more elements are added to this view",
				"Use add-connection-to-view for individual connections "
						+ "using sourceViewObjectId or targetViewObjectId 'vo-1'",
				"Auto-connect capped at 50 connections. 5 additional relationship(s) exist "
						+ "— use add-connection-to-view manually."),
				nextSteps("add-to-view", Map.of("viewId", "v-1", "elementId", "e-1")));
	}

	@Test
	public void addToView_appliedArm_isPinnedByValue_withNothingAutoConnected() throws Exception {
		accessor.addToView = applied(addToViewDto(0, null));

		assertEquals(List.of(
				"Use get-view-contents to verify the element placement",
				"Use auto-connect-view to batch-create connections for all existing "
						+ "relationships between elements on this view (recommended)",
				"Use add-connection-to-view for individual connections "
						+ "using sourceViewObjectId or targetViewObjectId 'vo-1'"),
				nextSteps("add-to-view", Map.of("viewId", "v-1", "elementId", "e-1")));
	}

	@Test
	public void addToView_queuedArm_disclosesTheCountTheCapAndKeepsTheId() throws Exception {
		accessor.addToView = queued(addToViewDto(2, 5));

		assertEquals(concat(List.of(
				"Use get-view-contents after end-batch to verify the element placement — it reads "
						+ "the committed view, which does not hold this element yet.",
				"2 connection(s) will be auto-created when this batch commits. Nothing has been "
						+ "drawn yet — end-batch rollback:true discards them with the placement.",
				"Use add-connection-to-view for individual connections using sourceViewObjectId "
						+ "or targetViewObjectId 'vo-1' — that id is already assigned, so those "
						+ "calls can be queued into this same batch.",
				"Auto-connect is capped at 50 connections. 5 additional relationship(s) exist — "
						+ "use add-connection-to-view manually for those; those calls can be "
						+ "queued into this same batch."), QUEUE_TAIL),
				nextSteps("add-to-view", map("viewId", "v-1", "elementId", "e-1")));
	}

	@Test
	public void addToView_approvalArm_disclosesTheCountAndTheCap() throws Exception {
		accessor.addToView = proposed(addToViewDto(2, 5));

		assertEquals(afterFixedLines(
				"Use get-view-contents once the human has approved the change to verify the "
						+ "element placement — it reads the committed view, which does not hold "
						+ "this element yet.",
				"2 connection(s) will be auto-created if this change is approved. The "
						+ "relationships were scanned when the change was proposed and are "
						+ "scanned again on approval, so the final count may differ.",
				"Use add-connection-to-view for individual connections once this change is "
						+ "approved. The view object does not exist yet and the id it will be "
						+ "given is not the one previewed here, so read it back from the view "
						+ "after approval.",
				"Auto-connect is capped at 50 connections. 5 additional relationship(s) existed "
						+ "when this change was proposed — once it is approved, use "
						+ "add-connection-to-view manually for whatever remains."),
				nextSteps("add-to-view", map("viewId", "v-1", "elementId", "e-1")));
	}

	/**
	 * The same field, the same builder, opposite rulings — asserted by value in both directions.
	 *
	 * <p>{@code dispatchOrQueue} enqueues the prepared command verbatim, so the object that lands
	 * at {@code end-batch} is the one whose id was reported and a queued caller can act on it. A
	 * stored proposal is a deferred <em>rebuild</em> handle: the propose-time command is discarded
	 * and a fresh one is constructed at approval, stamping a new random identifier. Reporting the
	 * previewed id there hands the agent a value that is guaranteed not to exist.</p>
	 *
	 * <p>Two pins in opposite directions over one fixture, because a re-point that keeps a single
	 * direction green proves only that the string moved.</p>
	 */
	@Test
	public void addToView_carriesThePlacementIdWhenQueuedAndNeverWhenAwaitingApproval()
			throws Exception {
		String id = "vo-distinctive-3f9c";
		AddToViewResultDto dto = new AddToViewResultDto(
				new ViewObjectDto(id, "e-1", "Actor", "BusinessActor", 10, 20, 120, 55),
				List.of(), null, List.of());

		accessor.addToView = queued(dto);
		List<String> whenQueued = nextSteps("add-to-view", map("viewId", "v-1", "elementId", "e-1"));
		assertEquals("the queued arm reports the id the queued command will create, exactly once",
				1, whenQueued.stream().filter(s -> s.contains(id)).count());

		accessor.addToView = proposed(dto);
		List<String> whenProposed = nextSteps("add-to-view", map("viewId", "v-1", "elementId", "e-1"));
		assertEquals("the approval rebuild re-mints the identifier, so no step may name the "
				+ "previewed one: " + whenProposed,
				0, whenProposed.stream().filter(s -> s.contains(id)).count());
	}

	/**
	 * A placement with nothing to disclose gains no conditional step on either deferred arm.
	 *
	 * <p>"Nothing" is this tool's two conditional selectors: no auto-connections were drawn and
	 * nothing was capped. The step naming the new view object is unconditional — every placement
	 * produces one — and is covered by the pin above rather than here.</p>
	 */
	@Test
	public void addToView_negativeControl_addsNoConditionalStepOnEitherDeferredArm()
			throws Exception {
		AddToViewResultDto clean = addToViewDto(0, null);

		accessor.addToView = queued(clean);
		List<String> whenQueued = nextSteps("add-to-view", map("viewId", "v-1", "elementId", "e-1"));
		assertNoneContains(whenQueued, "auto-created");
		assertNoneContains(whenQueued, "capped at 50");
		assertNoneContains(whenQueued, "(recommended)");

		accessor.addToView = proposed(clean);
		List<String> whenProposed = nextSteps("add-to-view", map("viewId", "v-1", "elementId", "e-1"));
		assertNoneContains(whenProposed, "auto-created");
		assertNoneContains(whenProposed, "capped at 50");
		assertNoneContains(whenProposed, "(recommended)");
	}

	// ---- update-view-connection ---------------------------------------------------------------

	@Test
	public void updateViewConnection_appliedArm_pinsAllFourTerminalBranches() throws Exception {
		accessor.updateViewConnection = applied(connectionDto());

		assertEquals("bendpoints explicitly cleared", List.of(
				"Connection bendpoints cleared (straight line). Use get-view-contents to inspect.",
				"Use update-view-connection to add bendpoints for routing."),
				nextSteps("update-view-connection",
						map("viewConnectionId", "vc-1", "bendpoints", List.of())));

		assertEquals("bendpoints set", List.of(
				"Connection bendpoints updated. Use get-view-contents to inspect.",
				"Use update-view-connection with empty bendpoints array to straighten the connection."),
				nextSteps("update-view-connection", map("viewConnectionId", "vc-1",
						"bendpoints", List.of(bendpoint()))));

		assertEquals("styling only", List.of(
				"Connection updated (bendpoints unchanged). Use get-view-contents to inspect.",
				"Provide bendpoints or absoluteBendpoints to change routing."),
				nextSteps("update-view-connection",
						map("viewConnectionId", "vc-1", "lineColor", "#ff0000")));

		// The fourth branch needs a styling object that carries nothing hasAnyValue() counts:
		// 'recede' is read by extractStylingParams and is not one of the fields hasAnyValue()
		// tests, so it produces a non-null StylingParams that reports no value.
		assertEquals("neither bendpoints nor anything hasAnyValue() sees", List.of(
				"Connection updated. Use get-view-contents to inspect.",
				"Use update-view-connection to change bendpoints, styling, or label visibility."),
				nextSteps("update-view-connection",
						map("viewConnectionId", "vc-1", "recede", Boolean.TRUE)));
	}

	@Test
	public void updateViewConnection_appliedArm_defaultsToClearingWhenNothingIsPassed()
			throws Exception {
		accessor.updateViewConnection = applied(connectionDto());

		assertEquals(List.of(
				"Connection bendpoints cleared (straight line). Use get-view-contents to inspect.",
				"Use update-view-connection to add bendpoints for routing."),
				nextSteps("update-view-connection", map("viewConnectionId", "vc-1")));
	}

	@Test
	public void updateViewConnection_queuedArm_reTensesAllFourTerminalBranches() throws Exception {
		accessor.updateViewConnection = queued(connectionDto());

		assertEquals("bendpoints explicitly cleared", concat(List.of(
				"Connection bendpoints will be cleared (straight line) when this batch commits."
						+ " Nothing is written yet — get-view-contents reads the committed view, "
						+ "so inspect after end-batch.",
				"Use update-view-connection to add bendpoints for routing."), QUEUE_TAIL),
				nextSteps("update-view-connection",
						map("viewConnectionId", "vc-1", "bendpoints", List.of())));

		assertEquals("bendpoints set", concat(List.of(
				"Connection bendpoints will be updated when this batch commits."
						+ " Nothing is written yet — get-view-contents reads the committed view, "
						+ "so inspect after end-batch.",
				"Use update-view-connection with empty bendpoints array to straighten the connection."),
				QUEUE_TAIL),
				nextSteps("update-view-connection", map("viewConnectionId", "vc-1",
						"bendpoints", List.of(bendpoint()))));

		assertEquals("styling only", concat(List.of(
				"Connection will be updated when this batch commits (bendpoints unchanged)."
						+ " Nothing is written yet — get-view-contents reads the committed view, "
						+ "so inspect after end-batch.",
				"Provide bendpoints or absoluteBendpoints to change routing."), QUEUE_TAIL),
				nextSteps("update-view-connection",
						map("viewConnectionId", "vc-1", "lineColor", "#ff0000")));

		assertEquals("neither bendpoints nor anything hasAnyValue() sees", concat(List.of(
				"Connection will be updated when this batch commits."
						+ " Nothing is written yet — get-view-contents reads the committed view, "
						+ "so inspect after end-batch.",
				"Use update-view-connection to change bendpoints, styling, or label visibility."),
				QUEUE_TAIL),
				nextSteps("update-view-connection",
						map("viewConnectionId", "vc-1", "recede", Boolean.TRUE)));
	}

	@Test
	public void updateViewConnection_approvalArm_reTensesAllFourTerminalBranches() throws Exception {
		accessor.updateViewConnection = proposed(connectionDto());

		assertEquals("bendpoints explicitly cleared", afterFixedLines(
				"Connection bendpoints will be cleared (straight line) if this change is approved."
						+ " Nothing is written yet — get-view-contents reads the committed view, "
						+ "so inspect once the human has approved the change.",
				"Use update-view-connection to add bendpoints for routing."),
				nextSteps("update-view-connection",
						map("viewConnectionId", "vc-1", "bendpoints", List.of())));

		assertEquals("bendpoints set", afterFixedLines(
				"Connection bendpoints will be updated if this change is approved."
						+ " Nothing is written yet — get-view-contents reads the committed view, "
						+ "so inspect once the human has approved the change.",
				"Use update-view-connection with empty bendpoints array to straighten the connection."),
				nextSteps("update-view-connection", map("viewConnectionId", "vc-1",
						"bendpoints", List.of(bendpoint()))));

		assertEquals("styling only", afterFixedLines(
				"Connection will be updated if this change is approved (bendpoints unchanged)."
						+ " Nothing is written yet — get-view-contents reads the committed view, "
						+ "so inspect once the human has approved the change.",
				"Provide bendpoints or absoluteBendpoints to change routing."),
				nextSteps("update-view-connection",
						map("viewConnectionId", "vc-1", "lineColor", "#ff0000")));

		assertEquals("neither bendpoints nor anything hasAnyValue() sees", afterFixedLines(
				"Connection will be updated if this change is approved."
						+ " Nothing is written yet — get-view-contents reads the committed view, "
						+ "so inspect once the human has approved the change.",
				"Use update-view-connection to change bendpoints, styling, or label visibility."),
				nextSteps("update-view-connection",
						map("viewConnectionId", "vc-1", "recede", Boolean.TRUE)));
	}

	/**
	 * All six selectors stay request values, and the builder keeps reading no DTO field.
	 *
	 * <p>This tool is the one of the eight whose guidance is chosen entirely from what the caller
	 * asked for, evaluated before the accessor is even called. That is legitimate here — the four
	 * branches describe the <em>request's shape</em>, not the write's outcome — and it is exactly
	 * what makes the re-tensing safe on a deferred arm, where no outcome exists to read.</p>
	 *
	 * <p>It is also one edit away from becoming the defect the effective-state rule names: a field
	 * sourced from the request under an outcome's name. So the absence is pinned rather than
	 * assumed, over comment-stripped source, with a positive control proving the probe can see the
	 * read it claims is missing.</p>
	 */
	@Test
	public void updateViewConnectionBuilderReadsNoDtoField() {
		String builder = stripComments(methodBody("buildUpdateViewConnectionNextSteps"));
		String control = stripComments(methodBody("addToViewDisclosures"));

		assertTrue("the probe must be able to see an entity read before it reports the absence "
				+ "of one — the control body was " + control.length() + " chars",
				control.contains("entity"));
		assertTrue("the builder body must be real, not an empty parse: " + builder.length()
				+ " chars", builder.length() > 400);
		assertFalse("this builder must keep choosing its branch from the request alone; reading "
				+ "the DTO here would source an outcome from the ask under an outcome's name:\n"
				+ builder, builder.contains("entity"));
	}

	// ---- remove-from-view ---------------------------------------------------------------------

	@Test
	public void removeFromView_appliedArm_pinsBothObjectTypesAndTheCascade() throws Exception {
		accessor.removeFromView = applied(
				new RemoveFromViewResultDto("vo-1", "viewObject", List.of("vc-1", "vc-2")));
		assertEquals("a view object with a cascade", List.of(
				"Element removed from view (2 connections also removed). "
						+ "Underlying model element is unchanged.",
				"Use get-view-contents to inspect the current view layout.",
				"Use add-to-view to place the element back on the view."),
				nextSteps("remove-from-view", map("viewId", "v-1", "viewObjectId", "vo-1")));

		accessor.removeFromView = applied(
				new RemoveFromViewResultDto("vo-1", "viewObject", List.of()));
		assertEquals("a view object with no cascade", List.of(
				"Element removed from view. Underlying model element is unchanged.",
				"Use get-view-contents to inspect the current view layout.",
				"Use add-to-view to place the element back on the view."),
				nextSteps("remove-from-view", map("viewId", "v-1", "viewObjectId", "vo-1")));

		accessor.removeFromView = applied(
				new RemoveFromViewResultDto("vc-1", "viewConnection", null));
		assertEquals("a connection", List.of(
				"Connection removed from view. Underlying model relationship is unchanged.",
				"Use get-view-contents to inspect the current view layout.",
				"Use add-connection-to-view to add a connection back."),
				nextSteps("remove-from-view", map("viewId", "v-1", "viewObjectId", "vc-1")));
	}

	@Test
	public void removeFromView_queuedArm_carriesTheTypeTheCascadeAndARescopedReversal()
			throws Exception {
		accessor.removeFromView = queued(
				new RemoveFromViewResultDto("vo-1", "viewObject", List.of("vc-1", "vc-2")));
		assertEquals("a view object with a cascade", concat(List.of(
				"Element will be removed from view when this batch commits "
						+ "(2 connections will also be removed). "
						+ "Underlying model element is unchanged.",
				"Use get-view-contents after end-batch to inspect the view layout — it reads the "
						+ "committed view, which still holds this object until then.",
				"Nothing has been removed yet: end-batch rollback:true discards this removal. "
						+ "Calling add-to-view instead would queue a second placement rather than "
						+ "restore anything."), QUEUE_TAIL),
				nextSteps("remove-from-view", map("viewId", "v-1", "viewObjectId", "vo-1")));

		accessor.removeFromView = queued(
				new RemoveFromViewResultDto("vc-1", "viewConnection", null));
		assertEquals("a connection", concat(List.of(
				"Connection will be removed from view when this batch commits. "
						+ "Underlying model relationship is unchanged.",
				"Use get-view-contents after end-batch to inspect the view layout — it reads the "
						+ "committed view, which still holds this object until then.",
				"Nothing has been removed yet: end-batch rollback:true discards this removal. "
						+ "Calling add-connection-to-view instead would queue a second connection "
						+ "rather than restore anything."), QUEUE_TAIL),
				nextSteps("remove-from-view", map("viewId", "v-1", "viewObjectId", "vc-1")));
	}

	@Test
	public void removeFromView_approvalArm_carriesTheTypeTheCascadeAndARescopedReversal()
			throws Exception {
		accessor.removeFromView = proposed(
				new RemoveFromViewResultDto("vo-1", "viewObject", List.of("vc-1", "vc-2")));
		assertEquals("a view object with a cascade", afterFixedLines(
				"Element will be removed from view if this change is approved "
						+ "(2 connections will also be removed, re-counted on approval). "
						+ "Underlying model element is unchanged.",
				"Use get-view-contents once the human has approved the change to inspect the view "
						+ "layout — it reads the committed view, which still holds this object "
						+ "until then.",
				"Nothing has been removed yet: rejecting the change in Archi leaves the object "
						+ "where it is. Calling add-to-view instead would propose a second "
						+ "placement rather than restore anything."),
				nextSteps("remove-from-view", map("viewId", "v-1", "viewObjectId", "vo-1")));

		accessor.removeFromView = proposed(
				new RemoveFromViewResultDto("vc-1", "viewConnection", null));
		assertEquals("a connection", afterFixedLines(
				"Connection will be removed from view if this change is approved. "
						+ "Underlying model relationship is unchanged.",
				"Use get-view-contents once the human has approved the change to inspect the view "
						+ "layout — it reads the committed view, which still holds this object "
						+ "until then.",
				"Nothing has been removed yet: rejecting the change in Archi leaves the object "
						+ "where it is. Calling add-connection-to-view instead would propose a "
						+ "second connection rather than restore anything."),
				nextSteps("remove-from-view", map("viewId", "v-1", "viewObjectId", "vc-1")));
	}

	/**
	 * The one clause in this builder that is true on every arm reads identically on all three.
	 *
	 * <p>Removing an object from a view never touches the concept behind it, whether the removal
	 * has landed, is queued, or is waiting on a human. It is also the most useful thing in a
	 * deferred response: an agent that has queued a destructive view operation wants to know the
	 * model concepts survive it. Pinned byte-for-byte so a rescope of the sentences around it
	 * cannot drag it along.</p>
	 */
	@Test
	public void removeFromView_modelUnchangedClauseIsIdenticalOnAllThreeArms() throws Exception {
		RemoveFromViewResultDto object = new RemoveFromViewResultDto("vo-1", "viewObject", List.of());
		RemoveFromViewResultDto connection =
				new RemoveFromViewResultDto("vc-1", "viewConnection", null);
		Map<String, Object> args = map("viewId", "v-1", "viewObjectId", "vo-1");

		for (String clause : List.of(" Underlying model element is unchanged.")) {
			accessor.removeFromView = applied(object);
			assertEndsWith(nextSteps("remove-from-view", args).get(0), clause);
			accessor.removeFromView = queued(object);
			assertEndsWith(nextSteps("remove-from-view", args).get(0), clause);
			accessor.removeFromView = proposed(object);
			assertEndsWith(nextSteps("remove-from-view", args).get(FIXED_APPROVAL_LINES.size()),
					clause);
		}
		for (String clause : List.of(" Underlying model relationship is unchanged.")) {
			accessor.removeFromView = applied(connection);
			assertEndsWith(nextSteps("remove-from-view", args).get(0), clause);
			accessor.removeFromView = queued(connection);
			assertEndsWith(nextSteps("remove-from-view", args).get(0), clause);
			accessor.removeFromView = proposed(connection);
			assertEndsWith(nextSteps("remove-from-view", args).get(FIXED_APPROVAL_LINES.size()),
					clause);
		}
	}

	@Test
	public void removeFromView_negativeControl_namesNoCascadeWhenThereIsNone() throws Exception {
		RemoveFromViewResultDto clean =
				new RemoveFromViewResultDto("vo-1", "viewObject", List.of());
		Map<String, Object> args = map("viewId", "v-1", "viewObjectId", "vo-1");

		accessor.removeFromView = queued(clean);
		assertNoneContains(nextSteps("remove-from-view", args), "also be removed");
		accessor.removeFromView = proposed(clean);
		assertNoneContains(nextSteps("remove-from-view", args), "also be removed");
	}

	// ---- clear-view ---------------------------------------------------------------------------

	@Test
	public void clearView_appliedArm_pinsBothSummaryShapes() throws Exception {
		accessor.clearView = applied(new ClearViewResultDto("v-1", "View", 12, 30, 0));
		assertEquals("no non-ArchiMate objects", List.of(
				"View cleared: 12 object(s) and 30 connection(s) removed."
						+ " Underlying model objects are unchanged.",
				"Use get-view-contents to verify the view is empty.",
				"Use add-to-view to re-populate the view with elements."),
				nextSteps("clear-view", map("viewId", "v-1")));

		accessor.clearView = applied(new ClearViewResultDto("v-1", "View", 12, 30, 4));
		assertEquals("with non-ArchiMate objects", List.of(
				"View cleared: 12 object(s) and 30 connection(s) removed."
						+ " (4 non-ArchiMate object(s) such as Notes/Groups were also removed.)"
						+ " Underlying model objects are unchanged.",
				"Use get-view-contents to verify the view is empty.",
				"Use add-to-view to re-populate the view with elements."),
				nextSteps("clear-view", map("viewId", "v-1")));
	}

	/**
	 * The counts are withheld on both deferred arms, and the reason is mechanical.
	 *
	 * <p>Every other tool here keeps its measurement and rescopes only the remedy, because its
	 * command is frozen at prepare time and will do exactly what the prepare-time report describes.
	 * This command is the exception: it re-walks the view inside {@code execute()} and clears
	 * whatever it finds there, so the counts taken at prepare describe a different model than the
	 * one it will empty. Inside an open batch, everything queued between the two walks is invisible
	 * to the first and removed by the second.</p>
	 *
	 * <p>Restating them in the future tense would not be a rescoped remedy — it would be a
	 * fabricated measurement, which is worse than the silence it replaced.</p>
	 */
	@Test
	public void clearView_deferredArms_carryNoneOfTheThreeCounts() throws Exception {
		// Distinctive values, so the assertion is about these numbers rather than about a phrase
		// somebody could reword around.
		ClearViewResultDto dto = new ClearViewResultDto("v-1", "View", 4711, 8329, 6553);

		accessor.clearView = queued(dto);
		List<String> whenQueued = nextSteps("clear-view", map("viewId", "v-1"));
		for (String count : List.of("4711", "8329", "6553")) {
			assertNoneContains(whenQueued, count);
		}

		accessor.clearView = proposed(dto);
		List<String> whenProposed = nextSteps("clear-view", map("viewId", "v-1"));
		for (String count : List.of("4711", "8329", "6553")) {
			assertNoneContains(whenProposed, count);
		}
	}

	@Test
	public void clearView_queuedArm_saysWhatWillHappenWithoutSayingHowMuch() throws Exception {
		accessor.clearView = queued(new ClearViewResultDto("v-1", "View", 12, 30, 4));

		assertEquals(concat(List.of(
				"The view will be cleared when this batch commits. What it removes is not counted "
						+ "here: the command re-walks the view as it runs, so it will also remove "
						+ "whatever else this batch queues onto the view before then."
						+ " Underlying model objects are unchanged.",
				"Use get-view-contents after end-batch to verify the view is empty — it reads the "
						+ "committed view, which is still populated until then.",
				"Use add-to-view to re-populate the view. Queue those placements after end-batch, "
						+ "not into this batch: the clear removes whatever the view holds when it "
						+ "runs, this batch's own additions included."), QUEUE_TAIL),
				nextSteps("clear-view", map("viewId", "v-1")));
	}

	@Test
	public void clearView_approvalArm_saysWhatWillHappenWithoutSayingHowMuch() throws Exception {
		accessor.clearView = proposed(new ClearViewResultDto("v-1", "View", 12, 30, 4));

		assertEquals(afterFixedLines(
				"The view will be cleared if this change is approved. What it removes is not "
						+ "counted here: the command re-walks the view as it runs, so whatever the "
						+ "view holds at that moment is what it takes."
						+ " Underlying model objects are unchanged.",
				"Use get-view-contents once the human has approved the change to verify the view "
						+ "is empty — it reads the committed view, which is still populated until "
						+ "then.",
				"Use add-to-view to re-populate the view once this change has been approved or "
						+ "rejected."),
				nextSteps("clear-view", map("viewId", "v-1")));
	}

	@Test
	public void clearView_modelUnchangedClauseIsIdenticalOnAllThreeArms() throws Exception {
		String clause = " Underlying model objects are unchanged.";
		ClearViewResultDto dto = new ClearViewResultDto("v-1", "View", 12, 30, 0);
		Map<String, Object> args = map("viewId", "v-1");

		accessor.clearView = applied(dto);
		assertEndsWith(nextSteps("clear-view", args).get(0), clause);
		accessor.clearView = queued(dto);
		assertEndsWith(nextSteps("clear-view", args).get(0), clause);
		accessor.clearView = proposed(dto);
		assertEndsWith(nextSteps("clear-view", args).get(FIXED_APPROVAL_LINES.size()), clause);
	}

	@Test
	public void clearView_negativeControl_namesNoNonArchimateObjectsWhenThereAreNone()
			throws Exception {
		ClearViewResultDto clean = new ClearViewResultDto("v-1", "View", 12, 30, 0);
		Map<String, Object> args = map("viewId", "v-1");

		accessor.clearView = applied(clean);
		assertNoneContains(nextSteps("clear-view", args), "non-ArchiMate");
		accessor.clearView = queued(clean);
		assertNoneContains(nextSteps("clear-view", args), "non-ArchiMate");
		accessor.clearView = proposed(clean);
		assertNoneContains(nextSteps("clear-view", args), "non-ArchiMate");
	}

	// ---- optimize-group-order -----------------------------------------------------------------

	@Test
	public void optimizeGroupOrder_appliedArm_pinsTheReductionAndTheAlreadyOptimalBranch()
			throws Exception {
		accessor.optimizeGroupOrder = applied(optimizeDto(5, 2, 60.0, 2));
		assertEquals("crossings reduced", List.of(
				"Crossings reduced from 5 to 2 (60.0% reduction).",
				"Use auto-route-connections to compute orthogonal paths for inter-group connections.",
				"Use assess-layout to evaluate final crossing count and layout quality.",
				"Notes inside groups may need manual repositioning after element reordering.",
				"Use export-view to visually verify the optimized layout."),
				nextSteps("optimize-group-order", map("viewId", "v-1")));

		accessor.optimizeGroupOrder = applied(optimizeDto(4, 4, 0.0, 0));
		assertEquals("nothing to reorder", List.of(
				"Element order is already optimal — no reordering was needed.",
				"Use auto-route-connections to compute orthogonal paths for inter-group connections.",
				"Use assess-layout to evaluate final crossing count and layout quality.",
				"Use export-view to visually verify the optimized layout."),
				nextSteps("optimize-group-order", map("viewId", "v-1")));
	}

	@Test
	public void optimizeGroupOrder_deferredArms_carryTheCrossingsAndTheNotesWarning()
			throws Exception {
		accessor.optimizeGroupOrder = queued(optimizeDto(5, 2, 60.0, 2));
		assertEquals(concat(List.of(
				"Crossings will fall from 5 to 2 (60.0% reduction) when this batch commits. The "
						+ "count is a simulation over the proposed ordering, not a reading of the "
						+ "view.",
				"Notes inside groups may need manual repositioning once this batch commits and "
						+ "the elements are reordered."), QUEUE_TAIL),
				nextSteps("optimize-group-order", map("viewId", "v-1")));

		accessor.optimizeGroupOrder = proposed(optimizeDto(5, 2, 60.0, 2));
		assertEquals(afterFixedLines(
				"Crossings will fall from 5 to 2 (60.0% reduction) if this change is approved. The "
						+ "count is a simulation over the proposed ordering, and it is re-run "
						+ "against the view as it then stands.",
				"Notes inside groups may need manual repositioning once this change is approved "
						+ "and the elements are reordered."),
				nextSteps("optimize-group-order", map("viewId", "v-1")));
	}

	/**
	 * The already-optimal sentence is not written for a deferred arm, and cannot be reached there.
	 *
	 * <p>This is the builder half of the claim: even handed a DTO that selects that branch, no
	 * deferred response emits it, because no deferred wording for it exists. The other half — that
	 * such a DTO can never actually arrive on a deferred arm, because the accessor returns above
	 * both the approval gate and the queue when it has no commands to dispatch — is pinned against
	 * the real accessor in {@code UnreachableDeferredBranchTest}, with a positive control.</p>
	 */
	@Test
	public void optimizeGroupOrder_deferredArms_neverSayTheOrderIsAlreadyOptimal()
			throws Exception {
		OptimizeGroupOrderResultDto nothingToDo = optimizeDto(4, 4, 0.0, 0);

		accessor.optimizeGroupOrder = queued(nothingToDo);
		List<String> whenQueued = nextSteps("optimize-group-order", map("viewId", "v-1"));
		assertNoneContains(whenQueued, "already optimal");
		assertEquals("with nothing measured and nothing reordered, a queued call discloses "
				+ "nothing at all: " + whenQueued, QUEUE_TAIL, whenQueued);

		accessor.optimizeGroupOrder = proposed(nothingToDo);
		List<String> whenProposed = nextSteps("optimize-group-order", map("viewId", "v-1"));
		assertNoneContains(whenProposed, "already optimal");
		assertEquals("and an awaiting-approval one carries only the fixed lines: " + whenProposed,
				FIXED_APPROVAL_LINES, whenProposed);
	}

	// ---- adjust-view-spacing ------------------------------------------------------------------

	@Test
	public void adjustViewSpacing_appliedArm_pinsBothCoincidentSegmentBranches() throws Exception {
		accessor.adjustViewSpacing = applied(spacingDto(3));
		assertEquals("coincident segments remain", List.of(
				"Use assess-layout to verify the quality improvement.",
				"Coincident segments remain — try a larger interElementDelta.",
				"Use undo to roll back if the result is unsatisfactory."),
				nextSteps("adjust-view-spacing", map("viewId", "v-1", "interElementDelta", 40)));

		accessor.adjustViewSpacing = applied(spacingDto(0));
		assertEquals("none remain", List.of(
				"Use assess-layout to verify the quality improvement.",
				"Use undo to roll back if the result is unsatisfactory."),
				nextSteps("adjust-view-spacing", map("viewId", "v-1", "interElementDelta", 40)));
	}

	/**
	 * This tool has one deferred arm, not two, and its {@code undo} remedy does not survive it.
	 *
	 * <p>{@code adjustViewSpacing} consults no approval gate and stores no proposal — both of its
	 * returns use the two-argument result constructor, which cannot carry a proposal context — so
	 * no approval-tense wording is written for it. That absence is pinned on the mechanism in
	 * {@code DeferredArmDisclosureTest}, which this story's census entry extends to a fourth
	 * member of the family.</p>
	 *
	 * <p>Its queued arm is real, and letting the conditional step through newly exposes the
	 * unconditional {@code undo} line that sat below the early return. Nothing has been applied on
	 * that arm, so undo would revert whichever command is on top of the stack — somebody else's.</p>
	 */
	@Test
	public void adjustViewSpacing_queuedArm_carriesTheRemedyAndPrescribesNoUndo() throws Exception {
		accessor.adjustViewSpacing = queued(spacingDto(3));
		List<String> steps = nextSteps("adjust-view-spacing",
				map("viewId", "v-1", "interElementDelta", 40));

		assertEquals(concat(List.of(
				"Use assess-layout after end-batch to verify the quality improvement — it reads "
						+ "the committed view, which this batch has not changed yet.",
				"Coincident segments will remain once this batch commits — re-issue with a larger "
						+ "interElementDelta.",
				"Nothing has been applied yet, so undo would revert whichever command is on top of "
						+ "the stack; discard this spacing change with end-batch rollback:true "
						+ "instead."), QUEUE_TAIL),
				steps);

		assertNoneContains(steps, "Use undo");
	}

	@Test
	public void adjustViewSpacing_negativeControl_namesNoCoincidentSegmentsWhenThereAreNone()
			throws Exception {
		accessor.adjustViewSpacing = queued(spacingDto(0));
		List<String> steps = nextSteps("adjust-view-spacing",
				map("viewId", "v-1", "interElementDelta", 40));

		assertNoneContains(steps, "Coincident segments");
		assertNoneContains(steps, "Use undo");
	}

	// ---- create-specialization ----------------------------------------------------------------

	@Test
	public void createSpecialization_appliedArm_pinsBothBranches() throws Exception {
		accessor.createSpecialization = applied(specializationDto("Cloud Server", true));
		assertEquals("newly created", List.of(
				"Use create-element with specialization='Cloud Server' to instantiate an element "
						+ "of this specialization",
				"Use list-specializations to browse the model's vocabulary",
				"Use get-specialization-usage to audit usage later"),
				nextSteps("create-specialization",
						map("name", "Cloud Server", "conceptType", "Node")));

		accessor.createSpecialization = applied(specializationDto("Cloud Server", false));
		assertEquals("already existed", List.of(
				"Specialization already existed — no model change",
				"Use list-specializations to see all defined specializations",
				"Use create-element with this specialization to instantiate it"),
				nextSteps("create-specialization",
						map("name", "Cloud Server", "conceptType", "Node")));
	}

	// ---- delete-specialization ----------------------------------------------------------------

	@Test
	public void deleteSpecialization_appliedArm_pinsBothClearedBranches() throws Exception {
		accessor.deleteSpecialization = applied(deletionDto(2));
		assertEquals("concepts were cleared", List.of(
				"Cleared specialization from 2 concepts",
				"Use list-specializations to verify the deletion",
				"Use undo to revert if this was unintentional"),
				nextSteps("delete-specialization",
						map("name", "Cloud Server", "conceptType", "Node")));

		accessor.deleteSpecialization = applied(deletionDto(1));
		assertEquals("exactly one concept — singular", List.of(
				"Cleared specialization from 1 concept",
				"Use list-specializations to verify the deletion",
				"Use undo to revert if this was unintentional"),
				nextSteps("delete-specialization",
						map("name", "Cloud Server", "conceptType", "Node")));

		accessor.deleteSpecialization = applied(deletionDto(0));
		assertEquals("nothing was cleared", List.of(
				"Use list-specializations to verify the deletion",
				"Use undo to revert if this was unintentional"),
				nextSteps("delete-specialization",
						map("name", "Cloud Server", "conceptType", "Node")));
	}

	@Test
	public void createSpecialization_deferredArms_carryTheNameAndTheFollowUps() throws Exception {
		accessor.createSpecialization = queued(specializationDto("Cloud Server", true));
		assertEquals(concat(List.of(
				"Once this batch commits, use create-element with specialization='Cloud Server' "
						+ "to instantiate an element of this specialization",
				"Use list-specializations to browse the model's vocabulary — it reads the "
						+ "committed model, so this specialization appears there after end-batch.",
				"Use get-specialization-usage to audit usage later"), QUEUE_TAIL),
				nextSteps("create-specialization",
						map("name", "Cloud Server", "conceptType", "Node")));

		accessor.createSpecialization = proposed(specializationDto("Cloud Server", true));
		assertEquals(afterFixedLines(
				"Once this change is approved, use create-element with specialization="
						+ "'Cloud Server' to instantiate an element of this specialization",
				"Use list-specializations to browse the model's vocabulary — it reads the "
						+ "committed model, so this specialization appears there once the change "
						+ "is approved.",
				"Use get-specialization-usage to audit usage later"),
				nextSteps("create-specialization",
						map("name", "Cloud Server", "conceptType", "Node")));
	}

	/**
	 * The already-existed sentence is not written for a deferred arm, and cannot be reached there.
	 *
	 * <p>{@code created: false} is produced on exactly one path — an existing profile, returned
	 * with a no-op command — and that path short-circuits above both the approval gate and the
	 * queue. The accessor half of the claim is pinned in {@code UnreachableDeferredBranchTest};
	 * this is the builder half, that no deferred wording for it exists to be selected.</p>
	 */
	@Test
	public void createSpecialization_deferredArms_neverSayTheSpecializationAlreadyExisted()
			throws Exception {
		Map<String, Object> duplicate = specializationDto("Cloud Server", false);
		Map<String, Object> args = map("name", "Cloud Server", "conceptType", "Node");

		accessor.createSpecialization = queued(duplicate);
		assertNoneContains(nextSteps("create-specialization", args), "already existed");
		accessor.createSpecialization = proposed(duplicate);
		assertNoneContains(nextSteps("create-specialization", args), "already existed");
	}

	/**
	 * The cleared count is a reading the commit can move EITHER WAY, and it must not be a floor.
	 *
	 * <p>The accessor builds one {@code ClearSpecializationCommand} per prepare-time usage and
	 * appends one {@code DeleteProfileCommand}. Both re-derive at execute, in opposite directions:
	 * the delete command clears a usage attached after the prepare, taking the total <em>above</em>
	 * the counted set — which is the direction the accessor's own comment documents and the only
	 * one this wording used to admit. But each individual clear also re-checks, and
	 * {@code ClearSpecializationCommand.describeUnauthorisedProfiles} declines for any concept that
	 * has since acquired another specialization, taking the total <em>below</em> it, to zero in the
	 * limit.</p>
	 *
	 * <p>So "at least N" was a guarantee the commit can break. The count is a prepare-time reading
	 * and the wording must say which way it can move — both ways — rather than fixing a bound at
	 * one end. Asserted here by banning the floor phrasing outright, not merely by matching the
	 * replacement: a later edit that reintroduces a bound in either direction fails.</p>
	 */
	@Test
	public void deleteSpecialization_deferredArms_stateTheCountAsAReadingNotABound()
			throws Exception {
		Map<String, Object> boundArgs = map("name", "Cloud Server", "conceptType", "Node");

		for (MutationResult<Map<String, Object>> deferred :
				List.of(queued(deletionDto(3)), proposed(deletionDto(3)))) {
			accessor.deleteSpecialization = deferred;
			List<String> steps = nextSteps("delete-specialization", boundArgs);
			assertNoneContains(steps, "at least");
			assertNoneContains(steps, "at most");
			assertNoneContains(steps, "no more than");
			assertNoneContains(steps, "no fewer than");
			assertTrue("a deferred arm must say the count can move DOWN as well as up — the "
					+ "direction the accessor's own comment does not document: " + steps,
					steps.stream().anyMatch(s -> s.contains("fewer")));
			assertTrue("and it must still name the upward direction: " + steps,
					steps.stream().anyMatch(s -> s.contains("as well")));
		}
	}

	@Test
	public void deleteSpecialization_deferredArms_nameTheCountAndPrescribeNoUndo()
			throws Exception {
		Map<String, Object> args = map("name", "Cloud Server", "conceptType", "Node");

		accessor.deleteSpecialization = queued(deletionDto(2));
		List<String> whenQueued = nextSteps("delete-specialization", args);
		assertEquals(concat(List.of(
				"2 concepts carried this specialization when the deletion was queued. That is a "
						+ "reading, not a promise: at commit each clear re-checks and is declined "
						+ "for any concept that has since acquired a different specialization, so "
						+ "fewer can be cleared — while a concept that acquires this one is "
						+ "cleared as well. end-batch names every operation it skipped, with the "
						+ "reason.",
				"Use list-specializations after end-batch to verify the deletion — it reads the "
						+ "committed model, which still holds this specialization until then.",
				"Nothing has been deleted yet, so undo would revert whichever command is on top of "
						+ "the stack; discard this deletion with end-batch rollback:true instead."),
				QUEUE_TAIL), whenQueued);
		assertNoneContains(whenQueued, "Use undo");

		accessor.deleteSpecialization = proposed(deletionDto(1));
		List<String> whenProposed = nextSteps("delete-specialization", args);
		assertEquals(afterFixedLines(
				"1 concept carries this specialization now. That is a reading, not a promise: the "
						+ "deletion is re-prepared when it is approved and each clear re-checks as "
						+ "it runs, so fewer can be cleared if a concept has acquired a different "
						+ "specialization by then — while a concept that acquires this one is "
						+ "cleared as well.",
				"Use list-specializations once the human has approved the change to verify the "
						+ "deletion — it reads the committed model, which still holds this "
						+ "specialization until then.",
				"Nothing has been deleted yet — rejecting the change in Archi leaves the "
						+ "specialization in place, and no tool the agent can call recovers it."),
				whenProposed);
		assertNoneContains(whenProposed, "Use undo");
	}

	/**
	 * Both branches of the approval arm's verb agreement, pinned by value.
	 *
	 * <p>The deferred wordings lead with the noun phrase, so the verb has to agree with a count the
	 * caller chose. The first draft of this sentence did not, and shipped "1 concept carry this
	 * specialization" — caught only because the arm pin above happens to use a count of one. A
	 * ternary needs a fixture on each side or half of it is unread.</p>
	 */
	@Test
	public void deleteSpecialization_approvalArm_agreesWithTheCountBothWays() throws Exception {
		Map<String, Object> args = map("name", "Cloud Server", "conceptType", "Node");

		accessor.deleteSpecialization = proposed(deletionDto(1));
		assertTrue("a count of one takes the singular verb",
				nextSteps("delete-specialization", args).stream()
						.anyMatch(s -> s.startsWith("1 concept carries this specialization now.")));

		accessor.deleteSpecialization = proposed(deletionDto(4));
		assertTrue("a count above one takes the plural",
				nextSteps("delete-specialization", args).stream()
						.anyMatch(s -> s.startsWith("4 concepts carry this specialization now.")));
	}

	@Test
	public void deleteSpecialization_negativeControl_namesNoCountWhenNothingIsCleared()
			throws Exception {
		Map<String, Object> args = map("name", "Cloud Server", "conceptType", "Node");

		accessor.deleteSpecialization = queued(deletionDto(0));
		List<String> whenQueued = nextSteps("delete-specialization", args);
		assertNoneContains(whenQueued, "carried this specialization");
		assertNoneContains(whenQueued, "carry this specialization");
		assertNoneContains(whenQueued, "Use undo");

		accessor.deleteSpecialization = proposed(deletionDto(0));
		List<String> whenProposed = nextSteps("delete-specialization", args);
		assertNoneContains(whenProposed, "carried this specialization");
		assertNoneContains(whenProposed, "carry this specialization");
		assertNoneContains(whenProposed, "Use undo");
	}

	/**
	 * Every batched arm in this handler now names {@code get-batch-status}, as the other eight do.
	 *
	 * <p>Measured before this change: {@code SpecializationHandler} was the only handler in the
	 * tree with batched arms and zero occurrences of that line — three builders, all two lines
	 * where every other handler emits three. Nothing makes the call unavailable for a
	 * specialization mutation, so an agent reading two lines here had no way to tell the
	 * difference from an oversight. Pinned on {@code update-specialization} as well, which this
	 * story otherwise does not touch, so the third builder is not left unguarded.</p>
	 */
	@Test
	public void everySpecializationBatchedArmNamesGetBatchStatus() throws Exception {
		accessor.createSpecialization = queued(specializationDto("Cloud Server", true));
		assertQueueTail(nextSteps("create-specialization",
				map("name", "Cloud Server", "conceptType", "Node")));

		accessor.updateSpecialization = queued(specializationDto("Cloud Server", true));
		assertQueueTail(nextSteps("update-specialization",
				map("name", "Cloud Server", "conceptType", "Node", "newName", "Server")));

		accessor.deleteSpecialization = queued(deletionDto(0));
		assertQueueTail(nextSteps("delete-specialization",
				map("name", "Cloud Server", "conceptType", "Node")));
	}

	private static void assertQueueTail(List<String> steps) {
		assertEquals("the last three steps must be the standard queue tail, was: " + steps,
				QUEUE_TAIL, steps.subList(steps.size() - QUEUE_TAIL.size(), steps.size()));
	}

	// ---- the out-of-scope regression pin -------------------------------------------------------

	/**
	 * A tool that discloses nothing on the approval arm still carries exactly three lines.
	 *
	 * <p>Every call site in the two handlers this story edits moves from the five-argument
	 * {@code formatMutationResponse} to the six-argument overload. The overload appends; it must
	 * never reorder, replace or duplicate the fixed lines, and a tool that was never in scope must
	 * be indistinguishable afterwards from what it was before.</p>
	 */
	@Test
	public void anOutOfScopeToolsApprovalResponseIsExactlyTheThreeFixedLines() throws Exception {
		accessor.updateSpecialization = proposed(specializationDto("Cloud Server", true));

		assertEquals(FIXED_APPROVAL_LINES,
				nextSteps("update-specialization",
						map("name", "Cloud Server", "conceptType", "Node", "newName", "Server")));
	}

	// ---- assertions ----------------------------------------------------------------------------

	/**
	 * The whole approval response: the three fixed lines first, then what the tool disclosed.
	 *
	 * <p>Asserted as one list rather than as "contains", so a disclosure that displaced a fixed
	 * line, reordered them, or arrived twice cannot pass.</p>
	 */
	private static List<String> afterFixedLines(String... disclosures) {
		return concat(FIXED_APPROVAL_LINES, List.of(disclosures));
	}

	private static List<String> concat(List<String> head, List<String> tail) {
		List<String> all = new ArrayList<>(head);
		all.addAll(tail);
		return all;
	}

	private static void assertEndsWith(String step, String clause) {
		assertTrue("the step must end with the byte-identical clause '" + clause + "', was: "
				+ step, step.endsWith(clause));
	}

	private static void assertNoneContains(List<String> steps, String fragment) {
		assertEquals("no step may carry '" + fragment + "': " + steps,
				0, steps.stream().filter(s -> s.contains(fragment)).count());
	}

	// ---- fixtures -----------------------------------------------------------------------------

	private static <T> MutationResult<T> applied(T entity) {
		return new MutationResult<>(entity, null);
	}

	private static <T> MutationResult<T> queued(T entity) {
		return new MutationResult<>(entity, BATCH_SEQ);
	}

	private static <T> MutationResult<T> proposed(T entity) {
		return new MutationResult<>(entity, null, new ProposalContext(
				"prop-1", "a change awaiting a human", Instant.parse("2026-01-01T00:00:00Z")));
	}

	private static AddToViewResultDto addToViewDto(int autoConnections, Integer skipped) {
		ViewObjectDto vo = new ViewObjectDto("vo-1", "e-1", "Actor", "BusinessActor",
				10, 20, 120, 55);
		List<ViewConnectionDto> conns = new ArrayList<>();
		for (int i = 0; i < autoConnections; i++) {
			conns.add(new ViewConnectionDto(
					"vc-" + i, "rel-" + i, "Serving", "vo-1", "vo-" + (i + 2), null));
		}
		return new AddToViewResultDto(vo, conns, skipped, List.of());
	}

	/** The wire shape the handler validates: four integer fields, none optional. */
	private static Map<String, Object> bendpoint() {
		return map("startX", 10, "startY", 20, "endX", 30, "endY", 40);
	}

	private static ViewConnectionDto connectionDto() {
		return new ViewConnectionDto("vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);
	}

	private static OptimizeGroupOrderResultDto optimizeDto(
			int before, int after, double percent, int groupsOptimized) {
		return new OptimizeGroupOrderResultDto("v-1", before, after, percent, groupsOptimized, 4,
				List.of(new OptimizeGroupOrderResultDto.GroupDetail(
						"g-1", "Group 1", 3, true, "row", "detected")));
	}

	private static AdjustViewSpacingResultDto spacingDto(int coincidentSegments) {
		return new AdjustViewSpacingResultDto("v-1", 3, 9, 5, 0, 12, 8, "good", null,
				coincidentSegments, 0, 80.0, List.of(), 40, null);
	}

	private static Map<String, Object> specializationDto(String name, boolean created) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("name", name);
		dto.put("conceptType", "Node");
		dto.put("created", created);
		return dto;
	}

	private static Map<String, Object> deletionDto(int clearedFromConcepts) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("name", "Cloud Server");
		dto.put("conceptType", "Node");
		dto.put("deleted", true);
		dto.put("clearedFromConcepts", clearedFromConcepts);
		return dto;
	}

	/** {@code Map.of} refuses a null value and fixes no order; these fixtures need both. */
	private static Map<String, Object> map(Object... keysAndValues) {
		Map<String, Object> args = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			args.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return args;
	}

	// ---- reading the source ---------------------------------------------------------------------

	private static final String VIEW_PLACEMENT_HANDLER =
			"net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/handlers/ViewPlacementHandler.java";

	/** The brace-matched body of a single method declaration, which must be unique in the file. */
	private static String methodBody(String methodName) {
		List<String> lines = List.of(readRepoFile(VIEW_PLACEMENT_HANDLER).split("\n", -1));
		List<String> bodies = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int at = line.indexOf(methodName + "(");
			if (at < 0) {
				continue;
			}
			String before = line.substring(0, at);
			if (!before.contains("private ") && !before.contains("public ")
					&& !before.contains("protected ")) {
				continue;                       // a call site, not the declaration
			}
			StringBuilder body = new StringBuilder();
			int depth = 0;
			boolean opened = false;
			for (int j = i; j < lines.size(); j++) {
				body.append(lines.get(j)).append('\n');
				for (char c : lines.get(j).toCharArray()) {
					if (c == '{') {
						depth++;
						opened = true;
					} else if (c == '}') {
						depth--;
					}
				}
				if (opened && depth <= 0) {
					break;
				}
			}
			bodies.add(body.toString());
		}
		assertEquals("expected exactly one declaration of " + methodName + " — a parse that finds "
				+ "none reads exactly like a body with nothing in it", 1, bodies.size());
		return bodies.get(0);
	}

	/** Comments are prose: a javadoc naming a read must not satisfy or trip a source probe. */
	private static String stripComments(String java) {
		return java.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	/** Walks up from the working directory until the repo-relative path resolves. */
	private static String readRepoFile(String relativePath) {
		java.nio.file.Path here = java.nio.file.Path.of("").toAbsolutePath();
		for (java.nio.file.Path dir = here; dir != null; dir = dir.getParent()) {
			java.nio.file.Path candidate = dir.resolve(relativePath);
			if (java.nio.file.Files.exists(candidate)) {
				try {
					return java.nio.file.Files.readString(candidate);
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException("Failed to read " + candidate, e);
				}
			}
		}
		throw new IllegalStateException("Could not locate " + relativePath + " from " + here);
	}

	// ---- driving the handlers ------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private List<String> nextSteps(String toolName, Map<String, Object> args) throws Exception {
		McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
				.name(toolName)
				.arguments(args)
				.build();

		McpSchema.CallToolResult result = switch (toolName) {
			case "add-to-view" -> viewHandler.handleAddToView(null, request);
			case "update-view-connection" -> viewHandler.handleUpdateViewConnection(null, request);
			case "remove-from-view" -> viewHandler.handleRemoveFromView(null, request);
			case "clear-view" -> viewHandler.handleClearView(null, request);
			case "optimize-group-order" -> viewHandler.handleOptimizeGroupOrder(null, request);
			case "adjust-view-spacing" -> viewHandler.handleAdjustViewSpacing(null, request);
			case "create-specialization" ->
					specializationHandler.handleCreateSpecialization(null, request);
			case "update-specialization" ->
					specializationHandler.handleUpdateSpecialization(null, request);
			case "delete-specialization" ->
					specializationHandler.handleDeleteSpecialization(null, request);
			default -> throw new IllegalArgumentException("unknown tool: " + toolName);
		};

		assertFalse("the tool must succeed, or its nextSteps prove nothing: "
				+ ((McpSchema.TextContent) result.content().get(0)).text(), result.isError());

		String content = ((McpSchema.TextContent) result.content().get(0)).text();
		Map<String, Object> envelope = objectMapper.readValue(content, new TypeReference<>() {});
		List<String> steps = (List<String>) envelope.get("nextSteps");
		assertTrue("every envelope carries a nextSteps array", steps != null);
		return steps;
	}

	/** An accessor whose every in-scope method returns exactly the result the test hands it. */
	private static final class ArmStubAccessor extends BaseTestAccessor {

		MutationResult<AddToViewResultDto> addToView;
		MutationResult<ViewConnectionDto> updateViewConnection;
		MutationResult<RemoveFromViewResultDto> removeFromView;
		MutationResult<ClearViewResultDto> clearView;
		MutationResult<OptimizeGroupOrderResultDto> optimizeGroupOrder;
		MutationResult<AdjustViewSpacingResultDto> adjustViewSpacing;
		MutationResult<Map<String, Object>> createSpecialization;
		MutationResult<Map<String, Object>> updateSpecialization;
		MutationResult<Map<String, Object>> deleteSpecialization;

		ArmStubAccessor() {
			super(true);
		}

		@Override
		public MutationResult<AddToViewResultDto> addToView(String sessionId, String viewId,
				String elementId, Integer x, Integer y, Integer width, Integer height,
				boolean autoConnect, String parentViewObjectId, StylingParams styling,
				ImageParams imageParams) {
			return require(addToView, "addToView");
		}

		@Override
		public MutationResult<ViewConnectionDto> updateViewConnection(String sessionId,
				String viewConnectionId, List<BendpointDto> bendpoints,
				List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling,
				Boolean showLabel, Integer textPosition) {
			return require(updateViewConnection, "updateViewConnection");
		}

		@Override
		public MutationResult<RemoveFromViewResultDto> removeFromView(String sessionId,
				String viewId, String viewObjectId) {
			return require(removeFromView, "removeFromView");
		}

		@Override
		public MutationResult<ClearViewResultDto> clearView(String sessionId, String viewId) {
			return require(clearView, "clearView");
		}

		@Override
		public MutationResult<OptimizeGroupOrderResultDto> optimizeGroupOrder(
				String sessionId, String viewId, String arrangement,
				Integer spacing, Integer padding, Integer elementWidth,
				Integer elementHeight, boolean autoWidth, Integer columns,
				Map<String, String> groupArrangements) {
			return require(optimizeGroupOrder, "optimizeGroupOrder");
		}

		@Override
		public MutationResult<AdjustViewSpacingResultDto> adjustViewSpacing(
				String sessionId, String viewId, Integer interElementDelta,
				Integer paddingDelta, Integer interGroupDelta, boolean recursive) {
			return require(adjustViewSpacing, "adjustViewSpacing");
		}

		@Override
		public MutationResult<Map<String, Object>> createSpecialization(String sessionId,
				String name, String conceptType, String imagePath) {
			return require(createSpecialization, "createSpecialization");
		}

		@Override
		public MutationResult<Map<String, Object>> updateSpecialization(String sessionId,
				String name, String conceptType, String newName,
				String imagePath, boolean clearImagePath) {
			return require(updateSpecialization, "updateSpecialization");
		}

		@Override
		public MutationResult<Map<String, Object>> deleteSpecialization(String sessionId,
				String name, String conceptType, boolean force) {
			return require(deleteSpecialization, "deleteSpecialization");
		}

		/** A test that forgot to seed an arm must fail loudly, never fall through to a default. */
		private static <T> MutationResult<T> require(MutationResult<T> seeded, String method) {
			if (seeded == null) {
				throw new IllegalStateException(
						"the test must seed a MutationResult for " + method + " before calling it");
			}
			return seeded;
		}
	}
}
