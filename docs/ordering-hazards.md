# Ordering Hazards

An **ordering hazard** is a caveat whose remedy is a change in *sequence*: "call X before Y", or
"call X only after Y". This document states where such a caveat must live, lists the ones this
server currently carries, and is backed by a build-fired guard so the list and the tool descriptions
cannot drift apart.

## The placement rule

> **Every ordering hazard must be stated in the description of the tool that is unsafe to call at
> the wrong time, in the imperative, naming the operation it must follow or precede. Reference
> pages, recipes and checklists MAY repeat it; they MAY NOT be its only home.**

The reason is how an agent actually reads this server. It reads exactly one tool description — the
one for the call it is about to make — and fetches anything else only on a guess that the other
document is relevant. A hazard filed in a reference page is therefore found only by an agent that
already suspected it, and the hazards that bite are precisely the ones nobody suspected. Guidance
filed away from the call site is guidance the reader never reaches while they can still act on it.

## What is, and is not, an ordering hazard

A caveat earns a row here only when its remedy is a change in **sequence**. A caveat whose remedy is
a different *parameter*, a different *tool*, or a different *value* is a real caveat and belongs in
the tool's description like any other, but it is not an ordering hazard and does not earn a row.

Without that bound the list has no floor and grows to hold every caveat on the surface, at which
point it stops distinguishing anything.

## The scope test

A tool is **in scope** for ordering-hazard review if it:

- **(a)** mutates geometry — writes x/y/width/height, or bendpoints, on a view; **or**
- **(b)** reads geometry to produce a judgement — a rating, a ranking, a detection, a rendering; **or**
- **(c)** mutates structure in a way that invalidates a geometry read.

Clauses (b) and (c) are load-bearing, not decoration: two of the hazards below are about *when a
read is valid*, and one is reachable only through a structural change. A tool that fails all three
clauses is out of scope — it cannot be called at the wrong time in a way that costs geometry.

Where a clause reading was arguable, the tool was ruled **in**. The cost of an in-scope tool with no
hazard is one line of review; the cost of an out-of-scope tool that had a hazard is the defect this
document exists to prevent.

## The registry

The **terminating action** column carries the hazard's machine probe. Every span written in
`backticks` in that column must appear verbatim in the **served surface** of every tool named in the
**owning tool description** column — the tool's own description *and* its input-schema parameter
descriptions, because an agent reads both in the same breath and one of the hazards below belongs on
a parameter. `OrderingHazardPlacementParityTest` enforces exactly that, off this file, and fails the
build when a row names a tool whose surface does not carry it.

A row's spans are **ANDed**: it passes only when every one of them is present. That is what lets a
row pair a broad span with a narrow one — rows 4 and 6 do, and it is the narrow span that carries
them. **At least one span per row must be specific enough that a bare cross-reference in a tool's
*Related:* list cannot satisfy it.** Measured while this guard was written: the bare tool name
`auto-route-connections` already appeared in four owners' *Related:* lists while none of them stated
the hazard, so a row probed only on the bare name would have passed on descriptions that said
nothing.

| Hazard | Owning tool description | Trigger condition, in the reader's terms | Terminating action |
|---|---|---|---|
| A hub element is enlarged after its connections have been routed, so the router never saw the larger perimeter it needed and the extra attachment faces go unused. | `detect-hub-elements`, `update-view-object`, `resize-elements-to-fit` | The view has an element carrying five or more connections, and you are about to size it. | Size the hub `before auto-route-connections`. |
| A note, view-reference or image placed before routing sits in a corridor the router does not treat as an obstacle, so the route is drawn straight through it. | `add-note-to-view`, `add-view-reference-to-view`, `add-image-to-view` | You are about to place an annotation on a view whose connections are not yet routed. | Place the annotation `after auto-route-connections`, then re-run `assess-layout`. |
| A spacing control loop handed an unrouted view scores its own reroute pass against a bare baseline, refuses to enter its loop, and applies no spacing at all. | `apply-element-spacing-recommendations`, `apply-group-spacing-recommendations`, `apply-spacing-recommendations` | The view's connections carry no stored routing — straight from placement, or from `auto-connect-view`. | Call the spacing tool `after auto-route-connections`; on an unrouted view the pre-loop check reports `reroute_degraded_input_baseline` and nothing is applied. |
| Geometry written after a route leaves the stored bendpoints anchored to bounds that have since moved, and the drawn polyline shears away from the stored one. | `apply-positions`, `update-view-object` | You reposition or resize a view object on a view that has already been routed. | Re-run `auto-route-connections`, then confirm `anchorDriftCount` is back to zero. |
| `arrangement: 'grid'` advances every row by the tallest element in the whole grid, not by the tallest in that row, so a short row is followed by empty space and the container is fitted around it — and everything positioned afterwards is laid out against a canvas that is taller than the content needs, which is undone only by going back. | `layout-within-group` | You are choosing `columns` on a container whose children differ in height. | Lay a single-column inventory out with `arrangement: 'column'`, which writes each element its own width and advances each row by that element's own height. |
| Remediation is aimed at a metric the rating has already excluded: the de-noised headline agrees with the headline, the reader takes that as the terminals still capping the rating, and iterates on terminals that cannot move it while the metric that is capping it sits unread in the same response. | `assess-layout` | You have read the rating, the two headline values agree, and you are deciding what to change next. | The terminals are not the cause: read the rest of `ratingBreakdown` and act on whichever metric is `still below pass`. |

### Why rows 5 and 6 are admitted

Both rows look at first like the parameter-or-value caveats the bound above refuses. The judgement
is recorded here so a reader does not have to re-litigate it, and so that a reader who disagrees can
see exactly what they are disagreeing with.

**Row 5** reads like a parameter caveat and is not one. Its remedy names a different value, but the
cost is not paid by the call that chose the grid — it is paid by everything positioned afterwards
against a canvas taller than the content needs. No later call undoes that; only going back does. The
caveat therefore has to reach the reader *before* the band is laid out, which makes it a sequence.

**Row 6** is a hazard about when a **read** is valid rather than when a **write** is safe — scope-test
clause (b), which is in the test for exactly this case. The sequence is: read the rating, conclude,
act. A conclusion drawn at the wrong point in it sends the next several calls at a metric the rating
has already excluded, and the terminating action is what ends the sequence rather than repeating it.

## Considered and not admitted

**A relationship whose endpoint is a populated container.** A relationship terminating on a
container that already holds nested children fails the crossing check, because the line must cross
the container's own children to reach it. This is a real defect and it has no documented home
anywhere — but no call sequence avoids it, and no tool description is the wrong place for it,
because the guidance does not exist to be misplaced. It is a design gap, not a placement failure,
and it needs a remedy of its own rather than a row here.

## Adding a hazard

1. Confirm the remedy is a change in sequence, not a parameter, tool or value.
2. Add a row here, naming every tool whose description is unsafe without it.
3. Run the guard **before** you edit any description and watch it fail on the new row. A probe you
   never saw fail is a probe that cannot fail, and a row guarded by one is a row guarded by nothing.
4. State the hazard in each of those descriptions, in the imperative, naming the operation it must
   follow or precede — and carrying the row's probe spans verbatim.
5. Raise the guard's row floor in the same commit.

Repeating the hazard in a reference page or a recipe afterwards is welcome. Repeating it *instead*
is the defect.

---

**See also:** [Extension Guide](extension-guide.md) | [Layout Engine](layout-engine.md) | [Routing Pipeline](routing-pipeline.md)
