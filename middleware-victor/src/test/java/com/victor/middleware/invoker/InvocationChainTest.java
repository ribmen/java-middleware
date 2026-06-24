package com.victor.middleware.invoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.InvocationAbortedException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

class InvocationChainTest {

    /** Builds a minimal context for a Message; used by every test below. */
    private static InvocationContext ctxFor(Message m) {
        return InvocationContext.forMessage(m);
    }

    @Test
    void exhaustedChainCallsTerminalHandler() throws InvocationAbortedException {
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = ctxFor(m);
        AtomicReference<Message> seen = new AtomicReference<>();
        Function<Message, Message> terminal = msg -> {
            seen.set(msg);
            return new Message(Command.OK, List.of("wrote:k|v"));
        };
        InvocationChain chain = InvocationChainImpl.of(List.of(), terminal);

        Message result = chain.proceed(ctx);

        assertEquals(new Message(Command.OK, List.of("wrote:k|v")), result);
        assertEquals(m, seen.get(), "terminal must receive the Message from the context");
    }

    @Test
    void nonEmptyChainCallsInterceptorsInOrder() throws InvocationAbortedException {
        Message m = new Message(Command.READ, List.of("k"));
        InvocationContext ctx = ctxFor(m);
        List<String> log = new ArrayList<>();

        InvocationInterceptor first = (c, ch) -> {
            log.add("first:pre");
            Message r = ch.proceed(c);
            log.add("first:post:" + r.command().wireForm() + "/" + r.args());
            return r;
        };
        InvocationInterceptor second = (c, ch) -> {
            log.add("second:pre");
            Message r = ch.proceed(c);
            log.add("second:post:" + r.command().wireForm() + "/" + r.args());
            return r;
        };

        InvocationChain chain = InvocationChainImpl.of(List.of(first, second),
                msg -> {
                    log.add("terminal");
                    return new Message(Command.OK, List.of("read:k"));
                });

        Message result = chain.proceed(ctx);

        assertEquals(new Message(Command.OK, List.of("read:k")), result);
        // Pre-order recursion: first wraps second wraps terminal.
        assertEquals(
                List.of(
                        "first:pre",
                        "second:pre",
                        "terminal",
                        "second:post:OK/[read:k]",
                        "first:post:OK/[read:k]"),
                log);
    }

    @Test
    void interceptorCanShortCircuitByNotCallingProceed() throws InvocationAbortedException {
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = ctxFor(m);
        AtomicReference<Boolean> terminalCalled = new AtomicReference<>(false);

        InvocationInterceptor shortCircuit = (c, ch) ->
                new Message(Command.OK, List.of("intercepted:" + c.traceId()));

        InvocationChain chain = InvocationChainImpl.of(List.of(shortCircuit),
                msg -> {
                    terminalCalled.set(true);
                    return new Message(Command.OK, List.of("should-not-happen"));
                });

        Message result = chain.proceed(ctx);

        assertEquals(new Message(Command.OK, List.of("intercepted:" + ctx.traceId())), result);
        assertEquals(Boolean.FALSE, terminalCalled.get(),
                "terminal must NOT be called when interceptor skips proceed");
    }

    @Test
    void interceptorCanWrapResultFromProceed() throws InvocationAbortedException {
        Message m = new Message(Command.READ, List.of("k"));
        InvocationContext ctx = ctxFor(m);

        InvocationInterceptor wrapper = (c, ch) -> {
            Message inner = ch.proceed(c);
            return new Message(inner.command(),
                    List.of("WRAPPED(" + inner.firstArg() + ")"));
        };

        InvocationChain chain = InvocationChainImpl.of(List.of(wrapper),
                msg -> new Message(Command.OK, List.of("read:k")));

        assertEquals(new Message(Command.OK, List.of("WRAPPED(read:k)")),
                chain.proceed(ctx));
    }

    @Test
    void abortedExceptionPropagatesThroughChain() throws InvocationAbortedException {
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = ctxFor(m);

        InvocationInterceptor aborter = (c, ch) -> {
            throw new InvocationAbortedException("nope");
        };

        InvocationChain chain = InvocationChainImpl.of(List.of(aborter),
                msg -> new Message(Command.OK, List.of("ok")));

        InvocationAbortedException ex = assertThrows(InvocationAbortedException.class,
                () -> chain.proceed(ctx));
        assertEquals("nope", ex.getMessage());
    }

    @Test
    void chainImplThrowsOnNullInterceptorsList() {
        Function<Message, Message> terminal = msg -> new Message(Command.OK, List.of("x"));
        assertThrows(NullPointerException.class,
                () -> InvocationChainImpl.of(null, terminal));
    }

    @Test
    void chainImplThrowsOnNullTerminal() {
        assertThrows(NullPointerException.class,
                () -> InvocationChainImpl.of(List.of(), null));
    }

    @Test
    void chainImplIsImmutableSnapshotOfInterceptors() throws InvocationAbortedException {
        // Mutating the source list after construction must not affect chain behavior.
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = ctxFor(m);
        List<InvocationInterceptor> source = new ArrayList<>();
        InvocationInterceptor a = (c, ch) -> {
            Message inner = ch.proceed(c);
            return new Message(inner.command(), List.of("A:" + inner.firstArg()));
        };
        InvocationInterceptor b = (c, ch) -> {
            Message inner = ch.proceed(c);
            return new Message(inner.command(), List.of("B:" + inner.firstArg()));
        };
        source.add(a);

        InvocationChain chain = InvocationChainImpl.of(source, msg -> new Message(Command.OK, List.of("term")));
        // Mutate source after construction
        source.add(b);

        // The chain must only call 'a' (the snapshot it took at construction).
        Message result = chain.proceed(ctx);
        assertEquals(new Message(Command.OK, List.of("A:term")), result);
    }

    /** Sentinel used to assert interceptor sees the same context it was given. */
    @Test
    void interceptorReceivesSameContextInstance() throws InvocationAbortedException {
        Message m = new Message(Command.READ, List.of("k"));
        InvocationContext ctx = ctxFor(m);
        AtomicReference<InvocationContext> seen = new AtomicReference<>();

        InvocationInterceptor capture = (c, ch) -> {
            seen.set(c);
            return ch.proceed(c);
        };

        InvocationChain chain = InvocationChainImpl.of(List.of(capture),
                msg -> new Message(Command.OK, List.of("ok")));
        chain.proceed(ctx);

        assertSame(ctx, seen.get(), "interceptor must receive the context it was called with");
    }
}