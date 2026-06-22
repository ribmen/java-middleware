package com.victor.middleware.invoker;

import com.victor.middleware.exceptions.InvocationAbortedException;

/**
 * Recursion primitive for an interceptor chain. An interceptor calls
 * {@link #proceed} to advance the chain one step; the wrapper at the end
 * of the chain invokes the terminal handler.
 *
 * <p>Each call to {@code proceed} returns the terminal handler's result
 * string (or the result produced by an upstream interceptor that chose
 * not to call {@code proceed}). Interceptors on the way up see the result
 * and can transform or wrap it.</p>
 *
 * <p>Interceptors should call {@code proceed} at most once per
 * {@code around} invocation. Calling it twice or never has undefined
 * behavior (the latter means the chain is short-circuited and the
 * interceptor's own return value is what the wrapper sees).</p>
 */
public interface InvocationChain {
    String proceed(InvocationContext ctx) throws InvocationAbortedException;
}
