package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.slf4j.Logger;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * A server fault inside one bulk-mutate operation reaches the Archi log, once, with its stack.
 *
 * <p>The refusal and the continue-on-error result both carry only each operation's error code as
 * text, and the refusal's own code is the whole-call one, so the shared rule never saw an
 * operation's {@code INTERNAL_ERROR}: the client read a row saying the server failed, and the log
 * held nothing. Every failed operation passes through {@link BulkValidationFailures#record} on
 * both paths, so that is where it is logged.</p>
 *
 * <p>Observed through a recording proxy, because SLF4J is bound to nothing in this lane.</p>
 */
public class BulkValidationFailuresTest {

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

    private List<String> levelsAboveDebug() {
        return calls.stream().map(Call::method)
                .filter(m -> !m.equals("debug") && !m.equals("trace")).toList();
    }

    @Test
    public void shouldLogErrorWithThrowable_whenAnOperationFailsWithInternalError() {
        BulkValidationFailures failures = new BulkValidationFailures(log);
        ModelAccessException fault = new ModelAccessException(
                "Font metrics measurement failed: no device", new IOException("no device"),
                ErrorCode.INTERNAL_ERROR);

        failures.record(2, "add-group-to-view", fault);

        assertEquals("exactly one log call: " + levelsAboveDebug(), 1, calls.size());
        Call call = calls.get(0);
        assertSame("the operation's own exception is the last argument, so its stack is printed",
                fault, call.args()[call.args().length - 1]);
        assertEquals("error", call.method());
    }

    @Test
    public void shouldAddNoLineAboveDebug_whenAnOperationFailsWithAClientError() {
        BulkValidationFailures failures = new BulkValidationFailures(log);

        failures.record(0, "create-element", new ModelAccessException(
                "Unknown type", ErrorCode.INVALID_ELEMENT_TYPE));
        failures.record(1, "create-element", new ModelAccessException(
                "name is required", new IllegalArgumentException("blank"), ErrorCode.INVALID_PARAMETER));
        failures.recordCascade(2, "add-to-view", "Operation 2 references operation 0, which failed");

        assertEquals("a caller's mistake must not reach the user's log: " + levelsAboveDebug(),
                List.of(), levelsAboveDebug());
    }

    /** Each failed operation is logged once, in the order recorded, and the rows are unchanged. */
    @Test
    public void shouldLogEachInternalOperationOnce_whenSeveralFail() {
        BulkValidationFailures failures = new BulkValidationFailures(log);
        ModelAccessException first = new ModelAccessException("first", ErrorCode.INTERNAL_ERROR);
        ModelAccessException second = new ModelAccessException("second", ErrorCode.INTERNAL_ERROR);

        failures.record(0, "remove-from-view", first);
        failures.record(1, "create-element", new ModelAccessException("bad", ErrorCode.INVALID_PARAMETER));
        failures.record(3, "add-to-view", second);

        assertEquals(List.of("error", "error"), levelsAboveDebug());
        assertSame(first, calls.get(0).args()[calls.get(0).args().length - 1]);
        assertSame(second, calls.get(calls.size() - 1).args()[calls.get(calls.size() - 1).args().length - 1]);
        assertEquals(3, failures.rows().size());
        assertEquals("INTERNAL_ERROR", failures.rows().get(0).errorCode());
    }
}
