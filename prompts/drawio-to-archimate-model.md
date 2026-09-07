# Prompt — Replicate a draw.io architecture diagram as an ArchiMate model

<!-- Self-contained. Paste into an agent session with the Archi MCP Server tools connected.
     Fill the {{PLACEHOLDER}} tokens and run. -->

---

## ROLE

You are an enterprise architect fluent in ArchiMate 3.2 and in the mxGraph file format that draw.io writes. You are converting a *picture* into a *model*. Those are different things, and the gap between them is where every failure of this prompt lives.

## INPUT

- **`{{DRAWIO_FILE}}`** — the source diagram, as either a **path** to a `.drawio` (or `.drawio.xml`, or an `.xml` mxGraph export) file, **or the mxGraph XML pasted directly**. **Required.** Pasted content is treated identically to a file once read — every trap, gate and count below operates on the XML, not on where it came from. Say in the report which form you received, and for a path, the path; for pasted content, its length and where the caller said it came from. A diagram extracted from a wiki page or an issue tracker arrives pasted or as fetched bytes and has no path, which is the normal case for that route rather than a degraded one. **If the source is a Confluence page with the diagram embedded in it, read *Sourcing the inputs from a Confluence page* before you fetch anything** — the diagram is an attachment named by the macro, a page usually holds several, and picking the wrong one is silent.
- **`{{COMPANION_TEXT}}`** — path to, or pasted body of, the document the diagram came from: a design doc, an HLD, a solution description. **Optional but high-value** — see *Companion text*. Leave empty if you have none. From a wiki page, take the **rendered/markdown** body rather than the storage format, and strip the flattened diagram-macro noise first — *Sourcing the inputs from a Confluence page* says why that noise corrupts the corroboration test.
- **`{{ICON_PACKAGES}}`** — where to find the vendor icon sets. **Optional, and a list — one entry per vendor.** Each entry is either a **local path** to an unpacked package (for AWS, the directory holding the `Arch_<Category>/` folders), or a **URL to the vendor's icon-package archive or its download page** — the publisher's own, not a mirror. Fetch each URL, unpack it to a working directory, and proceed exactly as for a local path. Say in the report which URLs you fetched and which package version each resolved to; vendors date-stamp these and two runs a year apart will not get the same artwork.

  **One diagram routinely needs more than one package**, and assuming otherwise silently drops every icon from the second vendor. A hybrid drawing carries AWS *and* Azure stencils; even a single-cloud drawing usually carries the generic draw.io families beside the vendor's — this prompt's own specimen mixes `mxgraph.aws3.*`, `mxgraph.aws4.*` and `mxgraph.networks.*`. Supply as many entries as the diagram has vendors.

  If empty, search locally before concluding there is none — the packages nest, so search **at least three levels deep** under the usual download locations, matching on `Architecture-Service-Icons*`, `Asset-Package*` and `Icon-package*` for AWS and the equivalent published names for other vendors. **Report the paths you searched, not merely the conclusion:** *"no icons available"* is a claim, and on a host where a package is present it is a wrong one that costs every icon on the diagram.

  **Route each token to a package by its vendor family, and search only that package.** The token's second segment names the vendor — `mxgraph.aws4.lambda` is AWS, `mxgraph.azure.virtual_machine` is Azure, `mxgraph.networks.*` is the generic family and usually has no vendor package at all. Match a token **only against the package for its own vendor**. Cross-vendor matching is how an Azure glyph lands on an AWS element: the fuzzy match compares on residual words after the vendor prefixes are stripped, which is exactly the step that makes two vendors' service names collide (`gateway`, `firewall`, `container registry`). A token whose vendor has no supplied package goes in the not-found list unchanged — it is not an invitation to try the packages you do have.

  **Take each package whole from its publisher. Never web-search for an individual icon.** A vendor package is a single verifiable provenance: every glyph in it is the real artwork, correctly versioned, under one licence, named to a scheme the token match already expects. A per-icon search is a fresh gamble on every element, and its losing outcomes — a third-party recreation, a superseded logo revision, a watermarked stock image, another company's mark entirely — are **indistinguishable from a win in the response you get back**. Two rules in *Carrying the stencil icon onto the element* make this decisive rather than merely cautious: the archive write is **permanent** and has no removal API, and a wrong logo *"looks deliberate"*, so it misleads every future reader more thoroughly than a blank corner ever could. Where no package is available for a vendor, **place nothing for that vendor's tokens, and say so prominently** — an element without an icon is honest, and a report that names the gap lets a human supply the package and re-run.
- **`{{MODE}}`** — `semantic` (default), `mirror`, or `both`. See *Two modes*.
- **`{{VIEW_BASE_NAME}}`** — base name for the view(s) to create. It names **views, not the model** — the open model is already named and this prompt never renames it. **Defaults to the source file's stem** (`Testing generic.drawio` ⇒ `Testing generic`), *not* to the diagram's page name: draw.io's default page name is `Page-1`, so defaulting to it makes every run of every un-renamed file produce identically-named views. The stem default is the right one for an ordinary file. **It is the one case of a wiki-sourced diagram that breaks it** — those filenames are bare timestamps — and *Sourcing the inputs from a Confluence page* says what to use instead there. In `both` mode the two views are named `{{VIEW_BASE_NAME}} — semantic` and `{{VIEW_BASE_NAME}} — mirror`.

  **If the open model already holds a view with the name you are about to create, stop and ask** — this is the ordinary outcome of running this prompt twice into the same model, and all three plausible reactions are wrong to pick silently. Overwriting destroys a previous run's work; adding a numbered suffix leaves two near-identical views nobody can tell apart later; merging into the existing view mixes two runs' provenance in one place. Name the colliding views, say which run produced them if the model records it, and let the human choose. **If the run is non-interactive**, create the new views with a ` (run 2)`-style suffix — never overwrite — and report the collision prominently as the first line of §8.

## OBJECTIVE

Produce, in the open Archi model: the elements, relationships and the view (or, in `both` mode, the two views) that the diagram depicts — every concept marked with how much of it was **read** versus **guessed**, and a final report that says plainly what could not be recovered.

A draw.io diagram is a picture of a model that lived in someone's head. Your job is to reconstruct as much of that model as the evidence supports, and to be conspicuously honest about the rest. **A model that silently guesses looks exactly like a model that knew.** The provenance marking is what stops those being confused, and it is not optional.

---

## TWO MODES

| | **`semantic`** (default) | **`mirror`** |
|---|---|---|
| Goal | All elements and relationships present; layout from the ArchiMate recipes | The view visually resembles the source diagram |
| Coordinates | Discarded after structure is derived | Preserved, normalised, scaled |
| Layout | `auto-layout-and-route` / `layout-within-group` / `arrange-groups` | `apply-positions` with derived geometry |
| Confidence | High | Medium — see the honesty clause below |

**Run `semantic` unless the user asked for `mirror` or `both`.** The semantic mode does the hard work — parsing, containment, identity, relationship inference — and the mirror mode is that same extraction with a different final layout stage. Both share Phases 0–5.

**The `mirror` honesty clause (gate G13).** draw.io icons are typically 30×30–80×80 with the label rendered *outside* the shape. ArchiMate elements are ~120×55 with the label *inside*. A 1:1 geometry copy therefore produces systematic label overflow. `mirror` mode delivers a view that is **recognisably the same diagram**, not a pixel-faithful copy, and the final report must say so in those words. Do not let a user believe otherwise.

### `both` — two views over one set of elements

`both` runs Phases 0–5 exactly once and then builds **two views from the same model content**. This is the point of the mode and the constraint that makes it safe:

- **Create the elements and relationships once.** The two views are two pictures of one model. Placing an element on a second view is `add-to-view` against the id you already have — it is **not** a second `create-element`. Duplicating model content to fill a second view is invention (G7) and doubles the model for nothing.
- **Build them in order: semantic first, then mirror.** The semantic pass is the one whose layout you will iterate on; getting it finished before the mirror geometry starts keeps the two from interleaving. Each view is completed — placed, laid out, `assess-layout`, exported and eyeballed — before the next is begun.
- **G12 is per view, not per run.** The rule "no view-mutating call follows that export" binds within a single view: once you have exported and accepted the semantic view, no further call may mutate *that* view. Building the mirror view afterwards is not a violation, because it touches a different view. If you go back and change the semantic view, you owe it a fresh export.
- **G13 fires on the mirror view.** The fidelity statement is owed whenever a mirror view exists, `both` included.
- **The counts in the report are model-level and stated once** — elements, relationships, provenance, tokens. Only the layout, verification and mode-limit sections are stated per view.

A `both` run costs roughly one semantic run plus the mirror layout stage, not two runs: Phases 0–5 are the expensive half and they happen once.

---

## HOW TO READ THIS PROMPT — GATES vs GUIDANCE

Two kinds of rule, and a long run under cost pressure blurs them:

- **GUIDANCE** (the default, the bulk of this prompt) — judgment under the principles. Adapt it; a logged, reasoned deviation is correct. Guidance hedges on purpose ("prefer", "usually", "where it helps") — those words signal room to think.
- **GATE** — a hard pass/fail check on the artifact, stated **without hedge-words**. If a gate's condition is unmet the draft is **not ready**: fix it, or record a named explicit exception in the final report (which gate, which element, why). "I ran low on budget" is not an exception.

**Gate manifest.** Each gate is *defined* in full where cited; this table is the index.

| # | Gate | Pass condition (falsifiable) | Defined in |
|---|---|---|---|
| **G1** | Read-only until build | No mutating call before Phase 5. Parsing, containment, identity and mapping all complete first. | Phases 0–4 |
| **G2** | Model open | `get-model-info` confirms an open model, or the run **stops**. | Phase 0 |
| **G3** | Wrapper cells recovered | Every `<object>` / `<UserObject>` wrapper is read for its `id` and `label`. The count of recovered wrappers appears in the report. | *Parsing contract* trap 1 |
| **G4** | Edge labels recovered | Every child vertex of an edge is read as that edge's label. Count in the report. | *Parsing contract* trap 2 |
| **G5** | Containment computed geometrically | Every vertex has an inferred visual parent from smallest-enclosing-rectangle, not from its declared `parent`. The count of vertices where the two disagree appears in the report, **with the population size**, computed by trap 3's stated definition. | *Parsing contract* trap 3 |
| **G6** | Every vertex dispositioned | Each parsed vertex resolves to **one of seven**: created · merged-into-another · dropped as annotation · dropped as decoration · **consumed as a container title** · **consumed as an edge label (relationship name)** · **rendered as a view-only group with no model element** — none silently lost. **Counts sum to the parsed vertex total, and that total is every `vertex="1"` cell in the file — not the subset carrying a real rectangle.** A cell with no width and height is still a parsed vertex and still needs a bucket: draw.io stores an **edge label as a child vertex of the edge**, with `style=edgeLabel` and a `relative` geometry, so a file with labelled arrows has vertices that no geometric population contains. **Narrowing the denominator to the rectangle-bearing population is not a way to pass this gate** — it is the failure the gate exists to catch, and it will look like a clean sum. State the parsed total, state the bucket counts, and show them summing to it. | *Completeness* |
| **G7** | No invention | No element on the canvas that is not traceable to a parsed cell or an explicit companion-text statement. Prose-only elements are **reported, never created**. | *Companion text* |
| **G8** | Provenance on every element | Every element carries `provenance` + `provenanceMark` + `source`, plus the `Provenance:` documentation line. Grade is weakest-link. | *Provenance marking* |
| **G9** | Glyph on every placed object | Every element view-object carries the `labelExpression` glyph (or the ASCII fallback on a genuine render failure). | *Provenance marking* ch.4 |
| **G10** | No unresolved relationship errors | Zero `RELATIONSHIP_NOT_ALLOWED` reaches the user. Every one is avoided by the ladder or absorbed by the retry. | *Relationship inference* |
| **G11** | Notes auto-fit, and are placed **last** | Every note is created, and every edit to its `text` or `width` is made, **without** a `height` parameter, so the server fits it and text never clips. **And annotation is the FINAL action on a view** — no `layout-within-group`, `arrange-groups`, `auto-route-connections`, resize or `add-to-view` may follow a note/legend/image on that view. This is a *build-time* invariant, not only a Phase-8 check: without it a note placed after G12's close-out export violates G12 by construction, and a later layout pass grows a container under a note that was correctly positioned when it was stamped. **If a later geometry change proves unavoidable, this gate is not thereby broken — remove or re-place the annotation *after* that change, never before, then re-arm G12 (re-assess + re-export)**; see *Annotations*. A fix that follows a note and leaves the note where it was is the violation, not the fix itself. | *Annotations* |
| **G12** | Terminal close-out | Every pass that adds/moves/resizes/styles a view object ends with `assess-layout`, then an eyeballed `export-view` PNG. **No view-mutating call follows that export, on that view.** The render is authoritative. Applies per view: in `both` mode each view is closed out separately. | Phase 8 |
| **G13** | Mirror declares its limit | Wherever a mirror view exists (`mirror` or `both`) the report states that the result is recognisably-the-same, not pixel-faithful. | *Two modes* |
| **G14** | Report is complete | Every count in *Final report* is present. A section with nothing to report says "none", never nothing. | *Final report* |
| **G15** | Endpoint recovery attempted | Every edge carrying no `source`/`target` id has geometric endpoint recovery attempted **before** it may be dropped. Each is reported as recovered (naming both ends) or unrecoverable (naming which end failed). | *Parsing contract* trap 5 |
| **G16** | Every text cell dispositioned | Each free-text cell resolves to container title / relationship name / view note / dropped-and-reported — none silently lost. | *Container semantics*, *Annotations* |
| **G17** | No silent orphans | Every created element holds **at least one relationship**, or is listed in the report as a deliberate isolate with the reason. Verify by querying the model after the build — not from your own intent. | *Container semantics* |

