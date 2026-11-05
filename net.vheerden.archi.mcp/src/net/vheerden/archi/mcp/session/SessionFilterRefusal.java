package net.vheerden.archi.mcp.session;

/**
 * A session filter value {@link SessionManager#setSessionFilter} refuses, with the parameter it was sent
 * as. The message repeats the caller's value, so a reader that picks a suggestion must go by
 * {@link #parameter()}, never by words in the message.
 */
public final class SessionFilterRefusal extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The parameter whose value was refused. */
    public enum Parameter { TYPE, LAYER, FIELDS, EXCLUDE }

    private final Parameter parameter;

    public SessionFilterRefusal(Parameter parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    public Parameter parameter() {
        return parameter;
    }
}
