# Recipe Relationship Audit

Every relationship the viewpoint recipe library prescribes, checked against the relationship matrix
Archi itself enforces.

**Scope.** The six pages under `net.vheerden.archi.mcp/resources/recipes/`. This list is the audited
set, and a build-fired guard reads it from this block — so it is machine-checked against the directory
rather than merely asserted here. Prose elsewhere in this document may name any other file freely; only
this block defines coverage.

```text
index.md
application-integration.md
behaviour-process-flow.md
motivation.md
roadmap-migration.md
technology-deployment.md
```

**Not in scope.** The four pages under `resources/reference/` are **not audited here**. They state
general modelling rules rather than prescribing specific pairs, and nothing in this document should be
read as a statement about them.

**Audited at** commit `1ada2d19`. The three corrections in the "Corrections" section below were made in
the same change that added this document; every other line describes the corpus as it stands.

---

## Why this document exists

The recipe corpus is executable guidance. An agent fetches a page, reads the relationship subset, and
calls `create-relationship` with what it finds. If a page names a pair the ArchiMate metamodel does not
permit between those element types, the call is rejected — and the agent has no way to know in advance,
because nothing gates the prose against the metamodel.

That is a control-surface problem, not a documentation-quality one: the caller cannot look at the model
and notice the instruction was wrong.

---

## How the answer was derived

The server's single validity gate is `ArchimateModelUtils.isValidRelationship`, backed by
`RelationshipsMatrix`, which loads `model/relationships.xml` from the `com.archimatetool.model` bundle.
That file is the authority, and it is readable from disk, so the audit is a deterministic sweep rather
than a sample of live probes.

Derived from **two independent copies**, parsed into `(source, target) -> relations` maps and compared:

| Copy | `relationships` version | rows |
|---|---|---|
| Archi 5.10 release tag (`release_5_10_0`), `com.archimatetool.model/model/relationships.xml` | `3.2` | 3844 |
| Archi 5.6 install, `plugins/com.archimatetool.model_5.6.0.…/model/relationships.xml` | `3.2` | 3844 |

The parsed maps are **equal**. There are **62 source concepts**, and 62 × 62 = 3844 — the matrix is
**total**: every ordered pair of concepts has a row, and an empty `relations` attribute means "nothing is
permitted", not "unknown". That totality is what makes a negative sweep sound.

Key letters, from the sibling `model/relationships-keys.xml`:

| letter | relationship | letter | relationship |
|---|---|---|---|
| `a` | Access | `o` | Association |
| `c` | Composition | `r` | Realization |
| `f` | Flow | `s` | Specialization |
| `g` | Aggregation | `t` | Triggering |
| `i` | Assignment | `v` | Serving |
| `n` | Influence | | |

`RelationshipsMatrix` treats an uppercase letter as a *derived* relationship and a lowercase one as
direct, but **no uppercase letter appears anywhere in the shipped file** and `isValidRelationship` tests
only for the letter's presence. The derived/direct split is therefore inert for validity: a lowercase
letter is a permitted relationship.

**Reproducing it.** Parse `<source concept="X">` and `<target concept="Y" relations="…"/>` into a map
keyed by `(X, Y)`, do it for both copies, assert the maps are equal, then test for a letter's presence.
The matrix is deliberately **not** read from the test suite: it lives outside the repo under an install
path that differs between a developer machine and CI, so a test that could not find it would skip — and a
skipped test is a green scan.

---

## Class A — pairs the corpus names in full

A pair is "fully named" when both endpoints resolve to an ArchiMate concept from the sentence itself.
The corpus named **28** before the corrections below and names **36** after them. **Every one is
permitted** — the audit found no impermissible fully-named pair anywhere in the six pages.

