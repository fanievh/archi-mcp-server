package net.vheerden.archi.mcp.response;

import net.vheerden.archi.mcp.response.FieldSelector.FieldPreset;

/**
 * Token estimation utility for dry-run cost estimation.
 *
 * <p>Estimates approximate token counts for query results based on item count,
 * field preset, and item type. Uses character-per-item heuristics divided by
 * a standard chars-per-token ratio.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class imports only
 * {@link FieldSelector.FieldPreset}. It has no handler, model, or session dependencies.</p>
 */
public final class CostEstimator {

	// --- Per-item character estimates by field preset ---
	//
	// Every width in this file was measured by serializing a census-calibrated DTO through
	// FieldSelector.applyFieldSelection and the same Jackson configuration the response formatter
	// uses, then dividing by the row count so each figure includes the comma that joins one row to
	// the next. A figure reasoned from field arithmetic is a prediction to check a measurement
	// against, never a value to ship.
	//
	// Each Javadoc records the MEASURED figure; the shipped constant is that figure ROUNDED TO
	// THE NEAREST 5. The rounding is deliberate and is at most 1.85% across every measured pair in
	// this file -- far inside the 20% accuracy these estimates are held to -- and it keeps
	// the constants legible as the heuristics they are rather than implying a precision a single
	// fixture cannot support. A future re-measurement should round the same way rather than
	// pinning an exact byte count.
	//
	// The bound is 1.85% rather than the 1.1% this paragraph used to claim, and it is worth saying
	// which pairs set it, because three of them exceeded the old figure: RELATIONSHIP_MINIMAL is
	// the widest at 54 -> 55 = 1.85%, then ELEMENT_MINIMAL and ELEMENT_SUMMARY_MINIMAL at
	// 81 -> 80 = 1.23%. A rounding step of 5 is a larger fraction of a narrow width than of a wide
	// one, so it is always the smallest constants that set this bound, and adding a narrower one
	// would move it again.
	//
	// ELEMENT_STANDARD_CHARS is the one constant here that is NOT a rounding: it is 250 against a
	// measured 262, which is 4.6% away and is a deliberate choice made for the reason its own
	// Javadoc gives. Reading this paragraph as covering it would be reading it as a claim about a
	// constant it has never described.

	/**
	 * Minimal is {@code id} and {@code name} only: measured 81 chars on 2026-09-05 by the same
	 * method as the view and relationship widths below, rounded to 80.
	 *
	 * <p>This is the first measurement this constant has had. The previous 60 was a
	 * pre-measurement estimate, and it was 26% low -- outside the accuracy these estimates are
	 * held to, in the direction that under-charges the caller.</p>
	 */
	static final int ELEMENT_MINIMAL_CHARS = 80;

	/**
	 * Measured 262 chars against the census fixture for an element carrying {@code documentation}.
	 * Left at the 250 it was set to independently of any measurement: 4.6% apart is well inside
	 * the accuracy contract, and this width doubles as the control that validates the measurement
	 * method itself.
	 *
	 * <p><strong>No single value can hold the accuracy contract for this width, and this one is
	 * the best available.</strong> Re-measured on 2026-09-05 over the 1959 elements in the eight
	 * captured reference models -- rebuilt from the stored models and put through the real field
	 * selector, so these are the widths the serializer really emits and not a reconstruction:</p>
	 *
	 * <pre>
	 *   216  233  268  240  247  255  368  368      chars per standard element row, one per model
	 * </pre>
	 *
	 * <p>That is a 1.70x spread against an accuracy band that spans at most 1.2/0.8 = 1.50x, so the
	 * disjointness is arithmetic rather than a matter of choosing better: holding the narrowest
	 * model needs a width of at most 259 and holding the widest needs at least 295. Sweeping every
	 * candidate from 150 to 450, the fewest models any single width can hold is six of eight, and
	 * the whole range 215 to 255 achieves it. The shipped 250 is inside that optimal set. Moving it
	 * toward the widest models would make the estimator worse, not better: 262 misses three models,
	 * 283 misses four, and the widest model's own 368 misses six.
	 *
	 * <p>The spread is drift rather than noise. The two newest models are about 40% wider than the
	 * six older ones -- longer names, roughly double the documentation, and properties on 28-49% of
	 * elements against 0-18% -- so a measurement taken against any one model is a measurement of
	 * when that model was captured. That is also why a width derived from the elements actually in
	 * hand, rather than a constant, is the only thing that could hold the contract across this
	 * range; it is a different shape of change and is tracked separately.</p>
	 *
	 * <p>All eight models are one lineage rather than a sample of real models in general, so this
	 * is evidence about the spread a single corpus contains, which is the point being made.</p>
	 */
	static final int ELEMENT_STANDARD_CHARS = 250;

