/**
 * Small value types shared across the middleware.
 *
 * <p>The current occupant is
 * {@link com.victor.middleware.util.ComponentInfo}, a triple of
 * {@link java.net.InetAddress}, port, and last-heartbeat timestamp used
 * by the gateway's worker registry. The class is deliberately
 * minimal: no equality tricks, no caching — just the fields and a
 * canonical {@link com.victor.middleware.util.ComponentInfo#getId()
 * "host:port"} string for logging and round-robin bookkeeping.</p>
 */
package com.victor.middleware.util;
