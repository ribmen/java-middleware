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
        Function<Message, String> terminal = msg -> {
            seen.set(msg);
            return "wrote:k|v";
        };
        InvocationChain chain = InvocationChainImpl.of(List.of(), terminal);

        String result = chain.proceed(ctx);

        assertEquals("wrote:k|v", result);
        assertEquals(m, seen.get(), "terminal must receive the Message from the context");
    }

    @Test
    void nonEmptyChainCallsInterceptorsInOrder() throws InvocationAbortedException {
        Message m = new Message(Command.READ, List.of("k"));
        InvocationContext ctx = ctxFor(m);
        List<String> log = new ArrayList<>();

        InvocationInterceptor first = (c, ch) -> {
            log.add("first:pre");
            String r = ch.proceed(c);
            log.add("first:post:" + r);
            return r;
        };
        InvocationInterceptor second = (c, ch) -> {
            log.add("second:pre");
            String r = ch.proceed(c);
            log.add("second:post:" + r);
            return r;
        };

        InvocationChain chain = InvocationChainImpl.of(List.of(first, second),
                msg -> {
                    log.add("terminal");
                    return "read:k";
                });

        String result = chain.proceed(ctx);

        assertEquals("read:k", result);
        // Pre-order recursion: first wraps second wraps terminal.
        assertEquals(
                List.of("first:pre", "second:pre", "terminal", "second:post:read:k", "first:post:read:k"),
                log);
    }

    @Test
    void interceptorCanShortCircuitByNotCallingProceed() throws InvocationAbortedException {
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = ctxFor(m);
        AtomicReference<Boolean> terminalCalled = new AtomicReference<>(false);

        InvocationInterceptor shortCircuit = (c, ch) -> "intercepted:" + c.traceId();

        InvocationChain chain = InvocationChainImpl.of(List.of(shortCircuit),
                msg -> {
                    terminalCalled.set(true);
                    return "should-not-happen";
                });

        String result = chain.proceed(ctx);

        assertEquals("intercepted:" + ctx.traceId(), result);
        assertEquals(Boolean.FALSE, terminalCalled.get(),
                "terminal must NOT be called when interceptor skips proceed");
    }

    @Test
    void interceptorCanWrapResultFromProceed() throws InvocationAbortedException {
        Message m = new Message(Command.READ, List.of("k"));
        InvocationContext ctx = ctxFor(m);

        InvocationInterceptor wrapper = (c, ch) -> "WRAPPED(" + ch.proceed(c) + ")";

        InvocationChain chain = InvocationChainImpl.of(List.of(wrapper),
                msg -> "read:k");

        assertEquals("WRAPPED(read:k)", chain.proceed(ctx));
    }

    @Test
    void abortedExceptionPropagatesThroughChain() throws InvocationAbortedException {
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = ctxFor(m);

        InvocationInterceptor aborter = (c, ch) -> {
            throw new InvocationAbortedException("nope");
        };

        InvocationChain chain = InvocationChainImpl.of(List.of(aborter),
                msg -> "ok");

        InvocationAbortedException ex = assertThrows(InvocationAbortedException.class,
                () -> chain.proceed(ctx));
        assertEquals("nope", ex.getMessage());
    }

    @Test
    void chainImplThrowsOnNullInterceptorsList() {
        Function<Message, String> terminal = msg -> "x";
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
        InvocationInterceptor a = (c, ch) -> "A:" + ch.proceed(c);
        InvocationInterceptor b = (c, ch) -> "B:" + ch.proceed(c);
        source.add(a);

        InvocationChain chain = InvocationChainImpl.of(source, msg -> "term");
        // Mutate source after construction
        source.add(b);

        // The chain must only call 'a' (the snapshot it took at construction).
        String result = chain.proceed(ctx);
        assertEquals("A:term", result);
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

        InvocationChain chain = InvocationChainImpl.of(List.of(capture), msg -> "ok");
        chain.proceed(ctx);

        assertSame(ctx, seen.get(), "interceptor must receive the context it was called with");
    }
}