	/**
	 * Equal to {@link #ELEMENT_STANDARD_CHARS}, and equal by derivation rather than by
	 * coincidence: {@code FieldSelector} defines the full element field set as the standard one,
	 * so the full preset selects exactly the fields standard selects and the serializer emits the
	 * identical row. There is no measurement of its own to record because there is no distinct row
	 * to measure.
	 *
	 * <p><strong>Moving one of these two without the other is a defect rather than a choice.</strong>
	 * Two tests pin them equal, one of them in a different class from this constant's own, so an
	 * estimator-only test run will not show the second failing. If a future field set makes the
	 * full preset genuinely wider, measure it and move both pins deliberately.</p>
	 */
	static final int ELEMENT_FULL_CHARS = 250;

	/**
	 * View widths, measured 2026-09-04 by serializing a census-calibrated
	 * {@code ViewDto} through {@code FieldSelector.applyFieldSelection} and the same
	 * Jackson configuration the response formatter uses. Census: 88 views across the
	 * captured reference models (name 37 chars, viewpoint present on 81%, router type
	 * always emitted, folder path "Views").
	 *
	 * <p>Minimal is {@code id} and {@code name} only: measured 91 chars, rounded to 90.</p>
	 */
	static final int VIEW_MINIMAL_CHARS = 90;

	/**
	 * Measured 181 chars for a view carrying a viewpoint and 144 for one without. Views carry a
	 * viewpoint 81% of the time in the census, so the blended width is
	 * {@code 0.81 x 181 + 0.19 x 144 = 174}, rounded to 175. Neither cited figure is the constant
	 * on its own; the blend is what the constant models. See {@link #VIEW_MINIMAL_CHARS}.
	 */
	static final int VIEW_STANDARD_CHARS = 175;

	/**
	 * Measured 383 chars, rounded to 385, for a view whose {@code documentation} and {@code properties}
	 * are populated -- the two fields the full preset adds over standard. Deliberately
	 * anchored on the populated fixture rather than the census mix: a caller selecting
	 * {@code full} is asking for exactly those fields, and over-reporting is the safe
	 * direction for a budget estimate. An undocumented view is over-reported ~2.2x.
	 */
	static final int VIEW_FULL_CHARS = 385;

	/**
	 * Relationship widths, measured 2026-09-04 by the same method as the view widths.
	 * Census: 2365 relationships across the captured reference models (93% unnamed,
	 * type 21 chars, two 35-char endpoint ids, documentation on 4%, no properties).
	 *
	 * <p>Minimal is {@code id} and {@code name} only: measured 54 chars, rounded to 55.</p>
	 */
	static final int RELATIONSHIP_MINIMAL_CHARS = 55;

	/**
	 * Measured 183 chars, rounded to 185. The standard preset carries neither {@code documentation} nor
	 * {@code properties}, so this width is independent of how documented the model is.
	 */
	static final int RELATIONSHIP_STANDARD_CHARS = 185;

	/**
	 * Measured 365 chars (already a multiple of 5) for a relationship whose {@code documentation}
	 * is populated; 267 without. The full preset adds {@code specialization}, {@code documentation},
	 * {@code properties}, {@code sourceName} and {@code targetName}. Anchored on the
	 * populated fixture for the same reason as {@link #VIEW_FULL_CHARS}; an undocumented
	 * relationship is over-reported ~1.4x.
	 */
	static final int RELATIONSHIP_FULL_CHARS = 365;

	// --- Per-row character estimates for the visual arrays get-view-contents emits ---
	//
	// The five widths below were measured on 2026-09-05 over the eight captured reference models
	// by rebuilding each array's DTO rows from the stored models and serializing them through the
	// same Jackson configuration the response formatter uses, then dividing by the row count so
	// each figure includes the comma that joins one row to the next.
	//
	// NONE of them moves with the field preset, and that is a property of the code rather than of
	// the measurement: the field selector routes the element and relationship arrays through its
	// preset-aware list filter and puts these five into the response RAW, with no preset argument
	// at all. A caller narrowing the preset therefore narrows two of the seven arrays and leaves
	// five untouched, which is why narrowing saves the caller far less than the two preset-aware
	// widths alone would suggest.

