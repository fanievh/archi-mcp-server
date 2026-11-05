package net.vheerden.archi.mcp.model;

import org.slf4j.Logger;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Logs a {@link ModelAccessException} that is about to be answered to the client. The one place
 * that decides how such an error is logged.
 *
 * <p>{@code INTERNAL_ERROR} is a server fault: the client is told only that something failed,
 * so the Archi log is the only place its cause can be read. It is logged at ERROR with the
 * exception as the last argument, which makes SLF4J print the stack and its cause chain. Every
 * other code is a client error whose remedy is in the caller's input, and stays at DEBUG.</p>
 *
 * <p>The rule keys on the <em>code</em>, not on whether a cause is present. Some client errors
 * carry one (an {@code INVALID_PARAMETER} wrapping the {@code IllegalArgumentException} a bad
 * argument raised, a {@code LayoutValidationException} chaining its first entry's failure), and
 * logging those at ERROR would let a caller's typo fill a user's log. Equally, some server
 * faults have no cause at all and still need their message at ERROR.</p>
 *
 * <p>One cause type is the exception: an {@code INTERNAL_ERROR} caused by an
 * {@link InterruptedException}. That is the server cancelling its own work (Jetty stopping its
 * workers, a request abandoned), not a fault, and the client usually never reads the answer, so
 * it is a WARN without the stack. The check only ever moves an entry out of the ERROR set; it
 * cannot add one, and every other code is decided by the code alone.</p>
 *
 * <p>Lives in {@code model/} because a bulk-mutate refusal records its operations' failures
 * here, where the handlers cannot reach; the handlers delegate to it.</p>
 *
 * <p>Takes the {@link Logger} as a parameter so the rule can be exercised without an SLF4J
 * binding.</p>
 */
public final class ToolErrorLog {

    private ToolErrorLog() {
    }

    public static void log(Logger log, ModelAccessException e) {
        if (e.getErrorCode() != ErrorCode.INTERNAL_ERROR) {
            log.debug("Tool error (expected): {} [{}]", CallerText.loggedMessage(e.getMessage()), e.getErrorCode());
        } else if (e.getCause() instanceof InterruptedException) {
            log.warn("Tool interrupted, answered INTERNAL_ERROR: {}", CallerText.loggedMessage(e.getMessage()));
        } else {
            log.error("Tool failed with INTERNAL_ERROR: {}", CallerText.loggedMessage(e.getMessage()), e);
        }
    }
}
