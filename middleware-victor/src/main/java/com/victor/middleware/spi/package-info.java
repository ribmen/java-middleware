/**
 * Service Provider Interfaces (SPIs) for the middleware transport.
 *
 * <p>These three interfaces are the seam between the transport layer
 * (which knows about sockets and wire framing) and the application
 * layer (which knows about commands and business logic). Everything in
 * {@link com.victor.middleware.server} and
 * {@link com.victor.middleware.client} implements these; the
 * {@link com.victor.middleware.factory.CommunicationFactory} is the only
 * place that instantiates the concrete classes.</p>
 *
 * <p>The interfaces are deliberately tiny:</p>
 * <ul>
 *     <li>{@link com.victor.middleware.spi.ComponentServer#start(int, com.victor.middleware.spi.RequestHandler)
 *         ComponentServer.start(port, handler)} binds to {@code port}
 *         and dispatches each incoming request to {@code handler}.</li>
 *     <li>{@link com.victor.middleware.spi.ComponentClient#send(String, int, String)
 *         ComponentClient.send(host, port, request)} opens a connection,
 *         writes the request, and returns the reply as a single string.</li>
 *     <li>{@link com.victor.middleware.spi.RequestHandler#handle(String)
 *         RequestHandler.handle(request)} is the
 *         {@link java.lang.FunctionalInterface} business layer — it
 *         receives the canonical {@code "COMMAND|arg1|arg2|..."} wire
 *         form and returns the canonical response string.</li>
 * </ul>
 *
 * <p>Because every implementation is protocol-agnostic at this seam,
 * swapping HTTP for TCP for UDP is a single-line factory change.</p>
 */
package com.victor.middleware.spi;
