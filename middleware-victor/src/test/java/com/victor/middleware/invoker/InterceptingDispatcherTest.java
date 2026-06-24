package com.victor.middleware.invoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.InvocationAbortedException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

class InterceptingDispatcherTest {

    /** Build a Dispatcher pre-bound to a single WRITE handler that returns a fixed typed value. */
    private static Dispatcher dispatcherStoringLastMessage(Message response, AtomicReference<Message> seen) {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> {
            seen.set(msg);
            return response;
        });
        return d;
    }

    @Test
    void passesThroughToInnerDispatcher() throws InvocationAbortedException {
        AtomicReference<Message> seen = new AtomicReference<>();
        Dispatcher inner = dispatcherStoringLastMessage(
                new Message(Command.OK, List.of("wrote:k|v")), seen);

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of());
        Message msg = new Message(Command.WRITE, List.of("k", "v"));
        Message result = wrapper.dispatch(msg);

        assertEquals(new Message(Command.OK, List.of("wrote:k|v")), result);
        assertEquals(msg, seen.get(), "inner dispatcher must have been called");
    }

    @Test
    void invokesInterceptorsInOrder() throws InvocationAbortedException {
        Dispatcher inner = new Dispatcher();
        inner.register(Command.READ, m -> new Message(Command.OK, List.of("read:" + m.firstArg())));
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

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of(first, second));
        Message result = wrapper.dispatch(new Message(Command.READ, List.of("k")));

        assertEquals(new Message(Command.OK, List.of("read:k")), result);
        assertEquals(
                List.of(
                        "first:pre",
                        "second:pre",
                        "second:post:OK/[read:k]",
                        "first:post:OK/[read:k]"),
                log);
    }

    @Test
    void abortedInvocationReturnsTypedError() throws InvocationAbortedException {
        Dispatcher inner = new Dispatcher();
        inner.register(Command.WRITE, m -> new Message(Command.OK, List.of("should-not-happen")));

        InvocationInterceptor aborter = (c, ch) -> {
            throw new InvocationAbortedException("auth failed");
        };

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of(aborter));
        Message result = wrapper.dispatch(new Message(Command.WRITE, List.of("k", "v")));

        assertSame(Command.UNKNOWN, result.command());
        assertEquals(List.of("ERRO: auth failed"), result.args());
    }

    @Test
    void runtimeExceptionInInterceptorSurfacedAsTypedError() throws InvocationAbortedException {
        Dispatcher inner = new Dispatcher();
        inner.register(Command.WRITE, m -> new Message(Command.OK, List.of("should-not-happen")));

        InvocationInterceptor exploder = (c, ch) -> {
            throw new IllegalStateException("boom");
        };

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of(exploder));
        Message result = wrapper.dispatch(new Message(Command.WRITE, List.of("k", "v")));

        assertSame(Command.UNKNOWN, result.command());
        String arg = result.args().get(0);
        assertTrue(arg.startsWith("ERRO:"),
                "runtime exception must surface as a typed error, got: " + arg);
        assertTrue(arg.contains("IllegalStateException"),
                "error must name the exception type, got: " + arg);
        assertTrue(arg.contains("boom"),
                "error must include the exception message, got: " + arg);
    }

    @Test
    void emptyInterceptorListBehavesLikeBareDispatcher() throws InvocationAbortedException {
        Dispatcher inner = new Dispatcher();
        inner.register(Command.READ, m -> new Message(Command.OK, List.of("read:" + m.firstArg())));

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of());
        Message result = wrapper.dispatch(new Message(Command.READ, List.of("foo")));

        assertEquals(new Message(Command.OK, List.of("read:foo")), result);
    }

    @Test
    void dispatcherPropagatesInnerUnknownCommandError() throws InvocationAbortedException {
        // The wrapper should NOT swallow the inner dispatcher's typed error for unknown commands.
        Dispatcher inner = new Dispatcher(); // no handlers
        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of());
        Message result = wrapper.dispatch(new Message(Command.READ, List.of("foo")));

        assertSame(Command.UNKNOWN, result.command());
        assertTrue(result.args().get(0).startsWith("ERRO:"),
                "got: " + result.args());
    }

    @Test
    void constructorRejectsNullInner() {
        assertThrows(NullPointerException.class,
                () -> new InterceptingDispatcher(null, List.of()));
    }

    @Test
    void constructorRejectsNullInterceptors() {
        Dispatcher inner = new Dispatcher();
        assertThrows(NullPointerException.class,
                () -> new InterceptingDispatcher(inner, null));
    }

    @Test
    void constructorTakesImmutableSnapshotOfInterceptors() throws InvocationAbortedException {
        Dispatcher inner = new Dispatcher();
        inner.register(Command.WRITE, m -> new Message(Command.OK, List.of("ok")));
        List<InvocationInterceptor> source = new ArrayList<>();
        InvocationInterceptor a = (c, ch) -> {
            ch.proceed(c);
            return new Message(Command.OK, List.of("A"));
        };
        source.add(a);

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, source);
        // Mutate source after construction; the wrapper must keep its snapshot.
        source.add((c, ch) -> {
            ch.proceed(c);
            return new Message(Command.OK, List.of("B"));
        });

        Message result = wrapper.dispatch(new Message(Command.WRITE, List.of("k", "v")));
        assertEquals(new Message(Command.OK, List.of("A")), result,
                "wrapper must only invoke interceptors in its original snapshot");
    }
}