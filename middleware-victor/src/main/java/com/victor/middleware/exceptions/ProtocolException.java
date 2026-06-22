package com.victor.middleware.exceptions;

/**
 * Signals malformed wire input: bad HTTP request line, empty request,
 * non-numeric {@code Content-Length}, payload larger than the UDP buffer,
 * etc.
 *
 * <p>Servers may still translate this into an error response on the wire
 * (e.g. HTTP 400), but the exception is what the codec throws internally so
 * the failure is testable and reportable.</p>
 */
public class ProtocolException extends MiddlewareException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String origin, String message) {
        super(origin, message);
    }

    public ProtocolException(String origin, String message, Throwable cause) {
        super(origin, message, cause);
    }
}