Everything not in this table is guidance.

---

## METHOD — work in phases. Do not skip the preflight or the verify.

### Phase 0 — Preflight (read-only)

1. `get-model-info`. No open model ⇒ **stop** and say so (G2).
2. Obtain `{{DRAWIO_FILE}}` — read the path, or take the pasted XML. Not found, or not mxGraph at all ⇒ stop. **Then run the compressed-payload check below (*Parsing contract* → *Before trap 1*) before parsing anything.** A compressed diagram is valid mxGraph that no amount of careful parsing can read, and it fails in a way that mimics an empty drawing. *(Only if the diagram is embedded in a Confluence page rather than handed to you as a file or as XML: do *Sourcing the inputs from a Confluence page* before this step — locating and selecting the right diagram happens first. Otherwise that section does not apply and you can skip it.)*
3. `get-guidance` with no arguments, then read `archimate://recipes/index`. If the diagram is infrastructure-shaped (the common case), read `archimate://recipes/technology-deployment` — it is the target topology for this work.

### Phase 1 — Parse the file (read-only)

Apply the *Parsing contract* below in full. All five traps. Produce an internal inventory: vertices, edges, shape tokens, labels, geometry, declared parents.

**Report the counts to yourself before continuing.** If your vertex count looks suspiciously round, or your edge-label count is zero, you have probably hit trap 1 or trap 2.

### Phase 2 — Reconstruct structure (read-only)

1. Compute absolute rectangles, then the visual containment tree (trap 3).
2. Apply *Identity resolution* to collapse duplicates.
3. Classify every vertex: **element** / **container** / **annotation** / **decoration**.
4. Classify every edge on its **endpoints**, never on its style: **structural** (ids read, or endpoints recovered per trap 5) / **dangling** (recovery failed) / **duplicate**.

### Phase 3 — Read the companion text (read-only)

If `{{COMPANION_TEXT}}` is supplied, read it now — *after* the picture, never before. You want the picture's structure fixed in your head so the prose refines it rather than replaces it. Apply the *Companion text* precedence contract.

If it is absent, note that the run is diagram-only and expect the `assumed` count to dominate.

### Phase 3.5 — Confirm the open rulings (once, first run only)

Two questions this prompt cannot answer from the file. Ask them **together, once**, then proceed on the stated defaults if the user does not answer.

**In a non-interactive run there is no one to ask, and that is not a blocker.** If you have no channel to a human — a headless, scripted or agent-to-agent run — do **not** stall and do **not** invent an answer: take both defaults immediately, and record in the report (§8) that the questions were unasked *because the run was non-interactive*, together with what you would have asked. An unasked question recorded as such is a finding a human can act on later; a silently-defaulted one is invisible.

1. **Unnamed containers.** Run the title-adjacency test (*Container semantics*) first — it resolves most of these from the file. For any unlabelled rectangle still enclosing real content that neither the test nor the companion text names: list them with their contents and ask. *Default: render as a view-only group; create no model element.* Pass `label: ""` so the group is genuinely untitled — see the note below.
2. **Unresolved identity collisions.** List any same-label, same-token, same-container groups you could not split. *Default: treat as distinct, and report.*

Do not ask about anything else. Every other decision in this prompt has a ruling.

### Phase 4 — Map to ArchiMate (read-only)

Apply *Container semantics*, then *Shape-token mapping*, then *Relationship inference*. Decide every element's `(type, specialization)` and every relationship's type **before** you write anything (G1).

**Then, and only then, re-open the near-misses** (*Container semantics* → *Near-miss containment*) — **both arms**: the shapes that fell out of the containment tree, *and* the shapes that landed in it one level too high. This step is here rather than in Phase 2 on purpose: containment is computed from geometry alone before any type is known, so a shape that fell out of the tree — or into the wrong part of it — could not be judged on **what it is** at the moment it was placed. By this point you know. Re-run it with that knowledge.

### Phase 5 — Create model content

Order: folders → specializations → elements → relationships.

Use `bulk-mutate` or `begin-batch`/`end-batch` for volume. Set `provenance`, `provenanceMark`, `source` and the `documentation` line at create time — not as a later pass, which is where they get forgotten.

### Phase 6 — Build the view

`create-view`, then place elements outermost-container-first so `parentViewObjectId` is always available for the next level down. Respect the nesting cap (*Container semantics*).

**In `both` mode, Phases 6–8 run twice** — semantic view first, complete through its export, then the mirror view. The second pass places the **same element ids** on a new view; it creates nothing. Re-query view-object ids from `get-view-contents` for each view separately: a view-object id belongs to one view, and reusing the semantic view's ids against the mirror view is a class of error the tools cannot catch for you.

### Phase 7 — Lay out and route

- **`semantic` mode:** read `archimate://prompts/routing-preconditions-checklist` and satisfy its three preconditions *before* routing. Then `layout-within-group` (with `recursiveChildren: true` on the outermost containers), `arrange-groups`, and `auto-layout-and-route`.
- **`mirror` mode:** apply derived geometry via `apply-positions` / `update-view-object`. See *Mirror-mode geometry*.

  **On `layout-within-group`, `recursiveChildren` and `recursive` are not symmetric, and the outermost containers here are exactly where that bites.** `recursiveChildren: true` descends into any container. `recursive: true` walks *upward* through native view groups only — and the decision table types most containers here as `Grouping` or `Node`, which are ArchiMate elements. So on those containers the upward pass **does not run**, and a container you grew can be left overflowing its parent. Do not read `ancestorsResized: 0` as "nothing needed doing": **read `ancestorPropagation`**, which is on every response. Three codes mean **work is left undone**: `container-not-a-native-group` (the pass never started — re-call on an enclosing native group, or fit the parent with `resize-elements-to-fit`), `stopped-at-non-native-ancestor` (it ran and then stopped at a `Grouping` parent that is now too small — re-call on *that* parent), and `depth-cap-reached` (it exhausted its nesting budget with groups still above — re-call higher up). **The last two can arrive with `ancestorsResized` above zero, so the count will look like success on exactly the calls that are not finished.** The other five need nothing: `not-requested` and `auto-resize-not-requested` are your own call, and `no-ancestor`, `all-ancestors-already-fitted` and `propagated` are terminal.
- **`both` mode:** the semantic recipe on the semantic view, the mirror recipe on the mirror view. Do not run the semantic layout tools over the mirror view — `auto-layout-and-route` will discard exactly the geometry the mirror view exists to carry.

**`arrange-groups` positions top-level `Grouping` elements as well as native view groups**, so a view built from the decision table — where most containers are `Grouping` — is arranged by it. `get-view-contents` `format=tree` counts the same containers: its `topLevelGroups` equals `arrange-groups`' `groupsPositioned` **when `groupIds` is omitted**, `Grouping` elements included, and a pinned test measures the two against each other on one fixture in one run. Pass `groupIds` and the two part company by design — see below. Trust the tool's own `positionedContainers` for *where* each one landed, and trust the export over both.

**It does not arrange a plain element acting as a container *by default*.** The decision table types a Region as `Node`, so a top-level Region holding a whole branch is neither a native group nor a `Grouping`, and a call that does not name it leaves it where it is. That is deliberate — a host is not a zone — and it is no longer silent: `skippedContainers` names every populated top-level container the tool declined to arrange, with the reason.

**Pass the id back and it is arranged.** Every `skippedContainers` entry carries a `viewObjectId`, and putting that id in `groupIds` on the next `arrange-groups` call positions that container alongside the zones, whatever its element type — counted in `groupsPositioned`, returned in `positionedContainers`, and gone from `skippedContainers`. This is the intended follow-up, not a workaround: **read `skippedContainers`, and for every container that matters to the layout, re-call `arrange-groups` with those ids in `groupIds`.** On the specimen this is the Region host that had to be placed by hand. Name the zones you still want arranged in the same list — `groupIds` replaces the default set rather than adding to it. Two effects to expect on that second call, both correct: the host's contents now count toward the topology weight matrix, so the zones may be ordered differently, and the density-aware `spacing` default may resolve to a different value (the response's `defaultResolutionReason` says which). The refusals are specific if an id is wrong — an unknown id, a model concept id, an object on another view, a nested container and a top-level note are five different messages, each naming its remedy.

**Do not diff anything. The response accounts for the whole view.** `topLevelObjects` is the number of direct children the call measured against, and the four buckets sum to it exactly:

```
groupsPositioned + standaloneElementsPlaced + skippedContainers + unhandled = topLevelObjects
```

`unhandled` names every direct child no bucket claimed — the loose elements, the notes, the images, the view references — each with its `viewObjectId` and a `reason` leading with a stable code (`not-requested`, `not-an-archimate-element`, `lane-not-run`, `no-inter-container-gap`, `insufficient-connections`, `type-not-lane-eligible`). This is the population that used to be invisible: on the specimen the container diff read a clean 2 = 2 while **seven loose elements sat stacked at (50,50)**, overlapping the container they were drawn beside, and no response field named one of them.

**So read the two lists, not a subtraction.** Anything in `skippedContainers` or `unhandled` is still wherever it started, which on a freshly built view means stacked at the origin. Route each entry by what it is: a **container** — everything in `skippedContainers`, plus any `unhandled` entry reading `not-requested` — is arranged by naming its `viewObjectId` in `groupIds`; anything else holds no children and must be positioned with `apply-positions`. Filter the rest by reason code, and then trust the export over all of it.

**Watch `layout-within-group` in a grid — with `autoWidth` set OR UNSET.** It writes every child a full rectangle, and in a grid that rectangle is the child's *cell*, so an element lands at its column's width however narrow it started. `autoWidth` only changes what the element contributes as *input*; it is not what does the stretching, and leaving it off does not opt you out. Two ways a column gets wide: single-level, every cell takes the widest element anywhere in the grid; with `recursiveChildren`, each column takes its own widest member — and an inner container that fitted wide is one of those members, so a narrow element sharing its column is stretched to the container's width. Measured on this specimen, `columns: 3` with `recursiveChildren` and `autoResize` on the outermost cloud container: **13 elements re-sized, the worst going 230px → 3330px — a 14.5× stretch** — with the container itself finishing at 5940 × 2470. Two other elements passed 1300px. Treat those magnitudes as the scale of the effect, not its ceiling.

**The escape hatch is the arrangement, not a width parameter.** `arrangement: "row"` and `arrangement: "column"` do **not** normalise child widths at all — they return no `resizedElements` key, because the normalisation is a **grid-only** behaviour. So a container holding one wide sub-container beside narrow leaves is laid out safely as a row or a column, and dangerously as a grid. `elementWidth` does *not* rescue the grid case under `recursiveChildren`: it sets what an element *contributes* to its column, and the column still takes the fitted container's larger width. **Read `resizedElements` after every grid call** — it names each child this happened to, and it is the only way to see the stretch without re-querying the view.

Read **`resizedElements`** in the response: it names every child whose size actually changed, with the rectangle it ended at, and is omitted entirely when nothing did. Do not read `elementsRepositioned` for this — it counts *moves* and says nothing about size. The visible symptom is a canvas that is mostly whitespace; `assess-layout` does not reliably catch it and the export does. To choose the width yourself, pass `elementWidth`.

### Phase 8 — Verify (close the loop; never assume success)

1. `get-view-contents` — confirm every element you intended to place is placed.

   **Then check for orphans (G17).** An element with zero relationships is invisible to every other check in this prompt: it is created, typed, provenance-marked, counted in the disposition, and connected to nothing. Query the model — `get-relationships` per element, or a search over what you created — and reconcile the isolates against what you *meant* to leave isolated. On the specimen a run passed all sixteen other gates while leaving **eleven** elements disconnected. Do not verify this from your own build log; the log records what you intended, and this gate exists because intent and outcome parted.
