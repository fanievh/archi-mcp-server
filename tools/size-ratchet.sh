#!/usr/bin/env bash
#
# Size ratchet + interface-signature guard for ArchiModelAccessorImpl.
#
# WHY THIS EXISTS
#   ArchiModelAccessorImpl is a thick orchestration facade that is being
#   decomposed cluster-by-cluster behind its unchanged interface. A guideline
#   ("don't let it grow back") can slip; a ratchet cannot. This script is
#   EXECUTABLE CI CODE, not a guideline: it fails the build (exit 1) the moment
#   the facade grows past a frozen ceiling, or the public API surface drifts.
#
# THREE PAWLS (each can only tighten, never loosen):
#   1. LOC ceiling      — file line count must be <= CEILING_LOC.
#   2. public ceiling   — count of the class's OWN public methods (4-space
#                         indent) must be <= CEILING_PUB. Nested-helper-class
#                         publics (indent >= 5 spaces) are deliberately NOT
#                         counted: they are not part of the accessor API.
#   3. signature guard  — the live ArchiModelAccessor interface, reduced to a
#                         deterministic normalized signature set, must be
#                         byte-identical to the committed baseline. This is the
#                         anti-behaviour-drift pawl: every extraction must leave
#                         this diff EMPTY (interface stays fixed; the impl keeps
#                         one-line forwards).
#
# IMPORTANT: pawls 1 and 2 measure THE FILE, never the model/ package. A package
# metric would make any extraction a no-op (lines just move to a sibling file).
#
# The ceilings are LOWERED (only ever lowered) in the same PR that shrinks the
# file — that manual, reviewable edit is the ratchet "click".
#
# Usage:
#   tools/size-ratchet.sh                 # check all three pawls; exit 1 on any breach
#   tools/size-ratchet.sh --generate      # (re)write the interface-signature baseline
#                                         # from the live source, then exit 0. Use only
#                                         # when the interface legitimately changed.
#
# Portable to bash 3.2 (macOS dev box) and bash 5 (Linux CI). No JDK / Archi needed.
#
set -uo pipefail

# ---- locale pin: REQUIRED, not cosmetic -------------------------------------
# `sort` is locale-aware. macOS (dev box) collates case-insensitively, so lines
# beginning `boolean`/`default`/`void` interleave alphabetically among the
# uppercase-initial return types; the ubuntu CI runner sorts in the C locale
# (ASCII: all uppercase-initial first, lowercase pushed to the bottom). The
# signature baseline is COMMITTED, so an unpinned sort yields the SAME method
# SET in a DIFFERENT order on the two platforms -> spurious "drift" -> a red
# ci-ratchet on a byte-clean release (this exact false positive shipped on
# v1.7.0, public commit 37589de). Pinning C locale makes the sort that WRITES
# the baseline (--generate) and the sort that DIFFS it (pawl 3) byte-identical
# on every platform. Do NOT remove this, and regenerate the baseline ONLY
# through this script so the same pin applies to both sides.
export LC_ALL=C LANG=C