	/**
	 * One drawn object's position and styling row: measured 205.3 chars over 2829 rows, rounded
	 * to 205. Per-model widths run 196.5 to 209.8 -- a 1.07x spread, comfortably inside the
	 * accuracy these estimates are held to, because the row is dominated by two fixed-width ids
	 * and four integer bounds and the styling fields are omitted when at their defaults.
	 *
	 * <p><strong>There is one of these per DRAWN OBJECT, not per element.</strong> The accessor
	 * deduplicates the element array by element id and deliberately does not deduplicate this one,
	 * because the same element drawn at two positions is two meaningful rows. Over the captured
	 * models this array carries 1.32 to 1.57 rows for every distinct element, so a width multiplied
	 * by the element count would under-report every view that draws a concept twice. The estimate
	 * reads the array's own size.</p>
	 */
	static final int VISUAL_NODE_CHARS = 205;

	/**
	 * One drawn connection's row: measured 642.5 chars over 1398 rows, rounded to 640. This is the
	 * widest row anywhere in this family -- roughly 1.75x a full relationship row -- because it
	 * carries the routing geometry as well as the styling: the stored bendpoints, the same
	 * bendpoints again in absolute canvas coordinates, two anchor points and two resolved render
	 * faces.
	 *
	 * <p><strong>The width is not fixed even in principle, and the bendpoint count is the whole
	 * uncertainty.</strong> Measured by bucket over the same corpus: 408 chars at 0 bendpoints,
	 * 504 at 1, 582 at 2, 642 at 3, 718 at 4 and 786 at 5 -- roughly 75 chars per bendpoint,
	 * because each one is emitted twice, once relative and once in absolute canvas coordinates.
	 * The shipped figure is anchored on the census incidence, whose modal bucket is three.
	 *
	 * <p><strong>It holds the accuracy contract from two bendpoints to five, and not below.</strong>
	 * At one bendpoint it over-reports by 27% and at zero by 57%. Both breaches are in the
	 * over-reporting direction, which is the safe one for a budget estimate and the direction the
	 * full-preset widths above are anchored in, and both are rare: 94% of the corpus's connections
	 * carry at least two. The range is asserted rather than described -- a bracket check pins the
	 * contract holding at two and five and failing at zero and one, so this paragraph cannot go
	 * stale without a test going red. Per-model means run 600.1 to 672.9, a 1.12x spread.</p>
	 *
	 * <p>Like the node rows, these are per DRAWN CONNECTION rather than per relationship: the
	 * relationship array is deduplicated by relationship id and this one is not.</p>
	 */
	static final int VIEW_CONNECTION_CHARS = 640;

	/**
	 * One grouping rectangle's row: measured 433.7 chars over 254 rows, rounded to 435. Wider than
	 * a node row because it carries a label and the ids of every object it contains. Per-model
	 * means run 389.6 to 515.3, a 1.32x spread, which is inside the accuracy contract but the
	 * widest of the three arrays that carry one.
	 *
	 * <p>Counted at any nesting depth: the collector recurses into containers and appends every
	 * group it finds to one flat array.</p>
	 */
	static final int VIEW_GROUP_CHARS = 435;

	/**
	 * One text annotation's row: measured 429.6 chars over 26 rows, rounded to 430. The row is
	 * about 127 chars of id and bounds around the note's own text, which averages 303 chars over
	 * the corpus.
	 *
	 * <p><strong>This is the least certain of the five and its uncertainty is disclosed rather than
	 * buried.</strong> A note's width is dominated by author-written text, which nothing bounds:
	 * the corpus's own notes run from 24 to 492 chars of content, so per-model means span 183 to
	 * 473 -- a 2.6x range no single width holds the accuracy contract across. What makes one figure
	 * tolerable here is that notes are rare, 26 rows against 2829 node rows, so the term is a small
	 * fraction of any estimate it appears in and a view with no notes is charged nothing.</p>
	 *
	 * <p>Note that the DTO's {@code note} field is a write-path field: this read path sets it null
	 * and NON_NULL omits it, so the text appears once per row and not twice.</p>
	 */
	static final int VIEW_NOTE_CHARS = 430;

