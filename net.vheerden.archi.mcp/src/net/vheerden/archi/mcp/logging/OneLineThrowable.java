package net.vheerden.archi.mcp.logging;

import java.util.IdentityHashMap;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;

import net.vheerden.archi.mcp.model.CallerText;

/**
 * A logged throwable's stand-in when its header would break a log entry. A stack trace prints each
 * throwable's {@link Throwable#toString()} (its class and message) as the header line and on every
 * {@code Caused by:} line, so a line break in any message in the chain, which can hold a caller's text,
 * splits the entry or forges a new one. The stand-in prints the same header with the characters
 * {@link CallerText#loggedMessage} escapes escaped, keeps the original's stack frames, and stands in for each
 * cause and suppressed throwable the same way. It fills in no stack of its own. A {@link CoreException} is stood
 * in as a {@code CoreException} ({@link OneLineCoreException}), and the statuses it contains are read too: a line
 * break in any of their messages is escaped as well, whatever plugin id or severity they carry. A throwable that
 * cannot be read (its {@code toString} or {@code getMessage} throws, a status in it has a getter that throws, or
 * its chain is too deep to walk) is logged as it is, so the entry is still written.
 */
final class OneLineThrowable extends Throwable {

    private static final long serialVersionUID = 1L;

    private final String header;

    private OneLineThrowable(Throwable original) {
        super(escaped(original.getMessage()));
        String text = original.toString();
        header = CallerText.loggedMessage(text);
        setStackTrace(original.getStackTrace());
    }

    @Override
    public String toString() {
        return header;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this; // the original's frames are set instead
    }

    /**
     * {@code throwable} itself when nothing in it needs escaping, or {@code null} for {@code null}; otherwise a
     * stand-in that prints as one line per header.
     */
    static Throwable of(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        try {
            return needsEscaping(throwable, new IdentityHashMap<>())
                    ? standIn(throwable, new IdentityHashMap<>())
                    : throwable;
        } catch (RuntimeException | StackOverflowError unreadable) {
            return throwable;
        }
    }

    private static boolean needsEscaping(Throwable t, Map<Object, Boolean> seen) {
        if (t == null || seen.put(t, Boolean.TRUE) != null) {
            return false;
        }
        String text = t.toString();
        if (!text.equals(CallerText.loggedMessage(text))) {
            return true;
        }
        if (t instanceof CoreException core
                && OneLineCoreException.needsEscaping(core.getStatus(), seen, next -> needsEscaping(next, seen))) {
            return true;
        }
        for (Throwable suppressed : t.getSuppressed()) {
            if (needsEscaping(suppressed, seen)) {
                return true;
            }
        }
        return needsEscaping(t.getCause(), seen);
    }

    private static Throwable standIn(Throwable t, Map<Throwable, Throwable> made) {
        Throwable existing = made.get(t);
        if (existing != null) {
            return existing;
        }
        if (t instanceof CoreException core) {
            IStatus status = OneLineCoreException.shell(core.getStatus());
            OneLineCoreException copy = new OneLineCoreException(core, status);
            made.put(t, copy);
            // Its cause is its status's exception, so the status is filled rather than initCause called.
            OneLineCoreException.fill(status, core.getStatus(), next -> {
                Throwable standIn = standIn(next, made);
                return standIn == copy ? null : standIn;
            });
            suppressed(t, copy, made);
            return copy;
        }
        OneLineThrowable copy = new OneLineThrowable(t);
        made.put(t, copy);
        Throwable cause = t.getCause();
        if (cause != null) {
            Throwable causeStandIn = standIn(cause, made);
            if (causeStandIn != copy) { // a getCause that answers the throwable itself
                copy.initCause(causeStandIn);
            }
        }
        suppressed(t, copy, made);
        return copy;
    }

    private static void suppressed(Throwable t, Throwable copy, Map<Throwable, Throwable> made) {
        for (Throwable suppressed : t.getSuppressed()) {
            Throwable standIn = standIn(suppressed, made);
            if (standIn != copy) {
                copy.addSuppressed(standIn);
            }
        }
    }

    private static String escaped(String message) {
        return message == null ? null : CallerText.loggedMessage(message);
    }
}
