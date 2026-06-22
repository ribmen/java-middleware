/**
 * Checked exception hierarchy raised by every layer of the middleware.
 *
 * <p>All exceptions extend
 * {@link com.victor.middleware.exceptions.MiddlewareException}, which
 * carries an {@code origin} tag ({@code "HTTP"}, {@code "TCP"},
 * {@code "UDP"}, {@code "PARSER"}, {@code "REGISTRY"}, ...). The tag is
 * the only thing the application layer needs to read to know which
 * transport failed — there is no need to inspect stack traces or
 * unwrap causes.</p>
 *
 * <p>The three concrete subclasses each correspond to a single failure
 * category:</p>
 * <ul>
 *     <li>{@link com.victor.middleware.exceptions.ConnectionException} —
 *         the wire side is unreachable: connection refused, read
 *         timeout, host unknown.</li>
 *     <li>{@link com.victor.middleware.exceptions.ProtocolException} —
 *         the wire side is reachable but speaks nonsense: malformed
 *         HTTP request line, non-numeric {@code Content-Length},
 *         payload larger than the buffer.</li>
 *     <li>{@link com.victor.middleware.exceptions.NoAvailableNodeException} —
 *         the registry has no healthy worker to forward a request to.
 *         Surfaced by the gateway, not by transport.</li>
 * </ul>
 */
package com.victor.middleware.exceptions;
