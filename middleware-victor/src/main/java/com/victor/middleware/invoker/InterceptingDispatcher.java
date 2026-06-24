package com.victor.middleware.invoker;

import java.util.List;
import java.util.Objects;

import com.victor.middleware.exceptions.InvocationAbortedException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

/**
 * Wraps a {@link Dispatcher} with a chain of {@link InvocationInterceptor}s.
 *
 * <p>Same external contract as {@code Dispatcher.dispatch}: takes a
 * {@link Message} and returns a typed {@link Message} response. The
 * interceptor chain runs in pre-order around the inner dispatcher.</p>
 *
 * <p>Two exception paths convert into typed error
 * {@code Message(UNKNOWN, ["ERRO: ..."])} values, matching
 * {@link Dispatcher#dispatch(Message)}'s error shape:</p>
 * <ul>
 *   <li>{@link InvocationAbortedException} → {@code UNKNOWN}-command
 *       with arg {@code "ERRO: <message>"}</li>
 *   <li>Any other {@link RuntimeException} thrown by an interceptor →
 *       {@code UNKNOWN}-command with arg
 *       {@code "ERRO: Interceptor lançou <Type>: <message>"}</li>
 * </ul>
 *
 * <p>The inner dispatcher's own exception handling is untouched: handler
 * {@code RuntimeException}s are still translated by the inner
 * {@code Dispatcher} before they reach this wrapper.</p>
 *
 * <p>Constructor-only interceptor registration (immutable). The
 * {@code interceptors} list is a defensive {@link List#copyOf}.</p>
 */
public final class InterceptingDispatcher {

    private final Dispatcher inner;
    private final List<InvocationInterceptor> interceptors;

    public InterceptingDispatcher(Dispatcher inner, List<InvocationInterceptor> interceptors) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.interceptors = List.copyOf(Objects.requireNonNull(interceptors, "interceptors"));
    }

    public Message dispatch(Message msg) {
        InvocationContext ctx = InvocationContext.forMessage(msg);
        InvocationChain chain = InvocationChainImpl.of(interceptors, inner::dispatch);
        try {
            return chain.proceed(ctx);
        } catch (InvocationAbortedException e) {
            return new Message(Command.UNKNOWN, java.util.List.of("ERRO: " + e.getMessage()));
        } catch (RuntimeException e) {
            return new Message(Command.UNKNOWN, java.util.List.of(
                    "ERRO: Interceptor lançou "
                            + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }
}