| # | Page | Relationship | Pair | `relations` | relies on |
|---|---|---|---|---|---|
| 1 | application-integration | Composition | `ApplicationComponent → ApplicationComponent` | `cfgorstv` | `c` |
| 2 | application-integration | Aggregation | `ApplicationCollaboration → ApplicationComponent` | `fgortv` | `g` |
| 3 | behaviour-process-flow | Triggering | `BusinessProcess → BusinessProcess` | `cfgostv` | `t` |
| 4 | behaviour-process-flow | Flow | `BusinessProcess → BusinessProcess` | `cfgostv` | `f` |
| 5 | behaviour-process-flow | Assignment | `BusinessRole → BusinessProcess` | `fiotv` | `i` |
| 6 | behaviour-process-flow | Assignment | `BusinessActor → BusinessProcess` | `fiotv` | `i` |
| 7 | behaviour-process-flow | Composition | `BusinessService → BusinessService` | `cfgostv` | `c` |
| 8 | behaviour-process-flow | Assignment | `ApplicationComponent → ApplicationService` | `fiortv` | `i` |
| 9 | motivation | Influence | `Driver → Goal` | `no` | `n` |
| 10 | motivation | Influence | `Assessment → Goal` | `no` | `n` |
| 11 | motivation | Influence | `Principle → Requirement` | `no` | `n` |
| 12 | motivation | Realization | `Requirement → Goal` | `nor` | `r` |
| 13 | motivation | Realization | `Outcome → Goal` | `nor` | `r` |
| 14 | motivation | Association | `Stakeholder → Driver` | `no` | `o` |
| 15 | motivation | Association | `Driver → Stakeholder` | `no` | `o` |
| 16 | motivation | Aggregation | `Driver → Driver` | `cgnos` | `g` |
| 17 | motivation | Aggregation | `Goal → Goal` | `cgnos` | `g` |
| 18 | roadmap-migration | Association | `Gap → Plateau` | `o` | `o` |
| 19 | roadmap-migration | Association | `Plateau → Gap` | `o` | `o` |
| 20 | roadmap-migration | Realization | `WorkPackage → Plateau` | `fort` | `r` |
| 21 | roadmap-migration | Realization | `Deliverable → Plateau` | `or` | `r` |
| 22 | roadmap-migration | Composition | `WorkPackage → WorkPackage` | `cfgost` | `c` |
| 23 | roadmap-migration | Composition | `Deliverable → Deliverable` | `cgos` | `c` |
| 24 | roadmap-migration | Triggering | `ImplementationEvent → WorkPackage` | `fot` | `t` |
| 25 | technology-deployment | Association | `Node → Node` | `cfgiostv` | `o` |
| 26 | technology-deployment | Association | `Node → CommunicationNetwork` | `fotv` | `o` |
| 27 | technology-deployment | Association | `Node → Path` | `fotv` | `o` |
| 28 | technology-deployment | Assignment | `Node → Artifact` | `aio` | `i` |
| 29 | technology-deployment | Assignment | `Node → SystemSoftware` | `cfgiortv` | `i` |
| 30 | technology-deployment | Composition | `Device → Device` | `cfgostv` | `c` |
| 31 | technology-deployment | Composition | `Node → Node` | `cfgiostv` | `c` |
| 32 | technology-deployment | Realization | `Artifact → ApplicationComponent` | `or` | `r` |
| 33 | index | Composition | `BusinessActor → BusinessActor` | `cfgostv` | `c` |
| 34 | index | Composition | `BusinessRole → BusinessRole` | `cfgostv` | `c` |
| 35 | index | Assignment | `BusinessActor → BusinessRole` | `fiotv` | `i` |
| 36 | index | Realization | `DataObject → BusinessObject` | `or` | `r` |

Rows 7, 8, 16, 17 and 33-36 are the eight pairs the corrections below *introduced*; the other 28 were
already named at `1ada2d19` and were re-checked, not assumed.

**What this table is a list of, and what it is not.** Rows are **distinct pairs**, not sites: a pair named on
two pages appears once, under the page that names it first. The corrected journey bullet, for example, names
`ApplicationComponent → ApplicationComponent` Composition, which is already row 1 under
application-integration and is not repeated. So the Page column says where a pair is *first* named, and the
row count is a count of pairs rather than of prescriptions.

