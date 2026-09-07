package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.ParamNameDiagnostics;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.FailureRow;
import net.vheerden.archi.mcp.response.dto.MutationResultDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Shared static helpers for mutation handler classes.
 *
 * <p>Eliminates code duplication across ElementCreationHandler,
 * ElementUpdateHandler, DiscoveryHandler, and MutationHandler.
 * Every method is stateless — dependencies are passed as parameters.</p>
 */
public final class HandlerUtils {

    private static final Logger logger = LoggerFactory.getLogger(HandlerUtils.class);

    private HandlerUtils() {
        // utility class
    }

    // ---- Parameter extraction ----

    /**
     * Extracts a required string parameter from the arguments map.
     *
     * @throws ModelAccessException with INVALID_PARAMETER if missing/empty
     */
    public static String requireStringParam(Map<String, Object> args, String paramName) {
        if (args == null) {
            throw new ModelAccessException(
                    "Missing required parameter: " + paramName,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide the '" + paramName + "' parameter",
                    null);
        }
        Object value = args.get(paramName);
        if (!(value instanceof String str) || str.isBlank()) {
            throw ParamNameDiagnostics.missingHandlerParameter(args, paramName);
        }
        return str;
    }

    /**
     * Extracts a required string parameter that accepts the empty string as a value.
     *
     * <p>The key must still be present and hold a string; only the blank rejection is dropped. Use
     * this where the wrapped model treats "" as a legitimate stored value — a view group with no
     * title, for instance — so the tool surface is neither stricter nor more forgiving than the
     * model itself. An absent key and an explicitly empty value stay different requests.</p>
     *
     * <p>{@code optionalStringParamAllowEmpty} is not a substitute: it returns null for an absent
     * key, which would turn a required parameter into a null reaching the accessor.</p>
     *
     * @throws ModelAccessException with INVALID_PARAMETER if the key is absent or not a string
     */
    public static String requireStringParamAllowEmpty(Map<String, Object> args, String paramName) {
        if (args == null) {
            throw new ModelAccessException(
                    "Missing required parameter: " + paramName,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide the '" + paramName + "' parameter",
                    null);
        }
        if (!(args.get(paramName) instanceof String str)) {
            throw ParamNameDiagnostics.missingHandlerParameter(args, paramName, true);
        }
        return str;
    }

