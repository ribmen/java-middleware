package com.victor.middleware.exceptions;

/**
 * Raised by the Marshaller layer when wire-format conversion fails.
 *
 * <p>Carries an HTTP-style {@code statusCode} (400 for malformed input,
 * 500 for encoder failures) so the {@link com.victor.middleware.server.HttpServer}
 * can translate it to a response code without inspecting the message.
 * Origin is fixed to {@code "MARSHALLER"}.</p>
 */
public class MarshalException extends MiddlewareException {

    private static final long serialVersionUID = 1L;

    private static final String ORIGIN = "MARSHALLER";

    private final int statusCode;

    public MarshalException(String message, int statusCode) {
        super(ORIGIN, message);
        validateStatus(statusCode);
        this.statusCode = statusCode;
    }

    public MarshalException(String message, int statusCode, Throwable cause) {
        super(ORIGIN, message, cause);
        validateStatus(statusCode);
        this.statusCode = statusCode;
    }

    /** @return the HTTP status code (400 or 500) for this marshalling failure. */
    public int statusCode() {
        return statusCode;
    }

    private static void validateStatus(int code) {
        if (code != 400 && code != 500) {
            throw new IllegalArgumentException(
                    "MarshalException only supports statusCode 400 or 500, got: " + code);
        }
    }
}