	/**
	 * One standalone image visual's row: measured 343.5 chars over the two the corpus contains,
	 * rounded to 345. The row is an id, the opaque archive path Archi mints for the image, four
	 * integer bounds and the visual's documentation, which is what makes it wider than a node row.
	 *
	 * <p>Two rows is far too thin a census to call this figure settled, and it is published here so
	 * that a later measurement over a corpus that uses images more can replace it knowingly.
	 * Charging zero would have been the worse error: an image visual is a real row on the wire, and
	 * an array left out of the estimate is the exact defect the other four terms exist to close.</p>
	 */
	static final int DIAGRAM_IMAGE_CHARS = 345;

	/**
	 * The per-response envelope every estimate carries: the {@code result} wrapper, the
	 * {@code nextSteps} suggestions, the five {@code _meta} keys the response formatter emits on
	 * the success path, and the {@code durationMs} the command registry adds to every response
	 * after the formatter has returned. Re-measured 2026-09-05 by serializing a real success
	 * envelope through the same Jackson configuration the formatter uses.
	 *
	 * <p><strong>The timing key is inside this width and the other unmodelled terms are not, and
	 * the difference is whether the term is unconditional.</strong> {@code durationMs} is added at
	 * a single site that wraps every registered tool, on the success and error paths alike, with no
	 * branch and no parameter to condition it on: it is 16 chars on every response of every tool.
	 * A term like that belongs inside a flat constant. A term that appears on some responses does
	 * not, because widening a flat width to cover it corrupts every response that does not carry
	 * it, which is the majority.</p>
	 *
	 * <p><strong>This width varies by tool and by result branch, and one constant cannot hold the
	 * accuracy contract across all of them.</strong> Measured across the <strong>fourteen</strong>
	 * success branches of the six dry-run call sites -- six call sites but five tool names, since
	 * {@code get-relationships} estimates from both its depth path and its traverse path -- the
	 * envelope spans <strong>205 to 326 chars</strong>. The two lows are both {@code get-views},
	 * whose empty and complete branches emit a single short suggestion each; the high is
	 * {@code get-relationships} in traverse mode on a complete result, which emits three long
	 * suggestions. Being within the contract for 205 requires at most 248 and being within it for
	 * 326 requires at least 261, so no single value covers both.</p>
	 *
	 * <p>The interval that holds the contract on <strong>twelve of the fourteen</strong> is
	 * [261, 296], whose midpoint is 278.5; 280 is the nearest multiple of 5 to it, which is where
	 * the rounding convention above puts it, and it leaves a margin of 19 and 16 at the two ends. The two remaining branches are
	 * the {@code get-views} pair, over-reported by about a third -- the safe direction for a budget
	 * estimate, and the same direction the full-preset widths above are anchored in. Excluding only
	 * the narrower of the two would not help: the other still caps a covering constant at 256,
	 * below the 261 the widest branch needs. Both exclusions are asserted rather than assumed, so
	 * neither can outlive its justification.</p>
	 *
	 * <p>The result count is <em>not</em> an axis: from an empty result to 320 rows the envelope
	 * moves by at most 4 chars, which is the extra digits in {@code resultCount} and
	 * {@code totalCount}. The figures above are therefore quoted at an empty result, where the
	 * envelope is the entire estimate and nothing else can dilute it.</p>
	 *
	 * <p>Three things are deliberately not modelled, each because its discriminator makes it
	 * conditional rather than flat.</p>
	 *
	 * <p>A paged response carries a base64 pagination cursor appended to {@code _meta} after the
	 * envelope is built, measured at 228 further chars on a four-parameter search cursor. It
	 * appears only on the paged branch of three of the six call sites, and {@code get-view-contents}
	 * and {@code get-relationships} are not paged and never carry one at all -- so a constant
	 * widened to include it would be more than 20% out on every unpaginated response, which is
	 * asserted rather than asserted-of.</p>
	 *
	 * <p>The response format is the larger omission of the three and the least visible. The dry-run
	 * branch returns above the format branches at every one of the six call sites, so one estimate
	 * is published for {@code json}, {@code graph} and {@code summary} alike -- and for
	 * {@code tree} as well on {@code get-view-contents}. The estimate is anchored on {@code json},
	 * so it over-reports the narrower formats rather than under-reporting them, and the error
	 * changes direction rather than scaling. The envelope is not where that divergence lives: a
	 * graph response differs from a success one by exactly one {@code _meta} key, {@code edgeCount},
	 * plus the name of the top-level key. What diverges is the payload shape and the per-tool
	 * suggestions.</p>
	 *
	 * <p>Field-level exclusions narrow the element and relationship rows rather than removing an
	 * array, and a row width cannot be derived from a count. They over-report, which is the safe
	 * direction, and modelling them would mean teaching this class the field selector's
	 * semantics.</p>
	 */
	static final int ENVELOPE_OVERHEAD_CHARS = 280;

