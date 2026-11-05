package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.slf4j.Logger;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.LayoutValidationException;
import net.vheerden.archi.mcp.model.LayoutValidationFailures;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * How a {@link ModelAccessException} that becomes a client error is logged, and that the response
 * the client receives does not move when the logging does.
 *
 * <p>A model failure coded {@code INTERNAL_ERROR} is a server fault: the client is told only that
 * something went wrong on the server, so the Archi log is the one place its cause can be found. It
 * used to be logged at DEBUG, which the default level discards, so a user asked for their log after
 * such a failure had nothing to send. Every other code is a client error whose remedy is in the
 * caller's input, and stays at DEBUG so a caller's typo does not fill a user's log.</p>
 *
 * <p>The handlers' loggers are static and, in the headless test lane, SLF4J is bound to nothing, so
 * the rule takes its {@link Logger} as a parameter and is observed here through a recording
 * proxy. That pins the exact overload called and every argument passed to it.</p>
 */
public class HandlerUtilsTest {

    /** Captured from the builder before the logging change and kept unedited. */
    private static final String BASELINE_INTERNAL_JSON =
            "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"Failed to create output directory: "
                    + "/dev/null/x: Not a directory\"},\"_meta\":{\"sessionActive\":true}}";

    /** Captured from the builder before the logging change and kept unedited. */
    private static final String BASELINE_CLIENT_JSON =
            "{\"error\":{\"code\":\"INVALID_PARAMETER\",\"message\":\"View not found: v-1\","
                    + "\"details\":\"No view has id v-1\",\"suggestedCorrection\":\"Call get-views to "
                    + "list view ids\",\"archiMateReference\":\"ArchiMate 3.2 \\\"quoted\\\"\"},"
                    + "\"_meta\":{\"sessionActive\":true}}";

    private record Call(String method, Object[] args) {
    }

    private final List<Call> calls = new ArrayList<>();

