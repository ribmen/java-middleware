package com.victor.middleware.invoker;

import java.util.List;
import java.util.UUID;

import com.victor.middleware.protocol.Message;

/**
 * Immutable per-invocation state passed through the interceptor chain.
 *
 * <p>A fresh instance is created for every {@code dispatch} call. Interceptors
 * receive the same context object; they cannot mutate it (records are
 * immutable) but they can construct a new {@code InvocationContext} via
 * the canonical constructor if they need to add information for downstream
 * interceptors.</p>
 *
 * <p>{@code traceId} is a UUID v4 string suitable for log correlation.
 * {@code startNanos} is the value of {@link System#nanoTime()} at the
 * moment the wrapper entered dispatch; {@link #elapsedNanos()} returns
 * the delta against the current nanoTime.</p>
 */
public record InvocationContext(
        String traceId,
        long startNanos,
        com.victor.middleware.protocol.Command command,
        List<String> args) {

    /**
     * Build a context for a freshly-arrived message. Generates a new trace id
     * and stamps {@code startNanos} from {@link System#nanoTime()}. The args
     * list is defensively copied to match {@link Message#args()} semantics.
     */
    public static InvocationContext forMessage(Message m) {
        return new InvocationContext(
                UUID.randomUUID().toString(),
                System.nanoTime(),
                m.command(),
                List.copyOf(m.args()));
    }

    /** Nanoseconds elapsed since {@code startNanos}, per {@link System#nanoTime()}. */
    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }
}