	static final int CHARS_PER_TOKEN = 4;

	// --- Traversal per-row character estimates ---
	//
	// Depth 0 has no constant of its own: it returns the flat relationship rows through
	// the same field selection search-relationships uses, so it delegates to the
	// relationship estimate rather than duplicating a constant that must move with it.
	//
	// Above depth 0 the row is composed rather than flat, because its parts move
	// independently: the relationship half is assembled by the traversal handler and is fixed,
	// while the elements embedded in it are filtered by the caller's preset. Composing the width
	// from the parts is what lets the depth-1 element half and the traverse-mode element half
	// read one measurement instead of two constants that would drift apart.

	/**
	 * The fixed part of one expanded relationship row: its own {@code id}, {@code name} and
	 * {@code type}, plus the two keys carrying the embedded source and target. Measured 104 chars
	 * on 2026-09-05, rounded to 105. It does not move with the preset -- the traversal handler
	 * assembles this part itself and it carries no preset-dependent field.
	 */
	static final int EXPANDED_RELATIONSHIP_HEADER_CHARS = 105;

	/**
	 * One embedded element summary: {@code id} and {@code name} at the minimal preset, measured
	 * 81 chars and rounded to 80.
	 *
	 * <p><strong>One width, two readers.</strong> The depth-mode expansion at depth 1 and the
	 * traverse-mode element list build the identical map -- the same two field sets through the
	 * same field-selection helper -- from two different classes. A second constant for the second
	 * reader would be a drift hazard, so both read this one.</p>
	 *
	 * <p>It equals {@link #ELEMENT_MINIMAL_CHARS} today, and that is a coincidence of two field
	 * sets defined in different places rather than an identity: one is the minimal preset's field
	 * set, the other is the summary shape the traversal code hardcodes. Aliasing them would tie
	 * together two things nothing keeps equal.</p>
	 */
	static final int ELEMENT_SUMMARY_MINIMAL_CHARS = 80;

	/**
	 * The same summary at every other preset, which adds {@code type}: measured 105 chars, already
	 * a multiple of 5. See {@link #ELEMENT_SUMMARY_MINIMAL_CHARS}.
	 */
	static final int ELEMENT_SUMMARY_STANDARD_CHARS = 105;

	/**
	 * One hop relationship in a traverse response, plus the key that carries the element it
	 * reached. Measured 184 chars for the relationship and 19 for the {@code connectedElement}
	 * key, so 203, rounded to 205.
	 *
	 * <p><strong>It does not move with the preset, at any preset.</strong> The traversal engine
	 * hand-builds this map instead of running a DTO through field selection, and its field set is
	 * fixed at {@code id}, {@code name}, {@code type}, the two endpoint ids and whichever semantic
	 * attributes are populated -- which is exactly the relationship standard set, whatever the
	 * caller asked for. Charging the minimal or full relationship width here would price fields
	 * the caller neither asked for nor receives.</p>
	 */
	static final int HOP_RELATIONSHIP_CHARS = 205;

	/**
	 * What depth 3 attaches to each embedded element beyond the element's own row: the
	 * {@code relationships} key with its brackets, measured 19 chars, plus the nested rows
	 * themselves at 183 chars each. At the census incidence of 2.41 relationships per element
	 * (2365 relationships across 1959 elements, each touching two), that is 460.
	 *
	 * <p>The incidence is the whole uncertainty in the depth-3 width and it is large: an element
	 * with no relationships costs 19 here and one with five costs 934. No single width holds the
	 * accuracy contract across that range -- the shipped figure holds it either side of the
	 * census incidence and not far beyond. The nested rows also deliberately do not follow the
	 * caller's preset, which is why this is a separate quantity rather than another column of the
	 * widths above. Tracked separately.</p>
	 */
	static final int DEPTH_3_NESTED_CHARS_PER_ELEMENT = 460;

	// --- Recommendation thresholds ---

	public static final int THRESHOLD_COMFORTABLE = 2000;
	public static final int THRESHOLD_LARGE = 8000;

