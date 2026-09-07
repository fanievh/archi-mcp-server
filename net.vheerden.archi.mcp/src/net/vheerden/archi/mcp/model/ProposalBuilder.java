package net.vheerden.archi.mcp.model;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.exceptions.MutationException;

/**
 * The shared "stored request → fresh {@code Command} + preconditions" seam used by the approve path
 * A pending proposal no longer holds a pre-built GEF {@code Command}
 * closed over propose-time EObjects; it holds a <em>deferred rebuild handle</em> — a
 * {@code Supplier<PreparedMutation<?>>} that re-invokes the very same per-tool {@code prepareXxx(...)}
 * logic the immediate-dispatch path runs, against the <em>current</em> model. This builder turns that
 * handle back into a fresh {@link PreparedMutation} at approve-time.
 *
 * <p><strong>No drift between paths.</strong> Both the immediate path and the approve path build through
 * the same {@code prepareXxx} family — the immediate path calls it directly; the approve path's stored
 * handle re-invokes the identical method. So preconditions and command construction cannot diverge: it is
 * the same code, run twice (the propose-time call produces the card's
 * {@code entity}/{@code effectDescription}; this approve-time call produces the command actually
 * dispatched, re-resolved against current state).</p>
 *
 * <p><strong>Stale/precondition signal, never an NPE — and never the wrong reason.</strong> If a target was
 * deleted or retyped so re-resolution fails, the re-invoked {@code prepareXxx} throws. The three arms are
 * deliberately ordered and mean different things:</p>
 * <ol>
 *   <li>a {@link MutationException} passes through <em>unchanged</em> (it is already the approve seam's
 *       own vocabulary);</li>
 *   <li>a {@link ModelAccessException} is a <em>structured domain refusal</em> — the prepare step ran and
 *       said no for a nameable reason, carrying its own remedy sentence. Its message is surfaced
 *       <em>verbatim</em>. It extends {@code RuntimeException}, so without this arm it would fall into (3)
 *       and a right refusal would be reported with a wrong, generic reason;</li>
 *   <li>any <em>other</em> runtime failure is genuine staleness of the kind nobody can name, and becomes
 *       the plain-language generic sentence — so the approve seam surfaces "stale", not a raw exception or
 *       null-pointer.</li>
 * </ol>
 * <p>The {@link ProposalStalenessGuard} runs <em>first</em> and names the touched/removed object for the
 * common cases; this is the last-line safety net for targets the coarse guard did not track.</p>
 *
 * <p>Package-private, {@code model/}-only. Stateless — one instance per dispatcher.</p>
 */
