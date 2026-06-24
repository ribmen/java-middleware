package com.victor.middleware.invoker;

import com.victor.middleware.exceptions.InvocationAbortedException;
import com.victor.middleware.protocol.Message;

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
 * ({@code Message dispatch(Message) → Message}): interceptors transform
 * or wrap the typed result as it flows back up the chain. There is no
 * longer a string round-trip inside the in-process dispatcher — the
 * marshaller is the only layer that touches wire form, and it lives
 * outside this SPI.</p>
 */
@FunctionalInterface
public interface InvocationInterceptor {
    Message around(InvocationContext ctx, InvocationChain chain)
            throws InvocationAbortedException;
}