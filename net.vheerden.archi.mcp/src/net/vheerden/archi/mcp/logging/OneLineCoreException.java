package net.vheerden.archi.mcp.logging;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;

import net.vheerden.archi.mcp.model.CallerText;

/**
 * A {@link CoreException}'s stand-in when something in it would break a log entry, which stays a
 * {@code CoreException} so the entry is still written as Equinox writes one: the log writer adds the status as a
 * sub-entry, and {@link CoreException#printStackTrace} prints a {@code Contains:} line for each status the status
 * holds, then that status's exception. The stand-in carries an escaped copy of the status: each message escaped
 * as {@link CallerText#loggedMessage} escapes it, plugin, severity and code kept as they are, each exception stood
 * in, and each status it contains copied the same way. Its header names the original's class, and it keeps the
 * original's frames. A status is never changed in place, since another component may still hold it.
 */
final class OneLineCoreException extends CoreException {

    private static final long serialVersionUID = 1L;

    private final String header;

    /** Over {@code escapedStatus}, a copy made by {@link #shell}; its exception and children are filled after. */
    OneLineCoreException(CoreException original, IStatus escapedStatus) {
        super(escapedStatus);
        header = CallerText.loggedMessage(original.toString());
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

    /** Whether any message in {@code status}, or any status it contains, needs escaping. */
    static boolean needsEscaping(IStatus status, Map<Object, Boolean> seen,
            Function<Throwable, Boolean> throwableNeedsEscaping) {
        if (status == null || seen.put(status, Boolean.TRUE) != null) {
            return false;
        }
        String message = status.getMessage();
        if (message != null && !message.equals(CallerText.loggedMessage(message))) {
            return true;
        }
        if (Boolean.TRUE.equals(throwableNeedsEscaping.apply(status.getException()))) {
            return true;
        }
        for (IStatus child : children(status)) {
            if (needsEscaping(child, seen, throwableNeedsEscaping)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An escaped view of {@code original}, with no exception or children yet. Its plugin, severity, code and
     * whether it says it is a multi-status are read once, here, and carried over as they are, so a status that
     * {@link org.eclipse.core.runtime.Status} would refuse to be built with (no plugin id, or a combined
     * severity) is escaped too; the log writer reads a status through {@link IStatus} only.
     */
    static IStatus shell(IStatus original) {
        return new EscapedStatus(original);
    }

    /**
     * Fills {@code shell}, made by {@link #shell} from {@code original}, with the stand-in of the original's
     * exception and an escaped copy of each status it contains. A status held by two parents is copied under
     * each, as the log writer prints it under each; only a status that contains itself, met again below
     * itself, is left out, so a cycle ends rather than repeats.
     */
    static void fill(IStatus shell, IStatus original, Function<Throwable, Throwable> standIn) {
        fill((EscapedStatus) shell, original, standIn, new IdentityHashMap<>());
    }

    private static void fill(EscapedStatus shell, IStatus original, Function<Throwable, Throwable> standIn,
            Map<IStatus, Boolean> ancestors) {
        Throwable exception = original.getException();
        shell.exception = exception == null ? null : standIn.apply(exception);
        ancestors.put(original, Boolean.TRUE);
        List<IStatus> copies = new ArrayList<>();
        for (IStatus child : children(original)) {
            if (child == null || ancestors.containsKey(child)) {
                continue;
            }
            EscapedStatus copy = new EscapedStatus(child);
            fill(copy, child, standIn, ancestors);
            copies.add(copy);
        }
        ancestors.remove(original);
        shell.children = copies.toArray(new IStatus[0]);
    }

    private static IStatus[] children(IStatus status) {
        IStatus[] children = status.getChildren();
        return children == null ? new IStatus[0] : children;
    }

    private static String escaped(String message) {
        return message == null ? null : CallerText.loggedMessage(message);
    }

    /**
     * A status read once from another, its message escaped. Its exception and children are set once their
     * stand-ins exist. It answers {@link #isMultiStatus()} as the original did, since the log writer adds a
     * status's children as sub-entries only for a multi-status, while a stack trace prints them for any status.
     */
    private static final class EscapedStatus implements IStatus {

        private final String plugin;
        private final int severity;
        private final int code;
        private final String message;
        private final boolean multiStatus;
        private Throwable exception;
        private IStatus[] children = new IStatus[0];

        EscapedStatus(IStatus original) {
            plugin = original.getPlugin();
            severity = original.getSeverity();
            code = original.getCode();
            message = escaped(original.getMessage());
            multiStatus = original.isMultiStatus();
        }

        @Override
        public IStatus[] getChildren() {
            return children.clone();
        }

        @Override
        public int getCode() {
            return code;
        }

        @Override
        public Throwable getException() {
            return exception;
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public String getPlugin() {
            return plugin;
        }

        @Override
        public int getSeverity() {
            return severity;
        }

        @Override
        public boolean isMultiStatus() {
            return multiStatus;
        }

        @Override
        public boolean isOK() {
            return severity == OK;
        }

        @Override
        public boolean matches(int severityMask) {
            return (severity & severityMask) != 0;
        }
    }
}
