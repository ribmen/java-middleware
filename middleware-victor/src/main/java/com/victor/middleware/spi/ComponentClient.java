package com.victor.middleware.spi;

import com.victor.middleware.exceptions.MiddlewareException;

/**
 * Strategy for sending a wire string to a {@code (host, port)} and reading
 * one wire string back. Protocol-agnostic: the HTTP, TCP and UDP
 * implementations all conform to this SPI.
 *
 * <p>Used by the Gateway to talk to workers and by every
 * {@link com.victor.middleware.spi.ComponentServer} registration/heartbeat
 * flow.</p>
 */
public interface ComponentClient {

    /**
     * Send a request and block until a response arrives (or the
     * implementation-specific timeout fires).
     *
     * @param host    destination host (DNS name or IP)
     * @param port    destination port
     * @param request canonical wire-form request string
     * @return canonical wire-form response string
     * @throws MiddlewareException on socket failure, timeout, or malformed
     *         response
     */
    String send(String host, int port, String request) throws MiddlewareException;
}