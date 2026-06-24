package com.victor.middleware.invoker;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.victor.middleware.exceptions.InvocationAbortedException;
import com.victor.middleware.protocol.Message;

/**
 * Package-private implementation of {@link InvocationChain}. Walks the
 * interceptor list in pre-order: each interceptor's {@code around} is
 * called with a child chain pointing at the next interceptor (or the
 * terminal handler at the end of the list).
 *
 * <p>Package-private on purpose: exposed only to {@link InvocationChainTest}
 * in the same package and to {@link InterceptingDispatcher}. Not part of
 * the public SPI surface.</p>
 */
final class InvocationChainImpl implements InvocationChain {

    private final List<InvocationInterceptor> interceptors;
    private final int index;
    private final Function<Message, Message> terminal;

    private InvocationChainImpl(
            List<InvocationInterceptor> interceptors,
            int index,
            Function<Message, Message> terminal) {
        this.interceptors = interceptors;
        this.index = index;
        this.terminal = terminal;
    }

    /**
     * Build the outermost chain for a given interceptor list and terminal.
     * The interceptor list is defensively copied via {@link List#copyOf},
     * so later mutations to the caller's list cannot affect the chain.
     *
     * @throws NullPointerException if {@code interceptors} or {@code terminal} is null
     */
    static InvocationChainImpl of(
            List<InvocationInterceptor> interceptors,
            Function<Message, Message> terminal) {
        Objects.requireNonNull(interceptors, "interceptors");
        Objects.requireNonNull(terminal, "terminal");
        return new InvocationChainImpl(List.copyOf(interceptors), 0, terminal);
    }

    @Override
    public Message proceed(InvocationContext ctx) throws InvocationAbortedException {
        if (index < interceptors.size()) {
            InvocationInterceptor next = interceptors.get(index);
            InvocationChain child = new InvocationChainImpl(interceptors, index + 1, terminal);
            return next.around(ctx, child);
        }
        // Exhausted chain: invoke the terminal handler with a fresh Message
        // reconstructed from the context's command + args.
        return terminal.apply(new Message(ctx.command(), ctx.args()));
    }
}