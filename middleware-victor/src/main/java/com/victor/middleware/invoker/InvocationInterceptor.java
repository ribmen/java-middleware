package com.victor.middleware.invoker;

import com.victor.middleware.exceptions.InvocationAbortedException;

/**
 * A single cross-cutting concern that wraps an invocation in the dispatcher.
 *
 * <p>The interceptor decides whether and when to call {@link InvocationChain#proceed}.
 * It can:</p>
 * <ul>
 *   <li>Call {@code proceed} once and return (or transform) the terminal result.</li>
 *   <li>Skip {@code proceed} entirely and return its own result (no-op chain).</li>
 *   <li>Throw {@link InvocationAbortedException} to abort with a wire-form error.</li>
 * </ul>
 *
 * <p>The signature mirrors the dispatcher contract
 * ({@code String dispatch(Message) → String}): interceptors transform or
 * wrap the result string as it flows back up the chain.</p>
 */
@FunctionalInterface
public interface InvocationInterceptor {
    String around(InvocationContext ctx, InvocationChain chain)
            throws InvocationAbortedException;
}
