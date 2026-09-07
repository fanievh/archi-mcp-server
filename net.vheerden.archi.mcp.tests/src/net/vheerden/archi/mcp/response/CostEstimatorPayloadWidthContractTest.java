package net.vheerden.archi.mcp.response;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.CostEstimator.ItemType;
import net.vheerden.archi.mcp.response.PaginationCursor;
import net.vheerden.archi.mcp.response.FieldSelector.FieldPreset;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AnchorPointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.DiagramImageDto;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;

/**
 * Cross-source guard over {@link CostEstimator}'s per-item character estimates.
 *
 * <p>The estimator's constants model a payload whose definition lives in another file, with
 * nothing connecting the two. This test supplies the connection. The <em>measured</em> side
 * comes from running a representative DTO through the real
 * {@link FieldSelector#applyFieldSelection} and the same Jackson configuration
 * {@link ResponseFormatter} uses; the <em>asserted</em> side comes from the estimator's own
 * public API. Neither reads the other's numbers, so a constant that drifts away from what the
 * serializer emits fails here rather than reaching a caller as a wrong budget.</p>
 *
 * <p>The tolerance is the estimator's published contract: an estimate is approximate but
 * directionally accurate to within 20% of actual.</p>
 *
 * <p><strong>Fixtures are census-calibrated</strong> against the captured reference models
 * (2365 relationships, 1959 elements, 88 views): 35-character ids, a 26-character element
 * name, a 37-character view name, a 21-character relationship type, and a 117-character
 * documentation string. Relationship and view documentation and properties are populated in
 * the fixture, which changes nothing at the minimal and standard presets -- those presets do
 * not carry those fields -- and anchors the full preset on a caller who is asking for exactly
 * the fields the full preset adds.</p>
 */
public class CostEstimatorPayloadWidthContractTest {

	/** The estimator's published accuracy contract. */
	private static final double TOLERANCE = 0.20;

	/** Large enough that the envelope constant and integer rounding are not the signal. */
	private static final int SAMPLE_SIZE = 1000;

	private static final String ID = "id-0123456789abcdef0123456789abcdef";

	/** The shape Archi mints for an image in the model archive: a prefix, an EMF id, a suffix. */
	private static final String IMAGE_PATH = "images/_BRJagKIjEfGmoYmHSR0RJQ.png";

	/** A realistic model version: the accessor returns a small monotonic counter as a string. */
	private static final String MODEL_VERSION = "3";

	/**
	 * A realistic tool duration. Two digits: the read tools this estimate covers return in
	 * single- or double-digit milliseconds, and the key's width is the only thing that matters
	 * here -- a three-digit duration would add one char to a 280-char envelope.
	 */
	private static final long TYPICAL_DURATION_MS = 12L;

	/**
	 * The tool and result branch the envelope width is anchored on: {@code search-relationships},
	 * complete page, JSON format. Named so the per-item checks below all price the same envelope.
	 */
	private static final List<String> ANCHOR_NEXT_STEPS = List.of(
			"Use get-element to get full details of source/target elements",
			"Use get-relationships with elementId for traversal and depth expansion");

	/**
	 * One success-path {@code nextSteps} list, with the handler and source form it came from.
	 *
	 * <p><strong>These lists are an assertion about the handlers, reversing what this class used to
	 * say.</strong> It used to record that they are fixture input only, on the ground that "a
	 * handler that rewords its own suggestions is a re-measurement, not a regression". The
	 * distinction is real and the conclusion drawn from it was wrong: a re-measurement is precisely
	 * the event nobody was being told about. These lists ARE the envelope width -- the suggestions
	 * are most of what a zero-result envelope contains -- so a reworded suggestion silently
	 * invalidates the measurement behind the envelope constant and behind both declared exclusions,
	 * and leaves this class asserting a width against a payload that no longer exists. Calling that
	 * "not a regression" made it invisible rather than benign.</p>
	 *
	 * @param handler        the source file the literals live in, so drift is caught at the source
	 * @param sourceLiterals the literals as the handler writes them, which differ from the rendered
	 *                       text only where a suggestion interpolates a value
	 */
	private record EnvelopeCase(String tool, String branch, String handler, List<String> nextSteps,
			List<String> sourceLiterals) {

		static EnvelopeCase of(String tool, String branch, String handler, String... nextSteps) {
			List<String> steps = List.of(nextSteps);
			return new EnvelopeCase(tool, branch, handler, steps, steps);
		}

		/** A suggestion built around an interpolated value: what is rendered is not what is written. */
		static EnvelopeCase interpolated(String tool, String branch, String handler, String rendered,
				String... sourceLiterals) {
			return new EnvelopeCase(tool, branch, handler, List.of(rendered),
					List.of(sourceLiterals));
		}

		String label() {
			return tool + "/" + branch;
		}

		/** A paged response reports more results than it returned, and says it was truncated. */
		boolean hasMore() {
			return "hasMore".equals(branch);
		}
	}

	/**
	 * Every success-path branch of the six dry-run call sites, in JSON format. Six call sites but
	 * five tool names: {@code get-relationships} publishes an estimate from both its depth path and
	 * its traverse path, and they build different suggestion lists, so they are measured
	 * separately.
	 * The {@code graph} format is deliberately absent: it goes through
	 * {@link ResponseFormatter#formatGraph}, a different envelope with different keys, which the
	 * estimator does not model at all.
	 *
	 * <p>Transcribed from {@code SearchHandler.handleSearchElements} and
	 * {@code handleSearchRelationships}, {@code ViewHandler.handleGetViews} and
	 * {@code handleGetViewContents}, and {@code TraversalHandler}'s depth and traverse paths.</p>
	 */
	private static final String SEARCH = "SearchHandler.java";
	private static final String VIEW = "ViewHandler.java";
	private static final String TRAVERSAL = "TraversalHandler.java";

	/** A view name at the census width, for the one suggestion that interpolates one. */
	private static final String FILTERED_NAME = chars(37);

	private static final List<EnvelopeCase> ENVELOPE_CASES = List.of(
			EnvelopeCase.of("search-elements", "empty", SEARCH,
					"Try broader search terms or partial words",
					"Use get-model-info to see available element types and counts"),
			EnvelopeCase.of("search-elements", "hasMore", SEARCH,
					"Use cursor parameter to retrieve next page",
					"Use get-element with a specific ID for full element details",
					"Use get-relationships to explore connections from a found element"),
			EnvelopeCase.of("search-elements", "complete", SEARCH,
					"Use get-element with a specific ID for full element details",
					"Use get-relationships to explore connections from a found element"),
			EnvelopeCase.of("search-relationships", "empty", SEARCH,
					"Try broader search terms or partial words",
					"Use search-elements to find elements, then get-relationships for their connections"),
			EnvelopeCase.of("search-relationships", "hasMore", SEARCH,
					"Use cursor parameter to retrieve next page",
					"Use get-element to get full details of source/target elements",
					"Use get-relationships with elementId for traversal and depth expansion"),
			EnvelopeCase.of("search-relationships", "complete", SEARCH,
					ANCHOR_NEXT_STEPS.get(0), ANCHOR_NEXT_STEPS.get(1)),
			EnvelopeCase.of("get-views", "hasMore", VIEW,
					"Use cursor parameter to retrieve next page",
					"Use get-view-contents with a view ID to explore a specific diagram",
					"Add a viewpoint filter to narrow results"),
			EnvelopeCase.of("get-views", "complete", VIEW,
					"Use get-view-contents with a view ID to explore a specific diagram"),
			EnvelopeCase.of("get-views", "empty", VIEW,
					"Use get-model-info to verify the model is loaded correctly"),
			// The other arm of the same two-armed builder, and a far wider one. Its exclusion sibling
			// above was measured on this branch alone for as long as this class has existed.
			EnvelopeCase.interpolated("get-views", "emptyNameFiltered", VIEW,
					"No views matching '" + FILTERED_NAME + "'. Try a broader name, "
							+ "remove the name filter, or use search-elements to find elements by name.",
					"No views matching '",
					"'. Try a broader name, ",
					"remove the name filter, or use search-elements to find elements by name."),
			EnvelopeCase.of("get-view-contents", "complete", VIEW,
					"Use get-element with an element ID for detailed information",
					"Use get-relationships with an element ID to explore connections beyond this view"),
			EnvelopeCase.of("get-relationships", "empty", TRAVERSAL,
					"Use search-elements to find related elements by name",
					"Use get-model-info to see model structure overview"),
			EnvelopeCase.of("get-relationships", "complete", TRAVERSAL,
					"Use get-element for full details on a related element",
					"Use search-elements to discover more elements"),
			EnvelopeCase.of("traverse", "empty", TRAVERSAL,
					"Use search-elements to find related elements by name",
					"Use get-model-info to see model structure overview"),
			EnvelopeCase.of("traverse", "complete", TRAVERSAL,
					"Use get-element for full details on a discovered element",
					"Use get-relationships with depth 2 for detailed relationship view",
					"Use search-elements to find related elements by name"));