# ---- frozen ceilings (lower-only) -------------------------------------------
# Today's exact measured values (no padding). Lower these in the same PR that
# shrinks the file. NEVER raise them.
#   CEILING_LOC history (lower-only): 19859 (initial) -> 19665 (ImageOperations extraction)
#                                     -> 19531 (DtoMapper extraction)
#                                     -> 19498 (FolderOperations read-facade completion)
#                                     -> 19493 (icon-band + cascade compound wraps folded into AnchorResolver)
#                                     -> 19482 (proposal-card bounds builder folded into ProposalBuilder.putBounds)
#                                     -> 19458 (icon-band mutation-moment gate folded into ImageHelper.iconBandGrownHeight)
#                                     -> 19359 (parent-fit cascade + its overflow predicate extracted to ParentFitCascade)
#                                     -> 19358 (both update prepares' same-batch write-back tail folded into AnchorResolver.recordEffective)
#                                     -> 19302 (five unreachable add-*-to-view convenience overloads
#                                               removed, plus three intermediates the same-batch
#                                               view/element resolution made unreachable)
#                                     -> 19293 (parent-fit map -> resized-group projection extracted
#                                               to ParentFitCascade.project, now shared by
#                                               auto-route-connections and resize-elements-to-fit)
#                                     -> 19238 (bulk per-operation result description extracted to
#                                               BulkResultProjection, joining the post-dispatch
#                                               effective-bounds read already living there)
#                                     -> 19234 (post-rename folder-path construction folded into
#                                               FolderOperations.pathAfterRename)
#                                     -> 19136 (prepareAddToViewDirect folded away entirely: it was a
#                                               near-clone of prepareAddToView differing only in
#                                               taking a resolved element and having no auto-connect
#                                               branch, and prepareAddToView already accepts a
#                                               resolved batch element, so the bulk back-reference
#                                               hands its element over through that same seam)
#                                     -> 19008 (icon-band creation-moment reservation extracted to
#                                               IconBandReservation: geometry the facade merely
#                                               triggers, taking its same-unit-of-work fallbacks as
#                                               parameters exactly as ParentFitCascade does, with the
#                                               icon size and margin moved beside the band height
#                                               they sum to in ImageHelper)
#                                     -> 19003 (the whole-view parent-fit driver folded into
#                                               ParentFitCascade.fitAll, and update-view-object's
#                                               two near-identical ViewObjectDto constructions
#                                               folded into one — they differed only in the three
#                                               identity fields a group or note has no element for)
#                                     -> 18982 (both update prepares' thirteen post-styling
#                                               reconciliations folded into
#                                               StylingHelper.computePostStyling, and their
#                                               hand-written image-coverage guard dropped — the
#                                               call it guarded already answers CoverageReport.NONE
#                                               for an absent path)
#   CEILING_PUB stays 88: extractions keep the public API as one-line forwards, so the
#   class's own public surface is unchanged (only the bodies move out).
#   18982 -> 18950 (prepareUpdateViewConnectionDirect folded into prepareUpdateViewConnection
#                   behind a pre-resolved-connection parameter, the same seam prepareAddToView
#                   and prepareUpdateViewObject already carry; the two differed only in how they
#                   obtained the connection. Recovered 80 lines, of which 48 were spent making a
#                   same-call group addressable as an update target and rejecting a nested bulk
#                   with an error that names the mechanism.)
#   18950 -> 18936 (adversarial review: carrying `text` through the direct update-view-object
#                   prepare cost lines, and the text rejection it needs was about to be
#                   duplicated across both prepares. Both copies moved to TextUtils, which
#                   already owns text for view annotations, and a forwarding overload folded
#                   into its target — leaving the facade smaller than before the fix.)
#   18936 -> 18923 (the five byte-similar back-reference finders — element, relationship, view,
#                   view object, view connection — folded into one generic over IIdentifier, since
#                   they differed only in what their map held. Recovered 48 lines, of which 35 were
#                   spent: 7 wrapping create-element's prepared command in an execution-time
#                   attachment re-check, and 28 giving the pre-resolved parent arm the view-
#                   membership check the live-lookup arm gets by construction. The ancestor walk
#                   that check needs went to AnchorResolver, beside the two-source pending-parent
#                   lookup it reads.)
#   18923 -> 18880 -> 18919 (the six-fold view-resolution prologue and the three-fold group-layout
#                   parameter validation both folded away, recovering 57 lines, of which 53 were
#                   spent declining a placement whose container an earlier operation removed —
#                   eight prepare sites plus the retraction of a declined operation's reported
#                   collateral. The intermediate 18880 is a SEQUENCING ERROR, recorded rather than
#                   hidden: the fold's own commit clicked to the bare post-fold count, which the
#                   commits it exists to pay for then exceed. Click to the figure the work LANDS
#                   at, as every entry above does; the pawl only ever moves down across a story.)
#   18919 -> 18913 (adversarial review: the styled-connection builder the two auto-connect arms
#                   shared was folded away, 18 lines, and 12 spent guarding the connection
#                   endpoints that fold sits on plus clone-view's target folder. This click IS a
#                   lowering; the 18880 above it was the sequencing error described there.)
#   18913 -> 18908 (the two degenerate assess-layout returns — empty view and single-object view —
#                   were 15-line near-duplicates of each other, differing only in an element count
#                   and a suggestion string. Folded into one branch plus a helper, 30 lines out and
#                   25 back. The suggestion text moved to LayoutQualityAssessor, which is not
#                   measured here, so that it could be pinned by an executable test — no test can
#                   execute this facade's assessLayout at all. Clicked at the END of the sweep, to
#                   the figure the work LANDS at: the sweep's second member was superseded by
#                   measurement rather than landing code, so 18908 is final for it.)
#   18908 -> 18905 (the bulk per-operation read now raises its missing-parameter error through
#                   ParamNameDiagnostics, which names the spelling the caller actually supplied.
#                   The three-line throw became a one-line delegation. Sprinkling the alias check
#                   across the switch arms — the obvious shape — would have breached the ceiling
#                   with certainty from one line of headroom, so the knowledge went to a
#                   collaborator instead and the facade shrank. The standalone seam delegates to
#                   the same class from handlers/, which is not measured here. Nothing else in
#                   this sweep touches this file: its image work landed entirely in handlers/.)
#   18905 -> 18900 (the generic URL-download failure arm substituted the exception's message FOR the
#                   url, so a cause with no message — a refused connection has none — delivered
#                   "Failed to download image from URL: null", reading as though the caller had
#                   passed a null url. Moved to ImageDownloadFailure, which is not measured here and,
#                   unlike the arm it came from, can be executed by a test: the full addImageFromUrl
#                   is awkward to reach offline. Six lines out, one back.)
#   18900 -> 18896 (both update prepares computed the post-execution imagePath, imagePosition and
#                   showIcon by hand, each a three-line deferral to a reader already on ImageHelper.
#                   Moved there, which is not measured here. Twenty lines out; sixteen came back as
#                   the second parent-fit pass and its comments, so the net is four. The fit driver
#                   itself went to ParentFitCascade, off this file, which is why the spend was that
#                   small.)
#   NOTE: the entry above ends at 18896 but CEILING_LOC read 18883 before the entries below — a
#   13-line drop that landed without a history line. Recorded here rather than silently inherited;
#   the chain below starts from the value the file actually held.
#   18883 -> 18720 (label policy: the label-optimization pass needed the facade to grow, and the
#                   facade had zero headroom. Three self-contained pieces moved out instead — the
#                   ancestor/child exclude-set lookups to RoutingExcludeSets, newStyledConnection to
#                   StylingHelper (which already owned applyConnectionStyling, its only callee), and
#                   the whole standalone label-optimization pass to LabelOptimizationPass. That pass
#                   needed no accessor state at all, only the diagram handed to it, which is why it
#                   moved whole. 213 lines out, 37 back for the policy threading.)
#   18720 -> 18603 (extending the label policy to auto-layout-and-route needed the facade to grow
#                   again, and it was back at zero headroom. The relationship semantic-attribute
#                   family — resolveAccessTypeInt plus the create/update validators and the
#                   influenceStrength cap they share — moved to RelationshipSemantics. It is
#                   type-conditional validation over a DTO, needing no accessor state, so it moved
#                   whole. 150 lines out, 33 back for the second tool's threading.)
#   18603 -> 18588 (adversarial review round. Three routing entry points needed the same
#                   label-visibility bookkeeping, so the shared guard and the queued-hide filter both
#                   went to LabelVisibilityReadback, and resolveCrossedElementName — a private static
#                   with one caller — folded into AutoRouteWarnings, which already owns the
#                   auto-route response helpers. The fixes cost lines; the folds more than paid.)
#   18588 -> 18565 (reporting why the quality-target loop stopped needed a local plus five exit
#                   assignments in EACH of the two near-duplicate loops, and the facade had zero
#                   headroom. Two things moved to QualityTargetTermination: the remediation text
#                   switch, which had one production caller, and the per-iteration dispatch table
#                   both loops run on. The second was not a spend but the point — the response's
#                   "would more spacing help?" advice is now READ off that table instead of
#                   restating it, so guidance and loop cannot drift apart. 62 lines out, 39 back.)
#   18565 -> 18508 (four layout tools each open-coded the same "collect the view's top-level groups"
#                   loop, and each of them tested one concrete type, so a view built from ArchiMate
#                   Grouping elements was skipped by one and rejected outright by three. Ten such
#                   loops collapsed into TopLevelGroupTargets, which owns the predicate, the
#                   collection, the groupIds resolution and the effective-geometry read-back. The
#                   groupIds block moved because the review that followed grew it: telling a caller
#                   its id is nested rather than absent, and naming both buckets a valid id can be
#                   read from, cost lines in the facade — and that resolution has to agree with the
#                   predicate, so it belongs beside it. The fix REMOVED lines rather than spending
#                   them, and paid for the widening and the reporting on top.)
#   18508 -> 18386 (BulkResultProjection had to describe a concept in the same words the single-tool
#                   caller is given, and the readers that do that were package-private INSTANCE or
#                   private static members of the facade, so the projection could reach none of
#                   them. Writing a second reader is how the two paths come to disagree, so the
#                   element and relationship mappers, the layer resolver and the four
#                   semantic-attribute helpers moved to DtoMapper, which already held a relationship
#                   mapper and already took the attribute values as parameters because the helpers
#                   that compute them lived on the facade. No forward was left behind: a forward is
#                   itself lines, on a pawl that had none. Pure removal.)
#   18386 -> 18384 (two id slots an open batch could not address were closed, and both folds that
#                   paid for them came out of the same two connection prepares. The first: the
#                   connection-add prepare kept its own copy of the view-id resolution the six other
#                   placement prepares had already folded away, missed because it resolves without a
#                   batch slot and so did not match the shape the earlier fold searched for. The
#                   second: the endpoint resolution was written out at both ends of both connection
#                   prepares, four copies that had already drifted to two different suggestions for
#                   the same failure — which is the argument for folding it rather than a side
#                   effect of doing so. Net two lines below the previous ceiling after paying for
#                   the queue fallback, its rationale and the referenced-view fallback.)
#   18384 -> 18379 (every id a deferred unit of work hands back is now addressable by the operations
#                   that follow it, and the fold that paid for it was found by searching for the
#                   shape the previous fold had missed rather than for a new one. Four prepares still
#                   carried their own copy of the view-id resolution — the layout entry point and the
#                   remove, clear and delete prepares — all four byte-identical, all four invisible to
#                   the earlier search because none of them takes a batch slot. 32 lines out. Spent on
#                   queue fallbacks for the four ids the connection add takes, the three the layout
#                   tool takes, and a second view slot on the view-reference prepare, plus the
#                   rationale for each. Three lines came back from the headline fix, which replaced a
#                   prepare-time bendpoint snapshot with the null the prepare already computed: the
#                   snapshot was the thing overwriting the earlier update's work, so removing it was
#                   both the repair and a refund. The double-walk fix the previous entry recorded was
#                   built and measured again at +9 against 5 lines of headroom, and reverted again —
#                   it is a cost fix, and trimming its own rationale to fit is not paying for it.)
#   18379 -> 18370 (a policy change funded two endpoint fixes, and an unreachable overload funded the
#                   third. update-view-object's bulk arm stopped refusing an id the enclosing batch
#                   had queued and started resolving it, which deleted a rejection method and its
#                   call site for -25 after wiring all five queue arguments. Spent on one shared
#                   endpoint-match validator replacing the two near-duplicate copies the connection
#                   prepares carried (+14, a fold that costs rather than pays because it also had to
#                   carry the null-end case both copies used to crash on), and on the double-walk fix
#                   the two entries above record as refused twice. That fix measured +10 this time,
#                   worse than the +9 and +8 already recorded, and it was NOT trimmed to fit: what
#                   paid for it was a twelve-argument prepareAddConnectionToViewDirect overload whose
#                   last caller had gone away unnoticed, -9 including moving its javadoc to the
#                   declaration that survived. An honest move verb cost +1 for its own case rather
#                   than joining the coarser "updated" list; the two placement verbs were free,
#                   being a longer case label and not a new one. -25 -9 +14 +10 +1 = -9.)
#   18370 -> 18349 (dead code funded the whole of it, and there was no other funder: the ceiling was
#                   at zero headroom and every slot to be closed lived in this file. Three private
#                   methods had no reachable caller — a containment-pair helper referenced nowhere in
#                   the tree, and the six- and seven-argument buildOrthogonalRoutingCommands
#                   forwards, which could not be selected because all three call sites pass ten
#                   arguments. -59, less 6 to move the deleted parameter documentation onto the
#                   declaration that survived, so -53 as landed. Spent on the queued-id fallbacks:
#                   +19 closed twelve slots across the five placement arms and update-view-connection,
#                   which is cheap only because those arms already share resolveParentContainer (it
#                   already took the session, so the parent slot cost one coalesce for five arms and
#                   both call paths) and because the view slot folded into one private helper rather
#                   than five repetitions. +13 closed the connection back-reference branch, most of
#                   it the comment explaining why the deferred-connect skip needs a fourth conjunct:
#                   without it, resolving a relationship out of the enclosing queue would have
#                   silently skipped endpoint validation on every one of them. -53 +19 +13 = -21.)
#   18349 -> 18347 (the search relationship mapper folded away. Giving the shared relationship
#                   mapper an empty-string-semantics parameter made its read flavour
#                   field-for-field identical to the search-only clone — proven by comparing the
#                   two outputs on a rich relationship and on a bare orphan BEFORE deleting
#                   either — so the search call site collapsed from a three-line call passing
#                   three separately-computed semantic attributes to a one-line call that
#                   computes them itself. -2 in this file, and -21 in DtoMapper, which is
#                   off-ratchet and therefore funds nothing; the -2 is the whole click.)
#   18347 -> 18346 (whitespace funded a small behaviour fix, which is worth naming as the weak form
#                   of funding it is. Accepting an empty group label needed +2 in this file: the
#                   bulk arm's presence check became a two-statement read, because the helper that
#                   preserves an empty string returns null for an absent key too, plus the comment
#                   recording why that check must NOT be deferred to the prepare's null guard —
#                   deferring it would keep the check and lose the near-miss key report. The other
#                   four edits here were genuinely net 0: the prepare's guard kept its shape, both
#                   elementName reads became post-write resolutions on the same line, and the two
#                   approval/batch descriptions became conditionals on their existing lines. No
#                   dead private method remained to harvest — the two entries above took them all,
#                   and a scan for unreachable privates returned nothing. What paid instead was
#                   three stray double-blank-line runs (-3), which is formatting noise rather than
#                   structure, so the -1 click is real but this file is no smaller in substance.)
#   18346 -> 18324 (create-relationship gained documentation/properties/source, and two folds paid
#                   for it with room to spare. The 8-line property-application loop was
#                   byte-identical in prepareCreateElement and prepareCreateFolder; it moved to a
#                   new ConceptMetadata collaborator over IProperties, which is the common supertype
#                   of IFolder and IArchimateConcept and therefore serves element, folder and
#                   relationship from one implementation. Because the element path guards its
#                   documentation exactly as a relationship should, that guard folded in too;
#                   the FOLDER's did not, and deliberately so — it stores any non-null value, and
#                   routing it through the blank guard would have silently changed what it keeps.
#                   Second fold: the coordinate-pair both-or-neither validation was written out
#                   five times, four byte-identical and a fifth wrapped in a position==null guard,
#                   and became InputValidation.requireCoordinatePair. Together -47, against +29 for
#                   threading the three parameters through the facade, three prepare overloads, the
#                   approval proposal and re-prepare lambda, the DTO's effective-state read-back and
#                   all four bulk back-reference arms. CEILING_PUB is untouched: the interface took
#                   a DEFAULT forwarding overload rather than a second abstract, so the impl still
#                   carries one public createRelationship. The signature baseline moves 92 -> 93 for
#                   that default, regenerated in this commit.)
#   18324 -> 18322 (arrange-groups now declares the total it measured against and names every direct
#                   child of the view no bucket claimed. The response assembly moved wholesale to an
#                   ArrangeGroupsReport collaborator beside the container predicate it has to agree
#                   with, so the facade holds a three-line call where it held a five-line
#                   constructor chain. The lane's placement loop paid the second line by keeping the
#                   placement list it already built instead of counting into a separate int — the
#                   assembly needs the placed ids anyway, to keep an object the lane moved out of
#                   the skipped-container list it would otherwise appear in twice.)
#   18322 -> 18320 (arrange-groups accepts a container the caller names in groupIds whatever its
#                   element type, so a report that says which object was left standing can be turned
#                   back into a call. Paid by a signature pass-through: the resolver needs the view's
#                   own children to find a named non-target, so it takes the view and calls the
#                   collection itself instead of being handed a pre-filtered list. The local that
#                   held that list had no other use in the method, so six lines became four. The
#                   opt-in itself, the refusals it tells apart and the lane exclusion that
#                   stops a named container being claimed twice all live in separate files, which
#                   neither pawl measures.)
#   18320 -> 18313 (layout-within-group reports how its upward ancestor pass ended, not only how
#                   many ancestors it re-fitted. The two guarded call sites — one per layout arm,
#                   each testing the same two conditions before calling the walk — collapsed into a
#                   single call placed after both arms, because the walk composes with either one
#                   and only needs the container's own resize command to be queued first. The
#                   conditions were not deleted: they moved into the collaborator that owns the
#                   walk, which is where they belong anyway, since each one is a reason the walk did
#                   not run and the code that decides an outcome is the code that can name it. The
#                   counter local became the call's own result, so every later use reads a field on
#                   the same line it read an int on. The reason vocabulary, its precedence and the
#                   walk's termination classification all live in files neither pawl measures.)
#   18313 -> 18303 (auto-route-connections names the notes its applied routes pass through, instead
#                   of leaving the caller to discover them from a later assess-layout whose ids are
#                   only in prose. The disclosure itself costs the facade two lines, and they are
#                   funded several times over by extracting the candidate-path overlay: scoring a
#                   routing before it is applied and disclosing a crossing before it is applied both
#                   need the same projection of routed bendpoints onto endpoint centres, and that
#                   projection was written out inline in the scorer. Moving it to a collaborator
#                   turned twenty-two lines into two and gave the two callers one construction to
#                   share, which is the point: a pre-apply verdict and a post-apply verdict that
#                   build the path differently are a defect waiting to be measured. The saving also
#                   paid for the one thing that genuinely had to land in this file: the unified
#                   obstacle list keeps notes while the per-connection list forty lines above drops
#                   them, both deliberately, and nothing had ever written that down — so the next
#                   maintainer to notice would have "fixed" one of them. A comment is LOC on this
#                   pawl and it is worth eight of them. The detector, the shared pass-through
#                   geometry and the warning text live in files neither pawl measures.)
#   18303 -> 18294 (adversarial review of the above. The disclosure moved to after the auto-nudge
#                   pass, and gained the two routing shapes it had silently skipped: a cleared
#                   straight line, and a rectified terminal. Paid for by lifting the stacked-element
#                   pre-route check out of the facade — it builds a position map and writes free-text
#                   warnings, which is what the warning collaborator is for, and it was the last
#                   sizeable block of warning assembly still inline. The two new call sites and the
#                   applied-path map they need cost less than the block was worth.)
#   18294 -> 18189 (reporting whether the quality-target loop left the view worse than it found it
#                   needed a pre-loop measurement, a threaded field and a warning emitter in EACH of
#                   the two near-duplicate loops, and the facade had zero headroom. The four pure
#                   readers over an assessment DTO moved to QualityTargetTermination, which already
#                   owned the remediation table and the dispatch the loops run on and already named
#                   one of them in its javadoc: findLimitingFactor, tierWeightedScore,
#                   hasTier1Regression and getMetricCount. None touches EMF, SWT or instance state —
#                   they are functions of an assessment — and the regression predicate this change
#                   adds is read off two of them, so keeping them apart would have split one decision
#                   across two files. 119 lines out, 14 back for the measurement and the threading.)
#   18189 -> 18188 (the content-bounds pass still averaged a bendpoint's two stored reconstructions
#                   after the two reporting surfaces moved to the weight Archi renders at, so it
#                   understated how far a drifted connection reaches near its terminals — where the
#                   shear is largest — and those bounds exist to keep note placement clear of the
#                   connections. Paid for by dropping the one-line computeAbsoluteCenter forward:
#                   it had no production callers, every call inside this file already went to
#                   ConnectionResponseBuilder fully qualified, and the four tests that used it were
#                   re-pointed at the same method rather than deleted.)
#   18188 -> 18171 (the view collector re-implemented the anchor and absolute-bendpoint block the
#                   shared connection builder already owned, so the two could describe the same
#                   geometry differently and a field added to one would be missing from the other.
#                   Folded into one describeEndpoints on the builder, which now also derives the
#                   render face each end attaches to. That made the convertRelativeToAbsolute
#                   forward production-dead — its single caller was inside the folded block — and it
#                   was dropped with its three tests re-pointed at the builder rather than deleted.)
#   18171 -> 18135 (four call sites re-listed a connection DTO's components to overlay styling on
#                   the builder's geometry, each at whatever arity it happened to know about, so a
#                   rebuild silently truncated whatever the builder derived beyond it. Replaced by
#                   one withConnectionStyling overlay, which cannot drop a component. The view
#                   collector then no longer needed its own DTO construction at all and calls the
#                   builder directly.)
#   18135 -> 18128 (the URL image import derived its temp-file extension inline, lower-casing under
#                   the default locale — so under a Turkish locale an uppercase .TIFF or .ICO became
#                   a dotless-i string that the format switch could not match and silently reported
#                   as PNG. The derivation moved beside that switch as two pure statics, where it is
#                   locale-fixed and unit-tested against a forced locale; the accessor keeps a
#                   one-line call. The same defect stood on the base64 import path and is fixed by
#                   the same helper.)
#   18128 -> 18126 (the flat layout wrote a full x/y/width/height to every top-level element and
#                   every embedded child from two hand-rolled loops, so a parent grown to contain
#                   the children the same call laid out inside it changed size with the only trace
#                   a local boolean. Both loops now call the shared placeChildren collaborator,
#                   which records the size changes against the effective rectangle; the two loops
#                   pay for the wiring and two lines over.)
#   18126 -> 18125 (the group-order optimiser re-ran each group's arrangement and wrote the result
#                   through a hand-rolled loop, so a grid's uniform cell width, autoWidth, or an
#                   explicit elementWidth could land a child at a size the response never carried.
#                   The loop now calls the shared placeChildren collaborator.)
#   18125 -> 18124 (grouped auto-layout read fittedContainers and depthCapHit out of the recursive
#                   pass and dropped the rest, so the leaves that pass had ALREADY recorded itself
#                   as re-sized reached no response at all. Reading them costs a few lines, paid
#                   for by collapsing a child collection that existed only to answer "is there
#                   anything here?" into one call to a new layoutChildrenOf helper.)
#   18124 -> 18103 (review of the four reporting arms found grouped auto-layout reporting a
#                   rectangle a LATER pass overwrote: the element-reorder pass re-lays out every
#                   child of a group it reorders and is merged after the layout pass, so the last
#                   command wins and the frozen observation was stale. Rectangles now come from the
#                   merged compound. Paid for by both reorder paths, which had each open-coded the
#                   same fourteen-line rebuild of a child list from an id order.)
#   18103 -> 18102 (the spacing tool re-fits each container to its inflated contents, but reported
#                   the objects it re-sized from the child-placement observation map — and a
#                   TOP-LEVEL container is a child of the view, not of any group, so no placement
#                   loop ever held it. Measured: a container at 2000x1200 committed at 240x118 and
#                   was named in neither list. The report is now projected from the merged
#                   compound, which holds every rectangle the call writes. Paid for by deleting the
#                   observation map and the two signatures that threaded it through the recursion.)
#   18102 -> 18037 (the three spacing convenience tools now compare their own before/after
#                   assess-layout snapshots and disclose a rating regression the control loop's
#                   step scalar cannot see. Paid for by moving toLayoutMetrics -- the pure
#                   AssessLayoutResultDto -> LayoutMetrics conversion, EMF-free and with no state
#                   of its own -- out of the facade and onto LayoutQualityScalar, whose scalar it
#                   exists to build and whose javadoc already documented the conversion. Body moved
#                   byte-identical apart from the modifier; three call sites re-qualified. Net -45
#                   after the three disclosure call sites, which cost two lines each because the
#                   comparison lives in a collaborator and the DTO component threads through a new
#                   delegating constructor rather than through every short-circuit path.)
#   18037 -> 18034 (auto-route's autoNudge loop reported an element whose two iterations cancelled
#                   out as nudged, contradicting the same response's failed array and its
#                   recommendations. The consolidation of the cumulative-delta map into the
#                   reported list moved to an unmeasured collaborator that splits moved from
#                   net-zero; the facade calls it and hands the net-zero entries to the warnings
#                   emitter. Paid for by deleting a per-iteration trace list that was built every
#                   iteration and read nowhere -- the whole-file grep found only its declaration
#                   and its one append.)
#                                     -> 18033 (add-note-to-view registered as the seventh
#                                               back-reference registry, and the note prepare made
#                                               to record its pending parent like its two siblings.
#                                               Paid for by folding the four optionalIntParam
#                                               declarations to two, and each two-line
#                                               findBatchCreatedObject / findBackReferenced call to
#                                               one, across the arms of the same bulk switch the
#                                               change edits -- 19 lines funded, 18 spent.)
#                             18033 -> 18027 (approval-card disclosure pass. Twenty-two parameters
#                                               that a gated approval writes but no card named are
#                                               now disclosed, and the misleading update-view-object
#                                               sentence became conditional -- yet the file SHRANK.
#                                               Paid for by folding runs of guarded
#                                               `if (x != null) proposedChanges.put(...)` one-liners
#                                               into ProposalBuilder.putIfPresent, and the two
#                                               visual-record projections into putStyling /
#                                               putImageParams / putVisuals. The collaborator is not
#                                               ratcheted, for the same reason putBounds lives there:
#                                               disclosing more on a card must not cost the facade a
#                                               line per key. 23 lines funded, 17 spent.)
#                             18027 -> 18026 (approval-card prose pass. Both prose fields of
#                                               update-view-connection and the validation summary of
#                                               update-view-object named a single aspect --
#                                               bendpoints, bounds -- while their methods write five
#                                               and twelve things; all three now read the same
#                                               disclosure map their card carries, so a restyle is
#                                               no longer announced as a reroute. Self-funding: the
#                                               two-line connection sentence became one call into an
#                                               unratcheted collaborator, and both summary literals
#                                               swapped one line for one.)
#                             18026 -> 17962 (inter-group connection counter moved out. The counter
#                                               and the containment walk it uses had their own
#                                               instanceof test for what a container is, so a view
#                                               whose zones are ArchiMate Grouping elements was
#                                               counted at zero and took the unconnected column of
#                                               the spacing heuristic. They now live in
#                                               TopLevelGroupTargets, beside the one predicate that
#                                               defines the count, which is also what makes them
#                                               reachable from a headless test. 64 lines out, none
#                                               back: both call sites were already two lines.)
#                             17962 -> 17956 (resize-elements-to-fit stopped treating an ArchiMate
#                                               Grouping as a label-bearing element: a zone is now
#                                               grown around its children and never shrunk, sized
#                                               to its own name, or given a label band. The target
#                                               walk, the nesting-depth helper and the containment
#                                               map moved to TopLevelGroupTargets, beside the
#                                               predicate that now splits "is this a zone" out of
#                                               "is this a container". The map carries the rule
#                                               that made the whole cluster cheaper: its key set IS
#                                               the parent set, so the parallel hasChildren set
#                                               went with it.)
#                                     -> 17941 (the ancestor-on-view containment predicate moved to
#                                               AutoConnectSkip, beside the record describing a pair
#                                               a pass declined to draw. Both auto-connect passes ask
#                                               that question, and the move funded the second one
#                                               learning to ask it: the 30 lines it freed paid for
#                                               the check and the two skip reports add-to-view's
#                                               autoConnect now returns, with 15 left over.)
#                                     -> 17938 (the add-to-view approval card's auto-connect sentence
#                                               and its structured disclosure built together in
#                                               AutoConnectSkip.discloseOnCard. The card had counted
#                                               only what WOULD be drawn, which said nothing at all
#                                               once the pass could decline; composing both from one
#                                               reading of the prepared result replaced four lines
#                                               with one.)
#                                     -> 17922 (collectHubData extracted to HubDataCollector. Two
#                                               tools publish a per-element connection count for the
#                                               same view and an agent reads them in consecutive
#                                               calls, so the walk had to become one shared method
#                                               rather than a second one alongside it. The
#                                               extraction freed 42 lines and the fan-out
#                                               precondition plus the three annotation corridor
#                                               checks spent 26 of them.)
#                                     -> 17918 (the large-hub spacing signal moved to
#                                               HubSpacingSignal. Five callsites wrote the
#                                               predicate out; four agreed and a fifth read the
#                                               hub-detection result's emptiness, which is a test
#                                               for the view being connected at all, so the
#                                               composed tool selected a spacing column its own
#                                               single-arm siblings did not. Delegating makes the
#                                               five provably identical and folds four wrapped
#                                               initialisers into one line each; a comment
#                                               claiming a symmetry the call now enforces paid the
#                                               remaining two.)
#                                     -> 17901 (mergeSourceProperties moved to ConceptMetadata,
#                                               beside the write it feeds. 25 lines out; the four
#                                               call sites re-qualified in place at no cost. The
#                                               merge is now a package-visible static on an
#                                               un-ratcheted collaborator, which is also the only
#                                               way its two refusals could be pinned in the
#                                               headless lane at all. 8 came back as comment: 7 on
#                                               the connection-id dedupe, saying why it sits on the
#                                               loop's input rather than its output, and 1 on the
#                                               create-relationship arm now passing an empty
#                                               documentation through. Clicked ONCE, at the end,
#                                               to the figure the work LANDS at.)
#   17901 -> 17899 (threading the dispatch arm into the routing disclosures needed seven emitter
#                   call sites and two private signatures to gain an argument, and every one of
#                   them fit on a line that already existed, so the threading itself cost nothing.
#                   The two lines came from the degenerate-geometry early return: its three-line
#                   free-text add became a one-line call to an emitter that also repairs the
#                   connection-not-found entry the same response carries, which until now said the
#                   remaining connections were routed normally beside a notice that routing had
#                   failed. Composition belongs with the other message composition, not in the
#                   facade. Clicked to what the work lands at.)
#                   17808 -> 17787 (the entry-guard termination mapper moved out whole, to a
#                   pure-string collaborator that also builds the one refusal three tools publish
#                   and owns the fixed code it maps to. Slugifying a reason long enough to name a
#                   remedy would have produced a ninety-character code that changed shape with
#                   every rewording, so the code is named beside the prose rather than derived
#                   from it. The predicate and the refusal builder went to the class that already
#                   owns the sibling refusal and the container walks they read. Clicked to what
#                   the work lands at.)
#                   17787 -> 17785 (review: the two questions a spacing gate asks — which frame may
#                   it measure, and does it owe a refusal — are now paired per step in the
#                   collaborator, so the facade names the step rather than the threshold and cannot
#                   ask the two with different numbers. That mismatch was a live defect: an arm
#                   needing ONE of the view's own containers did not decline and then measured the
#                   host's frame anyway. Clicked to what the work lands at.)
#                   17785 -> 17698 (the back-reference cluster — the resolver, its four-way
#                   validator and the cascade check — moved out whole to a package-private,
#                   dependency-light collaborator, with the two regex imports it orphaned. It took
#                   everything as parameters and touched no field, so it was never facade work.
#                   Both call sites requalified in place rather than forwarded, which is why the
#                   removal is pure. Beyond the lines, it is what made those four refusal messages
#                   and the cascade sentence assertable at all: on the facade they were reachable
#                   only through a method whose pins run in the display lane, so the exact wording
#                   an agent reads was covered in no lane that runs by default. A dead index
#                   parameter went with it, proved dead by passing a nonsense value and watching
#                   fifty-three tests stay green. The refusal work the fold funded then spent part
#                   of it back. Clicked ONCE, at the end, to what the work lands at.)
#                   17698 -> 17684 (code review of the same work. The fixes cost six lines on this
#                   file — a deferred cache write moved to the true method end rather than merely
#                   below the validation, so the guarantee stops depending on nothing throwing
#                   between there and the return, plus the rationale for keying it off the
#                   validated name. Funded, not waived: twenty imports the file no longer
#                   references came out, found by a comment-stripped reference scan rather than a
#                   token search, so an identifier occurring only inside a comment still counts as
#                   unused. Clicked to what the work lands at.)
#                   17673 -> 17661 (naming a bulk operation, so a later one can reference it by
#                   name instead of by position. The facade pays nine lines: one to index the
#                   declared names before the prepare loop, and a skip so an operation refused over
#                   its own name is not prepared anyway. Funded by extracting the bulk staleness
#                   target set to its own collaborator — twenty-two lines out, one call site, and
#                   the set it builds is the whole staleness check on that path so it is better off
#                   somewhere it can be reached headlessly. The two byte-identical back-reference
#                   nextSteps sentences were single-sourced onto the collaborator that owns the
#                   grammar in the same pass, which is line-neutral here and closes a divergence no
#                   test was guarding. Clicked to what the work lands at.)
#                   17661 -> 17650 (one group resize per apply-positions call instead of one per
#                   entry that grew the group: the prepare takes the pass-scoped command map its
#                   sibling already had, stops wrapping the resizes into each entry's own command
#                   and stops projecting a pass map into a per-object report, and the caller commits
#                   them into the compound after every object has landed. Measured before and after:
#                   ten thousand entries in one group put ten thousand absolute rectangles for it
#                   into a single compound, all but the last overwritten on execution, and now put
#                   one. Funded by single-sourcing the five byte-identical view-object-not-found
#                   refusals in the remove prepare, whose shared remedy names the fields a caller
#                   reads a visual id out of and had five places to drift in. Clicked to what the
#                   work lands at.)
CEILING_LOC=17650
CEILING_PUB=88