    /**
     * Extracts an optional string parameter, returning null if absent or blank.
     */
    public static String optionalStringParam(Map<String, Object> args, String paramName) {
        if (args == null) return null;
        Object value = args.get(paramName);
        if (value instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }

    /**
     * Extracts an optional string parameter, preserving empty strings.
     * Returns null only if absent; empty strings are returned as-is.
     * Needed for styling colour params where "" means "clear to default".
     */
    public static String optionalStringParamAllowEmpty(Map<String, Object> args, String paramName) {
        if (args == null) return null;
        Object value = args.get(paramName);
        if (value instanceof String str) {
            return str;
        }
        return null;
    }

    /**
     * Extracts an optional integer parameter, returning null if absent.
     * Handles JSON number coercion (Double/Integer/Long) from the MCP SDK.
     */
    public static Integer optionalIntegerParam(Map<String, Object> args, String paramName) {
        if (args == null) return null;
        Object value = args.get(paramName);
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    /**
     * Extracts an optional boolean parameter, returning false if absent.
     */
    public static boolean optionalBooleanParam(Map<String, Object> args, String paramName) {
        return optionalBooleanParam(args, paramName, false);
    }

    /**
     * Extracts an optional boolean parameter with a specified default value.
     * Handles both {@code Boolean} and {@code String} inputs (MCP clients may
     * send boolean params as strings).
     */
    public static boolean optionalBooleanParam(Map<String, Object> args, String paramName,
                                                boolean defaultValue) {
        if (args == null) return defaultValue;
        Object value = args.get(paramName);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    /**
     * Extracts an optional boxed {@code Boolean} parameter — returns {@code null}
     * when the param is absent or unparseable. Used when null carries semantic
     * meaning (e.g., "leave unchanged" for update operations). Used for
     * {@code associationDirected}.
     */
    public static Boolean optionalBoxedBooleanParam(Map<String, Object> args, String paramName) {
        if (args == null) return null;
        Object value = args.get(paramName);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    /**
     * Extracts an optional double parameter with a specified default value.
     * Handles JSON number coercion (Double/Integer/Long) from the MCP SDK
     * and String inputs (some MCP clients send numeric params as strings).
     */
    public static double optionalDoubleParam(Map<String, Object> args, String paramName,
                                              double defaultValue) {
        if (args == null) return defaultValue;
        Object value = args.get(paramName);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Extracts an optional map parameter, converting all values to strings.
     * Null values in the source map are stripped (use
     * {@link #optionalMapParamWithNulls} for property removal semantics).
     */
    public static Map<String, String> optionalMapParam(Map<String, Object> args, String paramName) {
        if (args == null) return null;
        Object value = args.get(paramName);
        if (value instanceof Map<?, ?> rawMap && !rawMap.isEmpty()) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() != null) {
                    result.put(key, String.valueOf(entry.getValue()));
                }
            }
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    /**
     * Extracts an optional map parameter preserving null values.
     * Needed for update-element property removal where {@code {"key": null}}
     * means "remove this property".
     */
    public static Map<String, String> optionalMapParamWithNulls(Map<String, Object> args,
                                                                 String paramName) {
        if (args == null) return null;
        Object value = args.get(paramName);
        if (value instanceof Map<?, ?> rawMap && !rawMap.isEmpty()) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue() != null
                            ? String.valueOf(entry.getValue()) : null);
                }
            }
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    // ---- Model guards ----

    /**
     * Throws {@link NoModelLoadedException} if no model is loaded.
     */
    public static void requireModelLoaded(ArchiModelAccessor accessor) {
        if (!accessor.isModelLoaded()) {
            throw new NoModelLoadedException();
        }
    }

    // ---- Session ----

    /**
     * Extracts the session ID from the exchange, or returns "default" when
     * session management is unavailable.
     *
     * @param sessionManager nullable — null in test mode without session management
     */
    public static String extractSessionId(SessionManager sessionManager,
                                           McpSyncServerExchange exchange) {
        return (sessionManager != null)
                ? SessionManager.extractSessionId(exchange) : "default";
    }

    // ---- Error responses ----

    public static McpSchema.CallToolResult buildModelNotLoadedError(
            ResponseFormatter formatter, NoModelLoadedException e) {
        logger.debug("Tool error (expected): {}", e.getMessage());
        ErrorResponse error = new ErrorResponse(
                ErrorCode.MODEL_NOT_LOADED,
                e.getMessage(),
                null,
                "Open an ArchiMate model in ArchimateTool",
                null);
        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
    }

    public static McpSchema.CallToolResult buildModelAccessError(
            ResponseFormatter formatter, ModelAccessException e) {
        logger.debug("Tool error (expected): {} [{}]", e.getMessage(), e.getErrorCode());
        ErrorResponse error = new ErrorResponse(
                e.getErrorCode(),
                e.getMessage(),
                e.getDetails(),
                e.getSuggestedCorrection(),
                e.getArchiMateReference());
        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
    }

    public static McpSchema.CallToolResult buildMutationError(
            ResponseFormatter formatter, MutationException e) {
        logger.warn("Tool error (session survived): mutation failed - {}", e.getMessage());
        ErrorResponse error = new ErrorResponse(
                ErrorCode.MUTATION_FAILED,
                "Mutation failed: " + e.getMessage(),
                null,
                "Retry the operation or check the model state",
                null);
        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
    }

    public static McpSchema.CallToolResult buildInternalError(
            ResponseFormatter formatter, String message) {
        logger.warn("Tool error (session survived): internal error - {}", message);
        ErrorResponse error = new ErrorResponse(
                ErrorCode.INTERNAL_ERROR, message);
        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
    }

    // ---- Multi-failure refusals ----

    /**
     * How many failures a refusal names individually in {@code nextSteps} before the closing entry
     * states how many are left. {@code nextSteps} is prose an agent reads in order rather than data
     * it indexes by position, so it is the half worth bounding on every tool that refuses this way.
     *
     * <p>Whether the rows themselves are also capped is the calling tool's decision, and the two
     * tools differ: {@code bulk-mutate} carries every row because its input ceiling already bounds
     * them, while {@code apply-positions} accepts ten thousand entries and caps its own.</p>
     */
    public static final int NEXT_STEP_FAILURE_LIMIT = 10;

    /**
     * Publishes a failure list into the map that carries it: the rows under {@code failed}, and
     * beside them the strings those rows share.
     *
     * <p>One call rather than two, because a reference is only honest while the thing it indexes
     * is beside it. Handing a caller the rows and trusting it to remember the dictionary is the
     * same defect one dropped line away — references into a map that was never emitted, which is
     * worse than the duplication this replaces, since the caller would be reading an index into
     * missing data. The key name is single-sourced here too, so the four places that publish rows
     * cannot come to disagree about what the array is called.</p>
     *
     * <p>Callers still decide <em>whether</em> to publish: every refusal builder guards on having
     * more than one failure, because one failure is already described in full by the scalar fields
     * beside it.</p>
     *
     * @param target the map the rows belong in — the error object on a refusal, the result object
     *               on a partial success
     */
    public static void putFailures(Map<String, Object> target,
            List<? extends FailureRow> failures) {
        Map<String, Object> shared = new LinkedHashMap<>();
        target.put("failed", failureRows(failures, shared));
        target.putAll(shared);
    }

    /**
     * How long a shared tail must be before carrying it once is worth the reference keys that
     * replace it.
     *
     * <p>Below this the dictionary costs more than it saves. Two rows are the worst case — the
     * dictionary entry is paid once and saved only once — and there the arithmetic breaks even
     * around seventy characters, so the gate sits above it rather than at it. The saving grows
     * with the number of rows sharing the string, which is the shape this defect has: a refusal is
     * large exactly when many entries are wrong in the same way.</p>
     */
    private static final int MIN_SHARED_TAIL_CHARS = 80;

    /**
     * Projects failed entries onto their wire rows, carrying what they all say once.
     *
     * <p>Single-sourced across every tool that refuses this way. These rows appear in more than one
     * envelope — beside the succeeded operations when {@code continueOnError} let a bulk call
     * proceed, and inside the error object when a call was refused whole — and an agent that
     * learned the shape from one must be able to read the others. Two hand-built copies of one row
     * shape drift, and this repository has paid for that before.</p>
     *
     * <p>The request index leads, then whatever keys say what that index is an index <em>into</em>,
     * then the failure itself. A tool with one input array names the tool it attempted; a tool with
     * two names which array the entry came from, because an index alone cannot distinguish them.</p>
     *
     * <p><b>What the rows share, they no longer repeat.</b> A refusal is largest exactly when many
     * entries are wrong in the same way, and entries wrong in the same way carry the same remedy:
     * fifty stale ids earned fifty byte-identical copies of one sentence, and a hundred and fifty
     * misspelled tool names republished the whole supported-tools list once per row. The bytes a
     * caller pays for a refusal should be proportional to how many <em>distinct</em> things are
     * wrong, not to how many entries happen to be wrong in the same way. So a string every row of a
     * group ends with is published once, under {@link #MIN_SHARED_TAIL_CHARS} — see
     * {@link #sharedTail} for what may be split off and where.</p>
     *
     * <p>Each row stays <b>self-describing</b>: whether a key is present on row <em>n</em> depends
     * only on row <em>n</em>'s own content, never on what row <em>n−1</em> carried, so a client can
     * read any row without having read the ones before it. That is why this is a dictionary with a
     * per-row key and not a delta against the previous row. A row whose field was split keeps its
     * own head under the field's own name and names the shared remainder; a row that shares the
     * <em>whole</em> field carries the reference alone, because a truncated value under the name of
     * a whole one is the failure this must not commit — a client that ignores the reference must
     * find the key <em>absent</em> rather than find it holding a fragment.</p>
     */
    private static List<Map<String, Object>> failureRows(List<? extends FailureRow> failures,
            Map<String, Object> shared) {
        // A row must always keep a message of its own — see sharedTail. A correction may go to
        // the dictionary whole, because a remedy identical on every row is one fact about the
        // server rather than anything this entry did.
        Map<String, String> messageTails =
                sharedTails(failures, FailureRow::message, false);
        Map<String, String> correctionTails =
                sharedTails(failures, FailureRow::suggestedCorrection, true);

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> messages = new LinkedHashMap<>();
        Map<String, Object> corrections = new LinkedHashMap<>();
        for (FailureRow failure : failures) {
            // The one key this row is grouped, referenced and indexed by. Read once and passed
            // down rather than re-read off the row: the group is keyed by the sanitised value and
            // a reference keyed by the raw one would name an entry the dictionary does not hold —
            // and, for a null code, would write a null map key that no JSON encoder will take.
            String key = groupKey(failure);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", failure.index());
            row.putAll(failure.identity());
            row.put("errorCode", failure.errorCode());
            put(row, "message", "messageRef", failure.message(),
                    messageTails.get(key), key, messages);
            put(row, "suggestedCorrection", "correctionRef", failure.suggestedCorrection(),
                    correctionTails.get(key), key, corrections);
            rows.add(row);
        }

        if (!messages.isEmpty()) {
            shared.put("messages", messages);
        }
        if (!corrections.isEmpty()) {
            shared.put("corrections", corrections);
        }
        return rows;
    }

    /**
     * Writes one field onto a row, splitting off the tail its group shares.
     *
     * <p>The dictionary is filled from the same pass that writes the references, so an entry
     * cannot exist that no row names and a row cannot name an entry that does not exist. Building
     * the two from separate walks is how that pair comes apart.</p>
     */
    private static void put(Map<String, Object> row, String field, String refKey, String value,
            String tail, String key, Map<String, Object> dictionary) {
        if (value == null) {
            // Absent rather than null: the row carried no such guidance, and a key holding null
            // claims there is one to read.
            return;
        }
        if (tail == null || !value.endsWith(tail)) {
            row.put(field, value);
            return;
        }
        String head = value.substring(0, value.length() - tail.length());
        if (!head.isEmpty()) {
            row.put(field, head);
        }
        row.put(refKey, key);
        dictionary.put(key, tail);
    }

    /**
     * The tail each group of failures shares, keyed by the group, or no entry when it shares none
     * worth carrying separately.
     *
     * <p>Grouped by error code rather than by string coincidence. Failures with the same code are
     * the same kind of failure and carry the same remedy by construction, so the grouping is a
     * property of the data rather than of how two sentences happened to end. It also gives the
     * dictionary a key that names what the entry <em>is</em> — a reference reading
     * {@code VIEW_OBJECT_NOT_FOUND} tells the reader which failure the sentence belongs to without
     * resolving it at all.</p>
     */
    private static Map<String, String> sharedTails(List<? extends FailureRow> failures,
            java.util.function.Function<FailureRow, String> field, boolean wholeValueAllowed) {
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (FailureRow failure : failures) {
            String value = field.apply(failure);
            if (value != null) {
                byGroup.computeIfAbsent(groupKey(failure), key -> new ArrayList<>()).add(value);
            }
        }
        Map<String, String> tails = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> group : byGroup.entrySet()) {
            // One row shares a string with nobody, and its own field already carries it.
            if (group.getValue().size() < 2) {
                continue;
            }
            String tail = sharedTail(group.getValue(), wholeValueAllowed);
            if (tail != null) {
                tails.put(group.getKey(), tail);
            }
        }
        return tails;
    }

    private static String groupKey(FailureRow failure) {
        return failure.errorCode() != null ? failure.errorCode() : "";
    }

    /**
     * The longest ending these values share that may be split off, or null when there is none.
     *
     * <p>Three rules bound what "may be split off". The tail must start where a sentence starts,
     * or be the whole value: a head cut mid-sentence would publish a field that reads as prose and
     * ends in the middle of a clause, and a reader who never resolves the reference would take it
     * for the whole message. It must be long enough to pay for the reference keys that replace it
     * ({@link #MIN_SHARED_TAIL_CHARS}), because a refusal whose rows share only a few characters
     * would otherwise grow. And the whole value may go only where {@code wholeValueAllowed} says
     * a row can stand without it — true of a remedy, false of a message.</p>
     *
     * <p>Splitting only at {@code ". "} also settles a hazard the character-level rule alone does
     * not: a boundary chosen by counting matching characters from the end can land inside a
     * surrogate pair, and a space never can.</p>
     */
    private static String sharedTail(List<String> values, boolean wholeValueAllowed) {
        String first = values.get(0);
        int common = first.length();
        for (String value : values) {
            common = Math.min(common, commonSuffixLength(first, value));
        }
        if (common == 0) {
            return null;
        }
        String candidate = first.substring(first.length() - common);
        boolean whole = true;
        for (String value : values) {
            whole &= value.length() == common;
        }
        if (whole && !wholeValueAllowed) {
            // Every value is the same string, so splitting a tail off would leave nothing on the
            // row. A message is the row's own account of what went wrong with this entry, and a
            // row that carries none is not self-describing however faithfully the dictionary
            // reassembles it. Two entries can genuinely fail in identical words — a name claimed
            // by two operations is reported the same way on both — and there the repetition is
            // the data.
            return null;
        }
        if (!whole) {
            // The tail is not the whole of every value, so somebody keeps a head — and that head
            // must end where a sentence ends. Characters before the first such boundary are shared
            // by accident, not because the rows are saying the same thing.
            int start = -1;
            for (int i = 1; i < candidate.length(); i++) {
                if (candidate.charAt(i) == ' ' && candidate.charAt(i - 1) == '.') {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                return null;
            }
            candidate = candidate.substring(start);
        }
        return candidate.length() >= MIN_SHARED_TAIL_CHARS ? candidate : null;
    }

    private static int commonSuffixLength(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i;
    }

    /**
     * The first sentence of a failure message, for the one-line-per-failure guidance.
     *
     * <p>{@code nextSteps} is a list an agent reads in order, and the row it points at already
     * carries the message in full. Restating a whole message per entry was paying for the longest
     * one repeatedly: an unsupported tool name enumerates all twenty-eight supported tools, so a
     * payload with three misspelled names published that list seven times in one refusal. Cutting
     * at the first sentence keeps the entry pointing at the right failure without republishing the
     * row beside it.</p>
     */
    public static String headline(String message) {
        if (message == null) {
            // Its sibling in the collector degrades a null message to the text "null" through
            // string concatenation. Crashing here instead would lose the whole refusal to a
            // generic internal error — the worst place to fail is while reporting a failure.
            return "(no message)";
        }
        int stop = message.indexOf(". ");
        return stop > 0 ? message.substring(0, stop + 1) : message;
    }

    // ---- Result wrapping ----

    public static McpSchema.CallToolResult buildResult(String json, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(isError)
                .build();
    }

    // ---- Mutation response formatting ----

    /**
     * Formats a mutation result into the standard envelope with batch and
     * approval support. Used by ElementCreationHandler, ElementUpdateHandler,
     * and BulkMutationHandler.
     *
     * <p>Checks in order: proposal → batched → immediate. When in approval
     * mode, the entity is returned as a "preview" alongside proposal metadata.</p>
     */
    public static <T> McpSchema.CallToolResult formatMutationResponse(
            T entity, MutationResult<?> result, List<String> nextSteps,
            ArchiModelAccessor accessor, ResponseFormatter formatter) {
        return formatMutationResponse(entity, result, nextSteps, List.of(), accessor, formatter);
    }

    /**
     * The same, for a tool that has state-dependent guidance to disclose when the write is
     * awaiting a human's approval.
     *
     * <p>{@code nextSteps} and {@code approvalDisclosures} are deliberately separate lists rather
     * than one list reused on both arms. The immediate list is composed in the present tense and
     * asserts the write landed ("the new paths were applied anyway; undo to restore"). Forwarding
     * it to the approval arm would put that claim in front of a caller whose change has not been
     * applied and cannot be undone — a larger defect than the silence it would close, and one that
     * would land on every tool at once. So the approval arm receives only what a caller passes
     * explicitly for it, and a tool that passes nothing keeps exactly the three fixed lines.</p>
     */
    public static <T> McpSchema.CallToolResult formatMutationResponse(
            T entity, MutationResult<?> result, List<String> nextSteps,
            List<String> approvalDisclosures,
            ArchiModelAccessor accessor, ResponseFormatter formatter) {
        String modelVersion = accessor.getModelVersion();

        // Approval mode: return proposal response
        if (result.isProposal()) {
            return formatProposalResponse(entity, result.proposalContext(),
                    modelVersion, formatter, approvalDisclosures);
        }

        Object responseEntity;
        if (result.isBatched()) {
            MutationResultDto batchDto = new MutationResultDto(
                    true,
                    "Mutation queued for batch commit",
                    result.batchSequenceNumber());
            Map<String, Object> batchResponse = new LinkedHashMap<>();
            batchResponse.put("batch", batchDto);
            batchResponse.put("preview", entity);
            responseEntity = batchResponse;
        } else {
            responseEntity = entity;
        }

        Map<String, Object> envelope = formatter.formatSuccess(
                responseEntity, nextSteps, modelVersion, 1, 1, false);

        return buildResult(formatter.toJsonString(envelope), false);
    }

    /**
     * Formats a proposal response when approval mode is active.
     * Returns the entity as a preview alongside proposal metadata.
     */
    public static McpSchema.CallToolResult formatProposalResponse(
            Object entity, ProposalContext proposal,
            String modelVersion, ResponseFormatter formatter) {
        return formatProposalResponse(entity, proposal, modelVersion, formatter, List.of());
    }

    /**
     * The same, with guidance a tool has composed for a change that has not been applied.
     *
     * <p>The three fixed lines below stay first and stay unchanged: approval mode is human-owned,
     * so they are the correct instruction for every tool. The disclosures are appended after them,
     * and they are the tool's own — never the immediate arm's list forwarded, which is present
     * tense by construction. A caller with nothing to add gets exactly the three lines.</p>
     */
    public static McpSchema.CallToolResult formatProposalResponse(
            Object entity, ProposalContext proposal,
            String modelVersion, ResponseFormatter formatter,
            List<String> disclosures) {
        Map<String, Object> proposalInfo = new LinkedHashMap<>();
        proposalInfo.put("proposalId", proposal.proposalId());
        proposalInfo.put("status", "pending");
        proposalInfo.put("description", proposal.description());
        proposalInfo.put("createdAt", proposal.createdAt().toString());

        Map<String, Object> responseEntity = new LinkedHashMap<>();
        responseEntity.put("proposal", proposalInfo);
        responseEntity.put("preview", entity);

        // Override next steps to approval-specific ones. Approval mode is human-owned:
        // the agent observes the gate but cannot approve its own queued change.
        List<String> approvalNextSteps = new ArrayList<>(List.of(
                "This change is pending the human's approval and was NOT applied",
                "Tell the user to approve or reject it in Archi (the agent cannot approve its own changes)",
                "Use list-pending-approvals to see all changes awaiting the human's decision"));
        if (disclosures != null) {
            approvalNextSteps.addAll(disclosures);
        }

        Map<String, Object> envelope = formatter.formatSuccess(
                responseEntity, approvalNextSteps, modelVersion, 1, 1, false);

        return buildResult(formatter.toJsonString(envelope), false);
    }
}