	/**
	 * The measured combinations a single envelope constant cannot cover -- both of them
	 * {@code get-views}, which emits the shortest suggestion lists of any tool here.
	 *
	 * <p>No constant can cover them: being inside the contract for the narrower one's 205 chars
	 * requires at most 248, and being inside it for the widest branch's 326 requires at least 261.
	 * Excluding only the narrower of the two does not help either -- the second still caps a
	 * covering constant at 256. The shipped width holds the contract on the other twelve and
	 * over-reports these, which is the safe direction for a budget estimate. Both exclusions are
	 * asserted below, so neither can go stale.</p>
	 *
	 * <p><strong>{@code get-views/empty} is one arm of a two-armed builder, and the exclusion is
	 * scoped to that arm deliberately.</strong> {@code buildEmptyViewsNextSteps} emits the short
	 * suggestion measured here only when no name filter was given; filtered, it emits a suggestion
	 * naming the filter that did not match, which is about 85 chars wider and lands that branch
	 * INSIDE the contract. So this exclusion -- and the control below asserting the branch is still
	 * outside the contract and still over-reporting -- were both false on the other arm for as long
	 * as they have existed, and were true of the arm they were measured on. The other arm is now a
	 * case of its own, covered by the band check like any other, rather than an unstated
	 * generalisation of this one.</p>
	 */
	private static final List<String> DECLARED_OVER_REPORTS =
			List.of("get-views/complete", "get-views/empty");

	private static String chars(int len) {
		return "x".repeat(len);
	}

