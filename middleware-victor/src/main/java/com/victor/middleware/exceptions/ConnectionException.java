package com.victor.middleware.exceptions;

/**
 * Signals socket-level failures (connection refused, read/write timeout,
 * premature EOF, host unknown, etc.).
 *
 * <p>This is the exception type clients and servers throw when the underlying
 * {@link java.io.IOException} chain cannot complete a request. Callers see a
 * single exception type instead of the raw JDK exception zoo.</p>
 */
public class ConnectionException extends MiddlewareException {

    private static final long serialVersionUID = 1L;

    public ConnectionException(String origin, String message) {
        super(origin, message);
    }

    public ConnectionException(String origin, String message, Throwable cause) {
        super(origin, message, cause);
    }
}