2. `assess-layout` — resolve what it flags.

   **On a mirror view, the exemption is narrow and it is NOT "ignore the assessment".** Mirror mode preserves **element geometry**; it explicitly does **not** preserve routing (*Mirror-mode geometry* step 6 routes conventionally from scratch). So split what the assessment reports:

   | metric class | on a mirror view |
   |---|---|
   | element **positions** and **sizes** — `overlaps`, `cousinOverlaps` the source itself contains | **exempt.** Do not move elements. On the specimen the router asked for `Ireland` to be moved by (−1302, +1447), which would have destroyed the view |
   | overlaps the **growth step created** — two boxes that do not overlap in the source but do at 120×55 | **fix them.** These are yours, not the author's |
   | **routing** — `passThroughs`, `nonOrthogonalTerminals`, `interiorTerminations`, `coincidentSegments`, `edgeCoincidence` | **fix every one that is resolvable without moving an element**, to the same standard as any other view. You routed these; the source did not. **Where a defect cannot be resolved without moving an element, the geometry wins** — mirror mode preserves the author's layout, and a mirror view that quietly relocated boxes is no longer a mirror. Leave it, and report it: name the connections, the count, and the element whose position blocks the corridor. That residue is a property of the source drawing, not a failure of this run, and reporting it is what tells the architect their own diagram cannot be routed cleanly as drawn |

   **How to tell a source overlap from a growth artifact** — do not assert the distinction without testing it: two elements overlap in the source if their *source* rectangles intersect. You computed those in step 1. Any overlapping pair whose source rectangles are disjoint was created by the growth step. Report the split as two counts; a single "overlaps are faithful" claim is not checkable and is usually wrong.

   `interiorTerminations` in particular is never faithful — a connection terminating inside an element's bounds instead of on its perimeter is a routing defect on any view. `assess-layout` names the remedy (`ChopboxAnchor` face selection; re-run `auto-route-connections`).

   A mirror view is graded on **geometric** fidelity (G13), not on being exempt from craft. None of this is a gate exception — do not report it as one.
3. `export-view` — **look at the PNG**. The render is authoritative over any metric (G12).
4. Re-run 2 then 3 after any fix, in that order, and make no further view-mutating call **against that view** after its export. A fix you did not re-export is a fix you have not seen (G12). In `both` mode, closing out the semantic view does not close the run — the mirror view gets its own full pass of steps 1–4.

**`assess-layout` grades what is there, not what is missing.** It can rate a canvas that is largely empty space as good, because every element it *can* see is well placed. Judge whitespace, balance and overall shape from the PNG only — those are the failures the metric is structurally unable to report, and they are the ones a reader notices first.

### Phase 9 — Close-out

Emit the *Final report*. A first generation is a **draft for review**, not a finished model. Say so.

---

## SOURCING THE INPUTS FROM A CONFLUENCE PAGE

**Skip this whole section unless the diagram is embedded in a wiki page.** If you were handed a `.drawio` file, a path, or mxGraph XML, nothing here applies and nothing here is owed — go on to *The parsing contract*.

Both inputs can come from a wiki page: the prose is the page body, and the diagram is embedded in it by the draw.io plugin. **Nothing downstream changes.** Every trap, gate, count and grade below operates on the mxGraph XML and on the companion prose, not on where either came from. Only the plumbing differs, and this section is that plumbing.

This section is **guidance**, not a gate — but two things in it are load-bearing enough that getting them wrong ends the run with a confident wrong answer, and both are marked.

### The shape of the problem

A Confluence page holds the companion text and **one or more** embedded diagrams. The diagram's XML is normally **not in the page body**: the macro *names* an attachment, and the bytes live in that attachment. So the chain is:

```
page (storage format) → drawio macro → the diagram's NAME → the matching attachment → its bytes → mxGraph XML
```

Each arrow is a place this goes wrong quietly.

### Finding the macro — two generations, two spellings

The draw.io plugin has shipped more than one storage shape. **Both are live in the field**; which one you meet depends on the app generation and on Cloud vs Server/DC.

| Generation | What the storage format shows | Where the diagram's name lives |
|---|---|---|
| **Forge / ADF** (Confluence Cloud, current) | `<ac:adf-extension><ac:adf-node type="extension">` carrying `<ac:adf-attribute key="extension-key">…/static/drawio</ac:adf-attribute>` | `<ac:adf-parameter key="diagram-name">NAME.drawio</ac:adf-parameter>`, inside the extension's `guest-params` |
| **Connect / legacy macro** | `<ac:structured-macro ac:name="drawio">` | `<ac:parameter ac:name="diagramName">NAME</ac:parameter>` |

**⚠ The parameter key is spelled differently in each, and a grep for one finds zero hits in the other.** `diagram-name` is kebab-case; `diagramName` is camel-case. So is the enclosing element: `ac:adf-parameter` versus `ac:parameter`. A search for `ac:structured-macro` on a Forge page returns nothing at all, and *"there is no draw.io macro on this page"* is then a **wrong conclusion presented as an observation** — the same failure mode this prompt guards everywhere else.

**Do this instead.** Fetch the page in **storage format** (raw XHTML, not rendered), search it for the bare literal `drawio`, and read whatever encloses each hit. Let the file tell you which generation it is. If neither table row matches what you find, describe what you actually see rather than forcing it into one.

### ⚠ A page is a SET of diagrams, not one diagram

This prompt converts **one** diagram. A Confluence page routinely embeds several, and its attachment list routinely holds more `.drawio` files than the page still references — old revisions and removed macros stay attached. One real page measured **4+ draw.io macros, 56 attachments, 14+ `.drawio` files**: roughly ten of them orphans.

Three consequences, all mandatory:

- **Enumerate before you choose.** List every draw.io macro in document order with its diagram name and the nearest heading above it. Report the list. If the caller named a diagram, take that one; if not, **ask** — or run once per diagram into separately-named views, and say which you did.
- **Never pick "the `.drawio` attachment".** With orphans present there is no such thing, and the attachment that *looks* newest may belong to no macro at all.
- **Match by exact filename equality**, against attachments whose media type is `application/vnd.jgraph.mxfile`. Exact, not fuzzy: sibling names differ only in the middle of a long digit run (`Untitled Diagram-1785338002857.drawio` against `…-1785401366864.drawio`), so a nearest-match rule silently converts the wrong picture, and every count in the report will be internally consistent and about a diagram nobody asked for.

### View naming on this route

`{{VIEW_BASE_NAME}}` defaults to the source file's stem. **On this route that default is useless**: the plugin's own names are `Untitled Diagram-<epoch-milliseconds>.drawio`, so the stem carries no meaning and several views from one page would be indistinguishable.

Override it. Use the **page title**, plus a disambiguator taken from the nearest heading above the macro (`Phase 1`, `Data Flows`) or, failing that, the macro's ordinal on the page. Say in the report which you used and what the underlying diagram name was, so a later reader can find the source.

### Retrieving the bytes

> **⚠ UNVERIFIED ROUTE.** As of 2026-08-25 this chain has **not** been executed end to end. It is written from a read-only capability probe whose download step failed (see the clinic below). The tools exist and the lookup chain above is observed fact; the fetch itself is a **route to test, not a route known to work.** Report which step you actually reached.

1. **List the page's attachments** — filename, id, media type, byte size, and the download link the API returns (`_links.download` in Confluence's v1 REST shape). Match to the macro by the exact-filename rule above.
2. **Fetch that attachment's content by id.** Confluence MCP servers typically return attachment bytes **base64-encoded**; decode to get the file. If your client has shell or file-write access, land the decoded bytes on disk and hand the path to `{{DRAWIO_FILE}}`; otherwise paste the XML — `{{DRAWIO_FILE}}` accepts either, and the pasted form is the normal case for this route rather than a degraded one.
3. **Then run the compressed-payload check** (*Parsing contract* → *Before trap 1*) **before parsing anything.** This route is the most likely of any to hand you a compressed payload — you never see the file, so you cannot eyeball it, and a compressed payload parses to an empty inventory without raising an error.

#### Failure clinic — read this before "fixing" a download error

Apply the three-outcome rule to every step: *tool does not exist* · *tool exists and errored (quote the error)* · *tool worked and returned nothing*. These need different fixes and collapsing them into "it didn't work" costs a day.

**If the download fails while `get page` and `list attachments` succeed on the same page, do not accept "insufficient permission" as the diagnosis.** Confluence attachment-read follows page-read; a credential that reads the body and lists the attachments but cannot fetch one is an unusual permission shape and a very ordinary URL bug. **Read the URL in the error first** — it is usually the whole answer:

| What the failing URL looks like | What it means | Fix |
|---|---|---|
| `https://<site>.atlassian.net/rest/api/…` — **no `/wiki` segment** | Confluence **Cloud** REST lives under `https://<site>.atlassian.net/wiki/rest/api/…`. This is a **Data Center-shaped URL fired at a Cloud site**; it never reaches Confluence, and the tenant gateway answers `401`. **Not a permission problem.** | Correct the client's base URL / context path. Retry the same call with `/wiki` inserted before concluding anything else. |
| `/rest/api/content/{id}` with **no `/download`** | That is the *metadata* endpoint. It returns JSON about a content object and never returns bytes. | Use the `_links.download` path from the attachment listing. |
| a correct `/wiki/…` download path, still `401`/`403` | *Now* a permission or OAuth-scope problem is plausible — Cloud 3LO needs an attachment-read scope, not only `read:page:confluence`. | Grant the scope, or fall back below. |

**The fallback that always works, and is not a defeat.** A human with browser access to the page downloads the attachment themselves. That uses **their** Confluence permissions, not the integration's token, needs no scope grant and no server fix, and takes about a minute. It also settles the compression question from real bytes. **Manual export is a sanctioned route, not a degraded one** — say in the report that the bytes arrived by hand, and carry on.

### The companion text

**Observed to work.** Fetch the page body as **rendered text or markdown**, not storage format. Prose, headings, code blocks and tables come through cleanly, and a Confluence component table is the same shape the *Companion text* section already expects. Prefer this over storage XHTML, which buries the prose in `<ac:structured-macro>` / `<ac:adf-*>` wrappers the corroboration test has no use for.

**⚠ Strip the flattened macro text before you paste it.** In the rendered/markdown view the draw.io macros do not vanish — they render as **garbled inline runs**: extension keys, UUIDs, parameter names, hex strings. That text sits *inside* the companion body, and to the corroboration test (*Provenance marking* → four channels) it is indistinguishable from prose. Left in, it can supply **spurious matches** for tokens like `drawio`, `static`, or a hex run that happens to resemble an element name, and a corroborated grade earned that way is worse than an uncorroborated one. Delete those runs before pasting. Say in the report that you stripped them and roughly how much.

### What this route owes the report

**On this route only** — a run sourced from a file or from pasted XML owes none of it, and must not emit these as `none` rows. Add to *§1 Inputs*: the page (title and id or URL); which macro you converted, by diagram name and by its ordinal on the page; the attachment id and byte size; **whether the bytes arrived through the API or by hand**; whether they were compressed; and **how many other diagrams the page holds that you did not convert** — that last number is the one a reader will otherwise assume is zero.

---

## THE PARSING CONTRACT

Five traps. Each one loses data **without raising an error**, which is why they are gates rather than advice. A parser that misses trap 1 and trap 2 produces a plausible-looking inventory that is quietly wrong.

### Before trap 1 — confirm you are reading XML at all

draw.io can store a diagram **compressed**: the `<diagram>` element's payload is deflate-compressed and base64-encoded rather than written as readable XML. The file is still a valid `.drawio`, the `<mxfile>` and `<diagram>` tags are still there, and **every trap below silently finds nothing**. This is a precondition, not a parsing subtlety — run it first.

**The check.** Find the first `<diagram …>` tag and look at what follows it:

| What follows `<diagram …>` | Meaning | Action |
|---|---|---|
| `<mxGraphModel …>` and, further in, `<mxCell` elements | **uncompressed** | proceed to trap 1 |
| one continuous run of `A–Z a–z 0–9 + / =` with no angle brackets | **compressed** | decode it, below |

The single most reliable signal: **`<diagram` is present and `<mxCell` count is zero.** That is compression, every time. It is *not* an empty diagram, a malformed file, or a failed download, and reporting it as any of those is the failure this section exists to prevent — an empty inventory is exactly what an unread compressed payload looks like from the inside.

**To decode**, apply draw.io's own pipeline to the payload, in this order: **base64-decode → raw DEFLATE inflate (no zlib/gzip header) → URL-decode**. The result is the `<mxGraphModel>…</mxGraphModel>` the uncompressed form would have contained. Parse that, and run every trap against it unchanged. In Python: `urllib.parse.unquote(zlib.decompress(base64.b64decode(payload), -15).decode('utf-8'))` — the negative window size is what selects raw DEFLATE, and omitting it is the usual reason a correct decode attempt fails.

