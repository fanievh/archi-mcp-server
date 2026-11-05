package net.vheerden.archi.mcp.response;

/**
 * Enumeration of structured error codes for MCP tool responses.
 *
 * <p>Used in {@link ErrorResponse} to provide machine-readable error
 * classification. Each code maps to a specific error category that
 * LLM clients can use to determine appropriate recovery actions.</p>
 */
public enum ErrorCode {

    ELEMENT_NOT_FOUND,
    FOLDER_NOT_FOUND,
    MODEL_NOT_LOADED,
    INVALID_PARAMETER,
    INVALID_CURSOR,
    VIEW_NOT_FOUND,
    INTERNAL_ERROR,
    BATCH_ALREADY_ACTIVE,
    BATCH_NOT_ACTIVE,
    MUTATION_FAILED,
    INVALID_ELEMENT_TYPE,
    INVALID_RELATIONSHIP_TYPE,
    RELATIONSHIP_NOT_ALLOWED,
    SOURCE_ELEMENT_NOT_FOUND,
    TARGET_ELEMENT_NOT_FOUND,
    POTENTIAL_DUPLICATES,
    BULK_VALIDATION_FAILED,
    PROPOSAL_STALE,
    RELATIONSHIP_NOT_FOUND,
    VIEW_OBJECT_NOT_FOUND,
    CONNECTION_ALREADY_ON_VIEW,
    RELATIONSHIP_MISMATCH,
    FORMAT_NOT_AVAILABLE,
    FOLDER_NOT_EMPTY,
    CANNOT_DELETE_DEFAULT_FOLDER,
    CIRCULAR_FOLDER_REFERENCE,
    CANNOT_MOVE_DEFAULT_FOLDER,
    CANNOT_CREATE_ROOT_FOLDER,
    ALREADY_IN_TARGET_FOLDER,
    OBJECT_NOT_FOUND,
    INVALID_MOVE_TARGET,
    FOLDER_LAYER_MISMATCH,
    /** Supplied imagePath does not resolve to bytes in the model archive. */
    IMAGE_NOT_FOUND,
    /**
     * A batch, a proposal, or a call in progress is bound to one open model while a different model is
     * now active, so the work would be recorded in the wrong model's undo history. Nothing was applied
     * to the active model; for a call in progress, the message says whether the call's own earlier
     * changes to its model were undone.
     */
    BOUND_MODEL_NOT_ACTIVE,
    /**
     * The model a batch, a proposal, or a call in progress is bound to has been closed. Distinct from
     * {@link #BOUND_MODEL_NOT_ACTIVE} because the remedy differs: reopen the model, not switch to it.
     */
    BOUND_MODEL_CLOSED,
    /**
     * A write named no model while two or more models are open and the session has no default, so
     * which model it would change is a coin flip. Nothing was applied; the details list the open
     * models by id and name. Retry with {@code modelId}.
     */
    MODEL_AMBIGUOUS,
    /** The model a call names (by {@code modelId}, or as the session's default) is not open in Archi. */
    MODEL_NOT_OPEN,
    /**
     * The {@code modelId} a call names is open more than once: a copied file keeps its model id, and
     * Archi opens both copies. Id and name are identical across copies, so the details name each
     * copy by its file. The call resolved to none of them.
     */
    MODEL_COPIES_AMBIGUOUS,
    /**
     * Undo or redo was refused because another session currently works on the same model. Archi keeps
     * one undo history per model, not per session, so the step could undo the other session's change.
     */
    MODEL_SHARED,
    /**
     * A call inside an open batch names a different model from the one the batch is bound to. Nothing
     * was queued; end the batch first, or correct the call's {@code modelId}.
     */
    BATCH_MODEL_MISMATCH
}
