/**
 * In-process message router and interceptor chain.
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
 *
 * <h2>Interceptor chain (Phase 5A)</h2>
 *
 * <p>Cross-cutting concerns can be wrapped around a {@code Dispatcher}
 * via {@link com.victor.middleware.invoker.InterceptingDispatcher}.
 * Each invocation runs through an ordered list of
 * {@link com.victor.middleware.invoker.InvocationInterceptor}s,
 * sharing an immutable
 * {@link com.victor.middleware.invoker.InvocationContext} (trace id,
 * start time, command, args). Interceptors recurse via
 * {@link com.victor.middleware.invoker.InvocationChain#proceed}; aborts
 * signal themselves by throwing
 * {@link com.victor.middleware.exceptions.InvocationAbortedException}.
 * A built-in {@link com.victor.middleware.invoker.LoggingInterceptor}
 * is provided for visibility during development.</p>
 */
package com.victor.middleware.invoker;