    private final Logger log = (Logger) Proxy.newProxyInstance(
            Logger.class.getClassLoader(), new Class<?>[] {Logger.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.startsWith("is") && method.getReturnType() == boolean.class) {
                    return true;
                }
                if (name.equals("getName")) {
                    return "recording";
                }
                calls.add(new Call(name, args == null ? new Object[0] : args));
                return null;
            });

    private static ModelAccessException internalWithCause() {
        return new ModelAccessException(
                "Failed to create output directory: /dev/null/x: Not a directory",
                new FileSystemException("/dev/null/x: Not a directory"),
                ErrorCode.INTERNAL_ERROR);
    }

    private static ModelAccessException clientError() {
        return new ModelAccessException(
                "View not found: v-1", ErrorCode.INVALID_PARAMETER,
                "No view has id v-1", "Call get-views to list view ids", "ArchiMate 3.2 \"quoted\"");
    }

    private void assertSingleDebugWithoutThrowable(ModelAccessException e) {
        assertEquals("exactly one log call: " + calls, 1, calls.size());
        Call call = calls.get(0);
        assertEquals("a client error stays at DEBUG", "debug", call.method());
        assertArrayEquals("the DEBUG line is the one it always was, with no throwable",
                new Object[] {"Tool error (expected): {} [{}]", e.getMessage(), e.getErrorCode()},
                call.args());
    }

    @Test
    public void shouldLogErrorWithThrowable_whenCodeIsInternalError() {
        ModelAccessException e = internalWithCause();

        HandlerUtils.logModelAccessError(log, e);

        assertEquals("exactly one log call: " + calls, 1, calls.size());
        Call call = calls.get(0);
        assertEquals("a server fault is logged at ERROR", "error", call.method());
        assertSame("the exception must be the LAST argument, so its cause chain is printed",
                e, call.args()[call.args().length - 1]);
        assertEquals("format, message, throwable: " + List.of(call.args()), 3, call.args().length);
        assertTrue("the format names the failure", call.args()[0] instanceof String);
        assertEquals(e.getMessage(), call.args()[1]);
    }

    @Test
    public void shouldLogErrorWithThrowable_whenInternalErrorHasNoCause() {
        ModelAccessException e = new ModelAccessException(
                "Unexpected arrangement: diagonal", ErrorCode.INTERNAL_ERROR);

        HandlerUtils.logModelAccessError(log, e);

        assertEquals("exactly one log call: " + calls, 1, calls.size());
        assertEquals("the rule keys on the code, not on whether a cause exists",
                "error", calls.get(0).method());
        assertSame(e, calls.get(0).args()[calls.get(0).args().length - 1]);
    }

    @Test
    public void shouldLogDebugOnly_whenCodeIsClientError() {
        ModelAccessException invalidWithCause = new ModelAccessException(
                "Bad spacing", new IllegalArgumentException("negative gap"),
                ErrorCode.INVALID_PARAMETER);
        HandlerUtils.logModelAccessError(log, invalidWithCause);
        assertSingleDebugWithoutThrowable(invalidWithCause);

        calls.clear();
        ModelAccessException notOpen = new ModelAccessException(
                "No model with that id is open", ErrorCode.MODEL_NOT_OPEN);
        HandlerUtils.logModelAccessError(log, notOpen);
        assertSingleDebugWithoutThrowable(notOpen);

        calls.clear();
        LayoutValidationFailures failures = new LayoutValidationFailures();
        failures.recordMalformedEntry("positions", 0, new ModelAccessException(
                "positions[0] is not an object", new IllegalStateException("parse"),
                ErrorCode.INVALID_PARAMETER));
        LayoutValidationException layout = failures.toException();
        assertTrue("fixture: the layout refusal carries a cause", layout.getCause() != null);
        HandlerUtils.logModelAccessError(log, layout);
        assertSingleDebugWithoutThrowable(layout);
    }

    @Test
    public void shouldReturnBaselineJson_forInternalAndClientError() {
        ResponseFormatter formatter = new ResponseFormatter();

        McpSchema.CallToolResult internal =
                HandlerUtils.buildModelAccessError(formatter, internalWithCause());
        McpSchema.CallToolResult client = HandlerUtils.buildModelAccessError(formatter, clientError());

        assertEquals(Boolean.TRUE, internal.isError());
        assertEquals(1, internal.content().size());
        assertEquals(BASELINE_INTERNAL_JSON, ((McpSchema.TextContent) internal.content().get(0)).text());
        assertEquals(Boolean.TRUE, client.isError());
        assertEquals(1, client.content().size());
        assertEquals(BASELINE_CLIENT_JSON, ((McpSchema.TextContent) client.content().get(0)).text());
    }

    /**
     * An interrupted download is the server cancelling its own work, not a fault: the code stays
     * {@code INTERNAL_ERROR}, but the line is a WARN with no stack, so a routine shutdown does not
     * read as a crash in a user's log.
     */
    @Test
    public void shouldWarnWithoutThrowable_whenInternalErrorWasInterrupted() {
        ModelAccessException e = new ModelAccessException(
                "Image download interrupted: http://127.0.0.1/x.png",
                new InterruptedException(), ErrorCode.INTERNAL_ERROR);

        HandlerUtils.logModelAccessError(log, e);

        assertEquals("exactly one log call: " + calls, 1, calls.size());
        Call call = calls.get(0);
        for (Object arg : call.args()) {
            assertFalse("no throwable, so no stack: " + List.of(call.args()), arg instanceof Throwable);
        }
        assertEquals("a cancellation is a WARN, not an ERROR", "warn", call.method());
        assertEquals(e.getMessage(), call.args()[call.args().length - 1]);
    }

    /** The exception is narrow: any other cause of a server fault is still an ERROR with its stack. */
    @Test
    public void shouldStillLogErrorWithThrowable_whenInternalErrorHasAnIoCause() {
        ModelAccessException e = new ModelAccessException(
                "Failed to store image", new java.io.IOException("No space left on device"),
                ErrorCode.INTERNAL_ERROR);

        HandlerUtils.logModelAccessError(log, e);

        assertEquals("exactly one log call: " + calls, 1, calls.size());
        assertSame(e, calls.get(0).args()[calls.get(0).args().length - 1]);
        assertEquals("error", calls.get(0).method());
    }

    /**
     * The builder every tool's catch uses logs through the rule. Called with a recording logger,
     * so the wiring from builder to rule is observed, not inferred.
     */
    @Test
    public void shouldLogThroughTheRule_whenBuildingAModelAccessError() {
        ResponseFormatter formatter = new ResponseFormatter();
        ModelAccessException e = internalWithCause();

        McpSchema.CallToolResult result = HandlerUtils.buildModelAccessError(formatter, e, log);

        assertEquals("exactly one log call: " + calls, 1, calls.size());
        assertSame("the builder hands the exception to the rule",
                e, calls.get(0).args()[calls.get(0).args().length - 1]);
        assertEquals("error", calls.get(0).method());
        assertEquals("the logger parameter changes nothing the client reads",
                BASELINE_INTERNAL_JSON, ((McpSchema.TextContent) result.content().get(0)).text());
    }
}
