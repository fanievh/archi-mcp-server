---
title: Bibliography
description: Numbered research bibliography for the layout engine and routing pipeline.
---

# Bibliography

This file is the canonical reference list for the algorithmic foundations of the layout engine and the routing pipeline. The technical documents [Routing Pipeline](routing-pipeline.md) and [Layout Engine](layout-engine.md) cite into this list by number, e.g. `[4]` linking to entry [4] below.

Entries marked **empirical** in the per-stage map at the end of this document have no academic basis cited because the implementation is a project-specific contribution or a vendor-compatibility adaptation.

## How to cite

In any technical document under `docs/`, write `[N]` inline (CommonMark shortcut reference link), and add a single line at the bottom of the file:

```markdown
[N]: bibliography.md#ref-N
```

That renders `[N]` as a clickable link to the matching entry below.

## References

<a id="ref-1"></a>

### [1] Wybrow, Marriott, Stuckey (2009) — Orthogonal Connector Routing

Wybrow, M.; Marriott, K.; Stuckey, P. J. (2009). *Orthogonal Connector Routing.* In Eppstein, D.; Gansner, E. R. (Eds.), *Graph Drawing 2009*, LNCS 5849, Springer.

[Paper PDF](https://users.monash.edu/~mwybrow/papers/wybrow-gd-2009.pdf)

The foundational visibility-graph + A* + ordering-and-nudging pipeline this project descends from.

<a id="ref-2"></a>

### [2] Marriott, Stuckey, Wybrow (2014) — Seeing Around Corners

Marriott, K.; Stuckey, P. J.; Wybrow, M. (2014). *Seeing Around Corners: Fast Orthogonal Connector Routing.* In *Diagrams 2014*.

[Paper PDF](https://users.monash.edu/~mwybrow/papers/marriott-diagrams-2014.pdf)

The 1-bend visibility-graph optimisation that constrains the search space without sacrificing optimality.

<a id="ref-3"></a>

### [3] Wybrow (2005) — Incremental Connector Routing

Wybrow, M. (2005). *Incremental Connector Routing.* In *Graph Drawing 2005*.

[Paper PDF](https://users.monash.edu/~mwybrow/papers/wybrow-gd-2005.pdf)

Persistent visibility-graph maintenance pattern. Background reference for incremental rerouting (not used in the current batch-mode pipeline but documented as future work).

<a id="ref-4"></a>

### [4] Hegemann, Wolff (2023) — A Simple Pipeline for Orthogonal Graph Drawing

Hegemann, T.; Wolff, A. (2023). *A Simple Pipeline for Orthogonal Graph Drawing.* In *Graph Drawing 2023*. arXiv:2309.01671.

[arXiv](https://arxiv.org/abs/2309.01671)

Channel-centring + LP-based proportional nudging. Direct algorithmic basis for the `ChannelNudgingPass` and the proportional-spacing fallback in `CoincidentSegmentDetector`.

<a id="ref-5"></a>

### [5] Bereg, Holroyd, Nachmanson, Pupyrev (2016) — Edge Routing with Ordered Bundles

Bereg, S.; Holroyd, A. E.; Nachmanson, L.; Pupyrev, S. (2016). *Edge Routing with Ordered Bundles.* *Computational Geometry: Theory and Applications*, 52, 18–33. DOI:10.1016/j.comgeo.2015.10.005.

[arXiv](https://arxiv.org/abs/1209.4227)

Metro-line crossing minimisation (MLCM). Formal basis for ordering connectors that share corridor segments (used by `PathOrderer`).

<a id="ref-6"></a>

### [6] Hart, Nilsson, Raphael (1968) — A Formal Basis for the Heuristic Determination of Minimum Cost Paths

Hart, P. E.; Nilsson, N. J.; Raphael, B. (1968). *A Formal Basis for the Heuristic Determination of Minimum Cost Paths.* *IEEE Transactions on Systems Science and Cybernetics*, 4(2), 100–107. DOI:10.1109/TSSC.1968.300136.

The original A* algorithm. The Manhattan-distance heuristic used by `VisibilityGraphRouter` is admissible by the argument made in this paper.

<a id="ref-7"></a>

### [7] Sugiyama, Tagawa, Toda (1981) — Methods for Visual Understanding of Hierarchical System Structures

Sugiyama, K.; Tagawa, S.; Toda, M. (1981). *Methods for Visual Understanding of Hierarchical System Structures.* *IEEE Transactions on Systems, Man, and Cybernetics*, 11(2), 109–125. DOI:10.1109/TSMC.1981.4308636.

The four-phase layered/hierarchical layout pipeline (cycle removal → layer assignment → crossing minimisation via barycentre/median heuristics → coordinate assignment). Used by Eclipse ELK Layered (`auto-layout-and-route` mode `auto`) and the `optimize-group-order` barycentric heuristic.

<a id="ref-8"></a>

### [8] Fößmeier, Kaufmann (1996) — Drawing High Degree Graphs with Low Bend Numbers

Fößmeier, U.; Kaufmann, M. (1996). *Drawing High Degree Graphs with Low Bend Numbers.* In *Graph Drawing*, LNCS 1027 (Kandinsky model). Bend-minimisation extension follows in *Graph Drawing 2007*, LNCS 4875.

[Springer (LNCS 4875)](https://link.springer.com/chapter/10.1007/978-3-540-70904-6_33)

The Kandinsky orthogonal-layout model for vertices of degree > 4 — relevant background for ArchiMate hubs with seven or more connections. Background reference for hub-face redistribution in `EdgeAttachmentCalculator`.

<a id="ref-9"></a>

### [9] McMurchie, Ebeling (1995) — PathFinder

McMurchie, L.; Ebeling, C. (1995). *PathFinder: A Negotiation-Based Performance-Driven Router for FPGAs.* In *FPGA '95*.

[IEEE Xplore](https://ieeexplore.ieee.org/document/1377269/)

Sequential routing with incremental congestion feedback. The architectural basis for the corridor-diversity work: `CorridorOccupancyTracker`'s multiplicative occupancy penalty is a single-pass simplification of PathFinder's negotiation-based congestion model.

<a id="ref-10"></a>

### [10] Eclipse Layout Kernel (ELK) — Layered Algorithm

Eclipse Foundation. *ELK Layered Algorithm Reference.*

[Algorithm reference](https://eclipse.dev/elk/reference/algorithms/org-eclipse-elk-layered.html) · [Plain Java Layout API](https://eclipse.dev/elk/documentation/tooldevelopers/usingplainjavalayout.html)

The actual layered-layout engine wrapped by `ElkLayoutEngine`. Implements Sugiyama-style layered layout with orthogonal edge routing, compound-graph (nested-group) support, and Brandes–Köpf coordinate assignment.

<a id="ref-11"></a>

### [11] Brandes, Köpf (2001) — Fast and Simple Horizontal Coordinate Assignment

Brandes, U.; Köpf, B. (2001). *Fast and Simple Horizontal Coordinate Assignment.* In *Graph Drawing 2001*, LNCS 2265, 31–44. DOI:10.1007/3-540-45848-4_3.

Horizontal-coordinate-assignment phase used internally by ELK Layered ([10]). Background reference for the layered-layout output the pipeline consumes.

<a id="ref-12"></a>

### [12] libavoid (Adaptagrams)

Wybrow, M.; Adaptagrams Project, Monash University (ongoing). *libavoid — orthogonal connector routing reference implementation.*

[Library overview](https://www.adaptagrams.org/documentation/libavoid.html) · [`router.h` source](https://www.adaptagrams.org/documentation/router_8h_source.html)

The canonical imperative implementation of the Wybrow 2009 / 2014 algorithms. Used as a code-level blueprint; the `performUnifyingNudgingPreprocessingStep` pattern in `orthogonal.cpp` directly informed the channel-grouping primitive in `ChannelNudgingPass`.

<a id="ref-13"></a>

### [13] Liang, Barsky (1984) — Line Clipping

Liang, Y.-D.; Barsky, B. A. (1984). *A New Concept and Method for Line Clipping.* *ACM Transactions on Graphics*, 3(1), 1–22. DOI:10.1145/357332.357333.

The line-segment-vs-rectangle clipping test used by `lineSegmentIntersectsRect()` for obstacle pass-through detection in `LayoutQualityAssessor` and the legacy `ConnectionRouter`. Named in source comments.

## Modelling and visual-design references

> **Scope note.** Entries [14] and [15] are *not* algorithmic foundations of the layout engine or routing pipeline. They are practitioner sources for ArchiMate diagramming conventions, and they ground the LLM-facing guidance in `archimate://reference/archimate-view-patterns` ("ArchiMate Modelling & Aesthetic Best Practices") — the view-*setup* advice an agent applies before routing, not the routing algorithm itself. They are listed here so the project keeps a single canonical reference list.

<a id="ref-14"></a>

### [14] Hosiaisluoma — ArchiMate Cookbook (Patterns & Examples)

Hosiaisluoma, E. *ArchiMate® Cookbook — Patterns & Examples.* Aligned to ArchiMate 3.2; continuously updated.

[Cookbook PDF](http://www.hosiaisluoma.fi/ArchiMate-Cookbook.pdf) · [Companion blog](https://www.hosiaisluoma.fi/blog/archimate/)

Free, redistributable. Source of the top-down layer-banding convention (customers/actors → Business → Application → Technology), the swimlane-as-large-container-with-nested-members pattern, the Grouping-element clustering pattern, the conventional layer colour palette, and the viewpoint "80% rule" for element selection. The most directly automatable layout guidance of the two.

<a id="ref-15"></a>

### [15] Wierda — Mastering ArchiMate (Edition 3.x)

Wierda, G. *Mastering ArchiMate, Edition 3.x.* R&A. ISBN-13 978-9081984096 (Edition III).

[Book site](https://ea.rna.nl/the-book/)

Paid book (paraphrased only — no extended quotation). Source of the explicit minimize-line-crossings heuristic, grid-and-mutual alignment discipline, whitespace-to-imply-grouping guidance, and the nesting-vs-explicit-connector trade-off (nesting compresses detail but is ambiguous about the implied structural relationship).

## Per-stage citation map

Each entry below names the source class plus the references that ground it. **Empirical** marks stages where the design is a project contribution or a vendor-compatibility adaptation with no published academic source.

### Routing pipeline

| Stage | Source class | References |
|------|-------------|-----------|
| Visibility-graph construction (corner expansion, scan-line projection, perimeter boundary nodes) | `OrthogonalVisibilityGraph` | [1], [2], [12] |
| A* path search with direction-tracking state | `VisibilityGraphRouter` | [1], [6], [12] |
| Bend-penalty cost term | `VisibilityGraphRouter` | [1] |
| Clearance-weighted cost (perpendicular obstacle clearance, `MAX_EFFECTIVE_CLEARANCE` cap) | `VisibilityGraphRouter` | empirical (libavoid notably does **not** use inverse-distance clearance) |
| Corridor directionality (cosine-based penalty) | `VisibilityGraphRouter` | empirical |
| Group-wall clearance cost | `VisibilityGraphRouter` | empirical |
| Corridor occupancy / diversity (multiplicative penalty) | `CorridorOccupancyTracker` | [9] (single-pass simplification of PathFinder) |
| Path simplification (greedy farthest-reachable-point shortcutting; obstacle-aware reachability, **not** Douglas-Peucker tolerance) | `RoutingPipeline.simplifyFinalPath` | empirical |
| Path ordering (parallel-segment grouping, perpendicular-endpoint sort) | `PathOrderer` | [1], [5] |
| Edge nudging (greedy distribution, corridor-bound clamp) | `EdgeNudger` | [1], [4] |
| Coincident-segment detection (proportional spacing fallback to fixed delta) | `CoincidentSegmentDetector` | [4] (proportional-spacing LP, simplified to closed-form division) |
| Channel-global ordered nudging | `ChannelNudgingPass` | [4], [12] |
| Label clearance / label positioning | `LabelClearance`, `LabelPositionOptimizer` | empirical |
| Terminal edge attachment — Phase 1.1 hub face redistribution | `EdgeAttachmentCalculator` | [8] (Kandinsky background); fix is project-specific |
| Terminal edge attachment — Phase 1.2 natural approach direction | `EdgeAttachmentCalculator` | empirical |
| Terminal edge attachment — Phase 1.3 pass-through-aware face selection | `EdgeAttachmentCalculator` | empirical |
| Stage 4.7f self-element pass-through safety net | `RoutingPipeline` | empirical |
| Stage 4.7g late-stage path simplification | `RoutingPipeline` | empirical |
| Stage 4.7h post-simplification coincident resolution | `RoutingPipeline` (reuses `CoincidentSegmentDetector`) | [4] |
| Stage 4.7i path straightening | `PathStraightener` | empirical |
| Stage 4.7k center-termination fix | `RoutingPipeline.fixCenterTerminatedPath` | empirical (Archi `ChopboxAnchor` compatibility) |
| Stage 4.7m interior terminal BP fix | `RoutingPipeline.fixInteriorTerminalBPs` | empirical |
| Stage 4.7n orthogonality enforcement safety net | `RoutingPipeline.enforceOrthogonalPaths` | empirical |
| Stage 4.7p source-self-hug correction (v1.4) | `RoutingPipeline` | empirical |
| Stage 4.7q coincident-regression surgical fix (v1.4) | `RoutingPipeline` | [4] (channel-centring rationale) |
| Endpoint pass-through correction (uses [13] for intersection test) | `RoutingPipeline.correctEndpointPassThroughs` | [13] for the line-clipping primitive; correction strategy is empirical |
| Corridor re-route (Stage 5a) | `RoutingPipeline` | empirical |
| Fallback edge port strategy | `RoutingPipeline.calculateAlternativeEdgePorts` | empirical |
| Auto-nudge on route failure | `ArchiModelAccessorImpl` | empirical |
| Recommendation engine | `RoutingRecommendationEngine` | empirical |
| Best-of-K seeded multi-start (never-worse-by-construction, K=12) | `BestOfKRoutingStrategy` | empirical (project contribution) |
| Hub-perimeter routing stage | `HubPerimeterRoutingStage` | [8] (Kandinsky high-degree-vertex background); stage is project-specific |
| Terminal-segment corridor migration | `TerminalSegmentCorridorMigrator` | [4] (channel-centring rationale); migration strategy is empirical |

### Layout engine

| Stage | Source class | References |
|------|-------------|-----------|
| ELK Layered (positions + orthogonal routes for `auto-layout-and-route` mode `auto`) | `ElkLayoutEngine` | [7], [10], [11] |
| Hierarchical containment (compound-graph layout for nested groups) | `ElkLayoutEngine` two-pass | [7], [10] |
| Crossing minimisation — barycentric heuristic + adjacent-swap fallback | `CrossingMinimizer` | [7] for the barycentric heuristic; adjacent-swap is empirical |
| `optimize-group-order` (barycentric inter-group ordering) | `ArchiModelAccessorImpl` | [7] |
| `arrange-groups` topology arrangement (connection-density ordering) | `ArchiModelAccessorImpl` | empirical |
| Post-layout overlap resolution (horizontal/vertical sweep) | `OverlapResolver` | empirical |
| Hub element detection + sizing formula `baseDimension + 15·(connectionCount − 6)` | `ArchiModelAccessorImpl.detectHubElements` | [8] (Kandinsky background); formula itself is empirical |
| Auto-size at placement / `resize-elements-to-fit` (label-aware sizing, aspect-ratio targeting, dynamic containment label height) | `ArchiModelAccessorImpl` | empirical |
| `LayoutQualityAssessor` — overlaps, crossings, spacing, alignment, label overlaps, pass-throughs, coincident segments, non-orthogonal terminals | `LayoutQualityAssessor` | empirical aesthetic-criteria selection |
| Pass-through detection (line-segment vs element-rect with 10px inset) | `LayoutQualityAssessor` | [13] |
| Severity-tiered overall rating (Tier 1 critical / Tier 2 moderate / Tier 3 cosmetic; M6 two-dimensional rating in v1.4) | `LayoutQualityAssessor` | empirical |
| M1–M5 perception-aligned metrics + R8 corridor utilisation (assessor redesign, v1.4) | `LayoutQualityAssessor` | empirical (project contribution) |
| Label truncation / parent-label-obscured / image-sibling-overlap detections | `LayoutQualityAssessor` | empirical |
| Label position optimisation (greedy assignment, longest-path-first) | `LabelPositionOptimizer` | empirical |
| Spacing convenience-tool control loop (observe → decide → density-aware-terminate) + sound pre-routing infeasibility certificate | `SpacingControlLoop`, `SpacingPreconditionInfeasibilityCertificate`, `ComposerSpeculativeReplay` | empirical (project contribution) |

## Provenance summary

**Found in repository (planning artifacts, source comments):** [1], [2], [3], [4], [5], [7], [8], [9], [10], [12], [13].

**Standard reference works (canonical for the algorithm named in repository sources, not directly cited there):** [6], [11].

**Practitioner modelling/visual-design sources (ground the LLM-facing view-setup guidance, not the algorithm):** [14], [15].

## Caveats

- **Path simplification is not Douglas-Peucker.** `simplifyFinalPath` does greedy farthest-reachable-point shortcutting through obstacle-aware visibility, not perpendicular-distance polyline simplification. Citing Douglas-Peucker (1973) or Ramer (1972) here would be misleading; the stage is flagged as empirical.
- **Clearance-weighted A* is a deliberate departure from prior art.** Prior-art review found that libavoid does not use an inverse-distance clearance penalty. Present clearance-weighting as a project contribution, not a citation-grounded inheritance.
- **`optimize-group-order` adjacent-swap fallback** is closest in spirit to Eades-Lin-Smyth 1993 ("A fast and effective heuristic for the feedback arc set problem") but the connection is unverified. Listed as empirical pending future verification.
- **Brandes-Köpf [11]** is what ELK Layered uses internally; the project consumes ELK output rather than calling [11] directly. Listed for completeness.

---

**See also:** [Routing Pipeline](routing-pipeline.md) | [Layout Engine](layout-engine.md) | [Architecture Overview](architecture.md)
