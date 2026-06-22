/**
 * Reusable transport and dispatch library extracted from the original
 * {@code kvstore} module.
 *
 * <p>The module is intentionally split into small, layered packages:</p>
 * <ul>
 *     <li>{@link com.victor.middleware.spi} — protocol-agnostic interfaces
 *         that servers and clients implement.</li>
 *     <li>{@link com.victor.middleware.protocol} — the wire envelope and
 *         its codec.</li>
 *     <li>{@link com.victor.middleware.exceptions} — the checked
 *         exception hierarchy every other package throws.</li>
 *     <li>{@link com.victor.middleware.server},
 *         {@link com.victor.middleware.client} — the three concrete
 *         transports (HTTP, TCP, UDP), each behind its own SPI.</li>
 *     <li>{@link com.victor.middleware.factory} — single switchboard
 *         that maps a {@link com.victor.middleware.protocol.CommunicationType}
 *         to a concrete client or server.</li>
 *     <li>{@link com.victor.middleware.util} — small value types
 *         (e.g. {@link com.victor.middleware.util.ComponentInfo}).</li>
 *     <li>{@link com.victor.middleware.invoker} — the in-process
 *         {@code Dispatcher} that routes a parsed {@code Message} to a
 *         registered handler.</li>
 *     <li>{@link com.victor.middleware.registry} — the worker registry
 *         used by the gateway, including heartbeat bookkeeping.</li>
 * </ul>
 *
 * <p>Everything in this module speaks raw
 * {@link java.net.ServerSocket}/{@link java.net.Socket}/{@link java.net.DatagramSocket}
 * — no HTTP framework, no JSON, no connection pool — to stay faithful to
 * the "Sockets" requirement of the course assignment.</p>
 */
package com.victor.middleware;
