package net.vheerden.archi.mcp.handlers;

import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.ModelSelection;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.PaginationCursor;

/**
 * Binds a pagination cursor to the model instance it pages.
 *
 * <p>A cursor's version check alone cannot tell two models apart: each instance counts its own changes,
 * so two models, or a reopened file and the instance it replaced, can read the same version. A cursor
 * issued on one model and honoured on another would return the other model's page at this cursor's
 * offset as though it continued the first. The paged read tools stamp each cursor with the model they
 * read and refuse a cursor whose stamp names a different one.</p>
 *
 * <p>{@link #decode} is the only production code that reads a cursor: it decodes it and runs the
 * model and version checks as one step, so a paged tool cannot honour a cursor it has decoded but not
 * checked. {@code CursorDecodeContractTest} keeps it that way.</p>
 */
final class CursorModelCheck {

    private CursorModelCheck() {} // utility class

    /**
     * The model instance the current call reads, or null when there is none to name: no model
     * resolves, or the accessor has no model selection (outside a tool call).
     */
    static PaginationCursor.CursorModel current(ArchiModelAccessor accessor) {
        ModelSelection selection = selection(accessor);
        return selection == null ? null : selection.cursorModel();
    }

    private static ModelSelection selection(ArchiModelAccessor accessor) {
        MutationDispatcher dispatcher = accessor.getMutationDispatcher();
        return dispatcher == null ? null : dispatcher.selection();
    }

    /** A decoded cursor the call may honour, or the refusal to return instead: exactly one is non-null. */
    record Decoded(PaginationCursor.CursorData cursor, ErrorResponse refusal) {
        Decoded {
            if ((cursor == null) == (refusal == null)) {
                throw new IllegalArgumentException("A decoded cursor carries either the cursor or its refusal");
            }
        }
    }

    /**
     * Reads a paged tool's cursor and checks, in this order, that it is well formed, that it pages the
     * model instance the current call reads, and that the model has not changed since it was cut. A
     * cross-model cursor is reported as such, not as a modified model. The version check abstains when
     * no model is open: the query then reports that itself.
     */
    static Decoded decode(String cursorParam, ArchiModelAccessor accessor) {
        PaginationCursor.CursorData cursor;
        try {
            cursor = PaginationCursor.decode(cursorParam);
        } catch (PaginationCursor.InvalidCursorException e) {
            return new Decoded(null, new ErrorResponse(
                    ErrorCode.INVALID_CURSOR,
                    e.getMessage(),
                    null,
                    "Re-run the original query without a cursor parameter",
                    null));
        }

        // A cursor pages one model instance: refuse it on any other, before its version is compared
        ErrorResponse otherModel = refusal(cursor, accessor);
        if (otherModel != null) {
            return new Decoded(null, otherModel);
        }

        String currentModelVersion = accessor.getModelVersion();
        if (currentModelVersion != null && !cursor.modelVersion().equals(currentModelVersion)) {
            return new Decoded(null, new ErrorResponse(
                    ErrorCode.INVALID_CURSOR,
                    "The model has been modified since this cursor was created",
                    null,
                    "Re-run the original query without a cursor parameter to get fresh results",
                    null));
        }
        return new Decoded(cursor, null);
    }

    /**
     * The refusal for a cursor that pages a model other than the one the current call reads, or null
     * when it may be honoured. Abstains (null) when either side names no model: with no model to read,
     * the query reports that itself, and a cursor that names none is checked by its version alone.
     */
    private static ErrorResponse refusal(PaginationCursor.CursorData cursor, ArchiModelAccessor accessor) {
        ModelSelection selection = selection(accessor);
        PaginationCursor.CursorModel reading = selection == null ? null : selection.cursorModel();
        if (reading == null) {
            return null;
        }
        if (cursor.modelId() != null && !cursor.modelId().equals(reading.id())) {
            if (!selection.isOpen(cursor.modelId())) {
                // Addressing the cursor's model would only be refused again: say it is gone.
                return new ErrorResponse(ErrorCode.INVALID_CURSOR,
                        "This cursor pages model " + describe(cursor.modelName(), cursor.modelId())
                                + ", which is no longer open, and this call reads model "
                                + describe(reading.name(), reading.id()),
                        null,
                        "Re-run the query without a cursor to page " + label(reading.name(), reading.id())
                                + ", or open " + label(cursor.modelName(), cursor.modelId())
                                + " in Archi and re-run the query on it.",
                        null);
            }
            return new ErrorResponse(ErrorCode.INVALID_CURSOR,
                    "This cursor pages model " + describe(cursor.modelName(), cursor.modelId())
                            + ", but this call reads model " + describe(reading.name(), reading.id()),
                    null,
                    "Pass modelId \"" + cursor.modelId() + "\" with the cursor to continue paging "
                            + label(cursor.modelName(), cursor.modelId())
                            + ", or re-run the query without a cursor to page "
                            + label(reading.name(), reading.id()) + ".",
                    null);
        }
        if (cursor.modelKey() != null && !cursor.modelKey().equals(reading.key())) {
            return new ErrorResponse(ErrorCode.INVALID_CURSOR,
                    "This cursor pages an earlier or another open instance of model "
                            + describe(cursor.modelName(), cursor.modelId())
                            + ": it was closed and reopened, or the file is open more than once",
                    null,
                    "Re-run the original query without a cursor parameter",
                    null);
        }
        return null;
    }

    /**
     * {@code 'Name' (id x)}; {@code (id x)} for a model with no name, {@code 'Name'} for a cursor that
     * names no id.
     */
    private static String describe(String name, String id) {
        if (name == null || name.isBlank()) {
            return id == null ? "(no id)" : "(id " + id + ")";
        }
        return id == null ? "'" + name + "'" : "'" + name + "' (id " + id + ")";
    }

    /** {@code 'Name'}, or the id for a model with no name. */
    private static String label(String name, String id) {
        return name == null || name.isBlank() ? id : "'" + name + "'";
    }
}