**Report which form you received**, and if you decompressed, say so and give the decoded length. A run that silently decompresses is fine; a run that silently *fails* to and reports a small model is the outcome this prevents. **If you cannot decode it, stop and say so** — do not proceed to build a model from a partial or guessed parse. An honest stop is recoverable; a model built from an unread file is not, because every count in the report will be internally consistent and wrong.

**A caller can also avoid this entirely**: re-saving the diagram in draw.io with *Extras → Edit Diagram* (or exporting as XML with compression off) yields an uncompressed file. Where the caller can do that, it is cheaper than any decode.

### Trap 1 — the id and label can live on a wrapper (G3)

draw.io wraps any cell carrying custom attributes in `<object>` or `<UserObject>`, and **moves the `id` and label onto the wrapper**:

```xml
<UserObject id="REAL-ID" label="AWS Transit Gateway" lucidchartObjectId="BVyOZMk0ueoX">
  <mxCell style="shape=mxgraph.aws4.transit_gateway;…" vertex="1" parent="1">
    <mxGeometry x="-100" y="240" width="78" height="78" as="geometry"/>
  </mxCell>
</UserObject>
```

A parser that iterates `mxCell` and reads `id` / `value` loses every wrapped cell — silently, because the `mxCell` still parses fine with `id=None`.

**Rule:** when an `mxCell`'s parent element is `<object>` or `<UserObject>`, take `id` and the label from the **wrapper**; take style, geometry and `parent` from the `mxCell`. Custom attributes on the wrapper (`lucidchartObjectId`, `treeRoot`, anything else) are **evidence** — carry them into identity resolution and into the element's `source` property.

### Trap 2 — edge labels are child cells, not the edge's `value` (G4)

draw.io stores an edge's label as a **child vertex of the edge**, with `relative="1"` geometry — usually `width=0 height=0`:

```xml
<mxCell id="edge-1" edge="1" source="A" target="B" style="edgeStyle=orthogonalEdgeStyle;…"/>
<mxCell id="label-1" vertex="1" parent="edge-1" value="Static IPs Port 443" style="edgeLabel;…">
  <mxGeometry relative="1" as="geometry"/>
</mxCell>
```

An edge almost never carries a `value`. Reading only `value` reports "no edge labels" on a diagram that has them — and edge labels are the single richest relationship signal the file contains, so losing them is expensive.

**Rule:** any vertex whose `parent` is an edge id is that edge's label. Attach it. A label on a **dangling** edge (no source/target) is recoverable text describing an unrecoverable relationship — report it, do not discard it silently.

### Trap 3 — visual nesting is not structural nesting (G5)

This is the big one. In hand-drawn diagrams most cells sit at the root layer with `parent="1"` regardless of what they visually sit inside. Only cells explicitly marked `container="1"` — and even then only sometimes — actually parent their contents.

Expect **the majority** of vertices to have a visual container that differs from their declared parent. On a real specimen the figure was 69 of 98.

**Rule — compute containment geometrically:**

1. Resolve every vertex to an **absolute** rectangle by walking its declared-parent chain, summing offsets. (Children of a real container have parent-relative coordinates.)
2. Work over the **population** of vertices with `width > 0 ∧ height > 0`. Zero-area cells are edge labels (trap 2), not boxes, and counting them corrupts every figure downstream.
3. For each vertex, its visual parent is the **smallest strictly-enclosing** rectangle in that population. Strictly-enclosing means `b.x1 ≤ a.x1 ∧ b.y1 ≤ a.y1 ∧ b.x2 ≥ a.x2 ∧ b.y2 ≥ a.y2` and `b ≠ a`. A vertex enclosed by nothing has **no** visual parent.
4. **Ties on identical geometry: the unlabelled cell is the decoration, the labelled one is the container.** Do not tie-break on tree depth — draw.io's `style="group"` wrapper and the labelled box inside it are byte-identical rectangles, so each strictly encloses the other and "prefer the deeper one" is circular: it asks for the answer you are computing. Applied naively this generates phantom nesting tens of levels deep. Drop the unlabelled wrapper as decoration and keep its labelled twin.
5. Build the tree.

**Counting the G5 mismatch — the definition, so two runs agree.** Take the same population as step 2. A vertex's **declared** parent is its `parent` attribute, treated as *none* when it names the mxGraph root (`0` / `1`) or anything outside the population. Its **inferred** parent is step 3's result, or *none*. Count a mismatch wherever the two differ, **including** where one is *none* and the other is not. Report the count **and the population size** — a bare number is not checkable.

