package net.vheerden.archi.mcp.server;

import java.net.BindException;

/**
 * Exception thrown when the MCP server fails to start.
 * Provides an error code for UI consumption (Story 1.4).
 */
public class ServerStartException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Port is already bound by another process. */
    public static final String PORT_IN_USE = "PORT_IN_USE";

    /** Cannot bind to the specified network address. */
    public static final String INVALID_BIND_ADDRESS = "INVALID_BIND_ADDRESS";

    /** TLS/keystore configuration is invalid. */
    public static final String INVALID_TLS_CONFIG = "INVALID_TLS_CONFIG";

    /** Generic Jetty startup failure. */
    public static final String SERVER_START_FAILED = "SERVER_START_FAILED";

    private final String errorCode;

    public ServerStartException(String message, Throwable cause) {
        super(message, cause);
        if (cause instanceof BindException) {
            this.errorCode = PORT_IN_USE;
        } else {
            this.errorCode = SERVER_START_FAILED;
        }
    }

    public ServerStartException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns a structured error code for UI display.
     *
     * @return one of {@link #PORT_IN_USE}, {@link #INVALID_BIND_ADDRESS},
     *         {@link #INVALID_TLS_CONFIG}, or {@link #SERVER_START_FAILED}
     */
    public String getErrorCode() {
        return errorCode;
    }
}
