package com.victor.middleware.invoker;

import com.victor.middleware.exceptions.InvocationAbortedException;

/**
 * Built-in interceptor that logs every invocation's IN, OUT, and ABORT
 * events to {@link System#out}. The trace id is preserved across both
 * log lines so log aggregators can correlate an IN with its OUT/ABORT.
 *
 * <p>Log shape:</p>
 * <ul>
 *   <li>{@code [INVOKER] IN  trace=<uuid> cmd=<CMD> args=[...]}</li>
 *   <li>{@code [INVOKER] OUT trace=<uuid> elapsed_ms=<n> result=<string>}</li>
 *   <li>{@code [INVOKER] OUT trace=<uuid> ABORTED msg=<string>}</li>
 * </ul>
 */
public final class LoggingInterceptor implements InvocationInterceptor {

    @Override
    public String around(InvocationContext ctx, InvocationChain chain)
            throws InvocationAbortedException {
        System.out.println("[INVOKER] IN  trace=" + ctx.traceId()
                + " cmd=" + ctx.command().wireForm()
                + " args=" + ctx.args());
        try {
            String result = chain.proceed(ctx);
            System.out.println("[INVOKER] OUT trace=" + ctx.traceId()
                    + " elapsed_ms=" + (ctx.elapsedNanos() / 1_000_000)
                    + " result=" + result);
            return result;
        } catch (InvocationAbortedException e) {
            System.out.println("[INVOKER] OUT trace=" + ctx.traceId()
                    + " ABORTED msg=" + e.getMessage());
            throw e;
        }
    }
}