**This is also not the same count as the build guard's.** `RecipeCopyContractTest` pins the number of
Assignment pairs written in **arrow form inside an `AssignmentRelationship` clause** — three, at the time of
writing. That is a scanner's reach, not the corpus's content: rows 5 and 6 above are real Assignment pairs
this document derives from prose (`role→process` plus the page's element subset) and the scanner cannot see,
because the arrow's left side is lower-case. Neither number is wrong; they answer different questions, and
the guard's own documentation says so.

**Pairs deliberately named as illegal.** Two pages state a prohibition rather than a prescription, and
both are correct:

| Page | Statement | `relations` | verdict |
|---|---|---|---|
| roadmap-migration | no Composition or Aggregation between `ImplementationEvent` and `WorkPackage`, either direction | `fot` / `fot` | correct — no `c`, no `g` |
| behaviour-process-flow | an `ApplicationComponent` is not composed of its `ApplicationService`s | `fiortv` | correct — no `c` |
| motivation | no Aggregation between a `Driver` and a `Goal`, either direction | `no` / `no` | correct — no `g` |

---

## Class B — universal claims the corpus makes

Both are **true**, established by sweeping all 62 concepts rather than by sampling.

| Claim | Where | Sweep | verdict |
|---|---|---|---|
| ArchiMate permits no Assignment into an `ApplicationComponent` from any concept | application-integration, technology-deployment | all 62 sources tested for `i` toward `ApplicationComponent` — **none** | **true** |
| ArchiMate has no "Path" relationship | technology-deployment | `Path` is a *concept* (62 source rows); no key letter names it | **true** |

---

## Class C — clauses that prescribe a relationship without naming its endpoints

This is where the risk actually sits, and a pair-by-pair check cannot see it. These bullets name a
relationship type and leave the element types to the reader. Sweeping **every** endpoint pairing the
page's own element subset admits separates the harmless from the hazardous:

**Which endpoint set the readings are counted over.** For C1 and C2 the clause says "within the support
layers", and the page's element subset distinguishes *journey steps* (`BusinessProcess`, `BusinessService`)
from *supporting* types (`BusinessService`, `ApplicationService`, `ApplicationComponent`). The counts below
sweep the **whole four-type element subset**, not the three types the phrase literally scopes to — the wider,
more conservative set, because an agent reading a page that lists four types has no reliable way to exclude
one. Over the literal three-type scope the figures are **8 of 9** and **6 of 9** rather than 8 of 16 and 12
of 16; the direction is identical and the correction's claims hold over both sets, which is why the wider
denominator was kept. It is stated here rather than left implicit, since a document about unstated scope
should not have any.

| | Site | Clause | readings | not permitted | status |
|---|---|---|---|---|---|
| C1 | behaviour-process-flow, journey nesting bullet | Assignment "within the support layers" | 16 (9 literal) | **15** (8 literal) | **corrected** |
| C2 | behaviour-process-flow, same bullet | Composition "within the support layers" | 16 (9 literal) | **12** (6 literal) | **corrected** |
| C3 | motivation, nesting bullet and Step 3 | Aggregation "of sub-concerns into a parent driver/goal" | 4 | **2** | **corrected** |
| C4 | technology-deployment, relationship subset | Serving "from a `TechnologyService` up to what it serves" | 62 | 26 | left as written |
| C5 | application-integration and behaviour-process-flow, Draw lists — **three clauses** | Flow / Serving over the page's own element subset | 24 (9 + 9 + 6) | **0** | left as written |
| C6 | index, organization-structure row | Composition and "owner-Assignment" over org units / roles | 8 | **5** | **corrected** |
| C7 | index, information-structure row | Realization over business objects / data objects | 4 | **3** | **corrected** |

**C5 is why this table is the deliverable and a pair list is not.** Under-specification is not itself a
defect: 24 readings across three Draw clauses are all permitted, and narrowing those bullets would be
churn in a published surface with no upside. The defect is under-specification *where the readings are
mostly rejected*, and only the sweep tells the two apart.

**Why C4 was left, and why C6 and C7 were not.** All three relied on the same weak protection — the
surrounding prose is directional and selects a permitted reading — and that is a materially more fragile
kind of safe than C5's, where every reading is permitted outright. C4's "up to what it serves" is
self-limiting *in the sentence itself*, so it stays. C6 and C7 leaned on something outside the clause: the
word "owner" implying actor-to-role, and a band order implying bottom-to-top. **A reader who follows the
prescription and not the layout hint gets a rejected call**, so both were corrected to name their pairs
outright rather than left resting on an inference a rewording could remove.

C6 and C7 were not in this audit's original scope. They were found only by sweeping for bare type names
(see the limits below), and correcting them was a deliberate ruling rather than an extension of the first
one.

**C1, in detail — the worst site found.** The journey view's element subset is `BusinessService`,
`ApplicationService`, `ApplicationComponent` and `BusinessProcess`. Of the 16 orderings, exactly **one**
permits an Assignment: `ApplicationComponent → ApplicationService`. The other 15 are rejected, including
all four same-type orderings — no concept here may be assigned to itself. An agent told to nest "an
Assignment within the support layers" had one chance in sixteen of choosing a pair `create-relationship`
accepts.

**C2, in detail.** Of the same 16, exactly the four same-type orderings permit Composition. Every
cross-type reading is rejected — including `ApplicationComponent → ApplicationService` (`fiortv`, no
`c`), which is the most natural reading of "a component is composed of its services". The legal link
between those two is the Assignment named in C1.

**C3, in detail.** `Driver → Driver` and `Goal → Goal` are `cgnos` and permit Aggregation. The two
cross-type readings that "a parent driver/goal" invites — `Driver → Goal` and `Goal → Driver` — are `no`:
no `g`. Separately, the clause said "sub-concerns", and **"concern" names none of the 62 ArchiMate
concepts**; an agent taking it literally would be rejected at `create-element`, before reaching the
relationship at all.

---

## Corrections made

Five clauses on three pages. No tool schema, signature, parameter or response field changed.

| Site | Was | Now |
|---|---|---|
| behaviour-process-flow, journey nesting bullet | "`CompositionRelationship` / `AssignmentRelationship` within the support layers" | Composition scoped to same-type nesting (a `BusinessService` into sub-services, an `ApplicationComponent` into sub-components); Assignment named as `ApplicationComponent → ApplicationService`; both cross-type Composition and any other Assignment among these four types stated as impermissible |
| motivation, nesting bullet | "`AggregationRelationship` of sub-concerns into a parent driver/goal" | Aggregation of a `Driver` into sub-drivers or a `Goal` into sub-goals, with the cross-type case denied and `InfluenceRelationship` named as the link between the two |
| motivation, Step 3 | "nest a sub-concern under its parent driver/goal … when an `Aggregation` makes it a part" | nest a sub-driver under its parent `Driver`, or a sub-goal under its parent `Goal`, within the same type |
| index, organization-structure row | "exclude Composition/owner-Assignment by nesting" | Composition scoped to same-type nesting (a `BusinessActor` into sub-actors, a `BusinessRole` into sub-roles); Assignment named as `BusinessActor → BusinessRole`, with both cross-type Compositions and the reverse Assignment stated as impermissible |
| index, information-structure row | "draw Realization" | draw the `RealizationRelationship` `DataObject → BusinessObject`, that direction only, with the reverse and both same-type orderings stated as impermissible |

The motivation Step 3 edit matters as much as the subset edit: a build step is where an agent learns what
a nesting *means*, so correcting the relationship subset while leaving the step restating the old claim
in the imperative would have shipped a page that contradicts itself.

---

## What this audit does not cover

Stated plainly, because an audit that does not name its own limits is read as covering more than it did.

1. **`resources/reference/` is not audited.** Four pages, none of them checked here.
2. **The `relationships.xml` sweep answers permissibility, nothing else.** A pair can be permitted by the
   metamodel and still be the wrong thing to model. This audit does not judge modelling quality.
3. **The census that located these clauses counts type names written in full** — `AggregationRelationship`
   and so on. It counted 48 such mentions across the six pages at the audited commit, and 52 after the corrections. It does **not** see a bare type name:
   there are a further **25** occurrences of `Aggregation`, `Composition`, `Assignment`, `Association`,
   `Flow`, `Realization`, `Serving`, `Specialization` and `Triggering` written without the suffix, mostly
   in Step-4 filter parentheticals and topology diagrams. One of them — the motivation Step 3 clause
   corrected above — carried a real defect, and two more (C6, C7) were found only by sweeping for bare
   names. **A future check that reuses the suffixed-census method will inherit this blind spot.**
   (The figure is **25** at the audited commit, which is the corpus the blind spot actually hid the defect
   in. The same sweep returns 28 after the corrections, because the corrections themselves add three bare
   denials. The two numbers are the same measurement in different frames and only the first one answers
   the question this paragraph asks.)
4. **Nothing here is enforced by parsing the corpus.** The guards in
   `RecipeCopyContractTest` are targeted assertions on named clauses, not a general relationship-pair
   extractor. Extracting pairs from freeform English is a separate piece of work whose stated prerequisite
   is giving each recipe structured front-matter so the pairs are data rather than prose; a parser over
   the corpus in its current form would fail by silently matching nothing and reporting green, which would
   launder an unchecked corpus as a checked one.

---

## Keeping this document honest

`RecipeCopyContractTest` fails the build if the set of recipe pages changes without this document
changing, and if this document names a page that no longer exists. That is deliberately a coarse guard:
it cannot tell whether the tables above are still *correct*, only whether the corpus has moved underneath
them. When it fires, re-run the sweep — do not edit the page list to make it pass.

The individual corrections are held by their own assertions, each paired with an anchor that fails
loudly if the prose it guards has moved rather than passing silently over its absence.