	/**
	 * Item types for cost estimation.
	 */
	public enum ItemType {
		ELEMENT, VIEW, RELATIONSHIP
	}

	private CostEstimator() {
	}

	/**
	 * Estimates token count for a homogeneous result set.
	 *
	 * @param itemCount number of items in the result
	 * @param preset    the field preset used for the query
	 * @param itemType  the type of items in the result
	 * @return estimated token count
	 */
	public static int estimateTokens(int itemCount, FieldPreset preset, ItemType itemType) {
		if (itemCount <= 0) {
			return charsToTokens(ENVELOPE_OVERHEAD_CHARS);
		}
		int charsPerItem = getCharsPerItem(preset, itemType);
		int totalChars = (itemCount * charsPerItem) + ENVELOPE_OVERHEAD_CHARS;
		return charsToTokens(totalChars);
	}

	/**
	 * How many rows a get-view-contents response carries in each of its seven arrays.
	 *
	 * <p>Seven named counts rather than seven positional arguments, and a record rather than the
	 * response object itself. The record keeps the architecture boundary above -- this class still
	 * imports only the field preset, and knows nothing about the response DTO or the handler that
	 * builds it -- while making a forgotten array a compile error instead of a silent zero. That
	 * matters here more than it usually would: the defect this shape exists to prevent is exactly
	 * an array the estimate does not charge for.</p>
	 *
	 * <p><strong>Every count is the array's own size, never a multiple of another count.</strong>
	 * The element and relationship arrays are deduplicated by concept id and the visual arrays are
	 * not, so on a view that draws one element three times the node count is three where the
	 * element count is one.</p>
	 *
	 * <p><strong>An array the caller excluded is counted zero.</strong> Five of the seven can be
	 * dropped by name, and the tool recommends dropping two of them in its own dry-run
	 * suggestions. Passing zero is how the estimate follows that choice without this class having
	 * to learn what exclusion means.</p>
	 */
	public record ViewContentsCounts(
			int elementCount,
			int relationshipCount,
			int visualNodeCount,
			int connectionCount,
			int groupCount,
			int noteCount,
			int imageCount) {
	}

	/**
	 * Estimates token count for a get-view-contents dry run.
	 *
	 * <p>The response carries seven parallel arrays and this charges for all seven. Only the first
	 * two follow the caller's preset: the field selector runs the element and relationship rows
	 * through its preset-aware filter and emits the five visual arrays raw. So narrowing the preset
	 * narrows two terms and leaves five, and on a view whose visual arrays dominate -- which is
	 * most views, since the connection row alone is 2.5x an element row -- the saving a narrower
	 * preset buys is a small fraction of the whole.</p>
	 *
	 * <p><strong>What this deliberately does not model.</strong> Field-level exclusions
	 * ({@code documentation}, {@code properties}, {@code layer}, {@code type},
	 * {@code specialization}) narrow the element and relationship ROWS rather than removing an
	 * array, and a row width cannot be derived from a count. Modelling them would mean teaching
	 * this class the field selector's semantics, which the architecture boundary above rules out.
	 * They are left unmodelled knowingly: the effect is to over-report a caller who used one, which
	 * is the safe direction for a budget.</p>
	 *
	 * @param counts the rows the response will carry, per array
	 * @param preset the field preset used for the query
	 * @return estimated token count
	 */
	public static int estimateTokensForViewContents(ViewContentsCounts counts, FieldPreset preset) {
		int elementChars = counts.elementCount() * getCharsPerItem(preset, ItemType.ELEMENT);
		int relationshipChars = counts.relationshipCount() * getCharsPerItem(preset, ItemType.RELATIONSHIP);
		int visualChars = (counts.visualNodeCount() * VISUAL_NODE_CHARS)
				+ (counts.connectionCount() * VIEW_CONNECTION_CHARS)
				+ (counts.groupCount() * VIEW_GROUP_CHARS)
				+ (counts.noteCount() * VIEW_NOTE_CHARS)
				+ (counts.imageCount() * DIAGRAM_IMAGE_CHARS);
		int totalChars = elementChars + relationshipChars + visualChars + ENVELOPE_OVERHEAD_CHARS;
		return charsToTokens(totalChars);
	}