final class ProposalBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ProposalBuilder.class);

    /**
     * Card text naming the <strong>reviewed-or-reject</strong> gate, appended to the validation summary of
     * every proposal whose deferred handle returns an already-built compound instead of re-preparing.
     *
     * <p><strong>Two kinds of gate, one dock.</strong> Of the propose sites in the accessor, most store a
     * handle that re-invokes their {@code prepareXxx} against the current model at approve — so what runs
     * is recomputed from the model as it stands when the human clicks. Fourteen instead store the compound
     * that was <em>already built and already reviewed</em>:</p>
     * <ul>
     *   <li>{@code apply-positions}, {@code resize-elements-to-fit}, {@code layout-within-group},
     *       {@code layout-flat-view}, {@code optimize-group-order}, {@code arrange-groups};</li>
     *   <li>{@code auto-route-connections} (both the full and terminals-only passes);</li>
     *   <li>{@code auto-layout-and-route} (flat, flat + quality target, grouped, grouped + quality target);</li>
     *   <li>{@code auto-connect-view};</li>
     *   <li>{@code bulk-mutate}.</li>
     * </ul>
     *
     * <p><strong>Why they freeze, and why that is not a bug.</strong> Re-running a layout, routing or
     * bulk-operation pass can legitimately produce a different result from the one on the card — a
     * different arrangement, a different set of ops. Re-preparing them would hand the human an outcome
     * they never reviewed, which is a worse failure than refusing. So these gates are honest only as
     * "apply exactly this, or reject it", and the {@link ProposalStalenessGuard} bounds the risk by
     * rejecting-stale when a tracked object is removed, edited or dragged in the meantime.
     *
     * <p><strong>The residual, stated plainly.</strong> The guard forgives a change made by an intervening
     * <em>agent</em> command, because a re-preparing handle would re-resolve against it. These fourteen
     * re-resolve nothing, so such a change is silently overwritten by propose-time state on approve.
     * Closing that would need the frozen-ness carried per proposal into the guard's decision.</p>
     *
     * <p>Stated on the card because the human cannot otherwise tell the two gates apart: both render the
     * same way, and only one of them recomputes.</p>
     */
    static final String REVIEWED_OR_REJECT =
            " Approving applies exactly this reviewed result; it is not recomputed.";

    /**
     * Adds the caller-supplied geometry to a proposal's {@code proposedChanges} map, omitting each
     * value the caller left unset so the card never shows a bound that was not asked for.
     *
     * <p>Every proposable placement tool — the five {@code add-*-to-view} tools and
     * {@code update-view-object} — built this identical block inline. Sharing it keeps the geometry
     * fields named and ordered the same way on all six cards, so a card cannot come to mean
     * different things depending on which tool produced it.</p>
     */
    static void putBounds(Map<String, Object> proposedChanges,
            Integer x, Integer y, Integer width, Integer height) {
        if (x != null) proposedChanges.put("x", x);
        if (y != null) proposedChanges.put("y", y);
        if (width != null) proposedChanges.put("width", width);
        if (height != null) proposedChanges.put("height", height);
    }

    /**
     * Adds each caller-supplied key/value pair to a proposal's {@code proposedChanges} map,
     * omitting every pair whose value is {@code null}.
     *
     * <p><strong>Presence semantics are the contract, and they are exact.</strong> A {@code null}
     * value writes <em>no key</em> — never a null-valued one, which would survive into the
     * Technical-details disclosure a human reads while being stripped from the wire the agent
     * receives, so the two doors would disagree. An <em>explicitly blank</em> value still writes
     * its key: a blank name is the only way a name-wipe is disclosed at all, and the card's
     * explicitly-blank row depends on that key being present.</p>
     *
     * <p>This is the shared fold for the runs of {@code if (x != null) proposedChanges.put(...)}
     * one-liners the approval branches were built from. It lives here, beside {@link #putBounds},
     * for the same reason: the facade is size-ratcheted and this collaborator is not, so
     * disclosing more on a card does not have to cost the facade a line per key.</p>
     *
     * @param proposedChanges the card's change map, mutated in place
     * @param keyValuePairs   alternating {@code String} key and nullable value; insertion order
     *                        is preserved, so the argument order <em>is</em> the card's key order
     * @throws IllegalArgumentException if an odd number of arguments is supplied — a dangling key
     *                                  is a programming error, and dropping it silently would lose
     *                                  exactly the disclosure this helper exists to guarantee
     */
    static void putIfPresent(Map<String, Object> proposedChanges, Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "putIfPresent needs an even number of arguments (alternating key/value); got "
                            + keyValuePairs.length);
        }
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (!(keyValuePairs[i] instanceof String key)) {
                throw new IllegalArgumentException(
                        "putIfPresent needs a String key at argument " + i + "; got "
                                + (keyValuePairs[i] == null ? "null"
                                        : keyValuePairs[i].getClass().getSimpleName()));
            }
            Object value = keyValuePairs[i + 1];
            if (value != null) {
                proposedChanges.put(key, value);
            }
        }
    }

    /**
     * Discloses the caller's styling parameters on the card under a single {@code "styling"} key,
     * projected to a plain map holding only the fields that this site will actually write.
     *
     * <p><strong>Guarded on the projection being non-empty, never on
     * {@link StylingParams#hasAnyValue()}.</strong> That method checks sixteen of the record's
     * seventeen fields, deliberately excluding {@code recede}, and gating disclosure on it would
     * stay silent about a {@code recede}-only call at the two sites where {@code recede} is live.
     * Widening {@code hasAnyValue()} is not the alternative: it guards the styling-application early
     * return at every one of its call sites, and relaxing a shared guard widens all of them.</p>
     *
     * <p><strong>And the opposite error is equally a defect.</strong> {@code recede} is read by
     * exactly one command, wrapped at exactly two prepare sites — the ones behind
     * {@code add-to-view} and {@code add-group-to-view}, the only two tools whose schema offers it.
     * Everywhere else the field is inert: the write path never reads it, so disclosing it would tell
     * the approving human that styling is about to change when nothing will. Under-disclosing hides
     * a write; over-disclosing invents one. Both break the same promise, so {@code recede} is
     * disclosed only by {@link #putContainerVisuals}.</p>
     *
     * <p>The projection is a plain {@code Map}, not the record, so every value in
     * {@code proposedChanges} stays JSON-native: it serialises identically on the wire and in the
     * Technical-details payload without depending on either mapper's null-inclusion setting, and a
     * model-layer value object does not travel out through the response DTO.</p>
     */
    static void putStyling(Map<String, Object> proposedChanges, StylingParams styling) {
        putStyling(proposedChanges, styling, false);
    }

    private static void putStyling(Map<String, Object> proposedChanges, StylingParams styling,
            boolean recedeApplies) {
        if (styling == null) {
            return;
        }
        Map<String, Object> disclosed = new LinkedHashMap<>();
        putIfPresent(disclosed,
                "fillColor", styling.fillColor(),
                "lineColor", styling.lineColor(),
                "fontColor", styling.fontColor(),
                "opacity", styling.opacity(),
                "lineWidth", styling.lineWidth(),
                "figureType", styling.figureType(),
                "textAlignment", styling.textAlignment(),
                "verticalTextAlignment", styling.verticalTextAlignment(),
                "fontName", styling.fontName(),
                "fontSize", styling.fontSize(),
                "fontStyle", styling.fontStyle(),
                "lineStyle", styling.lineStyle(),
                "gradient", styling.gradient(),
                "borderType", styling.borderType(),
                "deriveLineColor", styling.deriveLineColor(),
                "outlineOpacity", styling.outlineOpacity());
        if (recedeApplies) {
            putIfPresent(disclosed, "recede", styling.recede());
        }
        // An empty projection means nothing styling-related will be written, so the card says
        // nothing about styling. A bare "styling": {} key would announce a change and describe none.
        if (!disclosed.isEmpty()) {
            proposedChanges.put("styling", disclosed);
        }
    }

    /**
     * Discloses the caller's image parameters on the card under a single {@code "imageParams"} key,
     * projected the same way {@link #putStyling} projects styling: only the fields that were set,
     * JSON-native, and no key at all when nothing was set.
     */
    static void putImageParams(Map<String, Object> proposedChanges, ImageParams imageParams) {
        if (imageParams == null) {
            return;
        }
        Map<String, Object> disclosed = new LinkedHashMap<>();
        putIfPresent(disclosed,
                "imagePath", imageParams.imagePath(),
                "imagePosition", imageParams.imagePosition(),
                "showIcon", imageParams.showIcon());
        if (!disclosed.isEmpty()) {
            proposedChanges.put("imageParams", disclosed);
        }
    }

    /**
     * Discloses both visual records a placement tool accepts, in the one fixed order every such
     * card uses. The seed defect in this family was two sibling tools answering the same question
     * differently, so the shape is applied from one place rather than re-chosen per call site.
     *
     * <p>For the sites where {@code recede} has no effect — every placement tool except the two
     * container ones. See {@link #putContainerVisuals}.</p>
     */
    static void putVisuals(Map<String, Object> proposedChanges,
            StylingParams styling, ImageParams imageParams) {
        putStyling(proposedChanges, styling, false);
        putImageParams(proposedChanges, imageParams);
    }

    /**
     * As {@link #putVisuals}, and additionally discloses {@code recede} — for the two tools that
     * place an object <em>into a container</em> and therefore actually apply it
     * ({@code add-to-view} and {@code add-group-to-view}, the only two whose schema offers the
     * field and the only two whose prepare wraps the receding command).
     */
    static void putContainerVisuals(Map<String, Object> proposedChanges,
            StylingParams styling, ImageParams imageParams) {
        putStyling(proposedChanges, styling, true);
        putImageParams(proposedChanges, imageParams);
    }

    /**
     * Re-resolves and rebuilds the proposal's command fresh against the current model by invoking its
     * stored deferred handle.
     *
     * @param proposal the approved proposal carrying the deferred rebuild handle
     * @return a fresh {@link PreparedMutation} (command + re-resolved entity)
     * @throws MutationException if a targeted object can no longer be resolved or fails preconditions
     *                           (reported as stale, not as a raw exception)
     */
    PreparedMutation<?> rebuild(PendingProposal proposal) throws MutationException {
        try {
            PreparedMutation<?> fresh = proposal.rebuild().get();
            if (fresh == null || fresh.command() == null) {
                throw new MutationException(staleMessage());
            }
            return fresh;
        } catch (MutationException e) {
            throw e;
        } catch (ModelAccessException e) {
            // A STRUCTURED DOMAIN REFUSAL, not staleness. ModelAccessException extends RuntimeException,
            // so the generic catch below used to swallow it and substitute the generic stale sentence —
            // telling the human the wrong reason and destroying an actionable remedy. The clearest case:
            // content was added to a folder proposed for a non-force delete, so the re-invoked
            // prepareDeleteFolder throws FOLDER_NOT_EMPTY carrying its own "Use force: true to
            // cascade-delete all contents." remedy. The refusal is right; only the reason was wrong.
            //
            // The message passes through UNCHANGED. Every ModelAccessException reachable from a rebuild
            // handle is raised by a prepareXxx method and carries a curated, human-safe domain sentence:
            // the public deleteXxx/createXxx wrappers' "Error …ing '<id>'" INTERNAL_ERROR tail-catches sit
            // ABOVE the prepare step and are never entered from here, so no wrapped stack detail can reach
            // the approval dock. The cause is retained for the log/diagnostics.
            logger.info("Proposal '{}' refused at rebuild by a domain precondition [{}]: {}",
                    proposal.proposalId(), e.getErrorCode(), e.getMessage());
            throw new MutationException(e.getMessage(), e);
        } catch (RuntimeException e) {
            logger.info("Proposal '{}' could not be rebuilt against the current model: {}",
                    proposal.proposalId(), e.getMessage());
            throw new MutationException(staleMessage(), e);
        }
    }

    private static String staleMessage() {
        return "This proposal can no longer be applied because a targeted object was changed or removed "
                + "since the agent proposed it. Reject it and ask the agent to retry.";
    }
}
