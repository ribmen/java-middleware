/**
 * Concrete server implementations of
 * {@link com.victor.middleware.spi.ComponentServer}, one per supported
 * transport.
 *
 * <p>All three classes follow the same shape:</p>
 * <ol>
 *     <li>Open the appropriate primitive
 *         ({@link java.net.ServerSocket} for HTTP/TCP,
 *         {@link java.net.DatagramSocket} for UDP) on the requested
 *         port.</li>
 *     <li>Hand the accepted request off to a worker thread from a
 *         bounded {@link java.util.concurrent.ExecutorService}.</li>
 *     <li>Each worker calls
 *         {@link com.victor.middleware.spi.RequestHandler#handle(String)},
 *         which sees the canonical {@code "COMMAND|arg1|arg2|..."} wire
 *         form (framing/unframing is the server's job, not the
 *         handler's).</li>
 *     <li>{@link #stop()} closes the listening socket and shuts the
 *         thread pool down.</li>
 * </ol>
 *
 * <p>Each implementation has its own buffering and timeout policy,
 * tuned to the protocol:</p>
 * <ul>
 *     <li>{@link com.victor.middleware.server.HttpServer} — 50-thread
 *         pool, hand-rolled HTTP/1.1 framing on top of
 *         {@code ServerSocket}.</li>
 *     <li>{@link com.victor.middleware.server.TcpServer} — 500-thread
 *         pool, line-oriented framing.</li>
 *     <li>{@link com.victor.middleware.server.UdpServer} — 500-thread
 *         pool, datagram per request, 16 KB buffer.</li>
 * </ul>
 */
package com.victor.middleware.server;
