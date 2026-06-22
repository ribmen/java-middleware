/**
 * Concrete client implementations of
 * {@link com.victor.middleware.spi.ComponentClient}, one per supported
 * transport.
 *
 * <p>Each client is intentionally one-shot: a fresh
 * {@link java.net.Socket} or {@link java.net.DatagramSocket} per
 * {@link com.victor.middleware.spi.ComponentClient#send(String, int, String)
 * send()} call. There is no connection pool, no keep-alive, no
 * retry — the gateway owns that policy. Keeping clients minimal makes
 * them easy to test (the unit tests use a real
 * {@link java.net.ServerSocket}/{@link java.net.DatagramSocket} double
 * running on the same JVM) and easy to swap.</p>
 *
 * <p>All three classes translate the underlying
 * {@link java.io.IOException} into the typed hierarchy from
 * {@link com.victor.middleware.exceptions}:</p>
 * <ul>
 *     <li>Connection refused / timeout →
 *         {@link com.victor.middleware.exceptions.ConnectionException}.</li>
 *     <li>Malformed response header →
 *         {@link com.victor.middleware.exceptions.ProtocolException}.</li>
 * </ul>
 */
package com.victor.middleware.client;
