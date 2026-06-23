package com.victor.middleware.spi;

import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.protocol.Message;

/**
 * Typed counterpart to {@link RequestHandler}. Operates on
 * {@link Message} values in and out, so handlers that consume the
 * Marshaller decorator layer don't have to manually parse wire form.
 *
 * <p>Prefer this over {@link RequestHandler} for new code. The legacy
 * SPI is kept (now {@code @Deprecated}) for the 35 existing tests
 * that depend on its string-in/string-out contract.</p>
 */
@FunctionalInterface
public interface TypedRequestHandler {

    /**
     * Handle a single typed request.
     *
     * @param request parsed request
     * @return response (may carry an error command — handlers decide)
     * @throws MiddlewareException to signal unrecoverable failure;
     *         the dispatcher translates thrown exceptions to wire-form
     *         {@code "ERRO:"} strings.
     */
    Message handle(Message request) throws MiddlewareException;
}