# ---- locate repo (the script lives in <repo>/tools/) ------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"

IMPL="$REPO/net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java"
IFACE="$REPO/net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessor.java"
BASELINE="$REPO/tools/accessor-interface-baseline.txt"

fail=0

err() {
  # GitHub Actions annotation + human-readable line.
  echo "::error::$1"
  echo "RATCHET FAIL: $1" >&2
}

# Fail loudly if the measured files are missing. Without this, an absent file makes
# `wc -l`/`grep -c` yield an empty string, which numeric comparison treats as 0 and
# the ceiling check silently PASSES — a mis-named or deleted file must never bypass
# the ratchet. (set -e is not used here, so command-substitution failures are silent.)
if [ ! -f "$IMPL" ]; then
  err "ArchiModelAccessorImpl.java not found at: $IMPL"
  exit 1
fi
if [ ! -f "$IFACE" ]; then
  err "ArchiModelAccessor.java not found at: $IFACE"
  exit 1
fi

# -----------------------------------------------------------------------------
# Deterministic interface-signature extraction.
#
# Produces one normalized signature per interface method, sorted, so the dump is
# stable under reformatting AND reordering (neither is behaviour drift). Steps:
#   1. strip block comments (/* ... */, incl. javadoc with its {@code ...} braces)
#   2. strip line comments (// ...)
#   3. keep only the interface BODY (everything after the `interface ... {` line;
#      the opening brace lives on that excluded line, so only default-method
#      braces remain below)
#   4. collapse every balanced { ... } (default-method bodies) to a single `;`
#   5. flatten whitespace, split on `;` into one statement per line
#   6. keep only statements with a parameter list `(` (methods; drops the lone
#      trailing interface-close `}` and any constants)
#   7. strip leading annotations (@Deprecated, incl. parameterized forms) and trim; sort
# -----------------------------------------------------------------------------
extract_signatures() {
  perl -0777 -pe 's{/\*.*?\*/}{}gs' "$IFACE" \
    | perl -pe 's{//.*}{}' \
    | awk 'f{print} /public[ \t]+interface[ \t]+ArchiModelAccessor/{f=1}' \
    | perl -0777 -pe '1 while s/\{[^{}]*\}/;/g' \
    | perl -0777 -pe 's/\s+/ /g' \
    | tr ';' '\n' \
    | sed -E 's/^ +//; s/ +$//' \
    | grep '(' \
    | sed -E 's/^(@[A-Za-z][A-Za-z0-9]*(\([^)]*\))? )+//' \
    | sed -E 's/^ +//; s/ +$//' \
    | sort   # C-collation (pinned via LC_ALL=C at top) -> deterministic across macOS/Linux
}

