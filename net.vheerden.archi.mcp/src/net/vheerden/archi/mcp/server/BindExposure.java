package net.vheerden.archi.mcp.server;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Whether a bind address makes the server reachable from other machines: the one answer every
 * decision about exposure reads.
 *
 * <p>The answer has three values, not two, because "closed" means opposite things to the two
 * consumers. For the Origin/Host guard the closed answer to an address it cannot classify is
 * <em>enforce</em>; for server file access (image file paths, URLs, exported files) it is
 * <em>refuse</em>. A single boolean can be closed for one of them only. So a bind that is null,
 * blank or does not resolve is {@link #UNKNOWN}, and each consumer's mapping of it is written once,
 * here, beside the classification.</p>
 *
 * <p>{@link #of(String)} is the only lookup of a bind for exposure. The server classifies the bind
 * once as it starts, in {@link TransportConfig}, and hands that one value to both the guard and the
 * file-access decision, so a second lookup that disagrees with the first (a DNS change mid-start, a
 * hostname whose resolution flips) cannot give the two decisions different answers.</p>
 */
public enum BindExposure {

    /**
     * The address the bind resolves to is a loopback address: reachable from this machine only. A
     * hostname is judged by the address it resolves to first, which is the one the server binds.
     */
    LOOPBACK,
    /** The bind resolves to an address other machines can reach, a wildcard ({@code 0.0.0.0}, {@code ::}) included. */
    EXPOSED,
    /** The bind is null, blank, or does not resolve, so whether it is exposed cannot be said. */
    UNKNOWN;

    /**
     * Classifies {@code bind}. Null and the empty string are {@link #UNKNOWN} before any lookup: the
     * JDK resolves both to loopback, which would answer for an address nobody named. Any other value
     * that does not resolve, whitespace included, is {@link #UNKNOWN} too.
     */
    public static BindExposure of(String bind) {
        try {
            return resolve(bind);
        } catch (UnknownHostException | SecurityException e) {
            return UNKNOWN;
        }
    }

    /**
     * As {@link #of(String)}, but a bind other than null or the empty string that does not resolve
     * throws, so the server can refuse to start on it and name the cause, as it always has. The
     * server's start path calls this once and keeps the answer; it is the same lookup
     * {@link #of(String)} makes.
     *
     * @throws UnknownHostException when {@code bind} is neither null nor empty and does not resolve
     */
    static BindExposure resolve(String bind) throws UnknownHostException {
        if (bind == null || bind.isEmpty()) {
            return UNKNOWN;
        }
        return InetAddress.getByName(bind).isLoopbackAddress() ? LOOPBACK : EXPOSED;
    }

    /**
     * Whether the Origin/Host guard enforces for this bind. Its closed answer to an unknown bind is to
     * enforce, so only an exposed bind turns it off.
     */
    public boolean enforcesOriginHost() {
        return this != EXPOSED;
    }

    /**
     * Whether the server may read and write files for a caller (image file paths and URLs, exported
     * files) without the operator allowing it. Its closed answer to an unknown bind is to refuse, so
     * only a loopback bind allows it.
     */
    public boolean allowsServerFileAccess() {
        return this == LOOPBACK;
    }

    /**
     * Whether the preference page warns that this bind exposes the server. Only an exposed bind does:
     * a value the page cannot accept is shown as the address field's own error instead.
     */
    public boolean warnsOperator() {
        return this == EXPOSED;
    }
}