	/**
	 * Estimates token count for get-relationships traverse mode.
	 *
	 * <p>A traverse response is not a list of element rows and relationship rows, which is why it
	 * has its own estimate. The traversal engine assembles both halves itself: each discovered
	 * element arrives as a two-or-three field summary nested inside the hop relationship that
	 * reached it, never as the full element row. At the standard preset that is a summary of
	 * 105 chars against an element row of 250.</p>
	 *
	 * <p>Only the element half moves with the preset. The preset is still taken here rather than a
	 * flag, so this call site reads the same as its siblings and the caller does not have to
	 * re-derive which of its two halves the preset reaches.</p>
	 *
	 * @param elementCount      number of elements discovered
	 * @param relationshipCount number of hop relationships
	 * @param preset            the field preset used for the query
	 * @return estimated token count
	 */
	public static int estimateTokensForTraversal(int elementCount, int relationshipCount, FieldPreset preset) {
		int elementChars = elementCount * elementSummaryChars(preset);
		int relationshipChars = relationshipCount * HOP_RELATIONSHIP_CHARS;
		int totalChars = elementChars + relationshipChars + ENVELOPE_OVERHEAD_CHARS;
		return charsToTokens(totalChars);
	}

	/**
	 * Estimates token count for get-relationships depth mode based on the expansion
	 * depth.
	 *
	 * <p>At depth 0 the response is a flat relationship list filtered by the same field
	 * selection {@code search-relationships} applies, so the estimate delegates to
	 * {@link #estimateTokens(int, FieldPreset, ItemType)} for {@link ItemType#RELATIONSHIP}.</p>
	 *
	 * <p>At depth 1 and above the row is composed: a fixed relationship header the traversal
	 * handler assembles itself, plus the two elements embedded in it. Those elements <em>are</em>
	 * filtered by the caller's preset, at every depth, so the estimate follows it: {@code standard}
	 * and {@code full} select the same element fields and are therefore identical, while
	 * {@code minimal} is strictly narrower at all three depths. What each depth embeds differs --
	 * a two-or-three field summary at depth 1, the full element row at depth 2, and that row plus
	 * the element's own relationships at depth 3.</p>
	 *
	 * @param relationshipCount number of relationships
	 * @param depth             expansion depth (0-3)
	 * @param preset            the field preset used for the query
	 * @return estimated token count
	 */
	public static int estimateTokensForDepth(int relationshipCount, int depth, FieldPreset preset) {
		if (relationshipCount <= 0) {
			return charsToTokens(ENVELOPE_OVERHEAD_CHARS);
		}
		if (depth == 0) {
			return estimateTokens(relationshipCount, preset, ItemType.RELATIONSHIP);
		}
		int charsPerRelationship = EXPANDED_RELATIONSHIP_HEADER_CHARS
				+ (2 * charsPerEmbeddedElement(depth, preset));
		int totalChars = (relationshipCount * charsPerRelationship) + ENVELOPE_OVERHEAD_CHARS;
		return charsToTokens(totalChars);
	}

	/**
	 * What one embedded element costs at the given expansion depth. Depths outside 0-3 are not
	 * reachable: the traversal handler rejects them against its own minimum and maximum before it
	 * estimates. Should that ever stop being true, the default arm here falls to the widest branch
	 * rather than the narrowest, so a validation gap upstream would over-report rather than hand a
	 * caller a budget that is too small.
	 */
	private static int charsPerEmbeddedElement(int depth, FieldPreset preset) {
		return switch (depth) {
		case 1 -> elementSummaryChars(preset);
		case 2 -> getCharsPerItem(preset, ItemType.ELEMENT);
		default -> getCharsPerItem(preset, ItemType.ELEMENT) + DEPTH_3_NESTED_CHARS_PER_ELEMENT;
		};
	}

	/**
	 * The width of one embedded element summary.
	 *
	 * <p>The test against {@code MINIMAL} rather than a switch means an unset preset lands on the
	 * standard width here, but that is not a null-safety guarantee and must not be read as one:
	 * the depth-2 and depth-3 branches beside this one resolve their width through
	 * {@link #getCharsPerItem}, whose switch would reject a null. No shipped call site can pass
	 * one -- every dry-run caller resolves its preset before estimating -- so neither branch
	 * carries an unreachable arm to make the two agree.</p>
	 */
	private static int elementSummaryChars(FieldPreset preset) {
		return preset == FieldPreset.MINIMAL ? ELEMENT_SUMMARY_MINIMAL_CHARS
				: ELEMENT_SUMMARY_STANDARD_CHARS;
	}

