package com.victor.middleware.spi;

import com.victor.middleware.exceptions.MiddlewareException;

/**
 * Single-method functional interface used by every {@link ComponentServer}.
 *
 * <p>Each protocol server invokes {@link #handle(String)} on every incoming
 * request and writes the returned string back to the wire. By design,
 * protocol framing and unframing live in the server — not in the handler. The
 * handler sees exactly the canonical wire form
 * {@code "COMMAND|arg1|arg2|..."}.</p>
 *
 * <p>Handlers may throw {@link MiddlewareException} to signal validation or
 * upstream failures; the server is responsible for translating that into the
 * appropriate wire-level error.</p>
 */
@FunctionalInterface
public interface RequestHandler {

    /**
     * Handle a single request.
     *
     * @param request canonical wire-form request string
     * @return canonical wire-form response string
     * @throws MiddlewareException if the handler cannot complete the request
     */
    String handle(String request) throws MiddlewareException;
}