	private static ObjectMapper productionMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
		return mapper;
	}

	// ---- census-calibrated fixtures ----

	private static ElementDto elementFixture() {
		// 100% of census elements carry documentation; 21% also carry properties, which is
		// the tail the shipped standard width does not model.
		return new ElementDto(ID, chars(26), chars(14), null, chars(10), chars(117), null);
	}

	private static ViewDto viewFixture() {
		Map<String, String> properties = new LinkedHashMap<>();
		properties.put(chars(9), chars(10));
		properties.put(chars(9) + "2", chars(10));
		return new ViewDto(ID, chars(37), chars(18), "manual", "Views", chars(117), properties);
	}

	private static RelationshipDto relationshipFixture() {
		List<Map<String, String>> properties = new ArrayList<>();
		Map<String, String> property = new LinkedHashMap<>();
		property.put("key", chars(9));
		property.put("value", chars(10));
		properties.add(property);
		return new RelationshipDto(ID, "", chars(21), null, ID, ID, false, chars(79), properties,
				chars(26), chars(26), null, null, null);
	}

	// ---- the measured side: the real field selector, formatter and serializer ----

	/**
	 * The measured side serializes a whole {@link ResponseFormatter#formatSuccess} envelope around
	 * the filtered rows, so the envelope width comes from the formatter rather than from the
	 * estimator's own constant.
	 *
	 * <p>This matters more than it looks. An earlier version of this method added
	 * {@code CostEstimator.ENVELOPE_OVERHEAD_CHARS} to the measured side, which put the same term
	 * on both sides of every comparison below: an envelope constant that did not match what the
	 * formatter emits cancelled out and could not be seen here at any sample size.</p>
	 */
	private static int measuredTokens(Object fixture, FieldPreset preset) throws Exception {
		return measuredTokens(fixture, preset, SAMPLE_SIZE, ANCHOR_NEXT_STEPS);
	}

	private static int measuredTokens(Object fixture, FieldPreset preset, int count,
			List<String> nextSteps) throws Exception {
		return measuredEnvelopeTokens(
				FieldSelector.applyFieldSelection(repeat(fixture, count), preset, null), count, nextSteps);
	}

	/**
	 * Prices rows that are already in the shape the client receives. The traversal surfaces do not
	 * go through {@link FieldSelector#applyFieldSelection} at all -- the handler and the engine
	 * assemble their maps themselves -- so running them through it would measure a payload no
	 * caller is ever sent.
	 */
	private static int measuredEnvelopeTokens(Object rows, int count, List<String> nextSteps)
			throws Exception {
		return measuredEnvelopeTokens(rows, count, nextSteps, false);
	}

	/**
	 * @param hasMore build the envelope the paged branch really produces -- a total larger than the
	 *                count returned, and the truncation flag set. The base64 cursor a paged
	 *                response also carries is appended to {@code _meta} after the formatter
	 *                returns and is deliberately outside what this constant models; see
	 *                {@link CostEstimator#ENVELOPE_OVERHEAD_CHARS}.
	 */
	private static int measuredEnvelopeTokens(Object rows, int count, List<String> nextSteps,
			boolean hasMore) throws Exception {
		String json = productionMapper()
				.writeValueAsString(successEnvelope(rows, count, nextSteps, hasMore));
		return (json.length() + CostEstimator.CHARS_PER_TOKEN - 1) / CostEstimator.CHARS_PER_TOKEN;
	}

	/**
	 * A success envelope as the client receives it, timing key included.
	 *
	 * <p>The formatter does not put {@code durationMs} there. The command registry does, wrapping
	 * every tool it registers and adding the key to {@code _meta} after the handler has returned --
	 * so it is on every response of every tool, on the error path as well as the success one, and
	 * no handler can opt out. Measuring the formatter's output alone therefore measured a response
	 * no caller receives, and left the estimate short by the same 16 chars everywhere.</p>
	 */
	private static Map<String, Object> successEnvelope(Object rows, int count,
			List<String> nextSteps, boolean hasMore) {
		int total = hasMore ? count * 10 + 7 : count;
		Map<String, Object> envelope = new ResponseFormatter()
				.formatSuccess(rows, nextSteps, MODEL_VERSION, count, total, hasMore);
		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
		meta.put("durationMs", TYPICAL_DURATION_MS);
		return envelope;
	}

	private static List<Object> repeat(Object row, int count) {
		List<Object> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			rows.add(row);
		}
		return rows;
	}

	private void assertWithinContract(Object fixture, FieldPreset preset, ItemType itemType)
			throws Exception {
		int measured = measuredTokens(fixture, preset);
		int estimated = CostEstimator.estimateTokens(SAMPLE_SIZE, preset, itemType);
		double error = Math.abs(estimated - measured) / (double) measured;
		assertTrue(String.format(
				"%s at %s: estimator says %d tokens, the serializer emits %d (%.0f%% off, contract is %.0f%%)",
				itemType, preset, estimated, measured, error * 100, TOLERANCE * 100),
				error <= TOLERANCE);
	}

	// ---- relationships ----

	@Test
	public void shouldEstimateRelationshipWidthWithinContract_atEveryPreset() throws Exception {
		for (FieldPreset preset : FieldPreset.values()) {
			assertWithinContract(relationshipFixture(), preset, ItemType.RELATIONSHIP);
		}
	}

	// ---- views ----

	@Test
	public void shouldEstimateViewWidthWithinContract_atEveryPreset() throws Exception {
		for (FieldPreset preset : FieldPreset.values()) {
			assertWithinContract(viewFixture(), preset, ItemType.VIEW);
		}
	}

	// ---- elements ----

	@Test
	public void shouldEstimateElementWidthWithinContract_atEveryPreset() throws Exception {
		// Standard is the control that validates the method: its shipped width was set
		// independently of this test and must land inside the contract. Minimal was a declared
		// exclusion until its width was measured for the first time and corrected.
		for (FieldPreset preset : FieldPreset.values()) {
			assertWithinContract(elementFixture(), preset, ItemType.ELEMENT);
		}
	}

	// ---- the payload the traverse mode emits ----

	/**
	 * One hop relationship, transcribed from {@code TraversalEngine.buildHopRelationship}. The
	 * engine hand-builds this map and never consults the preset, so its field set is fixed at
	 * whatever the traversal emits -- which happens to be exactly the relationship standard set.
	 * The three semantic attributes are absent here because they are null on the census fixture
	 * and the serializer omits nulls, the same as for any relationship that is not their subtype.
	 */
	private static Map<String, Object> hopRelationshipFixture() {
		RelationshipDto rel = relationshipFixture();
		Map<String, Object> hopRel = new LinkedHashMap<>();
		hopRel.put("id", rel.id());
		hopRel.put("name", rel.name());
		hopRel.put("type", rel.type());
		hopRel.put("sourceId", rel.sourceId());
		hopRel.put("targetId", rel.targetId());
		return hopRel;
	}

	/**
	 * The whole traverse result, transcribed from {@code TraversalEngine.traverse}. Note that the
	 * discovered elements are not a list of their own: each one is nested inside the hop
	 * relationship that reached it, so a traversal returns as many summaries as hops.
	 *
	 * @param elementCount summaries to nest; pass 0 to price the relationship half alone
	 */
	private static Map<String, Object> traversalResultFixture(int relationshipCount, int elementCount,
			FieldPreset preset) {
		List<Object> hopRelationships = new ArrayList<>();
		for (int i = 0; i < relationshipCount; i++) {
			Map<String, Object> hopRel = hopRelationshipFixture();
			if (i < elementCount) {
				hopRel.put("connectedElement", elementSummaryFixture(preset));
			}
			hopRelationships.add(hopRel);
		}
		Map<String, Object> hop = new LinkedHashMap<>();
		hop.put("hopLevel", 1);
		hop.put("relationships", hopRelationships);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("startElement", elementSummaryFixture(preset));
		result.put("hops", List.of(hop));
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("totalElementsDiscovered", elementCount);
		summary.put("totalRelationships", relationshipCount);
		summary.put("maxDepthReached", 1);
		summary.put("cyclesDetected", false);
		result.put("traversalSummary", summary);
		return result;
	}

	private static List<String> nextStepsFor(String label) {
		return ENVELOPE_CASES.stream().filter(c -> c.label().equals(label)).findFirst()
				.orElseThrow(() -> new AssertionError("no measured envelope for " + label))
				.nextSteps();
	}

	/**
	 * A traversal returns one summary per hop, so this prices both halves together at the shape a
	 * real traversal produces rather than each in isolation.
	 */
	@Test
	public void shouldEstimateTraversalWidthWithinContract_atEveryPreset() throws Exception {
		for (FieldPreset preset : FieldPreset.values()) {
			int measured = measuredEnvelopeTokens(
					traversalResultFixture(SAMPLE_SIZE, SAMPLE_SIZE, preset), SAMPLE_SIZE,
					nextStepsFor("traverse/complete"));
			int estimated = CostEstimator.estimateTokensForTraversal(SAMPLE_SIZE, SAMPLE_SIZE, preset);
			double error = Math.abs(estimated - measured) / (double) measured;
			assertTrue(String.format(
					"traverse at %s: estimator says %d tokens, the serializer emits %d (%.0f%% off, contract is %.0f%%)",
					preset, estimated, measured, error * 100, TOLERANCE * 100),
					error <= TOLERANCE);
		}
	}

	/**
	 * The relationship half alone. The engine builds these rows itself and never reads the
	 * preset, so an estimate that charges a different width per preset is charging for fields the
	 * caller will not receive at either end of the range -- and it is the caller at minimal who is
	 * hurt, being told a traversal is cheap when its relationships cost the standard width.
	 */
	/**
	 * The premise the traverse relationship width rests on, checked against the real field
	 * selector rather than asserted: whatever preset the caller asked for, the engine emits the
	 * key set the STANDARD relationship preset selects. Asserting the hop map does not vary is
	 * not enough on its own -- it takes no preset, so it could not vary, and a test that noticed
	 * only that would certify nothing.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void shouldEmitTheStandardRelationshipFieldSet_forEveryHopRelationship() {
		Map<String, Object> standardRow = (Map<String, Object>) FieldSelector
				.applyFieldSelection(relationshipFixture(), FieldPreset.STANDARD, null);
		assertEquals("the hop relationship's field set is what fixes its width at every preset",
				standardRow.keySet(), hopRelationshipFixture().keySet());
	}

	@Test
	public void shouldNotMoveTheTraversalRelationshipHalfWithThePreset() throws Exception {
		int estimatedStandard = CostEstimator.estimateTokensForTraversal(0, SAMPLE_SIZE,
				FieldPreset.STANDARD);
		for (FieldPreset preset : FieldPreset.values()) {
			assertEquals(
					"the hop relationship carries no preset-dependent field, so its estimate must not move at "
							+ preset,
					estimatedStandard, CostEstimator.estimateTokensForTraversal(0, SAMPLE_SIZE, preset));
			int measured = measuredEnvelopeTokens(traversalResultFixture(SAMPLE_SIZE, 0, preset),
					SAMPLE_SIZE, nextStepsFor("traverse/complete"));
			int estimated = CostEstimator.estimateTokensForTraversal(0, SAMPLE_SIZE, preset);
			double error = Math.abs(estimated - measured) / (double) measured;
			assertTrue(String.format(
					"traverse relationship half at %s: estimator says %d tokens, the serializer emits %d (%.0f%% off)",
					preset, estimated, measured, error * 100),
					error <= TOLERANCE);
		}
	}

	// ---- the expanded rows the depth mode emits ----

	/**
	 * One expanded relationship row, transcribed from {@code TraversalHandler.buildExpandedResult}
	 * and {@code buildElementResponse}. The handler assembles this map itself rather than
	 * serializing a DTO, so the shape has to be reproduced here; the element halves still go
	 * through the real {@link FieldSelector}, which is where the preset actually bites.
	 *
	 * @param nestedCount relationships attached to each embedded element at depth 3, which the
	 *                    handler reads from the model and this class varies deliberately
	 */
	private static Map<String, Object> expandedRelationshipFixture(int depth, FieldPreset preset,
			int nestedCount) {
		RelationshipDto rel = relationshipFixture();
		Map<String, Object> expanded = new LinkedHashMap<>();
		expanded.put("id", rel.id());
		expanded.put("name", rel.name());
		expanded.put("type", rel.type());
		expanded.put("source", embeddedElement(depth, preset, nestedCount));
		expanded.put("target", embeddedElement(depth, preset, nestedCount));
		return expanded;
	}

	@SuppressWarnings("unchecked")
	private static Object embeddedElement(int depth, FieldPreset preset, int nestedCount) {
		if (depth == 1) {
			return elementSummaryFixture(preset);
		}
		Map<String, Object> full = (Map<String, Object>) FieldSelector
				.applyFieldSelection(elementFixture(), preset, null);
		if (depth == 3) {
			List<RelationshipDto> nested = new ArrayList<>();
			for (int i = 0; i < nestedCount; i++) {
				nested.add(relationshipFixture());
			}
			full.put("relationships", FieldSelector.applyToNestedRelationships(nested, null));
		}
		return full;
	}

	/**
	 * One embedded element summary, transcribed from {@code TraversalEngine.buildSummary}, which
	 * is byte-identical to {@code TraversalHandler.buildElementResponse}'s depth-1 branch: the
	 * same two field sets, through the same {@link FieldSelector#filterMap}.
	 */
	private static Map<String, Object> elementSummaryFixture(FieldPreset preset) {
		Map<String, Object> summary = FieldSelector.elementDtoToMap(elementFixture());
		Set<String> fields = (preset == FieldPreset.MINIMAL)
				? Set.of("id", "name")
				: Set.of("id", "name", "type");
		return FieldSelector.filterMap(summary, fields, null);
	}

	private void assertDepthWithinContract(int depth, FieldPreset preset, int nestedCount)
			throws Exception {
		int measured = measuredEnvelopeTokens(
				repeat(expandedRelationshipFixture(depth, preset, nestedCount), SAMPLE_SIZE),
				SAMPLE_SIZE, ANCHOR_NEXT_STEPS);
		int estimated = CostEstimator.estimateTokensForDepth(SAMPLE_SIZE, depth, preset);
		double error = Math.abs(estimated - measured) / (double) measured;
		assertTrue(String.format(
				"depth %d at %s (%d nested per element): estimator says %d tokens, the serializer emits %d (%.0f%% off, contract is %.0f%%)",
				depth, preset, nestedCount, estimated, measured, error * 100, TOLERANCE * 100),
				error <= TOLERANCE);
	}

	/**
	 * The relationship header on its own. The per-depth checks below cannot see this constant move:
	 * it is at most 40% of a depth-1 row and 7% of a depth-3 one, so halving it stays inside the
	 * accuracy contract at every depth and every preset, and every band check stays green over it.
	 *
	 * <p>Isolating it needs subtraction rather than a wider tolerance. An expanded row is the
	 * header plus exactly two embedded elements, so measuring a whole row and taking two measured
	 * summaries off it leaves the header -- both terms from the real serializer, neither from the
	 * estimator. Asserting it at both presets also pins that the header does NOT move with the
	 * preset, which is the property that lets the composition put it outside the per-element
	 * term.</p>
	 */
	@Test
	public void shouldEstimateTheExpandedRelationshipHeaderWithinContract_atEveryPreset()
			throws Exception {
		for (FieldPreset preset : FieldPreset.values()) {
			int wholeRow = measuredEnvelopeTokens(
					repeat(expandedRelationshipFixture(1, preset, 0), SAMPLE_SIZE), SAMPLE_SIZE,
					ANCHOR_NEXT_STEPS);
			int summaries = measuredEnvelopeTokens(
					repeat(elementSummaryFixture(preset), SAMPLE_SIZE), SAMPLE_SIZE, ANCHOR_NEXT_STEPS);
			int envelope = measuredEnvelopeTokens(List.of(), 0, ANCHOR_NEXT_STEPS);
			// Both measured sides carry the envelope once; subtracting two summary payloads means
			// adding it back once, so it is removed twice and restored once.
			int measuredHeader = wholeRow - (2 * (summaries - envelope)) - envelope;
			int estimatedHeader = (SAMPLE_SIZE * CostEstimator.EXPANDED_RELATIONSHIP_HEADER_CHARS)
					/ CostEstimator.CHARS_PER_TOKEN;
			double error = Math.abs(estimatedHeader - measuredHeader) / (double) measuredHeader;
			assertTrue(String.format(
					"expanded relationship header at %s: estimator charges %d tokens per %d rows, the "
							+ "serializer emits %d once the two embedded elements are taken off (%.0f%% off, contract is %.0f%%)",
					preset, estimatedHeader, SAMPLE_SIZE, measuredHeader, error * 100, TOLERANCE * 100),
					error <= TOLERANCE);
		}
	}

	@Test
	public void shouldEstimateDepthOneWidthWithinContract_atEveryPreset() throws Exception {
		for (FieldPreset preset : FieldPreset.values()) {
			assertDepthWithinContract(1, preset, 0);
		}
	}

	@Test
	public void shouldEstimateDepthTwoWidthWithinContract_atEveryPreset() throws Exception {
		for (FieldPreset preset : FieldPreset.values()) {
			assertDepthWithinContract(2, preset, 0);
		}
	}

	/**
	 * Depth 3 attaches every embedded element's own relationships, so its width turns on how
	 * connected the model is -- a quantity the estimator is never told. The shipped width is
	 * anchored on the census incidence of 2.41 relationships per element, and this checks the
	 * contract holds at both integers that bracket it. It does NOT hold far outside that
	 * bracket, which is why the incidence is disclosed rather than buried.
	 */
	@Test
	public void shouldEstimateDepthThreeWidthWithinContract_acrossTheCensusNestedRange() throws Exception {
		for (FieldPreset preset : FieldPreset.values()) {
			assertDepthWithinContract(3, preset, 2);
			assertDepthWithinContract(3, preset, 3);
		}
	}

	/**
	 * The band checks above cannot see a depth-1 estimate that stops following the preset. The
	 * real gap between a minimal and a standard summary is one {@code type} field -- 18% of the
	 * row -- so an estimate that charged the standard width at both presets would still be inside
	 * the accuracy contract at depth 1, and every check that measures accuracy would stay green
	 * over it.
	 *
	 * <p>What that estimate would get wrong is not the budget, it is the saving: the caller is
	 * told narrowing the preset buys nothing when the serializer says it buys 18%. So this asserts
	 * the direction rather than the magnitude, on both sides -- the payload really is narrower,
	 * and the estimate really does say so.</p>
	 */
	@Test
	public void shouldNarrowEveryDepthAtTheMinimalPreset_becauseTheSerializerDoes() throws Exception {
		for (int depth = 1; depth <= 3; depth++) {
			int measuredMinimal = measuredEnvelopeTokens(
					repeat(expandedRelationshipFixture(depth, FieldPreset.MINIMAL, 2), SAMPLE_SIZE),
					SAMPLE_SIZE, ANCHOR_NEXT_STEPS);
			int measuredStandard = measuredEnvelopeTokens(
					repeat(expandedRelationshipFixture(depth, FieldPreset.STANDARD, 2), SAMPLE_SIZE),
					SAMPLE_SIZE, ANCHOR_NEXT_STEPS);
			assertTrue(String.format(
					"the premise itself: at depth %d the serializer must emit less at minimal (%d) than at standard (%d)",
					depth, measuredMinimal, measuredStandard),
					measuredMinimal < measuredStandard);
			assertTrue(String.format(
					"depth %d: the serializer emits %d tokens at minimal against %d at standard, so the "
							+ "estimator must not charge %d at both",
					depth, measuredMinimal, measuredStandard,
					CostEstimator.estimateTokensForDepth(SAMPLE_SIZE, depth, FieldPreset.MINIMAL)),
					CostEstimator.estimateTokensForDepth(SAMPLE_SIZE, depth, FieldPreset.MINIMAL)
							< CostEstimator.estimateTokensForDepth(SAMPLE_SIZE, depth, FieldPreset.STANDARD));
		}
	}

	@Test
	public void shouldEstimateDepthZeroWidthWithinContract_atEveryPreset() throws Exception {
		for (FieldPreset preset : FieldPreset.values()) {
			int measured = measuredTokens(relationshipFixture(), preset);
			int estimated = CostEstimator.estimateTokensForDepth(SAMPLE_SIZE, 0, preset);
			double error = Math.abs(estimated - measured) / (double) measured;
			assertTrue(String.format("depth 0 at %s: estimator %d, serializer %d (%.0f%% off)",
					preset, estimated, measured, error * 100), error <= TOLERANCE);
		}
	}

	// ---- the envelope, at the one count where it IS the whole estimate ----

	/**
	 * At zero results the estimate is the envelope and nothing else, so this is the only check
	 * in the class where an envelope error cannot be diluted by the per-item widths. At the
	 * sample size the other tests use, 280 chars inside a quarter-million is 0.1% -- an envelope
	 * constant could be wrong by a factor of two and every one of them would stay green.
	 */
	@Test
	public void shouldEstimateEnvelopeWithinContract_atZeroResults() throws Exception {
		for (EnvelopeCase c : ENVELOPE_CASES) {
			if (DECLARED_OVER_REPORTS.contains(c.label())) {
				continue;
			}
			int measured = measuredEnvelopeTokens(List.of(), 0, c.nextSteps(), c.hasMore());
			int estimated = CostEstimator.estimateTokens(0, FieldPreset.STANDARD, ItemType.ELEMENT);
			double error = Math.abs(estimated - measured) / (double) measured;
			assertTrue(String.format(
					"%s at zero results: estimator says %d tokens, the serializer emits %d (%.0f%% off, contract is %.0f%%)",
					c.label(), estimated, measured, error * 100, TOLERANCE * 100),
					error <= TOLERANCE);
		}
	}

	// ---- the terms the envelope constant does and does not carry ----

	/**
	 * The timing key's width, so the decision to fold it in cannot go stale.
	 *
	 * <p>It is folded in rather than tracked because it is the only one of the envelope's
	 * unmodelled terms that is unconditional: one injection site in the command registry, on every
	 * response of every tool, success and error alike, with no branch and no parameter to condition
	 * it on. A term like that belongs inside the constant; a term that appears on some responses
	 * does not, because widening a flat constant to cover it corrupts every response that does not
	 * carry it.</p>
	 */
	@Test
	public void shouldCarryTheTimingKeyOnEveryEnvelope() throws Exception {
		for (EnvelopeCase c : ENVELOPE_CASES) {
			int total = c.hasMore() ? 7 : 0;
			Map<String, Object> withoutTiming = new ResponseFormatter()
					.formatSuccess(List.of(), c.nextSteps(), MODEL_VERSION, 0, total, c.hasMore());
			int bare = productionMapper().writeValueAsString(withoutTiming).length();
			int timed = productionMapper()
					.writeValueAsString(successEnvelope(List.of(), 0, c.nextSteps(), c.hasMore()))
					.length();
			assertEquals("the timing key must cost the same on every branch, and it is inside the "
					+ "envelope constant on that basis (" + c.label() + ")", 16, timed - bare);
		}
	}

	/**
	 * The pagination cursor, measured rather than folded in.
	 *
	 * <p>It is comparable in size to the whole envelope constant, which is why it must not be
	 * averaged into it: it appears only on the paged branch of three of the six dry-run surfaces,
	 * and {@code get-view-contents} and {@code get-relationships} are not paged and never carry one
	 * at all. A constant widened to cover it would nearly double the reported cost of every
	 * unpaginated response to be right about a minority of paged ones.</p>
	 *
	 * <p>What this asserts is the decision criterion itself rather than a width: that a constant
	 * widened to carry the cursor would put the unpaginated branches outside the accuracy contract.
	 * That is the reason the term is excluded, so it is the thing worth pinning -- the cursor's own
	 * width varies with the filter parameters it encodes and is not a fixed figure. It is also not,
	 * as this constant's documentation used to claim, wider than the whole envelope: measured on a
	 * four-parameter search cursor it is 228 chars against the envelope's 280, which is large
	 * enough to make the point without the overstatement.</p>
	 */
	@Test
	public void shouldLeaveThePaginationCursorOutsideTheEnvelopeConstant() throws Exception {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("query", chars(12));
		params.put("type", "ApplicationComponent");
		params.put("layer", "Application");
		params.put("specialization", null);
		String cursor = PaginationCursor.encode(MODEL_VERSION, 100, 50, 320, params);

		List<String> nextSteps = nextStepsFor("search-elements/hasMore");
		Map<String, Object> paged = successEnvelope(List.of(), 0, nextSteps, true);
		int withoutCursor = productionMapper().writeValueAsString(paged).length();
		ResponseFormatter.addCursorToken(paged, cursor);
		int withCursor = productionMapper().writeValueAsString(paged).length();
		int cursorChars = withCursor - withoutCursor;

		// The narrowest branch the envelope constant is anchored on. Widening the constant by the
		// cursor has to push this one outside the contract, or excluding the cursor would be the
		// more expensive choice and this exclusion would be unearned.
		int narrowest = measuredEnvelopeTokens(List.of(), 0,
				nextStepsFor("get-relationships/complete"), false);
		int widened = (CostEstimator.ENVELOPE_OVERHEAD_CHARS + cursorChars
				+ CostEstimator.CHARS_PER_TOKEN - 1) / CostEstimator.CHARS_PER_TOKEN;
		double error = Math.abs(widened - narrowest) / (double) narrowest;
		assertTrue("folding the cursor in must put the unpaginated branches outside the contract -- "
				+ "that is the whole reason it is tracked separately. The cursor measured "
				+ cursorChars + " chars; a constant of "
				+ (CostEstimator.ENVELOPE_OVERHEAD_CHARS + cursorChars)
				+ " would be " + Math.round(error * 100) + "% off an unpaged branch",
				error > TOLERANCE);

		Map<String, Object> complete = successEnvelope(List.of(), 0,
				nextStepsFor("search-elements/complete"), false);
		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) complete.get("_meta");
		assertFalse("and it must be absent from an unpaged response, or folding it in would be the "
				+ "cheaper option", meta.containsKey("cursor"));
	}

	/**
	 * The graph envelope, measured rather than assumed.
	 *
	 * <p>The estimator's own documentation used to say a graph response "goes through a different
	 * formatter method with different keys". The method is different; the keys are one key. The
	 * envelope difference is not what makes the graph format unmodelled -- its payload shape and
	 * its own suggestion lists are -- and a reader told the envelope diverges would look for the
	 * divergence in the wrong place.</p>
	 */
	@Test
	public void shouldDifferFromTheGraphEnvelopeByExactlyOneKey() {
		Map<String, Object> success = new ResponseFormatter()
				.formatSuccess(List.of(), ANCHOR_NEXT_STEPS, MODEL_VERSION, 0, 0, false);
		Map<String, Object> graph = new ResponseFormatter()
				.formatGraph(Map.of(), ANCHOR_NEXT_STEPS, MODEL_VERSION, 0, 0, 0, false);
		assertEquals("the top-level key is the payload's name, and that is one of the two "
				+ "differences", Set.of("result", "nextSteps", "_meta"), success.keySet());
		assertEquals(Set.of("graph", "nextSteps", "_meta"), graph.keySet());

		@SuppressWarnings("unchecked")
		Set<String> successMeta = ((Map<String, Object>) success.get("_meta")).keySet();
		@SuppressWarnings("unchecked")
		Set<String> graphMeta = ((Map<String, Object>) graph.get("_meta")).keySet();
		Set<String> extra = new java.util.LinkedHashSet<>(graphMeta);
		extra.removeAll(successMeta);
		assertEquals("the graph envelope adds exactly one meta key, and it is edgeCount",
				Set.of("edgeCount"), extra);
		assertTrue("and removes none", successMeta.stream().allMatch(graphMeta::contains));
	}

	/**
	 * The interval the envelope constant is chosen from, derived here rather than transcribed.
	 *
	 * <p>Unlike every per-item width in this class, this constant is not a measured figure: no
	 * single envelope width exists to measure, because the width varies by tool and branch. It is
	 * the centre of the interval that holds the contract on the branches not declared as
	 * exclusions, and both the interval and the centring are claims its documentation publishes.
	 * The band checks cannot see either -- they pass for every value in the interval, which is what
	 * the interval means -- so a documented interval that had gone stale, or a constant that had
	 * drifted to one end of it, would look exactly like a healthy one.</p>
	 *
	 * <p>So this recomputes the interval from the measured branches and asserts the published
	 * figures against it. It is what makes the sentence "280 is the centre of the interval
	 * [261, 296]" a checked statement rather than a remembered one.</p>
	 */
	@Test
	public void shouldCentreTheEnvelopeConstantInTheIntervalThatHoldsTheContract() throws Exception {
		int lowerBound = Integer.MIN_VALUE;
		int upperBound = Integer.MAX_VALUE;
		for (EnvelopeCase c : ENVELOPE_CASES) {
			if (DECLARED_OVER_REPORTS.contains(c.label())) {
				continue;
			}
			int measured = measuredEnvelopeTokens(List.of(), 0, c.nextSteps(), c.hasMore());
			lowerBound = Math.max(lowerBound, smallestWidthWithin(measured));
			upperBound = Math.min(upperBound, largestWidthWithin(measured));
		}
		assertEquals("the narrowest width that still holds every branch this constant covers",
				261, lowerBound);
		assertEquals("the widest width that still holds every branch this constant covers",
				296, upperBound);
		assertTrue("the shipped width must be inside the interval it is documented as covering ("
				+ CostEstimator.ENVELOPE_OVERHEAD_CHARS + " against [" + lowerBound + ", "
				+ upperBound + "])",
				CostEstimator.ENVELOPE_OVERHEAD_CHARS >= lowerBound
						&& CostEstimator.ENVELOPE_OVERHEAD_CHARS <= upperBound);
		// "Chosen for maximum margin at both ends" is the documented reason for this value rather
		// than any other inside the interval, so it is worth checking: nearer one end than the
		// other by more than a rounding step means the sentence has stopped being true.
		int centre = (lowerBound + upperBound) / 2;
		assertTrue("the shipped width is documented as the centre of that interval, and the centre "
				+ "is " + centre + " (shipped " + CostEstimator.ENVELOPE_OVERHEAD_CHARS + ")",
				Math.abs(CostEstimator.ENVELOPE_OVERHEAD_CHARS - centre) <= 5);
	}

	/** The narrowest envelope width whose estimate is still within the contract of {@code measured}. */
	private static int smallestWidthWithin(int measuredTokens) {
		for (int width = 1; width <= 2000; width++) {
			int estimated = (width + CostEstimator.CHARS_PER_TOKEN - 1) / CostEstimator.CHARS_PER_TOKEN;
			if (Math.abs(estimated - measuredTokens) / (double) measuredTokens <= TOLERANCE) {
				return width;
			}
		}
		throw new AssertionError("no width holds " + measuredTokens + " tokens");
	}

	/** The widest such width. */
	private static int largestWidthWithin(int measuredTokens) {
		for (int width = 2000; width >= 1; width--) {
			int estimated = (width + CostEstimator.CHARS_PER_TOKEN - 1) / CostEstimator.CHARS_PER_TOKEN;
			if (Math.abs(estimated - measuredTokens) / (double) measuredTokens <= TOLERANCE) {
				return width;
			}
		}
		throw new AssertionError("no width holds " + measuredTokens + " tokens");
	}

	// ---- the handlers these envelope measurements are taken from ----

	/**
	 * Every suggestion list above must still be the one its handler emits.
	 *
	 * <p><strong>This reverses a ruling this class used to publish.</strong> Its own documentation
	 * said these lists are "fixture INPUT transcribed from the handlers, not an assertion about
	 * them", because "a handler that rewords its own suggestions is a re-measurement, not a
	 * regression". The premise is right and the conclusion was backwards. A re-measurement is
	 * exactly the event that has to be announced: these suggestions are most of what a zero-result
	 * envelope contains, so rewording one moves the envelope width, the interval the shipped
	 * constant is centred in, and the membership of the two declared exclusions -- and every check
	 * in this class would go on passing, measuring a payload the handler no longer emits. Silence
	 * there is not benignity, it is the measurement going stale unobserved.</p>
	 *
	 * <p><strong>Why reading the handler source is legitimate here.</strong> This repository has a
	 * parity guard that refuses to do this and says why: a tool description is assembled by
	 * concatenation, and javac folds a concatenated description into one constant-pool entry, so
	 * the source text is not evidence of what is served -- that guard reads the live registry
	 * instead. The objection is real and does not reach these. A {@code nextSteps} entry is a
	 * single unconcatenated literal inside a {@code List.of(...)}, so the source text IS the served
	 * text, and the one entry that is assembled is declared as assembled and matched by its
	 * fragments. Reading the source also reaches branches no registry read can: the lists live
	 * inside conditional arms that only a request of the right shape would produce.</p>
	 *
	 * <p><strong>Scoped by the whole list, not by the presence of a literal.</strong> Seventeen of
	 * the nineteen distinct suggestions appear more than once across the handlers -- one of them
	 * eight times in a single file -- and {@code get-view-contents} is the sharp case: "Use
	 * get-element with an element ID for detailed information" is emitted by both the summary
	 * branch and the JSON branch this class measures. A guard checking that each literal appears
	 * somewhere would be satisfied by the summary branch and would certify nothing. So the
	 * assertion is that the case's literals appear CONSECUTIVELY, in order, among the handler's
	 * string literals: a list is matched as the unit it is measured as, and no other branch sharing
	 * one of its lines can satisfy it.</p>
	 *
	 * <p>Comments are stripped before matching, following this repository's other source-reading
	 * guard, because a Javadoc quoting a suggestion has satisfied one of these before.</p>
	 */
	@Test
	public void shouldStillEmitEverySuggestionListTheEnvelopeIsMeasuredOn() throws Exception {
		Map<String, List<String>> literalsByHandler = new LinkedHashMap<>();
		List<String> drifted = new ArrayList<>();
		for (EnvelopeCase c : ENVELOPE_CASES) {
			List<String> literals = literalsByHandler.computeIfAbsent(c.handler(), handler -> {
				try {
					return stringLiteralsOf(stripComments(Files.readString(handlerSource(handler))));
				} catch (Exception e) {
					throw new AssertionError("could not read " + handler, e);
				}
			});
			if (indexOfSublist(literals, c.sourceLiterals()) < 0) {
				drifted.add(c.label() + " (" + c.handler() + ")");
			}
		}
		assertEquals("these suggestion lists ARE the envelope width this class measures, so one "
				+ "that no longer appears in its handler -- as a consecutive run of literals, in "
				+ "order -- means the measurement behind ENVELOPE_OVERHEAD_CHARS and behind both "
				+ "declared exclusions was taken against a payload that is no longer emitted. "
				+ "Re-measure and re-anchor rather than editing the list here to match.",
				List.of(), drifted);
	}

	/**
	 * The guard above is scoped by the whole list for a reason, and this is the reason: a check
	 * that only looked for each literal somewhere in the handler would pass over a list the handler
	 * no longer emits, because another branch of the same handler emits one of its lines.
	 */
	@Test
	public void shouldNotBeSatisfiedByALiteralFromADifferentBranch() throws Exception {
		List<String> literals = stringLiteralsOf(stripComments(Files.readString(handlerSource(VIEW))));
		String shared = "Use get-element with an element ID for detailed information";
		assertTrue("the premise: this line really is emitted by more than one branch",
				literals.stream().filter(shared::equals).count() > 1);
		assertTrue("and the measured list really is present as a run",
				indexOfSublist(literals, nextStepsFor("get-view-contents/complete")) >= 0);
		assertTrue("but that line paired with a suggestion from the OTHER branch is not, which a "
				+ "per-literal check could not tell apart",
				indexOfSublist(literals, List.of(shared,
						"Re-run with format=json for full element and relationship data")) < 0);
	}

	private static Path handlerSource(String fileName) {
		Path dir = Path.of("").toAbsolutePath();
		while (dir != null && !Files.isDirectory(dir.resolve("net.vheerden.archi.mcp/src"))) {
			dir = dir.getParent();
		}
		assertNotNull("could not locate the plugin source tree", dir);
		return dir.resolve("net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/handlers")
				.resolve(fileName);
	}

	/** Block and line comments removed, so a quoted suggestion in a Javadoc cannot satisfy this. */
	private static String stripComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
	}

	/** Every Java string literal in the source, in order, unescaped. */
	private static List<String> stringLiteralsOf(String source) {
		List<String> literals = new ArrayList<>();
		Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(source);
		while (m.find()) {
			literals.add(m.group(1).replace("\\n", "\n").replace("\\\"", "\"")
					.replace("\\\\", "\\"));
		}
		return literals;
	}

	/** Where {@code needle} appears as a consecutive run inside {@code haystack}, or -1. */
	private static int indexOfSublist(List<String> haystack, List<String> needle) {
		return java.util.Collections.indexOfSubList(haystack, needle);
	}

	/**
	 * The positive control for the exclusion above: the excluded branch must still be outside the
	 * contract, and outside it in the over-reporting direction. Without this, deleting the
	 * exclusion's justification -- or narrowing the envelope until this branch came inside the
	 * band while others fell out -- would leave the skip above silently unearned.
	 */
	@Test
	public void shouldOverReportTheEnvelopeBranchesNoConstantCanCover() throws Exception {
		for (String label : DECLARED_OVER_REPORTS) {
			EnvelopeCase excluded = ENVELOPE_CASES.stream().filter(c -> c.label().equals(label))
					.findFirst().orElseThrow(() -> new AssertionError(
							"a declared exclusion names a branch this class does not measure: " + label));
			int measured = measuredEnvelopeTokens(List.of(), 0, excluded.nextSteps(), excluded.hasMore());
			int estimated = CostEstimator.estimateTokens(0, FieldPreset.STANDARD, ItemType.ELEMENT);
			double error = (estimated - measured) / (double) measured;
			assertTrue(String.format(
					"%s is now within %.0f%% (estimator %d, serializer %d). The exclusion is stale: drop it "
							+ "from the list above, and delete this test once the list is empty.",
					label, TOLERANCE * 100, estimated, measured),
					error > TOLERANCE);
		}
	}

	// ---- the derived surfaces must inherit the same widths ----

	@Test
	public void shouldEstimateViewContentsRelationshipHalfWithinContract_atEveryPreset() throws Exception {
		for (FieldPreset preset : FieldPreset.values()) {
			int measured = measuredTokens(relationshipFixture(), preset);
			int estimated = viewContentsEstimate(0, SAMPLE_SIZE, 0, 0, 0, 0, 0, preset);
			double error = Math.abs(estimated - measured) / (double) measured;
			assertTrue(String.format("view-contents relationship half at %s: estimator %d, serializer %d (%.0f%% off)",
					preset, estimated, measured, error * 100), error <= TOLERANCE);
		}
	}

	@Test
	public void shouldEstimateViewContentsElementHalfWithinContract_atEveryPreset() throws Exception {
		// The half the class had no coverage of at all: the only assertion that reached this
		// estimate passed an element count of zero, so the element term was never priced here.
		for (FieldPreset preset : FieldPreset.values()) {
			int measured = measuredTokens(elementFixture(), preset);
			int estimated = viewContentsEstimate(SAMPLE_SIZE, 0, 0, 0, 0, 0, 0, preset);
			double error = Math.abs(estimated - measured) / (double) measured;
			assertTrue(String.format("view-contents element half at %s: estimator %d, serializer %d (%.0f%% off)",
					preset, estimated, measured, error * 100), error <= TOLERANCE);
		}
	}

	// ---- the whole get-view-contents payload, priced as one object ----

	/**
	 * A whole {@link ViewContentsDto} at the scale the rest of this class measures at.
	 *
	 * <p><strong>Why the view is scaled rather than the envelope subtracted.</strong> Every other
	 * measured side in this class repeats one row {@link #SAMPLE_SIZE} times so that the envelope
	 * constant and integer rounding are not the signal. A view-contents response is a single
	 * object and cannot be repeated, so the same property has to be bought a different way: the
	 * view itself is built at that scale, with a thousand rows in each array. At those counts the
	 * envelope is under a tenth of a percent of the payload, exactly as it is for the per-item
	 * checks, and the band means the same thing here that it means everywhere else in the class.
	 * The alternative -- pricing one small view and subtracting the envelope from both sides --
	 * would have measured a regime where the envelope is a third of the estimate, which is a
	 * different question and a much weaker check.</p>
	 */
	private static ViewContentsDto viewContentsFixture(int elements, int relationships,
			int visualNodes, int connections, int groups, int notes, int images) {
		return viewContentsFixture(elements, relationships, visualNodes, connections, groups, notes,
				images, -1);
	}

	/**
	 * @param bendpointsEach every connection carries exactly this many bendpoints, for the boundary
	 *                       probe; -1 keeps the census blend the other checks measure against
	 */
	private static ViewContentsDto viewContentsFixture(int elements, int relationships,
			int visualNodes, int connections, int groups, int notes, int images,
			int bendpointsEach) {
		return new ViewContentsDto("id-view-0123456789abcdef0123456789", chars(37), chars(18),
				"manual",
				fill(elements, CostEstimatorPayloadWidthContractTest::elementFixture),
				fill(relationships, CostEstimatorPayloadWidthContractTest::relationshipFixture),
				visualNodeFixtures(visualNodes),
				bendpointsEach < 0 ? viewConnectionFixtures(connections)
						: viewConnectionFixtures(connections, bendpointsEach),
				groups == 0 ? null : viewGroupFixtures(groups),
				notes == 0 ? null : fill(notes, CostEstimatorPayloadWidthContractTest::viewNoteFixture),
				images == 0 ? null : fill(images, CostEstimatorPayloadWidthContractTest::imageFixture));
	}

	private static <T> List<T> fill(int count, java.util.function.Supplier<T> row) {
		List<T> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			rows.add(row.get());
		}
		return rows;
	}

	private static int viewContentsEstimate(int elements, int relationships, int visualNodes,
			int connections, int groups, int notes, int images, FieldPreset preset) {
		return CostEstimator.estimateTokensForViewContents(new CostEstimator.ViewContentsCounts(
				elements, relationships, visualNodes, connections, groups, notes, images), preset);
	}

	private static int measuredViewContentsTokens(ViewContentsDto contents, FieldPreset preset,
			Set<String> exclude) throws Exception {
		Object shaped = FieldSelector.applyFieldSelection(contents, preset, exclude);
		return measuredEnvelopeTokens(shaped,
				contents.elements().size() + contents.relationships().size(),
				nextStepsFor("get-view-contents/complete"));
	}

	private static void assertViewContentsWithinContract(String label, ViewContentsDto contents,
			CostEstimator.ViewContentsCounts counts, FieldPreset preset, Set<String> exclude)
			throws Exception {
		int measured = measuredViewContentsTokens(contents, preset, exclude);
		int estimated = CostEstimator.estimateTokensForViewContents(counts, preset);
		double error = Math.abs(estimated - measured) / (double) measured;
		assertTrue(String.format(
				"%s at %s: estimator says %d tokens, the serializer emits %d (%.0f%% off, contract is %.0f%%)",
				label, preset, estimated, measured, error * 100, TOLERANCE * 100),
				error <= TOLERANCE);
	}

	/**
	 * The composite, at every preset. Checking each array in isolation would not have caught the
	 * defect this covers: the five visual widths could each be right while the estimate omitted
	 * them entirely, which is what it did.
	 */
	@Test
	public void shouldEstimateTheWholeViewContentsPayloadWithinContract_atEveryPreset()
			throws Exception {
		int n = SAMPLE_SIZE;
		ViewContentsDto contents = viewContentsFixture(n, n, n, n, n, n, n);
		for (FieldPreset preset : FieldPreset.values()) {
			assertViewContentsWithinContract("whole view-contents payload", contents,
					new CostEstimator.ViewContentsCounts(n, n, n, n, n, n, n), preset, null);
		}
	}

	/**
	 * The shape the three views the defect was measured on actually have: elements, relationships
	 * and their visual rows, and none of the three arrays that arrive null when empty. Those three
	 * views showed four arrays rather than seven, which is why a four-term fix would have looked
	 * complete.
	 */
	@Test
	public void shouldEstimateTheCommonViewContentsShapeWithinContract_atEveryPreset()
			throws Exception {
		int n = SAMPLE_SIZE;
		ViewContentsDto contents = viewContentsFixture(n, n, n, n, 0, 0, 0);
		for (FieldPreset preset : FieldPreset.values()) {
			assertViewContentsWithinContract("view-contents without groups, notes or images",
					contents, new CostEstimator.ViewContentsCounts(n, n, n, n, 0, 0, 0), preset, null);
		}
	}

	/**
	 * The caller who followed the tool's own advice. Charging the visual arrays without following
	 * {@code exclude} would turn this from an under-report into a large OVER-report, for exactly
	 * the caller who did the right thing -- so the estimate has to fall, and it has to stay inside
	 * the contract once it has.
	 */
	@Test
	public void shouldEstimateTheExcludedViewContentsPayloadWithinContract_atEveryPreset()
			throws Exception {
		int n = SAMPLE_SIZE;
		Set<String> exclude = Set.of("visualMetadata", "connections");
		ViewContentsDto contents = viewContentsFixture(n, n, n, n, n, 0, 0);
		for (FieldPreset preset : FieldPreset.values()) {
			assertViewContentsWithinContract("view-contents with visualMetadata and connections excluded",
					contents, new CostEstimator.ViewContentsCounts(n, n, 0, 0, n, 0, 0), preset, exclude);
		}
	}

	/**
	 * Each visual array's own width, isolated by subtraction.
	 *
	 * <p><strong>The composite check above cannot see these constants, and one of them it cannot
	 * see at all.</strong> A band is blind to any term smaller than the band: the image width is
	 * about 7% of a payload carrying every array, so deleting it outright leaves the composite
	 * comfortably inside the contract and green. Two payloads differing only in one array isolate
	 * that array's contribution exactly -- the envelope, and every other array, appear identically
	 * on both sides and cancel -- so each width is checked against the serializer at full strength
	 * whatever its share of the whole. This is the same subtraction the expanded relationship
	 * header above needs, and for the same reason.</p>
	 */
	@Test
	public void shouldEstimateEachVisualArrayWidthWithinContract_isolatedBySubtraction()
			throws Exception {
		int n = SAMPLE_SIZE;
		assertVisualArrayWidthWithinContract("visualMetadata", CostEstimator.VISUAL_NODE_CHARS,
				viewContentsFixture(n, n, n, 0, 0, 0, 0), viewContentsFixture(n, n, 0, 0, 0, 0, 0));
		assertVisualArrayWidthWithinContract("connections", CostEstimator.VIEW_CONNECTION_CHARS,
				viewContentsFixture(n, n, 0, n, 0, 0, 0), viewContentsFixture(n, n, 0, 0, 0, 0, 0));
		assertVisualArrayWidthWithinContract("groups", CostEstimator.VIEW_GROUP_CHARS,
				viewContentsFixture(n, n, 0, 0, n, 0, 0), viewContentsFixture(n, n, 0, 0, 0, 0, 0));
		assertVisualArrayWidthWithinContract("notes", CostEstimator.VIEW_NOTE_CHARS,
				viewContentsFixture(n, n, 0, 0, 0, n, 0), viewContentsFixture(n, n, 0, 0, 0, 0, 0));
		assertVisualArrayWidthWithinContract("images", CostEstimator.DIAGRAM_IMAGE_CHARS,
				viewContentsFixture(n, n, 0, 0, 0, 0, n), viewContentsFixture(n, n, 0, 0, 0, 0, 0));
	}

	private void assertVisualArrayWidthWithinContract(String array, int shippedWidth,
			ViewContentsDto with, ViewContentsDto without) throws Exception {
		int measured = measuredViewContentsTokens(with, FieldPreset.STANDARD, null)
				- measuredViewContentsTokens(without, FieldPreset.STANDARD, null);
		int estimated = (SAMPLE_SIZE * shippedWidth) / CostEstimator.CHARS_PER_TOKEN;
		double error = Math.abs(estimated - measured) / (double) measured;
		assertTrue(String.format(
				"%s: the estimator charges %d tokens per %d rows, the serializer emits %d once a "
						+ "payload without that array is subtracted (%.0f%% off, contract is %.0f%%)",
				array, estimated, SAMPLE_SIZE, measured, error * 100, TOLERANCE * 100),
				error <= TOLERANCE);
	}

	/**
	 * The bendpoint range the connection width is documented as holding, asserted rather than
	 * described.
	 *
	 * <p>A connection row is the only width in this file whose size is unbounded in principle: it
	 * carries its bendpoints twice, once relative and once in absolute canvas coordinates, so the
	 * row grows about 75 chars per bendpoint. One constant therefore cannot hold the contract
	 * across the whole range, and its Javadoc says which part of the range it does hold.</p>
	 *
	 * <p><strong>Nothing else here can see that claim.</strong> The census-blended fixture puts 94%
	 * of its rows at three bendpoints, which is where the width is anchored and where the error is
	 * near zero, so every other check in this class passes comfortably however wrong the boundaries
	 * are. This brackets them: inside the contract at two and at five, and outside it -- in the
	 * over-reporting direction, which is the safe one -- at one and at zero. The failing end is a
	 * positive control, so narrowing the constant until a boundary quietly came inside the band
	 * would fail here rather than pass unnoticed.</p>
	 */
	@Test
	public void shouldEstimateTheConnectionWidthWithinContract_acrossTheDisclosedBendpointRange()
			throws Exception {
		for (int bendpoints : new int[] { 2, 3, 4, 5 }) {
			double error = connectionWidthErrorAt(bendpoints);
			assertTrue(String.format(
					"the connection width is documented as holding the contract from two bendpoints "
							+ "to five, but at %d it is %.0f%% off (contract is %.0f%%)",
					bendpoints, error * 100, TOLERANCE * 100),
					Math.abs(error) <= TOLERANCE);
		}
		for (int bendpoints : new int[] { 0, 1 }) {
			double error = connectionWidthErrorAt(bendpoints);
			assertTrue(String.format(
					"at %d bendpoints the width is documented as NOT holding the contract, and as "
							+ "over-reporting. It is now %.0f%% off. If it has come inside the band, "
							+ "the Javadoc's range is stale: widen it there and here together.",
					bendpoints, error * 100),
					error > TOLERANCE);
		}
	}

	/** Signed error of the shipped connection width against a payload whose rows carry {@code k}. */
	private static double connectionWidthErrorAt(int bendpoints) throws Exception {
		int n = SAMPLE_SIZE;
		ViewContentsDto with = viewContentsFixture(n, n, 0, n, 0, 0, 0, bendpoints);
		ViewContentsDto without = viewContentsFixture(n, n, 0, 0, 0, 0, 0);
		int measured = measuredViewContentsTokens(with, FieldPreset.STANDARD, null)
				- measuredViewContentsTokens(without, FieldPreset.STANDARD, null);
		int estimated = (n * CostEstimator.VIEW_CONNECTION_CHARS) / CostEstimator.CHARS_PER_TOKEN;
		return (estimated - measured) / (double) measured;
	}

	/**
	 * These five arrays are emitted raw, with no preset argument, so their contribution is
	 * identical at every preset. Asserted as a difference rather than as whole estimates, because
	 * comparing whole estimates would be satisfied by the concept halves not moving either -- which
	 * is false, and is not the property under test.
	 */
	@Test
	public void shouldNotNarrowTheVisualArraysAtTheMinimalPreset_becauseTheSerializerDoesNot()
			throws Exception {
		int n = SAMPLE_SIZE;
		ViewContentsDto with = viewContentsFixture(n, n, n, n, n, n, n);
		ViewContentsDto without = viewContentsFixture(n, n, 0, 0, 0, 0, 0);
		int atStandard = measuredViewContentsTokens(with, FieldPreset.STANDARD, null)
				- measuredViewContentsTokens(without, FieldPreset.STANDARD, null);
		int estimatedAtStandard = viewContentsEstimate(n, n, n, n, n, n, n, FieldPreset.STANDARD)
				- viewContentsEstimate(n, n, 0, 0, 0, 0, 0, FieldPreset.STANDARD);
		for (FieldPreset preset : FieldPreset.values()) {
			int measured = measuredViewContentsTokens(with, preset, null)
					- measuredViewContentsTokens(without, preset, null);
			// One token of slack on each comparison, and only one: both sides are rounded up to a
			// whole token before the subtraction, so a difference of two rounded figures can move
			// by a token without any width having moved.
			assertTrue("the serializer must emit the same visual rows at " + preset + " -- "
					+ atStandard + " at standard, " + measured + " here",
					Math.abs(measured - atStandard) <= 1);
			int estimated = viewContentsEstimate(n, n, n, n, n, n, n, preset)
					- viewContentsEstimate(n, n, 0, 0, 0, 0, 0, preset);
			assertTrue("so the estimate must charge the same for them at " + preset + " -- "
					+ estimatedAtStandard + " at standard, " + estimated + " here",
					Math.abs(estimated - estimatedAtStandard) <= 1);
		}
	}

	@Test
	public void shouldFallWhenAnArrayIsExcluded_forViewContents() {
		int n = SAMPLE_SIZE;
		int full = CostEstimator.estimateTokensForViewContents(
				new CostEstimator.ViewContentsCounts(n, n, n, n, n, n, n), FieldPreset.STANDARD);
		int excluded = CostEstimator.estimateTokensForViewContents(
				new CostEstimator.ViewContentsCounts(n, n, 0, 0, n, n, n), FieldPreset.STANDARD);
		assertTrue("excluding two arrays must reduce the estimate, not leave it flat",
				excluded < full);
	}

	// ---- fixtures for the five arrays the estimate used to ignore ----
	//
	// Census-calibrated against the eight captured reference models, and calibrated as a MIX
	// rather than as one representative row. A styling field on a view object is present only
	// when the user moved it off Archi's default -- the accessor returns null for a default and
	// NON_NULL then omits the key -- so these arrays are not rows of one shape. Over 2829 node
	// rows the parent id is present on 87.8% and no other optional key clears 9%; over 1398
	// connections the two bendpoint lists are present on 93.9% and carry 2.96 points each; over
	// 254 groups the fill colour is present on 86.2% and the font on 64.2%.
	//
	// One row at the modal shape would be the wrong fixture and would be wrong in a way that
	// HIDES drift: it lands about 17% wide of the census, which spends most of the accuracy band
	// before the check has begun and leaves a real 27% error in a shipped width still inside it.
	// Building the list at the measured incidences puts the whole band back where it belongs.
	// The row index drives the mix deterministically, so the fixture is reproducible.

	/** The percentile this row sits at, 0-99, so a field present on N% of the census is on N rows. */
	private static int percentile(int index, int count) {
		return (index * 100) / count;
	}

	private static List<ViewNodeDto> visualNodeFixtures(int count) {
		List<ViewNodeDto> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int p = percentile(i, count);
			rows.add(new ViewNodeDto(ID, ID, 1224, 856, 145, 61,
					p < 88 ? ID : null,
					p < 9 ? "#F4F4F4" : null,
					null, null, null,
					p < 1 ? Integer.valueOf(2) : null,
					p < 7 ? IMAGE_PATH : null,
					p < 7 ? "fill" : null,
					null, null,
					p < 1 ? "left" : null,
					null, null, null,
					p < 5 ? Integer.valueOf(13) : null,
					p < 5 ? "bold" : null,
					null, null, null, null));
		}
		return rows;
	}

	/** {@code k} stored bendpoints, and the {@code k} absolute ones the accessor derives from them. */
	private static List<BendpointDto> bendpoints(int k) {
		List<BendpointDto> points = new ArrayList<>();
		for (int i = 0; i < k; i++) {
			points.add(new BendpointDto(-84 + (i * 49), 132 + (i * 42), 176 - (i * 31), -48 + (i * 28)));
		}
		return points;
	}

	private static List<AbsoluteBendpointDto> absoluteBendpoints(int k) {
		List<AbsoluteBendpointDto> points = new ArrayList<>();
		for (int i = 0; i < k; i++) {
			points.add(new AbsoluteBendpointDto(1140 + (i * 65), 988 + (i * 43)));
		}
		return points;
	}

	private static List<ViewConnectionDto> viewConnectionFixtures(int count) {
		List<BendpointDto> bendpoints = bendpoints(3);
		List<AbsoluteBendpointDto> absolute = absoluteBendpoints(3);
		List<ViewConnectionDto> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int p = percentile(i, count);
			boolean routed = p < 94;
			rows.add(new ViewConnectionDto(ID, ID, chars(21), ID, ID,
					routed ? bendpoints : null,
					routed ? absolute : null,
					new AnchorPointDto(1224, 856), new AnchorPointDto(1420, 1104),
					p < 3 ? Integer.valueOf(2) : null,
					p < 14 ? "#5C5C5C" : null,
					p < 10 ? Integer.valueOf(2) : null,
					p < 3 ? "#000000" : null,
					p < 28 ? Boolean.FALSE : null,
					null,
					p < 3 ? Integer.valueOf(9) : null,
					null, null, null, "bottom", "top"));
		}
		return rows;
	}

	/**
	 * Connections all carrying the same bendpoint count. Deliberately NOT census-blended: this is a
	 * boundary probe, and blending it would put 94% of the rows back at the anchor and hide exactly
	 * the ends being probed.
	 */
	private static List<ViewConnectionDto> viewConnectionFixtures(int count, int bendpointsEach) {
		List<BendpointDto> relative = bendpoints(bendpointsEach);
		List<AbsoluteBendpointDto> absolute = absoluteBendpoints(bendpointsEach);
		List<ViewConnectionDto> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int p = percentile(i, count);
			rows.add(new ViewConnectionDto(ID, ID, chars(21), ID, ID,
					relative.isEmpty() ? null : relative,
					absolute.isEmpty() ? null : absolute,
					new AnchorPointDto(1224, 856), new AnchorPointDto(1420, 1104),
					p < 3 ? Integer.valueOf(2) : null,
					p < 14 ? "#5C5C5C" : null,
					p < 10 ? Integer.valueOf(2) : null,
					p < 3 ? "#000000" : null,
					p < 28 ? Boolean.FALSE : null,
					null,
					p < 3 ? Integer.valueOf(9) : null,
					null, null, null, "bottom", "top"));
		}
		return rows;
	}

	private static List<ViewGroupDto> viewGroupFixtures(int count) {
		// Six child ids is the corpus mean; the label averages 24 chars.
		List<String> children = List.of(ID, ID, ID, ID, ID, ID);
		List<ViewGroupDto> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int p = percentile(i, count);
			rows.add(new ViewGroupDto(ID, chars(24), 24, 120, 1160, 320,
					p < 2 ? ID : null, children,
					p < 86 ? "#FBFBFB" : null,
					null, null, null, null,
					p < 3 ? IMAGE_PATH : null,
					p < 3 ? "fill" : null,
					null,
					p < 12 ? "rectangular" : null,
					p < 21 ? "left" : null,
					null, null, null,
					p < 64 ? Integer.valueOf(13) : null,
					p < 64 ? "bold" : null,
					null, null, null, null));
		}
		return rows;
	}

	private static ViewNoteDto viewNoteFixture() {
		// 303 chars of content is the corpus mean over 26 notes. The `note` field stays null
		// because the read path sets it null -- populating it here would price the note's text
		// twice and inflate the width by a third. It has no styling tail worth blending: no
		// optional key on a note reaches 5% of the census.
		return new ViewNoteDto(ID, chars(303), 1400, 96, 300, 160, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null);
	}

	private static DiagramImageDto imageFixture() {
		return new DiagramImageDto(ID, IMAGE_PATH, 1420, 640, 180, 120, null, null, chars(190),
				null);
	}
}