	/**
	 * Recommends a field preset based on estimated tokens at standard preset.
	 *
	 * <p>Returns one of two values, not three. Both branches below the large-result threshold
	 * return {@code "standard"} -- they are separate because they read as the two distinct
	 * situations they describe, comfortable and merely large, and collapsing them would change no
	 * output. What it would change is this line, which used to promise a bare {@code "minimal"}
	 * that no input can produce.</p>
	 *
	 * @param estimatedTokensAtStandard token estimate calculated at STANDARD preset
	 * @return {@code "standard"}, or {@code "minimal with filters"} above the large-result
	 *         threshold
	 */
	public static String recommendPreset(int estimatedTokensAtStandard) {
		if (estimatedTokensAtStandard < THRESHOLD_COMFORTABLE) {
			return "standard";
		} else if (estimatedTokensAtStandard <= THRESHOLD_LARGE) {
			return "standard";
		} else {
			return "minimal with filters";
		}
	}

	/**
	 * Builds a human-readable recommendation string for the dry-run response.
	 *
	 * <p>The caller supplies {@code minimalTokens} rather than an item type because only the caller
	 * knows which estimator produced {@code estimatedTokens}. A projection computed here from an
	 * item type alone is on the right scale only for callers that estimated with
	 * {@link #estimateTokens(int, FieldPreset, ItemType)}; a caller that estimated with
	 * {@link #estimateTokensForDepth} or {@link #estimateTokensForViewContents} would have its saving computed
	 * against a different quantity entirely and could advertise a reduction that does not exist.
	 * Requiring the projection as an argument makes the two figures come from the same source.</p>
	 *
	 * <p>When the projection shows no saving, the suggestion to narrow the preset is omitted rather
	 * than published as a zero: an estimate that does not move with the preset has nothing to say
	 * about changing it.</p>
	 *
	 * @param estimatedCount  number of items in the result
	 * @param estimatedTokens estimated token count at the current preset
	 * @param currentPreset   the field preset used for estimation
	 * @param minimalTokens   the same estimate recomputed at the minimal preset, by the same
	 *                        estimator the caller used for {@code estimatedTokens}
	 * @return recommendation text with actionable suggestions
	 */
	public static String buildRecommendation(int estimatedCount, int estimatedTokens, FieldPreset currentPreset,
			int minimalTokens) {
		if (estimatedTokens < THRESHOLD_COMFORTABLE) {
			return String.format("Small result set (%d items, ~%d tokens at %s). Safe to execute as-is.", estimatedCount,
					estimatedTokens, currentPreset.value());
		} else if (estimatedTokens <= THRESHOLD_LARGE) {
			return String.format(
					"Medium result set (%d items, ~%d tokens at %s). Consider using limit parameter for paginated retrieval.",
					estimatedCount, estimatedTokens, currentPreset.value());
		} else {
			int savingsPercent = estimatedTokens > 0 ? (int) ((1.0 - (double) minimalTokens / estimatedTokens) * 100)
					: 0;
			if (savingsPercent <= 0) {
				return String.format(
						"Large result set (%d items, ~%d tokens at %s). Narrowing the field preset would not reduce this; consider adding type/layer filters to narrow results.",
						estimatedCount, estimatedTokens, currentPreset.value());
			}
			return String.format(
					"Large result set (%d items, ~%d tokens at %s). Consider using fields=minimal (~%d tokens, ~%d%% reduction) or adding type/layer filters to narrow results.",
					estimatedCount, estimatedTokens, currentPreset.value(), minimalTokens, savingsPercent);
		}
	}

	private static int getCharsPerItem(FieldPreset preset, ItemType itemType) {
		return switch (itemType) {
		case ELEMENT -> switch (preset) {
		case MINIMAL -> ELEMENT_MINIMAL_CHARS;
		case STANDARD -> ELEMENT_STANDARD_CHARS;
		case FULL -> ELEMENT_FULL_CHARS;
		};
		case VIEW -> switch (preset) {
		case MINIMAL -> VIEW_MINIMAL_CHARS;
		case STANDARD -> VIEW_STANDARD_CHARS;
		case FULL -> VIEW_FULL_CHARS;
		};
		case RELATIONSHIP -> switch (preset) {
		case MINIMAL -> RELATIONSHIP_MINIMAL_CHARS;
		case STANDARD -> RELATIONSHIP_STANDARD_CHARS;
		case FULL -> RELATIONSHIP_FULL_CHARS;
		};
		};
	}

	private static int charsToTokens(int chars) {
		return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
	}
}
