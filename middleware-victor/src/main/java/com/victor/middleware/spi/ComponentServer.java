package com.victor.middleware.spi;

import com.victor.middleware.exceptions.MiddlewareException;

/**
 * Strategy for binding a socket on a port and dispatching every incoming
 * request to a {@link RequestHandler}. Used by both the Gateway and the
 * Worker component.
 *
 * <p>Implementations must be non-blocking on {@link #start(int, RequestHandler)}
 * — the accept loop runs on its own thread(s), and {@code start} returns as
 * soon as the server is bound.</p>
 */
public interface ComponentServer {

    /**
     * Bind on {@code port} and begin accepting connections. The accept loop
     * runs until {@link #stop()} is invoked.
     *
     * @param port    TCP/UDP port to bind on
     * @param handler callback invoked once per incoming request
     * @throws MiddlewareException if the socket cannot be bound (e.g. the
     *         port is already in use)
     */
    void start(int port, RequestHandler handler) throws MiddlewareException;

    /**
     * Stop the accept loop, close the listening socket and release the
     * thread pool. Idempotent: a second call is a no-op.
     */
    void stop();
}