**Tie-break — state it or the count is not reproducible.** Two rectangles can enclose each other (draw.io's `style="group"` wrapper and its labelled twin are byte-identical). Under a non-strict `≤` test each is a candidate parent of the other and "smallest enclosing" cannot separate them, so the answer falls out of *iteration order* — which is not an answer. **Resolve it by dropping the unlabelled wrapper from the candidate pool** before computing anything, exactly as step 4 above already requires. Then the twin's inferred parent is the next enclosing box out, and the count is deterministic.

*On the specimen: **75 of 94**, measured under that rule and stable under reversed input.* Related figures that are **not** this one, and are easy to report by mistake: 81 vertices have a visual parent at all, 77 have a declared parent outside the population, 64 sit at the root while visually enclosed, and **69 or 73 if you leave the wrappers in the pool** — that pair is the order-dependence, and reporting either one means the tie-break was never applied.

**Strict `<` gives the same 75 on this specimen, and that is worth knowing rather than assuming.** Seven vertices change *which* parent they get between `≤` and `<`, but all seven carry `parent="1"` — outside the population — so both variants already score them as mismatches and the total does not move. Do not read that as a general result: it says the `<`/`≤` choice is invisible to *this* metric on *this* file, not that the two rules agree. Measure both on your own specimen before quoting either.

**Do not "fix" the source by normalising a contradictory hierarchy.** If one branch nests Account inside Region and another nests Region inside Account, that is a real inconsistency in the source diagram. **Reproduce it and report it.** An architect wants that told, not quietly resolved — a tidied hierarchy that hides a contradiction is worse than an untidy one that shows it.

### Trap 4 — coordinates are negative, fractional and zero

- Top-level geometry is absolute and **frequently negative** (a canvas may run from x = −1660).
- Children of a true container are **relative to that container's origin**.
- Some vertices have `width=0 height=0` (usually edge labels, per trap 2).
- Imported diagrams carry fractional sizes (`35.44844574780059`) — an artifact of the import, not a meaningful dimension.

**Rule:** resolve to absolute, then translate so the minimum x and y are ≥ 0 before using any coordinate. Never feed a raw draw.io coordinate to a placement call.

### Trap 5 — an edge can carry its endpoints as geometry instead of as ids (G15)

An edge drawn by dragging between two shapes carries `source` and `target` attributes holding cell ids. An edge drawn **freehand** — placed on the canvas and pushed up against two shapes without ever snapping to them — carries neither. Its endpoints live in its geometry instead:

```xml
<mxCell id="edge-9" edge="1" style="shape=flexArrow;…">
  <mxGeometry relative="1" as="geometry">
    <mxPoint x="-1570" y="2339.5" as="sourcePoint"/>
    <mxPoint x="-1490" y="2340"   as="targetPoint"/>
  </mxGeometry>
</mxCell>
```

Nothing distinguishes this from a genuinely dangling arrow except the geometry, so **"no `source`/`target` attribute" is not a licence to drop.** It is the trigger for a recovery attempt.

This matters more than its frequency suggests. Freehand arrows are what an author draws when marking out the *primary path* through an architecture — the fat emphasis arrows across the top of the picture. On the specimen behind this prompt, all five were the entire ingress chain, and they carried the diagram's only two informative edge labels. Dropping them also **flatters the `Association` proportion** in the final report, by removing the most meaningful edges from the denominator.

**Rule — recover geometrically, then grade `assumed`:**

1. Take each endpoint's absolute point (trap 4).
2. Compute its distance to the **boundary** of every vertex rectangle — zero if the point is inside.
3. **Exclude containers** (any rectangle that strictly encloses another vertex) and **exclude annotations** (step markers, legend text). Both are near-misses that outrank the real endpoint: a freehand arrow inside a big region box is nearer to the region than to anything in it.
4. Take the nearest surviving shape, subject to two thresholds you choose and **state in the report**: a maximum distance, and a minimum margin over the runner-up. An endpoint that fails either is **unrecovered** — report it, do not guess it.
5. Create the relationship from the recovered pair and grade it **`assumed`** — the endpoint was derived from proximity, which is exactly what that level means. Its label, if it has one (trap 2), still feeds the intent ladder normally.
6. An edge unrecovered at **either** end is a genuine dangling edge. Drop it, report it, and keep its label as recoverable text (trap 2).

**Calibration, not a constant.** On the specimen, **eleven** endpoints need recovery — five freehand arrows contributing two each, plus one half-dangling edge carrying a single id and contributing one. They resolved at 5–52 px from their shape, with the nearest runner-up 37 px further out — so a 60 px ceiling and a 25 px margin recovered all eleven with room to spare, *once containers and step markers were excluded*. Without that exclusion three of the five arrows resolved to a region box or a numbered circle instead. Those figures are a starting point for a diagram at that scale; measure your own and say what you used.

⚠ **Count endpoints, not edges, and count the half-dangling one.** An edge missing *both* ids needs two recoveries; an edge missing *one* needs one. The report section below requires those two populations separately for exactly this reason — a figure derived from "five arrows × 2" silently drops the half-dangling edge and will not reconcile with it.

---

## CONTAINER SEMANTICS

### The reframing that makes this tractable

Two separate questions, and separating them dissolves most of the difficulty:

- **In the *view*, what does a container become?** A nested visual parent, via `parentViewObjectId` on `add-to-view`. **This requires no relationship and is subject to no validity check.** Containment in the view is free.
- **In the *model*, what does it become?** An element plus a real relationship. This is where type choice matters.

A model that records hierarchy only as view geometry is not queryable — you could not ask "what runs in this region?". So **do create the relationships** — but know you are choosing to, and that the view would render either way.

### The decision table

| Drawn container | Type | Rationale |
|---|---|---|
| Cloud / platform boundary | `Grouping` | An ownership boundary, not a host |
| Account / subscription / project | `Grouping` | An administrative boundary |
| **Region** | **`Node`** | It *hosts* computational resources — which is Node's definition. Published cloud-modelling practice uses `Node` here |
| Availability zone | `Grouping` | Established practice for zone-scoped resource sets |
| VPC / VNet / subnet | `Grouping` | A logical network boundary |
| Security group / security zone / VLAN *(drawn as a container)* | `Grouping` | A rule set and a logical perimeter, not an appliance |
| Firewall / WAF *(drawn as a discrete icon)* | `Node` + specialization `Firewall` | A traffic-processing appliance |
| On-premise / data-centre boundary | `Grouping` | Consistency with other ownership boundaries |
| Unnamed rectangle enclosing content | **title test first.** If it **succeeds**, type it from the recovered name (below). If it **fails**, defer — ask (Phase 3.5) | The name is often in the file, just not on the cell |

**When the title test succeeds, the table still owes you a type — here is how to get one.** A rectangle named this way is typically a plain draw.io box with **no stencil token at all**, so there is nothing to look up: the rows above are recognised in practice from the container's token, and this one has none. Do not stop at the name.

1. **Match the recovered title against the rows above, by meaning.** A title naming a VPC, subnet, region, account, availability zone, security group or on-premise boundary takes that row and its type. The title is evidence about what the container *is*, and that is exactly what those rows key on.
2. **Where no row matches, default to `Grouping`**, and grade the type `assumed`. `Grouping` is the safer default because it is what this table already gives every boundary that does not *host* anything: a wrong `Grouping` merely under-commits, whereas a wrong `Node` positively asserts that the container hosts computational resources.
3. **Name every container typed this way in the report**, with its recovered title and which branch applied (matched row, or defaulted). These are the containers a human is most likely to want to re-type, and they are invisible unless you list them.

⚠ **A title-derived type can never be corroborated.** The name came from a text cell and the companion document describes the container by that same name, so treating the two as agreeing is *one source used twice* — see *The corroboration test*, which excludes exactly this case. Such containers stay `inferred` or `assumed`; they do not reach `stated`, however confidently the document describes them.

**Do not use `Location` for cloud regions.** It is semantically tempting and wrong in practice: no published cloud-modelling source maps a region to `Location`, and it is more restrictive as a relationship *target* than `Node` (`Node → Location` permits only Flow / Association / Triggering / Serving). Reserve `Location` for genuine geography in a business or physical view.

**The container-vs-icon split is evidence-based, not stylistic.** A security group drawn as a box that *encloses* other shapes is a boundary. A firewall drawn as a bare icon that encloses *nothing* is a device. Read the drawn form; it usually matches the semantic distinction.

### Containers titled by an adjacent text cell (G16)

The cloud-stencil idiom: the box is a plain unlabelled rectangle and its **name is a separate text cell** sitting on or just above its top edge. Read the two independently and you get an unnamed container plus a stray text fragment — so the container is dropped as unnamed and everything inside it is orphaned. On the specimen this pattern accounted for three real services and the elements beneath them.

**Rule — adjacency test.** An unlabelled container is titled by a text cell when *all* of:

1. the cell carries **no shape token** — it is text, not a shape;
2. its **top edge** is level with the container's top edge, within a small tolerance either side, and it overlaps the container horizontally;
3. **no other unlabelled container** is a closer candidate for the same cell.

**Enclosure does not disqualify a title.** A cell sitting wholly inside a container and *clear of* its top edge is a child. A cell sitting flush with the top edge is a title **even when it is technically enclosed** — draw.io's own idiom drops the title on, or fractionally inside, the box it names. Testing "is it enclosed?" instead of "where is its top edge?" throws away real titles: on the specimen the three genuine titles sat at **−1.5, 0.0 and +4.2 px**, and one of them *was* enclosed. Rejecting it would have dropped its container as unnamed and orphaned the four elements inside.

**Corroborating signal.** Where the stencil library supplies a container badge — a small icon with an empty label sitting at the container's **top-left corner** — its presence confirms the pairing, and its token supplies the container's *type* evidence. Treat the badge as decoration (do not create it as an element), but do record which token it carried.

**This rule and *A recognised token with an empty label* describe the same cells, and this one runs first.** Both speak to an empty-label tokened cell; the badge test — at a container's top-left corner — decides which of them applies. A cell that passes it is decoration here; every other empty-label tokened cell is a bare icon and becomes an element there. Run them in that order and the two counts partition the population instead of competing for it.

The cell is then **consumed as the title** — it becomes the container's name and is *not* separately created as an element or a note. Grade the container's name `stated` (it was read from the file) and its type `inferred` (the decision table supplied it). **Report every adoption**, with which cell titled which container, so a wrong pairing is visible rather than silent.

Where no cell qualifies, the container really is unnamed — go to Phase 3.5 and ask.

**Rendering a container the source never named.** `add-group-to-view` **accepts an empty `label`** and creates a genuinely untitled group, which Archi stores and renders correctly. Pass `label: ""` — do not substitute any placeholder text:

- **Do not paint the source cell id on the canvas.** It is tempting, because it preserves the trace — but the id belongs in the group's `source` property and in the report, where a reviewer can follow it. On the deliverable it is parser exhaust rendered as architecture, and it is the first thing a reader's eye lands on.
- **Do not invent a plausible name** from the contents. A guessed label is indistinguishable from a read one, which is the failure this whole prompt is built to prevent.
- **Do not write a literal placeholder** such as `(unnamed)` either. It reads as a title the source supplied, and it is a decision the tool no longer asks you to make. An untitled box on the canvas says "unnamed" without claiming to be a name.
- **Note that the `label` key is still required** — omitting it is an error. `""` is a value; absence is not.
- **Say how many there are in the view note**, so a reader of the picture alone knows those boxes carry no model semantics.

**⚠️ An unnamed container must not orphan what it holds (G17).** This is the failure the rule above causes if you stop at "no model element". A view-only group has no model identity, so its children have **no model parent** — and if geometric containment puts nothing else above them, they land in the model with **zero relationships**: present, typed, provenance-marked, and connected to nothing. On the specimen this was **eleven elements**, an entire platform-services tier (IAM, KMS, Secrets Manager, CloudWatch, CloudTrail and the rest), and every disposition count still summed correctly because G6 counts them as *created*.

**Rule: an unnamed container is skipped as a model element, never as a link in the chain. Do BOTH of the following — they are not alternatives.**

1. **Render the box** as a view-only group with `label: ""`, on every view. The author drew a boundary; dropping it loses grouping the picture was carrying. Skipping this step is not a tidier model, it is a worse diagram — on the specimen, omitting these four boxes let the layout stretch eleven elements across three empty rows and produced the run's worst render.
2. **Compose its children into the nearest named ancestor**, so the containment chain closes over the gap rather than breaking at it.

**Where there is no named ancestor**, the elements are real and they belong somewhere, so do not leave them orphaned. Resolve it in this order, and **stop at the first that applies**:

- **A provider boundary drawn on the same canvas** takes it. If the diagram draws a cloud/platform boundary anywhere and the orphaned group's contents are that provider's own services — read from their stencil tokens, not guessed from their names — compose them into it **even when the group sits outside that boundary's rectangle**. Geometry is the default evidence, and here the stencil is better evidence than a box the author drew loosely.
- **Otherwise ask** (Phase 3.5), and on no answer leave them at the top level and **report them as isolates under G17**. An orphan you named is a finding; an orphan you did not notice is the defect.

*On the specimen the first case applies: the four unnamed groups sit above the `AWS Cloud` rectangle, and every element they hold carries an AWS stencil token, so they compose into `AWS Cloud`. Do **not** reach for a Region — the drawing places them outside every region box, so a region assignment would be invented, which is the guessing this prompt exists to prevent.*

*Calibration:* on the specimen the three qualifying titles sat at **−1.5, 0.0 and +4.2 px** of their container's top edge, while every non-qualifying unlabelled container had **no text candidate anywhere** in a ±50 px window — the gap was absolute, not narrow. Do not assume that separation holds; measure it and say what you used.

### Near-miss containment — run this in Phase 4, after types are known

Strict enclosure (trap 3) is the right primary rule and it has a systematic failure: **draw.io does not snap, so authors routinely draw a shape a few pixels outside the box they mean it to be in.** On the specimen this misfires **five times**, in **two different shapes** — and the distinction is the whole point of this section, because a rule written for one shape is structurally blind to the other:

- **Orphaned — no container encloses it, so it gets no parent at all.** A `Router` 20 px and a `Firewall` 23 px outside an on-premise boundary, and a gateway 20 px outside the cloud boundary.
- **Mis-parented — an *outer* container still encloses it.** Strict enclosure succeeds against the grandparent, so the shape attaches one level too high and lands as a **sibling of the container it belongs in**. A VPC 30 px past its region's right edge attaches to the *account*, beside the region; an edge service 8.9 px past its region's left edge does the same.

Every one of them is obvious to a human reading the picture. **The second shape is the more dangerous of the two**, and it is the one a reader is most likely to notice before you do: nothing is left unparented, so no orphan is reported, **G17 does not fire**, every disposition count still sums — and the model answers *"which region is this VPC in?"* with *no region at all*. That is the question this prompt creates real relationships in order to answer.

**Resolve the residue, not the tree.** Leave the Phase 2 containment tree and the G5 count exactly as strict enclosure computed them — that figure is a parsing metric and must stay comparable. Report every correction below **separately** from it.

**Two cases, and both must be run.** They differ only in which vertices enter and which containers may be candidates; steps 1–3 are identical for both. Running only the orphaned case leaves two of the specimen's five cases unreachable — not unresolved, *unreachable*, because they never enter the step to be judged.

| | **The orphaned case** | **The mis-parented case** |
|---|---|---|
| Enters when | strict enclosure gave the vertex **no parent** | strict enclosure gave it a parent, but a container **inside** that parent nearly holds it |
| Candidate containers | every container on the canvas | **only descendants of the parent already assigned** |
| On success | attach | **re-**parent, naming the parent it had |
| Specimen cases | `Router`, `Firewall`, the gateway | the VPC, the edge service |

**The mis-parented case's candidate restriction is its safety, not a convenience.** A correction can only move a vertex *further down the chain it is already on* — never sideways into an unrelated box. So the vertex's ancestors are untouched, no cycle is constructible, and the move is a refinement of the existing chain rather than a relocation. Note what this argument is and is not: it protects the **tree's integrity**. It is *not* what keeps G5 comparable — G5 is a Phase 2 figure counting where the geometric parent and the declared `parent` disagree, computed and frozen before this section runs at all, so **both** cases leave it alone by construction, not by any property of their candidate sets.

Then, for each vertex that enters:

1. **Geometry proposes.** Compute what fraction of the vertex's area falls inside each candidate container. A container qualifies only if it holds **≥ 50 %** of the vertex. Then:
   - **exactly one qualifies** ⇒ it is the candidate;
   - **several qualify and they nest in a single chain** ⇒ take the **innermost** — the rest are its own ancestors and already sit above it, so this is not an ambiguity;
   - **several qualify and they are disjoint**, or **none qualifies** ⇒ stop, leave the vertex exactly where strict enclosure left it, and report it.
2. **Type corroborates — it never originates.** You now know what both things are. Ask only whether the candidate is *coherent*: network equipment inside a site boundary, a service inside a region, a component inside a host. If the pairing is incoherent, do **not** attach — report the conflict instead, because geometry and semantics disagreeing is a finding.
3. **Attach, grade `assumed`, and name it in the report** with the percentage inside, the spill in pixels and the edge it spilled on — and, for a re-parent, **the parent it was moved off**. A re-parent that is not reported is indistinguishable from the mis-parenting it corrects, which is the failure this case exists to surface.

**Geometry must propose first, and this ordering is the whole safety of the rule.** By the time type is consulted the candidate set is already exactly one, so semantics is answering a yes/no question about a specific pair. Reasoning the other way round — "what would an architect expect to contain this?" — is unbounded, and it is precisely the invention G7 forbids. **A plausible answer is not evidence.** The `assumed` grade is what keeps this honest: it says a human concluded this rather than read it, which is exactly what happened.

*Calibration on the specimen — measured, all five, every one spilling on a single edge:*

| Case | Which | Container | % inside | Spill |
|---|---|---|---|---|
| `Router` | orphaned | On-Premise | 60.8 % | 20.0 px left |
| `Firewall` | orphaned | On-Premise | 57.0 % | 23.4 px left |
| gateway (`AWS Global Accelerator`) | orphaned | AWS Cloud | **50.0 %** | 20.0 px left |
| `Help Centre AI (chatbot) VPC` | mis-parented | Ireland | **97.0 %** | 30.0 px right |
| `Lambda@Edge` | mis-parented | North Virginia | 55.2 % | 8.9 px left |

**A 50 % floor does not catch these with margin — one case sits exactly on it.** The gateway measures 50.0 %, admitted by a rounding rather than by the threshold. Treat 50 % as a floor you must justify against your own specimen, not a safe default: measure your own, state the threshold you used, and **name the case that came closest to it** so a reader can see how much room the rule actually had.

**Note which cases the overlap figures belong to.** The single clearest case in the table — 97.0 % inside, the highest of all five — is a mis-parented one, and so is a 55.2 % one. Being unmistakably inside the right box is no protection at all against being attached to the wrong one; the overlap fraction and the failure shape are independent, and only the mis-parented case looks for it.

### Nesting depth

Real diagrams nest deeply — cloud › account › region › network › subnet › security group › resource is seven levels, and seven levels of nested boxes is unreadable at any zoom.

**Rule: the model keeps the full hierarchy as relationships; the view flattens only as far as readability demands, and carries what it dropped as element properties.** This is ArchiMate used properly — model and view are different artifacts. Record which levels were flattened, in the report.

**Cap per branch, not per view.** Four levels is a reasonable working depth for a *typical* branch, but applied as a global constant it flattens the one chain a reviewer opened the diagram to see — on the specimen, `VPC › subnet › security group › ALB`. A branch that carries the architecture earns its depth; a branch of pass-through wrappers does not. Deepen where the nesting *is* the content, flatten hard where it is bookkeeping, and state the depth you allowed each branch. If a branch's innermost boxes render too small to read, that is the signal to flatten it — which you will see in the G12 export, not in a rule.

---

## SHAPE-TOKEN MAPPING

**Emit a `(type, specialization)` pair, not a bare type.** This is how you reconcile ArchiMate's fixed vocabulary with a stencil library of hundreds of service names, and `create-element` takes an inline `specialization` that auto-creates.

**Extract the token** from the cell's style: `resIcon=` first, then `shape=`. A cell with neither has **no token at all** — which is a different situation from a token you do not recognise, and the two are resolved differently below.

### The table

Cloud-stencil-first, with a generic fallback. Extend it as you meet new stencils.

| Token family | Type | Specialization |
|---|---|---|
| load balancer, application/network LB | `Node` | Load Balancer |
| firewall, WAF, security appliance | `Node` | Firewall |
| container service, ECS/Fargate/Kubernetes | `Node` | Container Platform |
| VM, compute instance, EC2, traditional server | `Node` | Cloud Server |
| serverless function *(the service)* | `Node` | Serverless Platform |
| serverless function *(your code)* | `Artifact` | Function Code |
| object store, blob store, S3 | `Node` | Object Store |
| relational / NoSQL database service | `Node` | Database Server |
| DNS, name resolution | `Node` | DNS |
| CDN, edge cache | `Node` | Content Delivery |
| API gateway | `ApplicationComponent` | API Gateway |
| message broker, queue, topic | `ApplicationComponent` | Message Broker |
| application / microservice / agent | `ApplicationComponent` | Microservice |
| ML model, inference endpoint | `ApplicationComponent` | Model |
| identity, IAM, roles, permissions | `Node` | Identity |
| secrets, key management | `Node` | Key Management |
| monitoring, logging, tracing, audit | `Node` | Observability |
| gateway, transit gateway, interconnect, direct connect, radio/carrier link | `CommunicationNetwork` — see *Network elements* for the one case that is `Path` | — |
| router, switch | `Node` | Router |
| end-user device, mobile client, browser | `Node` — a **device** is a Node; only a person or organisation is a `BusinessActor` | Client Device |
| **custom code, container images, deployable data** | **`Artifact`** | — |
| **token present but unrecognised** | **`Node`** | — **and report the token** |
| **no token at all** (plain rectangle) | **read the label through this table** — see below | — |

### Five rulings this table encodes

**Deployed things, not exposed services.** Published sources split on whether cloud services map to `Node` or `TechnologyService`. Both are right — for different views. A drawing of a cloud architecture is a **deployment/integration view**: it shows what is deployed where and what talks to what, not what capabilities are contracted. So `Node` / `ApplicationComponent`, and **that includes the observability tools**. A CloudWatch or CloudTrail icon on a deployment diagram is a deployed thing sitting in an account like everything else around it; typing it `TechnologyService` while its neighbours are `Node`s makes the *drawing* look like it distinguished them when it did not. Reserve `TechnologyService` for a diagram that genuinely depicts a consumed service rather than a deployed tool — and say so in the report when you use it.

**Separate what you built from what the platform operates.** Reserve `Artifact` for custom application code — container images, function source, deployable data. It is the distinction a reader most wants and the diagram rarely states; take it from the companion text where you can.

**Read the first column as a family, not as a literal token string.** This is the single most consequential reading decision in the table and it must not be made per-run. The left column names a *kind of thing* in prose; it is not a list of stencil identifiers. So `mxgraph.aws4.bedrock` on a cell labelled "RAG Agent" hits the *application / microservice / agent* row, and on a cell labelled "Model" hits the *ML model, inference endpoint* row — even though the literal string `bedrock` appears in neither. A token is **unrecognised** only when neither the token's own name nor the cell's label names any family here. Read literally instead, and five elements on a diagram of this shape drop two ArchiMate layers to bare `Node`s; two runs over one file would then disagree for no reason, which is the failure the `CommunicationNetwork`/`Path` ruling below exists to prevent. **Say in the report which reading you used** — that sentence is cheap and it makes the disagreement visible if one ever happens.

**Unknown tokens fall back to `Node` and are reported — never guessed silently.** This makes the table extensible by observation: every run tells you which tokens to add next. A silent fallback makes the table look complete when it is not.

**No token at all is a different case from an unknown token — and it is usually the easier one.** A plain rectangle carries no stencil evidence, but it very often carries the answer in its label: a box reading `internal API GW` names a row of this table outright. So for an untokened cell, **match the label against the token-family column** and use the row it hits, graded `inferred` — the label was read, the type derived. Only a label that matches nothing falls to `Node`, graded `assumed`. Report the two classes **separately**: labels resolved through the table, and labels that resolved to nothing. Collapsing them hides the fact that most plain rectangles were readable all along, and treating every plain rectangle as an unknown throws away evidence that is sitting in plain text.

**A recognised token with an empty label is named by its token.** Bare icons — an `iam`, a `permissions`, a `role`, an unlabelled service glyph — are real elements the author expected the picture to name for them. Derive the name from the token's last segment (`mxgraph.aws4.role` → `Role`), grade the **name** `assumed` and the type `inferred`, and report every one so a human can rename them. The derived name then feeds *Identity resolution* normally — which matters, because two bare icons sharing a token in different containers are two elements, not one. Do **not** drop them.

⚠ **Check the badge rule first — it takes precedence, and the two rules address overlapping cells.** An empty-label tokened cell sitting at a container's **top-left corner** is a container badge, and *Container semantics* says to treat it as decoration and record its token rather than create an element. Only the cells that are **not** badges are bare icons. Apply the badge test first, then this rule to whatever remains. On the specimen there are **six** empty-label tokened cells and they split **three and three**: three badges at their containers' corners (dropped as decoration, tokens recorded) and **three real elements** — an `iam`, a `permissions` and a `role` — an identity-and-secrets tier that must not be lost. Report both halves with their tokens, so the split is visible rather than inferred from a count.

### Network elements

Use `CommunicationNetwork` / `Path` for network links, and connect nodes to them with `AssociationRelationship`. **ArchiMate has no "Path" relationship** — a network connection between two nodes is an Association.

**Ruling — `CommunicationNetwork` vs `Path`.** They are not interchangeable and the table's row must not be decided per-run, or two runs over the same file disagree for no reason:

- **A named network drawn as a shape** — an MNO/ISP, a transit gateway, a VPN concentrator, a direct-connect service — is a `CommunicationNetwork`. It is a thing the author put on the canvas and named; it has an identity.
- **`Path`** is only for a link the author drew as a **bare line between two nodes** and gave a network character rather than a service character — an unlabelled interconnect between two data centres. If it has a stencil and a name, it is not a `Path`.

When the evidence is genuinely balanced, take `CommunicationNetwork` and say so in the report. It is the more constrained claim of the two: a `Path` asserts that the author meant a link and nothing more, which a named box on a diagram rarely does.

**Ruling — device vs actor.** An `end-user device` row is a **`Node`**, specialization `Client Device`. `BusinessActor` is for a person or an organisation. A mobile handset, a browser or a kiosk is equipment, and typing it `BusinessActor` puts a business-layer concept in the middle of a deployment topology where every neighbour is technology — which then blocks the containment relationships you want from it. Where the diagram genuinely depicts a *person* (a stick figure, a labelled role, "Customer"), that is a `BusinessActor` and this row does not apply. Where a single cell means both — a "Mobile App" icon standing for the user *and* their handset — model the technology and report the conflation; do not create two elements from one cell (G7).

There is a respected argument that these elements are poorly designed and networks are better modelled as `Node`s wired by `Serving`. It is a fair critique of the standard, and it is not what this prompt does: the layout engine recognises `Path` and `CommunicationNetwork` as inter-zone-lane candidates and auto-places them between the groups they connect, which is exactly the value this workflow is built on.

### Carrying the stencil icon onto the element

A converted diagram loses the thing that made the original readable at a glance: the provider's icon. An ArchiMate `Node` labelled "AWS Global Accelerator" is correct and unrecognisable; the same box with the Global Accelerator glyph in its corner reads instantly. **Where you have an icon for a token, place it.** This is guidance, not a gate — a run with no icon assets available is not defective — but it is the single cheapest improvement to a converted diagram's readability, and the token you already extracted is what identifies the icon.

**Measured on Archi 5.10 / this server, 2026-08-14 — do not take these on trust, re-verify if the host changes:**

| finding | detail |
|---|---|
| **Use PNG, not SVG** | SVG *does* import and render — verified on Archi 5.10, against a tool schema that claimed it could not. But support is **host-version dependent**: it arrived with one Eclipse platform, was reverted, and returned in 2026-06, and **a model carrying SVG images can lose them when opened on an older Archi**. A converted model is a thing you hand to other people. Use PNG |
| **Size comes from the asset, not from a parameter** | there is no `imageWidth`. A 48×48 asset renders 48×48 and swamps a 120×55 element; a **16×16** asset renders as a clean corner badge. Author the icon at the size you want |
| **Use the vendor's small PNG as it ships — do not downscale it** | AWS's official package names its smallest tier `_16` but the file is actually **24×24** (the name is nominal; the artwork carries padding). At 24 px it renders as a clean, legible corner badge. **Measured 2026-08-14: downscaling it to a true 16×16 made it visibly worse** — the glyph goes muddy and reads as a smudge at normal zoom. The obvious "16×16 means resize to 16×16" step is the wrong move; import the vendor file unchanged |
| **Where they are** | AWS package layout is `Architecture-Service-Icons_<date>/Arch_<Category>/16/Arch_<Service-Name>_16.png` — predictable enough to resolve by name. Resource and Group icon families sit beside it; only Category-Icons ships explicit `_16/_32/_48/_64` directories. **The package is normally itself nested** — `Asset-Package_<date>/Architecture-Service-Icons_<date>/…` — so a one-level search finds nothing on a host that has it. Take `{{ICON_PACKAGES}}` if supplied; otherwise search ≥ 3 levels deep and **report the paths searched**. Other vendors publish their own layouts — resolve each package on its own scheme, and never against another vendor's |
| **Placement** | `imagePosition: "bottom-left"` with `showIcon: "never"`. **Never `top-right`** — Archi draws the element's type icon there and it will obscure yours |
| **⚠️ Give the box room, or the badge lands on the label** | Avoiding the type icon is not enough. A default element is **120 × 55** with its name drawn top-centre and wrapping down; a 24 px badge in the bottom-left corner then sits **underneath the second line of a two-line name**. Measured on the specimen: `assess-layout` reported `ownIconOverLabelCount` of 5–6. Either place icon-bearing elements at **≥ 140 × 70**, or check `ownIconOverLabelCount` after placement and grow the ones it names. `assess-layout` reports this metric precisely so you do not have to eyeball it — but it is still the export that tells you whether the result reads |
| **Batch** | `add-image-to-model` takes an `images` array of up to **150** per call. Import once, up front, then reference the returned `imagePath` on every placement. It is **not** available inside `bulk-mutate` and is **not** deferred by `begin-batch` — an archive write is not undoable |

**The archive write is permanent.** There is no removal API, so an image imported by mistake stays in the model file. Import only what you will place.

**Map icons by token, not by name** — the token is the evidence, the name is often derived. One import per *distinct token*, reused across every element carrying it; on the specimen that is roughly 30 imports for 72 elements. Report which tokens you could not find an icon for, in the same list as the unrecognised tokens — it is the same extensibility signal. **Report them grouped by vendor family**, and name which vendors had a package and which did not: "18 of 22 AWS tokens resolved, no Azure package supplied so all 9 Azure tokens are unresolved" is actionable, while a flat count of 13 unresolved tokens is not.

**Resolving a token to a file is a fuzzy match, so say what you matched.** The stencil token and the vendor's filename are two different vocabularies — `mxgraph.aws4.bedrock` against `Arch_Amazon-Bedrock_16.png`, `mxgraph.aws3.iam` against `Arch_AWS-Identity-and-Access-Management_16.png`. Strip the vendor prefixes from both sides, compare on the remaining words, and **take only a confident match**. A near-miss puts the *wrong company logo* on an element, which is worse than no icon at all because it looks deliberate. Where you are unsure, place nothing and list the token — an element without an icon is honest, and the reader can see the gap.

---

## IDENTITY RESOLUTION

Names are not identities. Expect repeated labels — the same label three times over three different subnets is three elements; the same label twice from a copy-paste is one element placed twice.

`add-to-view` supports **placing one element multiple times on a view**, each placement its own visual object. So merging costs nothing at render time — you do not lose the second box by deciding it is the same element.

Apply in order; first match wins:

1. **Same stable custom attribute** (`lucidchartObjectId` or equivalent from the wrapper, trap 1) ⇒ **same element**. Decisive — copy-paste and import tools preserve these.
2. **Same label ∧ same shape token ∧ same inferred container ∧ not drawn-as-separate-instances** ⇒ **same element**.

   **The last clause is load-bearing, and the obvious wording of it is a trap.** An earlier draft said "not disjoint-with-different-contents", reasoning that two boxes which neither overlap *nor* hold the same children are two things drawn on purpose. That works for containers and is **vacuous for leaves**: a bare icon encloses nothing, so "different contents" is never true, so the merge always fires. Measured on the specimen: three `Model` icons collapsed into one element invoked by three different agents, and two `Alias Record` icons into one — the exact silent merge the clause exists to prevent.

   **Test separation, not contents.** Two candidates are **separate instances** when they are geometrically disjoint *and* either holds different children **or** is wired to a different neighbour. A leaf has no children but it does have edges, and an icon drawn three times with three different things pointing at it is three deployments of one service — which is a `Node` placed three times on the view, not one element with three arrows converging on it. When this clause blocks a merge the pair becomes a **Phase 3.5 question**, whose default (treat as distinct, and report) then governs.

   **A merge is the outcome nobody notices**, so when the evidence is balanced, **do not merge** — report the pair instead. An unmerged duplicate is visible on the canvas and a reviewer can collapse it in seconds; a wrong merge silently deletes an element and rewires everything that pointed at it.
3. **Same label ∧ same shape token ∧ different container** ⇒ **distinct**, disambiguated by container name (`Security group (ingress tier)` vs `Security group (application tier)`).
4. **Same label ∧ different shape token** ⇒ **distinct**. Never merge across types.
5. **Otherwise** ⇒ distinct, and **report the collision** for human ruling.

Rule 5 is not a failure. Identical label, identical token, no container evidence and no prose is a genuine ambiguity, and guessing is worse than asking.

**Every merge is reported, not only every collision.** Rules 1 and 2 *reduce* the element count, and a reduction is the one outcome nobody notices: two boxes go in, one element comes out, and the model quietly asserts a sameness that may be wrong. Report each merge with which cells were collapsed and on which rule — a shared custom attribute (rule 1) is near-certain, a same-label-same-container match (rule 2) is a judgment that deserves a reader's eye. Reporting only the non-merges leaves the confident half of identity resolution unauditable.

---

## RELATIONSHIP INFERENCE

### The ladder does not need to be hard-coded

When a relationship is invalid, the server does not merely refuse — it returns **the complete legal set** for that source/target pair, plus a suggested correction. So the operating pattern is:

> **attempt → read the rejection → retry with a type from the returned set → `Association` if all else fails.**

The ladder below is a *fast path* that avoids most round-trips. It is not the correctness mechanism; the retry is. This matters beyond convenience — it keeps the prompt correct on stencils and diagram styles nobody tabulated.

**`AssociationRelationship` is a guaranteed floor.** It is legal between every pair of real ArchiMate element types. The ladder can always terminate.

### The ladder

Resolve an **intent** first — from the edge label, the companion text, or the shapes at each end — then take the first legal candidate:

| Inferred intent | Candidate order |
|---|---|
| Containment (parent → child) | `Composition` → `Aggregation` → `Association` |
| "calls", "invokes", "requests", "queries" | `Serving` *(direction reversed — see below)* → `Flow` → `Association` |
| "sends", "publishes", "streams", "notifies" | `Flow` → `Triggering` → `Association` |
| "reads", "writes", "stores", "persists" | `Access` (set `accessType`) → `Association` |
| "deploys", "hosts", "runs on" | `Assignment` → `Realization` → `Association` |
| Network link between nodes | `Association` |
| **Unknown — unlabelled edge, no prose** | **`Association` directly. Do not guess** |

### Direction is not free

A drawn arrow means "points at". ArchiMate `Serving` runs **provider → consumer**, which is frequently the *opposite* of the drawn arrow: if the diagram draws `Client → API`, the ArchiMate serving relationship is `API → Client`.

**Every direction flip must be justified by an edge label or companion text.** Where it is not, use an undirected `Association` and mark it `assumed`. Silently reversing an arrow on a hunch produces a model that is confidently backwards.

### The honest consequence

On a diagram whose edges are mostly unlabelled and whose styling carries no relationship signal, a diagram-only run produces **overwhelmingly `Association`** — legal, renders correctly, and says very little.

**That is correct behaviour, not a defect.** What would be a defect is letting a wall of grey lines imply more rigour than exists. State the Association proportion in the final report, plainly.

This is the strongest reason to supply companion text.

### Two different things become `Association`, and the headline must not merge them

`Association` is reached by two unrelated routes, and a single percentage hides which one you are looking at:

- **The ladder's floor** — an unlabelled arrow, no prose, nothing to infer. This says *the picture did not tell me what this line means*, and it is the number that argues for companion text.
- **A structural fallback** — you inferred containment correctly, but ArchiMate refuses `Composition` for that pair, so the ladder walked down to `Association`. This says nothing about the diagram's quality; it is the metamodel's constraint, and the relationship is *not* an unknown.

Merging them makes the headline move for the wrong reasons. On the specimen, recovering the five emphasis arrows **improved** the drawn-arrow figure, yet the overall Association share *rose* — because eight containment relationships had fallen back structurally and were counted in the same bucket. A reader would have read that rise as the model getting vaguer, when the opposite had happened.

**Rule: report the two separately** (*Final report* §6), and **name every structural fallback** (`contains` is a reasonable name) so it is distinguishable in the model as well as in the report. `Composition` is most often refused from `Node` into `ApplicationComponent` and from `Node` into `CommunicationNetwork`.

**Do not predict this bucket's size at all — measure it.** Three runs over the same file measured **8, then 0, then 6**, and none was wrong: the count depends entirely on which containers ended up typed `Node`, which shifts with the shape-token reading. This prompt has now guessed the figure twice — once as "expect it non-empty", once as "usually empty" — and been wrong both times, in opposite directions. A run that carries either expectation into its report will describe the file it expected rather than the one it read.

The mechanism is worth knowing even though the count is not predictable: the decision table makes most containers a `Grouping`, and `Grouping` composes into anything, so fallbacks arise exactly where a **`Node` is a parent** — a Region holding a service directly, or a host holding an application component. If your fallback count is high, that is telling you the diagram nests services under regions rather than under zones, which is a real property of the drawing worth a sentence in the report.

Report the count you actually got, zero included, and never carry a figure over from a previous run.

### Deployment is containment

Per the technology-deployment recipe: nest a deployed member inside its host and **do not draw the assignment**. Exclude `Assignment` and `Composition` from the view's relationship filter where nesting already conveys them.

Note that `Assignment` is legal from `Node` to `SystemSoftware` and to `Artifact`, but **not** from `Node` to `ApplicationComponent`. For an application component deployed on infrastructure, nest it, or use `Realization` from the `Artifact` that implements it.

---

## COMPANION TEXT

A diagram of this kind usually travels inside a document, and that prose carries most of the semantics the picture lacks: what the unlabelled edges mean, what the unnamed boxes are, which duplicates are the same thing, and what the numbered step markers index.

**Accept a path or pasted prose. Require neither.**

### Precedence

| Question | Authority |
|---|---|
| Does this element exist? | **Picture** |
| What contains what? | **Picture** (geometry), unless the prose contradicts it *explicitly* |
| What type is it? | **The shape table**, from the token. The prose **corroborates** it (⇒ `stated`) or **contradicts** it (⇒ report the conflict, stay `inferred`) — see *The corroboration test*. Prose that names a type outright is rare and is simply the strongest form of corroboration |
| What relationship, and which direction? | **Prose.** The picture has almost no signal |
| What is this unnamed box? | **Prose only** — the picture cannot answer |

**Report every conflict; resolve none silently.** A diagram that disagrees with its own design document is a finding the architect wants surfaced.

### What the prose is for, beyond types

Precedence answers *"who wins when they disagree"*. It is not the only thing a document is good for. Design prose carries **rationale** — the constraint that forced a component to exist, the option that was rejected, the boundary a control enforces — and none of that is derivable from any picture. Capture it as the `Purpose:` documentation line (*Provenance marking*, channel 2) on the elements it speaks about.

Rationale attaches to an element the document **discusses**; it never creates one (G7). A paragraph explaining a component the picture does not draw is an unmatched prose-only element and is reported as one — its rationale goes in the report, not into a model object.

### The drift failure mode

Documents and pictures fall out of sync. If the prose names elements that are absent from the picture, **report them as unmatched — do not create them** (G7). The model must not contain things the diagram does not depict. If the prose describes a materially different architecture, say so prominently and ask whether you have the right pair of inputs.

---

## ANNOTATIONS — what is not architecture

| Artifact in the file | Ruling |
|---|---|
| Numbered step markers (bare digits in circles) | **Not elements.** Render as a view note when the companion text supplies the walkthrough they index; otherwise drop and report. **Exclude them from endpoint recovery** (trap 5) — they sit exactly where an arrow ends and will win a nearest-shape contest they have no business entering |
| Free-floating text near a container | **Read it as the container's title** where the adjacency test in *Container semantics* is met. Consumed as a title, **not** created as an element or a note |
| Free-floating text near an edge | Attach as that relationship's **name** when within a tight proximity threshold; otherwise a view note. **Report which choice was made, per item** |
| Unnamed rectangles enclosing content | Try the title-adjacency test first (*Container semantics*). Still unnamed ⇒ ask (Phase 3.5); unresolved ⇒ view-only group with `label: ""` (untitled), **no model element** |
| Edges with no `source`/`target` attribute | **Not automatically dangling.** Run trap 5 endpoint recovery first (G15). Drop only what recovery fails to resolve, and report which end failed |
| `shape=flexArrow` and other heavy/emphasis arrows | **Not a decoration marker — do not drop on style.** A fat arrow is an author signalling *importance*; it is usually the primary path. Disposition it on its endpoints like any other edge (G15) |
| Duplicate edges (same type, same endpoints) | Deduplicate. Report the count |
| Title blocks, legends, logos, revision boxes | Drop. Report |

**Proximity thresholds are not specified here on purpose.** Tune them against the diagram in front of you and state what you used. An invented pixel constant would look authoritative and mean nothing.

**Annotate LAST (gate G11).** The legend, notes, captions and images are the **final** action on a view. Only once every layout, routing, resize and `add-to-view` pass on that view is finished may a note be placed. A note's position is computed from the content bounding box **once** and stamped as absolute coordinates — nothing re-anchors it afterwards — so any geometry-mutating call that follows can grow a container straight under it. This also keeps G12 satisfiable: a note added after the close-out export is a view-mutating call following that export, which G12 forbids. If a later geometry change is genuinely unavoidable, remove or re-place the annotation *after* it, never before, then re-arm G12 (re-assess + re-export).

**Note sizing (gate G11).** When calling `add-note-to-view` — or any `update-view-object` that changes a note's `text` or `width` — **omit `height` entirely**. The server fits a note's height to its wrapped text only when `height` is absent; passing one takes the fixed-size branch and clips the text. The fit is symmetric on update: shorter text shrinks the note back down (floor 80, cap ~600). A call that only *moves* a note does not re-fit it, so a note whose height was once pinned is un-pinned by re-sending its `text` (or `width`) with `height` omitted. You may set `width` for uniform legends. Prefer `position: above-content`: both anchors are stamped once as absolute coordinates and neither tracks the content afterwards, so what matters is which edge of the content box moves later — and containers grow **downward**, moving `maxY` while `minY` stays put. A `below-content` note is therefore the one a later container growth overruns.

---

## PROVENANCE MARKING (mandatory)

This model's central risk is not that it is wrong — it is that a guess is indistinguishable from a reading. A model where every relationship silently became `Association` and every container silently became `Grouping` looks exactly like a carefully-reasoned one.

**Grade by the weakest link.** An element whose *existence* was read but whose *type* was guessed is `inferred`, not `stated`.

**Know what that costs on a diagram-only run.** Weakest-link grading plus a type that always comes from the shape table means **no element can reach `stated` without a companion text**. A diagram-only run therefore reports zero `stated`, every time — and that is the scale working, not a bug in it. Say so in the report rather than leaving a reader to wonder whether the run simply failed to read anything.

**What lifts an element to `stated` is corroboration, not a type name in the prose.** Do not wait for a document to say "this is a `Node`" — no real design document does, and a scale whose top level requires one is a two-level scale wearing three labels. The document earns `stated` by *agreeing with the picture* about what a thing is. See *The corroboration test* below.

So that the signal is not lost, **report existence and type separately** as well as the weakest-link grade (see *Final report* §5). An element whose label and box were read straight from the file has `stated` existence even when its type is `inferred`, and a reader who wants to know "how much of this model is really in the picture" is asking about that column, not the headline one.

### Three levels

| Level | Glyph | Meaning |
|---|---|---|
| `stated` | `●` | Read directly — a label in the file, an explicit `container="1"` parent — **or corroborated: the picture and the companion text independently resolve to the same answer** |
| `inferred` | `◐` | Derived by rule from **one** source — shape-token lookup with no corroboration, geometric containment, label matched through the table, identity merge, intent-ladder relationship |
| `assumed` | `○` | A fallback fired — unmapped token, `Association` floor, unresolved collision, dropped-and-guessed annotation |

### The corroboration test

Two sources, read independently, agreeing. Run it per element, after types are known.

1. **The picture's answer.** The token extracted from the cell, resolved through the *Shape-token mapping* table to a `(type, specialization)` pair. An untokened cell whose *label* resolved through the table is **not** eligible — that is one source used twice, not two sources.

   **Containers are included, and take their answer from the *container decision table*.** Read narrowly — "the table" meaning only the shape-token table — no container could ever be corroborated, because container types come from the decision table instead; on a cloud diagram that silently bars the regions, VPCs and accounts that form the whole skeleton of the model. They are in scope, on the same terms as everything else.

   **But the eligibility rule applies to them unchanged, and it bites hardest here.** A container is eligible only where its decision-table row was reached from **evidence in the picture** — its stencil token, or a container badge's token. A container whose row was reached from its **title text** — including any rectangle named by the title-adjacency test — is **ineligible**, because the document names it by that same title and the two "sources" are one. Report those containers as ineligible with a count; do not quietly grade them `inferred` as though the document had simply been silent.

   **Report the ineligible count in §5 whichever way each element fell.** It is the figure that says how much of the `stated` grade was actually available to be earned, and without it a reader cannot tell a low `stated` count from a narrow eligibility pool.
2. **The document's answer.** The companion text names the same component and describes it in terms that resolve to a row of the same table. Match the component the way icons are matched — strip vendor prefixes from both sides, compare on the remaining words, **take only a confident match** — and say what you matched to what.
3. **Both resolve to the same `(type, specialization)` pair ⇒ type is `stated`.** Same type but a different specialization ⇒ `inferred`, **and report the split**: it usually means the table needs a row, not that either source is wrong.
4. **They disagree on the type ⇒ `inferred`, never silently resolved.** Report it as a companion-text conflict. The picture still governs existence and containment; a disagreement about *what a thing is* is a finding the architect wants, and it is the reason this test reports rather than decides.
5. **The document is silent about the component ⇒ `inferred`.** Silence is not disagreement and is not a defect — most elements on most diagrams will land here.

**A corroborated type does not lift existence or name.** Weakest-link still governs: an element with a `stated` existence, an `assumed` name derived from a bare icon's token, and a corroborated type is still `assumed`. Corroboration removes the *structural* block on `stated`; it does not exempt an element from the rest of the ladder.

**Report the corroborated count separately** (*Final report* §5) — it is the figure that says how much the document and the drawing actually agree, which is a different and more interesting question than how much of either was read.

### Four channels

**1. Property (canonical — always).** Every element gets `provenance` = `stated` | `inferred` | `assumed`, plus `provenanceMark` (the glyph), plus `source` naming what justified it — the cell id, the stencil token, or the companion-text heading. Set these at create time via `create-element` / `bulk-mutate`.

**Relationships carry the same properties, in the same call.** `create-relationship` accepts `properties`, `documentation` and `source` exactly as `create-element` does, so mark a relationship **at create time** — no second pass is needed. (`update-relationship` also accepts `documentation` and `properties` if you need to change them afterwards; a create that matches an existing relationship dedupes and does **not** overwrite what that relationship already holds.) Do **not** leave relationships unmarked: the ladder's floor and a structural fallback are indistinguishable in the model without it, which is exactly the confusion this section exists to prevent.

**2. Documentation lines (human-readable and searchable).** Append to each element's `documentation`, as its **first** line:

```
Provenance: <level> — <source>
```

`search-elements` matches documentation text, so a reviewer can pull every `assumed` element with one search.

**Where the companion text says why the element is there, add a second line immediately below it:**

```
Purpose: <one sentence> — <the heading it came from>
```

Rules, because this line is the one most likely to drift into invention:

- **Only from the companion text.** Never from the picture, never from your own knowledge of the vendor's product. An element the document does not discuss gets no `Purpose:` line, and that is the common case.
- **One sentence, quoted or closely paraphrased**, naming the constraint or the role — not a restatement of the element's own name. *"Proxies ALB requests to the internal API Gateway because AgentCore cannot validate a private-CA certificate"* earns its place; *"An AWS Lambda function"* does not.
- **Name the source heading**, so a reader can go back to the document. Same discipline as `source` on channel 1.
- **It is not a grade and it does not affect one.** A `Purpose:` line on an `assumed` element is normal — the document explained the role of a thing whose type you still had to guess.

This is **guidance, not a gate** — a run with no companion text writes none of these — but where a document exists it is the cheapest way to carry the *why* into a model that otherwise records only the *what*.

**3. Specialization stereotype (optional).** The domain specialization from the shape table normally owns the single primary-specialization slot, so **do not spend it on provenance**. Channels 1, 2 and 4 already carry it.

**4. Label expression glyph (gate G9).** After placing an element, set its `labelExpression` via `update-view-object` to:

```
${name} ${property:provenanceMark}
```

One glyph after the name — it never overflows a normal box, and the label-truncation check stays clean. The only licensed degradation is a *rendering* failure, never an opt-out: if the deployment shows a literal `${...}`, fall back to ASCII `[s]` / `[i]` / `[a]`, still on every object.

**Legend — every view decodes itself.** Append to the view's note, as its last line:

```
Legend: ● stated (read from the diagram or its document) · ◐ inferred (derived by rule) · ○ assumed (a fallback fired)
```

---

## MIRROR-MODE GEOMETRY

For `{{MODE}} = mirror`, and for the mirror view of a `both` run. Skip entirely in `semantic` mode.

1. **Resolve** every vertex to an absolute rectangle (trap 3, step 1).
2. **Translate** so `min(x)` and `min(y)` are ≥ 0 (trap 4).
3. **Scale** uniformly if the canvas is large. Preserve aspect ratio; never scale axes independently.
4. **Grow the boxes.** This is the step that makes or breaks the mode. Source icons are small with labels outside; ArchiMate boxes are larger with labels inside. Expand each element to at least the default 120×55, keep its **centre** on the original centre, then run `resize-elements-to-fit` on containers so parents accommodate the grown children.
5. **Re-derive containers** from the grown children rather than copying the source container rectangles — a source container sized for 30×30 icons cannot hold 120×55 boxes.

   **The mirror view nests**, using `parentViewObjectId` on the containment tree, with each child's coordinates converted from absolute source geometry to parent-relative. Step 4 presupposes this: without nesting there are no children to fit. **Sibling containers are allowed to overlap** — do not clamp them apart. That is not a bug to fix, it is the mode working: where the source's own placement contradicts the containment tree, the overlap is exactly the contradiction, and flattening it away would hide the thing the mirror exists to show. On the specimen a VPC overflows its region by 30 px and therefore resolves as the region's *sibling*; the semantic view shows them side by side, the mirror shows them overlapping, and the difference is the finding.
6. **Route conventionally.** Do not attempt to reproduce source bendpoints; run `auto-route-connections` after placement. Copied bendpoints computed for a different geometry produce worse paths than a fresh route.
7. **Verify by eye** (G12) and state the fidelity limit in the report (G13).

---

## CONSTRAINTS & GUARDRAILS

- **Read-only until Phase 5** (G1). The entire analysis precedes the first write. A mutation issued while you are still deciding is a mutation you will want to undo.
- **Never invent an element** to make a diagram tidier or a story complete (G7). Absence is a finding.
- **Never silently normalise a contradiction** in the source hierarchy. Reproduce and report.
- **Batch the writes.** Use `bulk-mutate` / `begin-batch` for volume, but remember that a queued mutation is *not applied* — read back effective state after execution rather than trusting a projection.
- **Re-query IDs after any structural change.** Never infer a view-object id from batch position or memory; get it from `get-view-contents`.
- **The render is authoritative** (G12). `assess-layout` is a strong signal, not the verdict. Export and look.
- **One view per run** unless the user asks otherwise — `{{MODE}} = both` *is* the user asking, and it yields exactly two views over one element set. Splitting a diagram into more than that is a modelling decision the user should make, not one you make to tidy a crowded canvas.
- **Do not delete or modify anything you did not create** in this run.
- **The first generation is a draft.** Present it as one.

---

## FINAL REPORT (end your run with this)

Every section present; "none" where there is nothing (G14).

**1. Inputs.** File, page name, mode, whether companion text was supplied. In `both` mode, name the two views created.

Sections 2–9 are **model-level**: state them once, however many views were built. Sections 10 and 11 are **per view**.

**2. Parse counts.**
- vertices, edges parsed
- wrapper cells recovered (G3)
- edge labels recovered from child cells, and how many sat on dangling edges (G4)
- vertices whose visual container differed from their declared parent (G5)
- edges needing endpoint recovery, as **two figures, because an edge can be missing one id or both**: edges carrying *neither* id (how many had both ends recovered, how many failed) and edges carrying *one* id (how many had the missing end recovered, how many failed) — with the distance and margin thresholds used (G15). Reporting a single "both endpoints recovered" figure cannot describe a half-dangling edge, and the specimen has one.
- text cells consumed as container titles, with the proximity threshold used (G16)

**3. Disposition** — must sum to the parsed vertex count (G6), all seven buckets. **Print the parsed total and the sum side by side.** If they differ, you have narrowed the population somewhere; say where, and do not report the gate as passed:
- elements created · merged into another (with which) · dropped as annotation · dropped as decoration · consumed as a container title · **consumed as an edge label** (which edge each titled) · rendered as a view-only group with no model element

**4. Edges:** structural (with ids) · structural (endpoints recovered geometrically) · dangling dropped after recovery failed · duplicates removed. **No "decorative dropped" bucket** — an arrow is dispositioned on its endpoints, never on its style.

**5. Provenance counts.** Elements and relationships at each of `stated` / `inferred` / `assumed`. **Lead with this.** If `assumed` dominates, say so in a sentence a reader cannot skim past. Break out **existence** and **type** grades alongside the weakest-link grade, and — on a diagram-only run — state plainly that zero `stated` is the expected result of weakest-link grading, not a failure to read the file.

Where a companion text was supplied, add the **corroboration figures**, which are the point of having one: elements whose type the document **confirmed**, elements it **contradicted** (each named, with both answers), elements it was **silent** about, and elements **ineligible for corroboration** — those whose picture-answer and document-answer would be the same source used twice (an untokened cell typed from its own label; a container typed from its title text). The four sum to the element count.

**The ineligible figure is not optional and is not the same as silence.** Silence means the document had nothing to say; ineligibility means the test could not legitimately be run. Without it a reader cannot tell a low `stated` count from a narrow eligibility pool, and those call for opposite responses — the first asks for a better document, the second for better shape evidence. A supplied document that confirms nothing is itself a finding: either the match discipline is too strict, the eligible pool is tiny, or the pair of inputs is wrong.

State how many elements carry a `Purpose:` line and how many do not. A low count against a long document usually means the rationale is written about layers rather than components, which is worth saying — it tells the next reader where the document's value actually sits.

**6. Relationship types created,** with the `Association` proportion broken into its **three** figures — never one:
- over **all** relationships
- over relationships **derived from drawn arrows** only — the figure that says how much the picture actually told you
- **structural fallbacks**: containment you inferred correctly where ArchiMate refused `Composition`, listed with the source/target type pair that caused each refusal

The first figure is meaningless without the other two, because they move independently and in opposite directions.

**7. Shape tokens.** Separate lists, never merged — report every one that applies:
- tokens present but unrecognised — so the table can grow
- tokens for which **no icon asset was found**, and whether icons were placed at all (*Carrying the stencil icon onto the element*)
- untokened cells whose **label** resolved through the table, with which row each hit
- untokened cells whose label resolved to **nothing** and fell to `Node`
- bare icons named from their token, with the derived name — these are the ones a human most wants to rename

**8. Open questions for the human:**
- identity **merges performed**, with the rule and evidence for each
- unresolved identity collisions
- container titles adopted from adjacent text cells, each pairing named
- unnamed containers left untitled (`label: ""`), with the count
- direction flips made on weak evidence
- relationships whose endpoints were recovered by proximity rather than read
- hierarchy contradictions found in the source
- **near-miss containments resolved** (*Container semantics*): each vertex attached (orphaned case) or re-parented (mis-parented case), with the container, the percentage inside, the pixel spill and the edge it spilled on — and for every re-parent **the parent it was moved off**, since strict enclosure had already given it one and the move is otherwise invisible. Separately: any vertex left exactly where strict enclosure placed it because two disjoint containers qualified, none did, or the type pairing was incoherent. **Report the two cases with their own counts** — a single total hides whether the mis-parented case ran at all
- companion-text conflicts, and prose-only elements **not** created (G7)

**9. Flattening.** The depth allowed per branch and why, and which levels were dropped from the view and carried as properties.

**9a. Orphans (G17).** Elements created holding **zero relationships**, queried from the model rather than from your build log — each named, with why it is isolated. "None" is the expected answer and is the only one that needs no explanation.

**10. Verification — per view.** For each view built: its `assess-layout` result, and confirmation the PNG was exported and examined (G12). Two views ⇒ two of each. A single merged verification line for a `both` run does not satisfy G12. **On a mirror view, report the overlap split** — how many overlapping pairs are faithful to the source and how many the growth step created (Phase 8 step 2).

**11. Mode limits.** Wherever a mirror view was built (`mirror` or `both`), the fidelity statement (G13). In `both` mode, add one sentence on **what the two views disagree about** — which is the interesting output of the mode: where the source's own placement contradicted the containment tree, the semantic view shows the derived structure and the mirror view shows what the author drew.

**12. Gate exceptions.** Any gate not met: which, where, why. "None" if none.

Close with one sentence naming the single thing that would most improve a re-run — usually the companion document, a named unnamed container, or a resolved collision.
