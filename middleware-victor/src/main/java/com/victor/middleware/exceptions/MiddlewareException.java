package com.victor.middleware.exceptions;

/**
 * Base checked exception for all errors raised by the middleware module.
 *
 * <p>Mirrors the role of {@code br.imd.ufrn.Exceptions.RemoteException} in the
 * reference project, but kept minimal in Phase 1. A {@code code} field
 * (HTTP-style status) will be added in Phase 2 to bridge the transport and
 * gateway layers.</p>
 *
 * <p>Every subclass carries an {@code origin} tag (e.g. {@code "HTTP"},
 * {@code "TCP"}, {@code "UDP"}, {@code "PARSER"}) so that the source of the
 * failure is recoverable from the exception itself, without inspecting stack
 * traces.</p>
 */
public class MiddlewareException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Logical layer / transport that raised the exception. */
    protected final String origin;

    public MiddlewareException(String message) {
        super(message);
        this.origin = "MIDDLEWARE";
    }

    public MiddlewareException(String message, Throwable cause) {
        super(message, cause);
        this.origin = "MIDDLEWARE";
    }

    public MiddlewareException(String origin, String message) {
        super(message);
        this.origin = origin;
    }

    public MiddlewareException(String origin, String message, Throwable cause) {
        super(message, cause);
        this.origin = origin;
    }

    /** @return the logical origin tag (e.g. {@code "HTTP"}, {@code "TCP"}). */
    public String getOrigin() {
        return origin;
    }
}