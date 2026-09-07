package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.LayoutEntryFailure;

/**
 * An {@code apply-positions} pre-validation refusal that carries <em>every</em> entry that failed,
 * not only the one that failed first.
 *
 * <p>A {@link ModelAccessException} so that every existing route keeps working unchanged — the
 * accessor's own rethrow and the handler's {@code catch (ModelAccessException e)} both see exactly
 * what they saw before. The list rides alongside as a typed field.</p>
 *
 * <p>Unlike its {@code bulk-mutate} sibling this <strong>takes</strong> its error code rather than
 * hardcoding one. The entry wrappers this tool builds have always preserved the inner failure's own
 * code, and a whole-call code invented here would destroy the one piece of information this tool
 * already reported correctly.</p>
 *
 * <p>The scalar fields are deliberately those of the <strong>first</strong> failure alone, exactly
 * as they were before the list existed: no count clause and no list until there are two. Leading
 * with a count would make the common single-failure case strictly less informative for no gain.</p>
 *
 * <p><strong>One message class is deliberately not left alone.</strong> A failure that names
 * something <em>inside</em> a readable entry — the bendpoint family — gains its entry's prefix,
 * because the helpers raising those are shared with the single-connection tools and report a
 * bendpoint index alone, which read from inside an array tells a caller a bendpoint is malformed
 * without telling it which entry carried it. Every other class is untouched for a single failure:
 * an unresolvable id keeps its existing wrapper, an unreadable entry already named its own index,
 * and a missing or blank id is republished verbatim.</p>
 *
 * <p>{@link #getFailures()} may be shorter than {@link #getTotalFailureCount()}: this tool accepts
 * ten thousand entries and the all-fail case is the realistic one, so the rows are capped. The true
 * total is carried separately precisely so that every count the refusal publishes can be the real
 * one rather than the length of a truncated list.</p>
 */
public class LayoutValidationException extends ModelAccessException {

    private static final long serialVersionUID = 1L;

    private final transient List<LayoutEntryFailure> failures;
    private final transient Map<String, Integer> totalsByArray;
    private final int totalFailureCount;

    LayoutValidationException(String message, ErrorCode errorCode, String details,
            String suggestedCorrection, String archiMateReference, Throwable cause,
            List<LayoutEntryFailure> failures, Map<String, Integer> totalsByArray,
            int totalFailureCount) {
        super(message, errorCode, details, suggestedCorrection, archiMateReference);
        // The constructor that carries the structured fields takes no cause, so the chain is
        // attached here rather than lost. The refusal this replaces wrapped the first failure with
        // its cause, and a change made to report MORE about a failure must not quietly report less
        // of it to whatever logs the stack.
        if (cause != null) {
            initCause(cause);
        }
        this.failures = List.copyOf(failures);
        this.totalsByArray = Map.copyOf(totalsByArray);
        this.totalFailureCount = totalFailureCount;
    }

    /**
     * The entries that failed pre-validation, in request order, capped. Never empty: this exception
     * is only built once at least one entry has failed.
     */
    public List<LayoutEntryFailure> getFailures() {
        return failures;
    }

    /**
     * How many entries actually failed, which is what every published count must be derived from.
     * Equal to {@code getFailures().size()} until the cap bites, and larger after it.
     */
    public int getTotalFailureCount() {
        return totalFailureCount;
    }

    /**
     * How many entries failed in each caller array, whether or not they fit in {@link
     * #getFailures()}.
     *
     * <p>The row cap is shared across both arrays and the walks run in a fixed order, so one array
     * can fill every slot and leave the other counted but unlisted. Without this a caller reading a
     * capped refusal would fix everything it was shown, resend, and only then discover a second
     * array was broken too — the round-trip this whole refusal exists to end.</p>
     */
    public Map<String, Integer> getTotalsByArray() {
        return totalsByArray;
    }
}