# ---- --generate mode --------------------------------------------------------
if [ "${1:-}" = "--generate" ]; then
  extract_signatures > "$BASELINE"
  echo "Wrote interface-signature baseline: $BASELINE ($(wc -l < "$BASELINE" | tr -d ' ') signatures)"
  exit 0
fi

# ---- pawl 1: LOC ceiling ----------------------------------------------------
loc=$(wc -l < "$IMPL" | tr -d ' ')
if [ "$loc" -gt "$CEILING_LOC" ]; then
  err "ArchiModelAccessorImpl.java LOC $loc > ceiling $CEILING_LOC. The facade must not grow; extract a collaborator instead."
  fail=1
else
  echo "OK  LOC      : $loc <= $CEILING_LOC"
fi

# ---- pawl 2: public-method ceiling (class's OWN methods only) ---------------
# Counts lines beginning at the class's own 4-space indent with `public ` — i.e. the
# first line of each top-level public method declaration (one such line per method,
# even when the parameter list wraps onto 8-space-indented continuation lines).
# Nested-helper-class publics live at >= 5-space indent and are deliberately excluded.
pub=$(grep -cE '^    public ' "$IMPL")
if [ "$pub" -gt "$CEILING_PUB" ]; then
  err "ArchiModelAccessorImpl top-level public methods $pub > ceiling $CEILING_PUB. Do not add public API to the facade."
  fail=1
else
  echo "OK  publics  : $pub <= $CEILING_PUB"
fi

# ---- pawl 3: interface-signature drift --------------------------------------
if [ ! -f "$BASELINE" ]; then
  err "interface-signature baseline missing: $BASELINE (run: tools/size-ratchet.sh --generate)"
  fail=1
else
  sig_diff="$(mktemp "${TMPDIR:-/tmp}/accessor-sig.XXXXXX")"
  trap 'rm -f "$sig_diff"' EXIT
  if diff -u "$BASELINE" <(extract_signatures) > "$sig_diff"; then
    echo "OK  signature: ArchiModelAccessor matches baseline ($(wc -l < "$BASELINE" | tr -d ' ') methods)"
  else
    err "ArchiModelAccessor interface signature drifted from baseline. Extraction must keep the interface fixed (impl forwards). Diff:"
    cat "$sig_diff" >&2
    fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "RATCHET PASS"
exit 0
