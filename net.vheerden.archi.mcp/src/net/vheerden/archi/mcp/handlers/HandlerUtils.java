package net.vheerden.archi.mcp.handlers;

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
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;
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
            throw new ModelAccessException(
                    "Missing or empty required parameter: " + paramName,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a non-empty string value for '" + paramName + "'",
                    null);
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
     * Story 11-2: Needed for styling colour params where "" means "clear to default".
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
        String modelVersion = accessor.getModelVersion();

        // Approval mode: return proposal response (Story 7-6)
        if (result.isProposal()) {
            return formatProposalResponse(entity, result.proposalContext(),
                    modelVersion, formatter);
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
        Map<String, Object> proposalInfo = new LinkedHashMap<>();
        proposalInfo.put("proposalId", proposal.proposalId());
        proposalInfo.put("status", "pending");
        proposalInfo.put("description", proposal.description());
        proposalInfo.put("createdAt", proposal.createdAt().toString());

        Map<String, Object> responseEntity = new LinkedHashMap<>();
        responseEntity.put("proposal", proposalInfo);
        responseEntity.put("preview", entity);

        // Override next steps to approval-specific ones
        List<String> approvalNextSteps = List.of(
                "Use list-pending-approvals to review all pending mutations",
                "Use decide-mutation with proposalId '" + proposal.proposalId()
                        + "' and decision 'approve' to apply this mutation",
                "Use decide-mutation with proposalId '" + proposal.proposalId()
                        + "' and decision 'reject' to discard this mutation");

        Map<String, Object> envelope = formatter.formatSuccess(
                responseEntity, approvalNextSteps, modelVersion, 1, 1, false);

        return buildResult(formatter.toJsonString(envelope), false);
    }
}
