package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the layout-within-group tool.
 *
 * <p>{@code ancestorsResized} counts the enclosing groups whose size the recursive upward pass
 * actually changed; {@code resizedAncestors} says what each of them became. The count alone was the
 * defect: an agent that cannot see the canvas learns that three groups above the one it named
 * changed shape and has no way to learn their new rectangles, so it keeps planning against the ones
 * it last saw. The two always describe the same set — the list is the count with its geometry
 * restored — and both are observations: an already-fitted ancestor that the walk recomputes to the
 * same rectangle appears in neither.</p>
 *
 * <p>{@code ancestorPropagation} says <em>how the upward walk ended</em>, and ships on every
 * response. The count was the earlier defect and this is its other half: the same zero is produced
 * by five different situations that ask for different actions, and a caller that cannot see the
 * canvas could not tell them apart. It is deliberately not conditional on the count being zero,
 * because the count does not partition the reasons in either direction — {@link #PROPAGATED} is the
 * only value that guarantees a non-zero count, while
 * {@link #PROPAGATION_STOPPED_AT_NON_NATIVE_ANCESTOR} and {@link #PROPAGATION_DEPTH_CAP_REACHED}
 * can each arrive with zero or with a positive count. Those two are exactly the values a field
 * shown only beside a zero would have hidden, and they are the ones that leave a container above
 * the one you named too small for what the call just grew. See
 * {@link #ANCESTOR_PROPAGATION_REASONS} for the values and
 * {@code NestedLayoutOperations#propagateToAncestors} for the order they are decided in.</p>
 *
 * @param viewId               the view containing the group
 * @param groupViewObjectId    the group whose children were arranged
 * @param arrangement          the arrangement pattern used (row, column, grid)
 * @param elementsRepositioned number of child elements repositioned
 * @param groupResized         whether the group's size actually changed — an observation, not an
 *                             echo of the caller's autoResize request
 * @param newGroupWidth        new group width if resized, null otherwise
 * @param newGroupHeight       new group height if resized, null otherwise
 * @param overflow             true if child positions exceed current group bounds (when autoResize=false)
 * @param autoWidth            true if element widths were computed from label text
 * @param columnsUsed          number of columns used for grid arrangement, null for row/column
 * @param ancestorsResized     number of ancestor groups whose size actually changed
 * @param nestedContainersArranged number of nested descendant containers arranged (recursive descendant layout)
 * @param maxDepthReached      deepest nesting level arranged (0 = direct children only); recursive descendant layout
 * @param resizedAncestors     each ancestor group the recursive upward pass re-fitted, with the
 *                             rectangle it ended at; omitted from JSON when empty
 * @param nestedContainersFitted each DESCENDANT container the recursive downward pass re-fitted,
 *                             with the rectangle it ended at. The mirror of
 *                             {@code resizedAncestors}: that one says what happened above the
 *                             container you named, this one says what happened below it.
 *                             {@code nestedContainersArranged} counts the same set. The count alone
 *                             was the defect — a container two levels down is resized and moved
 *                             without ever being named in the request, so a caller that cannot see
 *                             the canvas is told that three containers changed shape and has no way
 *                             to learn their rectangles. Omitted from JSON when empty.
 * @param ancestorPropagation  how the upward walk ended, as {@code code: phrase} — always present,
 *                             never null. One of {@link #ANCESTOR_PROPAGATION_REASONS}.
 * @param resizedElements      each child the layout re-sized without descending into it, with the
 *                             rectangle it ended at. The third of the family, and the one that had
 *                             no field at all: both arms write a full rectangle to every child and
 *                             a grid cell takes its column's width, so a leaf sharing a column with
 *                             a container that fitted wide is stretched to that width — measured,
 *                             a 120px element landed at 2230px — while the response said only that
 *                             69 elements had been "repositioned". A count of moves that silently
 *                             also covers resizes is a report of neither. Disjoint from
 *                             {@code nestedContainersFitted} by construction: the two are the arms
 *                             of one if/else on whether the walk recursed into the child. The
 *                             membership rule is that if/else and not "is a leaf", so a container
 *                             left in place at the depth cap and then stretched by its column is
 *                             named here rather than nowhere. Like both siblings it is an
 *                             observation — a child re-written to the size it already had does not
 *                             appear — and, like both, it reports SIZE alone: a child that only
 *                             moved and kept its size is not in it. Omitted from JSON when empty.
 */
public record LayoutWithinGroupResultDto(
		String viewId,
		String groupViewObjectId,
		String arrangement,
		int elementsRepositioned,
		boolean groupResized,
		Integer newGroupWidth,
		Integer newGroupHeight,
		boolean overflow,
		boolean autoWidth,
		Integer columnsUsed,
		int ancestorsResized,
		int nestedContainersArranged,
		int maxDepthReached,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> resizedAncestors,
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		List<MovedViewObjectDto> nestedContainersFitted,
		String ancestorPropagation,
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		List<MovedViewObjectDto> resizedElements) {

	// ---- ancestorPropagation vocabulary --------------------------------------------------------
	//
	// STABILITY. These codes are published: an agent triages on the part before the colon, so once
	// a code has shipped it is never renamed, only superseded by a new one alongside it. The phrase
	// after the colon is prose and may be reworded. Each value is one line — a leading kebab-case
	// code, a colon, then a short phrase — capped at 90 characters, because this field ships on
	// every response and prose repeated per call is what makes a field unreadable.

	/** The caller did not set {@code recursive}. Outranks every other reason: it is undone by
	 *  changing the call rather than the view. */
	public static final String PROPAGATION_NOT_REQUESTED =
			"not-requested: recursive was not set, so no ancestor was consulted";

	/** {@code recursive} was set but {@code autoResize} was not, so the walk was gated out before
	 *  any geometry was consulted. */
	public static final String PROPAGATION_AUTO_RESIZE_NOT_REQUESTED =
			"auto-resize-not-requested: recursive propagates only when autoResize is also set";

	/** The container named in the request is an ArchiMate element, so the walk never started. */
	public static final String PROPAGATION_CONTAINER_NOT_A_NATIVE_GROUP =
			"container-not-a-native-group: the walk starts only from a view group, not an element";

	/** The container is a view group at the top level of the view. Terminal — nothing is above it. */
	public static final String PROPAGATION_NO_ANCESTOR =
			"no-ancestor: the container is at the top level of the view, nothing sits above it";

	/** The walk ran and ended by meeting a parent it cannot grow. Emitted whatever
	 *  {@code ancestorsResized} is, including when it is greater than zero. */
	public static final String PROPAGATION_STOPPED_AT_NON_NATIVE_ANCESTOR =
			"stopped-at-non-native-ancestor: an element parent halted the walk, re-run naming it";

	/** The walk covered at least one enclosing group and changed none of them. Terminal. */
	public static final String PROPAGATION_ALL_ANCESTORS_ALREADY_FITTED =
			"all-ancestors-already-fitted: every group above it was the right size already";

	/** The walk stopped at its fixed nesting limit with enclosing groups still above the last one
	 *  it re-fitted. Neither terminal code is true here, so it takes its own. */
	public static final String PROPAGATION_DEPTH_CAP_REACHED =
			"depth-cap-reached: the walk stopped at its nesting limit with groups still above";

	/** The ordinary success: the walk re-fitted at least one enclosing group and reached the view. */
	public static final String PROPAGATED =
			"propagated: every group above it was re-fitted up to the view";

	/** Every value {@code ancestorPropagation} can take. */
	public static final List<String> ANCESTOR_PROPAGATION_REASONS = List.of(
			PROPAGATION_NOT_REQUESTED,
			PROPAGATION_AUTO_RESIZE_NOT_REQUESTED,
			PROPAGATION_CONTAINER_NOT_A_NATIVE_GROUP,
			PROPAGATION_NO_ANCESTOR,
			PROPAGATION_STOPPED_AT_NON_NATIVE_ANCESTOR,
			PROPAGATION_ALL_ANCESTORS_ALREADY_FITTED,
			PROPAGATION_DEPTH_CAP_REACHED,
			PROPAGATED);

	/**
	 * Constructor matching the prior 13-field shape. Delegates with an empty
	 * {@code resizedAncestors}, which is omitted from JSON — so a call that re-fitted no ancestor
	 * still names no ancestor on the wire.
	 *
	 * <p>This no longer serializes byte-identically to the pre-{@code resizedAncestors} shape:
	 * {@code ancestorPropagation} is always present by design, and saying otherwise here would
	 * publish a claim the field deliberately breaks.</p>
	 */
	public LayoutWithinGroupResultDto(
			String viewId, String groupViewObjectId, String arrangement,
			int elementsRepositioned, boolean groupResized,
			Integer newGroupWidth, Integer newGroupHeight,
			boolean overflow, boolean autoWidth, Integer columnsUsed,
			int ancestorsResized, int nestedContainersArranged, int maxDepthReached) {
		this(viewId, groupViewObjectId, arrangement, elementsRepositioned, groupResized,
				newGroupWidth, newGroupHeight, overflow, autoWidth, columnsUsed,
				ancestorsResized, nestedContainersArranged, maxDepthReached, List.of());
	}

	/**
	 * Constructor matching the 14-field shape that carried only {@code resizedAncestors}. Delegates
	 * with an empty descendant list, which is likewise omitted from JSON.
	 */
	public LayoutWithinGroupResultDto(
			String viewId, String groupViewObjectId, String arrangement,
			int elementsRepositioned, boolean groupResized,
			Integer newGroupWidth, Integer newGroupHeight,
			boolean overflow, boolean autoWidth, Integer columnsUsed,
			int ancestorsResized, int nestedContainersArranged, int maxDepthReached,
			List<MovedViewObjectDto> resizedAncestors) {
		this(viewId, groupViewObjectId, arrangement, elementsRepositioned, groupResized,
				newGroupWidth, newGroupHeight, overflow, autoWidth, columnsUsed,
				ancestorsResized, nestedContainersArranged, maxDepthReached,
				resizedAncestors, List.of());
	}

	/**
	 * Constructor matching the 15-field shape that carried both the ancestor and the descendant
	 * list. Delegates with {@link #PROPAGATION_NOT_REQUESTED}.
	 *
	 * <p>That is the value that is <em>true</em> for all three of these shorter shapes, not a
	 * placeholder chosen to fill the slot: each of them predates the upward walk reporting anything,
	 * so no walk was consulted on a call built through them, and the caller's own argument is the
	 * terminal state — the same footing on which {@code not-requested} outranks every geometric code
	 * when the walk really is skipped. Leaving the field absent instead would be a false all-clear
	 * beside a published list of values it can take.</p>
	 */
	public LayoutWithinGroupResultDto(
			String viewId, String groupViewObjectId, String arrangement,
			int elementsRepositioned, boolean groupResized,
			Integer newGroupWidth, Integer newGroupHeight,
			boolean overflow, boolean autoWidth, Integer columnsUsed,
			int ancestorsResized, int nestedContainersArranged, int maxDepthReached,
			List<MovedViewObjectDto> resizedAncestors,
			List<MovedViewObjectDto> nestedContainersFitted) {
		this(viewId, groupViewObjectId, arrangement, elementsRepositioned, groupResized,
				newGroupWidth, newGroupHeight, overflow, autoWidth, columnsUsed,
				ancestorsResized, nestedContainersArranged, maxDepthReached,
				resizedAncestors, nestedContainersFitted, PROPAGATION_NOT_REQUESTED);
	}

	/**
	 * Constructor matching the 16-field shape that carried the two lists and the propagation
	 * reason. Delegates with an empty {@code resizedElements}, which is omitted from JSON — so a
	 * call built through it names no resized leaf, exactly as it did before the field existed.
	 */
	public LayoutWithinGroupResultDto(
			String viewId, String groupViewObjectId, String arrangement,
			int elementsRepositioned, boolean groupResized,
			Integer newGroupWidth, Integer newGroupHeight,
			boolean overflow, boolean autoWidth, Integer columnsUsed,
			int ancestorsResized, int nestedContainersArranged, int maxDepthReached,
			List<MovedViewObjectDto> resizedAncestors,
			List<MovedViewObjectDto> nestedContainersFitted,
			String ancestorPropagation) {
		this(viewId, groupViewObjectId, arrangement, elementsRepositioned, groupResized,
				newGroupWidth, newGroupHeight, overflow, autoWidth, columnsUsed,
				ancestorsResized, nestedContainersArranged, maxDepthReached,
				resizedAncestors, nestedContainersFitted, ancestorPropagation, List.of());
	}

	/**
	 * Lists are never null: the canonical constructor normalizes null to empty.
	 * {@code ancestorPropagation} is normalized the same way for the same reason — the field is
	 * documented as always present, and a null reaching the wire would be an omission where the
	 * contract promises a value.
	 */
	public LayoutWithinGroupResultDto {
		resizedAncestors = resizedAncestors != null ? resizedAncestors : List.of();
		nestedContainersFitted = nestedContainersFitted != null
				? nestedContainersFitted : List.of();
		ancestorPropagation = ancestorPropagation != null
				? ancestorPropagation : PROPAGATION_NOT_REQUESTED;
		resizedElements = resizedElements != null ? resizedElements : List.of();
	}
}
