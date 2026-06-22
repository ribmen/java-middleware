/**
 * In-process message router.
 *
 * <p>{@link com.victor.middleware.invoker.Dispatcher} is the seed of
 * the future annotation-driven invoker. Today it is an
 * {@link java.util.EnumMap} keyed by
 * {@link com.victor.middleware.protocol.Command}; tomorrow the same
 * class can back handlers discovered via reflection on
 * {@code @Handler(WRITE)} annotations.</p>
 *
 * <p>Two contracts are pinned by tests and worth understanding:</p>
 * <ul>
 *     <li>Unknown commands return a wire-form error string instead of
 *         throwing, so the response is always a well-formed
 *         {@code Message} on the wire.</li>
 *     <li>Handler exceptions are caught and converted to the same
 *         wire-form error shape — a thrown {@code RuntimeException}
 *         never becomes a silent connection drop.</li>
 * </ul>
 */
package com.victor.middleware.invoker;
